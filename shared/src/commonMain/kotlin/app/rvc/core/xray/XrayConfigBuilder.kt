package app.rvc.core.xray

import app.rvc.core.model.Protocol
import app.rvc.core.model.ProxyNode
import app.rvc.core.parser.WgConf
import app.rvc.core.provider.Fragmentation
import app.rvc.core.provider.MuxSettings
import app.rvc.core.provider.Noises
import app.rvc.core.routing.RoutingProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * MTU туннеля по умолчанию — 8500, как у Happ и v2rayNG: на каждый пакет через TUN уходит системный вызов,
 * и при 1500 их впятеро больше (замер на телефоне: 2,5–2,8 с процессора на 100 МБ против 1,2–1,4 с).
 */
const val DEFAULT_TUN_MTU = 8500

/** Параметры сборки, общие для всех узлов: из настроек пользователя и заголовков провайдера. */
data class BuildOptions(
    val routing: RoutingProfile? = RoutingProfile.RUSSIA,
    val mtu: Int = DEFAULT_TUN_MTU,
    val sniffing: Boolean = true,
    val fragmentation: Fragmentation? = null,
    val noises: Noises? = null,
    val mux: MuxSettings? = null,
    val ipv6: Boolean = false,
    /** Журнал ядра: в релизе выключен, отладочная сборка передаёт "warning". */
    val logLevel: String = "none",
    /** Режим прокси: вместо TUN — SOCKS5 и HTTP на этом адресе. */
    val proxy: ProxyListen? = null,
    /** TUN на Windows (wintun): адаптер, адреса, маршруты и фильтры утечек настраивает само ядро. */
    val windowsTun: WindowsTun? = null,
)

/**
 * Параметры TUN-входа Xray на Windows. Там нет дескриптора от системы: ядро само создаёт адаптер wintun,
 * назначает ему [gateway] (на Windows это адрес самого интерфейса), ставит [dns], добавляет маршруты
 * [routes] и привязывает свои исходящие соединения к физическому интерфейсу, чтобы не было петли.
 *
 * @param blockLeaks значения `autoSystemWfpBlockLeak`: "dns" — DNS только через туннель,
 *   "misconfigtun" — версия IP без маршрутов в туннель блокируется целиком (нет утечки IPv6).
 */
data class WindowsTun(
    val name: String = "RVC",
    val gateway: List<String>,
    val dns: List<String>,
    val routes: List<String>,
    val blockLeaks: List<String> = emptyList(),
    /** Интерфейс для исходящих ядра (`autoOutboundsInterface`); null — ядро выбирает само по маршруту по умолчанию. */
    val outboundsInterface: String? = null,
)

/** Вход режима прокси. Пустые логин и пароль — без авторизации. */
data class ProxyListen(val port: Int, val lan: Boolean, val user: String, val pass: String)

/**
 * Собирает конфигурацию Xray.
 *
 * Трафик приходит только через встроенный TUN inbound ядра (дескриптор передаётся в StartLoop):
 * ни SOCKS, ни HTTP, ни API не слушают порты на 127.0.0.1, поэтому другие приложения не могут
 * найти прокси сканированием локальных портов или узнать через него внешний IP сервера.
 */
object XrayConfigBuilder {
    const val TUN_TAG = "tun"
    const val PROXY_TAG = "proxy"
    const val DIRECT_TAG = "direct"
    const val BLOCK_TAG = "block"
    private val LOOPBACK = listOf("127.0.0.0/8", "::1/128", "0.0.0.0/32")
    const val DNS_OUT_TAG = "dns-out"
    const val DNS_INTERNAL_TAG = "dns-internal"
    private const val FRAGMENT_TAG = "fragment"

    private val json = Json { ignoreUnknownKeys = true }

    /** Секции, которые никогда не попадают в ядро: открывают порты или пишут чувствительные логи. */
    private val FORBIDDEN_SECTIONS = setOf("api", "metrics", "inbounds")

    /** Служебные outbound'ы, которые не должны оказаться первыми в конфигурации для замера задержки. */
    private val TEST_SKIP = setOf("freedom", "blackhole", "dns", "loopback")

    fun build(node: ProxyNode, options: BuildOptions = BuildOptions(), forTest: Boolean = false): String =
        if (node.isJson) patchFull(node, options, forTest) else generate(node, options, forTest)

    /**
     * Временный экземпляр для запроса через туннель: SOCKS только на 127.0.0.1, случайный порт
     * и обязательная авторизация. Останавливается сразу после запроса.
     */
    fun buildProbe(node: ProxyNode, port: Int, user: String, pass: String): String {
        val base = json.parseToJsonElement(build(node, BuildOptions(routing = null), forTest = true)).jsonObject
        val inbound = buildJsonObject {
            put("tag", "probe")
            put("listen", "127.0.0.1")
            put("port", port)
            put("protocol", "socks")
            putJsonObject("settings") {
                put("auth", "password")
                putJsonArray("accounts") { addJsonObject { put("user", user); put("pass", pass) } }
                put("udp", false)
            }
        }
        return JsonObject(base + ("inbounds" to JsonArray(listOf(inbound)))).toString()
    }

    // region Сгенерированная конфигурация

    private fun generate(node: ProxyNode, o: BuildOptions, forTest: Boolean): String {
        val fragment = o.fragmentation?.takeIf { it.enabled && node.canFragment }
        val noises = o.noises?.takeIf { it.enabled && node.canNoise }
        val dialer = if (fragment != null || noises != null) FRAGMENT_TAG else null
        val proxy = withMux(outbound(node, dialer), o.mux?.takeIf { it.enabled && node.canMux })
        val profile = o.routing
        return buildJsonObject {
            putJsonObject("log") { put("loglevel", o.logLevel); put("access", "none"); put("dnsLog", false) }
            if (!forTest) {
                putJsonArray("inbounds") { add(entryInbound(o, profile?.fakeDns == true)) }
                put("dns", dns(profile, o))
                if (profile?.fakeDns == true) putJsonArray("fakedns") {
                    addJsonObject { put("ipPool", "198.18.0.0/15"); put("poolSize", 65535) }
                }
            }
            putJsonArray("outbounds") {
                add(proxy)
                if (dialer != null) add(fragmentOutbound(fragment, noises))
                addJsonObject {
                    put("tag", DIRECT_TAG)
                    put("protocol", "freedom")
                    putJsonObject("settings") {
                        put("domainStrategy", if (o.ipv6) "UseIP" else "UseIPv4")
                        loopbackFinalRules(o)?.let { put("finalRules", it) }
                    }
                }
                addJsonObject { put("tag", BLOCK_TAG); put("protocol", "blackhole") }
                if (!forTest) addJsonObject { put("tag", DNS_OUT_TAG); put("protocol", "dns") }
            }
            if (!forTest) {
                put("routing", routing(profile, o))
                stats(this)
            }
        }.toString()
    }

    /** Вход трафика: TUN в режиме VPN или прокси в режиме прокси. Тег один — правила маршрутизации общие. */
    private fun entryInbound(o: BuildOptions, fakeDns: Boolean) = o.proxy?.let { proxyInbound(it, o, fakeDns) } ?: tunInbound(o, fakeDns)

    /**
     * Режим прокси: «mixed»-вход Xray принимает и SOCKS5, и HTTP на одном порту. По умолчанию — только
     * 127.0.0.1 и с паролем: это единственный режим, где RVC открывает порт, и открывает его осознанно.
     */
    private fun proxyInbound(p: ProxyListen, o: BuildOptions, fakeDns: Boolean) = buildJsonObject {
        // Без пароля порт в сети — открытый прокси: им воспользуется любой в той же Wi-Fi.
        require(!p.lan || (p.user.isNotEmpty() && p.pass.isNotEmpty())) { "Для доступа из локальной сети нужен пароль прокси" }
        put("tag", TUN_TAG)
        put("listen", if (p.lan) "0.0.0.0" else "127.0.0.1")
        put("port", p.port)
        put("protocol", "mixed")
        putJsonObject("settings") {
            if (p.user.isNotEmpty() && p.pass.isNotEmpty()) {
                put("auth", "password")
                putJsonArray("accounts") { addJsonObject { put("user", p.user); put("pass", p.pass) } }
            } else {
                put("auth", "noauth")
            }
            put("udp", true)
            put("userLevel", 0)
        }
        putJsonObject("sniffing") {
            put("enabled", o.sniffing || fakeDns)
            putJsonArray("destOverride") {
                add("http"); add("tls"); add("quic")
                if (fakeDns) add("fakedns")
            }
            put("routeOnly", !fakeDns)
        }
    }

    /**
     * В режиме прокси адреса самого телефона (127.0.0.0/8, ::1, localhost) закрыты: иначе устройство
     * из Wi-Fi через прокси достучалось бы до локальных портов других приложений на телефоне.
     */
    private fun loopbackGuard(o: BuildOptions): JsonObject? = if (o.proxy == null) null else buildJsonObject {
        putJsonArray("inboundTag") { add(TUN_TAG) }
        putJsonArray("ip") { LOOPBACK.forEach { add(it) } }
        put("outboundTag", BLOCK_TAG)
    }

    private fun loopbackDomainGuard(o: BuildOptions): JsonObject? = if (o.proxy == null) null else buildJsonObject {
        putJsonArray("inboundTag") { add(TUN_TAG) }
        putJsonArray("domain") { add("domain:localhost") }
        put("outboundTag", BLOCK_TAG)
    }

    /** То же на выходе freedom: ловит и домены, которые резолвятся в 127.0.0.1 (localtest.me и т.п.). */
    private fun loopbackFinalRules(o: BuildOptions): JsonArray? = if (o.proxy == null) null else buildJsonArray {
        addJsonObject {
            put("action", "block")
            putJsonArray("ip") { LOOPBACK.forEach { add(it) } }
        }
    }

    private fun guardFreedom(outbound: JsonObject, o: BuildOptions): JsonObject {
        val rules = loopbackFinalRules(o) ?: return outbound
        if (outbound["protocol"]?.str() != "freedom") return outbound
        val settings = (outbound["settings"] as? JsonObject).orEmpty()
        val existing = (settings["finalRules"] as? JsonArray).orEmpty()
        return JsonObject(outbound + ("settings" to JsonObject(settings + ("finalRules" to JsonArray(rules + existing)))))
    }

    private fun tunInbound(o: BuildOptions, fakeDns: Boolean) = buildJsonObject {
        put("tag", TUN_TAG)
        put("protocol", "tun")
        putJsonObject("settings") {
            val win = o.windowsTun
            put("name", win?.name ?: "tun0")
            put("mtu", o.mtu)
            put("userLevel", 0)
            if (win != null) {
                putJsonArray("gateway") { win.gateway.forEach { add(it) } }
                putJsonArray("dns") { win.dns.forEach { add(it) } }
                putJsonArray("autoSystemRoutingTable") { win.routes.forEach { add(it) } }
                if (win.blockLeaks.isNotEmpty()) putJsonArray("autoSystemWfpBlockLeak") { win.blockLeaks.forEach { add(it) } }
                win.outboundsInterface?.let { put("autoOutboundsInterface", it) }
            }
        }
        putJsonObject("sniffing") {
            put("enabled", o.sniffing || fakeDns)
            putJsonArray("destOverride") {
                add("http"); add("tls"); add("quic")
                if (fakeDns) add("fakedns")
            }
            put("routeOnly", !fakeDns)
        }
    }

    private fun stats(builder: kotlinx.serialization.json.JsonObjectBuilder) {
        // Внутренние счётчики для скорости в уведомлении. API наружу не открывается.
        builder.putJsonObject("stats") {}
        builder.putJsonObject("policy") {
            putJsonObject("system") {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            }
        }
    }

    /** DNS по схеме Happ: Remote — через прокси, Domestic — напрямую для прямых ресурсов. */
    private fun dns(profile: RoutingProfile?, o: BuildOptions): JsonObject {
        val p = profile ?: RoutingProfile.GLOBAL
        val remote = dnsServer(p.remoteDnsType, p.remoteDnsDomain, p.remoteDnsIp)
        val domestic = dnsServer(p.domesticDnsType, p.domesticDnsDomain, p.domesticDnsIp)
        val directDomains = p.directSites.filter(::isDomainRule)
        val proxyDomains = p.proxySites.filter(::isDomainRule)
        return buildJsonObject {
            putJsonObject("hosts") {
                p.dnsHosts.forEach { (k, v) -> put(k, v) }
                hostOf(p.remoteDnsDomain)?.let { if (p.remoteDnsType.equals("DoH", true)) put(it, p.remoteDnsIp) }
                if ("dns.google" !in p.dnsHosts) put("dns.google", "8.8.8.8")
                hostOf(p.domesticDnsDomain)?.let { if (p.domesticDnsType.equals("DoH", true)) put(it, p.domesticDnsIp) }
            }
            putJsonArray("servers") {
                if (p.fakeDns) addJsonObject {
                    put("address", "fakedns")
                    if (!p.globalProxy && proxyDomains.isNotEmpty()) putJsonArray("domains") { proxyDomains.forEach { add(it) } }
                }
                if (p.globalProxy) {
                    add(remote)
                    // Запасной DoH через туннель: если основной (часто Cloudflare) недоступен, резолвинг не встаёт.
                    if ("dns.google" !in remote && "8.8.8.8" !in remote) add(JsonPrimitive("https://dns.google/dns-query"))
                    if (directDomains.isNotEmpty()) addJsonObject {
                        put("address", domestic)
                        putJsonArray("domains") { directDomains.forEach { add(it) } }
                        put("skipFallback", true)
                    }
                } else {
                    add(domestic)
                    if (proxyDomains.isNotEmpty()) addJsonObject {
                        put("address", remote)
                        putJsonArray("domains") { proxyDomains.forEach { add(it) } }
                        put("skipFallback", true)
                    }
                }
            }
            put("queryStrategy", if (o.ipv6) "UseIP" else "UseIPv4")
            put("disableFallbackIfMatch", true)
            put("serveStale", true)
            put("tag", DNS_INTERNAL_TAG)
        }
    }

    private fun dnsServer(type: String, domain: String, ip: String): String = when {
        type.equals("DoH", true) && domain.startsWith("https://") -> domain
        type.equals("DoH", true) && domain.isNotBlank() -> "https://${domain.removePrefix("https://")}"
        type.equals("DoH", true) -> "https://$ip/dns-query"
        else -> ip
    }

    private fun hostOf(url: String): String? =
        url.removePrefix("https://").substringBefore('/').substringBefore(':').takeIf { it.isNotBlank() && it.any(Char::isLetter) }

    private fun routing(profile: RoutingProfile?, o: BuildOptions) = buildJsonObject {
        val p = profile
        put("domainStrategy", p?.domainStrategy ?: "AsIs")
        putJsonArray("rules") {
            loopbackGuard(o)?.let(::add)
            loopbackDomainGuard(o)?.let(::add)
            // DNS из туннеля — во встроенный резолвер ядра, наружу открытым UDP он не уходит.
            addJsonObject {
                putJsonArray("inboundTag") { add(TUN_TAG) }
                put("port", "53")
                put("outboundTag", DNS_OUT_TAG)
            }
            if (p != null) {
                val domesticIp = p.domesticDnsIp
                addJsonObject {
                    putJsonArray("inboundTag") { add(DNS_INTERNAL_TAG) }
                    putJsonArray("ip") { add(domesticIp) }
                    put("outboundTag", DIRECT_TAG)
                }
                hostOf(p.domesticDnsDomain)?.let { host ->
                    addJsonObject {
                        putJsonArray("inboundTag") { add(DNS_INTERNAL_TAG) }
                        putJsonArray("domain") { add("full:$host") }
                        put("outboundTag", DIRECT_TAG)
                    }
                }
                addJsonObject {
                    putJsonArray("inboundTag") { add(DNS_INTERNAL_TAG) }
                    put("outboundTag", PROXY_TAG)
                }
            }
            addJsonObject {
                putJsonArray("ip") { RoutingProfile.PRIVATE_IPS.forEach { add(it) } }
                put("outboundTag", DIRECT_TAG)
            }
            if (p != null) for (step in p.routeOrder) {
                val (sites, ips, target) = when (step) {
                    "block" -> Triple(p.blockSites, p.blockIp, BLOCK_TAG)
                    "proxy" -> Triple(p.proxySites, p.proxyIp, PROXY_TAG)
                    else -> Triple(p.directSites, p.directIp, DIRECT_TAG)
                }
                sites.filter(::isDomainRule).takeIf { it.isNotEmpty() }?.let { list ->
                    addJsonObject { putJsonArray("domain") { list.forEach { add(it) } }; put("outboundTag", target) }
                }
                ips.filter { !isDomainRule(it) }.takeIf { it.isNotEmpty() }?.let { list ->
                    addJsonObject { putJsonArray("ip") { list.forEach { add(it) } }; put("outboundTag", target) }
                }
            }
            addJsonObject {
                put("network", "tcp,udp")
                put("outboundTag", if (p == null || p.globalProxy) PROXY_TAG else DIRECT_TAG)
            }
        }
    }

    private fun isDomainRule(rule: String): Boolean {
        val r = rule.trim().lowercase()
        if (r.startsWith("geoip:")) return false
        if (r.startsWith("geosite:") || r.startsWith("domain:") || r.startsWith("full:") || r.startsWith("regexp:") ||
            r.startsWith("keyword:") || r.startsWith("ext:")
        ) return true
        val isIp = r.all { it.isDigit() || it == '.' || it == '/' } || (r.contains(':') && r.all { it.isLetterOrDigit() || it == ':' || it == '/' } && r.count { it == ':' } >= 2)
        return !isIp
    }

    private fun fragmentOutbound(fragment: Fragmentation?, noises: Noises?) = buildJsonObject {
        put("tag", FRAGMENT_TAG)
        put("protocol", "freedom")
        putJsonObject("settings") {
            put("domainStrategy", "UseIPv4")
            if (fragment != null) putJsonObject("fragment") {
                put("packets", fragment.packets)
                put("length", fragment.length)
                put("interval", fragment.interval)
                fragment.maxSplit?.let { put("maxSplit", it) }
            }
            if (noises != null) putJsonArray("noises") {
                addJsonObject {
                    put("type", noises.type)
                    put("packet", noises.packet)
                    put("delay", noises.delay)
                }
            }
        }
        putJsonObject("streamSettings") { putJsonObject("sockopt") { put("tcpNoDelay", true) } }
    }

    private fun withMux(outbound: JsonObject, mux: MuxSettings?): JsonObject = if (mux == null) outbound else JsonObject(
        outbound + ("mux" to buildJsonObject {
            put("enabled", true)
            put("concurrency", mux.tcp ?: 8)
            put("xudpConcurrency", mux.xudp ?: 8)
            put("xudpProxyUDP443", mux.quic ?: "reject")
        }),
    )

    private fun outbound(n: ProxyNode, dialer: String?): JsonObject = when (n.protocol) {
        Protocol.VLESS -> buildJsonObject {
            put("tag", PROXY_TAG)
            put("protocol", "vless")
            putJsonObject("settings") {
                putJsonArray("vnext") {
                    addJsonObject {
                        put("address", n.address)
                        put("port", n.port)
                        putJsonArray("users") {
                            addJsonObject {
                                put("id", n.secret)
                                put("encryption", n.params["encryption"].nb() ?: "none")
                                n.params["flow"].nb()?.let { put("flow", it) }
                            }
                        }
                    }
                }
            }
            put("streamSettings", stream(n, dialer))
        }

        Protocol.VMESS -> buildJsonObject {
            put("tag", PROXY_TAG)
            put("protocol", "vmess")
            putJsonObject("settings") {
                putJsonArray("vnext") {
                    addJsonObject {
                        put("address", n.address)
                        put("port", n.port)
                        putJsonArray("users") {
                            addJsonObject {
                                put("id", n.secret)
                                put("alterId", n.params["aid"]?.toIntOrNull() ?: 0)
                                put("security", n.params["scy"].nb() ?: "auto")
                            }
                        }
                    }
                }
            }
            put("streamSettings", stream(n, dialer))
        }

        Protocol.TROJAN -> buildJsonObject {
            put("tag", PROXY_TAG)
            put("protocol", "trojan")
            putJsonObject("settings") {
                putJsonArray("servers") {
                    addJsonObject {
                        put("address", n.address)
                        put("port", n.port)
                        put("password", n.secret)
                    }
                }
            }
            put("streamSettings", stream(n, dialer))
        }

        Protocol.SHADOWSOCKS -> buildJsonObject {
            put("tag", PROXY_TAG)
            put("protocol", "shadowsocks")
            putJsonObject("settings") {
                putJsonArray("servers") {
                    addJsonObject {
                        put("address", n.address)
                        put("port", n.port)
                        put("method", n.params["method"].orEmpty())
                        put("password", n.secret)
                        put("uot", true)
                    }
                }
            }
            putJsonObject("streamSettings") {
                put("network", "tcp")
                dialer?.let { putJsonObject("sockopt") { put("dialerProxy", it) } }
            }
        }

        Protocol.HYSTERIA2 -> buildJsonObject {
            put("tag", PROXY_TAG)
            put("protocol", "hysteria")
            putJsonObject("settings") {
                put("version", 2)
                put("address", n.address)
                put("port", n.port)
            }
            put("streamSettings", stream(n, dialer))
        }

        Protocol.SOCKS, Protocol.HTTP -> buildJsonObject {
            put("tag", PROXY_TAG)
            put("protocol", if (n.protocol == Protocol.SOCKS) "socks" else "http")
            putJsonObject("settings") {
                putJsonArray("servers") {
                    addJsonObject {
                        put("address", n.address)
                        put("port", n.port)
                        n.params["user"].nb()?.let { user ->
                            putJsonArray("users") { addJsonObject { put("user", user); put("pass", n.secret) } }
                        }
                    }
                }
            }
            putJsonObject("streamSettings") {
                put("network", "tcp")
                if (n.security == "tls") {
                    put("security", "tls")
                    putJsonObject("tlsSettings") { put("serverName", n.params["sni"].nb() ?: n.address); put("fingerprint", "chrome") }
                }
                dialer?.let { putJsonObject("sockopt") { put("dialerProxy", it) } }
            }
        }

        Protocol.WIREGUARD -> buildJsonObject {
            val p = n.params
            put("tag", PROXY_TAG)
            put("protocol", "wireguard")
            putJsonObject("settings") {
                put("secretKey", WgConf.keyBase64(n.secret))
                put("noKernelTun", true)
                putJsonArray("address") {
                    p["address"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        .ifEmpty { listOf("172.16.0.2/32") }
                        .forEach { add(if ('/' in it) it else if (':' in it) "$it/128" else "$it/32") }
                }
                putJsonArray("peers") {
                    addJsonObject {
                        put("publicKey", WgConf.keyBase64(p["publickey"].orEmpty()))
                        p["presharedkey"].nb()?.let { put("preSharedKey", WgConf.keyBase64(it)) }
                        put("endpoint", if (':' in n.address) "[${n.address}]:${n.port}" else "${n.address}:${n.port}")
                        p["keepalive"]?.substringBefore('-')?.toIntOrNull()?.let { put("keepAlive", it) }
                        putJsonArray("allowedIPs") { add("0.0.0.0/0"); add("::/0") }
                    }
                }
                p["mtu"]?.toIntOrNull()?.let { put("mtu", it) }
                p["reserved"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 3 }?.let { r ->
                    putJsonArray("reserved") { r.forEach { add(it) } }
                }
                put("domainStrategy", "ForceIPv4")
            }
        }

        Protocol.AMNEZIAWG -> error("AmneziaWG запускается отдельным движком")
    }

    private fun stream(n: ProxyNode, dialer: String?): JsonObject = buildJsonObject {
        val p = n.params
        val hysteria = n.protocol == Protocol.HYSTERIA2
        val network = if (hysteria) "hysteria" else n.transport
        val security = when {
            hysteria -> "tls"
            n.security.isNotBlank() -> n.security
            n.protocol == Protocol.TROJAN -> "tls"
            else -> "none"
        }
        val sni = p["sni"].nb() ?: p["peer"].nb() ?: p["host"].nb()?.substringBefore(',')
        val path = p["path"].nb()
        val host = p["host"].nb()

        put("network", network)
        put("security", security)
        if (dialer != null) putJsonObject("sockopt") { put("dialerProxy", dialer) }

        when (security) {
            "tls" -> putJsonObject("tlsSettings") {
                sni?.let { put("serverName", it) }
                if (!hysteria) put("fingerprint", p["fp"].nb() ?: "chrome")
                (p["alpn"].nb() ?: "h3".takeIf { hysteria })?.let { alpn ->
                    putJsonArray("alpn") { alpn.split(',').forEach { add(it.trim()) } }
                }
                n.pinnedCert?.let { put("pinnedPeerCertSha256", it) }
                p["ech"].nb()?.let { put("echConfigList", it) }
                p["vcn"].nb()?.let { put("verifyPeerCertByName", it) }
            }
            "reality" -> putJsonObject("realitySettings") {
                put("serverName", sni.orEmpty())
                put("fingerprint", p["fp"].nb() ?: "chrome")
                put("publicKey", p["pbk"].orEmpty())
                put("shortId", p["sid"].orEmpty())
                p["spx"].nb()?.let { put("spiderX", it) }
                p["pqv"].nb()?.let { put("mldsa65Verify", it) }
            }
        }

        val finalmask = p["fm"].nb()?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        when (network) {
            "hysteria" -> {
                putJsonObject("hysteriaSettings") {
                    put("version", 2)
                    put("auth", n.secret)
                }
                val obfs = p["obfs-password"].nb()?.takeIf { (p["obfs"].nb() ?: "salamander") == "salamander" }
                val hop = p["mport"].nb()
                if (obfs != null || hop != null) putJsonObject("finalmask") {
                    putJsonArray("udp") {
                        obfs?.let {
                            addJsonObject {
                                put("type", "salamander")
                                putJsonObject("settings") { put("password", it) }
                            }
                        }
                        hop?.let {
                            addJsonObject {
                                put("type", "udphop")
                                putJsonObject("settings") {
                                    put("mode", "intervallocal,intervalremote")
                                    put("remotePorts", it)
                                    p["mportHopInt"].nb()?.let { s -> put("interval", s) }
                                }
                            }
                        }
                    }
                }
            }
            "ws" -> putJsonObject("wsSettings") {
                path?.let { put("path", it) }
                host?.let { put("host", it) }
            }
            "httpupgrade" -> putJsonObject("httpupgradeSettings") {
                path?.let { put("path", it) }
                host?.let { put("host", it) }
            }
            "xhttp" -> putJsonObject("xhttpSettings") {
                path?.let { put("path", it) }
                host?.let { put("host", it) }
                p["mode"].nb()?.let { put("mode", it) }
                p["extra"].nb()?.let { extra ->
                    runCatching { json.parseToJsonElement(extra).jsonObject }.getOrNull()?.let { put("extra", it) }
                }
            }
            "grpc" -> putJsonObject("grpcSettings") {
                put("serviceName", p["serviceName"].nb() ?: path.orEmpty())
                if (p["mode"] == "multi") put("multiMode", true)
                p["authority"].nb()?.let { put("authority", it) }
            }
            "kcp", "mkcp" -> putJsonObject("kcpSettings") {
                p["mtu"]?.toIntOrNull()?.let { put("mtu", it) }
                p["tti"]?.toIntOrNull()?.let { put("tti", it) }
                p["seed"].nb()?.let { put("seed", it) }
                p["headerType"].nb()?.let { putJsonObject("header") { put("type", it) } }
            }
            "tcp", "raw" -> if (p["headerType"] == "http") putJsonObject("tcpSettings") {
                putJsonObject("header") {
                    put("type", "http")
                    putJsonObject("request") {
                        putJsonArray("path") { add(path ?: "/") }
                        putJsonObject("headers") {
                            putJsonArray("Host") { host?.split(',')?.forEach { add(it.trim()) } }
                        }
                    }
                }
            }
        }
        if (finalmask != null && network != "hysteria") put("finalmask", finalmask)
    }

    // endregion

    // region Полная конфигурация провайдера

    /**
     * Полная JSON-конфигурация передаётся почти без изменений (как у Happ/INCY), но:
     * локальные SOCKS/HTTP inbound'ы заменяются TUN, удаляются API и метрики,
     * правила с inboundTag переводятся на TUN, а без собственного DNS подставляется защищённый.
     */
    private fun patchFull(node: ProxyNode, o: BuildOptions, forTest: Boolean): String {
        val original = json.parseToJsonElement(node.raw).jsonObject
        val oldInbounds = (original["inbounds"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { ib -> ib["tag"]?.str()?.let { it to (ib["protocol"]?.str() ?: "") } }.toMap()
        val outbounds = (original["outbounds"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val hasDnsOut = outbounds.any { it["protocol"]?.str() == "dns" }
        val dnsOutTag = outbounds.firstOrNull { it["protocol"]?.str() == "dns" }?.get("tag")?.str() ?: DNS_OUT_TAG
        val hasDirect = outbounds.any { it["tag"]?.str() == DIRECT_TAG }
        val needsBlock = o.proxy != null && outbounds.none { it["tag"]?.str() == BLOCK_TAG }
        val dns = original["dns"] as? JsonObject
        val ownDns = (dns?.get("servers") as? JsonArray)?.isNotEmpty() == true
        val outboundTags = outbounds.mapNotNull { it["tag"]?.str() }.toSet() + DIRECT_TAG + DNS_OUT_TAG + BLOCK_TAG

        val result = buildJsonObject {
            for ((key, value) in original) {
                if (key in FORBIDDEN_SECTIONS || key == "log" || key == "remarks" || key == "stats" || key == "policy") continue
                if (forTest && key in setOf("dns", "fakedns", "observatory", "burstObservatory", "routing")) continue
                when (key) {
                    "routing" -> put(key, if (forTest) testRouting(value as? JsonObject) else patchRouting(value as? JsonObject, oldInbounds, outboundTags, dnsOutTag, o))
                    "outbounds" -> putJsonArray(key) {
                        val ordered = if (forTest) outbounds.sortedBy { o -> if (o["protocol"]?.str() in TEST_SKIP) 1 else 0 } else outbounds
                        ordered.forEach { add(guardFreedom(stripInsecure(it), o)) }
                        if (!hasDirect) add(guardFreedom(buildJsonObject { put("tag", DIRECT_TAG); put("protocol", "freedom") }, o))
                        if (needsBlock && !forTest) addJsonObject { put("tag", BLOCK_TAG); put("protocol", "blackhole") }
                        if (!hasDnsOut && !forTest) addJsonObject { put("tag", DNS_OUT_TAG); put("protocol", "dns") }
                    }
                    "dns" -> put(key, if (ownDns) value else dns(null, o))
                    else -> put(key, value)
                }
            }
            putJsonObject("log") { put("loglevel", o.logLevel); put("access", "none"); put("dnsLog", false) }
            if (!forTest) {
                putJsonArray("inbounds") { add(entryInbound(o, original["fakedns"] != null)) }
                if ("dns" !in original) put("dns", dns(null, o))
                if ("routing" !in original) put("routing", patchRouting(null, oldInbounds, outboundTags, dnsOutTag, o))
                stats(this)
                val policy = original["policy"] as? JsonObject
                if (policy != null) put("policy", mergePolicy(policy))
            }
        }
        return result.toString()
    }

    private fun mergePolicy(policy: JsonObject): JsonObject {
        val system = (policy["system"] as? JsonObject).orEmpty() + mapOf(
            "statsOutboundUplink" to JsonPrimitive(true),
            "statsOutboundDownlink" to JsonPrimitive(true),
        )
        return JsonObject(policy + ("system" to JsonObject(system)))
    }

    /**
     * Правила провайдера переводятся на TUN: inboundTag бывших SOCKS/HTTP-входов заменяется на `tun`.
     * Правила служебных входов (api, metrics, dokodemo) и правила, ведущие в несуществующий outbound,
     * удаляются — иначе после замены входа они перехватили бы весь трафик.
     */
    private fun patchRouting(routing: JsonObject?, inbounds: Map<String, String>, outboundTags: Set<String>, dnsOutTag: String, o: BuildOptions): JsonObject {
        val balancers = (routing?.get("balancers") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("tag")?.str() }.toSet()
        val userFacing = inbounds.filterValues { it in setOf("socks", "http", "mixed", "tun") }.keys
        val rules = (routing?.get("rules") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { rule ->
            val target = rule["outboundTag"]?.str()
            val balancer = rule["balancerTag"]?.str()
            if (target != null && target !in outboundTags) return@mapNotNull null
            if (balancer != null && balancer !in balancers) return@mapNotNull null
            val tags = (rule["inboundTag"] as? JsonArray)?.mapNotNull { it.str() } ?: return@mapNotNull rule
            val removed = tags.filter { it in inbounds && it !in userFacing }
            val mapped = tags.filter { it !in inbounds } + if (tags.any { it in userFacing }) listOf(TUN_TAG) else emptyList()
            if (mapped.isEmpty() || (removed.isNotEmpty() && mapped.none { it == TUN_TAG })) null
            else JsonObject(rule + ("inboundTag" to JsonArray(mapped.distinct().map(::JsonPrimitive))))
        }
        val hijack = buildJsonObject {
            putJsonArray("inboundTag") { add(TUN_TAG) }
            put("port", "53")
            put("outboundTag", dnsOutTag)
        }
        val base = routing.orEmpty()
        val guards = listOfNotNull(loopbackGuard(o), loopbackDomainGuard(o))
        return JsonObject(base + ("rules" to JsonArray(listOf(hijack) + guards + rules)))
    }

    /** Для замера задержки правила с inboundTag не нужны — запрос идёт прямо из ядра. */
    private fun testRouting(routing: JsonObject?): JsonObject {
        val rules = (routing?.get("rules") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .filter { "inboundTag" !in it }
        return JsonObject(routing.orEmpty() + ("rules" to JsonArray(rules)))
    }

    private fun stripInsecure(outbound: JsonObject): JsonObject {
        val stream = outbound["streamSettings"] as? JsonObject ?: return outbound
        val tls = (stream["tlsSettings"] as? JsonObject)?.takeIf { "allowInsecure" in it } ?: return outbound
        return JsonObject(outbound + ("streamSettings" to JsonObject(stream + ("tlsSettings" to JsonObject(tls - "allowInsecure")))))
    }

    // endregion

    /** Все упоминания geosite:/geoip: в конфигурации — для подготовки урезанных геофайлов. */
    fun referencedGeoTags(config: String): Pair<Set<String>, Set<String>> {
        val sites = mutableSetOf<String>()
        val ips = mutableSetOf<String>()
        Regex("""\"(geosite|geoip):([^\"@]+)""", RegexOption.IGNORE_CASE).findAll(config).forEach { m ->
            val tag = m.groupValues[2].trim().lowercase().removePrefix("!")
            if (m.groupValues[1].equals("geosite", true)) sites += tag else ips += tag
        }
        return sites to ips
    }

    /** Убирает из правил ссылки на отсутствующие теги, чтобы ядро не падало при старте. */
    fun dropGeoTags(config: String, missingSites: Set<String>, missingIps: Set<String>): String {
        if (missingSites.isEmpty() && missingIps.isEmpty()) return config
        fun strip(el: JsonElement): JsonElement = when (el) {
            is JsonObject -> {
                JsonObject(el.mapValues { (_, v) -> strip(v) })
            }
            is JsonArray -> JsonArray(el.mapNotNull { item ->
                val s = (item as? JsonPrimitive)?.contentOrNull
                if (s != null) {
                    val lower = s.lowercase()
                    val drop = (lower.startsWith("geosite:") && lower.substringAfter(':').substringBefore('@').removePrefix("!") in missingSites) ||
                        (lower.startsWith("geoip:") && lower.substringAfter(':').removePrefix("!") in missingIps)
                    if (drop) null else item
                } else strip(item)
            })
            else -> el
        }
        val root = json.parseToJsonElement(config).jsonObject
        val cleaned = strip(root).jsonObject
        val routing = cleaned["routing"] as? JsonObject ?: return cleaned.toString()
        // Правило, у которого после чистки опустели условия, сработало бы на весь трафик, поэтому оно удаляется.
        val rules = (routing["rules"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { rule ->
            listOf("domain", "ip", "source").all { key -> (rule[key] as? JsonArray)?.isNotEmpty() ?: true }
        }
        return JsonObject(cleaned + ("routing" to JsonObject(routing + ("rules" to JsonArray(rules))))).toString()
    }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
    private fun String?.nb(): String? = this?.takeIf { it.isNotBlank() }
}

val ProxyNode.canMux: Boolean
    get() = !isJson && protocol in setOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN) &&
        params["flow"].isNullOrBlank() && transport !in setOf("xhttp", "kcp", "grpc")

val ProxyNode.canFragment: Boolean
    get() = !isJson && protocol in setOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN) &&
        (security == "tls" || security == "reality" || (protocol == Protocol.TROJAN && security.isBlank()))

val ProxyNode.canNoise: Boolean
    get() = !isJson && protocol in setOf(Protocol.HYSTERIA2, Protocol.WIREGUARD)
