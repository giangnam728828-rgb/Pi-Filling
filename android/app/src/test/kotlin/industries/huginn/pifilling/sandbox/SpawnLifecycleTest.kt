package industries.huginn.pifilling.sandbox

import industries.huginn.pifilling.sandbox.meta.ProcTree
import industries.huginn.pifilling.sandbox.meta.SpawnReservation
import industries.huginn.pifilling.sandbox.meta.WriteResult
import industries.huginn.pifilling.sandbox.meta.WriteStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * The spawn lifecycle is reserve -> start -> register -> release, and each edge
 * has a different safe behaviour when it breaks.
 *
 * The dangerous confusion is calling `release()` on a path where registration
 * did not succeed: the reservation is the only durable record that a process may
 * exist, so clearing it early turns an unaccounted-for process into an invisible
 * one, and the next wipe reads an empty registry and reports AllDead.
 *
 * These tests drive the real `start()` against a fake process, so the ordering
 * is what is under test — not a mock's idea of it.
 */
/**
 * Spawn-lifecycle ordering, written against the B4 4c implementation.
 *
 * **Disabled on purpose.** The runtime interception these tests assert was
 * rolled back after it made every session fail on device: the reservation and
 * prune machinery was correct in isolation and, combined with three call sites
 * that bypassed it, left registry rows that refused every later spawn.
 *
 * Kept rather than deleted because the ordering rules are still the intended
 * design — reserve -> start -> register -> (exit observer) prune + release — and
 * rewriting them from scratch would lose the reasoning behind each edge. B4's
 * redesign should re-enable this class and make it pass.
 */
@org.junit.Ignore("B4 runtime interception rolled back; see commit message")
class SpawnLifecycleTest {

    private val events = mutableListOf<String>()

    /** Minimal Process so `start()` can run without proot or a real fork. */
    private class FakeProcess(private val exitDelayMs: Long = 0) : Process() {
        override fun getOutputStream(): java.io.OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): java.io.InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): java.io.InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() { }
        override fun isAlive(): Boolean = false
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
    }

    private class RecordingRegistrar(
        private val events: MutableList<String>,
        private val reserveResult: (() -> ProotExecutor.ReservationResult)? = null,
        private val registerResult: (() -> WriteResult)? = null,
        private val releaseResult: (() -> WriteResult)? = null,
    ) : ProotExecutor.SpawnRegistrar {

        var reserveCalls = 0
        var registerCalls = 0
        var releaseCalls = 0

        override suspend fun reserve(purpose: String): ProotExecutor.ReservationResult {
            reserveCalls++
            events += "reserve"
            return reserveResult?.invoke() ?: ProotExecutor.ReservationResult.Ok(
                SpawnReservation(
                    id = "spawn#test",
                    format = SpawnReservation.CURRENT_FORMAT,
                    purpose = purpose,
                    seq = 1,
                    startedAt = 0,
                    pid = -1,
                    startTimeTicks = -1,
                ),
            )
        }

        override suspend fun register(
            reservation: SpawnReservation,
            tree: ProcTree,
        ): WriteResult {
            registerCalls++
            events += "register"
            return registerResult?.invoke() ?: WriteResult.Ok
        }

        override suspend fun release(reservation: SpawnReservation): WriteResult {
            releaseCalls++
            events += "release"
            return releaseResult?.invoke() ?: WriteResult.Ok
        }

        override suspend fun prune(
            members: List<industries.huginn.pifilling.sandbox.meta.ProcIdentity>,
            spawnId: String,
        ): WriteResult {
            events += "prune"
            return WriteResult.Ok
        }
    }

    private fun executor(registrar: ProotExecutor.SpawnRegistrar?): ProotExecutor {
        val ex = ProotExecutor(
            prootPath = "/nonexistent/proot",
            libDir = "/nonexistent",
            rootfsPath = "/nonexistent/rootfs",
            homePath = "/nonexistent/home",
            tmpPath = "/nonexistent/tmp",
            registrar = registrar,
        )
        var started = 0
        ex.launcher = { _, _ ->
            started++
            events += "start"
            FakeProcess()
        }
        return ex
    }

    // A1
    @Test
    fun `a failed reservation prevents the process from starting`() {
        var starts = 0
        val registrar = RecordingRegistrar(
            events,
            reserveResult = {
                ProotExecutor.ReservationResult.Failed(
                    WriteStage.DIR_FSYNC, IOException("injected"),
                )
            },
        )
        val ex = ProotExecutor(
            "/nonexistent/proot", "/nonexistent", "/nonexistent/rootfs",
            "/nonexistent/home", "/nonexistent/tmp", registrar,
        )
        ex.launcher = { _, _ -> starts++; FakeProcess() }

        val threw = runCatching { ex.start("echo hi") }.isFailure
        assertTrue("must refuse to start", threw)
        assertEquals("Process.start() must never run", 0, starts)
        assertEquals(1, registrar.reserveCalls)
        assertEquals(0, registrar.registerCalls)
        assertEquals(0, registrar.releaseCalls)
    }

    // A2
    @Test
    fun `a blocked reservation prevents the process from starting`() {
        var starts = 0
        val registrar = RecordingRegistrar(
            events,
            reserveResult = { ProotExecutor.ReservationResult.Blocked("wipe active") },
        )
        val ex = ProotExecutor(
            "/nonexistent/proot", "/nonexistent", "/nonexistent/rootfs",
            "/nonexistent/home", "/nonexistent/tmp", registrar,
        )
        ex.launcher = { _, _ -> starts++; FakeProcess() }

        assertTrue(runCatching { ex.start("echo hi") }.isFailure)
        assertEquals(0, starts)
        assertEquals(0, registrar.releaseCalls)
    }

    // A4
    @Test
    fun `a failed registration must not release the reservation`() {
        val registrar = RecordingRegistrar(
            events,
            registerResult = { WriteResult.Failed(WriteStage.TEMP_WRITE, IOException("injected")) },
        )
        val ex = executor(registrar)

        val threw = runCatching { ex.start("echo hi") }.isFailure
        assertTrue("registration failure must fail the launch", threw)
        assertEquals("reserve -> start -> register, no release", listOf("reserve", "start", "register"), events)
        assertEquals(0, registrar.releaseCalls)
    }

    // A5
    @Test
    fun `a registering exception must not release the reservation`() {
        val registrar = RecordingRegistrar(
            events,
            registerResult = { throw IllegalStateException("boom") },
        )
        val ex = executor(registrar)

        assertTrue(runCatching { ex.start("echo hi") }.isFailure)
        assertEquals(0, registrar.releaseCalls)
    }

    // A6
    @Test
    fun `a failed release leaves the reservation outstanding but still launches`() {
        val registrar = RecordingRegistrar(
            events,
            releaseResult = { WriteResult.Failed(WriteStage.RENAME, IOException("injected")) },
        )
        val ex = executor(registrar)

        // A release failure is not fatal: the registry write succeeded, so the
        // process IS accounted for. The leftover reservation is reconciled on
        // the next launch rather than blocking this one forever. What must not
        // happen is treating the failed release as a failed launch.
        val process = ex.start("echo hi")
        assertNotNull("the process must still be returned", process)
        // start() stops after register. The release belongs to whoever observes
        // the process exit — execute() or teardown() — because only they know it
        // has finished.
        assertEquals(listOf("reserve", "start", "register"), events)
        assertEquals(0, registrar.releaseCalls)
    }

    // A7
    @Test
    fun `a throwing release does not fail the launch`() {
        val registrar = RecordingRegistrar(
            events,
            releaseResult = { throw IOException("release exploded") },
        )
        val ex = executor(registrar)

        val process = runCatching { ex.start("echo hi") }
        assertTrue("the launch must still succeed", process.isSuccess)
        assertEquals("release is deferred to the exit observer", 0, registrar.releaseCalls)
    }

    // A8
    @Test
    fun `the happy path runs the four steps in order`() {
        val registrar = RecordingRegistrar(events)
        val ex = executor(registrar)
        ex.start("echo hi")
        assertEquals(listOf("reserve", "start", "register"), events)
        assertEquals(1, registrar.reserveCalls)
        assertEquals(1, registrar.registerCalls)
        // Released by the exit observer, not by start().
        assertEquals(0, registrar.releaseCalls)
    }

    @Test
    fun `without a registrar the process still starts and nothing is reserved`() {
        val ex = executor(null)
        ex.start("echo hi")
        assertEquals(listOf("start"), events)
    }

}
