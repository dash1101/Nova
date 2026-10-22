// Nova web — the assistant (Labs): talk or type in plain words, see the plan, then Nova does it one step at a time.
// The server only plans (POST /api/v1/assistant/plan); every step is an ordinary call this browser signs itself, so the
// usual rules hold: risky steps wait for an admin phone's approval, everything is in the action log. Stop halts before the
// next step (a task the server already started — an install, freeing space — finishes on its own).
// It's one ongoing conversation, kept on the server and shared by every admin phone and browser: what you asked, what Nova
// said and what its steps did, so follow-ups work ("how much did that free?", "actually make it 6pm"). After a few quiet
// hours it starts afresh, but Nova keeps what was done as memory. Voice: the mic records here and the server's own
// speech model turns it into text (nothing goes to an outside service); replies can be read out by this browser.
import { S, $, $$, esc, get, post, del, prefs, waitTask, bytes, signedRaw } from "./core.js";
import { I, group, sec, note, toast, choose, bar, confirm } from "./ui.js";
import { stage as stagePartitions } from "./parts.js";
import { orbHtml, runOrbs, setLevel } from "./orb.js";

let pending = "";                                       // text handed over by Search ("Ask Nova: …") or the assistant button
export const ask = text => { pending = text || ""; location.hash = "#/assistant"; };

// The only calls a plan may make (the server makes plans, but this browser checks before signing anything)
const ALLOWED = [["POST", /^\/api\/v1\/fan$/], ["POST", /^\/api\/v1\/actions\/(free-ram|backup)$/], ["POST", /^\/api\/v1\/store\/[a-z0-9-]{1,40}\/install$/],
  ["POST", /^\/api\/v1\/containers\/[A-Za-z0-9][A-Za-z0-9_.-]{0,62}\/(start|stop|restart)$/], ["POST", /^\/api\/v1\/power\/(schedule|reboot|poweroff)$/], ["POST", /^\/api\/v1\/freeup$/]];
const allowed = c => c && ALLOWED.some(([m, re]) => m === c.method && re.test(c.path));
const ROUTES = { home: "home", status: "status", containers: "containers", store: "store", inbox: "inbox", lighting: "lighting", hardware: "hardware", backups: "backups",
  updates: "updates", settings: "settings", appearance: "appearance", security: "security", devices: "devices", files: "files", terminal: "term", dashboard: "dashboard",
  apps: "apps", freeup: "freeup", recommended: "recommended", power: "quick", "container-new": "container-new" };
const LOOKS = ["liquid", "fluent", "breeze", "aero", "lumen"];
const UI = { theme: v => { prefs.theme = v; }, style: v => { prefs.style = v; }, reduce_motion: v => { prefs.reduceMotion = !!v; },
  accent: v => { prefs.accentLocal = /^#[0-9a-f]{6}$/i.test(v) ? v : ""; }, win_round: v => { prefs.winRound = Math.max(0, Math.min(300, +v || 100)); },
  corner_round: v => { prefs["round_" + (LOOKS.includes(prefs.style) ? prefs.style : "default")] = Math.max(25, Math.min(200, +v || 100)); },
  living: v => { if (v === "off") prefs.livingWall = false; else { prefs.livingWall = true; prefs.livingScene = v || "auto"; } },
  fps: v => { prefs.devFps = !!v; }, solid: v => { prefs.reduceTransparency = !!v; }, speak: v => { prefs.asstSpeak = !!v; } };

/** The orb: a sphere of dots in the accent (orb.js) — idle, think, listen or work. */
export const orb = (state = "idle", size = 96) => orbHtml(state || "idle", size);

// the conversation: the server's (one for everyone, GET /api/v1/assistant/convo), plus the full plan and results of the
// turns asked from this tab: [{id, q, plan, results, choice, summary, done: [what happened]}]
let turns = [];
const local = (() => { try { return JSON.parse(sessionStorage.getItem("nova.asst") || "[]"); } catch { return []; } })();
const save = () => { try { sessionStorage.setItem("nova.asst", JSON.stringify(turns.filter(t => t.plan?.steps?.length || t.results).slice(-6))); } catch {} };
const record = (t, done) => { if (t?.id) post("/api/v1/assistant/convo", { turn: t.id, done }).catch(() => {}); };

// ── speaking: this browser's own voices ──
const speak = t => { try { if (prefs.asstSpeak && t && "speechSynthesis" in window) { speechSynthesis.cancel(); const u = new SpeechSynthesisUtterance(t); u.rate = 1.05; speechSynthesis.speak(u); } } catch {} };

// ── listening: record 16 kHz mono WAV here, the server's speech model reads it ──
let rec = null;
async function listen(onLevel) {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
  const ac = new (window.AudioContext || window.webkitAudioContext)();        // the device's own rate (many browsers can't record at 16 kHz); downsampled after
  const src = ac.createMediaStreamSource(stream), proc = ac.createScriptProcessor(4096, 1, 1), chunks = [];
  let quiet = 0, heard = false, t0 = performance.now(), done;
  const finished = new Promise(r => done = r);
  proc.onaudioprocess = e => {
    const d = e.inputBuffer.getChannelData(0); chunks.push(new Float32Array(d));
    let s = 0; for (let i = 0; i < d.length; i++) s += d[i] * d[i]; const rms = Math.sqrt(s / d.length); onLevel?.(Math.min(1, rms * 8));
    if (rms > .02) { heard = true; quiet = 0; } else quiet += d.length / ac.sampleRate;
    const long = (performance.now() - t0) / 1000;
    if ((heard && quiet > 1.4) || long > 30 || (!heard && long > 8)) stop();      // stops by itself after a pause
  };
  src.connect(proc); proc.connect(ac.destination);
  const stop = () => { if (!rec) return; rec = null; proc.disconnect(); src.disconnect(); stream.getTracks().forEach(t => t.stop()); const r = ac.sampleRate; ac.close(); done(heard ? wav(down(chunks, r), 16000) : null); };
  rec = { stop };
  return finished;
}
/** To 16 kHz (what the speech model reads): each output sample is the average of the input samples it covers. */
function down(chunks, rate) {
  const n = chunks.reduce((a, c) => a + c.length, 0), all = new Float32Array(n); let o = 0; for (const c of chunks) { all.set(c, o); o += c.length; }
  if (rate === 16000) return [all];
  const k = rate / 16000, out = new Float32Array(Math.floor(n / k));
  for (let i = 0; i < out.length; i++) { const a = Math.floor(i * k), b = Math.min(n, Math.floor((i + 1) * k)); let s = 0; for (let j = a; j < b; j++) s += all[j]; out[i] = s / Math.max(1, b - a); }
  return [out];
}
/** (for testing the whole path without a microphone) */
export const encodeWav16 = (samples, rate) => wav(down([samples], rate), 16000);
function wav(chunks, rate) {
  const n = chunks.reduce((a, c) => a + c.length, 0), buf = new ArrayBuffer(44 + n * 2), v = new DataView(buf);
  const w = (o, s) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  w(0, "RIFF"); v.setUint32(4, 36 + n * 2, true); w(8, "WAVE"); w(12, "fmt "); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true); w(36, "data"); v.setUint32(40, n * 2, true);
  let o = 44; for (const c of chunks) for (let i = 0; i < c.length; i++, o += 2) v.setInt16(o, Math.max(-1, Math.min(1, c[i])) * 0x7fff, true);
  return new Uint8Array(buf);
}

export async function assistant(ctx) {
  let text = pending; pending = "";
  const lab = S.cache["/api/v1/labs"] || await get("/api/v1/labs").catch(() => null);
  if (!lab?.labs?.assistant) {
    ctx.show(`<div class="ahead">${orb("idle")}<h2>The assistant is in Labs</h2><p class="muted">Turn on “Nova assistant” in Settings → Labs, then ask Nova in plain words — from here or from Search.</p></div>
      ${group(`<div class="row click" data-act="go:labs" role="button" tabindex="0"><span class="ri">${I("science")}</span><div class="t"><b>Open Labs</b></div></div>`)}`, { title: "Assistant" });
    return;
  }
  let showSet = false, busy = false, stop = false, cur = -1, err = "", listening = false, hearing = false, level = 0;
  // the conversation so far, from the server (this tab's own turns keep their full plans)
  try { const c = await get("/api/v1/assistant/convo");
    turns = (c.turns || []).map(x => local.find(l => l.id === x.id) || { id: x.id, q: x.q, plan: { reply: x.reply, steps: [] }, results: {}, choice: {}, summary: "", done: x.done || [], past: true });
  } catch { turns = local; }
  const now = () => turns[turns.length - 1];
  const risky = s => s.danger ? "danger" : /\/(power|store|freeup)\b|\/(stop|restart)$/.test(s.call?.path || "") ? "stepup" : "";
  const stepsOf = t => (t?.plan?.steps || []).map(s => s.kind === "ask" ? (t.choice?.[s.id] != null ? { ...s.options[t.choice[s.id]].step, id: s.id, asked: s } : s) : s);
  const badge = s => ({ danger: `<span class="abadge red">${I("warn")}Erases data — you'll confirm it</span>`, stepup: `<span class="abadge">${I("key")}Needs approval</span>` })[risky(s)] || "";
  const stIcon = (t, s, i, live) => { const r = t.results?.[s.id];
    if (r?.ok) return `<span class="astate ok">${I("check")}</span>`; if (r && !r.ok) return `<span class="astate bad">${I("err")}</span>`;
    if (live && i === cur) return `<span class="astate run"><span class="spinner"></span></span>`; return `<span class="astate">${i + 1}</span>`; };
  // an earlier turn, folded: what you said, what Nova said, what happened
  const pastHtml = t => `<div class="aturn"><div class="aq">${esc(t.q)}</div>${t.plan?.reply ? `<div class="areply">${esc(t.plan.reply)}</div>` : ""}
    ${(t.done || []).length ? `<div class="adone">${t.done.map(d => `<span>${I(/didn't work|failed/i.test(d) ? "err" : "check")}${esc(d)}</span>`).join("")}</div>` : ""}${t.summary && !(t.done || []).includes(t.summary) ? `<div class="muted asum">${esc(t.summary)}</div>` : ""}</div>`;
  const currentHtml = t => {
    const steps = stepsOf(t), next = steps.find((s, i) => i > cur && !t.results?.[s.id]), waiting = steps.find(s => s.kind === "ask");
    return `<div class="aturn now"><div class="aq">${esc(t.q)}</div>${t.plan?.reply ? `<div class="areply">${esc(t.plan.reply)}</div>`
        : !t.plan ? `<div class="athink" role="status">${orbHtml("think", 30)}<span>Thinking<i>.</i><i>.</i><i>.</i></span></div>` : ""}</div>`
      + (steps.length || (t.plan && !t.plan.reply) ? sec(`Plan · ${steps.length} step${steps.length === 1 ? "" : "s"}`) + group(steps.map((s, i) => `<div class="row astep${busy && i === cur ? " now" : ""}${t.results?.[s.id] ? " did" : ""}">${stIcon(t, s, i, busy)}
          <div class="t"><b>${esc(s.say)}</b>${s.kind === "ask" ? `<small>${esc(s.ask)}</small><div class="achips">${s.options.map((o, k) => `<button class="achip press" data-act="pick:${s.id}:${k}" title="${esc(o.sub || "")}">${esc(o.label)}${o.sub ? `<small>${esc(o.sub)}</small>` : ""}</button>`).join("")}</div>`
            : t.results?.[s.id] ? `<small class="${t.results[s.id].ok ? "" : "bad"}">${esc(t.results[s.id].msg)}</small>` : badge(s)}
            ${s.asked && !busy && !t.results?.[s.id] ? `<button class="alink" data-act="unpick:${s.id}">Change</button>` : ""}</div></div>`).join("") || `<div class="row"><div class="t muted">Nothing I can do from that.</div></div>`) : "")
      + (t.plan?.unknown?.length ? note(`Sorry, I couldn't work out ${t.plan.unknown.map(u => `“${u}”`).join(", ")} — try saying it another way${br?.model ? "" : ", or give me a brain (below) so I understand more"}.`) : "")
      + (t.plan?.notes || []).map(n => note(n)).join("")
      + (busy && t.plan && cur >= 0 ? `<div class="athink work" role="status">${orbHtml("work", 30)}<span>${esc(steps[cur]?.say || "Working")}<i>.</i><i>.</i><i>.</i></span></div>` : "")
      + (busy && next ? `<div class="anext">${I("history")}<span>Next: ${esc(next.say)}</span></div>` : "")
      + (steps.length ? `<div class="aacts">${busy && t.plan ? `<button class="btn ghost" data-act="stop" ${stop ? "disabled" : ""}>${stop ? "Stopping after this step…" : "Stop"}</button>`
          : t.summary ? "" : `<button class="btn ghost" data-act="skip">Not now</button><button class="btn" data-act="run" ${waiting ? "disabled" : ""}>${waiting ? "Answer the question first" : "Do it"}</button>`}</div>` : "")
      + (busy && t.plan ? note("Stop halts before the next step. Something the server has already started (an install, freeing space) finishes on its own.") : "")
      + (t.summary ? `<p class="muted asum" style="text-align:center">${esc(t.summary)}</p>` : "");
  };
  const draw = () => {
    const t = now(), earlier = turns.slice(0, -1).slice(-12);
    ctx.show(`<div class="ahead${t ? " small" : ""}">${orb(listening ? "listen" : hearing || (busy && cur < 0) ? "think" : busy ? "work" : "idle", t ? 56 : 96)}${t ? "" : `<h2>Hey! What can I do?</h2>
        <p class="muted">Say it or type it — several things at once is fine. Ask about your server, or just chat${br?.model ? "" : " (with my brain, below)"}.</p>`}
        ${listening || hearing ? `<h2>${listening ? "Listening…" : "Hearing you…"}</h2>` : ""}</div>
      ${earlier.map(pastHtml).join("")}${t ? (t.past ? pastHtml(t).replace('class="aturn"', 'class="aturn now"') : currentHtml(t)) : ""}
      <form class="abox" id="aform" autocomplete="off"><textarea id="aq" rows="2" maxlength="500" placeholder="${t ? "Reply, or ask something else…" : "e.g. Set the lights to fire, free up some space and restart Jellyfin"}" aria-label="Ask Nova">${esc(text)}</textarea>
        <button class="abtn mic press${listening ? " on" : ""}" type="button" data-act="mic" aria-label="${listening ? "Stop listening" : "Speak"}" style="--lv:${level.toFixed(2)}" ${busy || hearing ? "disabled" : ""}>${I(listening ? "stop" : "mic")}</button>
        <button class="abtn press" id="ago" type="submit" aria-label="Send" ${busy ? "disabled" : ""}>${I("back")}</button></form>
      ${err ? `<p class="note" style="color:var(--red)">${esc(err)}</p>` : ""}
      ${turns.length ? `<div class="alinks"><button class="alink" data-act="speak">${prefs.asstSpeak ? "Stop reading replies aloud" : "Read replies aloud"}</button><button class="alink" data-act="settings">${showSet ? "Hide settings" : "Assistant settings"}</button></div>` : ""}
      ${!turns.length ? note("Runs on your server — nothing is sent anywhere. Risky steps still need approval on your phone, and everything goes in the action log.") : ""}${!turns.length || showSet ? brainHtml() : ""}`, { title: "Assistant" });
    const q = $("#aq"); if (!listening) q?.focus({ preventScroll: true });
    q && (q.onkeydown = e => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); $("#aform").requestSubmit(); } });
    $("#aform").onsubmit = e => { e.preventDefault(); if (!busy) planIt($("#aq").value); };
    if (turns.length) (document.querySelector(".aturn.now") || document.querySelector(".aturn:last-of-type"))?.scrollIntoView({ block: "start", behavior: "smooth" });
    runOrbs();
  };
  // its brain and its voice: models on the server, downloaded once
  let br = S.cache["/api/v1/assistant/brain"] || null, brTask = null;
  const brainHtml = () => {
    if (!br) return "";
    const c = br.choices || {}, cur = br.model, v = br.voice || {};
    if (brTask) return sec(brTask.what) + group(`<div class="pad"><div class="muted" style="font-size:14px;margin-bottom:8px">${esc(brTask.step || "Downloading…")}</div>${bar((brTask.pct || 0) / 100)}</div>`);
    const brain = cur ? sec("Nova's brain") + group(`<div class="row"><span class="ri">${I("star")}</span><div class="t"><b>${esc(c[cur]?.name || cur)} · ${br.ready ? "ready" : br.running ? "starting…" : "stopped"}</b><small>${esc(c[cur]?.about || "")} On this server only.</small></div></div>`
        + `<div class="row click" data-act="brainpick" role="button" tabindex="0"><div class="t"><b>Switch brain</b></div></div><div class="row click" data-act="brainrm" role="button" tabindex="0"><div class="t"><b style="color:var(--red)">Remove it</b><small>Frees ${(((c[cur]?.size) || 0) / 1e9).toFixed(1)} GB — the assistant keeps working with its rules</small></div></div>`)
      : sec("Make it smarter") + group(Object.entries(c).map(([k, x]) => `<div class="row click" data-act="brain:${k}" role="button" tabindex="0"><span class="ri">${I(k === "smart" ? "star" : "speed")}</span>
          <div class="t"><b>${esc(x.name)} brain${br.recommended === k ? " · recommended" : ""}</b><small>${esc(x.about)} ${(x.size / 1e9).toFixed(1)} GB download, needs about ${x.ram_gb} GB of memory.</small></div></div>`).join(""))
        + note("A small language model that runs on your server, so the assistant understands what its rules don't, answers questions and follows a conversation. It's downloaded once from its makers (checked against a fingerprint of the file), runs on its own without access to your files or the internet, and never sends anything anywhere.");
    const voice = sec("Voice") + group(v.installed
        ? `<div class="row"><span class="ri">${I("mic")}</span><div class="t"><b>Voice · ${v.running ? "ready" : "stopped"}</b><small>Speech to text on this server (Whisper). Tap the mic to talk.</small></div></div><div class="row click" data-act="voicerm" role="button" tabindex="0"><div class="t"><b style="color:var(--red)">Remove voice</b><small>Frees 0.2 GB</small></div></div>`
        : `<div class="row click" data-act="voice" role="button" tabindex="0"><span class="ri">${I("mic")}</span><div class="t"><b>Talk to Nova</b><small>Download voice (0.2 GB): what you say is turned into text on your server, never by an outside service.</small></div></div>`);
    return brain + voice + group(`<div class="row click" data-act="forget" role="button" tabindex="0"><span class="ri">${I("history")}</span><div class="t"><b>Forget the conversation</b><small>Nova keeps one conversation for all your devices; after a few quiet hours it starts afresh but remembers what it did. This clears both.</small></div></div><div class="row click" data-act="fab" role="switch" tabindex="0" aria-checked="${prefs.asstButton !== false}"><span class="ri">${I("star")}</span><div class="t"><b>Assistant button</b><small>The orb in the corner of every page, to ask from anywhere</small></div><span class="sw${prefs.asstButton !== false ? " on" : ""}"></span></div>`);
  };
  const loadBrain = async () => { try { br = await get("/api/v1/assistant/brain"); } catch { br = null; } if (ctx.alive() && !busy && !listening) draw(); };
  const planIt = async t => {
    t = t.trim(); if (!t) return;
    text = ""; err = ""; cur = -1; busy = true;
    turns.push({ q: t, plan: null, results: {}, choice: {}, summary: "", done: [] }); draw();
    try { const p = await post("/api/v1/assistant/plan", { text: t }); now().plan = p; now().id = p.turn; }
    catch (e) { err = e.message; turns.pop(); }
    busy = false; save(); if (ctx.alive()) draw();
    const p = now()?.plan; if (p?.reply) speak(p.reply);
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
    return { ok: true, msg: s.done.replace("{freed_mb}", r?.freed_mb ?? "some"), freed_mb: r?.freed_mb };
  };
  const getModel = async (path, body, what) => {
    try { const r = await post(path, body); if (!r?.task) return;
      brTask = { pct: 0, step: "Starting the download…", what }; draw();
      const t = await waitTask(r.task, x => { brTask = { ...x, what }; if (ctx.alive()) draw(); });
      brTask = null; toast(t.state === "done" ? `${what} is ready` : t.error || "That didn't work"); await loadBrain();
    } catch (e) { brTask = null; toast(e.message); draw(); }
  };
  ctx.handlers({
    brain: k => getModel("/api/v1/assistant/brain", { model: k }, "The brain"),
    voice: () => getModel("/api/v1/assistant/voice", {}, "Voice"),
    brainpick: async () => { const k = await choose("Brain", Object.entries(br?.choices || {}).map(([k, v]) => [k, v.name, v.about]), br?.model); if (k && k !== br?.model) getModel("/api/v1/assistant/brain", { model: k }, "The brain"); },
    brainrm: async () => { if (!(await confirm("Remove the brain?", "Its model is deleted from the server. The assistant keeps working with its rules, and you can download it again any time.", "Remove"))) return;
      try { await del("/api/v1/assistant/brain"); toast("Removed"); } catch (e) { toast(e.message); } loadBrain(); },
    voicerm: async () => { if (!(await confirm("Remove voice?", "The speech model is deleted from the server. You can download it again any time.", "Remove"))) return;
      try { await del("/api/v1/assistant/voice"); toast("Removed"); } catch (e) { toast(e.message); } loadBrain(); },
    fab: () => { prefs.asstButton = prefs.asstButton === false; ctx.applyTheme(); draw(); },
    settings: () => { showSet = !showSet; draw(); },
    speak: () => { prefs.asstSpeak = !prefs.asstSpeak; if (!prefs.asstSpeak) try { speechSynthesis.cancel(); } catch {} draw(); },
    forget: async () => { if (!(await confirm("Forget the conversation?", "Nova forgets what you've talked about and what it remembers doing. Nothing on the server changes.", "Forget"))) return;
      try { await del("/api/v1/assistant/convo"); turns = []; save(); toast("Forgotten"); } catch (e) { toast(e.message); } draw(); },
    pick: v => { const [id, k] = v.split(":"); now().choice[id] = +k; save(); draw(); },
    unpick: id => { delete now().choice[id]; save(); draw(); },
    skip: () => { now().summary = "Okay, I'll leave it."; now().done = ["You said not now — nothing was changed"]; record(now(), now().done); save(); draw(); },
    stop: () => { stop = true; draw(); },
    mic: async () => {
      if (listening) { rec?.stop(); return; }
      if (!br?.voice?.installed) { toast("Download voice first (on this page, below), then tap the mic"); return; }
      if (!navigator.mediaDevices?.getUserMedia) { toast("This browser can't use the microphone here"); return; }
      try {
        listening = true; draw();
        let last = 0;
        const w = await listen(l => { level = l; setLevel(l); const b = $(".abtn.mic"); if (b && performance.now() - last > 60) { last = performance.now(); b.style.setProperty("--lv", l.toFixed(2)); } });
        listening = false; level = 0;
        if (!w) { draw(); toast("I didn't hear anything"); return; }
        hearing = true; draw();
        const r = await signedRaw("POST", `/api/v1/assistant/hear?lang=${encodeURIComponent((prefs.lang && prefs.lang !== "auto" ? prefs.lang : navigator.language || "en").slice(0, 2))}`, w);
        hearing = false;
        if (r?.text) planIt(r.text); else { draw(); toast("I couldn't make that out — try again"); }
      } catch (e) { listening = hearing = false; draw(); toast(e.name === "NotAllowedError" ? "Allow the microphone for this page to talk to Nova" : e.message); }
    },
    run: async () => {
      const t = now(), steps = stepsOf(t); if (steps.some(s => s.kind === "ask")) return;
      busy = true; stop = false; draw();
      let freed = 0, ram = 0, okN = 0, bad = 0, later = [];
      for (let i = 0; i < steps.length; i++) {
        if (stop) break;
        cur = i; if (ctx.alive()) draw();
        const s = steps[i];
        let r; try { r = await runStep(s); } catch (e) { r = { ok: false, msg: e.message || "It didn't work" }; }
        t.results[s.id] = r; if (r.later) later.push(s);
        t.done.push(`${s.say}: ${r.ok ? r.msg : "didn't work — " + r.msg}`);
        r.ok ? okN++ : bad++; freed += r.freed || 0; ram += +r.freed_mb || 0; save();
      }
      cur = -1; busy = false;
      const got = [freed ? `${bytes(freed)} of storage` : "", ram ? `${ram} MB of RAM` : ""].filter(Boolean).join(" and "), extra = got ? "freed " + got : "";
      const left = steps.length - Object.keys(t.results).length;
      t.summary = stop && left ? `Stopped — ${okN} done, ${left} not started.` : bad ? `${okN} done, ${bad} didn't work — see above.` : `All done!${extra ? ` I ${extra}.` : ""}`;
      record(t, [...t.done, t.summary]);
      save(); if (ctx.alive()) draw(); else toast(t.summary);
      speak(t.summary);
      const go = later[later.length - 1];
      if (go && !stop) {
        if (go.kind === "partition") { stagePartitions(go.serial, go.fs, go.parts, go.after); location.hash = "#/partitions/" + encodeURIComponent(go.serial); }
        else if (ROUTES[go.route]) location.hash = "#/" + ROUTES[go.route] + (go.arg ? "/" + encodeURIComponent(go.arg) : "");
      }
    },
  });
  draw(); loadBrain();
  if (text) { const t = text; text = ""; planIt(t); }
}

/** Does this look like a request rather than a search? (Search offers "Ask Nova" for these.) */
export const looksLikeRequest = q => q.trim().split(/\s+/).length >= 3 && /^(?:hey|please|can you|could you|turn|set|apply|make|free|clean|disable|enable|lower|raise|dim|install|schedule|restart|reboot|stop|start|run|back ?up|open|show|switch|erase|format|wipe|change|what|why|how|is|are|did)\b/i.test(q.trim());
