#!/usr/bin/python3 -I
"""
Nova helper service for Windows — the front door to the privileged helper (helper.py), like
nova-helper.socket + helper-sock.py on Linux.

The API runs as a low-privilege account and can't manage the machine; this runs as SYSTEM and does
the fixed list of things helper.py knows. It listens on this computer only (127.0.0.1, a port
Windows picks) and every request must start with a key that only Nova's two accounts can read
(%ProgramData%\\Nova\\run\\helper.json — the installer sets that folder's permissions).

Protocol: the key and a newline, then exactly what the Linux socket takes: one line of JSON (the
argv list), then
  * files-get: the helper's output is sent straight back;
  * everything else: the rest of the input is the helper's stdin, and the reply is one JSON object
    {"rc": int, "stdout": str, "stderr": str}.
"""
import hmac, json, os, secrets, socket, socketserver, subprocess, sys, threading

HERE = os.path.dirname(os.path.realpath(__file__))
sys.path.insert(0, HERE)
import plat  # noqa: E402

HELPER = os.path.join(HERE, "helper.py")
INFO = plat.p("/run/nova/helper.json")
STREAMING = {"files-get"}
WITH_INPUT = {"files-data", "cf-code-new"}
KEY = secrets.token_urlsafe(32)
NO_WINDOW = 0x08000000 if plat.WINDOWS else 0
BUSY = threading.BoundedSemaphore(16)             # at most this many helper runs at once


class Conn(socketserver.StreamRequestHandler):
    timeout = 30

    def line(self, limit=16384):
        l = self.rfile.readline(limit + 1)
        if not l.endswith(b"\n") or len(l) > limit: raise ValueError("bad request")
        return l[:-1]

    def handle(self):
        try:
            if not hmac.compare_digest(self.line(256), KEY.encode()): return
            args = json.loads(self.line())
            assert isinstance(args, list) and 0 < len(args) <= 8 and all(isinstance(a, str) and len(a) <= 8192 for a in args)
        except Exception:
            try: self.wfile.write(b'{"rc": 2, "stdout": "", "stderr": "bad request"}')
            except OSError: pass
            return
        cmd = [sys.executable, "-I", HELPER] + args
        with BUSY:
            if args[0] in STREAMING:
                p = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, creationflags=NO_WINDOW)
                try:
                    while True:
                        b = p.stdout.read(65536)
                        if not b: break
                        self.wfile.write(b)
                except OSError: p.kill()
                p.wait(); return
            data = b""
            if args[0] in WITH_INPUT:
                self.connection.settimeout(120)
                data = self.rfile.read(6 * 1024 * 1024 + 1)
            self.connection.settimeout(None)
            r = subprocess.run(cmd, input=data, capture_output=True, creationflags=NO_WINDOW) if data else \
                subprocess.run(cmd, stdin=subprocess.DEVNULL, capture_output=True, creationflags=NO_WINDOW)
            self.wfile.write(json.dumps({"rc": r.returncode, "stdout": r.stdout.decode("utf-8", "replace"),
                                         "stderr": r.stderr.decode("utf-8", "replace")[-4000:]}).encode())


class Server(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = False


def main():
    srv = Server(("127.0.0.1", 0), Conn)
    os.makedirs(os.path.dirname(INFO), exist_ok=True)
    tmp = INFO + ".tmp"
    with open(tmp, "w") as f: json.dump({"port": srv.server_address[1], "key": KEY, "pid": os.getpid()}, f)
    os.replace(tmp, INFO)
    print(f"nova-helper listening on 127.0.0.1:{srv.server_address[1]}", flush=True)
    try: srv.serve_forever()
    finally:
        try: os.remove(INFO)
        except OSError: pass


if __name__ == "__main__":
    main()
