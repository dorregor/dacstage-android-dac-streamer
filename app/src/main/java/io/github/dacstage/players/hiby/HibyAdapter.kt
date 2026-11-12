package io.github.dacstage.players.hiby

import android.media.AudioManager
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import io.github.dacstage.model.PlayerId
import io.github.dacstage.players.SessionAdapter

/**
 * HiBy Music sobre su MediaController. Publica la cola real (mediaId con ID de TIDAL) y salta con
 * skipToQueueItem + play. No publica formato/bitrate: specs vacías.
 */
class HibyAdapter(
    controller: MediaController,
    handler: Handler = Handler(Looper.getMainLooper()),
    audioManager: AudioManager? = null,
) : SessionAdapter(
    controller = controller,
    id = PlayerId.HIBY,
    displayName = PlayerId.HIBY.displayName,
    keyPrefix = KEY_PREFIX,
    handler = handler,
    audioManager = audioManager,
    enforceActions = false,
) {
    override fun externalTrackId(metaMediaId: String?, queueMediaId: String?): String? =
        HibyMediaId.tidalTrackId(metaMediaId) ?: HibyMediaId.tidalTrackId(queueMediaId)

    companion object {
        const val KEY_PREFIX = "hiby:"
    }
}
