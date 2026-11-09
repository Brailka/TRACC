package app.tracc.engine

import app.tracc.net.FetchRequest
import app.tracc.net.RustNet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class WhitelistState { UNKNOWN, CHECKING, OPEN, WHITELIST, OFFLINE }

/**
 * Определяет «белые списки» оператора. Запросы идут напрямую (само приложение исключено из VPN),
 * поэтому результат отражает сеть провайдера, даже когда туннель включён.
 * Хоть один зарубежный сайт открывается → ограничений нет; ни один, а Яндекс открывается → включены
 * белые списки; ничего не открывается → нет сети.
 *
 * При белых списках зарубежные адреса не молчат: короткие ответы (`generate_204`, заголовки страниц)
 * проходят, а тело ответа почти стоит — ТСПУ душит соединение после первых килобайт. Поэтому зарубежная
 * страница считается открытой, только если скачалась целиком (не меньше [MIN_BYTES]) за таймаут.
 * Проверяется несколько сайтов параллельно, первый успех решает: медленная, но рабочая сеть успевает.
 */
object WhitelistDetector {
    private const val TIMEOUT_MS = 7_000L
    // Без сжатия страницы весят 120–260 КБ: на обычной сети это доли секунды, а при белых списках
    // за 8 секунд проходит не больше ~110 КБ (замер на мобильной сети с включёнными белыми списками).
    private const val MIN_BYTES = 100_000L
    private val FOREIGN = listOf(
        "https://www.google.com/",
        "https://www.wikipedia.org/",
        "https://speed.cloudflare.com/__down?bytes=262144",
    )
    private const val YANDEX = "https://yandex.ru/"

    private val mutex = Mutex()
    private val _state = MutableStateFlow(WhitelistState.UNKNOWN)
    val state = _state.asStateFlow()

    @Volatile
    var checkedAt = 0L
        private set

    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile"

    /** Яндекс: достаточно ответа — он нужен только как признак, что сеть вообще есть. */
    private fun answers(url: String): Boolean {
        val r = RustNet.fetch(FetchRequest(url, headers = mapOf("User-Agent" to BROWSER_UA), timeoutMs = TIMEOUT_MS, discardBody = true))
        return r.ok || r.status in 200..499
    }

    /** Зарубежная страница: успех, только если тело скачалось полностью и оно не заглушка. */
    private fun loads(url: String): Boolean {
        // identity: сжатая страница весит в разы меньше и успела бы проскочить даже удушенное соединение.
        val headers = mapOf("User-Agent" to BROWSER_UA, "Accept-Encoding" to "identity")
        val r = RustNet.fetch(FetchRequest(url, headers = headers, timeoutMs = TIMEOUT_MS, maxBytes = 4L * 1024 * 1024))
        return r.ok && r.bytes >= MIN_BYTES
    }

    /** Запросы блокирующие и отмене не поддаются, поэтому идут в отдельной области: опоздавших не ждём. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun probe(url: String, check: (String) -> Boolean) = io.async { check(url) }

    /**
     * true, если открылась хоть одна зарубежная страница. Сначала одна (на обычной сети этого хватает,
     * ~200 КБ трафика), остальные — параллельно и только если первая не открылась.
     */
    private suspend fun anyForeign(): Boolean {
        if (probe(FOREIGN.first(), ::loads).await()) return true
        val pending = FOREIGN.drop(1).map { probe(it, ::loads) }.toMutableList()
        while (pending.isNotEmpty()) {
            val done = select { pending.forEach { d -> d.onAwait { d } } }
            if (done.await()) return true
            pending.remove(done)
        }
        return false
    }

    suspend fun check(force: Boolean = false): WhitelistState = mutex.withLock {
        if (!force && System.currentTimeMillis() - checkedAt < 60_000 && _state.value != WhitelistState.UNKNOWN) return _state.value
        if (_state.value == WhitelistState.UNKNOWN) _state.value = WhitelistState.CHECKING
        val yandex = probe(YANDEX, ::answers)
        val result = when {
            anyForeign() -> WhitelistState.OPEN
            yandex.await() -> WhitelistState.WHITELIST
            else -> WhitelistState.OFFLINE
        }
        checkedAt = System.currentTimeMillis()
        _state.value = result
        result
    }
}
