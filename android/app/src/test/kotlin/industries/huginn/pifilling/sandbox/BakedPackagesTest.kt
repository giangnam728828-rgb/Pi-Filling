package industries.huginn.pifilling.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The bundled arm64 image is baked with exactly the packages the code expects,
 * so a first run — and the "install tools" button — needs no network at all.
 *
 * That invariant is not self-evident from either side: `bake-rootfs.sh` builds
 * the tarball long before the app runs, and `APK_PACKAGES` in the manager is
 * what the app believes is present. If the two drift apart, the app either
 * skips a package that was never baked in (a missing tool at runtime) or tries
 * to download something it already has (the failure this test exists to
 * prevent: minutes of "fetch ... IO ERROR" for packages already installed).
 */
class BakedPackagesTest {

    private fun bakeScript(): File? {
        // Unit tests run with the module dir (android/app) as the working
        // directory, so the repo root is two levels up.
        val candidates = listOf(
            File("../../scripts/bake-rootfs.sh"),
            File("../scripts/bake-rootfs.sh"),
            File("scripts/bake-rootfs.sh"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    @Test
    fun `the code package list matches what the bake script installs`() {
        val script = bakeScript()
        assumeTrue("bake-rootfs.sh not reachable from the test working dir", script != null)

        val text = script!!.readText()
        // PKGS="a b c\n   d e f"   -> split on whitespace inside the quotes.
        val m = Regex("(?s)PKGS=\"(.*?)\"").find(text)
        assertTrue("PKGS declaration not found in bake-rootfs.sh", m != null)
        val baked = m!!.groupValues[1].split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

        val coded = LinuxSandboxManager.APK_PACKAGES
            .split(' ').filter { it.isNotBlank() }.toSet()

        assertEquals(
            "baked packages and APK_PACKAGES must not drift apart",
            baked.sorted(),
            coded.sorted(),
        )
    }
}
