import test from "node:test";
import assert from "node:assert/strict";

// Pad runs macros on requestAnimationFrame; drive it by hand.
let rafQueue = [];
globalThis.requestAnimationFrame = (fn) => rafQueue.push(fn);
globalThis.cancelAnimationFrame = () => {};
const { Pad, RETROPAD } = await import("../js/input.js");

function setup() {
  rafQueue = [];
  const log = [];
  const state = {};
  let frame = 0;
  const pad = new Pad({
    send: (i, v) => {
      log.push([i, v]);
      state[i] = v;
    },
    clock: () => frame,
  });
  const runFrames = (n) => {
    for (let k = 0; k < n; k++) {
      frame++;
      const q = rafQueue;
      rafQueue = [];
      q.forEach((fn) => fn());
    }
  };
  return { pad, log, state, runFrames };
}

test("only changes are sent", () => {
  const { pad, log } = setup();
  pad.setDir(6);
  pad.setDir(6);
  pad.press("A", true);
  assert.deepEqual(log, [[RETROPAD.right, 1], [RETROPAD.A, 1]]);
});

test("macro plays QCF+A then hands control back to the finger", () => {
  const { pad, log, state, runFrames } = setup();
  pad.timing = { step: 1, press: 1 };
  pad.setDir(4); // finger holding back
  log.length = 0;
  pad.play("236+A");
  runFrames(10);
  const seq = log.map(([i, v]) => `${Object.keys(RETROPAD).find((k) => RETROPAD[k] === i)}${v}`);
  assert.deepEqual(seq, [
    "down1", "left0", // frame 1: 2
    "right1", // 3
    "down0", // 6
    "A1", // 6 + A
    "right0", "A0", // neutral
    "left1", // finger still holds back
  ]);
  assert.equal(state[RETROPAD.left], 1);
});

test("stick moves during a macro do not interrupt it", () => {
  const { pad, state, runFrames } = setup();
  pad.timing = { step: 3, press: 1 };
  pad.play("2+A");
  runFrames(1);
  pad.setDir(8);
  assert.equal(state[RETROPAD.down], 1);
  assert.ok(!state[RETROPAD.up]);
  runFrames(10);
  assert.equal(state[RETROPAD.up], 1);
});

test("facing left mirrors macros", () => {
  const { pad, state, runFrames } = setup();
  pad.timing = { step: 2, press: 1 };
  pad.facingLeft = true;
  pad.play("6+A");
  runFrames(1);
  assert.equal(state[RETROPAD.left], 1);
});
