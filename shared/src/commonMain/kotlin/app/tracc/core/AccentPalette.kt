package app.tracc.core

/** Пары оттенков: насыщенный для светлой темы, светлый для тёмной. */
object AccentPalette {
    val names = listOf("Фиолетовый", "Синий", "Бирюзовый", "Зелёный", "Янтарный", "Розовый")
    val light = listOf(0xFF6D28D9, 0xFF1D4ED8, 0xFF0E7490, 0xFF047857, 0xFF92400E, 0xFFBE123C)
    val dark = listOf(0xFFC4B5FD, 0xFF93C5FD, 0xFF67E8F9, 0xFF6EE7B7, 0xFFFCD34D, 0xFFFDA4AF)
    fun index(value: Int) = value.takeIf { it in names.indices } ?: 0
}
