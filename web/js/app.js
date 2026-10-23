import { Pad } from "./input.js";
import { parse, toArrows } from "./macro.js";
import { motionIcon } from "./glyph.js";
import { mountStick, mountButtons, mountHoldButton, mountKeyboard, arcPositions } from "./controls.js";
import { GAMES, BIOS, gameById, setName } from "./games.js";
import { putRom, listRoms, getRom, deleteRom, loadSettings, saveSettings } from "./store.js";

const EJS_VERSION = "4.2.3";
const CDN_DATA = `https://cdn.emulatorjs.org/${EJS_VERSION}/data/`;
const LOCAL_DATA = "vendor/emulatorjs/data/";
// Optional: ROMs dropped in this folder of your own copy of the app (it is
// git-ignored) are used without adding them on the phone.
const PROJECT_ROMS = "roms/";
const MAX_MOVES = 6;

const $ = (sel, root = document) => root.querySelector(sel);
const settings = loadSettings({
  stepFrames: 2,
  pressFrames: 4,
  size: 1,
  opacity: 0.8,
  haptics: true,
  leftHanded: false,
  stick: "floating", // floating | fixed | hidden
  labels: true,
  moves: {}, // custom move lists: "<roster>/<character id>" -> [{ name, cmd }]
  teams: {}, // game id -> [character id | null] x team size
  active: {}, // game id -> index of the fighter on screen
});

let game = null; // the game being played
let roster = null; // its character list, if it has one
let pad = null;

function applyLook() {
  const root = document.documentElement;
  root.style.setProperty("--pad-scale", settings.size);
  root.style.setProperty("--pad-opacity", settings.opacity);
  document.body.classList.toggle("left-handed", settings.leftHanded);
  document.body.classList.toggle("stick-fixed", settings.stick === "fixed");
  document.body.classList.toggle("stick-hidden", settings.stick === "hidden");
  document.body.classList.toggle("no-labels", !settings.labels);
  layoutButtons();
}

// ---------- ROM storage ----------

// Names of ROM zips available: added on this device, or in the project's
// roms/ folder.
async function availableRoms() {
  const names = new Set((await listRoms()).map((r) => r.name));
  const wanted = [BIOS, ...GAMES.flatMap((g) => g.sets.map((s) => s + ".zip"))];
  await Promise.all(
    wanted
      .filter((n) => !names.has(n))
      .map(async (n) => {
        try {
          const res = await fetch(PROJECT_ROMS + n, { method: "HEAD" });
          if (res.ok) names.add(n);
        } catch {}
      }),
  );
  return names;
}

async function romBytes(name) {
  const stored = await getRom(name);
  if (stored) return stored.blob;
  const res = await fetch(PROJECT_ROMS + name);
  if (!res.ok) throw new Error(`${name} not found`);
  return res.blob();
}

// The zip to boot for a game: the first of its sets that is available.
function setFor(g, names) {
  return g.sets.find((s) => names.has(s + ".zip"));
}

// Ask for one file and store it under `storeAs` (FBNeo identifies games by
// the zip name, so "King of Fighters 97.zip" is saved as kof97.zip).
function pickFile(storeAs, accept) {
  return new Promise((resolve) => {
    const input = $("#pick");
    input.onchange = async () => {
      const file = input.files[0];
      input.value = "";
      if (!file) return resolve(false);
      if (!/\.zip$/i.test(file.name)) {
        alert(`${file.name} is not a .zip file`);
        return resolve(false);
      }
      const name = accept?.includes(setName(file.name)) ? setName(file.name) + ".zip" : storeAs;
      await putRom(new File([file], name));
      resolve(true);
    };
    input.click();
  });
}

// ---------- Home: the game catalog ----------

async function renderCatalog() {
  const names = await availableRoms();
  const hasBios = names.has(BIOS);
  const bios = $("#bios");
  bios.classList.toggle("ok", hasBios);
  $(".text small", bios).textContent = hasBios ? "Ready" : "Needed by every game: add neogeo.zip";
  $(".state", bios).textContent = hasBios ? "✓" : "Add";
  bios.onclick = async () => {
    if (hasBios) {
      if (confirm("Replace the Neo Geo BIOS file?")) {
        if (await pickFile(BIOS)) renderCatalog();
      }
    } else if (await pickFile(BIOS)) {
      renderCatalog();
    }
  };

  const catalog = $("#catalog");
  catalog.replaceChildren();
  const series = [...new Set(GAMES.map((g) => g.series))];
  for (const name of series) {
    const h = document.createElement("h2");
    h.textContent = name;
    const grid = document.createElement("div");
    grid.className = "grid";
    for (const g of GAMES.filter((x) => x.series === name)) {
      const set = setFor(g, names);
      const card = document.createElement("button");
      card.className = `card ${g.layout}${set ? " ready" : ""}`;
      card.innerHTML = `<strong></strong><small></small><span class="state"></span>`;
      $("strong", card).textContent = g.title;
      $("small", card).textContent = g.subtitle || (set ? set + ".zip" : g.sets[0] + ".zip");
      $(".state", card).textContent = set ? "Play" : "Add ROM";
      card.addEventListener("click", () => openGame(g));
      if (set) {
        card.addEventListener("contextmenu", async (e) => {
          e.preventDefault();
          if (confirm(`Remove ${set}.zip from this device?`)) {
            await deleteRom(set + ".zip");
            renderCatalog();
          }
        });
      }
      grid.append(card);
    }
    catalog.append(h, grid);
  }
}

async function openGame(g) {
  let names = await availableRoms();
  if (!names.has(BIOS)) {
    alert("First add the Neo Geo BIOS (neogeo.zip). Every game needs it.");
    if (!(await pickFile(BIOS))) return;
    names = await availableRoms();
  }
  if (!setFor(g, names)) {
    alert(`Add your ${g.title} ROM (${g.sets.map((s) => s + ".zip").join(" or ")}).`);
    if (!(await pickFile(g.sets[0] + ".zip", g.sets))) return;
    names = await availableRoms();
  }
  renderCatalog();
  startGame(g, setFor(g, names));
}

// ---------- Emulator ----------

async function dataPath() {
  try {
    const res = await fetch(LOCAL_DATA + "loader.js", { method: "HEAD" });
    if (res.ok) return LOCAL_DATA;
  } catch {}
  return CDN_DATA;
}

async function startGame(g, set) {
  const name = set + ".zip";
  const [rom, bios] = await Promise.all([romBytes(name), romBytes(BIOS)]);
  const biosBytes = new Uint8Array(await bios.arrayBuffer());
  game = g;
  roster = g.roster ? await loadRoster(g.roster) : null;

  $("#library").hidden = true;
  $("#play").hidden = false;
  document.title = g.title;
  document.body.dataset.layout = g.layout;
  try {
    await document.documentElement.requestFullscreen?.({ navigationUI: "hide" });
    await screen.orientation?.lock?.("landscape");
  } catch {}

  const path = await dataPath();
  Object.assign(window, {
    EJS_player: "#game",
    EJS_core: "fbneo",
    EJS_gameName: set,
    EJS_gameUrl: new File([rom], name),
    EJS_pathtodata: path,
    EJS_startOnLoaded: true,
    EJS_color: "#e8b21a",
    EJS_backgroundColor: "#000",
    EJS_defaultOptions: { "virtual-gamepad": "disabled" },
    // Our own key handling replaces EmulatorJS's keyboard mapping.
    EJS_defaultControls: { 0: {}, 1: {}, 2: {}, 3: {} },
    // FBNeo looks for the BIOS next to the game, so drop neogeo.zip into the
    // emulator's file system before the game boots.
    EJS_ready: () => {
      window.EJS_emulator.on("saveDatabaseLoaded", (FS) => FS.writeFile("/" + BIOS, biosBytes));
    },
    EJS_onGameStart: onGameStart,
  });
  const script = document.createElement("script");
  script.src = path + "loader.js";
  document.body.append(script);
}

async function loadRoster(name) {
  try {
    const res = await fetch(`data/rosters/${name}.json`);
    return res.ok ? await res.json() : null;
  } catch {
    return null;
  }
}

function onGameStart() {
  const gm = window.EJS_emulator.gameManager;
  const frameClock = typeof gm.getFrameNum === "function";
  pad = new Pad({
    send: (index, value) => gm.simulateInput(0, index, value),
    clock: () => (frameClock ? gm.getFrameNum() : Math.floor(performance.now() / 16.67)),
  });
  pad.timing = { step: settings.stepFrames, press: settings.pressFrames };
  setupHud();
  $("#hud").hidden = false;
  stick.rest();
}

// ---------- In-game HUD ----------

const KEY_COLORS = { A: "#ff5a5f", B: "#ffc928", C: "#34c47c", D: "#4a9df0" };

function setupHud() {
  const labels = game.labels || {};
  for (const k of ["A", "B", "C", "D"]) {
    const btn = $(`.btn.${k.toLowerCase()}`);
    const label = labels[k];
    btn.hidden = game.layout === "shooter" ? !label : false;
    // A word label ("Shoot") replaces the letter; a letter label is redundant.
    const word = label && label !== k;
    $("b", btn).textContent = word && game.layout === "shooter" ? label : k;
    $("small", btn).textContent = word && game.layout !== "shooter" ? label : "";
    btn.classList.toggle("word", !!(word && game.layout === "shooter"));
  }
  $("#flip").hidden = game.layout !== "fighter";
  renderTeam();
  renderMoves();
  updateFacing();
}

// ----- Team bar (fighters): pick up to three, tap who is fighting -----

function team() {
  const size = game.team || 0;
  const t = settings.teams[game.id] || [];
  return Array.from({ length: size }, (_, i) => t[i] ?? null);
}

function activeIndex() {
  return Math.min(settings.active[game.id] ?? 0, Math.max(0, (game.team || 1) - 1));
}

function character(id) {
  return roster?.characters.find((c) => c.id === id) || null;
}

function activeCharacter() {
  if (!roster) return null;
  return character(team()[activeIndex()]);
}

function initials(name) {
  return name
    .replace(/[^A-Za-z' ]/g, " ")
    .split(/\s+/)
    .filter(Boolean)
    .map((w) => w[0])
    .join("")
    .slice(0, 2)
    .toUpperCase();
}

// A colour per character, from its name, so portraits are easy to tell apart.
function hue(name) {
  let h = 0;
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) % 360;
  return h;
}

function portrait(c, cls = "") {
  const el = document.createElement("span");
  el.className = `portrait ${cls}`.trim();
  if (c) {
    el.textContent = initials(c.name);
    el.style.setProperty("--h", hue(c.name));
  } else {
    el.textContent = "+";
    el.classList.add("empty");
  }
  return el;
}

function renderTeam() {
  const bar = $("#team");
  bar.hidden = !(game.team && roster);
  if (bar.hidden) return;
  const t = team();
  const active = activeIndex();
  bar.replaceChildren(
    ...t.map((id, i) => {
      const c = character(id);
      const slot = document.createElement("button");
      slot.className = `slot${i === active ? " active" : ""}`;
      slot.setAttribute("aria-label", c ? `${c.name}${i === active ? " (fighting)" : ""}` : `Pick fighter ${i + 1}`);
      const name = document.createElement("small");
      name.textContent = c ? c.name.split(" ")[0] : "Pick";
      slot.append(portrait(c), name);
      slot.addEventListener("click", () => {
        // Tap a filled, inactive slot to switch to that fighter; tap the
        // active one (or an empty one) to choose who goes there.
        if (c && i !== active) {
          settings.active[game.id] = i;
          saveSettings(settings);
          renderTeam();
          renderMoves();
          toast(`${c.name}`);
        } else {
          openRoster(i);
        }
      });
      return slot;
    }),
  );
  $("#pick-hint").hidden = t.some(Boolean);
}

function openRoster(slot) {
  pad?.releaseAll();
  window.EJS_emulator?.pause?.();
  $("#roster-slot").textContent = `slot ${slot + 1}`;
  const grid = $("#roster-grid");
  grid.replaceChildren();
  const teams = [...new Set(roster.characters.map((c) => c.team || "Other"))];
  for (const teamName of teams) {
    const members = roster.characters.filter((c) => (c.team || "Other") === teamName);
    const head = document.createElement("button");
    head.type = "button";
    head.className = "team-name";
    head.textContent = teamName;
    head.addEventListener("click", () => {
      settings.teams[game.id] = members.slice(0, game.team).map((c) => c.id);
      settings.active[game.id] = 0;
      saveSettings(settings);
      $("#roster").close("pick");
    });
    const row = document.createElement("div");
    row.className = "members";
    for (const c of members) {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "member";
      const name = document.createElement("small");
      name.textContent = c.name;
      b.append(portrait(c), name);
      b.addEventListener("click", () => {
        const t = team();
        t[slot] = c.id;
        settings.teams[game.id] = t;
        settings.active[game.id] = slot;
        saveSettings(settings);
        $("#roster").close("pick");
      });
      row.append(b);
    }
    grid.append(head, row);
  }
  $("#roster").showModal();
}

$("#roster").addEventListener("close", () => {
  renderTeam();
  renderMoves();
  window.EJS_emulator?.play?.();
});

// ----- Move ring -----

function movesKey(c) {
  return `${game.roster}/${c.id}`;
}

function movesFor(c) {
  if (!c) return [];
  const custom = settings.moves[movesKey(c)];
  return (custom || c.moves).slice(0, MAX_MOVES);
}

// Move button: the stick motion drawn as an icon, coloured chips for the
// button(s) it ends on, and the move's name underneath.
function moveButton(move) {
  const b = document.createElement("button");
  b.className = "skill glass";
  b.dataset.cmd = move.cmd;
  b.setAttribute("aria-label", `${move.name}: ${toArrows(move.cmd)}`);
  if (move.type && move.type !== "special") b.classList.add("super");
  const { buttons } = parse(move.cmd);
  b.append(motionIcon(move.cmd));
  const keys = document.createElement("span");
  keys.className = "keys";
  for (const k of buttons) {
    const i = document.createElement("i");
    i.dataset.k = k;
    i.textContent = k;
    i.style.setProperty("--c", KEY_COLORS[k]);
    keys.append(i);
  }
  const label = document.createElement("span");
  label.className = "label";
  // "Ura 108 Shiki: Orochinagi" -> "Orochinagi": the part people call it.
  label.textContent = move.name.split(": ").pop();
  b.append(keys, label);
  return b;
}

// One-tap buttons on the inner ring: the power button, roll, blowback, or
// Metal Slug's rapid-fire toggle.
function systemButton(move) {
  const b = document.createElement("button");
  b.className = `sys glass${move.power ? " max" : ""}`;
  if (move.cmd) b.dataset.cmd = move.cmd;
  b.setAttribute("aria-label", move.cmd ? `${move.name}: ${move.cmd}` : move.name);
  b.innerHTML = "<b></b><small></small>";
  $("b", b).textContent = move.name;
  $("small", b).textContent = move.cmd ? (move.power ? move.cmd : move.cmd.split("").join("+")) : move.hint || "";
  return b;
}

function renderMoves() {
  const c = game.layout === "fighter" ? activeCharacter() : null;
  const moves = c ? movesFor(c) : game.moves || [];
  $("#moves").replaceChildren(...moves.map(moveButton));
  const sys = [];
  if (game.power) sys.push(systemButton({ ...game.power, power: true }));
  for (const m of game.system || []) sys.push(systemButton(m));
  if (game.rapid) {
    const on = pad?.rapid.has(game.rapid);
    const b = systemButton({ name: "Rapid", hint: on ? "ON" : "OFF" });
    b.classList.toggle("on", !!on);
    b.addEventListener("click", () => {
      pad?.setRapid(game.rapid, !pad.rapid.has(game.rapid));
      renderMoves();
    });
    sys.push(b);
  }
  $("#system").replaceChildren(...sys);
  layoutButtons();
}

// A B C D sit in a staggered 2x2 like an MVS panel. Moves go on an outer
// ring around them and the one-tap system buttons on an inner one, both
// sweeping from above the buttons round to the thumb side.
function layoutButtons() {
  const S = 62 * settings.size;
  const mirror = settings.leftHanded ? -1 : 1;
  const put = (el, { x, y }) => {
    el.style.left = `${x * mirror}px`;
    el.style.top = `${y}px`;
  };
  const h = S / 2 + 5;
  put($(".btn.c"), { x: -0.3 * S - 5, y: -h });
  put($(".btn.d"), { x: 0.7 * S + 5, y: -h });
  put($(".btn.a"), { x: -0.7 * S - 5, y: h });
  put($(".btn.b"), { x: 0.3 * S + 5, y: h });
  const moves = [...document.querySelectorAll("#moves .skill")];
  arcPositions(moves.length, { radius: 3 * S, from: 72, step: 23 }).forEach((p, i) => put(moves[i], p));
  const sys = [...document.querySelectorAll("#system .sys")];
  arcPositions(sys.length, { radius: 1.72 * S, from: 104, step: 34 }).forEach((p, i) => put(sys[i], p));
}

// Sweep a ring round the button for as long as its motion takes to play.
function showFiring(el, frames) {
  if (!el.classList.contains("skill") || !frames) return;
  el.classList.remove("firing");
  void el.offsetWidth;
  el.style.setProperty("--dur", `${Math.round(frames * 16.7)}ms`);
  el.classList.add("firing");
  clearTimeout(el.firingTimer);
  el.firingTimer = setTimeout(() => el.classList.remove("firing"), frames * 16.7 + 120);
}

function updateFacing() {
  const left = pad?.facingLeft;
  $("#flip").title = left ? "Your fighter faces left. Tap when sides swap." : "Your fighter faces right. Tap when sides swap.";
  $("#flip").classList.toggle("left", !!left);
  document.body.classList.toggle("facing-left", !!left);
}

function flip() {
  if (!pad || game?.layout !== "fighter") return;
  pad.facingLeft = !pad.facingLeft;
  updateFacing();
}

const haptics = () => settings.haptics;
const fakePad = { setDir: (d) => pad?.setDir(d), press: (k, v) => pad?.press(k, v), play: (c) => pad?.play(c) };
const stick = mountStick($("#stick-zone"), fakePad, { haptics, mode: () => settings.stick });
mountButtons($("#buttons"), fakePad, { haptics, onMacro: showFiring });
mountKeyboard(fakePad, { macros: () => [...document.querySelectorAll("#moves .skill")], flip });
mountHoldButton($("#coin"), () => pad?.press("coin", true), () => pad?.press("coin", false));
mountHoldButton($("#start"), () => pad?.press("start", true), () => pad?.press("start", false));
$("#flip").addEventListener("click", flip);
window.addEventListener("resize", () => {
  layoutButtons();
  stick.rest();
});

// ----- Pause menu -----

const menu = $("#menu");
$("#pause").addEventListener("click", () => {
  pad?.releaseAll();
  window.EJS_emulator?.pause?.();
  $("#menu-title").textContent = game ? game.title : "Paused";
  const c = activeCharacter();
  let edit = $('#menu button[value="moves"]');
  if (c) {
    if (!edit) {
      edit = document.createElement("button");
      edit.value = "moves";
      $('#menu button[value="settings"]').before(edit);
    }
    edit.textContent = `Edit ${c.name}'s moves`;
  } else {
    edit?.remove();
  }
  menu.showModal();
});
menu.addEventListener("close", () => {
  const gm = window.EJS_emulator?.gameManager;
  switch (menu.returnValue) {
    case "save":
      gm?.quickSave(1);
      toast("State saved");
      break;
    case "load":
      gm?.quickLoad(1);
      toast("State loaded");
      break;
    case "moves":
      openMoveEditor(activeCharacter());
      return;
    case "settings":
      openSettings();
      return;
    case "quit":
      if (confirm("Quit to the game list? Unsaved progress is lost.")) location.reload();
      break;
  }
  window.EJS_emulator?.play?.();
});
// Losing focus (notification, app switch) must not leave a direction stuck.
document.addEventListener("visibilitychange", () => pad?.releaseAll());
window.addEventListener("blur", () => pad?.releaseAll());

let toastTimer = 0;
function toast(text) {
  const el = $("#toast");
  el.textContent = text;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (el.hidden = true), 1400);
}

// ---------- Move editor (per character) ----------

const editor = $("#moves-editor");
let editing = null;
let draft = [];

function openMoveEditor(c) {
  editing = c;
  draft = movesFor(c).map((m) => ({ name: m.name, cmd: m.cmd }));
  $("#editor-who").textContent = c.name;
  renderDraft();
  editor.showModal();
}

function renderDraft() {
  const list = $("#move-editor");
  list.replaceChildren();
  draft.forEach((move, i) => {
    const row = document.createElement("li");
    row.innerHTML = `
      <input class="name" aria-label="Button label" maxlength="28">
      <input class="cmd" aria-label="Command" autocapitalize="characters" spellcheck="false">
      <button type="button" class="test" aria-label="Test this move">▶</button>
      <button type="button" class="del" aria-label="Remove">✕</button>
      <output></output>`;
    const name = $(".name", row);
    const cmd = $(".cmd", row);
    const out = $("output", row);
    name.value = move.name;
    cmd.value = move.cmd;
    const check = () => {
      try {
        parse(cmd.value);
        out.textContent = toArrows(cmd.value);
        out.className = "";
      } catch (err) {
        out.textContent = err.message;
        out.className = "bad";
      }
    };
    check();
    name.addEventListener("input", () => (draft[i].name = name.value));
    cmd.addEventListener("input", () => {
      draft[i].cmd = cmd.value.trim();
      check();
    });
    $(".del", row).addEventListener("click", () => {
      draft.splice(i, 1);
      renderDraft();
    });
    $(".test", row).addEventListener("click", () => {
      try {
        parse(cmd.value);
      } catch {
        return;
      }
      editor.close("test");
      setTimeout(() => {
        const frames = pad?.play(cmd.value);
        const el = [...document.querySelectorAll("#moves .skill")][i];
        if (el) showFiring(el, frames);
      }, 150);
    });
    list.append(row);
  });
  $("#add-move").disabled = draft.length >= MAX_MOVES;
}

$("#add-move").addEventListener("click", () => {
  draft.push({ name: "Super", cmd: "236236+A" });
  renderDraft();
});
$("#reset-moves").addEventListener("click", () => {
  draft = editing.moves.slice(0, MAX_MOVES).map((m) => ({ name: m.name, cmd: m.cmd }));
  renderDraft();
});

editor.addEventListener("close", () => {
  if (editor.returnValue !== "cancel" && editing) {
    const valid = draft.filter((m) => {
      try {
        parse(m.cmd);
        return m.name.trim();
      } catch {
        return false;
      }
    });
    settings.moves[movesKey(editing)] = valid;
    saveSettings(settings);
    renderMoves();
  }
  window.EJS_emulator?.play?.();
});

// ---------- Settings ----------

const dialog = $("#settings");

function openSettings() {
  $("#step").value = settings.stepFrames;
  $("#press").value = settings.pressFrames;
  $("#size").value = settings.size;
  $("#opacity").value = settings.opacity;
  $("#haptics").checked = settings.haptics;
  $("#lefty").checked = settings.leftHanded;
  $("#labels").checked = settings.labels;
  $("#stick-mode").value = settings.stick;
  dialog.showModal();
}

dialog.addEventListener("close", () => {
  if (dialog.returnValue !== "cancel") {
    settings.stepFrames = Number($("#step").value);
    settings.pressFrames = Number($("#press").value);
    settings.size = Number($("#size").value);
    settings.opacity = Number($("#opacity").value);
    settings.haptics = $("#haptics").checked;
    settings.leftHanded = $("#lefty").checked;
    settings.labels = $("#labels").checked;
    settings.stick = $("#stick-mode").value;
    saveSettings(settings);
    applyLook();
    stick.rest();
    if (pad) pad.timing = { step: settings.stepFrames, press: settings.pressFrames };
  }
  window.EJS_emulator?.play?.();
});

applyLook();
renderCatalog();

if ("serviceWorker" in navigator && location.protocol !== "file:") {
  navigator.serviceWorker.register("sw.js").catch(() => {});
}
