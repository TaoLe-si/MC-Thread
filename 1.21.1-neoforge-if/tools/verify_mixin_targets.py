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


def handler_signature(mixin_name):
    """(return type, non-Operation param count) of the @WrapOperation handler."""
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "java")
    for base, _, files in os.walk(root):
        if mixin_name + ".java" in files:
            with open(os.path.join(base, mixin_name + ".java"), encoding="utf-8") as fh:
                text = fh.read()
            break
    else:
        return None

    m = WRAP_RE.search(text)
    if not m:
        return None
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
    return decl.group(1), len(params)


def check_handler_signatures(cache):
    """The receiver is not optional for an instance call, and the handler
    return type must be the wrapped method's return type."""
    failures = 0
    seen = set()
    for (mixin, cls, mname, mdesc, owner, wname, wdesc, _) in CHECKS:
        if (mixin, wname) in seen:
            continue
        seen.add((mixin, wname))
        invokes = method_invokes(cache[cls], mname, mdesc, cls)
        opcode = next((c[0] for c in invokes if c[1:] == (owner, wname, wdesc)), None)
        if opcode is None:
            continue  # already reported by the call-site check
        want = descriptor_arg_count(wdesc) + (0 if opcode == "invokestatic" else 1)
        got = handler_signature(mixin)
        if got is None:
            print(f"FAIL  no @WrapOperation handler found in {mixin}")
            failures += 1
            continue
        ret, count = got
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