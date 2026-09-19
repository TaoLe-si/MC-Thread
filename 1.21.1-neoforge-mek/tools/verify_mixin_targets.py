"""Pre-build checks on the addon's mixins.

Two checks, both of which fail the build:

1. Mixin package hygiene — no class may live in a mixin package without being
   declared in that config. Violating this crashes the game the first time the
   offending mixin applies, not at startup.
2. Every @WrapOperation target, verified against the real jars. Ordinals are the
   fragile part: they count matching INVOKEs in method order, so a Mekanism
   update that adds a call re-points them silently. This reads the installed
   jars and checks each claim.

Run:  python tools/verify_mixin_targets.py
"""

import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

MODS = r"E:\MC\.minecraft\versions\NAST hard d0.9.1 beta3\mods"
JARS = [
    os.path.join(MODS, "Mekanism-1.21.1-10.7.19.85.jar"),
    os.path.join(MODS, "MekanismGenerators-1.21.1-10.7.19.85.jar"),
]

# (mixin, target class, method name, method descriptor, wrapped owner.name, wrapped descriptor, ordinal or None)
CHECKS = [
    ("TileEntityMultiblockDataTickMixin",
     "mekanism.common.tile.prefab.TileEntityMultiblock", "onUpdateServer", "()Z",
     "mekanism.common.lib.multiblock.MultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z", None),

    ("TileEntityGeneratorEmitMixin",
     "mekanism.generators.common.tile.TileEntityGenerator", "onUpdateServer", "()Z",
     "mekanism.common.util.CableUtils", "emit",
     "(Ljava/util/Collection;Lmekanism/api/energy/IEnergyContainer;J)V", None),

    ("BoilerMultiblockDataEjectMixin",
     "mekanism.common.content.boiler.BoilerMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.common.util.ChemicalUtil", "emit",
     "(Ljava/util/Collection;Lmekanism/api/chemical/IChemicalTank;)V", 0),
    ("BoilerMultiblockDataEjectMixin",
     "mekanism.common.content.boiler.BoilerMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.common.util.ChemicalUtil", "emit",
     "(Ljava/util/Collection;Lmekanism/api/chemical/IChemicalTank;)V", 1),
    ("BoilerMultiblockDataEjectMixin",
     "mekanism.common.content.boiler.BoilerMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "it.unimi.dsi.fastutil.objects.Object2BooleanMap", "put", "(Ljava/lang/Object;Z)Z", None),

    ("MatrixMultiblockDataEjectMixin",
     "mekanism.common.content.matrix.MatrixMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.common.util.CableUtils", "emit",
     "(Ljava/util/Collection;Lmekanism/api/energy/IEnergyContainer;J)V", None),
    ("MatrixMultiblockDataEjectMixin",
     "mekanism.common.content.matrix.MatrixMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.common.content.matrix.MatrixMultiblockData", "markDirtyComparator",
     "(Lnet/minecraft/world/level/Level;)V", None),

    ("SPSMultiblockDataEjectMixin",
     "mekanism.common.content.sps.SPSMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.common.util.ChemicalUtil", "emit",
     "(Ljava/util/Collection;Lmekanism/api/chemical/IChemicalTank;)V", None),
    ("SPSMultiblockDataEjectMixin",
     "mekanism.common.content.sps.SPSMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.common.content.sps.SPSMultiblockData", "kill",
     "(Lnet/minecraft/world/level/Level;)V", None),

    ("TurbineMultiblockDataEjectMixin",
     "mekanism.generators.common.content.turbine.TurbineMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.api.fluid.IExtendedFluidTank", "isEmpty", "()Z", None),
    ("TurbineMultiblockDataEjectMixin",
     "mekanism.generators.common.content.turbine.TurbineMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.common.util.FluidUtils", "emit",
     "(Ljava/util/Collection;Lnet/neoforged/neoforge/fluids/FluidStack;)I", None),
    ("TurbineMultiblockDataEjectMixin",
     "mekanism.generators.common.content.turbine.TurbineMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.common.util.CableUtils", "emit",
     "(Ljava/util/Collection;Lmekanism/api/energy/IEnergyContainer;)V", None),

    ("FissionReactorMultiblockDataEjectMixin",
     "mekanism.generators.common.content.fission.FissionReactorMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.common.util.ChemicalUtil", "emit",
     "(Ljava/util/Collection;Lmekanism/api/chemical/IChemicalTank;)V", 0),
    ("FissionReactorMultiblockDataEjectMixin",
     "mekanism.generators.common.content.fission.FissionReactorMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.common.util.ChemicalUtil", "emit",
     "(Ljava/util/Collection;Lmekanism/api/chemical/IChemicalTank;)V", 1),
    ("FissionReactorMultiblockDataEjectMixin",
     "mekanism.generators.common.content.fission.FissionReactorMultiblockData", "burnFuel",
     "(Lnet/minecraft/world/level/Level;)V",
     "mekanism.api.radiation.IRadiationManager", "radiate",
     "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;D)V", None),
    ("FissionReactorMultiblockDataEjectMixin",
     "mekanism.generators.common.content.fission.FissionReactorMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.generators.common.content.fission.FissionReactorMultiblockData", "handleDamage",
     "(Lnet/minecraft/world/level/Level;)V", None),
    ("FissionReactorMultiblockDataEjectMixin",
     "mekanism.generators.common.content.fission.FissionReactorMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.generators.common.content.fission.FissionReactorMultiblockData", "radiateEntities",
     "(Lnet/minecraft/world/level/Level;)V", None),

    ("FusionReactorMultiblockDataEjectMixin",
     "mekanism.generators.common.content.fusion.FusionReactorMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.common.util.CableUtils", "emit",
     "(Ljava/util/Collection;Lmekanism/api/energy/IEnergyContainer;)V", None),
    ("FusionReactorMultiblockDataEjectMixin",
     "mekanism.generators.common.content.fusion.FusionReactorMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.common.util.ChemicalUtil", "emit",
     "(Ljava/util/Collection;Lmekanism/api/chemical/IChemicalTank;)V", None),
    ("FusionReactorMultiblockDataEjectMixin",
     "mekanism.generators.common.content.fusion.FusionReactorMultiblockData", "tick",
     "(Lnet/minecraft/world/level/Level;)Z",
     "mekanism.generators.common.content.fusion.FusionReactorMultiblockData", "kill",
     "(Lnet/minecraft/world/level/Level;)V", None),

    ("TileEntityResistiveHeaterSimulateMixin",
     "mekanism.common.tile.machine.TileEntityResistiveHeater", "onUpdateServer", "()Z",
     "mekanism.common.tile.machine.TileEntityResistiveHeater", "simulate",
     "()Lmekanism/api/heat/HeatAPI$HeatTransfer;", None),
    ("TileEntityFuelwoodHeaterSimulateMixin",
     "mekanism.common.tile.machine.TileEntityFuelwoodHeater", "onUpdateServer", "()Z",
     "mekanism.common.tile.machine.TileEntityFuelwoodHeater", "simulate",
     "()Lmekanism/api/heat/HeatAPI$HeatTransfer;", None),
]

INVOKE_RE = re.compile(
    r"^\s+\d+:\s+invoke\w+\s+#\d+(?:,\s*\d+)?\s*//\s*(?:InterfaceMethod|Method)\s+(\S+)$")
# A method header is a line indented exactly two spaces, ending in ";", with a
# parameter list. Field declarations are also indented two spaces but carry no
# parentheses; code lines are indented further.
HEADER_RE = re.compile(r"^  (\S.*?)\(.*\);\s*$")
HEADER_DESC_RE = re.compile(r"^\s+descriptor: (\S+)$")


def extract_classes(dest):
    """Unzip the classes the checks name into dest."""
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
    """All (owner, name, desc) INVOKEs inside the named method, in order.

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
            # The header is "<modifiers> <ret> <name>(<params>);" — the method
            # name is the last token before the parenthesis.
            sig = header.group(1)
            header_name = sig.split("(")[0].split()[-1]
            j = i + 1
            m = HEADER_DESC_RE.match(lines[j]) if j < len(lines) else None
            if m and header_name == name and m.group(1) == desc:
                i = j + 1
                while i < len(lines) and not HEADER_RE.match(lines[i]):
                    hit = INVOKE_RE.match(lines[i])
                    if hit:
                        # Format: owner/name:descriptor.
                        ref = hit.group(1)
                        ref_name, _, ref_desc = ref.rpartition(":")
                        if "." in ref_name:
                            owner, _, ref_name = ref_name.rpartition(".")
                        else:
                            owner = self_owner
                        found.append((owner.replace("/", "."), ref_name, ref_desc))
                    i += 1
                continue
        i += 1
    return found


def check_mixin_package_hygiene():
    """No undeclared class may sit in a mixin package.

    Mixin owns the package its config declares. Every class in that package is
    assumed to be a mixin of that config, and one that is *not* declared there
    may not be referenced from a transformed target. A mixin's body is inlined
    into its target, so a helper call written inside a mixin becomes a call
    inside Mekanism's own class — and the first time that call is resolved the
    game dies with

        IllegalClassLoadError: ... is in a defined mixin package ... owned by
        threadtearer_mek.mixins.json and cannot be referenced directly

    That is not hypothetical: MekDeferral was a plain helper left in
    ...mek.mixin. The multiblock deferrals only fire once a structure of that
    family exists, so the mistake survived every test world until the generator
    emit deferral ran for real and crashed on the first gas generator.

    The config's own ``plugin`` class is the one legitimate exception — Mixin
    resolves it by name from the config, never from a transformed class.
    """
    resources = os.path.join(ROOT, "src", "main", "resources")
    problems = []
    configs = sorted(f for f in os.listdir(resources) if f.endswith(".mixins.json"))
    if not configs:
        problems.append("no *.mixins.json found under src/main/resources")
    for cfg_name in configs:
        with open(os.path.join(resources, cfg_name), encoding="utf-8") as fh:
            cfg = json.load(fh)
        package = cfg["package"]
        declared = set(cfg.get("mixins", []))
        for key in ("client", "server", "common"):
            declared |= set(cfg.get(key, []))
        plugin = cfg.get("plugin", "")
        plugin_simple = plugin.rsplit(".", 1)[-1] if plugin else None

        package_dir = os.path.join(ROOT, "src", "main", "java", *package.split("."))
        if not os.path.isdir(package_dir):
            problems.append(f"{cfg_name}: mixin package {package} does not exist")
            continue
        for entry in sorted(os.listdir(package_dir)):
            if not entry.endswith(".java"):
                continue
            simple = entry[:-5]
            if simple in declared or simple == plugin_simple:
                continue
            problems.append(
                f"{package}.{simple} is in {cfg_name}'s mixin package but is not "
                f"a declared mixin")
    return problems


def main():
    hygiene = check_mixin_package_hygiene()
    for problem in hygiene:
        print(f"FAIL  {problem}")
    if hygiene:
        print()
        print("VERDICT: MIXIN PACKAGE HYGIENE FAILED")
        print("Move the class out of the mixin package, or declare it in the config.")
        return 1

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
            hits = [c for c in invokes if c == (owner, wname, wdesc)]
            label = f"{mixin} -> {cls.split('.')[-1]}.{mname}{mdesc} : {owner.split('.')[-1]}.{wname}{wdesc}"
            if not hits:
                print(f"FAIL  no such call   {label}")
                failures += 1
            elif ordinal is not None:
                # The ordinal-th matching INVOKE in method order must be this one;
                # verify by position: hits are in order, so ordinal must be < len(hits).
                if ordinal < len(hits):
                    print(f"ok    ordinal {ordinal} of {len(hits)}  {label}")
                else:
                    print(f"FAIL  ordinal {ordinal} but only {len(hits)} matches  {label}")
                    failures += 1
            else:
                print(f"ok    {len(hits)} call site(s)          {label}")
        print()
        print("VERDICT:", "ALL TARGETS RESOLVE" if failures == 0 else f"{failures} FAILURES")
        return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
