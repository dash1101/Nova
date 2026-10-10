// A small terminal for Nova web: enough of VT100/xterm for shells, nano, htop, less and top.
// Screen is a grid of cells; output is parsed as it arrives; the keyboard is turned into the
// bytes a terminal sends. No third-party code.

const PAL = ["#1b1b1f", "#ff5f56", "#3ecf6e", "#ffbd2e", "#3e91ff", "#bf5af2", "#2fd3d8", "#d7d7dc",
             "#5a5a63", "#ff7b73", "#62e08c", "#ffd25e", "#6aaeff", "#d18bff", "#5ee6ea", "#ffffff"];
function color256(n) {
  if (n < 16) return PAL[n];
  if (n >= 232) { const v = 8 + (n - 232) * 10; return `rgb(${v},${v},${v})`; }
  n -= 16; const c = [Math.floor(n / 36), Math.floor(n / 6) % 6, n % 6].map(x => x ? 55 + x * 40 : 0);
  return `rgb(${c[0]},${c[1]},${c[2]})`;
}
const blank = a => ({ ch: " ", ...a });

export class Term {
  constructor(el, { onInput, onResize } = {}) {
    this.el = el; this.onInput = onInput; this.onResize = onResize;
    this.cols = 80; this.rows = 24; this.attr = {}; this.saved = null;
    this.main = null; this.alt = null; this.useAlt = false; this.scrollback = [];
    this.x = 0; this.y = 0; this.top = 0; this.bot = 23; this.cursor = true; this.appCursor = false; this.wrapNext = false;
    this.esc = ""; this.dirty = true;
    el.classList.add("vt"); el.tabIndex = 0;
    el.innerHTML = `<div class="vt-screen"></div><textarea class="vt-input" autocapitalize="off" autocomplete="off" autocorrect="off" spellcheck="false" aria-label="Terminal input"></textarea>`;
    this.screenEl = el.querySelector(".vt-screen"); this.inp = el.querySelector(".vt-input");
    this.grid = this.newGrid();
    this.measure(); this.bindKeys();
    this.ro = new ResizeObserver(() => this.measure()); this.ro.observe(el);
    el.addEventListener("mousedown", e => { if (!getSelection().toString()) setTimeout(() => this.inp.focus()); });
    const raf = () => { if (!this.el.isConnected) return this.ro.disconnect(); if (this.dirty) this.render(); requestAnimationFrame(raf); };
    requestAnimationFrame(raf);
  }
  newGrid() { return Array.from({ length: this.rows }, () => Array.from({ length: this.cols }, () => blank({}))); }
  measure() {
    const probe = document.createElement("span"); probe.textContent = "0".repeat(10); probe.className = "vt-probe"; this.screenEl.appendChild(probe);
    const cw = probe.getBoundingClientRect().width / 10, ch = probe.getBoundingClientRect().height; probe.remove();
    if (!cw || !ch) return;
    const cols = Math.max(20, Math.floor((this.el.clientWidth - 16) / cw)), rows = Math.max(6, Math.floor((this.el.clientHeight - 16) / ch));
    if (cols === this.cols && rows === this.rows) return;
    const old = this.grid; this.cols = cols; this.rows = rows; this.grid = this.newGrid();
    const off = Math.max(0, old.length - rows);
    for (let r = 0; r < Math.min(rows, old.length); r++) for (let c = 0; c < Math.min(cols, old[0].length); c++) this.grid[r][c] = old[r + off][c];
    this.y = Math.min(rows - 1, Math.max(0, this.y - off)); this.x = Math.min(cols - 1, this.x);
    this.top = 0; this.bot = rows - 1; this.dirty = true;
    this.onResize?.(cols, rows);
  }
  focus() { this.inp.focus(); }

  // ── output ──
  write(s) {
    for (const ch of s) {
      if (this.esc) { this.escape(ch); continue; }
      const c = ch.codePointAt(0);
      if (c === 27) { this.esc = "\x1b"; continue; }
      if (c === 13) { this.x = 0; this.wrapNext = false; }
      else if (c === 10 || c === 11 || c === 12) { this.lf(); }
      else if (c === 8) { if (this.x > 0) this.x--; this.wrapNext = false; }
      else if (c === 9) { this.x = Math.min(this.cols - 1, (Math.floor(this.x / 8) + 1) * 8); }
      else if (c === 7 || c < 32) { /* bell and other controls: ignored */ }
      else this.put(ch);
    }
    this.dirty = true;
  }
  put(ch) {
    if (this.wrapNext) { this.x = 0; this.lf(); this.wrapNext = false; }
    this.grid[this.y][this.x] = { ch, ...this.attr };
    if (this.x === this.cols - 1) this.wrapNext = true; else this.x++;
  }
  lf() { if (this.y === this.bot) this.scroll(1); else if (this.y < this.rows - 1) this.y++; }
  scroll(n) {
    for (let i = 0; i < n; i++) {
      const gone = this.grid.splice(this.top, 1)[0];
      if (!this.useAlt && this.top === 0) { this.scrollback.push(gone); if (this.scrollback.length > 2000) this.scrollback.shift(); }
      this.grid.splice(this.bot, 0, Array.from({ length: this.cols }, () => blank({ bg: this.attr.bg })));
    }
  }
  scrollDown(n) { for (let i = 0; i < n; i++) { this.grid.splice(this.bot, 1); this.grid.splice(this.top, 0, Array.from({ length: this.cols }, () => blank({ bg: this.attr.bg }))); } }
  escape(ch) {
    this.esc += ch; const e = this.esc;
    if (e.length === 2) {
      if (ch === "[" || ch === "]" || ch === "(" || ch === ")" || ch === "#" || ch === "P") return;
      if (ch === "7") this.saved = { x: this.x, y: this.y, attr: { ...this.attr } };
      else if (ch === "8" && this.saved) ({ x: this.x, y: this.y } = this.saved, this.attr = { ...this.saved.attr });
      else if (ch === "M") { if (this.y === this.top) this.scrollDown(1); else if (this.y > 0) this.y--; }
      else if (ch === "D") this.lf();
      else if (ch === "E") { this.x = 0; this.lf(); }
      else if (ch === "c") { this.grid = this.newGrid(); this.x = this.y = 0; this.attr = {}; }
      this.esc = ""; return;
    }
    if (e[1] === "(" || e[1] === ")" || e[1] === "#") { this.esc = ""; return; }
    if (e[1] === "]" || e[1] === "P") {                  // OSC / DCS: until BEL or ST
      if (ch === "\x07" || e.endsWith("\x1b\\")) { this.esc = ""; }
      else if (e.length > 2000) this.esc = "";
      return;
    }
    if (ch >= "@" && ch <= "~") { this.csi(e.slice(2, -1), ch); this.esc = ""; }
    else if (e.length > 64) this.esc = "";
  }
  csi(p, f) {
    const priv = p.startsWith("?"), a = (priv ? p.slice(1) : p).split(";").map(v => parseInt(v, 10));
    const n = (i = 0, d = 1) => isNaN(a[i]) || a[i] === 0 ? d : a[i];
    const clampY = () => { this.y = Math.max(0, Math.min(this.rows - 1, this.y)); this.x = Math.max(0, Math.min(this.cols - 1, this.x)); this.wrapNext = false; };
    switch (f) {
      case "A": this.y -= n(); clampY(); break; case "B": case "e": this.y += n(); clampY(); break;
      case "C": case "a": this.x += n(); clampY(); break; case "D": this.x -= n(); clampY(); break;
      case "E": this.y += n(); this.x = 0; clampY(); break; case "F": this.y -= n(); this.x = 0; clampY(); break;
      case "G": case "`": this.x = n() - 1; clampY(); break; case "d": this.y = n() - 1; clampY(); break;
      case "H": case "f": this.y = n(0) - 1; this.x = n(1) - 1; clampY(); break;
      case "J": { const m = isNaN(a[0]) ? 0 : a[0];
        if (m === 0) { this.eraseLine(this.y, this.x, this.cols); for (let r = this.y + 1; r < this.rows; r++) this.eraseLine(r, 0, this.cols); }
        else if (m === 1) { for (let r = 0; r < this.y; r++) this.eraseLine(r, 0, this.cols); this.eraseLine(this.y, 0, this.x + 1); }
        else { for (let r = 0; r < this.rows; r++) this.eraseLine(r, 0, this.cols); if (m === 3) this.scrollback = []; } break; }
      case "K": { const m = isNaN(a[0]) ? 0 : a[0]; if (m === 0) this.eraseLine(this.y, this.x, this.cols); else if (m === 1) this.eraseLine(this.y, 0, this.x + 1); else this.eraseLine(this.y, 0, this.cols); break; }
      case "X": this.eraseLine(this.y, this.x, Math.min(this.cols, this.x + n())); break;
      case "@": { const row = this.grid[this.y]; for (let i = 0; i < n(); i++) { row.splice(this.x, 0, blank({})); row.pop(); } break; }
      case "P": { const row = this.grid[this.y]; for (let i = 0; i < n(); i++) { row.splice(this.x, 1); row.push(blank({})); } break; }
      case "L": if (this.y >= this.top && this.y <= this.bot) { const t = this.top; this.top = this.y; this.scrollDown(n()); this.top = t; } break;
      case "M": if (this.y >= this.top && this.y <= this.bot) { const t = this.top; this.top = this.y; this.scroll(n()); this.top = t; } break;
      case "S": this.scroll(n()); break; case "T": this.scrollDown(n()); break;
      case "r": this.top = n(0) - 1; this.bot = (isNaN(a[1]) ? this.rows : a[1]) - 1; if (this.bot <= this.top) { this.top = 0; this.bot = this.rows - 1; } this.x = 0; this.y = 0; break;
      case "s": this.saved = { x: this.x, y: this.y, attr: { ...this.attr } }; break;
      case "u": if (this.saved) { this.x = this.saved.x; this.y = this.saved.y; } break;
      case "m": this.sgr(p === "" ? [0] : a.map(v => isNaN(v) ? 0 : v)); break;
      case "h": case "l": if (priv) this.mode(a, f === "h"); break;
      case "n": if (a[0] === 6) this.onInput?.(`\x1b[${this.y + 1};${this.x + 1}R`); else if (a[0] === 5) this.onInput?.("\x1b[0n"); break;
      case "c": if (!priv) this.onInput?.("\x1b[?1;2c"); break;
    }
  }
  mode(a, on) {
    for (const m of a) {
      if (m === 1) this.appCursor = on;
      else if (m === 25) this.cursor = on;
      else if (m === 1049 || m === 47 || m === 1047) {
        if (on && !this.useAlt) { this.main = { grid: this.grid, x: this.x, y: this.y }; this.grid = this.newGrid(); this.useAlt = true; this.x = this.y = 0; }
        else if (!on && this.useAlt) { this.grid = this.main.grid; this.x = this.main.x; this.y = this.main.y; this.useAlt = false; }
        this.top = 0; this.bot = this.rows - 1;
      }
    }
  }
  eraseLine(r, from, to) { const row = this.grid[r]; if (!row) return; for (let c = from; c < to && c < this.cols; c++) row[c] = blank({ bg: this.attr.bg }); }
  sgr(a) {
    for (let i = 0; i < a.length; i++) {
      const v = a[i];
      if (v === 0) this.attr = {};
      else if (v === 1) this.attr.b = 1; else if (v === 2) this.attr.dim = 1; else if (v === 3) this.attr.i = 1; else if (v === 4) this.attr.u = 1;
      else if (v === 7) this.attr.inv = 1; else if (v === 22) { delete this.attr.b; delete this.attr.dim; } else if (v === 23) delete this.attr.i;
      else if (v === 24) delete this.attr.u; else if (v === 27) delete this.attr.inv;
      else if (v >= 30 && v <= 37) this.attr.fg = PAL[v - 30]; else if (v >= 90 && v <= 97) this.attr.fg = PAL[v - 82];
      else if (v >= 40 && v <= 47) this.attr.bg = PAL[v - 40]; else if (v >= 100 && v <= 107) this.attr.bg = PAL[v - 92];
      else if (v === 39) delete this.attr.fg; else if (v === 49) delete this.attr.bg;
      else if ((v === 38 || v === 48) && a[i + 1] === 5) { this.attr[v === 38 ? "fg" : "bg"] = color256(a[i + 2] | 0); i += 2; }
      else if ((v === 38 || v === 48) && a[i + 1] === 2) { this.attr[v === 38 ? "fg" : "bg"] = `rgb(${a[i + 2] | 0},${a[i + 3] | 0},${a[i + 4] | 0})`; i += 4; }
    }
  }

  // ── drawing ──
  render() {
    this.dirty = false;
    const esc = s => s === "<" ? "&lt;" : s === ">" ? "&gt;" : s === "&" ? "&amp;" : s;
    const style = c => { let fg = c.fg, bg = c.bg; if (c.inv) [fg, bg] = [bg || "var(--vt-bg)", fg || "var(--vt-fg)"];
      return (fg ? `color:${fg};` : "") + (bg ? `background:${bg};` : "") + (c.b ? "font-weight:700;" : "") + (c.dim ? "opacity:.65;" : "") + (c.i ? "font-style:italic;" : "") + (c.u ? "text-decoration:underline;" : ""); };
    const line = (row, r) => { let out = "", cur = null, buf = "";
      row.forEach((c, x) => { const atCur = r === this.y && x === this.x && this.cursor && !this.scrolledBack; const st = style(c) + (atCur ? "" : "");
        if (atCur) { if (buf) out += `<span style="${cur}">${buf}</span>`; buf = ""; cur = null; out += `<span class="vt-cur" style="${st}">${esc(c.ch)}</span>`; return; }
        if (st !== cur) { if (buf) out += `<span style="${cur}">${buf}</span>`; buf = ""; cur = st; } buf += esc(c.ch); });
      if (buf) out += `<span style="${cur}">${buf}</span>`; return out; };
    const sb = this.useAlt ? [] : this.scrollback.slice(-500);
    this.screenEl.innerHTML = [...sb.map(r => line(r, -1)), ...this.grid.map(line)].map(l => `<div>${l}</div>`).join("");
    if (!this.userScrolled) this.el.scrollTop = this.el.scrollHeight;
  }

  // ── keyboard ──
  bindKeys() {
    const send = s => this.onInput?.(s);
    this.el.addEventListener("scroll", () => { this.userScrolled = this.el.scrollTop + this.el.clientHeight < this.el.scrollHeight - 4; });
    this.inp.addEventListener("keydown", e => {
      const k = e.key, ac = this.appCursor;
      const map = { Enter: "\r", Backspace: "\x7f", Tab: "\t", Escape: "\x1b", ArrowUp: ac ? "\x1bOA" : "\x1b[A", ArrowDown: ac ? "\x1bOB" : "\x1b[B",
        ArrowRight: ac ? "\x1bOC" : "\x1b[C", ArrowLeft: ac ? "\x1bOD" : "\x1b[D", Home: "\x1b[H", End: "\x1b[F", Delete: "\x1b[3~", Insert: "\x1b[2~",
        PageUp: "\x1b[5~", PageDown: "\x1b[6~", F1: "\x1bOP", F2: "\x1bOQ", F3: "\x1bOR", F4: "\x1bOS", F5: "\x1b[15~", F6: "\x1b[17~", F7: "\x1b[18~",
        F8: "\x1b[19~", F9: "\x1b[20~", F10: "\x1b[21~", F11: "\x1b[23~", F12: "\x1b[24~" };
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && (k === "C" || k === "c")) return;        // copy
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && (k === "V" || k === "v")) return;        // paste (handled below)
      let out = null;
      if (map[k]) out = (e.altKey ? "\x1b" : "") + map[k];
      else if (e.ctrlKey && !e.altKey && k.length === 1) { const c = k.toUpperCase().charCodeAt(0); if (c >= 64 && c <= 95) out = String.fromCharCode(c - 64); else if (k === " ") out = "\x00"; }
      else if (e.altKey && k.length === 1) out = "\x1b" + k;
      if (out !== null) { e.preventDefault(); this.userScrolled = false; send(out); }
    });
    this.inp.addEventListener("input", () => { const v = this.inp.value; this.inp.value = ""; if (v) { this.userScrolled = false; send(v.replace(/\n/g, "\r")); } });
    this.inp.addEventListener("paste", e => { e.preventDefault(); const t = e.clipboardData.getData("text"); if (t) send(t.replace(/\r?\n/g, "\r")); });
  }
}
