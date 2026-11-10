package industries.huginn.pifilling.sandbox.meta

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The store's whole job is to never let an interruption look like success.
 *
 * These tests inject a failure at each of the four write steps and assert the
 * store reports that step — because the failure modes are not interchangeable:
 * a crash before the rename leaves the old contents intact, a crash after it
 * leaves the new contents live but undurable, and a caller that cannot tell
 * them apart cannot decide between retrying and recovering.
 */
class AtomicStoreTest {

    private lateinit var dir: File
    private lateinit var store: AtomicStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("atomic-store").toFile()
        store = AtomicStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---- the happy path -----------------------------------------------------

    @Test
    fun `write then read round-trips and reports a digest`() {
        assertEquals(WriteResult.Ok, store.writeText("a.txt", "hello"))
        val r = store.read("a.txt")
        assertTrue(r is ReadResult.Found)
        val found = r as ReadResult.Found
        assertEquals("hello", String(found.bytes, Charsets.UTF_8))
        // Full SHA-256, 64 hex characters — not a truncated convenience value.
        assertEquals(64, found.digest.length)
        assertEquals(found.digest, AtomicStore.sha256Hex("hello".toByteArray()))
    }

    @Test
    fun `a second write replaces the first`() {
        assertEquals(WriteResult.Ok, store.writeText("a.txt", "one"))
        assertEquals(WriteResult.Ok, store.writeText("a.txt", "two"))
        val found = store.read("a.txt") as ReadResult.Found
        assertEquals("two", String(found.bytes))
    }

    @Test
    fun `absent and unknown are different answers`() {
        // Never written: genuinely absent.
        assertEquals(ReadResult.Absent, store.read("nope.txt"))
        // A path that escapes the store: unknown, because we refused to look.
        val r = store.read("../outside.txt")
        assertTrue("escaping path must be Unknown, not Absent", r is ReadResult.Unknown)
    }

    // ---- path safety --------------------------------------------------------

    @Test
    fun `escaping names are rejected`() {
        val bad = listOf(
            "../x", "a/../../x", "/abs", "", "  ", "a//b", "./a", "a/./b", "C:x",
        )
        for (name in bad) {
            assertNull("must reject '$name'", store.resolveSafe(name))
            val w = store.writeText(name, "x")
            assertTrue(
                "'$name' must fail with PATH_REJECTED",
                w is WriteResult.Failed && w.stage == WriteStage.PATH_REJECTED,
            )
        }
    }

    @Test
    fun `a nested name inside the store is allowed`() {
        assertEquals(WriteResult.Ok, store.writeText("sub/dir/a.txt", "ok"))
        assertTrue(File(dir, "sub/dir/a.txt").isFile)
    }

    @Test
    fun `a symlink pointing out of the store is refused`() {
        val outside = Files.createTempDirectory("outside").toFile()
        try {
            File(outside, "secret.txt").writeText("secret")
            val link = File(dir, "link")
            Files.createSymbolicLink(link.toPath(), File(outside, "secret.txt").toPath())
            // The link itself is a symlink, so resolveSafe refuses the name.
            assertNull(store.resolveSafe("link"))
            assertTrue(store.read("link") is ReadResult.Unknown)
        } finally {
            outside.deleteRecursively()
        }
    }

    // ---- fault injection: one stage at a time -------------------------------

    private fun failAt(stage: WriteStage) {
        store.testHook = { s, _ -> if (s == stage) throw IOException("injected at $s") }
    }

    @Test
    fun `failure at TEMP_WRITE is reported and leaves no target`() {
        failAt(WriteStage.TEMP_WRITE)
        val r = store.writeText("a.txt", "x")
        assertTrue(r is WriteResult.Failed)
        assertEquals(WriteStage.TEMP_WRITE, (r as WriteResult.Failed).stage)
        assertFalse("no target must appear", File(dir, "a.txt").exists())
    }

    @Test
    fun `failure at FILE_FSYNC is reported and leaves no target`() {
        failAt(WriteStage.FILE_FSYNC)
        val r = store.writeText("a.txt", "x")
        assertEquals(WriteStage.FILE_FSYNC, (r as WriteResult.Failed).stage)
        assertFalse(File(dir, "a.txt").exists())
    }

    @Test
    fun `failure at RENAME is reported and leaves the old contents intact`() {
        assertEquals(WriteResult.Ok, store.writeText("a.txt", "old"))
        failAt(WriteStage.RENAME)
        val r = store.writeText("a.txt", "new")
        assertEquals(WriteStage.RENAME, (r as WriteResult.Failed).stage)
        // The previous value must survive: this is the difference between a
        // failed write and a lost file.
        val found = store.read("a.txt") as ReadResult.Found
        assertEquals("old", String(found.bytes))
    }

    @Test
    fun `failure at DIR_FSYNC is reported as uncertain rather than success`() {
        failAt(WriteStage.DIR_FSYNC)
        val r = store.writeText("a.txt", "x")
        assertEquals(WriteStage.DIR_FSYNC, (r as WriteResult.Failed).stage)
        // The bytes are live (the rename happened) but durability is unknown.
        // What matters is that the store did NOT claim Ok.
        assertTrue(File(dir, "a.txt").exists())
    }

    @Test
    fun `no temp file survives any failure`() {
        for (stage in listOf(
            WriteStage.TEMP_WRITE, WriteStage.FILE_FSYNC,
            WriteStage.RENAME, WriteStage.DIR_FSYNC,
        )) {
            store.testHook = { s, _ -> if (s == stage) throw IOException("boom") }
            store.writeText("a.txt", "x")
            val leftovers = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
            assertTrue("stage $stage left a temp file: $leftovers", leftovers.isEmpty())
        }
    }

    // ---- concurrency --------------------------------------------------------

    @Test
    fun `concurrent writes to the same target do not tear`() {
        // A fixed temp name would let two writers share one temporary path:
        // both would report Ok, and the last rename would silently win, so the
        // loser's bytes vanish without anyone being told. The counter makes each
        // writer's temp file its own.
        val writers = 8
        val payload = (0 until writers).map { i -> "value-$i".repeat(64) }
        val threads = payload.map { body ->
            Thread {
                store.writeText("race.txt", body)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Whatever won, the file must hold exactly one writer's complete payload
        // — not a mixture and not a truncation.
        val found = store.read("race.txt") as ReadResult.Found
        val text = String(found.bytes, Charsets.UTF_8)
        assertTrue(
            "content must be one complete payload, got ${text.length} chars",
            payload.contains(text),
        )
        // And no temporary file may be left behind.
        val leftovers = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("temp files leaked: $leftovers", leftovers.isEmpty())
    }

    // ---- directory digest ---------------------------------------------------

    @Test
    fun `directory digest is stable across identical trees`() {
        val a = Files.createTempDirectory("d1").toFile()
        val b = Files.createTempDirectory("d2").toFile()
        try {
            for (d in listOf(a, b)) {
                File(d, "x.txt").writeText("same")
                File(d, "sub").mkdirs()
                File(d, "sub/y.txt").writeText("also same")
            }
            val da = AtomicStore(a).dirDigest()
            val db = AtomicStore(b).dirDigest()
            assertEquals(da.hash, db.hash)
            assertTrue(da.complete && da.stable)
        } finally {
            a.deleteRecursively(); b.deleteRecursively()
        }
    }

    @Test
    fun `directory digest changes when content changes`() {
        File(dir, "x.txt").writeText("one")
        val before = store.dirDigest().hash
        File(dir, "x.txt").writeText("two")
        assertNotEquals(before, store.dirDigest().hash)
    }

    @Test
    fun `an in-flight temp file is not part of the tree identity`() {
        File(dir, "x.txt").writeText("same")
        val clean = store.dirDigest().hash
        File(dir, ".x.txt.tmp").writeText("half written")
        assertEquals(clean, store.dirDigest().hash)
    }

    @Test
    fun `directory digest is full length and reports completeness`() {
        File(dir, "x.txt").writeText("x")
        val d = store.dirDigest()
        assertEquals(64, d.hash.length)
        assertTrue(d.complete)
        assertTrue(d.stable)
    }

    @Test
    fun `a missing subtree yields an incomplete digest rather than an empty one`() {
        val d = store.dirDigest("no-such-dir")
        assertFalse(d.complete)
        assertFalse(d.stable)
    }
}
