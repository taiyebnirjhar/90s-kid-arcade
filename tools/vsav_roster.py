"""Writes the Vampire Savior move list, rosters/vsav.json.

Moves from MAME's command.dat (Vampire Savior section); each one tried in the
real game (tools/movetest.py), and where a listing and the game disagree,
what works in the game. Buttons as the app lays them out (PadMap.CAPCOM_6):
A B C = LP MP HP, D E F = LK MK HK. A move for any punch or kick is written
with the light one; "two punches" is LP+MP (AB), "two kicks" LK+MK (DE).
EX moves (one level of the gauge) are "dm". Every special also has an ES
version with two punches or two kicks instead of one. The ring holds six
moves, so each list starts with what matters most. Dark Force (a punch and
kick of one strength) is the game's system move.
Run once: python3 tools/vsav_roster.py
"""
import json
import os

P, K, PP, KK = "A", "D", "AB", "DE"


def sp(name, cmd, note=""):
    return (name, cmd, "special", note)


def ex(name, cmd, note=""):
    return (name, cmd, "dm", note)


# id: the character's number in the game (the byte at +0x382 of a fighter block).
CHARACTERS = [
    ("demitri", 0x01, "Demitri", [
        ex("Midnight Bliss", "263+" + PP),
        ex("Demon Billion", "263+" + KK),
        sp("Chaos Flare", "236+" + P, "also in air"),
        sp("Demon Cradle", "623+" + P),
        sp("Bat Spin", "214+" + K, "also in air"),
        ex("Midnight Pleasure", "A,B,6,E,E", "two levels"),
    ]),
    ("morrigan", 0x05, "Morrigan", [
        ex("Valkyrie Turn", "63214+" + KK, "also in air"),
        ex("Darkness Illusion", "A,A,6,D,C", "also in air"),
        ex("Finishing Shower", "B,A,4,D,E"),
        sp("Soul Fist", "236+" + P, "also in air"),
        sp("Shadow Blade", "623+" + P),
        sp("Vector Drain", "63214+B", "close"),
    ]),
    ("victor", 0x03, "Victor", [
        ex("Thunder Break", "[2]8+" + KK),
        sp("Gyro Crush", "214+" + P),
        sp("Giga Burn", "623+" + K),
        sp("Mega Forrid", "[4]6+" + P),
        sp("Mega Stake", "[2]8+" + P),
        sp("Mega Shock", "236+" + K, "close"),
    ]),
    ("zabel", 0x04, "Lord Raptor", [
        ex("Death Voltage", "63214+" + KK, "also in air"),
        ex("Evil Scream", "64+" + PP),
        ex("Hell Dunk", "623+" + PP),
        sp("Death Hurricane", "214+" + K, "also in air"),
        sp("Skull Sting", "28+" + K, "also in air"),
        sp("Hell's Gate", "41236+" + K),
    ]),
    ("felicia", 0x07, "Felicia", [
        ex("Dancing Flash", "41236+" + PP),
        ex("Please Help Me!", "41236+" + KK),
        sp("Rolling Buckler", "236+" + P),
        sp("Cat Spike", "623+" + P),
        sp("Delta Kick", "623+" + K),
        sp("Hell Cat", "63214+E", "close"),
    ]),
    ("gallon", 0x02, "Jon Talbain", [
        ex("Dragon Cannon", "41236+" + KK),
        ex("Moment Slice", "A,B,6,D,E"),
        sp("Beast Cannon", "236+" + P),
        sp("Rising Beast Cannon", "623+" + P),
        sp("Million Flicker", "214+" + P),
        sp("Climb Razor", "28+" + K),
    ]),
    ("anakaris", 0x06, "Anakaris", [
        ex("Naraku no Ana", "41236+" + KK),
        ex("Pharaoh Magic", "E,A,2,D,B", "also in air"),
        sp("Miira Drop", "236+" + P),
        sp("Cobra Blow", "46+" + P),
        sp("Hitsugi no Mai", "22+" + P, "also in air"),
        sp("Kotodama Gaeshi", "214+" + K, "also in air"),
    ]),
    ("sasquatch", 0x0A, "Sasquatch", [
        ex("Big Freeze", "41236+" + PP),
        ex("Big Ice Burn", "41236+" + KK),
        sp("Big Breath", "236+" + P),
        sp("Big Blow", "623+" + P),
        sp("Big Typhoon", "623+" + K),
        sp("Big Towers", "22+" + P),
    ]),
    ("bbhood", 0x00, "B.B. Hood", [
        ex("Cool Hunting", "41236+" + PP),
        ex("Beautiful Memory", "41236+" + KK),
        ex("Apple For You", "63214+" + KK),
        sp("Smile & Missile", "[4]6+" + P),
        sp("Happy & Missile", "[2]8+" + P),
        sp("Cheer & Fire", "623+" + P),
    ]),
    ("lilith", 0x0E, "Lilith", [
        ex("Splendor Love", "623+" + KK),
        ex("Luminous Illusion", "A,A,6,D,C", "also in air"),
        ex("Gloomy Puppet Show", "41236+" + KK, "two levels"),
        sp("Soul Flash", "236+" + P, "also in air"),
        sp("Shining Blade", "623+" + P),
        sp("Merry Turn", "214+" + K),
    ]),
    ("bishamon", 0x08, "Bishamon", [
        ex("Enma Ishi", "41236+" + KK),
        ex("Oni Kubi Hineri", "63214+" + PP),
        sp("Karame Dama", "41236+" + P),
        sp("Iai Giri", "[4]6+" + P),
        sp("Iai Giri Gedan", "[4]6+" + K),
        sp("Mukuro Fuuji", "41236+" + P, "in air"),
    ]),
    ("aulbath", 0x09, "Rikuo", [
        ex("Aqua Spread", "632+" + PP),
        ex("Water Jail", "623+" + PP),
        ex("Sea Rage", "41236+" + PP),
        sp("Sonic Wave", "[4]6+" + P),
        sp("Poison Breath", "[4]6+" + K),
        sp("Trick Fish", "623+" + K),
    ]),
    ("leilei", 0x0D, "Hsien-Ko", [
        ex("Chireitou", "41236+" + KK),
        ex("Chuuka Dan", "41236+" + PP),
        ex("Tenrai Ha", "D,F,B,B,8"),
        sp("Anki Hou", "236+" + P),
        sp("Senpuu Bu", "623+" + P, "also in air"),
        sp("Henkyou Ki", "214+" + P, "also in air"),
    ]),
    ("qbee", 0x0C, "Q-Bee", [
        ex("Qj", "623+" + PP, "also in air"),
        ex("+B", "41236+" + KK, "also in air"),
        sp("C.R", "41236+" + P, "also in air"),
        sp("Delta A", "214+" + K, "also in air"),
        sp("O.M.", "63214+B", "close"),
    ]),
    ("jedah", 0x0F, "Jedah", [
        ex("Prova di Servo", "41236+" + KK),
        ex("Finale Rosso", "22+" + PP),
        sp("Dio Sega", "236+" + P, "also in air"),
        sp("Nero Fatica", "214+" + P),
        sp("Ira Spinta", "63214+" + K, "in air"),
        sp("San Passare", "63214+E", "close"),
    ]),
]

IDS = {cid: gid for cid, gid, _, _ in CHARACTERS}


def main():
    roster = {
        "game": "vsav",
        "sources": [
            "https://raw.githubusercontent.com/AntoPISA/MAME_SupportFiles/main/command.dat/command.dat",
            "Checked in FBNeo with tools/movetest.py",
        ],
        "characters": [
            {"id": cid, "name": name, "team": "Darkstalkers",
             "moves": [{"name": n, "cmd": c, "type": t, "note": note} for n, c, t, note in moves]}
            for cid, _, name, moves in CHARACTERS
        ],
    }
    out = os.path.join(os.path.dirname(__file__), "../android/core/src/main/resources/rosters/vsav.json")
    with open(out, "w") as f:
        json.dump(roster, f, indent=1, ensure_ascii=False)
        f.write("\n")


if __name__ == "__main__":
    main()
