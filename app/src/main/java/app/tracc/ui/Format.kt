package app.tracc.ui

import java.util.Locale
import java.util.concurrent.TimeUnit

fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0).toDouble()
    return when {
        b < 1024 -> "${b.toLong()} Б"
        b < 1024 * 1024 -> String.format(Locale.ROOT, "%.0f КБ", b / 1024)
        b < 1024.0 * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f МБ", b / 1024 / 1024)
        b < 1024.0 * 1024 * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f ГБ", b / 1024 / 1024 / 1024)
        else -> String.format(Locale.ROOT, "%.2f ТБ", b / 1024 / 1024 / 1024 / 1024)
    }.replace('.', ',')
}

fun formatSpeed(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/с"

fun formatDuration(ms: Long): String {
    val s = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%02d:%02d", m, sec)
}

/** «осталось 12 дн.», «истекает сегодня», «истекла». */
fun formatExpire(expireSec: Long, now: Long = System.currentTimeMillis()): String {
    val ms = expireSec * 1000 - now
    if (ms <= 0) return "истекла"
    val days = TimeUnit.MILLISECONDS.toDays(ms)
    return when {
        days >= 365 * 20 -> "бессрочно"
        days > 0 -> "осталось ${days} ${plural(days, "день", "дня", "дней")}"
        else -> "истекает сегодня"
    }
}

fun formatDate(expireSec: Long): String =
    java.text.SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("ru")).format(java.util.Date(expireSec * 1000))

fun formatAgo(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0) return "никогда"
    val min = TimeUnit.MILLISECONDS.toMinutes(now - at)
    return when {
        min < 1 -> "только что"
        min < 60 -> "$min мин назад"
        min < 60 * 24 -> "${min / 60} ч назад"
        else -> "${min / 60 / 24} дн. назад"
    }
}

fun plural(n: Long, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1L && m100 != 11L -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}
