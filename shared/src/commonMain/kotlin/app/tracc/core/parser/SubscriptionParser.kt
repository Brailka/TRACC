package app.tracc.core.parser

import app.tracc.core.model.ProxyNode
import app.tracc.core.provider.ProviderMeta

data class ParsedSubscription(
    val nodes: List<ProxyNode>,
    val meta: ProviderMeta,
    val rawMeta: Map<String, String>,
    /** Строки маршрутизации из тела: `happ://routing/onadd/…`, `://autorouting/…` и т.п. */
    val routingLinks: List<String>,
    /** Служебные «серверы» с адресом 0.0.0.0 — панели так передают сообщения (лимит устройств, истечение). */
    val notices: List<String>,
    val skipped: Map<String, Int> = emptyMap(),
)

object SubscriptionParser {
    private val ROUTING_MARKERS = listOf("://routing/", "://autorouting/", "://onadd/", "://routing?")

    fun parse(body: String, sourceId: String, headers: Map<String, String> = emptyMap()): ParsedSubscription {
        var text = body.trim().removePrefix("﻿").trim()
        val bodyMeta = LinkedHashMap<String, String>()
        val nodes = LinkedHashMap<String, ProxyNode>()
        val notices = mutableListOf<String>()
        val skipped = HashMap<String, Int>()
        val routing = mutableListOf<String>()
        val announceLines = mutableListOf<String>()

        fun add(node: ProxyNode) {
            if (node.address == "0.0.0.0" || node.address == "127.0.0.1" || node.address == "::") {
                notices += node.name
            } else {
                nodes.putIfAbsent(node.id, node)
            }
        }

        if (!text.startsWith("[") && !text.startsWith("{") && !text.contains("://") && !WgConf.looksLikeConf(text)) {
            Base64Util.decodeToString(text)?.takeIf { it.contains("://") || WgConf.looksLikeConf(it) || it.trimStart().startsWith("#") }
                ?.let { text = it.trim() }
        }

        val json = XrayJsonParser.parse(text, sourceId)
        when {
            json != null -> {
                json.nodes.forEach(::add)
                json.meta.forEach { (k, v) -> bodyMeta.putIfAbsent(k, v) }
            }
            WgConf.looksLikeConf(text) && !text.lineSequence().any { it.trim().substringBefore("://", "").lowercase() in LinkParser.SCHEMES } -> {
                LinkParser.fromConf(text, sourceId)?.let(::add)
            }
            else -> text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                if (ROUTING_MARKERS.any { line.contains(it, ignoreCase = true) } && !line.startsWith("#")) {
                    routing += line
                    return@forEach
                }
                if (line.startsWith("{")) {
                    XrayJsonParser.parse(line, sourceId)?.nodes?.forEach(::add)
                    return@forEach
                }
                if (line.startsWith("#")) {
                    parseMetaLine(line.removePrefix("#").trim())?.let { (key, value) ->
                        if (key == "announce") announceLines += value else bodyMeta.putIfAbsent(key, value)
                    }
                    return@forEach
                }
                val node = LinkParser.parse(line, sourceId)
                if (node == null) {
                    val scheme = line.substringBefore("://", "").lowercase()
                    if (scheme.isNotEmpty() && scheme.length <= 16 && scheme !in setOf("http", "https")) {
                        skipped[scheme] = (skipped[scheme] ?: 0) + 1
                    }
                    return@forEach
                }
                add(node)
            }
        }

        if (announceLines.isNotEmpty()) bodyMeta.putIfAbsent("announce", announceLines.joinToString("\n") { ProviderMeta.decodeValue(it) })
        val merged = LinkedHashMap<String, String>(bodyMeta)
        headers.forEach { (k, v) -> if (v.isNotBlank()) merged[k.lowercase()] = v }

        return ParsedSubscription(
            nodes = nodes.values.toList(),
            meta = ProviderMeta.parse(merged),
            rawMeta = merged.filterKeys { ProviderMeta.isKnownKey(it) },
            routingLinks = routing,
            notices = notices,
            skipped = skipped,
        )
    }

    /** `#key: value` и форма без двоеточия `#providerid ID`. */
    private fun parseMetaLine(kv: String): Pair<String, String>? {
        val colon = kv.indexOf(':')
        val space = kv.indexOf(' ')
        val (key, value) = when {
            colon > 0 && (space < 0 || colon < space || kv.substring(0, colon).none { it == ' ' }) ->
                kv.substring(0, colon).trim() to kv.substring(colon + 1).trim()
            space > 0 -> kv.substring(0, space).trim() to kv.substring(space + 1).trim()
            else -> return null
        }
        val k = key.lowercase()
        if (k.isEmpty() || ' ' in k || value.isEmpty()) return null
        return k to value
    }
}

/** `putIfAbsent` есть только в JVM; на iOS — эта версия (на Android побеждает метод самой Map). */
private fun <K, V> MutableMap<K, V>.putIfAbsent(key: K, value: V): V? = get(key) ?: run {
    put(key, value)
    null
}
