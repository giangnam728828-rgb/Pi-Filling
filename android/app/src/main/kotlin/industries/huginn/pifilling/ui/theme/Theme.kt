package industries.huginn.pifilling.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// A cool indigo/teal palette instead of Material's grey defaults, which were a
// large part of why the first builds looked unfinished. Deliberately *not*
// dynamic-colour-first any more: wallpaper-derived accents made the app look
// like a different product on every phone, and the settings sheet in particular
// became unreadable when the wallpaper was a loud photo. Dynamic colour is still
// available, but off by default.
private val Indigo = Color(0xFF4C5FD5)
private val IndigoDeep = Color(0xFF2B3674)
private val IndigoSoft = Color(0xFFE0E3FF)
private val Teal = Color(0xFF0E7C86)
private val Ink = Color(0xFF14161C)
private val Paper = Color(0xFFF8F9FC)
private val PaperDark = Color(0xFF101218)
private val SurfaceDark = Color(0xFF1A1D26)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C1FF),
    onPrimary = Color(0xFF1B2559),
    primaryContainer = Color(0xFF303A70),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFF7FD6DE),
    onSecondary = Color(0xFF00363B),
    secondaryContainer = Color(0xFF254A50),
    onSecondaryContainer = Color(0xFFA9EDF3),
    background = PaperDark,
    onBackground = Color(0xFFE6E8F0),
    surface = PaperDark,
    onSurface = Color(0xFFE6E8F0),
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = Color(0xFFB6BAC8),
    outline = Color(0xFF4A4E5C),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFD9D6),
)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = IndigoSoft,
    onPrimaryContainer = IndigoDeep,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC7F0F3),
    onSecondaryContainer = Color(0xFF00363B),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDEFF7),
    onSurfaceVariant = Color(0xFF4C5163),
    outline = Color(0xFFC3C7D6),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun PiFillingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
