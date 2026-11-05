package industries.huginn.pifilling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CornerBasedShape

/**
 * The app's "frosted glass" surface treatment.
 *
 * Why a hand-rolled look instead of a blur library: a real backdrop blur needs
 * either a third-party dependency (extra download size, extra breakage on cheap
 * GPUs) or API 31 RenderEffect (crashes or no-ops below). What actually reads as
 * glass on a phone screen is a translucent light fill over a colourful
 * background, a hairline highlight border, and a soft shadow — all of which
 * Compose does natively and cheaply. The colourful part is
 * [AuroraBackground]; these modifiers are the panes on top of it.
 */

private val GlassShape = RoundedCornerShape(22.dp)

/**
 * Paint a full-screen, softly coloured backdrop.
 *
 * Without something behind them, translucent panes look like a flat grey app
 * with muted text. Three large, low-alpha radial washes give every pane
 * something to sit on and make the whole screen feel lit rather than filled —
 * this is the single change that turns "Material defaults" into "designed".
 */
@Composable
fun AuroraBackground(content: @Composable BoxScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val top = if (dark) Color(0xFF0A0D14) else scheme.background
    val bottom = if (dark) Color(0xFF11151F) else Color(0xFFE7ECFA)
    val primary = scheme.primary.copy(alpha = if (dark) 0.34f else 0.24f)
    val secondary = scheme.secondary.copy(alpha = if (dark) 0.26f else 0.20f)
    val accent = if (dark) Color(0xFF6C5CE7).copy(alpha = 0.22f) else Color(0xFF8E7CFF).copy(alpha = 0.16f)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .drawBehind {
                // Radii are fractions of the *width* so the blooms keep their
                // shape on both a 360dp phone and a tablet.
                val w = size.width
                fun bloom(color: Color, cx: Float, cy: Float, r: Float) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(color, Color.Transparent),
                            center = Offset(w * cx, size.height * cy),
                            radius = w * r,
                        ),
                        radius = w * r,
                        center = Offset(w * cx, size.height * cy),
                    )
                }
                bloom(primary, 0.08f, 0.04f, 0.95f)
                bloom(secondary, 0.98f, 0.16f, 0.80f)
                bloom(accent, 0.70f, 0.96f, 0.90f)
            },
        content = content,
    )
}

/**
 * Glass fill + hairline highlight + soft shadow, as a modifier.
 *
 * [strength] scales the fill's opacity: 1f for cards that must stay readable
 * over a bright bloom, lower for chips that only need a hint of body.
 */
@Composable
fun Modifier.glass(
    shape: Shape = GlassShape,
    strength: Float = 1f,
    elevation: Dp = 2.dp,
    tint: Color? = null,
): Modifier {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val fillTop: Color
    val fillBottom: Color
    val stroke: Color
    if (tint != null) {
        // Tinted panes (the user's own message) keep their colour identity and
        // still look lit: a bright top edge over a darker bottom one.
        fillTop = tint.copy(alpha = if (dark) 0.86f else 0.94f)
        fillBottom = tint.copy(alpha = if (dark) 0.70f else 0.80f)
        stroke = Color.White.copy(alpha = if (dark) 0.16f else 0.5f)
    } else if (dark) {
        fillTop = scheme.surface.copy(alpha = 0.78f * strength)
        fillBottom = scheme.surface.copy(alpha = 0.52f * strength)
        stroke = Color.White.copy(alpha = 0.12f)
    } else {
        fillTop = Color.White.copy(alpha = 0.86f * strength)
        fillBottom = Color.White.copy(alpha = 0.56f * strength)
        stroke = Color.White.copy(alpha = 0.9f)
    }
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.16f),
            spotColor = Color.Black.copy(alpha = 0.22f),
        )
        .background(Brush.verticalGradient(listOf(fillTop, fillBottom)), shape)
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                listOf(stroke, stroke.copy(alpha = stroke.alpha * 0.35f)),
            ),
            shape = shape,
        )
}

/** Convenience wrapper: a glass pane with padding, for one-off boxes. */
@Composable
fun GlassPane(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShape,
    strength: Float = 1f,
    elevation: Dp = 2.dp,
    tint: Color? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.glass(shape = shape, strength = strength, elevation = elevation, tint = tint)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * Shape for the bottom sheets.
 *
 * `CornerBasedShape` is spelled out rather than inferred so call sites can reuse
 * the exact same radius the panel below the drag handle uses.
 */
@Composable
fun sheetShape(): CornerBasedShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** Background for sheets: mostly opaque, so text never fights the blooms. */
@Composable
fun sheetContainer(): Color {
    val dark = isSystemInDarkTheme()
    return if (dark) MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
    else Color.White.copy(alpha = 0.97f)
}
