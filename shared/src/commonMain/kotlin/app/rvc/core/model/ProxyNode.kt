package app.rvc.core.model

import app.rvc.core.firstCodePoint
import kotlinx.serialization.Serializable

@Serializable
enum class Protocol(val title: String) {
    VLESS("VLESS"),
    VMESS("VMess"),
    TROJAN("Trojan"),
    SHADOWSOCKS("Shadowsocks"),
    HYSTERIA2("Hysteria2"),
    SOCKS("SOCKS5"),
    HTTP("HTTP"),
    WIREGUARD("WireGuard"),
    AMNEZIAWG("AmneziaWG"),
}

/** На какой сети показывать сервер: метки «only Wi-Fi» / «only Mobile» в имени, как в Happ и INCY. */
enum class NetworkOnly { WIFI, MOBILE }

@Serializable
data class ProxyNode(
    val id: String,
    val protocol: Protocol,
    val name: String,
    val address: String,
    val port: Int,
    val secret: String,
    val params: Map<String, String> = emptyMap(),
    val raw: String,
    val sourceId: String = MANUAL_SOURCE,
    val description: String? = null,
) {
    val security: String get() = params["security"].orEmpty().lowercase()
    val transport: String get() = params["type"].orEmpty().ifBlank { "tcp" }.lowercase().let { if (it == "splithttp") "xhttp" else it }

    /** Полная конфигурация Xray от провайдера (JSON-подписка). */
    val isJson: Boolean get() = raw.startsWith("{")

    /** AmneziaWG работает на отдельном движке, а не через Xray. */
    val usesAwgEngine: Boolean get() = protocol == Protocol.AMNEZIAWG

    val networkOnly: NetworkOnly?
        get() = when {
            "only Wi-Fi" in name || "only WiFi" in name -> NetworkOnly.WIFI
            "only Mobile" in name -> NetworkOnly.MOBILE
            else -> null
        }

    val pinnedCert: String? get() = (params["pcs"] ?: params["pinSHA256"])?.takeIf { it.isNotBlank() }

    val securityIssue: String?
        get() = when {
            (params["allowInsecure"] == "1" || params["insecure"] == "1") && pinnedCert == null -> "Без проверки сертификата"
            protocol == Protocol.VLESS && (security.isBlank() || security == "none") && !isJson -> "Без шифрования"
            protocol == Protocol.SOCKS || protocol == Protocol.HTTP -> "Прокси без шифрования"
            else -> null
        }

    /** Подпись под именем: описание от провайдера или протокол и транспорт. */
    val subtitle: String
        get() = description?.takeIf { it.isNotBlank() } ?: tags.joinToString(" · ")

    val tags: List<String>
        get() = buildList {
            add(protocol.title)
            if (isJson) {
                if (params["balancer"] == "1") add("Автовыбор")
                return@buildList
            }
            when (security) {
                "reality" -> add("Reality")
                "tls" -> add("TLS")
            }
            if (params["flow"].orEmpty().contains("vision")) add("Vision")
            if (protocol in STREAM_PROTOCOLS && transport != "tcp" && transport != "raw") add(transport.uppercase())
        }

    /** Ключ хоста для подсчёта пинга: у JSON-конфигураций это первый сервер. */
    val endpoint: String get() = "$address:$port"

    companion object {
        const val MANUAL_SOURCE = "manual"
        private val STREAM_PROTOCOLS = setOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN)
    }
}

/** Флаг страны из начала имени (эмодзи) или null. */
/** Сервер-балансировщик из XRAY_JSON (Remnawave «Автовыбор»). */
val ProxyNode.isAuto: Boolean
    get() = params["balancer"] == "1"

val ProxyNode.flag: String?
    get() = leadingEmoji(name)

/** Эмодзи в начале строки (флаг страны, 🌍, ⚡ и т.п.) целиком, вместе с модификаторами, или null. */
fun leadingEmoji(text: String): String? {
    val t = text.trimStart()
    if (t.isEmpty()) return null
    val grapheme = app.rvc.core.Platform.firstGrapheme(t) ?: return null
    val cp = grapheme.firstCodePoint()
    val emoji = cp in 0x1F1E6..0x1F1FF || cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF
    return grapheme.takeIf { emoji }
}

/** Имя без ведущего флага — флаг рисуется отдельно. */
val ProxyNode.cleanName: String
    get() = flag?.let { name.trimStart().removePrefix(it).trim() }?.ifBlank { name } ?: name
