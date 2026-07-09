package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerAttachmentTools
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `upload_attachment posts multipart from a local file`() = runBlocking {
        val file = File.createTempFile("idr-upload", ".txt").apply {
            writeText("evidence")
            deleteOnExit()
        }
        kotlin.test.assertTrue(file.isFile, "precondition: temp file exists at ${file.absolutePath}")
        val h = harness()
        val result = h.call("upload_attachment", mapOf("file_path" to file.absolutePath))
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertFalse(result.isError == true, "upload failed: $text")
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertEquals("/idr/v1/attachments", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `upload_attachment reports a missing file as an error`() = runBlocking {
        val h = harness()
        val result = h.call("upload_attachment", mapOf("file_path" to "/definitely/not/here.bin"))
        assertTrue(result.isError == true)
    }

    @Test
    fun `upload_attachment refuses UNC and remote paths without issuing a request`() = runBlocking {
        val h = harness()
        for (p in listOf("""\\attacker.example.com\share\x""", "//attacker.example.com/share/x", """\\?\C:\Windows\win.ini""")) {
            val result = h.call("upload_attachment", mapOf("file_path" to p))
            assertTrue(result.isError == true, "UNC/remote path must be refused: $p")
            val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
            assertTrue("UNC/remote" in text, "error should explain the refusal: $text")
        }
        // No upload request should ever have been issued for a refused path.
        assertTrue(h.requests.isEmpty(), "a refused UNC path must not reach the network")
    }

    @Test
    fun `requireLocalFilePath allows ordinary local paths`() {
        // Ordinary absolute paths on either platform must pass.
        com.jitunicornfx.insightidr.mcp.tools.requireLocalFilePath("""C:\Users\me\evidence.txt""")
        com.jitunicornfx.insightidr.mcp.tools.requireLocalFilePath("/home/me/evidence.txt")
        com.jitunicornfx.insightidr.mcp.tools.requireLocalFilePath("evidence.txt")
    }

    @Test
    fun `upload_attachment refuses a file over the size cap`() = runBlocking {
        // A sparse file whose reported length exceeds the cap: guarded by file.length() before readBytes.
        val big = File.createTempFile("idr-big", ".bin").apply { deleteOnExit() }
        java.io.RandomAccessFile(big, "rw").use { it.setLength(com.jitunicornfx.insightidr.mcp.tools.MAX_UPLOAD_BYTES + 1) }
        val h = harness()
        val result = h.call("upload_attachment", mapOf("file_path" to big.absolutePath))
        assertTrue(result.isError == true, "oversize file must be refused")
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue("upload limit" in text, "error should mention the size limit: $text")
        assertTrue(h.requests.isEmpty(), "an oversize file must not reach the network")
    }
}
