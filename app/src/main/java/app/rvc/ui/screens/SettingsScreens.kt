package app.rvc.ui.screens

import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import app.rvc.data.ConnectionMode
import app.rvc.data.DEFAULT_MTU
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rvc.BuildConfig
import app.rvc.core.provider.Fragmentation
import app.rvc.data.Hwid
import app.rvc.data.PingType
import app.rvc.data.SortOrder
import app.rvc.data.ThemeMode
import app.rvc.engine.AwgEngine
import app.rvc.engine.XrayEngine
import app.rvc.net.RustNet
import app.rvc.ui.MainViewModel
import app.rvc.ui.Screen
import androidx.compose.ui.text.input.KeyboardType
import app.rvc.ui.components.Choice
import app.rvc.ui.components.ChoiceDialog
import app.rvc.ui.components.DialogAction
import app.rvc.ui.components.Divider
import app.rvc.ui.components.RvcDialog
import app.rvc.ui.components.RvcTextField
import app.rvc.ui.components.EmptyState
import app.rvc.ui.components.GlassPanel
import app.rvc.ui.components.GroupCard
import app.rvc.ui.components.ListRow
import app.rvc.ui.components.PrimaryButton
import app.rvc.ui.components.ScreenScaffold
import app.rvc.ui.components.SectionTitle
import app.rvc.ui.components.ToggleRow
import app.rvc.ui.components.Wordmark
import app.rvc.ui.components.pressable
import app.rvc.ui.formatAgo
import app.rvc.ui.glass.GlassStyles
import app.rvc.ui.theme.Rvc
import app.rvc.ui.theme.RvcType


@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.store.settings.collectAsStateWithLifecycle()
    val subs by vm.store.subscriptions.collectAsStateWithLifecycle()
    val selected by vm.store.selected.collectAsStateWithLifecycle()
    // Фрагментацию может задать провайдер выбранного сервера: тогда она важнее настройки пользователя.
    val providerFragment = subs.firstOrNull { it.id == selected?.sourceId }?.meta?.fragmentation
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var mtuDialog by remember { mutableStateOf(false) }
    var modeDialog by remember { mutableStateOf(false) }
    var portDialog by remember { mutableStateOf(false) }
    var pingDialog by remember { mutableStateOf(false) }
    var sortDialog by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
    val proxy = s.connectionMode == ConnectionMode.PROXY

    ScreenScaffold("Настройки", onBack = { vm.back() }) {
        item { SectionTitle("Режим работы") }
        item {
            GroupCard {
                ListRow(
                    "Режим",
                    if (proxy) "SOCKS5 и HTTP на порту — для приложений с настройкой прокси и раздачи через точку доступа" else "Весь трафик телефона через туннель, без открытых портов",
                    Icons.Rounded.SwapHoriz,
                    value = s.connectionMode.title,
                    chevron = true,
                    onClick = { modeDialog = true },
                )
                if (proxy) {
                    Divider()
                    ListRow("Порт", icon = Icons.Rounded.Dns, value = s.proxyPort.toString(), chevron = true, onClick = { portDialog = true })
                    Divider()
                    ToggleRow("Доступ из локальной сети", "Для устройств в той же Wi-Fi или на точке доступа этого телефона", Icons.Rounded.Lan, s.proxyLan) { v ->
                        // В сети без пароля прокси был бы открыт всем в той же Wi-Fi: пароль включается вместе с доступом.
                        vm.updateSettings(true) {
                            if (v && it.proxyUser.isEmpty()) it.copy(proxyLan = true, proxyUser = "rvc", proxyPass = randomSecret()) else it.copy(proxyLan = v)
                        }
                    }
                    Divider()
                    ToggleRow(
                        "Пароль",
                        if (s.proxyLan) "Обязателен при доступе из локальной сети" else "Без пароля прокси может использовать любое приложение на телефоне",
                        Icons.Rounded.Lock,
                        s.proxyUser.isNotEmpty(),
                        enabled = !s.proxyLan,
                    ) { v ->
                        vm.updateSettings(true) { if (v) it.copy(proxyUser = "rvc", proxyPass = randomSecret()) else it.copy(proxyUser = "", proxyPass = "") }
                    }
                    Divider()
                    val addresses = remember(s.proxyLan, s.proxyPort) { app.rvc.vpn.RvcVpnService.proxyAddresses().joinToString("\n") { "$it:${s.proxyPort}" } }
                    val auth = if (s.proxyUser.isNotEmpty()) "\nЛогин: ${s.proxyUser}   Пароль: ${s.proxyPass}" else ""
                    ListRow("Адрес для подключения", addresses + auth, Icons.Rounded.ContentCopy, onClick = {
                        app.rvc.ui.copySensitive(context, "Прокси", addresses.lines().last() + auth)
                        vm.toast("Скопировано")
                    })
                }
            }
        }
        item { SectionTitle("Подключение") }
        item {
            GroupCard {
                ListRow("Маршрутизация", if (s.routingEnabled) "Включена" else "Выключена", Icons.Rounded.CallSplit, chevron = true, onClick = { vm.navigate(Screen.Routing) })
                Divider()
                ListRow("Приложения", "Раздельное туннелирование", Icons.Rounded.Apps, chevron = true, onClick = { vm.navigate(Screen.Apps) })
                Divider()
                ToggleRow("Блокировать трафик при сбое", "Kill switch: при ошибке туннель не снимается, и трафик не уходит мимо VPN", Icons.Rounded.Lock, s.killSwitch) { v ->
                    vm.updateSettings { it.copy(killSwitch = v) }
                }
                Divider()
                ListRow("Постоянный VPN", "Системные «Постоянная VPN» и «Блокировать соединения без VPN»", Icons.Rounded.VpnKey, chevron = true, onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
                Divider()
                ToggleRow("Автоподключение", "При открытии приложения", Icons.Rounded.PowerSettingsNew, s.autoConnect) { v -> vm.updateSettings { it.copy(autoConnect = v) } }
                Divider()
                ToggleRow("После перезагрузки", "Включать VPN, если он был включён", Icons.Rounded.AutoMode, s.autoStartOnBoot) { v -> vm.updateSettings { it.copy(autoStartOnBoot = v) } }
                Divider()
                ToggleRow("Переключаться при сбое", "Если сервер перестал отвечать — на лучший по пингу", Icons.Rounded.SwapHoriz, s.autoFailover) { v -> vm.updateSettings { it.copy(autoFailover = v) } }
            }
        }
        item { SectionTitle("Сеть") }
        item {
            GroupCard {
                ToggleRow("Локальная сеть напрямую", "Принтеры, роутер и Chromecast без VPN", Icons.Rounded.Lan, s.bypassLan) { v -> vm.updateSettings(true) { it.copy(bypassLan = v) } }
                Divider()
                ToggleRow("IPv6", "Разрешать IPv6-адреса в DNS", Icons.Rounded.Wifi, s.ipv6) { v -> vm.updateSettings(true) { it.copy(ipv6 = v) } }
                Divider()
                ToggleRow("Анализ трафика (sniffing)", "Определение доменов для правил маршрутизации", Icons.Rounded.Tune, s.sniffing) { v -> vm.updateSettings(true) { it.copy(sniffing = v) } }
                Divider()
                if (providerFragment != null) {
                    ToggleRow("Фрагментация TLS", "Задано провайдером", Icons.Rounded.Straighten, providerFragment.enabled, enabled = false) {}
                } else {
                    ToggleRow("Фрагментация TLS", "Обход DPI для TLS/Reality", Icons.Rounded.Straighten, s.fragmentation?.enabled == true) { v ->
                        vm.updateSettings(true) { it.copy(fragmentation = if (v) Fragmentation(true) else null) }
                    }
                }
                Divider()
                ListRow("MTU", "Размер пакета в туннеле", value = s.mtu.toString(), icon = Icons.Rounded.DataUsage, chevron = true, onClick = { mtuDialog = true })
                if (Build.VERSION.SDK_INT >= 33) {
                    Divider()
                    ToggleRow("Скрыть значок VPN", "Работает не на всех прошивках", Icons.Rounded.VisibilityOff, s.hideVpnIcon) { v -> vm.updateSettings(true) { it.copy(hideVpnIcon = v) } }
                }
            }
        }
        item { SectionTitle("Подписки и пинг") }
        item {
            GroupCard {
                ToggleRow("Автообновление", "По интервалу провайдера (profile-update-interval)", Icons.Rounded.Refresh, s.autoUpdateSubs) { v -> vm.updateSettings { it.copy(autoUpdateSubs = v) } }
                Divider()
                ToggleRow("Обновлять при открытии", "Подписки обновляются при запуске приложения", Icons.Rounded.Sync, s.updateOnOpen) { v -> vm.updateSettings { it.copy(updateOnOpen = v) } }
                Divider()
                ToggleRow("Пинг при открытии", "Задержка всех локаций при запуске приложения", Icons.Rounded.Timer, s.pingOnOpen) { v -> vm.updateSettings { it.copy(pingOnOpen = v) } }
                Divider()
                ListRow("Тип пинга", value = s.pingType.title, icon = Icons.Rounded.Speed, chevron = true, onClick = { pingDialog = true })
                Divider()
                ListRow("Сортировка локаций", value = s.sortOrder.title, icon = Icons.Rounded.Sort, chevron = true, onClick = { sortDialog = true })
            }
        }
        item { SectionTitle("Уведомления") }
        item {
            GroupCard {
                ToggleRow("Сообщения провайдера", "Push-сообщения из подписки", Icons.Rounded.Notifications, s.notifyProvider) { v -> vm.updateSettings { it.copy(notifyProvider = v) } }
                Divider()
                ToggleRow("Окончание подписки", "Напоминание за 3 дня", Icons.Rounded.Event, s.notifyExpire) { v -> vm.updateSettings { it.copy(notifyExpire = v) } }
            }
        }
        item { SectionTitle("Оформление и устройство") }
        item {
            GroupCard {
                ListRow("Тема", value = themeTitle(s.themeMode), icon = Icons.Rounded.DarkMode, chevron = true, onClick = { themeDialog = true })
                var accentDialog by remember { mutableStateOf(false) }
                ListRow("Акцентный цвет", value = app.rvc.core.AccentPalette.names[app.rvc.core.AccentPalette.index(s.accentColor)], icon = Icons.Rounded.Palette, chevron = true, onClick = { accentDialog = true })
                if (accentDialog) ChoiceDialog(
                    "Акцентный цвет",
                    app.rvc.core.AccentPalette.names.mapIndexed { i, name -> Choice(i, name) },
                    selected = s.accentColor,
                    onDismiss = { accentDialog = false },
                ) { color -> vm.updateSettings { it.copy(accentColor = color) }; accentDialog = false }
                Divider()
                val hwid = remember { Hwid.get(context) }
                ListRow("HWID · ${hwid.take(8)}…", "Идентификатор устройства для лимита устройств провайдера. Нажмите, чтобы скопировать", Icons.Rounded.Fingerprint, onClick = {
                    app.rvc.ui.copySensitive(context, "HWID", hwid)
                    vm.toast("HWID скопирован")
                })
                Divider()
                ListRow("О приложении", icon = Icons.Rounded.Info, chevron = true, onClick = { vm.navigate(Screen.About) })
            }
        }
    }

    if (modeDialog) {
        ChoiceDialog(
            "Режим работы",
            listOf(
                Choice(ConnectionMode.VPN, "VPN", "Весь трафик телефона идёт через туннель. Локальных портов нет, другие приложения не найдут прокси."),
                Choice(
                    ConnectionMode.PROXY,
                    "Прокси",
                    "VPN не включается; RVC слушает SOCKS5 и HTTP на порту — для Telegram, браузера или другого устройства через точку доступа. " +
                        "Порт видят приложения на телефоне, поэтому пароль включён. AmneziaWG в этом режиме не работает.",
                ),
            ),
            selected = s.connectionMode,
            onDismiss = { modeDialog = false },
        ) { mode ->
            vm.updateSettings(true) {
                if (mode == ConnectionMode.PROXY) {
                    it.copy(connectionMode = mode, proxyUser = it.proxyUser.ifEmpty { "rvc" }, proxyPass = it.proxyPass.ifEmpty { randomSecret() })
                } else {
                    it.copy(connectionMode = mode)
                }
            }
            modeDialog = false
        }
    }

    if (pingDialog) {
        ChoiceDialog(
            "Тип пинга",
            PingType.entries.map { Choice(it, it.title, pingHint(it)) },
            selected = s.pingType,
            onDismiss = { pingDialog = false },
        ) { type ->
            vm.updateSettings { it.copy(pingType = type) }
            pingDialog = false
        }
    }

    if (sortDialog) {
        ChoiceDialog(
            "Сортировка локаций",
            SortOrder.entries.map { Choice(it, it.title) },
            selected = s.sortOrder,
            onDismiss = { sortDialog = false },
        ) { order ->
            vm.updateSettings { it.copy(sortOrder = order) }
            sortDialog = false
        }
    }

    if (themeDialog) {
        ChoiceDialog(
            "Тема",
            ThemeMode.entries.map { Choice(it, themeTitle(it)) },
            selected = s.themeMode,
            onDismiss = { themeDialog = false },
        ) { mode ->
            vm.updateSettings { it.copy(themeMode = mode) }
            themeDialog = false
        }
    }

    if (portDialog) {
        var text by remember { mutableStateOf(s.proxyPort.toString()) }
        val port = text.toIntOrNull()?.takeIf { it in 1024..65535 }
        RvcDialog(
            "Порт прокси",
            onDismiss = { portDialog = false },
            text = "От 1024 до 65535.",
            confirm = DialogAction("Сохранить", enabled = port != null) {
                port?.let { p -> vm.updateSettings(true) { it.copy(proxyPort = p) } }
                portDialog = false
            },
            dismiss = DialogAction("Отмена") { portDialog = false },
        ) {
            RvcTextField(text, { text = it.filter(Char::isDigit).take(5) }, keyboardType = KeyboardType.Number)
        }
    }

    if (mtuDialog) {
        var text by remember { mutableStateOf(s.mtu.toString()) }
        val mtu = text.toIntOrNull()?.takeIf { it in 1280..9000 }
        RvcDialog(
            "MTU туннеля",
            onDismiss = { mtuDialog = false },
            text = "8500 — меньше системных вызовов на трафике, вдвое экономнее для батареи; как у Happ и v2rayNG. 1500 — как у обычной сети. От 1280 до 9000.",
            confirm = DialogAction("Сохранить", enabled = mtu != null) {
                mtu?.let { m -> vm.updateSettings(true) { it.copy(mtu = m) } }
                mtuDialog = false
            },
            dismiss = DialogAction("По умолчанию") {
                vm.updateSettings(true) { it.copy(mtu = DEFAULT_MTU) }
                mtuDialog = false
            },
        ) {
            RvcTextField(text, { text = it.filter(Char::isDigit).take(5) }, keyboardType = KeyboardType.Number)
        }
    }
}

@Composable
fun MessagesScreen(vm: MainViewModel) {
    val notices by vm.store.notices.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = Rvc.colors
    LaunchedEffect(Unit) { vm.store.markNoticesRead() }
    ScreenScaffold("Сообщения", onBack = { vm.back() }) {
        if (notices.isEmpty()) item { EmptyState("Пока пусто", "Здесь появятся объявления и сообщения от провайдера.") }
        items(notices, key = { it.id }) { n ->
            GlassPanel(Modifier.fillMaxWidth().then(if (n.url != null) Modifier.pressable(scale = 0.985f, onClick = { app.rvc.ui.Links.open(context, n.url) }) else Modifier), shape = RoundedCornerShape(22.dp), style = GlassStyles.Toast) {
                Row {
                    Text(n.title, style = RvcType.headline, color = c.text, modifier = Modifier.weight(1f))
                    Text(formatAgo(n.at), style = RvcType.caption, color = c.textTertiary)
                }
                Text(n.body, style = RvcType.body, color = c.textSecondary, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
fun AboutScreen(vm: MainViewModel) {
    val c = Rvc.colors
    val uri = LocalUriHandler.current
    ScreenScaffold("О приложении", onBack = { vm.back() }) {
        item {
            GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp)) {
                Wordmark(Modifier.height(30.dp))
                Text("Версия ${BuildConfig.VERSION_NAME}", style = RvcType.body, color = c.textSecondary, modifier = Modifier.padding(top = 10.dp))
                Text("Клиент для VLESS, VMess, Trojan, Shadowsocks, Hysteria2, SOCKS5, HTTP, WireGuard и AmneziaWG. " +
                    "Совместим с Remnawave, подписками Happ и INCY.", style = RvcType.body, color = c.textSecondary, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            GroupCard {
                ListRow("Ядро", value = XrayEngine.version ?: "нет", icon = Icons.Rounded.Info)
                Divider()
                val context = LocalContext.current
                // Версию отдаёт процесс :awg; запрос через Binder — не в главном потоке.
                val awgVersion by produceState<String?>("…") { value = withContext(Dispatchers.IO) { AwgEngine.version(context) } ?: "нет" }
                ListRow("AmneziaWG", value = awgVersion ?: "нет", icon = Icons.Rounded.Info)
                Divider()
                ListRow("Сетевой модуль", value = RustNet.version ?: "нет", icon = Icons.Rounded.Info)
                Divider()
                val update by app.rvc.App.updates.available.collectAsStateWithLifecycle()
                ListRow(
                    if (update != null) "Обновить до ${update!!.versionName}" else "Проверить обновления",
                    vm.updateBusy,
                    Icons.Rounded.Refresh,
                    onClick = { if (update != null) vm.installUpdate(context) else vm.checkUpdates(manual = true) },
                )
                Divider()
                ListRow("Чат в Telegram", "Новости, вопросы и обратная связь — t.me/rvcapp", Icons.AutoMirrored.Rounded.Send, chevron = true, onClick = {
                    runCatching { uri.openUri("https://t.me/rvcapp") }
                })
                Divider()
                ListRow("Исходный код (GPL-3.0)", "github.com/${BuildConfig.UPDATE_REPO}", Icons.Rounded.Info, chevron = true, onClick = {
                    runCatching { uri.openUri("https://github.com/${BuildConfig.UPDATE_REPO}") }
                })
            }
        }
        item {
            Text(
                "Сторонние компоненты: Xray-core и AndroidLibXrayLite (MPL-2.0), amneziawg-go и amneziawg-android (MIT/Apache-2.0), " +
                    "Rust: reqwest, rustls, tokio, memmap2 (MIT/Apache-2.0), шрифт Inter (OFL), флаги circle-flags (MIT), ключ ссылок crypt1 — @incy/link-encoder (MIT).",
                style = RvcType.caption, color = c.textTertiary, modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
fun ConfirmLinkDialog(link: app.rvc.core.DeepLink, onResult: (Boolean) -> Unit) {
    val c = Rvc.colors
    val (title, text) = when (link) {
        is app.rvc.core.DeepLink.AddSubscription -> "Добавить подписку?" to (link.name
            ?: if (link.hidden) "Адрес скрыт провайдером" else link.url.substringAfter("://").substringBefore('/'))
        is app.rvc.core.DeepLink.ImportNodes -> "Добавить серверы?" to link.text.lineSequence().filter { "://" in it || it.startsWith("[") }
            .take(3).joinToString("\n") { it.substringBefore('?').take(60) }.ifBlank { "Конфигурация из ссылки" }
        is app.rvc.core.DeepLink.Routing -> when (val cmd = link.command) {
            app.rvc.core.routing.RoutingCommand.Off -> "Выключить маршрутизацию?" to "Весь трафик пойдёт по правилам без профиля."
            is app.rvc.core.routing.RoutingCommand.Import -> "Применить маршрутизацию?" to
                "Профиль решает, какие сайты идут через VPN, напрямую или блокируются, и может переопределять DNS. " +
                "Принимайте профили только от своего провайдера." + if (cmd.isUrl) "\n\nИсточник: ${cmd.payload.substringAfter("://").substringBefore('/')}" else ""
        }
        app.rvc.core.DeepLink.Disconnect, app.rvc.core.DeepLink.Toggle -> "Отключить VPN?" to "Ссылка из другого приложения просит отключить VPN."
        else -> "Выполнить действие?" to ""
    }
    val (action, danger) = when (link) {
        is app.rvc.core.DeepLink.AddSubscription, is app.rvc.core.DeepLink.ImportNodes -> "Добавить" to false
        is app.rvc.core.DeepLink.Routing -> (if (link.command == app.rvc.core.routing.RoutingCommand.Off) "Выключить" else "Применить") to false
        app.rvc.core.DeepLink.Disconnect, app.rvc.core.DeepLink.Toggle -> "Отключить" to true
        else -> "Да" to false
    }
    RvcDialog(
        title,
        onDismiss = { onResult(false) },
        text = text.ifEmpty { null },
        confirm = DialogAction(action, danger = danger) { onResult(true) },
        dismiss = DialogAction("Отмена") { onResult(false) },
    )
}

@Composable
fun AddDialog(vm: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    RvcDialog(
        "Добавить",
        onDismiss = { vm.showAdd = false },
        text = "Ссылка на подписку, сервер (vless://, hy2://, awg://…), конфигурация WireGuard или deep link.",
        confirm = DialogAction("Добавить", enabled = text.isNotBlank()) {
            vm.importText(text)
            vm.showAdd = false
        },
        dismiss = DialogAction("Из буфера") {
            val clip = clipboard.getText()?.text
            if (clip.isNullOrBlank()) {
                vm.toast("Буфер обмена пуст")
            } else {
                vm.importText(clip)
                vm.showAdd = false
            }
        },
    ) {
        RvcTextField(text, { text = it }, placeholder = "https://…", singleLine = false)
    }
}

private fun themeTitle(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> "Как в системе"
    ThemeMode.LIGHT -> "Светлая"
    ThemeMode.DARK -> "Тёмная"
}

private fun pingHint(type: PingType): String = when (type.name) {
    "TCP" -> "Время TCP-соединения с сервером — быстро, без запуска ядра"
    "ICMP" -> "Обычный ping; некоторые серверы его не пропускают"
    "PROXY" -> "Настоящий GET-запрос через сервер — точнее всего, но дольше"
    "PROXY_HEAD" -> "Короткий HEAD-запрос через сервер — почти так же точно и быстрее"
    else -> type.title
}

private fun randomSecret(): String = app.rvc.data.newProxySecret()
