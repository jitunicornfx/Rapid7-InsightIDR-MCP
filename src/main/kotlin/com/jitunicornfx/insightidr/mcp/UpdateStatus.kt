package com.jitunicornfx.insightidr.mcp

import java.util.concurrent.atomic.AtomicReference

/**
 * What this process knows about updates, retained so a tool handler can report it WITHOUT making a
 * network call.
 *
 * [UpdateChecker.check] hits GitHub on every invocation — it has no caching, and the unauthenticated
 * rate limit is 60/hour/IP — so it runs exactly once per process, at startup, and the answer is kept
 * here. Process-wide, matching [ResultBudget] and [SpoolStore]: `--http` builds a fresh Server per
 * connection, but there is one Config, one startup check and one install attempt per process.
 *
 * This type deliberately holds no `Deferred` and exposes no suspending member, so a handler reading
 * [active] cannot block on a check that is still in flight even by accident.
 */
object UpdateStatus {

    enum class CheckState {
        /** `INSIGHTIDR_DISABLE_UPDATE_CHECK` / `--no-update-check`: no check was started. */
        DISABLED,

        /** The check was started at startup and has not reported yet. */
        PENDING,

        /** The check finished; see [Snapshot.result]. */
        COMPLETED,

        /** The check was cancelled or never produced a result (e.g. shutdown during startup). */
        FAILED,
    }

    enum class InstallState { NOT_ATTEMPTED, DISABLED, INSTALLED, STAGED, FAILED, SKIPPED }

    /** An immutable, atomically-swapped view. Every field is server-authored or allow-listed. */
    data class Snapshot(
        val checkState: CheckState = CheckState.PENDING,
        /** Null unless [checkState] is COMPLETED. `latestVersion` has passed UpdateChecker's allow-list. */
        val result: UpdateChecker.Result? = null,
        val installState: InstallState = InstallState.NOT_ATTEMPTED,
        /** The version installed or staged; allow-listed by UpdateChecker before it reaches here. */
        val installedVersion: String? = null,
        /** Server-authored failure text from [UpdateInstaller.Outcome.Failed]; never remote content. */
        val failureReason: String? = null,
        /**
         * For a STAGED install: whether the shutdown hook that applies it was actually registered.
         * False when the JVM was already shutting down, in which case the staged file will NOT be
         * applied automatically.
         */
        val appliesOnExit: Boolean = false,
        /** False under `gradle run` and in tests, where [UpdateInstaller.runningJar] is null. */
        val runningFromJar: Boolean = false,
    ) {
        /** True when new code is on disk and only a restart is needed to run it. */
        val restartRequired: Boolean
            get() = installState == InstallState.INSTALLED ||
                (installState == InstallState.STAGED && appliesOnExit)
    }

    private val state = AtomicReference(Snapshot())

    /** The process-wide snapshot. Cheap, non-blocking, safe from any thread. */
    val active: Snapshot get() = state.get()

    fun markCheckDisabled() = update { it.copy(checkState = CheckState.DISABLED) }

    fun markCheckStarted() = update { it.copy(checkState = CheckState.PENDING) }

    /** Only downgrades a PENDING state, so a cancellation cannot erase an already-recorded result. */
    fun markCheckFailed() = update {
        if (it.checkState == CheckState.PENDING) it.copy(checkState = CheckState.FAILED) else it
    }

    fun recordCheck(result: UpdateChecker.Result) =
        update { it.copy(checkState = CheckState.COMPLETED, result = result) }

    fun markRunningFromJar(fromJar: Boolean) = update { it.copy(runningFromJar = fromJar) }

    fun markAutoInstallDisabled() = update { it.copy(installState = InstallState.DISABLED) }

    fun recordInstall(outcome: UpdateInstaller.Outcome) = update { current ->
        when (outcome) {
            is UpdateInstaller.Outcome.Installed ->
                current.copy(installState = InstallState.INSTALLED, installedVersion = outcome.version)

            is UpdateInstaller.Outcome.Staged ->
                // appliesOnExit stays false until the shutdown hook is actually registered.
                current.copy(installState = InstallState.STAGED, installedVersion = outcome.version)

            is UpdateInstaller.Outcome.Failed ->
                current.copy(installState = InstallState.FAILED, failureReason = outcome.reason)

            UpdateInstaller.Outcome.Skipped -> current.copy(installState = InstallState.SKIPPED)
        }
    }

    /** Called once the shutdown hook that applies a staged install has been registered. */
    fun markStagedAppliesOnExit() = update { it.copy(appliesOnExit = true) }

    /** Restore the initial snapshot. Tests only — the holder is process-wide. */
    internal fun reset() = state.set(Snapshot())

    // updateAndGet rather than a @Volatile read-modify-write: recordCheck and recordInstall run on
    // different threads, and a reader must never observe a torn copy().
    private fun update(transform: (Snapshot) -> Snapshot) {
        state.updateAndGet(transform)
    }
}

/**
 * One server-authored sentence describing [snapshot], for the `insightidr_server_info` payload.
 *
 * The install wording deliberately matches [notifyUpdateInstalled]'s, so the tool and the push
 * notification tell an operator the same thing.
 */
internal fun updateSummary(snapshot: UpdateStatus.Snapshot): String {
    if (!snapshot.runningFromJar && snapshot.installState == UpdateStatus.InstallState.NOT_ATTEMPTED) {
        // install() would fail with "nothing to replace", so say so before discussing updates.
        return "This server is not running from a JAR (e.g. started from class files), so updates " +
            "cannot be installed automatically."
    }
    val install = when (snapshot.installState) {
        UpdateStatus.InstallState.INSTALLED ->
            "Version ${snapshot.installedVersion} has been installed. Restart the server to run it."

        UpdateStatus.InstallState.STAGED -> if (snapshot.appliesOnExit) {
            "Version ${snapshot.installedVersion} has been downloaded and verified, and will be " +
                "applied when this server exits. Restart the server to run it."
        } else {
            "Version ${snapshot.installedVersion} has been downloaded and verified but will NOT be " +
                "applied automatically, because the server was already shutting down. Update it manually."
        }

        UpdateStatus.InstallState.FAILED ->
            "Automatic update did not complete: ${snapshot.failureReason}"

        UpdateStatus.InstallState.DISABLED ->
            "Automatic installation is disabled (${Config.ENV_DISABLE_AUTO_UPDATE} / --no-auto-update); " +
                "update it manually."

        UpdateStatus.InstallState.SKIPPED ->
            "There was nothing to install for the available release."

        UpdateStatus.InstallState.NOT_ATTEMPTED -> null
    }

    val check = when (snapshot.checkState) {
        UpdateStatus.CheckState.DISABLED ->
            "The startup update check is disabled (${Config.ENV_DISABLE_UPDATE_CHECK} / " +
                "--no-update-check), so whether a newer release exists is not known."

        UpdateStatus.CheckState.PENDING ->
            "The startup update check has not finished yet; ask again in a moment."

        UpdateStatus.CheckState.FAILED ->
            "The startup update check did not complete, so whether a newer release exists is not known."

        UpdateStatus.CheckState.COMPLETED ->
            snapshot.result?.message() ?: "The update check completed but reported nothing."
    }

    return if (install == null) check else "$check $install"
}
