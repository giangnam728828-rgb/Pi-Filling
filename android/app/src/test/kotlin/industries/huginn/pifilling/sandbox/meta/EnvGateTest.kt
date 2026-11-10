package industries.huginn.pifilling.sandbox.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each branch of the gate is a fail-closed rule, and each is asserted
 * individually: a combined "gate is shut" assertion would pass even if three of
 * the four rules were missing.
 *
 * The rules exist because a wipe deletes the environment, and a process that the
 * registry does not know about can still be holding it. Anything short of
 * "confirmed nothing is running" must keep the gate shut.
 */
class EnvGateTest {

    private fun gate(
        wipeActive: Boolean = false,
        maintenanceBlocked: Boolean = false,
        reservation: ReservationState = ReservationState.None,
        scan: ScanOutcome = ScanOutcome.Empty,
    ) = evaluateGate(GateInputs(wipeActive, maintenanceBlocked, reservation, scan))

    private fun reservation(id: String = "spawn#1") = SpawnReservation(
        id = id,
        format = SpawnReservation.CURRENT_FORMAT,
        purpose = "test",
        seq = 1,
        startedAt = 0,
        pid = -1,
        startTimeTicks = -1,
    )

    @Test
    fun `the gate opens only when nothing is outstanding`() {
        assertEquals(GateVerdict.Open, gate())
    }

    // ---- B1: a wipe shuts the gate ----

    @Test
    fun `an active wipe shuts the gate`() {
        val v = gate(wipeActive = true)
        assertTrue("expected Shut, got $v", v is GateVerdict.Shut)
    }

    // ---- maintenance block ----

    @Test
    fun `a maintenance block shuts the gate`() {
        val v = gate(maintenanceBlocked = true)
        assertTrue(v is GateVerdict.Shut)
    }

    // ---- B2: an outstanding reservation shuts the gate ----

    @Test
    fun `a pending reservation shuts the gate`() {
        val v = gate(reservation = ReservationState.Pending(reservation()))
        assertTrue("expected Shut, got $v", v is GateVerdict.Shut)
    }

    @Test
    fun `an unreadable reservation shuts the gate`() {
        // "Cannot read" is not "none". Treating it as none would let a corrupt
        // reservation release the gate while a process may be running.
        val v = gate(reservation = ReservationState.Unreadable("corrupt"))
        assertTrue(v is GateVerdict.Shut)
    }

    // ---- the registry ----

    @Test
    fun `a live process shuts the gate`() {
        val v = gate(scan = ScanOutcome.AllAlive(listOf(ProcIdentity(10, 1L))))
        assertTrue("expected Shut, got $v", v is GateVerdict.Shut)
    }

    @Test
    fun `a row for an exited process does not shut the gate`() {
        // The row is stale bookkeeping, not a hazard. Refusing on it is what
        // made every session fail after the first internal probe.
        val v = gate(scan = ScanOutcome.AllDead(0))
        assertEquals(GateVerdict.Open, v)
    }

    @Test
    fun `an unverifiable scan shuts the gate`() {
        val v = gate(scan = ScanOutcome.Unverifiable("corrupt"))
        assertTrue("unverifiable must not be read as empty", v is GateVerdict.Shut)
    }

    // ---- combinations -------------------------------------------------------

    @Test
    fun `every single blocking condition is sufficient on its own`() {
        // Asserted as a set so a future refactor that drops one branch shows up
        // as exactly one failure rather than a vague "gate broken".
        val blockers = mapOf(
            "wipe" to gate(wipeActive = true),
            "maintenance" to gate(maintenanceBlocked = true),
            "reservation-pending" to gate(
                reservation = ReservationState.Pending(reservation()),
            ),
            "reservation-unreadable" to gate(
                reservation = ReservationState.Unreadable("x"),
            ),
            "registry-alive" to gate(
                scan = ScanOutcome.AllAlive(listOf(ProcIdentity(10, 1L))),
            ),
            "registry-unverifiable" to gate(
                scan = ScanOutcome.Unverifiable("x"),
            ),
        )
        for ((name, verdict) in blockers) {
            assertTrue("$name must shut the gate, got $verdict", verdict is GateVerdict.Shut)
        }
    }

    @Test
    fun `a shut gate says why`() {
        // The user needs to know whether this is transient or permanent; a bare
        // boolean cannot express that.
        val v = gate(wipeActive = true) as GateVerdict.Shut
        assertTrue("reason must not be blank", v.reason.isNotBlank())
    }

    @Test
    fun `an empty reservation and empty registry open the gate`() {
        assertEquals(
            GateVerdict.Open,
            gate(reservation = ReservationState.None, scan = ScanOutcome.Empty),
        )
    }
}
