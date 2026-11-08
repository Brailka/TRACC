package app.rvc.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Круглые флаги стран из `assets/flags/<iso>.webp` (набор circle-flags, MIT). Картинки лежат в APK:
 * никаких запросов в сеть, по которым можно было бы понять, какие серверы у пользователя.
 */
object Flags {
    private val cache = LruCache<String, ImageBitmap>(64)
    @Volatile private var available: Set<String>? = null

    /** Код ISO 3166 из эмодзи-флага (пара региональных индикаторов) или из названия страны. */
    fun iso(flag: String?, name: String): String? {
        flag?.let { f ->
            val cps = f.codePoints().toArray()
            if (cps.size == 2 && cps.all { it in 0x1F1E6..0x1F1FF }) {
                return String(charArrayOf('a' + (cps[0] - 0x1F1E6), 'a' + (cps[1] - 0x1F1E6)))
            }
            return null
        }
        val words = name.lowercase().split(' ', '-', '_', '.', ',', '(', ')', '|', '/').filter { it.isNotEmpty() }
        return words.firstNotNullOfOrNull { NAMES[it] }
    }

    /** Флаг как картинка или null, если такого в наборе нет. Декодирование ~1 мс, результат кэшируется. */
    fun load(context: Context, iso: String): ImageBitmap? {
        cache.get(iso)?.let { return it }
        val known = available ?: runCatching { context.assets.list("flags").orEmpty().map { it.substringBefore('.') }.toSet() }
            .getOrDefault(emptySet()).also { available = it }
        if (iso !in known) return null
        val bitmap = runCatching { context.assets.open("flags/$iso.webp").use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return null
        return bitmap.asImageBitmap().also { cache.put(iso, it) }
    }

    /** Частые названия стран в именах серверов без эмодзи. */
    private val NAMES = mapOf(
        "россия" to "ru", "russia" to "ru", "рф" to "ru",
        "германия" to "de", "germany" to "de", "deutschland" to "de",
        "нидерланды" to "nl", "netherlands" to "nl", "голландия" to "nl", "holland" to "nl",
        "финляндия" to "fi", "finland" to "fi",
        "швеция" to "se", "sweden" to "se",
        "норвегия" to "no", "norway" to "no",
        "польша" to "pl", "poland" to "pl",
        "латвия" to "lv", "latvia" to "lv",
        "литва" to "lt", "lithuania" to "lt",
        "эстония" to "ee", "estonia" to "ee",
        "франция" to "fr", "france" to "fr",
        "англия" to "gb", "великобритания" to "gb", "britain" to "gb", "uk" to "gb", "england" to "gb", "london" to "gb", "лондон" to "gb",
        "сша" to "us", "usa" to "us", "америка" to "us", "america" to "us",
        "канада" to "ca", "canada" to "ca",
        "турция" to "tr", "turkey" to "tr", "türkiye" to "tr",
        "казахстан" to "kz", "kazakhstan" to "kz",
        "украина" to "ua", "ukraine" to "ua",
        "беларусь" to "by", "belarus" to "by",
        "грузия" to "ge", "georgia" to "ge",
        "армения" to "am", "armenia" to "am",
        "швейцария" to "ch", "switzerland" to "ch",
        "австрия" to "at", "austria" to "at",
        "чехия" to "cz", "czech" to "cz", "czechia" to "cz",
        "италия" to "it", "italy" to "it",
        "испания" to "es", "spain" to "es",
        "португалия" to "pt", "portugal" to "pt",
        "румыния" to "ro", "romania" to "ro",
        "болгария" to "bg", "bulgaria" to "bg",
        "сербия" to "rs", "serbia" to "rs",
        "венгрия" to "hu", "hungary" to "hu",
        "молдова" to "md", "moldova" to "md",
        "япония" to "jp", "japan" to "jp",
        "сингапур" to "sg", "singapore" to "sg",
        "гонконг" to "hk", "hongkong" to "hk",
        "индия" to "in", "india" to "in",
        "оаэ" to "ae", "uae" to "ae", "дубай" to "ae", "dubai" to "ae",
        "израиль" to "il", "israel" to "il",
        "австралия" to "au", "australia" to "au",
        "бразилия" to "br", "brazil" to "br",
    )
}
