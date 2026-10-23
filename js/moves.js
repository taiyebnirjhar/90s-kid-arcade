// Games, system moves and starter move lists.
//
// Commands use the numpad notation from macro.js, written facing right.
// Character lists are starters you can edit in the app: double-check a move
// against the in-game command list if it doesn't come out.

// FBNeo set names (the ROM zip file name) for the games this app is tuned for.
export const GAMES = {
  kof94: "The King of Fighters '94",
  kof95: "The King of Fighters '95",
  kof96: "The King of Fighters '96",
  kof97: "The King of Fighters '97",
  kof98: "The King of Fighters '98",
  kof99: "The King of Fighters '99",
  kof2000: "The King of Fighters 2000",
  kof2001: "The King of Fighters 2001",
  kof2002: "The King of Fighters 2002",
  kf2k2mp: "KOF 2002 Magic Plus",
  kf2k2mp2: "KOF 2002 Magic Plus II",
  kof2003: "The King of Fighters 2003",
  kf10thep: "KOF 10th Anniversary Extra Plus",
  kf2k5uni: "KOF 10th Anniversary 2005 Unique",
  kof10th: "KOF 10th Anniversary",
};

export function gameTitle(fileName) {
  const set = String(fileName).toLowerCase().replace(/\.zip$/, "");
  return GAMES[set] || set;
}

// One-tap system moves shown as small buttons next to A B C D.
export function systemMoves(fileName) {
  const set = String(fileName).toLowerCase();
  const moves = [
    { name: "Roll", cmd: "AB" },
    { name: "Blow\u00adback", cmd: "CD" },
  ];
  if (/kof9[4-6]/.test(set)) {
    moves[0] = { name: "Dodge", cmd: "AB" };
    moves.push({ name: "Power", cmd: "ABC" }); // charge the power gauge
  } else if (/kof9[78]/.test(set)) {
    moves.push({ name: "MAX", cmd: "ABC" }); // Advanced-mode power up
  } else if (/kof2002|kf2k2|kof10th|kf10th|kf2k5/.test(set)) {
    moves.push({ name: "MAX", cmd: "BC" }); // 2002-style MAX mode
  } else {
    moves.push({ name: "BC", cmd: "BC" });
  }
  return moves;
}

// Generic motions for when you don't want to pick a character.
export const GENERIC = [
  { name: "QCF+A", cmd: "236+A" },
  { name: "DP+C", cmd: "623+C" },
  { name: "QCB+B", cmd: "214+B" },
  { name: "QCFx2+A", cmd: "236236+A" },
  { name: "HCB+P", cmd: "63214+A" },
  { name: "QCB HCF+A", cmd: "2141236+A" },
];

// P = A or C (light/heavy punch), K = B or D. Swap A for C (or B for D) in the
// editor to get the other strength.
export const CHARACTERS = {
  "Kyo Kusanagi": [
    { name: "Yami Barai", cmd: "236+A" },
    { name: "Oniyaki", cmd: "623+C" },
    { name: "Orochinagi", cmd: "2141236+A" },
  ],
  "Iori Yagami": [
    { name: "Yami Barai", cmd: "236+A" },
    { name: "Oniyaki", cmd: "623+C" },
    { name: "Aoi Hana", cmd: "214+A" },
    { name: "Ya Otome", cmd: "2363214+A" },
  ],
  "Terry Bogard": [
    { name: "Power Wave", cmd: "236+A" },
    { name: "Burn Knuckle", cmd: "214+A" },
    { name: "Rising Tackle", cmd: "[2]8+A" },
    { name: "Buster Wolf", cmd: "236236+A" },
  ],
  "Ryo Sakazaki": [
    { name: "Ko-ou Ken", cmd: "236+A" },
    { name: "Kohou", cmd: "623+C" },
    { name: "Ryuuko Ranbu", cmd: "2363214+A" },
    { name: "Haoh Shoukou Ken", cmd: "641236+A" },
  ],
  "Robert Garcia": [
    { name: "Ryuu Geki Ken", cmd: "236+A" },
    { name: "Ryuu Ga", cmd: "623+C" },
    { name: "Ryuuko Ranbu", cmd: "2363214+A" },
    { name: "Haoh Shoukou Ken", cmd: "641236+A" },
  ],
  "Mai Shiranui": [
    { name: "Kachousen", cmd: "236+A" },
    { name: "Ryuuenbu", cmd: "214+A" },
    { name: "Shinobi Bachi", cmd: "41236+B" },
    { name: "Chou Hissatsu Shinobi Bachi", cmd: "2141236+B" },
  ],
  "K'": [
    { name: "Ein Trigger", cmd: "236+A" },
    { name: "Crow Bites", cmd: "623+C" },
    { name: "Heat Drive", cmd: "2363214+A" },
    { name: "Chain Drive", cmd: "236236+B" },
  ],
};
