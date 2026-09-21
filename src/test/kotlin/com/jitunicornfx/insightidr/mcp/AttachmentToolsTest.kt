package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerAttachmentTools
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentToolsTest {

    private suspend fun harness(body: String = "{}") =
        mcpHarness(responseBody = body) { registerAttachmentTools(it) }

    @Test
    fun `list_attachments passes target and paging`() = runBlocking {
        val h = harness(body = "[]")
        h.call("list_attachments", mapOf("target" to "rrn:inv:1", "index" to 0, "size" to 25))
        assertEquals(HttpMethod.Get, h.lastRequest.method)
        assertEquals("/idr/v1/attachments", h.lastRequest.url.encodedPath)
        assertEquals("rrn:inv:1", h.lastRequest.url.parameters["target"])
        assertEquals("25", h.lastRequest.url.parameters["size"])
    }

    @Test
    fun `get_attachment_metadata and download and delete resolve paths`() = runBlocking {
        val h = harness()
        h.call("get_attachment_metadata", mapOf("rrn" to "att1"))
        assertEquals("/idr/v1/attachments/att1/metadata", h.lastRequest.url.encodedPath)

        h.call("download_attachment", mapOf("rrn" to "att1"))
        assertEquals(HttpMethod.Get, h.lastRequest.method)
        assertEquals("/idr/v1/attachments/att1", h.lastRequest.url.encodedPath)

        h.call("delete_attachment", mapOf("rrn" to "att1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)
        assertEquals("/idr/v1/attachments/att1", h.lastRequest.url.encodedPath)
    }

    // ---------------------------------------------------------------------
    // upload_attachment reads a file the MODEL names and sends it to Rapid7. Unrestricted, a prompt
    // injection in a log line can have ~/.ssh/id_rsa attached to an investigation. So it is off until
    // the operator names a directory, and then confined to it.
    // ---------------------------------------------------------------------

    private val uploadDir: File = createTempDirectory("idr-uploads").toFile()
    private val outside: File = createTempDirectory("idr-elsewhere").toFile()

    @AfterTest
    fun cleanup() {
        // Links first, and non-recursively: deleteRecursively follows a junction and would empty
        // whatever it points at. A test that fails before removing its own link must not do that.
        uploadDir.listFiles()?.forEach { entry ->
            val path = entry.toPath()
            val isLink = runCatching { path.toRealPath() != path.toAbsolutePath().normalize() }.getOrDefault(false)
            if (isLink) runCatching { Files.delete(path) }
        }
        uploadDir.deleteRecursively()
        outside.deleteRecursively()
    }

    private suspend fun uploading(policy: UploadPolicy = UploadPolicy(uploadDir.toPath())) =
        mcpHarness(responseBody = "{}") { registerAttachmentTools(it, policy) }

    private fun text(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult) =
        (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text

    @Test
    fun `uploading is disabled until the operator names a directory`() = runBlocking {
        val secret = File(outside, "id_rsa").apply { writeText("PRIVATE KEY") }
        val h = uploading(UploadPolicy.DISABLED)

        val result = h.call("upload_attachment", mapOf("file_path" to secret.absolutePath))

        assertTrue(result.isError == true)
        assertTrue(h.requests.isEmpty(), "nothing may be sent")
        // Exactly the explanation, in the server's own voice: not an exception that happens to mention it.
        assertEquals(UploadPolicy.DISABLED_MESSAGE, text(result))
        assertTrue(Config.ENV_UPLOAD_DIR in text(result), "which says how to turn it on")
        // The tool is still listed: a missing tool invites a workaround, an explanation does not.
        assertTrue(h.tools().any { it.name == "upload_attachment" })
    }

    @Test
    fun `a disabled upload never touches the filesystem, even for a UNC path`() = runBlocking {
        // On Windows, merely stat-ing this path would open an SMB connection to the attacker.
        val h = uploading(UploadPolicy.DISABLED)
        val result = h.call("upload_attachment", mapOf("file_path" to """\\attacker.example.com\share\x"""))
        assertTrue(Config.ENV_UPLOAD_DIR in text(result), "the disabled answer comes first: ${text(result)}")
    }

    @Test
    fun `the process default is disabled`() {
        assertFalse(UploadPolicy.active.enabled, "nothing installs a policy in tests, as nothing does until Main")
        assertFalse(UploadPolicy.resolve(null).enabled)
        assertTrue(UploadPolicy.resolve(uploadDir.path).enabled)
    }

    @Test
    fun `a file inside the upload directory is sent, by absolute or relative path`() = runBlocking {
        File(uploadDir, "evidence.txt").writeText("evidence")
        File(uploadDir, "case-42").mkdirs()
        File(uploadDir, "case-42/notes.txt").writeText("notes")
        val h = uploading()

        for (path in listOf(File(uploadDir, "evidence.txt").absolutePath, "evidence.txt", "case-42/notes.txt")) {
            val result = h.call("upload_attachment", mapOf("file_path" to path))
            assertFalse(result.isError == true, "$path: ${text(result)}")
            assertEquals(HttpMethod.Post, h.lastRequest.method)
            assertEquals("/idr/v1/attachments", h.lastRequest.url.encodedPath)
        }
        assertEquals(3, h.requests.size)
    }

    @Test
    fun `a file outside the upload directory is refused, however the path is spelled`() = runBlocking {
        val secret = File(outside, "id_rsa").apply { writeText("PRIVATE KEY") }
        File(uploadDir, "sub").mkdirs()
        val h = uploading()

        val attempts = listOf(
            secret.absolutePath,
            "../${outside.name}/id_rsa",
            "sub/../../${outside.name}/id_rsa",
            File(uploadDir, "../${outside.name}/id_rsa").path,
        )
        for (path in attempts) {
            val result = h.call("upload_attachment", mapOf("file_path" to path))
            assertTrue(result.isError == true, "must be refused: $path")
            assertTrue("outside the upload directory" in text(result), "$path -> ${text(result)}")
            assertFalse(outside.name in text(result), "the path is the model's; do not repeat it in our voice")
        }
        assertTrue(h.requests.isEmpty(), "nothing outside the directory may reach the network")
    }

    @Test
    fun `a path outside the directory is refused the same way whether or not it exists`() = runBlocking {
        // Otherwise the two different errors would let a model map the host's filesystem.
        val real = File(outside, "exists.txt").apply { writeText("x") }
        val h = uploading()

        val existing = text(h.call("upload_attachment", mapOf("file_path" to real.absolutePath)))
        val missing = text(h.call("upload_attachment", mapOf("file_path" to File(outside, "no-such-file").absolutePath)))
        assertEquals(existing, missing)

        // The same through '..', which only a NORMALIZED containment check refuses up front. Checked
        // un-normalized, these get as far as the filesystem and come back with two different answers.
        val climbing = text(h.call("upload_attachment", mapOf("file_path" to "../${outside.name}/exists.txt")))
        val climbingMissing = text(h.call("upload_attachment", mapOf("file_path" to "../${outside.name}/no-such-file")))
        assertEquals(climbing, climbingMissing)
        assertEquals(existing, climbing)
    }

    /**
     * Make [link] a directory that is really [target]. A symbolic link needs a privilege most Windows
     * accounts lack, but a JUNCTION needs none — and is just as good a way out of the upload directory —
     * so this is testable on an ordinary developer machine.
     */
    private fun linkDirectory(link: File, target: File) {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            val exit = ProcessBuilder("cmd", "/c", "mklink", "/J", link.absolutePath, target.absolutePath)
                .redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor()
            assertEquals(0, exit, "could not create a junction")
        } else {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        }
    }

    @Test
    fun `a link inside the directory cannot point the read outside it`() = runBlocking {
        File(outside, "id_rsa").writeText("PRIVATE KEY")
        val jump = File(uploadDir, "case-notes")
        linkDirectory(jump, outside)
        assertTrue(File(jump, "id_rsa").isFile, "precondition: the link resolves, so only the policy can stop this")
        val h = uploading()

        // Every component of this path is lexically inside the upload directory.
        val result = h.call("upload_attachment", mapOf("file_path" to "case-notes/id_rsa"))

        assertTrue(result.isError == true)
        assertTrue("outside the upload directory" in text(result), text(result))
        assertTrue(h.requests.isEmpty(), "the key must not leave the machine")
        // deleteRecursively would follow the junction and empty the target; remove the link itself first.
        Files.delete(jump.toPath())
    }

    @Test
    fun `a link inside the directory does not turn the tool into a way of listing what is beyond it`() = runBlocking {
        // 'case-notes/id_rsa' exists beyond the link and 'case-notes/nope' does not. If the two got
        // different answers - "outside" for one, "no such file" for the other - a model could walk
        // the filesystem below any link by asking, without ever being allowed to read a byte.
        File(outside, "id_rsa").writeText("PRIVATE KEY")
        val jump = File(uploadDir, "case-notes")
        linkDirectory(jump, outside)
        val h = uploading()

        val exists = text(h.call("upload_attachment", mapOf("file_path" to "case-notes/id_rsa")))
        val missing = text(h.call("upload_attachment", mapOf("file_path" to "case-notes/nope")))
        val missingDeeper = text(h.call("upload_attachment", mapOf("file_path" to "case-notes/no/such/dir/file")))

        assertEquals(exists, missing)
        assertEquals(exists, missingDeeper)
        assertTrue("outside the upload directory" in exists, exists)
        // A file that is genuinely missing INSIDE the directory still says so.
        assertTrue("No such file" in text(h.call("upload_attachment", mapOf("file_path" to "really-not-here.bin"))))
        Files.delete(jump.toPath())
    }

    @Test
    fun `UNC, remote and device paths are refused before the filesystem is consulted`() = runBlocking {
        val h = uploading()
        for (path in listOf("""\\attacker.example.com\share\x""", "//attacker.example.com/share/x", """\\?\C:\Windows\win.ini""")) {
            val result = h.call("upload_attachment", mapOf("file_path" to path))
            assertTrue(result.isError == true, "must be refused: $path")
            assertTrue("UNC" in text(result), text(result))
            assertFalse("attacker.example.com" in text(result))
        }
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `a missing file, and a directory, are errors`() = runBlocking {
        File(uploadDir, "a-folder").mkdirs()
        val h = uploading()
        assertTrue("No such file" in text(h.call("upload_attachment", mapOf("file_path" to "nope.bin"))))
        assertTrue("not a regular file" in text(h.call("upload_attachment", mapOf("file_path" to "a-folder"))))
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `the size limit is enforced on the bytes read, not on a size looked up beforehand`() {
        val policy = UploadPolicy(uploadDir.toPath(), maxBytes = 1_000)
        val exact = File(uploadDir, "exact.bin").apply { writeBytes(ByteArray(1_000)) }
        val over = File(uploadDir, "over.bin").apply { writeBytes(ByteArray(1_001)) }

        assertEquals(1_000, policy.readCapped(exact.toPath()).size, "the limit itself is allowed")
        val refused = assertFailsWith<IllegalArgumentException> { policy.readCapped(over.toPath()) }
        assertTrue("upload limit" in refused.message.orEmpty())
    }

    @Test
    fun `a file that grows after its size was checked is still refused`() {
        // What length()-then-readBytes() got wrong. The stream stands in for a file that was small
        // when its size was looked up and is not by the time it is read.
        val policy = UploadPolicy(uploadDir.toPath(), maxBytes = 1_000)
        assertEquals(1_000, policy.readAtMost(ByteArray(1_000).inputStream()).size)
        assertFailsWith<IllegalArgumentException> { policy.readAtMost(ByteArray(5_000).inputStream()) }
    }

    @Test
    fun `readCapped itself enforces the limit on what it reads, whatever the size lookup said`() {
        // The size check and the counted read are two lines; only the second one is the guarantee.
        // Here the lookup lies the way a growing file makes it lie: small then, large now.
        val policy = UploadPolicy(uploadDir.toPath(), maxBytes = 1_000)
        val grown = File(uploadDir, "grown.bin").apply { writeBytes(ByteArray(5_000)) }

        val refused = assertFailsWith<IllegalArgumentException> { policy.readCapped(grown.toPath(), sizeOf = { 10 }) }
        assertTrue("upload limit" in refused.message.orEmpty())
    }

    @Test
    fun `an oversize file is refused without reaching the network`() = runBlocking {
        // Sparse, so the test does not write 100 MiB.
        val big = File(uploadDir, "big.bin")
        java.io.RandomAccessFile(big, "rw").use { it.setLength(UploadPolicy.DEFAULT_MAX_BYTES + 1) }
        val h = uploading()

        val result = h.call("upload_attachment", mapOf("file_path" to "big.bin"))

        assertTrue(result.isError == true)
        assertTrue("upload limit" in text(result), text(result))
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `an upload directory that does not exist is said so, in terms of the setting`() = runBlocking {
        val h = uploading(UploadPolicy(File(outside, "never-created").toPath()))
        val result = h.call("upload_attachment", mapOf("file_path" to "x.txt"))
        assertTrue(result.isError == true)
        assertTrue(Config.ENV_UPLOAD_DIR in text(result), text(result))
    }
}
