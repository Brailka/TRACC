package app.tracc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tracc.ui.glass.GlassStyles
import app.tracc.ui.glass.liquidGlass
import app.tracc.ui.theme.Tracc
import app.tracc.ui.theme.TraccType

/** Кнопка диалога. [danger] — необратимое действие (удаление, отключение): красная заливка. */
data class DialogAction(val label: String, val danger: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * Диалог в стиле приложения: та же стеклянная панель с ободом и бликом, что у карточек,
 * заголовок слева, кнопки на всю ширину — основная залита акцентом, вторая на заливке.
 * Закрывается касанием вне панели и кнопкой «Назад».
 */
@Composable
fun TraccDialog(
    title: String,
    onDismiss: () -> Unit,
    text: String? = null,
    confirm: DialogAction? = null,
    dismiss: DialogAction? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Tracc.colors
    val shape = RoundedCornerShape(28.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .imePadding()
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                // Внутри окна диалога живой фон недоступен: та же панель, но с плотной подложкой.
                .liquidGlass(null, shape, GlassStyles.Toast, fallback = c.bar)
                .clip(shape)
                .padding(20.dp),
        ) {
            Text(title, style = TraccType.title, color = c.text)
            if (text != null) {
                Text(text, style = TraccType.body, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
            }
            if (content != null) {
                Spacer(Modifier.size(12.dp))
                content()
            }
            if (confirm != null || dismiss != null) {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    dismiss?.let { DialogButton(it, primary = false, Modifier.weight(1f)) }
                    confirm?.let { DialogButton(it, primary = true, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(action: DialogAction, primary: Boolean, modifier: Modifier) {
    val c = Tracc.colors
    val fill = when {
        !action.enabled -> c.fill
        primary && action.danger -> c.danger
        primary -> c.accent
        else -> c.fill
    }
    val textColor = when {
        !action.enabled -> c.textTertiary
        primary -> if (action.danger) Color.White else c.onAccent
        action.danger -> c.danger
        else -> c.text
    }
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(fill)
            .pressable(enabled = action.enabled, onClick = action.onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(action.label, style = TraccType.headline, color = textColor, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** Поле ввода в стиле приложения: заливка вместо рамки Material, как у поиска. */
@Composable
fun TraccTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 6,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val c = Tracc.colors
    BasicTextField(
        value,
        onValueChange,
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = TraccType.body.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.fill)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty() && placeholder != null) Text(placeholder, style = TraccType.body, color = c.textTertiary)
                inner()
            }
        },
    )
}

/** Вариант в [ChoiceDialog]. */
data class Choice<T>(val value: T, val title: String, val subtitle: String? = null)

/** Выбор одного варианта: строки как в списках настроек, у выбранного — отметка. Выбор сразу закрывает диалог. */
@Composable
fun <T> ChoiceDialog(title: String, options: List<Choice<T>>, selected: T, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    val c = Tracc.colors
    TraccDialog(title, onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(18.dp)).background(c.fill)) {
            options.forEachIndexed { i, option ->
                val active = option.value == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable(scale = 0.985f, onClick = { onSelect(option.value) })
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(option.title, style = TraccType.bodyMedium, color = c.text)
                        if (option.subtitle != null) {
                            Text(option.subtitle, style = TraccType.caption, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    SelectionMark(active)
                }
                if (i < options.lastIndex) Divider()
            }
        }
    }
}
