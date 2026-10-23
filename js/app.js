import { Pad } from "./input.js";
import { parse, toArrows } from "./macro.js";
import { mountStick, mountButtons, mountHoldButton, mountKeyboard } from "./controls.js";
import { CHARACTERS, GENERIC, gameTitle, systemMoves } from "./moves.js";
import { putRom, listRoms, getRom, deleteRom, loadSettings, saveSettings } from "./store.js";

const EJS_VERSION = "4.2.3";
const CDN_DATA = `https://cdn.emulatorjs.org/${EJS_VERSION}/data/`;
const LOCAL_DATA = "vendor/emulatorjs/data/";
const BIOS = "neogeo.zip";
const MAX_MOVES = 6;

const $ = (sel, root = document) => root.querySelector(sel);
const settings = loadSettings({
  stepFrames: 2,
  pressFrames: 4,
  size: 1,
  opacity: 0.8,
  haptics: true,
  leftHanded: false,
  moves: {}, // per game set: [{ name, cmd }]
});

let currentSet = null;
let pad = null;

function movesFor(set) {
  return settings.moves[set] || GENERIC;
}

function applyLook() {
  const root = document.documentElement;
  root.style.setProperty("--pad-scale", settings.size);
  root.style.setProperty("--pad-opacity", settings.opacity);
  document.body.classList.toggle("left-handed", settings.leftHanded);
}

// ---------- Library ----------

async function renderLibrary() {
  const roms = await listRoms();
  const hasBios = roms.some((r) => r.name === BIOS);
  const games = roms.filter((r) => r.name !== BIOS).sort((a, b) => a.name.localeCompare(b.name));
  $("#bios-status").textContent = hasBios ? "Neo Geo BIOS ready" : "Neo Geo BIOS missing: add neogeo.zip";
  $("#bios-status").classList.toggle("ok", hasBios);
  const list = $("#game-list");
  list.replaceChildren();
  for (const rom of games) {
    const li = document.createElement("li");
    const play = document.createElement("button");
    play.className = "game";
    play.innerHTML = `<strong></strong><span></span>`;
    play.querySelector("strong").textContent = gameTitle(rom.name);
    play.querySelector("span").textContent = `${rom.name} · ${(rom.size / 1048576).toFixed(1)} MB`;
    play.disabled = !hasBios;
    play.addEventListener("click", () => startGame(rom.name));
    const del = document.createElement("button");
    del.className = "remove";
    del.setAttribute("aria-label", `Remove ${rom.name}`);
    del.textContent = "✕";
    del.addEventListener("click", async () => {
      if (confirm(`Remove ${rom.name} from this device?`)) {
        await deleteRom(rom.name);
        renderLibrary();
      }
    });
    li.append(play, del);
    list.append(li);
  }
  $("#empty").hidden = games.length > 0;
}

$("#add-files").addEventListener("change", async (e) => {
  for (const file of e.target.files) {
    if (!/\.zip$/i.test(file.name)) {
      alert(`${file.name} is not a .zip ROM set`);
      continue;
    }
    await putRom(file);
  }
  e.target.value = "";
  renderLibrary();
});

// ---------- Emulator ----------

async function dataPath() {
  try {
    const res = await fetch(LOCAL_DATA + "loader.js", { method: "HEAD" });
    if (res.ok) return LOCAL_DATA;
  } catch {}
  return CDN_DATA;
}

async function startGame(name) {
  const [rom, bios] = await Promise.all([getRom(name), getRom(BIOS)]);
  if (!rom || !bios) return;
  const biosBytes = new Uint8Array(await bios.blob.arrayBuffer());
  currentSet = name.replace(/\.zip$/, "");

  $("#library").hidden = true;
  $("#play").hidden = false;
  document.title = gameTitle(name);
  try {
    await document.documentElement.requestFullscreen?.({ navigationUI: "hide" });
    await screen.orientation?.lock?.("landscape");
  } catch {}

  const path = await dataPath();
  Object.assign(window, {
    EJS_player: "#game",
    EJS_core: "fbneo",
    EJS_gameName: currentSet,
    EJS_gameUrl: new File([rom.blob], name),
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

function onGameStart() {
  const emu = window.EJS_emulator;
  const gm = emu.gameManager;
  const frameClock = typeof gm.getFrameNum === "function";
  pad = new Pad({
    send: (index, value) => gm.simulateInput(0, index, value),
    clock: () => (frameClock ? gm.getFrameNum() : Math.floor(performance.now() / 16.67)),
  });
  pad.timing = { step: settings.stepFrames, press: settings.pressFrames };
  $("#hud").hidden = false;
  renderMoves();
  updateFacing();
}

// ---------- In-game HUD ----------

function moveButton(move, cls) {
  const b = document.createElement("button");
  b.className = cls;
  b.dataset.cmd = move.cmd;
  b.innerHTML = `<b></b><small></small>`;
  b.querySelector("b").textContent = move.name;
  b.querySelector("small").textContent = toArrows(move.cmd);
  return b;
}

function renderMoves() {
  $("#moves").replaceChildren(...movesFor(currentSet).map((m) => moveButton(m, "move")));
  $("#system").replaceChildren(...systemMoves(currentSet).map((m) => moveButton(m, "sys")));
}

function updateFacing() {
  const left = pad?.facingLeft;
  $("#flip").textContent = left ? "◀ Facing" : "Facing ▶";
  $("#flip").title = left ? "Your fighter faces left. Tap when sides swap." : "Your fighter faces right. Tap when sides swap.";
  $("#flip").classList.toggle("left", !!left);
  document.body.classList.toggle("facing-left", !!left);
}

function flip() {
  if (!pad) return;
  pad.facingLeft = !pad.facingLeft;
  updateFacing();
}

const haptics = () => settings.haptics;
const fakePad = { setDir: (d) => pad?.setDir(d), press: (k, v) => pad?.press(k, v), play: (c) => pad?.play(c) };
mountStick($("#stick-zone"), fakePad, { haptics });
mountButtons($("#buttons"), fakePad, { haptics });
mountKeyboard(fakePad, { macros: () => [...document.querySelectorAll("#moves .move")], flip });
mountHoldButton($("#coin"), () => pad?.press("coin", true), () => pad?.press("coin", false));
mountHoldButton($("#start"), () => pad?.press("start", true), () => pad?.press("start", false));
$("#flip").addEventListener("click", flip);
$("#save").addEventListener("click", () => {
  window.EJS_emulator?.gameManager.quickSave(1);
  toast("State saved");
});
$("#load").addEventListener("click", () => {
  window.EJS_emulator?.gameManager.quickLoad(1);
  toast("State loaded");
});
$("#exit").addEventListener("click", () => {
  if (confirm("Quit to the game list? Unsaved progress is lost.")) location.reload();
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

// ---------- Settings ----------

const dialog = $("#settings");
let draft = [];

function openSettings() {
  draft = movesFor(currentSet).map((m) => ({ ...m }));
  const select = $("#character");
  select.replaceChildren(new Option("Pick a character…", ""), new Option("Generic motions", "__generic"));
  for (const name of Object.keys(CHARACTERS)) select.append(new Option(name, name));
  $("#step").value = settings.stepFrames;
  $("#press").value = settings.pressFrames;
  $("#size").value = settings.size;
  $("#opacity").value = settings.opacity;
  $("#haptics").checked = settings.haptics;
  $("#lefty").checked = settings.leftHanded;
  $("#settings-game").textContent = currentSet ? gameTitle(currentSet) : "";
  renderDraft();
  pad?.releaseAll();
  window.EJS_emulator?.pause?.();
  dialog.showModal();
}

function renderDraft() {
  const list = $("#move-editor");
  list.replaceChildren();
  draft.forEach((move, i) => {
    const row = document.createElement("li");
    row.innerHTML = `
      <input class="name" aria-label="Button label" maxlength="24">
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
      dialog.close("test");
      window.EJS_emulator?.play?.();
      setTimeout(() => pad?.play(cmd.value), 150);
    });
    list.append(row);
  });
  $("#add-move").disabled = draft.length >= MAX_MOVES;
}

$("#character").addEventListener("change", (e) => {
  const v = e.target.value;
  if (!v) return;
  draft = (v === "__generic" ? GENERIC : CHARACTERS[v]).map((m) => ({ ...m }));
  renderDraft();
});
$("#add-move").addEventListener("click", () => {
  draft.push({ name: "Super", cmd: "236236+A" });
  renderDraft();
});
$("#reset-moves").addEventListener("click", () => {
  draft = GENERIC.map((m) => ({ ...m }));
  renderDraft();
});
$("#open-settings").addEventListener("click", openSettings);

dialog.addEventListener("close", () => {
  if (dialog.returnValue === "cancel") {
    window.EJS_emulator?.play?.();
    return;
  }
  const valid = draft.filter((m) => {
    try {
      parse(m.cmd);
      return m.name.trim();
    } catch {
      return false;
    }
  });
  if (currentSet) settings.moves[currentSet] = valid.slice(0, MAX_MOVES);
  settings.stepFrames = Number($("#step").value);
  settings.pressFrames = Number($("#press").value);
  settings.size = Number($("#size").value);
  settings.opacity = Number($("#opacity").value);
  settings.haptics = $("#haptics").checked;
  settings.leftHanded = $("#lefty").checked;
  saveSettings(settings);
  applyLook();
  if (pad) pad.timing = { step: settings.stepFrames, press: settings.pressFrames };
  if (currentSet) renderMoves();
  if (dialog.returnValue !== "test") window.EJS_emulator?.play?.();
});

applyLook();
renderLibrary();

if ("serviceWorker" in navigator && location.protocol !== "file:") {
  navigator.serviceWorker.register("sw.js").catch(() => {});
}
