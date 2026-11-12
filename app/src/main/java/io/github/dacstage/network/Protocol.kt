package io.github.dacstage.network

import io.github.dacstage.model.Command
import io.github.dacstage.model.CommandResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/*
 * Protocolo host <-> TV: TCP, un mensaje JSON UTF-8 por línea ('\n'), discriminador "type".
 *
 * Flujo:
 *  TV → Hello                     host → Challenge(nonce)
 *  con token:  TV → AuthResponse(HMAC(token, nonce))         host → AuthOk + Snapshot
 *  sin token:  TV muestra código → PairRequest(HMAC(código, nonce))  host → PairPending
 *              (usuario escribe el código en el host)        host → PairResult(token)
 *              TV → AuthResponse(HMAC(token, nonce))         host → AuthOk + Snapshot
 *  después: host → Patch(seq, claves cambiadas); TV → Cmd/Ping; host → CmdResult/Pong.
 *
 * HMAC-SHA256 con clave = UTF-8 del secreto (código o token hex) y mensaje = UTF-8 del nonce hex;
 * resultado en hex minúsculas.
 *
 * Limitación conocida: no hay cifrado de transporte. Cualquiera en la misma LAN puede leer el estado
 * y capturar el token durante el emparejado. El HMAC evita que el token viaje en las reconexiones,
 * pero el canal no es confidencial: pensado solo para una red doméstica de confianza.
 */
@Serializable
sealed interface WireMessage {
    // ---- TV → host ----
    @Serializable @SerialName("hello")
    data class Hello(val protocolVersion: Int, val deviceId: String, val deviceName: String) : WireMessage

    @Serializable @SerialName("pair_request")
    data class PairRequest(val deviceId: String, val deviceName: String, val proof: String) : WireMessage

    @Serializable @SerialName("auth")
    data class AuthResponse(val hmac: String) : WireMessage

    @Serializable @SerialName("cmd")
    data class Cmd(val id: String, val command: Command) : WireMessage

    @Serializable @SerialName("ping")
    data object Ping : WireMessage

    // ---- host → TV ----
    @Serializable @SerialName("challenge")
    data class Challenge(val nonce: String, val hostId: String, val hostName: String) : WireMessage

    @Serializable @SerialName("pair_pending")
    data object PairPending : WireMessage

    @Serializable @SerialName("pair_result")
    data class PairResult(val ok: Boolean, val token: String? = null, val reason: String? = null) : WireMessage

    @Serializable @SerialName("auth_ok")
    data class AuthOk(val artToken: String, val artPort: Int) : WireMessage

    @Serializable @SerialName("auth_fail")
    data class AuthFail(val reason: String) : WireMessage

    @Serializable @SerialName("snapshot")
    data class Snapshot(val seq: Long, val state: JsonObject) : WireMessage

    @Serializable @SerialName("patch")
    data class Patch(val seq: Long, val changes: JsonObject) : WireMessage

    @Serializable @SerialName("cmd_result")
    data class CmdResult(val id: String, val result: CommandResult) : WireMessage

    @Serializable @SerialName("pong")
    data object Pong : WireMessage
}

/** Motivos de [WireMessage.AuthFail] / [WireMessage.PairResult]. */
object WireReasons {
    const val PROTOCOL_VERSION = "protocol_version"
    const val RATE_LIMITED = "rate_limited"
    const val UNKNOWN_DEVICE = "unknown_device"
    const val BAD_TOKEN = "bad_token"
    const val UNAUTHENTICATED = "unauthenticated"
    const val UNPAIRED = "unpaired"
    const val BAD_REQUEST = "bad_request"
    const val REJECTED = "rejected"
    const val TIMEOUT = "timeout"
    const val TOO_MANY_ATTEMPTS = "too_many_attempts"
    const val SERVER_FULL = "server_full"
}

/** Json común: estado con todos los campos (defaults y nulls explícitos) para que los parches sean exactos. */
val ProtocolJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

/** Resultado de decodificar una línea entrante. */
sealed interface Decoded {
    data class Ok(val message: WireMessage) : Decoded
    /** Un "cmd" con id legible pero comando desconocido/mal formado: se responde INVALID_ARGUMENT. */
    data class BadCommand(val id: String) : Decoded
    data class Invalid(val reason: String) : Decoded
}

object WireCodec {
    /** Límite de línea que acepta el host (TV → host: mensajes pequeños). */
    const val MAX_CLIENT_LINE_BYTES = 64 * 1024
    /** Límite de línea que acepta la TV (snapshots con colas largas). */
    const val MAX_HOST_LINE_BYTES = 4 * 1024 * 1024
    private const val MAX_ID_CHARS = 64

    fun encode(message: WireMessage): String = ProtocolJson.encodeToString(WireMessage.serializer(), message)

    fun decode(line: String): Decoded {
        return try {
            Decoded.Ok(ProtocolJson.decodeFromString(WireMessage.serializer(), line))
        } catch (e: IllegalArgumentException) { // SerializationException hereda de IllegalArgumentException
            val obj = try {
                ProtocolJson.parseToJsonElement(line).jsonObject
            } catch (_: IllegalArgumentException) {
                return Decoded.Invalid("json")
            }
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (type == "cmd" && id != null && id.isNotEmpty() && id.length <= MAX_ID_CHARS) {
                Decoded.BadCommand(id)
            } else {
                Decoded.Invalid(e.message?.take(120) ?: "schema")
            }
        }
    }
}
