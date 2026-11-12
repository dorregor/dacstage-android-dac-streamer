package io.github.dacstage.ui.common

import androidx.compose.runtime.Immutable
import io.github.dacstage.model.SpecSource
import io.github.dacstage.model.TechSpecs
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class ChipKind { CODEC, SAMPLE_RATE, BIT_DEPTH, BITRATE }

/** Chip técnico ya formateado. Solo existe si el dato existe (con su fuente). */
@Immutable
data class TechChip(val kind: ChipKind, val label: String, val source: SpecSource)

/** Formateo puro (sin Android) de datos técnicos, tiempos y volumen. */
object TechFormat {
    private const val MINUS = '−'

    /** 96000 → "96 kHz", 44100 → "44.1 kHz". Resolución de 0,1 kHz; punto decimal fijo. */
    fun sampleRate(hz: Int): String {
        val tenths = (hz + 50) / 100
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0) "$whole kHz" else "$whole.$frac kHz"
    }

    fun bitDepth(bits: Int): String = "$bits bit"

    fun bitrate(kbps: Int): String = "$kbps kbps"

    /**
     * Chips en orden códec, kHz, bits, kbps. La frecuencia del DAC (Clock Source) tiene preferencia sobre la
     * de la cola/sesión. Valores no válidos (≤ 0, vacíos) se tratan como "sin dato".
     */
    fun chips(specs: TechSpecs, dacSampleRateHz: Int?): List<TechChip> = buildList {
        specs.codec?.let { c ->
            val label = c.value.trim()
            if (label.isNotEmpty()) add(TechChip(ChipKind.CODEC, label, c.source))
        }
        val dacRate = dacSampleRateHz?.takeIf { it > 0 }
        if (dacRate != null) {
            add(TechChip(ChipKind.SAMPLE_RATE, sampleRate(dacRate), SpecSource.DAC_CLOCK))
        } else {
            specs.sampleRateHz?.takeIf { it.value > 0 }?.let {
                add(TechChip(ChipKind.SAMPLE_RATE, sampleRate(it.value), it.source))
            }
        }
        // Solo bits del ARCHIVO. Los bits del enlace USB (specs.usbBitDepth) no van aquí: el reproductor rellena
        // (p. ej. 16 → 32 bit) y se leerían como los de la canción. Se muestran en el panel del DAC del host.
        specs.bitDepth?.takeIf { it.value > 0 }?.let { add(TechChip(ChipKind.BIT_DEPTH, bitDepth(it.value), it.source)) }
        specs.bitrateKbps?.takeIf { it.value > 0 }?.let { add(TechChip(ChipKind.BITRATE, bitrate(it.value), it.source)) }
    }

    /** 83_000 → "1:23"; 3_723_000 → "1:02:03". */
    fun time(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0) / 1000)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** −23 → "−23 dB"; −23.5 → "−23.5 dB"; 0 → "0 dB". Signo menos tipográfico. */
    fun db(value: Float): String {
        val tenths = (value * 10f).roundToLong()
        if (tenths == 0L) return "0 dB"
        val sign = if (tenths < 0) "$MINUS" else ""
        val a = abs(tenths)
        val body = if (a % 10 == 0L) "${a / 10}" else "${a / 10}.${a % 10}"
        return "$sign$body dB"
    }

    fun percent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
}
