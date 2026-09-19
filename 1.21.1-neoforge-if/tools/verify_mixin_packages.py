"""No undeclared class may sit in a mixin package.

Mixin owns the package its config declares. Every class in that package is
assumed to be a mixin of that config, and one that is *not* declared there may
not be referenced from a transformed target. A mixin's body is inlined into
its target, so a helper call written inside a mixin becomes a call inside the
target class -- and the first time that call is resolved, Mixin aborts the game
with IllegalClassLoadError: ... is in a defined mixin package ... and cannot
be referenced directly.

This guard exists on every addon: threadtearer-core's
tools/verify_mixin_packages.py covers the core's mixin package; each addon
ships its own copy for its own mixin package. The IF addon is no exception.

Run:  python tools/verify_mixin_packages.py
"""

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main():
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