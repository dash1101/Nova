# Storage, backups and diagnostics

Everything here is in the app and Nova web under **Storage & hardware**, **Backups** and **Diagnostics**.

## Storage map and suggestions

Nova reads the drives (`lsblk`), mounts, `/etc/fstab`, combined pools (mergerfs) and RAID arrays
(`/proc/mdstat`) and shows what each drive is for — system, part of a pool, backup destination, data,
unused — plus which containers use it and whether a backup covers it. Suggestions point out unused
drives, folders nothing backs up, full pools, degraded arrays, failing drives and drives that would
hold up the boot (no `nofail`).

## Setting up drives (the wizard)

| Goal | What Nova does | Survives a failed drive? |
|---|---|---|
| One big drive | Formats the drives (or keeps them with their files) and combines them with **mergerfs** | No — only that drive's files are lost |
| Safe if a drive fails | **mdadm** RAID 1 (mirror), 5 (parity), 6 (double parity) or 10 | Yes (1, or 2 for RAID 6) |
| A backup drive / Just one drive | One partition + filesystem | — |
| As fast as possible | RAID 0 | No — everything is lost |
| Add to a pool | Adds a drive to a combined pool, or a spare/replacement to an array | — |

Filesystems: ext4 (default), XFS, Btrfs, and exFAT for single drives. Every new filesystem is
mounted under `/mnt/<name>` and added to `/etc/fstab` by UUID with `nofail` (a backup of the old
fstab is kept as `/etc/fstab.nova-bak`). Arrays are added to `/etc/mdadm/mdadm.conf` and the
initramfs is updated.

Safety: the server re-checks everything itself. It never touches the system drive, swap, or a drive
that's mounted or in an array; the request must list exactly the drives you confirmed; erasing needs
your fingerprint (browsers: approval on your phone); you type ERASE in the app. Each operation runs
as its own background task (`nova-task-<id>`), so it finishes even if the app closes.

## Backups

Jobs live in `/etc/nova-backup/jobs.json` (root only; SMB passwords in `/etc/nova-backup/secrets/`).
`nova-backups.timer` checks every minute and starts due jobs (a run missed while the server was off
starts after boot). Each run is a dated snapshot in `<destination>/nova-backups/<name>/<date>/`,
hard-linked to the previous one, with `latest` pointing at the newest. Shares without hard links
get one copy plus `versions/<date>/`. Old snapshots are thinned to N hourly/daily/weekly/monthly
(the newest three always stay). A source that's missing, or lost more than 20 % of its files since
last time, is skipped and you get an alert; "Back up anyway" overrides it once.

Restore puts a file or folder back where it was (existing files are kept, nothing is deleted) or
next to it in `restored-<date>/`. A hand-written `/usr/local/bin/nova-backup` script, if present,
is shown alongside and keeps working.

From a shell: `sudo python3 /usr/lib/nova-api/backups.py list | run <id>`.

## Diagnostics

| Test | How |
|---|---|
| Internet speed | 4 download / 3 upload streams to `speed.cloudflare.com` for 10 s each (falls back to public test files if rate-limited); ping, jitter and loss from 30 ICMP pings to 1.1.1.1 |
| Phone ↔ server | The app downloads 25 MB from `/api/v1/diag/blob` and times `/api/v1/ping` |
| Drive speed | `fio`: 1 MiB sequential read/write at queue depth 8, 4 KiB random read/write at depth 32, 8 s each, on a temporary file in the drive's mount point (deleted after) |
| CPU stress | `stress-ng --cpu <all threads>`, sampling temperature (hwmon), clock (cpufreq) and package power (RAPL) every second; stops at 97 °C |
| Memory | `stress-ng --vm … --verify` over a chosen share of free memory (always leaves 1.5 GB) |
| Quick tools | ping, tracepath, DNS lookup, TCP port check, top processes |

## Tools

The package recommends mergerfs, mdadm, xfsprogs, btrfs-progs, exfatprogs, nfs-common, cifs-utils,
fio, stress-ng, iputils-tracepath and attr, so `apt` installs them with Nova. `sudo nova-setup` checks
for anything missing, and each feature installs what it needs the first time it's used.
