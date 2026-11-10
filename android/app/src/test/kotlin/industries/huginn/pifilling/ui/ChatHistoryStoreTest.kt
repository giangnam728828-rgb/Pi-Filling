package industries.huginn.pifilling.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The history file is the only thing standing between a conversation and
 * nothing, so its failure modes matter more than its happy path: a newer
 * version, a truncated write, a process that died mid-tool.
 */
class ChatHistoryStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(dir: File = temp.root, clock: () -> Long = { 1_000L }) =
        ChatHistoryStore(dir, clock)

    private fun userMessage(id: String, text: String) =
        ChatItem.MessageItem(id = id, role = "you", text = text)

    private fun assistantMessage(id: String, text: String) =
        ChatItem.MessageItem(id = id, role = "assistant", text = text)

    private fun toolItem(id: String, status: ToolStatus, result: String = "out") =
        ChatItem.ToolItem(
            id = id,
            toolCallId = "call-$id",
            name = "bash",
            status = status,
            args = """{"command":"ls"}""",
            result = result,
        )

    private fun session(id: String, at: Long, items: List<ChatItem>) =
        StoredSession(id = id, title = ChatHistoryStore.titleOf(items), updatedAt = at, items = items)

    // ---- round trip ---------------------------------------------------------

    // ---- open session pointer (0.20.35) ------------------------------------

    @Test
    fun `the open session records its mode`() {
        val s = store()
        s.writeOpenSession("abc", viewing = false)
        val live = s.readOpenSession()!!
        assertEquals("abc", live.id)
        assertEquals(false, live.viewing)

        s.writeOpenSession("xyz", viewing = true)
        val viewing = s.readOpenSession()!!
        assertEquals("xyz", viewing.id)
        assertEquals(true, viewing.viewing)
    }

    @Test
    fun `a bare id from an older build reads as live`() {
        // The previous format held just the id. Treating it as live is what it
        // meant at the time, and misreading it as viewing would open a
        // conversation read-only.
        File(temp.root, ChatHistoryStore.OPEN_FILE).writeText("legacy-id")
        val open = store().readOpenSession()!!
        assertEquals("legacy-id", open.id)
        assertEquals(false, open.viewing)
    }

    @Test
    fun `concurrent saves all leave a parsable file`() {
        // Two save paths used to share one temp file. Whichever renamed second
        // could publish the other's half-written payload, and load() would then
        // quarantine the file — the whole history gone.
        val threads = (1..100).map { i ->
            Thread {
                store().save(
                    listOf(
                        StoredSession(
                            id = "s$i",
                            title = "t$i",
                            updatedAt = i.toLong(),
                            items = listOf(userMessage("m$i", "hello")),
                        ),
                    ),
                )
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertFalse(
            "a concurrent write corrupted the history",
            File(temp.root, "${ChatHistoryStore.FILE_NAME}.bak").exists(),
        )
        assertTrue(store().load().isNotEmpty())
    }

    @Test
    fun `saveBlocking writes before returning`() {
        store().saveBlocking(
            listOf(
                StoredSession(
                    id = "a",
                    title = "A",
                    updatedAt = 1L,
                    items = listOf(userMessage("m", "hello")),
                ),
            ),
        )
        // No sleeping, no polling: if the call returned, the bytes are there.
        val loaded = store().load()
        assertEquals(1, loaded.size)
        assertEquals("a", loaded.first().id)
    }

    @Test
    fun `round trip keeps messages and tools`() {
        val original = listOf(
            session("s1", 10L, listOf(userMessage("a", "hello"), assistantMessage("b", "hi there"))),
            session("s2", 20L, listOf(userMessage("c", "run it"), toolItem("d", ToolStatus.SUCCESS))),
        )
        store().save(original)
        val loaded = store().load()

        assertEquals(2, loaded.size)
        // Newest first.
        assertEquals("s2", loaded[0].id)
        val tools = loaded[0].items.filterIsInstance<ChatItem.ToolItem>()
        assertEquals(1, tools.size)
        assertEquals("bash", tools[0].name)
        assertEquals("call-d", tools[0].toolCallId)
        assertEquals(ToolStatus.SUCCESS, tools[0].status)
        assertEquals("hello", (loaded[1].items[0] as ChatItem.MessageItem).text)
    }

    @Test
    fun `ids survive a round trip unchanged`() {
        store().save(listOf(session("s1", 1L, listOf(userMessage("fixed-id", "x")))))
        assertEquals("fixed-id", (store().load().single().items.single() as ChatItem.MessageItem).id)
    }

    // ---- the failures -------------------------------------------------------

    @Test
    fun `unknown version is not guessed at and is kept as a backup`() {
        val file = File(temp.root, ChatHistoryStore.FILE_NAME)
        file.writeText("""{"version":999,"sessions":[{"id":"x"}]}""")

        assertTrue(store().load().isEmpty())
        assertFalse("original file must be moved aside, not deleted", file.exists())
        assertTrue(File(temp.root, "${ChatHistoryStore.FILE_NAME}.bak").exists())
    }

    @Test
    fun `corrupt json degrades to empty and keeps a backup`() {
        val file = File(temp.root, ChatHistoryStore.FILE_NAME)
        file.writeText("""{"version":1,"sessions":[{"id":"x\"""")

        assertTrue(store().load().isEmpty())
        assertTrue(File(temp.root, "${ChatHistoryStore.FILE_NAME}.bak").exists())
    }

    @Test
    fun `missing file is simply empty`() {
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `running tool becomes interrupted on load`() {
        store().save(listOf(session("s1", 1L, listOf(userMessage("a", "go"), toolItem("b", ToolStatus.RUNNING)))))
        val loaded = store().load().single()
        val tool = loaded.items.filterIsInstance<ChatItem.ToolItem>().single()
        assertEquals(ToolStatus.INTERRUPTED, tool.status)
    }

    @Test
    fun `finished tools keep their status`() {
        store().save(
            listOf(
                session(
                    "s1", 1L,
                    listOf(
                        userMessage("a", "go"),
                        toolItem("b", ToolStatus.SUCCESS),
                        toolItem("c", ToolStatus.FAILED),
                    ),
                ),
            ),
        )
        val statuses = store().load().single().items
            .filterIsInstance<ChatItem.ToolItem>().map { it.status }
        assertEquals(listOf(ToolStatus.SUCCESS, ToolStatus.FAILED), statuses)
    }

    // ---- capacity -----------------------------------------------------------

    @Test
    fun `more than thirty sessions keeps the newest thirty`() {
        val many = (1..35).map { session("s$it", it.toLong(), listOf(userMessage("u$it", "msg $it"))) }
        store().save(many)
        val loaded = store().load()

        assertEquals(ChatHistoryStore.MAX_SESSIONS, loaded.size)
        assertEquals("s35", loaded.first().id)
        assertTrue(loaded.none { it.id == "s1" })
    }

    @Test
    fun `a session with no user message is not saved`() {
        store().save(
            listOf(
                session("empty", 1L, listOf(assistantMessage("a", "hi"))),
                session("real", 2L, listOf(userMessage("b", "hello"))),
            ),
        )
        val loaded = store().load()
        assertEquals(1, loaded.size)
        assertEquals("real", loaded[0].id)
    }

    @Test
    fun `tool output is truncated on disk`() {
        val huge = (1..900).joinToString("\n") { "line $it" }
        store().save(listOf(session("s1", 1L, listOf(userMessage("a", "go"), toolItem("b", ToolStatus.SUCCESS, huge)))))
        val tool = store().load().single().items.filterIsInstance<ChatItem.ToolItem>().single()
        assertTrue(
            "should keep at most ${ChatHistoryStore.KEPT_RESULT_LINES} lines, got ${tool.result.lines().size}",
            tool.result.lines().size <= ChatHistoryStore.KEPT_RESULT_LINES,
        )
    }

    @Test
    fun `oversized session drops the oldest tool output first`() {
        val big = "x".repeat(50_000)
        val items = listOf(
            userMessage("a", "first"),
            toolItem("t1", ToolStatus.SUCCESS, big),
            toolItem("t2", ToolStatus.SUCCESS, big),
            toolItem("t3", ToolStatus.SUCCESS, big),
            assistantMessage("z", "final answer"),
        )
        val capped = ChatHistoryStore.capSize(items, maxChars = 120_000)
        val tools = capped.filterIsInstance<ChatItem.ToolItem>()

        // Oldest outputs go first, messages are never dropped.
        assertEquals(5, capped.size)
        assertTrue(tools[0].result.isEmpty())
        assertTrue("the newest output should survive", tools[2].result.isNotEmpty())
    }

    // ---- ids -----------------------------------------------------------------

    @Test
    fun `new messages after a reload never reuse a stored id`() {
        val existing = listOf(
            userMessage("i0", "old one"),
            assistantMessage("i1", "old reply"),
            toolItem("i2", ToolStatus.SUCCESS),
        )
        store().save(listOf(session("s1", 1L, existing)))
        val restored = store().load().single()

        val fresh = ChatHistoryStore.newSessionId()
        val freshIds = (1..50).map { java.util.UUID.randomUUID().toString() }
        val storedIds = restored.items.map { it.id }.toSet()

        assertTrue(fresh.isNotBlank())
        assertTrue(freshIds.none { it in storedIds })
        // And the two id spaces are shaped the same way: UUIDs, not counters.
        assertNotEquals("i0", ChatHistoryStore.newSessionId())
    }

    @Test
    fun `clear removes the file`() {
        store().save(listOf(session("s1", 1L, listOf(userMessage("a", "x")))))
        store().clear()
        assertFalse(File(temp.root, ChatHistoryStore.FILE_NAME).exists())
        assertTrue(store().load().isEmpty())
    }
}
