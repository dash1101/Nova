"""
A small client for the OpenRGB SDK server (Labs → OpenRGB): list its devices and set their LEDs.

OpenRGB (openrgb.org) talks to almost every RGB thing — motherboards, RAM, GPUs, fans, keyboards.
With its SDK server running (`openrgb --server`, port 6742), Nova draws its effects itself and sends
the colors over, so every Nova effect works on every device OpenRGB knows.

Speaks protocol 0 (the oldest layout, which every OpenRGB server still accepts): Nova never asks for a
newer version, so the server answers in that layout and device ids are plain indexes.
"""
import socket, struct

PORT = 6742
_COUNT, _DATA, _NAME, _LEDS, _CUSTOM = 0, 1, 50, 1050, 1100


class OpenRGBError(ValueError): pass        # a ValueError: the API shows the reason to the user


class _Reader:
    def __init__(self, b): self.b, self.i = b, 0
    def take(self, n):
        if self.i + n > len(self.b): raise OpenRGBError("short controller data")
        v = self.b[self.i:self.i + n]; self.i += n; return v
    def u16(self): return struct.unpack("<H", self.take(2))[0]
    def u32(self): return struct.unpack("<I", self.take(4))[0]
    def i32(self): return struct.unpack("<i", self.take(4))[0]
    def s(self): return self.take(self.u16()).split(b"\0")[0].decode("utf-8", "replace")


def parse_controller(b):
    """Protocol-0 controller data → {name, type, description, location, zones: [{name, leds}], leds}."""
    r = _Reader(b); r.u32()                                  # data_size
    out = {"type": r.i32(), "name": r.s(), "description": r.s()}; r.s(); r.s()   # version, serial
    out["location"] = r.s()
    modes = r.u16(); r.i32()                                 # active mode
    for _ in range(modes):
        r.s(); r.i32(); r.u32(); r.u32(); r.u32(); r.u32(); r.u32(); r.u32(); r.u32(); r.u32()
        r.take(4 * r.u16())                                  # the mode's colors
    zones = []
    for _ in range(r.u16()):
        name = r.s(); r.i32(); r.u32(); r.u32(); n = r.u32()
        ml = r.u16()
        if ml: r.take(ml)                                    # matrix map (height, width, map)
        zones.append({"name": name, "leds": n})
    leds = r.u16()
    for _ in range(leds): r.s(); r.u32()
    out["zones"], out["leds"] = zones, leds
    return out


class Client:
    def __init__(self, host="127.0.0.1", port=PORT, name="Nova", timeout=2.0):
        try: self.s = socket.create_connection((host, int(port)), timeout=timeout)
        except OSError as e: raise OpenRGBError(f"can't reach OpenRGB at {host}:{port} — is its SDK server on? ({e.strerror or e})")
        self.s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self._send(0, _NAME, name.encode()[:60] + b"\0")

    def close(self):
        try: self.s.close()
        except OSError: pass
    def __enter__(self): return self
    def __exit__(self, *a): self.close()

    def _send(self, dev, pid, data=b""):
        try: self.s.sendall(b"ORGB" + struct.pack("<III", dev, pid, len(data)) + data)
        except OSError as e: raise OpenRGBError(f"OpenRGB connection lost ({e})")

    def _recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            try: c = self.s.recv(n - len(buf))
            except OSError as e: raise OpenRGBError(f"OpenRGB didn't answer ({e})")
            if not c: raise OpenRGBError("OpenRGB closed the connection")
            buf += c
        return buf

    def _reply(self, pid):
        """The answer to `pid` (the server may slip in other packets, like 'device list changed', first)."""
        for _ in range(50):
            h = self._recv_exact(16)
            if h[:4] != b"ORGB": raise OpenRGBError("that's not an OpenRGB server")
            _, got, size = struct.unpack("<III", h[4:])
            if size > 4 << 20: raise OpenRGBError("OpenRGB sent too much")
            data = self._recv_exact(size)
            if got == pid: return data
        raise OpenRGBError("no answer from OpenRGB")

    def count(self):
        self._send(0, _COUNT); return struct.unpack("<I", self._reply(_COUNT)[:4])[0]

    def controller(self, i):
        self._send(i, _DATA); return parse_controller(self._reply(_DATA))

    def devices(self):
        return [{"index": i, **self.controller(i)} for i in range(min(self.count(), 64))]

    def direct(self, i):
        """Put a device in its custom (direct) mode, so it shows the colors Nova sends."""
        self._send(i, _CUSTOM)

    def set_leds(self, i, colors):
        """colors: [(r, g, b), …], one per LED of the whole device."""
        body = struct.pack("<H", len(colors)) + b"".join(struct.pack("BBBB", r & 255, g & 255, b & 255, 0) for r, g, b in colors)
        self._send(i, _LEDS, struct.pack("<I", 4 + len(body)) + body)
