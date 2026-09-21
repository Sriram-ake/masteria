package com.triplethreats.masteria.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.triplethreats.masteria.R

/*
 * Inter (variable, with an optical-size axis) set the way iOS sets SF: two optical cuts,
 * "Text" for small sizes and "Display" for 20sp and up, with tracking that tightens as size
 * grows and leading that tightens with it. All sizes are sp, so the user's font scale is honoured.
 */

@OptIn(ExperimentalTextApi::class)
private fun interFamily(opticalSize: Float) = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(
            R.font.inter_variable,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(
                FontVariation.weight(w),
                FontVariation.Setting("opsz", opticalSize),
            ),
        )
    }
)

val InterText = interFamily(14f)
val InterDisplay = interFamily(32f)

private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(
    size: Int, line: Int, weight: FontWeight, tracking: TextUnit, display: Boolean = size >= 20,
) = TextStyle(
    fontFamily = if (display) InterDisplay else InterText,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking,
    lineHeightStyle = trim,
)

@Immutable
data class MasteriaType(
    val hero: TextStyle,          // numbers that are the point of a screen (XP, level, mastery %)
    val largeTitle: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val title3: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val bodyEmphasized: TextStyle,
    val callout: TextStyle,
    val subhead: TextStyle,
    val footnote: TextStyle,
    val caption: TextStyle,
    val caption2: TextStyle,
    val mono: TextStyle,
)

val MasteriaTypography = MasteriaType(
    hero = style(56, 60, FontWeight(800), (-0.035).em),
    largeTitle = style(34, 41, FontWeight.Bold, (-0.022).em),
    title1 = style(28, 34, FontWeight.Bold, (-0.02).em),
    title2 = style(22, 28, FontWeight.Bold, (-0.016).em),
    title3 = style(20, 25, FontWeight.SemiBold, (-0.014).em),
    headline = style(17, 22, FontWeight.SemiBold, (-0.012).em),
    body = style(17, 24, FontWeight.Normal, (-0.01).em),
    bodyEmphasized = style(17, 24, FontWeight.Medium, (-0.01).em),
    callout = style(16, 21, FontWeight.Normal, (-0.008).em),
    subhead = style(15, 20, FontWeight.Normal, (-0.006).em),
    footnote = style(13, 18, FontWeight.Normal, (-0.002).em),
    caption = style(12, 16, FontWeight.Medium, 0.em),
    caption2 = style(11, 13, FontWeight.SemiBold, 0.02.em),
    mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp),
)

val LocalType = staticCompositionLocalOf { MasteriaTypography }
