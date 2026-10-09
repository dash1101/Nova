// Nova web — entry: router (with back stack, scroll memory and slide transitions), the app shell
// (side rail on wide screens, frosted bottom bar on phones), pairing, start-up.
import { S, $, $$, esc, sleep, kv, pemOf, get, post, prefs, has, refresh, onApproval, ApiError, webForm } from "./core.js";
import { I, logo, toast, waitApproval, closeSheet } from "./ui.js";
import * as V from "./views.js";
import * as ST from "./storage.js";

onApproval(id => waitApproval(id, get));

// ── theme ────────────────────────────────────────────────────────────────────────
function applyTheme() {
  const t = prefs.theme, root = document.documentElement;
  if (t === "system") delete root.dataset.theme; else root.dataset.theme = t;
  root.classList.toggle("reduce", prefs.reduceMotion || matchMedia("(prefers-reduced-motion: reduce)").matches);
  const dark = t === "dark" || (t === "system" && !matchMedia("(prefers-color-scheme: light)").matches);
  $('meta[name="theme-color"]')?.setAttribute("content", dark ? "#000000" : "#f4f3f8");
}
applyTheme();
matchMedia("(prefers-color-scheme: light)").addEventListener?.("change", applyTheme);

// ── routes ───────────────────────────────────────────────────────────────────────
const ROUTES = {
  home: V.home, menu: V.menu, status: V.status, containers: V.containers, store: V.store, inbox: V.inbox, notify: V.notify,
  quick: V.quick, "edit-quick": V.editQuick, lighting: V.lighting, schedules: V.schedules, schedule: V.schedule, hardware: V.hardware,
  devices: V.devices, settings: V.settings, server: V.serverSettings, appearance: V.appearance, "edit-home": V.editHome,
  "edit-shortcuts": V.editShortcuts, "edit-tabs": V.editTabs, about: V.about, guide: V.guide, terminal: V.terminal,
  dashboard: V.dashboard, "edit-dash": V.editDash, archive: V.archive,
  setup: ST.setup, pool: ST.pool, task: ST.task, backups: ST.backups, backup: ST.backup, "backup-edit": ST.backupEdit, restore: ST.restore, diag: ST.diag,
};
const RAIL = () => [["home", "dns", "Home"], ["status", "status", "Status"], ["containers", "box", "Containers"],
  ...(has("store") ? [["store", "store", "Store"]] : []), ["dashboard", "dash", "Dashboard"], ["menu", "list", "Menu"]];
const parse = () => (location.hash.replace(/^#\/?/, "") || "home").split("/").filter(Boolean).map(decodeURIComponent);

// ── navigation: a real back stack on top of browser history ───────────────────────
let depth = history.state?.d ?? 0, seq = 0, current = null, rootRoute = "home";
const keyNow = () => history.state?.k ?? "0";
const scrolls = (() => { try { return JSON.parse(sessionStorage.getItem("nova.scrolls") || "{}"); } catch { return {}; } })();
function saveScroll() { scrolls[keyNow()] = scrollY; try { sessionStorage.setItem("nova.scrolls", JSON.stringify(scrolls)); } catch {} }
const newKey = () => Math.random().toString(36).slice(2, 10);
if (!history.state) history.replaceState({ k: newKey(), d: 0 }, "");

function go(path) { saveScroll(); history.pushState({ k: newKey(), d: depth + 1 }, "", "#/" + path); depth++; render(1); }
function tab(path) {
  saveScroll(); const r = path.split("/")[0]; rootRoute = r;
  const here = parse()[0];
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
  if (parse()[0] !== "home") { saveScroll(); history.replaceState({ k: newKey(), d: 0 }, "", "#/home"); rootRoute = "home"; render(-1); }
}
addEventListener("popstate", e => {
  const d = e.state?.d ?? 0, dir = pendingHome ? 0 : d < depth ? -1 : 1; depth = d;
  if (pendingHome) { pendingHome = false; if (parse()[0] !== "home") history.replaceState({ k: newKey(), d: 0 }, "", "#/home"); }
  render(dir);
});
addEventListener("keydown", e => { if (e.key === "Escape" && !$("#sheet").hidden) closeSheet(); });

// ── the shell (rail + page + top bar + bottom bar), built once ────────────────────
function ensureShell() {
  if ($("#shell")) return;
  $("#app").innerHTML = `<div class="shell" id="shell"><nav class="rail" id="rail"></nav><main class="main" id="main"></main></div>
    <div class="scrim"></div><div class="topbar" id="topbar"></div><nav class="nav frost" id="nav" hidden></nav>`;
  addEventListener("scroll", fade, { passive: true });
}
function fade() { const f = Math.max(0, Math.min(1, scrollY / 36)); $$("#topbar .bgc").forEach(b => b.style.setProperty("--fade", f)); }
function drawRail(route) {
  if (!$("#rail")) return;
  const sel = RAIL().findIndex(([k]) => k === route) >= 0 ? route : rootRoute;
  $("#rail").innerHTML = logo() + RAIL().map(([k, ic, l]) => `<button class="${k === sel ? "on" : ""}" data-act="tab:${k}"><span class="ic">${I(ic)}</span>${l}</button>`).join("");
}
function drawNav(route) {
  const t = V.navTabs(), i = t.findIndex(x => x.route === route), nav = $("#nav");
  if (!nav) return;
  nav.hidden = i < 0 || innerWidth >= 900;
  if (nav.hidden) return;
  const was = nav.dataset.sel, ids = t.map(x => x.id).join();
  if (nav.dataset.ids !== ids) {
    nav.innerHTML = `<div class="items"><span class="bead"></span>${t.map(x => `<button class="press" aria-label="${esc(x.label)}" data-act="tab:${x.route}">${I(x.icon)}</button>`).join("")}</div>`;
    nav.dataset.ids = ids;
    let h; nav.onpointerdown = () => { h = setTimeout(() => { navigator.vibrate?.(10); go("edit-tabs"); }, 550); };
    nav.onpointerup = nav.onpointerleave = () => clearTimeout(h);
    nav.oncontextmenu = e => { e.preventDefault(); go("edit-tabs"); };
  }
  const bead = $(".bead", nav);
  if (was == null) { bead.style.transition = "none"; requestAnimationFrame(() => bead.style.transition = ""); }
  bead.style.transform = `translateX(${i * 80}px)`; nav.dataset.sel = i;
}

// ── render a route ───────────────────────────────────────────────────────────────
function render(dir = 0) {
  const my = ++seq, [r, ...args] = parse(), view = ROUTES[r] || V.home;
  current?.leave(); closeSheet();
  if (V.navTabs().some(t => t.route === r) && depth === 0) rootRoute = r;
  if (RAIL().some(([k]) => k === r) && depth === 0) rootRoute = r;
  let shown, firstShow = new Promise(res => shown = res);
  const leaveFns = [], timers = [];
  let handlers = {};
  const ctx = {
    args, dir,
    alive: () => my === seq,
    go, tab, back,
    run: (name, ...a) => handlers[name]?.(...a),
    handlers: h => { handlers = h; },
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
      ensureShell();
      const main = $("#main"), first = !main.dataset.seq || main.dataset.seq !== String(my);
      const header = o.noHeader ? "" : o.root ? `<div class="ph root"><h1>${esc(o.title || "")}</h1></div>` : `<div class="ph"><h1>${esc(o.title || "")}</h1></div>`;
      const wide = o.narrow === false || r === "home";
      const keepY = scrollY;
      main.innerHTML = `<div class="page${wide ? "" : " narrow"}${V.navTabs().some(t => t.route === r) ? " tabroot" : ""}${first && dir !== 0 && !document.startViewTransition ? ` enter-${dir}` : first ? " enter-0" : ""}"><div class="col">${header}${html}</div></div>`;
      main.dataset.seq = my; ctx.root = main;
      $("#topbar").innerHTML = (o.root || o.noHeader ? "" : `<button class="circle press" data-act="back" aria-label="Back"><span class="bgc frost"></span>${I("back")}</button>`)
        + `<span class="sp"></span>` + (o.actions || []).map(a => `<button class="circle press" data-act="${esc(a.act)}" aria-label="${esc(a.label)}"><span class="bgc frost"></span>${I(a.icon)}</button>`).join("");
      drawRail(r); drawNav(r);
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
  const vt = document.startViewTransition && !document.documentElement.classList.contains("reduce") && dir !== 0 && $("#shell") && r !== "dashboard";
  if (vt) {
    document.documentElement.classList.toggle("vt-fwd", dir === 1); document.documentElement.classList.toggle("vt-back", dir === -1);
    const t = document.startViewTransition(() => Promise.race([run(), sleep(400)]));
    [t.ready, t.finished, t.updateCallbackDone].forEach(p => p?.catch?.(() => {}));    // a newer navigation may cut it short
  } else run();
}

// one click handler for everything: data-act="name" or "name:arg:arg"
document.addEventListener("click", e => {
  const el = e.target.closest("[data-act]"); if (!el || !el.dataset.act || el.closest("[disabled]")) return;
  const [name, ...a] = el.dataset.act.split(":"), h = current?.handlers || {};
  if (h[name]) return h[name](...a, el);
  if (name === "help") return ST.showHelp(a[0]);
  if (name === "go") return go(a.join(":"));
  if (name === "tab") return tab(a.join(":"));
  if (name === "back") return back();
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
      $("#app").innerHTML = `<div class="pair"><h1 style="font-size:34px">Approve on your phone</h1>
        <p class="lead">On an admin phone open <b>Nova → Menu → Users &amp; devices → Approve a browser</b> and enter:</p>
        <div class="code">${esc(j.code)}</div>
        <div class="center"><div class="spinner" style="margin:auto"></div><p class="note">Waiting… the code expires in 10 minutes.</p></div></div>`;
      for (let i = 0; i < 300; i++) {
        await sleep(2000);
        const s = await fetch(`/api/v1/browser/request/${j.id}`, { credentials: "same-origin" }).then(x => x.json()).catch(() => ({}));
        if (s.state === "approved") { await kv("device", { id: s.device_id, keys }); toast("This browser is paired"); unauthorizedShown = false; return start(); }
        if (s.state === "expired") break;
      }
      toast("The code expired — try again"); pairScreen();
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
  ensureShell(); render(0);
  // keep the overview fresh in the background (screens refresh what they show themselves)
  setInterval(() => { if (!document.hidden) refresh().catch(e => { if (e.code === 401) unauthorized(); }); }, 30000);
  addEventListener("resize", () => { const [r] = parse(); drawNav(r); drawRail(r); });
}
start();
