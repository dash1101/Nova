"""
Nova's own updates on Windows and macOS: the newest release, checked the same way `nova-update` checks it on Linux — the
list of checksums must carry a valid signature from Nova's release key (shipped next to this file), and the download
must match its signed checksum — then installed by that release's own installer (on Windows in a window of its own: it
restarts Nova and closes whatever runs from Nova's folder, this included). Settings and paired devices stay, as with
any install over an install.

    from the Nova Server window: Check for updates → Update now          nova update   (as administrator / with sudo)
"""
import base64, hashlib, json, os, re, subprocess, sys, tarfile, tempfile, urllib.error, urllib.request, zipfile

MAC = sys.platform == "darwin"

HERE = os.path.dirname(os.path.realpath(__file__))
REPO = "dash1101/Nova"                                   # where Nova's signed releases are published
KEY = next((p for p in (os.path.join(HERE, "release-key.pub"), os.path.join(HERE, "..", "..", "packaging", "release-key.pub")) if os.path.exists(p)), None)
ZIP = "nova-macos.tar.gz" if MAC else "nova-windows.zip"          # this system's download in every release


class UpdateError(Exception):
    pass


def _fetch(url, limit=80 << 20):
    req = urllib.request.Request(url, headers={"User-Agent": "nova-update", "Accept": "application/octet-stream"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r: data = r.read(limit + 1)
    except urllib.error.HTTPError as e: raise UpdateError(f"GitHub answered {e.code}" + (" — no release published yet?" if e.code == 404 else " — try again in a few minutes"))
    except (urllib.error.URLError, OSError) as e: raise UpdateError(f"couldn't reach GitHub ({getattr(e, 'reason', e)}) — is this computer online?")
    if len(data) > limit: raise UpdateError("the download is larger than a Nova release can be")
    return data


def _key(v):
    """0.9.22-alpha < 0.9.22 < 0.9.23-alpha (a "-word" part is a pre-release, as "~word" is on Linux)."""
    out = []
    for part in re.split(r"[.+]", re.sub(r"-(?=[A-Za-z])", "~", (v or "").strip().lstrip("v"))):
        main, _, pre = part.partition("~")
        out.append((int(main) if main.isdigit() else 0, 0 if pre else 1, pre))
    return out


def installed():
    try:
        with open(os.path.join(HERE, "VERSION"), encoding="utf-8") as f: return f.read().strip()
    except OSError: return ""


# The newest release's files, by their download links. (Not GitHub's API: it lets one address ask only 60 times an hour —
# everyone behind that address together — and these links say the same without that limit.)
BASE = f"https://github.com/{REPO}/releases/latest/download/"

def latest():
    """(version, {file name: checksum}) of the newest release, read from its list of checksums — after checking that the
    list carries a valid signature from Nova's release key."""
    if not KEY: raise UpdateError("Nova's release key isn't installed here, so an update can't be checked — install the newest release by hand")
    sums, sig = _fetch(BASE + "SHA256SUMS", 1 << 20), _fetch(BASE + "SHA256SUMS.sig", 1 << 16)
    from cryptography.hazmat.primitives import serialization
    from cryptography.exceptions import InvalidSignature
    try: serialization.load_pem_public_key(open(KEY, "rb").read()).verify(base64.b64decode(sig.strip()), sums)
    except (InvalidSignature, ValueError): raise UpdateError("the release's signature is NOT valid — not installing it")
    listed = {m.group(2): m.group(1) for m in (re.fullmatch(r"([0-9a-f]{64})\s+\*?(\S+)", l.strip()) for l in sums.decode("utf-8", "replace").splitlines()) if m}
    ver = next((n[len("nova-server_"):-len("_all.deb")].replace("~", "-") for n in sorted(listed) if n.startswith("nova-server_") and n.endswith("_all.deb")), "")
    if not ver: raise UpdateError("the newest release doesn't say which version it is")
    return ver, listed


def check():
    cur = installed(); ver, _ = latest()
    return {"installed": cur, "available": ver, "update": bool(ver) and (not cur or _key(ver) > _key(cur))}


def prepare():
    """Download the newest release's package for this system, verify it, unpack it → (version, path of its installer)."""
    ver, listed = latest()
    want = listed.get(ZIP)
    if not want: raise UpdateError(f"the newest release has no {ZIP}")
    data = _fetch(BASE + ZIP)
    if hashlib.sha256(data).hexdigest() != want: raise UpdateError("the download doesn't match its signed checksum — not installing it")
    work = tempfile.mkdtemp(prefix="nova-update-")
    zp = os.path.join(work, ZIP)
    with open(zp, "wb") as f: f.write(data)
    if MAC:
        with tarfile.open(zp) as t:
            for m in t.getmembers():                                    # nothing may land outside the folder, and no links or devices
                if m.name.startswith("/") or ".." in m.name.split("/") or not (m.isfile() or m.isdir()): raise UpdateError("the download has an unsafe file in it")
            t.extractall(work)
        inst = os.path.join(work, "Nova", "mac", "install.sh")
    else:
        with zipfile.ZipFile(zp) as z:
            for n in z.namelist():                                      # nothing may land outside the folder
                if n.startswith(("/", "\\")) or ".." in n.replace("\\", "/").split("/"): raise UpdateError("the download has an unsafe file name in it")
            z.extractall(work)
        inst = os.path.join(work, "Nova", "windows", "install.ps1")
    if not os.path.exists(inst): raise UpdateError("the download has no installer in it")
    return ver, inst


def launch(inst, reopen=True, window=True):
    """Run the installer. Windows: in a window of its own, started through `start` so it isn't this process's child — the
    installer closes everything running from Nova's folder (this window's part included) and must not take itself down
    with it (window=False, from the app: nobody is at the screen, so it doesn't wait for Enter or open Nova Server).
    macOS: on its own in the background (its output goes to Nova's log folder as update.log)."""
    if MAC:
        import plat
        log = open(os.path.join(plat.p("/var/log/nova-api"), "update.log"), "ab")
        subprocess.Popen(["/bin/sh", inst, "--quiet"], stdin=subprocess.DEVNULL, stdout=log, stderr=log, close_fds=True, start_new_session=True, cwd="/")
        return
    if not window:
        # From the app, this runs inside Nova's Helper task — and the installer's first step is to stop that task, which
        # ends everything the task started. So the installer gets a task of its own (\Nova\Update, run once, as SYSTEM).
        import winhost
        arg = f'-NoProfile -ExecutionPolicy Bypass -File "{inst}" -NoWindow'
        rc, so, se = winhost.ps("$a=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument " + winhost.psq(arg) + ";"
                                "$p=New-ScheduledTaskPrincipal -UserId 'S-1-5-18' -LogonType ServiceAccount -RunLevel Highest;"
                                "$s=New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 30);"
                                "Register-ScheduledTask -TaskPath '\\Nova\\' -TaskName 'Update' -Action $a -Principal $p -Settings $s -Force | Out-Null;"
                                "Start-ScheduledTask -TaskPath '\\Nova\\' -TaskName 'Update'", 90)
        if rc != 0: raise UpdateError("couldn't start the installer: " + (se or so).strip()[-200:])
        return
    args = ["cmd.exe", "/c", "start", "Nova update", "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", inst, "-Relaunched"]
    if reopen: args.append("-OpenWindow")
    subprocess.Popen(args, creationflags=0x08000000, close_fds=True, cwd=os.path.dirname(inst))


# ── from the Nova app (Settings → Software update): the helper's update-status / update-check / update-start verbs ──────
# What the app is told is kept next to the paired devices, in the same shape Linux's nova-update writes:
#   {"state": "available" | "current" | "installing" | "failed", "available": version, "error": why, "t": when}
def _status_file():
    import plat
    return plat.p("/var/lib/nova-api/update.json")

def _load():
    try:
        with open(_status_file(), encoding="utf-8") as f: return json.load(f)
    except (OSError, ValueError): return {}

def _save(st):
    import time
    st["t"] = time.time()
    tmp = _status_file() + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f: json.dump(st, f)
    os.replace(tmp, _status_file())

def status():
    import time
    st = _load(); cur = installed()
    if st.get("state") == "installing":
        # done once the installer has written this folder afresh (its VERSION file is newer than the update's start)
        try: fresh = os.path.getmtime(os.path.join(HERE, "VERSION")) >= st.get("started", 0)
        except OSError: fresh = False
        if fresh and (not st.get("available") or cur == st["available"]): st["state"] = "current"
        elif time.time() - st.get("started", st.get("t", 0)) > 900: st.update(state="failed", error="it didn't finish — see Nova's log folder")
    elif st.get("state") in ("available", "failed") and st.get("available") and _key(st["available"]) <= _key(cur): st["state"] = "current"; st.pop("error", None)
    return {**st, "installed": cur, "source": f"github:{REPO}", "running": st.get("state") == "installing"}

def record_check():
    """Look for a newer release and remember the answer for the app."""
    try: r = check()
    except UpdateError as e: return {"ok": False, "message": f"Couldn't check: {e}"}
    st = _load()
    if st.get("state") != "installing": _save({"state": "available" if r["update"] else "current", "available": r["available"]})
    return {"ok": True, "message": f"Nova {r['available']} is out — this server has {r['installed'] or 'an unknown version'}." if r["update"] else f"This is the newest Nova ({r['installed']})."}

def start():
    """Download, verify and install the newest release, without anyone at the server. Runs on its own (this file, `install`),
    so the helper call that started it can answer the app at once."""
    st = _load()
    if st.get("state") == "installing" and status()["running"]: return {"ok": True, "already": True}
    import time
    _save({"state": "installing", "available": st.get("available", ""), "started": time.time()})
    flags = 0x08000000 | 0x00000008 | 0x00000200 if os.name == "nt" else 0          # (Windows: no window, detached, its own group)
    subprocess.Popen([sys.executable, os.path.realpath(__file__), "install"], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     close_fds=True, cwd=os.environ.get("SystemRoot") or "/", creationflags=flags, start_new_session=os.name != "nt")
    return {"ok": True}

def _install():
    try:
        ver, inst = prepare()
        _save({"state": "installing", "available": ver, "started": _load().get("started", 0)})
        launch(inst, reopen=False, window=False)
    except UpdateError as e: _save({"state": "failed", "available": _load().get("available", ""), "error": str(e)})
    except Exception as e: _save({"state": "failed", "available": _load().get("available", ""), "error": repr(e)[:200]})

def verb(name, out, changelog):
    """helper.py's update verbs on Windows and macOS (winhost.py and machost.py pass them here)."""
    if name == "update-status": out(status())
    if name == "update-check":
        r = record_check(); out(r, 0 if r["ok"] else 1)
    if name == "update-start":
        changelog("Server update started from the Nova app"); out(start())


if __name__ == "__main__" and sys.argv[1:] == ["install"]:
    sys.path.insert(0, HERE)
    _install()
