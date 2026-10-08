// Nova web — the same security model as the Android app, in a browser.
//
// * This browser has its own ECDSA P-256 key, made by Web Crypto as NON-EXTRACTABLE and kept in
//   IndexedDB: page scripts can use it to sign, but nobody (not even this code) can read it out.
// * Every API request is signed exactly like the app's:  METHOD\nPATH\nTIME_MS\nNONCE\nsha256(BODY).
// * A browser is added only when an admin phone approves its code (with fingerprint).
// * Risky actions from a browser are forwarded to your phone; you approve them there.

const $ = (s, r = document) => r.querySelector(s);
const enc = s => new TextEncoder().encode(s);
const hex = b => [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, "0")).join("");
const b64 = b => btoa(String.fromCharCode(...new Uint8Array(b)));
const b64url = b => b64(b).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── icons (Material Symbols Rounded paths) ──────────────────────────────────────
const P = {
  home: "M4 21V9l8-6 8 6v12h-6v-7h-4v7Z",
  status: "M3 13h4l2-5 4 10 2-5h6v-2h-4.6L15 13.4 11 3.6 8.6 11H3Z",
  box: "M12 2 3 7v10l9 5 9-5V7Zm0 2.3 6.4 3.6L12 11.5 5.6 7.9ZM5 9.6l6 3.4v6.6l-6-3.3Zm8 10V13l6-3.4v6.7Z",
  store: "M5 4h14l2 5c0 1.5-1.2 2.7-2.7 2.7A2.7 2.7 0 0 1 15.7 10 2.7 2.7 0 0 1 13 11.7 2.7 2.7 0 0 1 10.3 10 2.7 2.7 0 0 1 7.7 11.7 2.7 2.7 0 0 1 5 10 2.7 2.7 0 0 1 3 9ZM5 13h2v5h10v-5h2v7H5Z",
  dash: "M3 13h8V3H3Zm0 8h8v-6H3Zm10 0h8V11h-8Zm0-18v6h8V3Z",
  bell: "M12 22a2 2 0 0 0 2-2h-4a2 2 0 0 0 2 2Zm6-6v-5c0-3.1-1.6-5.6-4.5-6.3V4a1.5 1.5 0 0 0-3 0v.7C7.6 5.4 6 7.9 6 11v5l-2 2v1h16v-1Z",
  gear: "M19.4 13a7.6 7.6 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7.4 7.4 0 0 0-1.7-1L14.9 3h-4l-.4 2.7a7.4 7.4 0 0 0-1.7 1l-2.5-1-2 3.5L6.4 11a7.6 7.6 0 0 0 0 2l-2.1 1.6 2 3.5 2.5-1a7.4 7.4 0 0 0 1.7 1l.4 2.7h4l.4-2.7a7.4 7.4 0 0 0 1.7-1l2.5 1 2-3.5ZM12.9 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7Z",
  back: "M15.4 4.6 14 3.2 5.2 12l8.8 8.8 1.4-1.4L8 12Z",
  bulb: "M9 21h6v-1H9Zm3-19a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2Z",
  cpu: "M9 9h6v6H9Zm12 2V9h-2V7a2 2 0 0 0-2-2h-2V3h-2v2h-2V3H9v2H7a2 2 0 0 0-2 2v2H3v2h2v2H3v2h2v2a2 2 0 0 0 2 2h2v2h2v-2h2v2h2v-2h2a2 2 0 0 0 2-2v-2h2v-2h-2v-2ZM17 17H7V7h10Z",
  mem: "M15 9H9v6h6Zm-2 4h-2v-2h2Zm8-2V9h-2V7a2 2 0 0 0-2-2h-2V3h-2v2h-2V3H9v2H7a2 2 0 0 0-2 2v2H3v2h2v2H3v2h2v2a2 2 0 0 0 2 2h2v2h2v-2h2v2h2v-2h2a2 2 0 0 0 2-2v-2h2v-2h-2v-2Z",
  disk: "M2 20h20v-4H2Zm2-3h2v2H4ZM2 4v4h20V4Zm4 3H4V5h2Zm-4 7h20v-4H2Zm2-3h2v2H4Z",
  temp: "M15 13V5a3 3 0 0 0-6 0v8a5 5 0 1 0 6 0Zm-3-9a1 1 0 0 1 1 1v3h-2V5a1 1 0 0 1 1-1Z",
  net: "M16 17.01V10h-2v7.01h-3L15 21l4-3.99ZM9 3 5 6.99h3V14h2V6.99h3Z",
  backup: "M19.4 10A7.5 7.5 0 0 0 5.4 8 6 6 0 0 0 6 20h13a5 5 0 0 0 .4-10ZM14 13v4h-4v-4H7l5-5 5 5Z",
  term: "M20 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2Zm0 14H4V8h16ZM6.7 16.3 5.3 14.9 7.6 12.6 5.3 10.3 6.7 8.9l3.7 3.7ZM12 15h6v2h-6Z",
  play: "M8 5v14l11-7Z", stop: "M6 6h12v12H6Z", restart: "M12 5V2L8 6l4 4V7a5 5 0 1 1-5 5H5a7 7 0 1 0 7-7Z",
  update: "M5 20h14v-2H5Zm7-3 5-5h-3V4h-4v8H7Z", logs: "M4 6h16v2H4Zm0 5h16v2H4Zm0 5h10v2H4Z",
  more: "M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm0 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4Zm0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4Z",
  check: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm-2 15-5-5 1.4-1.4 3.6 3.6 7.6-7.6L19 8Z",
  err: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm1 15h-2v-2h2Zm0-4h-2V7h2Z",
  edit: "M3 17.2V21h3.8L17.8 10l-3.8-3.8ZM20.7 7a1 1 0 0 0 0-1.4l-2.3-2.3a1 1 0 0 0-1.4 0l-1.8 1.8 3.7 3.7Z",
  full: "M7 14H5v5h5v-2H7Zm-2-4h2V7h3V5H5Zm12 7h-3v2h5v-5h-2ZM14 5v2h3v3h2V5Z",
  ram: "M17 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2Zm-1 14H8v-2h8Zm0-4H8v-2h8Zm0-4H8V7h8Z",
  power: "M13 3h-2v10h2Zm4.8 2.2-1.4 1.4A7 7 0 1 1 7.6 6.6L6.2 5.2A9 9 0 1 0 17.8 5.2Z",
  logo: "M51,30 C53,46 59.5,52.5 75,54.5 C59.5,56.5 53,63 51,79 C49,63 42.5,56.5 27,54.5 C42.5,52.5 49,46 51,30 Z",
};
const I = (n, c = "") => `<svg class="i ${c}" viewBox="0 0 24 24" aria-hidden="true"><path d="${P[n] || ""}"/></svg>`;

// ── this browser's key (IndexedDB) ──────────────────────────────────────────────
const DB = { db: null };
function idb() {
  if (DB.db) return Promise.resolve(DB.db);
  return new Promise((ok, no) => {
    const r = indexedDB.open("nova", 1);
    r.onupgradeneeded = () => r.result.createObjectStore("kv");
    r.onsuccess = () => ok(DB.db = r.result); r.onerror = () => no(r.error);
  });
}
async function kv(k, v) {
  const db = await idb();
  return new Promise((ok, no) => {
    const tx = db.transaction("kv", v === undefined ? "readonly" : "readwrite"); const st = tx.objectStore("kv");
    const r = v === undefined ? st.get(k) : v === null ? st.delete(k) : st.put(v, k);
    r.onsuccess = () => ok(r.result); r.onerror = () => no(r.error);
  });
}
async function pemOf(pub) {
  const der = await crypto.subtle.exportKey("spki", pub);
  return "-----BEGIN PUBLIC KEY-----\n" + b64(der).match(/.{1,64}/g).join("\n") + "\n-----END PUBLIC KEY-----\n";
}

// ── signed API ───────────────────────────────────────────────────────────────────
const S = { device: null, keys: null, me: null, overview: null, fan: null, cache: {}, wide: false };
class ApiError extends Error { constructor(code, msg) { super(msg); this.code = code; } }

async function api(method, path, body) {
  const bodyStr = body === undefined ? "" : JSON.stringify(body);
  const ts = String(Date.now()), nonce = b64url(crypto.getRandomValues(new Uint8Array(18)));
  const digest = hex(await crypto.subtle.digest("SHA-256", enc(bodyStr)));
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, S.keys.privateKey, enc(`${method}\n${path}\n${ts}\n${nonce}\n${digest}`));
  const r = await fetch(path, { method, body: method === "GET" ? undefined : bodyStr, credentials: "same-origin",
    headers: { "Content-Type": "application/json", "X-Nova-Device": S.device, "X-Nova-Time": ts, "X-Nova-Nonce": nonce, "X-Nova-Signature": b64(sig) } });
  let j = {}; try { j = await r.json(); } catch {}
  if (r.status === 202 && j.approval) return waitApproval(j.approval);          // risky: approve on the phone
  if (r.status === 401) { if (S.me) toast("This browser isn't authorized anymore"); throw new ApiError(401, "unauthorized"); }
  if (!r.ok) throw new ApiError(r.status, j.message || j.error || `HTTP ${r.status}`);
  if (method === "GET") S.cache[path] = j;
  return j;
}
const get = p => api("GET", p), post = (p, b = {}) => api("POST", p, b);

async function waitApproval(id) {
  sheet(`<h2>Approve on your phone</h2><p>Nova sent this to your admin phone. Open the notification (or Nova → Menu → Users &amp; devices → Approvals) and confirm with your fingerprint.</p>
    <div class="center" style="padding:18px"><div class="spinner" style="margin:auto"></div></div>
    <div class="acts"><button data-x>Stop waiting</button></div>`);
  let stop = false; $("#sheet [data-x]").onclick = () => { stop = true; closeSheet(); };
  for (let i = 0; i < 300 && !stop; i++) {
    await sleep(2000);
    let a; try { a = await get(`/api/v1/approvals/${id}`); } catch { continue; }
    if (a.state === "done") { closeSheet(); return a.result || {}; }
    if (["failed", "denied", "expired"].includes(a.state)) { closeSheet(); throw new ApiError(403, a.state === "denied" ? "Declined on the phone" : (a.result?.error || `Request ${a.state}`)); }
  }
  throw new ApiError(408, "No approval");
}

// ── tiny UI helpers ──────────────────────────────────────────────────────────────
let toastT; function toast(m) { const t = $("#toast"); t.textContent = m; t.classList.add("on"); clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove("on"), 2600); }
function sheet(html) { const s = $("#sheet"); s.innerHTML = `<div class="box">${html}</div>`; s.hidden = false; s.onclick = e => { if (e.target === s) closeSheet(); }; }
function closeSheet() { $("#sheet").hidden = true; $("#sheet").innerHTML = ""; }
function confirmSheet(title, text, ok, danger = true) {
  return new Promise(res => {
    sheet(`<h2>${esc(title)}</h2><p>${esc(text)}</p><div class="acts"><button data-c>Cancel</button><button data-o style="color:var(${danger ? "--red" : "--blue"})">${esc(ok)}</button></div>`);
    $("#sheet [data-c]").onclick = () => { closeSheet(); res(false); }; $("#sheet [data-o]").onclick = () => { closeSheet(); res(true); };
  });
}
async function act(fn, ok) { try { const r = await fn(); if (ok) toast(ok); return r; } catch (e) { toast(e.message); } }
const levelColor = l => l === "critical" ? "var(--red)" : l === "warning" ? "var(--amber)" : l === "ok" || l === "resolved" ? "var(--green)" : "var(--blue)";
const pct = s => +((/(\d+)%/.exec(s || "") || [])[1] || 0);
const rate = b => b >= 1e6 ? (b / 1e6).toFixed(1) + " MB/s" : b >= 1e3 ? Math.round(b / 1e3) + " kB/s" : Math.round(b) + " B/s";
const isAdmin = () => S.me?.role !== "viewer";
const serverName = () => { const s = S.overview?.server; return s?.display_name || (s?.name || "Nova").replace(/(^|-)\w/g, m => m.toUpperCase()); };
function spark(canvas, vals, color, max) {
  const dpr = devicePixelRatio || 1, w = canvas.clientWidth, h = canvas.clientHeight; if (!w || vals.length < 2) return;
  canvas.width = w * dpr; canvas.height = h * dpr; const c = canvas.getContext("2d"); c.scale(dpr, dpr);
  const top = max || Math.max(1, Math.max(...vals) * 1.15), dx = w / (vals.length - 1), y = v => h - Math.min(1, v / top) * (h - 4) - 2;
  c.beginPath(); vals.forEach((v, i) => i ? c.lineTo(i * dx, y(v)) : c.moveTo(0, y(v)));
  const g = c.createLinearGradient(0, 0, 0, h); g.addColorStop(0, color + "55"); g.addColorStop(1, color + "00");
  c.lineWidth = 2; c.strokeStyle = color; c.lineJoin = "round"; c.stroke();
  c.lineTo(w, h); c.lineTo(0, h); c.closePath(); c.fillStyle = g; c.fill();
}
const css = v => getComputedStyle(document.documentElement).getPropertyValue(v).trim();

// ── shell & routing ──────────────────────────────────────────────────────────────
const NAV = [["home", "Home", "home"], ["status", "Status", "status"], ["containers", "Containers", "box"], ["store", "Store", "store"],
             ["dashboard", "Dashboard", "dash"], ["inbox", "Inbox", "bell"], ["settings", "Settings", "gear"]];
const route = () => (location.hash.replace(/^#\/?/, "") || "home").split("/").map(decodeURIComponent);

function shell(content, { narrow = true } = {}) {
  const [r] = route();
  $("#app").innerHTML = `<div class="shell">
    <nav class="rail"><svg class="logo" viewBox="0 0 108 108"><defs><linearGradient id="lg" x1=".1" y1="0" x2=".9" y2="1"><stop offset="0" stop-color="#3e91ff"/><stop offset="1" stop-color="#8a4dff"/></linearGradient></defs><rect width="108" height="108" rx="30" fill="url(#lg)"/><path fill="#fff" d="${P.logo}"/></svg>
      ${NAV.map(([k, l, i]) => `<button class="${r === k ? "on" : ""}" data-go="${k}"><span class="ic">${I(i)}</span>${l}</button>`).join("")}</nav>
    <main class="main ${narrow ? "narrowcol" : ""}"><div class="page">${content}</div></main>
    <nav class="bottom">${NAV.filter(n => ["store", "home", "containers", "settings"].includes(n[0])).map(([k, l, i]) => `<button aria-label="${l}" class="${r === k ? "on" : ""}" data-go="${k}">${I(i)}</button>`).join("")}</nav></div>`;
  document.querySelectorAll("[data-go]").forEach(b => b.onclick = () => go(b.dataset.go));
}
function go(h) { location.hash = "#/" + h; }
const header = (title, back = true, actions = "") => `<div class="ph">${back ? `<button class="circle" data-back aria-label="Back">${I("back")}</button>` : ""}<h1 class="${back ? "" : "big"}">${esc(title)}</h1>${actions}</div>`;
function wireBack() { document.querySelectorAll("[data-back]").forEach(b => b.onclick = () => history.length > 1 ? history.back() : go("home")); }

let renderSeq = 0;
async function render() {
  const seq = ++renderSeq; const [r, a, b] = route();
  const views = { home, status, containers, store, dashboard, inbox, settings, lighting, devices };
  try { await (views[r] || home)(a, b, seq); } catch (e) { if (e.code !== 401) toast(e.message); }
}
window.addEventListener("hashchange", render);

// ── views ────────────────────────────────────────────────────────────────────────
async function refreshOverview() {
  S.overview = await get("/api/v1/overview"); S.fan = S.overview.fan;
  const acc = S.overview.server?.accent;                       // the server's accent colour (set in the app)
  if (/^#[0-9a-f]{6}$/i.test(acc || "")) document.documentElement.style.setProperty("--blue", acc);
  return S.overview;
}
function applyTheme() { const t = localStorage.getItem("nova-theme") || "system";
  if (t === "system") delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = t; }
applyTheme();

function statCards(st) {
  const n = st?.now, m = S.overview?.status?.metrics || {}, cs = S.overview?.containers;
  return `<div class="grid">
    ${card("CPU", "cpu", n ? Math.round(n.cpu) + "%" : "—", n?.temp ? Math.round(n.temp) + "°C" : m.cpu_temp || "", n ? n.cpu / 100 : null, "--blue")}
    ${card("Memory", "mem", n ? Math.round(n.mem) + "%" : (m.memory || "—").split(" ")[0], n ? `${n.mem_used_gb} / ${n.mem_total_gb} GB` : "", n ? n.mem / 100 : pct(m.memory) / 100, "--violet")}
    ${card("Disk", "disk", (/\(([^)]*free)\)/.exec(m.root_used || "") || [])[1] || "—", "system drive", pct(m.root_used) / 100, "--green")}
    ${card("Services", "box", cs ? `${cs.running}/${cs.total}` : "—", m.websites ? "sites " + m.websites : "containers running", cs?.total ? cs.running / cs.total : null, "--amber")}
  </div>`;
}
function card(l, ic, v, sub, frac, color) {
  return `<div class="card click" data-go="status"><div class="lbl" style="color:var(${color})">${I(ic)}<span class="muted">${l}</span></div>
    <div class="val">${esc(v)}</div><div class="sub">${esc(sub)}</div>${frac != null ? `<div class="bar"><i style="width:${Math.round(frac * 100)}%;background:var(${color})"></i></div>` : ""}</div>`;
}
function bannerHtml() {
  const st = S.overview?.status || {}, m = st.metrics || {};
  if (st.level && st.level !== "ok") return `<div class="banner" data-go="inbox"><span class="dot" style="background:${levelColor(st.level)}"></span><span>${esc(st.headline)}</span>›</div>`;
  if ((m.data_backup || "").includes("running")) return `<div class="banner" data-go="status"><span class="dot" style="background:var(--blue)"></span><span>Backup in progress</span>›</div>`;
  return m.data_backup ? `<div class="banner" data-go="status"><span class="dot" style="background:var(--green)"></span><span>Backup ${esc(m.data_backup)} · ${esc(m.cpu_temp || "")} CPU</span>›</div>` : "";
}
function fanTile() {
  const f = S.fan; if (!f || S.overview?.features?.lighting === false) return "";
  const on = f.on !== false, v = f.brightness ?? 50;
  return `<div class="slidetile" id="fantile" title="Drag to dim · click to switch on/off"><div class="fill" style="width:${on ? v : 0}%"></div>
    <div class="in">${I("bulb")}<div style="flex:1"><b>Fan light</b><div class="muted" id="fanpct" style="font-size:12px">${on ? v + "%" : "Off"}</div></div><span class="muted" style="font-size:12px">${isAdmin() ? "Drag to dim" : "View only"}</span></div></div>`;
}
function wireFan() {
  const t = $("#fantile"); if (!t || !isAdmin()) return;
  let drag = null, moved = false;
  const at = e => Math.min(100, Math.max(1, Math.round((e.clientX - t.getBoundingClientRect().left) / t.clientWidth * 100)));
  t.onpointerdown = e => { drag = at(e); moved = false; t.setPointerCapture(e.pointerId); };
  t.onpointermove = e => { if (drag == null) return; moved = true; drag = at(e); $(".fill", t).style.width = drag + "%"; $("#fanpct").textContent = drag + "%"; };
  t.onpointerup = async e => {
    const v = drag; drag = null;
    const patch = moved ? { on: true, brightness: v } : { on: S.fan.on === false };
    S.fan = { ...S.fan, ...patch };
    await act(async () => { S.fan = await post("/api/v1/fan", patch); });
    render();
  };
}

async function home(_a, _b, seq) {
  const draw = () => {
    const st = S.overview?.status, cs = S.overview?.containers, lvl = st?.level || "ok";
    const head = !st ? "Connecting…" : lvl === "ok" ? "All systems normal" : `${st.active_count} need${st.active_count === 1 ? "s" : ""} attention`;
    shell(`<div class="ph"><h1 class="big">${esc(serverName())}</h1><button class="circle" data-ref aria-label="Refresh">${I("restart")}</button></div>
      <div class="status" data-go="status"><span style="color:${levelColor(lvl)}">${I(lvl === "ok" ? "check" : "err")}</span>${head}${cs ? ` &nbsp;|&nbsp; ${cs.running}/${cs.total} running` : ""}</div>
      ${bannerHtml()}
      <div class="home2"><div>
        <div class="pills">${[["bell", "Inbox", "inbox"], ["status", "Status", "status"], ["box", "Containers", "containers"], ["disk", "Storage", "status"]].map(([i, l, g]) => `<button data-go="${g}">${I(i)}${l}</button>`).join("")}</div>
        ${fanTile()}
        <div class="sec">Quick actions</div>
        <div class="group">
          <div class="row click" data-q="backup"><span class="ri" style="background:#3e91ff28;color:var(--blue)">${I("backup")}</span><div class="t"><b>Back up now</b><small>${esc(S.overview?.status?.metrics?.data_backup || "")}</small></div></div>
          <div class="row click" data-q="ram"><span class="ri" style="background:#3e91ff28;color:var(--blue)">${I("ram")}</span><div class="t"><b>Free RAM</b><small>Drop the disk cache (safe)</small></div></div>
          <div class="row click" data-go="dashboard"><span class="ri" style="background:#64d2ff28;color:#64d2ff">${I("dash")}</span><div class="t"><b>Dashboard mode</b><small>Always-on screen</small></div></div>
        </div></div>
        <div>${statCards(S.cache["/api/v1/stats"])}</div></div>`, { narrow: false });
    $("[data-ref]").onclick = () => render(); wireFan();
    document.querySelectorAll("[data-q]").forEach(b => b.onclick = () => {
      if (!isAdmin()) return toast("This browser has view-only access");
      if (b.dataset.q === "backup") act(() => post("/api/v1/actions/backup"), "Backup started");
      else act(async () => { const r = await post("/api/v1/actions/free-ram"); toast(`Freed ${r.freed_mb} MB · ${r.available_mb} MB available`); });
    });
  };
  draw();
  await refreshOverview(); await get("/api/v1/stats").catch(() => {}); if (seq === renderSeq) draw();
}

async function status(_a, _b, seq) {
  const draw = () => {
    const st = S.overview?.status || {}, m = st.metrics || {}, s = S.cache["/api/v1/stats"], n = s?.now, h = s?.history || [];
    const lvl = st.level || "ok";
    const storage = [["Photos", "photo_pool_used"], ["System drive", "root_used"], ["Cold storage", "cold_storage_used"], ["Backup drive", "backup_drive_used"]].filter(x => m[x[1]]);
    shell(`${header("Server status")}
      <div class="center" style="padding:6px 0 10px"><div style="color:${levelColor(lvl)};transform:scale(2.2);margin:16px">${I(lvl === "ok" ? "check" : "err")}</div>
        <div style="font-size:20px;font-weight:700">${esc(lvl === "ok" ? "All systems normal" : st.headline)}</div>
        <div class="muted">Updated ${esc((/\d{1,2}:\d{2}/.exec(st.updated_local || "") || ["—"])[0])} · up ${n ? Math.floor(n.uptime_s / 86400) + "d " + Math.floor(n.uptime_s % 86400 / 3600) + "h" : esc(m.uptime || "—")}</div></div>
      ${(st.active || []).filter(a => a.level !== "ok").length ? `<div class="sec">Needs attention</div><div class="group">${st.active.filter(a => a.level !== "ok").map(a => `<div class="row"><span class="dot" style="background:${levelColor(a.level)}"></span><div class="t"><b>${esc(a.title.replace(/^[^\p{L}\p{N}]+\s*/u, ""))}</b><small>${esc(a.detail)}</small></div></div>`).join("")}</div>` : ""}
      <div class="sec">Last hour</div>
      <div class="grid">
        ${gcard("CPU", n ? Math.round(n.cpu) + "%" : "—", n ? `load ${n.load} · ${n.cores} cores` : "", "c1")}
        ${gcard("Memory", n ? Math.round(n.mem) + "%" : "—", n ? `${n.mem_used_gb} of ${n.mem_total_gb} GB` : "", "c2")}
        ${gcard("CPU temperature", n?.temp ? Math.round(n.temp) + "°C" : m.cpu_temp || "—", n?.nvme_temp ? `NVMe ${Math.round(n.nvme_temp)}°C` : "", "c3")}
        ${gcard("Network", n ? "↓ " + rate(n.rx) : "—", n ? "↑ " + rate(n.tx) : "", "c4")}
      </div>
      <div class="sec">Storage</div><div class="group">${storage.map(([l, k]) => `<div class="row" style="display:block"><div style="display:flex"><b style="flex:1;font-weight:400">${l}</b><span class="muted">${esc(m[k])}</span></div>
        <div class="bar" style="margin-top:8px"><i style="width:${pct(m[k])}%;background:${pct(m[k]) > 95 ? "var(--red)" : pct(m[k]) > 85 ? "var(--amber)" : "var(--blue)"}"></i></div></div>`).join("")}</div>
      <div class="sec">Backups</div><div class="group">${[["Data backup", m.data_backup], ["Backup sets", m.backup_sets], ["Photo check", m.backup_verify], ["Server settings backup", m.config_backup], ["Photo database backup", m.immich_db_backup]]
        .filter(x => x[1]).map(([l, v]) => `<div class="row"><div class="t"><b>${l}</b><small>${esc(v)}</small></div></div>`).join("")}</div>
      <div class="sec">Services</div><div class="group">${[["Containers", m.containers], ["Websites", m.websites], ["Swap", m.swap]].filter(x => x[1]).map(([l, v]) => `<div class="row"><div class="t"><b>${l}</b><small>${esc(v)}</small></div></div>`).join("")}</div>`);
    wireBack();
    requestAnimationFrame(() => {
      spark($("#c1"), h.map(x => x.cpu), css("--blue"), 100); spark($("#c2"), h.map(x => x.mem), "#bf5af2", 100);
      spark($("#c3"), h.map(x => x.temp || 0), css("--amber")); spark($("#c4"), h.map(x => (x.rx || 0) + (x.tx || 0)), css("--green"));
    });
  };
  draw(); await Promise.all([refreshOverview(), get("/api/v1/stats")]); if (seq === renderSeq) draw();
}
const gcard = (l, v, sub, id) => `<div class="card"><div class="lbl">${l}</div><div class="val">${esc(v)}</div><div class="sub">${esc(sub)}</div><canvas id="${id}"></canvas></div>`;

async function containers(name, sub, seq) {
  if (name && sub === "logs") return logsView(name);
  if (name && sub === "shell") return shellView(name);
  const list = S.cache["/api/v1/containers"]?.containers;
  const draw = (c) => {
    const items = (S.cache["/api/v1/containers"]?.containers || []);
    const stacks = {}; items.forEach(x => (stacks[x.stack] ||= []).push(x));
    const singles = Object.values(stacks).filter(v => v.length === 1).flat();
    const groups = [...Object.entries(stacks).filter(([, v]) => v.length > 1), ...(singles.length ? [["Apps", singles]] : [])];
    const up = items.filter(x => x.state === "running").length;
    const left = `${header("Containers")}<div class="status" style="cursor:default"><span class="dot" style="background:${up === items.length ? "var(--green)" : "var(--amber)"}"></span>${S.cache["/api/v1/containers"] ? `${up} of ${items.length} running` : "Loading…"}</div>
      ${groups.map(([g, cs]) => `<div class="sec">${esc(g[0].toUpperCase() + g.slice(1))}</div><div class="group">${cs.map(x => `<div class="row click" data-c="${esc(x.name)}" style="${x.name === name ? "background:color-mix(in srgb,var(--blue) 14%,transparent)" : ""}"><div class="t"><b>${esc(x.name)}</b><small>${esc(x.image.split("/").pop())} · ${esc(x.health ? x.state + ", " + x.health : x.state)}</small></div><span class="dot" style="background:${x.health === "unhealthy" ? "var(--amber)" : x.state === "running" ? "var(--green)" : "var(--sub)"}"></span></div>`).join("")}</div>`).join("")}`;
    shell(`<div class="split ${name ? "has" : ""}"><div class="left">${name && innerWidth < 1150 ? "" : left}</div>${name ? `<div>${detail(c)}</div>` : ""}</div>`, { narrow: !name });
    wireBack(); document.querySelectorAll("[data-c]").forEach(r => r.onclick = () => go("containers/" + r.dataset.c));
    document.querySelectorAll("[data-ca]").forEach(b => b.onclick = () => containerAction(name, b.dataset.ca));
  };
  const detail = c => {
    if (!c) return header(name) + `<div class="center muted" style="padding:40px">Loading…</div>`;
    const running = c.state === "running";
    return `${header(name)}<div class="center" style="padding:14px 0"><div class="muted" style="font-weight:600;font-size:17px">${esc(c.state)}${c.health ? " · " + esc(c.health) : ""}</div><div class="muted" style="font-size:13px">${esc(c.image)}</div></div>
      ${isAdmin() ? `<div class="pills">${running ? `<button data-ca="stop">${I("stop")}Stop</button>` : `<button data-ca="start">${I("play")}Start</button>`}<button data-ca="restart">${I("restart")}Restart</button><button data-ca="update">${I("update")}Update</button><button data-ca="shell">${I("term")}Shell</button></div>` : ""}
      <div class="sec">Live</div><div class="group">${[["CPU", c.cpu], ["Memory", c.mem], ["Network in / out", c.net], ["Restarts", String(c.restarts ?? "")]].map(([l, v]) => `<div class="row"><div class="t"><b>${l}</b><small>${esc(v || "—")}</small></div></div>`).join("")}
        <div class="row click" data-ca="logs"><div class="t"><b>Logs</b><small class="blue">See what it's been saying</small></div></div></div>
      ${(c.ports || []).length ? `<div class="sec">Ports</div><div class="group">${c.ports.map(p => `<div class="row"><div class="t"><b>${esc(p)}</b></div></div>`).join("")}</div>` : ""}
      ${(c.mounts || []).length ? `<div class="sec">Folders</div><div class="group">${c.mounts.map(m => `<div class="row"><div class="t"><b>${esc(m.target)}</b><small>${esc(m.source)}${m.rw === false ? " · read-only" : ""}</small></div></div>`).join("")}</div>` : ""}
      ${(c.env || []).length ? `<div class="sec">Environment</div><div class="group">${c.env.map(e => `<div class="row"><div class="t"><b>${esc(e.key)}</b><small>${esc(e.value)}</small></div></div>`).join("")}</div>` : ""}`;
  };
  draw(name ? S.cache[`/api/v1/containers/${name}`] : null);
  await get("/api/v1/containers"); const c = name ? await get(`/api/v1/containers/${encodeURIComponent(name)}`) : null;
  if (seq === renderSeq) draw(c);
}
async function containerAction(name, a) {
  if (a === "logs" || a === "shell") return go(`containers/${name}/${a}`);
  if (a === "update") return act(async () => { toast("Updating…"); let j = (await post(`/api/v1/containers/${name}/update`)).job;
    while (j.state === "running") { await sleep(2000); j = await get(`/api/v1/jobs/${j.id}`); } toast(j.state === "done" ? `${name} is up to date` : `Update failed: ${j.result?.error}`); render(); });
  if (a !== "start" && !(await confirmSheet(`${a[0].toUpperCase() + a.slice(1)} ${name}?`, "Your phone will ask you to confirm with your fingerprint.", a[0].toUpperCase() + a.slice(1)))) return;
  await act(() => post(`/api/v1/containers/${name}/${a}`), `${name}: ${a} done`); render();
}
async function logsView(name) {
  shell(`${header("Logs · " + name)}<div class="term" id="logs">Loading…</div>`, { narrow: false }); wireBack();
  const draw = lines => { const el = $("#logs"); if (!el) return false;
    el.innerHTML = lines.map(l => { const ts = l.split(" ")[0], msg = l.slice(ts.length + 1); const cls = /error|fatal|panic|exception/i.test(msg) ? "err" : /warn/i.test(msg) ? "warn" : "";
      return `<span class="ts">${esc(ts.slice(11, 19))}</span> <span class="${cls}">${esc(msg)}</span>`; }).join("\n"); el.scrollTop = el.scrollHeight; return true; };
  const me = location.hash;
  while (location.hash === me) { try { if (!draw((await get(`/api/v1/containers/${encodeURIComponent(name)}/logs?lines=400`)).lines || [])) break; } catch {} await sleep(4000); }
}
async function shellView(name) {
  shell(`${header("Terminal · " + name)}<div class="term" id="out">Asking your phone to approve…</div>
    <div style="display:flex;gap:8px;padding:10px 14px"><input class="field" id="cmd" placeholder="command" autocomplete="off" autocapitalize="off" spellcheck="false" style="font-family:ui-monospace,monospace"><button class="btn" id="send">Send</button></div>
    <div class="chips">${[["Ctrl-C", "\u0003"], ["Tab", "\t"], ["ls -la", "ls -la\n"], ["df -h", "df -h\n"]].map(([l, v]) => `<button class="chip" data-k="${encodeURIComponent(v)}">${l}</button>`).join("")}</div>`, { narrow: false });
  wireBack();
  let sid; try { sid = (await post(`/api/v1/containers/${name}/shell`)).session; } catch (e) { $("#out").textContent = e.message; return; }
  let off = 0, out = ""; const me = location.hash;
  const send = t => post(`/api/v1/shell/${sid}`, { input: t }).catch(e => toast(e.message));
  $("#send").onclick = () => { send($("#cmd").value + "\n"); $("#cmd").value = ""; };
  $("#cmd").onkeydown = e => { if (e.key === "Enter") $("#send").click(); };
  document.querySelectorAll("[data-k]").forEach(b => b.onclick = () => send(decodeURIComponent(b.dataset.k)));
  window.addEventListener("hashchange", () => api("DELETE", `/api/v1/shell/${sid}`).catch(() => {}), { once: true });
  while (location.hash === me) {
    try { const r = await get(`/api/v1/shell/${sid}?offset=${off}`); if (r.data) { out = (out + r.data.replace(/\x1b\[[0-9;?]*[A-Za-z]/g, "")).slice(-200000); const el = $("#out"); el.textContent = out; el.scrollTop = el.scrollHeight; }
      off = r.offset; if (!r.alive) { $("#out").textContent += "\n[session ended]"; break; } } catch {}
    await sleep(400);
  }
}

async function store(id, _b, seq) {
  const draw = () => {
    const apps = S.cache["/api/v1/store"]?.items || [];
    if (id) {
      const a = apps.find(x => x.id === id); if (!a) return shell(header("App") + "Loading…");
      const host = location.hostname;
      shell(`${header(a.name)}<div class="center" style="padding:14px"><div class="muted" style="font-weight:600">${esc(a.category)}</div><div class="muted">${a.installed ? "Installed · " + esc(a.state || "") : "Not installed"}</div></div>
        <div style="display:flex;gap:12px;padding:0 22px">${a.installed ? `<a class="btn" style="flex:1;text-decoration:none" target="_blank" rel="noopener" href="http://${esc(host)}:${a.port}${esc(a.path || "/")}">Open</a>${isAdmin() ? `<button class="btn danger" style="flex:1" data-un>Uninstall</button>` : ""}`
          : isAdmin() ? `<button class="btn" style="flex:1" data-in ${a.port_free === false ? "disabled" : ""}>Install</button>` : `<div class="muted">An admin can install this.</div>`}</div>
        <div class="sec">About</div><div class="group"><div class="row"><div class="t"><b>${esc(a.description)}</b>${a.notes ? `<small>${esc(a.notes)}</small>` : ""}</div></div></div>
        <div class="sec">Details</div><div class="group"><div class="row"><div class="t"><b>Image</b><small>${esc(a.image)}</small></div></div><div class="row"><div class="t"><b>Port</b><small>${a.port}</small></div></div></div>`);
      wireBack();
      const job = async verb => act(async () => { let j = (await post(`/api/v1/store/${id}/${verb}`)).job; toast(verb === "install" ? "Installing…" : "Uninstalling…");
        while (j.state === "running") { await sleep(2000); j = await get(`/api/v1/jobs/${j.id}`); } toast(j.state === "done" ? "Done" : "Failed: " + (j.result?.error || "")); await get("/api/v1/store"); render(); });
      $("[data-in]")?.addEventListener("click", () => job("install"));
      $("[data-un]")?.addEventListener("click", async () => { if (await confirmSheet(`Uninstall ${a.name}?`, "Its data is kept on the server in /opt/.nova-uninstalled.", "Uninstall")) job("uninstall"); });
      return;
    }
    const cats = {}; apps.forEach(a => (cats[a.installed ? "Installed" : a.category] ||= []).push(a));
    shell(`${header("App store", false)}<p class="muted" style="margin:0 26px 8px">Hand-picked for your server. Installs run on Nova and show up in Containers.</p>
      ${Object.entries(cats).sort(([a], [b]) => (a === "Installed" ? -1 : b === "Installed" ? 1 : a.localeCompare(b))).map(([c, l]) => `<div class="sec">${esc(c)}</div><div class="group">${l.map(a => `<div class="row click" data-a="${esc(a.id)}"><div class="t"><b>${esc(a.name)}</b><small>${esc(a.description)}</small></div><span class="pillbtn">${a.installed ? "Open" : "Get"}</span></div>`).join("")}</div>`).join("")}`);
    document.querySelectorAll("[data-a]").forEach(r => r.onclick = () => go("store/" + r.dataset.a));
  };
  draw(); await get("/api/v1/store"); if (seq === renderSeq) draw();
}

async function inbox(_a, _b, seq) {
  const draw = () => {
    const ev = S.cache["/api/v1/events?since=0"]?.events || [];
    const days = {}; ev.forEach(e => (days[new Date(e.t * 1000).toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" })] ||= []).push(e));
    shell(`${header("Inbox")}${Object.entries(days).map(([d, l]) => `<div class="sec">${d}</div><div class="group">${l.map(e => `<div class="row"><span class="dot" style="background:${levelColor(e.level)}"></span><div class="t"><b>${esc(e.title.replace(/^[^\p{L}\p{N}]+\s*/u, ""))}</b><small>${esc(e.detail)}</small></div><span class="muted" style="font-size:13px">${new Date(e.t * 1000).toTimeString().slice(0, 5)}</span></div>`).join("")}</div>`).join("") || `<p class="muted" style="margin:30px">Nothing here — all quiet.</p>`}`);
    wireBack();
  };
  draw(); await get("/api/v1/events?since=0"); if (seq === renderSeq) draw();
}

async function lighting(_a, _b, seq) {
  const SW = ["#ffffff", "#ff3b30", "#ff9500", "#ffcc00", "#34c759", "#00c7be", "#005aff", "#3e91ff", "#5e5ce6", "#bf5af2", "#ff2d55", "#ff6b9a"];
  const EFF = [["static", "Static"], ["pulse", "Pulse"], ["blink", "Blink"], ["cycle", "Colour cycle"], ["wave", "Wave"], ["random", "Random"], ["gradient", "Gradient"]];
  const draw = () => {
    const f = S.fan || {}, ro = !isAdmin();
    shell(`${header("Lighting")}${ro ? `<p class="muted" style="margin:0 30px">View-only access.</p>` : ""}
      ${fanTile()}
      <div class="sec">Colour</div><div class="group"><div class="swatches">${SW.map(c => `<button class="swatch ${c === f.color ? "on" : ""}" style="background:${c}" data-col="${c}" aria-label="${c}"></button>`).join("")}</div></div>
      <div class="sec">Effect</div><div class="group">${EFF.map(([k, l]) => `<div class="row click" data-eff="${k}"><div class="t"><b>${l}</b></div><span class="dot" style="width:18px;height:18px;border:2px solid ${k === f.effect ? "var(--blue)" : "var(--sub)"};background:${k === f.effect ? "var(--blue)" : "transparent"}"></span></div>`).join("")}</div>
      <div class="sec">Speed</div><div class="group"><div class="row" style="display:block"><input type="range" min="1" max="100" value="${f.speed || 50}" id="spd" ${ro ? "disabled" : ""}><div class="muted" style="display:flex;justify-content:space-between;font-size:13px"><span>Slower</span><span>Faster</span></div></div></div>`);
    wireBack(); wireFan();
    const set = patch => { if (ro) return toast("View-only access"); S.fan = { ...S.fan, ...patch }; draw(); act(async () => { S.fan = await post("/api/v1/fan", patch); }); };
    document.querySelectorAll("[data-col]").forEach(b => b.onclick = () => set({ color: b.dataset.col }));
    document.querySelectorAll("[data-eff]").forEach(b => b.onclick = () => set({ effect: b.dataset.eff }));
    $("#spd").onchange = e => set({ speed: +e.target.value });
  };
  draw(); S.fan = await get("/api/v1/fan"); if (seq === renderSeq) draw();
}

async function devices(_a, _b, seq) {
  const draw = () => {
    const l = S.cache["/api/v1/devices"]?.devices || [];
    const by = {}; l.forEach(d => (by[d.user || "No name yet"] ||= []).push(d));
    shell(`${header("Users & devices")}${Object.entries(by).map(([u, ds]) => `<div class="sec">${esc(u)}</div><div class="group">${ds.map(d => `<div class="row"><div class="t"><b>${esc(d.name)}${d.current ? " · this browser" : ""}</b><small>${d.role === "viewer" ? "View only" : "Admin"} · ${d.type === "browser" ? "browser" : "phone"} · last seen ${esc(d.last_seen || "never")}</small></div></div>`).join("")}</div>`).join("")}
      <p class="muted" style="margin:12px 30px;font-size:13px">Add or remove devices and change roles in the Nova app on an admin phone.</p>`);
    wireBack();
  };
  draw(); await get("/api/v1/devices"); if (seq === renderSeq) draw();
}

async function settings() {
  const me = S.me || {};
  shell(`${header("Settings", false)}
    <div class="sec">This browser</div><div class="group">
      <div class="row"><div class="t"><b>${esc(me.device || "")}</b><small>${me.role === "viewer" ? "View only" : "Admin — risky actions are approved on your phone"}${me.user ? " · " + esc(me.user) : ""}</small></div></div>
      <div class="row"><div class="t"><b>Signing key</b><small>Created in this browser, can't be exported. Every request is signed with it.</small></div></div>
      <div class="row"><div class="t"><b>Connection</b><small>${esc(location.host)} · ${location.protocol === "https:" ? "encrypted" : "not encrypted"}</small></div></div></div>
    <div class="sec">Theme</div><div class="seg" id="theme">${["system", "light", "dark"].map(t => `<button data-t="${t}" class="${(localStorage.getItem("nova-theme") || "system") === t ? "on" : ""}">${t[0].toUpperCase() + t.slice(1)}</button>`).join("")}</div>
    <div class="group"><div class="row click" data-go="devices"><div class="t"><b>Users & devices</b><small class="blue">Who can reach this server</small></div></div>
      <div class="row click" data-go="lighting"><div class="t"><b>Lighting</b><small class="blue">Fan colour and effects</small></div></div></div>
    <div class="group"><div class="row click" id="forget"><div class="t"><b style="color:var(--red)">Remove this browser</b><small>Erases its key here and its access on the server</small></div></div></div>
    <p class="muted center" style="font-size:13px">Nova web · ${esc(serverName())}</p>`);
  document.querySelectorAll("#theme [data-t]").forEach(b => b.onclick = () => { localStorage.setItem("nova-theme", b.dataset.t); applyTheme(); settings(); });
  $("#forget").onclick = async () => {
    if (!(await confirmSheet("Remove this browser?", "It loses access and its key is erased. You can add it again from an admin phone.", "Remove"))) return;
    try { await api("DELETE", `/api/v1/devices/${S.device}`); } catch {}       // admins: phone approval; otherwise just forget locally
    await kv("device", null); location.reload();
  };
}

// Dashboard: full-screen, always-on (screen wake lock), dims at night, drifts to avoid burn-in.
async function dashboard() {
  let wake; try { wake = await navigator.wakeLock?.request("screen"); } catch {}
  const me = location.hash; let shift = 0;
  const draw = () => {
    const s = S.cache["/api/v1/stats"], n = s?.now, h = s?.history || [], st = S.overview?.status || {}, m = st.metrics || {}, cs = S.overview?.containers;
    const ev = (S.cache["/api/v1/events?since=0"]?.events || []).slice(0, 5); const now = new Date(), hr = now.getHours();
    $("#app").innerHTML = `<div class="dash" style="transform:translate(${(shift * 7) % 9 - 4}px,${(shift * 5) % 7 - 3}px)">
      <div class="top" id="dtop"><button class="circle" data-x aria-label="Leave">${I("back")}</button><b style="font-size:18px;flex:1">${esc(serverName())}</b><button class="circle" data-fs aria-label="Full screen">${I("full")}</button></div>
      <div class="grid">
        <div class="card"><div class="clock">${now.toTimeString().slice(0, 5)}</div><div class="muted">${now.toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" })}</div></div>
        <div class="card"><div class="lbl" style="color:${levelColor(st.level || "ok")}">${I(st.level === "ok" || !st.level ? "check" : "err")}<span class="muted">Health</span></div><div class="val">${st.level === "ok" || !st.level ? "All good" : esc(st.headline)}</div><div class="sub" style="margin-top:auto">${cs ? `${cs.running}/${cs.total} containers running` : ""}</div></div>
        ${gcard("CPU", n ? Math.round(n.cpu) + "%" : "—", n ? "load " + n.load : "", "d1")}
        ${gcard("Memory", n ? Math.round(n.mem) + "%" : "—", n ? `${n.mem_used_gb} / ${n.mem_total_gb} GB` : "", "d2")}
        ${gcard("CPU temperature", n?.temp ? Math.round(n.temp) + "°C" : "—", n?.nvme_temp ? `NVMe ${Math.round(n.nvme_temp)}°C` : "", "d3")}
        ${gcard("Network", n ? "↓ " + rate(n.rx) : "—", n ? "↑ " + rate(n.tx) : "", "d4")}
        <div class="card wide"><div class="lbl">Storage</div>${[["Photos", "photo_pool_used"], ["System", "root_used"], ["Cold", "cold_storage_used"], ["Backup", "backup_drive_used"]].filter(x => m[x[1]]).map(([l, k]) => `<div style="display:flex;align-items:center;gap:10px;margin-top:10px"><span style="width:64px">${l}</span><div class="bar" style="flex:1;margin:0"><i style="width:${pct(m[k])}%"></i></div><span class="muted" style="font-size:12px">${esc((/\(([^)]*)\)/.exec(m[k]) || [])[1] || "")}</span></div>`).join("")}</div>
        <div class="card wide"><div class="lbl">Recent alerts</div>${ev.map(e => `<div style="display:flex;align-items:center;gap:8px;margin-top:8px"><span class="dot" style="width:8px;height:8px;background:${levelColor(e.level)}"></span><span style="flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(e.title.replace(/^[^\p{L}\p{N}]+\s*/u, ""))}</span><span class="muted" style="font-size:12px">${new Date(e.t * 1000).toTimeString().slice(0, 5)}</span></div>`).join("") || `<div class="muted" style="margin-top:8px">Nothing lately</div>`}</div>
        <div class="card"><div class="lbl">Backups</div><div class="val" style="font-size:22px">${(m.data_backup || "").includes("running") ? "Running" : esc(m.data_backup || "—")}</div><div class="sub">${esc(m.backup_sets || "")}</div></div>
      </div>${hr >= 23 || hr < 7 ? `<div class="night"></div>` : ""}</div>`;
    $("[data-x]").onclick = () => history.back();
    $("[data-fs]").onclick = () => document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen?.();
    requestAnimationFrame(() => { spark($("#d1"), h.map(x => x.cpu), css("--blue"), 100); spark($("#d2"), h.map(x => x.mem), "#bf5af2", 100); spark($("#d3"), h.map(x => x.temp || 0), css("--amber")); spark($("#d4"), h.map(x => (x.rx || 0) + (x.tx || 0)), css("--green")); });
  };
  let tick = 0;
  while (location.hash === me) {
    if (tick % 15 === 0) { await Promise.all([refreshOverview(), get("/api/v1/stats"), get("/api/v1/events?since=0")].map(p => p.catch(() => {}))); }
    if (tick % 60 === 0) shift++;
    if (location.hash !== me) break; draw(); tick++; await sleep(1000);
  }
  wake?.release?.();
}

// ── pairing a new browser ────────────────────────────────────────────────────────
async function pairScreen() {
  $("#app").innerHTML = `<div class="pair"><h1>Nova</h1><p class="muted" style="font-size:17px">Add this browser to your server</p>
    <div class="group" style="margin:24px 0"><div class="row" style="display:block"><b style="font-weight:400;font-size:17px">Name this browser</b>
      <input class="field" id="nm" style="margin-top:10px" value="${esc(guessName())}"></div></div>
    <button class="btn" id="go" style="width:100%">Get a code</button>
    <p class="muted" style="font-size:13px;margin-top:18px">This browser makes its own signing key (it can't be copied out). An admin approves it in the Nova app on their phone — browsers can't add themselves.</p></div>`;
  $("#go").onclick = async () => {
    const keys = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, false, ["sign", "verify"]);
    const name = $("#nm").value.trim() || guessName();
    const r = await fetch("/api/v1/browser/request", { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ public_key: await pemOf(keys.publicKey), name }) });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) return toast(j.error || "Couldn't start pairing");
    $("#app").innerHTML = `<div class="pair"><h1>Approve on your phone</h1>
      <p class="muted">On an admin phone open <b>Nova → Menu → Users &amp; devices → Approve a browser</b> (or Settings → Users &amp; devices) and enter:</p>
      <div class="code">${esc(j.code)}</div>
      <div class="center"><div class="spinner" style="margin:auto"></div><p class="muted" style="font-size:13px">Waiting… the code expires in 10 minutes.</p></div></div>`;
    for (let i = 0; i < 300; i++) {
      await sleep(2000);
      const s = await fetch(`/api/v1/browser/request/${j.id}`, { credentials: "same-origin" }).then(x => x.json()).catch(() => ({}));
      if (s.state === "approved") { await kv("device", { id: s.device_id, keys }); toast("This browser is paired"); return start(); }
      if (s.state === "expired") break;
    }
    toast("The code expired — try again"); pairScreen();
  };
}
function guessName() {
  const ua = navigator.userAgent, b = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
  const os = /Windows/.test(ua) ? "Windows" : /Mac OS/.test(ua) ? "Mac" : /Android/.test(ua) ? "Android" : /Linux/.test(ua) ? "Linux" : /iPhone|iPad/.test(ua) ? "iOS" : "";
  return `${b}${os ? " on " + os : ""}`;
}

async function start() {
  if (!crypto?.subtle) { $("#app").innerHTML = `<div class="pair"><h1>Nova</h1><p>This page needs a secure connection (https). Open it at <b>https://</b>${esc(location.host)}.</p></div>`; return; }
  const d = await kv("device").catch(() => null);
  if (!d?.id) return pairScreen();
  S.device = d.id; S.keys = d.keys;
  try { S.me = await get("/api/v1/whoami"); }
  catch (e) { if (e.code === 401) { await kv("device", null); return pairScreen(); } }
  render();
  setInterval(() => { if (document.visibilityState === "visible" && ["home", "status"].includes(route()[0])) render(); }, 15000);
}
start();
