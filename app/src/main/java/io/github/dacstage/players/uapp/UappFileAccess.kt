package io.github.dacstage.players.uapp

import kotlinx.coroutines.flow.StateFlow

/**
 * Acceso OPCIONAL y explícito a los archivos de cola de UAPP
 * (/sdcard/Android/data/com.extreamsd.usbaudioplayerpro/files/UAPP/) mediante la depuración inalámbrica
 * de la propia tablet, usada por la app como cliente ADB local (libadb-android).
 * Sin este acceso la cola de UAPP se oculta (nunca se simula).
 */
interface UappFileAccess {
    val status: StateFlow<UappAccessStatus>

    /**
     * Empareja con el código de 6 dígitos y el puerto que muestra
     * Opciones de desarrollador → Depuración inalámbrica → "Vincular dispositivo con código".
     * [pairingPort] null = descubrirlo por mDNS (_adb-tls-pairing._tcp).
     */
    suspend fun pair(code: String, pairingPort: Int? = null): Result<Unit>

    /** Conecta (puerto descubierto por mDNS _adb-tls-connect._tcp) y verifica que puede leer los archivos. */
    suspend fun connect(): Result<Unit>

    /** Olvida claves y desconecta. */
    fun forget()

    /**
     * Lee un archivo de la carpeta de UAPP ([UAPP_DIR]) por nombre (solo [UAPP_FILES]). Bytes crudos.
     * Requiere status Connected. (Aditivo: por defecto no soportado.)
     */
    suspend fun readFile(name: String): Result<ByteArray> =
        Result.failure(UnsupportedOperationException("readFile"))

    /** mtime/tamaño de esos archivos (los que no existen se omiten). (Aditivo.) */
    suspend fun statFiles(names: List<String>): Result<List<UappFileStat>> =
        Result.failure(UnsupportedOperationException("statFiles"))

    /**
     * Últimos [maxBytes] de un archivo de la lista [UAPP_TAIL_FILES] (el log de UAPP). (Aditivo.)
     * El log contiene URLs con tokens de TIDAL: quien lo lea solo debe extraer datos técnicos y no
     * guardarlo, registrarlo ni enviarlo.
     */
    suspend fun readTail(name: String, maxBytes: Int): Result<ByteArray> =
        Result.failure(UnsupportedOperationException("readTail"))

    companion object {
        const val UAPP_DIR = "/sdcard/Android/data/com.extreamsd.usbaudioplayerpro/files/UAPP"
        const val PLAY_QUEUE_XML = "PlayQueue.xml"
        const val POS_A = "PosA.txt"
        const val POS_B = "PosB.txt"
        const val UAPP_LOG = "UAPP.txt"
        val UAPP_FILES = setOf(PLAY_QUEUE_XML, POS_A, POS_B)
        val UAPP_TAIL_FILES = setOf(UAPP_LOG)
    }
}

/** Metadatos de un archivo de UAPP: [mtimeMs] con precisión de ms si el sistema la da. */
data class UappFileStat(val name: String, val mtimeMs: Long, val sizeBytes: Long)

sealed interface UappAccessStatus {
    /** El usuario no lo ha activado en Ajustes. */
    data object Disabled : UappAccessStatus
    /** Activado pero sin emparejar todavía. */
    data object NotPaired : UappAccessStatus
    /** Emparejado; la depuración inalámbrica está apagada o no se encuentra el puerto (la UI explica cómo activarla). */
    data object WirelessDebuggingOff : UappAccessStatus
    data object Connecting : UappAccessStatus
    /** Conectado y leyendo PlayQueue.xml / PosA.txt. */
    data object Connected : UappAccessStatus
    /** [problem]: motivo conocido (la UI lo traduce); si es null se muestra [message] tal cual. */
    data class Error(val message: String, val problem: UappAccessProblem? = null) : UappAccessStatus
}

/** Fallos del acceso a UAPP que se enseñan al usuario (la UI los traduce; el mensaje queda para el log). */
enum class UappAccessProblem {
    DISABLED,
    BAD_CODE,
    PAIRING_PORT_NOT_FOUND,
    PAIRING_REJECTED,
    PAIRING_FAILED,
    NOT_PAIRED,
    CONNECTION_REFUSED,
    FOLDER_UNREADABLE,
    CONNECTED_FOLDER_UNREADABLE,
    WIRELESS_OFF,
}

/** Excepción con motivo conocido para la UI. */
open class UappAccessException(val problem: UappAccessProblem, message: String) : IllegalStateException(message)

/** Motivo conocido de un fallo (para traducirlo), o null si es un error del sistema/libadb. */
val Throwable.uappProblem: UappAccessProblem? get() = (this as? UappAccessException)?.problem
