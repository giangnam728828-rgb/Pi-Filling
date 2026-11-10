package industries.huginn.pifilling.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** One saved conversation, as the drawer sees it. */
data class StoredSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val items: List<ChatItem>,
)

/**
 * Conversations on disk.
 *
 * Everything here is best-effort by design: a chat log is not the source of
 * truth for anything, so a half-written or hand-edited file must never stop the
 * app from starting. Unreadable data is moved aside rather than deleted, because
 * silently discarding the only copy of a conversation is worse than keeping a
 * file nobody can read.
 *
 * IO is plain `File` work — callers must invoke it off the main thread.
 */
class ChatHistoryStore(
    private val dir: File,
    /** Injected so the tests can pin behaviour without a clock. */
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val file = File(dir, FILE_NAME)
    private val tempFile = File(dir, "$FILE_NAME.tmp")
    private val backupFile = File(dir, "$FILE_NAME.bak")

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; isLenient = true }

    /**
     * Which conversation is open, so reopening the app returns to it.
     *
     * Kept in its own small file rather than in the session list: the open
     * conversation is state about *this* device's current position, not part of
     * any conversation's content, and losing it must not risk the transcripts.
     *
     * Written synchronously — it is a few bytes, and the moment it matters is
     * exactly the moment the process is about to die.
     */
    /**
     * Which conversation to reopen, and whether it was live or read-only.
     *
     * The mode matters: a live conversation comes back editable, an archived one
     * comes back read-only. Restoring both as editable would let the agent
     * continue a conversation the user had already finished.
     */
    data class OpenSession(val id: String, val viewing: Boolean)

    fun readOpenSession(): OpenSession? {
        val f = File(dir, OPEN_FILE)
        if (!f.exists()) return null
        val text = runCatching { f.readText().trim() }.getOrNull() ?: return null
        if (text.isBlank()) return null
        // Format: "<mode>\n<id>". A file written by the previous version holds a
        // bare id; treat that as live, which is what it meant then.
        val lines = text.lines()
        return if (lines.size >= 2) {
            OpenSession(id = lines[1].trim(), viewing = lines[0].trim() == MODE_VIEWING)
        } else {
            OpenSession(id = lines[0].trim(), viewing = false)
        }
    }

    fun writeOpenSession(id: String, viewing: Boolean) {
        runCatching {
            val f = File(dir, OPEN_FILE)
            val tmp = File(dir, "$OPEN_FILE.tmp")
            f.parentFile?.mkdirs()
            val mode = if (viewing) MODE_VIEWING else MODE_LIVE
            tmp.writeText("$mode\n$id")
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    /**
     * Read every stored conversation, newest first.
     *
     * A file from a newer version is left alone and reported as empty: guessing
     * at a format this build does not know is how data gets destroyed.
     */
    fun load(): List<StoredSession> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        if (text.isBlank()) return emptyList()

        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return quarantine()

        val version = (root["version"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (version == null || version > CURRENT_VERSION) return quarantine()

        val sessions = (root["sessions"] as? JsonArray)?.mapNotNull { element ->
            runCatching { element.jsonObject.toSession() }.getOrNull()
        } ?: return quarantine()

        // A conversation with no user message is not worth keeping: it is a
        // chat the user opened and never used. The "blank new chat" state does
        // not depend on storing one — `open-session.id` may point at an id that
        // is not here, and that absence *is* the signal to show an empty
        // conversation.
        return sessions
            .filter { it.items.any { item -> item is ChatItem.MessageItem && item.role == "you" } }
            .sortedByDescending { it.updatedAt }
            .let { repaired(it) }
            .take(MAX_SESSIONS)
    }

    /** Write every conversation, atomically. */
    /**
     * Serialises every write.
     *
     * Two `store.save` calls used to run concurrently from separate
     * `viewModelScope.launch` blocks, sharing one temp file. Whichever renamed
     * second could publish a half-written payload from the other, and `load`
     * would then quarantine the file — the whole history gone because two saves
     * overlapped. A lock is the smallest thing that makes that impossible.
     */
    // Deliberately not a per-instance field: `ChatHistoryStore` is created in
    // several places (the ViewModel, tests, diagnostics), and two instances
    // writing the same directory would otherwise hold different locks and race
    // over the same temp file — exactly the failure this lock exists to prevent.
    private val writeLock: Any get() = FILE_LOCK

    fun save(sessions: List<StoredSession>) = synchronized(writeLock) {
        runCatching {
            dir.mkdirs()
            val payload = buildJsonObject {
                put("version", CURRENT_VERSION)
                put(
                    "sessions",
                    // Newest first before trimming: taking the caller's order
                    // would evict the conversations the user just had.
                    JsonArray(
                        sessions.sortedByDescending { it.updatedAt }
                            .take(MAX_SESSIONS)
                            .map { it.toJson() },
                    ),
                )
            }
            // Temp file then rename: a kill mid-write leaves the previous
            // history intact instead of a truncated one.
            tempFile.writeText(payload.toString())
            if (file.exists() && !file.delete()) {
                // Fall through to the rename attempt; if it fails the old file
                // is still readable, which is the outcome that matters.
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
                return@runCatching
            }
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        }
    }

    /**
     * Write and wait for the bytes to land.
     *
     * `save` schedules work and returns; at the moment the process is about to
     * die that is the wrong shape. This one blocks until the file is in place, so
     * a caller in `onStop` can be sure the conversation exists on disk before it
     * lets the app go.
     */
    fun saveBlocking(sessions: List<StoredSession>) = synchronized(writeLock) {
        saveLocked(sessions)
    }

    private fun saveLocked(sessions: List<StoredSession>) {
        runCatching {
            dir.mkdirs()
            val payload = buildJsonObject {
                put("version", CURRENT_VERSION)
                put(
                    "sessions",
                    JsonArray(
                        sessions.sortedByDescending { it.updatedAt }
                            .take(MAX_SESSIONS)
                            .map { it.toJson() },
                    ),
                )
            }
            tempFile.writeText(payload.toString())
            if (file.exists() && !file.delete()) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
                return@runCatching
            }
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        }
    }

    /** Delete everything, including a quarantined file. */
    fun clear() {
        runCatching { file.delete() }
        runCatching { tempFile.delete() }
        runCatching { backupFile.delete() }
    }

    /**
     * Move the unreadable file aside and start fresh.
     *
     * Kept rather than deleted: it is the user's only copy, and a later build
     * (or a person) may still recover it.
     */
    private fun quarantine(): List<StoredSession> {
        runCatching {
            if (backupFile.exists()) backupFile.delete()
            file.renameTo(backupFile)
        }
        return emptyList()
    }

    private fun repaired(sessions: List<StoredSession>): List<StoredSession> =
        sessions.map { session ->
            session.copy(
                items = session.items.map { item ->
                    // A tool that was running when the process died will never
                    // finish. Showing it as running forever is a lie.
                    if (item is ChatItem.ToolItem && item.status == ToolStatus.RUNNING) {
                        item.copy(status = ToolStatus.INTERRUPTED)
                    } else {
                        item
                    }
                },
            )
        }

    private fun StoredSession.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        put("updatedAt", updatedAt)
        put(
            "items",
            JsonArray(
                items.map { item ->
                    when (item) {
                        is ChatItem.MessageItem -> buildJsonObject {
                            put("type", "message")
                            put("id", item.id)
                            put("role", item.role)
                            put("text", item.text)
                        }
                        is ChatItem.ToolItem -> buildJsonObject {
                            put("type", "tool")
                            put("id", item.id)
                            put("toolCallId", item.toolCallId)
                            put("name", item.name)
                            put("status", item.status.name)
                            put("args", item.args)
                            // Same cap the UI shows: the transcript is not an
                            // archive, and an `ls -R` would dominate the file.
                            put("result", truncateLines(item.result, KEPT_RESULT_LINES).first)
                        }
                    }
                },
            ),
        )
    }

    private fun JsonObject.toSession(): StoredSession? {
        val id = (this["id"] as? JsonPrimitive)?.content ?: return null
        val updatedAt = runCatching { (this["updatedAt"] as? JsonPrimitive)?.long }.getOrNull()
            ?: return null
        val items = (this["items"] as? JsonArray)?.mapNotNull { it.toItem() } ?: return null
        if (items.isEmpty()) return null
        return StoredSession(
            id = id,
            title = (this["title"] as? JsonPrimitive)?.content.orEmpty(),
            updatedAt = updatedAt,
            items = items,
        )
    }

    private fun kotlinx.serialization.json.JsonElement.toItem(): ChatItem? = runCatching {
        val obj = jsonObject
        when ((obj["type"] as? JsonPrimitive)?.content) {
            "message" -> ChatItem.MessageItem(
                id = obj["id"]!!.jsonPrimitive.content,
                role = obj["role"]!!.jsonPrimitive.content,
                text = obj["text"]!!.jsonPrimitive.content,
            )
            "tool" -> ChatItem.ToolItem(
                id = obj["id"]!!.jsonPrimitive.content,
                toolCallId = (obj["toolCallId"] as? JsonPrimitive)?.content.orEmpty(),
                name = obj["name"]!!.jsonPrimitive.content,
                status = ToolStatus.entries
                    .firstOrNull { it.name == (obj["status"] as? JsonPrimitive)?.content }
                    ?: ToolStatus.SUCCESS,
                args = (obj["args"] as? JsonPrimitive)?.content.orEmpty(),
                result = (obj["result"] as? JsonPrimitive)?.content.orEmpty(),
            )
            else -> null
        }
    }.getOrNull()

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_SESSIONS = 30

        /** Result lines kept on disk; the UI shows the same number. */
        const val KEPT_RESULT_LINES = 400

        const val FILE_NAME = "chat-history.json"

        /** Small file holding the open conversation's id and mode. */
        const val OPEN_FILE = "open-session.id"
        /** Guards every write to any history file. See [writeLock]. */
        private val FILE_LOCK = Any()

        const val MODE_LIVE = "live"
        const val MODE_VIEWING = "viewing"

        fun newSessionId(): String = UUID.randomUUID().toString()

        /** The first line of the first thing the user said, for the drawer. */
        fun titleOf(items: List<ChatItem>): String {
            val text = items.asSequence()
                .filterIsInstance<ChatItem.MessageItem>()
                .firstOrNull { it.role == "you" }
                ?.text
                ?.replace('\n', ' ')
                ?.trim()
                .orEmpty()
            return text.take(60).ifBlank { "" }
        }

        /**
         * Cap a session's size by dropping the oldest tool output first.
         *
         * Tool payloads are what makes a conversation file large, and the oldest
         * ones are the least interesting; messages are never dropped.
         */
        fun capSize(items: List<ChatItem>, maxChars: Int = 200_000): List<ChatItem> {
            var total = items.sumOf { it.approxSize() }
            if (total <= maxChars) return items

            val updated = items.toMutableList()
            for (index in updated.indices) {
                if (total <= maxChars) break
                val item = updated[index]
                if (item is ChatItem.ToolItem && item.result.isNotEmpty()) {
                    total -= item.result.length
                    updated[index] = item.copy(result = "")
                }
            }
            return updated
        }

        private fun ChatItem.approxSize(): Int = when (this) {
            is ChatItem.MessageItem -> text.length
            is ChatItem.ToolItem -> args.length + result.length + name.length
        }
    }
}
