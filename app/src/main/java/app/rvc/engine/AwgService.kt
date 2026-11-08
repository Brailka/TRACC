package app.rvc.engine

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.amnezia.awg.GoBackend

/**
 * AmneziaWG живёт в своём процессе `:awg`. В основном процессе уже работает рантайм Go из Xray
 * (libgojni), а второй рантайм Go (libwg-go) в том же процессе роняет приложение ещё при загрузке
 * библиотеки. Здесь грузится только libwg-go; App.onCreate в этом процессе ничего не делает.
 */
class AwgService : Service() {
    private val binder = AwgApi.stub(object : AwgApi {
        override fun turnOn(tun: ParcelFileDescriptor, settings: String): Int {
            if (!GoBackend.isAvailable) return -100
            // Владение descriptor'ом переходит к amneziawg-go: он закроет его в turnOff.
            return GoBackend.awgTurnOn("tun0", tun.detachFd(), settings)
        }

        override fun turnOff(handle: Int) {
            if (GoBackend.isAvailable) runCatching { GoBackend.awgTurnOff(handle) }
        }

        override fun socketV4(handle: Int): ParcelFileDescriptor? = socket(GoBackend.awgGetSocketV4(handle))

        override fun socketV6(handle: Int): ParcelFileDescriptor? = socket(GoBackend.awgGetSocketV6(handle))

        override fun config(handle: Int): String? = if (GoBackend.isAvailable) GoBackend.awgGetConfig(handle) else null

        override fun version(): String? = if (GoBackend.isAvailable) GoBackend.awgVersion() else null
    })

    private fun socket(fd: Int): ParcelFileDescriptor? = if (fd < 0) null else runCatching { ParcelFileDescriptor.fromFd(fd) }.getOrNull()

    override fun onBind(intent: Intent?): IBinder = binder
}
