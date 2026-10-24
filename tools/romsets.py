#!/usr/bin/env python3
"""Builds android/core/src/main/resources/romsets.json from FBNeo's Neo Geo driver.

Lists, for every version (set) of the games the app supports, the files FBNeo
loads with their sizes and CRC32s. The app checks imported zips against it.

Usage: tools/romsets.py path/to/FBNeo/src/burn/drv/neogeo/d_neogeo.cpp
(raw.githubusercontent.com/finalburnneo/FBNeo/master/src/burn/drv/neogeo/d_neogeo.cpp)
"""
import json
import os
import re
import sys

# The sets the app's games are listed under (core Games.kt); their clones are added.
BASE = ["kof97", "kof2002", "kf2k5uni", "kof10th", "kf10thep",
        "mslug", "mslug2", "mslugx", "mslug3", "mslug4", "mslug5", "sengoku3"]

src = open(sys.argv[1], encoding="latin-1").read()

ROM = re.compile(r'\{\s*"([^"]*)"\s*,\s*(0x[0-9a-fA-F]+|\d+)\s*,\s*(0x[0-9a-fA-F]+)\s*,\s*([^}]*)\}')
descs = {}
for m in re.finditer(r'static struct BurnRomInfo (\w+)RomDesc\[\] = \{(.*?)\n\};', src, re.S):
    files = []
    for line in m.group(2).splitlines():
        if line.strip().startswith("#"):
            continue  # ROM_VERIFY variants: keep the normal build's entries
        r = ROM.search(line.split("//")[0])
        if not r or not r.group(1):
            continue
        name, size, crc, flags = r.group(1), int(r.group(2), 0), int(r.group(3), 16), r.group(4)
        optional = "BRF_OPT" in flags or "BRF_NODUMP" in flags or crc == 0
        files.append({"name": name, "size": size, "crc": "%08x" % crc, "optional": optional})
    descs[m.group(1)] = files

drivers = {}
for m in re.finditer(r'struct BurnDriver\w* BurnDrv\w+ = \{\s*"(\w+)"\s*,\s*(NULL|"\w+")\s*,\s*(NULL|"\w+")(.*?)\n\};', src, re.S):
    name = m.group(1)
    parent = None if m.group(2) == "NULL" else m.group(2).strip('"')
    info = re.search(r'(\w+)RomInfo', m.group(4))
    title = re.search(r'"([^"\\]*)\\0"', m.group(4))
    drivers[name] = {
        "parent": parent,
        "desc": info.group(1) if info else None,
        "title": title.group(1) if title else name,
        "working": "BDF_GAME_WORKING" in m.group(4),
    }

def game_of(name):
    """The base set a driver belongs to: itself, or its parent's."""
    while name and name not in BASE:
        name = drivers.get(name, {}).get("parent")
    return name

sets = []
for name, d in sorted(drivers.items()):
    base = game_of(name)
    # The app shows the 10th Anniversary hacks of 2002 as "KOF 2005".
    if base == "kof2002" and "10th Anniversary" in d["title"]:
        base = "kof10th"
    if not base or not d["working"] or d["desc"] not in descs:
        continue
    sets.append({"name": name, "parent": d["parent"], "base": base, "title": d["title"], "files": descs[d["desc"]]})

missing = [b for b in BASE if b not in {s["name"] for s in sets}]
if missing:
    sys.exit("not found in the driver: %s" % missing)

bios = [f for f in descs["neogeo"] if f["name"] in ("sp-s3.sp1", "sm1.sm1", "sfix.sfix", "000-lo.lo")]
out = os.path.join(os.path.dirname(__file__), "..", "android/core/src/main/resources/romsets.json")
with open(out, "w") as f:
    json.dump({"source": "FBNeo src/burn/drv/neogeo/d_neogeo.cpp", "bios": bios, "sets": sets}, f, indent=1)
    f.write("\n")
print("%d sets, %d BIOS files -> %s" % (len(sets), len(bios), os.path.normpath(out)))
for s in sets:
    print("  %-10s %-10s %s" % (s["name"], s["base"], s["title"]))
