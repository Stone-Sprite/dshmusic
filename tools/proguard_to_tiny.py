#!/usr/bin/env python3
"""
Turn Mojang's ProGuard mapping file into Tiny v2 for the *first* hop of a
two-pass remap:  mojang -> official.

Why two passes instead of composing mojang <-> intermediary directly:

    DataComponentHolder   official `c`  -> intermediary method_57826  (mojang: has)
    DataComponentMap      official `c`  -> intermediary method_57832  (mojang: has)

Both declaring classes spell the method `has` with the same descriptor in
Mojang names, but the two intermediary names differ. Asking tiny-remapper to
rename *named* -> intermediary therefore sees one source name with two targets
and aborts with "Unfixable conflicts". Routing through `official` removes the
ambiguity entirely, because official names are unique per declaring class --
and it is also the namespace Fabric's own mappings.tiny is keyed on, so the
second hop can use that file verbatim with no join and no lost entries.

In this file the first namespace is `mojang`, so member descriptors are written
in Mojang names -- which is exactly what ProGuard already gives us.

    python proguard_to_tiny.py <proguard.txt> <out.tiny>
"""

import re
import sys
from pathlib import Path

PRIMITIVES = {
    "void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
    "int": "I", "long": "J", "float": "F", "double": "D",
}


def type_to_desc(t: str) -> str:
    """`com.mojang.blaze3d.Blaze3D` -> `Lcom/mojang/blaze3d/Blaze3D;`"""
    t = t.strip()
    if not t:
        return ""
    if t.endswith("[]"):
        return "[" + type_to_desc(t[:-2])
    if t.endswith("..."):
        return "[" + type_to_desc(t[:-3])
    if t in PRIMITIVES:
        return PRIMITIVES[t]
    if len(t) == 1 and t in "VZBSCIJFD":
        return t
    return "L" + t.replace(".", "/") + ";"


RE_CLASS = re.compile(r"^([\w$.]+) -> ([\w$]+):$")
# 12:13:void foo(int,long) -> a      (the line-number prefix is optional)
RE_METHOD = re.compile(r"^\s*(?:\d+:\d+:)?([\w$.\[\]]+) ([\w$<>]+)\(([^)]*)\) -> (\w+)$")
RE_FIELD = re.compile(r"^\s*([\w$.\[\]]+) ([\w$]+) -> (\w+)$")


def main() -> int:
    src = Path(sys.argv[1] if len(sys.argv) > 1
               else r"D:\.minecraft\libraries\net\minecraft\client\1.21.11\client-1.21.11-mappings.txt")
    dst = Path(sys.argv[2] if len(sys.argv) > 2
               else r"D:\dsh-build\mojmap-official.tiny")

    classes = []          # (mojang, official) in file order
    members = []          # (officialOwner, kind, descMojang, mojangName, officialName)
    current = None
    n_m = n_f = 0

    for raw in src.read_text(encoding="utf-8", errors="replace").splitlines():
        if not raw or raw.lstrip().startswith("#"):
            continue
        m = RE_CLASS.match(raw)
        if m:
            current = m.group(2)                       # official
            classes.append((m.group(1).replace(".", "/"), current))
            continue
        if current is None:
            continue
        m = RE_METHOD.match(raw)
        if m:
            ret, name, args, obf = m.groups()
            desc = "(" + "".join(
                type_to_desc(a) for a in args.split(",") if a.strip()
            ) + ")" + type_to_desc(ret)
            members.append((current, "m", desc, name, obf))
            n_m += 1
            continue
        m = RE_FIELD.match(raw)
        if m:
            ftype, name, obf = m.groups()
            members.append((current, "f", type_to_desc(ftype), name, obf))
            n_f += 1

    by_owner = {}
    for owner, kind, desc, moj, obf in members:
        by_owner.setdefault(owner, []).append((kind, desc, moj, obf))

    with dst.open("w", encoding="utf-8", newline="\n") as f:
        # first namespace = mojang, so every descriptor below is in Mojang names
        f.write("tiny\t2\t0\tmojang\tofficial\n")
        for moj, official in classes:
            f.write(f"c\t{moj}\t{official}\n")
            for kind, desc, m_moj, m_obf in by_owner.get(official, []):
                f.write(f"\t{kind}\t{desc}\t{m_moj}\t{m_obf}\n")

    print(f"classes={len(classes)}  methods={n_m}  fields={n_f}")
    print(f"wrote {dst}  ({dst.stat().st_size / 1024 / 1024:.1f} MB)")

    # sanity: the one that crashed in game
    with dst.open(encoding="utf-8") as f:
        for line in f:
            if "fromNamespaceAndPath" in line:
                print("  sanity:", line.rstrip())
                break
    return 0


if __name__ == "__main__":
    sys.exit(main())
