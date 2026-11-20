package com.friday.app.ui.theme

import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.app.R

@Immutable
data class FridayColors(
    val background: Color,
    val surface: Color,
    val fill: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val separator: Color,
    val accent: Color,
    val accentSoft: Color,
    val amber: Color,
    val red: Color,
    val green: Color,
    val isDark: Boolean,
)

val LightColors = FridayColors(
    background = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    fill = Color(0xFFE8E8ED),
    text = Color(0xFF1C1C1E),
    textSecondary = Color(0xFF6C6C70),
    textTertiary = Color(0xFF8E8E93),
    separator = Color(0xFFDCDCE0),
    accent = Color(0xFF007AFF),
    accentSoft = Color(0x1A007AFF),
    amber = Color(0xFFD97706),
    red = Color(0xFFE5342A),
    green = Color(0xFF34C759),
    isDark = false,
)

val DarkColors = FridayColors(
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    fill = Color(0xFF2C2C2E),
    text = Color(0xFFF5F5F7),
    textSecondary = Color(0xFF98989F),
    textTertiary = Color(0xFF7C7C80),
    separator = Color(0xFF38383A),
    accent = Color(0xFF0A84FF),
    accentSoft = Color(0x330A84FF),
    amber = Color(0xFFFF9F0A),
    red = Color(0xFFFF453A),
    green = Color(0xFF30D158),
    isDark = true,
)

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int, opticalSize: Float = 14f) = Font(
    R.font.inter,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opticalSize)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))
/** Inter's display optical size, for the large titles and the focus countdown. */
val InterDisplay = FontFamily(inter(500, 32f), inter(600, 32f), inter(700, 32f))

private fun style(family: FontFamily, weight: Int, size: Int, line: Int, tracking: Double) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

val FridayTypography = Typography(
    displayLarge = style(InterDisplay, 500, 72, 76, -2.0).copy(fontFeatureSettings = "tnum"),
    displaySmall = style(InterDisplay, 700, 32, 38, -0.7),
    headlineSmall = style(InterDisplay, 700, 24, 30, -0.4),
    titleLarge = style(Inter, 600, 20, 25, -0.3),
    titleMedium = style(Inter, 600, 17, 22, -0.2),
    titleSmall = style(Inter, 600, 15, 20, -0.1),
    bodyLarge = style(Inter, 400, 16, 22, -0.15),
    bodyMedium = style(Inter, 400, 15, 20, -0.1),
    bodySmall = style(Inter, 400, 13, 18, 0.0),
    labelLarge = style(Inter, 600, 16, 20, -0.15),
    labelMedium = style(Inter, 500, 13, 16, 0.0),
    labelSmall = style(Inter, 600, 11, 14, 0.4),
)

private val FridayShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val LocalFridayColors = staticCompositionLocalOf { LightColors }
val LocalReduceMotion = staticCompositionLocalOf { false }

object Friday {
    val colors: FridayColors
        @Composable @ReadOnlyComposable get() = LocalFridayColors.current
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

private fun FridayColors.toMaterial() = if (isDark) darkColorScheme(
    primary = accent, onPrimary = Color.White, primaryContainer = accentSoft, onPrimaryContainer = accent,
    secondary = accent, onSecondary = Color.White, secondaryContainer = fill, onSecondaryContainer = text,
    tertiary = amber, background = background, onBackground = text, surface = surface, onSurface = text,
    surfaceVariant = fill, onSurfaceVariant = textSecondary, surfaceTint = Color.Transparent,
    outline = separator, outlineVariant = separator, error = red, onError = Color.White,
    surfaceContainerLowest = background, surfaceContainerLow = surface, surfaceContainer = surface,
    surfaceContainerHigh = fill, surfaceContainerHighest = fill, inverseSurface = text, inverseOnSurface = background,
) else lightColorScheme(
    primary = accent, onPrimary = Color.White, primaryContainer = accentSoft, onPrimaryContainer = accent,
    secondary = accent, onSecondary = Color.White, secondaryContainer = fill, onSecondaryContainer = text,
    tertiary = amber, background = background, onBackground = text, surface = surface, onSurface = text,
    surfaceVariant = fill, onSurfaceVariant = textSecondary, surfaceTint = Color.Transparent,
    outline = separator, outlineVariant = separator, error = red, onError = Color.White,
    surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
    surfaceContainerHigh = surface, surfaceContainerHighest = fill, inverseSurface = text, inverseOnSurface = surface,
)

@Composable
fun FridayTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    CompositionLocalProvider(LocalFridayColors provides colors, LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = colors.toMaterial(), typography = FridayTypography, shapes = FridayShapes, content = content)
    }
}
