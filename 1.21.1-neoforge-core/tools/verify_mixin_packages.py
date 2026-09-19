"""No undeclared class may sit in a mixin package.

Mixin owns the package its config declares. Every class in that package is
assumed to be a mixin of that config, and one that is *not* declared there may
not be referenced from a transformed target. A mixin's body is inlined into its
target, so a helper call written inside a mixin becomes a call inside the
transformed class -- and the first time that call is resolved the game dies:

    IllegalClassLoadError: ... is in a defined mixin package ... owned by
    threadtearer.mixins.json and cannot be referenced directly

This crashed the Mekanism addon: a plain helper (MekDeferral) sat in
...mek.mixin, and because the mixins that reached it only apply once a matching
multiblock structure exists in the world, the mistake survived every test world
until a generator emit deferral ran for real.

The config's own `plugin` class is the one legitimate exception: Mixin resolves
it by name from the config, never from a transformed class.

Run:  python tools/verify_mixin_packages.py
"""

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def check(config_name):
    resources = os.path.join(ROOT, "src", "main", "resources")
    cfg_path = os.path.join(resources, config_name)
    if not os.path.isfile(cfg_path):
        return [f"{config_name} not found under src/main/resources"]

    with open(cfg_path, encoding="utf-8") as fh:
        cfg = json.load(fh)

    package = cfg["package"]
    declared = set(cfg.get("mixins", []))
    for key in ("client", "server", "common"):
        declared |= set(cfg.get(key, []))
    plugin = cfg.get("plugin", "")
    plugin_simple = plugin.rsplit(".", 1)[-1] if plugin else None

    package_dir = os.path.join(ROOT, "src", "main", "java", *package.split("."))
    if not os.path.isdir(package_dir):
        return [f"{config_name}: mixin package {package} does not exist"]

    problems = []
    for entry in sorted(os.listdir(package_dir)):
        if not entry.endswith(".java"):
            continue
        simple = entry[:-5]
        if simple in declared or simple == plugin_simple:
            continue
        problems.append(
            f"{package}.{simple} is in {config_name}'s mixin package but is not "
            f"a declared mixin")
    return problems


def main():
    problems = []
    for cfg in ("threadtearer.mixins.json", "threadtearer.mixins.client.json"):
        if os.path.isfile(os.path.join(ROOT, "src", "main", "resources", cfg)):
            problems += check(cfg)

    for problem in problems:
        print(f"FAIL  {problem}")
    if problems:
        print()
        print("Move the class out of the mixin package, or declare it in the config.")
        print("VERDICT: MIXIN PACKAGE HYGIENE FAILED")
        return 1
    print("VERDICT: MIXIN PACKAGES CLEAN")
    return 0


if __name__ == "__main__":
    sys.exit(main())
