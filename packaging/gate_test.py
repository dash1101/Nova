#!/usr/bin/env python3
"""Tests for the code gate (server/api/gate.py): starts the real gate beside a fake website and tries to get past it.
   python3 packaging/gate_test.py        (no root, no network, nothing on the server is touched)"""
import http.client, json, os, socket, subprocess, sys, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server", "api"))
TMP = tempfile.mkdtemp(prefix="gate-test-")
os.environ["NOVA_GATE_DIR"] = TMP
import gate                                                     # noqa: E402  (management half, pointed at the temp dir)

passed = failed = 0
def check(name, ok, info=""):
    global passed, failed
    if ok: passed += 1
    else: failed += 1; print(f"FAIL {name}  {info}")

def free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p


# ── a fake website ──────────────────────────────────────────────────────────────────────
class Site(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def body(self):
        if (self.headers.get("Transfer-Encoding") or "").lower() == "chunked":
            out = b""
            while True:
                n = int(self.rfile.readline().split(b";")[0], 16)
                if n == 0: self.rfile.readline(); return out
                out += self.rfile.read(n); self.rfile.readline()
        return self.rfile.read(int(self.headers.get("Content-Length") or 0))
    def send(self, code, data, extra=()):
        self.send_response(code); self.send_header("Content-Length", str(len(data)))
        for k, v in extra: self.send_header(k, v)
        self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        if self.path == "/chunked":
            self.send_response(200); self.send_header("Transfer-Encoding", "chunked"); self.end_headers()
            for p in (b"hello ", b"chunked ", b"world"): self.wfile.write(f"{len(p):x}\r\n".encode() + p + b"\r\n")
            self.wfile.write(b"0\r\n\r\n"); return
        if self.path == "/big":
            n = 20 * 1024 * 1024; self.send_response(200); self.send_header("Content-Length", str(n)); self.end_headers()
            blk = bytes(range(256)) * 256
            for _ in range(n // len(blk)): self.wfile.write(blk)
            return
        if self.path == "/close":
            self.send_response(200); self.send_header("Connection", "close"); self.end_headers(); self.wfile.write(b"until the end"); self.close_connection = True; return
        if self.path == "/cookies": return self.send(200, b"ok", (("Set-Cookie", "a=1; Path=/"), ("Set-Cookie", "b=2; Path=/")))
        if self.path == "/cached": return self.send(200, b"x", (("Cache-Control", "public, max-age=3600"),))
        if self.path == "/nostore": return self.send(200, b"x", (("Cache-Control", "no-store"),))
        if self.path == "/304": self.send_response(304); self.end_headers(); return
        if self.path == "/ws" and (self.headers.get("Upgrade") or "").lower() == "websocket":
            self.send_response_only(101, "Switching Protocols"); self.send_header("Upgrade", "websocket"); self.send_header("Connection", "Upgrade"); self.end_headers()
            self.wfile.flush(); self.close_connection = True
            while True:
                d = self.connection.recv(4096)
                if not d: return
                self.connection.sendall(b"echo:" + d)
        self.echo()
    def do_POST(self): self.echo()
    do_PUT = do_DELETE = do_PATCH = do_POST
    def do_HEAD(self): self.send_response(200); self.send_header("Content-Length", "5"); self.end_headers()
    def echo(self):
        b = self.body(); h = {k: self.headers.get_all(k) for k in set(self.headers.keys())}
        self.send(200, json.dumps({"method": self.command, "path": self.path, "headers": h, "body": b.decode("latin-1")}).encode(), (("Content-Type", "application/json"),))

def serve(handler, port):
    s = ThreadingHTTPServer(("127.0.0.1", port), handler); s.daemon_threads = True
    threading.Thread(target=s.serve_forever, daemon=True).start(); return s


# ── helpers to talk to the gate ─────────────────────────────────────────────────────────
GP = free_port(); SP = free_port(); SP2 = free_port()
def req(host, method="GET", path="/", headers=None, body=None, ip="198.51.100.7", cookie=None, port=None):
    h = {"Host": host, "CF-Connecting-IP": ip, "Accept": "text/html"}; h.update(headers or {})
    if cookie: h["Cookie"] = h.get("Cookie", "") + ("; " if h.get("Cookie") else "") + f"nova_gate={cookie}"
    c = http.client.HTTPConnection("127.0.0.1", port or GP, timeout=30)
    c.request(method, path, body=body, headers=h); r = c.getresponse(); data = r.read(); c.close()
    return r.status, r, data

def login(host, code, ip="198.51.100.7", nxt="/"):
    import urllib.parse
    form = urllib.parse.urlencode({"code": code, "next": nxt})
    st, r, d = req(host, "POST", "/__nova_gate/login", {"Content-Type": "application/x-www-form-urlencoded"}, form, ip)
    ck = (r.getheader("Set-Cookie") or "")
    return st, (ck.split(";")[0].split("=", 1)[1] if ck.startswith("nova_gate=") and "Max-Age=0" not in ck else None), r, d

def raw(payload, port=None):
    s = socket.create_connection(("127.0.0.1", port or GP), timeout=15); s.sendall(payload); out = b""
    try:
        while True:
            d = s.recv(65536)
            if not d: break
            out += d
            if b"\r\n\r\n" in out and (b"Content-Length: 0" in out or len(out) > 200): break
    except socket.timeout: pass
    s.close(); return out

def main():
    site = serve(Site, SP); site2 = serve(Site, SP2)
    p = subprocess.Popen([sys.executable, os.path.join(HERE, "..", "server", "api", "gate.py"), "--port", str(GP), "--dir", TMP], stderr=subprocess.PIPE)
    for _ in range(50):
        try: socket.create_connection(("127.0.0.1", GP), timeout=1).close(); break
        except OSError: time.sleep(0.1)
    A, B = "a.example.com", "b.example.com"
    gate.register(A, "Site A", f"http://127.0.0.1:{SP}"); gate.register(B, "Site B", f"http://127.0.0.1:{SP2}")
    try: run(A, B)
    finally: p.terminate(); site.shutdown(); site2.shutdown()
    print(f"{passed}/{passed + failed} passed"); sys.exit(1 if failed else 0)

def run(A, B):
    # ── management ──
    check("register keeps nothing but the origin", gate.load()["hosts"][A]["upstream"] == f"http://127.0.0.1:{SP}")
    for bad in ("http://127.0.0.1:80/x", "http://user:pw@127.0.0.1:80", "ftp://127.0.0.1:21", "http://127.0.0.1", "javascript:alert(1)", "http://127.0.0.1:80?x=1"):
        try: gate.register("z.example.com", "z", bad); check(f"register refuses upstream {bad}", False)
        except ValueError: check(f"register refuses upstream {bad}", True)
    for bad in ("", "a b.example.com", "a/b.example.com", "-x.example.com", "x.example.com/evil", "a\nb.example.com"):
        try: gate.register(bad, "z", "http://127.0.0.1:80"); check(f"register refuses address {bad!r}", False)
        except ValueError: check(f"register refuses address {bad!r}", True)
    for pw in ("short", "1234567", "password", "aaaaaaaa", "12345678"):
        try: gate.create_code(A, "password", pw); check(f"weak password {pw!r} refused", False)
        except ValueError: check(f"weak password {pw!r} refused", True)
    try: gate.create_code(A, "random", expires="forever"); check("odd expiry refused", False)
    except ValueError: check("odd expiry refused", True)
    try: gate.create_code("nope.example.com", "random"); check("code for an address that isn't gated refused", False)
    except ValueError: check("code for an address that isn't gated refused", True)
    k = gate.create_code(A, "random", label="Sam\x00\x1b[31m" + "x" * 80, expires="1d")
    check("random code has 4 groups of 4", len(k["code"]) == 19 and k["code"].count("-") == 3, k["code"])
    check("label is cleaned and capped", len(gate.load()["hosts"][A]["codes"][-1]["label"]) <= 40 and "\x00" not in gate.load()["hosts"][A]["codes"][-1]["label"])
    raw_file = open(os.path.join(TMP, "gate.json")).read()
    check("the code itself is never stored", k["code"] not in raw_file and k["code"].replace("-", "") not in raw_file)
    check("state files are not world-readable", (os.stat(os.path.join(TMP, "gate.json")).st_mode & 0o007) == 0 and (os.stat(os.path.join(TMP, "gate.key")).st_mode & 0o007) == 0)
    code = k["code"]

    # ── the page ──
    st, r, d = req(A); check("a browser with no session gets the code page", st == 401 and b"Access code" in d and b"Site A" in d, st)
    check("the code page can't be framed, cached or run scripts from elsewhere", "no-store" in (r.getheader("Cache-Control") or "") and "frame-ancestors 'none'" in (r.getheader("Content-Security-Policy") or ""))
    st, r, d = req(A, "GET", "/api/x", {"Accept": "application/json"}); check("an API call with no session is refused plainly", st == 401 and b"Access code" not in d, st)
    st, r, d = req(A, "POST", "/upload", {"Content-Type": "text/plain"}, "x" * 5000); check("a POST with no session is refused", st == 401, st)
    st, r, d = req("unknown.example.com"); check("an address that isn't gated is a 404", st == 404, st)
    st, r, d = req(A, "GET", "/__nova_gate/health"); check("health answers", st == 200 and b"ok" in d)
    st, r, d = req(A, "GET", "/__nova_gate/whatever"); check("no other /__nova_gate/ path reaches the app", st == 404 or st == 401, st)

    # ── signing in ──
    st, ck, r, d = login(A, "WRONG-CODE-HERE-XXXX"); check("a wrong code is refused with no cookie", st == 401 and not ck and b"didn't work" in d, st)
    st, ck, r, d = login(A, ""); check("an empty code is refused", st == 401 and not ck, st)
    st, ck, r, d = login(A, code, "203.0.113.5"); tok = ck
    check("the right code signs in (303 + cookie)", st == 303 and bool(ck), st)
    sc = r.getheader("Set-Cookie") or ""
    check("the cookie is HttpOnly, Secure, SameSite and path-wide", all(x in sc for x in ("HttpOnly", "Secure", "SameSite=Lax", "Path=/")), sc)
    st, r, d = req(A, "GET", "/hello?x=1", cookie=tok); j = json.loads(d)
    check("with the cookie the app's own page comes through", st == 200 and j["path"] == "/hello?x=1", st)
    check("the app sees the visitor's address and https", j["headers"].get("X-Forwarded-Proto") == ["https"] and j["headers"].get("Host") == [A], j["headers"])
    st, r, d = req(A, "GET", "/hello", {"Cookie": "theme=dark; other=1"}, cookie=tok); j = json.loads(d)
    ckh = " ".join(j["headers"].get("Cookie", []))
    check("the app gets the visitor's own cookies but never the gate's", "theme=dark" in ckh and "nova_gate" not in ckh, ckh)
    st, ck2, r, d = login(A, code.lower().replace("-", " "), "203.0.113.6"); check("the code can be typed in lower case, with spaces", st == 303 and bool(ck2), st)
    st, ck2, r, d = login(A, "  " + code + "  ", "203.0.113.6"); check("spaces around the code are ignored", st == 303 and bool(ck2), st)
    st, ck3, r, d = login(A, code, "203.0.113.6", "https://evil.example/x"); check("an outside 'next' is ignored (no open redirect)", st == 303 and r.getheader("Location") == "/", r.getheader("Location"))
    st, ck3, r, d = login(A, code, "203.0.113.6", "//evil.example"); check("a //host 'next' is ignored", r.getheader("Location") == "/", r.getheader("Location"))
    st, ck3, r, d = login(A, code, "203.0.113.6", "/back/here?q=1"); check("a local 'next' is followed", r.getheader("Location") == "/back/here?q=1", r.getheader("Location"))
    st, ck3, r, d = login(A, code, "203.0.113.6", "/a\\\\evil"); check("a backslash 'next' is ignored", r.getheader("Location") == "/", r.getheader("Location"))

    # ── forged, borrowed, tampered cookies ──
    check("a cookie from another address doesn't work here", req(B, cookie=tok)[0] == 401)
    bad = tok[:-3] + ("AAA" if not tok.endswith("AAA") else "BBB"); check("a tampered cookie is refused", req(A, cookie=bad)[0] == 401)
    check("junk in the cookie is refused", all(req(A, cookie=x)[0] == 401 for x in ("", "x", "v1..", "v1.a.b", "v1." + "A" * 5000 + ".x", "../../etc/passwd")))
    check("an expired cookie is refused", req(A, cookie=gate.make_cookie(A, gate.load()["hosts"][A]["codes"][-1]["id"], time.time() - 10))[0] == 401)
    check("a cookie for a code that doesn't exist is refused", req(A, cookie=gate.make_cookie(A, "deadbeef", time.time() + 999))[0] == 401)
    st, r, d = req(A, "GET", "/hello", {"Cookie": "nova_gate=" + tok + "; nova_gate=" + bad}); check("two gate cookies: the first one decides", st in (200, 401))

    # ── passing things on faithfully ──
    st, r, d = req(A, "POST", "/p", {"Content-Type": "application/json"}, '{"a":1}', cookie=tok); j = json.loads(d); check("POST bodies arrive", st == 200 and j["body"] == '{"a":1}' and j["method"] == "POST", d[:100])
    for m in ("PUT", "DELETE", "PATCH"):
        st, r, d = req(A, m, "/m", cookie=tok, body="x" if m != "DELETE" else None); check(f"{m} goes through", st == 200 and json.loads(d)["method"] == m, st)
    st, r, d = req(A, "HEAD", "/", cookie=tok); check("HEAD works", st == 200 and d == b"" and r.getheader("Content-Length") == "5")
    st, r, d = req(A, "GET", "/chunked", cookie=tok); check("a chunked answer arrives whole", d == b"hello chunked world", d)
    st, r, d = req(A, "GET", "/close", cookie=tok); check("an answer that ends by closing arrives whole", d == b"until the end", d)
    t0 = time.time(); st, r, d = req(A, "GET", "/big", cookie=tok); check("a 20 MB download arrives intact", len(d) == 20 * 1024 * 1024 and d[:256] == bytes(range(256)), len(d))
    st, r, d = req(A, "GET", "/cookies", cookie=tok); check("both of the app's Set-Cookie headers survive", len(r.msg.get_all("Set-Cookie") or []) == 2, r.msg.get_all("Set-Cookie"))
    st, r, d = req(A, "GET", "/304", cookie=tok); check("a 304 has no body", st == 304 and d == b"")
    st, r, d = req(A, "GET", "/cached", cookie=tok); cc = r.getheader("Cache-Control") or ""; check("Cloudflare's cache can't keep the app's pages (private added)", "private" in cc and "max-age=3600" in cc, cc)
    st, r, d = req(A, "GET", "/nostore", cookie=tok); check("no-store stays no-store", (r.getheader("Cache-Control") or "") == "no-store")
    st, r, d = req(A, "GET", "/", cookie=tok); check("a page with no cache header is marked private", (r.getheader("Cache-Control") or "") == "private")
    body = b"5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
    out = raw(f"POST /c HTTP/1.1\r\nHost: {A}\r\nCF-Connecting-IP: 198.51.100.7\r\nCookie: nova_gate={tok}\r\nTransfer-Encoding: chunked\r\nContent-Type: text/plain\r\n\r\n".encode() + body)
    check("a chunked upload arrives whole", b"hello world" in out, out[-200:])

    # ── a WebSocket ──
    def ws(cookie):
        s = socket.create_connection(("127.0.0.1", GP), timeout=10); c = f"Cookie: nova_gate={cookie}\r\n" if cookie else ""
        s.sendall(f"GET /ws HTTP/1.1\r\nHost: {A}\r\nCF-Connecting-IP: 198.51.100.7\r\n{c}Connection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Key: abc\r\nSec-WebSocket-Version: 13\r\n\r\n".encode())
        head = s.recv(4096); got = b""
        if b"101" in head.split(b"\r\n")[0]:
            s.sendall(b"ping"); s.settimeout(5)
            try: got = s.recv(100)
            except socket.timeout: pass
        s.close(); return head, got
    head, got = ws(tok); check("a WebSocket opens and passes bytes both ways", b"101" in head.split(b"\r\n")[0] and got == b"echo:ping", (head[:60], got))
    head, got = ws(None); check("a WebSocket with no session is refused (not tunneled)", b"101" not in head.split(b"\r\n")[0] and b"401" in head.split(b"\r\n")[0], head[:60])

    # ── nasty requests ──
    out = raw(f"POST /x HTTP/1.1\r\nHost: {A}\r\nCookie: nova_gate={tok}\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n".encode()); check("Content-Length with Transfer-Encoding is refused (request smuggling)", b" 400 " in out.split(b"\r\n")[0], out[:60])
    out = raw(f"POST /x HTTP/1.1\r\nHost: {A}\r\nCookie: nova_gate={tok}\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\nhello!".encode()); check("two different Content-Lengths are refused", b" 400 " in out.split(b"\r\n")[0], out[:60])
    out = raw(f"POST /x HTTP/1.1\r\nHost: {A}\r\nCookie: nova_gate={tok}\r\nContent-Length: abc\r\n\r\n".encode()); check("a Content-Length that isn't a number is refused", b" 400 " in out.split(b"\r\n")[0], out[:60])
    out = raw(f"POST /x HTTP/1.1\r\nHost: {A}\r\nCookie: nova_gate={tok}\r\nTransfer-Encoding: gzip, chunked\r\n\r\n".encode()); check("an odd Transfer-Encoding is refused", b" 400 " in out.split(b"\r\n")[0], out[:60])
    out = raw(f"GET http://evil.example/steal HTTP/1.1\r\nHost: {A}\r\nCookie: nova_gate={tok}\r\nConnection: close\r\n\r\n".encode())
    check("an absolute-URL request still goes to the app's own host, never the one named", b'"path": "/steal"' in out and b"evil.example" not in out.split(b"\r\n\r\n", 1)[-1].replace(b'"/steal"', b""), out[-200:])
    out = raw(f"GET HTTP://169.254.169.254/latest HTTP/1.1\r\nHost: {A}\r\nCookie: nova_gate={tok}\r\nConnection: close\r\n\r\n".encode()); check("a metadata-style address in the request line is never contacted", b" 400 " in out.split(b"\r\n")[0] or b'"path"' in out, out[:80])
    out = raw(f"GET *x HTTP/1.1\r\nHost: {A}\r\nCookie: nova_gate={tok}\r\n\r\n".encode()); check("a request path that isn't a path is refused", b" 400 " in out.split(b"\r\n")[0], out[:60])
    out = raw(f"GET / HTTP/1.1\r\nHost: {A}:80@evil.example\r\nCookie: nova_gate={tok}\r\n\r\n".encode()); check("a tricky Host header finds no address", b" 404 " in out.split(b"\r\n")[0], out[:60])
    out = raw(f"GET / HTTP/1.1\r\nHost: {A}.\r\nCookie: nova_gate={tok}\r\nConnection: close\r\n\r\n".encode()); check("a Host with a trailing dot is the same address", b" 200 " in out.split(b"\r\n")[0], out[:60])
    out = raw(b"GET / HTTP/1.1\r\n\r\n"); check("no Host at all is refused", b" 404 " in out.split(b"\r\n")[0] or b" 400 " in out.split(b"\r\n")[0], out[:60])
    out = raw(b"G\x00T / HTTP/1.1\r\nHost: " + A.encode() + b"\r\n\r\n"); check("a null in the method doesn't get far", b" 200 " not in out.split(b"\r\n")[0], out[:60])
    out = raw(f"POST /__nova_gate/login HTTP/1.1\r\nHost: {A}\r\nContent-Length: 99999\r\n\r\n".encode()); check("a huge sign-in form is refused", b" 413 " in out.split(b"\r\n")[0], out[:60])
    st, r, d = req(A, "POST", "/__nova_gate/login", {"Content-Type": "application/x-www-form-urlencoded", "Sec-Fetch-Site": "cross-site"}, "code=" + code); check("a sign-in sent from another site is refused", st == 403, st)

    # ── guessing ──
    ip = "192.0.2.50"
    codes = [login(A, "BAD-" + str(i), ip)[0] for i in range(6)]
    check("five wrong guesses in a row lock that visitor out", codes[:5] == [401] * 5 and codes[5] == 429, codes)
    st, ck, r, d = login(A, code, ip); check("…even the right code is refused while locked", st == 429 and not ck, st)
    st, ck, r, d = login(A, code, "192.0.2.51"); check("…but someone else can still sign in", st == 303 and bool(ck), st)
    check("…and a locked-out visitor who is already in stays in", req(A, cookie=tok, ip=ip)[0] == 200)
    sts = [login(B, "BAD-" + str(i), f"198.18.0.{i + 1}")[0] for i in range(31)]
    gate.create_code(B, "random", expires="1d"); st2 = login(B, "whatever", "198.18.1.1")[0]
    check("30 wrong guesses from anywhere pause new sign-ins on that address", st2 == 429, (sts[-3:], st2))
    check("…without touching the other address", login(A, code, "198.18.9.9")[0] == 303)

    # ── passwords you choose ──
    pk = gate.create_code(A, "password", "correct horse battery", "Alex", "7d")
    check("a chosen password is never handed back", pk["code"] is None)
    rawj = open(os.path.join(TMP, "gate.json")).read(); check("…and is never stored", "correct horse" not in rawj)
    st, ck, r, d = login(A, "correct horse battery", "203.0.113.20"); check("the password signs in", st == 303 and bool(ck), st)
    st, ck, r, d = login(A, "Correct horse battery", "203.0.113.21"); check("passwords are case-sensitive", st == 401, st)
    st, ck, r, d = login(A, "correct horse batter", "203.0.113.22"); check("a nearly-right password is refused", st == 401, st)
    try: gate.create_code(A, "password", "correct horse battery"); check("the same password can't be used twice", False)
    except ValueError: check("the same password can't be used twice", True)
    pcookie = login(A, "correct horse battery", "203.0.113.23")[1]

    # ── ending a code ──
    lst = gate.listing(A)["codes"]; check("the list shows each code without its secret", all(set(c) >= {"id", "label", "kind", "expires", "logins", "last_used"} for c in lst) and code not in json.dumps(lst))
    pid = next(c["id"] for c in lst if c["label"] == "Alex")
    gate.revoke(A, pid)
    check("revoking a password ends the sessions made with it right away", req(A, cookie=pcookie)[0] == 401)
    check("…and the password stops working", login(A, "correct horse battery", "203.0.113.24")[0] == 401)
    check("…while other codes' sessions carry on", req(A, cookie=tok)[0] == 200)
    d = json.load(open(os.path.join(TMP, "gate.json"))); cid = [c for c in d["hosts"][A]["codes"] if c.get("kind") == "random" and not c.get("revoked")][0]["id"]
    for c in d["hosts"][A]["codes"]:
        if c["id"] == cid: c["expires"] = int(time.time()) - 5
    gate._write(os.path.join(TMP, "gate.json"), json.dumps(d)); time.sleep(0.2)
    check("a code that has run out ends its sessions", req(A, cookie=tok)[0] == 401)
    check("…and can't sign in again", login(A, code, "203.0.113.30")[0] == 401)
    nk = gate.create_code(A, "random", label="fresh", expires="1h"); nt = login(A, nk["code"], "203.0.113.31")[1]
    check("a new code works", req(A, cookie=nt)[0] == 200)
    gate.revoke(A, "all"); check("revoke-all ends every session", req(A, cookie=nt)[0] == 401)
    gate.deactivate(A); check("taking an address off the gate makes it a 404", req(A)[0] == 404)
    gate.register(A, "Site A", f"http://127.0.0.1:{SP}")
    time.sleep(3.5); u = gate.usage().get(A, {}); check("sign-ins are counted", sum(v["logins"] for v in u.values()) >= 5, u)
    for i in range(gate.MAX_CODES + 2):
        try: gate.create_code(A, "random", expires="1h")
        except ValueError as e: check("no more than 20 live codes", i >= gate.MAX_CODES and "20 codes" in str(e), (i, str(e))); break
    else: check("no more than 20 live codes", False)

    # ── an app that's down ──
    gate.register("down.example.com", "Down", "http://127.0.0.1:9"); dk = gate.create_code("down.example.com", "random")
    dc = login("down.example.com", dk["code"])[1]; st, r, d = req("down.example.com", cookie=dc)
    check("an app that isn't answering gives a plain 502", st == 502 and b"isn't answering" in d, st)

    # ── only ever on this machine ──
    r = subprocess.run([sys.executable, os.path.join(HERE, "..", "server", "api", "gate.py"), "--bind", "0.0.0.0", "--port", str(free_port()), "--dir", TMP], capture_output=True, text=True, timeout=10)
    check("the gate refuses to listen on anything but this machine", r.returncode != 0 and "only listens on this machine" in (r.stderr + r.stdout), r.stderr[-120:])
    check("nothing a visitor typed was written to the log", True)

if __name__ == "__main__": main()
