package io.github.dacstage.ui.common

import androidx.compose.runtime.Immutable
import io.github.dacstage.R
import io.github.dacstage.model.ArtRef
import io.github.dacstage.model.DacControlAvailability
import io.github.dacstage.model.DacState
import io.github.dacstage.model.HostState
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.PlayerId
import io.github.dacstage.model.QueueItem
import io.github.dacstage.model.VolumeControlMode
import io.github.dacstage.model.SystemVolume
import io.github.dacstage.model.playerLabel

/** Lo que pinta la vista "reproduciendo" (sin progreso: va aparte para no recomponer). */
@Immutable
data class NowPlayingUi(
    val player: PlayerId?,
    /** Nombre visible de la app ("Spotify", "UAPP"). */
    val playerName: String?,
    val status: PlaybackStatus,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val art: ArtRef?,
    /** Miniatura de la pista actual en la cola (carga progresiva). */
    val thumbUrl: String?,
    val chips: List<TechChip>,
    val dacName: String?,
    val dacConnected: Boolean,
    /** "1987 · Factory" (MusicBrainz), si el host lo identificó. */
    val detailsLine: String? = null,
    val hasLyrics: Boolean = false,
) {
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING
    /** Clave de pista para transiciones (título+artista+álbum: no hay id estable común). */
    val trackKey: String get() = "${title.orEmpty()}\u0000${artist.orEmpty()}\u0000${album.orEmpty()}"

    companion object {
        val Empty = from(HostState())

        fun from(h: HostState): NowPlayingUi {
            val q = h.queue
            val current = q.items.getOrNull(q.currentIndex)?.takeIf { q.available }
            return NowPlayingUi(
                player = h.player,
                playerName = h.playerLabel(),
                status = h.status,
                title = h.track?.title?.takeIf { it.isNotBlank() },
                artist = h.track?.artist?.takeIf { it.isNotBlank() },
                album = h.track?.album?.takeIf { it.isNotBlank() },
                durationMs = h.track?.durationMs?.takeIf { it > 0 },
                art = h.art,
                thumbUrl = current?.artUrl,
                chips = TechFormat.chips(h.specs, if (h.dac.connected) h.dac.sampleRateHz else null),
                dacName = h.dac.deviceName,
                dacConnected = h.dac.connected,
                detailsLine = h.details?.let { d ->
                    listOfNotNull(d.year?.toString(), d.label?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null }
                },
                hasLyrics = h.lyrics != null,
            )
        }
    }
}

enum class VolumeNote {
    CAP_REACHED, UNAVAILABLE, DISCONNECTED,
    /** El DAC no deja cambiar el volumen por USB (se ajusta en el propio DAC). */
    ON_DEVICE,
    /** El DAC no informa una escala fiable. */
    NO_SCALE,
    /** El DAC no informó su rango (reconectar/actualizar). */
    NO_RANGE,
    /** El DAC lo usa el audio de Android y la app no da volumen ajustable. */
    ANDROID_AUDIO,
}

/** Nota fija según el modo de volumen del DAC; null si se puede controlar desde la app. */
fun volumeModeNote(m: VolumeControlMode?): VolumeNote? = when (m) {
    null, VolumeControlMode.HARDWARE_DB -> null
    VolumeControlMode.FIXED_ON_DEVICE, VolumeControlMode.NONE -> VolumeNote.ON_DEVICE
    VolumeControlMode.STEPS -> VolumeNote.NO_SCALE
    VolumeControlMode.UNAVAILABLE -> VolumeNote.NO_RANGE
}

/** Volumen para pintar: siempre el valor CONFIRMADO del DAC. */
@Immutable
data class VolumeUi(
    val enabled: Boolean,
    val connected: Boolean,
    /** "−23 dB", "DAC desconectado" o "—" si no hay lectura (se resuelve en la UI). */
    val label: UiText,
    val db: Float?,
    val muted: Boolean,
    /** Posición 0..1 en el rango del DAC; null sin rango conocido. */
    val fraction: Float?,
    val capFraction: Float?,
    val atCap: Boolean,
    val note: VolumeNote?,
) {
    companion object {
        fun from(d: DacState, system: SystemVolume? = null): VolumeUi {
            if (d.connected && d.control == DacControlAvailability.ANDROID_AUDIO) return fromSystem(system)
            val connected = d.connected && d.control != DacControlAvailability.DISCONNECTED
            val modeNote = volumeModeNote(d.volumeMode)
            val available = connected && d.control == DacControlAvailability.AVAILABLE && modeNote == null
            val min = d.minDb
            val max = d.maxDb
            val range = if (min != null && max != null && max > min) min to max else null
            fun frac(v: Float?) = if (v == null || range == null) null
            else ((v - range.first) / (range.second - range.first)).coerceIn(0f, 1f)
            val muted = d.muted == true
            val vol = d.volumeDb
            return VolumeUi(
                enabled = available,
                connected = connected,
                label = when {
                    !connected -> UiText.Res(R.string.dac_disconnected)
                    modeNote == VolumeNote.ON_DEVICE -> UiText.Res(R.string.volume_label_on_dac)
                    vol == null -> UiText.Raw("—")
                    else -> UiText.Raw(TechFormat.db(vol))
                },
                db = vol,
                muted = muted,
                fraction = frac(vol),
                capFraction = frac(d.capDb),
                atCap = vol != null && d.capDb != null && vol >= d.capDb - 0.05f,
                note = when {
                    !connected -> VolumeNote.DISCONNECTED
                    modeNote != null -> modeNote
                    !available -> VolumeNote.UNAVAILABLE
                    else -> null
                },
            )
        }
    }
}

/**
 * El DAC lo usa el audio de Android: el volumen es el multimedia de Android de la app (pasos, no dB).
 * Sin tope en dB (es atenuación digital de Android; el nivel del DAC no cambia).
 */
private fun fromSystem(sys: SystemVolume?): VolumeUi {
    val frac = sys?.let { (it.current.toFloat() / it.max).coerceIn(0f, 1f) }
    return VolumeUi(
        enabled = sys != null,
        connected = true,
        label = sys?.let { UiText.Res(R.string.volume_android_level, it.current, it.max) }
            ?: UiText.Res(R.string.volume_label_android),
        db = null,
        muted = sys?.muted == true,
        fraction = frac,
        capFraction = null,
        atCap = false,
        note = if (sys == null) VolumeNote.ANDROID_AUDIO else null,
    )
}

/** Claves únicas para Lazy* aunque el host repita alguna (las listas perezosas no admiten duplicados). */
fun uniqueQueueKeys(items: List<QueueItem>): List<String> {
    val seen = HashSet<String>(items.size * 2)
    return items.mapIndexed { i, it -> if (seen.add(it.key)) it.key else "${it.key}#$i" }
}
