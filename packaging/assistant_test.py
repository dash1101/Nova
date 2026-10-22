#!/usr/bin/env python3
"""Tests for the assistant's planner (server/api/assistant.py), on made-up server data — nothing runs.
   python3 packaging/assistant_test.py"""
import datetime, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "server", "api"))
import assistant as A

NOW = datetime.datetime(2026, 10, 21, 14, 0)        # a Wednesday
ENV = {
    "lights": lambda: {"on": True, "effect": "static", "brightness": 60, "status_light": True, "presets": [{"id": "p1", "name": "Movie night"}]},
    "containers": lambda: [{"name": "jellyfin"}, {"name": "homeassistant"}, {"name": "immich-server"}, {"name": "immich-ml"}],
    "store": lambda: [{"id": "jellyfin", "name": "Jellyfin", "installed": True}, {"id": "vaultwarden", "name": "Vaultwarden", "installed": False},
                      {"id": "uptime-kuma", "name": "Uptime Kuma", "installed": False}, {"id": "radarr", "name": "Radarr"}, {"id": "sonarr", "name": "Sonarr"}],
    "hub": lambda q: [{"image": "linuxserver/brave", "stars": 40, "official": False, "description": "Brave in a browser"},
                      {"image": "kasmweb/brave", "stars": 30, "official": False}] if "brave" in q else [],
    "drives": lambda: [{"serial": "SYS1", "model": "Samsung SSD 980", "size": 1e12, "system": True},
                       {"serial": "FAKE-ST1", "model": "ST1000NM0033 Seagate Constellation", "size": 1.0e12, "size_text": "1 TB", "mounts": ["/mnt/a"]},
                       {"serial": "FAKE-ST2", "model": "ST1000NM0033 Seagate Constellation", "size": 1.0e12, "size_text": "1 TB"},
                       {"serial": "FAKE-WD", "model": "WDC WD40EFRX", "size": 4e12, "size_text": "4 TB"}],
}
fails = 0
def check(name, ok, got=None):
    global fails
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"   → {got}"))
    if not ok: fails += 1
def P(text, env=ENV): return A.plan(text, env, NOW)
paths = lambda p: [(s.get("call") or {}).get("path") or s["kind"] for s in p["steps"]]

# the example, word for word
p = P("Hey, Nova. Apply the Fire light theme, free up some storage and ram, disable the status lights, lower the brightness of the fan a bit, "
      "install the brave docker container, and while you're at it, can you schedule me a planned reboot at 5pm a week from now?")
k = [s["kind"] for s in p["steps"]]
check("example: nothing left over", p["unknown"] == [], p["unknown"])
check("example: six kinds of step in order", k == ["call", "freeup", "call", "call", "call", "ask", "call"], k)
s = p["steps"]
check("example: Fire effect (the warm flame)", s[0]["call"]["body"] == {"on": True, "effect": "fire", "rainbow": True}, s[0]["call"]["body"])
check("example: free up storage then RAM", s[1]["kind"] == "freeup" and s[2]["call"]["path"] == "/api/v1/actions/free-ram", paths(p))
check("example: status light off", s[3]["call"]["body"] == {"status_light": False}, s[3])
check("example: brightness 60 → 45", s[4]["call"]["body"] == {"brightness": 45}, s[4])
check("example: Brave → a question with Docker Hub choices", s[5]["kind"] == "ask" and [o["label"] for o in s[5]["options"]] == ["linuxserver/brave", "kasmweb/brave"], s[5])
check("example: …each choice opens Your own container, never installs blind", all(o["step"]["kind"] == "nav" and o["step"]["route"] == "container-new" for o in s[5]["options"]))
want = int(datetime.datetime(2026, 10, 28, 17, 0).timestamp())
check("example: reboot at 5pm, a week from now", s[6]["call"]["path"] == "/api/v1/power/schedule" and s[6]["call"]["body"] == {"action": "reboot", "at": want}, s[6]["call"])
check("example: steps have ids", [x["id"] for x in s] == [f"s{i}" for i in range(1, 8)])

# the drive example: two 1 TB Seagates → ask; the system drive is never offered
p = P("Erase the 1TB Seagate Constellation drive, reformat it to NTFS, and create a split partition setup 33% called root mounted at /mnt/root and the other remaining 67% called home at /mnt/home")
st = p["steps"]
check("drive: one step, a question", len(st) == 1 and st[0]["kind"] == "ask", st)
check("drive: both Seagates offered, nothing else", [o["step"]["serial"] for o in st[0]["options"]] == ["FAKE-ST1", "FAKE-ST2"], st)
o = st[0]["options"][0]["step"]
check("drive: ntfs, 33% root /mnt/root + 67% home /mnt/home", o["fs"] == "ntfs" and o["parts"] == [{"pct": 33.0, "label": "root", "mount": "/mnt/root"}, {"pct": 67.0, "label": "home", "mount": "/mnt/home"}], o)
check("drive: handed to the Partition manager (danger)", o["kind"] == "partition" and o["route"] == "partitions" and o["danger"], o)
p = P("wipe the 4tb wd drive and format it ext4")
check("drive: one match → straight to the plan", p["steps"][0]["kind"] == "partition" and p["steps"][0]["serial"] == "FAKE-WD", p["steps"])
check("drive: the system drive is never matched", P("format the samsung 1tb drive")["steps"][0]["kind"] == "ask" or all(s.get("serial") != "SYS1" for s in P("format the samsung 980 drive")["steps"]))
check("drive: partitions over 100% refused", P("erase the 4tb drive 60% called a and 50% called b")["notes"] != [])

# times
W = lambda t: A.parse_when(t, NOW)
check("time: at 5pm (later today)", W("at 5pm") == datetime.datetime(2026, 10, 21, 17, 0))
check("time: at 9am (passed → tomorrow)", W("at 9am") == datetime.datetime(2026, 10, 22, 9, 0))
check("time: tomorrow at 17:30", W("tomorrow at 17:30") == datetime.datetime(2026, 10, 22, 17, 30))
check("time: friday at noon", W("on friday at noon") == datetime.datetime(2026, 10, 23, 12, 0))
check("time: next wednesday 8pm", W("next wednesday at 8pm") == datetime.datetime(2026, 10, 28, 20, 0))
check("time: in 2 hours", W("in 2 hours") == datetime.datetime(2026, 10, 21, 16, 0))
check("time: oct 30 at 3am", W("on oct 30 at 3am") == datetime.datetime(2026, 10, 30, 3, 0))
check("time: no time at all → none", W("schedule a reboot") is None)
try: W("at 25:00"); check("time: 25:00 refused", False)
except ValueError: check("time: 25:00 refused", True)
check("schedule: past time refused", P("schedule a reboot in 0 minutes")["notes"] != [], P("schedule a reboot in 0 minutes"))
check("schedule: shutdown", P("schedule a shutdown tomorrow at 1am")["steps"][0]["call"]["body"]["action"] == "poweroff")
check("schedule: a container isn't scheduled", P("restart jellyfin tomorrow at 5pm")["notes"] != [], P("restart jellyfin tomorrow at 5pm"))

# lights
check("lights: preset by name", P("set the lights to movie night")["steps"][0]["call"]["body"] == {"apply_preset": "p1"})
check("lights: color", P("make the lights red")["steps"][0]["call"]["body"] == {"on": True, "color": "#ff2a1a"})
check("lights: off", P("turn off the lights")["steps"][0]["call"]["body"] == {"on": False})
check("lights: brightness to 30%", P("set the brightness of the lights to 30%")["steps"][0]["call"]["body"] == {"brightness": 30})
check("lights: brighter a lot", P("raise the fan brightness a lot")["steps"][0]["call"]["body"] == {"brightness": 90})
check("lights: none set up → a note, no call", P("turn off the status light", {**ENV, "lights": lambda: None})["steps"] == [])

# apps, containers
check("store: one match installs", paths(P("install vaultwarden")) == ["/api/v1/store/vaultwarden/install"])
check("store: already installed → note", P("install jellyfin")["notes"] != [] and P("install jellyfin")["steps"] == [])
check("store: fuzzy (uptime kuma)", paths(P("install uptime kuma")) == ["/api/v1/store/uptime-kuma/install"])
check("store: unknown everywhere → note", P("install frobnicator9000")["notes"] != [])
check("container: restart a real one", paths(P("restart jellyfin")) == ["/api/v1/containers/jellyfin/restart"])
check("container: ambiguous → ask", P("stop the immich container")["steps"][0]["kind"] == "ask", P("stop the immich container"))
check("container: a made-up name never reaches a path", all("evil" not in str(s) for s in P("restart evil;rm -rf container")["steps"]))
check("free up: just RAM", paths(P("free up some memory")) == ["/api/v1/actions/free-ram"])
check("backup now", paths(P("back up now")) == ["/api/v1/actions/backup"])
check("power: reboot now is marked danger", P("reboot the server")["steps"][0].get("danger") is True)

# Nova's own look vs the lights
check("ui: aero theme is Nova's look", P("apply the aero theme")["steps"][0] == {**P("apply the aero theme")["steps"][0], "kind": "ui", "pref": "style", "value": "aero"})
check("ui: dark mode", P("switch to dark mode")["steps"][0]["value"] == "dark")
check("nav: open containers", P("open containers")["steps"][0]["route"] == "containers")
check("unknown is reported, not guessed", P("bake me a cake")["unknown"] == ["bake me a cake"])
check("long text is cut, odd characters dropped", len(A.plan("x" * 5000 + "‮", ENV, NOW)["text"]) <= A.MAX_TEXT)
try: A.plan("   ", ENV, NOW); check("empty refused", False)
except ValueError: check("empty refused", True)
# the brain: only used for what the rules didn't get (or a question), and its commands go through the rules again
calls = []
def fake(text):
    calls.append(text)
    return {"commands": ["set the lights to pulse", "make the lights blue", "format the drive", "rm -rf /"], "reply": "Blue pulse, like a police car."}
BE = {**ENV, "brain": fake}
p = A.plan("make the lights look like a police car", BE, NOW)
check("brain: used when the rules don't understand", len(calls) == 1 and p.get("brain") and p["reply"].startswith("Blue"), p)
check("brain: its commands go through the rules (made-up ones are dropped)", [s_["call"]["body"] for s_ in p["steps"]] == [{"on": True, "effect": "pulse"}, {"on": True, "color": "#2f7dff"}], p["steps"])
calls.clear(); A.plan("turn off the lights", BE, NOW)
check("brain: not asked when the rules understood it all", calls == [])
p = A.plan("is my disk ok?", {**ENV, "brain": lambda t: {"commands": [], "reply": "Yes — 32 GB free."}}, NOW)
check("brain: a question gets an answer and no steps", p["steps"] == [] and p["reply"] == "Yes — 32 GB free." and p["unknown"] == [], p)
p = A.plan("bake me a cake", {**ENV, "brain": lambda t: None}, NOW)
check("brain: not running → the rules' plan", p["unknown"] == ["bake me a cake"] and "reply" not in p)

# Nova's look, more of it (the request that used to half-fail)
p = P("set the live wallpaper to aero, set the theme to glass, set the accent color to orange, set window radius to 20%, enable the frame rate viewer from developer options, set the led theme to fire, and check for updates")
check("look: nothing left over", p["unknown"] == [], p["unknown"])
got = [(x.get("pref"), x.get("value")) for x in p["steps"] if x["kind"] == "ui"]
check("look: living wallpaper, style, accent, window roundness, fps meter", got == [("living", "aero"), ("style", "liquid"), ("accent", "#ff7a00"), ("win_round", 20), ("fps", True)], got)
check("look: the lights still go to the lights", any((x.get("call") or {}).get("body") == {"on": True, "effect": "fire", "rainbow": True} for x in p["steps"]), p["steps"])
check("look: lumen", P("apply the lumen theme")["steps"][0]["value"] == "lumen")
check("look: living wallpaper off", P("turn off the living wallpaper")["steps"][0]["value"] == "off")
check("look: corner roundness clamped", P("set corner roundness to 900%")["steps"][0]["value"] == 200)
check("look: the lights' color isn't the accent", P("make the lights orange")["steps"][0]["kind"] == "call")
check("look: “disable the orb for the ai assistant” hides the assistant button", [(x.get("pref"), x.get("value")) for x in P("disable the orb for the ai assistant")["steps"]] == [("asst_button", False)], P("disable the orb for the ai assistant"))
check("look: the status light is still the status light", P("turn off the status light")["steps"][0]["kind"] == "call")
# small talk without the brain: a friendly answer, not "I didn't understand"
for t in ("Yo, whats up?", "hey nova", "thanks!", "how are you?"):
    q_ = P(t); check(f"chat: “{t}” gets a reply", q_["steps"] == [] and q_["unknown"] == [] and q_.get("reply"), q_)
# the brain fills in what the rules missed, and the rules' steps stay
p = A.plan("turn off the lights and make it feel like a police car", {**ENV, "brain": lambda t: {"commands": ["set the lights to pulse", "make the lights blue"], "reply": "Police lights!"}}, NOW)
check("brain: adds to the rules' steps", len(p["steps"]) == 3 and p["unknown"] == [], p)
p = A.plan("yo whats up", {**ENV, "brain": lambda t: {"commands": [], "reply": "Not much! Your server's chilling."}}, NOW)
check("brain: small talk is the brain's", p["reply"].startswith("Not much") and p["unknown"] == [], p)

print(f"\n{'all passed' if not fails else f'{fails} failed'}")
sys.exit(1 if fails else 0)
