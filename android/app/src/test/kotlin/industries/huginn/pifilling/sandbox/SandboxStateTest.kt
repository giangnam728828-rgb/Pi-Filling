package industries.huginn.pifilling.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sandbox state is what the UI believes about the environment, so the
 * interesting cases are the ones where it could lie: a progress bar with no
 * measurement behind it, or a capability flag that lets the app start an agent
 * against a half-installed rootfs.
 */
class SandboxStateTest {

    // ---- progress must never be invented ------------------------------------

    @Test
    fun `download without a content length has no fraction`() {
        val state = SandboxState.Downloading(
            mirrorIndex = 2, mirrorCount = 6, mirrorHost = "tuna.tsinghua.edu.cn",
            bytesRead = 84_000_000, bytesTotal = null, bytesPerSecond = 2_400_000,
        )
        assertNull("no total means no percentage", state.fraction)
        assertNull("no total means no ETA", state.etaSeconds)
    }

    @Test
    fun `download with a content length reports a real fraction`() {
        val state = SandboxState.Downloading(
            mirrorIndex = 1, mirrorCount = 6, mirrorHost = "dl-cdn.alpinelinux.org",
            bytesRead = 50, bytesTotal = 200, bytesPerSecond = 10,
        )
        assertEquals(0.25f, state.fraction!!, 0.0001f)
        assertEquals(15L, state.etaSeconds)
    }

    @Test
    fun `eta needs both a fraction and a speed`() {
        val noSpeed = SandboxState.Downloading(1, 1, "h", 50, 200, null)
        assertNull(noSpeed.etaSeconds)

        val zeroSpeed = SandboxState.Downloading(1, 1, "h", 50, 200, 0)
        assertNull(zeroSpeed.etaSeconds)
    }

    @Test
    fun `finished download has zero eta rather than a negative one`() {
        val state = SandboxState.Downloading(1, 1, "h", 200, 200, 10)
        assertEquals(0L, state.etaSeconds)
    }

    @Test
    fun `extracting reports compressed-read progress only`() {
        val state = SandboxState.Extracting(
            fromBundled = true, bytesRead = 25, bytesTotal = 100, currentEntry = null,
        )
        assertEquals(0.25f, state.fraction!!, 0.0001f)

        val unknown = state.copy(bytesTotal = null)
        assertNull(unknown.fraction)
    }

    // ---- capability flags ---------------------------------------------------

    @Test
    fun `only Ready allows a new session`() {
        assertTrue(SandboxState.Ready(verifiedAt = 1L).allowsNewSession)
        assertTrue(SandboxState.Ready(verifiedAt = 1L).environmentPresent)

        val others = listOf(
            SandboxState.NotInstalled,
            SandboxState.Checking(SandboxState.CheckStep.ABI),
            SandboxState.Downloading(1, 1, "h", 0, null, null),
            SandboxState.Extracting(false, 0, null, null),
            SandboxState.Installing(SandboxState.InstallStep.APK_ADD, "nodejs", 0, 3),
            SandboxState.Deploying(SandboxState.DeployStep.RUNNING, null),
            SandboxState.Verifying(SandboxState.VerifyItem.NODE),
            SandboxState.Upgrading(SandboxState.UpgradeStage.PACKAGES),
            SandboxState.Error(
                SandboxState.Stage.EXTRACT, SandboxState.ErrorCode.CORRUPT_ARCHIVE, "x", true,
            ),
            SandboxState.Cancelled(SandboxState.Stage.DOWNLOAD, emptyList(), emptyList()),
        )
        others.forEach {
            assertFalse("${it::class.simpleName} must not allow a session", it.allowsNewSession)
        }
    }

    @Test
    fun `upgrading keeps the environment present but blocks new sessions`() {
        val state = SandboxState.Upgrading(SandboxState.UpgradeStage.WRAPPER)
        assertTrue("the old environment is still there", state.environmentPresent)
        assertFalse("its files are being replaced", state.allowsNewSession)
    }

    // ---- failure classification --------------------------------------------

    @Test
    fun `an error always carries the stage it failed at`() {
        val error = SandboxState.Error(
            stage = SandboxState.Stage.DOWNLOAD,
            code = SandboxState.ErrorCode.NETWORK,
            message = "all 6 Alpine mirrors failed",
            retryable = true,
        )
        assertEquals(SandboxState.Stage.DOWNLOAD, error.stage)
        assertEquals(SandboxState.ErrorCode.NETWORK, error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun `cancelled reports what the cleanup managed`() {
        val state = SandboxState.Cancelled(
            stage = SandboxState.Stage.EXTRACT,
            cleaned = listOf("rootfs.tar.gz"),
            failed = listOf("staging-7"),
        )
        assertEquals(SandboxState.Stage.EXTRACT, state.stage)
        assertEquals(1, state.cleaned.size)
        assertEquals("a partial cleanup must be visible", 1, state.failed.size)
        assertFalse(state.allowsNewSession)
    }

    // ---- structural invariants ---------------------------------------------

    @Test
    fun `ready keeps the time it was verified`() {
        val at = 1_700_000_000_000L
        assertEquals(at, SandboxState.Ready(at).verifiedAt)
    }

    @Test
    fun `installing tracks package progress without exceeding the total`() {
        val state = SandboxState.Installing(
            step = SandboxState.InstallStep.APK_ADD, currentPackage = "nodejs", completed = 3, total = 12,
        )
        assertTrue(state.completed <= state.total)
        assertNotNull(state.currentPackage)
    }
}
