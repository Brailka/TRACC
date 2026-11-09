package app.tracc.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import app.tracc.BuildConfig
import java.security.MessageDigest
import java.util.Locale

/**
 * Идентификатор устройства для лимита устройств Remnawave (и совместимых панелей).
 *
 * Считается как у INCY: SHA-256 от ANDROID_ID и характеристик железа, оформленный как UUID.
 * ANDROID_ID на Android 8+ уникален для пары «устройство + ключ подписи приложения», поэтому
 * HWID стабилен между переустановками, но не позволяет связать пользователя между разными приложениями.
 */
object Hwid {
    @Volatile private var cached: String? = null

    @SuppressLint("HardwareIds")
    fun get(context: Context): String {
        cached?.let { return it }
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val seed = listOf(androidId, Build.MANUFACTURER, Build.MODEL, Build.BRAND, Build.DEVICE, Build.PRODUCT, Build.BOARD, Build.HARDWARE)
            .joinToString("|")
        val deviceId = sha256(seed)
        val hex = sha256("tracc_hwid_$deviceId")
        val uuid = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
            .uppercase(Locale.ROOT)
        cached = uuid
        return uuid
    }

    val deviceModel: String
        get() {
            val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.titlecase(Locale.ROOT) }
            val model = Build.MODEL.orEmpty()
            return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
        }

    /** TRACC всегда представляется своим именем: провайдер различает клиента правилом `user-agent CONTAINS TRACC`. */
    val USER_AGENT: String = "TRACC/${BuildConfig.VERSION_NAME}"

    /** Заголовки запроса подписки, как у Happ/INCY: по ним Remnawave считает устройства. */
    fun headers(context: Context, sendHwid: Boolean): Map<String, String> = buildMap<String, String> {
        put("x-device-os", "Android")
        put("x-ver-os", Build.VERSION.RELEASE.orEmpty())
        put("x-device-model", deviceModel)
        put("x-app-version", BuildConfig.VERSION_NAME)
        put("Accept-Language", Locale.getDefault().toLanguageTag())
        if (sendHwid) {
            val id = Hwid.get(context)
            put("x-hwid", id)
            put("X-Device-ID", id)
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
