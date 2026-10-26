"""Writes the Double Dragon (Neo Geo) move list, rosters/doubledr.json.

Moves from MAME's command.dat, StrategyWiki and SuperCombo (where they
disagree, the input that works in the real game: tools/movetest.py).
A B C D are one attack at four strengths, so a special takes any one
button (written A, the quickest) and a Charge Move any two together (AB),
with the gauge full and CHARGE showing. "dm" marks the Charge Moves.
Run once: python3 tools/doubledr_roster.py
"""
import json
import os

CHARGE = "CHARGE gauge full: any two buttons"
DD_FORM = "Double Dragon form: "


def lee(super_cmd):
    return [
        ("Cho So Syu Ga" if super_cmd.startswith("236") else "Cho Rekko Ha", super_cmd, "dm", CHARGE),
        ("Souryuu Tenshou", "ABCD", "dm", "CHARGE gauge full: turns into the Double Dragon form for the round"),
        ("So Syu Ga", "236+A", "special", DD_FORM + "Hi Ryu Ken"),
        ("Rekko Ha", "623+A", "special", DD_FORM + "Ryu Jin Ken"),
        ("Ryu Bi Sen", "214+A", "special", "also in air; " + DD_FORM + "Ryu Jin Kyaku"),
        ("Sho Ryu Ha", "41236+A", "special", "Double Dragon form only"),
    ]


CHARACTERS = [
    ("billy", "Billy Lee", lee("236+AB")),
    ("jimmy", "Jimmy Lee", lee("623+AB")),
    ("marian", "Marian", [
        ("Accele Destroyer", "[2]8+AB", "dm", CHARGE),
        ("Full Moon Drive", "[4]6+AB", "dm", CHARGE),
        ("Accele Breaker", "[2]8+A", "special", ""),
        ("Moon Drive", "[4]6+A", "special", ""),
        ("Ikaros Crash", "63214+A", "special", "close (grab)"),
    ]),
    ("abobo", "Abobo", [
        ("Triple Jackhammer", "632+AB", "dm", CHARGE),
        ("Earthquake Bomb", "63214+AB", "dm", CHARGE + "; close (grab)"),
        ("Hammer Blow", "632+A", "special", ""),
        ("Hyper Bomb", "236+A", "special", "close (grab)"),
        ("Giant Swing", "63214+A", "special", "close (grab)"),
    ]),
    ("amon", "Amon", [
        ("Cho Bakuen Rai", "[2]8+AB", "dm", CHARGE),
        ("Mokko Bakudan", "41236+AB", "dm", CHARGE + "; also in air"),
        ("Rekka Bakuen Heki", "[2]8+A", "special", ""),
        ("Shi Ho Ken", "[4]6+A", "special", "press a button again to warp behind"),
        ("Yo So Ken", "412+A", "special", "in air"),
    ]),
    ("eddie", "Eddie", [
        ("Volcano Upper", "[2]8+AB", "dm", CHARGE),
        ("Dragon Finisher", "623+AB", "dm", CHARGE),
        ("Low Leg Slash", "[4]6+A", "special", ""),
        ("High Leg Slash", "[2]8+A", "special", ""),
        ("Burning Knee", "623+A", "special", ""),
    ]),
    ("rebecca", "Rebecca", [
        ("Cho Hi Sho Zan", "623+AB", "dm", CHARGE),
        ("Rekko Zan", "236+AB", "dm", CHARGE),
        ("Kuchu Rekko Zan", "412+AB", "dm", CHARGE + "; in air"),
        ("Hi Sho Zan", "623+A", "special", ""),
        ("Ko Bi Ken", "236+A", "special", ""),
        ("Kuchu Ko Bi Ken", "412+A", "special", "in air"),
    ]),
    ("dulton", "Dulton", [
        ("Cannon Punch", "236+AB", "dm", CHARGE),
        ("Dynamite Finisher", "AB,AB,AB,AB,AB,AB,AB,AB", "dm", CHARGE + "; mash the pair"),
        ("Machine Gun Punch", "236+A", "special", ""),
        ("Dynamite Combo", "A,A,A,A,A", "special", "mash"),
        ("Elbow Neckbreaker", "[4]6+A", "special", ""),
    ]),
    ("chengfu", "Cheng-Fu", [
        ("Cho Ko Ryu Kyaku", "623+AB", "dm", CHARGE),
        ("Cho Suiko Retsu Ken", "236+AB", "dm", CHARGE),
        ("Ko Ryu Kyaku", "623+A", "special", ""),
        ("Suiko Retsu Ken", "236+A", "special", ""),
        ("Hi Sho Kyaku", "214+A", "special", ""),
        ("Counter Toss", "421+A", "special", "catches an attack"),
    ]),
    ("burnov", "Burnov", [
        ("Bomb Belch", "236+AB", "dm", CHARGE),
        ("Dynamite Tackle", "[4]6+AB", "dm", CHARGE),
        ("Burning Belch", "236+A", "special", ""),
        ("Running Tackle", "[4]6+A", "special", ""),
        ("Bone Crusher", "63214+A", "special", "close (grab)"),
    ]),
    ("duke", "Duke", [
        ("Moko Ressho Geki Enha", "236+AB", "dm", CHARGE),
        ("Hooh Tensho Kyaku", "623+AB", "dm", CHARGE),
        ("Hooh Chizan Kyaku", "412+AB", "dm", CHARGE + "; in air"),
        ("Moko Resshoda", "236+A", "special", ""),
        ("Sho So Kyaku", "623+A", "special", ""),
        ("Cho Geki Hasho", "[4]6+A", "special", ""),
        ("Ko Soran Shobu", "[2]8+A", "special", ""),
        ("Hi So Kyaku", "412+A", "special", "in air"),
    ]),
    ("shuko", "Koga Shuko", [
        ("Kirin", "623+AB", "dm", CHARGE),
        ("Ge Kirin", "632+AB", "dm", CHARGE),
        ("Suzaku", "236+A", "special", ""),
        ("Suiryu", "623+A", "special", ""),
        ("Kasumi", "46+A", "special", ""),
        ("Byakko", "28+A", "special", "also in air"),
        ("Genbu", "632+A", "special", ""),
        ("Hooh", "412+A", "special", "in air"),
    ]),
]

TEAMS = {"billy": "Heroes", "jimmy": "Heroes", "marian": "Heroes", "duke": "Hidden", "shuko": "Hidden"}


def main():
    roster = {
        "game": "doubledr",
        "sources": [
            "https://raw.githubusercontent.com/AntoPISA/MAME_SupportFiles/main/command.dat/command.dat",
            "https://strategywiki.org/wiki/Double_Dragon_(Neo_Geo)/Moves",
            "https://wiki.supercombo.gg/w/Double_Dragon",
            "Checked in FBNeo with tools/movetest.py",
        ],
        "characters": [
            {"id": cid, "name": name, "team": TEAMS.get(cid, "Fighters"),
             "moves": [{"name": n, "cmd": c, "type": t, "note": note} for n, c, t, note in moves]}
            for cid, name, moves in CHARACTERS
        ],
    }
    out = os.path.join(os.path.dirname(__file__), "../android/core/src/commonMain/resources/rosters/doubledr.json")
    with open(out, "w") as f:
        json.dump(roster, f, indent=1, ensure_ascii=False)
        f.write("\n")


if __name__ == "__main__":
    main()
