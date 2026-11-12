package io.github.dacstage.ui.common

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import io.github.dacstage.R
import io.github.dacstage.model.CommandError
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.PlayerId
import io.github.dacstage.model.QueueState
import io.github.dacstage.model.QueueUnavailableReason
import io.github.dacstage.network.DisconnectReason

/**
 * Texto de UI sin Context: el estado y los ViewModels guardan ids de recursos (o texto ya neutro,
 * p. ej. "−23 dB") y la UI lo resuelve con [asString] / [resolve] en el idioma del dispositivo.
 * Los argumentos pueden ser a su vez [UiText] (se resuelven primero).
 */
@Immutable
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any>) : UiText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Texto que no se traduce (valores técnicos, nombres, mensajes del sistema). */
    data class Raw(val text: String) : UiText

    fun resolve(res: Resources): String = when (this) {
        is Raw -> text
        is Res -> if (args.isEmpty()) res.getString(id) else res.getString(id, *resolveArgs(args, res))
        is Plural -> res.getQuantityString(id, count, *resolveArgs(args, res))
    }

    companion object {
        fun volumeNote(n: VolumeNote): UiText = Res(
            when (n) {
                VolumeNote.CAP_REACHED -> R.string.volume_cap_reached
                VolumeNote.UNAVAILABLE -> R.string.volume_unavailable
                VolumeNote.DISCONNECTED -> R.string.dac_disconnected
                VolumeNote.ON_DEVICE -> R.string.volume_mode_on_device
                VolumeNote.NO_SCALE -> R.string.volume_mode_no_scale
                VolumeNote.NO_RANGE -> R.string.volume_mode_no_range
                VolumeNote.ANDROID_AUDIO -> R.string.volume_android_no_control
            },
        )

        fun queueUnavailable(reason: QueueUnavailableReason?, playerName: String?): UiText = when (reason) {
            QueueUnavailableReason.UAPP_NO_FILE_ACCESS -> Res(R.string.queue_uapp_no_access)
            QueueUnavailableReason.UAPP_OUT_OF_SYNC -> Res(R.string.queue_uapp_out_of_sync)
            QueueUnavailableReason.EMPTY -> Res(R.string.queue_empty)
            QueueUnavailableReason.NOT_PUBLISHED ->
                playerName?.let { Res(R.string.queue_not_published_by, it) } ?: Res(R.string.queue_not_published)
            QueueUnavailableReason.NO_PLAYER, null -> Res(R.string.queue_no_player)
        }

        fun queueMessage(q: QueueState, playerName: String?): UiText? = when {
            !q.available -> queueUnavailable(q.unavailableReason, playerName)
            q.items.isEmpty() -> queueUnavailable(QueueUnavailableReason.EMPTY, playerName)
            else -> null
        }

        /** Mensaje breve para un error de comando; null = no molestar (ya se refleja en otro sitio). */
        fun commandError(e: CommandError?): UiText? = when (e) {
            null -> null
            CommandError.NO_PLAYER -> Res(R.string.error_no_player)
            CommandError.NOT_SUPPORTED -> Res(R.string.error_not_supported)
            CommandError.QUEUE_UNAVAILABLE -> Res(R.string.error_queue_unavailable)
            CommandError.UNKNOWN_QUEUE_ITEM -> Res(R.string.error_unknown_queue_item)
            CommandError.JUMP_FAILED -> Res(R.string.error_jump_failed)
            CommandError.JUMP_CANCELLED -> Res(R.string.error_jump_cancelled)
            CommandError.VOLUME_UNAVAILABLE -> Res(R.string.volume_unavailable)
            CommandError.VOLUME_ABOVE_CAP -> Res(R.string.volume_cap_reached)
            CommandError.DAC_DISCONNECTED -> Res(R.string.dac_disconnected)
            CommandError.INVALID_ARGUMENT, CommandError.INTERNAL -> Res(R.string.error_host)
            CommandError.RATE_LIMITED -> null
        }

        fun status(s: PlaybackStatus): UiText? = when (s) {
            PlaybackStatus.PAUSED -> Res(R.string.status_paused)
            PlaybackStatus.BUFFERING -> Res(R.string.status_buffering)
            PlaybackStatus.STOPPED -> Res(R.string.status_stopped)
            PlaybackStatus.PLAYING, PlaybackStatus.NONE -> null
        }

        fun disconnectReason(r: DisconnectReason): UiText = when (r) {
            DisconnectReason.NoResponse -> Res(R.string.disconnect_no_response)
            is DisconnectReason.ConnectionLost ->
                r.detail?.takeIf { it.isNotBlank() }?.let(::Raw) ?: Res(R.string.disconnect_connection_lost)
            DisconnectReason.HostClosed -> Res(R.string.disconnect_host_closed)
            DisconnectReason.PairingRejected -> Res(R.string.disconnect_pairing_rejected)
            DisconnectReason.CodeExpired -> Res(R.string.disconnect_code_expired)
            DisconnectReason.TooManyCodes -> Res(R.string.disconnect_too_many_codes)
            DisconnectReason.PairingFailed -> Res(R.string.disconnect_pairing_failed)
            DisconnectReason.Unpaired -> Res(R.string.disconnect_unpaired)
            DisconnectReason.RateLimited -> Res(R.string.disconnect_rate_limited)
            DisconnectReason.VersionMismatch -> Res(R.string.disconnect_version_mismatch)
            is DisconnectReason.Rejected -> Res(R.string.disconnect_rejected, r.code)
            is DisconnectReason.ConnectFailed -> Res(R.string.disconnect_connect_failed, r.target)
            is DisconnectReason.InvalidAddress -> Res(R.string.disconnect_invalid_address, r.target)
        }
    }
}

private fun resolveArgs(args: List<Any>, res: Resources): Array<Any> =
    Array(args.size) { i -> args[i].let { if (it is UiText) it.resolve(res) else it } }

/** Resuelve el texto en composición (se recompone si cambia la configuración/idioma). */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String = resolve(LocalResources.current)
