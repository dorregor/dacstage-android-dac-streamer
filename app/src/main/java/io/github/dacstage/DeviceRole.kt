package io.github.dacstage

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Una sola APK. La TV es siempre visor/mando ([TV]). En un teléfono o tablet el usuario elige la función:
 * [HOST] (el dispositivo con el DAC y el reproductor) o [REMOTE] (control remoto: lo mismo que la TV, sin
 * salvapantallas). La elección se guarda y se puede cambiar después.
 */
enum class DeviceRole { TV, HOST, REMOTE;

    companion object {
        private const val PREFS = "device_role"
        private const val KEY_ROLE = "role"

        fun isTv(context: Context): Boolean {
            val pm = context.packageManager
            val uiMode = context.getSystemService(UiModeManager::class.java)?.currentModeType
            return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || uiMode == Configuration.UI_MODE_TYPE_TELEVISION
        }

        /** null = teléfono/tablet que aún no eligió (hay que preguntar). */
        fun detect(context: Context): DeviceRole? = if (isTv(context)) TV else saved(context)

        fun saved(context: Context): DeviceRole? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ROLE, null)
                ?.let { name -> entries.firstOrNull { it.name == name } }
                ?.takeIf { it != TV }
                ?: legacyHost(context)

        /**
         * Instalaciones anteriores a la elección: si este dispositivo ya tiene TVs emparejadas como host, sigue
         * siendo host sin preguntar (no se corta a las TV al actualizar).
         */
        private fun legacyHost(context: Context): DeviceRole? {
            val paired = context.getSharedPreferences("host_pairing", Context.MODE_PRIVATE).all.keys.any { it.startsWith("tv:") }
            return if (paired) HOST.also { save(context, it) } else null
        }

        fun save(context: Context, role: DeviceRole) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ROLE, role.name).commit()
        }
    }
}
