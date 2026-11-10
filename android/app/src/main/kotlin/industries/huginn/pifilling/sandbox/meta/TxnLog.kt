package industries.huginn.pifilling.sandbox.meta

import java.io.IOException

/**
 * One durable record in the transaction log.
 *
 * [body] is opaque to this layer — the caller decides what goes in it. What the
 * log guarantees is only that a record either exists intact and is part of an
 * unbroken chain, or the log says it cannot be trusted. There is no third state
 * where a record is "probably fine".
 */
data class TxnRecord(
    val seq: Long,
    val txn: String,
    val prevDigest: String,
    val bodyDigest: String,
    val body: String,
)

/**
 * What the files on disk actually mean.
 *
 * Only [CONTINUOUS] and [ABSENT] are safe to act on. Every other verdict is
 * fail-closed on purpose: a log that cannot be verified must not be read by
 * picking whichever file looks newest, because "looks newest" is exactly the
 * judgement a crash can invalidate.
 */
enum class LogVerdict {
    /** Chain intact and the sequence anchor agrees with the body. */
    CONTINUOUS,

    /** Nothing has ever been written. Not an error. */
    ABSENT,

    /** A link in the chain is missing. */
    GAP,

    /** The stored body does not hash to its recorded digest. */
    DIGEST_MISMATCH,

    /**
     * The anchor and the body disagree, one of them could not be read, or an
     * interrupted write left a marker behind. Nothing can be concluded.
     */
    UNCERTAIN,
}

sealed interface LogState {
    data class Valid(val record: TxnRecord) : LogState
    data object Empty : LogState
    data class Untrusted(val verdict: LogVerdict, val detail: String) : LogState
}

sealed interface AppendResult {
    data class Ok(val record: TxnRecord) : AppendResult

    /** The existing log is not trustworthy; nothing was written. */
    data class Refused(val verdict: LogVerdict) : AppendResult

    data class WriteFailed(val stage: WriteStage, val cause: Throwable) : AppendResult
}

/**
 * An append-only, verifiable record of what the sandbox was in the middle of
 * doing when it stopped.
 *
 * The point is not to store state — [AtomicStore] can do that — but to make a
 * *sequence* of states verifiable, so recovery can tell "the last step finished"
 * from "the last step was interrupted". A single file cannot express that
 * difference; a digest chain plus an independent sequence anchor can.
 *
 * Files, all inside the store:
 *
 * ```
 * txn.main        the current record
 * txn.prev        the previous record, promoted on close()
 * txn.uncertain   present only while a write is in flight
 * log.seq         the sequence anchor, independent of the body
 * ```
 *
 * Write order in [append] is deliberate: the uncertain marker goes down first so
 * an interruption is detectable, the anchor advances next, and the body becomes
 * authoritative last. A failure after the marker leaves the log in a state
 * [read] reports as [LogVerdict.UNCERTAIN] — never as a success with stale data.
 */
class TxnLog(private val store: AtomicStore) {

    /**
     * Append [body] under transaction identity [txn] and promote it to current.
     *
     * [txn] must look like `kind#opId` (for example `setup#7`). Binding the
     * identity to the operation counter is what makes it possible to tell two
     * operations of the same kind apart later: without it, a recovery that finds
     * a half-finished wipe cannot know whether it is looking at the one that was
     * running or the one that was queued behind it.
     */
    fun append(txn: String, body: String): AppendResult {
        if (!TXN_FORMAT.matches(txn)) {
            return AppendResult.WriteFailed(
                WriteStage.PATH_REJECTED,
                IllegalArgumentException("transaction id must be kind#opId, got '$txn'"),
            )
        }

        // Refuse to extend a log we cannot verify. Appending to an unverified
        // chain would launder the uncertainty into a fresh, valid-looking tail.
        val current = when (val state = read()) {
            is LogState.Empty -> null
            is LogState.Valid -> state.record
            is LogState.Untrusted -> return AppendResult.Refused(state.verdict)
        }

        val nextSeq = (current?.seq ?: 0L) + 1
        val bodyDigest = AtomicStore.sha256Hex(body.toByteArray(Charsets.UTF_8))
        val record = TxnRecord(
            seq = nextSeq,
            txn = txn,
            prevDigest = current?.bodyDigest ?: GENESIS,
            bodyDigest = bodyDigest,
            body = body,
        )
        val encoded = encode(record)

        // 1. Leave a trace first, so an interruption anywhere below is visible.
        val marker = store.writeText(UNCERTAIN_NAME, encoded)
        if (marker is WriteResult.Failed) {
            return AppendResult.WriteFailed(marker.stage, marker.cause)
        }

        // 2. Advance the anchor. If this fails the marker stays, and read()
        //    reports UNCERTAIN — which is the truth: we do not know whether the
        //    body below will land.
        val anchor = store.writeText(SEQ_NAME, nextSeq.toString())
        if (anchor is WriteResult.Failed) {
            return AppendResult.WriteFailed(anchor.stage, anchor.cause)
        }

        // 3. Promote the new record to current.
        val main = store.writeText(MAIN_NAME, encoded)
        if (main is WriteResult.Failed) {
            return AppendResult.WriteFailed(main.stage, main.cause)
        }

        // 4. Clear the trace. Delete rather than overwrite with "": the marker's
        //    presence is the signal, so an empty file would still read as "in
        //    flight" and leave every subsequent read UNCERTAIN forever.
        val markerFile = store.resolveSafe(UNCERTAIN_NAME)
        if (markerFile != null && markerFile.exists() && !markerFile.delete()) {
            return AppendResult.WriteFailed(
                WriteStage.RENAME,
                IOException("could not clear $UNCERTAIN_NAME"),
            )
        }

        return AppendResult.Ok(record)
    }

    /**
     * Current log state, without writing anything.
     *
     * Order matters. An uncertain marker short-circuits everything: if a previous
     * append died partway, no other evidence can establish what the log means,
     * so there is no point reading further.
     */
    fun read(): LogState {
        if (uncertainMarkerPresent()) {
            return LogState.Untrusted(
                LogVerdict.UNCERTAIN,
                "an interrupted write left ${UNCERTAIN_NAME} behind",
            )
        }

        val anchor = when (val r = store.read(SEQ_NAME)) {
            is ReadResult.Unknown -> return LogState.Untrusted(
                LogVerdict.UNCERTAIN, "sequence anchor unreadable: ${r.reason}",
            )
            ReadResult.Absent -> null
            is ReadResult.Found -> {
                val text = String(r.bytes, Charsets.UTF_8).trim()
                val value = text.toLongOrNull()
                    ?: return LogState.Untrusted(
                        LogVerdict.UNCERTAIN, "sequence anchor is not a number: '$text'",
                    )
                value
            }
        }

        val main = when (val r = store.read(MAIN_NAME)) {
            is ReadResult.Unknown -> return LogState.Untrusted(
                LogVerdict.UNCERTAIN, "log body unreadable: ${r.reason}",
            )
            ReadResult.Absent -> null
            is ReadResult.Found -> decode(String(r.bytes, Charsets.UTF_8))
                ?: return LogState.Untrusted(
                    LogVerdict.DIGEST_MISMATCH, "log body is not a well-formed record",
                )
        }

        // The four combinations of (anchor, body), each with one correct reading.
        if (anchor == null && main == null) return LogState.Empty

        if (anchor == null && main != null) {
            // The anchor is written before the body, so a body without an anchor
            // means the anchor was lost after the fact. Which value it held is
            // unknowable.
            return LogState.Untrusted(
                LogVerdict.UNCERTAIN,
                "log body present but the sequence anchor is missing",
            )
        }

        if (anchor != null && main == null) {
            return LogState.Untrusted(
                LogVerdict.GAP,
                "sequence anchor is $anchor but there is no log body",
            )
        }

        // Both present.
        anchor!!
        main!!
        if (main.seq > anchor) {
            // The body claims to be ahead of the anchor. Since append() advances
            // the anchor before the body, this cannot happen through the normal
            // path — something wrote the body out of band.
            return LogState.Untrusted(
                LogVerdict.UNCERTAIN,
                "log body seq ${main.seq} is ahead of anchor $anchor",
            )
        }
        if (main.seq < anchor) {
            return LogState.Untrusted(
                LogVerdict.GAP,
                "log body seq ${main.seq} is behind anchor $anchor",
            )
        }

        // Integrity of the body itself.
        val recomputed = AtomicStore.sha256Hex(main.body.toByteArray(Charsets.UTF_8))
        if (recomputed != main.bodyDigest) {
            return LogState.Untrusted(
                LogVerdict.DIGEST_MISMATCH,
                "body digest does not match the recorded value",
            )
        }

        // `prevDigest` points at the *preceding record's body digest*, which for
        // the common case is simply not retained: `append` overwrites main and
        // only `close` copies a record to `txn.prev`. So a missing PREV file is
        // normal, not a gap — requiring it here made every second append report
        // GAP, which is why this check now treats PREV as optional evidence.
        val prev = when (val r = store.read(PREV_NAME)) {
            is ReadResult.Unknown -> return LogState.Untrusted(
                LogVerdict.UNCERTAIN, "previous record unreadable: ${r.reason}",
            )
            ReadResult.Absent -> null
            is ReadResult.Found -> {
                if (String(r.bytes, Charsets.UTF_8).isBlank()) null
                else decode(String(r.bytes, Charsets.UTF_8))
            }
        }

        if (prev != null) {
            // When a previous record *is* on disk it must be the one this record
            // claims to follow. A mismatch means the two files disagree about the
            // chain, and there is no basis for preferring either.
            if (prev.bodyDigest != main.prevDigest) {
                return LogState.Untrusted(
                    LogVerdict.GAP,
                    "record ${main.seq} does not chain to the stored previous record",
                )
            }
            if (prev.seq >= main.seq) {
                return LogState.Untrusted(
                    LogVerdict.UNCERTAIN,
                    "previous record seq ${prev.seq} is not below current ${main.seq}",
                )
            }
            if (prev.txn == main.txn) {
                return LogState.Untrusted(
                    LogVerdict.UNCERTAIN,
                    "transaction id '${main.txn}' appears twice in the chain",
                )
            }
        } else if (main.seq > 1 && main.prevDigest == GENESIS) {
            // Only a first record may claim no predecessor. A later record that
            // says "genesis" has lost its link, and the chain cannot be verified.
            return LogState.Untrusted(
                LogVerdict.GAP,
                "record ${main.seq} claims to be first",
            )
        }

        return LogState.Valid(main)
    }

    /**
     * Mark [txn] complete: the current record moves to `prev`, freeing the slot.
     *
     * Refuses when the current record is not [txn], so a caller cannot close a
     * transaction it did not open — the failure mode that would let a recovery
     * believe a half-finished operation had completed.
     */
    fun close(txn: String): WriteResult {
        val current = when (val state = read()) {
            is LogState.Valid -> state.record
            is LogState.Empty -> return WriteResult.Failed(
                WriteStage.PATH_REJECTED,
                IllegalStateException("no record to close"),
            )
            is LogState.Untrusted -> return WriteResult.Failed(
                WriteStage.PATH_REJECTED,
                IllegalStateException("log is not trustworthy: ${state.verdict}"),
            )
        }
        if (current.txn != txn) {
            return WriteResult.Failed(
                WriteStage.PATH_REJECTED,
                IllegalStateException(
                    "cannot close '$txn': the current record is '${current.txn}'",
                ),
            )
        }
        val promoted = store.writeText(PREV_NAME, encode(current))
        if (promoted is WriteResult.Failed) return promoted
        // Remove the current record rather than overwriting it with "". An empty
        // file decodes as a malformed record, so writing "" would turn a
        // successfully closed transaction into a log that reads as corrupt on
        // the next launch.
        val mainFile = store.resolveSafe(MAIN_NAME) ?: return WriteResult.Failed(
            WriteStage.PATH_REJECTED, IllegalStateException("bad main name"),
        )
        return if (!mainFile.exists() || mainFile.delete()) WriteResult.Ok
        else WriteResult.Failed(WriteStage.RENAME, IOException("could not clear $MAIN_NAME"))
    }

    // ---- encoding -----------------------------------------------------------

    /**
     * Line-oriented so a human can read a log on a device with no tooling.
     * `body` is last and may contain newlines; it is length-prefixed so the
     * parser cannot be confused by a body that happens to contain `seq=`.
     */
    private fun encode(record: TxnRecord): String = buildString {
        append("seq=").append(record.seq).append('\n')
        append("txn=").append(record.txn).append('\n')
        append("prev=").append(record.prevDigest).append('\n')
        append("digest=").append(record.bodyDigest).append('\n')
        append("bodyLen=").append(record.body.length).append('\n')
        append('\n')
        append(record.body)
    }

    private fun decode(text: String): TxnRecord? {
        val sep = text.indexOf("\n\n")
        if (sep < 0) return null
        val header = text.substring(0, sep)
        val body = text.substring(sep + 2)

        val fields = header.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }
            .toMap()

        val seq = fields["seq"]?.toLongOrNull() ?: return null
        val txn = fields["txn"] ?: return null
        val prev = fields["prev"] ?: return null
        val digest = fields["digest"] ?: return null
        val declaredLen = fields["bodyLen"]?.toIntOrNull() ?: return null
        // A length mismatch means the file was truncated or appended to; either
        // way the body is not the one the header describes.
        if (declaredLen != body.length) return null
        return TxnRecord(seq, txn, prev, digest, body)
    }

    /**
     * Whether a previous append died partway.
     *
     * Presence alone is the signal. Checking length would mean an empty leftover
     * file reads as "clean", which is the one reading that must never be guessed
     * — the file exists because a write was in progress, and how far it got is
     * not knowable from the file's size.
     */
    private fun uncertainMarkerPresent(): Boolean {
        val file = store.resolveSafe(UNCERTAIN_NAME) ?: return false
        return file.exists()
    }

    companion object {
        const val MAIN_NAME = "txn.main"
        const val PREV_NAME = "txn.prev"
        const val UNCERTAIN_NAME = "txn.uncertain"
        const val SEQ_NAME = "log.seq"

        /** Digest of "no predecessor"; distinguishes the first record. */
        const val GENESIS = "genesis"

        /** `kind#opId`, e.g. `setup#7`. Bound to the operation counter on purpose. */
        val TXN_FORMAT = Regex("^[a-z]+#\\d+$")
    }
}
