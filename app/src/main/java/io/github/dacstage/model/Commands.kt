package io.github.dacstage.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Comandos que la TV (y la UI del host) envían al núcleo del host. Deliberadamente NO existe comando
 * para fijar el tope ni un volumen absoluto: desde la TV solo hay pasos de ±1 dB y mute. El volumen
 * absoluto (slider) es una API interna del host, no del protocolo.
 */
@Serializable
sealed interface Command {
    @Serializable @SerialName("play_pause") data object TogglePlayPause : Command
    @Serializable @SerialName("play") data object Play : Command
    @Serializable @SerialName("pause") data object Pause : Command
    @Serializable @SerialName("next") data object Next : Command
    @Serializable @SerialName("prev") data object Previous : Command
    @Serializable @SerialName("jump") data class JumpToQueueItem(val key: String) : Command
    @Serializable @SerialName("jump_cancel") data object CancelJump : Command

    /**
     * Paso de volumen. [deltaDb] debe ser +1 o -1 (el host rechaza otros valores). [repeatCount] es la
     * repetición de la tecla mantenida (0 = pulsación nueva); el host decide la aceleración con tope.
     */
    @Serializable @SerialName("vol_step") data class VolumeStep(val deltaDb: Int, val repeatCount: Int = 0) : Command
    @Serializable @SerialName("mute_toggle") data object ToggleMute : Command
    /** Pide releer el DAC y reenviar el estado completo. */
    @Serializable @SerialName("refresh") data object RefreshState : Command
}

@Serializable
enum class CommandError {
    NO_PLAYER,
    NOT_SUPPORTED,
    QUEUE_UNAVAILABLE,
    UNKNOWN_QUEUE_ITEM,
    JUMP_FAILED,
    JUMP_CANCELLED,
    /** "Volumen no disponible ahora": el DAC no se pudo reclamar sin confirmación en el host. */
    VOLUME_UNAVAILABLE,
    /** Rechazado por quedar por encima del tope (o fuera del rango del DAC). */
    VOLUME_ABOVE_CAP,
    DAC_DISCONNECTED,
    INVALID_ARGUMENT,
    RATE_LIMITED,
    INTERNAL,
}

@Serializable
data class CommandResult(val ok: Boolean, val error: CommandError? = null, val message: String? = null) {
    companion object {
        val OK = CommandResult(true)
        fun fail(error: CommandError, message: String? = null) = CommandResult(false, error, message)
    }
}

/** Quién originó un comando: la política de volumen se aplica igual a ambos orígenes. */
enum class CommandOrigin { TV, HOST_UI }
