package app.filenfiles.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Same palette as the Stack app: white paper, ink text, one Google-blue accent, hairline #DADCE0 outlines. */
object StackColors {
    val Ink = Color(0xFF202124)
    val Muted = Color(0xFF5F6368)
    val Blue = Color(0xFF1A73E8)
    val Line = Color(0xFFDADCE0)
    val Wash = Color(0xFFF1F3F4)
    val DarkPaper = Color(0xFF17181A)
}

private val Light = lightColorScheme(
    primary = StackColors.Blue, onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FE), onPrimaryContainer = Color(0xFF174EA6),
    secondary = StackColors.Muted, onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8F0FE), onSecondaryContainer = StackColors.Ink,
    tertiary = Color(0xFF8A4F00), tertiaryContainer = Color(0xFFFEEFC3), onTertiaryContainer = StackColors.Ink,
    background = Color.White, onBackground = StackColors.Ink,
    surface = Color.White, onSurface = StackColors.Ink,
    surfaceVariant = StackColors.Wash, onSurfaceVariant = StackColors.Muted,
    surfaceTint = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color.White,
    surfaceContainerHigh = StackColors.Wash, surfaceContainerHighest = StackColors.Wash,
    outline = StackColors.Line, outlineVariant = Color(0xFFEEEEEE),
    error = Color(0xFFD93025),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8AB4F8), onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF2A3A55), onPrimaryContainer = Color(0xFFD2E3FC),
    secondary = Color(0xFFBDC1C6), secondaryContainer = Color(0xFF2A3A55), onSecondaryContainer = Color(0xFFE8EAED),
    tertiary = Color(0xFFFFB871), tertiaryContainer = Color(0xFF4A3410), onTertiaryContainer = Color(0xFFE8EAED),
    background = StackColors.DarkPaper, onBackground = Color(0xFFE8EAED),
    surface = StackColors.DarkPaper, onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF2A2B2E), onSurfaceVariant = Color(0xFF9AA0A6),
    surfaceTint = StackColors.DarkPaper,
    surfaceContainerLowest = StackColors.DarkPaper, surfaceContainerLow = StackColors.DarkPaper, surfaceContainer = StackColors.DarkPaper,
    surfaceContainerHigh = Color(0xFF2A2B2E), surfaceContainerHighest = Color(0xFF2A2B2E),
    outline = Color(0xFF3C4043), outlineVariant = Color(0xFF2A2B2E),
    error = Color(0xFFF28B82),
)

/** Fixed Stack palette (no wallpaper-derived colours), light or dark following the system. */
@Composable
fun FilesTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
