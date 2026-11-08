package app.rvc.core

import app.rvc.core.parser.Base64Util
import app.rvc.core.parser.LinkParser
import app.rvc.core.provider.ProviderMeta
import kotlin.test.assertEquals
import kotlin.test.Test

/** «Р» (U+0420) в UTF-8 — `D0 A0`; байт `A0` — это NBSP в Latin-1, на нём легко сломать декодирование. */
class EncodingTest {
    private fun b64(s: String) = Base64Util.encode(s.encodeToByteArray())

    @Test
    fun cyrillicEr() {
        assertEquals("Работаем. Россия", ProviderMeta.decodeValue("base64:" + b64("Работаем. Россия")))
        assertEquals("Россия", LinkParser.parse("vless://id@1.2.3.4:443?security=none#%D0%A0%D0%BE%D1%81%D1%81%D0%B8%D1%8F")!!.name)
        assertEquals("Рава raw", LinkParser.parse("vless://id@1.2.3.4:443?security=none#Рава raw")!!.name)
    }
}
