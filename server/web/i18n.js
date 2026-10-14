// Nova web — translations. The page is written in English; this swaps each piece of text for the chosen
// language as it appears (text, tooltips, labels, placeholders), using i18n-<lang>.json:
//   { "English text": "translation", "Freed {0} MB": "{0} MB liberados", … }
// Text with values in it is matched as a pattern ({0}, {1}…). Anything not in the file stays English.
// Text written by the server (alert titles, errors) stays English for now.
export const LANGS = [["en", "English"], ["es", "Español"], ["de", "Deutsch"], ["fr", "Français"], ["pt", "Português"], ["it", "Italiano"],
  ["ru", "Русский"], ["zh", "简体中文"], ["ja", "日本語"], ["ko", "한국어"]];
let exact = null, patterns = [], lang = "en";
const done = new WeakMap();                       // node → the text we put there (so our own change isn't translated again)

export function pickLang(pref) {
  const want = (pref && pref !== "auto" ? [pref] : navigator.languages || [navigator.language || "en"]).map(l => String(l).toLowerCase().split("-")[0]);
  return want.find(l => LANGS.some(x => x[0] === l)) || "en";
}
export const currentLang = () => lang;

export async function loadLang(pref) {
  lang = pickLang(pref);
  document.documentElement.lang = lang;
  if (lang === "en") { exact = null; patterns = []; return; }
  try {
    const d = await (await fetch(`/web/i18n-${lang}.json`, { cache: "no-cache" })).json();
    exact = new Map(); patterns = [];
    for (const [k, v] of Object.entries(d)) {
      if (!v) continue;
      if (/\{\d\}/.test(k)) {
        const names = []; const re = k.replace(/[.*+?^$()|[\]\\]/g, "\\$&").replace(/\\?\{(\d)\\?\}/g, (_, n) => { names.push(n); return "([\\s\\S]+?)"; });
        patterns.push([new RegExp("^" + re + "$"), v, names, k.length]);
      } else exact.set(k, v);
    }
    patterns.sort((a, b) => b[3] - a[3]);          // longer (more specific) patterns first
  } catch { exact = null; patterns = []; }
}

/** One piece of English text → the chosen language (the original if there's no translation). */
export function t(s, ...args) {
  let out = s;
  if (exact) {
    const key = String(s).replace(/\s+/g, " ").trim();
    const hit = exact.get(key);
    if (hit != null) out = hit;
    else for (const [re, v, names] of patterns) { const m = re.exec(key); if (m) { out = v.replace(/\{(\d)\}/g, (_, n) => m[names.indexOf(n) + 1] ?? ""); break; } }
  }
  return args.length ? out.replace(/\{(\d)\}/g, (_, n) => args[n] ?? "") : out;
}

const SKIP = new Set(["SCRIPT", "STYLE", "TEXTAREA", "CODE", "PRE", "INPUT"]);
function textNode(n) {
  const v = n.data; if (!v.trim() || done.get(n) === v) return;
  const p = n.parentElement; if (!p || SKIP.has(p.tagName) || p.closest("[data-noi18n],.code-block,.cmd,.vtbox,.pinbox")) return;
  const tr = t(v);
  if (tr !== v) { const lead = v.match(/^\s*/)[0], tail = v.match(/\s*$/)[0]; n.data = lead + tr.trim() + tail; }
  done.set(n, n.data);
}
function element(el) {
  for (const [a, k] of [["title", "i18nT"], ["aria-label", "i18nA"], ["placeholder", "i18nP"]]) {
    const v = el.getAttribute?.(a); if (!v || el.dataset?.[k] === v) continue;
    const tr = t(v); if (tr !== v) el.setAttribute(a, tr); if (el.dataset) el.dataset[k] = tr;
  }
}
export function translate(root) {
  if (!exact) return;
  if (root.nodeType === 3) return textNode(root);
  if (root.nodeType !== 1) return;
  element(root);
  const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
  for (let n = w.nextNode(); n; n = w.nextNode()) n.nodeType === 3 ? textNode(n) : element(n);
}
/** Translate everything shown now, and whatever appears later. */
export function watch() {
  if (!exact) return;
  translate(document.body);
  new MutationObserver(ms => { for (const m of ms) {
    if (m.type === "characterData") textNode(m.target);
    else if (m.type === "attributes") element(m.target);
    else m.addedNodes.forEach(translate);
  } }).observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ["title", "aria-label", "placeholder"] });
}
