package io.github.dacstage.dac

import kotlin.math.log10

/** Rango de un control de volumen en unidades del dispositivo (int16 con signo; UAC: 1/256 dB). */
data class RawVolumeRange(val min: Int, val max: Int, val res: Int)

/**
 * Conversión crudo ↔ dB de un control de volumen. Solo existe cuando la escala es de fiar
 * (ver [VolumeSanity]); si no, el volumen se expone sin dB ([DacVolumeMode.STEPS]).
 */
sealed class VolumeScale {
    abstract val raw: RawVolumeRange

    /** dB de un valor crudo leído (GET_CUR); −∞ = silencio; null si no se puede convertir con certeza. */
    abstract fun toDb(rawValue: Int): Float?

    /**
     * Valor crudo a escribir para [db]: nunca más alto que lo pedido (salvo el redondeo a 1/256 dB de la
     * escala UAC estándar). null si [db] no es representable.
     */
    abstract fun toRaw(db: Float): Int?

    abstract val minDb: Float
    abstract val maxDb: Float

    /** Paso uniforme en dB; null si los niveles no son uniformes (ver [levelsDb]). */
    abstract val stepDb: Float?

    /** Niveles discretos (dB finitos, ascendentes) cuando el paso no es uniforme o es > 1 dB; si no, null. */
    open val levelsDb: List<Float>? get() = null

    /** Misma escala con otra resolución cruda (tras comprobar que la anunciada no es real). */
    abstract fun withRes(res: Int): VolumeScale

    /** Escala UAC estándar: valor = dB × 256 (int16), 0x8000 = −∞. */
    data class Db256(override val raw: RawVolumeRange) : VolumeScale() {
        override fun toDb(rawValue: Int): Float = DbScaling.rawToDb(rawValue.toShort())
        override fun toRaw(db: Float): Int? =
            if (db.isNaN() || db == Float.POSITIVE_INFINITY) null else DbScaling.dbToRaw(db).toInt()
        override val minDb: Float get() = raw.min / 256f
        override val maxDb: Float get() = raw.max / 256f
        override val stepDb: Float get() = raw.res / 256f
        override val levelsDb: List<Float>?
            get() {
                if (raw.res <= 256) return null
                val n = (raw.max - raw.min) / raw.res
                if (n > MAX_LEVELS) return null
                return (0..n).map { (raw.min + it * raw.res) / 256f }
            }
        override fun withRes(res: Int) = copy(raw = raw.copy(res = res))
    }

    /** Escala con niveles discretos y conversión monótona propia (tabla o lineal). */
    abstract class Discrete(final override val raw: RawVolumeRange) : VolumeScale() {
        /** Monótona no decreciente en [rawValue]. */
        protected abstract fun dbOf(rawValue: Int): Float

        private val values: IntArray by lazy {
            val res = raw.res.coerceAtLeast(1)
            val count = (raw.max - raw.min) / res + 1
            val stride = ((count + MAX_LEVELS - 1) / MAX_LEVELS).coerceAtLeast(1)
            val list = ArrayList<Int>()
            var v = raw.min
            while (v <= raw.max) { list += v; v += res * stride }
            if (list.last() != raw.max) list += raw.max
            list.toIntArray()
        }

        override fun toDb(rawValue: Int): Float? = if (rawValue in raw.min..raw.max) dbOf(rawValue) else null

        override fun toRaw(db: Float): Int? {
            if (db.isNaN()) return null
            var lo = 0
            var hi = values.size - 1
            var best = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (dbOf(values[mid]) <= db + TOLERANCE_DB) { best = mid; lo = mid + 1 } else hi = mid - 1
            }
            return if (best < 0) null else values[best]
        }

        override val levelsDb: List<Float> by lazy { values.map { dbOf(it) }.filter { it.isFinite() }.distinct() }
        override val minDb: Float get() = levelsDb.first()
        override val maxDb: Float get() = levelsDb.last()
        override val stepDb: Float? get() = null
    }

    /**
     * Tabla por tramos (dB interpolados linealmente dentro de cada tramo), en centésimas de dB como los
     * TLV de ALSA. [segments]: (crudoDesde, crudoHasta, dBx100Desde, dBx100Hasta).
     */
    class Table(raw: RawVolumeRange, private val segments: List<IntArray>) : Discrete(raw) {
        override fun dbOf(rawValue: Int): Float {
            val s = segments.firstOrNull { rawValue in it[0]..it[1] } ?: return Float.NaN
            val span = s[1] - s[0]
            val cdb = if (span == 0) s[2].toFloat() else s[2] + (s[3] - s[2]) * (rawValue - s[0]).toFloat() / span
            return cdb / 100f
        }
        override fun toDb(rawValue: Int): Float? = super.toDb(rawValue)?.takeIf { !it.isNaN() }
        override fun withRes(res: Int): VolumeScale = this

        companion object {
            /**
             * AudioQuest DragonFly, variante 0..50: aproximación medida que usa Linux
             * (sound/usb/mixer_quirks.c, snd_dragonfly_quirk_db_scale): 0 → −53 dB … 50 → 0 dB.
             */
            fun dragonFly(): Table = Table(
                RawVolumeRange(0, 50, 1),
                listOf(
                    intArrayOf(0, 1, -5300, -4970),
                    intArrayOf(2, 5, -4710, -4160),
                    intArrayOf(6, 7, -3884, -3710),
                    intArrayOf(8, 14, -3443, -2560),
                    intArrayOf(15, 16, -2475, -2324),
                    intArrayOf(17, 19, -2228, -2031),
                    intArrayOf(20, 26, -1910, -1393),
                    intArrayOf(27, 31, -1322, -1032),
                    intArrayOf(32, 40, -968, -490),
                    intArrayOf(41, 50, -441, 0),
                ),
            )
        }
    }

    /**
     * Volumen lineal en amplitud (no dB): min = silencio, max = 0 dB, dB = 20·log10((v−min)/(max−min)).
     * Es la conversión de ALSA (TLV_DB_LINEAR) para JBL Pebbles, Hotone y MV-SILICON 0/4096/1.
     */
    class Linear(raw: RawVolumeRange) : Discrete(raw) {
        override fun dbOf(rawValue: Int): Float {
            if (rawValue <= raw.min) return Float.NEGATIVE_INFINITY
            val ratio = (rawValue - raw.min).toDouble() / (raw.max - raw.min)
            return (20.0 * log10(ratio)).toFloat()
        }
        override fun toRaw(db: Float): Int? =
            if (db == Float.NEGATIVE_INFINITY) raw.min else super.toRaw(db)
        override fun withRes(res: Int): VolumeScale = Linear(raw.copy(res = res))
    }

    companion object {
        /** Más niveles no aportan nada a la UI (pasos de ±1 dB) y solo cuestan memoria. */
        const val MAX_LEVELS = 8192
        private const val TOLERANCE_DB = 1e-3f
    }
}

/**
 * Reglas de cordura del rango de volumen (de Linux sound/usb/mixer.c + quirks). Nunca inventa dB:
 * si la escala no es de fiar, el modo es [DacVolumeMode.STEPS] (sin dB) o [DacVolumeMode.UNAVAILABLE].
 */
object VolumeSanity {
    /** Por debajo: no es una escala dB plausible (Linux ignora 0..≤1000 en DragonFly; < 256 ya avisa). */
    const val TINY_SPAN_RAW = 1024

    /** max ≤ −96 dB: Linux lo considera dB falso. */
    const val BOGUS_MAX_RAW = -96 * 256

    data class Decision(
        val mode: DacVolumeMode,
        val scale: VolumeScale?,
        /** Rango crudo efectivo (tras quirks); null si no se pudo leer. */
        val raw: RawVolumeRange?,
        val notes: List<String> = emptyList(),
    )

    fun decide(reported: RawVolumeRange?, quirks: DacQuirks): Decision {
        if (reported == null) {
            return Decision(DacVolumeMode.UNAVAILABLE, null, null, listOf("el DAC no devolvió el rango de volumen"))
        }
        val notes = ArrayList<String>()
        var min = reported.min
        var max = reported.max
        var res = reported.res
        if (res == 0) { res = 1; notes += "res 0 → 1" }
        if (res < 0) { res = -res; notes += "res negativa" }
        quirks.minRawOverride?.let { o ->
            if (o > min) { min = o; notes += "mínimo efectivo ${o / 256f} dB (quirk)" }
        }
        if (quirks.essShiftedUnits) {
            min = min shr 8; max = 0; res = 1
            notes += "unidades no estándar (quirk ESS)"
        }
        val effective = RawVolumeRange(min, max, res)

        if (quirks.dragonFlyTable) {
            if (min == 0 && max == 50) {
                return Decision(DacVolumeMode.HARDWARE_DB, VolumeScale.Table.dragonFly(), effective, notes + "tabla DragonFly")
            }
            if (min == 0 && max <= 1000) {
                return Decision(DacVolumeMode.STEPS, null, effective, notes + "rango DragonFly sin escala dB")
            }
        }
        val linear = quirks.linearVolume ||
            (quirks.linearVolumeIf4096 && min == 0 && max == 4096 && res == 1)
        if (linear) {
            return if (min < max) Decision(DacVolumeMode.HARDWARE_DB, VolumeScale.Linear(effective), effective, notes + "volumen lineal")
            else Decision(DacVolumeMode.STEPS, null, effective, notes + "rango lineal inválido")
        }

        // Escala UAC estándar (1/256 dB).
        var dmin = min
        var dmax = max
        if (dmin > dmax) {
            // Como Linux: se asume que uno de los extremos es 0 dB.
            if (dmin < 0) dmax = 0 else if (dmin > 0) dmin = 0
            if (dmin > dmax) return Decision(DacVolumeMode.STEPS, null, effective, notes + "rango incoherente")
            notes += "min > max corregido"
        }
        if (dmin > 0 && dmax > 0) return Decision(DacVolumeMode.STEPS, null, effective, notes + "ambos extremos positivos")
        if (dmax <= BOGUS_MAX_RAW) return Decision(DacVolumeMode.STEPS, null, effective, notes + "máximo ≤ −96 dB")
        if (dmax - dmin < TINY_SPAN_RAW) return Decision(DacVolumeMode.STEPS, null, effective, notes + "rango demasiado pequeño para ser dB")
        val r = RawVolumeRange(dmin, dmax, res)
        return Decision(DacVolumeMode.HARDWARE_DB, VolumeScale.Db256(r), r, notes)
    }
}
