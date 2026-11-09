package app.tracc.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tracc.ui.glass.GlassStyle
import app.tracc.ui.glass.liquidGlass
import app.tracc.ui.theme.Tracc
import app.tracc.ui.theme.TraccMotion

enum class PowerState { OFF, CONNECTING, ON, ERROR }

@Composable
/** [halo] — поле вокруг кнопки под свечение при подключении. */
fun PowerButton(state: PowerState, onClick: () -> Unit, modifier: Modifier = Modifier, diameter: Dp = 184.dp, halo: Dp = 56.dp) {
    val c = Tracc.colors
    val still = reducedMotion()
    val on = state == PowerState.ON
    val ring by animateColorAsState(
        when (state) {
            PowerState.ON -> c.connected
            PowerState.CONNECTING -> c.accent
            PowerState.ERROR -> c.danger
            PowerState.OFF -> c.text.copy(alpha = 0.18f)
        },
        TraccMotion.medium(),
        label = "ring",
    )
    val iconTint by animateColorAsState(if (on) c.connected else if (state == PowerState.ERROR) c.danger else c.text, TraccMotion.medium(), label = "icon")
    val glow by animateFloatAsState(if (on) 1f else 0f, tween(500, easing = TraccMotion.EaseOut), label = "glow")

    Box(
        modifier
            .size(diameter + halo)
            .semantics { contentDescription = if (on) "Отключить VPN" else "Подключить VPN" },
        contentAlignment = Alignment.Center,
    ) {
        // Мягкое свечение вокруг кнопки при подключении — материал «загорается», а не появляется рывком.
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = glow }) {
            drawCircle(
                Brush.radialGradient(listOf(c.connected.copy(alpha = if (c.dark) 0.35f else 0.28f), Color.Transparent), center = center, radius = size.minDimension / 2),
            )
        }
        Box(
            Modifier
                .size(diameter)
                // Кнопка прокручивается вместе со списком — без захвата фона, иначе линза пересчитывается на каждом кадре.
                .liquidGlass(LocalBackdrop.current, CircleShape, GlassStyle(blur = 10.dp, lensHeight = 26.dp, lensAmount = 30.dp, elevation = 18.dp), sampleBackdrop = false)
                .clip(CircleShape)
                .pressable(scale = 0.90f, haptic = true, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (state == PowerState.CONNECTING && !still) {
                ConnectingArc(ring)
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 3.dp.toPx()
                    val r = size.minDimension / 2 - (diameter * 0.055f).toPx()
                    drawCircle(ring, radius = r, style = Stroke(if (on) stroke * 1.4f else stroke))
                }
            }
            Icon(
                Icons.Rounded.PowerSettingsNew,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(diameter * 0.32f),
            )
        }
    }
}

/**
 * Дуга подключения. Бесконечная анимация существует только пока идёт подключение,
 * а значения читаются в фазе рисования — перекомпоновки на каждый кадр нет.
 */
@Composable
private fun ConnectingArc(color: Color) {
    val spin = rememberInfiniteTransition(label = "spin")
    val angle = spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "angle")
    val sweep = spin.animateFloat(70f, 150f, infiniteRepeatable(tween(900, easing = TraccMotion.EaseInOut), RepeatMode.Reverse), label = "sweep")
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 3.dp.toPx()
        val r = size.minDimension / 2 - 10.dp.toPx()
        drawArc(
            color = color,
            startAngle = angle.value,
            sweepAngle = sweep.value,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
