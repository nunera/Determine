package org.determine.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** DESIGN.md palette: paper/ink for maximum contrast, hazard yellow only with a text label. */
object Palette {
    val Paper = Color(0xFFFFFFFF)
    val Ink = Color(0xFF131316)
    val MutedInk = Color(0xFF4A4D55)
    val Rule = Color(0xFFC9CBD1)
    val LeftEar = Color(0xFF1D4ED8)
    val RightEar = Color(0xFFCF1F2A)
    val Hazard = Color(0xFFFFD000)
}

private val LightScheme = lightColorScheme(
    primary = Palette.Ink,
    onPrimary = Palette.Paper,
    secondary = Palette.LeftEar,
    onSecondary = Palette.Paper,
    background = Palette.Paper,
    onBackground = Palette.Ink,
    surface = Palette.Paper,
    onSurface = Palette.Ink,
    surfaceVariant = Color(0xFFF1F1F3),
    onSurfaceVariant = Palette.Ink,
    outline = Palette.Ink,
    error = Palette.RightEar,
    onError = Palette.Paper,
)

private val DarkScheme = darkColorScheme(
    primary = Palette.Paper,
    onPrimary = Palette.Ink,
    secondary = Color(0xFFA9C0FF),
    onSecondary = Palette.Ink,
    background = Color.Black,
    onBackground = Palette.Paper,
    surface = Color.Black,
    onSurface = Palette.Paper,
    surfaceVariant = Color(0xFF1E1E22),
    onSurfaceVariant = Palette.Paper,
    outline = Palette.Paper,
    error = Color(0xFFFFA9AD),
    onError = Palette.Ink,
)

/** Large type throughout (low-vision users; Arch §9 "fully operable with TalkBack"). Scales with font size. */
private val LargeType = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.ExtraBold),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold),
    titleLarge = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontSize = 19.sp, lineHeight = 26.sp),
    bodySmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun DetermineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = LargeType,
        content = content,
    )
}
