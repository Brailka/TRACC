package app.tracc.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle

/**
 * Ссылки из подписки (баннеры, поддержка, сайт) приходят от сервера, поэтому открываются только
 * безопасные схемы: `intent://` и прочие могли бы запустить чужие Activity с произвольными данными.
 */
object Links {
    private val allowed = setOf("https", "http", "tg", "mailto")

    fun isSafe(url: String?): Boolean {
        val scheme = url?.substringBefore("://", url.substringBefore(':'))?.lowercase() ?: return false
        return scheme in allowed && url.length <= 4096
    }

    fun open(context: Context, url: String?): Boolean {
        if (!isSafe(url)) return false
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }
}

/** Копирование с пометкой «чувствительные данные»: Android 13+ не показывает их в превью буфера. */
fun copySensitive(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}
