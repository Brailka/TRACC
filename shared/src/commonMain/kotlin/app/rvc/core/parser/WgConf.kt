package app.rvc.core.parser

import app.rvc.core.toHex

/**
 * Конфигурация WireGuard / AmneziaWG в формате `.conf` (секции `[Interface]`, `[Peer]`, `[Device]`).
 * Ключи хранятся в нижнем регистре, значения — как в файле.
 */
data class WgConf(
    val iface: Map<String, String>,
    val peers: List<Map<String, String>>,
) {
    val privateKey: String? get() = iface["privatekey"]
    val addresses: List<String> get() = iface["address"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val dns: List<String> get() = iface["dns"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val mtu: Int? get() = iface["mtu"]?.toIntOrNull()
    val peer: Map<String, String> get() = peers.firstOrNull().orEmpty()

    val endpointHost: String?
        get() = peer["endpoint"]?.let { ep ->
            if (ep.startsWith("[")) ep.substringAfter('[').substringBefore(']') else ep.substringBeforeLast(':')
        }
    val endpointPort: Int? get() = peer["endpoint"]?.substringAfterLast(':')?.toIntOrNull()

    /** AmneziaWG узнаётся по параметрам обфускации, как у INCY. */
    val isAmnezia: Boolean get() = AWG_KEYS.any { it in iface }

    /**
     * Строка настроек для userspace-движка (UAPI `set=1`). Ключи — в hex, endpoint — уже IP:port.
     */
    fun toUapi(resolvedEndpoint: String): String = buildString {
        fun line(k: String, v: String) = append(k).append('=').append(v).append('\n')
        line("private_key", keyHex(privateKey ?: error("нет PrivateKey")))
        line("replace_peers", "true")
        for ((conf, uapi) in AWG_UAPI) {
            val value = iface[conf] ?: continue
            when (conf) {
                "headerprotectionkey" -> line(uapi, keyHex(value))
                "randomtrailers", "disablecookies" -> line(uapi, if (ProviderBool.parse(value)) "true" else "false")
                else -> line(uapi, value.trim())
            }
        }
        peers.forEachIndexed { index, p ->
            line("public_key", keyHex(p["publickey"] ?: error("нет PublicKey")))
            p["presharedkey"]?.let { line("preshared_key", keyHex(it)) }
            line("endpoint", if (index == 0) resolvedEndpoint else p["endpoint"].orEmpty())
            p["persistentkeepalive"]?.takeIf { it != "0" && it.isNotBlank() }?.let { line("persistent_keepalive_interval", it.trim()) }
            line("replace_allowed_ips", "true")
            p["allowedips"].orEmpty().ifBlank { "0.0.0.0/0, ::/0" }.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { line("allowed_ip", it) }
        }
    }

    companion object {
        private val AWG_UAPI = linkedMapOf(
            "jc" to "jc", "jmin" to "jmin", "jmax" to "jmax",
            "s1" to "s1", "s2" to "s2", "s3" to "s3", "s4" to "s4",
            "h1" to "h1", "h2" to "h2", "h3" to "h3", "h4" to "h4",
            "i1" to "i1", "i2" to "i2", "i3" to "i3", "i4" to "i4", "i5" to "i5",
            "headerprotectionkey" to "header_protection_key",
            "contentpaddingaddition" to "content_padding_addition",
            "rekeyaftertime" to "rekey_after_time",
            "rekeytimeout" to "rekey_timeout",
            "rejectaftertime" to "reject_after_time",
            "keepalivetimeout" to "keepalive_timeout",
            "maxhandshakeattempts" to "max_handshake_attempts",
            "randomtrailers" to "random_trailers",
            "disablecookies" to "disable_cookies",
        )
        private val AWG_KEYS = AWG_UAPI.keys - setOf("rekeyaftertime", "rekeytimeout", "rejectaftertime", "keepalivetimeout", "maxhandshakeattempts")

        fun looksLikeConf(text: String): Boolean =
            text.contains("[Interface]", ignoreCase = true) && text.contains("PrivateKey", ignoreCase = true)

        fun parse(text: String): WgConf? {
            val sections = mutableListOf<Pair<String, MutableMap<String, String>>>()
            for (rawLine in text.lineSequence()) {
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    sections += line.substring(1, line.length - 1).trim().lowercase() to mutableMapOf()
                    continue
                }
                val eq = line.indexOf('=')
                if (eq <= 0 || sections.isEmpty()) continue
                val key = line.substring(0, eq).trim().lowercase()
                val value = line.substring(eq + 1).trim()
                val map = sections.last().second
                // Address/AllowedIPs/DNS могут повторяться строками — склеиваем.
                map[key] = map[key]?.let { "$it, $value" } ?: value
            }
            val iface = mutableMapOf<String, String>()
            sections.filter { it.first == "device" }.forEach { iface.putAll(it.second) }
            sections.filter { it.first == "interface" }.forEach { iface.putAll(it.second) }
            val peers = sections.filter { it.first == "peer" }.map { it.second.toMap() }
            if (iface["privatekey"].isNullOrBlank() || peers.isEmpty()) return null
            return WgConf(iface, peers)
        }

        /** Ключ в base64 (как выдаёт `wg genkey`) или в hex → hex. */
        fun keyHex(key: String): String {
            val k = key.trim()
            if (k.length == 64 && k.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return k.lowercase()
            val bytes = Base64Util.decode(k) ?: error("неверный ключ")
            require(bytes.size == 32) { "неверная длина ключа" }
            return bytes.toHex()
        }

        fun keyBase64(key: String): String {
            val k = key.trim()
            if (k.length == 64 && k.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                val bytes = ByteArray(32) { i -> k.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                return Base64Util.encode(bytes)
            }
            return k
        }
    }
}

internal object ProviderBool {
    fun parse(value: String): Boolean = value.trim().lowercase() in setOf("1", "true", "yes", "on", "enabled")
}
