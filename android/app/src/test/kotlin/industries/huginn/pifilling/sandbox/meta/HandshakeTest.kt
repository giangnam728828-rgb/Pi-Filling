package industries.huginn.pifilling.sandbox.meta

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A registration that reports Ok only means the bytes were written. It does not
 * prove the row that landed belongs to this spawn — a recycled pid or a row from
 * another spawn would pass a pid-only check.
 *
 * The handshake therefore compares all three fields, and a row with a blank
 * `spawnId` never matches: an unowned row cannot prove ownership.
 */
class HandshakeTest {

    private lateinit var dir: File
    private lateinit var proc: File
    private lateinit var store: AtomicStore
    private lateinit var registry: ProcessRegistry

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("handshake").toFile()
        proc = Files.createTempDirectory("proc").toFile()
        store = AtomicStore(dir)
        registry = ProcessRegistry(store, proc)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        proc.deleteRecursively()
    }

    private fun fakeProcess(pid: Int, start: Long) {
        val d = File(proc, pid.toString()).apply { mkdirs() }
        val tail = buildString {
            append("S ")
            append(1).append(' ')
            repeat(17) { append("0 ") }
            append(start)
            append('\n')
        }
        File(d, "stat").writeText("$pid (test) $tail")
    }

    private fun treeOf(pid: Int, start: Long, complete: Boolean = true) =
        ProcTree(pid, listOf(ProcIdentity(pid, start)), complete)

    /** The three-field match the manager performs. */
    private fun matches(reservationId: String, tree: ProcTree): Boolean {
        val state = registry.state()
        if (state !is ProcessRegistry.RegistryState.Present) return false
        val root = tree.members.firstOrNull() ?: return false
        val entry = state.record.entries.firstOrNull { it.identity == root } ?: return false
        return entry.spawnId == reservationId &&
            entry.identity.startTimeTicks == root.startTimeTicks &&
            entry.identity.pid == root.pid
    }

    // ---- the happy path -----------------------------------------------------

    @Test
    fun `all three fields matching is a successful handshake`() {
        fakeProcess(pid = 42, start = 100L)
        val tree = treeOf(42, 100L)
        assertTrue(registry.record(tree, "spawn#a") is WriteResult.Ok)
        assertTrue(matches("spawn#a", tree))
    }

    // ---- each field independently ------------------------------------------

    @Test
    fun `a different spawn id fails the handshake`() {
        fakeProcess(pid = 42, start = 100L)
        val tree = treeOf(42, 100L)
        registry.record(tree, "spawn#a")
        assertFalse("a row written by spawn#a must not match spawn#b", matches("spawn#b", tree))
    }

    @Test
    fun `a legacy row with a blank spawn id never matches`() {
        // Written before the field existed: it cannot prove it belongs to
        // anyone, so it must not be accepted as this spawn's row.
        store.writeText(
            ProcessRegistry.RECYCLE_NAME,
            "{\"emptyRounds\":0,\"entries\":[" +
                "{\"pid\":42,\"start\":100,\"treeComplete\":true}]}",
        )
        val tree = treeOf(42, 100L)
        assertFalse(matches("spawn#a", tree))
        assertEquals("", registry.current()!!.entries.single().spawnId)
    }

    @Test
    fun `a start time mismatch fails the handshake`() {
        fakeProcess(pid = 42, start = 999L)
        registry.record(treeOf(42, 999L), "spawn#a")
        // The reservation claims 100 but the registry holds 999.
        assertFalse(matches("spawn#a", treeOf(42, 100L)))
    }

    @Test
    fun `a missing row fails the handshake`() {
        registry.record(treeOf(42, 100L), "spawn#a")
        assertFalse("a different pid must not match", matches("spawn#a", treeOf(43, 100L)))
    }

    @Test
    fun `an empty registry fails the handshake`() {
        assertFalse(matches("spawn#a", treeOf(42, 100L)))
    }

    @Test
    fun `an unreadable registry fails the handshake`() {
        File(dir, ProcessRegistry.RECYCLE_NAME).mkdirs()
        assertFalse("unreadable must not be treated as a match", matches("spawn#a", treeOf(42, 100L)))
    }

    // ---- tree exit verification --------------------------------------------

    @Test
    fun `a fully exited tree is safe to clear`() {
        // No /proc entry for the pid: it is gone.
        val tree = treeOf(42, 100L)
        val outcome = registry.verifyTreeExited(tree, killRequested = true)
        assertTrue(outcome.rootExited)
        assertTrue(outcome.knownTreeExited)
        assertTrue("complete tree + all gone = safe", outcome.safeToClear)
    }

    @Test
    fun `a still-running root is not safe to clear`() {
        fakeProcess(pid = 42, start = 100L)
        val outcome = registry.verifyTreeExited(treeOf(42, 100L), killRequested = true)
        assertFalse(outcome.rootExited)
        assertFalse(outcome.safeToClear)
    }

    @Test
    fun `an incomplete tree is never safe to clear`() {
        // Every member is gone, but the tree was truncated: a child we never saw
        // could still be running, and there is no list to check it against.
        val outcome = registry.verifyTreeExited(
            treeOf(42, 100L, complete = false),
            killRequested = true,
        )
        assertTrue("known members are gone", outcome.knownTreeExited)
        assertFalse("but the tree was incomplete", outcome.treeWasComplete)
        assertFalse("so it must not be cleared", outcome.safeToClear)
    }

    @Test
    fun `a surviving child blocks even when the root is gone`() {
        // Root has no /proc entry; the child does.
        fakeProcess(pid = 77, start = 200L)
        val tree = ProcTree(
            42,
            listOf(ProcIdentity(42, 100L), ProcIdentity(77, 200L)),
            treeComplete = true,
        )
        val outcome = registry.verifyTreeExited(tree, killRequested = true)
        assertTrue("the root is gone", outcome.rootExited)
        assertFalse("but a child survives", outcome.knownTreeExited)
        assertFalse(outcome.safeToClear)
    }

    @Test
    fun `no kill request means nothing may be cleared`() {
        val outcome = registry.verifyTreeExited(treeOf(42, 100L), killRequested = false)
        assertFalse(outcome.safeToClear)
    }

    @Test
    fun `an unverifiable identity is not treated as exited`() {
        // -1 start time: cannot tell whether the pid is ours.
        val tree = ProcTree(42, listOf(ProcIdentity(42, -1L)), treeComplete = true)
        val outcome = registry.verifyTreeExited(tree, killRequested = true)
        assertFalse("unverifiable must not read as gone", outcome.rootExited)
        assertFalse(outcome.safeToClear)
    }

    @Test
    fun `a reused pid counts as exited`() {
        // The pid exists but belongs to a different process; ours is gone.
        fakeProcess(pid = 42, start = 999L)
        val outcome = registry.verifyTreeExited(treeOf(42, 100L), killRequested = true)
        assertTrue("a recycled pid means ours exited", outcome.rootExited)
        assertTrue(outcome.safeToClear)
    }
}
