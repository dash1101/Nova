"""
winrun — starts one of Nova's parts on Windows and keeps what it prints.

    python winrun.py api|helper|alerts

Task Scheduler throws a program's output away, so each part's output goes to %ProgramData%\\Nova\\log\\<part>.log
(kept under 2 MB: the previous one is <part>.log.1). On Linux systemd does all of this, so this file is Windows-only.
"""
import os, runpy, sys

HERE = os.path.dirname(os.path.realpath(__file__))
sys.path.insert(0, HERE)
import plat  # noqa: E402

PARTS = {"api": ("server.py", []), "helper": ("helper-win.py", []),
         "alerts": (next((p for p in (os.path.join("monitor", "nova_alerts.py"), os.path.join("..", "monitor", "nova_alerts.py")) if os.path.exists(os.path.join(HERE, p))), ""), ["loop"])}

def main():
    part = sys.argv[1] if len(sys.argv) > 1 else ""
    if part not in PARTS: sys.exit("usage: winrun.py api|helper|alerts")
    script, args = PARTS[part]
    logdir = plat.p("/var/log/nova-api"); os.makedirs(logdir, exist_ok=True)
    log = os.path.join(logdir, f"{part}.log")
    try:
        if os.path.getsize(log) > 2 * 1024 * 1024: os.replace(log, log + ".1")
    except OSError: pass
    f = open(log, "a", buffering=1, encoding="utf-8", errors="replace")
    sys.stdout = sys.stderr = f
    import time
    print(f"--- {time.strftime('%Y-%m-%d %H:%M:%S')} starting {part}", flush=True)
    path = os.path.join(HERE, script)
    sys.argv = [path] + args
    try: runpy.run_path(path, run_name="__main__")
    except SystemExit as e:
        print(f"--- {part} stopped: {e.code}", flush=True); raise
    except BaseException:
        import traceback
        traceback.print_exc(); raise

if __name__ == "__main__":
    main()
