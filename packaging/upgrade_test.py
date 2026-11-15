"""
An upgrade keeps what matters: a phone paired with the old version still works with the new one, without pairing again.

    python3 upgrade_test.py before     on the OLD version: pair a device, remember it, the certificate and the settings
    python3 upgrade_test.py after      on the NEW version: the same device signs a request, the certificate is the same one

Run by .github/workflows/linux.yml around a real update (the previously published release → the build under test, done
by the old release's own updater), as a normal user with sudo.
"""
import base64, hashlib, json, os, secrets, ssl, subprocess, sys, time, urllib.error, urllib.request
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

KEEP = "/tmp/nova-upgrade.json"
HOST = os.environ.get("NOVA_HOST", "127.0.0.1")
CTX = ssl.create_default_context(); CTX.check_hostname = False; CTX.verify_mode = ssl.CERT_NONE
fails = []

def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + (f"   [{str(detail)[:400]}]" if detail and not ok else ""), flush=True)
    if not ok: fails.append(name)

def sh(*cmd, timeout=180):
    r = subprocess.run(list(cmd), capture_output=True, text=True, timeout=timeout); return r.returncode, r.stdout, r.stderr

def http(method, path, body=b"", headers=None):
    rq = urllib.request.Request(f"https://{HOST}:8495{path}", data=body if method != "GET" else None, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(rq, timeout=30, context=CTX) as r: return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e: return e.code, {"_": e.read()[:200].decode("utf-8", "replace")}
    except Exception as e: return -1, {"_": str(e)}

def signed(did, key, method, path):
    ts = str(int(time.time() * 1000)); nonce = secrets.token_urlsafe(18)
    msg = "\n".join([method, path, ts, nonce, hashlib.sha256(b"").hexdigest()]).encode()
    return http(method, path, headers={"X-Nova-Device": did, "X-Nova-Time": ts, "X-Nova-Nonce": nonce,
                                       "X-Nova-Signature": base64.b64encode(key.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()})

def cert_pin():
    return hashlib.sha256(ssl.PEM_cert_to_DER_cert(ssl.get_server_certificate((HOST, 8495)))).hexdigest()

def version():
    s, r = http("GET", "/api/v1/ping"); return r.get("api", "")

if sys.argv[1:] == ["before"]:
    print("\nBefore the upgrade (the previously published release)")
    rc, so, se = sh("sudo", "nova", "add", "--json"); inv = json.loads(so.strip().splitlines()[-1]) if rc == 0 else {}
    k = ec.generate_private_key(ec.SECP256R1())
    pem = k.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    s, pr = http("POST", "/api/v1/pair", json.dumps({"code": inv.get("code", ""), "name": "Phone from before the upgrade", "public_key": pem}).encode(), {"Content-Type": "application/json"})
    check("a device pairs with the old version", s == 200 and pr.get("device_id"), (s, pr))
    s, w = signed(pr.get("device_id", "x"), k, "GET", "/api/v1/whoami"); check("…and its signed requests work", s == 200 and w.get("role") == "admin", (s, w))
    rc, cfg, _ = sh("sudo", "cat", "/etc/nova-api/config.json")
    json.dump({"id": pr.get("device_id"), "key": k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode(),
               "pin": cert_pin(), "lan_pin": pr.get("lan_pin"), "version": version(), "config": json.loads(cfg or "{}")}, open(KEEP, "w"))
    print("      old version:", version(), "· certificate", cert_pin()[:12])
elif sys.argv[1:] == ["after"]:
    print("\nAfter the upgrade (the build under test)")
    b = json.load(open(KEEP)); k = serialization.load_pem_private_key(b["key"].encode(), None)
    for _ in range(40):
        if version(): break
        time.sleep(2)
    print("     ", b["version"], "→", version())
    want = os.environ.get("NOVA_EXPECT_VERSION", "")
    check("Nova came back, on the new version", bool(version()) and (not want or version() == want), (version(), want))
    for u in ("nova-api.service", "nova-helper.socket", "nova-alerts.timer"):
        rc, so, _ = sh("systemctl", "is-active", u); check(f"{u} is active", so.strip() == "active", so)
    check("the certificate is the same one (phones pin it: a new one would lock every phone out)", cert_pin() == b["pin"] == b["lan_pin"], (cert_pin(), b["pin"]))
    s, w = signed(b["id"], k, "GET", "/api/v1/whoami"); check("the phone paired before the upgrade still gets in, without pairing again", s == 200 and w.get("role") == "admin", (s, w))
    s, o = signed(b["id"], k, "GET", "/api/v1/overview"); check("…and sees the server", s == 200 and o.get("server", {}).get("os") == "linux", (s, str(o)[:200]))
    rc, cfg, _ = sh("sudo", "cat", "/etc/nova-api/config.json"); now = json.loads(cfg or "{}")
    lost = {k_: v for k_, v in b["config"].items() if now.get(k_) != v}
    check("the settings are as they were", not lost, lost)
    rc, so, se = sh("sudo", "nova", "doctor", "--json")
    try: stops = [c for c in json.loads(so).get("checks", []) if c.get("level") == "stop"]
    except ValueError: stops = [so[-300:]]
    check("the check-up finds nothing that stops it", not stops, stops)
    rc, so, se = sh("sudo", "nova", "devices"); check("nova devices still lists the phone", "Phone from before the upg" in so and b["id"][:8] in so, so[-300:])
else:
    sys.exit("usage: upgrade_test.py before|after")
print(f"\n{'ALL PASSED' if not fails else str(len(fails)) + ' FAILED: ' + '; '.join(fails)}")
sys.exit(1 if fails else 0)
