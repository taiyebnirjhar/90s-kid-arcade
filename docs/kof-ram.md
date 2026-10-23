# KOF '97 / KOF 2002 (+ 2002-based hacks): 68000 RAM map for fighter detection

All addresses are 68000 CPU addresses in Neo Geo work RAM (0x100000-0x10FFFF). Sizes: u8 = byte, u16 = big-endian word as the 68000 sees it.

## 0. FBNeo note: byte order when you read the RAM buffer directly

FBNeo keeps 68K RAM (`Neo68KRAM`, mapped at 0x100000, 64 KB, mirrored up to 0x1FFFFF) as **host-endian 16-bit words**. On a little-endian host (every Android ABI), FBNeo's own byte read does `a ^= 1` for directly mapped memory (src/cpu/m68000_intf.cpp, `ReadByte`).
So if you read the buffer yourself, rather than through `SekReadByte`/`SekReadWord`:
- byte at 68K address A  ->  `Neo68KRAM[(A - 0x100000) ^ 1]`
- word at even 68K address A  ->  `*(uint16_t*)&Neo68KRAM[A - 0x100000]` (native read, no swap)

The Fightcade detector files back this up. They list the kof97 timer (68K 0x10A83A) as "68K RAM" offset **0xA83B**, and the kof2002 timer (0x10A7D2) as **0xA7D3**.
Sources: https://raw.githubusercontent.com/finalburnneo/FBNeo/master/src/cpu/m68000_intf.cpp , https://github.com/fightcadeorg/fightcade-detectors (kof97.inf, kof2002.inf)

## 1. Shared player-struct layout (KOF '94 to 2002 engine)

The hitbox viewer (dammit, edited by peon2) uses one profile for kof94 through kof2002. Every game in it gets the same values: `player = 0x108100`, `player_space = 0x200`, `pos_x = +0x18`, `pos_y = +0x26`, `flip_x = +0x31` (bit 0), `status = +0x7C`. dammit's fighting-OSD.lua also uses `player = 0x108100, space = 0x200` for kof97 and kof2002.

| field | P1 | P2 | size | meaning |
|---|---|---|---|---|
| struct base | 0x108100 | 0x108300 | - | P2 = P1 + 0x200 |
| X position | 0x108118 | 0x108318 | u16 (signed in hitbox script) | world X. Smaller X = further left. Screen X = X - screen_left |
| Y position | 0x108120 / +0x26 | 0x108320 | u16 | the RL projects use +0x20; the hitbox script uses +0x26 for its axis |
| facing | 0x108131 | 0x108331 | u8, bit 0 | 0 = facing left, 1 = facing right |
| character ID | 0x108170 (word) / 0x108171 (low byte) | 0x108370 / 0x108371 | u16 word, use low byte | see the per-game sections |
| action / animation ID | 0x108172 | 0x108372 | u16 | not needed for detection |
| health | 0x108239 | 0x108439 | u8 | kof97 max 0x67, kof2002 max 0x66 |

---

## 2. The King of Fighters '97 (`kof97`)

| what | address | size | values / meaning | source(s) |
|---|---|---|---|---|
| **P1 current on-screen character** | **0x108171** (low byte of word 0x108170) | u8 (word at 0x108170) | ID table below | (a) xeno0/kof97_rl kof97.yaml `own_character: 0x108171 u8`, live-verified on MAME 0.242 (Kyo=0, Benimaru=1, Terry=3, Kensou=13). (b) zengfr/romhack kof97 notes: "人物CH 宽度WORD 108100+70=0x108170"; game code does `MOVE.W (0x70,A4),D2`. (c) CarlosPix/CarlOS FBA cheat kof97.ini "P1 战斗中换人" (change character mid-fight) writes the ID to 0x108171. **3 sources agree** |
| **P2 current on-screen character** | **0x108371** | u8 | same table | kof97_rl (`opp_character`, verified Terry=3, Benimaru=1). CarlOS cheat "P2 战斗中换人" writes 0x108371. 2 sources |
| P1 team picks: slot A / B / C (pick order on select screen) | 0x10A84B / 0x10A84C / 0x10A84D | u8 each | ID table below | Pugsy/MAME cheat XML (zengfr/romhack neogeo/kof97.xml, furrtek/NeoCDSDLoader copy). FBNeo-cheats kof97.ini. mame2003-plus cheat.dat. All one lineage (Pugsy) |
| P1 fight order: 1st / 2nd / 3rd fighter | 0x10A851 / 0x10A852 / 0x10A853 | u8 each | 0 = slot A, 1 = slot B, 2 = slot C | same Pugsy lineage |
| P1 rounds lost | 0x10A84A | u8 | 0..2. Cheat comment: "will determine which member in your team fights (0 = first in team order)" | same Pugsy lineage |
| P1 mode | 0x10A849 | u8 | 0 = Advanced, 1 = Extra | Pugsy lineage |
| P1 alternate-version flags | 0x10A857 | u8 bitmask | bit0/1/2 = slot A/B/C uses the alternate version ('94 Kyo, Orochi Yashiro/Shermie/Chris) | Pugsy lineage |
| P2 team picks A / B / C | 0x10A85C / 0x10A85D / 0x10A85E | u8 | ID table | Pugsy lineage. Team block stride P2 = P1 + 0x11 |
| P2 fight order | 0x10A862 / 0x10A863 / 0x10A864 | u8 | 0..2 slot index | Pugsy lineage |
| P2 mode | 0x10A85A | u8 | | Pugsy lineage |
| P2 rounds lost | 0x10A85B (**inferred**) | u8 | | Every cheat file lists 0x10A84A for P2, the same as P1, which is almost certainly a copy-paste bug. 0x10A85B follows from the +0x11 stride. **Unverified** |
| P2 alternate-version flags | 0x10A868 | u8 bitmask | | Pugsy lineage |
| P1 / P2 X | 0x108118 / 0x108318 | u16 | world coords (kof97_rl saw values up to about 464) | peon2 hitbox (base+0x18). 568xiaoma/KOF97_Agent address.py. JoHnKiking/MameToolkit_kof97. kof97_rl (verified live). zengfr notes `positionP1 0x108118`. **Many sources** |
| P1 / P2 facing | 0x108131 / 0x108331 | u8 (bit 0) | 0 = facing left | peon2 kof97.lua (`p1direction`, `rb()==0` means facing left). Hitbox script (+0x31 bit 0). zengfr notes `BTST #0,(0x31,A4) //判断当前方向`. 3 sources |
| round timer | 0x10A83A | u8 BCD | 0x60 = "60" | peon2 kof97.lua, kof97_rl, KOF97_Agent, MameToolkit, Fightcade detector (offset 0xA83B) |
| **round in progress** | (0x10A83E & 0x80)==0 && 0x10A840 > 0 && (0x10A788 & 0x02)==0 | u8s | dammit fighting-OSD `show_OSD` for kof97 | fighting-OSD.lua (Jesuszilla/mame-rr-scripts) |
| round state (simpler) | 0x10A83E | u8 | kof97_rl, live: **0 during the fight**, non-zero (32, then 233) after round end. Also 0 in menus, so combine it with other checks | kof97_rl. KOF97_Agent / MameToolkit call it `playing` |
| game phase (hitbox viewer) | 0x10B092 | u8 | > 0 = match active (the viewer also checks that 0x108100 is not a BIOS test pattern 0x5555/0xAAAA) | peon2 kof-hitboxes.lua. Single source |
| other "match only" checks | 0x10A786 != 0 && 0x10A83F == 0 | u8 | used by a "Hide Background" cheat as "activate only during match" | Strugglemeat/kof97 kof97.xml. Single source |
| P1 / P2 win counters | 0x10DAF5 / 0x10DAF7 (derived) | u8 | > 0 means a win was registered (Fightcade "P1 Win"/"P2 Win" at RAM offset 0xDAF4/0xDAF6) | Fightcade detector kof97.inf. The ^1 conversion is my derivation |

Note: kof97_rl says "0x10A852 is 0 in menus, 1 while a match is active". In the cheat files, 0x10A852 is P1's 2nd-fighter order slot. Don't use it as a match flag.

### KOF '97 character IDs (team-pick bytes and in-match byte 0x108171)

| ID | name | ID | name |
|---|---|---|---|
| 0x00 | Kyo Kusanagi | 0x10 | Mai Shiranui |
| 0x01 | Benimaru Nikaido | 0x11 | King |
| 0x02 | Goro Daimon | 0x12 | Kim Kaphwan |
| 0x03 | Terry Bogard | 0x13 | Chang Koehan |
| 0x04 | Andy Bogard | 0x14 | Choi Bounge |
| 0x05 | Joe Higashi | 0x15 | Yashiro Nanakase |
| 0x06 | Ryo Sakazaki | 0x16 | Shermie |
| 0x07 | Robert Garcia | 0x17 | Chris |
| 0x08 | Yuri Sakazaki | 0x18 | Ryuji Yamazaki |
| 0x09 | Leona | 0x19 | Blue Mary |
| 0x0A | Ralf Jones | 0x1A | Billy Kane |
| 0x0B | Clark Steel | 0x1B | Iori Yagami |
| 0x0C | Athena Asamiya | 0x1C | Orochi Iori (hidden) |
| 0x0D | Sie Kensou | 0x1D | Orochi Leona (hidden) |
| 0x0E | Chin Gentsai | 0x1E | Orochi (boss) |
| 0x0F | Chizuru Kagura | 0x1F | Shingo Yabuki |

Sources and cross-checks:
- **Pugsy MAME cheat list.** Copies of it: zengfr/romhack, furrtek, FBNeo-cheats kof97.ini.
- **Independent Chinese FBA cheat (CarlOS kof97.ini).** It writes 0x108171 with the same values for all 32 IDs. One label differs: 0x11 is shown as "京", probably a typo for King/金.
- **kof97_rl live check.** Kyo=0, Benimaru=1, Daimon=2, Terry=3, Kensou=13 (0x0D).
- **Alternate versions** ('94 Kyo, Orochi Yashiro/Shermie/Chris) are flags in 0x10A857/0x10A868, not separate IDs on the select screen. **Unverified:** whether 0x108171 changes for them in a match.

Practical rule for kof97: read 0x108171 / 0x108371 every frame while a round is active. The team bytes and order bytes give the full 3-member lineup.

---

## 3. The King of Fighters 2002 (`kof2002`)

| what | address | size | values / meaning | source(s) |
|---|---|---|---|---|
| **P1 current on-screen character** | **0x108171 (word 0x108170): INFERRED, unverified** | u8 | probably the same ID table as the team bytes below | No kof2002-specific source found. It is inferred from the shared struct: the same +0x18/+0x31/+0x139/+0xE8/+0x17A offsets hold for both games (peon2 training scripts, hitbox script, OSD script, and FBNeo "Quick Action PL1 = 0x10817A" in both kof97.ini and kof2002.ini). **Verify live before relying on it** |
| P2 current character | 0x108371: INFERRED | u8 | | as above |
| P1 "Character" A / B / C | 0x10A804 / 0x10A805 / 0x10A806 | u8 | ID table below. Bit 7 (0x80) = picked by roulette ("Select Roulette Status" cheat writes 0x80 to the same bytes), so mask with 0x7F | FBNeo-cheats kof2002.ini. CarlOS kof2002.ini is a Chinese translation of it, not independent. **Single source lineage** |
| P1 "Fighter" A / B / C | 0x10A7EE / 0x10A7EF / 0x10A7F0 | u8 | same ID table. The cheat file has a separate "Select Fighter" cheat; how it differs from "Character" is undocumented (possibly the post-order lineup) | FBNeo-cheats. Single source |
| P1 "Winquote character" A / B / C | 0x10A7E6 / 0x10A7E7 / 0x10A7E8 | u8 | ID table | FBNeo-cheats |
| P1 order number A / B / C | 0x10A7F8 / 0x10A7F9 / 0x10A7FA | u8 | cheat values 0x01..0x04 (labelled "0".."3") | FBNeo-cheats. Single source, meaning unclear |
| P1 team | 0x10A7E3 | u8 | 0 Japan, 1 Fatal Fury, 2 Korea, 3 Psycho Soldier, 4 Ikari, 5 Art of Fighting, 6 Women, 7 Iori, 8 '98 Special, 9 New Face (Orochi), 0x0A Hero (K'), 0x0B 2000 Special, 0x0C NESTS, 0x0D Rugal solo, 0xFF Custom | FBNeo-cheats |
| P1 team style | 0x10A7E4 | u8 | 0x40 solo, 0x00 team | FBNeo-cheats |
| P1 rounds lost | 0x10A7E5 | u8 | 0..2 | FBNeo-cheats |
| P1 mode | 0x108182 | u8 | 0 normal, 4 Hero mode | FBNeo-cheats |
| P2 equivalents | P1 + 0x26: team 0x10A809, style 0x10A80A, rounds lost 0x10A80B, winquote 0x10A80C-E, fighter 0x10A814-16, order 0x10A81E-20, character 0x10A82A-2C. Mode 0x108382 (P1 + 0x200) | u8 | | FBNeo-cheats |
| P1 / P2 X | 0x108118 / 0x108318 | u16 | world X | peon2 kof-hitboxes.lua (kof2002 profile: base 0x108100 + 0x18, stride 0x200). Not cross-checked with a second kof2002-specific source |
| P1 / P2 facing | 0x108131 / 0x108331 | u8 bit 0 | 0 = facing left | peon2 kof2002.lua (`p1direction`). Hitbox script (+0x31). 2 sources from the same author |
| health P1 / P2 | 0x108239 / 0x108439 | u8 | max 0x66 | peon2 kof2002.lua. FBNeo-cheats "Infinite Energy PL1" 0x108239=0x66. dammit OSD (+0x138 word) |
| round timer | 0x10A7D2 | u8 BCD | 0x60 | peon2 kof2002.lua (`infiniteTime`). FBNeo-cheats "Infinite Battle Time". Fightcade detector kof2002.inf (offset 0xA7D3 = 96 at round start) |
| **round in progress** | (0x10A7D6 & 0x80)==0 && 0x10A7D8 > 0 && (0x10A788 & 0x02)==0 | u8s | dammit fighting-OSD `show_OSD` for kof2002 (same pattern as kof97, shifted -0x68) | fighting-OSD.lua. Single source |
| game phase (hitbox viewer) | 0x10B056 | u8 | > 0 = match active | peon2 kof-hitboxes.lua (kof2001/kof2002 profile) |
| P1 / P2 win counters | 0x10DAB7 / 0x10DAB9 (derived) | u8 | Fightcade "P1 Win"/"P2 Win" at RAM offset 0xDAB6/0xDAB8 | Fightcade detector kof2002.inf |

### KOF 2002 character IDs (from FBNeo-cheats kof2002.ini "Select Character/Fighter")

| ID | name | ID | name | ID | name |
|---|---|---|---|---|---|
| 0x00 | Kyo Kusanagi | 0x0E | Clark | 0x1C | Shermie |
| 0x01 | Benimaru | 0x0F | Ryo | 0x1D | Chris |
| 0x02 | Daimon | 0x10 | Robert | 0x1E | K' |
| 0x03 | Terry | 0x11 | Takuma | 0x1F | Maxima |
| 0x04 | Andy | 0x12 | Mai | 0x20 | Whip |
| 0x05 | Joe | 0x13 | Yuri | 0x21 | Vanessa |
| 0x06 | Kim | 0x14 | May Lee | 0x22 | Seth |
| 0x07 | Chang | 0x15 | Iori | 0x23 | Ramon |
| 0x08 | Choi | 0x16 | Mature | 0x24 | Kula |
| 0x09 | Athena | 0x17 | Vice | 0x25 | K9999 |
| 0x0A | Kensou | 0x18 | Yamazaki | 0x26 | Angel |
| 0x0B | Chin | 0x19 | Mary | 0x27 | Rugal (Omega) |
| 0x0C | Leona | 0x1A | Billy | 0x28 | Kusanagi (Kyo clone) |
| 0x0D | Ralf | 0x1B | Yashiro | | |

This table comes from a single source lineage (FBNeo-cheats; CarlOS is a translated copy). It is **not cross-checked** against in-match RAM. Note that the IDs differ from kof97 (e.g. Kensou is 0x0A here, 0x0D in '97).

Suggested live check: start a kof2002 match and compare 0x108171 with the team bytes (0x10A804-06 & 0x7F, or 0x10A7EE-F0). Then switch to the next team member and check that 0x108171 changes to match.

---

## 4. KOF 2005 hacks: kf2k5uni, kof10th, kf10thep

FBNeo d_neogeo.cpp lists all three as **clones of `kof2002`** (parent field = "kof2002"; kf2k5uni = "10th Anniversary 2005 Unique (bootleg of KOF 2002)").

**Evidence that the kof2002 RAM layout carries over:**
- peon2/fbneo-training-mode has **games/kf2k5uni/kf2k5uni.lua** with the same addresses as kof2002:
  - health 0x108239/0x108439, but max 0x67 (kof2002: 0x66)
  - meter/stocks 0x1082E3/0x1084E3, max stocks 5
  - direction 0x108131/0x108331
  - combo counters 0x1084B0/0x1082B0
  - timer 0x10A7D2
- Its hitbox viewer selects the profile by `emu.parentname()`, so kf2k5uni, kof10th and kf10thep all get the kof2002 profile: base 0x108100, stride 0x200, X +0x18, facing +0x31, phase 0x10B056.
- The Fightcade detector **kf2k5uni.inf is byte-for-byte identical to kof2002.inf** (timer 0xA7D3 = 96 at round start, win counters 0xDAB6/0xDAB8).
- FBNeo-cheats has kf2k5uni.ini, kof10th.ini and kf10thep.ini. They use exactly the kof2002 addresses for team (0x10A7E3), characters (0x10A804-06), fighters (0x10A7EE-F0), order (0x10A7F8-FA), P2 (+0x26), and timer 0x10A7D2. The only difference is that "Infinite Time" writes 0x99.

Conclusion: addresses and struct layout very likely carry over (medium-high confidence for health, timer, position, facing and the team-select block). In-match character byte 0x108171 is as unverified as for kof2002.

**Character ID tables for the hacks: UNVERIFIED.**
- The hack cheat files (FBNeo-cheats and CarlOS) contain the **exact same 41-entry kof2002 list (0x00-0x28)**, copied verbatim. They say nothing about the extra or swapped characters the hacks add.
- I found no hack-specific roster/ID source: no Lua script, cheat, wiki or RetroAchievements note.
- Expect IDs 0x00-0x28 to mostly keep their kof2002 meaning, since the hacks reuse the program. But the hacks replace or add characters (kf2k5uni reportedly has about 66 selectable), so IDs above 0x28, and any slot whose character was swapped, need to be mapped empirically. One way: log 0x10A804-06 (& 0x7F) and 0x108171 while picking each character on the select screen.
- There is no training-mode script, Fightcade detector or hitbox profile entry specifically for kof10th or kf10thep (only via parentname).

---

## 5. Source URLs

- peon2/fbneo-training-mode (cloned; layout: `games/<set>/<set>.lua`, `hitboxes/kof-hitboxes.lua`):
  - https://raw.githubusercontent.com/peon2/fbneo-training-mode/master/games/kof97/kof97.lua
  - https://raw.githubusercontent.com/peon2/fbneo-training-mode/master/games/kof2002/kof2002.lua
  - https://raw.githubusercontent.com/peon2/fbneo-training-mode/master/games/kf2k5uni/kf2k5uni.lua
  - https://raw.githubusercontent.com/peon2/fbneo-training-mode/master/hitboxes/kof-hitboxes.lua
- dammit fighting-OSD.lua: https://raw.githubusercontent.com/Jesuszilla/mame-rr-scripts/master/fighting-OSD.lua
- xeno0/kof97_rl spec (live-verified kof97 map incl. character byte): https://github.com/xeno0/kof97_rl (kof97rl/envs/mame/specs/kof97.yaml)
- 568xiaoma/KOF97_Agent (king_of_fighter/address.py), JoHnKiking/MameToolkit_kof97 (Kof97Environment.py)
- zengfr/romhack kof97 reverse-engineering notes ("kof97-hackdata绝密HACK资料2-by-zengfr整理.txt.md") and neogeo/kof97.xml (Pugsy cheats)
- Strugglemeat/kof97 kof97.xml (cheats with match-state conditions)
- FBNeo cheats: https://github.com/finalburnneo/FBNeo-cheats (cheats/kof97.ini, kof2002.ini, kf2k5uni.ini, kof10th.ini, kf10thep.ini, kof94.ini)
- CarlosPix/CarlOS FBA2012CC cheats (kof97.ini "战斗中换人" = 0x108171/0x108371): https://raw.githubusercontent.com/CarlosPix/CarlOS/master/RetroArch32/.config/retroarch/system/FBA2012CC/cheats/kof97.ini
- mame2003-plus cheat.dat (kof97 Pugsy entries): https://raw.githubusercontent.com/libretro/mame2003-plus-libretro/master/metadata/cheat.dat
- Fightcade detectors: https://github.com/fightcadeorg/fightcade-detectors (kof97.inf, kof2002.inf, kf2k5uni.inf)
- FBNeo driver list / 68K byte access: https://raw.githubusercontent.com/finalburnneo/FBNeo/master/src/burn/drv/neogeo/d_neogeo.cpp , .../src/cpu/m68000_intf.cpp
