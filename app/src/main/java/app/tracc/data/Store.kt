package app.tracc.data

import android.content.Context
import app.tracc.core.model.ProxyNode
import app.tracc.core.routing.RoutingProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File

/**
 * Состояние приложения. Всё, что может выдать сервер или пользователя (URL подписок, узлы с ключами,
 * профили маршрутизации, выбранный узел), хранится в зашифрованных файлах (SecureStore).
 * Чтение — из памяти, запись — сразу на диск.
 */
class Store(context: Context) {
    private val dir = File(context.filesDir, "state").apply { mkdirs() }
    private val nodesDir = File(dir, "nodes").apply { mkdirs() }

    init {
        SecureStore.bind(dir)
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private inline fun <reified T> load(name: String, default: T): T =
        SecureStore.readText(File(dir, name))?.let { runCatching { json.decodeFromString(serializer<T>(), it) }.getOrNull() } ?: default

    /**
     * Запись идёт в одном фоновом потоке: шифрование и диск не тормозят интерфейс, а порядок
     * записей сохраняется. В памяти состояние меняется сразу.
     */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "tracc-store").apply { isDaemon = true } }

    private fun <T> save(name: String, serializer: KSerializer<T>, value: T) {
        writer.execute { runCatching { SecureStore.writeText(File(dir, name), json.encodeToString(serializer, value)) } }
    }

    private val _settings = MutableStateFlow(migrate(load("settings.bin", AppSettings())))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /**
     * Перевод сохранённых настроек на новые значения по умолчанию. Значения по умолчанию записываются
     * в файл явно, поэтому без миграции старые установки так и остались бы на прежних.
     * 1: MTU 1500 → 8500 (1500 было значением по умолчанию; выбранное пользователем другое значение не трогаем).
     */
    private fun migrate(s: AppSettings): AppSettings {
        if (s.defaultsVersion >= DEFAULTS_VERSION) return s
        var m = s
        if (m.defaultsVersion < 1 && m.mtu == 1500) m = m.copy(mtu = DEFAULT_MTU)
        // 1.0.8: прокси в локальной сети только с паролем.
        if (m.defaultsVersion < 2 && m.proxyLan && m.proxyUser.isEmpty()) m = m.copy(proxyUser = "tracc", proxyPass = newProxySecret())
        return m.copy(defaultsVersion = DEFAULTS_VERSION).also { runCatching { save("settings.bin", serializer(), it) } }
    }

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        _settings.update(block)
        save("settings.bin", serializer(), _settings.value)
    }

    private val _subscriptions = MutableStateFlow(load("subscriptions.bin", emptyList<Subscription>()))
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    fun updateSubscriptions(block: (List<Subscription>) -> List<Subscription>) {
        synchronized(this) {
            _subscriptions.update(block)
            save("subscriptions.bin", serializer(), _subscriptions.value)
        }
    }

    fun updateSubscription(id: String, block: (Subscription) -> Subscription) =
        updateSubscriptions { list -> list.map { if (it.id == id) block(it) else it } }

    private val _nodes = MutableStateFlow(loadAllNodes())
    /** Узлы по подпискам (ключ — id подписки, ProxyNode.MANUAL_SOURCE — добавленные вручную). */
    val nodes: StateFlow<Map<String, List<ProxyNode>>> = _nodes.asStateFlow()

    private fun loadAllNodes(): Map<String, List<ProxyNode>> =
        nodesDir.listFiles().orEmpty().filter { it.name.endsWith(".bin") }.associate { f ->
            f.name.removeSuffix(".bin") to (SecureStore.readText(f)?.let {
                runCatching { json.decodeFromString(serializer<List<ProxyNode>>(), it) }.getOrNull()
            } ?: emptyList())
        }

    fun setNodes(sourceId: String, list: List<ProxyNode>) {
        synchronized(this) {
            _nodes.update { it + (sourceId to list) }
            writer.execute { runCatching { SecureStore.writeText(File(nodesDir, "$sourceId.bin"), json.encodeToString(serializer<List<ProxyNode>>(), list)) } }
        }
    }

    fun removeNodes(sourceId: String) {
        synchronized(this) {
            _nodes.update { it - sourceId }
            writer.execute { File(nodesDir, "$sourceId.bin").delete() }
        }
    }

    fun allNodes(): List<ProxyNode> = _nodes.value.values.flatten()

    fun findNode(id: String?): ProxyNode? = id?.let { nid -> allNodes().firstOrNull { it.id == nid } }

    private val _selected = MutableStateFlow(load<SelectedNode?>("selected.bin", null)?.node)
    val selected: StateFlow<ProxyNode?> = _selected.asStateFlow()

    fun select(node: ProxyNode?) {
        _selected.value = node
        save("selected.bin", serializer(), node?.let(::SelectedNode))
    }

    private val _routing = MutableStateFlow(load("routing.bin", emptyList<RoutingProfile>()))
    /** Профили, добавленные пользователем или пришедшие с подписками (встроенные — в RoutingProfile.BUILT_IN). */
    val routing: StateFlow<List<RoutingProfile>> = _routing.asStateFlow()

    fun updateRouting(block: (List<RoutingProfile>) -> List<RoutingProfile>) {
        synchronized(this) {
            _routing.update(block)
            save("routing.bin", serializer(), _routing.value)
        }
    }

    val allRoutingProfiles: List<RoutingProfile> get() = RoutingProfile.BUILT_IN + _routing.value

    fun activeRouting(): RoutingProfile? {
        val s = _settings.value
        if (!s.routingEnabled) return null
        return allRoutingProfiles.firstOrNull { it.id == s.activeRoutingId } ?: RoutingProfile.RUSSIA
    }

    init {
        // Файлы старого формата (каждый — через Keystore) переписываются на ключ данных в фоне.
        writer.execute { runCatching { SecureStore.upgrade(dir.walkTopDown().filter { it.isFile && it.name.endsWith(".bin") && it.name != "dek.bin" }.toList()) } }
    }

    private val pingFile = File(dir, "ping.json")
    private val _pings = MutableStateFlow(
        runCatching { json.decodeFromString(serializer<Map<String, PingResult>>(), pingFile.readText()) }.getOrDefault(emptyMap()),
    )
    val pings: StateFlow<Map<String, PingResult>> = _pings.asStateFlow()

    fun putPings(results: Map<String, PingResult>) {
        _pings.update { it + results }
        val snapshot = _pings.value
        writer.execute { runCatching { pingFile.writeText(json.encodeToString(serializer<Map<String, PingResult>>(), snapshot)) } }
    }

    // Объявления (`a-…`) раньше попадали в «Сообщения»; теперь они на главном экране.
    private val _notices = MutableStateFlow(load("notices.bin", emptyList<ProviderNotice>()).filterNot { it.id.startsWith("a-") })
    val notices: StateFlow<List<ProviderNotice>> = _notices.asStateFlow()

    fun addNotices(items: List<ProviderNotice>) {
        if (items.isEmpty()) return
        synchronized(this) {
            _notices.update { old -> (items + old).distinctBy { it.id }.take(50) }
            save("notices.bin", serializer(), _notices.value)
        }
    }

    fun markNoticesRead() {
        synchronized(this) {
            _notices.update { list -> list.map { it.copy(read = true) } }
            save("notices.bin", serializer(), _notices.value)
        }
    }

    var vpnWasOn: Boolean
        get() = File(dir, "vpn_on").exists()
        set(value) {
            if (value) File(dir, "vpn_on").writeText("1") else File(dir, "vpn_on").delete()
        }

    /** Случайный, но постоянный адрес TUN: известные адреса других клиентов (26.26.26.1, 172.19.0.1) — сигнатура VPN. */
    val tunAddress: Pair<String, String>
        get() {
            val f = File(dir, "tun_addr")
            f.takeIf { it.exists() }?.readText()?.split(' ')?.takeIf { it.size == 2 }?.let { return it[0] to it[1] }
            // Подсеть /30 внутри 10.0.0.0/8: .1 — шлюз (он же DNS туннеля), .2 — адрес устройства.
            val r = java.security.SecureRandom()
            val prefix = "10.${r.nextInt(200) + 20}.${r.nextInt(250) + 2}"
            val base = r.nextInt(63) * 4
            val pair = "$prefix.${base + 2}" to "$prefix.${base + 1}"
            f.writeText("${pair.first} ${pair.second}")
            return pair
        }
}
