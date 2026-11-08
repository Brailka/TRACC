package app.rvc.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.rvc.ui.glass.GlassBackdrop
import app.rvc.ui.glass.GlassStyle
import app.rvc.ui.glass.GlassStyles
import app.rvc.ui.glass.glassSource
import app.rvc.ui.glass.liquidGlass
import app.rvc.ui.theme.Rvc
import app.rvc.ui.theme.RvcMotion

val LocalBackdrop = compositionLocalOf<GlassBackdrop?> { null }

/** Системная настройка «Убрать анимации» — движение заменяется короткими переходами прозрачности. */
@Composable
fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) == 0f }.getOrDefault(false)
    }
}

/**
 * Живой фон: три мягких цветовых пятна. В покое фон неподвижен и не тратит ни одного кадра;
 * при подключении и отключении пятна плавно перетекают на новые места и меняют цвет — так
 * смена состояния ощущается, а батарея и стеклянные панели над фоном не перерисовываются зря.
 * Мягкость даёт сам радиальный градиент, без дорогого размытия на весь экран.
 */
@Composable
fun LiveBackground(backdrop: GlassBackdrop, connected: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = Rvc.colors
    val still = reducedMotion()
    val move = if (still) tween<Float>(0) else tween(1400, easing = RvcMotion.EaseInOut)
    val t1 by animateFloatAsState(if (connected) 0.85f else 0.2f, move, label = "t1")
    val t2 by animateFloatAsState(if (connected) 0.15f else 0.7f, move, label = "t2")
    val target = if (connected) c.blobsOn else c.blobsIdle
    val b0 by animateColorAsState(target[0], tween(900, easing = RvcMotion.EaseInOut), label = "b0")
    val b1 by animateColorAsState(target[1], tween(900, easing = RvcMotion.EaseInOut), label = "b1")
    val b2 by animateColorAsState(target[2], tween(900, easing = RvcMotion.EaseInOut), label = "b2")
    val alpha = if (c.dark) 0.5f else 0.7f

    Box(modifier.fillMaxSize().background(c.background)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .glassSource(backdrop),
        ) {
            val w = size.width
            val h = size.height
            val p1 = t1
            val p2 = t2
            fun blob(color: Color, x: Float, y: Float, r: Float) = drawCircle(
                brush = Brush.radialGradient(
                    0f to color.copy(alpha = alpha),
                    0.45f to color.copy(alpha = alpha * 0.45f),
                    1f to color.copy(alpha = 0f),
                    center = Offset(x, y),
                    radius = r,
                ),
                radius = r,
                center = Offset(x, y),
            )
            blob(b0, w * (0.15f + 0.35f * p1), h * (0.12f + 0.12f * p2), w * 0.75f)
            blob(b1, w * (0.95f - 0.4f * p2), h * (0.35f + 0.15f * p1), w * 0.7f)
            blob(b2, w * (0.3f + 0.4f * p2), h * (0.85f - 0.12f * p1), w * 0.8f)
        }
        content()
    }
}

/**
 * Подложка неподвижной шапки: тот же живой фон, что под всем экраном, поэтому шапка не выглядит
 * отдельной полосой — контент просто уходит под неё и растворяется в нижних [fade].
 * Фон берётся из уже записанного RenderNode, лишней отрисовки нет.
 */
@Composable
fun HeaderBackdrop(height: Dp, modifier: Modifier = Modifier, fade: Dp = 18.dp) {
    val c = Rvc.colors
    val backdrop = LocalBackdrop.current
    Box(
        modifier
            .fillMaxWidth()
            .height(height + fade)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                drawRect(c.background)
                backdrop?.layer?.let { drawLayer(it) }
                val solid = (height.toPx() / size.height).coerceIn(0f, 1f)
                drawRect(Brush.verticalGradient(0f to Color.Black, solid to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
    )
}

/** Стеклянная панель поверх живого фона. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    style: GlassStyle = GlassStyles.Bar,
    tint: Color = Color.Unspecified,
    padding: Dp = 16.dp,
    /** Живое стекло только для неподвижных панелей; карточки в списках рисуются без захвата фона. */
    live: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .liquidGlass(LocalBackdrop.current, shape, style, tint, sampleBackdrop = live)
            .clip(shape)
            .padding(padding),
        content = content,
    )
}

/**
 * Отклик на нажатие: масштаб 0,97 сразу при касании (а не при отпускании), пружина без перелёта.
 * Тактильный отклик — только на значимых действиях.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    scale: Float = 0.97f,
    haptic: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (pressed) scale else 1f, RvcMotion.press(), label = "press")
    val feedback = LocalHapticFeedback.current
    val currentClick by rememberUpdatedState(onClick)
    val currentLongClick by rememberUpdatedState(onLongClick)
    this
        .graphicsLayer { scaleX = s; scaleY = s }
        .semantics { role = Role.Button }
        .pointerInput(enabled, onLongClick != null) {
            if (!enabled) return@pointerInput
            // Gesture detector Compose корректно отменяет касание при прокрутке и вызывает
            // onLongPress в момент удержания, не дожидаясь отпускания пальца.
            if (currentLongClick == null) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { if (haptic) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove); currentClick() },
                )
            } else {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onLongPress = {
                        feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentLongClick?.invoke()
                    },
                    onTap = { if (haptic) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove); currentClick() },
                )
            }
        }
}

@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String, modifier: Modifier = Modifier, size: Dp = 48.dp, tint: Color = Rvc.colors.text, onClick: () -> Unit) {
    Box(
        modifier
            .size(size)
            .liquidGlass(LocalBackdrop.current, CircleShape, GlassStyles.Toast)
            .clip(CircleShape)
            .pressable(scale = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.45f))
    }
}
