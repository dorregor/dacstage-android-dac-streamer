package io.github.dacstage.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "DacStageNet"

/** Host: publicación del servicio por NSD (abstracta para poder probar sin Android). */
interface ServiceRegistrar {
    fun register(name: String, port: Int, hostId: String)
    fun unregister()
}

class NsdServiceRegistrar(context: Context) : ServiceRegistrar {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    override fun register(name: String, port: Int, hostId: String) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = name.ifBlank { "DAC Stage" }.take(63)
            serviceType = NSD_SERVICE_TYPE
            setPort(port)
            setAttribute("id", hostId)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) =
                Log.i(TAG, "NSD registrado como '${info.serviceName}' puerto $port").let { }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) =
                Log.w(TAG, "NSD registro falló: $errorCode").let { }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        listener = l
        try {
            nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: RuntimeException) {
            Log.w(TAG, "NSD registerService: $e")
            listener = null
        }
    }

    @Synchronized
    override fun unregister() {
        val l = listener ?: return
        listener = null
        try {
            nsd?.unregisterService(l)
        } catch (_: RuntimeException) {
        }
    }
}

/** Host encontrado por NSD y ya resuelto. */
data class DiscoveredHost(val serviceName: String, val hostId: String?, val address: String, val port: Int)

/** TV: descubrimiento de hosts (abstracto para tests). */
interface HostDiscovery {
    val hosts: StateFlow<List<DiscoveredHost>>
    fun start(scope: CoroutineScope)
    fun stop()
    /** Reinicia la búsqueda (p. ej. tras fallar la conexión a una dirección resuelta antigua). */
    fun refresh()
}

/**
 * NSD para API 30: discoverServices + resolveService. Antes de API 34 solo puede haber una resolución
 * activa a la vez, así que se serializan en una cola.
 */
class NsdHostDiscovery(context: Context) : HostDiscovery {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    override val hosts: StateFlow<List<DiscoveredHost>> = _hosts.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    private var resolveQueue: Channel<NsdServiceInfo>? = null
    private var resolverJob: Job? = null
    private var scope: CoroutineScope? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Synchronized
    override fun start(scope: CoroutineScope) {
        if (listener != null) return
        this.scope = scope
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        resolveQueue = queue
        resolverJob = scope.launch {
            for (info in queue) resolveOne(info)?.let { h ->
                _hosts.update { list -> list.filterNot { it.serviceName == h.serviceName } + h }
            }
        }
        // Algunas TVs filtran multicast sin este lock.
        multicastLock = try {
            app.getSystemService(WifiManager::class.java)?.createMulticastLock("dacstage-nsd")?.apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: RuntimeException) {
            null
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                Log.w(TAG, "NSD discovery falló: $errorCode").let { }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.trimEnd('.') == NSD_SERVICE_TYPE.trimEnd('.')) queue.trySend(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                _hosts.update { list -> list.filterNot { it.serviceName == info.serviceName } }
            }
        }
        listener = l
        try {
            nsd?.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: RuntimeException) {
            Log.w(TAG, "discoverServices: $e")
        }
    }

    @Synchronized
    override fun stop() {
        listener?.let { l ->
            try {
                nsd?.stopServiceDiscovery(l)
            } catch (_: RuntimeException) {
            }
        }
        listener = null
        resolveQueue?.close()
        resolveQueue = null
        resolverJob?.cancel()
        resolverJob = null
        try {
            multicastLock?.release()
        } catch (_: RuntimeException) {
        }
        multicastLock = null
        _hosts.value = emptyList()
    }

    @Synchronized
    override fun refresh() {
        val s = scope ?: return
        if (listener == null) return
        stop()
        start(s)
    }

    private suspend fun resolveOne(info: NsdServiceInfo): DiscoveredHost? {
        repeat(3) { attempt ->
            val r = withTimeoutOrNull(10_000) { resolveOnce(info) }
            when {
                r is ResolveOutcome.Ok -> return r.host
                r is ResolveOutcome.Failed && r.code != NsdManager.FAILURE_ALREADY_ACTIVE -> return null
                else -> delay(500L * (attempt + 1))
            }
        }
        return null
    }

    private sealed interface ResolveOutcome {
        data class Ok(val host: DiscoveredHost) : ResolveOutcome
        data class Failed(val code: Int) : ResolveOutcome
    }

    @Suppress("DEPRECATION") // resolveService/host: únicas APIs disponibles en API 30
    private suspend fun resolveOnce(info: NsdServiceInfo): ResolveOutcome =
        suspendCancellableCoroutine { cont ->
            val l = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(ResolveOutcome.Failed(errorCode))
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val addr = info.host?.hostAddress
                    val id = info.attributes["id"]?.let { String(it, Charsets.UTF_8) }
                    val out = if (addr != null && info.port > 0) {
                        ResolveOutcome.Ok(DiscoveredHost(info.serviceName, id, addr, info.port))
                    } else ResolveOutcome.Failed(-1)
                    if (cont.isActive) cont.resume(out)
                }
            }
            try {
                nsd?.resolveService(info, l) ?: cont.resume(ResolveOutcome.Failed(-1))
            } catch (e: RuntimeException) {
                if (cont.isActive) cont.resume(ResolveOutcome.Failed(-1))
            }
        }
}

/** TV: aviso de cambio de red (abstracto para tests). */
interface NetworkMonitor {
    fun start(onChange: () -> Unit)
    fun stop()
}

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    override fun start(onChange: () -> Unit) {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            override fun onAvailable(network: Network) {
                val prev = current
                current = network
                // La primera llamada es la red actual al registrarse: no es un cambio.
                if (prev != null && prev != network) onChange()
                else if (prev == null && hadLoss) onChange()
            }

            private var hadLoss = false

            override fun onLost(network: Network) {
                if (network == current) {
                    current = null
                    hadLoss = true
                    onChange()
                }
            }
        }
        callback = cb
        try {
            cm?.registerDefaultNetworkCallback(cb)
        } catch (e: RuntimeException) {
            Log.w(TAG, "registerDefaultNetworkCallback: $e")
            callback = null
        }
    }

    @Synchronized
    override fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            cm?.unregisterNetworkCallback(cb)
        } catch (_: RuntimeException) {
        }
    }
}
