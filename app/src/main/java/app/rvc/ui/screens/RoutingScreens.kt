package app.rvc.ui.screens

import app.rvc.ui.components.SelectionMark
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rvc.App
import app.rvc.core.routing.RoutingProfile
import app.rvc.data.PerAppMode
import app.rvc.ui.MainViewModel
import app.rvc.ui.Screen
import app.rvc.ui.components.Divider
import app.rvc.ui.components.GlassPanel
import app.rvc.ui.components.GroupCard
import app.rvc.ui.components.ListRow
import app.rvc.ui.components.Pill
import app.rvc.ui.components.ScreenScaffold
import app.rvc.ui.components.SectionTitle
import app.rvc.ui.components.ToggleRow
import app.rvc.ui.components.pressable
import app.rvc.ui.formatAgo
import app.rvc.ui.glass.GlassStyles
import app.rvc.ui.theme.Rvc
import app.rvc.ui.theme.RvcType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RoutingScreen(vm: MainViewModel) {
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val custom by vm.store.routing.collectAsStateWithLifecycle()
    val subs by vm.store.subscriptions.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val c = Rvc.colors
    val profiles = RoutingProfile.BUILT_IN + custom

    ScreenScaffold("Маршрутизация", onBack = { vm.back() }) {
        item {
            GroupCard {
                ToggleRow(
                    "Маршрутизация",
                    "Правила для прямых, проксируемых и блокируемых сайтов. Изменения применяются при переподключении.",
                    Icons.Rounded.CallSplit,
                    checked = settings.routingEnabled,
                ) { on -> vm.updateSettings(reconnect = true) { it.copy(routingEnabled = on) } }
            }
        }
        item { SectionTitle("Профили") }
        item {
            GroupCard {
                profiles.forEachIndexed { i, p ->
                    val active = settings.routingEnabled && settings.activeRoutingId == p.id
                    val owner = p.subscriptionId?.let { id -> subs.firstOrNull { it.id == id }?.title }
                    ListRow(
                        title = if (owner != null) "${p.name} [$owner]" else p.name,
                        subtitle = listOfNotNull(
                            if (p.builtIn) "Встроенный" else null,
                            p.sourceUrl?.let { "автообновление · ${formatAgo(p.sourceUpdatedAt)}" },
                            p.geoError?.let { "геофайлы: $it" },
                        ).joinToString(" · ").ifEmpty { null },
                        icon = when {
                            p.geoError != null -> Icons.Rounded.ErrorOutline
                            p.sourceUrl != null -> Icons.Rounded.Cloud
                            else -> Icons.Rounded.Public
                        },
                        iconTint = if (p.geoError != null) c.danger else c.accent,
                        chevron = !p.builtIn,
                        onClick = { if (p.builtIn) { App.routing.activate(p.id); vm.reconnectIfRunning() } else vm.navigate(Screen.RoutingDetail(p.id)) },
                    ) {
                        SelectionMark(active, Modifier.padding(start = 8.dp))
                    }
                    if (i < profiles.lastIndex) Divider()
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Вставить профиль или ссылку", Icons.Rounded.ContentPaste, onClick = {
                    clipboard.getText()?.text?.let(vm::importRoutingText) ?: vm.toast("Буфер обмена пуст")
                })
                Pill("Обновить", Icons.Rounded.Refresh, onClick = { vm.updateAll() })
            }
        }
        item {
            Text(
                "Поддерживаются профили Happ и INCY (routing.happ.su): ссылки happ://routing/add|onadd, autorouting по URL, " +
                    "JSON и base64. Провайдер может прислать профиль в заголовках подписки.",
                style = RvcType.caption, color = c.textTertiary, modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
fun RoutingDetailScreen(vm: MainViewModel, id: String) {
    val custom by vm.store.routing.collectAsStateWithLifecycle()
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val p = custom.firstOrNull { it.id == id } ?: run { LaunchedEffect(Unit) { vm.back() }; return }
    val scope = rememberCoroutineScope()
    val c = Rvc.colors
    ScreenScaffold(p.name, onBack = { vm.back() }) {
        item {
            GroupCard {
                ToggleRow("Активный профиль", checked = settings.routingEnabled && settings.activeRoutingId == p.id) { on ->
                    if (on) App.routing.activate(p.id) else vm.updateSettings { it.copy(activeRoutingId = RoutingProfile.RUSSIA.id) }
                    vm.reconnectIfRunning()
                }
                Divider()
                ListRow("По умолчанию", value = if (p.globalProxy) "через VPN" else "напрямую", icon = Icons.Rounded.Shield)
                Divider()
                ListRow("Стратегия доменов", value = p.domainStrategy, icon = Icons.Rounded.CallSplit)
                Divider()
                ListRow("DNS через VPN", value = "${p.remoteDnsType} ${p.remoteDnsDomain.ifBlank { p.remoteDnsIp }}", icon = Icons.Rounded.Dns)
                Divider()
                ListRow("DNS напрямую", value = "${p.domesticDnsType} ${p.domesticDnsDomain.ifBlank { p.domesticDnsIp }}", icon = Icons.Rounded.Dns)
            }
        }
        fun rules(title: String, list: List<String>) {
            if (list.isEmpty()) return
            item { SectionTitle(title) }
            item {
                GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), style = GlassStyles.Toast) {
                    Text(list.joinToString("\n"), style = RvcType.caption, color = c.textSecondary)
                }
            }
        }
        rules("Напрямую — сайты", p.directSites)
        rules("Напрямую — IP", p.directIp)
        rules("Через VPN — сайты", p.proxySites)
        rules("Через VPN — IP", p.proxyIp)
        rules("Блокировать — сайты", p.blockSites)
        rules("Блокировать — IP", p.blockIp)
        if (p.usesCustomGeo || p.sourceUrl != null) {
            item { SectionTitle("Источники") }
            item {
                GroupCard {
                    p.sourceUrl?.let { ListRow("Профиль", it, Icons.Rounded.Cloud) }
                    if (p.geoipUrl.isNotBlank()) ListRow("GeoIP", p.geoipUrl, Icons.Rounded.Public)
                    if (p.geositeUrl.isNotBlank()) ListRow("GeoSite", p.geositeUrl, Icons.Rounded.Public)
                    Divider()
                    ListRow("Обновить сейчас", icon = Icons.Rounded.Refresh, onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (p.sourceUrl != null) App.routing.refreshAuto(force = true)
                                App.routing.downloadGeo(p)
                            }.onSuccess { vm.toast("Геофайлы обновлены") }.onFailure { vm.toast("Ошибка: ${it.message}") }
                        }
                    })
                }
            }
        }
        item {
            GroupCard {
                ListRow("Удалить профиль", icon = Icons.Rounded.Delete, iconTint = c.danger, danger = true, onClick = {
                    App.routing.remove(p.id)
                    vm.back()
                })
            }
        }
    }
}

private data class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?)

@Composable
fun AppsScreen(vm: MainViewModel) {
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = Rvc.colors
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != context.packageName }
                .map { info ->
                    AppInfo(
                        info.packageName,
                        info.loadLabel(pm).toString(),
                        runCatching { info.loadIcon(pm).toBitmap(72, 72).asImageBitmap() }.getOrNull(),
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }
    val selected = settings.perAppList
    val visible = apps.orEmpty().filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
        .sortedByDescending { it.pkg in selected }

    ScreenScaffold("Приложения", onBack = { vm.back() }) {
        item {
            GroupCard {
                PerAppMode.entries.forEachIndexed { i, mode ->
                    ListRow(
                        when (mode) {
                            PerAppMode.OFF -> "Все приложения через VPN"
                            PerAppMode.BYPASS -> "Выбранные — мимо VPN"
                            PerAppMode.PROXY -> "Только выбранные — через VPN"
                        },
                        when (mode) {
                            PerAppMode.OFF -> null
                            PerAppMode.BYPASS -> "Банки, госуслуги и т.п. работают напрямую, через обычную сеть"
                            PerAppMode.PROXY -> "Остальные приложения работают без VPN"
                        },
                        onClick = { vm.updateSettings(reconnect = true) { it.copy(perAppMode = mode) } },
                    ) {
                        SelectionMark(settings.perAppMode == mode)
                    }
                    if (i < PerAppMode.entries.lastIndex) Divider()
                }
            }
        }
        if (settings.perAppMode != PerAppMode.OFF) {
            item {
                GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), style = GlassStyles.Toast, padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Search, null, tint = c.textTertiary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        BasicTextField(
                            query, { query = it }, singleLine = true,
                            textStyle = RvcType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (query.isEmpty()) Text("Поиск приложения", style = RvcType.body, color = c.textTertiary)
                                inner()
                            },
                        )
                    }
                }
            }
            if (apps == null) item { Text("Загружаю список…", style = RvcType.caption, color = c.textSecondary) }
            items(visible, key = { it.pkg }) { app ->
                val checked = app.pkg in selected
                GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), style = GlassStyles.Toast, padding = 0.dp) {
                    AppRow(app, checked) {
                        vm.updateSettings { s -> s.copy(perAppList = if (checked) s.perAppList - app.pkg else s.perAppList + app.pkg) }
                    }
                }
            }
            item {
                Text("Изменения применятся при следующем подключении.", style = RvcType.caption, color = c.textTertiary, modifier = Modifier.padding(6.dp))
            }
        }
    }
}

@Composable
private fun AppRow(app: AppInfo, checked: Boolean, onToggle: () -> Unit) {
    val c = Rvc.colors
    Row(
        Modifier.fillMaxWidth().pressable(scale = 0.985f, onClick = onToggle).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app.icon != null) Image(app.icon, null, Modifier.size(36.dp)) else Spacer(Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = RvcType.bodyMedium, color = c.text, maxLines = 1)
            Text(app.pkg, style = RvcType.caption, color = c.textTertiary, maxLines = 1)
        }
        SelectionMark(checked, Modifier.padding(start = 8.dp))
    }
}
