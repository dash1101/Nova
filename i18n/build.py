#!/usr/bin/env python3
"""Copy the translations where each part of Nova reads them:
   server/web/i18n-<lang>.json            (Nova web: what web and the site use)
   android/app/src/main/assets/i18n/<lang>.json, android/wear/src/main/assets/i18n/<lang>.json"""
import json, os
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
cat = {x["en"]: set(x["in"]) for x in json.load(open(f"{ROOT}/i18n/catalog.json"))}
for f in sorted(os.listdir(f"{ROOT}/i18n/tr")):
    lang = f[:-5]; tr = json.load(open(f"{ROOT}/i18n/tr/{f}"))
    for where, path in (({"web"}, f"{ROOT}/server/web/i18n-{lang}.json"), ({"app"}, f"{ROOT}/android/app/src/main/assets/i18n/{lang}.json"),
                        ({"wear"}, f"{ROOT}/android/wear/src/main/assets/i18n/{lang}.json"), ({"site"}, f"{ROOT}/site/i18n-{lang}.json")):
        part = {k: v for k, v in tr.items() if cat.get(k, set()) & where}
        os.makedirs(os.path.dirname(path), exist_ok=True)
        json.dump(part, open(path, "w"), ensure_ascii=False, separators=(",", ":"))
    print(lang, len(tr))
