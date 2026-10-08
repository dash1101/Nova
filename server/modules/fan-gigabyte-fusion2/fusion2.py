"""
fusion2.py — minimal driver for Gigabyte RGB Fusion 2 USB controllers (ITE IT5701/5702,
USB 048d:5702), as found on the A620I AX. Talks to /dev/hidraw* with HID feature reports.

Protocol per OpenRGB's GigabyteRGBFusion2USBController (GPL-2.0), re-implemented minimally:
  0xCC 0x60           -> request info; GET_FEATURE returns the IT8297 report
  0xCC 0x20..0x27     -> per-zone effect packet (header = 0x20 + led index)
  0xCC 0x28 <mask>    -> apply effects for the zones in <mask>
On this board: led index 5 = D_LED1 (the ARGB header) ; index 1 = LED_C (12V RGB header).
"""
import fcntl, glob, os, struct

VID_PID = "0000048D:00005702"
EFFECTS = {"off": 0, "static": 1, "pulse": 2, "blink": 3, "cycle": 4, "wave": 6, "random": 8}
ZONES = {"argb": 5, "rgb": 1}           # D_LED1, LED_C

def _ioc(nr, size): return (3 << 30) | (size << 16) | (ord('H') << 8) | nr

def find_device():
    for p in sorted(glob.glob("/sys/class/hidraw/hidraw*")):
        try:
            if VID_PID in open(f"{p}/device/uevent").read():
                return "/dev/" + os.path.basename(p)
        except OSError:
            pass
    raise FileNotFoundError("RGB Fusion 2 controller (048d:5702) not found")

LOCK = "/var/lib/nova-rgb/hw.lock"

class hwlock:
    """Cross-process lock: the API, the minute tick, boot restore and the animator all
    drive the same controller; packets from two writers must never interleave."""
    def __enter__(self):
        self.fd = os.open(LOCK, os.O_RDWR | os.O_CREAT, 0o660)
        fcntl.flock(self.fd, fcntl.LOCK_EX); return self
    def __exit__(self, *a): fcntl.flock(self.fd, fcntl.LOCK_UN); os.close(self.fd)

def period_ms(speed):
    """App speed 1 (slowest, 10 s per cycle) .. 100 (fastest, 0.2 s). Geometric, so every
    step of the slider feels like the same amount faster."""
    s = max(1, min(100, int(speed)))
    return round(10000 * (0.02 ** ((s - 1) / 99)))

class Fusion2:
    def __init__(self, path=None, init=True, lock=True):
        self.path = path or find_device()
        self._lock = hwlock().__enter__() if lock else None
        self.fd = os.open(self.path, os.O_RDWR)
        if init:
            self.init()

    def init(self):
        """Required before effects take hold (found by testing on the A620I AX:
        without it the board keeps running its stored rainbow)."""
        import time
        for reg in range(0x20, 0x28):          # reset all zone registers
            self._send(bytes((0xCC, reg, 0, 0)))
        self._send(b"\xCC\x31\x00")            # music-beat mode off
        self._send(b"\xCC\x32\x00")            # built-in effects enabled on every strip header
        time.sleep(0.05)

    def close(self):
        os.close(self.fd)
        if self._lock: self._lock.__exit__(); self._lock = None
    def __enter__(self): return self
    def __exit__(self, *a): self.close()

    def _send(self, data: bytes):
        buf = bytes(data).ljust(64, b"\0")
        fcntl.ioctl(self.fd, _ioc(0x06, 64), buf)

    def info(self):
        self._send(b"\xCC\x60")
        buf = bytearray(64); buf[0] = 0xCC
        fcntl.ioctl(self.fd, _ioc(0x07, 64), buf)
        return {"name": bytes(buf[12:40]).split(b"\0")[0].decode(errors="replace"),
                "fw": "0x%08x" % struct.unpack_from("<I", buf, 4)[0]}

    def set_effect(self, zone="argb", effect="static", rgb=(255, 255, 255), brightness=255, speed=50, rainbow=True):
        """speed: 1 (slow) .. 100 (fast) — see period_ms. The controller itself takes periods
        in ms (bigger = slower), which is why the old 0-9 scale felt inverted."""
        led = ZONES[zone]
        r, g, b = (max(0, min(255, int(c))) for c in rgb)
        brightness = max(0, min(255, int(brightness)))
        etype = EFFECTS[effect]
        p = bytearray(64)
        p[0] = 0xCC
        p[1] = 0x20 + led
        struct.pack_into("<II", p, 2, 1 << led, 0)          # zone0, zone1
        p[11] = etype
        p[12] = brightness                                   # max brightness
        p[13] = 0                                            # min brightness
        p[14:18] = bytes((b, g, r, 0))                       # color0, BGR
        per = period_ms(speed)
        if etype == 2:   # pulse: fade in, fade out, hold
            half = max(100, per // 2)
            struct.pack_into("<HHH", p, 22, half, half, max(50, per // 5))
        elif etype == 3: # blink: on, off, interval
            struct.pack_into("<HHH", p, 22, 100, 100, max(200, per))
        elif etype == 4: # color cycle
            per = max(400, per); struct.pack_into("<HH", p, 22, per, per - 200); p[30] = 7 if rainbow else 0
        elif etype == 6: # hardware wave (unused: doesn't render on this board's ARGB header; see nova_rgb animator)
            struct.pack_into("<H", p, 22, max(100, per)); p[30] = 7 if rainbow else 0; p[31] = 1
        elif etype == 8: # random
            struct.pack_into("<H", p, 22, max(30, min(1000, per // 10))); p[30] = 1; p[31] = 5
        self._send(p)
        apply = bytearray(64); apply[0] = 0xCC; apply[1] = 0x28
        struct.pack_into("<I", apply, 2, 1 << led)
        self._send(apply)
        self._send(b"\xCC\x28\xFF\x00")       # fast-apply (this firmware needs it)

    # ── Direct (per-LED) mode on the ARGB header — used for gradients ─────────────
    def byte_order(self):
        """(r_idx, g_idx, b_idx) inside each 3-byte LED slot, from the controller's
        calibration (this board reports GRB)."""
        self._send(b"\xCC\x60")
        buf = bytearray(64); buf[0] = 0xCC
        fcntl.ioctl(self.fd, _ioc(0x07, 64), buf)
        cal = struct.unpack_from("<I", buf, 44)[0] or 0x00010002
        return (cal >> 16) & 0xFF, (cal >> 8) & 0xFF, cal & 0xFF

    def direct_setup(self):
        """Switch D_LED1 to per-LED control. Returns the byte order for direct_frame."""
        self._send(b"\xCC\x34\x00\x00\x00")      # LED count class: 32 on every header (enough for any fan)
        self._send(b"\xCC\x32\x01")              # built-in effects OFF on D_LED1 -> direct control
        return self.byte_order()

    def set_direct(self, colors, order=None):
        """colors: list of (r,g,b) for LED 0..n-1 on the ARGB header (D_LED1).
        Pass `order` (from direct_setup) to send a frame without re-doing the setup."""
        ro, go, bo = order or self.direct_setup()
        sent, k = 0, 0
        while k < len(colors):
            chunk = colors[k:k + 19]
            p = bytearray(64); p[0] = 0xCC; p[1] = 0x58
            struct.pack_into("<HB", p, 2, sent, len(chunk) * 3)
            for i, (r, g, b) in enumerate(chunk):
                o = 5 + i * 3
                p[o + ro], p[o + go], p[o + bo] = r, g, b
            self._send(p)
            sent += len(chunk) * 3; k += len(chunk)
