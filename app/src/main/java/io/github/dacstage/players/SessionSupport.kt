package io.github.dacstage.players

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.PlaybackState
import io.github.dacstage.model.ArtSource
import io.github.dacstage.model.PlaybackStatus

/** Metadatos de la MediaSession tal como vienen (sin rellenar nada). */
internal data class SessionMeta(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val mediaId: String?,
    val artUris: List<String>,
    val bitmaps: List<Bitmap>,
) {
    /** Clave estable de la pista según la sesión. */
    fun identity(): String = mediaId ?: listOf(title, artist, album, durationMs).joinToString("|")

    fun sessionArtCandidates(): List<ArtCandidate> =
        artUris.map { ArtCandidate.Url(it, ArtSource.SESSION_ART_URI) } +
            bitmaps.map { ArtCandidate.Embedded(it, ArtSource.SESSION_BITMAP) }
}

internal fun MediaMetadata?.toSessionMeta(): SessionMeta? {
    val m = this ?: return null
    fun s(key: String) = m.getString(key)?.trim()?.takeIf { it.isNotEmpty() }
    val title = s(MediaMetadata.METADATA_KEY_TITLE) ?: s(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
    val artist = s(MediaMetadata.METADATA_KEY_ARTIST) ?: s(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
    val duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }
    val uris = listOfNotNull(s(MediaMetadata.METADATA_KEY_ART_URI), s(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)).distinct()
    val bitmaps = listOfNotNull(
        m.getBitmap(MediaMetadata.METADATA_KEY_ART),
        m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
    ).distinctBy { System.identityHashCode(it) }.filter { !it.isRecycled }
    return SessionMeta(
        title = title,
        artist = artist,
        album = s(MediaMetadata.METADATA_KEY_ALBUM),
        durationMs = duration,
        mediaId = s(MediaMetadata.METADATA_KEY_MEDIA_ID),
        artUris = uris,
        bitmaps = bitmaps,
    )
}

internal fun PlaybackState?.toStatus(): PlaybackStatus = when (this?.state) {
    null, PlaybackState.STATE_NONE -> PlaybackStatus.NONE
    PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> PlaybackStatus.PLAYING
    PlaybackState.STATE_PAUSED -> PlaybackStatus.PAUSED
    PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
    PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
    PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> PlaybackStatus.BUFFERING
    PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> PlaybackStatus.STOPPED
    else -> PlaybackStatus.NONE
}

internal fun PlaybackStatus.isActive() = this == PlaybackStatus.PLAYING || this == PlaybackStatus.BUFFERING

/** Posición extrapolada desde la última actualización de la sesión (reloj elapsedRealtime). */
object PositionMath {
    fun extrapolate(
        positionMs: Long,
        lastUpdateElapsedMs: Long,
        speed: Float,
        nowElapsedMs: Long,
        playing: Boolean,
        durationMs: Long?,
    ): Long {
        var pos = positionMs
        if (playing && lastUpdateElapsedMs > 0 && nowElapsedMs > lastUpdateElapsedMs && speed > 0f) {
            pos += ((nowElapsedMs - lastUpdateElapsedMs) * speed).toLong()
        }
        if (durationMs != null && durationMs > 0) pos = pos.coerceAtMost(durationMs)
        return pos.coerceAtLeast(0)
    }
}
