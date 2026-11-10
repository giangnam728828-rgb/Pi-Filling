package industries.huginn.pifilling.sandbox.meta

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency is asserted with explicit handshakes, never with sleeps: a sleep
 * that happens to be long enough on this machine proves nothing about the
 * ordering, and the failure it hides is exactly the one that matters — a process
 * starting after a wipe scanned.
 *
 * Each test parks one coroutine inside the critical section and then asserts what
 * the other one is allowed to do.
 */
class EnvironmentGateConcurrencyTest {

    private fun gate(
        reservation: () -> ReservationState = { ReservationState.None },
        scan: () -> ScanOutcome = { ScanOutcome.Empty },
    ) = EnvironmentGate(reservation, scan)

    // ---- B1: a wipe first, then a spawn ----

    @Test
    fun `a spawn arriving while a wipe holds the gate is refused`(): Unit = runBlocking(Dispatchers.Default) {
        val g = gate()
        val wipeInside = CompletableDeferred<Unit>()
        val releaseWipe = CompletableDeferred<Unit>()
        var spawnRan = false

        val wipe = async {
            g.withWipe {
                wipeInside.complete(Unit)
                releaseWipe.await()
            }
        }
        withTimeout(5_000) { wipeInside.await() }

        // The wipe holds both locks, so a spawn blocks rather than being
        // admitted. Bounded wait: if it were admitted, it would return quickly
        // and the assertion below would fail; if it blocks, the timeout proves
        // the gate is shut.
        val spawned = withTimeoutOrNull(300) { g.withSpawnPermit { spawnRan = true } }
        assertNull("spawn must not complete while a wipe runs", spawned)
        assertFalse("the block must not have run", spawnRan)

        releaseWipe.complete(Unit)
        withTimeout(5_000) { wipe.await() }
    }

    // ---- B2: a spawn first, then a wipe ----

    @Test
    fun `a wipe arriving while a spawn holds the gate waits for it`(): Unit = runBlocking(Dispatchers.Default) {
        val g = gate()
        val spawnInside = CompletableDeferred<Unit>()
        val releaseSpawn = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val spawn = async {
            g.withSpawnPermit {
                order += "spawn-enter"
                spawnInside.complete(Unit)
                releaseSpawn.await()
                order += "spawn-exit"
            }
        }
        withTimeout(5_000) { spawnInside.await() }

        // The wipe cannot take opLock while... actually it can: withSpawnPermit
        // only holds gateLock. It must therefore block on gateLock, not proceed.
        val wipe = async {
            g.withWipe {
                order += "wipe-enter"
            }
        }

        // Yield so the wipe coroutine reaches gateLock and blocks there. A
        // delay() would only prove the scheduler happened to run it.
        repeat(10) { kotlinx.coroutines.yield() }
        assertFalse(
            "the wipe must not enter before the spawn releases the gate",
            order.contains("wipe-enter"),
        )

        releaseSpawn.complete(Unit)
        withTimeout(5_000) { spawn.await() }
        withTimeout(5_000) { wipe.await() }

        assertEquals(listOf("spawn-enter", "spawn-exit", "wipe-enter"), order)
    }

    // ---- B4: an operation and a wipe cannot overlap ----

    @Test
    fun `a wipe is refused while an operation holds opLock`(): Unit = runBlocking(Dispatchers.Default) {
        val g = gate()
        val opInside = CompletableDeferred<Unit>()
        val releaseOp = CompletableDeferred<Unit>()

        val op = async {
            g.withOperation {
                opInside.complete(Unit)
                releaseOp.await()
            }
        }
        withTimeout(5_000) { opInside.await() }

        // tryLock must fail rather than queue: a wipe that waited and then began
        // would delete the environment the moment the operation finished, with
        // no further user intent. withWipe returns null immediately here rather
        // than blocking, which is why this needs no timeout.
        val wiped = g.withWipe { "should not run" }
        assertNull("wipe must not start while an operation runs", wiped)

        releaseOp.complete(Unit)
        withTimeout(5_000) { op.await() }
    }

    // ---- B7: an operation is refused while a wipe runs ----

    @Test
    fun `an operation is refused while a wipe holds the gate`(): Unit = runBlocking(Dispatchers.Default) {
        val g = gate()
        val wipeInside = CompletableDeferred<Unit>()
        val releaseWipe = CompletableDeferred<Unit>()

        val wipe = async {
            g.withWipe {
                wipeInside.complete(Unit)
                releaseWipe.await()
            }
        }
        withTimeout(5_000) { wipeInside.await() }

        // withOperation blocks on gateLock while the wipe holds it, so bound it.
        val ran = withTimeoutOrNull(300) { g.withOperation { "should not run" } }
        assertNull("operation must not complete during a wipe", ran)

        releaseWipe.complete(Unit)
        withTimeout(5_000) { wipe.await() }
    }

    // ---- the gate state itself ----

    @Test
    fun `a shut gate refuses the spawn permit`(): Unit = runBlocking(Dispatchers.Default) {
        val g = gate(reservation = { ReservationState.Unreadable("corrupt") })
        var ran = false
        assertNull(g.withSpawnPermit { ran = true })
        assertFalse(ran)
        assertNotNull("a shut gate must explain itself", g.blockReason())
    }

    @Test
    fun `an open gate admits the spawn permit`(): Unit = runBlocking(Dispatchers.Default) {
        val g = gate()
        assertEquals("ok", g.withSpawnPermit { "ok" })
    }

    @Test
    fun `the gate asks its sources each time rather than caching`(): Unit = runBlocking(Dispatchers.Default) {
        // A cached "open" would let a reservation written afterwards go
        // unnoticed, which is the same hole as an in-memory flag.
        val calls = AtomicInteger(0)
        val g = gate(
            reservation = {
                calls.incrementAndGet()
                ReservationState.None
            },
        )
        g.withSpawnPermit { }
        g.withSpawnPermit { }
        assertTrue("state must be re-read, not memoised", calls.get() >= 2)
    }

    // ---- recovery must be able to run while the gate is shut ----

    @Test
    fun `withRecovery runs even though the gate is shut`(): Unit = runBlocking(Dispatchers.Default) {
        // The state that shuts the gate is what recovery exists to repair.
        // Using withOperation here would mean the cleanup could never run.
        val g = gate(reservation = { ReservationState.Unreadable("corrupt") })
        assertNull("withOperation must be refused", g.withOperation { "no" })
        val recovered = withTimeout(5_000) { g.withRecovery { "repaired" } }
        assertEquals("withRecovery must still run", "repaired", recovered)
    }

    @Test
    fun `withRecovery still excludes a wipe`(): Unit = runBlocking(Dispatchers.Default) {
        val g = gate()
        val wipeInside = CompletableDeferred<Unit>()
        val releaseWipe = CompletableDeferred<Unit>()
        var recoveryRan = false

        val wipe = async {
            g.withWipe {
                wipeInside.complete(Unit)
                releaseWipe.await()
            }
        }
        withTimeout(5_000) { wipeInside.await() }

        val ran = withTimeoutOrNull(300) { g.withRecovery { recoveryRan = true } }
        assertNull("recovery must not run during a wipe", ran)
        assertFalse(recoveryRan)

        releaseWipe.complete(Unit)
        withTimeout(5_000) { wipe.await() }
    }

    // ---- lock hygiene ----

    @Test
    fun `a throwing block still releases the locks`(): Unit = runBlocking(Dispatchers.Default) {
        val g = gate()
        runCatching {
            g.withOperation { throw IllegalStateException("boom") }
        }
        // If either lock leaked, this would hang; the timeout makes that a
        // failure rather than a stuck test run.
        val after = withTimeout(5_000) { g.withOperation { "recovered" } }
        assertEquals("recovered", after)
    }

    @Test
    fun `a throwing wipe still clears the wipe flag`(): Unit = runBlocking(Dispatchers.Default) {
        val g = gate()
        runCatching { g.withWipe { throw IllegalStateException("boom") } }
        assertFalse("wipeActive must not stay set", g.isWipeActive())
        // And the gate must be usable again.
        assertEquals("ok", withTimeout(5_000) { g.withSpawnPermit { "ok" } })
    }

    @Test
    fun `a refused operation does not consume an op id`(): Unit = runBlocking(Dispatchers.Default) {
        val g = gate(reservation = { ReservationState.Unreadable("x") })
        val before = g.currentOpId()
        assertNull(g.withOperation { "nope" })
        assertEquals("a refused call must not advance the counter", before, g.currentOpId())
    }

    @Test
    fun `wipeBlockReason reports a busy operation`(): Unit = runBlocking(Dispatchers.Default) {
        val g = gate()
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val op = async {
            g.withOperation {
                inside.complete(Unit)
                release.await()
            }
        }
        withTimeout(5_000) { inside.await() }
        assertNotNull("a running operation must be reported", g.wipeBlockReason())
        release.complete(Unit)
        withTimeout(5_000) { op.await() }
        assertNull("an idle gate reports no reason", g.wipeBlockReason())
    }
}
