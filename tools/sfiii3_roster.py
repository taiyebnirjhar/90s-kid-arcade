"""Writes the Street Fighter III 3rd Strike move list, rosters/sfiii3.json.

Moves from MAME's command.dat, StrategyWiki, SuperCombo and the 3rd Strike
training scripts' move list; checked in FBNeo with tools/movetest.py.
Buttons as the app lays them out (PadMap.CAPCOM_6): A B C = LP MP HP,
D E F = LK MK HK. A move that takes any punch or kick is written with the
light one. Super Arts are "dm" with "Super Art I/II/III" in the note: only
the one picked before the fight works, and the app shows just that one.
The ring holds six moves, so each list starts with what matters most.
Run once: python3 tools/sfiii3_roster.py
"""
import json
import os

SA = {1: "Super Art I", 2: "Super Art II", 3: "Super Art III"}
LP, LK = "A", "D"


def sp(name, cmd, note=""):
    return (name, cmd, "special", note)


def sa(n, name, cmd, note=""):
    return (name, cmd, "dm", SA[n] + ("; " + note if note else "") if n else note)


# 360 from facing right: forward round to up (the button lands in the
# jump's first frames, which a throw cancels).
C360 = "63214789"

CHARACTERS = [
    ("alex", "Alex", [
        sa(1, "Hyper Bomb", C360 + "+" + LP, "close"),
        sa(2, "Boomerang Raid", "236236+" + LP),
        sa(3, "Stun Gun Headbutt", "236236+" + LP),
        sp("Flash Chop", "236+" + LP),
        sp("Powerbomb", "63214+" + LP, "close"),
        sp("Spiral D.D.T.", "63214+" + LK),
        sp("Air Knee Smash", "623+" + LK),
        sp("Air Stampede", "[2]8+" + LK),
        sp("Slash Elbow", "[4]6+" + LK),
    ]),
    ("akuma", "Akuma", [
        sa(1, "Messatsu Gou Hadou", "236236+" + LP, "also in air"),
        sa(2, "Messatsu Gou Shouryuu", "236236+" + LP),
        sa(3, "Messatsu Gou Rasen", "236236+" + LK, "also in air"),
        sa(0, "Shun Goku Satsu", "A,A,6,D,C", "full gauge"),
        sp("Gou Hadou Ken", "236+" + LP),
        sp("Gou Shouryuu Ken", "623+" + LP),
        sp("Tatsumaki Zankuu Kyaku", "214+" + LK, "also in air"),
        sp("Shakunetsu Hadou Ken", "63214+" + LP),
        sp("Zankuu Hadou Ken", "236+" + LP, "in air"),
        sp("Hyakki Shuu", "623+" + LK),
        sp("Ashura Senkuu", "623+AB", "421+AB goes back"),
    ]),
    ("chunli", "Chun-Li", [
        sa(1, "Kikou Shou", "236236+" + LP),
        sa(2, "Houyoku Sen", "236236+" + LK),
        sa(3, "Tensei Ranka", "236236+" + LK),
        sp("Hyakuretsu Kyaku", "D,D,D,D,D", "mash"),
        sp("Kikou Ken", "41236+" + LP),
        sp("Hazan Shuu", "63214+" + LK),
        sp("Spinning Bird Kick", "[2]8+" + LK),
    ]),
    ("dudley", "Dudley", [
        sa(1, "Rocket Uppercut", "236236+" + LP),
        sa(2, "Rolling Thunder", "236236+" + LP, "mash"),
        sa(3, "Corkscrew Blow", "236236+" + LP),
        sp("Machine Gun Blow", "41236+" + LP),
        sp("Jet Upper", "623+" + LP),
        sp("Ducking", "41236+" + LK),
        sp("Cross Counter", "63214+" + LP),
        sp("Short Swing Blow", "63214+" + LK),
    ]),
    ("elena", "Elena", [
        sa(1, "Spinning Beat", "236236+" + LK),
        sa(2, "Brave Dance", "236236+" + LK),
        sa(3, "Healing", "236236+" + LP),
        sp("Rhino Horn", "41236+" + LK),
        sp("Mallet Smash", "63214+" + LP),
        sp("Scratch Wheel", "623+" + LK),
        sp("Lynx Tail", "421+" + LK),
        sp("Spin Scythe", "214+" + LK),
    ]),
    ("hugo", "Hugo", [
        # A 720 has to pass up twice and the first one jumps; a dash first
        # hides it (checked: the super comes out; it grabs when in reach).
        sa(1, "Gigas Breaker", "66,63214789632147+" + LP, "close; dashes in first"),
        sa(2, "Megaton Press", "236236+" + LK),
        sa(3, "Hammer Frenzy", "236236+" + LP),
        sp("Giant Palm Bomber", "214+" + LP),
        sp("Monster Lariat", "236+" + LK),
        sp("Ultra Throw", "63214+" + LK, "close"),
        sp("Moonsault Press", C360 + "+" + LP, "close"),
        sp("Meat Squasher", C360 + "+" + LK),
        sp("Shootdown Backbreaker", "623+" + LK),
    ]),
    ("ibuki", "Ibuki", [
        sa(1, "Kasumi Suzaku", "236236+" + LP, "in air; mash"),
        sa(2, "Yoroi Doushi", "236236+" + LP),
        sa(3, "Yami Shigure", "236236+" + LP),
        sp("Kunai", "236+" + LP, "in air"),
        sp("Kubi Ori", "236+" + LP),
        sp("Kasumi Gake", "236+" + LK),
        sp("Tsuiji Goe", "623+" + LP),
        sp("Kazekiri", "623+" + LK),
        sp("Hien", "421+" + LK),
        sp("Raida", "63214+" + LP, "close"),
        sp("Tsumuji", "214+" + LK),
    ]),
    ("ken", "Ken", [
        sa(1, "Shouryuu Reppa", "236236+" + LP),
        sa(2, "Shinryuu Ken", "236236+" + LK, "mash"),
        sa(3, "Shippuu Jinrai Kyaku", "236236+" + LK),
        sp("Hadou Ken", "236+" + LP),
        sp("Shouryuu Ken", "623+" + LP),
        sp("Tatsumaki Senpuu Kyaku", "214+" + LK, "also in air"),
    ]),
    ("makoto", "Makoto", [
        sa(1, "Seichu Sen Godan-zuki", "236236+" + LP),
        sa(2, "Abare To Sanami Kudaki", "236236+" + LK),
        sa(3, "Tanden Renki", "236236+" + LP),
        sp("Hayate", "236+" + LP, "hold the button to charge"),
        sp("Fukiage", "623+" + LP),
        sp("Oroshi", "214+" + LP),
        sp("Karakusa", "63214+" + LK, "close"),
        sp("Tsurugi", "214+" + LK, "in air"),
    ]),
    ("necro", "Necro", [
        sa(1, "Magnetic Storm", "236236+" + LP, "mash"),
        sa(2, "Slam Dance", "236236+" + LP, "close"),
        sa(3, "Electric Snake", "236236+" + LP),
        sp("Tornado Hook", "41236+" + LP),
        sp("Denji Blast", "623+" + LP, "mash"),
        sp("Snake Fang", "41236+" + LK),
        sp("Flying Viper", "214+" + LP),
        sp("Rising Cobra", "214+" + LK),
    ]),
    ("oro", "Oro", [
        sa(1, "Kishin Riki", "236236+" + LP),
        sa(2, "Yagyou Dama", "236236+" + LP),
        sa(3, "Tengu Stone", "236236+" + LP),
        sp("Nichirin Shou", "[4]6+" + LP),
        sp("Oni Yanma", "[2]8+" + LP),
        sp("Niou Riki", "63214+" + LP, "close"),
        sp("Jinchuu Watari", "236+" + LK),
        sp("Hitobashira Nobori", "236+" + LK, "in air; mash"),
    ]),
    ("q", "Q", [
        sa(1, "Critical Combo Attack", "236236+" + LP),
        sa(2, "Deadly Double Combination", "236236+" + LP),
        sa(3, "Total Destruction", "236236+" + LP),
        sp("Dash Head Punch", "[4]6+" + LP),
        sp("Dash Low Kick", "[4]6+" + LK),
        sp("High Speed Barrage", "214+" + LP),
        sp("Capture and Deadly Blow", "63214+" + LK, "close"),
    ]),
    ("remy", "Remy", [
        sa(1, "Light of Justice", "236236+" + LP),
        sa(2, "Supreme Rising Rage Flash", "236236+" + LK),
        sa(3, "Blue Nocturne", "236236+" + LK),
        sp("Light of Virtue (high)", "[4]6+" + LP),
        sp("Light of Virtue (low)", "[4]6+" + LK),
        sp("Rising Rage Flash", "[2]8+" + LK),
        sp("Cold Blue Kick", "214+" + LK),
    ]),
    ("ryu", "Ryu", [
        sa(1, "Shinkuu Hadou Ken", "236236+" + LP),
        sa(2, "Shin Shouryuu Ken", "236236+" + LP),
        sa(3, "Denjin Hadou Ken", "236236+" + LP, "hold the button to charge"),
        sp("Hadou Ken", "236+" + LP),
        sp("Shouryuu Ken", "623+" + LP),
        sp("Tatsumaki Senpuu Kyaku", "214+" + LK, "also in air"),
        sp("Joudan Sokutou Geri", "41236+" + LK),
    ]),
    ("sean", "Sean", [
        sa(1, "Hadou Burst", "236236+" + LP),
        sa(2, "Shouryuu Cannon", "236236+" + LP, "mash"),
        sa(3, "Hyper Tornado", "236236+" + LP),
        sp("Sean Tackle", "41236+" + LP),
        sp("Dragon Smash", "623+" + LP),
        sp("Tornado", "214+" + LK),
        sp("Ryuubi Kyaku", "236+" + LK),
        sp("Zenpou Tenshin", "214+" + LP),
    ]),
    ("twelve", "Twelve", [
        sa(1, "X.N.D.L.", "236236+" + LP),
        sa(2, "X.F.L.A.T.", "236236+" + LK, "in air"),
        sa(3, "X.C.O.P.Y.", "236236+" + LP),
        sp("N.D.L.", "236+" + LP),
        sp("A.X.E.", "214+" + LP, "also in air; mash"),
        sp("D.R.A.", "214+" + LK, "in air"),
    ]),
    ("urien", "Urien", [
        sa(1, "Tyrant Slaughter", "236236+" + LP),
        sa(2, "Temporal Thunder", "236236+" + LP),
        sa(3, "Aegis Reflector", "236236+" + LP),
        sp("Metallic Sphere", "236+" + LP, "hold the button to delay"),
        sp("Chariot Tackle", "[4]6+" + LK),
        sp("Violence Knee Drop", "[2]8+" + LK),
        sp("Dangerous Headbutt", "[2]8+" + LP),
    ]),
    ("yang", "Yang", [
        sa(1, "Raishin Mahhaken", "236236+" + LP),
        sa(2, "Tenshin Senkyuutai", "236236+" + LK),
        sa(3, "Sei'ei Enbu", "236236+" + LP),
        sp("Tourou Zan", "236+" + LP, "press 3 times"),
        sp("Senkyuutai", "236+" + LK),
        sp("Byakko Soushouda", "214+" + LP),
        sp("Kaihou", "623+" + LK),
        sp("Zenpou Tenshin", "63214+" + LK, "close"),
    ]),
    ("yun", "Yun", [
        sa(1, "You Hou", "236236+" + LP),
        sa(2, "Sourai Rengeki", "236236+" + LP),
        sa(3, "Gen'ei Jin", "236236+" + LP),
        sp("Zesshou Hohou", "236+" + LP),
        sp("Kobokushi", "214+" + LP),
        sp("Tetsuzan Kou", "623+" + LP),
        sp("Nishou Kyaku", "623+" + LK),
        sp("Zenpou Tenshin", "63214+" + LK, "close"),
    ]),
    ("gill", "Gill", [
        sa(0, "Meteor Strike", "236236+" + LP),
        sp("Pyro / Cryo-kinesis", "236+" + LP),
        sp("Cyber Lariat", "623+" + LP),
        sp("Moonsault Knee Drop", "63214+" + LK),
        sp("Psycho Headbutt", "214+" + LP),
    ]),
]


def main():
    roster = {
        "game": "sfiii3",
        "sources": [
            "https://raw.githubusercontent.com/AntoPISA/MAME_SupportFiles/main/command.dat/command.dat",
            "https://strategywiki.org/wiki/Street_Fighter_III:_3rd_Strike/Moves",
            "https://wiki.supercombo.gg/w/Street_Fighter_3:_3rd_Strike",
            "https://github.com/Grouflon/3rd_training_lua",
            "Checked in FBNeo with tools/movetest.py",
        ],
        "characters": [
            {"id": cid, "name": name, "team": "Boss" if cid == "gill" else "Fighters",
             "moves": [{"name": n, "cmd": c, "type": t, "note": note} for n, c, t, note in moves]}
            for cid, name, moves in CHARACTERS
        ],
    }
    out = os.path.join(os.path.dirname(__file__), "../android/core/src/main/resources/rosters/sfiii3.json")
    with open(out, "w") as f:
        json.dump(roster, f, indent=1, ensure_ascii=False)
        f.write("\n")


if __name__ == "__main__":
    main()
