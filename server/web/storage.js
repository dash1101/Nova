// Nova web — storage map, drive setup wizard, pools, background tasks, backups, diagnostics.
// Mirrors the app's Storage.kt / Backups.kt / Diagnostics.kt.
import { S, $, esc, get, post, del, bytes, isAdmin, signedFetch } from "./core.js";
import { I, row, group, sec, note, radio, switchRow, segmented, bar, usageColor, toast, dialog, confirm, wireCommon } from "./ui.js";

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
      + (t?.log?.length ? sec("What happened") + group(`<pre style="padding:18px;margin:0;white-space:pre-wrap;font-size:13px;color:var(--sub)">${esc(t.log.slice(-40).join("\n"))}</pre>`) : "")
      + (state === "running" && !["format", "combine", "raid", "pool-remove", "pool-add"].includes(kind) && isAdmin() ? `<div style="padding:18px 22px"><button class="btn" style="width:100%;background:var(--card);color:var(--text)" data-act="stop" ${stopping ? "disabled" : ""}>${stopping ? "Stopping…" : "Stop"}</button></div>` : "")
      + (state === "running" ? note("You can leave this page — it keeps going on the server, and shows under Storage & hardware.") : ""), { title: t?.title || "Working…" });
    if (t?.samples?.length > 1) chart($("#chart"), $("#leg"), t.samples);
  };
  ctx.handlers({
    stop: async () => { stopping = true; draw(); try { await post(`/api/v1/tasks/${id}/stop`); } catch (e) { toast(e.message); } },
    force: () => startTask(ctx, `/api/v1/backups/${t.key}/run`, { force: true }),
    nextbackup: () => ctx.go(`backup-edit/new//${enc(then)}`),
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
      + group(`<div style="padding:16px;display:flex;gap:8px">${field("custom", "", "Another folder, e.g. /srv/music")}<button class="pillbtn press" data-act="addsrc">Add</button></div>`);
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

