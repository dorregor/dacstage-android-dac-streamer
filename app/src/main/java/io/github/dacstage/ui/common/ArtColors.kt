package io.github.dacstage.ui.common

import androidx.compose.runtime.Immutable

/** Muestras de androidx.palette ya reducidas a ARGB (null = la paleta no la encontró). */
data class ArtSwatches(
    val vibrant: Int? = null,
    val darkVibrant: Int? = null,
    val muted: Int? = null,
    val darkMuted: Int? = null,
    val dominant: Int? = null,
)

/**
 * Colores del fondo derivados de la carátula.
 * - [glow]: halo cerca de la carátula; [deep]: base del degradado. Ambos garantizan contraste AA (4.5:1)
 *   con [TEXT_PRIMARY] y [TEXT_SECONDARY]; cualquier mezcla entre ellos también (la luminancia de una
 *   mezcla sRGB no supera la del más claro).
 * - [accent]: progreso y pista actual; ≥ 3:1 contra ambos.
 */
@Immutable
data class ArtColors(val glow: Int, val deep: Int, val accent: Int) {
    companion object {
        const val TEXT_PRIMARY: Int = 0xFFFFFFFF.toInt()
        /** Gris claro opaco para texto secundario (más exigente que el blanco: rige el contraste). */
        const val TEXT_SECONDARY: Int = 0xFFD0D0D0.toInt()

        val Default = derive(ArtSwatches(dominant = 0xFF2A3346.toInt(), vibrant = 0xFF7FA7FF.toInt()))

        fun derive(s: ArtSwatches): ArtColors {
            val glowBase = s.darkVibrant ?: s.vibrant ?: s.dominant ?: s.muted ?: 0xFF2A3346.toInt()
            val deepBase = s.darkMuted ?: s.muted ?: s.dominant ?: glowBase
            // Fondo profundo: muy oscuro pero teñido.
            val deep = ColorMath.darkenForContrast(
                ColorMath.darkenToLuminance(ColorMath.mix(deepBase, 0xFF000000.toInt(), 0.35f), 0.02),
                TEXT_SECONDARY,
            )
            val glow = ColorMath.darkenForContrast(
                ColorMath.darkenForContrast(glowBase, TEXT_PRIMARY),
                TEXT_SECONDARY,
            )
            val accentBase = s.vibrant ?: s.darkVibrant ?: s.muted ?: s.dominant ?: 0xFF7FA7FF.toInt()
            val brighterBg = if (ColorMath.luminance(glow) > ColorMath.luminance(deep)) glow else deep
            val accent = ColorMath.lightenForContrast(accentBase, brighterBg, ColorMath.AA_UI)
            return ArtColors(glow = glow, deep = deep, accent = accent)
        }
    }
}
