// Nova web — partitions: a drive's partition table, edited the way KDE Partition Manager does it. Pick a partition or
// free space, queue up changes, see the drive as it will be, then apply them all at once (approved on a phone).
// The server checks every change against the real drive; nothing here is trusted.
import { S, $, $$, esc, get, post, isAdmin, bytes } from "./core.js";
import { I, row, group, sec, note, links, toast, confirm, choose, ask, wireCommon } from "./ui.js";
import * as ST from "./storage.js";

const MiB = 1 << 20;
const FS_COLOR = { ext4: "#3e91ff", xfs: "#bf5af2", btrfs: "#30b0c7", exfat: "#ff9f0a", vfat: "#34c759", swap: "#ff453a", ntfs: "#ffd60a" };
const FS_NEW = [["ext4", "ext4", "Linux's everyday filesystem — the safe choice"], ["xfs", "XFS", "Good for very large files; can grow but never shrink"],
  ["btrfs", "btrfs", "Snapshots and checksums"], ["exfat", "exFAT", "Readable by Windows and Macs too"], ["vfat", "FAT32", "For an EFI system partition, or old devices"],
  ["swap", "Swap", "Extra memory on disk"], ["none", "Leave it empty", "Just the partition, no filesystem"]];
const FLAG_NAMES = { none: "Normal data partition", esp: "EFI system partition", bios_grub: "BIOS boot", raid: "RAID member", lvm: "LVM member" };
const mib = (sectors, ss) => Math.floor(sectors * ss / MiB);

export async function partitions(ctx) {
  const serial = ctx.args[0];
  let lay = null, ops = [], planned = null, sel = null, err = "";
  const load = async () => { lay = await get(`/api/v1/drives/${encodeURIComponent(serial)}/partitions`); ops = []; planned = null; sel = null; };
  const replan = async () => {
    if (!ops.length) { planned = null; return true; }
    try { planned = await post(`/api/v1/drives/${encodeURIComponent(serial)}/partitions/plan`, { ops }); err = ""; return true; }
    catch (e) { ops.pop(); toast(e.message); return false; }
  };
  const add = async op => { ops.push(op); if (await replan()) { sel = null; draw(); } };
  const view = () => planned?.result || lay;

  const bar = v => {
    const items = [...v.partitions.map(p => ({ ...p, kind: "p" })), ...(v.gaps || []).map((g, i) => ({ ...g, kind: "g", i }))].sort((a, b) => a.start - b.start);
    if (!items.length) return `<div class="pbar"><div class="pseg gap" style="flex:1">${v.table ? "Free space" : "No partition table"}</div></div>`;
    const total = v.sectors || 1;
    return `<div class="pbar">${items.map(x => {
      const frac = (x.kind === "p" ? x.end - x.start + 1 : x.end - x.start + 1) / total, key = x.kind === "p" ? "p" + x.n : "g" + x.i;
      const c = x.kind === "p" ? (FS_COLOR[x.fs] || "var(--sub)") : "";
      return `<button class="pseg ${x.kind === "g" ? "gap" : ""}${sel === key ? " on" : ""}" data-act="sel:${key}" style="flex:${Math.max(frac, 0.025).toFixed(4)} 1 0;${c ? `--pc:${c}` : ""}"
        title="${esc(x.kind === "p" ? `${x.n} · ${x.fs || "empty"} · ${bytes(x.size)}` : `Free · ${bytes(x.size)}`)}"><span>${esc(x.kind === "p" ? (x.label || x.fs || "#" + x.n) : "free")}</span></button>`;
    }).join("")}</div>`;
  };
  const table = v => {
    const items = [...v.partitions.map(p => ({ ...p, kind: "p" })), ...(v.gaps || []).map((g, i) => ({ ...g, kind: "g", i }))].sort((a, b) => a.start - b.start);
    if (!items.length) return "";
    return group(items.map(x => {
      const key = x.kind === "p" ? "p" + x.n : "g" + x.i;
      if (x.kind === "g") return row("Free space", { sub: bytes(x.size), icon: "addc", tint: "var(--sub)", click: lay.editable && isAdmin() ? "sel:" + key : "", end: sel === key ? `<span style="color:var(--blue)">${I("okc")}</span>` : "" });
      const used = x.used != null && x.size ? ` · ${bytes(x.used)} used` : "";
      const bits = [x.fs || "no filesystem", bytes(x.size) + used, x.flag && x.flag !== "none" ? FLAG_NAMES[x.flag] : "", (x.mounts || []).length ? "mounted at " + x.mounts.join(", ") : "", x.new ? "new" : ""].filter(Boolean);
      return row(`${x.n} · ${x.label || x.name || (x.node || "").replace("/dev/", "") || "partition"}`, { sub: bits.join(" · "), icon: x.fs === "swap" ? "mem" : "disk",
        tint: FS_COLOR[x.fs] || "var(--sub)", click: lay.editable && isAdmin() ? "sel:" + key : "", end: sel === key ? `<span style="color:var(--blue)">${I("okc")}</span>` : "" });
    }).join(""));
  };
  const actions = v => {
    if (!lay.editable || !isAdmin()) return "";
    const rows = [];
    if (!v.table) rows.push(row("New partition table", { sub: "GPT (recommended) or MBR — the drive starts empty", icon: "add", tint: "var(--blue)", click: "table" }));
    else if (sel?.startsWith("g")) {
      const g = v.gaps[+sel.slice(1)];
      if (g) rows.push(row("Create a partition here", { sub: `Up to ${bytes(g.size)}`, icon: "addc", tint: "var(--green)", click: "create" }));
    } else if (sel?.startsWith("p")) {
      const p = v.partitions.find(x => "p" + x.n === sel);
      if (p) {
        rows.push(row("Format", { sub: "A new, empty filesystem — what's on it now is erased", icon: "disk", tint: "var(--amber)", click: "format" }));
        if (p.fs && p.fs !== "none") rows.push(row("Label", { sub: p.label ? `Now “${p.label}”` : "Give it a name", icon: "edit", tint: "var(--blue)", click: "label" }));
        if (!p.new) rows.push(row("Resize", { sub: p.fs === "xfs" ? "XFS can grow into free space right after it, never shrink" : p.fs && !["ext4", "btrfs", "xfs"].includes(p.fs) ? `Nova can't resize ${p.fs}` : "Grow into the free space right after it, or shrink (ext4) — the start stays put", icon: "full", tint: "var(--blue)", click: "resize" }));
        rows.push(row("Flag", { sub: FLAG_NAMES[p.flag || "none"], icon: "tune", tint: "var(--blue)", click: "flag" }));
        rows.push(row("Delete", { sub: "Its files are gone (until something new is made there, Restore can bring it back)", icon: "del", tint: "var(--red)", click: "del" }));
      }
    } else rows.push(row("Pick a partition or free space", { sub: "Tap it in the bar or the list to see what you can do" }));
    if (v.table) rows.push(row("New partition table", { sub: "Erases the whole drive and starts again", icon: "add", tint: "var(--red)", click: "table" }));
    return sec("Change") + group(rows.join(""));
  };
  const pending = () => {
    if (!ops.length) return "";
    return sec(`Waiting to apply · ${ops.length}`) + group((planned?.steps || []).map((s, i) => row(s, { icon: i === ops.length - 1 ? "back" : "history", tint: "var(--amber)", click: i === ops.length - 1 ? "undo" : "" })).join(""))
      + `<div style="display:flex;gap:10px;margin:8px var(--gutter)"><button class="btn ghost" style="flex:1" data-act="clear">Clear</button><button class="btn" style="flex:2" data-act="apply" ${lay.editable ? "" : "disabled"}>Apply ${ops.length} change${ops.length > 1 ? "s" : ""}</button></div>`;
  };
  const draw = () => {
    if (!lay) return ctx.show(note(err || "Reading the drive…"), { title: "Partitions" });
    const v = view();
    ctx.show(`<div class="pttop"><b>${esc(lay.model || lay.name)}</b><span class="muted"> · ${esc(lay.size_text)} · ${lay.table ? (lay.table === "gpt" ? "GPT" : "MBR") : "no partition table"} · /dev/${esc(lay.name)}</span></div>
      ${planned ? `<p class="note" style="margin-top:2px">Showing the drive as it will be after your changes.</p>` : ""}
      ${bar(v)}
      ${lay.busy?.length ? note("Read only: " + lay.busy.join("; ") + ".") : !isAdmin() ? note("View-only access: an admin can change partitions.") : ""}
      ${sec("Partitions")}${table(v) || note("Nothing on it yet.")}
      ${actions(v)}
      ${pending()}
      ${lay.backups?.length && lay.editable && isAdmin() ? sec("Undo") + group(row("Restore the previous table", { sub: `Saved before the last change (${esc(lay.backups[0].replace(/^.*--/, "").replace(/\.sfdisk$/, ""))}) — filesystems come back if nothing new was written over them`, icon: "history", tint: "var(--blue)", click: "restore" })) : ""}
      ${note("Nova doesn't move partitions (where one starts stays the same): it means copying every byte, and an interruption halfway loses it. Changes are applied together, after you approve them on your phone; the old table is saved first.")}`,
      { title: "Partitions" });
    wireCommon(ctx.root);
  };
  const pick = () => sel?.startsWith("p") ? view().partitions.find(x => "p" + x.n === sel) : null;
  ctx.handlers({
    sel: k => { sel = sel === k ? null : k; draw(); },
    table: async () => {
      const t = await choose("New partition table", [["gpt", "GPT", "Recommended — any size, up to 128 partitions"], ["dos", "MBR", "For old computers — up to 4 partitions, 2 TB"]], null);
      if (t && await confirm("Erase the whole drive?", "A new partition table replaces everything on it, once you apply.", "New table")) add({ op: "table", type: t });
    },
    create: async () => {
      const g = view().gaps[+sel.slice(1)]; if (!g) return;
      const fs = await choose("Filesystem", FS_NEW, null); if (!fs) return;
      const max = mib(g.end - g.start + 1, view().sector);
      const sz = await ask(`Size in MiB (up to ${max})`, String(max), String(max), "Next", 12); if (sz == null) return;
      const n = Math.min(max, Math.max(1, parseInt(sz, 10) || 0));
      const label = fs === "none" ? "" : await ask("Label (optional)", fs === "vfat" ? "EFI" : "data", "", "Add", 16); if (label == null) return;
      add({ op: "create", start_mib: mib(g.start, view().sector), size_mib: n, fs, label });
    },
    format: async () => {
      const p = pick(); if (!p) return;
      const fs = await choose(`Format partition ${p.n}`, FS_NEW.filter(f => f[0] !== "none"), null); if (!fs) return;
      const label = await ask("Label (optional)", "data", p.label || "", "Add", 16); if (label == null) return;
      add({ op: "format", n: p.n, fs, label });
    },
    label: async () => { const p = pick(); if (!p) return; const l = await ask(`Label partition ${p.n}`, "data", p.label || "", "Save", 16); if (l != null) add({ op: "label", n: p.n, label: l }); },
    flag: async () => {
      const p = pick(); if (!p) return;
      const f = await choose(`Partition ${p.n} is…`, Object.entries(FLAG_NAMES).filter(([k]) => k !== "bios_grub" || view().table === "gpt").map(([k, l]) => [k, l]), p.flag || "none");
      if (f && f !== p.flag) add({ op: "flag", n: p.n, flag: f });
    },
    resize: async () => {
      const p = pick(); if (!p) return; const v = view();
      const next = Math.min(...v.partitions.filter(q => q.start > p.start).map(q => q.start), v.last + 1);
      const maxMib = mib(next - p.start, v.sector), cur = mib(p.end - p.start + 1, v.sector);
      const sz = await ask(`New size in MiB (now ${cur}, up to ${maxMib})`, String(cur), String(cur), "Next", 12); if (sz == null) return;
      add({ op: "resize", n: p.n, size_mib: parseInt(sz, 10) || 0 });
    },
    del: async () => { const p = pick(); if (p && await confirm(`Delete partition ${p.n}?`, `${p.label || p.fs || "It"} (${bytes(p.size)}) goes, once you apply.`, "Delete")) add({ op: "delete", n: p.n }); },
    undo: async () => { ops.pop(); await replan(); draw(); },
    clear: async () => { ops = []; planned = null; draw(); },
    apply: async () => {
      const typed = await ask(`Type the drive's serial to apply`, lay.serial, "", "Apply", 64); if (typed == null) return;
      if (typed !== lay.serial) return toast("That isn't this drive's serial");
      ST.startTask(ctx, "/api/v1/storage/task/partition-apply", { spec: { serial: lay.serial, confirm: [typed], fingerprint: planned?.fingerprint || lay.fingerprint, ops } });
    },
    restore: async () => {
      if (!(await confirm("Restore the previous table?", "The partition table saved before the last change goes back on the drive (the current one is saved first). Filesystems on the old partitions come back if nothing new was written over them.", "Restore"))) return;
      const typed = await ask(`Type the drive's serial to restore`, lay.serial, "", "Restore", 64); if (typed == null) return;
      if (typed !== lay.serial) return toast("That isn't this drive's serial");
      ST.startTask(ctx, "/api/v1/storage/task/partition-restore", { spec: { serial: lay.serial, confirm: [typed], backup: lay.backups[0] } });
    },
  });
  draw();
  try { await load(); } catch (e) { err = e.message; }
  if (ctx.alive()) draw();
}
