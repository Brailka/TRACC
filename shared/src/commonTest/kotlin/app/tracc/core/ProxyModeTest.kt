package app.tracc.core

import app.tracc.core.parser.LinkParser
import app.tracc.core.xray.BuildOptions
import app.tracc.core.xray.ProxyListen
import app.tracc.core.xray.XrayConfigBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxyModeTest {
    private val node = LinkParser.parse("vless://id@1.2.3.4:443?security=none#X")!!

    private fun build(proxy: ProxyListen?, raw: Boolean = false): JsonObject {
        val n = if (raw) node.copy(raw = """{"outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"}]}""") else node
        return Json.parseToJsonElement(XrayConfigBuilder.build(n, BuildOptions(proxy = proxy))).jsonObject
    }

    @Test
    fun lanRequiresPassword() {
        assertFailsWith<IllegalArgumentException> { build(ProxyListen(10808, lan = true, user = "", pass = "")) }
        val cfg = build(ProxyListen(10808, lan = true, user = "tracc", pass = "secret"))
        val inbound = cfg["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals("0.0.0.0", inbound["listen"]!!.jsonPrimitive.content)
        assertEquals("password", inbound["settings"]!!.jsonObject["auth"]!!.jsonPrimitive.content)
    }

    @Test
    fun loopbackBlockedOnlyInProxyMode() {
        for (raw in listOf(false, true)) {
            val proxied = build(ProxyListen(10808, lan = true, user = "u", pass = "p"), raw).toString()
            assertTrue("127.0.0.0/8" in proxied && "finalRules" in proxied, "raw=$raw")
            assertTrue("\"blackhole\"" in proxied, "raw=$raw")
            val tun = build(null, raw).toString()
            assertFalse("finalRules" in tun, "raw=$raw")
        }
    }
}
