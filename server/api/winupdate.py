"""
Nova's own updates on Windows: the newest release, checked the same way `nova-update` checks it on Linux — the list of
checksums must carry a valid signature from Nova's release key (shipped next to this file), and the download must match
its signed checksum — then installed by that release's own installer, in a window of its own (it restarts Nova, and closes
whatever runs from Nova's folder, this included). Settings and paired devices stay, as with any install over an install.

    from the Nova Server window: Check for updates → Update now          nova update   (in an administrator terminal)
"""
import base64, hashlib, json, os, re, subprocess, tempfile, urllib.error, urllib.request, zipfile

HERE = os.path.dirname(os.path.realpath(__file__))
REPO = "dash1101/Nova"                                   # where Nova's signed releases are published
KEY = next((p for p in (os.path.join(HERE, "release-key.pub"), os.path.join(HERE, "..", "..", "packaging", "release-key.pub")) if os.path.exists(p)), None)
ZIP = "nova-windows.zip"


class UpdateError(Exception):
    pass


def _fetch(url, limit=80 << 20, api=False):
    req = urllib.request.Request(url, headers={"User-Agent": "nova-update", "Accept": "application/vnd.github+json" if api else "application/octet-stream"})
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


def latest():
    """(version, {asset name: download url}) of the newest release."""
    rel = json.loads(_fetch(f"https://api.github.com/repos/{REPO}/releases/latest", 2 << 20, api=True))
    return rel.get("tag_name", "").lstrip("v"), {a["name"]: a["browser_download_url"] for a in rel.get("assets", [])}


def check():
    cur = installed(); ver, _ = latest()
    return {"installed": cur, "available": ver, "update": bool(ver) and (not cur or _key(ver) > _key(cur))}


def prepare():
    """Download the newest release's Windows zip, verify it, unpack it → (version, path of its install.ps1)."""
    if not KEY: raise UpdateError("Nova's release key isn't installed here, so an update can't be checked — install the newest release by hand")
    ver, assets = latest()
    for need in ("SHA256SUMS", "SHA256SUMS.sig", ZIP):
        if need not in assets: raise UpdateError(f"the newest release has no {need}")
    sums, sig = _fetch(assets["SHA256SUMS"], 1 << 20), _fetch(assets["SHA256SUMS.sig"], 1 << 16)
    from cryptography.hazmat.primitives import serialization
    from cryptography.exceptions import InvalidSignature
    try: serialization.load_pem_public_key(open(KEY, "rb").read()).verify(base64.b64decode(sig.strip()), sums)
    except (InvalidSignature, ValueError): raise UpdateError("the release's signature is NOT valid — not installing it")
    want = next((m.group(1) for m in (re.fullmatch(r"([0-9a-f]{64})\s+\*?(\S+)", l.strip()) for l in sums.decode().splitlines()) if m and m.group(2) == ZIP), None)
    if not want: raise UpdateError(f"the release's signed checksums don't list {ZIP}")
    data = _fetch(assets[ZIP])
    if hashlib.sha256(data).hexdigest() != want: raise UpdateError(f"the download doesn't match its signed checksum — not installing it")
    work = tempfile.mkdtemp(prefix="nova-update-")
    zp = os.path.join(work, ZIP)
    with open(zp, "wb") as f: f.write(data)
    with zipfile.ZipFile(zp) as z:
        for n in z.namelist():                                          # nothing may land outside the folder
            if n.startswith(("/", "\\")) or ".." in n.replace("\\", "/").split("/"): raise UpdateError("the download has an unsafe file name in it")
        z.extractall(work)
    inst = os.path.join(work, "Nova", "windows", "install.ps1")
    if not os.path.exists(inst): raise UpdateError("the download has no installer in it")
    return ver, inst


def launch(inst, reopen=True):
    """Run the installer in a window of its own. Started through `start`, so it isn't this process's child: the installer
    closes everything running from Nova's folder (this window's part included) and must not take itself down with it."""
    args = ["cmd.exe", "/c", "start", "Nova update", "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", inst, "-Relaunched"]
    if reopen: args.append("-OpenWindow")
    subprocess.Popen(args, creationflags=0x08000000, close_fds=True, cwd=os.path.dirname(inst))
