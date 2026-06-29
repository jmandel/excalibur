package com.joshuamandel.excalibur.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Clothbound-library brand palette (used <A12 and when dynamic color is off).
private val Paper = Color(0xFFFBF8F2)
private val Ink = Color(0xFF1A1C19)
private val Pine = Color(0xFF2E5D4B)
private val Brass = Color(0xFFC2873B)
private val DarkBg = Color(0xFF14171A)
private val DarkSurface = Color(0xFF1E2225)
private val Mint = Color(0xFF8FD0B6)
private val BrassLight = Color(0xFFE0A95E)

private val BrandLight = lightColorScheme(
    primary = Pine, onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE9D9), onPrimaryContainer = Color(0xFF0B2C20),
    secondary = Brass, onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E0C4), onSecondaryContainer = Color(0xFF3D2A0C),
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink,
    surfaceVariant = Color(0xFFEDE6D8), onSurfaceVariant = Color(0xFF4C4639),
    outline = Color(0xFFBDB5A4), outlineVariant = Color(0xFFD8CFBE),
    error = Color(0xFF9B362F),
)

private val BrandDark = darkColorScheme(
    primary = Mint, onPrimary = Color(0xFF0B2C20),
    primaryContainer = Color(0xFF1F4D3F), onPrimaryContainer = Color(0xFFCDE9D9),
    secondary = BrassLight, onSecondary = Color(0xFF3D2A0C),
    secondaryContainer = Color(0xFF4D3A1C), onSecondaryContainer = Color(0xFFF3E0C4),
    background = DarkBg, onBackground = Color(0xFFE6E2DA),
    surface = DarkSurface, onSurface = Color(0xFFE6E2DA),
    surfaceVariant = Color(0xFF42463F), onSurfaceVariant = Color(0xFFC4C7BC),
    outline = Color(0xFF5C6058), outlineVariant = Color(0xFF3A3E37),
    error = Color(0xFFF2B8B2),
)

@Composable
fun KindleConverterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> BrandDark
        else -> BrandLight
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                scheme.background.luminance() > 0.5f
        }
    }
    MaterialTheme(colorScheme = scheme, typography = KindleTypography, content = content)
}
