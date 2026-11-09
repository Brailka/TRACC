package app.tracc.vpn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.tracc.R

/** Виджет-переключатель 1×1: зелёный — VPN включён, тёмный — выключен. */
class VpnWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids, VpnBus.state.value)
    }

    companion object {
        private const val OFF_TINT = 0xFF9AA0A6.toInt()
        private const val ON_TINT = 0xFFFFFFFF.toInt()

        fun refresh(context: Context, state: VpnState) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, VpnWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids, state)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, state: VpnState) {
            val on = state is VpnState.Connected || state is VpnState.Connecting
            val toggle = PendingIntent.getActivity(
                context, 0,
                Intent(context, ToggleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val views = RemoteViews(context.packageName, R.layout.widget_vpn).apply {
                setInt(R.id.widget_button, "setBackgroundResource", if (on) R.drawable.widget_on else R.drawable.widget_off)
                setInt(R.id.widget_icon, "setColorFilter", if (on) ON_TINT else OFF_TINT)
                setContentDescription(R.id.widget_button, if (on) "VPN включён, нажмите, чтобы отключить" else "VPN выключен, нажмите, чтобы подключиться")
                setOnClickPendingIntent(R.id.widget_button, toggle)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
