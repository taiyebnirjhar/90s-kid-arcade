// The games this app supports, and how each one is laid out on screen.
//
// sets:    FBNeo ROM set names accepted for the game (the zip file name).
//          The first is the one a differently named zip is stored as.
// layout:  "fighter" - stick, A B C D, power button, character move ring
//          "shooter" - stick and three big labelled buttons (+ rapid fire)
//          "brawler" - stick and four labelled buttons
// labels:  what each button does, shown on the button.
// power:   the one-tap power button (KOF MAX activation).
// system:  extra one-tap buttons shown next to A B C D.
// roster:  which character list the game uses (js/rosters/<roster>.js).
// team:    how many fighter slots the team bar shows.

const KOF_SYSTEM = [
  { name: "Roll", cmd: "AB" },
  { name: "Blow­back", cmd: "CD" },
];

export const GAMES = [
  {
    id: "kof97",
    series: "The King of Fighters",
    title: "KOF '97",
    sets: ["kof97"],
    layout: "fighter",
    power: { name: "MAX", cmd: "ABC" },
    system: KOF_SYSTEM,
    roster: "kof97",
    team: 3,
  },
  {
    id: "kof2002",
    series: "The King of Fighters",
    title: "KOF 2002",
    sets: ["kof2002"],
    layout: "fighter",
    power: { name: "MAX", cmd: "BC" },
    system: KOF_SYSTEM,
    roster: "kof2002",
    team: 3,
  },
  {
    // There is no official Neo Geo KOF 2005: "KOF 2005" is the 10th
    // Anniversary hacks of KOF 2002, which play like 2002.
    id: "kof2005",
    series: "The King of Fighters",
    title: "KOF 2005",
    subtitle: "10th Anniversary",
    sets: ["kf2k5uni", "kof10th", "kf10thep"],
    layout: "fighter",
    power: { name: "MAX", cmd: "BC" },
    system: KOF_SYSTEM,
    roster: "kof2002",
    team: 3,
  },
  ...[
    ["mslug", "Metal Slug"],
    ["mslug2", "Metal Slug 2"],
    ["mslugx", "Metal Slug X"],
    ["mslug3", "Metal Slug 3"],
    ["mslug4", "Metal Slug 4"],
    ["mslug5", "Metal Slug 5"],
  ].map(([set, title]) => ({
    id: set,
    series: "Metal Slug",
    title,
    sets: [set],
    layout: "shooter",
    // 4 and 5 add D: the "Metal Slug Attack" while in a vehicle.
    labels: { A: "Shoot", B: "Jump", C: "Bomb", ...(/mslug[45]/.test(set) ? { D: "Slug Attack" } : {}) },
    rapid: "A",
    // Metal Slug 5's slide: down + jump.
    system: set === "mslug5" ? [{ name: "Slide", cmd: "2+B" }] : [],
  })),
  {
    id: "sengoku3",
    series: "Sengoku",
    title: "Sengoku 3",
    sets: ["sengoku3"],
    layout: "brawler",
    labels: { A: "Weapon", B: "Punch", C: "Jump", D: "Throw" },
    // Inputs every Sengoku 3 character shares.
    moves: [
      { name: "Special 1", cmd: "22+A" },
      { name: "Special 2", cmd: "22+B" },
      { name: "Super", cmd: "28+AB", type: "dm" },
    ],
  },
];

export const BIOS = "neogeo.zip";

export function gameById(id) {
  return GAMES.find((g) => g.id === id);
}

// Which game a ROM zip belongs to, by file name ("kof97.zip" -> KOF '97).
export function gameForFile(fileName) {
  const set = setName(fileName);
  return GAMES.find((g) => g.sets.includes(set));
}

export function setName(fileName) {
  return String(fileName).toLowerCase().replace(/\.zip$/, "");
}
