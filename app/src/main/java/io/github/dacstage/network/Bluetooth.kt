package io.github.dacstage.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/**
 * Conexión directa por Bluetooth entre el host y un control remoto, sin pasar por el router (para redes
 * que aíslan a los equipos Wi‑Fi entre sí). El host anuncia por BLE el servicio DAC Stage con el PSM de un
 * canal L2CAP; el cliente lo busca, abre el canal y habla el mismo protocolo que por TCP (emparejado con
 * código en el host, HMAC, estado y comandos). El canal es "insecure" a nivel Bluetooth (sin vincular los
 * equipos en Ajustes): la autenticación es la del protocolo, igual que en la red local.
 */
internal object DacStageBluetooth {
    val SERVICE_UUID: UUID = UUID.fromString("6b9f4a62-2f0e-4d4c-9f6c-3d5a1c0d5ac1")
    private const val DATA_VERSION: Byte = 1

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun hostPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else emptyArray()

    fun clientPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun granted(context: Context, perms: Array<String>): Boolean =
        perms.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun serviceData(psm: Int): ByteArray = byteArrayOf(DATA_VERSION, (psm shr 8).toByte(), psm.toByte())

    /** PSM anunciado, o null si los datos no son de esta versión. */
    fun psmFrom(data: ByteArray?): Int? {
        if (data == null || data.size < 3 || data[0] != DATA_VERSION) return null
        val psm = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        return psm.takeIf { it > 0 }
    }
}

/**
 * Lado host: canal L2CAP en escucha + anuncio BLE mientras esté activo. Si el Bluetooth está apagado o
 * faltan permisos, reintenta cada pocos segundos (empieza solo al encenderlo o al dar el permiso).
 */
@SuppressLint("MissingPermission")
internal class BluetoothHostServer(
    private val context: Context,
    private val onLink: (WireLink) -> Boolean,
) {
    private var job: Job? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    private var advertiseCallback: AdvertiseCallback? = null

    private val _active = MutableStateFlow(false)
    /** true mientras se anuncia y se aceptan conexiones. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val adapter = DacStageBluetooth.adapter(context)
                if (adapter == null || !adapter.isEnabled ||
                    !DacStageBluetooth.granted(context, DacStageBluetooth.hostPermissions())
                ) {
                    delay(RETRY_MS)
                    continue
                }
                val ss = try {
                    adapter.listenUsingInsecureL2capChannel()
                } catch (e: Exception) {
                    Log.w(TAG, "Bluetooth: no se pudo abrir el canal: $e")
                    delay(RETRY_MS)
                    continue
                }
                serverSocket = ss
                val advertising = startAdvertising(adapter, ss.psm)
                _active.value = advertising
                Log.i(TAG, "Bluetooth: esperando controles remotos (PSM ${ss.psm}, anuncio=$advertising)")
                try {
                    while (isActive) {
                        val s: BluetoothSocket = ss.accept()
                        if (!onLink(BluetoothLink(s))) closeQuietly(s)
                    }
                } catch (e: IOException) {
                    // Canal cerrado (parada o Bluetooth apagado): se vuelve a intentar.
                } finally {
                    stopAdvertising(adapter)
                    _active.value = false
                    closeQuietly(ss)
                    serverSocket = null
                }
                delay(2_000)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        closeQuietly(serverSocket)
        serverSocket = null
        DacStageBluetooth.adapter(context)?.let { stopAdvertising(it) }
        _active.value = false
    }

    private fun startAdvertising(adapter: BluetoothAdapter, psm: Int): Boolean {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return false
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val uuid = ParcelUuid(DacStageBluetooth.SERVICE_UUID)
        val data = AdvertiseData.Builder().addServiceUuid(uuid).setIncludeDeviceName(false).build()
        val response = AdvertiseData.Builder().addServiceData(uuid, DacStageBluetooth.serviceData(psm)).build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "Bluetooth: no se pudo anunciar ($errorCode)")
            }
        }
        return try {
            advertiser.startAdvertising(settings, data, response, cb)
            synchronized(this) { advertiseCallback = cb }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth: anuncio rechazado: $e")
            false
        }
    }

    private fun stopAdvertising(adapter: BluetoothAdapter) {
        val cb = synchronized(this) { advertiseCallback.also { advertiseCallback = null } } ?: return
        try {
            adapter.bluetoothLeAdvertiser?.stopAdvertising(cb)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "DacStageNet"
        const val RETRY_MS = 5_000L
    }
}

/** Un host DAC Stage visto por Bluetooth. */
internal data class BluetoothHost(val device: BluetoothDevice, val psm: Int, val name: String, val seenAtMs: Long)

/**
 * Lado cliente: búsqueda BLE de hosts DAC Stage. Una sola búsqueda larga mientras [setSearching] (Android
 * limita cuántas veces por minuto se puede empezar a buscar); se pausa mientras hay conexión.
 */
@SuppressLint("MissingPermission")
internal class BluetoothHostFinder(private val context: Context) {
    private val _hosts = MutableStateFlow<List<BluetoothHost>>(emptyList())
    val hosts: StateFlow<List<BluetoothHost>> = _hosts.asStateFlow()

    private val _ready = MutableStateFlow(false)
    /** Bluetooth encendido y con permisos. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val searching = MutableStateFlow(true)
    private var job: Job? = null
    private var callback: ScanCallback? = null

    fun setSearching(on: Boolean) {
        searching.value = on
    }

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val adapter = DacStageBluetooth.adapter(context)
                val ok = adapter != null && adapter.isEnabled &&
                    DacStageBluetooth.granted(context, DacStageBluetooth.clientPermissions())
                _ready.value = ok
                val want = ok && searching.value
                if (want && callback == null) startScan(adapter!!)
                if (!want && callback != null) stopScan()
                // Se olvidan los hosts que dejaron de anunciarse.
                val now = SystemClock.elapsedRealtime()
                _hosts.update { list -> list.filter { now - it.seenAtMs < STALE_MS } }
                delay(2_000)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        stopScan()
        _hosts.value = emptyList()
    }

    private fun startScan(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner ?: return
        val uuid = ParcelUuid(DacStageBluetooth.SERVICE_UUID)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onResult)
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Bluetooth: búsqueda fallida ($errorCode)")
                synchronized(this@BluetoothHostFinder) { callback = null }
            }
        }
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(uuid).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cb,
            )
            synchronized(this) { callback = cb }
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth: no se pudo buscar: $e")
        }
    }

    private fun stopScan() {
        val cb = synchronized(this) { callback.also { callback = null } } ?: return
        try {
            DacStageBluetooth.adapter(context)?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
        }
    }

    private fun onResult(r: ScanResult) {
        val psm = DacStageBluetooth.psmFrom(r.scanRecord?.getServiceData(ParcelUuid(DacStageBluetooth.SERVICE_UUID))) ?: return
        val name = try {
            r.device.name
        } catch (_: SecurityException) {
            null
        } ?: r.scanRecord?.deviceName ?: "Bluetooth"
        val h = BluetoothHost(r.device, psm, name, SystemClock.elapsedRealtime())
        _hosts.update { list -> list.filterNot { it.device.address == h.device.address } + h }
    }

    /** Abre el canal L2CAP con [host] (bloqueante; se corta a los [timeoutMs]). */
    fun connect(host: BluetoothHost, timeoutMs: Long): WireLink {
        val socket = host.device.createInsecureL2capChannel(host.psm)
        val guard = Thread({
            try {
                Thread.sleep(timeoutMs)
                socket.close()
            } catch (_: InterruptedException) {
            } catch (_: IOException) {
            }
        }, "bt-connect-timeout").apply { isDaemon = true; start() }
        try {
            socket.connect()
        } finally {
            guard.interrupt()
        }
        return BluetoothLink(socket)
    }

    private companion object {
        const val TAG = "DacStageNet"
        const val STALE_MS = 30_000L
    }
}
