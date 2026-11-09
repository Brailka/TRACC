package app.tracc.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.tracc.core.model.Protocol
import app.tracc.core.model.ProxyNode
import app.tracc.core.xray.BuildOptions
import app.tracc.core.xray.XrayConfigBuilder
import app.tracc.data.PingType
import app.tracc.net.RustNet
import app.tracc.vpn.VpnBus
import app.tracc.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Пинг серверов. TCP и ICMP считает Rust-модуль; «через прокси» — настоящий HTTP-запрос через
 * отдельный экземпляр ядра: GET меряет само ядро, HEAD — Rust через временный SOCKS этого экземпляра.
 */
object NodeTester {
    // Каждая проверка «через прокси» — отдельный экземпляр ядра (~20 МБ), поэтому не больше четырёх одновременно.
    private const val PROXY_CONCURRENCY = 4

    /**
     * Включён VPN другого приложения (а не TRACC): трафик TRACC идёт через его туннель, и тот сам отвечает
     * на TCP-рукопожатия и ICMP за 1 мс. Такие замеры ничего не говорят о сервере.
     */
    fun foreignVpnActive(context: Context): Boolean {
        val own = VpnBus.state.value.let { it is VpnState.Connected || it is VpnState.Connecting }
        if (own) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun testAll(nodes: List<ProxyNode>, requested: PingType, url: String, foreignVpn: Boolean): Flow<Pair<String, Long>> = channelFlow {
        // Под чужим VPN TCP/ICMP недостоверны — меряем через ядро.
        val type = if (foreignVpn && requested in setOf(PingType.TCP, PingType.ICMP)) PingType.PROXY else requested
        val (udpOnly, rest) = nodes.partition { it.protocol == Protocol.AMNEZIAWG || it.protocol == Protocol.WIREGUARD }
        if (udpOnly.isNotEmpty()) launch(Dispatchers.IO) {
            val gate = Semaphore(16)
            udpOnly.forEach { node ->
                launch {
                    gate.withPermit {
                        // WireGuard через Xray проверяется по-настоящему; AmneziaWG и чужой VPN — только ICMP.
                        val ms = if (node.protocol == Protocol.WIREGUARD && type != PingType.ICMP) proxyDelay(node, url)
                        else runInterruptible { icmpDelay(node.address) }
                        send(node.id to ms)
                    }
                }
            }
        }
        when (type) {
            PingType.TCP -> launch(Dispatchers.IO) {
                val tcpable = rest.filter { it.protocol != Protocol.HYSTERIA2 && !it.isJson }
                val results = runInterruptible { RustNet.tcpPing(tcpable.map { it.address to it.port }) }
                tcpable.zip(results).forEach { (n, ms) -> send(n.id to ms) }
                (rest - tcpable.toSet()).forEach { n -> send(n.id to runInterruptible { proxyDelay(n, url) }) }
            }
            PingType.ICMP -> launch(Dispatchers.IO) {
                val gate = Semaphore(16)
                rest.forEach { node -> launch { gate.withPermit { send(node.id to runInterruptible { icmpDelay(node.address) }) } } }
            }
            PingType.PROXY -> launch(Dispatchers.IO) {
                val gate = Semaphore(PROXY_CONCURRENCY)
                rest.forEach { node -> launch { gate.withPermit { send(node.id to runInterruptible { proxyDelay(node, url) }) } } }
            }
            PingType.PROXY_HEAD -> launch(Dispatchers.IO) {
                val gate = Semaphore(PROXY_CONCURRENCY)
                rest.forEach { node -> launch { gate.withPermit { send(node.id to runInterruptible { headDelay(node, url) }) } } }
            }
        }
    }

    /** GET через отдельный экземпляр ядра (две попытки по одному соединению, лучшая). */
    fun proxyDelay(node: ProxyNode, url: String): Long {
        if (node.usesAwgEngine) return icmpDelay(node.address)
        if (!XrayEngine.isAvailable) return RustNet.tcpPing(listOf(node.address to node.port)).first()
        val config = runCatching { XrayConfigBuilder.build(node, BuildOptions(routing = null), forTest = true) }.getOrNull() ?: return -1
        return XrayEngine.measureDelay(config, url)
    }

    /** HEAD-запрос через временный SOCKS экземпляра ядра — без загрузки тела ответа. */
    fun headDelay(node: ProxyNode, url: String): Long {
        if (node.usesAwgEngine) return icmpDelay(node.address)
        return ProbeProxy.use(node) { proxy -> RustNet.delay(url, proxy, method = "HEAD") } ?: -1
    }

    /** Проверка работающего туннеля: запрос идёт через запущенное ядро. */
    fun checkTunnel(url: String): Long = XrayEngine.measureRunning(url)

    /** Две ICMP-попытки: первая может теряться при пробуждении радио или заполнении ARP-кэша. */
    private fun icmpDelay(host: String): Long {
        val first = RustNet.icmpPing(host, timeoutMs = 2_000)
        if (first > 0) return first
        return RustNet.icmpPing(host, timeoutMs = 3_000)
    }
}
