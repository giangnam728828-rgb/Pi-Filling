package industries.huginn.pifilling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import industries.huginn.pifilling.ui.theme.LocalPiFillingDark

/**
 * Liquid glass — the app's single surface material, built on Haze.
 *
 * [AuroraBackground] marks the backdrop with `haze(state)`; every surface that
 * should read as glass calls [Modifier.glass], which is `hazeChild` on the same
 * [HazeState] plus a hairline light border. Because all panes blur the *same
 * shared backdrop*, the material is genuinely unified: cards, the composer, the
 * drawer, dialogs and chips all refract whatever sits behind them.
 *
 * This is the real thing (RenderEffect backdrop blur, API 31+, with a
 * progressive-degradation scrim below), not a painted approximation.
 */

private val GlassShape = RoundedCornerShape(22.dp)

/** Shared blur state. One instance per screen root; every pane reads it. */
val LocalHaze = androidx.compose.runtime.staticCompositionLocalOf { HazeState() }

/**
 * The app-wide backdrop, marked for blur sampling.
 *
 * Light: daylight blue fading through lilac into warm paper (the chat
 * reference). Dark: deep navy into warm charcoal.
 */
@Composable
fun AuroraBackground(content: @Composable BoxScope.() -> Unit) {
    val dark = LocalPiFillingDark.current
    val top = if (dark) Color(0xFF131A2A) else Color(0xFFD9E6F5)
    val mid = if (dark) Color(0xFF171525) else Color(0xFFE7E4EF)
    val bottom = if (dark) Color(0xFF1E1826) else Color(0xFFF6EFE6)
    val hazeState = LocalHaze.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, mid, bottom)))
            .haze(hazeState),
        content = content,
    )
}

/** The haze style for glass panes: blur + tinted translucency. */
@Composable
private fun glassStyle(strength: Float): HazeStyle {
    val dark = LocalPiFillingDark.current
    val s = strength.coerceIn(0.5f, 1f)
    return if (dark) {
        HazeStyle(
            backgroundColor = Color(0xFF171525),
            tints = listOf(HazeTint(Color.White.copy(alpha = 0.10f * s))),
            blurRadius = 24.dp,
        )
    } else {
        HazeStyle(
            backgroundColor = Color(0xFFE7E4EF),
            tints = listOf(HazeTint(Color.White.copy(alpha = 0.62f * s))),
            blurRadius = 24.dp,
        )
    }
}

/**
 * Liquid glass, as a modifier. Blurs the shared backdrop behind the pane and
 * adds the hairline gloss border. [strength] scales the tint opacity.
 */
@Composable
fun Modifier.glass(
    shape: Shape = GlassShape,
    strength: Float = 1f,
    elevation: Dp = 2.dp,
    tint: Color? = null,
    bordered: Boolean = true,
): Modifier {
    val hazeState = LocalHaze.current
    val dark = LocalPiFillingDark.current
    val style = if (tint != null) {
        HazeStyle(
            backgroundColor = tint,
            tints = listOf(HazeTint(tint.copy(alpha = if (dark) 0.80f else 0.90f))),
            blurRadius = 24.dp,
        )
    } else {
        glassStyle(strength)
    }

    return this
        .clip(shape)
        .hazeChild(hazeState, style)
        .then(
            if (bordered) {
                Modifier.border(
                    width = 0.5.dp,
                    color = Color.White.copy(alpha = if (dark) 0.18f else 0.55f),
                    shape = shape,
                )
            } else {
                Modifier
            },
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
        modifier = modifier
            .glass(shape = shape, strength = strength, elevation = elevation, tint = tint)
            .padding(contentPadding),
        content = content,
    )
}

/** Shape for floating panels and dialogs. */
@Composable
fun sheetShape(): CornerBasedShape = RoundedCornerShape(28.dp)

/** Background for dialogs that must stay readable over busy content. */
@Composable
fun sheetContainer(): Color {
    val dark = LocalPiFillingDark.current
    return if (dark) Color(0xFF1D1F28).copy(alpha = 0.94f)
    else Color.White.copy(alpha = 0.94f)
}
