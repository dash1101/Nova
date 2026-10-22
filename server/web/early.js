// Runs before the first paint (a plain, blocking script — app.js is a module and comes later): put on the theme and
// style you chose, so a full page load never shows a frame in the device's own light or dark first (a white flash).
(() => {
  const root = document.documentElement, get = (k, d) => { try { const v = localStorage.getItem("nova." + k); return v == null ? d : JSON.parse(v); } catch { return d; } };
  const t = get("theme", "system"), style = get("style", "auto");
  if (t === "light" || t === "dark") root.dataset.theme = t;
  const dark = t === "dark" || (t !== "light" && !matchMedia("(prefers-color-scheme: light)").matches);
  if (["liquid", "fluent", "breeze", "aero", "lumen"].includes(style)) root.classList.add(style);
  if (!dark) root.classList.add("l-light");
  if (/iPhone|iPad|iPod/.test(navigator.userAgent) && !navigator.standalone) root.classList.add("ios-web");     // Install Nova: Add to Home Screen
  // the page's own background color from last time, before the stylesheet arrives (a fresh load, a Files window)
  root.style.colorScheme = dark ? "dark" : "light";
  const bg = get("bgc", ""); if (/^(#|rgb|color|hsl)/.test(bg)) root.style.backgroundColor = bg;
})();
