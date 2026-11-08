package app.rvc.core.parser

import app.rvc.core.toHex
import app.rvc.core.model.Protocol
import app.rvc.core.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

object LinkParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val SCHEMES = setOf(
        "vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "socks", "socks5", "wireguard", "wg", "amneziawg", "awg", "vpn",
    )

    fun parse(line: String, sourceId: String = ProxyNode.MANUAL_SOURCE): ProxyNode? {
        val raw = line.trim()
        if (raw.isEmpty() || raw.startsWith("#")) return null
        val scheme = raw.substringBefore("://", "").lowercase()
        return runCatching {
            when (scheme) {
                "vless" -> parseUriStyle(raw, Protocol.VLESS, sourceId)
                "trojan" -> parseUriStyle(raw, Protocol.TROJAN, sourceId)
                "hysteria2", "hy2" -> parseUriStyle(raw, Protocol.HYSTERIA2, sourceId)
                "vmess" -> parseVmess(raw, sourceId)
                "ss" -> parseShadowsocks(raw, sourceId)
                "socks", "socks5" -> parseProxy(raw, Protocol.SOCKS, sourceId)
                "http", "https" -> if ('@' in raw.substringAfter("://").substringBefore('/')) parseProxy(raw, Protocol.HTTP, sourceId) else null
                "wireguard", "wg" -> parseWireGuardUri(raw, sourceId)
                "amneziawg", "awg" -> parseAwgUri(raw, sourceId)
                // Ссылка AmneziaVPN может нести несколько контейнеров — здесь берётся основной.
                "vpn" -> AmneziaLink.parse(raw, sourceId).firstOrNull()
                else -> null
            }
        }.getOrNull()
    }

    /** `#Имя?serverDescription=base64` — имя до первого `?`, описание в base64 (до 30 символов). */
    private fun splitFragment(fragment: String): Pair<String, String?> {
        val decoded = pctDecode(fragment)
        val q = decoded.indexOf('?')
        if (q < 0) return decoded.trim() to null
        val name = decoded.substring(0, q).trim()
        val desc = decoded.substring(q + 1).split('&').firstOrNull { it.startsWith("serverDescription=") }
            ?.substringAfter('=')?.let { Base64Util.decodeToString(it) ?: it }?.trim()?.take(60)
        return name to desc?.takeIf { it.isNotEmpty() }
    }

    private fun parseUriStyle(raw: String, protocol: Protocol, sourceId: String): ProxyNode? {
        val body = raw.substringAfter("://")
        val (beforeFragment, fragment) = splitOnce(body, '#')
        val (beforeQuery, query) = splitOnce(beforeFragment, '?')
        val at = beforeQuery.lastIndexOf('@')
        if (at <= 0) return null
        val secret = pctDecode(beforeQuery.substring(0, at))
        val authority = beforeQuery.substring(at + 1).trimEnd('/')
        val (hostPort, hop) = if (protocol == Protocol.HYSTERIA2) hysteriaPorts(authority) else authority to null
        val (host, port) = parseHostPort(hostPort) ?: return null
        val params = parseQuery(query).let { q ->
            var p = q
            if (hop != null && "mport" !in p) p = p + ("mport" to hop)
            p["ports"]?.let { if ("mport" !in p) p = p + ("mport" to it) }
            p["type"]?.let { if (it == "splithttp") p = p + ("type" to "xhttp") }
            p
        }
        val (name, desc) = splitFragment(fragment)
        return ProxyNode(
            id = nodeId(raw.substringBefore('#')),
            protocol = protocol,
            name = name.ifBlank { "$host:$port" },
            address = host,
            port = port,
            secret = secret,
            params = params,
            raw = raw,
            sourceId = sourceId,
            description = desc,
        )
    }

    private fun hysteriaPorts(authority: String): Pair<String, String?> {
        val colon = authority.lastIndexOf(':')
        if (colon < 0 || authority.endsWith(']')) return "$authority:443" to null
        val ports = authority.substring(colon + 1)
        if (ports.all(Char::isDigit)) return authority to null
        return authority.substring(0, colon + 1) + ports.substringBefore(',').substringBefore('-') to ports
    }

    private fun parseVmess(raw: String, sourceId: String): ProxyNode? {
        val payload = Base64Util.decodeToString(raw.substringAfter("://").substringBefore('#')) ?: return null
        val obj = json.parseToJsonElement(payload).jsonObject
        fun s(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

        val host = s("add")
        val port = s("port").toIntOrNull() ?: return null
        val id = s("id")
        if (host.isBlank() || id.isBlank() || port !in 1..65535) return null
        val tls = s("tls").lowercase()
        val params = buildMap {
            put("type", s("net").ifBlank { "tcp" }.let { if (it == "splithttp") "xhttp" else it })
            put("security", if (tls == "tls" || tls == "reality") tls else "none")
            mapOf(
                "headerType" to s("type"), "host" to s("host"), "path" to s("path"),
                "sni" to s("sni"), "alpn" to s("alpn"), "fp" to s("fp"),
                "aid" to s("aid"), "scy" to s("scy"), "mode" to s("mode"),
            ).forEach { (k, v) -> if (v.isNotBlank() && v != "none") put(k, v) }
        }
        val (name, desc) = splitFragment(s("ps"))
        return ProxyNode(
            id = nodeId(JsonObject(obj - "ps").toString()),
            protocol = Protocol.VMESS,
            name = name.ifBlank { "$host:$port" },
            address = host,
            port = port,
            secret = id,
            params = params,
            raw = raw,
            sourceId = sourceId,
            description = s("serverDescription").ifBlank { null } ?: desc,
        )
    }

    private fun parseShadowsocks(raw: String, sourceId: String): ProxyNode? {
        val (beforeFragment, fragment) = splitOnce(raw.substringAfter("://"), '#')
        val (mainPart, query) = splitOnce(beforeFragment, '?')
        val main = mainPart.trimEnd('/')
        val at = main.lastIndexOf('@')
        val credentials: String
        val hostPort: String
        if (at > 0) {
            val userInfo = main.substring(0, at)
            credentials = Base64Util.decodeToString(userInfo)?.takeIf { ':' in it } ?: pctDecode(userInfo)
            hostPort = main.substring(at + 1)
        } else {
            val decoded = Base64Util.decodeToString(main) ?: return null
            val a = decoded.lastIndexOf('@')
            if (a <= 0) return null
            credentials = decoded.substring(0, a)
            hostPort = decoded.substring(a + 1)
        }
        if (':' !in credentials) return null
        val (host, port) = parseHostPort(hostPort) ?: return null
        val (name, desc) = splitFragment(fragment)
        return ProxyNode(
            id = nodeId(raw.substringBefore('#')),
            protocol = Protocol.SHADOWSOCKS,
            name = name.ifBlank { "$host:$port" },
            address = host,
            port = port,
            secret = credentials.substringAfter(':'),
            params = parseQuery(query) + ("method" to credentials.substringBefore(':')),
            raw = raw,
            sourceId = sourceId,
            description = desc,
        )
    }

    /** SOCKS5 и HTTP-прокси: `user:pass@host:port`, base64 логина/пароля или вся строка в base64. */
    private fun parseProxy(raw: String, protocol: Protocol, sourceId: String): ProxyNode? {
        val (beforeFragment, fragment) = splitOnce(raw.substringAfter("://"), '#')
        var main = beforeFragment.substringBefore('?').trimEnd('/')
        if ('@' !in main && ':' !in main.substringAfterLast(']')) main = Base64Util.decodeToString(main) ?: return null
        val at = main.lastIndexOf('@')
        var user = ""
        var pass = ""
        val hostPort: String
        if (at > 0) {
            val info = main.substring(0, at)
            val creds = if (':' in info) pctDecode(info) else Base64Util.decodeToString(info)?.takeIf { ':' in it } ?: pctDecode(info)
            user = creds.substringBefore(':')
            pass = creds.substringAfter(':', "")
            hostPort = main.substring(at + 1)
        } else {
            hostPort = main
        }
        val (host, port) = parseHostPort(hostPort) ?: return null
        val (name, desc) = splitFragment(fragment)
        return ProxyNode(
            id = nodeId(raw.substringBefore('#')),
            protocol = protocol,
            name = name.ifBlank { "$host:$port" },
            address = host,
            port = port,
            secret = pass,
            params = buildMap {
                if (user.isNotEmpty()) put("user", user)
                if (raw.startsWith("https", true)) put("security", "tls")
            },
            raw = raw,
            sourceId = sourceId,
            description = desc,
        )
    }

    /** `wireguard://secretKey@host:port?publickey=…&address=…&mtu=…&reserved=1,2,3#name`. */
    private fun parseWireGuardUri(raw: String, sourceId: String): ProxyNode? {
        val (beforeFragment, fragment) = splitOnce(raw.substringAfter("://"), '#')
        val (beforeQuery, query) = splitOnce(beforeFragment, '?')
        val at = beforeQuery.lastIndexOf('@')
        if (at <= 0) return null
        val secret = pctDecode(beforeQuery.substring(0, at))
        val (host, port) = parseHostPort(beforeQuery.substring(at + 1).trimEnd('/')) ?: return null
        val q = parseQuery(query).mapKeys { it.key.lowercase() }
        val publicKey = q["publickey"] ?: q["public_key"] ?: q["peer"] ?: return null
        val (name, desc) = splitFragment(fragment)
        return ProxyNode(
            id = nodeId(raw.substringBefore('#')),
            protocol = Protocol.WIREGUARD,
            name = name.ifBlank { "$host:$port" },
            address = host,
            port = port,
            secret = secret,
            params = buildMap {
                put("publickey", publicKey)
                (q["address"] ?: q["ip"])?.let { put("address", it) }
                q["mtu"]?.let { put("mtu", it) }
                q["reserved"]?.let { put("reserved", it) }
                (q["presharedkey"] ?: q["psk"])?.let { put("presharedkey", it) }
                q["keepalive"]?.let { put("keepalive", it) }
            },
            raw = raw,
            sourceId = sourceId,
            description = desc,
        )
    }

    /** `amneziawg://<base64url .conf>#Имя` или `awg://…`. */
    private fun parseAwgUri(raw: String, sourceId: String): ProxyNode? {
        val (payload, fragment) = splitOnce(raw.substringAfter("://"), '#')
        val conf = Base64Util.decodeToString(payload.trim().trimEnd('/')) ?: return null
        val (name, desc) = splitFragment(fragment)
        return fromConf(conf, sourceId, name.ifBlank { null }, desc)
    }

    /**
     * Узел из `.conf`. AmneziaWG — на собственном движке, обычный WireGuard — через Xray,
     * чтобы для него работала маршрутизация.
     */
    fun fromConf(text: String, sourceId: String, name: String? = null, description: String? = null): ProxyNode? {
        val conf = WgConf.parse(text) ?: return null
        val host = conf.endpointHost ?: return null
        val port = conf.endpointPort ?: return null
        val confName = Regex("""^\s*#\s*Name\s*=\s*(.+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
            .find(text)?.groupValues?.get(1)?.trim()
        val title = name ?: confName ?: "$host:$port"
        if (conf.isAmnezia) {
            return ProxyNode(
                id = nodeId(text.trim()),
                protocol = Protocol.AMNEZIAWG,
                name = title,
                address = host,
                port = port,
                secret = "",
                params = buildMap {
                    put("address", conf.addresses.joinToString(","))
                    conf.mtu?.let { put("mtu", it.toString()) }
                    if (conf.dns.isNotEmpty()) put("dns", conf.dns.joinToString(","))
                },
                raw = "awg-conf:" + Base64Util.encodeUrlSafe(text.trim()),
                sourceId = sourceId,
                description = description,
            )
        }
        val peer = conf.peer
        return ProxyNode(
            id = nodeId(text.trim()),
            protocol = Protocol.WIREGUARD,
            name = title,
            address = host,
            port = port,
            secret = conf.privateKey.orEmpty(),
            params = buildMap {
                put("publickey", peer["publickey"].orEmpty())
                put("address", conf.addresses.joinToString(","))
                conf.mtu?.let { put("mtu", it.toString()) }
                peer["presharedkey"]?.let { put("presharedkey", it) }
                peer["persistentkeepalive"]?.let { put("keepalive", it) }
                peer["allowedips"]?.let { put("allowedips", it) }
            },
            raw = "wg-conf:" + Base64Util.encodeUrlSafe(text.trim()),
            sourceId = sourceId,
            description = description,
        )
    }

    private fun splitOnce(s: String, delimiter: Char): Pair<String, String> {
        val i = s.indexOf(delimiter)
        return if (i < 0) s to "" else s.substring(0, i) to s.substring(i + 1)
    }

    fun parseHostPort(s: String): Pair<String, Int>? {
        val host: String
        val portStr: String
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            host = s.substring(1, end)
            portStr = s.substring(end + 1).removePrefix(":")
        } else {
            host = s.substringBeforeLast(':', "")
            portStr = s.substringAfterLast(':', "")
        }
        val port = portStr.toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val k = pctDecode(pair.substringBefore('='))
            val v = pctDecode(pair.substringAfter('=', ""))
            if (k.isBlank()) null else k to v
        }.toMap()

    private fun pctDecode(s: String): String =
        app.rvc.core.urlDecode(s, plusAsSpace = false)

    fun nodeId(seed: String): String =
        app.rvc.core.Platform.sha1(seed.trim().encodeToByteArray()).copyOfRange(0, 8).toHex()
}
