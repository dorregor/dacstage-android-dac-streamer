package io.github.dacstage.network

import io.github.dacstage.model.Command
import io.github.dacstage.model.CommandResult
import io.github.dacstage.model.HostState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Puerto TCP por defecto del host (JSON por líneas). El servidor HTTP de carátulas usa [ART_PORT]. */
const val DEFAULT_PORT = 47820
const val ART_PORT = 47821
const val NSD_SERVICE_TYPE = "_dacstage._tcp."
const val PROTOCOL_VERSION = 1

/** Estado de la conexión de la TV con el host. */
sealed interface LinkStatus {
    data object Discovering : LinkStatus
    data class Connecting(val hostLabel: String) : LinkStatus
    /** La TV muestra [code]; el usuario lo escribe en el host para emparejar. */
    data class PairingRequired(val hostLabel: String, val code: String) : LinkStatus
    data class Connected(val hostName: String, val address: String) : LinkStatus
    data class Disconnected(val reason: DisconnectReason?, val retryInMs: Long?) : LinkStatus
}

/** Motivo de desconexión (sin texto: la UI lo traduce al idioma del dispositivo). */
sealed interface DisconnectReason {
    data object NoResponse : DisconnectReason
    /** [detail]: mensaje del sistema (IOException), si lo hay. */
    data class ConnectionLost(val detail: String?) : DisconnectReason
    data object HostClosed : DisconnectReason
    data object PairingRejected : DisconnectReason
    data object CodeExpired : DisconnectReason
    data object TooManyCodes : DisconnectReason
    data object PairingFailed : DisconnectReason
    /** El host ya no reconoce esta TV: hay que emparejar de nuevo. */
    data object Unpaired : DisconnectReason
    data object RateLimited : DisconnectReason
    data object VersionMismatch : DisconnectReason
    /** Rechazo con un código que la TV no conoce. */
    data class Rejected(val code: String) : DisconnectReason
    data class ConnectFailed(val target: String) : DisconnectReason
    data class InvalidAddress(val target: String) : DisconnectReason
}

/** Resultado de un comando enviado desde la TV (con el id devuelto por [HostLink.send]). */
data class CommandAck(val commandId: String, val command: Command, val result: CommandResult)

/**
 * Lado TV: única conexión con el host. Sin servicios en segundo plano: vive con la Activity.
 * Implementación: [TvHostClient] (agente de red). La UI de TV solo depende de esta interfaz.
 */
interface HostLink {
    val status: StateFlow<LinkStatus>

    /** Último estado completo reconstruido (snapshot + parches). null hasta el primer snapshot. */
    val hostState: StateFlow<HostState?>

    val acks: SharedFlow<CommandAck>

    /** URL base del servidor de carátulas del host conectado (para ArtRef.hostPath), con token. */
    val artBaseUrl: StateFlow<String?>

    /** Encola el comando; devuelve su id. Si no hay conexión, se descarta y llega un ack de error. */
    fun send(command: Command): String

    fun start()
    fun stop()

    /** Respaldo si NSD falla: conectar a una IP manual. */
    fun connectManual(host: String, port: Int = DEFAULT_PORT)

    /** Bluetooth listo para buscar hosts (encendido y con permisos); null si este cliente no usa Bluetooth. */
    val bluetoothReady: StateFlow<Boolean>? get() = null

    /** Hosts DAC Stage visibles en la red (para elegir a cuál conectarse). */
    val discoveredHosts: StateFlow<List<DiscoveredHost>> get() = NO_HOSTS

    /** Conecta a [host] y lo deja como preferido. */
    fun connectTo(host: DiscoveredHost) = connectManual(host.address, host.port)

    /** Olvida el emparejado guardado (genera un código nuevo al reconectar). */
    fun forgetPairing()
}

private val NO_HOSTS: StateFlow<List<DiscoveredHost>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
