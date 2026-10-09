// Nova web — every screen of the app. Each view renders with ctx.show(html, options, handlers);
// buttons carry data-act="name" or data-act="name:arg" and land in `handlers` (or the router's).
import { S, $, $$, esc, get, post, del, api, sleep, prefs, levelColor, pct, rate, bytes, isAdmin, has, cleanTitle, cap,
         serverName, uptime, hm, refresh, changeFan, waitJob, kv } from "./core.js";
import { I, row, group, sec, note, sw, radio, switchRow, links, expand, slider, segmented, bar, usageColor,
         toast, dialog, confirm, choose, ask, wireCommon, reorderable, onHold, spark, swipeable } from "./ui.js";
import { animate } from "./hero.js";

// ── catalogs (same ids as the app, so the two read alike) ───────────────────────
export const SHORTCUTS = [
  ["inbox", "bell", "Inbox", "inbox"], ["quick", "widgets", "Quick panel", "quick", null, "Quick"], ["containers", "box", "Containers", "containers"],
  ["storage", "disk", "Storage", "hardware"], ["status", "status", "Status", "status"], ["lighting", "bulb", "Lighting", "lighting", "lighting"],
  ["terminal", "term", "Terminal", "terminal", "ssh"], ["store", "store", "Store", "store", "store"], ["dashboard", "dash", "Dashboard", "dashboard"],
  ["schedules", "clock", "Schedules", "schedules", "lighting"], ["devices", "group", "Devices", "devices"], ["settings", "gear", "Settings", "settings"],
].map(([id, icon, label, route, feature, short]) => ({ id, icon, label, route, feature, short: short || label }));
const sc = id => SHORTCUTS.find(s => s.id === id);
export const NAV_TABS = [{ id: "home", icon: "dns", label: "Home", route: "home" }, { id: "store", icon: "store", label: "Store", route: "store", feature: "store" },
  { id: "menu", icon: "list", label: "Menu", route: "menu" }, ...["status", "containers", "storage", "inbox", "quick", "lighting"].map(sc)];
export const navTabs = () => {
  const t = prefs.navTabs.map(id => NAV_TABS.find(x => x.id === id)).filter(x => x && (!x.feature || has(x.feature)));
  return t.some(x => x.id === "home") ? t : [NAV_TABS[0], ...t];
};
const homeChips = () => prefs.homeChips.map(sc).filter(s => s && (!s.feature || has(s.feature)));
const QUICK = [
  ["backup", "Back up now", "backup", "Start a backup now"], ["freeram", "Free RAM", "mem", "Drop the disk cache"], ["fan", "Fan light", "bulb", "On / off"],
  ["dim", "Dim fan", "dim", "Brightness 5%"], ["bright", "Bright fan", "bright", "Brightness 60%"], ["status_light", "Status light", "traffic", "Fan shows server health"],
  ["discord", "Discord pings", "bell", "Pause / resume alerts"], ["status", "Server status", "status", "Live graphs and health"], ["dashboard", "Dashboard", "dash", "Always-on screen"],
  ["containers", "Containers", "box", "Open the list"], ["lighting", "Lighting", "palette", "Colours and effects"], ["inbox", "Inbox", "bell", "Alerts and logins"],
  ["storage", "Storage", "disk", "Drives and temperatures"], ["store", "App store", "store", "Install apps"],
].map(([id, label, icon, hint]) => ({ id, label, icon, hint }));
const quickDef = id => id.startsWith("restart:") ? { id, label: "Restart " + id.slice(8), icon: "restart", hint: "Approved on your phone" } : QUICK.find(q => q.id === id);
const HOME_SECTIONS = [["hero", "Server picture", "dns", "homeHero"], ["shortcuts", "Shortcuts", "apps", "homeShortcuts"], ["stats", "At a glance", "speed", "homeStats"]];
const DASH_TILES = [["clock", "Clock", "clock", 1], ["health", "Health", "okc", 1], ["cpu", "CPU graph", "cpu", 1], ["mem", "Memory graph", "mem", 1], ["temp", "Temperature graph", "temp", 1],
  ["net", "Network graph", "net", 1], ["storage", "Storage", "disk", 2], ["containers", "Containers", "box", 1], ["backup", "Backups", "backup", 1], ["fan", "Fan light", "bulb", 2],
  ["alerts", "Recent alerts", "bell", 2], ["uptime", "Uptime", "clock", 1]];
const SWATCHES = ["#ffffff", "#ff3b30", "#ff9500", "#ffcc00", "#34c759", "#00c7be", "#005aff", "#3e91ff", "#5e5ce6", "#bf5af2", "#ff2d55", "#ff6b9a"];
const EFFECTS = [["static", "Static", "One steady colour"], ["pulse", "Pulse", "Breathes in and out"], ["blink", "Blink", "Flashes on and off"], ["cycle", "Colour cycle", "Fades through colours"],
  ["wave", "Wave", "Colour chases around the ring"], ["random", "Random", "Surprise me"], ["gradient", "Gradient", "Blends two colours across the ring"]];
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
  const p = b?.progress, NAMES = { immich: "Photos", home: "Home folder", minecraft: "Minecraft", cold: "Cold storage", gaming: "Games" };
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
  return [["System drive", "root_used"], ["Photos", "photo_pool_used"], ["Cold storage", "cold_storage_used"], ["Backup drive", "backup_drive_used"]].filter(x => m?.[x[1]])
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
        <button class="circle" data-act="refresh" aria-label="Refresh">${I("refresh")}</button><button class="circle" data-act="more" aria-label="More">${I("more")}</button></div>
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
    ctx.show(`
      ${group(row("Containers", { sub: cs ? `${cs.running} of ${cs.total} running` : null, blue: true, icon: "box", click: "go:containers" })
        + (has("lighting") ? row("Lighting", { sub: f ? (f.on !== false ? `${cap(f.effect)} · ${f.brightness}%` : "Off") : null, blue: true, icon: "bulb", tint: "#ffb020", click: "go:lighting" }) : ""))}
      ${group(row("Storage & hardware", { sub: storageList(m).map(x => `${x.name} ${Math.round(x.pct)}%`).slice(0, 3).join(" · ") || null, blue: true, icon: "disk", tint: "#3ecf6e", click: "go:hardware" })
        + row("Quick panel", { sub: "Your shortcuts — tap ✎ to customise", blue: true, icon: "widgets", click: "go:quick" })
        + row("Server status", { sub: "Live graphs, storage, backups", blue: true, icon: "status", tint: "#3ecf6e", click: "go:status" })
        + row("Dashboard mode", { sub: "Always-on screen for a tablet or spare screen", blue: true, icon: "dash", tint: "#64d2ff", click: "go:dashboard" })
        + row("Terminal", { sub: "Container shells here · SSH in the phone app", icon: "term", tint: "#8e8e93", click: "go:terminal" }))}
      ${group(row("Inbox", { sub: S.unread ? `${S.unread} new` : "Alerts, logins and server events", blue: true, icon: "bell", tint: "#ff5a5a", click: "go:inbox" })
        + row("Notifications", { sub: "Discord, logins, USB", icon: "bell", click: "go:notify" }))}
      ${has("store") ? group(row("App store", { sub: "Install apps & programs", blue: true, icon: "store", tint: "#bf5af2", click: "tab:store" })) : ""}
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
              ...Object.keys(m).filter(k => k.endsWith("_db_backup")).map(k => [k === "immich_db_backup" ? "Photo database backup" : cap(k.slice(0, -10).replace(/_/g, " ")) + " database backup", m[k]])].filter(x => x[1]).map(([l, v]) => row(l, { sub: v }))];
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
      { title: "Containers" });
  };
  ctx.handlers({ open: n => ctx.go("containers/" + encodeURIComponent(n)) });
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
        ${(c.env || []).length ? sec("Environment") + group(c.env.map(e => row(e.key, { sub: e.value })).join("")) : ""}` : ""}`,
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
      try { const j = await waitJob((await post(`${path}/update`)).job); toast(j.state === "done" ? `${name} is up to date` : `Update failed: ${j.result?.error || ""}`); }
      catch (e) { toast(e.message); } finally { job = null; await load(); }
    },
    shell: () => S.cache[path]?.state === "running" ? ctx.go(`containers/${encodeURIComponent(name)}/shell`) : toast("Start it first"),
    logs: () => ctx.go(`containers/${encodeURIComponent(name)}/logs`),
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
  if (id) return storeItem(ctx, id);
  let tab = 0, busy = null;
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
        end: busy === p.pkg ? `<span class="spinner"></span>` : (p.installed && p.protected) || !isAdmin() ? `<span class="end">${p.installed ? "Installed" : ""}</span>`
          : `<button class="pillbtn press${p.installed ? " red" : ""}" data-act="prog:${esc(p.pkg)}">${p.installed ? "Remove" : "Get"}</button>` })).join(""))).join("") + (progs ? "" : note("Loading…"));
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
      try { const j = await waitJob((await post(`/api/v1/programs/${pkg}/${p.installed ? "remove" : "install"}`)).job);
        toast(j.state === "done" ? `${p.name} ${p.installed ? "removed" : "installed"}` : `Failed: ${j.result?.error || ""}`); await get("/api/v1/programs"); }
      catch (e) { toast(e.message); } finally { busy = null; if (ctx.alive()) draw(); }
    },
  });
  draw(); try { await get("/api/v1/store"); if (ctx.alive()) draw(); } catch (e) { toast(e.message); }
}
async function storeItem(ctx, id) {
  let busy = null;
  const draw = () => {
    const a = (S.cache["/api/v1/store"]?.items || []).find(x => x.id === id);
    if (!a) return ctx.show(note("Loading…"), { title: "App" });
    const url = `http://${location.hostname}:${a.port}${a.path || "/"}`;
    ctx.show(`<div class="center" style="padding:18px 0"><span class="appicon" style="width:96px;height:96px;border-radius:30px;margin:0 auto 12px;background:${CAT_COLORS[a.category] || "var(--blue)"}"><b style="font-size:44px;color:#fff">${esc((a.name || "?")[0])}</b></span>
        <div class="muted" style="font-weight:600">${esc(a.category)}</div><div class="muted">${busy ? esc(busy) : a.installed ? "Installed · " + esc(a.state || "") : "Not installed"}</div></div>
      <div style="display:flex;gap:12px;padding:0 22px">${a.installed
        ? `<a class="btn" style="flex:1" target="_blank" rel="noopener" href="${esc(url)}">Open</a>${isAdmin() ? `<button class="btn" style="flex:1;background:color-mix(in srgb,var(--text) 8%,transparent);color:var(--red)" data-act="un" ${busy ? "disabled" : ""}>Uninstall</button>` : ""}`
        : isAdmin() ? `<button class="btn" style="flex:1" data-act="in" ${a.port_free === false || busy ? "disabled" : ""}>Install</button>` : `<p class="note">An admin can install this.</p>`}</div>
      ${a.port_free === false && !a.installed ? note(`Port ${a.port} is already used by something else on the server.`) : ""}
      ${sec("About")}${group(`<div class="row"><div class="t"><b>${esc(a.description)}</b>${a.notes ? `<small>${esc(a.notes)}</small>` : ""}</div></div>`)}
      ${sec("Details")}${group(row("Image", { sub: a.image }) + row("Port", { sub: String(a.port) }) + (a.installed ? row("Address", { sub: url }) : ""))}`, { title: a.name });
  };
  const job = async verb => {
    const a = (S.cache["/api/v1/store"]?.items || []).find(x => x.id === id);
    if (verb === "uninstall" && !(await confirm(`Uninstall ${a.name}?`, "Its data is kept on the server in /opt/.nova-uninstalled. Your phone confirms it.", "Uninstall"))) return;
    busy = verb === "install" ? "Installing…" : "Uninstalling…"; draw();
    try { const j = await waitJob((await post(`/api/v1/store/${id}/${verb}`)).job); toast(j.state === "done" ? "Done" : "Failed: " + (j.result?.error || "")); await get("/api/v1/store"); }
    catch (e) { toast(e.message); } finally { busy = null; if (ctx.alive()) draw(); }
  };
  ctx.handlers({ in: () => job("install"), un: () => job("uninstall") });
  draw(); if (!S.cache["/api/v1/store"]) { await get("/api/v1/store").catch(e => toast(e.message)); if (ctx.alive()) draw(); }
}

// ═════════════════════════════════════ INBOX ════════════════════════════════════
export async function inbox(ctx) {
  let filter = 0;
  const shown = () => (S.cache["/api/v1/events?since=0"]?.events || []).filter(e => filter === 1 ? ["warning", "critical"].includes(e.level) : filter === 2 ? e.level === "critical" : filter === 3 ? e.category === "login" : true);
  const draw = () => {
    const all = S.cache["/api/v1/events?since=0"]?.events, ev = shown();
    const days = {}; ev.forEach(e => (days[new Date(e.t * 1000).toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" })] ||= []).push(e));
    ctx.show(`${attentionHtml()}${segmented(["All", "Issues", "Critical", "Logins"], filter, "f")}
      ${ev.length ? `<p class="note" style="margin-top:6px">Swipe left to delete.</p>` : ""}
      ${Object.entries(days).map(([d, l]) => sec(d) + group(l.map(e => `<div class="swipe" data-key="${e.t}" data-left="Delete"><div class="swbg"></div><div class="swfg"><div class="row" style="align-items:flex-start"><span class="dot" style="margin-top:7px;background:${levelColor(e.level)}"></span><div class="t"><b style="font-size:16px">${esc(cleanTitle(e.title))}</b>${e.detail ? `<small>${esc(e.detail)}</small>` : ""}</div><span class="end" style="font-size:13px">${hm(e.t)}</span><button class="xbtn" data-act="del:${e.t}" aria-label="Delete">${I("del")}</button></div></div></div>`).join(""))).join("")
        || note(all ? "Nothing here — all quiet." : "Loading…")}`,
      { title: "Inbox", actions: [{ icon: "del", label: "Clear the inbox", act: "clear" }, { icon: "gear", label: "Notification settings", act: "go:notify" }] });
    wireCommon(ctx.root, { onSeg: (_, i) => { filter = i; draw(); } });
    swipeable(ctx.root, { onRight: key => dismissAlert(key, draw), onLeft: t => remove([+t]) });
  };
  const remove = async ts => {
    if (!isAdmin()) return viewOnly();
    const c = S.cache["/api/v1/events?since=0"]; if (c) c.events = c.events.filter(e => !ts.some(t => Math.abs(t - e.t) < .0005));
    draw();
    try { await post("/api/v1/events/delete", { t: ts }); } catch (e) { toast(e.message); await get("/api/v1/events?since=0").catch(() => {}); draw(); }
  };
  const keyOf = a => { a.pop(); return a.join(":"); };
  ctx.handlers({
    alert: (...a) => alertMenu(keyOf(a), draw), ignore: (...a) => dismissAlert(keyOf(a), draw), del: t => remove([+t]),
    clear: async () => {
      if (!(await confirm("Clear the inbox?", filter === 0 ? "Every past event is removed from this server's inbox (for all devices). Active alerts stay until they're fixed or ignored." : `The ${shown().length} event(s) shown are removed (for all devices).`, "Clear"))) return;
      if (!isAdmin()) return viewOnly();
      if (filter !== 0) return remove(shown().map(e => e.t));
      const c = S.cache["/api/v1/events?since=0"]; if (c) c.events = []; draw();
      try { await post("/api/v1/events/delete", { all: true }); toast("Inbox cleared"); } catch (e) { toast(e.message); }
    },
  });
  draw();
  ctx.every(15000, async () => { const r = await get("/api/v1/events?since=0"); if (r.events?.[0]) { prefs.lastEventSeen = r.events[0].t; S.unread = 0; } await refresh(); draw(); }, true);
}
export async function notify(ctx) {
  const LV = [["info", "Everything", "Includes logins and USB plug/unplug"], ["warning", "Warnings and critical"], ["critical", "Critical only"]];
  const draw = () => {
    const s = S.cache["/api/v1/notify"];
    ctx.show(`<div style="display:flex;justify-content:space-evenly;align-items:center;padding:22px 0;color:var(--text)">${I("computer").replace('class="i ', 'style="width:64px;height:64px" class="i ')}<b style="color:var(--blue);font-size:26px">•••</b>${I("dns").replace('class="i ', 'style="width:64px;height:64px" class="i ')}</div>
      ${note("Alerts land in the Inbox here. Your phone gets them as notifications (Nova app → Notifications). These settings are the server's, shared by every device.")}
      ${s ? `${sec("Discord")}${group(switchRow("Discord pings", s.discord_paused ? "Paused — alerts still show in the Inbox" : "On", !s.discord_paused, "set:discord_paused")
          + row("Send to Discord", { sub: (LV.find(l => l[0] === s.push_min_level) || [0, "—"])[1], blue: true, click: "level" }))}
        ${sec("What counts")}${group(switchRow("Logins", "Someone signs in to the server", s.push_logins, "set:push_logins", { blue: false }) + switchRow("USB devices", "Plugged in or unplugged", s.push_usb, "set:push_usb", { blue: false }))}` : note("Loading…")}
      ${links([["Inbox", "go:inbox"]])}`, { title: "Notifications" });
  };
  const save = async (k, v) => {
    if (!isAdmin()) return viewOnly();
    const before = S.cache["/api/v1/notify"]; S.cache["/api/v1/notify"] = { ...before, [k]: v }; draw();
    try { S.cache["/api/v1/notify"] = await post("/api/v1/notify", { [k]: v }); } catch (e) { S.cache["/api/v1/notify"] = before; toast(e.message); } draw();
  };
  ctx.handlers({
    set: k => { const s = S.cache["/api/v1/notify"]; save(k, k === "discord_paused" ? !s.discord_paused : !s[k]); },
    level: async () => { const v = await choose("Send to Discord", LV.map(([k, l]) => [k, l]), S.cache["/api/v1/notify"]?.push_min_level); if (v) save("push_min_level", v); },
  });
  draw(); try { await get("/api/v1/notify"); if (ctx.alive()) draw(); } catch (e) { toast(e.message); }
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
const swatchRow = (sel, key, dis) => `<div class="swatches">${SWATCHES.map(h => `<button class="swatch${h.toLowerCase() === (sel || "").toLowerCase() ? " on" : ""}" style="background:${h};color:${h === "#ffffff" ? "#000" : "#fff"}" data-act="${dis ? "" : `col:${key}:${h}`}" aria-label="${h}">${h.toLowerCase() === (sel || "").toLowerCase() ? I("check") : ""}</button>`).join("")}
  <label class="swatch custom" aria-label="Custom colour">${I("palette")}<input type="color" data-color="${key}" value="${esc(sel || "#3e91ff")}" ${dis ? "disabled" : ""}></label></div>`;
export async function lighting(ctx) {
  const draw = () => {
    const f = S.fan || {}, lit = f.on !== false, on = lit && isAdmin(), eff = f.effect || "static", nsch = (f.schedules || []).length;
    ctx.show(`<canvas class="fanhero" id="fh"></canvas>
      ${f.status_override ? `<p class="note" style="color:var(--amber);font-size:14px">Showing server status right now — your setting comes back when it's resolved.</p>` : ""}
      ${!isAdmin() ? note("View-only access — an admin can change the lighting.") : ""}
      ${group(switchRow("Fan light", lit ? "On" : "Off", lit, isAdmin() ? "set:on" : "", { dis: !isAdmin() }))}
      ${group(slider("Brightness", "brightness", f.brightness ?? 50, 0, 100, (f.brightness ?? 50) + "%", !on))}
      ${sec("Colour")}${group(swatchRow(f.color, "color", !on) + (eff === "gradient" ? `<div class="sec" style="margin:4px 22px 0">Blend into</div>${swatchRow(f.color2, "color2", !on)}` : ""))}
      ${sec("Effect")}${group(EFFECTS.map(([k, l, s]) => row(l, { sub: s, blue: eff === k, end: radio(eff === k), click: on ? "set:effect:" + k : "", dis: !on })).join(""))}
      ${["pulse", "blink", "cycle", "wave", "random"].includes(eff) ? group(slider("Speed", "speed", f.speed ?? 50, 1, 100, speedLabel(f.speed ?? 50), !on)
        + `<div style="display:flex;justify-content:space-between;padding:0 22px 12px" class="muted"><small>Slower</small><small>Faster</small></div>`
        + (["cycle", "wave"].includes(eff) ? switchRow("Rainbow", f.rainbow !== false ? "Uses every colour" : "Uses your colour only", f.rainbow !== false, on ? "set:rainbow" : "", { dis: !on }) : "")) : ""}
      ${["gradient", "wave"].includes(eff) ? group(slider("LEDs on the fan", "led_count", f.led_count ?? 12, 4, 40, String(f.led_count ?? 12), !on)
        + `<p class="note" style="margin:0 22px 14px">Match this to your fan so the ${eff === "wave" ? "wave" : "blend"} fits the ring exactly (most 120 mm fans have 8–18).</p>`) : ""}
      ${sec("Automation")}${group(switchRow("Status light", "Turns amber for warnings and pulses red for critical alerts, then goes back to your colour", !!f.status_light, isAdmin() ? "set:status_light" : "", { blue: false, dis: !isAdmin() })
        + row("Schedules", { sub: nsch ? `${nsch} schedule${nsch > 1 ? "s" : ""}` : "Dim at night, turn off while you sleep…", blue: nsch > 0, click: "go:schedules" }))}
      ${links([["Notifications", "go:notify"], ["Storage & hardware", "go:hardware"]])}`, { title: "Lighting" });
    animate($("#fh"), "fan");
    wireCommon(ctx.root, {
      onRangeInput: (k, v) => { const l = $(`[data-lbl="${k}"]`); if (l) l.textContent = k === "speed" ? speedLabel(v) : k === "brightness" ? v + "%" : String(v); if (k === "brightness") S.fan = { ...S.fan, brightness: v }; },
      onRange: (k, v) => set({ [k]: v }),
    });
    $$("[data-color]").forEach(i => i.onchange = () => set({ [i.dataset.color]: i.value }));
  };
  const set = async patch => { try { const p = changeFan(patch); draw(); await p; } catch (e) { toast(e.message); } if (ctx.alive()) draw(); };
  ctx.handlers({
    set: (k, v) => { const f = S.fan || {}; set({ [k]: v !== undefined ? v : k === "on" ? f.on === false : k === "rainbow" ? f.rainbow === false : !f[k] }); },
    col: (k, h) => set({ [k]: h }),
  });
  draw(); try { S.fan = await get("/api/v1/fan"); if (ctx.alive()) draw(); } catch (e) { toast(e.message); }
}
const DAYS = ["M", "T", "W", "T", "F", "S", "S"], DAYN = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
function describeSet(s) {
  if (!s) return ""; if ("on" in s && !s.on) return "Turn off";
  const p = []; if (s.on) p.push("Turn on"); if ("brightness" in s) p.push(`Brightness ${s.brightness}%`); if (s.effect) p.push(cap(s.effect)); if (s.color) p.push("colour");
  return p.join(", ") || "No change";
}
export async function schedules(ctx) {
  const draw = () => {
    const sch = S.fan?.schedules || [];
    ctx.show(sch.length ? group(sch.map((s, i) => { const d = s.days || [0, 1, 2, 3, 4, 5, 6];
        return `<div class="row click" data-act="edit:${i}"><div class="t"><b>${esc(`${s.time}  ${s.name || ""}`.trim())}</b><small class="blue">${esc(describeSet(s.set))} · ${d.length === 7 ? "Every day" : d.map(x => DAYN[x]).join(" ")}</small></div><button data-act="toggle:${i}" aria-label="On/off">${sw(s.enabled !== false)}</button></div>`; }).join(""))
      : `${note("No schedules yet. Add one to dim the fan at night or switch it off while you sleep.")}<div class="center"><button class="btn" data-act="edit:-1">Add schedule</button></div>`,
      { title: "Schedules", actions: [{ icon: "add", label: "Add", act: "edit:-1" }] });
  };
  ctx.handlers({
    edit: i => isAdmin() ? ctx.go("schedule/" + i) : viewOnly(),
    toggle: async i => { if (!isAdmin()) return viewOnly(); const all = structuredClone(S.fan?.schedules || []); all[i].enabled = all[i].enabled === false;
      try { const p = changeFan({ schedules: all }); draw(); await p; } catch (e) { toast(e.message); } draw(); },
  });
  draw(); try { S.fan = await get("/api/v1/fan"); if (ctx.alive()) draw(); } catch {}
}
export async function schedule(ctx) {
  const index = +ctx.args[0], ex = (S.fan?.schedules || [])[index], s0 = ex?.set || {};
  let time = ex?.time || "22:00", days = new Set(ex?.days || [0, 1, 2, 3, 4, 5, 6]), off = "on" in s0 && !s0.on, bright = s0.brightness ?? 5, color = s0.color || null;
  const draw = () => {
    ctx.show(`<div class="timepick"><input type="time" id="tm" value="${esc(time)}" required></div>
      <div class="days">${DAYS.map((d, i) => `<button class="${days.has(i) ? "on" : ""}" data-act="day:${i}">${d}</button>`).join("")}</div><div style="height:14px"></div>
      ${group(switchRow("Turn the light off", null, off, "off"))}
      ${off ? "" : group(slider("Brightness", "b", bright, 0, 100, bright + "%")) + sec("Colour (optional)") + group(swatchRow(color, "c", false))}
      ${index >= 0 ? group(row("Delete schedule", { icon: "del", tint: "var(--red)", click: "delete" })) : ""}
      <div style="display:flex;gap:12px;padding:18px 22px"><button class="btn" style="flex:1;background:color-mix(in srgb,var(--text) 8%,transparent);color:var(--text)" data-act="back">Cancel</button><button class="btn" style="flex:1" data-act="save" ${days.size ? "" : "disabled"}>Save</button></div>`,
      { title: index >= 0 ? "Edit schedule" : "New schedule" });
    $("#tm").onchange = e => time = e.target.value || time;
    wireCommon(ctx.root, { onRangeInput: (k, v) => { bright = v; $('[data-lbl="b"]').textContent = v + "%"; } });
    $$("[data-color]").forEach(i => i.onchange = () => { color = i.value; draw(); });
  };
  const write = async all => { try { await changeFan({ schedules: all }); ctx.back(); toast("Saved"); } catch (e) { toast(e.message); } };
  ctx.handlers({
    day: i => { i = +i; days.has(i) ? days.delete(i) : days.add(i); draw(); },
    off: () => { off = !off; draw(); },
    col: (_, h) => { color = color === h ? null : h; draw(); },
    delete: async () => { const all = structuredClone(S.fan?.schedules || []); all.splice(index, 1); await write(all); },
    save: async () => {
      const all = structuredClone(S.fan?.schedules || []);
      const set = off ? { on: false } : { on: true, brightness: bright, ...(color ? { color } : {}) };
      const s = { time, days: [...days].sort(), enabled: true, set, ...(ex?.id ? { id: ex.id } : {}) };
      if (index >= 0) all[index] = s; else all.push(s); await write(all);
    },
  });
  draw();
}

// ═══════════════════════════════ STORAGE & HARDWARE ═════════════════════════════
const driveName = d => { const m = d.model || "", gbn = Math.round(d.size / 1e9), gb = gbn >= 1000 ? Math.round(gbn / 1000) + "TB" : Math.round(gbn / 10) * 10 + "GB";
  return /^CT.*MX500/.test(m) ? `Crucial MX500 ${gb}` : m.startsWith("SanDisk") ? `SanDisk SSD ${gb}` : /^ST.*LM/.test(m) ? `Seagate laptop HDD ${gb}` : m.startsWith("HFM") ? `SK hynix NVMe ${gb}` : m ? `${m} ${gb}` : d.name; };
const health = d => d.smart_passed === false ? ["Failing", "critical"] : (d.realloc > 0 || d.uncorrect > 0 || d.pending > 0) ? ["Worn — keep an eye on it", "warning"] : d.crc > 50 ? ["Healthy*", "ok"] : ["Healthy", "ok"];
export async function hardware(ctx) {
  const [serial] = ctx.args;
  if (serial) return drive(ctx, serial);
  const draw = () => {
    const hw = S.cache["/api/v1/hardware"], t = hw?.temps, ds = hw?.drives || [];
    const roles = {}; ds.forEach(d => (roles[d.role || "Other"] ||= []).push(d));
    const ord = r => { const i = ["Photo pool", "Backup drive", "Cold storage", "Boot drive"].indexOf(r); return i < 0 ? 9 : i; };
    ctx.show(`${t ? sec("Temperatures") + group(row("CPU", { sub: t.cpu_temp || "—", icon: "cpu" }) + row("Boot NVMe", { sub: t.nvme_temp || "—", icon: "ssd" }) + row("Drives", { sub: t.drive_temps || "—", icon: "disk" })) : ""}
      ${Object.entries(roles).sort(([a], [b]) => ord(a) - ord(b)).map(([r, l]) => sec(r) + group(l.map(d => { const [h, lv] = health(d), u = d.usage?.find(x => x.mount === "/") || d.usage?.[0], f = u ? u.used / Math.max(1, u.total) : 0;
          return `<div class="row click" data-act="open:${esc(d.serial)}" style="display:block"><div style="display:flex;align-items:center;gap:8px"><div class="t"><b>${esc(driveName(d))}</b><small>${bytes(d.size)} · ${d.ssd ? "SSD" : "HDD"} · ${esc((d.bus || "").toUpperCase())}${d.temp ? ` · ${d.temp}°C` : ""}</small></div><b style="color:${levelColor(lv)};font-size:14px">${h}</b></div>
            ${u ? `<div style="margin-top:8px">${bar(f, usageColor(f))}</div><small class="muted" style="font-size:13px">${bytes(u.free)} free of ${bytes(u.total)}</small>` : (d.mounts || []).length ? "" : `<small style="color:var(--amber);font-size:13px">Not mounted</small>`}</div>`; }).join(""))).join("")
        || note(hw ? "No drives reported." : "Loading…")}
      ${sec("Fans")}${group(expand("CPU & case fan speed", "Run by the motherboard", "speed", "The fans follow the motherboard's own curve — set it in the BIOS (often under Smart Fan or Q-Fan). Reading or setting speeds from Linux needs a driver for the board's fan chip.", { tint: "var(--sub)" })
        + (has("lighting") ? row("Fan lighting", { sub: "Colour, effects, schedules", blue: true, icon: "bulb", tint: "var(--amber)", click: "go:lighting" }) : ""))}
      ${links([["Quick panel (restart, shut down)", "go:quick"]])}`, { title: "Storage & hardware" });
    wireCommon(ctx.root);
  };
  ctx.handlers({ open: s => ctx.go("hardware/" + encodeURIComponent(s)) });
  draw(); ctx.every(15000, async () => { await get("/api/v1/hardware"); draw(); }, true);
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
export async function devices(ctx) {
  const draw = () => {
    const l = S.cache["/api/v1/devices"]?.devices || [], by = {};
    l.forEach(d => (by[d.user || "No name yet"] ||= []).push(d));
    ctx.show(`${Object.entries(by).sort(([a], [b]) => (a === "No name yet") - (b === "No name yet") || a.localeCompare(b)).map(([u, ds]) => sec(u) + group(ds.map(d => row(d.name + (d.current ? "  ·  this browser" : ""), {
        sub: `${d.role === "viewer" ? "View only" : "Admin"} · last seen ${d.last_seen || "never"}${d.via ? " via " + d.via : ""}${d.type === "browser" ? " · risky actions approved on a phone" : ""}`,
        icon: d.type === "browser" ? "computer" : d.role === "viewer" ? "eye" : "shield", tint: d.current ? "var(--green)" : d.role === "viewer" ? "var(--sub)" : "var(--blue)" })).join(""))).join("") || note("Loading…")}
      ${group(row("Remove this browser", { sub: "Erases its key here and its access on the server", icon: "del", tint: "var(--red)", click: "forget" }))}
      ${note("Inviting phones, approving browsers and changing roles happen in the Nova app on an admin phone (they need its fingerprint key). Admins can do everything; view-only devices see the same screens but can't change anything.")}`,
      { title: "Users & devices" });
  };
  ctx.handlers({ forget: () => forgetBrowser() });
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
      ${links([["About Nova", "go:about"]])}`, { title: "Settings" });
    wireCommon(ctx.root);
  };
  ctx.handlers({ forget: () => forgetBrowser() });
  draw(); ctx.every(10000, async () => { await get("/api/v1/server/update").catch(() => {}); draw(); }, true);
}
export async function serverSettings(ctx) {
  const draw = () => {
    const st = S.cache["/api/v1/settings"] || {};
    ctx.show(`${sec("Name")}${group(`<div style="padding:18px"><input class="field" id="nm" maxlength="40" placeholder="${esc(st.hostname || "Server name")}" value="${esc(st.display_name || "")}" ${isAdmin() ? "" : "disabled"}>
        <div style="display:flex;gap:10px;margin-top:10px">${isAdmin() ? `<button class="pillbtn press" data-act="save">Save</button>${st.display_name ? `<button class="pillbtn plain press" data-act="host">Use hostname</button>` : ""}` : ""}</div></div>`)}
      ${note(`Shown at the top of Home and in the server switcher. The machine's hostname (${st.hostname || "—"}) doesn't change.`)}
      ${sec("Accent colour")}${group(`<div style="display:flex;justify-content:space-between;padding:18px">${ACCENTS.map(h => `<button class="swatch${(st.accent || "") === h ? " on" : ""}" style="width:32px;height:32px;${h ? `background:${h}` : ""}" data-act="${isAdmin() ? "acc:" + (h || "none") : ""}">${h ? "" : `<span class="muted" style="font-size:13px">A</span>`}</button>`).join("")}</div>`)}
      ${note('Gives each server its own colour, so you always know which one you\'re controlling. "A" is the default blue.')}`, { title: "Server" });
  };
  const save = async patch => {
    try { S.cache["/api/v1/settings"] = await post("/api/v1/settings", patch); if (S.overview?.server) Object.assign(S.overview.server, patch); await refresh(); toast("Saved"); } catch (e) { toast(e.message); }
    if (ctx.alive()) draw();
  };
  ctx.handlers({ save: () => save({ display_name: $("#nm").value.trim() }), host: () => save({ display_name: "" }), acc: h => save({ accent: h === "none" ? "" : h }) });
  draw(); try { await get("/api/v1/settings"); if (ctx.alive()) draw(); } catch (e) { toast(e.message); }
}
export async function appearance(ctx) {
  const draw = () => {
    const t = prefs.theme;
    ctx.show(`${sec("Theme")}${group([["system", "Same as this device"], ["light", "Light"], ["dark", "Dark"]].map(([k, l]) => row(l, { end: radio(t === k), click: "theme:" + k })).join(""))}
      ${group(switchRow("Reduce motion", "Simple fades instead of slides and bounces", prefs.reduceMotion, "motion", { blue: false }))}
      ${sec("Home")}${group(row("Home layout", { sub: prefs.homeOrder.filter(id => prefs[HOME_SECTIONS.find(s => s[0] === id)[3]]).map(id => HOME_SECTIONS.find(s => s[0] === id)[1]).join(" · ") || "Just the header", blue: true, icon: "dash", click: "go:edit-home" })
        + row("Choose shortcuts", { sub: homeChips().map(s => s.label).join(", ") || "None", blue: true, icon: "tune", click: "go:edit-shortcuts" })
        + row("Bottom bar", { sub: navTabs().map(s => s.label).join(", "), blue: true, icon: "viewday", click: "go:edit-tabs" }))}
      ${note("These choices are saved in this browser. Each server also has its own name and accent colour (Settings → Server).")}`, { title: "Appearance" });
  };
  ctx.handlers({
    theme: k => { prefs.theme = k; ctx.applyTheme(); draw(); },
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
  editList(ctx, { title: "Bottom bar", label: "Tabs · hold and drag to reorder", all: NAV_TABS, key: "navTabs", max: 5, fixed: "home", resetLabel: "Reset the bottom bar",
    preview: () => { const t = navTabs(), i = t.findIndex(x => x.id === "home"); return `<div style="display:flex;justify-content:center;padding:4px 0 6px"><div class="nav glass" style="position:static;transform:none"><div class="items"><span class="bead" style="transform:translateX(${i * 80}px)"></span>${t.map(x => `<button aria-label="${esc(x.label)}">${I(x.icon)}</button>`).join("")}</div></div></div>${note("On a wide screen these sit in the side rail. Back from any tab goes to Home. If you remove Menu, it's still in Home ⋮.")}`; } });
};
export async function about(ctx) {
  const s = S.overview?.server || {};
  ctx.show(`<div class="center" style="padding:20px 0"><div style="display:inline-block">${(await import("./ui.js")).logo(72)}</div><div style="font-size:34px;font-weight:700">Nova</div><div class="muted">Nova web · server ${esc(S.cache["/api/v1/server/update"]?.installed || "")}</div></div>
    ${sec("Server")}${group(row("Name", { sub: s.name }) + row("Board", { sub: s.board }) + row("CPU", { sub: s.cpu }) + row("Memory", { sub: s.ram_gb ? s.ram_gb + " GB" : "" }) + row("Kernel", { sub: s.kernel }) + row("Up for", { sub: s.uptime_s ? uptime(s.uptime_s) : "" }))}
    ${sec("Security")}${group(row("Every request is signed", { sub: "By this browser's own key — it can't be copied out — time-stamped and single-use" }) + row("Risky actions need your phone", { sub: "Shells, stopping things, installs, unmounting, power — approved with your fingerprint" })
      + row("At home: encrypted", { sub: "HTTPS to the server's own certificate" }) + row("Outside home: Cloudflare Access", { sub: "Strangers are stopped before they reach the server" }))}`, { title: "About" });
}
export async function guide(ctx) {
  const step = (n, t, b, cmd) => group(`<div style="padding:20px"><div style="display:flex;align-items:center;gap:12px"><span style="width:30px;height:30px;border-radius:50%;background:var(--blue);color:#fff;display:grid;place-items:center;font-weight:700">${n}</span><b style="font-size:18px;font-weight:600">${t}</b></div><p class="muted" style="margin:8px 0 0;font-size:15px">${b}</p>${cmd ? `<div class="code-block">${esc(cmd)}</div>` : ""}</div>`);
  ctx.show(`${note("Nova has two parts: the app (phone, or this web version), and a small server package on the Linux machine you want to control (Debian or Ubuntu).")}
    ${step(1, "Install the server package", "On the server, install the Nova package (nova-server_….deb) — or build it from the source code.", "sudo apt install ./nova-server_*.deb\n# or from source:\ngit clone <repo> && cd nova && sudo ./server/install.sh")}
    ${step(2, "Run the setup wizard", "It finds your home network, asks a few questions (press Enter for the suggested answers) and starts Nova.", "sudo nova-setup")}
    ${step(3, "Pair your phone", "On home Wi-Fi, show a pairing code on the server and scan it with the Nova app. The code works once and expires in 10 minutes.", "sudo nova-api pair")}
    ${step(4, "Optional: use it away from home", "Without a VPN, put Nova behind Cloudflare Access (free) — docs/REMOTE.md walks you through it. For this web version away from home, add an Allow policy for your email to the same Access application.")}
    ${step(5, "Optional: other people, tablets, browsers", "In the app: Menu → Users & devices — invite phones as Admin or View only, and approve browsers (open https://&lt;server&gt;:8495, choose “Get a code”).")}
    ${note("Security in a sentence: every request is signed by a key the device can't give away, and anything risky also needs your fingerprint on a phone.")}`, { title: "Setup guide" });
}
export async function terminal(ctx) {
  ctx.show(`<div class="center" style="padding:18px 0"><div style="width:96px;height:96px;border-radius:30px;background:#0b0b0d;margin:0 auto 12px;display:grid;place-items:center;color:#3ecf6e;font:700 34px ui-monospace,monospace">&gt;_</div>
      <div style="font-size:18px;font-weight:600">${esc(S.overview?.server?.name || "Server")}</div><div class="muted">Command lines</div></div>
    ${group(row("Container terminals", { sub: "A shell inside any running container — open Containers, pick one, tap Shell (approved on your phone)", blue: true, icon: "box", click: "go:containers" }))}
    ${group(row("SSH into the server", { sub: "In the Nova phone app (Menu → Terminal). Its SSH key lives in the phone's secure chip, so browsers don't get one.", icon: "term", tint: "var(--sub)" }))}`, { title: "Terminal" });
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
        <button class="circle frost" data-act="edit" aria-label="Customise">${I("edit")}</button><button class="circle frost" data-act="fs" aria-label="Full screen">${I("full")}</button></div>
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
      ${links([["Reset the dashboard", "reset"]])}`, { title: "Customise dashboard" });
    reorderable($("#rl"), keys => { prefs.dashTiles = keys; draw(); });
    wireCommon(ctx.root, { onRangeInput: (k, v) => { $(`[data-lbl="${k}"]`).textContent = v + ":00"; }, onRange: (k, v) => { prefs[k === "from" ? "dashFrom" : "dashTo"] = v; draw(); } });
  };
  ctx.handlers({ rm: id => { prefs.dashTiles = prefs.dashTiles.filter(x => x !== id); draw(); }, add: id => { prefs.dashTiles = [...prefs.dashTiles, id]; draw(); },
    dim: () => { prefs.dashDim = !prefs.dashDim; draw(); }, reset: () => { ["dashTiles", "dashDim", "dashFrom", "dashTo"].forEach(k => localStorage.removeItem("nova." + k)); draw(); } });
  draw();
}
