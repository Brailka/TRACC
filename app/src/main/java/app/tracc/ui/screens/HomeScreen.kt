package app.tracc.ui.screens

import app.tracc.ui.components.SelectionMark
import app.tracc.vpn.Traffic
import androidx.compose.runtime.State
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.foundation.layout.widthIn
import app.tracc.data.PingResult
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import app.tracc.ui.components.Flags
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.tracc.core.model.ProxyNode
import app.tracc.core.model.cleanName
import app.tracc.core.model.flag
import app.tracc.core.model.isAuto
import app.tracc.core.provider.Banner
import app.tracc.data.Subscription
import app.tracc.engine.WhitelistDetector
import app.tracc.engine.WhitelistState
import app.tracc.ui.MainViewModel
import app.tracc.ui.Screen
import app.tracc.ui.components.EmptyState
import app.tracc.ui.components.FlagBubble
import app.tracc.ui.components.GlassIconButton
import app.tracc.ui.components.GlassPanel
import app.tracc.ui.components.Pill
import app.tracc.ui.components.PingBadge
import app.tracc.ui.components.PowerButton
import app.tracc.ui.components.PowerState
import app.tracc.ui.components.PrimaryButton
import app.tracc.ui.components.ProviderBanner
import app.tracc.ui.components.ProviderLogo
import app.tracc.ui.components.HEADER_HEIGHT
import app.tracc.ui.components.HeaderBackdrop
import app.tracc.ui.components.UsageBar
import app.tracc.ui.components.Wordmark
import app.tracc.ui.components.pressable
import app.tracc.ui.formatAgo
import app.tracc.ui.formatBytes
import app.tracc.ui.formatDate
import app.tracc.ui.formatDuration
import app.tracc.ui.formatExpire
import app.tracc.ui.formatSpeed
import app.tracc.core.isVisiblyBlank
import app.tracc.ui.glass.GlassStyles
import app.tracc.ui.theme.Tracc
import app.tracc.ui.theme.TraccMotion
import app.tracc.ui.theme.TraccType
import app.tracc.vpn.VpnBus
import app.tracc.vpn.VpnState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    var deleteNode by remember { mutableStateOf<ProxyNode?>(null) }
    deleteNode?.let { node ->
        app.tracc.ui.components.TraccDialog(
            "Удалить сервер?",
            onDismiss = { deleteNode = null },
            text = node.name,
            confirm = app.tracc.ui.components.DialogAction("Удалить", danger = true) {
                app.tracc.App.subscriptions.removeManual(node.id)
                deleteNode = null
            },
            dismiss = app.tracc.ui.components.DialogAction("Отмена") { deleteNode = null },
        )
    }
    val store = vm.store
    val state by VpnBus.state.collectAsStateWithLifecycle()
    val subs by store.subscriptions.collectAsStateWithLifecycle()
    val nodes by store.nodes.collectAsStateWithLifecycle()
    val selected by store.selected.collectAsStateWithLifecycle()
    val pings by store.pings.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    val notices by store.notices.collectAsStateWithLifecycle()
    val updateInfo by app.tracc.App.updates.available.collectAsStateWithLifecycle()
    val downloadStatus by app.tracc.App.updates.downloadStatus.collectAsStateWithLifecycle()
    val updateContext = androidx.compose.ui.platform.LocalContext.current
    var updateDetails by remember { mutableStateOf(false) }
    if (updateDetails) updateInfo?.let { u ->
        app.tracc.ui.components.TraccDialog(
            "TRACC ${u.versionName}",
            onDismiss = { updateDetails = false },
            text = (u.notes + listOf(downloadStatus)).filter { it.isNotBlank() }.joinToString("\n\n"),
            confirm = app.tracc.ui.components.DialogAction(vm.updateBusy ?: "Скачать и установить", enabled = vm.updateBusy == null) { vm.installUpdate(updateContext) },
            dismiss = app.tracc.ui.components.DialogAction("Закрыть") { updateDetails = false },
        )
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    val c = Tracc.colors

    val current = (state as? VpnState.Connected)?.node ?: (state as? VpnState.Connecting)?.node ?: selected
    val sub = subs.firstOrNull { it.id == current?.sourceId } ?: subs.firstOrNull()
    // Скорость обновляется каждую секунду — список групп пересчитывается только при смене данных.
    val groups = remember(nodes, subs, settings, pings) { vm.groups(nodes, subs, settings, pings) }
    // Флаги декодируются заранее в фоне, чтобы первая прокрутка не спотыкалась о декодирование картинок.
    LaunchedEffect(nodes) {
        withContext(Dispatchers.IO) {
            nodes.values.flatten().forEach { node -> Flags.iso(node.flag, node.cleanName)?.let { Flags.load(context, it) } }
        }
    }
    val open: (String) -> Unit = { if (!app.tracc.ui.Links.open(context, it)) vm.toast("Ссылка не поддерживается") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Где в списке лежит каждая группа локаций: заполняется при построении списка, нужно для прокрутки к выбранной.
    val groupIndex = remember { HashMap<String, Int>() }
    // Несколько подписок листаются свайпом по карточке провайдера; список локаций — той, что на экране.
    val pager = rememberPagerState(initialPage = subs.indexOfFirst { it.id == current?.sourceId }.coerceAtLeast(0)) { subs.size }
    val pageSub = subs.getOrNull(pager.currentPage)
    val visibleGroups = if (subs.size > 1) groups.filter { it.id == pageSub?.id || it.id == ProxyNode.MANUAL_SOURCE } else groups
    // Подключились к серверу другой подписки — карточка перелистывается к ней.
    LaunchedEffect(current?.sourceId) {
        val index = subs.indexOfFirst { it.id == current?.sourceId }
        if (index >= 0 && index != pager.currentPage) pager.animateScrollToPage(index)
    }
    val scrollToCurrent: () -> Unit = {
        val node = current
        scope.launch {
            val page = subs.indexOfFirst { it.id == node?.sourceId }
            if (page >= 0 && page != pager.currentPage) {
                pager.animateScrollToPage(page)
                kotlinx.coroutines.delay(60) // список перестраивается под новую подписку
            }
            val group = groups.firstOrNull { g -> g.nodes.any { it.id == node?.id && it.sourceId == node.sourceId } }
            val index = group?.let { groupIndex[it.id] }
            if (node != null && group != null && index != null) {
                val row = group.nodes.indexOfFirst { it.id == node.id }
                val offset = with(density) { (58.dp * row - 96.dp).roundToPx() }.coerceAtLeast(0)
                listState.animateScrollToItem(index, offset)
            }
        }
    }
    ConnectionHaptics(state)

    // Потянуть вниз — обновить подписки.
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val refresh = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = vm.updating,
        // Один жест обновляет всё: подписки и пинг.
        onRefresh = { vm.updateAll(ping = true) },
        modifier = Modifier.fillMaxSize(),
        state = refresh,
        // Индикатор появляется под неподвижной шапкой, а не за ней.
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = refresh,
                isRefreshing = vm.updating,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = top + HEADER_HEIGHT),
            )
        },
    ) {
        LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + HEADER_HEIGHT + 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        var count = 0
        fun entry(key: String, content: @Composable androidx.compose.foundation.lazy.LazyItemScope.() -> Unit) {
            item(key, content = content)
            count++
        }
        updateInfo?.let { u ->
            entry("update") {
                ProviderBanner(
                    Banner(
                        // Коротко: список изменений не должен отодвигать главное.
                        text = "Доступна новая версия TRACC ${u.versionName}",
                        buttonText = vm.updateBusy ?: "Подробнее",
                        buttonUrl = "tracc-update",
                    ),
                    onOpen = { updateDetails = true },
                )
            }
        }

        entry("power") { PowerHero(vm, state, current, current?.let { pings[it.id] }, pingChecking = current?.id in vm.pingPendingIds, hideCheck = sub?.meta?.hideCheck == true, onLocation = scrollToCurrent) }

        if (subs.isNotEmpty()) {
            entry("provider") {
                val openSubs = { vm.navigate(Screen.Subscriptions) }
                if (subs.size == 1) {
                    ProviderBlock(subs[0], open, openSubs)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        HorizontalPager(pager, pageSpacing = 12.dp, verticalAlignment = Alignment.Top) { page ->
                            ProviderBlock(subs[page], open, openSubs)
                        }
                        PagerDots(subs.size, pager.currentPage, Modifier.padding(top = 10.dp))
                    }
                }
            }
        }

        if (subs.isEmpty() && nodes.values.all { it.isEmpty() }) {
            entry("empty") {
                EmptyState(
                    title = "Добавьте подписку",
                    text = "Вставьте ссылку на подписку или сервер — например, из личного кабинета провайдера.",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Из буфера") { clipboard.getText()?.text?.let(vm::importText) ?: vm.toast("Буфер обмена пуст") }
                        PrimaryButton("Ввести ссылку") { vm.showAdd = true }
                    }
                }
            }
        }

        // Все локации всех подписок прямо на главном экране, как в INCY; выбранная отмечена галочкой.
        if (visibleGroups.any { it.nodes.isNotEmpty() }) {
            entry("loc-header") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Локации", style = TraccType.title, color = c.text, modifier = Modifier.weight(1f))
                    Pill("", Icons.Rounded.Search, description = "Поиск локации", onClick = { vm.navigate(Screen.Locations) })
                    Spacer(Modifier.width(8.dp))
                    Pill(if (vm.pinging) "Пинг…" else "Пинг", Icons.Rounded.Speed, onClick = { vm.pingAll(visibleGroups.flatMap { it.nodes }) })
                    Spacer(Modifier.width(8.dp))
                    Pill("", Icons.Rounded.Refresh, description = if (vm.updating) "Обновление…" else "Обновить подписки и пинг", onClick = { vm.updateAll(ping = true) })
                }
            }
            visibleGroups.filter { it.nodes.isNotEmpty() }.forEach { group ->
                if (visibleGroups.size > 1) {
                    entry("group-${group.id}") {
                        Text(group.title, style = TraccType.micro, color = c.textTertiary, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
                    }
                }
                groupIndex[group.id] = count
                entry("nodes-${group.id}") {
                    GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), style = GlassStyles.Toast, padding = 0.dp) {
                        group.nodes.forEachIndexed { i, node ->
                            val ping = pings[node.id]
                            NodeRow(node, ping?.ms, selected = node.id == current?.id && node.sourceId == current.sourceId, stale = ping?.isStale == true, checking = node.id in vm.pingPendingIds,
                                onLongClick = if (node.sourceId == ProxyNode.MANUAL_SOURCE) ({ deleteNode = node }) else null,
                            ) { vm.select(node) }
                            if (i < group.nodes.lastIndex) app.tracc.ui.components.Divider()
                        }
                    }
                }
            }
        }
        entry("bottom") { Spacer(Modifier.height(24.dp).windowInsetsBottomHeight(WindowInsets.navigationBars)) }
    }
        // Шапка неподвижна: логотип, БС и кнопки всегда под рукой, контент уходит под неё.
        HeaderBackdrop(top + HEADER_HEIGHT)
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(HEADER_HEIGHT).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Wordmark()
            Spacer(Modifier.width(10.dp))
            WhitelistBadge(vm)
            Spacer(Modifier.weight(1f))
            Box {
                GlassIconButton(Icons.Rounded.Notifications, "Сообщения") { vm.navigate(Screen.Messages) }
                if (notices.any { !it.read }) {
                    Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(8.dp).clip(CircleShape).background(c.danger))
                }
            }
            Spacer(Modifier.width(10.dp))
            GlassIconButton(Icons.Rounded.Add, "Добавить") { vm.showAdd = true }
            Spacer(Modifier.width(10.dp))
            GlassIconButton(Icons.Rounded.Settings, "Настройки") { vm.navigate(Screen.Settings) }
        }
    }
}


/**
 * Бейдж «БС»: оранжевый — у оператора включены белые списки (зарубежные сайты недоступны, Яндекс доступен),
 * зелёный — ограничений нет, серый — нет сети или идёт проверка. Нажатие — перепроверить.
 */
@Composable
private fun WhitelistBadge(vm: MainViewModel) {
    val c = Tracc.colors
    val state by WhitelistDetector.state.collectAsStateWithLifecycle()
    // Проверка скачивает ~200 КБ, поэтому по таймеру — раз в 15 минут; смена сети проверяется сразу.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                delay(15 * 60_000L)
                vm.checkWhitelist(force = true)
            }
        }
    }
    val (text, color) = when (state) {
        WhitelistState.WHITELIST -> "БС" to c.warning
        WhitelistState.OPEN -> "без БС" to c.connected
        WhitelistState.OFFLINE -> "нет сети" to c.textTertiary
        WhitelistState.CHECKING, WhitelistState.UNKNOWN -> "БС…" to c.textTertiary
    }
    val tint by androidx.compose.animation.animateColorAsState(color, TraccMotion.medium(), label = "bs")
    Row(
        Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f))
            .pressable(scale = 0.94f, onClick = {
                vm.toast(
                    when (state) {
                        WhitelistState.WHITELIST -> "У оператора включены белые списки: зарубежные сайты недоступны, Яндекс открывается"
                        WhitelistState.OPEN -> "Белых списков нет: зарубежные сайты открываются напрямую"
                        WhitelistState.OFFLINE -> "Не открываются ни зарубежные сайты, ни Яндекс"
                        else -> "Проверяю доступность зарубежных сайтов и Яндекса…"
                    },
                )
                vm.checkWhitelist(force = true)
            })
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(5.dp))
        Text(text, style = TraccType.micro, color = tint)
    }
}

/** Всё о подписке на главном экране: карточка провайдера, баннер и ошибка обновления. */
@Composable
private fun ProviderBlock(sub: Subscription, open: (String) -> Unit, onClick: () -> Unit) {
    val c = Tracc.colors
    // Баннер из одной кнопки («Перейти в ЛК») живёт внутри карточки провайдера, а не отдельным блоком.
    val banner = bannerFor(sub)
    val buttonOnly = banner != null && banner.text.isVisiblyBlank() && banner.buttonUrl != null
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProviderCard(sub, announce = announceFor(sub), onOpen = open, action = banner.takeIf { buttonOnly }, onClick = onClick)
        if (banner != null && !buttonOnly) ProviderBanner(banner, onOpen = open)
        sub.error?.let { error ->
            GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), style = GlassStyles.Toast, tint = c.warning.copy(alpha = 0.18f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = c.warning, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(app.tracc.core.UserMessage.readable(error), style = TraccType.body, color = c.text, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Точки под карточкой: сколько подписок и какая на экране. */
@Composable
private fun PagerDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val c = Tracc.colors
    Row(modifier.semantics { contentDescription = "Подписка ${current + 1} из $count" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val active = i == current
            val width by animateDpAsState(if (active) 18.dp else 6.dp, TraccMotion.ui(), label = "dot")
            Box(Modifier.height(6.dp).width(width).clip(CircleShape).background(if (active) c.accent else c.textTertiary.copy(alpha = 0.5f)))
        }
    }
}

/** Объявление провайдера (`announce`) живёт на главном экране, а не в уведомлениях; дубль текста баннера не показываем. */
private fun announceFor(sub: Subscription): String? {
    val text = sub.meta.announce?.trim()?.takeUnless { it.isVisiblyBlank() } ?: return null
    val banner = bannerFor(sub)?.text?.trim()
    return text.takeIf { it != banner }
}

/** Что показать в блоке баннера: баннер окончания → баннер провайдера → инфо-блок Happ. */
private fun bannerFor(sub: Subscription): Banner? {
    val meta = sub.meta
    val expire = meta.usage?.expire ?: 0
    val now = System.currentTimeMillis() / 1000
    val expiring = expire > 0 && expire - now < 3 * 86_400
    if (expiring && (meta.expiryBanner != null || meta.subExpire)) {
        return meta.expiryBanner ?: Banner(
            text = if (expire > now) "Подписка скоро закончится — продлите её, чтобы не потерять доступ." else "Подписка закончилась. Продлите её, чтобы снова подключиться.",
            buttonText = "Продлить",
            buttonUrl = meta.premiumUrl ?: meta.webPageUrl ?: meta.supportUrl,
            bgColor = "red",
        )
    }
    meta.banner?.let { return it }
    return meta.infoBanner
}

/**
 * Всё о провайдере в одной карточке: логотип, имя, трафик и срок одной строкой, кнопки связи,
 * объявление и кнопка-баннер («Перейти в ЛК»). Раньше это были три блока, которые вместе
 * отодвигали список локаций за край экрана.
 */
@Composable
private fun ProviderCard(sub: Subscription, announce: String?, onOpen: (String) -> Unit, action: Banner? = null, onClick: () -> Unit) {
    val c = Tracc.colors
    val meta = sub.meta
    val actions = buildList {
        meta.premiumUrl?.let { add(Triple(Icons.Rounded.Star, "Премиум", it)) }
        meta.webPageUrl?.let { add(Triple(Icons.Rounded.Language, "Сайт", it)) }
        meta.supportUrl?.let { add(Triple(if ("t.me" in it || it.startsWith("tg:")) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.SupportAgent, "Поддержка", it)) }
        meta.supportEmail?.let { add(Triple(Icons.Rounded.MailOutline, "Email", "mailto:$it")) }
    }
    val usage = meta.usage?.takeIf { !meta.hideUsage }
    val expiring = usage != null && usage.expire > 0 && usage.expire * 1000 - System.currentTimeMillis() < 3 * 86_400_000L
    GlassPanel(Modifier.fillMaxWidth().pressable(scale = 0.985f, onClick = onClick), shape = RoundedCornerShape(24.dp), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderLogo(meta.logoUrl, sub.title, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(sub.title, style = TraccType.headline, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val summary = when {
                    usage != null -> buildString {
                        append(formatBytes(usage.used))
                        append(if (usage.unlimited) " из ∞" else " из ${formatBytes(usage.total)}")
                    }
                    else -> meta.description ?: "обновлено ${formatAgo(sub.updatedAt)}"
                }
                Text(summary, style = TraccType.caption, color = if (expiring) c.warning else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Срок выводим отдельной строкой: на узком экране он не конкурирует с трафиком,
                // названием и кнопками карточки и всегда остаётся читаемым.
                if (usage != null && usage.expire > 0) {
                    Text(formatExpire(usage.expire), style = TraccType.caption, color = if (expiring) c.warning else c.textSecondary, maxLines = 1)
                }
                val description = meta.description
                if (usage != null && description != null) {
                    Text(description, style = TraccType.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // До двух кнопок — справа от имени; больше — отдельной строкой, чтобы не сжимать имя и трафик.
            if (actions.size <= 2) actions.forEach { (icon, label, url) -> SmallAction(icon, label) { onOpen(url) } }
        }
        if (actions.size > 2) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                actions.forEach { (icon, label, url) -> SmallAction(icon, label) { onOpen(url) } }
            }
        }
        announce?.let { AnnounceLine(it, meta.announceUrl, onOpen) }
        action?.buttonUrl?.let { url ->
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.accent)
                    .pressable(onClick = { onOpen(url) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(action.buttonText ?: "Подробнее", style = TraccType.bodyMedium, color = c.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Объявление внутри карточки провайдера: две строки, нажатие раскрывает полностью. */
@Composable
private fun AnnounceLine(text: String, url: String?, onOpen: (String) -> Unit) {
    val c = Tracc.colors
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.fill)
            .pressable(scale = 0.99f, onClick = { if (overflows || expanded) expanded = !expanded else url?.let(onOpen) })
            .animateContentSize(TraccMotion.ui())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Campaign, null, tint = c.accent, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = TraccType.body,
                color = c.text,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            )
            if (url != null && (expanded || !overflows)) {
                Text("Подробнее", style = TraccType.caption, color = c.accent, modifier = Modifier.padding(top = 6.dp).pressable(onClick = { onOpen(url) }))
            }
        }
        if (overflows || expanded) {
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                if (expanded) "Свернуть" else "Показать полностью",
                tint = c.textTertiary,
                modifier = Modifier.padding(start = 6.dp).size(20.dp),
            )
        }
    }
}

/** Скорость обновляется раз в секунду — собирается только здесь, чтобы не перекомпоновывать весь экран. */
@Composable
private fun SpeedPills(trafficState: State<Traffic>) {
    val traffic by trafficState
    Pill(formatSpeed(traffic.downBps), Icons.Rounded.ArrowDownward)
    Pill(formatSpeed(traffic.upBps), Icons.Rounded.ArrowUpward)
}

/**
 * Главное действие экрана: большая кнопка, под ней статус и сервер, к которому подключится кнопка.
 * Сервер виден сразу — не нужно искать галочку в списке; касание прокручивает список к нему.
 */
@Composable
private fun PowerHero(vm: MainViewModel, state: VpnState, current: ProxyNode?, ping: PingResult?, pingChecking: Boolean, hideCheck: Boolean, onLocation: () -> Unit) {
    val c = Tracc.colors
    val power = when (state) {
        is VpnState.Connected -> PowerState.ON
        is VpnState.Connecting -> PowerState.CONNECTING
        is VpnState.Failed -> PowerState.ERROR
        VpnState.Idle -> PowerState.OFF
    }
    // Один сборщик на таймер и скорость: с двумя их обновления иногда попадали в соседние кадры.
    // State передаётся целиком, чтобы читали его только StatusLine и SpeedPills, а не весь PowerHero.
    val traffic = VpnBus.traffic.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PowerButton(power, onClick = vm::toggle, diameter = 168.dp, halo = 36.dp)
        StatusLine(state, traffic, Modifier.padding(horizontal = 24.dp))
        // Режим прокси: адрес, который нужно указать в приложении или на другом устройстве.
        val settings by vm.store.settings.collectAsStateWithLifecycle()
        if (state is VpnState.Connected && settings.connectionMode == app.tracc.data.ConnectionMode.PROXY) {
            Text(
                "Прокси SOCKS5 / HTTP · ${app.tracc.vpn.TraccVpnService.proxyAddresses().last()}:${settings.proxyPort}",
                style = TraccType.caption,
                color = c.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (current != null) LocationChip(current, ping, pingChecking, onLocation, Modifier.padding(top = 8.dp))
        AnimatedVisibility(state is VpnState.Connected, enter = fadeIn(TraccMotion.medium()), exit = fadeOut(TraccMotion.fast())) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SpeedPills(traffic)
                if (!hideCheck) {
                    val result = vm.checkResult
                    Pill(
                        when {
                            vm.checking -> "Проверка…"
                            result == null -> "Проверить"
                            result > 0 -> "$result мс"
                            else -> "Нет связи"
                        },
                        if (result != null && result > 0) Icons.Rounded.Check else Icons.Rounded.Speed,
                        tint = if (result != null && result <= 0) c.danger else c.text,
                        loading = vm.checking,
                        onClick = vm::check,
                    )
                }
            }
        }
        ForeignVpnHint(state)
    }
}

/** Сервер под кнопкой: флаг, имя и пинг; касание — к нему в списке. */
@Composable
private fun LocationChip(node: ProxyNode, ping: PingResult?, checking: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tracc.colors
    Row(
        modifier
            .clip(CircleShape)
            .background(c.fill)
            .pressable(scale = 0.96f, onClick = onClick)
            .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagBubble(node.flag, node.cleanName, size = 24.dp, auto = node.isAuto)
        Spacer(Modifier.width(8.dp))
        Text(node.cleanName, style = TraccType.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
        if (ping != null || checking) {
            Spacer(Modifier.width(10.dp))
            PingBadge(ping?.ms, stale = ping?.isStale == true, checking = checking)
        }
    }
}

/**
 * Если сейчас работает VPN другого приложения, подключение TRACC его отключит (в Android активен
 * только один VPN). Подсказка снимает удивление от «пропавшего» чужого VPN.
 */
@Composable
private fun ForeignVpnHint(state: VpnState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var foreign by remember { mutableStateOf(false) }
    // Подписка на изменения сетей вместо опроса: ни одного пробуждения, пока ничего не меняется.
    DisposableEffect(state) {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val update = { foreign = state == VpnState.Idle && app.tracc.engine.NodeTester.foreignVpnActive(context) }
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = update()
            override fun onLost(network: android.net.Network) = update()
        }
        update()
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
    AnimatedVisibility(foreign, enter = fadeIn(TraccMotion.medium()), exit = fadeOut(TraccMotion.fast())) {
        Text(
            "Сейчас включён VPN другого приложения — при подключении TRACC он отключится",
            style = TraccType.caption,
            color = Tracc.colors.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = Tracc.colors
    Box(
        Modifier.padding(start = 6.dp).size(36.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.14f)).pressable(scale = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = c.accent, modifier = Modifier.size(18.dp)) }
}

/** Тактильный отклик на результат подключения: подтверждение при успехе, «отказ» при ошибке. */
@Composable
private fun ConnectionHaptics(state: VpnState) {
    val view = androidx.compose.ui.platform.LocalView.current
    var previous by remember { mutableStateOf<VpnState?>(null) }
    LaunchedEffect(state) {
        val before = previous
        previous = state
        if (before == null || before::class == state::class) return@LaunchedEffect
        val modern = android.os.Build.VERSION.SDK_INT >= 30
        when (state) {
            is VpnState.Connected -> view.performHapticFeedback(if (modern) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            is VpnState.Failed -> view.performHapticFeedback(if (modern) android.view.HapticFeedbackConstants.REJECT else android.view.HapticFeedbackConstants.LONG_PRESS)
            else -> Unit
        }
    }
}

@Composable
private fun StatusLine(state: VpnState, trafficState: State<Traffic>, modifier: Modifier = Modifier) {
    val c = Tracc.colors
    // Свой секундный цикл не нужен: такт даёт сервис вместе со скоростью, и таймер со скоростью
    // перерисовываются одним кадром, а не двумя в разные моменты.
    val tick by trafficState
    val now = remember(tick.at, state) { System.currentTimeMillis() }
    val text = when (state) {
        is VpnState.Connected -> "Подключено · ${formatDuration(now - state.since)}"
        is VpnState.Connecting -> "Подключение…"
        is VpnState.Failed -> app.tracc.core.UserMessage.readable(state.message)
        VpnState.Idle -> "Не подключено"
    }
    AnimatedContent(
        targetState = text.substringBefore(" · "),
        // Без анимации размера: таймер меняет ширину строки каждую секунду, и SizeTransform добавлял
        // к каждому такту лишний кадр.
        transitionSpec = { (fadeIn(TraccMotion.medium()) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(TraccMotion.fast()) using null },
        label = "status",
    ) { head ->
        Text(
            if (state is VpnState.Connected) "$head · ${formatDuration(now - state.since)}" else head,
            // Цифры одной ширины: строка с таймером не «дышит» каждую секунду.
            style = TraccType.headline.copy(fontFeatureSettings = "tnum"),
            color = when (state) {
                is VpnState.Connected -> c.connected
                is VpnState.Failed -> c.danger
                else -> c.textSecondary
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = modifier,
        )
    }
}

@Composable
fun NodeRow(node: ProxyNode, ping: Long?, selected: Boolean, stale: Boolean = false, checking: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = Tracc.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(scale = 0.985f, onLongClick = onLongClick, onClick = onClick)
            .background(if (selected) c.accent.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagBubble(node.flag, node.cleanName, auto = node.isAuto)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(node.cleanName, style = TraccType.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Подпись — только если она что-то говорит: описание от провайдера или протокол своего сервера.
            // «VLESS» под каждой строкой подписки — шум, который удлиняет список.
            val hint = node.description?.takeIf { it.isNotBlank() } ?: node.subtitle.takeIf { node.sourceId == ProxyNode.MANUAL_SOURCE }
            if (hint != null) Text(hint, style = TraccType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PingBadge(ping, stale = stale, checking = checking)
        Box(Modifier.padding(start = 10.dp).size(22.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(selected, enter = fadeIn(TraccMotion.fast()) + scaleIn(initialScale = 0.8f), exit = fadeOut(TraccMotion.fast())) {
                SelectionMark(true)
            }
        }
    }
}
