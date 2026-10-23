// ROM library in IndexedDB (add a game once, tap it next time) and settings
// in localStorage. Both can be unavailable (private mode), so every call
// degrades to "nothing stored" instead of throwing.

const DB = "kof-arcade";
const STORE = "roms";

function open() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE, { keyPath: "name" });
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function tx(mode, fn) {
  const db = await open();
  return new Promise((resolve, reject) => {
    const t = db.transaction(STORE, mode);
    const result = fn(t.objectStore(STORE));
    t.oncomplete = () => resolve(result.result);
    t.onerror = () => reject(t.error);
  });
}

export async function putRom(file) {
  const entry = { name: file.name.toLowerCase(), blob: file, size: file.size, added: Date.now() };
  try {
    await tx("readwrite", (s) => s.put(entry));
  } catch (e) {
    console.warn("Could not store ROM", e);
  }
  return entry;
}

export async function listRoms() {
  try {
    return (await tx("readonly", (s) => s.getAll())) || [];
  } catch {
    return [];
  }
}

export async function getRom(name) {
  try {
    return await tx("readonly", (s) => s.get(name));
  } catch {
    return undefined;
  }
}

export async function deleteRom(name) {
  try {
    await tx("readwrite", (s) => s.delete(name));
  } catch {}
}

export function loadSettings(defaults) {
  try {
    return { ...defaults, ...JSON.parse(localStorage.getItem("kof-arcade-settings") || "{}") };
  } catch {
    return { ...defaults };
  }
}

export function saveSettings(settings) {
  try {
    localStorage.setItem("kof-arcade-settings", JSON.stringify(settings));
  } catch {}
}
