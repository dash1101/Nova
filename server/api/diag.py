"""
Nova diagnostics — run by tasks.py (long tests, with live samples) or the helper (quick checks).

  net_internet  download / upload / ping / jitter / loss to Cloudflare's speed-test servers
  disk_speed    sequential + random read/write on a chosen drive or pool (fio, temp file, deleted after)
  cpu_stress    every core flat out (stress-ng) while sampling temperature, clock and package power
  mem_test      fill a chosen share of free memory with patterns and verify them (stress-ng --verify)
  ping / trace / dns / port / top   quick checks
"""
import glob, json, os, re, shutil, socket, statistics, subprocess, threading, time, urllib.request

CF = "https://speed.cloudflare.com"
# If Cloudflare rate-limits us (HTTP 429 after several tests), download from public test files instead.
MIRRORS = ["https://ash-speed.hetzner.com/100MB.bin", "https://proof.ovh.net/files/100Mb.dat"]
UA = {"User-Agent": "nova-diagnostics/1"}


def run(cmd, timeout=120, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, **kw)
    return r.returncode, r.stdout, r.stderr


# ── live sensors (for the CPU / memory tests) ─────────────────────────────────────────
def _hwmon(name):
    for h in glob.glob("/sys/class/hwmon/hwmon*"):
        try:
            if open(f"{h}/name").read().strip() == name: return h
        except OSError: pass
    return None

def cpu_temp():
    for n in ("k10temp", "coretemp", "zenpower", "cpu_thermal"):
        h = _hwmon(n)
        if h:
            vals = []
            for f in sorted(glob.glob(f"{h}/temp*_input")):
                lab = open(f.replace("_input", "_label")).read().strip() if os.path.exists(f.replace("_input", "_label")) else ""
                if n != "k10temp" or lab in ("Tctl", "Tdie", ""): vals.append(int(open(f).read()) / 1000)
            if vals: return round(max(vals), 1)
    return None

def cpu_mhz():
    f = glob.glob("/sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq")
    vals = [int(open(x).read()) / 1000 for x in f if os.path.exists(x)]
    if not vals:
        vals = [float(m) for m in re.findall(r"cpu MHz\s*:\s*([\d.]+)", open("/proc/cpuinfo").read())]
    return {"avg": round(sum(vals) / len(vals)), "max": round(max(vals))} if vals else None

class Power:
    """CPU package power from RAPL (Intel, and AMD Zen on recent kernels)."""
    def __init__(self):
        doms = [p for p in glob.glob("/sys/class/powercap/*rapl*") if os.path.exists(p + "/energy_uj")]
        def nm(p):
            try: return open(p + "/name").read().strip()
            except OSError: return ""
        pkg = [p for p in doms if nm(p).startswith("package")]            # the whole CPU, not just the cores
        self.f = (sorted(pkg) or [None])[0] and sorted(pkg)[0] + "/energy_uj"
        try: self.max = int(open(os.path.dirname(self.f) + "/max_energy_range_uj").read()) if self.f else 0
        except OSError: self.max = 0
        self.last = self._read()
    def _read(self):
        try: return (int(open(self.f).read()), time.time()) if self.f else None
        except OSError: return None
    def watts(self):
        now = self._read()
        if not now or not self.last: return None
        de = now[0] - self.last[0]
        if de < 0: de += self.max
        dt = now[1] - self.last[1]; self.last = now
        return round(de / 1e6 / dt, 1) if dt > 0 else None

def meminfo():
    m = {k: int(v) * 1024 for k, v in re.findall(r"(\w+):\s+(\d+) kB", open("/proc/meminfo").read())}
    return {"total": m.get("MemTotal", 0), "available": m.get("MemAvailable", 0), "used": m.get("MemTotal", 0) - m.get("MemAvailable", 0)}

def load1():
    return round(os.getloadavg()[0], 2)


# ── internet speed ────────────────────────────────────────────────────────────────────
def _get(url, n=None, timeout=15):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        hdr = dict(r.headers)
        read = 0
        while True:
            b = r.read(65536)
            if not b: break
            read += len(b)
        return read, hdr

def net_internet(spec, log, progress):
    res = {"server": None}
    log("Measuring ping…")
    try:
        _, hdr = _get(f"{CF}/__down?bytes=0", timeout=8)
        res["server"] = " ".join(x for x in (hdr.get("cf-meta-city"), hdr.get("colo") or hdr.get("cf-meta-colo")) if x) or "Cloudflare"
    except Exception as e: raise RuntimeError(f"couldn't reach the internet (speed.cloudflare.com): {e}")
    progress(4)
    rc, so, _ = run(["ping", "-c", "30", "-i", "0.2", "-W", "1", "1.1.1.1"], timeout=40)
    lat = [float(x) for x in re.findall(r"time=([\d.]+) ms", so)]
    m = re.search(r"([\d.]+)% packet loss", so)
    res["loss_pct"] = float(m.group(1)) if m else None
    if not lat:                                  # ICMP blocked: time requests on one open connection instead
        import http.client
        c = http.client.HTTPSConnection("speed.cloudflare.com", timeout=5)
        for _ in range(20):
            t = time.perf_counter()
            try: c.request("GET", "/__down?bytes=0", headers=UA); c.getresponse().read(); lat.append((time.perf_counter() - t) * 1000)
            except Exception: break
        res["ping_method"] = "https"
    else: res["ping_method"] = "icmp"
    if not lat: raise RuntimeError("no replies to ping")
    res["ping_ms"] = round(statistics.median(lat), 1); res["ping_min_ms"] = round(min(lat), 1)
    res["jitter_ms"] = round(statistics.mean(abs(a - b) for a, b in zip(lat, lat[1:])), 1) if len(lat) > 1 else 0
    progress(18)

    def pick_down():
        for u in [f"{CF}/__down?bytes=25000000"] + MIRRORS:
            try:
                req = urllib.request.Request(u, headers={**UA, "Range": "bytes=0-1023"} if u in MIRRORS else UA)
                with urllib.request.urlopen(req, timeout=8) as r: r.read(1024)
                return u
            except Exception: continue
        raise RuntimeError("every speed-test server refused us — try again in a few minutes")
    down_url = pick_down()
    if down_url != f"{CF}/__down?bytes=25000000": res["download_from"] = down_url.split("/")[2]
    def measure(kind, seconds, streams, p0, p1):
        done = [0]; stop = time.time() + seconds; lock = threading.Lock(); samples = []
        payload = os.urandom(4 * 1024 * 1024) if kind == "up" else None
        def worker():
            while time.time() < stop:
                try:
                    if kind == "down":
                        req = urllib.request.Request(down_url, headers=UA)
                        with urllib.request.urlopen(req, timeout=15) as r:
                            while time.time() < stop:
                                b = r.read(65536)
                                if not b: break
                                with lock: done[0] += len(b)
                    else:
                        req = urllib.request.Request(f"{CF}/__up", data=payload, headers={**UA, "Content-Type": "application/octet-stream"}, method="POST")
                        urllib.request.urlopen(req, timeout=20).read()
                        with lock: done[0] += len(payload)
                except Exception: time.sleep(0.2)
        ts = [threading.Thread(target=worker, daemon=True) for _ in range(streams)]
        t0 = time.time(); [t.start() for t in ts]
        last_b, last_t, warm = 0, t0, None
        while time.time() < stop:
            time.sleep(0.5); now = time.time()
            with lock: b = done[0]
            mbps = (b - last_b) * 8 / (now - last_t) / 1e6; last_b, last_t = b, now
            if now - t0 > 2: samples.append(mbps)                         # skip TCP slow start
            progress(p0 + (p1 - p0) * min(1, (now - t0) / seconds), f"{kind}load {mbps:.0f} Mbps")
        for t in ts: t.join(timeout=3)
        return round(statistics.median(samples), 1) if samples else 0, [round(x, 1) for x in samples]
    log("Measuring download…"); res["download_mbps"], res["download_samples"] = measure("down", 10, 4, 20, 60)
    log("Measuring upload…"); res["upload_mbps"], res["upload_samples"] = measure("up", 10, 3, 60, 100)
    if not res["download_mbps"]: raise RuntimeError("the download test got no data — the speed-test server may be limiting us; try again in a few minutes")
    log(f"Download {res['download_mbps']} Mbps · upload {res['upload_mbps']} Mbps · ping {res['ping_ms']} ms")
    return res


# ── disk speed ────────────────────────────────────────────────────────────────────────
def disk_speed(spec, log, progress):
    import storage
    path = spec.get("path", "")
    if not re.fullmatch(r"/[A-Za-z0-9_./-]{0,200}", path) or ".." in path.split("/") or not os.path.isdir(path): raise ValueError("pick a mounted drive or pool")
    path = os.path.realpath(path)
    if not os.path.ismount(path) or path.startswith(("/proc", "/sys", "/dev", "/run", "/boot")): raise ValueError("pick a drive or pool (its mount point)")
    if path == "/": path = "/var/tmp"                       # the system drive: test in its temp folder
    storage.ensure_tools(["fio"], log)
    free = shutil.disk_usage(path).free
    size = "1G" if free > 8e9 else "256M" if free > 1.5e9 else "64M" if free > 300e6 else None
    if not size: raise ValueError("not enough free space for a test (needs 300 MB)")
    f = os.path.join(path, f".nova-speedtest-{os.getpid()}")
    is_fuse = storage.mounts().get(storage.owner_mount(path, list(storage.mounts())), {}).get("type", "").startswith("fuse")
    tests = [("seq_read", "read", "1M", 8), ("seq_write", "write", "1M", 8), ("rand_read", "randread", "4k", 32), ("rand_write", "randwrite", "4k", 32)]
    res = {"path": path, "size": size, "note": "Through the pool (mergerfs), so this measures one of its drives." if is_fuse else ""}
    try:
        log(f"Preparing a {size} test file…")
        run(["fio", "--name=prep", f"--filename={f}", f"--size={size}", "--rw=write", "--bs=1M", "--direct=0", "--end_fsync=1"], timeout=900)
        for i, (key, rw, bs, qd) in enumerate(tests):
            progress(10 + i * 22, key.replace("_", " "))
            log(f"Testing {key.replace('_', ' ')}…")
            cmd = ["fio", "--name=t", f"--filename={f}", f"--size={size}", f"--rw={rw}", f"--bs={bs}", f"--iodepth={qd}", "--ioengine=libaio",
                   "--runtime=8", "--time_based", "--group_reporting", "--output-format=json", "--invalidate=1"]
            rc, so, se = run(cmd + ["--direct=1"], timeout=120)
            if rc != 0: rc, so, se = run(cmd + ["--direct=0", "--end_fsync=1"], timeout=120)
            if rc != 0: raise RuntimeError(f"fio: {se.strip()[-200:]}")
            j = json.loads(so[so.index("{"):])["jobs"][0]; side = j["read"] if "read" in rw else j["write"]
            res[key] = {"mbps": round(side["bw_bytes"] / 1e6, 1), "iops": round(side["iops"]), "lat_ms": round(side.get("clat_ns", {}).get("mean", 0) / 1e6, 2)}
    finally:
        try: os.remove(f)
        except OSError: pass
    progress(100)
    log(f"Read {res['seq_read']['mbps']} MB/s · write {res['seq_write']['mbps']} MB/s · random read {res['rand_read']['iops']} IOPS")
    return res


# ── CPU and memory ────────────────────────────────────────────────────────────────────
def _stress(cmd, seconds, log, progress, extra=lambda s: s, sample=None):
    import storage
    storage.ensure_tools(["stress-ng"], log)
    pw = Power(); samples = []; t0 = time.time()
    base = {"temp": cpu_temp(), "mhz": (cpu_mhz() or {}).get("avg"), "watts": None}
    time.sleep(1); base["watts"] = pw.watts()
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        while p.poll() is None:
            time.sleep(1)
            if p.poll() is not None: break                              # finished: don't sample the cool-down
            s = extra({"t": round(time.time() - t0, 1), "temp": cpu_temp(), "mhz": (cpu_mhz() or {}).get("avg"), "watts": pw.watts(), "load": load1()})
            samples.append(s)
            if sample: sample(s)
            progress(min(99, 100 * (time.time() - t0) / seconds), f"{s['temp']} °C · {s['mhz']} MHz" + (f" · {s['watts']} W" if s["watts"] else ""))
            if s["temp"] and s["temp"] >= 97:
                log("Stopped early: the CPU reached 97 °C"); p.terminate(); break
    finally:
        if p.poll() is None: p.terminate()
    out = p.communicate()[0] if p.stdout else ""
    def col(k): return [s[k] for s in samples if s.get(k) is not None]
    return {"baseline": base, "samples": samples, "max_temp": max(col("temp"), default=None), "avg_mhz": round(statistics.mean(col("mhz"))) if col("mhz") else None,
            "min_mhz": min(col("mhz"), default=None), "max_watts": max(col("watts"), default=None),
            "avg_watts": round(statistics.mean(col("watts")), 1) if col("watts") else None, "output": out[-2000:], "rc": p.returncode}

def cpu_stress(spec, log, progress, sample=None):
    sec = max(10, min(900, int(spec.get("seconds", 60))))
    workers = os.cpu_count() or 1
    log(f"Running all {workers} threads flat out for {sec} s…")
    r = _stress(["stress-ng", "--cpu", str(workers), "--cpu-method", "all", "--timeout", f"{sec}s", "--metrics-brief"], sec, log, progress, sample=sample)
    m = re.search(r"cpu\s+\d+\s+[\d.]+\s+[\d.]+\s+[\d.]+\s+[\d.]+\s+([\d.]+)", r["output"])
    r["bogo_ops_s"] = float(m.group(1)) if m else None
    hot = r["max_temp"] or 0
    r["verdict"] = ("Cooling keeps up well." if hot < 80 else "Warm but fine — most desktop CPUs are designed for up to about 90–95 °C." if hot < 92
                    else "Running very hot — check the cooler, fan curve and case airflow.")
    if r["min_mhz"] and r["avg_mhz"] and r["baseline"]["mhz"] and r["min_mhz"] < 0.5 * r["avg_mhz"]: r["verdict"] += " The clock dipped a lot, which can mean thermal throttling."
    log(f"Max {r['max_temp']} °C · avg {r['avg_mhz']} MHz" + (f" · {r['max_watts']} W peak" if r["max_watts"] else ""))
    return r

def mem_test(spec, log, progress, sample=None):
    sec = max(10, min(1800, int(spec.get("seconds", 60))))
    share = max(10, min(90, int(spec.get("percent", 50))))
    mi = meminfo(); size = int(min(mi["available"] * share / 100, mi["available"] - 1.5e9))
    if size < 256e6: raise ValueError("not enough free memory to test safely right now")
    workers = min(4, os.cpu_count() or 1); per = size // workers
    log(f"Filling {size / 1e9:.1f} GB ({share}% of what's free) with test patterns and checking them for {sec} s…")
    r = _stress(["stress-ng", "--vm", str(workers), "--vm-bytes", f"{per // (1024 * 1024)}M", "--vm-method", "all", "--verify", "--vm-keep",
                 "--timeout", f"{sec}s", "--metrics-brief"], sec, log, progress, extra=lambda s: {**s, "mem_used": meminfo()["used"]}, sample=sample)
    errors = len(re.findall(r"(?i)verif\w* (?:error|fail)|detected \d+ (?:memory )?error", r["output"]))
    r.update(tested_bytes=size, errors=errors, ok=r["rc"] == 0 and errors == 0)
    r["verdict"] = ("No errors — the memory read back exactly what was written." if r["ok"] else
                    "Errors found! Faulty RAM or unstable settings (XMP/EXPO overclock). Run memtest86+ from a USB stick to confirm.")
    log(r["verdict"])
    return r


# ── quick checks (helper verbs; return in seconds) ────────────────────────────────────
HOST_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9.-]{0,252}|[0-9a-fA-F:.]{2,45}")

def ping(host):
    if not HOST_RE.fullmatch(host): raise ValueError("bad host")
    rc, so, se = run(["ping", "-c", "10", "-i", "0.3", "-W", "2", host], timeout=30)
    m = re.search(r"([\d.]+)% packet loss", so); rt = re.search(r"= ([\d.]+)/([\d.]+)/([\d.]+)/([\d.]+) ms", so)
    times = [float(x) for x in re.findall(r"time=([\d.]+) ms", so)]
    return {"host": host, "loss_pct": float(m.group(1)) if m else 100.0, "min": rt and float(rt.group(1)), "avg": rt and float(rt.group(2)),
            "max": rt and float(rt.group(3)), "jitter": round(statistics.mean(abs(a - b) for a, b in zip(times, times[1:])), 2) if len(times) > 1 else None,
            "times": times, "error": "" if rc == 0 else (se.strip() or "no reply")[-200:]}

def trace(host):
    if not HOST_RE.fullmatch(host): raise ValueError("bad host")
    tool = shutil.which("tracepath") or shutil.which("traceroute")
    if not tool:
        import storage; storage.ensure_tools(["tracepath"]); tool = shutil.which("tracepath")
    rc, so, se = run([tool, "-n", "-m", "20", host] if tool.endswith("tracepath") else [tool, "-n", "-m", "20", "-q", "1", host], timeout=90)
    hops = []
    for l in so.splitlines():
        m = re.match(r"\s*(\d+)\??:?\s+(\S+)\s+(?:([\d.]+)ms)?", l)
        if m and m.group(2) not in ("[LOCALHOST]",) and not l.strip().startswith("Resume"):
            if hops and hops[-1]["hop"] == int(m.group(1)): continue
            hops.append({"hop": int(m.group(1)), "host": m.group(2), "ms": float(m.group(3)) if m.group(3) else None})
    return {"host": host, "hops": hops}

def dns(name):
    if not HOST_RE.fullmatch(name): raise ValueError("bad name")
    out = {"name": name, "resolvers": []}
    try: servers = re.findall(r"^nameserver\s+(\S+)", open("/etc/resolv.conf").read(), re.M)
    except OSError: servers = []
    t = time.perf_counter()
    try: addrs = sorted({a[4][0] for a in socket.getaddrinfo(name, None)}); err = ""
    except socket.gaierror as e: addrs, err = [], str(e)
    out.update(addresses=addrs, ms=round((time.perf_counter() - t) * 1000, 1), error=err, system_resolvers=servers)
    return out

def port(host, p):
    if not HOST_RE.fullmatch(host) or not (0 < int(p) < 65536): raise ValueError("bad host or port")
    t = time.perf_counter()
    try:
        with socket.create_connection((host, int(p)), timeout=5): pass
        return {"host": host, "port": int(p), "open": True, "ms": round((time.perf_counter() - t) * 1000, 1)}
    except OSError as e:
        return {"host": host, "port": int(p), "open": False, "error": str(e)[:120]}

def top():
    rc, so, _ = run(["ps", "-eo", "pid,user,pcpu,pmem,rss,etimes,comm", "--sort=-pcpu", "--no-headers"], timeout=10)
    procs = []
    for l in so.splitlines()[:40]:
        f = l.split(None, 6)
        if len(f) == 7: procs.append({"pid": int(f[0]), "user": f[1], "cpu": float(f[2]), "mem": float(f[3]), "rss": int(f[4]) * 1024, "age": int(f[5]), "name": f[6]})
    return {"procs": procs, "mem": meminfo(), "load": list(os.getloadavg()), "cpus": os.cpu_count(), "temp": cpu_temp(), "mhz": cpu_mhz()}

TESTS = {"net-internet": net_internet, "disk-speed": disk_speed, "cpu-stress": cpu_stress, "mem-test": mem_test}
