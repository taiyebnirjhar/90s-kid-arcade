"""Tries KOF moves in the real game the way the app plays them, and says
whether the fighter really did each one.

A move counts as working when:
- a super (dm/sdm/hsdm) uses up power, or darkens the screen for its flash;
- a special starts an animation (the action ID at 0x108172) that no normal
  attack, jump or dash makes, and that no simpler listed move starts.

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
    """Writes a byte of 68000 RAM (FBNeo's byte order, see docs/kof-ram.md)."""
    p = e.lib.retro_get_memory_data(2)
    C.c_uint8.from_address(p + ((addr - 0x100000) ^ 1)).value = val


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
        buttons = ''.join(b for b in 'ABCD' if b in rest)
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
        acts.append(word(e, 0x108172))
    for _ in range(n_after):
        e.run(1)
        acts.append(word(e, 0x108172))
        lums.append(brightness(e))
    # Darkest moment relative to the first frames (1.0: no change).
    ref = max(lums[:3]) if lums else 1
    LAST_DARK[0] = min(lums) / ref if lums and ref else 1.0
    return acts

class Game:
    def __init__(self, g):
        self.g = g
        self.kof97 = g.startswith('kof97')
        self.stock = 0x1082E3

    def power(self, e, maxmode=False, lowlife=False):
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
    x1 = word(e, 0x108118, r); x2 = word(e, 0x108318, r)
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
            for b in 'ABCD':
                e.load(st); game.power(e)
                seen.update(play(e, [(d, '')] * 2 + [(d, b)] * 4, None, 50))
        # jumps and hops with each button, dashes
        for j in (7, 8, 9):
            for hold in (1, 4):
                for b in 'ABCD':
                    e.load(st); game.power(e)
                    seen.update(play(e, [(j, '')] * hold + [(5, '')] * 8 + [(5, b)] * 4, None, 50))
        for seq in ([6, 5, 6], [4, 5, 4]):
            for b in ['', 'A', 'B', 'C', 'D']:
                e.load(st); game.power(e)
                fr = []
                for d in seq: fr += [(d, '')] * 2
                fr += [(seq[-1], '')] * 15 + [(seq[-1], b)] * 4
                seen.update(play(e, fr, None, 40))
        for b in ('AB', 'CD', 'BC', 'ABC', 'AC', 'BD'):
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
    before = e.byte(game.stock)
    pre = [(9, '')] * 4 + [(5, '')] * 8 if air else []
    acts = play(e, pre + compile_cmd(cmd), None)
    after = min(e.byte(game.stock), before)
    used = before - after
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

def variant(cmd):
    """The other-strength version: A<->C, B<->D."""
    return cmd.translate(SWAP)

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
        if m['type'] in ('dm', 'hsdm', 'sdm'):
            ok = any(used for _, _, used in tries)
        else:
            # Starting the same way as a simpler listed move: the game read that one instead.
            ok = any(f is not None and size(owner.get(f, m['cmd'])) >= size(m['cmd']) for _, f, _ in tries)
        res.append((m, ok, [(l, f, u, owner.get(f) if owner.get(f) != m['cmd'] else '') for l, f, u in tries]))
    return res
