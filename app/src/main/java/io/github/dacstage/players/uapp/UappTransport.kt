package io.github.dacstage.players.uapp

import android.media.session.MediaController

/**
 * Único acceso de UAPP a TransportControls: play/pausa/siguiente/anterior/posición y nada más.
 * (Los "play from" por búsqueda o id vacían la cola del usuario; skipToQueueItem no está implementado.)
 */
internal class UappTransport(private val controller: MediaController) {
    fun play() = controller.transportControls.play()
    fun pause() = controller.transportControls.pause()
    fun next() = controller.transportControls.skipToNext()
    fun previous() = controller.transportControls.skipToPrevious()
    fun seekTo(positionMs: Long) = controller.transportControls.seekTo(positionMs)
}
