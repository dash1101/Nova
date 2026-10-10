"""
Nova's file manager: everything runs as your normal account on the server (the same one the
terminal uses), never as root — so it can see and change exactly what you could when logged in.
Deleting moves things to that account's Trash (~/.local/share/Trash), so it can be undone.

Called by the root helper, which first drops to that account (drop()).
"""
import base64, json, mimetypes, os, pwd, grp, shutil, stat, time, urllib.parse

MAX_TEXT = 1024 * 1024            # open / save as text up to 1 MB


def drop(u):
    """Become the user `u` (pwd entry) for the rest of this process."""
    os.setgroups(os.getgrouplist(u.pw_name, u.pw_gid))
    os.setgid(u.pw_gid); os.setuid(u.pw_uid)
    os.environ.update(HOME=u.pw_dir, USER=u.pw_name, LOGNAME=u.pw_name)
    os.umask(0o022)

def norm(p, home):
    p = str(p or "").strip() or home
    if p.startswith("~"): p = home + p[1:]
    if not p.startswith("/") or "\0" in p: raise ValueError("a full path, like /home/you/file.txt")
    return os.path.normpath(p)

def name_ok(n):
    n = str(n or "").strip()
    if not n or n in (".", "..") or "/" in n or "\0" in n or len(n.encode()) > 255: raise ValueError("a name without / (up to 255 characters)")
    return n

def entry(dirpath, n):
    p = os.path.join(dirpath, n)
    try: st = os.lstat(p)
    except OSError: return None
    link = stat.S_ISLNK(st.st_mode)
    if link:
        try: st2 = os.stat(p); isdir = stat.S_ISDIR(st2.st_mode); size = st2.st_size
        except OSError: isdir, size = False, 0
    else: isdir, size = stat.S_ISDIR(st.st_mode), st.st_size
    try: owner = pwd.getpwuid(st.st_uid).pw_name
    except KeyError: owner = str(st.st_uid)
    return {"name": n, "dir": isdir, "link": link, "size": 0 if isdir else size, "mtime": int(st.st_mtime), "mode": stat.filemode(st.st_mode),
            "owner": owner, "hidden": n.startswith("."), "writable": os.access(p, os.W_OK), "mime": "" if isdir else (mimetypes.guess_type(n)[0] or "")}

def op_list(a, home):
    d = norm(a.get("path"), home)
    if not os.path.isdir(d): raise ValueError("that folder doesn't exist")
    try: names = os.listdir(d)
    except PermissionError: raise ValueError("you don't have permission to open that folder")
    items = [e for e in (entry(d, n) for n in names) if e]
    items.sort(key=lambda e: (not e["dir"], e["name"].lower()))
    du = shutil.disk_usage(d)
    return {"path": d, "parent": os.path.dirname(d) if d != "/" else None, "home": home, "items": items[:5000], "more": len(items) > 5000,
            "writable": os.access(d, os.W_OK), "free": du.free, "total": du.total}

def op_mkdir(a, home):
    d = norm(a.get("path"), home); n = name_ok(a.get("name")); p = os.path.join(d, n)
    if os.path.lexists(p): raise ValueError(f"“{n}” already exists here")
    os.mkdir(p); return {"ok": True, "path": p}

def op_new(a, home):
    d = norm(a.get("path"), home); n = name_ok(a.get("name")); p = os.path.join(d, n)
    if os.path.lexists(p): raise ValueError(f"“{n}” already exists here")
    text = str(a.get("text", ""))
    if len(text.encode()) > MAX_TEXT: raise ValueError("that's too much text for one file here")
    with open(p, "x") as f: f.write(text)
    return {"ok": True, "path": p}

def op_rename(a, home):
    src = norm(a.get("path"), home)
    if not os.path.lexists(src): raise ValueError("it isn't there any more")
    if a.get("to"): dst = norm(a.get("to"), home)
    else: dst = os.path.join(os.path.dirname(src), name_ok(a.get("name")))
    if os.path.isdir(dst) and not os.path.isdir(src): dst = os.path.join(dst, os.path.basename(src))   # moved into a folder
    if os.path.lexists(dst): raise ValueError(f"“{os.path.basename(dst)}” already exists there")
    if os.path.isdir(src) and (dst + "/").startswith(src.rstrip("/") + "/"): raise ValueError("a folder can't go inside itself")
    shutil.move(src, dst); return {"ok": True, "path": dst}

def op_copy(a, home):
    src = norm(a.get("path"), home); dst = norm(a.get("to"), home)
    if os.path.isdir(dst): dst = os.path.join(dst, os.path.basename(src))
    if os.path.lexists(dst):
        base, ext = os.path.splitext(dst); i = 2
        while os.path.lexists(f"{base} ({i}){ext}"): i += 1
        dst = f"{base} ({i}){ext}"
    if os.path.isdir(src): shutil.copytree(src, dst, symlinks=True)
    else: shutil.copy2(src, dst)
    return {"ok": True, "path": dst}

def op_trash(a, home):
    """Move to the Trash (freedesktop layout, so desktop file managers show and restore it too)."""
    out = []
    for raw in (a.get("paths") or [a.get("path")])[:500]:
        p = norm(raw, home)
        if not os.path.lexists(p): continue
        if p in ("/", home): raise ValueError("that can't be deleted")
        tdir = os.path.join(home, ".local/share/Trash")
        os.makedirs(f"{tdir}/files", exist_ok=True); os.makedirs(f"{tdir}/info", exist_ok=True)
        base = os.path.basename(p); n = base; i = 2
        while os.path.lexists(f"{tdir}/files/{n}") or os.path.lexists(f"{tdir}/info/{n}.trashinfo"): n = f"{base}.{i}"; i += 1
        with open(f"{tdir}/info/{n}.trashinfo", "w") as f:
            f.write(f"[Trash Info]\nPath={urllib.parse.quote(p)}\nDeletionDate={time.strftime('%Y-%m-%dT%H:%M:%S')}\n")
        try: shutil.move(p, f"{tdir}/files/{n}")
        except Exception:
            os.remove(f"{tdir}/info/{n}.trashinfo"); raise
        out.append(n)
    return {"ok": True, "trashed": len(out)}

def op_read(a, home):
    p = norm(a.get("path"), home)
    if os.path.getsize(p) > MAX_TEXT: raise ValueError("too big to open here — download it instead")
    raw = open(p, "rb").read()
    try: return {"path": p, "text": raw.decode("utf-8"), "mtime": int(os.path.getmtime(p))}
    except UnicodeDecodeError: raise ValueError("this isn't a text file — download it instead")

def op_write(a, home, data):
    p = norm(a.get("path"), home)
    if len(data) > MAX_TEXT: raise ValueError("too big to save here")
    if a.get("mtime") and os.path.exists(p) and int(os.path.getmtime(p)) != int(a["mtime"]): raise ValueError("it changed on the server since you opened it — reopen it to see the new version")
    tmp = p + ".nova-tmp"
    with open(tmp, "wb") as f: f.write(data)
    if os.path.exists(p): shutil.copymode(p, tmp)
    os.replace(tmp, p); return {"ok": True, "mtime": int(os.path.getmtime(p))}

def op_put(a, home, data):
    """Upload, in chunks: offset 0 creates the file (as name.part), the last chunk renames it into place."""
    d = norm(a.get("path"), home); n = name_ok(a.get("name")); final = os.path.join(d, n); part = final + ".nova-upload"
    off = int(a.get("offset", 0))
    if off == 0:
        if os.path.lexists(final) and not a.get("replace"): raise ValueError(f"“{n}” already exists here")
        open(part, "wb").close()
    if not os.path.exists(part) or os.path.getsize(part) != off: raise ValueError("the upload got out of step — try again")
    with open(part, "ab") as f: f.write(data)
    if a.get("last"): os.replace(part, final); return {"ok": True, "path": final, "done": True}
    return {"ok": True, "offset": off + len(data)}

OPS = {"list": op_list, "mkdir": op_mkdir, "new": op_new, "rename": op_rename, "copy": op_copy, "trash": op_trash, "read": op_read}
DATA_OPS = {"write": op_write, "put": op_put}
