package app.tracc.vpn

import app.tracc.core.model.ProxyNode
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface VpnState {
    data object Idle : VpnState
    data class Connecting(val node: ProxyNode) : VpnState
    data class Connected(val node: ProxyNode, val since: Long) : VpnState
    data class Failed(val message: String, val node: ProxyNode?) : VpnState
}

data class Traffic(
    val upBps: Long = 0,
    val downBps: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
    /** Время замера: значение меняется каждый такт, и таймер на главном экране обновляется в том же кадре. */
    val at: Long = 0,
)

object VpnBus {
    val state = MutableStateFlow<VpnState>(VpnState.Idle)
    val traffic = MutableStateFlow(Traffic())
    /** Сколько проверок подряд узел не ответил (для переключения на другой узел). */
    val failedChecks = MutableStateFlow(0)
}
