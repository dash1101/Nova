#!/usr/bin/env python3
"""Merge work/out_v*.tsv batches keyed by work/todo3.tsv (index → English, fixed when the batch was made), so a
re-run of extract.py (which renumbers the catalog) can't misalign them. Checks the {0} placeholders."""
import glob, json, os, re
ROOT = os.path.dirname(os.path.abspath(__file__))
LANGS = ["es", "de", "fr", "pt", "it", "ru", "zh", "ja", "ko"]
key = {}
for l in open(f"{ROOT}/work/todo3.tsv", encoding="utf-8"):
    i, en = l.rstrip("\n").split("\t", 1); key[int(i)] = en
cat = {x["en"] for x in json.load(open(f"{ROOT}/catalog.json"))}
tr = {l: json.load(open(f"{ROOT}/tr/{l}.json")) for l in LANGS}
bad = n = 0
for f in sorted(glob.glob(f"{ROOT}/work/out_v*.tsv")):
    for line in open(f, encoding="utf-8"):
        c = line.rstrip("\n").split(" ¦ ")
        if len(c) < 2 or not c[0].strip().isdigit(): continue
        if len(c) != 1 + len(LANGS): print("wrong column count", f, line[:80]); bad += 1; continue
        en = key.get(int(c[0]))
        if en is None: print("unknown index", f, c[0]); bad += 1; continue
        if en not in cat: continue                       # the text changed since; skip
        ph = sorted(re.findall(r"\{\d\}", en))
        for l, v in zip(LANGS, c[1:]):
            v = v.strip()
            if not v: continue
            if sorted(re.findall(r"\{\d\}", v)) != ph: print(f"placeholders differ [{l}] {en!r} → {v!r}"); bad += 1; continue
            tr[l][en] = v
        n += 1
for l in LANGS: json.dump(dict(sorted(tr[l].items())), open(f"{ROOT}/tr/{l}.json", "w"), ensure_ascii=False, indent=0)
print(n, "strings merged |", {l: len(tr[l]) for l in LANGS}, "of", len(cat), "| problems:", bad)
