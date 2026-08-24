package dev.stan.duolock.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Nox's midnight sky, with Lumen's firefly gold as the accent.
val LumenGold = Color(0xFFFFC24D)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9B82E0),
    onPrimary = Color(0xFF1E1433),
    secondary = Color(0xFF6B4FBB),
    tertiary = LumenGold,
    background = Color(0xFF191125),
    surface = Color(0xFF241A36),
    onBackground = Color(0xFFEDE7F8),
    onSurface = Color(0xFFEDE7F8),
)

@Composable
fun DuoGateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
