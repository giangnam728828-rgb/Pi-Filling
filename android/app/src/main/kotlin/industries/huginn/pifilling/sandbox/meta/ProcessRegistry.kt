package industries.huginn.pifilling.sandbox.meta

import java.io.File

/**
 * One recorded process, identified so that pid reuse is detectable.
 *
 * [startTimeTicks] is `/proc/<pid>/stat` field 22 (process start time in clock
 * ticks since boot). Recording it is what separates "our process is still
 * running" from "a stranger inherited our pid" — two situations that look
 * identical if only the pid is stored, and that call for opposite responses.
 *
 * A value of -1 means it could not be read (SELinux on Android 10+ restricts
 * `/proc/<pid>/stat` for processes outside our uid). Such an entry can never be
 * verified, so it must block rather than be assumed dead.
 */
data class ProcIdentity(
    val pid: Int,
    val startTimeTicks: Long,
) {
    /** False when pid reuse could go unnoticed. */
    val verifiable: Boolean get() = startTimeTicks >= 0
}

/**
 * The verified state of one recorded process.
 *
 * Four states, not three. [Reused] exists because "the pid is occupied" and "our
 * process is running" are different facts: collapsing them would either delete
 * files a live process holds (if Reused were read as Dead) or block forever on a
 * process that is already gone (if it were read as Alive).
 */
sealed interface ProcStatus {
    val identity: ProcIdentity

    /**
     * The pid exists and its start time matches the record.
     *
     * [treeComplete] carries the entry's capture completeness so callers can tell
     * "this process is running" from "this process and everything it spawned are
     * accounted for". A spawn decision needs only the first; a deletion decision
     * needs the second.
     */
    data class Alive(
        override val identity: ProcIdentity,
        val treeComplete: Boolean = true,
    ) : ProcStatus

    /** Confirmed gone: no `/proc` entry at that pid. */
    data class Dead(override val identity: ProcIdentity) : ProcStatus

    /**
     * A process exists at this pid, but its start time differs.
     *
     * Our process is gone; this is a stranger. Must not be treated as alive (it
     * is not ours) nor silently deleted as dead (the record still needs
     * reconciling, and the entry is evidence that a pid was recycled).
     */
    data class Reused(
        override val identity: ProcIdentity,
        val actualStart: Long,
    ) : ProcStatus

    /**
     * Cannot tell. `/proc` unreadable, the record has no start time, or the walk
     * was incomplete.
     *
     * The only correct response is to block. Treating it as dead risks deleting
     * files a live process still holds; treating it as alive blocks without
     * saying why.
     */
    data class Unverifiable(
        override val identity: ProcIdentity,
        val reason: String,
    ) : ProcStatus
}

/** A process tree captured at launch time. */
data class ProcTree(
    val rootPid: Int,
    val members: List<ProcIdentity>,
    /**
     * False when the walk hit its deadline or `/proc` was partly unreadable.
     *
     * This is part of the record, not a diagnostic: an incomplete tree means we
     * do not know what else belongs to this process, so a later recycle must
     * block while any member is alive rather than conclude the tree is gone.
     */
    val treeComplete: Boolean,
)

data class RegistryEntry(
    val identity: ProcIdentity,
    val treeComplete: Boolean,
    /**
     * The spawn reservation that created this process, or "" for entries
     * recorded before this field existed (legacy rows).
     *
     * Persisted so a reservation can prove a registry row belongs to it.
     * Without it, a "match" could only compare pid + start time, which two
     * different spawns can theoretically share after pid reuse.
     */
    val spawnId: String = "",
)

data class RecycleRecord(
    val entries: List<RegistryEntry>,
    /** Consecutive scans that found nothing alive. 0..[ROUNDS_TO_CLEAR]. */
    val emptyRounds: Int,
)

sealed interface ScanOutcome {
    /** Nothing recorded. */
    data object Empty : ScanOutcome

    /** Every entry confirmed [ProcStatus.Dead]. */
    data class AllDead(val rounds: Int) : ScanOutcome

    /** A mix; the dead entries may be pruned, the alive ones block. */
    data class Partial(
        val alive: List<ProcIdentity>,
        val dead: List<ProcIdentity>,
    ) : ScanOutcome

    /** Every entry still alive. Blocks. */
    data class AllAlive(val alive: List<ProcIdentity>) : ScanOutcome

    /** A pid was recycled. Blocks; entries retained for reconciliation. */
    data class HasReused(val reused: List<ProcStatus.Reused>) : ScanOutcome

    /** Any unverifiable entry, or `/proc` unreadable. Blocks. */
    data class Unverifiable(val reason: String) : ScanOutcome
}

/**
 * Tracks processes the sandbox started so that a wipe can know whether anything
 * still holds the files it wants to delete.
 *
 * The registry is deliberately conservative. It never concludes "nothing is
 * running" from an absence of evidence: an unreadable `/proc`, a missing start
 * time, or an incomplete tree all block instead. Blocking is recoverable — the
 * user can retry, or the next launch can re-verify — while deleting a directory
 * out from under a live process is not.
 *
 * Pure Kotlin: [procRoot] is injectable so tests need no real `/proc`, and no
 * `android.*` type appears here.
 */
class ProcessRegistry(
    private val store: AtomicStore,
    /** Root for `/proc` lookups. Tests point this at a temporary directory. */
    private val procRoot: File = File("/proc"),
) {

    /**
     * Record a freshly launched tree.
     *
     * Merges with what is already stored rather than replacing it: two processes
     * may be alive at once (a wrapper and an install), and dropping the earlier
     * entry would let a recycle conclude that only the newer one existed.
     */
    fun record(tree: ProcTree, spawnId: String): WriteResult {
        // An empty id cannot be matched against a reservation later, which would
        // make the spawn unaccounted for while looking recorded. Fail rather
        // than write a row that can never be proven to belong to this spawn.
        if (spawnId.isBlank()) {
            return WriteResult.Failed(
                WriteStage.PATH_REJECTED,
                IllegalArgumentException("record() requires a non-blank spawn id"),
            )
        }
        // An empty member list means the tree could not be captured. Returning
        // Ok would tell the caller "recorded" while nothing was written, and
        // would silently drop treeComplete=false — that flag has no entry to
        // attach to.
        if (tree.members.isEmpty()) {
            return WriteResult.Failed(
                WriteStage.TEMP_WRITE,
                IllegalStateException(
                    "cannot record an empty process tree (root=${tree.rootPid}, " +
                        "complete=${tree.treeComplete})",
                ),
            )
        }

        val current = current() ?: RecycleRecord(emptyList(), 0)
        val merged = current.entries.toMutableList()
        for (identity in tree.members) {
            // Match on the full identity, not just the pid: a reused pid is a
            // different process and must not overwrite the earlier row.
            val existing = merged.indexOfFirst { it.identity == identity }
            if (existing < 0) {
                merged += RegistryEntry(identity, tree.treeComplete, spawnId)
                continue
            }
            val old = merged[existing]
            if (old.spawnId.isNotBlank() && old.spawnId != spawnId) {
                // Two spawns claim the same (pid, startTime). The registry
                // cannot represent both, and picking one would silently discard
                // the other's evidence.
                return WriteResult.Failed(
                    WriteStage.TEMP_WRITE,
                    IllegalStateException(
                        "pid ${identity.pid} already recorded by ${old.spawnId}, " +
                            "cannot re-record under $spawnId",
                    ),
                )
            }
            // An incomplete tree is safety evidence, not a capture attribute:
            // a later complete capture cannot prove the unknown descendants of
            // the earlier one are gone. Keep it false once it is false.
            merged[existing] = RegistryEntry(
                identity = identity,
                treeComplete = old.treeComplete && tree.treeComplete,
                spawnId = spawnId,
            )
        }
        return persist(RecycleRecord(merged, current.emptyRounds))
    }

    /**
     * Prune entries that are confirmed [ProcStatus.Dead].
     *
     * Only dead entries may go. Reused and unverifiable entries are retained:
     * removing them would turn an untrustworthy file into a trustworthy one by
     * deletion, which is precisely the laundering this registry must not permit.
     * Pruning also must not reset the incomplete-tree flag on what remains.
     */
    fun prune(dead: List<ProcIdentity>): WriteResult {
        val current = current() ?: return WriteResult.Ok
        val gone = dead.toSet()
        val kept = current.entries.filterNot { it.identity in gone }
        return persist(RecycleRecord(kept, current.emptyRounds))
    }

    /** Remove the registry entirely. Only valid once nothing is recorded. */
    fun clear(): WriteResult {
        val file = store.resolveSafe(RECYCLE_NAME)
            ?: return WriteResult.Failed(
                WriteStage.PATH_REJECTED,
                IllegalArgumentException("bad registry name"),
            )
        if (!file.exists()) return WriteResult.Ok
        return if (file.delete()) WriteResult.Ok
        else WriteResult.Failed(
            WriteStage.RENAME,
            java.io.IOException("could not delete $RECYCLE_NAME"),
        )
    }

    /**
     * The registry's contents with "cannot read" kept distinct from "empty".
     *
     * `current()` folds an unreadable file into null, which is fine for
     * maintenance helpers but not for a wipe: "the file cannot be parsed" and
     * "nothing was ever recorded" lead to opposite decisions, and only one of
     * them may proceed.
     */
    sealed interface RegistryState {
        /** Confirmed to hold no records. */
        data object Empty : RegistryState
        data class Present(val record: RecycleRecord) : RegistryState
        /** Cannot tell. Must block. */
        data class Unreadable(val reason: String) : RegistryState
    }

    fun state(): RegistryState = when (val r = store.read(RECYCLE_NAME)) {
        ReadResult.Absent -> RegistryState.Empty
        is ReadResult.Unknown -> RegistryState.Unreadable(r.reason)
        is ReadResult.Found -> {
            val text = String(r.bytes, Charsets.UTF_8)
            if (text.isBlank()) {
                RegistryState.Empty
            } else {
                decode(text)?.let { RegistryState.Present(it) }
                    ?: RegistryState.Unreadable("registry is not well-formed")
            }
        }
    }

    /** Current contents, or null when nothing has been recorded. */
    fun current(): RecycleRecord? {
        val text = when (val r = store.read(RECYCLE_NAME)) {
            ReadResult.Absent -> return null
            is ReadResult.Unknown -> return null
            is ReadResult.Found -> String(r.bytes, Charsets.UTF_8)
        }
        if (text.isBlank()) return null
        return decode(text)
    }

    /**
     * Verify every recorded entry against `/proc`.
     *
     * A corrupt registry yields [ScanOutcome.Unverifiable] rather than an empty
     * result: an unreadable record is not the same as "nothing was running".
     */
    fun scan(): ScanOutcome {
        val raw = when (val r = store.read(RECYCLE_NAME)) {
            ReadResult.Absent -> return ScanOutcome.Empty
            is ReadResult.Unknown -> return ScanOutcome.Unverifiable(
                "registry unreadable: ${r.reason}",
            )
            is ReadResult.Found -> String(r.bytes, Charsets.UTF_8)
        }
        if (raw.isBlank()) return ScanOutcome.Empty

        val record = decode(raw)
            ?: return ScanOutcome.Unverifiable("registry is not well-formed")

        // Duplicate pids mean two records claim the same slot; there is no basis
        // for choosing between them.
        val pids = record.entries.map { it.identity.pid }
        if (pids.size != pids.toSet().size) {
            return ScanOutcome.Unverifiable("registry lists a pid twice")
        }
        if (!procRoot.isDirectory) {
            return ScanOutcome.Unverifiable("/proc is not readable")
        }

        val statuses = record.entries.map { verify(it) }

        statuses.firstOrNull { it is ProcStatus.Unverifiable }?.let {
            return ScanOutcome.Unverifiable((it as ProcStatus.Unverifiable).reason)
        }
        val reused = statuses.filterIsInstance<ProcStatus.Reused>()
        if (reused.isNotEmpty()) return ScanOutcome.HasReused(reused)

        val alive = statuses.filterIsInstance<ProcStatus.Alive>().map { it.identity }
        val dead = statuses.filterIsInstance<ProcStatus.Dead>().map { it.identity }

        return when {
            alive.isEmpty() -> ScanOutcome.AllDead(record.emptyRounds)
            dead.isEmpty() -> ScanOutcome.AllAlive(alive)
            else -> ScanOutcome.Partial(alive, dead)
        }
    }

    /**
     * Advance the empty-round counter after a scan.
     *
     * Three consecutive rounds with nothing alive are required before the
     * registry may be cleared. A process leaving `/proc` is immediate, but its
     * children may still be flushing writes; a single empty scan cannot tell the
     * difference between "gone" and "almost gone".
     */
    fun advanceRounds(allDead: Boolean): WriteResult {
        val current = current() ?: return WriteResult.Ok
        val next = if (allDead) {
            (current.emptyRounds + 1).coerceAtMost(ROUNDS_TO_CLEAR)
        } else {
            0
        }
        return persist(RecycleRecord(current.entries, next))
    }

    // ---- verification -------------------------------------------------------

    private fun verify(entry: RegistryEntry): ProcStatus {
        val identity = entry.identity
        if (!identity.verifiable) {
            return ProcStatus.Unverifiable(
                identity,
                "no start time recorded for pid ${identity.pid}",
            )
        }
        // An incomplete tree is not evidence that anything is still running: it
        // is evidence that we did not enumerate everything. Those are different
        // questions with different answers.
        //
        //   * "May a new process start?" — yes. Whatever the incomplete tree
        //     left behind is unknown, but starting another process does not
        //     make it more unknown, and refusing would leave the sandbox
        //     unusable forever, since a 200ms walk can plausibly time out.
        //   * "May this environment be deleted?" — no. A descendant we never
        //     enumerated could still be holding the rootfs, and there is no
        //     list to re-verify against.
        //
        // The completeness flag therefore gates the wipe (see `safeToClear`),
        // not the spawn. It is still reported on the entry so the wipe can
        // refuse.
        val treeIncomplete = !entry.treeComplete
        // Distinguish "the entry is gone" from "the entry is there but I could
        // not parse it". Treating a parse failure as Dead would delete files
        // held by a process we simply failed to read — the exact mistake this
        // registry exists to prevent.
        val statFile = File(procRoot, "${identity.pid}/stat")
        if (!statFile.exists()) return ProcStatus.Dead(identity)
        val stat = readStat(identity.pid)
            ?: return ProcStatus.Unverifiable(
                identity,
                "pid ${identity.pid} exists but its stat could not be parsed",
            )

        if (stat.startTimeTicks != identity.startTimeTicks) {
            return ProcStatus.Reused(identity, stat.startTimeTicks)
        }
        return ProcStatus.Alive(identity, treeComplete = entry.treeComplete)
    }

    private data class Stat(val ppid: Int, val startTimeTicks: Long)

    private fun readStat(pid: Int): Stat? {
        val file = File(procRoot, pid.toString() + "/stat")
        if (!file.isFile) return null
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        val parsed = parseStat(raw) ?: return null
        return Stat(parsed.first, parsed.second)
    }

    // ---- persistence --------------------------------------------------------

    private fun persist(record: RecycleRecord): WriteResult =
        store.writeText(RECYCLE_NAME, encode(record))

    private fun encode(record: RecycleRecord): String = buildString {
        append("{\"emptyRounds\":").append(record.emptyRounds).append(",\"entries\":[")
        record.entries.forEachIndexed { i, e ->
            if (i > 0) append(',')
            append("{\"pid\":").append(e.identity.pid)
            append(",\"start\":").append(e.identity.startTimeTicks)
            append(",\"treeComplete\":").append(e.treeComplete)
            append(",\"spawnId\":\"").append(e.spawnId.replace("\"", "")).append('"')
            append('}')
        }
        append("]}")
    }

    private fun decode(text: String): RecycleRecord? {
        val rounds = ROUNDS_PATTERN.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val matches = ENTRY_PATTERN.findAll(text).toList()
        // A body that mentions a pid but yields no parsable entry is corrupt,
        // not empty. Silently returning an empty record would turn a damaged
        // file into a clean one.
        if (matches.isEmpty() && text.contains("\"pid\"")) return null

        val entries = matches.mapNotNull { m ->
            val pid = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val start = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val complete = m.groupValues[3].toBooleanStrictOrNull() ?: return@mapNotNull null
            // Missing group -> legacy row -> "" -> never matches a reservation.
            val spawnId = m.groupValues.getOrNull(4).orEmpty()
            RegistryEntry(ProcIdentity(pid, start), complete, spawnId)
        }
        // A partial parse means part of the file was unreadable; the whole
        // record is untrustworthy.
        if (entries.size != matches.size) return null
        return RecycleRecord(entries, rounds)
    }

    /**
     * Verify that every process in [tree] has exited.
     *
     * Returns a [KillOutcome] whose [KillOutcome.knownTreeExited] reflects a
     * fresh probe of each recorded identity — not the fact that a `waitFor()`
     * returned. A root process can exit while its children keep running, and
     * those children still hold the rootfs.
     *
     * When the tree was captured incompletely, [KillOutcome.safeToClear] stays
     * false no matter what the probe finds: the members we never saw cannot be
     * shown to be gone, and this registry has no list to re-verify against.
     */
    fun verifyTreeExited(tree: ProcTree, killRequested: Boolean): KillOutcome {
        // Probe each member's liveness only. Tree completeness is carried
        // separately in the outcome: passing it into `verify` would make every
        // member Unverifiable for an incomplete tree, and then "known members
        // exited" could never be reported even when it is true. The two facts
        // are different and the caller needs both.
        fun isGone(member: ProcIdentity): Boolean =
            when (verify(RegistryEntry(member, treeComplete = true, spawnId = ""))) {
                is ProcStatus.Dead, is ProcStatus.Reused -> true
                // Unverifiable covers "no start time" and "stat unreadable".
                // Neither may be read as "gone": that is the mistake this whole
                // layer exists to prevent.
                is ProcStatus.Alive, is ProcStatus.Unverifiable -> false
            }

        val allGone = tree.members.all(::isGone)
        return KillOutcome(
            killRequested = killRequested,
            // The root is the first member by construction in captureTree.
            rootExited = tree.members.firstOrNull()?.let(::isGone) ?: false,
            knownTreeExited = allGone,
            treeWasComplete = tree.treeComplete,
        )
    }

    companion object {
        const val RECYCLE_NAME = "recycle.json"

        /** Rounds with nothing alive before the registry may be cleared. */
        const val ROUNDS_TO_CLEAR = 3

        private val ROUNDS_PATTERN = Regex("\"emptyRounds\"\\s*:\\s*(\\d+)")
        private val ENTRY_PATTERN = Regex(
            "\"pid\"\\s*:\\s*(-?\\d+)\\s*,\\s*\"start\"\\s*:\\s*(-?\\d+)\\s*," +
                "\\s*\"treeComplete\"\\s*:\\s*(true|false)" +
                // Optional: files written before spawnId existed must still
                // parse, and yield "" so they can never match a reservation.
                "(?:\\s*,\\s*\"spawnId\"\\s*:\\s*\"([^\"]*)\")?",
        )

        /**
         * Parse `(ppid, startTimeTicks)` out of `/proc/<pid>/stat`.
         *
         * The `comm` field is parenthesised and may itself contain spaces and
         * closing parentheses — `(Web Content)`, `(a b (c))` — so splitting the
         * whole line on spaces misaligns every field after it. The only safe
         * split point is the *last* `)`, after which the fields are plain
         * space-separated numbers:
         *
         * ```
         * index 0  = state
         * index 1  = ppid          (stat field 4)
         * index 19 = starttime     (stat field 22)
         * ```
         *
         * Returns null when the line does not have that shape, which the caller
         * treats as unverifiable rather than guessing.
         */
        internal fun parseStat(raw: String): Pair<Int, Long>? {
            val close = raw.lastIndexOf(')')
            if (close < 0 || close + 2 >= raw.length) return null
            val fields = raw.substring(close + 2).trim().split(' ')
            if (fields.size < 20) return null
            val ppid = fields[1].toIntOrNull() ?: return null
            val start = fields[19].toLongOrNull() ?: return null
            return ppid to start
        }
    }
}
