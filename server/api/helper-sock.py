#!/usr/bin/python3 -I
"""
nova-helper@.service — socket-activated front door to the root helper.

nova-api runs in a read-only, private-mount sandbox, and anything it starts with sudo inherits
that sandbox (so /etc, /opt and apt were read-only and unmounts never reached the host). The
helper therefore runs here instead: systemd starts one instance per connection on
/run/nova-helper.sock, in the normal system namespace. The socket is root:nova-api 0660, so only
the API can connect, which is the same trust boundary as the old sudo rule. Verbs are unchanged.

Protocol: one line of JSON (the argv list), then
  * streaming verbs (shell): the socket becomes the helper's stdin/stdout;
  * everything else: the rest of the input is the helper's stdin, and the reply is one JSON
    object {"rc": int, "stdout": str, "stderr": str}.
"""
import json, os, subprocess, sys

HELPER = os.path.join(os.path.dirname(os.path.realpath(__file__)), "helper")      # installed next to this file
STREAMING = {"shell", "host-shell", "files-get"}
WITH_INPUT = {"files-data"}          # the rest of the connection is the helper's stdin (≤ 6 MB)

def read_line(limit=16384):
    buf = b""
    while not buf.endswith(b"\n"):
        c = os.read(0, 1)                 # byte-wise: nothing after the header may be swallowed
        if not c or len(buf) > limit: sys.exit(2)
        buf += c
    return buf

try:
    args = json.loads(read_line())
    assert isinstance(args, list) and 0 < len(args) <= 8 and all(isinstance(a, str) and len(a) <= 8192 for a in args)
except Exception:
    os.write(1, b'{"rc": 2, "stdout": "", "stderr": "bad request"}'); sys.exit(2)

if args[0] in STREAMING:
    os.execv(HELPER, [HELPER] + args)
data = b""
if args[0] in WITH_INPUT:
    while len(data) <= 6 * 1024 * 1024:
        c = os.read(0, 1 << 16)
        if not c: break
        data += c
r = subprocess.run([HELPER] + args, input=data, capture_output=True) if data else subprocess.run([HELPER] + args, stdin=subprocess.DEVNULL, capture_output=True)
os.write(1, json.dumps({"rc": r.returncode, "stdout": r.stdout.decode(errors="replace"),
                        "stderr": r.stderr.decode(errors="replace")[-4000:]}).encode())
