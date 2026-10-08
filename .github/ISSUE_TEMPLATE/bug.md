---
name: Bug report
about: Something doesn't work
---
**What happened, and what did you expect?**

**Steps to reproduce**

**Versions** — app (Settings → Software update), server (`dpkg -s nova-server | grep Version`), Android version, server OS

**Logs** — `journalctl -u nova-api -n 50` (remove anything private)

> Security problem? Please don't file it here — see SECURITY.md.
