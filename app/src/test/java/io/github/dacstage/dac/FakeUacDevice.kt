package io.github.dacstage.dac

/** Reloj de prueba: sleep avanza el tiempo. */
class FakeClock(var now: Long = 0L) : DacClock {
    val sleeps = mutableListOf<Long>()
    override fun nowMs() = now
    override fun sleepMs(ms: Long) {
        sleeps += ms
        now += ms
    }
}

/**
 * Dispositivo USB Audio simulado a nivel de transferencias de control (Feature Unit [fu], Clock Selector
 * [selectorId], Clock Sources de [sampleRates]). Registra cada petición con su instante.
 */
class FakeUacDevice(
    val fu: Int = 10,
    val uac1: Boolean = false,
    var range: RawVolumeRange? = RawVolumeRange(-127 * 256, 0, 256),
    /** GET_RANGE / GET_MIN… fallan estas veces antes de responder. */
    var rangeFailures: Int = 0,
    val volumeChannels: Set<Int> = setOf(0),
    val muteChannels: Set<Int> = setOf(0),
    initialRaw: Int = -30 * 256,
    /** Acepta SET_CUR pero no cambia nada (volumen "pegado"). */
    var sticky: Boolean = false,
    /** Recorta los valores escritos al múltiplo inferior de [alignTo] (contado desde el mínimo). */
    var alignTo: Int = 1,
    val clock: FakeClock = FakeClock(),
    val selectorId: Int = 40,
    var selectorPin: Int = 1,
    val sampleRates: Map<Int, Long> = mapOf(41 to 96_000L),
    /** Las próximas N transferencias fallan (EPIPE). */
    var failNext: Int = 0,
) : UacControlTransport {

    data class Req(val set: Boolean, val request: Int, val cs: Int, val ch: Int, val entity: Int, val value: Int?, val atMs: Long)

    val log = ArrayList<Req>()
    val cur = HashMap<Int, Int>().apply { volumeChannels.forEach { put(it, initialRaw) } }
    val mute = HashMap<Int, Boolean>().apply { muteChannels.forEach { put(it, false) } }

    /** Valores de volumen escritos (todos los canales, en orden). */
    val volumeSets: List<Int> get() = log.filter { it.set && it.entity == fu && it.cs == Uac2Requests.FU_VOLUME }.map { it.value!! }
    val sets: List<Req> get() = log.filter { it.set }

    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int): Int {
        val cs = value shr 8
        val ch = value and 0xFF
        val entity = index shr 8
        val isSet = requestType == Uac2Requests.REQ_TYPE_SET
        val written = if (isSet && length >= 2) DbScaling.decodeLe(buffer).toInt()
        else if (isSet && length == 1) buffer[0].toInt() else null
        log += Req(isSet, request, cs, ch, entity, written, clock.now)
        if (failNext > 0) { failNext--; return -1 }
        return when (entity) {
            fu -> featureUnit(isSet, request, cs, ch, buffer, written)
            selectorId -> if (!isSet && cs == Uac2Requests.CX_CLOCK_SELECTOR) { buffer[0] = selectorPin.toByte(); 1 } else 1
            in sampleRates.keys -> if (!isSet && cs == Uac2Requests.CS_SAM_FREQ) {
                val hz = sampleRates.getValue(entity)
                for (k in 0 until 4) buffer[k] = ((hz shr (8 * k)) and 0xFF).toByte()
                4
            } else -1
            else -> -1
        }
    }

    private fun featureUnit(isSet: Boolean, request: Int, cs: Int, ch: Int, buf: ByteArray, written: Int?): Int {
        if (cs == Uac2Requests.FU_MUTE) {
            if (ch !in muteChannels) return -1
            if (isSet) { mute[ch] = written != 0; return 1 }
            buf[0] = if (mute.getValue(ch)) 1 else 0
            return 1
        }
        if (cs != Uac2Requests.FU_VOLUME || ch !in volumeChannels) return -1
        val r = range
        if (isSet) {
            if (!sticky && r != null) {
                val v = written!!
                cur[ch] = r.min + Math.floorDiv(v - r.min, alignTo) * alignTo
            }
            return 2
        }
        fun put(v: Int, at: Int = 0) { DbScaling.encodeLe(v.toShort()).copyInto(buf, at) }
        return when {
            !uac1 && request == Uac2Requests.RANGE -> {
                if (rangeFailures > 0) { rangeFailures--; return -1 }
                r ?: return -1
                put(1); put(r.min, 2); put(r.max, 4); put(r.res, 6)
                8
            }
            uac1 && request in Uac2Requests.UAC1_GET_MIN..Uac2Requests.UAC1_GET_RES -> {
                if (rangeFailures > 0) { rangeFailures--; return -1 }
                r ?: return -1
                put(when (request) { Uac2Requests.UAC1_GET_MIN -> r.min; Uac2Requests.UAC1_GET_MAX -> r.max; else -> r.res })
                2
            }
            request == (if (uac1) Uac2Requests.UAC1_GET_CUR else Uac2Requests.CUR) -> { put(cur.getValue(ch)); 2 }
            else -> -1
        }
    }
}

fun topology(
    volumeChannels: List<Int> = listOf(0),
    muteChannels: List<Int> = listOf(0),
    uacVersion: Int = 2,
    featureUnitId: Int? = 10,
    clockEntityId: Int? = 41,
    clockSources: Set<Int> = setOf(41),
    clockSelectors: Map<Int, List<Int>> = emptyMap(),
    volumeWritable: Boolean = volumeChannels.isNotEmpty(),
) = Uac2Topology(
    audioControlInterface = 0,
    featureUnitId = featureUnitId,
    featureUnitChannels = 2,
    hasMasterMute = 0 in muteChannels,
    inputTerminalId = 2,
    outputTerminalId = 20,
    clockEntityId = clockEntityId,
    clockSources = clockSources,
    clockSelectors = clockSelectors,
    clockMultipliers = emptyMap(),
    uacVersion = uacVersion,
    volumeChannels = if (featureUnitId == null) emptyList() else volumeChannels,
    volumeWritable = volumeWritable,
    muteChannels = if (featureUnitId == null) emptyList() else muteChannels,
)
