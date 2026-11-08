package app.rvc.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Тот же JVM-код, что в androidMain: java.security, javax.crypto, java.util.zip, java.text.
actual object Platform {
    actual fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    actual fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, cipherWithTag: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.doFinal(cipherWithTag)
    }.getOrNull()

    actual fun inflateZlib(data: ByteArray, limit: Int): ByteArray? = runCatching {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                check(out.size() <= limit) { "больше $limit байт" }
            }
        } finally {
            inflater.end()
        }
        out.toByteArray().takeIf { inflater.finished() || it.isNotEmpty() }
    }.getOrNull()

    actual fun gunzip(data: ByteArray, limit: Int): ByteArray? = runCatching {
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                check(out.size() <= limit) { "больше $limit байт" }
            }
            out.toByteArray()
        }
    }.getOrNull()

    actual fun firstGrapheme(text: String): String? {
        if (text.isEmpty()) return null
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val end = it.next().takeIf { e -> e > 0 } ?: return null
        return text.substring(0, end)
    }
}
