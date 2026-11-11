"""
Checks a freshly installed and set-up Nova server on Linux (run by .github/workflows/linux.yml): the API answers, a device
pairs and signs requests, the live numbers, processes and drives come through the helper, keep-awake holds a real systemd
sleep lock, the monitor writes the Inbox, the `nova` command and the Nova Server window work. Exits 1 if anything fails.
"""
import base64, hashlib, json, os, secrets, subprocess, sys, time, urllib.error, urllib.request
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

BASE = "http://127.0.0.1:8095"; fails = []
def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + (f"   [{str(detail)[:300]}]" if detail and not ok else ""), flush=True)
    if not ok: fails.append(name)
def raw(method, path, body=b"", headers=None, timeout=60):
    req = urllib.request.Request(BASE + path, data=body if method not in ("GET", "HEAD") else None, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r: return r.status, r.read()
    except urllib.error.HTTPError as e: return e.code, e.read()
    except Exception as e: return -1, str(e).encode()
class Dev:
    def __init__(self, did, k): self.id, self.k = did, k
    def call(self, method, path, body=None, ts=None):
        b = json.dumps(body).encode() if body is not None else b""
        ts = ts or str(int(time.time() * 1000)); nonce = secrets.token_urlsafe(18)
        msg = "\n".join([method, path, ts, nonce, hashlib.sha256(b).hexdigest()]).encode()
        h = {"X-Nova-Device": self.id, "X-Nova-Time": ts, "X-Nova-Nonce": nonce, "Content-Type": "application/json",
             "X-Nova-Signature": base64.b64encode(self.k.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()}
        s, r = raw(method, path, b, h)
        try: return s, json.loads(r)
        except ValueError: return s, {"_raw": r[:200]}
def sh(*cmd, timeout=120):
    r = subprocess.run(list(cmd), capture_output=True, text=True, timeout=timeout); return r.returncode, r.stdout, r.stderr

print("\nInstalled and running")
for u in ("nova-api.service", "nova-helper.socket", "nova-alerts.timer"):
    rc, so, _ = sh("systemctl", "is-active", u); check(f"{u} is active", so.strip() == "active", so)
s, r = raw("GET", "/api/v1/ping"); check("ping answers", s == 200, (s, r))
rc, so, se = sh("sudo", "nova", "doctor"); check("nova doctor finds nothing that stops it", rc == 0, so[-600:] + se[-300:]); print("      " + " | ".join(l.strip() for l in so.splitlines() if l.strip())[:600])

print("\nPairing")
rc, so, se = sh("sudo", "nova", "add", "--json"); inv = json.loads(so.strip().splitlines()[-1]) if rc == 0 else {}
check("nova add makes a code with a QR", len(inv.get("code", "")) == 10 and inv.get("payload"), (rc, so[-200:], se[-200:]))
k = ec.generate_private_key(ec.SECP256R1()); pem = k.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
host = os.environ.get("NOVA_HOST", "127.0.0.1")
import ssl
CTX = ssl.create_default_context(); CTX.check_hostname = False; CTX.verify_mode = ssl.CERT_NONE      # (phones pin the certificate instead)
req = urllib.request.Request(f"https://{host}:8495/api/v1/pair", data=json.dumps({"code": inv.get("code", ""), "name": "CI test device", "public_key": pem}).encode(), method="POST", headers={"Content-Type": "application/json"})
try:
    with urllib.request.urlopen(req, timeout=30, context=CTX) as r_: pr = json.loads(r_.read()); ps = r_.status
except urllib.error.HTTPError as e: ps, pr = e.code, {"_": e.read()[:200]}
check("the code pairs from the home network (encrypted, like a phone)", ps == 200 and pr.get("device_id") and len(pr.get("lan_pin", "")) == 64, (ps, pr))
d = Dev(pr.get("device_id", "x"), k)

print("\nSigned requests")
s, ov = d.call("GET", "/api/v1/overview"); sv = ov.get("server", {})
check("overview", s == 200 and sv.get("os") == "linux" and sv.get("ram_gb", 0) >= 1, (s, str(ov)[:300]))
check("…no lighting on a machine that never set it up", ov.get("features", {}).get("lighting") is False, ov.get("features"))
time.sleep(3); s, st = d.call("GET", "/api/v1/stats"); n = st.get("now", {})
check("live numbers", s == 200 and 0 <= n.get("cpu", -1) <= 100 and len(n.get("cpus", [])) == os.cpu_count(), str(n)[:300])
s, r = d.call("GET", "/api/v1/processes"); check("every process, through the helper", s == 200 and r.get("count", 0) > 50, (s, str(r)[:200]))
s, r = d.call("GET", "/api/v1/hardware"); check("drives", s == 200 and len(r.get("drives", [])) >= 1, (s, str(r)[:300]))
s, r = d.call("GET", "/api/v1/server/update"); check("updates point at the official releases by default", s == 200 and r.get("source") == "github:dash1101/Nova", (s, r))
s, t = d.call("POST", "/api/v1/updates/check", {}); tid = t.get("id", "x"); end = time.time() + 240; tj = {}
while time.time() < end:
    time.sleep(3); _, tj = d.call("GET", f"/api/v1/tasks/{tid}")
    if tj.get("state") in ("done", "failed"): break
s, u = d.call("GET", "/api/v1/updates"); nv = u.get("nova", {}); ver = open("/usr/lib/nova-api/VERSION").read().strip().replace("~", "-")
check("the update check sees which Nova is installed (the Updates page's Nova row)", tj.get("state") == "done" and nv.get("installed") == ver and "error" not in nv, (tj.get("state"), nv, ver))
s, r = d.call("GET", "/api/v1/whoami", ts=str(int(time.time() * 1000) - 300_000)); check("a late request says 'clock' with the server's time", s == 401 and r.get("reason") == "clock", (s, r))

print("\nKeep awake")
s, r = d.call("POST", "/api/v1/power/keep-awake", {"hours": 1}); check("keep awake for an hour (no fingerprint needed)", s == 200 and r.get("on") and r.get("until", 0) > time.time() + 3000, (s, r))
time.sleep(2); rc, so, _ = sh("systemd-inhibit", "--list", "--no-legend"); check("…a real systemd sleep lock is held", "Nova" in so and "sleep" in so, so)
s, r = d.call("POST", "/api/v1/power/keep-awake", {"off": True}); time.sleep(2); rc, so, _ = sh("systemd-inhibit", "--list", "--no-legend")
check("…and let go when turned off", s == 200 and r.get("on") is False and "Kept awake" not in so, (r, so))
s, r = d.call("POST", "/api/v1/power/keep-awake", {"hours": 500}); check("…a silly length is refused", s == 400, (s, r))

print("\nThe monitor and the nova command")
end = time.time() + 120
while time.time() < end and not os.path.exists("/var/lib/nova-alerts/www/status.json"): time.sleep(2)
s, r = d.call("GET", "/api/v1/status"); check("the monitor writes the status", s == 200 and r.get("level") in ("ok", "warning", "critical") and "storage" in r.get("metrics", {}), (s, str(r)[:300]))
rc, so, se = sh("sudo", "nova", "status"); check("nova status", rc == 0 and "nova-api" in so and "~" not in so.splitlines()[0], so[-400:])
rc, so, se = sh("sudo", "nova", "devices"); check("nova devices", rc == 0 and "CI test device" in so, so[-300:])
rc, so, se = sh("sudo", "nova", "log", "5"); check("nova log reads like sentences", rc == 0 and "/api/v1/" in so and '{"' not in so, so[-300:])
rc, so, se = sh("sudo", "nova", "approve", "ZZZZZZ"); check("a wrong approval code answers at once", rc == 1 and "Nothing is waiting" in so, (rc, so[-200:]))

print("\nThe Nova Server window")
p = subprocess.Popen(["sudo", "/usr/sbin/nova-gui", "--serve", "--print-url"], stdout=subprocess.PIPE, text=True)
url = (p.stdout.readline() or "").strip(); check("starts and gives its address", url.startswith("http://localhost:") and "#" in url, url[:40])
base, key = (url.split("/#") + [""])[:2]
def g(path, body=None):
    rq = urllib.request.Request(base + path, data=json.dumps(body).encode() if body is not None else None, method="POST" if body is not None else "GET", headers={"X-Nova-Key": key, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(rq, timeout=60) as r_: return r_.status, json.loads(r_.read())
    except urllib.error.HTTPError as e: return e.code, {}
s, r = g("/api/state"); check("shows the state", s == 200 and r.get("running") and any(x["name"] == "CI test device" for x in r.get("devices", [])), (s, str(r)[:300]))
s, r = g("/api/pair", {}); check("pairs with a QR", r.get("ok") and r.get("svg", "").startswith("<svg"), str(r)[:200]); g("/api/pair/cancel", {})
s, r = g("/api/doctor"); check("runs the check-up", s == 200 and any(c["title"] == "Nova is running" for c in r.get("checks", [])), str(r)[:300])
g("/api/quit", {})

rc, so, se = sh("sudo", "nova", "remove", "CI test device"); s, r = d.call("GET", "/api/v1/whoami")
check("a removed device is locked out", rc == 0 and s == 401, (rc, s))
print(f"\n{'ALL PASSED' if not fails else str(len(fails)) + ' FAILED: ' + '; '.join(fails)}")
sys.exit(1 if fails else 0)
