"""Writes the Samurai Shodown II move list, rosters/samsho2.json, and checks
it in the real game.

Moves from MAME's command.dat ($info=samsho2); every one tried in FBNeo the
way the app plays it (tools/movetest.py), and only the ones the fighter
really does are kept. Buttons are the cabinet's: A light slash, B medium
slash, C light kick, D medium kick (A+B heavy slash, C+D heavy kick). A move
for "any slash" or "any kick" is written with the light one.

Types: "special"; "dm" for the Weapon Destruction moves (Buki Hakai Waza:
POW gauge at MAX, the gauge empties); "sdm" for the secret moves.

RAM (68000 addresses, checked in samsho2, see the report in the PR):
  0x100A46 / 0x100A4A  long pointers to P1's / P2's fighter object (the game
                       allocates objects dynamically, every fight elsewhere)
  object +0x4E X (word), +0x50 Y (224 on the floor), +0x56 Y speed (0 on the
         floor), +0x65 character ID, +0x6C animation frame (long, ROM
         pointer), +0x7F facing (1 = facing left), +0xBB health (0x80 full),
         +0xF0 POW gauge (0x20 = MAX)
  0x100D0B / 0x100D1B  character picked, P1 / P2
  round on: 0x100AE5 == 3 and timer 0x100AC6 != 0 and 0x100AD5 == 0 and
            (0x100AD3 & 0x80) == 0

  python3 tools/samsho2_roster.py            writes the JSON
  python3 tools/samsho2_roster.py --test [id ...]   tries every move (slow)
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# Game ID (0x100D0B, object +0x65) -> roster id. 0x0A is unused, Mizuki
# (0x10) is the CPU boss and Kuroko (0x11) only in the home (AES) version.
IDS = ["haohmaru", "nakoruru", "hanzo", "galford", "wanfu", "ukyo", "kyoshiro", "genan",
       "earthquake", "jubei", None, "charlotte", "genjuro", "chamcham", "sieger", "nicotine",
       None, None]


def sp(name, cmd, note=""):
    return (name, cmd, "special", note)


def wd(name, cmd, note=""):
    return (name, cmd, "dm", "POW MAX" + ("; " + note if note else ""))


def sec(name, cmd, note=""):
    return (name, cmd, "sdm", "secret move" + ("; " + note if note else ""))


def nui(cmd):
    return ("Nuigurumi", cmd, "special", "secret: turns into a stuffed doll for a moment")


MASH = "A,A,A,A,A,A"

# (id, name, moves): the ring holds six moves, so each list starts with what matters most.
CHARACTERS = [
    ("haohmaru", "Haohmaru", [
        wd("Tenha Seikou Zan", "632146+A"),
        sp("Ougi Senpuu Retsu Zan", "236+A"),
        sp("Ougi Kogetsu Zan", "623+A"),
        sp("Ougi Resshin Zan", "623+C"),
        sp("Sake Kougeki", "214+A", "reflects projectiles"),
        sp("Fake Senpuu Retsu Zan", "236+C"),
        sec("Tenha Fuujin Zan", "341236421+BC"),
        nui("6321464+B"),
    ]),
    ("genjuro", "Genjuro Kibagami", [
        wd("Gokou Zan", "632146+A"),
        sp("Sanren Satsu", "236+A", "236+A twice more: Tsuno, Rin"),
        sp("Touha Kouyoku Jin", "623+A"),
        sp("Ouka Zan", "214+A"),
        nui("6321464+B"),
    ]),
    ("kyoshiro", "Kyoshiro Senryo", [
        wd("Ketsuniku no Mai", "641236+C", "4 or 6 to move"),
        sp("Kaen Kyokubu", "236+A"),
        sp("Kaiten Kyokubu: Ten", "623+A"),
        sp("Fuuretsu Sen", "214+A"),
        sp("Choubi Jishi", "214+C"),
        sp("Chikemuri Kuruwa", "2+AB", "in air"),
        nui("6412364+D"),
    ]),
    ("hanzo", "Hanzo Hattori", [
        wd("Tenma Rimetsu", "632146+D"),
        sp("Ninpou Reppuu Shuriken", "41236+A", "in air"),
        sp("Ninpou Baku'en Ryuu", "63214+A"),
        sp("Ninpou Mozu Otoshi", "623+C", "close"),
        sp("Ninpou Kage Bunshin", "641236+A"),
        sp("Ninpou Utsusemi Tenbu", "63214+BCD"),
        sec("Kage Mai", "6464642+BCD"),
        nui("6464642+A"),
    ]),
    ("sieger", "Neinhalt Sieger", [
        wd("Operation Tiger", "632146+CD"),
        sp("Wolf Fangen", "641236+AB"),
        sp("Vulcan Weinen", "236+A", "mash A for Dryukken, Exploizon"),
        sp("Blitz Jaeger", "63214+C"),
        sp("Tigerkopf", "236+C", "then 236+B, then 63214+A"),
        sp("Firestorm", "3+BC"),
        nui("6321464+A"),
    ]),
    ("charlotte", "Charlotte", [
        wd("Splash Gradation", "632146+B"),
        sp("Splash Fount", MASH, "mash a slash"),
        sp("Power Gradation", "123+A"),
        sp("Tri-Slash", "623+A"),
        nui("6321463214+D"),
    ]),
    ("galford", "Galford", [
        wd("Mega Strike Dog", "632146+D"),
        sp("Plasma Blade", "236+A"),
        sp("Rush Dog", "214+A"),
        sp("Machine Gun Dog", "214+C"),
        sp("Replica Dog", "214+D"),
        sp("Strike Heads", "623+C", "close"),
        sp("Shadow Copy", "641236+A"),
        sp("Replica Attack", "63214+BCD"),
        sec("Invisibility", "6464642+BCD"),
        nui("6321464+C"),
    ]),
    ("wanfu", "Wan-Fu", [
        wd("Shin Kikou Dai Bakuten", "641236+B"),
        sp("Shin Kikou Bakutenhoh", "214+A"),
        sp("Kikou Senpuu Geki", "623+A"),
        sp("Kikou Dai Bokusatsu", "236+A"),
        sp("Benpatsu", "214+CD"),
        nui("6412364+D"),
    ]),
    ("ukyo", "Ukyo Tachibana", [
        wd("Tsubame Rokuren", "641236+AB"),
        sp("Hiken Tsubame Gaeshi", "1236+A", "in air"),
        sp("Hiken Sasame Yuki", "214+A"),
        sp("Hiken Oboro Gatana", "236+C"),
        sp("Sasame Yuki: Damashi", "214+C"),
        nui("63214623+B"),
    ]),
    ("jubei", "Jubei Yagyu", [
        wd("Zetsu Suigetsu Tou", "632146+C"),
        sp("Hassou Happa", MASH, "mash a slash"),
        sp("Kattotsu Suigetsu Tou", "236+A"),
        sp("Nikkaku Ratou", "623+A"),
        sp("Shingan Tou: Souha", "2146+A", "counters a jump, high or mid attack"),
        nui("6321464+D"),
    ]),
    ("chamcham", "Cham Cham", [
        wd("Mou Moto Nai Ndakarana", "641236+A"),
        sp("Yokoni Nageru Ndazo!", "236+A"),
        sp("Shouni Nageru Ndazo!", "214+A"),
        sp("Tobi Hikkaki", "236+C"),
        sp("Mura Paku Paku", "63214+C"),
        sp("Paku Paku Dios", "63214+D"),
        sp("PakuPaku Gaburu", "63214+CD"),
        nui("6321463214+C"),
    ]),
    ("nakoruru", "Nakoruru", [
        wd("Irusuka Yatoro Lise", "641236+A"),
        sp("Annu Mutsube", "412+A"),
        sp("Lela Mutsube", "236+A"),
        sp("Amube Yatoro", "63214+A"),
        sp("Kamui Rimse", "214+A"),
        sp("Taka ni Tsukamaru", "214+C", "hangs from Mamahaha"),
        sec("Apefuchi Kamui Rimse", "3214623+BC"),
        nui("6321463214+D"),
    ]),
    ("earthquake", "Earthquake", [
        wd("Earth Got 'Em", "641236+CD", "4 or 6 to move"),
        sp("Fat Chainsaw", MASH, "mash a slash"),
        sp("Fat Breath", "214+A"),
        sp("Fat Copy", "641236+A"),
        sp("Fat Replica Attack", "63214+BCD"),
        sp("Fat Bound", "2+C", "in air"),
        nui("6464642+B"),
    ]),
    ("genan", "Gen-An Shiranui", [
        wd("Madou Diving Claw", "632146+AB"),
        sp("Doku Fubuki", "236+A"),
        sp("Nikuten Tsuki", "623+A"),
        sp("Tsume Tsunami", "63214+A"),
        sp("Utsusemi Dappi", "321+BD"),
        nui("6321463214+C"),
    ]),
    ("nicotine", "Nicotine Caffeine", [
        wd("Niou Furei Satsu", "641236+D"),
        sp("Shikigami Reifu Ikazuchi", "236+A"),
        sp("Shikigami Reifu Hanoo", "236+C"),
        sp("Shitsue Rasen Ashi", "1236+C", "in air"),
        sp("Rashin Jufu", "421+AB"),
        sp("Bakusa Jougeki", "63214+A"),
        nui("412146+D"),
    ]),
]

# Moves tried and dropped (kept out of the JSON), with why (from --test).
DROPPED = {
    ("genan", "Utsusemi Dappi"): "321+BD (and 321+BC, 32/1/2/3/21/6321+BD, step 1 or 2, far or "
                                 "close) starts nothing new in FBNeo",
    # These Nuigurumi motions pass through 214 + the same button, which the
    # game reads first: the fighter's 214 special comes out instead.
    ("genjuro", "Nuigurumi"): "6321464+B gives Ouka Zan (214+B)",
    ("galford", "Nuigurumi"): "6321464+C gives Machine Gun Dog (214+C)",
    ("ukyo", "Nuigurumi"): "63214623+B gives Sasame Yuki (214+B)",
    ("chamcham", "Nuigurumi"): "6321463214+C gives Mura Paku Paku (63214+C)",
}


def roster():
    return {
        "game": "samsho2",
        "sources": [
            "https://raw.githubusercontent.com/AntoPISA/MAME_SupportFiles/main/command.dat/command.dat",
            "Checked in FBNeo with tools/movetest.py (tools/samsho2_roster.py --test)",
        ],
        "characters": [
            {"id": cid, "name": name, "team": "Samurai",
             "moves": [{"name": n, "cmd": c, "type": t, "note": note} for n, c, t, note in moves
                       if (cid, n) not in DROPPED]}
            for cid, name, moves in CHARACTERS
        ],
    }


def write():
    out = os.path.join(HERE, "../android/core/src/main/resources/rosters/samsho2.json")
    with open(out, "w") as f:
        json.dump(roster(), f, indent=1, ensure_ascii=False)
        f.write("\n")


# ---------------------------------------------------------------- testing

P1_PTR, P2_PTR = 0x100A46, 0x100A4A
PICK = (0x100D0B, 0x100D1B)
POW, POW_MAX = 0xF0, 0x20
# Portraits in the order the cursor visits them going right from Haohmaru.
CURSOR = [0x0, 0xC, 0x2, 0xB, 0x4, 0x7, 0x1, 0x9, 0x5, 0xD, 0x8, 0xF, 0x3, 0xE, 0x6]


def setup():
    sys.path.insert(0, HERE)
    import movetest as mt
    from emu import Emu
    return mt, Emu


def word(e, a, r=None):
    return (e.byte(a, r) << 8) | e.byte(a + 1, r)


def obj(e, p, r=None):
    """The fighter object of player p (0, 1)."""
    a = P1_PTR + 4 * p
    return (word(e, a, r) << 16) | word(e, a + 2, r)


def round_on(e, r=None):
    r = r or e.ram()
    return (e.byte(0x100AE5, r) == 3 and e.byte(0x100AC6, r) != 0 and e.byte(0x100AD5, r) == 0
            and e.byte(0x100AD3, r) & 0x80 == 0)


def to_select(e):
    """Power on, two credits, both players start; through the how-to-play to the select screen."""
    e.run(600)
    for _ in range(3):
        e.tap("COIN", 8, 30)
    e.tap("START", 8, 60)
    e.tap("START", 8, 60, port=1)
    e.run(60 + 720 + 1080 + 770)
    before = e.save()
    e.tap("RIGHT", 4, 10)
    assert e.byte(PICK[0]) == CURSOR[1], "not on the select screen"
    e.load(before)
    return before


def pick(e, sel, a, b):
    e.load(sel)
    for _ in range(CURSOR.index(a)):
        e.tap("RIGHT", 4, 10)
    for _ in range(CURSOR.index(b)):
        e.tap("RIGHT", 4, 10, port=1)
    e.tap("A", 4, 10)
    e.tap("A", 4, 10, port=1)
    for _ in range(3000):
        e.run(1)
        if round_on(e):
            e.run(30)
            return e.save()
    raise RuntimeError("no round")


def patch(mt, e):
    """Points movetest at Samurai Shodown II's RAM."""
    def act(e):
        r = e.ram()
        o = obj(e, 0, r)
        return (word(e, o + 0x6C, r) << 16) | word(e, o + 0x6E, r)

    mt.ACT[0] = act

    def xdist(e):
        r = e.ram()
        return abs(word(e, obj(e, 1, r) + 0x4E, r) - word(e, obj(e, 0, r) + 0x4E, r))

    mt.xdist = xdist

    class Game:
        g = "samsho2"
        kof97 = dd = sf3 = mvc = False
        buttons = "ABCD"

        def power(self, e, maxmode=False, lowlife=False):
            mt.poke(e, obj(e, 0) + POW, POW_MAX)

        def before(self, e):
            return e.byte(obj(e, 0) + POW)

        def used(self, e, acts, before):
            # A Weapon Destruction move empties the MAX gauge while it plays out.
            # Some play out for seconds first (Nakoruru's): wait up to 10 s.
            o = obj(e, 0)
            for _ in range(600):
                if e.byte(o + POW) < before // 2:
                    return 1
                e.run(1)
            return 0

    g = Game()
    mt.GAME[0] = g
    base_baseline = mt.baseline

    def baseline(e, states, game):
        seen = base_baseline(e, states, game)
        # The system motions: lie down, rolls, hops, taunts (held), heavy attacks.
        extra = ["22", "33", "11", "44", "66", "6+BC", "ABCD", "BCD", "ACD", "ABD"]
        for st in states:
            for cmd in extra:
                e.load(st); game.power(e)
                seen.update(mt.play(e, mt.compile_cmd(cmd), None, 60))
            for b in ("AC", "BD"):
                e.load(st); game.power(e)
                seen.update(mt.play(e, [(5, b)] * 40, None, 60))
            # running, then each button
            for b in [""] + list("ABCD"):
                e.load(st); game.power(e)
                fr = [(6, "")] * 2 + [(5, "")] * 2 + [(6, "")] * 20 + [(6, b)] * 4
                seen.update(mt.play(e, fr, None, 40))
        return seen

    mt.baseline = baseline
    # The other strength of a move is the other slash or kick (A<->B, C<->D):
    # each has its own animation, and a long motion that ends in a listed one
    # with the other button must not count as the long move.
    swap = str.maketrans("ABCD", "BADC")
    mt.variant = lambda cmd: cmd.translate(swap)

    # Air moves as the app plays them from the ground (Pad.play jumpFirst):
    # up for 4 frames, 2 neutral, then the motion. Nicotine's Shitsue Rasen
    # Ashi only comes out that early in the jump.
    def try_move(e, st, game, move, air):
        e.load(st)
        game.power(e)
        before = game.before(e)
        pre = [(8, "")] * 4 + [(5, "")] * 2 if air else []
        acts = mt.play(e, pre + mt.compile_cmd(move["cmd"]), None)
        used = game.used(e, acts, before) if move["type"] == "dm" else 0
        return acts, used

    mt.try_move = try_move
    return g


def test(only=None, opponent=0x0):
    mt, Emu = setup()
    e = Emu("samsho2")
    sel = to_select(e)
    g = patch(mt, e)
    results = {}
    for cid, name, moves in CHARACTERS:
        if only and cid not in only:
            continue
        gid = IDS.index(cid)
        st = pick(e, sel, gid, opponent)
        r = e.ram()
        assert e.byte(obj(e, 0, r) + 0x65, r) == gid, (cid, e.byte(obj(e, 0, r) + 0x65, r))
        # Secret moves are judged like specials (a new animation), not by power.
        ch = {"moves": [dict(name=n, cmd=c, type="special" if t == "sdm" else t, note=note, real=t)
                        for n, c, t, note in moves]}
        res = mt.test_char(e, g, st, ch)
        # Control: does the MAX gauge empty by itself in the same time?
        ctrl = mt.try_move(e, st, g, {"cmd": "5", "type": "dm"}, False)[1]
        results[cid] = res
        print(f"== {name} (control used={ctrl})", flush=True)
        for m, ok, tries in res:
            print(f"  {'OK  ' if ok else 'FAIL'} {m['real']:7} {m['cmd']:14} {m['name']}  {tries}", flush=True)
    return results


if __name__ == "__main__":
    if "--test" in sys.argv:
        test([a for a in sys.argv[1:] if not a.startswith("--")] or None)
    else:
        write()
