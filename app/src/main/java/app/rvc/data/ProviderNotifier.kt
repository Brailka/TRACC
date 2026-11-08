package app.rvc.data

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.rvc.MainActivity
import app.rvc.R
import app.rvc.net.FetchRequest
import app.rvc.net.RustNet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Уведомления от провайдера без Firebase и сторонних серверов:
 * - напоминание об окончании подписки за 3 дня (раз в сутки), если включено у провайдера или в настройках;
 * - push-сообщения из `notifications-url`: JSON-массив `[{"id","title","body","url"}]`, опрашивается в фоне.
 */
object ProviderNotifier {
    const val CHANNEL = "provider"
    private const val EXPIRE_WINDOW_MS = 3 * 24 * 3_600_000L

    @Serializable
    private data class Remote(val id: String, val title: String = "", val body: String = "", val url: String? = null)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun onSubscriptionUpdated(context: Context, store: Store, sub: Subscription) {
        checkExpiry(context, store, sub)
    }

    fun checkExpiry(context: Context, store: Store, sub: Subscription) {
        val expire = (sub.meta.usage?.expire ?: 0) * 1000
        if (expire <= 0) return
        if (!(sub.meta.notifyExpire || store.settings.value.notifyExpire)) return
        val now = System.currentTimeMillis()
        if (expire - now > EXPIRE_WINDOW_MS || now - sub.lastExpireNotice < TimeUnit.HOURS.toMillis(23)) return
        val text = if (expire > now) {
            val days = TimeUnit.MILLISECONDS.toDays(expire - now)
            "Подписка «${sub.title}» истекает ${if (days == 0L) "сегодня" else "через ${days} дн."} — не забудьте продлить."
        } else {
            "Подписка «${sub.title}» истекла."
        }
        notify(context, sub.id.hashCode() + 1, "Скоро окончание подписки", text, sub.meta.expiryBanner?.buttonUrl ?: sub.meta.premiumUrl ?: sub.meta.webPageUrl)
        store.updateSubscription(sub.id) { it.copy(lastExpireNotice = now) }
    }

    /** Опрос push-адресов провайдеров. Новые сообщения показываются и сохраняются в «Сообщениях». */
    fun pollRemote(context: Context, store: Store) {
        val known = store.notices.value.map { it.id }.toSet()
        for (sub in store.subscriptions.value) {
            val url = sub.meta.notificationsUrl ?: continue
            val response = RustNet.fetch(
                FetchRequest(url, headers = mapOf("User-Agent" to Hwid.USER_AGENT),
                    privateHeaders = Hwid.headers(context, true), timeoutMs = 10_000, maxBytes = 256 * 1024),
            )
            if (!response.ok) continue
            val items = runCatching { json.decodeFromString<List<Remote>>(response.body.orEmpty()) }.getOrNull() ?: continue
            val fresh = items.filter { "p-${sub.id}-${it.id}" !in known && (it.title.isNotBlank() || it.body.isNotBlank()) }.take(5)
            fresh.forEach { item ->
                if (store.settings.value.notifyProvider) {
                    notify(context, (sub.id + item.id).hashCode(), item.title.ifBlank { sub.title }, item.body, item.url)
                }
            }
            store.addNotices(fresh.map { ProviderNotice("p-${sub.id}-${it.id}", it.title.ifBlank { sub.title }, it.body, it.url, subscriptionId = sub.id) })
        }
    }

    fun notifyUpdate(context: Context, version: String) =
        notify(context, 4242, "Доступно обновление RVC $version", "Откройте приложение, чтобы обновиться.", null)

    private fun notify(context: Context, id: Int, title: String, text: String, url: String?) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val intent = url?.takeIf { it.startsWith("https://") || it.startsWith("tg://") }
            ?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
            ?: Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}
