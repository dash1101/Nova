"""
Nova helper service for macOS — the front door to the privileged helper (helper.py), like nova-helper.socket on Linux.

The API runs as an account without administrator rights and can't manage the Mac; this runs as root (a launchd daemon)
and does the fixed list of things helper.py knows. It listens on a socket file that only root and Nova's own account
can open, so nothing else on the Mac can talk to it — the same boundary as on Linux, and the same protocol: each
connection is handed to helper-sock.py exactly the way systemd hands it one (the connection is its input and output).

It also carries out scheduled restarts and shutdowns (Power → Schedule in the app): macOS has no one-shot timers like
systemd's, so this service — which is always running — looks at the list twice a minute.
"""
import grp, os, socket, subprocess, sys, threading, time

HERE = os.path.dirname(os.path.realpath(__file__))
sys.path.insert(0, HERE)
import plat  # noqa: E402

SOCK = plat.p("/run/nova/helper.sock")
FRONT = os.path.join(HERE, "helper-sock.py")
BUSY = threading.BoundedSemaphore(32)             # at most this many helper runs at once (as MaxConnections on Linux)


def serve(conn):
    with BUSY:
        try:
            p = subprocess.Popen([sys.executable, "-I", FRONT], stdin=conn, stdout=conn, stderr=subprocess.DEVNULL, close_fds=True, cwd="/")
        except OSError: return
        finally: conn.close()                     # the child has its own copy now
        try: p.wait(4 * 3600)                     # a terminal session may stay open for hours, not forever
        except subprocess.TimeoutExpired: p.kill(); p.wait()


def scheduled_power():
    """Carry out what Power → Schedule set (machost.py keeps the list). One that was missed by more than ten minutes —
    the Mac was off — is dropped, not run late."""
    import machost
    while True:
        time.sleep(20)
        try:
            due = [x for x in machost.scheds() if x["at"] <= time.time()]
            if not due: continue
            machost.save_scheds([x for x in machost.scheds() if x["at"] > time.time()])
            x = due[0]
            if time.time() - x["at"] > 600: continue
            machost.changelog(f"Scheduled {'restart' if x['action'] == 'reboot' else 'shutdown'} is happening now")
            machost.notify("warning", f"Server {'restarting' if x['action'] == 'reboot' else 'shutting down'} (scheduled in the Nova app)", "")
            if machost.MONITOR: machost.run([sys.executable, machost.MONITOR], 40)
            machost.power_now(x["action"])
        except Exception as e:
            print(f"scheduled power: {e}", flush=True)


def main():
    os.makedirs(os.path.dirname(SOCK), exist_ok=True)
    try: os.remove(SOCK)
    except OSError: pass
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    old = os.umask(0o177)                         # made private, then opened to Nova's account only
    try: s.bind(SOCK)
    finally: os.umask(old)
    os.chown(SOCK, 0, grp.getgrnam("nova-api").gr_gid); os.chmod(SOCK, 0o660)
    s.listen(64)
    threading.Thread(target=scheduled_power, daemon=True).start()
    print(f"nova-helper listening on {SOCK}", flush=True)
    try:
        while True:
            conn, _ = s.accept()
            threading.Thread(target=serve, args=(conn,), daemon=True).start()
    finally:
        try: os.remove(SOCK)
        except OSError: pass


if __name__ == "__main__":
    main()
