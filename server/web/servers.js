// Nova web — several servers in one place. This server's web shows your other Nova servers (their health,
// containers, live numbers) and lets you control one from here: Nova looks and works the same, only it's
// that server you see and change. This browser pairs its own key with each server once (approved on that
// server's phone, or with `sudo nova approve` there); after that every request is signed for that server,
// and this one only carries it over. Adding a server: its address, then the code from `sudo nova add` on it.
import { S, $, esc, get, isAdmin, uptime, kv, pemOf, sleep, relayFetch, switchServer, hereApi, loadNodes, serverName } from "./core.js";
import { I, note, toast, dialog, confirm, sheet, closeSheet, copyCmd } from "./ui.js";

const away = () => S.me?.via === "remote";
const pct = v => v == null ? "—" : Math.round(v) + "%";

function card(n, here, on) {
  const lvl = n.ok === false ? "critical" : n.level || "ok";
  const col = n.ok === false ? "var(--sub)" : lvl === "critical" ? "var(--red)" : lvl === "warning" ? "var(--amber)" : "var(--green)";
  const href = here ? "" : away() ? n.remote_url : n.lan_url;
  const c = n.containers || {};
  const tag = on ? `<small class="muted"> · ${S.target ? "controlling" : "this one"}</small>` : here ? `<small class="muted"> · this one</small>` : "";
  return `<div class="srv glass${on ? " here" : ""}">
    <div class="srvhead"><span class="ri" style="color:${col};background:color-mix(in srgb, ${col} 16%, transparent)">${I("dns")}</span>
      <div class="t"><b>${esc(n.name)}${tag}</b><small>${esc(n.ok === false ? n.error || "Can't reach it" : n.headline || "")}</small></div>
      ${here || on ? "" : isAdmin() && !S.target ? `<button class="rmbtn press" data-act="rm:${esc(n.id)}" aria-label="Remove ${esc(n.name)}" title="Remove">${I("del")}</button>` : ""}</div>
    <div class="srvstats"><span><small>CPU</small><b>${pct(n.cpu)}</b></span><span><small>Memory</small><b>${pct(n.mem)}</b></span>
      <span><small>Containers</small><b>${c.total != null ? `${c.running}/${c.total}` : "—"}</b></span><span><small>Up</small><b>${n.uptime_s ? uptime(n.uptime_s).split(" ").slice(0, 2).join(" ") : "—"}</b></span></div>
    ${on ? "" : `<div class="srvbtns">${here || n.relay ? `<button class="btn srvopen" data-act="ctl:${esc(here ? "here" : n.id)}"${n.ok === false ? " disabled" : ""}>${I("dns")} ${here ? "Back to this server" : "Control from here"}</button>` : ""}
      ${here ? "" : href ? `<a class="btn ghost srvopen" href="${esc(href)}" target="_blank" rel="noopener">${I("open")} Its own Nova web</a>`
        : `<p class="note" style="margin:6px 0 0">Its Nova web is on your home network only${n.remote_url ? "" : " (it has no remote address set up)"}.</p>`}
      ${here || n.relay || n.ok === false ? "" : `<p class="note" style="margin:6px 0 0">Update Nova on it (sudo nova update) to control it from here.</p>`}</div>`}
  </div>`;
}

/** This browser asks `n` to add it (through this server), and waits for the approval there. → its device id, or null. */
async function pairWith(n) {
  const t = { id: n.id, name: n.name, device: "" };
  let j;
  try {
    const r = await relayFetch(t, "POST", "/api/v1/browser/request", JSON.stringify({ public_key: await pemOf(S.keys.publicKey), name: `${S.me?.device || "Browser"} (via ${serverName()})`.slice(0, 40) }));
    j = await r.json().catch(() => ({}));
    if (!r.ok) { toast(j.message || j.error || `Couldn't ask ${n.name} (${r.status})`); return null; }
  } catch (e) { toast(e.message); return null; }
  let stop = false; const until = Date.now() + (j.expires_in || 600) * 1000;
  const box = sheet(`<h2>Approve this browser on ${esc(n.name)}</h2>
    <p>Once per server: ${esc(n.name)} checks this browser itself, so it needs to know it. On an admin phone of <b>${esc(n.name)}</b> open Nova → Menu → Users &amp; devices → Approve a browser and enter</p>
    <div class="pinbox" style="font-size:26px;letter-spacing:.12em;text-align:center">${esc(j.code)}</div>
    <p>…or on ${esc(n.name)}:</p>${copyCmd("sudo nova approve " + j.code)}<p class="muted" id="pleft" style="text-align:center"></p>
    <div class="center" style="padding:10px"><div class="spinner" style="margin:auto"></div></div><div class="acts"><button data-b="0">Cancel</button></div>`, () => { stop = true; });
  $("[data-b]", box).onclick = () => { stop = true; closeSheet(); };
  while (!stop && Date.now() < until) {
    const s = Math.max(0, Math.round((until - Date.now()) / 1000)), el = $("#pleft"); if (el) el.textContent = `Expires in ${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
    await sleep(2000); if (stop) break;
    let st; try { st = await (await relayFetch(t, "GET", `/api/v1/browser/request/${j.id}`)).json(); } catch { continue; }
    if (st.state === "approved" && st.device_id) { closeSheet(); await kv("node:" + n.id, { device: st.device_id }); return st.device_id; }
    if (st.state === "expired" || st.state === "denied") break;
  }
  if (!stop) { closeSheet(); toast("The code expired — try again"); }
  return null;
}

/** Control server `id` from here ("" = this server again). Pairs this browser with it the first time. false = couldn't. */
export async function control(id) {
  if (!id) { if (S.target) { await switchServer(null); dispatchEvent(new Event("nova:server")); } return true; }
  const n = (S.nodes || await loadNodes().catch(() => [])).find(x => x.id === id);
  if (!n) return false;
  if (!n.relay) { toast(`${n.name} needs a newer Nova first — run sudo nova update on it`); return false; }
  for (let attempt = 0; attempt < 2; attempt++) {
    let rec = await kv("node:" + id).catch(() => null);
    if (!rec?.device) { const d = await pairWith(n); if (!d) return false; rec = { device: d }; }
    try {
      await switchServer({ id, name: n.name, device: rec.device, lan_url: n.lan_url, remote_url: n.remote_url });
      if (S.targetLost) throw Object.assign(new Error("forgotten"), { lost: true });
      dispatchEvent(new Event("nova:server")); toast(`Controlling ${n.name}`); return true;
    } catch (e) {
      await switchServer(null).catch(() => {});
      if (e.lost || e.kind === "not_paired_there") { await kv("node:" + id, null); continue; }      // it forgot this browser: pair again
      toast(e.message); dispatchEvent(new Event("nova:server")); return false;
    }
  }
  return false;
}

export async function servers(ctx) {
  let home = {};                // this server's own numbers, while you're controlling another
  const draw = () => {
    const o = S.overview || {}, srv = o.server || {}, now = S.lastNow || {}, list = S.nodes;
    const cur = { name: serverName(), level: o.status?.level, headline: o.status?.headline, containers: o.containers,
      cpu: now.cpu, mem: now.mem, uptime_s: now.uptime_s || srv.uptime_s, ok: true };
    const others = (list || []).filter(n => n.id !== S.target?.id);
    ctx.show(`${note(S.target ? `You're controlling ${S.target.name} from ${S.homeName || "this server"}'s Nova web. Everything you do happens on ${S.target.name}, and it asks its own phones to approve risky actions. Files, the terminal and speed tests need its own Nova web.`
        : "Your other Nova servers, at a glance. Control one from here — Nova stays the same, only it's that server you see and change. The first time, approve this browser on that server.")}
      <div class="srvgrid">${card(cur, false, true)}${S.target ? card({ name: S.homeName || "This server", ok: true, ...home }, true, false) : ""}${others.map(n => card(n, false, false)).join("")}
        ${isAdmin() && !S.target ? `<button class="srv add glass press" data-act="add">${I("add")}<b>Add a server</b><small>Another machine running Nova, on your home network or tailnet</small></button>` : ""}</div>
      ${list ? "" : note("Loading…")}`, { title: "Servers", actions: isAdmin() && !S.target ? [{ icon: "add", label: "Add a server", act: "add" }] : [] });
  };
  const add = async () => {
    let host = "";
    const p1 = dialog("Add a server", "Its address on your home network or tailnet. Nova must be installed on it.", [{ label: "Cancel", value: null }, { label: "Next", color: "var(--blue)", value: "next" }],
      `<div class="pad"><input class="field" id="nh" placeholder="192.168.1.20" autocomplete="off" autocapitalize="off" spellcheck="false"></div>`);
    $("#nh").oninput = e => host = e.target.value.trim(); $("#nh").focus();
    if ((await p1) !== "next" || !host) return;
    let pr; try { pr = await hereApi("POST", "/api/v1/nodes/probe", { host }); } catch (e) { return toast(e.message); }
    let code = "";
    const p2 = dialog(`Pair with ${pr.host}`, "", [{ label: "Cancel", value: null }, { label: "Add", color: "var(--blue)", value: "add" }],
      `<div class="pad"><p class="muted" style="margin-top:0">On that server run <b>sudo nova add</b>, check that its <b>Certificate</b> line says</p>
        <div class="pinbox">${esc(pr.pin_short)}</div><p class="muted">and type the code it shows:</p>
        <input class="field" id="nc" placeholder="Pairing code" autocomplete="off" autocapitalize="characters" spellcheck="false" style="font-family:ui-monospace,monospace;letter-spacing:.08em"></div>`);
    $("#nc").oninput = e => code = e.target.value.trim(); $("#nc").focus();
    if ((await p2) !== "add" || !code) return;
    try { const r = await hereApi("POST", "/api/v1/nodes", { host: pr.host, code, pin: pr.pin }); if (r?.id) toast(`Added ${r.name}`); }
    catch (e) { return toast(e.message); }
    await loadNodes().catch(() => {}); if (ctx.alive()) draw();
  };
  ctx.handlers({
    add,
    ctl: id => control(id === "here" ? "" : id),
    rm: async id => {
      const n = (S.nodes || []).find(x => x.id === id); if (!n) return;
      if (!(await confirm(`Remove ${n.name}?`, "It stops showing here, and that server forgets this Nova web and this browser. Nothing changes on it otherwise.", "Remove"))) return;
      // this browser's own key on that server goes too (a browser may always remove itself), then this server forgets it
      const rec = await kv("node:" + id).catch(() => null);
      if (rec?.device) await relayFetch({ id, name: n.name, device: rec.device }, "DELETE", `/api/v1/devices/${rec.device}`).catch(() => {});
      try { await hereApi("DELETE", `/api/v1/nodes/${id}`); await kv("node:" + id, null).catch(() => {}); toast(`Removed ${n.name}`); } catch (e) { toast(e.message); }
      await loadNodes().catch(() => {}); if (ctx.alive()) draw();
    },
  });
  draw();
  ctx.every(10000, async () => { await Promise.all([loadNodes(),
    S.target ? Promise.all([hereApi("GET", "/api/v1/overview"), hereApi("GET", "/api/v1/stats?since=9e12")]).then(([o, st]) => {
      home = { level: o.status?.level, headline: o.status?.headline, containers: o.containers, cpu: st.now?.cpu, mem: st.now?.mem, uptime_s: st.now?.uptime_s || o.server?.uptime_s }; }) : null, get("/api/v1/stats?since=9e12").then(r => { if (r.now?.cpu != null) S.lastNow = r.now; })]).catch(() => {}); if (ctx.alive()) draw(); }, true);
}
