package com.jitunicornfx.insightidr.mcp

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the component that installs code. The security-critical assertions here are the ones
 * that prove a *bad* download is refused: a wrong digest, a non-GitHub host, a wrong artifact, or a
 * size that disagrees with what the release advertised must never reach the installed JAR.
 */
class UpdateInstallerTest {

    private val tempDir: File = createTempDirectory("installer-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    /** Build a minimal but genuine JAR carrying the server's entry point. */
    private fun serverJar(file: File, marker: String = "v1"): File {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("com/jitunicornfx/insightidr/mcp/MainKt.class"))
            zip.write(marker.toByteArray())
            zip.closeEntry()
        }
        return file
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun assetFor(file: File, url: String = "https://github.com/jitunicornfx/Rapid7-InsightIDR-MCP/releases/download/v9.9.9/app-all.jar") =
        UpdateChecker.ReleaseAsset(
            name = "rapid7-insightidr-mcp-9.9.9-all.jar",
            downloadUrl = url,
            sha256 = sha256(file),
            sizeBytes = file.length(),
        )

    private fun engineServing(bytes: ByteArray) = MockEngine {
        respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, bytes.size.toString()))
    }

    // ---------------------------------------------------------------------
    // Download + verification
    // ---------------------------------------------------------------------

    @Test
    fun `a good download is verified and installed over the target`() = runBlocking {
        val source = serverJar(File(tempDir, "source.jar"), marker = "NEW")
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        val result = UpdateChecker.Result(
            updateAvailable = true, currentVersion = "0.1.6", latestVersion = "9.9.9",
            asset = assetFor(source),
        )

        val outcome = UpdateInstaller.install(result, engineServing(source.readBytes()), target)

        assertTrue(outcome is UpdateInstaller.Outcome.Installed, "expected an install, got $outcome")
        assertEquals(sha256(source), sha256(target), "target must now be the downloaded bytes")
        assertEquals(emptyList(), tempDir.listFiles()!!.filter { it.name.endsWith(UpdateInstaller.STAGED_SUFFIX) }
            .map { it.name }, "no staging file may be left behind")
    }

    @Test
    fun `an over-long download is cut off as it streams, not after it has all been written`() = runBlocking {
        // No Content-Length, so the declared-size check cannot help, and a body whose digest would be
        // wrong anyway - which is why "the install failed" proves nothing about the cut-off. What
        // shows it is how much reached the disk: a full disk is the harm being prevented.
        val source = serverJar(File(tempDir, "source.jar"))
        val asset = assetFor(source).copy(sizeBytes = 10)
        val endless = ByteArray(8 * 1024 * 1024) { 'A'.code.toByte() }
        val engine = MockEngine { respond(io.ktor.utils.io.ByteReadChannel(endless), HttpStatusCode.OK) }
        val destination = File(tempDir, "staged.new")

        val digest = UpdateInstaller.downloadTo(asset, destination, engine)

        assertNull(digest, "an over-long body has no acceptable digest")
        assertTrue(destination.length() < 1024 * 1024, "wrote ${destination.length()} bytes of an 8 MiB body advertised as 10")
    }

    @Test
    fun `cancelling a download stops it, rather than turning into a failed download`() = runBlocking {
        // install() wraps this in withContext, which rethrows cancellation on its own account - so a
        // test of install() passes even if downloadTo swallows it. This is the function itself.
        val source = serverJar(File(tempDir, "source.jar"))
        val started = CompletableDeferred<Unit>()
        val hanging = MockEngine {
            started.complete(Unit)
            awaitCancellation()
        }
        val ranPastTheDownload = AtomicBoolean(false)

        val job = launch(Dispatchers.Default) {
            UpdateInstaller.downloadTo(assetFor(source), File(tempDir, "staged.new"), hanging)
            ranPastTheDownload.set(true)
        }
        started.await()
        job.cancelAndJoin()

        assertFalse(ranPastTheDownload.get(), "a cancelled download must not return null as if it had merely failed")
    }

    @Test
    fun `a digest mismatch refuses to install and leaves the original intact`() = runBlocking {
        val source = serverJar(File(tempDir, "source.jar"), marker = "NEW")
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        val originalDigest = sha256(target)
        // Advertise a digest that does not match the bytes actually served.
        val asset = assetFor(source).copy(sha256 = "0".repeat(64))
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = asset)

        val outcome = UpdateInstaller.install(result, engineServing(source.readBytes()), target)

        assertTrue(outcome is UpdateInstaller.Outcome.Failed)
        assertTrue("SHA-256" in outcome.reason)
        assertEquals(originalDigest, sha256(target), "a failed verification must not touch the installed JAR")
        assertEquals(emptyList(), tempDir.listFiles()!!.filter { it.name.endsWith(UpdateInstaller.STAGED_SUFFIX) }
            .map { it.name }, "a rejected download leaves no staging file")
    }

    @Test
    fun `bytes that are not a server JAR are refused even with a matching digest`() = runBlocking {
        // Correct hash, wrong artifact: a valid ZIP without the server's entry point.
        val impostor = File(tempDir, "impostor.jar")
        ZipOutputStream(impostor.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("evil/Payload.class")); zip.write("x".toByteArray()); zip.closeEntry()
        }
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        val originalDigest = sha256(target)
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = assetFor(impostor))

        val outcome = UpdateInstaller.install(result, engineServing(impostor.readBytes()), target)

        assertTrue(outcome is UpdateInstaller.Outcome.Failed)
        assertTrue("not a valid server JAR" in outcome.reason)
        assertEquals(originalDigest, sha256(target))
    }

    @Test
    fun `a download larger than advertised is aborted`() = runBlocking {
        val source = serverJar(File(tempDir, "source.jar"))
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        val originalDigest = sha256(target)
        // Advertise a small size, then serve far more bytes.
        val asset = assetFor(source).copy(sizeBytes = 10)
        val oversized = ByteArray(500_000) { 'A'.code.toByte() }
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = asset)

        val outcome = UpdateInstaller.install(result, engineServing(oversized), target)

        assertTrue(outcome is UpdateInstaller.Outcome.Failed, "an over-long response must abort")
        assertEquals(originalDigest, sha256(target))
    }

    @Test
    fun `a download is never fetched from a non-GitHub host`() = runBlocking {
        val source = serverJar(File(tempDir, "source.jar"))
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        val asset = assetFor(source, url = "https://attacker.example/evil-all.jar")
        val engine = engineServing(source.readBytes())
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = asset)

        val outcome = UpdateInstaller.install(result, engine, target)

        assertTrue(outcome is UpdateInstaller.Outcome.Failed)
        assertEquals(0, engine.requestHistory.size, "no request may be sent to a disallowed host")
    }

    @Test
    fun `a redirect to a non-GitHub host is not followed`() = runBlocking {
        val source = serverJar(File(tempDir, "source.jar"))
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        val originalDigest = sha256(target)
        val engine = MockEngine { request ->
            if (request.url.host == "github.com") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://attacker.example/evil.jar"))
            } else {
                respond(source.readBytes(), HttpStatusCode.OK)
            }
        }
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = assetFor(source))

        val outcome = UpdateInstaller.install(result, engine, target)

        assertTrue(outcome is UpdateInstaller.Outcome.Failed, "redirect off GitHub must abort the download")
        assertEquals(1, engine.requestHistory.size, "only the original request is made")
        assertEquals(originalDigest, sha256(target))
    }

    @Test
    fun `a redirect to the GitHub CDN is followed and installed`() = runBlocking {
        val source = serverJar(File(tempDir, "source.jar"), marker = "NEW")
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        val engine = MockEngine { request ->
            if (request.url.host == "github.com") {
                respond(
                    "", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://objects.githubusercontent.com/release/app-all.jar"),
                )
            } else {
                respond(source.readBytes(), HttpStatusCode.OK)
            }
        }
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = assetFor(source))

        val outcome = UpdateInstaller.install(result, engine, target)

        assertTrue(outcome is UpdateInstaller.Outcome.Installed, "expected install, got $outcome")
        assertEquals(2, engine.requestHistory.size, "original request plus the CDN hop")
        assertEquals(sha256(source), sha256(target))
    }

    @Test
    fun `install is skipped when the release publishes no verifiable asset`() = runBlocking {
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = null)
        val outcome = UpdateInstaller.install(result, engineServing(ByteArray(0)), target)
        assertTrue(outcome is UpdateInstaller.Outcome.Failed)
        assertTrue("no verifiable" in outcome.reason)
    }

    // ---------------------------------------------------------------------
    // Swap mechanics
    // ---------------------------------------------------------------------

    @Test
    fun `overwriteInPlace replaces contents and removes the staged file`() {
        val staged = serverJar(File(tempDir, "staged.jar"), marker = "NEW")
        val target = serverJar(File(tempDir, "target.jar"), marker = "OLD")
        val stagedDigest = sha256(staged)

        assertTrue(UpdateInstaller.overwriteInPlace(staged, target, sha256(staged)))

        assertEquals(stagedDigest, sha256(target), "target must carry the new bytes")
        assertFalse(staged.exists(), "staged file consumed")
        assertFalse(File(tempDir, "target.jar${UpdateInstaller.BACKUP_SUFFIX}").exists(), "backup cleaned up")
    }

    @Test
    fun `installStagedAtShutdown refuses a staged file that is not a server JAR`() {
        val staged = File(tempDir, "staged.jar").apply { writeText("not a jar at all") }
        val target = serverJar(File(tempDir, "target.jar"), marker = "OLD")
        val originalDigest = sha256(target)

        assertFalse(UpdateInstaller.installStagedAtShutdown(staged, target, sha256(staged)))
        assertEquals(originalDigest, sha256(target), "a bogus staged file must never be swapped in")
    }

    @Test
    fun `installStagedAtShutdown is a no-op when nothing is staged`() {
        val target = serverJar(File(tempDir, "target.jar"))
        assertFalse(UpdateInstaller.installStagedAtShutdown(File(tempDir, "absent.jar"), target, "0".repeat(64)))
    }

    @Test
    fun `looksLikeServerJar accepts a real server jar and rejects other files`() {
        assertTrue(UpdateInstaller.looksLikeServerJar(serverJar(File(tempDir, "ok.jar"))))
        assertFalse(UpdateInstaller.looksLikeServerJar(File(tempDir, "text.jar").apply { writeText("nope") }))
        assertFalse(UpdateInstaller.looksLikeServerJar(File(tempDir, "missing.jar")))
    }

    @Test
    fun `install is skipped when there is no newer version`() = runBlocking {
        val target = serverJar(File(tempDir, "installed.jar"))
        val result = UpdateChecker.Result(updateAvailable = false, currentVersion = "0.1.6")
        assertEquals(UpdateInstaller.Outcome.Skipped, UpdateInstaller.install(result, engineServing(ByteArray(0)), target))
    }

    @Test
    fun `install fails cleanly when not running from a jar`() = runBlocking {
        val source = serverJar(File(tempDir, "source.jar"))
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = assetFor(source))
        val outcome = UpdateInstaller.install(result, engineServing(source.readBytes()), targetJar = null)
        assertTrue(outcome is UpdateInstaller.Outcome.Failed)
        assertTrue("nothing to replace" in outcome.reason)
    }

    @Test
    fun `a redirect loop is abandoned rather than followed forever`() = runBlocking {
        val source = serverJar(File(tempDir, "source.jar"))
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        // Always redirect, staying on an allowed host so only the hop limit can stop it.
        val engine = MockEngine {
            respond(
                "", HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://objects.githubusercontent.com/next"),
            )
        }
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = assetFor(source))

        val outcome = UpdateInstaller.install(result, engine, target)

        assertTrue(outcome is UpdateInstaller.Outcome.Failed)
        // Exactly: the asset request plus MAX_REDIRECTS hops. Fewer would mean a legitimate CDN chain
        // is abandoned early; more would mean the limit is not the limit.
        assertEquals(
            UpdateInstaller.MAX_REDIRECTS + 1,
            engine.requestHistory.size,
            "must follow ${UpdateInstaller.MAX_REDIRECTS} hops and then stop",
        )
    }

    @Test
    fun `a redirect without a location header aborts`() = runBlocking {
        val source = serverJar(File(tempDir, "source.jar"))
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        val originalDigest = sha256(target)
        val engine = MockEngine { respond("", HttpStatusCode.Found) }
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = assetFor(source))

        assertTrue(UpdateInstaller.install(result, engine, target) is UpdateInstaller.Outcome.Failed)
        assertEquals(originalDigest, sha256(target))
    }

    @Test
    fun `an http error response installs nothing`() = runBlocking {
        val source = serverJar(File(tempDir, "source.jar"))
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        val originalDigest = sha256(target)
        val engine = MockEngine { respond("not found", HttpStatusCode.NotFound) }
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = assetFor(source))

        assertTrue(UpdateInstaller.install(result, engine, target) is UpdateInstaller.Outcome.Failed)
        assertEquals(originalDigest, sha256(target))
    }

    @Test
    fun `a truncated download is refused`() = runBlocking {
        val source = serverJar(File(tempDir, "source.jar"))
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        val originalDigest = sha256(target)
        // Serve fewer bytes than the release advertised.
        val truncated = source.readBytes().copyOfRange(0, source.readBytes().size / 2)
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = assetFor(source))

        val outcome = UpdateInstaller.install(result, engineServing(truncated), target)

        assertTrue(outcome is UpdateInstaller.Outcome.Failed, "a short download must not be installed")
        assertEquals(originalDigest, sha256(target))
    }

    @Test
    fun `tryAtomicSwap moves the staged file over the target`() {
        val staged = serverJar(File(tempDir, "staged.jar"), marker = "NEW")
        val target = serverJar(File(tempDir, "target.jar"), marker = "OLD")
        val stagedDigest = sha256(staged)

        assertTrue(UpdateInstaller.tryAtomicSwap(staged, target))

        assertEquals(stagedDigest, sha256(target))
        assertFalse(staged.exists(), "the staged file is consumed by the move")
    }

    // ---------------------------------------------------------------------
    // Regressions for confirmed review findings
    // ---------------------------------------------------------------------

    @Test
    fun `the backup preserves the ORIGINAL jar, so a failed write can be rolled back`() {
        // Regression: the backup used to copy the *staged* file, so the original was never captured
        // and the "rollback" re-applied the bytes that had just failed.
        val staged = serverJar(File(tempDir, "staged.jar"), marker = "NEW")
        val target = serverJar(File(tempDir, "target.jar"), marker = "ORIGINAL")
        val originalDigest = sha256(target)

        // Force the verification after the write to fail by advertising a digest that cannot match.
        assertFalse(UpdateInstaller.overwriteInPlace(staged, target, "0".repeat(64)))

        assertEquals(originalDigest, sha256(target), "the original JAR must be restored byte-for-byte")
        val backup = File(tempDir, "target.jar${UpdateInstaller.BACKUP_SUFFIX}")
        assertTrue(backup.exists(), "recovery material must survive a failure")
        assertEquals(originalDigest, sha256(backup), "the backup must hold the ORIGINAL, not the new bytes")
    }

    @Test
    fun `a staged file altered after verification is never installed`() {
        // Regression: the digest used to be computed over the network stream only, so anything that
        // rewrote the staged file between verification and the swap was installed unchecked.
        val staged = serverJar(File(tempDir, "staged.jar"), marker = "VERIFIED")
        val verifiedDigest = sha256(staged)
        val target = serverJar(File(tempDir, "target.jar"), marker = "ORIGINAL")
        val originalDigest = sha256(target)

        // An attacker (or a second process) swaps the staged file for a different valid server JAR.
        serverJar(staged, marker = "TAMPERED")
        assertTrue(sha256(staged) != verifiedDigest, "precondition: the staged file changed")

        assertFalse(
            UpdateInstaller.installStagedAtShutdown(staged, target, verifiedDigest),
            "a staged file that no longer matches its verified digest must be refused",
        )
        assertEquals(originalDigest, sha256(target), "the running JAR must be untouched")
        assertFalse(staged.exists(), "the tampered staging file is discarded")
    }

    @Test
    fun `overwriteInPlace verifies what it actually wrote`() {
        val staged = serverJar(File(tempDir, "staged.jar"), marker = "NEW")
        val target = serverJar(File(tempDir, "target.jar"), marker = "ORIGINAL")

        assertTrue(UpdateInstaller.overwriteInPlace(staged, target, sha256(staged)))
        assertFalse(
            File(tempDir, "target.jar${UpdateInstaller.BACKUP_SUFFIX}").exists(),
            "a verified success cleans up its backup",
        )
    }

    @Test
    fun `an install is refused while another process holds the lock`() = runBlocking {
        val source = serverJar(File(tempDir, "source.jar"))
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        val originalDigest = sha256(target)
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = assetFor(source))

        // Hold the cross-process install lock, exactly as a second server process would.
        val held = UpdateInstaller.withInstallLock(target) {
            UpdateInstaller.install(result, engineServing(source.readBytes()), target)
        }

        val outcome = assertIs<UpdateInstaller.LockResult.Acquired<UpdateInstaller.Outcome>>(held).value
        assertIs<UpdateInstaller.Outcome.Failed>(outcome, "a contended install must not proceed")
        assertTrue("another process" in outcome.reason)
        assertEquals(originalDigest, sha256(target), "the JAR is untouched while the lock is held")
    }

    @Test
    fun `each install stages to its own file rather than a shared predictable path`() = runBlocking {
        // Regression: a fixed "<jar>.new" path let concurrent processes clobber each other's download.
        val source = serverJar(File(tempDir, "source.jar"))
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        // A leftover file at the old predictable path must be neither adopted nor deleted.
        val squatter = File(tempDir, "installed.jar${UpdateInstaller.STAGED_SUFFIX}")
        squatter.writeText("someone else's file")
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = assetFor(source))

        UpdateInstaller.install(result, engineServing(source.readBytes()), target)

        assertTrue(squatter.exists(), "another process's file must not be deleted")
        assertEquals("someone else's file", squatter.readText(), "nor overwritten")
    }

    @Test
    fun `runningJar returns null under the test runner, disabling installation`() {
        // Tests run from a classes directory, not a JAR — there is no single file to replace.
        assertNull(UpdateInstaller.runningJar())
    }

    // ---------------------------------------------------------------------
    // Stale-sidecar cleanup
    // ---------------------------------------------------------------------

    @Test
    fun `sweepStaleSidecars removes old staged files but keeps the jar, the lock, fresh files, and the backup`() {
        val target = serverJar(File(tempDir, "app.jar"))
        val old = System.currentTimeMillis() - 2 * 60 * 60 * 1000  // 2h ago, past the 1h threshold

        val oldStaged = File(tempDir, "app.jar.111.new").apply { writeText("x"); setLastModified(old) }
        val oldStaged2 = File(tempDir, "app.jar.222.new").apply { writeText("x"); setLastModified(old) }
        val oldLock = File(tempDir, "app.jar${UpdateInstaller.LOCK_SUFFIX}").apply { writeText(""); setLastModified(old) }

        val freshStaged = File(tempDir, "app.jar.333.new").apply { writeText("x") }  // just created -> in-flight
        val backup = File(tempDir, "app.jar${UpdateInstaller.BACKUP_SUFFIX}").apply { writeText("orig"); setLastModified(old) }
        val otherJarSidecar = File(tempDir, "other.jar.444.new").apply { writeText("x"); setLastModified(old) }
        val unrelated = File(tempDir, "notes.txt").apply { writeText("x"); setLastModified(old) }

        UpdateInstaller.sweepStaleSidecars(target)

        assertFalse(oldStaged.exists(), "an old staged .new must be removed")
        assertFalse(oldStaged2.exists(), "an old staged .new must be removed")
        // Age says nothing about whether a lock is held: nothing is ever written to the file, so a
        // lock held right now can look months old. On POSIX, sweeping it ends mutual exclusion.
        assertTrue(oldLock.exists(), "the install lock is never swept, however old it looks")
        assertTrue(target.exists(), "the JAR itself is never a sweep candidate")
        assertTrue(freshStaged.exists(), "a fresh (in-flight) staged file must be preserved")
        assertTrue(backup.exists(), "the .bak recovery copy must be preserved")
        assertTrue(otherJarSidecar.exists(), "another JAR's sidecars must not be touched")
        assertTrue(unrelated.exists(), "unrelated files must not be touched")
    }

    @Test
    fun `sweepStaleSidecars respects the age threshold and never throws on an empty directory`() {
        val target = serverJar(File(tempDir, "app.jar"))
        val recentLock = File(tempDir, "app.jar${UpdateInstaller.LOCK_SUFFIX}").apply { writeText("") }

        // Effectively-infinite threshold: nothing is old enough, so nothing is swept.
        UpdateInstaller.sweepStaleSidecars(target, olderThanMillis = Long.MAX_VALUE)
        assertTrue(recentLock.exists())

        // An empty directory must be a silent no-op, not an exception.
        val emptyDir = File(tempDir, "empty").apply { mkdirs() }
        UpdateInstaller.sweepStaleSidecars(File(emptyDir, "nothere.jar"))
    }

    // ---------------------------------------------------------------------
    // The shutdown swap rewrites the JAR's bytes in place. It must not do that to anyone else.
    // ---------------------------------------------------------------------

    /** Capture stderr around [block]: the swap runs in a shutdown hook, where stderr is all there is. */
    private fun <T> capturingStderr(block: () -> T): Pair<T, String> {
        val realErr = System.err
        val captured = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(captured, true))
        try {
            return block() to captured.toString()
        } finally {
            System.setErr(realErr)
        }
    }

    @Test
    fun `the JAR is not rewritten underneath another server that is still running from it`() {
        // One stdio server per MCP client is the normal deployment: several JVMs, one JAR. They load
        // classes from it lazily, so new bytes underneath them are a ZipException or a
        // NoClassDefFoundError in the middle of someone's investigation.
        val target = serverJar(File(tempDir, "app.jar"), marker = "OLD")
        val staged = serverJar(File(tempDir, "app.jar.1.new"), marker = "NEW")
        val before = sha256(target)
        val sibling = UpdateInstaller.markInUse(target)!!

        val (applied, log) = capturingStderr { UpdateInstaller.installStagedAtShutdown(staged, target, sha256(staged)) }

        assertFalse(applied)
        assertEquals(before, sha256(target), "the JAR a sibling is running from must be left exactly as it is")
        assertTrue("still running" in log, log)

        // Once the last other server has gone, the swap goes ahead.
        sibling.close()
        assertTrue(UpdateInstaller.installStagedAtShutdown(staged, target, sha256(staged)))
        assertEquals(sha256(serverJar(File(tempDir, "expected.jar"), marker = "NEW")), sha256(target))
    }

    @Test
    fun `an update staged against a JAR that has since been replaced is discarded, not applied`() {
        // A long-running server staged v2 days ago. Since then a sibling installed v3. Applying the
        // staged v2 on exit would silently downgrade the installation, possibly past a security fix.
        val target = serverJar(File(tempDir, "app.jar"), marker = "v1")
        val whatWasThereWhenStaged = sha256(target)
        val staged = serverJar(File(tempDir, "app.jar.1.new"), marker = "v2")
        serverJar(target, marker = "v3")
        val v3 = sha256(target)

        val (applied, log) = capturingStderr {
            UpdateInstaller.installStagedAtShutdown(staged, target, sha256(staged), replacesSha256 = whatWasThereWhenStaged)
        }

        assertFalse(applied)
        assertEquals(v3, sha256(target), "the newer JAR must survive")
        assertFalse(staged.exists(), "and the stale copy is removed")
        assertTrue("stale" in log, log)
    }

    @Test
    fun `an update staged against the JAR that is still there is applied`() {
        val target = serverJar(File(tempDir, "app.jar"), marker = "v1")
        val staged = serverJar(File(tempDir, "app.jar.1.new"), marker = "v2")
        assertTrue(UpdateInstaller.installStagedAtShutdown(staged, target, sha256(staged), replacesSha256 = sha256(target)))
    }

    @Test
    fun `a JAR that could not be opened for writing is reported as unchanged, not as needing recovery`() {
        // Read-only, or held by an antivirus scan. Nothing was truncated, so the JAR is fine - but the
        // message used to say "the original could not be restored; recover it from the backup".
        val target = serverJar(File(tempDir, "app.jar"), marker = "OLD")
        val staged = serverJar(File(tempDir, "app.jar.1.new"), marker = "NEW")
        val before = sha256(target)
        assertTrue(target.setReadOnly())
        try {
            val (applied, log) = capturingStderr { UpdateInstaller.overwriteInPlace(staged, target, sha256(staged)) }

            assertFalse(applied)
            assertEquals(before, sha256(target))
            assertTrue("unchanged" in log, log)
            assertFalse("could not be restored" in log, "a false alarm that sends the operator to repair a working file")
            assertFalse(File(tempDir, "app.jar${UpdateInstaller.BACKUP_SUFFIX}").exists(), "no backup is needed of a file that was never touched")
        } finally {
            target.setWritable(true)
        }
    }

    @Test
    fun `the installed JAR keeps the permissions it had`() {
        // On POSIX a temp file is created rw-------, and a move carries that onto the JAR: the next
        // user to launch it cannot read it. Where there are no POSIX permissions there is nothing to copy.
        val target = serverJar(File(tempDir, "app.jar"))
        val staged = serverJar(File(tempDir, "app.jar.1.new"))
        val posix = "posix" in tempDir.toPath().fileSystem.supportedFileAttributeViews()
        if (posix) {
            java.nio.file.Files.setPosixFilePermissions(target.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
            java.nio.file.Files.setPosixFilePermissions(staged.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        }

        val copied = UpdateInstaller.copyPermissions(from = target, to = staged)

        assertEquals(posix, copied)
        if (posix) {
            assertEquals("rw-r--r--", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(staged.toPath())))
        }
    }

    @Test
    fun `a download is consumed as a stream, so an endless one is abandoned rather than buffered`() = runBlocking {
        // get() reads the whole body into memory before handing back a response, so the size cap only
        // ever saw bytes that had already been accepted. Here the body is produced lazily and counted:
        // read as a stream and cut off at the advertised 10 bytes, almost none of it is ever asked for.
        val source = serverJar(File(tempDir, "source.jar"))
        val produced = java.util.concurrent.atomic.AtomicLong()
        val engine = MockEngine {
            val body = kotlinx.coroutines.GlobalScope.writer(Dispatchers.IO) {
                val chunk = ByteArray(64 * 1024)
                repeat(1_024) { // 64 MiB on offer
                    channel.writeFully(chunk)
                    channel.flush()
                    produced.addAndGet(chunk.size.toLong())
                }
            }.channel
            respond(body, HttpStatusCode.OK)
        }

        val digest = UpdateInstaller.downloadTo(assetFor(source).copy(sizeBytes = 10), File(tempDir, "staged.new"), engine)

        assertNull(digest)
        assertTrue(produced.get() < 16L * 1024 * 1024, "${produced.get()} bytes were pulled from a body advertised as 10")
    }

    // ---------------------------------------------------------------------
    // Keeping a staged download alive. It waits for its process to EXIT, which for --http is days,
    // while every sibling started from the same JAR sweeps old .new files at startup.
    // ---------------------------------------------------------------------

    private val twoHoursAgo get() = System.currentTimeMillis() - 2 * 60 * 60 * 1000

    /** Poll rather than sleep a fixed time: the heartbeat runs on its own thread. */
    private fun eventually(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        kotlin.test.fail("timed out waiting for: $what")
    }

    private fun isFresh(file: File) =
        System.currentTimeMillis() - file.lastModified() < UpdateInstaller.STALE_SIDECAR_MILLIS

    @Test
    fun `touchStaged re-stamps a staged file and reports a missing one`() {
        val staged = File(tempDir, "app.jar.1.new").apply { writeText("x"); setLastModified(twoHoursAgo) }

        assertTrue(UpdateInstaller.touchStaged(staged))
        assertTrue(isFresh(staged), "the mtime must have moved to now")

        staged.delete()
        assertFalse(UpdateInstaller.touchStaged(staged), "a vanished file is the cue that the update is lost")
    }

    @Test
    fun `a staged download kept fresh survives a sibling's startup sweep`() {
        val target = serverJar(File(tempDir, "app.jar"))
        val staged = File(tempDir, "app.jar.1.new").apply { writeText("x"); setLastModified(twoHoursAgo) }
        val abandoned = File(tempDir, "app.jar.2.new").apply { writeText("x"); setLastModified(twoHoursAgo) }

        UpdateInstaller.keepStagedFresh(staged, intervalMillis = 20) { }.use {
            eventually("the heartbeat to re-stamp the staged file") { isFresh(staged) }
            // What a second server started from the same JAR does as it starts.
            UpdateInstaller.sweepStaleSidecars(target)
        }

        assertTrue(staged.exists(), "a live process's pending update must not be swept")
        assertFalse(abandoned.exists(), "while a genuinely abandoned one still is")
    }

    @Test
    fun `losing the staged file is reported exactly once`() {
        val staged = File(tempDir, "app.jar.1.new").apply { writeText("x") }
        val lost = java.util.concurrent.atomic.AtomicInteger()

        // pin = false: on Windows the pin would (by design) stop this test deleting the file.
        UpdateInstaller.keepStagedFresh(staged, intervalMillis = 20, pin = false) { lost.incrementAndGet() }.use {
            assertTrue(staged.delete())
            eventually("the loss to be noticed") { lost.get() > 0 }
            Thread.sleep(150) // several more intervals
        }

        assertEquals(1, lost.get(), "the operator is told once, not every ten minutes")
    }

    @Test
    fun `the pin stops the staged file being deleted on Windows until it is released`() {
        val staged = File(tempDir, "app.jar.1.new").apply { writeText("x"); setLastModified(twoHoursAgo) }

        val keepAlive = UpdateInstaller.keepStagedFresh(staged, intervalMillis = 20) { }
        try {
            // The pin must not get in the way of the heartbeat's own re-stamping.
            eventually("the heartbeat to work with the file pinned") { isFresh(staged) }
            val deletedWhilePinned = staged.delete()
            // POSIX unlink ignores open descriptors, so there the heartbeat is the only defence.
            assertEquals(!UpdateInstaller.IS_WINDOWS, deletedWhilePinned)
        } finally {
            keepAlive.close()
        }
        if (UpdateInstaller.IS_WINDOWS) assertTrue(staged.delete(), "closing must release the file for the swap")
    }

    @Test
    fun `closing stops the heartbeat, and it never keeps the JVM alive`() {
        val staged = File(tempDir, "app.jar.1.new").apply { writeText("x"); setLastModified(twoHoursAgo) }

        val keepAlive = UpdateInstaller.keepStagedFresh(staged, intervalMillis = 20, pin = false) { }
        eventually("the heartbeat to start") { isFresh(staged) }
        val threads = Thread.getAllStackTraces().keys.filter { it.name == UpdateInstaller.HEARTBEAT_THREAD_NAME }
        assertTrue(threads.isNotEmpty(), "the heartbeat runs on its own named thread")
        assertTrue(threads.all { it.isDaemon }, "a non-daemon thread would stop the server ever exiting")

        keepAlive.close()
        staged.setLastModified(twoHoursAgo)
        Thread.sleep(150)
        assertFalse(isFresh(staged), "nothing may touch the file after close")
    }

    @Test
    fun `close waits for a heartbeat tick that is already running`() {
        // What follows close() in production is the shutdown swap, which overwrites and then deletes
        // the staged file. A tick still in flight would re-stamp it underneath that. Left to chance
        // the overlap shows up about one run in a hundred, so here a tick is HELD open with a latch.
        val tickEntered = java.util.concurrent.CountDownLatch(1)
        val letTickFinish = java.util.concurrent.CountDownLatch(1)
        val staged = object : File(tempDir, "app.jar.1.new") {
            override fun isFile(): Boolean {
                tickEntered.countDown()
                // shutdownNow() interrupts the worker; a real tick is a syscall and cannot be cut short.
                while (letTickFinish.count > 0) {
                    try {
                        letTickFinish.await()
                    } catch (_: InterruptedException) {
                    }
                }
                return super.isFile()
            }
        }.apply { writeText("x") }

        val keepAlive = UpdateInstaller.keepStagedFresh(staged, intervalMillis = 10, pin = false) { }
        assertTrue(tickEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the heartbeat never ticked")

        val closed = AtomicBoolean(false)
        val closer = Thread { keepAlive.close(); closed.set(true) }.apply { start() }
        Thread.sleep(300)
        assertFalse(closed.get(), "close() returned while a tick was still running")

        letTickFinish.countDown()
        closer.join(5_000)
        assertTrue(closed.get(), "and it must return once the tick is done")
    }

    @Test
    fun `a shutdown swap that finds its staged file gone says so`() {
        val target = serverJar(File(tempDir, "app.jar"))
        val realErr = System.err
        val captured = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(captured, true))
        val applied = try {
            UpdateInstaller.installStagedAtShutdown(File(tempDir, "app.jar.9.new"), target, "0".repeat(64))
        } finally {
            System.setErr(realErr)
        }

        assertFalse(applied)
        assertTrue("no longer on disk" in captured.toString(), "was: $captured")
    }

    // ---------------------------------------------------------------------
    // The install lock. It is the only thing serialising two servers started from the same JAR, so
    // what these tests have to prove is that tidying up the lock file never weakens exclusion.
    // ---------------------------------------------------------------------

    private fun lockFileFor(target: File) = File(tempDir, target.name + UpdateInstaller.LOCK_SUFFIX)

    @Test
    fun `withInstallLock runs the block and hands back its value`() {
        val target = serverJar(File(tempDir, "app.jar"))

        val result = UpdateInstaller.withInstallLock(target) { "done" }

        assertEquals(UpdateInstaller.LockResult.Acquired("done"), result)
    }

    @Test
    fun `the Windows behaviour removes the lock file once it is released`() {
        val target = serverJar(File(tempDir, "app.jar"))
        var existedWhileHeld = false

        UpdateInstaller.withInstallLock(target, osName = "Windows 11") {
            existedWhileHeld = lockFileFor(target).exists()
        }

        assertTrue(existedWhileHeld, "the lock file is what carries the lock")
        assertFalse(lockFileFor(target).exists(), "and it is removed after the handle closes")
    }

    @Test
    fun `the POSIX behaviour never removes the lock file`() {
        // unlink() succeeds against a file another process has open and locked, so a delete here
        // could remove a newcomer's live lock. See withInstallLock.
        val target = serverJar(File(tempDir, "app.jar"))

        UpdateInstaller.withInstallLock(target, osName = "Linux") { }
        assertTrue(lockFileFor(target).exists(), "released, but deliberately left in place")

        // A leftover lock file is inert: it never blocks the next acquisition.
        assertEquals(UpdateInstaller.LockResult.Acquired(2), UpdateInstaller.withInstallLock(target, osName = "Linux") { 2 })
    }

    @Test
    fun `which platform deletes its lock is decided by the OS name, and only Windows does`() {
        // There is no CI and the suite runs on Windows alone, where deleting is correct. So the
        // POSIX answer is pinned here as a pure function, and below by passing the name in.
        for (windows in listOf("Windows 11", "Windows Server 2022", "windows 10")) assertTrue(UpdateInstaller.isWindows(windows), windows)
        for (posix in listOf("Linux", "Mac OS X", "FreeBSD", "SunOS", "", null)) assertFalse(UpdateInstaller.isWindows(posix), "$posix")
        assertEquals(UpdateInstaller.isWindows(System.getProperty("os.name")), UpdateInstaller.IS_WINDOWS)
    }

    @Test
    fun `on Linux and macOS the lock file is left in place, whatever machine runs this test`() {
        // unlink() succeeds against a file a newcomer has already opened and locked, so a delete here
        // could remove a live lock and let two installs run at once.
        val target = serverJar(File(tempDir, "app.jar"))
        for (posix in listOf("Linux", "Mac OS X")) {
            UpdateInstaller.withInstallLock(target, osName = posix) { }
            assertTrue(lockFileFor(target).exists(), "under $posix the lock file must never be deleted")
        }
    }

    @Test
    fun `the default cleanup follows the platform`() {
        val target = serverJar(File(tempDir, "app.jar"))

        UpdateInstaller.withInstallLock(target) { }

        assertEquals(!UpdateInstaller.IS_WINDOWS, lockFileFor(target).exists())
    }

    @Test
    fun `a throwing block propagates, and the lock is still released`() {
        val target = serverJar(File(tempDir, "app.jar"))

        assertFailsWith<IllegalStateException> {
            UpdateInstaller.withInstallLock<Unit>(target, osName = "Windows 11") { error("install blew up") }
        }

        assertFalse(lockFileFor(target).exists(), "cleanup runs on the failure path too")
        assertIs<UpdateInstaller.LockResult.Acquired<Int>>(
            UpdateInstaller.withInstallLock(target) { 1 },
            "a lock leaked by the failed block would make this Contended",
        )
    }

    @Test
    fun `a contended acquisition reports Contended and leaves the holder's lock file alone`() {
        val target = serverJar(File(tempDir, "app.jar"))
        var innerRan = false
        var lockExistedAfterContention = false

        UpdateInstaller.withInstallLock(target, osName = "Windows 11") {
            // Note the file-survival assertion cannot fail on Windows however the code is written:
            // deleting a file another handle holds open is refused there. The `acquired` guard it
            // pins is only load-bearing where a delete could succeed. Asserted anyway so the intent
            // is recorded for the platform that can violate it.
            val inner = UpdateInstaller.withInstallLock(target, osName = "Windows 11") { innerRan = true }
            assertEquals(UpdateInstaller.LockResult.Contended, inner)
            lockExistedAfterContention = lockFileFor(target).exists()
        }

        assertFalse(innerRan, "the contended block must never run")
        assertTrue(lockExistedAfterContention, "the loser must not delete the winner's lock file")
    }

    @Test
    fun `a lock file that cannot be opened is Unavailable, not Contended`() {
        val target = serverJar(File(tempDir, "app.jar"))
        // A directory squatting on the lock path: opening it for writing fails on every platform,
        // which stands in for a read-only install directory.
        lockFileFor(target).mkdirs()
        var ran = false

        val result = UpdateInstaller.withInstallLock(target) { ran = true }

        val unavailable = assertIs<UpdateInstaller.LockResult.Unavailable>(result)
        assertFalse(ran, "the block must not run without the lock")
        assertFalse(tempDir.absolutePath in unavailable.reason, "the reason names the file, never its directory")
    }

    @Test
    fun `install tells a broken install directory apart from a concurrent install`() = runBlocking {
        val source = serverJar(File(tempDir, "source.jar"))
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        val originalDigest = sha256(target)
        lockFileFor(target).mkdirs()
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = assetFor(source))

        val outcome = UpdateInstaller.install(result, engineServing(source.readBytes()), target)

        assertIs<UpdateInstaller.Outcome.Failed>(outcome)
        assertTrue("install lock" in outcome.reason, "was: ${outcome.reason}")
        assertFalse("another process" in outcome.reason, "nobody else is installing; saying so sends the operator the wrong way")
        assertEquals(originalDigest, sha256(target))
    }

    @Test
    fun `a cancelled install removes its partial download and releases the lock`() = runBlocking {
        val source = serverJar(File(tempDir, "source.jar"))
        val target = serverJar(File(tempDir, "installed.jar"), marker = "OLD")
        val result = UpdateChecker.Result(true, "0.1.6", "9.9.9", asset = assetFor(source))
        val downloading = CompletableDeferred<Unit>()
        val hanging = MockEngine {
            downloading.complete(Unit)
            awaitCancellation()
        }
        val returnedAnOutcome = AtomicBoolean(false)

        val job = launch(Dispatchers.Default) {
            UpdateInstaller.install(result, hanging, target)
            returnedAnOutcome.set(true)
        }
        downloading.await()
        job.cancelAndJoin()

        assertFalse(returnedAnOutcome.get(), "cancellation must propagate, not come back as an Outcome")
        val leftovers = tempDir.listFiles()!!.map { it.name }.filter { it.endsWith(UpdateInstaller.STAGED_SUFFIX) }
        assertTrue(leftovers.isEmpty(), "the partial download must not be left behind, found: $leftovers")
        val reacquired = UpdateInstaller.withInstallLock(target) { }
        assertIs<UpdateInstaller.LockResult.Acquired<Unit>>(reacquired, "a leaked lock would make this Contended")
        Unit
    }
}
