package app.tracc.data

import app.tracc.core.model.ProxyNode
import app.tracc.core.provider.Fragmentation
import app.tracc.core.provider.Noises
import app.tracc.core.provider.ProviderMeta
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class InterfaceMode { MODERN, CLASSIC }

@Serializable
enum class PerAppMode { OFF, BYPASS, PROXY }

/**
 * VPN — весь трафик телефона через TUN, без открытых портов. PROXY — VPN не поднимается, ядро слушает
 * SOCKS5 и HTTP на одном порту: для приложений с настройкой прокси и для раздачи через точку доступа.
 */
enum class ConnectionMode(val title: String) { VPN("VPN"), PROXY("Прокси") }

@Serializable
enum class PingType(val title: String) {
    PROXY("Через прокси (GET)"),
    PROXY_HEAD("Через прокси (HEAD)"),
    TCP("TCP"),
    ICMP("ICMP"),
}

@Serializable
enum class SortOrder(val title: String) { NONE("Как в подписке"), PING("По пингу"), NAME("По имени") }

@Serializable
data class AppSettings(
    val onboardingDone: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColor: Int = 0,
    val interfaceMode: InterfaceMode = InterfaceMode.MODERN,
    val modernScale: Float = 1f,
    val classicScale: Float = 0.88f,
    val routingEnabled: Boolean = true,
    val activeRoutingId: String = "builtin-ru",
    val perAppMode: PerAppMode = PerAppMode.OFF,
    val perAppList: Set<String> = emptySet(),
    val bypassLan: Boolean = true,
    /**
     * 8500, как у Happ и v2rayNG: на каждый пакет через TUN уходит системный вызов, и при 1500 их
     * впятеро больше — замер на телефоне: 2,5–2,8 с процессора на 100 МБ против 1,2–1,4 с.
     */
    val mtu: Int = DEFAULT_MTU,
    val connectionMode: ConnectionMode = ConnectionMode.VPN,
    /** Порт прокси: SOCKS5 и HTTP на одном порту (mixed). */
    val proxyPort: Int = 10808,
    /** Слушать не только 127.0.0.1, но и все интерфейсы — для устройств в той же сети или на точке доступа. */
    val proxyLan: Boolean = false,
    /** Логин и пароль прокси; пустые — без авторизации. Генерируются при первом включении режима. */
    val proxyUser: String = "",
    val proxyPass: String = "",
    /** Версия значений по умолчанию: при обновлении старые значения переводятся на новые (см. Store). */
    val defaultsVersion: Int = 0,
    val ipv6: Boolean = false,
    val sniffing: Boolean = true,
    val killSwitch: Boolean = true,
    val sendHwid: Boolean = true,
    val userAgent: String = "",
    val autoConnect: Boolean = false,
    val autoStartOnBoot: Boolean = false,
    val autoUpdateSubs: Boolean = true,
    val updateOnOpen: Boolean = true,
    val pingOnOpen: Boolean = false,
    val pingType: PingType = PingType.PROXY,
    val testUrl: String = "https://www.gstatic.com/generate_204",
    val sortOrder: SortOrder = SortOrder.NONE,
    val fragmentation: Fragmentation? = null,
    val noises: Noises? = null,
    val notifyProvider: Boolean = true,
    val notifyExpire: Boolean = true,
    val hideVpnIcon: Boolean = false,
    val autoFailover: Boolean = true,
    val haptics: Boolean = true,
)

@Serializable
data class Subscription(
    val id: String,
    val url: String,
    val name: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = 0,
    val error: String? = null,
    val meta: ProviderMeta = ProviderMeta(),
    val rawMeta: Map<String, String> = emptyMap(),
    val notices: List<String> = emptyList(),
    val nodeCount: Int = 0,
    val collapsed: Boolean = false,
    val pinned: Boolean = false,
    val intervalHours: Int? = null,
    val lastExpireNotice: Long = 0,
    val importedVia: String? = null,
) {
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: meta.title ?: hostOf(url)

    val effectiveIntervalHours: Int get() = intervalHours ?: meta.updateIntervalHours ?: 12

    companion object {
        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfter('@').ifBlank { "Подписка" }
    }
}

@Serializable
data class PingResult(val ms: Long, val at: Long = System.currentTimeMillis()) {
    val alive: Boolean get() = ms > 0

    /** Замер старше получаса показывается приглушённо: вчерашние цифры не должны выглядеть свежими. */
    val isStale: Boolean get() = System.currentTimeMillis() - at > 30 * 60_000L
}

@Serializable
data class ProviderNotice(
    val id: String,
    val title: String,
    val body: String,
    val url: String? = null,
    val at: Long = System.currentTimeMillis(),
    val subscriptionId: String? = null,
    val read: Boolean = false,
)

@Serializable
data class SelectedNode(val node: ProxyNode)

/** Значение по умолчанию живёт в общем модуле: им же пользуется сборщик конфигурации. */
const val DEFAULT_MTU = app.tracc.core.xray.DEFAULT_TUN_MTU

/** Текущая версия значений по умолчанию в [AppSettings]. */
const val DEFAULTS_VERSION = 2

/** Пароль прокси: 16 символов из безопасного генератора. */
fun newProxySecret(): String {
    val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val rnd = java.security.SecureRandom()
    return String(CharArray(16) { alphabet[rnd.nextInt(alphabet.length)] })
}
