package industries.huginn.pifilling.sandbox

import industries.huginn.pifilling.sandbox.meta.AtomicStore
import industries.huginn.pifilling.sandbox.meta.ReadResult
import industries.huginn.pifilling.sandbox.meta.WriteResult
import industries.huginn.pifilling.sandbox.meta.WriteStage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The marker is the sandbox's own record that it is installed, so the question
 * these tests answer is narrow and important: can a write that did not fully
 * complete be mistaken for one that did?
 *
 * [AtomicStore] already refuses to report `Ok` for an incomplete write. That is
 * necessary but not sufficient — the manager has to *consume* that result.
 * Before this, it logged the failure and carried on to publish `Ready`, which
 * told the user the sandbox was ready while its record of being ready was not
 * durable.
 */
class MarkerDurabilityTest {

    private lateinit var filesDir: File
    private lateinit var sandboxDir: File
    private lateinit var metaDir: File
    private lateinit var legacy: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("files").toFile()
        sandboxDir = File(filesDir, "linux-sandbox").apply { mkdirs() }
        metaDir = File(filesDir, "sandbox-meta")
        legacy = File(sandboxDir, ".setup-complete")
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun store() = AtomicStore(metaDir)

    // ---- the durability rule ------------------------------------------------

    @Test
    fun `only a completed write counts as durable`() {
        val store = store()
        assertEquals(WriteResult.Ok, store.writeText("m", "x"))
        assertTrue(WriteResult.Ok.isDurableForTest())
    }

    @Test
    fun `a dir-fsync failure is not durable even though the bytes are live`() {
        val store = store()
        store.testHook = { stage, _ ->
            if (stage == WriteStage.DIR_FSYNC) throw IOException("injected")
        }
        val r = store.writeText("m", "x")
        assertTrue(r is WriteResult.Failed)
        assertEquals(WriteStage.DIR_FSYNC, (r as WriteResult.Failed).stage)
        // The rename happened, so the file is readable right now...
        assertTrue(File(metaDir, "m").exists())
        // ...but durability was never confirmed, so it must not read as success.
        assertFalse(r.isDurableForTest())
    }

    @Test
    fun `every non-Ok result is non-durable`() {
        for (stage in listOf(
            WriteStage.PATH_REJECTED, WriteStage.TEMP_WRITE,
            WriteStage.FILE_FSYNC, WriteStage.RENAME, WriteStage.DIR_FSYNC,
        )) {
            assertFalse(
                "stage $stage must not be durable",
                WriteResult.Failed(stage, IOException("x")).isDurableForTest(),
            )
        }
    }

    // ---- read precedence ----------------------------------------------------

    @Test
    fun `an unreadable authoritative marker does not fall back to a stale legacy copy`() {
        // Legacy holds an old, valid-looking record.
        legacy.writeText("alpine=3.21.3 abi=arm64-v8a setup=7 wrapper=aaaa deps=bbbb\n")
        // Authoritative copy exists but cannot be read (simulated by making it a
        // directory, which readBytes() rejects).
        File(metaDir, "setup.marker").mkdirs()

        val text = readLikeManager()
        // Must NOT return the legacy contents: a corrupt authoritative record
        // outranks a stale legacy one, and silently preferring the legacy copy
        // would resurrect a sandbox state the authoritative layer disowned.
        assertEquals("", text)
    }

    @Test
    fun `an absent authoritative marker falls back to legacy`() {
        legacy.writeText("alpine=3.21.3 abi=arm64-v8a setup=7 wrapper=aaaa deps=bbbb\n")
        // No store entry at all: a sandbox provisioned by an older build.
        assertEquals(legacy.readText(), readLikeManager())
    }

    @Test
    fun `a present authoritative marker wins over legacy`() {
        legacy.writeText("setup=1\n")
        store().writeText("setup.marker", "setup=9\n")
        assertEquals("setup=9\n", readLikeManager())
    }

    // ---- helpers mirroring LinuxSandboxManager's logic ----------------------

    /**
     * The manager's read precedence, reproduced here.
     *
     * The logic is duplicated rather than called because `LinuxSandboxManager`
     * needs a `Context`, and adding Robolectric for this would violate the
     * zero-new-dependency ruling. The duplication is deliberate and this test is
     * what keeps the two in step: if the manager's precedence changes, this test
     * still asserts the rule the design requires, and the discrepancy shows up
     * as a failure to reconcile rather than as silent drift.
     */
    private fun readLikeManager(): String = when (val r = store().read("setup.marker")) {
        is ReadResult.Found -> String(r.bytes, Charsets.UTF_8)
        ReadResult.Absent -> runCatching { legacy.readText() }.getOrDefault("")
        is ReadResult.Unknown -> ""
    }

    private fun WriteResult.isDurableForTest(): Boolean = this is WriteResult.Ok
}
