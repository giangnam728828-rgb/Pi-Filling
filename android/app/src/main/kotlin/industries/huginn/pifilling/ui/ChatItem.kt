package industries.huginn.pifilling.ui

/**
 * One row in the conversation.
 *
 * Replaces the old `TranscriptLine(role, text)`, which could only express
 * "somebody said something". A tool call is not a message: it starts, finishes,
 * can fail, and carries the command and its output. Modelling it as a message
 * with a decorative prefix meant the UI could never do anything with it.
 *
 * Every field is a plain value type (String / enum) on purpose: step 5 writes
 * these straight to disk, so anything clever here would have to be undone there.
 */
sealed interface ChatItem {
    /**
     * Stable for the life of the item.
     *
     * The list keys on it, and step 5 persists it. Recomposition, a reconnect,
     * or a regenerate must never renumber an existing row — a changed key makes
     * LazyColumn treat the row as a different one, which loses its scroll
     * anchor and replays its entry animation.
     */
    val id: String

    /** Something a participant said. [role] is "you", "assistant" or "system". */
    data class MessageItem(
        override val id: String,
        val role: String,
        val text: String,
    ) : ChatItem

    /**
     * A tool the agent ran.
     *
     * [toolCallId] is the agent's own correlation id, used to match the end
     * event to the start event; [id] is ours and stays fixed.
     */
    data class ToolItem(
        override val id: String,
        val toolCallId: String,
        val name: String,
        val status: ToolStatus,
        val args: String = "",
        val result: String = "",
    ) : ChatItem
}

/** Where a tool call got to. */
enum class ToolStatus {
    RUNNING,
    SUCCESS,
    FAILED,

    /**
     * The process died while this ran.
     *
     * Restored from disk as this rather than RUNNING: nothing will ever finish
     * it, and a spinner that turns forever is indistinguishable from a hang.
     */
    INTERRUPTED,
}

/** The text of a message, or null for a tool row (which has none). */
val ChatItem.messageText: String?
    get() = (this as? ChatItem.MessageItem)?.text
