"""Verify mixin package hygiene: every class sitting inside a mixin package
must be declared in the matching mixins.json.

Mixin treats any class under a config's declared ``package`` as a mixin
candidate. If a non-mixin class (a helper, an enum, a record) lives there,
Mixin may try to transform it and find none of the annotations it expects —
``IllegalClassLoadException`` at runtime, the same crash shape the mek
0.3.7 hotfix added this gate for.

Run:  python tools/verify_mixin_packages.py
"""

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESOURCES = os.path.join(ROOT, "src", "main", "resources")


def declared_class_names(cfg):
    """Every mixin class name declared in the config, flat namespace + client/server."""
    names = set(cfg.get("mixins", []))
    for key in ("client", "server", "common"):
        names.update(cfg.get(key, []))
    return names


def main():
    if not os.path.isdir(RESOURCES):
        print(f"missing {RESOURCES}")
        return 1

    configs = sorted(f for f in os.listdir(RESOURCES) if f.endswith(".mixins.json"))
    if not configs:
        print("no *.mixins.json found under src/main/resources")
        return 1

    problems = []
    java_root = os.path.join(ROOT, "src", "main", "java")

    for cfg_name in configs:
        with open(os.path.join(RESOURCES, cfg_name), encoding="utf-8") as fh:
            cfg = json.load(fh)
        package = cfg["package"]
        declared = declared_class_names(cfg)
        plugin = cfg.get("plugin", "")
        plugin_simple = plugin.rsplit(".", 1)[-1] if plugin else None

        package_dir = os.path.join(java_root, *package.split("."))
        if not os.path.isdir(package_dir):
            problems.append(f"{cfg_name}: package dir {package_dir} does not exist")
            continue

        on_disk = set()
        for entry in os.listdir(package_dir):
            if entry.endswith(".java") and not entry.startswith("package-info"):
                on_disk.add(entry[:-5])

        # The plugin class lives outside the mixin package by convention (the IF
        # and mek addons both put it next to the policy). It must be declared in
        # the config's "plugin" field, not as a regular mixin.
        if plugin_simple and plugin_simple in on_disk:
            problems.append(
                f"{cfg_name}: '{plugin_simple}' sits in the mixin package — it must "
                f"live outside (declarations of plugin= in {cfg_name} are looked up "
                f"by their FQCN, not by package)."
            )
            on_disk.discard(plugin_simple)

        # Undeclared files in a mixin package: the dangerous case.
        undeclared = on_disk - declared
        if undeclared:
            problems.append(
                f"{cfg_name}: class(es) sit in the mixin package but are not "
                f"listed in mixins[]: {sorted(undeclared)}. Either declare them or "
                f"move them out of the mixin package."
            )

        # Declared but missing: a typo'd mixin name crashes at runtime too.
        missing = declared - on_disk
        if missing:
            problems.append(
                f"{cfg_name}: mixin(s) declared but no source file found: "
                f"{sorted(missing)}"
            )

    if problems:
        for p in problems:
            print("FAIL", p)
        print(f"\nVERDICT: {len(problems)} PROBLEMS")
        return 1
    print("VERDICT: MIXIN PACKAGES CLEAN")
    return 0


if __name__ == "__main__":
    sys.exit(main())