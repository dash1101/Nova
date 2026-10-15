"""Passkeys (WebAuthn) for Nova web: a passkey saved in your phone's or computer's password manager can add a browser to
the server, or approve something a browser asks for — instead of an admin phone with the Nova app.

Passkeys belong to a web address, so they work when Nova is opened at its name (your Cloudflare address, say), not at
an IP address — browsers only allow them on a real domain with a proper certificate.

Checked here: the challenge (one use, five minutes), the origin and RP ID, user presence and user verification
(fingerprint / face / PIN on the device), the signature (ES256, or RS256 for Windows Hello) and the signature counter."""
import base64, hashlib, json, os, secrets, struct, time

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa

STORE = "/var/lib/nova-api/passkeys.json"
CHALLENGES = {}                     # challenge -> {kind, rp, t, ...}


def b64u(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
def unb64u(s): return base64.urlsafe_b64decode(str(s) + "=" * (-len(str(s)) % 4))


# ── a small CBOR reader (enough for attestation objects and COSE keys) ──
def _cbor(b, i=0):
    ib = b[i]; mt, ai = ib >> 5, ib & 31; i += 1
    if ai < 24: val = ai
    elif ai == 24: val = b[i]; i += 1
    elif ai == 25: val = struct.unpack(">H", b[i:i + 2])[0]; i += 2
    elif ai == 26: val = struct.unpack(">I", b[i:i + 4])[0]; i += 4
    elif ai == 27: val = struct.unpack(">Q", b[i:i + 8])[0]; i += 8
    else: raise ValueError("unsupported CBOR")
    if mt == 0: return val, i
    if mt == 1: return -1 - val, i
    if mt == 2: return bytes(b[i:i + val]), i + val
    if mt == 3: return b[i:i + val].decode(), i + val
    if mt == 4:
        out = []
        for _ in range(val): x, i = _cbor(b, i); out.append(x)
        return out, i
    if mt == 5:
        out = {}
        for _ in range(val): k, i = _cbor(b, i); v, i = _cbor(b, i); out[k] = v
        return out, i
    if mt == 7: return {20: False, 21: True, 22: None}.get(ai), i
    raise ValueError("unsupported CBOR")


def _load():
    try: return json.load(open(STORE))
    except Exception: return {}

def _save(d):
    tmp = STORE + ".tmp"; json.dump(d, open(tmp, "w")); os.chmod(tmp, 0o600); os.replace(tmp, STORE)

def listing():
    return [{"id": k, "name": v.get("name", ""), "rp": v.get("rp"), "created": v.get("created"), "by": v.get("by"), "last_used": v.get("last_used")}
            for k, v in sorted(_load().items(), key=lambda kv: kv[1].get("created", ""))]

def any_for(rp): return any(v.get("rp") == rp for v in _load().values())

def remove(cid):
    d = _load()
    if cid not in d: raise ValueError("no such passkey")
    d.pop(cid); _save(d)


def challenge(kind, rp, **extra):
    for k in [k for k, v in CHALLENGES.items() if time.time() - v["t"] > 300]: CHALLENGES.pop(k, None)
    if len(CHALLENGES) > 200: raise PermissionError("too many passkey requests — try again in a few minutes")
    c = b64u(secrets.token_bytes(32)); CHALLENGES[c] = {"kind": kind, "rp": rp, "t": time.time(), **extra}
    return c

def _client_data(cdj_b64, kind, rp):
    raw = unb64u(cdj_b64); cd = json.loads(raw)
    ch = CHALLENGES.pop(cd.get("challenge", ""), None)             # used once, whatever happens next
    if not ch or ch["kind"] != kind or time.time() - ch["t"] > 300: raise ValueError("that passkey request ran out — try again")
    if ch["rp"] != rp: raise ValueError("wrong address for this passkey")
    if cd.get("type") != ("webauthn.create" if kind == "register" else "webauthn.get"): raise ValueError("not a passkey answer")
    if cd.get("origin") not in (f"https://{rp}", f"https://{rp}:443"): raise ValueError("that passkey answer came from another site")
    return raw, ch

def _auth_data(ad, rp):
    if ad[:32] != hashlib.sha256(rp.encode()).digest(): raise ValueError("that passkey belongs to another site")
    flags = ad[32]
    if not flags & 0x01: raise ValueError("the passkey wasn't confirmed on the device")
    if not flags & 0x04: raise ValueError("the passkey needs your fingerprint, face or PIN on that device")
    return flags, struct.unpack(">I", ad[33:37])[0]


def register_start(rp, user_name, dev=""):
    c = challenge("register", rp, dev=dev)
    return {"challenge": c, "rp": {"id": rp, "name": "Nova"}, "user": {"id": b64u(hashlib.sha256(user_name.encode()).digest()[:16]), "name": user_name, "displayName": user_name},
            "pubKeyCredParams": [{"type": "public-key", "alg": -7}, {"type": "public-key", "alg": -257}], "timeout": 120000, "attestation": "none",
            "authenticatorSelection": {"residentKey": "required", "requireResidentKey": True, "userVerification": "required"},
            "excludeCredentials": [{"type": "public-key", "id": k} for k, v in _load().items() if v.get("rp") == rp]}

def register_finish(rp, body, by, dev=""):
    _, ch = _client_data(body.get("clientDataJSON"), "register", rp)
    if ch.get("dev") != dev: raise ValueError("finish adding the passkey on the device that started it")
    att, _ = _cbor(unb64u(body.get("attestationObject")))
    ad = att["authData"]; flags, counter = _auth_data(ad, rp)
    if not flags & 0x40: raise ValueError("no key in the passkey answer")
    n = struct.unpack(">H", ad[53:55])[0]; cid = ad[55:55 + n]; cose, _ = _cbor(ad, 55 + n)
    if cose.get(3) == -7 and cose.get(1) == 2 and cose.get(-1) == 1: key = {"alg": -7, "x": b64u(cose[-2]), "y": b64u(cose[-3])}
    elif cose.get(3) == -257 and cose.get(1) == 3: key = {"alg": -257, "n": b64u(cose[-1]), "e": b64u(cose[-2])}
    else: raise ValueError("that passkey uses a kind of key Nova doesn't take")
    d = _load()
    d[b64u(cid)] = {**key, "rp": rp, "count": counter, "name": str(body.get("name") or "Passkey")[:40], "created": time.strftime("%Y-%m-%d %H:%M"), "by": by}
    _save(d)
    return {"ok": True, "id": b64u(cid)}

def assert_start(rp, kind, **extra):
    if not any_for(rp): raise PermissionError("no passkeys are set up for this address")
    return {"challenge": challenge(kind, rp, **extra), "rpId": rp, "timeout": 120000, "userVerification": "required", "allowCredentials": []}

def assert_finish(rp, kind, body):
    """Checks a passkey answer; returns the challenge's extra data (what it was for) and the passkey's name."""
    raw, ch = _client_data(body.get("clientDataJSON"), kind, rp)
    d = _load(); cid = str(body.get("id", "")); k = d.get(cid)
    if not k or k.get("rp") != rp: raise ValueError("that passkey isn't set up on this server")
    ad = unb64u(body.get("authenticatorData")); flags, counter = _auth_data(ad, rp)
    sig = unb64u(body.get("signature")); msg = ad + hashlib.sha256(raw).digest()
    try:
        if k["alg"] == -7:
            pub = ec.EllipticCurvePublicNumbers(int.from_bytes(unb64u(k["x"]), "big"), int.from_bytes(unb64u(k["y"]), "big"), ec.SECP256R1()).public_key()
            pub.verify(sig, msg, ec.ECDSA(hashes.SHA256()))
        else:
            pub = rsa.RSAPublicNumbers(int.from_bytes(unb64u(k["e"]), "big"), int.from_bytes(unb64u(k["n"]), "big")).public_key()
            pub.verify(sig, msg, padding.PKCS1v15(), hashes.SHA256())
    except InvalidSignature: raise ValueError("that passkey's signature isn't right")
    if counter and k.get("count") and counter <= k["count"]: raise ValueError("that passkey answer was used before (or the passkey was copied)")
    k["count"] = counter; k["last_used"] = time.strftime("%Y-%m-%d %H:%M"); _save(d)
    return {k2: v for k2, v in ch.items() if k2 not in ("kind", "rp", "t")}, k.get("name", "Passkey")
