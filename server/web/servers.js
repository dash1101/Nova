// Nova web — several servers in one place. This server's web shows other Nova servers read-only
// (their health, containers, live numbers); "Open" goes to that server's own Nova web, where it has
// its own keys and approvals. Adding one: its address, then the code from `sudo nova add` on it.
import { S, $, esc, get, post, del, isAdmin, uptime } from "./core.js";
import { I, note, toast, dialog, confirm } from "./ui.js";

const away = () => S.me?.via === "remote";
const pct = v => v == null ? "—" : Math.round(v) + "%";

function card(n, here) {
  const lvl = n.ok === false ? "critical" : n.level || "ok";
  const col = n.ok === false ? "var(--sub)" : lvl === "critical" ? "var(--red)" : lvl === "warning" ? "var(--amber)" : "var(--green)";
  const href = here ? "" : away() ? n.remote_url : n.lan_url;
  const c = n.containers || {};
  return `<div class="srv glass${here ? " here" : ""}">
    <div class="srvhead"><span class="ri" style="color:${col};background:color-mix(in srgb, ${col} 16%, transparent)">${I("dns")}</span>
      <div class="t"><b>${esc(n.name)}${here ? `<small class="muted"> · this one</small>` : ""}</b><small>${esc(n.ok === false ? n.error || "Can't reach it" : n.headline || "")}</small></div>
      ${here ? "" : isAdmin() ? `<button class="rmbtn press" data-act="rm:${esc(n.id)}" aria-label="Remove ${esc(n.name)}" title="Remove">${I("del")}</button>` : ""}</div>
    <div class="srvstats"><span><small>CPU</small><b>${pct(n.cpu)}</b></span><span><small>Memory</small><b>${pct(n.mem)}</b></span>
      <span><small>Containers</small><b>${c.total != null ? `${c.running}/${c.total}` : "—"}</b></span><span><small>Up</small><b>${n.uptime_s ? uptime(n.uptime_s).split(" ").slice(0, 2).join(" ") : "—"}</b></span></div>
    ${here ? "" : href ? `<a class="btn srvopen" href="${esc(href)}" target="_blank" rel="noopener">${I("open")} Open its Nova web</a>`
      : `<p class="note" style="margin:6px 0 0">Its Nova web is on your home network only${n.remote_url ? "" : " (it has no remote address set up)"}.</p>`}
  </div>`;
}

export async function servers(ctx) {
  const draw = () => {
    const o = S.overview || {}, srv = o.server || {}, now = S.lastNow || {}, list = S.cache["/api/v1/nodes"]?.nodes;
    const here = { name: srv.display_name || srv.name || "This server", level: o.status?.level, headline: o.status?.headline, containers: o.containers,
      cpu: now.cpu, mem: now.mem, uptime_s: now.uptime_s || srv.uptime_s, ok: true };
    ctx.show(`${note("Your other Nova servers, at a glance. To manage one, open its own Nova web — this page only reads their status.")}
      <div class="srvgrid">${card(here, true)}${(list || []).map(n => card(n, false)).join("")}
        ${isAdmin() ? `<button class="srv add glass press" data-act="add">${I("add")}<b>Add a server</b><small>Another machine running Nova, on your home network or tailnet</small></button>` : ""}</div>
      ${list ? "" : note("Loading…")}`, { title: "Servers", actions: isAdmin() ? [{ icon: "add", label: "Add a server", act: "add" }] : [] });
  };
  const add = async () => {
    let host = "";
    const p1 = dialog("Add a server", "Its address on your home network or tailnet. Nova must be installed on it.", [{ label: "Cancel", value: null }, { label: "Next", color: "var(--blue)", value: "next" }],
      `<div class="pad"><input class="field" id="nh" placeholder="192.168.1.20" autocomplete="off" autocapitalize="off" spellcheck="false"></div>`);
    $("#nh").oninput = e => host = e.target.value.trim(); $("#nh").focus();
    if ((await p1) !== "next" || !host) return;
    let pr; try { pr = await post("/api/v1/nodes/probe", { host }); } catch (e) { return toast(e.message); }
    let code = "";
    const p2 = dialog(`Pair with ${pr.host}`, "", [{ label: "Cancel", value: null }, { label: "Add", color: "var(--blue)", value: "add" }],
      `<div class="pad"><p class="muted" style="margin-top:0">On that server run <b>sudo nova add</b>, check that its <b>Certificate</b> line says</p>
        <div class="pinbox">${esc(pr.pin_short)}</div><p class="muted">and type the code it shows:</p>
        <input class="field" id="nc" placeholder="Pairing code" autocomplete="off" autocapitalize="characters" spellcheck="false" style="font-family:ui-monospace,monospace;letter-spacing:.08em"></div>`);
    $("#nc").oninput = e => code = e.target.value.trim(); $("#nc").focus();
    if ((await p2) !== "add" || !code) return;
    try { const r = await post("/api/v1/nodes", { host: pr.host, code, pin: pr.pin }); if (r?.id) toast(`Added ${r.name}`); }
    catch (e) { return toast(e.message); }
    await get("/api/v1/nodes").catch(() => {}); if (ctx.alive()) draw();
  };
  ctx.handlers({
    add,
    rm: async id => {
      const n = (S.cache["/api/v1/nodes"]?.nodes || []).find(x => x.id === id); if (!n) return;
      if (!(await confirm(`Remove ${n.name}?`, "It stops showing here, and that server forgets this Nova web. Nothing changes on it otherwise.", "Remove"))) return;
      try { await del(`/api/v1/nodes/${id}`); toast(`Removed ${n.name}`); } catch (e) { toast(e.message); }
      await get("/api/v1/nodes").catch(() => {}); if (ctx.alive()) draw();
    },
  });
  draw();
  ctx.every(10000, async () => { await Promise.all([get("/api/v1/nodes"), get("/api/v1/stats?since=9e12").then(r => { if (r.now?.cpu != null) S.lastNow = r.now; })]).catch(() => {}); if (ctx.alive()) draw(); }, true);
}
