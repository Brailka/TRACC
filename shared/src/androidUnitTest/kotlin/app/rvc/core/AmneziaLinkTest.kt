package app.rvc.core

import app.rvc.core.model.Protocol
import app.rvc.core.parser.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.Deflater

class AmneziaLinkTest {
    /** Ссылка так, как её собирает AmneziaVPN: qCompress (4 байта длины + zlib) и base64url. */
    private fun vpnLink(json: String): String {
        val raw = json.toByteArray()
        val deflater = Deflater().apply { setInput(raw); finish() }
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).putInt(raw.size).array())
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        return "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\n") + "\""

    @Test
    fun amneziaWgContainer() {
        val conf = "[Interface]\nPrivateKey = mBJ6FLwZD5XVS1VCR12ZeuD/v4tS/5fYyxc14AeMDWQ=\nAddress = 10.8.1.2/32\nDNS = \$PRIMARY_DNS, \$SECONDARY_DNS\n" +
            "Jc = 4\nJmin = 10\nJmax = 50\nS1 = 20\nS2 = 30\nH1 = 1\nH2 = 2\nH3 = 3\nH4 = 4\n\n[Peer]\n" +
            "PublicKey = tuNRP2I7iU30qa1SKlhWCkDTfRSLaaUfpirUutybDEQ=\nAllowedIPs = 0.0.0.0/0\nEndpoint = 203.0.113.7:51820\n"
        val lastConfig = "{\"config\":" + quote(conf) + "}"
        val json = "{\"containers\":[{\"container\":\"amnezia-awg\",\"awg\":{\"last_config\":" + quote(lastConfig) + "}}]," +
            "\"defaultContainer\":\"amnezia-awg\",\"description\":\"Мой сервер\",\"dns1\":\"9.9.9.9\",\"dns2\":\"149.112.112.112\",\"hostName\":\"203.0.113.7\"}"
        val node = LinkParser.parse(vpnLink(json))
        assertNotNull(node)
        assertEquals(Protocol.AMNEZIAWG, node!!.protocol)
        assertEquals("Мой сервер", node.name)
        assertEquals("203.0.113.7", node.address)
        assertEquals(51820, node.port)
        assertEquals("9.9.9.9,149.112.112.112", node.params["dns"])
    }

    @Test
    fun xrayContainer() {
        val xray = "{\"outbounds\":[{\"tag\":\"proxy\",\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"198.51.100.4\",\"port\":443," +
            "\"users\":[{\"id\":\"7f1c2d3e-4b5a-4c6d-8e9f-0a1b2c3d4e5f\",\"encryption\":\"none\"}]}]},\"streamSettings\":{\"network\":\"tcp\",\"security\":\"reality\"}}]}"
        val json = "{\"containers\":[{\"container\":\"amnezia-xray\",\"xray\":{\"last_config\":" + quote(xray) + "}}],\"description\":\"Xray\"}"
        val node = LinkParser.parse(vpnLink(json))
        assertNotNull(node)
        assertTrue(node!!.isJson)
        assertEquals("Xray", node.name)
    }
}
