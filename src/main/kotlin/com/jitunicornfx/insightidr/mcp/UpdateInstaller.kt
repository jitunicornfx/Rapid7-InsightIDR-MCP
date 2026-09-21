package com.jitunicornfx.insightidr.mcp

import io.ktor.client.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.core.remaining
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Downloads a newer release and installs it over the JAR this server is running from.
 *
 * ## Why this is written defensively
 *
 * This is the one component that installs code. Everything it fetches is remote and therefore
 * attacker-influenceable, so the download is treated as hostile until proven otherwise:
 *
 *  - **The SHA-256 digest published by the GitHub API is mandatory and verified before anything is
 *    installed.** A byte that does not hash to the expected digest is never swapped in.
 *  - **Redirects are followed manually**, one hop at a time, and every hop must be HTTPS on a
 *    GitHub-owned host ([UpdateChecker.isAllowedDownloadUrl]). GitHub serves release binaries via a
 *    redirect to its CDN, so redirects cannot simply be disabled — but they must not be able to
 *    steer the download to an arbitrary host.
 *  - **No credentials are ever sent.** The InsightIDR API key must never leave Rapid7 hosts, and the
 *    releases endpoint is public, so this uses its own unauthenticated client.
 *  - **The payload must be a real JAR** containing this server's entry point before it replaces
 *    anything, so a valid-hash-but-wrong-artifact swap cannot brick the install.
 *  - **Nothing throws.** Every failure degrades to an [Outcome] describing what happened.
 *
 * ## Residual risk (deliberately documented, not solved here)
 *
 * The digest is fetched from the same GitHub API response that advertises the download. That
 * defends against a corrupted or tampered *download* (CDN compromise, an interception of the asset
 * fetch, truncation), but **not** against an attacker who controls the GitHub release itself — a
 * repository or account compromise would serve a matching digest for malicious bytes. Defending
 * against that requires signature verification against a pinned public key, which this project does
 * not yet publish. Operators who cannot accept that residual risk should disable automatic
 * installation ([Config.ENV_DISABLE_AUTO_UPDATE] or `--no-auto-update`) and update manually.
 */
object UpdateInstaller {

    /** Generous ceiling for the whole download; a ~19MB artifact over a slow link still fits. */
    const val DOWNLOAD_TIMEOUT_MS = 300_000L

    /** Maximum redirect hops followed while resolving a release asset to its CDN location. */
    const val MAX_REDIRECTS = 5

    /** Suffix for the staged download sitting beside the installed JAR. */
    const val STAGED_SUFFIX = ".new"

    /** Suffix for the pre-swap backup of the ORIGINAL JAR, kept so a failed write can be undone. */
    const val BACKUP_SUFFIX = ".bak"

    /** Sidecar used to serialise installation across processes launched from the same JAR. */
    const val LOCK_SUFFIX = ".update.lock"

    /**
     * Age past which an orphaned update sidecar is treated as abandoned debris rather than a file an
     * in-flight install might still be using. Comfortably longer than any real download.
     */
    const val STALE_SIDECAR_MILLIS = 60L * 60L * 1000L

    /** What happened, for logging and for the notification sent to the MCP client. */
    sealed interface Outcome {
        /** The new JAR is installed at the original path; the new code activates on restart. */
        data class Installed(val version: String, val path: String) : Outcome

        /**
         * The new JAR was downloaded and verified, but the running JVM holds the target file such
         * that it cannot be replaced yet. It is staged at [stagedPath] and will be swapped in when
         * this process exits — at which point [sha256] is re-checked against the file on disk, so a
         * staged file that changed in the meantime is never installed.
         */
        data class Staged(
            val version: String,
            val stagedPath: String,
            val sha256: String,
            /**
             * SHA-256 of the installed JAR at the moment this was staged, or null if it could not be
             * read. The swap happens when the process exits, which can be days later; if the installed
             * JAR is no longer this, someone else has updated it since and this copy is stale.
             */
            val replacesSha256: String? = null,
        ) : Outcome

        /** Nothing was installed. [reason] is server-authored text, never remote content. */
        data class Failed(val reason: String) : Outcome

        /** Automatic installation is switched off, or there is nothing to install. */
        data object Skipped : Outcome
    }

    /** SHA-256 of a file **as it exists on disk**, or null if it cannot be read. */
    internal fun sha256Of(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    /**
     * Whether [osName] (the `os.name` system property) is Windows, which decides how the install lock
     * file is cleaned up. A pure function of its argument, so the POSIX answer can be tested on the
     * Windows machine that is the only place this project's tests ever run.
     */
    internal fun isWindows(osName: String?): Boolean = osName.orEmpty().startsWith("Windows", ignoreCase = true)

    /** Sidecar on which every server started from a JAR holds a shared lock for as long as it runs. */
    const val IN_USE_SUFFIX = ".inuse"

    /**
     * Declare that this process is running from [target], until the returned handle is closed.
     *
     * Where the JAR cannot be swapped atomically (Windows), an update is applied by rewriting its bytes
     * in place as a process exits. But OTHER processes started from the same JAR - one per MCP client
     * is the normal stdio deployment - still have it open and load classes from it lazily: rewrite it
     * underneath them and their next first-time class load is a ZipException or NoClassDefFoundError
     * in the middle of someone's investigation. So every server holds a SHARED lock on a sidecar, and
     * the rewrite only happens when it can take that lock EXCLUSIVELY, i.e. when nobody else is running
     * ([withSoleUse]). The file is 0 bytes and is never deleted, for the reasons given at
     * [withInstallLock]. Returns null if the marker could not be taken, which only costs the protection.
     *
     * Keep a strong reference to the result: an unreachable RandomAccessFile is closed by its cleaner,
     * and the lock goes with it.
     */
    internal fun markInUse(target: File): AutoCloseable? = runCatching {
        val file = RandomAccessFile(File(target.parentFile, target.name + IN_USE_SUFFIX), "rw")
        val lock = runCatching { file.channel.tryLock(0, Long.MAX_VALUE, true) }.getOrNull()
        if (lock == null) {
            file.close()
            null
        } else {
            AutoCloseable { runCatching { file.close() } }
        }
    }.getOrNull()

    /**
     * Run [block] only if no OTHER process holds [markInUse] on [target]; null if one does. This
     * process must have closed its own marker first. The exclusive lock is held for the whole of
     * [block], so a server starting meanwhile cannot take its marker until the rewrite is over.
     */
    internal inline fun <T> withSoleUse(target: File, block: () -> T): T? {
        val file = try {
            RandomAccessFile(File(target.parentFile, target.name + IN_USE_SUFFIX), "rw")
        } catch (e: IOException) {
            return block() // no marker file can be made here, so nobody else has one either
        }
        try {
            val lock = try {
                file.channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null // held within this same JVM
            } catch (e: IOException) {
                null
            }
            return if (lock == null) null else block()
        } finally {
            try {
                file.close()
            } catch (e: IOException) {
            }
        }
    }

    /** Whether this JVM is running on Windows. */
    internal val IS_WINDOWS: Boolean = isWindows(System.getProperty("os.name"))

    /**
     * The outcome of [withInstallLock]. The three cases are kept apart because they mean different
     * things to an operator: [Contended] is normal and transient, [Unavailable] is a broken install
     * directory that will not fix itself.
     */
    internal sealed interface LockResult<out T> {
        /** The lock was held for the whole of the block, which returned [value]. */
        data class Acquired<T>(val value: T) : LockResult<T>

        /** Another process (or another thread of this one) holds the lock. The block did not run. */
        data object Contended : LockResult<Nothing>

        /**
         * The lock file could not be opened or locked at all — typically a read-only install
         * directory. The block did not run. [reason] is server-authored and names the lock file
         * only, never its directory.
         */
        data class Unavailable(val reason: String) : LockResult<Nothing>
    }

    /**
     * Run [block] while holding an exclusive lock on a sidecar beside [target].
     *
     * The staging file and the target JAR are machine-wide resources: the usual stdio deployment
     * starts one JVM per MCP client from the same JAR path, so an in-process flag cannot serialise
     * them. Everything from download through swap runs inside this lock.
     *
     * Whatever [block] throws — cancellation included — propagates, after the lock is released.
     * `inline`, so a suspending caller can suspend inside the block; the lock belongs to the process
     * rather than to a thread, so resuming on a different thread is harmless.
     *
     * ## Cleaning up the lock file
     *
     * The file is 0 bytes and inert: a leftover one never blocks a later acquisition, because the OS
     * drops the lock when its holder exits, however it exits. Removing it is purely cosmetic, so it
     * is only done where it cannot do harm:
     *
     *  - **Windows:** deleted once our handle is closed. The JDK opens files without
     *    `FILE_SHARE_DELETE`, so the delete is refused while *any* process still has the file open
     *    (measured). It therefore cannot remove a lock that is in use: it either removes a file
     *    nobody holds, or fails.
     *  - **POSIX:** never deleted. `unlink()` ignores open descriptors and `fcntl` locks, so it would
     *    succeed against a file a newcomer has already opened and locked; the next arrival then
     *    creates a fresh file at the same path, locks *that*, and two installs run at once. This
     *    cannot be fenced from Java: telling "the file I opened" from "the file now at this path"
     *    needs `fstat` on the descriptor, and the JDK only reads attributes by path — which races
     *    with exactly the unlink it is meant to detect.
     *
     * The platform is passed as [osName] rather than as a ready-made boolean, so that the decision
     * itself - not just each of its outcomes - runs under test: a default of "delete" could
     * otherwise be reintroduced and pass every test on Windows, where deleting is correct.
     */
    internal inline fun <T> withInstallLock(
        target: File,
        osName: String? = System.getProperty("os.name"),
        block: () -> T,
    ): LockResult<T> {
        val deleteOnRelease = isWindows(osName)
        val lockFile = File(target.parentFile, target.name + LOCK_SUFFIX)
        val raf = try {
            RandomAccessFile(lockFile, "rw")
        } catch (e: IOException) {
            return LockResult.Unavailable("could not open ${lockFile.name} in the install directory")
        } catch (e: SecurityException) {
            return LockResult.Unavailable("not permitted to open ${lockFile.name} in the install directory")
        }
        // Set only once we actually hold the lock, so a contended caller never deletes the file.
        var acquired = false
        try {
            val lock = try {
                raf.channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null // held by another thread of this same JVM
            } catch (e: IOException) {
                return LockResult.Unavailable("could not lock ${lockFile.name} in the install directory")
            }
            if (lock == null) return LockResult.Contended
            acquired = true
            return LockResult.Acquired(block())
        } finally {
            // Closing the file closes its channel, which releases the lock.
            try {
                raf.close()
            } catch (e: IOException) {
            }
            // Strictly after the close: see "Cleaning up the lock file" above.
            if (acquired && deleteOnRelease) lockFile.delete()
        }
    }

    /**
     * Locate the JAR this JVM is running from, or null when not running from a JAR (e.g. tests and
     * `gradle run`, where the code source is a directory). A null result disables installation:
     * there is no single file to replace.
     */
    internal fun runningJar(): File? {
        val source = runCatching {
            UpdateInstaller::class.java.protectionDomain?.codeSource?.location?.toURI()
        }.getOrNull() ?: return null
        val file = runCatching { File(source) }.getOrNull() ?: return null
        return file.takeIf { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }
    }

    /**
     * Delete this JAR's abandoned staged downloads (`<jar>.<n>.new`, ~20 MB each) left behind by
     * processes that were killed before they could finish or apply an update.
     *
     * Best-effort and never throws. Only files older than [olderThanMillis] are removed, so a
     * download a concurrent install is still writing is left alone.
     *
     * Two sidecars are deliberately NOT swept:
     *  - the install lock (`<jar>.update.lock`). It is 0 bytes and inert, and its age says nothing
     *    about whether it is held — nothing is ever written to it, so a lock held right now can carry
     *    an mtime from months ago. On POSIX, sweeping a held one silently ends mutual exclusion; see
     *    [withInstallLock].
     *  - the pre-swap backup (`<jar>.bak`), an operator's recovery copy after a failed write.
     *
     * The running JAR has no sidecar suffix and is never a candidate.
     */
    fun sweepStaleSidecars(
        target: File,
        olderThanMillis: Long = STALE_SIDECAR_MILLIS,
        now: Long = System.currentTimeMillis(),
    ) {
        val dir = target.parentFile ?: return
        val prefix = target.name + "."
        sweepOlderThan(dir, olderThanMillis, now) { name -> name.startsWith(prefix) && name.endsWith(STAGED_SUFFIX) }
    }

    /** Longest close() waits for a heartbeat tick already in progress. A tick is one setLastModified call. */
    private const val HEARTBEAT_STOP_WAIT_MILLIS = 2_000L

    /** Name of the daemon thread that keeps a staged download alive; see [keepStagedFresh]. */
    internal const val HEARTBEAT_THREAD_NAME = "insightidr-mcp-stage-heartbeat"

    /**
     * Re-stamp [staged]'s modification time, so [sweepStaleSidecars] run by ANOTHER process started
     * from the same JAR keeps seeing it as in use. Returns false when the file is no longer there,
     * which is the caller's cue that the staged update has been lost.
     */
    internal fun touchStaged(staged: File, now: Long = System.currentTimeMillis()): Boolean {
        if (!staged.isFile) return false
        runCatching { staged.setLastModified(now) }
        return true
    }

    /**
     * Keep a staged download alive until this process exits and applies it.
     *
     * A staged file is not applied until its process *exits* — for a long-running `--http` server
     * that is days away, not the length of a download. Meanwhile every other server started from the
     * same JAR runs [sweepStaleSidecars] at startup and deletes any `.new` older than
     * [STALE_SIDECAR_MILLIS]. Without this, a second instance starting an hour later silently
     * destroys the first one's pending update, which then goes on reporting "restart to apply".
     *
     * Two defences, because neither covers every platform:
     *  - a **heartbeat** re-stamps the mtime every [intervalMillis] (a sixth of the sweep threshold,
     *    so five consecutive ticks would have to be missed). This is what protects the file on POSIX.
     *  - with [pin], the file is held open for reading. The JDK opens without `FILE_SHARE_DELETE`, so
     *    on Windows another process's delete is refused outright. On POSIX it changes nothing.
     *
     * The tick doubles as a monitor: if the file is gone anyway, [onLost] runs once and the
     * heartbeat stops. Runs on a daemon thread of its own rather than a coroutine — it has to
     * outlive every session scope, and must never be the thing keeping the JVM alive.
     *
     * Close the returned handle BEFORE applying or deleting the staged file: on Windows the pin
     * blocks this process's own delete too.
     */
    internal fun keepStagedFresh(
        staged: File,
        intervalMillis: Long = STALE_SIDECAR_MILLIS / 6,
        pin: Boolean = true,
        onLost: () -> Unit,
    ): AutoCloseable {
        val pinned = if (pin) runCatching { RandomAccessFile(staged, "r") }.getOrNull() else null
        val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, HEARTBEAT_THREAD_NAME).apply { isDaemon = true }
        }
        val reported = AtomicBoolean(false)
        scheduler.scheduleWithFixedDelay(
            {
                if (!touchStaged(staged) && reported.compareAndSet(false, true)) {
                    runCatching(onLost)
                    scheduler.shutdown()
                }
            },
            intervalMillis,
            intervalMillis,
            TimeUnit.MILLISECONDS,
        )
        return AutoCloseable {
            scheduler.shutdownNow()
            // Wait for a tick that is already running. Without this, close() can return while the
            // heartbeat is still about to re-stamp the file - and what follows close() is the
            // shutdown swap, which overwrites and deletes it.
            runCatching { scheduler.awaitTermination(HEARTBEAT_STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS) }
            runCatching { pinned?.close() }
        }
    }

    /**
     * Verify [file] is a genuine JAR that carries this server's entry point.
     *
     * A correct hash only proves the bytes match what GitHub advertised; it does not prove they are
     * *this program*. Checking for the main class stops a well-formed but wrong artifact from being
     * installed and leaving an unstartable server behind.
     */
    internal fun looksLikeServerJar(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.getEntry("com/jitunicornfx/insightidr/mcp/MainKt.class") != null
        }
    }.getOrDefault(false)

    /**
     * Download [asset], following GitHub's CDN redirect by hand with a host allow-list at every hop,
     * and write it to [destination]. Returns the SHA-256 of the bytes actually written, or null on
     * any failure. The stream is hashed and size-capped as it is written, so an over-long or
     * truncated response never reaches the verification step with a passing digest.
     */
    internal suspend fun downloadTo(
        asset: UpdateChecker.ReleaseAsset,
        destination: File,
        engine: HttpClientEngine? = null,
    ): String? = runCatching {
        val http = if (engine != null) HttpClient(engine) { configure() } else HttpClient(OkHttp) { configure() }
        http.use { client ->
            var url = asset.downloadUrl
            // One request for the asset itself, plus at most MAX_REDIRECTS hops after it.
            repeat(MAX_REDIRECTS + 1) {
                if (!UpdateChecker.isAllowedDownloadUrl(url)) return null
                // execute { }, not get(): get() buffers the ENTIRE body in memory before returning, so
                // the size cap below would only ever see bytes that had already been accepted - a
                // chunked multi-gigabyte answer is an OutOfMemoryError in a server handling tool calls.
                val hop = client.prepareGet(url) {
                    header(HttpHeaders.Accept, "application/octet-stream")
                    header(HttpHeaders.UserAgent, "$SERVER_NAME/$SERVER_VERSION")
                }.execute { response ->
                    if (response.status.value in 300..399) Hop.Redirect(response.headers[HttpHeaders.Location])
                    else Hop.Final(streamVerified(response, asset, destination))
                }
                when (hop) {
                    is Hop.Final -> return hop.digest
                    // Follow the CDN hop ourselves so the allow-list is re-checked for the target.
                    is Hop.Redirect -> url = hop.location ?: return null
                }
            }
            null // still being redirected after MAX_REDIRECTS hops
        }
    }.getOrElse { failure ->
        // The caller's own cancellation is not a failed download; see UpdateChecker.check.
        if (failure is CancellationException) currentCoroutineContext().ensureActive()
        null
    }

    /** What one request in the redirect chain turned out to be. */
    private sealed interface Hop {
        data class Redirect(val location: String?) : Hop
        data class Final(val digest: String?) : Hop
    }

    /**
     * Write a final (non-redirect) [response] to [destination], hashing and size-capping it as it
     * streams. Returns the SHA-256 of exactly the bytes written, or null when the response is not a
     * success or its length disagrees with what the release advertised.
     */
    private suspend fun streamVerified(
        response: HttpResponse,
        asset: UpdateChecker.ReleaseAsset,
        destination: File,
    ): String? {
        if (response.status.value !in 200..299) return null
        val declared = response.contentLength()
        if (declared != null && declared > asset.sizeBytes) return null

        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val channel = response.bodyAsChannel()
        FileOutputStream(destination).use { file ->
            val out = file.buffered()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                if (read == 0) continue
                written += read
                // Hard-stop a response that exceeds the advertised size rather than buffering an
                // unbounded stream to disk.
                if (written > asset.sizeBytes) return null
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
            }
            out.flush()
            // On disk, not merely in the page cache, before anything is renamed over the JAR.
            runCatching { file.fd.sync() }
        }
        if (written != asset.sizeBytes) return null
        return digest.digest().toHex()
    }

    /**
     * Give [to] the POSIX permissions of [from]. Returns whether anything was applied: on a
     * filesystem without POSIX permissions (Windows) there is nothing to copy, and that is fine.
     */
    internal fun copyPermissions(from: File, to: File): Boolean = runCatching {
        Files.setPosixFilePermissions(to.toPath(), Files.getPosixFilePermissions(from.toPath()))
        true
    }.getOrDefault(false)

    /**
     * Replace [target] with [staged].
     *
     * Prefers an atomic move, which on POSIX swaps the directory entry while the running JVM keeps
     * reading the old inode through its open handle — the safe case. Windows refuses to rename or
     * move over a JAR the current JVM is running from (measured), so there the move fails and the
     * caller falls back to swapping at shutdown.
     */
    internal fun tryAtomicSwap(staged: File, target: File): Boolean = runCatching {
        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrElse { error ->
        if (error is AtomicMoveNotSupportedException) {
            runCatching {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            }.getOrDefault(false)
        } else {
            false
        }
    }

    /**
     * Overwrite [target]'s contents in place from [staged], keeping a backup so a partial write can
     * be rolled back.
     *
     * This is the Windows path and is intended for **JVM shutdown**, once class loading has settled:
     * rewriting the bytes of a JAR a running JVM has open can otherwise break lazily-loaded classes.
     * Returns true only when the new contents are fully in place.
     */
    internal fun overwriteInPlace(staged: File, target: File, expectedSha256: String): Boolean {
        val backup = File(target.parentFile, target.name + BACKUP_SUFFIX)

        // Capture the ORIGINAL before touching it — this is the only copy of the currently working
        // server, and it is what a failed write must be rolled back to.
        val backedUp = runCatching { target.copyTo(backup, overwrite = true) }.isSuccess &&
            backup.length() == target.length()
        if (!backedUp) {
            System.err.println("[insightidr-mcp] Could not back up ${target.name}; refusing to overwrite it.")
            return false
        }

        /** Stream [source] over [destination]'s existing path (truncating open, never unlink). */
        fun writeOver(source: File, destination: File): Boolean = runCatching {
            source.inputStream().buffered().use { input ->
                // Truncating open() on the existing path is permitted on Windows even while the JVM
                // holds the JAR, unlike rename/move — and it keeps the path's identity intact.
                FileOutputStream(destination).use { file ->
                    val output = file.buffered()
                    input.copyTo(output)
                    output.flush()
                    // Forced to disk BEFORE the caller deletes the backup. Verified from the page cache
                    // and then lost to a power cut, the JAR would be garbage with no .bak to restore.
                    file.fd.sync()
                }
            }
            true
        }.getOrDefault(false)

        val wrote = writeOver(staged, target)
        // Trust nothing: confirm the bytes now on disk are exactly what was verified.
        if (wrote && sha256Of(target) == expectedSha256) {
            runCatching { backup.delete() }
            runCatching { staged.delete() }
            return true
        }

        // The write failed or produced the wrong bytes. First: was the JAR touched at all? If it could
        // not even be opened for writing (read-only attribute, an antivirus handle) it is exactly as
        // it was, and "could not be restored - recover it from the backup" would be a false alarm
        // telling the operator to repair a file that is fine.
        val backupSha = sha256Of(backup)
        if (backupSha != null && sha256Of(target) == backupSha) {
            System.err.println(
                "[insightidr-mcp] Could not write to ${target.name}, so the update was not applied. The installed " +
                    "JAR is unchanged. Check that it is not read-only or held open by another program.",
            )
            runCatching { backup.delete() }
            return false
        }

        // It was changed. Restore the original in place, and KEEP the backup and the staged file
        // either way so an operator always has something to recover from.
        val restored = writeOver(backup, target) && backupSha != null && sha256Of(target) == backupSha
        System.err.println(
            if (restored) {
                "[insightidr-mcp] Update write failed; restored the previous ${target.name}. " +
                    "A copy remains at ${backup.absolutePath}."
            } else {
                "[insightidr-mcp] Update write failed AND the original could not be restored. " +
                    "Recover ${target.absolutePath} from ${backup.absolutePath} before restarting."
            },
        )
        return false
    }

    /**
     * Download, verify and install [result]'s asset over the running JAR.
     *
     * Returns an [Outcome] describing what happened; never throws. When the target cannot be
     * replaced while running (Windows), the verified download is left staged and
     * [installStagedAtShutdown] completes the swap as the JVM exits.
     */
    suspend fun install(
        result: UpdateChecker.Result,
        engine: HttpClientEngine? = null,
        targetJar: File? = runningJar(),
    ): Outcome = withContext(Dispatchers.IO) {
        val version = result.latestVersion ?: return@withContext Outcome.Skipped
        val asset = result.asset
            ?: return@withContext Outcome.Failed("release $version publishes no verifiable .jar asset")
        val target = targetJar
            ?: return@withContext Outcome.Failed("not running from a .jar, so there is nothing to replace")

        // Serialise the whole download-verify-swap against other processes started from this JAR.
        val locked = try {
            withInstallLock(target) { downloadVerifyAndSwap(version, asset, target, engine) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // "Never throws" is this function's contract. The text is fixed because an exception
            // message raised in here could carry a filesystem path.
            return@withContext Outcome.Failed("installing $version failed unexpectedly")
        }
        when (locked) {
            is LockResult.Acquired -> locked.value
            LockResult.Contended -> Outcome.Failed("another process is already installing an update")
            is LockResult.Unavailable -> Outcome.Failed("could not take the install lock: ${locked.reason}")
        }
    }

    /**
     * The body of [install], run while holding the install lock. The staged file is removed on every
     * path that does not hand it on: a refusal, an exception, or cancellation mid-download.
     */
    private suspend fun downloadVerifyAndSwap(
        version: String,
        asset: UpdateChecker.ReleaseAsset,
        target: File,
        engine: HttpClientEngine?,
    ): Outcome {
        // A unique staging file, created exclusively: two processes can never share one, and a
        // pre-existing file left by someone else is never adopted or deleted.
        val staged = runCatching {
            Files.createTempFile(target.parentFile.toPath(), "${target.name}.", STAGED_SUFFIX)
        }.getOrNull()?.toFile() ?: return Outcome.Failed("could not create a staging file next to ${target.name}")

        fun fail(reason: String): Outcome {
            runCatching { staged.delete() }
            return Outcome.Failed(reason)
        }

        try {
            val streamedDigest = downloadTo(asset, staged, engine)
                ?: return fail("download of $version failed or exceeded its advertised size")

            // Two checks, deliberately: the streamed digest catches a tampered/truncated transfer,
            // and re-hashing the file catches anything that touched it after it was written. The
            // value that authorises the swap is always computed from the bytes on disk.
            if (!streamedDigest.equals(asset.sha256, ignoreCase = true)) {
                return fail("SHA-256 mismatch for $version — refusing to install")
            }
            val onDisk = sha256Of(staged)
            if (onDisk == null || !onDisk.equals(asset.sha256, ignoreCase = true)) {
                return fail("staged $version does not match its published SHA-256 — refusing to install")
            }
            if (!looksLikeServerJar(staged)) {
                return fail("downloaded $version is not a valid server JAR — refusing to install")
            }

            // createTempFile makes the staged file rw------- on POSIX, and the move would carry that
            // mode onto the installed JAR: the next user to launch it gets "Unable to access jarfile".
            copyPermissions(from = target, to = staged)
            return if (tryAtomicSwap(staged, target)) {
                Outcome.Installed(version, target.absolutePath)
            } else {
                Outcome.Staged(version, staged.absolutePath, asset.sha256, replacesSha256 = sha256Of(target))
            }
        } catch (e: Throwable) {
            runCatching { staged.delete() }
            throw e
        }
    }

    /**
     * Complete a [Outcome.Staged] install as the JVM exits, when overwriting the JAR in place is
     * safe because class loading is finished. Best-effort, but never silent: an update that was
     * promised and then not applied is said so on stderr, since nothing else will ever mention it.
     */
    fun installStagedAtShutdown(
        staged: File,
        target: File,
        expectedSha256: String,
        /** [Outcome.Staged.replacesSha256]: what the installed JAR was when this update was staged. */
        replacesSha256: String? = null,
    ): Boolean {
        if (!staged.isFile) {
            System.err.println(
                "[insightidr-mcp] The staged update is no longer on disk, so nothing was applied. " +
                    "It will be downloaded again on the next start.",
            )
            return false
        }
        // The staged file has been sitting on disk for the whole session, so re-verify it against
        // the digest that authorised it. looksLikeServerJar is an anti-brick sanity check, never the
        // integrity gate — any ZIP carrying the right entry name would satisfy it.
        val onDisk = sha256Of(staged)
        if (onDisk == null || !onDisk.equals(expectedSha256, ignoreCase = true)) {
            System.err.println("[insightidr-mcp] Staged update no longer matches its verified digest; discarding it.")
            runCatching { staged.delete() }
            return false
        }
        if (!looksLikeServerJar(staged)) {
            System.err.println("[insightidr-mcp] Staged update is not a valid server JAR; discarding it.")
            runCatching { staged.delete() }
            return false
        }
        // Only swap when no other process from this JAR is mid-install. Anything other than a clean
        // acquisition leaves the staged file in place for the next start.
        val locked = runCatching {
            withInstallLock(target) {
                when {
                    // Staged days ago against a JAR that has since been replaced - by a sibling that
                    // installed something NEWER. Writing this copy over it would be a silent downgrade,
                    // possibly past a security fix.
                    replacesSha256 != null && sha256Of(target) != replacesSha256 -> {
                        System.err.println(
                            "[insightidr-mcp] ${target.name} has changed since this update was staged (another " +
                                "instance updated it), so the staged copy is stale and was discarded.",
                        )
                        runCatching { staged.delete() }
                        false
                    }

                    else -> withSoleUse(target) { overwriteInPlace(staged, target, expectedSha256) } ?: run {
                        System.err.println(
                            "[insightidr-mcp] Other servers are still running from ${target.name}, so it was not " +
                                "rewritten underneath them. The update will be fetched again on a later start.",
                        )
                        false
                    }
                }
            }
        }.getOrNull()
        return (locked as? LockResult.Acquired)?.value ?: false
    }

    private fun HttpClientConfig<*>.configure() {
        expectSuccess = false
        // Redirects are resolved manually so the host allow-list applies to every hop.
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS
            connectTimeoutMillis = UpdateChecker.TIMEOUT_MS
            socketTimeoutMillis = DOWNLOAD_TIMEOUT_MS
        }
    }
}
