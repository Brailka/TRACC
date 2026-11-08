package app.rvc.core

import app.rvc.core.model.ProxyNode
import app.rvc.core.parser.Base64Util
import app.rvc.core.parser.WgConf
import app.rvc.core.provider.ProviderMeta
import app.rvc.core.routing.RoutingProfile
import app.rvc.core.xray.BuildOptions
import app.rvc.core.xray.XrayConfigBuilder

/**
 * Что поднимать в туннеле и с какими сетевыми настройками — для платформ, где туннель
 * пишется не на Kotlin (Network Extension на iOS). Решения те же, что в `RvcVpnService`.
 */
class TunnelPlan(
    /** Xray (конфигурация в [xrayConfig]) или AmneziaWG ([awg]). */
    val usesAwg: Boolean,
    val xrayConfig: String?,
    val awg: WgConf?,
    val mtu: Int,
    /** Адреса интерфейса в виде CIDR. */
    val addresses: List<String>,
    val dns: List<String>,
    /** Сети, которые идут мимо туннеля (заголовок `exclude-routes` провайдера). */
    val excludedRoutes: List<String>,
) {
    /** Строка UAPI для AmneziaWG, когда адрес сервера уже найден (`ip:port` или `[ipv6]:port`). */
    fun awgSettings(resolvedEndpoint: String): String = awg?.toUapi(resolvedEndpoint) ?: error("не AmneziaWG")

    companion object {
        /** Адрес интерфейса для Xray: та же схема, что на Android (шлюз .1 — он же DNS туннеля). */
        const val XRAY_ADDRESS = "10.33.0.2/30"
        const val XRAY_GATEWAY = "10.33.0.1"
        const val XRAY_ADDRESS6 = "fd21::2/126"

        /**
         * @param mtu MTU из настроек приложения; заголовок провайдера `xray-tun-mtu` важнее.
         * @param routing активный профиль; null — без маршрутизации.
         */
        fun create(node: ProxyNode, meta: ProviderMeta?, routing: RoutingProfile?, mtu: Int, ipv6: Boolean): TunnelPlan {
            val excluded = meta?.excludeRoutes.orEmpty()
            if (node.usesAwgEngine) {
                val conf = WgConf.parse(Base64Util.decodeToString(node.raw.substringAfter("awg-conf:")).orEmpty())
                    ?: error("Конфигурация AmneziaWG повреждена")
                return TunnelPlan(
                    usesAwg = true,
                    xrayConfig = null,
                    awg = conf,
                    mtu = conf.mtu ?: 1420,
                    addresses = conf.addresses,
                    dns = conf.dns.ifEmpty { listOf("1.1.1.1") },
                    excludedRoutes = excluded,
                )
            }
            val tunMtu = meta?.tunMtu ?: mtu
            val options = BuildOptions(
                routing = if (meta?.routingEnabled == false) null else routing,
                mtu = tunMtu,
                sniffing = meta?.sniffing ?: true,
                fragmentation = meta?.fragmentation,
                noises = meta?.noises,
                mux = meta?.mux,
                ipv6 = ipv6,
            )
            return TunnelPlan(
                usesAwg = false,
                xrayConfig = XrayConfigBuilder.build(node, options),
                awg = null,
                mtu = tunMtu,
                addresses = listOf(XRAY_ADDRESS, XRAY_ADDRESS6),
                dns = listOf(XRAY_GATEWAY),
                excludedRoutes = excluded,
            )
        }
    }
}
