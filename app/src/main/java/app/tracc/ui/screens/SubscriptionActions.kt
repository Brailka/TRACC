package app.tracc.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracc.data.Subscription
import app.tracc.ui.MainViewModel
import app.tracc.ui.Screen
import app.tracc.ui.components.*
import app.tracc.ui.theme.Tracc

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubscriptionActions(vm: MainViewModel, sub: Subscription, refresh: () -> Unit, ping: () -> Unit, dismiss: () -> Unit) {
    var page by remember { mutableStateOf("menu") }
    var name by remember { mutableStateOf(sub.name ?: sub.title) }
    var url by remember { mutableStateOf(sub.url) }
    var interval by remember { mutableStateOf(sub.effectiveIntervalHours.toString()) }
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val hidden = sub.meta.hideUrl || sub.importedVia == "crypt1"
    ModalBottomSheet(onDismissRequest = dismiss, shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp), containerColor = Tracc.colors.bar.copy(alpha = 0.97f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (page) {
                "menu" -> {
                    Text(sub.title, style = MaterialTheme.typography.titleMedium, color = Tracc.colors.text)
                    ListRow("Настройки", icon = Icons.Rounded.Settings, onClick = { page = "settings" })
                    ListRow("Маршрутизация", icon = Icons.Rounded.Route, onClick = { dismiss(); vm.navigate(Screen.Routing) })
                    ListRow("Обновить подписку", icon = Icons.Rounded.Refresh, onClick = { dismiss(); refresh() })
                    ListRow("Пинг", icon = Icons.Rounded.Speed, onClick = { dismiss(); ping() })
                    ListRow("Редактировать", icon = Icons.Rounded.Edit, onClick = { page = "edit" })
                    ListRow(if (sub.pinned) "Открепить" else "Закрепить", icon = Icons.Rounded.PushPin, onClick = {
                        vm.store.updateSubscription(sub.id) { it.copy(pinned = !it.pinned) }; dismiss()
                    })
                    ListRow("Удалить", icon = Icons.Rounded.Delete, iconTint = Tracc.colors.danger, onClick = { page = "delete" })
                }
                "edit" -> {
                    Text("Редактирование подписки", style = MaterialTheme.typography.titleLarge, color = Tracc.colors.text)
                    TraccTextField(name, { name = it }, placeholder = "Название")
                    if (!hidden) TraccTextField(url, { url = it }, placeholder = "URL подписки")
                    PrimaryButton("Сохранить") {
                        val target = url.trim()
                        if (!hidden && !(target.startsWith("https://") || target.startsWith("http://"))) {
                            vm.toast("Укажите HTTP или HTTPS ссылку")
                        } else {
                            vm.store.updateSubscription(sub.id) { it.copy(name = name.trim().ifBlank { null }, url = if (hidden) it.url else target) }
                            if (target != sub.url && !hidden) refresh()
                            dismiss()
                        }
                    }
                }
                "settings" -> {
                    Text("Настройки подписки", style = MaterialTheme.typography.titleLarge, color = Tracc.colors.text)
                    TraccTextField(interval, { interval = it }, placeholder = "Интервал обновления, часы (1–730)")
                    ToggleRow("Автообновление подписок", checked = settings.autoUpdateSubs) { v -> vm.updateSettings { it.copy(autoUpdateSubs = v) } }
                    ToggleRow("Обновлять при открытии", checked = settings.updateOnOpen) { v -> vm.updateSettings { it.copy(updateOnOpen = v) } }
                    ToggleRow("Пинг при открытии", checked = settings.pingOnOpen) { v -> vm.updateSettings { it.copy(pingOnOpen = v) } }
                    Text("Переключатели применяются ко всем подпискам. Интервал — только к этой подписке.", style = MaterialTheme.typography.bodySmall, color = Tracc.colors.textSecondary)
                    PrimaryButton("Сохранить интервал") {
                        val hours = interval.toIntOrNull()
                        if (hours == null || hours !in 1..730) vm.toast("Интервал должен быть от 1 до 730 часов") else {
                            vm.store.updateSubscription(sub.id) { it.copy(intervalHours = hours) }; dismiss()
                        }
                    }
                }
                "delete" -> {
                    Text("Удалить подписку «${sub.title}»?", style = MaterialTheme.typography.titleLarge, color = Tracc.colors.text)
                    Text("Её серверы и профили маршрутизации будут удалены.", color = Tracc.colors.textSecondary)
                    PrimaryButton("Удалить подписку") { vm.removeSubscription(sub.id); dismiss() }
                    TextButton(onClick = { page = "menu" }) { Text("Отмена") }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
