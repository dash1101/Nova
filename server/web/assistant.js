// Nova web — the assistant (Labs): ask in plain words, see the plan, then Nova does it one step at a time.
// The server only plans (POST /api/v1/assistant/plan); every step is an ordinary call this browser signs itself, so the
// usual rules hold: risky steps wait for an admin phone's approval, everything is in the action log. Stop halts before the
// next step (a task the server already started — an install, freeing space — finishes on its own).
import { S, $, $$, esc, get, post, del, prefs, waitTask, bytes } from "./core.js";
import { I, group, sec, note, toast, choose, bar } from "./ui.js";
import { stage as stagePartitions } from "./parts.js";

let pending = "";                                       // text handed over by Search ("Ask Nova: …")
export const ask = text => { pending = text || ""; location.hash = "#/assistant"; };

// The only calls a plan may make (the server makes plans, but this browser checks before signing anything)
const ALLOWED = [["POST", /^\/api\/v1\/fan$/], ["POST", /^\/api\/v1\/actions\/(free-ram|backup)$/], ["POST", /^\/api\/v1\/store\/[a-z0-9-]{1,40}\/install$/],
  ["POST", /^\/api\/v1\/containers\/[A-Za-z0-9][A-Za-z0-9_.-]{0,62}\/(start|stop|restart)$/], ["POST", /^\/api\/v1\/power\/(schedule|reboot|poweroff)$/], ["POST", /^\/api\/v1\/freeup$/]];
const allowed = c => c && ALLOWED.some(([m, re]) => m === c.method && re.test(c.path));
const ROUTES = { home: "home", status: "status", containers: "containers", store: "store", inbox: "inbox", lighting: "lighting", hardware: "hardware", backups: "backups",
  updates: "updates", settings: "settings", appearance: "appearance", security: "security", devices: "devices", files: "files", terminal: "term", dashboard: "dashboard",
  apps: "apps", freeup: "freeup", recommended: "recommended", power: "quick", "container-new": "container-new" };
const UI = { theme: v => { prefs.theme = v; }, style: v => { prefs.style = v; }, reduce_motion: v => { prefs.reduceMotion = !!v; } };

/** The orb: soft light in the accent, turning while Nova thinks (still with Reduce motion). */
const orb = (state = "") => `<div class="aorb ${state}" aria-hidden="true"><i></i><i></i><i></i><b></b></div>`;

export async function assistant(ctx) {
  let text = pending; pending = "";
  const lab = S.cache["/api/v1/labs"] || await get("/api/v1/labs").catch(() => null);
  if (!lab?.labs?.assistant) {
    ctx.show(`<div class="ahead">${orb("idle")}<h2>The assistant is in Labs</h2><p class="muted">Turn on “Nova assistant” in Settings → Labs, then ask Nova in plain words — from here or from Search.</p></div>
      ${group(`<div class="row click" data-act="go:labs" role="button" tabindex="0"><span class="ri">${I("science")}</span><div class="t"><b>Open Labs</b></div></div>`)}`, { title: "Assistant" });
    return;
  }
  let plan = null, busy = false, stop = false, cur = -1, results = {}, choice = {}, summary = "", err = "";
  const risky = s => s.danger ? "danger" : /\/(power|store|freeup)\b|\/(stop|restart)$/.test(s.call?.path || "") ? "stepup" : "";
  const stepsNow = () => (plan?.steps || []).map(s => s.kind === "ask" ? (choice[s.id] != null ? { ...s.options[choice[s.id]].step, id: s.id, asked: s } : s) : s);
  const badge = s => ({ danger: `<span class="abadge red">${I("warn")}Erases data — you'll confirm it</span>`, stepup: `<span class="abadge">${I("key")}Needs approval</span>` })[risky(s)] || "";
  const stIcon = (s, i) => { const r = results[s.id];
    if (r?.ok) return `<span class="astate ok">${I("check")}</span>`; if (r && !r.ok) return `<span class="astate bad">${I("err")}</span>`;
    if (i === cur) return `<span class="astate run"><span class="spinner"></span></span>`; return `<span class="astate">${i + 1}</span>`; };
  const draw = () => {
    const steps = stepsNow(), next = steps.find((s, i) => i > cur && !results[s.id]);
    const waiting = steps.find(s => s.kind === "ask");
    ctx.show(`<div class="ahead">${orb(busy ? "think" : plan ? "" : "idle")}<h2>${busy ? (cur >= 0 ? "On it…" : "Thinking…") : plan ? (summary ? "Done" : plan.steps?.length ? "Here's my plan" : plan.reply ? "" : "Hmm") : "What can I do?"}</h2>
        ${plan?.reply && !summary ? `<p class="areply">${esc(plan.reply)}</p>` : ""}
        <p class="muted">${summary ? esc(summary) : plan ? (plan.steps?.length ? "Check it, answer anything I asked, then tap Do it. Nothing has changed yet." : "") : "Say it in plain words — several things at once is fine, or ask about your server."}</p></div>
      <form class="abox" id="aform" autocomplete="off"><textarea id="aq" rows="2" maxlength="500" placeholder="e.g. Set the lights to fire, free up some space and restart Jellyfin" aria-label="Ask Nova">${esc(text)}</textarea>
        <button class="abtn press" id="ago" type="submit" aria-label="Plan it" ${busy ? "disabled" : ""}>${I("back")}</button></form>
      ${err ? `<p class="note" style="color:var(--red)">${esc(err)}</p>` : ""}
      ${plan && (steps.length || !plan.reply) ? sec(`Plan · ${steps.length} step${steps.length === 1 ? "" : "s"}`) + group(steps.map((s, i) => `<div class="row astep${i === cur ? " now" : ""}${results[s.id] ? " did" : ""}">${stIcon(s, i)}
          <div class="t"><b>${esc(s.kind === "ask" ? s.say : s.say)}</b>${s.kind === "ask" ? `<small>${esc(s.ask)}</small><div class="achips">${s.options.map((o, k) => `<button class="achip press" data-act="pick:${s.id}:${k}" title="${esc(o.sub || "")}">${esc(o.label)}${o.sub ? `<small>${esc(o.sub)}</small>` : ""}</button>`).join("")}</div>`
            : results[s.id] ? `<small class="${results[s.id].ok ? "" : "bad"}">${esc(results[s.id].msg)}</small>` : badge(s)}
            ${s.asked && !busy && !results[s.id] ? `<button class="alink" data-act="unpick:${s.id}">Change</button>` : ""}</div></div>`).join("") || `<div class="row"><div class="t muted">Nothing I can do from that.</div></div>`)
        + (plan.unknown?.length ? note(`I didn't understand: ${plan.unknown.map(u => `“${u}”`).join(", ")}. Try saying it another way, or search for it.`) : "")
        + (plan.notes || []).map(n => note(n)).join("")
        + (busy && next ? `<div class="anext">${I("history")}<span>Next: ${esc(next.say)}</span></div>` : "")
        + `<div class="aacts">${busy ? `<button class="btn ghost" data-act="stop" ${stop ? "disabled" : ""}>${stop ? "Stopping after this step…" : "Stop"}</button>`
            : summary ? `<button class="btn" data-act="again">Ask something else</button>` : `<button class="btn ghost" data-act="clear">Cancel</button><button class="btn" data-act="run" ${!steps.length || waiting ? "disabled" : ""}>${waiting ? "Answer the question first" : "Do it"}</button>`}</div>`
        + (busy ? note("Stop halts before the next step. Something the server has already started (an install, freeing space) finishes on its own.") : "")
      : note("Runs on your server — nothing is sent anywhere. Risky steps still need approval on your phone, and everything goes in the action log.") + brainHtml()}`, { title: "Assistant" });
    const q = $("#aq"); q?.focus({ preventScroll: true });
    q && (q.onkeydown = e => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); $("#aform").requestSubmit(); } });
    $("#aform").onsubmit = e => { e.preventDefault(); if (!busy) planIt($("#aq").value); };
  };
  // its brain: a small language model on the server (Smart or Light), so it understands more and answers questions
  let br = S.cache["/api/v1/assistant/brain"] || null, brTask = null;
  const brainHtml = () => {
    if (!br) return "";
    const c = br.choices || {}, cur = br.model;
    if (brTask) return sec("Nova's brain") + group(`<div class="pad"><div class="muted" style="font-size:14px;margin-bottom:8px">${esc(brTask.step || "Downloading…")}</div>${bar((brTask.pct || 0) / 100)}</div>`);
    if (cur) return sec("Nova's brain") + group(`<div class="row"><span class="ri">${I("star")}</span><div class="t"><b>${esc(c[cur]?.name || cur)} · ${br.ready ? "ready" : br.running ? "starting…" : "stopped"}</b><small>${esc(c[cur]?.about || "")} On this server only.</small></div></div>`
        + `<div class="row click" data-act="brainpick" role="button" tabindex="0"><div class="t"><b>Switch brain</b></div></div><div class="row click" data-act="brainrm" role="button" tabindex="0"><div class="t"><b style="color:var(--red)">Remove it</b><small>Frees ${(((c[cur]?.size) || 0) / 1e9).toFixed(1)} GB — the assistant keeps working with its rules</small></div></div>`);
    return sec("Make it smarter") + group(Object.entries(c).map(([k, v]) => `<div class="row click" data-act="brain:${k}" role="button" tabindex="0"><span class="ri">${I(k === "smart" ? "star" : "speed")}</span>
        <div class="t"><b>${esc(v.name)} brain${br.recommended === k ? " · recommended" : ""}</b><small>${esc(v.about)} ${(v.size / 1e9).toFixed(1)} GB download, needs about ${v.ram_gb} GB of memory.</small></div></div>`).join(""))
      + note("A small language model that runs on your server, so the assistant understands what its rules don't and answers questions about your server. It's downloaded once from its makers (checked against a fingerprint of the file), runs on its own without access to your files or the internet, and never sends anything anywhere.");
  };
  const loadBrain = async () => { try { br = await get("/api/v1/assistant/brain"); } catch { br = null; } if (ctx.alive() && !busy) draw(); };
  const planIt = async t => {
    text = t.trim(); if (!text) return;
    busy = true; plan = null; results = {}; choice = {}; summary = ""; err = ""; cur = -1; draw();
    try { plan = await post("/api/v1/assistant/plan", { text }); }
    catch (e) { err = e.message; }
    busy = false; if (ctx.alive()) draw();
  };
  // one step, as this browser would do it by hand
  const runStep = async s => {
    if (s.kind === "ui") { UI[s.pref]?.(s.value); ctx.applyTheme(); return { ok: true, msg: s.done }; }
    if (s.kind === "nav" || s.kind === "partition") return { ok: true, msg: "Opening it at the end", later: true };
    if (!allowed(s.call)) return { ok: false, msg: "Not something the assistant may do" };
    if (s.kind === "freeup") {
      const scan = await get("/api/v1/freeup");
      const items = (scan.items || []).filter(i => i.default).map(i => i.id);
      if (!items.length) return { ok: true, msg: "There was nothing safe to remove" };
      const r = await post("/api/v1/freeup", { items }); const t = r.task ? await waitTask(r.task) : r;
      if (t.state === "failed") return { ok: false, msg: t.error || "It didn't work" };
      const freed = t.result?.freed ?? 0; return { ok: true, msg: s.done.replace("{freed}", bytes(freed)), freed };
    }
    const r = await post(s.call.path, s.call.body ?? undefined);
    if (r?.task) { const t = await waitTask(r.task); if (t.state === "failed") return { ok: false, msg: t.error || "It didn't work" }; }
    if (r?.approval && !r.ok && r.state === "denied") return { ok: false, msg: "Not approved" };
    return { ok: true, msg: s.done.replace("{freed_mb}", r?.freed_mb ?? "some"), freed_mb: r?.freed_mb };
  };
  const getBrain = async k => {
    try { const r = await post("/api/v1/assistant/brain", { model: k }); if (!r?.task) return;
      brTask = { pct: 0, step: "Starting the download…" }; draw();
      const t = await waitTask(r.task, x => { brTask = x; if (ctx.alive()) draw(); });
      brTask = null; toast(t.state === "done" ? "The brain is ready" : t.error || "That didn't work"); await loadBrain();
    } catch (e) { brTask = null; toast(e.message); draw(); }
  };
  ctx.handlers({
    brain: k => getBrain(k),
    brainpick: async () => { const k = await choose("Brain", Object.entries(br?.choices || {}).map(([k, v]) => [k, v.name, v.about]), br?.model); if (k && k !== br?.model) getBrain(k); },
    brainrm: async () => { if (!(await (await import("./ui.js")).confirm("Remove the brain?", "Its model is deleted from the server. The assistant keeps working with its rules, and you can download it again any time.", "Remove"))) return;
      try { await del("/api/v1/assistant/brain"); toast("Removed"); } catch (e) { toast(e.message); } loadBrain(); },
    pick: v => { const [id, k] = v.split(":"); choice[id] = +k; draw(); },
    unpick: id => { delete choice[id]; draw(); },
    clear: () => { plan = null; text = ""; draw(); },
    again: () => { plan = null; summary = ""; text = ""; results = {}; draw(); },
    stop: () => { stop = true; draw(); },
    run: async () => {
      const steps = stepsNow(); if (steps.some(s => s.kind === "ask")) return;
      busy = true; stop = false; draw();
      let freed = 0, ram = 0, okN = 0, bad = 0, later = [];
      for (let i = 0; i < steps.length; i++) {
        if (stop) break;
        cur = i; if (ctx.alive()) draw();
        const s = steps[i];
        let r; try { r = await runStep(s); } catch (e) { r = { ok: false, msg: e.message || "It didn't work" }; }
        results[s.id] = r; if (r.later) later.push(s);
        r.ok ? okN++ : bad++; freed += r.freed || 0; ram += +r.freed_mb || 0;
      }
      cur = -1; busy = false;
      const extra = [freed ? `freed ${bytes(freed)} of storage` : "", ram ? `${ram} MB of RAM` : ""].filter(Boolean).join(" and ");
      const left = steps.length - Object.keys(results).length;
      summary = stop && left ? `Stopped — ${okN} done, ${left} not started.` : bad ? `${okN} done, ${bad} didn't work — see below.` : `All done!${extra ? ` I ${extra.replace(/^freed/, "freed")} while I was at it.` : ""}`;
      if (ctx.alive()) draw(); else toast(summary);
      // pages to open last (so they don't cut the plan short): the Partition manager with the plan waiting, Your own container…
      const go = later[later.length - 1];
      if (go && !stop) {
        if (go.kind === "partition") { stagePartitions(go.serial, go.fs, go.parts, go.after); location.hash = "#/partitions/" + encodeURIComponent(go.serial); }
        else if (ROUTES[go.route]) location.hash = "#/" + ROUTES[go.route] + (go.arg ? "/" + encodeURIComponent(go.arg) : "");
      }
    },
  });
  draw(); loadBrain();
  if (text) planIt(text);
}

/** Does this look like a request rather than a search? (Search offers "Ask Nova" for these.) */
export const looksLikeRequest = q => q.trim().split(/\s+/).length >= 3 && /^(?:hey|please|can you|could you|turn|set|apply|make|free|clean|disable|enable|lower|raise|dim|install|schedule|restart|reboot|stop|start|run|back ?up|open|show|switch|erase|format|wipe|change)\b/i.test(q.trim());
