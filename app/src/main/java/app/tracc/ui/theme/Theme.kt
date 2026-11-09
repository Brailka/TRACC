package app.tracc.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.tracc.R
import app.tracc.data.ThemeMode

@Immutable
data class TraccColors(
    val dark: Boolean,
    val background: Color,
    /** Цвета размытых пятен фона: в покое — холодные, при подключении — зелёные. */
    val blobsIdle: List<Color>,
    val blobsOn: List<Color>,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val connected: Color,
    val warning: Color,
    val danger: Color,
    val separator: Color,
    val bar: Color,
    val fill: Color,
)

private val Dark = TraccColors(
    dark = true,
    background = Color(0xFF07070B),
    blobsIdle = listOf(Color(0xFF3A3AD6), Color(0xFF7B3FE4), Color(0xFF1A6CF0)),
    blobsOn = listOf(Color(0xFF10B981), Color(0xFF0EA5E9), Color(0xFF22C55E)),
    text = Color(0xFFF5F5F7),
    textSecondary = Color(0xFFF5F5F7).copy(alpha = 0.62f),
    textTertiary = Color(0xFFF5F5F7).copy(alpha = 0.38f),
    accent = Color(0xFF7C7CFF),
    onAccent = Color.White,
    connected = Color(0xFF34D399),
    warning = Color(0xFFFBBF24),
    danger = Color(0xFFFF6B6B),
    separator = Color.White.copy(alpha = 0.08f),
    bar = Color(0xFF16161D),
    fill = Color.White.copy(alpha = 0.08f),
)

private val Light = TraccColors(
    dark = false,
    background = Color(0xFFF3F4F9),
    blobsIdle = listOf(Color(0xFFB9B8FF), Color(0xFFE2C6FF), Color(0xFFAFCBFF)),
    blobsOn = listOf(Color(0xFF9FF0CF), Color(0xFFA5E1FF), Color(0xFFC6F7A8)),
    text = Color(0xFF0B0B12),
    textSecondary = Color(0xFF0B0B12).copy(alpha = 0.6f),
    textTertiary = Color(0xFF0B0B12).copy(alpha = 0.36f),
    accent = Color(0xFF5B57F0),
    onAccent = Color.White,
    connected = Color(0xFF0FA968),
    warning = Color(0xFFD97706),
    danger = Color(0xFFE5484D),
    separator = Color.Black.copy(alpha = 0.07f),
    bar = Color(0xFFFFFFFF),
    fill = Color.Black.copy(alpha = 0.05f),
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Шкала шрифта: крупные заголовки с отрицательным трекингом, мелкий текст — с небольшим положительным. */
object TraccType {
    val display = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.03).em)
    val title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = (-0.02).em)
    val headline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.01).em)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp)
    val caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.01.em)
    val micro = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.04.em)
    val mono = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.em)
}

/**
 * Движение по принципам Apple/Emil Kowalski: пружины без перелёта для интерфейса (≈0,35 с),
 * лёгкий отскок только там, где жест несёт импульс; переходы короче 300 мс, кривые ease-out.
 */
object TraccMotion {
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
    val Drawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    fun <T> ui() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f)
    fun <T> bouncy() = spring<T>(dampingRatio = 0.72f, stiffness = 420f)
    fun <T> press() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1400f)
    fun <T> fast() = tween<T>(durationMillis = 180, easing = EaseOut)
    fun <T> medium() = tween<T>(durationMillis = 260, easing = EaseOut)
}

private val LocalColors = staticCompositionLocalOf { Dark }

object Tracc {
    val colors: TraccColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
}

@Composable
fun TraccTheme(mode: ThemeMode, accentColor: Int = 0, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val palette = app.tracc.core.AccentPalette
    val index = palette.index(accentColor)
    val accent = Color((if (dark) palette.dark else palette.light)[index])
    val colors = (if (dark) Dark else Light).copy(
        accent = accent,
        onAccent = if (dark) Color(0xFF07070B) else Color.White,
    )
    val scheme = if (dark) {
        darkColorScheme(primary = colors.accent, background = colors.background, surface = colors.bar, onSurface = colors.text, onBackground = colors.text)
    } else {
        lightColorScheme(primary = colors.accent, background = colors.background, surface = colors.bar, onSurface = colors.text, onBackground = colors.text)
    }
    CompositionLocalProvider(LocalColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
