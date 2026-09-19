"""Which offloaded tick bodies can reach a world API the core does not catch?

The policy's shape rule says: anything whose superclass chain contains one of
the covered shapes may have its tick moved to a compute worker. That is a claim
about code this addon never compiled against. The generator family already
falsified it once -- four subclasses override onUpdateServer and read the world
-- and they had to be denied by name. Reading tick bodies by hand does not scale
and does not compose, because a tick reaches the world through private helpers
and interface default methods that no single-method reading shows.

So compute it instead. This reads every class file straight out of the installed
Mekanism jar (no decompiler, no network), builds a call graph, and asks which
tick bodies can reach a world API, with the shortest path that gets there.

Two sink classes, because they are not equally dangerous:

  HANDLED   -- vanilla world entry points the Thread Tearer core intercepts in
               its own LevelMixin (setBlock, setBlockAndUpdate, neighborChanged,
               ...). A worker calling these is the designed path: the core
               routes the write back to the server thread.
  UNHANDLED -- everything else that means "standing in the world": NeoForge's
               capability caches and their global listener map, the light
               engine, chunk sources, Mekanism's own world helpers and the
               neighbour pushes. Nothing intercepts these, so a worker calling
               one is a live cross-thread bug.

Usage:
    python audit_reach.py <mekanism.jar> [--workdir DIR] [--shapes A,B,C]
"""
import json
import os
import re
import struct
import sys
import zipfile
from collections import defaultdict, deque

COVERED_SHAPES = [
    "mekanism.common.tile.prefab.TileEntityConfigurableMachine",
    "mekanism.generators.common.tile.TileEntityGenerator",
]

# (label, owner-regex, name-regex). Owner is in internal form (a/b/C).
# Deliberately narrow: the *Util classes are matched on the specific method that
# walks into the world, not on the class. ChemicalUtil.canInsert, for instance,
# is a SIMULATE insert on the handler it is handed and touches nothing.
UNHANDLED = [
    ("capability cache", re.compile(r"^net/neoforged/neoforge/capabilities/BlockCapabilityCache$"),
     re.compile(r".*")),
    ("capability cache build", re.compile(r".*"), re.compile(r"createCache")),
    ("capability listeners", re.compile(r".*CapabilityListenerHolder.*"), re.compile(r".*")),
    ("light engine", re.compile(r"^net/minecraft/world/level/lighting/"), re.compile(r".*")),
    ("chunk source", re.compile(r"^net/minecraft/server/level/(ServerChunkCache|ChunkMap|ChunkHolder)$"),
     re.compile(r".*")),
    ("mek world helper", re.compile(r"^mekanism/common/util/WorldUtils$"), re.compile(r".*")),
    ("neighbour push", re.compile(r"^mekanism/common/util/(CableUtils|FluidUtils|ChemicalUtil)$"),
     re.compile(r"^emit$")),
    ("world block pickup", re.compile(r"^net/minecraft/world/level/block/BucketPickup$"), re.compile(r".*")),
    ("entity scan/damage", re.compile(r"^net/minecraft/world/level/entity/"), re.compile(r".*")),
    ("neighbour container", re.compile(r"^net/neoforged/neoforge/(items|fluids)/I.*Handler$"),
     re.compile(r".*")),
]

# Vanilla entry points the core's LevelMixin intercepts and relocates.
HANDLED = [
    ("level write (handled)", re.compile(r"^net/minecraft/(server/level/ServerLevel|world/level/Level"
                                         r"|world/level/LevelAccessor|world/level/LevelWriter)$"),
     re.compile(r"(setBlock|setBlockAndUpdate|removeBlock|destroyBlock|setBlockEntity|removeBlockEntity"
                r"|blockEntityChanged|neighborChanged|updateNeighborsAt|updateNeighbourForOutputSignal"
                r"|neighborShapeChanged|blockEvent|explode|addFreshBlockEntities|sendBlockUpdated)$")),
]

# Calls this addon's own mixins wrap and hand back to the server thread. A path
# that reaches one of these and stops is the designed split, not a hole -- the
# worker runs the caller, the server thread runs the callee. Keep this in step
# with the @At targets in src/main/java/.../mixin/, and with
# tools/verify_mixin_targets.py, which checks those targets against the jar.
DEFERRED = [
    ("deferred: ejector push", re.compile(r"^mekanism/common/tile/component/TileComponentEjector$"),
     re.compile(r"^tickServer$")),
    ("deferred: generator emit", re.compile(r"^mekanism/common/util/CableUtils$"),
     re.compile(r"^emit$")),
    ("deferred: multiblock sim", re.compile(r"^mekanism/common/lib/multiblock/MultiblockData$"),
     re.compile(r"^tick$")),
]

INVOKE_OPCODES = {0xB6, 0xB7, 0xB8, 0xB9}

# Operand byte counts, so the walk lands on real instruction boundaries instead
# of guessing. Only opcodes that carry operands are listed; everything else is
# one byte. Switch (0xAA/0xAB) and wide (0xC4) are handled in the walker.
OPERANDS = {}
OPERANDS.update({0x10: 1, 0x11: 2, 0x12: 1, 0x13: 2, 0x14: 2})          # bipush sipush ldc*
OPERANDS.update({op: 1 for op in range(0x15, 0x1A)})                    # iload..aload
OPERANDS.update({op: 1 for op in range(0x36, 0x3B)})                    # istore..astore
OPERANDS.update({0x84: 2})                                              # iinc
OPERANDS.update({op: 2 for op in range(0x99, 0xA9)})                    # if<cond>, goto, jsr
OPERANDS.update({op: 2 for op in range(0xB2, 0xBA)})                    # get/put/invoke
OPERANDS[0xB9] = 4                                                      # invokeinterface
OPERANDS[0xBA] = 4                                                      # invokedynamic
OPERANDS.update({op: 2 for op in range(0xBB, 0xBE)})                    # new, newarray(1), anewarray
OPERANDS[0xBC] = 1
OPERANDS.update({0xC0: 2, 0xC1: 2, 0xC5: 3, 0xC6: 2, 0xC7: 2, 0xC8: 4, 0xC9: 4})
FIELD_OPCODES = {0xB2, 0xB3, 0xB4, 0xB5}


class ClassFile:
    """Minimal constant-pool + method reader for a JVM class file."""

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
                raise ValueError("constant pool tag %d" % tag)
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

        field_count = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(field_count):
            off += 6
            off = self._skip_attrs(data, off)
        self.methods = {}
        method_count = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(method_count):
            _, name_idx, desc_idx = struct.unpack_from(">HHH", data, off)
            off += 6
            key = self.utf(name_idx) + self.utf(desc_idx)
            body, off = self._read_attrs(data, off)
            self.methods[key] = body

    def utf(self, idx):
        """Dereference a Utf8 entry, following Class/Module wrappers."""
        entry = self.cp.get(idx)
        if not entry:
            return "?"
        return self.utf(entry[1]) if entry[0] == "ref" else entry[1]

    def ref(self, idx):
        """Resolve a Methodref/Fieldref to (owner, name, desc)."""
        entry = self.cp.get(idx)
        if not entry:
            return None
        if entry[0] == "ref":
            return self.ref(entry[1])
        if entry[0] != "pair":
            return None
        class_idx, nat_idx = entry[1], entry[2]
        nat = self.cp.get(nat_idx)
        if not nat or nat[0] != "pair":
            return None
        return (self.utf(class_idx), self.utf(nat[1]), self.utf(nat[2]))

    def _skip_attrs(self, data, off):
        count = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(count):
            off += 2
            off += 4 + struct.unpack_from(">I", data, off)[0]
        return off

    def _read_attrs(self, data, off):
        body = None
        count = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(count):
            name_idx = struct.unpack_from(">H", data, off)[0]
            off += 2
            ln = struct.unpack_from(">I", data, off)[0]
            off += 4
            if self.utf(name_idx) == "Code":
                body = data[off:off + ln]
            off += ln
        return body, off


def calls_in(class_file, body):
    """Every method call in one Code attribute, as (owner, name, desc).

    Returns (calls, ok). ok is False when the walk did not land exactly on the
    end of the code array, which means the operand table is wrong and the
    result cannot be trusted -- reported rather than silently accepted.
    """
    if not body:
        return [], True
    # Code attribute layout: u2 max_stack, u2 max_locals, u4 code_length, code.
    code_len = struct.unpack_from(">I", body, 4)[0]
    code = body[8:8 + code_len]
    if len(code) != code_len:
        return [], False
    out = []
    i = 0
    while i < len(code):
        op = code[i]
        if op in INVOKE_OPCODES:
            if i + 2 >= len(code):
                return out, False
            idx = struct.unpack_from(">H", code, i + 1)[0]
            ref = class_file.ref(idx)
            if ref:
                # The opcode matters: invokespecial (super/private/<init>) and
                # invokestatic bind at compile time, so no subtype fan-out.
                out.append((ref[0], ref[1], ref[2], op))
        elif op == 0xBA:  # invokedynamic -- the lambda body is elsewhere
            if i + 2 >= len(code):
                return out, False
            idx = struct.unpack_from(">H", code, i + 1)[0]
            out.append(("~lambda~", "#%d" % idx, "", op))
        elif op == 0xAA:  # tableswitch
            pad = (4 - (i + 1) % 4) % 4
            base = i + 1 + pad
            if base + 12 > len(code):
                return out, False
            low, high = struct.unpack_from(">ii", code, base + 4)
            i = base + 12 + 4 * (high - low + 1)
            continue
        elif op == 0xAB:  # lookupswitch
            pad = (4 - (i + 1) % 4) % 4
            base = i + 1 + pad
            if base + 8 > len(code):
                return out, False
            npairs = struct.unpack_from(">i", code, base + 4)[0]
            i = base + 8 + 8 * npairs
            continue
        elif op == 0xC4:  # wide
            i += 4 if code[i + 1] == 0x84 else 3
            continue
        i += 1 + OPERANDS.get(op, 0)
    return out, i == len(code)


def main():
    jar = sys.argv[1]
    workdir = None
    shapes = list(COVERED_SHAPES)
    rest = sys.argv[2:]
    while rest:
        flag = rest.pop(0)
        if flag == "--workdir":
            workdir = rest.pop(0)
        elif flag == "--shapes":
            shapes = rest.pop(0).split(",")

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
    print("classes parsed: %d" % len(classes))

    # ---- graph -------------------------------------------------------------
    nodes = {}                       # node -> list of callee nodes
    by_name = defaultdict(set)       # "name desc" -> {node}
    malformed = 0
    for fqcn, cf in classes.items():
        for key, body in cf.methods.items():
            node = fqcn + "#" + key
            callees, ok = calls_in(cf, body)
            if not ok:
                malformed += 1
            nodes[node] = callees
            by_name[key].add(node)
    print("method bodies walked: %d (malformed: %d)" % (len(nodes), malformed))

    # Conservative virtual dispatch: a call to X.m may run any override of m in
    # a subtype of X. Index every class's ancestors once, then add the edges.
    ancestors = {}

    def ancestor_set(fqcn, seen=None):
        if fqcn in ancestors:
            return ancestors[fqcn]
        if seen is None:
            seen = set()
        if fqcn in seen:
            return set()
        seen.add(fqcn)
        cf = classes.get(fqcn)
        if cf is None:
            ancestors[fqcn] = set()
            return ancestors[fqcn]
        out = set()
        parents = [cf.super_name.replace("/", ".")] + [i.replace("/", ".") for i in cf.interfaces]
        for p in parents:
            if p and p != fqcn:
                out.add(p)
                out |= ancestor_set(p, seen)
        ancestors[fqcn] = out
        return out

    edges = defaultdict(set)
    certain_edges = set()
    for key, callees in nodes.items():
        for owner, name, desc, op in callees:
            if owner == "~lambda~":
                continue
            owner_dot = owner.replace("/", ".")
            static = owner_dot + "#" + name + desc
            edges[key].add(static)
            if op not in (0xB6, 0xB9):      # only invokevirtual / invokeinterface
                certain_edges.add((key, static))
                continue
            # Virtual dispatch: every subtype of owner that declares the same
            # name+desc is a possible target. With exactly one, the target is
            # certain; with several, the runtime type decides and this analysis
            # cannot know -- that edge is only "possible", so a finding that
            # rests on one is reported separately from a finding that does not.
            targets = [o for o in by_name.get(name + desc, ())
                       if o.split("#", 1)[0] == owner_dot
                       or owner_dot in ancestor_set(o.split("#", 1)[0])]
            for t in targets:
                edges[key].add(t)
            if len(targets) == 1:
                certain_edges.add((key, targets[0]))

    # ---- sinks -------------------------------------------------------------
    # Order matters. A node that is both a deferral boundary and something else
    # is a deferral boundary: the addon's own mixin intercepts it, so a path
    # that reaches one stops there and the server thread does the rest.
    sink_nodes = {}
    for node in nodes:
        fqcn, _, sig = node.partition("#")
        owner = fqcn.replace(".", "/")
        name = sig.split("(")[0]
        for table in (DEFERRED, UNHANDLED, HANDLED):
            for label, owner_re, name_re in table:
                if owner_re.search(owner) and name_re.search(name):
                    sink_nodes[node] = label
                    break
            else:
                continue
            break

    # The hole hunt is a SEPARATE pass with the deferral boundaries removed from
    # the sink set, so a tick that both ejects (deferred) and reads the world
    # somewhere else still reports the read. Stopping at the deferred call would
    # hide it, because that path is usually the shorter one.
    unhandled_nodes = {}
    for node in nodes:
        fqcn, _, sig = node.partition("#")
        owner = fqcn.replace(".", "/")
        name = sig.split("(")[0]
        for label, owner_re, name_re in UNHANDLED:
            if owner_re.search(owner) and name_re.search(name):
                unhandled_nodes[node] = label
                break

    # ---- reverse reachability ---------------------------------------------
    reverse = defaultdict(set)
    for src, dsts in edges.items():
        for d in dsts:
            reverse[d].add(src)

    def spread(sinks, allowed):
        """Shortest path from every node to a sink, over the allowed edges."""
        seen = {}
        queue = deque()
        for node, label in sinks.items():
            seen[node] = (label, [node])
            queue.append(node)
        while queue:
            node = queue.popleft()
            label, path = seen[node]
            for parent in reverse[node]:
                if parent in seen or (parent, node) not in allowed:
                    continue
                seen[parent] = (label, [parent] + path)
                queue.append(parent)
        return seen

    certain = spread(sink_nodes, certain_edges)
    every = spread(sink_nodes, set((s, d) for s, ds in edges.items() for d in ds))

    # Holes: the deferral boundaries are removed from the graph entirely, as
    # nodes AND as edges. A mixin wraps the whole deferred method, so everything
    # below it runs on the server thread too -- following the call into its body
    # would report the server thread's own work as a worker-side hole. What is
    # left is the world reach a tick has that no mixin intercepts.
    deferred_nodes = {n for n, lbl in sink_nodes.items() if lbl.startswith("deferred:")}
    hole_edges = set((s, d) for s, ds in edges.items() for d in ds
                     if s not in deferred_nodes and d not in deferred_nodes)
    holes_certain = spread(unhandled_nodes, certain_edges & hole_edges)
    holes_every = spread(unhandled_nodes, hole_edges)

    def first_uncertain(path):
        """The edge that makes this path only a possibility, or None."""
        for a, b in zip(path, path[1:]):
            if (a, b) not in certain_edges:
                return a, b
        return None

    # ---- which covered ticks reach what -----------------------------------
    def chain(fqcn):
        out, seen, cur = [], set(), fqcn
        while cur and cur not in seen and cur != "java.lang.Object":
            out.append(cur)
            seen.add(cur)
            cf = classes.get(cur)
            cur = cf.super_name.replace("/", ".") if cf else None
        return out

    shape_set = set(shapes)
    covered = [c for c in classes if shape_set & set(chain(c))]
    print("shape-allowed block entities: %d" % len(covered))

    def tidy(path):
        return ["%s.%s" % (p.split("#")[0], p.split("#")[1].split("(")[0]) for p in path]

    findings = []
    for fqcn in sorted(covered):
        node = fqcn + "#onUpdateServer()Z"
        # Where does this tick stop under the addon's own mixins?
        entry = certain.get(node) or every.get(node)
        boundary = entry[0] if entry else None
        # Separately: does it reach something no mixin intercepts?
        hole = holes_certain.get(node) or holes_every.get(node)
        if hole is None:
            findings.append({"class": fqcn, "boundary": boundary, "sink": None,
                             "certain": True, "path": []})
            continue
        label, path = hole
        weak = first_uncertain(path)
        findings.append({
            "class": fqcn, "boundary": boundary, "sink": label,
            "certain": node in holes_certain,
            "path": tidy(path),
            "weak_edge": ["%s.%s" % (weak[0].split("#")[0], weak[0].split("#")[1].split("(")[0]),
                          "%s.%s" % (weak[1].split("#")[0], weak[1].split("#")[1].split("(")[0])]
            if weak else None,
        })

    sure = [f for f in findings if f["sink"] and f["certain"]]
    maybe = [f for f in findings if f["sink"] and not f["certain"]]
    clean = [f for f in findings if not f["sink"]]
    print("covered ticks: %d" % len(findings))
    print("  stop at a deferral boundary, no unhandled world API reachable: %d" % len(clean))
    print("  CERTAINLY reach an unhandled world API: %d" % len(sure))
    print("  could, if a virtual receiver is one particular subtype: %d\n" % len(maybe))

    if sure:
        print("--- CERTAIN ---")
        for f in sure:
            print("== %s   [%s]" % (f["class"], f["sink"]))
            print("   " + "\n   -> ".join(f["path"][:14]))
            print()

    # Group the possibilities by the edge they hinge on: that edge is the real
    # question, not the individual machine.
    by_edge = defaultdict(list)
    for f in maybe:
        by_edge[tuple(f["weak_edge"]) if f["weak_edge"] else ("?", "?")].append(f)
    if by_edge:
        print("--- POSSIBLE, by the call site the answer hinges on ---")
        for (a, b), group in sorted(by_edge.items(), key=lambda kv: -len(kv[1])):
            print("\n%2d machines hinge on:  %s  ->  %s" % (len(group), a, b))
            for f in group:
                print("      %s   (nearest boundary: %s)" % (f["class"], f["boundary"]))

    out_path = os.path.join(workdir or ".", "reach_findings.json")
    json.dump(findings, open(out_path, "w"), indent=2)
    print("wrote %s" % out_path)


if __name__ == "__main__":
    main()
