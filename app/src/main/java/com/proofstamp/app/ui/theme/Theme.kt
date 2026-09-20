package com.proofstamp.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object PsColors {
    val Bg = Color(0xFF0B0F14)
    val Surface = Color(0xFF111820)
    val SurfaceHigh = Color(0xFF18212C)
    val SurfaceHigher = Color(0xFF1F2A37)
    val Outline = Color(0xFF2A3644)
    val Accent = Color(0xFF19C37D)
    val AccentDim = Color(0xFF0E7A4F)
    val OnAccent = Color(0xFF04120B)
    val Text = Color(0xFFF2F5F8)
    val TextDim = Color(0xFF9AA7B4)
    val TextFaint = Color(0xFF5E6B78)
    val Danger = Color(0xFFFF5C5C)
    val Warn = Color(0xFFFFB84D)
    val Info = Color(0xFF5AA9FF)
}

private val scheme = darkColorScheme(
    primary = PsColors.Accent,
    onPrimary = PsColors.OnAccent,
    primaryContainer = PsColors.AccentDim,
    onPrimaryContainer = PsColors.Text,
    secondary = PsColors.Info,
    onSecondary = PsColors.Bg,
    background = PsColors.Bg,
    onBackground = PsColors.Text,
    surface = PsColors.Surface,
    onSurface = PsColors.Text,
    surfaceVariant = PsColors.SurfaceHigh,
    onSurfaceVariant = PsColors.TextDim,
    surfaceContainer = PsColors.Surface,
    surfaceContainerHigh = PsColors.SurfaceHigh,
    surfaceContainerHighest = PsColors.SurfaceHigher,
    surfaceContainerLow = PsColors.Bg,
    outline = PsColors.Outline,
    outlineVariant = PsColors.Outline,
    error = PsColors.Danger,
    onError = PsColors.Bg,
    tertiary = PsColors.Warn,
)

private val typography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

val Mono = FontFamily.Monospace

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ProofStampTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
