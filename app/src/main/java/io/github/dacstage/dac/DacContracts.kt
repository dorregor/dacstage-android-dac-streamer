package io.github.dacstage.dac

import io.github.dacstage.model.CommandOrigin
import io.github.dacstage.model.CommandResult
import io.github.dacstage.model.DacState
import io.github.dacstage.model.PlayerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val UNKNOWN_VOLUME_MODE: StateFlow<DacVolumeMode?> = MutableStateFlow<DacVolumeMode?>(null).asStateFlow()

/** Resultado de intentar hablar con la interfaz AudioControl del DAC. */
sealed interface DacAccess {
    data object Ok : DacAccess
    data object Disconnected : DacAccess
    data object NoPermission : DacAccess
    /** claimInterface(false) falló porque otro (HiBy/UAPP) usa la interfaz; hace falta forzar. */
    data object NeedsForce : DacAccess
    /**
     * La interfaz la tiene el driver de audio de Android (el reproductor suena por el audio del sistema):
     * forzar desmontaría la tarjeta USB (corte y desvío del sonido al altavoz). No se toca nunca; el volumen
     * se maneja con el volumen multimedia de Android.
     */
    data object InUseByAndroid : DacAccess
    data class Failed(val reason: String) : DacAccess
}

/**
 * Acceso de bajo nivel a un DAC USB Audio Class 2.0 (implementación: [UsbAudioDacController]).
 * Cada operación reclama la interfaz AudioControl, hace la transferencia de control y la libera.
 * [force] = claimInterface(true): SOLO lo decide [DacVolumeControl] según la política de seguridad.
 */
interface DacController {
    /** Busca el DAC, pide permiso USB si falta y parsea descriptores (Feature Unit, Clock Source, rango). */
    suspend fun connect(): DacAccess

    suspend fun getVolumeDb(force: Boolean = false): Result<Float>

    /** Escribe y relee (GET_CUR). Devuelve el valor CONFIRMADO por el DAC. */
    suspend fun setVolumeDb(db: Float, force: Boolean = false): Result<Float>

    suspend fun getMute(force: Boolean = false): Result<Boolean>

    /** Escribe y relee. Devuelve el estado CONFIRMADO. */
    suspend fun setMute(muted: Boolean, force: Boolean = false): Result<Boolean>

    /** Frecuencia real del Clock Source en Hz. */
    suspend fun readSampleRateHz(force: Boolean = false): Result<Int>

    /** Nombre, rango y paso del volumen; null si no hay DAC conectado y parseado. */
    val info: StateFlow<DacInfo?>

    fun close()
}

/**
 * Cómo se puede manejar el volumen del DAC desde la app. Solo [HARDWARE_DB] permite subir/bajar
 * (el tope del host se aplica en dB y no se puede garantizar sin una escala dB fiable).
 */
enum class DacVolumeMode {
    /** Volumen por USB con escala dB fiable (estándar 1/256 dB, tabla o lineal convertida). */
    HARDWARE_DB,
    /** Hay control de volumen pero sin escala dB fiable (rango diminuto, absurdo o unidades raras). */
    STEPS,
    /** El DAC declara volumen pero no se mueve (pegado) o es de solo lectura: se ajusta en el propio DAC. */
    FIXED_ON_DEVICE,
    /** El DAC no tiene control de volumen USB (sin Feature Unit de volumen): se ajusta en el propio DAC. */
    NONE,
    /** Declara volumen pero no informó su rango (GET_RANGE falló dos veces); se reintenta al refrescar. */
    UNAVAILABLE,
}

data class DacInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    /** null salvo en [DacVolumeMode.HARDWARE_DB]: nunca se inventan dB. */
    val minDb: Float?,
    val maxDb: Float?,
    /** Paso uniforme en dB; null si no hay escala dB o los niveles no son uniformes ([levelsDb]). */
    val stepDb: Float?,
    val hasMute: Boolean,
    /** Formatos de streaming que declara el DAC (de sus descriptores; no requiere reclamar nada). */
    val streamFormats: List<UsbStreamFormat> = emptyList(),
    val volumeMode: DacVolumeMode = DacVolumeMode.HARDWARE_DB,
    /** Niveles dB discretos (ascendentes) cuando el paso no es uniforme o es > 1 dB; null = minDb + k·stepDb. */
    val levelsDb: List<Float>? = null,
    /** Rango crudo del control de volumen (diagnóstico; en STEPS es lo único que se sabe). */
    val rawVolumeRange: RawVolumeRange? = null,
    /** Quirks y decisiones aplicadas (diagnóstico / pantalla de detalles). */
    val notes: List<String> = emptyList(),
)

/** Un formato PCM de la interfaz AudioStreaming (UAC2 Type I): alternativa, canales y bits. */
data class UsbStreamFormat(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val channels: Int,
    /** bSubslotSize: bytes por muestra en el bus. */
    val subslotBytes: Int,
    /** bBitResolution: bits útiles por muestra. */
    val bitResolution: Int,
) {
    val frameBytes: Int get() = channels * subslotBytes

    companion object {
        /**
         * Bits útiles del formato con ese tamaño de trama. null si ninguno o varios con bits distintos
         * coinciden (no se adivina).
         */
        fun bitsForFrame(formats: List<UsbStreamFormat>, frameBytes: Int?): Int? {
            if (frameBytes == null || frameBytes <= 0) return null
            val bits = formats.filter { it.frameBytes == frameBytes && it.bitResolution > 0 }
                .map { it.bitResolution }.distinct()
            return bits.singleOrNull()
        }
    }
}

/**
 * Política de volumen seguro (se aplica en el host para TODOS los orígenes):
 * - tope configurable (por defecto maxDb - 10 dB), nunca se escribe un valor por encima;
 * - pasos de 1 dB; repetición acelera de forma gradual con tope de velocidad;
 * - mute siempre disponible;
 * - claimInterface(true) con música sonando solo con consentimiento del host por par DAC+reproductor
 *   (guardado y revocable); si no hay consentimiento: error VOLUME_UNAVAILABLE para la TV y el host
 *   ofrece "pausar y aplicar".
 */
interface DacVolumeControl {
    /** Estado publicado (incluye capDb y disponibilidad). Volumen = siempre el leído del DAC. */
    val state: StateFlow<DacState>

    /** Petición pendiente de confirmación en el host (para mostrar el diálogo / "pausar y aplicar"). */
    val pendingConfirmation: StateFlow<PendingDacConfirmation?>

    /**
     * Modo de volumen del DAC conectado; null = aún desconocido (sin DAC o sin leer). Si no es
     * [DacVolumeMode.HARDWARE_DB], la UI debe decir que el volumen se controla en el DAC en vez de error.
     */
    val volumeMode: StateFlow<DacVolumeMode?> get() = UNKNOWN_VOLUME_MODE

    suspend fun refresh(): CommandResult
    suspend fun step(deltaDb: Int, repeatCount: Int, origin: CommandOrigin): CommandResult
    suspend fun toggleMute(origin: CommandOrigin): CommandResult

    /**
     * Fija el mute sin pedir nunca confirmación. Para el silencio interno durante un salto en la cola.
     * Sin [playerPaused] nunca toma la interfaz si la tiene otro; con [playerPaused] (el que llama comprobó
     * que el reproductor está en pausa) sí, igual que la política normal cuando no suena nada.
     * true si el DAC confirmó el valor pedido.
     */
    suspend fun trySetMute(muted: Boolean, playerPaused: Boolean = false): Boolean = false

    /** Solo UI del host (slider). Se rechaza por encima del tope. */
    suspend fun setVolumeDb(db: Float): CommandResult

    /** Solo UI del host. La TV no tiene forma de llamarlo. */
    fun setCapDb(capDb: Float)

    /** Solo UI del host: consentimiento persistente para este DAC + reproductor. */
    fun grantForceConsent(player: PlayerId)
    fun revokeForceConsent(player: PlayerId)

    /** Solo UI del host: pausa el reproductor, aplica la petición pendiente y (si sonaba) reanuda. */
    suspend fun pauseAndApplyPending(): CommandResult
    fun dismissPending()
}

data class PendingDacConfirmation(
    val player: PlayerId?,
    val description: String,
    val origin: CommandOrigin,
    /** Nombre visible de la app ("Spotify"). */
    val playerName: String? = null,
)
