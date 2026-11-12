package io.github.dacstage.dac

import io.github.dacstage.model.CommandResult
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.PlayerId
import io.github.dacstage.players.PlayerAdapter
import io.github.dacstage.players.PlayerSnapshot
import io.github.dacstage.players.TransportAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** DAC simulado: [busy] = otro proceso tiene la interfaz (claim normal falla). */
class FakeDacController(
    var volumeDb: Float = -30f,
    var muted: Boolean = false,
    var sampleRateHz: Int = 96_000,
    val minDb: Float = -127f,
    val maxDb: Float = 0f,
    var busy: Boolean = false,
    /** La interfaz la tiene el driver de audio de Android: no se toca nunca (ni forzando). */
    var androidAudio: Boolean = false,
    var hasMute: Boolean = true,
    var volumeMode: DacVolumeMode = DacVolumeMode.HARDWARE_DB,
    /** Niveles no uniformes (tabla/lineal), como los publicaría el controlador real. */
    val levelsDb: List<Float>? = null,
) : DacController, DacDevicePresence {

    val volumeWrites = mutableListOf<Pair<Float, Boolean>>()
    val muteWrites = mutableListOf<Pair<Boolean, Boolean>>()
    var forcedClaims = 0
    var connectCalls = 0

    private val ref = DacDeviceRef("SMSL USB AUDIO", 0x152A, 0x85DD)
    override val attachedDevice = MutableStateFlow<DacDeviceRef?>(ref)
    override val info = MutableStateFlow<DacInfo?>(null)

    private fun publishInfo() {
        val db = volumeMode == DacVolumeMode.HARDWARE_DB
        info.value = DacInfo(
            ref.name, ref.vendorId, ref.productId,
            minDb.takeIf { db }, maxDb.takeIf { db }, (if (levelsDb == null) 1f else null).takeIf { db }, hasMute,
            volumeMode = volumeMode, levelsDb = levelsDb.takeIf { db },
        )
    }

    private fun <T> op(force: Boolean, block: () -> T): Result<T> {
        if (attachedDevice.value == null) return Result.failure(DacAccessException(DacAccess.Disconnected))
        if (androidAudio) return Result.failure(DacAccessException(DacAccess.InUseByAndroid))
        if (busy) {
            if (!force) return Result.failure(DacAccessException(DacAccess.NeedsForce))
            forcedClaims++
        }
        publishInfo()
        return runCatching(block)
    }

    override suspend fun connect(): DacAccess {
        connectCalls++
        if (attachedDevice.value == null) { info.value = null; return DacAccess.Disconnected }
        if (androidAudio) return DacAccess.InUseByAndroid
        if (busy) return DacAccess.NeedsForce
        publishInfo()
        return DacAccess.Ok
    }

    override suspend fun getVolumeDb(force: Boolean) = op(force) {
        if (volumeMode != DacVolumeMode.HARDWARE_DB) throw DacVolumeNotControllableException(volumeMode)
        volumeDb
    }

    override suspend fun setVolumeDb(db: Float, force: Boolean) = op(force) {
        if (volumeMode != DacVolumeMode.HARDWARE_DB) throw DacVolumeNotControllableException(volumeMode)
        require(db in minDb..maxDb)
        // Como el controlador real con niveles: nunca más alto que lo pedido.
        val applied = levelsDb?.lastOrNull { it <= db + 1e-3f } ?: db
        volumeWrites += applied to force
        volumeDb = applied
        volumeDb
    }

    override suspend fun getMute(force: Boolean) = op(force) {
        if (!hasMute) throw DacUnsupportedException("sin mute")
        muted
    }

    override suspend fun setMute(muted: Boolean, force: Boolean) = op(force) {
        if (!hasMute) throw DacUnsupportedException("sin mute")
        muteWrites += muted to force
        this.muted = muted
        this.muted
    }

    override suspend fun readSampleRateHz(force: Boolean) = op(force) { sampleRateHz }

    override fun close() = Unit
}

class FakePlayer(override val id: PlayerId = PlayerId.HIBY, status: PlaybackStatus = PlaybackStatus.PLAYING) : PlayerAdapter {
    override val snapshot = MutableStateFlow(PlayerSnapshot(id, status))
    val actions = mutableListOf<TransportAction>()
    /** Si false, PAUSE no surte efecto (reproductor que no responde). */
    var obeysPause = true

    override suspend fun transport(action: TransportAction): CommandResult {
        actions += action
        delay(100) // el reproductor tarda en reflejar el cambio
        when (action) {
            TransportAction.PAUSE -> if (obeysPause) set(PlaybackStatus.PAUSED)
            TransportAction.PLAY -> set(PlaybackStatus.PLAYING)
            else -> Unit
        }
        return CommandResult.OK
    }

    fun set(status: PlaybackStatus) {
        snapshot.value = snapshot.value.copy(status = status)
    }

    override suspend fun jumpTo(queueKey: String) = CommandResult.OK
    override fun cancelJump() = Unit
    override fun close() = Unit
}

class FakePolicySettings(cap: Float? = null) : DacPolicySettings {
    override val capDb = MutableStateFlow(cap)
    val consents = mutableSetOf<String>()
    override fun setCapDb(db: Float?) { capDb.value = db }
    override fun hasForceConsent(dacKey: String, player: PlayerId) = "$dacKey:$player" in consents
    override fun setForceConsent(dacKey: String, player: PlayerId, granted: Boolean) {
        if (granted) consents += "$dacKey:$player" else consents -= "$dacKey:$player"
    }
}

/** Para tipar el StateFlow de reproductor activo en las pruebas. */
fun playerFlow(p: PlayerAdapter?): MutableStateFlow<PlayerAdapter?> = MutableStateFlow(p)
