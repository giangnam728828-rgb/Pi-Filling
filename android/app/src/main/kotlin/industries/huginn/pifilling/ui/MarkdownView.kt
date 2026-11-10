package industries.huginn.pifilling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import industries.huginn.pifilling.ui.theme.PiTheme

/**
 * One assistant reply, rendered.
 *
 * Gestures are the fiddly part. A long press has to open the message menu
 * anywhere on the row, a tap on a link has to open the browser, and a code block
 * has to scroll sideways without stealing either. Compose gives each region
 * exactly one gesture detector here: the text blocks handle tap (links) and long
 * press, the code blocks handle long press and let their scroll handle drags,
 * and the row handles whatever is left over — the avatar, the gaps between
 * blocks. Only one detector ever sees a given press, so nothing double-fires.
 */
@Composable
fun MarkdownMessage(
    markdown: String,
    onLongPress: () -> Unit,
    onOpenLink: (String) -> Unit,
    onCopyCode: (String) -> Unit,
) {
    // Parsing is cached per message text: recomposition (a theme change, a
    // scroll) must not re-parse, and streaming invalidates it anyway.
    val blocks = remember(markdown) { parseMarkdown(markdown) }

    // Rhythm, not a uniform gap.
    //
    // Every block used to sit 6dp from the next, which made a heading read as
    // just another line and a code block float away from the sentence that
    // introduced it. The rule now: a heading belongs to what follows it, so it
    // gets space above and little below; everything else gets a single measure.
    Column {
        blocks.forEachIndexed { index, block ->
            val isHeading = block is MdBlock.Heading
            val previousIsHeading = index > 0 && blocks[index - 1] is MdBlock.Heading
            val top = when {
                index == 0 -> 0.dp
                isHeading -> PiTheme.spacing.group
                previousIsHeading -> PiTheme.spacing.xs
                else -> PiTheme.spacing.lg
            }
            Column(Modifier.padding(top = top)) {
                when (block) {
                    is MdBlock.Heading -> HeadingBlock(block, onLongPress, onOpenLink)
                    is MdBlock.Paragraph -> InlineBlock(block.text, onLongPress, onOpenLink)
                    is MdBlock.Quote -> QuoteBlock(block, onLongPress, onOpenLink)
                    is MdBlock.Bullets -> BulletsBlock(block, onLongPress, onOpenLink)
                    is MdBlock.Code -> CodeBlockView(block, onLongPress, onCopyCode)
                    MdBlock.Rule -> HorizontalDivider(
                        modifier = Modifier.padding(vertical = PiTheme.spacing.lg),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: MdBlock.Heading, onLongPress: () -> Unit, onOpenLink: (String) -> Unit) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    InlineBlock(
        text = block.text,
        onLongPress = onLongPress,
        onOpenLink = onOpenLink,
        style = style,
        weight = FontWeight.SemiBold,
        topPadding = 4.dp,
    )
}

@Composable
private fun QuoteBlock(block: MdBlock.Quote, onLongPress: () -> Unit, onOpenLink: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // A vertical rule is enough to read as a quotation; a full box would
        // compete with the reply it is quoting.
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    RoundedCornerShape(2.dp),
                ),
        )
        Box(Modifier.weight(1f)) {
            InlineBlock(block.text, onLongPress, onOpenLink, italic = true, dim = true)
        }
    }
}

@Composable
private fun BulletsBlock(block: MdBlock.Bullets, onLongPress: () -> Unit, onOpenLink: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = if (block.ordered) "${index + 1}." else "\u2022",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp),
                )
                Box(Modifier.weight(1f)) {
                    InlineBlock(item, onLongPress, onOpenLink)
                }
            }
        }
    }
}

/** A run of inline text: styled spans, tappable links, long press to the menu. */
@Composable
private fun InlineBlock(
    text: String,
    onLongPress: () -> Unit,
    onOpenLink: (String) -> Unit,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    weight: FontWeight? = null,
    italic: Boolean = false,
    dim: Boolean = false,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val parsed = remember(text) { parseInline(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val codeFg = MaterialTheme.colorScheme.onSurface
    val annotated: AnnotatedString = remember(parsed, linkColor, codeBg, codeFg) {
        buildInline(parsed, linkColor, codeBg, codeFg)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val baseColor = if (dim) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurface

    Text(
        text = annotated,
        style = style,
        fontWeight = weight,
        fontStyle = if (italic) FontStyle.Italic else null,
        color = baseColor,
        lineHeight = 22.sp,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding)
            .pointerInput(parsed) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = { position ->
                        val result = layout ?: return@detectTapGestures
                        val offset = result.getOffsetForPosition(position)
                        parsed.links
                            .firstOrNull { offset >= it.start && offset <= it.end }
                            ?.let { onOpenLink(it.url) }
                    },
                )
            },
    )
}

private fun buildInline(
    parsed: MdText,
    linkColor: Color,
    codeBg: Color,
    codeFg: Color,
): AnnotatedString = buildAnnotatedString {
    append(parsed.plain)
    parsed.spans.forEach { span ->
        val spanStyle = when (span.style) {
            MdStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            MdStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            MdStyle.CODE -> SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = codeBg,
                color = codeFg,
            )
        }
        addStyle(spanStyle, span.start, span.end)
    }
    parsed.links.forEach { link ->
        addStyle(
            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            link.start,
            link.end,
        )
    }
}

/**
 * A fenced code block.
 *
 * Scrolls sideways rather than wrapping: wrapped code is unreadable because the
 * indentation that carries the meaning is destroyed. The block is not syntax
 * highlighted — that needs either a dependency or a tokeniser per language, and
 * neither earns its place in a chat reply.
 */
/** Room reserved on the right so a long line never runs under the copy button. */
private val CodeBlockCopyGutter = 44.dp

/** Tap target for the copy button; at the minimum that still works with a thumb. */
private val CodeBlockCopyButton = 38.dp

@Composable
private fun CodeBlockView(
    block: MdBlock.Code,
    onLongPress: () -> Unit,
    onCopyCode: (String) -> Unit,
) {
    val code = block.code.trimEnd('\n')
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = PiTheme.spacing.xxs)
            .clip(PiTheme.radius.mdShape)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = PiTheme.radius.mdShape,
            )
            // Long press here must still reach the message menu; the sideways
            // scroll below only claims drags, so a stationary press passes down.
            .pointerInput(code) { detectTapGestures(onLongPress = { onLongPress() }) },
    ) {
        // The fence's language was parsed and thrown away. It costs one line and
        // tells the reader what they are looking at before they read it.
        block.language?.takeIf { it.isNotBlank() }?.let { lang ->
            Text(
                lang,
                modifier = Modifier.padding(
                    start = PiTheme.spacing.xl,
                    top = PiTheme.spacing.md,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Box {
            Text(
                text = code,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(
                        start = PiTheme.spacing.xl,
                        end = CodeBlockCopyGutter,
                        top = PiTheme.spacing.lg,
                        bottom = PiTheme.spacing.lg,
                    ),
                style = PiTheme.typography.code,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
            )
            IconButton(
                onClick = { onCopyCode(code) },
                modifier = Modifier.align(Alignment.TopEnd).size(CodeBlockCopyButton),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
