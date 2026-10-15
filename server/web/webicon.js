// Nova web — this browser's own Nova icon (Appearance → App icon): the tab icon, and the icon an iPhone or iPad
// uses when Nova is added to the home screen. The styles match the app's (appicons.js holds the artwork).
import { prefs } from "./core.js";

export const WEB_ICONS = [["default", "Default"], ["accent", "Your color"], ["dark", "Dark"], ["bw", "Black on white"], ["wb", "White on black"],
  ["clear", "Glass (see-through)"], ["aero", "Aero"], ["mica", "Mica"], ["breeze", "Breeze"]];

const accent = () => (getComputedStyle(document.documentElement).getPropertyValue("--blue").trim() || "#3e91ff");

/** The icon as an SVG document, rounded like an app icon. */
export async function iconSvg(id) {
  if (id === "default") return (await fetch("/web/icon.svg")).text();
  const { APP_ICONS } = await import("./appicons.js");
  const body = (APP_ICONS[id] || APP_ICONS.accent).replace("ACCENT", accent());
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><defs><clipPath id="cl"><rect x="18" y="18" width="72" height="72" rx="20"/></clipPath></defs><g clip-path="url(#cl)">${body}</g></svg>`;
}
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
