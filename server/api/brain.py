"""
The assistant's brain (Labs): a small language model that runs on this server, so the assistant understands what the rules
alone don't ("make the lights look like a police car", "is my disk ok?") and can answer questions about the server.

  • Nothing extra to set up: one download from Settings → Labs → Nova assistant. Nova fetches llama.cpp's server
    (llama-server, MIT) and an open model (Qwen, Apache-2.0) — both pinned to an exact file and checked against its
    SHA-256 — into /var/lib/nova-brain, and runs it as its own service (nova-brain).
  • Private: it listens on 127.0.0.1 only, runs as a throwaway system user with no access to your files, can't reach the
    network (IPAddressDeny), and gets a low CPU share so containers come first. Nothing you say leaves the server.
  • It only ever suggests Nova's own commands; the assistant's rules read those again, so the model can't add an action
    the rules don't know (see assistant.py).
"""
import hashlib, json, os, platform, re, shutil, subprocess, tarfile, time, urllib.request

ROOT = "/var/lib/nova-brain"
UNIT = "/etc/systemd/system/nova-brain.service"
PORT = 8497
RUNTIME = {      # llama.cpp b11463 (2026-10-07)
    "x86_64": ("https://github.com/ggml-org/llama.cpp/releases/download/b11463/llama-b11463-bin-ubuntu-x64.tar.gz",
               "c7d63a3dfd1fe93a679b4bcd981dacb9153e2509a21bb912b9a01fc28a8447f5", 17732486),
    "aarch64": ("https://github.com/ggml-org/llama.cpp/releases/download/b11463/llama-b11463-bin-ubuntu-arm64.tar.gz",
                "95e17be13037fafbed43c7afd75230ce04defb941d066062d0aff4bb3d52caf4", 13724995),
}
MODELS = {
    "smart": {"name": "Smart", "about": "Qwen3 4B — understands more and answers questions well. About 3–8 seconds a request.",
              "file": "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", "size": 2497281120, "ram_gb": 4,
              "url": "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
              "sha256": "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597"},
    "light": {"name": "Light", "about": "Qwen2.5 1.5B — quicker and smaller, for servers with less memory. Makes more mistakes.",
              "file": "qwen2.5-1.5b-instruct-q4_k_m.gguf", "size": 1117320736, "ram_gb": 2,
              "url": "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
              "sha256": "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"},
}

# Voice: whisper.cpp's server (MIT) and OpenAI's Whisper "small" model (MIT), turning what you say into text — on the server
EAR_RUNTIME = {      # whisper.cpp b5454 (2026-10-06)
    "x86_64": ("https://github.com/ggml-org/whisper.cpp/releases/download/b5454/whisper-bin-ubuntu-x64.tar.gz",
               "a72becf15d7917f990f6313867a52638b82b7f9ef237fb0c980dac56a135781c", 10364195),
    "aarch64": ("https://github.com/ggml-org/whisper.cpp/releases/download/b5454/whisper-bin-ubuntu-arm64.tar.gz",
                "6b95ebfc60447df48e70ef00a73bdc3f41679ed2d01ef465827206a2ff325149", 4608377),
}
EAR_MODEL = {"file": "ggml-small-q5_1.bin", "size": 190085487, "sha256": "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
             "url": "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"}
EAR_UNIT = "/etc/systemd/system/nova-ear.service"
EAR_PORT = 8498

def _mem_gb():
    try: return int(re.search(r"MemTotal:\s+(\d+)", open("/proc/meminfo").read()).group(1)) / 1048576
    except Exception: return 0

def status():
    cfg = {}
    try: cfg = json.load(open(f"{ROOT}/brain.json"))
    except (OSError, ValueError): pass
    m = cfg.get("model")
    active = subprocess.run(["systemctl", "is-active", "nova-brain.service"], capture_output=True, text=True).stdout.strip() == "active"
    ear = os.path.exists(f"{ROOT}/voice/{EAR_MODEL['file']}") and os.path.exists(f"{ROOT}/voice-runtime/whisper-server")
    ear_on = ear and subprocess.run(["systemctl", "is-active", "nova-ear.service"], capture_output=True, text=True).stdout.strip() == "active"
    return {"model": m if m in MODELS and os.path.exists(f"{ROOT}/models/{MODELS[m]['file']}") else None, "running": active,
            "voice": {"installed": ear, "running": ear_on, "size": EAR_MODEL["size"] + EAR_RUNTIME.get(platform.machine(), ("", "", 0))[2]},
            "arch_ok": platform.machine() in RUNTIME, "memory_gb": round(_mem_gb(), 1),
            "choices": {k: {kk: v[kk] for kk in ("name", "about", "size", "ram_gb")} for k, v in MODELS.items()},
            "recommended": "smart" if _mem_gb() >= 7 else "light"}

def _download(url, dest, sha, size, log, progress, lo, hi):
    """Into dest.part, checked against its SHA-256 before it's moved into place. Resumes a part file."""
    part = dest + ".part"; h = hashlib.sha256(); got = 0
    if os.path.exists(part):
        got = os.path.getsize(part)
        with open(part, "rb") as f:
            for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    req = urllib.request.Request(url, headers={"User-Agent": "Nova", **({"Range": f"bytes={got}-"} if got else {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        if got and r.status != 206: got = 0; h = hashlib.sha256(); open(part, "wb").close()
        with open(part, "ab" if got else "wb") as f:
            last = 0
            while True:
                b = r.read(1 << 20)
                if not b: break
                f.write(b); h.update(b); got += len(b)
                if got > size + (1 << 20): raise RuntimeError("the download is bigger than it should be — stopped")
                if time.time() - last > 1: last = time.time(); progress(lo + int((hi - lo) * got / size), f"{got / 1e9:.2f} of {size / 1e9:.2f} GB")
    if h.hexdigest() != sha:
        os.remove(part); raise RuntimeError(f"{os.path.basename(dest)} didn't match its checksum — it was removed (try again)")
    os.replace(part, dest); log(f"Checked {os.path.basename(dest)} ✓")

def install(spec, log=print, progress=lambda p, n="": None):
    key = spec.get("model")
    if key not in MODELS: raise ValueError("pick Smart or Light")
    arch = platform.machine()
    if arch not in RUNTIME: raise ValueError(f"the assistant's brain isn't available for {arch} servers yet")
    m = MODELS[key]
    free = shutil.disk_usage("/var/lib").free
    if free < m["size"] + 400e6: raise ValueError(f"needs {(m['size'] + 400e6) / 1e9:.1f} GB free on the system drive — Free up can help")
    os.makedirs(f"{ROOT}/models", exist_ok=True); os.chmod(ROOT, 0o755)
    url, sha, size = RUNTIME[arch]
    tgz = f"{ROOT}/runtime.tar.gz"
    if not (os.path.exists(f"{ROOT}/runtime/llama-server") and open(f"{ROOT}/runtime.sha").read().strip() == sha if os.path.exists(f"{ROOT}/runtime.sha") else False):
        log("Downloading the model runner (llama.cpp)…"); _download(url, tgz, sha, size, log, progress, 2, 8)
        _unpack(tgz, f"{ROOT}/runtime", "llama-server")
        open(f"{ROOT}/runtime.sha", "w").write(sha)
    dest = f"{ROOT}/models/{m['file']}"
    if not os.path.exists(dest):
        log(f"Downloading the {m['name']} model ({m['size'] / 1e9:.1f} GB)…"); _download(m["url"], dest, m["sha256"], m["size"], log, progress, 8, 95)
    for f in os.listdir(f"{ROOT}/models"):                   # one model at a time
        if f != m["file"]: os.remove(f"{ROOT}/models/{f}")
    json.dump({"model": key, "since": int(time.time())}, open(f"{ROOT}/brain.json", "w"))
    write_unit(m["file"]); progress(97, "Starting it")
    subprocess.run(["systemctl", "daemon-reload"], check=True); subprocess.run(["systemctl", "enable", "--now", "nova-brain.service"], check=True)
    for _ in range(60):
        if ready(): break
        time.sleep(1)
    else: raise RuntimeError("the model didn't start — sudo journalctl -u nova-brain shows why")
    log(f"The {m['name']} brain is ready"); progress(100, "Ready")
    return {"model": key}

def _unpack(tgz, dest, binary):
    tmp = dest + ".new"; shutil.rmtree(tmp, ignore_errors=True); os.makedirs(tmp)
    with tarfile.open(tgz) as t:
        for mem in t.getmembers():                      # only plain files and links inside the archive's own folder
            if mem.name.startswith("/") or ".." in mem.name.split("/") or not (mem.isfile() or mem.issym() or mem.isdir()): continue
            if mem.issym() and (mem.linkname.startswith("/") or ".." in mem.linkname.split("/")): continue
            t.extract(mem, tmp, filter="data") if hasattr(tarfile, "data_filter") else t.extract(mem, tmp)
    inner = next((os.path.join(tmp, d) for d in os.listdir(tmp) if os.path.isfile(os.path.join(tmp, d, binary))), None)
    if not inner: raise RuntimeError(f"{binary} wasn't in the download")
    shutil.rmtree(dest, ignore_errors=True); os.replace(inner, dest); shutil.rmtree(tmp, ignore_errors=True); os.remove(tgz)

def install_voice(spec, log=print, progress=lambda p, n="": None):
    arch = platform.machine()
    if arch not in EAR_RUNTIME: raise ValueError(f"voice isn't available for {arch} servers yet")
    if shutil.disk_usage("/var/lib").free < EAR_MODEL["size"] + 200e6: raise ValueError("needs about 0.4 GB free on the system drive")
    os.makedirs(f"{ROOT}/voice", exist_ok=True); os.chmod(ROOT, 0o755)
    url, sha, size = EAR_RUNTIME[arch]
    if not os.path.exists(f"{ROOT}/voice-runtime/whisper-server"):
        log("Downloading the speech runner (whisper.cpp)…"); _download(url, f"{ROOT}/voice-runtime.tar.gz", sha, size, log, progress, 2, 10)
        _unpack(f"{ROOT}/voice-runtime.tar.gz", f"{ROOT}/voice-runtime", "whisper-server")
    dest = f"{ROOT}/voice/{EAR_MODEL['file']}"
    if not os.path.exists(dest):
        log("Downloading the speech model (0.2 GB)…"); _download(EAR_MODEL["url"], dest, EAR_MODEL["sha256"], EAR_MODEL["size"], log, progress, 10, 95)
    threads = max(2, min(6, (os.cpu_count() or 4) // 2))
    open(EAR_UNIT, "w").write(UNIT_TEXT.format(desc="Nova assistant's ears (speech to text, on 127.0.0.1 only)", lib=f"{ROOT}/voice-runtime",
        cmd=f"{ROOT}/voice-runtime/whisper-server -m {dest} --host 127.0.0.1 --port {EAR_PORT} -t {threads} -l auto"))
    subprocess.run(["systemctl", "daemon-reload"], check=True); subprocess.run(["systemctl", "enable", "--now", "nova-ear.service"], check=True)
    progress(100, "Ready"); log("Voice is ready")
    return {"voice": True}

def remove_voice():
    subprocess.run(["systemctl", "disable", "--now", "nova-ear.service"], capture_output=True)
    try: os.remove(EAR_UNIT)
    except OSError: pass
    subprocess.run(["systemctl", "daemon-reload"], capture_output=True)
    shutil.rmtree(f"{ROOT}/voice", ignore_errors=True); shutil.rmtree(f"{ROOT}/voice-runtime", ignore_errors=True)
    return {"ok": True}

def hear(wav, lang="auto", timeout=60):
    """Speech (a 16 kHz mono WAV) → text, through the local whisper server. None if voice isn't running."""
    b = "----nova" + os.urandom(8).hex()
    lang = lang if re.fullmatch(r"[a-z]{2}|auto", str(lang)) else "auto"
    def field(k, v): return f"--{b}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
    body = (field("response_format", "json") + field("temperature", "0") + field("language", lang)
            + f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n".encode() + wav + f"\r\n--{b}--\r\n".encode())
    try:
        req = urllib.request.Request(f"http://127.0.0.1:{EAR_PORT}/inference", body, {"Content-Type": f"multipart/form-data; boundary={b}"})
        with urllib.request.urlopen(req, timeout=timeout) as r: j = json.loads(r.read(100_000))
        return " ".join(str(j.get("text", "")).split())[:500]
    except Exception:
        return None

UNIT_TEXT = """[Unit]
Description={desc}
After=network.target

[Service]
DynamicUser=yes
Environment=LD_LIBRARY_PATH={lib}
ExecStart={cmd}
Restart=on-failure
RestartSec=5
# its own: no access to your files or the network, a low share of the CPU so your apps come first
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes
NoNewPrivileges=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
IPAddressDeny=any
IPAddressAllow=localhost
CPUWeight=20
Nice=10
MemoryMax=6G

[Install]
WantedBy=multi-user.target
"""

def write_unit(model_file):
    threads = max(2, min(8, (os.cpu_count() or 4) // 2))
    open(UNIT, "w").write(UNIT_TEXT.format(desc="Nova assistant's brain (a local language model, on 127.0.0.1 only)", lib=f"{ROOT}/runtime",
        cmd=f"{ROOT}/runtime/llama-server -m {ROOT}/models/{model_file} --host 127.0.0.1 --port {PORT} -c 4096 -t {threads} -np 1 --no-webui"))

def remove():
    subprocess.run(["systemctl", "disable", "--now", "nova-brain.service"], capture_output=True)
    try: os.remove(UNIT)
    except OSError: pass
    subprocess.run(["systemctl", "daemon-reload"], capture_output=True)
    for p in ("models", "runtime", "brain.json", "runtime.sha"):
        q = f"{ROOT}/{p}"; shutil.rmtree(q, ignore_errors=True) if os.path.isdir(q) else (os.path.exists(q) and os.remove(q))
    return {"ok": True}

def ready():
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=2) as r: return json.loads(r.read()).get("status") == "ok"
    except Exception: return False


# ── asking it (from nova-api) ─────────────────────────────────────────────────────────
SYS = """You are Nova, the assistant built into a home-server app. Turn the user's request into short commands Nova knows, and write one friendly sentence back in "reply".
Commands (use these shapes exactly; fill in the CAPS parts with real values, never the CAPS words themselves):
- set the lights to EFFECT   (EFFECT: static pulse blink cycle wave random gradient comet scanner twinkle fire breathe)
- make the lights COLOR   (COLOR: red orange yellow green blue purple pink white warm white cyan teal magenta)
- turn the lights on | turn the lights off
- turn off the status light | turn on the status light
- lower the light brightness a bit | lower the light brightness a lot | raise the light brightness a bit | set the light brightness to N%
- free up storage | free up ram
- install APP   (APP: one from the app store list below)
- restart CONTAINER | stop CONTAINER | start CONTAINER   (CONTAINER: one from the server facts)
- run backups now
- check for updates
- schedule a reboot WHEN | schedule a shutdown WHEN   (WHEN like: at 5pm tomorrow, on friday at noon, in 2 hours)
- reboot the server
- switch to dark mode | switch to light mode | apply the STYLE theme   (STYLE: default material glass mica breeze aero)
- open SCREEN   (SCREEN: home status containers store inbox lighting storage backups updates settings appearance security devices files terminal)
If the user only asks a question, give no commands and answer it in "reply" from the server facts and the conversation (numbers included), in one or two sentences.
When there are commands, "reply" says what you're about to do ("Freeing up memory now.") — never claim something is done or give results you don't have yet. Numbers under "What happened" in the conversation are real; use them when asked.
Never invent containers or apps. If nothing fits, give no commands and say what you can do."""
SCHEMA = {"type": "object", "properties": {"commands": {"type": "array", "items": {"type": "string", "maxLength": 120}, "maxItems": 8},
                                           "reply": {"type": "string", "maxLength": 400}}, "required": ["commands", "reply"]}
_EX = None
def examples():
    """The worked examples it learns from (assistant_examples.json): the closest ones go with each request."""
    global _EX
    if _EX is None:
        try: _EX = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "assistant_examples.json")))
        except (OSError, ValueError): _EX = []
    return _EX
STOP = {"the", "a", "an", "my", "me", "i", "it", "to", "and", "of", "for", "is", "in", "please", "can", "you", "on", "at", "this", "that"}
def _toks(s): return set(re.findall(r"[a-z]+", s.lower())) - STOP
def pick(text, k=8):
    t = _toks(text)
    scored = sorted(examples(), key=lambda e: -len(t & _toks(e[0])) / (1 + len(_toks(e[0]))))
    out = scored[:k]
    q = next((e for e in examples() if not e[1] and e not in out), None)       # always one that just answers
    if q: out.append(q)
    return list(reversed(out))

def convo(history):
    """The conversation so far, as the model reads it: what you asked, what Nova said, and what its steps did."""
    out = []
    for h in (history or [])[-6:]:
        if not isinstance(h, dict): continue
        q = str(h.get("q", ""))[:200]; r = str(h.get("reply", ""))[:240]; d = [str(x)[:140] for x in (h.get("done") or [])][:8]
        out.append(f"- You asked: {q}" + (f"\n  Nova said: {r}" if r else "") + (f"\n  What happened: {'; '.join(d)}" if d else ""))
    return ("Earlier in this conversation (use it for follow-ups like “how much did that free?” or “actually make it 6pm”):\n" + "\n".join(out)) if out else ""

def ask(text, facts, history=None, timeout=30):
    """{"commands": [...], "reply": "..."} from the model, or None if it isn't installed or running."""
    # the fixed instructions first (the model keeps them processed between requests), then the closest examples, and the
    # server's facts last with the request — they change every time
    msgs = [{"role": "system", "content": SYS}]
    for u, c, r in pick(text): msgs += [{"role": "user", "content": u}, {"role": "assistant", "content": json.dumps({"commands": c, "reply": r})}]
    c = convo(history)
    msgs.append({"role": "user", "content": facts + ("\n\n" + c if c else "") + "\n\nRequest: " + text[:500]})
    body = {"messages": msgs, "temperature": 0.1, "max_tokens": 260, "cache_prompt": True, "response_format": {"type": "json_schema", "json_schema": {"schema": SCHEMA}}}
    try:
        req = urllib.request.Request(f"http://127.0.0.1:{PORT}/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as r: j = json.loads(r.read(200_000))
        out = json.loads(j["choices"][0]["message"]["content"])
        cmds = [str(c)[:160] for c in (out.get("commands") or []) if isinstance(c, str)][:8]
        return {"commands": cmds, "reply": "".join(ch for ch in str(out.get("reply", "")) if ch.isprintable())[:400]}
    except Exception:
        return None
