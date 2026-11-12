package io.github.dacstage.ui.tv

import android.view.KeyEvent
import io.github.dacstage.model.Command

/** Teclas globales de la TV (multimedia y volumen) que se convierten en comandos al host. */
enum class TvKeyAction(val isVolume: Boolean = false) {
    PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS,
    VOLUME_UP(true), VOLUME_DOWN(true), VOLUME_MUTE(true),
}

object TvKeyMap {
    fun actionFor(keyCode: Int): TvKeyAction? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> TvKeyAction.PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY -> TvKeyAction.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> TvKeyAction.PAUSE
        KeyEvent.KEYCODE_MEDIA_NEXT -> TvKeyAction.NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> TvKeyAction.PREVIOUS
        KeyEvent.KEYCODE_VOLUME_UP -> TvKeyAction.VOLUME_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> TvKeyAction.VOLUME_DOWN
        KeyEvent.KEYCODE_VOLUME_MUTE -> TvKeyAction.VOLUME_MUTE
        else -> null
    }

    /**
     * Comando para una pulsación (ACTION_DOWN). Transporte y mute solo en la pulsación inicial
     * (repeatCount 0) → null en repeticiones; el volumen se repite con su repeatCount.
     */
    fun commandFor(action: TvKeyAction, repeatCount: Int): Command? = when (action) {
        TvKeyAction.VOLUME_UP -> Command.VolumeStep(+1, repeatCount)
        TvKeyAction.VOLUME_DOWN -> Command.VolumeStep(-1, repeatCount)
        else -> if (repeatCount > 0) null else when (action) {
            TvKeyAction.PLAY_PAUSE -> Command.TogglePlayPause
            TvKeyAction.PLAY -> Command.Play
            TvKeyAction.PAUSE -> Command.Pause
            TvKeyAction.NEXT -> Command.Next
            TvKeyAction.PREVIOUS -> Command.Previous
            TvKeyAction.VOLUME_MUTE -> Command.ToggleMute
            else -> null
        }
    }
}
