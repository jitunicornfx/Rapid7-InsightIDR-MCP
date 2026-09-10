package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.serverInfo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerInfoTest {

    private val facts = ServerFacts.from(
        Config(
            apiKey = "super-secret-key",
            region = Region.US,
            baseUrl = "https://us.api.insight.rapid7.com",
            requestTimeoutMillis = 60_000,
            spoolDirectory = "/home/analyst/spool",
            httpAllowedOrigins = listOf("https://a.example", "https://b.example"),
        ),
    )

    private fun payload(
        snapshot: UpdateStatus.Snapshot,
        toolCount: Int = 146,
    ): kotlinx.serialization.json.JsonObject =
        serverInfo(facts, snapshot, toolCount).jsonObject

    private fun text(element: JsonElement) =
        JsonCodec.compact.encodeToString(JsonElement.serializer(), element)

    private fun update(json: kotlinx.serialization.json.JsonObject) = json["update"]!!.jsonObject
    private fun runtime(json: kotlinx.serialization.json.JsonObject) = json["runtime"]!!.jsonObject
    private fun str(json: kotlinx.serialization.json.JsonObject, key: String) =
        json[key]!!.jsonPrimitive.content

    private fun jar(snapshot: UpdateStatus.Snapshot) = snapshot.copy(runningFromJar = true)

    @Test
    fun `identity and tool count are reported`() {
        val json = payload(jar(UpdateStatus.Snapshot()), toolCount = 146)
        assertEquals(SERVER_NAME, str(json, "server"))
        assertEquals(SERVER_VERSION, str(json, "version"))
        assertEquals(146, json["toolCount"]!!.jsonPrimitive.int)
        assertEquals("generated", str(json["build"]!!.jsonObject, "versionSource"))
    }

    @Test
    fun `a disabled check says so instead of implying the server is current`() {
        val json = payload(jar(UpdateStatus.Snapshot(checkState = UpdateStatus.CheckState.DISABLED)))
        assertEquals("disabled", str(update(json), "checkState"))
        assertFalse(update(json)["updateAvailable"]!!.jsonPrimitive.boolean)
        assertNull(update(json)["latestVersion"])
        assertTrue(Config.ENV_DISABLE_UPDATE_CHECK in str(update(json), "summary"))
    }

    @Test
    fun `a check still running is reported as pending, never as up to date`() {
        val json = payload(jar(UpdateStatus.Snapshot()))
        assertEquals("pending", str(update(json), "checkState"))
        assertTrue("not finished yet" in str(update(json), "summary"))
    }

    @Test
    fun `a check that never completed is reported as failed`() {
        val json = payload(jar(UpdateStatus.Snapshot(checkState = UpdateStatus.CheckState.FAILED)))
        assertEquals("failed", str(update(json), "checkState"))
        assertTrue("did not complete" in str(update(json), "summary"))
    }

    @Test
    fun `an up-to-date server reports no update and no restart`() {
        val json = payload(
            jar(
                UpdateStatus.Snapshot(
                    checkState = UpdateStatus.CheckState.COMPLETED,
                    result = UpdateChecker.Result(updateAvailable = false, currentVersion = SERVER_VERSION),
                ),
            ),
        )
        assertFalse(update(json)["updateAvailable"]!!.jsonPrimitive.boolean)
        assertFalse(update(json)["restartRequired"]!!.jsonPrimitive.boolean)
        assertTrue("up to date" in str(update(json), "summary"))
    }

    @Test
    fun `an available but uninstalled update names the version and does not claim a restart helps`() {
        val json = payload(
            jar(
                UpdateStatus.Snapshot(
                    checkState = UpdateStatus.CheckState.COMPLETED,
                    result = UpdateChecker.Result(true, currentVersion = "0.2.0", latestVersion = "0.3.0"),
                ),
            ),
        )
        assertTrue(update(json)["updateAvailable"]!!.jsonPrimitive.boolean)
        assertEquals("0.3.0", str(update(json), "latestVersion"))
        assertFalse(
            update(json)["restartRequired"]!!.jsonPrimitive.boolean,
            "nothing is on disk yet, so a restart would achieve nothing",
        )
    }

    @Test
    fun `auto-install disabled is reported distinctly from a failure`() {
        val json = payload(jar(UpdateStatus.Snapshot(installState = UpdateStatus.InstallState.DISABLED)))
        assertEquals("disabled", str(update(json), "installState"))
        assertTrue(Config.ENV_DISABLE_AUTO_UPDATE in str(update(json), "summary"))
    }

    @Test
    fun `an installed update requires a restart`() {
        val json = payload(
            jar(
                UpdateStatus.Snapshot(
                    installState = UpdateStatus.InstallState.INSTALLED,
                    installedVersion = "0.3.0",
                ),
            ),
        )
        assertEquals("installed", str(update(json), "installState"))
        assertTrue(update(json)["restartRequired"]!!.jsonPrimitive.boolean)
        assertTrue("Restart the server" in str(update(json), "summary"))
    }

    @Test
    fun `a staged update that will self-apply requires a restart`() {
        val json = payload(
            jar(
                UpdateStatus.Snapshot(
                    installState = UpdateStatus.InstallState.STAGED,
                    installedVersion = "0.3.0",
                    appliesOnExit = true,
                ),
            ),
        )
        assertEquals("staged", str(update(json), "installState"))
        assertTrue(update(json)["appliesOnExit"]!!.jsonPrimitive.boolean)
        assertTrue(update(json)["restartRequired"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a staged update whose hook never registered does not promise a restart will help`() {
        val json = payload(
            jar(
                UpdateStatus.Snapshot(
                    installState = UpdateStatus.InstallState.STAGED,
                    installedVersion = "0.3.0",
                    appliesOnExit = false,
                ),
            ),
        )
        assertFalse(update(json)["appliesOnExit"]!!.jsonPrimitive.boolean)
        assertFalse(
            update(json)["restartRequired"]!!.jsonPrimitive.boolean,
            "restarting would not apply it, so the tool must not say a restart is required",
        )
        assertTrue("NOT be applied automatically" in str(update(json), "summary"))
    }

    @Test
    fun `a failed install surfaces the server-authored reason`() {
        val json = payload(
            jar(
                UpdateStatus.Snapshot(
                    installState = UpdateStatus.InstallState.FAILED,
                    failureReason = "SHA-256 mismatch",
                ),
            ),
        )
        assertEquals("failed", str(update(json), "installState"))
        assertEquals("SHA-256 mismatch", str(update(json), "failureReason"))
        assertFalse(update(json)["restartRequired"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `not running from a JAR is stated plainly`() {
        val json = payload(UpdateStatus.Snapshot(runningFromJar = false))
        assertFalse(update(json)["runningFromJar"]!!.jsonPrimitive.boolean)
        assertTrue("not running from a JAR" in str(update(json), "summary"))
    }

    @Test
    fun `install state names are camelCase`() {
        val json = payload(jar(UpdateStatus.Snapshot(installState = UpdateStatus.InstallState.NOT_ATTEMPTED)))
        assertEquals("notAttempted", str(update(json), "installState"))
    }

    @Test
    fun `runtime configuration is reported`() {
        val json = payload(jar(UpdateStatus.Snapshot()))
        assertEquals("us", str(runtime(json), "region"))
        assertEquals("https://us.api.insight.rapid7.com", str(runtime(json), "baseUrl"))
        assertEquals(200_000, runtime(json)["maxResultChars"]!!.jsonPrimitive.int)
        assertTrue(runtime(json)["spoolDirectoryConfigured"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `no absolute host path and no secret is ever emitted`() {
        // The facts above deliberately carry a real-looking spool path and an API key, so this is a
        // live assertion rather than a vacuous one.
        val text = text(serverInfo(facts, jar(UpdateStatus.Snapshot()), 146))
        assertFalse("/home/analyst/spool" in text, "the spool path must never reach the model")
        assertFalse("super-secret-key" in text, "the API key must never reach the model")
        System.getProperty("user.home")?.let {
            assertFalse(it in text, "no host home directory may appear in the payload")
        }
    }

    @Test
    fun `allow-listed origins are counted, not listed`() {
        val json = payload(jar(UpdateStatus.Snapshot()))
        assertEquals(2, runtime(json)["httpAllowedOriginCount"]!!.jsonPrimitive.int)
        assertFalse("a.example" in text(serverInfo(facts, jar(UpdateStatus.Snapshot()), 146)))
    }
}

class UpdateStatusTest {

    @kotlin.test.AfterTest
    fun reset() = UpdateStatus.reset()

    @Test
    fun `a cancelled await cannot erase an already-recorded result`() {
        UpdateStatus.recordCheck(UpdateChecker.Result(updateAvailable = false, currentVersion = "0.2.0"))
        UpdateStatus.markCheckFailed()
        assertEquals(UpdateStatus.CheckState.COMPLETED, UpdateStatus.active.checkState)
    }

    @Test
    fun `markCheckFailed downgrades only a pending check`() {
        UpdateStatus.markCheckStarted()
        UpdateStatus.markCheckFailed()
        assertEquals(UpdateStatus.CheckState.FAILED, UpdateStatus.active.checkState)
    }

    @Test
    fun `a skipped install never claims a restart is required`() {
        UpdateStatus.recordInstall(UpdateInstaller.Outcome.Skipped)
        assertEquals(UpdateStatus.InstallState.SKIPPED, UpdateStatus.active.installState)
        assertFalse(UpdateStatus.active.restartRequired)
    }

    @Test
    fun `a staged install only requires a restart once its hook is registered`() {
        UpdateStatus.recordInstall(UpdateInstaller.Outcome.Staged("0.3.0", "/tmp/x.new", "abc"))
        assertFalse(UpdateStatus.active.restartRequired, "the hook has not been registered yet")
        UpdateStatus.markStagedAppliesOnExit()
        assertTrue(UpdateStatus.active.restartRequired)
    }

    @Test
    fun `reset restores the initial snapshot`() {
        UpdateStatus.recordInstall(UpdateInstaller.Outcome.Installed("0.3.0", "/opt/app.jar"))
        UpdateStatus.reset()
        assertEquals(UpdateStatus.CheckState.PENDING, UpdateStatus.active.checkState)
        assertEquals(UpdateStatus.InstallState.NOT_ATTEMPTED, UpdateStatus.active.installState)
    }
}
