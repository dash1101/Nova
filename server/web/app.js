// Nova web — entry: router (with back stack, scroll memory and slide transitions), the app shell
// (the frosted nav pill: along the bottom on phones, down the left on wide screens), pairing, start-up.
import { S, $, $$, esc, sleep, kv, pemOf, get, post, prefs, has, refresh, onApproval, ApiError, webForm, levelColor, cleanTitle, switchServer, savedTarget, loadNodes, gpuInfo } from "./core.js";
let fpsEl = null;                 // Developer options → Show frame rate (the meter element)
import { I, logo, toast, waitApproval, closeSheet, copyCmd } from "./ui.js";
import * as V from "./views.js";
import { loadLang, watch } from "./i18n.js";
import { liquid } from "./glass.js";
import * as ST from "./storage.js";
import * as SR from "./start.js";
import * as SV from "./servers.js";
import * as FM from "./files.js";
import * as PT from "./parts.js";
import * as SEC from "./sec.js";
import * as WM from "./winmgr.js";
import * as SND from "./sounds.js";
import * as LV from "./living.js";
import * as AMB from "./ambience.js";
import "./gamepad.js";
import "./eggs.js";

onApproval((id, code, totp, pk) => waitApproval(id, get, code, totp, post, pk));

// ── theme ────────────────────────────────────────────────────────────────────────
let measuredSlow = (() => { try { return sessionStorage.getItem("nova-slow") === "1"; } catch { return false; } })();
function slowMachine() {
  return measuredSlow || gpuInfo().software || (navigator.hardwareConcurrency || 8) <= 2 || (navigator.deviceMemory || 8) <= 2;
}
// one quick look at the frame rate after start: if a second of frames averages under ~40 fps, go light
(() => {
  let n = 0, t0 = 0;
  const tick = t => { if (!t0) t0 = t; n++; if (t - t0 < 1000) return requestAnimationFrame(tick);
    if (n < 40 && document.visibilityState === "visible") { measuredSlow = true; try { sessionStorage.setItem("nova-slow", "1"); } catch {} if (prefs.lite !== "off") document.documentElement.classList.add("lite"); } };
  setTimeout(() => requestAnimationFrame(tick), 1500);
})();
/** How much body Glass has at a clarity (0..1): 1 at 10% (the old frosted look), 0 at 100%. */
function glassBody(clear) { return ((1 - clear) / .9) ** 2; }

function applyTheme() {
  const t = prefs.theme, root = document.documentElement;
  if (t === "system") delete root.dataset.theme; else root.dataset.theme = t;
  if (prefs.iconTheme && prefs.iconTheme !== "style") root.dataset.icons = prefs.iconTheme; else delete root.dataset.icons;
  if (prefs.webIcon && prefs.webIcon !== "default") import("./webicon.js").then(m => m.applyWebIcon()).catch(() => {});      // "Your color" follows the accent
  root.classList.toggle("reduce", prefs.reduceMotion || matchMedia("(prefers-reduced-motion: reduce)").matches);
  // Lighter effects: no frosted blur or soft shadows (the costly parts on older computers and phones).
  // "auto" turns it on for slow machines — few cores or little memory, or a choppy first second (measured once).
  root.classList.toggle("lite", prefs.lite === "on" || (prefs.lite !== "off" && slowMachine()));
  const dark = t === "dark" || (t === "system" && !matchMedia("(prefers-color-scheme: light)").matches);
  // Style: Material You on Android phones that aren't Samsung (or another One UI/iOS-like maker); the Default look elsewhere
  const autoMaterial = /Android/i.test(navigator.userAgent) && !/SamsungBrowser|SM-[A-Z0-9]|Xiaomi|Redmi|POCO|HUAWEI|HONOR|vivo|OPPO|realme/i.test(navigator.userAgent);
  const preset = ["liquid", "fluent", "breeze", "aero"].includes(prefs.style) ? prefs.style : "";
  const material = !preset && (prefs.style === "material" || (prefs.style !== "default" && autoMaterial));
  root.classList.toggle("material", material); root.classList.toggle("m-dark", material && dark); root.classList.toggle("m-light", material && !dark);
  for (const p of ["liquid", "fluent", "breeze", "aero"]) root.classList.toggle(p, preset === p);
  root.classList.toggle("l-light", !dark);
  // Background (Appearance → Wallpaper): the look's own, the Default look's glow on any look, or plain
  for (const k of ["glow", "vivid", "soft", "aero", "plain"]) root.classList.toggle("bg-" + k, prefs.lookBg === k);
  root.classList.toggle("no-scrims", prefs.scrims === false);
  if (prefs.scrimColor && /^#[0-9a-f]{6}$/i.test(prefs.scrimColor)) root.style.setProperty("--scrim", prefs.scrimColor); else root.style.removeProperty("--scrim");
  // the Default look's own Transparency and Frost (Appearance): see-through cards, and how blurred what's behind them is
  const dlook = !preset && !material, dtr = Math.max(0, Math.min(85, +(prefs.dTrans ?? 0))), dfr = Math.max(0, Math.min(40, +(prefs.dFrost ?? 26)));
  root.classList.toggle("dtune", dlook && (dtr > 0 || dfr !== 26));
  if (dlook) { root.style.setProperty("--dtr", String(dtr)); root.style.setProperty("--dfr", dfr + "px"); } else { root.style.removeProperty("--dtr"); root.style.removeProperty("--dfr"); }
  root.dataset.icshape = prefs.iconShape || "rounded";             // Appearance → Icon shape
  root.classList.toggle("icon-acc", !!prefs.iconAccent);           // Appearance → Icons → in your accent color
  root.style.setProperty("--wrk", String(Math.max(0, Math.min(300, +(prefs.winRound ?? 100))) / 100));   // Window roundness: windows and dialogs
  // Reduce transparency: every glass surface solid (also when the system asks for it)
  const solid = !!prefs.reduceTransparency || matchMedia("(prefers-reduced-transparency: reduce)").matches;
  root.classList.toggle("solid", solid);
  // how clear the glass is (Liquid Glass, Fluent) and how round the corners are (the three presets)
  const clear = Math.max(0, Math.min(100, prefs.glass ?? 60)) / 100;
  // Glass clarity: for Glass it also sets how sharp the page behind is (clear = liquid, low = frosted); 10% is the old
  // frosted Glass, 100% almost bare glass
  if (preset === "liquid" || preset === "fluent" || preset === "aero") root.style.setProperty("--ga", String(preset === "liquid" ? +(.02 + .08 * glassBody(clear)).toFixed(3) : preset === "aero" ? .82 - clear * .5 : .95 - clear * .45)); else root.style.removeProperty("--ga");
  if (preset === "aero") root.style.setProperty("--ae-blur", (4 + 12 * (1 - clear)).toFixed(1) + "px"); else root.style.removeProperty("--ae-blur");
  if (preset === "liquid") { root.style.setProperty("--lg-blur", (1 + 13 * glassBody(clear)).toFixed(1) + "px"); root.dataset.clear = clear.toFixed(2); }
  else { root.style.removeProperty("--lg-blur"); delete root.dataset.clear; }
  // Glass stops at 200%: past that its cards turn into ovals
  // Corner roundness: 25–200% on the slider, kept for each look. Up to 100% it's the plain scale; above, Glass reaches
  // 1.8× at 200% (rounder turns its cards into ovals) and the other looks 4× (the old 400%)
  const rv = Math.max(25, Math.min(200, prefs["round_" + (preset || "default")] ?? prefs.round ?? 100));
  root.style.setProperty("--rk", String(rv <= 100 ? rv / 100 : 1 + (rv - 100) / 100 * (preset === "liquid" ? .8 : 3)));
  liquid(preset === "liquid" && !solid && !root.classList.contains("lite") && !prefs.devNoLens);
  root.classList.toggle("dev-bounds", !!prefs.devBounds); fpsMeter(!!prefs.devFps);
  $('meta[name="theme-color"]')?.setAttribute("content", dark ? "#000000" : "#f4f3f8");
  // Your own colors (Appearance → Colors): an accent for this device, how strongly it tints, and a wallpaper
  root.style.setProperty("--tintk", String(Math.max(0, Math.min(100, prefs.tint ?? 50)) / 50));
  if (/^#[0-9a-f]{6}$/i.test(prefs.accentLocal || "")) root.style.setProperty("--blue", prefs.accentLocal);
  wallpaper().then(readability);
  readability();
  requestAnimationFrame(() => { try { prefs.bgc = getComputedStyle(root).getPropertyValue("--bg").trim(); } catch {} });   // for early.js next load
}
/** Readability (Appearance → Icons and text). Automatic: text is drawn stronger — a soft halo in the page color, gray
 *  text closer to the text color, and at level 2 a little bolder — only where it's needed: see-through surfaces over a
 *  picture, a living wallpaper or a vivid background, or a picture whose brightness fights the text (light text on a
 *  bright picture, dark text on a dark one). "more": always level 2; "off": never. */
function readability() {
  const root = document.documentElement, c = root.classList, pref = prefs.readability || "auto";
  let lv = pref === "more" ? 2 : 0;
  if (pref === "auto" && !c.contains("solid")) {
    const clear = Math.max(0, Math.min(100, prefs.glass ?? 60)) / 100, dark = !c.contains("l-light");
    const busy = c.contains("has-wall") || c.contains("living") || ["vivid", "aero"].includes(prefs.lookBg);
    const seeThrough = c.contains("liquid") ? clear >= .3 : c.contains("aero") ? clear >= .55 : c.contains("fluent") ? clear >= .75
      : !c.contains("material") && !c.contains("breeze") && (+(prefs.dTrans ?? 0)) >= 30;
    const lum = c.contains("has-wall") ? wallLum : null;                  // 0 (black) … 1 (white), measured once per picture
    const fights = lum != null && (dark ? lum > .62 : lum < .3);
    if (busy && seeThrough) lv = 1;
    if (busy && seeThrough && (fights || (c.contains("liquid") && clear >= .7) || (+(prefs.dTrans ?? 0)) >= 60)) lv = 2;
  }
  // text straight on the background (notes, section names, the title) over a picture or a living wallpaper: always helped
  c.toggle("rd-bg", pref !== "off" && (c.contains("has-wall") || c.contains("living") || ["vivid", "aero"].includes(prefs.lookBg)));
  c.toggle("rd1", lv >= 1); c.toggle("rd2", lv >= 2);
}
let wallLum = null, wallLumV = "";
async function measure(blob) {          // the picture's average brightness, from a tiny copy
  try { const b = await createImageBitmap(blob, { resizeWidth: 24, resizeHeight: 24 }), cv = new OffscreenCanvas(24, 24), g = cv.getContext("2d");
    g.drawImage(b, 0, 0); const d = g.getImageData(0, 0, 24, 24).data; let t = 0;
    for (let i = 0; i < d.length; i += 4) t += (.2126 * d[i] + .7152 * d[i + 1] + .0722 * d[i + 2]) / 255;
    const dim = (prefs.wallDim ?? 35) / 100; return t / (d.length / 4) * (1 - dim);      // the dimming darkens it
  } catch { return null; }
}
let wallUrl = null;
async function wallpaper() {
  const root = document.documentElement;
  let w = document.getElementById("wall");
  const data = await kv("wallpaper").catch(() => null);
  root.classList.toggle("has-wall", !!data); LV.update(); AMB.update();
  if (!data) { w?.remove(); if (wallUrl) { URL.revokeObjectURL(wallUrl); wallUrl = null; } return; }
  if (!w) { w = document.createElement("div"); w.id = "wall"; w.innerHTML = "<i></i>"; document.body.prepend(w); }
  if (!wallUrl || w.dataset.v !== String(data.size)) { if (wallUrl) URL.revokeObjectURL(wallUrl); wallUrl = URL.createObjectURL(data); w.dataset.v = String(data.size); }
  w.style.backgroundImage = `url("${wallUrl}")`;
  const key = data.size + ":" + (prefs.wallDim ?? 35); if (wallLumV !== key) { wallLumV = key; wallLum = await measure(data); }
  w.style.setProperty("--wblur", (prefs.wallBlur ?? 16) + "px"); w.style.setProperty("--wdim", String((prefs.wallDim ?? 35) / 100));
}
export { applyTheme, tab };
applyTheme();
matchMedia("(prefers-color-scheme: light)").addEventListener?.("change", applyTheme);

// ── routes ───────────────────────────────────────────────────────────────────────
const ROUTES = {
  home: V.home, menu: V.menu, status: V.status, containers: V.containers, store: V.store, inbox: V.inbox, notify: V.notify,
  quick: V.quick, "edit-quick": V.editQuick, lighting: V.lighting, lights: V.lights, schedules: V.schedules, schedule: V.schedule, hardware: V.hardware,
  partitions: PT.partitions, disks: PT.disks, recommended: V.recommended, security: SEC.security, devices: V.devices, settings: V.settings, server: V.serverSettings, appearance: V.appearance, "appearance-icons": V.appearanceIcons, "edit-home": V.editHome,
  "edit-shortcuts": V.editShortcuts, "edit-tabs": V.editTabs, about: V.about, guide: V.guide, faq: V.faq, terminal: V.terminal,
  dashboard: V.dashboard, "edit-dash": V.editDash, archive: V.archive,
  apps: ST.apps, updates: ST.updates, search: SR.search, start: SR.start, "start-edit": SR.startEdit, favorites: SR.favorites, servers: SV.servers, "container-new": ST.containerNew, term: V.hostTerminal, files: FM.files, cmdapp: ST.cmdApp, labs: ST.labs, freeup: ST.freeup, automation: ST.automation, features: ST.features, plugins: ST.plugins, "labs-images": ST.labsImages, wol: ST.wol, openrgb: ST.openrgb, access: ST.access, cloudflare: ST.cloudflare, setup: ST.setup, pool: ST.pool, task: ST.task, backups: ST.backups, backup: ST.backup, "backup-edit": ST.backupEdit, dev: V.dev, "ha-light": V.haLight, "backup-script": ST.backupScript, restore: ST.restore, diag: ST.diag,
};
// Wide screens and phones in landscape: the bottom bar's pill stands on its end down the left edge.
const SIDE = matchMedia("(min-width: 900px), (orientation: landscape) and (max-height: 540px)");
SIDE.addEventListener?.("change", () => { const n = $("#nav"); if (n) n.dataset.ids = ""; drawNav(parse()[0]); });
const parse = () => (location.hash.replace(/^#\/?/, "") || "home").split("/").filter(Boolean).map(decodeURIComponent);

// ── navigation: a real back stack on top of browser history ───────────────────────
let depth = history.state?.d ?? 0, seq = 0, current = null, rootRoute = "home";
const keyNow = () => history.state?.k ?? "0";
const scrolls = (() => { try { return JSON.parse(sessionStorage.getItem("nova.scrolls") || "{}"); } catch { return {}; } })();
function saveScroll() { scrolls[keyNow()] = scrollY; try { sessionStorage.setItem("nova.scrolls", JSON.stringify(scrolls)); } catch {} }
const newKey = () => Math.random().toString(36).slice(2, 10);
if (!history.state) history.replaceState({ k: newKey(), d: 0 }, "");

let pressing = false, heldShow = null;
addEventListener("pointerdown", () => { pressing = true; }, true);
for (const ev of ["pointerup", "pointercancel"]) addEventListener(ev, () => { pressing = false; if (heldShow) { const f = heldShow; heldShow = null; setTimeout(f, 0); } }, true);   // after the click
let maxDepth = depth;                                 // how far forward you can go again (Files windows have a forward button)
function go(path) { saveScroll(); history.pushState({ k: newKey(), d: depth + 1 }, "", "#/" + path); depth++; maxDepth = depth; render(1); }
function tab(path) {
  // Only real tabs (in your navigation pill) reset the stack; anything else opens as a page with a back button
  if (path.split("/")[0] !== "home" && !V.navTabs().some(t => t.route === path.split("/")[0])) return go(path);
  const r = path.split("/")[0], here = parse()[0];
  // already on this tab's own page: glide to the top instead of loading it again (like the app)
  if (r === here && parse().length === 1 && (r !== "home" || depth === 0)) { scrollTo({ top: 0, behavior: document.documentElement.classList.contains("reduce") ? "auto" : "smooth" }); return; }
  saveScroll(); rootRoute = r;
  if (r === "home") {                                  // Home is the bottom of the stack: unwind to it
    if (depth > 0) { pendingHome = true; history.go(-depth); return; }
    history.replaceState({ k: newKey(), d: 0 }, "", "#/home"); render(0); return;
  }
  if (depth === 0 && here === "home") {                 // a tab opened from Home sits on top of it (back → Home)
    history.pushState({ k: newKey(), d: 1, tab: true }, "", "#/" + path); depth = 1; render(0); return;
  }
  history.replaceState({ k: newKey(), d: depth, tab: true }, "", "#/" + path); render(0);
}
let pendingHome = false;
function back() {
  if (depth > 0) { history.back(); return; }
  if (WM.EMBED) return;                             // a Files/Terminal window: there's nothing to go back to

  if (parse()[0] !== "home") { saveScroll(); history.replaceState({ k: newKey(), d: 0 }, "", "#/home"); rootRoute = "home"; render(-1); }
}
addEventListener("popstate", e => {
  const d = e.state?.d ?? 0, dir = pendingHome ? 0 : d < depth ? -1 : 1; depth = d;
  if (pendingHome) { pendingHome = false; if (parse()[0] !== "home") history.replaceState({ k: newKey(), d: 0 }, "", "#/home"); }
  render(dir);
});
addEventListener("keydown", e => {
  if (e.key !== "Escape") return;
  if (!$("#sheet").hidden) { closeSheet(); return; }
  if ($("#alertbar")?.classList.contains("on")) { hideAlert(); return; }
  if (/INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName)) { document.activeElement.blur(); return; }
  // Esc = back (after the page had its say: the Inbox uses Esc to clear a selection)
  setTimeout(() => { if (!e.defaultPrevented && depth > 0) back(); });
});
// Copy buttons ([data-copy]) anywhere: the server command to approve a browser, etc.
addEventListener("click", async e => {
  const b = e.target.closest?.("[data-copy]"); if (!b) return;
  e.preventDefault(); e.stopPropagation();
  try { await navigator.clipboard.writeText(b.dataset.copy); }
  catch { const r = document.createRange(); const c = b.previousElementSibling || b; r.selectNodeContents(c); getSelection().removeAllRanges(); getSelection().addRange(r); toast("Selected — press Ctrl+C to copy"); return; }
  const was = b.innerHTML; b.innerHTML = I("check") + "Copied"; b.classList.add("done"); setTimeout(() => { b.innerHTML = was; b.classList.remove("done"); }, 1600);
}, true);

// ── the shell (page + top bar + nav pill), built once ────────────────────
function ensureShell() {
  if ($("#shell")) return;
  $("#app").innerHTML = `<div class="shell" id="shell"><main class="main" id="main"></main></div>
    <div class="scrim"></div><div class="scrim-bottom"></div><div class="topbar" id="topbar"></div><nav class="nav frost" id="nav" hidden></nav>
    <div class="ctlbar frost" id="ctlbar" hidden></div>`;
  addEventListener("scroll", fade, { passive: true });
}
/** A redraw makes new spinners: start them where the old ones were, so they keep turning without a jump. */
function syncSpinners(root) {
  const now = performance.now();
  for (const el of root.querySelectorAll(".spinner, .spin, .taskchip svg")) {
    const d = parseFloat(getComputedStyle(el).animationDuration) * 1000 || 900;
    el.style.animationDelay = `-${(now % d).toFixed(0)}ms`;
  }
}
/** "Controlling <server>" — always on screen while this web controls another server, with the way back. */
function drawCtl() {
  const b = $("#ctlbar"); if (!b) return;
  b.hidden = !S.target;
  if (S.target) { b.innerHTML = `${I("dns")}<span>Controlling <b>${esc(S.target.name)}</b></span><button id="ctlback" title="Back to ${esc(S.homeName || "this server")}">Switch back</button>`;
    $("#ctlback").onclick = () => SV.control(""); }
}
addEventListener("nova:server", () => { alertSince = null; alertQueue = []; drawCtl(); const n = $("#nav"); if (n) n.dataset.ids = ""; tab("home"); });
// Screens that stream or move files aren't carried through to another server (its own Nova web has them)
const NOT_RELAYED = ["term", "terminal", "files", "diag"];
function notRelayed(ctx) {
  const t = S.target, href = S.me && S.me.via === "remote" ? t.remote_url : t.lan_url;
  ctx.show(`<p class="note">This part of Nova (files, the terminal, speed tests) isn't carried from one server to another. Open ${esc(t.name)}'s own Nova web for it${href ? "" : " — it's on that server's home network address"}.</p>
    ${href ? `<a class="btn block" href="${esc(href)}" target="_blank" rel="noopener">${I("open")} Open ${esc(t.name)}'s Nova web</a>` : ""}`, { title: t.name });
}
function fade() { const f = Math.max(0, Math.min(1, scrollY / 36)); $$("#topbar .bgc").forEach(b => b.style.setProperty("--fade", f)); }
function drawNav(route) {
  const side = SIDE.matches, t = V.navTabs(), nav = $("#nav");
  if (!nav) return;
  if (WM.EMBED) { nav.hidden = true; document.body.classList.remove("side"); return; }   // Files/Terminal in a window: no pill
  // phones: only on the tab pages; side pill: always there, the bead on the tab you came from
  const i = t.findIndex(x => x.route === route) >= 0 ? t.findIndex(x => x.route === route) : side ? t.findIndex(x => x.route === rootRoute) : -1;
  nav.hidden = (i < 0 && !side) || route === "dashboard";
  document.body.classList.toggle("side", side && route !== "dashboard");
  nav.classList.toggle("vertical", side);
  if (nav.hidden) return;
  const was = nav.dataset.sel, ids = t.map(x => x.id).join();
  if (nav.dataset.ids !== ids) {
    // more tabs than fit: they slide, with the edges fading (like the apps in the phone app's dock)
    nav.innerHTML = `<div class="nscroll"><div class="items"><span class="bead"></span>${t.map(x => `<button class="press" aria-label="${esc(x.label)}" data-act="tab:${x.route}"><span class="ind">${I(x.icon)}</span><small>${esc(x.label)}</small></button>`).join("")}</div></div>`;
    nav.dataset.ids = ids;
    const sc = $(".nscroll", nav), edges = () => {
      const v = nav.classList.contains("vertical"), pos = v ? sc.scrollTop : sc.scrollLeft, max = v ? sc.scrollHeight - sc.clientHeight : sc.scrollWidth - sc.clientWidth;
      sc.classList.toggle("fs", pos > 2); sc.classList.toggle("fe", pos < max - 2);
    };
    sc.onscroll = edges; requestAnimationFrame(edges); addEventListener("resize", edges);
    let h; nav.onpointerdown = () => { h = setTimeout(() => { navigator.vibrate?.(10); go("edit-tabs"); }, 550); };
    nav.onpointerup = nav.onpointerleave = () => clearTimeout(h);
    nav.oncontextmenu = e => { e.preventDefault(); go("edit-tabs"); };
  }
  const bead = $(".bead", nav);
  if (was == null) { bead.style.transition = "none"; requestAnimationFrame(() => bead.style.transition = ""); }
  if (was != null && String(i) !== was && i >= 0) SND.play("slide");           // the selection sliding along the pill
  bead.style.opacity = i < 0 ? "0" : "1";
  $$("button", nav).forEach((btn, k) => btn.classList.toggle("on", k === i));
  bead.style.transform = side ? `translateY(${Math.max(0, i) * 60}px)` : `translateX(${Math.max(0, i) * 80}px)`; nav.dataset.sel = i;
  const on = $$("button", nav)[i];                       // the tab you're on scrolls into view
  if (on) on.scrollIntoView({ block: "nearest", inline: "nearest", behavior: document.documentElement.classList.contains("reduce") ? "auto" : "smooth" });
  $(".nscroll", nav)?.onscroll?.();
  WM.drawDock();
}

// ── animation speed (Appearance): every CSS transition and animation plays at the chosen rate as it starts ──
const animRate = () => Math.max(.25, Math.min(3, (+prefs.animSpeed || 100) / 100));
for (const ev of ["transitionrun", "animationstart"]) document.addEventListener(ev, e => {
  const k = animRate(); if (k === 1) return;
  for (const a of e.target.getAnimations?.() || []) if (a.playbackRate !== k) a.playbackRate = k;
}, true);
const rateViewTransition = t => t.ready?.then(() => { const k = animRate(); if (k !== 1) document.getAnimations().forEach(a => { if (String(a.effect?.pseudoElement || "").startsWith("::view-transition")) a.playbackRate = k; }); }).catch(() => {});

// ── render a route ───────────────────────────────────────────────────────────────
let lastRendered = null;
function render(dir = 0) {
  if (dir === 1) SND.feel("nav"); else if (dir === -1) SND.feel("back");     // going in or coming back has its own sound
  const my = ++seq, [r, ...args] = parse(), view = S.target && NOT_RELAYED.includes(r) ? notRelayed : ROUTES[r] || V.home;
  current?.leave(); closeSheet();
  if (V.navTabs().some(t => t.route === r) && depth === 0) rootRoute = r;
  let shown, firstShow = new Promise(res => shown = res), t0 = performance.now(), held = 0;
  const leaveFns = [], timers = [];
  let handlers = {};
  const ctx = {
    args, dir,
    alive: () => my === seq,
    go, tab, back,
    run: (name, ...a) => handlers[name]?.(...a),
    handlers: h => { handlers = { ...handlers, ...h }; },
    onLeave: fn => leaveFns.push(fn),
    applyTheme, refreshNav: () => { $("#nav") && ($("#nav").dataset.ids = ""); drawNav(r); },
    every(ms, fn, immediate = false) {
      let stop = false;
      const loop = async () => { while (!stop && ctx.alive()) { await sleep(ms); if (stop || !ctx.alive()) break; if (!document.hidden) { try { await fn(); } catch (e) { if (e.code === 401) unauthorized(); } } } };
      if (immediate) (async () => { try { await fn(); } catch (e) { if (e.code === 401) unauthorized(); } })();
      loop(); timers.push(() => { stop = true; });
    },
    show(html, o = {}) {
      if (!ctx.alive()) return;
      // a refresh that lands while you're pressing something waits until you let go — replacing the page mid-press
      // swallowed the click (you had to press twice)
      if (pressing && $("#main")?.dataset.seq === String(my)) { heldShow = () => ctx.show(html, o); return; }
      ensureShell();
      const main = $("#main"), first = !main.dataset.seq || main.dataset.seq !== String(my);
      // a page's "Loading…" placeholder: keep the last page up a moment instead, so a quick load doesn't flash an empty page
      clearTimeout(held);
      const wait = 350 - (performance.now() - t0);
      if (first && wait > 0 && !o.now && html.length < 600 && /Loading…|Looking at the server…/.test(html)) { held = setTimeout(() => ctx.show(html, { ...o, now: true }), wait); return; }
      // Store / Menu are tab roots only when they're in your navigation pill and you came to them as a tab
      if (o.root && !(depth === 0 && V.navTabs().some(t => t.route === r))) o = { ...o, root: false };
      const header = o.noHeader ? "" : o.root ? `<div class="ph root"><h1>${esc(o.title || "")}</h1></div>` : `<div class="ph"><h1>${esc(o.title || "")}</h1></div>`;
      const wide = o.narrow === false || r === "home";
      const keepY = scrollY;
      main.innerHTML = `<div class="page${wide ? "" : " narrow"}${V.navTabs().some(t => t.route === r) ? " tabroot" : ""}${window.__filesHop ? "" : first && dir !== 0 && !document.startViewTransition ? ` enter-${dir}` : first ? " enter-0" : ""}"><div class="col">${header}${html}</div></div>`;
      main.dataset.seq = my; ctx.root = main;
      syncSpinners(main);
      // in a Files/Terminal window: back is greyed out with nowhere to go, and forward sits beside it
      const tb = (WM.EMBED ? `<button class="circle press${depth > 0 ? "" : " off"}" data-act="back" aria-label="Back"${depth > 0 ? "" : " disabled"}><span class="bgc frost"></span>${I("back")}</button><button class="circle press fwd${depth < maxDepth ? "" : " off"}" data-act="fwd" aria-label="Forward"${depth < maxDepth ? "" : " disabled"}><span class="bgc frost"></span>${I("back")}</button>`
        : o.root || o.noHeader ? "" : `<button class="circle press" data-act="back" aria-label="Back"><span class="bgc frost"></span>${I("back")}</button>`)
        + `<span class="sp"></span>` + (o.actions || []).map(a => `<button class="circle press" data-act="${esc(a.act)}" aria-label="${esc(a.label)}"><span class="bgc frost"></span>${I(a.icon)}</button>`).join("");
      // only when it changed: rebuilding it on every refresh made the back arrow and its frosted circle flash
      if ($("#topbar").dataset.h !== tb) { $("#topbar").innerHTML = tb; $("#topbar").dataset.h = tb; fade(); }
      drawNav(r);
      if (first) {
        const y = dir === -1 ? (scrolls[keyNow()] || 0) : 0;
        requestAnimationFrame(() => { scrollTo(0, y); fade(); });
        shown();
      } else { if (Math.abs(scrollY - keepY) > 2) scrollTo(0, keepY); fade(); }
    },
    raw(html) {     // full-screen views (dashboard)
      if (!ctx.alive()) return;
      $("#app").innerHTML = html; ctx.root = $("#app"); shown();
    },
  };
  current = { leave: () => { timers.forEach(t => t()); leaveFns.forEach(f => { try { f(); } catch {} }); }, ctx, get handlers() { return handlers; } };
  if (r !== "dashboard" && !$("#shell")) ensureShell();
  const run = () => { Promise.resolve().then(() => view(ctx)).catch(e => { if (e.code === 401) unauthorized(); else if (!e.offline) toast(e.message); }); return firstShow; };
  // folder to folder in Files: the page stays, only the list slides (files.js)
  const hop = r === "files" && lastRendered === "files" && dir !== 0; window.__filesHop = hop ? dir : 0; lastRendered = r;
  const vt = !hop && document.startViewTransition && !document.documentElement.classList.contains("reduce") && dir !== 0 && $("#shell") && r !== "dashboard";
  if (vt) {
    document.documentElement.classList.toggle("vt-fwd", dir === 1); document.documentElement.classList.toggle("vt-back", dir === -1);
    const t = document.startViewTransition(() => Promise.race([run(), sleep(400)])); rateViewTransition(t);
    [t.ready, t.finished, t.updateCallbackDone].forEach(p => p?.catch?.(() => {}));    // a newer navigation may cut it short
  } else run();
}

// one click handler for everything: data-act="name" or "name:arg:arg"
document.addEventListener("click", e => {           // a folded note: open or close it
  const xb = e.target.closest?.(".xnote .xbtn");
  if (xb) { const n = xb.closest(".xnote"), open = n.classList.toggle("open"); xb.setAttribute("aria-expanded", open); xb.setAttribute("aria-label", open ? "Show less" : "Show more"); e.stopPropagation(); return; }
}, true);
document.addEventListener("click", e => {
  const el = e.target.closest("[data-act]"); if (!el || !el.dataset.act || el.closest("[disabled]")) return;
  const [name, ...a] = el.dataset.act.split(":"), h = current?.handlers || {};
  if (h[name]) return h[name](...a, el);
  if (name === "help") return ST.showHelp(a[0]);
  if (name === "search") return go("search");
  if (name === "go") return go(a.join(":"));
  if (name === "tab") return tab(a.join(":"));
  if (name === "back") return back();
  if (name === "fwd") return history.forward();
});
let unauthorizedShown = false;
async function unauthorized() {
  if (unauthorizedShown) return; unauthorizedShown = true;
  toast("This browser isn't authorized anymore — pair it again");
  await kv("device", null); await sleep(1500); pairScreen();
}

// ── pairing a new browser ────────────────────────────────────────────────────────
function guessName() {
  const ua = navigator.userAgent, b = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
  const os = /Windows/.test(ua) ? "Windows" : /Mac OS/.test(ua) ? "Mac" : /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : /Linux/.test(ua) ? "Linux" : "";
  return `${b}${os ? " on " + os : ""}`;
}
async function pairScreen() {
  const remote = !/^(\d+\.){3}\d+$|^\[|localhost/.test(location.hostname);
  $("#app").innerHTML = `<div class="pair"><div style="margin:0 26px 18px">${logo(64)}</div><h1>Nova</h1><p class="lead">Add this browser to your server</p>
    <div class="group glass"><div style="padding:18px 20px"><b style="font-weight:400;font-size:17px">Name this browser</b>
      <input class="field" id="nm" style="margin-top:10px" maxlength="40" value="${esc(guessName())}"></div></div>
    <button class="btn block" id="go">Get a code</button>
    <p class="note" style="font-size:14px">This browser makes its own signing key (it can't be copied out). An admin approves it in the Nova app on their phone — browsers can't add themselves.</p>
    ${remote ? `<p class="note">You're on the remote address, so Cloudflare checked who you are first. Approving still happens in the app.</p>` : ""}
    <div class="links glass"><b>New to Nova?</b><button id="guide">How to set up your server</button></div></div>`;
  $("#guide").onclick = () => { $("#app").innerHTML = ""; ensureShell(); history.replaceState({ k: newKey(), d: 0 }, "", "#/guide"); renderGuideOnly(); };
  $("#go").onclick = async () => {
    $("#go").disabled = true;
    try {
      const keys = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, false, ["sign", "verify"]);
      const name = $("#nm").value.trim() || guessName();
      const r = await fetch("/api/v1/browser/request", { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ public_key: await pemOf(keys.publicKey), name }) });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) { $("#go").disabled = false; return toast(j.error || (r.status === 403 ? "Cloudflare or the server turned this browser away" : `Couldn't start pairing (${r.status})`)); }
      const until = Date.now() + (j.expires_in || 600) * 1000;
      $("#app").innerHTML = `<div class="pair"><h1 style="font-size:34px">Approve this browser</h1>
        <p class="lead">On an admin phone open <b>Nova → Menu → Users &amp; devices → Approve a browser</b> and scan this, or enter the code:</p>
        <div class="pairqr" id="pairqr"></div>
        <div class="code">${esc(j.code)}</div>
        <p class="note" style="text-align:center">…or on the server:</p>${copyCmd("sudo nova approve " + j.code)}
        <div id="totpbox"></div>
        <div class="center"><div class="spinner" style="margin:auto"></div><p class="note" id="left" style="font-size:16px"></p></div></div>`;
      import("./qr.js").then(q => { const el = $("#pairqr"); if (el) el.innerHTML = q.qrSvg("nova-approve:" + j.code, 6); }).catch(() => {});   // the phone scans it
      // no phone and no SSH at hand: the server may take a code from an admin's authenticator app instead
      let paired = null;
      const showTotp = () => {
        if ($("#totpbox").innerHTML) return;
        $("#totpbox").innerHTML = `<p class="note" style="text-align:center">…or type a code from the admin's authenticator app:</p>
          <div class="group glass"><div style="padding:14px 16px;display:flex;gap:8px"><input class="field" id="tc" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="123456" style="flex:1;font-family:ui-monospace,monospace;letter-spacing:4px"><button class="pillbtn" id="tcgo">Approve</button></div></div><p class="note" id="tcerr" style="color:var(--red)"></p>`;
        const go = async () => {
          const v = $("#tc").value.trim(); if (!/^\d{6}$/.test(v)) { $("#tcerr").textContent = "Type the 6 digits the app shows"; return; }
          const r = await fetch(`/api/v1/browser/request/${j.id}/totp`, { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code: v }) });
          const k = await r.json().catch(() => ({}));
          if (r.ok && k.state === "approved") paired = k; else { $("#tcerr").textContent = k.error || `That didn't work (${r.status})`; $("#tc").select(); }
        };
        $("#tcgo").onclick = go; $("#tc").onkeydown = e => { if (e.key === "Enter") go(); };
      };
      const showPasskey = async () => {
        const SEC = await import("./sec.js");
        if ($("#pkbox") || !SEC.passkeysHere()) return;
        $("#totpbox").insertAdjacentHTML("beforebegin", `<div id="pkbox" style="margin:10px 26px"><button class="btn block" id="pkgo" style="margin:0;width:100%">Use a passkey</button><p class="note" id="pkerr" style="color:var(--red)"></p></div>`);
        $("#pkgo").onclick = async () => {
          try {
            const o = await fetch(`/api/v1/browser/request/${j.id}/passkey/start`, { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: "{}" }).then(x => x.json());
            if (o.error) throw new Error(o.error);
            const ans = await SEC.passkeyAnswer(o);
            const r = await fetch(`/api/v1/browser/request/${j.id}/passkey/finish`, { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: JSON.stringify(ans) });
            const k = await r.json().catch(() => ({}));
            if (r.ok && k.state === "approved") paired = k; else throw new Error(k.error || `That didn't work (${r.status})`);
          } catch (e) { $("#pkerr").textContent = e.name === "NotAllowedError" ? "Cancelled" : e.message; }
        };
      };
      const tick = () => { const s = Math.max(0, Math.round((until - Date.now()) / 1000)), el = $("#left");
        if (el) { el.innerHTML = `Waiting… <b>${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}</b> left`; el.style.color = s < 60 ? "var(--amber)" : ""; } };
      tick(); const timer = setInterval(tick, 1000);
      for (let i = 0; i < 300 && Date.now() < until; i++) {
        for (let w = 0; w < 10 && !paired; w++) await sleep(200);
        const s = paired || await fetch(`/api/v1/browser/request/${j.id}`, { credentials: "same-origin" }).then(x => x.json()).catch(() => ({}));
        if (s.totp) showTotp();
        if (s.passkey) showPasskey();
        if (s.state === "approved") { clearInterval(timer); await kv("device", { id: s.device_id, keys }); toast("This browser is paired"); unauthorizedShown = false; return start(); }
        if (s.state === "expired") break;
      }
      clearInterval(timer); toast("The code expired — get a new one"); pairScreen();
    } catch (e) { toast(e.message); $("#go").disabled = false; }
  };
}
function renderGuideOnly() {   // the setup guide is readable before pairing
  const ctx = { args: [], alive: () => true, handlers() {}, show(html, o) { $("#main").innerHTML = `<div class="page narrow"><div class="col"><div class="ph"><h1>${esc(o.title)}</h1></div>${html}</div></div>`;
    $("#topbar").innerHTML = `<button class="circle press" id="gb" aria-label="Back"><span class="bgc frost"></span>${I("back")}</button>`; $("#gb").onclick = () => { history.replaceState(null, "", "#/"); location.reload(); }; } };
  V.guide(ctx);
}

// ── start ────────────────────────────────────────────────────────────────────────
async function start() {
  await loadLang(prefs.lang); watch();                      // your language (Appearance → Language), or this browser's
  if (!crypto?.subtle) { $("#app").innerHTML = `<div class="pair"><h1>Nova</h1><p class="lead">This page needs a secure connection (https). Open it at <b>https://</b>${esc(location.host)}.</p></div>`; return; }
  const d = await kv("device").catch(() => null);
  if (!d?.id) return pairScreen();
  S.device = d.id; S.keys = d.keys;
  try { S.me = await get("/api/v1/whoami"); if (S.me.form !== webForm()) post("/api/v1/device/form", { form: webForm() }).catch(() => {}); }
  catch (e) {
    if (e.code === 401) { await kv("device", null); return pairScreen(); }
    if (e.code === 403) { $("#app").innerHTML = `<div class="pair"><h1>Nova</h1><p class="lead">${esc(e.message)}</p></div>`; return; }
  }
  await refresh().catch(() => {});
  const was = savedTarget();               // still controlling another server in this tab (after a reload)
  if (was?.id) { try { await switchServer(was); if (S.targetLost) throw new Error(); } catch { await switchServer(null).catch(() => {}); } }
  loadNodes().catch(() => {});
  ensureShell(); drawCtl();
  // "Open on start" (Appearance → Start page): where a fresh visit lands
  if (!location.hash || location.hash === "#" || location.hash === "#/") { const r = prefs.startRoute; if (r && r !== "home") history.replaceState({ k: newKey(), d: 0 }, "", "#/" + r); }
  render(0);
  // keep the overview fresh in the background (screens refresh what they show themselves)
  setInterval(() => { if (!document.hidden) refresh().catch(e => { if (e.code === 401) unauthorized(); }); }, 30000);
  if (WM.EMBED) { document.documentElement.classList.add("embed"); return; }   // the window's Nova page already watches alerts
  watchEvents();
  addEventListener("resize", () => { const [r] = parse(); drawNav(r); });
}
start();

// ── New alerts while Nova is open: a banner on the page, and a browser notification when the tab is in the background ──
let alertSince = null, alertQueue = [], alertTimer;
// A long poll, like the app's instant alerts: the server answers the moment a newer event is written (or after 25 s with
// nothing), and we ask again straight away — so a new alert shows within a second or two, not on the next 20-second check.
async function watchEvents() {
  for (let fails = 0; ;) {
    try {
      if (alertSince === null) {
        const ev = (await get("/api/v1/events?since=0")).events || [];
        alertSince = Math.max(0, ...ev.map(e => e.t)) || Date.now() / 1000;            // what's already there isn't news
      }
      const r = await get(`/api/v1/events/wait?since=${alertSince}&timeout=25`);
      const fresh = (r.events || []).filter(e => e.t > alertSince).sort((a, b) => a.t - b.t);
      if (fresh.length) {
        alertSince = Math.max(alertSince, ...fresh.map(e => e.t)); announce(fresh);
        get("/api/v1/events?since=0").then(() => dispatchEvent(new Event("nova:events"))).catch(() => {});   // the Inbox shows it at once
      }
      fails = 0;
    } catch (e) {
      if (e?.code === 401) return;
      await new Promise(res => setTimeout(res, Math.min(60000, 3000 * 2 ** fails++)));    // offline: back off
    }
  }
}
const important = e => ["warning", "critical"].includes(e.level);
function announce(list) {
  if (prefs.browserNotify && "Notification" in window && Notification.permission === "granted" && (document.hidden || !document.hasFocus())) {
    const min = { info: 0, warning: 1, critical: 2 }[V.bLevel()] ?? 1, lv = { info: 0, warning: 1, critical: 2 };
    list.filter(e => (lv[e.level] ?? 0) >= min).slice(-3).forEach(e => {
      try { const n = new Notification(cleanTitle(e.title), { body: e.detail || "", tag: "nova-" + e.t, icon: "/web/icon.svg" }); n.onclick = () => { focus(); location.hash = "#/inbox"; n.close(); }; } catch {}
    });
  }
  // the most serious new one makes its sound (and a buzz on a phone)
  const top = list.some(e => e.level === "critical") ? "critical" : list.some(e => e.level === "warning") ? "warning" : list.length ? "info" : "";
  if (top) SND.feel(top);
  if (parse()[0] === "inbox") return;                     // the Inbox shows them itself
  alertQueue.push(...list); showAlert();
}
function showAlert() {
  const box = $("#alertbar"); if (!box || !alertQueue.length) return;
  const e = alertQueue[alertQueue.length - 1], more = alertQueue.length - 1;
  box.innerHTML = `<button class="alertcard glass" data-act="go:inbox"><span class="dot" style="background:${levelColor(e.level)}"></span>
    <span class="t"><b>${esc(cleanTitle(e.title))}</b><small>${esc(e.detail || "")}${more ? ` · and ${more} more` : ""}</small></span>
    <span class="x" data-x="1" aria-label="Dismiss">×</span></button>`;
  box.classList.add("on");
  $("[data-x]", box).onclick = ev => { ev.stopPropagation(); hideAlert(); };
  box.querySelector(".alertcard").addEventListener("click", hideAlert);
  clearTimeout(alertTimer); alertTimer = setTimeout(hideAlert, important(e) ? 12000 : 6000);
}
function hideAlert() { alertQueue = []; $("#alertbar")?.classList.remove("on"); }

// Press / (or Ctrl/⌘+K) anywhere to search Nova
addEventListener("keydown", e => {
  if (/INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName) || $("#sheet:not([hidden])")) return;
  if (e.key === "/" || ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k")) { e.preventDefault(); if (parse()[0] !== "search") go("search"); }
});

// sideways rows (apps in Menu, chips…) fade at the edge that has more to scroll to, instead of cutting off hard
{
  const SEL = ".mapps, .srvstrip, .fdrives, .hfade", edges = sc => { const max = sc.scrollWidth - sc.clientWidth; sc.classList.toggle("fs", sc.scrollLeft > 2); sc.classList.toggle("fe", sc.scrollLeft < max - 2); };
  document.addEventListener("scroll", e => { if (e.target.matches?.(SEL)) edges(e.target); }, true);
  let q = 0; const all = () => { q = 0; document.querySelectorAll(SEL).forEach(edges); };
  new MutationObserver(() => { if (!q) q = requestAnimationFrame(all); }).observe(document.body, { childList: true, subtree: true });
  addEventListener("resize", all);
}


// Developer options → Show frame rate: frames per second over half a second, and the slowest frame
function fpsMeter(on) {
  if (WM.EMBED) on = false;                         // a Files/Terminal window: the page around it has the meter
  if (!on) { fpsEl?.remove(); fpsEl = null; return; }
  if (fpsEl) return;
  fpsEl = document.createElement("div"); fpsEl.className = "fpsmeter"; document.body.append(fpsEl);
  let n = 0, t0 = performance.now(), last = t0, worst = 0;
  const tick = now => { if (!fpsEl) return; n++; worst = Math.max(worst, now - last); last = now;
    if (now - t0 >= 500) { const fps = Math.round(n * 1000 / (now - t0)); window.__novaHz = Math.max(window.__novaHz || 0, fps);
      const hz = window.__novaHz || 60, c = fps >= hz * .9 ? "#30d158" : fps >= hz * .6 ? "#ff9f0a" : "#ff453a";
      fpsEl.innerHTML = `<b style="color:${c}">${fps} fps</b> · worst ${Math.round(worst)} ms`; n = 0; worst = 0; t0 = now; }
    requestAnimationFrame(tick); };
  requestAnimationFrame(tick);
}
// Offline: a reload would leave an empty page until the server is back, so it asks first (and you can still reload)
addEventListener("keydown", async e => {
  if (!S.error || !(e.key === "F5" || ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "r"))) return;
  e.preventDefault();
  const { confirm } = await import("./ui.js");
  if (await confirm("The server isn't answering", "Reloading now would leave this page empty until it's back. Nova keeps trying by itself and fills in as soon as it answers.", "Reload anyway", "var(--blue)")) { S.error = null; location.reload(); }
});
addEventListener("beforeunload", e => { if (S.error && !document.hidden) { e.preventDefault(); e.returnValue = ""; } });   // the browser's reload button: its own "Leave?" question

// Install Nova as an app (Menu → Install Nova): the browser's own install, when it offers one; iPhone and iPad get the
// Share → Add to Home Screen steps instead
let installEvt = null;
try { navigator.serviceWorker?.register("/web/sw.js", { scope: "/" }).catch(() => {}); } catch {}
addEventListener("beforeinstallprompt", e => { e.preventDefault(); installEvt = e; document.documentElement.classList.add("can-install"); });
addEventListener("appinstalled", () => { installEvt = null; document.documentElement.classList.remove("can-install"); toast("Nova is installed — open it like any app"); });
export const installable = () => !!installEvt || (/iPhone|iPad|iPod/.test(navigator.userAgent) && !navigator.standalone);
export async function installNova() {
  if (installEvt) { installEvt.prompt(); try { await installEvt.userChoice; } catch {} installEvt = null; document.documentElement.classList.remove("can-install"); return; }
  const { dialog } = await import("./ui.js");
  dialog("Add Nova to your home screen", "In Safari, tap the Share button (the square with an arrow), then “Add to Home Screen”. Nova then opens full screen, like an app. To approve things without an Android phone, set up a passkey or authenticator codes in Security & privacy.", [{ label: "OK", value: true }]);
}

// Sliders: the bar itself is thin, so you'd have to land right on it. Pressing anywhere on a slider's row (or within
// about a finger's width of the bar) grabs it there and follows the pointer, like pressing the bar.
addEventListener("pointerdown", e => {
  if (e.button > 0 || e.pointerType === "touch" || e.target.matches?.('input[type="range"]')) return;     // (touch: the row is for scrolling)
  const box = e.target.closest?.(".range-row, .sliderow"); if (!box) return;
  const r = box.querySelector('input[type="range"]'); if (!r || r.disabled) return;
  if (e.target.closest("button, a, input, select, textarea, [data-act]")) return;
  const rect = r.getBoundingClientRect();
  if (e.clientX < rect.left - 12 || e.clientX > rect.right + 12 || Math.abs(e.clientY - (rect.top + rect.height / 2)) > 26) return;
  const set = x => {
    const min = +r.min || 0, max = +(r.max || 100), step = +r.step || 1, f = Math.max(0, Math.min(1, (x - rect.left) / rect.width));
    const v = Math.round((min + f * (max - min)) / step) * step;
    if (+r.value !== v) { r.value = v; r.dispatchEvent(new Event("input", { bubbles: true })); }
  };
  e.preventDefault(); set(e.clientX);
  const move = ev => set(ev.clientX), up = () => { removeEventListener("pointermove", move); removeEventListener("pointerup", up); removeEventListener("pointercancel", up); r.dispatchEvent(new Event("change", { bubbles: true })); };
  addEventListener("pointermove", move); addEventListener("pointerup", up); addEventListener("pointercancel", up);
}, true);
