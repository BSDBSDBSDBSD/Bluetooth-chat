package com.example.nearchat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Brand {
    val Indigo = Color(0xFF5B5BF7)
    val Violet = Color(0xFF8B5CF6)
    val Pink = Color(0xFFEC4899)
    val Online = Color(0xFF22C55E)
    val Delivered = Color(0xFF7DD3FC)

    /** Headers, FABs and the app icon. */
    val header = Brush.linearGradient(listOf(Color(0xFF4F46E5), Violet, Pink))
    /** My chat bubbles and the send button. */
    val bubble = Brush.linearGradient(listOf(Indigo, Violet))
    val bubbleSolid = Violet
}

private val LightColors = lightColorScheme(
    primary = Brand.Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E4FF),
    onPrimaryContainer = Color(0xFF1E1B6B),
    secondary = Brand.Violet,
    tertiary = Brand.Pink,
    background = Color(0xFFF6F6FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEEEF6),
    surfaceContainerHigh = Color(0xFFF0F0F7),
    onSurfaceVariant = Color(0xFF5E5E72),
    outline = Color(0xFF8A8AA0),
    outlineVariant = Color(0xFFE2E2EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5A6FF),
    onPrimary = Color(0xFF1A1872),
    primaryContainer = Color(0xFF34318F),
    onPrimaryContainer = Color(0xFFE6E4FF),
    secondary = Color(0xFFC4B5FD),
    tertiary = Color(0xFFF9A8D4),
    background = Color(0xFF0E0E16),
    surface = Color(0xFF15151F),
    surfaceVariant = Color(0xFF232332),
    surfaceContainerHigh = Color(0xFF262636),
    onSurfaceVariant = Color(0xFFB4B4C8),
    outline = Color(0xFF7E7E96),
    outlineVariant = Color(0xFF2C2C3C),
)

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
    )
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun NearChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

val sectionTitle: TextStyle
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
