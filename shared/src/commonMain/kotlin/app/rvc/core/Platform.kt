package app.rvc.core

/**
 * То немногое, чего нет в общей стандартной библиотеке Kotlin: хеш, AES-GCM, zlib/gzip и границы графем.
 * Android — java.security / javax.crypto / java.util.zip / BreakIterator;
 * iOS — CommonCrypto, libz, Foundation, а AES-GCM — через CryptoKit из Swift (см. iosMain).
 */
expect object Platform {
    fun sha1(data: ByteArray): ByteArray

    /** AES-256-GCM: [cipherWithTag] — шифротекст и 16-байтный тег в конце. null — не расшифровалось. */
    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, cipherWithTag: ByteArray): ByteArray?

    /** Поток zlib (RFC 1950). null — не распаковался или вышел больше [limit] байт. */
    fun inflateZlib(data: ByteArray, limit: Int): ByteArray?

    /** Данные gzip (RFC 1952). null — не распаковались или вышли больше [limit] байт. */
    fun gunzip(data: ByteArray, limit: Int): ByteArray?

    /** Первая графема строки — например, эмодзи-флаг целиком, с модификаторами. */
    fun firstGrapheme(text: String): String?
}
