"""
Nova's assistant (Labs): a sentence in plain words becomes a plan of Nova's own actions.

  "Apply the Fire light theme, free up some storage and RAM, disable the status lights, lower the brightness of the
   fan a bit, install the Brave container, and schedule a reboot at 5pm a week from now."

Safe by design:
  • It only *plans*. Every step is one of Nova's ordinary API calls, and the app or browser that asked runs them one by
    one, signed as itself, so every rule still holds. Viewers can't change anything, risky calls still need a
    fingerprint (or a phone's approval), and everything lands in the action log. Nothing here runs anything.
  • Steps come from a fixed list. Any name taken from the sentence (a container, an app, a drive) must be a real one
    from the server's own lists before it goes in a path.
  • When it's not sure (two drives match "the 1TB Seagate"), it asks and offers the choices instead of guessing.
    A drive is never erased from here: the plan is handed to the Partition manager, which asks for the drive's serial
    and your fingerprint as always.

It understands the usual requests on its own, instantly (rules — nothing leaves the server). With its brain installed
(brain.py: a small language model on this server), it also gets what the rules don't and answers questions; the model
only writes Nova's own commands, which these rules read again.
"""
import datetime, difflib, re

MAX_TEXT = 500

# ── splitting a request into its parts ────────────────────────────────────────────────
VERBS = r"(?:turn|switch|set|apply|use|change|make|put|free|clean|clear|disable|enable|lower|raise|dim|brighten|increase|decrease|reduce|install|add|" \
        r"schedule|plan|restart|reboot|shut|power|stop|start|open|show|go|take|back ?up|run|check|update|erase|wipe|format|reformat|partition|cancel)"
FILLER = re.compile(r"^(?:hey|hi|ok|okay)?\s*,?\s*(?:nova)?\s*[,.!]?\s*|\b(?:please|kindly|for me|real quick|thanks|thank you)\b|\b(?:can|could|would|will) you\b|"
                    r"\bi(?:'d| would)? (?:like|want|need) (?:you )?to\b|\bi want\b|\bgo ahead and\b|\bmind\b|\bjust\b", re.I)

def clauses(text):
    t = re.sub(r"[’‘]", "'", text.strip())[:MAX_TEXT]
    t = FILLER.sub(" ", t)
    parts = re.split(r"\s*(?:[;!?]|,|\.(?=\s|$)|\band then\b|\bthen\b|\band also\b|\balso\b|\bwhile you'?re at it\b|\bafter that\b|\bplus\b)\s*", t, flags=re.I)
    out = []
    for p in parts:
        # "x and y": a new part only when "y" starts with something to do ("storage and RAM" stays one)
        for q in re.split(rf"\s+and\s+(?={VERBS}\b)", p, flags=re.I):
            q = re.sub(r"^\s*(?:and|&)\s+", "", q.strip(" .")).strip()
            if q and not re.fullmatch(r"(?:and|also|so|ok|okay|then|now|too|as well)", q, re.I): out.append(q)
    # a drive request spans its parts ("erase the 1TB drive, reformat it to NTFS, and make 33% … 67% …"): keep them together
    merged = []
    for q in out:
        if merged and re.search(r"\b(?:erase|wipe|format|reformat|partition)\b", merged[-1], re.I) and re.search(r"\b(?:drive|disk|tb|gb)\b", merged[-1], re.I) \
                and re.search(r"\b(?:format|reformat|ntfs|ext4|exfat|xfs|btrfs|fat32|partitions?|split|called|named|mounted)\b|%|/mnt/", q, re.I):
            merged[-1] += ", " + q
        else: merged.append(q)
    return merged


# ── when: "at 5pm a week from now", "tomorrow at 17:30", "on friday at noon", "in 2 hours" ──
DAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
MONTHS = ["january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"]

def parse_when(s, now):
    """A datetime (server's local time) from the words, or None. Raises ValueError with a reason for a time that can't be."""
    s = s.lower()
    m = re.search(r"\bin (\d+|a|an|one|two|three) (minute|hour|day|week)s?\b", s)
    num = lambda x: {"a": 1, "an": 1, "one": 1, "two": 2, "three": 3}.get(x) or int(x)
    if m:
        k = num(m.group(1)); return now + {"minute": datetime.timedelta(minutes=k), "hour": datetime.timedelta(hours=k), "day": datetime.timedelta(days=k), "week": datetime.timedelta(weeks=k)}[m.group(2)]
    hh = mm = None
    t = re.search(r"\b(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)\b", s) or re.search(r"\bat\s+(\d{1,2}):(\d{2})\b()", s) or re.search(r"\b(\d{1,2}):(\d{2})\b()", s)
    if t:
        hh, mm = int(t.group(1)), int(t.group(2) or 0); ap = (t.group(3) or "").replace(".", "")
        if ap == "pm" and hh < 12: hh += 12
        if ap == "am" and hh == 12: hh = 0
        if not (0 <= hh <= 23 and 0 <= mm <= 59): raise ValueError("that isn't a time of day")
    elif re.search(r"\bnoon\b", s): hh, mm = 12, 0
    elif re.search(r"\bmidnight\b", s): hh, mm = 0, 0
    elif re.search(r"\btonight\b", s): hh, mm = 21, 0
    day = None
    if re.search(r"\b(?:a|one) week from (?:now|today)\b|\bin a week\b|\bnext week\b", s): day = (now + datetime.timedelta(days=7)).date()
    elif re.search(r"\btomorrow\b", s): day = (now + datetime.timedelta(days=1)).date()
    elif re.search(r"\btoday\b|\btonight\b", s): day = now.date()
    else:
        d = re.search(r"\b(?:on |next |this )?(" + "|".join(DAYS) + r")\b", s)
        if d:
            ahead = (DAYS.index(d.group(1)) - now.weekday()) % 7
            if ahead == 0 or "next" in s[max(0, d.start() - 5):d.start() + 5]: ahead = ahead or 7
            day = (now + datetime.timedelta(days=ahead)).date()
        else:
            d = re.search(r"\b(" + "|".join(x[:3] for x in MONTHS) + r")[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b", s) or re.search(r"\b(\d{1,2})/(\d{1,2})\b", s)
            if d:
                mon = [x[:3] for x in MONTHS].index(d.group(1)[:3]) + 1 if not d.group(1).isdigit() else int(d.group(1))
                dd = int(d.group(2))
                try:
                    day = datetime.date(now.year, mon, dd)
                    if day < now.date(): day = datetime.date(now.year + 1, mon, dd)
                except ValueError: raise ValueError("that date doesn't exist")
    if day is None and hh is None: return None
    if hh is None: raise ValueError("say what time, like “at 5pm”")
    if day is None:                                   # just a time: today, or tomorrow if that's passed
        day = now.date() if (hh, mm) > (now.hour, now.minute) else (now + datetime.timedelta(days=1)).date()
    return datetime.datetime(day.year, day.month, day.day, hh, mm)

def when_text(dt):
    return dt.strftime("%A %b %-d at %-I:%M %p").replace(":00 ", " ")


# ── the plan's building blocks ────────────────────────────────────────────────────────
def call(say, method, path, body=None, done=None, kind="call", **kw):
    s = {"kind": kind, "say": say, "call": {"method": method, "path": "/api/v1" + path, "body": body}, "done": done or say}
    s.update(kw); return s

def ask(say, question, options):
    return {"kind": "ask", "say": say, "ask": question, "options": options}

EFFECTS = {"static": "Solid", "pulse": "Pulse", "blink": "Blink", "cycle": "Color cycle", "wave": "Wave", "random": "Random", "gradient": "Gradient",
           "comet": "Comet", "scanner": "Scanner", "twinkle": "Twinkle", "fire": "Fire", "breathe": "Breathe"}
EFFECT_WORDS = {"solid": "static", "steady": "static", "flame": "fire", "flames": "fire", "rainbow": "cycle", "breathing": "breathe", "stars": "twinkle", "sparkle": "twinkle"}
COLORS = {"red": "#ff2a1a", "orange": "#ff7a00", "yellow": "#ffd000", "green": "#22d36a", "blue": "#2f7dff", "purple": "#8a3cff", "violet": "#8a3cff",
          "pink": "#ff3c9a", "white": "#ffffff", "warm white": "#ffd9a0", "cyan": "#00d8e8", "teal": "#00c7be", "magenta": "#ff2bd6"}
STYLES = {"default": "default", "material": "material", "material you": "material", "glass": "liquid", "liquid glass": "liquid", "mica": "fluent",
          "fluent": "fluent", "breeze": "breeze", "aero": "aero", "windows 7": "aero", "lumen": "lumen"}
STYLE_NAMES = {"default": "Default", "material": "Material You", "liquid": "Glass", "fluent": "Mica", "breeze": "Breeze", "aero": "Aero", "lumen": "Lumen"}
# the living wallpaper's scenes (named after the look they come from, or by their own names)
SCENES = {"nebula": "default", "default": "default", "harmony": "aero", "aero": "aero", "bloom": "fluent", "mica": "fluent", "fluent": "fluent",
          "flow": "breeze", "breeze": "breeze", "liquid": "liquid", "glass": "liquid", "shapes": "material", "material": "material", "aurora": "lumen", "lumen": "lumen"}
SCENE_NAMES = {"default": "Nebula", "aero": "Harmony (Aero)", "fluent": "Bloom (Mica)", "breeze": "Flow (Breeze)", "liquid": "Liquid (Glass)", "material": "Shapes (Material)", "lumen": "Aurora (Lumen)"}
SCREENS = {"home": "home", "status": "status", "containers": "containers", "store": "store", "app store": "store", "inbox": "inbox", "alerts": "inbox",
           "lighting": "lighting", "lights": "lighting", "storage": "hardware", "drives": "hardware", "backups": "backups", "updates": "updates",
           "settings": "settings", "appearance": "appearance", "security": "security", "devices": "devices", "files": "files", "terminal": "terminal",
           "dashboard": "dashboard", "apps": "apps", "partition manager": "partitions", "free up": "freeup", "recommended": "recommended"}
LIGHT_WORDS = r"\b(?:lights?|lighting|leds?|rgb|fans?|strips?|glow)\b"
FS = {"ntfs": "ntfs", "ext4": "ext4", "exfat": "exfat", "xfs": "xfs", "btrfs": "btrfs", "fat32": "vfat", "fat": "vfat", "vfat": "vfat"}

def norm(s): return re.sub(r"[^a-z0-9]+", "", s.lower())
FOLLOW = re.compile(r"^\s*(?:actually|instead|no\b|nope|also|and|then|change|make it|do (?:that|it)|undo|again|same|what about|how (?:much|many|long)|did (?:it|that|you)|that|it|those|them)\b", re.I)
# small talk the rules can answer on their own (the brain, when installed, answers these itself — more naturally)
CHAT = [(re.compile(r"^\W*(?:yo|hi|hey|hello|hiya|howdy|sup|good (?:morning|afternoon|evening))\b[\s,!.]*(?:nova)?\W*$|^\W*(?:yo|hey|hi)?\W*(?:what'?s|whats|wassup|what is) up\W*$", re.I),
         "Hey! 👋 All good here. Ask me to do something on your server — like “free up some space” or “set the lights to fire”."),
        (re.compile(r"^\W*how(?: are|'?re| r) (?:you|u)(?: doing)?(?: today)?\W*$", re.I), "Doing great, thanks! What can I do for you?"),
        (re.compile(r"^\W*(?:thanks|thank you|thx|ty|cheers|nice|great|awesome|cool|perfect)\b(?: (?:a lot|so much|nova))?\W*$", re.I), "Anytime! 😊"),
        (re.compile(r"^\W*(?:who are you|what are you)\W*$", re.I), "I'm Nova, the assistant on your server. I can set your lights, free up space, install apps, restart containers, run backups, schedule restarts and change how Nova looks."),
        (re.compile(r"^\W*(?:what can you do|help|what do you do)\W*$", re.I), "I can set your lights, free up space and memory, install apps, restart containers, run backups, check for updates, schedule restarts and change Nova's look — several at once is fine. Download my brain (below) and I can chat and answer questions too.")]
QUESTION = re.compile(r"\?\s*$|^\s*(?:what|why|how|is|are|does|do|when|where|which|who|should|any)\b", re.I)


class Planner:
    def __init__(self, env, now=None):
        self.env = env; self.now = now or datetime.datetime.now()
        self._lights = self._cts = self._store = self._drives = None

    # what the server has (asked for only when a step needs it)
    def lights(self):
        if self._lights is None: self._lights = self.env.get("lights", lambda: None)() or {}
        return self._lights
    def containers(self):
        if self._cts is None: self._cts = self.env.get("containers", lambda: [])() or []
        return self._cts
    def store(self):
        if self._store is None: self._store = self.env.get("store", lambda: [])() or []
        return self._store
    def drives(self):
        if self._drives is None: self._drives = self.env.get("drives", lambda: [])() or []
        return self._drives

    def run(self, text):
        if not self.env.get("brain"):
            hit = next((r for rx, r in CHAT if rx.search(text)), None)
            if hit: return {"steps": [], "unknown": [], "notes": [], "reply": hit, "chat": True, "text": text[:MAX_TEXT]}
        p = self.read(clauses(text))
        # the brain (brain.py), when it's installed: for anything the rules didn't get, and for questions. It answers in
        # Nova's own commands, which the rules read again — so it can't add an action they don't know.
        asked = bool(QUESTION.search(text)); follow = bool(self.env.get("history") and FOLLOW.search(text))
        if self.env.get("brain") and (p["unknown"] or asked or follow):
            b = self.env["brain"](text)
            if b is not None:
                q = self.read(b["commands"], from_brain=True)
                if not p["steps"] or ((asked or follow) and not p["unknown"]):
                    # nothing the rules could do, or a question / follow-up: the brain's reading wins (the rules would act on
                    # "how much RAM did we free?")
                    q["unknown"] = [] if b["reply"] or q["steps"] else p["unknown"]
                    p = q
                else:
                    # the rules got some of it: keep their steps, add what the brain found for the rest
                    have = {s_["say"] for s_ in p["steps"]}
                    p["steps"] += [s_ for s_ in q["steps"] if s_["say"] not in have]
                    p["notes"] += [n for n in q["notes"] if n not in p["notes"]]
                    if q["steps"]: p["unknown"] = []
                p["reply"] = b["reply"]; p["brain"] = True
        for i, s in enumerate(p["steps"]): s["id"] = f"s{i + 1}"
        p["text"] = text[:MAX_TEXT]
        return p

    def read(self, parts, from_brain=False):
        steps, unknown, notes = [], [], []
        last = None
        for c in parts:
            got = self.one(c, last)
            if got is None: unknown.append(c); continue
            kind, ss = got
            last = kind
            for s in ss:
                if s.get("kind") == "note": notes.append(s["say"])
                else: steps.append(s)
        return {"steps": steps, "unknown": unknown, "notes": notes}

    def one(self, c, last):
        low = c.lower().strip()
        # a part with no verb of its own continues the one before: "free up storage, and RAM"
        if last == "freeup" and re.fullmatch(r"(?:the |some )?(?:ram|memory|storage|space|disk(?: space)?)", low): low = "free up " + low
        for f in (self.drive, self.schedule, self.power_now, self.status_light, self.brightness, self.lights_onoff, self.freeup,
                  self.install, self.container_action, self.backup, self.updates, self.ui, self.light_effect, self.navigate):
            r = f(low)
            if r is not None: return r
        return None

    # ── lights ──
    def _no_lights(self):
        return ("lights", [{"kind": "note", "say": "There are no lights set up on this server (Settings → Lighting)."}])
    def status_light(self, s):
        if not re.search(r"\bstatus (?:lights?|led|leds|glow)\b", s): return None
        if not self.lights(): return self._no_lights()
        on = not re.search(r"\b(?:disable|off|turn off|switch off|hide|stop|no)\b", s)
        return ("lights", [call(f"{'Turn on' if on else 'Turn off'} the status light", "POST", "/fan", {"status_light": on},
                                f"Status light {'on' if on else 'off'}")])
    def brightness(self, s):
        if not (re.search(r"\bbrightness\b", s) or re.search(r"\b(?:dim|brighten)\b", s)): return None
        if not re.search(LIGHT_WORDS + r"|\bbrightness\b", s): return None
        st = self.lights()
        if not st: return self._no_lights()
        cur = int(st.get("brightness", 60) or 0)
        m = re.search(r"\bto (\d{1,3})\s*%?", s); d = re.search(r"\bby (\d{1,3})\s*%?", s)
        if m: new = int(m.group(1))
        else:
            big = bool(re.search(r"\ba lot\b|\bway\b|\bmuch\b", s))           # "a bit": a quarter of what it is now (at least 5); "a lot": half (at least 15)
            k = int(d.group(1)) if d else max(15, round(cur * .5)) if big else max(5, round(cur * .25))
            down = bool(re.search(r"\b(?:lower|dim|decrease|reduce|down|less)\b", s))
            new = cur - k if down else cur + k
        new = max(1 if new > 0 or cur > 0 else 0, min(100, new))
        return ("lights", [call(f"Set the light's brightness to {new}% (it's {cur}%)", "POST", "/fan", {"brightness": new}, f"Brightness {cur}% → {new}%")])
    def lights_onoff(self, s):
        m = re.search(r"\bturn (on|off) (?:the |all )?(?:fan |rgb )?(?:lights?|leds?|lighting)\b|\b(?:lights?|leds?) (on|off)\b|\bturn (?:the )?(?:lights?|leds?) (on|off)\b", s)
        if not m: return None
        if not self.lights(): return self._no_lights()
        on = (m.group(1) or m.group(2) or m.group(3)) == "on"
        return ("lights", [call(f"Turn the lights {'on' if on else 'off'}", "POST", "/fan", {"on": on}, f"Lights {'on' if on else 'off'}")])
    def light_effect(self, s):
        if not re.search(r"\b(?:apply|set|use|switch|change|make|put|turn|show|start)\b", s): return None
        lightish = re.search(LIGHT_WORDS + r"|\beffect\b", s)
        st = self.lights()
        for p in (st.get("presets") or []) if st else []:           # your saved looks first
            if norm(p.get("name", "")) and norm(p["name"]) in norm(s):
                return ("lights", [call(f"Apply your “{p['name']}” lighting preset", "POST", "/fan", {"apply_preset": p["id"]}, f"Lighting: {p['name']}")])
        words = re.findall(r"[a-z]+", s)
        eff = next((w for w in words if w in EFFECTS), None) or next((EFFECT_WORDS[w] for w in words if w in EFFECT_WORDS), None)
        col = next((k for k in sorted(COLORS, key=len, reverse=True) if re.search(rf"\b{k}\b", s)), None)
        style = next((v for k, v in sorted(STYLES.items(), key=lambda x: -len(x[0])) if re.search(rf"\b{k}\b", s)), None)
        if style and not lightish and re.search(r"\b(?:theme|look|style)\b", s): return None      # that's Nova's look (ui)
        if not (eff or (col and lightish)): return None
        if eff and not lightish and not re.search(r"\b(?:theme|effect|mode)\b", s): return None
        if not st: return self._no_lights()
        body = {"on": True}
        if eff: body["effect"] = eff
        if col: body["color"] = COLORS[col]
        if eff == "fire" and not col: body["rainbow"] = True            # the warm flame (not a flame in one color)
        name = ((EFFECTS[eff] + " effect") if eff else "") + (f" in {col}" if col else "") if eff else col
        return ("lights", [call(f"Set the lights to {name.strip()}", "POST", "/fan", body, f"Lights: {name.strip()}")])

    # ── free up ──
    def freeup(self, s):
        if not re.search(r"\b(?:free(?: up)?|clean(?: up)?|clear(?: out)?|make (?:some )?room|reclaim)\b", s): return None
        disk = bool(re.search(r"\b(?:storage|space|disk|drive|room|gb|junk)\b", s)); ram = bool(re.search(r"\b(?:ram|memory)\b", s))
        if not (disk or ram): disk = True
        out = []
        if disk: out.append({"kind": "freeup", "say": "Free up storage: old images, package downloads, old logs and Nova's leftovers (the safe ones Free up picks itself)",
                             "call": {"method": "POST", "path": "/api/v1/freeup", "body": None}, "done": "Freed {freed} of storage"})
        if ram: out.append(call("Free up memory the system can let go of", "POST", "/actions/free-ram", None, "Freed {freed_mb} MB of RAM"))
        return ("freeup", out)

    # ── apps and containers ──
    def install(self, s):
        m = re.search(r"\b(?:install|set up|add|get)\s+(?:me\s+)?(?:the\s+|a\s+|an\s+)?(.+?)(?:\s+(?:docker\s+)?(?:container|app|image|server))?(?:\s+for me)?$", s)
        if not m or re.search(r"\bupdates?\b", s): return None
        want = re.sub(r"\b(?:docker|container|app|image|from the store|on (?:the )?server)\b", "", m.group(1)).strip()
        if not want or len(want) > 60: return None
        key = norm(want)
        items = self.store()
        def score(x): return max(difflib.SequenceMatcher(None, key, norm(x.get("id", ""))).ratio(), difflib.SequenceMatcher(None, key, norm(x.get("name", ""))).ratio(),
                                 .95 if key and (key in norm(x.get("name", "")) or key == norm(x.get("id", ""))) else 0)
        ranked = sorted(((score(x), x) for x in items), key=lambda t: -t[0])
        good = [x for sc, x in ranked if sc >= .8]
        step_for = lambda x: call(f"Install {x['name']} from the app store", "POST", f"/store/{x['id']}/install", None, f"{x['name']} installed", kind="task")
        if good and (len(good) == 1 or score(good[0]) >= .95):
            x = good[0]
            if x.get("installed"): return ("install", [{"kind": "note", "say": f"{x['name']} is already installed."}])
            return ("install", [step_for(x)])
        if len(good) > 1:
            return ("install", [ask(f"Install {want}", f"Which one did you mean?", [{"label": x["name"], "sub": x.get("description", ""), "step": step_for(x)} for x in good[:5]])])
        # not in Nova's store: Docker Hub (the same search the store does) — open it in "Your own container" to finish
        hub = []
        try: hub = self.env.get("hub", lambda q: [])(want) or []
        except Exception: hub = []
        close = [h for h in hub if key and key in norm(h.get("image", ""))][:6] or hub[:4]
        if not close: return None if self.env.get("brain") else ("install", [{"kind": "note", "say": f"I couldn't find “{want}” in the app store or on Docker Hub."}])
        opts = [{"label": h["image"], "sub": (("Official · " if h.get("official") else "") + f"{h.get('stars', 0)} stars" + (f" · {h['description'][:90]}" if h.get("description") else "")),
                 "step": {"kind": "nav", "say": f"Open “Your own container” with {h['image']} — add its ports and folders, then install (needs your fingerprint)",
                          "route": "container-new", "arg": h["image"], "done": f"{h['image']} is ready to finish in Your own container"}} for h in close]
        return ("install", [ask(f"Install {want}", f"I found {'these' if len(opts) > 1 else 'this'} {want} container{'s' if len(opts) > 1 else ''} on Docker Hub — which would you like?", opts)])

    def container_action(self, s):
        m = re.search(r"\b(restart|stop|start)\s+(?:the\s+)?([a-z0-9][a-z0-9_.-]{0,62})(?:\s+container)?\b", s)
        if not m or m.group(2) in ("server", "computer", "machine", "nova", "system", "it", "everything"): return None
        names = [c.get("name", "") for c in self.containers()]
        n = m.group(2); hit = next((x for x in names if x == n), None) or next((x for x in names if norm(x) == norm(n)), None)
        if not hit:
            close = [x for x in names if x.startswith(n) or norm(x).startswith(norm(n))] or difflib.get_close_matches(n, names, n=4, cutoff=.7)
            if not close: return None if not re.search(r"\bcontainer\b", s) else ("container", [{"kind": "note", "say": f"There's no container called “{n}”."}])
            if len(close) > 1:
                return ("container", [ask(f"{m.group(1).title()} {n}", "Which container?", [{"label": x, "step": call(f"{m.group(1).title()} {x}", "POST", f"/containers/{x}/{m.group(1)}", None, f"{x}: {m.group(1)}ed".replace("stoped", "stopped"))} for x in close])])
            hit = close[0]
        v = m.group(1)
        return ("container", [call(f"{v.title()} the {hit} container", "POST", f"/containers/{hit}/{v}", None, f"{hit} {'stopped' if v == 'stop' else v + 'ed'}")])

    def backup(self, s):
        if not re.search(r"\bback ?up (?:now|everything|my (?:stuff|files|server))\b|\brun (?:the |my )?backups?\b|\bbackups? now\b|\bstart (?:a |the )?backups?\b", s): return None
        return ("backup", [call("Run your backups now", "POST", "/actions/backup", None, "Backups started")])
    def updates(self, s):
        if not re.search(r"\b(?:check for|look for|install|get|apply) (?:the |any )?updates?\b|\bupdate (?:everything|all|the server)\b", s): return None
        return ("updates", [{"kind": "nav", "say": "Open Updates (pick what to update there)", "route": "updates", "done": "Updates open"}])

    # ── power ──
    def schedule(self, s):
        if not re.search(r"\b(?:reboot|restart|shut ?down|power off|turn off)\b", s): return None
        if re.search(r"\bcancel\b", s): return ("power", [{"kind": "nav", "say": "Open the power menu to cancel a scheduled restart", "route": "power", "done": "Power menu open"}])
        try: when = parse_when(s, self.now)
        except ValueError as e: return ("power", [{"kind": "note", "say": f"I couldn't schedule that: {e}."}])
        if when is None: return None
        if re.search(r"\bcontainer\b", s) or any(re.search(rf"\b{re.escape(c.get('name', ''))}\b", s) for c in self.containers() if c.get("name")):
            return ("power", [{"kind": "note", "say": "I can only schedule restarting the whole server for now, not one container."}])
        if when <= self.now + datetime.timedelta(minutes=1): return ("power", [{"kind": "note", "say": "That time has already passed."}])
        if when > self.now + datetime.timedelta(days=366): return ("power", [{"kind": "note", "say": "That's more than a year away."}])
        act = "poweroff" if re.search(r"\bshut ?down\b|\bpower off\b|\bturn off\b", s) else "reboot"
        return ("power", [call(f"Schedule a server {'restart' if act == 'reboot' else 'shutdown'} for {when_text(when)}", "POST", "/power/schedule",
                               {"action": act, "at": int(when.timestamp())}, f"{'Restart' if act == 'reboot' else 'Shutdown'} scheduled for {when_text(when)}")])
    def power_now(self, s):
        m = re.search(r"\b(reboot|restart|shut ?down|power off)\b(?:\s+(?:the\s+)?(?:server|computer|machine|system|now))?", s)
        if not m or re.search(r"\b(?:nova|container|app)\b", s): return None
        if not re.search(r"\b(?:server|computer|machine|system|now)\b", s) and m.group(1) not in ("reboot",): return None
        act = "poweroff" if re.search(r"shut|power off", m.group(1)) else "reboot"
        return ("power", [call(f"{'Restart' if act == 'reboot' else 'Shut down'} the server now", "POST", f"/power/{act}", None, f"Server {'restarting' if act == 'reboot' else 'shutting down'}", danger=True)])

    # ── drives: the plan goes to the Partition manager, never straight to the drive ──
    def drive(self, s):
        if not re.search(r"\b(?:erase|wipe|format|reformat|repartition|partition)\b", s): return None
        if not re.search(r"\b(?:drive|disk|ssd|hdd|hard drive|\d+\s*(?:tb|gb))\b", s): return None
        size = re.search(r"\b(\d+(?:\.\d+)?)\s*(tb|gb)\b", s)
        fs = next((v for k, v in FS.items() if re.search(rf"\b{k}\b", s)), "ext4")
        # partitions: "33% called root mounted at /mnt/root", "the other remaining 67% called home at /mnt/home"
        parts = [{"pct": float(p[0]), "label": p[1] or "", "mount": p[2] or ""} for p in
                 re.findall(r"(\d{1,3}(?:\.\d+)?)\s*%\s*(?:of it\s*)?(?:(?:called|named|labell?ed)\s+([a-z0-9_-]{1,16}))?(?:\s*(?:,\s*)?(?:and\s+)?(?:mounted\s+)?(?:at|on)\s+(/mnt/[a-z0-9_/-]{1,40}))?", s)]
        if not parts: parts = [{"pct": 100.0, "label": "", "mount": ""}]
        if sum(p["pct"] for p in parts) > 100.5: return ("drive", [{"kind": "note", "say": "Those partitions add up to more than the whole drive."}])
        words = [w for w in re.findall(r"[a-z0-9]+", s) if w not in ("erase", "wipe", "format", "reformat", "the", "drive", "disk", "and", "it", "to", "a", "split", "partition", "setup",
                 "create", "called", "mounted", "at", "other", "remaining", "of", "on", "named", "with", "into", "two", "partitions", "tb", "gb", fs) and not w.isdigit()]
        cands = []
        for d in self.drives():
            if d.get("system"): continue
            sc = 0
            if size:
                want = float(size.group(1)) * (1e12 if size.group(2) == "tb" else 1e9)
                if abs(d.get("size", 0) - want) / want > .12: continue
                sc += 1
            model = (d.get("model", "") + " " + d.get("vendor", "")).lower()
            sc += sum(1 for w in words if len(w) > 2 and w in model)
            if sc > 0: cands.append((sc, d))
        if not cands: return ("drive", [{"kind": "note", "say": "I couldn't tell which drive you mean — none matches (Nova never changes the system drive)."}])
        best = max(sc for sc, _ in cands); top = [d for sc, d in cands if sc == best]
        def step_for(d):
            mounts = [f"{p['label'] or 'part ' + str(i + 1)} at {p['mount']}" for i, p in enumerate(parts) if p["mount"]]
            say = (f"Erase {d.get('model', 'the drive')} ({d.get('size_text', '')}, serial {d.get('serial', '?')}) and make "
                   + " + ".join(f"{p['pct']:g}% {fs}" + (f" “{p['label']}”" if p['label'] else "") for p in parts))
            return {"kind": "partition", "say": say, "serial": d.get("serial", ""), "fs": fs, "parts": parts, "danger": True,
                    "route": "partitions", "done": "The plan is waiting in the Partition manager — check it, type the serial and approve to apply",
                    "after": (f"Then set the mount points ({'; '.join(mounts)}) from each partition's Mount point." if mounts else "")}
        if len(top) == 1: return ("drive", [step_for(top[0])])
        return ("drive", [ask("Pick the drive", f"{len(top)} drives match — which one should be erased? Everything on it will be gone.",
                              [{"label": f"{d.get('model', 'Drive')} · {d.get('size_text', '')}", "sub": f"serial {d.get('serial', '?')}" + (f" · mounted at {', '.join(d['mounts'][:2])}" if d.get("mounts") else ""), "step": step_for(d)} for d in top])])

    # ── Nova's own look (this device only: the app or browser sets it) ──
    def ui(self, s):
        U = lambda say, pref, value, done: ("ui", [{"kind": "ui", "say": say, "pref": pref, "value": value, "done": done}])
        off = bool(re.search(r"\b(?:off|disable|stop|hide|no)\b", s))
        # the living (animated) wallpaper: on, off, or a scene
        if re.search(r"\b(?:live|living|animated|moving)\s+(?:wall ?paper|background|scene)\b", s):
            if off and not re.search(r"\bto\b", s): return U("Turn off the living wallpaper", "living", "off", "Living wallpaper off")
            sc = next((v for k, v in sorted(SCENES.items(), key=lambda x: -len(x[0])) if re.search(rf"\b{k}\b", s)), None)
            return U(f"Set the living wallpaper to {SCENE_NAMES[sc]}" if sc else "Turn on the living wallpaper", "living", sc or "auto", "Living wallpaper set")
        # the accent color (this device's own)
        if re.search(r"\b(?:accent|highlight|main) colou?r\b|\baccent\b", s) and not re.search(LIGHT_WORDS, s):
            if re.search(r"\b(?:reset|default|server'?s?)\b", s): return U("Use the server's accent color", "accent", "", "Accent color reset")
            hx = re.search(r"#[0-9a-f]{6}\b", s)
            col = hx.group(0) if hx else next((v for k, v in sorted(COLORS.items(), key=lambda x: -len(x[0])) if re.search(rf"\b{k}\b", s)), None)
            if col: return U(f"Make the accent color {next((k for k, v in COLORS.items() if v == col), col)}", "accent", col, "Accent color changed")
        # roundness: windows (and dialogs), or the look's corners
        m = re.search(r"\b(window|dialog|popup|corner|card)s?\s*(?:radius|roundness|rounding|corners?)?\b.*?\b(\d{1,3})\s*%", s) or re.search(r"\b(\d{1,3})\s*%.*\b(window|dialog|corner)s?\b", s)
        if m and re.search(r"\b(?:radius|round|roundness|rounding|corners?)\b", s):
            g = m.groups(); kind, n = (g[0], int(g[1])) if g[0].isalpha() else (g[1], int(g[0]))
            if kind in ("window", "dialog", "popup"): return U(f"Set window roundness to {max(0, min(300, n))}%", "win_round", max(0, min(300, n)), "Window roundness set")
            return U(f"Set corner roundness to {max(25, min(200, n))}%", "corner_round", max(25, min(200, n)), "Corner roundness set")
        # developer options: the frame rate meter
        if re.search(r"\b(?:frame ?rate|fps)\b", s) and re.search(r"\b(?:show|enable|turn on|display|viewer|meter|counter|overlay|hide|disable|turn off|off)\b", s):
            return U("Hide the frame rate meter" if off else "Show the frame rate meter (Developer options)", "fps", not off, "Frame rate meter " + ("off" if off else "on"))
        if re.search(r"\breduce transparency\b|\bsolid (?:surfaces|look)\b", s): return U("Turn " + ("off" if off else "on") + " Reduce transparency", "solid", not off, "Reduce transparency set")
        if re.search(r"\b(?:read|speak|say)\b.*\b(?:replies|answers|aloud|out loud)\b", s): return U(("Stop reading" if off else "Read") + " my replies aloud", "speak", not off, "Done")
        if re.search(r"\bdark (?:mode|theme)\b", s) and not re.search(LIGHT_WORDS, s): return ("ui", [{"kind": "ui", "say": "Switch Nova to dark", "pref": "theme", "value": "dark", "done": "Dark mode on"}])
        if re.search(r"\blight (?:mode|theme)\b", s) and not re.search(r"\b(?:lights|leds?|rgb|fans?)\b", s) and not any(re.search(rf"\b{e}\b", s) for e in EFFECTS):
            return ("ui", [{"kind": "ui", "say": "Switch Nova to light", "pref": "theme", "value": "light", "done": "Light mode on"}])
        if re.search(r"\breduce motion\b", s): return ("ui", [{"kind": "ui", "say": "Turn on Reduce motion", "pref": "reduce_motion", "value": not re.search(r"\b(?:off|disable)\b", s), "done": "Reduce motion set"}])
        if re.search(r"\b(?:theme|look|style)\b", s) and not re.search(LIGHT_WORDS, s):
            st = next((v for k, v in sorted(STYLES.items(), key=lambda x: -len(x[0])) if re.search(rf"\b{k}\b", s)), None)
            if st: return ("ui", [{"kind": "ui", "say": f"Switch Nova's look to {STYLE_NAMES[st]}", "pref": "style", "value": st, "done": "Look changed"}])
        return None

    def navigate(self, s):
        m = re.search(r"\b(?:open|show(?: me)?|go to|take me to|bring up)\s+(?:the\s+|my\s+)?(.+)$", s)
        if not m: return None
        k = m.group(1).strip()
        hit = next((v for n, v in sorted(SCREENS.items(), key=lambda x: -len(x[0])) if re.search(rf"\b{n}\b", k)), None)
        if not hit: return None
        return ("nav", [{"kind": "nav", "say": f"Open {k}", "route": hit, "done": f"{k.capitalize()} open"}])


def plan(text, env, now=None):
    text = "".join(ch for ch in str(text) if ch.isprintable())[:MAX_TEXT].strip()
    if not text: raise ValueError("say what you'd like Nova to do")
    return Planner(env, now).run(text)
