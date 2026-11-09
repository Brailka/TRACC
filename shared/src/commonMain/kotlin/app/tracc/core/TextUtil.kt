package app.tracc.core

/** Символы, которые провайдеры используют как «пустую строку»: пустой символ Брайля и пробелы нулевой ширины. */
private val INVISIBLE = setOf('⠀', '​', '‌', '‍', '⁠', '﻿', 'ㅤ', 'ᅟ', 'ᅠ')

/** Текст, в котором нет ни одного видимого символа. */
fun String.isVisiblyBlank(): Boolean = all { it.isWhitespace() || it in INVISIBLE }

/**
 * Процентное декодирование UTF-8 как у `URLDecoder`: `%XX` → байты, `+` → пробел.
 * Неполная или битая последовательность оставляется как есть.
 */
fun urlDecode(s: String, plusAsSpace: Boolean = true): String {
    if ('%' !in s && (!plusAsSpace || '+' !in s)) return s
    val out = StringBuilder(s.length)
    val bytes = ArrayList<Byte>()
    fun flush() {
        if (bytes.isNotEmpty()) {
            out.append(bytes.toByteArray().decodeToString())
            bytes.clear()
        }
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val hex = if (c == '%' && i + 2 < s.length + 0 && i + 2 <= s.lastIndex) s.substring(i + 1, i + 3).toIntOrNull(16) else null
        when {
            hex != null -> { bytes.add(hex.toByte()); i += 3 }
            else -> {
                flush()
                out.append(if (c == '+' && plusAsSpace) ' ' else c)
                i++
            }
        }
    }
    flush()
    return out.toString()
}

/** Байты в hex строчными буквами. */
fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** Кодовая точка в начале строки — с учётом суррогатных пар (эмодзи, флаги). */
fun String.firstCodePoint(): Int {
    if (isEmpty()) return -1
    val hi = this[0]
    if (hi.isHighSurrogate() && length > 1 && this[1].isLowSurrogate()) {
        return 0x10000 + ((hi.code - 0xD800) shl 10) + (this[1].code - 0xDC00)
    }
    return hi.code
}
