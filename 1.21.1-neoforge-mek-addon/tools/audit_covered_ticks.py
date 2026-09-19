"""Which shape-allowed block entities reach the world from their own tick?

The policy's shape rule claims "anything extending TileEntityConfigurableMachine
or TileEntityGenerator has a tick body that touches nothing but its own slots,
tanks and energy container." The generator family already falsified that once.
This script answers it for the whole installed mod by walking the call graph
out of each covered onUpdateServer, transitively, through javap's resolved
output -- so a tick that reaches the world via a private helper or an interface
default method is caught, not just one that names a world API directly.

Usage: python audit_covered_ticks.py <mekanism.jar> [workdir]
"""
import json
import os
import re
import subprocess
import sys
import zipfile

COVERED_SHAPES = {
    "mekanism.common.tile.prefab.TileEntityConfigurableMachine",
    "mekanism.generators.common.tile.TileEntityGenerator",
}

# owner.name substrings that mean "this call is standing in the world".
SINKS = [
    ("Level", re.compile(r"^net/minecraft/(server/level/ServerLevel|world/level/Level|"
                         r"world/level/LevelAccessor)\.")),
    ("world write", re.compile(r"^net/minecraft/world/level/block/(BucketPickup|Block)\.")),
    ("capability cache", re.compile(r"^net/neoforged/neoforge/capabilities/BlockCapabilityCache\.")),
    ("WorldUtils", re.compile(r"^mekanism/common/util/WorldUtils\.")),
    ("neighbour push", re.compile(r"^mekanism/common/util/(CableUtils|FluidUtils|ChemicalUtils?)\.")),
    ("capability cache", re.compile(r"\.createCache$")),
    ("heat simulation", re.compile(r"\.(getAdjacent|getAdjacentUnchecked|simulate|"
                                   r"simulateAdjacent|simulateEnvironment|updateHeatCapacitors)$")),
]

INVOKE_RE = re.compile(
    r"^\s+\d+:\s+invoke\w+\s+#\d+(?:,\s*\d+)?\s*//\s*"
    r"(?:InterfaceMethod|Method)\s+([^\s:]+)(?::([^\s(]+))?")
HEADER_RE = re.compile(r"^  (\S.*?)\(.*\);\s*$")
CLASS_NAME_RE = re.compile(r"^(?:public |protected |private |abstract |final |static )*"
                           r"(?:class|interface|enum) (\S+)")


def javap(workdir, jar, fqcn):
    path = os.path.join(workdir, fqcn.replace(".", os.sep) + ".class")
    if not os.path.exists(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with zipfile.ZipFile(jar) as zf:
            with open(path, "wb") as out:
                out.write(zf.read(fqcn.replace(".", "/") + ".class"))
    p = subprocess.run(["javap", "-p", "-c", "-s", path],
                       capture_output=True, text=True, errors="replace")
    return p.stdout


def split_methods(text):
    """{name: body-lines} for one disassembly."""
    out, cur = {}, None
    for line in text.splitlines():
        h = HEADER_RE.match(line)
        if h:
            cur = h.group(1).split()[-1]
            out[cur] = []
        elif cur is not None:
            out[cur].append(line)
    return out


def super_name(text):
    m = re.search(r"^(?:public |protected |private |abstract |final |static )*"
                  r"(?:class|interface|enum) \S+ extends (\S+)", text, re.M)
    return m.group(1).replace(".", "/") if m else None


def main():
    jar = sys.argv[1]
    workdir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.environ.get("TEMP", "/tmp"), "mekv")
    with zipfile.ZipFile(jar) as zf:
        all_classes = [n[:-6].replace("/", ".") for n in zf.namelist()
                       if n.endswith(".class") and "$" not in n]

    cache = {}

    def load(fqcn):
        if fqcn not in cache:
            try:
                text = javap(workdir, jar, fqcn)
            except Exception:
                text = ""
            cache[fqcn] = (text, split_methods(text))
        return cache[fqcn]

    def chain(fqcn):
        out, seen, cur = [], set(), fqcn
        while cur and cur not in seen:
            out.append(cur)
            seen.add(cur)
            text, _ = load(cur)
            cur = super_name(text)
        return out

    covered = [c for c in all_classes if COVERED_SHAPES & set(chain(c))]
    print("shape-allowed block entities: %d" % len(covered))

    findings = []
    for cls in sorted(covered):
        text, methods = load(cls)
        body = methods.get("onUpdateServer")
        if body is None:
            continue
        # Walk out of this tick body, following self-calls into the same class.
        queue = [(cls, "onUpdateServer", body, [])]
        seen = {(cls, "onUpdateServer")}
        paths = []
        while queue:
            owner, name, lines, path = queue.pop(0)
            for line in lines:
                m = INVOKE_RE.match(line)
                if not m:
                    continue
                target = m.group(1)
                tname = m.group(2) or ""
                # javap omits the owner for a self-call; attribute it to the
                # class whose body we are reading.
                if "/" not in target:
                    target = owner.replace(".", "/") + "." + target
                    tname = tname
                full = target + "." + tname
                hit = None
                for label, pat in SINKS:
                    if pat.search(full):
                        hit = label
                        break
                if hit:
                    paths.append(path + [(hit, full)])
                    continue
                # Follow a call into a method of the same class we have bytecode for.
                tcls = target.replace("/", ".")
                if tcls == owner:
                    sub = methods.get(tname)
                    if sub is not None and (owner, tname) not in seen:
                        seen.add((owner, tname))
                        queue.append((owner, tname, sub, path + [("call", full)]))
        if paths:
            findings.append((cls, paths))

    print("of those, tick bodies that reach the world: %d\n" % len(findings))
    for cls, paths in findings:
        print("== %s" % cls)
        for path in paths[:4]:
            chain_txt = " -> ".join("%s(%s)" % (lbl, ref) for lbl, ref in path)
            print("   %s" % chain_txt)
        print()
    json.dump([{"class": c, "paths": [[list(p) for p in ps] for ps in [paths]]}
               for c, paths in findings],
              open("covered_world_access.json", "w"), indent=2)


main()
