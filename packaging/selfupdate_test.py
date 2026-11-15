"""
After a release, on Windows or macOS: update Nova the way the app does it — the helper's update-check and update-start,
with nobody at the screen. The installed version already is the newest release, so this installs that same release over
itself; what's checked is that the update goes through from inside Nova's own service and that Nova comes back.
(Run by the `released` jobs in .github/workflows/windows.yml and macos.yml.)
"""
import json, os, socket, subprocess, sys, time, urllib.request

WIN = os.name == "nt"
if WIN:
    DATA = os.path.join(os.environ.get("ProgramData", r"C:\ProgramData"), "Nova")
    LIB = os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "Nova", "lib")
else:
    DATA = "/Library/Application Support/Nova"; LIB = "/usr/local/lib/nova/lib"; PY = "/usr/local/lib/nova/venv/bin/python"
fails = []

def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + (f"   [{str(detail)[:400]}]" if detail and not ok else ""), flush=True)
    if not ok: fails.append(name)

def helper(*args, timeout=200):
    """The helper, asked the way the API asks it (Windows: its local listener and key; a Mac: run as root)."""
    try:
        if WIN:
            h = json.load(open(os.path.join(DATA, "run", "helper.json")))
            c = socket.create_connection(("127.0.0.1", h["port"]), timeout=10); c.settimeout(timeout)
            c.sendall((h["key"] + "\n" + json.dumps(list(args)) + "\n").encode()); c.shutdown(1)
            buf = b""
            while True:
                b = c.recv(65536)
                if not b: break
                buf += b
            r = json.loads(buf); return r["rc"], json.loads(r["stdout"] or "{}")
        r = subprocess.run(["sudo", PY, "-I", LIB + "/helper.py", *args], capture_output=True, text=True, timeout=timeout)
        return r.returncode, json.loads(r.stdout or "{}")
    except Exception as e: return -1, {"_": repr(e)[:200]}

def ping():
    try:
        with urllib.request.urlopen("http://127.0.0.1:8095/api/v1/ping", timeout=5) as r: return json.loads(r.read()).get("api", "")
    except Exception: return ""

print("\nUpdating Nova from the app")
before = ping(); check("Nova is running", bool(before), before)
rc, r = helper("update-check"); check("looking for an update works", rc == 0 and r.get("ok"), (rc, r)); print("     ", r.get("message"))
rc, r = helper("update-status"); check("…and the app is told what's installed", rc == 0 and r.get("installed") == before, (rc, r))
rc, r = helper("update-start"); check("the update starts", rc == 0 and r.get("ok"), (rc, r))
seen = []; end = time.time() + 480; st = {}
while time.time() < end:
    time.sleep(6)
    rc, st = helper("update-status", timeout=30)
    s = st.get("state", "?") if rc == 0 else "(helper restarting)"
    if not seen or seen[-1] != s: seen.append(s); print("      state:", s, flush=True)
    if rc == 0 and st.get("state") in ("current", "failed") and not st.get("running"): break
check("it went through: downloaded, verified against the release key, installed", st.get("state") == "current", (seen, st))
check("…it really was installing in between", "installing" in seen or "(helper restarting)" in seen, seen)
for _ in range(30):
    if ping(): break
    time.sleep(2)
check("Nova came back by itself, the same version", ping() == before, (ping(), before))
print(f"\n{'ALL PASSED' if not fails else str(len(fails)) + ' FAILED: ' + '; '.join(fails)}")
sys.exit(1 if fails else 0)
