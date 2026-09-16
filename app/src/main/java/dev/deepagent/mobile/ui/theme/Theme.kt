package dev.deepagent.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BrandBlue = Color(0xFF4D6BFE)
private val BrandBlueDark = Color(0xFF8AA3FF)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF5B6472),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF0F1115),
    surface = Color.White,
    onSurface = Color(0xFF0F1115),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF454A52),
    outline = Color(0x1F000000),
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF10131A),
    secondary = Color(0xFFB9C0CC),
    background = Color(0xFF151517),
    onBackground = Color(0xFFF9FAFB),
    surface = Color(0xFF1C1D21),
    onSurface = Color(0xFFF9FAFB),
    surfaceVariant = Color(0xFF26272C),
    onSurfaceVariant = Color(0xFFCFD3D6),
    outline = Color(0x1FFFFFFF),
)

@Composable
fun DeepAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
