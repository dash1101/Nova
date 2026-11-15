"""
A backup is only as good as its restore. For every kind of destination Nova backs up to — a drive, a drive that can't
hard-link (FAT, as most USB sticks are), an SMB share, an NFS export — this backs real files up, changes them, backs up
again, destroys the originals, restores, and compares what came back byte for byte.

    sudo python3 restore_drill.py            (on a throwaway machine: it makes loop drives and a local Samba / NFS server)

Run by .github/workflows/linux.yml on a fresh Ubuntu machine with Nova installed. Exits 1 if anything is off.
"""
import hashlib, json, os, shutil, subprocess, sys, time

sys.path.insert(0, "/usr/lib/nova-api")
import backups  # noqa: E402

fails, skipped = [], []
W = "/mnt/nova-drill"

def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + (f"   [{str(detail)[:500]}]" if detail and not ok else ""), flush=True)
    if not ok: fails.append(name)

def sh(cmd, check_=False, timeout=600):
    r = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True, timeout=timeout)
    if check_ and r.returncode != 0: raise RuntimeError(f"{cmd}: {(r.stderr or r.stdout).strip()[-300:]}")
    return r.returncode, r.stdout, r.stderr

def loop_drive(name, size_mb, mkfs, opts=""):
    """A small drive of its own (a file, formatted and mounted) at /mnt/nova-drill/<name>."""
    img, mp = f"{W}/{name}.img", f"{W}/{name}"
    os.makedirs(mp, exist_ok=True)
    sh(f"truncate -s {size_mb}M {img} && {mkfs} {img}", check_=True)
    sh(f"mount -o loop{',' + opts if opts else ''} {img} {mp}", check_=True)
    return mp

def tree(root):
    """{relative path: sha256} of every file under root (what "the same files" means here)."""
    out = {}
    for d, _, files in os.walk(root):
        for f in files:
            p = os.path.join(d, f)
            if os.path.islink(p): out[os.path.relpath(p, root)] = "link:" + os.readlink(p); continue
            h = hashlib.sha256()
            with open(p, "rb") as fh:
                for b in iter(lambda: fh.read(1 << 20), b""): h.update(b)
            out[os.path.relpath(p, root)] = h.hexdigest()
    return out

def fill(src):
    """A small household of files: text, a few megabytes of data, folders within folders, awkward names."""
    os.makedirs(f"{src}/photos/2024/summer", exist_ok=True); os.makedirs(f"{src}/documents/taxes", exist_ok=True); os.makedirs(f"{src}/empty folder", exist_ok=True)
    open(f"{src}/documents/letter.txt", "w").write("Dear Nova,\nthe first version.\n")
    open(f"{src}/documents/taxes/2024 return (final).txt", "w").write("numbers\n" * 2000)
    open(f"{src}/documents/naïve café.txt", "w").write("names with accents\n")
    open(f"{src}/documents/to-delete.txt", "w").write("this one gets deleted before the second backup\n")
    for i in range(3):
        with open(f"{src}/photos/2024/summer/IMG_{i:04}.raw", "wb") as f: f.write(os.urandom(1_500_000))
    open(f"{src}/documents/notes 12:30 (a name Windows drives can't hold).txt", "w").write("a colon in the name\n")
    os.symlink("letter.txt", f"{src}/documents/latest letter")
    os.chmod(f"{src}/documents/letter.txt", 0o600)
    os.utime(f"{src}/photos/2024/summer/IMG_0001.raw", (TAKEN, TAKEN))
    mine(src)

TAKEN = 1720094400                                    # 4 July 2024, noon UTC: when "the photo was taken"
ODD = "documents/notes 12:30 (a name Windows drives can't hold).txt"

def mine(src):
    """The files belong to an ordinary person (uid 1000), as real ones do — not to root."""
    for d, dirs, files in os.walk(src):
        for n in [d] + [os.path.join(d, f) for f in files]: os.chown(n, 1000, 1000, follow_symlinks=False)

def drill(key, kind, dest, links=True, perms=True, plain=False):
    """The whole exercise against one destination. links: unchanged files should cost nothing the second time.
    plain: a Windows-style drive — no symbolic links, and no colons in names (the backup must say so, and keep the rest)."""
    print(f"\n{kind}")
    src = f"{W}/src/{key}"; shutil.rmtree(src, ignore_errors=True); os.makedirs(src)
    fill(src)
    try: job = backups.put_job({"name": "Drill " + key, "sources": [src], "dest": dest, "schedule": {"manual": True}, "notify": "never", "guard_pct": 20})
    except Exception as e: check("the backup can be set up", False, e); return
    check("the backup can be set up", True)
    quiet = lambda *a, **k: None
    cant = {ODD, "documents/latest letter"} if plain else set()
    v1 = {k: v for k, v in tree(src).items() if k not in cant}
    try: r1 = backups.run_job(job, log=quiet)
    except Exception as e: check("first backup runs", False, e); return
    mode = r1.get("mode"); print("      mode:", mode, "·", r1.get("stats", {}))
    if plain:
        check("first backup runs, and counts even though one name can't be written to this kind of drive", r1.get("partial") and not r1.get("error"), r1)
        check("…it says which file, in words", any("couldn't be copied" in x and "notes 12:30" in x for x in r1.get("skipped", [])), r1.get("skipped"))
        check("…and that the link was left out", r1.get("stats", {}).get("left_out", 0) >= 1, r1.get("stats"))
    else: check("first backup runs", r1.get("ok") and not r1.get("skipped"), r1)
    # life goes on: one file edited, one deleted, one added
    time.sleep(1.2)
    open(f"{src}/documents/letter.txt", "w").write("Dear Nova,\nthe SECOND version, edited after the first backup.\n")
    os.remove(f"{src}/documents/to-delete.txt")
    open(f"{src}/documents/new since the first backup.txt", "w").write("added later\n")
    mine(src)
    v2 = {k: v for k, v in tree(src).items() if k not in cant}
    try: r2 = backups.run_job(job, log=quiet)
    except Exception as e: check("second backup runs", False, e); return
    check("second backup runs", r2.get("ok") or (plain and r2.get("partial") and not r2.get("error")), r2)
    check("…and copies only what changed (two files), not everything again", r2.get("stats", {}).get("transferred", 99) <= (3 if plain else 2), r2.get("stats"))
    snaps = backups.list_snapshots(job, log=quiet); ids = [s["id"] for s in snaps]
    check("both points in time can be picked for a restore", len(ids) >= 2, ids)
    dated = sorted(i for i in ids if i != "current")
    newest = "current" if mode == "mirror" else dated[-1]
    b = backups.browse(job, newest, src + "/documents", log=quiet)
    check("the newest backup can be browsed", any(i["name"] == "letter.txt" for i in b.get("items", [])) and not any(i["name"] == "to-delete.txt" for i in b.get("items", [])), b)
    if links and mode == "snapshots":
        with backups.Dest(job, quiet) as folder:
            a, c = (os.stat(os.path.join(folder, s) + src + "/photos/2024/summer/IMG_0001.raw") for s in dated[-2:])
        check("a file that didn't change takes no extra space in the second snapshot (the same data, linked)", a.st_ino == c.st_ino and a.st_nlink >= 2, (a.st_ino, c.st_ino))

    # the bad day: everything in the source is gone
    for n in os.listdir(src): p = os.path.join(src, n); shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)
    try: rr = backups.restore(job, newest, src, "original", log=quiet); back = tree(src)
    except Exception as e: check("everything comes back after the source is wiped", False, e); back = {}; rr = {}
    check("everything comes back after the source is wiped — the same files, byte for byte", back == v2 and bool(back),
          {"missing": sorted(set(v2) - set(back))[:5], "extra": sorted(set(back) - set(v2))[:5], "different": [k for k in v2 if k in back and back[k] != v2[k]][:5]})
    if perms: check("…with its permissions (a private file stays private)", os.path.exists(f"{src}/documents/letter.txt") and os.stat(f"{src}/documents/letter.txt").st_mode & 0o777 == 0o600,
                    oct(os.stat(f"{src}/documents/letter.txt").st_mode) if os.path.exists(f"{src}/documents/letter.txt") else "missing")
    check("…and its empty folders", os.path.isdir(f"{src}/empty folder"))
    pic = f"{src}/photos/2024/summer/IMG_0001.raw"
    check("…with its dates (a photo keeps the day it was taken)", os.path.exists(pic) and abs(os.stat(pic).st_mtime - TAKEN) <= 2, os.path.exists(pic) and os.stat(pic).st_mtime)
    theirs = [p_ for p_ in (pic, f"{src}/documents/letter.txt", f"{src}/documents", f"{src}/photos/2024") if os.path.exists(p_)]
    check("…and it belongs to its owner again, not to root", theirs and all(os.stat(p_).st_uid == 1000 for p_ in theirs), [(p_, os.stat(p_).st_uid) for p_ in theirs])

    # an older point in time: the file deleted before the second backup, and the first version of the edited one
    older = [i for i in ids if i not in (newest,)]
    got_deleted = got_first = False; tried = []
    for s in older:
        for rel, want in ((src + "/documents/to-delete.txt", v1["documents/to-delete.txt"]), (src + "/documents/letter.txt", v1["documents/letter.txt"])):
            try:
                t = backups.restore(job, s, rel, "beside", log=quiet)["target"]
                if os.stat(os.path.dirname(t)).st_uid != 1000: tried.append((s, "the restored- folder belongs to", os.stat(os.path.dirname(t)).st_uid)); continue
                ok = os.path.isfile(t) and tree(os.path.dirname(t)).get(os.path.basename(t)) == want
                tried.append((s, os.path.basename(rel), ok))
                if ok and rel.endswith("to-delete.txt"): got_deleted = True
                if ok and rel.endswith("letter.txt"): got_first = True
            except Exception as e: tried.append((s, os.path.basename(rel), str(e)[:80]))
    check("a file deleted before the last backup can still be brought back from an earlier one", got_deleted, tried)
    check("the earlier version of an edited file can be brought back next to the current one", got_first, tried)
    check("…without touching the current one", tree(src).get("documents/letter.txt") == v2["documents/letter.txt"])

    # "put it back where it was" never overwrites: a file that's there stays as it is, and the result says so
    open(f"{src}/documents/letter.txt", "w").write("edited again after the restore\n")
    os.remove(f"{src}/documents/naïve café.txt")
    try:
        r3 = backups.restore(job, newest, src + "/documents", "original", log=quiet)
        check("putting a folder back where it was restores what's missing and leaves what's there alone",
              open(f"{src}/documents/letter.txt").read().startswith("edited again") and os.path.exists(f"{src}/documents/naïve café.txt"), r3)
        check("…and says how many files it put back and how many it left alone", r3.get("restored") == 1 and r3.get("left_alone", 0) >= 1, r3)
    except Exception as e: check("putting a folder back where it was", False, e)

    # a drive that dropped out looks like an empty folder: that must not replace the good backup
    for n in os.listdir(src): p = os.path.join(src, n); shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)
    try: backups.run_job(job, log=quiet); refused = False
    except Exception as e: refused = "empty now" in str(e) or "nothing to back up" in str(e)
    after = [s["id"] for s in backups.list_snapshots(job, log=quiet)]
    b2 = backups.browse(job, newest, src + "/documents", log=quiet)
    check("an emptied source (a dropped drive) is not backed up over the good copy", refused and any(i["name"] == "letter.txt" for i in b2.get("items", [])), (refused, after))
    backups.delete_job(job["id"])

def main():
    if os.geteuid() != 0: sys.exit("run with sudo, on a throwaway machine")
    only = set(filter(None, os.environ.get("NOVA_DRILL_ONLY", "").split(",")))       # e.g. ext4,exfat — the rest are left out
    need = set(filter(None, os.environ.get("NOVA_DRILL_NEED", "").split(",")))       # these may not be skipped (CI)
    os.makedirs(W, exist_ok=True)
    if not os.environ.get("NOVA_DRILL_NO_APT"): sh("apt-get install -y -qq dosfstools exfatprogs rsync samba cifs-utils nfs-kernel-server >/dev/null 2>&1", timeout=900)
    loop_drive("src", 200, "mkfs.ext4 -q -F")

    def attempt(key, title, setup):
        """setup() gets the destination ready and returns drill()'s arguments; an OSError from it means "this machine can't"."""
        if only and key not in only: return
        try: dest, kw = setup()
        except OSError as e:
            print(f"\n{title}\n  (not run: {e})"); skipped.append(f"{key}: {e}")
            if key in need: check(f"{key} drill could be run here", False, e)
            return
        except Exception as e: check(f"{key} drill could be set up", False, e); return
        try: drill(key, title, dest, **kw)
        except Exception as e: check(f"{key} drill ran to the end", False, repr(e))

    def odd_drive(name, mkfs, opts):
        try: return loop_drive(name, 300, mkfs, opts)
        except RuntimeError as e: raise OSError(str(e))            # no such filesystem in this kernel

    attempt("ext4", "Drive (ext4: dated snapshots, unchanged files linked)", lambda: ({"type": "local", "path": loop_drive("ext4", 300, "mkfs.ext4 -q -F")}, {}))
    # a drive formatted to work with Windows and Macs, mounted the way Nova mounts one: no links, no permissions
    attempt("exfat", "Drive that also works on Windows and Mac (exFAT: one copy plus the earlier versions of what changed)",
            lambda: ({"type": "local", "path": odd_drive("exfat", "mkfs.exfat -q", "noatime,uid=1000,gid=1000,umask=002")}, {"links": False, "perms": False, "plain": True}))
    attempt("fat", "USB stick as it comes from the shop (FAT32)",
            lambda: ({"type": "local", "path": odd_drive("fat", "mkfs.vfat -F 32", "noatime,uid=1000,gid=1000,umask=002")}, {"links": False, "perms": False, "plain": True}))

    def smb():                                                   # an SMB share on this machine (what most home NAS boxes speak)
        if sh("modprobe cifs")[0] != 0: raise OSError("this machine's kernel has no SMB client (cifs)")
        share = loop_drive("smbshare", 300, "mkfs.ext4 -q -F")
        sh("id drill >/dev/null 2>&1 || useradd -M -s /usr/sbin/nologin drill", check_=True); sh(f"chown drill {share}")
        sh("(echo drillpass; echo drillpass) | smbpasswd -s -a drill", check_=True)
        with open("/etc/samba/smb.conf", "a") as f: f.write(f"\n[drill]\n path = {share}\n read only = no\n valid users = drill\n force user = drill\n")
        sh("systemctl restart smbd", check_=True); time.sleep(2)
        return {"type": "smb", "host": "127.0.0.1", "share": "drill", "user": "drill", "password": "drillpass"}, {"perms": False}
    attempt("smb", "NAS (SMB share)", smb)

    def nfs():                                                   # an NFS export on this machine
        exp = loop_drive("nfsexport", 300, "mkfs.ext4 -q -F")
        with open("/etc/exports", "a") as f: f.write(f"{exp} 127.0.0.1(rw,sync,no_root_squash,no_subtree_check,fsid=77)\n")
        if sh("systemctl restart nfs-kernel-server && exportfs -ra")[0] != 0: raise OSError("this machine can't run an NFS server")
        time.sleep(2)
        return {"type": "nfs", "host": "127.0.0.1", "share": exp.strip("/")}, {}
    attempt("nfs", "NAS (NFS export)", nfs)

    def nfs_squash():                                            # …the way most NAS boxes export: whoever connects becomes one account
        exp = loop_drive("nfssquash", 300, "mkfs.ext4 -q -F"); os.chown(exp, 65534, 65534)
        with open("/etc/exports", "a") as f: f.write(f"{exp} 127.0.0.1(rw,sync,all_squash,anonuid=65534,anongid=65534,no_subtree_check,fsid=78)\n")
        if sh("exportfs -ra")[0] != 0: raise OSError("this machine can't run an NFS server")
        time.sleep(1)
        return {"type": "nfs", "host": "127.0.0.1", "share": exp.strip("/")}, {"perms": False}
    attempt("nfs-squash", "NAS (NFS export that maps everyone to one account)", nfs_squash)

    print(f"\n{'ALL RESTORES VERIFIED' if not fails else str(len(fails)) + ' FAILED: ' + '; '.join(fails)}" + (f"   (not run here: {'; '.join(skipped)})" if skipped else ""))
    print(json.dumps({"failed": fails, "skipped": skipped}))
    sys.exit(1 if fails else 0)

if __name__ == "__main__":
    main()
