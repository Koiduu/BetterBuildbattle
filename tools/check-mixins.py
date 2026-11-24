"""Checks that every mixin target exists in the Minecraft jar a Fabric build compiles against.

A mixin whose class, method or field is missing compiles fine but crashes the game on start
(betterbattle.mixins.json sets "required": true). CI runs this after each Fabric build:
    python3 tools/check-mixins.py <mixin source dir> <minecraft jar>
"""
import re
import subprocess
import sys
from pathlib import Path

src, jar = Path(sys.argv[1]), sys.argv[2]
problems, checked = [], 0

for f in sorted(src.glob("*.java")):
    text = f.read_text()
    target = re.search(r"@Mixin\((\w+)\.class\)", text)
    if not target:
        continue
    simple = target.group(1)
    imp = re.search(rf"import ([\w.]+\.{simple});", text)
    cls = imp.group(1) if imp else simple
    out = subprocess.run(["javap", "-p", "-s", "-cp", jar, cls], capture_output=True, text=True)
    if out.returncode != 0:
        problems.append(f"{f.name}: class {cls} not found")
        continue
    # javap -s prints each member, then its descriptor on the next line
    lines = out.stdout.splitlines()
    members = []
    for i, line in enumerate(lines):
        if "descriptor:" in line:
            members.append((lines[i - 1].strip(), line.split("descriptor:")[1].strip()))

    for name_desc in re.findall(r'@Inject\(method\s*=\s*"([^"]+)"', text):
        checked += 1
        m = re.match(r"([^(]+)(\(.*)?", name_desc)
        name, desc = m.group(1), m.group(2)
        java_name = cls.rsplit(".", 1)[1] if name == "<init>" else name
        found = any(
            (decl.split("(")[0].split()[-1] in (java_name, cls)) and (desc is None or d == desc)
            for decl, d in members if "(" in decl
        )
        if not found:
            problems.append(f"{f.name}: {cls}.{name_desc} not found")

    for field in re.findall(r'@Accessor\("([^"]+)"\)', text):
        checked += 1
        if not any("(" not in decl and re.search(rf"\b{re.escape(field)};$", decl) for decl, _ in members):
            problems.append(f"{f.name}: field {cls}.{field} not found")

for p in problems:
    print("MISSING", p)
print(f"{checked} mixin targets checked, {len(problems)} missing")
sys.exit(1 if problems or checked == 0 else 0)
