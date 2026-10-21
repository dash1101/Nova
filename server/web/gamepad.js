// Nova web — use it with a game controller. Plug one in (or pair it) and press any button:
//   D-pad / left stick  move between things, to the nearest one that way     A  select       B  back / close
//   X  the item's menu (what a right-click or a long press gives)            Y  search
//   LB / RB  the previous / next tab in the navigation pill                  Start  Home     right stick  scroll
// On a slider, left and right change its value. A glowing ring shows where you are; it goes as soon as you use the mouse.
import { $, $$, prefs } from "./core.js";
import { toast } from "./ui.js";
import { play } from "./sounds.js";

const root = document.documentElement;
let pads = 0, raf = 0, cur = null;
const held = {};                                   // button/direction → { since, last } for auto-repeat

const FOCUSABLE = 'button:not([disabled]), a[href], [data-act], .row.click, input:not([type=hidden]):not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])';

/** Where controls can be picked from now: an open pop-up first, then the page, the top bar and the pill. */
function scope() {
  const sh = $("#sheet");
  if (sh && !sh.hidden && sh.innerHTML) return [sh];
  const front = [...$$("#wins .win.on:not([hidden])")][0];
  return [front, $("#app"), $("#alertbar")].filter(Boolean);
}
function candidates() {
  const out = [];
  for (const s of scope()) for (const el of s.querySelectorAll(FOCUSABLE)) {
    if (el.closest("[hidden], .ghost")) continue;
    const r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4 || r.bottom < 0 || r.top > innerHeight || r.right < 0 || r.left > innerWidth) continue;
    const cs = getComputedStyle(el); if (cs.visibility === "hidden" || +cs.opacity === 0) continue;
    out.push(el);
  }
  return out;
}

function setCur(el) {
  if (!el) return;
  cur?.classList.remove("padfocus");
  cur = el; cur.classList.add("padfocus");
  try { cur.focus({ preventScroll: true }); } catch {}
  cur.scrollIntoView({ block: "nearest", inline: "nearest", behavior: root.classList.contains("reduce") ? "auto" : "smooth" });
}

/** The nearest control in a direction: straight ahead counts most, sideways drift costs extra. */
function move(dx, dy) {
  const all = candidates();
  if (!cur || !cur.isConnected || !all.includes(cur)) { setCur(all.find(e => e.getBoundingClientRect().top >= 0) || all[0]); return; }
  const a = cur.getBoundingClientRect(), ax = a.left + a.width / 2, ay = a.top + a.height / 2;
  let best = null, bs = Infinity;
  for (const el of all) {
    if (el === cur || cur.contains(el) || el.contains(cur)) continue;
    const r = el.getBoundingClientRect(), bx = r.left + r.width / 2, by = r.top + r.height / 2;
    const along = dx ? (bx - ax) * dx : (by - ay) * dy;
    // must lie that way (edges, not centers, so a wide row below still counts as "below")
    const edgeOk = dx ? (dx > 0 ? r.left >= a.right - 4 : r.right <= a.left + 4) : (dy > 0 ? r.top >= a.bottom - 4 : r.bottom <= a.top + 4);
    if (along <= 0 || !edgeOk) continue;
    const side = dx ? Math.max(0, Math.abs(by - ay) - (a.height + r.height) / 2 * .5) : Math.max(0, Math.abs(bx - ax) - (a.width + r.width) / 2 * .5);
    const score = along + side * 2.5;
    if (score < bs) { bs = score; best = el; }
  }
  if (best) { setCur(best); play("tap"); }
  else if (dy) scrollBy({ top: dy * innerHeight * .4, behavior: "smooth" });   // nothing further that way: scroll
}

function press() {
  if (!cur || !cur.isConnected) return move(0, 1);
  if (cur.matches("input[type=range]")) return;
  if (cur.matches("input, textarea, select")) { cur.focus(); return; }
  cur.click();
  setTimeout(() => { if (!cur?.isConnected) { cur = null; move(0, 1); } }, 320);    // the page changed: start from the top
}
function backOut() {
  const sh = $("#sheet");
  if (sh && !sh.hidden && sh.innerHTML) { sh.click(); return; }
  document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
  setTimeout(() => { cur = null; }, 300);
}
function menu() {
  if (!cur) return;
  const r = cur.getBoundingClientRect();
  cur.dispatchEvent(new MouseEvent("contextmenu", { bubbles: true, cancelable: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 }));
}
function nudge(dir) {                                   // a slider: one step
  const el = cur; const st = +el.step || 1;
  el.value = String(Math.max(+el.min || 0, Math.min(+el.max || 100, +el.value + dir * st * (st < 5 ? 5 : 1))));
  el.dispatchEvent(new Event("input", { bubbles: true })); el.dispatchEvent(new Event("change", { bubbles: true }));
}
function tabStep(dir) {
  const bs = $$("#nav .items button"); if (!bs.length) return;
  const i = bs.findIndex(b => b.classList.contains("on")); bs[(i + dir + bs.length) % bs.length].click();
  setTimeout(() => { cur = null; move(0, 1); }, 380);
}

/** Fires once on press, then repeats while held (after a short pause), like a keyboard. */
function rep(key, down, fn, now) {
  const h = held[key];
  if (!down) { delete held[key]; return; }
  if (!h) { held[key] = { since: now, last: now }; fn(); return; }
  if (now - h.since > 380 && now - h.last > 110) { h.last = now; fn(); }
}
const once = (key, down, fn) => { if (down && !held[key]) { held[key] = 1; fn(); } else if (!down) delete held[key]; };

function poll(now) {
  raf = requestAnimationFrame(poll);
  const gp = [...(navigator.getGamepads?.() || [])].find(Boolean); if (!gp || document.hidden) return;
  const b = i => !!gp.buttons[i]?.pressed, ax = i => gp.axes[i] || 0;
  const any = gp.buttons.some(x => x.pressed) || gp.axes.some(v => Math.abs(v) > .5);
  if (any && !root.classList.contains("pad")) { root.classList.add("pad"); if (!cur) move(0, 1); }
  const L = b(14) || ax(0) < -.55, R = b(15) || ax(0) > .55, U = b(12) || ax(1) < -.55, D = b(13) || ax(1) > .55;
  const slider = cur?.matches?.("input[type=range]");
  rep("L", L, () => slider ? nudge(-1) : move(-1, 0), now); rep("R", R, () => slider ? nudge(1) : move(1, 0), now);
  rep("U", U, () => move(0, -1), now); rep("D", D, () => move(0, 1), now);
  once("A", b(0), press); once("B", b(1), backOut); once("X", b(2), menu);
  once("Y", b(3), () => { location.hash = "#/search"; setTimeout(() => $("#sq, .searchbox input, input[type=search]")?.focus(), 400); });
  once("LB", b(4), () => tabStep(-1)); once("RB", b(5), () => tabStep(1));
  once("ST", b(9), () => { location.hash = "#/home"; cur = null; });
  const sy = ax(3); if (Math.abs(sy) > .2) scrollBy({ top: sy * 18 });               // right stick scrolls smoothly
}

addEventListener("gamepadconnected", e => {
  pads++; if (!raf) raf = requestAnimationFrame(poll);
  toast(`${(e.gamepad.id || "Controller").replace(/\s*\(.*$/, "").slice(0, 40)} connected — D-pad to move, A to select, B to go back`);
  play("success");
});
addEventListener("gamepaddisconnected", () => { if (--pads <= 0) { pads = 0; cancelAnimationFrame(raf); raf = 0; root.classList.remove("pad"); cur?.classList.remove("padfocus"); cur = null; } });
// the mouse or a touch takes over again: the ring goes
addEventListener("pointermove", e => { if (root.classList.contains("pad") && (Math.abs(e.movementX) + Math.abs(e.movementY) > 3)) { root.classList.remove("pad"); cur?.classList.remove("padfocus"); } }, { passive: true });


// ── button hints in the corner (Appearance → Controller buttons: Xbox, PlayStation or plain) ──────────────────────
let padId = "";
const kind = () => { const p = prefs.padGlyphs || "auto"; if (p !== "auto") return p;
  return /xbox|045e|xinput/i.test(padId) ? "xbox" : /054c|playstation|dualsense|dualshock|wireless controller/i.test(padId) ? "ps" : "generic"; };   // Xbox first: its name also says "Wireless Controller"
const GLYPHS = { xbox: { a: "A", b: "B", x: "X", y: "Y", lb: "LB", rb: "RB" }, ps: { a: "✕", b: "○", x: "□", y: "△", lb: "L1", rb: "R1" },
  generic: { a: "1", b: "2", x: "3", y: "4", lb: "L", rb: "R" } };
export function drawHints() {
  let h = $("#padhints");
  if (!root.classList.contains("pad") || prefs.padHints === false) { h?.remove(); return; }
  if (!h) { h = document.createElement("div"); h.id = "padhints"; h.className = "frost"; document.body.append(h); }
  const g = GLYPHS[kind()], k = kind();
  h.dataset.kind = k;
  h.innerHTML = [["a", "Select"], ["b", "Back"], ["x", "Menu"], ["y", "Search"], ["lb", "Tabs"]].map(([b, l]) =>
    `<span><i class="pb pb-${b}">${b === "lb" ? g.lb + "/" + g.rb : g[b]}</i>${l}</span>`).join("");
}
addEventListener("gamepadconnected", e => { padId = e.gamepad.id || ""; setTimeout(drawHints, 50); });
new MutationObserver(drawHints).observe(root, { attributes: true, attributeFilter: ["class"] });
