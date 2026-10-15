"""Authenticator-app codes (TOTP, RFC 6238: SHA-1, 30-second steps, 6 digits) — the way to approve a browser from a
computer that has neither the admin's phone nor an SSH session. Off until an admin turns it on (with their fingerprint);
the secret stays in Nova's private data folder and is shown once, when it's set up."""
import base64, hashlib, hmac, secrets, struct, time, urllib.parse

STEP = 30


def new_secret():
    return base64.b32encode(secrets.token_bytes(20)).decode().rstrip("=")


def _code(secret, counter):
    key = base64.b32decode(secret + "=" * (-len(secret) % 8))
    h = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    o = h[-1] & 0x0F
    return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1_000_000)


def verify(secret, code, last_counter=0, now=None):
    """The time step this code belongs to (one step either side, for clock drift), or None. A step at or before
    last_counter is refused, so a code that was used once can't be used again."""
    code = "".join(c for c in str(code) if c.isdigit())
    if len(code) != 6 or not secret: return None
    t = int((now or time.time()) // STEP)
    for c in (t, t - 1, t + 1):
        if c > last_counter and hmac.compare_digest(_code(secret, c), code): return c
    return None


def uri(secret, label, issuer="Nova"):
    """The otpauth:// link an authenticator app reads from the QR code."""
    return (f"otpauth://totp/{urllib.parse.quote(issuer)}:{urllib.parse.quote(label)}"
            f"?secret={secret}&issuer={urllib.parse.quote(issuer)}&algorithm=SHA1&digits=6&period={STEP}")
