package com.vpnchain.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SlateBg = Color(0xFF0B1120)
val SlateSurface = Color(0xFF0F172A)
val SlateCard = Color(0xFF1E293B)
val SlateCardAlt = Color(0xFF162033)
val SlateBorder = Color(0xFF334155)
val SlateText = Color(0xFFF8FAFC)
val SlateMuted = Color(0xFF94A3B8)

val BrandBlue = Color(0xFF3B82F6)
val BrandBlueLight = Color(0xFF60A5FA)
val BrandGreen = Color(0xFF10B981)
val BrandRed = Color(0xFFEF4444)
val BrandAmber = Color(0xFFF59E0B)
val BrandPurple = Color(0xFF8B5CF6)

private val VpnChainDarkColors = darkColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    secondary = BrandGreen,
    onSecondary = Color.White,
    tertiary = BrandPurple,
    background = SlateBg,
    onBackground = SlateText,
    surface = SlateSurface,
    onSurface = SlateText,
    surfaceVariant = SlateCard,
    onSurfaceVariant = SlateMuted,
    outline = SlateBorder,
    error = BrandRed,
    onError = Color.White
)

@Composable
fun VpnChainTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VpnChainDarkColors,
        content = content
    )
}
