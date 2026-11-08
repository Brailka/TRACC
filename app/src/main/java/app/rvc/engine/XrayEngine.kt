package app.rvc.engine

import android.content.Context
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Обёртка над libv2ray (AndroidLibXrayLite). Ядро вызывается через рефлексию, чтобы сборка
 * оставалась рабочей и без aar (тогда приложение честно сообщает, что ядра нет).
 */
object XrayEngine {
    private const val LIB = "libv2ray.Libv2ray"
    private const val CALLBACK = "libv2ray.CoreCallbackHandler"

    private var controller: Any? = null
    var lastError: String? = null
        private set

    val isAvailable: Boolean by lazy { runCatching { Class.forName(LIB) }.isSuccess }

    val version: String? by lazy {
        if (!isAvailable) null
        else runCatching { libMethod("checkVersionX")?.invoke(null) as? String }.getOrNull()
    }

    private val measureMethod: Method? by lazy { libMethod("measureOutboundDelay") }

    private const val MEASURE_TIMEOUT_MS = 10_000L

    private val measurers = Executors.newCachedThreadPool { task ->
        Thread(task, "rvc-measure").apply { isDaemon = true }
    }

    private fun libMethod(name: String): Method? =
        runCatching { Class.forName(LIB).methods.firstOrNull { it.name == name } }.getOrNull()

    private val ready = CountDownLatch(1)

    /** Каталог ядра: сюда кладутся урезанные под текущую конфигурацию geoip.dat и geosite.dat. */
    fun assetDir(context: Context) = java.io.File(context.filesDir, "xray").apply { mkdirs() }

    fun init(context: Context) {
        val dir = assetDir(context).absolutePath
        thread(name = "xray-init") {
            if (isAvailable) runCatching {
                val m = libMethod("initCoreEnv") ?: libMethod("initV2Env")
                if (m != null && m.parameterCount == 2) m.invoke(null, dir, "")
            }
            ready.countDown()
        }
    }

    val isRunning: Boolean get() = controller != null

    /** Запуск с TUN: ядро читает пакеты прямо из дескриптора, локальные порты не нужны. */
    @Synchronized
    fun start(config: String, tunFd: Int): Result<Unit> = runCatching {
        ready.await()
        check(isAvailable) { "ядро Xray не встроено — добавьте libv2ray.aar в app/libs" }
        stop()
        controller = launch(config, tunFd)
        lastError = null
    }.onFailure { lastError = (it.cause ?: it).message }

    /** Отдельный экземпляр ядра без TUN (для загрузки через туннель и проверок). */
    fun startProbe(config: String): Any? = runCatching {
        ready.await()
        if (!isAvailable) null else launch(config, 0)
    }.getOrNull()

    fun stopProbe(probe: Any?) {
        val c = probe ?: return
        runCatching { c.javaClass.methods.first { it.name == "stopLoop" }.invoke(c) }
    }

    private fun launch(config: String, tunFd: Int): Any {
        val handlerClass = Class.forName(CALLBACK)
        val handler = Proxy.newProxyInstance(handlerClass.classLoader, arrayOf(handlerClass)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "RvcCoreCallback"
                else -> when (method.returnType) {
                    java.lang.Long.TYPE -> 0L
                    java.lang.Integer.TYPE -> 0
                    java.lang.Boolean.TYPE -> false
                    else -> null
                }
            }
        }
        val ctrl = libMethod("newCoreController")?.invoke(null, handler)
            ?: error("newCoreController недоступен")
        val startLoop = ctrl.javaClass.methods.first { it.name == "startLoop" }
        when (startLoop.parameterCount) {
            2 -> startLoop.invoke(ctrl, config, tunFd)
            1 -> {
                check(tunFd == 0) { "эта версия ядра не поддерживает TUN" }
                startLoop.invoke(ctrl, config)
            }
            else -> error("неизвестная сигнатура startLoop")
        }
        return ctrl
    }

    @Synchronized
    fun stop() {
        val c = controller ?: return
        runCatching { c.javaClass.methods.first { it.name == "stopLoop" }.invoke(c) }
        controller = null
    }

    private val statsMethod: Method? by lazy {
        runCatching { Class.forName("libv2ray.CoreController").methods.firstOrNull { it.name == "queryAllOutboundTrafficStats" } }.getOrNull()
    }

    /** Байты (вверх, вниз) с прошлого вызова: счётчики ядра сбрасываются при чтении. */
    fun takeTraffic(): Pair<Long, Long>? {
        val c = controller ?: return null
        val text = runCatching { statsMethod?.invoke(c) as? String }.getOrNull() ?: return null
        var up = 0L
        var down = 0L
        text.split(';').forEach { entry ->
            val parts = entry.split(',')
            if (parts.size < 3) return@forEach
            val value = parts[2].trim().toLongOrNull() ?: return@forEach
            if (parts[1].contains("up", true)) up += value else down += value
        }
        return up to down
    }

    private val runningMeasure: Method? by lazy {
        runCatching { Class.forName("libv2ray.CoreController").methods.firstOrNull { it.name == "measureDelay" } }.getOrNull()
    }

    /** Задержка через запущенный туннель (HTTP-запрос изнутри ядра), -1 если не прошёл. */
    fun measureRunning(url: String): Long {
        val c = controller ?: return -1
        val m = runningMeasure ?: return -1
        val task = measurers.submit<Long> { (m.invoke(c, url) as Number).toLong() }
        return runCatching { task.get(MEASURE_TIMEOUT_MS + 3000, TimeUnit.MILLISECONDS) }
            .onFailure { task.cancel(true) }
            .getOrDefault(-1L)
    }

    fun measureDelay(config: String, url: String): Long {
        val m = measureMethod ?: return -1
        val task = measurers.submit<Long> {
            ready.await()
            (m.invoke(null, config, url) as Number).toLong()
        }
        return runCatching { task.get(MEASURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .onFailure { task.cancel(true) }
            .getOrDefault(-1L)
    }
}
