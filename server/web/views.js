// Nova web — every screen of the app. Each view renders with ctx.show(html, options, handlers);
// buttons carry data-act="name" or data-act="name:arg" and land in `handlers` (or the router's).
import * as SND from "./sounds.js";
import * as AMB from "./ambience.js";
import * as LV from "./living.js";
import { gpuInfo, devLog } from "./core.js";
import { webIcons, iconSvg, svgUrl, applyWebIcon , SHAPES } from "./webicon.js";
let WEB_ICONS = null;                                   // the App icon choices (loaded with the artwork the first time Appearance opens)
import { S, $, $$, esc, get, post, del, api, loadNodes, serverHost, sleep, prefs, waitTask, runningTask, levelColor, pct, rate, bytes, isAdmin, has, cleanTitle, cap,
         serverName, uptime, hm, refresh, changeFan, waitJob, kv, archiveMerge, archiveAll, archiveClear, archiveUnmark } from "./core.js";
import { I, row, group, sec, note, sw, radio, switchRow, links, expand, slider, segmented, bar, usageColor,
         toast, dialog, confirm, choose, ask, sheet, closeSheet, wireCommon, reorderable, onHold, spark, swipeable, colorPicker, copyCmd } from "./ui.js";
import { animate, heroHit } from "./hero.js";
import * as ST from "./storage.js";
import { LANGS, pickLang } from "./i18n.js";
import { canRefract } from "./glass.js";
import * as SV from "./servers.js";
import { favoriteTiles, favoritesLink } from "./start.js";

// ── catalogs (same ids as the app, so the two read alike) ───────────────────────
export const SHORTCUTS = [
  ["inbox", "bell", "Inbox", "inbox"], ["apps", "apps", "Apps", "apps"], ["servers", "dns", "Servers", "servers"], ["files", "folder", "Files", "files"], ["start", "open", "Start page", "start", null, "Start"], ["search", "search", "Search", "search"], ["quick", "widgets", "Quick panel", "quick", null, "Quick"], ["containers", "box", "Containers", "containers"],
  ["storage", "disk", "Storage", "hardware"], ["status", "status", "Status", "status"], ["lighting", "bulb", "Lighting", "lighting", "lighting"],
  ["terminal", "term", "Terminal", "terminal", "ssh"], ["store", "store", "Store", "store", "store"], ["dashboard", "dash", "Dashboard", "dashboard"],
  ["schedules", "clock", "Schedules", "schedules", "lighting"], ["devices", "group", "Devices", "devices"], ["settings", "gear", "Settings", "settings"],
].map(([id, icon, label, route, feature, short]) => ({ id, icon, label, route, feature, short: short || label }));
const sc = id => SHORTCUTS.find(s => s.id === id);
export const NAV_TABS = [{ id: "home", icon: "home", label: "Home", route: "home" }, { id: "store", icon: "store", label: "Store", route: "store", feature: "store" },
  { id: "menu", icon: "list", label: "Menu", route: "menu" }, ...["start", "apps", "files", "servers", "search", "status", "containers", "storage", "inbox", "quick", "lighting"].map(sc)];
export const navTabs = () => {
  const t = prefs.navTabs.map(id => NAV_TABS.find(x => x.id === id)).filter(x => x && (!x.feature || has(x.feature)));
  return t.some(x => x.id === "home") ? t : [NAV_TABS[0], ...t];
};
const homeChips = () => prefs.homeChips.map(sc).filter(s => s && (!s.feature || has(s.feature)));
const QUICK = [
  ["backup", "Back up now", "backup", "Start a backup now"], ["freeram", "Free RAM", "mem", "Drop the disk cache"], ["freeup", "Free up", "disk", "Space and memory, in one go"], ["fan", "Fan light", "bulb", "On / off"],
  ["dim", "Dim fan", "dim", "Brightness 5%"], ["bright", "Bright fan", "bright", "Brightness 60%"], ["status_light", "Status light", "traffic", "Fan shows server health"],
  ["discord", "Discord pings", "bell", "Pause / resume alerts"], ["status", "Server status", "status", "Live graphs and health"], ["dashboard", "Dashboard", "dash", "Always-on screen"],
  ["containers", "Containers", "box", "Open the list"], ["lighting", "Lighting", "palette", "Colors and effects"], ["inbox", "Inbox", "bell", "Alerts and logins"],
  ["storage", "Storage", "disk", "Drives and temperatures"], ["store", "App store", "store", "Install apps"],
].map(([id, label, icon, hint]) => ({ id, label, icon, hint }));
const quickDef = id => id.startsWith("restart:") ? { id, label: "Restart " + id.slice(8), icon: "restart", hint: "Approved on your phone" } : QUICK.find(q => q.id === id);
const HOME_SECTIONS = [["hero", "Server picture", "dns", "homeHero"], ["servers", "Your servers", "dns", "homeServers"], ["shortcuts", "Shortcuts", "apps", "homeShortcuts"], ["stats", "At a glance", "speed", "homeStats"], ["recs", "Recommended", "okc", "homeRecs"]];
/** Home's sections in your order (a section added in a newer Nova goes in after the server picture). */
function homeOrder() {
  const o = prefs.homeOrder.filter(id => HOME_SECTIONS.some(s => s[0] === id));
  for (const [id] of HOME_SECTIONS) if (!o.includes(id)) o.splice(id === "servers" ? Math.min(1, o.length) : o.length, 0, id);
  return o;
}
const DASH_TILES = [["clock", "Clock", "clock", 1], ["health", "Health", "okc", 1], ["cpu", "CPU graph", "cpu", 1], ["mem", "Memory graph", "mem", 1], ["temp", "Temperature graph", "temp", 1],
  ["net", "Network graph", "net", 1], ["storage", "Storage", "disk", 2], ["containers", "Containers", "box", 1], ["backup", "Backups", "backup", 1], ["fan", "Fan light", "bulb", 2],
  ["alerts", "Recent alerts", "bell", 2], ["uptime", "Uptime", "clock", 1], ["gpu", "Graphics graph", "speed", 1], ["swap", "Swap graph", "mem", 1], ["io", "Disk I/O graph", "disk", 1],
  ["top", "Top containers", "box", 1], ["server", "Server info", "dns", 1]];
// tile sizes: 1 small, 2 wide, 3 tall, 4 big (two by two)
const SIZE_CLS = { 2: "span2", 3: "tall", 4: "span2 tall" }, SIZE_NAMES = { 1: "Small", 2: "Wide", 3: "Tall", 4: "Big" };
let arranging = false, dashUrl = "";
/** How dark the dashboard is right now: it fades in over the half hour before "From" and out over the half hour after "Until". */
function nightLevel() {
  if (!prefs.dashDim) return 0;
  const d = new Date(), now = d.getHours() * 60 + d.getMinutes() + d.getSeconds() / 60, F = (prefs.dashFade ?? 30) || .01;
  const from = prefs.dashFrom * 60, to = prefs.dashTo * 60, rel = x => ((now - x) % 1440 + 1440) % 1440;
  const inNight = prefs.dashFrom > prefs.dashTo ? now >= from || now < to : now >= from && now < to;
  let k = inNight ? 1 : 0;
  if (rel(from - F) < F) k = rel(from - F) / F;                      // getting darker toward "From"
  else if (rel(to) < F) k = 1 - rel(to) / F;                         // lightening after "Until"
  return k * (prefs.dashDimLevel ?? 40) / 100;
}
const dashSpan = id => prefs.dashSize?.[id] ?? (DASH_TILES.find(t => t[0] === id)?.[3] || 1);
const dashTime = (d, sec) => d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", ...(sec ? { second: "2-digit" } : {}), ...(prefs.dashClock24 == null ? {} : { hour12: !prefs.dashClock24 }) });
const SWATCHES = ["#ffffff", "#ff3b30", "#ff9500", "#ffcc00", "#34c759", "#00c7be", "#005aff", "#3e91ff", "#5e5ce6", "#bf5af2", "#ff2d55", "#ff6b9a"];
const ACCENTS = ["", "#3e91ff", "#7c5cff", "#bf5af2", "#ff2d55", "#ff9500", "#34c759", "#00c7be"];
const viewOnly = () => { toast("This browser has view-only access"); return false; };

// ── shared bits ─────────────────────────────────────────────────────────────────
function banner(text, color, act) { return `<div class="banner glass press" data-act="${act}"><span class="dot" style="background:${color}"></span><span class="tx">${esc(text)}</span>${I("back", "flip")}</div>`; }
const agoText = () => { if (!S.lastContact) return null; const m = Math.floor((Date.now() - S.lastContact) / 60000); return m < 1 ? "just now" : m < 60 ? `${m} min ago` : `${Math.floor(m / 60)} h ${m % 60} min ago`; };
export function disconnectedBanner() { const a = agoText(); return banner(a ? `Disconnected · last contact ${a}` : "Disconnected — tap for details", "var(--red)", "disconnected"); }
export async function showDisconnected() {
  const a = agoText();
  const r = await dialog("Can't reach your server", null, [{ label: "Close", value: false }, { label: "Try again", color: "var(--blue)", value: true }],
    `<div class="pad"><p style="margin:0 0 10px">${esc(S.lastAttempt || S.error || "No answer yet")}</p><p class="muted" style="margin:0;font-size:13px">Showing the last data Nova had${a ? ` (from ${a})` : ""}. Nova keeps trying and reconnects by itself when the server is back.</p></div>`);
  if (r) { await refresh(); return true; }
}
function backupLine(b) {
  const p = b?.progress, NAMES = new Proxy({}, { get: (_, k) => typeof k === "string" && k ? k.replace(/_/g, " ").replace(/^./, c => c.toUpperCase()) : k });
  if (!p) return "Backing up…";
  const set = NAMES[p.set] || p.set;
  if (p.phase === "copying") return `Backing up ${set} · ${p.pct}%` + (p.eta && p.eta !== "0:00:00" ? ` · ${p.eta} left` : "");
  if (p.phase === "checking") return `Backing up ${set} · scanning files…`;
  return p.done?.length ? `Backing up · ${p.done.map(d => NAMES[d] || d).join(", ")} done` : "Full backup in progress";
}
function topBanner() {
  const st = S.overview?.status, m = st?.metrics || {}, lvl = st?.level || "ok";
  if (S.error) return disconnectedBanner();
  if (lvl !== "ok") return banner(st?.headline || "Needs attention", levelColor(lvl), "go:inbox");
  if ((m.data_backup || "").includes("running")) return banner(S.cache["/api/v1/backup"] ? backupLine(S.cache["/api/v1/backup"]) : "Backing up…", "var(--blue)", "go:quick");
  return "";
}
/** Mounted storage: the monitor's list on any machine, or the older fixed keys. */
function storageList(m) {
  if (Array.isArray(m?.storage) && m.storage.length) return m.storage.map(x => ({ name: x.name, pct: x.pct, free: bytes(x.free) + " free" }));
  return [["System drive", "root_used"]].filter(x => m?.[x[1]])
    .map(([n, k]) => ({ name: n, pct: pct(m[k]) ?? 0, free: (/\(([^)]*)\)/.exec(m[k]) || [])[1] || m[k] }));
}
const activeAlerts = () => (S.overview?.status?.active || []).filter(a => a.level !== "ok");
/** "Needs attention": swipe right to ignore (drives: removed on purpose); tap for Mount now etc. */
function attentionHtml() {
  const l = activeAlerts(); if (!l.length) return "";
  return sec("Needs attention") + group(l.map(a => { const drive = (a.key || "").startsWith("drive:");
    return `<div class="swipe" data-key="${esc(a.key || "")}" data-right="${drive ? "Removed on purpose" : "Ignore"}" data-left="${drive ? "Removed on purpose" : "Ignore"}"><div class="swbg"></div><div class="swfg">${row(cleanTitle(a.title), { sub: [a.detail, a.since && "since " + a.since].filter(Boolean).join(" · "), icon: "warn", tint: levelColor(a.level), click: "alert:" + (a.key || ""),
      end: a.key ? `<button class="xbtn" data-act="ignore:${esc(a.key)}" aria-label="Ignore">${I("remc")}</button>` : "" })}</div></div>`; }).join(""))
    + note("Swipe (or tap ×) to ignore an alert you've dealt with — it comes back if the problem returns after clearing.");
}
/** Inbox → "Ignored · N": issues still going on that you said to ignore until they clear. Folded away by default. */
let ignoredOpen = false;
function ignoredHtml() {
  const l = S.overview?.status?.ignored || []; if (!l.length) return "";
  return `<div class="expand ign${ignoredOpen ? " open" : ""}" style="margin-top:14px">${group(`<div class="row click" data-act="ignfold"><span class="ri" style="color:var(--sub);background:color-mix(in srgb, var(--sub) 16%, transparent)">${I("eye")}</span>
      <div class="t"><b>Ignored · ${l.length}</b><small>Still going on — quiet until ${l.length === 1 ? "it clears" : "they clear"}</small></div><span class="chev muted">${I("down")}</span></div>
    <div class="xb">${l.map(a => row(cleanTitle(a.title), { sub: [a.detail, a.since && "since " + a.since, a.ignored && "ignored " + a.ignored].filter(Boolean).join(" · "), icon: "warn", tint: levelColor(a.level),
      end: `<button class="pillbtn press" data-act="unignore:${esc(a.key)}">Stop ignoring</button>` })).join("")}</div>`)}</div>`;
}
// The power menu: the server itself, or just Nova (the API — everything else keeps running).
const POWER = [
  ["reboot", "Restart server", "Everything goes offline for about 2 minutes", "restart", "var(--amber)"],
  ["poweroff", "Shut down server", "You'll need to press the power button to turn it back on", "power", "var(--red)"],
  ["nova-restart", "Restart Nova", "Only Nova restarts, in a few seconds — containers and everything else keep running", "restart", "var(--blue)"],
  ["nova-stop", "Shut down Nova", "The server keeps running, but Nova can't be reached until it's started again", "stop", "var(--red)"],
];
async function powerDo(v) {
  const [title, body, btn, done] = {
    reboot: ["Restart the server?", "Everything running on it will be unavailable until it's back. Your phone confirms it.", "Restart", s => `Done — the server will restart in ${s} seconds`],
    poweroff: ["Shut down the server?", "Everything running on it will be unavailable until someone presses its power button. Your phone confirms it.", "Shut down", s => `Done — the server will shut down in ${s} seconds`],
    "nova-restart": ["Restart Nova?", "The apps and this page lose the server for a few seconds, then reconnect. Nothing else is touched. Your phone confirms it.", "Restart", () => "Nova is restarting — back in a few seconds"],
    "nova-stop": ["Shut down Nova?", "Nova stops until the server restarts or someone runs “sudo systemctl start nova-api” on it — the apps and this page can't start it again. Everything else keeps running. Your phone confirms it.", "Shut down", () => "Nova is shutting down"],
  }[v] || [];
  if (!title || !(await confirm(title, body, btn))) return;
  try { const r = await post(`/api/v1/power/${v}`); toast(done(r.in_seconds || 8)); } catch (e) { toast(e.message); }
}

const speedText = v => v == 100 ? "Normal (1×)" : `${+(v / 100).toFixed(2)}× ${v > 100 ? "faster" : "speed"}`;
let smartOpen = false;
function smartHtml(d) {
  const probs = [d.smart_passed === false && "the drive reports it's failing", d.realloc > 0 && `${d.realloc} reallocated`, d.pending > 0 && `${d.pending} unreadable`, d.uncorrect > 0 && `${d.uncorrect} uncorrectable`].filter(Boolean);
  const sum = [d.smart_passed == null ? "Not read yet" : d.smart_passed === false ? "FAILED" : "Passed", d.temp ? d.temp + "°C" : "", probs.length ? probs.join(", ") : "no bad sectors"].filter(Boolean).join(" · ");
  return (d.health != null ? group(row("Estimated health", { sub: `${d.health}%${d.life != null ? ` · ${d.life}% of its rated wear left` : ""} — a guide from its SMART data, not a promise` })) : "")
    + `<div class="expand ign${smartOpen ? " open" : ""}" style="margin-top:${d.health != null ? 14 : 0}px">${group(`<div class="row click" data-act="smartfold"><span class="ri" style="color:var(--sub);background:color-mix(in srgb, var(--sub) 16%, transparent)">${I("speed")}</span>
      <div class="t"><b>Health checks</b><small>${esc(sum)}</small></div><span class="chev muted">${I("down")}</span></div>
    <div class="xb">${(d.hours ? row("Powered on", { sub: `${d.hours.toLocaleString()} hours (${(d.hours / 8766).toFixed(1)} years)` }) : "")
      + row("Overall", { sub: d.smart_passed == null ? "Not read yet — run a quick test" : d.smart_passed === false ? "FAILED" : "Passed" }) + row("Temperature", { sub: d.temp ? d.temp + "°C" : "—" })
      + row("Reallocated sectors", { sub: String(d.realloc ?? "—") }) + row("Unreadable (pending)", { sub: String(d.pending ?? "—") }) + row("Uncorrectable errors", { sub: String(d.uncorrect ?? "—") })
      + row("Cable errors (all-time)", { sub: `${d.crc ?? "—"} · if this keeps rising, check the cable` })}</div>`)}</div>`;
}
async function undismissAlert(key, redraw) {
  if (!isAdmin()) return viewOnly();
  try { await post("/api/v1/alerts/undismiss", { key }); toast("It's an active alert again"); } catch (e) { toast(e.message); }
  await refresh(); redraw?.();
}
async function dismissAlert(key, redraw) {
  if (!key) return toast("Update the server to dismiss alerts from here");
  if (!isAdmin()) return viewOnly();
  const st = S.overview?.status; if (st) { st.active = (st.active || []).filter(a => a.key !== key); if (!st.active.some(a => a.level !== "ok")) Object.assign(st, { level: "ok", headline: "All systems normal", active_count: 0 }); }
  redraw?.();
  try { await post("/api/v1/alerts/dismiss", { key }); toast(key.startsWith("drive:") ? "Forgotten — it won't be reported missing again" : "Ignored until it clears"); } catch (e) { toast(e.message); }
  await refresh(); redraw?.();
}
async function alertMenu(key, redraw) {
  const a = activeAlerts().find(x => x.key === key); if (!a) return;
  const drive = key.startsWith("drive:"), opts = [];
  if (key.startsWith("mount:")) opts.push(["mount", "Mount it now", "If the drive is plugged in, Nova mounts it from /etc/fstab"]);
  opts.push(["ignore", drive ? "Removed on purpose" : "Ignore until it clears", drive ? "Stop reporting this drive as missing" : "No more alerts for this until it's fixed and comes back"]);
  if (/^(drive|smart|temp):/.test(key)) opts.push(["hw", "Storage & hardware"]);
  const v = await choose(cleanTitle(a.title), opts, null);
  if (v === "ignore") return dismissAlert(key, redraw);
  if (v === "hw") return location.hash = "#/hardware";
  if (v === "mount") { if (!isAdmin()) return viewOnly(); try { await post("/api/v1/mounts/mount", { mount: key.slice(6) }); toast("Mounted"); } catch (e) { toast(e.message); } await refresh(); redraw?.(); }
}
function wireAttention(ctx, redraw) {
  swipeable(ctx.root, { onRight: key => dismissAlert(key, redraw), onLeft: key => dismissAlert(key, redraw) });
}

function liveNow() { return S.cache["/api/v1/stats?now"]?.now || S.lastNow || S.cache["/api/v1/stats"]?.now || null; }
async function fetchNow() {
  try { const r = await api("GET", "/api/v1/stats?since=9e12"); if (r.now?.cpu != null) { S.lastNow = r.now; S.cache["/api/v1/stats?now"] = r; } } catch {}
}

// ═════════════════════════════════════ HOME ═════════════════════════════════════
/** Home: a small "working on it" note while background tasks run (tap → Inbox, where their progress is). */
function tasksChip() {
  const tk = S.overview?.tasks || [];
  if (!tk.length) return "";
  return `<span class="sep"></span><span class="taskchip" data-act="go:inbox" title="${esc(tk.map(t => t.title).join(", "))}">${I("sync")}${tk.length === 1 ? `${esc(tk[0].title)} · ${Math.round(tk[0].pct || 0)}%` : `${tk.length} in progress`}</span>`;
}
/** Home → your servers: this one and every other Nova server added under Servers, side by side (only when there are others). */
function serverStrip() {
  const list = S.nodes; if (!list?.length) return "";
  const pc = v => v == null ? "—" : Math.round(v) + "%";
  const o = S.overview || {}, n = liveNow() || {};
  // the one you're looking at comes first (live numbers); then this server (when you're controlling another) and the rest
  const cur = { id: S.target?.id || "", name: serverName(), level: o.status?.level, headline: o.status?.headline, ok: !S.error, cpu: n.cpu, mem: n.mem, containers: o.containers };
  const tile = (x, on) => { const col = x.ok === false ? "var(--sub)" : levelColor(x.level || "ok"), c = x.containers || {};
    return `<button class="srvchip glass press${on ? " on" : ""}" data-act="${on ? "go:servers" : "srv:" + esc(x.id || "here")}" title="${esc(x.ok === false ? x.error || "Can't reach it" : x.headline || x.name)}">
      <span class="dot" style="background:${col}"></span><b>${esc(x.name)}</b>
      <small>${x.ok === false ? "Offline" : x.cpu === undefined ? "This server" : `CPU ${pc(x.cpu)} · RAM ${pc(x.mem)}${c.total != null ? ` · ${c.running}/${c.total}` : ""}`}</small></button>`; };
  return `<div class="srvstrip" id="srvstrip">${tile(cur, true)}${S.target ? tile({ id: "", name: S.homeName || "This server", level: "ok" }, false) : ""}${list.filter(x => x.id !== S.target?.id).map(x => tile(x, false)).join("")}</div>`;
}
// ── recommended actions: what's worth doing on the server now (worked out on the server) ──
const REC_COLOR = { urgent: "var(--red)", warn: "var(--amber)", tip: "var(--blue)" };
const REC_ICON = { urgent: "err", warn: "warn", tip: "info" };
const recRow = (r, short = true) => row(r.title, { sub: r.detail, icon: REC_ICON[r.level] || "info", tint: REC_COLOR[r.level], click: r.action?.go ? "go:" + r.action.go : "",
  end: short && r.action?.go ? I("back", "flip") : "" });
/** Home: the top three, and a link to the rest (nothing at all when there's nothing to do). */
function recsCard() {
  const l = S.cache["/api/v1/recommendations"]?.recommendations || [];
  if (!l.length) return "";
  return sec("Recommended") + `<div id="recs">` + group(l.slice(0, 3).map(r => recRow(r)).join("") + (l.length > 3 ? row(`See all ${l.length}`, { blue: true, icon: "list", click: "go:recommended" }) : "")) + `</div>`;
}
/** Hold (or right-click) a recommendation: hide it for a month, or hide the whole card from Home. */
function wireRecHold(root, list, redraw, onHome) {
  $$("#recs .row", root).forEach((el, i) => { const r = list[i]; if (!r) return;
    onHold(el, async () => {
      const opts = [...(isAdmin() ? [["snooze", "Not now", "Hide this one for a month"]] : []), ...(r.action?.go ? [["open", r.action.label || "Open", ""]] : []),
        ...(onHome ? [["hide", "Hide Recommended from Home", "Turn it back on in Edit Home"]] : [])];
      const v = await choose(r.title, opts, null);
      if (v === "open") { location.hash = "#/" + r.action.go; return; }
      if (v === "hide") { prefs.homeRecs = false; toast("Recommended is hidden — Edit Home brings it back"); return redraw(); }
      if (v === "snooze") try { await post("/api/v1/recommendations/snooze", { id: r.id }); await get("/api/v1/recommendations"); toast("Hidden for a month"); redraw(); } catch (e) { toast(e.message); }
    });
  });
}
export async function recommended(ctx) {
  const draw = () => {
    const r = S.cache["/api/v1/recommendations"], l = r?.recommendations || [];
    ctx.show(!r ? note("Looking at the server…") : `<div id="recs">${l.length ? l.map(x => group(recRow(x, false) + (isAdmin() ? `<div style="display:flex;gap:8px;padding:0 16px 12px 66px">${x.action?.go ? `<button class="pillbtn press" data-act="go:${esc(x.action.go)}">${esc(x.action.label)}</button>` : ""}<button class="pillbtn plain press" data-act="snooze:${esc(x.id)}">Not now</button></div>` : ""))).join("") : note("Nothing to do — the server looks after itself right now.")}</div>
      ${r?.snoozed ? note(`${r.snoozed} hidden for now (Not now hides one for a month).`) : ""}
      ${note("Nova works these out from what it already watches: space, memory, updates, drive health, backups, devices and more. Nothing changes until you tap a fix.")}`,
      { title: "Recommended", actions: [{ act: "fresh", icon: "refresh", label: "Look again" }] });
    wireRecHold(ctx.root, l, draw, false);
  };
  ctx.handlers({
    snooze: async (...a) => { const id = a.filter(x => typeof x === "string").join(":");      // ids hold ":" (the tapped button comes last, not part of it)
      try { await post("/api/v1/recommendations/snooze", { id }); await get("/api/v1/recommendations"); draw(); } catch (e) { toast(e.message); } },
    fresh: async () => { try { await get("/api/v1/recommendations?fresh=1"); draw(); } catch (e) { toast(e.message); } },
  });
  draw(); try { await get("/api/v1/recommendations"); } catch {} if (ctx.alive()) draw();
}

export async function home(ctx) {
  const statCard = (icon, label, value, sub, frac, color) => `<div class="stat glass press" data-act="go:status"><div class="lbl" style="color:${color}">${I(icon)}<span class="muted">${esc(label)}</span></div>
    <div class="val">${esc(value)}</div><div class="sub">${esc(sub)}</div><div style="margin-top:10px">${frac != null ? bar(frac, color) : ""}</div></div>`;
  const stats = () => {
    const n = liveNow(), m = S.overview?.status?.metrics || {}, cs = S.overview?.containers, disk = m.root_used;
    const free = (/\(([^)]*free)\)/.exec(disk || "") || [])[1];
    return `<div class="stats" id="hstats">
      ${statCard("cpu", "CPU", n ? Math.round(n.cpu) + "%" : "—", n?.temp ? Math.round(n.temp) + "°C" : m.cpu_temp || "", n ? n.cpu / 100 : null, "var(--blue)")}
      ${statCard("mem", "Memory", n ? Math.round(n.mem) + "%" : (m.memory || "—").split(" ")[0], n ? `${n.mem_used_gb} / ${n.mem_total_gb} GB` : "", n ? n.mem / 100 : (pct(m.memory) ?? 0) / 100, "var(--violet)")}
      ${statCard("disk", "Disk", free || "—", "system drive", pct(disk) != null ? pct(disk) / 100 : null, "var(--green)")}
      ${statCard("box", "Services", cs ? `${cs.running}/${cs.total}` : "—", m.websites ? "sites " + m.websites : "containers running", cs?.total ? cs.running / cs.total : null, "var(--amber)")}</div>`;
  };
  const pills = () => {
    const l = homeChips();
    if (!l.length) return `<div class="hint glass" id="chips">Hold here to add shortcuts</div>`;
    return `<div class="pillbar glass${l.length >= 5 ? " five" : ""}" id="chips">${l.map(s => `<button class="press" data-act="go:${s.route}">${I(s.icon)}<span class="l">${esc(l.length >= 5 ? s.short : s.label)}</span>${s.id === "inbox" && S.unread ? `<span class="badge">${S.unread}</span>` : ""}</button>`).join("")}</div>`;
  };
  const draw = () => {
    const st = S.overview?.status, lvl = st?.level || "ok", cs = S.overview?.containers;
    const head = S.error ? "Disconnected" : S.reconnecting ? "Reconnecting…" : !st ? "Connecting…" : lvl === "ok" ? "All systems normal" : `${st.active_count} need${st.active_count === 1 ? "s" : ""} attention`;
    const icon = S.error || S.reconnecting ? "sync" : lvl === "ok" ? "okc" : "err";
    const top = `<div class="home-title"><h1><span>${esc(serverName())}</span></h1><span class="sp"></span>
        <button class="circle" data-act="search" aria-label="Search (press /)">${I("search")}</button><button class="circle" data-act="refresh" aria-label="Refresh">${I("refresh")}</button><button class="circle" data-act="more" aria-label="More">${I("more")}</button></div>
      <div class="statusline" data-act="go:status"><span style="color:${S.error || S.reconnecting ? "var(--sub)" : levelColor(lvl)};display:flex">${I(icon)}</span>${esc(head)}${cs ? `<span class="sep"></span>${cs.running}/${cs.total} running${cs.stopped ? ` · ${cs.stopped} stopped` : ""}` : ""}${tasksChip()}</div>
      <div style="height:14px"></div>${topBanner()}${S.serverUpdate && localStorage.getItem("nova.dismissedUpdate") !== S.serverUpdate ? `<div class="upnote glass"><span style="color:var(--blue);display:flex">${I("update")}</span><b data-act="go:updates" style="flex:1;cursor:pointer">Nova ${esc(S.serverUpdate)} is ready to install</b><button class="xbtn" data-act="upx" aria-label="Not now">${I("close")}</button></div>` : ""}`;
    const order = homeOrder().filter(id => prefs[HOME_SECTIONS.find(s => s[0] === id)?.[3]] && (id !== "servers" || serverStrip()));
    const block = id => id === "hero" ? `<canvas class="hero" id="hero" data-act="herotap" title="Tap the fan, the drive lights or the power button"></canvas>` : id === "shortcuts" ? pills() : id === "servers" ? serverStrip() : id === "recs" ? recsCard() : stats();
    const wide = innerWidth >= 1100;
    ctx.show(wide
      ? `<div class="home-wide"><div>${top}${prefs.homeHero ? block("hero") : ""}</div><div>${order.filter(x => x !== "hero").map(id => `<div class="block">${block(id)}</div>`).join("")}</div></div>`
      : `${top}${order.map(id => `<div class="block">${block(id)}</div>`).join("")}`, { tabroot: true, noHeader: true });
    const hero = $("#hero");
    if (hero) hero.onclick = async ev => {                  // the picture works: fan → Lighting, drive lights → Storage, power button → power
      ev.stopPropagation();
      const hit = heroHit(hero, ev);
      if (hit === "fan" && has("lighting")) return ctx.go("lighting");
      if (hit === "drives") return ctx.go("hardware");
      if (hit === "power") {
        if (!isAdmin()) return viewOnly();
        const v = await choose("Power", POWER.map(([id, label, sub]) => [id, label, sub]), null);
        if (v) await powerDo(v);
      }
    };
    animate($("#hero"), "server", () => S.error ? "#9e9ea4" : getComputedStyle(document.documentElement).getPropertyValue(lvl === "ok" ? "--green" : lvl === "warning" ? "--amber" : "--red").trim());
    onHold($("#chips"), () => ctx.go("edit-shortcuts"));
    wireRecHold(ctx.root, (S.cache["/api/v1/recommendations"]?.recommendations || []).slice(0, 3), draw, true);
  };
  // once per visit: is there a newer signed release for the server? (the server checks at most every 30 minutes)
  if (isAdmin() && !S.updateAsked) { S.updateAsked = true;
    post("/api/v1/server/update/check", { quiet: true }).then(u => { if (u?.state === "available") { S.serverUpdate = String(u.available || "").replace("~", "-"); if (ctx.alive()) draw(); } }).catch(() => {}); }
  ctx.handlers({
    upx: () => { try { localStorage.setItem("nova.dismissedUpdate", S.serverUpdate); } catch {} draw(); },
    herotap: () => {},                                     // (the picture's own click handler decides; see draw)
    srv: async id => { if (await SV.control(id === "here" ? "" : id) === false) ctx.go("servers"); },
    refresh: async () => { await refresh(); await fetchNow(); draw(); toast("Up to date"); },
    disconnected: async () => { if (await showDisconnected()) draw(); },
    more: async () => {
      const opts = [...(navTabs().some(t => t.id === "menu") ? [] : [["menu", "Menu"]]), ["edit-home", "Edit Home"], ["settings", "Settings"], ["about", "About"]];
      const v = await choose(serverName(), opts.map(([k, l]) => [k, l]), null); if (v) ctx.go(v);
    },
  });
  draw();
  if (prefs.homeRecs) get("/api/v1/recommendations").then(() => { if (ctx.alive()) draw(); }).catch(() => {});      // the server caches it for a minute
  ctx.every(15000, async () => { await refresh(); if ((S.overview?.status?.metrics?.data_backup || "").includes("running")) await get("/api/v1/backup").catch(() => {});
    if (prefs.homeServers) await loadNodes().catch(() => {}); draw(); }, true);
  ctx.every(3000, async () => { await fetchNow(); const s = $("#hstats"); if (s) s.outerHTML = stats(); }, true);
}

// ═════════════════════════════════════ MENU ═════════════════════════════════════
export async function menu(ctx) {
  const draw = () => {
    const m = S.overview?.status?.metrics || {}, cs = S.overview?.containers, f = S.fan, disk = storageList(m).sort((a, b) => b.pct - a.pct)[0];
    const tile = (icon, tint, title, sub, act) => `<button class="mtile glass press" data-act="${act}"><span style="color:${tint};display:flex">${I(icon)}</span><b>${esc(title)}</b><small class="muted">${esc(sub)}</small></button>`;
    const apps = (S.cache["/api/v1/apps"]?.apps || []).filter(a => !a.hidden).slice(0, 10);
    const cell = (label, inner, act, href, wid) => href ? `<a class="appcell press" href="${esc(href)}" target="_blank" rel="noopener"${wid ? ` data-wid="${esc(wid)}"` : ""}>${inner}<span class="appname">${esc(label)}</span></a>`
      : `<button class="appcell press" data-act="${act}" style="border:0;background:none;font:inherit;color:var(--text);cursor:pointer">${inner}<span class="appname">${esc(label)}</span></button>`;
    const glyph = (ic, c) => `<span class="appic" style="background:color-mix(in srgb,${c} 18%,var(--card));color:${c}">${I(ic)}</span>`;
    ctx.show(`<button class="searchbox glass" data-act="search" style="width:calc(100% - 2*var(--gutter));text-align:left">${I("search")}<span class="muted" style="font-size:17px">Search Nova</span></button>
      <div class="mtiles">${tile("box", "var(--blue)", "Containers", cs ? `${cs.running}/${cs.total} up` : "—", "go:containers")}${tile("disk", "#3ecf6e", "Storage", disk ? `${disk.name} ${Math.round(disk.pct)}%` : "—", "go:hardware")}
        ${has("lighting") ? tile("bulb", "#ffb020", "Lighting", f ? (f.on !== false ? `${cap(f.effect)} · ${f.brightness}%` : "Off") : "—", "go:lighting") : tile("status", "#3ecf6e", "Status", "Live graphs", "go:status")}
        ${tile("bell", "#ff5a5a", "Inbox", S.unread ? `${S.unread} new` : "All caught up", "go:inbox")}${favoriteTiles(tile)}</div>${favoritesLink()}
      ${sec("Apps")}<div class="mapps">${isAdmin() && has("files") ? cell("Files", glyph("folder", "#3e91ff"), "go:files") : ""}${isAdmin() && has("terminal") ? cell("Terminal", glyph("term", "#3ecf6e"), "go:term") : ""}
        ${apps.map(a => { const h = ST.appHref(a); return a.kind === "command" ? cell(a.name, glyph("term", "#8e8e93"), "go:apps") : cell(a.name, `<span class="appic" data-ic="${esc(a.id)}">${esc((a.name || "?")[0])}</span>`, "go:apps", h, a.id); }).join("")}
        ${cell("All apps", glyph("apps", "var(--sub)"), "go:apps")}</div>
      <div class="installrow">${group(row("Install Nova", { sub: "Its own window and icon, like an app — no app store needed", blue: true, icon: "computer", click: "install" }))}</div>
      ${sec("Server")}${group(row("Status", { sub: "Live graphs, storage, backups", blue: true, icon: "status", tint: "#3ecf6e", click: "go:status" })
        + row("Storage & hardware", { sub: "Drives, pools, set up drives", blue: true, icon: "disk", tint: "#3ecf6e", click: "go:hardware" })
        + (isAdmin() ? row("Free up", { sub: "Old images, logs, caches, trash — and memory", blue: true, icon: "del", tint: "var(--amber)", click: "go:freeup" }) : "")
        + row("Backups", { sub: "What's backed up, restore files", blue: true, icon: "backup", click: "go:backups" })
        + row("Updates", { sub: "Packages, containers and Nova", blue: true, icon: "update", tint: "#3ecf6e", click: "go:updates" })
        + (has("diag") ? row("Diagnostics", { sub: "Speed, stress and network tests", blue: true, icon: "speed", tint: "#64d2ff", click: "go:diag" }) : "")
        + row("Servers", { sub: (S.nodes || []).length ? `${S.nodes.length + 1} servers — switch between them here` : "Your other Nova servers in one place — add one with +", blue: true, icon: "dns", click: "go:servers" }))}
      ${sec("Apps & tools")}${group((has("store") ? row("App store", { sub: "Containers and programs", blue: true, icon: "store", tint: "#bf5af2", click: navTabs().some(t => t.route === "store") ? "tab:store" : "go:store" }) : "")
        + row("Containers", { sub: cs ? `${cs.running} of ${cs.total} running` : null, blue: true, icon: "box", click: "go:containers" })
        + (has("lighting") ? row("Lighting", { sub: "Effects, colors, schedules, your lights", blue: true, icon: "bulb", tint: "#ffb020", click: "go:lighting" }) : "")
        + row("Quick panel", { sub: "Your shortcuts — tap ✎ to customize", blue: true, icon: "widgets", click: "go:quick" })
        + row("Dashboard mode", { sub: "Always-on screen for a tablet or spare screen", blue: true, icon: "dash", tint: "#64d2ff", click: "go:dashboard" }))}
      ${sec("Alerts")}${group(row("Inbox", { sub: S.unread ? `${S.unread} new` : "Alerts, logins and server events", blue: true, icon: "bell", tint: "#ff5a5a", click: "go:inbox" })
        + row("Notifications", { sub: "Discord, logins, USB", icon: "bell", click: "go:notify" }))}
      ${sec("You and this browser")}${group(row("Security & privacy", { sub: "Approvals, codes, passkeys, privacy", blue: true, icon: "shield", click: "go:security" }) + row("Users & devices", { sub: "Who can reach this server", blue: true, icon: "group", click: "go:devices" })
        + row("Appearance", { sub: "Look, theme, home layout", blue: true, icon: "palette", click: "go:appearance" })
        + row("Settings", { sub: "Labs, features, plugins", icon: "gear", tint: "var(--sub)", click: "go:settings" })
        + row("Questions", { sub: "Is it safe, what it connects to, how to undo things", icon: "help", tint: "var(--sub)", click: "go:faq" })
        + row("About Nova", { sub: "Nova web", icon: "info", tint: "var(--sub)", click: "go:about" }))}`,
      { title: "Menu", root: true, tabroot: true });
    $$("[data-ic]", ctx.root).forEach(async el => { const u = await ST.appIconUrl(el.dataset.ic); if (u && el.isConnected) { el.classList.add("img"); el.style.backgroundImage = `url(${u})`; el.textContent = ""; } });
  };
  draw(); get("/api/v1/apps").then(() => ctx.alive() && draw()).catch(() => {});
  ctx.every(15000, async () => { await refresh(); draw(); });
}

// ════════════════════════════════════ STATUS ════════════════════════════════════
export async function status(ctx) {
  let hour = false;
  const L = { tick: 0, added: 1, recent: [], history: [], now: null };
  const pts = () => !hour && L.recent.length >= 2 ? L.recent.slice(-122) : L.history;
  const live = () => !hour && L.recent.length >= 2;
  const gcard = (label, id, v, sub) => `<div class="card glass" data-act="big:${id}" title="Click to enlarge"><div class="lbl">${esc(label)}</div><div class="val" id="${id}v">${esc(v)}</div><div class="sub" id="${id}s">${esc(sub)}</div><canvas id="${id}"></canvas></div>`;
  let big = null;                                    // the graph shown large (tap a card), drawn with the others
  // CPU: double-click (or double-tap) splits it into a graph per core, like Task Manager; again to join them back
  const split = () => !!prefs.cpuSplit && live() && (L.now?.cpus?.length || 0) > 1;
  // columns that fill every row (12 cores → 6 × 2, 16 → 8 × 2, 8 → 4 × 2), or near-square when nothing divides evenly
  const kcols = k => { for (let c = Math.ceil(Math.sqrt(k)); c <= 8; c++) if (k % c === 0) return c; return Math.ceil(Math.sqrt(k * 1.6)); };
  const kgrid = (n, pre) => `<div class="kgrid" style="--kc:${kcols(n.cpus.length)}">${n.cpus.map((v, i) => `<div class="kcell"><canvas id="${pre}${i}"></canvas><small id="${pre}${i}l">${i} · ${Math.round(n.cpus[i] || 0)}%</small></div>`).join("")}</div>`;
  const cpuCard = n => `<div class="card glass cpucard${split() ? " split" : ""}" data-act="cpuclick" title="Click to enlarge · double-click for each core"><div class="lbl">CPU${split() ? ` · ${n.cpus.length} cores` : ""}</div><div class="val" id="c1v">${n ? Math.round(n.cpu) + "%" : "—"}</div><div class="sub" id="c1s">${n ? `load ${n.load} · ${n.cores} cores` : ""}</div>${split() ? kgrid(n, "k") : `<canvas id="c1"></canvas>`}</div>`;
  let clickT = 0;
  const GRAPH = { c1: ["CPU", "--blue", x => +(x.cpu || 0), v => Math.round(v) + "%", 100], c2: ["Memory", "#bf5af2", x => +(x.mem || 0), v => Math.round(v) + "%", 100],
                  c3: ["CPU temperature", "--amber", x => +(x.temp || 0), v => Math.round(v) + "°C", null], c4: ["Network", "--green", x => (x.rx || 0) + (x.tx || 0), v => rate(v), null],
                  c5: ["Graphics", "--red", x => +(x.gpu || 0), v => Math.round(v) + "%", 100], c6: ["Swap", "#ff9f0a", x => +(x.swap || 0), v => Math.round(v) + "%", 100],
                  c7: ["Disk I/O", "#64d2ff", x => +(x.io || 0), v => rate(v), null] };
  const draw = () => {
    const st = S.overview?.status || {}, m = st.metrics || {}, n = L.now || liveNow(), lvl = st.level || "ok";
    const active = (st.active || []).filter(a => a.level !== "ok");
    const storage = storageList(m);
    const backupRunning = (m.data_backup || "").includes("running");
    ctx.show(`
      <div class="center" style="padding:14px 0 6px"><div style="width:84px;height:84px;border-radius:50%;margin:0 auto 10px;display:grid;place-items:center;background:color-mix(in srgb, ${levelColor(lvl)} 18%, transparent);color:${levelColor(lvl)}">${I(lvl === "ok" ? "okc" : "err").replace('class="i ', 'style="width:48px;height:48px" class="i ')}</div>
        <div style="font-size:20px;font-weight:700">${esc(lvl === "ok" ? "All systems normal" : st.headline)}</div>
        <div class="muted" style="font-size:14px">Updated ${esc((/\d{1,2}:\d{2}/.exec(st.updated_local || "") || ["—"])[0])} · up ${n?.uptime_s ? uptime(n.uptime_s) : esc(m.uptime || "—")}</div></div>
      ${S.error ? disconnectedBanner() : ""}
      ${attentionHtml()}
      ${sec(hour ? "Last hour" : "Live · last 2 minutes")}
      ${segmented(["Live", "Last hour"], hour ? 1 : 0, "range")}
      <div style="height:6px"></div>
      <div class="grid four">
        ${cpuCard(n)}
        ${gcard("Memory", "c2", n ? Math.round(n.mem) + "%" : "—", n ? `${n.mem_used_gb} of ${n.mem_total_gb} GB` : "")}
        ${gcard("CPU temperature", "c3", n?.temp ? Math.round(n.temp) + "°C" : m.cpu_temp || "—", n?.nvme_temp ? `NVMe ${Math.round(n.nvme_temp)}°C` : "")}
        ${gcard("Network", "c4", n ? "↓ " + rate(n.rx) : "—", n ? "↑ " + rate(n.tx) : "")}
        ${n?.gpu != null ? gcard("Graphics", "c5", Math.round(n.gpu) + "%", "GPU busy") : ""}
        ${n?.swap_total_gb === 0 ? "" : gcard("Swap", "c6", n ? Math.round(n.swap || 0) + "%" : "—", n?.swap_total_gb ? `${n.swap_used_gb} of ${n.swap_total_gb} GB` : "of swap in use")}
        ${gcard("Disk I/O", "c7", n ? rate(n.io || 0) : "—", "read + write, all drives")}
      </div>
      <p class="note" id="gnote">${pts().length < 3 ? (hour ? "Graphs fill in over the next few minutes." : "Connecting to the live feed…") : ""}</p>
      ${sec("Storage")}${group(storage.map(x => { const p = x.pct / 100; return `<div class="row" style="display:block"><div style="display:flex"><span style="flex:1;font-size:16px">${esc(x.name)}</span><span class="muted" style="font-size:14px">${Math.round(x.pct)}% · ${esc(x.free)}</span></div><div style="margin-top:8px">${bar(p, usageColor(p))}</div></div>`; }).join("")
        + row("Drives", { sub: `${m.drives || "—"} · ${m.drive_temps || ""}`, blue: true, icon: "disk", click: "go:hardware" }))}
      ${(() => { const rows = [...(backupRunning || m.data_backup ? [row("Data backup", { sub: backupRunning ? backupLine(S.cache["/api/v1/backup"]) : m.data_backup, blue: backupRunning, icon: "backup" })] : []),
          ...[["Backup sets", m.backup_sets], ["Photo check", (m.backup_verify || "").replace("✗", "").trim()], ["Server settings backup", m.config_backup],
              ...Object.keys(m).filter(k => k.endsWith("_db_backup")).map(k => [cap(k.slice(0, -10).replace(/_/g, " ")) + " database backup", m[k]])].filter(x => x[1]).map(([l, v]) => row(l, { sub: v }))];
        return rows.length ? sec("Backups") + group(rows.join("")) : ""; })()}
      ${sec("Services")}${group(([["Containers", m.containers], ["Websites", m.websites], ["Swap", n?.swap_total_gb === 0 ? "" : m.swap]].filter(x => x[1]).map(([l, v]) => row(l, { sub: v })).join("") || row("—"))
        + (n?.swap_total_gb === 0 && isAdmin() ? row("Swap", { sub: "None set up — add a swap file, so a busy moment doesn't run memory out", blue: true, icon: "mem", click: "swapsetup" }) : ""))}`,
      { title: "Server status" });
    wireCommon(ctx.root, { onSeg: (_, i) => { hour = i === 1; draw(); } });
    wireAttention(ctx, draw);
    graphs();
  };
  const graphs = () => {
    const p = pts(), n = L.now, opts = { ...(live() ? { window: 120, tick: L.tick, added: L.added } : { tick: 0 }), times: p.map(x => x.t) };
    for (const [id, [, col, val, fmt, max]] of Object.entries(GRAPH)) spark($("#" + id), p.map(val), col, { ...opts, fmt, max });
    // each core's graph (the split CPU card, or the enlarged CPU view on "Each core"); the hour history has no per-core points
    const cores = (pre, o = {}) => (n?.cpus || []).forEach((v, i) => { const c = $("#" + pre + i); if (!c) return;
      spark(c, p.map(x => +(x.cpus?.[i] || 0)), "--blue", { ...opts, fmt: v => Math.round(v) + "%", max: 100, ...o });
      const l = $("#" + pre + i + "l"); if (l) l.textContent = `${i} · ${Math.round(v || 0)}%`; });           // each core's percent, always shown
    cores("k");
    if (big && big.canvas.isConnected) {
      if (big.id === "c1" && big.each && $("#bk0")) cores("bk", { scrub: "all" });
      else { const [, col, val, fmt, max] = GRAPH[big.id]; spark(big.canvas, p.map(val), col, { ...opts, fmt, max, scrub: "all" }); }
    }
    const gn = $("#gnote"); if (gn) gn.textContent = p.length < 3 ? (hour ? "Graphs fill in over the next few minutes." : "Connecting to the live feed…") : "";
    if (!n) return;
    const set = (id, v) => { const e = $("#" + id); if (e) e.textContent = v; };
    set("c1v", Math.round(n.cpu) + "%"); set("c1s", `load ${n.load} · ${n.cores} cores`);
    set("c2v", Math.round(n.mem) + "%"); set("c2s", `${n.mem_used_gb} of ${n.mem_total_gb} GB`);
    if (n.temp) set("c3v", Math.round(n.temp) + "°C"); set("c4v", "↓ " + rate(n.rx)); set("c4s", "↑ " + rate(n.tx)); if (n.gpu != null) set("c5v", Math.round(n.gpu) + "%"); set("c6v", Math.round(n.swap || 0) + "%"); if (n.swap_total_gb) set("c6s", `${n.swap_used_gb} of ${n.swap_total_gb} GB`); set("c7v", rate(n.io || 0));
  };
  const keyOf = a => { a.pop(); return a.join(":"); };          // keys contain ":" — rejoin the act arguments
  ctx.handlers({ install: async () => (await import("./app.js")).installNova() });
  ctx.handlers({ alert: (...a) => alertMenu(keyOf(a), draw), ignore: (...a) => dismissAlert(keyOf(a), draw),
    // no swap at all: make a swap file on the system drive (it comes back after a restart; the graph returns with it)
    swapsetup: async () => {
      const gb = await choose("Set up swap", [["2", "2 GB"], ["4", "4 GB", "A good size for most servers"], ["8", "8 GB"], ["16", "16 GB"]], "4");
      if (gb) ST.startTask(ctx, "/api/v1/storage/task/swap-setup", { spec: { gb: +gb } });
    },
    cpuclick: () => {
      // one click enlarges the graph; a second within a moment means double-click: split into cores (or join them)
      if (clickT) { clearTimeout(clickT); clickT = 0; if ((L.now?.cpus?.length || 0) > 1) { prefs.cpuSplit = !prefs.cpuSplit; draw(); } return; }
      clickT = setTimeout(() => { clickT = 0; ctx.run("big", "c1"); }, 260);
    },
    big: id => {
      const [label] = GRAPH[id];
      if (id === "c1" && (L.now?.cpus?.length || 0) > 1) {
        const n = L.now, each = split();
        const box = sheet(`<h2>CPU</h2><div class="pad" style="padding-bottom:0">${segmented(["Overall", `Each core · ${n.cpus.length}`], each ? 1 : 0, "cpuview")}</div>
          <p class="muted" style="margin:8px 24px 0">${hour ? "Last hour" : "Live · last 2 minutes"} — ${matchMedia("(any-pointer: fine)").matches ? "point at" : "drag along"} a line to see the value at that time.</p>
          <div class="pad" style="position:relative" id="bigwrap">${each && live() ? kgrid(n, "bk").replace('class="kgrid"', 'class="kgrid big"') : `<canvas class="bigchart" id="bigc"></canvas>`}</div><div class="acts"><button data-b="0">Close</button></div>`, () => { big = null; });
        $("[data-b]", box).onclick = () => { big = null; closeSheet(); };
        wireCommon(box, { onSeg: (_, i) => { prefs.cpuSplit = i === 1; const w = $("#bigwrap", box); w.innerHTML = i === 1 && live() ? kgrid(L.now, "bk").replace('class="kgrid"', 'class="kgrid big"') : `<canvas class="bigchart" id="bigc"></canvas>`;
          big = { id, each: i === 1, canvas: $("#bigc", box) || $("#bk0", box) }; draw(); } });
        big = { id, each: each && live(), canvas: $("#bigc", box) || $("#bk0", box) }; graphs();
        return;
      }
      const box = sheet(`<h2>${esc(label)}</h2><p class="muted" style="margin:0 24px">${hour ? "Last hour" : "Live · last 2 minutes"} — ${matchMedia("(any-pointer: fine)").matches ? "point at" : "drag along"} the line to see the value at that time.</p>
        <div class="pad" style="position:relative"><canvas class="bigchart" id="bigc"></canvas></div><div class="acts"><button data-b="0">Close</button></div>`, () => { big = null; });
      $("[data-b]", box).onclick = () => { big = null; closeSheet(); };
      big = { id, canvas: $("#bigc") }; graphs();
    } });
  const load = r => { L.history = r.history || L.history; if (r.recent) L.recent = r.recent; if (r.now?.cpu != null) { L.now = r.now; S.lastNow = r.now; } L.tick++; L.added = 1; };
  if (S.cache["/api/v1/stats"]) load(S.cache["/api/v1/stats"]);
  draw();
  try { await refresh(); load(await get("/api/v1/stats")); if (ctx.alive()) draw(); } catch {}
  // 1-second feed. The server samples on the second; asking ~350 ms after it means each answer
  // carries exactly one new point, and if two ever arrive the graph glides two steps, so it never snaps back.
  let skew = 0;
  while (ctx.alive()) {
    const lastT = L.recent.at(-1)?.t;
    if (lastT) skew = lastT - Date.now() / 1000;               // server clock vs ours (rough is fine)
    const serverNow = Date.now() / 1000 + skew, wait = ((Math.ceil(serverNow) + .35 - serverNow) % 1) * 1000 || 1000;
    await sleep(Math.max(200, wait));
    if (!ctx.alive()) break;
    if (document.hidden) continue;
    if (!L.recent.length) { try { load(await get("/api/v1/stats")); graphs(); } catch {} continue; }
    try {
      const r = await api("GET", "/api/v1/stats?since=" + L.recent.at(-1).t);
      if (r.now?.cpu != null) { L.now = r.now; S.lastNow = r.now; }
      const add = r.recent || [];
      if (add.length) { L.recent = L.recent.concat(add).slice(-180); L.tick++; L.added = add.length; }
      // the per-core section appears once the live feed carries cores (and goes with the hour view)
      if (ctx.alive()) { if (!!$("#k0") !== (live() && split())) draw(); else graphs(); }
    } catch { /* offline for a moment: keep the last graph */ }
  }
}

// ══════════════════════════════════ CONTAINERS ══════════════════════════════════
const POLICIES = [["unless-stopped", "Always, unless you stop it"], ["always", "Always"], ["on-failure", "Only if it crashes"], ["no", "Never"]];
const stateColor = (s, h) => h === "unhealthy" ? "var(--amber)" : s === "running" ? "var(--green)" : s === "restarting" ? "var(--amber)" : "var(--sub)";
// like docker ps: "Up 3 hours", "Exited (1) 2 days ago"
const dur = sec => sec == null ? "" : sec < 60 ? "less than a minute" : sec < 3600 ? `${Math.round(sec / 60)} minute${Math.round(sec / 60) === 1 ? "" : "s"}`
  : sec < 172800 ? `${Math.round(sec / 3600)} hour${Math.round(sec / 3600) === 1 ? "" : "s"}` : `${Math.round(sec / 86400)} days`;
const upText = x => x.up_s != null ? `Up ${dur(x.up_s)}` : x.exited_s != null ? `Exited (${x.exit_code ?? "?"}) ${dur(x.exited_s)} ago` : cap(x.state || "");
const portsText = x => x.network === "host" ? "server's network" : (x.ports || []).filter(p => p.host).map(p => `${p.host_ip === "127.0.0.1" ? "local " : ""}${p.host}→${p.container}${p.proto === "udp" ? "/udp" : ""}`).join(", ");
const openUrls = c => {                 // where to open it from here
  const h = serverHost(), web = p => [80, 443, 8443].includes(+p) ? (+p === 443 || +p === 8443 ? "https" : "http") : "http";
  const pub = (c.published || []).filter(p => p.host && p.host_ip !== "127.0.0.1" && p.proto !== "udp").map(p => +p.host);
  const ports = c.network === "host" ? (c.listening || []).slice(0, 1) : pub;          // (host network: its main port; the rest are listed below)
  return [...new Set(ports)].slice(0, 6).map(p => `${web(p)}://${h}${[80, 443].includes(p) ? "" : ":" + p}/`);
};

// the backgrounds of every look, for Appearance → Background (the CSS draws them: html.bg-*, --wp-*)
const WALLS = [["", "This look's own", "var(--wp-own, none)"], ["glow", "Default's glow", "var(--wp-glow)"], ["vivid", "Glass's vivid glow", "var(--wp-vivid)"],
  ["soft", "Mica's soft wash", "var(--wp-soft)"], ["aero", "Aero's sky", "var(--wp-aero)"], ["plain", "Plain", "none"]];

export async function containers(ctx) {
  const [name, sub] = ctx.args;
  if (name && sub === "logs") return logs(ctx, name);
  if (name && sub === "compose") return composeEditor(ctx, name);
  if (name && sub === "shell") return shell(ctx, name);
  if (name) return container(ctx, name);
  let heavy = false;
  const draw = () => {
    const items = S.cache["/api/v1/containers"]?.containers || [];
    const stacks = {}; items.forEach(x => (stacks[x.stack || x.name] ||= []).push(x));
    const singles = Object.values(stacks).filter(v => v.length === 1).flat();
    const groups = [...Object.entries(stacks).filter(([, v]) => v.length > 1), ...(singles.length ? [["Apps", singles]] : [])];
    const up = items.filter(x => x.state === "running").length;
    const nod = S.overview?.features?.docker === false ? group(row("Docker isn't installed", { sub: "Containers need it. Programs from the store work without it.", icon: "box", tint: "var(--amber)" })
      + (isAdmin() ? row("Install Docker", { sub: "In the app store", icon: "down", blue: true, click: "go:store" }) : "")) : "";
    const st = S.cache["/api/v1/containers/stats"]?.stats || {};
    const use = x => { const u = st[x.name]; return u && x.state === "running" ? ` · CPU ${u.cpu}% · ${u.mem}` : ""; };
    const weight = x => { const u = st[x.name] || {}; return (u.cpu || 0) * 4 + (u.mem_pct || 0); };
    const shown = heavy ? [["Using the most first", [...items].sort((a, b) => weight(b) - weight(a))]] : groups;
    const off = items.filter(x => x.stopped).length;          // stopped on purpose: not a problem, just not running
    ctx.show(`<div class="statusline" style="cursor:default"><span class="dot" style="background:${up + off === items.length ? "var(--green)" : "var(--amber)"}"></span>${S.cache["/api/v1/containers"] ? `${up} of ${items.length - off} running${off ? ` · ${off} stopped` : ""}` : "Loading…"}</div>
      ${items.length > 1 ? segmented(["By app", "Heaviest first"], heavy ? 1 : 0, "csort") : ""}
      ${nod}${shown.map(([g, cs]) => sec(cap(g)) + group(cs.map(x => row(x.name, { sub: `${x.image.split("/").pop()} · ${upText(x)}${x.health ? ` (${x.health})` : ""}${portsText(x) ? " · " + portsText(x) : ""}${use(x)}`, click: "open:" + x.name, end: `<span class="dot" style="background:${stateColor(x.state, x.health)}"></span>` })).join(""))).join("")}`,
      { title: "Containers", actions: isAdmin() ? [{ icon: "add", label: "Add a container", act: "addc" }] : [] });
    wireCommon(ctx.root, { onSeg: (k, i) => { if (k === "csort") { heavy = i === 1; draw(); } } });
  };
  ctx.handlers({ open: n => ctx.go("containers/" + encodeURIComponent(n)),
    addc: async () => { const r = await dialog("Add a container", "", [{ label: "Cancel", value: null }, ...(has("store") ? [{ label: "From the app store", value: "store" }] : []), { label: "Your own", color: "var(--blue)", value: "own" }],
      `<div class="pad"><p class="muted" style="margin:0">Pick a ready-made app from the store, or run any image you like.</p></div>`);
      if (r === "store") ctx.go("store"); else if (r === "own") ctx.go("container-new"); } });
  draw(); ctx.every(10000, async () => {        // the list first (quick), then the live use (docker stats takes a second or two)
    const st = get("/api/v1/containers/stats").catch(() => {});
    await get("/api/v1/containers"); if (ctx.alive()) draw(); await st; if (ctx.alive()) draw(); }, true);
}
async function container(ctx, name) {
  const path = `/api/v1/containers/${encodeURIComponent(name)}`;
  let job = null;
  const draw = () => {
    const c = S.cache[path], running = c?.state === "running" || c?.state === "restarting", dg = diag;
    const trouble = c && (c.state === "restarting" || (dg && (dg.restart_count > 2 || (c.state === "exited" && dg.exit_code)))), troubleHtml = trouble && dg ? `${sec("Troubleshoot")}<div class="group glass trouble">
        <div class="row"><span class="ri" style="color:var(--amber);background:color-mix(in srgb,var(--amber) 16%,transparent)">${I("warn")}</span><div class="t"><b>${c.state === "restarting" ? `It keeps crashing — restarted ${dg.restart_count} time${dg.restart_count === 1 ? "" : "s"}` : `It stopped with an error (code ${dg.exit_code})`}</b>
          <small>${dg.oom ? "It ran out of memory. " : ""}${dg.last_error ? "Last error: " : ""}</small>${dg.last_error ? `<code class="lasterr">${esc(dg.last_error)}</code>` : ""}</div></div>
        ${isAdmin() ? row("Stop it", { sub: "Stops the restart loop — it won't start again until you start it", icon: "stop", tint: "var(--red)", click: "fix:kill" })
          + (dg.can_rollback ? row("Go back to the previous version", { sub: "The image it had before its last update in Nova", icon: "restart", blue: true, click: "fix:rollback" }) : "")
          + (dg.compose ? row("Update it again", { sub: "Download the newest image and start fresh — a fixed version may be out", icon: "update", click: "update" }) : "")
          + (dg.compose ? row("Recreate it", { sub: "Rebuild the container from its compose file (keeps its data)", icon: "sync", click: "fix:recreate" }) : "") : ""}
        ${row("Read its logs", { sub: "The last lines usually say what's wrong — a missing setting, a changed option", icon: "article", click: "logs" })}</div>
      ${note(dg.can_rollback ? "Apps sometimes change what they need between versions; their release notes say what to add." : "Apps sometimes change what they need between versions; their release notes say what to add. Nova keeps the previous version from updates it does itself, so it can go back next time.")}` : "";
    const pill = (icon, label, act) => `<button class="press" data-act="${act}">${I(icon)}<span class="l">${label}</span></button>`;
    ctx.show(`<div class="center" style="padding:18px 0 10px"><div class="glass" style="width:120px;height:120px;border-radius:36px;margin:0 auto 12px;display:grid;place-items:center;color:var(--blue);position:relative">${I("box").replace('class="i ', 'style="width:60px;height:60px" class="i ')}
        <span class="dot" style="position:absolute;right:12px;bottom:12px;width:20px;height:20px;background:${stateColor(c?.state, c?.health)}"></span></div>
        <div class="muted" style="font-weight:600;font-size:17px">${esc(job || (c ? cap(c.state) + (c.health ? " · " + c.health : "") : "Loading…"))}</div><div class="muted" style="font-size:13px">${esc(c?.image || "")}</div></div>
      ${troubleHtml || ""}
      ${isAdmin() && c ? `<div class="pillbar glass">${running ? pill("stop", "Stop", "do:stop") : pill("play", "Start", "do:start")}${pill("restart", "Restart", "do:restart")}${pill("update", "Update", "update")}${pill("term", "Shell", "shell")}</div>` : ""}
      ${c ? `${sec("Live")}${group(row("CPU", { sub: c.cpu || "—" }) + row("Memory", { sub: c.mem || "—" }) + row("Network in / out", { sub: c.net || "—" }) + row("Running since", { sub: `${c.started ? new Date(c.started).toLocaleString() : "—"} · restarted ${c.restarts ?? 0} times` }))}
        ${group(row("Logs", { sub: "See what it's been saying", blue: true, icon: "article", click: "logs" }) + (isAdmin() ? row("Terminal", { sub: "Run commands inside it (approved on your phone)", blue: true, icon: "term", click: "shell" }) : ""))}
        ${sec("Configuration")}${group(row("Restart policy", { sub: (POLICIES.find(p => p[0] === c.restart_policy) || [0, c.restart_policy])[1], blue: true, click: "policy" })
          + row("Stack", { sub: `${c.stack || "—"} · ${c.compose_dir || ""}` }) + (c.privileged ? row("Privileged", { sub: "Has full access to the host", end: `<span style="color:var(--amber)">${I("warn")}</span>` }) : ""))}
        ${(() => { const urls = openUrls(c); return urls.length || c.network === "host" ? sec("Open it") + group(urls.map(u => row(u.replace(/\/$/, ""), { sub: "Opens in a new tab", icon: "open", blue: true, click: "url:" + u })).join("")
            + (c.network === "host" ? row("On the server's own network", { sub: `It doesn't get ports of its own — it listens on the server directly${(c.listening || []).length ? ` (${c.listening.join(", ")})` : ""}, which is why docker ps shows none. Your firewall decides who can reach it.`, icon: "info" }) : "")) : ""; })()}
        ${(c.ports || []).length ? sec("Ports") + group(c.ports.map(p => row(p)).join("")) : ""}
        ${sec("Details")}${group(row("Container ID", { sub: c.id || "—" }) + row("Command", { sub: c.command || "—" }) + row("Created", { sub: c.created ? new Date(c.created + "Z").toLocaleString() : "—" })
          + row("Networks", { sub: Object.entries(c.networks || {}).map(([n, ip]) => ip ? `${n} (${ip})` : n).join(", ") || "—" }) + (c.exposed?.length ? row("Ports it offers", { sub: c.exposed.join(", ") }) : ""))}
        ${isAdmin() && c.compose_file && c.compose_files === 1 ? group(row("Edit its Compose file", { sub: `Ports, folders, environment, anything — checked before it's saved, and you can undo it · ${c.compose_file}`, icon: "edit", blue: true, click: "compose" })) : ""}
        ${(c.mounts || []).length ? sec("Folders") + group(c.mounts.map(m => row(m.target, { sub: m.source + (m.rw === false ? " · read-only" : "") })).join("")) : ""}
        ${(c.env || []).length ? sec("Environment") + group(c.env.map(e => row(e.key, { sub: e.value })).join("")) : ""}
        ${isAdmin() && c.custom ? group(row("Remove this container", { sub: "Stops it and moves its folder to /opt/.nova-uninstalled (nothing is deleted)", icon: "del", tint: "var(--red)", click: "rmcustom" })) : ""}` : ""}`,
      { title: name });
  };
  let diag = null;
  const load = async () => { try { await get(path); } catch (e) { toast(e.message); }
    const c = S.cache[path]; if (c && c.state !== "running") diag = await get(`${path}/diagnose`).catch(() => null); else if (c?.state === "running" && !(diag?.restart_count > 2)) diag = null;
    if (ctx.alive()) draw(); };
  ctx.handlers({
    do: async a => {
      if (a !== "start" && !(await confirm(`${cap(a)} ${name}?`, "Your phone will ask you to confirm with your fingerprint.", cap(a)))) return;
      job = `${cap(a)}ing…`; draw();
      try { await post(`${path}/${a}`); toast(`${name}: ${a} done`); } catch (e) { toast(e.message); } finally { job = null; await load(); }
    },
    update: async () => {
      job = "Updating…"; draw();
      try { const r = await post(`${path}/update`); const j = r?.task ? await waitTask(r.task, t => { job = `Updating… ${Math.round(t.pct || 0)}%`; if (ctx.alive()) draw(); }) : { state: "failed" };
        toast(j.state === "done" ? `${name} is up to date` : `Update failed: ${j.error || ""}`); }
      catch (e) { toast(e.message); } finally { job = null; await load(); }
    },
    shell: () => S.cache[path]?.state === "running" ? ctx.go(`containers/${encodeURIComponent(name)}/shell`) : toast("Start it first"),
    logs: () => ctx.go(`containers/${encodeURIComponent(name)}/logs`),
    compose: () => ctx.go(`containers/${encodeURIComponent(name)}/compose`),
    url: u => window.open(u, "_blank", "noopener"),
    fix: async k => {
      const what = { kill: ["Stop it?", `${name} stops, and its automatic restarts are turned off until you start it again.`, "Stop"], rollback: ["Go back to the previous version?", `${name} restarts on the image it had before its last update. Its data stays.`, "Go back"],
        recreate: ["Recreate it?", `${name} is rebuilt from its compose file. Its data stays.`, "Recreate"] }[k];
      if (!(await confirm(what[0], what[1] + " Your phone confirms it.", what[2], k === "kill" ? "var(--red)" : "var(--blue)"))) return;
      job = { kill: "Stopping…", rollback: "Going back…", recreate: "Recreating…" }[k]; draw();
      try { const r = await post(`${path}/fix/${k}`); toast(r?.note || "Done"); } catch (e) { toast(e.message); } finally { job = null; await load(); }
    },
    rmcustom: async () => {
      if (!(await confirm(`Remove ${name}?`, "It stops, and its folder is kept in /opt/.nova-uninstalled. Your phone will ask for your fingerprint.", "Remove"))) return;
      job = "Removing…"; draw();
      try { const r = await post(`${path}/remove-custom`); const j = r?.task ? await waitTask(r.task) : { state: "failed" }; if (j.state === "done") { toast(`Removed ${name}`); await get("/api/v1/containers").catch(() => {}); return ctx.go("containers"); } toast(`Couldn't remove it: ${j.error || ""}`); }
      catch (e) { toast(e.message); } finally { job = null; if (ctx.alive()) await load(); }
    },
    policy: async () => {
      if (!isAdmin()) return viewOnly();
      const v = await choose("Restart automatically", POLICIES.map(([k, l]) => [k, l]), S.cache[path]?.restart_policy); if (!v) return;
      try { await post(`${path}/policy`, { policy: v }); toast("Saved"); } catch (e) { toast(e.message); } await load();
    },
  });
  draw(); await load(); ctx.every(8000, load);
}
// A container's Compose file: edit it as text; Docker Compose checks it before anything is saved, the old one is kept,
// and the containers are updated to match (your phone confirms). Undo puts the previous version back.
async function composeEditor(ctx, name) {
  const path = `/api/v1/containers/${encodeURIComponent(name)}/compose`;
  let d = null, err = null, dirty = false, busy = "";
  const draw = () => {
    ctx.show(err ? group(row("Couldn't open it", { sub: err, icon: "err", tint: "var(--red)" })) : !d ? group(row("Opening…", { dis: true }))
      : note(`${d.file} · how ${esc(d.project)} runs: its image, ports ("8080:80" = the server's 8080 to the app's 80), folders, environment and restart policy. Docker Compose checks your change before it's saved; the old file is kept, and Undo puts it back.`)
        + `<div style="padding:0 var(--gutter)"><textarea class="editor field" id="cmp" spellcheck="false" autocomplete="off" style="width:100%;min-height:55vh;font:13px/1.45 ui-monospace,SFMono-Regular,Menlo,monospace;white-space:pre;resize:vertical;padding:14px">${esc(d.text)}</textarea></div>`
        + (busy ? note(busy) : "")
        + `<div style="display:flex;gap:12px;padding:18px 22px;flex-wrap:wrap">${d.backup ? `<button class="btn" style="flex:1;background:color-mix(in srgb,var(--text) 8%,transparent);color:var(--text)" data-act="undo">Undo the last change</button>` : ""}
           <button class="btn" style="flex:1;background:color-mix(in srgb,var(--text) 8%,transparent);color:var(--text)" data-act="back">Cancel</button><button class="btn" style="flex:1" data-act="save">Save and apply</button></div>`,
      { title: "Compose · " + name });
    const t = $("#cmp"); if (t) { t.oninput = () => { dirty = true; }; t.onkeydown = e => { if (e.key === "Tab") { e.preventDefault(); t.setRangeText("  ", t.selectionStart, t.selectionEnd, "end"); dirty = true; } }; }
  };
  const load = async () => { try { d = await get(path); err = null; } catch (e) { err = e.message; } if (ctx.alive()) draw(); };
  ctx.handlers({
    save: async () => {
      const text = $("#cmp").value; if (!dirty) return toast("No changes");
      if (!(await confirm("Save and apply?", `${name} and the rest of ${d.project} are updated to match — containers whose settings changed restart. Your phone confirms it.`, "Save", "var(--blue)"))) return;
      busy = "Checking and applying…"; const keep = text; draw(); $("#cmp").value = keep;
      try { const r = await post(path, { text, mtime: d.mtime }); toast(r?.ok === false ? r.error : "Saved — it's running with the new settings"); dirty = false; await load(); }
      catch (e) { busy = ""; draw(); $("#cmp").value = keep; return toast(e.message); }
      busy = ""; draw();
    },
    undo: async () => {
      if (!(await confirm("Undo the last change?", `The Compose file goes back to how it was before (${d.backup}), and ${d.project} is updated to match.`, "Undo", "var(--blue)"))) return;
      busy = "Putting the previous version back…"; draw();
      try { await post(path + "/undo", {}); toast("Put back"); dirty = false; } catch (e) { toast(e.message); }
      busy = ""; await load();
    },
  });
  draw(); await load();
}
async function logs(ctx, name) {
  ctx.show(`<div class="term" id="logs">Loading…</div>`, { title: "Logs · " + name, narrow: false });
  const draw = lines => { const el = $("#logs"); if (!el) return;
    const stick = el.scrollTop + el.clientHeight >= el.scrollHeight - 30;
    el.innerHTML = lines.map(l => { const ts = l.split(" ")[0], msg = l.slice(ts.length + 1); const cls = /error|fatal|panic|exception/i.test(msg) ? "err" : /warn/i.test(msg) ? "warn" : "";
      return `<span class="ts">${esc(ts.slice(11, 19))}</span> <span class="${cls}">${esc(msg)}</span>`; }).join("\n");
    if (stick) el.scrollTop = el.scrollHeight; };
  ctx.every(4000, async () => { try { draw((await get(`/api/v1/containers/${encodeURIComponent(name)}/logs?lines=400`)).lines || []); } catch (e) { toast(e.message); } }, true);
}
async function shell(ctx, name) {
  ctx.show(`<div class="term" id="out">Asking your phone to approve…</div>
    <div style="display:flex;gap:8px;padding:10px var(--gutter)"><input class="field" id="cmd" placeholder="command" autocomplete="off" autocapitalize="off" spellcheck="false" style="font-family:ui-monospace,monospace"><button class="btn" data-act="send">Send</button></div>
    <div class="chips">${[["Ctrl-C", "\u0003"], ["Tab", "\t"], ["↑", "\u001b[A"], ["ls -la", "ls -la\n"], ["df -h", "df -h\n"], ["top -bn1", "top -bn1 | head -20\n"]].map(([l, v]) => `<button class="chip" data-act="key:${encodeURIComponent(v)}">${esc(l)}</button>`).join("")}</div>`,
    { title: "Terminal · " + name, narrow: false });
  let sid; try { sid = (await post(`/api/v1/containers/${encodeURIComponent(name)}/shell`)).session; } catch (e) { $("#out").textContent = e.message; return; }
  const send = t => post(`/api/v1/shell/${sid}`, { input: t }).catch(e => toast(e.message));
  ctx.handlers({ send: () => { send($("#cmd").value + "\n"); $("#cmd").value = ""; }, key: v => send(decodeURIComponent(v)) });
  $("#cmd").onkeydown = e => { if (e.key === "Enter") ctx.run("send"); };
  ctx.onLeave(() => del(`/api/v1/shell/${sid}`).catch(() => {}));
  let off = 0, out = "";
  while (ctx.alive()) {
    try { const r = await get(`/api/v1/shell/${sid}?offset=${off}`);
      if (r.data) { out = (out + r.data.replace(/\x1b\[[0-9;?]*[A-Za-z]/g, "").replace(/\r/g, "")).slice(-200000); const el = $("#out"); if (el) { el.textContent = out; el.scrollTop = el.scrollHeight; } }
      off = r.offset; if (!r.alive) { const el = $("#out"); if (el) el.textContent += "\n[session ended]"; break; } } catch {}
    await sleep(400);
  }
}

// ═════════════════════════════════════ STORE ════════════════════════════════════
const CAT_COLORS = { Media: "#ff6b6b", Photos: "#ff9500", Files: "#3e91ff", Network: "#00c7be", Monitoring: "#3ecf6e", Home: "#ffb020", Productivity: "#5e5ce6", Security: "#bf5af2", Development: "#8e8e93" };
export async function store(ctx) {
  const [id] = ctx.args;
  if (id === "programs") return programSearch(ctx);
  if (id) return storeItem(ctx, id);
  let tab = 0, busy = null, busyPct = 0, q = "", hub = null, hubBusy = false, found = null, timer;
  const appRow = a => `<div class="row"><span class="appicon" style="background:${CAT_COLORS[a.category] || "var(--blue)"};font-weight:700;font-size:22px">${esc((a.name || "?")[0])}</span><div class="t"><b style="font-weight:600">${esc(a.name)}</b><small>${esc(a.description)}</small></div><button class="pillbtn press" data-act="open:${esc(a.id)}">${a.installed ? "Open" : "Get"}</button></div>`;
  const progRow = p => row(p.name || p.pkg, { sub: p.description,
    end: busy === p.pkg ? `<span class="muted" style="font-variant-numeric:tabular-nums">${Math.round(busyPct || 0)}%</span><span class="spinner"></span>`
      : (p.installed && (p.protected || p.essential)) || !isAdmin() ? `<span class="end">${p.installed ? "Installed" : ""}</span>`
      : `<button class="pillbtn press${p.installed ? " red" : ""}" data-act="prog:${esc(p.pkg)}">${p.installed ? "Remove" : "Get"}</button>` });
  const body = () => {
    const ql = q.trim().toLowerCase();
    if (tab === 0) {
      const st = S.cache["/api/v1/store"], l = st?.items || [];
      const nodocker = st && st.docker === false ? group(row("Docker isn't installed", { sub: "Containers need it. Install Docker and Compose from your package manager — Nova sets them up to start with the server.", icon: "box", tint: "var(--amber)" })
        + (isAdmin() ? row("Install Docker", { icon: "down", blue: true, click: "prog:docker.io", end: busy === "docker.io" ? `<span class="spinner"></span>` : "" }) : "")) : "";
      if (!ql) {
        const inst = l.filter(a => a.installed), feat = l.filter(a => a.featured && !a.installed), cats = {};
        l.filter(a => !a.installed && !a.featured).forEach(a => (cats[a.category] ||= []).push(a));
        return nodocker + (inst.length ? sec("Installed") + group(inst.map(appRow).join("")) : "") + (feat.length ? sec("Editor's choice") + group(feat.map(appRow).join("")) : "")
          + Object.keys(cats).sort().map(c => sec(c) + group(cats[c].map(appRow).join(""))).join("") + (st ? "" : note("Loading…"));
      }
      const m = l.filter(a => ["name", "description", "category", "image"].some(k => (a[k] || "").toLowerCase().includes(ql)));
      return nodocker + (m.length ? sec("In the store") + group(m.map(appRow).join("")) : "") + sec("Docker Hub")
        + group(hub === null ? row(hubBusy ? "Searching Docker Hub…" : `Search Docker Hub for “${esc(q.trim())}”`, { sub: "Any public image — you set its ports and folders", icon: "search", blue: true, click: isAdmin() && !hubBusy ? "hub" : "" })
          : !hub.length ? row(`Nothing on Docker Hub for “${esc(q.trim())}”`) : hub.map(r => row(r.image + (r.official ? "  ·  official" : ""), { sub: [r.description, "★ " + r.stars].filter(Boolean).join(" · "), icon: "box", click: "use:" + encodeURIComponent(r.image) })).join(""));
    }
    if (tab === 2) return pluginsBody(ql);
    if (ql.length >= 2) return sec("Programs") + group(found === null ? row("Searching…") : !found.length ? row(`Nothing called “${esc(q.trim())}”`) : found.map(progRow).join(""));
    const cats = {}; (S.cache["/api/v1/programs"]?.items || []).forEach(p => (cats[p.category] ||= []).push(p));
    return (isAdmin() ? note("Type to search everything your package manager offers. These are our picks:") : "") + Object.entries(cats).map(([c, xs]) => sec(c) + group(xs.map(progRow).join(""))).join("") + (S.cache["/api/v1/programs"] ? "" : note("Loading…"));
  };
  // ── Plugins: the community list, what's installed, and installing your own ──
  let cat = null, mine = null;
  const pluginsBody = ql => {
    const inst = new Set((mine || []).map(p => p.id)), list = (cat?.plugins || []).filter(p => !ql || [p.name, p.description, p.author].some(x => (x || "").toLowerCase().includes(ql)));
    return `<div class="rcheck open" style="margin:4px var(--gutter) 8px">${I("warn")}<span>Plugins come from other people. A plugin's tools run commands on your server as your normal account (never as root) — install only ones you trust, and read what each tool runs before you say yes.</span></div>`
      + ((mine || []).length ? sec("Installed") + group(mine.map(p => row(`${p.name} ${p.version}`, { sub: [p.description, p.on ? "" : "switched off"].filter(Boolean).join(" · "), icon: "widgets",
          end: isAdmin() ? `<button class="pillbtn press red" data-act="plrm:${esc(p.id)}">Remove</button>` : "" })).join("")) : "")
      + sec("Community plugins") + group(!cat ? row("Loading…") : cat.error && !cat.plugins.length ? row("Couldn't load the list", { sub: cat.error, icon: "warn", tint: "var(--amber)" })
          : !list.length ? row(ql ? `No plugin matches “${esc(ql)}”` : "None listed yet")
          : list.map(p => row(p.name, { sub: [p.description, (p.author ? "by " + p.author : ""), p.reviewed ? "✓ Reviewed by Nova" : "Not reviewed — listed as-is"].filter(Boolean).join(" · "), icon: p.reviewed ? "shield" : "widgets",
              tint: p.reviewed ? "var(--green)" : "var(--amber)", end: inst.has(p.id) ? `<span class="end">Installed</span>` : isAdmin() ? `<button class="pillbtn press" data-act="plget:${esc(p.id)}">Get</button>` : "" })).join(""))
      + (isAdmin() ? sec("Your own") + group(row("Install from a link", { sub: "An https:// link to a plugin.json or a .zip", icon: "cloud", blue: true, click: "plurl" })
          + row("Install from a file", { sub: "A .zip (or plugin.json) on this device", icon: "up", blue: true, click: "plfile" })) : "")
      + note("Make one: a folder with a plugin.json — see docs/PLUGINS.md. Share it by adding it to plugins/index.json on GitHub.");
  };
  const loadPlugins = async () => {
    const [c, m] = await Promise.all([get("/api/v1/plugins/catalog").catch(e => ({ plugins: [], error: e.message })), get("/api/v1/plugins").catch(() => ({ plugins: [] }))]);
    cat = c; mine = m.plugins || []; if (ctx.alive() && tab === 2) paint();
  };
  /** Look first (what it adds, the exact commands), then install — your phone confirms with a fingerprint. */
  const installPlugin = async (src, reviewed) => {
    let pv; try { pv = await post("/api/v1/plugins/preview", src); } catch (e) { return toast(e.message); }
    const p = pv.plugin;
    const what = [...p.apps.map(a => `<li><b>App:</b> ${esc(a.name)} — ${esc(a.url || ":" + a.port + (a.path || ""))}</li>`),
      ...p.commands.map(c => `<li><b>Tool:</b> ${esc(c.name)}${c.confirm ? " (asks first)" : ""}<code class="cftok" style="margin-top:4px">${esc(c.command)}</code></li>`)].join("");
    const ok = await dialog(`${pv.installed ? "Update" : "Install"} ${p.name}${p.version ? " " + p.version : ""}?`,
      `${p.author ? "By " + p.author + ". " : ""}${reviewed ? "Reviewed by the Nova project." : "Not reviewed by anyone at Nova — check what it runs."}${pv.installed ? ` Replaces version ${pv.installed_version || "?"}.` : ""}`,
      [{ label: "Cancel", value: null }, { label: pv.installed ? "Update" : "Install", color: "var(--blue)", value: "ok" }],
      `<div class="pad"><p class="note" style="margin:0 0 6px">${esc(p.description)}</p><ul class="plwhat">${what || "<li>Nothing to add</li>"}</ul></div>`);
    if (ok !== "ok") return;
    try { await post("/api/v1/plugins/install", { ...src, sha256: pv.sha256 }); toast(`${p.name} installed — its apps and tools are in Apps`); await get("/api/v1/apps").catch(() => {}); }
    catch (e) { toast(e.message); }
    loadPlugins();
  };
  const paint = () => { const el = $("#sres"); if (el) el.innerHTML = body(); };
  const draw = () => {
    ctx.show(`<p class="note" style="font-size:15px;margin:0 26px 10px" id="sintro">${tab === 0 ? "Containers run in Docker, each in its own box with its own folder in /opt." : tab === 1 ? "Programs install straight onto the server with its package manager (apt), no Docker needed." : "Plugins add apps and one-tap tools to Nova."}</p>
      ${segmented(["Containers", "Programs", "Plugins"], tab, "tab")}
      <div class="searchbox glass" style="margin:10px var(--gutter)">${I("search")}<input id="sq" class="sq" value="${esc(q)}" placeholder="${["Search containers", "Search every program", "Search plugins"][tab]}" autocomplete="off" autocapitalize="off" spellcheck="false"></div>
      <div id="sres">${body()}</div>`, { title: "App store", root: true, tabroot: true });
    wireCommon(ctx.root, { onSeg: (_, i) => { tab = i; q = ""; hub = null; found = null; draw(); if (i === 1 && !S.cache["/api/v1/programs"]) get("/api/v1/programs").then(() => ctx.alive() && paint()).catch(() => {}); if (i === 2) loadPlugins(); } });
    const inp = $("#sq");
    inp.oninput = () => { q = inp.value; hub = null; found = null; paint(); clearTimeout(timer);
      if (tab === 1 && q.trim().length >= 2) timer = setTimeout(async () => { const t = q; try { const r = await get(`/api/v1/programs/search?q=${encodeURIComponent(t.trim())}`); if (t === q) { found = r.items || []; paint(); } } catch (e) { toast(e.message); } }, 350); };
  };
  ctx.handlers({
    open: id => ctx.go("store/" + encodeURIComponent(id)),
    hub: async () => { hubBusy = true; paint(); try { hub = (await get(`/api/v1/store/hub?q=${encodeURIComponent(q.trim())}`)).results || []; } catch (e) { toast(e.message); } hubBusy = false; paint(); },
    use: img => { ctx.go("container-new/" + img); },
    plget: id => { const p = cat?.plugins?.find(x => x.id === id); if (p) installPlugin({ url: p.url }, p.reviewed); },
    plurl: async () => { const u = await ask("Install from a link", "https://…/plugin.json or .zip", "", "Look at it", 1000); if (u) installPlugin({ url: u.trim() }, false); },
    plfile: () => { const inp = document.createElement("input"); inp.type = "file"; inp.accept = ".zip,.json,application/zip,application/json";
      inp.onchange = async () => { const f = inp.files?.[0]; if (!f) return; if (f.size > 256 * 1024) return toast("That's too big for a plugin (256 KB at most)");
        const b = new Uint8Array(await f.arrayBuffer()); let bin = ""; for (let i = 0; i < b.length; i += 8192) bin += String.fromCharCode(...b.subarray(i, i + 8192));
        installPlugin({ zip: btoa(bin) }, false); };
      inp.click(); },
    plrm: async id => { const p = mine.find(x => x.id === id); if (!p) return;
      if (!(await confirm(`Remove ${p.name}?`, "Its apps and tools leave Nova. You can install it again any time.", "Remove", "var(--red)"))) return;
      try { await post("/api/v1/plugins/remove", { id }); toast("Removed"); await get("/api/v1/apps").catch(() => {}); } catch (e) { toast(e.message); } loadPlugins(); },
    prog: async pkg => {
      const p = (found || []).find(x => x.pkg === pkg) || S.cache["/api/v1/programs"]?.items?.find(x => x.pkg === pkg) || (pkg === "docker.io" ? { pkg, name: "Docker", installed: false } : null); if (!p) return;
      if (!(await confirm(`${p.installed ? "Remove" : "Install"} ${p.name || pkg}?`, "Your phone will ask you to confirm with your fingerprint.", p.installed ? "Remove" : "Install", p.installed ? "var(--red)" : "var(--blue)"))) return;
      busy = pkg; paint();
      try { const r = await post(`/api/v1/programs/${pkg}/${p.installed ? "remove" : "install"}`);
        if (r?.task) { const j = await waitTask(r.task, t => { busyPct = t.pct; if (ctx.alive()) paint(); });
          toast(j.state === "done" ? `${p.name || pkg} ${p.installed ? "removed" : "installed"}` : `Failed: ${j.error || ""}`); if (j.state === "done" && found) p.installed = !p.installed; }
        await get("/api/v1/programs").catch(() => {}); if (pkg === "docker.io") await get("/api/v1/store").catch(() => {}); }
      catch (e) { toast(e.message); } finally { busy = null; busyPct = 0; if (ctx.alive()) paint(); }
    },
  });
  draw(); try { await get("/api/v1/store"); if (ctx.alive()) paint(); } catch (e) { toast(e.message); }
}
/** Any package from apt: search, install or remove (fingerprint), with apt's own progress. */
async function programSearch(ctx) {
  let q = "", items = null, busy = {}, timer;
  const results = () => items === null ? note(q.length < 2 ? "Type at least two letters — a name like htop, or what it does, like “disk usage”." : "Searching…")
    : !items.length ? note(`Nothing called “${q}”.`)
    : group(items.map(p => row(p.pkg, { sub: p.description, end: busy[p.pkg] != null ? `<span class="muted" style="font-variant-numeric:tabular-nums">${Math.round(busy[p.pkg])}%</span><span class="spinner"></span>`
        : p.essential && p.installed ? `<span class="end">Needed by the server</span>`
        : `<button class="pillbtn press${p.installed ? " red" : ""}" data-act="pk:${esc(p.pkg)}">${p.installed ? "Remove" : "Install"}</button>` })).join(""));
  const paint = () => { const el = $("#pres"); if (el) el.innerHTML = results(); };
  ctx.show(`<div class="searchbox glass">${I("search")}<input id="pq" class="sq" placeholder="Search programs, e.g. htop or “disk usage”" autocomplete="off" autocapitalize="off" spellcheck="false"></div>
    ${note("These come from your system's package manager (apt). Installing or removing asks for your fingerprint; Nova won't remove what the server needs to run.")}<div id="pres">${results()}</div>`, { title: "Find a program" });
  const inp = $("#pq"); inp.focus();
  inp.oninput = () => { q = inp.value.trim(); items = null; paint(); clearTimeout(timer); if (q.length < 2) return;
    timer = setTimeout(async () => { const t = q; try { const r = await get(`/api/v1/programs/search?q=${encodeURIComponent(t)}`); if (t === q) { items = r.items || []; paint(); } } catch (e) { toast(e.message); } }, 350); };
  ctx.handlers({ pk: async pkg => {
    const p = items.find(x => x.pkg === pkg); if (!p) return;
    if (!(await confirm(`${p.installed ? "Remove" : "Install"} ${pkg}?`, p.description + (p.installed ? "" : " — your phone confirms it."), p.installed ? "Remove" : "Install", p.installed ? "var(--red)" : "var(--blue)"))) return;
    busy[pkg] = 0; paint();
    try { const r = await post(`/api/v1/programs/${encodeURIComponent(pkg)}/${p.installed ? "remove" : "install"}`);
      if (r?.task) { const t = await waitTask(r.task, x => { busy[pkg] = x.pct || 0; paint(); });
        if (t.state === "done") { p.installed = !p.installed; toast(`${pkg} ${p.installed ? "installed" : "removed"}`); } else toast(t.error || "It didn't work"); } }
    catch (e) { toast(e.message); } finally { delete busy[pkg]; paint(); }
  } });
  const t = await runningTask(["program-install", "program-remove"]);
  if (t && ctx.alive()) toast(`${t.title} is still running — see the Inbox`);
}
async function storeItem(ctx, id) {
  let task = null;                 // the running install / uninstall (picked up again if you come back)
  const draw = () => {
    const a = (S.cache["/api/v1/store"]?.items || []).find(x => x.id === id);
    if (!a) return ctx.show(note("Loading…"), { title: "App" });
    const url = `http://${serverHost()}:${a.port}${a.path || "/"}`, busy = task?.state === "running";
    ctx.show(`<div class="center" style="padding:18px 0"><span class="appicon" style="width:96px;height:96px;border-radius:30px;margin:0 auto 12px;background:${CAT_COLORS[a.category] || "var(--blue)"}"><b style="font-size:44px;color:#fff">${esc((a.name || "?")[0])}</b></span>
        <div class="muted" style="font-weight:600">${esc(a.category)}</div><div class="muted">${busy ? esc(task.title) : a.installed ? "Installed · " + esc(a.state || "") : "Not installed"}</div></div>
      ${busy ? `<div class="taskbar glass"><div class="tb-top"><b>${esc(task.step || "Working…")}</b><span>${Math.round(task.pct || 0)}%</span></div>${bar((task.pct || 0) / 100)}${task.note ? `<small class="muted">${esc(task.note)}</small>` : ""}<small class="muted">You can leave this page — it keeps going, and the Inbox shows its progress.</small></div>` : ""}
      <div style="display:flex;gap:12px;padding:0 22px">${a.installed
        ? `<a class="btn" style="flex:1" target="_blank" rel="noopener" href="${esc(url)}">Open</a>${isAdmin() ? `<button class="btn" style="flex:1;background:color-mix(in srgb,var(--text) 8%,transparent);color:var(--red)" data-act="un" ${busy ? "disabled" : ""}>Uninstall</button>` : ""}`
        : isAdmin() ? `<button class="btn" style="flex:1" data-act="in" ${a.port_free === false || busy ? "disabled" : ""}>${busy ? "Installing…" : "Install"}</button>` : `<p class="note">An admin can install this.</p>`}</div>
      ${a.port_free === false && !a.installed ? note(`Port ${a.port} is already used by something else on the server.`) : ""}
      ${sec("About")}${group(`<div class="row"><div class="t"><b>${esc(a.description)}</b>${a.notes ? `<small>${esc(a.notes)}</small>` : ""}</div></div>`)}
      ${sec("Details")}${group(row("Image", { sub: a.image }) + row("Port", { sub: String(a.port) }) + (a.installed ? row("Address", { sub: url }) : ""))}`, { title: a.name });
  };
  const follow = async t => {
    task = t; draw();
    const end = await waitTask(t.id, x => { task = x; if (ctx.alive()) draw(); }).catch(e => ({ state: "failed", error: e.message }));
    if (ctx.alive()) toast(end.state === "done" ? `${end.title}: done` : `${end.title || "It"} failed: ${end.error || ""}`);
    task = null; await get("/api/v1/store").catch(() => {}); if (ctx.alive()) draw();
  };
  const run = async verb => {
    const a = (S.cache["/api/v1/store"]?.items || []).find(x => x.id === id);
    if (verb === "uninstall" && !(await confirm(`Uninstall ${a.name}?`, "Its data is kept on the server in /opt/.nova-uninstalled. Your phone confirms it.", "Uninstall"))) return;
    try { const r = await post(`/api/v1/store/${id}/${verb}`); if (r?.task) follow({ id: r.task, state: "running", title: verb === "install" ? `Installing ${a.name}` : `Uninstalling ${a.name}`, pct: 0 }); }
    catch (e) { toast(e.message); }
  };
  ctx.handlers({ in: () => run("install"), un: () => run("uninstall") });
  draw();
  const [, t] = await Promise.all([S.cache["/api/v1/store"] ? null : get("/api/v1/store").catch(e => toast(e.message)), runningTask(["store-install", "store-uninstall"], id)]);
  if (!ctx.alive()) return;
  if (t) follow(t); else draw();
}

// ═════════════════════════════════════ INBOX ════════════════════════════════════
/** What's running right now (installs, backups, updates, drive setup…), with live progress. */
const TASK_ROUTE = t => t.kind.startsWith("store-") ? `store/${t.key}` : t.kind.startsWith("program-") ? "store/programs" : ["updates-check", "apt-upgrade", "containers-update"].includes(t.kind) ? "updates"
  : t.kind === "backup-run" || t.kind === "restore" ? "backups" : t.kind.startsWith("custom-") ? "containers" : `task/${t.id}`;
// A task slides in the first time you see it (never again), and once it ends it shows "Done" for a
// moment and moves on — the server writes it into the Inbox as an ordinary event.
const liveSeen = new Set(), liveHidden = new Set();     // hidden: dismissed here (a running one keeps running on the server)
let liveFresh = new Set();
function liveHtml() {
  const now = Date.now() / 1000, l = (S.cache["/api/v1/tasks"]?.tasks || []).filter(t => !liveHidden.has(t.id) && t.state === "running" || (t.finished && now - t.finished < 6 && liveSeen.has(t.id) && !liveHidden.has(t.id)));
  liveFresh = new Set(l.filter(t => !liveSeen.has(t.id)).map(t => t.id)); l.forEach(t => liveSeen.add(t.id));
  if (!l.length) return "";
  const rowOf = t => `<div class="row click livetask${liveFresh.has(t.id) ? " livein" : ""}${t.state !== "running" ? " liveend" : ""}" data-id="${esc(t.id)}" data-act="go:${esc(TASK_ROUTE(t))}"><div class="t" style="gap:6px"><div class="tb-top"><b>${esc(t.title)}</b>
      <span class="muted" style="font-variant-numeric:tabular-nums">${t.state === "running" ? Math.round(t.pct || 0) + "%" : t.state === "done" ? "Done" : t.state === "stopped" ? "Stopped" : "Failed"}</span></div>
      ${t.state === "running" ? bar((t.pct || 0) / 100) : ""}<small>${esc(t.state === "running" ? (t.note || t.step || "") : t.state === "done" ? "Moved to your Inbox" : (t.error || t.step || ""))}</small></div><button class="rmbtn press" data-act="livehide:${esc(t.id)}" aria-label="Dismiss" title="${t.state === "running" ? "Hide (it keeps running)" : "Dismiss"}">${I("close")}</button></div>`;
  // finished ones swipe away (either way), like any Inbox event
  return sec(l.length > 1 ? `In progress · ${l.length}` : "In progress") + group(l.map(t => t.state === "running" ? rowOf(t)
    : `<div class="swipe" data-key="live:${esc(t.id)}" data-right="Dismiss" data-left="Dismiss"><div class="swbg"></div><div class="swfg">${rowOf(t)}</div></div>`).join(""));
}
/** Where an Inbox event takes you: its category, or what its title is about. (The app has the same rules: Inbox.kt evRoute.) */
export function evRoute(e) {
  const c = (e.category || "").split(":")[0], t = (e.title || "") + " " + (e.detail || "");
  const m = /^Container (?:crashed|unhealthy|ran out of memory|stopped|restarted)[^:]*: (\S+)/i.exec(e.title || "");
  if (m) return "containers/" + encodeURIComponent(m[1]);
  if (c === "container" || /container/i.test(t)) return "containers";
  if (c === "login" || c === "security" || /\b(login|ssh|sudo|fail2ban|passkey|authenticator)\b/i.test(t)) return "security";
  if (/paired|new device|device removed/i.test(t)) return "devices";
  if (c === "device" || /drive|disk|smart|sector|raid|pool|mount|nvme|usb/i.test(t)) return "hardware";
  if (/backup|restore/i.test(t)) return "backups";
  if (/update|upgrade|nova app .* ready|new version/i.test(t)) return "updates";
  if (c === "memory" || c === "service" || /memory|cpu|temperature|swap|service/i.test(t)) return "status";
  return "";
}
const dayOf = e => { const d = new Date(e.t * 1000), now = new Date(), y = new Date(now - 86400000);
  return d.toDateString() === now.toDateString() ? "Today" : d.toDateString() === y.toDateString() ? "Yesterday" : d.toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" }); };
export async function inbox(ctx) {
  let filter = 0, picked = new Set(), last = null;
  const openEv = new Set();                  // events whose full details are shown (click the text)
  // live progress: every 2 s while something runs, else every 15 s
  const liveSwipe = el => swipeable(el, { onRight: hideLive, onLeft: hideLive });
  const hideLive = key => { liveHidden.add(String(key).replace(/^live:/, "")); const el = $("#live"); if (el) { el.innerHTML = el.dataset.h = liveHtml(); liveSwipe(el); } };
  const pollLive = async () => { try { await get("/api/v1/tasks"); const el = $("#live"); if (el) { const h = liveHtml(); if (el.dataset.h !== h) { el.innerHTML = h; el.dataset.h = h; liveSwipe(el); } } } catch {} };
  (async () => { while (ctx.alive()) { await pollLive(); const busy = (S.cache["/api/v1/tasks"]?.tasks || []).some(t => t.state === "running" || (t.finished && Date.now() / 1000 - t.finished < 8)); await sleep(busy ? 2000 : 15000); } })();
  const mouse = matchMedia("(any-pointer: fine)").matches || navigator.maxTouchPoints === 0;     // a mouse or trackpad (touch screens keep swiping)
  const shown = () => (S.cache["/api/v1/events?since=0"]?.events || []).filter(e => filter === 1 ? ["warning", "critical"].includes(e.level) : filter === 2 ? e.level === "critical" : filter === 3 ? e.category === "login" : true);
  const draw = () => {
    const all = S.cache["/api/v1/events?since=0"]?.events, ev = shown();
    picked = new Set([...picked].filter(t => ev.some(e => String(e.t) === t)));
    const days = {}; ev.forEach(e => (days[dayOf(e)] ||= []).push(e));
    const bar = mouse ? `<div class="selbar glass fadeok${picked.size ? " show" : ""}" id="selbar" aria-live="polite"><b id="selcount">${picked.size || 1} selected</b><span class="sp"></span><button class="pillbtn press" data-act="selall">Select all</button>
        <button class="pillbtn press" data-act="selnone">Clear</button><button class="btn" style="height:40px;padding:0 18px;font-size:15px" data-act="archsel">${I("down")} Archive <span id="selnum">${picked.size || 1}</span></button></div>` : "";
    const nudge = "Notification" in window && Notification.permission === "default" && !prefs.browserNotify && !prefs.browserNotifyNudged
      ? `<div class="nudge glass fadeok">${I("bell")}<span>Get new alerts as browser notifications on this computer?</span><button class="pillbtn press" data-act="bnotify">Turn on</button><button class="pillbtn press" data-act="bnudgeno">Not now</button></div>` : "";
    ctx.show(`${nudge}<div id="live">${liveHtml()}</div>${attentionHtml()}${ignoredHtml()}${segmented(["All", "Issues", "Critical", "Logins"], filter, "f")}
      ${ev.length ? `<p class="note" style="margin-top:6px">${mouse ? "Tick events to archive several at once, or use the archive button on a row. Keys: <b>x</b> select · <b>Ctrl+A</b> all · <b>e</b> archive · <b>Shift</b>-click a range." : "Swipe left to archive"} — archived events are kept on the server (Inbox → Archive), for every device.</p>` : ""}
      ${bar}
      ${Object.entries(days).map(([d, l]) => `<div class="sechead dayhead">${sec(d)}${mouse || picked.size ? `<button class="pillbtn press dayall" data-act="pickday:${esc(encodeURIComponent(d))}">${l.every(e => picked.has(String(e.t))) ? "Unselect day" : "Select day"}</button>` : ""}</div>` + group(l.map(e => { const k = String(e.t), on = picked.has(k);
          return `<div class="swipe fadeok${mouse ? " mouse" : ""}${on ? " picked" : ""}" data-key="${e.t}" data-left="Archive" data-right="Archive" tabindex="0"><div class="swbg"></div><div class="swfg"><div class="row" style="align-items:flex-start">
            ${mouse ? `<button class="tick fadeok${on ? " on" : ""}" data-act="pick:${k}" aria-label="Select" aria-pressed="${on}">${I("check")}</button>` : `<span class="dot" style="margin-top:7px;background:${levelColor(e.level)}"></span>`}
            <div class="t evt" data-act="evx:${k}">${mouse ? `<span class="dot" style="display:inline-block;margin-right:8px;background:${levelColor(e.level)}"></span>` : ""}<b style="font-size:16px;display:inline">${esc(cleanTitle(e.title))}</b>${e.detail ? `<small class="evd${openEv.has(k) ? " open" : ""}">${esc(e.detail)}</small>` : ""}</div>
            <span class="end" style="font-size:13px">${hm(e.t)}</span>${evRoute(e) ? `<button class="xbtn" data-act="go:${esc(evRoute(e))}" aria-label="Open" title="Open">${I("back").replace('class="i ', 'style="transform:scaleX(-1)" class="i ')}</button>` : ""}<button class="xbtn" data-act="del:${e.t}" aria-label="Archive" title="Archive">${I("archive")}</button></div></div></div>`; }).join(""))).join("")
        || note(all ? "Nothing here — all quiet." : "Loading…")}`,
      { title: "Inbox", actions: [{ icon: "book", label: "Archive", act: "go:archive" }, { icon: "del", label: "Archive everything", act: "clear" }, { icon: "gear", label: "Notification settings", act: "go:notify" }] });
    wireCommon(ctx.root, { onSeg: (_, i) => { filter = i; picked.clear(); draw(); } });
    swipeable(ctx.root, { onRight: key => key.startsWith("live:") ? hideLive(key) : /^[\d.]+$/.test(key) ? remove([+key]) : dismissAlert(key, draw), onLeft: t => t.startsWith("live:") ? hideLive(t) : /^[\d.]+$/.test(t) ? remove([+t]) : dismissAlert(t, draw) });
    $$(".swipe.mouse", ctx.root).forEach(el => el.onfocus = () => { last = el.dataset.key; });
  };
  // Selection changes update the page in place (so ticks, rows and the bar can animate) instead of redrawing it.
  const updateSel = () => {
    $$(".swipe[data-key]", ctx.root).forEach(el => { const on = picked.has(el.dataset.key); el.classList.toggle("picked", on);
      const t = el.querySelector(".tick"); if (t) { t.classList.toggle("on", on); t.setAttribute("aria-pressed", on); } });
    const bar = $("#selbar"); if (!bar) return;
    if (picked.size) { $("#selcount").textContent = `${picked.size} selected`; $("#selnum").textContent = picked.size; }
    const sa = $("[data-act=selall]", ctx.root); if (sa) sa.textContent = shown().length && shown().every(e => picked.has(String(e.t))) ? "Select none" : "Select all";
    bar.classList.toggle("show", picked.size > 0);
  };
  const remove = async ts => {
    if (!isAdmin()) return viewOnly();
    const c = S.cache["/api/v1/events?since=0"];
    // rows fold away first, then the list is redrawn without them
    const rows = $$(".swipe[data-key]", ctx.root).filter(el => ts.some(t => Math.abs(t - +el.dataset.key) < .000005));
    rows.forEach(el => { el.style.height = el.offsetHeight + "px"; }); void ctx.root.offsetHeight; rows.forEach(el => el.classList.add("gone"));
    ts.forEach(t => picked.delete(String(t))); updateSel();
    if (rows.length && !document.documentElement.classList.contains("reduce")) await sleep(380);
    if (c) c.events = c.events.filter(e => !ts.some(t => Math.abs(t - e.t) < .000005));
    draw();
    try { await post("/api/v1/events/delete", { t: ts }); toast(ts.length > 1 ? `Archived ${ts.length}` : "Archived"); }
    catch (e) { toast(e.message); await get("/api/v1/events?since=0").catch(() => {}); draw(); }
  };
  const toggle = (k, shift) => {
    const ev = shown().map(e => String(e.t));
    if (shift && last && ev.includes(last)) { const [a, b] = [ev.indexOf(last), ev.indexOf(k)].sort((x, y) => x - y); ev.slice(a, b + 1).forEach(t => picked.add(t)); }
    else picked.has(k) ? picked.delete(k) : picked.add(k);
    last = k; updateSel();
  };
  const keyOf = a => { a.pop(); return a.join(":"); };
  ctx.handlers({
    livehide: id => hideLive(id),
    alert: (...a) => alertMenu(keyOf(a), draw), ignore: (...a) => dismissAlert(keyOf(a), draw), del: t => remove([+t]),
    ignfold: () => { ignoredOpen = !ignoredOpen; $(".expand.ign", ctx.root)?.classList.toggle("open", ignoredOpen); },
    unignore: (...a) => undismissAlert(keyOf(a), draw),
    evx: k => { openEv.has(k) ? openEv.delete(k) : openEv.add(k); $(`.swipe[data-key="${k}"] .evd`, ctx.root)?.classList.toggle("open", openEv.has(k)); },
    pick: (k, el) => toggle(k, lastClickShift),
    selall: () => { const all = shown().map(e => String(e.t)); if (all.every(k => picked.has(k))) picked.clear(); else all.forEach(k => picked.add(k)); updateSel(); },
    selnone: () => { picked.clear(); updateSel(); },
    pickday: d => { d = decodeURIComponent(d); const l = shown().filter(e => dayOf(e) === d).map(e => String(e.t)), all = l.every(k => picked.has(k));
      l.forEach(k => all ? picked.delete(k) : picked.add(k)); draw(); },
    archsel: () => remove([...picked].map(Number)),
    bnotify: async () => { prefs.browserNotifyNudged = true; await toggleBrowserNotify(); draw(); }, bnudgeno: () => { prefs.browserNotifyNudged = true; draw(); },
    clear: async () => {
      if (!(await confirm("Archive everything?", filter === 0 ? "The server's inbox is emptied for every device; everything stays in the server's Archive. Active alerts stay until they're fixed or ignored." : `The ${shown().length} event(s) shown leave the inbox; they stay in the server's Archive.`, "Archive", "var(--blue)"))) return;
      if (!isAdmin()) return viewOnly();
      if (filter !== 0) return remove(shown().map(e => e.t));
      const c = S.cache["/api/v1/events?since=0"]; if (c) c.events = []; draw();
      try { await post("/api/v1/events/delete", { all: true }); toast("Archived — see Inbox → Archive"); } catch (e) { toast(e.message); }
    },
  });
  // keyboard: x = select the focused row, e / Delete = archive it (or the selection)
  const onKey = e => {
    if (!ctx.alive()) return removeEventListener("keydown", onKey);
    if (/INPUT|TEXTAREA/.test(document.activeElement?.tagName)) return;
    const row = document.activeElement?.closest?.(".swipe"); const k = row?.dataset.key;
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "a" && mouse) { e.preventDefault(); shown().forEach(ev => picked.add(String(ev.t))); updateSel(); }
    else if (e.key === "Escape" && picked.size) { e.preventDefault(); picked.clear(); updateSel(); }
    else if (e.key === "x" && k) { e.preventDefault(); toggle(k, false); }
    else if ((e.key === "e" || e.key === "Delete") && (picked.size || k)) { e.preventDefault(); remove(picked.size ? [...picked].map(Number) : [+k]); }
    else if ((e.key === "j" || e.key === "ArrowDown" || e.key === "k" || e.key === "ArrowUp") && row) {
      const rows = $$(".swipe", ctx.root), i = rows.indexOf(row) + (e.key === "j" || e.key === "ArrowDown" ? 1 : -1); rows[i]?.focus(); e.preventDefault();
    }
  };
  let lastClickShift = false; const onDown = e => { lastClickShift = e.shiftKey; };
  addEventListener("keydown", onKey); addEventListener("mousedown", onDown, true);
  ctx.onLeave?.(() => { removeEventListener("keydown", onKey); removeEventListener("mousedown", onDown, true); });
  draw();
  ctx.every(15000, async () => { const r = await get("/api/v1/events?since=0"); if (r.events?.[0]) { prefs.lastEventSeen = r.events[0].t; S.unread = 0; } await refresh(); draw(); }, true);
  { const now = () => { if (!ctx.alive()) return removeEventListener("nova:events", now); const r = S.cache["/api/v1/events?since=0"]; if (r?.events?.[0]) { prefs.lastEventSeen = r.events[0].t; S.unread = 0; } draw(); };
    addEventListener("nova:events", now); }
}
export async function archive(ctx) {
  // The archive lives on the server (any device can read it). This browser's own copy fills gaps
  // from before the server kept one, and works when the server can't be reached.
  let filter = 0, local = await archiveAll(), server = [], more = false, loading = true, failed = false;
  const k4 = e => e.t.toFixed(6);
  const merged = () => {
    const by = new Map(), la = new Set(local.archived);
    for (const e of [...server, ...(S.cache["/api/v1/events?since=0"]?.events || []), ...local.events]) {
      const k = k4(e), was = by.get(k), a = !!(e.archived || la.has(k) || la.has(e.t.toFixed(4)) || was?.archived);
      by.set(k, { ...(was || e), archived: a });
    }
    return [...by.values()].sort((x, y) => y.t - x.t);
  };
  const load = async () => {
    loading = true; draw();
    try { const before = server.length ? server.at(-1).t : ""; const r = await get(`/api/v1/archive?limit=300${before ? "&before=" + before : ""}`);
      server = [...server, ...(r.events || [])]; more = !!r.more; failed = false; } catch (e) { failed = true; toast(e.message); }
    loading = false; if (ctx.alive()) draw();
  };
  const draw = () => {
    const all = merged(), ev = all.filter(e => filter === 1 ? e.archived : filter === 2 ? ["warning", "critical"].includes(e.level) : filter === 3 ? e.category === "login" : true);
    const days = {}; ev.slice(0, 3000).forEach(e => (days[new Date(e.t * 1000).toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric", year: "numeric" })] ||= []).push(e));
    ctx.show(`${note(failed ? `Couldn't reach ${serverName()} — showing the copy saved in this browser.` : `Everything ${serverName()} has kept — archived from the Inbox or older than it shows. Stored on the server, so every phone and browser sees the same history.`)}
      ${segmented(["All", "Archived", "Issues", "Logins"], filter, "f")}
      ${Object.entries(days).map(([d, l]) => sec(d) + group(l.map(e => `<div class="row" style="align-items:flex-start"><span class="dot" style="margin-top:7px;background:${levelColor(e.level)};opacity:${e.archived ? .5 : 1}"></span><div class="t"><b style="font-size:16px;${e.archived ? "color:var(--sub)" : ""}">${esc(cleanTitle(e.title))}</b>${e.detail ? `<small>${esc(e.detail)}</small>` : ""}${e.archived ? `<small>Archived</small>` : ""}</div><span class="end" style="font-size:13px">${hm(e.t)}</span>${(e.archived || server.some(x => Math.abs(x.t - e.t) < .000005)) && isAdmin() ? `<button class="xbtn" style="opacity:.8" data-act="restore:${e.t}" title="Back to Inbox" aria-label="Back to Inbox">${I("inbox")}</button>` : ""}</div>`).join(""))).join("")
        || note(loading ? "Loading…" : all.length ? "Nothing matches." : "Nothing yet.")}
      ${more && !loading ? `<div class="center" style="padding:14px"><button class="btn" data-act="older">Load older</button></div>` : loading && all.length ? `<div class="center" style="padding:14px"><div class="spinner" style="margin:auto"></div></div>` : ""}`,
      { title: "Archive", actions: [{ icon: "update", label: "Export", act: "export" }, { icon: "del", label: "Erase this browser's copy", act: "erase" }] });
    wireCommon(ctx.root, { onSeg: (_, i) => { filter = i; draw(); } });
  };
  ctx.handlers({
    restore: async t => {
      t = +t;
      try { await post("/api/v1/events/restore", { t: [t] }); server = server.filter(x => Math.abs(x.t - t) >= .000005);
        await archiveUnmark([t]); local = await archiveAll(); toast("Back in the Inbox"); await get("/api/v1/events?since=0").catch(() => {}); draw(); }
      catch (e) { toast(e.message); }
    },
    older: load,
    export: () => {
      const q = v => `"${String(v || "").replace(/"/g, '""')}"`;
      const csv = "time,level,title,detail,category,archived\n" + merged().map(e => [new Date(e.t * 1000).toISOString(), e.level, q(e.title), q(e.detail), e.category || "", e.archived ? "yes" : ""].join(",")).join("\n");
      const a = document.createElement("a"); a.href = URL.createObjectURL(new Blob([csv], { type: "text/csv" })); a.download = `nova-history-${serverName().replace(/\W+/g, "-")}.csv`; a.click();
    },
    erase: async () => { if (await confirm("Erase this browser's copy?", "Only the copy saved in this browser is deleted. The server's archive — what every device sees — stays.", "Erase")) { await archiveClear(); local = await archiveAll(); draw(); toast("Erased"); } },
  });
  draw(); load();
}
// Browser notifications (this browser only, while a Nova tab is open — in the background is fine)
export const bLevel = () => prefs.browserNotifyLevel || (prefs.browserNotifyAll ? "info" : "warning");
export function browserNotifyHtml() {
  if (!("Notification" in window)) return sec("This browser") + group(row("Browser notifications", { sub: "This browser can't show notifications" }));
  const perm = Notification.permission, on = !!prefs.browserNotify && perm === "granted";
  const sub = perm === "denied" ? "Blocked — allow notifications for this site in the browser's site settings, then turn this on"
    : on ? "On — while a Nova tab is open, even in the background" : "Pop up new alerts on this computer while a Nova tab is open";
  return sec("This browser") + group(switchRow("Browser notifications", sub, on, "bnotify", { blue: on })
    + (on ? row("Which alerts", { sub: { info: "Everything — logins and USB too", warning: "Warnings and critical", critical: "Critical only" }[bLevel()], blue: true, click: "blevel" }) : ""));
}
export async function toggleBrowserNotify() {
  if (!("Notification" in window)) return;
  if (prefs.browserNotify && Notification.permission === "granted") { prefs.browserNotify = false; return; }
  const p = Notification.permission === "default" ? await Notification.requestPermission() : Notification.permission;
  if (p === "granted") { prefs.browserNotify = true; toast("Browser notifications on"); }
  else toast("Notifications are blocked for this site — allow them in the browser's site settings");
}
export async function notify(ctx) {
  const LV = [["info", "Everything", "Includes logins and USB plug/unplug"], ["warning", "Warnings and critical"], ["critical", "Critical only"]];
  const draw = () => {
    const s = S.cache["/api/v1/notify"];
    ctx.show(`<div style="display:flex;justify-content:space-evenly;align-items:center;padding:22px 0;color:var(--text)">${I("computer").replace('class="i ', 'style="width:64px;height:64px" class="i ')}<b style="color:var(--blue);font-size:26px">•••</b>${I("dns").replace('class="i ', 'style="width:64px;height:64px" class="i ')}</div>
      ${note("Alerts land in the Inbox here. Your phone gets them as notifications (Nova app → Notifications). These settings are the server's, shared by every device.")}
      ${browserNotifyHtml()}
      ${s ? `${sec("Discord")}${group((isAdmin() ? row(S.cache["/api/v1/notify/discord"]?.configured ? "Discord channel" : "Set up Discord", { sub: S.cache["/api/v1/notify/discord"]?.configured ? `Sending to ${S.cache["/api/v1/notify/discord"].hint} · click to change or test` : "Get alerts in a Discord channel too — paste its webhook link", blue: !!S.cache["/api/v1/notify/discord"]?.configured, icon: "bell", tint: "#5865f2", click: "discord" }) : "")
          + switchRow("Discord pings", s.discord_paused ? "Paused — alerts still show in the Inbox" : "On", !s.discord_paused, "set:discord_paused")
          + row("Send to Discord", { sub: (LV.find(l => l[0] === s.push_min_level) || [0, "—"])[1], blue: true, click: "level" }))}
        ${sec("What counts")}${group(switchRow("Logins", "Someone signs in to the server", s.push_logins, "set:push_logins", { blue: false }) + switchRow("USB devices", "Plugged in or unplugged", s.push_usb, "set:push_usb", { blue: false }))}` : note("Loading…")}
      ${links([["Inbox", "go:inbox"]])}`, { title: "Notifications" });
  };
  ctx.handlers({ bnotify: async () => { await toggleBrowserNotify(); draw(); }, blevel: async () => { const v = await choose("Pop up on this computer", [["info", "Everything"], ["warning", "Warnings and critical"], ["critical", "Critical only"]], bLevel()); if (v) { prefs.browserNotifyLevel = v; draw(); } } });
  const save = async (k, v) => {
    if (!isAdmin()) return viewOnly();
    const before = S.cache["/api/v1/notify"]; S.cache["/api/v1/notify"] = { ...before, [k]: v }; draw();
    try { S.cache["/api/v1/notify"] = await post("/api/v1/notify", { [k]: v }); } catch (e) { S.cache["/api/v1/notify"] = before; toast(e.message); } draw();
  };
  const discordSetup = async () => {
    const on = S.cache["/api/v1/notify/discord"]?.configured; let url = "";
    const p = dialog("Discord alerts", "In Discord: open the channel's settings → Integrations → Webhooks → New Webhook → Copy Webhook URL, and paste it here. Nova never shows the link again.",
      [{ label: "Cancel", value: null }, ...(on ? [{ label: "Send a test", value: "test" }, { label: "Turn off", color: "var(--red)", value: "off" }] : []), { label: "Save", color: "var(--blue)", value: "save" }],
      `<div class="pad"><input class="field" id="dw" placeholder="https://discord.com/api/webhooks/…" autocomplete="off"></div>`);
    $("#dw").oninput = e => url = e.target.value.trim();
    const v = await p;
    try {
      if (v === "test") { await post("/api/v1/notify/discord/test"); toast("Sent — check the channel"); }
      if (v === "off") { await post("/api/v1/notify/discord", { webhook: "" }); toast("Discord alerts off"); }
      if (v === "save") { if (!url.startsWith("https://")) return toast("Paste the webhook link"); await post("/api/v1/notify/discord", { webhook: url }); await post("/api/v1/notify/discord/test"); toast("Saved — a test message is on its way"); }
    } catch (e) { toast(e.message); }
    await get("/api/v1/notify/discord").catch(() => {}); if (ctx.alive()) draw();
  };
  ctx.handlers({ discord: () => discordSetup(), 
    set: k => { const s = S.cache["/api/v1/notify"]; save(k, k === "discord_paused" ? !s.discord_paused : !s[k]); },
    level: async () => { const v = await choose("Send to Discord", LV.map(([k, l]) => [k, l]), S.cache["/api/v1/notify"]?.push_min_level); if (v) save("push_min_level", v); },
  });
  draw(); try { await Promise.all([get("/api/v1/notify"), isAdmin() ? get("/api/v1/notify/discord").catch(() => {}) : null]); if (ctx.alive()) draw(); } catch (e) { toast(e.message); }
}

// ══════════════════════════════════ QUICK PANEL ═════════════════════════════════
export function fanSliderTile(id = "fantile") {
  const f = S.fan || {}, on = f.on !== false, v = f.brightness ?? 50, fill = on ? v : 0;
  const inner = white => `<div class="in${white ? " white" : ""}"><span class="ti">${I("bulb")}</span><div><b>Fan light</b><small data-fpct>${on ? v + "%" : "Off"}</small></div><span class="r">${isAdmin() ? "Slide to dim" : "View only"}</span></div>`;
  return `<div class="slidetile glass${on ? "" : " off"}" id="${id}" style="--fill:${fill}%"><div class="fill" style="width:${fill}%"></div>${inner(false)}${on ? inner(true) : ""}</div>`;
}
export function wireFanSlider(el, redraw, onHoldGo) {
  if (!el) return;
  if (onHoldGo) onHold(el, onHoldGo);
  if (!isAdmin()) return;
  let drag = null, moved = false, x0 = 0;
  const at = e => Math.min(100, Math.max(1, Math.round((e.clientX - el.getBoundingClientRect().left) / el.clientWidth * 100)));
  el.onpointerdown = e => { drag = at(e); x0 = e.clientX; moved = false; el.setPointerCapture(e.pointerId); };
  el.onpointermove = e => {
    if (drag == null) return; if (Math.abs(e.clientX - x0) > 6) moved = true; if (!moved) return;
    drag = at(e); el.classList.remove("off"); el.style.setProperty("--fill", drag + "%"); $(".fill", el).style.width = drag + "%";
    if (!$(".in.white", el)) el.insertAdjacentHTML("beforeend", $(".in", el).outerHTML.replace('class="in"', 'class="in white"'));
    $$("[data-fpct]", el).forEach(x => x.textContent = drag + "%");
  };
  el.onpointerup = async () => {
    if (drag == null) return; const v = drag; drag = null;
    const patch = moved ? { on: true, brightness: v } : { on: S.fan?.on === false };
    try { const p = changeFan(patch); redraw(); await p; } catch (e) { toast(e.message); } redraw();
  };
  el.onpointercancel = () => { drag = null; redraw(); };
}
export async function quick(ctx) {
  let busy = null;
  const need = { fan: "lighting", dim: "lighting", bright: "lighting", status_light: "lighting", lighting: "lighting", backup: "backup", store: "store", status: "monitor" };
  const state = id => {
    const f = S.fan || {}, b = S.cache["/api/v1/backup"], n = S.cache["/api/v1/notify"], running = busy === "backup" || b?.running;
    switch (id) {
      case "backup": return [running ? (b ? backupLine(b).replace(/^Backing up /, "") : "Starting…") : `Last: ${b?.time ? b.time.slice(5, 16) : "—"}`, running];
      case "dim": return [null, f.on !== false && f.brightness === 5];
      case "bright": return [null, f.on !== false && f.brightness === 60];
      case "status_light": return [f.status_light ? "On" : "Off", !!f.status_light];
      case "discord": return n ? [n.discord_paused ? "Paused" : "On", !n.discord_paused] : [null, false];
      default: return [busy === id ? "Working…" : null, busy === id];
    }
  };
  const draw = () => {
    const ids = prefs.quick.filter(id => quickDef(id) && (!need[id] || has(need[id])));
    const b = S.cache["/api/v1/backup"];
    ctx.show(`<div class="tiles">${ids.includes("fan") ? fanSliderTile() : ""}${ids.filter(i => i !== "fan").map(id => { const d = quickDef(id), [st, on] = state(id);
        return `<button class="tile glass press${on ? " on" : ""}" data-act="run:${esc(id)}" data-hold="${esc(id)}"><span class="ti">${I(d.icon)}</span><span class="tt"><b>${esc(d.label)}</b>${st ? `<small>${esc(st)}</small>` : ""}</span></button>`; }).join("")}</div>
      <p class="note" style="margin-left:24px">Tip: hold a tile for its settings.${ids.length ? "" : " No quick actions — tap the pencil to add some."}</p>
      ${(busy === "backup" || b?.running) && b ? sec("Backup") + group(`<div style="padding:16px 22px">${bar(b.progress?.phase === "copying" ? b.progress.pct / 100 : .1)}<p class="muted" style="font-size:13px;margin:8px 0 0">${esc(backupLine(b))}</p></div>`) : ""}
      ${isAdmin() ? sec("Power") + group(POWER.map(([id, label, sub, icon, tint]) => row(label, { sub, icon, tint, click: "power:" + id })).join("")) : ""}`,
      { title: "Quick panel", actions: [{ icon: "edit", label: "Edit", act: "go:edit-quick" }] });
    wireFanSlider($("#fantile"), draw, () => ctx.go("lighting"));
    $$("[data-hold]").forEach(t => onHold(t, () => { const r = { fan: "lighting", dim: "lighting", bright: "lighting", status_light: "lighting", lighting: "lighting", backup: "status", freeram: "status", status: "status", discord: "notify", inbox: "notify", containers: "containers", storage: "hardware" }[t.dataset.hold] || (t.dataset.hold.startsWith("restart:") ? "containers/" + t.dataset.hold.slice(8) : null); if (r) ctx.go(r); }));
  };
  ctx.handlers({
    run: async id => {
      const opens = { status: "status", dashboard: "dashboard", containers: "containers", lighting: "lighting", inbox: "inbox", storage: "hardware", store: "store" };
      if (opens[id]) return ctx.go(opens[id]);
      if (!isAdmin()) return viewOnly();
      try {
        if (id === "backup") { if (state("backup")[1]) return; busy = id; draw(); await post("/api/v1/actions/backup"); await get("/api/v1/backup"); toast("Backup started"); }
        else if (id === "freeup") { ctx.go("freeup"); return; }
        else if (id === "freeram") { busy = id; draw(); const r = await post("/api/v1/actions/free-ram"); toast(`Freed ${r.freed_mb} MB · ${r.available_mb} MB available`); }
        else if (id === "dim" || id === "bright") { const p = changeFan({ on: true, brightness: id === "dim" ? 5 : 60 }); draw(); await p; }
        else if (id === "status_light") { const p = changeFan({ status_light: !S.fan?.status_light }); draw(); await p; }
        else if (id === "discord") { const n = S.cache["/api/v1/notify"]; S.cache["/api/v1/notify"] = await post("/api/v1/notify", { discord_paused: !n?.discord_paused }); }
        else if (id.startsWith("restart:")) { const n = id.slice(8); if (!(await confirm(`Restart ${n}?`, "Your phone will ask you to confirm with your fingerprint.", "Restart"))) return; busy = id; draw(); await post(`/api/v1/containers/${encodeURIComponent(n)}/restart`); toast(`${n} restarted`); }
      } catch (e) { toast(e.message); } finally { busy = null; if (ctx.alive()) draw(); }
    },
    power: what => powerDo(what),
  });
  draw();
  get("/api/v1/notify").then(() => ctx.alive() && draw()).catch(() => {});
  ctx.every(3000, async () => { await get("/api/v1/backup"); if (!$("#fantile")?.hasPointerCapture?.()) draw(); }, true);
}
export async function editQuick(ctx) {
  const draw = () => {
    const ids = prefs.quick, more = QUICK.filter(q => !ids.includes(q.id));
    ctx.show(`${sec("In the panel · hold and drag to reorder")}
      <div class="group glass rl" id="rl">${ids.length ? ids.map(id => { const d = quickDef(id); return d ? `<div class="row" data-key="${esc(id)}"><span class="handle">${I("drag")}</span><span style="color:var(--blue);display:flex">${I(d.icon)}</span><div class="t"><b>${esc(d.label)}</b></div><button data-noreorder data-act="rm:${esc(id)}" style="color:var(--red)">${I("remc")}</button></div>` : ""; }).join("") : `<div class="row"><div class="t muted">Nothing yet — add some below.</div></div>`}</div>
      ${sec("Add")}${group(more.map(q => row(q.label, { sub: q.hint, icon: q.icon, click: "add:" + q.id, end: `<span style="color:var(--green)">${I("addc")}</span>` })).join("") + row("Restart a container…", { sub: "Pick one — your phone confirms each restart", icon: "restart", click: "addc", end: `<span style="color:var(--green)">${I("addc")}</span>` }))}
      ${links([["Reset to the default tiles", "reset"]])}`, { title: "Edit quick panel" });
    reorderable($("#rl"), keys => { prefs.quick = keys; draw(); });
  };
  ctx.handlers({
    rm: id => { prefs.quick = prefs.quick.filter(x => x !== id); draw(); },
    add: id => { prefs.quick = [...prefs.quick, id]; draw(); },
    addc: async () => {
      const cs = (S.cache["/api/v1/containers"] || await get("/api/v1/containers").catch(() => ({}))).containers || [];
      const v = await choose("Restart which container?", cs.map(c => c.name).sort().map(n => [n, n]), null);
      if (v && !prefs.quick.includes("restart:" + v)) { prefs.quick = [...prefs.quick, "restart:" + v]; draw(); }
    },
    reset: () => { localStorage.removeItem("nova.quick"); draw(); },
  });
  draw();
}

// ════════════════════════════════════ LIGHTING ══════════════════════════════════
const periodMs = s => 10000 * Math.pow(.02, (Math.max(1, Math.min(100, s)) - 1) / 99);
const speedLabel = s => { const p = periodMs(s) / 1000; return (p >= 1 ? p.toFixed(1) : p.toFixed(2)) + " s per cycle"; };
const EFFECTS2 = [["static", "Static", "One steady color"], ["pulse", "Pulse", "Breathes in and out"], ["blink", "Blink", "Flashes on and off"], ["cycle", "Color cycle", "Fades through colors"],
  ["wave", "Wave", "Colors chase around the ring"], ["comet", "Comet", "A bright head with a fading tail"], ["scanner", "Scanner", "A light sweeping back and forth"],
  ["twinkle", "Twinkle", "LEDs fade in and out at random"], ["fire", "Fire", "A flickering flame"], ["breathe", "Breathe", "Slow breaths, one color after another"],
  ["random", "Random", "Surprise me"], ["gradient", "Gradient", "Blends two colors across the ring"]];
const SOFT = new Set(["wave", "comet", "scanner", "twinkle", "fire", "breathe"]), PALETTE_FX = new Set(["static", "pulse", "blink", "gradient"]), ANIM = new Set(["pulse", "blink", "cycle", "wave", "random", "comet", "scanner", "twinkle", "fire", "breathe"]);
const PALS = [["Ocean", ["#001a66", "#0050ff", "#00c7be", "#80f0ff"]], ["Lava", ["#200000", "#ff2000", "#ff8000", "#ffd060"]], ["Forest", ["#003300", "#20a040", "#80d000", "#004020"]],
  ["Sunset", ["#ff5e3a", "#ff2a68", "#bf5af2", "#5e5ce6"]], ["Party", ["#ff2d55", "#ffcc00", "#34c759", "#3e91ff", "#bf5af2"]], ["Aurora", ["#00ff88", "#00c7be", "#5e5ce6", "#bf5af2"]],
  ["Ice", ["#ffffff", "#80d8ff", "#3e91ff", "#0040a0"]], ["Candy", ["#ff6b9a", "#ffffff", "#bf5af2", "#80d8ff"]], ["Fire", ["#200000", "#ff1800", "#ff6000", "#ffb000", "#fff0a0"]]];
const swatchRow = (sel, key, dis, noChange) => `<div class="swatches">${noChange ? `<button class="swatch${sel ? "" : " on"}" style="background:var(--card);color:${sel ? "var(--sub)" : "var(--blue)"}" data-act="${dis ? "" : `col:${key}:none`}" aria-label="No change">${I("block")}</button>` : ""}${SWATCHES.map(h => `<button class="swatch${h.toLowerCase() === (sel || "").toLowerCase() ? " on" : ""}" style="background:${h};color:${h === "#ffffff" ? "#000" : "#fff"}" data-act="${dis ? "" : `col:${key}:${h}`}" aria-label="${h}">${h.toLowerCase() === (sel || "").toLowerCase() ? I("check") : ""}</button>`).join("")}
  <button class="swatch custom" aria-label="Custom color" data-act="${dis ? "" : `pick:${key}`}" style="${sel && !SWATCHES.includes((sel || "").toLowerCase()) ? `background:${sel};border:3px solid var(--blue)` : ""}">${I("palette")}</button></div>`;
const lookOf = f => Object.fromEntries(["on", "effect", "color", "color2", "brightness", "speed", "rainbow", "palette"].filter(k => f?.[k] !== undefined).map(k => [k, f[k]]));
const palBg = cols => cols.length > 1 ? `linear-gradient(90deg,${cols.join(",")})` : cols[0];
const chip = (label, act, on, icon) => `<button class="chip" style="font-family:inherit;font-size:14px;display:inline-flex;align-items:center;gap:6px;${on ? "background:var(--blue);color:#fff" : ""}" data-act="${act}">${icon ? I("block") : ""}${esc(label)}</button>`;
// Which light the Lighting page is showing ("main" = the one the rest of Nova calls the fan light).
let litCur = "main";
const zoneOf = id => (S.lights?.zones || []).find(z => z.id === id);
/** The status light on a Home Assistant light: connect, pick the light, switch it on. */
export async function haLight(ctx) {
  let found = null, busy = false;
  const v = { url: "", token: "", entity: "", name: "", enabled: true, loaded: false };
  const draw = () => {
    const c = S.cache["/api/v1/lights/ha"];
    if (c && !v.loaded) Object.assign(v, { loaded: true, url: c.url || "http://homeassistant.local:8123", entity: c.entity, name: c.name, enabled: c.has_token ? c.enabled : true });
    const lvName = { ok: "your own setting", warning: "amber (a warning)", critical: "red (critical)" };
    ctx.show(note("When the server needs attention, this light turns amber for a warning and red for something critical. When it's fine again, it goes back exactly to how it was. Nova only changes it when the status changes, so your own automations and switches keep working.")
      + (c?.has_token && c.entity ? group(row(c.enabled ? `Showing ${lvName[c.shown] || "your own setting"}` : "Off", { sub: `${c.name || c.entity}${c.error ? " · " + c.error : ""}`, icon: c.error ? "err" : "bulb", tint: c.error ? "var(--red)" : c.enabled ? "#41bdf5" : "var(--sub)" })) : "")
      + sec("Connect") + group(`<div style="padding:16px;display:grid;gap:10px">
          <input class="field" id="hu" placeholder="Home Assistant's address, like http://homeassistant.local:8123" value="${esc(v.url)}" autocomplete="off">
          <input class="field" id="ht" type="password" placeholder="${c?.has_token ? "Access token (leave empty to keep it)" : "Long-lived access token"}" value="${esc(v.token)}" autocomplete="off">
          <small class="muted">In Home Assistant: your profile (bottom left) → Security → Long-lived access tokens → Create token.</small>
          <div><button class="pillbtn press" data-act="find" ${busy ? "disabled" : ""}>${busy ? "Looking…" : "Find its lights"}</button></div></div>`)
      + (found ? sec("Which light") + group(found.length ? found.map((l, i) => row(l.name, { sub: `${l.entity} · ${l.state}`, icon: "bulb", tint: "#41bdf5", end: radio(v.entity === l.entity), click: `pick:${i}` })).join("") : row("No lights in Home Assistant", { dis: true }))
        : v.entity ? sec("Which light") + group(row(v.name || v.entity, { sub: v.entity, icon: "bulb", tint: "#41bdf5", end: radio(true) })) : "")
      + group(switchRow("Show the server's status", "Amber for warnings, red for critical", v.enabled, "en"))
      + `<div style="display:flex;gap:12px;padding:18px 22px"><button class="btn" style="flex:1" data-act="save" ${v.entity ? "" : "disabled"}>Save</button></div>`
      + (c?.has_token ? links([["Disconnect Home Assistant", "rm"]]) : ""), { title: "Home Assistant light" });
    for (const [id, k] of [["hu", "url"], ["ht", "token"]]) { const el = $("#" + id); if (el) el.oninput = e => { v[k] = e.target.value.trim(); }; }
  };
  ctx.handlers({
    find: async () => { busy = true; draw(); try { found = (await post("/api/v1/lights/ha/lights", { url: v.url, token: v.token })).lights; } catch (e) { toast(e.message); } finally { busy = false; if (ctx.alive()) draw(); } },
    pick: i => { const l = found[+i]; v.entity = l.entity; v.name = l.name; draw(); },
    en: () => { v.enabled = !v.enabled; draw(); },
    save: async () => { try { await post("/api/v1/lights/ha", { url: v.url, ...(v.token ? { token: v.token } : {}), entity: v.entity, name: v.name, enabled: v.enabled }); v.token = ""; await get("/api/v1/lights/ha"); toast("Saved"); if (ctx.alive()) draw(); } catch (e) { toast(e.message); } },
    rm: async () => { if (await confirm("Disconnect Home Assistant?", "The light goes back to how it was, and Nova forgets the token.", "Disconnect")) { try { await del("/api/v1/lights/ha"); await get("/api/v1/lights/ha"); v.loaded = false; found = null; draw(); } catch (e) { toast(e.message); } } },
  });
  draw(); try { await get("/api/v1/lights/ha"); } catch {} if (ctx.alive()) draw();
}
export async function lighting(ctx) {
  const draw = () => {
    const zs = S.lights?.zones || [], z = litCur === "main" ? null : zoneOf(litCur);
    if (litCur !== "main" && !z) litCur = "main";
    const main = !z, rgbOnly = (S.lights?.targets || []).find(t => t.id === (z || zs[0] || {}).target)?.kind === "rgb";
    const f = z || S.fan || {}, lit = f.on !== false, on = lit && isAdmin(), eff = f.effect || "static", nsch = (f.schedules || []).length, pal = f.palette || [], rb = f.rainbow !== false;
    const mainName = zs[0]?.name || "Fan", shape = (z || zs[0] || {}).shape || "fan";
    const presets = f.presets || [], soft = SOFT.has(eff) || eff === "cycle", mode = soft ? (pal.length >= 2 && !(eff !== "fire" && rb) ? 2 : rb ? 0 : 1) : -1;
    const pfx = !soft && PALETTE_FX.has(eff), pmode = pal.length >= 2 ? 1 : 0;      // static, pulse, flash, gradient: color(s) or a palette
    const palEditor = () => `<div style="padding:14px 20px"><div style="display:flex;flex-wrap:wrap;gap:12px">${pal.map((c, i) => `<button class="swatch" style="width:44px;height:44px;background:${c}" data-act="${on ? "pal:" + i : ""}" data-pi="${i}" aria-label="${c}"></button>`).join("")}
                ${pal.length < 8 && on ? `<button class="swatch" style="width:44px;height:44px;border:1.5px dashed var(--sub)" data-act="paladd">${I("add")}</button>` : ""}</div>
              <p class="muted" style="font-size:12px;margin:8px 0 10px">Tap a color to change it, hold (or right-click) to remove it.</p>
              <div style="display:flex;gap:8px;overflow-x:auto;scrollbar-width:none">${PALS.map(([n, c]) => `<button style="display:flex;flex-direction:column;align-items:center;gap:4px;padding:4px" data-act="${on ? "palset:" + n : ""}"><span style="width:64px;height:22px;border-radius:11px;background:${palBg(c)}"></span><small class="muted">${n}</small></button>`).join("")}</div></div>`;
    const chips = S.lights ? `<div class="chips litchips" style="padding:0 var(--gutter);margin-bottom:6px">${zs.map(x => chip(x.name, "lit:" + x.id, x.id === litCur)).join("")}
        ${isAdmin() ? `<button class="chip press" data-act="go:lights" aria-label="Your lights">${I("tune")} Your lights</button>` : ""}</div>` : "";
    ctx.show(`${chips}${main && shape === "fan" ? `<canvas class="fanhero" id="fh"></canvas>` : ""}
      ${!main ? note(`Presets are for ${esc(mainName)}; a schedule or the status light can be for any light (choose in Schedules / Status light).`) : ""}
      ${main && f.status_override ? `<p class="note" style="color:var(--amber);font-size:14px">Showing server status right now — your setting comes back when it's resolved.</p>` : ""}
      ${!isAdmin() ? note("View-only access — an admin can change the lighting.") : ""}
      ${group(switchRow(main && shape === "fan" && zs.length < 2 ? "Fan light" : esc(f.name || mainName), lit ? "On" : "Off", lit, isAdmin() ? "set:on" : "", { dis: !isAdmin() }))}
      ${group(slider("Brightness", "brightness", f.brightness ?? 50, 0, 100, (f.brightness ?? 50) + "%", !on))}
      ${main ? sec("Presets") : ""}<div class="chips" style="padding:0 var(--gutter);${main ? "" : "display:none"}">${presets.map(p => { const st = p.set || {}, pc = (st.palette || []).length > 1 ? st.palette : [st.color || "#3e91ff"];
          return `<button class="tile glass press" style="height:48px;padding:0 14px;width:auto" data-act="preset:${esc(p.id)}" data-pid="${esc(p.id)}"><span style="width:22px;height:22px;border-radius:50%;background:${st.rainbow && (SOFT.has(st.effect) || st.effect === "cycle") ? "conic-gradient(red,yellow,lime,cyan,blue,magenta,red)" : palBg(pc)}"></span><b>${esc(p.name)}</b></button>`; }).join("")}
        ${isAdmin() ? `<button class="tile glass press" style="height:48px;padding:0 14px;width:auto;color:var(--blue)" data-act="savepreset">${I("add")}<b>Save current</b></button>` : ""}</div>
      ${!main ? "" : presets.length ? note("Hold a preset (or right-click) to update, rename or delete it.") : note("Save the look you have now to switch back to it in one tap — or to use it in a schedule.")}
      ${rgbOnly ? note("A 12 V RGB header lights every LED in the same color, so the effects that move along the LEDs aren't offered.") : ""}
      ${sec("Effect")}${group(EFFECTS2.filter(e => !rgbOnly || (S.lights?.rgb_effects || []).includes(e[0])).map(([k, l, d]) => row(l, { sub: d, blue: eff === k, end: radio(eff === k), click: on ? "set:effect:" + k : "", dis: !on })).join(""))}
      ${sec("Color")}${group(soft ? segmented(eff === "fire" ? ["Flame", "One color", "Palette"] : ["Rainbow", "One color", "Palette"], mode, "cmode")
          + (mode === 2 ? palEditor() : mode === 1 ? swatchRow(f.color, "color", !on) : eff === "fire" ? `<p class="note" style="margin:14px 22px">A warm flame (dark red → orange → yellow). Pick One color for a flame in your color, or Palette for your own.</p>` : "")
        : pfx ? segmented([eff === "gradient" ? "Two colors" : "One color", "Palette"], pmode, "pmode")
          + (pmode === 1 ? palEditor() : swatchRow(f.color, "color", !on) + (eff === "gradient" ? `<div class="sec" style="margin:4px 22px 0">Blend into</div>${swatchRow(f.color2, "color2", !on)}` : ""))
        : swatchRow(f.color, "color", !on))}
      ${ANIM.has(eff) ? group(slider("Speed", "speed", f.speed ?? 50, 1, 100, speedLabel(f.speed ?? 50), !on) + `<div style="display:flex;justify-content:space-between;padding:0 22px 12px" class="muted"><small>Slower</small><small>Faster</small></div>`) : ""}
      ${!rgbOnly && (eff === "gradient" || soft || (pfx && pmode === 1)) ? group(slider(shape === "strip" ? "LEDs on the strip" : shape === "fan" ? "LEDs on the fan" : "LEDs", "led_count", f.led_count ?? 12, 4, 40, String(f.led_count ?? 12), !on)
        + `<p class="note" style="margin:0 22px 14px">${shape === "fan" ? "Match this to your fan so the effect fits the ring exactly (most 120 mm fans have 8–18)." : "Count the LEDs (or check the box) so effects fit the whole length."}</p>`
        + (shape !== "fan" ? "" : switchRow("Picture spins the other way", "If the effect here goes round the opposite way to your fan", localStorage.getItem("nova.fanReverse") === "true", "rev", { blue: false }))) : ""}
      ${main ? sec("Automation") + group(switchRow("Status light", "Turns amber for warnings and pulses red for critical alerts, then goes back to your color", !!f.status_light, isAdmin() ? "set:status_light" : "", { blue: false, dis: !isAdmin() })
        + row("Home Assistant light", { sub: S.cache["/api/v1/lights/ha"]?.enabled ? `${S.cache["/api/v1/lights/ha"].name || S.cache["/api/v1/lights/ha"].entity} shows the server's status` : "A light in Home Assistant (like a Govee strip) shows the server's status too", icon: "bulb", tint: "#41bdf5", click: "go:ha-light" })
        + row("Schedules", { sub: f.schedules_paused ? "Paused" : nsch ? `${nsch} schedule${nsch > 1 ? "s" : ""}` : "Wake up gently, dim at sunset, off while you sleep…", blue: nsch > 0, click: "go:schedules" })) : ""}
      ${links([["Your lights", "go:lights"], ["Notifications", "go:notify"], ["Storage & hardware", "go:hardware"]])}`, { title: "Lighting" });
    if ($("#fh")) animate($("#fh"), "fan");
    wireCommon(ctx.root, {
      onRangeInput: (k, v) => { const l = $(`[data-lbl="${k}"]`); if (l) l.textContent = k === "speed" ? speedLabel(v) : k === "brightness" ? v + "%" : String(v); if (k === "brightness" && litCur === "main") S.fan = { ...S.fan, brightness: v }; },
      onRange: (k, v) => set({ [k]: v }),
      onSeg: (key, m) => { const f2 = look();
        if (key === "pmode") return set(m === 0 ? { palette: [] } : { rainbow: false, palette: (f2.palette || []).length >= 2 ? f2.palette : [f2.color || "#3e91ff", f2.color2 || "#bf5af2"] });
        if (m === 0) set(f2.effect === "fire" ? { palette: [], rainbow: true } : { rainbow: true });
        else if (m === 1) set({ rainbow: false, palette: [] });
        else set({ rainbow: false, palette: (f2.palette || []).length >= 2 ? f2.palette : [f2.color || "#3e91ff", f2.color2 || "#bf5af2"] }); },
    });
    $$("[data-pid]").forEach(b => onHold(b, () => presetMenu(b.dataset.pid)));
    $$("[data-pi]").forEach(b => onHold(b, () => { const p = [...(S.fan.palette || [])]; if (p.length > 2) { p.splice(+b.dataset.pi, 1); set({ palette: p }); } }));
  };
  const setZone = async (id, patch) => {
    if (!isAdmin()) throw new Error("This browser has view-only access");
    const z = zoneOf(id), before = { ...z }; Object.assign(z, patch); draw();
    try { const { led_count, ...look } = patch;
      if (led_count != null) S.lights = await post(`/api/v1/lights/${id}/setup`, { led_count });
      if (Object.keys(look).length) S.lights = await post(`/api/v1/lights/${id}`, look); }
    catch (e) { Object.assign(z, before); throw e; }
  };
  const set = async patch => { try { if (litCur !== "main") await setZone(litCur, patch); else { const p = changeFan(patch); draw(); await p; } } catch (e) { toast(e.message); } if (ctx.alive()) draw(); };
  const savePresets = list => set({ presets: list });
  const presetMenu = async id => {
    if (!isAdmin()) return viewOnly();
    const list = S.fan.presets || [], p = list.find(x => x.id === id); if (!p) return;
    const v = await choose(p.name, [["update", "Update with the current look"], ["rename", "Rename"], ["delete", "Delete"]], null);
    if (v === "update") { savePresets(list.map(x => x.id === id ? { ...x, set: lookOf(S.fan) } : x)); toast("Updated"); }
    if (v === "rename") { const n = await ask("Rename preset", "Name", p.name); if (n) savePresets(list.map(x => x.id === id ? { ...x, name: n } : x)); }
    if (v === "delete") savePresets(list.filter(x => x.id !== id));
  };
  const look = () => (litCur !== "main" && zoneOf(litCur)) || S.fan || {};
  ctx.handlers({
    lit: id => { litCur = id; draw(); },
    set: (k, v) => { if (typeof v !== "string") v = undefined;              // (the router adds the clicked element last)
      const f = look(); set({ [k]: v !== undefined ? v : k === "on" ? f.on === false : k === "rainbow" ? f.rainbow === false : !f[k] }); },
    col: (k, h) => set({ [k]: h }),
    pick: async k => { const c = await colorPicker(look()[k] || "#3e91ff"); if (c) set({ [k]: c }); },
    pal: async i => { const p = [...(look().palette || [])], c = await colorPicker(p[+i]); if (c) { p[+i] = c; set({ palette: p, rainbow: false }); } },
    paladd: () => { const p = [...(look().palette || [])]; p.push(p.at(-1) || "#ffffff"); set({ palette: p, rainbow: false }); },
    palset: n => set({ palette: PALS.find(x => x[0] === n)[1], rainbow: false }),
    preset: id => { const p = (S.fan.presets || []).find(x => x.id === id); if (!p) return; if (!isAdmin()) return viewOnly(); set({ ...p.set, on: p.set.on !== false }); toast(`${p.name} on`); },
    savepreset: async () => { const n = await ask("Save as a preset", "e.g. Movie night", ""); if (n) savePresets([...(S.fan.presets || []), { name: n, set: lookOf(S.fan) }]); },
    rev: () => { try { localStorage.setItem("nova.fanReverse", String(localStorage.getItem("nova.fanReverse") !== "true")); } catch {} draw(); },
  });
  draw(); try { S.fan = await get("/api/v1/fan"); if (ctx.alive()) draw(); } catch (e) { toast(e.message); }
  try { S.lights = await get("/api/v1/lights"); if (ctx.alive()) draw(); } catch {}      // older servers: just the one light
  get("/api/v1/lights/ha").then(() => ctx.alive() && draw()).catch(() => {});
}
// Lighting → Your lights: what's plugged in where (a fan ring, a strip, a 12 V header…), each with its own look.
const SHAPE_NAME = { fan: "Fan", strip: "LED strip", other: "Something else" };
export async function lights(ctx) {
  const tgt = id => (S.lights?.targets || []).find(t => t.id === id) || { label: id, tested: false };
  const draw = () => {
    const L = S.lights;
    if (!L) return ctx.show(note("Loading…"), { title: "Your lights" });
    ctx.show(`${note("Each light plugged into the server, with its own effect and color. Add one for every fan, strip or header you light up.")}
      ${group(L.zones.map((z, i) => row(z.name, { sub: `${SHAPE_NAME[z.shape] || "Light"} · ${tgt(z.target).label}${tgt(z.target).tested ? "" : " · untested"}${tgt(z.target).kind === "rgb" ? "" : ` · ${z.led_count} LEDs`}${i === 0 ? " · main" : ""}`,
          icon: z.shape === "strip" ? "viewday" : z.shape === "fan" ? "sync" : "bulb", click: isAdmin() ? "edit:" + z.id : "lit:" + z.id })).join("")
        + (isAdmin() && L.targets.some(t => !t.used_by) ? row("Add a light", { icon: "add", blue: true, click: "add" }) : ""))}
      ${note("“Untested” headers follow the controller's documented protocol but haven't been tried on real hardware yet — if one doesn't light up, tell us which board you have. The main light is the one Presets, Schedules, the status light and the Home picture use.")}`,
      { title: "Your lights" });
  };
  const post2 = async (path, body, method = "POST") => { try { S.lights = method === "DELETE" ? await del(path) : await post(path, body); } catch (e) { toast(e.message); } draw(); };
  const pickTarget = async (cur, self) => {
    const opts = S.lights.targets.filter(t => !t.used_by || t.id === cur || t.used_by === self).map(t => [t.id, t.label + (t.tested ? "" : " (untested)") + (t.kind === "rgb" ? " — one color at a time" : "")]);
    return opts.length ? choose("Where is it plugged in?", opts, cur) : (toast("Every header is in use"), null);
  };
  ctx.handlers({
    lit: id => { litCur = id; ctx.go("lighting"); },
    add: async () => {
      const target = await pickTarget(null); if (!target) return;
      const shape = await choose("What is it?", [["fan", "A fan (a ring of LEDs)"], ["strip", "An LED strip"], ["other", "Something else"]], "strip"); if (!shape) return;
      const name = await ask("Name it", "e.g. Case strip, Rear fan", shape === "fan" ? "Fan 2" : shape === "strip" ? "LED strip" : "Light"); if (!name) return;
      let led_count = shape === "strip" ? 30 : 12;
      if (tgt(target).kind !== "rgb") { const n = await ask("How many LEDs?", shape === "fan" ? "Most 120 mm fans have 8–18" : "Count them, or check the box", String(led_count)); if (n === null) return; led_count = Math.max(1, Math.min(120, parseInt(n) || led_count)); }
      await post2("/api/v1/lights", { name, target, shape, led_count }); toast(`${name} added`);
    },
    edit: async id => {
      const z = S.lights.zones.find(x => x.id === id); if (!z) return;
      const v = await choose(z.name, [["look", "Change its look"], ["rename", "Rename"], ["where", "Where it's plugged in"], ["what", "What it is"], ...(tgt(z.target).kind !== "rgb" ? [["leds", "Number of LEDs"]] : []), ...(id !== "main" ? [["rm", "Remove"]] : [])], null);
      if (v === "look") { litCur = id; return ctx.go("lighting"); }
      if (v === "rename") { const n = await ask("Rename", "Name", z.name); if (n) post2(`/api/v1/lights/${id}/setup`, { name: n }); }
      if (v === "where") { const t = await pickTarget(z.target, z.name); if (t && t !== z.target) post2(`/api/v1/lights/${id}/setup`, { target: t }); }
      if (v === "what") { const sh = await choose("What is it?", [["fan", "A fan (a ring of LEDs)"], ["strip", "An LED strip"], ["other", "Something else"]], z.shape); if (sh) post2(`/api/v1/lights/${id}/setup`, { shape: sh }); }
      if (v === "leds") { const n = await ask("How many LEDs?", "1–120", String(z.led_count)); if (n) post2(`/api/v1/lights/${id}/setup`, { led_count: Math.max(1, Math.min(120, parseInt(n) || z.led_count)) }); }
      if (v === "rm" && await confirm(`Remove ${z.name}?`, "It's switched off and leaves the list. Nothing on the server changes otherwise.", "Remove")) { if (litCur === id) litCur = "main"; post2(`/api/v1/lights/${id}`, null, "DELETE"); }
    },
  });
  draw(); try { S.lights = await get("/api/v1/lights"); if (ctx.alive()) draw(); } catch (e) { ctx.show(note(e.message), { title: "Your lights" }); }
}
const DAYS = ["M", "T", "W", "T", "F", "S", "S"], DAYN = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
function describeSet(s) {
  if (!s) return ""; if ("on" in s && !s.on) return "Turn off";
  const p = []; if (s.on) p.push("Turn on"); if ("brightness" in s) p.push(p.length ? `${s.brightness}%` : `Brightness ${s.brightness}%`); if (s.effect) p.push((EFFECTS2.find(e => e[0] === s.effect) || [0, s.effect])[1]); if (s.color || s.palette) p.push("color");
  return p.join(", ") || "No change";
}
const whenText = (trig, time, off) => { const o = off ? ` ${off > 0 ? "+" : "−"}${Math.abs(off)} min` : ""; return trig === "sunrise" ? "Sunrise" + o : trig === "sunset" ? "Sunset" + o : time; };
const actionText = (s, presets) => s.preset ? ((presets.find(p => p.id === s.preset) || {}).name ? `Preset “${presets.find(p => p.id === s.preset).name}”` : "A deleted preset") : describeSet(s.set);
export async function schedules(ctx) {
  const draw = () => {
    const f = S.fan || {}, sch = f.schedules || [], presets = f.presets || [], paused = !!f.schedules_paused, sun = f.sun, starts = f.starts_today || {};
    ctx.show(`${group(switchRow("Pause all schedules", paused ? "Nothing runs until you turn this off" : "Schedules run as set", paused, isAdmin() ? "pause" : "", { blue: paused, dis: !isAdmin() })
        + row("Location for sunrise & sunset", { sub: locationText(f.location) + (sun ? ` · today: sunrise ${sun.sunrise}, sunset ${sun.sunset}` : ""), blue: !!sun, icon: "place", tint: "var(--amber)", click: isAdmin() ? "loc" : "" }))}
      ${sch.length ? group(sch.map((s, i) => { const d = s.days || [0, 1, 2, 3, 4, 5, 6], trig = s.trigger || "time", u = s.until;
          const title = whenText(trig, s.time, s.offset) + (trig !== "time" && starts[s.id] ? ` (${starts[s.id]})` : "") + (u ? " – " + whenText(u.trigger || "time", u.time, u.offset) : "") + (s.name ? "  ·  " + s.name : "");
          const sub = [actionText(s, presets), s.fade ? `fades over ${s.fade} min` : "", u ? "then back" : "", d.length === 7 ? "every day" : d.join() === "0,1,2,3,4" ? "weekdays" : d.join() === "5,6" ? "weekends" : d.map(x => DAYN[x]).join(" "), s.if_on ? "only while on" : "", s.skip_next ? "skipping next time" : ""].filter(Boolean).join(" · ");
          return `<div class="row click" data-act="menu:${i}"><div class="t"><b>${esc(title)}</b><small class="${s.enabled !== false && !paused ? "blue" : ""}">${esc(sub)}</small></div><button data-act="toggle:${i}" aria-label="On/off">${sw(s.enabled !== false)}</button></div>`; }).join(""))
        : `${note("No schedules yet. Some ideas: wake up to a slow sunrise, dim to a warm glow at sunset, turn off while you sleep and back on in the morning.")}<div class="center"><button class="btn" data-act="edit:-1">Add schedule</button></div>`}
      ${note("Fades change the light gradually, a step each minute. With an end time, the light goes back to how it was when the schedule started.")}`,
      { title: "Schedules", actions: [{ icon: "add", label: "Add", act: "edit:-1" }] });
  };
  const putAll = async all => { try { const p = changeFan({ schedules: all }); draw(); await p; } catch (e) { toast(e.message); } draw(); };
  ctx.handlers({
    edit: i => isAdmin() ? ctx.go("schedule/" + i) : viewOnly(),
    toggle: i => { if (!isAdmin()) return viewOnly(); const all = structuredClone(S.fan?.schedules || []); all[i].enabled = all[i].enabled === false; putAll(all); },
    pause: async () => { try { await changeFan({ schedules_paused: !S.fan?.schedules_paused }); } catch (e) { toast(e.message); } draw(); },
    menu: async i => {
      if (!isAdmin()) return viewOnly();
      const all = structuredClone(S.fan?.schedules || []), s = all[i]; if (!s) return;
      const v = await choose(whenText(s.trigger || "time", s.time, s.offset), [["edit", "Edit"], ["skip", s.skip_next ? "Don't skip next time" : "Skip next time"], ["run", "Run it now"], ["dup", "Duplicate"], ["del", "Delete"]], null);
      if (v === "edit") ctx.go("schedule/" + i);
      if (v === "skip") { s.skip_next = !s.skip_next; putAll(all); }
      if (v === "run") { const set = s.preset ? (S.fan.presets || []).find(p => p.id === s.preset)?.set : s.set; if (set) { try { await changeFan(set); toast("Done"); } catch (e) { toast(e.message); } } }
      if (v === "dup") { const c = structuredClone(s); delete c.id; all.push(c); putAll(all); }
      if (v === "del") { all.splice(i, 1); putAll(all); }
    },
    loc: async () => { await locationDialog(S.fan?.location); draw(); },
  });
  draw(); try { S.fan = await get("/api/v1/fan"); if (ctx.alive()) draw(); } catch {}
}
export async function schedule(ctx) {
  const index = +ctx.args[0], ex = (S.fan?.schedules || [])[index], s0 = ex?.set || {}, presets = S.fan?.presets || [];
  const v = { name: ex?.name || "", trig: ex?.trigger || "time", time: ex?.time || "22:00", offset: ex?.offset || 0, days: new Set(ex?.days || [0, 1, 2, 3, 4, 5, 6]),
    action: ex?.preset ? 2 : ("on" in s0 && !s0.on) ? 0 : 1, preset: ex?.preset || presets[0]?.id || null, bright: s0.brightness ?? 30, color: s0.color || null, effect: s0.effect || null,
    changeBright: !ex || "brightness" in s0, turnOn: !ex?.if_on,
    fade: ex?.fade || 0, hasEnd: !!ex?.until, endTrig: ex?.until?.trigger || "time", endTime: ex?.until?.time || "07:00", endOffset: ex?.until?.offset || 0,
    light: ex?.light || "main" };
  const sunKnown = !!S.fan?.sun;
  const zones = S.lights?.zones || [];                                // with more than one light: which one it's for
  const trigHtml = (pre, trig, time, off) => `${segmented(["Time", "Sunrise", "Sunset"], ["time", "sunrise", "sunset"].indexOf(trig), pre + "trig")}
    ${trig === "time" ? `<div class="timepick"><input type="time" id="${pre}tm" value="${esc(time)}" required></div>`
      : group(slider("Offset", pre + "off", off, -120, 120, off === 0 ? `At ${trig}` : off < 0 ? `${-off} min before` : `${off} min after`) + (sunKnown ? "" : `<p class="note" style="color:var(--amber);margin:0 22px 12px">Set the server's location in Schedules first.</p>`))}`;
  const draw = () => {
    const lightPick = zones.length > 1 ? sec("Which light") + group([["main", zones[0]?.name || "Main light"], ...zones.slice(1).map(z => [z.id, z.name]), ["all", "All lights"]]
      .map(([id, nm]) => row(nm, { icon: "bulb", end: radio(v.light === id), click: "lt:" + id })).join("")) : "";
    ctx.show(`${lightPick}${sec("Starts")}${trigHtml("s", v.trig, v.time, v.offset)}
      <div class="days">${DAYS.map((d, i) => `<button class="${v.days.has(i) ? "on" : ""}" data-act="day:${i}">${d}</button>`).join("")}</div>
      ${sec("Does")}${segmented(["Turn off", "Set the light", "A preset"], v.action, "action")}
      ${v.action === 1 ? group(switchRow("Change the brightness", v.changeBright ? `To ${v.bright}%` : "No change", v.changeBright, "cb") + (v.changeBright ? slider("Brightness", "b", v.bright, 0, 100, v.bright + "%") : ""))
          + sec("Color") + group(swatchRow(v.color, "c", false, true))
          + sec("Effect") + `<div class="chips">${[[null, "No change"], ...EFFECTS2.map(e => [e[0], e[1]])].map(([k, l]) => chip(l, "eff:" + (k || ""), v.effect === k, !k)).join("")}</div>`
          + group(switchRow("Turn the light on if it's off", v.turnOn ? "Always runs" : "Only runs while the light is on — handy for dimming", v.turnOn, "ton"))
        : v.action === 2 ? group(presets.length ? presets.map(p => row(p.name, { sub: describeSet(p.set), end: radio(v.preset === p.id), click: "pre:" + p.id })).join("") : row("No presets yet — save one on the Lighting page first.", { dis: true })) : ""}
      ${sec("Fade")}<div class="chips">${[0, 5, 10, 15, 30, 45, 60, 90, 120].map(m => chip(m ? `${m} min` : "Instant", "fade:" + m, v.fade === m)).join("")}</div>
      ${note(v.fade ? `Glides there over ${v.fade} minutes — a slow sunrise or a gentle fade to sleep.` : "Changes straight away.")}
      ${sec("Ends")}${group(switchRow("Put the light back afterwards", v.hasEnd ? "At the end time it returns to how it was" : "Stays like this", v.hasEnd, "end"))}
      ${v.hasEnd ? trigHtml("e", v.endTrig, v.endTime, v.endOffset) : ""}
      ${sec("Name (optional)")}${group(`<div style="padding:16px"><input class="field" id="nm" maxlength="30" placeholder="e.g. Wake up" value="${esc(v.name)}"></div>`)}
      ${index >= 0 ? group(row("Delete schedule", { icon: "del", tint: "var(--red)", click: "delete" })) : ""}
      <div style="display:flex;gap:12px;padding:18px 22px"><button class="btn" style="flex:1;background:color-mix(in srgb,var(--text) 8%,transparent);color:var(--text)" data-act="back">Cancel</button><button class="btn" style="flex:1" data-act="save" ${v.days.size && (v.action !== 2 || v.preset) && (v.action !== 1 || v.turnOn || v.changeBright || v.color || v.effect) ? "" : "disabled"}>Save</button></div>`,
      { title: index >= 0 ? "Edit schedule" : "New schedule" });
    $("#stm") && ($("#stm").onchange = e => v.time = e.target.value || v.time);
    $("#etm") && ($("#etm").onchange = e => v.endTime = e.target.value || v.endTime);
    $("#nm").oninput = e => v.name = e.target.value;
    wireCommon(ctx.root, {
      onSeg: (k, i) => { if (k === "strig") v.trig = ["time", "sunrise", "sunset"][i]; if (k === "etrig") v.endTrig = ["time", "sunrise", "sunset"][i]; if (k === "action") v.action = i; draw(); },
      onRangeInput: (k, x) => { if (k === "b") { v.bright = x; $('[data-lbl="b"]').textContent = x + "%"; }
        if (k === "soff" || k === "eoff") { x = Math.round(x / 5) * 5; if (k === "soff") v.offset = x; else v.endOffset = x; const t = k === "soff" ? v.trig : v.endTrig;
          $(`[data-lbl="${k}"]`).textContent = x === 0 ? `At ${t}` : x < 0 ? `${-x} min before` : `${x} min after`; } },
    });
  };
  const write = async all => { try { await changeFan({ schedules: all }); ctx.back(); toast("Saved"); } catch (e) { toast(e.message); } };
  ctx.handlers({
    day: i => { i = +i; v.days.has(i) ? v.days.delete(i) : v.days.add(i); draw(); },
    lt: id => { v.light = id; draw(); },
    col: (_, h) => { v.color = !h || h === "none" || v.color === h ? null : h; draw(); },
    pick: async () => { const c = await colorPicker(v.color || "#3e91ff"); if (c) { v.color = c; draw(); } },
    eff: k => { v.effect = k || null; draw(); }, cb: () => { v.changeBright = !v.changeBright; draw(); }, ton: () => { v.turnOn = !v.turnOn; draw(); }, pre: id => { v.preset = id; draw(); }, fade: m => { v.fade = +m; draw(); }, end: () => { v.hasEnd = !v.hasEnd; draw(); },
    delete: async () => { const all = structuredClone(S.fan?.schedules || []); all.splice(index, 1); await write(all); },
    save: async () => {
      const all = structuredClone(S.fan?.schedules || []);
      const set = v.action === 0 ? { on: false } : v.action === 1 ? { ...(v.turnOn ? { on: true } : {}), ...(v.changeBright ? { brightness: v.bright } : {}), ...(v.color ? { color: v.color } : {}), ...(v.effect ? { effect: v.effect } : {}) } : {};
      const s = { time: v.time, days: [...v.days].sort(), enabled: true, name: v.name.trim(), trigger: v.trig, offset: v.offset, fade: v.fade, set, preset: v.action === 2 ? v.preset : "",
        if_on: v.action === 1 && !v.turnOn, light: v.light, ...(v.hasEnd ? { until: { trigger: v.endTrig, time: v.endTime, offset: v.endOffset } } : {}), ...(ex?.id ? { id: ex.id, skip_next: !!ex.skip_next } : {}) };
      if (index >= 0) all[index] = s; else all.push(s); await write(all);
    },
  });
  draw();
}

// ═══════════════════════════════ STORAGE & HARDWARE ═════════════════════════════
const driveName = d => { const m = d.model || "", gbn = Math.round(d.size / 1e9), gb = gbn >= 1000 ? Math.round(gbn / 1000) + "TB" : Math.round(gbn / 10) * 10 + "GB";
  const brand = [["CT", "Crucial"], ["ST", "Seagate"], ["WDC", "WD"], ["WD", "WD"], ["Samsung", "Samsung"], ["SAMSUNG", "Samsung"], ["SanDisk", "SanDisk"], ["HFM", "SK hynix"], ["HFS", "SK hynix"],
    ["KINGSTON", "Kingston"], ["TOSHIBA", "Toshiba"], ["INTEL", "Intel"], ["Micron", "Micron"], ["HGST", "HGST"]].find(([p]) => m.startsWith(p))?.[1];
  const kind = d.bus === "nvme" || (d.name || "").startsWith("nvme") ? "NVMe" : d.ssd ? "SSD" : "HDD";
  return !m ? d.name : brand ? `${brand} ${kind} ${gb}` : `${m} ${gb}`; };
const health = d => { const p = d.health != null ? ` · ${d.health}%` : "";
  return d.smart_passed === false ? ["Failing" + p, "critical"] : (d.worn ?? (d.realloc > 9 || d.uncorrect > 3)) ? ["Worn — keep an eye on it" + p, "warning"] : (d.realloc > 0 || d.uncorrect > 0 || d.pending > 0) ? ["Mostly healthy" + p, "ok"] : d.crc > 50 ? ["Healthy*" + p, "ok"] : ["Healthy" + p, "ok"]; };
export async function hardware(ctx) {
  const [serial] = ctx.args;
  if (serial) return drive(ctx, serial);
  // each drive's light, beside it, blinking with its real activity like the one on the server picture
  let raf = 0, slot = -1;
  const blink = t => { raf = requestAnimationFrame(blink); const s = Math.floor(t / 110); if (s === slot) return; slot = s;
    for (const el of $$(".dled", ctx.root)) { const io = S.lastNow?.disks?.[el.dataset.dev], busy = io ? io[0] : 0, rate = io ? (busy > 0 || io[1] > 0 ? Math.min(.85, .06 + .8 * Math.sqrt(busy)) : 0) : .09;
      el.classList.toggle("on", Math.random() < rate); } };
  raf = requestAnimationFrame(blink); ctx.onLeave(() => cancelAnimationFrame(raf));
  const draw = () => {
    const hw = S.cache["/api/v1/hardware"], t = hw?.temps, ds = hw?.drives || [];
    const roles = {}; ds.forEach(d => (roles[d.role || "Other"] ||= []).push(d));
    const ord = r => r === "Boot drive" ? 1 : 0;
    ctx.show(`${ST.overviewHtml()}${t ? sec("Temperatures") + group(row("CPU", { sub: t.cpu_temp || "—", icon: "cpu" }) + row("Boot NVMe", { sub: t.nvme_temp || "—", icon: "ssd" }) + row("Drives", { sub: t.drive_temps || "—", icon: "disk" })) : ""}
      ${Object.entries(roles).sort(([a], [b]) => ord(a) - ord(b)).map(([r, l]) => sec(r) + group(l.map(d => { const [h, lv] = health(d), u = d.usage?.find(x => x.mount === "/") || d.usage?.[0], f = u ? u.used / Math.max(1, u.total) : 0;
          return `<div class="row click" data-act="open:${esc(d.serial)}" style="display:block"><div style="display:flex;align-items:center;gap:8px"><span class="dled" data-dev="${esc(d.name)}" title="Activity"></span><div class="t"><b>${esc(driveName(d))}</b><small>${esc(d.model || "")}${d.model ? " · " : ""}${bytes(d.size)} · ${d.ssd ? "SSD" : "HDD"} · ${esc((d.bus || "").toUpperCase())}${d.temp ? ` · ${d.temp}°C` : ""}<br>S/N ${esc(d.serial)}</small></div><b style="color:${levelColor(lv)};font-size:14px">${h}</b></div>
            ${u ? `<div style="margin-top:8px">${bar(f, usageColor(f))}</div><small class="muted" style="font-size:13px">${bytes(u.free)} free of ${bytes(u.total)}</small>` : (d.mounts || []).length ? "" : `<small style="color:var(--amber);font-size:13px">Not mounted</small>`}</div>`; }).join(""))).join("")
        || note(hw ? "No drives reported." : "Loading…")}
      ${sec("Fans")}${group(expand("CPU & case fan speed", "Run by the motherboard", "speed", "The fans follow the motherboard's own curve — set it in the BIOS (often under Smart Fan or Q-Fan). Reading or setting speeds from Linux needs a driver for the board's fan chip.", { tint: "var(--sub)" })
        + (has("lighting") ? row("Fan lighting", { sub: "Color, effects, schedules", blue: true, icon: "bulb", tint: "var(--amber)", click: "go:lighting" }) : ""))}
      ${links([["Quick panel (restart, shut down)", "go:quick"]])}`, { title: "Storage & hardware", actions: [{ icon: "add", label: "Set up drives", act: "go:setup" }] });
    wireCommon(ctx.root);
  };
  ctx.handlers({ open: s => ctx.go("hardware/" + encodeURIComponent(s)), ...ST.overviewHandlers(ctx) });
  draw(); ST.refreshOverview().then(() => ctx.alive() && draw());
  ctx.every(15000, async () => { await Promise.all([get("/api/v1/hardware"), ST.refreshOverview()]); draw(); }, true);
}
async function drive(ctx, serial) {
  let busy = false, testBusy = false;
  const tp = `/api/v1/drives/${encodeURIComponent(serial)}/test`;
  const dur = m => m >= 120 ? `${Math.round(m / 60)} hours` : `${m} min`;
  const testsHtml = () => {
    const t = S.cache[tp]; if (!t || t.supported === false) return "";
    const log = (t.log || []).slice(0, 5).map(e => { const ok = e.passed !== false && !/fail/i.test(e.result || "");
      return row(`${(e.type || "Test").replace(" offline", "")} · ${e.result}`, { sub: e.hours ? `At ${e.hours} power-on hours${t.power_on_hours ? ` (now ${t.power_on_hours})` : ""}` : "", icon: ok ? "okc" : "err", tint: ok ? "var(--green)" : "var(--red)" }); }).join("");
    if (t.running) { const done = t.remaining_pct != null ? 100 - t.remaining_pct : (t.progress_pct || 0);
      return sec("Health tests") + group(`<div style="padding:16px 22px"><div style="font-size:17px">Testing… ${done}%</div><div style="margin-top:8px">${bar(done / 100)}</div><p class="muted" style="font-size:13px;margin:6px 0 0">The drive keeps working normally while it tests itself.</p></div>`
        + row("Stop the test", { icon: "stop", tint: "var(--red)", click: "test:abort", dis: testBusy }) + log); }
    return sec("Health tests") + group(row("Quick test", { sub: `About ${t.short_minutes || 2} min · checks the electronics and a sample of the surface`, blue: true, icon: "speed", click: "test:short", dis: testBusy })
      + row("Full surface scan", { sub: `${t.long_minutes ? `About ${dur(t.long_minutes)} · ` : ""}reads every sector — best for second-hand drives`, blue: true, icon: "disk", click: "test:long", dis: testBusy }) + log);
  };
  const draw = () => {
    const d0 = (S.cache["/api/v1/hardware"]?.drives || []).find(x => x.serial === serial);
    if (!d0) return ctx.show(note("Loading…"), { title: "Drive" });
    const fresh = S.cache[tp]?.attrs || {};            // read just now (the list is refreshed every 30 minutes)
    const d = { ...d0, ...Object.fromEntries(Object.entries({ smart_passed: fresh.passed, temp: fresh.temp, realloc: fresh.realloc, pending: fresh.pending, uncorrect: fresh.uncorrect, crc: fresh.crc }).filter(([, v]) => v != null)) };
    const [h, lv] = health(d), mounted = (d.mounts || []).length > 0;
    ctx.show(`<div class="center" style="padding:16px 0"><div style="color:${levelColor(lv)}">${I(d.ssd ? "ssd" : "disk").replace('class="i ', 'style="width:90px;height:90px" class="i ')}</div>
        <div style="color:${levelColor(lv)};font-size:18px;font-weight:700">${h}</div><div class="muted">${esc(d.role || "")} · /dev/${esc(d.name)}</div></div>
      ${sec("Health (SMART)")}${smartHtml(d)}
      ${testsHtml()}
      ${isAdmin() ? group(row("Partitions", { sub: d.protected ? "See how it's laid out (it's in use, so it can't be changed)" : "See and change how it's laid out — create, delete, format, label, resize", blue: true, icon: "viewday", click: "go:partitions/" + encodeURIComponent(serial) })) : ""}
      ${sec("Details")}${group(row("Model", { sub: d.model || "—" }) + row("Size", { sub: bytes(d.size) }) + row("Connection", { sub: (d.bus || "").toUpperCase() }) + row("Serial", { sub: d.serial }) + row("Mounted at", { sub: (d.mounts || []).join(", ") || "Not mounted" })
        // bad sectors, as space: remapped ones are capacity the drive retired (nothing lost); pending ones can't be read right now
        + ((d.realloc || 0) + (d.pending || 0) > 0 ? row("Bad sectors", { sub: [(d.realloc || 0) ? `${d.realloc} remapped ≈ ${bytes(d.realloc * (d.sector || 512))} retired (nothing lost)` : "", (d.pending || 0) ? `${d.pending} unreadable ≈ ${bytes(d.pending * (d.sector || 512))} of data at risk until rewritten` : ""].filter(Boolean).join(" · ") }) : ""))}
      ${!isAdmin() ? note("View-only access: an admin can mount or unmount drives.") : d.protected ? note(`This drive is in use by the server (${d.role}) and can't be unmounted from the app.`)
        : `<button class="btn block" data-act="mount" ${busy ? "disabled" : ""}>${busy ? "Working…" : mounted ? "Safely unmount" : "Mount"}</button>`}
      ${isAdmin() && !d.protected ? sec("Fix and copy") + group(row("Repair", { sub: "Checks every filesystem on it and fixes what it can, then runs the drive's quick self-test. It's unmounted for a few minutes while that happens.", icon: "build", tint: "var(--amber)", click: "repair" })
          + row("Clone to another drive", { sub: "Copies everything onto a spare drive (which is erased). Bigger: an exact copy that grows to fill it. Smaller: the files are copied if they fit.", icon: "copy", tint: "var(--blue)", click: "clone" })) : ""}`, { title: driveName(d) });
  };
  ctx.handlers({
    smartfold: () => { smartOpen = !smartOpen; $$(".expand.ign", ctx.root).forEach(e => e.querySelector("[data-act=smartfold]") && e.classList.toggle("open", smartOpen)); },
    repair: async () => {
      const d = (S.cache["/api/v1/hardware"]?.drives || []).find(x => x.serial === serial); if (!d) return;
      if (!(await confirm(`Repair ${driveName(d)}?`, `${(d.mounts || []).length ? `It's unmounted while it's checked (${d.mounts.join(", ")}) — apps using it lose it for a few minutes, then it's mounted again. ` : ""}Nothing is erased. Your phone confirms it.`, "Repair", "var(--blue)"))) return;
      ST.startTask(ctx, "/api/v1/storage/task/repair", { spec: { drives: [serial], confirm: [serial] } });
    },
    clone: async () => {
      const d = (S.cache["/api/v1/hardware"]?.drives || []).find(x => x.serial === serial); if (!d) return;
      const st = S.cache["/api/v1/storage"] || await get("/api/v1/storage").catch(e => (toast(e.message), null)); if (!st) return;
      const me = st.drives.find(x => x.serial === serial), used = (me?.partitions || []).reduce((a, p) => a + (p.usage?.used || 0), 0);
      const targets = st.drives.filter(x => x.serial !== serial && x.erasable);
      if (!targets.length) return dialog("No spare drive", "Plug in the drive to copy onto. It has to be unmounted and not part of a pool or array — Nova never offers the system drive.", [{ label: "OK" }]);
      const pick = await choose("Copy onto which drive?", targets.map(x => [x.serial, `${x.size_text} ${x.model || x.name}`,
        x.size >= (me?.size || d.size) ? `Exact copy${x.size > (me?.size || d.size) * 1.01 ? ", then it grows to use the extra space" : ""}` : used && used * 1.05 < x.size ? `Smaller — the files (${bytes(used)}) are copied, the filesystems made to fit` : "Smaller — Nova checks whether the files fit"]), null);
      if (!pick) return;
      const t = targets.find(x => x.serial === pick);
      if (!(await confirm(`Erase ${t.size_text} ${t.model || t.name}?`, `Everything on it is replaced by a copy of ${driveName(d)}. ${(d.mounts || []).length ? "The drive you're copying is unmounted while it's copied, so nothing changes halfway. " : ""}This can't be undone — your phone confirms it.`, "Erase and clone"))) return;
      ST.startTask(ctx, "/api/v1/storage/task/clone", { spec: { drives: [serial, pick], confirm: [pick] } });
    },
    test: async kind => {
      if (!isAdmin()) return viewOnly();
      testBusy = true; draw();
      try { const r = await post(tp, { type: kind }); if (kind !== "abort") toast(r.minutes ? `Test started · about ${dur(r.minutes)}` : "Test started"); await get(tp); }
      catch (e) { toast(e.message); } finally { testBusy = false; if (ctx.alive()) draw(); }
    },
    mount: async () => {
      const d = (S.cache["/api/v1/hardware"]?.drives || []).find(x => x.serial === serial), mounted = (d?.mounts || []).length > 0;
      if (mounted && !(await confirm("Unmount this drive?", "Anything using it stops being able to read or write it. Your phone confirms it.", "Unmount"))) return;
      busy = true; draw();
      const path = `/api/v1/drives/${encodeURIComponent(serial)}/${mounted ? "unmount" : "mount"}`;
      try { const r = await post(path); toast(mounted ? (r?.note || "Unmounted — safe to remove") : "Mounted"); await get("/api/v1/hardware"); }
      catch (e) {
        // something's using it: say what, and offer to force it (stops those containers, detaches the rest)
        if (e.code === 409 && mounted && await confirm("Force unmount?", `${e.message}. Force unmount stops those containers and detaches the drive; programs that had it open (a terminal, say) let go when they're closed. Start the containers again once it's back.`, "Force unmount")) {
          try { const r = await post(path, { force: true }); toast(r?.note || "Unmounted"); await get("/api/v1/hardware"); } catch (e2) { toast(e2.message); }
        } else if (e.code !== 409) toast(e.message);
      } finally { busy = false; if (ctx.alive()) draw(); }
    },
  });
  draw(); if (!S.cache["/api/v1/hardware"]) { await get("/api/v1/hardware").catch(e => toast(e.message)); if (ctx.alive()) draw(); }
  ctx.every(5000, async () => { const before = S.cache[tp]?.running; await get(tp).catch(() => {}); if (before || S.cache[tp]?.running || !before) draw(); }, true);
}

// ════════════════════════════ DEVICES, SETTINGS, ABOUT ═════════════════════════
const formOf = d => d.form || (d.type === "browser" ? "desktop" : "phone");
const formLabel = d => { if (d.type === "watch") return "Approves from notifications"; if (d.type === "head") return "Another Nova server's web · read-only"; const n = { tablet: "Tablet", desktop: "Computer" }[formOf(d)] || "Phone"; return d.type === "browser" ? "Browser · " + n : n; };
export async function devices(ctx) {
  const draw = () => {
    const l = S.cache["/api/v1/devices"]?.devices || [], by = {};
    l.forEach(d => (by[d.user || "No name yet"] ||= []).push(d));
    ctx.show(`${Object.entries(by).sort(([a], [b]) => (a === "No name yet") - (b === "No name yet") || a.localeCompare(b)).map(([u, ds]) => sec(u) + group(ds.map(d => row(d.name + (d.current ? "  ·  this browser" : ""), {
        sub: `${formLabel(d)} · ${d.role === "viewer" ? "View only" : "Admin"} · last seen ${d.last_seen || "never"}${d.via ? " via " + d.via : ""}${d.type === "browser" ? " · risky actions approved on a phone" : ""}`,
        icon: d.type === "head" ? "dns" : { tablet: "tablet", desktop: "computer" }[formOf(d)] || "phone", tint: d.current ? "var(--green)" : d.role === "viewer" ? "var(--sub)" : "var(--blue)",
        click: isAdmin() ? "dev:" + d.id : "", end: !d.current && isAdmin() ? `<button class="rmbtn press" data-act="rm:${esc(d.id)}" aria-label="Remove ${esc(d.name)}" title="Remove">${I("del")}</button>` : "" })).join(""))).join("") || note("Loading…")}
      ${isAdmin() ? sec("Without a phone") + group(row("Security & privacy", { sub: "Authenticator codes, passkeys, recent security activity", blue: true, icon: "shield", click: "go:security" }) + row("Authenticator codes", { sub: S.cache["/api/v1/totp"]?.enabled ? `On since ${S.cache["/api/v1/totp"].since || "—"} · a code approves a browser, or what a browser asks for` : "Off · lets a computer with no phone or SSH at hand be approved with a code from an authenticator app", blue: true, icon: "key", click: "totp" })) : ""}
      ${group(row("Remove this browser", { sub: "Erases its key here and its access on the server", icon: "del", tint: "var(--red)", click: "forget" }))}
      ${note("Removing a device here is approved on an admin phone with your fingerprint. Inviting phones, approving browsers and changing roles happen in the Nova app on an admin phone (they need its fingerprint key). Admins can do everything; view-only devices see the same screens but can't change anything.")}`,
      { title: "Users & devices" });
  };
  ctx.handlers({ forget: () => forgetBrowser(),
    totp: () => totpSetup(draw),
    dev: async id => {
      const d = (S.cache["/api/v1/devices"]?.devices || []).find(x => x.id === id); if (!d) return;
      const v = await choose(d.name, [["rename", "Rename"], ...(d.current ? [] : [["remove", "Remove"]])], null);
      if (v === "remove") return ctx.run("rm", id);
      if (v !== "rename") return;
      const n = await ask("Rename", "Name", d.name, "Save", 40); if (!n || n === d.name) return;
      try { await post(`/api/v1/devices/${encodeURIComponent(id)}/rename`, { name: n }); await get("/api/v1/devices"); draw(); toast("Renamed"); } catch (e) { toast(e.message); }
    },
    rm: async id => {
      const d = (S.cache["/api/v1/devices"]?.devices || []).find(x => x.id === id); if (!d) return;
      if (!(await confirm(`Remove ${d.name}?`, `${d.name} loses access to this server${d.type === "watch" ? "" : " (and so does a watch paired with it)"}. You'll approve this on an admin phone with your fingerprint.`, "Remove"))) return;
      try { const r = await del(`/api/v1/devices/${encodeURIComponent(id)}`); if (r?.ok !== false) { toast(`Removed ${d.name}`); await get("/api/v1/devices"); draw(); } }
      catch (e) { toast(e.message); }
    } });
  draw(); try { await Promise.all([get("/api/v1/devices"), isAdmin() ? get("/api/v1/totp").catch(() => {}) : null]); if (ctx.alive()) draw(); } catch (e) { toast(e.message); }
}
/** Authenticator codes: set up (QR + key + one code to check) or turn off. Used by Users & devices and Security & privacy. */
export async function totpSetup(done = () => {}) {
      try {
    if (S.cache["/api/v1/totp"]?.enabled) {
      if (!(await confirm("Turn off authenticator codes?", "Codes from the app stop working. Browsers are approved on an admin phone (or with sudo nova approve) again.", "Turn off", "var(--red)"))) return;
      await post("/api/v1/totp/remove", {}); await get("/api/v1/totp"); done(); return toast("Authenticator codes are off");
    }
    if (!(await confirm("Set up authenticator codes?", "Any authenticator app works (Google Authenticator, Aegis, 1Password…). Once it's on, a 6-digit code from it can add a browser to this server, or approve something a browser asks for — so keep it on a device only you use. Turning it on is approved on your phone.", "Set up"))) return;
    const r = await post("/api/v1/totp/setup", {});
    const box = sheet(`<h2>Scan this in your authenticator app</h2><div class="pad" style="text-align:center"><div style="display:inline-block;background:#fff;padding:10px;border-radius:12px;width:220px;max-width:100%">${r.qr_svg || ""}</div>
      <p class="muted" style="margin:10px 0 4px">Or type this key into the app:</p>${copyCmd(r.secret.replace(/(.{4})/g, "$1 ").trim())}
      <p style="margin:14px 0 6px">Then type the code it shows, to check it works:</p><input class="field" id="tcf" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="123456" style="font-family:ui-monospace,monospace;letter-spacing:4px;text-align:center">
      <p class="muted" id="tcfe" style="color:var(--red)"></p></div><div class="acts"><button data-b="0" data-role="cancel">Cancel</button><i></i><button data-b="1" data-role="primary" style="color:var(--blue)">Turn on</button></div>`);
    $$("[data-b]", box)[0].onclick = () => closeSheet();
    $$("[data-b]", box)[1].onclick = async () => {
      try { await post("/api/v1/totp/confirm", { code: $("#tcf", box).value.trim() }); closeSheet(); await get("/api/v1/totp"); done(); toast("Authenticator codes are on"); }
      catch (e) { $("#tcfe", box).textContent = e.message; }
    };
  } catch (e) { toast(e.message); }
}
export async function forgetBrowser() {
  if (!(await confirm("Remove this browser?", "It loses access and its key is erased. You can add it again from an admin phone.", "Remove"))) return;
  try { await del(`/api/v1/devices/${S.device}`); } catch {}
  await kv("device", null); location.replace("/"); location.reload();
}
export async function settings(ctx) {
  const draw = () => {
    const me = S.me || {}, u = S.cache["/api/v1/server/update"];
    const upd = !u ? "Checking…" : !u.source ? `Version ${u.installed} · updates aren't set up yet (sudo nova-setup)` : u.running || u.state === "installing" ? "Installing a signed release — Nova restarts on its own"
      : u.state === "failed" ? `Last update failed: ${(u.error || "").slice(0, 80)}` : u.state === "available" ? `${u.available} is available · install it from the Nova app on an admin phone` : `Version ${u.installed} · up to date`;
    ctx.show(`${sec("Software update")}${group(row(u?.state === "available" ? `Server update to ${u.available}` : "Server", { sub: upd, blue: u?.state === "available", icon: "dns", tint: u?.state === "failed" ? "var(--red)" : "var(--blue)" })
        + row("Nova web", { sub: "Always the version on your server — updates with it", icon: "computer" })
        + `<a class="row click" style="text-decoration:none;color:inherit" href="https://github.com/dash1101/Nova/releases/latest/download/nova-universal.apk" target="_blank" rel="noopener"><span class="ri" style="color:var(--green);background:color-mix(in srgb, var(--green) 16%, transparent)">${I("phone")}</span><div class="t"><b>Get the Android app</b><small class="blue">Download it on your phone (Android 9 or newer), then pair with <code>sudo nova add</code></small></div></a>`)}
      ${sec("Server")}${group(row("Name", { sub: serverName(), blue: true, icon: "edit", click: "go:server" }))}
      ${sec("This browser")}${group(
        expand("Signing key", "Made in this browser, can't be exported", "key", "Every request is signed by a key this browser created with Web Crypto as non-extractable — page scripts can use it, but nobody (not even Nova) can read it out. Each request carries a fresh time stamp and a one-time number, so a recorded request can't be replayed.")
        + expand("Risky actions", "Approved on your phone", "shield", "Stopping or restarting things, installs, unmounting, shells, restarting the server: the server holds them until you approve them on an admin phone with your fingerprint. A stolen browser key alone can't do any of them.", { blue: true })
        + expand("Connection", `${location.host} · ${location.protocol === "https:" ? "encrypted" : "not encrypted"}`, location.hostname.match(/^\d/) ? "wifi" : "cloud",
          location.hostname.match(/^\d/) ? "At home the browser talks to the server directly over HTTPS (Nova's own certificate)." : "Away from home Nova goes through Cloudflare Access: you log in to Cloudflare first, then the browser's own key signs every request."))}
      ${group(row("Security & privacy", { sub: "Approvals, authenticator codes, passkeys, privacy, recent activity", blue: true, icon: "shield", tint: "var(--green)", click: "go:security" })
        + row("Users & devices", { sub: (me.role === "viewer" ? "You have view-only access" : "Admin") + (me.user ? " · " + me.user : ""), blue: true, icon: "group", click: "go:devices" }))}
      ${group(row("Notifications", { icon: "bell", click: "go:notify" }) + row("Appearance", { sub: "Theme, Home layout, shortcuts, bottom bar", blue: true, icon: "palette", click: "go:appearance" })
        + row("Setup guide", { sub: "Install Nova on a server, remote access, browsers", blue: true, icon: "book", click: "go:guide" }))}
      ${group(row("Remove this browser", { sub: "Erases its key", icon: "del", tint: "var(--red)", click: "forget" }))}
      ${sec("Labs")}${group(row("Automation", { sub: "Crash-loop guard, weekly updates, old-image cleanup, Wake-on-LAN", blue: true, icon: "sync", tint: "#30b0c7", click: "go:automation" }) + row("Labs", { sub: "Experimental features you can try", blue: true, icon: "science", tint: "#bf5af2", click: "go:labs" })
        + row("Features", { sub: "Switch off parts of Nova you don't use, like lighting control", blue: true, icon: "tune", click: "go:features" })
        + row("Plugins", { sub: "Add-ons that bring more apps and tools", blue: true, icon: "widgets", click: "go:plugins" }))}
      ${links([["About Nova", "go:about"]])}`, { title: "Settings" });
    wireCommon(ctx.root);
  };
  ctx.handlers({ forget: () => forgetBrowser(),
    dev: async id => {
      const d = (S.cache["/api/v1/devices"]?.devices || []).find(x => x.id === id); if (!d) return;
      const v = await choose(d.name, [["rename", "Rename"], ...(d.current ? [] : [["remove", "Remove"]])], null);
      if (v === "remove") return ctx.run("rm", id);
      if (v !== "rename") return;
      const n = await ask("Rename", "Name", d.name, "Save", 40); if (!n || n === d.name) return;
      try { await post(`/api/v1/devices/${encodeURIComponent(id)}/rename`, { name: n }); await get("/api/v1/devices"); draw(); toast("Renamed"); } catch (e) { toast(e.message); }
    },
    rm: async id => {
      const d = (S.cache["/api/v1/devices"]?.devices || []).find(x => x.id === id); if (!d) return;
      if (!(await confirm(`Remove ${d.name}?`, `${d.name} loses access to this server${d.type === "watch" ? "" : " (and so does a watch paired with it)"}. You'll approve this on an admin phone with your fingerprint.`, "Remove"))) return;
      try { const r = await del(`/api/v1/devices/${encodeURIComponent(id)}`); if (r?.ok !== false) { toast(`Removed ${d.name}`); await get("/api/v1/devices"); draw(); } }
      catch (e) { toast(e.message); }
    } });
  draw(); ctx.every(10000, async () => { await get("/api/v1/server/update").catch(() => {}); draw(); }, true);
}
export const locationText = l => !l ? "Not known — set it for sunrise/sunset schedules" : l.source === "timezone" ? `Near ${l.name} (from the time zone)` : l.name || `${(+l.lat).toFixed(2)}, ${(+l.lon).toFixed(2)}`;
/** Where the server is (for sunrise/sunset). Saved in the server's settings; resolves with the new location (or undefined if canceled). */
export async function locationDialog(loc) {
  const typed = loc?.source === "set" ? loc : {}, v = { lat: typed.lat ?? "", lon: typed.lon ?? "", name: typed.name ?? "" };
  const pending = dialog("Where is the server?", `Used to work out sunrise and sunset for light schedules. Nova starts from the server's time zone${loc?.source === "timezone" ? ` (${loc.name})` : ""}; for the exact times, type its latitude and longitude (from any map app — your town is close enough), or use this device's location if it's with the server.`,
    [{ label: "Use the time zone", value: "tz" }, { label: "Use my location", color: "var(--blue)", value: "geo" }, { label: "Save", color: "var(--blue)", value: "save" }],
    `<div class="pad" style="display:flex;flex-direction:column;gap:10px"><div style="display:flex;gap:10px"><input class="field" id="lat" inputmode="decimal" placeholder="Latitude" value="${esc(v.lat)}"><input class="field" id="lon" inputmode="decimal" placeholder="Longitude" value="${esc(v.lon)}"></div><input class="field" id="lnm" maxlength="40" placeholder="Name (optional, e.g. Home)" value="${esc(v.name)}"></div>`);
  $("#lat").oninput = e => v.lat = e.target.value; $("#lon").oninput = e => v.lon = e.target.value; $("#lnm").oninput = e => v.name = e.target.value;
  const b = await pending; let body;
  if (b === "tz") body = null;
  else if (b === "geo") { try { const p = await new Promise((ok, no) => navigator.geolocation.getCurrentPosition(ok, no, { timeout: 10000 })); body = { lat: p.coords.latitude, lon: p.coords.longitude, name: v.name.trim() }; } catch { toast("Location isn't available here — type it instead"); return; } }
  else if (b === "save") { const lat = parseFloat(v.lat), lon = parseFloat(v.lon); if (isNaN(lat) || isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) { toast("Enter a latitude (−90…90) and longitude (−180…180)"); return; } body = { lat, lon, name: v.name.trim() }; }
  else return;
  try { const r = await post("/api/v1/settings", { location: body }); S.cache["/api/v1/settings"] = r; S.fan = await get("/api/v1/fan"); toast("Saved"); return r.location || null; } catch (e) { toast(e.message); }
}
export async function serverSettings(ctx) {
  const draw = () => {
    const st = S.cache["/api/v1/settings"] || {};
    ctx.show(`${sec("Name")}${group(`<div style="padding:18px"><input class="field" id="nm" maxlength="40" placeholder="${esc(st.hostname || "Server name")}" value="${esc(st.display_name || "")}" ${isAdmin() ? "" : "disabled"}>
        <div style="display:flex;gap:10px;margin-top:10px">${isAdmin() ? `<button class="pillbtn press" data-act="save">Save</button>${st.display_name ? `<button class="pillbtn plain press" data-act="host">Use hostname</button>` : ""}` : ""}</div></div>`)}
      ${note(`Shown at the top of Home and in the server switcher. The machine's hostname (${st.hostname || "—"}) doesn't change.`)}
      ${sec("Accent color")}${group(`<div style="display:flex;justify-content:space-between;padding:18px">${ACCENTS.map(h => `<button class="swatch${(st.accent || "") === h ? " on" : ""}" style="width:32px;height:32px;${h ? `background:${h}` : ""}" data-act="${isAdmin() ? "acc:" + (h || "none") : ""}">${h ? "" : `<span class="muted" style="font-size:13px">A</span>`}</button>`).join("")}</div>`)}
      ${note('Gives each server its own color, so you always know which one you\'re controlling. "A" is the default blue.')}
      ${sec("Location")}${group(row("Where the server is", { sub: locationText(st.location), blue: st.location?.source === "set", icon: "place", tint: "var(--amber)", click: isAdmin() ? "loc" : "" }))}
      ${note("For sunrise and sunset light schedules — worked out on the server, nothing is looked up online.")}`, { title: "Server" });
  };
  const save = async patch => {
    try { S.cache["/api/v1/settings"] = await post("/api/v1/settings", patch); if (S.overview?.server) Object.assign(S.overview.server, patch); await refresh(); toast("Saved"); } catch (e) { toast(e.message); }
    if (ctx.alive()) draw();
  };
  ctx.handlers({ save: () => save({ display_name: $("#nm").value.trim() }), host: () => save({ display_name: "" }), acc: h => save({ accent: h === "none" ? "" : h }),
    loc: async () => { await locationDialog(S.cache["/api/v1/settings"]?.location); if (ctx.alive()) draw(); } });
  draw(); try { await get("/api/v1/settings"); if (ctx.alive()) draw(); } catch (e) { toast(e.message); }
}
const ICON_THEMES = [["style", "Matches the style", "Each style's own icons"], ["tonal", "Tonal", "Colored symbols on a soft circle"], ["tiles", "Tiles", "White symbols on colored rounded squares"],
  ["circles", "Circles", "White symbols on colored circles"], ["outline", "Outline", "Colored symbols in a thin ring"], ["glyph", "Symbols only", "Just the colored symbol"], ["mono", "Monochrome", "Plain symbols in the text color"],
  ["glossy", "Glossy", "White symbols on shiny glossy tiles"]];
/** One icon drawn the way an icon style draws it, for the picker — shown beside each choice, all the time. */
function iconSample(theme, icon, tint) {
  const look = theme !== "style" ? theme : ({ liquid: "tiles", fluent: "glyph", breeze: "glyph", aero: "glossy" })[prefs.style] || "tonal";
  const base = "display:inline-grid;place-items:center;flex:none;";
  const st = { tonal: `width:30px;height:30px;border-radius:50%;color:${tint};background:color-mix(in srgb, ${tint} 16%, transparent)`,
    tiles: `width:28px;height:28px;border-radius:8px;color:#fff;background:${tint}`, circles: `width:28px;height:28px;border-radius:50%;color:#fff;background:${tint}`,
    outline: `width:28px;height:28px;box-sizing:border-box;border-radius:50%;color:${tint};border:1.5px solid ${tint}`, glyph: `width:26px;height:26px;color:${tint}`,
    mono: "width:26px;height:26px;color:var(--text)",
    glossy: `width:28px;height:28px;border-radius:6px;color:#fff;background:linear-gradient(rgba(255,255,255,.55),rgba(255,255,255,.12) 50%,transparent 50%,rgba(255,255,255,.2)),${tint};box-shadow:inset 0 0 0 1px rgba(0,0,0,.3),inset 0 1px 0 rgba(255,255,255,.55)` }[look];
  return `<span style="${base}${st}">${I(icon).replace('class="i', 'style="width:17px;height:17px;fill:currentColor" class="i')}</span>`;
}
/** Developer options: a frame-rate meter, the requests this browser made, glass outlines, the lens off, device details. */
export async function dev(ctx) {
  const draw = () => {
    const g = gpuInfo(), hz = Math.round(window.__novaHz || 60);
    const info = [["Nova", S.me?.api || "—"], ["Browser", navigator.userAgent.replace(/^Mozilla\/5\.0 /, "").slice(0, 120)], ["Screen", `${screen.width}×${screen.height} · ${devicePixelRatio}× · about ${hz} Hz`],
      ["Graphics", g.software ? "Drawn by the processor (no graphics card)" : g.renderer || "Graphics card"], ["Blur", g.blur ? "Supported" : "Not supported"], ["Glass bending", canRefract() ? "Supported" : "Not in this browser"]];
    const log = devLog.slice(0, 40);
    ctx.show(note("Tools for checking how Nova runs in this browser. They're only in this browser, and off unless you turn them on.")
      + sec("Drawing") + group(switchRow("Show frame rate", "A meter at the top: frames per second and the slowest frame", !!prefs.devFps, "fps")
        + switchRow("Show glass areas", "Outlines every glass surface", !!prefs.devBounds, "bounds") + switchRow("Glass without bending", "Turns the lens off, to compare how smooth it runs", !!prefs.devNoLens, "nolens"))
      + sec("This browser") + group(info.map(([k, v]) => row(k, { sub: v })).join("") + row("Copy these details", { sub: "For a bug report", icon: "copy", click: "copyinfo" }))
      + sec("Requests · newest first") + group(log.length ? log.map(e => `<div class="row"><code style="width:38px;color:${e.status >= 200 && e.status < 300 ? "var(--green)" : "var(--red)"}">${e.status || "—"}</code><div class="t"><b style="font:500 13px ui-monospace,monospace">${esc(e.method)} ${esc(e.path)}</b><small>${new Date(e.t).toLocaleTimeString()} · ${e.ms} ms</small></div></div>`).join("") : row("Nothing yet", { dis: true })),
      { title: "Developer options" });
  };
  ctx.handlers({
    fps: () => { prefs.devFps = !prefs.devFps; ctx.applyTheme(); draw(); }, bounds: () => { prefs.devBounds = !prefs.devBounds; ctx.applyTheme(); draw(); },
    nolens: () => { prefs.devNoLens = !prefs.devNoLens; ctx.applyTheme(); draw(); },
    copyinfo: async () => { const t = [...document.querySelectorAll(".row .t")].slice(0, 6).map(r => r.innerText.replace(/\n/, ": ")).join("\n"); try { await navigator.clipboard.writeText(t); toast("Copied"); } catch { toast("Couldn't copy"); } },
  });
  draw(); ctx.every(2000, () => draw());
}
const PACKS = { default: "Droplets", material: "Material", aero: "Aero", fluent: "Mica", breeze: "Breeze", liquid: "Glass" };
export async function appearance(ctx) {
  const draw = () => {
    const rnow = Math.max(25, Math.min(200, prefs["round_" + prefs.style] ?? prefs.round ?? 100));   // each look keeps its own roundness
    const t = prefs.theme, st = prefs.style, mat = document.documentElement.classList.contains("material"), look = mat ? "Material" : ({ liquid: "Glass", fluent: "Mica", breeze: "Breeze", aero: "Aero" })[st] || "Default";
    const autoMat = /Android/i.test(navigator.userAgent) && !/SamsungBrowser|SM-[A-Z0-9]|Xiaomi|Redmi|POCO|HUAWEI|HONOR|vivo|OPPO|realme/i.test(navigator.userAgent);
    ctx.show(`${sec("Style")}${group([["auto", "Automatic", `${autoMat ? "Material You" : "Default"} — picked to suit this device`], ["default", "Default", "Nova's own look: frosted glass and a soft glow"],
        ["material", "Material You", "Material 3 Expressive: tonal colors from the accent, bolder shapes, springy motion"],
        ["liquid", "Glass", "Clear glass that bends the light behind it at its edges"],
        ["fluent", "Mica", "A soft, tinted base with frosted menus and crisp, small corners"],
        ["breeze", "Breeze", "Flat and tidy: a thin frame, gentle corners, the accent as the focus color"],
        ["aero", "Aero", "Tinted frosted glass frames with a glossy shine and glowing buttons"]].map(([k, l, d]) => row(l, { sub: d, end: radio(st === k), click: "style:" + k })).join(""))}
      ${["liquid", "fluent", "breeze", "aero"].includes(st) ? group((st === "liquid" ? `<div class="range-row"><div class="h"><span>Light splitting</span><span id="prisml">${prefs.prism ?? 50}%</span></div><input type="range" min="0" max="100" value="${prefs.prism ?? 50}" data-range="prism" style="--p:${prefs.prism ?? 50}%"></div>` : "")
        + (st !== "breeze" ? `<div class="range-row"><div class="h"><span>Glass clarity</span><span id="glassl">${prefs.glass ?? 60}%</span></div><input type="range" min="0" max="100" value="${prefs.glass ?? 60}" data-range="glass" style="--p:${prefs.glass ?? 60}%"></div>` : "")
        + `<div class="range-row"><div class="h"><span>Corner roundness</span><span id="roundl">${rnow}%</span></div><input type="range" min="25" max="200" value="${rnow}" data-range="round_${st}" style="--p:${(rnow - 25) / 175 * 100}%"></div>`) : ""}
      ${!mat && !["liquid", "fluent", "breeze", "aero"].includes(st) ? group([["dTrans", "Transparency", 0, 85, 0, "%"], ["dFrost", "Frost", 0, 40, 26, "px"]].map(([k, l, a, b, d, u]) =>
          `<div class="range-row"><div class="h"><span>${l}</span><span id="${k}l">${prefs[k] ?? d}${u}</span></div><input type="range" min="${a}" max="${b}" value="${prefs[k] ?? d}" data-range="${k}" style="--p:${((prefs[k] ?? d) - a) / (b - a) * 100}%"></div>`).join("")) : ""}
      ${st === "liquid" && !canRefract() ? note("This browser shows Glass frosted, without the bending at the edges — that part needs a Chromium-based browser.") : ""}
      ${st === "liquid" ? note("Glass is the most demanding look: on some devices it can make things warm, use more battery or stutter. Lighter effects (below) keeps the look with less work.") : ""}
      ${sec("Theme")}${group([["system", "Same as this device"], ["light", `${look} light`], ["dark", `${look} dark`]].map(([k, l]) => row(l, { end: radio(t === k), click: "theme:" + k })).join(""))}
      ${group(switchRow("Reduce motion", "Simple fades instead of slides and bounces", prefs.reduceMotion, "motion", { blue: false })
        + `<div class="range-row"><div class="h"><span>Animation speed</span><span id="speedl">${speedText(prefs.animSpeed ?? 100)}</span></div><input type="range" min="25" max="300" step="5" value="${prefs.animSpeed ?? 100}" data-range="animSpeed" style="--p:${((prefs.animSpeed ?? 100) - 25) / 275 * 100}%"></div>`
        + switchRow("Reduce transparency", "Solid surfaces instead of glass — easier to read, lighter to draw", !!prefs.reduceTransparency, "solid", { blue: false }))}
      ${sec("Sounds & haptics")}${group(switchRow("Sounds", "Clicks, switches, pages, pop-ups and windows — each look has its own sound pack", SND.soundsOn(), "snd")
        + (SND.soundsOn() ? row("Sound pack", { sub: (prefs.soundPack || "auto") === "auto" ? `Match the look (${PACKS[SND.packId()]})` : PACKS[prefs.soundPack] || "Match the look", blue: true, icon: "tune", click: "spack" })
          + `<div class="range-row"><div class="h"><span>Volume</span><span id="voll">${prefs.soundVol ?? 60}%</span></div><input type="range" min="0" max="100" step="5" value="${prefs.soundVol ?? 60}" data-range="soundVol" style="--p:${prefs.soundVol ?? 60}%"></div>`
          + switchRow("Alert sounds", "A chime when something needs you — a more urgent one when it's serious", prefs.alertSounds ?? true, "asnd")
          // which kinds play: folded away (most people never change them) — keep things simple until you want the detail
          + expand("Which sounds", Object.entries(SND.KINDS).filter(([k]) => SND.kindOn(k)).map(([, [l]]) => l).join(", ") || "None", "tune",
              Object.entries(SND.KINDS).map(([k, [l, d]]) => switchRow(l, d, SND.kindOn(k), "skind:" + k, { blue: false })).join("")) : "")
        + switchRow("Ambience · Beta", "Soft, slowly changing music under everything, in the mood of the look you picked", !!prefs.ambience, "amb")
        + (prefs.ambience ? row("Track", { sub: (prefs.ambTrack || "auto") === "auto" ? `Match the look (${AMB.TRACKS[AMB.trackId()]})` : AMB.TRACKS[prefs.ambTrack], blue: true, icon: "tune", click: "ambtrack" }) + `<div class="range-row"><div class="h"><span>Ambience volume</span><span id="ambl">${prefs.ambVol ?? 35}%</span></div><input type="range" min="0" max="100" step="5" value="${prefs.ambVol ?? 35}" data-range="ambVol" style="--p:${prefs.ambVol ?? 35}%"></div>` : "")
        + ("vibrate" in navigator ? switchRow("Vibration", "A light buzz with taps, switches and alerts (on phones)", prefs.haptics ?? true, "hap") : ""))}
      ${sec("Controller")}${group(row("Button hints", { sub: `${({ auto: "Match the controller", xbox: "Xbox (A B X Y)", ps: "PlayStation (✕ ○ □ △)", generic: "Plain (1 2 3 4)" })[prefs.padGlyphs || "auto"]}${prefs.padHints === false ? " · hidden" : ""} — shown in the corner while you use a game controller`, blue: true, icon: "tune", click: "padg" }))}
      ${sec("Colors")}${group(row("Accent color", { sub: prefs.accentLocal ? "This browser's own color" : `The server's color${S.overview?.server?.accent ? "" : " (Nova blue)"} — tap to pick your own`, blue: !!prefs.accentLocal,
            end: `<span class="swatch" style="background:${esc(prefs.accentLocal || S.overview?.server?.accent || "#3e91ff")}"></span>`, click: "accent" })
          + `<div class="swatches pad">${ACCENTS.filter(Boolean).map(c => `<button class="swatch press${prefs.accentLocal === c ? " on" : ""}" style="background:${c}" data-act="acc:${c}" aria-label="${c}"></button>`).join("")}</div>`
          + (prefs.accentLocal ? row("Use the server's color", { icon: "refresh", click: "acc:" }) : "")
          + `<div class="range-row"><div class="h"><span>Color strength${mat ? "" : " (Material You)"}</span><span id="tintl">${prefs.tint ?? 50}%</span></div>${`<input type="range" min="0" max="100" value="${prefs.tint ?? 50}" data-range="tint" style="--p:${prefs.tint ?? 50}%">`}</div>`)}
      ${sec("Wallpaper")}${group(switchRow("Living wallpaper · Beta", wallHas ? "An animated wallpaper for each look, in your accent color — your picture shows instead while it's set" : "An animated wallpaper for each look, in your accent color: Aero's ribbons of light, Mica's bloom, Breeze's waves, Glass's liquid color and more", !!prefs.livingWall, "livewall")
        + (prefs.livingWall ? row("Scene", { sub: (prefs.livingScene || "auto") === "auto" ? `Match the look (${LV.SCENES[LV.sceneId()]})` : prefs.livingScene === "media" ? "Your own GIF or video" : LV.SCENES[prefs.livingScene], blue: true, icon: "image", click: "lvscene" })
          + (prefs.livingScene === "media" ? row("Choose a GIF or video", { sub: "Kept in this browser only (up to 60 MB)", icon: "image", blue: true, click: "lvmedia" }) : "")
          + row("Color", { sub: prefs.livingAccent ? "Its own color" : "Same as the accent color", blue: !!prefs.livingAccent, end: `<span class="swatch" style="background:${esc(prefs.livingAccent || "var(--blue)")}"></span>`, click: "lvcolor" })
          + [["livingSpeed", "Speed", 10, 300, 100, "%"], ["livingBlur", "Blur", 0, 40, 0, "px"], ["livingTint", "Tint", 0, 80, 0, "%"], ["livingFps", "Frame rate limit", 10, 120, 30, " fps"]].map(([k, l, a, b, d, u]) =>
              `<div class="range-row"><div class="h"><span>${l}</span><span id="${k}l">${prefs[k] ?? d}${u}</span></div><input type="range" min="${a}" max="${b}" step="${k === "livingFps" ? 5 : 1}" value="${prefs[k] ?? d}" data-range="${k}" style="--p:${((prefs[k] ?? d) - a) / (b - a) * 100}%"></div>`).join("") : ""))}
      ${wallHas ? group(`<div class="range-row"><div class="h"><span>Blur</span><span id="blurl">${prefs.wallBlur ?? 16}px</span></div><input type="range" min="0" max="40" value="${prefs.wallBlur ?? 16}" data-range="wallBlur" style="--p:${(prefs.wallBlur ?? 16) / 40 * 100}%"></div>
            <div class="range-row"><div class="h"><span>Dim</span><span id="diml">${prefs.wallDim ?? 35}%</span></div><input type="range" min="0" max="90" value="${prefs.wallDim ?? 35}" data-range="wallDim" style="--p:${(prefs.wallDim ?? 35) / 90 * 100}%"></div>`
            + row("Change the picture", { icon: "image", blue: true, click: "wall" }) + row("Remove the wallpaper", { icon: "del", tint: "var(--red)", click: "unwall" }))
          : group(row("Choose a picture", { sub: "Behind every page, blurred and dimmed as you like. Kept in this browser only.", icon: "image", blue: true, click: "wall" })
            + row("Background", { sub: WALLS.find(w => w[0] === (prefs.lookBg || ""))?.[1] || "This look's own", icon: "palette", click: "lookbg" }))}
      ${group(switchRow("Top and bottom fades", "The soft tint at the top and bottom of the screen, so the page fades out under the bars", prefs.scrims !== false, "scrims", { blue: false })
        + (prefs.scrims !== false ? row("Fade color", { sub: prefs.scrimColor ? "Your own color" : "The page's color", blue: !!prefs.scrimColor, end: `<span class="swatch" style="background:${esc(prefs.scrimColor || "var(--bg)")}"></span>`, click: "scrimcolor" }) : ""))}
      ${sec("Language")}${group(row("Language", { sub: prefs.lang && prefs.lang !== "auto" ? (LANGS.find(l => l[0] === prefs.lang)?.[1] || prefs.lang) : `Same as this browser (${LANGS.find(l => l[0] === pickLang("auto"))?.[1] || "English"})`, blue: true, icon: "article", click: "lang" }))}
      ${note("Nova's own words are translated (by machine, so some may read oddly — tell us on GitHub). Messages written by the server, like alert details, stay in English for now.")}
      ${(() => { const g = gpuInfo(); if (!g.software && g.blur) return "";
        return `<div class="hint" style="margin:10px var(--gutter);border:1px solid color-mix(in srgb,var(--amber) 50%,transparent)"><b style="color:var(--amber)">${g.software ? "This browser isn't using the graphics card" : "This browser can't blur"}</b>
          <p class="muted" style="margin:6px 0 0">${g.software ? "Glass, blur and the live graphs are drawn by the processor instead, so they can stutter and use more power. Turn on hardware acceleration and restart the browser — Chrome or Edge: Settings → System → “Use graphics acceleration when available”. Firefox: Settings → Performance. Until then, Lighter effects is on automatically." : "Frosted glass shows as plain, see-through surfaces here. Update the browser, or turn on Reduce transparency for solid surfaces."}</p></div>`; })()}
      ${sec("App icon")}<div class="group glass wicons">${(WEB_ICONS || [["default", "Default"]]).map(([k, l]) => `<button class="wicon press${(prefs.webIcon || "default") === k ? " on" : ""}" data-act="wicon:${k}"><img data-wicon="${k}" alt=""><small>${esc(l)}</small></button>`).join("")}</div>
      ${group(row("Icon shape", { sub: `${SHAPES[prefs.iconShape || "rounded"]} — Nova's own icon here and your apps' icons inside Nova`, blue: true, icon: "apps", click: "ishape" }))}
      ${note("This browser's tab icon, and the icon Nova gets when you add it to an iPhone or iPad home screen. The phone app has its own (Appearance → App icon in the app).")}
      ${sec("Icons")}${group(ICON_THEMES.map(([k, l, d]) => `<div class="row click" data-act="icon:${k}"><div class="t"><b>${esc(l)}</b><small>${esc(d)}</small></div>
          <span class="icprev" aria-hidden="true">${[["status", "#3ecf6e"], ["backup", "var(--blue)"], ["del", "var(--amber)"]].map(([ic, c]) => iconSample(k, ic, c)).join("")}</span>${radio((prefs.iconTheme || "style") === k)}</div>`).join(""))}
      ${sec("Lighter effects")}${segmented(["Automatic", "On", "Off"], ["auto", "on", "off"].indexOf(prefs.lite || "auto"), "lite")}
      ${note("No frosted-glass blur or soft shadows — much smoother on older computers and phones. Automatic turns it on when this device looks slow.")}
      ${sec("Home")}${group(row("Home layout", { sub: homeOrder().filter(id => prefs[HOME_SECTIONS.find(s => s[0] === id)[3]]).map(id => HOME_SECTIONS.find(s => s[0] === id)[1]).join(" · ") || "Just the header", blue: true, icon: "dash", click: "go:edit-home" })
        + row("Choose shortcuts", { sub: homeChips().map(s => s.label).join(", ") || "None", blue: true, icon: "tune", click: "go:edit-shortcuts" })
        + row("Start page", { sub: `Search, stats, apps and bookmarks · Nova opens on ${({ home: "Home", start: "the start page", status: "Status", apps: "Apps", inbox: "the Inbox", dashboard: "the dashboard" })[prefs.startRoute || "home"]}`, blue: true, icon: "home", click: "go:start-edit" })
        + row("Navigation pill", { sub: navTabs().map(s => s.label).join(", ") + " · along the bottom, or down the left on wide screens", blue: true, icon: "viewday", click: "go:edit-tabs" }))}
      ${note("These choices are saved in this browser. Each server also has its own name and accent color (Settings → Server).")}`, { title: "Appearance" });
    if (!WEB_ICONS) webIcons().then(l => { WEB_ICONS = l; if (ctx.alive()) draw(); }).catch(() => {});
    $$("[data-wicon]", ctx.root).forEach(async im => { try { im.src = svgUrl(await iconSvg(im.dataset.wicon)); } catch {} });
    wireCommon(ctx.root, { onSeg: (k, i) => { if (k === "lite") { prefs.lite = ["auto", "on", "off"][i]; ctx.applyTheme(); draw(); } },
      onRangeInput: (k, v) => {                                   // live while dragging
        prefs[k] = v; ctx.applyTheme();
        if (k === "animSpeed" && $("#speedl")) $("#speedl").textContent = speedText(v);
        if (/^living/.test(k)) { const u = { livingSpeed: "%", livingBlur: "px", livingTint: "%", livingFps: " fps" }[k]; if ($("#" + k + "l")) $("#" + k + "l").textContent = v + u; LV.update(); }
        if (k === "ambVol") { if ($("#ambl")) $("#ambl").textContent = v + "%"; AMB.update(); }
        if (k === "soundVol") { if ($("#voll")) $("#voll").textContent = v + "%"; clearTimeout(volT); volT = setTimeout(() => SND.play("tap"), 60); }
        const l = { tint: ["tintl", "%"], wallBlur: ["blurl", "px"], wallDim: ["diml", "%"], glass: ["glassl", "%"], round: ["roundl", "%"], prism: ["prisml", "%"], dTrans: ["dTransl", "%"], dFrost: ["dFrostl", "px"] }[/^round_/.test(k) ? "round" : k]; if (l && $("#" + l[0])) $("#" + l[0]).textContent = v + l[1];
      } });
  };
  let wallHas = false, volT = 0;
  const checkWall = async () => { wallHas = !!(await kv("wallpaper").catch(() => null)); if (ctx.alive()) draw(); };
  const setAccent = c => {
    prefs.accentLocal = c || "";
    const acc = c || S.overview?.server?.accent;
    if (/^#[0-9a-f]{6}$/i.test(acc || "")) document.documentElement.style.setProperty("--blue", acc); else document.documentElement.style.removeProperty("--blue");
    ctx.applyTheme(); draw();
  };
  ctx.handlers({
    icon: k => { prefs.iconTheme = k; ctx.applyTheme(); draw(); },
    lvscene: async () => {
      const v = await choose("Scene", [["auto", "Match the look", "Changes with the style you pick"], ...Object.entries(LV.SCENES), ["media", "Your own GIF or video"]], prefs.livingScene || "auto");
      if (!v) return; prefs.livingScene = v; if (v === "media" && !(await kv("livemedia").catch(() => null))) return ctx.run("lvmedia"); LV.update(); draw();
    },
    lvmedia: () => {
      const inp = document.createElement("input"); inp.type = "file"; inp.accept = "image/gif,image/webp,image/apng,video/mp4,video/webm";
      inp.onchange = async () => { const f = inp.files?.[0]; if (!f) return;
        if (f.size > 60e6) return toast("That file is over 60 MB — pick a shorter or smaller one");
        await kv("livemedia", f); prefs.livingScene = "media"; LV.update(); draw(); toast("Living wallpaper set"); };
      inp.click();
    },
    lvcolor: async () => {
      const v = await choose("Living wallpaper color", [["", "Same as the accent color"], ["pick", "Pick a color…"]], prefs.livingAccent ? "pick" : "");
      if (v == null) return; if (v === "") prefs.livingAccent = ""; else { const c = await colorPicker(prefs.livingAccent || "#3e91ff"); if (!c) return; prefs.livingAccent = c; }
      LV.update(); draw();
    },
    ambtrack: async () => {
      const v = await choose("Ambience", [["auto", "Match the look", "Changes with the style you pick"], ...Object.entries(AMB.TRACKS)], prefs.ambTrack || "auto");
      if (!v) return; prefs.ambTrack = v; AMB.update(); draw();
    },
    padg: async () => {
      const v = await choose("Controller button hints", [["auto", "Match the controller"], ["xbox", "Xbox", "A B X Y"], ["ps", "PlayStation", "✕ ○ □ △"], ["generic", "Plain", "1 2 3 4"], ["off", "Don't show them"]], prefs.padHints === false ? "off" : prefs.padGlyphs || "auto");
      if (!v) return; if (v === "off") prefs.padHints = false; else { prefs.padHints = true; prefs.padGlyphs = v; } draw();
    },
    livewall: () => { prefs.livingWall = !prefs.livingWall; ctx.applyTheme(); draw(); },
    scrims: () => { prefs.scrims = prefs.scrims === false; ctx.applyTheme(); draw(); },
    scrimcolor: async () => {
      const v = await choose("Fade color", [["", "The page's color", "Follows your look and light or dark"], ["pick", "Pick a color…"]], prefs.scrimColor ? "pick" : ""); if (v == null) return;
      if (v === "pick") { const c = await colorPicker(prefs.scrimColor || "#000000"); if (!c) return; prefs.scrimColor = c; } else prefs.scrimColor = "";
      ctx.applyTheme(); draw();
    },
    lookbg: async () => {
      // a small gallery: each look's own background, shown as it would look here
      const cur = prefs.lookBg || "";
      const p = dialog("Background", "Every look's built-in background — pick one for any look.", [{ label: "Close", value: null }],
        `<div class="pad"><div class="wpgal">${WALLS.map(([k, l, wp]) => `<button class="press${k === cur ? " on" : ""}" data-wp="${k}"><span class="wpv"><i style="background-image:${wp}"></i></span>${esc(l)}</button>`).join("")}</div></div>`);
      document.querySelectorAll("#sheet .wpv").forEach(v => { v.style.aspectRatio = `${innerWidth} / ${innerHeight}`; v.style.setProperty("--s", v.clientWidth / innerWidth); });      // a whole screen, shrunk
      document.querySelectorAll("#sheet [data-wp]").forEach(b => b.onclick = () => { prefs.lookBg = b.dataset.wp; ctx.applyTheme(); document.querySelectorAll("#sheet [data-wp]").forEach(x => x.classList.toggle("on", x === b)); });
      await p; draw();
    },
    amb: () => { prefs.ambience = !prefs.ambience; AMB.update(); draw(); },
    snd: () => { prefs.sounds = !SND.soundsOn(); draw(); if (prefs.sounds) SND.play("toggleOn", { force: true }); },
    skind: k => { const o = { ...(prefs.sndOff || {}) }; o[k] = !o[k]; prefs.sndOff = o;     // in place: the dropdown stays open
      $(`[data-act="skind:${k}"] .sw`, ctx.root)?.classList.toggle("on", !o[k]); },
    asnd: () => { prefs.alertSounds = !(prefs.alertSounds ?? true); draw(); if (prefs.alertSounds) SND.play("info"); },
    hap: () => { prefs.haptics = !(prefs.haptics ?? true); draw(); SND.buzz("success"); },
    spack: async () => {
      const v = await choose("Sound pack", [["auto", "Match the look", "Changes with the style you pick"], ...Object.entries(PACKS).map(([k, n]) => [k, n])], prefs.soundPack || "auto");
      if (!v) return; prefs.soundPack = v; draw(); SND.play("info", { force: true });       // a taste of it
    },
    ishape: async () => { const v = await choose("Icon shape", Object.entries(SHAPES), prefs.iconShape || "rounded"); if (!v) return;
      prefs.iconShape = v; document.documentElement.dataset.icshape = v; await applyWebIcon(); draw(); },
    wicon: async k => { prefs.webIcon = k; await applyWebIcon(); draw(); },
    accent: async () => { const c = await colorPicker(prefs.accentLocal || S.overview?.server?.accent || "#3e91ff"); if (c) setAccent(c); },
    acc: c => setAccent(c && /^#[0-9a-f]{6}$/i.test(c) ? c : ""),
    wall: () => {
      const inp = document.createElement("input"); inp.type = "file"; inp.accept = "image/*";
      inp.onchange = async () => {
        const f = inp.files?.[0]; if (!f) return;
        try {        // shrink it (the longest side 2560 px, JPEG) so it stays light in this browser's storage
          const bmp = await createImageBitmap(f), k = Math.min(1, 2560 / Math.max(bmp.width, bmp.height));
          const cv = document.createElement("canvas"); cv.width = Math.round(bmp.width * k); cv.height = Math.round(bmp.height * k);
          cv.getContext("2d").drawImage(bmp, 0, 0, cv.width, cv.height);
          const blob = await new Promise(r => cv.toBlob(r, "image/jpeg", .86));
          await kv("wallpaper", blob); ctx.applyTheme(); toast("Wallpaper set"); checkWall();
        } catch { toast("That picture couldn't be opened"); }
      };
      inp.click();
    },
    unwall: async () => { await kv("wallpaper", null).catch(() => {}); ctx.applyTheme(); checkWall(); },
    lang: async () => {
      const v = await choose("Language", [["auto", "Same as this browser"], ...LANGS.map(([k, n]) => [k, n])], prefs.lang || "auto");
      if (v && v !== (prefs.lang || "auto")) { prefs.lang = v; location.reload(); }
    },
    theme: k => { prefs.theme = k; ctx.applyTheme(); draw(); },
    style: k => { prefs.style = k; ctx.applyTheme(); ctx.refreshNav(); draw(); },
    motion: () => { prefs.reduceMotion = !prefs.reduceMotion; ctx.applyTheme(); draw(); },
    solid: () => { prefs.reduceTransparency = !prefs.reduceTransparency; ctx.applyTheme(); draw(); },
  });
  draw(); checkWall();
}
export async function editHome(ctx) {
  const draw = () => {
    ctx.show(`${sec("Top to bottom · hold and drag to reorder")}
      <div class="group glass rl" id="rl">${homeOrder().map(id => { const [, l, ic, key] = HOME_SECTIONS.find(s => s[0] === id), on = prefs[key];
        return `<div class="row" data-key="${id}"><span class="handle">${I("drag")}</span><span style="color:${on ? "var(--blue)" : "var(--sub)"};display:flex">${I(ic)}</span><div class="t"><b style="color:${on ? "var(--text)" : "var(--sub)"}">${l}</b></div><button data-noreorder data-act="tog:${key}">${sw(on)}</button></div>`; }).join("")}</div>
      ${note("The name, status line and alerts always stay at the top. Hold the shortcut bar on Home to change its buttons.")}
      ${links([["Choose shortcuts", "go:edit-shortcuts"], ["Reset Home", "reset"]])}`, { title: "Home layout" });
    reorderable($("#rl"), keys => { prefs.homeOrder = keys; draw(); });
  };
  ctx.handlers({ tog: k => { prefs[k] = !prefs[k]; draw(); }, reset: () => { ["homeOrder", "homeHero", "homeServers", "homeShortcuts", "homeStats", "homeRecs"].forEach(k => localStorage.removeItem("nova." + k)); draw(); } });
  draw();
}
function editList(ctx, { title, label, all, key, max, fixed, preview, resetLabel }) {
  const draw = () => {
    const ids = prefs[key], more = all.filter(s => !ids.includes(s.id) && (!s.feature || has(s.feature)));
    ctx.show(`${preview()}
      ${sec(label)}<div class="group glass rl" id="rl">${ids.length ? ids.map(id => { const s = all.find(x => x.id === id); return s ? `<div class="row" data-key="${id}"><span class="handle">${I("drag")}</span><span style="color:var(--blue);display:flex">${I(s.icon)}</span><div class="t"><b>${esc(s.label)}</b></div>${id === fixed ? `<span class="end">Always</span>` : `<button data-noreorder data-act="rm:${id}" style="color:var(--red)">${I("remc")}</button>`}</div>` : ""; }).join("") : `<div class="row"><div class="t muted">Nothing here yet.</div></div>`}</div>
      ${sec(ids.length >= max ? `Add · the bar is full (${max} max)` : "Add")}${group(more.map(s => row(s.label, { icon: s.icon, click: "add:" + s.id, end: `<span style="color:${ids.length < max ? "var(--green)" : "var(--divider)"}">${I("addc")}</span>` })).join("") || row("Everything is already there", { dis: true }))}
      ${links([[resetLabel, "reset"]])}`, { title });
    reorderable($("#rl"), keys => { prefs[key] = keys; draw(); });
  };
  ctx.handlers({
    rm: id => { if (key === "navTabs" && prefs[key].length <= 2) return toast("Keep at least two tabs"); prefs[key] = prefs[key].filter(x => x !== id); draw(); ctx.refreshNav(); },
    add: id => { if (prefs[key].length >= max) return toast(`Remove one first — ${max} fit`); prefs[key] = [...prefs[key], id]; draw(); ctx.refreshNav(); },
    reset: () => { localStorage.removeItem("nova." + key); draw(); ctx.refreshNav(); },
  });
  draw();
}
export const editShortcuts = ctx => editList(ctx, { title: "Home shortcuts", label: "On Home · hold and drag to reorder", all: SHORTCUTS, key: "homeChips", max: 5, resetLabel: "Reset to the default shortcuts",
  preview: () => { const l = homeChips(); return `<div style="padding:4px 0 6px">${l.length ? `<div class="pillbar glass${l.length >= 5 ? " five" : ""}">${l.map(s => `<button>${I(s.icon)}<span class="l">${esc(l.length >= 5 ? s.short : s.label)}</span></button>`).join("")}</div>` : note("No shortcuts — add some below.")}</div>`; } });
export const editTabs = ctx => {
  if (!prefs.navTabs.includes("home")) prefs.navTabs = ["home", ...prefs.navTabs];
  editList(ctx, { title: "Navigation pill", label: "Tabs · hold and drag to reorder", all: NAV_TABS, key: "navTabs", max: 12, fixed: "home", resetLabel: "Reset the navigation pill",
    preview: () => { const t = navTabs(), i = t.findIndex(x => x.id === "home"); return `<div style="display:flex;justify-content:center;padding:4px 0 6px"><div class="nav glass" style="position:static;transform:none"><div class="items"><span class="bead" style="transform:translateX(${i * 80}px)"></span>${t.map(x => `<button aria-label="${esc(x.label)}">${I(x.icon)}</button>`).join("")}</div></div></div>${note("On a wide screen or in landscape the same pill stands down the left edge. Back from any tab goes to Home. If you remove Menu, it's still in Home ⋮.")}`; } });
};
export async function about(ctx) {
  const s = S.overview?.server || {};
  ctx.show(`<div class="center" style="padding:20px 0"><div style="display:inline-block">${(await import("./ui.js")).logo(72)}</div><div style="font-size:34px;font-weight:700">Nova</div><div class="muted">Nova web</div></div>
    ${group(row("Version", { sub: `Nova ${S.me?.api || S.cache["/api/v1/server/update"]?.installed || ""} · check for updates`, blue: true, icon: "restart", click: "go:updates" })
      + row("Developer options", { sub: "Frame rate, this browser's requests, glass layers, device details", icon: "tune", tint: "var(--sub)", click: "go:dev" }))}
    ${sec("Server")}${group(row("Name", { sub: s.name }) + row("Board", { sub: s.board }) + row("CPU", { sub: s.cpu }) + row("Memory", { sub: s.ram_gb ? s.ram_gb + " GB" : "" }) + row("Kernel", { sub: s.kernel }) + row("Up for", { sub: s.uptime_s ? uptime(s.uptime_s) : "" }))}
    ${sec("Security")}${group(row("Every request is signed", { sub: "By this browser's own key — it can't be copied out — time-stamped and single-use" }) + row("Risky actions need your phone", { sub: "Shells, stopping things, installs, unmounting, power — approved with your fingerprint" })
      + row("At home: encrypted", { sub: "HTTPS to the server's own certificate" }) + row("Outside home: Cloudflare Access", { sub: "Strangers are stopped before they reach the server" }))}
    ${group(row("Questions", { sub: "Is it safe, what it connects to, how to undo things", blue: true, icon: "help", click: "go:faq" })
      + `<a class="row press" href="https://github.com/dash1101/Nova/issues/new?template=feature_request.yml" target="_blank" rel="noopener"><span class="ri" style="color:var(--blue);background:color-mix(in srgb, var(--blue) 16%, transparent)">${I("add")}</span><div class="t"><b>Request a feature</b><small>Tell us what Nova should do — on GitHub</small></div></a>`)}
    ${sec("Project")}${group(`<a class="row click" href="https://github.com/dash1101/Nova" target="_blank" rel="noopener" style="color:inherit;text-decoration:none"><div class="t"><b>Nova by dash1101</b><small>Open source · AGPL-3.0 · github.com/dash1101/Nova</small></div></a><a class="row click" href="https://dash1101.github.io/Nova/privacy.html" target="_blank" rel="noopener" style="color:inherit;text-decoration:none"><div class="t"><b>Privacy policy</b><small>Nova collects nothing — this page talks only to your server</small></div></a>`)}`, { title: "About" });
}
/** Questions people ask before trusting Nova with their server — the same answers as the website's FAQ. */
export const FAQ = [
  ["Safety and privacy", [
    ["Can Nova read or send away my data?", "No. Nova runs on your own server and the app only ever talks to that server. There's no Nova account, no Nova cloud and no analytics or tracking of any kind. Your files, photos and settings never pass through anyone else. The code is open source, so anyone can check this for themselves."],
    ["What does Nova connect to on the internet?", "Only the services you'd expect, and never with your data. GitHub, to check for and download signed Nova updates. Your system's package mirrors and container registries (Docker Hub, GitHub's registry and others), when you install or update programs and apps. Cloudflare and Tailscale, only if you set them up for remote access. Discord, only if you add a webhook for alerts. Docker Hub's search, when you search the app store. A speed-test server, when you run a speed test. A public icon set, for app icons. DuckDuckGo, for search suggestions on the Start page as you type (you can turn them off)."],
    ["Is it safe to have on my server?", "Nova's API runs as its own unprivileged user in a systemd sandbox. The only part with root rights is a small helper that can run a fixed list of commands, and each one checks its input. Your containers, files and SSH settings are left alone unless you ask Nova to change them, and the risky changes also need your fingerprint."],
    ["What can someone do with a stolen phone or browser?", "Very little. The phone's key only works after the phone is unlocked, and anything risky needs your fingerprint too. A browser can never do anything risky by itself: it asks an admin phone first. Remove a lost device in Users & devices, or with sudo nova remove NAME, and its key stops working instantly."],
    ["How is Nova tested?", "Every release goes through an automated security test of more than 400 checks against a real server: forged and replayed requests, stolen keys, path tricks, oversized requests, brute force, and more. Releases are signed, and the server only installs updates whose signature checks out."]]],
  ["Undoing things", [
    ["How do I uninstall Nova completely?", "sudo apt remove nova-server stops Nova and removes it. Your containers, apps and data stay exactly as they are. sudo apt purge nova-server also deletes Nova's settings and the list of paired devices. If Nova put apps on your Cloudflare account, run sudo nova cloudflare undo first: it lists what it added and asks before taking anything off."],
    ["Can I undo what Nova changed on Cloudflare?", "Yes. Nova keeps a record of exactly what it added, and only ever removes those things. sudo nova cloudflare list shows them and sudo nova cloudflare undo takes them off. An address you had before Nova protected it keeps its login unless you say otherwise."],
    ["An update broke a container. Can I go back?", "Yes. When Nova updates a container it keeps the version it replaced. If the new one won't start, Nova puts the old one back by itself. Any time later, open Containers → the container → Troubleshoot → Go back to the previous version."],
    ["I ignored an alert or archived something by mistake.", "Ignored alerts that are still going on stay in the Inbox under “Ignored”, with a Stop ignoring button. Archived events are kept on the server, and you can move them back from Inbox → Archive."],
    ["Can I go back to an older Nova version?", "Yes. Download that version's .deb from the releases page and install it with sudo apt install ./nova-server_VERSION_all.deb (apt asks you to confirm going back). Your settings and paired devices stay on the server."]]],
  ["Using it", [
    ["How do I set up Nova on several computers on the same network?", "Install Nova on each one and run sudo nova setup on each. Pair your phone with every server: run sudo nova add on the next server, then in the app tap the server name at the top of Home → Add a server, and scan its code. The app finds other Nova servers on your Wi-Fi by itself (udp port 8496), and you switch between them from the same menu. Each server keeps its own paired devices. Here in Nova web, add the others under Servers to see and control them all from one page."],
    ["Can several servers share one Cloudflare account, token and domain?", "Yes. On the first server open Settings → Cloudflare → Show the token (it needs your fingerprint), and paste it into Settings → Cloudflare on the next one. Everything a server adds is labeled with its name, each server only changes or removes its own entries, and you can see what the others added. Give each server its own app addresses — if a name is taken by another server, Nova says which one and suggests a free name. Each server needs its own Cloudflare Tunnel: one tunnel running on two machines would send visits to either one, so Nova refuses to publish on it."],
    ["Can I control several servers from one place?", "Yes. Add your other servers under Servers, then pick one to control it from the same page. Each server checks your browser itself, so the first time you approve your browser on that server, once."],
    ["Do I need to open ports on my router?", "No. At home the app talks to the server directly. For access from anywhere, Nova uses a Cloudflare Tunnel with Cloudflare Access in front, or Tailscale if you already use it."],
    ["What happens if I lose my phone?", "Remove it in Users & devices from another phone, or run sudo nova remove NAME on the server. Its keys can never be used again."]]],
];
export async function faq(ctx) {
  ctx.show(FAQ.map(([title, qs]) => sec(title) + group(qs.map(([q, a]) => expand(q, "", "", esc(a))).join(""))).join("")
    + group(`<a class="row press" href="https://github.com/dash1101/Nova/issues/new?template=feature_request.yml" target="_blank" rel="noopener"><span class="ri" style="color:var(--blue);background:color-mix(in srgb, var(--blue) 16%, transparent)">${I("add")}</span><div class="t"><b>Request a feature</b><small>Tell us what Nova should do — on GitHub</small></div></a>`)
    + note("Something else? Ask on github.com/dash1101/Nova."), { title: "Questions" });
  wireCommon(ctx.root);
}
export async function guide(ctx) {
  const step = (n, t, b, cmd) => group(`<div style="padding:20px"><div style="display:flex;align-items:center;gap:12px"><span style="width:30px;height:30px;border-radius:50%;background:var(--blue);color:#fff;display:grid;place-items:center;font-weight:700">${n}</span><b style="font-size:18px;font-weight:600">${t}</b></div><p class="muted" style="margin:8px 0 0;font-size:15px">${b}</p>${cmd ? `<div class="code-block">${esc(cmd)}</div>` : ""}</div>`);
  ctx.show(`${note("Nova has two parts: the app (phone, or this web version), and a small server package on the Linux machine you want to control (Debian or Ubuntu).")}
    ${step(1, "Install the server package", "On the server, install the Nova package (nova-server_….deb) — or build it from the source code.", "sudo apt install ./nova-server_*.deb\n# or from source:\ngit clone <repo> && cd nova && sudo ./server/install.sh")}
    ${step(2, "Run the setup wizard", "It finds your home network, asks a few questions (press Enter for the suggested answers) and starts Nova.", "sudo nova-setup")}
    ${step(3, "Pair your phone", "On home Wi-Fi, show a pairing code on the server and scan it with the Nova app. The code works once and expires in 10 minutes.", "sudo nova add")}
    ${step(4, "Optional: use it away from home", "Without a VPN, put Nova behind Cloudflare Access (free) — docs/REMOTE.md walks you through it. For this web version away from home, add an Allow policy for your email to the same Access application.")}
    ${step(5, "Optional: other people, tablets, browsers", "In the app: Menu → Users & devices — invite phones as Admin or View only, and approve browsers (open https://&lt;server&gt;:8495, choose “Get a code”).")}
    ${note("Security in a sentence: every request is signed by a key the device can't give away, and anything risky also needs your fingerprint on a phone.")}
    ${group(row("Questions", { sub: "Is it safe, what it connects to, how to undo things", blue: true, icon: "help", click: "go:faq" }))}`, { title: "Setup guide" });
}
export async function terminal(ctx) {
  ctx.show(`<div class="center" style="padding:18px 0"><div style="width:96px;height:96px;border-radius:30px;background:#0b0b0d;margin:0 auto 12px;display:grid;place-items:center;color:#3ecf6e;font:700 34px ui-monospace,monospace">&gt;_</div>
      <div style="font-size:18px;font-weight:600">${esc(S.overview?.server?.name || "Server")}</div><div class="muted">Command lines</div></div>
    ${group(row("Container terminals", { sub: "A shell inside any running container — open Containers, pick one, tap Shell (approved on your phone)", blue: true, icon: "box", click: "go:containers" }))}
    ${isAdmin() ? group(row("Server terminal", { sub: "A shell on the server as your normal user, right here — approved on your phone; sudo asks your password", blue: true, icon: "term", click: "go:term" })) : ""}
    ${group(row("SSH from the phone app", { sub: "Menu → Terminal in the Nova app (its key lives in the phone's secure chip)", icon: "phone", tint: "var(--sub)" }))}`, { title: "Terminal" });
}
/** The server's own shell in the browser: a real terminal (term.js), as your normal user. */
export async function hostTerminal(ctx) {
  const { Term } = await import("./term.js");
  ctx.show(`<div class="vtwrap"><div class="vtbox" id="vt"></div>
      <div class="vtkeys">${[["Esc", "\x1b"], ["Tab", "\t"], ["Ctrl-C", "\x03"], ["Ctrl-D", "\x04"], ["Ctrl-Z", "\x1a"], ["↑", "\x1b[A"], ["↓", "\x1b[B"], ["←", "\x1b[D"], ["→", "\x1b[C"]].map(([l, v]) => `<button class="chip press" data-act="key:${encodeURIComponent(v)}">${esc(l)}</button>`).join("")}</div>
      <p class="muted vtnote" id="vtstate">Asking your phone to approve…</p></div>`, { title: "Server terminal", narrow: false });
  let sid = null, off = 0, size = null;
  // keystrokes go one request at a time, in order: what you type while one is on its way follows in the next
  let queue = "", busy = false;
  const flush = async () => {
    if (busy || !queue || !sid) return; busy = true;
    const t = queue; queue = "";
    try { await post(`/api/v1/shell/${sid}`, { input: t }); } catch (e) { toast(e.message); }
    busy = false; if (queue) flush();
  };
  const send = t => { queue += t; flush(); };
  const term = new Term($("#vt"), { onInput: send, onResize: (c, r) => { size = [c, r]; if (sid) send(`\x1b]7799;${c};${r}\x07`); } });
  // power commands and multi-line pastes ask first (term.js decides when)
  term.onConfirm = (what, paste) => confirm(paste ? "Paste and run these lines?" : "Run this on the server?", what, paste ? "Paste" : "Run it", "var(--red)");
  ctx.handlers({ key: v => { send(decodeURIComponent(v)); term.focus(); } });
  try { sid = (await post("/api/v1/terminal")).session; } catch (e) { $("#vtstate").textContent = e.message; return; }
  if (!sid) { $("#vtstate").textContent = "Not approved."; return; }
  $("#vtstate").textContent = "Connected · closes after 10 minutes without use · Ctrl+Shift+C / V to copy and paste";
  if (size) send(`\x1b]7799;${size[0]};${size[1]}\x07`);
  term.focus();
  ctx.onLeave(() => del(`/api/v1/shell/${sid}`).catch(() => {}));
  const dec = new TextDecoder();
  while (ctx.alive()) {
    try {
      const r = await get(`/api/v1/shell/${sid}?offset=${off}&wait=1`);
      if (r.data) term.write(r.data);
      off = r.offset;
      if (!r.alive) { term.write("\r\n\x1b[2m[session ended]\x1b[0m\r\n"); $("#vtstate").textContent = "Session ended — go back and open it again for a new one."; break; }
    } catch { await sleep(1000); }
  }
}

// ═════════════════════════════════ DASHBOARD MODE ═══════════════════════════════
export async function dashboard(ctx) {
  // "Living wallpaper" as the dashboard's background: shown while it's open, even if it's off for the rest of Nova
  const forceLive = on => { document.documentElement.classList.toggle("force-living", on); LV.update(); };
  forceLive(prefs.dashBg === "living"); ctx.onLeave(() => forceLive(false));
  let wake; try { wake = await navigator.wakeLock?.request("screen"); } catch {}
  ctx.onLeave(() => wake?.release?.());
  const L = { recent: [], history: [], tick: 0, added: 1 };
  let shift = 0, chrome = true, chromeT;
  const tileHtml = id => {
    const n = S.lastNow, st = S.overview?.status || {}, m = st.metrics || {}, cs = S.overview?.containers, f = S.fan || {};
    const lbl = (ic, l, c = "var(--sub)") => `<div class="lbl" style="color:${c}">${I(ic)}<span class="muted">${l}</span></div>`;
    const g = (k, ic, l, c, v, sub) => `<div class="card glass">${lbl(ic, l, c)}<div class="val" style="font-size:30px" id="d${k}v">${esc(v)}</div><div class="sub" id="d${k}s">${esc(sub)}</div><canvas id="d${k}"></canvas></div>`;
    switch (id) {
      case "clock": { const d = new Date(); return `<div class="card glass"><div class="clock" id="dclock">${esc(dashTime(d, prefs.dashSeconds))}</div><div class="muted">${d.toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" })}</div></div>`; }
      case "health": return `<div class="card glass">${lbl(st.level === "ok" || !st.level ? "okc" : "err", "Health", levelColor(st.level || "ok"))}<div class="val" style="font-size:${st.level === "ok" || !st.level ? 30 : 20}px;overflow-wrap:anywhere;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden">${st.level === "ok" || !st.level ? "All good" : esc(st.headline)}</div><div class="sub" style="margin-top:auto">${cs ? `${cs.running}/${cs.total} containers running` : ""}</div>${S.error ? `<div style="color:var(--amber);font-size:13px">Offline — showing the last data</div>` : ""}</div>`;
      case "cpu": return g("cpu", "cpu", "CPU", "var(--blue)", n ? Math.round(n.cpu) + "%" : "—", n ? "load " + n.load : "");
      case "mem": return g("mem", "mem", "Memory", "var(--violet)", n ? Math.round(n.mem) + "%" : "—", n ? `${n.mem_used_gb} / ${n.mem_total_gb} GB` : "");
      case "temp": return g("temp", "temp", "CPU temperature", "var(--amber)", n?.temp ? Math.round(n.temp) + "°C" : m.cpu_temp || "—", n?.nvme_temp ? `NVMe ${Math.round(n.nvme_temp)}°C` : "");
      case "net": return g("net", "net", "Network", "var(--green)", n ? "↓ " + rate(n.rx) : "—", n ? "↑ " + rate(n.tx) : "");
      case "gpu": return g("gpu", "speed", "Graphics", "var(--red)", n?.gpu != null ? Math.round(n.gpu) + "%" : "—", n?.gpu != null ? "GPU busy" : "No graphics reading on this server");
      case "swap": return g("swap", "mem", "Swap", "var(--amber)", n?.swap_total_gb === 0 ? "None" : n ? Math.round(n.swap || 0) + "%" : "—", n?.swap_total_gb === 0 ? "no swap set up" : n?.swap_total_gb ? `${n.swap_used_gb} of ${n.swap_total_gb} GB` : "of swap in use");
      case "io": return g("io", "disk", "Disk I/O", "#64d2ff", n ? rate(n.io || 0) : "—", "read + write, all drives");
      case "top": { const cst = S.cache["/api/v1/containers/stats"]?.stats, w = v => (v.cpu || 0) * 4 + (v.mem_pct || 0);
        const rows = cst ? Object.entries(cst).sort((a, b) => w(b[1]) - w(a[1])).slice(0, 4) : [];
        return `<div class="card glass">${lbl("box", "Top containers", "var(--amber)")}${!cst ? `<div class="sub">—</div>` : rows.length ? rows.map(([k, v]) => `<div style="display:flex;gap:8px;margin-top:8px;font-size:13px"><span style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(k)}</span><span class="muted">${Math.round(v.cpu || 0)}% · ${esc(v.mem || "")}</span></div>`).join("") : `<div class="sub">Nothing running</div>`}</div>`; }
      case "server": { const sv = S.overview?.server || {};
        return `<div class="card glass">${lbl("dns", "Server", "var(--blue)")}<div class="val" style="font-size:20px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(sv.display_name || sv.name || "—")}</div><div class="sub" style="white-space:normal">${esc((sv.cpu || "").replace(/\s+/g, " "))}</div><div class="sub" style="margin-top:auto;white-space:normal">${esc([sv.ram_gb ? sv.ram_gb + " GB memory" : "", sv.kernel ? "Linux " + sv.kernel : ""].filter(Boolean).join(" · "))}</div></div>`; }
      case "storage": return `<div class="card glass span2">${lbl("disk", "Storage", "var(--green)")}${storageList(m).slice(0, 5).map(x => { const p = x.pct / 100; return `<div style="display:flex;align-items:center;gap:10px;margin-top:12px"><span style="width:96px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(x.name)}</span><div style="flex:1">${bar(p, usageColor(p))}</div><span class="muted" style="font-size:12px;width:110px;text-align:right">${esc(x.free)}</span></div>`; }).join("")}</div>`;
      case "containers": return `<div class="card glass">${lbl("box", "Containers", "var(--amber)")}<div class="val" style="font-size:36px">${cs ? `${cs.running}/${cs.total}` : "—"}</div><div class="sub">running</div></div>`;
      case "backup": return `<div class="card glass">${lbl("backup", "Backups", "var(--blue)")}<div class="val" style="font-size:22px">${(m.data_backup || "").includes("running") ? "Running" : esc(m.data_backup || "—")}</div><div class="sub">${esc(m.backup_sets || "")}</div></div>`;
      case "fan": return `<div class="card glass span2" style="flex-direction:row;align-items:center;gap:12px"><canvas id="dfan" style="width:150px;height:150px;flex:none"></canvas><div>${lbl("bulb", "Fan light", "var(--amber)")}<div class="val">${f.on !== false ? `${cap(f.effect || "")} · ${f.brightness}%` : "Off"}</div></div></div>`;
      case "alerts": { const ev = (S.cache["/api/v1/events?since=0"]?.events || []).slice(0, 4); return `<div class="card glass span2">${lbl("bell", "Recent alerts", "var(--red)")}${ev.map(e => `<div style="display:flex;align-items:center;gap:8px;margin-top:8px"><span class="dot" style="width:8px;height:8px;background:${levelColor(e.level)}"></span><span style="flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:14px">${esc(cleanTitle(e.title))}</span><span class="muted" style="font-size:12px">${hm(e.t)}</span></div>`).join("") || `<div class="muted" style="margin-top:8px">Nothing lately</div>`}</div>`; }
      case "uptime": { const up = n?.uptime_s || 0; return `<div class="card glass">${lbl("clock", "Uptime", "var(--green)")}<div class="val" style="font-size:36px">${up ? `${Math.floor(up / 86400)}d ${Math.floor(up % 86400 / 3600)}h` : esc(m.uptime || "—")}</div><div class="sub">${esc(S.overview?.server?.kernel || "")}</div></div>`; }
    }
    return "";
  };
  const draw = () => {
    const dim = nightLevel();
    const cols = prefs.dashCols ? Math.min(prefs.dashCols, Math.max(1, Math.floor(innerWidth / 150))) : Math.max(2, Math.min(6, Math.floor(innerWidth / 260)));
    ctx.raw(`<div class="dash${prefs.dashPlain ? " plain" : ""} bg-${esc(prefs.dashBg || "theme")}" id="dash" style="--dscale:${(prefs.dashScale || 100) / 100}"><div class="top${chrome ? "" : " hide"}" id="dtop"><button class="circle frost" data-act="back" aria-label="Leave">${I("back")}</button><b style="font-size:18px;flex:1">${esc(serverName())}</b>
        <button class="circle frost" data-act="edit" aria-label="Customize">${I("edit")}</button>${document.fullscreenEnabled ? `<button class="circle frost" data-act="fs" aria-label="Full screen">${I("full")}</button>` : ""}</div>
      <div class="dgrid${arranging ? " arrange" : ""}" style="--cols:${cols};transform:translate(${(shift * 7) % 9 - 4}px,${(shift * 5) % 7 - 3}px)">${prefs.dashTiles.map(id => tileHtml(id).replace(/^<div class="card glass( span2)?/, `<div data-tile="${id}" class="card glass ${SIZE_CLS[dashSpan(id)] || ""}`)
          .replace(/<\/div>$/, arranging ? `<button class="tsize" data-act="tsize:${id}" title="Size: ${SIZE_NAMES[dashSpan(id)]}">${I("full")}</button><button class="trm" data-act="trm:${id}" title="Remove">${I("close")}</button></div>` : "</div>")).join("")}</div>
      ${arranging ? `<button class="arrdone btn" data-act="arrdone">Done</button>` : ""}<div class="night" style="background:rgba(0,0,0,${dim.toFixed(3)})"></div></div>`);
    if (prefs.dashBg === "picture") kv("dashwall").then(b => { const d = $("#dash"); if (b && d) { dashUrl ||= URL.createObjectURL(b); d.style.backgroundImage = `url("${dashUrl}")`; } }).catch(() => {});
    // hold (or right-click) a tile: arrange the dashboard like a phone's home screen — resize and remove tiles in place
    $$("[data-tile]", $("#dash")).forEach(t => { let tm; t.onpointerdown = () => { tm = setTimeout(() => { navigator.vibrate?.(12); arranging = true; draw(); }, 600); };
      t.onpointerup = t.onpointerleave = () => clearTimeout(tm); t.oncontextmenu = e => { e.preventDefault(); arranging = true; draw(); }; });
    $("#dash").onclick = e => { if (e.target.closest("[data-act]")) return;
      // "Full screen when tapped": browsers only allow full screen from a tap, so the first one asks for it
      if (prefs.dashFull && document.fullscreenEnabled && !document.fullscreenElement) document.documentElement.requestFullscreen?.().catch(() => {});
      chrome = !chrome; $("#dtop").classList.toggle("hide", !chrome); clearTimeout(chromeT); if (chrome) chromeT = setTimeout(() => { chrome = false; $("#dtop")?.classList.add("hide"); }, 5000); };
    graphs(); animate($("#dfan"), "fan");
  };
  const graphs = () => {
    const p = L.recent.length >= 2 ? L.recent.slice(-122) : L.history, o = L.recent.length >= 2 ? { window: 120, tick: L.tick, added: L.added } : {};
    const s = k => p.map(x => +(x[k] || 0)), n = S.lastNow;
    spark($("#dcpu"), s("cpu"), "--blue", { ...o, max: 100 }); spark($("#dmem"), s("mem"), "#bf5af2", { ...o, max: 100 });
    spark($("#dtemp"), s("temp"), "--amber", o); spark($("#dnet"), p.map(x => (x.rx || 0) + (x.tx || 0)), "--green", o);
    spark($("#dgpu"), s("gpu"), "--red", { ...o, max: 100 }); spark($("#dswap"), s("swap"), "--amber", { ...o, max: 100 }); spark($("#dio"), s("io"), "#64d2ff", o);
    const set = (id, v) => { const e = $("#" + id); if (e) e.textContent = v; };
    if (n) { set("dcpuv", Math.round(n.cpu) + "%"); set("dmemv", Math.round(n.mem) + "%"); if (n.temp) set("dtempv", Math.round(n.temp) + "°C"); set("dnetv", "↓ " + rate(n.rx)); set("dnets", "↑ " + rate(n.tx)); }
    if (n) { if (n.gpu != null) set("dgpuv", Math.round(n.gpu) + "%"); set("dswapv", Math.round(n.swap || 0) + "%"); set("diov", rate(n.io || 0)); }
    set("dclock", dashTime(new Date(), prefs.dashSeconds));
  };
  ctx.handlers({
    edit: () => ctx.go("edit-dash"),
    tsize: id => { const c = dashSpan(id); prefs.dashSize = { ...(prefs.dashSize || {}), [id]: c >= 4 ? 1 : c + 1 }; draw(); },
    trm: id => { prefs.dashTiles = prefs.dashTiles.filter(x => x !== id); draw(); },
    arrdone: () => { arranging = false; draw(); },
    fs: () => document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen?.(),
  });
  if (prefs.dashTiles.includes("top")) await get("/api/v1/containers/stats").catch(() => {});
  try { const r = await get("/api/v1/stats"); L.history = r.history || []; L.recent = r.recent || []; if (r.now) S.lastNow = r.now; } catch {}
  draw(); chromeT = setTimeout(() => { chrome = false; $("#dtop")?.classList.add("hide"); }, 5000);
  let tick = 0;
  ctx.every(1000, async () => {
    tick++;
    if (tick % 15 === 0) { await Promise.all([refresh(), get("/api/v1/events?since=0"), ...(prefs.dashTiles.includes("top") ? [get("/api/v1/containers/stats")] : [])].map(p => p.catch(() => {}))); }
    if (tick % 60 === 0) shift++;
    if (tick % 20 === 0) { const nl = $("#dash .night"); if (nl) nl.style.background = `rgba(0,0,0,${nightLevel().toFixed(3)})`; }   // the night fade, step by step
    if (L.recent.length) try { const r = await api("GET", "/api/v1/stats?since=" + L.recent.at(-1).t); if (r.now) S.lastNow = r.now; if (r.recent?.length) { L.recent = L.recent.concat(r.recent).slice(-180); L.tick++; L.added = r.recent.length; } } catch {}
    if (tick % 15 === 0) draw(); else graphs();
  });
}
export async function editDash(ctx) {
  const all = DASH_TILES.map(([id, label, icon]) => ({ id, label, icon }));
  const draw = () => {
    const ids = prefs.dashTiles, more = all.filter(t => !ids.includes(t.id));
    ctx.show(`${sec("Tiles · hold and drag to reorder")}<div class="group glass rl" id="rl">${ids.map(id => { const t = all.find(x => x.id === id); return t ? `<div class="row" data-key="${id}"><span class="handle">${I("drag")}</span><span style="color:var(--blue);display:flex">${I(t.icon)}</span><div class="t"><b>${t.label}</b></div><button data-noreorder class="pillbtn press" data-act="size:${id}" title="Tile size" style="margin-right:6px">${SIZE_NAMES[dashSpan(id)] || "Small"}</button><button data-noreorder data-act="rm:${id}" style="color:var(--red)">${I("remc")}</button></div>` : ""; }).join("")}</div>
      ${more.length ? sec("Add") + group(more.map(t => row(t.label, { icon: t.icon, click: "add:" + t.id, end: `<span style="color:var(--green)">${I("addc")}</span>` })).join("")) : ""}
      ${sec("Layout")}${group(row("Columns", { sub: prefs.dashCols ? `${prefs.dashCols} columns (fewer on a narrow screen)` : "Automatic — as many as fit", icon: "viewday", click: "cols" })
        + slider("Text size", "scale", prefs.dashScale || 100, 80, 160, (prefs.dashScale || 100) + "%")
        + row("Background", { sub: { theme: "Same as Nova (your living wallpaper too, when it's on)", black: "Black — best for OLED screens and dark rooms", wallpaper: "Your wallpaper", living: "Living wallpaper", picture: "Its own picture" }[prefs.dashBg || "theme"], icon: "palette", click: "bg" })
        + switchRow("Plain tiles", "No glass or borders — just the numbers", prefs.dashPlain, "plain"))}
      ${sec("Clock")}${group(row("Time format", { sub: prefs.dashClock24 == null ? "Same as this device" : prefs.dashClock24 ? "24-hour" : "12-hour", icon: "clock", click: "c24" })
        + switchRow("Show seconds", "", prefs.dashSeconds, "secs"))}
      ${sec("Night")}${group(switchRow("Dim at night", `${String(prefs.dashFrom).padStart(2, "0")}:00 – ${String(prefs.dashTo).padStart(2, "0")}:00`, prefs.dashDim, "dim")
        + (prefs.dashDim ? slider("How dark", "dimlvl", prefs.dashDimLevel ?? 40, 10, 90, (prefs.dashDimLevel ?? 40) + "%") + slider("Fade in and out over", "fade", prefs.dashFade ?? 30, 0, 120, (prefs.dashFade ?? 30) + " min") + slider("From", "from", prefs.dashFrom, 0, 23, prefs.dashFrom + ":00") + slider("Until", "to", prefs.dashTo, 0, 23, prefs.dashTo + ":00") : ""))}
      ${document.fullscreenEnabled ? sec("Full screen") + group(switchRow("Full screen when tapped", "The first tap on the dashboard fills the screen (browsers only allow it from a tap). Esc or the button leaves it.", prefs.dashFull, "full")) : ""}
      ${note("The dashboard keeps the screen awake, drifts a few pixels every minute so nothing burns in, and hides its buttons until you tap.")}
      ${links([["Reset the dashboard", "reset"]])}`, { title: "Customize dashboard" });
    reorderable($("#rl"), keys => { prefs.dashTiles = keys; draw(); });
    wireCommon(ctx.root, { onRangeInput: (k, v) => { $(`[data-lbl="${k}"]`).textContent = k === "scale" || k === "dimlvl" ? v + "%" : k === "fade" ? v + " min" : v + ":00"; },
      onRange: (k, v) => { if (k === "scale") prefs.dashScale = v; else if (k === "dimlvl") prefs.dashDimLevel = v; else if (k === "fade") prefs.dashFade = v; else prefs[k === "from" ? "dashFrom" : "dashTo"] = v; draw(); } });
  };
  ctx.handlers({ rm: id => { prefs.dashTiles = prefs.dashTiles.filter(x => x !== id); draw(); }, add: id => { prefs.dashTiles = [...prefs.dashTiles, id]; draw(); },
    size: id => { const c = dashSpan(id); prefs.dashSize = { ...(prefs.dashSize || {}), [id]: c >= 4 ? 1 : c + 1 }; draw(); },
    cols: async () => { const v = await choose("Columns", [[0, "Automatic"], ...[1, 2, 3, 4, 5, 6].map(n => [n, `${n} column${n > 1 ? "s" : ""}`])], prefs.dashCols || 0); if (v != null) { prefs.dashCols = v; draw(); } },
    bg: async () => {
      const v = await choose("Background", [["theme", "Same as Nova"], ["black", "Black", "Best for OLED screens and dark rooms"], ["wallpaper", "Your wallpaper", "Set one in Appearance"],
        ["living", "Living wallpaper", "The animated one, even if it's off for the rest of Nova"], ["picture", "Its own picture", "Just for the dashboard"]], prefs.dashBg || "theme");
      if (!v) return;
      if (v === "picture") { const inp = document.createElement("input"); inp.type = "file"; inp.accept = "image/*";
        inp.onchange = async () => { const f = inp.files?.[0]; if (!f) return; await kv("dashwall", f); prefs.dashBg = "picture"; draw(); toast("Dashboard picture set"); }; inp.click(); return; }
      prefs.dashBg = v; draw();
    },
    plain: () => { prefs.dashPlain = !prefs.dashPlain; draw(); }, full: () => { prefs.dashFull = !prefs.dashFull; draw(); }, secs: () => { prefs.dashSeconds = !prefs.dashSeconds; draw(); },
    c24: async () => { const v = await choose("Time format", [["auto", "Same as this device"], ["24", "24-hour"], ["12", "12-hour"]], prefs.dashClock24 == null ? "auto" : prefs.dashClock24 ? "24" : "12"); if (v) { prefs.dashClock24 = v === "auto" ? null : v === "24"; draw(); } },
    dim: () => { prefs.dashDim = !prefs.dashDim; draw(); }, reset: () => { ["dashTiles", "dashDim", "dashFrom", "dashTo", "dashCols", "dashSize", "dashClock24", "dashSeconds", "dashScale", "dashBg", "dashPlain", "dashDimLevel", "dashFull"].forEach(k => localStorage.removeItem("nova." + k)); draw(); } });
  draw();
}
