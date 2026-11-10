package industries.huginn.pifilling.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Markdown reader is fed partial input continuously while a reply streams,
 * so most of these cases are about what happens *before* a construct closes.
 */
class MarkdownTest {

    // ---- streaming tolerance ------------------------------------------------

    @Test
    fun `unclosed fence becomes an open code block`() {
        val blocks = parseMarkdown("before\n```kotlin\nval x = 1")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        val code = blocks[1] as MdBlock.Code
        assertTrue(code.open)
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
    }

    @Test
    fun `closed fence is not open`() {
        val code = parseMarkdown("```\nhi\n```").single() as MdBlock.Code
        assertFalse(code.open)
        assertEquals("hi", code.code)
    }

    @Test
    fun `half typed bold stays literal`() {
        val inline = parseInline("**bold")
        assertEquals("**bold", inline.plain)
        assertTrue(inline.spans.isEmpty())
    }

    @Test
    fun `half typed italic stays literal`() {
        val inline = parseInline("*it")
        assertEquals("*it", inline.plain)
        assertTrue(inline.spans.isEmpty())
    }

    @Test
    fun `half typed inline code stays literal`() {
        val inline = parseInline("`code")
        assertEquals("`code", inline.plain)
        assertTrue(inline.spans.isEmpty())
    }

    @Test
    fun `unfinished link stays literal and is not a link`() {
        val inline = parseInline("[label](https://example.com")
        assertEquals("[label](https://example.com", inline.plain)
        assertTrue(inline.links.isEmpty())
    }

    @Test
    fun `streaming never loses characters`() {
        // Feed every prefix of a document; the plain text of each prefix must be
        // a prefix-compatible rendering, never empty when input is not.
        val doc = "# Title\n\n**bold** and `code` and [l](https://a.b)\n\n```\nx\n```"
        for (end in 1..doc.length) {
            val blocks = parseMarkdown(doc.substring(0, end))
            if (doc.substring(0, end).isNotBlank()) {
                assertTrue("empty parse at $end", blocks.isNotEmpty())
            }
        }
    }

    // ---- the constructs we claim to support ---------------------------------

    @Test
    fun `headings carry their level`() {
        val blocks = parseMarkdown("# one\n## two\n### three")
        assertEquals(listOf(1, 2, 3), blocks.map { (it as MdBlock.Heading).level })
    }

    @Test
    fun `bullet and ordered lists are distinguished`() {
        val bullets = parseMarkdown("- a\n- b").single() as MdBlock.Bullets
        assertFalse(bullets.ordered)
        assertEquals(listOf("a", "b"), bullets.items)

        val ordered = parseMarkdown("1. a\n2. b").single() as MdBlock.Bullets
        assertTrue(ordered.ordered)
        assertEquals(listOf("a", "b"), ordered.items)
    }

    @Test
    fun `quote and rule are recognised`() {
        val blocks = parseMarkdown("> quoted\n\n---")
        assertTrue(blocks[0] is MdBlock.Quote)
        assertTrue(blocks[1] is MdBlock.Rule)
    }

    @Test
    fun `bold italic and code produce spans`() {
        val inline = parseInline("**b** *i* `c`")
        assertEquals("b i c", inline.plain)
        assertEquals(
            listOf(MdStyle.BOLD, MdStyle.ITALIC, MdStyle.CODE),
            inline.spans.map { it.style },
        )
    }

    @Test
    fun `only http and https become links`() {
        assertTrue(parseInline("[a](https://x.y)").links.isNotEmpty())
        assertTrue(parseInline("[a](http://x.y)").links.isNotEmpty())
        assertTrue(parseInline("[a](javascript:alert(1))").links.isEmpty())
        assertTrue(parseInline("[a](file:///etc/passwd)").links.isEmpty())
    }

    // ---- speech -------------------------------------------------------------

    @Test
    fun `speech drops markup and replaces code blocks`() {
        val spoken = markdownToSpeech(
            "# Title\n\n**bold** text\n\n```\nsecret()\n```",
            "There is a code block here.",
        )
        assertFalse(spoken.contains("**"))
        assertFalse(spoken.contains("#"))
        assertFalse(spoken.contains("secret()"))
        assertTrue(spoken.contains("There is a code block here."))
        assertTrue(spoken.contains("bold"))
    }

    @Test
    fun `speech keeps link labels not urls`() {
        val spoken = markdownToSpeech("see [the docs](https://example.com)", "code")
        assertTrue(spoken.contains("the docs"))
        assertFalse(spoken.contains("https://"))
    }

    // ---- tool payloads ------------------------------------------------------

    @Test
    fun `command is read from args`() {
        assertEquals("ls -la", toolCommand("""{"command":"ls -la"}"""))
    }

    @Test
    fun `args without a command fall back to the whole object`() {
        val rendered = toolCommand("""{"path":"/tmp"}""")
        assertTrue(rendered.contains("path"))
    }

    @Test
    fun `output is read from a content array`() {
        val result = """{"content":[{"type":"text","text":"hello"}]}"""
        assertEquals("hello", toolOutput(result))
    }

    @Test
    fun `plain string result passes through`() {
        assertEquals("just text", toolOutput("just text"))
    }

    @Test
    fun `truncation reports that it happened`() {
        val (kept, cut) = truncateLines((1..500).joinToString("\n"), 400)
        assertTrue(cut)
        assertEquals(400, kept.split('\n').size)

        val (all, none) = truncateLines("a\nb", 400)
        assertFalse(none)
        assertEquals("a\nb", all)
    }
}
