package io.github.dacstage.enrich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface HttpResult {
    data class Ok(val body: String) : HttpResult
    data class Status(val code: Int) : HttpResult
    data class Failed(val reason: String) : HttpResult
}

/** Resultado de una búsqueda: encontrado, no existe (se cachea) o error transitorio (no se cachea). */
sealed interface LookupResult<out T> {
    data class Found<T>(val value: T) : LookupResult<T>
    data object NotFound : LookupResult<Nothing>
    data class Error(val reason: String) : LookupResult<Nothing>
}

/** GET de texto (JSON). [minIntervalMs] > 0 espacia las peticiones (p. ej. 1/s para MusicBrainz). */
open class EnrichHttp(private val minIntervalMs: Long = 0) {
    private val gate = Mutex()
    private var lastAt = 0L

    open suspend fun get(url: String): HttpResult = withContext(Dispatchers.IO) {
        if (minIntervalMs > 0) {
            gate.withLock {
                val wait = lastAt + minIntervalMs - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastAt = System.currentTimeMillis()
                fetch(url)
            }
        } else fetch(url)
    }

    private fun fetch(url: String): HttpResult {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return HttpResult.Failed(e.javaClass.simpleName)
        }
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            // Identifica la app (lo piden MusicBrainz y LRCLIB) sin datos del usuario.
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) return HttpResult.Status(code)
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return HttpResult.Failed("respuesta demasiado grande")
                    out.write(buf, 0, n)
                }
            }
            HttpResult.Ok(out.toString("UTF-8"))
        } catch (e: IOException) {
            HttpResult.Failed(e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            HttpResult.Failed(e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** "DacStage/<versión> ( <url del proyecto> )": los servicios abiertos piden poder identificar al cliente. */
        val USER_AGENT: String = buildString {
            append("DacStage/").append(io.github.dacstage.BuildConfig.VERSION_NAME)
            val url = io.github.dacstage.BuildConfig.PROJECT_URL
            append(if (url.isNotBlank()) " ( $url )" else " (Android)")
        }
        private const val TIMEOUT_MS = 8_000
        private const val MAX_BYTES = 2 * 1024 * 1024
    }
}
