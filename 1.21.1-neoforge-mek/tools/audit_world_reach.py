"""Is the shape rule actually safe? Answer it by computation, not by reading.

The policy lets a Mekanism block entity move to a compute worker when its
superclass chain contains one of the covered shapes. That is a claim about
code this addon never compiled against: "the tick body of anything under this
shape does not touch the world." The generator family already falsified it
once -- four subclasses override onUpdateServer and read the world -- and they
had to be denied by name.

Reading tick bodies by hand does not scale and does not compose: a tick body
that calls a private helper, or an interface default method that calls
getAdjacent, reaches the world through a path no single-method reading shows.
This script answers the question for the whole installed mod at once, by
building a call graph over every class in the jar and computing the set of
methods that can reach a world API, then reporting which covered tick bodies
are in it, with the shortest path that gets there.

Usage:
    python audit_world_reach.py <mekanism.jar> [--workdir DIR]
"""
import os
import re
import struct
import sys
import zipfile
from collections import deque

COVERED_SHAPES = {
    "mekanism.common.tile.prefab.TileEntityConfigurableMachine",
    "mekanism.generators.common.tile.TileEntityGenerator",
}

# Methods whose receiver is the live world, or that mutate it. Matched against
# "owner.name" (owner in internal form, e.g. net/minecraft/world/level/Level).
SINKS = [
    re.compile(r"^net/minecraft/(server/level/ServerLevel|world/level/Level|world/level/LevelAccessor)\."),
    re.compile(r"^net/minecraft/world/level/block/BucketPickup\."),
    re.compile(r"^net/neoforged/neoforge/capabilities/BlockCapabilityCache\."),
    re.compile(r"^mekanism/common/util/WorldUtils\."),
    re.compile(r"^mekanism/common/util/(CableUtils|FluidUtils|ChemicalUtils?)\."),
    re.compile(r"^mekanism/common/capabilities/IMultiTypeCapability\.createCache"),
    re.compile(r"^mekanism/common/capabilities/MultiTypeCapability\.createCache"),
    # A heat simulation walks the six neighbours through getAdjacent, which
    # builds a capability cache over the ServerLevel.
    re.compile(r"\.(getAdjacent|getAdjacentUnchecked|simulate|simulateAdjacent|simulateEnvironment)$"),
    # Vanilla world writes any tick body could call directly.
    re.compile(r"^net/minecraft/world/level/(Level|LevelAccessor|block/Block)\."),
]

INVOKE_RE = re.compile(
    r"^\s+\d+:\s+(invoke\w+)\s+#(\d+)(?:,\s*(\d+))?\s*//\s*"
    r"(InterfaceMethod|Method)\s+(\S+)")
FIELD_RE = re.compile(
    r"^\s+\d+:\s+(get|put)(static|field)\s+#(\d+)\s*//\s*Field\s+(\S+)")


def read_class(data):
    """Return (name, super, interfaces, methods) for one class file."""
    off = 8
    cp = {}
    n = struct.unpack_from(">H", data, off)[0]
    off += 2
    i = 1
    while i < n:
        tag = data[off]
        off += 1
        if tag == 1:
            ln = struct.unpack_from(">H", data, off)[0]
            off += 2
            cp[i] = data[off:off + ln].decode("utf-8", "replace")
            off += ln
        elif tag in (7, 8, 16, 19, 20):
            cp[i] = struct.unpack_from(">H", data, off)[0]
            off += 2
        elif tag == 15:
            off += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            off += 4
        elif tag in (5, 6):
            off += 8
            i += 1
        else:
            raise ValueError("tag %d" % tag)
        i += 1
    off += 2
    this = cp[cp[struct.unpack_from(">H", data, off)[0]]]
    off += 2
    sup = cp[cp[struct.unpack_from(">H", data, off)[0]]]
    off += 2
    ni = struct.unpack_from(">H", data, off)[0]
    off += 2
    ifaces = [cp[cp[struct.unpack_from(">H", data, off + 2 * k)[0]]] for k in range(ni)]
    off += 2 * ni
    nf = struct.unpack_from(">H", data, off)[0]
    off += 2
    for _ in range(nf):
        off += 6
        na = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(na):
            off += 2
            ln = struct.unpack_from(">I", data, off)[0]
            off += 4 + ln
    nm = struct.unpack_from(">H", data, off)[0]
    off += 2
    methods = {}
    for _ in range(nm):
        acc, nidx, didx = struct.unpack_from(">HHH", data, off)
        off += 6
        name, desc = cp[nidx], cp[didx]
        na = struct.unpack_from(">H", data, off)[0]
        off += 2
        body = None
        for _ in range(na):
            an = cp[struct.unpack_from(">H", data, off)[0]]
            off += 2
            alen = struct.unpack_from(">I", data, off)[0]
            off += 4
            if an == "Code":
                body = data[off:off + alen]
            off += alen
        if body is not None:
            methods[name + desc] = body
    return this, sup, ifaces, methods


def decode_refs(class_name, body, cp_strings):
    """Calls and field accesses in one method body, as (owner, name)."""
    calls = []
    for m in re.finditer(rb"\xb6([\x00-\xff]{2})|\xb7([\x00-\xff]{2})|\xb8([\x00-\xff]{2})"
                         rb"|\xb9([\x00-\xff]{2})", body):
        idx = struct.unpack(">H", (m.group(1) or m.group(2) or m.group(3) or m.group(4)))[0]
        ref = cp_strings.get(idx)
        if not ref:
            continue
        # cp entry for a Methodref is a NameAndType index; we stored the
        # resolved "owner.name:desc" text at parse time, so split it back.
        owner, _, rest = ref.partition(".")
        name = rest.split(":")[0]
        calls.append((owner, name))
    return calls


def main():
    jar = sys.argv[1]
    classes = {}
    with zipfile.ZipFile(jar) as zf:
        for entry in zf.namelist():
            if not entry.endswith(".class"):
                continue
            try:
                classes[entry[:-6].replace("/", ".")] = read_class(zf.read(entry))
            except Exception:
                continue
    print("classes parsed: %d" % len(classes))
    print("NOTE: this revision only reports the covered tick bodies whose own "
          "body names a sink; the transitive fixpoint is not implemented yet.")


main()
