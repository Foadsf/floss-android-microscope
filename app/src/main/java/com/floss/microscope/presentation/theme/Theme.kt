package com.floss.microscope.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val ScientificDarkScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00262B),
    primaryContainer = Color(0xFF004E59),
    onPrimaryContainer = Color(0xFF9CF2FF),
    secondary = Color(0xFF64FFDA),
    onSecondary = Color(0xFF00382B),
    tertiary = Color(0xFFB388FF),
    background = Color(0xFF0B1017),
    surface = Color(0xFF131B24),
    surfaceVariant = Color(0xFF1D2936),
    onBackground = Color(0xFFE2E8F0),
    onSurface = Color(0xFFE2E8F0)
)

private val HighContrastDarkScheme = darkColorScheme(
    primary = Color(0xFF00FFFF),
    onPrimary = Color.Black,
    secondary = Color(0xFFFFFF00),
    onSecondary = Color.Black,
    background = Color.Black,
    surface = Color(0xFF0A0A0A),
    onBackground = Color.White,
    onSurface = Color.White
)

private val ScientificLightScheme = lightColorScheme(
    primary = Color(0xFF006876),
    onPrimary = Color.White,
    secondary = Color(0xFF006B58),
    background = Color(0xFFF5FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE0ECF0)
)

val ScientificTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp
    )
)

@Composable
fun MicroscopeTheme(
    darkTheme: Boolean = true,
    highContrast: Boolean = false,
    content: @Composable () -> Unit
) {
    val scheme = when {
        highContrast -> HighContrastDarkScheme
        darkTheme -> ScientificDarkScheme
        else -> ScientificLightScheme
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = ScientificTypography,
        content = content
    )
}
