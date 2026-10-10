// Nova web — search everything in Nova, and the start page (a new-tab homepage: search Nova and the
// web, the server at a glance, your apps and bookmarks; every part can be hidden or reordered).
import { S, $, $$, esc, get, prefs, isAdmin, bytes, serverName, pct } from "./core.js";
import { I, row, group, sec, note, switchRow, toast, dialog, reorderable, wireCommon } from "./ui.js";
import { poolKind } from "./storage.js";

const enc = encodeURIComponent;

// ── what Nova can find ─────────────────────────────────────────────────────────
export const PLACES = [
  ["Home", "overview start", "dns", "home"], ["Start page", "new tab homepage search bookmarks", "home", "start"],
  ["Status", "graphs cpu memory ram temperature network live", "status", "status"], ["Containers", "docker compose logs shell restart stop", "box", "containers"],
  ["Apps", "web apps open launch", "apps", "apps"], ["Servers", "servers nodes machines other computers add server multiple", "dns", "servers"], ["Labs", "labs experimental beta features cloudflare auto setup crash loop guard weekly automatic updates", "science", "labs"], ["Wake-on-LAN", "wake on lan wol turn on computer pc magic packet", "power", "wol"], ["Old images", "clean up old docker images free space disk reclaim prune", "disk", "labs-images"], ["Files", "file manager files folders upload download edit text documents explorer", "folder", "files"], ["Server terminal", "terminal shell bash command line sudo console", "term", "term"], ["App store", "install store programs", "store", "store"],
  ["Storage & hardware", "drives disks pools raid smart mount unmount temperature fans", "disk", "hardware"],
  ["Set up drives", "format raid mirror combine pool mergerfs mdadm new drive", "addc", "setup"], ["Backups", "backup restore snapshot nas smb nfs schedule", "backup", "backups"],
  ["Diagnostics", "speed test internet ping traceroute dns port cpu stress memory test disk speed", "speed", "diag"],
  ["Updates", "upgrade packages apt containers images nova update", "update", "updates"], ["Inbox", "alerts notifications events history", "bell", "inbox"],
  ["Archive", "old events history export", "book", "archive"], ["Notifications", "discord webhook alerts levels logins usb", "bell", "notify"],
  ["Lighting", "fan rgb led color color effect brightness wave", "bulb", "lighting"], ["Your lights", "lights zones strip argb header add light second fan d_led 12v rgb setup", "tune", "lights"], ["Light schedules", "schedule sunrise sunset fade timer", "clock", "schedules"],
  ["Quick panel", "shortcuts tiles restart shut down power free ram", "widgets", "quick"], ["Dashboard mode", "always on tablet wall display", "dash", "dashboard"],
  ["Users & devices", "devices phones browsers users roles remove", "group", "devices"], ["Settings", "software update connection this browser", "gear", "settings"],
  ["Appearance", "theme dark light material you style reduce motion home layout navigation pill tabs start page open on start", "palette", "appearance"],
  ["Server", "name accent color location sunrise", "dns", "server"], ["Setup guide", "help install how to", "info", "guide"], ["About", "version license", "info", "about"],
];
const SOURCES = ["/api/v1/containers", "/api/v1/apps", "/api/v1/storage", "/api/v1/backups"];
export const loadSearchData = () => Promise.all(SOURCES.map(p => S.cache[p] ? null : get(p).catch(() => null)));

export function searchNova(q0) {
  const q = q0.trim().toLowerCase(); if (!q) return [];
  const words = q.split(/\s+/);
  const score = (...f) => { const text = f.join(" ").toLowerCase(), t = String(f[0]).toLowerCase();
    if (words.some(w => !text.includes(w))) return 0;
    return (t.startsWith(q) ? 100 : 0) + (t.includes(q) ? 50 : 0) + words.filter(w => t.includes(w)).length * 10 + 1; };
  const hits = [];
  PLACES.forEach(([t, kw, ic, r]) => { const s = score(t, kw); if (s) hits.push([s, { title: t, sub: "Screen", icon: ic, kind: "Screens & settings", go: r }]); });
  (S.cache["/api/v1/containers"]?.containers || []).forEach(c => { const s = score(c.name, c.image, c.stack); if (s) hits.push([s, { title: c.name, sub: `${c.state} · ${c.image}`, icon: "box", kind: "Containers", go: `containers/${enc(c.name)}` }]); });
  (S.cache["/api/v1/apps"]?.apps || []).filter(a => !a.hidden).forEach(a => { const s = score(a.name, a.image || ""); if (s) hits.push([s, { title: a.name, sub: "Open the app", icon: "apps", kind: "Apps", go: "apps" }]); });
  const st = S.cache["/api/v1/storage"];
  (st?.drives || []).forEach(d => { const s = score(`${d.size_text} ${d.model || d.name}`, d.model, d.mounts.join(" "), d.serial); if (s) hits.push([s, { title: `${d.size_text} ${d.model || d.name}`, sub: d.mounts.join(", ") || "Not mounted", icon: "disk", kind: "Drives", go: `hardware/${enc(d.serial)}` }]); });
  (st?.pools || []).forEach(p => { const s = score(p.name, p.mount, poolKind(p.type)); if (s) hits.push([s, { title: p.name, sub: `${poolKind(p.type)} · ${p.mount}`, icon: "disk", kind: "Drives", go: `pool/${enc(p.id)}` }]); });
  (S.cache["/api/v1/backups"]?.jobs || []).forEach(j => { const s = score(j.name, j.sources.join(" ")); if (s) hits.push([s, { title: j.name, sub: "Backup · " + j.sources.join(", "), icon: "backup", kind: "Backups", go: `backup/${j.id}` }]); });
  return hits.sort((a, b) => b[0] - a[0]).map(h => h[1]).slice(0, 40);
}

export async function search(ctx) {
  let q = ctx.args[0] ? decodeURIComponent(ctx.args[0]) : "";
  const results = () => { const hits = searchNova(q), groups = {}; hits.forEach(h => (groups[h.kind] ||= []).push(h));
    return !q.trim() ? historyHtml() + note("Try “dark mode”, “raid”, “speed test” or a container's name. Press / anywhere in Nova to search.")
      : hits.length ? Object.entries(groups).map(([k, l]) => sec(k) + group(l.map(h => row(h.title, { sub: h.sub, icon: h.icon, click: `hit:${h.go}` })).join(""))).join("") : note(`Nothing called “${q}”.`); };
  // recent and pinned searches (this browser)
  const recent = () => prefs.searchRecent || [], pinned = () => prefs.searchPinned || [];
  const keep = t => { t = t.trim().slice(0, 80); if (t.length < 2) return; prefs.searchRecent = [t, ...recent().filter(x => x.toLowerCase() !== t.toLowerCase())].slice(0, 12); };
  const historyHtml = () => {
    const pins = pinned(), rec = recent().filter(t => !pins.includes(t));
    return (pins.length ? sec("Pinned") + `<div class="chips">${pins.map((t, i) => `<button class="chip press" data-act="useq:${i}:p" title="Right-click to unpin">${I("pin")}${esc(t)}</button>`).join("")}</div>` : "")
      + (rec.length ? sec("Recent") + group(rec.map(t => { const i = recent().indexOf(t);
          return row(t, { icon: "history", tint: "var(--sub)", click: `useq:${i}:r`, end: `<span class="hact"><button class="xbtn" style="opacity:.8" data-act="pinq:${i}" title="Pin">${I("pin")}</button><button class="xbtn" style="opacity:.8" data-act="rmq:${i}" title="Remove">${I("close")}</button></span>` }); }).join("")
          + row("Clear history", { icon: "del", tint: "var(--red)", click: "clearq" })) : "");
  };
  const redraw = () => { $("#sres").innerHTML = results(); $$("#sres .chip").forEach(c => c.oncontextmenu = e => { e.preventDefault(); const i = +c.dataset.act.split(":")[1]; prefs.searchPinned = pinned().filter((_, j) => j !== i); redraw(); }); };
  const setQ = t => { q = t; $("#sq").value = t; $("#sq").focus(); redraw(); };
  ctx.handlers({
    hit: (...a) => { keep(q); ctx.go(a.filter(x => typeof x === "string").join(":")); },
    useq: (i, kind) => setQ((kind === "p" ? pinned() : recent())[+i] || ""),
    pinq: i => { const t = recent()[+i]; if (t && !pinned().includes(t)) prefs.searchPinned = [...pinned(), t].slice(-8); redraw(); },
    rmq: i => { prefs.searchRecent = recent().filter((_, j) => j !== +i); redraw(); },
    clearq: () => { prefs.searchRecent = []; redraw(); },
  });
  ctx.show(`<div class="searchbox glass">${I("search")}<input id="sq" class="sq" placeholder="Screens, settings, containers, apps, drives…" value="${esc(q)}" autocomplete="off" aria-label="Search Nova"></div><div id="sres">${results()}</div>`, { title: "Search" });
  const inp = $("#sq"); inp.focus();
  inp.oninput = () => { q = inp.value; redraw(); };
  inp.onkeydown = e => { if (e.key === "Enter") { const h = searchNova(q)[0]; if (h) { keep(q); ctx.go(h.go); } } };
  redraw(); await loadSearchData(); if (ctx.alive()) redraw();
}

// ── the start page ─────────────────────────────────────────────────────────────
export const ENGINES = [
  ["google", "Google", "https://www.google.com/search?q=", "g"], ["ddg", "DuckDuckGo", "https://duckduckgo.com/?q=", "d"], ["bing", "Bing", "https://www.bing.com/search?q=", "b"],
  ["brave", "Brave", "https://search.brave.com/search?q=", "br"], ["startpage", "Startpage", "https://www.startpage.com/do/search?q=", "sp"], ["ecosia", "Ecosia", "https://www.ecosia.org/search?q=", "e"],
  ["youtube", "YouTube", "https://www.youtube.com/results?search_query=", "yt"], ["wikipedia", "Wikipedia", "https://en.wikipedia.org/w/index.php?search=", "w"],
  ["github", "GitHub", "https://github.com/search?q=", "gh"], ["reddit", "Reddit", "https://www.reddit.com/search/?q=", "r"],
];
const START_SECTIONS = [["search", "Search"], ["stats", "Server at a glance"], ["apps", "Your apps"], ["links", "Bookmarks"]];
const startPrefs = () => ({ order: prefs.startOrder || START_SECTIONS.map(s => s[0]), hidden: prefs.startHidden || [], engine: prefs.startEngine || "ddg",
  engines: prefs.startEngines || ["google", "ddg", "youtube", "wikipedia", "github"], suggest: prefs.startSuggest !== false, newTab: !!prefs.startNewTab,
  links: prefs.startLinks || [], name: prefs.startName || "" });
const greeting = () => { const h = new Date().getHours(); return h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening"; };

export async function start(ctx) {
  let q = "", sugg = [], novaHits = [], sel = -1, sugTimer = null;
  const p = startPrefs();
  const go = url => { if (p.newTab) window.open(url, "_blank", "noopener"); else location.href = url; };
  const webSearch = text => {
    const m = /^(\S+)\s+(.+)$/.exec(text.trim()), bang = m && ENGINES.find(e => e[3] === m[1].toLowerCase());
    const eng = bang || ENGINES.find(e => e[0] === p.engine) || ENGINES[1];
    const t = bang ? m[2] : text.trim();
    if (/^https?:\/\/\S+$/.test(t)) return go(t);
    if (/^[\w-]+(\.[\w-]+)+(\/\S*)?$/.test(t) && !/\s/.test(t)) return go("https://" + t);
    go(eng[2] + enc(t));
  };
  const stats = () => { const o = S.overview, m = o?.status?.metrics || {}, n = S.lastNow, list = (m.storage || []).slice(0, 3);
    const tile = (label, v, sub, f, c) => `<div class="stile glass"><small>${label}</small><b>${v}</b><span>${sub || ""}</span>${f != null ? `<div class="bar"><i style="width:${Math.round(Math.max(0, Math.min(1, f)) * 100)}%;background:${c}"></i></div>` : ""}</div>`;
    return `<div class="stiles">${tile("CPU", n?.cpu != null ? Math.round(n.cpu) + "%" : (m.cpu || "—"), m.cpu_temp || "", n?.cpu != null ? n.cpu / 100 : null, "var(--blue)")}
      ${tile("Memory", n?.mem != null ? Math.round(n.mem) + "%" : (m.memory || "—"), m.memory_detail || "", n?.mem != null ? n.mem / 100 : null, "#bf5af2")}
      ${list.map(s => tile(s.name, Math.round(s.pct) + "%", bytes(s.free) + " free", s.pct / 100, s.pct > 90 ? "var(--red)" : "var(--green)")).join("")}
      ${tile("Status", (o?.status?.level || "ok") === "ok" ? "All good" : `${(o?.status?.active || []).filter(a => a.level !== "ok").length} issue(s)`, `${o?.containers?.running ?? "?"}/${o?.containers?.total ?? "?"} running`, null)}</div>`; };
  const appsHtml = () => { const apps = (S.cache["/api/v1/apps"]?.apps || []).filter(a => !a.hidden);
    return apps.length ? `<div class="appgrid">${apps.map(a => `<a class="appcell" data-app="${esc(a.id)}"><span class="appic" data-id="${esc(a.id)}">${esc(a.name[0] || "?")}</span><span class="appname">${esc(a.name)}</span></a>`).join("")}</div>` : note("No apps yet — see Apps."); };
  const linksHtml = () => `<div class="appgrid">${p.links.map((l, i) => `<a class="appcell" href="${esc(l.url)}" ${p.newTab ? 'target="_blank" rel="noopener noreferrer"' : ""} data-li="${i}"><span class="appic">${esc((l.name || "?")[0].toUpperCase())}</span><span class="appname">${esc(l.name)}</span></a>`).join("")}
    <a class="appcell" data-act="addlink"><span class="appic">${I("add")}</span><span class="appname">Add</span></a></div>`;
  const section = id => ({
    search: `<div class="startsearch"><div class="searchbox glass big">${I("search")}<input id="ss" class="sq" placeholder="Search ${esc((ENGINES.find(e => e[0] === p.engine) || ENGINES[1])[1])} or ${esc(serverName())}" value="${esc(q)}" autocomplete="off" aria-label="Search">
        <div class="sugg" id="sugg" hidden></div></div>
      <div class="engines">${p.engines.map(k => ENGINES.find(e => e[0] === k)).filter(Boolean).map(e => `<button class="eng${e[0] === p.engine ? " on" : ""}" data-act="eng:${e[0]}" title="Shortcut: ${e[3]} your search">${esc(e[1])}</button>`).join("")}</div></div>`,
    stats: sec("At a glance") + stats(), apps: sec("Apps") + appsHtml(), links: sec("Bookmarks") + linksHtml(),
  }[id] || "");
  const now = () => new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  const draw = () => {
    ctx.show(`<div class="starthead"><div class="clock" id="clk">${now()}</div><div class="greet">${greeting()}${p.name ? ", " + esc(p.name) : ""}</div></div>
      ${p.order.filter(id => !p.hidden.includes(id)).map(id => `<div data-sec="${id}">${section(id)}</div>`).join("")}`, { title: "Start", actions: [{ icon: "edit", label: "Customize", act: "custom" }] });
    const inp = $("#ss"); if (inp) {
      inp.oninput = () => { q = inp.value; sel = -1; suggest(); };
      inp.onkeydown = e => {
        const items = $$("#sugg [data-s]");
        if (e.key === "ArrowDown" || e.key === "ArrowUp") { e.preventDefault(); sel = Math.max(-1, Math.min(items.length - 1, sel + (e.key === "ArrowDown" ? 1 : -1))); items.forEach((x, i) => x.classList.toggle("on", i === sel)); }
        else if (e.key === "Enter") { e.preventDefault(); if (sel >= 0) items[sel].click(); else if (q.trim()) webSearch(q); }
        else if (e.key === "Escape") { $("#sugg").hidden = true; }
      };
      if (!matchMedia("(pointer: coarse)").matches) inp.focus();
    }
    bind();
  };
  // Live parts (stats, apps, bookmarks) refresh in place, so typing in the search box is never interrupted.
  const refreshSections = () => { $$("[data-sec]", ctx.root).forEach(el => { if (el.dataset.sec !== "search") el.innerHTML = section(el.dataset.sec); }); bind(); };
  const bind = () => {
    $$(".appic[data-id]", ctx.root).forEach(async el => { const { appIconUrl } = await import("./storage.js"); const u = await appIconUrl(el.dataset.id); if (u && ctx.alive()) { el.textContent = ""; el.style.backgroundImage = `url("${u}")`; el.classList.add("img"); } });
    $$("[data-app]", ctx.root).forEach(el => el.onclick = async () => { const { appHrefFor } = await import("./storage.js"); const a = (S.cache["/api/v1/apps"]?.apps || []).find(x => x.id === el.dataset.app); const h = a && appHrefFor(a); h ? go(h) : toast("This app isn't reachable from here"); });
    $$("[data-li]", ctx.root).forEach(el => el.oncontextmenu = e => { e.preventDefault(); editLink(+el.dataset.li); });
  };
  const renderSugg = () => {
    const box = $("#sugg"); if (!box) return;
    const items = [...novaHits.slice(0, 4).map(h => `<button data-s="nova" data-go="${esc(h.go)}">${I(h.icon)}<span><b>${esc(h.title)}</b><small>${esc(h.kind)} · in Nova</small></span></button>`),
      ...sugg.map(s => `<button data-s="web" data-q="${esc(s)}">${I("search")}<span>${esc(s)}</span></button>`)];
    box.hidden = !items.length || !q.trim(); box.innerHTML = items.join("");
    $$("[data-s]", box).forEach(b => b.onclick = () => b.dataset.s === "nova" ? ctx.go(b.dataset.go) : webSearch(b.dataset.q));
  };
  const suggest = () => {
    novaHits = searchNova(q); renderSugg();
    clearTimeout(sugTimer);
    if (!p.suggest || q.trim().length < 2) { sugg = []; return renderSugg(); }
    sugTimer = setTimeout(async () => { const t = q; try { const r = await get(`/api/v1/suggest?q=${enc(t)}`); if (t === q) { sugg = r.suggestions || []; renderSugg(); } } catch {} }, 180);
  };
  const editLink = async i => {
    const l = i >= 0 ? p.links[i] : { name: "", url: "" }; let v = { ...l };
    const r = dialog(i >= 0 ? l.name : "Add a bookmark", "", [{ label: "Cancel", value: null }, ...(i >= 0 ? [{ label: "Delete", color: "var(--red)", value: "del" }] : []), { label: "Save", color: "var(--blue)", value: "save" }],
      `<div class="pad"><input class="field" id="ln" placeholder="Name" value="${esc(l.name)}"><input class="field" id="lu" placeholder="https://…" value="${esc(l.url)}" style="margin-top:8px"></div>`);
    $("#ln").oninput = e => v.name = e.target.value.trim(); $("#lu").oninput = e => v.url = e.target.value.trim();
    const a = await r; if (!a) return;
    const links = [...p.links];
    if (a === "del") links.splice(i, 1);
    else { if (!/^https?:\/\//.test(v.url)) return toast("Give a link starting with http:// or https://"); if (i >= 0) links[i] = v; else links.push({ name: v.name || new URL(v.url).hostname, url: v.url }); }
    prefs.startLinks = links; p.links = links; refreshSections();
  };
  ctx.handlers({
    eng: k => { prefs.startEngine = k; p.engine = k; draw(); },
    addlink: () => editLink(-1),
    custom: () => ctx.go("start-edit"),
  });
  draw();
  const t = setInterval(() => { const c = $("#clk"); if (!c || !ctx.alive()) return clearInterval(t); c.textContent = now(); }, 15000);
  await Promise.all([loadSearchData(), get("/api/v1/apps").catch(() => {})]); if (ctx.alive()) refreshSections();
  const liveNow = async () => { try { const r = await get("/api/v1/stats?since=9e12"); if (r.now?.cpu != null) S.lastNow = r.now; } catch {} };
  await liveNow(); if (ctx.alive()) refreshSections();
  ctx.every(5000, async () => { const { refresh } = await import("./core.js"); await Promise.all([refresh(), liveNow()]); if (ctx.alive()) refreshSections(); });
}

export async function startEdit(ctx) {
  const draw = () => {
    const p = startPrefs();
    ctx.show(sec("Sections · hold and drag to reorder") + `<div class="group glass rl" id="so">${p.order.map(id => { const n = START_SECTIONS.find(s => s[0] === id)?.[1] || id, on = !p.hidden.includes(id);
        return `<div class="row" data-key="${id}"><span class="handle">${I("drag")}</span><div class="t"><b>${esc(n)}</b></div><button data-act="tog:${id}" data-noreorder aria-label="Show">${`<span class="sw${on ? " on" : ""}"></span>`}</button></div>`; }).join("")}</div>`
      + sec("Search engines") + group(ENGINES.map(e => row(e[1], { sub: `Shortcut: ${e[3]} your search${e[0] === p.engine ? " · default" : ""}`, blue: e[0] === p.engine, end: `<span class="sw${p.engines.includes(e[0]) ? " on" : ""}"></span>`, click: `en:${e[0]}` })).join(""))
      + note("Tap an engine to show or hide it on the start page; pick the default with its button there. Type a shortcut first, like “yt lofi” or “w Debian”.")
      + sec("More") + group(switchRow("Suggestions as you type", "From DuckDuckGo, fetched by your server — the browser only talks to Nova", p.suggest, "sug")
        + switchRow("Open results in a new tab", null, p.newTab, "nt")
        + `<div class="row"><div class="t"><b>Your name</b><small>For the greeting</small></div><input class="field" id="nm" style="max-width:200px" value="${esc(p.name)}" placeholder="Optional"></div>`)
      + sec("Open on start") + group([["home", "Home"], ["start", "Start page"], ["status", "Status"], ["apps", "Apps"], ["inbox", "Inbox"], ["dashboard", "Dashboard"]].map(([k, l]) =>
          row(l, { end: `<span class="radio${(prefs.startRoute || "home") === k ? " on" : ""}"></span>`, click: `sr:${k}` })).join(""))
      + note("Use the start page as your browser's new-tab page: set the new-tab or home page to this address followed by #/start."), { title: "Customize start page" });
    reorderable($("#so"), ids => { prefs.startOrder = ids; draw(); });
    $("#nm").oninput = e => { prefs.startName = e.target.value.trim().slice(0, 30); };
  };
  ctx.handlers({
    tog: id => { const h = new Set(startPrefs().hidden); h.has(id) ? h.delete(id) : h.add(id); prefs.startHidden = [...h]; draw(); },
    en: k => { const s = new Set(startPrefs().engines); s.has(k) ? s.delete(k) : s.add(k); if (!s.size) s.add(k); prefs.startEngines = ENGINES.map(e => e[0]).filter(x => s.has(x)); draw(); },
    sug: () => { prefs.startSuggest = !startPrefs().suggest; draw(); }, nt: () => { prefs.startNewTab = !startPrefs().newTab; draw(); },
    sr: k => { prefs.startRoute = k; draw(); toast("Nova opens there next time"); },
  });
  draw();
}

/** Menu → Favorites: any screen in Nova, starred (saved in this browser). */
export const favoritesHtml = () => {
  const favs = (prefs.favorites || []).map(f => PLACES.find(p => p[0] === f)).filter(Boolean);
  return sec("Favorites") + group(favs.map(p => row(p[0], { icon: p[2], tint: "var(--amber)", click: "go:" + p[3] })).join("")
    + row(favs.length ? "Edit favorites" : "Add favorites", { sub: favs.length ? "" : "Star the screens you use most — they show up here", blue: !favs.length, icon: favs.length ? "edit" : "star", tint: "var(--sub)", click: "go:favorites" }));
};
export async function favorites(ctx) {
  const draw = () => ctx.show(`${note("Starred screens appear at the top of Menu, in this order.")}${group(PLACES.map(p => { const on = (prefs.favorites || []).includes(p[0]);
      return row(p[0], { icon: p[2], tint: on ? "var(--amber)" : "var(--sub)", click: "fav:" + p[0], end: `<span class="star${on ? " on" : ""}" aria-label="${on ? "Remove from favorites" : "Add to favorites"}">${I(on ? "star" : "starOutline")}</span>` }); }).join(""))}`, { title: "Favorites" });
  ctx.handlers({ fav: (...a) => { const t = a.filter(x => typeof x === "string").join(":"), f = prefs.favorites || []; prefs.favorites = f.includes(t) ? f.filter(x => x !== t) : [...f, t]; draw(); } });
  draw();
}
