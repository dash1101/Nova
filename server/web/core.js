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
};
export class ApiError extends Error {
  constructor(code, msg, offline = false) { super(msg); this.code = code; this.offline = offline; }
}

function cloudflareSays(code) {
  if ([530, 523, 521, 1033].includes(code)) return `Cloudflare is up, but your server isn't connected to it (off, restarting or no internet) — ${code}`;
  if ([522, 524, 504].includes(code)) return `Cloudflare reached the server but it didn't answer in time — ${code}`;
  return `Cloudflare couldn't get an answer from the server — ${code}`;
}

let approvalHook = null;          // set by the UI: shows "approve on your phone"
export function onApproval(fn) { approvalHook = fn; }

export async function api(method, path, body) {
  const bodyStr = body === undefined ? "" : JSON.stringify(body);
  const ts = String(Date.now()), nonce = b64url(crypto.getRandomValues(new Uint8Array(18)));
  const digest = hex(await crypto.subtle.digest("SHA-256", enc(bodyStr)));
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, S.keys.privateKey, enc(`${method}\n${path}\n${ts}\n${nonce}\n${digest}`));
  let r;
  try {
    r = await fetch(path, { method, body: method === "GET" ? undefined : bodyStr, credentials: "same-origin", cache: "no-store",
      headers: { "Content-Type": "application/json", "X-Nova-Device": S.device, "X-Nova-Time": ts, "X-Nova-Nonce": nonce, "X-Nova-Signature": b64(sig) } });
  } catch (e) {
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
  if (r.status === 202 && j.approval) return approvalHook ? approvalHook(j.approval) : j;     // risky: approve on the phone
  if (r.status === 401) throw new ApiError(401, "This browser isn't authorized anymore");
  if (!r.ok) throw new ApiError(r.status, j.message || j.error || `HTTP ${r.status}`);
  S.lastContact = Date.now();
  if (method === "GET" && !path.startsWith("/api/v1/stats?")) S.cache[path] = j;      // not the per-second deltas
  return j;
}
export const get = p => api("GET", p);
export const post = (p, b = {}) => api("POST", p, b);
export const del = p => api("DELETE", p);

/** Background jobs (installs/updates): poll until done. */
export async function waitJob(job, onUpdate) {
  let j = job;
  while (j.state === "running") { await sleep(2000); j = await get(`/api/v1/jobs/${j.id}`); onUpdate?.(j); }
  return j;
}

// ── preferences (this browser) — same choices as the app's Appearance screens ───
const PREF_DEFAULTS = {
  theme: "system", reduceMotion: false,
  homeOrder: ["hero", "shortcuts", "stats"], homeHero: true, homeShortcuts: true, homeStats: true,
  homeChips: ["inbox", "quick", "containers", "storage"],
  navTabs: ["store", "home", "menu"],
  quick: ["backup", "freeram", "fan", "dim", "status", "dashboard"],
  dashTiles: ["clock", "health", "cpu", "mem", "temp", "net", "storage", "containers", "backup", "alerts"],
  dashDim: true, dashFrom: 23, dashTo: 7,
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
export const isAdmin = () => S.me?.role !== "viewer";
export const has = f => S.overview?.features?.[f] !== false;
export const cleanTitle = t => String(t || "").replace(/^[^\p{L}\p{N}]+\s*/u, "");
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
    const acc = o.server?.accent;
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
const AKEY = () => "history:" + (S.device || "");
export async function archiveMerge(events, markArchived = false) {
  if (!events?.length) return;
  const h = (await kv(AKEY()).catch(() => null)) || { events: [], archived: [] };
  const have = new Set(h.events.map(e => e.t.toFixed(4)));
  h.events = [...events.filter(e => !have.has(e.t.toFixed(4))), ...h.events].sort((a, b) => b.t - a.t).slice(0, 10000);
  if (markArchived) h.archived = [...new Set([...h.archived, ...events.map(e => e.t.toFixed(4))])].slice(-10000);
  await kv(AKEY(), h).catch(() => {});
}
export async function archiveAll() { return (await kv(AKEY()).catch(() => null)) || { events: [], archived: [] }; }
export async function archiveClear() { await kv(AKEY(), null).catch(() => {}); }
