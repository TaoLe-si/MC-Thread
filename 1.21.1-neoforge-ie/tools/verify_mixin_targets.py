"""Verify every @WrapOperation @At target in the addon's mixins against the
real installed Immersive Engineering jar, AND verify the handler signature
matches what MixinExtras will demand at runtime.

This is the gate the mek addon's MKX addon needed: copying a wrapper from
a previous successful addon without verifying the new target's actual
signature is what produced three same-shape crashes in a row — wrong method
name, wrong descriptor, wrong handler return type. javap reads the installed
class file's constant pool and checks both the @At target and the handler
param/return list.

Run:  python tools/verify_mixin_targets.py
"""

import os
import re
import subprocess
import sys
import tempfile
import zipfile

MODS = r"E:\MC\.minecraft\versions\NAST hard d0.9.1 beta3\mods"
JAR_NAME = "ImmersiveEngineering-1.21.1-12.4.2-194.jar"
JARS = [os.path.join(MODS, JAR_NAME)]

# (mixin, target class, method name, method descriptor, wrapped owner, wrapped name, wrapped descriptor, ordinal or None)
#
# Two call sites to verify:
#   1. IEEntityBlock$BEClassInspectedData.makeBaseTicker — the invokestatic
#      of IEServerTickableBE.makeTicker(). The wrapper returns a delegating
#      ticker that applies the offload policy per BE per tick. This is the
#      chokepoint; the original interface-targeting variant was rejected by
#      Mixin (a class mixin cannot target an interface), so the wrap moved
#      to the class that calls makeTicker().
#   2. ClocheBlockEntity.tickServer — the `invokestatic` of
#      `ItemHandlerHelper.insertItem(IItemHandler, ItemStack, Z)ItemStack`,
#      which is the ejector that pushes the grown seed/soil output to a
#      neighbour IItemHandler. The wrapper defers it to the server-thread
#      FIFO so concurrent worker calls on the same neighbour don't race.
CHECKS = [
    # Chokepoint: the invokestatic of IEServerTickableBE.makeTicker() inside
    # the record's makeBaseTicker. The wrapped call is static, so the handler
    # takes no receiver; its return type is the ticker the wrapper re-wraps.
    # Note: the owner is written with dots here because the script normalizes
    # javap's slash-separated owners to dots before comparing. The mixin's
    # @At target itself correctly uses slashes (JVM internal name).
    ("IEClassInspectedDataTickerMixin",
     "blusunrize.immersiveengineering.common.blocks.IEEntityBlock$BEClassInspectedData", "makeBaseTicker",
     "(Z)Lnet/minecraft/world/level/block/entity/BlockEntityTicker;",
     "blusunrize.immersiveengineering.common.blocks.ticking.IEServerTickableBE", "makeTicker",
     "()Lnet/minecraft/world/level/block/entity/BlockEntityTicker;", 0),
    # Cloche ejector: ItemHandlerHelper.insertItem(IItemHandler, ItemStack, Z)
    # called from ClocheBlockEntity.tickServer. The wrapper defers the
    # non-simulate branch to deferWorldWrite; the FIFO is drained on the
    # server thread, so concurrent worker calls on the same neighbour
    # IItemHandler cannot race.
    # Same dots-vs-slashes note as above.
    ("ClocheEjectorMixin",
     "blusunrize.immersiveengineering.common.blocks.metal.ClocheBlockEntity", "tickServer",
     "()V",
     "net.neoforged.neoforge.items.ItemHandlerHelper", "insertItem",
     "(Lnet/neoforged/neoforge/items/IItemHandler;Lnet/minecraft/world/item/ItemStack;Z)"
             + "Lnet/minecraft/world/item/ItemStack;", 0),
]

INVOKE_RE = re.compile(
    r"^\s+\d+:\s+(invoke\w+)\s+#\d+(?:,\s*\d+)?\s*//\s*(?:InterfaceMethod|Method)\s+(\S+)$")
HEADER_RE = re.compile(r"^  (\S.*?)\(.*\);\s*$")
HEADER_DESC_RE = re.compile(r"^\s+descriptor: (\S+)$")

PRIMITIVES = {"V": "void", "Z": "boolean", "B": "byte", "C": "char",
              "S": "short", "I": "int", "J": "long", "F": "float", "D": "double"}


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


def java_return(desc):
    """The simple Java return type name for a descriptor's return slot.

    Simple, because the source may import the type; a mismatch in the
    package cannot change whether the transform applies, only the simple
    name can.
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

    A method-decl regex without this matched parentheticals inside javadoc
    ("the whole base tick (guard + tickServer)") when the real declaration's
    generic return type defeated the pattern.
    """
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return text


def handler_signature(mixin_name):
    """(return type, non-Operation param count) of the @WrapOperation handler."""
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "java")
    for base, _, files in os.walk(root):
        if mixin_name + ".java" in files:
            with open(os.path.join(base, mixin_name + ".java"), encoding="utf-8") as fh:
                text = strip_comments(fh.read())
            break
    else:
        return None

    m = re.search(r"@WrapOperation\s*\(", text)
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
    # Return type may carry one level of generics (e.g. BlockEntityTicker<BlockEntity>).
    decl = re.search(r"((?:[\w\$\.\[\]]|<[^<>]*>)+)\s+([\w\$]+)\s*\(([^()]*)\)", text[i + 1:], re.S)
    if not decl:
        return None
    params = [p for p in split_params(decl.group(3)) if not p.startswith("Operation<")]
    # Normalize the return type to its simple name: the source may write
    # generics (BlockEntityTicker<BlockEntity>) where the descriptor side
    # only ever produces the erasure (BlockEntityTicker).
    ret = decl.group(1)
    ret = re.sub(r"<.*>", "", ret).rsplit(".", 1)[-1]
    return ret, len(params)


def mixin_annotation_block(mixin_name):
    """The raw text of the @WrapOperation(...) annotation in the mixin source.

    Returns None when the mixin has no @WrapOperation.
    """
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "java")
    for base, _, files in os.walk(root):
        if mixin_name + ".java" in files:
            with open(os.path.join(base, mixin_name + ".java"), encoding="utf-8") as fh:
                text = fh.read()
            break
    else:
        return None

    m = re.search(r"@WrapOperation\s*\(", text)
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
    return text[m.start():i + 1]


def declared_selector(block, key):
    """The concatenated string literal assigned to `key =` inside an
    annotation block, with Java concatenation resolved and quotes stripped.

    Returns None when the key is absent.
    """
    if block is None:
        return None
    m = re.search(key + r'\s*=\s*((?:"[^"]*"\s*\+?\s*)+)', block)
    if not m:
        return None
    parts = re.findall(r'"([^"]*)"', m.group(1))
    return "".join(parts)


def check_declared_selectors():
    """Cross-check the mixin source's method=/target= strings against CHECKS.

    This is the check that would have caught all three MKX crashes: the jar
    side of CHECKS verifies the call site exists, and the handler check
    verifies the signature, but neither noticed when the *mixin source*
    named a different method than CHECKS audited. The method= string is
    compared as name+descriptor; the target= string as owner+name+desc
    (slashes, as the JVM writes them).
    """
    failures = 0
    seen = set()
    for (mixin, cls, mname, mdesc, owner, wname, wdesc, _) in CHECKS:
        if mixin in seen:
            continue
        seen.add(mixin)
        block = mixin_annotation_block(mixin)
        if block is None:
            print(f"FAIL  no @WrapOperation annotation found in {mixin}")
            failures += 1
            continue

        method = declared_selector(block, "method")
        want_method = mname + mdesc
        if method is None:
            print(f"FAIL  {mixin} declares no method= — CHECKS expects {want_method}")
            failures += 1
        elif method != want_method:
            print(f"FAIL  {mixin} declares method={method!r} but CHECKS audited "
                  f"{want_method!r} against the installed jar")
            failures += 1

        target = declared_selector(block, "target")
        # The mixin's @At target= uses mixin selector syntax:
        #   "L" + owner(slashes) + ";" + name + descriptor
        # CHECKS holds the same data as (owner with dots, name, descriptor);
        # rebuild the selector form for comparison.
        want_target = "L" + owner.replace(".", "/") + ";" + wname + wdesc
        if target is None:
            print(f"FAIL  {mixin} declares no target= — CHECKS expects {want_target}")
            failures += 1
        else:
            norm = target.replace(" ", "")
            want_norm = want_target.replace(" ", "")
            if norm != want_norm:
                print(f"FAIL  {mixin} declares target={target!r} but CHECKS audited "
                      f"{want_target!r} against the installed jar")
                failures += 1
    if failures == 0:
        print("ok    mixin method=/target= strings match CHECKS")
    return failures


def check_handler_signatures(cache):
    failures = 0
    seen = set()
    for (mixin, cls, mname, mdesc, owner, wname, wdesc, _) in CHECKS:
        if (mixin, wname) in seen:
            continue
        seen.add((mixin, wname))
        invokes = method_invokes(cache[cls], mname, mdesc, cls)
        opcode = next((c[0] for c in invokes if c[1:] == (owner, wname, wdesc)), None)
        if opcode is None:
            continue
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


def extract_classes(dest):
    wanted = set()
    for _, cls, _, _, _, _, _, _ in CHECKS:
        wanted.add(cls.replace(".", "/") + ".class")
    got = set()
    for jar in JARS:
        if not os.path.isfile(jar):
            print(f"missing jar: {jar}")
            continue
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
    """All (opcode, owner, name, desc) INVOKEs inside the named method, in order."""
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


def main():
    with tempfile.TemporaryDirectory() as tmp:
        got, wanted = extract_classes(tmp)
        missing = wanted - got
        if missing:
            for name in sorted(missing):
                print(f"MISSING CLASS IN JARS: {name}")
            print("\nVERDICT: missing classes — script cannot verify targets")
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
        failures += check_declared_selectors()

        print()
        print("VERDICT:", "ALL TARGETS RESOLVE" if failures == 0 else f"{failures} FAILURES")
        return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())