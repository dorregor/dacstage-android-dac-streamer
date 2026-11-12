package io.github.dacstage.players

import android.graphics.Bitmap
import io.github.dacstage.model.ArtSource
import io.github.dacstage.model.CommandResult
import io.github.dacstage.model.JumpProgress
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.PlayerId
import io.github.dacstage.model.Progress
import io.github.dacstage.model.QueueState
import io.github.dacstage.model.SystemVolume
import io.github.dacstage.model.CommandError
import io.github.dacstage.model.TechSpecs
import io.github.dacstage.model.TrackInfo
import kotlinx.coroutines.flow.StateFlow

/** Candidata de carátula publicada por el reproductor, en orden de preferencia (mejor primero). */
sealed interface ArtCandidate {
    val source: ArtSource

    /** [url] ya reescrita al mayor tamaño que tenga sentido probar; [fallbackUrls] en orden descendente. */
    data class Url(
        val url: String,
        override val source: ArtSource,
        val fallbackUrls: List<String> = emptyList(),
    ) : ArtCandidate

    data class Embedded(val bitmap: Bitmap, override val source: ArtSource) : ArtCandidate
}

/** Foto del reproductor activo (sin DAC). La compone cada adaptador a partir de su MediaSession/archivos. */
data class PlayerSnapshot(
    val player: PlayerId,
    val status: PlaybackStatus = PlaybackStatus.NONE,
    val track: TrackInfo? = null,
    val progress: Progress? = null,
    /**
     * SystemClock.elapsedRealtime() en el que [progress].positionMs era válida (aditivo). Permite al host
     * extrapolar la posición al enviar el estado sin depender de cuándo se compuso el snapshot.
     */
    val progressAtElapsedMs: Long? = null,
    /** Cambia solo cuando cambia la pista (para no re-resolver la carátula en cada tick). */
    val trackKey: String? = null,
    val artCandidates: List<ArtCandidate> = emptyList(),
    val specs: TechSpecs = TechSpecs(),
    val queue: QueueState = QueueState(),
    val jump: JumpProgress? = null,
    /** Bytes por trama USB que el reproductor preparó para el DAC (UAPP: de su log). El host lo cruza con
     *  los formatos del DAC para obtener los bits que viajan por USB. (Aditivo.) */
    val usbFrameBytes: Int? = null,
    /** Nombre visible de la app ("Spotify"). */
    val playerName: String? = null,
    /** Volumen multimedia de Android de la sesión (si suena en este dispositivo y es ajustable). */
    val systemVolume: SystemVolume? = null,
)

enum class TransportAction { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS }

/**
 * Adaptador de un reproductor sobre su MediaController: HiBy, UAPP o cualquier otra app ([SessionAdapter]).
 *
 * PROHIBIDO en cualquier implementación: MediaController.TransportControls.playFromSearch y
 * playFromMediaId (en UAPP vacían la cola del usuario). Una prueba unitaria escanea el código fuente.
 */
/**
 * Silencio del DAC durante un salto por pasos. Solo se llama con el reproductor en pausa (la política de
 * reclamo del DAC no fuerza nunca con música sonando sin confirmación).
 */
interface JumpSilencer {
    /**
     * Silencia si no lo estaba ya. true = lo silenció este salto (y hay que devolverlo).
     * [playerPaused]: el adaptador comprobó que el reproductor está en pausa en este momento, así que se
     * puede tomar la interfaz de control del DAC aunque la tenga otro (no es forzar con música sonando).
     */
    suspend fun mute(playerPaused: Boolean): Boolean

    /** Quita el silencio puesto por [mute]. true si se pudo. [playerPaused] como en [mute]. */
    suspend fun unmute(playerPaused: Boolean): Boolean
}

interface PlayerAdapter {
    val id: PlayerId
    val snapshot: StateFlow<PlayerSnapshot>

    /** Nombre visible de la app. */
    val displayName: String get() = id.displayName

    suspend fun transport(action: TransportAction): CommandResult

    /** Salta a la pista de la cola con esa clave (HiBy: skipToQueueItem+play; UAPP: N × next/prev). */
    suspend fun jumpTo(queueKey: String): CommandResult

    /** Igual, con [silencer] para no oír las pistas intermedias (solo lo usa UAPP). */
    suspend fun jumpTo(queueKey: String, silencer: JumpSilencer?): CommandResult = jumpTo(queueKey)

    /**
     * Volumen multimedia de Android de la sesión: +1 sube, −1 baja, 0 alterna silencio. Solo se usa cuando
     * el DAC lo maneja el audio de Android (no se puede tocar por USB).
     */
    suspend fun adjustVolume(direction: Int): CommandResult =
        CommandResult.fail(CommandError.NOT_SUPPORTED, "Sin volumen ajustable")

    /** Cancela un avance en curso (UAPP). No-op si no hay ninguno. */
    fun cancelJump()

    /** Libera callbacks, sondeos y corrutinas. */
    fun close()
}

/**
 * Observa las MediaSessions activas (vía MediaListenerService) y expone el adaptador del reproductor
 * relevante: la sesión que está reproduciendo (cualquier app); si ninguna reproduce, la más reciente.
 */
interface SessionMonitor {
    val activePlayer: StateFlow<PlayerAdapter?>

    /** Obsoleto (siempre null): ya se aceptan todas las apps con MediaSession. */
    val unsupportedActivePackage: StateFlow<String?>

    /** Paquete de otra sesión que también está sonando además de la activa, si la hay. */
    val otherSoundingPackage: StateFlow<String?>

    /** false si el usuario no concedió el acceso a notificaciones (necesario para ver sesiones). */
    val hasSessionAccess: StateFlow<Boolean>

    fun start()
    fun stop()
}
