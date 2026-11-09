package app.tracc.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.tracc.App
import app.tracc.MainActivity

/**
 * Невидимая Activity для виджета и ярлыков: переключает VPN без открытия интерфейса.
 * Нужна, потому что системное разрешение VPN можно запросить только из Activity.
 */
class ToggleActivity : ComponentActivity() {
    companion object {
        const val ACTION_CONNECT = "app.tracc.action.CONNECT"
        const val ACTION_DISCONNECT = "app.tracc.action.DISCONNECT"
    }

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) App.store.selected.value?.let { TraccVpnService.start(this, it) }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val running = VpnBus.state.value.let { it is VpnState.Connected || it is VpnState.Connecting }
        val wantOn = when (intent?.action) {
            ACTION_CONNECT -> true
            ACTION_DISCONNECT -> false
            else -> !running
        }
        if (!wantOn) {
            TraccVpnService.stop(this)
        } else if (!running) {
            val node = App.store.selected.value
            val request = TraccVpnService.prepare(this)
            when {
                node == null -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                request != null -> return consent.launch(request)
                else -> TraccVpnService.start(this, node)
            }
        }
        finish()
    }
}
