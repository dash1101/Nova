// Nova web needs a reasonably recent browser (Chrome 98 or newer, or the equivalent). An older one can't read the app at all
// and just shows a blank page — so if the app hasn't started a few seconds in, say that, and what to do instead.
// Plain old JavaScript on purpose: it has to run in the very browsers that can't run the rest.
(function () {
  setTimeout(function () {
    if (window.__novaUp) return;
    var m = /(Edg|Chrome|CriOS|Firefox|FxiOS|Version)\/(\d+)/.exec(navigator.userAgent);
    var name = m ? m[1].replace("Version", "Safari").replace("CriOS", "Chrome").replace("Edg", "Edge").replace("FxiOS", "Firefox") : "this browser", ver = m ? m[2] : "";
    var tooOld = !(window.structuredClone && Array.prototype.at && window.Promise);
    var box = document.createElement("div");
    box.style.cssText = "position:fixed;top:0;left:0;right:0;bottom:0;z-index:99999;display:flex;align-items:center;justify-content:center;padding:24px;background:#14141a;color:#f0f0f6;font:16px/1.5 sans-serif;text-align:center";
    var inner = document.createElement("div"); inner.style.cssText = "max-width:440px";
    var h = document.createElement("h2"); h.style.cssText = "margin:0 0 12px;font-size:22px"; h.textContent = "Nova web didn't start";
    var p = document.createElement("p"); p.style.cssText = "margin:0 0 18px;color:#b4b4c2";
    p.textContent = tooOld
      ? "This browser (" + name + (ver ? " " + ver : "") + ") is older than Nova web works with. Update it (Google Play → Chrome), or use the Nova app instead — it works on older Android too."
      : "The page didn't load all the way. Check the connection and try again. If it keeps happening, your browser (" + name + (ver ? " " + ver : "") + ") may be too old: update it, or use the Nova app.";
    var b = document.createElement("button"); b.textContent = "Try again";
    b.style.cssText = "font:inherit;font-weight:600;padding:12px 22px;border:0;border-radius:14px;background:#3e7bff;color:#fff;cursor:pointer";
    b.onclick = function () { location.reload(); };
    inner.appendChild(h); inner.appendChild(p); inner.appendChild(b); box.appendChild(inner); document.body.appendChild(box);
  }, 8000);
})();
