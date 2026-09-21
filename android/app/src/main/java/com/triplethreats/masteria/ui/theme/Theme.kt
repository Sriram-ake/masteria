package com.triplethreats.masteria.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Semantic colour tokens, modelled on the iOS system palette: grouped backgrounds, layered
 * labels and separators, plus Masteria's brand indigo and XP gold. Components never use raw hex.
 */
@Immutable
data class MasteriaColors(
    val isDark: Boolean,
    // Surfaces
    val background: Color,          // grouped background behind cards
    val card: Color,                // secondary grouped background
    val cardElevated: Color,        // tertiary: controls inside cards, sheets
    val fill: Color,                // thin fills: chips, tracks, pressed rows
    val fillStrong: Color,
    // Text
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    // Brand + system accents
    val brand: Color,
    val brandSoft: Color,           // tinted backgrounds behind brand glyphs
    val onBrand: Color,
    val xp: Color,                  // XP / gold
    val xpSoft: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val info: Color,
    val boss: Color,                // boss battles: deep crimson-magenta
    val bossSoft: Color,
    // Materials
    val glassTint: Color,
    val glassFallback: Color,
    val specular: Color,
    val shadow: Color,
    val scrim: Color,
)

val LightColors = MasteriaColors(
    isDark = false,
    background = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    cardElevated = Color(0xFFF7F7FA),
    fill = Color(0x1F787880),
    fillStrong = Color(0x33787880),
    label = Color(0xFF000000),
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    brand = Color(0xFF5B4CF0),
    brandSoft = Color(0x1A5B4CF0),
    onBrand = Color(0xFFFFFFFF),
    xp = Color(0xFFD27400),
    xpSoft = Color(0x1FFF9500),
    success = Color(0xFF248A3D),
    successSoft = Color(0x1F34C759),
    warning = Color(0xFFC93400),
    warningSoft = Color(0x1FFF9500),
    danger = Color(0xFFD70015),
    dangerSoft = Color(0x1AFF3B30),
    info = Color(0xFF007AFF),
    boss = Color(0xFFC2185B),
    bossSoft = Color(0x1AFF2D55),
    glassTint = Color(0xB8F9F9FB),
    glassFallback = Color(0xF2F9F9FB),
    specular = Color(0xFFFFFFFF),
    shadow = Color(0x33000000),
    scrim = Color(0x66000000),
)

val DarkColors = MasteriaColors(
    isDark = true,
    background = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    cardElevated = Color(0xFF2C2C2E),
    fill = Color(0x3D787880),
    fillStrong = Color(0x5C787880),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0xA6545458),
    brand = Color(0xFF8A7DFF),
    brandSoft = Color(0x298A7DFF),
    onBrand = Color(0xFFFFFFFF),
    xp = Color(0xFFFFB340),
    xpSoft = Color(0x29FF9F0A),
    success = Color(0xFF30D158),
    successSoft = Color(0x2930D158),
    warning = Color(0xFFFF9F0A),
    warningSoft = Color(0x29FF9F0A),
    danger = Color(0xFFFF453A),
    dangerSoft = Color(0x29FF453A),
    info = Color(0xFF0A84FF),
    boss = Color(0xFFFF375F),
    bossSoft = Color(0x29FF375F),
    glassTint = Color(0xA61C1C1E),
    glassFallback = Color(0xF21C1C1E),
    specular = Color(0xFFFFFFFF),
    shadow = Color(0x80000000),
    scrim = Color(0x99000000),
)

/** Mastery is always shown with a number and a word as well, never colour alone. */
fun MasteriaColors.forMastery(mastery: Int): Color = when {
    mastery >= 80 -> success
    mastery >= 50 -> info
    else -> warning
}

fun masteryWord(mastery: Int): String = when {
    mastery >= 90 -> "Mastered"
    mastery >= 80 -> "Strong"
    mastery >= 50 -> "Growing"
    mastery >= 30 -> "Weak"
    else -> "New"
}

val LocalColors = staticCompositionLocalOf { LightColors }
val LocalReducedMotion = staticCompositionLocalOf { false }

object Masteria {
    val colors: MasteriaColors @Composable get() = LocalColors.current
    val type: MasteriaType @Composable get() = LocalType.current
    val reducedMotion: Boolean @Composable get() = LocalReducedMotion.current
}

@Composable
fun MasteriaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val context = LocalContext.current
    // "Remove animations" in Android accessibility settings sets the animator scale to 0.
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val material = if (dark) {
        darkColorScheme(
            primary = colors.brand, onPrimary = colors.onBrand, background = colors.background,
            surface = colors.card, onSurface = colors.label, onBackground = colors.label,
            surfaceVariant = colors.cardElevated, error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.brand, onPrimary = colors.onBrand, background = colors.background,
            surface = colors.card, onSurface = colors.label, onBackground = colors.label,
            surfaceVariant = colors.cardElevated, error = colors.danger,
        )
    }
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalType provides MasteriaTypography,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
