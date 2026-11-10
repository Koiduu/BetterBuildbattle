#!/usr/bin/env python3
"""Copy api/db/seeds/achievements.yaml into the mod as JSON. Run after editing the catalog:
    python3 tools/gen-catalog.py
"""
import json, pathlib, yaml

root = pathlib.Path(__file__).resolve().parent.parent
data = yaml.safe_load((root / "api/db/seeds/achievements.yaml").read_text())
roman = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"]
entries = []
for t in data["tracks"]:
    for i, n in enumerate(t["tiers"]):
        text = t["description"].replace("{n}", f"{n:,}")
        if n == 1:
            for w in ["games", "grades", "rounds", "bests", "builds", "themes", "days"]:
                text = text.replace(f" {w}", f" {w[:-1]}", 1)
        entries.append({"id": f"{t['track']}_t{i + 1}", "name": f"{t['name']} {roman[i]}", "description": text,
                        "category": t["category"], "points": t["points"][i], "secret": False,
                        "track": t["track"], "threshold": n})
for o in data["one_offs"]:
    entries.append({"id": o["id"], "name": o["name"], "description": o["description"], "category": o["category"],
                    "points": o["points"], "secret": bool(o.get("secret"))})
out = root / "mod/core/src/main/resources/betterbattle/achievements.json"
out.write_text(json.dumps({"version": data["catalog_version"], "achievements": entries}, indent=1) + "\n")
print(f"wrote {len(entries)} achievements to {out.relative_to(root)}")
