// Motion-input engine.
//
// Commands are written in numpad notation, the standard for fighting games,
// always as if your character faces RIGHT:
//
//   7 8 9      4 = back, 6 = forward, 2 = down, 8 = up, 5 = neutral
//   4 5 6      236     = quarter circle forward  (QCF)
//   1 2 3      2141236 = QCB then HCF            (Kyo's Orochinagi)
//
// After the directions come the buttons to press together: "236236+AC".
// "[4]6+A" is a charge motion: hold back, then forward + A.
//
// compile() turns a command into a per-frame timeline the input layer plays
// back one emulated frame at a time, mirrored when your fighter faces left.

export const BUTTONS = ["A", "B", "C", "D"];

const MIRROR = { 1: 3, 3: 1, 4: 6, 6: 4, 7: 9, 9: 7, 2: 2, 5: 5, 8: 8 };

export const DEFAULT_TIMING = {
  step: 2,    // frames each direction is held
  charge: 50, // frames a [charge] direction is held
  press: 4,   // frames the buttons are held
};

// Parse "2141236+AC" into { parts: [{dir, charge}], buttons: "AC" }.
// Throws an Error with a human-readable message on bad input.
export function parse(command) {
  const src = String(command || "").toUpperCase().replace(/[\s,~>]/g, "");
  if (!src) throw new Error("Empty command");
  const parts = [];
  let i = 0;
  while (i < src.length) {
    const ch = src[i];
    if (ch === "[") {
      const m = /^\[([1-9])\]/.exec(src.slice(i));
      if (!m) throw new Error("Charge must look like [4] or [2]");
      parts.push({ dir: Number(m[1]), charge: true });
      i += m[0].length;
    } else if (/[1-9]/.test(ch)) {
      parts.push({ dir: Number(ch), charge: false });
      i++;
    } else {
      break;
    }
  }
  let rest = src.slice(i);
  if (rest.startsWith("+")) rest = rest.slice(1);
  if (!/^[ABCD]*$/.test(rest)) {
    throw new Error(`Unknown input "${rest}". Use directions 1-9 and buttons A B C D`);
  }
  const buttons = BUTTONS.filter((b) => rest.includes(b)).join("");
  if (!parts.length && !buttons) throw new Error("Command has no inputs");
  return { parts, buttons };
}

export function mirrorDir(dir) {
  return MIRROR[dir] ?? 5;
}

// Returns an array of frames, each { dir, buttons } where buttons is a string
// such as "AC". The last frame is always neutral so held keys get released.
export function compile(command, { facingLeft = false, timing = {} } = {}) {
  const t = { ...DEFAULT_TIMING, ...timing };
  const { parts, buttons } = parse(command);
  const map = (d) => (facingLeft ? mirrorDir(d) : d);
  const frames = [];
  for (const { dir, charge } of parts) {
    const n = charge ? t.charge : t.step;
    for (let k = 0; k < n; k++) frames.push({ dir: map(dir), buttons: "" });
  }
  if (buttons) {
    // Keep holding the final direction while the buttons go down: the games
    // read the button on the frame the motion completes.
    const last = parts.length ? map(parts[parts.length - 1].dir) : 5;
    for (let k = 0; k < t.press; k++) frames.push({ dir: last, buttons });
  }
  frames.push({ dir: 5, buttons: "" });
  return frames;
}

// Short readable form for button labels: "2141236+AC" -> "↓↙←↙↓↘→ AC".
const ARROWS = { 1: "↙", 2: "↓", 3: "↘", 4: "←", 5: "•", 6: "→", 7: "↖", 8: "↑", 9: "↗" };
export function toArrows(command) {
  try {
    const { parts, buttons } = parse(command);
    const dirs = parts.map((p) => (p.charge ? `[${ARROWS[p.dir]}]` : ARROWS[p.dir])).join("");
    return [dirs, buttons].filter(Boolean).join(" ");
  } catch {
    return command;
  }
}

export function dirFlags(dir) {
  return {
    up: dir === 7 || dir === 8 || dir === 9,
    down: dir === 1 || dir === 2 || dir === 3,
    left: dir === 1 || dir === 4 || dir === 7,
    right: dir === 3 || dir === 6 || dir === 9,
  };
}

// Plays a compiled timeline against a frame clock. tick(frame) returns the
// frame state to apply, or null once finished. A frame is never skipped even
// if the clock jumps, so a stutter can't eat a direction out of the motion.
export class MacroPlayer {
  constructor(frames) {
    this.frames = frames;
    this.index = -1;
    this.lastClock = null;
  }
  tick(clock) {
    if (this.lastClock === null || clock > this.lastClock) {
      this.index++;
      this.lastClock = clock;
    }
    return this.index < this.frames.length ? this.frames[this.index] : null;
  }
  get done() {
    return this.index >= this.frames.length;
  }
}
