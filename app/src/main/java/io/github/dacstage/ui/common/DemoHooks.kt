package io.github.dacstage.ui.common

import android.content.Context
import io.github.dacstage.DeviceRole
import io.github.dacstage.network.HostLink
import io.github.dacstage.ui.mobile.HostUiDeps

/**
 * Solo debug: un ContentProvider no exportado de src/debug registra aquí los fakes del modo demo.
 * En release no existe ese código y [factory] queda null.
 */
object DemoHooks {
    /** Extra del intent de lanzamiento (debug): `adb shell am start -n … --es demo <escenario>`. */
    const val EXTRA_DEMO = "demo"
    /** Extra opcional para forzar el rol en debug: "tv" o "host". */
    const val EXTRA_ROLE = "demo_role"

    @Volatile
    var factory: DemoFactory? = null
}

interface DemoFactory {
    fun tvLink(context: Context, scenario: String?): HostLink
    fun hostDeps(context: Context, scenario: String?): HostUiDeps
    fun role(value: String?): DeviceRole? = when (value) {
        "tv" -> DeviceRole.TV
        "host" -> DeviceRole.HOST
        "remote" -> DeviceRole.REMOTE
        else -> null
    }
}
