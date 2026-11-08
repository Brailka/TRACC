package app.rvc.engine

import app.rvc.core.model.ProxyNode
import app.rvc.core.xray.XrayConfigBuilder
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * Временный экземпляр ядра с SOCKS на 127.0.0.1: случайный порт, случайные логин и пароль,
 * живёт только на время одного действия (загрузка подписки через туннель, HEAD-пинг).
 */
object ProbeProxy {
    private val random = SecureRandom()

    fun <T> use(node: ProxyNode, block: (proxyUrl: String) -> T): T? {
        if (node.usesAwgEngine || !XrayEngine.isAvailable) return null
        val port = runCatching { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort } }.getOrNull() ?: return null
        val user = token(12)
        val pass = token(24)
        val config = runCatching { XrayConfigBuilder.buildProbe(node, port, user, pass) }.getOrNull() ?: return null
        val probe = XrayEngine.startProbe(config) ?: return null
        return try {
            block("socks5h://$user:$pass@127.0.0.1:$port")
        } finally {
            XrayEngine.stopProbe(probe)
        }
    }

    private fun token(length: Int): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }
}
