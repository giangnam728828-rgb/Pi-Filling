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

// A deliberate fallback for devices without dynamic colour (below Android 12).
// Material's bare `darkColorScheme()`/`lightColorScheme()` defaults are grey and
// were a big part of why the first build looked unfinished; these give the app
// an identity of its own instead.
private val FillingGreen = Color(0xFF1F8A5B)
private val FillingGreenDark = Color(0xFF6EDBA4)
private val FillingInk = Color(0xFF101915)
private val FillingSurface = Color(0xFFF7FAF8)

private val DarkColors = darkColorScheme(
    primary = FillingGreenDark,
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF00522F),
    onPrimaryContainer = Color(0xFF8DF7BE),
    secondary = Color(0xFFB4CCBC),
    surface = Color(0xFF0D1411),
    surfaceVariant = Color(0xFF26332C),
    background = Color(0xFF0D1411),
)

private val LightColors = lightColorScheme(
    primary = FillingGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F5C8),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4E6355),
    surface = FillingSurface,
    surfaceVariant = Color(0xFFDCE5DE),
    background = FillingSurface,
    onSurface = FillingInk,
)

@Composable
fun PiFillingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
