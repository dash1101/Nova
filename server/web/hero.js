// The home-screen server (tower case, glass side, live ARGB fan, drive LEDs, power LED in the
// status color) and the big Lighting fan — the same drawing as the app's Hero.kt, on a canvas.
import { S } from "./core.js";
import { isSoftware, frame } from "./fx.js";

const RAINBOW = ["#ff3b30", "#ff9500", "#ffcc00", "#34c759", "#00c7be", "#007aff", "#af52de", "#ff3b30"];
const reduce = () => document.documentElement.classList.contains("reduce");

export function fanLook(f) {
  const ov = f?.status_override;
  return {
    on: ov ? ov.on !== false : f?.on !== false,
    effect: ov?.effect || f?.effect || "static",
    color: ov?.color || f?.color || "#3e91ff",
    color2: f?.color2 || "#bf5af2",
    brightness: Math.max(0, Math.min(1, (ov?.brightness ?? f?.brightness ?? 50) / 100)),
    speed: ov?.speed ?? f?.speed ?? 50,
    rainbow: f?.rainbow !== false,
    leds: Math.max(4, Math.min(40, f?.led_count ?? 12)),
    palette: ov ? [] : f?.palette || [],
  };
}
const dark = () => getComputedStyle(document.documentElement).colorScheme !== "light";

const periodMs = s => 10000 * Math.pow(.02, (Math.max(1, Math.min(100, s)) - 1) / 99);      // same as the server
const hsv = h => { h = ((h % 1) + 1) % 1; const i = Math.floor(h * 6), f = h * 6 - i, q = 1 - f;
  const [r, g, b] = [[1, f, 0], [q, 1, 0], [0, 1, f], [0, q, 1], [f, 0, 1], [1, 0, q]][i % 6]; return [r * 255, g * 255, b * 255]; };
const hexRgb = h => { const n = parseInt((h || "#3e91ff").slice(1), 16); return [n >> 16 & 255, n >> 8 & 255, n & 255]; };
const mix = (a, b, k) => a.map((v, i) => v + (b[i] - v) * k);
const CYC = [[255, 0, 0], [255, 127, 0], [255, 255, 0], [0, 255, 0], [0, 0, 255], [75, 0, 130], [143, 0, 255]];
/** LED j of n at server time t (ms): the wave matches the real fan frame for frame (Nova draws it from
 *  the clock); pulse/blink/cycle/random run on the board's own clock, so their speed matches. */
function ledColor(look, j, n, t, reverse) {
  const per = periodMs(look.speed), jj = reverse ? (n - j) % n : j, col = hexRgb(look.color);
  if (isSoftware(look)) {                // Nova's animator draws these from the clock: identical maths
    const c = frame({ effect: look.effect, leds: n, speed: look.speed, rainbow: look.rainbow, palette: look.palette, color: look.color, color2: look.color2 }, t / 1000)[jj];
    const m = Math.max(1, ...c); return [c.map(v => v / m * 255), m / 255];
  }
  switch (look.effect) {
    case "wave": { const ph = (t / (per * 1.5)) % 1;
      if (look.rainbow) return [hsv(jj / n + ph), 1];
      const x = (Math.cos(2 * Math.PI * (jj / n - ph)) + 1) / 2; return [col, .08 + .92 * x ** 3]; }
    case "gradient": return [mix(col, hexRgb(look.color2), n > 1 ? jj / (n - 1) : 0), 1];
    case "pulse": { const hold = Math.max(50, per / 5), p = (t % (per + hold)) / per; return [col, .05 + .95 * (p >= 1 ? 0 : 1 - Math.abs(2 * p - 1))]; }
    case "blink": return [col, t % (Math.max(200, per) + 200) < 100 ? 1 : .04];
    case "cycle": { const p = Math.max(400, per), k = (t / p) % 7, i = Math.floor(k);
      return [look.rainbow ? mix(CYC[i], CYC[(i + 1) % 7], Math.max(0, Math.min(1, (k % 1) * 3 - 2))) : col, 1]; }
    case "random": { const slot = Math.floor(t / Math.max(30, Math.min(1000, per / 10))); return [hsv(((slot * 7919 + jj * 104729) % 997) / 997), 1]; }
  }
  return [col, 1];
}
const rgbA = ([r, g, b], a) => `rgba(${r | 0},${g | 0},${b | 0},${a})`;
/** The fan ring: a continuous glowing strip, the LEDs' colors blended round it (LED 0 at the top). */
function ring(c, x, y, r, look, t, spin, isDark, reverse, blades = true) {
  c.save();
  c.fillStyle = isDark ? "#151518" : "#2a2a30"; c.beginPath(); c.arc(x, y, r * 1.12, 0, 7); c.fill();
  if (look.on) {
    const n = look.leds, a0 = .25 + .75 * look.brightness, g = c.createConicGradient(-Math.PI / 2, x, y);
    const stops = [...Array(n).keys()].map(j => { const [col, k] = ledColor(look, j, n, t, reverse); return rgbA(col, Math.max(.03, Math.min(1, a0 * k))); });
    stops.forEach((st, j) => g.addColorStop(j / n, st)); g.addColorStop(1, stops[0]);
    c.strokeStyle = g;
    for (let i = 6; i >= 1; i--) { c.globalAlpha = .07 * (7 - i) / 6; c.lineWidth = r * .08 * i; c.beginPath(); c.arc(x, y, r * (1 + i * .06), 0, 7); c.stroke(); }
    c.globalAlpha = 1; c.lineWidth = r * .12; c.beginPath(); c.arc(x, y, r, 0, 7); c.stroke();
  } else { c.strokeStyle = "#2c2c30"; c.lineWidth = r * .12; c.beginPath(); c.arc(x, y, r, 0, 7); c.stroke(); }
  c.fillStyle = isDark ? "#0e0e10" : "#1e1e22"; c.beginPath(); c.arc(x, y, r * .93, 0, 7); c.fill();
  if (!blades) { c.restore(); return; }
  c.translate(x, y); c.fillStyle = "#3a3a40";
  // motion blur: a few fading copies just behind the blades, so the spin reads smooth
  for (const [d, al] of spin ? [[-7.5, .12], [-5, .2], [-2.5, .35], [0, 1]] : [[0, 1]]) {
    c.save(); c.globalAlpha = al; c.rotate((spin + d) * Math.PI / 180);
    for (let b = 0; b < 7; b++) {
      c.rotate(2 * Math.PI / 7); c.beginPath(); c.moveTo(0, -r * .2);
      c.bezierCurveTo(r * .55, -r * .35, r * .62, -r * .78, r * .12, -r * .86);
      c.bezierCurveTo(r * .25, -r * .55, r * .1, -r * .32, 0, -r * .2); c.fill();
    }
    c.restore();
  }
  c.globalAlpha = 1; c.restore();
  c.fillStyle = "#2e2e33"; c.beginPath(); c.arc(x, y, r * .22, 0, 7); c.fill();
  c.strokeStyle = "#45454b"; c.lineWidth = r * .02; c.stroke();
}
function glow(look, t, reverse) {
  let s = [0, 0, 0]; for (let j = 0; j < look.leds; j++) { const [c, k] = ledColor(look, j, look.leds, t, reverse); s = s.map((v, i) => v + c[i] * k); }
  return s.map(v => Math.min(255, v / look.leds));
}
/** How often a drive's light blinks, in % of 110 ms slots: from its real activity (the server's
 *  share of time spent on I/O, and bytes moved), or a gentle flicker when the server doesn't say. */
function blinkRate(io) {
  if (!io) return 9;
  const [busy, bps] = io;
  return busy > 0 || bps > 0 ? Math.min(85, 6 + 80 * Math.sqrt(busy)) : 0;
}
/** A healthy drive's LED: quick blips that fade, like disk activity (deterministic per LED). */
function activity(i, t, pct = 9) {
  const slot = 110, now = Math.floor(t / slot); let v = 0;
  for (let k = 0; k < 5; k++) { const s = now - k, h = (Math.imul(s, 2654435761) ^ Math.imul(i, 40503) ^ (s >> 3)) >>> 0;
    if (h % 100 < pct) v = Math.max(v, Math.exp(-(t - s * slot) / 160)); }
  return v;
}

function rr(c, x, y, w, h, r) { c.beginPath(); c.roundRect(x, y, w, h, r); }

/** The home picture comes in a few shapes, so it can look like the machine Nova runs on (Settings → Appearance → "Your
 *  server looks like"): each one is a layout — where its light, its LED strips, one LED per drive and the power LED sit —
 *  plus a body. The live parts (the light, the strips, the drive LEDs, the power LED) are drawn the same way on all of them,
 *  and taps land by the same layout. Same shapes and numbers as the app's Hero.kt. */
export const LOOKS = [["tower", "Tower"], ["mini", "Mini PC"], ["nas", "NAS"], ["rack", "Rack server"], ["laptop", "Laptop"]];
function rowLeds(n, cx, y, span, maxStep) {
  const step = n > 1 ? Math.min(maxStep, span / (n - 1)) : 0, x0 = cx - step * (n - 1) / 2;
  return { leds: [...Array(n).keys()].map(i => [x0 + i * step, y]), step };
}
function layout(model, w, h, n) {
  if (model === "mini") {
    const bw = Math.min(w * .8, h * 1.5), fh = bw * .3, th = bw * .17, left = (w - bw) / 2, top = (h - fh - th) / 2, fy = top + th;
    const { leds, step } = rowLeds(n, left + bw * .2, fy + fh * .46, bw * .22, bw * .04);
    return { model, left, top, bw, bh: fh + th, th, fy, fh,
      fan: { x: left + bw / 2, y: top + th * .5, r: bw * .15, squash: (th * .78) / (bw * .3), blades: true },
      strips: [{ x: left + bw * .06, y: fy + fh * .8, w: bw * .88, h: fh * .07, base: "#202026" }],
      leds, lr: Math.max(bw * .006, Math.min(bw * .011, step * .3)), power: { x: left + bw * .9, y: fy + fh * .46, r: bw * .011 },
      hit: { power: [left + bw * .9, fy + fh * .46, bw * .05], drives: [left + bw * .06, fy + fh * .25, left + bw * .34, fy + fh * .68], fan: [[left, top, left + bw, top + th * 1.05], [left + bw * .05, fy + fh * .72, left + bw * .95, fy + fh * .95]] } };
  }
  if (model === "nas") {
    const bw = Math.min(w * .58, h * .64), bh = bw * 1.1, left = (w - bw) / 2, top = (h - bh) / 2;
    const bays = Math.max(2, Math.min(8, n)), ax = left + bw * .07, aw = bw * .86, gap = aw * .025, bayW = (aw - gap * (bays - 1)) / bays;
    const ay = top + bh * .2, ah = bh * .72;
    const leds = [...Array(Math.min(n, bays)).keys()].map(i => [ax + i * (bayW + gap) + bayW / 2, ay + ah * .08]);
    return { model, left, top, bw, bh, bays, ax, aw, gap, bayW, ay, ah,
      bar: { x: left + bw * .36, y: top + bh * .075, w: bw * .56, h: bh * .03 },
      strips: [], leds, lr: Math.max(bw * .008, Math.min(bw * .014, bayW * .09)), power: { x: left + bw * .13, y: top + bh * .09, r: bw * .016 },
      hit: { power: [left + bw * .13, top + bh * .09, bw * .07], drives: [ax, ay, ax + aw, ay + ah], fan: [[left + bw * .32, top + bh * .03, left + bw * .96, top + bh * .15]] } };
  }
  if (model === "rack") {
    const bw = Math.min(w * .92, h * 2.1), lid = bw * .12, bh = bw * .3, left = (w - bw) / 2, top = (h - bh - lid) / 2 + lid, ear = bw * .035;
    const bays = Math.max(2, Math.min(12, n)), cols = Math.ceil(bays / 2), ax = left + ear + bw * .03, aw = bw * .54, gap = bw * .008;
    const bayW = (aw - gap * (cols - 1)) / cols, rowH = bh * .3, ay = top + bh * .14;
    const leds = [...Array(Math.min(n, bays)).keys()].map(i => { const col = i % cols, row = Math.floor(i / cols);
      return [ax + col * (bayW + gap) + bayW * .86, ay + row * (rowH + bh * .05) + rowH / 2]; });
    const fx = left + bw * .77, fy = top + bh * .45;
    return { model, left, top, bw, bh, lid, ear, bays, cols, ax, aw, gap, bayW, rowH, ay,
      fan: { x: fx, y: fy, r: bh * .27, blades: true },
      strips: [{ x: ax, y: top + bh * .83, w: bw - 2 * ear - bw * .06, h: bh * .05, base: "#202026" }],
      leds, lr: Math.max(bw * .0035, Math.min(bw * .0065, bayW * .05)), power: { x: left + bw - ear - bw * .045, y: top + bh * .3, r: bw * .007 },
      hit: { power: [left + bw - ear - bw * .045, top + bh * .3, bw * .035], drives: [ax, ay, ax + aw, ay + rowH * 2 + bh * .05],
        fan: [[fx - bh * .4, top, fx + bh * .4, top + bh * .8], [ax, top + bh * .8, left + bw - ear, top + bh * .92]] } };
  }
  if (model === "laptop") {
    const lw = Math.min(w * .72, h * .8 / .82), lh = lw * .64, bh = lw * .14, ex = lw * .08, left = (w - lw) / 2, top = (h - lh - bh) / 2, by = top + lh;
    const { leds, step } = rowLeds(n, left + lw * .1, by + bh * .86, lw * .14, lw * .028);
    return { model, left, top, bw: lw, bh: lh + bh, lw, lh, by, dh: bh, ex,
      fan: { x: left + lw / 2, y: top + lh * .48, r: lh * .22, blades: false },
      strips: [{ x: left + lw * .14, y: by + bh * .16, w: lw * .72, h: bh * .4, base: "#18181c", keys: true }],
      leds, lr: Math.max(lw * .004, Math.min(lw * .008, step * .3)), power: { x: left + lw * .97, y: by + bh * .86, r: lw * .008 },
      hit: { power: [left + lw * .97, by + bh * .86, lw * .04], drives: [left - ex * .3, by + bh * .7, left + lw * .24, by + bh * 1.05],
        fan: [[left, top, left + lw, top + lh], [left + lw * .1, by, left + lw * .9, by + bh * .62]] } };
  }
  const cw = Math.min(w * .56, h * .62), ch = cw * 1.42, left = (w - cw) / 2, top = (h - ch) / 2;     // tower (the original)
  const { leds, step } = rowLeds(n, left + cw / 2, top + ch * .83, cw * .62, cw * .09);
  return { model: "tower", left, top, bw: cw, bh: ch, cw, ch,
    fan: { x: left + cw / 2, y: top + ch * .33, r: cw * .29, blades: true },
    strips: [{ x: left + cw * .16, y: top + ch * .62, w: cw * .68, h: ch * .07, base: "#26262c" }, { x: left + cw * .16, y: top + ch * .72, w: cw * .68, h: ch * .05, base: "#1f1f25" }],
    leds, lr: Math.max(cw * .008, Math.min(cw * .016, step * .32)), power: { x: left + cw * .5, y: top + ch * .935, r: cw * .016 },
    hit: { power: [left + cw * .5, top + ch * .935, cw * .07], drives: [left + cw * .12, top + ch * .785, left + cw * .88, top + ch * .875],
      fan: [[left + cw * .14, top + ch * .6, left + cw * .86, top + ch * .78]], fanCircle: [left + cw / 2, top + ch * .33, cw * .36] } };
}

const shade = (c, x0, y0, x1, y1, stops) => { const g = c.createLinearGradient(x0, y0, x1, y1); stops.forEach(([o, col]) => g.addColorStop(o, col)); return g; };
function body(c, L, isDark) {
  const { left, top, bw, bh } = L;
  c.fillStyle = `rgba(0,0,0,${isDark ? .6 : .18})`; c.beginPath(); c.ellipse(left + bw / 2, top + bh, bw * .46, Math.max(4, bh * .03), 0, 0, 7); c.fill();
  if (L.model === "mini") {
    const { th, fy, fh } = L, inset = bw * .05;
    c.fillStyle = shade(c, 0, top, 0, fy, [[0, "#55555e"], [1, "#3a3a42"]]);       // the lid, seen from a little above
    c.beginPath(); c.moveTo(left + inset, top); c.lineTo(left + bw - inset, top); c.lineTo(left + bw, fy); c.lineTo(left, fy); c.closePath(); c.fill();
    c.strokeStyle = "rgba(255,255,255,.12)"; c.lineWidth = 1.5; c.stroke();
    c.fillStyle = "rgba(0,0,0,.35)"; c.beginPath(); c.ellipse(L.fan.x, L.fan.y, L.fan.r * 1.18, L.fan.r * 1.18 * L.fan.squash, 0, 0, 7); c.fill();   // the vent the fan sits in
    c.fillStyle = shade(c, 0, fy, 0, fy + fh, [[0, "#2c2c33"], [1, "#1b1b20"]]); rr(c, left, fy, bw, fh, [0, 0, bw * .035, bw * .035]); c.fill();
    c.strokeStyle = "rgba(255,255,255,.08)"; c.stroke();
    c.fillStyle = "#0d0d10";                                                             // ports: two USB, a USB-C, a headphone jack
    [[.52, .07], [.6, .07], [.68, .045]].forEach(([x, wd]) => { rr(c, left + bw * x, fy + fh * .38, bw * wd, fh * .17, 2); c.fill(); });
    c.beginPath(); c.arc(left + bw * .77, fy + fh * .465, fh * .07, 0, 7); c.fill();
    return;
  }
  if (L.model === "nas") {
    c.fillStyle = shade(c, 0, top, 0, top + bh, [[0, "#3c3c44"], [1, "#1c1c21"]]); rr(c, left, top, bw, bh, bw * .05); c.fill();
    c.strokeStyle = "rgba(255,255,255,.10)"; c.lineWidth = 2; c.stroke();
    c.fillStyle = "#121216"; rr(c, L.bar.x - bw * .02, L.bar.y - bh * .02, L.bar.w + bw * .04, L.bar.h + bh * .04, bh * .03); c.fill();
    for (let i = 0; i < L.bays; i++) {                                                  // drive trays: a front with a grip
      const x = L.ax + i * (L.bayW + L.gap);
      c.fillStyle = shade(c, 0, L.ay, 0, L.ay + L.ah, [[0, "#2b2b32"], [1, "#202026"]]); rr(c, x, L.ay, L.bayW, L.ah, Math.min(8, L.bayW * .12)); c.fill();
      c.strokeStyle = "rgba(255,255,255,.06)"; c.lineWidth = 1; c.stroke();
      c.fillStyle = "#16161a"; for (let k = 0; k < 6; k++) { rr(c, x + L.bayW * .22, L.ay + L.ah * (.3 + k * .07), L.bayW * .56, L.ah * .025, 2); c.fill(); }
      c.fillStyle = "#3a3a42"; rr(c, x + L.bayW * .15, L.ay + L.ah * .86, L.bayW * .7, L.ah * .05, 4); c.fill();
    }
    return;
  }
  if (L.model === "rack") {
    const { ear, lid } = L, inset = bw * .04;
    c.fillStyle = shade(c, 0, top - lid, 0, top, [[0, "#4c4c55"], [1, "#34343c"]]);          // the lid, seen from a little above
    c.beginPath(); c.moveTo(left + ear + inset, top - lid); c.lineTo(left + bw - ear - inset, top - lid); c.lineTo(left + bw - ear, top); c.lineTo(left + ear, top); c.closePath(); c.fill();
    c.strokeStyle = "rgba(255,255,255,.10)"; c.lineWidth = 1; c.stroke();
    c.strokeStyle = "rgba(0,0,0,.25)"; for (let k = 1; k < 6; k++) { const yy = top - lid + lid * k / 6, ins = inset * (1 - k / 6);
      c.beginPath(); c.moveTo(left + ear + ins + bw * .02, yy); c.lineTo(left + bw - ear - ins - bw * .02, yy); c.stroke(); }   // vent lines
    c.fillStyle = "#2e2e35"; [left, left + bw - ear].forEach(x => { rr(c, x, top - bh * .04, ear, bh * 1.08, 3); c.fill(); });
    c.fillStyle = "#121216"; [left, left + bw - ear].forEach(x => [.12, .88].forEach(y => { c.beginPath(); c.arc(x + ear / 2, top + bh * y, ear * .18, 0, 7); c.fill(); }));
    c.fillStyle = shade(c, 0, top, 0, top + bh, [[0, "#404049"], [1, "#1e1e23"]]); rr(c, left + ear, top, bw - 2 * ear, bh, 4); c.fill();
    c.strokeStyle = "rgba(255,255,255,.10)"; c.lineWidth = 1.5; c.stroke();
    for (let i = 0; i < L.bays; i++) {
      const col = i % L.cols, row = Math.floor(i / L.cols), x = L.ax + col * (L.bayW + L.gap), y = L.ay + row * (L.rowH + bh * .05);
      c.fillStyle = "#24242a"; rr(c, x, y, L.bayW, L.rowH, 3); c.fill();
      c.fillStyle = "#18181c"; rr(c, x + L.bayW * .08, y + L.rowH * .3, L.bayW * .62, L.rowH * .4, 2); c.fill();
    }
    c.fillStyle = "rgba(0,0,0,.45)";                                                     // the grille around the fan
    for (let gy = top + bh * .12; gy < top + bh * .78; gy += bh * .07)
      for (let gx = L.fan.x - bh * .42; gx < L.fan.x + bh * .42; gx += bh * .07) { c.beginPath(); c.arc(gx, gy, bh * .014, 0, 7); c.fill(); }
    return;
  }
  if (L.model === "laptop") {
    const { lw, lh, by, dh, ex } = L;
    c.fillStyle = shade(c, left, by, left, by + dh, [[0, "#5a5a63"], [1, "#2a2a30"]]);   // the deck, seen from a little above
    c.beginPath(); c.moveTo(left, by); c.lineTo(left + lw, by); c.lineTo(left + lw + ex, by + dh * .82); c.lineTo(left - ex, by + dh * .82); c.closePath(); c.fill();
    c.fillStyle = "#1e1e23"; c.beginPath(); c.moveTo(left - ex, by + dh * .82); c.lineTo(left + lw + ex, by + dh * .82);
    c.lineTo(left + lw + ex * .96, by + dh); c.lineTo(left - ex * .96, by + dh); c.closePath(); c.fill();                  // the front edge
    c.fillStyle = "rgba(255,255,255,.12)"; rr(c, left + lw * .4, by + dh * .6, lw * .2, dh * .14, 3); c.fill();       // trackpad
    c.fillStyle = shade(c, 0, top, 0, by, [[0, "#3a3a42"], [1, "#24242a"]]); rr(c, left, top, lw, lh, lw * .03); c.fill();   // the lid
    c.strokeStyle = "rgba(255,255,255,.10)"; c.lineWidth = 1.5; c.stroke();
    const b = lw * .03; c.fillStyle = shade(c, left, top, left + lw, by, [[0, "#0c1020"], [1, "#141a2a"]]); rr(c, left + b, top + b, lw - 2 * b, lh - 2 * b, lw * .012); c.fill();
    c.fillStyle = "#0a0a0c"; c.beginPath(); c.arc(left + lw / 2, top + b / 2, b * .18, 0, 7); c.fill();               // camera
    return;
  }
  const { cw, ch } = L;                                                                   // tower
  c.fillStyle = shade(c, 0, top, 0, top + ch, [[0, "#4a4a52"], [.5, "#26262b"], [1, "#1b1b1f"]]); rr(c, left, top, cw, ch, cw * .07); c.fill();
  c.strokeStyle = "rgba(255,255,255,.10)"; c.lineWidth = 2.5; c.stroke();
  const gp = cw * .07;
  c.fillStyle = shade(c, left, top, left + cw, top + ch, [[0, "#101014"], [1, "#17171c"]]); rr(c, left + gp, top + gp, cw - 2 * gp, ch - 2 * gp * 1.6, cw * .04); c.fill();
}
function glare(c, L) {
  const { left, top } = L;
  if (L.model === "tower") { const { cw, ch } = L, gp = cw * .07;
    c.fillStyle = shade(c, left, top, left + cw * .5, top + ch * .5, [[0, "rgba(255,255,255,.07)"], [1, "rgba(255,255,255,0)"]]);
    rr(c, left + gp, top + gp, cw - 2 * gp, ch - 2 * gp * 1.6, cw * .04); c.fill(); }
  if (L.model === "laptop") { const { lw, lh } = L, b = lw * .03;
    c.fillStyle = shade(c, left, top, left + lw * .5, top + lh * .6, [[0, "rgba(255,255,255,.06)"], [1, "rgba(255,255,255,0)"]]);
    rr(c, left + b, top + b, lw - 2 * b, lh - 2 * b, lw * .012); c.fill(); }
}

function drawServer(c, w, h, look, t, spin, status, drives, reverse, model) {
  const isDark = dark(), list = drives && drives.length ? drives : null, n = list ? list.length : 6;
  const L = layout(model, w, h, n);
  body(c, L, isDark);
  if (L.fan) {
    const { x, y, r } = L.fan, sq = L.fan.squash || 1;
    if (look.on) { const g0 = glow(look, t, reverse), rg = c.createRadialGradient(x, y, 0, x, y, r * 2.05); rg.addColorStop(0, rgbA(g0, .18 + .22 * look.brightness)); rg.addColorStop(1, rgbA(g0, 0));
      c.save(); c.translate(x, y); c.scale(1, sq); c.translate(-x, -y); c.fillStyle = rg; c.beginPath(); c.arc(x, y, r * 2.05, 0, 7); c.fill(); c.restore(); }
    c.save(); c.translate(x, y); c.scale(1, sq); c.translate(-x, -y); ring(c, x, y, r, look, t, spin, isDark, reverse, L.fan.blades); c.restore();
  }
  if (L.bar) {                                                                              // a NAS: its light is a bar across the top
    const { x, y, w: bw, h: bh } = L.bar, k = 16, seg = bw / k;
    c.fillStyle = "#1a1a1f"; rr(c, x, y, bw, bh, bh / 2); c.fill();
    if (look.on) { c.save(); rr(c, x, y, bw, bh, bh / 2); c.clip();
      for (let j = 0; j < k; j++) { const [col, a] = ledColor(look, j, k, t, reverse), al = Math.max(.08, a) * (.3 + .7 * look.brightness);
        const gx = x + seg * (j + .5), gg = c.createRadialGradient(gx, y + bh / 2, 0, gx, y + bh / 2, seg * 1.4);
        gg.addColorStop(0, rgbA(col, al)); gg.addColorStop(1, rgbA(col, 0)); c.fillStyle = gg; c.fillRect(gx - seg * 1.4, y, seg * 2.8, bh); }
      c.restore(); }
  }
  // the bars: plain, or your LED strips (Lighting → your lights), showing what they show (a laptop's keyboard backlight)
  const strips = (S.fan?.zones || []).filter(z => z.shape === "strip").slice(0, 2);
  L.strips.forEach((b, k) => {
    c.fillStyle = b.base; rr(c, b.x, b.y, b.w, b.h, Math.min(8, b.h / 2)); c.fill();
    const z = strips[k] || (b.keys && look.on ? { ...S.fan, on: true } : null); if (!z || z.on === false) return;
    const zl = fanLook(z), n2 = Math.max(6, Math.min(30, z.led_count || 20)), seg = (b.w - 8) / n2;
    c.save(); rr(c, b.x, b.y, b.w, b.h, Math.min(8, b.h / 2)); c.clip();
    for (let j = 0; j < n2; j++) {
      const [col, a] = ledColor(zl, j, n2, t, false), al = Math.max(.15, a) * (.35 + .65 * zl.brightness) * (b.keys ? .55 : 1);
      const gx = b.x + 4 + seg * (j + .5), gg = c.createRadialGradient(gx, b.y + b.h / 2, 0, gx, b.y + b.h / 2, seg * 1.3);
      gg.addColorStop(0, rgbA(col, al)); gg.addColorStop(1, rgbA(col, 0)); c.fillStyle = gg; c.fillRect(gx - seg * 1.3, b.y, seg * 2.6, b.h);
    }
    if (b.keys) { c.fillStyle = "rgba(10,10,12,.82)";                                   // the keys over the backlight
      for (let r = 0; r < 4; r++) for (let q = 0; q < 14; q++) { rr(c, b.x + b.w * (q / 14) + 1, b.y + b.h * (r / 4) + 1, b.w / 14 - 2, b.h / 4 - 2, 1.5); c.fill(); } }
    c.restore();
  });
  // one LED per drive (boot drive included): green + activity, amber = worn/hot (both blink with activity), red pulsing = missing/failing
  L.leds.forEach(([x, y], i) => {
    const lvl = list ? list[i].level : "ok";
    const io = list && S.lastNow?.disks ? S.lastNow.disks[list[i].name] : null;
    const [col, a] = lvl === "critical" ? [[255, 64, 64], .35 + .65 * (.5 + .5 * Math.sin(t / 1800 * 2 * Math.PI))] : lvl === "warning" ? [[255, 176, 32], .45 + .55 * activity(i, t, blinkRate(io))] : [[62, 207, 110], .38 + .62 * activity(i, t, blinkRate(io))];
    if (a > .5) { c.fillStyle = rgbA(col, (a - .5) * .6); c.beginPath(); c.arc(x, y, L.lr * 2.6, 0, 7); c.fill(); }
    c.fillStyle = rgbA(col, a); c.beginPath(); c.arc(x, y, L.lr, 0, 7); c.fill();
  });
  glare(c, L);
  const p = L.power;
  if (L.model !== "tower") { c.strokeStyle = "rgba(255,255,255,.18)"; c.lineWidth = 1.2; c.beginPath(); c.arc(p.x, p.y, p.r * 2.1, 0, 7); c.stroke(); }   // the button around the light
  c.fillStyle = status; c.globalAlpha = .35; c.beginPath(); c.arc(p.x, p.y, p.r * 1.9, 0, 7); c.fill();
  c.globalAlpha = 1; c.beginPath(); c.arc(p.x, p.y, p.r, 0, 7); c.fill();
}

export const heroLook = () => { const v = S.overview?.server?.look; return LOOKS.some(([k]) => k === v) ? v : "tower"; };
/** What's under a click on the home picture: "drives" (the drive lights), "power" (the power button), "fan", or null. */
export function heroHit(canvas, ev) {
  const r = canvas.getBoundingClientRect(), x = ev.clientX - r.left, y = ev.clientY - r.top;
  const d = S.overview?.status?.metrics?.drive_states, L = layout(heroLook(), r.width, r.height, d && d.length ? d.length : 6), H = L.hit;
  const inR = ([x0, y0, x1, y1]) => x >= x0 && x <= x1 && y >= y0 && y <= y1;
  if (Math.hypot(x - H.power[0], y - H.power[1]) < H.power[2]) return "power";
  if (inR(H.drives)) return "drives";
  if (H.fanCircle && Math.hypot(x - H.fanCircle[0], y - H.fanCircle[1]) < H.fanCircle[2]) return "fan";
  if (H.fan.some(inR)) return "fan";
  return null;
}

function drawFan(c, w, h, look, t, spin, reverse) {
  const r = Math.min(w, h) * .34, x = w / 2, y = h / 2;
  if (look.on) { const g0 = glow(look, t, reverse), rg = c.createRadialGradient(x, y, 0, x, y, r * 1.9); rg.addColorStop(0, rgbA(g0, .2 + .25 * look.brightness)); rg.addColorStop(1, rgbA(g0, 0));
    c.fillStyle = rg; c.beginPath(); c.arc(x, y, r * 1.9, 0, 7); c.fill(); }
  ring(c, x, y, r, look, t, spin, dark(), reverse);
}

/** Animate a canvas until it leaves the page. kind: "server" | "fan". */
export function animate(canvas, kind, statusColor = () => "#3ecf6e", still = false) {   // still: one frame, then only on resize (pickers)
  if (!canvas) return;
  const t0 = performance.now();
  const frame = now => {
    if (!canvas.isConnected) return;
    const dpr = devicePixelRatio || 1, w = canvas.clientWidth, h = canvas.clientHeight;
    if (still && canvas._drawn === `${w}x${h}`) return void setTimeout(() => requestAnimationFrame(frame), 500);
    requestAnimationFrame(frame);           // first: one bad frame (lights half-loaded, say) must never stop the picture for good
    if (!w || !h) return;
    if (still) canvas._drawn = `${w}x${h}`;
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr); }
    const c = canvas.getContext("2d"); c.setTransform(dpr, 0, 0, dpr, 0, 0);
    const look = fanLook(S.fan), t = Date.now() + (S.skew || 0);     // server time: the wave lines up with the real fan
    const spin = reduce() || still ? 0 : (t % 2200) / 2200 * 360, reverse = localStorage.getItem("nova.fanReverse") === "true";
    c.clearRect(0, 0, w, h);
    try {
      if (kind === "server") drawServer(c, w, h, look, t, spin, statusColor(), S.overview?.status?.metrics?.drive_states, reverse, canvas.dataset.look || heroLook());
      else drawFan(c, w, h, look, t, spin, reverse);
    } catch (e) { if (!canvas._warned) { canvas._warned = true; console.warn("illustration:", e); } }
  };
  requestAnimationFrame(frame);
}
