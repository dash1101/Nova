// Nova web — a few things to find. (Shh.)
import { toast } from "./ui.js";
import { play } from "./sounds.js";

const root = document.documentElement;
// ↑ ↑ ↓ ↓ ← → ← → B A: party mode — the colors spin for a little while
const KONAMI = ["ArrowUp", "ArrowUp", "ArrowDown", "ArrowDown", "ArrowLeft", "ArrowRight", "ArrowLeft", "ArrowRight", "b", "a"];
let k = 0;
addEventListener("keydown", e => {
  if (e.target.closest?.("input, textarea")) return;
  k = (e.key === KONAMI[k] || e.key.toLowerCase() === KONAMI[k]) ? k + 1 : (e.key === KONAMI[0] ? 1 : 0);
  if (k === KONAMI.length) { k = 0; party(); }
});
export function party() {
  if (root.classList.contains("party")) return;
  root.classList.add("party"); play("startup", { force: true }); toast("Party mode! (it calms down in a few seconds)");
  setTimeout(() => root.classList.remove("party"), 9000);
}
// the fan on Home: click it ten times quickly and it shows off
let clicks = [];
document.addEventListener("click", e => {
  if (!e.target.closest?.("#fh, .fanhero, .fanwrap")) return;
  const now = Date.now(); clicks = clicks.filter(t => now - t < 3000); clicks.push(now);
  if (clicks.length >= 10) { clicks = []; root.classList.add("turbo"); toast("Turbo fan engaged. (Not really — your server is fine.)"); play("success", { force: true }); setTimeout(() => root.classList.remove("turbo"), 6000); }
});
// typing "hello nova" anywhere that isn't a text box
let typed = "";
addEventListener("keydown", e => {
  if (e.target.closest?.("input, textarea") || e.key.length !== 1) return;
  typed = (typed + e.key.toLowerCase()).slice(-10);
  if (typed === "hello nova") toast("Hello! Your server says hi back. 👋");
});
