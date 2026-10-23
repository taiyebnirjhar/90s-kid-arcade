// Copies EmulatorJS and the FBNeo core out of node_modules into
// vendor/emulatorjs/data so the app can run without the EmulatorJS CDN.
// Run `npm install && npm run vendor`. The app uses vendor/ when present.
import { cpSync, mkdirSync, rmSync } from "node:fs";

const out = "vendor/emulatorjs/data";
rmSync("vendor", { recursive: true, force: true });
mkdirSync(`${out}/cores`, { recursive: true });
cpSync("node_modules/@emulatorjs/emulatorjs/data", out, { recursive: true });
for (const f of ["fbneo-wasm.data", "fbneo-legacy-wasm.data", "fbneo-thread-wasm.data", "fbneo-thread-legacy-wasm.data"]) {
  cpSync(`node_modules/@emulatorjs/core-fbneo/${f}`, `${out}/cores/${f}`);
}
cpSync("node_modules/@emulatorjs/core-fbneo/reports", `${out}/cores/reports`, { recursive: true });
console.log(`EmulatorJS + FBNeo copied to ${out}`);
