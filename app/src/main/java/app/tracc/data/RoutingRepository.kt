package app.tracc.data

import android.content.Context
import app.tracc.core.routing.RoutingCommand
import app.tracc.core.routing.RoutingProfile
import app.tracc.engine.GeoManager
import app.tracc.net.FetchRequest
import app.tracc.net.RustNet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Профили маршрутизации: импорт по ссылкам Happ/INCY, привязка к подпискам, autorouting и геофайлы. */
class RoutingRepository(private val context: Context, private val store: Store) {
    private companion object {
        const val DEFAULT_GEO_HOURS = 168
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    sealed interface Outcome {
        data class Imported(val profile: RoutingProfile, val activated: Boolean) : Outcome
        data object Disabled : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun apply(command: RoutingCommand, subscriptionId: String? = null): Outcome = withContext(Dispatchers.IO) {
        when (command) {
            RoutingCommand.Off -> {
                store.updateSettings { it.copy(routingEnabled = false) }
                Outcome.Disabled
            }
            is RoutingCommand.Import -> {
                val text = if (command.isUrl) download(command.payload) ?: return@withContext Outcome.Failed("Не удалось скачать профиль")
                else command.payload
                val parsed = RoutingProfile.parseAny(text, UUID.randomUUID().toString().take(12))
                    ?: return@withContext Outcome.Failed("Профиль маршрутизации не распознан")
                val profile = parsed.copy(
                    sourceUrl = if (command.autoUpdate && command.isUrl) RoutingProfile.githubRaw(command.payload) else null,
                    sourceUpdatedAt = if (command.autoUpdate) System.currentTimeMillis() else 0,
                    subscriptionId = subscriptionId,
                )
                val old = store.routing.value.firstOrNull { it.name == profile.name && it.subscriptionId == subscriptionId }
                val saved = upsert(profile)
                val activate = command.activate || store.routing.value.size == 1 && subscriptionId != null
                if (activate) store.updateSettings { it.copy(routingEnabled = true, activeRoutingId = saved.id) }
                // Геофайлы перекачиваются, только если профиль новый, сменились адреса, вырос LastUpdated или файлов нет.
                val filesMissing = GeoManager.Kind.entries.any { kind -> GeoManager.profileFile(context, saved, kind)?.exists() == false }
                val changed = old == null || old.geoipUrl != saved.geoipUrl || old.geositeUrl != saved.geositeUrl || saved.lastUpdated > old.lastUpdated
                if (saved.usesCustomGeo && (changed || filesMissing)) scope.launch { downloadGeo(saved) }
                Outcome.Imported(saved, activate)
            }
        }
    }

    /** Профиль с тем же именем (и той же подпиской) обновляется, а не дублируется. */
    private fun upsert(profile: RoutingProfile): RoutingProfile {
        var result = profile
        store.updateRouting { list ->
            val old = list.firstOrNull { it.name == profile.name && it.subscriptionId == profile.subscriptionId }
            if (old == null) {
                list + profile
            } else {
                // Более старый профиль не заменяет новый — если только провайдер не сменил ссылки на геобазы:
                // новые адреса применяются независимо от LastUpdated (так же делает INCY).
                val geoMoved = old.geoipUrl != profile.geoipUrl || old.geositeUrl != profile.geositeUrl
                if (!geoMoved && old.lastUpdated > 0 && profile.lastUpdated in 1 until old.lastUpdated) {
                    result = old
                    return@updateRouting list
                }
                result = profile.copy(
                    id = old.id,
                    sourceUrl = profile.sourceUrl ?: old.sourceUrl,
                    updateIntervalSec = old.updateIntervalSec,
                )
                list.map { if (it.id == old.id) result else it }
            }
        }
        return result
    }

    private fun download(url: String): String? {
        val response = RustNet.fetch(
            FetchRequest(RoutingProfile.githubRaw(url), headers = mapOf("User-Agent" to Hwid.USER_AGENT), timeoutMs = 20_000, maxBytes = 2L * 1024 * 1024),
        )
        return response.body?.takeIf { response.ok }
    }

    fun downloadGeo(profile: RoutingProfile): Result<Unit> {
        val result = GeoManager.downloadForProfile(context, profile)
        store.updateRouting { list ->
            list.map {
                if (it.id != profile.id) it
                else it.copy(geoError = result.exceptionOrNull()?.message, geoCheckedAt = if (result.isSuccess) System.currentTimeMillis() else it.geoCheckedAt)
            }
        }
        return result
    }

    fun remove(id: String) {
        store.updateRouting { list -> list.filterNot { it.id == id } }
        if (store.settings.value.activeRoutingId == id) store.updateSettings { it.copy(activeRoutingId = RoutingProfile.RUSSIA.id) }
        GeoManager.Kind.entries.forEach { GeoManager.custom(context, id, it).delete() }
    }

    fun removeForSubscription(subscriptionId: String) {
        store.routing.value.filter { it.subscriptionId == subscriptionId }.forEach { remove(it.id) }
    }

    fun activate(id: String) = store.updateSettings { it.copy(routingEnabled = true, activeRoutingId = id) }

    /**
     * Периодическая проверка геофайлов (INCY `routing-geo-update-interval`, часы; по умолчанию раз в неделю).
     * Благодаря `.sha256` неизменившиеся файлы не перекачиваются.
     */
    suspend fun refreshGeo() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        for (p in store.routing.value.filter { it.usesCustomGeo }) {
            val owner = store.subscriptions.value.firstOrNull { it.id == p.subscriptionId }
            val hours = owner?.rawMeta?.get("routing-geo-update-interval")?.trim()?.toIntOrNull() ?: DEFAULT_GEO_HOURS
            if (hours <= 0 || now - p.geoCheckedAt < hours * 3_600_000L) continue
            downloadGeo(p)
        }
    }

    /** Перекачивает autorouting-профили, у которых истёк интервал. */
    suspend fun refreshAuto(force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        for (p in store.routing.value) {
            val url = p.sourceUrl ?: continue
            if (!force && now - p.sourceUpdatedAt < p.updateIntervalSec * 1000) continue
            val text = download(url) ?: continue
            val fresh = RoutingProfile.parseAny(text, p.id) ?: continue
            val geoChanged = fresh.geoipUrl != p.geoipUrl || fresh.geositeUrl != p.geositeUrl
            val updated = fresh.copy(id = p.id, sourceUrl = url, sourceUpdatedAt = now, updateIntervalSec = p.updateIntervalSec, subscriptionId = p.subscriptionId)
            store.updateRouting { list -> list.map { if (it.id == p.id) updated else it } }
            if (geoChanged && updated.usesCustomGeo) downloadGeo(updated)
        }
    }
}
