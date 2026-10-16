// Nova web — this browser's own Nova icon (Appearance → App icon): the tab icon, and the icon an iPhone or iPad
// uses when Nova is added to the home screen. The same choices as the Android app (appicons.js is made from its
// launcher icons by packaging/make-web-icons.py), plus "Your color", which follows the accent like the app's
// "Match my accent color".
import { prefs } from "./core.js";

let icons = null;
const load = async () => icons || (icons = await import("./appicons.js"));

/** [id, label] for the picker, in the app's order, with "Your color" second. */
export async function webIcons() {
  const { APP_ICONS } = await load();
  const l = Object.entries(APP_ICONS).map(([k, v]) => [k, v.label]);
  return [l[0], ["accent", "Your color"], ...l.slice(1)];
}

const accent = () => getComputedStyle(document.documentElement).getPropertyValue("--blue").trim() || "#3e91ff";
const dark = () => document.documentElement.classList.contains("l-light") ? false : matchMedia("(prefers-color-scheme: dark)").matches || document.documentElement.dataset.theme === "dark";

/** The app's rule: the tonal icon in the accent's hue (every 20°, light or dark like the page), black & white for a grey. */
function forAccent(hex, isDark) {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim()); if (!m) return null;
  const n = parseInt(m[1], 16), r = (n >> 16) / 255, g = (n >> 8 & 255) / 255, b = (n & 255) / 255;
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2, d = mx - mn;
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
  if (s < .12) return isDark ? "wb" : "bw";
  let h = d === 0 ? 0 : mx === r ? ((g - b) / d) % 6 : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
  h = (h * 60 + 360) % 360;
  return "tone:" + (isDark ? "d" : "l") + String((Math.round(h / 20) * 20) % 360).padStart(3, "0");
}

/** The icon as an SVG document: the middle 72×72 of the 108×108 adaptive canvas, rounded like an app icon. */
export async function iconSvg(id) {
  const { APP_ICONS, TONE_ICONS } = await load();
  let body;
  if (id === "accent") { const k = forAccent(accent(), dark()) || "default"; body = k.startsWith("tone:") ? TONE_ICONS[k.slice(5)] : APP_ICONS[k]?.svg; }
  else body = APP_ICONS[id]?.svg;
  body = body || APP_ICONS.default.svg;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><defs><clipPath id="cl">${SHAPE_PATHS[prefs.iconShape] || SHAPE_PATHS.rounded}</clipPath></defs><g clip-path="url(#cl)">${body}</g></svg>`;
}
/** Icon shapes (Appearance → Icon shape), on the 72×72 middle of the canvas. The squircle is a superellipse, like One UI's. */
const squircle = (() => { const pts = []; for (let i = 0; i <= 64; i++) { const t = i / 64 * Math.PI * 2, c = Math.cos(t), s = Math.sin(t), n = 4.2;
  pts.push(`${(54 + 36 * Math.sign(c) * Math.pow(Math.abs(c), 2 / n)).toFixed(2)},${(54 + 36 * Math.sign(s) * Math.pow(Math.abs(s), 2 / n)).toFixed(2)}`); } return `M${pts.join("L")}Z`; })();
export const SHAPES = { rounded: "Rounded square", squircle: "Squircle", circle: "Circle", square: "Square", teardrop: "Teardrop" };
const SHAPE_PATHS = { rounded: '<rect x="18" y="18" width="72" height="72" rx="20"/>', squircle: `<path d="${squircle}"/>`, circle: '<circle cx="54" cy="54" r="36"/>',
  square: '<rect x="18" y="18" width="72" height="72" rx="6"/>', teardrop: '<path d="M54 18a36 36 0 0 1 36 36v36H54a36 36 0 0 1 0-72z"/>' };
export const svgUrl = s => "data:image/svg+xml;charset=utf-8," + encodeURIComponent(s);

/** Puts the chosen icon on the tab (and the home-screen icon link). Default: the shipped files. */
export async function applyWebIcon() {
  const id = prefs.webIcon || "default";
  const link = document.querySelector('link[rel="icon"]'), apple = document.querySelector('link[rel="apple-touch-icon"]');
  if (id === "default") { if (link) link.href = "/web/icon.svg"; if (apple) apple.href = "/web/apple-touch-icon.png"; return; }
  try {
    const url = svgUrl(await iconSvg(id));
    if (link) link.href = url;
    if (apple) {                                   // iOS wants a PNG: draw the SVG at 180 px (square — iOS rounds it itself)
      const img = new Image(); img.src = url; await img.decode();
      const c = document.createElement("canvas"); c.width = c.height = 180; c.getContext("2d").drawImage(img, -8, -8, 196, 196);
      apple.href = c.toDataURL("image/png");
    }
  } catch {}
}
