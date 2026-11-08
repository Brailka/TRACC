package app.rvc.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.rvc.App
import java.util.concurrent.TimeUnit

/**
 * Фоновая синхронизация раз в час: подписки по их интервалу (profile-update-interval),
 * autorouting-профили, push-сообщения провайдеров и напоминания об окончании подписки.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = App.store
        if (store.settings.value.autoUpdateSubs) App.subscriptions.updateAll(force = false)
        App.routing.refreshAuto()
        App.routing.refreshGeo()
        ProviderNotifier.pollRemote(applicationContext, store)
        store.subscriptions.value.forEach { ProviderNotifier.checkExpiry(applicationContext, store, it) }
        checkAppUpdate()
        return Result.success()
    }

    /** Раз в сутки — проверка новой версии RVC; уведомление один раз на версию. */
    private suspend fun checkAppUpdate() {
        val stamp = java.io.File(applicationContext.filesDir, "update_check")
        val last = stamp.takeIf { it.exists() }?.readText()?.split(' ')
        val lastAt = last?.getOrNull(0)?.toLongOrNull() ?: 0
        val notified = last?.getOrNull(1)?.toIntOrNull() ?: 0
        if (System.currentTimeMillis() - lastAt < 20 * 3_600_000L) return
        val info = App.updates.check().getOrNull()
        var mark = notified
        if (info != null && info.versionCode > notified) {
            ProviderNotifier.notifyUpdate(applicationContext, info.versionName)
            mark = info.versionCode
        }
        stamp.writeText("${System.currentTimeMillis()} $mark")
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
