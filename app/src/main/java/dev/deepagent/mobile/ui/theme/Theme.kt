package dev.deepagent.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Deep Agent semantic palette.
 *
 * The values borrow the restrained near-black, graphite, violet/cyan identity
 * and explicit active/restricted/failed states from the supplied cockpit
 * reference. The reference layout itself is intentionally not reproduced.
 */
object DeepAgentColors {
    val NearBlack = Color(0xFF030609)
    val Graphite = Color(0xFF080D12)
    val GraphiteElevated = Color(0xFF101820)
    val White = Color(0xFFF2F5F4)
    val Muted = Color(0xFFA7B3BD)

    val Violet = Color(0xFF912BFF)
    val Cyan = Color(0xFF00DFFF)
    val Active = Color(0xFF91F000)
    val Restricted = Color(0xFFF5C542)
    val Failed = Color(0xFFFF5C5C)
}

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
    primary = DeepAgentColors.Violet,
    onPrimary = DeepAgentColors.White,
    primaryContainer = Color(0xFF2B0A50),
    onPrimaryContainer = Color(0xFFF0DBFF),
    secondary = DeepAgentColors.Cyan,
    onSecondary = DeepAgentColors.NearBlack,
    secondaryContainer = Color(0xFF07343B),
    onSecondaryContainer = Color(0xFFB5F7FF),
    tertiary = DeepAgentColors.Restricted,
    onTertiary = DeepAgentColors.NearBlack,
    tertiaryContainer = Color(0xFF3A2D08),
    onTertiaryContainer = Color(0xFFFFE9A4),
    error = DeepAgentColors.Failed,
    onError = DeepAgentColors.NearBlack,
    errorContainer = Color(0xFF431316),
    onErrorContainer = Color(0xFFFFDAD8),
    background = DeepAgentColors.NearBlack,
    onBackground = DeepAgentColors.White,
    surface = DeepAgentColors.Graphite,
    onSurface = DeepAgentColors.White,
    surfaceVariant = DeepAgentColors.GraphiteElevated,
    onSurfaceVariant = DeepAgentColors.Muted,
    outline = Color(0xFF3A4852),
    outlineVariant = Color(0xFF202B33),
)

private val DeepAgentShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
)

@Composable
fun DeepAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = DeepAgentShapes,
        content = content,
    )
}
