package industries.huginn.pifilling.sandbox

/**
 * Lifecycle state of the Alpine sandbox.
 *
 * Surfaced as a StateFlow by [LinuxSandboxManager] so the UI can render real
 * progress and gate the agent until [Ready].
 *
 * Two design rules hold across every variant:
 *
 *  - **No invented progress.** A stage that cannot measure itself carries no
 *    fraction, and the UI shows an indeterminate indicator. A spinner that
 *    claims 42% because it guessed is worse than an honest spinner.
 *  - **The phase is structured, not prose.** Every failure names the stage and
 *    a code, so the UI can say what failed instead of printing a stack-trace
 *    string. The free-text `message` stays for the human-readable cause.
 *
 * Two capability flags replace the hand-written state lists the UI used to
 * carry. `allowsNewSession` is the single gate for starting an agent; adding a
 * variant without updating it is a compile error rather than a silent "the app
 * let me send a message and nothing happened".
 */
sealed interface SandboxState {

    /** The rootfs exists and is structurally usable (may still be upgrading). */
    val environmentPresent: Boolean get() = false

    /** A new agent session may be started right now. */
    val allowsNewSession: Boolean get() = false

    data object NotInstalled : SandboxState

    /** Pre-flight: what we are about to do, before touching the network or disk. */
    data class Checking(val step: CheckStep) : SandboxState

    enum class CheckStep { ABI, BUNDLED_ASSET, DISK_SPACE, EXISTING_ENV }

    /**
     * Fetching the minirootfs over the network.
     *
     * [bytesTotal] is null when the server sent no Content-Length; [fraction]
     * and [etaSeconds] then stay null and the UI must not guess.
     */
    data class Downloading(
        val mirrorIndex: Int,
        val mirrorCount: Int,
        val mirrorHost: String,
        val bytesRead: Long,
        val bytesTotal: Long?,
        val bytesPerSecond: Long?,
    ) : SandboxState {
        val fraction: Float?
            get() = bytesTotal?.takeIf { it > 0 }?.let { bytesRead.toFloat() / it }

        val etaSeconds: Long?
            get() {
                val f = fraction ?: return null
                val speed = bytesPerSecond?.takeIf { it > 0 } ?: return null
                val total = bytesTotal ?: return null
                if (f >= 1f) return 0
                return ((total - bytesRead) / speed).coerceAtLeast(0)
            }
    }

    /**
     * Unpacking the rootfs.
     *
     * [bytesRead] counts **compressed bytes consumed**, not files written — the
     * two are related but not equal, and the UI says so rather than implying a
     * write-completion percentage.
     *
     * [fromBundled] distinguishes "unpacking the image that shipped in the APK"
     * from "unpacking something we just downloaded". Conflating them was how the
     * app used to show a download progress bar for an operation that never
     * touched the network.
     */
    data class Extracting(
        val fromBundled: Boolean,
        val bytesRead: Long,
        val bytesTotal: Long?,
        val currentEntry: String?,
    ) : SandboxState {
        val fraction: Float?
            get() = bytesTotal?.takeIf { it > 0 }?.let { bytesRead.toFloat() / it }
    }

    /** Running `apk add` for the toolchain. */
    data class Installing(
        val step: InstallStep,
        val currentPackage: String?,
        val completed: Int,
        val total: Int,
    ) : SandboxState

    enum class InstallStep { APK_UPDATE, APK_ADD, WRAPPER_SOURCE }

    /** Copying the wrapper and its dependencies into the sandbox. */
    data class Deploying(val step: DeployStep, val detail: String?) : SandboxState

    enum class DeployStep { PENDING, RUNNING, DONE, FAILED }

    /** Proving the environment actually works before calling it ready. */
    data class Verifying(val item: VerifyItem) : SandboxState

    enum class VerifyItem { ROOTFS, NODE, GIT, WRAPPER, NODE_MODULES }

    /**
     * An existing sandbox is being topped up by a newer build.
     *
     * The environment stays present, but new sessions are refused: the files
     * being replaced are the ones a fresh agent would load.
     */
    data class Upgrading(val stage: UpgradeStage) : SandboxState {
        override val environmentPresent: Boolean get() = true
    }

    enum class UpgradeStage { CONFIG, PACKAGES, WRAPPER, VERIFY, TOOLS }

    /**
     * [verifiedAt] is when the last successful verification finished. It is not
     * refreshed by a cheap existence check — that would make it meaningless.
     */
    data class Ready(val verifiedAt: Long) : SandboxState {
        override val environmentPresent: Boolean get() = true
        override val allowsNewSession: Boolean get() = true
    }

    data class Error(
        val stage: Stage,
        val code: ErrorCode,
        val message: String,
        val retryable: Boolean,
    ) : SandboxState

    enum class Stage { CHECK, DOWNLOAD, EXTRACT, INSTALL, DEPLOY, VERIFY, UPGRADE }

    enum class ErrorCode {
        NETWORK,
        HTTP,
        CORRUPT_ARCHIVE,
        MISSING_ASSET,
        DISK_FULL,
        EXEC_FAILED,
        UNKNOWN,
    }

    /**
     * The user cancelled. [cleaned] and [failed] report what the cleanup managed
     * to remove, so a partial cleanup is visible instead of being reported as a
     * clean cancel.
     */
    data class Cancelled(
        val stage: Stage,
        val cleaned: List<String>,
        val failed: List<String>,
    ) : SandboxState
}

/**
 * Result envelope for a one-shot sandbox command, matching Kai's
 * `{success, stdout, stderr, exit_code, timed_out}` shape.
 */
data class CommandResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean,
)
