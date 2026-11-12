package io.github.dacstage.network

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Persistencia de la TV: identidad propia y token por host. */
interface TvStore {
    val deviceId: String
    val deviceName: String
    fun token(hostId: String): String?
    fun saveToken(hostId: String, token: String)
    fun clearToken(hostId: String)
    /** Olvida todos los emparejados y el último host. */
    fun clearAll()
    var lastHostId: String?
    /** "ip:puerto" del último host autenticado (respaldo si NSD no encuentra nada). */
    var lastAddress: String?
}

/** SharedPreferences privadas de la TV ("tv_link"). */
class SharedPrefsTvStore(context: Context) : TvStore {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("tv_link", Context.MODE_PRIVATE)

    override val deviceId: String = synchronized(this) {
        prefs.getString(KEY_DEVICE_ID, null) ?: AuthCrypto.randomHex(16).also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    override val deviceName: String by lazy {
        val name = try {
            Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: RuntimeException) {
            null
        }
        name?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "TV"
    }

    override fun token(hostId: String): String? = prefs.getString(TOKEN_PREFIX + hostId, null)
    override fun saveToken(hostId: String, token: String) = prefs.edit().putString(TOKEN_PREFIX + hostId, token).apply()
    override fun clearToken(hostId: String) = prefs.edit().remove(TOKEN_PREFIX + hostId).apply()

    override fun clearAll() {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith(TOKEN_PREFIX) }.forEach { e.remove(it) }
        e.remove(KEY_LAST_HOST).remove(KEY_LAST_ADDR).apply()
    }

    override var lastHostId: String?
        get() = prefs.getString(KEY_LAST_HOST, null)
        set(v) = prefs.edit().putString(KEY_LAST_HOST, v).apply()

    override var lastAddress: String?
        get() = prefs.getString(KEY_LAST_ADDR, null)
        set(v) = prefs.edit().putString(KEY_LAST_ADDR, v).apply()

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_LAST_HOST = "last_host_id"
        const val KEY_LAST_ADDR = "last_addr"
        const val TOKEN_PREFIX = "token:"
    }
}
