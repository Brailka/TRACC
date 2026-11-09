package app.tracc.vpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.tracc.App
import app.tracc.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Плитка в шторке: включает выбранный сервер и выключает VPN, не открывая приложение. */
class QsTileService : TileService() {
    private var job: Job? = null

    override fun onStartListening() {
        job = CoroutineScope(Dispatchers.Main).launch { VpnBus.state.collect { render(it) } }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
    }

    private fun render(state: VpnState) {
        val tile = qsTile ?: return
        tile.state = when (state) {
            is VpnState.Connected, is VpnState.Connecting -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when (state) {
            is VpnState.Connected -> state.node.name
            is VpnState.Connecting -> "Подключение…"
            else -> App.store.selected.value?.name
        }
        tile.updateTile()
    }

    override fun onClick() {
        when (VpnBus.state.value) {
            is VpnState.Connected, is VpnState.Connecting -> TraccVpnService.stop(this)
            else -> {
                val node = App.store.selected.value
                if (node == null || TraccVpnService.prepare(this) != null) {
                    val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                } else {
                    TraccVpnService.start(this, node)
                }
            }
        }
    }
}
