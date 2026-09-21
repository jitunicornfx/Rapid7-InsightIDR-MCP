package com.jitunicornfx.insightidr.mcp

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spooled files hold tenant log data. These tests pin WHO can read them — on whichever platform the
 * suite runs on. Each test branches on the platform rather than being skipped on the other one, so
 * the count of executed tests is the same everywhere and a skipped security test can never hide.
 */
class SpoolStorePrivacyTest {

    private val root: File = createTempDirectory("spoolstore-test").toFile()
    private val warnings = mutableListOf<String>()
    private val posix = "posix" in root.toPath().fileSystem.supportedFileAttributeViews()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun store(dir: File) = SpoolStore(dir.toPath(), warn = { warnings += it })

    private fun acl(path: Path): List<AclEntry> =
        Files.getFileAttributeView(path, AclFileAttributeView::class.java).acl

    /** Whoever the OS makes the owner of a file this process creates. */
    private val me by lazy { Files.getOwner(File(root, "probe").apply { writeText("") }.toPath()) }

    /** Assert [path] admits its owner and nobody else. */
    private fun assertPrivate(path: Path, what: String, directory: Boolean = false) {
        if (posix) {
            val expected = if (directory) "rwx------" else "rw-------"
            assertEquals(expected, PosixFilePermissions.toString(Files.getPosixFilePermissions(path)), what)
        } else {
            val entries = acl(path)
            assertEquals(1, entries.size, "$what must admit exactly one principal, had: ${entries.map { it.principal().name }}")
            assertEquals(me, entries.single().principal(), what)
            assertEquals(AclEntryType.ALLOW, entries.single().type())
        }
    }

    /** Make [dir] readable by other users, the way a shared evidence folder would be. */
    private fun share(dir: File) {
        if (posix) {
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        } else {
            val everyone = dir.toPath().fileSystem.userPrincipalLookupService.lookupPrincipalByGroupName("Everyone")
            val view = Files.getFileAttributeView(dir.toPath(), AclFileAttributeView::class.java)
            view.acl = view.acl + AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(everyone)
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .build()
        }
    }

    @Test
    fun `a spool directory this server creates is private to its owner`() {
        val dir = File(root, "made/by/us")

        store(dir).prepare()

        assertPrivate(dir.toPath(), "the spool directory", directory = true)
        assertTrue(warnings.none { "accessible to" in it }, "nothing to warn about: $warnings")
    }

    @Test
    fun `every file is born private, even inside a directory shared with everyone`() {
        // The case that matters: an operator points INSIGHTIDR_SPOOL_DIR at a shared folder. A file
        // created there and restricted afterwards would be readable in between — and a handle opened
        // in that window keeps its access. So the restriction has to be part of the create call.
        val shared = File(root, "shared-evidence").apply { mkdirs() }
        share(shared)
        val store = store(shared)

        val spool = store.newSpoolFile("lk1")
        val manifest = store.manifestFor(spool).also { store.writePrivate(it, "{}") }

        assertPrivate(spool, "the spool file")
        assertPrivate(manifest, "the manifest")
        assertPrivate(shared.toPath().resolve(SpoolStore.README_NAME), "the README")
        // Still usable by the owner after being locked down.
        Files.writeString(spool, "{}\n")
        assertEquals("{}\n", Files.readString(spool))
    }

    @Test
    fun `an existing shared directory is reported, and left exactly as it was`() {
        val shared = File(root, "shared-evidence").apply { mkdirs() }
        share(shared)
        val before = if (posix) Files.getPosixFilePermissions(shared.toPath()).toString() else acl(shared.toPath()).toString()

        store(shared).prepare()

        val after = if (posix) Files.getPosixFilePermissions(shared.toPath()).toString() else acl(shared.toPath()).toString()
        assertEquals(before, after, "someone set this directory up on purpose; it is not ours to change")
        val warning = warnings.single { "accessible to" in it }
        assertTrue(if (posix) "everyone" in warning else "Everyone" in warning, "was: $warning")
    }

    @Test
    fun `an existing directory nobody else can enter raises no warning`() {
        // A plain directory under the user's own temp folder inherits the same entries as their home.
        val private = File(root, "already-there").apply { mkdirs() }
        if (posix) Files.setPosixFilePermissions(private.toPath(), PosixFilePermissions.fromString("rwx------"))

        store(private).prepare()

        assertTrue(warnings.none { "accessible to" in it }, "was: $warnings")
    }

    @Test
    fun `prepare does its work once, and again only if the directory disappears`() {
        val dir = File(root, "spool")
        val store = store(dir)
        store.prepare()
        val readme = File(dir, SpoolStore.README_NAME)
        assertTrue(readme.exists())

        // It used to re-run per page of every spool: a permission change and a README check each time.
        readme.delete()
        store.prepare()
        assertFalse(readme.exists(), "a second prepare on an intact directory must do nothing")

        dir.deleteRecursively()
        store.prepare()
        assertTrue(readme.exists(), "but a directory removed under a running server is set up again")
    }

    @Test
    fun `prepare fails loudly when the directory cannot be created`() {
        val blocker = File(root, "a-file").apply { writeText("x") }

        assertFailsWith<IOException> { store(File(blocker, "spool")).prepare() }
    }

    @Test
    fun `a spool directory outside the user's home is pointed out, the default one is not`() {
        val home = File(root, "home/analyst").apply { mkdirs() }.toPath()
        fun storeAt(dir: File, isDefault: Boolean = false) =
            SpoolStore(dir.toPath(), isDefaultLocation = isDefault, warn = { warnings += it }, homeDirectory = home)

        storeAt(File(root, "srv/evidence")).prepare()
        assertEquals(1, warnings.count { "outside the home directory" in it }, "was: $warnings")

        warnings.clear()
        storeAt(File(home.toFile(), "spool")).prepare()
        storeAt(File(root, "anywhere"), isDefault = true).prepare()
        assertTrue(warnings.none { "outside the home directory" in it }, "was: $warnings")
    }


    // ---------------------------------------------------------------------
    // The startup sweep runs in a directory that may be an operator's evidence folder.
    // ---------------------------------------------------------------------

    @Test
    fun `the sweep removes only this server's own old files`() {
        val dir = File(root, "evidence").apply { mkdirs() }
        val store = store(dir)
        val longAgo = System.currentTimeMillis() - 48L * 60 * 60 * 1000
        fun aged(name: String) = File(dir, name).apply { writeText("x"); setLastModified(longAgo) }

        val oldSpool = aged("${SpoolStore.FILE_PREFIX}20260101-000000-lk1-1${SpoolStore.FILE_SUFFIX}")
        val oldManifest = aged("${SpoolStore.FILE_PREFIX}20260101-000000-lk1-1${SpoolStore.MANIFEST_SUFFIX}")
        // Everything below is somebody else's, however old it is.
        val analystsCase = aged("case-4411.ndjson")
        val notes = aged("notes.txt")
        val readme = aged(SpoolStore.README_NAME)
        val lookAlike = aged("my-${SpoolStore.FILE_PREFIX}export${SpoolStore.FILE_SUFFIX}")
        val freshSpool = File(dir, "${SpoolStore.FILE_PREFIX}20260921-000000-lk1-2${SpoolStore.FILE_SUFFIX}").apply { writeText("x") }

        store.sweepStale(olderThanMillis = 24L * 60 * 60 * 1000)

        assertFalse(oldSpool.exists(), "an old spool file is what the sweep is for")
        assertFalse(oldManifest.exists(), "and its manifest goes with it")
        for (kept in listOf(analystsCase, notes, readme, lookAlike, freshSpool)) {
            assertTrue(kept.exists(), "${kept.name} must survive the sweep")
        }
    }

    @Test
    fun `a file whose age cannot be read is left alone`() {
        // File.lastModified() reports "could not read it" as 0, which naive arithmetic reads as 50
        // years stale. Only a file KNOWN to be old is deleted.
        val dir = File(root, "spool").apply { mkdirs() }
        val unreadable = File(dir, "${SpoolStore.FILE_PREFIX}x${SpoolStore.FILE_SUFFIX}").apply { writeText("x") }
        assertTrue(unreadable.setLastModified(0), "precondition: the platform lets the test set an epoch mtime")

        store(dir).sweepStale(olderThanMillis = 1)

        assertTrue(unreadable.exists())
    }

    // ---------------------------------------------------------------------
    // Telling the operator, at the time they are looking.
    // ---------------------------------------------------------------------

    @Test
    fun `an existing spool directory is audited at startup, without being created or changed`() {
        val shared = File(root, "shared-evidence").apply { mkdirs() }
        share(shared)
        val store = store(shared)

        store.auditAtStartup()

        assertEquals(1, warnings.count { "accessible to" in it }, "said at startup, while the operator is watching: $warnings")
        assertFalse(File(shared, SpoolStore.README_NAME).exists(), "auditing is not preparing: nothing is written yet")

        store.prepare()
        assertEquals(1, warnings.count { "accessible to" in it }, "and not said a second time on first use")
    }

    @Test
    fun `auditing at startup never creates the spool directory`() {
        val absent = File(root, "not-yet")
        store(absent).auditAtStartup()
        assertFalse(absent.exists(), "a server that never spools should leave no directory behind")
        assertTrue(warnings.none { "accessible to" in it })
    }

    @Test
    fun `a file that did not come out private is reported, once`() {
        // FAT, exFAT and some network shares accept the owner-only attribute and quietly ignore it:
        // no exception, so nothing to catch. The only way to know is to look at what was created.
        val shared = File(root, "shared-evidence").apply { mkdirs() }
        share(shared)
        val store = SpoolStore(
            directory = shared.toPath(),
            isDefaultLocation = false,
            warn = { warnings += it },
            homeDirectory = null,
            privacyFor = { _, warn -> SpoolStore.Privacy.none(warn) },
        )

        store.newSpoolFile("lk1")
        store.newSpoolFile("lk2")

        assertEquals(1, warnings.count { "could not be made private" in it }, "$warnings")
    }

    @Test
    fun `files that did come out private raise no such warning`() {
        val shared = File(root, "shared-evidence").apply { mkdirs() }
        share(shared)
        store(shared).newSpoolFile("lk1")
        assertTrue(warnings.none { "could not be made private" in it }, "$warnings")
    }
}
