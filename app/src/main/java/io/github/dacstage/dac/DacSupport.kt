package io.github.dacstage.dac

import kotlinx.coroutines.flow.StateFlow

/** Error tipado de [DacController]: viaja dentro de Result.failure y se traduce a [DacAccess]. */
open class DacAccessException(val access: DacAccess, message: String? = null) :
    Exception(message ?: access.toString())

/** Ni claimInterface(false) ni claimInterface(true) funcionaron. */
class DacClaimException : DacAccessException(DacAccess.Failed("No se pudo reclamar la interfaz AudioControl"))

/** El DAC no tiene ese control (p. ej. sin mute). */
open class DacUnsupportedException(message: String) : Exception(message)

/** El volumen no se puede manejar desde la app en este [mode] (se ajusta en el DAC). */
class DacVolumeNotControllableException(val mode: DacVolumeMode) :
    DacUnsupportedException(volumeModeMessage(mode))

/** Texto (es) para cuando el volumen no se maneja desde la app. */
fun volumeModeMessage(mode: DacVolumeMode): String = when (mode) {
    DacVolumeMode.HARDWARE_DB -> "Volumen disponible"
    DacVolumeMode.STEPS -> "Este DAC no informa una escala de volumen fiable: ajústalo en el DAC"
    DacVolumeMode.FIXED_ON_DEVICE -> "El volumen se controla en el DAC"
    DacVolumeMode.NONE -> "El DAC no tiene control de volumen por USB: se controla en el DAC"
    DacVolumeMode.UNAVAILABLE -> "El DAC no informó su rango de volumen"
}

fun Throwable.toDacAccess(): DacAccess =
    (this as? DacAccessException)?.access ?: DacAccess.Failed(message ?: javaClass.simpleName)

/** DAC presente en el bus (aunque aún no se haya podido leer su rango). */
data class DacDeviceRef(val name: String, val vendorId: Int, val productId: Int) {
    /** Clave de consentimiento "VID:PID" en hex, p. ej. "152A:85DD". */
    val key: String get() = dacKey(vendorId, productId)
}

fun dacKey(vendorId: Int, productId: Int): String = "%04X:%04X".format(vendorId, productId)

/**
 * Extensión opcional de [DacController]: presencia física del DAC (attach/detach). Permite publicar
 * "conectado" y la clave del consentimiento antes de poder reclamar la interfaz para leer el rango.
 */
interface DacDevicePresence {
    val attachedDevice: StateFlow<DacDeviceRef?>
}
