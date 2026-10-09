package app.novalabs.nova

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Plain-language explanations behind the "?" buttons (storage, backups, diagnostics). */
object Help {
    val topics: Map<String, Pair<String, String>> = mapOf(
        "storage" to ("How storage works" to "Each drive holds a filesystem, which appears as a folder (its mount point, like /mnt/photos). " +
            "A pool joins several drives so they look like one folder. Backups copy folders to another drive or a NAS so a failed drive doesn't lose anything."),
        "pool" to ("What's a pool?" to "Several drives that work as one. Nova can make two kinds: a combined pool (drives added together into one big folder) " +
            "and a RAID array (drives that keep copies or parity, so one can fail without losing anything)."),
        "combine" to ("One big drive (combined)" to "Your drives keep working as separate drives, but Nova shows them as one folder. New files go to the drive with the most free space. " +
            "You can mix sizes and add drives later. If one drive dies you only lose the files on that drive — so back the pool up."),
        "raid0" to ("Fastest (RAID 0, stripe)" to "Every file is split across all the drives, so reading and writing are faster. But if any one drive fails, everything is lost. Only for scratch space or things you can re-download."),
        "raid1" to ("Mirror (RAID 1)" to "Every drive holds the same copy. Space = one drive. Survives all but one drive failing. Simple and very safe; great for two drives."),
        "raid5" to ("Parity (RAID 5)" to "Spreads data and a checksum (parity) across 3 or more drives. Space = all drives minus one. Survives one drive failing. " +
            "Rebuilding after a failure takes hours and strains the other drives, so keep a backup too."),
        "raid6" to ("Double parity (RAID 6)" to "Like RAID 5 with two parity drives' worth of space. Needs 4+ drives; survives any two failing. Good for many large drives."),
        "raid10" to ("Mirror + stripe (RAID 10)" to "Drives in mirrored pairs, striped together. Needs an even number (4+). Half the space, fast, survives one failure per pair."),
        "raid-not-backup" to ("RAID isn't a backup" to "RAID keeps running when a drive fails. It does not protect against deleting a file by mistake, ransomware, or the whole machine dying — every copy changes at once. Keep a backup on a separate drive or NAS as well."),
        "fs" to ("Filesystems" to "How files are laid out on the drive.\n\n• ext4 — the dependable Linux default. Pick this if unsure.\n• XFS — great with huge files and very large drives.\n" +
            "• Btrfs — checksums every file (spots silent corruption) and compresses; good for backup drives.\n• exFAT — also readable by Windows and Mac; for a drive you'll plug into other computers. No Linux permissions."),
        "mount" to ("Mount point" to "The folder where a drive appears, like /mnt/backup. Apps and containers use this path. Nova adds it to /etc/fstab so it comes back after every restart."),
        "nofail" to ("Booting without a missing drive" to "Normally Linux waits at startup for every drive in /etc/fstab. With 'nofail', the server starts anyway if a drive is unplugged or dead, and Nova alerts you which one is missing."),
        "policy" to ("Where new files go" to "• Most free space (recommended) — fills drives evenly.\n• Keep folders together — a new file goes to a drive that already has its folder.\n• Least free space — fills one drive before the next.\n• Random (weighted by free space) — spreads files out."),
        "erase" to ("Erasing drives" to "Setting up a drive deletes everything on it — Nova creates a fresh partition table and filesystem. It can't be undone. Nova never offers the system drive, or drives that are mounted or part of a pool."),
        "suggestions" to ("Suggestions" to "Nova looks at your drives, pools and backups and points out what's worth doing — like drives that aren't used, folders nothing backs up, or pools that are nearly full."),
        "smart" to ("Drive health (SMART)" to "Drives keep their own error counters. Reallocated or pending sectors mean the surface is wearing out — back it up and plan a replacement. 'Cable errors' are usually a loose or bad SATA cable."),
        "backup" to ("How Nova backs up" to "Each run makes a dated snapshot: a complete copy you can browse like any folder. Files that didn't change are shared with the previous snapshot (hard links), so each run only costs what changed."),
        "keep" to ("How long backups are kept" to "Nova keeps the newest snapshot of each day for N days, of each week for N weeks and of each month for N months — then deletes older ones. The newest three are always kept."),
        "guard" to ("Missing-drive protection" to "If a folder suddenly has far fewer files than last time (what an unplugged drive looks like), Nova doesn't back it up — the previous copy stays safe and you get an alert. If you really deleted the files, run it once with \"Back up anyway\"."),
        "nas" to ("Backing up to a NAS" to "A NAS (Synology, QNAP, TrueNAS, Unraid, or another computer) shares folders over the network.\n\n• SMB — the Windows-style share every NAS offers; needs a user name and password.\n• NFS — the Linux-style share; usually just the address and export path.\n\nNova connects only while a backup runs."),
        "mirror-mode" to ("Without snapshots" to "Some shares can't do hard links. Then Nova keeps one up-to-date copy plus dated 'versions' folders holding the files that changed or were deleted."),
        "restore" to ("Restoring" to "• Put it back where it was — copies the file or folder back; files that are already there are left alone, so nothing newer is overwritten.\n• Next to the original — puts it in a 'restored-<date>' folder beside it, so you can compare first."),
        "net" to ("Internet speed" to "Download and upload are measured from the server to Cloudflare's speed-test network (or another public test server if that's busy). Ping is the round-trip time to 1.1.1.1; jitter is how much it varies — low jitter matters for games and calls."),
        "device-net" to ("This device ↔ server" to "Measures the link between this phone and the server — your Wi-Fi or mobile data plus the route (home network or remote)."),
        "disk" to ("Drive speed" to "Writes a temporary file (deleted afterwards) and measures:\n• Sequential — big files like videos (MB/s).\n• Random 4K — lots of tiny reads/writes like databases and app data (IOPS = operations per second).\n\nTypical: HDD ~150 MB/s & ~100 IOPS, SATA SSD ~500 MB/s & tens of thousands, NVMe several GB/s."),
        "cpu" to ("CPU stress test" to "Runs every core flat out to see how hot the CPU gets, whether the clock stays up, and how much power it draws. Most desktop CPUs are fine up to about 90–95 °C; a falling clock under load means it's throttling. Nova stops the test at 97 °C."),
        "mem" to ("Memory test" to "Fills part of the free memory with patterns and reads them back to find bad RAM. Your apps keep running (Nova leaves room). For a full check, boot memtest86+ from a USB stick."),
        "tools" to ("Quick tools" to "• Ping — is a host reachable, and how fast?\n• Traceroute — the hops between the server and a host.\n• DNS — what a name resolves to.\n• Port — can the server open a connection to host:port?"),
    )
}

/** A small "?" that explains [topic] in a sheet. */
@Composable fun HelpButton(topic: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val t = Help.topics[topic] ?: return
    Box(modifier.size(26.dp).clip(CircleShape).background(N.sub.copy(alpha = 0.18f)).clickable { open = true }
        .semantics { contentDescription = "Help: ${t.first}" }, contentAlignment = Alignment.Center) {
        Text("?", color = N.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
    if (open) OneDialog({ open = false }, t.first, t.second, listOf(DialogButton("Got it", N.blue) { open = false }))
}

/** Section label with a "?" next to it. */
@Composable fun SectionHelp(text: String, topic: String) {
    Row(Modifier.fillMaxWidth().padding(start = Space.gutter + Space.inner - 6.dp, end = 30.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = N.sub, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        HelpButton(topic)
    }
}
