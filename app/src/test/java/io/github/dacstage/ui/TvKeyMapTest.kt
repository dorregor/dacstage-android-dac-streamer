package io.github.dacstage.ui

import android.view.KeyEvent
import io.github.dacstage.model.Command
import io.github.dacstage.ui.tv.TvKeyAction
import io.github.dacstage.ui.tv.TvKeyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvKeyMapTest {
    @Test fun mediaKeys() {
        assertEquals(Command.TogglePlayPause, cmd(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(Command.Play, cmd(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(Command.Pause, cmd(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(Command.Next, cmd(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(Command.Previous, cmd(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(Command.ToggleMute, cmd(KeyEvent.KEYCODE_VOLUME_MUTE))
    }

    @Test fun volumeKeysCarryRepeatCount() {
        assertEquals(Command.VolumeStep(1, 0), cmd(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(Command.VolumeStep(-1, 7), cmd(KeyEvent.KEYCODE_VOLUME_DOWN, 7))
        assertEquals(true, TvKeyMap.actionFor(KeyEvent.KEYCODE_VOLUME_UP)?.isVolume)
    }

    @Test fun transportIgnoresRepeats() {
        assertNull(cmd(KeyEvent.KEYCODE_MEDIA_NEXT, 1))
        assertNull(cmd(KeyEvent.KEYCODE_VOLUME_MUTE, 3))
    }

    @Test fun navigationIsNotMapped() {
        listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            .forEach { assertNull(TvKeyMap.actionFor(it)) }
        assertEquals(TvKeyAction.PLAY_PAUSE, TvKeyMap.actionFor(KeyEvent.KEYCODE_HEADSETHOOK))
    }

    private fun cmd(code: Int, repeat: Int = 0): Command? = TvKeyMap.actionFor(code)?.let { TvKeyMap.commandFor(it, repeat) }
}
