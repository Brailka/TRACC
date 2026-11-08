package app.rvc.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import app.rvc.core.parser.WgConf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Движок AmneziaWG: туннель целиком в userspace (amneziawg-go) в отдельном процессе [AwgService].
 * Вызовы синхронные через Binder; привязка ждёт подключения, поэтому [start] и [version] нельзя
 * вызывать из главного потока.
 */
object AwgEngine {
    @Volatile private var handle = -1
    @Volatile private var remote: AwgApi? = null
    @Volatile private var remoteBinder: IBinder? = null
    private var connection: ServiceConnection? = null

    val isRunning: Boolean get() = handle >= 0

    @Synchronized
    private fun bind(context: Context): AwgApi {
        remote?.takeIf { remoteBinder?.isBinderAlive == true }?.let { return it }
        val ready = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                remoteBinder = service
                remote = AwgApi.proxy(service)
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Процесс :awg умер — туннеля больше нет; монитор VPN-сервиса заметит отсутствие рукопожатий.
                remote = null
                handle = -1
            }
        }
        connection?.let { old -> runCatching { context.applicationContext.unbindService(old) } }
        check(context.applicationContext.bindService(Intent(context, AwgService::class.java), conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) {
            "не удалось запустить процесс AmneziaWG"
        }
        connection = conn
        check(ready.await(5, TimeUnit.SECONDS)) { "процесс AmneziaWG не ответил" }
        return remote ?: error("процесс AmneziaWG не ответил")
    }

    /** Версия amneziawg-go. Поднимает процесс :awg только на время запроса. */
    fun version(context: Context): String? = runCatching {
        val wasBound = remote != null
        bind(context).version().also { if (!wasBound && !isRunning) release(context) }
    }.getOrNull()

    @Synchronized
    fun start(service: VpnService, tun: ParcelFileDescriptor, conf: WgConf, endpoint: String): Result<Unit> = runCatching {
        stop()
        val awg = bind(service)
        val h = awg.turnOn(tun, conf.toUapi(endpoint))
        check(h != -100) { "в сборке нет libwg-go.so" }
        check(h >= 0) { "AmneziaWG не принял конфигурацию (код $h)" }
        handle = h
        // RVC и так исключён из своего VPN, но сокеты всё равно защищаем — как amneziawg-android.
        listOf(awg.socketV4(h), awg.socketV6(h)).forEach { pfd -> pfd?.use { service.protect(it.fd) } }
    }

    @Synchronized
    fun stop(context: Context? = null) {
        val h = handle
        handle = -1
        if (h >= 0) runCatching { remote?.turnOff(h) }
        if (context != null) release(context)
    }

    @Synchronized
    private fun release(context: Context) {
        connection?.let { runCatching { context.applicationContext.unbindService(it) } }
        connection = null
        remote = null
    }

    private fun config(): String? {
        val h = handle
        if (h < 0) return null
        return runCatching { remote?.config(h) }.getOrNull()
    }

    /** Суммарные rx/tx байт из UAPI `get=1`. */
    fun stats(): Pair<Long, Long>? {
        val config = config() ?: return null
        var rx = 0L
        var tx = 0L
        config.lineSequence().forEach { line ->
            when {
                line.startsWith("rx_bytes=") -> rx += line.substringAfter('=').toLongOrNull() ?: 0
                line.startsWith("tx_bytes=") -> tx += line.substringAfter('=').toLongOrNull() ?: 0
            }
        }
        return rx to tx
    }

    /** Время последнего рукопожатия (сек с эпохи) — признак того, что сервер отвечает. */
    fun lastHandshake(): Long {
        val config = config() ?: return 0
        return config.lineSequence().filter { it.startsWith("last_handshake_time_sec=") }
            .maxOfOrNull { it.substringAfter('=').toLongOrNull() ?: 0 } ?: 0
    }
}
