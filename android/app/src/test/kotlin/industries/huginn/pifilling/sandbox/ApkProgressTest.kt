package industries.huginn.pifilling.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The apk progress regex is the only place the install bar gets its numbers,
 * so a regression here is the difference between a bar that moves and one that
 * sits still. Both phases must match: the download is where most of the time
 * goes, and counting only the install lines is exactly the bug that made the
 * bar look frozen.
 */
class ApkProgressTest {

    @Test
    fun `download lines match with the artifact name`() {
        val m = APK_PROGRESS_REGEX.find("(3/24) Downloading openssl-3.1.0-r3.apk")
        assertNotNull("a download line must parse", m)
        assertEquals("3", m!!.groupValues[1])
        assertEquals("24", m.groupValues[2])
        assertEquals("openssl-3.1.0-r3.apk", m.groupValues[3])
    }

    @Test
    fun `install lines match with the bare name`() {
        val m = APK_PROGRESS_REGEX.find("(3/24) Installing openssl (3.1.0-r3)")
        assertNotNull(m)
        assertEquals("3", m!!.groupValues[1])
        assertEquals("24", m.groupValues[2])
        // Stops at the space before the version parenthetical.
        assertEquals("openssl", m.groupValues[3])
    }

    @Test
    fun `the counter is shared across the two phases`() {
        val dl = APK_PROGRESS_REGEX.find("(1/2) Downloading a-1.apk")
        val ins = APK_PROGRESS_REGEX.find("(1/2) Installing a (1)")
        assertNotNull(dl); assertNotNull(ins)
        // Both report the same (done,total) space; the UI keeps the bar from
        // reading as "going backwards" because the label names the phase.
        assertEquals(dl!!.groupValues[1], ins!!.groupValues[1])
        assertEquals(dl.groupValues[2], ins.groupValues[2])
    }

    @Test
    fun `non-progress lines do not match`() {
        assertNull(APK_PROGRESS_REGEX.find("Installing (3.1.0-r3)"))
        assertNull(APK_PROGRESS_REGEX.find("WARNING: updating ... IO ERROR"))
        assertNull(APK_PROGRESS_REGEX.find("(no counter here) Installing foo"))
    }

    @Test
    fun `a full counter line does match end to end`() {
        assertEquals(
            true,
            APK_PROGRESS_REGEX.matches("(3/24) Downloading x.apk"),
        )
    }
}
