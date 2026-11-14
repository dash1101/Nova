"""Nova web's requests for another server (Servers → Control from here) must pass the relay (nodes.relay) as the web
really signs them: every header signHeaders() in server/web/core.js adds. 0.7.5 added X-Nova-Client there, and the relay
refused every request ("only the four X-Nova signature headers") until 0.9.27. Run by ci.yml; no network, no server."""
import os, re, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "server", "api"))
import nodes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization

js = open(os.path.join(ROOT, "server", "web", "core.js"), encoding="utf-8").read()
body = js[js.index("async function signHeaders"):]; body = body[:body.index("\n}")]
names = re.findall(r'"(X-Nova-[A-Za-z-]+)"\s*:', body)
assert {"X-Nova-Device", "X-Nova-Time", "X-Nova-Nonce", "X-Nova-Signature"} <= set(names), names
sample = {"X-Nova-Device": "dev12345", "X-Nova-Time": "1794616313839", "X-Nova-Nonce": "abcdefghijklmnopqrstuvwx", "X-Nova-Signature": "A" * 88}
for n in names:
    if n not in sample:
        m = re.search(rf'"{n}"\s*:\s*"([^"]*)"', body)
        sample[n] = m.group(1) if m else "x"

k = ec.generate_private_key(ec.SECP256R1()).private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode()
nodes._load = lambda: {"nodes": [{"id": "n1", "name": "other", "host": "192.0.2.9", "pin": "x", "key": k, "device_id": "headdev1"}]}
sent = {}
class Resp:
    status = 200
    def read(self, n): return b'{"ok": true}'
    def getheader(self, k, d=""): return "application/json"
class Conn:
    def request(self, m, p, body=None, headers=None): sent.update(headers)
    def getresponse(self): return Resp()
    def close(self): pass
nodes._conn = lambda *a, **kw: (Conn(), None)

fails = 0
def check(name, ok, info=""):
    global fails; fails += not ok; print(("  ok   " if ok else "  FAIL ") + name + ("" if ok else f"  [{info}]"))
try: r = nodes.relay("n1", "GET", "/api/v1/overview", sample, "", "lan", "192.0.2.5"); ok = r[0] == 200
except ValueError as e: ok, r = False, e
check(f"the relay takes Nova web's headers as it signs them ({', '.join(names)})", ok, r)
check("…and passes them on", all(sent.get(n) == v for n, v in sample.items()), sent)
for bad, why in (({**sample, "Cookie": "a=b"}, "another header"), ({**sample, "X-Nova-Client": "web 1\r\nX-Evil: 1"}, "a header with a line break")):
    try: nodes.relay("n1", "GET", "/api/v1/overview", bad, "", "lan", "192.0.2.5"); check(f"{why} is refused", False, "accepted")
    except ValueError: check(f"{why} is refused", True)
sys.exit(1 if fails else 0)
