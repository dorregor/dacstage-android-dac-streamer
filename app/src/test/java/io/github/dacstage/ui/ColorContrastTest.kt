package io.github.dacstage.ui

import io.github.dacstage.ui.common.ArtColors
import io.github.dacstage.ui.common.ArtSwatches
import io.github.dacstage.ui.common.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorContrastTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test fun extremes() {
        assertEquals(21.0, ColorMath.contrast(white, black), 0.01)
        assertEquals(1.0, ColorMath.contrast(0xFF777777.toInt(), 0xFF777777.toInt()), 1e-9)
    }

    @Test fun knownPair() {
        // Gris 767676 sobre blanco = 4.54 (umbral AA clásico).
        assertEquals(4.54, ColorMath.contrast(0xFF767676.toInt(), white), 0.01)
    }

    @Test fun darkenReachesAa() {
        val bg = ColorMath.darkenForContrast(0xFFFFE082.toInt(), white)
        assertTrue(ColorMath.contrast(white, bg) >= ColorMath.AA_TEXT)
    }

    @Test fun darkEnoughIsUntouched() {
        val bg = 0xFF101820.toInt()
        assertEquals(bg, ColorMath.darkenForContrast(bg, white))
    }

    @Test fun lightenAccentForUi() {
        val bg = 0xFF101010.toInt()
        val accent = ColorMath.lightenForContrast(0xFF202060.toInt(), bg)
        assertTrue(ColorMath.contrast(accent, bg) >= ColorMath.AA_UI)
    }

    @Test fun derivedPaletteAlwaysAa() {
        val cases = listOf(
            ArtSwatches(),
            ArtSwatches(vibrant = 0xFFFFEB3B.toInt(), dominant = 0xFFFFFFFF.toInt()),
            ArtSwatches(darkVibrant = 0xFFF8BBD0.toInt(), darkMuted = 0xFFE0E0E0.toInt(), muted = 0xFFBDBDBD.toInt()),
            ArtSwatches(dominant = 0xFF000000.toInt()),
            ArtSwatches(vibrant = 0xFF00E5FF.toInt(), darkVibrant = 0xFF006064.toInt(), darkMuted = 0xFF263238.toInt()),
        )
        for (s in cases) {
            val c = ArtColors.derive(s)
            for (bg in listOf(c.glow, c.deep, ColorMath.mix(c.glow, c.deep, 0.5f))) {
                assertTrue("primario $s", ColorMath.contrast(ArtColors.TEXT_PRIMARY, bg) >= ColorMath.AA_TEXT)
                assertTrue("secundario $s", ColorMath.contrast(ArtColors.TEXT_SECONDARY, bg) >= ColorMath.AA_TEXT)
            }
            assertTrue("acento/glow $s", ColorMath.contrast(c.accent, c.glow) >= ColorMath.AA_UI)
            assertTrue("acento/deep $s", ColorMath.contrast(c.accent, c.deep) >= ColorMath.AA_UI)
        }
    }
}
