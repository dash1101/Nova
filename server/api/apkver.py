#!/usr/bin/python3 -I
"""Print "package versionCode versionName" of an APK, reading its binary AndroidManifest.xml (no Android SDK needed)."""
import struct, sys, zipfile

def manifest_attrs(data):
    # Android binary XML: a string pool, then start-element chunks with typed attributes.
    strings = []
    pos = 8
    while pos < len(data):
        ctype, hsize, csize = struct.unpack_from("<HHI", data, pos)
        if ctype == 0x0001:                                           # string pool
            count, _styles, flags, s_start, _ = struct.unpack_from("<IIIII", data, pos + 8)
            utf8 = flags & 0x100
            offs = struct.unpack_from(f"<{count}I", data, pos + hsize)
            base = pos + s_start
            for o in offs:
                p = base + o
                if utf8:
                    n = data[p]; p += 2 if n & 0x80 else 1           # utf-16 length, then utf-8 length
                    m = data[p]
                    if m & 0x80: m = ((m & 0x7f) << 8) | data[p + 1]; p += 2
                    else: p += 1
                    strings.append(data[p:p + m].decode("utf-8", "replace"))
                else:
                    n = struct.unpack_from("<H", data, p)[0]; p += 2
                    if n & 0x8000: n = ((n & 0x7fff) << 16) | struct.unpack_from("<H", data, p)[0]; p += 2
                    strings.append(data[p:p + n * 2].decode("utf-16le", "replace"))
        elif ctype == 0x0102:                                         # start element
            _ns, name, _astart, _asize, acount = struct.unpack_from("<IIHHH", data, pos + 16)
            if strings[name] == "manifest":
                out = {}
                a = pos + 36
                for i in range(acount):
                    ans, aname, araw, _sz, _r, dtype, dval = struct.unpack_from("<IIIHBBI", data, a + i * 20)
                    key = strings[aname]
                    if dtype == 0x03: out[key] = strings[dval]       # string
                    elif dtype in (0x10, 0x11): out[key] = dval       # int
                    elif araw != 0xFFFFFFFF: out[key] = strings[araw]
                return out
        pos += csize
    return {}

with zipfile.ZipFile(sys.argv[1]) as z:
    m = manifest_attrs(z.read("AndroidManifest.xml"))
print(m.get("package", ""), m.get("versionCode", ""), m.get("versionName", ""))
