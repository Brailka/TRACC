package app.rvc.core

import app.rvc.core.parser.Base64Util
import app.rvc.core.parser.LinkParser
import app.rvc.core.routing.RoutingProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunnelPlanTest {
    @Test
    fun xrayPlan() {
        val node = LinkParser.parse("vless://id@1.2.3.4:443?security=none#X")!!
        val plan = TunnelPlan.create(node, meta = null, routing = RoutingProfile.RUSSIA, mtu = 8500, ipv6 = false)
        assertFalse(plan.usesAwg)
        assertNull(plan.awg)
        assertEquals(8500, plan.mtu)
        assertEquals(listOf(TunnelPlan.XRAY_GATEWAY), plan.dns)
        assertTrue("\"tun\"" in plan.xrayConfig!!)
    }

    @Test
    fun awgPlan() {
        val key = Base64Util.encode(ByteArray(32) { it.toByte() })
        val conf = "[Interface]\nPrivateKey = $key\nAddress = 10.8.0.2/32\nMTU = 1380\nJc = 4\n" +
            "[Peer]\nPublicKey = $key\nAllowedIPs = 0.0.0.0/0\nEndpoint = vpn.example.com:51820\n"
        val node = LinkParser.parse("awg://" + Base64Util.encodeUrlSafe(conf))!!
        val plan = TunnelPlan.create(node, meta = null, routing = null, mtu = 8500, ipv6 = false)
        assertTrue(plan.usesAwg)
        assertEquals(1380, plan.mtu)
        assertEquals(listOf("10.8.0.2/32"), plan.addresses)
        assertEquals(listOf("1.1.1.1"), plan.dns)
        assertTrue("endpoint=5.6.7.8:51820" in plan.awgSettings("5.6.7.8:51820"))
    }
}
