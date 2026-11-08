package app.rvc

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import app.rvc.ui.MainViewModel
import app.rvc.ui.RvcRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.onPermissionResult(result.resultCode == RESULT_OK)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Защита от tapjacking: касания сквозь чужие оверлеи не принимаются (подтверждения импорта и отключения).
        window.decorView.filterTouchesWhenObscured = true
        setContent { RvcRoot(vm) }
        lifecycleScope.launch {
            vm.permissionRequests.collect {
                val intent = app.rvc.vpn.RvcVpnService.prepare(this@MainActivity)
                if (intent == null) {
                    vm.onPermissionResult(true)
                } else {
                    runCatching { vpnPermission.launch(intent) }.onFailure { vm.onPermissionResult(false) }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) {
            handleIntent(intent)
            vm.onAppOpen(this)
        }
    }

    override fun onStart() {
        super.onStart()
        vm.setForeground(true)
        vm.checkUpdates(manual = false)
    }

    override fun onStop() {
        vm.setForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        vm.handleDeepLink(data)
    }
}
