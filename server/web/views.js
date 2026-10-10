// Nova web — every screen of the app. Each view renders with ctx.show(html, options, handlers);
// buttons carry data-act="name" or data-act="name:arg" and land in `handlers` (or the router's).
import { S, $, $$, esc, get, post, del, api, sleep, prefs, waitTask, runningTask, levelColor, pct, rate, bytes, isAdmin, has, cleanTitle, cap,
         serverName, uptime, hm, refresh, changeFan, waitJob, kv, archiveMerge, archiveAll, archiveClear } from "./core.js";
import { I, row, group, sec, note, sw, radio, switchRow, links, expand, slider, segmented, bar, usageColor,
         toast, dialog, confirm, choose, ask, wireCommon, reorderable, onHold, spark, swipeable, colorPicker } from "./ui.js";
import { animate } from "./hero.js";
import * as ST from "./storage.js";
import { favoritesHtml } from "./start.js";

// ── catalogs (same ids as the app, so the two read alike) ───────────────────────
export const SHORTCUTS = [
  ["inbox", "bell", "Inbox", "inbox"], ["apps", "apps", "Apps", "apps"], ["servers", "dns", "Servers", "servers"], ["files", "folder", "Files", "files"], ["start", "home", "Start page", "start", null, "Start"], ["search", "search", "Search", "search"], ["quick", "widgets", "Quick panel", "quick", null, "Quick"], ["containers", "box", "Containers", "containers"],
  ["storage", "disk", "Storage", "hardware"], ["status", "status", "Status", "status"], ["lighting", "bulb", "Lighting", "lighting", "lighting"],
  ["terminal", "term", "Terminal", "terminal", "ssh"], ["store", "store", "Store", "store", "store"], ["dashboard", "dash", "Dashboard", "dashboard"],
  ["schedules", "clock", "Schedules", "schedules", "lighting"], ["devices", "group", "Devices", "devices"], ["settings", "gear", "Settings", "settings"],
].map(([id, icon, label, route, feature, short]) => ({ id, icon, label, route, feature, short: short || label }));
const sc = id => SHORTCUTS.find(s => s.id === id);
export const NAV_TABS = [{ id: "home", icon: "dns", label: "Home", route: "home" }, { id: "store", icon: "store", label: "Store", route: "store", feature: "store" },
  { id: "menu", icon: "list", label: "Menu", route: "menu" }, ...["start", "apps", "files", "servers", "search", "status", "containers", "storage", "inbox", "quick", "lighting"].map(sc)];
export const navTabs = () => {
  const t = prefs.navTabs.map(id => NAV_TABS.find(x => x.id === id)).filter(x => x && (!x.feature || has(x.feature)));
  return t.some(x => x.id === "home") ? t : [NAV_TABS[0], ...t];
};
const homeChips = () => prefs.homeChips.map(sc).filter(s => s && (!s.feature || has(s.feature)));
const QUICK = [
  ["backup", "Back up now", "backup", "Start a backup now"], ["freeram", "Free RAM", "mem", "Drop the disk cache"], ["fan", "Fan light", "bulb", "On / off"],
  ["dim", "Dim fan", "dim", "Brightness 5%"], ["bright", "Bright fan", "bright", "Brightness 60%"], ["status_light", "Status light", "traffic", "Fan shows server health"],
  ["discord", "Discord pings", "bell", "Pause / resume alerts"], ["status", "Server status", "status", "Live graphs and health"], ["dashboard", "Dashboard", "dash", "Always-on screen"],
  ["containers", "Containers", "box", "Open the list"], ["lighting", "Lighting", "palette", "Colors and effects"], ["inbox", "Inbox", "bell", "Alerts and logins"],
  ["storage", "Storage", "disk", "Drives and temperatures"], ["store", "App store", "store", "Install apps"],
].map(([id, label, icon, hint]) => ({ id, label, icon, hint }));
const quickDef = id => id.startsWith("restart:") ? { id, label: "Restart " + id.slice(8), icon: "restart", hint: "Approved on your phone" } : QUICK.find(q => q.id === id);
const HOME_SECTIONS = [["hero", "Server picture", "dns", "homeHero"], ["shortcuts", "Shortcuts", "apps", "homeShortcuts"], ["stats", "At a glance", "speed", "homeStats"]];
const DASH_TILES = [["clock", "Clock", "clock", 1], ["health", "Health", "okc", 1], ["cpu", "CPU graph", "cpu", 1], ["mem", "Memory graph", "mem", 1], ["temp", "Temperature graph", "temp", 1],
  ["net", "Network graph", "net", 1], ["storage", "Storage", "disk", 2], ["containers", "Containers", "box", 1], ["backup", "Backups", "backup", 1], ["fan", "Fan light", "bulb", 2],
  ["alerts", "Recent alerts", "bell", 2], ["uptime", "Uptime", "clock", 1]];
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
    return `<div class="swipe" data-key="${esc(a.key || "")}" data-right="${drive ? "Removed on purpose" : "Ignore"}"><div class="swbg"></div><div class="swfg">${row(cleanTitle(a.title), { sub: [a.detail, a.since && "since " + a.since].filter(Boolean).join(" · "), icon: "warn", tint: levelColor(a.level), click: "alert:" + (a.key || ""),
      end: a.key ? `<button class="xbtn" data-act="ignore:${esc(a.key)}" aria-label="Ignore">${I("remc")}</button>` : "" })}</div></div>`; }).join(""))
    + note("Swipe right (or tap) to ignore an alert you've dealt with — it comes back if the problem returns after clearing.");
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
  swipeable(ctx.root, { onRight: key => dismissAlert(key, redraw) });
}

function liveNow() { return S.cache["/api/v1/stats?now"]?.now || S.lastNow || S.cache["/api/v1/stats"]?.now || null; }
async function fetchNow() {
  try { const r = await api("GET", "/api/v1/stats?since=9e12"); if (r.now?.cpu != null) { S.lastNow = r.now; S.cache["/api/v1/stats?now"] = r; } } catch {}
}

// ═════════════════════════════════════ HOME ═════════════════════════════════════
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
      <div class="statusline" data-act="go:status"><span style="color:${S.error || S.reconnecting ? "var(--sub)" : levelColor(lvl)};display:flex">${I(icon)}</span>${esc(head)}${cs ? `<span class="sep"></span>${cs.running}/${cs.total} running` : ""}</div>
      <div style="height:14px"></div>${topBanner()}`;
    const order = prefs.homeOrder.filter(id => prefs[HOME_SECTIONS.find(s => s[0] === id)?.[3]]);
    const block = id => id === "hero" ? `<canvas class="hero" id="hero" data-act="${has("lighting") ? "go:lighting" : ""}"></canvas>` : id === "shortcuts" ? pills() : stats();
    const wide = innerWidth >= 1100;
    ctx.show(wide
      ? `<div class="home-wide"><div>${top}${prefs.homeHero ? block("hero") : ""}</div><div>${order.filter(x => x !== "hero").map(id => `<div class="block">${block(id)}</div>`).join("")}</div></div>`
      : `${top}${order.map(id => `<div class="block">${block(id)}</div>`).join("")}`, { tabroot: true, noHeader: true });
    animate($("#hero"), "server", () => S.error ? "#9e9ea4" : getComputedStyle(document.documentElement).getPropertyValue(lvl === "ok" ? "--green" : lvl === "warning" ? "--amber" : "--red").trim());
    onHold($("#chips"), () => ctx.go("edit-shortcuts"));
  };
  ctx.handlers({
    refresh: async () => { await refresh(); await fetchNow(); draw(); toast("Up to date"); },
    disconnected: async () => { if (await showDisconnected()) draw(); },
    more: async () => {
      const opts = [...(navTabs().some(t => t.id === "menu") ? [] : [["menu", "Menu"]]), ["edit-home", "Edit Home"], ["settings", "Settings"], ["about", "About"]];
      const v = await choose(serverName(), opts.map(([k, l]) => [k, l]), null); if (v) ctx.go(v);
    },
  });
  draw();
  ctx.every(15000, async () => { await refresh(); if ((S.overview?.status?.metrics?.data_backup || "").includes("running")) await get("/api/v1/backup").catch(() => {}); draw(); }, true);
  ctx.every(3000, async () => { await fetchNow(); const s = $("#hstats"); if (s) s.outerHTML = stats(); }, true);
}

// ═════════════════════════════════════ MENU ═════════════════════════════════════
export async function menu(ctx) {
  const draw = () => {
    const m = S.overview?.status?.metrics || {}, cs = S.overview?.containers, f = S.fan;
    ctx.show(`<button class="searchbox glass" data-act="search" style="width:calc(100% - 2*var(--gutter));text-align:left">${I("search")}<span class="muted" style="font-size:17px">Search Nova</span><span class="muted" style="margin-left:auto;font-size:13px">/</span></button>
      ${favoritesHtml()}${sec("Everything")}
      ${group(row("Containers", { sub: cs ? `${cs.running} of ${cs.total} running` : null, blue: true, icon: "box", click: "go:containers" })
        + (has("lighting") ? row("Lighting", { sub: f ? (f.on !== false ? `${cap(f.effect)} · ${f.brightness}%` : "Off") : null, blue: true, icon: "bulb", tint: "#ffb020", click: "go:lighting" }) : ""))}
      ${group(row("Storage & hardware", { sub: "Drives, pools, set up drives" + (storageList(m).length ? " · " + storageList(m).map(x => `${x.name} ${Math.round(x.pct)}%`).slice(0, 2).join(" · ") : ""), blue: true, icon: "disk", tint: "#3ecf6e", click: "go:hardware" })
        + row("Backups", { sub: "What's backed up, restore files", blue: true, icon: "backup", click: "go:backups" })
        + row("Diagnostics", { sub: "Speed, stress and network tests", blue: true, icon: "speed", tint: "#64d2ff", click: "go:diag" })
        + row("Updates", { sub: "Packages, containers and Nova", blue: true, icon: "update", tint: "#3ecf6e", click: "go:updates" })
        + (isAdmin() ? row("Files", { sub: "Browse, upload, download and edit files on the server", blue: true, icon: "folder", tint: "#3e91ff", click: "go:files" }) : "")
        + row("Servers", { sub: (S.cache["/api/v1/nodes"]?.nodes || []).length ? `${S.cache["/api/v1/nodes"].nodes.length + 1} servers, at a glance` : "Your other Nova servers in one place — add one with +", blue: true, icon: "dns", tint: "#64d2ff", click: "go:servers" })
        + row("Quick panel", { sub: "Your shortcuts — tap ✎ to customize", blue: true, icon: "widgets", click: "go:quick" })
        + row("Server status", { sub: "Live graphs, storage, backups", blue: true, icon: "status", tint: "#3ecf6e", click: "go:status" })
        + row("Dashboard mode", { sub: "Always-on screen for a tablet or spare screen", blue: true, icon: "dash", tint: "#64d2ff", click: "go:dashboard" })
        + row("Terminal", { sub: "Container shells here · SSH in the phone app", icon: "term", tint: "#8e8e93", click: "go:terminal" }))}
      ${group(row("Inbox", { sub: S.unread ? `${S.unread} new` : "Alerts, logins and server events", blue: true, icon: "bell", tint: "#ff5a5a", click: "go:inbox" })
        + row("Notifications", { sub: "Discord, logins, USB", icon: "bell", click: "go:notify" }))}
      ${has("store") ? group(row("App store", { sub: "Install apps & programs", blue: true, icon: "store", tint: "#bf5af2", click: navTabs().some(t => t.route === "store") ? "tab:store" : "go:store" })) : ""}
      ${group(row("Users & devices", { sub: "Who can reach this server", blue: true, icon: "group", click: "go:devices" })
        + row("Settings", { icon: "gear", tint: "var(--sub)", click: "go:settings" })
        + row("About Nova", { sub: "Nova web", icon: "info", tint: "var(--sub)", click: "go:about" }))}`,
      { title: "Menu", root: true, tabroot: true });
  };
  draw(); ctx.every(15000, async () => { await refresh(); draw(); });
}

// ════════════════════════════════════ STATUS ════════════════════════════════════
export async function status(ctx) {
  let hour = false;
  const L = { tick: 0, added: 1, recent: [], history: [], now: null };
  const pts = () => !hour && L.recent.length >= 2 ? L.recent.slice(-122) : L.history;
  const live = () => !hour && L.recent.length >= 2;
  const gcard = (label, id, v, sub) => `<div class="card glass"><div class="lbl">${esc(label)}</div><div class="val" id="${id}v">${esc(v)}</div><div class="sub" id="${id}s">${esc(sub)}</div><canvas id="${id}"></canvas></div>`;
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
        ${gcard("CPU", "c1", n ? Math.round(n.cpu) + "%" : "—", n ? `load ${n.load} · ${n.cores} cores` : "")}
        ${gcard("Memory", "c2", n ? Math.round(n.mem) + "%" : "—", n ? `${n.mem_used_gb} of ${n.mem_total_gb} GB` : "")}
        ${gcard("CPU temperature", "c3", n?.temp ? Math.round(n.temp) + "°C" : m.cpu_temp || "—", n?.nvme_temp ? `NVMe ${Math.round(n.nvme_temp)}°C` : "")}
        ${gcard("Network", "c4", n ? "↓ " + rate(n.rx) : "—", n ? "↑ " + rate(n.tx) : "")}
      </div>
      <p class="note" id="gnote">${pts().length < 3 ? (hour ? "Graphs fill in over the next few minutes." : "Connecting to the live feed…") : ""}</p>
      ${sec("Storage")}${group(storage.map(x => { const p = x.pct / 100; return `<div class="row" style="display:block"><div style="display:flex"><span style="flex:1;font-size:16px">${esc(x.name)}</span><span class="muted" style="font-size:14px">${Math.round(x.pct)}% · ${esc(x.free)}</span></div><div style="margin-top:8px">${bar(p, usageColor(p))}</div></div>`; }).join("")
        + row("Drives", { sub: `${m.drives || "—"} · ${m.drive_temps || ""}`, blue: true, icon: "disk", click: "go:hardware" }))}
      ${(() => { const rows = [...(backupRunning || m.data_backup ? [row("Data backup", { sub: backupRunning ? backupLine(S.cache["/api/v1/backup"]) : m.data_backup, blue: backupRunning, icon: "backup" })] : []),
          ...[["Backup sets", m.backup_sets], ["Photo check", (m.backup_verify || "").replace("✗", "").trim()], ["Server settings backup", m.config_backup],
              ...Object.keys(m).filter(k => k.endsWith("_db_backup")).map(k => [cap(k.slice(0, -10).replace(/_/g, " ")) + " database backup", m[k]])].filter(x => x[1]).map(([l, v]) => row(l, { sub: v }))];
        return rows.length ? sec("Backups") + group(rows.join("")) : ""; })()}
      ${sec("Services")}${group([["Containers", m.containers], ["Websites", m.websites], ["Swap", m.swap]].filter(x => x[1]).map(([l, v]) => row(l, { sub: v })).join("") || row("—"))}`,
      { title: "Server status" });
    wireCommon(ctx.root, { onSeg: (_, i) => { hour = i === 1; draw(); } });
    wireAttention(ctx, draw);
    graphs();
  };
  const graphs = () => {
    const p = pts(), n = L.now, opts = live() ? { window: 120, tick: L.tick, added: L.added } : { tick: 0 };
    const s = k => p.map(x => +(x[k] || 0));
    spark($("#c1"), s("cpu"), "--blue", { ...opts, max: 100 }); spark($("#c2"), s("mem"), "#bf5af2", { ...opts, max: 100 });
    spark($("#c3"), s("temp"), "--amber", opts); spark($("#c4"), p.map(x => (x.rx || 0) + (x.tx || 0)), "--green", opts);
    const gn = $("#gnote"); if (gn) gn.textContent = p.length < 3 ? (hour ? "Graphs fill in over the next few minutes." : "Connecting to the live feed…") : "";
    if (!n) return;
    const set = (id, v) => { const e = $("#" + id); if (e) e.textContent = v; };
    set("c1v", Math.round(n.cpu) + "%"); set("c1s", `load ${n.load} · ${n.cores} cores`);
    set("c2v", Math.round(n.mem) + "%"); set("c2s", `${n.mem_used_gb} of ${n.mem_total_gb} GB`);
    if (n.temp) set("c3v", Math.round(n.temp) + "°C"); set("c4v", "↓ " + rate(n.rx)); set("c4s", "↑ " + rate(n.tx));
  };
  const keyOf = a => { a.pop(); return a.join(":"); };          // keys contain ":" — rejoin the act arguments
  ctx.handlers({ alert: (...a) => alertMenu(keyOf(a), draw), ignore: (...a) => dismissAlert(keyOf(a), draw) });
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
      if (ctx.alive()) graphs();
    } catch { /* offline for a moment: keep the last graph */ }
  }
}

// ══════════════════════════════════ CONTAINERS ══════════════════════════════════
const POLICIES = [["unless-stopped", "Always, unless you stop it"], ["always", "Always"], ["on-failure", "Only if it crashes"], ["no", "Never"]];
const stateColor = (s, h) => h === "unhealthy" ? "var(--amber)" : s === "running" ? "var(--green)" : s === "restarting" ? "var(--amber)" : "var(--sub)";
export async function containers(ctx) {
  const [name, sub] = ctx.args;
  if (name && sub === "logs") return logs(ctx, name);
  if (name && sub === "shell") return shell(ctx, name);
  if (name) return container(ctx, name);
  const draw = () => {
    const items = S.cache["/api/v1/containers"]?.containers || [];
    const stacks = {}; items.forEach(x => (stacks[x.stack || x.name] ||= []).push(x));
    const singles = Object.values(stacks).filter(v => v.length === 1).flat();
    const groups = [...Object.entries(stacks).filter(([, v]) => v.length > 1), ...(singles.length ? [["Apps", singles]] : [])];
    const up = items.filter(x => x.state === "running").length;
    ctx.show(`<div class="statusline" style="cursor:default"><span class="dot" style="background:${up === items.length ? "var(--green)" : "var(--amber)"}"></span>${S.cache["/api/v1/containers"] ? `${up} of ${items.length} running` : "Loading…"}</div>
      ${groups.map(([g, cs]) => sec(cap(g)) + group(cs.map(x => row(x.name, { sub: `${x.image.split("/").pop()} · ${x.health ? x.state + ", " + x.health : x.state}`, click: "open:" + x.name, end: `<span class="dot" style="background:${stateColor(x.state, x.health)}"></span>` })).join(""))).join("")}`,
      { title: "Containers", actions: isAdmin() ? [{ icon: "add", label: "Add a container", act: "addc" }] : [] });
  };
  ctx.handlers({ open: n => ctx.go("containers/" + encodeURIComponent(n)),
    addc: async () => { const r = await dialog("Add a container", "", [{ label: "Cancel", value: null }, ...(has("store") ? [{ label: "From the app store", value: "store" }] : []), { label: "Your own", color: "var(--blue)", value: "own" }],
      `<div class="pad"><p class="muted" style="margin:0">Pick a ready-made app from the store, or run any image you like.</p></div>`);
      if (r === "store") ctx.go("store"); else if (r === "own") ctx.go("container-new"); } });
  draw(); ctx.every(10000, async () => { await get("/api/v1/containers"); draw(); }, true);
}
async function container(ctx, name) {
  const path = `/api/v1/containers/${encodeURIComponent(name)}`;
  let job = null;
  const draw = () => {
    const c = S.cache[path], running = c?.state === "running";
    const pill = (icon, label, act) => `<button class="press" data-act="${act}">${I(icon)}<span class="l">${label}</span></button>`;
    ctx.show(`<div class="center" style="padding:18px 0 10px"><div class="glass" style="width:120px;height:120px;border-radius:36px;margin:0 auto 12px;display:grid;place-items:center;color:var(--blue);position:relative">${I("box").replace('class="i ', 'style="width:60px;height:60px" class="i ')}
        <span class="dot" style="position:absolute;right:12px;bottom:12px;width:20px;height:20px;background:${stateColor(c?.state, c?.health)}"></span></div>
        <div class="muted" style="font-weight:600;font-size:17px">${esc(job || (c ? cap(c.state) + (c.health ? " · " + c.health : "") : "Loading…"))}</div><div class="muted" style="font-size:13px">${esc(c?.image || "")}</div></div>
      ${isAdmin() && c ? `<div class="pillbar glass">${running ? pill("stop", "Stop", "do:stop") : pill("play", "Start", "do:start")}${pill("restart", "Restart", "do:restart")}${pill("update", "Update", "update")}${pill("term", "Shell", "shell")}</div>` : ""}
      ${c ? `${sec("Live")}${group(row("CPU", { sub: c.cpu || "—" }) + row("Memory", { sub: c.mem || "—" }) + row("Network in / out", { sub: c.net || "—" }) + row("Running since", { sub: `${c.started ? new Date(c.started).toLocaleString() : "—"} · restarted ${c.restarts ?? 0} times` }))}
        ${group(row("Logs", { sub: "See what it's been saying", blue: true, icon: "article", click: "logs" }) + (isAdmin() ? row("Terminal", { sub: "Run commands inside it (approved on your phone)", blue: true, icon: "term", click: "shell" }) : ""))}
        ${sec("Configuration")}${group(row("Restart policy", { sub: (POLICIES.find(p => p[0] === c.restart_policy) || [0, c.restart_policy])[1], blue: true, click: "policy" })
          + row("Stack", { sub: `${c.stack || "—"} · ${c.compose_dir || ""}` }) + (c.privileged ? row("Privileged", { sub: "Has full access to the host", end: `<span style="color:var(--amber)">${I("warn")}</span>` }) : ""))}
        ${(c.ports || []).length ? sec("Ports") + group(c.ports.map(p => row(p)).join("")) : ""}
        ${(c.mounts || []).length ? sec("Folders") + group(c.mounts.map(m => row(m.target, { sub: m.source + (m.rw === false ? " · read-only" : "") })).join("")) : ""}
        ${(c.env || []).length ? sec("Environment") + group(c.env.map(e => row(e.key, { sub: e.value })).join("")) : ""}
        ${isAdmin() && c.custom ? group(row("Remove this container", { sub: "Stops it and moves its folder to /opt/.nova-uninstalled (nothing is deleted)", icon: "del", tint: "var(--red)", click: "rmcustom" })) : ""}` : ""}`,
      { title: name });
  };
  const load = async () => { try { await get(path); } catch (e) { toast(e.message); } if (ctx.alive()) draw(); };
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
  let tab = 0, busy = null, busyPct = 0;
  const draw = () => {
    const apps = S.cache["/api/v1/store"]?.items, progs = S.cache["/api/v1/programs"]?.items;
    const appRow = a => `<div class="row"><span class="appicon" style="background:${CAT_COLORS[a.category] || "var(--blue)"};font-weight:700;font-size:22px">${esc((a.name || "?")[0])}</span><div class="t"><b style="font-weight:600">${esc(a.name)}</b><small>${esc(a.description)}</small></div><button class="pillbtn press" data-act="open:${esc(a.id)}">${a.installed ? "Open" : "Get"}</button></div>`;
    let body = "";
    if (tab === 0) {
      const l = apps || [], inst = l.filter(a => a.installed), cats = {};
      l.filter(a => !a.installed).forEach(a => (cats[a.category] ||= []).push(a));
      body = (inst.length ? sec("Installed") + group(inst.map(appRow).join("")) : "") + Object.entries(cats).map(([c, xs]) => sec(c) + group(xs.map(appRow).join(""))).join("") + (apps ? "" : note("Loading…"));
    } else {
      const cats = {}; (progs || []).forEach(p => (cats[p.category] ||= []).push(p));
      body = Object.entries(cats).map(([c, xs]) => sec(c) + group(xs.map(p => row(p.name, { sub: p.description,
        end: busy === p.pkg ? `<span class="muted" style="font-variant-numeric:tabular-nums">${Math.round(busyPct || 0)}%</span><span class="spinner"></span>` : (p.installed && p.protected) || !isAdmin() ? `<span class="end">${p.installed ? "Installed" : ""}</span>`
          : `<button class="pillbtn press${p.installed ? " red" : ""}" data-act="prog:${esc(p.pkg)}">${p.installed ? "Remove" : "Get"}</button>` })).join(""))).join("") + (progs ? "" : note("Loading…"));
      if (isAdmin()) body = group(row("Find any program", { sub: "Search everything in your system's package manager (apt)", blue: true, icon: "search", click: "go:store/programs" })) + body;
    }
    ctx.show(`<p class="note" style="font-size:15px;margin:0 26px 10px">Hand-picked for your server. Installs run on Nova and show up in Containers.</p>${segmented(["Apps", "Programs"], tab, "tab")}${body}`,
      { title: "App store", root: true, tabroot: true });
    wireCommon(ctx.root, { onSeg: (_, i) => { tab = i; draw(); if (i === 1 && !S.cache["/api/v1/programs"]) get("/api/v1/programs").then(() => ctx.alive() && draw()).catch(() => {}); } });
  };
  ctx.handlers({
    open: id => ctx.go("store/" + encodeURIComponent(id)),
    prog: async pkg => {
      const p = S.cache["/api/v1/programs"]?.items?.find(x => x.pkg === pkg); if (!p) return;
      if (!(await confirm(`${p.installed ? "Remove" : "Install"} ${p.name}?`, "Your phone will ask you to confirm with your fingerprint.", p.installed ? "Remove" : "Install", p.installed ? "var(--red)" : "var(--blue)"))) return;
      busy = pkg; draw();
      try { const r = await post(`/api/v1/programs/${pkg}/${p.installed ? "remove" : "install"}`);
        if (r?.task) { const j = await waitTask(r.task, t => { busyPct = t.pct; if (ctx.alive()) draw(); });
          toast(j.state === "done" ? `${p.name} ${p.installed ? "removed" : "installed"}` : `Failed: ${j.error || ""}`); }
        await get("/api/v1/programs"); }
      catch (e) { toast(e.message); } finally { busy = null; busyPct = 0; if (ctx.alive()) draw(); }
    },
  });
  draw(); try { await get("/api/v1/store"); if (ctx.alive()) draw(); } catch (e) { toast(e.message); }
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
    const url = `http://${location.hostname}:${a.port}${a.path || "/"}`, busy = task?.state === "running";
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
function liveHtml() {
  const now = Date.now() / 1000, l = (S.cache["/api/v1/tasks"]?.tasks || []).filter(t => t.state === "running" || (t.finished && now - t.finished < 90));
  if (!l.length) return "";
  return sec("In progress") + group(l.map(t => `<div class="row click livetask" data-act="go:${esc(TASK_ROUTE(t))}"><div class="t" style="gap:6px"><div class="tb-top"><b>${esc(t.title)}</b>
      <span class="muted" style="font-variant-numeric:tabular-nums">${t.state === "running" ? Math.round(t.pct || 0) + "%" : t.state === "done" ? "Done" : t.state === "stopped" ? "Stopped" : "Failed"}</span></div>
      ${t.state === "running" ? bar((t.pct || 0) / 100) : ""}<small>${esc(t.state === "running" ? (t.note || t.step || "") : t.state === "done" ? (t.step || "Finished") : (t.error || t.step || ""))}</small></div></div>`).join(""));
}
export async function inbox(ctx) {
  let filter = 0, picked = new Set(), last = null;
  // live progress: every 2 s while something runs, else every 15 s
  const pollLive = async () => { try { await get("/api/v1/tasks"); const el = $("#live"); if (el) el.innerHTML = liveHtml(); } catch {} };
  (async () => { while (ctx.alive()) { await pollLive(); const busy = (S.cache["/api/v1/tasks"]?.tasks || []).some(t => t.state === "running"); await sleep(busy ? 2000 : 15000); } })();
  const mouse = matchMedia("(any-pointer: fine)").matches || navigator.maxTouchPoints === 0;     // a mouse or trackpad (touch screens keep swiping)
  const shown = () => (S.cache["/api/v1/events?since=0"]?.events || []).filter(e => filter === 1 ? ["warning", "critical"].includes(e.level) : filter === 2 ? e.level === "critical" : filter === 3 ? e.category === "login" : true);
  const draw = () => {
    const all = S.cache["/api/v1/events?since=0"]?.events, ev = shown();
    picked = new Set([...picked].filter(t => ev.some(e => String(e.t) === t)));
    const days = {}; ev.forEach(e => (days[new Date(e.t * 1000).toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" })] ||= []).push(e));
    const bar = mouse ? `<div class="selbar glass fadeok${picked.size ? " show" : ""}" id="selbar" aria-live="polite"><b id="selcount">${picked.size || 1} selected</b><span class="sp"></span><button class="pillbtn press" data-act="selall">Select all</button>
        <button class="pillbtn press" data-act="selnone">Clear</button><button class="btn" style="height:40px;padding:0 18px;font-size:15px" data-act="archsel">${I("down")} Archive <span id="selnum">${picked.size || 1}</span></button></div>` : "";
    const nudge = "Notification" in window && Notification.permission === "default" && !prefs.browserNotify && !prefs.browserNotifyNudged
      ? `<div class="nudge glass fadeok">${I("bell")}<span>Get new alerts as browser notifications on this computer?</span><button class="pillbtn press" data-act="bnotify">Turn on</button><button class="pillbtn press" data-act="bnudgeno">Not now</button></div>` : "";
    ctx.show(`${nudge}<div id="live">${liveHtml()}</div>${attentionHtml()}${segmented(["All", "Issues", "Critical", "Logins"], filter, "f")}
      ${ev.length ? `<p class="note" style="margin-top:6px">${mouse ? "Tick events to archive several at once, or use the archive button on a row. Keys: <b>x</b> select · <b>Ctrl+A</b> all · <b>e</b> archive · <b>Shift</b>-click a range." : "Swipe left to archive"} — archived events are kept on the server (Inbox → Archive), for every device.</p>` : ""}
      ${bar}
      ${Object.entries(days).map(([d, l]) => sec(d) + group(l.map(e => { const k = String(e.t), on = picked.has(k);
          return `<div class="swipe fadeok${mouse ? " mouse" : ""}${on ? " picked" : ""}" data-key="${e.t}" data-left="Archive" tabindex="0"><div class="swbg"></div><div class="swfg"><div class="row" style="align-items:flex-start">
            ${mouse ? `<button class="tick fadeok${on ? " on" : ""}" data-act="pick:${k}" aria-label="Select" aria-pressed="${on}">${I("check")}</button>` : `<span class="dot" style="margin-top:7px;background:${levelColor(e.level)}"></span>`}
            <div class="t">${mouse ? `<span class="dot" style="display:inline-block;margin-right:8px;background:${levelColor(e.level)}"></span>` : ""}<b style="font-size:16px;display:inline">${esc(cleanTitle(e.title))}</b>${e.detail ? `<small>${esc(e.detail)}</small>` : ""}</div>
            <span class="end" style="font-size:13px">${hm(e.t)}</span><button class="xbtn" data-act="del:${e.t}" aria-label="Archive" title="Archive">${I("down")}</button></div></div></div>`; }).join(""))).join("")
        || note(all ? "Nothing here — all quiet." : "Loading…")}`,
      { title: "Inbox", actions: [{ icon: "book", label: "Archive", act: "go:archive" }, { icon: "del", label: "Archive everything", act: "clear" }, { icon: "gear", label: "Notification settings", act: "go:notify" }] });
    wireCommon(ctx.root, { onSeg: (_, i) => { filter = i; picked.clear(); draw(); } });
    swipeable(ctx.root, { onRight: key => dismissAlert(key, draw), onLeft: t => remove([+t]) });
    $$(".swipe.mouse", ctx.root).forEach(el => el.onfocus = () => { last = el.dataset.key; });
  };
  // Selection changes update the page in place (so ticks, rows and the bar can animate) instead of redrawing it.
  const updateSel = () => {
    $$(".swipe[data-key]", ctx.root).forEach(el => { const on = picked.has(el.dataset.key); el.classList.toggle("picked", on);
      const t = el.querySelector(".tick"); if (t) { t.classList.toggle("on", on); t.setAttribute("aria-pressed", on); } });
    const bar = $("#selbar"); if (!bar) return;
    if (picked.size) { $("#selcount").textContent = `${picked.size} selected`; $("#selnum").textContent = picked.size; }
    bar.classList.toggle("show", picked.size > 0);
  };
  const remove = async ts => {
    if (!isAdmin()) return viewOnly();
    const c = S.cache["/api/v1/events?since=0"];
    // rows fold away first, then the list is redrawn without them
    const rows = $$(".swipe[data-key]", ctx.root).filter(el => ts.some(t => Math.abs(t - +el.dataset.key) < .0005));
    rows.forEach(el => { el.style.height = el.offsetHeight + "px"; }); void ctx.root.offsetHeight; rows.forEach(el => el.classList.add("gone"));
    ts.forEach(t => picked.delete(String(t))); updateSel();
    if (rows.length && !document.documentElement.classList.contains("reduce")) await sleep(380);
    if (c) c.events = c.events.filter(e => !ts.some(t => Math.abs(t - e.t) < .0005));
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
    alert: (...a) => alertMenu(keyOf(a), draw), ignore: (...a) => dismissAlert(keyOf(a), draw), del: t => remove([+t]),
    pick: (k, el) => toggle(k, lastClickShift), selall: () => { shown().forEach(e => picked.add(String(e.t))); updateSel(); }, selnone: () => { picked.clear(); updateSel(); },
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
}
export async function archive(ctx) {
  // The archive lives on the server (any device can read it). This browser's own copy fills gaps
  // from before the server kept one, and works when the server can't be reached.
  let filter = 0, local = await archiveAll(), server = [], more = false, loading = true, failed = false;
  const k4 = e => e.t.toFixed(4);
  const merged = () => {
    const by = new Map(), la = new Set(local.archived);
    for (const e of [...server, ...(S.cache["/api/v1/events?since=0"]?.events || []), ...local.events]) {
      const k = k4(e), was = by.get(k), a = !!(e.archived || la.has(k) || was?.archived);
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
      ${Object.entries(days).map(([d, l]) => sec(d) + group(l.map(e => `<div class="row" style="align-items:flex-start"><span class="dot" style="margin-top:7px;background:${levelColor(e.level)};opacity:${e.archived ? .5 : 1}"></span><div class="t"><b style="font-size:16px;${e.archived ? "color:var(--sub)" : ""}">${esc(cleanTitle(e.title))}</b>${e.detail ? `<small>${esc(e.detail)}</small>` : ""}${e.archived ? `<small>Archived</small>` : ""}</div><span class="end" style="font-size:13px">${hm(e.t)}</span></div>`).join(""))).join("")
        || note(loading ? "Loading…" : all.length ? "Nothing matches." : "Nothing yet.")}
      ${more && !loading ? `<div class="center" style="padding:14px"><button class="btn" data-act="older">Load older</button></div>` : loading && all.length ? `<div class="center" style="padding:14px"><div class="spinner" style="margin:auto"></div></div>` : ""}`,
      { title: "Archive", actions: [{ icon: "update", label: "Export", act: "export" }, { icon: "del", label: "Erase this browser's copy", act: "erase" }] });
    wireCommon(ctx.root, { onSeg: (_, i) => { filter = i; draw(); } });
  };
  ctx.handlers({
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
export function browserNotifyHtml() {
  if (!("Notification" in window)) return sec("This browser") + group(row("Browser notifications", { sub: "This browser can't show notifications" }));
  const perm = Notification.permission, on = !!prefs.browserNotify && perm === "granted";
  const sub = perm === "denied" ? "Blocked — allow notifications for this site in the browser's site settings, then turn this on"
    : on ? "On — while a Nova tab is open, even in the background" : "Pop up new alerts on this computer while a Nova tab is open";
  return sec("This browser") + group(switchRow("Browser notifications", sub, on, "bnotify", { blue: on })
    + (on ? switchRow("Include everything", prefs.browserNotifyAll ? "Every new event, logins and USB too" : "Only warnings and critical alerts", !!prefs.browserNotifyAll, "bnotifyall", { blue: false }) : ""));
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
  ctx.handlers({ bnotify: async () => { await toggleBrowserNotify(); draw(); }, bnotifyall: () => { prefs.browserNotifyAll = !prefs.browserNotifyAll; draw(); } });
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
      ${isAdmin() ? sec("Power") + group(row("Restart server", { sub: "Everything goes offline for about 2 minutes", icon: "restart", tint: "var(--amber)", click: "power:reboot" })
        + row("Shut down server", { sub: "You'll need to press the power button to turn it back on", icon: "power", tint: "var(--red)", click: "power:poweroff" })) : ""}`,
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
        else if (id === "freeram") { busy = id; draw(); const r = await post("/api/v1/actions/free-ram"); toast(`Freed ${r.freed_mb} MB · ${r.available_mb} MB available`); }
        else if (id === "dim" || id === "bright") { const p = changeFan({ on: true, brightness: id === "dim" ? 5 : 60 }); draw(); await p; }
        else if (id === "status_light") { const p = changeFan({ status_light: !S.fan?.status_light }); draw(); await p; }
        else if (id === "discord") { const n = S.cache["/api/v1/notify"]; S.cache["/api/v1/notify"] = await post("/api/v1/notify", { discord_paused: !n?.discord_paused }); }
        else if (id.startsWith("restart:")) { const n = id.slice(8); if (!(await confirm(`Restart ${n}?`, "Your phone will ask you to confirm with your fingerprint.", "Restart"))) return; busy = id; draw(); await post(`/api/v1/containers/${encodeURIComponent(n)}/restart`); toast(`${n} restarted`); }
      } catch (e) { toast(e.message); } finally { busy = null; if (ctx.alive()) draw(); }
    },
    power: async what => {
      if (!(await confirm(what === "reboot" ? "Restart the server?" : "Shut down the server?", "Everything running on it will be unavailable until it's back. Your phone confirms it.", what === "reboot" ? "Restart" : "Shut down"))) return;
      try { const r = await post(`/api/v1/power/${what}`); toast(`Done — the server will ${what === "reboot" ? "restart" : "shut down"} in ${r.in_seconds || 8} seconds`); } catch (e) { toast(e.message); }
    },
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
export async function lighting(ctx) {
  const draw = () => {
    const f = S.fan || {}, lit = f.on !== false, on = lit && isAdmin(), eff = f.effect || "static", nsch = (f.schedules || []).length, pal = f.palette || [], rb = f.rainbow !== false;
    const presets = f.presets || [], soft = SOFT.has(eff) || eff === "cycle", mode = soft ? (pal.length >= 2 && !(eff !== "fire" && rb) ? 2 : rb ? 0 : 1) : -1;
    const pfx = !soft && PALETTE_FX.has(eff), pmode = pal.length >= 2 ? 1 : 0;      // static, pulse, flash, gradient: color(s) or a palette
    const palEditor = () => `<div style="padding:14px 20px"><div style="display:flex;flex-wrap:wrap;gap:12px">${pal.map((c, i) => `<button class="swatch" style="width:44px;height:44px;background:${c}" data-act="${on ? "pal:" + i : ""}" data-pi="${i}" aria-label="${c}"></button>`).join("")}
                ${pal.length < 8 && on ? `<button class="swatch" style="width:44px;height:44px;border:1.5px dashed var(--sub)" data-act="paladd">${I("add")}</button>` : ""}</div>
              <p class="muted" style="font-size:12px;margin:8px 0 10px">Tap a color to change it, hold (or right-click) to remove it.</p>
              <div style="display:flex;gap:8px;overflow-x:auto;scrollbar-width:none">${PALS.map(([n, c]) => `<button style="display:flex;flex-direction:column;align-items:center;gap:4px;padding:4px" data-act="${on ? "palset:" + n : ""}"><span style="width:64px;height:22px;border-radius:11px;background:${palBg(c)}"></span><small class="muted">${n}</small></button>`).join("")}</div></div>`;
    ctx.show(`<canvas class="fanhero" id="fh"></canvas>
      ${f.status_override ? `<p class="note" style="color:var(--amber);font-size:14px">Showing server status right now — your setting comes back when it's resolved.</p>` : ""}
      ${!isAdmin() ? note("View-only access — an admin can change the lighting.") : ""}
      ${group(switchRow("Fan light", lit ? "On" : "Off", lit, isAdmin() ? "set:on" : "", { dis: !isAdmin() }))}
      ${group(slider("Brightness", "brightness", f.brightness ?? 50, 0, 100, (f.brightness ?? 50) + "%", !on))}
      ${sec("Presets")}<div class="chips" style="padding:0 var(--gutter)">${presets.map(p => { const st = p.set || {}, pc = (st.palette || []).length > 1 ? st.palette : [st.color || "#3e91ff"];
          return `<button class="tile glass press" style="height:48px;padding:0 14px;width:auto" data-act="preset:${esc(p.id)}" data-pid="${esc(p.id)}"><span style="width:22px;height:22px;border-radius:50%;background:${st.rainbow && (SOFT.has(st.effect) || st.effect === "cycle") ? "conic-gradient(red,yellow,lime,cyan,blue,magenta,red)" : palBg(pc)}"></span><b>${esc(p.name)}</b></button>`; }).join("")}
        ${isAdmin() ? `<button class="tile glass press" style="height:48px;padding:0 14px;width:auto;color:var(--blue)" data-act="savepreset">${I("add")}<b>Save current</b></button>` : ""}</div>
      ${presets.length ? note("Hold a preset (or right-click) to update, rename or delete it.") : note("Save the look you have now to switch back to it in one tap — or to use it in a schedule.")}
      ${sec("Effect")}${group(EFFECTS2.map(([k, l, d]) => row(l, { sub: d, blue: eff === k, end: radio(eff === k), click: on ? "set:effect:" + k : "", dis: !on })).join(""))}
      ${sec("Color")}${group(soft ? segmented(eff === "fire" ? ["Flame", "One color", "Palette"] : ["Rainbow", "One color", "Palette"], mode, "cmode")
          + (mode === 2 ? palEditor() : mode === 1 ? swatchRow(f.color, "color", !on) : eff === "fire" ? `<p class="note" style="margin:14px 22px">A warm flame (dark red → orange → yellow). Pick One color for a flame in your color, or Palette for your own.</p>` : "")
        : pfx ? segmented([eff === "gradient" ? "Two colors" : "One color", "Palette"], pmode, "pmode")
          + (pmode === 1 ? palEditor() : swatchRow(f.color, "color", !on) + (eff === "gradient" ? `<div class="sec" style="margin:4px 22px 0">Blend into</div>${swatchRow(f.color2, "color2", !on)}` : ""))
        : swatchRow(f.color, "color", !on))}
      ${ANIM.has(eff) ? group(slider("Speed", "speed", f.speed ?? 50, 1, 100, speedLabel(f.speed ?? 50), !on) + `<div style="display:flex;justify-content:space-between;padding:0 22px 12px" class="muted"><small>Slower</small><small>Faster</small></div>`) : ""}
      ${eff === "gradient" || soft || (pfx && pmode === 1) ? group(slider("LEDs on the fan", "led_count", f.led_count ?? 12, 4, 40, String(f.led_count ?? 12), !on)
        + `<p class="note" style="margin:0 22px 14px">Match this to your fan so the effect fits the ring exactly (most 120 mm fans have 8–18).</p>`
        + switchRow("Picture spins the other way", "If the effect here goes round the opposite way to your fan", localStorage.getItem("nova.fanReverse") === "true", "rev", { blue: false })) : ""}
      ${sec("Automation")}${group(switchRow("Status light", "Turns amber for warnings and pulses red for critical alerts, then goes back to your color", !!f.status_light, isAdmin() ? "set:status_light" : "", { blue: false, dis: !isAdmin() })
        + row("Schedules", { sub: f.schedules_paused ? "Paused" : nsch ? `${nsch} schedule${nsch > 1 ? "s" : ""}` : "Wake up gently, dim at sunset, off while you sleep…", blue: nsch > 0, click: "go:schedules" }))}
      ${links([["Notifications", "go:notify"], ["Storage & hardware", "go:hardware"]])}`, { title: "Lighting" });
    animate($("#fh"), "fan");
    wireCommon(ctx.root, {
      onRangeInput: (k, v) => { const l = $(`[data-lbl="${k}"]`); if (l) l.textContent = k === "speed" ? speedLabel(v) : k === "brightness" ? v + "%" : String(v); if (k === "brightness") S.fan = { ...S.fan, brightness: v }; },
      onRange: (k, v) => set({ [k]: v }),
      onSeg: (key, m) => { const f2 = S.fan || {};
        if (key === "pmode") return set(m === 0 ? { palette: [] } : { rainbow: false, palette: (f2.palette || []).length >= 2 ? f2.palette : [f2.color || "#3e91ff", f2.color2 || "#bf5af2"] });
        if (m === 0) set(f2.effect === "fire" ? { palette: [], rainbow: true } : { rainbow: true });
        else if (m === 1) set({ rainbow: false, palette: [] });
        else set({ rainbow: false, palette: (f2.palette || []).length >= 2 ? f2.palette : [f2.color || "#3e91ff", f2.color2 || "#bf5af2"] }); },
    });
    $$("[data-pid]").forEach(b => onHold(b, () => presetMenu(b.dataset.pid)));
    $$("[data-pi]").forEach(b => onHold(b, () => { const p = [...(S.fan.palette || [])]; if (p.length > 2) { p.splice(+b.dataset.pi, 1); set({ palette: p }); } }));
  };
  const set = async patch => { try { const p = changeFan(patch); draw(); await p; } catch (e) { toast(e.message); } if (ctx.alive()) draw(); };
  const savePresets = list => set({ presets: list });
  const presetMenu = async id => {
    if (!isAdmin()) return viewOnly();
    const list = S.fan.presets || [], p = list.find(x => x.id === id); if (!p) return;
    const v = await choose(p.name, [["update", "Update with the current look"], ["rename", "Rename"], ["delete", "Delete"]], null);
    if (v === "update") { savePresets(list.map(x => x.id === id ? { ...x, set: lookOf(S.fan) } : x)); toast("Updated"); }
    if (v === "rename") { const n = await ask("Rename preset", "Name", p.name); if (n) savePresets(list.map(x => x.id === id ? { ...x, name: n } : x)); }
    if (v === "delete") savePresets(list.filter(x => x.id !== id));
  };
  ctx.handlers({
    set: (k, v) => { const f = S.fan || {}; set({ [k]: v !== undefined ? v : k === "on" ? f.on === false : k === "rainbow" ? f.rainbow === false : !f[k] }); },
    col: (k, h) => set({ [k]: h }),
    pick: async k => { const c = await colorPicker(S.fan?.[k] || "#3e91ff"); if (c) set({ [k]: c }); },
    pal: async i => { const p = [...(S.fan.palette || [])], c = await colorPicker(p[+i]); if (c) { p[+i] = c; set({ palette: p, rainbow: false }); } },
    paladd: () => { const p = [...(S.fan.palette || [])]; p.push(p.at(-1) || "#ffffff"); set({ palette: p, rainbow: false }); },
    palset: n => set({ palette: PALS.find(x => x[0] === n)[1], rainbow: false }),
    preset: id => { const p = (S.fan.presets || []).find(x => x.id === id); if (!p) return; if (!isAdmin()) return viewOnly(); set({ ...p.set, on: p.set.on !== false }); toast(`${p.name} on`); },
    savepreset: async () => { const n = await ask("Save as a preset", "e.g. Movie night", ""); if (n) savePresets([...(S.fan.presets || []), { name: n, set: lookOf(S.fan) }]); },
    rev: () => { try { localStorage.setItem("nova.fanReverse", String(localStorage.getItem("nova.fanReverse") !== "true")); } catch {} draw(); },
  });
  draw(); try { S.fan = await get("/api/v1/fan"); if (ctx.alive()) draw(); } catch (e) { toast(e.message); }
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
    fade: ex?.fade || 0, hasEnd: !!ex?.until, endTrig: ex?.until?.trigger || "time", endTime: ex?.until?.time || "07:00", endOffset: ex?.until?.offset || 0 };
  const sunKnown = !!S.fan?.sun;
  const trigHtml = (pre, trig, time, off) => `${segmented(["Time", "Sunrise", "Sunset"], ["time", "sunrise", "sunset"].indexOf(trig), pre + "trig")}
    ${trig === "time" ? `<div class="timepick"><input type="time" id="${pre}tm" value="${esc(time)}" required></div>`
      : group(slider("Offset", pre + "off", off, -120, 120, off === 0 ? `At ${trig}` : off < 0 ? `${-off} min before` : `${off} min after`) + (sunKnown ? "" : `<p class="note" style="color:var(--amber);margin:0 22px 12px">Set the server's location in Schedules first.</p>`))}`;
  const draw = () => {
    ctx.show(`${sec("Starts")}${trigHtml("s", v.trig, v.time, v.offset)}
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
    col: (_, h) => { v.color = !h || h === "none" || v.color === h ? null : h; draw(); },
    pick: async () => { const c = await colorPicker(v.color || "#3e91ff"); if (c) { v.color = c; draw(); } },
    eff: k => { v.effect = k || null; draw(); }, cb: () => { v.changeBright = !v.changeBright; draw(); }, ton: () => { v.turnOn = !v.turnOn; draw(); }, pre: id => { v.preset = id; draw(); }, fade: m => { v.fade = +m; draw(); }, end: () => { v.hasEnd = !v.hasEnd; draw(); },
    delete: async () => { const all = structuredClone(S.fan?.schedules || []); all.splice(index, 1); await write(all); },
    save: async () => {
      const all = structuredClone(S.fan?.schedules || []);
      const set = v.action === 0 ? { on: false } : v.action === 1 ? { ...(v.turnOn ? { on: true } : {}), ...(v.changeBright ? { brightness: v.bright } : {}), ...(v.color ? { color: v.color } : {}), ...(v.effect ? { effect: v.effect } : {}) } : {};
      const s = { time: v.time, days: [...v.days].sort(), enabled: true, name: v.name.trim(), trigger: v.trig, offset: v.offset, fade: v.fade, set, preset: v.action === 2 ? v.preset : "",
        if_on: v.action === 1 && !v.turnOn, ...(v.hasEnd ? { until: { trigger: v.endTrig, time: v.endTime, offset: v.endOffset } } : {}), ...(ex?.id ? { id: ex.id, skip_next: !!ex.skip_next } : {}) };
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
const health = d => d.smart_passed === false ? ["Failing", "critical"] : (d.realloc > 0 || d.uncorrect > 0 || d.pending > 0) ? ["Worn — keep an eye on it", "warning"] : d.crc > 50 ? ["Healthy*", "ok"] : ["Healthy", "ok"];
export async function hardware(ctx) {
  const [serial] = ctx.args;
  if (serial) return drive(ctx, serial);
  const draw = () => {
    const hw = S.cache["/api/v1/hardware"], t = hw?.temps, ds = hw?.drives || [];
    const roles = {}; ds.forEach(d => (roles[d.role || "Other"] ||= []).push(d));
    const ord = r => r === "Boot drive" ? 1 : 0;
    ctx.show(`${ST.overviewHtml()}${t ? sec("Temperatures") + group(row("CPU", { sub: t.cpu_temp || "—", icon: "cpu" }) + row("Boot NVMe", { sub: t.nvme_temp || "—", icon: "ssd" }) + row("Drives", { sub: t.drive_temps || "—", icon: "disk" })) : ""}
      ${Object.entries(roles).sort(([a], [b]) => ord(a) - ord(b)).map(([r, l]) => sec(r) + group(l.map(d => { const [h, lv] = health(d), u = d.usage?.find(x => x.mount === "/") || d.usage?.[0], f = u ? u.used / Math.max(1, u.total) : 0;
          return `<div class="row click" data-act="open:${esc(d.serial)}" style="display:block"><div style="display:flex;align-items:center;gap:8px"><div class="t"><b>${esc(driveName(d))}</b><small>${bytes(d.size)} · ${d.ssd ? "SSD" : "HDD"} · ${esc((d.bus || "").toUpperCase())}${d.temp ? ` · ${d.temp}°C` : ""}</small></div><b style="color:${levelColor(lv)};font-size:14px">${h}</b></div>
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
    const d = (S.cache["/api/v1/hardware"]?.drives || []).find(x => x.serial === serial);
    if (!d) return ctx.show(note("Loading…"), { title: "Drive" });
    const [h, lv] = health(d), mounted = (d.mounts || []).length > 0;
    ctx.show(`<div class="center" style="padding:16px 0"><div style="color:${levelColor(lv)}">${I(d.ssd ? "ssd" : "disk").replace('class="i ', 'style="width:90px;height:90px" class="i ')}</div>
        <div style="color:${levelColor(lv)};font-size:18px;font-weight:700">${h}</div><div class="muted">${esc(d.role || "")} · /dev/${esc(d.name)}</div></div>
      ${sec("Health (SMART)")}${group(row("Overall", { sub: d.smart_passed === false ? "FAILED" : "Passed" }) + row("Temperature", { sub: d.temp ? d.temp + "°C" : "—" })
        + row("Reallocated sectors", { sub: String(d.realloc ?? "—") }) + row("Unreadable (pending)", { sub: String(d.pending ?? "—") }) + row("Uncorrectable errors", { sub: String(d.uncorrect ?? "—") })
        + row("Cable errors (all-time) *", { sub: `${d.crc ?? "—"} · if this keeps rising, check the cable` }))}
      ${testsHtml()}
      ${sec("Details")}${group(row("Size", { sub: bytes(d.size) }) + row("Connection", { sub: (d.bus || "").toUpperCase() }) + row("Serial", { sub: d.serial }) + row("Mounted at", { sub: (d.mounts || []).join(", ") || "Not mounted" }))}
      ${!isAdmin() ? note("View-only access: an admin can mount or unmount drives.") : d.protected ? note(`This drive is in use by the server (${d.role}) and can't be unmounted from the app.`)
        : `<button class="btn block" data-act="mount" ${busy ? "disabled" : ""}>${busy ? "Working…" : mounted ? "Safely unmount" : "Mount"}</button>`}`, { title: driveName(d) });
  };
  ctx.handlers({
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
      try { await post(`/api/v1/drives/${encodeURIComponent(serial)}/${mounted ? "unmount" : "mount"}`); toast(mounted ? "Unmounted — safe to remove" : "Mounted"); await get("/api/v1/hardware"); }
      catch (e) { toast(e.message); } finally { busy = false; if (ctx.alive()) draw(); }
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
        click: !d.current && isAdmin() ? "rm:" + d.id : "", end: !d.current && isAdmin() ? `<button class="rmbtn press" data-act="rm:${esc(d.id)}" aria-label="Remove ${esc(d.name)}" title="Remove">${I("del")}</button>` : "" })).join(""))).join("") || note("Loading…")}
      ${group(row("Remove this browser", { sub: "Erases its key here and its access on the server", icon: "del", tint: "var(--red)", click: "forget" }))}
      ${note("Removing a device here is approved on an admin phone with your fingerprint. Inviting phones, approving browsers and changing roles happen in the Nova app on an admin phone (they need its fingerprint key). Admins can do everything; view-only devices see the same screens but can't change anything.")}`,
      { title: "Users & devices" });
  };
  ctx.handlers({ forget: () => forgetBrowser(),
    rm: async id => {
      const d = (S.cache["/api/v1/devices"]?.devices || []).find(x => x.id === id); if (!d) return;
      if (!(await confirm(`Remove ${d.name}?`, `${d.name} loses access to this server${d.type === "watch" ? "" : " (and so does a watch paired with it)"}. You'll approve this on an admin phone with your fingerprint.`, "Remove"))) return;
      try { const r = await del(`/api/v1/devices/${encodeURIComponent(id)}`); if (r?.ok !== false) { toast(`Removed ${d.name}`); await get("/api/v1/devices"); draw(); } }
      catch (e) { toast(e.message); }
    } });
  draw(); try { await get("/api/v1/devices"); if (ctx.alive()) draw(); } catch (e) { toast(e.message); }
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
        + row("Nova web", { sub: "Always the version on your server — updates with it", icon: "computer" }))}
      ${sec("Server")}${group(row("Name", { sub: serverName(), blue: true, icon: "edit", click: "go:server" }))}
      ${sec("This browser")}${group(
        expand("Signing key", "Made in this browser, can't be exported", "key", "Every request is signed by a key this browser created with Web Crypto as non-extractable — page scripts can use it, but nobody (not even Nova) can read it out. Each request carries a fresh time stamp and a one-time number, so a recorded request can't be replayed.")
        + expand("Risky actions", "Approved on your phone", "shield", "Stopping or restarting things, installs, unmounting, shells, restarting the server: the server holds them until you approve them on an admin phone with your fingerprint. A stolen browser key alone can't do any of them.", { blue: true })
        + expand("Connection", `${location.host} · ${location.protocol === "https:" ? "encrypted" : "not encrypted"}`, location.hostname.match(/^\d/) ? "wifi" : "cloud",
          location.hostname.match(/^\d/) ? "At home the browser talks to the server directly over HTTPS (Nova's own certificate)." : "Away from home Nova goes through Cloudflare Access: you log in to Cloudflare first, then the browser's own key signs every request."))}
      ${group(row("Users & devices", { sub: (me.role === "viewer" ? "You have view-only access" : "Admin") + (me.user ? " · " + me.user : ""), blue: true, icon: "group", click: "go:devices" }))}
      ${group(row("Notifications", { icon: "bell", click: "go:notify" }) + row("Appearance", { sub: "Theme, Home layout, shortcuts, bottom bar", blue: true, icon: "palette", click: "go:appearance" })
        + row("Setup guide", { sub: "Install Nova on a server, remote access, browsers", blue: true, icon: "book", click: "go:guide" }))}
      ${group(row("Remove this browser", { sub: "Erases its key", icon: "del", tint: "var(--red)", click: "forget" }))}
      ${sec("Labs")}${group(row("Labs", { sub: "Experimental features you can try", blue: true, icon: "science", tint: "#bf5af2", click: "go:labs" }))}
      ${links([["About Nova", "go:about"]])}`, { title: "Settings" });
    wireCommon(ctx.root);
  };
  ctx.handlers({ forget: () => forgetBrowser(),
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
export async function appearance(ctx) {
  const draw = () => {
    const t = prefs.theme, st = prefs.style, mat = document.documentElement.classList.contains("material"), look = mat ? "Material" : "Default";
    const autoMat = /Android/i.test(navigator.userAgent) && !/SamsungBrowser|SM-[A-Z0-9]|Xiaomi|Redmi|POCO|HUAWEI|HONOR|vivo|OPPO|realme/i.test(navigator.userAgent);
    ctx.show(`${sec("Style")}${group([["auto", "Automatic", `${autoMat ? "Material You" : "Default"} — matches this device`], ["default", "Default", "Nova's own look: frosted glass, soft glow, One UI-style"],
        ["material", "Material You", "Google's Material 3 Expressive: tonal colors from the accent, bolder shapes, springy motion"]].map(([k, l, d]) => row(l, { sub: d, end: radio(st === k), click: "style:" + k })).join(""))}
      ${sec("Theme")}${group([["system", "Same as this device"], ["light", `${look} light`], ["dark", `${look} dark`]].map(([k, l]) => row(l, { end: radio(t === k), click: "theme:" + k })).join(""))}
      ${group(switchRow("Reduce motion", "Simple fades instead of slides and bounces", prefs.reduceMotion, "motion", { blue: false }))}
      ${sec("Home")}${group(row("Home layout", { sub: prefs.homeOrder.filter(id => prefs[HOME_SECTIONS.find(s => s[0] === id)[3]]).map(id => HOME_SECTIONS.find(s => s[0] === id)[1]).join(" · ") || "Just the header", blue: true, icon: "dash", click: "go:edit-home" })
        + row("Choose shortcuts", { sub: homeChips().map(s => s.label).join(", ") || "None", blue: true, icon: "tune", click: "go:edit-shortcuts" })
        + row("Start page", { sub: `Search, stats, apps and bookmarks · Nova opens on ${({ home: "Home", start: "the start page", status: "Status", apps: "Apps", inbox: "the Inbox", dashboard: "the dashboard" })[prefs.startRoute || "home"]}`, blue: true, icon: "home", click: "go:start-edit" })
        + row("Navigation pill", { sub: navTabs().map(s => s.label).join(", ") + " · along the bottom, or down the left on wide screens", blue: true, icon: "viewday", click: "go:edit-tabs" }))}
      ${note("These choices are saved in this browser. Each server also has its own name and accent color (Settings → Server).")}`, { title: "Appearance" });
  };
  ctx.handlers({
    theme: k => { prefs.theme = k; ctx.applyTheme(); draw(); },
    style: k => { prefs.style = k; ctx.applyTheme(); ctx.refreshNav(); draw(); },
    motion: () => { prefs.reduceMotion = !prefs.reduceMotion; ctx.applyTheme(); draw(); },
  });
  draw();
}
export async function editHome(ctx) {
  const draw = () => {
    ctx.show(`${sec("Top to bottom · hold and drag to reorder")}
      <div class="group glass rl" id="rl">${prefs.homeOrder.map(id => { const [, l, ic, key] = HOME_SECTIONS.find(s => s[0] === id), on = prefs[key];
        return `<div class="row" data-key="${id}"><span class="handle">${I("drag")}</span><span style="color:${on ? "var(--blue)" : "var(--sub)"};display:flex">${I(ic)}</span><div class="t"><b style="color:${on ? "var(--text)" : "var(--sub)"}">${l}</b></div><button data-noreorder data-act="tog:${key}">${sw(on)}</button></div>`; }).join("")}</div>
      ${note("The name, status line and alerts always stay at the top. Hold the shortcut bar on Home to change its buttons.")}
      ${links([["Choose shortcuts", "go:edit-shortcuts"], ["Reset Home", "reset"]])}`, { title: "Home layout" });
    reorderable($("#rl"), keys => { prefs.homeOrder = keys; draw(); });
  };
  ctx.handlers({ tog: k => { prefs[k] = !prefs[k]; draw(); }, reset: () => { ["homeOrder", "homeHero", "homeShortcuts", "homeStats"].forEach(k => localStorage.removeItem("nova." + k)); draw(); } });
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
  editList(ctx, { title: "Navigation pill", label: "Tabs · hold and drag to reorder", all: NAV_TABS, key: "navTabs", max: 5, fixed: "home", resetLabel: "Reset the navigation pill",
    preview: () => { const t = navTabs(), i = t.findIndex(x => x.id === "home"); return `<div style="display:flex;justify-content:center;padding:4px 0 6px"><div class="nav glass" style="position:static;transform:none"><div class="items"><span class="bead" style="transform:translateX(${i * 80}px)"></span>${t.map(x => `<button aria-label="${esc(x.label)}">${I(x.icon)}</button>`).join("")}</div></div></div>${note("On a wide screen or in landscape the same pill stands down the left edge. Back from any tab goes to Home. If you remove Menu, it's still in Home ⋮.")}`; } });
};
export async function about(ctx) {
  const s = S.overview?.server || {};
  ctx.show(`<div class="center" style="padding:20px 0"><div style="display:inline-block">${(await import("./ui.js")).logo(72)}</div><div style="font-size:34px;font-weight:700">Nova</div><div class="muted">Nova web</div></div>
    ${group(row("Version", { sub: `Nova ${S.me?.api || S.cache["/api/v1/server/update"]?.installed || ""} · check for updates`, blue: true, icon: "restart", click: "go:updates" }))}
    ${sec("Server")}${group(row("Name", { sub: s.name }) + row("Board", { sub: s.board }) + row("CPU", { sub: s.cpu }) + row("Memory", { sub: s.ram_gb ? s.ram_gb + " GB" : "" }) + row("Kernel", { sub: s.kernel }) + row("Up for", { sub: s.uptime_s ? uptime(s.uptime_s) : "" }))}
    ${sec("Security")}${group(row("Every request is signed", { sub: "By this browser's own key — it can't be copied out — time-stamped and single-use" }) + row("Risky actions need your phone", { sub: "Shells, stopping things, installs, unmounting, power — approved with your fingerprint" })
      + row("At home: encrypted", { sub: "HTTPS to the server's own certificate" }) + row("Outside home: Cloudflare Access", { sub: "Strangers are stopped before they reach the server" }))}
    ${sec("Project")}${group(`<a class="row click" href="https://github.com/dash1101/Nova" target="_blank" rel="noopener" style="color:inherit;text-decoration:none"><div class="t"><b>Nova by dash1101</b><small>Open source · AGPL-3.0 · github.com/dash1101/Nova</small></div></a>`)}`, { title: "About" });
}
export async function guide(ctx) {
  const step = (n, t, b, cmd) => group(`<div style="padding:20px"><div style="display:flex;align-items:center;gap:12px"><span style="width:30px;height:30px;border-radius:50%;background:var(--blue);color:#fff;display:grid;place-items:center;font-weight:700">${n}</span><b style="font-size:18px;font-weight:600">${t}</b></div><p class="muted" style="margin:8px 0 0;font-size:15px">${b}</p>${cmd ? `<div class="code-block">${esc(cmd)}</div>` : ""}</div>`);
  ctx.show(`${note("Nova has two parts: the app (phone, or this web version), and a small server package on the Linux machine you want to control (Debian or Ubuntu).")}
    ${step(1, "Install the server package", "On the server, install the Nova package (nova-server_….deb) — or build it from the source code.", "sudo apt install ./nova-server_*.deb\n# or from source:\ngit clone <repo> && cd nova && sudo ./server/install.sh")}
    ${step(2, "Run the setup wizard", "It finds your home network, asks a few questions (press Enter for the suggested answers) and starts Nova.", "sudo nova-setup")}
    ${step(3, "Pair your phone", "On home Wi-Fi, show a pairing code on the server and scan it with the Nova app. The code works once and expires in 10 minutes.", "sudo nova add")}
    ${step(4, "Optional: use it away from home", "Without a VPN, put Nova behind Cloudflare Access (free) — docs/REMOTE.md walks you through it. For this web version away from home, add an Allow policy for your email to the same Access application.")}
    ${step(5, "Optional: other people, tablets, browsers", "In the app: Menu → Users & devices — invite phones as Admin or View only, and approve browsers (open https://&lt;server&gt;:8495, choose “Get a code”).")}
    ${note("Security in a sentence: every request is signed by a key the device can't give away, and anything risky also needs your fingerprint on a phone.")}`, { title: "Setup guide" });
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
  const send = t => sid && post(`/api/v1/shell/${sid}`, { input: t }).catch(e => toast(e.message));
  const term = new Term($("#vt"), { onInput: send, onResize: (c, r) => { size = [c, r]; if (sid) send(`\x1b]7799;${c};${r}\x07`); } });
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
  let wake; try { wake = await navigator.wakeLock?.request("screen"); } catch {}
  ctx.onLeave(() => wake?.release?.());
  const L = { recent: [], history: [], tick: 0, added: 1 };
  let shift = 0, chrome = true, chromeT;
  const tileHtml = id => {
    const n = S.lastNow, st = S.overview?.status || {}, m = st.metrics || {}, cs = S.overview?.containers, f = S.fan || {};
    const lbl = (ic, l, c = "var(--sub)") => `<div class="lbl" style="color:${c}">${I(ic)}<span class="muted">${l}</span></div>`;
    const g = (k, ic, l, c, v, sub) => `<div class="card glass">${lbl(ic, l, c)}<div class="val" style="font-size:30px" id="d${k}v">${esc(v)}</div><div class="sub" id="d${k}s">${esc(sub)}</div><canvas id="d${k}"></canvas></div>`;
    switch (id) {
      case "clock": { const d = new Date(); return `<div class="card glass"><div class="clock" id="dclock">${d.toTimeString().slice(0, 5)}</div><div class="muted">${d.toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" })}</div></div>`; }
      case "health": return `<div class="card glass">${lbl(st.level === "ok" || !st.level ? "okc" : "err", "Health", levelColor(st.level || "ok"))}<div class="val" style="font-size:${st.level === "ok" || !st.level ? 30 : 20}px;overflow-wrap:anywhere;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden">${st.level === "ok" || !st.level ? "All good" : esc(st.headline)}</div><div class="sub" style="margin-top:auto">${cs ? `${cs.running}/${cs.total} containers running` : ""}</div>${S.error ? `<div style="color:var(--amber);font-size:13px">Offline — showing the last data</div>` : ""}</div>`;
      case "cpu": return g("cpu", "cpu", "CPU", "var(--blue)", n ? Math.round(n.cpu) + "%" : "—", n ? "load " + n.load : "");
      case "mem": return g("mem", "mem", "Memory", "var(--violet)", n ? Math.round(n.mem) + "%" : "—", n ? `${n.mem_used_gb} / ${n.mem_total_gb} GB` : "");
      case "temp": return g("temp", "temp", "CPU temperature", "var(--amber)", n?.temp ? Math.round(n.temp) + "°C" : m.cpu_temp || "—", n?.nvme_temp ? `NVMe ${Math.round(n.nvme_temp)}°C` : "");
      case "net": return g("net", "net", "Network", "var(--green)", n ? "↓ " + rate(n.rx) : "—", n ? "↑ " + rate(n.tx) : "");
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
    const h = new Date().getHours(), night = prefs.dashDim && (prefs.dashFrom > prefs.dashTo ? h >= prefs.dashFrom || h < prefs.dashTo : h >= prefs.dashFrom && h < prefs.dashTo);
    const cols = Math.max(2, Math.min(6, Math.floor(innerWidth / 260)));
    ctx.raw(`<div class="dash" id="dash"><div class="top${chrome ? "" : " hide"}" id="dtop"><button class="circle frost" data-act="back" aria-label="Leave">${I("back")}</button><b style="font-size:18px;flex:1">${esc(serverName())}</b>
        <button class="circle frost" data-act="edit" aria-label="Customize">${I("edit")}</button><button class="circle frost" data-act="fs" aria-label="Full screen">${I("full")}</button></div>
      <div class="dgrid" style="--cols:${cols};transform:translate(${(shift * 7) % 9 - 4}px,${(shift * 5) % 7 - 3}px)">${prefs.dashTiles.map(tileHtml).join("")}</div>${night ? `<div class="night"></div>` : ""}</div>`);
    $("#dash").onclick = e => { if (e.target.closest("[data-act]")) return; chrome = !chrome; $("#dtop").classList.toggle("hide", !chrome); clearTimeout(chromeT); if (chrome) chromeT = setTimeout(() => { chrome = false; $("#dtop")?.classList.add("hide"); }, 5000); };
    graphs(); animate($("#dfan"), "fan");
  };
  const graphs = () => {
    const p = L.recent.length >= 2 ? L.recent.slice(-122) : L.history, o = L.recent.length >= 2 ? { window: 120, tick: L.tick, added: L.added } : {};
    const s = k => p.map(x => +(x[k] || 0)), n = S.lastNow;
    spark($("#dcpu"), s("cpu"), "--blue", { ...o, max: 100 }); spark($("#dmem"), s("mem"), "#bf5af2", { ...o, max: 100 });
    spark($("#dtemp"), s("temp"), "--amber", o); spark($("#dnet"), p.map(x => (x.rx || 0) + (x.tx || 0)), "--green", o);
    const set = (id, v) => { const e = $("#" + id); if (e) e.textContent = v; };
    if (n) { set("dcpuv", Math.round(n.cpu) + "%"); set("dmemv", Math.round(n.mem) + "%"); if (n.temp) set("dtempv", Math.round(n.temp) + "°C"); set("dnetv", "↓ " + rate(n.rx)); set("dnets", "↑ " + rate(n.tx)); }
    set("dclock", new Date().toTimeString().slice(0, 5));
  };
  ctx.handlers({
    edit: () => ctx.go("edit-dash"),
    fs: () => document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen?.(),
  });
  try { const r = await get("/api/v1/stats"); L.history = r.history || []; L.recent = r.recent || []; if (r.now) S.lastNow = r.now; } catch {}
  draw(); chromeT = setTimeout(() => { chrome = false; $("#dtop")?.classList.add("hide"); }, 5000);
  let tick = 0;
  ctx.every(1000, async () => {
    tick++;
    if (tick % 15 === 0) { await Promise.all([refresh(), get("/api/v1/events?since=0")].map(p => p.catch(() => {}))); }
    if (tick % 60 === 0) shift++;
    if (L.recent.length) try { const r = await api("GET", "/api/v1/stats?since=" + L.recent.at(-1).t); if (r.now) S.lastNow = r.now; if (r.recent?.length) { L.recent = L.recent.concat(r.recent).slice(-180); L.tick++; L.added = r.recent.length; } } catch {}
    if (tick % 15 === 0) draw(); else graphs();
  });
}
export async function editDash(ctx) {
  const all = DASH_TILES.map(([id, label, icon]) => ({ id, label, icon }));
  const draw = () => {
    const ids = prefs.dashTiles, more = all.filter(t => !ids.includes(t.id));
    ctx.show(`${sec("Tiles · hold and drag to reorder")}<div class="group glass rl" id="rl">${ids.map(id => { const t = all.find(x => x.id === id); return t ? `<div class="row" data-key="${id}"><span class="handle">${I("drag")}</span><span style="color:var(--blue);display:flex">${I(t.icon)}</span><div class="t"><b>${t.label}</b></div><button data-noreorder data-act="rm:${id}" style="color:var(--red)">${I("remc")}</button></div>` : ""; }).join("")}</div>
      ${more.length ? sec("Add") + group(more.map(t => row(t.label, { icon: t.icon, click: "add:" + t.id, end: `<span style="color:var(--green)">${I("addc")}</span>` })).join("")) : ""}
      ${sec("Night")}${group(switchRow("Dim at night", `${String(prefs.dashFrom).padStart(2, "0")}:00 – ${String(prefs.dashTo).padStart(2, "0")}:00`, prefs.dashDim, "dim")
        + (prefs.dashDim ? slider("From", "from", prefs.dashFrom, 0, 23, prefs.dashFrom + ":00") + slider("Until", "to", prefs.dashTo, 0, 23, prefs.dashTo + ":00") : ""))}
      ${note("The dashboard keeps the screen awake, drifts a few pixels every minute so nothing burns in, and hides its buttons until you tap.")}
      ${links([["Reset the dashboard", "reset"]])}`, { title: "Customize dashboard" });
    reorderable($("#rl"), keys => { prefs.dashTiles = keys; draw(); });
    wireCommon(ctx.root, { onRangeInput: (k, v) => { $(`[data-lbl="${k}"]`).textContent = v + ":00"; }, onRange: (k, v) => { prefs[k === "from" ? "dashFrom" : "dashTo"] = v; draw(); } });
  };
  ctx.handlers({ rm: id => { prefs.dashTiles = prefs.dashTiles.filter(x => x !== id); draw(); }, add: id => { prefs.dashTiles = [...prefs.dashTiles, id]; draw(); },
    dim: () => { prefs.dashDim = !prefs.dashDim; draw(); }, reset: () => { ["dashTiles", "dashDim", "dashFrom", "dashTo"].forEach(k => localStorage.removeItem("nova." + k)); draw(); } });
  draw();
}
