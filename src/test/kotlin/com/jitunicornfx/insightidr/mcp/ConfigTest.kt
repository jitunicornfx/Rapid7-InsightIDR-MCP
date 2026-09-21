package com.jitunicornfx.insightidr.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigTest {

    @Test
    fun `region resolves case-insensitively and trims`() {
        assertEquals(Region.US, Region.fromCode("US"))
        assertEquals(Region.EU, Region.fromCode(" eu "))
    }

    @Test
    fun `unknown region is rejected`() {
        assertFailsWith<IllegalArgumentException> { Region.fromCode("zz") }
    }

    @Test
    fun `missing api key is rejected`() {
        assertFailsWith<IllegalStateException> { Config.fromEnv(emptyMap()) }
    }

    @Test
    fun `defaults resolve region and base url`() {
        val config = Config.fromEnv(mapOf(Config.ENV_API_KEY to "key"))
        assertEquals(Region.US, config.region)
        assertEquals("https://us.api.insight.rapid7.com", config.baseUrl)
        assertEquals(Config.DEFAULT_TIMEOUT_MS, config.requestTimeoutMillis)
    }

    @Test
    fun `region drives the base url host`() {
        val config = Config.fromEnv(mapOf(Config.ENV_API_KEY to "key", Config.ENV_REGION to "eu"))
        assertEquals("https://eu.api.insight.rapid7.com", config.baseUrl)
    }

    @Test
    fun `explicit base url overrides and trailing slash is trimmed`() {
        val config = Config.fromEnv(
            mapOf(Config.ENV_API_KEY to "key", Config.ENV_BASE_URL to "https://example.test/"),
        )
        assertEquals("https://example.test", config.baseUrl)
    }

    @Test
    fun `toString does not leak the api key`() {
        val config = Config.fromEnv(mapOf(Config.ENV_API_KEY to "super-secret-value"))
        assertTrue("super-secret-value" !in config.toString())
    }

    @Test
    fun `log search base url defaults to the rest logs host per the spec servers`() {
        val config = Config.fromEnv(mapOf(Config.ENV_API_KEY to "key", Config.ENV_REGION to "eu"))
        assertEquals("https://eu.rest.logs.insight.rapid7.com", config.logSearchBaseUrl)
    }

    @Test
    fun `v1 base url defaults to the api insight host, where the v1 IDR routes actually live`() {
        // Regression: the v1 spec's servers block advertises the rest.logs host, but every
        // /idr/v1/* route 404s there and exists on api.insight — see Config.v1BaseUrl.
        val config = Config.fromEnv(mapOf(Config.ENV_API_KEY to "key", Config.ENV_REGION to "eu"))
        assertEquals("https://eu.api.insight.rapid7.com", config.v1BaseUrl)
    }

    @Test
    fun `v1 base url can be overridden`() {
        val config = Config.fromEnv(
            mapOf(
                Config.ENV_API_KEY to "key",
                Config.ENV_V1_BASE_URL to "https://us.api.insight.rapid7.com/",
            ),
        )
        assertEquals("https://us.api.insight.rapid7.com", config.v1BaseUrl)
    }

    @Test
    fun `log search base url can be overridden to the unified route`() {
        val config = Config.fromEnv(
            mapOf(
                Config.ENV_API_KEY to "key",
                Config.ENV_LOG_SEARCH_BASE_URL to "https://us.api.insight.rapid7.com/log_search/",
            ),
        )
        assertEquals("https://us.api.insight.rapid7.com/log_search", config.logSearchBaseUrl)
    }

    @Test
    fun `http allowed origins default to empty so cross-origin browser access is denied`() {
        val config = Config.fromEnv(mapOf(Config.ENV_API_KEY to "key"))
        assertTrue(config.httpAllowedOrigins.isEmpty())
    }

    @Test
    fun `http allowed origins parse a comma list, trim, and drop blanks and wildcard`() {
        val config = Config.fromEnv(
            mapOf(
                Config.ENV_API_KEY to "key",
                Config.ENV_HTTP_ALLOWED_ORIGINS to " https://app.example.com , , * ,https://b.example.com:8443 ",
            ),
        )
        assertEquals(listOf("https://app.example.com", "https://b.example.com:8443"), config.httpAllowedOrigins)
    }

    @Test
    fun `update check and auto update are enabled by default`() {
        val config = Config.fromEnv(mapOf(Config.ENV_API_KEY to "key"))
        assertFalse(config.updateCheckDisabled)
        assertFalse(config.autoUpdateDisabled)
    }

    @Test
    fun `auto update is disabled by any truthy value and unaffected by others`() {
        fun autoUpdateDisabledWith(value: String) = Config.fromEnv(
            mapOf(Config.ENV_API_KEY to "key", Config.ENV_DISABLE_AUTO_UPDATE to value),
        ).autoUpdateDisabled

        for (truthy in listOf("1", "true", "TRUE", "yes", "on", " true ")) {
            assertTrue(autoUpdateDisabledWith(truthy), "'$truthy' must disable automatic installation")
        }
        for (other in listOf("0", "false", "no", "off", "")) {
            assertFalse(autoUpdateDisabledWith(other), "'$other' must leave automatic installation enabled")
        }
    }

    @Test
    fun `disabling auto update leaves the update check running and vice versa`() {
        val noInstall = Config.fromEnv(
            mapOf(Config.ENV_API_KEY to "key", Config.ENV_DISABLE_AUTO_UPDATE to "1"),
        )
        assertTrue(noInstall.autoUpdateDisabled)
        assertFalse(noInstall.updateCheckDisabled, "the notification survives when only installation is off")

        val noCheck = Config.fromEnv(
            mapOf(Config.ENV_API_KEY to "key", Config.ENV_DISABLE_UPDATE_CHECK to "1"),
        )
        assertTrue(noCheck.updateCheckDisabled)
    }

    // ---------------------------------------------------------------------
    // Validation. Every request to a base URL carries the API key, and a number that silently
    // falls back to its default can mean deleted evidence.
    // ---------------------------------------------------------------------

    private fun configWith(vararg env: Pair<String, String>) =
        Config.fromEnv(mapOf(Config.ENV_API_KEY to "key") + env)

    private fun rejected(vararg env: Pair<String, String>): String =
        assertFailsWith<IllegalStateException> { configWith(*env) }.message.orEmpty()

    @Test
    fun `a base url with no host is rejected instead of quietly meaning localhost`() {
        // Ktor parses "https://" to the host "localhost" — and would send the API key there.
        for (value in listOf("https://", "https:///idr", "https://:8443")) {
            val message = rejected(Config.ENV_BASE_URL to value)
            assertTrue(Config.ENV_BASE_URL in message, "was: $message")
        }
    }

    @Test
    fun `a base url must be https unless it is localhost`() {
        assertTrue("https://" in rejected(Config.ENV_BASE_URL to "http://us.api.insight.rapid7.com"))
        assertTrue("https://" in rejected(Config.ENV_V1_BASE_URL to "ftp://us.api.insight.rapid7.com"))
        assertTrue("https://" in rejected(Config.ENV_LOG_SEARCH_BASE_URL to "us.rest.logs.insight.rapid7.com"))
        // The reason the override exists.
        assertEquals("http://localhost:8080", configWith(Config.ENV_BASE_URL to "http://localhost:8080/").baseUrl)
        assertEquals("http://127.0.0.1:8080", configWith(Config.ENV_BASE_URL to "http://127.0.0.1:8080").baseUrl)
        // Literal names only: a name that merely resolves to loopback must not qualify.
        assertTrue("https://" in rejected(Config.ENV_BASE_URL to "http://localhost.example.com"))
    }

    @Test
    fun `a base url with credentials, a query or a fragment is rejected, and never repeated`() {
        val withCredentials = rejected(Config.ENV_BASE_URL to "https://admin:hunter2@us.api.insight.rapid7.com")
        assertTrue("credentials" in withCredentials)
        assertFalse("hunter2" in withCredentials, "a rejected value must not be echoed into a log")

        assertTrue("query" in rejected(Config.ENV_BASE_URL to "https://us.api.insight.rapid7.com?x=1"))
        assertTrue("query" in rejected(Config.ENV_BASE_URL to "https://us.api.insight.rapid7.com#frag"))
        assertTrue("spaces" in rejected(Config.ENV_BASE_URL to "https://us.api.insight .rapid7.com"))
    }

    @Test
    fun `a base url may keep a path, and surrounding whitespace is ignored`() {
        val config = configWith(Config.ENV_LOG_SEARCH_BASE_URL to "  https://us.api.insight.rapid7.com/log_search/  ")
        assertEquals("https://us.api.insight.rapid7.com/log_search", config.logSearchBaseUrl)
    }

    @Test
    fun `a base url outside rapid7 is allowed but called out`() {
        assertTrue(configWith().startupWarnings().isEmpty(), "the defaults are Rapid7's own hosts")
        assertTrue(configWith(Config.ENV_BASE_URL to "http://localhost:8080").startupWarnings().isEmpty())

        val warnings = configWith(Config.ENV_BASE_URL to "https://proxy.corp.example/idr").startupWarnings()
        assertEquals(1, warnings.size)
        assertTrue(Config.ENV_BASE_URL in warnings.single() && "proxy.corp.example" in warnings.single())
        assertTrue("API key" in warnings.single(), "say what is at stake")
        // A look-alike is not a rapid7.com host.
        assertEquals(1, configWith(Config.ENV_BASE_URL to "https://rapid7.com.evil.example").startupWarnings().size)
    }

    @Test
    fun `the http token is optional, trimmed, and held to a minimum length`() {
        assertEquals(null, configWith().httpAuthToken)
        assertEquals(null, configWith(Config.ENV_HTTP_TOKEN to "   ").httpAuthToken, "blank is unset")
        assertEquals("0123456789abcdef", configWith(Config.ENV_HTTP_TOKEN to "  0123456789abcdef  ").httpAuthToken)

        // A throttle only slows a search; the size of the space is what defeats one.
        assertTrue(Config.ENV_HTTP_TOKEN in rejected(Config.ENV_HTTP_TOKEN to "hunter2"))
        assertTrue(Config.ENV_HTTP_TOKEN in rejected(Config.ENV_HTTP_TOKEN to "0123456789abcde"), "15 is one short")
        assertTrue(Config.ENV_HTTP_TOKEN in rejected(Config.ENV_HTTP_TOKEN to "0123456789 abcdefghij"), "no spaces")
    }

    @Test
    fun `the http token is never printed, echoed or described`() {
        val token = "correct-horse-battery-staple"
        val printed = configWith(Config.ENV_HTTP_TOKEN to token).toString()
        assertFalse(token in printed, printed)
        assertTrue("httpAuthToken=***" in printed)
        assertTrue("httpAuthToken=unset" in configWith().toString())

        val message = rejected(Config.ENV_HTTP_TOKEN to "tooshort-secret")
        assertFalse("tooshort-secret" in message, "a rejected token must not be echoed into a log")
        assertFalse("15" in message, "nor its length")
    }

    @Test
    fun `the upload directory is unset by default, which leaves upload_attachment disabled`() {
        assertEquals(null, configWith().uploadDirectory)
        assertEquals(null, configWith(Config.ENV_UPLOAD_DIR to "   ").uploadDirectory, "blank is unset")
        assertEquals("/srv/uploads", configWith(Config.ENV_UPLOAD_DIR to "  /srv/uploads  ").uploadDirectory)
    }

    @Test
    fun `a number that is not a number stops the server instead of meaning the default`() {
        // This one is data loss: "never" used to mean 24 hours, and the evidence was swept.
        assertTrue(Config.ENV_SPOOL_RETENTION_HOURS in rejected(Config.ENV_SPOOL_RETENTION_HOURS to "never"))
        assertTrue(Config.ENV_TIMEOUT_MS in rejected(Config.ENV_TIMEOUT_MS to "60s"))
        assertTrue(Config.ENV_MAX_RESULT_CHARS in rejected(Config.ENV_MAX_RESULT_CHARS to "200k"))
        assertTrue(Config.ENV_MAX_RESULT_CHARS in rejected(Config.ENV_MAX_RESULT_CHARS to "2e5"))
        // A negative retention is refused rather than adjusted: whoever wrote -1 plausibly meant
        // "never sweep", and guessing 24 hours instead is how preserved evidence gets deleted.
        assertTrue(Config.ENV_SPOOL_RETENTION_HOURS in rejected(Config.ENV_SPOOL_RETENTION_HOURS to "-1"))
    }

    @Test
    fun `a number that parses but is out of range is adjusted, and the operator is told`() {
        // These all ran on 0.3.1. A server that updates itself must not turn a working configuration
        // into an outage, so they keep working; what changes is that it is no longer silent.
        val zeroTimeout = configWith(Config.ENV_TIMEOUT_MS to "0")
        assertEquals(Config.DEFAULT_TIMEOUT_MS, zeroTimeout.requestTimeoutMillis, "0 meant the default before, and still does")
        assertTrue(zeroTimeout.startupWarnings().any { Config.ENV_TIMEOUT_MS in it }, "${zeroTimeout.startupWarnings()}")

        val hugeTimeout = configWith(Config.ENV_TIMEOUT_MS to "7200000")
        assertEquals(Config.MAX_TIMEOUT_MS, hugeTimeout.requestTimeoutMillis)
        assertTrue(hugeTimeout.startupWarnings().any { Config.ENV_TIMEOUT_MS in it })

        for (unlimited in listOf("0", "-1")) {
            val config = configWith(Config.ENV_MAX_RESULT_CHARS to unlimited)
            assertEquals(Config.DEFAULT_MAX_RESULT_CHARS, config.maxResultChars, "there is no unlimited; $unlimited means the default")
            assertTrue(config.startupWarnings().any { Config.ENV_MAX_RESULT_CHARS in it })
        }
        assertEquals(Int.MAX_VALUE, configWith(Config.ENV_MAX_RESULT_CHARS to "99999999999").maxResultChars)
        assertEquals(Config.MIN_MAX_RESULT_CHARS, configWith(Config.ENV_MAX_RESULT_CHARS to "5").maxResultChars)

        assertTrue(configWith(Config.ENV_TIMEOUT_MS to "30000").startupWarnings().isEmpty(), "nothing to say about a good value")
    }

    @Test
    fun `an api key is trimmed, and one that cannot be an http header value is refused without being quoted`() {
        // A Kubernetes secret made from a file, or a CRLF .env, ends in a line break.
        assertEquals("abc123", Config.fromEnv(mapOf(Config.ENV_API_KEY to "abc123\r\n")).apiKey)

        // Left in, Ktor refuses the header with an IllegalArgumentException whose message QUOTES the
        // value - and the tool layer reports that class of message to the model.
        for (bad in listOf("abc\n123", "abc 123", "abc\u00A0123", "\uFEFFabc123", "abc\u201C123")) {
            val message = assertFailsWith<IllegalStateException> { Config.fromEnv(mapOf(Config.ENV_API_KEY to bad)) }.message.orEmpty()
            assertTrue(Config.ENV_API_KEY in message)
            assertFalse("abc" in message || "123" in message, "the key must not be echoed: $message")
        }
    }

    @Test
    fun `an unknown region is refused without repeating what was typed`() {
        // Someone will paste the API key into the wrong variable one day.
        val message = assertFailsWith<IllegalArgumentException> {
            Config.fromEnv(mapOf(Config.ENV_API_KEY to "key", Config.ENV_REGION to "sk-live-0123456789"))
        }.message.orEmpty()
        assertFalse("0123456789" in message, message)
        assertTrue("us2" in message, "but do list the valid ones")
    }

    @Test
    fun `a base url port has to be a real port, and Ktor has to agree on the host`() {
        assertTrue(Config.ENV_BASE_URL in rejected(Config.ENV_BASE_URL to "https://proxy.corp.example:84430"))
        assertTrue(Config.ENV_BASE_URL in rejected(Config.ENV_BASE_URL to "https://proxy.corp.example:0"))
        assertEquals("https://proxy.corp.example:8443", configWith(Config.ENV_BASE_URL to "https://proxy.corp.example:8443").baseUrl)
        assertEquals("http://[::1]:8080", configWith(Config.ENV_BASE_URL to "http://[::1]:8080").baseUrl, "IPv6 loopback is local too")
    }

    @Test
    fun `a safety switch set to something unrecognised stops the server rather than staying off`() {
        // INSIGHTIDR_DISABLE_AUTO_UPDATE=y was read as "not set", and the server went on installing
        // updates for an operator who had asked it not to.
        for (variable in listOf(Config.ENV_DISABLE_AUTO_UPDATE, Config.ENV_DISABLE_UPDATE_CHECK)) {
            for (unclear in listOf("y", "enabled", "disable", "2", "tru")) {
                val message = rejected(variable to unclear)
                assertTrue(variable in message, message)
            }
        }
        assertTrue(configWith(Config.ENV_DISABLE_AUTO_UPDATE to "ON").autoUpdateDisabled)
        assertFalse(configWith(Config.ENV_DISABLE_AUTO_UPDATE to "No").autoUpdateDisabled)
    }

    @Test
    fun `allowed origins are normalised once, and one that cannot be an origin stops the server`() {
        // Dropped in silence, an unusable entry left the operator believing a browser client was
        // allowed while every request from it got 403 - and server_info still counted it.
        val config = configWith(Config.ENV_HTTP_ALLOWED_ORIGINS to " HTTPS://App.Example.com:443/ , app2.example.com , http://localhost:5173 ")
        assertEquals(
            listOf("https://app.example.com", "https://app2.example.com", "http://localhost:5173"),
            config.httpAllowedOrigins,
            "a bare host still means https, as it did in 0.3.1",
        )
        assertEquals(3, ServerFacts.from(config).httpAllowedOriginCount, "the count is of origins that will actually work")

        for (bad in listOf("https://app.example.com/ui", "https://user@app.example.com", "ftp://app.example.com", "https://")) {
            val message = rejected(Config.ENV_HTTP_ALLOWED_ORIGINS to "https://ok.example,$bad")
            assertTrue(Config.ENV_HTTP_ALLOWED_ORIGINS in message, message)
            assertTrue("entry 2" in message, "say which one: $message")
        }

        // "*" is documented as ignored, so it still is - but no longer without a word.
        val wildcard = configWith(Config.ENV_HTTP_ALLOWED_ORIGINS to "*,https://ok.example")
        assertEquals(listOf("https://ok.example"), wildcard.httpAllowedOrigins)
        assertTrue(wildcard.startupWarnings().any { "*" in it && Config.ENV_HTTP_ALLOWED_ORIGINS in it })
    }

    @Test
    fun `a rejected number is never repeated in the message`() {
        // Someone will paste the API key into the wrong variable one day.
        val message = rejected(Config.ENV_TIMEOUT_MS to "sk-live-0123456789abcdef")
        assertFalse("0123456789" in message, "was: $message")
    }

    @Test
    fun `numbers are trimmed, and unset or blank still means the default`() {
        assertEquals(30_000L, configWith(Config.ENV_TIMEOUT_MS to " 30000 ").requestTimeoutMillis)
        assertEquals(Config.DEFAULT_TIMEOUT_MS, configWith(Config.ENV_TIMEOUT_MS to "").requestTimeoutMillis)
        assertEquals(Config.DEFAULT_TIMEOUT_MS, configWith().requestTimeoutMillis)
        assertEquals(0, configWith(Config.ENV_SPOOL_RETENTION_HOURS to "0").spoolRetentionHours)
    }
}
