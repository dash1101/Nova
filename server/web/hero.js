// The home-screen server (tower case, glass side, live ARGB fan, drive LEDs, power LED in the
// status colour) and the big Lighting fan — the same drawing as the app's Hero.kt, on a canvas.
import { S } from "./core.js";

const RAINBOW = ["#ff3b30", "#ff9500", "#ffcc00", "#34c759", "#00c7be", "#007aff", "#af52de", "#ff3b30"];
const reduce = () => document.documentElement.classList.contains("reduce");

export function fanLook(f) {
  const ov = f?.status_override;
  return {
    on: ov ? ov.on !== false : f?.on !== false,
    effect: ov?.effect || f?.effect || "static",
    color: ov?.color || f?.color || "#3e91ff",
    color2: f?.color2 || "#bf5af2",
    brightness: Math.max(0, Math.min(1, (f?.brightness ?? 50) / 100)),
    speed: f?.speed ?? 50,
    rainbow: f?.rainbow !== false,
  };
}
const dark = () => getComputedStyle(document.documentElement).colorScheme !== "light";
function rgba(hex, a) { const n = parseInt(hex.slice(1), 16); return `rgba(${n >> 16 & 255},${n >> 8 & 255},${n & 255},${a})`; }

function ring(c, x, y, r, look, phase, spin, isDark) {
  c.save();
  c.fillStyle = isDark ? "#151518" : "#2a2a30"; c.beginPath(); c.arc(x, y, r * 1.12, 0, 7); c.fill();
  if (look.on) {
    const pulse = look.effect === "pulse" ? .25 + .75 * (.5 + .5 * Math.sin(phase * 2 * Math.PI)) : look.effect === "blink" ? (phase < .5 ? 1 : .08) : 1;
    const a = (.25 + .75 * look.brightness) * pulse;
    let stroke;
    if (look.effect === "gradient" || (look.effect === "wave")) {
      const g = c.createConicGradient(look.effect === "wave" ? phase * 2 * Math.PI : 0, x, y);
      const cols = look.effect === "gradient" ? [look.color, look.color2, look.color]
        : look.rainbow ? RAINBOW : [look.color, rgba(look.color, .15), look.color];
      cols.forEach((col, i) => g.addColorStop(i / (cols.length - 1), col)); stroke = g;
    } else if ((look.effect === "cycle" || look.effect === "random") && look.rainbow) stroke = RAINBOW[Math.min(6, Math.floor(phase * 7))];
    else stroke = look.color;
    c.strokeStyle = stroke;
    for (let i = 6; i >= 1; i--) {             // soft bloom
      c.globalAlpha = a * .07 * (7 - i) / 6; c.lineWidth = r * .08 * i;
      c.beginPath(); c.arc(x, y, r * (1 + i * .06), 0, 7); c.stroke();
    }
    c.globalAlpha = a; c.lineWidth = r * .12; c.beginPath(); c.arc(x, y, r, 0, 7); c.stroke(); c.globalAlpha = 1;
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

function rr(c, x, y, w, h, r) { c.beginPath(); c.roundRect(x, y, w, h, r); }

function drawServer(c, w, h, look, phase, spin, status) {
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
  if (look.on) { const rg = c.createRadialGradient(fx, fy, 0, fx, fy, cw * .6); rg.addColorStop(0, rgba(look.color, .22 * look.brightness + .05)); rg.addColorStop(1, rgba(look.color, 0));
    c.fillStyle = rg; c.beginPath(); c.arc(fx, fy, cw * .6, 0, 7); c.fill(); }
  ring(c, fx, fy, cw * .29, look, phase, spin, isDark);
  c.fillStyle = "#26262c"; rr(c, left + cw * .16, top + ch * .62, cw * .68, ch * .07, 8); c.fill();
  c.fillStyle = "#1f1f25"; rr(c, left + cw * .16, top + ch * .72, cw * .68, ch * .05, 8); c.fill();
  for (let i = 0; i < 6; i++) { c.fillStyle = `rgba(62,207,110,${Math.floor(phase * 6) === i ? .95 : .45})`; c.beginPath(); c.arc(left + cw * (.28 + i * .09), top + ch * .83, cw * .014, 0, 7); c.fill(); }
  g = c.createLinearGradient(left, top, left + cw * .5, top + ch * .5); g.addColorStop(0, "rgba(255,255,255,.07)"); g.addColorStop(1, "rgba(255,255,255,0)");
  c.fillStyle = g; c.fillRect(left + gp, top + gp, cw * .42, ch * .55);
  c.fillStyle = status; c.globalAlpha = .35; c.beginPath(); c.arc(left + cw * .5, top + ch * .935, cw * .03, 0, 7); c.fill();
  c.globalAlpha = 1; c.beginPath(); c.arc(left + cw * .5, top + ch * .935, cw * .016, 0, 7); c.fill();
}

function drawFan(c, w, h, look, phase, spin) {
  const r = Math.min(w, h) * .34, x = w / 2, y = h / 2;
  if (look.on) { const rg = c.createRadialGradient(x, y, 0, x, y, r * 1.9); rg.addColorStop(0, rgba(look.color, .25 * look.brightness + .05)); rg.addColorStop(1, rgba(look.color, 0));
    c.fillStyle = rg; c.beginPath(); c.arc(x, y, r * 1.9, 0, 7); c.fill(); }
  ring(c, x, y, r, look, phase, spin, dark());
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
    const look = fanLook(S.fan);
    const dur = Math.max(500, 3200 - look.speed * 28);           // same pace as the app
    const t = reduce() ? 0 : now;
    const phase = (t % dur) / dur, spin = (t % 1400) / 1400 * 360;
    if (kind === "server") drawServer(c, w, h, look, phase, spin, statusColor()); else drawFan(c, w, h, look, phase, spin);
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}
