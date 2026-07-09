package com.jitunicornfx.insightidr.mcp

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Rapid7ClientTest {

    private val config = Config(
        apiKey = "secret-key",
        region = Region.US,
        baseUrl = "https://us.api.insight.rapid7.com",
        requestTimeoutMillis = 60_000,
    )

    private fun jsonEngine(status: HttpStatusCode, body: String, contentType: String = "application/json") =
        MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, contentType)) }

    @Test
    fun `get attaches auth header and expands query, parsing a 2xx response`() = runBlocking {
        val engine = jsonEngine(HttpStatusCode.OK, """{"ok":true}""")
        val client = Rapid7Client(config, engine)

        val resp = client.request(
            HttpMethod.Get,
            "/idr/v2/investigations",
            query = mapOf("a" to listOf("1"), "b" to listOf("2", "3")),
        )

        val req = engine.requestHistory.last()
        assertEquals("GET", req.method.value)
        assertEquals("/idr/v2/investigations", req.url.encodedPath)
        assertEquals("https://us.api.insight.rapid7.com", "${req.url.protocol.name}://${req.url.host}")
        assertEquals("secret-key", req.headers["X-Api-Key"])
        assertEquals("1", req.url.parameters["a"])
        assertEquals(listOf("2", "3"), req.url.parameters.getAll("b"))

        assertEquals(200, resp.status)
        assertTrue(resp.ok)
        assertEquals("""{"ok":true}""", resp.body)
        assertTrue(resp.contentType?.contains("application/json") == true)
        client.close()
    }

    @Test
    fun `post sends a compact json body with json content type`() = runBlocking {
        var sentBody: String? = null
        var sentType: String? = null
        val engine = MockEngine { request ->
            sentBody = (request.body as? TextContent)?.text
            sentType = request.body.contentType?.toString()
            respond("{}", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = Rapid7Client(config, engine)

        val resp = client.request(HttpMethod.Post, "/x", jsonBody = buildJsonObject { put("k", "v") })

        assertEquals(201, resp.status)
        assertTrue(resp.ok)
        assertEquals("""{"k":"v"}""", sentBody)
        assertTrue(sentType?.contains("application/json") == true)
        client.close()
    }

    @Test
    fun `raw body is sent with the provided content type`() = runBlocking {
        var sentBody: String? = null
        var sentType: String? = null
        val engine = MockEngine { request ->
            sentBody = (request.body as? TextContent)?.text
            sentType = request.body.contentType?.toString()
            respond("ok", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = Rapid7Client(config, engine)

        client.request(HttpMethod.Post, "/z", rawBody = "1.2.3.4\n5.6.7.8", rawContentType = ContentType.Text.CSV)

        assertEquals("1.2.3.4\n5.6.7.8", sentBody)
        assertTrue(sentType?.contains("text/csv") == true)
        client.close()
    }

    @Test
    fun `non-2xx responses are surfaced with ok=false`() = runBlocking {
        val engine = jsonEngine(HttpStatusCode.InternalServerError, "boom", contentType = "text/plain")
        val client = Rapid7Client(config, engine)

        val resp = client.request(HttpMethod.Get, "/e")

        assertEquals(500, resp.status)
        assertFalse(resp.ok)
        assertEquals("boom", resp.body)
        client.close()
    }

    @Test
    fun `client constructs with the default CIO engine`() {
        // Exercises the production (engine == null) branch without making a network call.
        val client = Rapid7Client(config)
        client.close()
    }

    @Test
    fun `v1 base routes requests to the rest logs host`() = runBlocking {
        val engine = jsonEngine(HttpStatusCode.OK, "{}")
        val client = Rapid7Client(config, engine)
        client.requestV1(HttpMethod.Get, "/idr/v1/investigations")
        val req = engine.requestHistory.last()
        assertEquals("us.rest.logs.insight.rapid7.com", req.url.host)
        assertEquals("/idr/v1/investigations", req.url.encodedPath)
        assertEquals("secret-key", req.headers["X-Api-Key"])
        client.close()
    }

    @Test
    fun `log search base routes requests to the rest logs host per the spec`() = runBlocking {
        val engine = jsonEngine(HttpStatusCode.OK, "{}")
        val client = Rapid7Client(config, engine)
        client.request(HttpMethod.Get, "/query/logs/abc", base = Rapid7Client.ApiBase.LOG_SEARCH)
        val req = engine.requestHistory.last()
        assertEquals("us.rest.logs.insight.rapid7.com", req.url.host)
        assertEquals("/query/logs/abc", req.url.encodedPath)
        assertEquals("secret-key", req.headers["X-Api-Key"])
        client.close()
    }

    @Test
    fun `requestAbsolute follows rapid7 URLs and attaches auth`() = runBlocking {
        val engine = jsonEngine(HttpStatusCode.OK, """{"events":[]}""")
        val client = Rapid7Client(config, engine)
        val resp = client.requestAbsolute("https://us.rest.logs.insight.rapid7.com/query/cont-1")
        assertTrue(resp.ok)
        assertEquals("secret-key", engine.requestHistory.last().headers["X-Api-Key"])
        client.close()
    }

    @Test
    fun `requestAbsolute refuses non-rapid7 URLs`() = runBlocking {
        val engine = jsonEngine(HttpStatusCode.OK, "{}")
        val client = Rapid7Client(config, engine)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            client.requestAbsolute("https://evil.example.com/steal")
        }
        assertEquals(0, engine.requestHistory.size, "no request must be sent to a disallowed host")
        client.close()
    }

    @Test
    fun `follow-url allow-list validates the origin, not a URL prefix`() {
        val client = Rapid7Client(config)
        try {
            // Legitimate hosts over HTTPS.
            assertTrue(client.isAllowedFollowUrl("https://us.api.insight.rapid7.com/x"))
            assertTrue(client.isAllowedFollowUrl("https://us.rest.logs.insight.rapid7.com/query/1"))
            assertTrue(client.isAllowedFollowUrl("https://anything.rapid7.com/x"))

            // Prefix-confusion: a foreign host that merely starts with a base URL string.
            assertFalse(client.isAllowedFollowUrl("https://us.api.insight.rapid7.com.evil.com/steal"))
            // Userinfo trick: real host is evil.com.
            assertFalse(client.isAllowedFollowUrl("https://us.api.insight.rapid7.com@evil.com/steal"))
            // Look-alikes.
            assertFalse(client.isAllowedFollowUrl("https://rapid7.com.evil.com/x"))
            assertFalse(client.isAllowedFollowUrl("https://notrapid7.com/x"))
            // Scheme downgrade to a real Rapid7 host must be refused (no cleartext key egress).
            assertFalse(client.isAllowedFollowUrl("http://us.api.insight.rapid7.com/x"))
            assertFalse(client.isAllowedFollowUrl("http://evil.com/x"))
            // Non-HTTP(S) schemes and unparseable input.
            assertFalse(client.isAllowedFollowUrl("file:///etc/passwd"))
            assertFalse(client.isAllowedFollowUrl("not a url"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `requestAbsolute does not leak the api key to a prefix-confusion host`() = runBlocking {
        val engine = jsonEngine(HttpStatusCode.OK, "{}")
        val client = Rapid7Client(config, engine)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            client.requestAbsolute("https://us.api.insight.rapid7.com.attacker.example/exfil")
        }
        assertEquals(0, engine.requestHistory.size, "the X-Api-Key must never reach the attacker host")
        client.close()
    }

    @Test
    fun `uploadFile posts multipart and sanitizes the filename`() = runBlocking {
        val engine = jsonEngine(HttpStatusCode.OK, """{"rrn":"att1"}""")
        val client = Rapid7Client(config, engine)

        // Filename contains characters that must be stripped from the Content-Disposition.
        val resp = client.uploadFile(
            "/idr/v1/attachments",
            fileName = "e\"vi\r\nl.txt",
            bytes = "evidence".toByteArray(),
        )

        val req = engine.requestHistory.last()
        assertEquals("POST", req.method.value)
        assertEquals("/idr/v1/attachments", req.url.encodedPath)
        assertEquals("secret-key", req.headers["X-Api-Key"])
        assertTrue(resp.ok)
        client.close()
    }
}
