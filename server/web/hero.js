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
function ring(c, x, y, r, look, t, spin, isDark, reverse) {
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
  c.translate(x, y); c.rotate(spin * Math.PI / 180); c.fillStyle = "#3a3a40";
  for (let b = 0; b < 7; b++) {
    c.rotate(2 * Math.PI / 7); c.beginPath(); c.moveTo(0, -r * .2);
    c.bezierCurveTo(r * .55, -r * .35, r * .62, -r * .78, r * .12, -r * .86);
    c.bezierCurveTo(r * .25, -r * .55, r * .1, -r * .32, 0, -r * .2); c.fill();
  }
  c.restore();
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

function drawServer(c, w, h, look, t, spin, status, drives, reverse) {
  const isDark = dark();
  const cw = Math.min(w * .56, h * .62), ch = cw * 1.42, left = (w - cw) / 2, top = (h - ch) / 2;
  c.fillStyle = `rgba(0,0,0,${isDark ? .6 : .18})`; c.beginPath(); c.ellipse(left + cw / 2, top + ch, cw * .45, ch * .03, 0, 0, 7); c.fill();
  let g = c.createLinearGradient(0, top, 0, top + ch); g.addColorStop(0, "#4a4a52"); g.addColorStop(.5, "#26262b"); g.addColorStop(1, "#1b1b1f");
  c.fillStyle = g; rr(c, left, top, cw, ch, cw * .07); c.fill();
  c.strokeStyle = "rgba(255,255,255,.10)"; c.lineWidth = 2.5; c.stroke();
  const gp = cw * .07;
  g = c.createLinearGradient(left, top, left + cw, top + ch); g.addColorStop(0, "#101014"); g.addColorStop(1, "#17171c");
  c.fillStyle = g; rr(c, left + gp, top + gp, cw - 2 * gp, ch - 2 * gp * 1.6, cw * .04); c.fill();
  const fx = left + cw / 2, fy = top + ch * .33;
  if (look.on) { const g0 = glow(look, t, reverse), rg = c.createRadialGradient(fx, fy, 0, fx, fy, cw * .6); rg.addColorStop(0, rgbA(g0, .18 + .22 * look.brightness)); rg.addColorStop(1, rgbA(g0, 0));
    c.fillStyle = rg; c.beginPath(); c.arc(fx, fy, cw * .6, 0, 7); c.fill(); }
  ring(c, fx, fy, cw * .29, look, t, spin, isDark, reverse);
  c.fillStyle = "#26262c"; rr(c, left + cw * .16, top + ch * .62, cw * .68, ch * .07, 8); c.fill();
  c.fillStyle = "#1f1f25"; rr(c, left + cw * .16, top + ch * .72, cw * .68, ch * .05, 8); c.fill();
  // one LED per drive (boot drive included): green + activity, amber = worn/hot, red pulsing = missing/failing
  const list = drives && drives.length ? drives : null, n = list ? list.length : 6;
  const step = n > 1 ? Math.min(cw * .09, cw * .62 / (n - 1)) : 0, x0 = left + cw / 2 - step * (n - 1) / 2, ly = top + ch * .83;
  const lr = Math.max(cw * .008, Math.min(cw * .016, step * .32));
  for (let i = 0; i < n; i++) {
    const lvl = list ? list[i].level : "ok";
    const io = list && S.lastNow?.disks ? S.lastNow.disks[list[i].name] : null;
    const [col, a] = lvl === "critical" ? [[255, 64, 64], .35 + .65 * (.5 + .5 * Math.sin(t / 1000 * 2 * Math.PI))] : lvl === "warning" ? [[255, 176, 32], .9] : [[62, 207, 110], .38 + .62 * activity(i, t, blinkRate(io))];
    if (a > .5) { c.fillStyle = rgbA(col, (a - .5) * .6); c.beginPath(); c.arc(x0 + i * step, ly, lr * 2.6, 0, 7); c.fill(); }
    c.fillStyle = rgbA(col, a); c.beginPath(); c.arc(x0 + i * step, ly, lr, 0, 7); c.fill();
  }
  g = c.createLinearGradient(left, top, left + cw * .5, top + ch * .5); g.addColorStop(0, "rgba(255,255,255,.07)"); g.addColorStop(1, "rgba(255,255,255,0)");
  c.fillStyle = g; c.fillRect(left + gp, top + gp, cw * .42, ch * .55);
  c.fillStyle = status; c.globalAlpha = .35; c.beginPath(); c.arc(left + cw * .5, top + ch * .935, cw * .03, 0, 7); c.fill();
  c.globalAlpha = 1; c.beginPath(); c.arc(left + cw * .5, top + ch * .935, cw * .016, 0, 7); c.fill();
}

function drawFan(c, w, h, look, t, spin, reverse) {
  const r = Math.min(w, h) * .34, x = w / 2, y = h / 2;
  if (look.on) { const g0 = glow(look, t, reverse), rg = c.createRadialGradient(x, y, 0, x, y, r * 1.9); rg.addColorStop(0, rgbA(g0, .2 + .25 * look.brightness)); rg.addColorStop(1, rgbA(g0, 0));
    c.fillStyle = rg; c.beginPath(); c.arc(x, y, r * 1.9, 0, 7); c.fill(); }
  ring(c, x, y, r, look, t, spin, dark(), reverse);
}

/** Animate a canvas until it leaves the page. kind: "server" | "fan". */
export function animate(canvas, kind, statusColor = () => "#3ecf6e") {
  if (!canvas) return;
  const t0 = performance.now();
  const frame = now => {
    if (!canvas.isConnected) return;
    const dpr = devicePixelRatio || 1, w = canvas.clientWidth, h = canvas.clientHeight;
    if (canvas.width !== Math.round(w * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr); }
    const c = canvas.getContext("2d"); c.setTransform(dpr, 0, 0, dpr, 0, 0); c.clearRect(0, 0, w, h);
    const look = fanLook(S.fan), t = Date.now() + (S.skew || 0);     // server time: the wave lines up with the real fan
    const spin = reduce() ? 0 : (t % 1400) / 1400 * 360, reverse = localStorage.getItem("nova.fanReverse") === "true";
    if (kind === "server") drawServer(c, w, h, look, t, spin, statusColor(), S.overview?.status?.metrics?.drive_states, reverse);
    else drawFan(c, w, h, look, t, spin, reverse);
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}
