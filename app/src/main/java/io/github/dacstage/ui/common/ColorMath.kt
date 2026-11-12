package io.github.dacstage.ui.common

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Contraste WCAG sobre colores ARGB empaquetados en Int. Puro (sin android.graphics) para poder
 * probarlo en la JVM.
 */
object ColorMath {
    const val AA_TEXT = 4.5
    const val AA_UI = 3.0

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF

    private fun channel(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    /** Luminancia relativa (0 = negro, 1 = blanco). Ignora alfa. */
    fun luminance(c: Int): Double = 0.2126 * channel(red(c)) + 0.7152 * channel(green(c)) + 0.0722 * channel(blue(c))

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** Mezcla en espacio sRGB: t = 0 → a, t = 1 → b. Resultado opaco. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun m(x: Int, y: Int) = (x + (y - x) * k).roundToInt()
        return argb(255, m(red(a), red(b)), m(green(a), green(b)), m(blue(a), blue(b)))
    }

    /** Compone [fg] (con su alfa) sobre [bg] opaco. */
    fun over(fg: Int, bg: Int): Int = mix(bg, fg or (0xFF shl 24), alpha(fg) / 255f)

    /**
     * Oscurece [bg] (manteniendo el tono) hasta que [fg] contraste al menos [minRatio]. Para texto claro
     * siempre converge: el negro da el máximo contraste.
     */
    fun darkenForContrast(bg: Int, fg: Int, minRatio: Double = AA_TEXT): Int {
        var c = bg or (0xFF shl 24)
        val effFg = { over(fg, c) }
        var i = 0
        while (contrast(effFg(), c) < minRatio && i < 64) {
            c = mix(c, 0xFF000000.toInt(), 0.08f)
            i++
        }
        return c
    }

    /** Aclara [color] hacia blanco hasta contrastar [minRatio] con [bg] (para acentos sobre fondo oscuro). */
    fun lightenForContrast(color: Int, bg: Int, minRatio: Double = AA_UI): Int {
        var c = color or (0xFF shl 24)
        var i = 0
        while (contrast(c, bg) < minRatio && i < 64) {
            c = mix(c, 0xFFFFFFFF.toInt(), 0.08f)
            i++
        }
        return c
    }

    /** Limita la luminancia a [maxLuminance] oscureciendo (para fondos "profundos"). */
    fun darkenToLuminance(color: Int, maxLuminance: Double): Int {
        var c = color or (0xFF shl 24)
        var i = 0
        while (luminance(c) > maxLuminance && i < 64) {
            c = mix(c, 0xFF000000.toInt(), 0.08f)
            i++
        }
        return c
    }
}
