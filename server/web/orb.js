// Nova web — the assistant's orb: a sphere of dots in the accent color, turning in 3D (near dots bigger and brighter).
// Its state says what Nova is doing: idle (slow, breathing), think (a bright band scans over it while it turns faster),
// listen (a wave rolls through it with your voice), work (a band sweeps round it while steps run).
// Plain 2D canvas, one shared clock for every orb on the page; nothing draws while no orb is on screen.
// With Reduce motion an idle orb holds still, and the busy states move slowly (they still show that Nova is busy).

const GOLD = Math.PI * (3 - Math.sqrt(5)), CACHE = {};
const sphere = n => CACHE[n] ||= Array.from({ length: n }, (_, i) => { const y = 1 - (2 * (i + .5)) / n, r = Math.sqrt(1 - y * y), a = i * GOLD; return [r * Math.cos(a), y, r * Math.sin(a)]; });
const SPIN = { idle: .22, think: 1.05, listen: .45, work: .7 };

/** The orb's markup: a canvas the loop below finds and draws. */
export const orbHtml = (state = "idle", size = 96, cls = "") => `<canvas class="norb ${cls}" data-state="${state}" width="${size}" height="${size}" style="width:${size}px;height:${size}px" aria-hidden="true"></canvas>`;

let level = 0;                                         // the microphone's loudness (0–1), for the listening wave
export const setLevel = v => { level = Math.max(0, Math.min(1, +v || 0)); };

function rgb() {
  const c = getComputedStyle(document.documentElement).getPropertyValue("--blue").trim();
  const m = c.match(/^#([0-9a-f]{6})$/i); if (m) { const n = parseInt(m[1], 16); return [n >> 16 & 255, n >> 8 & 255, n & 255]; }
  const f = c.match(/(\d+)\D+(\d+)\D+(\d+)/); return f ? [+f[1], +f[2], +f[3]] : [62, 145, 255];
}

/** One frame of one orb at time t (seconds). */
export function drawOrb(ctx, size, t, state, col, dark) {
  const n = size >= 72 ? 340 : size >= 40 ? 190 : 110, P = sphere(n);
  const yaw = t * (SPIN[state] ?? .22), tilt = .42 + Math.sin(t * .27) * .14;
  const cy = Math.cos(yaw), sy = Math.sin(yaw), ct = Math.cos(tilt), st = Math.sin(tilt);
  const c = size / 2, R = size * .36 * (state === "idle" ? 1 + .035 * Math.sin(t * 1.5) : 1), rs = Math.pow(size / 96, .62);
  const scan = Math.sin(t * 1.9), sweep = t * 2.2;
  const out = [];
  for (const [x, y, z] of P) {
    let k = 1, hot = 0;
    if (state === "listen") k = 1 + (.04 + .2 * level) * Math.sin(y * 7 - t * 7);
    else if (state === "think") { k = 1 + .045 * Math.sin(x * 5 + t * 3.1) * Math.cos(z * 4 - t * 2.3); hot = Math.exp(-((y - scan) ** 2) / .025); }
    else if (state === "work") { const d = Math.atan2(z, x) - sweep; hot = Math.exp(-(Math.atan2(Math.sin(d), Math.cos(d)) ** 2) / .09); }
    const x1 = x * cy + z * sy, z1 = -x * sy + z * cy, y1 = y * ct - z1 * st, z2 = y * st + z1 * ct;
    const near = (z2 + 1) / 2;                            // 0 at the back, 1 at the front
    out.push([c + x1 * R * k, c - y1 * R * k, z2, (.45 + 1.55 * near + 1.1 * hot) * rs, .12 + .78 * near + .5 * hot, near * .55 + hot * .6]);
  }
  out.sort((a, b) => a[2] - b[2]);
  ctx.clearRect(0, 0, size, size);
  // a dark sphere behind the dots (so the light dots stand out on any page), with a faint glow of the accent
  const g = ctx.createRadialGradient(c, c * .9, 0, c, c, size / 2);
  g.addColorStop(0, `rgba(${col[0] * .12 | 0},${col[1] * .12 | 0},${col[2] * .16 + 10 | 0},.96)`); g.addColorStop(.72, `rgba(${col[0] * .08 | 0},${col[1] * .08 | 0},${col[2] * .1 + 6 | 0},.92)`);
  g.addColorStop(.94, `rgba(${col[0] * .1 | 0},${col[1] * .1 | 0},${col[2] * .12 + 8 | 0},.9)`); g.addColorStop(.985, `rgba(${col[0]},${col[1]},${col[2]},.35)`); g.addColorStop(1, "rgba(0,0,0,0)");
  ctx.fillStyle = g; ctx.beginPath(); ctx.arc(c, c, size / 2, 0, 6.2832); ctx.fill();
  const lift = 255;                                       // near dots go toward white
  for (const [px, py, , r, a, w] of out) {
    const m = w;
    ctx.fillStyle = `rgba(${Math.round(col[0] + (lift - col[0]) * m)},${Math.round(col[1] + (lift - col[1]) * m)},${Math.round(col[2] + (lift - col[2]) * m)},${Math.min(1, a)})`;
    ctx.beginPath(); ctx.arc(px, py, r, 0, 6.2832); ctx.fill();
  }
}

// the loop: every orb on the page, one requestAnimationFrame
let raf = 0;
const tick = now => {
  raf = 0;
  const orbs = [...document.querySelectorAll("canvas.norb")]; if (!orbs.length) return;
  const reduce = document.documentElement.classList.contains("reduce"), col = rgb();
  const dark = !document.documentElement.classList.contains("l-light");
  let moving = false;
  for (const cv of orbs) {
    const state = cv.dataset.state || "idle";
    if (!cv.dataset.dpr) { const d = Math.min(2, devicePixelRatio || 1), s = +cv.width; cv.dataset.dpr = d; cv.width = cv.height = Math.round(s * d); }
    const still = reduce && state === "idle";
    if (still && cv.dataset.drawn) continue;
    const ctx = cv.getContext("2d"), d = +cv.dataset.dpr, s = cv.width / d;
    ctx.setTransform(d, 0, 0, d, 0, 0);
    drawOrb(ctx, s, still ? 1.2 : (now / 1000) * (reduce ? .45 : 1), state, col, dark);
    cv.dataset.drawn = 1; if (!still) moving = true;
  }
  if (moving && !document.hidden) raf = requestAnimationFrame(tick);
};
/** Start (or keep) drawing the orbs on the page — call after the page changes. */
export const runOrbs = () => { if (!raf) raf = requestAnimationFrame(tick); };
/** Change an orb's state without redrawing the page around it. */
export const orbState = (el, state) => { if (el && el.dataset.state !== state) { el.dataset.state = state; delete el.dataset.drawn; runOrbs(); } };
document.addEventListener("visibilitychange", () => { if (!document.hidden) runOrbs(); });
