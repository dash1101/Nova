// Nova web — living wallpaper (Appearance → Wallpaper, Beta): an animated wallpaper for each look, in your accent color.
// Aero: glowing ribbons of light and drifting bokeh. Mica: a slowly turning bloom. Breeze: flowing waves. Glass: liquid
// color moving under the glass. Material: soft shapes floating by. Default: a drifting nebula with a few stars.
// Drawn small (a quarter of the screen) at 30 frames a second and scaled up soft, paused when the page is hidden, still
// with Reduce motion — so it stays light. A picture you set (Wallpaper) shows instead.
import { prefs } from "./core.js";

let cv = null, g = null, raf = 0, last = 0, t0 = performance.now(), scene = "", pal = null;
const DPR = .3;                                    // drawing scale: soft scenes look the same, at a tenth of the cost

const root = () => document.documentElement;
const look = () => { const c = root().classList; return c.contains("aero") ? "aero" : c.contains("fluent") ? "fluent" : c.contains("breeze") ? "breeze" : c.contains("liquid") ? "liquid" : c.contains("material") ? "material" : "default"; };
const dark = () => !root().classList.contains("l-light");

function hex2hsl(hex) {
  const m = /^#?([0-9a-f]{6})$/i.exec(String(hex).trim()); const n = m ? parseInt(m[1], 16) : 0x3e91ff;
  const r = (n >> 16) / 255, gg = (n >> 8 & 255) / 255, b = (n & 255) / 255, mx = Math.max(r, gg, b), mn = Math.min(r, gg, b), l = (mx + mn) / 2, d = mx - mn;
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
  let h = d === 0 ? 0 : mx === r ? ((gg - b) / d) % 6 : mx === gg ? (b - r) / d + 2 : (r - gg) / d + 4;
  return [(h * 60 + 360) % 360, s * 100, l * 100];
}
const hsl = (h, s, l, a = 1) => `hsla(${(h % 360 + 360) % 360},${s}%,${l}%,${a})`;

/** Colors for this frame's scene from the accent and light/dark. */
function palette() {
  const acc = getComputedStyle(root()).getPropertyValue("--blue").trim() || "#3e91ff";
  const [h, s0] = hex2hsl(acc), s = Math.max(35, Math.min(90, s0)), d = dark();
  return { h, s, d, bg: getComputedStyle(root()).getPropertyValue("--bg").trim() || (d ? "#000" : "#f2f2f7") };
}

export function enabled() {
  return !!prefs.livingWall && !/[?&]embed=1/.test(location.search) && !root().classList.contains("has-wall") && !root().classList.contains("solid");
}

/** Start, stop or change scene to match the settings (called whenever the look changes). */
export function update() {
  const on = enabled();
  root().classList.toggle("living", on);
  if (!on) { cancelAnimationFrame(raf); raf = 0; cv?.remove(); cv = null; return; }
  if (!cv) {
    cv = document.createElement("canvas"); cv.id = "live"; cv.setAttribute("aria-hidden", "true");
    document.body.prepend(cv); g = cv.getContext("2d", { alpha: false });
    addEventListener("resize", size); size();
  }
  scene = look(); pal = palette();
  cv.style.filter = scene === "material" ? "none" : `blur(${scene === "fluent" ? 12 : scene === "liquid" ? 18 : 10}px)`;
  if (!raf) raf = requestAnimationFrame(frame);
}
function size() { if (!cv) return; cv.width = Math.max(64, Math.round(innerWidth * DPR)); cv.height = Math.max(64, Math.round(innerHeight * DPR)); }

function frame(now) {
  raf = requestAnimationFrame(frame);
  if (document.hidden || now - last < 33) return;                          // 30 frames a second is plenty
  last = now;
  const still = root().classList.contains("reduce");
  const t = still ? 20 : (now - t0) / 1000 * Math.max(.25, (+prefs.animSpeed || 100) / 100);
  draw(t);
  if (still) { cancelAnimationFrame(raf); raf = 0; }                       // Reduce motion: one still frame
}

const W = () => cv.width, H = () => cv.height;
function blob(x, y, r, c0, c1 = "transparent") {
  const gr = g.createRadialGradient(x, y, 0, x, y, r); gr.addColorStop(0, c0); gr.addColorStop(1, c1);
  g.fillStyle = gr; g.beginPath(); g.arc(x, y, r, 0, Math.PI * 2); g.fill();
}

function draw(t) {
  const { h, s, d } = pal, w = W(), hh = H(), m = Math.max(w, hh);
  g.globalCompositeOperation = "source-over";
  ({
    // a drifting nebula: three soft clouds in the accent and its neighbors, and a few stars
    default() {
      g.fillStyle = d ? hsl(h, s * .5, 4) : hsl(h, s * .4, 95); g.fillRect(0, 0, w, hh);
      g.globalCompositeOperation = d ? "lighter" : "source-over";
      const cl = [[h, .55, .35, 47, 61], [h + 50, .45, .7, 59, 43], [h - 40, .3, .55, 71, 53]];
      cl.forEach(([hu, x0, y0, px, py], i) => blob(w * (x0 + .18 * Math.sin(t / px * 6.28 + i)), hh * (y0 + .16 * Math.cos(t / py * 6.28 + i * 2)), m * .55,
        d ? hsl(hu, s, 45, .55) : hsl(hu, s, 72, .42)));
      if (d) { g.globalCompositeOperation = "lighter"; for (let i = 0; i < 26; i++) { const x = (i * .6180339 % 1) * w, y = (i * .5377 % 1) * hh, a = .25 + .25 * Math.sin(t * (.6 + i % 5 * .2) + i);
        g.fillStyle = `rgba(255,255,255,${a.toFixed(3)})`; g.fillRect(x, y, 1.2, 1.2); } }
    },
    // Windows 7 "Harmony": wide ribbons of light curving across, glowing where they cross, and bokeh drifting up
    aero() {
      const bg = g.createLinearGradient(0, 0, w, hh);
      bg.addColorStop(0, d ? hsl(h, s * .9, 14) : hsl(h, s * .8, 62)); bg.addColorStop(1, d ? hsl(h + 10, s, 5) : hsl(h + 10, s * .9, 40));
      g.fillStyle = bg; g.fillRect(0, 0, w, hh);
      g.globalCompositeOperation = "lighter"; g.lineCap = "round";
      for (let i = 0; i < 5; i++) {
        const ph = t * (.05 + i * .012) + i * 1.7, y0 = hh * (.25 + i * .13);
        const lg = g.createLinearGradient(0, 0, w, 0);
        lg.addColorStop(0, hsl(h + i * 8, s, 70, 0)); lg.addColorStop(.5, hsl(h + i * 8 - 10, s * .7, 78, .22)); lg.addColorStop(1, hsl(h + i * 8, s, 70, 0));
        g.strokeStyle = lg; g.lineWidth = m * (.05 + .03 * Math.sin(ph * 1.3));
        g.beginPath(); g.moveTo(-w * .1, y0 + Math.sin(ph) * hh * .2);
        g.bezierCurveTo(w * .3, y0 - hh * .35 * Math.cos(ph * .8), w * .65, y0 + hh * .35 * Math.sin(ph * .9), w * 1.1, y0 - hh * .15 * Math.cos(ph));
        g.stroke();
        g.lineWidth *= .18; g.strokeStyle = `rgba(255,255,255,${d ? .12 : .2})`; g.stroke();
      }
      for (let i = 0; i < 14; i++) {                                         // bokeh
        const sp = .008 + (i % 4) * .004, y = (1.1 - ((t * sp + i * .137) % 1.2)) * hh, x = w * ((i * .618) % 1) + Math.sin(t * .2 + i) * w * .03;
        blob(x, y, m * (.02 + (i % 3) * .015), `rgba(255,255,255,${d ? .14 : .22})`);
      }
    },
    // Windows 11 bloom: soft petals turning slowly round the middle, breathing
    fluent() {
      g.fillStyle = d ? hsl(h, s * .35, 8) : hsl(h, s * .3, 93); g.fillRect(0, 0, w, hh);
      const cx = w * .55, cy = hh * .55, n = 7;
      for (let i = 0; i < n; i++) {                       // petals layered over each other, not added (that went muddy)
        const a = t * .03 + i / n * Math.PI * 2, br = 1 + .08 * Math.sin(t * .4 + i);
        g.save(); g.translate(cx, cy); g.rotate(a); g.scale(1, .42);
        blob(m * .22 * br, 0, m * .3 * br, d ? hsl(h + (i - 3) * 7, s, 54, .5) : hsl(h + (i - 3) * 9, s, 62, .38));
        g.restore();
      }
      blob(cx, cy, m * .2, d ? hsl(h + 15, s * .7, 70, .3) : hsl(h + 15, s * .5, 92, .65));
    },
    // KDE Plasma: layered waves flowing across from the left, darker toward the back
    breeze() {
      const bg = g.createLinearGradient(0, 0, 0, hh);
      bg.addColorStop(0, d ? hsl(h, s * .5, 12) : hsl(h, s * .5, 90)); bg.addColorStop(1, d ? hsl(h, s * .6, 6) : hsl(h, s * .55, 76));
      g.fillStyle = bg; g.fillRect(0, 0, w, hh);
      for (let k = 0; k < 5; k++) {
        const y0 = hh * (.45 + k * .1), amp = hh * (.08 - k * .008), sp = .12 + k * .05;
        g.fillStyle = d ? hsl(h - k * 6, s * (.6 + k * .08), 18 + k * 7, .55) : hsl(h - k * 6, s * (.5 + k * .1), 75 - k * 7, .55);
        g.beginPath(); g.moveTo(0, hh);
        for (let x = 0; x <= w; x += 4) g.lineTo(x, y0 + Math.sin(x / w * (3 + k * .6) + t * sp + k) * amp + Math.sin(x / w * 7 - t * sp * .7) * amp * .3);
        g.lineTo(w, hh); g.closePath(); g.fill();
      }
    },
    // Glass: liquid color moving under the glass, colors mixing as they pass
    liquid() {
      g.fillStyle = d ? hsl(h, s * .4, 6) : hsl(h, s * .3, 94); g.fillRect(0, 0, w, hh);
      g.globalCompositeOperation = d ? "screen" : "multiply";
      const b = [[h, 0], [h + 40, 1], [h - 50, 2], [h + 160, 3], [h + 20, 4]];
      b.forEach(([hu, i]) => {
        const x = w * (.5 + .38 * Math.sin(t * (.05 + i * .013) + i * 1.3)), y = hh * (.5 + .38 * Math.cos(t * (.04 + i * .011) + i * 2.1));
        blob(x, y, m * (.38 + .06 * Math.sin(t * .3 + i)), d ? hsl(hu, s, 50, .55) : hsl(hu, s * .9, 74, .6));
      });
    },
    // Material You: soft shapes in the accent's tones floating by — circles, rounded squares, flowers
    material() {
      g.fillStyle = d ? hsl(h, s * .25, 7) : hsl(h, s * .35, 96); g.fillRect(0, 0, w, hh);
      const tone = (k, a) => d ? hsl(h + k * 12, s * .45, 22 + k * 5, a) : hsl(h + k * 12, s * .6, 86 - k * 5, a);
      for (let i = 0; i < 9; i++) {
        const x = ((i * .37 + t * (.004 + i % 3 * .002)) % 1.3 - .15) * w, y = hh * ((i * .29) % 1) + Math.sin(t * .15 + i) * hh * .04, r = m * (.06 + (i % 4) * .025);
        g.save(); g.translate(x, y); g.rotate(t * .05 * (i % 2 ? 1 : -1) + i); g.fillStyle = tone(i % 4, .9);
        g.beginPath();
        if (i % 3 === 0) g.arc(0, 0, r, 0, Math.PI * 2);
        else if (i % 3 === 1) g.roundRect(-r, -r, 2 * r, 2 * r, r * .45);
        else for (let k = 0; k <= 64; k++) { const a = k / 64 * Math.PI * 2, rr = r * (1 + .14 * Math.cos(a * 6)); g[k ? "lineTo" : "moveTo"](Math.cos(a) * rr, Math.sin(a) * rr); }
        g.fill(); g.restore();
      }
    },
  })[scene]?.();
}

// the accent, the look or light/dark changed: new colors (the app calls update() from applyTheme)
document.addEventListener("visibilitychange", () => { if (!document.hidden && cv && !raf) raf = requestAnimationFrame(frame); });
