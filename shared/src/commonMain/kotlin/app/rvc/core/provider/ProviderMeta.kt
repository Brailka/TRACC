package app.rvc.core.provider

import app.rvc.core.parser.Base64Util
import kotlinx.serialization.Serializable

@Serializable
data class Usage(val upload: Long = 0, val download: Long = 0, val total: Long = 0, val expire: Long = 0) {
    val used: Long get() = upload + download
    val unlimited: Boolean get() = total <= 0
    val fraction: Float get() = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
}

@Serializable
data class Banner(
    val text: String,
    val buttonText: String? = null,
    val buttonUrl: String? = null,
    val bgColor: String? = null,
    val buttonColor: String? = null,
    val textColor: String? = null,
    val buttonTextColor: String? = null,
)

@Serializable
data class PerAppDirective(
    val enabled: Boolean? = null,
    /** bypass — указанные приложения идут мимо VPN, proxy — только они идут через VPN. */
    val mode: String? = null,
    val add: List<String> = emptyList(),
    val set: List<String>? = null,
    val invert: List<String>? = null,
    val listUrl: String? = null,
)

@Serializable
data class Fragmentation(
    val enabled: Boolean,
    val packets: String = "tlshello",
    val length: String = "100-200",
    val interval: String = "10-20",
    val maxSplit: String? = null,
)

@Serializable
data class Noises(
    val enabled: Boolean,
    val type: String = "rand",
    val packet: String = "10-20",
    val delay: String = "10-16",
)

@Serializable
data class ServerResolve(val enabled: Boolean, val dohUrl: String? = null, val dohIp: String? = null)

@Serializable
data class MuxSettings(val enabled: Boolean, val tcp: Int? = null, val xudp: Int? = null, val quic: String? = null)

/**
 * Всё, чем провайдер управляет через заголовки ответа подписки или строки `#key: value` в теле.
 * Источники: документация Happ (app-management), INCY (app-management, subscription-format) и Remnawave.
 * Заголовки приоритетнее тела.
 */
@Serializable
data class ProviderMeta(
    val title: String? = null,
    val description: String? = null,
    val logoUrl: String? = null,
    val updateIntervalHours: Int? = null,
    val usage: Usage? = null,
    val hideUsage: Boolean = false,
    val refillDate: String? = null,
    val supportUrl: String? = null,
    val supportEmail: String? = null,
    val webPageUrl: String? = null,
    val announce: String? = null,
    val announceUrl: String? = null,
    val premiumUrl: String? = null,
    val banner: Banner? = null,
    val expiryBanner: Banner? = null,
    val infoBanner: Banner? = null,
    val infoBannerColor: String? = null,
    val subExpire: Boolean = false,
    val notifyExpire: Boolean = false,
    val hideUrl: Boolean = false,
    val hideCheck: Boolean = false,
    val hideSettings: Boolean = false,
    val newUrl: String? = null,
    val newDomain: String? = null,
    val fallbackUrl: String? = null,
    val routing: String? = null,
    val autorouting: String? = null,
    val routingEnabled: Boolean? = null,
    val sortOrder: String? = null,
    val perApp: PerAppDirective? = null,
    val fragmentation: Fragmentation? = null,
    val noises: Noises? = null,
    val serverResolve: ServerResolve? = null,
    val autoconnect: Boolean? = null,
    val autoconnectType: String? = null,
    val pingOnOpen: Boolean? = null,
    val updateOnOpen: Boolean? = null,
    val autoUpdateEnabled: Boolean? = null,
    val pingType: String? = null,
    val checkUrl: String? = null,
    val userAgent: String? = null,
    val mux: MuxSettings? = null,
    val sniffing: Boolean? = null,
    val excludeRoutes: List<String>? = null,
    val excludeRoutesReplace: Boolean = false,
    val tunMtu: Int? = null,
    val providerId: String? = null,
    val hwidActive: Boolean = false,
    val hwidMaxDevicesReached: Boolean = false,
    val hwidNotSupported: Boolean = false,
    val requestTimeoutSec: Int? = null,
    val notificationsUrl: String? = null,
    val dontUseFilter: Boolean = false,
    val alwaysHwid: Boolean = false,
    val pin: Boolean? = null,
    val hideVpnIcon: Boolean? = null,
    val dnsFromJson: Boolean? = null,
) {
    companion object {
        /** Ключи, которые стоит хранить вместе с кэшем подписки. */
        fun isKnownKey(key: String): Boolean = key in KNOWN || key.startsWith("banner-") || key.startsWith("expiry-banner-") ||
            key.startsWith("sub-info") || key.startsWith("per-app-proxy") || key.startsWith("fragmentation-") ||
            key.startsWith("noises-") || key.startsWith("server-address-resolve") || key.startsWith("mux-") ||
            key.startsWith("x-hwid") || key.startsWith("subscription-")

        private val KNOWN = setOf(
            "profile-title", "subscription-name", "content-disposition", "profile-description", "profile-update-interval",
            "subscription-userinfo", "support-url", "support-email", "profile-web-page-url", "homepage", "announce",
            "announce-url", "premium-url", "hide-url", "hide-check", "hide-settings", "new-url", "new-domain",
            "fallback-url", "routing", "autorouting", "routing-enable", "sort-order", "subscriptions-sort-type",
            "ping-type", "check-url-via-proxy", "change-user-agent", "sniffing-enable", "exclude-routes",
            "exclude-routes-set", "xray-tun-mtu", "providerid", "provider-id", "notification-subs-expire", "sub-expire",
            "notifications-url", "push-url", "dont-use-filter", "profile-logo", "profile-logo-url", "logo-url",
            "hide-vpn-icon", "dns-from-json-enable", "app-auto-start", "routing-geo-update-interval",
        )

        fun parse(raw: Map<String, String>): ProviderMeta {
            val m = raw.mapKeys { it.key.trim().lowercase() }.mapValues { it.value.trim() }
            fun s(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> m[k]?.takeIf { it.isNotBlank() } }
            fun text(vararg keys: String): String? = s(*keys)?.let(::decodeValue)?.takeIf { it.isNotBlank() }
            fun bool(vararg keys: String): Boolean? = s(*keys)?.let(::flag)
            fun int(vararg keys: String): Int? = s(*keys)?.substringBefore('.')?.toIntOrNull()

            val (title, titleDescription) = splitTitle(
                text("profile-title") ?: text("subscription-name") ?: s("content-disposition")?.let(::fileName),
            )
            val userinfo = s("subscription-userinfo")

            fun banner(prefix: String): Banner? {
                val body = text("$prefix-text") ?: return null
                if (body == "0") return null
                return Banner(
                    text = body.take(400),
                    buttonText = text("$prefix-button-text")?.take(80),
                    // У обычного баннера premium-url исторически приоритетнее, у баннера истечения — нет.
                    buttonUrl = if (prefix == "banner") s("premium-url", "banner-button-url", "banner-url")
                    else s("$prefix-button-url", "$prefix-url"),
                    bgColor = s("$prefix-bg-color"),
                    buttonColor = s("$prefix-button-color"),
                    textColor = s("$prefix-text-color"),
                    buttonTextColor = s("$prefix-button-text-color"),
                )
            }

            val happInfo = text("sub-info-text")?.takeIf { it != "0" }?.let {
                Banner(
                    text = it.take(400),
                    buttonText = text("sub-info-button-text")?.take(80),
                    buttonUrl = s("sub-info-button-link"),
                    bgColor = s("sub-info-color"),
                )
            }

            val perAppMode = s("per-app-proxy-mode")?.lowercase()
            val perApp = if (perAppMode != null || s("per-app-proxy-enable", "per-app-proxy-list", "per-app-proxy-list-set", "per-app-proxy-list-invert") != null) {
                val list = text("per-app-proxy-list")
                PerAppDirective(
                    enabled = bool("per-app-proxy-enable") ?: perAppMode?.let { it != "off" },
                    mode = when (perAppMode) {
                        "on", "proxy" -> "proxy"
                        "bypass" -> "bypass"
                        else -> null
                    },
                    add = list?.takeUnless { it.startsWith("http") }?.let(::csv).orEmpty(),
                    set = text("per-app-proxy-list-set")?.let(::csv),
                    invert = text("per-app-proxy-list-invert")?.let(::csv),
                    listUrl = list?.takeIf { it.startsWith("https://") },
                )
            } else null

            val routes = s("exclude-routes-set") ?: s("exclude-routes")

            return ProviderMeta(
                title = title?.take(40),
                description = text("profile-description") ?: titleDescription,
                logoUrl = s("profile-logo-url", "profile-logo", "logo-url")?.takeIf { it.startsWith("https://") },
                updateIntervalHours = int("profile-update-interval")?.takeIf { it > 0 },
                usage = parseUsage(userinfo),
                hideUsage = userinfo == "0",
                refillDate = s("subscription-refill-date"),
                supportUrl = s("support-url"),
                supportEmail = s("support-email"),
                webPageUrl = s("profile-web-page-url", "homepage"),
                announce = text("announce")?.takeIf { it != "0" }?.take(1000),
                announceUrl = s("announce-url"),
                premiumUrl = s("premium-url"),
                banner = banner("banner"),
                expiryBanner = banner("expiry-banner"),
                infoBanner = happInfo,
                subExpire = bool("sub-expire") == true,
                notifyExpire = bool("notification-subs-expire") == true || bool("sub-expire") == true,
                hideUrl = bool("hide-url") == true,
                hideCheck = bool("hide-check") == true,
                hideSettings = bool("hide-settings") == true,
                newUrl = s("new-url")?.takeIf { it.startsWith("https://") },
                newDomain = s("new-domain")?.takeIf { it.none { c -> c == '/' || c == ':' || c.isWhitespace() } },
                fallbackUrl = s("fallback-url")?.takeIf { it.startsWith("http") },
                routing = s("routing"),
                autorouting = s("autorouting"),
                routingEnabled = bool("routing-enable") ?: s("routing")?.let { if (it.equals("off", true)) false else null },
                sortOrder = s("sort-order", "subscriptions-sort-type")?.lowercase()?.let {
                    when (it) {
                        "alphabet" -> "name"
                        "without" -> "none"
                        else -> it
                    }
                },
                perApp = perApp,
                fragmentation = bool("fragmentation-enable")?.let {
                    Fragmentation(
                        enabled = it,
                        packets = s("fragmentation-packets") ?: "tlshello",
                        length = s("fragmentation-length") ?: "100-200",
                        interval = s("fragmentation-interval") ?: "10-20",
                        maxSplit = s("fragmentation-maxsplit"),
                    )
                },
                noises = bool("noises-enable")?.let {
                    Noises(
                        enabled = it,
                        type = s("noises-type", "noises-packet-type") ?: "rand",
                        packet = s("noises-packet") ?: s("noises-rand") ?: "10-20",
                        delay = s("noises-delay") ?: "10-16",
                    )
                },
                serverResolve = bool("server-address-resolve-enable")?.let {
                    ServerResolve(it, s("server-address-resolve-dns-domain"), s("server-address-resolve-dns-ip"))
                },
                autoconnect = bool("subscription-autoconnect"),
                autoconnectType = s("subscription-autoconnect-type")?.lowercase(),
                pingOnOpen = bool("subscription-ping-onopen-enabled"),
                updateOnOpen = bool("subscription-auto-update-open-enable"),
                autoUpdateEnabled = bool("subscription-auto-update-enable"),
                pingType = s("ping-type")?.lowercase(),
                checkUrl = s("check-url-via-proxy")?.takeIf { it.startsWith("http") },
                userAgent = s("change-user-agent"),
                mux = bool("mux-enable")?.let { MuxSettings(it, int("mux-tcp-connections"), int("mux-xudp-connections"), s("mux-quic")) },
                sniffing = bool("sniffing-enable"),
                excludeRoutes = routes?.split(',', ' ', ';')?.map { it.trim() }?.filter { it.isNotEmpty() },
                excludeRoutesReplace = s("exclude-routes-set") != null,
                tunMtu = int("xray-tun-mtu")?.takeIf { it in 576..65535 },
                providerId = s("providerid", "provider-id"),
                hwidActive = bool("x-hwid-active") == true,
                hwidMaxDevicesReached = bool("x-hwid-max-devices-reached") == true,
                hwidNotSupported = bool("x-hwid-not-supported") == true,
                requestTimeoutSec = int("subscription-request-timeout")?.coerceIn(5, 30),
                notificationsUrl = s("notifications-url", "push-url")?.takeIf { it.startsWith("https://") },
                dontUseFilter = bool("dont-use-filter") == true,
                alwaysHwid = bool("subscription-always-hwid-enable") == true,
                pin = bool("subscription-pin"),
                hideVpnIcon = bool("hide-vpn-icon"),
                dnsFromJson = bool("dns-from-json-enable"),
            )
        }

        /** `true`/`1`/`yes` — включено, любое другое непустое значение — выключено. */
        fun flag(value: String): Boolean = value.trim().lowercase() in setOf("1", "true", "yes", "on")

        /** Значение может прийти как `base64:...`, `base64:gzip:...`, `gzip:base64:...` или открытым текстом. */
        fun decodeValue(value: String): String {
            var v = value.trim()
            var gzip = false
            var base64 = false
            while (true) {
                v = when {
                    v.startsWith("base64:", true) -> { base64 = true; v.substring(7) }
                    v.startsWith("gzip:", true) -> { gzip = true; v.substring(5) }
                    else -> break
                }
            }
            if (!base64 && !gzip) return v
            val bytes = Base64Util.decode(v) ?: return value
            return runCatching {
                if (gzip) app.rvc.core.Platform.gunzip(bytes, 1024 * 1024)!!.decodeToString()
                else bytes.decodeToString()
            }.getOrDefault(value)
        }

        /** В base64-заголовке profile-title первая строка — имя, остальные — описание. */
        private fun splitTitle(value: String?): Pair<String?, String?> {
            if (value == null) return null to null
            val lines = value.lines()
            return lines.first().trim() to lines.drop(1).joinToString("\n").trim().takeIf { it.isNotEmpty() }
        }

        private fun fileName(disposition: String): String? =
            Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)
                ?.let { app.rvc.core.urlDecode(it) }
                ?.substringBeforeLast(".txt")?.substringBeforeLast(".yaml")?.substringBeforeLast(".yml")
                ?.takeIf { it.isNotBlank() }

        private fun csv(value: String): List<String> =
            value.split(',', '\n', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() && '.' in it }

        fun parseUsage(value: String?): Usage? {
            if (value.isNullOrBlank() || value == "0") return null
            val parts = value.split(';').mapNotNull { part ->
                val key = part.substringBefore('=', "").trim().lowercase()
                val num = part.substringAfter('=').trim().substringBefore('.').toLongOrNull()
                if (key.isEmpty() || num == null) null else key to num
            }.toMap()
            var expire = parts["expire"] ?: 0
            if (expire > 32_000_000_000L) expire /= 1000
            val usage = Usage(parts["upload"] ?: 0, parts["download"] ?: 0, parts["total"] ?: 0, expire)
            return usage.takeIf { it.used > 0 || it.total > 0 || it.expire > 0 }
        }
    }
}
