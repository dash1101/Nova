// Nova web — sounds and haptics. Every sound is made here from sounds.json (the app makes the same ones from the same
// file), one pack per look: Aero's glassy chimes and clicks, Mica's soft taps, Breeze's plucks, Glass's pings, Material's
// ticks, the Default look's droplets. Each pack is rendered once into short buffers, so playing one costs next to nothing.
import { prefs } from "./core.js";

let ctx = null, data = null, loading = null;
const bufs = {};                                   // pack → event → AudioBuffer
const SR = 44100;

const pref = (k, d) => prefs[k] ?? d;
export const soundsOn = () => pref("sounds", true);
const vol = () => Math.max(0, Math.min(100, +pref("soundVol", 60))) / 100;

function look() {
  const c = document.documentElement.classList;
  return c.contains("aero") ? "aero" : c.contains("fluent") ? "fluent" : c.contains("breeze") ? "breeze" : c.contains("liquid") ? "liquid" : c.contains("material") ? "material" : "default";
}
export const packId = () => { const p = pref("soundPack", "auto"); return p === "auto" ? look() : p; };

async function load() {
  if (data) return data;
  return loading ||= fetch("/web/sounds.json").then(r => r.json()).then(j => data = j).catch(() => null);
}

/** One sound from its recipe, as samples. The app's Sounds.kt does the same steps, so both sound alike. */
export function render(voices, echo) {
  let len = 0;
  for (const v of voices) len = Math.max(len, (v.t || 0) + (v.a || 0) + (v.d || .1));
  const tail = echo ? echo[0] * 5 : 0, n = Math.ceil((len + tail + .02) * SR), out = new Float32Array(n);
  let seed = 22222;                                 // the same noise every time (and in the app)
  const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x3fffffff - 1; };
  for (const v of voices) {
    const s0 = Math.floor((v.t || 0) * SR), a = v.a || .001, d = v.d || .1, total = Math.ceil((a + d) * SR), g = v.g || .1;
    const f = v.f || 440, f2 = v.f2 || f, kLp = v.lp ? 1 - Math.exp(-2 * Math.PI * v.lp / SR) : 0, kHp = v.hp ? 1 - Math.exp(-2 * Math.PI * v.hp / SR) : 0;
    let ph = 0, lp = 0, hpLow = 0;
    for (let i = 0; i < total && s0 + i < n; i++) {
      const t = i / SR, env = t < a ? t / a : Math.exp(Math.log(.001) * (t - a) / d);
      let x;
      if (v.w === "noise") {
        x = rnd();
        if (kLp) { lp += kLp * (x - lp); x = lp; }
        if (kHp) { hpLow += kHp * (x - hpLow); x = x - hpLow; }
      } else {
        const fr = f * Math.pow(f2 / f, Math.min(1, t / (a + d)));
        ph += fr / SR; ph -= Math.floor(ph);
        x = v.w === "triangle" ? 1 - 4 * Math.abs(ph - .5) : v.w === "square" ? (ph < .5 ? 1 : -1) : Math.sin(2 * Math.PI * ph);
      }
      out[s0 + i] += x * env * g;
    }
  }
  if (echo) { const D = Math.round(echo[0] * SR), fb = echo[1]; for (let i = D; i < n; i++) out[i] += out[i - D] * fb; }
  for (let i = 0; i < n; i++) out[i] = Math.tanh(out[i] * 1.2) / 1.2;     // never clips
  return out;
}

async function buffers(pack) {
  if (bufs[pack]) return bufs[pack];
  const j = await load(); if (!j || !ctx) return null;
  const p = j.packs[pack] || j.packs.default, b = {};
  for (const ev of j.events) {
    if (!p[ev]) continue;
    const s = render(p[ev], p.echo), ab = ctx.createBuffer(1, s.length, SR);
    ab.copyToChannel(s, 0); b[ev] = ab;
  }
  return bufs[pack] = b;
}

/** Plays one of the events (tap, toggleOn, nav, info, warning, critical, success, …) in this look's pack. */
/** Which switch (Appearance → Sounds) each sound belongs to. */
export const KINDS = { taps: ["Taps", "Buttons, rows and links"], switches: ["Switches", "On and off"], pages: ["Pages", "Going into a page and back, and the navigation pill sliding"],
  popups: ["Pop-ups", "Dialogs and menus opening and closing"], windows: ["Windows", "App windows opening, minimizing and closing"], tasks: ["Finished tasks", "Done, or didn't finish"] };
const KIND = { tap: "taps", toggleOn: "switches", toggleOff: "switches", nav: "pages", back: "pages", slide: "pages", open: "popups", close: "popups",
  minimize: "windows", maximize: "windows", winclose: "windows", success: "tasks", error: "tasks" };
export const kindOn = k => !(prefs.sndOff || {})[k];
export async function play(ev, { force = false, pack } = {}) {
  if (!force && !soundsOn()) return;
  if (!force && /^(info|warning|critical)$/.test(ev) && !pref("alertSounds", true)) return;
  if (!force && KIND[ev] && !kindOn(KIND[ev])) return;
  try {
    ctx ||= new (window.AudioContext || window.webkitAudioContext)();
    if (ctx.state === "suspended") { await ctx.resume().catch(() => {}); if (ctx.state !== "running") return; }   // not until you've clicked once
    const b = (await buffers(pack || packId()))?.[ev]; if (!b) return;
    const src = ctx.createBufferSource(), g = ctx.createGain();
    g.gain.value = vol(); src.buffer = b; src.connect(g).connect(ctx.destination); src.start();
  } catch {}
}

/** A short buzz on phones (Android browsers; iPhones have no vibration for web pages). */
const HAPTIC = { tap: 6, toggleOn: 12, toggleOff: 10, nav: 4, back: 4, open: 8, info: [18, 50, 18], warning: [30, 60, 30], critical: [60, 50, 60, 50, 140], success: [10, 40, 16], error: [50, 40, 50] };
export function buzz(ev) {
  if (!pref("haptics", true) || !navigator.vibrate || !matchMedia("(pointer: coarse)").matches) return;
  try { navigator.vibrate(HAPTIC[ev] || 0); } catch {}
}
export const feel = (ev, o) => { play(ev, o); buzz(ev); };

// Every press makes its sound: switches say on or off, going to a page leaves that to the page change (nav/back)
document.addEventListener("click", e => {
  if (!soundsOn() && !pref("haptics", true)) return;
  const el = e.target.closest("button, a, [data-act], .row.click, .sw, label, summary, input[type=checkbox], input[type=radio], select");
  if (!el || el.closest(".wbar") || el.disabled) return;
  const act = el.dataset?.act || "";
  if (/^(go|tab):|^back$/.test(act) || el.closest('[data-act="back"]')) return;
  const sw = el.querySelector?.(".sw") || (el.classList.contains("sw") ? el : null);
  feel(sw ? (sw.classList.contains("on") ? "toggleOff" : "toggleOn") : "tap");
}, true);
// the first press of a visit wakes the sound up (browsers keep it silent until then)
addEventListener("pointerdown", () => { if (soundsOn() && !ctx) try { ctx = new (window.AudioContext || window.webkitAudioContext)(); } catch {} }, { once: true, capture: true });
