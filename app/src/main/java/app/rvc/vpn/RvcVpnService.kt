package app.rvc.vpn

import app.rvc.core.xray.ProxyListen
import app.rvc.data.ConnectionMode
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import app.rvc.App
import app.rvc.MainActivity
import app.rvc.R
import app.rvc.core.model.ProxyNode
import app.rvc.core.parser.Base64Util
import app.rvc.core.parser.WgConf
import app.rvc.core.xray.BuildOptions
import app.rvc.core.xray.XrayConfigBuilder
import app.rvc.data.PerAppMode
import app.rvc.engine.AwgEngine
import app.rvc.engine.GeoManager
import app.rvc.engine.NodeTester
import app.rvc.engine.XrayEngine
import app.rvc.net.RustNet
import app.rvc.ui.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress

class RvcVpnService : VpnService() {
    companion object {
        const val ACTION_START = "app.rvc.action.START"
        const val ACTION_STOP = "app.rvc.action.STOP"
        const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 7
        private const val CHECK_EVERY_S = 60
        private const val STALL_CHECKS = 3

        /**
         * Запрос разрешения на VPN. В режиме прокси VPN не поднимается, и разрешение не нужно —
         * поэтому все места запуска (кнопка, плитка, ярлыки, автозапуск) спрашивают здесь, а не у VpnService.
         */
        fun prepare(context: Context): Intent? =
            if (App.store.settings.value.connectionMode == ConnectionMode.PROXY) null else VpnService.prepare(context)

        /** Адреса, по которым доступен прокси: 127.0.0.1 и, если включён доступ из сети, адреса Wi-Fi и точки доступа. */
        fun proxyAddresses(): List<String> {
            val s = App.store.settings.value
            val lan = if (!s.proxyLan) emptyList() else runCatching {
                java.net.NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("rmnet") }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress.orEmpty() }
            }.getOrDefault(emptyList())
            return listOf("127.0.0.1") + lan
        }

        fun start(context: Context, node: ProxyNode) {
            App.store.select(node)
            context.startForegroundService(Intent(context, RvcVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RvcVpnService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Маршруты 0.0.0.0/0 без частных сетей: локальная сеть, принтеры и Chromecast работают напрямую
         * и не попадают в туннель. Классический список, как у WireGuard для Android до API 33.
         */
        private val PUBLIC_V4 = listOf(
            "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3", "64.0.0.0/3",
            "96.0.0.0/6", "100.0.0.0/10", "100.128.0.0/9", "101.0.0.0/8", "102.0.0.0/7", "104.0.0.0/5",
            "112.0.0.0/5", "120.0.0.0/6", "124.0.0.0/7", "126.0.0.0/8", "128.0.0.0/3", "160.0.0.0/5",
            "168.0.0.0/8", "169.0.0.0/9", "169.128.0.0/10", "169.192.0.0/11", "169.224.0.0/12", "169.240.0.0/13",
            "169.248.0.0/14", "169.252.0.0/15", "169.255.0.0/16", "170.0.0.0/7", "172.0.0.0/12", "172.32.0.0/11",
            "172.64.0.0/10", "172.128.0.0/9", "173.0.0.0/8", "174.0.0.0/7", "176.0.0.0/4", "192.0.0.0/9",
            "192.128.0.0/11", "192.160.0.0/13", "192.169.0.0/16", "192.170.0.0/15", "192.172.0.0/14",
            "192.176.0.0/12", "192.192.0.0/10", "193.0.0.0/8", "194.0.0.0/7", "196.0.0.0/6", "200.0.0.0/5",
            "208.0.0.0/4",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var monitor: Job? = null
    private var lastNotification: String? = null
    @Volatile private var stopped = false
    @Volatile private var lastFailover = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            App.store.vpnWasOn = false
            stopVpn()
            return START_NOT_STICKY
        }
        val node = App.store.selected.value
        startForegroundCompat(notification(node?.name ?: getString(R.string.app_name), "Подключение…"))
        if (node == null) {
            fail("Сначала выберите сервер", null)
            return START_NOT_STICKY
        }
        stopped = false
        scope.launch { connect(node) }
        return START_STICKY
    }

    private fun connect(node: ProxyNode): Unit = synchronized(this) {
        if (stopped) return
        val previous = tun
        tun = null
        teardownEngines()
        VpnBus.state.value = VpnState.Connecting(node)
        updateNotification(node.name, "Подключение…")
        runCatching { establishAndStart(node, previous) }.onFailure { e ->
            // Новый интерфейс не поднялся: прежний остаётся как «заглушка» kill switch или закрывается.
            if (tun == null && previous != null) {
                if (App.store.settings.value.killSwitch) tun = previous else runCatching { previous.close() }
            }
            fail(e.message ?: "Не удалось подключиться", node)
        }
    }

    private fun establishAndStart(node: ProxyNode, previous: ParcelFileDescriptor?) {
        val store = App.store
        val settings = store.settings.value
        val sub = store.subscriptions.value.firstOrNull { it.id == node.sourceId }
        val meta = sub?.meta
        val target = resolveServer(node, meta?.serverResolve)

        var config: String? = null
        var awg: Pair<WgConf, String>? = null
        if (target.usesAwgEngine) {
            val conf = WgConf.parse(Base64Util.decodeToString(target.raw.substringAfter("awg-conf:")).orEmpty())
                ?: error("Конфигурация AmneziaWG повреждена")
            val ip = resolveIp(target.address) ?: error("Не удалось найти адрес сервера ${target.address}")
            awg = conf to (if (':' in ip) "[$ip]:${target.port}" else "$ip:${target.port}")
        } else {
            val routing = if (meta?.routingEnabled == false) null else store.activeRouting()
            val options = BuildOptions(
                routing = routing,
                mtu = meta?.tunMtu ?: settings.mtu,
                sniffing = meta?.sniffing ?: settings.sniffing,
                fragmentation = meta?.fragmentation ?: settings.fragmentation,
                noises = meta?.noises ?: settings.noises,
                mux = meta?.mux,
                ipv6 = settings.ipv6,
                logLevel = if (app.rvc.BuildConfig.DEBUG) "warning" else "none",
                proxy = if (settings.connectionMode == ConnectionMode.PROXY) {
                    ProxyListen(settings.proxyPort, settings.proxyLan, settings.proxyUser, settings.proxyPass)
                } else {
                    null
                },
            )
            val raw = XrayConfigBuilder.build(target, options)
            config = GeoManager.prepare(this, raw, routing)
        }

        if (settings.connectionMode == ConnectionMode.PROXY) {
            check(awg == null) { "AmneziaWG работает только в режиме VPN" }
            // Без TUN: ядро слушает порт прокси, системный VPN не поднимается.
            runCatching { previous?.close() }
            XrayEngine.start(config!!, 0).getOrElse { throw IllegalStateException("Ядро не запустилось: ${it.message}") }
            VpnBus.state.value = VpnState.Connected(node, System.currentTimeMillis())
            App.store.vpnWasOn = true
            updateNotification(node.name, "Прокси · порт ${settings.proxyPort}")
            monitor = scope.launch { monitorLoop(node) }
            return
        }

        val (address, gateway) = store.tunAddress
        val mtu = if (awg != null) awg.first.mtu ?: 1420 else meta?.tunMtu ?: settings.mtu
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(mtu)

        if (awg != null) {
            // У WireGuard адрес интерфейса задаёт сервер: лишний адрес сломал бы выбор адреса отправителя.
            val addrs = awg.first.addresses
            addrs.forEach { a ->
                runCatching { builder.addAddress(a.substringBefore('/'), a.substringAfter('/', if (':' in a) "128" else "32").toInt()) }
            }
            awg.first.dns.ifEmpty { listOf("1.1.1.1") }.forEach { runCatching { builder.addDnsServer(it) } }
            builder.addRoute("0.0.0.0", 0)
            // Без IPv6-адреса Android сам блокирует IPv6 в туннеле, поэтому утечки нет.
            if (addrs.any { ':' in it }) builder.addRoute("::", 0)
        } else {
            builder.addAddress(address, 30)
            builder.addAddress("fd${address.split('.')[1].toInt().toString(16).padStart(2, '0')}::2", 126)
            builder.addDnsServer(gateway)
            if (settings.bypassLan && Build.VERSION.SDK_INT < 33) {
                PUBLIC_V4.forEach { builder.addRoute(it.substringBefore('/'), it.substringAfter('/').toInt()) }
            } else {
                builder.addRoute("0.0.0.0", 0)
            }
            // Шлюз туннеля (DNS) маршрутизируется в туннель всегда, даже если 10.0.0.0/8 исключена.
            builder.addRoute(gateway, 32)
            // IPv6 всегда в туннель: иначе трафик по IPv6 ушёл бы мимо VPN.
            builder.addRoute("::", 0)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val excluded = buildList {
                if (settings.bypassLan) addAll(listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "fe80::/10"))
                meta?.excludeRoutes?.let { addAll(it) }
                if (settings.hideVpnIcon) addAll(listOf("0.0.0.0/8", "::/128"))
            }
            excluded.forEach { cidr ->
                runCatching {
                    val ip = InetAddress.getByName(cidr.substringBefore('/'))
                    val len = cidr.substringAfter('/', if (ip is Inet4Address) "32" else "128").toInt()
                    builder.excludeRoute(IpPrefix(ip, len))
                }
            }
        }

        when (settings.perAppMode) {
            PerAppMode.PROXY -> {
                val list = settings.perAppList - packageName
                if (list.isEmpty()) runCatching { builder.addDisallowedApplication(packageName) }
                list.forEach { runCatching { builder.addAllowedApplication(it) } }
            }
            else -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                if (settings.perAppMode == PerAppMode.BYPASS) settings.perAppList.forEach { runCatching { builder.addDisallowedApplication(it) } }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val fd = builder.establish() ?: error("Нет разрешения на VPN")
        tun = fd
        runCatching { previous?.close() }

        if (awg != null) {
            AwgEngine.start(this, fd, awg.first, awg.second).getOrThrow()
        } else {
            XrayEngine.start(config!!, fd.fd).getOrElse { throw IllegalStateException("Ядро не запустилось: ${it.message}") }
        }

        VpnBus.state.value = VpnState.Connected(node, System.currentTimeMillis())
        App.store.vpnWasOn = true
        updateNotification(node.name, "Подключено")
        monitor = scope.launch { monitorLoop(node) }
    }

    /** Передаём AmneziaWG собственную копию дескриптора: движок закрывает его сам при остановке. */

    /** Адрес сервера через DoH провайдера (server-address-resolve), чтобы обойти подмену DNS. */
    private fun resolveServer(node: ProxyNode, resolve: app.rvc.core.provider.ServerResolve?): ProxyNode {
        if (resolve?.enabled != true || node.isJson) return node
        if (node.address.all { it.isDigit() || it == '.' } || ':' in node.address) return node
        val url = resolve.dohUrl ?: "https://cloudflare-dns.com/dns-query"
        val ip = RustNet.dohResolve(node.address, url, resolve.dohIp).firstOrNull() ?: return node
        val params = if (node.params["sni"].isNullOrBlank() && node.security in setOf("tls", "reality")) node.params + ("sni" to node.address) else node.params
        return node.copy(address = ip, params = params)
    }

    private fun resolveIp(host: String): String? {
        if (host.all { it.isDigit() || it == '.' } || ':' in host) return host
        return runCatching { InetAddress.getAllByName(host).sortedBy { it !is Inet4Address }.first().hostAddress }.getOrNull()
            ?: RustNet.dohResolve(host, "https://cloudflare-dns.com/dns-query", "1.1.1.1").firstOrNull()
    }

    private suspend fun monitorLoop(node: ProxyNode) {
        val power = getSystemService(PowerManager::class.java)
        var upTotal = 0L
        var downTotal = 0L
        var lastAwg: Pair<Long, Long>? = null
        var failures = 0
        var lastCheck = System.currentTimeMillis()
        var lastTick = System.currentTimeMillis()
        var lastSpeedText: String? = null
        while (scope.isActive && !stopped) {
            // Скорость раз в секунду нужна, только пока её видно в приложении. Свёрнутому приложению
            // хватит уведомления раз в 3 с, а при выключенном экране счётчики нужны лишь для суммы.
            val interactive = power.isInteractive
            delay(
                when {
                    !interactive -> 10_000L
                    VpnBus.traffic.subscriptionCount.value > 0 -> 1_000L
                    else -> 3_000L
                },
            )
            val (up, down) = if (AwgEngine.isRunning) {
                val now = AwgEngine.stats() ?: (0L to 0L)
                val prev = lastAwg ?: now
                lastAwg = now
                (now.second - prev.second).coerceAtLeast(0) to (now.first - prev.first).coerceAtLeast(0)
            } else {
                XrayEngine.takeTraffic() ?: (0L to 0L)
            }
            upTotal += up
            downTotal += down
            val tickNow = System.currentTimeMillis()
            val elapsed = (tickNow - lastTick).coerceAtLeast(1)
            lastTick = tickNow
            val upBps = up * 1000 / elapsed
            val downBps = down * 1000 / elapsed
            VpnBus.traffic.value = Traffic(upBps, downBps, upTotal, downTotal, tickNow)
            // Уведомление публикуется заново только при новом тексте: в простое скорость не меняется,
            // а каждая публикация — работа и для приложения, и для системного интерфейса.
            val speedText = "↓ ${formatSpeed(downBps)}   ↑ ${formatSpeed(upBps)}"
            if (interactive && speedText != lastSpeedText) {
                lastSpeedText = speedText
                updateNotification(node.name, speedText)
            }
            // Проверка связи будит радиомодуль: раз в минуту, пока приложение открыто, раз в 3 минуты при
            // включённом экране, раз в 5 минут при выключенном; после неудачи — через 15 секунд.
            val every = when {
                failures > 0 -> 15_000L
                interactive && VpnBus.traffic.subscriptionCount.value > 0 -> CHECK_EVERY_S * 1000L
                interactive -> 3 * 60_000L
                else -> 5 * 60_000L
            }
            val now = System.currentTimeMillis()
            if (now - lastCheck >= every) {
                lastCheck = now
                val ok = if (AwgEngine.isRunning) {
                    System.currentTimeMillis() / 1000 - AwgEngine.lastHandshake() < 180
                } else {
                    NodeTester.checkTunnel(App.store.settings.value.testUrl) > 0
                }
                failures = if (ok) 0 else failures + 1
                VpnBus.failedChecks.value = failures
                if (failures >= STALL_CHECKS) {
                    updateNotification(node.name, "Сервер не отвечает")
                    lastSpeedText = null
                    if (App.store.settings.value.autoFailover) failover(node)
                }
            }
        }
    }

    /** Переключение на лучший по пингу узел той же подписки, не чаще раза в 2 минуты. */
    private fun failover(stalled: ProxyNode) {
        val now = System.currentTimeMillis()
        if (now - lastFailover < 120_000) return
        lastFailover = now
        val pings = App.store.pings.value
        val candidate = App.store.nodes.value[stalled.sourceId].orEmpty()
            .filter { it.id != stalled.id }
            .filter { (pings[it.id]?.ms ?: -1) > 0 }
            .minByOrNull { pings[it.id]!!.ms } ?: return
        App.store.select(candidate)
        scope.launch { connect(candidate) }
    }

    private fun teardownEngines() {
        monitor?.cancel()
        monitor = null
        XrayEngine.stop()
        AwgEngine.stop(this)
        VpnBus.traffic.value = Traffic()
        VpnBus.failedChecks.value = 0
    }

    private fun stopVpn() {
        synchronized(this) {
            stopped = true
            teardownEngines()
            runCatching { tun?.close() }
            tun = null
        }
        VpnBus.state.value = VpnState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Kill switch: при сбое интерфейс VPN остаётся поднятым без ядра — трафик приложений блокируется,
     * а не уходит в обычную сеть. Снять блокировку можно кнопкой «Отключить» или переподключением.
     */
    private fun fail(message: String, node: ProxyNode?) {
        teardownEngines()
        VpnBus.state.value = VpnState.Failed(message, node)
        if (App.store.settings.value.killSwitch && tun != null && !stopped) {
            updateNotification(node?.name ?: getString(R.string.app_name), "Ошибка: $message. Трафик заблокирован до переподключения")
            return
        }
        App.store.vpnWasOn = false
        runCatching { tun?.close() }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        App.store.vpnWasOn = false
        stopVpn()
    }

    override fun onDestroy() {
        synchronized(this) {
            teardownEngines()
            runCatching { tun?.close() }
            tun = null
        }
        scope.cancel()
        if (VpnBus.state.value !is VpnState.Failed) VpnBus.state.value = VpnState.Idle
        super.onDestroy()
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RvcVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "Отключить", stop).build())
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val key = "$title\n$text"
        if (key == lastNotification) return
        lastNotification = key
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, text))
    }

    private fun startForegroundCompat(n: Notification) {
        lastNotification = null
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }
}
