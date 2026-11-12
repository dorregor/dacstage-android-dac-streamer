package io.github.dacstage.ui.tv

import androidx.compose.runtime.Immutable
import io.github.dacstage.model.HostState
import io.github.dacstage.model.playerLabel
import io.github.dacstage.model.PlayerId
import io.github.dacstage.network.DisconnectReason
import io.github.dacstage.network.LinkStatus

/** Pantalla que toca mostrar en la TV según el enlace y el estado del host. */
@Immutable
sealed interface TvScreen {
    data object Searching : TvScreen
    data class Connecting(val host: String) : TvScreen
    data class Pairing(val host: String, val code: String) : TvScreen
    data class Disconnected(val reason: DisconnectReason?, val retryInMs: Long?) : TvScreen
    /** Conectado, esperando el primer estado completo. */
    data class Waiting(val host: String) : TvScreen
    data class NoPlayback(val host: String, val playerName: String?) : TvScreen
    data class UnsupportedPlayer(val host: String, val packageName: String) : TvScreen
    data object NowPlaying : TvScreen

    companion object {
        fun from(status: LinkStatus, host: HostState?): TvScreen = when (status) {
            LinkStatus.Discovering -> Searching
            is LinkStatus.Connecting -> Connecting(status.hostLabel)
            is LinkStatus.PairingRequired -> Pairing(status.hostLabel, status.code)
            is LinkStatus.Disconnected -> Disconnected(status.reason, status.retryInMs)
            is LinkStatus.Connected -> {
                val name = host?.hostName?.takeIf { it.isNotBlank() } ?: status.hostName
                when {
                    host == null -> Waiting(name)
                    host.player == null && host.unsupportedPlayerPackage != null ->
                        UnsupportedPlayer(name, host.unsupportedPlayerPackage)
                    host.player == null || host.track == null -> NoPlayback(name, host.playerLabel())
                    else -> NowPlaying
                }
            }
        }
    }
}
