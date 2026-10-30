"""Writes The Last Blade's move list, rosters/lastblad.json, and checks it in
the real game.

Moves from MAME's command.dat ($info=lastblad); every one tried in FBNeo the
way the app plays it (tools/movetest.py's timeline). Buttons: A weak slash,
B strong slash, C kick, D repel (deflect). A special that takes A or B is
written with A and noted.

Each fighter picks Power or Speed before the fight (Ability Select: LEFT
Power, RIGHT Speed):
- Desperation moves ("dm", motion + A+B) work in both: a MAX gauge (used up)
  or a flashing life bar (free).
- Super desperation moves ("sdm", motion + B) are Power only: MAX gauge and
  flashing life both.
- Speed's Shuuen no Mai (2,2+A+B, MAX gauge and flashing life) starts a
  chain of normals; it is every fighter's, listed as "sdm" with "Speed only".

  python3 tools/lastblad_roster.py           writes the JSON
  python3 tools/lastblad_roster.py --test    tries every move in FBNeo (EMU=...)
  python3 tools/lastblad_roster.py --test kaede lee
  python3 tools/lastblad_roster.py --replay  checks that a savestate replays exactly

What the game keeps where (68000 addresses; found with tools/emu.py, leads
from FBNeo's cheat file lastblad.ini):
  0x10AC2E P1's fighter block, low word of a pointer (block = 0x100000 | word);
           P2's at 0x10AC32. The blocks move between fights (object slots);
           each starts with "PLAYER  " at +0x10.
    +0x18  X (word)           +0x20 height (word, 0 on the floor)
    +0x91  character ID (ORDER below)
    +0x9C  animation pointer (long, ROM address): what the fighter does
    +0xE0  player (0 P1, 1 P2)  +0xE3 1 facing right, 0 facing left
    +0x180 life (word, 0x100 full)   +0x18D power gauge (byte, 0x40 = MAX)
  0x10EAAD P1's pick (ORDER), P2's 0x10EAAF
  0x10EAB6 P1's mode: 1 Power, 0xFF Speed; P2's 0x10EAB7
  0x10E32F the match's phase: 2 while a round is fought (from "Ready" until
           the KO), 1 the round's intro and end, 3 select screens
  0x10E32B round timer (BCD)
"""
import json
import os
import re
import sys

ORDER = ["kaede", "moriya", "yuki", "okina", "akari", "juzoh", "washizuka", "shikyoh",
         "hyo", "lee", "zantetsu", "shigen", "musashi", "kagami", "kaede"]
# 14 is Kaede awakened (B+C+D); the app shows Kaede's list.

PTR = 0x10AC2E
PHASE = 0x10E32F
FIGHT = 2
PICK = 0x10EAAD
MODE = 0x10EAB6
GAUGE = 0x18D
LIFE = 0x180
MAX = 0x40
LOW = 0x20

POWER = "Power only"
SPEED = "Speed only"


def sp(name, cmd, note=""):
    return (name, cmd, "special", note)


def dm(name, cmd, note=""):
    n = "MAX gauge or flashing life"
    return (name, cmd, "dm", n + ("; " + note if note else ""))


def sdm(name, cmd, note=""):
    n = POWER + "; MAX gauge and flashing life"
    return (name, cmd, "sdm", n + ("; " + note if note else ""))


SHUUEN = ("Shuuen no Mai", "22+AB", "sdm", SPEED + "; MAX gauge and flashing life; then a chain of normals")

# (roster id, game ID, name, team, moves). The ring holds six moves, so
# each list starts with what matters most.
CHARACTERS = [
    ("kaede", 0x00, "Kaede", "Fighters", [
        dm("Kasshin: Fukuryuu", "21416+AB"),
        sdm("Kasshin: Kouryuu", "21416+B"),
        SHUUEN,
        sp("Kakusei (awaken)", "BCD", "MAX gauge or flashing life; awakened for the round"),
        sp("Ittou Hayate", "236+A", "A or B"),
        sp("Ittou Kuga", "623+A", "A or B; B: Kabutowari"),
        sp("Ittou Renjin Zan", "214+A", "A or B; up to 3 times"),
        sp("Ittou Tabakaze", "214+C"),
        sp("Ittou Arashi Uchi", "41236+C", "close"),
    ]),
    ("moriya", 0x01, "Moriya Minakata", "Fighters", [
        dm("Kassatsu Izayoi Gekka", "641236+AB"),
        sdm("Kassatsu Midare Setsu Gekka", "641236+B"),
        SHUUEN,
        sp("Ittou Tsukikage", "236+A", "A or B; up to 3 times"),
        sp("Ittou Shingetsu", "623+A"),
        sp("Ittou Shingetsu Ura", "623+B"),
        sp("Ittou Oboro: Jodan", "214+A", "counter stance: high; B middle, C low"),
        sp("Taitou Hogetsu", "412+A", "A, B or C; then A, B or C"),
    ]),
    ("yuki", 0x02, "Yuki", "Fighters", [
        dm("Yukishi Maki", "21416+AB"),
        sdm("Shin Yukishi Maki", "21416+B"),
        SHUUEN,
        sp("Hyou Jin", "236+A", "A or B"),
        sp("Sou Ka", "623+A", "A or B"),
        sp("Shun Setsu Zan", "214+A", "A or B"),
        sp("Hyou Kyou", "41236+C"),
        sp("Suizan", "63214+C", "close"),
    ]),
    ("okina", 0x03, "Genbu no Okina", "Fighters", [
        dm("Genbu no Hoko", "641236+AB"),
        sdm("Genbu no Ikari", "641236+B"),
        SHUUEN,
        sp("Ki Paku", "236+A", "A, B or C"),
        sp("Chouka Tairyou", "623+A", "A, B or C"),
        sp("Kame Mai Chi", "63214+A", "A or B"),
        sp("Kame Mai Ten", "63214+C"),
        sp("Mukuyu", "22+C"),
    ]),
    ("akari", 0x04, "Akari Ichijou", "Fighters", [
        dm("Shikigami Rikugou", "21416+AB"),
        sdm("Shikigami Hyakki Yakou", "632146+B"),
        SHUUEN,
        sp("Shikigami Tenku", "236+A", "A or B"),
        sp("Tenmon Hoshi no Meguri", "28+C"),
        sp("Gaiki Dorota Bou", "623+A", "A, B or C"),
        sp("Gaiki Kiyo Hime", "63214+A"),
        sp("Kawari Hito Gata", "63214+B"),
        sp("Henka Hito Gata", "63214+C"),
    ]),
    ("juzoh", 0x05, "Juzoh Kanzaki", "Fighters", [
        dm("Katsu Moku Dai Gekidou", "21416+AB"),
        sdm("Katsu Moku Cho Gekidou", "6321463214+B", "close"),
        SHUUEN,
        sp("Kongou Ju Tsui", "236+A", "A or B"),
        sp("Geki Shin", "623+A", "A or B"),
        sp("Fugaku", "63214+B", "close"),
    ]),
    ("washizuka", 0x06, "Keiichiro Washizuka", "Fighters", [
        dm("Shin Rouga", "21416+AB"),
        sdm("Saishu Rouga", "21416+B"),
        SHUUEN,
        sp("Rouga", "[4]6+C"),
        sp("Shun Satsu", "214+A", "A or B; press again for Ren"),
        sp("Shikku Satsu", "[4]6+A", "A or B"),
        sp("Koku Satsu", "[2]8+A", "A or B"),
    ]),
    ("shikyoh", 0x07, "Shikyoh", "Fighters", [
        dm("Mei Kyo Shisui Kyoki", "236236+AB"),
        sdm("Mei Kyo Shisui Kyoki Ruin", "236236+B"),
        SHUUEN,
        sp("Hage Washi", "214+A", "A or B"),
        sp("Kaiten Kimo Eguri", "623+A", "A or B"),
        sp("Zan Niku Kamaitachi", "41236+A", "A or B"),
        sp("Ji Name Suberi", "646+C"),
        sp("Zan Niku Obasami", "214+B", "in air"),
        sp("Mujihi Sashi", "63214+C", "close"),
    ]),
    ("hyo", 0x08, "Hyo Amano", "Fighters", [
        dm("Banjo Kono Itte: Tokin", "6321463214+AB"),
        sdm("Banjo Kono Itte: Kaku", "6321463214+B"),
        sdm("Banjo Kono Itte: Hisha", "6321463214+A"),
        SHUUEN,
        sp("Suzume Sashi", "616+A", "A or B"),
        sp("Keima no Taka", "623+C"),
        sp("Hisshi", "63214+B"),
        sp("Takabisha", "63214+C", "counter"),
        sp("Ibisha Anaguma", "A,A,A,A,A", "mash"),
        sp("Shougi Doushi", "33+C", "close"),
    ]),
    ("lee", 0x09, "Lee Rekka", "Fighters", [
        dm("Ougi Enryu Tenshin", "21416+AB"),
        sdm("Hiougi Soten Muei Kyaku", "21416+B"),
        SHUUEN,
        sp("Ryu Tsui Sen", "214+C", "up to 3 times; also in air"),
        sp("En Sen Shou", "623+B"),
        sp("Enryu Haibi", "63214+A", "A or B"),
        sp("Kasumi", "AB", "then A, B or C"),
    ]),
    ("zantetsu", 0x0A, "Zantetsu", "Fighters", [
        dm("Toro Soujin", "21416+AB"),
        sdm("Yami Gari", "6321463214+B", "close"),
        SHUUEN,
        sp("Ryuei Jin", "236+A", "A or B; twice"),
        sp("Kikou Hou", "214+B"),
        sp("Hagane Tachi Kai", "641236+B"),
        sp("Oboro Giri", "623+C"),
        sp("Minamo Gakure", "421+A"),
        sp("Mukuro Nui", "236+A", "in air; A or B"),
        sp("Tenma Otoshi", "63214+C", "close"),
    ]),
    ("shigen", 0x0B, "Shigen Naoe", "Fighters", [
        dm("Inga Ohou", "6321463214+AB", "close"),
        sdm("Hisshi no Sou", "6321463214+B", "close"),
        SHUUEN,
        sp("Byakko So", "214+A", "A or B"),
        sp("Retsu Hoko", "623+A", "press A again"),
        sp("Zetsu Hoko", "623+B", "press B again"),
        sp("Hisui Sai", "41236+C", "close"),
        sp("Kongo Sai", "632146+C", "close"),
    ]),
    ("musashi", 0x0C, "Akatsuki Musashi", "Secret", [
        dm("Gorin Tsurane", "632146+AB"),
        sdm("Niten Muso Ken", "6321463214+B"),
        SHUUEN,
        sp("Kanketsu Sen", "236+B"),
        sp("Obiyakashi", "41236+A"),
        sp("Juji Gamae", "623+B"),
        sp("Makura Niren", "63214+B", "close"),
    ]),
    ("kagami", 0x0D, "Shinnosuke Kagami", "Secret", [
        dm("Guren Suzaku", "41236+AB", "in air"),
        sdm("Funsho Koju", "21416+B"),
        SHUUEN,
        sp("Kakusei (awaken)", "BCD", "MAX gauge or flashing life"),
        sp("Shiranui", "236+A", "A or B"),
        sp("Sho Hoko", "623+A", "A or B"),
        sp("En Hoko", "63214+C", "close"),
    ]),
]


def roster_dicts():
    return [
        {"id": cid, "name": name, "team": team,
         "moves": [{"name": n, "cmd": c, "type": t, "note": note} for n, c, t, note in moves]}
        for cid, _, name, team, moves in CHARACTERS
    ]


def main():
    roster = {
        "game": "lastblad",
        "power": {"name": "Power / Speed",
                  "note": "Picked before each fight. Power: stronger hits; super desperation moves "
                          "(motion + B) with a MAX gauge and flashing life. Speed: chain combos; "
                          "Shuuen no Mai (2,2+A+B) with a MAX gauge and flashing life. "
                          "Desperation moves (motion + A+B) in both, with a MAX gauge or flashing life."},
        "sources": [
            "https://raw.githubusercontent.com/AntoPISA/MAME_SupportFiles/main/command.dat/command.dat",
            "https://github.com/finalburnneo/FBNeo-cheats (lastblad.ini)",
            "Checked in FBNeo with tools/movetest.py (tools/lastblad_roster.py --test)",
        ],
        "characters": roster_dicts(),
    }
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "../android/core/src/main/resources/rosters/lastblad.json")
    with open(out, "w") as f:
        json.dump(roster, f, indent=1, ensure_ascii=False)
        f.write("\n")


# ---- checking in FBNeo ------------------------------------------------------

def emu_tools():
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import movetest as mt
    from emu import Emu
    return mt, Emu


def word(e, a, r=None):
    return (e.byte(a, r) << 8) | e.byte(a + 1, r)


def block(e, player, r=None):
    return 0x100000 | word(e, PTR + 4 * player, r)


# The select screen's portraits: top row left to right, bottom row left to right.
TOP = [0, 2, 5, 4, 8, 1]
BOTTOM = [3, 9, 6, 7, 10, 11]


def _pos(cid):
    return (0, TOP.index(cid)) if cid in TOP else (1, BOTTOM.index(cid))


def select_screen(Emu):
    """Boots, puts two coins in, starts both players, waits for Player Select."""
    e = Emu("lastblad")
    e.run(1800)
    e.tap("COIN"); e.tap("COIN", port=1); e.run(30)
    e.tap("START"); e.run(120)
    e.tap("START", port=1)
    e.run(1690)  # the How to Play pages (phase 3 already), then Player Select
    return e, e.save()


def to_fight(e, sel, cid, speed, opponent=None):
    """P1 picks [cid] (0-13) in Power or Speed, P2 a standing opponent; on to the round."""
    e.load(sel)
    opp = opponent if opponent is not None else (3 if cid != 3 else 0)
    p1 = cid if cid < 12 else 0
    for port, start, want in ((0, 0, p1), (1, 1, opp)):
        r0, c0 = _pos(start); r1, c1 = _pos(want)
        if r1 != r0:
            e.tap("DOWN" if r1 > r0 else "UP", 4, 10, port=port)
        for _ in range(abs(c1 - c0)):
            e.tap("RIGHT" if c1 > c0 else "LEFT", 4, 10, port=port)
    e.tap("A", 4, 10); e.tap("A", 4, 10, port=1); e.run(40)
    if speed:
        e.tap("RIGHT", 4, 10)
    if cid >= 12:
        # The secret fighters, as FBNeo's cheat file does: P1's pick, set on the select screen.
        mt.poke(e, PICK, cid)
    e.tap("A", 4, 10); e.tap("A", 4, 10, port=1)
    for _ in range(400):
        e.run(10)
        if e.byte(PHASE) == FIGHT:
            break
    e.run(150)
    b = block(e, 0)
    assert e.byte(b + 0x91) == cid, (cid, e.byte(b + 0x91))
    assert e.byte(MODE) == (0xFF if speed else 1)
    return e.save()


mt = None


def patch_movetest(m):
    global mt
    mt = m

    class Game:
        g = "lastblad"
        kof97 = dd = sf3 = mvc = False
        buttons = "ABCD"
        xs = (0, 0)

        def power(self, e, maxmode=False, lowlife=False):
            b = block(e, 0)
            mt.poke(e, b + GAUGE, MAX)
            if maxmode or lowlife:
                mt.poke(e, b + LIFE, 0); mt.poke(e, b + LIFE + 1, LOW)

        def before(self, e):
            return e.byte(block(e, 0) + GAUGE)

        def used(self, e, acts, before):
            return before - min(e.byte(block(e, 0) + GAUGE), before)

    m.ACT[0] = lambda e: (word(e, block(e, 0) + 0x9C) << 16) | word(e, block(e, 0) + 0x9E)
    m.baseline = baseline
    g = Game()
    m.GAME[0] = g
    return g


def baseline(e, states, game):
    """movetest's baseline without A+B (Lee's Kasumi) and B+C+D (awakening)."""
    seen = set()
    play = mt.play
    for st in states:
        e.load(st); game.power(e)
        seen.update(play(e, [(5, '')] * 30, None, 0))
        for d in range(1, 10):
            e.load(st); game.power(e)
            seen.update(play(e, [(d, '')] * 25, None, 30))
            for b in game.buttons:
                e.load(st); game.power(e)
                seen.update(play(e, [(d, '')] * 2 + [(d, b)] * 4, None, 50))
        for j in (7, 8, 9):
            for hold in (1, 4):
                for b in game.buttons:
                    e.load(st); game.power(e)
                    seen.update(play(e, [(j, '')] * hold + [(5, '')] * 8 + [(5, b)] * 4, None, 50))
        for seq in ([6, 5, 6], [4, 5, 4]):
            for b in [''] + list(game.buttons):
                e.load(st); game.power(e)
                fr = []
                for d in seq:
                    fr += [(d, '')] * 2
                fr += [(seq[-1], '')] * 15 + [(seq[-1], b)] * 4
                seen.update(play(e, fr, None, 40))
        for b in ('CD', 'BC', 'AC', 'BD', 'ABC', 'ACD'):
            e.load(st); game.power(e)
            seen.update(play(e, [(5, b)] * 4, None, 50))
    return seen


def modes_for(move):
    n = move["note"]
    return [True] if n.startswith(SPEED) else [False] if n.startswith(POWER) else [False, True]


def run_tests(names):
    m, Emu = emu_tools()
    game = patch_movetest(m)
    e, sel = select_screen(Emu)
    bad = []
    for ch in roster_dicts():
        if names and ch["id"] not in names:
            continue
        cid = [c[1] for c in CHARACTERS if c[0] == ch["id"]][0]
        results = {}
        for speed in (False, True):
            base = to_fight(e, sel, cid, speed)
            e.load(base)
            game.xs = (block(e, 0) + 0x18, block(e, 1) + 0x18)
            moves = [mv for mv in ch["moves"] if speed in modes_for(mv)]
            res = m.test_char(e, game, base, dict(ch, moves=moves))
            for mv, ok, tries in res:
                results.setdefault(mv["name"] + " " + mv["cmd"], []).append(("Speed" if speed else "Power", ok, tries))
        for k, rs in results.items():
            ok = all(r[1] for r in rs)
            if not ok:
                bad.append((ch["id"], k))
            print("%-10s %-4s %-40s %s" % (ch["id"], "ok" if ok else "FAIL", k,
                  "  ".join("%s:%s %s" % (md, "y" if o else "n",
                            [(l, hex(f) if f else None, u, w) for l, f, u, w in t]) for md, o, t in rs)), flush=True)
    print("failing:", bad)
    return bad


def replay():
    """Loads a fight state, plays the same inputs twice, compares RAM after each."""
    m, Emu = emu_tools()
    patch_movetest(m)
    e, sel = select_screen(Emu)
    st = to_fight(e, sel, 0, False)
    inputs = [(6, '')] * 30 + m.compile_cmd("236+A") + [(5, '')] * 40 + m.compile_cmd("623+B") + \
        [(3, 'C')] * 6 + [(5, '')] * 60 + [(4, 'D')] * 6 + [(5, '')] * 120
    rams = []
    for _ in range(2):
        e.load(st)
        trace = []
        for d, b in inputs:
            e.held[1] = set()
            e.run(1, *(m.DIRS[d] + tuple(b)))
            trace.append(e.ram())
        rams.append(trace)
    same = all(a == b for a, b in zip(*rams))
    print("replays exactly" if same else "differs", len(inputs), "frames")
    return same


if __name__ == "__main__":
    if "--test" in sys.argv:
        sys.exit(1 if run_tests([a for a in sys.argv[1:] if not a.startswith("--")]) else 0)
    elif "--replay" in sys.argv:
        sys.exit(0 if replay() else 1)
    else:
        main()
