package app.tracc.core

import app.tracc.core.parser.Base64Util
import app.tracc.core.parser.LinkParser
import app.tracc.core.parser.WgConf
import app.tracc.core.routing.RoutingCommand
import app.tracc.core.routing.RoutingLinks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

sealed interface DeepLink {
    data class AddSubscription(val url: String, val name: String? = null, val hidden: Boolean = false) : DeepLink
    data class ImportNodes(val text: String) : DeepLink
    data class Routing(val command: RoutingCommand) : DeepLink
    data object Connect : DeepLink
    data object Disconnect : DeepLink
    data object Toggle : DeepLink
    data object Status : DeepLink
}

/**
 * Deep links в форматах Happ и INCY. Схема перед `://` любая из зарегистрированных
 * (`tracc`, `happ`, `incy`, `v2raytun`), разбор смотрит на путь:
 * - `://add/{url|ссылка}`, `://import/{данные}` — подписка, сервер(ы) или `.conf`;
 * - `://routing/…`, `://autorouting/…`, `://onadd/…` — маршрутизация;
 * - `://connect|open`, `://disconnect|close`, `://toggle`, `://status` — управление VPN;
 * - `incy://crypt1/…` — зашифрованная ссылка INCY;
 * - прямые ссылки `vless://`, `vmess://`, `trojan://`, `ss://`, `hy2://`, `socks://`, `wireguard://`, `awg://`.
 */
object DeepLinks {
    fun parse(raw: String): DeepLink? {
        val text = raw.trim()
        val scheme = text.substringBefore("://", "").lowercase()
        if (scheme in LinkParser.SCHEMES) return DeepLink.ImportNodes(text)
        if (scheme == "http" || scheme == "https") return DeepLink.AddSubscription(text)
        if (scheme == "sub") return Base64Util.decodeToString(text.substringAfter("://"))?.let { DeepLink.AddSubscription(it.trim()) }

        val rest = text.substringAfter("://", text)
        val head = rest.substringBefore('/').substringBefore('?').lowercase()
        val tail = rest.substringAfter('/', "")
        if (RoutingLinks.isRoutingLink(text)) return RoutingLinks.parse(text)?.let(DeepLink::Routing)
        return when (head) {
            "connect", "open" -> DeepLink.Connect
            "disconnect", "close" -> DeepLink.Disconnect
            "toggle" -> DeepLink.Toggle
            "status" -> DeepLink.Status
            "add", "install-config", "install-sub" -> data(tail.ifEmpty { query(rest, "url") ?: "" })
            "import" -> data(tail.ifEmpty { query(rest, "url") ?: query(rest, "data") ?: "" })
            "crypt1" -> Incy.decrypt(tail)?.let { (url, name) -> DeepLink.AddSubscription(url, name, hidden = true) }
            else -> null
        }
    }

    /** Автоопределение данных: URL подписки, ссылки серверов, base64 от них или `.conf`. */
    private fun data(payload: String): DeepLink? {
        val p = decodeUrl(payload).trim()
        if (p.isEmpty()) return null
        val nameFromFragment = p.substringAfter('#', "").takeIf { p.startsWith("http") && it.isNotBlank() && '=' !in it }
        if (p.startsWith("http://") || p.startsWith("https://")) {
            if ('\n' !in p && '@' !in p.substringAfter("://").substringBefore('/')) return DeepLink.AddSubscription(p, nameFromFragment)
        }
        if (p.substringBefore("://", "").lowercase() in LinkParser.SCHEMES || WgConf.looksLikeConf(p)) return DeepLink.ImportNodes(p)
        val decoded = Base64Util.decodeToString(p)?.trim() ?: return null
        return when {
            decoded.startsWith("http") && '\n' !in decoded -> DeepLink.AddSubscription(decoded)
            decoded.substringBefore("://", "").lowercase() in LinkParser.SCHEMES || WgConf.looksLikeConf(decoded) -> DeepLink.ImportNodes(decoded)
            decoded.startsWith("{") || decoded.startsWith("[") -> DeepLink.ImportNodes(decoded)
            else -> null
        }
    }

    private fun decodeUrl(s: String): String =
        if ("%3A" in s || "%2F" in s) urlDecode(s) else s

    private fun query(rest: String, key: String): String? =
        rest.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.let(::decodeUrl)
}

/**
 * Ссылки `incy://crypt1/<base64url(iv(12) || ciphertext || tag(16))>`: AES-256-GCM с публичным ключом K1.
 * Ключ взят из пакета @incy/link-encoder 1.3.0 (MIT, © 2026 INCY LLC) — он опубликован, чтобы провайдеры
 * могли сами собирать такие ссылки. Отпечаток SHA-256 ключа: b6bf7084…24c08c.
 */
object Incy {
    private const val K1 = "f6d40ea0c8a8899d7c682d09ba0d4165dfe2b3dd45e6bb3e25cb233cf00c2462"

    fun decrypt(payload: String): Pair<String, String?>? = runCatching {
        val data = Base64Util.decode(payload.trim().substringBefore('?').substringBefore('#')) ?: return null
        if (data.size < 12 + 16 + 2) return null
        val key = ByteArray(32) { i -> K1.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val plain = Platform.aesGcmDecrypt(key, data.copyOfRange(0, 12), data.copyOfRange(12, data.size))?.decodeToString() ?: return null
        val obj = Json.parseToJsonElement(plain) as JsonObject
        val url = (obj["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") } ?: return null
        url to (obj["n"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()
}
