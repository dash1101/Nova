#!/usr/bin/env python3
"""Tests for the "Anyone with a code" mode in cfsync.py, against a fake in-memory Cloudflare.
It runs public → only you → code → guests → code → public → taken off, and again with a failure injected at every
single Cloudflare change along the way, checking after each step that the address was never reachable without either a
Cloudflare login or the code gate (once it had one), and that Nova's record always matches what's on the tunnel.
   python3 packaging/cfsync_code_test.py        (no network; nothing real is touched)"""
import copy, json, os, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
TMP = tempfile.mkdtemp(prefix="cf-code-test-")
os.environ["NOVA_GATE_DIR"] = os.path.join(TMP, "gate")
sys.path.insert(0, os.path.join(HERE, "..", "server", "api"))
import cfsync, gate                                                        # noqa: E402

passed = failed = 0
def check(name, ok, info=""):
    global passed, failed
    if ok: passed += 1
    else: failed += 1; print(f"FAIL {name}  {info}")

HOST = "yt.example.com"; UP = "http://localhost:5000"


class Boom(ValueError): pass

class Fake:
    """Just enough of Cloudflare: tunnel rules, DNS, Access apps and policies."""
    def __init__(self):
        self.ingress = [{"hostname": "nova.example.com", "service": "http://localhost:8095"}, {"hostname": HOST, "service": UP}, {"service": "http_status:404"}]
        self.dns = {HOST: [{"id": "d1", "type": "CNAME", "content": "tun.cfargotunnel.com", "name": HOST}]}
        self.apps = {"A0": {"id": "A0", "name": "Nova", "type": "self_hosted", "domain": "nova.example.com", "destinations": [{"type": "public", "uri": "nova.example.com"}],
                            "policies": [{"id": "P0", "decision": "allow", "precedence": 1, "reusable": True}], "session_duration": "24h"}}
        self.pols = {"P0": {"id": "P0", "decision": "allow"}}
        self.n = 0; self.muts = 0; self.fail_at = None; self.private = False; self.violations = []; self.log = []
    def protected(self, h): return any(h in [d["uri"] for d in a["destinations"]] for a in self.apps.values())
    def route(self, h): return next((r for r in self.ingress if r.get("hostname") == h), None)
    def exposed(self, h):
        r = self.route(h)
        return bool(r) and r.get("service") != cfsync.GATE_SERVICE and not self.protected(h)
    def call(self, method, path, body=None):
        mut = method in ("POST", "PUT", "DELETE")
        if mut:
            self.muts += 1
            if self.fail_at == self.muts: raise Boom(f"injected failure at change #{self.muts}: {method} {path}")
        out = self.handle(method, path, body)
        if mut:
            self.log.append(f"{method} {path.split('/', 3)[-1][:70]}")
            if self.private and self.exposed(HOST): self.violations.append(f"EXPOSED after change #{self.muts}: {method} {path}")
        return out
    def handle(self, m, path, body):
        p = path.split("?")[0]; q = path.split("?", 1)[1] if "?" in path else ""
        if p == "/zones": return [{"id": "Z1", "name": "example.com"}] if q.endswith("example.com") else []
        if p == "/zones/Z1/dns_records":
            return copy.deepcopy(self.dns.get(q.replace("name=", ""), [])) if m == "GET" else None
        if p.startswith("/zones/Z1/dns_records/"):
            did = p.rsplit("/", 1)[1]
            for h, recs in self.dns.items():
                for r in recs:
                    if r["id"] == did:
                        if m == "DELETE": recs.remove(r)
                        return copy.deepcopy(r)
            raise ValueError("not found")
        if p.endswith("/connections"): return []
        if p.endswith("/configurations"):
            if m == "GET": return {"config": {"ingress": copy.deepcopy(self.ingress)}}
            self.ingress = copy.deepcopy(body["config"]["ingress"]); return {}
        if p == "/accounts/acct/access/apps":
            if m == "GET": return copy.deepcopy(list(self.apps.values()))
            self.n += 1; a = {**copy.deepcopy(body), "id": f"A{self.n}"}; a["destinations"] = body.get("destinations") or [{"type": "public", "uri": body["domain"]}]
            a["policies"] = [{"id": x["id"], "precedence": x.get("precedence", 1), "decision": "allow", "reusable": True} for x in body.get("policies", [])]; self.apps[a["id"]] = a; return copy.deepcopy(a)
        if p.startswith("/accounts/acct/access/apps/"):
            aid = p.rsplit("/", 1)[1]
            if m == "DELETE":
                if aid not in self.apps: raise ValueError("not found")
                del self.apps[aid]; return {}
            a = {**self.apps[aid], **copy.deepcopy(body)}; a["id"] = aid
            a["policies"] = [{"id": x["id"], "precedence": x.get("precedence", 1), "decision": "allow", "reusable": True} for x in body.get("policies", [])]; self.apps[aid] = a; return copy.deepcopy(a)
        if p == "/accounts/acct/access/policies":
            self.n += 1; pid = f"P{self.n}"; self.pols[pid] = {"id": pid, **body}; return {"id": pid}
        if p.startswith("/accounts/acct/access/policies/"):
            pid = p.rsplit("/", 1)[1]
            if m == "DELETE": self.pols.pop(pid, None)
            return {"id": pid}
        raise AssertionError(f"fake Cloudflare has no {m} {path}")

def install(fake):
    cfsync.call = fake.call; cfsync.tunnel = lambda: ("acct", "tun")
    cfsync._site = lambda: {"remote_url": "https://nova.example.com"}
    cfsync.LEDGER = os.path.join(TMP, "ledger.json"); cfsync._gate_up = lambda: None
    for f in (cfsync.LEDGER,):
        try: os.remove(f)
        except OSError: pass
    gate.DIR = os.environ["NOVA_GATE_DIR"]
    import shutil; shutil.rmtree(gate.DIR, ignore_errors=True)
    cfsync.time.sleep = lambda s: None

STEPS = [("me", {}), ("code", {}), ("guests", {"guests": ["a@b.co"]}), ("code", {}), ("me", {}), ("code", {}), ("public", {"allow_public": True}), ("code", {}), ("remove", {})]

def consistent(fake, label):
    """The record and the tunnel agree, and the address is never reachable around the gate or the login."""
    led = cfsync.ledger().get(HOST) or {}; r = fake.route(HOST); svc = (r or {}).get("service")
    if led.get("gated"): check(f"{label}: gated record ⇒ route goes to the gate", svc == cfsync.GATE_SERVICE, svc)
    elif led: check(f"{label}: ungated record ⇒ route is the app's own", svc == UP, (svc, led.get("mode")))
    if led.get("mode") == "code" and led.get("gated"): check(f"{label}: code mode ⇒ no Cloudflare login on it", not fake.protected(HOST))
    if led.get("gated"):
        h = gate.load()["hosts"].get(HOST) or {}; check(f"{label}: gated ⇒ the gate knows the address and the app's place", h.get("active") and h.get("upstream") == UP, h)

def run(fail_at=None, quiet=False):
    fake = Fake(); fake.fail_at = fail_at; install(fake); errs = []
    for mode, kw in STEPS:
        if mode in ("public", "remove"): fake.private = False          # (these two are meant to leave it open, as it was before Nova)
        try:
            if mode == "remove": cfsync.remove(HOST, allow_public=True)
            else: cfsync.set_access(HOST, mode, kw.get("guests"), "24h", kw.get("allow_public", False), "YT")
        except (ValueError, KeyError) as e: errs.append(f"{mode}: {str(e)[:90]}")
        led = cfsync.ledger().get(HOST) or {}
        if led.get("mode") in ("me", "guests", "code") or led.get("gated"): fake.private = True
        if not quiet: consistent(fake, f"step {mode}" + (f" (failure #{fail_at})" if fail_at else ""))
    return fake, errs

def main():
    fake, errs = run(None, quiet=False)
    check("the whole run works with nothing failing", not errs, errs)
    total = fake.muts
    check("no moment was reachable without a login or the gate", not fake.violations, fake.violations[:3])
    led = cfsync.ledger()
    check("taking it off restores the app's own route and forgets the gate", HOST not in led and fake.route(HOST).get("service") == UP and HOST not in gate.load()["hosts"], (led, fake.route(HOST)))
    check("…and leaves your DNS record and route as they were", fake.dns[HOST][0]["id"] == "d1")
    print(f"  ({total} Cloudflare changes in a full run; now failing each one in turn)")
    for k in range(1, total + 1):
        f2, errs = run(k)
        check(f"failure at change #{k}: never reachable around the gate/login", not f2.violations, f2.violations[:2])
    # after a failure, trying the same thing again finishes the job
    fake = Fake(); install(fake); cfsync.set_access(HOST, "me", None, "24h", False, "YT")
    fake.private = True; fake.fail_at = fake.muts + 2
    try: cfsync.set_access(HOST, "code", None, "24h", False, "YT"); check("an injected failure is raised", False)
    except ValueError: check("an injected failure is raised", True)
    fake.fail_at = None; consistent(fake, "after the failure")
    cfsync.set_access(HOST, "code", None, "24h", False, "YT"); consistent(fake, "after the retry")
    check("a retry after a failure ends up in code mode", cfsync.ledger()[HOST]["mode"] == "code" and cfsync.ledger()[HOST].get("gated"))
    check("…with no login on it and the route on the gate", not fake.protected(HOST) and fake.route(HOST)["service"] == cfsync.GATE_SERVICE)
    # codes are tied to the mode
    k = cfsync.new_code(HOST, "random", label="t", expires="1h"); check("codes can be made while it's in code mode", bool(k["code"]) and k["url"] == "https://" + HOST)
    check("…and listed without secrets", k["code"] not in json.dumps(cfsync.codes(HOST)))
    cfsync.set_access(HOST, "me", None, "24h", False, "YT")
    try: cfsync.new_code(HOST, "random"); check("no codes once it's not in code mode", False)
    except ValueError: check("no codes once it's not in code mode", True)
    try: cfsync.codes("nope.example.com"); check("an unknown address has no codes", False)
    except ValueError: check("an unknown address has no codes", True)
    # an address that goes somewhere that isn't a web address can't be put behind a code
    fake = Fake(); fake.ingress.insert(1, {"hostname": "ssh.example.com", "service": "ssh://localhost:22"}); fake.dns["ssh.example.com"] = [{"id": "d2", "type": "CNAME", "content": "tun.cfargotunnel.com", "name": "ssh.example.com"}]
    install(fake)
    try: cfsync.set_access("ssh.example.com", "code", None, "24h", False, "SSH"); check("a non-web route can't be put behind a code", False)
    except ValueError as e: check("a non-web route can't be put behind a code", "normal web address" in str(e), str(e))
    check("…and nothing changed", fake.route("ssh.example.com")["service"] == "ssh://localhost:22" and "ssh.example.com" not in cfsync.ledger())
    # nobody can publish a brand-new address straight into code mode (it has to exist first)
    try: cfsync.apply("new.example.com", "http://localhost:9999", mode="code"); check("apply refuses code mode", False)
    except ValueError: check("apply refuses code mode", True)
    # a route already pointing at the gate (out of step) is refused rather than nested
    fake = Fake(); fake.ingress[1]["service"] = cfsync.GATE_SERVICE; install(fake)
    try: cfsync.set_access(HOST, "code", None, "24h", False, "YT"); check("a route that already points at the gate is not nested", False)
    except ValueError as e: check("a route that already points at the gate is not nested", "already goes to the code gate" in str(e), str(e))
    print(f"{passed}/{passed + failed} passed"); sys.exit(1 if failed else 0)

if __name__ == "__main__": main()
