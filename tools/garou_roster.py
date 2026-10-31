"""Writes the Garou: Mark of the Wolves move list, rosters/garou.json, and
checks it in the real game.

Moves from MAME's command.dat ($info=garou) and SuperCombo's Garou pages;
every one tried in FBNeo the way the app plays it (tools/movetest.py's
timeline). Buttons: A light punch, B light kick, C heavy punch, D heavy kick.
A special that takes either punch or either kick is written with the light
one (A or B) and noted. S.Power supers ("dm") take one stock, the A/B
version; P.Power supers ("sdm") take the full gauge (two stocks), the C/D
version.

  python3 tools/garou_roster.py           writes the JSON
  python3 tools/garou_roster.py --test    tries every move in FBNeo (EMU=...)
  python3 tools/garou_roster.py --test rock tizoc
  python3 tools/garou_roster.py --replay  checks that a savestate replays exactly

What the game keeps where (68000 addresses; found with tools/emu.py, leads
from peon2's fbneo-training-mode garou.lua, its hitbox viewer and dammit's
fighting-OSD.lua):
  0x100400 P1's fighter block, P2's 0x100 later
    +0x11  character ID (byte; word at +0x10): ORDER below
    +0x20  X (word)          +0x28 height (word, 0 on the floor)
    +0x58  bit 7 set: facing left
    +0x60  what the fighter does (word): 0 standing, 2 walking, 0x23-0x26
           standing A-D, ...; specials and supers 0x53-0x133 (per character),
           feints 0x134/0x135, taunt 0x200
    +0x8E  life (0x78 full)  +0xBE power gauge (0x80 full = two stocks)
  0x10748A the match's phase (byte): 0x44 while a round is fought (0x38 the
           round's intro, 0x66 after it ends; also 0x44 in the attract demo)
"""
import json
import os
import re
import sys

ORDER = ["terry", "rock", "donghwan", "jaehoon", "hotaru", "gato", "jenet", "butt",
         "hokutomaru", "freeman", "tizoc", "kevin", "grant", "kain"]

P1 = 0x100400
STRIDE = 0x100
PHASE = 0x10748A
FIGHT = 0x44
GAUGE_FULL = 0x80

C360 = "63214789"
C720 = "6321478963214789"


def sp(name, cmd, note=""):
    return (name, cmd, "special", note)


def sup(name, cmd, note=""):
    return (name, cmd, "dm", ("S.Power; " + note) if note else "S.Power")


def pot(name, cmd, note=""):
    return (name, cmd, "sdm", ("P.Power: full gauge; " + note) if note else "P.Power: full gauge")


CHARACTERS = [
    ("terry", "Terry Bogard", "", [
        sup("Power Geyser", "236236+A"),
        pot("Triple Geyser", "236236+C"),
        sup("Buster Wolf", "236236+B"),
        pot("Buster Wolf", "236236+D"),
        sp("Power Wave", "236+A", "A or C"),
        sp("Burn Knuckle", "214+A", "A or C"),
        sp("Crack Shoot", "214+B", "B or D"),
        sp("Power Dunk", "623+B", "B or D; break: A+B"),
        sp("Power Charge", "66+A", "A or C; up to 3 times"),
    ]),
    ("rock", "Rock Howard", "", [
        sup("Raging Storm", "236236+A"),
        pot("Raging Storm", "236236+C"),
        sup("Shine Knuckle", "236236+B"),
        pot("Shine Knuckle", "236236+D"),
        pot("Deadly Rave Neo", "632146+A,A,A,B,B,C,C,D,D,214+C"),
        sp("Reppuu Ken", "236+A"),
        sp("Double Reppuu Ken", "236+C"),
        sp("Hard Edge", "214+A", "A or C"),
        sp("Rising Tackle", "[2]8+A", "A or C"),
        sp("Crack Counter (high)", "236+B", "catches a high or mid attack"),
        sp("Crack Counter (low)", "236+D", "catches a low attack"),
        sp("Rage Run: Dunk", "214+B"),
        sp("Rage Run: Shift", "214+D"),
        sp("Shinkuu Nage", "AB~" + C360 + "+C", "close; from the evasion attack (A+B), so Rock doesn't jump; break: A+B"),
    ]),
    ("donghwan", "Kim Dong Hwan", "", [
        sup("Super Dong Hwan Kyaku", "236236+A", "in air"),
        pot("Super Dong Hwan Kyaku", "236236+C", "in air"),
        sup("Raimei Dan", "236236+B"),
        pot("Raimei Dan", "236236+D"),
        pot("Ore-sama Hou'ou Kyaku", "236236236+C"),
        sp("Shiden Kyaku", "236+B", "B or D"),
        sp("Kuu Sajin", "623+A", "A or C; break: A+B"),
        sp("Raimei Zan", "214+B", "B or D"),
        sp("Ashi Binta", "63214+C"),
        sp("Hishou Kyaku", "2+B", "in air; B or D"),
    ]),
    ("jaehoon", "Kim Jae Hoon", "", [
        sup("Hou Yoku Ten Shou Kyaku", "236236+A"),
        pot("Hou Yoku Ten Shou Kyaku", "236236+C"),
        sup("Hou'ou Kyaku", "236236+B"),
        pot("Hou'ou Kyaku", "236236+D"),
        sp("Shakka Shuu", "236+A", "A or C"),
        sp("Ryuusei Raku", "236+B", "B or D"),
        sp("Hangetsu Zan", "214+B", "B or D"),
        sp("Hien Zan", "[2]8+B", "B or D; break: A+B"),
        sp("Hishou Kyaku", "2+B", "in air; B or D"),
    ]),
    ("hotaru", "Hotaru Futaba", "", [
        sup("Soushou Tenrenge", "236236+A"),
        pot("Soushou Tenrenge", "236236+C"),
        sup("Tenshou Ranki", "236236+B"),
        pot("Tenshou Ranki", "236236+D"),
        sp("Hakki Shou", "236+A", "A or C"),
        sp("Soushou Shin", "214+A", "A or C"),
        sp("Kobi Kyaku", "214+B", "B or D; break: A+B"),
        sp("Tenshin Shou", "623+B", "B or D"),
        sp("Rengeki Shou", "214+B", "in air; B or D"),
        sp("Shajou Tai", "41236+D"),
    ]),
    ("gato", "Gato", "", [
        sup("Zero Kiba", "236236+A"),
        pot("Zero Kiba", "236236+C"),
        sup("Tatsu Kiba", "236236+B"),
        pot("Tatsu Kiba", "236236+D"),
        pot("Tenryuu Retsu Kiba", "623+AB,623+AB,623+AB"),
        sp("Shinga", "236+A", "A or C"),
        sp("Shinga > Totsuga", "236+A,66+A"),
        sp("Raiga", "623+B", "B or D; break: A+B"),
        sp("Fuuga", "214+B", "B or D"),
        sp("Houzan Sai Hekiga", "[2]8+C", "catches a high or mid attack"),
    ]),
    ("jenet", "B. Jenet", "", [
        sup("Many Many Torpedoes", "236236+A"),
        pot("Many Many Torpedoes", "236236+C"),
        sup("Aurora", "236236+B"),
        pot("Aurora", "236236+D"),
        sp("Baffrass", "236+A", "A or C"),
        sp("Crazy Ivan", "214+A", "A or C"),
        sp("The Hind", "236+B", "B or D; break: A+B"),
        sp("Gulf Tomahawk", "214+B", "B or D"),
        sp("Harrier Bee", "2+B", "in air; B or D; press again to keep going"),
    ]),
    ("butt", "Khushnood Butt", "", [
        sup("Haoh Shoukou Ken", "236236+A"),
        pot("Haoh Shoukou Ken", "236236+C"),
        sup("Kyokugen Kohou", "236236+B"),
        pot("Kyokugen Kohou", "236236+D"),
        pot("Ryuuko Ranbu", "236236236+C"),
        sp("Ko'ou Ken", "236+A", "A or C"),
        sp("Kohou", "623+A", "A or C; break: A+B"),
        sp("Hien Shippuu Kyaku", "236+B", "B or D"),
        sp("Joudan Harai", "214+A", "catches a high attack"),
        sp("Gedan Harai", "214+B", "catches a low attack"),
    ]),
    ("hokutomaru", "Hokutomaru", "", [
        sup("Chou Hissatsu Shuriken", "236236+A"),
        pot("Chou Hissatsu Shuriken", "236236+C"),
        sup("Ougi: Chou Hissatsu Tatsumaki", "236236+B"),
        pot("Ougi: Chou Hissatsu Tatsumaki", "236236+D"),
        sp("Shuriken", "236+A", "A or C; also in air"),
        sp("Kuuha Dan", "236+B", "B or D; break: A+B"),
        sp("Rakka Zan (B)", "214+B", "also in air"),
        sp("Rakka Zan (D)", "214+D", "also in air"),
        sp("Karakusa Giri", "214+A", "A or C"),
    ]),
    ("freeman", "Freeman", "", [
        sup("Full Blast", "236236+A"),
        pot("Full Blast", "236236+C"),
        sup("Creeping Death", "236236+B"),
        pot("Creeping Death", "236236+D"),
        sp("Nightmare", "236+A", "A or C"),
        sp("V.O.D.", "214+A", "A or C; up to 3 times"),
        sp("Crow", "214+B", "B or D"),
        sp("Phobia", "623+A", "A or C"),
        sp("Morbid Angel", "41236+B", "B or D; break: A+B"),
    ]),
    ("tizoc", "Tizoc", "", [
        sup("Big Fall Griffon", "2+AC~" + C720 + "+A", "close; from the down feint (2+A+C), so Tizoc doesn't jump"),
        pot("Big Fall Griffon", "2+AC~" + C720 + "+C", "close; from the down feint (2+A+C), so Tizoc doesn't jump"),
        sup("Daedalus Attack", "236236+B"),
        pot("Daedalus Attack", "236236+D"),
        sp("Justice Hurricane", "63214~8+A", "close; A or C; up comes with the button, or Tizoc jumps"),
        sp("Hercules Throw", "41236+B", "close"),
        sp("Olympus Over", "214+B", "B or D; break with D: A+B"),
        sp("Active Tupon", "623+A", "A or C"),
        sp("Poseidon Wave", "421+A", "A or C; break with C: A+B"),
    ]),
    ("kevin", "Kevin Rian", "", [
        sup("Lucky Strike", "236236+A"),
        pot("Lucky Strike", "236236+C"),
        sup("Gattling Freezer", "236236+B"),
        pot("Gattling Freezer", "236236+D"),
        sp("Hell Rotor", "214+A", "hold A to charge"),
        sp("Downward Hell Rotor", "214+C", "hold C to charge"),
        sp("Hell Arrest", "41236+A", "close; A or C"),
        sp("Hell Snipe", "623+A", "A or C"),
        sp("Hell Creeper", "214+B"),
        sp("Abide Mine", "214+D"),
        sp("Hell Trap", "236+B", "B or D; break: A+B"),
    ]),
    ("grant", "Grant", "Secret", [
        sup("Majin Haten Dan", "236236+A"),
        pot("Majin Haten Dan", "236236+C"),
        sup("Majin Engetsu Rin", "236236+B"),
        pot("Majin Engetsu Rin", "236236+D"),
        sp("Kokuen Ryuu", "214+A", "A or C"),
        sp("Kyou Choujin", "623+A", "A or C; break: A+B"),
        sp("Gou Retsu Shou", "646+A", "A or C"),
        sp("Gou Dangai", "236+B", "B or D"),
        sp("Messhou Hisetsu", "2+B", "in air; B or D"),
    ]),
    ("kain", "Kain R. Heinlein", "Secret", [
        sup("Himmlische Atem", "236236+A"),
        pot("Himmlische Atem", "236236+C"),
        sup("Himmlische Seele", "236236+B"),
        pot("Himmlische Seele", "236236+D"),
        sp("Schwarze Flamme (low)", "[4]6+A"),
        sp("Schwarze Flamme (high)", "[4]6+C"),
        sp("Schwarze Panzer", "[4]6+B"),
        sp("Schwarze Panzer Force", "[4]6+D", "break: A+B"),
        sp("Schwarze Lanze", "[2]8+B", "B or D; break: A+B"),
    ]),
]

# Moves listed by the sources the app leaves out: none of them can be played as a command.
LEFT_OUT = {
    "jenet": "Ennui Mademoiselle (A, B, C during a Just Defend)",
    "butt": "Ryuusen Ken / Kosen Kyaku (hold C or D, let go)",
}

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "../android/core/src/main/resources/rosters/garou.json")


def main():
    roster = {
        "game": "garou",
        "sources": [
            "https://raw.githubusercontent.com/AntoPISA/MAME_SupportFiles/main/command.dat/command.dat",
            "https://wiki.supercombo.gg/w/Garou:_Mark_of_the_Wolves",
            "Checked in FBNeo with tools/garou_roster.py --test (tools/movetest.py)",
        ],
        "characters": [
            {"id": cid, "name": name, "team": team,
             "moves": [{"name": n, "cmd": c, "type": t, "note": note} for n, c, t, note in moves]}
            for cid, name, team, moves in CHARACTERS
        ],
    }
    with open(OUT, "w") as f:
        json.dump(roster, f, indent=1, ensure_ascii=False)
        f.write("\n")


# ---------------------------------------------------------------- checks in FBNeo

# Portraits on the select screen, left to right (P1 starts on the first; RIGHT moves on).
PORTRAITS = ["rock", "donghwan", "hotaru", "butt", "hokutomaru", "kevin",
             "freeman", "tizoc", "jenet", "gato", "jaehoon", "terry"]
# The MVS codes: cursor on a portrait, hold START and tap the directions.
SECRET = {"grant": ("donghwan", "882282"), "kain": ("jaehoon", "228828")}


def emu_tools():
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import movetest
    from emu import Emu
    return movetest, Emu


def select_screen(Emu):
    """Boots garou, puts in two credits, starts P1 and P2: the select screen."""
    e = Emu("garou")
    e.run(600)
    e.tap("COIN"); e.tap("COIN", port=1); e.run(60)
    e.tap("START"); e.run(30); e.tap("START", port=1)
    e.run(360)
    for _ in range(40):  # the "how to play" screen
        if e.byte(PHASE) != 0x33:
            break
        e.tap("A"); e.run(30)
    e.run(30)
    e.tap("START", port=1)
    e.run(60)
    return e, e.save()


def to_fight(e, sel, cid):
    """From the select screen: P1 picks cid (P2 stays on Terry), on to the round."""
    e.load(sel)
    target, code = SECRET.get(cid, (cid, ""))
    for _ in range(PORTRAITS.index(target)):
        e.tap("RIGHT", 4, 4)
    if code:
        e.run(6, "START")
        for c in code:
            e.run(6, "START", {"8": "UP", "2": "DOWN"}[c]); e.run(6, "START")
        e.run(4, "START", "A"); e.run(10)
    for f in range(2400):
        if f % 20 == 0 and f < 600:
            e.run(4, "A"); e.run(4, "A", port=1)
        else:
            e.run(1)
        if e.byte(PHASE) == FIGHT:
            break
    else:
        raise RuntimeError("no fight for " + cid)
    e.run(40)
    assert e.byte(P1 + 0x11) == ORDER.index(cid), (cid, e.byte(P1 + 0x11))
    return e.save()


def patch_movetest(mt):
    """Points tools/movetest.py at Garou's RAM."""
    class Garou:
        g = "garou"
        kof97 = dd = sf3 = mvc = False
        buttons = "ABCD"
        xs = (P1 + 0x20, P1 + STRIDE + 0x20)

        def power(self, e, maxmode=False, lowlife=False):
            mt.poke(e, P1 + 0xBE, GAUGE_FULL)
            mt.poke(e, 0x107490, 0x99)  # the round's clock

        def before(self, e):
            return e.byte(P1 + 0xBE)

        def used(self, e, acts, before):
            return before - min(e.byte(P1 + 0xBE), before)

    game = Garou()
    mt.GAME[0] = game
    mt.ACT[0] = lambda e: mt.word(e, P1 + 0x60)
    return game


# Below this the fighter's own animations: standing, walking, jumps, normals, throws.
FIRST_MOVE = 0x53
# Up held 6 frames: a full jump, early enough on the way up for Jenet's
# Harrier Bee (a 4-frame tap is Garou's low hop, too short for the air supers).
JUMP = [(8, "")] * 6


def extra_baseline(mt, e, states, game):
    """Feints, evasions and the T.O.P. attack aren't moves of the list either."""
    seen = set()
    for st in states:
        for d in (2, 6, 5, 3, 1, 4):
            for b in ("AC", "AB", "CD", "BD", "ABCD"):
                e.load(st); game.power(e)
                seen.update(mt.play(e, [(d, "")] * 3 + [(d, b)] * 4, None, 50))
        for b in game.buttons:
            e.load(st); game.power(e)
            seen.update(mt.play(e, JUMP + [(5, b)] * 4, None, 50))
    return seen


def attempt(mt, e, st, game, cmd, air):
    """Plays cmd (after a full jump for air): the animations it went through, power used."""
    e.load(st)
    game.power(e)
    before = game.before(e)
    acts = mt.play(e, (JUMP if air else []) + mt.compile_cmd(cmd), None, 90)
    return acts, game.used(e, acts, before)


def size(cmd):
    return len(re.findall(r"\[\d\]|\d", cmd))


def first_step(cmd):
    """A sequence's inputs up to the first with a button ("236+A,66+A" -> "236+A")."""
    parts = re.split(r"[,~]", cmd)
    out = []
    for p in parts:
        out.append(p)
        if re.search(r"[A-D]", p):
            break
    return "~".join(out) if "~" in cmd.split(out[-1])[0] else ",".join(out)


def test_char(mt, e, game, base, ch):
    """Like movetest.test_char. A move works when it starts an animation no
    normal, jump, dash, feint or evasion makes, and no simpler listed move of
    its kind starts first; a super must also use power. A sequence must go on
    to an animation its first input alone doesn't reach."""
    far, close = mt.setup_states(e, base)
    seen = {a for a in mt.baseline(e, [far, close], game) | extra_baseline(mt, e, [far, close], game)}
    seen |= set(range(FIRST_MOVE))
    tried = []
    owner = {}
    for m in ch["moves"]:
        n = (m.get("note") or "").lower()
        only_air = re.search(r"(?<!also )in air", n)
        plan = [(l, st, air) for l, st, air in (("far", far, False), ("close", close, False), ("air", far, True))
                if (air and "in air" in n) or (not air and not only_air)]
        tries = []
        for label, st, air in plan:
            acts, used = attempt(mt, e, st, game, m["cmd"], air)
            f = mt.first_new(acts, seen)
            later = set()
            if re.search(r"[,~]", m["cmd"]) and m["type"] == "special":
                pre, _ = attempt(mt, e, st, game, first_step(m["cmd"]), air)
                later = set(acts) - seen - set(pre)
            tries.append((label, f, used, later))
        tried.append(tries)
        kind = m["type"] == "special"
        for _, f, _, _ in tries:
            key = (kind, f)
            if f is not None and (key not in owner or size(m["cmd"]) < owner[key]):
                owner[key] = size(m["cmd"])
    res = []
    for m, tries in zip(ch["moves"], tried):
        kind = m["type"] == "special"
        own = lambda f: f is not None and owner[(kind, f)] >= size(m["cmd"])
        if kind:
            ok = any(own(f) or later for _, f, _, later in tries)
        else:
            ok = any(u and own(f) and (True, f) not in owner for _, f, u, _ in tries)
        res.append((m, ok, [(l, f, u) for l, f, u, _ in tries]))
    return res


def run_tests(names):
    mt, Emu = emu_tools()
    e, sel = select_screen(Emu)
    game = patch_movetest(mt)
    chars = [c for c in roster_dicts() if not names or c["id"] in names]
    bad = 0
    for ch in chars:
        base = to_fight(e, sel, ch["id"])
        res = test_char(mt, e, game, base, ch)
        ok = sum(1 for _, k, _ in res if k)
        print("%-11s %d/%d" % (ch["id"], ok, len(res)), flush=True)
        for m, k, tries in res:
            t = " ".join("%s:%s/%d" % (l, hex(f) if f is not None else "-", u) for l, f, u in tries)
            print("   %s %-32s %-34s %s" % ("ok " if k else "BAD", m["name"], m["cmd"], t), flush=True)
            bad += not k
    return bad


def roster_dicts():
    return [{"id": cid, "name": name, "moves": [{"name": n, "cmd": c, "type": t, "note": note} for n, c, t, note in moves]}
            for cid, name, _, moves in CHARACTERS]


def replay():
    """Loads a state mid-fight, plays the same random inputs on both sides
    twice (1200 frames) and compares RAM every 100 frames and the last picture."""
    import random
    mt, Emu = emu_tools()
    e, sel = select_screen(Emu)
    st = to_fight(e, sel, "rock")
    rnd = random.Random(1)
    pick = ["", "A", "B", "C", "D", "AB", "CD"]
    seq = [(rnd.randint(1, 9), rnd.choice(pick), rnd.randint(1, 9), rnd.choice(pick)) for _ in range(1200)]
    runs = []
    for _ in range(2):
        e.load(st)
        rams = []
        for i, (d1, b1, d2, b2) in enumerate(seq):
            e.held[0] = set(mt.DIRS[d1] + tuple(b1))
            e.held[1] = set(mt.DIRS[d2] + tuple(b2))
            e.lib.retro_run()
            if i % 100 == 99:
                rams.append(e.ram())
        e.held = [set() for _ in range(4)]
        runs.append((rams, e.frame.tobytes(), e.save()))
    same = runs[0][0] == runs[1][0] and runs[0][1] == runs[1][1]
    diff = sum(x != y for x, y in zip(runs[0][2], runs[1][2]))
    print("RAM (12 checkpoints) and picture: %s; savestate blobs differ in %d bytes"
          % ("identical" if same else "DIFFERENT", diff))
    return 0 if same else 1


if __name__ == "__main__":
    if "--test" in sys.argv:
        sys.exit(1 if run_tests([a for a in sys.argv[1:] if not a.startswith("--")]) else 0)
    elif "--replay" in sys.argv:
        sys.exit(replay())
    else:
        main()
