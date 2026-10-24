"""Applies the move fixes found by testing every move in the real games
(FBNeo, tools/emu.py) and cross-checking wikis. Run once; idempotent."""
import json, os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'android', 'core', 'src', 'main', 'resources', 'rosters')


def load(name):
    return json.load(open(os.path.join(ROOT, name + '.json')))


def save(name, data):
    with open(os.path.join(ROOT, name + '.json'), 'w') as f:
        json.dump(data, f, indent=1, ensure_ascii=False)


def char(r, cid):
    return next(c for c in r['characters'] if c['id'] == cid)


def move(c, name, cmd=None):
    return next(m for m in c['moves'] if m['name'] == name and (cmd is None or m['cmd'] == cmd))


def add(c, m):
    if not any(x['name'] == m['name'] and x['cmd'] == m['cmd'] for x in c['moves']):
        c['moves'].append(m)


def drop(c, name, cmd):
    c['moves'] = [m for m in c['moves'] if not (m['name'] == name and m['cmd'] == cmd)]


MASH = 'tap the button rapidly (the button plays it as five quick taps)'

# ----- KOF '97 -----
r = load('kof97')
m = move(char(r, 'benimaru'), 'Elec-Trigger')
m['cmd'] = '632146632146+A'
m['note'] = ('A or C; close range; written hcb x2 (StrategyWiki, SuperCombo, command.dat), but in the game it only '
             'comes out with hcb-forward twice (632146 632146); SDM: same input during MAX (Extra mode: MAX gauge + flashing life)')
# Listed only by SuperCombo as a direct input; it is Suikan Kanou's follow-up (6+P), and doesn't come out on its own.
drop(char(r, 'chin'), 'Choushuu Rikugyo', '23[6]+A')
add(char(r, 'joe'), {'name': 'Bakuretsu Ken', 'cmd': 'A,A,A,A,A', 'type': 'special', 'note': 'A or C: ' + MASH + '; finishers: 236+A (Bakuretsu Ken Finish), 236+C (Bakuretsu Hurricane)'})
add(char(r, 'ralf'), {'name': 'Vulcan Punch', 'cmd': 'A,A,A,A,A', 'type': 'special', 'note': 'A or C: ' + MASH})
add(char(r, 'clark'), {'name': 'Vulcan Punch', 'cmd': 'A,A,A,A,A', 'type': 'special', 'note': 'A or C: ' + MASH})
add(char(r, 'chang'), {'name': 'Tekkyuu Dai Kaiten', 'cmd': 'A,A,A,A,A', 'type': 'special', 'note': 'A or C: ' + MASH + '; move with 4/6; stop with A+B+C+D'})
add(char(r, 'billy'), {'name': 'Senpuu Kon', 'cmd': 'A,A,A,A,A', 'type': 'special', 'note': MASH})
add(char(r, 'billy'), {'name': 'Shuuten Renpa Kon', 'cmd': 'C,C,C,C,C', 'type': 'special', 'note': MASH + '; close range'})
save('kof97', r)

# ----- KOF '97 Plus: '97 plus Orochi, playable (moves checked in kof97pls) -----
p = json.loads(json.dumps(r))
p['game'] = 'kof97pls'
p['sources'] = p['sources'] + [
    'https://snk.fandom.com/wiki/Orochi/Move_List',
    'https://www.9game.cn (拳皇97风云再起 Orochi move list)',
]
p['characters'].append({
    'id': 'orochi', 'name': 'Orochi', 'team': 'Boss',
    'moves': [
        {'name': 'Marokare', 'cmd': '214+A', 'type': 'dm', 'note': "A or C; '97 Plus input (the original's is 236236+A/C): a storm of white energy over the screen"},
        {'name': 'Oomiwa', 'cmd': '623+A', 'type': 'dm', 'note': "A or C; '97 Plus input: pulls the opponent in and tears out their life force"},
        {'name': 'Harae', 'cmd': '236+A', 'type': 'special', 'note': 'multi-hit light wave'},
        {'name': 'Utsushi Iwai', 'cmd': '236+B', 'type': 'special', 'note': 'B (seal in front) or C (seal above); counters an attack'},
        {'name': 'Tadasu', 'cmd': '236+D', 'type': 'special', 'note': 'swallows a projectile and fires it back'},
        {'name': 'Ho no Susori', 'cmd': '63214+A', 'type': 'special', 'note': 'any button: A, B, C, D set how far away the pillar rises'},
    ],
})
save('kof97plus', p)
print('ok')
