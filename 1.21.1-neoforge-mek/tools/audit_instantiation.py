"""Can the hazardous subtype on a weak edge ever be the receiver?

audit_reach.py answers "this tick can reach a world API if a virtual receiver
is one particular subtype", and leaves the subtype question open because a
static call graph cannot know the runtime type. This closes it: for each
hazardous subtype named in a weak edge, find every place the class is
constructed, and report which of them are on the covered chain.

A subtype constructed only by a class outside the covered population can never
be the receiver inside a covered tick, so the edge is not a hole.

Usage: python audit_instantiation.py <mekanism.jar> [--workdir DIR]
"""
import os
import re
import struct
import sys
import zipfile
from collections import defaultdict

COVERED_SHAPES = {
    "mekanism.common.tile.prefab.TileEntityConfigurableMachine",
    "mekanism.generators.common.tile.TileEntityGenerator",
}

# The hazardous subtypes the weak edges hinge on, as internal names.
HAZARDS = [
    "mekanism/common/capabilities/chemical/StackedWasteBarrel",
    "mekanism/common/content/entangloporter/InventoryFrequency$SendingChemicalHandlerTarget",
    "mekanism/common/content/entangloporter/InventoryFrequency$SendingFluidHandlerTarget",
    "mekanism/common/tile/factory/TileEntityItemStackChemicalToItemStackFactory",
]

INVOKE = {0xB6, 0xB7, 0xB8, 0xB9}
OPERANDS = {}
OPERANDS.update({0x10: 1, 0x11: 2, 0x12: 1, 0x13: 2, 0x14: 2})
OPERANDS.update({op: 1 for op in range(0x15, 0x1A)})
OPERANDS.update({op: 1 for op in range(0x36, 0x3B)})
OPERANDS.update({0x84: 2})
OPERANDS.update({op: 2 for op in range(0x99, 0xA9)})
OPERANDS.update({op: 2 for op in range(0xB2, 0xBA)})
OPERANDS[0xB9] = 4
OPERANDS[0xBA] = 4
OPERANDS.update({op: 2 for op in range(0xBB, 0xBE)})
OPERANDS[0xBC] = 1
OPERANDS.update({0xC0: 2, 0xC1: 2, 0xC5: 3, 0xC6: 2, 0xC7: 2, 0xC8: 4, 0xC9: 4})


class ClassFile:
    def __init__(self, data):
        self.cp = {}
        off = 8
        count = struct.unpack_from(">H", data, off)[0]
        off += 2
        i = 1
        while i < count:
            tag = data[off]
            off += 1
            if tag == 1:
                ln = struct.unpack_from(">H", data, off)[0]
                off += 2
                self.cp[i] = ("utf8", data[off:off + ln].decode("utf-8", "replace"))
                off += ln
            elif tag in (7, 8, 16, 19, 20):
                self.cp[i] = ("ref", struct.unpack_from(">H", data, off)[0])
                off += 2
            elif tag == 15:
                off += 3
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
                self.cp[i] = ("pair",) + struct.unpack_from(">HH", data, off)
                off += 4
            elif tag in (5, 6):
                off += 8
                i += 1
            else:
                raise ValueError("tag %d" % tag)
            i += 1
        off += 2
        self.name = self.utf(struct.unpack_from(">H", data, off)[0])
        off += 2
        self.super_name = self.utf(struct.unpack_from(">H", data, off)[0])
        off += 2
        ni = struct.unpack_from(">H", data, off)[0]
        off += 2
        self.interfaces = [self.utf(struct.unpack_from(">H", data, off + 2 * k)[0])
                           for k in range(ni)]
        off += 2 * ni
        nf = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(nf):
            off += 6
            off = self._skip(data, off)
        self.methods = {}
        nm = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(nm):
            _, nidx, didx = struct.unpack_from(">HHH", data, off)
            off += 6
            body, off = self._read(data, off)
            self.methods[self.utf(nidx) + self.utf(didx)] = body

    def utf(self, idx):
        e = self.cp.get(idx)
        if not e:
            return "?"
        return self.utf(e[1]) if e[0] == "ref" else e[1]

    def ref(self, idx):
        e = self.cp.get(idx)
        if not e:
            return None
        if e[0] == "ref":
            return self.ref(e[1])
        if e[0] != "pair":
            return None
        nat = self.cp.get(e[2])
        if not nat or nat[0] != "pair":
            return None
        return self.utf(e[1]), self.utf(nat[1]), self.utf(nat[2])

    def _skip(self, data, off):
        n = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(n):
            off += 2
            off += 4 + struct.unpack_from(">I", data, off)[0]
        return off

    def _read(self, data, off):
        body = None
        n = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(n):
            nidx = struct.unpack_from(">H", data, off)[0]
            off += 2
            ln = struct.unpack_from(">I", data, off)[0]
            off += 4
            if self.utf(nidx) == "Code":
                body = data[off:off + ln]
            off += ln
        return body, off


def refs(cf, body):
    """(opcode, owner, name, desc) for every field/method reference."""
    if not body:
        return []
    clen = struct.unpack_from(">I", body, 4)[0]
    code = body[8:8 + clen]
    if len(code) != clen:
        return []
    out = []
    i = 0
    while i < len(code):
        op = code[i]
        if op in INVOKE or 0xB2 <= op <= 0xB5 or op in (0xBB, 0xBD, 0xC0, 0xC1):
            if i + 2 >= len(code):
                break
            r = cf.ref(struct.unpack_from(">H", code, i + 1)[0])
            if r:
                out.append((op,) + r)
        elif op == 0xAA:
            pad = (4 - (i + 1) % 4) % 4
            base = i + 1 + pad
            if base + 12 > len(code):
                break
            low, high = struct.unpack_from(">ii", code, base + 4)
            i = base + 12 + 4 * (high - low + 1)
            continue
        elif op == 0xAB:
            pad = (4 - (i + 1) % 4) % 4
            base = i + 1 + pad
            if base + 8 > len(code):
                break
            i = base + 8 + 8 * struct.unpack_from(">i", code, base + 4)[0]
            continue
        elif op == 0xC4:
            i += 4 if code[i + 1] == 0x84 else 3
            continue
        i += 1 + OPERANDS.get(op, 0)
    return out


def main():
    jar = sys.argv[1]
    workdir = "."
    rest = sys.argv[2:]
    while rest:
        if rest.pop(0) == "--workdir":
            workdir = rest.pop(0)

    classes = {}
    with zipfile.ZipFile(jar) as zf:
        for entry in zf.namelist():
            if not entry.endswith(".class"):
                continue
            try:
                cf = ClassFile(zf.read(entry))
            except Exception:
                continue
            classes[cf.name.replace("/", ".")] = cf

    ancestors = {}

    def anc(fqcn, seen=None):
        if fqcn in ancestors:
            return ancestors[fqcn]
        seen = seen or set()
        if fqcn in seen:
            return set()
        seen.add(fqcn)
        cf = classes.get(fqcn)
        if cf is None:
            ancestors[fqcn] = set()
            return ancestors[fqcn]
        out = set()
        for p in [cf.super_name.replace("/", ".")] + [i.replace("/", ".") for i in cf.interfaces]:
            if p and p != fqcn:
                out.add(p)
                out |= anc(p, seen)
        ancestors[fqcn] = out
        return out

    # Subtypes of the hazards, so a factory method returning a subclass counts.
    hazard_fqcns = [h.replace("/", ".") for h in HAZARDS]
    def is_hazard(fqcn):
        return any(fqcn == h or h in anc(fqcn) for h in hazard_fqcns)

    # Every construction site: `new X` (0xBB), and a static factory whose
    # return type is a hazard (StackedWasteBarrel.create is the only way in,
    # the constructor being private).
    sites = defaultdict(list)
    for fqcn, cf in classes.items():
        for key, body in cf.methods.items():
            for op, owner, name, desc in refs(cf, body):
                if op == 0xBB and is_hazard(owner.replace("/", ".")):
                    sites[owner.replace("/", ".")].append((fqcn, key, "new"))
                elif op == 0xB8 and desc.endswith(";"):
                    ret = desc[desc.rfind(")") + 2:-1].replace("/", ".")
                    if is_hazard(ret):
                        sites[owner.replace("/", ".")].append(
                            (fqcn, key, "factory " + name + " -> " + ret))

    def chain(fqcn):
        out, seen, cur = [], set(), fqcn
        while cur and cur not in seen and cur != "java.lang.Object":
            out.append(cur)
            seen.add(cur)
            cf = classes.get(cur)
            cur = cf.super_name.replace("/", ".") if cf else None
        return out

    covered = {c for c in classes if COVERED_SHAPES & set(chain(c))}
    print("covered block entities: %d\n" % len(covered))
    for h in HAZARDS:
        dot = h.replace("/", ".")
        where = sites.get(dot, [])
        reachable = [s for s in where if s[0] in covered]
        print(dot)
        print("   construction sites: %d, inside a covered tick: %d"
              % (len(where), len(reachable)))
        for o, k, kind in where:
            mark = "COVERED" if o in covered else "       "
            print("      [%s] %-40s %s.%s" % (mark, kind, o, k.split("(")[0]))
        print()


main()
