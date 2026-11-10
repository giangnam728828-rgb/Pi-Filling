package industries.huginn.pifilling.ui

/**
 * A very small Markdown reader.
 *
 * Deliberately not a library. The agent's replies use a narrow slice of
 * Markdown, and every library that covers the whole spec is either a View-system
 * dependency that would have to be bridged through AndroidView (with its own
 * font and colour story, doubling the theming work) or large enough to matter in
 * an APK that already ships a rootfs.
 *
 * Two properties matter more than coverage:
 *
 *  - **Streaming tolerance.** Text arrives a few characters at a time, so the
 *    parser is constantly fed input that ends mid-token: an unclosed fence, half
 *    a `**`, a link missing its `)`. None of that may throw or swallow
 *    characters — an unterminated marker simply stays literal until it closes.
 *  - **Theme independence.** Parsing produces spans and ranges, not colours, so
 *    the result can be cached per message and painted with whatever theme is
 *    active.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock

    data class Paragraph(val text: String) : MdBlock

    /** [open] is true while the closing fence has not arrived yet. */
    data class Code(val code: String, val language: String?, val open: Boolean) : MdBlock

    data class Bullets(val ordered: Boolean, val items: List<String>) : MdBlock

    data class Quote(val text: String) : MdBlock

    data object Rule : MdBlock
}

enum class MdStyle { BOLD, ITALIC, CODE }

/** A styled range inside [MdText.plain]. Offsets are into the plain string. */
data class MdSpan(val start: Int, val end: Int, val style: MdStyle)

/** A link range. Only http/https URLs are ever recorded. */
data class MdLink(val start: Int, val end: Int, val url: String)

/**
 * Inline content flattened to plain text plus spans.
 *
 * Flattening is what makes half-typed markers harmless: `**bold` becomes the
 * literal characters `**bold` rather than an unterminated style.
 */
data class MdText(
    val plain: String,
    val spans: List<MdSpan> = emptyList(),
    val links: List<MdLink> = emptyList(),
)

private val FENCE = Regex("""^\s{0,3}(`{3,}|~{3,})\s*([^\s`]*)""")
private val HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.*)$""")
private val RULE = Regex("""^\s{0,3}([-*_])\s*(?:\1\s*){2,}$""")
private val BULLET = Regex("""^\s{0,3}[-*+]\s+(.*)$""")
private val ORDERED = Regex("""^\s{0,3}\d{1,9}[.)]\s+(.*)$""")
private val QUOTE = Regex("""^\s{0,3}>\s?(.*)$""")

/** True when [line] starts any construct that interrupts a paragraph. */
private fun startsBlock(line: String): Boolean =
    FENCE.containsMatchIn(line) || HEADING.matches(line) || RULE.matches(line) ||
        BULLET.matches(line) || ORDERED.matches(line) || QUOTE.matches(line)

fun parseMarkdown(source: String): List<MdBlock> {
    if (source.isBlank()) return emptyList()
    val lines = source.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        if (line.isBlank()) {
            i++
            continue
        }

        // ---- fenced code ----
        val fence = FENCE.find(line)
        if (fence != null) {
            val marker = fence.groupValues[1]
            val language = fence.groupValues[2].takeIf { it.isNotBlank() }
            val body = mutableListOf<String>()
            var closed = false
            i++
            while (i < lines.size) {
                val candidate = lines[i].trim()
                if (candidate.startsWith(marker.take(3)) &&
                    candidate.all { it == marker[0] } && candidate.length >= marker.length
                ) {
                    closed = true
                    i++
                    break
                }
                body += lines[i]
                i++
            }
            blocks += MdBlock.Code(body.joinToString("\n"), language, open = !closed)
            continue
        }

        // ---- heading ----
        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            i++
            continue
        }

        // ---- horizontal rule ----
        if (RULE.matches(line)) {
            blocks += MdBlock.Rule
            i++
            continue
        }

        // ---- blockquote ----
        val firstQuote = QUOTE.matchEntire(line)
        if (firstQuote != null) {
            val quoted = mutableListOf(firstQuote.groupValues[1])
            i++
            while (i < lines.size) {
                val quotedLine = QUOTE.matchEntire(lines[i]) ?: break
                quoted += quotedLine.groupValues[1]
                i++
            }
            blocks += MdBlock.Quote(quoted.joinToString("\n"))
            continue
        }

        // ---- lists ----
        val bullet = BULLET.matchEntire(line)
        val ordered = ORDERED.matchEntire(line)
        if (bullet != null || ordered != null) {
            val isOrdered = ordered != null
            val items = mutableListOf<String>()
            while (i < lines.size) {
                val b = BULLET.matchEntire(lines[i])
                val o = ORDERED.matchEntire(lines[i])
                val text = when {
                    isOrdered && o != null -> o.groupValues[1]
                    !isOrdered && b != null -> b.groupValues[1]
                    else -> break
                }
                items += text
                i++
            }
            blocks += MdBlock.Bullets(isOrdered, items)
            continue
        }

        // ---- paragraph: run until a blank line or another block ----
        val paragraph = mutableListOf(line.trim())
        i++
        while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines[i])) {
            paragraph += lines[i].trim()
            i++
        }
        blocks += MdBlock.Paragraph(paragraph.joinToString("\n"))
    }
    return blocks
}

/**
 * Parse inline markup into plain text plus ranges.
 *
 * Every unterminated marker degrades to its literal characters, which is what
 * keeps a streaming reply readable instead of flickering between styled and
 * raw as the closing marker arrives.
 */
fun parseInline(raw: String): MdText {
    val out = StringBuilder(raw.length)
    val spans = mutableListOf<MdSpan>()
    val links = mutableListOf<MdLink>()
    var i = 0

    fun emitStyle(style: MdStyle, start: Int) {
        if (out.length > start) spans += MdSpan(start, out.length, style)
    }

    while (i < raw.length) {
        val c = raw[i]
        when {
            // Escaped character: drop the backslash, keep the character.
            c == '\\' && i + 1 < raw.length -> {
                out.append(raw[i + 1])
                i += 2
            }

            c == '*' && i + 1 < raw.length && raw[i + 1] == '*' -> {
                val close = raw.indexOf("**", i + 2)
                if (close > i + 2) {
                    val start = out.length
                    out.append(raw, i + 2, close)
                    emitStyle(MdStyle.BOLD, start)
                    i = close + 2
                } else {
                    // Unclosed: keep the two asterisks visible.
                    out.append("**")
                    i += 2
                }
            }

            c == '*' -> {
                val close = raw.indexOf('*', i + 1)
                if (close > i + 1) {
                    val start = out.length
                    out.append(raw, i + 1, close)
                    emitStyle(MdStyle.ITALIC, start)
                    i = close + 1
                } else {
                    out.append('*')
                    i++
                }
            }

            c == '`' -> {
                val close = raw.indexOf('`', i + 1)
                if (close > i + 1) {
                    val start = out.length
                    out.append(raw, i + 1, close)
                    emitStyle(MdStyle.CODE, start)
                    i = close + 1
                } else {
                    out.append('`')
                    i++
                }
            }

            c == '[' -> {
                val closeBracket = raw.indexOf(']', i + 1)
                val openParen = if (closeBracket > 0) raw.indexOf('(', closeBracket) else -1
                val closeParen = if (openParen == closeBracket + 1) raw.indexOf(')', openParen) else -1
                val url = if (closeParen > 0) raw.substring(openParen + 1, closeParen) else null
                if (closeBracket > i + 1 && url != null && isHttpUrl(url)) {
                    val label = raw.substring(i + 1, closeBracket)
                    val start = out.length
                    out.append(label)
                    links += MdLink(start, out.length, url)
                    i = closeParen + 1
                } else {
                    // Not a usable link (yet): leave the bracket as typed.
                    out.append('[')
                    i++
                }
            }

            else -> {
                out.append(c)
                i++
            }
        }
    }
    return MdText(out.toString(), spans, links)
}

/** Only real web URLs become links; anything else stays literal text. */
fun isHttpUrl(url: String): Boolean {
    val lower = url.trim().lowercase()
    return (lower.startsWith("http://") || lower.startsWith("https://")) &&
        lower.length > "https://".length
}

/**
 * The text to speak for a reply.
 *
 * Markdown punctuation read aloud is noise ("asterisk asterisk bold asterisk
 * asterisk"), and a code block read character by character is worse than
 * silence. Blocks are replaced by one spoken sentence supplied by the caller,
 * because the wording is a translated resource and this file has no Context.
 */
fun markdownToSpeech(source: String, codeBlockHint: String): String {
    val lines = source.replace("\r\n", "\n").split('\n')
    val spoken = StringBuilder()
    var inFence = false
    var fenceAnnounced = false

    for (line in lines) {
        if (FENCE.containsMatchIn(line)) {
            if (!inFence) {
                inFence = true
                if (!fenceAnnounced) {
                    spoken.append('\n').append(codeBlockHint).append('\n')
                    fenceAnnounced = true
                }
            } else {
                inFence = false
            }
            continue
        }
        if (inFence) continue

        var text = line.trim()
        if (text.isEmpty()) {
            spoken.append('\n')
            continue
        }
        text = text
            .replace(HEADING, "$2")
            .replace(QUOTE, "$1")
            .replace(BULLET, "$1")
            .replace(ORDERED, "$1")
            .replace(RULE, "")
        // Inline markers are dropped but their content kept.
        text = text
            .replace(Regex("""!?\[([^\]]*)]\((?:[^)]*)\)"""), "$1")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replace("*", "")
        spoken.append(text).append('\n')
    }
    return spoken.toString()
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}
