package app.rvc.engine

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor

/**
 * Протокол между VPN-сервисом и процессом `:awg` (см. [AwgService]). Написан вручную вместо AIDL:
 * генератор AIDL под Windows ломается на путях вида `C:\Users\user`.
 */
interface AwgApi {
    /** Поднимает туннель на копии tun; возвращает handle >= 0 или код ошибки < 0. */
    fun turnOn(tun: ParcelFileDescriptor, settings: String): Int
    fun turnOff(handle: Int)

    /** Копии UDP-сокетов туннеля, чтобы VPN-сервис защитил их от петли через себя. */
    fun socketV4(handle: Int): ParcelFileDescriptor?
    fun socketV6(handle: Int): ParcelFileDescriptor?

    /** UAPI `get=1`: счётчики и время рукопожатия. */
    fun config(handle: Int): String?
    fun version(): String?

    companion object {
        private const val DESCRIPTOR = "app.rvc.engine.AwgApi"
        private const val TURN_ON = IBinder.FIRST_CALL_TRANSACTION
        private const val TURN_OFF = TURN_ON + 1
        private const val SOCKET_V4 = TURN_ON + 2
        private const val SOCKET_V6 = TURN_ON + 3
        private const val CONFIG = TURN_ON + 4
        private const val VERSION = TURN_ON + 5

        /** Серверная сторона: принимает вызовы и передаёт их [impl]. */
        fun stub(impl: AwgApi): Binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code !in TURN_ON..VERSION) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(DESCRIPTOR)
                reply ?: return false
                reply.writeNoException()
                when (code) {
                    TURN_ON -> {
                        val tun = data.readFileDescriptor()
                        reply.writeInt(impl.turnOn(tun, data.readString().orEmpty()))
                    }
                    TURN_OFF -> impl.turnOff(data.readInt())
                    SOCKET_V4 -> writeFd(reply, impl.socketV4(data.readInt()))
                    SOCKET_V6 -> writeFd(reply, impl.socketV6(data.readInt()))
                    CONFIG -> reply.writeString(impl.config(data.readInt()))
                    VERSION -> reply.writeString(impl.version())
                }
                return true
            }
        }

        /** Клиентская сторона поверх полученного из bindService [IBinder]. */
        fun proxy(binder: IBinder): AwgApi = object : AwgApi {
            private fun <T> call(code: Int, write: Parcel.() -> Unit = {}, read: Parcel.() -> T): T {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.write()
                    binder.transact(code, data, reply, 0)
                    reply.readException()
                    return reply.read()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun turnOn(tun: ParcelFileDescriptor, settings: String) =
                call(TURN_ON, { writeFileDescriptor(tun.fileDescriptor); writeString(settings) }) { readInt() }

            override fun turnOff(handle: Int) = call(TURN_OFF, { writeInt(handle) }) { }
            override fun socketV4(handle: Int) = call(SOCKET_V4, { writeInt(handle) }) { readFd() }
            override fun socketV6(handle: Int) = call(SOCKET_V6, { writeInt(handle) }) { readFd() }
            override fun config(handle: Int) = call(CONFIG, { writeInt(handle) }) { readString() }
            override fun version() = call(VERSION) { readString() }
        }

        private fun writeFd(reply: Parcel, pfd: ParcelFileDescriptor?) {
            reply.writeInt(if (pfd != null) 1 else 0)
            pfd?.use { reply.writeFileDescriptor(it.fileDescriptor) }
        }

        private fun Parcel.readFd(): ParcelFileDescriptor? = if (readInt() != 0) readFileDescriptor() else null
    }
}
