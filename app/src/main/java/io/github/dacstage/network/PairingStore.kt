package io.github.dacstage.network

import android.content.Context
import kotlinx.serialization.Serializable

/** Persistencia del host: id propio + TVs emparejadas con su token. */
interface PairingStore {
    val hostId: String
    fun token(deviceId: String): String?
    fun put(tv: PairedTv, token: String)
    fun remove(deviceId: String)
    fun all(): List<PairedTv>
    /** Tokens de todas las TVs emparejadas (para validar el token de carátulas). */
    fun allTokens(): List<String>
}

/** SharedPreferences privadas del host ("host_pairing"), una entrada JSON por deviceId. */
class SharedPrefsPairingStore(context: Context) : PairingStore {
    private val prefs = context.applicationContext.getSharedPreferences("host_pairing", Context.MODE_PRIVATE)

    @Serializable
    private data class StoredTv(val name: String, val token: String, val pairedAtMs: Long)

    override val hostId: String = synchronized(this) {
        prefs.getString(KEY_HOST_ID, null) ?: AuthCrypto.randomHex(8).also {
            prefs.edit().putString(KEY_HOST_ID, it).apply()
        }
    }

    private fun read(deviceId: String): StoredTv? = prefs.getString(PREFIX + deviceId, null)?.let {
        try {
            ProtocolJson.decodeFromString(StoredTv.serializer(), it)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @Synchronized override fun token(deviceId: String): String? = read(deviceId)?.token

    @Synchronized override fun put(tv: PairedTv, token: String) {
        val json = ProtocolJson.encodeToString(StoredTv.serializer(), StoredTv(tv.deviceName, token, tv.pairedAtMs))
        prefs.edit().putString(PREFIX + tv.deviceId, json).apply()
    }

    @Synchronized override fun remove(deviceId: String) {
        prefs.edit().remove(PREFIX + deviceId).apply()
    }

    @Synchronized override fun all(): List<PairedTv> = prefs.all.keys
        .filter { it.startsWith(PREFIX) }
        .mapNotNull { key ->
            val id = key.removePrefix(PREFIX)
            read(id)?.let { PairedTv(id, it.name, it.pairedAtMs) }
        }
        .sortedBy { it.pairedAtMs }

    @Synchronized override fun allTokens(): List<String> = prefs.all.keys
        .filter { it.startsWith(PREFIX) }
        .mapNotNull { read(it.removePrefix(PREFIX))?.token }

    private companion object {
        const val KEY_HOST_ID = "host_id"
        const val PREFIX = "tv:"
    }
}
