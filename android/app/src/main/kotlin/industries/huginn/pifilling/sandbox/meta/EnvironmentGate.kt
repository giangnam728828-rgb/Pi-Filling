package industries.huginn.pifilling.sandbox.meta

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Serialises access to the sandbox environment.
 *
 * Two locks, in a fixed order, for two different jobs:
 *
 *  - [opLock] serialises *operations* that modify the environment. A second one
 *    is refused immediately rather than queued — queueing would read as "tapping
 *    twice installs twice".
 *  - [gateLock] serialises *the right to start a process at all*. A wipe holds
 *    it while it decides what is safe to delete, so no new process can appear
 *    between its scan and its deletion.
 *
 * The order is always `opLock -> gateLock`. Nothing may acquire them the other
 * way round: two paths that each take both locks in opposite orders deadlock
 * even when each lock is used correctly in isolation.
 *
 * This class holds **no** copy of the registry, reservation or wipe state. Those
 * remain authoritative where they live; the gate only asks them, under
 * [gateLock], whether it may proceed. Duplicating that state here would create a
 * second source of truth that could disagree with the first.
 */
class EnvironmentGate(
    /** Reads the durable spawn reservation. */
    private val reservationState: () -> ReservationState,
    /**
     * Probes whether any recorded process is still alive.
     *
     * A scan, not a read of the registry's contents: a row for an exited process
     * must not count as a running one.
     */
    private val scanRegistry: () -> ScanOutcome,
) {

    private val opLock = Mutex()
    private val gateLock = Mutex()

    /** Monotonic operation id; used to discard stale state emissions. */
    private val opSeq = AtomicLong(0)

    /** True while a wipe owns the environment in this process. */
    @Volatile
    private var wipeActive: Boolean = false

    /** Set when a maintenance hazard is unresolved. */
    @Volatile
    private var maintenanceBlocked: Boolean = false

    /** Why the gate is currently shut, for the UI and for logs. */
    fun blockReason(): String? = evaluateGate(inputs()).let {
        if (it is GateVerdict.Shut) it.reason else null
    }

    private fun inputs() = GateInputs(
        wipeActive = wipeActive,
        maintenanceBlocked = maintenanceBlocked,
        reservation = reservationState(),
        scan = scanRegistry(),
    )

    // ---- read side ----------------------------------------------------------

    /**
     * Run [block] only if a process may be started right now.
     *
     * The lock covers the whole call. That is the point: a wipe starting between
     * "the gate was open" and "the process exists" would scan before the new
     * process was registered, and would then conclude that nothing was running.
     *
     * Returns null when the gate is shut. The caller decides how to report it.
     */
    suspend fun <T> withSpawnPermit(block: suspend () -> T): T? {
        if (!gateLock.tryLock()) {
            // Distinguishable from "the gate is shut": this means another
            // operation is holding the environment right now, which resolves on
            // its own. Reporting both as the same null made a stuck lock look
            // like a policy decision for three releases.
            lastSpawnRefusal = "environment lock held by another operation"
            return null
        }
        try {
            val verdict = evaluateGate(inputs())
            if (verdict !is GateVerdict.Open) {
                lastSpawnRefusal = "gate shut: " + (verdict as GateVerdict.Shut).reason
                return null
            }
            lastSpawnRefusal = null
            return block()
        } finally {
            gateLock.unlock()
        }
    }

    /** Why the most recent [withSpawnPermit] returned null, or null if it did not. */
    @Volatile
    private var lastSpawnRefusal: String? = null

    fun lastRefusal(): String? = lastSpawnRefusal

    /**
     * Run [block] as the only modifying operation, with the environment gate held.
     *
     * Refuses immediately when another operation is running: a caller that waits
     * would look like a queued install, which is not what the user asked for.
     */
    suspend fun <T> withOperation(block: suspend (opId: Long) -> T): T? {
        if (!opLock.tryLock()) return null
        try {
            return gateLock.withLock {
                if (evaluateGate(inputs()) !is GateVerdict.Open) return@withLock null
                block(opSeq.incrementAndGet())
            }
        } finally {
            opLock.unlock()
        }
    }

    /**
     * Run [block] under both locks **without** consulting the gate.
     *
     * For recovery only: the state that shuts the gate is precisely what these
     * operations exist to repair. Using [withOperation] for them would be a
     * deadlock in the practical sense — the cleanup could never run because the
     * thing it cleans is what blocks it.
     *
     * Still takes both locks, so it cannot race a wipe or a spawn; it merely
     * skips the permit decision. Every use must be a repair path that inspects
     * state itself and refuses when the state is not provably safe.
     */
    suspend fun <T> withRecovery(block: suspend (opId: Long) -> T): T? {
        if (!opLock.tryLock()) return null
        try {
            return gateLock.withLock { block(opSeq.incrementAndGet()) }
        } finally {
            opLock.unlock()
        }
    }

    /**
     * Run [block] as the exclusive wipe.
     *
     * Takes both locks, in the fixed order, and marks the wipe active for the
     * duration so that any spawn attempt is refused even before it reaches the
     * registry. Returns null when another operation holds [opLock] — the wipe
     * must not wait and then begin behind the user's back.
     */
    suspend fun <T> withWipe(block: suspend (opId: Long) -> T): T? {
        if (!opLock.tryLock()) return null
        try {
            return gateLock.withLock {
                val opId = opSeq.incrementAndGet()
                wipeActive = true
                try {
                    block(opId)
                } finally {
                    // Cleared here, not by the caller: a wipe that throws must
                    // not leave the environment permanently gated.
                    wipeActive = false
                }
            }
        } finally {
            opLock.unlock()
        }
    }

    /**
     * Whether a wipe may begin at all.
     *
     * Checked without taking the locks, for callers that want to report the
     * reason before committing to a wipe.
     */
    fun wipeBlockReason(): String? = when {
        opLock.tryLock() -> {
            opLock.unlock()
            null
        }
        else -> "another operation is running"
    }

    /** Set by the maintenance path; the gate refuses spawns while true. */
    fun setMaintenanceBlocked(blocked: Boolean) {
        maintenanceBlocked = blocked
    }

    /** True while a wipe holds the environment. */
    fun isWipeActive(): Boolean = wipeActive

    /**
     * Diagnostic snapshot of the lock state.
     *
     * `Mutex.isLocked` is public API on kotlinx.coroutines' Mutex, so this can be
     * reported without disturbing anything. Exposed because "正在执行其他操作"
     * is otherwise indistinguishable from a leaked lock, and a leaked lock has
     * no recovery at all.
     */
    fun lockDiagnostics(): String =
        "opLock=${opLock.isLocked} gateLock=${gateLock.isLocked} " +
            "wipeActive=$wipeActive maintenanceBlocked=$maintenanceBlocked"

    /** Monotonic id of the most recent operation, for stale-emission checks. */
    fun currentOpId(): Long = opSeq.get()

    /**
     * Publish [next] only if [opId] is still the current operation.
     *
     * Without this a slow operation can overwrite the state of a newer one, and
     * the UI ends up showing progress for something that already finished.
     */
    inline fun emitIfCurrent(opId: Long, next: () -> Unit) {
        if (currentOpId() == opId) next()
    }
}
