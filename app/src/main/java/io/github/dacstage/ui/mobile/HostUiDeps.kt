package io.github.dacstage.ui.mobile

import io.github.dacstage.dac.DacVolumeControl
import io.github.dacstage.host.HostController
import io.github.dacstage.host.HostSettings
import io.github.dacstage.model.ArtRef
import io.github.dacstage.network.HostServerApi
import io.github.dacstage.players.uapp.UappFileAccess
import kotlinx.coroutines.flow.StateFlow

/** Todo lo que necesita la UI del host. Lo construye la integración (servicio del host). */
class HostUiDeps(
    val controller: HostController,
    val dacVolume: DacVolumeControl,
    val server: HostServerApi,
    val settings: HostSettings,
    val uappFiles: UappFileAccess,
    /** false si falta el acceso a notificaciones (necesario para ver las sesiones de HiBy/UAPP). */
    val hasSessionAccess: StateFlow<Boolean>,
    /** Modelo de Coil para una carátula resuelta en el host (URL, archivo, bytes…); null = sin carátula. */
    val artUrlFor: (ArtRef) -> Any?,
    /** Controles remotos por Bluetooth activos (anunciando y aceptando conexiones). */
    val bluetoothActive: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
)
