package industries.huginn.pifilling.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 统一为参考图里那套浅色冷蓝/紫渐变风格：顶部是淡蓝白，中间是柔和浅紫，
// 底部是很浅的米白，所有页面共享同一套配色，切换页面时不割裂。
private val Indigo = Color(0xFF5B6BD6)
private val IndigoDeep = Color(0xFF2B3674)
private val IndigoSoft = Color(0xFFF1F3FF)
private val Teal = Color(0xFF0E7C86)
private val Ink = Color(0xFF14161C)
private val Paper = Color(0xFFF6F6F8)
private val PaperDark = Color(0xFF101114)
private val SurfaceDark = Color(0xFF1E1F24)

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
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1F1F4),
    onSurfaceVariant = Color(0xFF75798A),
    outline = Color(0xFFDDDEE3),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/**
 * 实际生效的深浅模式。所有页面都读 LocalPiFillingDark，统一光色/深色配色，
 * 避免部分页面跟系统配色、部分页面跟用户设置导致页面间颜色割裂。
 */
val LocalPiFillingDark = staticCompositionLocalOf { false }

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
    CompositionLocalProvider(
        LocalPiFillingDark provides darkTheme,
        LocalTokens provides DefaultTokens,
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
