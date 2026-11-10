package industries.huginn.pifilling.sandbox.meta

/**
 * The single decision "may a new process be started?".
 *
 * Extracted from the manager so it can be tested without an Android `Context`:
 * every branch here is a fail-closed rule, and each one is worth asserting
 * individually rather than inferring from a larger integration path.
 *
 * The four inputs are deliberately separate. Collapsing them into one boolean
 * would lose the ability to say *why* the gate is shut, which is what the user
 * needs when the environment refuses to do anything.
 */
data class GateInputs(
    /** A wipe is running in this process right now. */
    val wipeActive: Boolean,
    /** A maintenance hazard is unresolved in this process. */
    val maintenanceBlocked: Boolean,
    /** Disk evidence of a spawn that never finished registering. */
    val reservation: ReservationState,
    /**
     * Whether any recorded process is *still alive*.
     *
     * Deliberately not the registry's contents. "There are rows" and "there is a
     * running process" are different facts: a probe that has already exited
     * leaves a row behind, and treating that row as a live process is what shut
     * this gate permanently on every session.
     */
    val scan: ScanOutcome,
)

sealed interface GateVerdict {
    data object Open : GateVerdict
    data class Shut(val reason: String) : GateVerdict
}

/**
 * Decide whether the environment gate is open.
 *
 * Only one combination opens it: no wipe, no maintenance block, no outstanding
 * reservation, and a registry confirmed to be empty. Every other combination
 * shuts it, including — importantly — the ones where the evidence merely could
 * not be read.
 */
fun evaluateGate(inputs: GateInputs): GateVerdict = when {
    inputs.wipeActive ->
        GateVerdict.Shut("a wipe is running")

    inputs.maintenanceBlocked ->
        GateVerdict.Shut("maintenance is blocked")

    // A pending reservation means a previous spawn never completed. Whatever it
    // started may not be in the registry, so the gate cannot open until that is
    // reconciled — this is the state that has to survive a restart, which an
    // in-memory flag could not do.
    inputs.reservation is ReservationState.Pending ->
        GateVerdict.Shut("a previous spawn never finished registering")

    inputs.reservation is ReservationState.Unreadable ->
        GateVerdict.Shut("the spawn reservation cannot be read")

    // Only a live or unverifiable process shuts the gate. A row for a process
    // that has already exited is stale bookkeeping, not a hazard: it is exactly
    // what a probe leaves behind, and refusing on it means refusing forever.
    else -> when (val scan = inputs.scan) {
        ScanOutcome.Empty, is ScanOutcome.AllDead -> GateVerdict.Open

        is ScanOutcome.AllAlive ->
            GateVerdict.Shut("a recorded process is still running")

        is ScanOutcome.Partial ->
            GateVerdict.Shut("some recorded processes are still running")

        is ScanOutcome.HasReused ->
            GateVerdict.Shut("a recorded pid was reused; identity cannot be confirmed")

        is ScanOutcome.Unverifiable ->
            GateVerdict.Shut("process state cannot be verified: ${scan.reason}")
    }
}
