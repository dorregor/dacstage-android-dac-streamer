package io.github.dacstage.dac

/**
 * Lo que necesitamos del DAC, sacado de sus descriptores (sin ids fijos en código).
 * [clockEntityId] es el reloj del camino elegido (puede ser un selector o multiplicador).
 *
 * Volumen y mute pueden estar en el canal master (0) o solo en los canales lógicos 1..N
 * ([volumeChannels] / [muteChannels]): en ese caso se manejan todos a la vez y el rango se lee del
 * primero (como hace Linux). [featureUnitId] es null si el camino no tiene Feature Unit.
 */
data class Uac2Topology(
    val audioControlInterface: Int,
    val featureUnitId: Int?,
    /** Canales lógicos de la Feature Unit (sin contar el master). */
    val featureUnitChannels: Int,
    val hasMasterMute: Boolean,
    val inputTerminalId: Int?,
    val outputTerminalId: Int?,
    val clockEntityId: Int?,
    val clockSources: Set<Int>,
    val clockSelectors: Map<Int, List<Int>>,
    val clockMultipliers: Map<Int, Int>,
    /** 2 = UAC2 (protocolo 0x20), 1 = UAC1 (protocolo 0x00). */
    val uacVersion: Int = 2,
    /** Canales con control de volumen que se manejan juntos: [0] = master; vacío = sin volumen. */
    val volumeChannels: List<Int> = if (featureUnitId != null) listOf(Uac2Requests.MASTER_CHANNEL) else emptyList(),
    /** false si el descriptor declara el volumen como solo lectura (UAC2 bits 0b01). */
    val volumeWritable: Boolean = volumeChannels.isNotEmpty(),
    val muteChannels: List<Int> = if (hasMasterMute) listOf(Uac2Requests.MASTER_CHANNEL) else emptyList(),
    val muteWritable: Boolean = muteChannels.isNotEmpty(),
) {
    val hasVolume: Boolean get() = featureUnitId != null && volumeChannels.isNotEmpty()
    val hasWritableMute: Boolean get() = featureUnitId != null && muteChannels.isNotEmpty() && muteWritable
}

/** Parser puro de descriptores USB Audio (UsbDeviceConnection.rawDescriptors): UAC2 y UAC1. */
object Uac2DescriptorParser {
    const val USB_CLASS_AUDIO = 0x01
    const val SUBCLASS_AUDIOCONTROL = 0x01
    const val PROTOCOL_UAC2 = 0x20
    const val PROTOCOL_UAC1 = 0x00

    private const val DT_CONFIGURATION = 0x02
    private const val DT_INTERFACE = 0x04
    private const val DT_CS_INTERFACE = 0x24

    private const val AC_HEADER = 0x01
    private const val AC_INPUT_TERMINAL = 0x02
    private const val AC_OUTPUT_TERMINAL = 0x03
    private const val AC_MIXER_UNIT = 0x04
    private const val AC_SELECTOR_UNIT = 0x05
    private const val AC_FEATURE_UNIT = 0x06

    // UAC2
    private const val AC2_EFFECT_UNIT = 0x07
    private const val AC2_PROCESSING_UNIT = 0x08
    private const val AC2_EXTENSION_UNIT = 0x09
    private const val AC2_CLOCK_SOURCE = 0x0A
    private const val AC2_CLOCK_SELECTOR = 0x0B
    private const val AC2_CLOCK_MULTIPLIER = 0x0C

    // UAC1
    private const val AC1_PROCESSING_UNIT = 0x07
    private const val AC1_EXTENSION_UNIT = 0x08

    /** Terminales 0x01xx = lado USB (streaming). */
    private fun isUsbTerminal(type: Int) = (type and 0xFF00) == 0x0100
    const val TERMINAL_USB_STREAMING = 0x0101

    private const val MAX_DEPTH = 32
    private const val MAX_PATHS = 64

    private class InputTerminal(val id: Int, val type: Int, val clockId: Int)
    private class OutputTerminal(val id: Int, val type: Int, val sourceId: Int, val clockId: Int)

    /** [controls][0] = master, [controls][n] = canal lógico n. */
    private class FeatureUnit(val id: Int, val sourceId: Int, val controls: List<Long>, val version: Int) {
        val channels get() = (controls.size - 1).coerceAtLeast(0)

        /** Bits UAC2 (00 no, 01 solo lectura, 11 programable). UAC1 solo dice presente → 11. */
        private fun bits(ch: Int, uac2Shift: Int, uac1Bit: Int): Int {
            val c = controls.getOrNull(ch) ?: return 0
            return if (version == 2) ((c shr uac2Shift) and 0x3L).toInt()
            else if ((c shr uac1Bit) and 0x1L != 0L) 0x3 else 0
        }

        fun muteBits(ch: Int) = bits(ch, uac2Shift = 0, uac1Bit = 0)
        fun volumeBits(ch: Int) = bits(ch, uac2Shift = 2, uac1Bit = 1)

        val masterMute get() = muteBits(0) != 0
        val masterVolume get() = volumeBits(0) != 0
        val anyVolume get() = (0..channels).any { volumeBits(it) != 0 }
        val anyMute get() = (0..channels).any { muteBits(it) != 0 }
    }

    private class Ac(val version: Int) {
        val inputs = LinkedHashMap<Int, InputTerminal>()
        val outputs = ArrayList<OutputTerminal>()
        val features = LinkedHashMap<Int, FeatureUnit>()
        /** Otras unidades: id → fuentes. */
        val units = HashMap<Int, List<Int>>()
        val clockSources = LinkedHashSet<Int>()
        val clockSelectors = LinkedHashMap<Int, List<Int>>()
        val clockMultipliers = LinkedHashMap<Int, Int>()
    }

    private class Candidate(val ot: OutputTerminal, val path: List<Int>)

    /**
     * Compatibilidad: solo UAC2 y solo si hay una Feature Unit con volumen en el canal master
     * (comportamiento original). La app usa [parseControl].
     */
    fun parse(raw: ByteArray?): Uac2Topology? {
        val (iface, ac) = scan(raw, acceptUac1 = false) ?: return null
        for (c in candidates(ac)) {
            val fu = c.path.firstNotNullOfOrNull { id -> ac.features[id]?.takeIf { it.masterVolume } } ?: continue
            return build(iface, ac, c, fu)
        }
        return null
    }

    /**
     * Modelo completo (UAC2 o UAC1). Devuelve null solo si no hay interfaz AudioControl.
     * Elige el primer camino de reproducción (terminal de salida no USB primero; caminos que nacen en
     * USB streaming primero) con Feature Unit de volumen (master preferido; si no, por canal); si
     * ninguno tiene volumen, uno con mute; si tampoco, el camino sin Feature Unit (volumen en el DAC).
     */
    fun parseControl(raw: ByteArray?): Uac2Topology? {
        val (iface, ac) = scan(raw, acceptUac1 = true) ?: return null
        val cands = candidates(ac)
        for (c in cands) {
            val fus = c.path.mapNotNull { ac.features[it] }
            val fu = fus.firstOrNull { it.masterVolume } ?: fus.firstOrNull { it.anyVolume } ?: continue
            return build(iface, ac, c, fu)
        }
        for (c in cands) {
            val fu = c.path.firstNotNullOfOrNull { id -> ac.features[id]?.takeIf { it.anyMute } } ?: continue
            return build(iface, ac, c, fu)
        }
        return build(iface, ac, cands.firstOrNull(), null)
    }

    private fun scan(raw: ByteArray?, acceptUac1: Boolean): Pair<Int, Ac>? {
        if (raw == null) return null
        var acInterface: Int? = null
        var ac: Ac? = null
        var inAc = false
        var configs = 0
        var i = 0
        scan@ while (i + 2 <= raw.size) {
            val len = u8(raw, i)
            val type = u8(raw, i + 1)
            if (len < 2 || i + len > raw.size) break@scan
            when (type) {
                // Solo la primera configuración (evita duplicados).
                DT_CONFIGURATION -> if (++configs > 1) break@scan
                DT_INTERFACE -> if (len >= 9) {
                    val number = u8(raw, i + 2)
                    val proto = u8(raw, i + 7)
                    val isAc = u8(raw, i + 5) == USB_CLASS_AUDIO &&
                        u8(raw, i + 6) == SUBCLASS_AUDIOCONTROL &&
                        (proto == PROTOCOL_UAC2 || (acceptUac1 && proto == PROTOCOL_UAC1))
                    inAc = isAc && (acInterface == null || acInterface == number)
                    if (inAc && acInterface == null) {
                        acInterface = number
                        ac = Ac(if (proto == PROTOCOL_UAC2) 2 else 1)
                    }
                }
                // Los CS_INTERFACE de AudioStreaming (u otras interfaces) se ignoran.
                DT_CS_INTERFACE -> if (inAc && len >= 3) ac?.let { parseAc(raw, i, len, it) }
            }
            i += len
        }
        val iface = acInterface ?: return null
        return iface to (ac ?: return null)
    }

    private fun candidates(ac: Ac): List<Candidate> =
        ac.outputs.sortedBy { if (isUsbTerminal(it.type)) 1 else 0 }.flatMap { ot ->
            upstreamPaths(ot.sourceId, ac)
                .sortedBy { p -> if (ac.inputs[p.last()]?.type == TERMINAL_USB_STREAMING) 0 else 1 }
                .map { Candidate(ot, it) }
        }

    private fun build(iface: Int, ac: Ac, c: Candidate?, fu: FeatureUnit?): Uac2Topology {
        val input = c?.let { ac.inputs[it.path.last()] }
        val clock = input?.clockId?.takeIf { id -> id != 0 } ?: c?.ot?.clockId?.takeIf { id -> id != 0 }
        val volCh = if (fu == null) emptyList() else channelsFor(fu) { ch -> fu.volumeBits(ch) }
        val muteCh = if (fu == null) emptyList() else channelsFor(fu) { ch -> fu.muteBits(ch) }
        return Uac2Topology(
            audioControlInterface = iface,
            featureUnitId = fu?.id,
            featureUnitChannels = fu?.channels ?: 0,
            hasMasterMute = fu?.masterMute == true,
            inputTerminalId = input?.id,
            outputTerminalId = c?.ot?.id,
            clockEntityId = clock,
            clockSources = ac.clockSources.toSet(),
            clockSelectors = ac.clockSelectors.toMap(),
            clockMultipliers = ac.clockMultipliers.toMap(),
            uacVersion = ac.version,
            volumeChannels = volCh,
            volumeWritable = fu != null && volCh.isNotEmpty() && volCh.all { ch -> fu.volumeBits(ch) and 0x2 != 0 },
            muteChannels = muteCh,
            muteWritable = fu != null && muteCh.isNotEmpty() && muteCh.all { ch -> fu.muteBits(ch) and 0x2 != 0 },
        )
    }

    /** Master si lo tiene; si no, todos los canales lógicos que lo tengan (Linux hace lo mismo). */
    private fun channelsFor(fu: FeatureUnit, bits: (Int) -> Int): List<Int> =
        if (bits(0) != 0) listOf(Uac2Requests.MASTER_CHANNEL)
        else (1..fu.channels).filter { bits(it) != 0 }

    private const val SUBCLASS_AUDIOSTREAMING = 0x02
    private const val AS_GENERAL = 0x01
    private const val AS_FORMAT_TYPE = 0x02
    private const val FORMAT_TYPE_I = 0x01

    /**
     * Formatos PCM (UAC2 Type I) de las interfaces AudioStreaming: AS_GENERAL da los canales
     * (bNrChannels, offset 10) y FORMAT_TYPE da bSubslotSize (4) y bBitResolution (5).
     * Solo lee los descriptores: no reclama ni consulta la interfaz de streaming.
     */
    fun parseStreamFormats(raw: ByteArray?): List<UsbStreamFormat> {
        if (raw == null) return emptyList()
        val out = ArrayList<UsbStreamFormat>()
        var configs = 0
        var iface = -1
        var alt = -1
        var inAs = false
        var channels = 0
        var i = 0
        scan@ while (i + 2 <= raw.size) {
            val len = u8(raw, i)
            val type = u8(raw, i + 1)
            if (len < 2 || i + len > raw.size) break@scan
            when (type) {
                DT_CONFIGURATION -> if (++configs > 1) break@scan
                DT_INTERFACE -> if (len >= 9) {
                    iface = u8(raw, i + 2)
                    alt = u8(raw, i + 3)
                    inAs = u8(raw, i + 5) == USB_CLASS_AUDIO &&
                        u8(raw, i + 6) == SUBCLASS_AUDIOSTREAMING &&
                        u8(raw, i + 7) == PROTOCOL_UAC2
                    channels = 0
                }
                DT_CS_INTERFACE -> if (inAs && len >= 3) {
                    when (u8(raw, i + 2)) {
                        AS_GENERAL -> if (len >= 11) channels = u8(raw, i + 10)
                        AS_FORMAT_TYPE -> if (len >= 6 && u8(raw, i + 3) == FORMAT_TYPE_I && channels > 0) {
                            val subslot = u8(raw, i + 4)
                            val bits = u8(raw, i + 5)
                            if (subslot in 1..4 && bits in 1..32) {
                                out += UsbStreamFormat(iface, alt, channels, subslot, bits)
                            }
                        }
                    }
                }
            }
            i += len
        }
        return out
    }

    private fun parseAc(b: ByteArray, o: Int, len: Int, ac: Ac) {
        fun list(countAt: Int): List<Int>? {
            if (countAt >= len) return null
            val n = u8(b, o + countAt)
            if (countAt + 1 + n > len) return null
            return (0 until n).map { u8(b, o + countAt + 1 + it) }
        }
        val subtype = u8(b, o + 2)
        if (ac.version == 1) {
            when (subtype) {
                AC_INPUT_TERMINAL -> if (len >= 12) {
                    val id = u8(b, o + 3)
                    ac.inputs[id] = InputTerminal(id, u16(b, o + 4), 0)
                }
                AC_OUTPUT_TERMINAL -> if (len >= 9) {
                    ac.outputs += OutputTerminal(u8(b, o + 3), u16(b, o + 4), u8(b, o + 7), 0)
                }
                // bLength, type, subtype, bUnitID, bSourceID, bControlSize, bmaControls[n], iFeature
                AC_FEATURE_UNIT -> if (len >= 7) {
                    val id = u8(b, o + 3)
                    val size = u8(b, o + 5)
                    if (size in 1..4) {
                        val n = (len - 7) / size
                        val controls = (0 until n).map { k -> uN(b, o + 6 + k * size, size) }
                        ac.features[id] = FeatureUnit(id, u8(b, o + 4), controls, 1)
                    }
                }
                AC_MIXER_UNIT, AC_SELECTOR_UNIT -> list(4)?.let { ac.units[u8(b, o + 3)] = it }
                AC1_PROCESSING_UNIT, AC1_EXTENSION_UNIT -> list(6)?.let { ac.units[u8(b, o + 3)] = it }
            }
            return
        }
        when (subtype) {
            AC_HEADER -> Unit
            AC_INPUT_TERMINAL -> if (len >= 17) {
                val id = u8(b, o + 3)
                ac.inputs[id] = InputTerminal(id, u16(b, o + 4), u8(b, o + 7))
            }
            AC_OUTPUT_TERMINAL -> if (len >= 12) {
                ac.outputs += OutputTerminal(u8(b, o + 3), u16(b, o + 4), u8(b, o + 7), u8(b, o + 8))
            }
            AC_FEATURE_UNIT -> if (len >= 10) {
                val id = u8(b, o + 3)
                val n = (len - 6) / 4
                val controls = (0 until n).map { k -> u32(b, o + 5 + 4 * k) }
                ac.features[id] = FeatureUnit(id, u8(b, o + 4), controls, 2)
            }
            AC_MIXER_UNIT, AC_SELECTOR_UNIT -> list(4)?.let { ac.units[u8(b, o + 3)] = it }
            AC2_EFFECT_UNIT -> if (len >= 7) ac.units[u8(b, o + 3)] = listOf(u8(b, o + 6))
            AC2_PROCESSING_UNIT, AC2_EXTENSION_UNIT -> list(6)?.let { ac.units[u8(b, o + 3)] = it }
            AC2_CLOCK_SOURCE -> if (len >= 8) ac.clockSources += u8(b, o + 3)
            AC2_CLOCK_SELECTOR -> list(4)?.let { ac.clockSelectors[u8(b, o + 3)] = it }
            AC2_CLOCK_MULTIPLIER -> if (len >= 7) ac.clockMultipliers[u8(b, o + 3)] = u8(b, o + 4)
        }
    }

    /** Caminos aguas arriba desde [start] (cada uno termina en un Input Terminal o en un callejón). */
    private fun upstreamPaths(start: Int, ac: Ac): List<List<Int>> {
        val result = ArrayList<List<Int>>()
        fun walk(id: Int, path: List<Int>) {
            if (result.size >= MAX_PATHS) return
            if (id in path || path.size >= MAX_DEPTH) { result += path; return }
            val next = path + id
            val sources = when {
                ac.inputs.containsKey(id) -> null
                ac.features.containsKey(id) -> listOf(ac.features.getValue(id).sourceId)
                else -> ac.units[id]
            }
            if (sources.isNullOrEmpty()) result += next else sources.forEach { walk(it, next) }
        }
        walk(start, emptyList())
        return result.filter { it.isNotEmpty() }
    }

    /**
     * Resuelve el Clock Source que alimenta el camino. [selectorPin] LEE (GET_CUR, nunca SET) el pin
     * activo (base 1) de un Clock Selector con varias entradas. Null si no se puede saber con certeza
     * (p. ej. hay multiplicador, o no se pudo leer el selector).
     */
    fun resolveClockSource(topology: Uac2Topology, selectorPin: (Int) -> Int?): Int? {
        var id = topology.clockEntityId ?: return topology.clockSources.singleOrNull()
        repeat(MAX_DEPTH) {
            when {
                id in topology.clockSources -> return id
                topology.clockMultipliers.containsKey(id) -> return null
                topology.clockSelectors.containsKey(id) -> {
                    val inputs = topology.clockSelectors.getValue(id)
                    id = if (inputs.size == 1) inputs[0] else {
                        val pin = selectorPin(id) ?: return null
                        inputs.getOrNull(pin - 1) ?: return null
                    }
                }
                else -> return null
            }
        }
        return null
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun u32(b: ByteArray, o: Int) = DbScaling.decodeU32Le(b, o)
    private fun uN(b: ByteArray, o: Int, n: Int): Long =
        (0 until n).fold(0L) { acc, k -> acc or ((b[o + k].toLong() and 0xFF) shl (8 * k)) }
}
