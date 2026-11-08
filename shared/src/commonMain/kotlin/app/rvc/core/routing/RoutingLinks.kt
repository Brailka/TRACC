package app.rvc.core.routing


/**
 * Ссылки маршрутизации Happ / INCY. Схема перед `://` не важна (`happ://`, `incy://`, `rvc://` или пусто):
 * - `://routing/add/{base64|url}`, `://routing/onadd/{base64|url}` — разовый импорт (onadd — сразу активировать);
 * - `://autorouting/add/{url}`, `://autorouting/onadd/{url}` — профиль с автообновлением по URL;
 * - `://onadd/{…}`, `://routing/{base64}` — сокращённые формы;
 * - `://routing/off` или значение `off` — выключить маршрутизацию;
 * - `://routing/add?data={base64}` — данные в query-параметре.
 */
sealed interface RoutingCommand {
    data class Import(val payload: String, val activate: Boolean, val autoUpdate: Boolean) : RoutingCommand {
        val isUrl: Boolean get() = payload.startsWith("http://") || payload.startsWith("https://")
    }
    data object Off : RoutingCommand
}

object RoutingLinks {
    fun isRoutingLink(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "off" || listOf("://routing", "://autorouting/", "://onadd/").any { it in t }
    }

    fun parse(text: String): RoutingCommand? {
        val t = text.trim()
        if (t.equals("off", true)) return RoutingCommand.Off
        val lower = t.lowercase()
        val tail = lower.substringAfter("://", lower)
        val rawTail = t.substring(t.length - tail.length)
        val auto = tail.startsWith("autorouting/")
        val path = when {
            auto -> rawTail.substringAfter('/')
            tail.startsWith("routing") -> rawTail.removePrefix(rawTail.substring(0, 7)).removePrefix("/")
            tail.startsWith("onadd/") -> rawTail
            else -> return null
        }
        if (path.equals("off", true) || path.lowercase().startsWith("off?")) return RoutingCommand.Off
        query(path, "data")?.let { data ->
            return RoutingCommand.Import(data, activate = path.lowercase().startsWith("onadd"), autoUpdate = auto)
        }
        val (verb, payload) = when {
            path.lowercase().startsWith("onadd/") -> true to path.substring(6)
            path.lowercase().startsWith("add/") -> false to path.substring(4)
            else -> true to path
        }
        if (payload.isBlank()) return null
        return RoutingCommand.Import(payload.trim(), activate = verb, autoUpdate = auto && payload.startsWith("http"))
    }

    /** Полезная нагрузка вложенной ссылки (для autorouting-файлов вида `.deeplink`). */
    fun extractPayload(text: String): String? = (parse(text) as? RoutingCommand.Import)?.payload?.takeIf { isRoutingLink(text) }

    private fun query(path: String, key: String): String? {
        val q = path.substringAfter('?', "").takeIf { it.isNotEmpty() } ?: return null
        return q.split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
            ?.let { app.rvc.core.urlDecode(it) }
    }
}
