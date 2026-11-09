package app.tracc.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.tracc.App

/** Автоподключение после перезагрузки, если оно включено и VPN был включён до выключения телефона. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = App.store
        if (!store.settings.value.autoStartOnBoot || !store.vpnWasOn) return
        val node = store.selected.value ?: return
        if (TraccVpnService.prepare(context) != null) return
        runCatching { TraccVpnService.start(context, node) }
    }
}
