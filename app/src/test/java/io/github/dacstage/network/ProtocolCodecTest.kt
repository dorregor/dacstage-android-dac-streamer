package io.github.dacstage.network

import io.github.dacstage.model.ArtRef
import io.github.dacstage.model.ArtSource
import io.github.dacstage.model.Command
import io.github.dacstage.model.CommandError
import io.github.dacstage.model.CommandResult
import io.github.dacstage.model.DacControlAvailability
import io.github.dacstage.model.DacState
import io.github.dacstage.model.HostState
import io.github.dacstage.model.JumpProgress
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.PlayerId
import io.github.dacstage.model.Progress
import io.github.dacstage.model.QueueItem
import io.github.dacstage.model.QueueState
import io.github.dacstage.model.Spec
import io.github.dacstage.model.SpecSource
import io.github.dacstage.model.TechSpecs
import io.github.dacstage.model.TrackInfo
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodecTest {

    private val allCommands: List<Command> = listOf(
        Command.TogglePlayPause, Command.Play, Command.Pause, Command.Next, Command.Previous,
        Command.JumpToQueueItem("uapp:3:12345"), Command.CancelJump,
        Command.VolumeStep(1, 3), Command.VolumeStep(-1), Command.ToggleMute, Command.RefreshState,
    )

    @Test
    fun roundTripOfEveryMessage() {
        val state = StateCodec.encode(sampleState())
        val messages = listOf(
            WireMessage.Hello(PROTOCOL_VERSION, "tvdevice01", "TCL «salón»"),
            WireMessage.PairRequest("tvdevice01", "TCL", "ab".repeat(32)),
            WireMessage.AuthResponse("cd".repeat(32)),
            WireMessage.Ping,
            WireMessage.Challenge("ef".repeat(32), "hostid", "Tablet\nlínea"),
            WireMessage.PairPending,
            WireMessage.PairResult(ok = true, token = "01".repeat(32)),
            WireMessage.PairResult(ok = false, reason = WireReasons.REJECTED),
            WireMessage.AuthOk("aa".repeat(16), ART_PORT),
            WireMessage.AuthFail(WireReasons.BAD_TOKEN),
            WireMessage.Snapshot(1, state),
            WireMessage.Patch(2, JsonObject(mapOf("track" to JsonNull, "status" to state["status"]!!))),
            WireMessage.CmdResult("c1", CommandResult.OK),
            WireMessage.CmdResult("c2", CommandResult.fail(CommandError.VOLUME_ABOVE_CAP, "tope")),
            WireMessage.Pong,
        ) + allCommands.mapIndexed { i, c -> WireMessage.Cmd("c$i", c) }

        for (m in messages) {
            val line = WireCodec.encode(m)
            assertFalse("una sola línea: $line", line.contains('\n'))
            assertEquals(Decoded.Ok(m), WireCodec.decode(line))
        }
    }

    @Test
    fun wireFormatUsesTypeDiscriminator() {
        val line = WireCodec.encode(WireMessage.Cmd("c1", Command.VolumeStep(-1)))
        assertEquals("""{"type":"cmd","id":"c1","command":{"type":"vol_step","deltaDb":-1,"repeatCount":0}}""", line)
        assertEquals("""{"type":"ping"}""", WireCodec.encode(WireMessage.Ping))
    }

    @Test
    fun discriminatorMayComeAnywhereAndUnknownKeysAreIgnored() {
        val d = WireCodec.decode("""{"id":"x","extra":1,"command":{"deltaDb":1,"type":"vol_step"},"type":"cmd"}""")
        assertEquals(Decoded.Ok(WireMessage.Cmd("x", Command.VolumeStep(1, 0))), d)
    }

    @Test
    fun invalidInputIsReportedNotThrown() {
        assertTrue(WireCodec.decode("{nope") is Decoded.Invalid)
        assertTrue(WireCodec.decode("[]") is Decoded.Invalid)
        assertTrue(WireCodec.decode("42") is Decoded.Invalid)
        assertTrue(WireCodec.decode("""{"type":"bogus"}""") is Decoded.Invalid)
        assertTrue(WireCodec.decode("""{"type":"hello"}""") is Decoded.Invalid)
        assertEquals(Decoded.BadCommand("c9"), WireCodec.decode("""{"type":"cmd","id":"c9","command":{"type":"self_destruct"}}"""))
        assertEquals(
            Decoded.BadCommand("c9"),
            WireCodec.decode("""{"type":"cmd","id":"c9","command":{"type":"vol_step","deltaDb":"mucho"}}"""),
        )
        assertTrue(WireCodec.decode("""{"type":"cmd","id":"${"x".repeat(100)}","command":{}}""") is Decoded.Invalid)
    }

    @Test
    fun hostStateRoundTripKeepsEveryField() {
        val s = sampleState()
        assertEquals(s, StateCodec.decode(StateCodec.encode(s)))
        assertEquals(HostState(), StateCodec.decode(StateCodec.encode(HostState())))
    }

    companion object {
        fun sampleState() = HostState(
            hostName = "Tablet",
            player = PlayerId.UAPP,
            status = PlaybackStatus.PLAYING,
            track = TrackInfo("Título", "Artista", "Álbum", 245_000, "123"),
            progress = Progress(12_345, 1f),
            art = ArtRef("a".repeat(64), 1280, 1280, ArtSource.PLAYER_QUEUE_URL, url = "https://cdn/x.jpg", hostPath = "/art/x", mimeType = "image/jpeg"),
            specs = TechSpecs(
                sampleRateHz = Spec(96_000, SpecSource.DAC_CLOCK),
                bitDepth = Spec(24, SpecSource.UAPP_QUEUE_FILE),
                codec = Spec("FLAC", SpecSource.SESSION_METADATA),
            ),
            queue = QueueState(
                available = true,
                unavailableReason = null,
                items = listOf(QueueItem("uapp:0:1", "A"), QueueItem("uapp:1:2", "B", specs = TechSpecs(bitrateKbps = Spec(900, SpecSource.UAPP_QUEUE_FILE)))),
                currentIndex = 1,
                version = 7,
            ),
            jump = JumpProgress("uapp:1:2", 1, 3),
            dac = DacState(true, "DAC", 96_000, -20f, false, -60f, 0f, 1f, -10f, DacControlAvailability.AVAILABLE),
        )
    }
}
