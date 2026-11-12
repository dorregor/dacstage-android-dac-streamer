package io.github.dacstage.dac

import kotlin.math.roundToInt

/** Rango de volumen (una subrange de GET_RANGE) en dB. */
data class DbRange(val minDb: Float, val maxDb: Float, val resDb: Float)

/**
 * Escalado de volumen UAC2: valor = dB × 256 en int16 little-endian (1/256 dB por unidad).
 * 0x8000 en CUR significa −∞ dB (silencio).
 */
object DbScaling {
    const val SILENCE_RAW: Short = Short.MIN_VALUE // 0x8000

    /** Bytes de una respuesta GET_RANGE con [n] subrangos (wNumSubRanges + n × MIN,MAX,RES). */
    fun rangeLength(n: Int): Int = 2 + 6 * n

    fun dbToRaw(db: Float): Short {
        if (db == Float.NEGATIVE_INFINITY) return SILENCE_RAW
        require(db.isFinite()) { "dB no finito: $db" }
        return (db * 256f).roundToInt().coerceIn(-32767, 32767).toShort()
    }

    fun rawToDb(raw: Short): Float = if (raw == SILENCE_RAW) Float.NEGATIVE_INFINITY else raw / 256f

    fun encodeLe(raw: Short): ByteArray {
        val v = raw.toInt()
        return byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    }

    fun decodeLe(bytes: ByteArray, offset: Int = 0): Short =
        ((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort()

    fun decodeU16Le(bytes: ByteArray, offset: Int = 0): Int = decodeLe(bytes, offset).toInt() and 0xFFFF

    fun decodeU32Le(bytes: ByteArray, offset: Int = 0): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i)) }

    /** wNumSubRanges de una respuesta GET_RANGE; null si es demasiado corta. */
    fun subRangeCount(bytes: ByteArray, length: Int): Int? =
        if (length < 2 || bytes.size < 2) null else decodeU16Le(bytes, 0)

    /**
     * Parsea GET_RANGE de volumen (int16 por campo). En RANGE el valor 0x8000 se toma literal (−128 dB),
     * no como silencio. Devuelve null si la respuesta está incompleta o es incoherente.
     */
    fun parseVolumeRange(bytes: ByteArray, length: Int): List<DbRange>? {
        val n = subRangeCount(bytes, length) ?: return null
        val len = minOf(length, bytes.size)
        if (n == 0 || rangeLength(n) > len) return null
        val ranges = (0 until n).map { k ->
            val o = 2 + 6 * k
            DbRange(
                minDb = decodeLe(bytes, o) / 256f,
                maxDb = decodeLe(bytes, o + 2) / 256f,
                resDb = decodeLe(bytes, o + 4) / 256f,
            )
        }
        return ranges.takeIf { r -> r.all { it.minDb <= it.maxDb && it.resDb > 0f } }
    }

    /**
     * Primer subrango de un GET_RANGE de volumen, en unidades crudas (int16 con signo), SIN validar
     * (las reglas de cordura están en [VolumeSanity]). Como Linux, se ignoran los demás subrangos.
     * null si la respuesta es corta o declara 0 subrangos.
     */
    fun parseFirstSubrangeRaw(bytes: ByteArray, length: Int): RawVolumeRange? {
        val n = subRangeCount(bytes, length) ?: return null
        if (n == 0 || minOf(length, bytes.size) < rangeLength(1)) return null
        return RawVolumeRange(
            min = decodeLe(bytes, 2).toInt(),
            max = decodeLe(bytes, 4).toInt(),
            res = decodeLe(bytes, 6).toInt(),
        )
    }

    /** Une subrangos: mínimo y máximo globales; paso = el del primer subrango. */
    fun aggregate(ranges: List<DbRange>): DbRange? {
        if (ranges.isEmpty()) return null
        return DbRange(ranges.minOf { it.minDb }, ranges.maxOf { it.maxDb }, ranges.first().resDb)
    }
}

/** Constantes y campos de las peticiones de control UAC2 (class-specific, destinatario interfaz). */
object Uac2Requests {
    const val REQ_TYPE_GET = 0xA1 // IN | class | interface
    const val REQ_TYPE_SET = 0x21 // OUT | class | interface
    const val CUR = 0x01
    const val RANGE = 0x02

    /** Feature Unit. */
    const val FU_MUTE = 0x01
    const val FU_VOLUME = 0x02

    /** Clock Source: frecuencia (4 bytes). Clock Selector: pin activo (1 byte, base 1). */
    const val CS_SAM_FREQ = 0x01
    const val CX_CLOCK_SELECTOR = 0x01

    const val MASTER_CHANNEL = 0

    /** UAC1: peticiones separadas (bRequest); SET_CUR = 0x01 igual que UAC2 CUR. */
    const val UAC1_SET_CUR = 0x01
    const val UAC1_GET_CUR = 0x81
    const val UAC1_GET_MIN = 0x82
    const val UAC1_GET_MAX = 0x83
    const val UAC1_GET_RES = 0x84

    fun wValue(controlSelector: Int, channel: Int = MASTER_CHANNEL): Int =
        ((controlSelector and 0xFF) shl 8) or (channel and 0xFF)

    fun wIndex(entityId: Int, interfaceNumber: Int): Int =
        ((entityId and 0xFF) shl 8) or (interfaceNumber and 0xFF)
}
