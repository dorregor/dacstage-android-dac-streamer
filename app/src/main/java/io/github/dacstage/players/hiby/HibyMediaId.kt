package io.github.dacstage.players.hiby

/**
 * mediaId de HiBy Music: "_SONG_/song_N|[tidal][track][id=<TIDAL_ID>][uri=...]".
 * Solo se extrae el ID si el mediaId declara de verdad una pista de TIDAL.
 */
object HibyMediaId {
    private val TIDAL_TRACK_ID = Regex("\\[tidal]\\s*\\[track]\\s*(?:\\[[^\\]]*]\\s*)*?\\[id=([A-Za-z0-9]+)]", RegexOption.IGNORE_CASE)

    fun tidalTrackId(mediaId: String?): String? {
        if (mediaId.isNullOrBlank()) return null
        return TIDAL_TRACK_ID.find(mediaId)?.groupValues?.get(1)
    }
}
