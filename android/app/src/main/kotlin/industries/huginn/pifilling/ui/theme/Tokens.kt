package industries.huginn.pifilling.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The spacing scale.
 *
 * These values were already in the code — `22.dp` appears 46 times in
 * `PiFillingApp.kt`, `10.dp` another 46 — but as literals, so nothing stopped a
 * new screen from inventing `23.dp`. Naming them turns an implicit convention
 * into something a reviewer can check.
 *
 * A 2dp base rather than 4dp: the existing screens use 6, 10 and 22 heavily, and
 * rounding those to a 4dp grid would visibly change the layout. The scale records
 * what is there, not what a style guide would prefer.
 */
data class Spacing(
    /** Hairline gaps: between a label and its value inside one row. */
    val xxs: Dp = 2.dp,
    /** Tight: icon-to-text, chip internals. */
    val xs: Dp = 4.dp,
    val sm: Dp = 6.dp,
    /** The default gap between related elements. */
    val md: Dp = 8.dp,
    val lg: Dp = 10.dp,
    /** The standard inner padding of a list row. */
    val xl: Dp = 12.dp,
    val xxl: Dp = 14.dp,
    val xxxl: Dp = 16.dp,
    /** Between groups. */
    val group: Dp = 20.dp,
    /** The horizontal page gutter — the most common value in the app. */
    val gutter: Dp = 22.dp,
    /** Between major sections. */
    val section: Dp = 26.dp,
    val huge: Dp = 28.dp,
)

/**
 * Corner radii.
 *
 * The app uses rounded rectangles almost everywhere and pills for buttons that
 * carry a single action. Three sizes are enough; more would invite drift without
 * adding meaning.
 */
data class Radius(
    /** Chips, small badges. */
    val sm: Dp = 8.dp,
    /** Cards, list rows, the standard container. */
    val md: Dp = 12.dp,
    /** Bottom sheets and other large surfaces. */
    val lg: Dp = 20.dp,
    /** Anything taller than it is wide. */
    val pill: Dp = 100.dp,
) {
    val smShape: RoundedCornerShape get() = RoundedCornerShape(sm)
    val mdShape: RoundedCornerShape get() = RoundedCornerShape(md)
    val lgShape: RoundedCornerShape get() = RoundedCornerShape(lg)
    val pillShape: RoundedCornerShape get() = RoundedCornerShape(pill)
}

/**
 * Type scale.
 *
 * Material 3's default `Typography` is used as the base rather than replaced:
 * the existing screens already read `MaterialTheme.typography.bodyMedium` and
 * friends, and swapping the whole scale would change every screen at once — the
 * opposite of what a token-only stage is for.
 *
 * What is added here is the two roles the app needed and Material does not name:
 * [mono] for command output and file paths, and [code] for fenced blocks inside
 * messages.
 */
data class AppTypography(
    // Slightly tighter, more "product" type ramp: larger titles, roomier body
    // line-height, calmer small text. Baseline stays Material so untouched
    // call sites keep working.
    val body: Typography = Typography(
        titleLarge = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
        ),
        titleMedium = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            letterSpacing = (-0.1).sp,
        ),
        bodyLarge = TextStyle(
            fontSize = 16.sp,
            lineHeight = 25.sp,
            letterSpacing = 0.1.sp,
        ),
        bodyMedium = TextStyle(
            fontSize = 15.sp,
            lineHeight = 22.sp,
        ),
        bodySmall = TextStyle(
            fontSize = 13.sp,
            lineHeight = 19.sp,
        ),
        labelSmall = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
    ),
    /** Monospace for tool output, paths, and anything the agent literally typed. */
    val mono: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    /** Monospace for fenced code inside a chat message. */
    val code: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    /** All-caps micro labels: section headers, state pills. */
    val overline: TextStyle = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
    ),
)

/**
 * Elevation and blur, for the frosted surfaces in `Glass.kt`.
 *
 * Named separately from Material's elevation because the glass panes are not
 * shadows: they are translucent layers with a blur radius, and treating them as
 * elevation would produce the wrong visual result on a light background.
 */
data class Elevation(
    /** No separation; the pane sits flat on the backdrop. */
    val flat: Dp = 0.dp,
    /** The default pane lift. */
    val pane: Dp = 2.dp,
    /** Floating elements: the composer, the drawer edge. */
    val floating: Dp = 8.dp,
    /** Sheets and dialogs. */
    val overlay: Dp = 16.dp,
)

/**
 * Every design token, reachable as `PiTheme.spacing.gutter` and friends.
 *
 * Deliberately a plain data holder rather than a CompositionLocal per group:
 * these values do not vary with theme or configuration today, and making them
 * reactive would invite call sites to depend on that. If a future stage needs
 * per-theme spacing, this is the one place to change.
 */
/**
 * The frosted-surface parameters.
 *
 * These describe the look of `Glass.kt`'s panes: how translucent the fill is,
 * how strong the hairline highlight, and how soft the shadow. They are tokens
 * rather than constants inside `Glass.kt` so a future theme can adjust the glass
 * without editing the drawing code.
 */
data class GlassTokens(
    /** Fill opacity for a pane sitting on the backdrop. */
    val fillAlpha: Float = 0.62f,
    /** Fill opacity for a pane that must read as clearly separated (sheets). */
    val fillAlphaStrong: Float = 0.86f,
    /** Opacity of the hairline highlight along the top edge. */
    val borderAlpha: Float = 0.55f,
    /** Opacity of the inner glow that makes the pane look curved. */
    val sheenAlpha: Float = 0.30f,
    /** Corner radius of a glass pane. Matches [Radius.lg] by default. */
    val cornerRadius: Dp = 22.dp,
)

data class PiTokens(
    val spacing: Spacing = Spacing(),
    val radius: Radius = Radius(),
    val typography: AppTypography = AppTypography(),
    val elevation: Elevation = Elevation(),
    val glass: GlassTokens = GlassTokens(),
)

internal val DefaultTokens = PiTokens()

internal val LocalTokens = staticCompositionLocalOf { DefaultTokens }

/**
 * Access point for the design tokens.
 *
 * Usage: `PiTheme.spacing.gutter`, `PiTheme.radius.mdShape`, `PiTheme.typography.mono`.
 *
 * Existing screens keep using their literals until stage 4 converts them, so
 * introducing this object changes nothing on screen.
 */
object PiTheme {
    val spacing: Spacing
        @Composable @ReadOnlyComposable get() = LocalTokens.current.spacing

    val radius: Radius
        @Composable @ReadOnlyComposable get() = LocalTokens.current.radius

    val typography: AppTypography
        @Composable @ReadOnlyComposable get() = LocalTokens.current.typography

    val elevation: Elevation
        @Composable @ReadOnlyComposable get() = LocalTokens.current.elevation

    val glass: GlassTokens
        @Composable @ReadOnlyComposable get() = LocalTokens.current.glass
}
