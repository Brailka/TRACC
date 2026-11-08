package org.amnezia.awg

/**
 * Привязка к libwg-go.so из amneziawg-android (amneziawg-go v3.1). Имена и сигнатуры методов
 * зашиты в нативной библиотеке, поэтому пакет и класс должны оставаться такими.
 */
object GoBackend {
    val isAvailable: Boolean by lazy { runCatching { System.loadLibrary("wg-go") }.isSuccess }

    @JvmStatic external fun awgTurnOn(ifName: String, tunFd: Int, settings: String): Int
    @JvmStatic external fun awgTurnOff(handle: Int)
    @JvmStatic external fun awgGetSocketV4(handle: Int): Int
    @JvmStatic external fun awgGetSocketV6(handle: Int): Int
    @JvmStatic external fun awgGetConfig(handle: Int): String?
    @JvmStatic external fun awgVersion(): String?
}
