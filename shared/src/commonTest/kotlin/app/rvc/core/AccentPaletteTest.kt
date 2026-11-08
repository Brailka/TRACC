package app.rvc.core

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class AccentPaletteTest {
    private fun luminance(rgb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((rgb shr shift) and 255).toDouble() / 255
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
    @Test fun allButtonLabelsHaveAccessibleContrast() {
        AccentPalette.light.forEach { assertTrue(1.05 / (luminance(it) + 0.05) >= 4.5) }
        AccentPalette.dark.forEach { assertTrue((luminance(it) + 0.05) / (luminance(0xFF07070B) + 0.05) >= 4.5) }
        assertEquals(0, AccentPalette.index(-1))
        assertEquals(0, AccentPalette.index(100))
    }
}
