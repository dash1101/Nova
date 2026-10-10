// Nova web — Files: browse the server as your normal account, open and edit text, upload,
// download, new files and folders, rename, move, copy, and delete (to the Trash).
import { S, $, $$, esc, get, post, bytes, isAdmin, signedFetch, signedRaw, prefs } from "./core.js";
import { I, row, group, sec, note, toast, dialog, confirm, switchRow, sheet, closeSheet } from "./ui.js";

const enc = encodeURIComponent;
const CHUNK = 4 * 1024 * 1024;
const join = (d, n) => (d === "/" ? "" : d) + "/" + n;
const when = t => { const d = new Date(t * 1000), now = new Date();
  return d.toDateString() === now.toDateString() ? d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : d.toLocaleDateString([], { month: "short", day: "numeric", year: d.getFullYear() === now.getFullYear() ? undefined : "numeric" }); };
const kindIcon = e => e.dir ? "folder" : /^image\//.test(e.mime) ? "image" : /^(video|audio)\//.test(e.mime) ? "play" : /^text\/|json|xml|yaml|javascript|x-sh/.test(e.mime) || /\.(md|conf|cfg|ini|log|env|toml|ya?ml|sh|py|js|ts|json|txt|service)$/i.test(e.name) ? "article" : "file";
const textish = e => kindIcon(e) === "article" || (!e.mime && e.size < 512 * 1024);

export async function files(ctx) {
  if (!isAdmin()) return ctx.show(note("Only admins can use the file manager."), { title: "Files" });
  const path = ctx.args.length ? decodeURIComponent(ctx.args.join("/")) : "";
  if (ctx.args[0] === "edit") return editor(ctx, decodeURIComponent(ctx.args.slice(1).join("/")));
  let d = null, busy = null, picked = new Set();
  const load = async () => { try { d = await get(`/api/v1/files?path=${enc(path)}`); } catch (e) { d = { error: e.message }; } if (ctx.alive()) draw(); };
  const crumbs = p => { const parts = p.split("/").filter(Boolean); let acc = "";
    return `<div class="crumbs">${[["/", "Server"], ...parts.map(x => [acc += "/" + x, x])].map(([to, l], i, all) => `<button class="crumb${i === all.length - 1 ? " on" : ""}" data-act="cd:${esc(enc(to))}">${i === 0 ? I("dns") : ""}${esc(l)}</button>`).join(`<span class="muted">›</span>`)}</div>`; };
  const draw = () => {
    if (!d) return ctx.show(note("Loading…"), { title: "Files" });
    if (d.error) return ctx.show(note(d.error) + group(row("Go to your home folder", { icon: "home", click: "cd:~" })), { title: "Files" });
    const show = d.items.filter(e => prefs.filesHidden || !e.hidden);
    ctx.show(`${crumbs(d.path)}
      ${busy ? `<div class="taskbar glass"><div class="tb-top"><b>${esc(busy.label)}</b><span>${Math.round(busy.pct)}%</span></div><div class="bar"><i style="width:${busy.pct}%;background:var(--blue)"></i></div></div>` : ""}
      <div class="fsel${picked.size ? " show" : ""}"><b>${picked.size} selected</b><span class="sp"></span><button class="pillbtn press" data-act="selmove">Move…</button><button class="pillbtn press" data-act="selcopy">Copy…</button><button class="pillbtn press red" data-act="seldel">Delete</button><button class="pillbtn press" data-act="selnone">Clear</button></div>
      <div class="group glass fslist" id="drop">${d.parent != null ? `<div class="row click" data-act="cd:${esc(enc(d.parent))}"><span class="ri" style="color:var(--sub);background:color-mix(in srgb,var(--sub) 14%,transparent)">${I("back")}</span><div class="t"><b>Up one folder</b><small>${esc(d.parent)}</small></div></div>` : ""}
        ${show.map((e, i) => `<div class="row click fsrow${picked.has(e.name) ? " picked" : ""}" data-i="${d.items.indexOf(e)}" draggable="false">
          <button class="tick${picked.has(e.name) ? " on" : ""}" data-act="pick:${d.items.indexOf(e)}" aria-label="Select">${I("check")}</button>
          <span class="ri" style="color:${e.dir ? "var(--blue)" : "var(--sub)"};background:color-mix(in srgb,${e.dir ? "var(--blue)" : "var(--sub)"} 14%,transparent)">${I(kindIcon(e))}</span>
          <div class="t"><b>${esc(e.name)}${e.link ? ` <small class="muted">↪</small>` : ""}</b><small>${e.dir ? "Folder" : bytes(e.size)} · ${when(e.mtime)}${e.writable ? "" : " · read-only"}</small></div>
          <button class="xbtn" style="opacity:.8" data-act="menu:${d.items.indexOf(e)}" aria-label="More" title="More">${I("more")}</button></div>`).join("")
          || `<div class="row"><div class="t"><small>${d.items.length ? "Only hidden files here" : "This folder is empty"}${d.writable ? " — drop files here to upload them" : ""}</small></div></div>`}</div>
      <p class="note">${bytes(d.free)} free of ${bytes(d.total)} · ${d.writable ? "Drop files anywhere on this page to upload them here." : "You can't change this folder."} Deleted things go to the Trash (~/.local/share/Trash).</p>
      ${group(switchRow("Show hidden files", null, !!prefs.filesHidden, "hidden", { blue: false }))}`,
      { title: d.path === d.home ? "Home" : d.path === "/" ? "Server" : d.path.split("/").pop(),
        actions: d.writable ? [{ icon: "add", label: "New…", act: "new" }, { icon: "up", label: "Upload files", act: "upload" }, { icon: "home", label: "Home folder", act: "cd:~" }] : [{ icon: "home", label: "Home folder", act: "cd:~" }] });
    $$(".fsrow", ctx.root).forEach(el => {
      el.onclick = ev => { if (ev.target.closest("[data-act]")) return; const e = d.items[+el.dataset.i];
        if (ev.ctrlKey || ev.metaKey || ev.shiftKey || picked.size) return toggle(e.name);
        open(e); };
      el.oncontextmenu = ev => { ev.preventDefault(); menu(d.items[+el.dataset.i]); };
    });
  };
  const toggle = n => { picked.has(n) ? picked.delete(n) : picked.add(n); draw(); };
  const cd = to => ctx.go("files/" + (to === "~" ? enc(d?.home || "~") : to));
  const open = e => {
    const p = join(d.path, e.name);
    if (e.dir) return cd(enc(p));
    if (textish(e) && e.size <= 1024 * 1024) return ctx.go("files/edit/" + enc(p));
    if (/^image\//.test(e.mime)) return preview(p, e);
    download(p, e.name);
  };
  const preview = async (p, e) => {
    const r = await signedFetch("GET", `/api/v1/files/download?path=${enc(p)}&inline=1`); if (!r.ok) return toast("Couldn't open it");
    const url = URL.createObjectURL(await r.blob());
    const v = await dialog(e.name, "", [{ label: "Close", value: null }, { label: "Download", color: "var(--blue)", value: "dl" }], `<div class="pad"><img src="${url}" alt="" style="max-width:100%;max-height:60vh;border-radius:14px;display:block;margin:auto"></div>`);
    URL.revokeObjectURL(url); if (v === "dl") download(p, e.name);
  };
  const download = async (p, name) => {
    busy = { label: `Downloading ${name}`, pct: 0 }; draw();
    try {
      const r = await signedFetch("GET", `/api/v1/files/download?path=${enc(p)}`);
      if (!r.ok) { let j = {}; try { j = await r.json(); } catch {} throw new Error(j.error || "Couldn't download it"); }
      const total = +r.headers.get("Content-Length") || 0, reader = r.body.getReader(), parts = []; let got = 0;
      for (;;) { const { done, value } = await reader.read(); if (done) break; parts.push(value); got += value.length; if (total) { busy.pct = got / total * 100; if (ctx.alive()) { const b = $(".taskbar .bar i"); if (b) b.style.width = busy.pct + "%"; const s = $(".taskbar .tb-top span"); if (s) s.textContent = Math.round(busy.pct) + "%"; } } }
      const a = document.createElement("a"); a.href = URL.createObjectURL(new Blob(parts)); a.download = name; document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(a.href), 30000);
    } catch (e) { toast(e.message); } finally { busy = null; if (ctx.alive()) draw(); }
  };
  const upload = async list => {
    for (const f of list) {
      const exists = d.items.some(e => e.name === f.name);
      if (exists && !(await confirm(`Replace “${f.name}”?`, "There's already one with that name here.", "Replace"))) continue;
      busy = { label: `Uploading ${f.name}`, pct: 0 }; draw();
      try {
        for (let off = 0; off < f.size || off === 0; off += CHUNK) {
          const last = off + CHUNK >= f.size;
          await signedRaw("POST", `/api/v1/files/upload?path=${enc(d.path)}&name=${enc(f.name)}&offset=${off}${last ? "&last=1" : ""}${exists ? "&replace=1" : ""}`, f.slice(off, off + CHUNK));
          busy.pct = Math.min(100, (off + CHUNK) / Math.max(1, f.size) * 100); if (ctx.alive()) draw();
          if (last) break;
        }
      } catch (e) { toast(`${f.name}: ${e.message}`); break; }
    }
    busy = null; await load();
  };
  const ask = (title, text, label, value = "", ph = "") => new Promise(async res => {
    let v = value;
    const p = dialog(title, text, [{ label: "Cancel", value: null }, { label, color: "var(--blue)", value: "ok" }], `<div class="pad"><input class="field" id="fsname" value="${esc(value)}" placeholder="${esc(ph)}" autocomplete="off" spellcheck="false"></div>`);
    const i = $("#fsname"); i.oninput = () => v = i.value; i.focus(); const dot = value.lastIndexOf("."); i.setSelectionRange(0, dot > 0 ? dot : value.length);
    i.onkeydown = e => { if (e.key === "Enter") $$("#sheet [data-b]").pop()?.click(); };
    res((await p) === "ok" ? v.trim() : null);
  });
  const act = async (body, ok) => { try { await post("/api/v1/files", body); if (ok) toast(ok); } catch (e) { toast(e.message); } picked.clear(); await load(); };
  const chooseFolder = async () => { const { pickFolder } = await import("./storage.js"); return pickFolder(d.path); };
  const menu = async e => {
    const opts = [...(e.dir ? [["open", "Open", "folder"]] : [["dl", "Download", "down"], ...(textish(e) && e.size <= 1048576 ? [["edit", "Open as text", "edit"]] : [])]),
      ["rename", "Rename", "edit"], ["move", "Move to…", "folder"], ["copy", "Make a copy in…", "copy"], ["copypath", "Copy its path", "copy"], ["del", "Delete", "del"]];
    const k = await new Promise(res => {
      const box = sheet(`<h2>${esc(e.name)}</h2><p>${e.dir ? "Folder" : bytes(e.size)} · ${esc(e.mode)} · ${esc(e.owner)}</p><div class="body">${opts.map(([k, l, ic]) =>
        `<button class="choice" data-c="${k}"><span class="t fsopt${k === "del" ? " red" : ""}">${I(ic)}${l}</span></button>`).join("")}</div><div class="acts"><button data-c="">Cancel</button></div>`, () => res(null));
      $$("[data-c]", box).forEach(b => b.onclick = () => { closeSheet(); res(b.dataset.c || null); });
    });
    const p = join(d.path, e.name);
    if (k === "open") cd(enc(p)); else if (k === "dl") download(p, e.name); else if (k === "edit") ctx.go("files/edit/" + enc(p));
    else if (k === "rename") { const n = await ask("Rename", "", "Rename", e.name); if (n && n !== e.name) act({ op: "rename", path: p, name: n }); }
    else if (k === "move") { const to = await chooseFolder(); if (to) act({ op: "rename", path: p, to }, `Moved to ${to}`); }
    else if (k === "copy") { const to = await chooseFolder(); if (to) act({ op: "copy", path: p, to }, "Copied"); }
    else if (k === "copypath") { try { await navigator.clipboard.writeText(p); toast("Path copied"); } catch { toast(p); } }
    else if (k === "del") { if (await confirm(`Delete ${e.name}?`, "It goes to the Trash on the server, so it can be put back.", "Delete")) act({ op: "trash", path: p }, "Moved to the Trash"); }
  };
  ctx.handlers({
    cd: to => cd(to), pick: i => toggle(d.items[+i].name), menu: i => menu(d.items[+i]),
    hidden: () => { prefs.filesHidden = !prefs.filesHidden; draw(); },
    selnone: () => { picked.clear(); draw(); },
    seldel: async () => { if (await confirm(`Delete ${picked.size} item${picked.size === 1 ? "" : "s"}?`, "They go to the Trash on the server.", "Delete")) act({ op: "trash", paths: [...picked].map(n => join(d.path, n)) }, "Moved to the Trash"); },
    selmove: async () => { const to = await chooseFolder(); if (!to) return; for (const n of picked) await post("/api/v1/files", { op: "rename", path: join(d.path, n), to }).catch(e => toast(`${n}: ${e.message}`)); picked.clear(); load(); },
    selcopy: async () => { const to = await chooseFolder(); if (!to) return; for (const n of picked) await post("/api/v1/files", { op: "copy", path: join(d.path, n), to }).catch(e => toast(`${n}: ${e.message}`)); picked.clear(); load(); },
    new: async () => {
      const k = await dialog("New", "", [{ label: "Cancel", value: null }, { label: "Text file", value: "file" }, { label: "Folder", color: "var(--blue)", value: "dir" }]);
      if (!k) return;
      const n = await ask(k === "dir" ? "New folder" : "New text file", "", "Create", k === "dir" ? "" : "", k === "dir" ? "Folder name" : "notes.txt"); if (!n) return;
      try { const r = await post("/api/v1/files", { op: k === "dir" ? "mkdir" : "new", path: d.path, name: n });
        if (k === "file") return ctx.go("files/edit/" + enc(r.path)); await load(); } catch (e) { toast(e.message); }
    },
    upload: () => { const i = document.createElement("input"); i.type = "file"; i.multiple = true; i.onchange = () => i.files.length && upload([...i.files]); i.click(); },
  });
  // drag and drop anywhere on the page
  const over = ev => { if (!d?.writable || ![...(ev.dataTransfer?.types || [])].includes("Files")) return; ev.preventDefault(); $("#drop")?.classList.add("over"); };
  const leave = () => $("#drop")?.classList.remove("over");
  const dropped = ev => { if (!d?.writable || !ev.dataTransfer?.files?.length) return; ev.preventDefault(); leave(); upload([...ev.dataTransfer.files]); };
  addEventListener("dragover", over); addEventListener("dragleave", leave); addEventListener("drop", dropped);
  ctx.onLeave(() => { removeEventListener("dragover", over); removeEventListener("dragleave", leave); removeEventListener("drop", dropped); });
  draw(); await load();
}

/** A plain text editor for files up to 1 MB. Ctrl+S saves. */
async function editor(ctx, path) {
  let d, dirty = false;
  try { d = await post("/api/v1/files", { op: "read", path }); } catch (e) { return ctx.show(note(e.message), { title: path.split("/").pop() }); }
  ctx.show(`<div class="edwrap"><div class="muted edpath">${esc(path)}</div><textarea id="ed" class="editor" spellcheck="false" autocapitalize="off" autocomplete="off">${esc(d.text)}</textarea>
      <div class="cact" style="padding:0"><span class="muted" id="edstate">Saved</span><span class="sp" style="flex:1"></span><button class="btn" data-act="save">${I("check")} Save</button></div></div>`,
    { title: path.split("/").pop(), narrow: false });
  const ed = $("#ed"), state = $("#edstate");
  ed.oninput = () => { dirty = true; state.textContent = "Not saved yet"; };
  ed.onkeydown = e => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") { e.preventDefault(); ctx.run("save"); }
    if (e.key === "Tab") { e.preventDefault(); const s = ed.selectionStart; ed.setRangeText("  ", s, ed.selectionEnd, "end"); ed.oninput(); }
  };
  const before = e => { if (dirty) { e.preventDefault(); e.returnValue = ""; } };
  addEventListener("beforeunload", before); ctx.onLeave(() => removeEventListener("beforeunload", before));
  ctx.handlers({ save: async () => {
    try { const r = await signedRaw("POST", `/api/v1/files/save?path=${enc(path)}&mtime=${d.mtime}`, new TextEncoder().encode(ed.value));
      d.mtime = r.mtime; dirty = false; state.textContent = "Saved"; toast("Saved"); }
    catch (e) { toast(e.message); }
  } });
  ed.focus();
}
