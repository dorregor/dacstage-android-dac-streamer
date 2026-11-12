package io.github.dacstage.model

import kotlinx.serialization.Serializable

/*
 * Modelo compartido host <-> TV. Es lo único que viaja por el socket (kotlinx.serialization).
 *
 * Regla no negociable: NO HAY VALORES POR DEFECTO INVENTADOS. Todo dato técnico es nullable y lleva su
 * fuente real; si no hay fuente, el campo es null y la UI no muestra el chip.
 */

@Serializable
enum class PlayerId(val packageName: String, val displayName: String) {
    HIBY("com.hiby.music", "HiBy Music"),
    UAPP("com.extreamsd.usbaudioplayerpro", "UAPP"),

    /**
     * Cualquier otra app con MediaSession (Spotify, YouTube Music, Poweramp…). Solo lo que da la sesión:
     * metadatos, carátula, progreso, transporte, cola si la publica y volumen multimedia de Android.
     * El nombre real de la app viaja en [HostState.playerName].
     */
    GENERIC("", "App");

    companion object {
        /** Reproductor con adaptador propio para ese paquete (HiBy/UAPP); null para el resto. */
        fun fromPackage(pkg: String?): PlayerId? =
            entries.firstOrNull { it != GENERIC && it.packageName == pkg }
    }
}

@Serializable
enum class PlaybackStatus { PLAYING, PAUSED, BUFFERING, STOPPED, NONE }

/** De dónde sale un dato técnico. Sin fuente, no hay dato. */
@Serializable
enum class SpecSource {
    /** Clock Source del DAC (GET_CUR CS=0x01). Fuente fiable de la frecuencia real. */
    DAC_CLOCK,
    /** Metadatos publicados por la MediaSession del reproductor. */
    SESSION_METADATA,
    /** Atributos Resolution/SampleRate de PlayQueue.xml de UAPP. */
    UAPP_QUEUE_FILE,
    /** Lectura pasiva de pantalla (opcional, desactivada por defecto). */
    PASSIVE_SCREEN_READ,
    /** Log de UAPP (UAPP.txt): extensión del stream que abrió y tamaño de trama USB que preparó. */
    UAPP_LOG,
    /** Descriptores del DAC (formato de streaming) cruzados con la trama USB que usa el reproductor. */
    DAC_DESCRIPTOR,
}

@Serializable
data class Spec<T>(val value: T, val source: SpecSource)

@Serializable
data class TechSpecs(
    val sampleRateHz: Spec<Int>? = null,
    val bitDepth: Spec<Int>? = null,
    /** Tipo de archivo/códec tal como lo publica la fuente (p. ej. "FLAC"). */
    val codec: Spec<String>? = null,
    val bitrateKbps: Spec<Int>? = null,
    /**
     * Bits útiles que el reproductor envía AL DAC por USB (no los del archivo): bBitResolution del formato de
     * streaming del DAC cuyo tamaño de trama coincide con el que preparó el reproductor.
     */
    val usbBitDepth: Spec<Int>? = null,
) {
    val isEmpty: Boolean
        get() = sampleRateHz == null && bitDepth == null && codec == null && bitrateKbps == null && usbBitDepth == null
}

@Serializable
enum class ArtSource {
    /** URL de CDN publicada por el reproductor (UAPP: ArtURL de PlayQueue.xml reescrita al mayor tamaño). */
    PLAYER_QUEUE_URL,
    /** METADATA_KEY_ART_URI / ALBUM_ART_URI de la MediaSession. */
    SESSION_ART_URI,
    /** METADATA_KEY_ART / ALBUM_ART (bitmap embebido, suele venir reducido). */
    SESSION_BITMAP,
    /** Cover Art Archive, para el disco que MusicBrainz identificó (opcional, desactivado por defecto). */
    COVER_ART_ARCHIVE,
}

/** Servicio público del que sale un dato de información (no técnico). */
@Serializable
enum class InfoSource { LRCLIB, MUSICBRAINZ }

@Serializable
data class LyricLine(val timeMs: Long, val text: String)

/**
 * Letra de la pista actual. [synced] con tiempos (LRC) si existe; si no, [plain]. [instrumental] = la
 * fuente confirma que no tiene letra.
 */
@Serializable
data class Lyrics(
    val synced: List<LyricLine>? = null,
    val plain: String? = null,
    val instrumental: Boolean = false,
    val source: InfoSource = InfoSource.LRCLIB,
)

/** Datos del disco/grabación que MusicBrainz identificó con seguridad (todos opcionales). */
@Serializable
data class TrackDetails(
    /** Año de la primera edición de la grabación. */
    val year: Int? = null,
    val label: String? = null,
    val releaseTitle: String? = null,
    val recordingMbid: String? = null,
    val releaseMbid: String? = null,
    val source: InfoSource = InfoSource.MUSICBRAINZ,
)

/**
 * Carátula ya verificada por el host (descargada, medida y con hash SHA-256 de los bytes = [id]).
 * - [url] != null: URL pública; la TV la descarga directo (clave de caché = [id]).
 * - [url] == null: la TV la pide al servidor HTTP del host en [hostPath] ("/art/<id>").
 * [widthPx]/[heightPx] son el tamaño REAL medido de la imagen elegida.
 */
@Serializable
data class ArtRef(
    val id: String,
    val widthPx: Int,
    val heightPx: Int,
    val source: ArtSource,
    val url: String? = null,
    val hostPath: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class TrackInfo(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    /** ID de pista de TIDAL si el reproductor lo expone (HiBy mediaId, UAPP ID). Solo informativo. */
    val tidalTrackId: String? = null,
)

/**
 * Posición en el instante en que el host envió el mensaje. La TV la sella con su reloj monotónico al
 * recibirla y extrapola con [speed] mientras status == PLAYING (sin depender de relojes cruzados).
 */
@Serializable
data class Progress(val positionMs: Long, val speed: Float = 1f)

@Serializable
data class QueueItem(
    /** Clave estable: HiBy "hiby:<queueId>", UAPP "uapp:<índice>:<ID>". */
    val key: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    /** Miniatura pública si existe (la TV la cachea en disco). */
    val artUrl: String? = null,
    val specs: TechSpecs = TechSpecs(),
)

@Serializable
enum class QueueUnavailableReason {
    NO_PLAYER,
    EMPTY,
    /** UAPP: sin acceso a sus archivos (depuración inalámbrica local no configurada o no conectada). */
    UAPP_NO_FILE_ACCESS,
    /** UAPP: PlayQueue.xml/PosA.txt no coinciden con la MediaSession. Nunca se simula la cola. */
    UAPP_OUT_OF_SYNC,
    /** El reproductor no publica cola. */
    NOT_PUBLISHED,
}

@Serializable
data class QueueState(
    val available: Boolean = false,
    val unavailableReason: QueueUnavailableReason? = QueueUnavailableReason.NO_PLAYER,
    val items: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    /** Cambia cuando cambia la lista (no cuando solo cambia currentIndex). */
    val version: Long = 0,
)

/** Avance en curso hacia una pista de la cola ("Avanzando…" en UAPP: N × siguiente/anterior). */
@Serializable
data class JumpProgress(val targetKey: String, val stepsDone: Int, val stepsTotal: Int)

@Serializable
enum class DacControlAvailability {
    /** Se puede leer/escribir volumen ahora. */
    AVAILABLE,
    /** El reproductor tiene la interfaz; tomarla con música sonando requiere confirmación en el host. */
    NEEDS_HOST_CONFIRMATION,
    /** No se pudo reclamar la interfaz AudioControl. */
    BUSY,
    /** Falta el permiso USB (diálogo en el host). */
    NO_PERMISSION,
    DISCONNECTED,
    /**
     * El DAC lo usa el audio de Android (el reproductor suena por el sistema): no se toca por USB.
     * El volumen va por el volumen multimedia de Android ([HostState.systemVolume]).
     */
    ANDROID_AUDIO,
}

/** Nombre visible del reproductor activo: el de la app; para HiBy/UAPP su nombre fijo. */
fun HostState.playerLabel(): String? =
    playerName?.takeIf { it.isNotBlank() } ?: player?.takeIf { it != PlayerId.GENERIC }?.displayName

/**
 * Preferencias de pantalla de la TV que se eligen en el host (la TV no tiene pantalla de ajustes).
 * [earthView]: fondo del reposo con fotos de Google Earth View, que la TV descarga directamente.
 */
@Serializable
data class TvDisplayPrefs(
    val earthView: Boolean = false,
    val photoIntervalSec: Int = DEFAULT_PHOTO_INTERVAL_SEC,
    /** Tiempo sin tocar el mando hasta el reposo. */
    val idleSec: Int = DEFAULT_IDLE_SEC,
    /** Qué aparece en el reposo sin letra. */
    val showArtwork: Boolean = true,
    val showSongInfo: Boolean = true,
    val showPhotoInfo: Boolean = true,
    /** Formato y calidad (códec, frecuencia, bits… lo que informen reproductor y DAC). */
    val showTech: Boolean = true,
    /** Qué aparece en el reposo con letra. */
    val lyricsShowArtwork: Boolean = true,
    val lyricsShowSongInfo: Boolean = true,
    val lyricsShowPhotoInfo: Boolean = true,
    val lyricsShowTech: Boolean = false,
    /** Opacidad base del fondo difuminado de los textos (0..[MAX_BACKDROP]); la TV la adapta a cada foto. */
    val backdropOpacity: Float = DEFAULT_BACKDROP,
    /** Escala de todo el texto del reposo (1 = normal). */
    val textScale: Float = 1f,
) {
    companion object {
        const val DEFAULT_PHOTO_INTERVAL_SEC = 300
        const val DEFAULT_IDLE_SEC = 300
        const val DEFAULT_BACKDROP = 0.4f
        const val MAX_BACKDROP = 0.8f
        const val MIN_TEXT_SCALE = 0.8f
        const val MAX_TEXT_SCALE = 1.5f
        /** Opciones del ajuste de reposo (segundos). */
        val IDLE_OPTIONS_SEC = listOf(60, 120, 300, 600, 900, 1800)
        /** Opciones del ajuste (segundos). */
        val PHOTO_INTERVALS_SEC = listOf(60, 120, 300, 600, 1800)
    }
}

/** Volumen multimedia de Android de la sesión activa (pasos del sistema, no dB). */
@Serializable
data class SystemVolume(val current: Int, val max: Int, val muted: Boolean = false)

@Serializable
data class DacState(
    val connected: Boolean = false,
    val deviceName: String? = null,
    /** Frecuencia real leída del Clock Source. */
    val sampleRateHz: Int? = null,
    /** Último valor CONFIRMADO leyendo el DAC (GET_CUR), nunca el pedido. */
    val volumeDb: Float? = null,
    val muted: Boolean? = null,
    val minDb: Float? = null,
    val maxDb: Float? = null,
    val stepDb: Float? = null,
    /** Tope impuesto por el host. La TV solo lo ve; no puede cambiarlo. */
    val capDb: Float? = null,
    val control: DacControlAvailability = DacControlAvailability.DISCONNECTED,
    /** Cómo se puede controlar el volumen de este DAC por USB (null = sin DAC o aún desconocido). */
    val volumeMode: VolumeControlMode? = null,
)

/** Espejo serializable de dac.DacVolumeMode. Solo HARDWARE_DB permite cambiar el volumen desde la app. */
@Serializable
enum class VolumeControlMode { HARDWARE_DB, STEPS, FIXED_ON_DEVICE, NONE, UNAVAILABLE }

/** Estado completo que publica el host. */
@Serializable
data class HostState(
    val hostName: String = "",
    val player: PlayerId? = null,
    /** Nombre visible de la app activa ("Spotify"); null = usar [PlayerId.displayName]. */
    val playerName: String? = null,
    /** Obsoleto: ya se aceptan todas las apps con MediaSession. Se mantiene por compatibilidad. */
    val unsupportedPlayerPackage: String? = null,
    val status: PlaybackStatus = PlaybackStatus.NONE,
    val track: TrackInfo? = null,
    val progress: Progress? = null,
    val art: ArtRef? = null,
    val specs: TechSpecs = TechSpecs(),
    val queue: QueueState = QueueState(),
    val jump: JumpProgress? = null,
    val dac: DacState = DacState(),
    /** Preferencias de pantalla de la TV elegidas en el host. */
    val tvDisplay: TvDisplayPrefs = TvDisplayPrefs(),
    /** Volumen multimedia de Android, solo cuando el DAC lo usa el audio de Android (control ANDROID_AUDIO). */
    val systemVolume: SystemVolume? = null,
    /** Letra de la pista actual (LRCLIB), solo si está activado en el host y coincide con seguridad. */
    val lyrics: Lyrics? = null,
    /** La letra de esta pista se está buscando (la TV mantiene el modo letra mientras tanto). */
    val lyricsPending: Boolean = false,
    /** Año/sello de la pista actual (MusicBrainz), con las mismas condiciones. */
    val details: TrackDetails? = null,
    /** Reloj monotónico del host en que se calculó [progress]. No viaja: sirve para re-sellar al enviar. */
    @kotlinx.serialization.Transient val progressStampMs: Long = 0L,
) {
    /**
     * Mismo estado con la posición llevada a [nowMs]. El envío a la TV puede ir algo después del cálculo
     * (agrupación de cambios, ritmo de envío) y la TV sella al recibir: sin esto, la TV iría atrasada.
     */
    fun progressAt(nowMs: Long): HostState {
        val p = progress ?: return this
        if (status != PlaybackStatus.PLAYING || progressStampMs <= 0L || nowMs <= progressStampMs) return this
        val pos = p.positionMs + ((nowMs - progressStampMs) * p.speed).toLong()
        val max = track?.durationMs?.takeIf { it > 0 }
        return copy(progress = p.copy(positionMs = if (max != null) pos.coerceAtMost(max) else pos), progressStampMs = nowMs)
    }
}
