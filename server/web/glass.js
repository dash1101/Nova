// Glass (Appearance → Style): glass that bends what's behind it.
//
// Each glass element gets a displacement map made for its exact size and corner radius: flat in the middle,
// bending more and more toward the rim, the way light does through the curved edge of a thick pane. The map
// feeds an SVG filter used as the element's backdrop-filter, so the wallpaper (and anything else behind)
// is refracted live. Maps are cached by size, so scrolling and re-rendering cost nothing extra.
//
// Only Chromium can use an SVG filter as a backdrop-filter; other browsers keep frosted glass with the same
// highlights (app.css), which still reads as glass, just without the bending.

const SEL = ".liquid .glass, .liquid .frost, .liquid #sheet .box, .liquid .alertcard, .liquid .sugg";
const NS = "http://www.w3.org/2000/svg";
const cache = new Map();                    // "w×h×r" → filter id
let defs = null, ro = null, mo = null, on = false, refit = 0, seq = 0;

export const canRefract = () => (navigator.userAgentData?.brands || []).some(b => /Chromium/.test(b.brand)) || /\bChrome\/\d/.test(navigator.userAgent);

function ensureDefs() {
  if (defs?.isConnected) return defs;
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("aria-hidden", "true"); svg.setAttribute("width", "0"); svg.setAttribute("height", "0");
  svg.style.cssText = "position:absolute;width:0;height:0;overflow:hidden";
  defs = document.createElementNS(NS, "defs"); svg.appendChild(defs); document.body.appendChild(svg);
  return defs;
}

// The glass edge is a convex "squircle" bevel, h(x) = ⁴√(1 − (1 − x)⁴) for x from the rim (0) to the flat
// middle (1). Light coming straight down refracts at that surface (Snell's law, glass n = 1.5) and lands a
// little further in; how far is worked out once, along one line across the bevel (a 128-step table), and
// then reused all the way round — every point of the edge bends the same, only the direction turns.
const N_GLASS = 1.5, LUT = 128;
const squircle = x => Math.pow(1 - Math.pow(1 - x, 4), 1 / 4);
function bendTable(bevel, thick) {
  const t = new Float32Array(LUT); let max = 0;
  for (let i = 0; i < LUT; i++) {
    const x = (i + .5) / LUT, d = .001;
    const slope = (squircle(Math.min(1, x + d)) - squircle(Math.max(0, x - d))) / (2 * d) * thick / bevel;   // dh/dx in pixels
    const a1 = Math.atan(slope), a2 = Math.asin(Math.sin(a1) / N_GLASS);
    const v = (thick * squircle(x) + thick * .25) * Math.tan(a1 - a2);                                      // how far the ray moves
    t[i] = v; if (v > max) max = v;
  }
  for (let i = 0; i < LUT; i++) t[i] /= max || 1;
  return { t, max };
}

/** Rounded-rectangle geometry at (x, y): signed distance to the edge (negative inside) and the outward normal. */
function edge(px, py, hx, hy, R) {
  const qx = Math.abs(px) - (hx - R), qy = Math.abs(py) - (hy - R);
  const ox = Math.max(qx, 0), oy = Math.max(qy, 0), out = Math.hypot(ox, oy);
  const sd = out + Math.min(Math.max(qx, qy), 0) - R;
  let nx, ny;
  if (out > 0) { nx = ox / out; ny = oy / out; } else if (qx > qy) { nx = 1; ny = 0; } else { nx = 0; ny = 1; }
  return { sd, nx: nx * (Math.sign(px) || 1), ny: ny * (Math.sign(py) || 1) };
}

/** Two images for one size: the displacement map (red = sideways, green = up/down, 128 = straight) and the rim light. */
function maps(w, h, r, bevel, thick) {
  const k = .5, W = Math.max(2, Math.round(w * k)), H = Math.max(2, Math.round(h * k)), R = r * k, B = Math.max(2, bevel * k);
  const { t, max } = bendTable(B, thick * k);
  const disp = document.createElement("canvas"); disp.width = W; disp.height = H;
  const dg = disp.getContext("2d"), img = dg.createImageData(W, H), d = img.data;
  const hx = W / 2, hy = H / 2, inX = hx - Math.max(R, B) - 2, inY = hy - Math.max(R, B) - 2;
  for (let i = 0; i < d.length; i += 4) { d[i] = d[i + 1] = d[i + 2] = 128; d[i + 3] = 255; }      // straight through by default
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const px = x + .5 - hx, py = y + .5 - hy;
    if (Math.abs(px) < inX && Math.abs(py) < inY) { x = Math.max(x, Math.floor(hx + inX) - 1); continue; }   // the flat middle: skip ahead
    const e = edge(px, py, hx, hy, R), depth = -e.sd;
    let dx = 0, dy = 0;
    if (depth > -1 && depth < B) { const m = t[Math.min(LUT - 1, Math.max(0, (depth / B * LUT) | 0))]; dx = -e.nx * m; dy = -e.ny * m; }   // inward
    const i = (y * W + x) * 4;
    d[i] = 128 + dx * 127; d[i + 1] = 128 + dy * 127; d[i + 2] = 128; d[i + 3] = 255;
  }
  dg.putImageData(img, 0, 0);
  // The rim light, at full size so it's crisp but anti-aliased: brightest where the edge faces the light (up and
  // to the left), a fainter catch on the far side, nothing where the edge runs along the light.
  const spec = document.createElement("canvas"); spec.width = Math.round(w); spec.height = Math.round(h);
  const sg = spec.getContext("2d"), si = sg.createImageData(spec.width, spec.height), sd = si.data;
  const Lx = Math.cos(-2.1), Ly = Math.sin(-2.1), HX = spec.width / 2, HY = spec.height / 2;
  const sX = HX - r - 3, sY = HY - r - 3;
  for (let y = 0; y < spec.height; y++) for (let x = 0; x < spec.width; x++) {
    const px = x + .5 - HX, py = y + .5 - HY;
    if (Math.abs(px) < HX - 3 && Math.abs(py) < sY || Math.abs(px) < sX && Math.abs(py) < HY - 3) {         // not near the edge
      if (Math.abs(py) < sY && Math.abs(px) < HX - 3) x = Math.max(x, Math.floor(HX + HX - 3) - 1); continue; }
    const e = edge(px, py, HX, HY, r);
    const band = 1 - Math.min(1, Math.abs(e.sd + .9) / 1.1);                 // a ~2 px band just inside the edge, soft both sides
    if (band <= 0) continue;
    const dot = e.nx * Lx + e.ny * Ly, a = band * (Math.max(0, dot) ** 1.4 * .95 + Math.max(0, -dot) ** 2 * .45 + .1);
    const i = (y * spec.width + x) * 4; sd[i] = sd[i + 1] = sd[i + 2] = 255; sd[i + 3] = Math.min(255, a * 255);
  }
  sg.putImageData(si, 0, 0);
  return { disp: disp.toDataURL(), spec: spec.toDataURL(), max: max / k };        // (max: in real pixels)
}

function filterFor(w, h, r, soft) {
  w = Math.round(w / 4) * 4; h = Math.round(h / 4) * 4; r = Math.round(Math.min(r, w / 2, h / 2));
  const key = `${w}x${h}x${r}x${soft}`;
  if (cache.has(key)) return cache.get(key);
  const small = Math.min(w, h);
  const bevel = Math.min(Math.max(small * .3, 10), 34, r * 1.6 || 34), thick = bevel * 1.4;
  const m = maps(w, h, r, bevel, thick);
  const scale = Math.min(80, m.max * 2);                                     // the map spans −½…+½ of scale, so ×2 = the strongest bend in pixels
  const id = "lg" + (++seq).toString(36);
  const f = document.createElementNS(NS, "filter");
  for (const [a, v] of Object.entries({ id, x: 0, y: 0, width: w, height: h, filterUnits: "userSpaceOnUse", "color-interpolation-filters": "sRGB" })) f.setAttribute(a, v);
  f.innerHTML = `<feImage x="0" y="0" width="${w}" height="${h}" preserveAspectRatio="none" result="map" href="${m.disp}"/>
    <feDisplacementMap in="SourceGraphic" in2="map" scale="${scale.toFixed(1)}" xChannelSelector="R" yChannelSelector="G" result="bent0"/>
    <feGaussianBlur in="bent0" stdDeviation="${soft}" edgeMode="duplicate" result="bent"/>
    <feImage x="0" y="0" width="${w}" height="${h}" preserveAspectRatio="none" result="rim" href="${m.spec}"/>
    <feBlend in="rim" in2="bent" mode="screen"/>`;
  ensureDefs().appendChild(f);
  cache.set(key, id);
  if (cache.size > 48) { const [k0, id0] = cache.entries().next().value; cache.delete(k0); document.getElementById(id0)?.remove(); }
  return id;
}

function fit(el) {
  if (!on) return;
  const b = { width: el.offsetWidth, height: el.offsetHeight }; if (b.width < 8 || b.height < 8) return;   // layout size: not scaled by an enter animation
  const r = parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0;
  // bend first, then blur (inside the filter, so the bending stays visible): content panes frosted, small controls clear
  const soft = el.matches(".nav, #toast") ? 1.2 : 5;
  const id = filterFor(b.width, b.height, r, soft);
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
