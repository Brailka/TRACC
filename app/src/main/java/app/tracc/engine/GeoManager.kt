package app.tracc.engine

import android.content.Context
import app.tracc.core.routing.RoutingProfile
import app.tracc.core.xray.XrayConfigBuilder
import app.tracc.net.FetchRequest
import app.tracc.net.RustNet
import java.io.File
import java.security.MessageDigest

/**
 * Геофайлы для ядра.
 *
 * Источники по приоритету: файлы профиля маршрутизации (его Geoipurl/Geositeurl) → полные базы
 * Loyalsoldier, если уже скачаны → урезанные базы из APK. Перед каждым запуском из источника
 * вырезаются только теги, на которые ссылается конфигурация, и кладутся в каталог ядра:
 * ядро тратит память лишь на то, что реально используется.
 */
object GeoManager {
    const val DEFAULT_GEOIP = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"
    const val DEFAULT_GEOSITE = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"
    private const val GEO_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    enum class Kind(val file: String) { IP("geoip.dat"), SITE("geosite.dat") }

    private val tagCache = HashMap<String, Pair<Long, Set<String>>>()

    private fun dir(context: Context) = File(context.filesDir, "geo").apply { mkdirs() }

    fun bundled(context: Context, kind: Kind): File {
        val out = File(dir(context), "bundled-${kind.file}")
        val stamp = File(dir(context), "bundled.version")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (!out.exists() || stamp.takeIf { it.exists() }?.readText() != version) {
            context.assets.open(kind.file).use { input -> out.outputStream().use { input.copyTo(it) } }
            if (kind == Kind.SITE) stamp.writeText(version)
        }
        return out
    }

    fun full(context: Context, kind: Kind) = File(dir(context), "full-${kind.file}")

    fun custom(context: Context, profileId: String, kind: Kind) = File(dir(context), "${safe(profileId)}-${kind.file}")

    private fun urlOf(profile: RoutingProfile, kind: Kind) = if (kind == Kind.IP) profile.geoipUrl else profile.geositeUrl

    /**
     * Файл профиля. Стандартные базы Loyalsoldier (их указывает большинство профилей Happ/INCY)
     * хранятся одной общей копией, а не по копии на каждый профиль.
     */
    fun profileFile(context: Context, profile: RoutingProfile, kind: Kind): File? {
        val url = urlOf(profile, kind).takeIf { it.isNotBlank() } ?: return null
        return if (url == DEFAULT_GEOIP || url == DEFAULT_GEOSITE) full(context, kind) else custom(context, profile.id, kind)
    }

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40)

    fun tagsOf(file: File): Set<String> {
        if (!file.exists()) return emptySet()
        synchronized(tagCache) {
            tagCache[file.absolutePath]?.let { (stamp, tags) -> if (stamp == file.lastModified()) return tags }
        }
        val tags = RustNet.geoTags(file.absolutePath)
        synchronized(tagCache) { tagCache[file.absolutePath] = file.lastModified() to tags }
        return tags
    }

    private val downloading = java.util.concurrent.ConcurrentHashMap.newKeySet<Kind>()

    /** Полные базы Loyalsoldier — в фоне и не чаще раза в 6 часов, чтобы не тратить трафик на повторы. */
    private fun scheduleFullDownload(context: Context, kind: Kind) {
        val stamp = File(dir(context), "full-${kind.file}.attempt")
        if (System.currentTimeMillis() - stamp.lastModified() < 6 * 3_600_000L) return
        if (!downloading.add(kind)) return
        stamp.writeText(System.currentTimeMillis().toString())
        kotlin.concurrent.thread(name = "tracc-geo", isDaemon = true) {
            try {
                download(if (kind == Kind.IP) DEFAULT_GEOIP else DEFAULT_GEOSITE, full(context, kind))
            } finally {
                downloading.remove(kind)
            }
        }
    }

    /** Скачивает файл (с проверкой `.sha256`, если сервер его публикует). Блокирующий вызов. */
    fun download(url: String, target: File): Result<Unit> = runCatching {
        val shaFile = File(target.path + ".sha256")
        val remoteSha = RustNet.fetch(FetchRequest("$url.sha256", headers = mapOf("User-Agent" to GEO_UA), timeoutMs = 10_000, maxBytes = 512))
            .takeIf { it.ok }?.body?.trim()?.take(64)?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c.isLetterOrDigit() } }
        if (remoteSha != null && target.exists() && shaFile.takeIf { it.exists() }?.readText() == remoteSha) return@runCatching
        val tmp = File(target.path + ".download")
        val response = RustNet.fetch(
            FetchRequest(url, headers = mapOf("User-Agent" to GEO_UA), timeoutMs = 180_000, maxBytes = 96L * 1024 * 1024, outFile = tmp.absolutePath),
        )
        check(response.ok) { response.error ?: "HTTP ${response.status}" }
        if (remoteSha != null) {
            val actual = sha256(tmp)
            check(actual == remoteSha) { tmp.delete(); "контрольная сумма не совпала" }
        }
        check(tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))) { "не удалось сохранить файл" }
        remoteSha?.let { shaFile.writeText(it) }
    }

    /** Скачивает геофайлы профиля; при ошибке прежние файлы остаются на месте. */
    fun downloadForProfile(context: Context, profile: RoutingProfile): Result<Unit> = runCatching {
        profileFile(context, profile, Kind.IP)?.let { download(profile.geoipUrl, it).getOrThrow() }
        profileFile(context, profile, Kind.SITE)?.let { download(profile.geositeUrl, it).getOrThrow() }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Готовит урезанные геофайлы под конфигурацию. Возвращает конфигурацию, из которой при
     * необходимости убраны теги, которых нет ни в одном источнике (иначе ядро не запустится).
     */
    fun prepare(context: Context, config: String, profile: RoutingProfile?, allowDownload: Boolean = true): String {
        val (sites, ips) = XrayConfigBuilder.referencedGeoTags(config)
        val missingSites = prepareKind(context, Kind.SITE, sites, profile, allowDownload)
        val missingIps = prepareKind(context, Kind.IP, ips, profile, allowDownload)
        return XrayConfigBuilder.dropGeoTags(config, missingSites, missingIps)
    }

    private fun prepareKind(context: Context, kind: Kind, tags: Set<String>, profile: RoutingProfile?, allowDownload: Boolean): Set<String> {
        val target = File(XrayEngine.assetDir(context), kind.file)
        if (tags.isEmpty()) {
            target.delete()
            return emptySet()
        }
        val candidates = buildList {
            profile?.let { profileFile(context, it, kind) }?.takeIf { it.exists() }?.let(::add)
            full(context, kind).takeIf { it.exists() }?.let(::add)
            add(bundled(context, kind))
        }
        var source = candidates.firstOrNull { tags.all(tagsOf(it)::contains) }
        if (source == null && allowDownload && !full(context, kind).exists()) scheduleFullDownload(context, kind)
        val chosen = source ?: candidates.maxBy { file -> tags.count(tagsOf(file)::contains) }
        val available = tagsOf(chosen)
        val present = tags.filter(available::contains)
        if (present.isEmpty()) target.delete()
        else if (RustNet.trimGeo(chosen.absolutePath, target.absolutePath, present) < 0) chosen.copyTo(target, overwrite = true)
        return tags - present.toSet()
    }
}
