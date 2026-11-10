package industries.huginn.pifilling.sandbox.meta

/**
 * A process launch that has been authorised but not yet confirmed registered.
 *
 * Written **before** the process is created, so that a crash anywhere between
 * "about to start" and "recorded in the registry" leaves durable evidence that
 * something may be running unregistered.
 *
 * Without this, the only trace of an unregistered live process would be an
 * in-memory flag, which a restart erases — and the next wipe would then read an
 * empty registry and conclude that everything had died. The reservation is what
 * makes "I do not know" survive a reboot.
 */
data class SpawnReservation(
    /** Unique per launch, e.g. `spawn#lz3k-7`. */
    val id: String,
    /** Schema version, so a future format change is detectable rather than misread. */
    val format: Int,
    /** Which entry point asked for this: setup / tools / redeploy / wrapper / probe. */
    val purpose: String,
    /** Monotonic within a process; survives wall-clock changes. */
    val seq: Long,
    /** Wall clock. Diagnostics only — never used for ordering. */
    val startedAt: Long,
    /** Root pid, or -1 while unknown (the process has not been started yet). */
    val pid: Int,
    /** Start time ticks for [pid], or -1 while unknown. */
    val startTimeTicks: Long,
) {
    /** True once the pid was recorded, i.e. recovery can attempt verification. */
    val hasPid: Boolean get() = pid > 0 && startTimeTicks >= 0

    companion object {
        const val CURRENT_FORMAT = 1
        /** Name of the file inside the metadata store. */
        const val FILE_NAME = "spawn.pending"
    }
}

/**
 * Reads and writes the single outstanding spawn reservation.
 *
 * Only one spawn may be in flight at a time: the manager holds the environment
 * gate across the whole start-and-register window, so a second reservation can
 * only appear after the first was cleared or the process died.
 */
class SpawnReservationStore(private val store: AtomicStore) {

    /** The outstanding reservation, or null when none was written. */
    fun read(): SpawnReservation? {
        val text = when (val r = store.read(SpawnReservation.FILE_NAME)) {
            ReadResult.Absent -> return null
            is ReadResult.Unknown -> return null
            is ReadResult.Found -> String(r.bytes, Charsets.UTF_8)
        }
        if (text.isBlank()) return null
        return decode(text)
    }

    /**
     * Read with the three-state distinction the wipe depends on.
     *
     * [read] folds "cannot parse" into null, which would let a corrupt
     * reservation look like "no reservation". That is the one confusion this
     * file must never make.
     */
    fun state(): ReservationState = when (val r = store.read(SpawnReservation.FILE_NAME)) {
        ReadResult.Absent -> ReservationState.None
        is ReadResult.Unknown -> ReservationState.Unreadable(r.reason)
        is ReadResult.Found -> {
            val text = String(r.bytes, Charsets.UTF_8)
            if (text.isBlank()) ReservationState.None
            else decode(text)?.let { ReservationState.Pending(it) }
                ?: ReservationState.Unreadable("reservation is not well-formed")
        }
    }

    /** Persist a reservation. Must succeed before any process is started. */
    fun write(reservation: SpawnReservation): WriteResult =
        store.writeText(SpawnReservation.FILE_NAME, encode(reservation))

    /**
     * Record the pid once the process is up.
     *
     * A failure here must **not** clear the reservation: the process is running
     * and we cannot prove which one, so the old reservation (without a pid) has
     * to stay and keep the gate shut.
     */
    fun updatePid(reservation: SpawnReservation, pid: Int, startTimeTicks: Long): WriteResult =
        write(reservation.copy(pid = pid, startTimeTicks = startTimeTicks))

    /** Clear the reservation. Only valid once the spawn is fully accounted for. */
    fun clear(): WriteResult {
        val file = store.resolveSafe(SpawnReservation.FILE_NAME)
            ?: return WriteResult.Failed(
                WriteStage.PATH_REJECTED,
                IllegalArgumentException("bad reservation name"),
            )
        if (!file.exists()) return WriteResult.Ok
        // Route through the store's atomic write so the removal is durable: a
        // plain delete() leaves the directory entry unflushed, and a power loss
        // could resurrect a reservation that was already handled.
        return store.writeText(SpawnReservation.FILE_NAME, "")
    }

    private fun encode(r: SpawnReservation): String = buildString {
        append("{\"id\":\"").append(r.id.replace("\"", "")).append('"')
        append(",\"format\":").append(r.format)
        append(",\"purpose\":\"").append(r.purpose.replace("\"", "")).append('"')
        append(",\"seq\":").append(r.seq)
        append(",\"startedAt\":").append(r.startedAt)
        append(",\"pid\":").append(r.pid)
        append(",\"startTimeTicks\":").append(r.startTimeTicks)
        append('}')
    }

    private fun decode(text: String): SpawnReservation? {
        val id = FIELD_ID.find(text)?.groupValues?.get(1) ?: return null
        val format = FIELD_FORMAT.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val purpose = FIELD_PURPOSE.find(text)?.groupValues?.get(1) ?: return null
        val seq = FIELD_SEQ.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val startedAt = FIELD_AT.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val pid = FIELD_PID.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val start = FIELD_START.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return SpawnReservation(id, format, purpose, seq, startedAt, pid, start)
    }

    companion object {
        private val FIELD_ID = Regex("\"id\"\\s*:\\s*\"([^\"]*)\"")
        private val FIELD_FORMAT = Regex("\"format\"\\s*:\\s*(\\d+)")
        private val FIELD_PURPOSE = Regex("\"purpose\"\\s*:\\s*\"([^\"]*)\"")
        private val FIELD_SEQ = Regex("\"seq\"\\s*:\\s*(-?\\d+)")
        private val FIELD_AT = Regex("\"startedAt\"\\s*:\\s*(-?\\d+)")
        private val FIELD_PID = Regex("\"pid\"\\s*:\\s*(-?\\d+)")
        private val FIELD_START = Regex("\"startTimeTicks\"\\s*:\\s*(-?\\d+)")
    }
}

sealed interface ReservationState {
    /** No reservation outstanding. */
    data object None : ReservationState
    /** A launch was authorised and never confirmed complete. */
    data class Pending(val reservation: SpawnReservation) : ReservationState
    /** Cannot tell. Must block. */
    data class Unreadable(val reason: String) : ReservationState
}
