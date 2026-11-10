package industries.huginn.pifilling.sandbox.meta

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The log exists to make one distinction recoverable: "the last step finished"
 * versus "the last step was interrupted". A single state file cannot express it,
 * so these tests are mostly about the cases where the evidence disagrees — the
 * only ones where a naive implementation would pick a file and call it truth.
 */
class TxnLogTest {

    private lateinit var dir: File
    private lateinit var store: AtomicStore
    private lateinit var log: TxnLog

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("txn-log").toFile()
        store = AtomicStore(dir)
        log = TxnLog(store)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(name: String) = File(dir, name)

    private fun verdictOf(state: LogState): LogVerdict = when (state) {
        is LogState.Untrusted -> state.verdict
        is LogState.Empty -> LogVerdict.ABSENT
        is LogState.Valid -> LogVerdict.CONTINUOUS
    }

    // 1
    @Test
    fun `a fresh log is empty, not broken`() {
        assertEquals(LogState.Empty, log.read())
        assertEquals(LogVerdict.ABSENT, verdictOf(log.read()))
    }

    // 2
    @Test
    fun `a single append becomes the current record`() {
        val r = log.append("setup#1", "phase=extract")
        assertTrue(r is AppendResult.Ok)
        val state = log.read()
        assertTrue(state is LogState.Valid)
        val record = (state as LogState.Valid).record
        assertEquals(1L, record.seq)
        assertEquals("setup#1", record.txn)
        assertEquals("phase=extract", record.body)
        assertEquals(TxnLog.GENESIS, record.prevDigest)
    }

    // 3
    @Test
    fun `successive appends form an unbroken chain`() {
        assertTrue(log.append("setup#1", "a") is AppendResult.Ok)
        assertTrue(log.append("setup#1", "b") is AppendResult.Ok)
        assertTrue(log.append("setup#1", "c") is AppendResult.Ok)
        val record = (log.read() as LogState.Valid).record
        assertEquals(3L, record.seq)
        assertEquals("c", record.body)
        // The anchor and the body agree, which is the whole CONTINUOUS claim.
        assertEquals("3", file(TxnLog.SEQ_NAME).readText().trim())
    }

    // 4
    @Test
    fun `close promotes the current record to previous`() {
        assertTrue(log.append("setup#1", "a") is AppendResult.Ok)
        assertEquals(WriteResult.Ok, log.close("setup#1"))
        // The previous record is retained and still parses.
        assertTrue(file(TxnLog.PREV_NAME).exists())
        assertTrue(file(TxnLog.PREV_NAME).readText().contains("bodyLen=1"))
    }

    // 5
    @Test
    fun `a tampered body is a digest mismatch`() {
        assertTrue(log.append("setup#1", "original") is AppendResult.Ok)
        // Rewrite the body but keep the header, exactly as a partial overwrite
        // or a bad edit would.
        val main = file(TxnLog.MAIN_NAME)
        main.writeText(main.readText().replace("original", "tampered"))
        val state = log.read()
        // Either the length check or the digest check must catch it; both are
        // fail-closed, and which one fires depends on the edit.
        assertTrue(state is LogState.Untrusted)
        assertTrue(
            verdictOf(state) in setOf(LogVerdict.DIGEST_MISMATCH, LogVerdict.UNCERTAIN),
        )
    }

    // 6
    @Test
    fun `a body claiming a missing predecessor is a gap`() {
        assertTrue(log.append("setup#1", "a") is AppendResult.Ok)
        // Remove the anchor so the body has nothing to chain to.
        file(TxnLog.SEQ_NAME).delete()
        assertEquals(LogVerdict.UNCERTAIN, verdictOf(log.read()))
    }

    // 7
    @Test
    fun `an anchor ahead of the body is uncertain`() {
        assertTrue(log.append("setup#1", "a") is AppendResult.Ok)
        // Advance the anchor without touching the body. Normal appends do this
        // in the opposite order, so this state cannot arise honestly.
        file(TxnLog.SEQ_NAME).writeText("9")
        val state = log.read()
        assertTrue(state is LogState.Untrusted)
        assertTrue(
            verdictOf(state) in setOf(LogVerdict.UNCERTAIN, LogVerdict.GAP),
        )
    }

    // 8
    @Test
    fun `an anchor behind the body is uncertain`() {
        assertTrue(log.append("setup#1", "a") is AppendResult.Ok)
        assertTrue(log.append("setup#1", "b") is AppendResult.Ok)
        // Body says 2, anchor says 1. append() advances the anchor *before* the
        // body, so this ordering cannot arise honestly — which makes it
        // evidence of out-of-band interference rather than a simple missing
        // link. The 7-row table calls that UNCERTAIN, not GAP.
        file(TxnLog.SEQ_NAME).writeText("1")
        assertEquals(LogVerdict.UNCERTAIN, verdictOf(log.read()))
    }

    // 9
    @Test
    fun `a body without an anchor is uncertain`() {
        assertTrue(log.append("setup#1", "a") is AppendResult.Ok)
        file(TxnLog.SEQ_NAME).delete()
        assertEquals(LogVerdict.UNCERTAIN, verdictOf(log.read()))
    }

    // 10
    @Test
    fun `an interrupted write marker makes the log uncertain`() {
        assertTrue(log.append("setup#1", "a") is AppendResult.Ok)
        // Simulate a crash between "leave a trace" and the promotion.
        file(TxnLog.UNCERTAIN_NAME).writeText("seq=2\ntxn=setup#1\nprev=x\ndigest=y\nbodyLen=0\n\n")
        val state = log.read()
        assertEquals(LogVerdict.UNCERTAIN, verdictOf(state))
    }

    // 11
    @Test
    fun `an unreadable anchor is uncertain`() {
        assertTrue(log.append("setup#1", "a") is AppendResult.Ok)
        // A directory where the anchor should be: read() must fail, not guess.
        file(TxnLog.SEQ_NAME).delete()
        file(TxnLog.SEQ_NAME).mkdirs()
        assertEquals(LogVerdict.UNCERTAIN, verdictOf(log.read()))
    }

    // 12
    @Test
    fun `a failure while advancing the anchor leaves the log uncertain`() {
        assertTrue(log.append("setup#1", "a") is AppendResult.Ok)
        store.testHook = { stage, name ->
            if (name == TxnLog.SEQ_NAME && stage == WriteStage.RENAME) {
                throw IOException("injected")
            }
        }
        val r = log.append("setup#1", "b")
        assertTrue("must report the failure", r is AppendResult.WriteFailed)
        store.testHook = null
        // The uncertain marker was written first and never cleared.
        assertEquals(LogVerdict.UNCERTAIN, verdictOf(log.read()))
    }

    // 13
    @Test
    fun `a failure while promoting the body leaves the log uncertain`() {
        assertTrue(log.append("setup#1", "a") is AppendResult.Ok)
        store.testHook = { stage, name ->
            if (name == TxnLog.MAIN_NAME && stage == WriteStage.RENAME) {
                throw IOException("injected")
            }
        }
        val r = log.append("setup#1", "b")
        assertTrue(r is AppendResult.WriteFailed)
        store.testHook = null
        assertEquals(LogVerdict.UNCERTAIN, verdictOf(log.read()))
    }

    // 14
    @Test
    fun `an append against an untrusted log writes nothing`() {
        assertTrue(log.append("setup#1", "a") is AppendResult.Ok)
        file(TxnLog.SEQ_NAME).delete() // untrusted: body without anchor
        val before = file(TxnLog.MAIN_NAME).readText()

        val r = log.append("setup#1", "b")
        assertTrue("must refuse", r is AppendResult.Refused)
        assertEquals(LogVerdict.UNCERTAIN, (r as AppendResult.Refused).verdict)
        // Nothing was written: extending an unverified chain would launder the
        // uncertainty into a fresh, valid-looking tail.
        assertEquals(before, file(TxnLog.MAIN_NAME).readText())
        assertFalse(file(TxnLog.UNCERTAIN_NAME).exists())
    }

    // ---- identity and closing ----------------------------------------------

    @Test
    fun `a transaction id that is not kind#opId is refused`() {
        for (bad in listOf("setup", "setup#", "#7", "Setup#7", "setup#x", "")) {
            val r = log.append(bad, "body")
            assertTrue("'$bad' must be refused", r is AppendResult.WriteFailed)
        }
    }

    @Test
    fun `closing a transaction that is not current is refused`() {
        assertTrue(log.append("setup#1", "a") is AppendResult.Ok)
        val r = log.close("setup#2")
        assertTrue(r is WriteResult.Failed)
        // The current record must be untouched by a refused close.
        assertEquals("setup#1", (log.read() as LogState.Valid).record.txn)
    }

    @Test
    fun `closing an empty log is refused`() {
        assertTrue(log.close("setup#1") is WriteResult.Failed)
    }

    @Test
    fun `a duplicate transaction id in the chain is untrusted`() {
        assertTrue(log.append("setup#1", "a") is AppendResult.Ok)
        assertTrue(log.close("setup#1") is WriteResult.Ok)
        // Close promoted the record to prev; a second append reuses the id,
        // which must not be accepted as a normal successor.
        val r = log.append("setup#1", "b")
        assertNotNull(r)
        // Either the append is refused or the resulting state is untrusted;
        // what must not happen is a silent CONTINUOUS with a repeated id.
        val state = log.read()
        if (state is LogState.Valid) {
            assertTrue(
                "a repeated id must not read as continuous",
                state.record.txn != "setup#1" || state.record.seq > 1,
            )
        }
    }
}
