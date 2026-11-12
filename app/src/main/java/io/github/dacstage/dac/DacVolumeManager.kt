package io.github.dacstage.dac

import android.content.Context
import android.os.SystemClock
import io.github.dacstage.host.HostSettings
import io.github.dacstage.model.CommandError
import io.github.dacstage.model.CommandOrigin
import io.github.dacstage.model.CommandResult
import io.github.dacstage.model.DacControlAvailability
import io.github.dacstage.model.DacState
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.PlayerId
import io.github.dacstage.players.PlayerAdapter
import io.github.dacstage.players.TransportAction
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Lo que la política necesita de los ajustes (abstraído para poder probarla sin Android). */
interface DacPolicySettings {
    val capDb: StateFlow<Float?>
    fun setCapDb(db: Float?)
    fun hasForceConsent(dacKey: String, player: PlayerId): Boolean
    fun setForceConsent(dacKey: String, player: PlayerId, granted: Boolean)
}

private class HostSettingsPolicy(private val s: HostSettings) : DacPolicySettings {
    override val capDb: StateFlow<Float?> get() = s.capDb
    override fun setCapDb(db: Float?) = s.setCapDb(db)
    override fun hasForceConsent(dacKey: String, player: PlayerId) = s.hasForceConsent(dacKey, player)
    override fun setForceConsent(dacKey: String, player: PlayerId, granted: Boolean) =
        s.setForceConsent(dacKey, player, granted)
}

/**
 * Política de volumen seguro (ver [DacVolumeControl]) sobre un [DacController].
 * Todo lo publicado en [state] es lo LEÍDO del DAC; lo que no se pudo leer queda en null.
 */
class DacVolumeManager(
    private val controller: DacController,
    private val settings: DacPolicySettings,
    private val activePlayer: StateFlow<PlayerAdapter?>,
    private val scope: CoroutineScope,
    /** true si además del reproductor activo suena otra app: entonces nunca se fuerza sin confirmación. */
    private val otherAppSounding: () -> Boolean = { false },
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
) : DacVolumeControl {

    /** Constructor de producción. [context] no se usa: el permiso USB lo gestiona el controlador. */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        context: Context,
        controller: DacController,
        settings: HostSettings,
        activePlayer: StateFlow<PlayerAdapter?>,
        scope: CoroutineScope,
        otherAppSounding: () -> Boolean,
    ) : this(controller, HostSettingsPolicy(settings), activePlayer, scope, otherAppSounding = otherAppSounding)

    private val _state = MutableStateFlow(DacState())
    override val state: StateFlow<DacState> = _state.asStateFlow()

    private val _pending = MutableStateFlow<PendingDacConfirmation?>(null)
    override val pendingConfirmation: StateFlow<PendingDacConfirmation?> = _pending.asStateFlow()

    private val _volumeMode = MutableStateFlow<DacVolumeMode?>(null)
    override val volumeMode: StateFlow<DacVolumeMode?> = _volumeMode.asStateFlow()

    private val opMutex = Mutex()
    @Volatile private var pendingOp: (suspend (Boolean) -> Result<CommandResult>)? = null
    @Volatile private var lastStepAtMs: Long? = null

    private val presence: StateFlow<DacDeviceRef?>? = (controller as? DacDevicePresence)?.attachedDevice

    private val deviceFlow: Flow<DacDeviceRef?> =
        presence ?: controller.info.map { i -> i?.let { DacDeviceRef(it.deviceName, it.vendorId, it.productId) } }

    init {
        scope.launch {
            combine(controller.info, settings.capDb, deviceFlow) { info, cap, dev -> Triple(info, cap, dev) }
                .collect { (info, cap, dev) ->
                    _volumeMode.value = if (dev == null) null else info?.volumeMode
                    _state.update {
                        it.copy(
                            connected = dev != null,
                            deviceName = dev?.name ?: info?.deviceName,
                            minDb = info?.minDb,
                            maxDb = info?.maxDb,
                            stepDb = info?.stepDb,
                            capDb = effectiveCap(info, cap),
                            volumeMode = if (dev == null) null
                            else info?.volumeMode?.let { m -> io.github.dacstage.model.VolumeControlMode.valueOf(m.name) },
                        )
                    }
                }
        }
        scope.launch {
            var first = true
            deviceFlow.map { it?.key }.distinctUntilChanged().collect { key ->
                when {
                    first || key != null -> refresh()
                    else -> markDisconnected()
                }
                first = false
            }
        }
    }

    // --- API ---

    /**
     * Lectura automática: NUNCA fuerza. Si el reproductor tiene la interfaz de control (UAPP la mantiene
     * tomada), quitársela —aunque haya consentimiento— le impide después fijar la frecuencia del DAC
     * (EBUSY en su SET_CUR del Clock Source) y el DAC se queda en la frecuencia anterior. En ese caso se
     * conservan volumen/mute leídos y solo se descarta la frecuencia (podría estar desfasada).
     */
    override suspend fun refresh(): CommandResult {
        connectOrError(reconnect = true)?.let { return it }
        return opMutex.withLock {
            when (val o = runWithoutForce(::readAll)) {
                is Outcome.Done -> o.result
                is Outcome.Error -> { clearReadings(); o.result }
                Outcome.NeedsConfirmation -> {
                    // El DAC está conectado; solo su interfaz de control la tiene el reproductor.
                    _state.update { it.copy(sampleRateHz = null) }
                    setControl(
                        if (forceAllowedNow()) DacControlAvailability.AVAILABLE
                        else DacControlAvailability.NEEDS_HOST_CONFIRMATION,
                    )
                    unavailable()
                }
            }
        }
    }

    override suspend fun step(deltaDb: Int, repeatCount: Int, origin: CommandOrigin): CommandResult {
        if (deltaDb != 1 && deltaDb != -1) return CommandResult.fail(CommandError.INVALID_ARGUMENT, "Solo pasos de ±1 dB")
        connectOrError(reconnect = false)?.let { return it }
        return opMutex.withLock {
            val now = clockMs()
            val last = lastStepAtMs
            if (last != null && now - last < minStepIntervalMs(repeatCount)) {
                return@withLock CommandResult.fail(CommandError.RATE_LIMITED)
            }
            lastStepAtMs = now
            val desc = if (deltaDb > 0) "Subir volumen 1 dB" else "Bajar volumen 1 dB"
            runAction(desc, origin) { force -> stepOp(deltaDb, force) }
        }
    }

    override suspend fun toggleMute(origin: CommandOrigin): CommandResult {
        connectOrError(reconnect = false)?.let { return it }
        return opMutex.withLock {
            val desc = if (_state.value.muted == true) "Quitar silencio" else "Silenciar"
            runAction(desc, origin, ::toggleMuteOp)
        }
    }

    override suspend fun trySetMute(muted: Boolean, playerPaused: Boolean): Boolean {
        if (connectOrError(reconnect = false) != null) return false
        return opMutex.withLock {
            // Silencio interno (saltos). Desde que la conexión USB se cierra tras cada operación, la interfaz
            // de control vuelve a quedar en manos de otro entre operaciones: sin tomarla, el silencio no se
            // aplicaba y se oía el inicio de cada pista saltada. Solo se toma con el reproductor en pausa
            // (comprobado por quien llama) y sin otra app sonando; si no, nunca se fuerza.
            val canTake = playerPaused && !otherAppSounding()
            val o = if (canTake) runWithPolicy(confirmed = true) { force -> setMuteOp(muted, force) }
            else runWithoutForce { force -> setMuteOp(muted, force) }
            o is Outcome.Done && o.result.ok
        }
    }

    override suspend fun setVolumeDb(db: Float): CommandResult {
        if (!db.isFinite()) return CommandResult.fail(CommandError.INVALID_ARGUMENT, "Valor no válido")
        // Rechazo inmediato si ya se sabe que está fuera de rango o por encima del tope.
        precheckAbsolute(db)?.let { return it }
        connectOrError(reconnect = false)?.let { return it }
        return opMutex.withLock {
            runAction("Fijar volumen a ${formatDb(db)}", CommandOrigin.HOST_UI) { force -> setAbsoluteOp(db, force) }
        }
    }

    override fun setCapDb(capDb: Float) {
        if (!capDb.isFinite()) return
        val info = controller.info.value
        val min = info?.minDb
        val max = info?.maxDb
        val cap = if (min != null && max != null) capDb.coerceIn(min, max) else capDb
        settings.setCapDb(cap)
        _state.update { it.copy(capDb = effectiveCap(info, cap)) }
        // Bajar es seguro: si el volumen leído supera el nuevo tope, se baja al tope.
        scope.launch {
            opMutex.withLock { runAction("Bajar volumen al nuevo tope", CommandOrigin.HOST_UI, ::lowerToCapOp) }
        }
    }

    override fun grantForceConsent(player: PlayerId) {
        currentDacKey()?.let { settings.setForceConsent(it, player, true) }
    }

    override fun revokeForceConsent(player: PlayerId) {
        currentDacKey()?.let { settings.setForceConsent(it, player, false) }
    }

    override suspend fun pauseAndApplyPending(): CommandResult {
        val op = pendingOp
        if (op == null || _pending.value == null) {
            return CommandResult.fail(CommandError.INVALID_ARGUMENT, "No hay nada pendiente")
        }
        val player = activePlayer.value
        val wasPlaying = player != null && player.snapshot.value.status.isSounding()
        if (wasPlaying) {
            val r = player.transport(TransportAction.PAUSE)
            if (!r.ok) return r
            val paused = withTimeoutOrNull(PAUSE_TIMEOUT_MS) {
                player.snapshot.first { !it.status.isSounding() }
            }
            if (paused == null) return CommandResult.fail(CommandError.VOLUME_UNAVAILABLE, "El reproductor no se pausó")
        }
        val result = opMutex.withLock {
            clearPending()
            when (val o = runWithPolicy(confirmed = true, op = op)) {
                is Outcome.Done -> o.result
                is Outcome.Error -> o.result
                Outcome.NeedsConfirmation -> unavailable() // no ocurre con confirmed = true
            }
        }
        if (wasPlaying) {
            val resumed = player.transport(TransportAction.PLAY)
            if (!resumed.ok && result.ok) return CommandResult(true, message = "Aplicado; no se pudo reanudar")
        }
        return result
    }

    override fun dismissPending() = clearPending()

    // --- Operaciones (se ejecutan con opMutex tomado; force lo decide runWithPolicy) ---

    private suspend fun readAll(force: Boolean): Result<CommandResult> {
        // Sin volumen manejable (fijo, sin FU, sin escala dB): se sigue leyendo mute y frecuencia.
        val vol = controller.getVolumeDb(force).getOrElse {
            if (it is DacUnsupportedException) null else return Result.failure(it)
        }
        val info = controller.info.value
        val muted = if (info?.hasMute == false) null else controller.getMute(force).getOrNull()
        val rate = controller.readSampleRateHz(force).getOrNull()
        _state.update { it.copy(volumeDb = vol?.finiteOrNull(), muted = muted, sampleRateHz = rate) }
        return Result.success(CommandResult.OK)
    }

    private suspend fun stepOp(deltaDb: Int, force: Boolean): Result<CommandResult> {
        notControllableNow()?.let { return Result.success(it) }
        val current = controller.getVolumeDb(force).getOrElse { return Result.failure(it) }
        publishVolume(current)
        val info = controller.info.value ?: return Result.success(unavailable("Rango del DAC desconocido"))
        val v = dbView(info) ?: return Result.success(notControllable(info.volumeMode))
        if (deltaDb < 0 && current != Float.NEGATIVE_INFINITY && current <= v.minDb + EPS) {
            return Result.success(CommandResult(true, message = "Mínimo"))
        }
        val levels = v.levels
        val target = when {
            current == Float.NEGATIVE_INFINITY -> if (deltaDb > 0) v.minDb else return Result.success(CommandResult.OK)
            // Niveles no uniformes (tabla/lineal) o paso > 1 dB: siguiente nivel real (≤ 1 dB si existe).
            levels != null -> nextLevel(levels, current, deltaDb) ?: return Result.success(
                if (deltaDb < 0) CommandResult(true, message = "Mínimo") else aboveCap("Fuera del rango del DAC"),
            )
            // Lectura por debajo del mínimo efectivo (p. ej. quirk de mínimo): subir lleva al mínimo.
            else -> if (deltaDb > 0) (current + deltaDb).coerceAtLeast(v.minDb) else current + deltaDb
        }
        if (deltaDb < 0) return write(target.coerceAtLeast(v.minDb), force)
        if (target > v.maxDb + EPS) return Result.success(aboveCap("Fuera del rango del DAC"))
        val cap = effectiveCap(info, settings.capDb.value) ?: return Result.success(unavailable("Tope desconocido"))
        if (target > cap + EPS) return Result.success(aboveCap("Tope ${formatDb(cap)}"))
        return write(target, force)
    }

    private suspend fun setAbsoluteOp(db: Float, force: Boolean): Result<CommandResult> {
        notControllableNow()?.let { return Result.success(it) }
        // La lectura previa asegura el rango (y refresca el estado).
        publishVolume(controller.getVolumeDb(force).getOrElse { return Result.failure(it) })
        val info = controller.info.value ?: return Result.success(unavailable("Rango del DAC desconocido"))
        val v = dbView(info) ?: return Result.success(notControllable(info.volumeMode))
        if (db < v.minDb - EPS || db > v.maxDb + EPS) return Result.success(aboveCap("Fuera del rango del DAC"))
        val cap = effectiveCap(info, settings.capDb.value) ?: return Result.success(unavailable("Tope desconocido"))
        val target = snap(db, v).coerceIn(v.minDb, v.maxDb)
        if (db > cap + EPS || target > cap + EPS) return Result.success(aboveCap("Tope ${formatDb(cap)}"))
        return write(target, force)
    }

    private suspend fun lowerToCapOp(force: Boolean): Result<CommandResult> {
        // Sin escala dB no hay tope que aplicar (y no se toca el volumen).
        if (notControllableNow() != null) return Result.success(CommandResult.OK)
        val current = controller.getVolumeDb(force).getOrElse {
            return if (it is DacUnsupportedException) Result.success(CommandResult.OK) else Result.failure(it)
        }
        publishVolume(current)
        val info = controller.info.value ?: return Result.success(CommandResult.OK)
        val cap = effectiveCap(info, settings.capDb.value) ?: return Result.success(CommandResult.OK)
        if (current <= cap + EPS) return Result.success(CommandResult.OK)
        return write(cap, force)
    }

    private suspend fun toggleMuteOp(force: Boolean): Result<CommandResult> {
        if (controller.info.value?.hasMute == false) return Result.success(noMute())
        val current = controller.getMute(force).getOrElse {
            return if (it is DacUnsupportedException) Result.success(noMute()) else Result.failure(it)
        }
        val confirmed = controller.setMute(!current, force).getOrElse {
            return if (it is DacUnsupportedException) Result.success(noMute()) else Result.failure(it)
        }
        _state.update { it.copy(muted = confirmed) }
        return Result.success(CommandResult.OK)
    }

    private suspend fun setMuteOp(muted: Boolean, force: Boolean): Result<CommandResult> {
        if (controller.info.value?.hasMute == false) return Result.success(noMute())
        val confirmed = controller.setMute(muted, force).getOrElse {
            return if (it is DacUnsupportedException) Result.success(noMute()) else Result.failure(it)
        }
        _state.update { it.copy(muted = confirmed) }
        return Result.success(if (confirmed == muted) CommandResult.OK else unavailable())
    }

    private suspend fun write(db: Float, force: Boolean): Result<CommandResult> {
        val confirmed = controller.setVolumeDb(db, force).getOrElse { return Result.failure(it) }
        publishVolume(confirmed)
        return Result.success(CommandResult.OK)
    }

    // --- Política de reclamo ---

    private sealed interface Outcome {
        data class Done(val result: CommandResult) : Outcome
        data class Error(val result: CommandResult) : Outcome
        data object NeedsConfirmation : Outcome
    }

    /** Solo sin forzar. NeedsConfirmation = la interfaz la tiene otro (no se toca). */
    private suspend fun runWithoutForce(op: suspend (Boolean) -> Result<CommandResult>): Outcome {
        val first = op(false)
        first.onSuccess { setControl(DacControlAvailability.AVAILABLE); return Outcome.Done(it) }
        val error = first.exceptionOrNull()!!
        if (error.toDacAccess() == DacAccess.NeedsForce) return Outcome.NeedsConfirmation
        return Outcome.Error(mapFailure(error))
    }

    /** Intenta sin forzar; si hace falta forzar, solo lo hace si está permitido (o [confirmed] por el host). */
    private suspend fun runWithPolicy(confirmed: Boolean, op: suspend (Boolean) -> Result<CommandResult>): Outcome {
        val first = op(false)
        first.onSuccess { setControl(DacControlAvailability.AVAILABLE); return Outcome.Done(it) }
        val error = first.exceptionOrNull()!!
        if (error.toDacAccess() != DacAccess.NeedsForce) return Outcome.Error(mapFailure(error))
        if (!confirmed && !forceAllowedNow()) {
            setControl(DacControlAvailability.NEEDS_HOST_CONFIRMATION)
            return Outcome.NeedsConfirmation
        }
        val second = op(true)
        second.onSuccess { setControl(DacControlAvailability.AVAILABLE); return Outcome.Done(it) }
        return Outcome.Error(mapFailure(second.exceptionOrNull()!!))
    }

    private suspend fun runAction(
        description: String,
        origin: CommandOrigin,
        op: suspend (Boolean) -> Result<CommandResult>,
    ): CommandResult = when (val o = runWithPolicy(confirmed = false, op = op)) {
        is Outcome.Done -> o.result
        is Outcome.Error -> o.result
        Outcome.NeedsConfirmation -> {
            pendingOp = op
            _pending.value = PendingDacConfirmation(
                activePlayer.value?.id, description, origin, playerName = activePlayer.value?.displayName,
            )
            unavailable()
        }
    }

    /**
     * Forzar está permitido si el reproductor activo no está sonando (o no hay reproductor activo),
     * o si hay consentimiento guardado para este DAC + reproductor.
     */
    private fun forceAllowedNow(): Boolean {
        // Otra app reproduciendo hacia el DAC: no hay par DAC+reproductor que consentir → nunca forzar.
        if (otherAppSounding()) return false
        val player = activePlayer.value ?: return true
        if (!player.snapshot.value.status.isSounding()) return true
        val key = currentDacKey() ?: return false
        return settings.hasForceConsent(key, player.id)
    }

    private fun mapFailure(t: Throwable): CommandResult = when {
        t is DacClaimException -> {
            setControl(DacControlAvailability.BUSY)
            CommandResult.fail(CommandError.VOLUME_UNAVAILABLE, "No se pudo reclamar el DAC")
        }
        t is DacUnsupportedException -> CommandResult.fail(CommandError.NOT_SUPPORTED, t.message)
        t is IllegalArgumentException -> aboveCap("Fuera del rango del DAC")
        else -> when (val a = t.toDacAccess()) {
            DacAccess.Disconnected -> { markDisconnected(); CommandResult.fail(CommandError.DAC_DISCONNECTED) }
            DacAccess.NoPermission -> {
                setControl(DacControlAvailability.NO_PERMISSION)
                CommandResult.fail(CommandError.VOLUME_UNAVAILABLE, "Falta permiso USB")
            }
            is DacAccess.Failed -> CommandResult.fail(CommandError.VOLUME_UNAVAILABLE, a.reason)
            DacAccess.InUseByAndroid -> {
                // Sin lecturas: el DAC no se puede leer sin quitarle la tarjeta a Android.
                _state.update { it.copy(volumeDb = null, muted = null, sampleRateHz = null) }
                setControl(DacControlAvailability.ANDROID_AUDIO)
                unavailable("El DAC lo usa el audio de Android")
            }
            DacAccess.NeedsForce, DacAccess.Ok -> unavailable()
        }
    }

    /** Conecta si hace falta. Devuelve un error si no se puede seguir. */
    private suspend fun connectOrError(reconnect: Boolean): CommandResult? {
        if (!reconnect && controller.info.value != null) return null
        // Sin permiso: solo refresh() (host) vuelve a pedirlo, no cada pulsación de la TV.
        if (!reconnect && _state.value.control == DacControlAvailability.NO_PERMISSION) {
            return CommandResult.fail(CommandError.VOLUME_UNAVAILABLE, "Falta permiso USB")
        }
        return when (val a = controller.connect()) {
            // NeedsForce lo resuelve la política en la operación; InUseByAndroid lo publica la operación.
            DacAccess.Ok, DacAccess.NeedsForce, DacAccess.InUseByAndroid -> null
            DacAccess.Disconnected -> { markDisconnected(); CommandResult.fail(CommandError.DAC_DISCONNECTED) }
            DacAccess.NoPermission -> {
                setControl(DacControlAvailability.NO_PERMISSION)
                CommandResult.fail(CommandError.VOLUME_UNAVAILABLE, "Falta permiso USB")
            }
            is DacAccess.Failed -> CommandResult.fail(CommandError.VOLUME_UNAVAILABLE, a.reason)
        }
    }

    /** Si ya se sabe que el volumen no se maneja desde la app, el resultado a devolver (sin tocar el DAC). */
    private fun notControllableNow(): CommandResult? {
        val info = controller.info.value ?: return null
        return if (info.volumeMode == DacVolumeMode.HARDWARE_DB) null else notControllable(info.volumeMode)
    }

    private fun precheckAbsolute(db: Float): CommandResult? {
        val info = controller.info.value ?: return null
        val v = dbView(info) ?: return notControllable(info.volumeMode)
        if (db < v.minDb - EPS || db > v.maxDb + EPS) return aboveCap("Fuera del rango del DAC")
        val cap = effectiveCap(info, settings.capDb.value) ?: return null
        return if (db > cap + EPS) aboveCap("Tope ${formatDb(cap)}") else null
    }

    // --- Estado ---

    private fun currentDacKey(): String? =
        presence?.value?.key ?: controller.info.value?.let { dacKey(it.vendorId, it.productId) }

    private fun publishVolume(db: Float) = _state.update { it.copy(volumeDb = db.finiteOrNull()) }

    private fun setControl(c: DacControlAvailability) = _state.update { it.copy(control = c) }

    private fun clearReadings() = _state.update { it.copy(volumeDb = null, muted = null, sampleRateHz = null) }

    private fun clearPending() {
        pendingOp = null
        _pending.value = null
    }

    private fun markDisconnected() {
        clearPending()
        _state.update {
            it.copy(
                connected = false, volumeDb = null, muted = null, sampleRateHz = null,
                control = DacControlAvailability.DISCONNECTED,
            )
        }
    }

    companion object {
        /** Tasa de pasos aceptados: arranca en ~4/s al mantener y sube gradualmente hasta ~12/s. */
        const val BASE_STEPS_PER_SEC = 4.0
        const val MAX_STEPS_PER_SEC = 12.0
        const val ACCEL_PER_REPEAT = 0.5
        const val PAUSE_TIMEOUT_MS = 2_000L
        private const val EPS = 1f / 512f

        /**
         * Intervalo mínimo desde el último paso aceptado. Pulsación nueva (repeatCount = 0): solo el tope
         * global (~12/s). Mantenida: 4/s + 0,5/s por repetición, hasta 12/s.
         */
        fun minStepIntervalMs(repeatCount: Int): Long {
            val rate = if (repeatCount <= 0) MAX_STEPS_PER_SEC
            else min(MAX_STEPS_PER_SEC, BASE_STEPS_PER_SEC + repeatCount * ACCEL_PER_REPEAT)
            return ceil(1000.0 / rate).toLong()
        }

        /**
         * Tope efectivo: el configurado o maxDb − 10 dB; nunca por encima de maxDb. null si el DAC no
         * tiene escala dB fiable (no hay tope que se pueda garantizar: el volumen no se maneja).
         */
        fun effectiveCap(info: DacInfo?, capSetting: Float?): Float? {
            if (info != null && (info.volumeMode != DacVolumeMode.HARDWARE_DB || info.maxDb == null)) return null
            val maxDb = info?.maxDb
            val cap = capSetting ?: maxDb?.let { it - HostSettings.DEFAULT_CAP_BELOW_MAX_DB } ?: return null
            return if (maxDb != null) min(cap, maxDb) else cap
        }

        /**
         * Siguiente nivel real desde [current] en la dirección de [delta] (niveles ascendentes): el más
         * lejano dentro de 1 dB; si no hay ninguno a ≤ 1 dB, el inmediato. null si no hay más niveles.
         */
        fun nextLevel(levels: List<Float>, current: Float, delta: Int): Float? =
            if (delta > 0) {
                val above = levels.filter { it > current + EPS }
                above.lastOrNull { it <= current + 1f + EPS } ?: above.firstOrNull()
            } else {
                val below = levels.filter { it < current - EPS }
                below.firstOrNull { it >= current - 1f - EPS } ?: below.lastOrNull()
            }

        /** Vista dB utilizable de [info]; null si el volumen no se maneja desde la app. */
        private class DbView(val minDb: Float, val maxDb: Float, val stepDb: Float?, val levels: List<Float>?)

        private fun dbView(info: DacInfo): DbView? {
            if (info.volumeMode != DacVolumeMode.HARDWARE_DB) return null
            val min = info.minDb ?: return null
            val max = info.maxDb ?: return null
            return DbView(min, max, info.stepDb, info.levelsDb)
        }

        /** Valor alcanzable para un volumen absoluto: nivel ≤ [db] si hay niveles; si no, paso uniforme. */
        private fun snap(db: Float, v: DbView): Float {
            v.levels?.let { levels -> return levels.lastOrNull { it <= db + EPS } ?: v.minDb }
            val step = v.stepDb
            if (step == null || step <= 0f) return db
            return v.minDb + ((db - v.minDb) / step).roundToInt() * step
        }

        private fun notControllable(mode: DacVolumeMode) =
            CommandResult.fail(CommandError.NOT_SUPPORTED, volumeModeMessage(mode))

        private fun PlaybackStatus.isSounding() = this == PlaybackStatus.PLAYING || this == PlaybackStatus.BUFFERING

        private fun Float.finiteOrNull(): Float? = takeIf { it.isFinite() }

        private fun formatDb(db: Float): String =
            if (db == db.roundToInt().toFloat()) "${db.roundToInt()} dB" else "%.1f dB".format(db)

        private fun unavailable(msg: String = "Volumen no disponible ahora") =
            CommandResult.fail(CommandError.VOLUME_UNAVAILABLE, msg)

        private fun aboveCap(msg: String) = CommandResult.fail(CommandError.VOLUME_ABOVE_CAP, msg)

        private fun noMute() = CommandResult.fail(CommandError.NOT_SUPPORTED, "El DAC no tiene mute")
    }
}
