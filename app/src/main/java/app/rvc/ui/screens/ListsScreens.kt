package app.rvc.ui.screens

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rvc.core.model.ProxyNode
import app.rvc.data.Subscription
import app.rvc.ui.MainViewModel
import app.rvc.ui.components.DialogAction
import app.rvc.ui.components.Divider
import app.rvc.ui.components.RvcDialog
import app.rvc.ui.components.RvcTextField
import app.rvc.ui.components.EmptyState
import app.rvc.ui.components.GlassIconButton
import app.rvc.ui.components.GlassPanel
import app.rvc.ui.components.GroupCard
import app.rvc.ui.components.ListRow
import app.rvc.ui.components.Pill
import app.rvc.ui.components.PrimaryButton
import app.rvc.ui.components.ProviderLogo
import app.rvc.ui.components.ScreenScaffold
import app.rvc.ui.components.UsageBar
import app.rvc.ui.components.pressable
import app.rvc.ui.formatAgo
import app.rvc.ui.formatBytes
import app.rvc.ui.formatExpire
import app.rvc.ui.glass.GlassStyles
import app.rvc.ui.theme.Rvc
import app.rvc.ui.theme.RvcMotion
import app.rvc.ui.theme.RvcType
import app.rvc.vpn.VpnBus
import app.rvc.vpn.VpnState

@Composable
fun LocationsScreen(vm: MainViewModel) {
    val store = vm.store
    val subs by store.subscriptions.collectAsStateWithLifecycle()
    val nodes by store.nodes.collectAsStateWithLifecycle()
    val pings by store.pings.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    val selected by store.selected.collectAsStateWithLifecycle()
    val state by VpnBus.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(subs.filter { it.collapsed }.map { it.id }.toSet()) }
    var confirmDelete by remember { mutableStateOf<ProxyNode?>(null) }
    val c = Rvc.colors
    // Один и тот же сервер может быть в двух подписках: выбранным считается только тот, что из нужной подписки.
    val currentNode = (state as? VpnState.Connected)?.node ?: selected

    val groups = vm.groups(nodes, subs, settings, pings).map { g ->
        if (query.isBlank()) g else g.copy(nodes = g.nodes.filter { it.name.contains(query, ignoreCase = true) })
    }.filter { it.nodes.isNotEmpty() || query.isBlank() }

    ScreenScaffold("Локации", onBack = { vm.back() }, actions = {
        GlassIconButton(Icons.Rounded.Speed, "Пинг") { vm.pingAll() }
    }) {
        item("search") {
            GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), style = GlassStyles.Toast, padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, tint = c.textTertiary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = RvcType.body.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (query.isEmpty()) Text("Поиск локации", style = RvcType.body, color = c.textTertiary)
                            inner()
                        },
                    )
                }
            }
        }
        if (vm.pinging) item("pinging") { Text("Проверяю задержку…", style = RvcType.caption, color = c.textSecondary, modifier = Modifier.padding(start = 6.dp)) }
        if (groups.isEmpty()) item("empty") { EmptyState("Нет серверов", "Добавьте подписку или сервер на главном экране.") }
        groups.forEach { group ->
            val open = group.id !in collapsed || query.isNotBlank()
            item("h-${group.id}") {
                Row(
                    Modifier.fillMaxWidth().pressable(scale = 0.99f, onClick = {
                        collapsed = if (group.id in collapsed) collapsed - group.id else collapsed + group.id
                        store.updateSubscription(group.id) { it.copy(collapsed = group.id in collapsed) }
                    }).padding(start = 6.dp, top = 8.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(group.title, style = RvcType.headline, color = c.text, modifier = Modifier.weight(1f))
                    Text("${group.nodes.size}", style = RvcType.caption, color = c.textTertiary)
                    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.textTertiary, modifier = Modifier.padding(start = 4.dp))
                }
            }
            item("g-${group.id}") {
                AnimatedVisibility(open, enter = expandVertically(RvcMotion.ui()) + fadeIn(RvcMotion.fast()), exit = shrinkVertically(RvcMotion.ui()) + fadeOut(RvcMotion.fast())) {
                    GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), style = GlassStyles.Toast, padding = 0.dp) {
                        group.nodes.forEachIndexed { i, node ->
                            NodeRow(
                                node, pings[node.id]?.ms, selected = node.id == currentNode?.id && node.sourceId == currentNode.sourceId,
                                onLongClick = if (node.sourceId == ProxyNode.MANUAL_SOURCE) ({ confirmDelete = node }) else null,
                            ) {
                                vm.select(node)
                                vm.back()
                            }
                            if (i < group.nodes.lastIndex) Divider()
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { node ->
        RvcDialog(
            "Удалить сервер?",
            onDismiss = { confirmDelete = null },
            text = node.name,
            confirm = DialogAction("Удалить", danger = true) {
                app.rvc.App.subscriptions.removeManual(node.id)
                confirmDelete = null
            },
            dismiss = DialogAction("Отмена") { confirmDelete = null },
        )
    }
}

@Composable
fun SubscriptionsScreen(vm: MainViewModel) {
    val subs by vm.store.subscriptions.collectAsStateWithLifecycle()
    val nodes by vm.store.nodes.collectAsStateWithLifecycle()
    ScreenScaffold("Подписки", onBack = { vm.back() }, actions = {
        GlassIconButton(Icons.Rounded.Refresh, "Обновить все") { vm.updateAll() }
    }) {
        if (subs.isEmpty()) item { EmptyState("Подписок нет", "Добавьте ссылку из личного кабинета провайдера.") { PrimaryButton("Добавить") { vm.showAdd = true } } }
        items(subs, key = { it.id }) { sub -> SubscriptionCard(vm, sub) }
        val manual = nodes[ProxyNode.MANUAL_SOURCE].orEmpty()
        if (manual.isNotEmpty()) item("manual") {
            GroupCard { ListRow("Мои серверы", "${manual.size} — добавлены вручную; удалить — долгим нажатием в «Локациях»") }
        }
        item("add") { PrimaryButton("Добавить подписку", Modifier.fillMaxWidth()) { vm.showAdd = true } }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionCard(vm: MainViewModel, sub: Subscription) {
    val c = Rvc.colors
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val meta = sub.meta
    // URL прячется, если провайдер прислал hide-url или ссылка пришла зашифрованной (crypt1).
    val hidden = meta.hideUrl || sub.importedVia == "crypt1"

    GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderLogo(meta.logoUrl, sub.title, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(sub.title, style = RvcType.headline, color = c.text)
                Text("${sub.nodeCount} серв. · обновлено ${formatAgo(sub.updatedAt)}", style = RvcType.caption, color = c.textSecondary)
            }
        }
        meta.description?.let { Text(it, style = RvcType.body, color = c.textSecondary, modifier = Modifier.padding(top = 10.dp)) }
        meta.usage?.takeIf { !meta.hideUsage }?.let { u ->
            Spacer(Modifier.height(12.dp))
            Text(
                "${formatBytes(u.used)} из ${if (u.unlimited) "∞" else formatBytes(u.total)}" + if (u.expire > 0) " · ${formatExpire(u.expire)}" else "",
                style = RvcType.caption, color = c.textSecondary,
            )
            if (!u.unlimited) UsageBar(u.fraction, Modifier.padding(top = 6.dp))
        }
        sub.error?.let { Text(app.rvc.core.UserMessage.readable(it), style = RvcType.caption, color = c.warning, modifier = Modifier.padding(top = 8.dp)) }
        sub.notices.takeIf { it.isNotEmpty() }?.let { list ->
            Text(list.joinToString("\n"), style = RvcType.caption, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        // Кнопки переносятся на новую строку, а не сжимают друг друга: раньше «Удалить» превращалась в полоску.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Обновить", Icons.Rounded.Refresh, onClick = { vm.update(sub.id) })
            Pill("Имя", Icons.Rounded.Edit, onClick = { rename = true })
            if (!hidden) Pill("Ссылка", Icons.Rounded.ContentCopy, onClick = {
                app.rvc.ui.copySensitive(context, "Подписка", sub.url)
                vm.toast("Ссылка скопирована")
            })
            meta.supportUrl?.let { url -> Pill("Поддержка", Icons.Rounded.SupportAgent, onClick = { app.rvc.ui.Links.open(context, url) }) }
            meta.webPageUrl?.let { url -> Pill("Сайт", Icons.Rounded.Language, onClick = { app.rvc.ui.Links.open(context, url) }) }
            if (meta.hwidActive) Pill("Лимит устройств", Icons.Rounded.Fingerprint, tint = c.textSecondary)
            Pill("Удалить", Icons.Rounded.Delete, tint = c.danger, onClick = { delete = true })
        }
    }

    if (rename) {
        var text by remember { mutableStateOf(sub.name ?: sub.title) }
        RvcDialog(
            "Имя подписки",
            onDismiss = { rename = false },
            confirm = DialogAction("Сохранить") {
                vm.rename(sub.id, text)
                rename = false
            },
            dismiss = DialogAction("Как у провайдера") {
                vm.rename(sub.id, "")
                rename = false
            },
        ) {
            RvcTextField(text, { text = it })
        }
    }
    if (delete) {
        RvcDialog(
            "Удалить подписку?",
            onDismiss = { delete = false },
            text = "Серверы и профили маршрутизации этой подписки будут удалены.",
            confirm = DialogAction("Удалить", danger = true) {
                vm.removeSubscription(sub.id)
                delete = false
            },
            dismiss = DialogAction("Отмена") { delete = false },
        )
    }
}

