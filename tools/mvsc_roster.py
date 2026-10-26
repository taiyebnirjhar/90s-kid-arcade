"""Writes the Marvel vs Capcom move list, rosters/mvsc.json.

Moves from MAME's command.dat, SuperCombo and StrategyWiki (where they
disagree, what works in the real game: tools/movetest.py). Buttons as the
app lays them out (PadMap.CAPCOM_6): A B C = LP MP HP, D E F = LK MK HK.
A move for any punch or kick is written with the light one; "two punches"
is LP+MP (AB), "two kicks" LK+MK (DE). Hyper Combos are "dm". The ring
holds six moves, so each list starts with what matters most.
Run once: python3 tools/mvsc_roster.py
"""
import json
import os

P, K, PP, KK = "A", "D", "AB", "DE"
C360 = "63214789"


def sp(name, cmd, note=""):
    return (name, cmd, "special", note)


def hy(name, cmd, note=""):
    return (name, cmd, "dm", note)


# id: the character's number in the game (the byte at +0x53 of a fighter block).
CHARACTERS = [
    ("spiderman", 0x0E, "Spider-Man", "Marvel", [
        hy("Maximum Spider", "236+" + PP, "also in air"),
        hy("Crawler Assault", "236+" + KK),
        hy("Ultimate Web Throw", "214+" + PP),
        sp("Web Ball", "236+" + P, "also in air"),
        sp("Spider Sting", "623+" + P),
        sp("Web Throw", "63214+" + P, "close"),
    ]),
    ("hulk", 0x06, "Hulk", "Marvel", [
        hy("Gamma Wave", "236+" + PP),
        hy("Gamma Quake", "236+" + KK),
        hy("Gamma Crush", "214+" + PP),
        sp("Gamma Slam", "236+" + P),
        sp("Gamma Charge", "[4]6+" + K),
        sp("Gamma Tornado", "63214+" + P, "close"),
    ]),
    ("warmachine", 0x02, "War Machine", "Marvel", [
        hy("Proton Cannon", "236+" + PP),
        hy("War Destroyer", "236+" + KK),
        sp("Shoulder Cannon", "236+" + P, "also in air"),
        sp("Low Shoulder Cannon", "236+" + K),
        sp("Repulsor Blast", "63214+" + P),
        sp("Smart Bomb", "BD", "also in air"),
    ]),
    ("captainamerica", 0x04, "Captain America", "Marvel", [
        hy("Final Justice", "236+" + PP),
        hy("Hyper Stars & Stripes", "623+" + PP),
        hy("Hyper Charging Star", "236+" + KK),
        sp("Shield Slash", "236+" + P, "also in air"),
        sp("Stars & Stripes", "623+" + P),
        sp("Charging Star", "236+" + K),
    ]),
    ("venom", 0x0C, "Venom", "Marvel", [
        hy("Venom Web", "236+" + PP),
        hy("Death Bite", "236+" + KK),
        sp("Venom Fang", "236+" + P, "also in air"),
        sp("Venom Rush", "236+" + K),
        sp("Web Throw", "63214+" + P, "close"),
    ]),
    ("wolverine", 0x08, "Wolverine", "Marvel", [
        hy("Berserker Barrage X", "236+" + PP),
        hy("Weapon X", "623+" + PP),
        hy("Fatal Claw", "623+" + KK),
        sp("Berserker Barrage", "236+" + P),
        sp("Tornado Claw", "623+" + P),
        sp("Drill Claw", "BD", "also in air"),
    ]),
    ("gambit", 0x0A, "Gambit", "Marvel", [
        hy("Royal Flush", "236+" + PP),
        hy("Cajun Explosion", "236+" + KK),
        sp("Kinetic Card", "236+" + P, "also in air"),
        sp("Cajun Slash", "623+" + P),
        sp("Trick Card", "214+" + P),
        sp("Cajun Strike", "[2]8+" + K),
    ]),
    ("ryu", 0x12, "Ryu", "Capcom", [
        hy("Shinkuu Hadouken", "236+" + PP, "also in air"),
        hy("Shin Shouryuuken", "623+" + PP),
        hy("Shinkuu Tatsumaki", "214+" + KK),
        sp("Hadouken", "236+" + P, "also in air"),
        sp("Shouryuuken", "623+" + P),
        sp("Tatsumaki Senpuu Kyaku", "214+" + K, "also in air"),
    ]),
    ("chunli", 0x16, "Chun-Li", "Capcom", [
        hy("Kikou Shou", "236+" + PP),
        hy("Senretsu Kyaku", "236+" + KK),
        hy("Hazan Tenshou Kyaku", "623+" + KK),
        sp("Kikou Ken", "41236+" + P),
        sp("Tenshou Kyaku", "623+" + K),
        sp("Hyakuretsu Kyaku", "D,D,D,D,D", "mash"),
    ]),
    ("zangief", 0x1A, "Zangief", "Capcom", [
        hy("Final Atomic Buster", C360 + "+" + PP, "close"),
        sp("Spinning Piledriver", C360 + "+" + P, "close"),
        sp("Double Lariat", "ABC"),
        sp("Atomic Suplex", "41236+" + K, "close"),
        sp("Banishing Flat", "623+" + P),
        sp("Aerial Russian Slam", "623+" + K),
    ]),
    ("captaincommando", 0x14, "Captain Commando", "Capcom", [
        hy("Captain Sword", "236+" + PP),
        hy("Captain Storm", "236+" + KK),
        sp("Captain Fire", "236+" + P, "also in air"),
        sp("Captain Corridor", "214+" + P),
        sp("Captain Kick", "214+" + K),
        sp("Captain Strike", "236+" + K),
    ]),
    ("morrigan", 0x1E, "Morrigan", "Capcom", [
        hy("Soul Eraser", "236+" + PP),
        hy("Darkness Illusion", "236+" + KK, "also in air"),
        hy("Silhouette Blade", "623+" + PP),
        sp("Soul Fist", "236+" + P, "also in air"),
        sp("Shadow Blade", "623+" + P, "also in air"),
        sp("Vector Drain", "63214+" + P, "close"),
    ]),
    ("megaman", 0x20, "Mega Man", "Capcom", [
        hy("Hyper Mega Man", "236+" + PP),
        hy("Rush Drill", "236+" + KK),
        hy("Beat Plane", "214+" + KK, "also in air"),
        sp("Mega Upper", "623+" + P),
        sp("Item Attack", "236+" + P),
        sp("Item Change", "214+" + K),
    ]),
    ("strider", 0x1C, "Strider Hiryu", "Capcom", [
        hy("Ouroboros", "236+" + PP),
        hy("Ragnarok", "623+" + PP),
        hy("Legion", "236+" + KK),
        sp("Ame no Murakumo", "236+" + P),
        sp("Gram", "623+" + P),
        sp("Vajra", "214+" + K),
    ]),
    ("jin", 0x18, "Jin", "Capcom", [
        hy("Blodia Punch", "236+" + PP),
        hy("Blodia Vulcan", "214+" + PP),
        hy("Saotome Cyclone", "236+" + KK),
        sp("Saotome Typhoon", "[4]6+" + P),
        sp("Saotome Dynamite", "[2]8+" + P),
        sp("Saotome Crush", "63214+" + K, "close"),
    ]),
    # The secret characters (codes on the select screen).
    ("redvenom", 0x24, "Red Venom", "Secret", [
        hy("Venom Web", "236+" + PP),
        hy("Death Bite", "236+" + KK),
        sp("Venom Crush", "236+" + P),
        sp("Venom Storm", "236+" + K),
        sp("Venom Charge", "236+" + P, "in air"),
        sp("Web Throw", "63214+" + P, "close"),
    ]),
    ("orangehulk", 0x26, "Orange Hulk", "Secret", [
        hy("Gamma Wave", "236+" + PP),
        hy("Gamma Quake", "236+" + KK),
        hy("Gamma Crush", "214+" + PP),
        sp("Gamma Slam", "236+" + P),
        sp("Gamma Charge", "[4]6+" + K),
        sp("Gamma Tornado", "63214+" + P, "close"),
    ]),
    ("goldwarmachine", 0x28, "Gold War Machine", "Secret", [
        hy("Proton Cannon", "236+" + PP),
        hy("War Destroyer", "236+" + KK),
        sp("Shoulder Cannon", "236+" + P, "also in air"),
        sp("Low Shoulder Cannon", "236+" + K),
        sp("Smart Bomb", "BD", "also in air"),
    ]),
    ("shadowlady", 0x2A, "Shadow Lady", "Secret", [
        hy("Big Bang Laser", "236+" + PP),
        hy("Galaxy Missile", "236+" + KK),
        sp("Miracle Drill", "236+" + P),
        sp("Rainbow Missile", "236+" + K),
        sp("Sky Counter Crash", "63214+" + K),
        sp("Plasma Barrier", "623+" + P),
    ]),
    ("lilith", 0x2C, "Lilith-style Morrigan", "Secret", [
        hy("Brilliant Shower", "236+" + PP),
        hy("Luminous Illusion", "236+" + KK, "also in air"),
        hy("Splendor Love", "623+" + PP),
        sp("Soul Flash", "236+" + P, "also in air"),
        sp("Shining Blade", "623+" + P),
        sp("Vector Drain", "63214+" + P, "close"),
    ]),
    ("roll", 0x10, "Roll", "Secret", [
        hy("Hyper Roll", "236+" + PP, "also in air"),
        hy("Rush Drill", "236+" + KK),
        hy("Beat Plane", "214+" + KK, "also in air"),
        sp("Roll Buster", "236+" + P, "also in air"),
        sp("Exploding Flower", "214+" + P, "also in air"),
        sp("Item Attack", "236+" + K),
    ]),
]

IDS = {cid: gid for cid, gid, _, _, _ in CHARACTERS}


def main():
    roster = {
        "game": "mvsc",
        "sources": [
            "https://raw.githubusercontent.com/AntoPISA/MAME_SupportFiles/main/command.dat/command.dat",
            "https://wiki.supercombo.gg/w/Marvel_vs._Capcom",
            "https://strategywiki.org/wiki/Marvel_vs._Capcom/Moves",
            "Checked in FBNeo with tools/movetest.py",
        ],
        "characters": [
            {"id": cid, "name": name, "team": team,
             "moves": [{"name": n, "cmd": c, "type": t, "note": note} for n, c, t, note in moves]}
            for cid, _, name, team, moves in CHARACTERS
        ],
    }
    out = os.path.join(os.path.dirname(__file__), "../android/core/src/main/resources/rosters/mvsc.json")
    with open(out, "w") as f:
        json.dump(roster, f, indent=1, ensure_ascii=False)
        f.write("\n")


if __name__ == "__main__":
    main()
