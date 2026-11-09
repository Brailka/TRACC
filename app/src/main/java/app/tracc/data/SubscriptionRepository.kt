package app.tracc.data

import android.content.Context
import app.tracc.core.model.ProxyNode
import app.tracc.core.parser.LinkParser
import app.tracc.core.parser.SubscriptionParser
import app.tracc.core.provider.PerAppDirective
import app.tracc.core.provider.ProviderMeta
import app.tracc.core.routing.RoutingCommand
import app.tracc.core.routing.RoutingLinks
import app.tracc.net.FetchRequest
import app.tracc.net.FetchResponse
import app.tracc.net.RustNet
import app.tracc.vpn.VpnBus
import app.tracc.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Параметры после `#` в адресе подписки (Happ): имя, фронтинг и фрагментация запроса. */
data class SubscriptionUrl(val fetchUrl: String, val name: String?, val resolveAddress: String?, val host: String?) {
    companion object {
        fun parse(url: String): SubscriptionUrl {
            val base = url.substringBefore('#')
            val fragment = url.substringAfter('#', "")
            val name = fragment.substringBefore('?').let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.trim()
            val q = fragment.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
            return SubscriptionUrl(base, name.ifBlank { null }, q["resolve-address"], q["host"])
        }
    }
}

class SubscriptionRepository(private val context: Context, private val store: Store, private val routing: RoutingRepository) {

    private fun headers(meta: ProviderMeta?): Map<String, String> {
        return mapOf("User-Agent" to Hwid.USER_AGENT, "Accept" to "*/*")
    }

    /** HWID уходит всегда: без него Remnawave с включённым лимитом устройств не отдаёт подписку. */
    private fun privateHeaders(): Map<String, String> = Hwid.headers(context, sendHwid = true)

    suspend fun add(url: String, name: String? = null, via: String? = null): Result<Subscription> = withContext(Dispatchers.IO) {
        val clean = url.trim()
        store.subscriptions.value.firstOrNull { it.url == clean }?.let { existing ->
            return@withContext update(existing.id).map { store.subscriptions.value.first { it.id == existing.id } }
        }
        val parsedUrl = SubscriptionUrl.parse(clean)
        val sub = Subscription(id = UUID.randomUUID().toString().take(12), url = clean, name = name ?: parsedUrl.name, importedVia = via)
        store.updateSubscriptions { it + sub }
        update(sub.id).fold(
            onSuccess = { Result.success(store.subscriptions.value.first { it.id == sub.id }) },
            onFailure = { e ->
                if (store.nodes.value[sub.id].isNullOrEmpty()) {
                    store.updateSubscriptions { list -> list.filterNot { it.id == sub.id } }
                    store.removeNodes(sub.id)
                }
                Result.failure(e)
            },
        )
    }

    fun remove(id: String) {
        store.updateSubscriptions { list -> list.filterNot { it.id == id } }
        store.removeNodes(id)
        routing.removeForSubscription(id)
        if (store.selected.value?.sourceId == id) store.select(null)
        stopIfRunning { it.sourceId == id }
    }

    /** Туннель на сервере, которого больше нет в списке, оставлять нельзя: пользователь его удалил. */
    private fun stopIfRunning(removed: (ProxyNode) -> Boolean) {
        val node = when (val s = VpnBus.state.value) {
            is VpnState.Connected -> s.node
            is VpnState.Connecting -> s.node
            is VpnState.Failed -> s.node
            VpnState.Idle -> null
        } ?: return
        if (removed(node)) app.tracc.vpn.TraccVpnService.stop(context)
    }

    suspend fun updateAll(force: Boolean = true): Int = withContext(Dispatchers.IO) {
        var ok = 0
        for (sub in store.subscriptions.value) {
            val due = force || System.currentTimeMillis() - sub.updatedAt > sub.effectiveIntervalHours * 3_600_000L
            if (due && update(sub.id).isSuccess) ok++
        }
        ok
    }

    suspend fun update(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val sub = store.subscriptions.value.firstOrNull { it.id == id } ?: return@withContext Result.failure(IllegalStateException("нет подписки"))
        val target = SubscriptionUrl.parse(sub.url)
        val timeout = (sub.meta.requestTimeoutSec ?: 12) * 1000L

        fun request(url: String, proxy: String? = null) = FetchRequest(
            url = url,
            headers = headers(sub.meta),
            privateHeaders = privateHeaders(),
            timeoutMs = timeout,
            proxy = proxy,
            resolve = target.resolveAddress?.let { addr ->
                val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
                if (addr.all { it.isDigit() || it == '.' || it == ':' }) mapOf(host to addr) else emptyMap()
            }.orEmpty(),
            hostHeader = target.host,
        )

        val candidates = listOfNotNull(target.fetchUrl, sub.meta.fallbackUrl).distinct()
        var response: FetchResponse? = null
        for (url in candidates) {
            val r = RustNet.fetch(request(url))
            response = r
            if (r.ok) break
            // На 404/410 подписка удалена провайдером — запасные адреса не помогут.
            if (r.status == 404 || r.status == 410) break
        }
        if (response?.ok != true && response?.status !in setOf(404, 410)) {
            throughTunnel { proxy -> RustNet.fetch(request(target.fetchUrl, proxy)) }?.let { if (it.ok) response = it }
        }
        val r = response ?: FetchResponse(error = "нет ответа")
        if (!r.ok) {
            val message = when {
                r.headers["x-hwid-max-devices-reached"] == "true" -> "Достигнут лимит устройств для подписки"
                r.headers["x-hwid-not-supported"] == "true" -> "Провайдер требует HWID"
                r.status == 404 -> "Подписка не найдена (404)"
                r.status > 0 -> "Сервер ответил HTTP ${r.status}"
                else -> r.error ?: "Не удалось загрузить подписку"
            }
            store.updateSubscription(id) { it.copy(error = message) }
            return@withContext Result.failure(IllegalStateException(message))
        }
        val body = r.body.orEmpty()
        val parsed = SubscriptionParser.parse(body, id, r.headers)
        if (parsed.nodes.isEmpty() && parsed.notices.isEmpty()) {
            val message = when {
                parsed.meta.hwidMaxDevicesReached -> "Достигнут лимит устройств для подписки"
                parsed.meta.hwidNotSupported -> "Провайдер требует HWID"
                body.trimStart().startsWith("<") -> "Вместо подписки пришла веб-страница"
                else -> "В подписке нет серверов"
            }
            store.updateSubscription(id) { it.copy(error = message, meta = parsed.meta, rawMeta = parsed.rawMeta) }
            return@withContext Result.failure(IllegalStateException(message))
        }

        val previous = store.nodes.value[id].orEmpty()
        store.setNodes(id, parsed.nodes)
        refreshSelection(previous, parsed.nodes)
        val migrated = migratedUrl(sub.url, parsed.meta)
        store.updateSubscription(id) {
            it.copy(
                url = migrated ?: it.url,
                updatedAt = System.currentTimeMillis(),
                error = when {
                    parsed.meta.hwidMaxDevicesReached -> "Достигнут лимит устройств для подписки"
                    parsed.meta.hwidNotSupported && parsed.nodes.isEmpty() -> "Провайдер требует HWID"
                    parsed.nodes.isEmpty() -> parsed.notices.firstOrNull() ?: "В подписке нет серверов"
                    else -> null
                },
                meta = parsed.meta,
                rawMeta = parsed.rawMeta,
                notices = parsed.notices,
                nodeCount = parsed.nodes.size,
            )
        }
        applyProviderSettings(id, parsed.meta, parsed.routingLinks)
        ProviderNotifier.onSubscriptionUpdated(context, store, store.subscriptions.value.first { it.id == id })
        Result.success(Unit)
    }

    /** Если сервер узла пропал из подписки, выбор переносится на узел с тем же именем. */
    private fun refreshSelection(previous: List<ProxyNode>, fresh: List<ProxyNode>) {
        val selected = store.selected.value ?: return
        if (previous.none { it.id == selected.id }) return
        val same = fresh.firstOrNull { it.id == selected.id } ?: fresh.firstOrNull { it.name == selected.name }
        if (same != null) store.select(same)
    }

    private fun migratedUrl(current: String, meta: ProviderMeta): String? {
        meta.newUrl?.let { if (it != current.substringBefore('#')) return it + current.substringAfter('#', "").let { f -> if (f.isEmpty()) "" else "#$f" } }
        val domain = meta.newDomain ?: return null
        val scheme = current.substringBefore("://")
        val rest = current.substringAfter("://")
        val authority = rest.substringBefore('/')
        val port = authority.substringAfter(':', "").let { if (it.isNotEmpty()) ":$it" else "" }
        val host = authority.substringBefore(':')
        if (scheme != "https" || host.equals(domain, true)) return null
        return "$scheme://$domain$port" + rest.removePrefix(authority)
    }

    private suspend fun applyProviderSettings(id: String, meta: ProviderMeta, bodyRouting: List<String>) {
        // Приоритет источников маршрутизации как у INCY: autorouting-заголовок → URL в теле → routing-заголовок → base64 в теле.
        val links = buildList {
            meta.autorouting?.let(::add)
            addAll(bodyRouting.filter { "autorouting" in it.lowercase() })
            meta.routing?.let(::add)
            addAll(bodyRouting.filter { "autorouting" !in it.lowercase() })
        }
        if (meta.routingEnabled == false) {
            routing.apply(RoutingCommand.Off, id)
        } else links.firstNotNullOfOrNull { link ->
            val command = if (RoutingLinks.isRoutingLink(link)) RoutingLinks.parse(link) else RoutingCommand.Import(link, activate = true, autoUpdate = false)
            command
        }?.let { routing.apply(it, id) }

        meta.perApp?.let { applyPerApp(it) }
        meta.autoUpdateEnabled?.let { v -> store.updateSettings { it.copy(autoUpdateSubs = v) } }
        meta.updateOnOpen?.let { v -> store.updateSettings { it.copy(updateOnOpen = v) } }
        meta.pingOnOpen?.let { v -> store.updateSettings { it.copy(pingOnOpen = v) } }
        meta.autoconnect?.let { v -> store.updateSettings { it.copy(autoConnect = v) } }
        meta.sniffing?.let { v -> store.updateSettings { it.copy(sniffing = v) } }
        meta.hideVpnIcon?.let { v -> store.updateSettings { it.copy(hideVpnIcon = v) } }
        meta.pingType?.let { type ->
            val mapped = when (type) {
                "tcp" -> PingType.TCP
                "icmp" -> PingType.ICMP
                "proxy-head" -> PingType.PROXY_HEAD
                "proxy" -> PingType.PROXY
                else -> null
            }
            if (mapped != null) store.updateSettings { it.copy(pingType = mapped) }
        }
        meta.checkUrl?.let { url -> store.updateSettings { it.copy(testUrl = url) } }
        meta.sortOrder?.let { order ->
            SortOrder.entries.firstOrNull { it.name.equals(order, true) }?.let { o -> store.updateSettings { it.copy(sortOrder = o) } }
        }
        meta.tunMtu?.let { mtu -> store.updateSettings { it.copy(mtu = mtu) } }
    }

    private fun applyPerApp(d: PerAppDirective) {
        val remote = d.listUrl?.let { url ->
            RustNet.fetch(FetchRequest(url, headers = mapOf("User-Agent" to Hwid.USER_AGENT), timeoutMs = 10_000, maxBytes = 512 * 1024))
                .takeIf { it.ok }?.body?.split(',', '\n', ';', ' ')?.map { it.trim() }?.filter { '.' in it }
        }.orEmpty()
        store.updateSettings { s ->
            var mode = when (d.mode) {
                "proxy" -> PerAppMode.PROXY
                "bypass" -> PerAppMode.BYPASS
                else -> s.perAppMode
            }
            if (d.enabled == false) mode = PerAppMode.OFF
            if (d.enabled == true && mode == PerAppMode.OFF) mode = PerAppMode.BYPASS
            var list = s.perAppList
            d.set?.let { list = it.toSet() }
            list = list + d.add + remote
            // «Все, кроме списка» равносильно противоположному режиму с тем же списком.
            d.invert?.let { inv ->
                list = inv.toSet()
                mode = if (mode == PerAppMode.PROXY) PerAppMode.BYPASS else PerAppMode.PROXY
            }
            s.copy(perAppMode = mode, perAppList = list)
        }
    }

    /**
     * Загрузка через туннель, когда адрес подписки заблокирован. Поднимается отдельный экземпляр ядра
     * с SOCKS на случайном порту и случайными логином/паролем; живёт он только на время запроса.
     */
    private fun <T> throughTunnel(block: (String) -> T): T? {
        if (VpnBus.state.value !is VpnState.Connected) return null
        val node = store.selected.value ?: return null
        return app.tracc.engine.ProbeProxy.use(node, block)
    }

    /** Ручное добавление узлов из текста (ссылки, .conf, JSON). Возвращает число новых. */
    fun importNodes(text: String): Int {
        val parsed = SubscriptionParser.parse(text, ProxyNode.MANUAL_SOURCE)
        val nodes = parsed.nodes.ifEmpty { listOfNotNull(LinkParser.parse(text)) }
        if (nodes.isEmpty()) return 0
        val existing = store.nodes.value[ProxyNode.MANUAL_SOURCE].orEmpty()
        val fresh = nodes.filter { n -> existing.none { it.id == n.id } }
        store.setNodes(ProxyNode.MANUAL_SOURCE, existing + fresh)
        return fresh.size
    }

    fun removeManual(nodeId: String) {
        val existing = store.nodes.value[ProxyNode.MANUAL_SOURCE].orEmpty()
        store.setNodes(ProxyNode.MANUAL_SOURCE, existing.filterNot { it.id == nodeId })
        stopIfRunning { it.id == nodeId }
    }
}
