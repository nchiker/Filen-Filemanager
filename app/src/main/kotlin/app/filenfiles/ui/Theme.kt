package app.filenfiles.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(primary = Color(0xFF0B5FD8), secondary = Color(0xFF4F5B92), tertiary = Color(0xFF8A4F00))
private val Dark = darkColorScheme(primary = Color(0xFFADC6FF), secondary = Color(0xFFBAC3FF), tertiary = Color(0xFFFFB871))

@Composable
fun FilesTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
