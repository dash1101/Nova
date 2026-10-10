// Nova web — storage map, drive setup wizard, pools, background tasks, backups, diagnostics.
// Mirrors the app's Storage.kt / Backups.kt / Diagnostics.kt.
import { S, $, $$, esc, get, post, del, bytes, isAdmin, signedFetch } from "./core.js";
import { I, row, group, sec, note, radio, switchRow, segmented, bar, usageColor, toast, dialog, confirm, wireCommon, sheet, closeSheet, copyCmd } from "./ui.js";

const enc = encodeURIComponent;
const viewOnly = () => toast("This browser has view-only access");

// ── "?" help (same texts as the app) ────────────────────────────────────────────
export const HELP = {
  storage: ["How storage works", "Each drive holds a filesystem, which appears as a folder (its mount point, like /mnt/photos). A pool joins several drives so they look like one folder. Backups copy folders to another drive or a NAS so a failed drive doesn't lose anything."],
  pool: ["What's a pool?", "Several drives that work as one. Nova can make two kinds: a combined pool (drives added together into one big folder) and a RAID array (drives that keep copies or parity, so one can fail without losing anything)."],
  combine: ["One big drive (combined)", "Your drives keep working as separate drives, but Nova shows them as one folder. New files go to the drive with the most free space. You can mix sizes and add drives later. If one drive dies you only lose the files on that drive — so back the pool up."],
  raid0: ["Fastest (RAID 0, stripe)", "Every file is split across all the drives, so reading and writing are faster. But if any one drive fails, everything is lost. Only for scratch space or things you can re-download."],
  raid1: ["Mirror (RAID 1)", "Every drive holds the same copy. Space = one drive. Survives all but one drive failing. Simple and very safe; great for two drives."],
  raid5: ["Parity (RAID 5)", "Spreads data and a checksum (parity) across 3 or more drives. Space = all drives minus one. Survives one drive failing. Rebuilding takes hours and strains the other drives, so keep a backup too."],
  raid6: ["Double parity (RAID 6)", "Like RAID 5 with two drives' worth of parity. Needs 4+ drives; survives any two failing. Good for many large drives."],
  raid10: ["Mirror + stripe (RAID 10)", "Drives in mirrored pairs, striped together. Needs an even number (4+). Half the space, fast, survives one failure per pair."],
  "raid-not-backup": ["RAID isn't a backup", "RAID keeps running when a drive fails. It does not protect against deleting a file by mistake, ransomware, or the whole machine dying. Keep a backup on a separate drive or NAS as well."],
  fs: ["Filesystems", "How files are laid out on the drive.\n\n• ext4 — the dependable Linux default. Pick this if unsure.\n• XFS — great with huge files and very large drives.\n• Btrfs — checksums every file and compresses; good for backup drives.\n• exFAT — also readable by Windows and Mac. No Linux permissions."],
  mount: ["Mount point", "The folder where a drive appears, like /mnt/backup. Apps and containers use this path. Nova adds it to /etc/fstab so it comes back after every restart."],
  policy: ["Where new files go", "• Most free space (recommended) — fills drives evenly.\n• Keep folders together — a new file goes to a drive that already has its folder.\n• Least free space — fills one drive before the next.\n• Random (weighted by free space) — spreads files out."],
  erase: ["Erasing drives", "Setting up a drive deletes everything on it. It can't be undone. Nova never offers the system drive, or drives that are mounted or part of a pool."],
  suggestions: ["Suggestions", "Nova looks at your drives, pools and backups and points out what's worth doing — like drives that aren't used, folders nothing backs up, or pools that are nearly full."],
  backup: ["How Nova backs up", "Each run makes a dated snapshot: a complete copy you can browse like any folder. Files that didn't change are shared with the previous snapshot (hard links), so each run only costs what changed."],
  keep: ["How long backups are kept", "Nova keeps the newest snapshot of each day for N days, of each week for N weeks and of each month for N months — then deletes older ones. The newest three are always kept."],
  guard: ["Missing-drive protection", "If a folder suddenly has far fewer files than last time (what an unplugged drive looks like), Nova doesn't back it up — the previous copy stays safe and you get an alert. If you really deleted the files, run it once with \"Back up anyway\"."],
  nas: ["Backing up to a NAS", "A NAS (Synology, QNAP, TrueNAS, Unraid, or another computer) shares folders over the network.\n\n• SMB — the Windows-style share every NAS offers; needs a user name and password.\n• NFS — the Linux-style share; usually just the address and export path.\n\nNova connects only while a backup runs."],
  restore: ["Restoring", "• Put it back where it was — copies it back; files already there are left alone, so nothing newer is overwritten.\n• Next to the original — puts it in a 'restored-<date>' folder beside it, to compare first."],
  net: ["Internet speed", "Measured from the server to Cloudflare's speed-test network (or another public test server if that's busy). Ping is the round trip to 1.1.1.1; jitter is how much it varies."],
  "device-net": ["This browser ↔ server", "Measures the link between this computer and the server — your network plus the route (home network or remote)."],
  disk: ["Drive speed", "Writes a temporary file (deleted afterwards) and measures sequential speed (big files, MB/s) and random 4K (tiny reads/writes, IOPS).\n\nTypical: HDD ~150 MB/s & ~100 IOPS, SATA SSD ~500 MB/s & tens of thousands, NVMe several GB/s."],
  cpu: ["CPU stress test", "Runs every core flat out to see how hot the CPU gets, whether the clock stays up, and how much power it draws. Most desktop CPUs are fine up to about 90–95 °C. Nova stops at 97 °C."],
  mem: ["Memory test", "Fills part of the free memory with patterns and reads them back to find bad RAM. Your apps keep running. For a full check, boot memtest86+ from a USB stick."],
  tools: ["Quick tools", "• Ping — is a host reachable, and how fast?\n• Traceroute — the hops between the server and a host.\n• DNS — what a name resolves to.\n• Port — can the server open a connection to host:port?"],
};
export const helpBtn = t => `<button class="help press" data-act="help:${t}" aria-label="Help">?</button>`;
export const secHelp = (t, topic) => `<div class="sec" style="display:flex;align-items:center;gap:8px"><span style="flex:1">${esc(t)}</span>${helpBtn(topic)}</div>`;
export function showHelp(t) { const h = HELP[t]; if (h) dialog(h[0], "", [{ label: "Got it", color: "var(--blue)" }], `<p style="white-space:pre-line;margin:0 26px">${esc(h[1])}</p>`); }

export const poolKind = t => ({ combine: "Combined drives", raid0: "Stripe (RAID 0)", raid1: "Mirror (RAID 1)", raid5: "Parity (RAID 5)", raid6: "Double parity (RAID 6)", raid10: "Mirror + stripe (RAID 10)" }[t] || String(t).toUpperCase());
const driveTitle = d => `${d.size_text} ${d.model || d.name}`.trim();
const ago = t => { if (!t) return "never"; const d = Date.now() / 1000 - t;
  return d < 0 ? new Date(t * 1000).toLocaleString(undefined, { weekday: "short", hour: "2-digit", minute: "2-digit" }) : d < 90 ? "just now" : d < 3600 ? `${Math.round(d / 60)} min ago` : d < 86400 ? `${Math.round(d / 3600)} h ago`
    : new Date(t * 1000).toLocaleString(undefined, d < 7 * 86400 ? { weekday: "short", hour: "2-digit", minute: "2-digit" } : { day: "numeric", month: "short" }); };
const f1 = x => x == null || isNaN(x) ? "—" : x >= 100 ? Math.round(x) : (+x).toFixed(1);
const lvColor = l => l === "critical" ? "var(--red)" : l === "warning" ? "var(--amber)" : l === "ok" ? "var(--green)" : "var(--blue)";
const chip = (label, act, on) => `<button class="chip" style="font-family:inherit;font-size:14px;white-space:nowrap;flex:none;${on ? "background:var(--blue);color:#fff" : ""}" data-act="${act}">${esc(label)}</button>`;

async function startTask(ctx, path, body) {
  try {
    const t = await post(path, body);
    if (t?.id) ctx.go("task/" + t.id); else if (t?.error) toast(t.error);
  } catch (e) { toast(e.message); }
}

// ── the top of Storage & hardware ──────────────────────────────────────────────
export function overviewHtml() {
  const st = S.cache["/api/v1/storage"], tasks = (S.cache["/api/v1/tasks"]?.tasks || []).filter(t => t.state === "running");
  const sug = st?.suggestions || [], pools = st?.pools || [], legacy = st?.legacy_backup, nj = (st?.jobs || []).length;
  return `${tasks.length ? sec("Working on it") + group(tasks.map(taskRow).join("")) : ""}
    ${sug.length ? secHelp("Suggested", "suggestions") + sug.map((s, i) => group(`<div class="row" style="align-items:flex-start"><span class="ic" style="background:color-mix(in srgb,${lvColor(s.level)} 16%,transparent);color:${lvColor(s.level)}">${I(s.level === "info" ? "bulb" : "warn")}</span>
        <div class="t"><b>${esc(s.title)}</b><small>${esc(s.detail)}</small>${s.actions?.length ? `<div style="display:flex;flex-wrap:wrap;gap:8px;margin-top:10px">${s.actions.map((a, k) => `<button class="pillbtn press" data-act="sug:${i}:${k}">${esc(a.label)}</button>`).join("")}</div>` : ""}</div></div>`)).join("") : ""}
    ${secHelp("Pools", "pool")}${group(pools.map((p, i) => poolRow(p, i)).join("") + row("Set up drives", { sub: "Combine drives, make a RAID, or a backup drive — a step-by-step guide", blue: true, icon: "addc", tint: "var(--green)", click: "go:setup" }))}
    ${group(row("Backups", { sub: [legacy ? "your backup script" : "", nj ? `${nj} Nova backup${nj > 1 ? "s" : ""}` : ""].filter(Boolean).join(" + ") || "Nothing backed up yet — set one up", blue: true, icon: "backup", click: "go:backups" })
      + row("Diagnostics", { sub: "Internet & drive speed, CPU and memory tests, ping and more", icon: "speed", tint: "#64d2ff", click: "go:diag" }))}`;
}
const taskRow = t => `<div class="row click" data-act="go:task/${t.id}" style="display:block"><div style="display:flex"><b style="flex:1;font-weight:400">${esc(t.title)}</b><span style="color:var(--link)">${Math.round(t.pct || 0)}%</span></div>
  <div style="margin-top:8px">${bar((t.pct || 0) / 100)}</div><small class="muted">${esc(t.note || t.step || "")}</small></div>`;
function poolRow(p, i) {
  const u = p.usage, f = u ? u.used / Math.max(1, u.total) : 0;
  const state = p.degraded ? ["Missing a drive", "var(--red)"] : p.sync != null ? [`Building ${Math.round(p.sync)}%`, "var(--amber)"] : [p.redundancy > 0 ? "Protected" : "OK", "var(--green)"];
  return `<div class="row click" data-act="pool:${i}" style="display:block"><div style="display:flex;align-items:center"><div class="t"><b>${esc(p.name)}</b><small>${esc(poolKind(p.type))} · ${p.members.length} drives · ${esc(p.mount)}</small></div><b style="color:${state[1]};font-size:14px">${state[0]}</b></div>
    ${u ? `<div style="margin-top:8px">${bar(f, usageColor(f))}</div><small class="muted">${bytes(u.free)} free of ${bytes(u.total)}${p.used_by?.length ? ` · used by ${esc(p.used_by.slice(0, 3).join(", "))}` : ""}</small>` : ""}</div>`;
}
export function overviewHandlers(ctx) {
  return {
    pool: i => { const p = S.cache["/api/v1/storage"]?.pools?.[+i]; if (p) ctx.go("pool/" + enc(p.id)); },
    sug: (i, k) => {
      const a = S.cache["/api/v1/storage"]?.suggestions?.[+i]?.actions?.[+k]; if (!a) return;
      if (!isAdmin()) return viewOnly();
      if (a.action === "wizard") ctx.go(`setup/${enc(a.goal)}/${enc((a.drives || []).join(","))}/${enc(a.pool || "")}`);
      if (a.action === "backup-wizard") ctx.go(`backup-edit/new/${enc((a.sources || []).join(","))}`);
      if (a.action === "task") startTask(ctx, `/api/v1/storage/task/${a.kind}`, { spec: a.spec || {} });
    },
  };
}
export async function refreshOverview() { await Promise.all([get("/api/v1/storage").catch(() => {}), get("/api/v1/tasks").catch(() => {})]); }

// ── one pool ───────────────────────────────────────────────────────────────────
export async function pool(ctx) {
  const id = ctx.args[0]; let erase = false;
  const draw = () => {
    const st = S.cache["/api/v1/storage"], p = st?.pools?.find(x => x.id === id);
    if (!p) return ctx.show(note(st ? "This pool is gone." : "Loading…"), { title: "Pool" });
    const combine = p.type === "combine", u = p.usage, red = p.redundancy;
    const members = p.members.map(m => { const d = st.drives.find(x => x.mounts.includes(m) || x.partitions.some(pt => pt.name === m));
      const pu = d?.partitions.find(pt => pt.mounts.includes(m) || pt.name === m)?.usage;
      return row(d ? driveTitle(d) : m, { sub: [m, pu ? `${bytes(pu.free)} free` : "", (p.failed || []).includes(m) ? "FAILED" : ""].filter(Boolean).join(" · "), icon: d?.ssd ? "ssd" : "disk", click: d ? `go:hardware/${enc(d.serial)}` : "" }); }).join("");
    ctx.show(`<div class="center" style="padding:14px 0"><div style="color:var(--blue)">${I(combine ? "viewday" : "shield").replace('class="i ', 'style="width:72px;height:72px" class="i ')}</div>
        <div style="display:flex;gap:8px;justify-content:center;align-items:center"><b style="font-size:18px">${esc(poolKind(p.type))}</b>${helpBtn(combine ? "combine" : p.type)}</div><div class="muted">${esc(p.mount || "Not mounted")}</div></div>
      ${u ? group(`<div style="padding:20px">${bar(u.used / Math.max(1, u.total), usageColor(u.used / Math.max(1, u.total)))}<small class="muted">${bytes(u.used)} used · ${bytes(u.free)} free of ${bytes(u.total)}</small></div>`) : ""}
      ${sec("Safety")}${group(row(p.degraded ? "Missing a drive — replace it now" : red === 0 ? (combine ? "If a drive fails, its files are lost" : "If any drive fails, everything is lost") : `Survives ${red} drive${red > 1 ? "s" : ""} failing`,
        { sub: p.sync != null ? `Building redundancy: ${Math.round(p.sync)}% (usable meanwhile)` : "RAID and pools aren't backups — keep a copy elsewhere too", blue: red > 0 && !p.degraded, icon: red > 0 ? "shield" : "warn", tint: red > 0 ? "var(--green)" : "var(--amber)", end: helpBtn("raid-not-backup") })
        + (p.used_by?.length ? row("Used by", { sub: p.used_by.join(", "), icon: "apps" }) : ""))}
      ${sec("Drives in it")}${group(members)}
      ${isAdmin() ? group(row(combine ? "Add a drive" : "Add or replace a drive", { sub: combine ? "Makes the pool bigger" : "A spare, or a replacement for a failed drive", blue: true, icon: "addc", tint: "var(--green)", click: "grow" })
        + row("Remove this pool", { sub: combine ? "The drives keep their files and stay mounted on their own" : "Stops the array (you can also erase its drives)", icon: "del", tint: "var(--red)", click: "remove" })) : ""}`,
      { title: p.name });
  };
  ctx.handlers({
    grow: () => ctx.go(`setup/grow//${enc(id)}`),
    remove: async () => {
      const p = S.cache["/api/v1/storage"]?.pools?.find(x => x.id === id); if (!p) return;
      const combine = p.type === "combine";
      const v = await dialog(`Remove ${p.name}?`, combine ? `The combined folder ${p.mount} goes away. Each drive keeps its files and stays mounted on its own. Apps using it must be stopped first.` : `The array is stopped and ${p.mount} unmounted. Apps using it must be stopped first.`,
        [{ label: "Cancel", value: null }, { label: "Remove", color: "var(--red)", value: "rm" }], combine ? "" : `<label style="display:flex;gap:10px;margin:0 26px;align-items:center"><input type="checkbox" id="er"> Also erase the drives</label>`);
      if (v !== "rm") return;
      startTask(ctx, "/api/v1/storage/task/pool-remove", { spec: { pool: id, confirm: [id], erase } });
    },
  });
  document.addEventListener("change", e => { if (e.target.id === "er") erase = e.target.checked; });
  draw(); ctx.every(10000, async () => { await get("/api/v1/storage"); draw(); }, true);
}

// ── the drive setup wizard ─────────────────────────────────────────────────────
const GOALS = [["combine", "One big drive", "Add drives together into one large folder. Mix any sizes, add more later.", "viewday", "combine"],
  ["safe", "Safe if a drive fails", "Mirror or parity (RAID) — keeps working when a drive dies.", "shield", "raid1"],
  ["backup", "A backup drive", "One drive just for backups of your other folders.", "backup", "backup"],
  ["single", "Just one drive", "Set up a drive as a normal folder.", "disk", "mount"],
  ["fast", "As fast as possible", "Stripe drives together (RAID 0). No protection at all.", "speed", "raid0"],
  ["grow", "Add to a pool", "Make a pool bigger, or replace a failed drive in an array.", "addc", "pool"]];
const usable = (lv, s) => { if (!s.length) return 0; const mn = Math.min(...s), n = s.length;
  return { combine: s.reduce((a, b) => a + b, 0), raid0: mn * n, raid1: mn, raid5: mn * (n - 1), raid6: mn * (n - 2), raid10: mn * Math.floor(n / 2) }[lv] ?? s[0]; };
const survives = (lv, n) => ({ raid1: n - 1, raid5: 1, raid10: 1, raid6: 2 }[lv] || 0);
export async function setup(ctx) {
  const [g0, d0, p0] = ctx.args;
  const v = { goal: g0 || null, step: g0 ? 1 : 0, picked: new Set((d0 || "").split(",").filter(Boolean)), keep: new Set(), level: "raid1", pool: p0 || null, name: "", fs: "ext4", policy: "mfs", typed: "", busy: false };
  const draw = () => {
    const st = S.cache["/api/v1/storage"]; if (!st) return ctx.show(note("Looking at your drives…"), { title: "Set up drives" });
    const drives = st.drives, pools = st.pools, free = drives.filter(d => d.erasable), data = drives.filter(d => d.roles.join() === "data" && d.mounts.length);
    const poolObj = pools.find(p => p.id === v.pool), g = GOALS.find(x => x[0] === v.goal);
    const eff = { combine: "combine", fast: "raid0", safe: v.level, grow: poolObj?.type === "combine" ? "combine" : "raid1" }[v.goal] || "single";
    const chosen = drives.filter(d => v.picked.has(d.serial)), kept = drives.filter(d => v.keep.has(d.serial)), n = chosen.length + kept.length;
    const one = ["single", "backup", "grow"].includes(v.goal);
    if (!v.name) { const base = { backup: "backup", single: "storage", combine: "pool", fast: "scratch" }[v.goal] || "array";
      const taken = new Set([...drives.flatMap(d => d.mounts), ...pools.map(p => p.mount)]); for (let i = 1; ; i++) { const nm = i === 1 ? base : base + i; if (!taken.has("/mnt/" + nm)) { v.name = nm; break; } } }
    const steps = v.goal === "grow" ? ["Goal", "Pool & drive", "Confirm"] : ["Goal", "Drives", "Details", "Confirm"], cur = steps[v.step];
    const okNext = { Goal: !!v.goal, Drives: { single: chosen.length === 1, backup: chosen.length === 1, combine: n >= 2, fast: chosen.length >= 2 }[v.goal] ?? (chosen.length >= { raid1: 2, raid5: 3, raid6: 4, raid10: 4 }[v.level] && (v.level !== "raid10" || chosen.length % 2 === 0)),
      "Pool & drive": !!poolObj && (chosen.length === 1 || (kept.length === 1 && eff === "combine")), Details: /^[a-z0-9][a-z0-9_-]{0,23}$/.test(v.name), Confirm: !chosen.length || v.typed.trim().toUpperCase() === "ERASE" }[cur] && !v.busy;
    const driveRow = (d, on, act, keepMode) => row(driveTitle(d), { sub: keepMode ? `${d.mounts.join(", ")} · kept as it is, nothing erased` : [d.ssd ? "SSD" : "HDD", (d.bus || "").toUpperCase(), d.has_data ? "has old files — will be erased" : "empty"].filter(Boolean).join(" · ") + (d.warnings[0] ? ` · ⚠ ${d.warnings[0]}` : ""),
      blue: on, icon: keepMode ? "list" : d.ssd ? "ssd" : "disk", tint: keepMode ? "var(--green)" : on ? "var(--red)" : "var(--blue)", end: radio(on), click: act });
    let body = "";
    if (cur === "Goal") body = `<p style="font-size:18px;font-weight:600;margin:8px 22px">What would you like?</p>` + GOALS.map(([id, t, s, ic, h]) =>
        group(row(t, { sub: id === "grow" && !pools.length ? "No pools yet" : s, blue: v.goal === id, icon: ic, tint: id === "fast" ? "var(--amber)" : "var(--blue)", click: id === "grow" && !pools.length ? "" : `goal:${id}`, end: helpBtn(h), dis: id === "grow" && !pools.length }))).join("")
      + note("Nova only offers drives that are safe to set up: never the system drive, or anything that's mounted or already in a pool.");
    if (cur === "Drives") body = (v.goal === "safe" ? secHelp("Kind of protection", v.level) + group([["raid1", "Mirror — 2+ drives, same copy on each"], ["raid5", "Parity — 3+ drives, lose one drive's space"], ["raid6", "Double parity — 4+ drives, survives two failing"], ["raid10", "Mirror + stripe — 4, 6, 8… drives, fast"]]
        .map(([k, l]) => row(poolKind(k), { sub: l, blue: v.level === k, end: radio(v.level === k), click: `lvl:${k}` })).join("")) : "")
      + secHelp(one ? "Pick a drive" : "Pick the drives", "erase") + group(free.map(d => driveRow(d, v.picked.has(d.serial), `pick:${enc(d.serial)}`)).join("") || row("No free drives", { sub: "Plug one in, or unmount a drive you no longer use.", dis: true }))
      + (v.goal === "combine" && data.length ? secHelp("Or add drives with their files", "combine") + group(data.map(d => driveRow(d, v.keep.has(d.serial), `keep:${enc(d.serial)}`, true)).join("")) : "")
      + (n && !one ? capacity(eff, [...chosen, ...kept]) : "");
    if (cur === "Pool & drive") body = sec("Which pool") + group(pools.map((p, i) => row(p.name, { sub: `${poolKind(p.type)} · ${p.mount}`, blue: v.pool === p.id, end: radio(v.pool === p.id), click: `pool:${i}` })).join(""))
      + (poolObj ? secHelp(eff === "combine" ? "Drive to add" : "Spare or replacement drive", "erase") + group(free.map(d => driveRow(d, v.picked.has(d.serial), `pick1:${enc(d.serial)}`)).join("")
          + (eff === "combine" ? data.map(d => driveRow(d, v.keep.has(d.serial), `keep1:${enc(d.serial)}`, true)).join("") : "") || row("No free drives", { sub: "Plug one in first.", dis: true })) : "");
    if (cur === "Details") body = secHelp("Name", "mount") + group(`<div style="padding:16px"><input class="field" id="nm" maxlength="24" value="${esc(v.name)}" placeholder="e.g. photos"><small class="muted" id="nmh" style="display:block;margin:8px 6px 0">Appears as /mnt/${esc(v.name || "…")}</small></div>`)
      + secHelp("Filesystem", "fs") + group([["ext4", "ext4", "Recommended — reliable, works everywhere"], ["xfs", "XFS", "Very large drives and big files"], ["btrfs", "Btrfs", "Checksums + compression — nice for backups"], ...(one ? [["exfat", "exFAT", "Also opens on Windows and Mac"]] : [])]
        .map(([k, l, s]) => row(l, { sub: s, blue: v.fs === k, end: radio(v.fs === k), click: `fs:${k}` })).join(""))
      + (v.goal === "combine" ? secHelp("Where new files go", "policy") + group([["mfs", "Most free space (recommended)"], ["epmfs", "Keep folders together"], ["lfs", "Fill one drive at a time"], ["pfrd", "Random, weighted by free space"]].map(([k, l]) => row(l, { blue: v.policy === k, end: radio(v.policy === k), click: `pol:${k}` })).join("")) : "");
    if (cur === "Confirm") body = sec("Summary") + group(row(g?.[1] || "", { sub: v.goal === "grow" ? `Into ${poolObj?.name}` : v.goal === "safe" ? poolKind(eff) : "", blue: true, icon: g?.[3] })
        + (v.goal !== "grow" ? row("Folder", { sub: `/mnt/${v.name} · ${v.fs === "exfat" ? "exFAT" : v.fs}` }) : "")
        + (!one ? row("Space", { sub: `${bytes(usable(eff, [...chosen, ...kept].map(d => d.size)))} usable · ${survives(eff, n) ? `survives ${survives(eff, n)} failing` : "no drive may fail"}` }) : "")
        + (kept.length ? row("Kept with their files", { sub: kept.map(driveTitle).join(", "), icon: "list", tint: "var(--green)" }) : ""))
      + (chosen.length ? `<div class="group" style="background:color-mix(in srgb,var(--red) 12%,transparent);border:1px solid color-mix(in srgb,var(--red) 50%,transparent);padding:20px">
          <div style="display:flex;align-items:center;gap:10px;color:var(--red)">${I("warn")}<b style="flex:1;font-size:17px">These drives will be erased</b>${helpBtn("erase")}</div>
          ${chosen.map(d => `<div style="margin-top:6px">• ${esc(driveTitle(d))} (${esc(d.serial)})${d.has_data ? " — has files on it" : ""}</div>`).join("")}
          <p class="muted" style="margin:12px 0 8px">Everything on them is deleted for good. Type ERASE to continue.</p><input class="field" id="erase" placeholder="ERASE" value="${esc(v.typed)}" autocomplete="off"></div>` : "");
    const dots = `<div style="display:flex;gap:6px;margin:6px 22px">${steps.map((_, i) => `<i style="flex:1;height:6px;border-radius:3px;background:${i <= v.step ? "var(--blue)" : "var(--divider)"}"></i>`).join("")}</div><small class="muted" style="margin:0 22px">Step ${v.step + 1} of ${steps.length} · ${cur}</small>`;
    const back = v.step > 0 && (!g0 || v.step > 1);
    ctx.show(dots + body + `<div style="display:flex;gap:12px;padding:18px 22px"><button class="btn" style="flex:1;background:color-mix(in srgb,var(--text) 8%,transparent);color:var(--text)" data-act="${back ? "prev" : "back"}">${back ? "Back" : "Cancel"}</button>
      <button class="btn" style="flex:1${chosen.length && cur === "Confirm" ? ";background:var(--red)" : ""}" data-act="next" ${okNext ? "" : "disabled"}>${v.step < steps.length - 1 ? "Next" : chosen.length ? "Erase and set up" : "Set up"}</button></div>`, { title: v.step === 0 ? "Set up drives" : g?.[1] || "Set up drives" });
    $("#nm") && ($("#nm").oninput = e => { v.name = e.target.value.toLowerCase().replace(/[^a-z0-9_-]/g, "").slice(0, 24); e.target.value = v.name; $("#nmh").textContent = `Appears as /mnt/${v.name || "…"}`; $('[data-act="next"]').disabled = !/^[a-z0-9][a-z0-9_-]{0,23}$/.test(v.name); });
    $("#erase") && ($("#erase").oninput = e => { v.typed = e.target.value; $('[data-act="next"]').disabled = v.typed.trim().toUpperCase() !== "ERASE"; });
    v._steps = steps; v._eff = eff; v._chosen = chosen;
  };
  const submit = async () => {
    const eff = v._eff, picked = [...v.picked];
    const [kind, spec] = { single: ["format", { name: v.name, fs: v.fs }], backup: ["format", { name: v.name, fs: v.fs }], combine: ["combine", { name: v.name, fs: v.fs, policy: v.policy, keep: [...v.keep] }],
      grow: ["pool-add", { pool: v.pool, ...(v.keep.size ? { keep: [...v.keep][0] } : {}) }] }[v.goal] || ["raid", { name: v.name, fs: v.fs, level: eff }];
    Object.assign(spec, { drives: picked, confirm: picked });
    v.busy = true; draw();
    try { const t = await post(`/api/v1/storage/task/${kind}`, { spec }); if (t?.id) { history.back(); setTimeout(() => ctx.go("task/" + t.id + (v.goal === "backup" ? "/" + enc("/mnt/" + v.name) : "")), 50); } else toast(t?.error || "Couldn't start"); }
    catch (e) { toast(e.message); } v.busy = false; if (ctx.alive()) draw();
  };
  ctx.handlers({
    goal: id => { if (!isAdmin()) return viewOnly(); v.goal = id; v.picked.clear(); v.keep.clear(); v.name = ""; v.step = 1; draw(); },
    lvl: k => { v.level = k; draw(); },
    pick: s => { s = decodeURIComponent(s); const one = ["single", "backup"].includes(v.goal); if (v.picked.has(s)) v.picked.delete(s); else { if (one) v.picked.clear(); v.picked.add(s); v.keep.delete(s); } draw(); },
    keep: s => { s = decodeURIComponent(s); v.keep.has(s) ? v.keep.delete(s) : v.keep.add(s); v.picked.delete(s); draw(); },
    pick1: s => { s = decodeURIComponent(s); const on = v.picked.has(s); v.picked.clear(); v.keep.clear(); if (!on) v.picked.add(s); draw(); },
    keep1: s => { s = decodeURIComponent(s); const on = v.keep.has(s); v.picked.clear(); v.keep.clear(); if (!on) v.keep.add(s); draw(); },
    pool: i => { v.pool = S.cache["/api/v1/storage"].pools[+i].id; v.picked.clear(); v.keep.clear(); draw(); },
    fs: k => { v.fs = k; draw(); }, pol: k => { v.policy = k; draw(); },
    prev: () => { v.step--; draw(); },
    next: () => { if (v.step < v._steps.length - 1) { v.step++; draw(); } else submit(); },
  });
  draw(); try { await get("/api/v1/storage"); if (ctx.alive()) draw(); } catch (e) { toast(e.message); }
}
function capacity(lv, ds) {
  const s = ds.map(d => d.size), use = usable(lv, s), tot = s.reduce((a, b) => a + b, 0), sv = survives(lv, s.length);
  return group(`<div style="padding:20px"><b style="font-size:22px">${bytes(use)} usable</b><small class="muted" style="display:block">from ${s.length} drives, ${bytes(tot)} in total</small>
    <div style="margin:10px 0">${bar(use / Math.max(1, tot), "var(--green)")}</div>
    <div style="color:${sv ? "var(--green)" : "var(--amber)"}">${lv === "combine" ? "If a drive fails, only the files on it are lost (the rest keep working). Back it up." : sv ? `Keeps working if ${sv} drive${sv > 1 ? "s" : ""} fail${sv === 1 ? "s" : ""}.` : "If any one drive fails, everything on it is lost."}</div>
    ${lv !== "combine" && new Set(s).size > 1 ? `<small style="color:var(--amber);display:block;margin-top:6px">Drives differ in size: RAID uses only ${bytes(Math.min(...s))} of each (the smallest).</small>` : ""}
    ${lv !== "combine" && ds.some(d => d.bus === "usb") ? `<small style="color:var(--amber);display:block;margin-top:6px">A USB drive can drop out of an array — SATA is safer.</small>` : ""}</div>`);
}

// ── one background task ────────────────────────────────────────────────────────
export async function task(ctx) {
  const [id, then] = ctx.args; let t = null, stopping = false;
  const draw = () => {
    const state = t?.state || "running", kind = t?.kind || "";
    const icon = state === "running" ? `<div class="spinner"></div>` : state === "done" ? `<span style="color:var(--green)">${I("okc")}</span>` : `<span style="color:${state === "stopped" ? "var(--sub)" : "var(--red)"}">${I(t?.refused ? "block" : "err")}</span>`;
    ctx.show(group(`<div style="padding:22px"><div style="display:flex;align-items:center;gap:14px">${icon}<b style="font-size:18px">${esc(state === "running" ? (t?.step || "Starting…") : state === "done" ? "Done" : state === "stopped" ? "Stopped" : t?.refused ? "Not started" : "Didn't finish")}</b></div>
        ${state === "running" ? `<div style="margin-top:14px">${bar((t?.pct || 0) / 100)}</div>${t?.note ? `<small class="muted">${esc(t.note)}</small>` : ""}` : ""}
        ${t?.error ? `<p style="color:${state === "stopped" ? "var(--sub)" : "var(--red)"};margin:12px 0 0">${esc(t.error)}</p>` : ""}</div>`)
      + (t?.samples?.length > 1 ? `<div class="group glass" style="padding:18px"><div id="leg" style="display:flex;gap:16px"></div><canvas id="chart" style="width:100%;height:140px;margin-top:10px"></canvas></div>` : "")
      + (state === "done" && t?.result ? resultHtml(kind, t.result) : "")
      + (kind === "backup-run" && state === "failed" && /down from/.test(t?.error || "") && isAdmin() ? group(row("I deleted those files on purpose", { sub: "Back up anyway — just this once", blue: true, icon: "backup", tint: "var(--amber)", click: "force" })) : "")
      + (state === "done" && then ? group(row("Now choose what to back up", { sub: `Your new drive is ready at ${then}`, blue: true, icon: "backup", click: "nextbackup" })) : "")
      + (t?.log?.length ? sec(kind === "run-command" ? "Output" : "What happened") + group(`<pre class="${kind === "run-command" ? "cmdout" : ""}" style="padding:18px;margin:0;white-space:pre-wrap;font-size:13px;color:var(--sub)">${esc((kind === "run-command" ? t.log.slice(-200).map(l => l.replace(/^\d\d:\d\d:\d\d /, "")) : t.log.slice(-40)).join("\n"))}</pre>`)
          + (kind === "run-command" && state !== "running" ? group(row("Run it again", { icon: "play", blue: true, click: "again" })) : "") : "")
      + (state === "running" && !["format", "combine", "raid", "pool-remove", "pool-add"].includes(kind) && isAdmin() ? `<div style="padding:18px 22px"><button class="btn" style="width:100%;background:var(--card);color:var(--text)" data-act="stop" ${stopping ? "disabled" : ""}>${stopping ? "Stopping…" : "Stop"}</button></div>` : "")
      + (state === "running" ? note("You can leave this page — it keeps going on the server, and shows under Storage & hardware.") : ""), { title: t?.title || "Working…" });
    if (t?.samples?.length > 1) chart($("#chart"), $("#leg"), t.samples);
  };
  ctx.handlers({
    stop: async () => { stopping = true; draw(); try { await post(`/api/v1/tasks/${id}/stop`); } catch (e) { toast(e.message); } },
    force: () => startTask(ctx, `/api/v1/backups/${t.key}/run`, { force: true }),
    nextbackup: () => ctx.go(`backup-edit/new//${enc(then)}`),
    again: async () => { try { const r = await post(`/api/v1/apps/${enc(t.key)}/run`); if (r?.task) ctx.go("task/" + r.task); } catch (e) { toast(e.message); } },
  });
  draw();
  ctx.every(1000, async () => { if (t && t.state !== "running") return; t = await get(`/api/v1/tasks/${id}`); draw(); }, true);
}
function chart(cv, leg, samples) {
  const series = [["temp", "°C", "#ff453a"], ["mhz", "MHz", "#34c759"], ["watts", "W", "#3e91ff"]].filter(([k]) => samples.some(s => s[k] != null));
  leg.innerHTML = series.map(([k, u, c]) => { const v = [...samples].reverse().find(s => s[k] != null)?.[k]; return `<span style="display:flex;align-items:center;gap:6px"><i style="width:10px;height:10px;border-radius:5px;background:${c}"></i><b>${v == null ? "—" : k === "mhz" ? Math.round(v) : f1(v)} ${u}</b></span>`; }).join("");
  const r = devicePixelRatio || 1, w = cv.clientWidth, h = cv.clientHeight; cv.width = w * r; cv.height = h * r;
  const g = cv.getContext("2d"); g.scale(r, r); g.lineWidth = 2.5; g.lineCap = "round";
  for (const [k, , c] of series) {
    const vals = samples.map(s => s[k]), ok = vals.filter(x => x != null); if (ok.length < 2) continue;
    const lo = k === "temp" ? Math.min(30, ...ok) : 0, hi = Math.max(...ok, lo + 1) * 1.05;
    g.strokeStyle = c; g.beginPath(); let on = false;
    vals.forEach((x, i) => { if (x == null) return; const X = w * i / Math.max(1, vals.length - 1), Y = h * (1 - (x - lo) / (hi - lo)); on ? g.lineTo(X, Y) : g.moveTo(X, Y); on = true; });
    g.stroke();
  }
}
const big = (v, u, l, c = "var(--text)") => `<div style="text-align:center"><b style="font-size:32px;color:${c}">${v}</b><span class="muted"> ${u}</span><small class="muted" style="display:block">${l}</small></div>`;
const bigs = (...b) => `<div style="display:flex;justify-content:space-evenly;padding:16px 0 6px">${b.join("")}</div>`;
function resultHtml(kind, r) {
  if (kind === "net-internet") return group(bigs(big(f1(r.download_mbps), "Mbps", "↓ Download", "var(--green)"), big(f1(r.upload_mbps), "Mbps", "↑ Upload", "var(--blue)"))
    + bigs(big(f1(r.ping_ms), "ms", "Ping"), big(f1(r.jitter_ms), "ms", "Jitter"), big(f1(r.loss_pct), "%", "Loss", r.loss_pct > 1 ? "var(--amber)" : "var(--text)"))
    + `<small class="muted" style="display:block;padding:0 22px 14px">Server: ${esc(r.server || "")}${r.download_from ? ` · download from ${esc(r.download_from)}` : ""}</small>`);
  if (kind === "disk-speed") { const s = r.seq_read?.mbps || 0;
    return group(`<div style="padding:14px 22px"><b>${esc(r.path)}</b>${bigs(big(f1(r.seq_read?.mbps), "MB/s", "Read", "var(--green)"), big(f1(r.seq_write?.mbps), "MB/s", "Write", "var(--blue)"))}
      <div class="muted" style="font-size:14px">Random reads (4K): ${r.rand_read?.iops} IOPS · ${f1(r.rand_read?.mbps)} MB/s<br>Random writes (4K): ${r.rand_write?.iops} IOPS · ${f1(r.rand_write?.mbps)} MB/s</div>
      <small class="muted" style="display:block;margin-top:6px">${s > 1500 ? "NVMe-class speed." : s > 350 ? "SATA SSD-class speed." : s > 80 ? "Hard-drive-class speed — fine for photos, video and backups." : "Slow — a USB 2 link, a struggling drive, or it's busy."} ${esc(r.note || "")}</small></div>`); }
  if (kind === "cpu-stress") return group(bigs(big(f1(r.max_temp), "°C", "Hottest", r.max_temp >= 92 ? "var(--red)" : r.max_temp >= 80 ? "var(--amber)" : "var(--green)"), big(r.avg_mhz ?? "—", "MHz", "Average clock"), ...(r.avg_watts != null ? [big(f1(r.avg_watts), "W", "CPU power", "var(--blue)")] : [])) + `<p style="margin:6px 22px 14px">${esc(r.verdict || "")}</p>`);
  if (kind === "mem-test") return group(bigs(big(f1(r.tested_bytes / 1e9), "GB", "Tested"), big(r.errors, "", "Errors", r.errors ? "var(--red)" : "var(--green)")) + `<p style="margin:6px 22px 14px;${r.ok ? "" : "color:var(--red)"}">${esc(r.verdict || "")}</p>`);
  if (kind === "backup-run" && r.stats) return group(row(`${r.stats.transferred} files changed`, { sub: `${bytes(r.stats.sent)} copied · ${r.stats.files} files in the backup · ${r.duration} s`, blue: true, icon: "okc", tint: "var(--green)" }));
  if (kind === "restore") return group(row("Restored", { sub: r.target, blue: true, icon: "okc", tint: "var(--green)" }));
  if (r.mount) return group(row("Ready", { sub: r.mount, blue: true, icon: "okc", tint: "var(--green)" }));
  return "";
}

// ── backups ────────────────────────────────────────────────────────────────────
const DAYN = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
export const schedText = s => !s ? "" : s.manual ? "Only when you run it" : s.every_hours ? `Every ${s.every_hours} h` : `${(s.days || []).length === 7 ? "Daily" : (s.days || []).join() === "0,1,2,3,4" ? "Weekdays" : (s.days || []).join() === "5,6" ? "Weekends" : (s.days || []).map(d => DAYN[d]).join(" ")} at ${s.time}`;
export const destText = d => d?.type === "local" ? d.path : d?.type === "smb" ? `\\\\${d.host}\\${d.share}` : d?.type === "nfs" ? `${d.host}:/${d.share}` : "—";
const lastText = j => { const l = j.last; if (!l) return ["Not run yet", "info"];
  return l.ok ? [`Backed up ${ago(l.t)} · ${l.stats?.transferred ?? 0} files changed`, "ok"] : l.partial ? [`Partly backed up ${ago(l.t)} — ${(l.skipped || [])[0] || ""}`, "warning"] : [`Failed ${ago(l.t)} — ${l.error || ""}`, "critical"]; };
export async function backups(ctx) {
  const draw = () => {
    const bl = S.cache["/api/v1/backups"], jobs = bl?.jobs || [], legacy = bl?.legacy, ls = S.cache["/api/v1/backup"];
    ctx.show(`<div style="display:flex;align-items:center;gap:8px;margin:4px 22px"><p class="muted" style="flex:1;margin:0">Copies of your folders on another drive or a NAS, made on a schedule.</p>${helpBtn("backup")}</div>
      ${legacy ? sec("Your backup script") + group(row("nova-backup", { sub: `${ls?.running ? "Running now…" : `Last run ${ls?.time || "—"}`} · ${(legacy.sets || []).map(s => s.name).join(", ")} → ${legacy.dest}`, blue: !!ls?.running, icon: "article" })
          + row("Back up now", { sub: "Runs your script (it also runs nightly)", icon: "play", tint: "var(--green)", click: isAdmin() ? "legacy" : "" }))
        + note(`This one is a script on the server (${legacy.script}), so Nova shows it but doesn't edit it. Backups you add below run alongside it.`) : ""}
      ${sec(legacy ? "Nova backups" : "Your backups")}${group(!bl ? row("Loading…", { dis: true }) : jobs.length ? jobs.map((j, i) => { const [t, lv] = lastText(j);
          return row(j.name, { sub: `${j.running ? "Running now…" : t} · ${schedText(j.schedule)} → ${destText(j.dest)}`, blue: j.running, icon: "backup", tint: j.enabled === false ? "var(--sub)" : lvColor(lv), click: `go:backup/${j.id}` }); }).join("")
        : row("Set up your first backup", { sub: "Pick what to protect and where to keep the copies — a drive or a NAS", blue: true, icon: "addc", tint: "var(--green)", click: "new" }))}`,
      { title: "Backups", actions: [{ icon: "add", label: "New backup", act: "new" }] });
  };
  ctx.handlers({ new: () => isAdmin() ? ctx.go("backup-edit/new") : viewOnly(), legacy: async () => { try { await post("/api/v1/actions/backup"); toast("Backup started"); } catch (e) { toast(e.message); } } });
  draw(); ctx.every(5000, async () => { await Promise.all([get("/api/v1/backups"), get("/api/v1/backup").catch(() => {})]); draw(); }, true);
}
export async function backup(ctx) {
  const id = ctx.args[0];
  const draw = () => {
    const bl = S.cache["/api/v1/backups"], j = bl?.jobs?.find(x => x.id === id);
    if (!j) return ctx.show(note(bl ? "This backup is gone." : "Loading…"), { title: "Backup" });
    const [t, lv] = lastText(j), k = j.keep || {};
    ctx.show(group(row(j.running ? "Running now…" : t, { sub: j.next ? `Next: ${ago(j.next)}` : schedText(j.schedule), blue: j.running, icon: lv === "ok" ? "okc" : "info", tint: lvColor(lv) }))
      + (isAdmin() ? group(row("Back up now", { icon: "play", tint: "var(--green)", click: j.running ? "" : "run", dis: j.running }) + row("Browse & restore", { sub: "Get back a file or folder from any snapshot", icon: "refresh", click: `go:restore/${id}`, end: helpBtn("restore") }) + row("Edit", { icon: "edit", click: `go:backup-edit/${id}` })) : "")
      + sec("What and where") + group(row("Folders", { sub: j.sources.join(", ") }) + row("Copies go to", { sub: destText(j.dest) + (j.last?.mode === "mirror" ? " · one copy + versions" : "") })
        + row("Kept", { sub: [k.hourly ? `${k.hourly} hourly` : "", `${k.daily} daily`, `${k.weekly} weekly`, `${k.monthly} monthly`].filter(Boolean).join(" · "), end: helpBtn("keep") })
        + row("Missing-drive protection", { sub: j.guard_pct ? `Skips a folder that loses over ${j.guard_pct}% of its files` : "Off", end: helpBtn("guard") }))
      + (j.history?.length ? sec("History") + group(j.history.slice(0, 15).map(h => row(`${ago(h.t)} · ${h.ok ? "OK" : h.partial ? "Partly" : "Failed"}`, { sub: h.error || `${h.stats?.transferred ?? 0} files changed · ${bytes(h.stats?.sent || 0)} copied · ${h.duration} s` })).join("")) : "")
      + (isAdmin() ? group(row("Delete this backup", { sub: "Stops it running; the copies already made stay on the drive", icon: "del", tint: "var(--red)", click: "del" })) : ""), { title: j.name });
  };
  ctx.handlers({
    run: () => startTask(ctx, `/api/v1/backups/${id}/run`, {}),
    del: async () => { const j = S.cache["/api/v1/backups"]?.jobs?.find(x => x.id === id);
      if (await confirm(`Delete ${j?.name}?`, `It won't run again. The snapshots already on ${destText(j?.dest)} are kept.`, "Delete")) { try { await del(`/api/v1/backups/${id}`); ctx.back(); } catch (e) { toast(e.message); } } },
  });
  draw(); ctx.every(4000, async () => { await get("/api/v1/backups"); draw(); }, true);
}
export async function backupEdit(ctx) {
  const [idArg, srcArg, destArg] = ctx.args, isNew = !idArg || idArg === "new";
  const v = { step: 0, sources: new Set((srcArg || "").split(",").filter(Boolean)), destType: "local", destPath: destArg || "", host: "", share: "", subdir: "", user: "", pass: "", tested: null,
    sched: 0, time: "03:30", days: new Set([0, 1, 2, 3, 4, 5, 6]), every: 6, daily: 14, weekly: 8, monthly: 12, name: "", guard: true, always: false, loaded: isNew };
  const job = () => ({ ...(isNew ? {} : { id: idArg }), name: v.name.trim(), sources: [...v.sources],
    dest: v.destType === "local" ? { type: "local", path: v.destPath } : { type: v.destType, host: v.host.trim(), share: v.share.trim().replace(/^\/+|\/+$/g, ""), subdir: v.subdir.trim(), ...(v.destType === "smb" ? { user: v.user.trim(), ...(v.pass || isNew ? { password: v.pass } : {}) } : {}) },
    schedule: v.sched === 2 ? { manual: true } : v.sched === 1 ? { every_hours: v.every } : { time: v.time, days: [...v.days].sort() },
    keep: { daily: v.daily, weekly: v.weekly, monthly: v.monthly }, guard_pct: v.guard ? 20 : 0, notify: v.always ? "always" : "failures" });
  const field = (id, val, ph, type = "text") => `<input class="field" id="${id}" type="${type}" placeholder="${esc(ph)}" value="${esc(val)}" autocomplete="off">`;
  const draw = () => {
    const sug = S.cache["/api/v1/backups/suggest"], srcs = sug?.sources || [], dests = sug?.destinations || [];
    if (!v.loaded) { const j = S.cache["/api/v1/backups"]?.jobs?.find(x => x.id === idArg);
      if (j) { Object.assign(v, { loaded: true, sources: new Set(j.sources), name: j.name, destType: j.dest.type, destPath: j.dest.path || "", host: j.dest.host || "", share: j.dest.share || "", subdir: j.dest.subdir || "", user: j.dest.user || "",
          sched: j.schedule.manual ? 2 : j.schedule.every_hours ? 1 : 0, every: j.schedule.every_hours || 6, time: j.schedule.time || "03:30", days: new Set(j.schedule.days || [0, 1, 2, 3, 4, 5, 6]),
          daily: j.keep.daily, weekly: j.keep.weekly, monthly: j.keep.monthly, guard: j.guard_pct > 0, always: j.notify === "always" }); } }
    const steps = ["What", "Where", "When", "Keep", "Name"];
    let body = "";
    if (v.step === 0) body = secHelp("What to back up", "backup") + group((sug ? srcs.map((s, i) => row(s.label, { sub: `${s.why}${s.size != null ? ` · ${bytes(s.size)}` : ""} · ${s.path}`, blue: v.sources.has(s.path), icon: "list", end: radio(v.sources.has(s.path)), click: `src:${i}` })).join("") : row("Looking at your server…", { dis: true }))
        + [...v.sources].filter(p => !srcs.some(s => s.path === p)).map(p => row(p, { sub: "Added by you", blue: true, icon: "list", end: radio(true), click: `unsrc:${enc(p)}` })).join(""))
      + group(`<div style="padding:16px;display:flex;gap:8px;flex-wrap:wrap">${field("custom", "", "Another folder, e.g. /srv/music")}<button class="pillbtn press" data-act="browse">Browse…</button><button class="pillbtn press" data-act="addsrc">Add</button></div>`);
    if (v.step === 1) body = secHelp("Where the copies go", "nas") + segmented(["A drive", "NAS (SMB)", "NAS (NFS)"], ["local", "smb", "nfs"].indexOf(v.destType), "dt")
      + (v.destType === "local" ? group((dests.map((d, i) => row(d.label, { sub: `${d.path} · ${bytes(d.free)} free${d.backup ? " · backup drive" : ""}`, blue: v.destPath === d.path, icon: "disk", tint: d.backup ? "var(--green)" : "var(--blue)", end: radio(v.destPath === d.path), click: `dst:${i}` })).join("")
          || row("No other drive found", { sub: "Set one up first: Storage & hardware → Set up drives → A backup drive", dis: true })) + row("Set up a new backup drive", { sub: "Erase a spare drive and use it for backups", icon: "addc", tint: "var(--green)", click: "go:setup/backup" }))
        : group(`<div style="padding:16px;display:grid;gap:10px">${field("host", v.host, "NAS address (e.g. 192.168.1.20 or nas.local)")}${field("share", v.share, v.destType === "smb" ? "Share name (e.g. backups)" : "Export path (e.g. volume1/backups)")}${field("subdir", v.subdir, "Folder inside it (optional)")}
          ${v.destType === "smb" ? field("user", v.user, "User name") + field("pass", v.pass, isNew ? "Password" : "Password (leave empty to keep it)", "password") : ""}
          <div><button class="pillbtn press" data-act="test">Test connection</button></div>${v.tested ? `<div style="color:${v.tested.startsWith("✓") ? "var(--green)" : "var(--red)"}">${esc(v.tested)}</div>` : ""}</div>`));
    if (v.step === 2) body = sec("When") + segmented(["Daily", "Every few hours", "Only manually"], v.sched, "sch")
      + (v.sched === 0 ? `<div class="timepick"><input type="time" id="tm" value="${esc(v.time)}"></div><div class="days">${["M", "T", "W", "T", "F", "S", "S"].map((d, i) => `<button class="${v.days.has(i) ? "on" : ""}" data-act="day:${i}">${d}</button>`).join("")}</div>${note("If the server is off at that time, it runs as soon as it's back on.")}`
        : v.sched === 1 ? group([1, 2, 3, 4, 6, 8, 12].map(h => row(`Every ${h} hour${h > 1 ? "s" : ""}`, { blue: v.every === h, end: radio(v.every === h), click: `ev:${h}` })).join("")) : note("It runs only when you tap Back up now."));
    if (v.step === 3) body = secHelp("How long to keep old copies", "keep") + group([["daily", "Daily copies", 1, 60, "days"], ["weekly", "Weekly copies", 0, 52, "weeks"], ["monthly", "Monthly copies", 0, 60, "months"]].map(([k, l, a, b, u]) =>
        `<div class="slider"><div class="top"><b>${l}</b><span data-lbl="${k}">${v[k]} ${u}</span></div><input type="range" data-range="${k}" min="${a}" max="${b}" value="${v[k]}"></div>`).join(""))
      + `<div class="chips" style="padding:0 22px">${chip("Smart (14 · 8 · 12)", "keep:14:8:12")}${chip("Just a week", "keep:7:0:0")}${chip("A long history", "keep:30:26:36")}</div>`
      + group(switchRow("Missing-drive protection", "Don't back up a folder that suddenly lost most of its files", v.guard, "guard") + switchRow("Tell me after every backup", v.always ? "A notification each time" : "Only when something goes wrong", v.always, "always"));
    if (v.step === 4) { if (!v.name) v.name = (srcs.find(s => v.sources.has(s.path))?.label || [...v.sources][0]?.split("/").pop() || "Backup").replace(/[^A-Za-z0-9 _-]/g, "").slice(0, 40);
      body = sec("Name") + group(`<div style="padding:16px">${field("bname", v.name, "e.g. Photos")}</div>`) + sec("Summary") + group(row("Backs up", { sub: [...v.sources].join(", ") }) + row("To", { sub: destText(job().dest) }) + row("When", { sub: schedText(job().schedule) }) + row("Keeps", { sub: `${v.daily} daily · ${v.weekly} weekly · ${v.monthly} monthly` })); }
    const ok = [v.sources.size > 0, v.destType === "local" ? !!v.destPath : !!(v.host.trim() && v.share.trim()), v.sched !== 0 || v.days.size > 0, true, !!v.name.trim()][v.step];
    ctx.show(`<div style="display:flex;gap:6px;margin:6px 22px">${steps.map((_, i) => `<i style="flex:1;height:6px;border-radius:3px;background:${i <= v.step ? "var(--blue)" : "var(--divider)"}"></i>`).join("")}</div><small class="muted" style="margin:0 22px">Step ${v.step + 1} of 5 · ${steps[v.step]}</small>`
      + body + `<div style="display:flex;gap:12px;padding:18px 22px"><button class="btn" style="flex:1;background:color-mix(in srgb,var(--text) 8%,transparent);color:var(--text)" data-act="${v.step ? "prev" : "back"}">${v.step ? "Back" : "Cancel"}</button><button class="btn" style="flex:1" data-act="next" ${ok ? "" : "disabled"}>${v.step < 4 ? "Next" : isNew ? "Save and back up now" : "Save"}</button></div>`,
      { title: isNew ? "New backup" : "Edit backup" });
    for (const k of ["host", "share", "subdir", "user", "pass", "bname"]) { const el = $("#" + k); if (el) el.oninput = e => { v[k === "bname" ? "name" : k] = e.target.value; v.tested = null; const n = $('[data-act="next"]'); if (n) n.disabled = !(v.destType === "local" ? v.destPath : v.host.trim() && v.share.trim()) && v.step === 1 || (v.step === 4 && !v.name.trim()); }; }
    $("#tm") && ($("#tm").onchange = e => v.time = e.target.value || v.time);
    wireCommon(ctx.root, { onSeg: (k, i) => { if (k === "dt") { v.destType = ["local", "smb", "nfs"][i]; v.tested = null; } if (k === "sch") v.sched = i; draw(); },
      onRangeInput: (k, x) => { v[k] = x; const l = $(`[data-lbl="${k}"]`); if (l) l.textContent = `${x} ${{ daily: "days", weekly: "weeks", monthly: "months" }[k]}`; } });
  };
  ctx.handlers({
    src: i => { const p = S.cache["/api/v1/backups/suggest"].sources[+i].path; v.sources.has(p) ? v.sources.delete(p) : v.sources.add(p); draw(); },
    unsrc: p => { v.sources.delete(decodeURIComponent(p)); draw(); },
    browse: async () => { const p = await pickFolder(); if (p) { v.sources.add(p); draw(); } },
    addsrc: () => { const p = ($("#custom").value || "").trim().replace(/\/+$/, "") || "/"; if (!p.startsWith("/")) return toast("Give a full path, like /srv/music"); v.sources.add(p); draw(); },
    dst: i => { v.destPath = S.cache["/api/v1/backups/suggest"].destinations[+i].path; draw(); },
    test: async () => { v.tested = "Testing…"; draw(); try { const r = await post("/api/v1/backups/test", { ...job(), name: "test", sources: v.sources.size ? [...v.sources] : ["/etc"] });
      v.tested = `✓ Connected · ${bytes(r.free)} free${r.hardlinks ? "" : " · no snapshots on this share (one copy + versions)"}`; } catch (e) { v.tested = "✗ " + e.message; } if (ctx.alive()) draw(); },
    day: i => { i = +i; v.days.has(i) ? v.days.delete(i) : v.days.add(i); draw(); }, ev: h => { v.every = +h; draw(); },
    keep: (a, b, c) => { Object.assign(v, { daily: +a, weekly: +b, monthly: +c }); draw(); },
    guard: () => { v.guard = !v.guard; draw(); }, always: () => { v.always = !v.always; draw(); },
    prev: () => { v.step--; draw(); },
    next: async () => {
      if (v.step < 4) { v.step++; return draw(); }
      try { const r = await post("/api/v1/backups", job()); history.back();
        if (isNew && r.job?.id) { const t = await post(`/api/v1/backups/${r.job.id}/run`, {}); setTimeout(() => ctx.go("task/" + t.id), 50); toast("Saved — first backup started"); } else toast("Saved"); }
      catch (e) { toast(e.message); }
    },
  });
  draw();
  await Promise.all([get("/api/v1/backups/suggest").catch(e => toast(e.message)), isNew ? null : get("/api/v1/backups")]); if (ctx.alive()) draw();
}
export async function restore(ctx) {
  const [id, snap = "", ...rest] = ctx.args, path = rest.length ? "/" + rest.join("/") : "";
  let data = null, err = null;
  const draw = () => {
    const items = data?.items || [], base = (data?.path || "/").replace(/\/$/, "");
    ctx.show((snap ? note(`Snapshot ${snap.replace("_", " ")} · ${path || "/"}`) : "") + group(err ? row("Couldn't open the backup", { sub: err, icon: "err", tint: "var(--red)" }) : !data ? row("Opening…", { dis: true })
      : !snap ? (data.snapshots || []).map((s, i) => row(s.id === "current" ? "Latest copy" : ago(s.t), { sub: s.id.replace("_", " "), blue: i === 0, icon: "clock", click: `go:restore/${id}/${s.id}` })).join("")
      : items.length ? items.map((it, i) => row(it.name, { sub: it.dir ? "Folder" : `${bytes(it.size)} · ${ago(it.mtime)}`, icon: it.dir ? "list" : "article",
          click: it.dir ? `go:restore/${id}/${snap}${(base + "/" + it.name).split("/").map(enc).join("/")}` : isAdmin() ? `pick:${i}` : "", end: isAdmin() ? `<button class="xbtn" data-act="pick:${i}" aria-label="Restore">${I("refresh")}</button>` : "" })).join("") : row("Empty folder", { dis: true })),
      { title: !snap ? "Pick a snapshot" : (path.split("/").pop() || snap), actions: snap && path.length > 1 && isAdmin() ? [{ icon: "refresh", label: "Restore this folder", act: "pickdir" }] : [] });
  };
  const doRestore = async p => {
    const v = await dialog(`Restore ${p.split("/").pop()}?`, `From the snapshot of ${snap.replace("_", " ")}.`, [{ label: "Cancel", value: null }, { label: "Next to the original", color: "var(--blue)", value: "beside" }, { label: "Put it back", color: "var(--blue)", value: "original" }],
      `<p class="muted" style="margin:0 26px;white-space:pre-line">${esc(HELP.restore[1])}</p>`);
    if (v) startTask(ctx, `/api/v1/backups/${id}/restore`, { snapshot: snap, path: p, to: v });
  };
  ctx.handlers({ pick: i => { const it = data.items[+i]; doRestore((data.path || "/").replace(/\/$/, "") + "/" + it.name); }, pickdir: () => doRestore(path) });
  draw();
  try { data = snap ? await get(`/api/v1/backups/${id}/browse?snap=${enc(snap)}&path=${enc(path || "/")}`) : await get(`/api/v1/backups/${id}/snapshots`); } catch (e) { err = e.message; }
  if (ctx.alive()) draw();
}

// ── diagnostics ────────────────────────────────────────────────────────────────
export async function diag(ctx) {
  const v = { disk: null, cpu: 60, memPct: 50, memSec: 60, host: "1.1.1.1", port: "443", out: null, dev: null, devLive: null };
  const draw = () => {
    const tasks = S.cache["/api/v1/tasks"]?.tasks || [], st = S.cache["/api/v1/storage"];
    const last = k => tasks.find(t => t.kind === k && t.state === "done"), running = k => tasks.find(t => t.kind === k && t.state === "running");
    const run = (k, title, sub, act, en = true) => running(k) ? taskRow(running(k)) : row(title, { sub, icon: "play", tint: "var(--green)", click: en && isAdmin() ? act : "", dis: !en || !isAdmin() });
    const targets = [...(st?.pools || []).filter(p => p.mount).map(p => [p.mount, `${p.name} pool`]), ...(st?.drives || []).flatMap(d => d.mounts.map(m => [m, driveTitle(d)]))].filter((x, i, a) => a.findIndex(y => y[0] === x[0]) === i);
    const top = S.cache["/api/v1/diag/top"];
    ctx.show(note("Tests run on the server. They're safe to run any time; the heavy ones (CPU, memory) slow other apps while they run.")
      + secHelp("Internet speed (server)", "net") + group((last("net-internet") ? resultHtml("net-internet", last("net-internet").result).replace(/^<div class="group glass">|<\/div>$/g, "") : "") + run("net-internet", "Run the internet test", "About 30 seconds", "net"))
      + secHelp("This browser ↔ server", "device-net") + group((v.dev ? bigs(big(f1(v.dev.mbps), "Mbps", "↓ To this browser", "var(--green)"), big(f1(v.dev.ping), "ms", "Response time")) : "")
        + row(v.devLive ? "Testing…" : "Test this browser's connection", { sub: v.devLive || "Downloads 25 MB from the server", icon: "play", tint: "var(--green)", click: v.devLive ? "" : "dev" }))
      + secHelp("Drive speed", "disk") + group((last("disk-speed") ? resultHtml("disk-speed", last("disk-speed").result).replace(/^<div class="group glass">|<\/div>$/g, "") : "")
        + `<div class="chips" style="padding:8px 16px;overflow-x:auto;flex-wrap:nowrap">${targets.map(([m, l], i) => chip(`${l} · ${m}`, `disk:${i}`, v.disk === m)).join("")}</div>` + run("disk-speed", `Test ${v.disk || "a drive"}`, "About 40 seconds · writes a temporary file, deleted after", "diskgo", !!v.disk))
      + secHelp("CPU stress test", "cpu") + group((last("cpu-stress") ? resultHtml("cpu-stress", last("cpu-stress").result).replace(/^<div class="group glass">|<\/div>$/g, "") : "")
        + `<div class="chips" style="padding:8px 16px">${[[30, "30 s"], [60, "1 min"], [300, "5 min"], [600, "10 min"]].map(([s, l]) => chip(l, `cpu:${s}`, v.cpu === s)).join("")}</div>` + run("cpu-stress", "Run every core flat out", "Watch temperature, clock speed and power live", "cpugo"))
      + secHelp("Memory test", "mem") + group((last("mem-test") ? resultHtml("mem-test", last("mem-test").result).replace(/^<div class="group glass">|<\/div>$/g, "") : "")
        + `<div class="chips" style="padding:8px 16px">${[25, 50, 75].map(p => chip(`${p}% of free`, `mp:${p}`, v.memPct === p)).join("")}</div><div class="chips" style="padding:0 16px 8px">${[[60, "1 min"], [300, "5 min"], [900, "15 min"]].map(([s, l]) => chip(l, `ms:${s}`, v.memSec === s)).join("")}</div>`
        + run("mem-test", "Fill and check memory", "Your apps keep running — Nova leaves room for them", "memgo"))
      + secHelp("Quick tools", "tools") + group(`<div style="padding:16px;display:grid;gap:10px"><div style="display:flex;gap:8px"><input class="field" id="qh" value="${esc(v.host)}" placeholder="Host or IP"><input class="field" id="qp" style="width:90px" value="${esc(v.port)}" placeholder="Port" inputmode="numeric"></div>
          <div style="display:flex;gap:8px;flex-wrap:wrap">${[["ping", "Ping"], ["trace", "Traceroute"], ["dns", "DNS lookup"], ["port", "Check port"]].map(([k, l]) => `<button class="pillbtn press" data-act="q:${k}">${l}</button>`).join("")}</div>
          ${v.out ? `<pre style="margin:0;white-space:pre-wrap;font-size:14px">${esc(v.out)}</pre>` : ""}</div>`)
      + sec("What's using the server") + group(top ? `<div style="padding:14px 22px"><small class="muted">Load ${f1(top.load[0])} on ${top.cpus} threads · memory ${bytes(top.mem.used)} of ${bytes(top.mem.total)}</small>
          ${top.procs.slice(0, 15).map(p => `<div style="display:flex;gap:8px;padding:3px 0"><span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(p.name)}</span><span style="width:64px;color:var(--blue)">${f1(p.cpu)}%</span><span class="muted" style="width:76px">${bytes(p.rss)}</span></div>`).join("")}</div>` : row("Loading…", { dis: true })),
      { title: "Diagnostics" });
    for (const [id, k] of [["qh", "host"], ["qp", "port"]]) { const el = $("#" + id); if (el) el.oninput = e => v[k] = e.target.value.trim(); }
    v._targets = targets;
  };
  const start = (kind, body = {}) => isAdmin() ? startTask(ctx, `/api/v1/diag/${kind}`, body) : viewOnly();
  ctx.handlers({
    net: () => start("net-internet"), disk: i => { v.disk = v._targets[+i][0]; draw(); }, diskgo: () => start("disk-speed", { path: v.disk }),
    cpu: s => { v.cpu = +s; draw(); }, cpugo: () => start("cpu-stress", { seconds: v.cpu }),
    mp: p => { v.memPct = +p; draw(); }, ms: s => { v.memSec = +s; draw(); }, memgo: () => start("mem-test", { percent: v.memPct, seconds: v.memSec }),
    q: async tool => {
      v.out = "…"; draw();
      try {
        const r = await get(`/api/v1/diag/${tool}?host=${enc(v.host)}${tool === "port" ? `&port=${enc(v.port)}` : ""}`);
        v.out = tool === "ping" ? (r.loss_pct >= 100 ? `No reply from ${r.host}. ${r.error || ""}` : `${r.host}: avg ${f1(r.avg)} ms (min ${f1(r.min)}, max ${f1(r.max)}) · jitter ${f1(r.jitter)} ms · ${f1(r.loss_pct)}% lost`)
          : tool === "trace" ? (r.hops.map(h => `${h.hop}. ${h.host}${h.ms != null ? `  ${f1(h.ms)} ms` : ""}`).join("\n") || "No route found")
          : tool === "dns" ? (r.error ? `Couldn't resolve: ${r.error}` : `${r.name} → ${r.addresses.join(", ")}  (${f1(r.ms)} ms)`)
          : r.open ? `${r.host}:${r.port} is reachable (${f1(r.ms)} ms)` : `${r.host}:${r.port} — ${r.error}`;
      } catch (e) { v.out = e.message; }
      if (ctx.alive()) draw();
    },
    dev: async () => {
      v.devLive = "Measuring response time…"; draw();
      try {
        const times = []; for (let i = 0; i < 8; i++) { const t = performance.now(); await get("/api/v1/ping"); times.push(performance.now() - t); }
        const ping = times.sort((a, b) => a - b)[4];
        const t0 = performance.now(); let n = 0, shown = 0;
        const r = await signedFetch("GET", "/api/v1/diag/blob?mb=25");
        if (!r.ok) throw new Error(`Speed test failed (${r.status})`);
        const rd = r.body.getReader();
        for (;;) {
          const { done, value } = await rd.read(); if (done) break; n += value.length;
          const s = (performance.now() - t0) / 1000;
          if (s - shown > .25 && ctx.alive()) { shown = s; v.devLive = `${bytes(n)} · ${f1(n * 8 / 1e6 / Math.max(.01, s))} Mbps`; const el = $('[data-act="dev"] small'); if (el) el.textContent = v.devLive; }
        }
        v.dev = { mbps: n * 8 / 1e6 / Math.max(.01, (performance.now() - t0) / 1000), ping };
      } catch (e) { toast(e.message); }
      v.devLive = null; if (ctx.alive()) draw();
    },
  });
  draw();
  ctx.every(4000, async () => { await Promise.all([get("/api/v1/tasks"), get("/api/v1/diag/top").catch(() => {}), S.cache["/api/v1/storage"] ? null : get("/api/v1/storage").catch(() => {})]); if (!v.devLive) draw(); }, true);
}



// ── apps: the web apps on the server ───────────────────────────────────────────
// Nova web is HTTPS and most home-server apps are plain http on the LAN, so browsers won't show them
// inside this page: each app opens in its own tab (the Android app opens them inside Nova).
const iconCache = {};
export async function appIconUrl(id) { return appIcon(id); }
export function appHrefFor(a) { return appHref(a); }
async function appIcon(id) {
  if (id in iconCache) return iconCache[id];
  try { const r = await signedFetch("GET", `/api/v1/apps/${enc(id)}/icon`); iconCache[id] = r.ok ? URL.createObjectURL(await r.blob()) : null; }
  catch { iconCache[id] = null; }
  return iconCache[id];
}
const appHref = a => {
  // away from home = came in through Cloudflare (the server says so); a home or Tailscale address works directly
  const ip = /^(\d+\.){3}\d+$|^\[/.test(location.hostname);
  const remote = S.me?.via ? S.me.via === "remote" : !ip && !/\.local$/.test(location.hostname);
  if (remote) return a.remote_url || null;
  if (a.url) return a.url;
  if (!a.port) return null;
  const host = a.host_ip || location.hostname;
  return `${a.scheme || "http"}://${host.includes(":") ? `[${host}]` : host}:${a.port}${a.path || "/"}`;
};
export async function apps(ctx) {
  let showHidden = false;
  const draw = () => {
    const all = S.cache["/api/v1/apps"]?.apps || [], list = all.filter(a => showHidden || !a.hidden);
    ctx.show(note("The web apps on your server. Each opens in its own tab. Right-click (or hold) an app to rename, hide it or set a remote link.")
      + (isAdmin() ? `${sec("Built in")}<div class="appgrid">${[["files", "Files", "folder", "#3e91ff"], ["term", "Terminal", "term", "#3ecf6e"]].map(([r, n, ic, c]) =>
          `<button class="appcell press" data-act="go:${r}" style="border:0;background:none;font:inherit;color:var(--text);cursor:pointer"><span class="appic" style="background:color-mix(in srgb,${c} 18%,var(--card));color:${c}">${I(ic)}</span><span class="appname">${n}</span></button>`).join("")}</div>${sec("On your server")}` : "")
      + (S.cache["/api/v1/apps"] ? (list.length ? `<div class="appgrid">${list.map((a, i) => { const h = appHref(a);
          if (a.kind === "command") return `<button class="appcell press${a.hidden ? " dim" : ""}" data-act="runcmd:${i}" data-i="${i}" style="border:0;background:none;font:inherit;color:var(--text);cursor:pointer"><span class="appic" data-id="${esc(a.id)}" style="color:var(--green)">${I("term")}</span><span class="appname">${esc(a.name)}</span></button>`;
          return `<a class="appcell${a.hidden ? " dim" : ""}" ${h ? `href="${esc(h)}" target="_blank" rel="noopener noreferrer"` : `data-act="noreach"`} data-i="${i}"><span class="appic" data-id="${esc(a.id)}">${esc((a.name || "?")[0].toUpperCase())}</span><span class="appname">${esc(a.name)}</span></a>`; }).join("")}${isAdmin() ? `<button class="appcell addapp press" data-act="add"><span class="appic">${I("add")}</span><span class="appname">Add</span></button>` : ""}</div>`
        : group(row("No web apps found", { sub: "Install one from the Store, or add a link with +", icon: "apps" }))) : note("Looking for apps…"))
      + (all.some(a => a.hidden) ? group(switchRow("Show hidden apps", null, showHidden, "hid")) : ""),
      { title: "Apps", actions: [{ icon: "add", label: "Add an app", act: "add" }] });
    $$(".appic", ctx.root).forEach(async el => { const u = await appIcon(el.dataset.id); if (u && ctx.alive()) { el.textContent = ""; el.style.backgroundImage = `url("${u}")`; el.classList.add("img"); } });
    $$(".appcell", ctx.root).forEach(el => el.oncontextmenu = e => { e.preventDefault(); edit(list[+el.dataset.i]); });
  };
  // command apps: a saved command or script, run with one tap as your normal account
  const editCommand = async a => {
    const v = { name: a?.name || "", command: a?.command || "", timeout: a?.timeout || 600, confirm: !!a?.confirm };
    const p = dialog(a ? a.name : "New command app", "It runs on the server as your normal account, and you see its output. Saving it asks for your fingerprint.",
      [{ label: "Cancel", value: null }, ...(a ? [{ label: "Delete", color: "var(--red)", value: "del" }] : []), { label: "Save", color: "var(--blue)", value: "save" }],
      `<div class="pad" style="display:grid;gap:8px"><input class="field" id="cm-name" placeholder="Name, e.g. Clean up Docker" value="${esc(v.name)}">
        <textarea class="field" id="cm-cmd" rows="5" placeholder="docker system prune -f&#10;df -h" spellcheck="false" style="font-family:ui-monospace,monospace;font-size:14px">${esc(v.command)}</textarea>
        <label class="chk"><input type="checkbox" id="cm-confirm" ${v.confirm ? "checked" : ""}> Ask before running</label>
        <label class="chk">Stop it after <input class="field" id="cm-to" type="number" min="10" max="3600" value="${v.timeout}" style="width:90px;padding:6px 10px;font-size:14px"> seconds</label></div>`);
    $("#cm-name").oninput = e => v.name = e.target.value; $("#cm-cmd").oninput = e => v.command = e.target.value;
    $("#cm-confirm").onchange = e => v.confirm = e.target.checked; $("#cm-to").oninput = e => v.timeout = +e.target.value || 600;
    const r = await p; if (!r) return;
    try {
      if (r === "del") { if (!(await confirm(`Delete ${a.name}?`, "Only the app tile goes; nothing on the server changes.", "Delete"))) return; await del(`/api/v1/apps/${enc(a.id)}`); }
      else await post("/api/v1/apps/command", { ...(a ? { id: a.id } : {}), name: v.name.trim(), command: v.command, timeout: v.timeout, confirm: v.confirm });
      await get("/api/v1/apps"); draw(); if (r === "save") toast("Saved");
    } catch (e) { toast(e.message); }
  };
  const runCommand = async a => {
    if (!isAdmin()) return viewOnly();
    if (a.confirm && !(await confirm(`Run ${a.name}?`, a.command.slice(0, 200), "Run", "var(--blue)"))) return;
    try { const r = await post(`/api/v1/apps/${enc(a.id)}/run`); if (r?.task) ctx.go("task/" + r.task); } catch (e) { toast(e.message); }
  };
  const edit = async a => {
    if (!isAdmin()) return viewOnly();
    if (a?.kind === "command") return editCommand(a);
    if (!a) { const k = await dialog("Add an app", "", [{ label: "Cancel", value: null }, { label: "Command or script", value: "cmd" }, { label: "Web page", color: "var(--blue)", value: "web" }],
        `<div class="pad"><p class="muted" style="margin:0">A web page on your network opens like any other app. A command or script runs on the server with one tap, and shows you its output.</p></div>`);
      if (!k) return; if (k === "cmd") return editCommand(null); }
    const v = { name: a?.name || "", url: a?.url || "", remote_url: a?.remote_url || "", icon: a?.slug || "" };
    const f = (id, ph) => `<input class="field" id="ap-${id}" placeholder="${esc(ph)}" value="${esc(v[id])}" style="margin-top:8px">`;
    const btns = [{ label: "Cancel", value: null }, ...(a ? [{ label: "From anywhere…", value: "remote" }] : []), ...(a && a.source !== "custom" ? [{ label: a.hidden ? "Show" : "Hide", value: "hide" }] : []), ...(a?.source === "custom" ? [{ label: "Delete", color: "var(--red)", value: "del" }] : []), { label: "Save", color: "var(--blue)", value: "save" }];
    const p = dialog(a ? a.name : "Add an app", a ? "Leave the link empty to use the one Nova found." : "Any web page on your network, like http://192.168.1.20:8096",
      btns, `<div class="pad">${f("name", "Name")}${f("url", a ? "Link at home (optional)" : "Link (http://…)")}${f("remote_url", "Remote link, e.g. https://photos.example.com (optional)")}${f("icon", "Icon name from dashboard-icons, e.g. jellyfin")}</div>`);
    for (const k of Object.keys(v)) $("#ap-" + k).oninput = e => v[k] = e.target.value.trim();
    const r = await p; if (!r) return;
    if (r === "remote") return remoteSetup(a);
    try {
      if (r === "del") await del(`/api/v1/apps/${enc(a.id)}`);
      else await post("/api/v1/apps", { ...(a ? { id: a.id } : {}), ...(r === "hide" ? { hidden: !a.hidden } : v) });
      delete iconCache[a?.id]; await get("/api/v1/apps"); draw();
    } catch (e) { toast(e.message); }
  };
  // Labs: Nova does the Cloudflare steps itself (protect → DNS → route), after showing exactly what it will change
  const autoSetup = async (a, g, host) => {
    let h = host, p;
    const r0 = dialog("Address for " + a.name, "On your domain, like photos.example.com.", [{ label: "Cancel", value: null }, { label: "Next", color: "var(--blue)", value: "next" }], `<div class="pad"><input class="field" id="cfh" value="${esc(h)}"></div>`);
    $("#cfh").oninput = e => h = e.target.value.trim().toLowerCase(); if ((await r0) !== "next") return;
    const tls = /^https:/.test(g.service || "");
    try { p = await post("/api/v1/cloudflare/plan", { host: h, service: g.service, no_tls_verify: tls }); } catch (e) { return toast(e.message); }
    const ok = await dialog("Here's what Nova will do", "", [{ label: "Cancel", value: null }, { label: "Do it", color: "var(--blue)", value: "go" }],
      `<div class="pad"><ol class="steps">${p.steps.map(x => `<li>${esc(x)}</li>`).join("")}</ol><p class="muted">In that order, so the app is never reachable without the login. Your phone confirms it.</p></div>`);
    if (ok !== "go") return;
    try { const res = await post("/api/v1/cloudflare/publish", { host: p.host, service: g.service, no_tls_verify: tls, app: a.id }); if (res?.url) { toast(`${a.name} is at ${res.url}`); await get("/api/v1/apps"); draw(); remoteSetup({ ...a, remote_url: res.url }); } }
    catch (e) { toast(e.message); }
  };
  // Open an app from anywhere: a public hostname on your Cloudflare tunnel, behind Cloudflare Access
  const remoteSetup = async a => {
    let g; try { g = await get(`/api/v1/apps/${enc(a.id)}/remote`); } catch (e) { return toast(e.message); }
    const chk = g.check ? `<div class="rcheck ${esc(g.check.state)}">${I(g.check.state === "protected" ? "shield" : g.check.state === "open" ? "warn" : "info")}<span>${esc(g.check.message)}</span></div>` : "";
    const step = (n, t) => `<li><b>${n}.</b> ${t}</li>`;
    const host = g.remote_url ? new URL(g.remote_url).hostname : g.suggested;
    const labsOn = (await get("/api/v1/labs").catch(() => ({})))?.labs?.cloudflare_sync;
    const r = await dialog(`${a.name} from anywhere`, "", [{ label: "Close", value: null }, ...(g.remote_url ? [{ label: "Check again", value: "check" }] : []), ...(labsOn && !g.remote_url && g.service ? [{ label: "Set it up for me", value: "auto" }] : []), { label: g.remote_url ? "Change link" : "Set the link", color: "var(--blue)", value: "set" }],
      `<div class="pad rguide">${chk}<p class="muted">Your server already has a Cloudflare tunnel. Give this app its own address on it, protected by the same Cloudflare login as Nova — nothing reaches the app until you've signed in.</p>
        <ol>${step(1, "Cloudflare Zero Trust → <b>Networks → Tunnels</b> → your tunnel → <b>Public hostnames</b> → <b>Add a public hostname</b>.")}
        ${step(2, `Subdomain and domain: ${host ? copyCmd(host) : "e.g. photos.yourdomain.com"}`)}
        ${step(3, `Service: ${g.service ? copyCmd(g.service) : "the app's address on the server"}${/^https:/.test(g.service || "") ? `<br><small class="muted">It uses its own certificate: under <b>Additional application settings → TLS</b>, turn on <b>No TLS Verify</b>.</small>` : ""}`)}
        ${step(4, "<b>Access → Applications</b>: add the same address to the application that protects Nova (or use a wildcard like <code>*." + esc(g.domain || "yourdomain.com") + "</code>), with your Allow policy.")}
        ${step(5, "Set it below as the app's remote link, then <b>Check</b> — Nova makes sure Cloudflare asks for a login first.")}</ol></div>`);
    if (r === "check") return remoteSetup(a);
    if (r === "auto") return autoSetup(a, g, host);
    if (r === "set") {
      let v = a.remote_url || (host ? "https://" + host : "");
      const p2 = dialog("Remote link", "The https:// address you added in Cloudflare.", [{ label: "Cancel", value: null }, { label: "Save and check", color: "var(--blue)", value: "save" }],
        `<div class="pad"><input class="field" id="rl" value="${esc(v)}" placeholder="https://photos.example.com"></div>`);
      $("#rl").oninput = e => v = e.target.value.trim();
      if ((await p2) !== "save") return;
      try { await post("/api/v1/apps", { id: a.id, remote_url: v }); await get("/api/v1/apps"); draw(); return remoteSetup({ ...a, remote_url: v }); } catch (e) { toast(e.message); }
    }
  };
  ctx.handlers({ add: () => edit(null), hid: () => { showHidden = !showHidden; draw(); },
    runcmd: i => { const all = S.cache["/api/v1/apps"]?.apps || []; const list = all.filter(a => showHidden || !a.hidden); runCommand(list[+i]); },
    noreach: () => toast("Away from home this app needs its own link — right-click it → From anywhere…") });
  draw(); ctx.every(30000, async () => { await get("/api/v1/apps"); draw(); }, true);
}


/** Pick a folder on the server by clicking through it. Resolves the path, or null. */
export function pickFolder(start = "/") {
  return new Promise(res => {
    let path = start, done = false;
    const finish = v => { if (done) return; done = true; closeSheet(); res(v); };
    const box = sheet(`<h2>Choose a folder</h2><div class="pad" id="fp"></div><div class="acts"><button data-fp="cancel">Cancel</button><i></i><button data-fp="up">Up</button><i></i><button data-fp="pick" style="color:var(--blue)">Back up this folder</button></div>`, () => { if (!done) { done = true; res(null); } });
    const load = async () => {
      const el = $("#fp", box); el.innerHTML = `<p class="muted">Loading…</p>`;
      let r; try { r = await get(`/api/v1/fs/dirs?path=${enc(path)}`); path = r.path; } catch (e) { r = { dirs: [], error: e.message }; }
      el.innerHTML = `<div class="muted" style="font-family:ui-monospace,monospace;margin-bottom:8px;overflow-wrap:anywhere">${esc(path)}</div>
        <div style="max-height:50vh;overflow:auto">${r.error ? `<p style="color:var(--red)">${esc(r.error)}</p>` : r.dirs.length ? r.dirs.map((d, i) => `<button class="choice" data-d="${i}"><span class="t" style="display:flex;gap:10px;align-items:center">${I("list")} ${esc(d)}</span></button>`).join("") : `<p class="muted">No folders inside</p>`}</div>`;
      $$("[data-d]", el).forEach(b => b.onclick = () => { path = path.replace(/\/$/, "") + "/" + r.dirs[+b.dataset.d]; load(); });
    };
    $$("[data-fp]", box).forEach(b => b.onclick = () => {
      const k = b.dataset.fp;
      if (k === "cancel") finish(null);
      else if (k === "up") { path = path.replace(/\/[^/]*\/?$/, "") || "/"; load(); }
      else finish(path);
    });
    load();
  });
}


// ── update center ──────────────────────────────────────────────────────────────
const tick = on => `<span class="tick${on ? " on" : ""}" style="margin:0">${I("check")}</span>`;
export async function updates(ctx) {
  let pk = null, cs = null, lastT = null;
  const draw = () => {
    const u = S.cache["/api/v1/updates"] || {}, apt = u.apt || [], cons = u.containers || [], outdated = cons.filter(c => c.status === "update");
    if (u.t !== lastT) { lastT = u.t; pk = new Set(apt.map(p => p.name)); cs = new Set(outdated.filter(c => c.updatable).map(c => c.name)); }
    const run = (S.cache["/api/v1/tasks"]?.tasks || []).find(t => t.state === "running" && ["updates-check", "apt-upgrade", "containers-update"].includes(t.kind));
    ctx.show(group(run ? taskRow(run) : row("Check for updates", { sub: u.t ? `Last checked ${ago(u.t)}` : "Not checked yet", icon: "refresh", click: isAdmin() ? "check" : "" }))
      + (u.nova ? sec("Nova") + group(row(u.nova.update ? `Nova ${u.nova.available} is ready` : "Nova is up to date", { sub: `Installed: ${u.nova.installed || "?"}`, blue: !!u.nova.update, icon: "update", click: "go:settings" })) : "")
      + (u.t ? `<div class="sechead">${sec("System packages")}${apt.length > 1 && isAdmin() ? `<button class="pillbtn press" data-act="pkall">${pk.size === apt.length ? "Select none" : "Select all"}</button>` : ""}</div>` + group((apt.length ? apt.map((p, i) => row(p.name, { sub: `${p.from} → ${p.to}${p.security ? " · security" : ""}${p.restarts === "docker" ? " · restarts Docker (every container)" : p.restarts === "server" ? " · needs a restart" : ""}`,
            blue: p.security, end: tick(pk.has(p.name)), click: `pk:${i}` })).join("") + row(`Update ${pk.size === apt.length ? "all " + apt.length : pk.size} package${pk.size === 1 ? "" : "s"}`, { icon: "down", tint: "var(--green)", blue: true, click: pk.size && !run && isAdmin() ? "doPk" : "", dis: !pk.size || !!run })
          : row("All packages are up to date", { icon: "okc", tint: "var(--green)" })))
        + `<div class="sechead">${sec("Containers")}${outdated.filter(c => c.updatable).length > 1 && isAdmin() ? `<button class="pillbtn press" data-act="csall">${cs.size === outdated.filter(c => c.updatable).length ? "Select none" : "Select all"}</button>` : ""}</div>` + group((cons.length ? cons.map((c, i) => row(c.name, { sub: `${c.image} · ${c.status === "update" ? "newer image available" : c.status === "current" ? "up to date" : "couldn't check"}${c.status === "update" && !c.updatable ? " · not from a Compose file" : ""}`,
            blue: c.status === "update", icon: "box", tint: c.status === "update" ? "var(--amber)" : "var(--sub)", end: c.status === "update" && c.updatable ? tick(cs.has(c.name)) : "", click: c.status === "update" && c.updatable ? `cs:${i}` : "" })).join("") : row("No containers", { icon: "box" }))
          + (outdated.length ? row(`Update ${cs.size} container${cs.size === 1 ? "" : "s"}`, { sub: "Pulls the newest image and restarts each one", icon: "down", tint: "var(--green)", blue: true, click: cs.size && !run && isAdmin() ? "doCs" : "", dis: !cs.size || !!run }) : ""))
        + note("Big apps (Immich, Nextcloud, Home Assistant…) sometimes change how they work between versions — check their release notes before a major update.") : ""),
      { title: "Updates" });
  };
  ctx.handlers({
    check: () => startTask(ctx, "/api/v1/updates/check", {}),
    pk: i => { const n = S.cache["/api/v1/updates"].apt[+i].name; pk.has(n) ? pk.delete(n) : pk.add(n); draw(); },
    pkall: () => { const apt = S.cache["/api/v1/updates"].apt; pk = pk.size === apt.length ? new Set() : new Set(apt.map(p => p.name)); draw(); },
    csall: () => { const l = S.cache["/api/v1/updates"].containers.filter(c => c.status === "update" && c.updatable); cs = cs.size === l.length ? new Set() : new Set(l.map(c => c.name)); draw(); },
    cs: i => { const n = S.cache["/api/v1/updates"].containers[+i].name; cs.has(n) ? cs.delete(n) : cs.add(n); draw(); },
    doPk: async () => {
      const apt = S.cache["/api/v1/updates"].apt;
      if (apt.some(p => pk.has(p.name) && p.restarts === "docker") && !(await confirm("This restarts Docker", "Updating Docker restarts it, so every container stops for a moment and starts again.", "Update anyway", "var(--blue)"))) return;
      startTask(ctx, "/api/v1/updates/packages", { packages: pk.size === apt.length ? "all" : [...pk] });
    },
    doCs: () => startTask(ctx, "/api/v1/updates/containers", { containers: [...cs] }),
  });
  draw(); ctx.every(5000, async () => { await Promise.all([get("/api/v1/updates"), get("/api/v1/tasks")]); draw(); }, true);
}


// ── your own container: a simple form (the server checks every field) ──────────────────────
export async function containerNew(ctx) {
  const v = { name: "", image: "", restart: "unless-stopped", ports: [{ host: "", container: "" }], volumes: [], env: "" };
  const spec = () => ({ name: v.name.trim().toLowerCase(), image: v.image.trim(), restart: v.restart,
    ports: v.ports.filter(p => p.host && p.container).map(p => ({ host: +p.host, container: +p.container, proto: p.udp ? "udp" : "tcp" })),
    volumes: v.volumes.filter(x => x.host && x.container).map(x => ({ host: x.host, container: x.container, ro: !!x.ro })),
    env: Object.fromEntries(v.env.split("\n").map(l => l.trim()).filter(l => l && l.includes("=")).map(l => [l.slice(0, l.indexOf("=")).trim(), l.slice(l.indexOf("=") + 1)])) });
  const field = (id, ph, val, extra = "") => `<input class="field" data-f="${id}" placeholder="${esc(ph)}" value="${esc(val)}" autocomplete="off" autocapitalize="off" spellcheck="false" ${extra}>`;
  const draw = () => {
    ctx.show(`${note("Run any image from Docker Hub or another registry. It gets its own folder in /opt, starts with the server, and shows up in Containers and Apps. For safety it can't be given full control of the server (no privileged mode, host network, devices or system folders).")}
      ${sec("Container")}<div class="group glass cform">${field("name", "Name, e.g. my-web", v.name)}${field("image", "Image, e.g. nginx:latest or ghcr.io/owner/app:1.2", v.image)}</div>
      ${sec("Ports · server → container")}<div class="group glass cform">${v.ports.map((p, i) => `<div class="crow">${field("ph" + i, "8080", p.host, 'inputmode="numeric"')}<span class="muted">→</span>${field("pc" + i, "80", p.container, 'inputmode="numeric"')}
          <label class="chk"><input type="checkbox" data-f="pu${i}" ${p.udp ? "checked" : ""}> UDP</label><button class="rmbtn press" data-act="rmport:${i}" aria-label="Remove">${I("del")}</button></div>`).join("")}
        <button class="pillbtn press" data-act="addport">${I("add")} Add a port</button></div>
      ${sec("Folders · server → container")}<div class="group glass cform">${v.volumes.map((x, i) => `<div class="crow">${field("vh" + i, "/mnt/media, or a name for its own data", x.host)}<button class="pillbtn press" data-act="browse:${i}">Browse…</button><span class="muted">→</span>${field("vc" + i, "/data", x.container)}
          <label class="chk"><input type="checkbox" data-f="vr${i}" ${x.ro ? "checked" : ""}> Read-only</label><button class="rmbtn press" data-act="rmvol:${i}" aria-label="Remove">${I("del")}</button></div>`).join("") || `<p class="muted" style="margin:4px 4px 8px">A name like <b>config</b> keeps it in the container's own folder; your files can be shared from /mnt, /srv, /media or /home.</p>`}
        <button class="pillbtn press" data-act="addvol">${I("add")} Add a folder</button></div>
      ${sec("Settings · one per line, NAME=value")}<div class="group glass cform"><textarea class="field" data-f="env" rows="4" placeholder="TZ=America/New_York&#10;PUID=1000" spellcheck="false">${esc(v.env)}</textarea></div>
      ${sec("Restart")}${group(["unless-stopped", "always", "on-failure", "no"].map(r => row({ "unless-stopped": "Unless I stop it", always: "Always", "on-failure": "Only if it crashes", no: "Never" }[r], { end: radio(v.restart === r), click: "restart:" + r })).join(""))}
      <div class="cact"><button class="pillbtn press" data-act="preview">Preview</button><button class="btn" data-act="create">${I("play")} Add and start</button></div>`, { title: "New container" });
    $$("[data-f]", ctx.root).forEach(el => el.oninput = el.onchange = () => {
      const k = el.dataset.f, val = el.type === "checkbox" ? el.checked : el.value;
      if (k === "name" || k === "image" || k === "env") v[k] = val;
      else { const i = +k.slice(2), t = k.slice(0, 2); if (t === "ph") v.ports[i].host = val; if (t === "pc") v.ports[i].container = val; if (t === "pu") v.ports[i].udp = val;
        if (t === "vh") v.volumes[i].host = val; if (t === "vc") v.volumes[i].container = val; if (t === "vr") v.volumes[i].ro = val; }
    });
  };
  ctx.handlers({
    addport: () => { v.ports.push({ host: "", container: "" }); draw(); }, rmport: i => { v.ports.splice(+i, 1); draw(); },
    addvol: () => { v.volumes.push({ host: "", container: "" }); draw(); }, rmvol: i => { v.volumes.splice(+i, 1); draw(); },
    browse: async i => { const f = await pickFolder("/mnt"); if (f) { v.volumes[+i].host = f; draw(); } },
    restart: r => { v.restart = r; draw(); },
    preview: async () => {
      try { const r = await post("/api/v1/containers/custom/check", { spec: spec() });
        await dialog("What Nova will run", "", [{ label: "Close", value: null }], `<pre class="code-block" style="white-space:pre-wrap;margin:0 16px">${esc(r.compose)}</pre>`); }
      catch (e) { toast(e.message); }
    },
    create: async () => {
      if (!isAdmin()) return viewOnly();
      try { const r = await post("/api/v1/containers/custom", { spec: spec() });
        if (r?.task) { toast("Downloading and starting it — follow along in the Inbox"); ctx.go("inbox"); } }
      catch (e) { toast(e.message); }
    },
  });
  draw();
}

// ── Labs: experimental features (off until you turn them on) ─────────────────────────────
export async function labs(ctx) {
  const draw = () => {
    const l = S.cache["/api/v1/labs"];
    ctx.show(`${note("Experimental features. They work, but haven't had as much use as the rest of Nova — so they're off until you turn them on.")}
      ${l ? group(Object.entries(l.about).map(([k, a]) => switchRow(a.name, a.about, !!l.labs[k], isAdmin() ? "lab:" + k : "", { blue: !!l.labs[k] })).join("")) : note("Loading…")}
      ${l && Object.values(l.labs).some(Boolean) ? sec("Set up") : ""}
      ${l ? group([
        l.labs.cloudflare_sync ? row("Cloudflare", { sub: "API token, and the addresses Nova has set up", blue: true, icon: "cloud", click: "go:cloudflare" }) : "",
        l.labs.auto_updates ? row("Weekly updates", { sub: sched ? `${DAYS[sched.day]}s at ${hh(sched.hour)}${sched.last ? " · last ran " + new Date(sched.last * 1000).toLocaleDateString() : ""}` : "…", blue: true, icon: "update", click: isAdmin() ? "sched" : "" }) : "",
        l.labs.image_cleanup ? row("Old images", { sub: "See what's taking space, and clean it up", blue: true, icon: "disk", click: "go:labs-images" }) : "",
        l.labs.wake_on_lan ? row("Wake-on-LAN", { sub: "Turn on other computers on your network", blue: true, icon: "power", click: "go:wol" }) : "",
      ].join("")) : ""}`, { title: "Labs" });
  };
  ctx.handlers({
    lab: async k => { try { const l = S.cache["/api/v1/labs"]; S.cache["/api/v1/labs"] = await post("/api/v1/labs", { [k]: !l.labs[k] }); } catch (e) { toast(e.message); } draw(); if (k === "auto_updates") loadSched(); },
    sched: async () => {
      let day = sched?.day ?? 6, hour = sched?.hour ?? 4;
      const p = dialog("When should it update?", "Pick a quiet time — each container is offline for a few seconds while it restarts.", [{ label: "Cancel", value: null }, { label: "Save", color: "var(--blue)", value: "save" }],
        `<div class="pad" style="display:flex;gap:10px"><select class="field" id="lsd">${DAYS.map((d, i) => `<option value="${i}"${i === day ? " selected" : ""}>${d}</option>`).join("")}</select>
         <select class="field" id="lsh">${[...Array(24).keys()].map(h => `<option value="${h}"${h === hour ? " selected" : ""}>${hh(h)}</option>`).join("")}</select></div>`);
      $("#lsd").onchange = e => day = +e.target.value; $("#lsh").onchange = e => hour = +e.target.value;
      if ((await p) !== "save") return;
      try { sched = await post("/api/v1/labs/schedule", { day, hour }); toast("Saved"); } catch (e) { toast(e.message); } draw();
    },
  });
  let sched = null;
  const loadSched = async () => { if (!S.cache["/api/v1/labs"]?.labs.auto_updates) return; try { sched = await get("/api/v1/labs/schedule"); } catch {} if (ctx.alive()) draw(); };
  draw(); await get("/api/v1/labs").catch(e => toast(e.message)); if (ctx.alive()) draw(); loadSched();
}
const DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
const hh = h => new Date(2000, 0, 1, h).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });

// Labs → Old images: what nothing uses anymore, and removing it
export async function labsImages(ctx) {
  let d = null, err = null;
  const load = async () => { try { d = await get("/api/v1/labs/images"); err = null; } catch (e) { err = e.message; } if (ctx.alive()) draw(); };
  const draw = () => {
    if (err) return ctx.show(note(err), { title: "Old images" });
    if (!d) return ctx.show(note("Looking through your images…"), { title: "Old images" });
    ctx.show(`${note(d.images.length ? `${d.images.length} image${d.images.length === 1 ? "" : "s"} that no container uses — mostly old versions left behind by updates. Removing them is safe: anything you install again is simply downloaded again.` : "Nothing to clean up — every image is in use.")}
      ${d.images.length ? group(d.images.map(e => row(e.tags[0] || (e.what ? `Old version of ${e.what}` : "Untagged image"), { sub: `${e.size}${e.created ? " · " + e.created : ""}${e.tags.length > 1 ? ` · +${e.tags.length - 1} more name${e.tags.length > 2 ? "s" : ""}` : ""}`, icon: "disk" })).join("")) : ""}
      ${d.kept ? note(`${d.kept} earlier version${d.kept === 1 ? " is" : "s are"} kept so you can roll back (Containers → Troubleshoot). Those stay.`) : ""}
      ${d.images.length && isAdmin() ? group(row("Clean up", { sub: `Removes these ${d.images.length}. You have ${bytes(d.free)} free now.`, icon: "del", tint: "var(--red)", click: "clean" })) : ""}`, { title: "Old images" });
  };
  ctx.handlers({ clean: async () => {
    if (!(await confirm(`Remove ${d.images.length} unused image${d.images.length === 1 ? "" : "s"}?`, "Images a container uses, and the versions kept for rolling back, stay.", "Clean up"))) return;
    try { const r = await post("/api/v1/labs/images/clean", {}); if (r?.task) ctx.go("task/" + r.task); } catch (e) { toast(e.message); }
  } });
  draw(); await load();
}

// Labs → Wake-on-LAN
export async function wol(ctx) {
  let d = null, err = null;
  const load = async () => { try { d = await get("/api/v1/labs/wol"); err = null; } catch (e) { err = e.message; } if (ctx.alive()) draw(); };
  const draw = () => {
    if (err) return ctx.show(note(err), { title: "Wake-on-LAN" });
    ctx.show(`${note("Turn on a computer that's asleep or off, from anywhere: the server sends it a wake-up packet on your network. Wake-on-LAN has to be turned on in that computer's BIOS (and its network settings) first.")}
      ${d ? group((d.devices.map(e => row(e.name, { sub: e.mac, icon: "power", click: "wake:" + e.id,
          end: isAdmin() ? `<button class="rmbtn press" data-act="rm:${esc(e.id)}" aria-label="Remove" title="Remove">${I("del")}</button>` : "" })).join("")) || row("No computers yet", { sub: "Add one with its MAC address" }))
        + (isAdmin() ? group(row("Add a computer", { icon: "add", blue: true, click: "add" })) : "") : note("Loading…")}`, { title: "Wake-on-LAN" });
  };
  ctx.handlers({
    wake: async id => { try { const r = await post(`/api/v1/labs/wol/${id}/wake`, {}); toast(r.note || "Sent"); } catch (e) { toast(e.message); } },
    rm: async id => { const e = d.devices.find(x => x.id === id); if (!(await confirm(`Remove ${e?.name}?`, "It only leaves this list.", "Remove"))) return;
      try { d = await post(`/api/v1/labs/wol/${id}/remove`, {}); } catch (e) { toast(e.message); } draw(); },
    add: async () => { let name = "", mac = "";
      const p = dialog("Add a computer", "Its MAC address is in its network settings (on Windows: ipconfig /all, “Physical Address”; on Linux: ip link).", [{ label: "Cancel", value: null }, { label: "Add", color: "var(--blue)", value: "add" }],
        `<div class="pad" style="display:grid;gap:10px"><input class="field" id="wn" placeholder="Name, like Gaming PC" maxlength="40"><input class="field" id="wm" placeholder="MAC, like 3c:7c:3f:12:34:56" autocomplete="off" spellcheck="false"></div>`);
      $("#wn").oninput = e => name = e.target.value; $("#wm").oninput = e => mac = e.target.value; $("#wn").focus();
      if ((await p) !== "add") return;
      try { d = await post("/api/v1/labs/wol", { name, mac }); } catch (e) { toast(e.message); } draw(); },
  });
  draw(); await load();
}

export async function cloudflare(ctx) {
  let st = null, err = null;
  const load = async () => { try { st = await get("/api/v1/cloudflare"); err = null; } catch (e) { err = e.message; } if (ctx.alive()) draw(); };
  const draw = () => {
    if (err) return ctx.show(note(err), { title: "Cloudflare" });
    if (!st) return ctx.show(note("Checking your Cloudflare account…"), { title: "Cloudflare" });
    const nova = st.nova_host, mine = (st.hostnames || []).filter(h => h !== nova);
    ctx.show(`${st.error ? `<div class="rcheck open">${I("warn")}<span>${esc(st.error)}</span></div>` : st.token ? `<div class="rcheck protected">${I("shield")}<span>Connected — new app addresses are protected by “${esc(st.access_app || "?")}”, the same login as Nova.</span></div>` : ""}
      ${sec("API token")}${group(row(st.token ? "Change the API token" : "Add an API token", { sub: "Kept on the server, readable only by root. Saving it asks for your fingerprint.", blue: true, icon: "key", click: "token" })
        + (st.token ? row("Remove the token", { icon: "del", tint: "var(--red)", click: "untoken" }) : ""))}
      <p class="note">Create one at dash.cloudflare.com → My Profile → API Tokens → Create Token → Custom, with: <b>Account · Cloudflare Tunnel · Edit</b>, <b>Account · Access: Apps and Policies · Edit</b>, <b>Zone · DNS · Edit</b> (for your domain).</p>
      ${st.token && !st.error ? sec("On your tunnel") + group(((st.hostnames || []).map(h => row(h, { sub: h === nova ? "Nova itself" : (st.protected || []).includes(h) ? "Behind your Access login" : "Not behind Nova's Access app — check it in Cloudflare",
          icon: h === nova ? "dns" : "cloud", tint: (st.protected || []).includes(h) || h === nova ? "var(--green)" : "var(--amber)",
          end: h === nova ? "" : `<button class="rmbtn press" data-act="unpub:${esc(h)}" aria-label="Take it off" title="Take it off">${I("del")}</button>` })).join("")) || row("Nothing yet", { sub: "Apps → hold an app → From anywhere…" })) : ""}
      ${note("To put an app online: Apps → hold (or right-click) an app → From anywhere… → Set it up for me.")}`, { title: "Cloudflare" });
  };
  ctx.handlers({
    token: async () => { let v = "";
      const p = dialog("Cloudflare API token", "Paste the token you created.", [{ label: "Cancel", value: null }, { label: "Save", color: "var(--blue)", value: "save" }], `<div class="pad"><input class="field" id="cft" type="password" autocomplete="off" placeholder="API token"></div>`);
      $("#cft").oninput = e => v = e.target.value.trim(); $("#cft").focus();
      if ((await p) !== "save" || !v) return;
      try { await post("/api/v1/cloudflare/token", { token: v }); toast("Saved"); } catch (e) { toast(e.message); } load(); },
    untoken: async () => { if (!(await confirm("Remove the API token?", "Addresses already set up keep working; Nova just can't add or remove them any more.", "Remove"))) return;
      try { await post("/api/v1/cloudflare/token", { token: "" }); } catch (e) { toast(e.message); } load(); },
    unpub: async h => { if (!(await confirm(`Take ${h} off the internet?`, "Nova removes its tunnel route and DNS record, then its Access entry. The app itself isn't touched.", "Take it off"))) return;
      try { await post("/api/v1/cloudflare/remove", { host: h }); toast(`${h} removed`); } catch (e) { toast(e.message); } load(); },
  });
  draw(); await load();
}
