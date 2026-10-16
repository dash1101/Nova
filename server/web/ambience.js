// Nova web — ambience (Appearance → Sounds, Beta): a soft, slowly changing pad under everything, made live in the
// browser — each look has its own chords and color (Aero bright and glassy, Mica warm, Breeze open, Glass airy,
// Material clean, Default dreamy), with a breath of air and, for Aero and Glass, the odd glint on top.
// Very quiet by default, only after you've clicked once (browsers insist), and it stops while the tab is hidden.
import { prefs } from "./core.js";

const SONGS = {
  default: { wave: "sine", wave2: "triangle", cut: 1300, chords: [[48, 55, 64, 71, 74], [45, 52, 60, 67, 71], [41, 48, 57, 64, 67], [43, 50, 59, 64, 69]] },
  aero: { wave: "triangle", wave2: "sine", cut: 2200, glint: true, chords: [[50, 57, 62, 66, 73], [47, 54, 62, 66, 71], [43, 50, 59, 62, 69], [45, 52, 61, 64, 69]] },
  fluent: { wave: "sine", wave2: "sine", cut: 1000, chords: [[53, 60, 65, 69, 72], [50, 57, 62, 65, 69], [46, 53, 58, 62, 65], [48, 55, 60, 64, 67]] },
  breeze: { wave: "triangle", wave2: "triangle", cut: 1600, chords: [[52, 59, 64, 66, 71], [49, 56, 61, 64, 68], [45, 52, 57, 59, 64], [47, 54, 59, 61, 66]] },
  liquid: { wave: "sine", wave2: "sine", cut: 1800, glint: true, chords: [[55, 62, 67, 71, 74], [52, 59, 64, 67, 71], [48, 55, 62, 64, 71], [50, 57, 62, 66, 69]] },
  material: { wave: "sine", wave2: "triangle", cut: 1400, chords: [[57, 64, 69, 73, 76], [54, 61, 66, 69, 73], [50, 57, 62, 66, 69], [52, 59, 64, 68, 71]] },
};
const CHORD_S = 11;                      // each chord lasts this long, fading into the next
const hz = n => 440 * Math.pow(2, (n - 69) / 12);

let ctx = null, master = null, timer = 0, step = 0, song = "", air = null, playing = false;

export const TRACKS = { default: "Dreamy (Default)", aero: "Glassy (Aero)", fluent: "Warm (Mica)", breeze: "Open (Breeze)", liquid: "Airy (Glass)", material: "Clean (Material)" };
export const trackId = () => track();
const track = () => { const p = prefs.ambTrack || "auto"; return p === "auto" ? look() : p; };
const look = () => { const c = document.documentElement.classList; return c.contains("aero") ? "aero" : c.contains("fluent") ? "fluent" : c.contains("breeze") ? "breeze" : c.contains("liquid") ? "liquid" : c.contains("material") ? "material" : "default"; };
const level = () => Math.max(0, Math.min(100, +(prefs.ambVol ?? 35))) / 100 * .16;
export const enabled = () => !!prefs.ambience && !/[?&]embed=1/.test(location.search);

/** Start, stop or change the music to fit the settings and the look. */
export function update() {
  if (!enabled()) return stop();
  if (playing && song === track()) { master && master.gain.setTargetAtTime(level(), ctx.currentTime, .12); return; }
  start();
}

function start() {
  try { ctx ||= new (window.AudioContext || window.webkitAudioContext)(); } catch { return; }
  if (ctx.state !== "running") { ctx.resume().then(() => { if (ctx.state === "running" && enabled()) start(); }).catch(() => {}); if (ctx.state !== "running") return; }
  stop(false);                                              // the old track fades out slowly under the new one
  song = track(); playing = true; step = 0;
  master = ctx.createGain(); master.gain.value = 0; master.connect(ctx.destination);
  master.gain.setTargetAtTime(level(), ctx.currentTime + .5, 3);     // and the new one eases in over several seconds
  airStart();
  chord(); timer = setInterval(chord, CHORD_S * 1000);
}
export function stop(quick = false) {
  clearInterval(timer); timer = 0; playing = false;
  if (master && ctx) { const m = master; m.gain.cancelScheduledValues(ctx.currentTime); m.gain.setTargetAtTime(0, ctx.currentTime, quick ? .3 : 2.2); setTimeout(() => m.disconnect(), quick ? 2000 : 12000); }
  master = null; air = null;
}

/** One chord: two slightly detuned oscillators per note, through a slowly opening and closing filter, swelling in and out. */
function chord() {
  if (!master || document.hidden) return;
  const S = SONGS[song] || SONGS.default, notes = S.chords[step++ % S.chords.length], t = ctx.currentTime, len = CHORD_S + 5;
  const f = ctx.createBiquadFilter(); f.type = "lowpass"; f.Q.value = .6;
  f.frequency.setValueAtTime(S.cut * .5, t); f.frequency.linearRampToValueAtTime(S.cut, t + len * .45); f.frequency.linearRampToValueAtTime(S.cut * .45, t + len);
  const env = ctx.createGain(); env.gain.setValueAtTime(0, t); env.gain.linearRampToValueAtTime(1, t + 4.5); env.gain.setValueAtTime(1, t + len - 5); env.gain.linearRampToValueAtTime(0, t + len);
  f.connect(env).connect(master);
  notes.forEach((n, i) => {
    for (const [type, det, g] of [[S.wave, -6, .5], [S.wave2, 7, .35]]) {
      const o = ctx.createOscillator(), og = ctx.createGain();
      o.type = type; o.frequency.value = hz(n); o.detune.value = det + (Math.random() - .5) * 4;
      og.gain.value = g / notes.length * (i === 0 ? 1.3 : 1);
      o.connect(og).connect(f); o.start(t); o.stop(t + len + .1);
    }
  });
  if (S.glint) for (let k = 0; k < 3; k++) {                    // the odd high glint, like light catching glass
    const at = t + 2 + Math.random() * (CHORD_S - 3), o = ctx.createOscillator(), g = ctx.createGain();
    o.type = "sine"; o.frequency.value = hz(notes[1 + (Math.random() * 4 | 0)] + 24);
    g.gain.setValueAtTime(0, at); g.gain.linearRampToValueAtTime(.05, at + .4); g.gain.exponentialRampToValueAtTime(.0005, at + 4);
    o.connect(g).connect(master); o.start(at); o.stop(at + 4.2);
  }
}

/** A breath of air: soft noise through a gently moving band. */
function airStart() {
  const n = ctx.sampleRate * 4, b = ctx.createBuffer(1, n, ctx.sampleRate), d = b.getChannelData(0);
  for (let i = 0; i < n; i++) d[i] = Math.random() * 2 - 1;
  const src = ctx.createBufferSource(); src.buffer = b; src.loop = true;
  const bp = ctx.createBiquadFilter(); bp.type = "bandpass"; bp.frequency.value = 1400; bp.Q.value = .7;
  const lfo = ctx.createOscillator(), lg = ctx.createGain(); lfo.frequency.value = .05; lg.gain.value = 700; lfo.connect(lg).connect(bp.frequency); lfo.start();
  const g = ctx.createGain(); g.gain.value = .05;
  src.connect(bp).connect(g).connect(master); src.start();
  air = src;
}

document.addEventListener("visibilitychange", () => { if (!enabled()) return; if (document.hidden) stop(); else update(); });
// browsers keep sound off until the first click: start then, if it's on
addEventListener("pointerdown", () => { if (enabled() && !playing) update(); }, { capture: true });
