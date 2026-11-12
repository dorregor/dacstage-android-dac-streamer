package io.github.dacstage.dac

/**
 * Arreglos por dispositivo para el control USB Audio. Fuente: tablas de Linux (sound/usb/quirks.c,
 * mixer.c volume_control_quirks, mixer_quirks.c). Solo IDs conocidos: los DAC sin volumen por USB
 * (Chord, Cayin, Shanling…) se detectan de forma genérica (sin Feature Unit o volumen "pegado").
 */
data class DacQuirks(
    /** Mínimo efectivo en crudo (1/256 dB): por debajo el DAC silencia. Solo sube el mínimo, nunca lo baja. */
    val minRawOverride: Int? = null,
    /** ESS "Asus USB DAC": min >>= 8, max = 0, res = 1 (unidades no estándar → sin dB). */
    val essShiftedUnits: Boolean = false,
    /** AudioQuest DragonFly: 0..50 con tabla dB de Linux; otras variantes estrechas sin dB. */
    val dragonFlyTable: Boolean = false,
    /** Volumen lineal en amplitud (JBL Pebbles, Hotone). */
    val linearVolume: Boolean = false,
    /** Lineal solo si el rango es 0/4096/1 (MV-SILICON, detectado por el fabricante). */
    val linearVolumeIf4096: Boolean = false,
    /** No leer la frecuencia de muestreo. */
    val skipSampleRate: Boolean = false,
    /** No enviar ninguna petición al Clock Selector (ni siquiera GET). */
    val skipClockSelector: Boolean = false,
    /** Algunas peticiones fallan (EPIPE) sin que el DAC se haya ido: reintentar una vez y seguir. */
    val ignoreControlErrors: Boolean = false,
    /** Espaciado mínimo entre transferencias de control (ms). */
    val controlSpacingMs: Long = 0,
    /** Sin volumen USB útil (volumen fijo / en el propio DAC): no se toca la Feature Unit de volumen. */
    val fixedVolume: Boolean = false,
    /** Volumen verificado en hardware real: no hace falta la prueba de volumen "pegado". */
    val trustedVolume: Boolean = false,
    /** Nombres de las entradas aplicadas (diagnóstico). */
    val applied: List<String> = emptyList(),
) {
    operator fun plus(o: DacQuirks) = DacQuirks(
        minRawOverride = o.minRawOverride ?: minRawOverride,
        essShiftedUnits = essShiftedUnits || o.essShiftedUnits,
        dragonFlyTable = dragonFlyTable || o.dragonFlyTable,
        linearVolume = linearVolume || o.linearVolume,
        linearVolumeIf4096 = linearVolumeIf4096 || o.linearVolumeIf4096,
        skipSampleRate = skipSampleRate || o.skipSampleRate,
        skipClockSelector = skipClockSelector || o.skipClockSelector,
        ignoreControlErrors = ignoreControlErrors || o.ignoreControlErrors,
        controlSpacingMs = maxOf(controlSpacingMs, o.controlSpacingMs),
        fixedVolume = fixedVolume || o.fixedVolume,
        trustedVolume = trustedVolume || o.trustedVolume,
        applied = applied + o.applied,
    )

    companion object {
        val NONE = DacQuirks()
    }
}

/** Tabla de quirks por VID:PID (pid null = todo el fabricante) y por nombre de fabricante. */
object DacQuirkTable {

    class Entry(val vendorId: Int, val productId: Int?, val label: String, val quirks: DacQuirks)

    private fun e(vid: Int, pid: Int?, label: String, q: DacQuirks) = Entry(vid, pid, label, q.copy(applied = listOf(label)))

    val entries: List<Entry> = listOf(
        e(0x152A, 0x85DD, "SMSL USB DAC (verificado)", DacQuirks(trustedVolume = true)),
        e(0x3302, 0x12DB, "MOONDROP Quark2", DacQuirks(minRawOverride = -14208)),
        e(0x31B2, 0x0111, "MOONDROP JU Jiu", DacQuirks(minRawOverride = -10880)),
        e(0x12D1, 0x3A07, "Huawei CM-Q3", DacQuirks(minRawOverride = -11264)),
        e(0x0D8C, 0x0103, "C-Media CM102", DacQuirks(minRawOverride = -256)),
        e(0x0495, 0x3042, "ESS Asus USB DAC", DacQuirks(essShiftedUnits = true)),
        e(0x21B4, 0x0081, "AudioQuest DragonFly", DacQuirks(dragonFlyTable = true, skipSampleRate = true)),
        e(0x30BE, 0x0101, "Schiit Hel", DacQuirks(ignoreControlErrors = true)),
        e(0x04E8, 0xA051, "Samsung/AKG USB-C", DacQuirks(skipClockSelector = true, controlSpacingMs = 5)),
        e(0x2FC6, null, "Comture (iBasso/Moondrop)", DacQuirks(controlSpacingMs = 2)),
        e(0x2FC6, 0xF06B, "MOONDROP Moonriver2 Ti", DacQuirks(controlSpacingMs = 20)),
        e(0x2A39, 0x3FD2, "RME ADI-2", DacQuirks(fixedVolume = true)),
        e(0x2A39, 0x3FD3, "RME ADI-2", DacQuirks(fixedVolume = true)),
        e(0x2A39, 0x3FD4, "RME ADI-2", DacQuirks(fixedVolume = true)),
        e(0x05FC, 0x0231, "JBL Pebbles", DacQuirks(linearVolume = true, skipSampleRate = true)),
        e(0x84EF, 0x0082, "Hotone Pulze Mini", DacQuirks(linearVolume = true)),
    )

    private const val MV_SILICON = "MV-SILICON"

    fun forDevice(vendorId: Int, productId: Int, manufacturer: String? = null): DacQuirks {
        var q = DacQuirks.NONE
        entries.filter { it.vendorId == vendorId && it.productId == null }.forEach { q += it.quirks }
        entries.filter { it.vendorId == vendorId && it.productId == productId }.forEach { q += it.quirks }
        if (manufacturer?.trim()?.startsWith(MV_SILICON, ignoreCase = true) == true) {
            q += DacQuirks(linearVolumeIf4096 = true, applied = listOf("MV-SILICON (lineal si 0/4096/1)"))
        }
        return q
    }
}
