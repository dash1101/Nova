"""
qrgen — a small QR code maker, so pairing codes can be shown without any outside program (the Nova Server window,
and servers without qrencode). Byte mode, error correction L, versions 1–20 (up to 858 bytes). No third-party code;
follows ISO/IEC 18004.

    matrix(text) → list of rows of booleans (True = dark), without the quiet zone
    svg(text, border=2) → the same as an SVG picture (one path, crisp at any size)
"""
ECC_PER_BLOCK = [None, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28]      # level L, per version
BLOCKS = [None, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8]
MAX_VERSION = 20


def _raw_modules(ver):
    r = (16 * ver + 128) * ver + 64
    if ver >= 2:
        n = ver // 7 + 2; r -= (25 * n - 10) * n - 55
        if ver >= 7: r -= 36
    return r

def _data_codewords(ver): return _raw_modules(ver) // 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver]

def _mul(x, y):
    z = 0
    for i in range(7, -1, -1):
        z = (z << 1) ^ ((z >> 7) * 0x11D); z ^= ((y >> i) & 1) * x
    return z & 0xFF

def _rs_divisor(deg):
    r = [0] * (deg - 1) + [1]; root = 1
    for _ in range(deg):
        for j in range(deg):
            r[j] = _mul(r[j], root)
            if j + 1 < deg: r[j] ^= r[j + 1]
        root = _mul(root, 2)
    return r

def _rs_remainder(data, div):
    r = [0] * len(div)
    for b in data:
        f = b ^ r.pop(0); r.append(0)
        for i, c in enumerate(div): r[i] ^= _mul(c, f)
    return r

def _codewords(data, ver):
    """Data bytes → the full interleaved codeword sequence for this version."""
    cap = _data_codewords(ver); bits = []
    def put(val, n): bits.extend((val >> i) & 1 for i in range(n - 1, -1, -1))
    put(4, 4); put(len(data), 8 if ver <= 9 else 16)
    for b in data: put(b, 8)
    put(0, min(4, cap * 8 - len(bits)))
    put(0, -len(bits) % 8)
    cw = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
    pad = 0xEC
    while len(cw) < cap: cw.append(pad); pad ^= 0xEC ^ 0x11
    nb, ecl = BLOCKS[ver], ECC_PER_BLOCK[ver]; raw = _raw_modules(ver) // 8
    short = nb - raw % nb; slen = raw // nb
    div = _rs_divisor(ecl); blocks = []; k = 0
    for i in range(nb):
        d = cw[k:k + slen - ecl + (0 if i < short else 1)]; k += len(d)
        ecc = _rs_remainder(d, div)
        if i < short: d = d + [None]                    # a gap, so the blocks line up when interleaved
        blocks.append(d + ecc)
    return [b[i] for i in range(len(blocks[0])) for b in blocks if b[i] is not None]

def _penalty(m):
    """How hard this pattern is to scan (lower is better) — the standard's four rules, scored the way qrencode does."""
    n = len(m)
    dark = sum(v for row in m for v in row)
    score = abs((200 * dark + n * n) // (n * n) // 2 - 50) // 5 * 10          # balance of dark and light
    for y in range(n - 1):                               # 2×2 blocks of one color
        a, b = m[y], m[y + 1]
        for x in range(n - 1):
            if a[x] == a[x + 1] == b[x] == b[x + 1]: score += 3
    for rows in (m, list(zip(*m))):
        for row in rows:
            runs = [-1, 1] if row[0] else [1]            # run lengths, light first (odd places are dark runs)
            for i in range(1, n):
                if row[i] != row[i - 1]: runs.append(1)
                else: runs[-1] += 1
            L = len(runs)
            for i, r in enumerate(runs):
                if r >= 5: score += 3 + (r - 5)          # five or more in a row
                if i & 1 and 3 <= i < L - 2 and r % 3 == 0:   # something that looks like a corner square (1:1:3:1:1 with light beside it)
                    f = r // 3
                    if runs[i - 2] == f and runs[i - 1] == f and runs[i + 1] == f and runs[i + 2] == f:
                        if i == 3 or runs[i - 3] >= 4 * f: score += 40
                        elif i + 4 >= L or runs[i + 3] >= 4 * f: score += 40
    return score

def matrix(text, mask=None):
    data = text.encode("utf-8") if isinstance(text, str) else bytes(text)
    ver = next((v for v in range(1, MAX_VERSION + 1) if len(data) + (2 if v <= 9 else 3) <= _data_codewords(v)), None)
    if ver is None: raise ValueError("too long for a QR code")
    n = ver * 4 + 17
    m = [[False] * n for _ in range(n)]; fixed = [[False] * n for _ in range(n)]
    def setf(x, y, dark):
        if 0 <= x < n and 0 <= y < n: m[y][x] = bool(dark); fixed[y][x] = True
    for i in range(n):                                   # timing lines
        setf(6, i, i % 2 == 0); setf(i, 6, i % 2 == 0)
    for cx, cy in ((3, 3), (n - 4, 3), (3, n - 4)):       # the three corner squares, with their light border
        for dy in range(-4, 5):
            for dx in range(-4, 5): setf(cx + dx, cy + dy, max(abs(dx), abs(dy)) not in (2, 4))
    if ver >= 2:                                         # the small alignment squares
        na = ver // 7 + 2; step = (ver * 4 + na * 2 + 1) // (na * 2 - 2) * 2
        pos = [6] + [n - 7 - i * step for i in range(na - 2, -1, -1)]
        for i, cy in enumerate(pos):
            for j, cx in enumerate(pos):
                if (i == 0 and j == 0) or (i == 0 and j == len(pos) - 1) or (i == len(pos) - 1 and j == 0): continue
                for dy in range(-2, 3):
                    for dx in range(-2, 3): setf(cx + dx, cy + dy, max(abs(dx), abs(dy)) != 1)
    def draw_format(mask):
        d = 1 << 3 | mask; rem = d                       # level L = 01
        for _ in range(10): rem = (rem << 1) ^ ((rem >> 9) * 0x537)
        bits = (d << 10 | rem) ^ 0x5412
        bit = lambda i: (bits >> i) & 1
        for i in range(6): setf(8, i, bit(i))
        setf(8, 7, bit(6)); setf(8, 8, bit(7)); setf(7, 8, bit(8))
        for i in range(9, 15): setf(14 - i, 8, bit(i))
        for i in range(8): setf(n - 1 - i, 8, bit(i))
        for i in range(8, 15): setf(8, n - 15 + i, bit(i))
        setf(8, n - 8, True)
    draw_format(0)
    if ver >= 7:
        rem = ver
        for _ in range(12): rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
        bits = ver << 12 | rem
        for i in range(18):
            a, b = n - 11 + i % 3, i // 3
            setf(a, b, (bits >> i) & 1); setf(b, a, (bits >> i) & 1)
    cw = _codewords(data, ver); i = 0; total = len(cw) * 8        # the data, zig-zagging up and down from the right
    right = n - 1
    while right >= 1:
        if right == 6: right = 5
        for vert in range(n):
            for j in range(2):
                x = right - j; up = ((right + 1) & 2) == 0; y = n - 1 - vert if up else vert
                if not fixed[y][x] and i < total:
                    m[y][x] = bool((cw[i >> 3] >> (7 - (i & 7))) & 1); i += 1
        right -= 2
    MASKS = (lambda x, y: (x + y) % 2 == 0, lambda x, y: y % 2 == 0, lambda x, y: x % 3 == 0, lambda x, y: (x + y) % 3 == 0,
             lambda x, y: (x // 3 + y // 2) % 2 == 0, lambda x, y: x * y % 2 + x * y % 3 == 0,
             lambda x, y: (x * y % 2 + x * y % 3) % 2 == 0, lambda x, y: ((x + y) % 2 + x * y % 3) % 2 == 0)
    best = None
    for k, f in enumerate(MASKS):
        if mask is not None and k != mask: continue
        c = [[m[y][x] ^ (f(x, y) and not fixed[y][x]) for x in range(n)] for y in range(n)]
        keep = m; m = c; draw_format(k); c = [row[:] for row in m]; m = keep
        p = _penalty(c)
        if best is None or p < best[0]: best = (p, c)
    return best[1]

def svg(text, border=2, dark="#000", light="#fff"):
    m = matrix(text); n = len(m); s = n + border * 2
    d = "".join(f"M{x + border},{y + border}h1v1h-1z" for y in range(n) for x in range(n) if m[y][x])
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {s} {s}" shape-rendering="crispEdges">'
            f'<rect width="{s}" height="{s}" fill="{light}"/><path d="{d}" fill="{dark}"/></svg>')
