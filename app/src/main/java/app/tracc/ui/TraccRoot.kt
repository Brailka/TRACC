package app.tracc.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracc.ui.components.GlassPanel
import app.tracc.ui.components.LiveBackground
import app.tracc.ui.components.LocalBackdrop
import app.tracc.ui.components.reducedMotion
import app.tracc.ui.glass.GlassStyles
import app.tracc.ui.glass.rememberGlassBackdrop
import app.tracc.ui.screens.AboutScreen
import app.tracc.ui.screens.AddDialog
import app.tracc.ui.screens.AppsScreen
import app.tracc.ui.screens.ConfirmLinkDialog
import app.tracc.ui.screens.HomeScreen
import app.tracc.ui.screens.ClassicHomeScreen
import app.tracc.ui.screens.InterfaceScreen
import app.tracc.data.InterfaceMode
import app.tracc.ui.screens.LocationsScreen
import app.tracc.ui.screens.MessagesScreen
import app.tracc.ui.screens.RoutingDetailScreen
import app.tracc.ui.screens.RoutingScreen
import app.tracc.ui.screens.SettingsScreen
import app.tracc.ui.screens.SubscriptionsScreen
import app.tracc.ui.theme.Tracc
import app.tracc.ui.theme.TraccMotion
import app.tracc.ui.theme.TraccTheme
import app.tracc.ui.theme.TraccType
import app.tracc.vpn.VpnBus
import app.tracc.vpn.VpnState
import kotlinx.coroutines.delay

@Composable
fun TraccRoot(vm: MainViewModel) {
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val state by VpnBus.state.collectAsStateWithLifecycle()
    TraccTheme(settings.themeMode, settings.accentColor) {
        val backdrop = rememberGlassBackdrop()
        val still = reducedMotion()
        BackHandler(enabled = vm.stack.size > 1) { vm.back() }
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            LiveBackground(backdrop, connected = state is VpnState.Connected) {
                AnimatedContent(
                    targetState = vm.screen,
                    // Вперёд экран въезжает справа, назад уходит туда же: путь входа и выхода совпадает.
                    // Направление берётся из навигации: transitionSpec может вызываться повторно, считать в нём нельзя.
                    transitionSpec = { screenTransition(vm.navForward, still) },
                    label = "screen",
                ) { screen ->
                    when (screen) {
                        Screen.Home -> if (settings.interfaceMode == InterfaceMode.CLASSIC) ClassicHomeScreen(vm) else HomeScreen(vm)
                        Screen.Locations -> LocationsScreen(vm)
                        Screen.Subscriptions -> SubscriptionsScreen(vm)
                        Screen.Routing -> RoutingScreen(vm)
                        is Screen.RoutingDetail -> RoutingDetailScreen(vm, screen.id)
                        Screen.Apps -> AppsScreen(vm)
                        Screen.Settings -> SettingsScreen(vm)
                        Screen.Interface -> InterfaceScreen(vm)
                        Screen.Messages -> MessagesScreen(vm)
                        Screen.About -> AboutScreen(vm)
                    }
                }
                Toasts(vm)
            }
        }
        if (vm.showAdd) AddDialog(vm)
        vm.pendingLink?.let { ConfirmLinkDialog(it, vm::confirmLink) }
    }
}

private fun screenTransition(forward: Boolean, still: Boolean): ContentTransform {
    if (still) return fadeIn(tween(160)) togetherWith fadeOut(tween(120))
    val slide = tween<IntOffset>(320, easing = TraccMotion.Drawer)
    val enterFade = fadeIn(tween(220, easing = TraccMotion.EaseOut))
    val exitFade = fadeOut(tween(160))
    return if (forward) {
        (slideInHorizontally(slide) { it / 3 } + enterFade) togetherWith (slideOutHorizontally(slide) { -it / 6 } + exitFade)
    } else {
        (slideInHorizontally(slide) { -it / 6 } + enterFade) togetherWith (slideOutHorizontally(slide) { it / 3 } + exitFade)
    }
}

/** Всплывающее сообщение снизу: въезжает и уходит по одному пути, держится 2,4 с. */
@Composable
private fun Toasts(vm: MainViewModel) {
    var text by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    var stamp by remember { mutableIntStateOf(0) }
    // Обновление подписки отвечает вибрацией: подтверждение или «отказ», как при подключении.
    val hapticView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(Unit) {
        vm.haptics.collect { ok ->
            val modern = android.os.Build.VERSION.SDK_INT >= 30
            hapticView.performHapticFeedback(
                when {
                    !modern -> android.view.HapticFeedbackConstants.VIRTUAL_KEY
                    ok -> android.view.HapticFeedbackConstants.CONFIRM
                    else -> android.view.HapticFeedbackConstants.REJECT
                },
            )
        }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect {
            text = it
            visible = true
            stamp++
        }
    }
    LaunchedEffect(stamp) {
        if (visible) {
            delay(2400)
            visible = false
        }
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible,
            enter = slideInVertically(tween(260, easing = TraccMotion.EaseOut)) { it } + fadeIn(TraccMotion.fast()),
            exit = slideOutVertically(tween(200, easing = TraccMotion.EaseOut)) { it } + fadeOut(TraccMotion.fast()),
        ) {
            GlassPanel(shape = RoundedCornerShape(18.dp), style = GlassStyles.Toast, padding = 14.dp, live = true) {
                Text(text.orEmpty(), style = TraccType.bodyMedium, color = Tracc.colors.text)
            }
        }
    }
}
