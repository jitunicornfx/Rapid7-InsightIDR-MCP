package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerLogSearchSpoolTools
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NEXT_1 = "https://us.rest.logs.insight.rapid7.com/query/next-1"
private const val NEXT_2 = "https://us.rest.logs.insight.rapid7.com/query/next-2"

class LogSearchSpoolToolsTest {

    // Injected through mcpHarness's existing `register` lambda, so no harness change is needed and
    // no test ever writes to a real user directory.
    private val tempDir: File = createTempDirectory("spool-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private suspend fun harness(responses: List<Pair<HttpStatusCode, String>>) =
        mcpHarness(responses = responses) { registerLogSearchSpoolTools(it, SpoolStore(tempDir.toPath())) }

    private fun spooled() = tempDir.listFiles { f: File -> f.name.endsWith(".ndjson") }?.toList() ?: emptyList()

    private fun textOf(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult) =
        (result.content.first() as TextContent).text

    private fun page(events: String, next: String? = null): String {
        val links = next?.let { ""","links":[{"rel":"Next","href":"$it"}]""" } ?: ""
        return """{"events":[$events]$links}"""
    }

    private fun spoolArgs(vararg extra: Pair<String, Any?>) =
        mapOf<String, Any?>("log_keys" to listOf("lk1"), "query" to "where(x)", "time_range" to "last 1 hour") + extra

    @Test
    fun `a three-page run writes every event as one NDJSON line and returns only a summary`() = runBlocking {
        val h = harness(
            listOf(
                HttpStatusCode.OK to page("""{"id":1},{"id":2}""", NEXT_1),
                HttpStatusCode.OK to page("""{"id":3},{"id":4}""", NEXT_2),
                HttpStatusCode.OK to page("""{"id":5}"""),
            ),
        )
        val result = h.call("logsearch_spool_query_to_file", spoolArgs())

        assertFalse(result.isError == true)
        assertEquals(3, h.requests.size, "should have followed both Next links")
        assertEquals("/query/logs", h.requests[0].url.encodedPath)

        val file = spooled().single()
        val lines = file.readLines().filter { it.isNotBlank() }
        assertEquals(5, lines.size, "every event across all three pages must be written")
        lines.forEach { JsonCodec.compact.parseToJsonElement(it).jsonObject }

        val text = textOf(result)
        assertTrue(file.path in text, "the summary must name the file")
        assertTrue("5" in text)
        // The whole point: cost is a fixed summary, not the data.
        assertTrue(text.length < 4_000, "the summary must stay small regardless of result size (was ${text.length})")
    }

    @Test
    fun `the summary embeds sample events inside the untrusted envelope`() = runBlocking {
        val h = harness(listOf(HttpStatusCode.OK to page("""{"id":1},{"id":2}""")))
        val text = textOf(h.call("logsearch_spool_query_to_file", spoolArgs()))

        val begin = "----- BEGIN UNTRUSTED INSIGHTIDR API DATA -----"
        assertEquals(1, text.split(begin).size - 1, "samples must be fenced exactly once")
        val file = spooled().single()
        assertTrue(
            text.indexOf(file.path) < text.indexOf(begin),
            "server-authored facts must sit before the fence so nothing inside can impersonate them",
        )
        assertTrue("UNTRUSTED THIRD-PARTY LOG DATA" in text, "the file's own contents must be flagged")
    }

    @Test
    fun `the max_pages cap stops the run and reports a resume link`() = runBlocking {
        val h = harness(
            listOf(
                HttpStatusCode.OK to page("""{"id":1},{"id":2}""", NEXT_1),
                HttpStatusCode.OK to page("""{"id":3},{"id":4}""", NEXT_2),
                HttpStatusCode.OK to page("""{"id":5}"""),
            ),
        )
        val text = textOf(h.call("logsearch_spool_query_to_file", spoolArgs("max_pages" to 2)))

        assertEquals(2, h.requests.size)
        assertEquals(4, spooled().single().readLines().filter { it.isNotBlank() }.size)
        assertTrue("max_pages cap" in text)
        assertTrue(NEXT_2 in text, "a capped run must hand back a resume link")
        assertTrue("resume_from_next_link" in text)
    }

    @Test
    fun `the max_events cap stops mid-page`() = runBlocking {
        val h = harness(
            listOf(
                HttpStatusCode.OK to page("""{"id":1},{"id":2}""", NEXT_1),
                HttpStatusCode.OK to page("""{"id":3},{"id":4}""", NEXT_2),
            ),
        )
        val text = textOf(h.call("logsearch_spool_query_to_file", spoolArgs("max_events" to 3)))

        assertEquals(3, spooled().single().readLines().filter { it.isNotBlank() }.size)
        assertTrue("max_events cap" in text)
    }

    @Test
    fun `a mid-run page failure keeps the events already written`() = runBlocking {
        val h = harness(
            listOf(
                HttpStatusCode.OK to page("""{"id":1},{"id":2}""", NEXT_1),
                HttpStatusCode.TooManyRequests to """{"code":429,"message":"slow down"}""",
            ),
        )
        val result = h.call("logsearch_spool_query_to_file", spoolArgs())
        val text = textOf(result)

        assertEquals(2, spooled().single().readLines().filter { it.isNotBlank() }.size)
        assertTrue("INCOMPLETE" in text)
        assertTrue("429" in text)
        assertTrue(NEXT_1 in text, "a resume link lets the caller pick up where it stopped")
        // Not an error: a file was genuinely produced, and erroring risks a client discarding the path.
        assertFalse(result.isError == true)
    }

    @Test
    fun `an unparseable page body stops the run without corrupting the file`() = runBlocking {
        val h = harness(
            listOf(
                HttpStatusCode.OK to page("""{"id":1},{"id":2}""", NEXT_1),
                HttpStatusCode.OK to "this is not json",
            ),
        )
        val text = textOf(h.call("logsearch_spool_query_to_file", spoolArgs()))

        val lines = spooled().single().readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        lines.forEach { JsonCodec.compact.parseToJsonElement(it).jsonObject }
        assertTrue("could not be parsed as JSON" in text)
    }

    @Test
    fun `a statistic result writes no file and returns the result inline`() = runBlocking {
        val h = harness(listOf(HttpStatusCode.OK to """{"statistics":{"count":5}}"""))
        val text = textOf(h.call("logsearch_spool_query_to_file", spoolArgs()))

        assertTrue(spooled().isEmpty(), "a statistic query has nothing to spool")
        assertTrue("statistics" in text)
        assertTrue("calculate" in text, "the explanation must say why nothing was spooled")
        assertTrue("----- BEGIN UNTRUSTED INSIGHTIDR API DATA -----" in text, "it is still API data")
    }

    @Test
    fun `an empty result writes no file`() = runBlocking {
        val h = harness(listOf(HttpStatusCode.OK to """{"events":[]}"""))
        val text = textOf(h.call("logsearch_spool_query_to_file", spoolArgs()))

        assertTrue(spooled().isEmpty(), "the file is created lazily, so a zero-event run leaves nothing")
        assertTrue("No events matched" in text)
    }

    @Test
    fun `the statistic pagination retry still applies to a spool run`() = runBlocking {
        val h = harness(
            listOf(
                HttpStatusCode.BadRequest to
                    """{"id":"IDR","code":101009,"message":"Pagination is not supported with statistic queries"}""",
                HttpStatusCode.OK to """{"statistics":{"count":5}}""",
            ),
        )
        val result = h.call(
            "logsearch_spool_query_to_file",
            spoolArgs("query" to "where(x) calculate(count)"),
        )

        assertEquals(2, h.requests.size, "the spool tool must route through submitLogSearchQuery")
        assertEquals(null, h.requests[1].url.parameters["per_page"], "the retry must drop pagination")
        assertTrue(spooled().isEmpty())
        assertFalse(result.isError == true)
    }

    @Test
    fun `a next-page link outside Rapid7 is refused and the run ends cleanly`() = runBlocking {
        val h = harness(
            listOf(HttpStatusCode.OK to page("""{"id":1},{"id":2}""", "https://evil.example.com/steal")),
        )
        val result = h.call("logsearch_spool_query_to_file", spoolArgs())
        val text = textOf(result)

        assertEquals(1, h.requests.size, "the off-Rapid7 href must never be fetched")
        assertEquals(2, spooled().single().readLines().filter { it.isNotBlank() }.size)
        assertTrue("refused to follow" in text)
        assertFalse(result.isError == true, "the events already written must not be lost to an error result")
    }

    @Test
    fun `a repeated next-page link stops the run`() = runBlocking {
        // Every response repeats the same Next href; without a loop guard this never terminates.
        val h = harness(listOf(HttpStatusCode.OK to page("""{"id":1}""", NEXT_1)))
        val text = textOf(h.call("logsearch_spool_query_to_file", spoolArgs()))

        assertTrue(h.requests.size <= 3, "the loop guard must stop the run, made ${h.requests.size} requests")
        assertTrue("repeated the same next-page link" in text)
    }

    @Test
    fun `the spool file is created in the injected directory with a server-generated name`() = runBlocking {
        val h = harness(listOf(HttpStatusCode.OK to page("""{"id":1}""")))
        h.call("logsearch_spool_query_to_file", spoolArgs())

        val file = spooled().single()
        assertEquals(tempDir.canonicalPath, file.parentFile.canonicalPath)
        assertTrue(
            Regex("""insightidr-spool-\d{8}-\d{6}-.*\.ndjson""").matches(file.name),
            "unexpected spool file name: ${file.name}",
        )
    }

    @Test
    fun `the tool exposes no path-like parameter and ignores one if supplied`() = runBlocking {
        val h = harness(listOf(HttpStatusCode.OK to page("""{"id":1}""")))

        // A caller must have no way to steer where the data lands. Check the property NAMES; the
        // descriptions legitimately talk about the file the server writes.
        val properties = h.tools()
            .first { it.name == "logsearch_spool_query_to_file" }
            .inputSchema.properties?.keys.orEmpty()
        for (forbidden in listOf("path", "file", "dir", "output")) {
            assertFalse(
                properties.any { forbidden in it },
                "the input schema must not expose a '$forbidden' parameter, found: $properties",
            )
        }

        h.call("logsearch_spool_query_to_file", spoolArgs("file_path" to "../../etc/passwd"))
        val file = spooled().single()
        assertEquals(tempDir.canonicalPath, file.parentFile.canonicalPath)
        assertFalse(File(tempDir.parentFile, "etc").exists(), "nothing may be written outside the spool directory")
    }

    @Test
    fun `a page that returns 202 with a Self link is polled before its events are spooled`() = runBlocking {
        val poll = "https://us.rest.logs.insight.rapid7.com/query/cont-9"
        val h = harness(
            listOf(
                HttpStatusCode.Accepted to """{"id":"cont-9","links":[{"rel":"Self","href":"$poll"}]}""",
                HttpStatusCode.OK to page("""{"id":1},{"id":2}"""),
            ),
        )
        h.call("logsearch_spool_query_to_file", spoolArgs())

        assertEquals(2, h.requests.size, "the 202 continuation must be polled")
        assertEquals(2, spooled().single().readLines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun `per_page defaults to the API maximum for spooling`() = runBlocking {
        val h = harness(listOf(HttpStatusCode.OK to page("""{"id":1}""")))
        h.call("logsearch_spool_query_to_file", spoolArgs())

        // These events never enter the conversation, so the largest page is strictly cheapest.
        assertEquals("500", h.requests[0].url.parameters["per_page"])
    }

    @Test
    fun `the manifest records the run and the untrusted-data warning`() = runBlocking {
        val h = harness(listOf(HttpStatusCode.OK to page("""{"id":1},{"id":2}""")))
        h.call("logsearch_spool_query_to_file", spoolArgs())

        val manifest = tempDir.listFiles { f: File -> f.name.endsWith(".manifest.json") }!!.single()
        val parsed = JsonCodec.compact.parseToJsonElement(manifest.readText()).jsonObject
        assertEquals(2, parsed["events"]?.toString()?.toIntOrNull())
        assertTrue(parsed.containsKey("status"))
        assertTrue(parsed["warning"]!!.toString().contains("UNTRUSTED"))
    }
}

class SpoolStoreTest {

    private val tempDir: File = createTempDirectory("spool-sweep").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `sweepStale removes old spool files, keeps fresh ones, and keeps the README`() {
        val store = SpoolStore(tempDir.toPath())
        store.prepare()

        val old = store.newSpoolFile("lk1").toFile()
        val fresh = store.newSpoolFile("lk2").toFile()
        val now = System.currentTimeMillis()
        old.setLastModified(now - 48L * 3_600_000)

        store.sweepStale(24L * 3_600_000, now)

        assertFalse(old.exists(), "a file past the retention window must be swept")
        assertTrue(fresh.exists(), "a fresh file must be kept")
        assertTrue(File(tempDir, SpoolStore.README_NAME).exists(), "the warning README is never swept")
    }

    @Test
    fun `a hostile label cannot escape the spool directory`() {
        val store = SpoolStore(tempDir.toPath())
        val file = store.newSpoolFile("../../../etc/passwd").toFile()

        assertEquals(tempDir.canonicalPath, file.parentFile.canonicalPath)
        assertFalse(".." in file.name)
        assertTrue(file.name.startsWith(SpoolStore.FILE_PREFIX))
    }
}
