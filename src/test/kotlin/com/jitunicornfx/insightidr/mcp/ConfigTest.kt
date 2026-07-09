package com.jitunicornfx.insightidr.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `v1 base url defaults to the rest logs host per the v1 spec servers`() {
        val config = Config.fromEnv(mapOf(Config.ENV_API_KEY to "key", Config.ENV_REGION to "eu"))
        assertEquals("https://eu.rest.logs.insight.rapid7.com", config.v1BaseUrl)
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
}
