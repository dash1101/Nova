#!/usr/bin/env python3
"""
Build the English catalog (i18n/catalog.json) from the source: every bit of user-facing text in Nova web
(server/web/*.js), the Android and Wear apps (*.kt) and the website (site/body.html).

Text with values in it becomes a pattern: `Freed ${mb} MB` → "Freed {0} MB". At runtime a shown text is
looked up as-is first, then against the patterns (see server/web/i18n.js and the app's I18n.kt).
Run after changing UI text:   python3 i18n/extract.py   (then translate the new entries; see i18n/README.md)
"""
import glob, html, json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP = re.compile(r"^(/|https?:|#/|#[\w-]+$|\[[\w-]|[a-z0-9_.:-]+$|[A-Z0-9_]+$|\W+$|.*\.(js|css|svg|png|json|kt)$|M\d|data:)")

CODE = re.compile(r"\)\?|!!|\?\.|esc\(|\bopt(String|Int|Double|Long|Boolean)|^[,)&(%#:;+]|\(\?i\)|-----|prefers-|pointer:|min-width|\$\{|\b(click|blue|end|sub):|\belse\b|\bnull\b")
def keep(s):
    s = s.strip()
    if CODE.search(s) and not s.startswith("+ "): return False
    if len(s) < 2 or SKIP.match(s): return False
    if not re.search(r"[A-Za-z]{2}", s): return False
    if re.fullmatch(r"[a-z]+[A-Z]\w*", s): return False                  # camelCase identifiers
    if re.search(r"[{};=]|=>|\(\)|\bvar\(|\bcalc\(|rgba?\(|\.[a-z]+\(", s) and not re.search(r"\{\d\}", s): return False
    if re.fullmatch(r"[\w-]+(\s+[\w-]+)*", s) and re.fullmatch(r"[a-z0-9 -]+", s) and len(s.split()) > 3: return False   # css class lists / keyword lists
    return True

def norm(s): return re.sub(r"\s+", " ", html.unescape(s)).strip()

def js_fragments(src):
    out = set()
    # template literals: split on tags, turn ${…} into {n}
    for m in re.finditer(r"`((?:[^`\\]|\\.)*)`", src, re.S):
        body = m.group(1)
        # replace nested ${...} (balanced braces) by markers
        parts, depth, buf, i = [], 0, "", 0
        out_s, n = "", 0
        while i < len(body):
            if body.startswith("${", i):
                j, d = i + 2, 1
                while j < len(body) and d:
                    d += {"{": 1, "}": -1}.get(body[j], 0); j += 1
                out_s += "\x00"; i = j; continue
            out_s += body[i]; i += 1
        for frag in re.split(r"<[^>]*>", out_s):
            k = 0
            def ph(_):
                nonlocal k; k += 1; return "{%d}" % (k - 1)
            t = norm(re.sub("\x00", ph, frag))
            if keep(re.sub(r"\{\d\}", "N", t)) and len(re.sub(r"\{\d\}", "", t).strip()) > 1: out.add(t)
    for m in re.finditer(r'"((?:[^"\\\n]|\\.)*)"', src):
        s = m.group(1).replace('\\"', '"').replace("\\n", "\n")
        for frag in re.split(r"<[^>]*>", s):
            t = norm(frag)
            if keep(t): out.add(t)
    return out

def kt_fragments(src):
    out = set()
    for m in re.finditer(r'"((?:[^"\\\n]|\\.)*)"', src):
        s = m.group(1)
        if "\\n" in s: s = s.replace("\\n", "\n")
        k = 0
        def ph(_):
            nonlocal k; k += 1; return "{%d}" % (k - 1)
        # ${…} (balanced) and $name → {n}
        t = re.sub(r"\$\{(?:[^{}]|\{[^{}]*\})*\}|\$[a-zA-Z_][a-zA-Z0-9_.]*(?:\(\))?", ph, s)
        t = norm(t.replace('\\"', '"'))
        if keep(re.sub(r"\{\d\}", "N", t)): out.add(t)
    return out

def site_fragments(src):
    out = set()
    body = re.sub(r"<(script|style)[\s\S]*?</\1>", "", src)
    for frag in re.split(r"<[^>]*>", body):
        t = norm(frag)
        if keep(t): out.add(t)
    for m in re.finditer(r'(?:aria-label|title|placeholder)="([^"]+)"', body): out.add(norm(m.group(1)))
    return out

def main():
    cat = {}
    def add(where, frags):
        for f in frags: cat.setdefault(f, set()).add(where)
    for f in glob.glob(f"{ROOT}/server/web/*.js"): add("web", js_fragments(open(f).read()))
    for f in glob.glob(f"{ROOT}/android/app/src/main/java/**/*.kt", recursive=True): add("app", kt_fragments(open(f).read()))
    for f in glob.glob(f"{ROOT}/android/wear/src/main/java/**/*.kt", recursive=True): add("wear", kt_fragments(open(f).read()))
    add("site", site_fragments(open(f"{ROOT}/site/body.html").read()))
    items = sorted(({"en": k, "in": sorted(v)} for k, v in cat.items()), key=lambda x: x["en"].lower())
    json.dump(items, open(f"{ROOT}/i18n/catalog.json", "w"), ensure_ascii=False, indent=0)
    print(len(items), "strings:", {w: sum(1 for i in items if w in i["in"]) for w in ("web", "app", "wear", "site")})

if __name__ == "__main__": main()
