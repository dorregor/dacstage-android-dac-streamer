package io.github.dacstage.ui

import io.github.dacstage.R
import io.github.dacstage.model.ArtRef
import io.github.dacstage.model.ArtSource
import io.github.dacstage.model.CommandError
import io.github.dacstage.model.DacControlAvailability
import io.github.dacstage.model.DacState
import io.github.dacstage.model.HostState
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.PlayerId
import io.github.dacstage.model.QueueItem
import io.github.dacstage.model.QueueState
import io.github.dacstage.model.QueueUnavailableReason
import io.github.dacstage.model.TrackInfo
import io.github.dacstage.model.playerLabel
import io.github.dacstage.network.DisconnectReason
import io.github.dacstage.network.LinkStatus
import io.github.dacstage.ui.common.ArtUrls
import io.github.dacstage.ui.common.UiText
import io.github.dacstage.ui.common.VolumeNote
import io.github.dacstage.ui.common.VolumeUi
import io.github.dacstage.ui.common.uniqueQueueKeys
import io.github.dacstage.ui.tv.TvScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateTest {
    private val art = ArtRef("abc", 1000, 1000, ArtSource.SESSION_BITMAP, hostPath = "/art/abc")

    @Test fun artUrls() {
        assertEquals("https://cdn/x.jpg", ArtUrls.forTv(art.copy(url = "https://cdn/x.jpg"), "http://h:1"))
        assertEquals("http://h:47821/art/abc", ArtUrls.forTv(art, "http://h:47821/"))
        assertEquals("http://h:47821/art/abc?t=tok", ArtUrls.forTv(art, "http://h:47821?t=tok"))
        assertNull(ArtUrls.forTv(art, null))
        assertEquals("abc", ArtUrls.tvModel(art, "http://h:1")!!.id)
    }

    @Test fun volumeShowsConfirmedValueOnly() {
        val v = VolumeUi.from(
            DacState(connected = true, volumeDb = -20f, minDb = -100f, maxDb = 0f, capDb = -10f, control = DacControlAvailability.AVAILABLE),
        )
        assertEquals(UiText.Raw("−20 dB"), v.label)
        assertEquals(0.8f, v.fraction!!, 1e-6f)
        assertEquals(0.9f, v.capFraction!!, 1e-6f)
        assertTrue(v.enabled)
        assertNull(v.note)
    }

    @Test fun volumeUnavailableAndDisconnected() {
        val busy = VolumeUi.from(DacState(connected = true, volumeDb = -20f, control = DacControlAvailability.NEEDS_HOST_CONFIRMATION))
        assertFalse(busy.enabled)
        assertEquals(VolumeNote.UNAVAILABLE, busy.note)
        assertEquals(UiText.Res(R.string.volume_unavailable), UiText.volumeNote(busy.note!!))
        val off = VolumeUi.from(DacState())
        assertFalse(off.connected)
        assertEquals(VolumeNote.DISCONNECTED, off.note)
        assertEquals(UiText.Res(R.string.dac_disconnected), off.label)
        assertEquals(UiText.Raw("—"), VolumeUi.from(DacState(connected = true, control = DacControlAvailability.AVAILABLE)).label)
        assertTrue(VolumeUi.from(DacState(connected = true, volumeDb = -10f, capDb = -10f, control = DacControlAvailability.AVAILABLE)).atCap)
    }

    @Test fun androidAudioVolume() {
        val dac = DacState(connected = true, control = DacControlAvailability.ANDROID_AUDIO)
        val v = VolumeUi.from(dac, io.github.dacstage.model.SystemVolume(7, 15))
        assertTrue(v.enabled)
        assertEquals(UiText.Res(R.string.volume_android_level, 7, 15), v.label)
        assertNull(v.note)
        assertNull(v.capFraction)
        val none = VolumeUi.from(dac, null)
        assertEquals(VolumeNote.ANDROID_AUDIO, none.note)
        assertTrue(!none.enabled)
    }

    @Test fun playerLabels() {
        assertNull(PlayerId.fromPackage(""))
        assertNull(PlayerId.fromPackage("com.spotify.music"))
        assertEquals(PlayerId.UAPP, PlayerId.fromPackage("com.extreamsd.usbaudioplayerpro"))
        assertEquals("Spotify", HostState(player = PlayerId.GENERIC, playerName = "Spotify").playerLabel())
        assertNull(HostState(player = PlayerId.GENERIC).playerLabel())
        assertEquals("UAPP", HostState(player = PlayerId.UAPP).playerLabel())
        assertEquals(
            TvScreen.NoPlayback("Tablet", "Spotify"),
            TvScreen.from(LinkStatus.Connected("Tablet", "1.2.3.4"), HostState(player = PlayerId.GENERIC, playerName = "Spotify")),
        )
    }

    @Test fun queueMessages() {
        assertEquals(
            UiText.Res(R.string.queue_uapp_no_access),
            UiText.queueMessage(QueueState(false, QueueUnavailableReason.UAPP_NO_FILE_ACCESS), "UAPP"),
        )
        assertEquals(UiText.Res(R.string.queue_empty), UiText.queueMessage(QueueState(true, null, emptyList()), "HiBy Music"))
        assertEquals(
            UiText.Res(R.string.queue_not_published_by, "Spotify"),
            UiText.queueMessage(QueueState(false, QueueUnavailableReason.NOT_PUBLISHED), "Spotify"),
        )
        assertEquals(
            UiText.Res(R.string.queue_not_published),
            UiText.queueMessage(QueueState(false, QueueUnavailableReason.NOT_PUBLISHED), null),
        )
        assertNull(UiText.queueMessage(QueueState(true, null, listOf(QueueItem("a")), 0), "HiBy Music"))
    }

    @Test fun commandErrorsAndStatus() {
        assertEquals(UiText.Res(R.string.volume_cap_reached), UiText.commandError(CommandError.VOLUME_ABOVE_CAP))
        assertEquals(UiText.Res(R.string.error_host), UiText.commandError(CommandError.INTERNAL))
        assertNull(UiText.commandError(CommandError.RATE_LIMITED))
        assertNull(UiText.commandError(null))
        assertEquals(UiText.Res(R.string.status_paused), UiText.status(PlaybackStatus.PAUSED))
        assertNull(UiText.status(PlaybackStatus.PLAYING))
    }

    @Test fun disconnectReasons() {
        assertEquals(UiText.Res(R.string.disconnect_connection_lost), UiText.disconnectReason(DisconnectReason.ConnectionLost(null)))
        assertEquals(UiText.Raw("Connection reset"), UiText.disconnectReason(DisconnectReason.ConnectionLost("Connection reset")))
        assertEquals(
            UiText.Res(R.string.disconnect_connect_failed, "1.2.3.4"),
            UiText.disconnectReason(DisconnectReason.ConnectFailed("1.2.3.4")),
        )
        assertEquals(UiText.Res(R.string.disconnect_unpaired), UiText.disconnectReason(DisconnectReason.Unpaired))
    }

    @Test fun uniqueKeys() {
        assertEquals(listOf("a", "b", "a#2"), uniqueQueueKeys(listOf(QueueItem("a"), QueueItem("b"), QueueItem("a"))))
    }

    @Test fun tvScreens() {
        val connected = LinkStatus.Connected("Tablet", "1.2.3.4")
        assertEquals(TvScreen.Searching, TvScreen.from(LinkStatus.Discovering, null))
        assertEquals(TvScreen.Pairing("Tablet", "123456"), TvScreen.from(LinkStatus.PairingRequired("Tablet", "123456"), null))
        assertEquals(TvScreen.Waiting("Tablet"), TvScreen.from(connected, null))
        assertEquals(TvScreen.NoPlayback("Tablet", null), TvScreen.from(connected, HostState()))
        assertEquals(
            TvScreen.UnsupportedPlayer("Tablet", "com.spotify.music"),
            TvScreen.from(connected, HostState(unsupportedPlayerPackage = "com.spotify.music")),
        )
        assertEquals(TvScreen.NowPlaying, TvScreen.from(connected, HostState(player = PlayerId.HIBY, track = TrackInfo("t"))))
    }
}
