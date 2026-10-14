// Liquid Glass (Appearance → Style): glass that bends what's behind it.
//
// Each glass element gets a displacement map made for its exact size and corner radius: flat in the middle,
// bending more and more toward the rim, the way light does through the curved edge of a thick pane. The map
// feeds an SVG filter used as the element's backdrop-filter, so the wallpaper (and anything else behind)
// is refracted live. Maps are cached by size, so scrolling and re-rendering cost nothing extra.
//
// Only Chromium can use an SVG filter as a backdrop-filter; other browsers keep frosted glass with the same
// highlights (app.css), which still reads as glass, just without the bending.

const SEL = ".liquid .glass, .liquid .frost, .liquid #sheet .box";
const NS = "http://www.w3.org/2000/svg";
const cache = new Map();                    // "w×h×r" → filter id
let defs = null, ro = null, mo = null, on = false, refit = 0;

export const canRefract = () => (navigator.userAgentData?.brands || []).some(b => /Chromium/.test(b.brand)) || /\bChrome\/\d/.test(navigator.userAgent);

function ensureDefs() {
  if (defs?.isConnected) return defs;
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("aria-hidden", "true"); svg.setAttribute("width", "0"); svg.setAttribute("height", "0");
  svg.style.cssText = "position:absolute;width:0;height:0;overflow:hidden";
  defs = document.createElementNS(NS, "defs"); svg.appendChild(defs); document.body.appendChild(svg);
  return defs;
}

/** The displacement map: red = sideways, green = up/down, 128 = no bend. Drawn at half size (it's smooth). */
function lensMap(w, h, r, bevel) {
  const k = .5, W = Math.max(2, Math.round(w * k)), H = Math.max(2, Math.round(h * k)), R = r * k, B = Math.max(2, bevel * k);
  const cv = document.createElement("canvas"); cv.width = W; cv.height = H;
  const g = cv.getContext("2d"), img = g.createImageData(W, H), d = img.data;
  const hx = W / 2, hy = H / 2;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    // signed distance to the rounded rectangle (negative inside) and its outward normal
    const px = x + .5 - hx, py = y + .5 - hy, qx = Math.abs(px) - (hx - R), qy = Math.abs(py) - (hy - R);
    const ox = Math.max(qx, 0), oy = Math.max(qy, 0), out = Math.hypot(ox, oy);
    const sd = out + Math.min(Math.max(qx, qy), 0) - R, depth = -sd;
    let dx = 0, dy = 0;
    if (depth < B && depth > -1) {
      let nx, ny;
      if (out > 0) { nx = ox / out; ny = oy / out; } else if (qx > qy) { nx = 1; ny = 0; } else { nx = 0; ny = 1; }
      nx *= Math.sign(px) || 1; ny *= Math.sign(py) || 1;
      const t = 1 - Math.max(0, depth) / B, m = t * t * (3 - 2 * t);      // smooth: none at the bevel's inner edge, most at the rim
      dx = -nx * m; dy = -ny * m;                                         // look inward: the rim gathers what's behind the middle
    }
    const i = (y * W + x) * 4;
    d[i] = 128 + dx * 127; d[i + 1] = 128 + dy * 127; d[i + 2] = 128; d[i + 3] = 255;
  }
  g.putImageData(img, 0, 0);
  return cv.toDataURL();
}

function filterFor(w, h, r) {
  w = Math.round(w / 4) * 4; h = Math.round(h / 4) * 4; r = Math.round(Math.min(r, w / 2, h / 2));
  const key = `${w}x${h}x${r}`;
  if (cache.has(key)) return cache.get(key);
  const bevel = Math.min(Math.max(r * 1.1, 14), 34, w / 3, h / 3), scale = Math.min(70, Math.max(24, Math.min(w, h) * .35));
  const id = "lg" + cache.size.toString(36) + Math.random().toString(36).slice(2, 6);
  const f = document.createElementNS(NS, "filter");
  for (const [a, v] of Object.entries({ id, x: 0, y: 0, width: w, height: h, filterUnits: "userSpaceOnUse", "color-interpolation-filters": "sRGB" })) f.setAttribute(a, v);
  f.innerHTML = `<feImage x="0" y="0" width="${w}" height="${h}" preserveAspectRatio="none" result="m" href="${lensMap(w, h, r, bevel)}"/>
    <feDisplacementMap in="SourceGraphic" in2="m" scale="${scale}" xChannelSelector="R" yChannelSelector="G"/>`;
  ensureDefs().appendChild(f);
  cache.set(key, id);
  if (cache.size > 60) { const [k0, id0] = cache.entries().next().value; cache.delete(k0); document.getElementById(id0)?.remove(); }
  return id;
}

function fit(el) {
  if (!on) return;
  const b = { width: el.offsetWidth, height: el.offsetHeight }; if (b.width < 8 || b.height < 8) return;   // layout size: not scaled by an enter animation
  const r = parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0;
  const id = filterFor(b.width, b.height, r);
  el.style.setProperty("--lens", `url(#${id})`);
}

/** Turn the bending on or off (called from applyTheme). */
export function liquid(enable) {
  enable = !!enable && canRefract();
  if (enable === on) { if (on) { clearTimeout(refit); refit = setTimeout(() => document.querySelectorAll(SEL).forEach(fit), 60); } return; }   // corners changed
  on = enable;
  document.documentElement.classList.toggle("refract", on);
  if (!on) { ro?.disconnect(); mo?.disconnect(); document.querySelectorAll("[style*='--lens']").forEach(el => el.style.removeProperty("--lens")); return; }
  ro = new ResizeObserver(es => es.forEach(e => fit(e.target)));
  const watch = root => { if (root.nodeType !== 1) return; if (root.matches(SEL)) ro.observe(root); root.querySelectorAll?.(SEL).forEach(el => ro.observe(el)); };
  mo = new MutationObserver(ms => ms.forEach(m => m.addedNodes.forEach(watch)));
  mo.observe(document.body, { childList: true, subtree: true });
  watch(document.body);
}
