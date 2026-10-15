// Runs before the first paint (a plain, blocking script — app.js is a module and comes later): put on the theme and
// style you chose, so a full page load never shows a frame in the device's own light or dark first (a white flash).
(() => {
  const root = document.documentElement, get = (k, d) => { try { const v = localStorage.getItem("nova." + k); return v == null ? d : JSON.parse(v); } catch { return d; } };
  const t = get("theme", "system"), style = get("style", "auto");
  if (t === "light" || t === "dark") root.dataset.theme = t;
  const dark = t === "dark" || (t !== "light" && !matchMedia("(prefers-color-scheme: light)").matches);
  if (["liquid", "fluent", "breeze", "aero"].includes(style)) root.classList.add(style);
  if (!dark) root.classList.add("l-light");
})();
