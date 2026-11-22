package cd.ctm.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cd.ctm.agent.R

val BrandTeal = Color(0xFF0E93A8)
val BrandTealDark = Color(0xFF0B7A8C)
val BrandTealDeep = Color(0xFF0A5565)
val BrandTealLight = Color(0xFF22B5CB)
val BrandSoftLight = Color(0xFFE3F5F8)
val BrandInkLight = Color(0xFF075666)
val BrandSoftDark = Color(0xFF153742)
val BrandInkDark = Color(0xFF7FDCEA)

val PlusJakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
)

@Immutable
data class CtmSemanticColors(
    val okBg: Color,
    val okFg: Color,
    val okBd: Color,
    val warnBg: Color,
    val warnFg: Color,
    val warnBd: Color,
    val badBg: Color,
    val badFg: Color,
    val badBd: Color,
    val infoBg: Color,
    val infoFg: Color,
    val infoBd: Color,
    val neutralBg: Color,
    val neutralFg: Color,
)

private val LightSemanticColors = CtmSemanticColors(
    okBg = Color(0xFFE7F6EC),
    okFg = Color(0xFF166534),
    okBd = Color(0xFFBFE5CC),
    warnBg = Color(0xFFFFF3DC),
    warnFg = Color(0xFF9A5B06),
    warnBd = Color(0xFFF6D9A4),
    badBg = Color(0xFFFDE9E7),
    badFg = Color(0xFFA5281B),
    badBd = Color(0xFFF4C3BE),
    infoBg = Color(0xFFE4F0FB),
    infoFg = Color(0xFF1B5B94),
    infoBd = Color(0xFFBCD7EE),
    neutralBg = Color(0xFFEEF1F5),
    neutralFg = Color(0xFF475569),
)

private val DarkSemanticColors = CtmSemanticColors(
    okBg = Color(0xFF132E20),
    okFg = Color(0xFF6FDC9B),
    okBd = Color(0xFF235C3B),
    warnBg = Color(0xFF33260F),
    warnFg = Color(0xFFF6C26B),
    warnBd = Color(0xFF664B1B),
    badBg = Color(0xFF381715),
    badFg = Color(0xFFFF8D80),
    badBd = Color(0xFF6E2722),
    infoBg = Color(0xFF142B3E),
    infoFg = Color(0xFF7EBDF0),
    infoBd = Color(0xFF234B6B),
    neutralBg = Color(0xFF1E293B),
    neutralFg = Color(0xFFB6C2D3),
)

val LocalCtmSemanticColors = staticCompositionLocalOf { LightSemanticColors }

private val LightColorScheme = lightColorScheme(
    primary = BrandTeal,
    onPrimary = Color.White,
    primaryContainer = BrandSoftLight,
    onPrimaryContainer = BrandInkLight,
    secondary = BrandTealDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7EEF3),
    onSecondaryContainer = BrandTealDeep,
    tertiary = Color(0xFF2980B9),
    onTertiary = Color.White,
    background = Color(0xFFF3F5F9),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFEAEFF6),
    surfaceContainerHighest = Color(0xFFE2E8F0),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFC0392B),
    onError = Color.White,
    errorContainer = Color(0xFFFDE9E7),
    onErrorContainer = Color(0xFFA5281B),
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandTealLight,
    onPrimary = Color(0xFF04222A),
    primaryContainer = BrandSoftDark,
    onPrimaryContainer = BrandInkDark,
    secondary = Color(0xFF3CC4D8),
    onSecondary = Color(0xFF04222A),
    secondaryContainer = Color(0xFF18303D),
    onSecondaryContainer = Color(0xFFAADFE9),
    tertiary = Color(0xFF67B3E8),
    onTertiary = Color(0xFF082235),
    background = Color(0xFF0A111D),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF111A2A),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF0D1524),
    onSurfaceVariant = Color(0xFF93A1B5),
    surfaceContainerLowest = Color(0xFF09101B),
    surfaceContainerLow = Color(0xFF0D1524),
    surfaceContainer = Color(0xFF111A2A),
    surfaceContainerHigh = Color(0xFF172236),
    surfaceContainerHighest = Color(0xFF1F2B3F),
    outline = Color(0xFF2D3D57),
    outlineVariant = Color(0xFF1F2B3F),
    error = Color(0xFFFF8D80),
    onError = Color(0xFF3F0A07),
    errorContainer = Color(0xFF381715),
    onErrorContainer = Color(0xFFFFB4AB),
)

private val CtmTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

private val CtmShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

@Composable
fun CtmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val semantic = if (darkTheme) DarkSemanticColors else LightSemanticColors
    CompositionLocalProvider(LocalCtmSemanticColors provides semantic) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CtmTypography,
            shapes = CtmShapes,
            content = content,
        )
    }
}

object CtmThemeExt {
    val semantic: CtmSemanticColors
        @Composable get() = LocalCtmSemanticColors.current
}
