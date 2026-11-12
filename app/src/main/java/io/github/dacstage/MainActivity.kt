package io.github.dacstage

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import io.github.dacstage.host.HostGraph
import io.github.dacstage.host.HostService
import io.github.dacstage.network.HostLink
import io.github.dacstage.network.TvHostClient
import io.github.dacstage.tv.TvViewModel
import io.github.dacstage.ui.common.DemoFactory
import io.github.dacstage.ui.common.DemoHooks
import io.github.dacstage.ui.mobile.HostRoot
import io.github.dacstage.ui.mobile.HostUiDeps
import io.github.dacstage.ui.mobile.RemoteRoot
import io.github.dacstage.ui.mobile.RoleChooser
import io.github.dacstage.ui.tv.TvRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {

    /** TV y control remoto: recibe las teclas multimedia/volumen antes que Compose. */
    private var tvViewModel: TvViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Modo demo SOLO en debug y solo si se pide explícitamente con el extra "demo".
        val demo: DemoFactory? = if (BuildConfig.DEBUG && intent.hasExtra(DemoHooks.EXTRA_DEMO)) DemoHooks.factory else null
        val scenario = intent.getStringExtra(DemoHooks.EXTRA_DEMO)
        val role = demo?.role(intent.getStringExtra(DemoHooks.EXTRA_ROLE)) ?: DeviceRole.detect(this)

        when (role) {
            // Teléfono o tablet sin elegir: se pregunta una vez y se guarda.
            null -> {
                enableEdgeToEdge()
                setContent {
                    RoleChooser { picked ->
                        DeviceRole.save(this, picked)
                        recreate()
                    }
                }
            }
            DeviceRole.REMOTE -> {
                enableEdgeToEdge()
                // Mismo cliente y ViewModel que la TV, sin salvapantallas. Las teclas de volumen del teléfono
                // controlan el DAC del host mientras hay conexión (igual que el mando de la TV).
                val provider: () -> HostLink = when {
                    demo != null -> { { demo.tvLink(applicationContext, scenario) } }
                    else -> { { TvHostClient(applicationContext, CoroutineScope(SupervisorJob() + Dispatchers.Default), useBluetooth = true) } }
                }
                // Bluetooth para llegar al host aunque la red no deje ver a otros equipos Wi‑Fi.
                (io.github.dacstage.network.DacStageBluetooth.clientPermissions().toList() +
                    (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()))
                    .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    .takeIf { it.isNotEmpty() }
                    ?.let { requestPermissions(it.toTypedArray(), 2) }
                val vm = ViewModelProvider(this, TvViewModel.factory(provider, screensaver = false))[TvViewModel::class.java]
                tvViewModel = vm
                remoteMode = true
                if (intent.getBooleanExtra(RemoteKeepAliveService.EXTRA_DISCONNECT, false)) {
                    finishAndRemoveTask()
                    return
                }
                RemoteKeepAliveService.start(this)
                // Notificación multimedia, pantalla de bloqueo, controles del sistema y teclas de volumen.
                RemoteMedia.attach(this, vm)
                setContent {
                    RemoteRoot(vm, onUseAsHost = {
                        RemoteMedia.release()
                        RemoteKeepAliveService.stop(this)
                        vm.link.stop()
                        remoteMode = false
                        DeviceRole.save(this, DeviceRole.HOST)
                        recreate()
                    })
                }
            }
            DeviceRole.TV -> {
                val provider: (() -> HostLink)? = when {
                    demo != null -> { { demo.tvLink(applicationContext, scenario) } }
                    else -> createHostLinkProvider()
                }
                if (provider == null) {
                    setContent { IntegrationPending("TV") }
                    return
                }
                // El enlace vive con el ViewModel (sobrevive a cambios de configuración).
                val vm = ViewModelProvider(this, TvViewModel.factory(provider))[TvViewModel::class.java]
                tvViewModel = vm
                setContent { TvRoot(vm.link, viewModel = vm) }
            }
            DeviceRole.HOST -> {
                enableEdgeToEdge()
                val deps = demo?.hostDeps(applicationContext, scenario) ?: createHostUiDeps()
                setContent {
                    HostRoot(deps, onSwitchToRemote = if (demo != null) null else {
                        {
                            HostService.stop(this)
                            DeviceRole.save(this, DeviceRole.REMOTE)
                            recreate()
                        }
                    })
                }
            }
        }
    }

    /** Control remoto: la conexión sigue con la pantalla apagada mientras la app no se cierre. */
    private var remoteMode = false

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (remoteMode && intent.getBooleanExtra(RemoteKeepAliveService.EXTRA_DISCONNECT, false)) finishAndRemoveTask()
    }

    override fun onDestroy() {
        // Al cerrar de verdad (atrás, "Desconectar" o cambio de función) se suelta el servicio; el ViewModel
        // corta la conexión. En un giro de pantalla no (la actividad vuelve enseguida).
        if (remoteMode && isFinishing) {
            RemoteMedia.release()
            RemoteKeepAliveService.stop(this)
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val vm = tvViewModel
        if (vm != null && vm.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** TV: un único cliente (un socket al host) que vive con el ViewModel. */
    private fun createHostLinkProvider(): (() -> HostLink)? = {
        TvHostClient(applicationContext, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    /** Host: arranca el servicio en primer plano y expone a la UI las mismas instancias que usa. */
    private fun createHostUiDeps(): HostUiDeps {
        // Notificación del servicio y, si está activado, Bluetooth para controles remotos (una sola petición).
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (HostGraph.get(this@MainActivity).settings.bluetoothRemote.value) {
                addAll(io.github.dacstage.network.DacStageBluetooth.hostPermissions())
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        HostService.start(this)
        val g = HostGraph.get(this)
        return HostUiDeps(
            controller = g.core,
            dacVolume = g.dac,
            server = g.server,
            settings = g.settings,
            uappFiles = g.uappFiles,
            hasSessionAccess = g.sessions.hasSessionAccess,
            // En el host: URL pública si existe; si no, los bytes ya verificados (Coil acepta ByteArray).
            artUrlFor = { art -> art.url ?: g.artwork.bytesFor(art.id)?.first },
            bluetoothActive = g.bluetooth.active,
        )
    }
}

@Composable
private fun IntegrationPending(what: String) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        BasicText(stringResource(R.string.integration_pending, what), style = TextStyle(color = Color.White, fontSize = 22.sp))
    }
}
