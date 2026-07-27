package com.lifetrack.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Palette ----------------------------------------------------------------
object Ink {
    val bg = Color(0xFF0E1016)        // deep ink blue-black
    val surface = Color(0xFF161A23)   // card
    val surfaceHi = Color(0xFF1D2230) // raised card / chip
    val hairline = Color(0xFF272E3D)  // borders
    val text = Color(0xFFE9ECF2)
    val textDim = Color(0xFF97A0B3)

    // Module accents — the app's signature
    val mint = Color(0xFF6EE7B7)      // Expense (money)
    val mintDeep = Color(0xFF10B981)
    val ember = Color(0xFFFF9A6C)     // Gym (effort)
    val emberDeep = Color(0xFFF4732C)
    val violet = Color(0xFFB79CFF)    // Goals (discipline)
    val violetDeep = Color(0xFF8B5CF6)

    val danger = Color(0xFFFF7B8A)
}

val DarkScheme = darkColorScheme(
    primary = Ink.mint,
    onPrimary = Color(0xFF06281C),
    secondary = Ink.ember,
    onSecondary = Color(0xFF2B1200),
    tertiary = Ink.violet,
    onTertiary = Color(0xFF1E1040),
    background = Ink.bg,
    onBackground = Ink.text,
    surface = Ink.surface,
    onSurface = Ink.text,
    surfaceVariant = Ink.surfaceHi,
    onSurfaceVariant = Ink.textDim,
    outline = Ink.hairline,
    outlineVariant = Ink.hairline,
    error = Ink.danger,
)

val LightScheme = lightColorScheme(
    primary = Ink.mintDeep,
    secondary = Ink.emberDeep,
    tertiary = Ink.violetDeep,
    background = Color(0xFFF6F7FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEFF5),
    onSurfaceVariant = Color(0xFF5B6474),
    outline = Color(0xFFDDE1EA),
    outlineVariant = Color(0xFFDDE1EA),
)

// ---- Type: big confident numerals, quiet labels -----------------------------
// System font, disciplined scale; "tnum" keeps amounts aligned in lists.
val LifeTypography = Typography(
    displayLarge = TextStyle(          // hero numbers
        fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp,
        fontFeatureSettings = "tnum"
    ),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(                // section eyebrows
        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp
    ),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
)

val LifeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun LifeTrackTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = LifeTypography,
        shapes = LifeShapes,
        content = content
    )
}
