package app.rvc.core.parser

import app.rvc.core.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Ссылки AmneziaVPN `vpn://…` (их же выдают панели вроде 3x-ui для Amnezia).
 *
 * Внутри — base64url от JSON, сжатого как `qCompress` из Qt: 4 байта длины (big-endian) и поток zlib.
 * В JSON — «контейнеры»: у AmneziaWG и WireGuard в `last_config` лежит JSON с готовым `.conf`
 * (DNS — подстановки `$PRIMARY_DNS`/`$SECONDARY_DNS`), у Xray — полная клиентская конфигурация.
 */
object AmneziaLink {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String, sourceId: String): List<ProxyNode> {
        val (payload, fragment) = raw.substringAfter("://").let { it.substringBefore('#') to it.substringAfter('#', "") }
        val bytes = Base64Util.decode(payload.trim().trimEnd('/')) ?: return emptyList()
        val text = inflate(bytes) ?: bytes.decodeToString()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()

        val name = fragment.takeIf { it.isNotBlank() }?.let { app.rvc.core.urlDecode(it) }
            ?: root.str("description")?.takeIf { it.isNotBlank() }
        val dns1 = root.str("dns1") ?: "1.1.1.1"
        val dns2 = root.str("dns2") ?: "1.0.0.1"
        val containers = (root["containers"] as? kotlinx.serialization.json.JsonArray).orEmpty().filterIsInstance<JsonObject>()
        // Контейнер по умолчанию — первым, чтобы при одном сервере брался именно он.
        val preferred = root.str("defaultContainer")
        val ordered = containers.sortedBy { if (it.str("container") == preferred) 0 else 1 }

        return ordered.flatMap { container ->
            when {
                container["awg"] is JsonObject -> listOfNotNull(wgNode(container["awg"] as JsonObject, dns1, dns2, sourceId, name))
                container["wireguard"] is JsonObject -> listOfNotNull(wgNode(container["wireguard"] as JsonObject, dns1, dns2, sourceId, name))
                container["xray"] is JsonObject -> xrayNodes(container["xray"] as JsonObject, sourceId, name)
                else -> emptyList()
            }
        }
    }

    private fun wgNode(section: JsonObject, dns1: String, dns2: String, sourceId: String, name: String?): ProxyNode? {
        val last = section.str("last_config") ?: return null
        val conf = runCatching { json.parseToJsonElement(last).jsonObject.str("config") }.getOrNull() ?: last
        val filled = conf.replace("\$PRIMARY_DNS", dns1).replace("\$SECONDARY_DNS", dns2)
        return LinkParser.fromConf(filled, sourceId, name)
    }

    private fun xrayNodes(section: JsonObject, sourceId: String, name: String?): List<ProxyNode> {
        val config = section.str("last_config")?.trim() ?: return emptyList()
        val nodes = XrayJsonParser.parse(config, sourceId)?.nodes.orEmpty()
        return if (name != null && nodes.size == 1) listOf(nodes[0].copy(name = name)) else nodes
    }

    /** `qCompress`: 4 байта длины + zlib. Без заголовка длины — пробуем как чистый zlib. */
    private fun inflate(data: ByteArray): String? {
        val limit = 4 * 1024 * 1024
        val bytes = (if (data.size > 4) app.rvc.core.Platform.inflateZlib(data.copyOfRange(4, data.size), limit) else null)
            ?: app.rvc.core.Platform.inflateZlib(data, limit)
        return bytes?.decodeToString()?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}
