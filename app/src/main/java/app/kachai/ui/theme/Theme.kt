package app.kachai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kachai.data.ThemeMode

// Тёплый нейтральный фон + один яркий акцент. Без динамических цветов — узнаваемость важнее.
private val Accent = Color(0xFFFF5A36)
private val AccentDark = Color(0xFFFF6E4D)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3DB),
    onPrimaryContainer = Color(0xFF3D0D00),
    secondary = Color(0xFF6B635A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9E3),
    onSecondaryContainer = Color(0xFF26221D),
    background = Color(0xFFF7F6F3),
    onBackground = Color(0xFF16130F),
    surface = Color(0xFFF7F6F3),
    onSurface = Color(0xFF16130F),
    surfaceVariant = Color(0xFFEDEAE4),
    onSurfaceVariant = Color(0xFF6B655D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF1EFEB),
    surfaceContainerHigh = Color(0xFFEBE8E3),
    surfaceContainerHighest = Color(0xFFE4E1DB),
    outline = Color(0xFFCFCAC2),
    outlineVariant = Color(0xFFE4E0D9),
    error = Color(0xFFC62828),
)

private val Dark = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF1C0700),
    primaryContainer = Color(0xFF4A1708),
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = Color(0xFFB8AFA4),
    onSecondary = Color(0xFF221E19),
    secondaryContainer = Color(0xFF2B2722),
    onSecondaryContainer = Color(0xFFE9E3DB),
    background = Color(0xFF0F0E0C),
    onBackground = Color(0xFFF2EFEA),
    surface = Color(0xFF0F0E0C),
    onSurface = Color(0xFFF2EFEA),
    surfaceVariant = Color(0xFF24211D),
    onSurfaceVariant = Color(0xFFA39C92),
    surfaceContainerLowest = Color(0xFF0B0A09),
    surfaceContainerLow = Color(0xFF171512),
    surfaceContainer = Color(0xFF1B1916),
    surfaceContainerHigh = Color(0xFF23201C),
    surfaceContainerHighest = Color(0xFF2C2924),
    outline = Color(0xFF4A453E),
    outlineVariant = Color(0xFF2F2B26),
    error = Color(0xFFFF8A80),
)

private val base = Typography()
private val AppTypography = Typography(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.5).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

val MonoNumbers = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun KachaiTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
