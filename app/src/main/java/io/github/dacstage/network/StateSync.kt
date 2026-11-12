package io.github.dacstage.network

import io.github.dacstage.model.HostState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Snapshot/parches de [HostState]. Un parche contiene solo las claves de primer nivel que cambiaron, con
 * su valor completo (o null explícito si el campo pasó a null). La TV fusiona y decodifica.
 */
object StateCodec {
    fun encode(state: HostState): JsonObject =
        ProtocolJson.encodeToJsonElement(HostState.serializer(), state).jsonObject

    fun decode(obj: JsonObject): HostState = ProtocolJson.decodeFromJsonElement(HostState.serializer(), obj)

    fun diff(old: JsonObject, new: JsonObject): JsonObject {
        val changes = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for ((k, v) in new) if (old[k] != v) changes[k] = v
        for (k in old.keys) if (k !in new) changes[k] = JsonNull
        return JsonObject(changes)
    }

    fun merge(base: JsonObject, changes: JsonObject): JsonObject = JsonObject(base + changes)
}

/**
 * Lado TV: reconstruye el estado a partir de snapshot + parches y detecta huecos de seq.
 * No es thread-safe (lo usa solo el hilo lector).
 */
class StateAssembler {
    private var current: JsonObject? = null
    private var lastSeq = -1L

    /** [state] nuevo (o null si no se pudo decodificar); [needsRefresh] = pedir snapshot completo. */
    data class Result(val state: HostState?, val needsRefresh: Boolean)

    fun onSnapshot(seq: Long, state: JsonObject): Result {
        current = state
        lastSeq = seq
        return decodeCurrent(gap = false)
    }

    fun onPatch(seq: Long, changes: JsonObject): Result {
        val base = current ?: return Result(null, needsRefresh = true)
        val gap = seq != lastSeq + 1
        lastSeq = seq
        current = StateCodec.merge(base, changes)
        // Con hueco se aplica igualmente (mejor que quedarse quieto) y se pide snapshot.
        return decodeCurrent(gap)
    }

    fun reset() {
        current = null
        lastSeq = -1
    }

    private fun decodeCurrent(gap: Boolean): Result {
        val obj = current ?: return Result(null, true)
        return try {
            Result(StateCodec.decode(obj), gap)
        } catch (_: IllegalArgumentException) {
            Result(null, true)
        }
    }
}
