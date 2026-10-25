package io.maildots.app.ui

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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Marka renkleri: web paneliyle aynı palet. */
object Brand {
    val Blue = Color(0xFF2A38EC)
    val BlueBright = Color(0xFF4753FF)
    val Periwinkle = Color(0xFF8F98FF)
    val Paper = Color(0xFFFBFAF6)
    val Ink = Color(0xFF090C1D)
    val Night = Color(0xFF121213)
    val Cream = Color(0xFFF4F1EA)
}

private val Light = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECEEFE),
    onPrimaryContainer = Color(0xFF1B27A0),
    secondary = Brand.Ink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1EFE8),
    onSecondaryContainer = Brand.Ink,
    background = Brand.Paper,
    onBackground = Brand.Ink,
    surface = Brand.Paper,
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFF4F2EB),
    onSurfaceVariant = Color(0xFF585C70),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF6F4EE),
    surfaceContainerHigh = Color(0xFFF1EFE8),
    surfaceContainerHighest = Color(0xFFEBE8DF),
    outline = Color(0xFFCFCCC1),
    outlineVariant = Color(0xFFE3E0D7),
    error = Color(0xFFC8321F),
    onError = Color.White,
    errorContainer = Color(0xFFFBE9E6),
    onErrorContainer = Color(0xFF7A1C10),
)

/**
 * Koyu tema: nötr siyah ve griler (mavi alt ton yok). Marka mavisi yalnızca vurgu
 * rengidir ve açık temadakiyle aynıdır.
 */
private val Dark = darkColorScheme(
    primary = Brand.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF24263A),
    onPrimaryContainer = Color(0xFFD5D8FB),
    secondary = Color(0xFFEDEDEE),
    onSecondary = Color(0xFF141415),
    secondaryContainer = Color(0xFF27272A),
    onSecondaryContainer = Color(0xFFEDEDEE),
    background = Color(0xFF121213),
    onBackground = Color(0xFFEDEDEE),
    surface = Color(0xFF121213),
    onSurface = Color(0xFFEDEDEE),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFFA1A1A6),
    surfaceContainerLowest = Color(0xFF0C0C0D),
    surfaceContainerLow = Color(0xFF18181A),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF232325),
    surfaceContainerHighest = Color(0xFF2B2B2E),
    inverseSurface = Color(0xFFEDEDEE),
    inverseOnSurface = Color(0xFF141415),
    outline = Color(0xFF45454A),
    outlineVariant = Color(0xFF2C2C30),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF1A0806),
    errorContainer = Color(0xFF3A1410),
    onErrorContainer = Color(0xFFFFD0CA),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.em)

private val AppTypography = Typography(
    headlineMedium = style(26, 32, FontWeight.Medium, -0.02),
    headlineSmall = style(22, 28, FontWeight.Medium, -0.015),
    titleLarge = style(20, 26, FontWeight.Medium, -0.01),
    titleMedium = style(16, 22, FontWeight.Medium),
    titleSmall = style(14, 20, FontWeight.Medium),
    bodyLarge = style(16, 24),
    bodyMedium = style(15, 22),
    bodySmall = style(13, 18),
    labelLarge = style(15, 20, FontWeight.Medium),
    labelMedium = style(13, 18, FontWeight.Medium),
    labelSmall = style(12, 16, FontWeight.Medium),
)

@Composable
fun MailDotsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
