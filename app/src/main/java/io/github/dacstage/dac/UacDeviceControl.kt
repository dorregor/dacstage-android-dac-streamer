package io.github.dacstage.dac

import io.github.dacstage.dac.Uac2Requests.CUR
import io.github.dacstage.dac.Uac2Requests.RANGE
import io.github.dacstage.dac.Uac2Requests.REQ_TYPE_GET
import io.github.dacstage.dac.Uac2Requests.REQ_TYPE_SET

/**
 * Transferencia de control USB (en Android: UsbDeviceConnection.controlTransfer sobre la interfaz ya
 * reclamada). Devuelve los bytes transferidos o < 0 si falla.
 */
fun interface UacControlTransport {
    fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int): Int
}

/** Reloj inyectable (para el espaciado entre peticiones y los reintentos). */
interface DacClock {
    fun nowMs(): Long
    fun sleepMs(ms: Long)

    companion object {
        val SYSTEM: DacClock = object : DacClock {
            override fun nowMs(): Long = System.nanoTime() / 1_000_000
            override fun sleepMs(ms: Long) { if (ms > 0) Thread.sleep(ms) }
        }
    }
}

/** Espaciado mínimo entre transferencias de control de un dispositivo (0 = sin espera). */
class ControlPacer(val minSpacingMs: Long, private val clock: DacClock) {
    private var lastEndMs: Long? = null

    fun <T> paced(block: () -> T): T {
        val last = lastEndMs
        if (minSpacingMs > 0 && last != null) {
            val wait = last + minSpacingMs - clock.nowMs()
            if (wait > 0) clock.sleepMs(wait)
        }
        try {
            return block()
        } finally {
            lastEndMs = clock.nowMs()
        }
    }
}

/** Resultado de comprobar si el volumen de la Feature Unit se mueve de verdad. */
enum class VolumeProbeResult {
    NOT_RUN,
    /** No se pudo o no hacía falta comprobar (en el mínimo, silencio, verificado, error): se confía. */
    SKIPPED,
    MOVES,
    /** La lectura nunca cambió: volumen fijo / controlado en el DAC. */
    STICKY,
}

/**
 * Lógica de control de un DAC USB Audio conectado (UAC2 o UAC1), sin Android: rango con reintento,
 * reglas de cordura, quirks, canales, prueba de volumen "pegado", mute y frecuencia (solo lectura).
 * Cada llamada recibe el transporte de la operación en curso (interfaz ya reclamada según la política
 * de [DacVolumeManager]); esta clase nunca reclama ni fuerza nada, ni toca el streaming.
 */
class UacDeviceControl(
    val topology: Uac2Topology,
    val quirks: DacQuirks = DacQuirks.NONE,
    private val clock: DacClock = DacClock.SYSTEM,
) {
    data class VolumeModel(
        val mode: DacVolumeMode,
        val scale: VolumeScale?,
        val raw: RawVolumeRange?,
        val notes: List<String>,
    )

    private val pacer = ControlPacer(quirks.controlSpacingMs, clock)
    private val uac1: Boolean get() = topology.uacVersion == 1

    /** Modelo de volumen decidido (una vez por conexión del dispositivo). */
    var model: VolumeModel? = null
        private set

    var probeResult: VolumeProbeResult = VolumeProbeResult.NOT_RUN
        private set

    val hasMute: Boolean get() = topology.hasWritableMute

    /**
     * Decide el modelo de volumen si falta. Con [retryUnavailable] vuelve a intentarlo si quedó
     * [DacVolumeMode.UNAVAILABLE] (GET_RANGE falló). No lanza: los fallos se reflejan en el modo.
     */
    fun ensureVolumeModel(t: UacControlTransport, retryUnavailable: Boolean = false): VolumeModel {
        model?.let { if (!(retryUnavailable && it.mode == DacVolumeMode.UNAVAILABLE)) return it }
        return decide(t).also { model = it }
    }

    private fun decide(t: UacControlTransport): VolumeModel {
        val notes = ArrayList(quirks.applied)
        if (!topology.hasVolume) {
            return VolumeModel(DacVolumeMode.NONE, null, null, notes + "sin control de volumen USB")
        }
        if (quirks.fixedVolume) {
            return VolumeModel(DacVolumeMode.FIXED_ON_DEVICE, null, null, notes + "volumen fijo (quirk)")
        }
        if (!topology.volumeWritable) {
            return VolumeModel(DacVolumeMode.FIXED_ON_DEVICE, null, null, notes + "volumen de solo lectura")
        }
        val d = VolumeSanity.decide(readRangeWithRetry(t), quirks)
        notes += d.notes
        val scale = d.scale
        if (d.mode != DacVolumeMode.HARDWARE_DB || scale == null) return VolumeModel(d.mode, null, d.raw, notes)
        if (quirks.trustedVolume) {
            probeResult = VolumeProbeResult.SKIPPED
            return VolumeModel(DacVolumeMode.HARDWARE_DB, scale, d.raw, notes)
        }
        val (result, realRes) = probeVolume(t, scale)
        probeResult = result
        return when {
            result == VolumeProbeResult.STICKY ->
                VolumeModel(DacVolumeMode.FIXED_ON_DEVICE, null, d.raw, notes + "el volumen no responde (pegado)")
            realRes != null && realRes != scale.raw.res -> {
                val s = scale.withRes(realRes)
                VolumeModel(DacVolumeMode.HARDWARE_DB, s, s.raw, notes + "resolución real $realRes")
            }
            else -> VolumeModel(DacVolumeMode.HARDWARE_DB, scale, d.raw, notes)
        }
    }

    // --- Volumen ---

    fun readVolumeDb(t: UacControlTransport): Float {
        val scale = controllableScale(t)
        val raw = getCurVolumeRaw(t, topology.volumeChannels.first())
        return scale.toDb(raw) ?: throw transferError("Valor de volumen fuera de escala ($raw)")
    }

    /** Escribe en todos los canales manejados y relee. Devuelve el valor CONFIRMADO. */
    fun setVolumeDb(t: UacControlTransport, db: Float): Float {
        if (db.isNaN() || db == Float.POSITIVE_INFINITY) throw IllegalArgumentException("dB inválido: $db")
        val scale = controllableScale(t)
        if (db.isFinite() && (db < scale.minDb - DB_TOLERANCE || db > scale.maxDb + DB_TOLERANCE)) {
            throw IllegalArgumentException("$db dB fuera del rango ${scale.minDb}…${scale.maxDb}")
        }
        val raw = scale.toRaw(db) ?: throw IllegalArgumentException("$db dB no representable")
        for (ch in topology.volumeChannels) setCurVolumeRaw(t, ch, raw)
        return readVolumeDb(t)
    }

    private fun controllableScale(t: UacControlTransport): VolumeScale {
        val m = ensureVolumeModel(t)
        if (m.mode != DacVolumeMode.HARDWARE_DB || m.scale == null) throw DacVolumeNotControllableException(m.mode)
        return m.scale
    }

    // --- Mute ---

    fun readMute(t: UacControlTransport): Boolean {
        if (!hasMute) throw DacUnsupportedException("El DAC no tiene mute")
        return get(t, curRequest(), Uac2Requests.FU_MUTE, topology.muteChannels.first(), featureUnit(), 1)[0].toInt() != 0
    }

    fun setMute(t: UacControlTransport, muted: Boolean): Boolean {
        if (!hasMute) throw DacUnsupportedException("El DAC no tiene mute")
        for (ch in topology.muteChannels) {
            set(t, Uac2Requests.FU_MUTE, ch, featureUnit(), byteArrayOf(if (muted) 1 else 0))
        }
        return readMute(t)
    }

    // --- Frecuencia (solo lectura) ---

    /**
     * Frecuencia del Clock Source activo, leída de forma pasiva: si hay Clock Selector con varias
     * entradas se LEE su pin (nunca se fija). UAC1: no se lee (está en el endpoint de streaming).
     */
    fun readSampleRateHz(t: UacControlTransport): Int {
        if (quirks.skipSampleRate) throw DacUnsupportedException("Este DAC no informa bien la frecuencia")
        if (uac1) throw DacUnsupportedException("UAC1: la frecuencia solo está en el endpoint de streaming")
        val clockId = Uac2DescriptorParser.resolveClockSource(topology) { selectorId ->
            if (quirks.skipClockSelector) null
            else get(t, CUR, Uac2Requests.CX_CLOCK_SELECTOR, Uac2Requests.MASTER_CHANNEL, selectorId, 1)[0].toInt() and 0xFF
        } ?: throw transferError("No se pudo determinar el Clock Source")
        val hz = DbScaling.decodeU32Le(get(t, CUR, Uac2Requests.CS_SAM_FREQ, Uac2Requests.MASTER_CHANNEL, clockId, 4))
        if (hz <= 0 || hz > Int.MAX_VALUE) throw transferError("Frecuencia inválida: $hz")
        return hz.toInt()
    }

    /** DacInfo publicable; null si aún no se decidió el modelo de volumen. */
    fun toInfo(deviceName: String, vendorId: Int, productId: Int, streamFormats: List<UsbStreamFormat>): DacInfo? {
        val m = model ?: return null
        val s = m.scale.takeIf { m.mode == DacVolumeMode.HARDWARE_DB }
        return DacInfo(
            deviceName = deviceName,
            vendorId = vendorId,
            productId = productId,
            minDb = s?.minDb,
            maxDb = s?.maxDb,
            stepDb = s?.stepDb,
            hasMute = hasMute,
            streamFormats = streamFormats,
            volumeMode = m.mode,
            levelsDb = s?.levelsDb,
            rawVolumeRange = m.raw,
            notes = m.notes,
        )
    }

    // --- Rango ---

    private fun readRangeWithRetry(t: UacControlTransport): RawVolumeRange? {
        repeat(RANGE_ATTEMPTS) { attempt ->
            if (attempt > 0) clock.sleepMs(RANGE_RETRY_DELAY_MS)
            val r = runCatching { readRangeOnce(t) }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    private fun readRangeOnce(t: UacControlTransport): RawVolumeRange? {
        val ch = topology.volumeChannels.first()
        val fu = featureUnit()
        if (uac1) {
            fun read(req: Int) = DbScaling.decodeLe(get(t, req, Uac2Requests.FU_VOLUME, ch, fu, 2)).toInt()
            val min = read(Uac2Requests.UAC1_GET_MIN)
            val max = read(Uac2Requests.UAC1_GET_MAX)
            // GET_RES es opcional en la práctica (Linux usa 1 si falla); 0 → 1 en VolumeSanity.
            val res = runCatching { read(Uac2Requests.UAC1_GET_RES) }.getOrDefault(0)
            return RawVolumeRange(min, max, res)
        }
        // Solo el primer subrango (como Linux): wLength = 2 + 3 × 2.
        val buf = ByteArray(DbScaling.rangeLength(1))
        val n = transfer(t, REQ_TYPE_GET, RANGE, Uac2Requests.FU_VOLUME, ch, fu, buf, "GET_RANGE")
        return DbScaling.parseFirstSubrangeRaw(buf, n)
    }

    // --- Prueba de volumen "pegado" + resolución real (siempre hacia ABAJO) ---

    /**
     * Lee CUR, baja un paso (res, 2·res, 4·res, 8·res: Linux check_volume_control_res pero hacia abajo),
     * relee y restaura el valor original. Si nada de eso movió la lectura, una última prueba al mínimo
     * (sigue siendo bajar). Nunca escribe por encima del valor original. Solo canal del rango.
     */
    private fun probeVolume(t: UacControlTransport, scale: VolumeScale): Pair<VolumeProbeResult, Int?> {
        val ch = topology.volumeChannels.first()
        val raw = scale.raw
        val saved = runCatching { getCurVolumeRaw(t, ch) }.getOrNull()
            ?: return VolumeProbeResult.SKIPPED to null
        if (saved == SILENCE_RAW || saved <= raw.min || saved > raw.max) return VolumeProbeResult.SKIPPED to null
        var res = raw.res.coerceAtLeast(1)
        var moved = false
        var wrote = false
        var confirmedRes: Int? = null
        var lastTest: Int? = null
        try {
            for (attempt in 0 until RES_PROBE_ATTEMPTS) {
                val test = saved - res
                if (test < raw.min) break
                wrote = true
                setCurVolumeRaw(t, ch, test)
                lastTest = test
                val check = getCurVolumeRaw(t, ch)
                if (check != saved) moved = true
                if (check == test) { confirmedRes = res; break }
                res *= 2
            }
            if (!moved && wrote && lastTest != raw.min) {
                setCurVolumeRaw(t, ch, raw.min)
                if (getCurVolumeRaw(t, ch) != saved) moved = true
            }
        } catch (e: Exception) {
            return VolumeProbeResult.SKIPPED to null
        } finally {
            if (wrote) restore(t, ch, saved)
        }
        return when {
            !wrote -> VolumeProbeResult.SKIPPED to null
            moved -> VolumeProbeResult.MOVES to confirmedRes
            else -> VolumeProbeResult.STICKY to null
        }
    }

    /** Vuelve al valor original (nunca más alto). Si falla, el volumen queda más bajo: lo seguro. */
    private fun restore(t: UacControlTransport, ch: Int, saved: Int) {
        repeat(2) {
            val ok = runCatching {
                setCurVolumeRaw(t, ch, saved)
                getCurVolumeRaw(t, ch) == saved
            }.getOrDefault(false)
            if (ok) return
        }
    }

    // --- Transferencias ---

    private fun featureUnit(): Int =
        topology.featureUnitId ?: throw DacVolumeNotControllableException(DacVolumeMode.NONE)

    private fun curRequest(): Int = if (uac1) Uac2Requests.UAC1_GET_CUR else CUR

    private fun getCurVolumeRaw(t: UacControlTransport, ch: Int): Int =
        DbScaling.decodeLe(get(t, curRequest(), Uac2Requests.FU_VOLUME, ch, featureUnit(), 2)).toInt()

    private fun setCurVolumeRaw(t: UacControlTransport, ch: Int, raw: Int) =
        set(t, Uac2Requests.FU_VOLUME, ch, featureUnit(), DbScaling.encodeLe(raw.toShort()))

    private fun get(t: UacControlTransport, request: Int, cs: Int, ch: Int, entity: Int, length: Int): ByteArray {
        val buf = ByteArray(length)
        val n = transfer(t, REQ_TYPE_GET, request, cs, ch, entity, buf, "GET")
        if (n < length) throw transferError("GET corto ($n/$length) entidad $entity")
        return buf
    }

    private fun set(t: UacControlTransport, cs: Int, ch: Int, entity: Int, data: ByteArray) {
        // SET_CUR: 0x01 tanto en UAC2 (CUR) como en UAC1 (SET_CUR).
        val n = transfer(t, REQ_TYPE_SET, CUR, cs, ch, entity, data, "SET_CUR")
        if (n < data.size) throw transferError("SET_CUR incompleto ($n/${data.size}) entidad $entity")
    }

    private fun transfer(
        t: UacControlTransport, reqType: Int, req: Int, cs: Int, ch: Int, entity: Int,
        buf: ByteArray, what: String,
    ): Int {
        val value = Uac2Requests.wValue(cs, ch)
        val index = Uac2Requests.wIndex(entity, topology.audioControlInterface)
        var n = pacer.paced { t.controlTransfer(reqType, req, value, index, buf, buf.size) }
        if (n < 0 && quirks.ignoreControlErrors) {
            clock.sleepMs(IGNORED_ERROR_RETRY_MS)
            n = pacer.paced { t.controlTransfer(reqType, req, value, index, buf, buf.size) }
        }
        if (n < 0) throw transferError("$what falló (entidad $entity, CS $cs, canal $ch)")
        return n
    }

    private fun transferError(msg: String) = DacAccessException(DacAccess.Failed(msg), msg)

    companion object {
        const val RANGE_ATTEMPTS = 2
        const val RANGE_RETRY_DELAY_MS = 50L
        const val RES_PROBE_ATTEMPTS = 4
        const val IGNORED_ERROR_RETRY_MS = 5L
        private const val DB_TOLERANCE = 1e-4f
        private const val SILENCE_RAW = -32768
    }
}
