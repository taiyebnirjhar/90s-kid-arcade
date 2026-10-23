// Merges touch input and running macros into one controller state and sends
// only the changes to the emulator.

import { compile, dirFlags, MacroPlayer } from "./macro.js";

// RetroPad indices used by EmulatorJS's simulateInput(). FBNeo's default
// ("classic") Neo Geo layout puts A on RetroPad B, B on A, C on Y and D on X.
export const RETROPAD = {
  A: 0, B: 8, C: 1, D: 9,
  coin: 2, start: 3,
  up: 4, down: 5, left: 6, right: 7,
};

export class Pad {
  // send(index, value) forwards one change to the emulator.
  // clock() returns the emulated frame counter (or any increasing number).
  constructor({ send, clock, onMacroEnd }) {
    this.send = send;
    this.clock = clock;
    this.onMacroEnd = onMacroEnd || (() => {});
    this.dir = 5;
    this.held = new Set(); // buttons/keys held by fingers: "A".."D", "coin", "start"
    this.facingLeft = false;
    this.timing = {};
    this.macro = null;
    this.sent = {};
    this.queue = [];
    this.raf = 0;
  }

  setDir(dir) {
    this.dir = dir;
    this.flush();
  }

  press(key, down) {
    if (down) this.held.add(key);
    else this.held.delete(key);
    this.flush();
  }

  // Play a command. A tap while another macro runs is queued (once), so
  // mashing a super button doesn't garble the motion. Returns the number of
  // frames the command takes.
  play(command) {
    const frames = compile(command, { facingLeft: this.facingLeft, timing: this.timing });
    if (this.macro) {
      this.queue = [frames];
    } else {
      this.start(frames);
    }
    return frames.length;
  }

  start(frames) {
    this.macro = new MacroPlayer(frames);
    this.current = null;
    this.loop();
  }

  loop() {
    cancelAnimationFrame(this.raf);
    const step = () => {
      const frame = this.macro && this.macro.tick(this.clock());
      if (frame) {
        this.current = frame;
        this.flush();
        this.raf = requestAnimationFrame(step);
        return;
      }
      this.macro = null;
      const next = this.queue.shift();
      if (next) {
        this.start(next);
        return;
      }
      this.flush();
      this.onMacroEnd();
    };
    step();
  }

  cancel() {
    cancelAnimationFrame(this.raf);
    this.macro = null;
    this.queue = [];
    this.flush();
  }

  // While a macro runs it owns the stick and A-D; coin/start stay live.
  flush() {
    const frame = this.macro ? this.current : null;
    const state = {};
    const dir = frame ? frame.dir : this.dir;
    Object.assign(state, dirFlags(dir));
    for (const b of ["A", "B", "C", "D"]) {
      state[b] = frame ? frame.buttons.includes(b) : this.held.has(b);
    }
    state.coin = this.held.has("coin");
    state.start = this.held.has("start");
    for (const key in state) {
      const value = state[key] ? 1 : 0;
      if ((this.sent[key] || 0) !== value) {
        this.sent[key] = value;
        this.send(RETROPAD[key], value);
      }
    }
  }

  releaseAll() {
    this.held.clear();
    this.dir = 5;
    this.cancel();
  }
}
