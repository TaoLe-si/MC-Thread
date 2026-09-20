"""Verify every @WrapOperation target in the addon's mixins against the real
Titanium jar, and that the handler beside it has the signature MixinExtras will
demand at runtime.

The two mixins target Titanium (BasicTileBlock's ticker lambda and
ActiveTile's facing auto-push), so the CHECKS read the installed Titanium jar,
not IF's. IF's own classes carry no @WrapOperation targets.

Mirrors the Mekanism addon's verify_mixin_targets.py: fail the build when a
call site stops matching its declared wrapper — a Titanium update that
renames the synthetic lambda (the `lambda$getTicker$5` index is fragile) or
reorders the invokes is a build failure here instead of an in-game
InvalidInjectionException.

The second check is the one the mek addon's 0.3.9 hotfixes earned: a handler
whose parameter list is off by the receiver compiles fine and only fails at
class-transform time, so the arity is derived from the wrapped call's opcode
and descriptor here rather than trusted.

Run:  python tools/verify_mixin_targets.py
"""

import os
import re
import subprocess
import sys
import tempfile
import zipfile

MODS = r"E:\MC\.minecraft\versions\NAST hard d0.9.1 beta3\mods"
JARS = [
    os.path.join(MODS, "titanium-1.21-4.0.45.jar"),
    os.path.join(MODS, "industrialforegoing-1.21-3.6.39.jar"),
]

# (mixin, target class, method name, method descriptor, wrapped owner, wrapped name, wrapped descriptor, ordinal or None)
CHECKS = [
    # The single chokepoint: every Titanium-based block entity (all of IF)
    # ticks through this lambda's invokeinterface.
    ("TitaniumTickerMixin",
     "com.hrznstudio.titanium.block.BasicTileBlock", "lambda$getTicker$5",
     "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
     "Lnet/minecraft/world/level/block/state/BlockState;"
     "Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
     "com.hrznstudio.titanium.block.tile.ITickableBlockEntity", "serverTick",
     "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
     "Lnet/minecraft/world/level/block/state/BlockState;"
     "Lnet/minecraft/world/level/block/entity/BlockEntity;)V", None),
    # The neighbour auto-push, two identical call sites (inventories, tanks).
    ("ActiveTileFacingWorkMixin",
     "com.hrznstudio.titanium.block.tile.ActiveTile", "serverTick",
     "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
     "Lnet/minecraft/world/level/block/state/BlockState;"
     "Lcom/hrznstudio/titanium/block/tile/ActiveTile;)V",
     "com.hrznstudio.titanium.component.sideness.IFacingComponent", "work",
     "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
     "Lnet/minecraft/core/Direction;I)Z", 0),
    ("ActiveTileFacingWorkMixin",
     "com.hrznstudio.titanium.block.tile.ActiveTile", "serverTick",
     "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
     "Lnet/minecraft/world/level/block/state/BlockState;"
     "Lcom/hrznstudio/titanium/block/tile/ActiveTile;)V",
     "com.hrznstudio.titanium.component.sideness.IFacingComponent", "work",
     "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
     "Lnet/minecraft/core/Direction;I)Z", 1),
    # HydroponicBed route-B: the bonemeal action (one call site).
    ("HydroponicBedDeferralMixin",
     "com.buuz135.industrial.block.agriculturehusbandry.tile.HydroponicBedTile", "work",
     "()Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile$WorkAction;",
     "net.minecraft.world.level.block.BonemealableBlock", "performBonemeal",
     "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/util/RandomSource;"
     "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V", 0),
    # HydroponicBed route-B: the growth bursts (three call sites, wrapped
    # without ordinal — the gate reports "N of M" and any reordering fails).
    ("HydroponicBedDeferralMixin",
     "com.buuz135.industrial.block.agriculturehusbandry.tile.HydroponicBedTile", "work",
     "()Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile$WorkAction;",
     "net.minecraft.world.level.block.state.BlockState", "randomTick",
     "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;"
     "Lnet/minecraft/util/RandomSource;)V", 0),
    ("HydroponicBedDeferralMixin",
     "com.buuz135.industrial.block.agriculturehusbandry.tile.HydroponicBedTile", "work",
     "()Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile$WorkAction;",
     "net.minecraft.world.level.block.state.BlockState", "randomTick",
     "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;"
     "Lnet/minecraft/util/RandomSource;)V", 1),
    ("HydroponicBedDeferralMixin",
     "com.buuz135.industrial.block.agriculturehusbandry.tile.HydroponicBedTile", "work",
     "()Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile$WorkAction;",
     "net.minecraft.world.level.block.state.BlockState", "randomTick",
     "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;"
     "Lnet/minecraft/util/RandomSource;)V", 2),
    # HydroponicBed route-B: the harvest (a self-call, so javap prints it
    # without an owner — the script attributes those to the disassembled
    # class, which is what the constant pool holds and what the mixin
    # target must name).
    ("HydroponicBedDeferralMixin",
     "com.buuz135.industrial.block.agriculturehusbandry.tile.HydroponicBedTile", "work",
     "()Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile$WorkAction;",
     "com.buuz135.industrial.block.agriculturehusbandry.tile.HydroponicBedTile", "tryToHarvestAndReplant",
     "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
     "Lnet/minecraft/world/level/block/state/BlockState;"
     "Lnet/neoforged/neoforge/items/IItemHandler;"
     "Lcom/hrznstudio/titanium/component/progress/ProgressBarComponent;"
     "Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile;"
     "Ljava/util/function/Supplier;Lnet/minecraft/world/item/ItemStack;)Z", 0),
    ("HydroponicBedDeferralMixin",
     "com.buuz135.industrial.block.agriculturehusbandry.tile.HydroponicBedTile", "work",
     "()Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile$WorkAction;",
     "com.buuz135.industrial.block.agriculturehusbandry.tile.HydroponicBedTile", "tryToHarvestAndReplant",
     "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
     "Lnet/minecraft/world/level/block/state/BlockState;"
     "Lnet/neoforged/neoforge/items/IItemHandler;"
     "Lcom/hrznstudio/titanium/component/progress/ProgressBarComponent;"
     "Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile;"
     "Ljava/util/function/Supplier;Lnet/minecraft/world/item/ItemStack;)Z", 1),
    # LaserDrill route-B: the two writes to the LaserBase's bar.
    ("LaserDrillDeferralMixin",
     "com.buuz135.industrial.block.resourceproduction.tile.LaserDrillTile", "work",
     "()Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile$WorkAction;",
     "com.hrznstudio.titanium.component.progress.ProgressBarComponent", "setProgress",
     "(I)V", 0),
    ("LaserDrillDeferralMixin",
     "com.buuz135.industrial.block.resourceproduction.tile.LaserDrillTile", "work",
     "()Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile$WorkAction;",
     "com.hrznstudio.titanium.component.progress.ProgressBarComponent", "tickBar",
     "()V", 0),
]

INVOKE_RE = re.compile(
    r"^\s+\d+:\s+(invoke\w+)\s+#\d+(?:,\s*\d+)?\s*//\s*(?:InterfaceMethod|Method)\s+(\S+)$")
HEADER_RE = re.compile(r"^  (\S.*?)\(.*\);\s*$")
HEADER_DESC_RE = re.compile(r"^\s+descriptor: (\S+)$")

# The handler sits right after its @WrapOperation annotation.
WRAP_RE = re.compile(r"@WrapOperation\s*\(")
DECL_RE = re.compile(r"([\w$.\[\]]+)\s+([\w$]+)\s*\(([^()]*)\)", re.S)


def extract_classes(dest):
    wanted = set()
    for _, cls, _, _, _, _, _, _ in CHECKS:
        wanted.add(cls.replace(".", "/") + ".class")
    got = set()
    for jar in JARS:
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if name in wanted:
                    z.extract(name, dest)
                    got.add(name)
    return got, wanted


def disassemble(path):
    out = subprocess.run(["javap", "-p", "-c", "-s", path],
                         capture_output=True, text=True, check=True).stdout
    return out


def method_invokes(text, name, desc, self_owner):
    """All (opcode, owner, name, desc) INVOKEs inside the named method, in order.

    javap prints a self-call without an owner ("// Method kill:(...)V"), so
    those are attributed to self_owner — which is what the constant pool
    actually holds and what the mixin target must name.
    """
    lines = text.splitlines()
    found = []
    i = 0
    while i < len(lines):
        header = HEADER_RE.match(lines[i])
        if header:
            sig = header.group(1)
            header_name = sig.split("(")[0].split()[-1]
            j = i + 1
            m = HEADER_DESC_RE.match(lines[j]) if j < len(lines) else None
            if m and header_name == name and m.group(1) == desc:
                i = j + 1
                while i < len(lines) and not HEADER_RE.match(lines[i]):
                    hit = INVOKE_RE.match(lines[i])
                    if hit:
                        opcode, ref = hit.group(1), hit.group(2)
                        ref_name, _, ref_desc = ref.rpartition(":")
                        if "." in ref_name:
                            owner, _, ref_name = ref_name.rpartition(".")
                        else:
                            owner = self_owner
                        found.append((opcode, owner.replace("/", "."), ref_name, ref_desc))
                    i += 1
                continue
        i += 1
    return found


def descriptor_arg_count(desc):
    """Number of arguments in a JVM method descriptor."""
    body = desc[1:desc.rindex(")")]
    count = 0
    i = 0
    while i < len(body):
        if body[i] == "L":
            i = body.index(";", i)
        elif body[i] == "[":
            while body[i] == "[":
                i += 1
            if body[i] == "L":
                i = body.index(";", i)
        count += 1
        i += 1
    return count


def descriptor_return(desc):
    return desc[desc.rindex(")") + 1:]


PRIMITIVES = {"V": "void", "Z": "boolean", "B": "byte", "C": "char",
              "S": "short", "I": "int", "J": "long", "F": "float", "D": "double"}


def java_return(desc):
    """The simple Java return type name for a descriptor's return slot.

    Simple, because the source may import the type; a mismatch in the package
    cannot change whether the transform applies, only the simple name can.
    """
    ret = descriptor_return(desc)
    if ret in PRIMITIVES:
        return PRIMITIVES[ret]
    if ret.startswith("L"):
        return ret[1:-1].rpartition("/")[2]
    return ret


def split_params(text):
    """Split a Java parameter list on top-level commas only."""
    parts, depth, current = [], 0, ""
    for ch in text:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(current.strip())
            current = ""
        else:
            current += ch
    if current.strip():
        parts.append(current.strip())
    return parts


def strip_comments(text):
    """Remove // line comments and /* */ block comments from Java source.

    Without this, javadoc mentions like "{@code work()}" match the
    method-declaration pattern and pollute the handler list.
    """
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return text


def mixin_source(mixin_name):
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "java")
    for base, _, files in os.walk(root):
        if mixin_name + ".java" in files:
            with open(os.path.join(base, mixin_name + ".java"), encoding="utf-8") as fh:
                return strip_comments(fh.read())
    return None


def handler_signatures(mixin_name):
    """[(return type, non-Operation param count)] for every @WrapOperation
    handler in the mixin, in declaration order.

    A route-B mixin wraps several call-site groups, so one signature is not
    enough — the gate pairs the CHECKS groups (in first-appearance order)
    with these signatures positionally. Returns None when the file or the
    annotation is missing.
    """
    text = mixin_source(mixin_name)
    if text is None:
        return None
    sigs = []
    pos = 0
    while True:
        m = WRAP_RE.search(text, pos)
        if not m:
            break
        depth, i = 0, m.end() - 1
        while i < len(text):
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        decl = DECL_RE.search(text[i + 1:])
        if not decl:
            return None
        params = [p for p in split_params(decl.group(3)) if not p.startswith("Operation<")]
        sigs.append((decl.group(1), len(params)))
        pos = i + 1
    return sigs or None


def check_handler_signatures(cache):
    """The receiver is not optional for an instance call, and every handler's
    return type must be its wrapped call's return type.

    CHECKS rows for one mixin that share (owner, name, descriptor) belong to
    one annotation (an ordinal-split or an all-sites wrap); the distinct
    groups, in first-appearance order, pair with the mixin's handlers in
    declaration order.
    """
    failures = 0
    groups = {}
    for row in CHECKS:
        (mixin, cls, mname, mdesc, owner, wname, wdesc, _) = row
        key = (owner, wname, wdesc)
        lst = groups.setdefault(mixin, [])
        if key not in [k for k, *_ in lst]:
            lst.append((key, row))

    for mixin, entries in groups.items():
        sigs = handler_signatures(mixin)
        if sigs is None:
            print(f"FAIL  no @WrapOperation handler found in {mixin}")
            failures += 1
            continue
        if len(sigs) != len(entries):
            print(f"FAIL  {mixin} declares {len(sigs)} @WrapOperation handler(s) "
                  f"but CHECKS groups {len(entries)} call-site group(s)")
            failures += 1
            continue
        for (key, row), (ret, count) in zip(entries, sigs):
            (mixin_, cls, mname, mdesc, owner, wname, wdesc, _) = row
            invokes = method_invokes(cache[cls], mname, mdesc, cls)
            opcode = next((c[0] for c in invokes if c[1:] == key), None)
            if opcode is None:
                continue  # already reported by the call-site check
            want = descriptor_arg_count(wdesc) + (0 if opcode == "invokestatic" else 1)
            want_ret = java_return(wdesc)
            label = f"{mixin}.handler -> {owner.split('.')[-1]}.{wname}"
            if count != want:
                print(f"FAIL  handler takes {count} params, {opcode} needs {want} "
                      f"(receiver {'included' if opcode != 'invokestatic' else 'absent'})  {label}")
                failures += 1
            elif ret != want_ret:
                print(f"FAIL  handler returns {ret}, wrapped call returns {want_ret}  {label}")
                failures += 1
            else:
                print(f"ok    {count} params + Operation -> {ret}   {label}")
    return failures


def main():
    with tempfile.TemporaryDirectory() as tmp:
        got, wanted = extract_classes(tmp)
        missing = wanted - got
        if missing:
            for name in sorted(missing):
                print(f"MISSING CLASS IN JARS: {name}")
            return 1

        cache = {}
        failures = 0
        for (mixin, cls, mname, mdesc, owner, wname, wdesc, ordinal) in CHECKS:
            if cls not in cache:
                cache[cls] = disassemble(os.path.join(tmp, cls.replace(".", "/") + ".class"))
            invokes = method_invokes(cache[cls], mname, mdesc, cls)
            hits = [c for c in invokes if c[1:] == (owner, wname, wdesc)]
            label = f"{mixin} -> {cls.split('.')[-1]}.{mname} : {owner.split('.')[-1]}.{wname}{wdesc}"
            if not hits:
                print(f"FAIL  no such call   {label}")
                failures += 1
            elif ordinal is not None:
                if ordinal < len(hits):
                    print(f"ok    ordinal {ordinal} of {len(hits)}  {label}")
                else:
                    print(f"FAIL  ordinal {ordinal} but only {len(hits)} matches  {label}")
                    failures += 1
            else:
                print(f"ok    {len(hits)} call site(s)          {label}")

        failures += check_handler_signatures(cache)

        print()
        print("VERDICT:", "ALL TARGETS RESOLVE" if failures == 0 else f"{failures} FAILURES")
        return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())