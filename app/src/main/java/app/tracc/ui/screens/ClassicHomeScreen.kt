package app.tracc.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracc.ui.MainViewModel
import app.tracc.ui.Screen
import app.tracc.ui.components.FlagBubble
import app.tracc.ui.components.PingBadge
import app.tracc.ui.components.PowerButton
import app.tracc.ui.components.PowerState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import app.tracc.ui.components.pressable
import app.tracc.core.model.cleanName
import app.tracc.core.model.flag
import app.tracc.core.model.ProxyNode
import app.tracc.data.Subscription
import app.tracc.ui.formatDuration
import app.tracc.ui.theme.Tracc
import app.tracc.ui.theme.TraccType
import app.tracc.vpn.VpnBus
import app.tracc.vpn.VpnState
import kotlinx.coroutines.delay

/** Вертикальный вариант главного экрана, вдохновлённый привычным расположением карточек Happ. */
@Composable
fun ClassicHomeScreen(vm: MainViewModel) {
    val c = Tracc.colors
    val state by VpnBus.state.collectAsStateWithLifecycle()
    val subs by vm.store.subscriptions.collectAsStateWithLifecycle()
    val nodes by vm.store.nodes.collectAsStateWithLifecycle()
    val selected by vm.store.selected.collectAsStateWithLifecycle()
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val pings by vm.store.pings.collectAsStateWithLifecycle()
    val update by app.tracc.App.updates.available.collectAsStateWithLifecycle()
    val groups = remember(nodes, subs, settings, pings) { vm.groups(nodes, subs, settings, pings) }
    val active = state is VpnState.Connected
    var expanded by remember(subs.firstOrNull()?.id) { mutableStateOf(subs.firstOrNull()?.id) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val current = (state as? VpnState.Connected)?.node ?: selected

    Box(Modifier.fillMaxSize().background(if (c.dark) Color(0xFF11142F) else c.background)) {
    Canvas(Modifier.fillMaxSize()) {
        repeat(7) { i ->
            val radius = size.width * (0.7f + i * 0.28f)
            val origin = Offset(size.width * 1.2f, -size.height * 0.1f)
            drawCircle(c.accent.copy(alpha = 0.025f + i * 0.004f), radius, origin, style = Stroke(size.width * 0.19f))
            drawCircle(Color.White.copy(alpha = if (c.dark) 0.025f else 0.18f), radius, origin, style = Stroke(1.dp.toPx()))
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Settings, "Настройки", tint = c.text, modifier = Modifier.size(30.dp).pressable(onClick = { vm.navigate(Screen.Settings) }))
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.Add, "Добавить подписку", tint = c.text, modifier = Modifier.size(32.dp).pressable(onClick = { vm.showAdd = true }))
            }
        }
        item {
            Column(Modifier.fillMaxWidth().height(250.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                PowerButton(when (state) {
                    is VpnState.Connected -> PowerState.ON
                    is VpnState.Connecting -> PowerState.CONNECTING
                    is VpnState.Failed -> PowerState.ERROR
                    else -> PowerState.OFF
                }, vm::toggle, diameter = 158.dp, halo = 40.dp)
                Text(if (active) "Подключён · ${formatDuration(now - (state as VpnState.Connected).since)}" else if (state is VpnState.Connecting) "Подключение…" else "Не подключён", style = TraccType.caption, color = c.textSecondary)
            }
        }
        update?.let { info ->
            item { Text("Доступна TRACC ${info.versionName} · Открыть обновление", color = c.accent, style = TraccType.body, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).pressable(onClick = { vm.navigate(Screen.About) })) }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (vm.pinging) "Проверка пинга…" else "Проверить пинг", style = TraccType.body, color = c.textSecondary, modifier = Modifier.pressable(onClick = { vm.pingAll(groups.flatMap { it.nodes }) }))
                Spacer(Modifier.weight(1f))
                Text("Скрыть все", style = TraccType.body, color = c.textSecondary, modifier = Modifier.pressable(onClick = { expanded = null }))
            }
        }
        subs.forEach { sub ->
            item(sub.id) {
                ClassicSubscription(sub, groups.firstOrNull { it.id == sub.id }?.nodes.orEmpty(), pings, vm.pingPendingIds, current, expanded == sub.id,
                    onToggle = { expanded = if (expanded == sub.id) null else sub.id },
                    onRefresh = { vm.update(sub.id) }, onPing = { vm.pingAll(groups.firstOrNull { it.id == sub.id }?.nodes.orEmpty()) }, onSelect = vm::select)
            }
        }
        groups.firstOrNull { it.id == ProxyNode.MANUAL_SOURCE }?.takeIf { it.nodes.isNotEmpty() }?.let { group ->
            item("manual") {
                ClassicSubscription(null, group.nodes, pings, vm.pingPendingIds, current, expanded == "manual", { expanded = if (expanded == "manual") null else "manual" }, {}, { vm.pingAll(group.nodes) }, vm::select)
            }
        }
        if (subs.isEmpty() && groups.all { it.nodes.isEmpty() }) item {
            Text("Добавьте подписку или сервер кнопкой +", color = c.textSecondary, style = TraccType.body, modifier = Modifier.fillMaxWidth().padding(30.dp))
        }
    }
    }
}

@Composable
private fun ClassicSubscription(sub: Subscription?, nodes: List<ProxyNode>, pings: Map<String, app.tracc.data.PingResult>, pending: Set<String>, selected: ProxyNode?, expanded: Boolean, onToggle: () -> Unit, onRefresh: () -> Unit, onPing: () -> Unit, onSelect: (ProxyNode) -> Unit) {
    val c = Tracc.colors
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(22.dp)).background(c.bar.copy(alpha = 0.83f))) {
        Row(Modifier.fillMaxWidth().pressable(onClick = onToggle).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight, null, tint = c.textSecondary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(sub?.title ?: "Мои серверы", style = TraccType.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${nodes.size} серверов", style = TraccType.caption, color = c.textSecondary)
            }
            if (sub != null) Icon(Icons.Rounded.Refresh, "Обновить подписку", tint = c.textSecondary, modifier = Modifier.padding(8.dp).size(23.dp).pressable(onClick = onRefresh))
            Icon(Icons.Rounded.Speed, "Проверить пинг", tint = c.textSecondary, modifier = Modifier.padding(8.dp).size(23.dp).pressable(onClick = onPing))
        }
        if (sub != null) {
            ProviderBlock(sub, open = { app.tracc.ui.Links.open(context, it) }, onClick = onToggle)
            sub.meta.usage?.takeIf { !sub.meta.hideUsage && !it.unlimited }?.let { usage ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    app.tracc.ui.components.UsageBar((usage.used.toFloat() / usage.total.coerceAtLeast(1)).coerceIn(0f, 1f))
                    Text("Осталось ${app.tracc.ui.formatBytes((usage.total - usage.used).coerceAtLeast(0))}", style = TraccType.caption, color = c.textSecondary)
                }
            }
        }
        AnimatedVisibility(expanded) {
            Column {
                nodes.forEach { node ->
                    Row(Modifier.fillMaxWidth().background(if (node.id == selected?.id) c.accent.copy(alpha = 0.16f) else Color.Transparent).pressable(onClick = { onSelect(node) }).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        FlagBubble(node.flag, node.cleanName)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(node.cleanName, style = TraccType.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(node.connectionLabel, style = TraccType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        PingBadge(pings[node.id]?.ms, stale = pings[node.id]?.isStale == true, checking = node.id in pending)
                        Icon(Icons.Rounded.KeyboardArrowRight, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
