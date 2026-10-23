// Draws a move's stick motion as an icon: the path the stick travels, as a
// curve around a circle that tightens as it goes (so 236236 is a visible
// double spiral, not 236 drawn twice), with an arrowhead at the end.
// Coordinates fit a 48x48 viewBox; y points down like the screen.

import { parse } from "./macro.js";

const C = 24; // centre
const R = 17; // outer radius

// Numpad direction to an angle in radians (screen coordinates, y down).
const ANGLE = { 6: 0, 3: 45, 2: 90, 1: 135, 4: 180, 7: 225, 8: 270, 9: 315 };

const round = (n) => Math.round(n * 10) / 10;

// Points along the motion. Each step turns the short way round the circle;
// a 180° turn (charge back, then forward) cuts straight through the middle.
export function motionPoints(command) {
  const { parts } = parse(command);
  const dirs = parts.map((p) => p.dir).filter((d) => d !== 5);
  if (!dirs.length) return [];
  const n = dirs.length;
  const radius = (i) => R * (n > 3 ? 1 - (0.42 * i) / (n - 1) : 1);
  const at = (deg, r) => ({ x: round(C + Math.cos((deg * Math.PI) / 180) * r), y: round(C + Math.sin((deg * Math.PI) / 180) * r) });

  if (n === 1) {
    // A single direction (or charge): an arrow from the centre outwards.
    const a = ANGLE[dirs[0]];
    return [at(a + 180, R * 0.55), at(a, R)];
  }
  const pts = [at(ANGLE[dirs[0]], radius(0))];
  for (let i = 1; i < n; i++) {
    const a0 = ANGLE[dirs[i - 1]];
    let delta = ((ANGLE[dirs[i]] - a0 + 540) % 360) - 180;
    const r0 = radius(i - 1);
    const r1 = radius(i);
    if (Math.abs(delta) === 180) {
      pts.push(at(ANGLE[dirs[i]], r1));
      continue;
    }
    const steps = Math.max(1, Math.round(Math.abs(delta) / 15));
    for (let k = 1; k <= steps; k++) {
      const t = k / steps;
      pts.push(at(a0 + delta * t, r0 + (r1 - r0) * t));
    }
  }
  return pts;
}

// Centre the drawing and scale it to fill the icon, so a quarter circle
// isn't stuck small in one corner. Keeps proportions.
function fit(pts, pad = 9) {
  const xs = pts.map((p) => p.x);
  const ys = pts.map((p) => p.y);
  const [x0, x1, y0, y1] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)];
  const k = (48 - 2 * pad) / Math.max(x1 - x0, y1 - y0, 12);
  const ox = 24 - ((x0 + x1) / 2) * k;
  const oy = 24 - ((y0 + y1) / 2) * k;
  return pts.map((p) => ({ x: round(p.x * k + ox), y: round(p.y * k + oy) }));
}

// SVG path data for the motion line and its arrowhead, fitted to the icon.
export function motionPath(command) {
  const raw = motionPoints(command);
  if (raw.length < 2) return { line: "", head: "", start: null };
  const pts = fit(raw);
  const line = "M" + pts.map((p) => `${p.x} ${p.y}`).join("L");
  const a = pts[pts.length - 2];
  const b = pts[pts.length - 1];
  const ang = Math.atan2(b.y - a.y, b.x - a.x);
  const s = 6;
  const wing = (off) => `${round(b.x - Math.cos(ang + off) * s)} ${round(b.y - Math.sin(ang + off) * s)}`;
  const head = `M${wing(0.55)}L${b.x} ${b.y}L${wing(-0.55)}`;
  return { line, head, start: pts[0] };
}

const SVG = "http://www.w3.org/2000/svg";

// <svg> icon for a command. Charge motions get a dot where the stick waits.
export function motionIcon(command) {
  const svg = document.createElementNS(SVG, "svg");
  svg.setAttribute("viewBox", "0 0 48 48");
  svg.setAttribute("aria-hidden", "true");
  svg.classList.add("glyph");
  const { line, head, start } = motionPath(command);
  for (const d of [line, head]) {
    if (!d) continue;
    const p = document.createElementNS(SVG, "path");
    p.setAttribute("d", d);
    svg.append(p);
  }
  const { parts } = parse(command);
  if (parts[0]?.charge && start) {
    const dot = document.createElementNS(SVG, "circle");
    dot.setAttribute("cx", start.x);
    dot.setAttribute("cy", start.y);
    dot.setAttribute("r", 3.4);
    dot.classList.add("hold");
    svg.append(dot);
  }
  return svg;
}
