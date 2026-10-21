// Nova web — a small QR code maker (byte mode, error correction M, versions 1–3: up to 42 characters), for the
// pairing screen: the Nova app scans it to approve this browser. No third-party code. Follows ISO/IEC 18004.

const VER = [null, { size: 21, data: 16, ec: 10 }, { size: 25, data: 28, ec: 16, align: 18 }, { size: 29, data: 44, ec: 26, align: 22 }];

function rsDivisor(deg) {
  const r = new Array(deg).fill(0); r[deg - 1] = 1; let root = 1;
  for (let i = 0; i < deg; i++) {
    for (let j = 0; j < deg; j++) { r[j] = mul(r[j], root); if (j + 1 < deg) r[j] ^= r[j + 1]; }
    root = mul(root, 2);
  }
  return r;
}
function mul(x, y) { let z = 0; for (let i = 7; i >= 0; i--) { z = (z << 1) ^ ((z >>> 7) * 0x11d); z ^= ((y >>> i) & 1) * x; } return z & 255; }
function rsRemainder(data, div) {
  const r = div.map(() => 0);
  for (const b of data) { const f = b ^ r.shift(); r.push(0); div.forEach((c, i) => r[i] ^= mul(c, f)); }
  return r;
}

/** The QR code for [text] as a square of booleans (true = dark). */
export function qrMatrix(text) {
  const bytes = [...new TextEncoder().encode(text)];
  const v = [1, 2, 3].find(n => bytes.length <= VER[n].data - 2);
  if (!v) throw new Error("too long for this QR maker");
  const { size, data: nData, ec: nEc, align } = VER[v];
  // the data: byte mode (0100), an 8-bit count, the bytes, a terminator, then padding to fill
  const bits = [];
  const put = (val, n) => { for (let i = n - 1; i >= 0; i--) bits.push((val >>> i) & 1); };
  put(4, 4); put(bytes.length, 8); bytes.forEach(b => put(b, 8));
  put(0, Math.min(4, nData * 8 - bits.length)); while (bits.length % 8) bits.push(0);
  const cw = []; for (let i = 0; i < bits.length; i += 8) cw.push(parseInt(bits.slice(i, i + 8).join(""), 2));
  for (let p = 0; cw.length < nData; p++) cw.push(p % 2 ? 0x11 : 0xec);
  const all = cw.concat(rsRemainder(cw, rsDivisor(nEc)));

  const m = [...Array(size)].map(() => new Array(size).fill(false)), fn = [...Array(size)].map(() => new Array(size).fill(false));
  const set = (x, y, d) => { m[y][x] = d; fn[y][x] = true; };
  // finder patterns in three corners (with their white separators), the timing lines, the alignment pattern
  for (const [cx, cy] of [[3, 3], [size - 4, 3], [3, size - 4]])
    for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
      const x = cx + dx, y = cy + dy, d = Math.max(Math.abs(dx), Math.abs(dy));
      if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d !== 2 && d !== 4);
    }
  for (let i = 0; i < size; i++) { if (!fn[6][i]) set(i, 6, i % 2 === 0); if (!fn[i][6]) set(6, i, i % 2 === 0); }
  if (align) for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(align + dx, align + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
  const format = mask => {   // error correction M (00) and the mask, BCH-protected, in its two copies
    const d = (0 << 3) | mask; let r = d; for (let i = 0; i < 10; i++) r = (r << 1) ^ ((r >>> 9) * 0x537);
    const b = ((d << 10) | r) ^ 0x5412, bit = i => ((b >>> i) & 1) === 1;
    for (let i = 0; i <= 5; i++) set(8, i, bit(i));
    set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8));
    for (let i = 9; i < 15; i++) set(14 - i, 8, bit(i));
    for (let i = 0; i < 8; i++) set(size - 1 - i, 8, bit(i));
    for (let i = 8; i < 15; i++) set(8, size - 15 + i, bit(i));
    set(8, size - 8, true);                       // the dark module
  };
  format(0);                                       // reserve the format areas first
  // the codewords, zig-zagging up and down in two-column strips from the bottom right
  let i = 0;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let vert = 0; vert < size; vert++) for (let j = 0; j < 2; j++) {
      const x = right - j, up = ((right + 1) & 2) === 0, y = up ? size - 1 - vert : vert;
      if (!fn[y][x] && i < all.length * 8) { m[y][x] = ((all[i >>> 3] >>> (7 - (i & 7))) & 1) === 1; i++; }
    }
  }
  // mask 0 (checkerboard) on everything that isn't a fixed pattern
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) if (!fn[y][x] && (x + y) % 2 === 0) m[y][x] = !m[y][x];
  format(0);
  return m;
}

/** The QR code as an SVG (dark squares on a white quiet zone). */
export function qrSvg(text, px = 6) {
  const m = qrMatrix(text), n = m.length, q = 4, s = (n + 2 * q) * px;
  let d = ""; m.forEach((row, y) => row.forEach((on, x) => { if (on) d += `M${(x + q) * px},${(y + q) * px}h${px}v${px}h-${px}z`; }));
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${s} ${s}" width="${s}" height="${s}" role="img" aria-label="QR code"><rect width="${s}" height="${s}" fill="#fff"/><path d="${d}" fill="#000"/></svg>`;
}
