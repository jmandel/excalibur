package com.joshuamandel.excalibur.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.joshuamandel.excalibur.R

// Variable fonts bundled in res/font (offline-first: no Downloadable Fonts, since the
// app runs while tethering with no internet). Literata is Google's screen-reading serif
// — on-theme for an ebook tool — used for display/titles; Inter carries the UI.
@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, weight: FontWeight) =
    Font(resId, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

val Literata = FontFamily(
    variable(R.font.literata, FontWeight.Normal),
    variable(R.font.literata, FontWeight.Medium),
    variable(R.font.literata, FontWeight.SemiBold),
    variable(R.font.literata, FontWeight.Bold),
)
val Inter = FontFamily(
    variable(R.font.inter, FontWeight.Normal),
    variable(R.font.inter, FontWeight.Medium),
    variable(R.font.inter, FontWeight.SemiBold),
    variable(R.font.inter, FontWeight.Bold),
)

private val base = Typography()
val KindleTypography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = Literata, fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontFamily = Literata, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Literata, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = Literata, fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontFamily = Literata, fontWeight = FontWeight.Medium),
    titleMedium = base.titleMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFamily = Inter),
    bodyMedium = base.bodyMedium.copy(fontFamily = Inter),
    bodySmall = base.bodySmall.copy(fontFamily = Inter),
    labelLarge = base.labelLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
)

// Monospace style for URLs/IP addresses — a network address is data, set it like data.
val MonoUrl = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Medium)
