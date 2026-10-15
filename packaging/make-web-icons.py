#!/usr/bin/env python3
"""Nova web's App icon choices, made from the Android app's own launcher icons so the two always match.

Reads each adaptive icon (android/app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml), turns its background
and foreground vector drawables into one SVG body (viewBox 0 0 108 108, the adaptive-icon canvas), and writes
server/web/appicons.js. The web rounds and crops it to the visible 72×72 middle, like a launcher does.

Run after changing an app icon:   python3 packaging/make-web-icons.py
"""
import json, os, re, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = f"{ROOT}/android/app/src/main/res"
A = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"

# The picker's choices (AppIcon.base in AppIcon.kt), in its order
CHOICES = [("default", "Default", "ic_launcher"), ("material", "Material You", "ic_launcher_material"), ("dark", "Dark", "ic_launcher_dark"),
           ("outline", "Outline", "ic_launcher_outline"), ("outline_light", "Outline light", "ic_launcher_outline_light"),
           ("bw", "Black on white", "ic_launcher_bw"), ("wb", "White on black", "ic_launcher_wb"), ("glass", "Glossy", "ic_launcher_glass"),
           ("clear", "Glass (see-through)", "ic_launcher_clear"), ("aero", "Aero", "ic_launcher_aero"), ("mica", "Mica", "ic_launcher_mica"),
           ("breeze", "Breeze", "ic_launcher_breeze"), ("blue", "Blue", "ic_launcher_blue"), ("green", "Green", "ic_launcher_green"),
           ("orange", "Orange", "ic_launcher_orange"), ("red", "Red", "ic_launcher_red"), ("pink", "Pink", "ic_launcher_pink")]

def color(c):
    """#RGB, #ARGB, #RRGGBB or #AARRGGBB → (css color, opacity)."""
    c = c.strip().lstrip("#")
    if len(c) == 3: c = "ff" + "".join(x * 2 for x in c)
    elif len(c) == 4: c = "".join(x * 2 for x in c)
    elif len(c) == 6: c = "ff" + c
    a = int(c[:2], 16) / 255
    return "#" + c[2:], round(a, 3)

class Svg:
    def __init__(self, prefix): self.defs, self.n, self.prefix = [], 0, prefix
    def gradient(self, g):
        self.n += 1; gid = f"{self.prefix}g{self.n}"
        t = g.get(A + "type", "linear")
        stops = [(float(i.get(A + "offset", 0)), i.get(A + "color")) for i in g.findall("item")]
        if not stops:
            stops = [(0, g.get(A + "startColor"))] + ([(0.5, g.get(A + "centerColor"))] if g.get(A + "centerColor") else []) + [(1, g.get(A + "endColor"))]
        st = "".join(f'<stop offset="{o}" stop-color="{color(c)[0]}" stop-opacity="{color(c)[1]}"/>' for o, c in stops if c)
        if t == "radial":
            self.defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{g.get(A + "centerX", 54)}" cy="{g.get(A + "centerY", 54)}" r="{g.get(A + "gradientRadius", 54)}">{st}</radialGradient>')
        else:
            self.defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{g.get(A + "startX", 0)}" y1="{g.get(A + "startY", 0)}" x2="{g.get(A + "endX", 108)}" y2="{g.get(A + "endY", 108)}">{st}</linearGradient>')
        return f"url(#{gid})"
    def paint(self, el, attr):
        """fillColor / strokeColor: a plain attribute, or an <aapt:attr name="android:fillColor"><gradient/></aapt:attr> child."""
        for ch in el.findall(AAPT + "attr"):
            if ch.get("name") == "android:" + attr:
                g = ch.find("gradient")
                if g is not None: return self.gradient(g), 1
        v = el.get(A + attr)
        return color(v) if v and v.startswith("#") else (None, 1)
    def node(self, el):
        tag = el.tag
        if tag == "group":
            px, py = float(el.get(A + "pivotX", 0)), float(el.get(A + "pivotY", 0))
            tx, ty = float(el.get(A + "translateX", 0)), float(el.get(A + "translateY", 0))
            sx, sy, r = el.get(A + "scaleX", "1"), el.get(A + "scaleY", "1"), el.get(A + "rotation", "0")
            tr = f"translate({tx + px} {ty + py}) rotate({r}) scale({sx} {sy}) translate({-px} {-py})"
            clip = ""
            for c in el.findall("clip-path"):
                self.n += 1; cid = f"{self.prefix}c{self.n}"
                self.defs.append(f'<clipPath id="{cid}"><path d="{c.get(A + "pathData")}"/></clipPath>'); clip = f' clip-path="url(#{cid})"'
            return f'<g transform="{tr}"><g{clip}>' + "".join(self.node(c) for c in el if c.tag != "clip-path") + "</g></g>"
        if tag == "path":
            d = el.get(A + "pathData", "")
            fill, fa = self.paint(el, "fillColor"); stroke, sa = self.paint(el, "strokeColor")
            fa *= float(el.get(A + "fillAlpha", 1)); sa *= float(el.get(A + "strokeAlpha", 1))
            at = [f'd="{d}"', f'fill="{fill or "none"}"']
            if fill and fa < 1: at.append(f'fill-opacity="{round(fa, 3)}"')
            if el.get(A + "fillType") == "evenOdd": at.append('fill-rule="evenodd"')
            if stroke:
                at += [f'stroke="{stroke}"', f'stroke-width="{el.get(A + "strokeWidth", 1)}"']
                if sa < 1: at.append(f'stroke-opacity="{round(sa, 3)}"')
                if el.get(A + "strokeLineJoin"): at.append(f'stroke-linejoin="{el.get(A + "strokeLineJoin")}"')
                if el.get(A + "strokeLineCap"): at.append(f'stroke-linecap="{el.get(A + "strokeLineCap")}"')
            return "<path " + " ".join(at) + "/>"
        return "".join(self.node(c) for c in el)

def layer(ref, svg):
    """@drawable/name or @color/name → SVG markup for the 108×108 canvas."""
    kind, name = ref.lstrip("@").split("/")
    if kind == "color":
        txt = open(f"{RES}/values/colors.xml").read() if os.path.exists(f"{RES}/values/colors.xml") else ""
        m = re.search(rf'<color name="{name}">([^<]+)</color>', txt)
        c, a = color(m.group(1)) if m else ("#000", 1)
        return f'<rect width="108" height="108" fill="{c}" fill-opacity="{a}"/>'
    root = ET.parse(f"{RES}/drawable/{name}.xml").getroot()
    vw = float(root.get(A + "viewportWidth", 108))
    body = "".join(svg.node(c) for c in root)
    return body if vw == 108 else f'<g transform="scale({108 / vw})">{body}</g>'

def plain(drawable, prefix):
    """A plain (non-adaptive) icon drawn over the visible 72×72: placed back on the 108 canvas."""
    root = ET.parse(f"{RES}/drawable/{drawable}.xml").getroot(); svg = Svg(prefix)
    body = "".join(svg.node(c) for c in root)
    return (f"<defs>{''.join(svg.defs)}</defs>" if svg.defs else "") + f'<g transform="translate(18 18)">{body}</g>'

def icon(mipmap, prefix):
    root = ET.parse(f"{RES}/mipmap-anydpi-v26/{mipmap}.xml").getroot()
    svg = Svg(prefix)
    parts = [layer(root.find(t).get(A + "drawable"), svg) for t in ("background", "foreground") if root.find(t) is not None]
    return (f"<defs>{''.join(svg.defs)}</defs>" if svg.defs else "") + "".join(parts)

out = {}
for i, (cid, label, mip) in enumerate(CHOICES):
    if os.path.exists(f"{RES}/drawable/{mip}_plain.xml"): out[cid] = {"label": label, "svg": plain(mip + "_plain", f"i{i}"), "clear": True}
    elif os.path.exists(f"{RES}/mipmap-anydpi-v26/{mip}.xml"): out[cid] = {"label": label, "svg": icon(mip, f"i{i}")}
# "Your color": the tonal icon in the accent's hue is picked at run time from these (light and dark, every 20°)
tones = {}
for f in sorted(os.listdir(f"{RES}/mipmap-anydpi-v26")):
    m = re.fullmatch(r"ic_launcher_tone_([ld])(\d{3})\.xml", f)
    if m: tones[m.group(1) + m.group(2)] = icon(f[:-4], "t" + m.group(1) + m.group(2))
js = ("// generated by packaging/make-web-icons.py from the Android app's launcher icons — don't edit by hand.\n"
      "// Each is an SVG body on the 108×108 adaptive-icon canvas; the page crops it to the middle 72×72 and rounds it.\n"
      f"export const APP_ICONS = {json.dumps(out, separators=(',', ':'))};\n"
      f"export const TONE_ICONS = {json.dumps(tones, separators=(',', ':'))};\n")
open(f"{ROOT}/server/web/appicons.js", "w").write(js)
print(len(out), "icons,", len(tones), "tones,", len(js) // 1024, "KB")
