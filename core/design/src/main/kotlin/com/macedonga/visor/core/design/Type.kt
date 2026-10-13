package com.macedonga.visor.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Inter 4, the OFL variable file: weight plus an optical size axis that turns it into Inter Display at 32. */
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int, opticalSize: Float) = Font(
    resId = R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opticalSize)),
)

private fun family(weight: Int, opticalSize: Float) = FontFamily(inter(weight, opticalSize))

/** Numerals are tabular, so a percentage or a counter does not shift its neighbours as it changes. */
private const val TABULAR = "tnum"

@Immutable
data class VisorTypography(
    val display: TextStyle = TextStyle(
        fontFamily = family(450, 32f), fontWeight = FontWeight(450),
        fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-0.025).em,
    ),
    val title: TextStyle = TextStyle(
        fontFamily = family(500, 28f), fontWeight = FontWeight(500),
        fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.02).em,
    ),
    val heading: TextStyle = TextStyle(
        fontFamily = family(500, 20f), fontWeight = FontWeight(500),
        fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.012).em,
    ),
    val body: TextStyle = TextStyle(
        fontFamily = family(400, 16f), fontWeight = FontWeight(400),
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.006).em,
    ),
    val label: TextStyle = TextStyle(
        fontFamily = family(450, 14f), fontWeight = FontWeight(450),
        fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 0.005.em,
    ),
    /** A reading that changes: battery, a count. Light and large, never for prose. */
    val readout: TextStyle = TextStyle(
        fontFamily = family(300, 32f), fontWeight = FontWeight(300),
        fontSize = 56.sp, lineHeight = 56.sp, letterSpacing = (-0.035).em, fontFeatureSettings = TABULAR,
    ),
    val readoutSmall: TextStyle = TextStyle(
        fontFamily = family(400, 24f), fontWeight = FontWeight(400),
        fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.02).em, fontFeatureSettings = TABULAR,
    ),
)

val LocalVisorTypography = staticCompositionLocalOf { VisorTypography() }
