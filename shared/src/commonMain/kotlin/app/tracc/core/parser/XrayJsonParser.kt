package app.tracc.core.parser

import app.tracc.core.model.Protocol
import app.tracc.core.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * JSON-подписки: массив полных конфигураций Xray (формат Remnawave XRAY_JSON), одиночная
 * конфигурация или контейнер AmneziaWG от INCY (`{"type":"amneziawg","servers":[…]}`).
 * Полная конфигурация хранится как есть и патчится только при запуске (см. XrayConfigBuilder).
 */
object XrayJsonParser {
    private val PROTOCOLS = mapOf(
        "vless" to Protocol.VLESS,
        "vmess" to Protocol.VMESS,
        "trojan" to Protocol.TROJAN,
        "shadowsocks" to Protocol.SHADOWSOCKS,
        "hysteria" to Protocol.HYSTERIA2,
        "hysteria2" to Protocol.HYSTERIA2,
        "socks" to Protocol.SOCKS,
        "http" to Protocol.HTTP,
        "wireguard" to Protocol.WIREGUARD,
    )
    private val RESERVED_TAGS = setOf("direct", "block", "dns-out", "freedom", "blackhole")

    data class Result(val nodes: List<ProxyNode>, val meta: Map<String, String>)

    fun parse(text: String, sourceId: String): Result? {
        if (!text.startsWith("[") && !text.startsWith("{")) return null
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
        val items = (root as? JsonArray)?.filterIsInstance<JsonObject>() ?: listOfNotNull(root as? JsonObject)
        val nodes = mutableListOf<ProxyNode>()
        val meta = mutableMapOf<String, String>()
        for (item in items) {
            when {
                item.str("type") == "amneziawg" -> nodes += awgContainer(item, sourceId)
                "outbounds" in item -> node(item, sourceId)?.let(nodes::add)
            }
            // Флаги INCY в JSON-теле: {"hide_check": true}, {"hide_url": true}.
            item.bool("hide_check")?.let { meta["hide-check"] = it.toString() }
            item.bool("hide_url")?.let { meta["hide-url"] = it.toString() }
        }
        return Result(nodes, meta)
    }

    private fun awgContainer(obj: JsonObject, sourceId: String): List<ProxyNode> =
        (obj["servers"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { server ->
            val conf = server.str("config")?.let { Base64Util.decodeToString(it) } ?: return@mapNotNull null
            LinkParser.fromConf(conf, sourceId, server.str("name"))
        }

    private fun node(config: JsonObject, sourceId: String): ProxyNode? {
        val outbounds = config.array("outbounds").filterIsInstance<JsonObject>()
            .filter { it.str("tag") !in RESERVED_TAGS && it.str("protocol") in PROTOCOLS }
        val main = outbounds.firstOrNull { it.str("tag") == "proxy" } ?: outbounds.firstOrNull() ?: return null
        val protocol = PROTOCOLS.getValue(main.str("protocol")!!)
        val settings = main.obj("settings") ?: JsonObject(emptyMap())
        val server = (settings.array("vnext").firstOrNull() ?: settings.array("servers").firstOrNull()
            ?: settings.array("peers").firstOrNull()) as? JsonObject ?: settings
        val stream = main.obj("streamSettings")
        val tls = stream?.obj("tlsSettings")
        val reality = stream?.obj("realitySettings")
        val endpoint = server.str("endpoint")
        val address = server.str("address") ?: endpoint?.substringBeforeLast(':') ?: return null
        val port = server.int("port") ?: endpoint?.substringAfterLast(':')?.toIntOrNull() ?: return null
        val user = server.array("users").firstOrNull() as? JsonObject ?: server

        val name = config.str("remarks")?.trim().orEmpty().ifEmpty { main.str("tag")?.takeIf { it != "proxy" } ?: "$address:$port" }
        // Remnawave и Happ передают описание в meta уже раскодированным текстом.
        val description = config.obj("meta")?.str("serverDescription")?.take(60)
        val params = buildMap {
            stream?.str("security")?.let { put("security", it) }
            stream?.str("network")?.let { put("type", it) }
            (tls ?: reality)?.str("serverName")?.let { put("sni", it) }
            user.str("flow")?.let { put("flow", it) }
            if (tls?.bool("allowInsecure") == true) put("allowInsecure", "1")
            tls?.str("pinnedPeerCertSha256")?.let { put("pcs", it) }
            // «Автовыбор» — только когда балансировщику есть из чего выбирать.
            if (config.obj("routing")?.array("balancers")?.isNotEmpty() == true && outbounds.size >= 2) put("balancer", "1")
        }
        val cleaned = JsonObject(config.filterKeys { it != "meta" })
        return ProxyNode(
            id = LinkParser.nodeId(JsonObject(cleaned - "remarks").toString()),
            protocol = protocol,
            name = name,
            address = address,
            port = port,
            secret = user.str("id") ?: user.str("password") ?: "",
            params = params,
            raw = cleaned.toString(),
            sourceId = sourceId,
            description = description,
        )
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "1") }
}
