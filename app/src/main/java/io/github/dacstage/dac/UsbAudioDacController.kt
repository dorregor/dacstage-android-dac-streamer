package io.github.dacstage.dac

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [DacController] sobre UsbManager para DACs USB Audio Class 2.0 y 1.0 (preferente: SMSL 152A:85DD).
 *
 * Cada operación: Mutex + hilo USB dedicado → abre la conexión → claim de la interfaz AudioControl
 * (normal; forzado solo si [force]) → transferencias de control (timeout [TIMEOUT_MS]) → la devuelve
 * (ver [claimed]) y cierra la conexión. Entre operaciones no se retiene nada del DAC.
 * No decide políticas: el forzado lo decide [DacVolumeManager]. La lógica de control (rango, quirks,
 * canales, prueba de volumen fijo, frecuencia) está en [UacDeviceControl].
 */
class UsbAudioDacController(
    context: Context,
    /** true si el reproductor activo usa su propio driver USB (UAPP), no el audio de Android. */
    private val keepKernelDriverDetached: () -> Boolean = { false },
) : DacController, DacDevicePresence {

    private val appContext = context.applicationContext
    private val usbManager: UsbManager = appContext.getSystemService(UsbManager::class.java)
    private val audioManager: AudioManager = appContext.getSystemService(AudioManager::class.java)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dac-usb").apply { isDaemon = true } }
    private val usbDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + usbDispatcher)

    /** Serializa todo acceso a la conexión (solo se toca desde [usbDispatcher]). */
    private val opMutex = Mutex()
    private val connectMutex = Mutex()
    private val permissionInFlight = AtomicBoolean(false)

    private val _info = MutableStateFlow<DacInfo?>(null)
    override val info: StateFlow<DacInfo?> = _info.asStateFlow()

    private val _attached = MutableStateFlow<DacDeviceRef?>(null)
    override val attachedDevice: StateFlow<DacDeviceRef?> = _attached.asStateFlow()

    // Protegido por opMutex.
    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var acInterface: UsbInterface? = null
    /** Estado de control del dispositivo abierto (modelo de volumen cacheado por conexión). */
    private var control: UacDeviceControl? = null
    private var streamFormats: List<UsbStreamFormat> = emptyList()

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val dev = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (dev != null && dev.uacControlVersion() != null) {
                    // Si ya hay uno, se queda el preferido según findDac().
                    _attached.value = (findDac() ?: dev).toRef()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    _attached.value = findDac()?.toRef()
                    scope.launch {
                        opMutex.withLock {
                            if (dev == null || dev.deviceName == device?.deviceName) {
                                closeConnectionLocked()
                                _info.value = null
                            }
                        }
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(appContext, attachReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        _attached.value = findDac()?.toRef()
    }

    override suspend fun connect(): DacAccess {
        val dev = findDac()
        if (dev == null) {
            _attached.value = null
            runLocked { closeConnectionLocked(); _info.value = null }
            return DacAccess.Disconnected
        }
        _attached.value = dev.toRef()
        if (!usbManager.hasPermission(dev)) {
            // Un único diálogo a la vez; las demás llamadas no esperan.
            if (!permissionInFlight.compareAndSet(false, true)) return DacAccess.NoPermission
            val granted = try {
                requestPermission(dev)
            } finally {
                permissionInFlight.set(false)
            }
            if (!granted) return DacAccess.NoPermission
        }
        return connectMutex.withLock { runLocked { openLocked(dev) } }
    }

    override suspend fun getVolumeDb(force: Boolean): Result<Float> = runOp(force) { t, c -> c.readVolumeDb(t) }

    override suspend fun setVolumeDb(db: Float, force: Boolean): Result<Float> {
        if (db.isNaN() || db == Float.POSITIVE_INFINITY) return Result.failure(IllegalArgumentException("dB inválido: $db"))
        return runOp(force) { t, c -> c.setVolumeDb(t, db) }
    }

    override suspend fun getMute(force: Boolean): Result<Boolean> = runOp(force) { t, c -> c.readMute(t) }

    override suspend fun setMute(muted: Boolean, force: Boolean): Result<Boolean> =
        runOp(force) { t, c -> c.setMute(t, muted) }

    override suspend fun readSampleRateHz(force: Boolean): Result<Int> = runOp(force) { t, c -> c.readSampleRateHz(t) }

    override fun close() {
        runCatching { appContext.unregisterReceiver(attachReceiver) }
        scope.launch { opMutex.withLock { closeConnectionLocked() } }.invokeOnCompletion {
            scope.cancel()
            executor.shutdown()
        }
        _info.value = null
    }

    // --- Interno ---

    private suspend fun <T> runLocked(block: () -> T): T =
        opMutex.withLock { withContext(usbDispatcher) { block() } }

    /** Claim → (modelo de volumen si falta) → [block] → devolución (ver [claimed]). */
    private suspend fun <T> runOp(force: Boolean, block: (UacControlTransport, UacDeviceControl) -> T): Result<T> =
        runLocked { claimed(force) { t, c -> ensureModelLocked(t, c, retryUnavailable = false); block(t, c) } }

    /**
     * Toma la interfaz AudioControl, ejecuta [block] y la devuelve.
     *
     * Siempre se devuelve CERRANDO la conexión, nunca con releaseInterface(): ese método de Android vuelve a
     * enganchar el driver de audio del kernel ("allow kernel to reconnect its driver") y, con él puesto, UAPP
     * ya no puede fijar la frecuencia del DAC (EBUSY). Al cerrar, el kernel suelta la interfaz sin enganchar
     * nada y la app que la tenía (UAPP, con su propio driver USB) la retoma sola en su siguiente transferencia.
     *
     * Si la tiene el driver de audio de Android (apps que suenan por el audio del sistema), forzar desmontaría
     * la tarjeta USB: corte y desvío del sonido al altavoz. No se toca ([DacAccess.InUseByAndroid]), salvo que
     * el reproductor activo use su propio driver ([keepKernelDriverDetached]): entonces el driver de Android
     * sobra (quedó enganchado por error) y quitarlo le devuelve el control al reproductor.
     */
    private fun <T> claimed(force: Boolean, block: (UacControlTransport, UacDeviceControl) -> T): Result<T> {
        val ctrl = control ?: return Result.failure(DacAccessException(DacAccess.Disconnected))
        val intf = acInterface ?: return Result.failure(DacAccessException(DacAccess.Disconnected))
        val conn = ensureOpenLocked() ?: return Result.failure(DacAccessException(DacAccess.Disconnected))
        val t0 = android.os.SystemClock.elapsedRealtime()
        var owner = Owner.FREE
        if (!conn.claimInterface(intf, false)) {
            owner = if (androidHasUsbOutput()) Owner.ANDROID else Owner.OTHER_APP
            if (owner == Owner.ANDROID && !keepKernelDriverDetached()) {
                Log.d(TAG, "AC del audio de Android: no se toca")
                closeFdLocked()
                return Result.failure(DacAccessException(DacAccess.InUseByAndroid))
            }
            if (!force) {
                Log.d(TAG, "AC ocupada (${owner.name.lowercase()}, sin forzar): no se toca")
                closeFdLocked()
                return Result.failure(DacAccessException(DacAccess.NeedsForce))
            }
            // Puede cortar el audio del reproductor: solo llega aquí si la política lo permitió.
            if (!conn.claimInterface(intf, true)) {
                closeFdLocked()
                return Result.failure(DacClaimException())
            }
        }
        val transport = UacControlTransport { reqType, req, value, index, buf, len ->
            conn.controlTransfer(reqType, req, value, index, buf, len, TIMEOUT_MS)
        }
        return try {
            Result.success(block(transport, ctrl))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            closeFdLocked()
            Log.d(TAG, "AC tomada ${android.os.SystemClock.elapsedRealtime() - t0} ms (antes: ${owner.name.lowercase()}; devuelta cerrando)")
        }
    }

    private enum class Owner { FREE, OTHER_APP, ANDROID }

    /** Abre la conexión con el DAC ya parseado si está cerrada (se cierra tras cada operación). */
    private fun ensureOpenLocked(): UsbDeviceConnection? {
        connection?.let { return it }
        val dev = device ?: return null
        val conn = usbManager.openDevice(dev) ?: return null
        connection = conn
        return conn
    }

    /** Cierra solo el descriptor (el kernel suelta lo que hubiera tomado) y conserva el modelo del DAC. */
    private fun closeFdLocked() {
        connection?.close()
        connection = null
    }

    /** Android tiene registrada una salida de audio USB (= su driver tiene la tarjeta del DAC). */
    private fun androidHasUsbOutput(): Boolean =
        runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        }.getOrDefault(false)

    private fun openLocked(dev: UsbDevice): DacAccess {
        if (control == null || device?.deviceName != dev.deviceName) {
            closeConnectionLocked()
            val conn = usbManager.openDevice(dev) ?: return DacAccess.Failed("No se pudo abrir el dispositivo USB")
            val raw = conn.rawDescriptors
            val topo = Uac2DescriptorParser.parseControl(raw)
            val intf = topo?.let { t ->
                (0 until dev.interfaceCount).map { dev.getInterface(it) }
                    .firstOrNull { it.id == t.audioControlInterface && it.alternateSetting == 0 }
            }
            if (topo == null || intf == null) {
                conn.close()
                return DacAccess.Failed("El DAC no expone una interfaz de control USB Audio")
            }
            val quirks = DacQuirkTable.forDevice(dev.vendorId, dev.productId, dev.manufacturerName)
            Log.i(
                TAG,
                "DAC ${dacKey(dev.vendorId, dev.productId)} UAC${topo.uacVersion}: FU ${topo.featureUnitId}, " +
                    "volumen canales ${topo.volumeChannels}, mute ${topo.muteChannels}, reloj ${topo.clockEntityId}, " +
                    "quirks ${quirks.applied}",
            )
            streamFormats = Uac2DescriptorParser.parseStreamFormats(raw)
            Log.i(TAG, "formatos de streaming: " + streamFormats.joinToString {
                "if${it.interfaceNumber}/alt${it.alternateSetting} ${it.channels}ch ${it.subslotBytes * 8}/${it.bitResolution}bit"
            })
            device = dev; connection = conn; acInterface = intf
            control = UacDeviceControl(topo, quirks)
        }
        val ready = control?.model?.let { it.mode != DacVolumeMode.UNAVAILABLE } == true
        if (ready) {
            closeFdLocked()
            return DacAccess.Ok
        }
        // Modelo de volumen sin forzar; si otro tiene la interfaz se decidirá en la primera operación permitida.
        return claimed(force = false) { t, c -> ensureModelLocked(t, c, retryUnavailable = true) }
            .fold(onSuccess = { DacAccess.Ok }, onFailure = { it.toDacAccess() })
    }

    /** Decide (una vez por conexión) el modelo de volumen y publica [info]. */
    private fun ensureModelLocked(t: UacControlTransport, c: UacDeviceControl, retryUnavailable: Boolean) {
        val before = c.model
        val m = c.ensureVolumeModel(t, retryUnavailable)
        if (m !== before) {
            Log.i(TAG, "volumen: ${m.mode} rango ${m.raw} prueba ${c.probeResult} ${m.notes}")
        }
        val dev = device ?: return
        if (m !== before || _info.value == null) {
            _info.value = c.toInfo(dev.displayName(), dev.vendorId, dev.productId, streamFormats)
        }
    }

    private fun closeConnectionLocked() {
        connection?.close()
        connection = null; device = null; acInterface = null; control = null; streamFormats = emptyList()
    }

    private suspend fun requestPermission(dev: UsbDevice): Boolean = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val action = "${appContext.packageName}.USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != action) return
                    runCatching { appContext.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            ContextCompat.registerReceiver(appContext, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }
            // Explícito (setPackage) y mutable: UsbManager añade EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(appContext, 0, Intent(action).setPackage(appContext.packageName), flags)
            usbManager.requestPermission(dev, pi)
        }
    } ?: false

    /** DAC USB Audio conectado: SMSL preferente; luego cualquiera UAC2; luego UAC1. */
    private fun findDac(): UsbDevice? {
        val devices = usbManager.deviceList?.values.orEmpty().filter { it.uacControlVersion() != null }
        return devices.firstOrNull { it.vendorId == SMSL_VID && it.productId == SMSL_PID }
            ?: devices.firstOrNull { it.uacControlVersion() == 2 }
            ?: devices.firstOrNull()
    }

    /** 2 = AudioControl UAC2, 1 = UAC1, null = sin AudioControl. */
    private fun UsbDevice.uacControlVersion(): Int? {
        var best: Int? = null
        for (k in 0 until interfaceCount) {
            val i = getInterface(k)
            if (i.interfaceClass != UsbConstants.USB_CLASS_AUDIO || i.interfaceSubclass != Uac2DescriptorParser.SUBCLASS_AUDIOCONTROL) continue
            when (i.interfaceProtocol) {
                Uac2DescriptorParser.PROTOCOL_UAC2 -> return 2
                Uac2DescriptorParser.PROTOCOL_UAC1 -> best = 1
            }
        }
        return best
    }

    private fun UsbDevice.displayName(): String =
        productName?.trim()?.takeIf { it.isNotEmpty() } ?: "DAC USB ${dacKey(vendorId, productId)}"

    private fun UsbDevice.toRef() = DacDeviceRef(displayName(), vendorId, productId)

    companion object {
        private const val TAG = "UsbAudioDac"
        const val SMSL_VID = 0x152A
        const val SMSL_PID = 0x85DD
        const val TIMEOUT_MS = 500
        private const val PERMISSION_TIMEOUT_MS = 60_000L
    }
}
