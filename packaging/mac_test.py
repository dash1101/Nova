"""
Checks a freshly installed Nova server on a real Mac (run by .github/workflows/macos.yml, as the signed-in user with sudo):
the API answers, a device can pair and sign requests, the live numbers, processes, drives, power schedules, keep awake,
the terminal, the file manager and the phone's SSH key work, the monitor writes the Inbox, and the `nova` command and
the Nova Server window do their job. Exits 1 if anything fails.
"""
import base64, getpass, hashlib, json, os, secrets, subprocess, sys, time, urllib.error, urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

APP = "/usr/local/lib/nova"; LIB = APP + "/lib"; PY = APP + "/venv/bin/python"
DATA = "/Library/Application Support/Nova"
BASE = "http://127.0.0.1:8095"
ME = getpass.getuser()
fails = []

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
    def __init__(self, did, k): self.id, self.k, self.su = did, k, None
    def call(self, method, path, body=None, timeout=30, stepup=False):
        b = json.dumps(body).encode() if body is not None else b""
        ts = str(int(time.time() * 1000)); nonce = secrets.token_urlsafe(18)
        msg = "\n".join([method, path, ts, nonce, hashlib.sha256(b).hexdigest()]).encode()
        h = {"X-Nova-Device": self.id, "X-Nova-Time": ts, "X-Nova-Nonce": nonce, "Content-Type": "application/json",
             "X-Nova-Signature": base64.b64encode(self.k.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()}
        if stepup: h["X-Nova-StepUp"] = base64.b64encode(self.su.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()
        s, r = raw(method, path, b, h, timeout)
        try: return s, json.loads(r)
        except ValueError: return s, {"_raw": r[:300].decode("utf-8", "replace")}

def sh(*cmd, timeout=120, input=None):
    r = subprocess.run(list(cmd), capture_output=True, text=True, errors="replace", timeout=timeout, input=input)
    return r.returncode, r.stdout, r.stderr

def nova(*args, timeout=90): return sh("sudo", "/usr/local/bin/nova", *args, timeout=timeout)

def helper(*args, timeout=90, input=None):
    """The root helper, run the way the helper service runs it."""
    rc, so, se = sh("sudo", PY, "-I", LIB + "/helper.py", *args, timeout=timeout, input=input)
    try: return rc, json.loads(so or "{}")
    except ValueError: return rc, {"_stdout": so[-300:], "_stderr": se[-600:]}

def pem_of(k): return k.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()


print("\nInstalled")
check("the nova command is on the PATH", os.access("/usr/local/bin/nova", os.X_OK))
check("Nova Server is in Applications", os.access("/Applications/Nova Server.app/Contents/MacOS/NovaServer", os.X_OK))
rc, so, se = sh("launchctl", "print", "system/app.novalabs.nova.api")
check("launchd runs the API", rc == 0 and "state = running" in so, so[-300:] + se[-200:])
rc, so, se = sh("ps", "-axo", "user=,command=")
mine = {l.split()[-1]: l.split()[0] for l in so.splitlines() if "runpart.py" in l}
check("the API runs without administrator rights (as nova-api)", mine.get("api") == "nova-api", mine)
check("the helper and the monitor run as root", mine.get("helper") == "root" and mine.get("alerts") == "root", mine)
check("someone else on this Mac can't read Nova's settings, its devices or the helper's socket",
      not os.access(DATA + "/config/config.json", os.R_OK) and not os.access(DATA + "/data", os.R_OK) and not os.access(DATA + "/run/helper.sock", os.W_OK))
rc, so, se = sh("sudo", "stat", "-f", "%Su:%Sg %Lp", DATA + "/config/config.json", DATA + "/config/alerts", DATA + "/run/helper.sock")
check("…the settings are root's, readable by Nova's account; the alert settings (Discord webhook) root's alone; the socket for Nova's account only",
      so.split() == ["root:nova-api", "640", "root:wheel", "700", "root:nova-api", "660"], so)

print("\nThe API")
s, r = raw("GET", "/api/v1/ping"); check("ping answers", s == 200, (s, r))
s, r = raw("GET", "/"); check("serves the web app", s == 200 and b"<html" in r.lower(), (s, r[:100]))
s, r = raw("GET", "/api/v1/overview"); check("unsigned requests are refused", s in (401, 403), s)

print("\nPairing")
rc, so, se = nova("add", "--json")
try: inv = json.loads(so.strip().splitlines()[-1])
except Exception: inv = {}
check("nova add makes a code", rc == 0 and len(inv.get("code", "")) == 10 and inv.get("payload"), (rc, so[-200:], se[-300:]))
qr = json.loads(inv.get("payload") or "{}")
check("…whose QR carries the encrypted address and the certificate's fingerprint", str(qr.get("lan", "")).startswith("https://") and len(qr.get("pin", "")) == 64, qr)
k = ec.generate_private_key(ec.SECP256R1())
s, r = raw("POST", "/api/v1/pair", json.dumps({"code": "WRONGCODE1", "name": "ci", "public_key": pem_of(k)}).encode(), {"Content-Type": "application/json"})
check("a wrong code is refused", s == 401, (s, r))
s, r = raw("POST", "/api/v1/pair", json.dumps({"code": inv.get("code", ""), "name": "CI test device", "public_key": pem_of(k), "form": "desktop"}).encode(), {"Content-Type": "application/json"})
try: pr = json.loads(r)
except ValueError: pr = {}
check("the right code pairs", s == 200 and pr.get("device_id"), (s, r[:300]))
check("…and gives the certificate pin for the encrypted listener", len(pr.get("lan_pin", "")) == 64 and str(pr.get("lan_url", "")).startswith("https://"), pr)
d = Dev(pr.get("device_id", "x"), k)

print("\nSigned requests")
s, r = d.call("GET", "/api/v1/whoami"); check("whoami", s == 200 and r.get("role") == "admin", (s, r))
s, ov = d.call("GET", "/api/v1/overview", timeout=60)
sv = ov.get("server", {}) if s == 200 else {}
check("overview", s == 200 and sv.get("os") == "macos", (s, str(ov)[:300]))
check("…knows the machine", bool(sv.get("name")) and sv.get("ram_gb", 0) >= 1 and "Darwin" in sv.get("kernel", "") and sv.get("uptime_s", 0) > 0, sv)
check("…has a CPU name and says which Mac it is", len(sv.get("cpu", "")) > 3 and "Apple" in str(sv.get("board", "")), (sv.get("cpu"), sv.get("board")))
check("…says which picture Home shows", sv.get("look") in ("tower", "mini", "laptop"), sv.get("look"))
ft = ov.get("features", {})
check("…says what a Mac server has (terminal, files) and doesn't have yet (the store, backups)",
      ft.get("terminal") is not False and ft.get("files") is not False and ft.get("store") is False and ft.get("backup") is False and ft.get("lan_tls") is True, ft)
print("     ", json.dumps(sv)[:400])
time.sleep(4)
s, st = d.call("GET", "/api/v1/stats"); now = st.get("now", {}) if s == 200 else {}
check("live numbers", s == 200 and 0 <= now.get("cpu", -1) <= 100 and 0 < now.get("mem", -1) <= 100 and now.get("mem_total_gb", 0) > 0, (s, str(st)[:300]))
check("…per core", len(now.get("cpus", [])) == os.cpu_count() and all(0 <= c <= 100 for c in now.get("cpus", [])), now.get("cpus"))
check("…network and drive activity", isinstance(now.get("rx"), int) and isinstance(now.get("disks"), dict) and len(now["disks"]) >= 1, (now.get("rx"), now.get("disks")))
check("…history is building", len(st.get("recent", [])) >= 2, len(st.get("recent", [])))
print("     ", json.dumps({k_: v for k_, v in now.items() if k_ != "cpus"})[:400])
s, r = d.call("GET", "/api/v1/processes", timeout=60); ps = r.get("processes", []) if s == 200 else []
check("processes (through the helper's socket)", s == 200 and len(ps) > 15 and any("python" in p["name"].lower() for p in ps) and all(p["pid"] >= 0 for p in ps), (s, str(r)[:300]))
check("…with memory, owner and the system's own marked", any(p["mem"] > 1e6 for p in ps) and any(p["kernel"] for p in ps) and any(p["user"] == "root" for p in ps), ps[:3])
s, r = d.call("GET", "/api/v1/hardware", timeout=90); dr = r.get("drives", []) if s == 200 else []
check("drives", s == 200 and len(dr) >= 1 and all(x["size"] > 0 and x["name"].startswith("disk") for x in dr), (s, str(r)[:400]))
boot = next((x for x in dr if x["protected"]), None)
check("…the Mac's own drive is found, protected, with its space", bool(boot) and boot["role"] == "Boot drive" and any(u["mount"] == "/" and u["total"] > u["free"] > 0 for u in boot["usage"]), boot)
check("…drive activity names match the drive list", set(now.get("disks", {})) & {x["name"] for x in dr}, (list(now.get("disks", {})), [x["name"] for x in dr]))
print("     ", json.dumps(dr)[:500])
s, r = d.call("GET", "/api/v1/devices"); check("devices", s == 200 and "CI test device" in json.dumps(r), (s, str(r)[:200]))
s, r = d.call("GET", "/api/v1/events"); check("events", s == 200, (s, str(r)[:200]))
s, r = d.call("GET", "/api/v1/containers", timeout=60); check("containers (none here is fine)", s == 200 and isinstance(r.get("containers"), list), (s, str(r)[:300]))
s, r = d.call("POST", "/api/v1/power/reboot"); check("restarting isn't done on one device's word: it waits for a second approval", s == 202 and r.get("approval") and len(r.get("code", "")) >= 6, (s, r))
rc, so, se = nova("approve"); check("…which `nova approve` lists", rc == 0 and r.get("code", "?") in so, (rc, so[-300:], se[-200:]))

print("\nPower")
d.su = ec.generate_private_key(ec.SECP256R1())
s, r = d.call("POST", "/api/v1/device/stepup-key", {"public_key": pem_of(d.su)}); check("this device turns on fingerprint confirmation", s == 200, (s, r))
at = int(time.time()) + 3600
s, r = d.call("POST", "/api/v1/power/schedule", {"action": "reboot", "at": at}, stepup=True); sid = r.get("id", "")
check("schedule a restart (fingerprint-confirmed)", s in (200, 201) and len(sid) == 8, (s, r))
s, r = d.call("GET", "/api/v1/power/schedule"); check("…it's listed", s == 200 and any(x["id"] == sid and x["at"] == at for x in r.get("schedules", [])), (s, r))
rc, so, se = sh("sudo", "cat", DATA + "/data/power-sched.json"); check("…and kept where the helper service looks", sid in so, so[-200:])
s, r = d.call("DELETE", f"/api/v1/power/schedule/{sid}", stepup=True); check("cancel it", s == 200, (s, r))
s, r = d.call("GET", "/api/v1/power/schedule"); check("…gone", s == 200 and r.get("schedules") == [], (s, r))
s, r = d.call("POST", "/api/v1/power/keep-awake", {"hours": 0.1}); check("keep awake for a few minutes", s == 200 and r.get("on") and r.get("until", 0) > time.time() + 200, (s, r))
time.sleep(2)
rc, so, se = sh("pmset", "-g", "assertions")
check("…and macOS really holds a no-sleep request for it (caffeinate)", "caffeinate" in so and "PreventUserIdleSystemSleep" in so, so[-600:])
s, r = d.call("GET", "/api/v1/power/keep-awake"); check("…which the status shows", s == 200 and r.get("on") is True and r.get("forever") is False, (s, r))
s, r = d.call("POST", "/api/v1/power/keep-awake", {"off": True}); time.sleep(1)
rc, so, se = sh("pmset", "-g", "assertions")
check("turning it off lets the Mac sleep again", s == 200 and r.get("on") is False and "caffeinate" not in so, (r, so[-300:]))

print("\nThe helper (what runs as root)")
victim = subprocess.Popen(["sleep", "300"])
time.sleep(1); rc, r = helper("proc-signal", str(victim.pid), "kill"); time.sleep(1)
check("force-stop a process", rc == 0 and r.get("ok") and victim.poll() is not None, (rc, r, victim.poll()))
rc, r = helper("proc-signal", "1", "kill"); check("…but not macOS itself", rc != 0 and "error" in r, (rc, r))
rc, r = helper("partitions", "disk0"); check("what isn't there yet says so plainly", rc != 0 and "Mac" in r.get("error", ""), (rc, r))
rc, r = helper("get-notify"); check("notification settings", rc == 0 and r.get("push_min_level") == "info", (rc, r))
rc, r = helper("set-notify", "push_min_level", "warning"); rc2, r2 = helper("get-notify")
check("…can be changed", rc == 0 and r2.get("push_min_level") == "warning", (r, r2)); helper("set-notify", "push_min_level", "info")
rc, r = helper("tools-status"); check("tools", rc == 0 and "docker" in r.get("tools", {}), (rc, r))
rc, r = helper("list-dirs", "/"); check("folder picker starts with people's folders", rc == 0 and any(x["path"] == f"/Users/{ME}" for x in r.get("dirs", [])), (rc, r))

print("\nThe terminal and the file manager (as your own account)")
s, r = d.call("GET", "/api/v1/files?path=~", timeout=60)
check("the file manager lists your home folder", s == 200 and r.get("path") == f"/Users/{ME}" and isinstance(r.get("items"), list), (s, str(r)[:300]))
term = subprocess.Popen(["sudo", PY, "-I", LIB + "/helper.py", "host-shell", "CI"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
time.sleep(3); term.stdin.write(b"echo nova-term-$((40+2)); whoami\n"); term.stdin.flush()
time.sleep(4); term.stdin.write(b"exit\n"); term.stdin.flush(); time.sleep(2); term.stdin.close()
try: so = term.stdout.read().decode("utf-8", "replace"); se = term.stderr.read().decode("utf-8", "replace"); term.wait(30)
except Exception as e: so, se = "", str(e); term.kill()
check("the server terminal opens a shell as you (never root)", "nova-term-42" in so and ME in so.split("nova-term-42")[-1] and "root" not in so.split("nova-term-42")[-1], (so[-400:], se[-300:]))

print("\nThe phone's Terminal (SSH, with Remote Login on)")
s, r = d.call("GET", "/api/v1/ssh-hostkeys")
check("ssh-hostkeys: the Mac's host keys, the account, and where to connect", s == 200 and any(x.startswith(("ecdsa-", "ssh-")) for x in r.get("keys", [])) and r.get("user") == ME and r.get("hosts"), (s, r))
sk = ec.generate_private_key(ec.SECP256R1())
pub = sk.public_key().public_bytes(serialization.Encoding.OpenSSH, serialization.PublicFormat.OpenSSH).decode()
s, r = d.call("GET", "/api/v1/ssh/authorize"); check("the phone's key isn't there yet", s == 200 and r.get("installed") is False, (s, r))
s, r = d.call("POST", "/api/v1/ssh/authorize", {"key": pub}, stepup=True); check("add the phone's key (fingerprint-confirmed)", s == 200 and r.get("ok"), (s, r))
s, r = d.call("GET", "/api/v1/ssh/authorize"); check("…now it's there", s == 200 and r.get("installed") is True, (s, r))
kf = "/tmp/nova-ci-key"
with open(os.open(kf, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "wb") as f:
    f.write(sk.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.OpenSSH, serialization.NoEncryption()))
ssh = ["ssh", "-i", kf, "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes", "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null", f"{ME}@127.0.0.1", "echo nova-ssh-ok"]
rc, so, se = sh(*ssh, timeout=60); check("…and it really logs in over SSH", "nova-ssh-ok" in so, (rc, so[-200:], se[-300:]))

print("\nThe monitor")
time.sleep(25)                                                # (a pass every 20 s)
try: stt = json.load(open(DATA + "/alerts/www/status.json"))
except Exception as e: stt = {"_": str(e)}
check("writes the server's status, readable by anyone on this Mac (hub apps read it)", stt.get("level") in ("ok", "info", "warning", "critical"), str(stt)[:300])
mt = stt.get("metrics", {})
check("…with memory, uptime and storage", "memory" in mt and "uptime" in mt and any(x.get("mount") == "/" for x in mt.get("storage", [])), mt)
check("…and where Nova answers (for hub apps)", stt.get("setup", {}).get("done") is True and stt.get("ports", {}).get("https") == 8495 and str(stt.get("urls", {}).get("local", "")).startswith("https://"), {k_: stt.get(k_) for k_ in ("setup", "ports", "urls")})
print("     ", stt.get("headline"), "|", json.dumps(mt)[:400])
rc, so, se = nova("alert", "info", "Hello from the test", "A test alert."); time.sleep(25)
s, r = d.call("GET", "/api/v1/events"); check("an alert sent with `nova alert` reaches the Inbox", s == 200 and "Hello from the test" in json.dumps(r), str(r)[:300])

print("\nThe nova command")
rc, so, se = nova("status", "--json")
try: sj = json.loads(so)
except ValueError: sj = {}
check("nova status --json", rc == 0 and sj.get("running") is True and len(sj.get("parts", [])) == 3 and all(p["state"] == "active" for p in sj["parts"]), so[-400:] + se[-200:])
rc, so, se = nova("status"); check("nova status", rc == 0 and "Home network" in so, so[-400:] + se[-200:])
rc, so, se = nova("doctor", "--json")
try: dj = json.loads(so)
except ValueError: dj = {}
check("nova doctor --json finds nothing that stops it", isinstance(dj.get("checks"), list) and not [c for c in dj["checks"] if c.get("level") == "stop"], so[-500:] + se[-200:])
print("      " + " | ".join(f"{c.get('level')}: {c.get('title')}" for c in dj.get("checks", []))[:700])
rc, so, se = nova("devices"); check("nova devices", rc == 0 and "CI test device" in so, so[-300:] + se[-200:])
rc, so, se = nova("log", "5"); check("nova log", rc == 0 and "/api/v1/" in so, so[-300:] + se[-200:])
rc, so, se = nova("approve", "ZZZZZZ"); check("a wrong approval code answers at once", rc == 1 and "Nothing is waiting" in so, (rc, so[-200:]))
rc, so, se = sh("/usr/local/bin/nova", "status"); check("without sudo it says to use sudo", rc != 0 and "sudo nova status" in (so + se), (rc, so[-200:], se[-200:]))
rc, so, se = nova("setup"); check("nova setup explains that a Mac sets itself up", "sets itself up" in (so + se), (so + se)[-200:])

print("\nUpdates")
rc, so, se = nova("update", "--check"); check("nova update --check", ("newest Nova" in so or "is out" in so) or "GitHub answered" in (so + se), (rc, so[-300:], se[-300:]))
s, r = d.call("GET", "/api/v1/server/update", timeout=60)
check("the app is told which Nova this server has, and that updates come from Nova's signed releases", s == 200 and r.get("installed") and r.get("source", "").startswith("github:") and r.get("running") is False, (s, r))
s, r = d.call("POST", "/api/v1/server/update/check", timeout=120)
check("…and can look for a newer one", s in (200, 400) and ("newest Nova" in r.get("message", "") or "is out" in r.get("message", "") or "Couldn't check" in r.get("message", "")), (s, r))
print("     ", r.get("message"))

print("\nThe Nova Server window")
p = subprocess.Popen(["sudo", PY, LIB + "/nova-gui", "--serve", "--print-url"], stdout=subprocess.PIPE, text=True)
url = (p.stdout.readline() or "").strip(); check("starts and gives its address", url.startswith("http://localhost:") and "?k=" in url, url[:40])
base, key = url.split("/?k=")[0], url.split("?k=")[-1].split("&")[0]
def g(path, body=None):
    rq = urllib.request.Request(base + path, data=json.dumps(body).encode() if body is not None else None, method="POST" if body is not None else "GET", headers={"X-Nova-Key": key, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(rq, timeout=90) as r_: return r_.status, json.loads(r_.read())
    except urllib.error.HTTPError as e: return e.code, {}
    except Exception as e: return -1, {"_": str(e)}
s, r = g("/api/state"); check("shows the state", s == 200 and r.get("running") and any(x["name"] == "CI test device" for x in r.get("devices", [])) and all(x["state"] == "active" for x in r.get("parts", [])), (s, str(r)[:400]))
s, r = g("/api/pair", {}); check("pairs with a QR", r.get("ok") and r.get("svg", "").startswith("<svg"), str(r)[:200]); g("/api/pair/cancel", {})
s, r = g("/api/doctor"); check("runs the check-up", s == 200 and any(c["title"] == "Nova is running" for c in r.get("checks", [])), str(r)[:300])
s, r = g("/api/update-check"); print("      update check:", str(r)[:200])      # (talks to GitHub: shown, not judged)
g("/api/quit", {})

print("\nRemoving a device")
rc, so, se = nova("remove", "CI test device"); check("nova remove", rc == 0, (rc, so[-200:], se[-300:]))
s, r = d.call("GET", "/api/v1/whoami"); check("…and the removed device is locked out", s in (401, 403), (s, r))
rc, so, se = sh(*ssh, timeout=60); check("…and its SSH key is gone with it", "nova-ssh-ok" not in so, so[-200:])

print(f"\n{'ALL PASSED' if not fails else str(len(fails)) + ' FAILED: ' + '; '.join(fails)}")
sys.exit(1 if fails else 0)
