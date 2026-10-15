// Nova web — core: this browser's key, signed API calls, preferences, cached data.
//
// * This browser has its own ECDSA P-256 key, made by Web Crypto as NON-EXTRACTABLE and kept in
//   IndexedDB: page scripts can use it to sign, but nobody (not even this code) can read it out.
// * Every API request is signed exactly like the app's:  METHOD\nPATH\nTIME_MS\nNONCE\nsha256(BODY).
// * A browser is added only when an admin phone approves its code (with fingerprint).
// * Risky actions from a browser are forwarded to your phone; you approve them there.

export const $ = (s, r = document) => r.querySelector(s);
export const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const enc = s => new TextEncoder().encode(s);
const hex = b => [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, "0")).join("");
export const b64 = b => btoa(String.fromCharCode(...new Uint8Array(b)));
const b64url = b => b64(b).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
export const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
export const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── this browser's key (IndexedDB) ──────────────────────────────────────────────
let _db = null;
function idb() {
  if (_db) return Promise.resolve(_db);
  return new Promise((ok, no) => {
    const r = indexedDB.open("nova", 1);
    r.onupgradeneeded = () => r.result.createObjectStore("kv");
    r.onsuccess = () => ok(_db = r.result); r.onerror = () => no(r.error);
  });
}
export async function kv(k, v) {
  const db = await idb();
  return new Promise((ok, no) => {
    const tx = db.transaction("kv", v === undefined ? "readonly" : "readwrite"); const st = tx.objectStore("kv");
    const r = v === undefined ? st.get(k) : v === null ? st.delete(k) : st.put(v, k);
    r.onsuccess = () => ok(r.result); r.onerror = () => no(r.error);
  });
}
export async function pemOf(pub) {
  const der = await crypto.subtle.exportKey("spki", pub);
  return "-----BEGIN PUBLIC KEY-----\n" + b64(der).match(/.{1,64}/g).join("\n") + "\n-----END PUBLIC KEY-----\n";
}

// ── state shared by every screen ────────────────────────────────────────────────
export const S = {
  device: null, keys: null, me: null,
  overview: null, fan: null, cache: {},
  error: null, reconnecting: false, lastContact: 0, lastAttempt: "", failures: 0,
  lastNow: null,                 // last live numbers, so a redrawn page never shows "—"
  skew: 0,                       // server clock − ours (ms), so the fan picture lines up with the real fan
  unread: 0,
  nodes: null,                   // the other Nova servers added here (always from this server)
  homeName: "",                  // this server's name (kept while you control another)
  target: null,                  // another Nova server you're controlling from here: {id, name, device, homeName}
};
export class ApiError extends Error {
  constructor(code, msg, offline = false, kind = "") { super(msg); this.code = code; this.offline = offline; this.kind = kind; }
}

function cloudflareSays(code) {
  if ([530, 523, 521, 1033].includes(code)) return `Cloudflare is up, but your server isn't connected to it (off, restarting or no internet) — ${code}`;
  if ([522, 524, 504].includes(code)) return `Cloudflare reached the server but it didn't answer in time — ${code}`;
  return `Cloudflare couldn't get an answer from the server — ${code}`;
}

let approvalHook = null;          // set by the UI: shows "approve on your phone"
export function onApproval(fn) { approvalHook = fn; }

async function signHeaders(method, path, bodyStr, device) {
  const ts = String(Date.now()), nonce = b64url(crypto.getRandomValues(new Uint8Array(18)));
  const digest = hex(await crypto.subtle.digest("SHA-256", enc(bodyStr)));
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, S.keys.privateKey, enc(`${method}\n${path}\n${ts}\n${nonce}\n${digest}`));
  return { "X-Nova-Device": device, "X-Nova-Time": ts, "X-Nova-Nonce": nonce, "X-Nova-Signature": b64(sig), "X-Nova-Client": "web 1" };
}

/** A signed request; returns the raw Response (for streaming — e.g. the speed test). */
export async function signedFetch(method, path, body, here = false) {
  const bodyStr = body === undefined ? "" : JSON.stringify(body);
  if (S.target && !here) return relayFetch(S.target, method, path, bodyStr);
  return fetch(path, { method, body: method === "GET" ? undefined : bodyStr, credentials: "same-origin", cache: "no-store",
    headers: { "Content-Type": "application/json", ...(await signHeaders(method, path, bodyStr, S.device)) } });
}

/** Controlling another Nova server from here: the request is signed by this browser for that server (its own
 *  device id there), and this server only carries it over (nodes.py). Its answer comes back as a normal Response. */
export async function relayFetch(t, method, path, bodyStr = "") {
  const unsigned = /^\/api\/v1\/browser\/request(\/|$)/.test(path);
  const inner = unsigned ? {} : await signHeaders(method, path, bodyStr, t.device);
  const outerPath = `/api/v1/nodes/${t.id}/relay`, outer = JSON.stringify({ method, path, headers: inner, body: method === "GET" ? "" : bodyStr });
  const r = await fetch(outerPath, { method: "POST", body: outer, credentials: "same-origin", cache: "no-store",
    headers: { "Content-Type": "application/json", ...(await signHeaders("POST", outerPath, outer, S.device)) } });
  if (r.status !== 200) return r;                              // about this server (signed out, can't reach that one…)
  let j; try { j = await r.json(); } catch { return new Response("{}", { status: 502 }); }
  let st = +j.status || 502, b = j.body ?? {};
  if (st === 401 && !unsigned) { st = 403; b = { error: "not_paired_there", message: `${t.name} doesn't know this browser anymore — pick it under Servers to pair again` }; S.targetLost = true; }
  if (st < 200 || st > 599 || st === 204) st = st === 204 ? 200 : 502;
  return new Response(JSON.stringify(b), { status: st, headers: { "Content-Type": "application/json" } });
}

/** Signed request with a raw body (file uploads/saves) — the signature covers the bytes' SHA-256. */
export async function signedRaw(method, path, bytes) {
  if (S.target) throw new ApiError(400, `Not while you're controlling ${S.target.name} — open its own Nova web for files`);
  const buf = bytes instanceof Blob ? new Uint8Array(await bytes.arrayBuffer()) : bytes;
  const ts = String(Date.now()), nonce = b64url(crypto.getRandomValues(new Uint8Array(18)));
  const digest = hex(await crypto.subtle.digest("SHA-256", buf));
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, S.keys.privateKey, enc(`${method}\n${path}\n${ts}\n${nonce}\n${digest}`));
  const r = await fetch(path, { method, body: buf, credentials: "same-origin", cache: "no-store",
    headers: { "Content-Type": "application/octet-stream", "X-Nova-Device": S.device, "X-Nova-Time": ts, "X-Nova-Nonce": nonce, "X-Nova-Signature": b64(sig) } });
  let j = {}; try { j = await r.json(); } catch {}
  if (!r.ok) throw new ApiError(r.status, j.message || j.error || `HTTP ${r.status}`, false, j.error || "");
  return j;
}
/** Developer options → requests: the last 60 calls to the server, newest first. */
export const devLog = [];
function logReq(method, path, status, t0) { devLog.unshift({ t: Date.now(), method, path: String(path).split("?")[0].slice(0, 80), status, ms: Math.round(performance.now() - t0) }); devLog.length = Math.min(devLog.length, 60); }
export async function api(method, path, body, here = false) {
  let r; const t0 = performance.now();
  try {
    r = await signedFetch(method, path, body, here);
    logReq(method, path, r.status, t0);
  } catch (e) {
    logReq(method, path, 0, t0);
    S.lastAttempt = navigator.onLine === false ? "This device is offline" : `Nothing answered at ${location.host} (the server is off, or this network can't reach it)`;
    throw new ApiError(0, "Disconnected", true);
  }
  const text = await r.text();
  let j = {}; try { j = JSON.parse(text); } catch {}
  // A Cloudflare error page (not our JSON): the server itself is off or unreachable
  if (!r.ok && !("error" in j) && ((r.status >= 520 && r.status <= 530) || (r.status >= 502 && r.status <= 504))) {
    S.lastAttempt = cloudflareSays(r.status); throw new ApiError(r.status, "Disconnected", true);
  }
  if (r.status === 403 && !("error" in j) && /cloudflare/i.test(text))
    throw new ApiError(403, "Cloudflare Access turned this browser away — log in at the Cloudflare page first");
  if (r.status === 202 && j.approval) return approvalHook ? approvalHook(j.approval, j.code) : j;     // risky: approve on the phone
  if (r.status === 401) throw new ApiError(401, "This browser isn't authorized anymore");
  if (!r.ok) throw new ApiError(r.status, j.message || j.error || `HTTP ${r.status}`, false, j.error || "");
  S.lastContact = Date.now();
  if (method === "GET" && !here && !path.startsWith("/api/v1/stats?")) S.cache[path] = j;      // not the per-second deltas
  return j;
}
export const get = p => api("GET", p);
export const post = (p, b = {}) => api("POST", p, b);
export const del = p => api("DELETE", p);
/** This server itself, even while you control another one (the Servers list lives here). */
export const hereApi = (m, p, b) => api(m, p, b, true);
export async function loadNodes() { const r = await hereApi("GET", "/api/v1/nodes"); S.nodes = r.nodes || []; return S.nodes; }

/** Background jobs (installs/updates): poll until done. */
/** Background tasks (installs, backups, updates, drive setup…): follow one until it ends. */
export async function waitTask(id, onUpdate) {
  let t = await get(`/api/v1/tasks/${id}`); onUpdate?.(t);
  while (t.state === "running") { await sleep(1200); t = await get(`/api/v1/tasks/${id}`); onUpdate?.(t); }
  return t;
}
/** The running task for something (e.g. kind "store-install", key = the app's id), if any. */
export async function runningTask(kinds, key) {
  const l = (await get("/api/v1/tasks").catch(() => ({}))).tasks || [];
  return l.find(t => t.state === "running" && kinds.includes(t.kind) && (key == null || t.key === key)) || null;
}
export async function waitJob(job, onUpdate) {
  let j = job;
  while (j.state === "running") { await sleep(2000); j = await get(`/api/v1/jobs/${j.id}`); onUpdate?.(j); }
  return j;
}

// ── preferences (this browser) — same choices as the app's Appearance screens ───
const PREF_DEFAULTS = {
  theme: "system", style: "auto", reduceMotion: true,       // browsers start calm; turn motion on in Appearance
  homeOrder: ["hero", "servers", "shortcuts", "stats"], homeHero: true, homeShortcuts: true, homeStats: true, homeServers: true,
  homeChips: ["inbox", "quick", "containers", "storage"],
  navTabs: ["store", "home", "menu"],
  quick: ["backup", "freeram", "fan", "dim", "status", "dashboard"],
  dashTiles: ["clock", "health", "cpu", "mem", "temp", "net", "storage", "containers", "backup", "alerts"],
  dashDim: true, dashFrom: 23, dashTo: 7, dashDimLevel: 40, dashFull: false,
  dashCols: 0, dashSize: {}, dashClock24: null, dashSeconds: false, dashScale: 100, dashBg: "theme", dashPlain: false,
  lastEventSeen: 0,
};
export const prefs = new Proxy({}, {
  get(_, k) {
    try { const v = localStorage.getItem("nova." + k); return v == null ? structuredClone(PREF_DEFAULTS[k]) : JSON.parse(v); }
    catch { return structuredClone(PREF_DEFAULTS[k]); }
  },
  set(_, k, v) { try { localStorage.setItem("nova." + k, JSON.stringify(v)); } catch {} return true; },
});

// ── small formatting helpers ───────────────────────────────────────────────────
export const levelColor = l => l === "critical" ? "var(--red)" : l === "warning" ? "var(--amber)" : l === "ok" || l === "resolved" ? "var(--green)" : "var(--blue)";
export const pct = s => { const m = /(\d+)%/.exec(s || ""); return m ? +m[1] : null; };
export const rate = b => b >= 1e6 ? (b / 1e6).toFixed(1) + " MB/s" : b >= 1e3 ? Math.round(b / 1e3) + " kB/s" : Math.round(b || 0) + " B/s";
export const bytes = b => { if (!b) return "0 B"; const u = ["B", "KB", "MB", "GB", "TB"]; let i = 0; while (b >= 1000 && i < u.length - 1) { b /= 1000; i++; } return (b >= 100 || i === 0 ? Math.round(b) : b.toFixed(1)) + " " + u[i]; };
/** What kind of device this browser is on — only for the device list's icon. */
export function webForm() {
  const ua = navigator.userAgent, uad = navigator.userAgentData;
  if (uad?.mobile) return "phone";
  if (/iPad|Tablet/i.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) return "tablet";   // iPadOS says "Macintosh"
  if (/Android/i.test(ua)) return /Mobile/i.test(ua) ? "phone" : "tablet";
  if (/iPhone|iPod|Mobile/i.test(ua)) return "phone";
  return "desktop";
}
export const isAdmin = () => S.me?.role !== "viewer";
// Through another server, the things that stream or move files aren't carried (yet): its own Nova web does them
const NOT_RELAYED = ["files", "terminal", "diag"];
export const has = f => !(S.target && NOT_RELAYED.includes(f)) && S.overview?.features?.[f] !== false;
export const relayed = () => !!S.target;
/** The host name of the server you're looking at (apps on it open at its address, not this one's). */
export const serverHost = () => { if (S.target) { try { return new URL(S.target.lan_url).hostname.replace(/^\[|\]$/g, ""); } catch {} } return location.hostname; };
export const cleanTitle = t => String(t || "").replace(/^[^\p{L}\p{N}\/~]+\s*/u, "");     // the status emoji goes; a path like "/ is 90% full" stays
export const cap = s => s ? s[0].toUpperCase() + s.slice(1) : "";
export const serverName = () => { const s = S.overview?.server; return s?.display_name || (s?.name || "Nova").replace(/(^|-)\w/g, m => m.toUpperCase()); };
export const uptime = s => `${Math.floor(s / 86400)}d ${Math.floor(s % 86400 / 3600)}h ${Math.floor(s % 3600 / 60)}m`;
export const hm = t => new Date(t * 1000).toTimeString().slice(0, 5);

/** Server's live numbers: overview + lighting, with "Disconnected" bookkeeping (like the app's refresh). */
export async function refresh() {
  try {
    const t0 = Date.now(); const o = await get("/api/v1/overview"); const t1 = Date.now(); S.overview = o;
    if (typeof o.time === "number" && t1 - t0 < 3000) S.skew = o.time * 1000 - (t0 + t1) / 2;
    if (o.fan && !fanInFlight) S.fan = { ...(S.fan || {}), ...o.fan };
    const acc = /^#[0-9a-f]{6}$/i.test(prefs.accentLocal || "") ? prefs.accentLocal : o.server?.accent;      // this device's own accent wins
    if (/^#[0-9a-f]{6}$/i.test(acc || "")) document.documentElement.style.setProperty("--blue", acc);
    else document.documentElement.style.removeProperty("--blue");
    S.error = null; S.reconnecting = false; S.failures = 0;
    try {
      const ev = (await get("/api/v1/events?since=0")).events || [];
      archiveMerge(ev);                 // this browser's permanent history
      if (!prefs.lastEventSeen && ev[0]) prefs.lastEventSeen = ev[0].t;
      S.unread = ev.filter(e => e.t > prefs.lastEventSeen && ["warning", "critical"].includes(e.level)).length;
    } catch {}
  } catch (e) {
    S.failures++;
    if (e.code === 401) throw e;
    S.reconnecting = true;
    if (S.failures >= 2) S.error = e.offline ? "Disconnected" : e.message;
  }
}

/** Switch which server this web is controlling (null = this one). Everything shown is dropped and fetched again. */
const saved = {};
export async function switchServer(t) {
  saved[S.target?.id || ""] = S.cache;
  if (!S.target) S.homeName = serverName();
  S.target = t || null; S.targetLost = false;
  S.cache = saved[S.target?.id || ""] || {};
  Object.assign(S, { overview: null, fan: null, lastNow: null, unread: 0, serverUpdate: null, updateAsked: false, error: null, reconnecting: false, failures: 0 });
  document.documentElement.style.removeProperty("--blue");
  try { sessionStorage.setItem("nova.target", t ? JSON.stringify(t) : ""); } catch {}
  S.me = await get("/api/v1/whoami");
  await refresh();
}
export function savedTarget() { try { return JSON.parse(sessionStorage.getItem("nova.target") || "null"); } catch { return null; } }

// Optimistic lighting changes: the UI moves first, the server catches up.
let fanInFlight = 0;
export async function changeFan(patch) {
  if (!isAdmin()) throw new ApiError(403, "This browser has view-only access");
  const before = S.fan; S.fan = { ...(S.fan || {}), ...patch }; fanInFlight++;
  try { const r = await post("/api/v1/fan", patch); S.fan = r; return r; }
  catch (e) { S.fan = before; throw e; }
  finally { fanInFlight--; }
}

// ── this browser's permanent history of server events (IndexedDB; survives the Inbox being cleared) ──
const AKEY = () => "history:" + (S.target ? S.target.id + ":" + S.target.device : S.device || "");
export async function archiveMerge(events, markArchived = false) {
  if (!events?.length) return;
  const h = (await kv(AKEY()).catch(() => null)) || { events: [], archived: [] };
  const have = new Set(h.events.map(e => e.t.toFixed(6)));
  h.events = [...events.filter(e => !have.has(e.t.toFixed(6))), ...h.events].sort((a, b) => b.t - a.t).slice(0, 10000);
  if (markArchived) h.archived = [...new Set([...h.archived, ...events.map(e => e.t.toFixed(6))])].slice(-10000);
  await kv(AKEY(), h).catch(() => {});
}
export async function archiveUnmark(ts) {
  const h = (await kv(AKEY()).catch(() => null)); if (!h) return;
  const drop = new Set(ts.map(t => (+t).toFixed(6))); h.archived = h.archived.filter(k => !drop.has(k)); await kv(AKEY(), h).catch(() => {});
}
export async function archiveAll() { return (await kv(AKEY()).catch(() => null)) || { events: [], archived: [] }; }
export async function archiveClear() { await kv(AKEY(), null).catch(() => {}); }

/** What this browser can draw: is the graphics card in use (or is it software rendering), and are blur/glass supported. */
let _gpu;
export function gpuInfo() {
  if (_gpu) return _gpu;
  let renderer = "", software = false, webgl = false;
  try {
    const c = document.createElement("canvas");
    const gl = c.getContext("webgl", { failIfMajorPerformanceCaveat: true });
    if (gl) { webgl = true; const ext = gl.getExtension("WEBGL_debug_renderer_info"); renderer = ext ? String(gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) || "") : ""; gl.getExtension("WEBGL_lose_context")?.loseContext(); }
    else software = !!document.createElement("canvas").getContext("webgl");   // only works without the "must be fast" flag: software drawing
  } catch {}
  if (/swiftshader|llvmpipe|softpipe|software|basic render|mesa offscreen/i.test(renderer)) software = true;
  const blur = CSS.supports("backdrop-filter", "blur(2px)") || CSS.supports("-webkit-backdrop-filter", "blur(2px)");
  return (_gpu = { software, webgl, renderer, blur });
}
