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

# ----- KOF 2002 -----
r = load('kof2002')
joe = char(r, 'joe')
# Not a move: 41236+A or C is Hurricane Upper (SuperCombo, SNK Fandom).
drop(joe, 'Bakuretsu Hurricane', '41236+C')
add(joe, {'name': 'Bakuretsu Ken', 'cmd': 'A,A,A,A,A', 'type': 'special', 'note': 'P: A or C: ' + MASH + '; finishers: 236+A, 236+C'})
add(char(r, 'ralf'), {'name': 'Vulcan Punch', 'cmd': 'A,A,A,A,A', 'type': 'special', 'note': 'P: A or C: ' + MASH})
add(char(r, 'chang'), {'name': 'Tekkyuu Dai Kaiten', 'cmd': 'A,A,A,A,A', 'type': 'special', 'note': 'P: A or C: ' + MASH + '; stop with A+B+C+D'})
add(char(r, 'billy'), {'name': 'Senpuu Kon', 'cmd': 'A,A,A,A,A', 'type': 'special', 'note': MASH})
m = move(char(r, 'chang'), 'Dai Hakai Nage')
m['cmd'] = '632146+A'
m['note'] = 'P: A or C; close range'
m = move(char(r, 'kdash'), 'Crimson Star Road')
m['cmd'] = '214+C~A'
m['note'] = 'HSDM: MAX mode + 1 power stock (2 total incl. activation) + life at or below ~1/4; C, then A at once'
m = next((m for m in char(r, 'ryo')['moves'] if m['name'] in ('Ryuuko Ranbu: Destroy', 'Ryuuko Ranbu (MAX)')))
m['name'] = 'Ryuuko Ranbu (MAX)'
m['cmd'] = '2363214+AC'
m['note'] = ("SDM (MAX DM): MAX mode + 1 power stock (2 total incl. activation); SNK Fandom's input: SuperCombo's "
             "'qcf + C, A' doesn't come out in the game")
for name in ('Loyalty Test For the Liberalists',):
    for m in char(r, 'angel')['moves']:
        if m['name'] == name and 'Unchain' not in m.get('note', ''):
            m['note'] = m.get('note', '') + '; only as the finish of an Unchain combo (SuperCombo, Moegirl)'
add(char(r, 'angel'), {'name': "Survivor's Banquet", 'cmd': '4623+BCD', 'type': 'hsdm',
                       'note': 'HSDM: MAX mode + 1 power stock + low life; counters a jumping attack (SuperCombo)'})
save('kof2002', r)

# ----- KOF 2005 (10th Anniversary 2005 Unique, kf2k5uni) -----
# KOF 2002 underneath. Every move was tried in kf2k5uni; a move whose 2002
# input no longer works was found by its animation (the same animation ID as
# in 2002) under the input the 2005 sources give, or by trying every motion.
f = json.loads(json.dumps(r))
f['game'] = 'kf2k5uni'
f['sources'] = f['sources'] + [
    'https://www.ddooo.com/softdown/63700.htm (拳皇十周年2005特别版: hidden supers)',
    'https://www.kofcn.org/article/13185.html (拳皇2005十周年纪念加强版出招表)',
    'https://www.gamespot.com.cn/youxi/27588.html (hidden supers)',
]
NEW = '2005 input; the 2002 one no longer works'


def redo(cid, name, cmd, note, old=None):
    m = move(char(f, cid), name, old)
    m['cmd'] = cmd
    m['note'] = note


# Hidden supers (MAX2) take the simpler inputs ddooo.com lists for this hack.
# Each was the same move as 2002's (same animation) unless noted.
HIDDEN = {
    'kyo': ('524 Shiki: Kamichiri', '624624+AC'), 'benimaru': ('Raijin Ten', '4646+ABCD'),
    'terry': ('Rising Force', '2626+AC'), 'andy': ("Zan'ei Shitou Reppa Dan", '2626+AC'),
    'ryo': ('Tenchi Haoh Ken', '2626+AC'), 'robert': ('Zetsu!! Ryuuko Ranbu', '2624+BD'),
    'leona': ('Rebel Spark', '2426+BD'), 'ralf': ('Umanori Galactica Phantom', '242626+AD'),
    'athena': ('Psycho Medley', '26+ABCD'), 'mai': ('Shiranui-ryuu: Kyuubi no Kitsune', '2424+ABCD'),
    'yuri': ('"Ee!" Hien Hou\'ou Kyaku', '26264+BD'), 'iori': ('Kin 1209 Shiki Homura Hotogi', '242626+AC'),
    'yashiro': ('[ERROR...] CODE "2002"', '2426+BD'), 'kdash': ('Crimson Star Road', '2626+BD'),
    'vanessa': ('Gaia Gear', '6426+AC'), 'kula': ('Freeze Completion', '22+AC'),
    'kusanagi': ('Saishuu Kessen Ougi Reishiki', '2626+AC'), 'joe': ('Cross Gigantes', '2626+BD'),
    'takuma': ('Kosatsu Jin', '2424+AC'), 'clark': ('Running Pirates', '426426+BD'),
    'chin': ('Suisou Enbu', '2626+BD'), 'mature': ('Ecstasy 816', '2626+BD'),
    'shermie': ('Inazuma Leg Lariat', '2626+BD'), 'maxima': ('Arc Enemy', '26+AC'),
    'seth': ('Sigure-Rangiku', '6426+AC'), 'ramon': ('Hypnotic Tiger', '2426+AC'),
    'rugal': ('Kaiser Phoenix', '426426+AC'),
    'choi': ('Shakushi', '2626+AC'), 'whip': ('Super Black Hawk', '4+AC'),
}
for cid, (name, cmd) in HIDDEN.items():
    m = move(char(f, cid), name)
    m['cmd'] = cmd
    extra = {'choi': ' (in the air)', 'andy': ', then B+C+D for the extra hit'}.get(cid, '')
    m['note'] = 'HSDM: MAX mode + 1 power stock; 2005 input' + extra
# Regular moves on new inputs (identified by their 2002 animation).
redo('terry', 'Rising Tackle', '623+A', 'P: A or C; ' + NEW + ' ([2]8)')
redo('ryo', 'Zanretsu Ken', '214+A', 'P: A or C; ' + NEW + ' (646)')
redo('robert', "Gen'ei Kyaku", '214+B', 'K: B or D; on the ground (in the air 214+B is Hien Ryuujin Kyaku); ' + NEW + ' (646)')
redo('benimaru', 'Benimaru Collider', '623+A', 'P: A or C; close range; ' + NEW + ' (632146)')
redo('iori', 'Kuzukaze', '41236+B', 'K: B or D; close range; ' + NEW + ' (632146+P)')
redo('joe', 'Ougon no Tiger Kick', '2368+B', 'DM: 1 power stock; K: B or D; ' + NEW + ' (2369; kofcn.org)')
redo('k9999', 'Tsuki...', '6321463214+A', 'DM: 1 power stock; P: A or C; ' + NEW + ' (1632143)')
# No input brings these out in 2005 Unique (2002's, the sources', or a scan of
# every common motion), so they're left out rather than guessed.
for cid, name in (('k9999', 'Temee mo oucchimae!!'), ('k9999', 'Chikara ga...katte ni...uwaaaa!!'),
                  ('mary', 'M. Typhoon'), ('mary', 'M. Dynamite Swing'), ('mary', 'M. Dynamite Swing (MAX)'),
                  ('vice', 'Overkill'), ('daimon', 'Fu Rin Ka Zan'),
                  # 214214 + any button (ddooo.com) only starts his wind-up, as 2002's does; the super never follows.
                  ('chang', 'Tekkyuu Dai Sekai')):
    c = char(f, cid)
    c['moves'] = [m for m in c['moves'] if m['name'] != name]
# The Orochi team (Yashiro, Shermie, Chris from the Orochi box of the select
# screen) shares the regular fighters' IDs; detection tells them apart by the
# fighter block's flag (Detect.kt). Each was picked from the select screen and
# its moves tried: Orochi Yashiro's hidden super is 2+ABC here (2 power stocks,
# 1 in MAX mode; ddooo.com: 末日之审判), and these 2002 inputs bring out nothing.
redo('oyashiro', 'Armageddon', '2+ABC', 'HSDM: 2 power stocks (1 in MAX mode); ' + NEW + ' (B,D,A,ABC)')
drop(char(f, 'oyashiro'), 'Ankoku Jigoku Gokuraku Otoshi', '6321463214+AC')
drop(char(f, 'oshermie'), 'Unmei no Ya', 'B,A,4,A,A')
drop(char(f, 'oshermie'), "Shukumei, Gen'ei, Shinshi", '236236+BD')
drop(char(f, 'ochris'), 'Sanagi o Yaburi, Chou wa Mau', 'D,C,2,C,D')
save('kof2005', f)
print('ok 2002/2005')

# ----- KOF 2002 Magic Plus (kf2k2mp, kf2k2mpl, kf2k2mp2) -----
# The 2002 list, checked in kf2k2mp2 (Magic Plus II). Changes below are where
# Magic Plus differs from 2002.
mp = json.loads(json.dumps(r))
mp['game'] = 'kf2k2mp2'
# No input brings these out in Magic Plus II: their 2002 inputs do something
# else, and a scan of every common motion never showed their 2002 animation.
for cid, name in (('benimaru', 'Benimaru Collider'), ('shermie', 'F-Captured'), ('kula', 'Freeze Completion')):
    c = char(mp, cid)
    c['moves'] = [m for m in c['moves'] if m['name'] != name]
save('kof2002mp', mp)
print('ok magic plus')
