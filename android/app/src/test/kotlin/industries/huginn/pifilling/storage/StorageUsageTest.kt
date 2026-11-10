package industries.huginn.pifilling.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The distinction that matters here is "zero" versus "unknown".
 *
 * A sandbox that is genuinely empty and one that could not be read look the same
 * on a settings row, and telling a user "0 B" when their 1.2 GB sandbox is
 * unreadable would make them think deleting it had already worked.
 */
class StorageUsageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(path: String, bytes: Int): File {
        val f = File(temp.root, path)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes))
        return f
    }

    @Test
    fun `an empty directory measures zero rather than failing`() {
        val report = StorageUsageReader.read(temp.root)
        assertTrue("expected Measured, got $report", report is StorageReport.Measured)
        assertEquals(0L, (report as StorageReport.Measured).usage.totalBytes)
    }

    @Test
    fun `categories are attributed separately`() {
        write("linux-sandbox/rootfs/big.bin", 5000)
        write("sandbox-meta/recycle.json", 100)
        write("chat-history.json", 200)

        val usage = (StorageUsageReader.read(temp.root) as StorageReport.Measured).usage
        assertEquals(5000L, usage.sandboxBytes)
        assertEquals(100L, usage.metaBytes)
        assertEquals(200L, usage.historyBytes)
        // 5000 + 100 + 200 accounted for; nothing left over.
        assertEquals(0L, usage.otherBytes)
    }

    @Test
    fun `unrelated files land in other`() {
        write("linux-sandbox/a.bin", 1000)
        write("something-else.dat", 300)

        val usage = (StorageUsageReader.read(temp.root) as StorageReport.Measured).usage
        assertEquals(1000L, usage.sandboxBytes)
        assertEquals(300L, usage.otherBytes)
    }

    @Test
    fun `a missing filesDir is Failed, not zero`() {
        val gone = File(temp.root, "does-not-exist")
        val report = StorageUsageReader.read(gone)
        assertTrue("expected Failed, got $report", report is StorageReport.Failed)
    }

    @Test
    fun `a missing subdirectory is zero, not an error`() {
        // No sandbox installed yet: that is a real zero, not a failure.
        write("chat-history.json", 50)
        val report = StorageUsageReader.read(temp.root)
        assertTrue(report is StorageReport.Measured)
        assertEquals(0L, (report as StorageReport.Measured).usage.sandboxBytes)
    }

    @Test
    fun `bytes format at sensible magnitudes`() {
        assertEquals("512 B", StorageUsageReader.format(512))
        assertEquals("2 KB", StorageUsageReader.format(2_000))
        assertEquals("1.5 MB", StorageUsageReader.format(1_500_000))
        assertEquals("1.2 GB", StorageUsageReader.format(1_200_000_000))
    }
}
