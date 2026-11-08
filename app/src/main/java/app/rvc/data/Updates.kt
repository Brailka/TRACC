package app.rvc.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.rvc.BuildConfig
import app.rvc.net.FetchRequest
import app.rvc.net.RustNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class UpdateAsset(val url: String, val sha256: String, val size: Long = 0)

/** `update.json`, публикуемый в каждом релизе на GitHub (scripts/make-update-json.py). */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: List<String> = emptyList(),
    val critical: Boolean = false,
    val assets: Map<String, UpdateAsset> = emptyMap(),
) {
    fun assetForDevice(): UpdateAsset? = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { assets[it] } ?: assets["universal"]
}

/**
 * Обновление из приложения. APK скачивается с GitHub Releases и перед установкой проверяется:
 * SHA-256 из update.json, имя пакета, номер версии и — главное — тот же сертификат подписи, что у
 * установленного приложения. Подменённый или чужой APK установить не получится.
 */
class UpdateRepository(private val context: Context) {
    private val _downloadStatus = MutableStateFlow("")
    val downloadStatus = _downloadStatus.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(context.cacheDir, "updates").apply { mkdirs() }

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()

    private val manifestUrls: List<String>
        get() = listOf(
            "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download/update.json",
            "https://cdn.jsdelivr.net/gh/${BuildConfig.UPDATE_REPO}@updates/update.json",
        )

    suspend fun check(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        var lastError: String? = null
        for (url in manifestUrls) {
            val r = RustNet.fetch(FetchRequest(url, headers = mapOf("User-Agent" to "RVC/${BuildConfig.VERSION_NAME}"), timeoutMs = 15_000, maxBytes = 256 * 1024))
            if (!r.ok) {
                lastError = r.error ?: "HTTP ${r.status}"
                continue
            }
            val info = runCatching { json.decodeFromString(UpdateInfo.serializer(), r.body.orEmpty()) }.getOrNull() ?: continue
            val fresh = info.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            _available.value = fresh
            return@withContext Result.success(fresh)
        }
        Result.failure(IllegalStateException(lastError ?: "не удалось проверить обновления"))
    }

    suspend fun download(info: UpdateInfo): Result<File> = withContext(Dispatchers.IO) {
        var monitor: kotlinx.coroutines.Job? = null
        try { runCatching {
            val asset = info.assetForDevice() ?: error("в релизе нет APK для этого телефона")
            require(app.rvc.core.UpdatePolicy.validVersion(info.versionName) &&
                app.rvc.core.UpdatePolicy.validAsset(asset.url, BuildConfig.UPDATE_REPO, "v${info.versionName}", asset.sha256, asset.size)) {
                "некорректное описание файла обновления"
            }
            val target = File(dir, "rvc-${info.versionCode}.apk")
            if (target.length() == asset.size && sha256(target).equals(asset.sha256, true)) {
                verifyApk(target, info)
                return@runCatching target
            }
            target.delete()
            monitor = launch {
                var previous = 0L
                while (true) {
                    val bytes = File(target.path + ".part").length()
                    val percent = if (asset.size > 0) " · ${(bytes * 100 / asset.size).coerceIn(0, 100)}%" else ""
                    _downloadStatus.value = "Загрузка: ${bytes / 1024} / ${asset.size / 1024} КБ$percent · ${(bytes - previous).coerceAtLeast(0) / 1024} КБ/с"
                    previous = bytes
                    delay(1000)
                }
            }
            dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
            val r = RustNet.fetch(
                FetchRequest(asset.url, headers = mapOf("User-Agent" to "RVC/${BuildConfig.VERSION_NAME}"), timeoutMs = 300_000, maxBytes = asset.size, outFile = target.absolutePath),
            )
            check(r.ok) { r.error ?: "HTTP ${r.status}" }
            monitor?.cancelAndJoin()
            _downloadStatus.value = "Проверка файла и подписи…"
            check(target.length() == asset.size) { "размер файла не совпал с объявленным" }
            val actual = sha256(target)
            if (!actual.equals(asset.sha256, ignoreCase = true)) {
                target.delete()
                error("файл повреждён или подменён: контрольная сумма не совпала")
            }
            runCatching { verifyApk(target, info) }.onFailure { target.delete() }.getOrThrow()
            target
        }.onSuccess { _downloadStatus.value = "Файл проверен. Открывается установщик…" }
            .onFailure {
                if (it is CancellationException) throw it
                _downloadStatus.value = app.rvc.core.UserMessage.readable(it.message)
            }
        } finally { monitor?.cancel() }
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

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, info: UpdateInfo) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: error("скачанный файл не является APK")
        if (archive.packageName != context.packageName) error("APK от другого приложения")
        if (archive.longVersionCode != info.versionCode.toLong()) error("версия APK не совпадает с объявленной")
        val expected = signers(pm.getPackageInfo(context.packageName, flags))
        if (expected.isEmpty() || signers(archive) != expected) error("APK подписан чужим ключом")
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners ?: info.signatures else info.signatures
        val sha = MessageDigest.getInstance("SHA-256")
        return signatures.orEmpty().map { sig -> sha.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
