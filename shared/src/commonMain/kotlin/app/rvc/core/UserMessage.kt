package app.rvc.core

/** Тексты для пользователя; исходная ошибка не должна раскрывать URL подписки или токены. */
object UserMessage {
    fun readable(raw: String?): String {
        val value = raw.orEmpty()
        val text = value.lowercase()
        val status = Regex("(?:http|status)[^0-9]{0,12}([45][0-9]{2})", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            status == 401 || status == 403 -> "Сервер отказал в доступе. Проверьте срок подписки и лимит устройств в личном кабинете."
            status == 404 -> "Ссылка больше не доступна. Скопируйте актуальную ссылку у провайдера."
            status == 429 -> "Слишком много запросов. Подождите немного и повторите попытку."
            status != null && status >= 500 -> "Сервер временно недоступен. Попробуйте позже."
            listOf("timeout", "timed out", "вовремя").any { it in text } -> "Сервер не ответил вовремя. Проверьте интернет и повторите попытку."
            listOf("certificate", "tls", "ssl").any { it in text } -> "Не удалось проверить защищённое соединение. Проверьте дату и время на устройстве. Если они верны, обратитесь к провайдеру."
            listOf("dns", "resolve", "name or service").any { it in text } -> "Не удалось найти сервер. Проверьте интернет и адрес подписки."
            listOf("connection refused", "network is unreachable", "не удалось подключиться к серверу").any { it in text } -> "Нет соединения с сервером. Проверьте интернет или выберите другую локацию."
            listOf("no space", "disk full", "недостаточно места").any { it in text } -> "Недостаточно свободного места. Освободите память и повторите попытку."
            listOf("permission denied", "access is denied", "access denied", "доступ запрещен", "отказано в доступе").any { it in text } -> "Недостаточно прав для выполнения действия. Проверьте доступ к папке приложения; режим VPN в Windows требует прав администратора."
            listOf("контрольн", "checksum", "sha256", "подписан чужим", "подписи apk").any { it in text } -> "Проверка безопасности файла не пройдена. Установка отменена. Повторите загрузку из официального релиза RVC."
            "exception" in text || "stacktrace" in text || !Regex("[А-Яа-яЁё]").containsMatchIn(value) ->
                "Не удалось выполнить действие. Повторите попытку. Если ошибка сохраняется, обратитесь в поддержку."
            else -> value.replace(Regex("(?i)(?:https?|vless|vmess|trojan|ss)://[^\\s]+"), "[ссылка скрыта]")
                .replace(Regex("(?i)(token|password|passwd|authorization|secret)\\s*[:=]\\s*[^\\s,;]+"), "$1=[скрыто]")
                .lineSequence().firstOrNull().orEmpty().take(400)
        }
    }
}
