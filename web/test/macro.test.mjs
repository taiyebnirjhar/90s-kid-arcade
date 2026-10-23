import test from "node:test";
import assert from "node:assert/strict";
import { parse, compile, toArrows, MacroPlayer } from "../js/macro.js";
import { angleToDir, arcPositions } from "../js/controls.js";
import { GAMES, gameForFile } from "../js/games.js";
import { readFileSync, readdirSync } from "node:fs";

const dirs = (frames) => frames.map((f) => f.dir);

test("parse reads directions, charge and buttons", () => {
  assert.deepEqual(parse("2363214+A"), {
    parts: [2, 3, 6, 3, 2, 1, 4].map((dir) => ({ dir, charge: false })),
    buttons: "A",
  });
  assert.deepEqual(parse("[4]6 + ca"), {
    parts: [{ dir: 4, charge: true }, { dir: 6, charge: false }],
    buttons: "AC",
  });
  assert.equal(parse("236A").buttons, "A");
  assert.equal(parse("BC").parts.length, 0);
});

test("parse rejects nonsense", () => {
  assert.throws(() => parse(""), /Empty/);
  assert.throws(() => parse("236+X"), /Unknown input/);
  assert.throws(() => parse("[46]A"), /Charge/);
  assert.throws(() => parse("0"), /Unknown input/);
});

test("compile holds each direction, then the buttons, then releases", () => {
  const f = compile("236+A", { timing: { step: 2, press: 3 } });
  assert.deepEqual(dirs(f), [2, 2, 3, 3, 6, 6, 6, 6, 6, 5]);
  assert.deepEqual(f.map((x) => x.buttons), ["", "", "", "", "", "", "A", "A", "A", ""]);
});

test("compile mirrors when facing left", () => {
  const f = compile("2363214+A", { facingLeft: true, timing: { step: 1, press: 1 } });
  assert.deepEqual(dirs(f), [2, 1, 4, 1, 2, 3, 6, 6, 5]);
});

test("charge holds for the charge time", () => {
  const f = compile("[2]8+A", { timing: { step: 2, charge: 10, press: 1 } });
  assert.equal(f.filter((x) => x.dir === 2).length, 10);
});

test("button-only commands press together", () => {
  const f = compile("CD", { timing: { press: 2 } });
  assert.deepEqual(f, [{ dir: 5, buttons: "CD" }, { dir: 5, buttons: "CD" }, { dir: 5, buttons: "" }]);
});

test("player advances one frame per new clock tick and never skips", () => {
  const p = new MacroPlayer(compile("236+A", { timing: { step: 1, press: 1 } }));
  assert.equal(p.tick(10).dir, 2);
  assert.equal(p.tick(10).dir, 2); // same emulated frame: no advance
  assert.equal(p.tick(15).dir, 3); // clock jumped 5 frames: still only one step
  assert.equal(p.tick(16).dir, 6);
  assert.equal(p.tick(17).buttons, "A");
  assert.equal(p.tick(18).dir, 5);
  assert.equal(p.tick(19), null);
  assert.ok(p.done);
});

test("arrows label", () => {
  assert.equal(toArrows("2141236+A"), "↓↙←↙↓↘→ A");
  assert.equal(toArrows("[4]6+B"), "[←]→ B");
});

test("stick angle maps to numpad directions", () => {
  assert.equal(angleToDir(0, 0), 5);
  assert.equal(angleToDir(40, 0), 6);
  assert.equal(angleToDir(-40, 0), 4);
  assert.equal(angleToDir(0, -40), 8); // screen y grows downward
  assert.equal(angleToDir(0, 40), 2);
  assert.equal(angleToDir(30, 30), 3);
  assert.equal(angleToDir(-30, 30), 1);
  assert.equal(angleToDir(-30, -30), 7);
  assert.equal(angleToDir(30, -30), 9);
});

const ROSTERS = readdirSync(new URL("../data/rosters/", import.meta.url)).map((f) =>
  JSON.parse(readFileSync(new URL(`../data/rosters/${f}`, import.meta.url))),
);
const ALL_MOVES = [
  ...GAMES.flatMap((g) => [...(g.moves || []), ...(g.system || []), ...(g.power ? [g.power] : [])]),
  ...ROSTERS.flatMap((r) => r.characters.flatMap((c) => c.moves)),
];

test("every built-in move parses", () => {
  assert.ok(ALL_MOVES.length > 0);
  for (const m of ALL_MOVES) assert.doesNotThrow(() => parse(m.cmd), m.name);
});

test("games are found by ROM file name", () => {
  assert.equal(gameForFile("KOF97.zip").id, "kof97");
  assert.equal(gameForFile("kf2k5uni.zip").id, "kof2005");
  assert.equal(gameForFile("mslugx.zip").power, undefined);
  assert.equal(gameForFile("kof2002.zip").power.cmd, "BC");
  assert.equal(gameForFile("kof97.zip").power.cmd, "ABC");
  assert.equal(gameForFile("random.zip"), undefined);
});

test("arc positions start at `from` and step round counter-clockwise", () => {
  assert.deepEqual(arcPositions(3, { radius: 100, from: 90, step: 90 }), [
    { x: 0, y: -100 }, // straight up
    { x: -100, y: 0 }, // left
    { x: 0, y: 100 }, // down
  ]);
  assert.deepEqual(arcPositions(0, { radius: 100, from: 0, step: 10 }), []);
});

test("motion icons trace the stick path", async () => {
  const { motionPoints, motionPath } = await import("../js/glyph.js");
  // QCF starts straight down and ends straight forward (right).
  const qcf = motionPoints("236+A");
  assert.deepEqual(qcf[0], { x: 24, y: 41 });
  assert.deepEqual(qcf.at(-1), { x: 41, y: 24 });
  // A double motion spirals inwards, so it ends closer to the centre.
  const end = motionPoints("236236+A").at(-1);
  assert.ok(end.x < 41 && end.y === 24);
  // Charge back, forward cuts straight across.
  assert.equal(motionPoints("[4]6+A").length, 2);
  // Button-only commands have no drawing.
  assert.deepEqual(motionPath("CD"), { line: "", head: "", start: null });
  for (const m of ALL_MOVES) if (/^\[?\d/.test(m.cmd)) assert.ok(motionPath(m.cmd).line, m.name);
});
