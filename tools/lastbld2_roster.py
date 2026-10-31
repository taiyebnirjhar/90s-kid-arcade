"""The Last Blade 2 (lastbld2): writes the move list, rosters/lastbld2.json,
and checks it, and the RAM detection reads, in the real game (FBNeo).

Moves from MAME's command.dat ($info=lastbld2); every one tried in FBNeo the
way the app plays it (tools/movetest.py), in each of the three styles.

Buttons: A weak slash, B strong slash, C kick, D repel. Each fighter is
played in a style picked after the fighter (Power, Speed, or EX: the code
from command.dat on the style screen):
- Desperation Moves (dm, A+B): a MAX gauge, or a flashing life bar; any style.
- Super Desperation Moves (sdm, the same motion with B): Power and EX only,
  a MAX gauge and a flashing life bar.
- Shuuen no Mai (22+A or 22+B, then the chain strings): Speed and EX only,
  a MAX gauge (checked: the life bar needn't flash).
A move that takes A or B is written with A; the note says so. The ring
holds six moves, so each list starts with what matters most.

  python3 tools/lastbld2_roster.py              # write the JSON
  python3 tools/lastbld2_roster.py test [ids]   # try every move (all fighters, or the ids given)
  python3 tools/lastbld2_roster.py ram          # check the detection reads
  python3 tools/lastbld2_roster.py replay       # savestates replay exactly

RAM (68000 addresses; FBNeo keeps the words byte-swapped, see emu.py):
- 0x10E344 / 0x10E348: long pointers to P1's / P2's fighter block. The
  blocks move from stage to stage (0x104600-0x107800 seen; P2's is not
  always P1's + 0x200), so everything below is read through them.
  Block: +0x41 facing (1 right, 0 left), +0x61 character, +0x74 action
  (word), +0x80 X (word), +0x88 height (word, 0 on the floor), +0x17C style
  (1 Power, 2 Speed, 3 EX), +0x17D gauge (0x40 = MAX), +0x17E life (word,
  0x100 full; flashing at 0x40 and below).
- 0x10E3ED / 0x10E411: the character of P1's / P2's fighter, the same
  as block +0x61 (fixed addresses).
- 0x10BD59 / 0x10BD5B: the pick on the select screen (19 Kotetsu and 18
  Hagure stay here; in the fight Kotetsu is Shigen, 11, and Hagure is
  whoever they copy). 0x10BD60 / 0x10BD61: style + 3 * colour.
- A round is fought while 0x10E36C == 1, 0x10E443 == 0 and 0x10E46A == 0.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "../android/core/src/main/resources/rosters/lastbld2.json")

P, S, E = "P", "S", "E"
ANY = "PSE"
DM_NOTE = "MAX gauge or flashing life"
SDM_NOTE = "Power and EX styles: MAX gauge and flashing life"
SHUUEN_NOTE = "Speed and EX styles: MAX gauge; then chain A, B, C... as the game shows"


def sp(name, cmd, note="", modes=ANY):
    return (name, cmd, "special", note, modes)


def dm(name, cmd, note=""):
    return (name, cmd, "dm", "; ".join(x for x in (DM_NOTE, note) if x), ANY)


def sdm(name, cmd, note=""):
    return (name, cmd, "sdm", "; ".join(x for x in (SDM_NOTE, note) if x), P + E)


SHUUEN = ("Shuuen no Mai", "22+A", "sdm", "A or B; " + SHUUEN_NOTE, S + E)

# (roster id, game ID: block +0x61, name, team, moves)
CHARACTERS = [
    ("kaede", 0x00, "Kaede", "Fighters", [
        dm("Kasshin: Fukuryuu", "21416+AB"),
        dm("Kasshin: Seiryuu", "236236+AB"),
        sdm("Kasshin: Kouryuu", "21416+B"),
        sdm("Kasshin: Souryuu", "236236+B"),
        SHUUEN,
        sp("Shinmei Hayate", "236+A", "A or B"),
        sp("Shinmei Kuga", "623+A", "A or B; with B: 623+B again for Shinmei Kokuuga"),
        sp("Shinmei Renjin Zan", "214+A", "A or B"),
        sp("Shinmei Oite", "236+A", "in air; A or B"),
        sp("Shinmei Tabakaze", "214+C"),
        sp("Shinmei Arashi Uchi", "41236+C", "close"),
    ]),
    ("moriya", 0x01, "Moriya Minakata", "Fighters", [
        dm("Kassatsu Izayoi Gekka", "641236+AB"),
        sdm("Kassatsu Midare Setsu Gekka", "641236+B"),
        SHUUEN,
        sp("Ittou Oboro: Jodan", "214+A", "hold to wait; D cancels"),
        sp("Ittou Oboro: Chuudan", "214+B", "hold to wait; D cancels"),
        sp("Ittou Oboro: Gedan", "214+C", "hold to wait; D cancels"),
        sp("Ittou Shingetsu", "623+A", "Speed and EX: 623+B after it for Ittou Sogetsu"),
        sp("Ittou Shingetsu Ura", "623+B"),
        sp("Ittou Tsukikage", "236+A", "A (3 times) or B (4 times)"),
        sp("Taitou Hogetsu", "412+A", "A, B or C; press again for Ittou Oboro"),
    ]),
    ("yuki", 0x02, "Yuki", "Fighters", [
        dm("Juuyou", "236236+AB"),
        sdm("Shin Yukishi Maki", "21416+B"),
        SHUUEN,
        sp("Hyou Jin", "236+A", "A or B"),
        sp("Sou Ka", "623+A", "A or B"),
        sp("Shun Setsu Zan", "214+A", "A or B"),
        sp("Hyou Kyou", "41236+C", "counter"),
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
        sp("Mukuyu Jin", "421+A"),
        sp("Mukuyu Chi", "421+B"),
        sp("Mukuyu Ten", "421+C"),
    ]),
    ("akari", 0x04, "Akari Ichijou", "Fighters", [
        dm("Shikigami Rikugou", "21416+AB"),
        sdm("Shikigami Hyakki Yakou", "632146+B"),
        SHUUEN,
        sp("Shikigami Tenku", "236+A", "A or B"),
        sp("Tenmon Hoshi no Meguri", "28+C", "then 28+B or 28+C"),
        sp("Gaiki Dorota Bou", "623+A", "A, B or C; follow-ups 2+C, 8+A, 8+B, 8+C"),
        sp("Koumyou Gojuugo", "421+A", "A, B or C"),
        sp("Gaiki Kiyo Hime", "63214+A"),
        sp("Kawari Hito Gata", "63214+B", "counter"),
        sp("Henka Hito Gata", "63214+C"),
    ]),
    ("juzoh", 0x05, "Juzoh Kanzaki", "Fighters", [
        dm("Daitan Muteki", "236236+AB"),
        sdm("Chougekiretsubouzen Gyoutendaifunka", "236236+B"),
        SHUUEN,
        sp("Happa", "236+A", "A or B"),
        sp("Yasegaman", "236+C"),
        sp("Iwakudaki", "[6]4+C", "Speed and EX: 236+C (3 times) or 214+C after it"),
        sp("Yurashi", "623+A", "A or B"),
        sp("Mozuhana", "214+C", "hold C to wait"),
        sp("Tatakitsuke", "63214+A", "then 236+B or 236+A; after Bokunaguri, 6321463214+AB is Ranmoku!"),
        sp("Bunnage", "63214+B", "close; then 236+B, 236+C; 236+AB is Houmuran"),
    ]),
    ("washizuka", 0x06, "Keiichiro Washizuka", "Fighters", [
        dm("Shin Rouga", "21416+AB"),
        sdm("Saishu Rouga", "21416+B"),
        SHUUEN,
        sp("Shun Satsu", "214+A", "A or B; press again for Shun Satsu: Ren"),
        sp("Shikku Satsu", "[4]6+A", "A or B"),
        sp("Koku Satsu", "[2]8+A", "A or B"),
        sp("Rouga", "[4]6+C", "then 214+C, 214+B or 236+C"),
    ]),
    ("mukuro", 0x07, "Mukuro", "Fighters", [
        dm("Mei Kyo Shisui Kyoki", "236236+AB"),
        sdm("Mei Kyo Shisui Kyoki Ruin", "236236+B"),
        SHUUEN,
        sp("Hage Washi", "214+A", "A or B; 8+B after it"),
        sp("Kaiten Kimo Eguri", "623+A", "A or B; press again for Buchimaki"),
        sp("Zan Niku Kamaitachi", "41236+A", "A or B"),
        sp("Ji Name Suberi", "646+C", "hold 6 for a long dash"),
        sp("Zan Niku Obasami", "214+A", "in air; A or B"),
        sp("Mujihi Sashi", "63214+C", "close"),
    ]),
    ("amano", 0x08, "Hyo Amano", "Fighters", [
        dm("Banjo Kono Itte: Tokin", "6321463214+AB"),
        sdm("Banjo Kono Itte: Hisha", "6321463214+A"),
        sdm("Banjo Kono Itte: Kaku", "6321463214+B"),
        SHUUEN,
        sp("Ibisha Anaguma", "A,A,A,A,A", "mash A; then 236+B"),
        sp("Keima no Taka", "623+C"),
        sp("Fusha", "214+A"),
        sp("Suzume Sashi", "616+A", "A or B"),
        sp("Shougi Doushi", "33+C", "close"),
        sp("Hisshi", "63214+B", "hold B for Tsumi; D cancels"),
        sp("Takabisha", "63214+C", "counter"),
    ]),
    ("lee", 0x09, "Lee Rekka", "Fighters", [
        dm("Ougi Enryu Tenshin", "21416+AB"),
        sdm("Hiougi Soten Muei Kyaku", "21416+B"),
        SHUUEN,
        sp("Ryu Tsui Sen", "214+C", "press again, up to 3 times"),
        sp("Ryu Shou Sen", "214+C", "in air; twice"),
        sp("En Sen Shou", "[2]8+B", "2+B after it"),
        sp("Kasumi", "AB", "then A, B, C or D"),
        sp("Enryu Haibi", "63214+A", "A or B; with B: 41236+B after it"),
        sp("Muei Kyaku", "236+C", "in air"),
    ]),
    ("zantetsu", 0x0A, "Zantetsu", "Fighters", [
        dm("Dankoujin", "641236+AB"),
        sdm("Yami Gari", "6321463214+B", "close"),
        SHUUEN,
        sp("Ryuei Jin", "236+A"),
        sp("Kikou Hou", "214+B"),
        sp("Oboro Giri", "623+C"),
        sp("Minamo Gakure", "421+A"),
        sp("Mukuro Nui", "236+A", "in air; A or B; twice"),
        sp("Kagehoushi", "646+C", "then 214+A or 214+B"),
        sp("Tenma Otoshi", "63214+C", "close"),
    ]),
    ("shigen", 0x0B, "Shigen Naoe", "Fighters", [
        dm("Bouko Hyouga", "236236+AB", "then 6321463214+B or 41236+B"),
        dm("Inga Ohou", "412364+AB"),
        sdm("Hisshi no Sou", "6321463214+B", "close"),
        SHUUEN,
        sp("Byakko So", "214+A", "A or B"),
        sp("Byakko Shu", "214+C", "Speed and EX: 214+C again, twice"),
        sp("Retsu Hoko", "623+A"),
        sp("Zetsu Hoko", "623+B"),
        sp("Hisui Sai", "41236+A", "close; A, B or C"),
        sp("Kongo Sai", "632146+C", "close; then 41236+A or 41236+B"),
    ]),
    ("kagami", 0x0C, "Shinnosuke Kagami", "Fighters", [
        dm("Houou Tenshou", "641236+AB"),
        dm("Guren Suzaku", "41236+AB", "in air"),
        sdm("Houou Ittou", "41236+B", "in air"),
        SHUUEN,
        sp("Hien Yoku", "236+A"),
        sp("Gouen Sou", "236+B", "236+B again for Shiranui"),
        sp("Sho Hoko", "623+A", "A or B"),
        sp("En Hoko", "63214+A", "close; A or B"),
        sp("Shizuka Naru Kodou", "214+C", "hold C"),
    ]),
    ("kojiroh", 0x0D, "Kojiroh Sanada", "Fighters", [
        dm("Mu Myou Ken Nie", "21416+AB"),
        sdm("Rouga Zero", "21416+B"),
        SHUUEN,
        sp("Shoubi Sen", "214+A"),
        sp("Mu Myou Ken", "214+B"),
        sp("Shunjin", "214+C", "C close for Tenchi"),
        sp("Koku Satsu", "623+A", "A or B"),
        sp("Shikku Satsu", "236+A", "A or B"),
    ]),
    ("hibiki", 0x0E, "Hibiki Takane", "Fighters", [
        dm("Hassyousuru Shinki Nari", "21416+AB"),
        sdm("Shi o Osorenu Kokoro Nari", "641236+B"),
        SHUUEN,
        sp("Touma Nite Kiru Nari", "236+A", "A or B"),
        sp("Chika Yorite Kiru Nari", "623+B", "hold B to wait"),
        sp("Suigetsu o Tsuku Nari", "623+C", "hold C to wait"),
        sp("Iwoawasu Nari", "41236+C", "then C, or B+C"),
        sp("Kamihitoe Nite Kawasu Nari", "AB", "then 6+C or 4+C"),
    ]),
    ("setsuna", 0x0F, "Setsuna", "Fighters", [
        dm("Mumei: Zetsu", "236236+AB"),
        sdm("Mumei: Kyoku", "6321463214+B", "41236+AB after it"),
        SHUUEN,
        sp("Mumei: Ichi", "236+A", "A or B"),
        sp("Mumei: Ni", "623+A", "A or B"),
        sp("Mumei: San", "214+B"),
        sp("Mumei: Yon", "623+C"),
        sp("Mumei: Go", "63214+C", "41236+C after it"),
    ]),
    ("kouryu", 0x10, "Kouryu", "Boss", [
        dm("Suzaku", "641236+A", "in air"),
        dm("Byakko", "641236+B", "close"),
        dm("Genbu", "641236+C"),
        dm("Seiryu", "641236+D"),
        sdm("Shishin no Chikara", "463214+B"),
        SHUUEN,
        sp("Ku o Turanuku Ninuri no Ya", "236+A", "A or B"),
        sp("Amatsu Tsumi Kiyomeru Ooharai", "214+A", "press again, up to 4 times"),
        sp("Kotoamatsu Konshin no Itto", "214+B", "press again, up to 4 times"),
        sp("Shoryuken", "214+C"),
    ]),
    ("kaedenormal", 0x11, "Kaede (normal)", "Secret", [
        dm("Kasshin: Fukuryuu", "21416+AB"),
        sdm("Kasshin: Kouryuu", "21416+B"),
        SHUUEN,
        sp("Ittou Hayate", "236+A", "A or B"),
        sp("Ittou Kuga", "623+A", "A or B; with B: 623+B again for Ittou Kabutowari"),
        sp("Ittou Renjin Zan", "214+A", "A or B"),
        sp("Ittou Tabakaze", "214+C"),
        sp("Ittou Arashi Uchi", "41236+C", "close"),
    ]),
]

# A+B is a stance every fighter has (the same animation, 0xFD, for all), so
# Lee's Kasumi and Hibiki's Kamihitoe are tried with a follow-up only they have.
TEST_CMD = {("lee", "AB"): "AB,C", ("hibiki", "AB"): "AB,4+C"}

# Detection: game ID (block +0x61) to roster id. 18 (Hagure) and 19
# (Kotetsu) are only select-screen picks: in the fight the byte holds the
# fighter they become.
IDS = [cid for cid, _, _, _, _ in sorted(CHARACTERS, key=lambda c: c[1])] + [None, None]


def roster():
    return {
        "game": "lastbld2",
        "sources": [
            "https://raw.githubusercontent.com/AntoPISA/MAME_SupportFiles/main/command.dat/command.dat",
            "Checked in FBNeo with tools/movetest.py (tools/lastbld2_roster.py test), in each style",
        ],
        "characters": [
            {"id": cid, "name": name, "team": team,
             "moves": [{"name": n, "cmd": c, "type": t, "note": note} for n, c, t, note, _ in moves]}
            for cid, _, name, team, moves in CHARACTERS
        ],
    }


def write():
    with open(OUT, "w") as f:
        json.dump(roster(), f, indent=1, ensure_ascii=False)
        f.write("\n")


# ---------------------------------------------------------------- the real game

def _emu():
    sys.path.insert(0, HERE)
    import movetest as M
    from emu import Emu
    return M, Emu


PTR = (0x10E344, 0x10E348)
GRID = {0: (0, 0), 2: (0, 1), 4: (0, 2), 8: (0, 3), 6: (0, 4), 3: (0, 5), 9: (0, 6), 12: (0, 7),
        1: (1, 0), 15: (1, 1), 5: (1, 2), 14: (1, 3), 13: (1, 4), 7: (1, 5), 10: (1, 6), 11: (1, 7)}
# Select-screen codes (command.dat; checked): Kaede (normal), Hagure, Kotetsu.
CODES = {17: (0, "C" * 9 + "B" + "C" * 4), 18: (4, "C" * 8 + "B" * 9 + "|C"), 19: (11, "C" * 5 + "B" * 10 + "CC")}
STYLES = {P: 0, S: 1, E: 2}


def word(e, a, r=None):
    return (e.byte(a, r) << 8) | e.byte(a + 1, r)


def block(e, p, r=None):
    return 0x100000 | word(e, PTR[p] + 2, r)


def in_round(e, r=None):
    r = r or e.ram()
    return e.byte(0x10E36C, r) == 1 and e.byte(0x10E443, r) == 0 and e.byte(0x10E46A, r) == 0


def boot(Emu):
    """Two credits, both players in, past How to Play: the select screen."""
    e = Emu("lastbld2")
    e.run(1200)
    e.tap("COIN", 8, 20); e.tap("COIN", 8, 20); e.run(60)
    e.tap("START", 8, 30); e.run(60); e.tap("START", 8, 30, port=1); e.run(180)
    e.tap("A", 4, 10); e.tap("A", 4, 10, port=1); e.run(100)
    return e, e.save()


def pick(e, M, cid, port):
    start = (port, 0)
    at, code = CODES.get(cid, (cid, ""))
    pos = GRID.get(at, start)
    for _ in range(pos[1]):
        e.tap("DOWN", 3, 10, port=port)
    if pos[0] != start[0]:
        e.tap("RIGHT" if pos[0] else "LEFT", 3, 10, port=port)
    for ch in code:
        if ch == "|":
            e.run(60)
        else:
            e.tap(ch, 2, 4, port=port)
    e.tap("A", 4, 20, port=port)
    if cid not in GRID and cid not in CODES:
        # Kouryu: his code (command.dat) didn't work here; the cheat file's pick byte.
        M.poke(e, 0x10BD59 + 2 * port, cid)


def pick_style(e, port, style):
    """The style screen comes once both have picked: any direction moves between Power and Speed."""
    if style == 1:
        e.tap("DOWN", 3, 10, port=port)
    if style == 2:
        # EX: on Speed C x6, Power B x3, Speed C x4, in 4 seconds.
        for d, b, n in ((1, "C", 6), (1, "B", 3), (1, "C", 4)):
            e.tap("DOWN", 2, 6, port=port)
            for _ in range(n):
                e.tap(b, 2, 6, port=port)
        e.run(10)
    e.tap("A", 4, 20, port=port)


def to_fight(e, M, sel, c1, c2, s1=0, s2=0):
    e.load(sel)
    pick(e, M, c1, 0); pick(e, M, c2, 1); e.run(40)
    pick_style(e, 0, s1); pick_style(e, 1, s2)
    for _ in range(1500):
        e.run(2)
        if in_round(e):
            e.run(30)
            return True
    return False


def make_game(M):
    import ctypes as C

    def fast(e, a):
        p = e.lib.retro_get_memory_data(2)
        return C.c_uint8.from_address(p + ((a - 0x100000) ^ 1)).value

    class LB2(M.Game):
        def __init__(self):
            self.g = "lastbld2"
            self.kof97 = self.dd = self.sf3 = self.mvc = False
            self.buttons = "ABCD"
            M.GAME[0] = self

        def bind(self, e):
            r = e.ram()
            self.p, self.q = block(e, 0, r), block(e, 1, r)
            self.xs = (self.p + 0x80, self.q + 0x80)
            p = self.p
            M.ACT[0] = lambda e: (fast(e, p + 0x74) << 8) | fast(e, p + 0x75)

        def power(self, e, maxmode=False, lowlife=False):
            M.poke(e, self.p + 0x17D, 0x40)
            if maxmode or lowlife:
                M.poke(e, self.p + 0x17E, 0); M.poke(e, self.p + 0x17F, 0x30)

        def before(self, e):
            return fast(e, self.p + 0x17D)

        def used(self, e, acts, before):
            # A super empties the gauge; some specials take a little (Akari's Kawari Hito Gata: 8).
            spent = before - min(fast(e, self.p + 0x17D), before)
            return spent if spent >= 0x20 else 0

    # The other strength of a slash: A <-> B.
    M.variant = lambda cmd: cmd.translate(str.maketrans("AB", "BA"))
    return LB2()


def test(ids):
    M, Emu = _emu()
    e, sel = boot(Emu)
    game = make_game(M)
    report = {}
    for cid, gid, name, _, moves in CHARACTERS:
        if ids and cid not in ids:
            continue
        dummy = 0x05 if gid != 0x05 else 0x01
        res = {}
        for style in (P, S, E):
            # Power: every move. Speed and EX: the supers, and moves only some styles have.
            sub = [m for m in moves if style == P or m[2] != "special" or m[4] != ANY]
            if not to_fight(e, M, sel, gid, dummy, STYLES[style], 0):
                print(cid, style, "no fight"); continue
            r = e.ram()
            game.bind(e)
            got = (e.byte(game.p + 0x61, r), e.byte(game.p + 0x17C, r))
            ch = {"moves": [{"name": n, "cmd": TEST_CMD.get((cid, c), c), "type": t, "note": note, "roster": c}
                            for n, c, t, note, _ in sub]}
            for m, ok, tries in M.test_char(e, game, e.save(), ch):
                res.setdefault((m["name"], m["roster"]), {})[style] = (ok, tries)
            print(cid, style, "ID/style read", got, flush=True)
        rows = []
        for n, c, t, note, modes in moves:
            r = res.get((n, c), {})
            if "in air" in note:
                # Air only: the jump's try must start an animation the same input on the floor doesn't.
                for s, (ok, tries) in list(r.items()):
                    floor = {f for l, f, _, _ in tries if l != "air"}
                    r[s] = (any(l == "air" and (f is not None and f not in floor or u) for l, f, u, _ in tries), tries)
            worked = "".join(s for s in (P, S, E) if r.get(s, (False,))[0])
            expect = modes if t != "special" or modes != ANY else P
            status = "ok" if worked == expect or (t == "special" and modes == ANY and worked) else "CHECK"
            rows.append((status, n, c, t, worked, expect, {s: v[1] for s, v in r.items()}))
            print("  %-5s %-36s %-14s %-7s works in %-3s expected %-3s %s" % (
                status, n, c, t, worked or "-", expect, {s: v[1] for s, v in r.items()}), flush=True)
        report[cid] = rows
    return report


def ram_check():
    """Each fighter on both sides: the character byte, both fixed copies, facing flips when crossing."""
    M, Emu = _emu()
    e, sel = boot(Emu)
    bad = 0
    for cid, gid, *_ in CHARACTERS:
        other = 0x0F if gid != 0x0F else 0x00
        for side, (c1, c2) in enumerate(((gid, other), (other, gid))):
            assert to_fight(e, M, sel, c1, c2)
            r = e.ram()
            a, b = block(e, 0, r), block(e, 1, r)
            ids = (e.byte(a + 0x61, r), e.byte(b + 0x61, r), e.byte(0x10E3ED, r), e.byte(0x10E411, r))
            face = (e.byte(a + 0x41, r), e.byte(b + 0x41, r))
            ok = ids == (c1, c2, c1, c2) and face == (1, 0) and word(e, a + 0x88, r) == 0
            bad += not ok
            print(cid, "P%d" % (side + 1), "blocks", hex(a), hex(b), "ids", ids, "facing", face,
                  "x", word(e, a + 0x80, r), word(e, b + 0x80, r), "ok" if ok else "BAD", flush=True)
    return bad


def replay():
    """Load a state, play the same inputs twice (both players), compare RAM every frame.
    The serialized states themselves differ in a few bytes near the start (14-16 of
    415155: sound/timer bookkeeping, different bytes each run); RAM and the picture
    match, and play continued from either end state matches too."""
    M, Emu = _emu()
    e, sel = boot(Emu)
    to_fight(e, M, sel, 0x04, 0x09)
    st = e.save()
    inputs = [((i * 5) % 9 + 1, "ABCD"[(i // 4) % 4] if i % 3 == 0 else "") for i in range(1500)]

    def go(s):
        e.load(s)
        rams = []
        for d, b in inputs:
            e.run(1, *(M.DIRS[d] + tuple(b)))
            e.run(1, *(M.DIRS[10 - d] + tuple(b)), port=1)
            rams.append(e.ram())
        return rams, e.save()

    (r1, s1), (r2, s2) = go(st), go(st)
    r3, r4 = go(s1)[0], go(s2)[0]
    print("RAM identical every frame:", r1 == r2, "; continued from both end states:", r3 == r4,
          "; state bytes differing:", sum(x != y for x, y in zip(s1, s2)), "of", len(s1))
    return r1 == r2 and r3 == r4


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "write"
    if cmd == "test":
        test(set(sys.argv[2:]))
    elif cmd == "ram":
        sys.exit(1 if ram_check() else 0)
    elif cmd == "replay":
        sys.exit(0 if replay() else 1)
    else:
        write()
