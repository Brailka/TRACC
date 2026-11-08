package app.rvc.core.routing

import app.rvc.core.parser.Base64Util
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Профиль маршрутизации в формате Happ / INCY (routing.happ.su).
 * Поля JSON читаются без учёта регистра: `GlobalProxy`, `globalProxy` и `globalproxy` равнозначны.
 */
@Serializable
data class RoutingProfile(
    val id: String,
    val name: String = "Default",
    val globalProxy: Boolean = true,
    val remoteDnsType: String = "DoH",
    val remoteDnsDomain: String = "https://cloudflare-dns.com/dns-query",
    val remoteDnsIp: String = "1.1.1.1",
    val domesticDnsType: String = "DoU",
    val domesticDnsDomain: String = "",
    val domesticDnsIp: String = "77.88.8.8",
    val geoipUrl: String = "",
    val geositeUrl: String = "",
    val lastUpdated: Long = 0,
    val dnsHosts: Map<String, String> = emptyMap(),
    val directSites: List<String> = emptyList(),
    val directIp: List<String> = emptyList(),
    val proxySites: List<String> = emptyList(),
    val proxyIp: List<String> = emptyList(),
    val blockSites: List<String> = emptyList(),
    val blockIp: List<String> = emptyList(),
    val domainStrategy: String = "IPIfNonMatch",
    val fakeDns: Boolean = false,
    val routeOrder: List<String> = DEFAULT_ORDER,
    /** URL-источник для autorouting: профиль периодически перекачивается. */
    val sourceUrl: String? = null,
    val sourceUpdatedAt: Long = 0,
    val updateIntervalSec: Long = 86_400,
    /** Подписка, вместе с которой пришёл профиль (null — добавлен вручную или встроенный). */
    val subscriptionId: String? = null,
    val builtIn: Boolean = false,
    val geoError: String? = null,
    /** Когда геофайлы профиля последний раз успешно проверялись (для routing-geo-update-interval). */
    val geoCheckedAt: Long = 0,
) {
    val usesCustomGeo: Boolean get() = geoipUrl.isNotBlank() || geositeUrl.isNotBlank()

    /** Все теги geosite:/geoip:, на которые ссылаются правила. */
    val geositeTags: Set<String>
        get() = (directSites + proxySites + blockSites).mapNotNull { it.tagOf("geosite:") }.toSet()
    val geoipTags: Set<String>
        get() = (directIp + proxyIp + blockIp).mapNotNull { it.tagOf("geoip:") }.toSet()

    companion object {
        val DEFAULT_ORDER = listOf("block", "proxy", "direct")

        val PRIVATE_IPS = listOf(
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10",
            "127.0.0.0/8", "224.0.0.0/4", "255.255.255.255", "fc00::/7", "fe80::/10",
        )

        /** Весь трафик через VPN, кроме локальной сети. */
        val GLOBAL = RoutingProfile(
            id = "builtin-global",
            name = "Весь трафик через VPN",
            directIp = listOf("geoip:private"),
            domainStrategy = "AsIs",
            builtIn = true,
        )

        /** Российские сайты и сервисы напрямую, реклама блокируется. */
        val RUSSIA = RoutingProfile(
            id = "builtin-ru",
            name = "Россия напрямую",
            directSites = listOf("geosite:private", "geosite:category-ru", "geosite:category-gov-ru", "geosite:category-bank-ru", "geosite:tld-ru"),
            directIp = listOf("geoip:private", "geoip:ru"),
            blockSites = listOf("geosite:category-ads"),
            builtIn = true,
        )

        val BUILT_IN = listOf(RUSSIA, GLOBAL)

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Данные из ссылки/заголовка: JSON, base64(JSON) или вложенная deep link. */
        fun parseAny(text: String, id: String): RoutingProfile? {
            val t = text.trim()
            RoutingLinks.extractPayload(t)?.let { inner -> if (inner != t) return parseAny(inner, id) }
            if (t.startsWith("{")) return parseJson(t, id)
            val decoded = Base64Util.decodeToString(t)?.trim() ?: return null
            return if (decoded.startsWith("{")) parseJson(decoded, id) else null
        }

        fun parseJson(text: String, id: String): RoutingProfile? {
            val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            val m = obj.entries.associate { it.key.lowercase() to it.value }
            fun s(vararg keys: String): String? = keys.firstNotNullOfOrNull { (m[it] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { v -> v.isNotEmpty() } }
            fun b(key: String, def: Boolean): Boolean = (m[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.trim()?.lowercase()?.let { v -> v == "true" || v == "1" } } ?: def
            fun list(key: String): List<String> = when (val v = m[key]) {
                is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotEmpty() }
                is JsonPrimitive -> v.contentOrNull.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                else -> emptyList()
            }
            val hosts = (m["dnshosts"] as? JsonObject)?.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { k to it } ?: (v as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull?.let { k to it }
            }?.toMap().orEmpty()
            val order = list("routeorder").flatMap { it.split('-', ',', ' ') }.map { it.lowercase() }.filter { it in DEFAULT_ORDER }
            val strategy = s("domainstrategy")?.let { v -> listOf("AsIs", "IPIfNonMatch", "IPOnDemand").firstOrNull { it.equals(v, true) } }
            return RoutingProfile(
                id = id,
                name = s("name") ?: "Default",
                globalProxy = b("globalproxy", true),
                remoteDnsType = s("remotednstype") ?: "DoH",
                remoteDnsDomain = s("remotednsdomain") ?: "https://cloudflare-dns.com/dns-query",
                remoteDnsIp = s("remotednsip", "remotedns") ?: "1.1.1.1",
                domesticDnsType = s("domesticdnstype") ?: "DoU",
                domesticDnsDomain = s("domesticdnsdomain").orEmpty(),
                domesticDnsIp = s("domesticdnsip", "domesticdns") ?: "77.88.8.8",
                geoipUrl = s("geoipurl").orEmpty().let(::githubRaw),
                geositeUrl = s("geositeurl").orEmpty().let(::githubRaw),
                lastUpdated = s("lastupdated", "lastupdateddate")?.substringBefore('.')?.toLongOrNull() ?: 0,
                dnsHosts = hosts,
                directSites = list("directsites"),
                directIp = list("directip"),
                proxySites = list("proxysites"),
                proxyIp = list("proxyip"),
                blockSites = list("blocksites"),
                blockIp = list("blockip"),
                domainStrategy = strategy ?: "IPIfNonMatch",
                fakeDns = b("fakedns", false) || b("fakednsenabled", false),
                routeOrder = order.distinct().ifEmpty { DEFAULT_ORDER },
            )
        }

        /** github.com/…/blob/… → raw.githubusercontent.com/…, как у INCY. */
        fun githubRaw(url: String): String {
            val m = Regex("""^https://github\.com/([^/]+)/([^/]+)/blob/(.+)$""").find(url) ?: return url
            return "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}"
        }

        private fun String.tagOf(prefix: String): String? =
            if (startsWith(prefix, ignoreCase = true)) substring(prefix.length).substringBefore('@').lowercase().takeIf { it.isNotEmpty() } else null
    }
}

fun JsonElement?.isTrue(): Boolean = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull == "1" } == true
