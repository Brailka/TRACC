package app.rvc.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rvc.App
import app.rvc.core.DeepLink
import app.rvc.core.DeepLinks
import app.rvc.core.model.NetworkOnly
import app.rvc.core.model.ProxyNode
import app.rvc.core.routing.RoutingCommand
import app.rvc.core.routing.RoutingLinks
import app.rvc.data.AppSettings
import app.rvc.data.PingResult
import app.rvc.data.RoutingRepository
import app.rvc.data.SortOrder
import app.rvc.data.Subscription
import app.rvc.engine.NodeTester
import app.rvc.engine.WhitelistDetector
import app.rvc.vpn.RvcVpnService
import app.rvc.vpn.VpnBus
import app.rvc.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Locations : Screen
    data object Subscriptions : Screen
    data object Routing : Screen
    data class RoutingDetail(val id: String) : Screen
    data object Apps : Screen
    data object Settings : Screen
    data object Messages : Screen
    data object About : Screen
}

/** Группа серверов для списка локаций. */
data class NodeGroup(val id: String, val title: String, val nodes: List<ProxyNode>)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val store = App.store

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()

    var pinging by mutableStateOf(false)
        private set
    var updating by mutableStateOf(false)
        private set
    var checkResult by mutableStateOf<Long?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var showAdd by mutableStateOf(false)

    /** Состояние обновления приложения: null — нет действий, иначе текст для кнопки. */
    var updateBusy by mutableStateOf<String?>(null)
        private set

    /**
     * Действие из внешней ссылки, ждущее подтверждения. Ссылку может открыть любая страница или
     * приложение, поэтому импорт подписок, серверов, маршрутизации и отключение VPN не выполняются молча.
     */
    var pendingLink by mutableStateOf<DeepLink?>(null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    /** Тактильный отклик на результат действия: true — подтверждение, false — ошибка. Его выполняет RvcRoot. */
    private val _haptics = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val haptics = _haptics.asSharedFlow()

    /** Запрос разрешения VPN: Activity запускает системный диалог и вызывает [onPermissionResult]. */
    private val _permission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val permissionRequests = _permission.asSharedFlow()
    private var pendingConnect: ProxyNode? = null

    private var pingJob: Job? = null
    private var lastOpenUpdate = 0L
    private var whitelistJob: Job? = null
    @Volatile private var foreground = false

    fun setForeground(visible: Boolean) {
        foreground = visible
        if (visible) checkWhitelist() else whitelistJob?.cancel()
    }

    /** Смена сети (Wi-Fi ↔ мобильная, другой оператор) — повод заново проверить белые списки. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            if (foreground) checkWhitelist(force = true, delayMs = 2_000)
        }
    }

    init {
        runCatching { app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback) }
        // Результат «Проверить» относится к конкретному подключению и сбрасывается при его смене.
        viewModelScope.launch {
            var last: Any? = null
            VpnBus.state.collect { s ->
                val key = (s as? VpnState.Connected)?.let { it.node.id to it.since }
                if (key != last) checkResult = null
                last = key
            }
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }

    fun checkWhitelist(force: Boolean = false, delayMs: Long = 0) {
        if (whitelistJob?.isActive == true && !force) return
        whitelistJob?.cancel()
        whitelistJob = viewModelScope.launch {
            delay(delayMs)
            WhitelistDetector.check(force)
        }
    }

    /** Направление последнего перехода: вперёд экран въезжает справа, назад уходит вправо. */
    var navForward by mutableStateOf(true)
        private set

    fun navigate(to: Screen) {
        if (stack.last() == to) return
        navForward = true
        stack.add(to)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        navForward = false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun toast(text: String) {
        _messages.tryEmit(app.rvc.core.UserMessage.readable(text))
    }

    // region VPN

    fun toggle() {
        when (VpnBus.state.value) {
            is VpnState.Connected, is VpnState.Connecting -> disconnect()
            is VpnState.Failed -> if (store.settings.value.killSwitch) disconnect() else connect()
            else -> connect()
        }
    }

    fun connect(node: ProxyNode? = null) {
        val target = node ?: store.selected.value ?: bestNode() ?: run {
            toast("Добавьте подписку или сервер")
            showAdd = true
            return
        }
        store.select(target)
        pendingConnect = target
        _permission.tryEmit(Unit)
    }

    fun onPermissionResult(granted: Boolean) {
        val node = pendingConnect ?: return
        pendingConnect = null
        if (granted) RvcVpnService.start(getApplication(), node) else toast("Без разрешения VPN подключиться нельзя")
    }

    fun disconnect() = RvcVpnService.stop(getApplication())

    fun select(node: ProxyNode) {
        val wasOn = VpnBus.state.value.let { it is VpnState.Connected || it is VpnState.Connecting }
        store.select(node)
        if (wasOn && (VpnBus.state.value as? VpnState.Connected)?.node?.id != node.id) connect(node)
    }

    /** Проверка соединения через работающий туннель (кнопка «Проверить»). */
    fun check() {
        if (checking) return
        checking = true
        checkResult = null
        viewModelScope.launch(Dispatchers.IO) {
            val ms = NodeTester.checkTunnel(store.settings.value.testUrl)
            withContext(Dispatchers.Main) {
                checkResult = ms
                checking = false
            }
        }
    }

    // endregion

    // region Серверы

    fun groups(nodes: Map<String, List<ProxyNode>>, subs: List<Subscription>, settings: AppSettings, pings: Map<String, PingResult>): List<NodeGroup> {
        val network = currentNetwork()
        fun visible(sub: Subscription?, list: List<ProxyNode>): List<ProxyNode> {
            val filtered = if (sub?.meta?.dontUseFilter == true || network == null) list else list.filter { n ->
                when (n.networkOnly) {
                    NetworkOnly.WIFI -> network == NetworkOnly.WIFI
                    NetworkOnly.MOBILE -> network == NetworkOnly.MOBILE
                    null -> true
                }
            }
            val order = sub?.meta?.sortOrder?.let { o -> SortOrder.entries.firstOrNull { it.name.equals(o, true) } } ?: settings.sortOrder
            return when (order) {
                SortOrder.NONE -> filtered
                SortOrder.NAME -> filtered.sortedBy { it.name.lowercase() }
                SortOrder.PING -> filtered.sortedBy { pings[it.id]?.ms?.takeIf { ms -> ms > 0 } ?: Long.MAX_VALUE }
            }
        }
        val groups = subs.map { sub -> NodeGroup(sub.id, sub.title, visible(sub, nodes[sub.id].orEmpty())) }
        val manual = nodes[ProxyNode.MANUAL_SOURCE].orEmpty()
        return (groups + listOfNotNull(manual.takeIf { it.isNotEmpty() }?.let { NodeGroup(ProxyNode.MANUAL_SOURCE, "Мои серверы", visible(null, it)) }))
            .filter { it.nodes.isNotEmpty() }
    }

    private fun currentNetwork(): NetworkOnly? {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.firstOrNull { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkOnly.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkOnly.MOBILE
            else -> null
        }
    }

    private fun bestNode(): ProxyNode? {
        val all = store.allNodes()
        if (all.isEmpty()) return null
        val pings = store.pings.value
        return all.filter { (pings[it.id]?.ms ?: -1) > 0 }.minByOrNull { pings[it.id]!!.ms } ?: all.first()
    }

    fun pingAll(nodes: List<ProxyNode> = store.allNodes()) {
        if (pinging || nodes.isEmpty()) return
        pinging = true
        pingJob = viewModelScope.launch {
            val settings = store.settings.value
            val batch = HashMap<String, PingResult>()
            var last = System.currentTimeMillis()
            val foreignVpn = NodeTester.foreignVpnActive(getApplication())
            if (foreignVpn && settings.pingType in setOf(app.rvc.data.PingType.TCP, app.rvc.data.PingType.ICMP)) {
                toast("Включён VPN другого приложения — TCP и ICMP через него недостоверны, проверяю через ядро")
            }
            var answered = 0
            NodeTester.testAll(nodes, settings.pingType, settings.testUrl, foreignVpn).collect { (id, ms) ->
                batch[id] = PingResult(ms)
                if (ms >= 0) answered++
                // Результаты применяются пачками, чтобы список не дёргался на каждом ответе.
                if (System.currentTimeMillis() - last > 250) {
                    store.putPings(HashMap(batch))
                    batch.clear()
                    last = System.currentTimeMillis()
                }
            }
            store.putPings(batch)
            pinging = false
            // Многие сети (и часть Wi-Fi-роутеров) режут ICMP целиком: тогда «нет» у всех — не вина серверов.
            if (settings.pingType == app.rvc.data.PingType.ICMP && !foreignVpn && answered == 0 && nodes.size > 1) {
                toast("Ни один сервер не ответил на ICMP — похоже, сеть его блокирует. Выберите пинг TCP или «Через прокси»")
            }
        }
    }

    // endregion

    // region Подписки и импорт

    /** [ping] — после обновления сразу перепинговать: жест «потянуть вниз» обновляет всё разом. */
    fun updateAll(ping: Boolean = false) {
        if (updating) return
        updating = true
        viewModelScope.launch {
            val ok = App.subscriptions.updateAll(force = true)
            App.routing.refreshAuto()
            updating = false
            val total = store.subscriptions.value.size
            if (total > 0) {
                toast(if (ok == total) "Подписки обновлены" else "Обновлено $ok из $total")
                _haptics.tryEmit(ok == total)
            }
            if (ping) pingAll()
        }
    }

    fun update(id: String) = viewModelScope.launch {
        App.subscriptions.update(id)
            .onSuccess { toast("Подписка обновлена"); _haptics.tryEmit(true) }
            .onFailure { toast(it.message ?: "Ошибка"); _haptics.tryEmit(false) }
    }

    fun removeSubscription(id: String) = App.subscriptions.remove(id)

    fun rename(id: String, name: String) = store.updateSubscription(id) { it.copy(name = name.trim().ifBlank { null }) }

    /** Ввод пользователя или содержимое буфера: подписка, ссылки серверов, .conf, deep link, профиль маршрутизации. */
    fun importText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val link = DeepLinks.parse(t)
        when {
            link != null -> handle(link)
            t.lines().size > 1 || t.startsWith("[") || t.startsWith("{") -> importNodes(t)
            else -> toast("Не удалось распознать ссылку")
        }
    }

    fun handleDeepLink(uri: String) {
        val link = DeepLinks.parse(uri) ?: return toast("Ссылка не поддерживается")
        val running = VpnBus.state.value.let { it is VpnState.Connected || it is VpnState.Connecting }
        when (link) {
            DeepLink.Status -> Unit
            DeepLink.Connect -> connect()
            DeepLink.Toggle -> if (running) pendingLink = DeepLink.Disconnect else connect()
            DeepLink.Disconnect -> if (running) pendingLink = link
            else -> pendingLink = link
        }
    }

    fun checkUpdates(manual: Boolean) {
        if (updateBusy != null) return
        updateBusy = "Проверка…"
        viewModelScope.launch {
            val result = App.updates.check()
            updateBusy = null
            if (manual) toast(
                result.fold(
                    onSuccess = { it?.let { u -> "Доступна версия ${u.versionName}" } ?: "У вас последняя версия" },
                    onFailure = { "Не удалось проверить: ${it.message}" },
                ),
            )
        }
    }

    fun installUpdate(context: Context) {
        val info = App.updates.available.value ?: return
        if (!App.updates.canInstall()) {
            toast("Разрешите установку из RVC и нажмите ещё раз")
            runCatching { context.startActivity(App.updates.installPermissionIntent()) }
            return
        }
        if (updateBusy != null) return
        updateBusy = "Загрузка…"
        viewModelScope.launch {
            App.updates.download(info)
                .onSuccess { apk -> runCatching { context.startActivity(App.updates.installIntent(apk)) }.onFailure { toast("Не удалось открыть установщик") } }
                .onFailure { toast("Обновление не установлено: ${it.message}") }
            updateBusy = null
        }
    }

    fun confirmLink(accept: Boolean) {
        val link = pendingLink ?: return
        pendingLink = null
        if (accept) handle(link)
    }

    private fun handle(link: DeepLink) {
        when (link) {
            is DeepLink.AddSubscription -> addSubscription(link.url, link.name, if (link.hidden) "crypt1" else null)
            is DeepLink.ImportNodes -> importNodes(link.text)
            is DeepLink.Routing -> applyRouting(link.command)
            DeepLink.Connect -> connect()
            DeepLink.Disconnect -> disconnect()
            DeepLink.Toggle -> toggle()
            DeepLink.Status -> Unit
        }
    }

    fun addSubscription(url: String, name: String? = null, via: String? = null) {
        updating = true
        viewModelScope.launch {
            App.subscriptions.add(url, name, via)
                .onSuccess { sub ->
                    toast("Добавлено: ${sub.title}")
                    if (store.selected.value == null) store.nodes.value[sub.id]?.firstOrNull()?.let(store::select)
                    pingAll(store.nodes.value[sub.id].orEmpty())
                }
                .onFailure { toast(it.message ?: "Не удалось добавить подписку") }
            updating = false
        }
    }

    private fun importNodes(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val count = App.subscriptions.importNodes(text)
            withContext(Dispatchers.Main) {
                if (count > 0) {
                    toast("Добавлено серверов: $count")
                    if (store.selected.value == null) store.nodes.value[ProxyNode.MANUAL_SOURCE]?.lastOrNull()?.let(store::select)
                } else {
                    toast("Новых серверов не найдено")
                }
            }
        }
    }

    fun applyRouting(command: RoutingCommand) {
        viewModelScope.launch {
            when (val r = App.routing.apply(command)) {
                is RoutingRepository.Outcome.Imported -> toast(if (r.activated) "Маршрутизация «${r.profile.name}» включена" else "Профиль «${r.profile.name}» добавлен")
                RoutingRepository.Outcome.Disabled -> toast("Маршрутизация выключена")
                is RoutingRepository.Outcome.Failed -> toast(r.message)
            }
            reconnectIfRunning()
        }
    }

    fun importRoutingText(text: String) {
        val t = text.trim()
        val command = if (RoutingLinks.isRoutingLink(t)) RoutingLinks.parse(t) else RoutingCommand.Import(t, activate = true, autoUpdate = false)
        command?.let(::applyRouting) ?: toast("Профиль не распознан")
    }

    /** Изменения, влияющие на конфигурацию ядра, применяются переподключением, как у Happ. */
    fun reconnectIfRunning() {
        val state = VpnBus.state.value
        if (state is VpnState.Connected) RvcVpnService.start(getApplication(), state.node)
    }

    fun updateSettings(reconnect: Boolean = false, block: (AppSettings) -> AppSettings) {
        store.updateSettings(block)
        if (reconnect) reconnectIfRunning()
    }

    // endregion

    /** Поведение при открытии приложения (настраивается провайдером: Happ subscription-*-onopen, autoconnect). */
    fun onAppOpen(context: Context) {
        val settings = store.settings.value
        val now = System.currentTimeMillis()
        checkWhitelist()
        // «Обновлять при открытии» (Happ subscription-auto-update-open-enable) — все подписки, не чаще раза в 5 минут.
        if (settings.updateOnOpen && now - lastOpenUpdate > 5 * 60_000 && store.subscriptions.value.isNotEmpty()) {
            lastOpenUpdate = now
            viewModelScope.launch { App.subscriptions.updateAll(force = true) }
        }
        if (settings.pingOnOpen) viewModelScope.launch { delay(600); pingAll() }
        val autoconnect = store.subscriptions.value.firstNotNullOfOrNull { it.meta.autoconnect } ?: settings.autoConnect
        if (autoconnect && VpnBus.state.value == VpnState.Idle && app.rvc.vpn.RvcVpnService.prepare(context) == null) {
            val type = store.subscriptions.value.firstNotNullOfOrNull { it.meta.autoconnectType } ?: "lastused"
            val node = when (type) {
                "lowestdelay" -> bestNode()
                "random" -> store.allNodes().randomOrNull()
                else -> store.selected.value ?: bestNode()
            }
            node?.let { RvcVpnService.start(context, it) }
        }
    }
}
