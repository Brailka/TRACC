package app.tracc.core

import app.tracc.core.DeepLink
import app.tracc.core.DeepLinks
import app.tracc.core.model.Protocol
import app.tracc.core.parser.Base64Util
import app.tracc.core.parser.LinkParser
import app.tracc.core.parser.SubscriptionParser
import app.tracc.core.parser.WgConf
import app.tracc.core.provider.ProviderMeta
import app.tracc.core.routing.RoutingCommand
import app.tracc.core.routing.RoutingLinks
import app.tracc.core.routing.RoutingProfile
import app.tracc.core.xray.BuildOptions
import app.tracc.core.xray.XrayConfigBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class ParserTest {
    private val reality = "vless://70cc48c5-b2f4-34ac-a092-d806984a6b8c@1.13.7.91:443?encryption=none&security=reality" +
        "&pbk=qGPTy8EZokn3hWp6hKBQ0MVvEuLRJCcv5UdWeP4TVhI&fp=chrome&type=tcp&flow=xtls-rprx-vision&sni=booking.com&sid=6ba85179e30d4fc2" +
        "#%F0%9F%87%B1%F0%9F%87%B9%20Test?serverDescription=SGFwcCB0aGUgYmVzdA=="

    @Test
    fun vlessRealityWithDescription() {
        val n = LinkParser.parse(reality)!!
        assertEquals(Protocol.VLESS, n.protocol)
        assertEquals("1.13.7.91", n.address)
        assertEquals("reality", n.security)
        assertEquals("xtls-rprx-vision", n.params["flow"])
        assertEquals("🇱🇹 Test", n.name)
        assertEquals("Happ the best", n.description)
        assertTrue("Vision" in n.tags)
        assertEquals("VLESS / TCP / REALITY / VISION", n.connectionLabel)
    }

    @Test
    fun xhttpAndHysteriaPorts() {
        val x = LinkParser.parse("vless://id@h.example:443?type=splithttp&security=tls&path=%2Fx&mode=auto#X")!!
        assertEquals("xhttp", x.transport)
        assertEquals("VLESS / XHTTP / TLS", x.connectionLabel)
        val h = LinkParser.parse("hysteria2://pass@srv.example:443,5000-6000?sni=srv.example&obfs=salamander&obfs-password=ob#H")!!
        assertEquals(443, h.port)
        assertEquals("443,5000-6000", h.params["mport"])
        assertEquals("HYSTERIA2 / UDP", h.connectionLabel)
    }

    @Test
    fun socksHttpWireguard() {
        val b64 = Base64Util.encodeUrlSafe("user:pass")
        val s = LinkParser.parse("socks://$b64@1.2.3.4:1080#S")!!
        assertEquals(Protocol.SOCKS, s.protocol)
        assertEquals("user", s.params["user"])
        assertEquals("pass", s.secret)
        val h = LinkParser.parse("http://u:p@5.6.7.8:3128#P")!!
        assertEquals(Protocol.HTTP, h.protocol)
        assertNull(LinkParser.parse("https://sub.example.com/token"))
        val wg = LinkParser.parse("wireguard://cHJpdmF0ZQ@9.9.9.9:51820?publickey=KEY&address=10.0.0.2/32&reserved=1,2,3#W")!!
        assertEquals(Protocol.WIREGUARD, wg.protocol)
        assertEquals("1,2,3", wg.params["reserved"])
    }

    private val key = Base64Util.encode(ByteArray(32) { it.toByte() })

    @Test
    fun amneziaConf() {
        val conf = """
            [Interface]
            PrivateKey = $key
            Address = 10.8.0.2/32
            DNS = 1.1.1.1
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 86
            H1 = 1234567890
            [Peer]
            PublicKey = $key
            AllowedIPs = 0.0.0.0/0
            Endpoint = vpn.example.com:51820
            PersistentKeepalive = 25
        """.trimIndent()
        val parsed = WgConf.parse(conf)!!
        assertTrue(parsed.isAmnezia)
        val uapi = parsed.toUapi("1.2.3.4:51820")
        assertTrue("jc=4" in uapi)
        assertTrue("endpoint=1.2.3.4:51820" in uapi)
        assertTrue("private_key=" + "000102".padEnd(6, '0') in uapi)
        val node = LinkParser.parse("awg://" + Base64Util.encodeUrlSafe(conf) + "#Германия")!!
        assertEquals(Protocol.AMNEZIAWG, node.protocol)
        assertEquals("Германия", node.name)
        assertEquals("vpn.example.com", node.address)
    }

    @Test
    fun subscriptionBodyWithMeta() {
        val body = """
            #profile-title: base64:${Base64Util.encodeUrlSafe("Мой VPN\nБыстрые серверы")}
            #profile-update-interval: 6
            #announce: Плановые работы
            #providerid ABC123
            happ://routing/onadd/${Base64Util.encodeUrlSafe("""{"Name":"RU","DirectSites":["geosite:category-ru"]}""")}
            $reality
            vless://x@0.0.0.0:1#Подписка истекла
        """.trimIndent()
        val parsed = SubscriptionParser.parse(Base64Util.encode(body.encodeToByteArray()), "s1",
            mapOf("subscription-userinfo" to "upload=10; download=20; total=100; expire=1790951622", "profile-update-interval" to "12"))
        assertEquals(1, parsed.nodes.size)
        assertEquals(listOf("Подписка истекла"), parsed.notices)
        assertEquals("Мой VPN", parsed.meta.title)
        assertEquals("Быстрые серверы", parsed.meta.description)
        assertEquals(12, parsed.meta.updateIntervalHours) // заголовок приоритетнее тела
        assertEquals("Плановые работы", parsed.meta.announce)
        assertEquals("ABC123", parsed.meta.providerId)
        assertEquals(30L, parsed.meta.usage!!.used)
        assertEquals(1, parsed.routingLinks.size)
    }

    @Test
    fun remnawaveJsonKeepsRouting() {
        val json = """[{"remarks":"🇩🇪 DE","dns":{"servers":["1.1.1.1"]},"inbounds":[{"tag":"socks","port":10808,"listen":"127.0.0.1","protocol":"socks"},{"tag":"api","protocol":"dokodemo-door","port":10085}],
            "outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"de.example.com","port":443,"users":[{"id":"u","flow":"xtls-rprx-vision","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"x.com"}}},{"tag":"direct","protocol":"freedom"}],
            "routing":{"rules":[{"type":"field","inboundTag":["socks"],"domain":["geosite:category-ru"],"outboundTag":"direct"},{"type":"field","inboundTag":["api"],"outboundTag":"api"}]},"api":{"tag":"api","services":["StatsService"]}}]"""
        val parsed = SubscriptionParser.parse(json, "s")
        val node = parsed.nodes.single()
        assertTrue(node.isJson)
        assertEquals("de.example.com", node.address)
        val config = Json.parseToJsonElement(XrayConfigBuilder.build(node)).jsonObject
        assertFalse("api" in config)
        val inbounds = config["inbounds"]!!.jsonArray
        assertEquals(1, inbounds.size)
        assertEquals("tun", inbounds[0].jsonObject["protocol"]!!.jsonPrimitive.content)
        val rules = config["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertEquals("53", rules.first()["port"]!!.jsonPrimitive.content)
        assertTrue(rules.any { r -> (r["inboundTag"] as? JsonArray)?.map { it.jsonPrimitive.content } == listOf("tun") && r["outboundTag"]!!.jsonPrimitive.content == "direct" })
        // Правило для удалённого API-inbound'а исчезло вместе с ним.
        assertFalse(rules.any { it["outboundTag"]?.jsonPrimitive?.content == "api" })
    }

    @Test
    fun generatedConfigHasNoLocalPorts() {
        val node = LinkParser.parse(reality)!!
        val text = XrayConfigBuilder.build(node, BuildOptions(routing = RoutingProfile.RUSSIA))
        val config = Json.parseToJsonElement(text).jsonObject
        val inbounds = config["inbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("tun"), inbounds.map { it["protocol"]!!.jsonPrimitive.content })
        assertTrue(inbounds.none { "port" in it || "listen" in it })
        assertFalse("api" in config)
        val (sites, ips) = XrayConfigBuilder.referencedGeoTags(text)
        assertTrue("category-ru" in sites)
        assertTrue("ru" in ips)
        val dropped = Json.parseToJsonElement(XrayConfigBuilder.dropGeoTags(text, setOf("category-ru", "category-gov-ru", "category-bank-ru", "tld-ru", "private", "category-ads"), emptySet())).jsonObject
        assertFalse("category-ru" in dropped.toString())
    }

    @Test
    fun providerMeta() {
        val m = ProviderMeta.parse(
            mapOf(
                "Profile-Title" to "Name VPN",
                "subscription-userinfo" to "upload=0; download=2153701362; total=0; expire=1790951622000",
                "banner-text" to "base64:" + Base64Util.encode("Акция!".encodeToByteArray()),
                "premium-url" to "https://p.example",
                "hide-url" to "1",
                "per-app-proxy-mode" to "bypass",
                "per-app-proxy-list" to "com.a.b,com.c.d",
                "routing-enable" to "0",
                "x-hwid-active" to "true",
            ),
        )
        assertEquals("Name VPN", m.title)
        assertEquals(1790951622L, m.usage!!.expire)
        assertTrue(m.usage!!.unlimited)
        assertEquals("Акция!", m.banner!!.text)
        assertEquals("https://p.example", m.banner!!.buttonUrl)
        assertTrue(m.hideUrl)
        assertEquals(listOf("com.a.b", "com.c.d"), m.perApp!!.add)
        assertEquals(false, m.routingEnabled)
        assertTrue(m.hwidActive)
        assertTrue(ProviderMeta.parse(mapOf("subscription-userinfo" to "0")).hideUsage)
    }

    @Test
    fun routingLinksAndProfiles() {
        val payload = Base64Util.encodeUrlSafe("""{"Name":"China","GlobalProxy":"false","DirectSites":["geosite:cn"],"BlockIp":["geoip:ads"],"DomainStrategy":"ipifnonmatch","LastUpdated":"1700000000"}""")
        val add = RoutingLinks.parse("happ://routing/add/$payload") as RoutingCommand.Import
        assertFalse(add.activate)
        val on = RoutingLinks.parse("incy://routing/onadd/$payload") as RoutingCommand.Import
        assertTrue(on.activate)
        val auto = RoutingLinks.parse("://autorouting/onadd/https://github.com/u/r/blob/main/p.json") as RoutingCommand.Import
        assertTrue(auto.autoUpdate)
        assertTrue(auto.isUrl)
        assertEquals(RoutingCommand.Off, RoutingLinks.parse("happ://routing/off"))
        assertEquals(RoutingCommand.Off, RoutingLinks.parse("off"))
        val p = RoutingProfile.parseAny(on.payload, "id")!!
        assertEquals("China", p.name)
        assertFalse(p.globalProxy)
        assertEquals("IPIfNonMatch", p.domainStrategy)
        assertEquals(1700000000L, p.lastUpdated)
        assertEquals("https://raw.githubusercontent.com/u/r/main/p.json", RoutingProfile.githubRaw("https://github.com/u/r/blob/main/p.json"))
    }

    @Test
    fun deepLinks() {
        assertEquals(DeepLink.AddSubscription("https://sub.example.com/abc"), DeepLinks.parse("happ://add/https://sub.example.com/abc"))
        assertEquals(DeepLink.Connect, DeepLinks.parse("incy://connect"))
        assertEquals(DeepLink.Toggle, DeepLinks.parse("tracc://toggle"))
        assertTrue(DeepLinks.parse(reality) is DeepLink.ImportNodes)
        assertTrue(DeepLinks.parse("incy://import/" + Base64Util.encodeUrlSafe(reality)) is DeepLink.ImportNodes)
        assertTrue(DeepLinks.parse("happ://routing/onadd/e30=") is DeepLink.Routing)
        // Тестовый вектор из @incy/link-encoder 1.3.0.
        val crypt = DeepLinks.parse("incy://crypt1/bsW7F09Hhc9eXuIcdlSEtAEwJn8JIt3B9_ab578WD4uaNC4rx7Jw8xompb2UdbxVWe7Hz2DVCzJ-z0B3vjzH7e4tMtFtm4c0fZaSZLSNCT9CTg")
        assertEquals(DeepLink.AddSubscription("https://example.com/sub/abc", "Test", hidden = true), crypt)
        assertNotNull(DeepLinks.parse("v2raytun://import/https://sub.example.com/x"))
    }

    @Test
    fun probeConfigRequiresAuth() {
        val node = LinkParser.parse(reality)!!
        val probe = Json.parseToJsonElement(XrayConfigBuilder.buildProbe(node, 23456, "u", "p")).jsonObject
        val inbound = (probe["inbounds"] as JsonArray).single() as JsonObject
        assertEquals("127.0.0.1", inbound["listen"]!!.jsonPrimitive.content)
        assertEquals("password", inbound["settings"]!!.jsonObject["auth"]!!.jsonPrimitive.content)
    }
}
