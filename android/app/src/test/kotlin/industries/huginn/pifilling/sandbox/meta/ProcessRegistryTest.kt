package industries.huginn.pifilling.sandbox.meta

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The registry exists to answer one question conservatively: may this directory
 * be deleted? Every test here is about the cases where the honest answer is "I
 * cannot tell" — because deleting a tree out from under a live process is not
 * recoverable, while blocking always is.
 *
 * `/proc` is injected as a temporary directory so the tests need no real
 * processes and stay deterministic.
 */
class ProcessRegistryTest {

    private lateinit var dir: File
    private lateinit var proc: File
    private lateinit var store: AtomicStore
    private lateinit var registry: ProcessRegistry

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("registry").toFile()
        proc = Files.createTempDirectory("proc").toFile()
        store = AtomicStore(dir)
        registry = ProcessRegistry(store, proc)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        proc.deleteRecursively()
    }

    /** Materialise a fake `/proc/<pid>/stat` with the given ppid and start time. */
    private fun fakeProcess(pid: Int, ppid: Int = 1, start: Long = 1000L) {
        val d = File(proc, pid.toString()).apply { mkdirs() }
        // Field layout matches the real thing: pid (comm) state ppid ... starttime.
        // comm is parenthesised and everything after the last ')' is numeric.
        // After the last ')' the real layout is 52 space-separated fields.
        // index 0 = state (field 3), index 1 = ppid (field 4),
        // index 19 = starttime (field 22). Pad to index 19, then append start.
        val tail = buildString {
            append("S ")            // index 0  state
            append(ppid).append(' ') // index 1  ppid
            repeat(17) { append("0 ") } // indices 2..18
            append(start)            // index 19 starttime
            append('\n')
        }
        File(d, "stat").writeText("$pid (test proc) $tail")
    }

    private fun identity(pid: Int, start: Long) = ProcIdentity(pid, start)

    private fun record(
        vararg ids: ProcIdentity,
        treeComplete: Boolean = true,
        spawnId: String = "spawn#1",
    ) {
        registry.record(ProcTree(ids.first().pid, ids.toList(), treeComplete), spawnId)
    }

    // 1
    @Test
    fun `an empty registry scans as Empty`() {
        assertEquals(ScanOutcome.Empty, registry.scan())
        assertNull(registry.current())
    }

    // 2
    @Test
    fun `a recorded live process scans as AllAlive`() {
        fakeProcess(pid = 4242, start = 777L)
        record(identity(4242, 777L))
        val outcome = registry.scan()
        assertTrue("expected AllAlive, got $outcome", outcome is ScanOutcome.AllAlive)
        assertEquals(1, (outcome as ScanOutcome.AllAlive).alive.size)
    }

    // 3
    @Test
    fun `a process that left proc scans as AllDead`() {
        // Recorded but never materialised in the fake /proc: it is gone.
        record(identity(4242, 777L))
        assertTrue(registry.scan() is ScanOutcome.AllDead)
    }

    // 4
    @Test
    fun `a mix of live and gone scans as Partial`() {
        fakeProcess(pid = 100, start = 1L)
        record(identity(100, 1L), identity(200, 2L))
        val outcome = registry.scan()
        assertTrue("expected Partial, got $outcome", outcome is ScanOutcome.Partial)
        val partial = outcome as ScanOutcome.Partial
        assertEquals(1, partial.alive.size)
        assertEquals(1, partial.dead.size)
        assertEquals(100, partial.alive.single().pid)
        assertEquals(200, partial.dead.single().pid)
    }

    // 5
    @Test
    fun `prune removes only the entries it was given`() {
        fakeProcess(pid = 100, start = 1L)
        record(identity(100, 1L), identity(200, 2L), identity(300, 3L))
        registry.prune(listOf(identity(200, 2L)))

        val left = registry.current()!!.entries.map { it.identity.pid }.toSet()
        assertEquals(setOf(100, 300), left)
    }

    // 6
    @Test
    fun `an entry without a start time is unverifiable`() {
        fakeProcess(pid = 100, start = 1L)
        record(identity(100, -1L))
        val outcome = registry.scan()
        assertTrue("expected Unverifiable, got $outcome", outcome is ScanOutcome.Unverifiable)
    }

    // 7
    @Test
    fun `an unreadable proc root is unverifiable`() {
        record(identity(100, 1L))
        proc.deleteRecursively() // /proc vanished
        assertTrue(registry.scan() is ScanOutcome.Unverifiable)
    }

    // 8
    @Test
    fun `a corrupt registry is unverifiable rather than empty`() {
        // Mentions a pid but yields no parsable entry: damaged, not empty.
        store.writeText(ProcessRegistry.RECYCLE_NAME, "{\"pid\": garbage")
        val outcome = registry.scan()
        assertTrue("expected Unverifiable, got $outcome", outcome is ScanOutcome.Unverifiable)
    }

    // 9
    @Test
    fun `a registry listing a pid twice is unverifiable`() {
        val body = "{\"emptyRounds\":0,\"entries\":[" +
            "{\"pid\":100,\"start\":1,\"treeComplete\":true}," +
            "{\"pid\":100,\"start\":2,\"treeComplete\":true}]}"
        store.writeText(ProcessRegistry.RECYCLE_NAME, body)
        assertTrue(registry.scan() is ScanOutcome.Unverifiable)
    }

    // 10
    @Test
    fun `three dead rounds are required before clearing`() {
        record(identity(4242, 777L))
        registry.advanceRounds(allDead = true)
        registry.advanceRounds(allDead = true)
        assertEquals(2, registry.current()!!.emptyRounds)
        registry.advanceRounds(allDead = true)
        assertEquals(
            ProcessRegistry.ROUNDS_TO_CLEAR,
            registry.current()!!.emptyRounds,
        )
    }

    // 11
    @Test
    fun `a round with something alive resets the counter`() {
        record(identity(4242, 777L))
        registry.advanceRounds(allDead = true)
        registry.advanceRounds(allDead = true)
        assertEquals(2, registry.current()!!.emptyRounds)
        registry.advanceRounds(allDead = false)
        assertEquals(0, registry.current()!!.emptyRounds)
    }

    // 12
    @Test
    fun `a failed persistence is reported to the caller`() {
        record(identity(4242, 777L))
        store.testHook = { _, name ->
            if (name == ProcessRegistry.RECYCLE_NAME) throw IOException("injected")
        }
        val r = registry.record(ProcTree(1, listOf(identity(1, 1L)), true), "spawn#1")
        assertTrue("must surface the failure", r is WriteResult.Failed)
    }

    // 13
    @Test
    fun `a recycled pid is neither alive nor dead`() {
        // Recorded start time 777, but the pid now belongs to start 999.
        fakeProcess(pid = 100, start = 999L)
        record(identity(100, 777L))
        val outcome = registry.scan()
        assertTrue("expected HasReused, got $outcome", outcome is ScanOutcome.HasReused)
        val reused = (outcome as ScanOutcome.HasReused).reused.single()
        assertEquals(999L, reused.actualStart)
        // It must not be reported as alive: it is not our process.
        assertFalse(outcome is ScanOutcome.AllAlive)
    }

    // 14
    @Test
    fun `parseStat survives a comm containing spaces and parentheses`() {
        // comm deliberately contains both a space and a closing paren.
        // After the last ')': index 0 = S, index 1 = ppid 99, then pad to
        // index 19 where starttime lives.
        val raw = "1234 (Web Content (x)) S 99 " + "0 ".repeat(17) + "5555\n"
        val parsed = ProcessRegistry.parseStat(raw)
        assertNotNull("a parenthesised comm must not break parsing", parsed)
        assertEquals(99, parsed!!.first)     // ppid
        assertEquals(5555L, parsed.second)   // starttime
    }

    @Test
    fun `parseStat rejects a line with too few fields`() {
        assertNull(ProcessRegistry.parseStat("1234 (x) S 1 2 3"))
        assertNull(ProcessRegistry.parseStat("no parens at all"))
        assertNull(ProcessRegistry.parseStat(""))
    }

    // 15
    @Test
    fun `an alive process from an incomplete tree still counts as alive`() {
        fakeProcess(pid = 100, start = 1L)
        record(identity(100, 1L), treeComplete = false)
        val outcome = registry.scan()
        // It IS running; the incompleteness only means we do not know what else
        // it spawned. That distinction gates deletion, not spawning.
        assertTrue("expected AllAlive, got $outcome", outcome is ScanOutcome.AllAlive)
        val alive = (outcome as ScanOutcome.AllAlive).alive.single()
        assertEquals(100, alive.pid)
    }

    // 16
    @Test
    fun `pruning dead entries cannot launder an untrustworthy registry`() {
        fakeProcess(pid = 100, start = 1L)
        // One live entry from an incomplete tree, one genuinely dead entry.
        record(identity(100, 1L), identity(200, 2L), treeComplete = false)
        registry.prune(listOf(identity(200, 2L)))

        // The incomplete flag must survive the prune: deleting the easy case
        // must not make the remaining record look trustworthy for deletion.
        val entry = registry.current()!!.entries.single()
        assertEquals(100, entry.identity.pid)
        assertFalse("treeComplete must stay false", entry.treeComplete)
        // Spawning is still allowed (the process is alive, and that is what the
        // gate asks); deleting is not.
        assertTrue(registry.scan() is ScanOutcome.AllAlive)
        assertFalse(
            registry.verifyTreeExited(
                ProcTree(100, listOf(identity(100, 1L)), treeComplete = false),
                killRequested = true,
            ).safeToClear,
        )
    }

    // ---- spawn identity (v11) ----------------------------------------------

    @Test
    fun `an empty spawn id is refused`() {
        val r = registry.record(ProcTree(1, listOf(identity(10, 1L)), true), "")
        assertTrue("blank spawn id must fail", r is WriteResult.Failed)
        assertNull("nothing may be recorded", registry.current())
    }

    @Test
    fun `an empty process tree is refused`() {
        // captureTree returns empty members when /proc could not be walked.
        // Recording that as Ok would claim success while writing nothing.
        val r = registry.record(ProcTree(-1, emptyList(), treeComplete = false), "spawn#1")
        assertTrue("empty members must fail", r is WriteResult.Failed)
    }

    @Test
    fun `the spawn id is persisted and read back`() {
        record(identity(10, 1L), spawnId = "spawn#42")
        val entry = registry.current()!!.entries.single()
        assertEquals("spawn#42", entry.spawnId)
    }

    @Test
    fun `a legacy row without a spawn id reads as blank`() {
        // Files written before spawnId existed must still parse, and must yield
        // "" so they can never be matched to a reservation.
        store.writeText(
            ProcessRegistry.RECYCLE_NAME,
            "{\"emptyRounds\":0,\"entries\":[{\"pid\":10,\"start\":1,\"treeComplete\":true}]}",
        )
        val entry = registry.current()!!.entries.single()
        assertEquals("", entry.spawnId)
        assertEquals(10, entry.identity.pid)
    }

    @Test
    fun `the same pid under two different spawn ids is refused`() {
        record(identity(10, 1L), spawnId = "spawn#1")
        val r = registry.record(ProcTree(1, listOf(identity(10, 1L)), true), "spawn#2")
        assertTrue("a second spawn claiming the same identity must fail", r is WriteResult.Failed)
        // The original row must be untouched.
        assertEquals("spawn#1", registry.current()!!.entries.single().spawnId)
    }

    @Test
    fun `an incomplete entry is never upgraded to complete`() {
        fakeProcess(pid = 10, start = 1L)
        record(identity(10, 1L), treeComplete = false, spawnId = "spawn#1")
        // A later, complete capture of the same process must not erase the
        // evidence that the earlier tree was incomplete.
        record(identity(10, 1L), treeComplete = true, spawnId = "spawn#1")
        assertFalse(
            "treeComplete must stay false",
            registry.current()!!.entries.single().treeComplete,
        )
        // The process is alive, so the spawn gate sees it; the deletion guard
        // still refuses because the tree was never complete.
        assertTrue(registry.scan() is ScanOutcome.AllAlive)
    }

    // ---- 4c-C: identity and exit safety ------------------------------------

    @Test
    fun `a registry row keeps its spawn id across a reload`() {
        // Round trip through the real encode/decode: a hand-built string would
        // not prove the persistence format actually carries the field.
        record(identity(10, 1L), spawnId = "spawn#alpha")
        val reloaded = ProcessRegistry(store, proc)
        assertEquals("spawn#alpha", reloaded.current()!!.entries.single().spawnId)
    }

    @Test
    fun `a row with a different spawn id cannot be mistaken for another spawn`() {
        fakeProcess(pid = 10, start = 1L)
        record(identity(10, 1L), spawnId = "spawn#alpha")
        val entry = registry.current()!!.entries.single()
        assertFalse(
            "a different spawn must not match",
            entry.spawnId == "spawn#beta",
        )
        assertTrue("the real one must match", entry.spawnId == "spawn#alpha")
    }

    @Test
    fun `an unreadable registry is not empty`() {
        // A directory where the file should be: read() fails.
        File(dir, ProcessRegistry.RECYCLE_NAME).mkdirs()
        val state = registry.state()
        assertTrue("expected Unreadable, got $state", state is ProcessRegistry.RegistryState.Unreadable)
        assertTrue("scan must block", registry.scan() is ScanOutcome.Unverifiable)
    }

    @Test
    fun `an empty registry is Empty not Unreadable`() {
        assertEquals(ProcessRegistry.RegistryState.Empty, registry.state())
    }

    @Test
    fun `a reused pid is never reported as alive`() {
        fakeProcess(pid = 10, start = 999L)
        record(identity(10, 1L))
        val outcome = registry.scan()
        assertTrue(outcome is ScanOutcome.HasReused)
        // Critically: it must not appear in the alive set, or a wipe would wait
        // forever on a process that is not ours.
        assertFalse(outcome is ScanOutcome.AllAlive)
    }

    @Test
    fun `an incomplete entry whose process exited scans as dead`() {
        // No /proc entry: the root is gone. The tree was never fully enumerated,
        // so deletion must still refuse — but that is `safeToClear`'s job, not
        // the scan's. Reporting Unverifiable here would block spawning forever.
        record(identity(10, 1L), treeComplete = false)
        assertTrue(registry.scan() is ScanOutcome.AllDead)
        // And the deletion guard still refuses.
        val outcome = registry.verifyTreeExited(
            ProcTree(10, listOf(identity(10, 1L)), treeComplete = false),
            killRequested = true,
        )
        assertFalse(outcome.safeToClear)
    }

    @Test
    fun `a complete capture cannot upgrade an incomplete entry`() {
        fakeProcess(pid = 10, start = 1L)
        record(identity(10, 1L), treeComplete = false, spawnId = "spawn#1")
        record(identity(10, 1L), treeComplete = true, spawnId = "spawn#1")
        assertFalse(
            "incomplete evidence must survive a later complete capture",
            registry.current()!!.entries.single().treeComplete,
        )
    }

    // ---- additional invariants ---------------------------------------------

    @Test
    fun `record merges rather than replacing`() {
        registry.record(ProcTree(1, listOf(identity(10, 1L)), true), "spawn#1")
        registry.record(ProcTree(2, listOf(identity(20, 2L)), true), "spawn#2")
        val pids = registry.current()!!.entries.map { it.identity.pid }.toSet()
        assertEquals(setOf(10, 20), pids)
    }

    @Test
    fun `a round-trip preserves start times and completeness`() {
        registry.record(ProcTree(1, listOf(identity(10, 12345L)), false), "spawn#1")
        val entry = registry.current()!!.entries.single()
        assertEquals(12345L, entry.identity.startTimeTicks)
        assertFalse(entry.treeComplete)
    }
}
