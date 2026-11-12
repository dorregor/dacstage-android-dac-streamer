package io.github.dacstage.network

import kotlinx.coroutines.flow.StateFlow

data class PairingRequest(val requestId: String, val deviceId: String, val deviceName: String, val address: String)

data class PairedTv(val deviceId: String, val deviceName: String, val pairedAtMs: Long)

/**
 * Lado host del protocolo (implementación: [HostServer], agente de red). Publica por NSD, acepta TVs
 * emparejadas, envía snapshot + parches de [io.github.dacstage.host.HostController.state] y ejecuta
 * comandos con CommandOrigin.TV. Lo arranca/para HostService.
 */
interface HostServerApi {
    val pairingRequests: StateFlow<List<PairingRequest>>
    val pairedTvs: StateFlow<List<PairedTv>>
    /** Nombres de las TVs conectadas y autenticadas ahora mismo. */
    val connectedClients: StateFlow<List<String>>
    /** Puerto TCP real en uso (null si no está escuchando). */
    val listeningPort: StateFlow<Int?>

    fun start()
    fun stop()

    /** El usuario escribió en el host el código que muestra la TV. true si coincide y quedó emparejada. */
    fun confirmPairing(requestId: String, code: String): Boolean
    fun rejectPairing(requestId: String)
    fun unpair(deviceId: String)
}
