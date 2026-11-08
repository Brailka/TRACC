package app.rvc.core.parser

import kotlin.io.encoding.Base64

object Base64Util {
    /** Понимает обычный и URL-safe алфавит, с паддингом и без. */
    fun decode(input: String): ByteArray? {
        val clean = input.filterNot { it.isWhitespace() }
        if (clean.isEmpty()) return null
        val normalized = clean.replace('-', '+').replace('_', '/').trimEnd('=')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { Base64.Default.decode(padded) }.getOrNull()
    }

    fun decodeToString(input: String): String? = decode(input)?.decodeToString()

    fun encodeUrlSafe(text: String): String = Base64.UrlSafe.encode(text.encodeToByteArray()).trimEnd('=')

    fun encode(bytes: ByteArray): String = Base64.Default.encode(bytes)
}
