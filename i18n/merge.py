#!/usr/bin/env python3
"""Merge translated batches (work/out_*.jsonl: {"i": n, "es": "…", …}) into tr/<lang>.json (English → translation),
checking that every translation keeps the same {0}, {1}… placeholders as the English."""
import glob, json, os, re
ROOT = os.path.dirname(os.path.abspath(__file__))
LANGS = ["es", "de", "fr", "pt", "it", "ru", "zh", "ja", "ko"]
cat = json.load(open(f"{ROOT}/catalog.json"))
os.makedirs(f"{ROOT}/tr", exist_ok=True)
tr = {l: (json.load(open(f"{ROOT}/tr/{l}.json")) if os.path.exists(f"{ROOT}/tr/{l}.json") else {}) for l in LANGS}
bad = 0
for f in sorted(glob.glob(f"{ROOT}/work/out_*.jsonl")):
    for line in open(f):
        line = line.strip()
        if not line: continue
        try: d = json.loads(line)
        except ValueError: print("bad line", f, line[:80]); bad += 1; continue
        en = cat[d["i"]]["en"]; ph = sorted(re.findall(r"\{\d\}", en))
        for l in LANGS:
            v = d.get(l)
            if not v: continue
            if sorted(re.findall(r"\{\d\}", v)) != ph: print(f"placeholders differ [{l}] {en!r} → {v!r}"); bad += 1; continue
            tr[l][en] = v
# batches as TSV (work/out_*.tsv): index, then the nine languages in LANGS order, separated by tabs or " ¦ "
for f in sorted(glob.glob(f"{ROOT}/work/out_*.tsv")):
    for line in open(f, encoding="utf-8"):
        line = line.rstrip("\n"); c = line.split("\t") if "\t" in line else line.split(" ¦ ")
        if len(c) < 2 or not c[0].strip().isdigit(): continue
        if len(c) != 1 + len(LANGS): print("wrong column count", f, line[:80]); bad += 1; continue
        en = cat[int(c[0])]["en"]; ph = sorted(re.findall(r"\{\d\}", en))
        for l, v in zip(LANGS, c[1:]):
            v = v.strip()
            if not v: continue
            if sorted(re.findall(r"\{\d\}", v)) != ph: print(f"placeholders differ [{l}] {en!r} → {v!r}"); bad += 1; continue
            tr[l][en] = v
for l in LANGS: json.dump(dict(sorted(tr[l].items())), open(f"{ROOT}/tr/{l}.json", "w"), ensure_ascii=False, indent=0)
print({l: len(tr[l]) for l in LANGS}, "of", len(cat), "| problems:", bad)
