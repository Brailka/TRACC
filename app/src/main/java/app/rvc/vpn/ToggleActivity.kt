package app.rvc.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.rvc.App
import app.rvc.MainActivity

/**
 * Невидимая Activity для виджета и ярлыков: переключает VPN без открытия интерфейса.
 * Нужна, потому что системное разрешение VPN можно запросить только из Activity.
 */
class ToggleActivity : ComponentActivity() {
    companion object {
        const val ACTION_CONNECT = "app.rvc.action.CONNECT"
        const val ACTION_DISCONNECT = "app.rvc.action.DISCONNECT"
    }

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) App.store.selected.value?.let { RvcVpnService.start(this, it) }
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
            RvcVpnService.stop(this)
        } else if (!running) {
            val node = App.store.selected.value
            val request = RvcVpnService.prepare(this)
            when {
                node == null -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                request != null -> return consent.launch(request)
                else -> RvcVpnService.start(this, node)
            }
        }
        finish()
    }
}
