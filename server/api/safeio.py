"""
Writing files as root into folders the nova-api account owns (/var/lib/nova-api, /var/log/nova-api).

That account could plant a symlink there ("updates.json.tmp" → /etc/shadow) and wait for root to
write through it. These never follow a symlink: a fresh, unpredictable temp file is created next
to the target (O_EXCL | O_NOFOLLOW, relative to the opened folder), owned and moded through its
file descriptor, then renamed over the target — a rename replaces a symlink, it doesn't follow it.
"""
import json, os, secrets

_NOFOLLOW = getattr(os, "O_NOFOLLOW", 0)


def _ids(owner, group):
    import grp, pwd
    uid = -1 if owner is None else (owner if isinstance(owner, int) else pwd.getpwnam(owner).pw_uid)
    gid = -1 if group is None else (group if isinstance(group, int) else grp.getgrnam(group).gr_gid)
    return uid, gid


def write(path, data, mode=0o640, owner=None, group=None):
    if isinstance(data, str): data = data.encode()
    d, name = os.path.split(os.path.abspath(path))
    uid, gid = _ids(owner, group)
    dfd = os.open(d, os.O_RDONLY | os.O_DIRECTORY | _NOFOLLOW)
    try:
        tmp = f".{name}.{secrets.token_hex(6)}.tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | _NOFOLLOW, 0o600, dir_fd=dfd)
        try:
            with os.fdopen(fd, "wb") as f:
                f.write(data); f.flush()
                if uid != -1 or gid != -1: os.fchown(f.fileno(), uid, gid)
                os.fchmod(f.fileno(), mode)
            os.replace(tmp, name, src_dir_fd=dfd, dst_dir_fd=dfd)
        except BaseException:
            try: os.unlink(tmp, dir_fd=dfd)
            except OSError: pass
            raise
    finally:
        os.close(dfd)


def write_json(path, obj, mode=0o640, owner=None, group=None, indent=None):
    write(path, json.dumps(obj, indent=indent), mode, owner, group)


def append(path, text, mode=0o640):
    """Append a line to a log file, refusing to follow a symlink (or open anything but a regular file)."""
    fd = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT | _NOFOLLOW | getattr(os, "O_NONBLOCK", 0), mode)
    try:
        import stat
        if not stat.S_ISREG(os.fstat(fd).st_mode): raise OSError("not a regular file")
        os.write(fd, text.encode())
    finally:
        os.close(fd)
