"""Tries moves in the real game the way the app plays them, and says
whether the fighter really did each one. KOF, Double Dragon (doubledr) and
Street Fighter III (sfiii3n): Game() holds where each keeps what's read.

A move counts as working when:
- a super (dm/sdm/hsdm) uses up power (KOF stock, Double Dragon's CHARGE,
  a 3rd Strike Super Art stock), or darkens the screen for its flash;
- a special starts an animation (KOF: the action ID at 0x108172) that no
  normal attack, jump or dash makes, and that no simpler listed move starts.

Uses tools/emu.py (bring your own FBNeo core and ROMs). Typical use:

  from movetest import *
  e = Emu("kof97"); game = Game("kof97")
  ... get to a fight with the fighter you want (to_fight), then
  res = test_char(e, game, e.save(), character_from_a_roster_json)

compile_cmd() mirrors Macro.kt's compile(); keep the two in step.
"""
import ctypes as C
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from emu import Emu  # noqa: E402

def poke(e, addr, val):
    """Writes a byte of RAM (FBNeo's byte order, see docs/kof-ram.md)."""
    p = e.lib.retro_get_memory_data(2)
    C.c_uint8.from_address(p + ((addr - e.base) ^ e.swap)).value = val


def word(e, addr, r=None):
    return (e.byte(addr, r) << 8) | e.byte(addr + 1, r)


def round_on(e, g, r=None):
    """A round is being fought (detection's test, docs/kof-ram.md)."""
    r = r or e.ram()
    if g.startswith('kof97'):
        return (e.byte(0x10A83E, r) & 0x80) == 0 and e.byte(0x10A840, r) > 0 and (e.byte(0x10A788, r) & 2) == 0
    return (e.byte(0x10A7D6, r) & 0x80) == 0 and e.byte(0x10A7D8, r) > 0 and (e.byte(0x10A788, r) & 2) == 0


def to_fight(e, g, limit=40):
    """From the select screens (two players in), press on until the round starts."""
    for t in range(limit):
        e.run(60); e.tap('A', 4, 10); e.tap('A', 4, 10, port=1)
        if round_on(e,g): return True
    return False


DIRS = {1: ('DOWN', 'LEFT'), 2: ('DOWN',), 3: ('DOWN', 'RIGHT'), 4: ('LEFT',), 5: (), 6: ('RIGHT',),
        7: ('UP', 'LEFT'), 8: ('UP',), 9: ('UP', 'RIGHT')}

STEP = int(os.environ.get("STEP", "2"))
SEQ_PRESS = int(os.environ.get("SEQ_PRESS", "4"))
SEQ_GAP = int(os.environ.get("SEQ_GAP", "2"))

# A real stick passes through the diagonal between two directions a quarter
# turn apart: 4 to 2 goes via 1. Games read "214214" with that roll.
ROLL = {(4, 2): 1, (2, 4): 1, (2, 6): 3, (6, 2): 3, (6, 8): 9, (8, 6): 9, (8, 4): 7, (4, 8): 7}

QUICK_PRESS, QUICK_GAP = 2, 0

def compile_cmd(cmd, step=None, charge=50, press=4, gap=2):
    """Same timeline as Macro.kt compile() (facing right). "," separates inputs; "~" means
    the next input follows at once ("214+C~A")."""
    step = step or STEP
    src = re.sub(r'\s', '', cmd.upper())
    out = []
    tokens = re.split(r'([,>~])', src)
    segs = tokens[0::2]
    seps = tokens[1::2]
    for i, seg in enumerate(segs):
        quick_next = i < len(seps) and seps[i] == '~'
        quick_self = i > 0 and seps[i - 1] == '~'
        parts = []
        j = 0
        while j < len(seg):
            if seg[j] == '[':
                parts.append((int(seg[j + 1]), True)); j += 3
            elif seg[j].isdigit():
                parts.append((int(seg[j]), False)); j += 1
            else:
                break
        rest = seg[j:].lstrip('+')
        buttons = ''.join(b for b in 'ABCDEF' if b in rest)
        for k, (d, c) in enumerate(parts):
            if k and parts[k - 1][0] == d:
                out += [(5, '')] * step
            elif k and (parts[k - 1][0], d) in ROLL:
                out += [(ROLL[(parts[k - 1][0], d)], '')] * step
            # Up in the middle of a motion is only passed through, or the fighter jumps.
            through = d in (7, 8, 9) and k < len(parts) - 1 and not c
            out += [(d, '')] * (charge if c else 1 if through else step)
        if buttons:
            last = parts[-1][0] if parts else 5
            out += [(last, buttons)] * (QUICK_PRESS if quick_next or quick_self else press)
        if i < len(segs) - 1:
            out += [(5, '')] * (QUICK_GAP if quick_next else gap)
    out.append((5, ''))
    return out

LAST_DARK = [1.0]
# What the fighter is doing: KOF's action ID; Game() switches it per game.
ACT = [lambda e: word(e, 0x108172)]
GAME = [None]

def brightness(e):
    """Mean brightness of the play field (0-255), for spotting a super's dark flash."""
    im = e.frame.crop((0, 40, e.frame.width, e.frame.height - 40)).convert('L').resize((38, 18))
    px = list(im.getdata())
    return sum(px) / len(px)

def play(e, frames, watch, n_after=70):
    acts = []
    lums = []
    for d, b in frames:
        e.run(1, *(DIRS[d] + tuple(b)))
        acts.append(ACT[0](e))
    for _ in range(n_after):
        e.run(1)
        acts.append(ACT[0](e))
        lums.append(brightness(e))
    # Darkest moment relative to the first frames (1.0: no change).
    ref = max(lums[:3]) if lums else 1
    LAST_DARK[0] = min(lums) / ref if lums and ref else 1.0
    return acts

# Street Fighter III 3rd Strike, P1 (effie3rd's 3rd_training_lua memory map).
SF3_P1 = 0x02068C6C
SF3_GAUGE = 0x020695B5  # max at -2
SF3_STOCK = 0x020695BF  # max at -2
# Marvel vs Capcom: P1's Hyper Combo levels (FBNeo cheat file).
MVC_LEVEL = 0xFF3274


class Game:
    def __init__(self, g):
        self.g = g
        self.kof97 = g.startswith('kof97')
        self.dd = g == 'doubledr'
        self.sf3 = g.startswith('sfiii')
        self.mvc = g.startswith('mvsc')
        self.buttons = 'ABCDEF' if self.sf3 or self.mvc else 'ABCD'
        GAME[0] = self
        self.stock = 0x1082E3
        self.xs = (0x108118, 0x108318)
        if self.dd:
            # Double Dragon: the sprite frame (each move has its own frames).
            self.xs = (0x100430, 0x100530)
            ACT[0] = lambda e: word(e, 0x10042E)
        elif self.mvc:
            # Marvel vs Capcom: the animation at +0x60 of the point fighter's block.
            self.xs = (0xFF300C, 0xFF340C)
            ACT[0] = lambda e: word(e, 0xFF3060)
        elif self.sf3:
            # 3rd Strike: the animation at +0x202 of the fighter block (training scripts).
            self.xs = (SF3_P1 + 0x64, SF3_P1 + 0x498 + 0x64)
            ACT[0] = lambda e: word(e, SF3_P1 + 0x202)
        else:
            ACT[0] = lambda e: word(e, 0x108172)

    def used(self, e, acts, before):
        """Power a super used: KOF's stock; Double Dragon's CHARGE spent, or a transformation."""
        if self.sf3:
            return before - min(e.byte(SF3_STOCK), before)
        if self.mvc:
            return before - min(e.byte(MVC_LEVEL), before)
        if self.dd:
            # CHARGE goes out when the move ends, a few seconds in.
            for _ in range(450):
                if (e.byte(0x10042A) & 1, e.byte(0x100445)) != before:
                    return 1
                e.run(1)
            return 0
        return before - min(e.byte(self.stock), before)

    def before(self, e):
        if self.sf3:
            return e.byte(SF3_STOCK)
        if self.mvc:
            return e.byte(MVC_LEVEL)
        return (e.byte(0x10042A) & 1, e.byte(0x100445)) if self.dd else e.byte(self.stock)

    def power(self, e, maxmode=False, lowlife=False):
        if self.mvc:
            poke(e, MVC_LEVEL, 3)
            return
        if self.sf3:
            # A full Super Art gauge: its size and stock count are kept beside it.
            poke(e, SF3_GAUGE, e.byte(SF3_GAUGE - 2))
            poke(e, SF3_STOCK, max(1, e.byte(SF3_STOCK - 2)))
            return
        if self.dd:
            # CHARGE mode is bit 0 of 0x10042A (the full gauge alone isn't enough).
            poke(e, 0x10042A, e.byte(0x10042A) | 1)
            return
        if self.kof97:
            for a, v in ((0x1082E3, 3), (0x1081EA, 0x40), (0x10825F, 0x23), (0x1081E0, 0x10)):
                poke(e, a, v)
            if maxmode:
                for a, v in ((0x1081E0, 0x10), (0x1081E8, 0x81), (0x1081EA, 0x3F), (0x10824D, 0x04)):
                    poke(e, a, v)
        else:
            poke(e, 0x1082E3, 5)
            if maxmode:
                for a, v in ((0x1081EA, 0x2F), (0x1081EB, 0x10), (0x10821D, 0x20)):
                    poke(e, a, v)
        if lowlife:
            poke(e, 0x108239, 0x10)

def xdist(e):
    r = e.ram()
    x1 = word(e, GAME[0].xs[0], r); x2 = word(e, GAME[0].xs[1], r)
    return abs(x2 - x1)

def setup_states(e, base):
    """far: the round start; close: walked up to the opponent."""
    e.load(base); e.run(30)
    far = e.save()
    last, still = None, 0
    for _ in range(300):
        e.run(1, 'RIGHT')
        d = xdist(e)
        still = still + 1 if d == last else 0
        last = d
        if still >= 8 or d < 30: break
    e.run(20)
    close = e.save()
    return far, close

def baseline(e, states, game):
    seen = set()
    for st in states:
        e.load(st); game.power(e)
        seen.update(play(e, [(5, '')] * 30, None, 0))
        for d in range(1, 10):
            e.load(st); game.power(e)
            seen.update(play(e, [(d, '')] * 25, None, 30))
            for b in game.buttons:
                e.load(st); game.power(e)
                seen.update(play(e, [(d, '')] * 2 + [(d, b)] * 4, None, 50))
        # jumps and hops with each button, dashes
        for j in (7, 8, 9):
            for hold in (1, 4):
                for b in game.buttons:
                    e.load(st); game.power(e)
                    seen.update(play(e, [(j, '')] * hold + [(5, '')] * 8 + [(5, b)] * 4, None, 50))
        for seq in ([6, 5, 6], [4, 5, 4]):
            for b in [''] + list(game.buttons):
                e.load(st); game.power(e)
                fr = []
                for d in seq: fr += [(d, '')] * 2
                fr += [(seq[-1], '')] * 15 + [(seq[-1], b)] * 4
                seen.update(play(e, fr, None, 40))
        for b in ('AB', 'CD', 'BC', 'ABC', 'AC', 'BD') + (('AD', 'BE', 'CF', 'DE') if game.sf3 or game.mvc else ()):
            e.load(st); game.power(e)
            seen.update(play(e, [(5, b)] * 4, None, 50))
    return seen

def uses_max(game, move):
    note = (move.get('note') or '').lower()
    return not game.kof97 and (move['type'] in ('hsdm', 'sdm') or note.startswith('sdm') or note.startswith('hsdm'))

def try_move(e, st, game, move, air):
    cmd = move['cmd']
    t = move['type']
    maxmode = uses_max(game, move)
    e.load(st)
    game.power(e, maxmode=maxmode, lowlife=(t == 'hsdm'))
    if maxmode and not game.kof97:
        e.run(1)
    before = game.before(e)
    pre = [(9, '')] * 4 + [(5, '')] * 8 if air else []
    acts = play(e, pre + compile_cmd(cmd), None)
    used = game.used(e, acts, before)
    # A super darkens the whole screen for its flash: count that as power used.
    if not used and LAST_DARK[0] < 0.45:
        used = 9
    return acts, used

def first_new(acts, seen):
    for a in acts:
        if a not in seen:
            return a
    return None

def suffixes(cmd):
    """Shorter commands inside a single-step command: its motion with directions dropped from the front."""
    if ',' in cmd or '>' in cmd:
        return []
    m = re.match(r'^((?:\[\d\]|\d)*)\+?([A-D]*)$', cmd.upper())
    if not m:
        return []
    parts = re.findall(r'\[\d\]|\d', m.group(1))
    return [''.join(parts[k:]) + '+' + m.group(2) for k in range(1, len(parts))]

SWAP = str.maketrans('ABCD', 'CDAB')
SWAP6 = str.maketrans('ABCDEF', 'CBAFED')

def variant(cmd):
    """The other-strength version: A<->C, B<->D (six buttons: light <-> heavy)."""
    return cmd.translate(SWAP6 if GAME[0] and (GAME[0].sf3 or GAME[0].mvc) else SWAP)

def test_char(e, game, base, ch):
    far, close = setup_states(e, base)
    seen = baseline(e, [far, close], game)
    plans = []
    for m in ch['moves']:
        n = (m.get('note') or '').lower()
        plans.append([(l, st, air) for l, st, air in (('far', far, False), ('close', close, False), ('air', far, True))
                      if not air or re.search(r'\bair\b', n)])
    def run(m, plan):
        tries = []
        for label, st, air in plan:
            acts, used = try_move(e, st, game, m, air)
            tries.append((label, first_new(acts, seen), used))
        return tries
    tried = [run(m, p) for m, p in zip(ch['moves'], plans)]
    # Which listed move (or other-strength version) each first animation
    # belongs to: the one with the shortest motion that makes it.
    def size(cmd):
        return len(re.findall(r'\[\d\]|\d', cmd))
    listed = {m['cmd'] for m in ch['moves']}
    owner = {}
    def claim(f, cmd):
        if f is not None and (f not in owner or size(cmd) < size(owner[f])):
            owner[f] = cmd
    for m, p, tries in zip(ch['moves'], plans, tried):
        if m['type'] != 'special':
            continue
        for _, f, _ in tries:
            claim(f, m['cmd'])
        if m['type'] == 'special' and variant(m['cmd']) not in listed:
            for _, f, _ in run(dict(m, cmd=variant(m['cmd'])), p):
                claim(f, variant(m['cmd']))
    res = []
    for m, tries in zip(ch['moves'], tried):
        if m['type'] in ('dm', 'hsdm', 'sdm', 'super'):
            ok = any(used for _, _, used in tries)
        else:
            # Starting the same way as a simpler listed move: the game read that one instead.
            ok = any(f is not None and size(owner.get(f, m['cmd'])) >= size(m['cmd']) for _, f, _ in tries)
        res.append((m, ok, [(l, f, u, owner.get(f) if owner.get(f) != m['cmd'] else '') for l, f, u in tries]))
    return res
