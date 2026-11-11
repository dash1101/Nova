"""
Checks a freshly installed Nova server on a real Windows machine (run by .github/workflows/windows.yml, as administrator):
the API answers, a device can pair and sign requests, the live numbers, processes, drives and power schedules work,
the monitor writes the Inbox, and the `nova` command does its job. Exits 1 if anything fails.
"""
import base64, hashlib, json, os, secrets, socket, subprocess, sys, time, urllib.error, urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

DATA = os.path.join(os.environ.get("ProgramData", r"C:\ProgramData"), "Nova")
APP = os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "Nova")
NOVA = [os.path.join(APP, "venv", "Scripts", "python.exe"), os.path.join(APP, "lib", "nova")]
BASE = "http://127.0.0.1:8095"
fails = []
for _s in (sys.stdout, sys.stderr): _s.reconfigure(encoding="utf-8", errors="replace")

def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + (f"   [{str(detail)[:300]}]" if detail and not ok else ""), flush=True)
    if not ok: fails.append(name)

def raw(method, path, body=b"", headers=None, timeout=30):
    req = urllib.request.Request(BASE + path, data=body if method not in ("GET", "HEAD") else None, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r: return r.status, r.read()
    except urllib.error.HTTPError as e: return e.code, e.read()
    except Exception as e: return -1, str(e).encode()

class Dev:
    def __init__(self, did, k): self.id, self.k = did, k
    def call(self, method, path, body=None, timeout=30):
        b = json.dumps(body).encode() if body is not None else b""
        ts = str(int(time.time() * 1000)); nonce = secrets.token_urlsafe(18)
        msg = "\n".join([method, path, ts, nonce, hashlib.sha256(b).hexdigest()]).encode()
        h = {"X-Nova-Device": self.id, "X-Nova-Time": ts, "X-Nova-Nonce": nonce, "Content-Type": "application/json",
             "X-Nova-Signature": base64.b64encode(self.k.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()}
        s, r = raw(method, path, b, h, timeout)
        try: return s, json.loads(r)
        except ValueError: return s, {"_raw": r[:300].decode("utf-8", "replace")}

def nova(*args, timeout=60):
    r = subprocess.run(NOVA + list(args), capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    return r.returncode, r.stdout, r.stderr

def helper(*args, timeout=90):
    """Straight to the helper service, the way the API does it."""
    h = json.load(open(os.path.join(DATA, "run", "helper.json")))
    c = socket.create_connection(("127.0.0.1", h["port"]), timeout=10); c.settimeout(timeout)
    c.sendall((h["key"] + "\n" + json.dumps(list(args)) + "\n").encode()); c.shutdown(1)
    buf = b""
    while True:
        b = c.recv(65536)
        if not b: break
        buf += b
    r = json.loads(buf)
    try: return r["rc"], json.loads(r["stdout"] or "{}")
    except ValueError: return r["rc"], {"_stdout": r["stdout"][-300:], "_stderr": r["stderr"][-600:]}

print("\nThe API")
s, r = raw("GET", "/api/v1/ping"); check("ping answers", s == 200, (s, r))
s, r = raw("GET", "/"); check("serves the web app", s == 200 and b"<html" in r.lower(), (s, r[:100]))
s, r = raw("GET", "/api/v1/overview"); check("unsigned requests are refused", s in (401, 403), s)

print("\nPairing")
rc, so, se = nova("add", "--json"); 
try: inv = json.loads(so.strip().splitlines()[-1])
except Exception: inv = {}
check("nova add makes a code", rc == 0 and len(inv.get("code", "")) == 10 and inv.get("payload"), (rc, so[-200:], se[-300:]))
k = ec.generate_private_key(ec.SECP256R1())
pem = k.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
s, r = raw("POST", "/api/v1/pair", json.dumps({"code": "WRONGCODE1", "name": "ci", "public_key": pem}).encode(), {"Content-Type": "application/json"})
check("a wrong code is refused", s == 401, (s, r))
s, r = raw("POST", "/api/v1/pair", json.dumps({"code": inv.get("code", ""), "name": "CI test device", "public_key": pem, "form": "desktop"}).encode(), {"Content-Type": "application/json"})
try: pr = json.loads(r)
except ValueError: pr = {}
check("the right code pairs", s == 200 and pr.get("device_id"), (s, r[:300]))
check("…and gives the certificate pin for the encrypted listener", len(pr.get("lan_pin", "")) == 64 and str(pr.get("lan_url", "")).startswith("https://"), pr)
d = Dev(pr.get("device_id", "x"), k)

print("\nSigned requests")
s, r = d.call("GET", "/api/v1/whoami"); check("whoami", s == 200 and r.get("role") == "admin", (s, r))
s, ov = d.call("GET", "/api/v1/overview", timeout=60)
sv = ov.get("server", {}) if s == 200 else {}
check("overview", s == 200 and sv.get("os") == "windows", (s, str(ov)[:300]))
check("…knows the machine", bool(sv.get("name")) and sv.get("ram_gb", 0) >= 1 and "Windows" in sv.get("kernel", "") and sv.get("uptime_s", 0) > 0, sv)
check("…has a CPU name", len(sv.get("cpu", "")) > 3, sv.get("cpu"))
ft = ov.get("features", {})
check("…says what a Windows server doesn't have yet", ft.get("terminal") is False and ft.get("files") is False and ft.get("lan_tls") is True, ft)
print("     ", json.dumps(sv)[:400])
time.sleep(4)
s, st = d.call("GET", "/api/v1/stats"); now = st.get("now", {}) if s == 200 else {}
check("live numbers", s == 200 and 0 <= now.get("cpu", -1) <= 100 and 0 < now.get("mem", -1) <= 100 and now.get("mem_total_gb", 0) > 0, (s, str(st)[:300]))
check("…per core", len(now.get("cpus", [])) == os.cpu_count() and all(0 <= c <= 100 for c in now.get("cpus", [])), now.get("cpus"))
check("…network and drive activity", isinstance(now.get("rx"), int) and isinstance(now.get("disks"), dict) and len(now["disks"]) >= 1, (now.get("rx"), now.get("disks")))
check("…history is building", len(st.get("recent", [])) >= 2, len(st.get("recent", [])))
print("     ", json.dumps({k: v for k, v in now.items() if k != "cpus"})[:400])
s, r = d.call("GET", "/api/v1/status"); check("status", s == 200 and r.get("level") in ("ok", "warning", "critical"), (s, str(r)[:200]))
s, r = d.call("GET", "/api/v1/processes", timeout=60); ps = r.get("processes", []) if s == 200 else []
check("processes", s == 200 and len(ps) > 15 and any("python" in p["name"].lower() for p in ps) and all(p["pid"] > 0 for p in ps), (s, str(r)[:300]))
check("…with memory, owner and system ones marked", any(p["mem"] > 1e6 for p in ps) and any(p["kernel"] for p in ps) and any(p["user"] for p in ps), ps[:3])
check("…and the machine's memory", r.get("memory", {}).get("total", 0) > 1e9 and r.get("cpus") == os.cpu_count(), r.get("memory"))
# a request signed by this device but sent late (a phone that slept on it) says why, with the server's time
def late(path, off_ms):
    ts = str(int(time.time() * 1000) - off_ms); nonce = secrets.token_urlsafe(18)
    msg = "\n".join(["GET", path, ts, nonce, hashlib.sha256(b"").hexdigest()]).encode()
    h = {"X-Nova-Device": d.id, "X-Nova-Time": ts, "X-Nova-Nonce": nonce, "X-Nova-Signature": base64.b64encode(d.k.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()}
    s_, r_ = raw("GET", path, b"", h); return s_, json.loads(r_ or b"{}")
s, r = late("/api/v1/whoami", 5 * 60_000); check("a late request says 'clock' and the server's time", s == 401 and r.get("reason") == "clock" and abs(r.get("server_time", 0) - time.time() * 1000) < 10_000, (s, r))
s, r = d.call("GET", "/api/v1/devices"); check("devices", s == 200 and "CI test device" in json.dumps(r), (s, str(r)[:200]))
s, r = d.call("GET", "/api/v1/events"); check("events", s == 200, (s, str(r)[:200]))
s, r = d.call("GET", "/api/v1/containers", timeout=60); check("containers (none here is fine)", s == 200 and isinstance(r.get("containers"), list), (s, str(r)[:300]))
s, r = d.call("GET", "/api/v1/power/schedule"); check("power schedules", s == 200 and r.get("schedules") == [], (s, str(r)[:200]))
s, r = d.call("POST", "/api/v1/power/reboot"); check("restarting isn't done on one device's word: it waits for a second approval", s == 202 and r.get("approval") and len(r.get("code", "")) >= 6, (s, r))
rc, so, se = nova("approve"); check("…which `nova approve` lists", rc == 0 and r.get("code", "?") in so, (rc, so[-300:], se[-200:]))

print("\nThe helper (what runs as SYSTEM)")
rc, r = helper("drives"); dr = r.get("drives", [])
check("drives", rc == 0 and len(dr) >= 1 and all(x["size"] > 0 and x["name"].startswith("PhysicalDrive") for x in dr), (rc, str(r)[:400]))
sysd = next((x for x in dr if x["protected"]), None)
check("…the system drive is found, protected, with its space", bool(sysd) and sysd["role"] == "Boot drive" and any(u["mount"].upper().startswith(os.environ.get("SystemDrive", "C:").upper()) and u["total"] > u["free"] > 0 for u in sysd["usage"]), sysd)
check("…drive activity names match the drive list", set(now.get("disks", {})) & {x["name"] for x in dr}, (list(now.get("disks", {})), [x["name"] for x in dr]))
print("     ", json.dumps(dr)[:500])
rc, r = helper("proc-list"); check("proc-list", rc == 0 and len(r.get("processes", [])) > 15, (rc, str(r)[:200]))
victim = subprocess.Popen(["ping", "-n", "120", "127.0.0.1"], stdout=subprocess.DEVNULL)
time.sleep(1); rc, r = helper("proc-signal", str(victim.pid), "kill"); time.sleep(1)
check("force-stop a process", rc == 0 and r.get("ok") and victim.poll() is not None, (rc, r, victim.poll()))
rc, r = helper("proc-signal", "4", "kill"); check("…but not Windows itself", rc != 0 and "error" in r, (rc, r))
at = int(time.time()) + 3600
rc, r = helper("power-schedule", "reboot", str(at)); sid = r.get("id", "")
check("schedule a restart", rc == 0 and len(sid) == 8, (rc, r))
rc, r = helper("power-schedule-list"); check("…it's listed", rc == 0 and any(x["id"] == sid and x["at"] == at for x in r.get("schedules", [])), (rc, r))
q = subprocess.run(["schtasks", "/Query", "/TN", f"\\Nova\\sched-{sid}"], capture_output=True, text=True)
check("…and really in Task Scheduler", q.returncode == 0, q.stderr[-200:])
rc, r = helper("power-unschedule", sid); check("cancel it", rc == 0 and r.get("ok"), (rc, r))
q = subprocess.run(["schtasks", "/Query", "/TN", f"\\Nova\\sched-{sid}"], capture_output=True, text=True)
check("…gone from Task Scheduler", q.returncode != 0)
rc, r = helper("partitions", "PhysicalDrive0"); check("what isn't there yet says so plainly", rc != 0 and "Windows" in r.get("error", ""), (rc, r))
rc, r = helper("keep-awake", "300"); check("keep awake for 5 minutes", rc == 0 and r.get("on") and r.get("until", 0) > time.time() + 200, (rc, r))
time.sleep(3)
pq = subprocess.run(["powercfg", "/requests"], capture_output=True, text=True, errors="replace").stdout
check("…and Windows really holds a 'system required' request for it", "SYSTEM:" in pq and "python" in pq.split("SYSTEM:")[1].split("AWAYMODE:")[0].lower(), pq[-600:])
rc, r = helper("keep-awake-status"); check("…which the status shows", rc == 0 and r.get("on") is True and r.get("forever") is False, (rc, r))
rc, r = helper("keep-awake", "0"); time.sleep(2)
pq = subprocess.run(["powercfg", "/requests"], capture_output=True, text=True, errors="replace").stdout
check("turning it off lets Windows sleep again", rc == 0 and r.get("on") is False and "python" not in pq.split("SYSTEM:")[1].split("AWAYMODE:")[0].lower(), (r, pq[-400:]))
rc, r = helper("get-notify"); check("notification settings", rc == 0 and r.get("push_min_level") == "info", (rc, r))
rc, r = helper("set-notify", "push_min_level", "warning"); rc2, r2 = helper("get-notify")
check("…can be changed", rc == 0 and r2.get("push_min_level") == "warning", (r, r2)); helper("set-notify", "push_min_level", "info")
rc, r = helper("tools-status"); check("tools", rc == 0 and "docker" in r.get("tools", {}), (rc, r))
rc, r = helper("list-dirs", "/"); check("folder picker lists the drives", rc == 0 and any(x["path"].upper().startswith("C:") for x in r.get("dirs", [])), (rc, r))
try:
    c = socket.create_connection(("127.0.0.1", json.load(open(os.path.join(DATA, "run", "helper.json")))["port"]), timeout=5)
    c.sendall(b"wrong-key\n" + json.dumps(["drives"]).encode() + b"\n"); c.shutdown(1); c.settimeout(10); got = c.recv(4096)
except OSError: got = b""
check("the helper ignores a caller without the key", got == b"", got[:100])

print("\nThe monitor")
status = os.path.join(DATA, "alerts", "www", "status.json"); end = time.time() + 120
while time.time() < end and not os.path.exists(status): time.sleep(2)
st = json.load(open(status)) if os.path.exists(status) else {}
check("writes the server's status", st.get("level") in ("ok", "warning", "critical"), str(st)[:300])
m = st.get("metrics", {})
check("…with memory, uptime and storage", "memory" in m and "uptime" in m and len(m.get("storage", [])) >= 1 and "root_used" in m, m)
print("     ", st.get("headline"), "|", json.dumps(m)[:400])
print("      active:", json.dumps(st.get("active"))[:500])
rc, so, se = nova("alert", "info", "CI says hello", "from the Windows test"); check("nova alert queues a message", rc == 0, (rc, so, se))
seen = False; end = time.time() + 90
while time.time() < end and not seen:
    s, r = d.call("GET", "/api/v1/events"); seen = s == 200 and any("CI says hello" in e.get("title", "") for e in r.get("events", [])); time.sleep(3)
check("…and it reaches the Inbox through the API", seen)
s, r = d.call("GET", "/api/v1/status"); check("the API serves the monitor's status", s == 200 and "metrics" in r and "storage" in r.get("metrics", {}), (s, str(r)[:200]))

print("\nThe nova command")
rc, so, se = nova("status"); check("nova status", rc == 0 and "Nova" in so and "Api" in so and "active" in so, (rc, so[-400:], se[-300:])); print("     ", " | ".join(so.split("\n"))[:500])
rc, so, se = nova("devices"); check("nova devices", rc == 0 and "CI test device" in so, (rc, so[-300:], se[-300:]))
rc, so, se = nova("approve"); check("nova approve (nothing waiting)", rc == 0, (rc, so[-200:], se[-300:]))
rc, so, se = nova("log", "5"); check("nova log", rc == 0 and "/api/v1/" in so, (rc, so[-200:], se[-300:]))
rc, so, se = nova("doctor"); check("nova doctor", rc in (0, 1) and "Nova is running" in so and "Listening for phones" in so and "Windows Firewall lets" in so, (rc, so[-600:], se[-300:])); print("     ", " | ".join(l.strip() for l in so.split("\n") if l.strip())[:700])

print("\nThe Nova Server window")
import tempfile
uf = os.path.join(tempfile.gettempdir(), "nova-gui-test.url")
if os.path.exists(uf): os.remove(uf)
gui = subprocess.Popen([NOVA[0], os.path.join(APP, "lib", "nova-gui"), "--serve", "--url-file", uf], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
url = ""; end = time.time() + 30
while time.time() < end and not url.startswith("http://localhost:"):
    time.sleep(.3)
    try: url = open(uf).read().strip()
    except OSError: pass
check("starts and says where it is", url.startswith("http://localhost:") and "#" in url, (url[:40], gui.poll()))
gbase, gkey = (url.split("/#") + [""])[:2]
def g(path, body=None, key=None, host=None):
    h = {"X-Nova-Key": gkey if key is None else key, "Content-Type": "application/json"}
    if host: h["Host"] = host
    req = urllib.request.Request(gbase + path, data=json.dumps(body).encode() if body is not None else None, method="POST" if body is not None else "GET", headers=h)
    try:
        with urllib.request.urlopen(req, timeout=120) as r: return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e: return e.code, {}
    except Exception as e: return -1, {"error": repr(e)}
s, r = raw("GET", "/"); 
try:
    with urllib.request.urlopen(gbase + "/", timeout=10) as r_: page = r_.read()
except Exception as e: page = repr(e).encode()
check("serves its page", b"Nova Server" in page and b"Pair a device" in page, page[:100])
s, r = g("/api/state", key="wrong"); check("refuses a request without this window's key", s == 403, s)
s, r = g("/api/state", host="evil.example"); check("refuses a request made under another site's name", s == 403, s)
s, gs = g("/api/state"); check("shows the server's state", s == 200 and gs.get("running") is True and gs.get("windows") is True and any(p["id"] == "api" and p["state"] == "active" for p in gs.get("parts", [])), (s, str(gs)[:400]))
check("…its addresses, certificate, devices and health", len(gs.get("addresses", [])) >= 2 and len(gs.get("pin", "")) == 14 and any(x["name"] == "CI test device" for x in gs.get("devices", [])) and (gs.get("health") or {}).get("level") in ("ok", "warning", "critical"), str(gs)[:600])
check("…and what's waiting for approval", any(w["kind"] == "action" for w in gs.get("waiting", [])), gs.get("waiting"))
s, r = g("/api/pair", {"viewer": True}); check("makes a pairing code with a QR picture", s == 200 and r.get("ok") and len(r.get("code", "")) == 10 and r.get("svg", "").startswith("<svg") and r.get("role") == "viewer", (s, str(r)[:300]))
k2 = ec.generate_private_key(ec.SECP256R1()); pem2 = k2.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
s, r2 = raw("POST", "/api/v1/pair", json.dumps({"code": r.get("code", ""), "name": "GUI paired", "public_key": pem2}).encode(), {"Content-Type": "application/json"})
check("…that really pairs a device, view-only as asked", s == 200 and json.loads(r2).get("role") == "viewer", (s, r2[:200]))
s, gs = g("/api/state"); gid = next((x["id"] for x in gs.get("devices", []) if x["name"] == "GUI paired"), "")
check("…which then shows in the list", bool(gid) and not gs["pairing"]["active"], gs.get("devices"))
s, r = g("/api/remove", {"id": gid}); s2, gs = g("/api/state"); check("removes a device", r.get("ok") and not any(x["name"] == "GUI paired" for x in gs.get("devices", [])), (r, gs.get("devices")))
s, r = g("/api/doctor"); check("runs the check-up", s == 200 and any(c["title"] == "Nova is running" for c in r.get("checks", [])), (s, str(r)[:300]))
s, r = g("/api/activity"); check("shows recent activity", s == 200 and isinstance(r.get("lines"), list), (s, str(r)[:200]))
s, r = g("/api/service", {"action": "stop"}); time.sleep(3); ps_, _ = raw("GET", "/api/v1/ping", timeout=5)
check("stops Nova", r.get("ok") and ps_ != 200, (r, ps_))
s, gs = g("/api/state"); check("…and shows it stopped", gs.get("running") is False, gs.get("parts"))
s, r = g("/api/service", {"action": "start"}); up = False; end = time.time() + 40
while time.time() < end and not up: time.sleep(1); up = raw("GET", "/api/v1/ping", timeout=5)[0] == 200
check("starts it again", r.get("ok") and up, r)
s, r = d.call("GET", "/api/v1/whoami"); check("…and paired devices still work after the restart", s == 200, (s, r))
s, r = g("/api/network", {}); up = False; end = time.time() + 40
while time.time() < end and not up: time.sleep(1); up = raw("GET", "/api/v1/ping", timeout=5)[0] == 200
check("looks at the network again and restarts", r.get("ok") and r.get("found", {}).get("lan_host") and up, r)
s, r = g("/api/quit", {}); time.sleep(1.5); check("closes when asked", gui.poll() is not None, gui.poll())
if gui.poll() is None: gui.kill()
err = gui.stderr.read().decode("utf-8", "replace").strip()
if err: print("      gui stderr:", err[-600:])

print("\nRemoving a device")
rc, so, se = nova("remove", "CI test device"); check("nova remove", rc == 0, (rc, so[-200:], se[-300:]))
s, r = d.call("GET", "/api/v1/whoami"); check("…and the removed device is locked out", s in (401, 403), (s, r))

print("\nWho runs what")
import psutil
mine = {}
for p in psutil.process_iter(["pid", "name", "username", "cmdline"]):
    cl = " ".join(p.info.get("cmdline") or [])
    if "winrun.py" in cl: mine[cl.split()[-1]] = (p.info.get("username") or "")
print("     ", mine)
check("the API runs without administrator rights (Local Service)", "LOCAL SERVICE" in mine.get("api", "").upper(), mine)
check("the helper and monitor run as SYSTEM", "SYSTEM" in mine.get("helper", "").upper() and "SYSTEM" in mine.get("alerts", "").upper(), mine)
acl = subprocess.run(["icacls", os.path.join(DATA, "config", "alerts")], capture_output=True, text=True).stdout
check("Local Service can't read the alerts settings (Discord webhook)", "LOCAL SERVICE" not in acl.upper() and "S-1-5-19" not in acl, acl)
acl = subprocess.run(["icacls", DATA], capture_output=True, text=True).stdout
check("ordinary users can't read Nova's data", "BUILTIN\\Users" not in acl and "Everyone" not in acl and "Authenticated Users" not in acl, acl)

print(f"\n{'ALL PASSED' if not fails else str(len(fails)) + ' FAILED: ' + '; '.join(fails)}")
sys.exit(1 if fails else 0)
