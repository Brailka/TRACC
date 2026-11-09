package app.tracc.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FetchRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** Уходят только на исходный хост и не передаются при перенаправлении на другой домен. */
    val privateHeaders: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 15_000,
    val proxy: String? = null,
    val maxBytes: Long = 8L * 1024 * 1024,
    val resolve: Map<String, String> = emptyMap(),
    val outFile: String? = null,
    val hostHeader: String? = null,
    /** Только статус ответа, без тела — для быстрой проверки доступности. */
    val discardBody: Boolean = false,
)

@Serializable
data class FetchResponse(
    val ok: Boolean = false,
    val status: Int = 0,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val error: String? = null,
    val finalUrl: String? = null,
    val bytes: Long = 0,
    val elapsedMs: Long = 0,
)

@Serializable
private data class PingTarget(val host: String, val port: Int)

@Serializable
private data class DelayRequest(val url: String, val proxy: String?, val method: String, val attempts: Int, val timeoutMs: Long)

@Serializable
private data class PingBatch(val targets: List<PingTarget>, val timeoutMs: Long, val attempts: Int, val concurrency: Int)

@Serializable
private data class DohRequest(val name: String, val dohUrl: String, val bootstrapIp: String? = null, val timeoutMs: Long = 5000, val ipv6: Boolean = false)

@Serializable
private data class DohResponse(val ok: Boolean = false, val ips: List<String> = emptyList(), val error: String? = null)

/**
 * Сетевой слой на Rust (libtraccnet.so): HTTP через rustls, пинг на tokio, DoH и обрезка геофайлов через mmap.
 * Все методы блокирующие — вызывать из Dispatchers.IO.
 */
object RustNet {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    val isAvailable: Boolean by lazy { runCatching { System.loadLibrary("traccnet") }.isSuccess }

    val version: String? get() = if (isAvailable) runCatching { nativeVersion() }.getOrNull() else null

    fun fetch(request: FetchRequest): FetchResponse {
        if (!isAvailable) return FetchResponse(error = "сетевой модуль не загружен")
        return runCatching { json.decodeFromString<FetchResponse>(nativeFetch(json.encodeToString(request))) }
            .getOrElse { FetchResponse(error = it.message) }
    }

    /** Задержка HTTP-запроса через прокси (`socks5h://…`), лучшая из нескольких попыток; -1 — не ответил. */
    fun delay(url: String, proxy: String?, method: String = "HEAD", attempts: Int = 2, timeoutMs: Long = 8_000): Long {
        if (!isAvailable) return -1
        return runCatching { nativeDelay(json.encodeToString(DelayRequest(url, proxy, method, attempts, timeoutMs))) }.getOrDefault(-1)
    }

    /** TCP-задержка до каждого адреса (мс, -1 — не ответил). Время DNS не учитывается. */
    fun tcpPing(targets: List<Pair<String, Int>>, timeoutMs: Long = 3000, attempts: Int = 3, concurrency: Int = 32): List<Long> {
        if (!isAvailable || targets.isEmpty()) return List(targets.size) { -1L }
        val request = PingBatch(targets.map { PingTarget(it.first, it.second) }, timeoutMs, attempts, concurrency)
        return runCatching { json.decodeFromString<List<Long>>(nativeTcpPing(json.encodeToString(request))) }
            .getOrElse { List(targets.size) { -1L } }
    }

    fun icmpPing(host: String, timeoutMs: Long = 2500): Long =
        if (isAvailable) runCatching { nativeIcmpPing(host, timeoutMs) }.getOrDefault(-1) else -1

    fun dohResolve(name: String, dohUrl: String, bootstrapIp: String?, ipv6: Boolean = false): List<String> {
        if (!isAvailable) return emptyList()
        val request = DohRequest(name, dohUrl, bootstrapIp, ipv6 = ipv6)
        return runCatching { json.decodeFromString<DohResponse>(nativeDoh(json.encodeToString(request))).ips }.getOrDefault(emptyList())
    }

    /** Оставляет в геофайле только нужные теги. Возвращает число записей или -1. */
    fun trimGeo(src: String, dst: String, tags: Collection<String>): Long =
        if (isAvailable) runCatching { nativeTrimGeo(src, dst, tags.joinToString(",")) }.getOrDefault(-1) else -1

    fun geoTags(src: String): Set<String> =
        if (isAvailable) runCatching { nativeGeoTags(src).split(',').filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet()) else emptySet()

    @JvmStatic private external fun nativeFetch(request: String): String
    @JvmStatic private external fun nativeDelay(request: String): Long
    @JvmStatic private external fun nativeTcpPing(request: String): String
    @JvmStatic private external fun nativeIcmpPing(host: String, timeoutMs: Long): Long
    @JvmStatic private external fun nativeDoh(request: String): String
    @JvmStatic private external fun nativeTrimGeo(src: String, dst: String, tags: String): Long
    @JvmStatic private external fun nativeGeoTags(src: String): String
    @JvmStatic private external fun nativeVersion(): String
}
