// Touch overlay: an 8-way stick on the left, A B C D with one-tap move
// buttons arranged around them on the right. Every finger is tracked on its own so you can
// hold a direction and press buttons, or slide a finger across buttons.

const DEADZONE = 14; // px from the stick centre before a direction counts

// Angle (0 = right, counter-clockwise) to numpad direction, 45° sectors.
export function angleToDir(dx, dy) {
  if (Math.hypot(dx, dy) < DEADZONE) return 5;
  const a = (Math.atan2(-dy, dx) * 180) / Math.PI;
  const sector = Math.round(((a + 360) % 360) / 45) % 8;
  return [6, 9, 8, 7, 4, 1, 2, 3][sector];
}

function vibrate(ms) {
  try {
    if (navigator.vibrate) navigator.vibrate(ms);
  } catch {}
}

// Stick modes (settings):
//   floating - appears under the thumb, anywhere in the left zone
//   fixed    - always drawn at its home spot; a touch near it uses that
//              centre, a touch further away pulls it under the thumb
//   hidden   - floating, but nothing is drawn
// In every mode the centre follows a thumb that drifts too far, so a long
// round of play never leaves you pushing against the edge.
export function mountStick(zone, pad, { haptics, mode }) {
  const base = zone.querySelector(".stick-base");
  const knob = zone.querySelector(".stick-knob");
  let id = null;
  let cx = 0;
  let cy = 0;
  let lastDir = 5;

  const radius = () => base.offsetWidth / 2 || 60;
  const home = () => {
    const r = zone.querySelector(".stick-home").getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  };
  const place = (x, y) => {
    const rect = zone.getBoundingClientRect();
    base.style.transform = `translate(${x - rect.left}px, ${y - rect.top}px)`;
  };
  const moveKnob = (dx, dy) => {
    const r = radius();
    const d = Math.hypot(dx, dy);
    const k = d > r ? r / d : 1;
    knob.style.transform = `translate(${dx * k}px, ${dy * k}px)`;
  };
  const update = (e) => {
    let dx = e.clientX - cx;
    let dy = e.clientY - cy;
    const d = Math.hypot(dx, dy);
    const follow = radius() * 1.4;
    if (d > follow) {
      cx += (dx * (d - follow)) / d;
      cy += (dy * (d - follow)) / d;
      place(cx, cy);
      dx = e.clientX - cx;
      dy = e.clientY - cy;
    }
    moveKnob(dx, dy);
    const dir = angleToDir(dx, dy);
    if (dir !== lastDir) {
      lastDir = dir;
      if (dir !== 5 && haptics()) vibrate(4);
      pad.setDir(dir);
    }
  };
  const rest = () => {
    const h = home();
    place(h.x, h.y);
  };
  const end = (e) => {
    if (e.pointerId !== id) return;
    id = null;
    lastDir = 5;
    zone.classList.remove("active");
    moveKnob(0, 0);
    rest();
    pad.setDir(5);
  };

  zone.addEventListener("pointerdown", (e) => {
    if (id !== null) return;
    e.preventDefault();
    id = e.pointerId;
    zone.setPointerCapture(id);
    cx = e.clientX;
    cy = e.clientY;
    if (mode() === "fixed") {
      const h = home();
      if (Math.hypot(cx - h.x, cy - h.y) < radius() * 1.5) {
        cx = h.x;
        cy = h.y;
      }
    }
    place(cx, cy);
    zone.classList.add("active");
    update(e);
  });
  zone.addEventListener("pointermove", (e) => {
    if (e.pointerId === id) update(e);
  });
  zone.addEventListener("pointerup", end);
  zone.addEventListener("pointercancel", end);
  return { rest };
}

// Centres for `count` buttons placed along an arc around a point, starting
// at angle `from` and `step` degrees apart (0 = right, 90 = up). Returns
// [{x, y}] offsets from the arc centre, y pointing down like the screen.
export function arcPositions(count, { radius, from, step }) {
  const out = [];
  for (let i = 0; i < count; i++) {
    const a = ((from + step * i) * Math.PI) / 180;
    out.push({ x: Math.round(Math.cos(a) * radius) || 0, y: Math.round(-Math.sin(a) * radius) || 0 });
  }
  return out;
}

// Buttons: elements with data-key (A-D) are held while touched; elements with
// data-cmd fire a macro on touch. Fingers can slide between held buttons.
export function mountButtons(zone, pad, { haptics, onMacro }) {
  const fingers = new Map(); // pointerId -> element

  const hit = (x, y) => {
    const el = document.elementFromPoint(x, y);
    return el && zone.contains(el) ? el.closest("[data-key],[data-cmd]") : null;
  };
  const engage = (el) => {
    if (!el) return;
    el.classList.add("down");
    if (haptics()) vibrate(el.dataset.cmd ? 12 : 6);
    if (el.dataset.cmd) {
      onMacro?.(el, pad.play(el.dataset.cmd));
    } else {
      pad.press(el.dataset.key, true);
    }
  };
  const disengage = (el) => {
    if (!el) return;
    el.classList.remove("down");
    if (el.dataset.key) pad.press(el.dataset.key, false);
  };

  zone.addEventListener("pointerdown", (e) => {
    const el = hit(e.clientX, e.clientY);
    if (!el) return;
    e.preventDefault();
    zone.setPointerCapture(e.pointerId);
    fingers.set(e.pointerId, el);
    engage(el);
  });
  zone.addEventListener("pointermove", (e) => {
    if (!fingers.has(e.pointerId)) return;
    const prev = fingers.get(e.pointerId);
    // Only plain buttons slide; a move button fires once per touch.
    if (prev?.dataset.cmd) return;
    const el = hit(e.clientX, e.clientY);
    if (el === prev || el?.dataset.cmd) return;
    disengage(prev);
    fingers.set(e.pointerId, el);
    engage(el);
  });
  const end = (e) => {
    if (!fingers.has(e.pointerId)) return;
    disengage(fingers.get(e.pointerId));
    fingers.delete(e.pointerId);
  };
  zone.addEventListener("pointerup", end);
  zone.addEventListener("pointercancel", end);
}

// Simple press-and-hold buttons outside the pad (coin, start).
export function mountHoldButton(el, onDown, onUp) {
  el.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    el.setPointerCapture(e.pointerId);
    el.classList.add("down");
    onDown();
  });
  const up = () => {
    el.classList.remove("down");
    onUp();
  };
  el.addEventListener("pointerup", up);
  el.addEventListener("pointercancel", up);
}

// Desktop / Bluetooth-keyboard play: arrows or WASD, J K U I = A B C D
// (same shape as the on-screen buttons), 1-6 = move buttons, F = flip side,
// Enter = start, Backspace = coin.
export function mountKeyboard(pad, { macros, flip }) {
  const dirKeys = new Set();
  const DIR = {
    ArrowUp: "u", KeyW: "u", ArrowDown: "d", KeyS: "d",
    ArrowLeft: "l", KeyA: "l", ArrowRight: "r", KeyD: "r",
  };
  const BTN = { KeyJ: "A", KeyK: "B", KeyU: "C", KeyI: "D" };
  const dirFromKeys = () => {
    const v = (dirKeys.has("u") ? 1 : 0) - (dirKeys.has("d") ? 1 : 0);
    const h = (dirKeys.has("r") ? 1 : 0) - (dirKeys.has("l") ? 1 : 0);
    return 5 + h + v * 3;
  };
  const onKey = (e) => {
    if (e.target.closest?.("input, textarea, select")) return;
    const down = e.type === "keydown";
    if (DIR[e.code]) {
      down ? dirKeys.add(DIR[e.code]) : dirKeys.delete(DIR[e.code]);
      pad.setDir(dirFromKeys());
    } else if (BTN[e.code]) {
      pad.press(BTN[e.code], down);
    } else if (e.code === "Enter") {
      pad.press("start", down);
    } else if (e.code === "Backspace") {
      pad.press("coin", down);
    } else if (down && !e.repeat && /^Digit[1-6]$|^Numpad[1-6]$/.test(e.code)) {
      const cmd = macros()[Number(e.code.slice(-1)) - 1]?.dataset.cmd;
      if (cmd) pad.play(cmd);
    } else if (down && !e.repeat && e.code === "KeyF") {
      flip();
    } else {
      return;
    }
    e.preventDefault();
    e.stopPropagation();
  };
  // Capture phase so EmulatorJS's own key handling never sees these keys.
  window.addEventListener("keydown", onKey, true);
  window.addEventListener("keyup", onKey, true);
}
