package app.tracc.ui.components

import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Public
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tracc.R
import app.tracc.core.isVisiblyBlank
import app.tracc.core.provider.Banner
import app.tracc.net.FetchRequest
import app.tracc.net.RustNet
import app.tracc.ui.glass.GlassStyles
import app.tracc.ui.theme.Tracc
import app.tracc.ui.theme.TraccMotion
import app.tracc.ui.theme.TraccType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Каркас вложенного экрана: неподвижная шапка «назад + заголовок», контент прокручивается под ней. */
@Composable
fun ScreenScaffold(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: LazyListScope.() -> Unit) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + HEADER_HEIGHT + 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
        }
        HeaderBackdrop(top + HEADER_HEIGHT)
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(HEADER_HEIGHT)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(title, style = TraccType.title, color = Tracc.colors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
    }
}

/** Высота неподвижной шапки под статус-баром — одна на всех экранах, чтобы кнопки стояли на одной линии. */
val HEADER_HEIGHT = 64.dp

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = TraccType.micro, color = Tracc.colors.textTertiary, modifier = modifier.padding(start = 6.dp, top = 10.dp))
}

/** Группа строк на одной стеклянной подложке, как секции в настройках iOS. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    GlassPanel(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), style = GlassStyles.Toast, padding = 0.dp, content = content)
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Tracc.colors.accent,
    value: String? = null,
    chevron: Boolean = false,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Tracc.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(scale = 0.985f, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(iconTint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(14.dp))
        }
        // Отступ справа отделяет текст от значения, переключателя или стрелки.
        Column(Modifier.weight(1f).padding(end = if (value != null || trailing != null || chevron) 12.dp else 0.dp)) {
            Text(title, style = TraccType.bodyMedium, color = if (danger) c.danger else c.text)
            if (subtitle != null) Text(subtitle, style = TraccType.caption, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        if (value != null) {
            // Значение не длиннее ~половины строки: иначе длинный адрес (DoH) сжимает заголовок до столбика букв.
            Text(value, style = TraccType.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 170.dp))
        }
        trailing?.invoke()
        if (chevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.textTertiary, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, icon: ImageVector? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListRow(title, subtitle, icon, onClick = if (enabled) ({ onChange(!checked) }) else null) {
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onChange else null,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Tracc.colors.connected, checkedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent, uncheckedTrackColor = Tracc.colors.fill),
        )
    }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Tracc.colors.separator))
}

/** Пинг: зелёный до 150 мс, жёлтый до 400, красный выше, серый — нет ответа. */
@Composable
fun PingBadge(ms: Long?, modifier: Modifier = Modifier, stale: Boolean = false, checking: Boolean = false) {
    val c = Tracc.colors
    val color by animateColorAsState(
        when {
            ms == null || stale -> c.textTertiary
            ms <= 0 -> c.danger
            ms < 150 -> c.connected
            ms < 400 -> c.warning
            else -> c.danger
        },
        TraccMotion.fast(),
        label = "ping",
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (checking) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = c.accent,
                trackColor = c.fill,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(6.dp))
            Text("…", style = TraccType.mono, color = c.accent)
            return@Row
        }
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(
            when {
                ms == null -> "—"
                ms <= 0 -> "нет"
                else -> "$ms"
            },
            style = TraccType.mono,
            color = color,
        )
    }
}

/**
 * Значок локации: круглый флаг страны во весь кружок, как в INCY. Не страна (🌍, ⚡) — эмодзи
 * на подложке; автовыбор — значок молнии; страна неизвестна — глобус.
 */
@Composable
fun FlagBubble(flag: String?, fallback: String, size: Dp = 36.dp, auto: Boolean = false) {
    val c = Tracc.colors
    val context = LocalContext.current
    val image = remember(flag, fallback) { Flags.iso(flag, fallback)?.let { Flags.load(context, it) } }
    if (image != null) {
        Image(
            image,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                // Тонкий обод отделяет белые части флагов (Япония, Польша) от светлого фона.
                .border(1.dp, c.separator, CircleShape),
        )
        return
    }
    Box(Modifier.size(size).clip(CircleShape).background(c.fill), contentAlignment = Alignment.Center) {
        when {
            auto -> Icon(Icons.Rounded.Bolt, null, tint = c.accent, modifier = Modifier.size(size * 0.55f))
            flag != null -> Text(flag, style = TraccType.title.copy(fontSize = TraccType.title.fontSize * (size.value / 40f)))
            else -> Icon(Icons.Rounded.Public, null, tint = c.textSecondary, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/** Логотип провайдера по `profile-logo-url`: качается один раз Rust-модулем и кэшируется на диске. */
@Composable
fun ProviderLogo(url: String?, title: String, size: Dp = 44.dp) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(context.cacheDir, "logo-$name")
            if (!file.exists()) RustNet.fetch(FetchRequest(url, timeoutMs = 10_000, maxBytes = 1024 * 1024, outFile = file.absolutePath))
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 8192 || bounds.outHeight > 8192) return@runCatching null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    val c = Tracc.colors
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(if (bitmap == null) c.accent.copy(alpha = 0.18f) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(title.trim().take(1).uppercase().ifEmpty { "T" }, style = TraccType.title, color = c.accent)
        }
    }
}

@Composable
fun Wordmark(modifier: Modifier = Modifier, tint: Color = Tracc.colors.text) {
    Icon(painterResource(R.drawable.ic_wordmark), contentDescription = "TRACC", tint = tint, modifier = modifier.height(22.dp).width(58.dp))
}

private fun parseColor(hex: String?): Color? = runCatching {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    when (h.length) {
        6 -> Color(("FF$h").toLong(16))
        8 -> Color(("${h.substring(6)}${h.substring(0, 6)}").toLong(16))
        else -> null
    }
}.getOrNull()

/** Баннер провайдера (banner-*, expiry-banner-*, sub-info-* Happ, announce): текст и кнопка, цвета от провайдера. */
@Composable
fun ProviderBanner(banner: Banner, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Tracc.colors
    val named = when (banner.bgColor?.lowercase()) {
        "red" -> c.danger
        "green" -> c.connected
        "blue" -> c.accent
        else -> null
    }
    val bg = named ?: parseColor(banner.bgColor)
    val textColor = parseColor(banner.textColor) ?: if (bg != null) Color.White else c.text
    val text = banner.text.trim()
    val hasText = !text.isVisiblyBlank()
    val url = banner.buttonUrl
    if (!hasText && url == null) return
    GlassPanel(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), style = GlassStyles.Toast, tint = bg?.copy(alpha = 0.78f) ?: Color.Unspecified, padding = if (hasText) 16.dp else 10.dp) {
        if (hasText) Text(text, style = TraccType.body, color = textColor, maxLines = 8, overflow = TextOverflow.Ellipsis)
        if (url != null) {
            if (hasText) Spacer(Modifier.height(12.dp))
            val buttonBg = parseColor(banner.buttonColor) ?: if (bg != null) Color.White.copy(alpha = 0.22f) else c.accent
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(buttonBg)
                    .pressable(onClick = { onOpen(url) })
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    banner.buttonText ?: "Подробнее",
                    style = TraccType.bodyMedium,
                    color = parseColor(banner.buttonTextColor) ?: Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Шкала израсходованного трафика: тонкая полоса, цвет теплеет ближе к лимиту. */
@Composable
fun UsageBar(fraction: Float, modifier: Modifier = Modifier) {
    val c = Tracc.colors
    val f by animateFloatAsState(fraction, TraccMotion.ui(), label = "usage")
    val color = when {
        fraction > 0.9f -> c.danger
        fraction > 0.75f -> c.warning
        else -> c.accent
    }
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.fill)) {
        Box(Modifier.fillMaxWidth(f.coerceIn(0.02f, 1f)).height(6.dp).clip(CircleShape).background(color))
    }
}

@Composable
fun Pill(
    text: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    tint: Color = Tracc.colors.text,
    /** Подпись для TalkBack, когда у кнопки только иконка. */
    description: String? = null,
    loading: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = Tracc.colors
    Row(
        modifier
            .clip(CircleShape)
            .background(c.fill)
            .then(if (onClick != null) Modifier.pressable(scale = 0.95f, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(15.dp), color = tint, trackColor = c.fill, strokeWidth = 2.dp)
        } else if (icon != null) {
            Icon(icon, description ?: text.ifEmpty { null }, tint = tint, modifier = Modifier.size(15.dp))
        }
        if (text.isNotEmpty()) Text(text, style = TraccType.caption, color = tint, maxLines = 1)
    }
}

@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = TraccType.headline, color = Tracc.colors.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(text, style = TraccType.body, color = Tracc.colors.textSecondary, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/**
 * Отметка выбора — одна на всё приложение: выбранная локация, профиль маршрутизации, режим,
 * вариант в диалоге, приложение в списке. Выбрано — круг акцентного цвета с галочкой, нет — тонкое кольцо.
 */
@Composable
fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val c = Tracc.colors
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(c.accent) else Modifier.border(1.5.dp, c.textTertiary.copy(alpha = 0.6f), CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(size * 0.64f))
    }
}

/** Основная кнопка: заливка акцентом и хорошо заметный пружинный отклик. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Tracc.colors
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) c.accent else c.fill)
            .pressable(enabled = enabled, scale = 0.93f, haptic = true, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TraccType.headline, color = if (enabled) c.onAccent else c.textTertiary)
    }
}
