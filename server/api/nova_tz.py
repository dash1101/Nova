"""Where the server is, roughly, from its time zone — for sunrise/sunset schedules.

Uses the zone's reference city in tzdata's zone1970.tab: offline, nothing is looked up, and good to
a few minutes for the sun (the zone's city is usually within a few hundred km). Shared by nova-api,
nova-setup and the lighting module.
"""
import os


def timezone():
    try: return os.path.realpath("/etc/localtime").split("/zoneinfo/", 1)[1]
    except IndexError:
        try: return open("/etc/timezone").read().strip()
        except OSError: return ""


def _dms(x, deg):
    v = int(x[1:1 + deg]) + int(x[1 + deg:3 + deg]) / 60 + (int(x[3 + deg:5 + deg]) / 3600 if len(x) > 3 + deg else 0)
    return round(-v if x[0] == "-" else v, 4)


def tz_location():
    """{"lat", "lon", "name", "tz", "source": "timezone"} or None (UTC, unknown zone)."""
    tz = timezone()
    if not tz: return None
    for tab in ("/usr/share/zoneinfo/zone1970.tab", "/usr/share/zoneinfo/zone.tab"):
        try:
            for line in open(tab):
                f = line.split("\t")
                if line.startswith("#") or len(f) < 3 or f[2].strip() != tz: continue
                c = f[1]; i = max(c.rfind("+"), c.rfind("-"))
                return {"lat": _dms(c[:i], 2), "lon": _dms(c[i:], 3), "name": tz.rsplit("/", 1)[-1].replace("_", " "),
                        "tz": tz, "source": "timezone"}
        except OSError: continue
    return None
