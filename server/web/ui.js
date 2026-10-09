// Nova web — UI kit: icons, One UI components, dialogs, toast, reorder lists, live graphs.
import { $, $$, esc, sleep, prefs } from "./core.js";

// ── icons (Material Symbols Rounded, 24px) ────────────────────────────────────────
const P = {
  home: "M4 21V9l8-6 8 6v12h-6v-7h-4v7Z",
  dns: "M20 13H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 19c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM20 3H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zM7 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z",
  status: "M3 13h4l2-5 4 10 2-5h6v-2h-4.6L15 13.4 11 3.6 8.6 11H3Z",
  box: "M12 2 3 7v10l9 5 9-5V7Zm0 2.3 6.4 3.6L12 11.5 5.6 7.9ZM5 9.6l6 3.4v6.6l-6-3.3Zm8 10V13l6-3.4v6.7Z",
  store: "M5 4h14l2 5c0 1.5-1.2 2.7-2.7 2.7A2.7 2.7 0 0 1 15.7 10 2.7 2.7 0 0 1 13 11.7 2.7 2.7 0 0 1 10.3 10 2.7 2.7 0 0 1 7.7 11.7 2.7 2.7 0 0 1 5 10 2.7 2.7 0 0 1 3 9ZM5 13h2v5h10v-5h2v7H5Z",
  dash: "M3 13h8V3H3Zm0 8h8v-6H3Zm10 0h8V11h-8Zm0-18v6h8V3Z",
  bell: "M12 22a2 2 0 0 0 2-2h-4a2 2 0 0 0 2 2Zm6-6v-5c0-3.1-1.6-5.6-4.5-6.3V4a1.5 1.5 0 0 0-3 0v.7C7.6 5.4 6 7.9 6 11v5l-2 2v1h16v-1Z",
  gear: "M19.4 13a7.6 7.6 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7.4 7.4 0 0 0-1.7-1L14.9 3h-4l-.4 2.7a7.4 7.4 0 0 0-1.7 1l-2.5-1-2 3.5L6.4 11a7.6 7.6 0 0 0 0 2l-2.1 1.6 2 3.5 2.5-1a7.4 7.4 0 0 0 1.7 1l.4 2.7h4l.4-2.7a7.4 7.4 0 0 0 1.7-1l2.5 1 2-3.5ZM12.9 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7Z",
  back: "M15.4 4.6 14 3.2 5.2 12l8.8 8.8 1.4-1.4L8 12Z",
  list: "M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z",
  bulb: "M9 21c0 .55.45 1 1 1h4c.55 0 1-.45 1-1v-1H9v1zm3-19C8.14 2 5 5.14 5 9c0 2.38 1.19 4.47 3 5.74V17c0 .55.45 1 1 1h6c.55 0 1-.45 1-1v-2.26c1.81-1.27 3-3.36 3-5.74 0-3.86-3.14-7-7-7z",
  cpu: "M9 9h6v6H9Zm12 2V9h-2V7a2 2 0 0 0-2-2h-2V3h-2v2h-2V3H9v2H7a2 2 0 0 0-2 2v2H3v2h2v2H3v2h2v2a2 2 0 0 0 2 2h2v2h2v-2h2v2h2v-2h2a2 2 0 0 0 2-2v-2h2v-2h-2v-2ZM17 17H7V7h10Z",
  mem: "M15 9H9v6h6Zm-2 4h-2v-2h2Zm8-2V9h-2V7a2 2 0 0 0-2-2h-2V3h-2v2h-2V3H9v2H7a2 2 0 0 0-2 2v2H3v2h2v2H3v2h2v2a2 2 0 0 0 2 2h2v2h2v-2h2v2h2v-2h2a2 2 0 0 0 2-2v-2h2v-2h-2v-2Z",
  disk: "M2 20h20v-4H2Zm2-3h2v2H4ZM2 4v4h20V4Zm4 3H4V5h2Zm-4 7h20v-4H2Zm2-3h2v2H4Z",
  ssd: "M18 2h-8L4.02 8 4 20c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-6 6h-2V4h2v4zm3 0h-2V4h2v4zm3 0h-2V4h2v4z",
  temp: "M15 13V5a3 3 0 0 0-6 0v8a5 5 0 1 0 6 0Zm-3-9a1 1 0 0 1 1 1v3h-2V5a1 1 0 0 1 1-1Z",
  net: "M16 17.01V10h-2v7.01h-3L15 21l4-3.99ZM9 3 5 6.99h3V14h2V6.99h3Z",
  backup: "M19.4 10A7.5 7.5 0 0 0 5.4 8 6 6 0 0 0 6 20h13a5 5 0 0 0 .4-10ZM14 13v4h-4v-4H7l5-5 5 5Z",
  term: "M20 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2Zm0 14H4V8h16ZM6.7 16.3 5.3 14.9 7.6 12.6 5.3 10.3 6.7 8.9l3.7 3.7ZM12 15h6v2h-6Z",
  play: "M8 5v14l11-7Z", stop: "M6 6h12v12H6Z", restart: "M12 5V2L8 6l4 4V7a5 5 0 1 1-5 5H5a7 7 0 1 0 7-7Z",
  update: "M5 20h14v-2H5Zm7-3 5-5h-3V4h-4v8H7Z",
  article: "M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-5 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z",
  more: "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
  okc: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm-2 15-5-5 1.4-1.4 3.6 3.6 7.6-7.6L19 8Z",
  err: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm1 15h-2v-2h2Zm0-4h-2V7h2Z",
  warn: "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z",
  edit: "M3 17.2V21h3.8L17.8 10l-3.8-3.8ZM20.7 7a1 1 0 0 0 0-1.4l-2.3-2.3a1 1 0 0 0-1.4 0l-1.8 1.8 3.7 3.7Z",
  full: "M7 14H5v5h5v-2H7Zm-2-4h2V7h3V5H5Zm12 7h-3v2h5v-5h-2ZM14 5v2h3v3h2V5Z",
  power: "M13 3h-2v10h2Zm4.8 2.2-1.4 1.4A7 7 0 1 1 7.6 6.6L6.2 5.2A9 9 0 1 0 17.8 5.2Z",
  refresh: "M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
  sync: "M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46A7.93 7.93 0 0 0 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74A7.93 7.93 0 0 0 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z",
  down: "M16.59 8.59 12 13.17 7.41 8.59 6 10l6 6 6-6z",
  check: "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
  add: "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
  addc: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm5 11h-4v4h-2v-4H7v-2h4V7h2v4h4v2z",
  remc: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm5 11H7v-2h10v2z",
  drag: "M20 9H4v2h16V9zM4 15h16v-2H4v2z",
  widgets: "M13 13v8h8v-8h-8zM3 21h8v-8H3v8zM3 3v8h8V3H3zm13.66-1.31L11 7.34 16.66 13l5.66-5.66-5.66-5.65z",
  palette: "M12 3a9 9 0 0 0 0 18c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S5.67 9 6.5 9 8 9.67 8 10.5 7.33 12 6.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 9.5 5s1.5.67 1.5 1.5S10.33 8 9.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S13.67 5 14.5 5s1.5.67 1.5 1.5S15.33 8 14.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S16.67 9 17.5 9s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z",
  clock: "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z",
  group: "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z",
  phone: "M17 1.01 7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z",
  tablet: "M18 0H6C4.34 0 3 1.34 3 3v18c0 1.66 1.34 3 3 3h12c1.66 0 3-1.34 3-3V3c0-1.66-1.34-3-3-3zm-4 22h-4v-1h4v1zm5.25-3H4.75V3h14.5v16z",
  place: "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z",
  block: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM4 12c0-4.42 3.58-8 8-8 1.85 0 3.55.63 4.9 1.69L5.69 16.9C4.63 15.55 4 13.85 4 12zm8 8c-1.85 0-3.55-.63-4.9-1.69L18.31 7.1C19.37 8.45 20 10.15 20 12c0 4.42-3.58 8-8 8z",
  computer: "M20 18c1.1 0 1.99-.9 1.99-2L22 6c0-1.1-.9-2-2-2H4c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2H0v2h24v-2h-4zM4 6h16v10H4V6z",
  eye: "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z",
  shield: "M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-2 16-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z",
  key: "M12.65 10A5.99 5.99 0 0 0 7 6c-3.31 0-6 2.69-6 6s2.69 6 6 6a5.99 5.99 0 0 0 5.65-4H17v4h4v-4h2v-4H12.65zM7 14c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z",
  wifi: "M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8 3 3 3-3a4.237 4.237 0 0 0-6 0zm-4-4 2 2a7.074 7.074 0 0 1 10 0l2-2C15.14 9.14 8.87 9.14 5 13z",
  info: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z",
  del: "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
  speed: "M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z",
  apps: "M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z",
  tune: "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z",
  viewday: "M2 21h19v-3H2v3zM20 8H3c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h17c.55 0 1-.45 1-1V9c0-.55-.45-1-1-1zM2 3v3h19V3H2z",
  dim: "M20 15.31 23.31 12 20 8.69V4h-4.69L12 .69 8.69 4H4v4.69L.69 12 4 15.31V20h4.69L12 23.31 15.31 20H20v-4.69zM12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6z",
  bright: "M20 8.69V4h-4.69L12 .69 8.69 4H4v4.69L.69 12 4 15.31V20h4.69L12 23.31 15.31 20H20v-4.69L23.31 12 20 8.69zM12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6zm0-10c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4z",
  traffic: "M20 10h-3V8.86c1.72-.45 3-2 3-3.86h-3V4c0-.55-.45-1-1-1H8c-.55 0-1 .45-1 1v1H4c0 1.86 1.28 3.41 3 3.86V10H4c0 1.86 1.28 3.41 3 3.86V15H4c0 1.86 1.28 3.41 3 3.86V20c0 .55.45 1 1 1h8c.55 0 1-.45 1-1v-1.14c1.72-.45 3-2 3-3.86h-3v-1.14c1.72-.45 3-2 3-3.86zm-8 9a2 2 0 1 1 0-4 2 2 0 0 1 0 4zm0-5a2 2 0 1 1 0-4 2 2 0 0 1 0 4zm0-5a2 2 0 0 1-2-2c0-1.11.89-2 2-2a2 2 0 1 1 0 4z",
  book: "M18 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 4h5v8l-2.5-1.5L6 12V4z",
  cloud: "M19.35 10.04A7.49 7.49 0 0 0 12 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 0 0 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z",
  open: "M19 19H5V5h7V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z",
};
export const I = (n, c = "") => `<svg class="i ${c}" viewBox="0 0 24 24" aria-hidden="true"><path d="${P[n] || ""}"/></svg>`;
export const logo = (size = 46) => `<img class="logo" src="/web/icon.svg" width="${size}" height="${size}" alt="" style="border-radius:${Math.round(size * .28)}px">`;

// ── building blocks (HTML strings; wiring is done by data-* attributes) ───────────
/** Row with optional icon bubble, subtitle (blue when it's a live state), and end content. */
export function row(title, { sub, blue, icon, tint = "var(--blue)", end = "", click, attrs = "", dis } = {}) {
  return `<div class="row${click ? " click" : ""}${dis ? " dis" : ""}" ${click ? `data-act="${esc(click)}"` : ""} ${attrs}>
    ${icon ? `<span class="ri" style="color:${tint};background:color-mix(in srgb, ${tint} 16%, transparent)">${I(icon)}</span>` : ""}
    <div class="t"><b>${esc(title)}</b>${sub ? `<small class="${blue ? "blue" : ""}">${esc(sub)}</small>` : ""}</div>${end}</div>`;
}
export const group = inner => `<div class="group glass">${inner}</div>`;
export const sec = t => `<div class="sec">${esc(t)}</div>`;
export const note = t => `<p class="note">${esc(t)}</p>`;
export const sw = on => `<span class="sw${on ? " on" : ""}"></span>`;
export const radio = on => `<span class="radio${on ? " on" : ""}"></span>`;
export const switchRow = (title, sub, on, key, { blue, dis } = {}) => row(title, { sub, blue: blue ?? on, end: sw(on), click: key, dis });
export const links = (items) => `<div class="links glass"><b>Looking for something else?</b>${items.map(([l, k]) => `<button data-act="${esc(k)}">${esc(l)}</button>`).join("")}</div>`;
export function expand(title, sub, icon, body, { blue, tint } = {}) {
  return `<div class="expand"><div class="row click" data-expand>${icon ? `<span class="ri" style="color:${tint || "var(--blue)"};background:color-mix(in srgb, ${tint || "var(--blue)"} 16%, transparent)">${I(icon)}</span>` : ""}
    <div class="t"><b>${esc(title)}</b>${sub ? `<small class="${blue ? "blue" : ""}">${esc(sub)}</small>` : ""}</div><span class="chev muted">${I("down")}</span></div><div class="xb">${body}</div></div>`;
}
export function slider(title, key, value, min, max, label, dis) {
  const p = (value - min) / (max - min) * 100;
  return `<div class="sliderow"><div class="h"><span>${esc(title)}</span><span data-lbl="${key}">${esc(label)}</span></div>
    <input type="range" min="${min}" max="${max}" value="${value}" data-range="${key}" style="--p:${p}%" ${dis ? "disabled" : ""}></div>`;
}
export function segmented(options, selected, key) {
  return `<div class="seg glass" data-seg="${key}" style="--n:${options.length}"><span class="bead" style="width:calc((100% - 8px) / ${options.length});transform:translateX(${selected * 100}%)"></span>
    ${options.map((o, i) => `<button class="${i === selected ? "on" : ""}" data-segi="${i}">${esc(o)}</button>`).join("")}</div>`;
}
export const bar = (frac, color = "var(--blue)") => `<div class="bar"><i style="width:${Math.round(Math.max(0, Math.min(1, frac || 0)) * 100)}%;background:${color}"></i></div>`;
export const usageColor = f => f > .95 ? "var(--red)" : f > .85 ? "var(--amber)" : "var(--blue)";

// ── toast & sheets ──────────────────────────────────────────────────────────────
let toastT;
export function toast(m) {
  const t = $("#toast"); t.textContent = m; t.className = "frost on"; clearTimeout(toastT);
  toastT = setTimeout(() => t.className = "frost", 2800);
}
export function sheet(html, onClose) {
  const s = $("#sheet"); s.innerHTML = `<div class="box glass">${html}</div>`; s.hidden = false;
  s.onclick = e => { if (e.target === s) { closeSheet(); onClose?.(); } };
  return $(".box", s);
}
export function closeSheet() { const s = $("#sheet"); s.hidden = true; s.innerHTML = ""; }
const acts = btns => `<div class="acts">${btns.map((b, i) => `${i ? "<i></i>" : ""}<button data-b="${i}" style="${b.color ? `color:${b.color}` : ""}" ${b.dis ? "disabled" : ""}>${esc(b.label)}</button>`).join("")}</div>`;
/** One UI dialog. buttons: [{label, color, value}] — resolves to the clicked value (or null). */
export function dialog(title, text, buttons, body = "") {
  return new Promise(res => {
    const box = sheet(`${title ? `<h2>${esc(title)}</h2>` : ""}${text ? `<p>${esc(text)}</p>` : ""}${body ? `<div class="body">${body}</div>` : ""}${acts(buttons)}`, () => res(null));
    $$("[data-b]", box).forEach(b => b.onclick = () => { const v = buttons[+b.dataset.b].value; closeSheet(); res(v === undefined ? true : v); });
  });
}
export const confirm = (title, text, ok, color = "var(--red)") => dialog(title, text, [{ label: "Cancel", value: false }, { label: ok, color, value: true }]);
/** Pick one option. options: [[value, label, sub]] */
export function choose(title, options, current) {
  return new Promise(res => {
    const box = sheet(`<h2>${esc(title)}</h2><div class="body">${options.map(([v, l, s], i) => `<button class="choice" data-c="${i}"><span class="t">${esc(l)}${s ? `<small>${esc(s)}</small>` : ""}</span>${radio(v === current)}</button>`).join("")}</div>${acts([{ label: "Cancel" }])}`, () => res(null));
    $$("[data-c]", box).forEach(b => b.onclick = () => { closeSheet(); res(options[+b.dataset.c][0]); });
    $("[data-b]", box).onclick = () => { closeSheet(); res(null); };
  });
}
/** Ask for a line of text. */
export function ask(title, placeholder, value = "", ok = "Save") {
  return new Promise(res => {
    const box = sheet(`<h2>${esc(title)}</h2><div class="pad"><input class="field" id="askv" placeholder="${esc(placeholder)}" value="${esc(value)}" maxlength="40"></div>${acts([{ label: "Cancel" }, { label: ok, color: "var(--blue)" }])}`, () => res(null));
    const inp = $("#askv", box); inp.focus(); inp.select();
    const done = v => { closeSheet(); res(v); };
    $$("[data-b]", box).forEach(b => b.onclick = () => done(b.dataset.b === "1" ? inp.value.trim() : null));
    inp.onkeydown = e => { if (e.key === "Enter") done(inp.value.trim()); };
  });
}
/** "Approve on your phone": the server forwarded a risky action to an admin phone. */
export async function waitApproval(id, get, code) {
  let stop = false;
  const until = Date.now() + 600000;
  const box = sheet(`<h2>Approve on your phone</h2><p>Nova sent this to your admin phone. Open the notification (or Nova → Menu → Users &amp; devices → Approvals) and confirm with your fingerprint.</p>
    ${code ? `<p>…or on the server: <code>sudo nova approve ${esc(code)}</code></p>` : ""}<p class="muted" id="apleft" style="text-align:center"></p>
    <div class="center" style="padding:14px"><div class="spinner" style="margin:auto"></div></div>${acts([{ label: "Stop waiting" }])}`, () => { stop = true; });
  $("[data-b]", box).onclick = () => { stop = true; closeSheet(); };
  const tick = setInterval(() => { const el = $("#apleft"), s = Math.max(0, Math.round((until - Date.now()) / 1000)); if (el) el.textContent = `Expires in ${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; if (stop || !el) clearInterval(tick); }, 1000);
  for (let i = 0; i < 300 && !stop; i++) {
    await sleep(2000);
    let a; try { a = await get(`/api/v1/approvals/${id}`); } catch { continue; }
    if (a.state === "done") { closeSheet(); return a.result || {}; }
    if (["failed", "denied", "expired"].includes(a.state)) {
      closeSheet(); const e = new Error(a.state === "denied" ? "Declined on the phone" : (a.result?.error || `Request ${a.state}`)); e.code = 403; throw e;
    }
  }
  const e = new Error(stop ? "Stopped waiting — the request expires on its own" : "No approval"); e.code = 408; throw e;
}

// ── wiring helpers ───────────────────────────────────────────────────────────────
/** Expand rows, segmented controls (with sliding bead), ranges with a live label. */
export function wireCommon(root, { onSeg, onRange, onRangeInput } = {}) {
  $$("[data-expand]", root).forEach(r => r.onclick = () => r.parentElement.classList.toggle("open"));
  $$("[data-seg]", root).forEach(s => $$("[data-segi]", s).forEach(b => b.onclick = () => {
    const i = +b.dataset.segi; $(".bead", s).style.transform = `translateX(${i * 100}%)`;
    $$("[data-segi]", s).forEach(x => x.classList.toggle("on", x === b)); onSeg?.(s.dataset.seg, i);
  }));
  $$("[data-range]", root).forEach(r => {
    const k = r.dataset.range, upd = () => { r.style.setProperty("--p", (r.value - r.min) / (r.max - r.min) * 100 + "%"); onRangeInput?.(k, +r.value); };
    r.oninput = upd; r.onchange = () => onRange?.(k, +r.value);
  });
}

/**
 * Hold a row and drag it to reorder (like the app). Rows need data-key; calls onMove(newOrder).
 * The lifted row follows the finger; the others slide out of its way.
 */
export function reorderable(list, onMove) {
  const rows = () => $$(".row[data-key]", list);
  rows().forEach(row => {
    let timer, startY, lifted = false, h, from, to;
    const cancel = () => { clearTimeout(timer); };
    row.addEventListener("pointerdown", e => {
      if (e.target.closest("[data-noreorder]")) return;
      startY = e.clientY;
      timer = setTimeout(() => {
        lifted = true; row.setPointerCapture(e.pointerId); row.classList.add("lift");
        h = row.getBoundingClientRect().height; from = rows().indexOf(row); to = from; navigator.vibrate?.(10);
      }, e.pointerType === "mouse" ? 180 : 350);
    });
    row.addEventListener("pointermove", e => {
      if (!lifted) { if (Math.abs(e.clientY - startY) > 8) cancel(); return; }
      e.preventDefault();
      const dy = e.clientY - startY; row.style.transform = `translateY(${dy}px) scale(1.03)`;
      const all = rows(); to = Math.max(0, Math.min(all.length - 1, from + Math.round(dy / h)));
      all.forEach((r, i) => { if (r === row) return; const s = i > from && i <= to ? -1 : i < from && i >= to ? 1 : 0; r.style.transform = s ? `translateY(${s * h}px)` : ""; });
    });
    const end = () => {
      cancel(); if (!lifted) return; lifted = false;
      rows().forEach(r => { r.style.transform = ""; }); row.classList.remove("lift");
      if (to !== from) { const keys = rows().map(r => r.dataset.key); keys.splice(to, 0, keys.splice(from, 1)[0]); onMove(keys); navigator.vibrate?.(6); }
    };
    row.addEventListener("pointerup", end); row.addEventListener("pointercancel", end);
    row.addEventListener("contextmenu", e => e.preventDefault());
  });
}

/** Long-press (and right-click) handler, for "hold the bar to edit". */
export function onHold(el, fn) {
  if (!el) return;
  let t; el.addEventListener("pointerdown", () => { t = setTimeout(() => { t = null; navigator.vibrate?.(10); fn(); }, 500); });
  ["pointerup", "pointerleave", "pointercancel"].forEach(ev => el.addEventListener(ev, () => clearTimeout(t)));
  el.addEventListener("contextmenu", e => { e.preventDefault(); fn(); });
}

// ── live graphs: monotone cubic that glides left by one step when a new sample arrives ──
const graphs = new WeakMap();
const css = v => getComputedStyle(document.documentElement).getPropertyValue(v).trim() || v;
function smooth(c, X, Y) {
  const n = X.length; c.moveTo(X[0], Y[0]);
  if (n < 3) { for (let i = 1; i < n; i++) c.lineTo(X[i], Y[i]); return; }
  const d = []; for (let i = 0; i < n - 1; i++) d.push((Y[i + 1] - Y[i]) / Math.max(.001, X[i + 1] - X[i]));
  const m = Y.map((_, i) => i === 0 ? d[0] : i === n - 1 ? d[n - 2] : d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2);
  for (let i = 0; i < n - 1; i++) {
    if (d[i] === 0) { m[i] = m[i + 1] = 0; continue; }
    const a = m[i] / d[i], b = m[i + 1] / d[i], q = a * a + b * b;
    if (q > 9) { const t = 3 / Math.sqrt(q); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]; }
  }
  for (let i = 0; i < n - 1; i++) { const k = (X[i + 1] - X[i]) / 3; c.bezierCurveTo(X[i] + k, Y[i] + m[i] * k, X[i + 1] - k, Y[i + 1] - m[i + 1] * k, X[i + 1], Y[i + 1]); }
}
/**
 * spark(canvas, values, colorVar, {max, window, tick}): [window] steps visible; when [tick] changes the
 * line glides one step left over ~1 s instead of jumping (like the app's status page).
 */
export function spark(canvas, vals, color, { max = null, window = 0, tick = 0, added = 1 } = {}) {
  if (!canvas) return;
  let g = graphs.get(canvas);
  if (!g) { g = { t0: 0, tick: null, top: null }; graphs.set(canvas, g); }
  if (g.tick !== tick) { g.tick = tick; g.t0 = performance.now(); g.added = Math.max(1, Math.min(5, added)); }
  g.vals = vals; g.color = css(color); g.max = max; g.window = window;
  if (g.running) return; g.running = true;
  const frame = now => {
    if (!canvas.isConnected) { g.running = false; return; }
    const dpr = devicePixelRatio || 1, w = canvas.clientWidth, h = canvas.clientHeight;
    if (canvas.width !== Math.round(w * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr); }
    const c = canvas.getContext("2d"); c.setTransform(dpr, 0, 0, dpr, 0, 0); c.clearRect(0, 0, w, h);
    const v = g.vals;
    if (v.length >= 2 && w > 0) {
      const peak = g.max ?? Math.max(1, Math.max(...v) * 1.15);
      g.top = g.top == null ? peak : g.top + (peak - g.top) * .12;            // the scale eases too
      const steps = g.window > 0 ? g.window : v.length - 1, dx = w / steps;
      const reduce = document.documentElement.classList.contains("reduce");
      const p = reduce || !g.window ? 1 : Math.min(1, (now - g.t0) / 950);
      const shift = g.window > 0 ? (1 - p) * dx * (g.added || 1) : 0;      // glide by however many points arrived
      const first = g.window > 0 ? Math.max(0, v.length - 1 - g.window - 1 - (g.added || 1)) : 0;
      const X = [], Y = [];
      for (let i = first; i < v.length; i++) { X.push(w - (v.length - 1 - i) * dx + shift); Y.push(h - Math.min(1, Math.max(0, v[i] / g.top)) * h * .94 - h * .03); }
      c.beginPath(); smooth(c, X, Y);
      c.save(); c.lineTo(X.at(-1), h); c.lineTo(X[0], h); c.closePath();
      const gr = c.createLinearGradient(0, 0, 0, h); gr.addColorStop(0, g.color + "52"); gr.addColorStop(1, g.color + "00");
      c.fillStyle = gr; c.fill(); c.restore();
      c.beginPath(); smooth(c, X, Y); c.lineWidth = 2.2; c.lineJoin = "round"; c.lineCap = "round"; c.strokeStyle = g.color; c.stroke();
      c.fillStyle = g.color + "40"; c.beginPath(); c.arc(X.at(-1), Y.at(-1), 7, 0, 7); c.fill();
      c.fillStyle = g.color; c.beginPath(); c.arc(X.at(-1), Y.at(-1), 3.5, 0, 7); c.fill();
    }
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}
export { css };

/**
 * Swipe a row sideways to reveal an action (like the app): past 35 % of the width it arms (a buzz),
 * and letting go there slides the row away and runs it. right = swipe right, left = swipe left.
 * Rows: <div class="swipe" data-right="Ignore" data-left="Delete"><div class="swbg"></div><div class="swfg">…</div></div>
 */
export function swipeable(root, { onRight, onLeft } = {}) {
  $$(".swipe", root).forEach(el => {
    const fg = $(".swfg", el), bg = $(".swbg", el);
    let x0 = null, y0 = 0, dx = 0, armed = false, horiz = null;
    el.addEventListener("pointerdown", e => { if (e.button > 0) return; x0 = e.clientX; y0 = e.clientY; dx = 0; horiz = null; armed = false; });
    el.addEventListener("pointermove", e => {
      if (x0 == null) return;
      const mx = e.clientX - x0, my = e.clientY - y0;
      if (horiz == null) { if (Math.abs(mx) < 8 && Math.abs(my) < 8) return; horiz = Math.abs(mx) > Math.abs(my); if (horiz) el.setPointerCapture(e.pointerId); }
      if (!horiz) return;
      dx = mx; if (dx > 0 && !el.dataset.right) dx = 0; if (dx < 0 && !el.dataset.left) dx = 0;
      fg.style.transition = "none"; fg.style.transform = `translateX(${dx}px)`;
      const w = el.clientWidth, past = Math.abs(dx) > w * .35, side = dx > 0 ? "right" : "left";
      bg.className = "swbg " + side; bg.textContent = dx ? el.dataset[side] : ""; bg.style.opacity = Math.min(1, .3 + Math.abs(dx) / (w * .35) * .7);
      if (past !== armed) { armed = past; navigator.vibrate?.(past ? 12 : 4); }
    });
    const end = () => {
      if (x0 == null) return; x0 = null; if (!horiz) return;
      fg.style.transition = "";
      if (armed) {
        const w = el.clientWidth, right = dx > 0; fg.style.transform = `translateX(${right ? w : -w}px)`;
        el.style.height = el.offsetHeight + "px"; void el.offsetHeight; el.classList.add("gone"); el.addEventListener("click", ev => ev.stopPropagation(), { capture: true, once: true });
        setTimeout(() => (right ? onRight : onLeft)?.(el.dataset.key, el), 180);
      } else { fg.style.transform = ""; bg.textContent = ""; }
    };
    el.addEventListener("pointerup", end); el.addEventListener("pointercancel", end);
  });
}

/** Custom colour: hue / saturation / brightness, exact R G B values, or a hex code. Resolves "#rrggbb" or null. */
export function colorPicker(initial = "#3e91ff") {
  const toHsv = ([r, g, b]) => { r /= 255; g /= 255; b /= 255; const mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn;
    let h = 0; if (d) h = mx === r ? ((g - b) / d) % 6 : mx === g ? (b - r) / d + 2 : (r - g) / d + 4; return [(h * 60 + 360) % 360, mx ? d / mx : 0, mx]; };
  const fromHsv = (h, s, v) => { const f = n => { const k = (n + h / 60) % 6; return Math.round(255 * (v - v * s * Math.max(0, Math.min(k, 4 - k, 1)))); }; return [f(5), f(3), f(1)]; };
  const hx = rgb => "#" + rgb.map(x => x.toString(16).padStart(2, "0")).join("");
  let rgb = [1, 3, 5].map(i => parseInt((initial || "#3e91ff").slice(i, i + 2), 16) || 0), hue = toHsv(rgb)[0];
  return new Promise(res => {
    const box = sheet(`<h2>Custom colour</h2><div class="pad">
      <div id="cp-prev" style="height:56px;border-radius:18px;margin-bottom:12px"></div>
      ${["Hue:h:0:360", "Saturation:s:0:100", "Brightness:v:0:100"].map(x => { const [l, k, a, b] = x.split(":"); return `<div class="muted" style="font-size:13px;margin-top:6px">${l}</div><input type="range" min="${a}" max="${b}" data-cp="${k}">`; }).join("")}
      ${["R:#ff453a", "G:#32d74b", "B:#0a84ff"].map((x, i) => { const [l, c] = x.split(":"); return `<div style="display:flex;align-items:center;gap:10px;margin-top:10px"><b style="color:${c};width:16px">${l}</b><input type="range" min="0" max="255" data-ch="${i}" style="flex:1"><input class="field" data-num="${i}" inputmode="numeric" maxlength="3" style="width:76px;padding:10px 12px;font-family:ui-monospace,monospace"></div>`; }).join("")}
      <input class="field" id="cp-hex" maxlength="7" style="margin-top:12px;font-family:ui-monospace,monospace" placeholder="#rrggbb"></div>
      <div class="acts"><button data-b="0">Cancel</button><i></i><button data-b="1" style="color:var(--blue)">Done</button></div>`, () => res(null));
    const sync = (from) => {
      const [h0, s, v] = toHsv(rgb); if (s > 0) hue = h0;
      $("#cp-prev", box).style.background = hx(rgb);
      if (from !== "hsv") { const set = (k, val) => { const e = $(`[data-cp="${k}"]`, box); e.value = val; e.style.setProperty("--p", val / e.max * 100 + "%"); };
        set("h", Math.round(hue)); set("s", Math.round(s * 100)); set("v", Math.round(v * 100)); }
      rgb.forEach((x, i) => { const e = $(`[data-ch="${i}"]`, box); e.value = x; e.style.setProperty("--p", x / 255 * 100 + "%"); if (from !== "num" + i) $(`[data-num="${i}"]`, box).value = x; });
      if (from !== "hex") $("#cp-hex", box).value = hx(rgb);
    };
    $$("[data-cp]", box).forEach(e => e.oninput = () => { e.style.setProperty("--p", e.value / e.max * 100 + "%");
      const g = k => +$(`[data-cp="${k}"]`, box).value; hue = g("h"); rgb = fromHsv(hue, g("s") / 100, g("v") / 100); sync("hsv"); });
    $$("[data-ch]", box).forEach(e => e.oninput = () => { rgb[+e.dataset.ch] = +e.value; sync(); });
    $$("[data-num]", box).forEach(e => e.oninput = () => { const n = parseInt(e.value.replace(/\D/g, ""), 10); if (!isNaN(n)) { rgb[+e.dataset.num] = Math.max(0, Math.min(255, n)); sync("num" + e.dataset.num); } });
    $("#cp-hex", box).oninput = e => { const v = e.target.value.startsWith("#") ? e.target.value : "#" + e.target.value; if (/^#[0-9a-f]{6}$/i.test(v)) { rgb = [1, 3, 5].map(i => parseInt(v.slice(i, i + 2), 16)); sync("hex"); } };
    $$("[data-b]", box).forEach(b => b.onclick = () => { closeSheet(); res(b.dataset.b === "1" ? hx(rgb) : null); });
    sync();
  });
}
