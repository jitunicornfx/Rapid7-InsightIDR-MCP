package com.jitunicornfx.insightidr.mcp

import java.io.File

/**
 * Delete the regular files directly inside [dir] whose name [matches] and that were last modified
 * at least [olderThanMillis] before [now].
 *
 * Best-effort and never throws: it runs at startup, where a file it cannot list, stat or delete —
 * on Windows, typically one another process still holds open — is simply left for next time. Not
 * recursive, and [matches] sees only the bare file name, so a caller cannot widen it into a path.
 *
 * A file whose modification time cannot be read is skipped rather than treated as ancient:
 * [File.lastModified] reports that case as 0, which would otherwise look 50 years stale.
 */
internal fun sweepOlderThan(dir: File, olderThanMillis: Long, now: Long, matches: (String) -> Boolean) {
    val entries = runCatching { dir.listFiles() }.getOrNull() ?: return
    for (file in entries) {
        if (!file.isFile || !matches(file.name)) continue
        val lastModified = runCatching { file.lastModified() }.getOrDefault(0L)
        if (lastModified <= 0L) continue
        if (now - lastModified >= olderThanMillis) runCatching { file.delete() }
    }
}

/** Lower-case hex, the form GitHub publishes its `sha256:` asset digests in. */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
