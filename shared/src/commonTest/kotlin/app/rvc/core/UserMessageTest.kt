package app.rvc.core

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class UserMessageTest {
    @Test fun translatesErrorsAndRemovesSecrets() {
        assertTrue(UserMessage.readable("HTTP 403").contains("отказал"))
        assertTrue(UserMessage.readable("connection timed out").contains("вовремя"))
        assertTrue(UserMessage.readable("certificate verify failed").contains("дату"))
        assertFalse(UserMessage.readable("Ошибка https://sub.test/private-token").contains("private-token"))
        assertEquals("Подписки обновлены", UserMessage.readable("Подписки обновлены"))
    }
}
