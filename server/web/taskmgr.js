// Nova web — Task manager: the server's processes, like top. Sort by CPU, memory or name, search, and tap one for its
// details, where you can end it (or force-stop it). Ending one needs approval on an admin phone, like any risky step;
// the server refuses the system's own core, Nova and SSH. Refreshes every few seconds while it's open.
import { esc, get, post, bytes } from "./core.js";
import { group, note, toast, sheet, closeSheet, confirm, segmented, wireCommon } from "./ui.js";

let sortBy = "cpu", q = "", showKernel = false;

const dur = s => s >= 86400 ? `${Math.floor(s / 86400)} d ${Math.floor(s % 86400 / 3600)} h` : s >= 3600 ? `${Math.floor(s / 3600)} h ${Math.floor(s % 3600 / 60)} min` : s >= 60 ? `${Math.floor(s / 60)} min` : `${s} s`;

export async function processes(ctx) {
  let data = null, err = "", timer = 0;
  const load = async () => {
    try { data = await get("/api/v1/processes"); err = ""; } catch (e) { err = e.message; }
    if (ctx.alive()) { draw(); timer = setTimeout(load, 3000); }
  };
  const list = () => {
    let ps = (data?.processes || []).filter(p => showKernel || !p.kernel);
    if (q) { const k = q.toLowerCase(); ps = ps.filter(p => p.name.toLowerCase().includes(k) || p.cmd.toLowerCase().includes(k) || p.user.toLowerCase().includes(k) || String(p.pid) === k); }
    return ps.sort(sortBy === "mem" ? (a, b) => b.mem - a.mem : sortBy === "name" ? (a, b) => a.name.localeCompare(b.name) : (a, b) => b.cpu - a.cpu || b.mem - a.mem);
  };
  const draw = () => {
    const focused = document.activeElement?.id === "tmq";
    if (!data && !err) { ctx.show(`<p class="muted pad">Loading…</p>`, { title: "Task manager" }); return; }
    const m = data?.memory || {}, ps = list(), cpus = data?.cpus || 1;
    const total = (data?.processes || []).reduce((a, p) => a + p.cpu, 0) / cpus;
    ctx.show(`
      <div class="tmhead">
        <div><b>${Math.round(total)}%</b><small>CPU · load ${(data?.load?.[0] ?? 0).toFixed(2)}</small></div>
        <div><b>${m.total ? Math.round(m.used / m.total * 100) + "%" : "—"}</b><small>Memory · ${m.used ? bytes(m.used) + " of " + bytes(m.total) : ""}</small></div>
        <div><b>${data?.count ?? "—"}</b><small>Processes</small></div>
      </div>
      <div style="margin:4px var(--gutter)"><input id="tmq" class="field" style="width:100%" placeholder="Search by name, user or PID" value="${esc(q)}" autocomplete="off"></div>
      ${segmented(["CPU", "Memory", "Name"], ["cpu", "mem", "name"].indexOf(sortBy), "tmsort")}
      ${err ? note(esc(err)) : ""}
      ${group(`<div class="tmrow tmh"><span>Name</span><span>CPU</span><span>Memory</span></div>` + ps.slice(0, 150).map(p => `
        <button class="tmrow press" data-act="proc:${p.pid}"><span class="tmn"><b>${esc(p.name)}</b><small>${esc(p.user)} · ${p.pid}</small></span>
          <span class="tmv${p.cpu >= 50 ? " hot" : ""}">${p.cpu.toFixed(1)}%</span><span class="tmv">${bytes(p.mem)}</span></button>`).join("")
        + (ps.length > 150 ? `<div class="row"><div class="t muted">…and ${ps.length - 150} more — search to find one</div></div>` : "") + (ps.length ? "" : `<div class="row"><div class="t muted">No process matches</div></div>`))}
      <div class="links glass" style="margin-top:6px"><button data-act="kernel">${showKernel ? "Hide kernel threads" : "Show kernel threads"}</button></div>`, { title: "Task manager" });
    wireCommon(ctx.root, { onSeg: (k, i) => { if (k === "tmsort") { sortBy = ["cpu", "mem", "name"][i]; draw(); } } });
    const inp = document.getElementById("tmq");
    if (inp) { inp.oninput = () => { q = inp.value; clearTimeout(inp._t); inp._t = setTimeout(draw, 150); }; if (focused) { inp.focus(); inp.setSelectionRange(q.length, q.length); } }
  };
  const details = pid => {
    const p = (data?.processes || []).find(x => x.pid === pid); if (!p) return;
    sheet(`<h2>${esc(p.name)}</h2><div class="pad"><dl class="kv">
        <dt>PID</dt><dd>${p.pid}${p.ppid ? ` (started by ${p.ppid})` : ""}</dd><dt>User</dt><dd>${esc(p.user)}</dd>
        <dt>CPU</dt><dd>${p.cpu.toFixed(1)}%</dd><dt>Memory</dt><dd>${bytes(p.mem)}</dd><dt>Threads</dt><dd>${p.threads}</dd>
        <dt>Running for</dt><dd>${dur(p.up_s)}</dd></dl>
        ${p.cmd ? `<div class="muted" style="font-size:13px;margin-top:8px;overflow-wrap:anywhere;font-family:ui-monospace,monospace">${esc(p.cmd)}</div>` : ""}</div>
      ${p.kernel ? note("Part of the Linux kernel — it can't be stopped.") : `<div class="sheetbtns"><button class="btn ghost" data-sact="term">End process</button><button class="btn" style="background:var(--red)" data-sact="kill">Force stop</button></div>
      ${note("End asks it to close (it can save its work first); Force stop ends it at once. Needs approval on your phone. A service may start again by itself.")}`}`);
    document.querySelectorAll("[data-sact]").forEach(b => b.onclick = async () => {
      const kill = b.dataset.sact === "kill";
      if (kill && !(await confirm(`Force stop ${p.name}?`, "It ends at once, without saving anything. If it's part of something bigger, that can stop working too.", "Force stop"))) return;
      closeSheet();
      try { await post(`/api/v1/processes/${p.pid}/signal`, { sig: kill ? "kill" : "term", name: p.name }); toast(`${kill ? "Force-stopped" : "Ended"} ${p.name}`); }
      catch (e) { toast(e.message); }
      clearTimeout(timer); load();
    });
  };
  ctx.handlers({
    kernel: () => { showKernel = !showKernel; draw(); },
    proc: v => details(+v),
  });
  draw(); load();
}
