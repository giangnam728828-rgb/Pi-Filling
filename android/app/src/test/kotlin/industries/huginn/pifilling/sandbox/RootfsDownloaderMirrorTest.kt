package industries.huginn.pifilling.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The apk repositories file decides which mirror the guest tries first. The
 * screenshot failure was apk fetching `APKINDEX.tar.gz` from European CDNs the
 * device could not reach while Tsinghua sat unused at position six, so the
 * ordering (and the user's ability to override it) is the fix, not a nicety.
 */
class RootfsDownloaderMirrorTest {

    @Test
    fun `an explicit preferred mirror leads`() {
        val preferred = RootfsDownloader.MIRRORS.first()
        val ordered = RootfsDownloader().orderMirrors(preferred)
        assertEquals(preferred, ordered.first())
        assertEquals("every mirror still listed", RootfsDownloader.MIRRORS.size, ordered.size)
        assertEquals("no duplicates", ordered.size, ordered.toSet().size)
    }

    @Test
    fun `an unknown mirror url is honoured verbatim`() {
        val ordered = RootfsDownloader().orderMirrors("https://mirror.example.org/alpine")
        assertEquals("https://mirror.example.org/alpine", ordered.first())
        // The known mirrors follow, so a failure still falls back.
        assertTrue(ordered.containsAll(RootfsDownloader.MIRRORS))
    }

    @Test
    fun `null keeps the full mirror list in canonical order`() {
        val ordered = RootfsDownloader().orderMirrors(null)
        assertEquals(RootfsDownloader.MIRRORS, ordered)
    }

    @Test
    fun `auto language zh leads with the Tsinghua mirror`() {
        val ordered = RootfsDownloader().orderMirrors(null, "zh")
        val tuna = RootfsDownloader.MIRRORS.first { "tuna" in it }
        assertEquals(tuna, ordered.first())
    }

    @Test
    fun `auto language zh-TW also leads with the Tsinghua mirror`() {
        val ordered = RootfsDownloader().orderMirrors(null, "zh-TW")
        val tuna = RootfsDownloader.MIRRORS.first { "tuna" in it }
        assertEquals(tuna, ordered.first())
    }

    @Test
    fun `auto language en keeps the canonical CDN order`() {
        val ordered = RootfsDownloader().orderMirrors(null, "en")
        assertEquals(RootfsDownloader.MIRRORS, ordered)
    }

    @Test
    fun `an explicit preferred choice wins over the auto language`() {
        val explicit = RootfsDownloader.MIRRORS.last()
        val ordered = RootfsDownloader().orderMirrors(explicit, "zh")
        assertEquals(explicit, ordered.first())
    }

    @Test
    fun `writeRepositories emits main and community for each mirror`() {
        val rootfs = java.nio.file.Files.createTempDirectory("rootfs").toFile()
        try {
            RootfsDownloader().writeRepositories(rootfs, listOf("https://m.example.org/alpine"))
            val text = java.io.File(rootfs, "etc/apk/repositories").readText()
            assertTrue(text.contains("https://m.example.org/alpine/${RootfsDownloader.ALPINE_BRANCH}/main"))
            assertTrue(text.contains("https://m.example.org/alpine/${RootfsDownloader.ALPINE_BRANCH}/community"))
        } finally {
            rootfs.deleteRecursively()
        }
    }
}
