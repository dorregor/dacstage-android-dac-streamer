package io.github.dacstage.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import io.github.dacstage.model.Command
import io.github.dacstage.model.DacControlAvailability
import io.github.dacstage.model.DacState
import io.github.dacstage.model.HostState
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.PlayerId
import io.github.dacstage.model.Progress
import io.github.dacstage.model.QueueItem
import io.github.dacstage.model.QueueState
import io.github.dacstage.model.Spec
import io.github.dacstage.model.SpecSource
import io.github.dacstage.model.TechSpecs
import io.github.dacstage.model.TrackInfo
import io.github.dacstage.network.CommandAck
import io.github.dacstage.network.HostLink
import io.github.dacstage.network.LinkStatus
import io.github.dacstage.tv.TvViewModel
import io.github.dacstage.ui.tv.TvRoot
import io.github.dacstage.ui.mobile.HostRoot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/* Previews (solo debug). TV a 960×540 dp / 320 dpi como la TCL; host en teléfono y tablet. */

private val sampleState = HostState(
    hostName = "Tablet del salón",
    player = PlayerId.HIBY,
    status = PlaybackStatus.PLAYING,
    track = TrackInfo("Nocturno para cuerdas en re menor", "Orquesta de Cámara Aurora", "Nocturnos", 412_000),
    progress = Progress(131_000),
    specs = TechSpecs(
        sampleRateHz = Spec(192_000, SpecSource.SESSION_METADATA),
        bitDepth = Spec(24, SpecSource.SESSION_METADATA),
        codec = Spec("FLAC", SpecSource.SESSION_METADATA),
    ),
    queue = QueueState(
        available = true,
        unavailableReason = null,
        items = (0 until 8).map { QueueItem("hiby:$it", "Pista ${it + 1}", "Artista", "Álbum", 200_000L + it * 1000) },
        currentIndex = 2,
        version = 1,
    ),
    dac = DacState(
        connected = true, deviceName = "FiiO K7", sampleRateHz = 192_000, volumeDb = -23f, muted = false,
        minDb = -127f, maxDb = 0f, stepDb = 0.5f, capDb = -10f, control = DacControlAvailability.AVAILABLE,
    ),
)

private class StaticLink(status: LinkStatus, state: HostState?) : HostLink {
    override val status: StateFlow<LinkStatus> = MutableStateFlow(status)
    override val hostState: StateFlow<HostState?> = MutableStateFlow(state)
    override val acks: SharedFlow<CommandAck> = MutableSharedFlow()
    override val artBaseUrl: StateFlow<String?> = MutableStateFlow(null)
    override fun send(command: Command) = "preview"
    override fun start() = Unit
    override fun stop() = Unit
    override fun connectManual(host: String, port: Int) = Unit
    override fun forgetPairing() = Unit
}

@Composable
private fun TvPreview(status: LinkStatus, state: HostState?) {
    val link = remember { StaticLink(status, state) }
    TvRoot(link, viewModel = remember { TvViewModel(link) })
}

@Preview(name = "TV · reproduciendo", device = "spec:width=960dp,height=540dp,dpi=320")
@Composable
private fun TvNowPlayingPreview() = TvPreview(LinkStatus.Connected("Tablet del salón", "192.168.1.50"), sampleState)

@Preview(name = "TV · emparejar", device = "spec:width=960dp,height=540dp,dpi=320")
@Composable
private fun TvPairingPreview() = TvPreview(LinkStatus.PairingRequired("Tablet del salón", "482913"), null)

@Preview(name = "TV · sin reproducción", device = "spec:width=960dp,height=540dp,dpi=320")
@Composable
private fun TvNoPlaybackPreview() = TvPreview(LinkStatus.Connected("Tablet del salón", "192.168.1.50"), HostState(hostName = "Tablet del salón"))

@Preview(name = "Host · teléfono", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
private fun HostPhonePreview() = HostPreview()

@Preview(name = "Host · tablet", device = "spec:width=1205dp,height=753dp,dpi=340")
@Composable
private fun HostTabletPreview() = HostPreview()

@Composable
private fun HostPreview() {
    val context = LocalContext.current
    val deps = remember { DemoFactoryImpl.hostDeps(context, "play") }
    HostRoot(deps)
}
