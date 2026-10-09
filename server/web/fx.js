// The fan's software effects, ported line for line from the server (nova_rgb.frame) — same as the
// app's LightFx.kt — so the picture shows exactly what the fan shows at the same moment.
export const SOFTWARE = new Set(["wave", "comet", "scanner", "twinkle", "fire", "breathe"]);
/** Controller effects that also take a palette: with 2+ colors Nova draws them itself (same as the server). */
export const PALETTE_FX = new Set(["static", "pulse", "blink", "cycle", "gradient"]);
const paletteOf = fx => (fx.palette || []).filter(Boolean);
export const usesPalette = fx => PALETTE_FX.has(fx.effect) && paletteOf(fx).length >= 2 && !(fx.effect === "cycle" && fx.rainbow);
export const isSoftware = fx => SOFTWARE.has(fx.effect) || usesPalette(fx);
const FIRE = ["#200000", "#ff1800", "#ff6000", "#ffb000", "#fff0a0"];

export const periodMs = s => pyRound(10000 * Math.pow(.02, (Math.max(1, Math.min(100, s)) - 1) / 99));
/** Python's round(): halves go to the even neighbor. */
function pyRound(x) { const f = Math.floor(x), d = x - f; return d > .5 ? f + 1 : d < .5 ? f : (f % 2 === 0 ? f : f + 1); }
const pmod = x => { const r = x % 1; return r < 0 ? r + 1 : r; };
/** 32-bit integer hash, identical to the server's _h and the app's. */
export function h(a, b) {
  let x = (Math.imul(a | 0, 73856093) ^ Math.imul(Number(BigInt.asIntN(32, BigInt(Math.floor(b)))), 19349663)) & 0x7fffffff;
  x ^= x >>> 13;
  x = Math.imul(x, 1274126177) & 0x7fffffff;
  return x ^ (x >>> 16);
}
export const hexRgb = s => { const n = parseInt((s || "#ffffff").replace("#", "").padEnd(6, "0").slice(0, 6), 16); return [n >> 16 & 255, n >> 8 & 255, n & 255]; };
function hsv(x) {
  const hh = pmod(x), i = Math.floor(hh * 6), f = hh * 6 - i, q = 1 - f;
  const [r, g, b] = [[1, f, 0], [q, 1, 0], [0, 1, f], [0, q, 1], [f, 0, 1], [1, 0, q]][i % 6];
  return [pyRound(r * 255), pyRound(g * 255), pyRound(b * 255)];
}
/** Fire in one color: from nearly black, through the color, to almost white (same as the server). */
function tintFlame(c) {
  const k = f => c.map(v => pyRound(v * f)), w = f => c.map(v => pyRound(v + (255 - v) * f));
  return [k(0.08), k(0.55), c, w(0.45), w(0.8)];
}
function pal(fx) {
  const p = (fx.palette || []).filter(Boolean);
  if (fx.effect === "fire") {                // Flame (rainbow on) · Palette: your own heat ramp · One color: a flame in it
    if (p.length >= 2) return p.map(hexRgb);
    if (fx.rainbow) return FIRE.map(hexRgb);
    return tintFlame(hexRgb(fx.color || "#ff6000"));
  }
  if (fx.rainbow) return null;
  if (p.length >= 2) return p.map(hexRgb);
  return [hexRgb(fx.color || "#3e91ff")];
}
function at(p, x0) {
  const x = pmod(x0);
  if (p === null) return hsv(x);
  if (p.length === 1) return p[0];
  const f = x * p.length, i = Math.trunc(f) % p.length, k = f - Math.trunc(f), a = p[i], b = p[(i + 1) % p.length];
  return [0, 1, 2].map(c => pyRound(a[c] + (b[c] - a[c]) * k));
}
function heat(p, x0) {
  const x = Math.max(0, Math.min(.999, x0)), f = x * (p.length - 1), i = Math.trunc(f), k = f - i, a = p[i], b = p[Math.min(i + 1, p.length - 1)];
  return [0, 1, 2].map(c => pyRound(a[c] + (b[c] - a[c]) * k));
}
const sc = (c, k) => c.map(v => pyRound(v * Math.max(0, Math.min(1, k))));

/** LED colors (0..255, full brightness) for a software effect at server time t (seconds). */
export function frame(fx, t) {
  const n = Math.max(1, fx.leds | 0), per = periodMs(fx.speed) / 1000, p = pal(fx), L = [...Array(n).keys()];
  if (usesPalette(fx)) {
    const pp = paletteOf(fx).map(hexRgb), e = fx.effect, c = Math.floor(t / per);
    if (e === "static" || e === "gradient") return L.map(j => at(pp, j / n));
    if (e === "pulse") { const k = .06 + .94 * (.5 - .5 * Math.cos(2 * Math.PI * ((t / per) % 1))); return L.map(() => sc(pp[c % pp.length], k)); }
    if (e === "blink") return L.map(() => (t / per) % 1 < .5 ? pp[c % pp.length] : [0, 0, 0]);
    if (e === "cycle") return L.map(() => at(pp, t / (per * 2 * pp.length)));
  }
  switch (fx.effect) {
    case "wave": { const ph = (t / (per * 1.5)) % 1;
      if (p === null || p.length >= 2) return L.map(j => at(p, j / n + ph));
      return L.map(j => { const x = (Math.cos(2 * Math.PI * (j / n - ph)) + 1) / 2; return sc(p[0], .08 + .92 * x ** 3); }); }
    case "comet": { const head = (t / (per * 1.5)) % 1 * n;
      return L.map(j => { let d = (head - j) % n; if (d < 0) d += n; const k = Math.max(0, 1 - d / (n * .6)) ** 2; return sc(at(p, head / n), Math.max(k, .03)); }); }
    case "scanner": { const q = (t / (per * 1.5)) % 1, pos = (q < .5 ? q * 2 : 2 - q * 2) * (n - 1);
      return L.map(j => sc(at(p, j / n), Math.max(.03, Math.max(0, 1 - Math.abs(j - pos) / 2.2) ** 1.5))); }
    case "twinkle": return L.map(j => {
      const Lp = per * 1.5, loc = t / Lp + (h(j, 7) % 1000) / 1000, c = Math.floor(loc), f = loc - c, r = h(j, c);
      const k = r % 100 < 55 ? Math.sin(Math.PI * f) ** 2 : 0;
      return sc(p === null || p.length > 1 ? at(p, (r % 997) / 997) : p[0], Math.max(.05, k)); });
    case "fire": return L.map(j => {
      const q = t / .12, c = Math.floor(q); let f = q - c; f = f * f * (3 - 2 * f);
      const a = (h(j, c) % 1000) / 1000, b = (h(j, c + 1) % 1000) / 1000, v = .45 + .55 * (a + (b - a) * f);
      return sc(heat(p, v), .35 + .65 * v); });
    case "breathe": { const T = per * 2, c = Math.floor(t / T), k = .06 + .94 * (.5 - .5 * Math.cos(2 * Math.PI * ((t / T) % 1)));
      const col = p === null ? at(null, (c % 12) / 12) : p[c % p.length]; return L.map(() => sc(col, k)); }
  }
  return L.map(() => hexRgb(fx.color));
}
