// Nova web — apps in Nova windows (Labs, per browser). An app opens in a window inside Nova instead of a new tab:
// a titlebar in the look you picked (Aero, Mica, Breeze, Glass, Material, Default) with minimize, maximize and
// close. Minimized windows wait in the navigation pill, like the phone app's dock. Windows live outside the page, so
// they stay open while you move around Nova. On a phone they always fill the screen.
import { $, $$, S, esc, get, prefs } from "./core.js";
import { I, toast } from "./ui.js";

const wins = [];                 // { id, app, el, min, max, z }
let zTop = 20, seq = 0;
const phone = () => matchMedia("(max-width: 700px)").matches;
const reduce = () => document.documentElement.classList.contains("reduce");
const frameOk = {};              // app id → the server's answer, once per page load

export const enabled = () => !!prefs.appWindows;

function layer() {
  let l = $("#wins");
  if (!l) { l = document.createElement("div"); l.id = "wins"; document.body.append(l); }
  return l;
}

/** Where a maximized window may go: the window, less the navigation pill. */
function area() {
  const n = $("#nav"), r = n && !n.hidden ? n.getBoundingClientRect() : null;
  const side = r && n.classList.contains("vertical");
  return { left: side ? Math.ceil(r.right + 8) : 0, top: 0, right: 0, bottom: r && !side ? Math.ceil(innerHeight - r.top + 8) : 0 };
}

const glyph = {
  min: '<svg viewBox="0 0 10 10"><path d="M1 5.5h8"/></svg>',
  max: '<svg viewBox="0 0 10 10"><rect x="1.5" y="1.5" width="7" height="7"/></svg>',
  restore: '<svg viewBox="0 0 10 10"><path d="M3 3V1.5h5.5V7H7"/><rect x="1.5" y="3" width="5.5" height="5.5"/></svg>',
  close: '<svg viewBox="0 0 10 10"><path d="M1.5 1.5l7 7M8.5 1.5l-7 7"/></svg>',
};

/** Opens the app in a window (or brings its window back). Returns false when it should open as a tab instead. */
export function openApp(a, href) {
  if (!href) return false;
  const have = wins.find(w => w.app.id === a.id);
  if (have) { restore(have); return true; }
  // a plain-http app can't be shown inside a page that came over https (the browser blocks it), so it gets a tab
  if (location.protocol === "https:" && href.startsWith("http:")) {
    toast(`${a.name} is on a plain http address, which browsers won't show inside Nova here — opened in a new tab`);
    window.open(href, "_blank", "noopener"); return true;
  }
  const w = { id: "w" + ++seq, app: a, href, min: false, max: phone(), z: ++zTop };
  const n = wins.length % 6, el = document.createElement("section");
  el.className = "win"; el.dataset.id = w.id; el.setAttribute("role", "dialog"); el.setAttribute("aria-label", a.name);
  const W = Math.min(1100, innerWidth - 120), H = Math.min(760, innerHeight - 140);
  Object.assign(el.style, { left: `${Math.max(8, (innerWidth - W) / 2 + n * 28)}px`, top: `${Math.max(8, (innerHeight - H) / 2 - 30 + n * 28)}px`, width: `${W}px`, height: `${H}px` });
  el.innerHTML = `<header class="wbar">
      <span class="wlights"><button class="wb wclose" data-w="close" title="Close">${glyph.close}</button><button class="wb wmin" data-w="min" title="Minimize">${glyph.min}</button><button class="wb wmax" data-w="max" title="Maximize">${glyph.max}</button></span>
      <span class="wic" data-ic="${esc(a.id)}">${esc((a.name || "?")[0].toUpperCase())}</span><b class="wtitle">${esc(a.name)}</b>
      <span class="wbtns"><button class="wb wtab" data-w="tab" title="Open in a new tab">${I("open")}</button><button class="wb wmin" data-w="min" title="Minimize">${glyph.min}</button><button class="wb wmax" data-w="max" title="Maximize">${glyph.max}</button><button class="wb wclose" data-w="close" title="Close">${glyph.close}</button></span>
    </header>
    <div class="wbody"><iframe src="${esc(href)}" title="${esc(a.name)}" referrerpolicy="no-referrer" allow="fullscreen; clipboard-write; autoplay"></iframe><div class="wmsg" hidden></div></div>
    <span class="wgrip" aria-hidden="true"></span>`;
  w.el = el; wins.push(w); layer().append(el);
  wire(w); place(w); focus(w); drawDock(); icon(w);
  if (!reduce()) el.animate([{ opacity: 0, transform: "scale(.94) translateY(12px)" }, { opacity: 1, transform: "none" }], { duration: 220, easing: "cubic-bezier(.2,.9,.3,1)" });
  check(w);
  return true;
}

/** Asks the server whether the app lets itself be shown in a frame; says why not, with a way to open it in a tab. */
async function check(w) {
  let r = frameOk[w.app.id];
  if (!r) { try { r = frameOk[w.app.id] = await get(`/api/v1/apps/${encodeURIComponent(w.app.id)}/frame`); } catch { return; } }
  if (!w.el.isConnected) return;
  const m = $(".wmsg", w.el);
  // behind Cloudflare sign-in: its sign-in page won't show in a frame, so you sign in once in a tab, then reload here
  if (r.frameable && r.signin && w.href.startsWith("https:") && w.href === w.app.remote_url) {
    m.hidden = false; $("iframe", w.el).style.visibility = "hidden";
    m.innerHTML = `<div>${I("key")}<b>Sign in to ${esc(w.app.name)} first</b><p>It's behind Cloudflare sign-in, and that page can't show inside a window. Sign in once in a tab, then come back here.</p><button class="btn" data-w="tab">Sign in in a tab</button><button class="linkbtn" data-w="try">It's done — show it here</button></div>`;
    $("[data-w=tab]", m).onclick = e => { window.open(w.href, "_blank", "noopener"); e.target.textContent = "Signed in? Show it here"; e.target.dataset.w = "try"; e.target.onclick = show; };
    const show = () => { m.hidden = true; const f = $("iframe", w.el); f.style.visibility = ""; f.src = w.href; };
    $("[data-w=try]", m).onclick = show;
    return;
  }
  if (r.frameable) return;
  $("iframe", w.el).remove();
  m.hidden = false;
  m.innerHTML = `<div>${I("open")}<b>${esc(w.app.name)} can't open inside Nova</b><p>${esc(cap(r.why || "it doesn't allow it"))}.</p><button class="btn" data-w="tab">Open in a new tab</button></div>`;
  $("[data-w=tab]", m).onclick = () => { window.open(w.href, "_blank", "noopener"); close(w); };
}
const cap = s => s[0].toUpperCase() + s.slice(1);

async function icon(w) {
  try {
    const { appIconUrl } = await import("./storage.js"), u = await appIconUrl(w.app.id);
    if (u) for (const el of [...$$(`.wic[data-ic="${CSS.escape(w.app.id)}"]`), ...$$(`.wdock [data-ic="${CSS.escape(w.app.id)}"]`)]) { el.classList.add("img"); el.style.backgroundImage = `url(${u})`; el.textContent = ""; }
  } catch {}
}

function wire(w) {
  const el = w.el, bar = $(".wbar", el);
  el.addEventListener("pointerdown", () => focus(w), true);
  $$("[data-w]", bar).forEach(b => b.onclick = e => { e.stopPropagation(); ({ min: () => minimize(w), max: () => toggleMax(w), close: () => close(w), tab: () => window.open(w.href, "_blank", "noopener") })[b.dataset.w](); });
  bar.ondblclick = e => { if (!e.target.closest("button")) toggleMax(w); };
  // dragging the titlebar moves it (a maximized window comes loose under the pointer); the corner grip resizes it
  const drag = (e, kind) => {
    if (e.button !== 0 || e.target.closest("button") || phone()) return;
    e.preventDefault();
    const r0 = el.getBoundingClientRect(), x0 = e.clientX, y0 = e.clientY;
    let started = false, l0 = r0.left, t0 = r0.top;
    const move = ev => {
      const dx = ev.clientX - x0, dy = ev.clientY - y0;
      if (!started) { if (Math.hypot(dx, dy) < 4) return; started = true; layer().classList.add("dragging");
        if (kind === "move" && w.max) { w.max = false; place(w); l0 = x0 - parseFloat(el.style.width) * (x0 - r0.left) / r0.width; t0 = 0; } }
      if (kind === "move") {
        const fw = parseFloat(el.style.width);
        el.style.left = `${Math.min(innerWidth - 80, Math.max(80 - fw, l0 + dx))}px`; el.style.top = `${Math.min(innerHeight - 40, Math.max(0, t0 + dy))}px`;
      } else { el.style.width = `${Math.max(320, r0.width + dx)}px`; el.style.height = `${Math.max(220, r0.height + dy)}px`; }
    };
    const up = () => { removeEventListener("pointermove", move); removeEventListener("pointerup", up); layer().classList.remove("dragging"); };
    addEventListener("pointermove", move); addEventListener("pointerup", up);
  };
  bar.onpointerdown = e => drag(e, "move");
  $(".wgrip", el).onpointerdown = e => drag(e, "size");
}

function place(w) {
  const el = w.el;
  el.classList.toggle("max", w.max || phone());
  el.hidden = w.min;
  if (w.max || phone()) { const a = phone() ? { left: 0, top: 0, right: 0, bottom: 0 } : area(); el.style.setProperty("--ml", a.left + "px"); el.style.setProperty("--mb", a.bottom + "px"); }
  $$(".wmax", el).forEach(b => { b.innerHTML = w.max ? glyph.restore : glyph.max; b.title = w.max ? "Restore" : "Maximize"; });
}
function focus(w) {
  if (w.el.style.zIndex != zTop || !w.el.classList.contains("on")) { w.z = ++zTop; w.el.style.zIndex = w.z; }
  wins.forEach(x => x.el.classList.toggle("on", x === w)); drawDock();
}
function toggleMax(w) { if (phone()) return; w.max = !w.max; anim(w, () => place(w)); }
function minimize(w) {
  const to = $(`.wdock [data-win="${w.id}"]`)?.getBoundingClientRect(), r = w.el.getBoundingClientRect();
  const done = () => { w.min = true; place(w); w.el.classList.remove("on"); drawDock(); };
  if (reduce() || !to) return done();
  w.el.animate([{ transform: "none", opacity: 1 }, { transform: `translate(${to.x + to.width / 2 - r.x - r.width / 2}px, ${to.y + to.height / 2 - r.y - r.height / 2}px) scale(.08)`, opacity: 0 }], { duration: 260, easing: "cubic-bezier(.4,0,.6,1)" }).onfinish = done;
  // on a phone a minimized app goes back to Nova's home, where the dock is
  if (phone()) import("./app.js").then(m => m.tab("home")).catch(() => {});
}
function restore(w) {
  const was = w.min; w.min = false; place(w); focus(w);
  if (was && !reduce()) w.el.animate([{ opacity: 0, transform: "scale(.9) translateY(30px)" }, { opacity: 1, transform: "none" }], { duration: 220, easing: "cubic-bezier(.2,.9,.3,1)" });
}
function close(w) {
  const i = wins.indexOf(w); if (i < 0) return;
  wins.splice(i, 1); drawDock();
  const gone = () => w.el.remove();
  if (reduce() || w.min) return gone();
  w.el.animate([{ opacity: 1, transform: "none" }, { opacity: 0, transform: "scale(.95)" }], { duration: 160, easing: "ease-in" }).onfinish = gone;
}
/** Animates a size/position change (maximize and back) from where it was. */
function anim(w, change) {
  const a = w.el.getBoundingClientRect(); change(); const b = w.el.getBoundingClientRect();
  if (reduce() || !b.width) return;
  w.el.animate([{ transformOrigin: "0 0", transform: `translate(${a.x - b.x}px, ${a.y - b.y}px) scale(${a.width / b.width}, ${a.height / b.height})` }, { transformOrigin: "0 0", transform: "none" }], { duration: 220, easing: "cubic-bezier(.2,.9,.3,1)" });
}

/** The open windows in the navigation pill: tap one to bring it up, tap the one in front to minimize it. */
export function drawDock() {
  const nav = $("#nav"); if (!nav) return;
  let d = $(".wdock", nav);
  if (!wins.length) { d?.remove(); return; }
  if (!d) { d = document.createElement("div"); d.className = "wdock"; nav.append(d); }
  const front = wins.filter(w => !w.min).sort((a, b) => b.z - a.z)[0];
  d.innerHTML = wins.map(w => `<button class="press${w === front ? " on" : ""}${w.min ? " mini" : ""}" data-win="${w.id}" title="${esc(w.app.name)}"><span class="appic" data-ic="${esc(w.app.id)}">${esc((w.app.name || "?")[0].toUpperCase())}</span></button>`).join("");
  $$("button", d).forEach(b => b.onclick = () => { const w = wins.find(x => x.id === b.dataset.win); if (!w) return; w === front && !w.min ? minimize(w) : restore(w); });
  wins.forEach(icon);
}

addEventListener("resize", () => wins.forEach(place));
// Esc in Nova goes back — with a window in front it minimizes that window instead
addEventListener("keydown", e => {
  if (e.key !== "Escape" || !wins.length) return;
  const front = wins.filter(w => !w.min).sort((a, b) => b.z - a.z)[0];
  if (front && front.el.classList.contains("on")) { e.stopImmediatePropagation(); minimize(front); }
}, true);
// Opening an app (the Apps page, Menu, the start page): in a window, when this browser has them on. A middle click or
// Ctrl/⌘-click still makes a tab.
document.addEventListener("click", async e => {
  if (!enabled() || e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
  const el = e.target.closest("[data-wid], [data-app]"); if (!el) return;
  const id = el.dataset.wid || el.dataset.app, a = (S.cache["/api/v1/apps"]?.apps || []).find(x => x.id === id);
  if (!a || a.kind === "command" || a.companion_of) return;
  e.preventDefault(); e.stopPropagation();
  const { appHrefFor } = await import("./storage.js");
  let h = appHrefFor(a);
  // this page came over https, so a plain-http address can't be shown in it: the app's https address does, when it has one
  if (h && location.protocol === "https:" && h.startsWith("http:") && /^https:/.test(a.remote_url || "")) h = a.remote_url;
  if (!openApp(a, h)) toast("This app isn't reachable from here");
}, true);
// switching to another server: its apps aren't this one's, so the windows close
addEventListener("nova:server", () => [...wins].forEach(close));
