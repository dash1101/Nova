package app.novalabs.nova

/** Regular expressions, compiled once. A `Regex(...)` written inside a composable or a per-row function is compiled again on
 *  every pass — on an old tablet, sixty Inbox rows doing that was most of what switching to the Inbox cost. */
object Rx {
    val titleLead = Regex("^[^\\p{L}\\p{N}]+\\s*")                  // the status emoji in front of an alert's title
    val titleLeadPath = Regex("^[^\\p{L}\\p{N}/~]+\\s*")            // …but a path like "/ is 90% full" stays
    val spaces = Regex("\\s+")
    val logTime = Regex("^\\d\\d:\\d\\d:\\d\\d ")
    val logError = Regex("(?i)error|fatal|panic|exception")
    val logWarn = Regex("(?i)warn")
    val ansi = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    val percent = Regex("(\\d+)%")
    val inParens = Regex("\\(([^)]*)\\)")
    val freeInParens = Regex("\\(([^)]*free)\\)")
    val clock = Regex("\\d{1,2}:\\d{2}")
    val hex6 = Regex("#[0-9a-fA-F]{6}")
    val hexPick = Regex("#?([0-9a-fA-F]{6})")
    val email = Regex("[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}")
    val port = Regex(":(\\d+)")
    val textFile = Regex("\\.(md|conf|cfg|ini|log|env|toml|ya?ml|sh|py|js|ts|json|txt|service|properties)$", RegexOption.IGNORE_CASE)
    val approveQr = Regex("^nova-approve:([A-Za-z0-9]{6})$")
    val driveIssue = Regex("^(dup|stale|hidden):")
    val mountPath = Regex("/(mnt|srv|media|data)/[A-Za-z0-9][A-Za-z0-9_.-]{0,40}")
    val notSlug = Regex("[^a-z0-9_.-]+")
    val i18nSlot = Regex("\\{(\\d)\\}")

    // what an Inbox event is about (see evRoute)
    val evContainer = Regex("^Container (?:crashed|unhealthy|ran out of memory|stopped|restarted)[^:]*: (\\S+)", RegexOption.IGNORE_CASE)
    val evSecurity = Regex("\\b(login|ssh|sudo|fail2ban|passkey|authenticator)\\b", RegexOption.IGNORE_CASE)
    val evDevices = Regex("paired|new device|device removed", RegexOption.IGNORE_CASE)
    val evHardware = Regex("drive|disk|smart|sector|raid|pool|mount|nvme|usb", RegexOption.IGNORE_CASE)
    val evBackups = Regex("backup|restore", RegexOption.IGNORE_CASE)
    val evUpdates = Regex("update|upgrade|new version", RegexOption.IGNORE_CASE)
    val evStatus = Regex("memory|cpu|temperature|swap|service", RegexOption.IGNORE_CASE)
}
