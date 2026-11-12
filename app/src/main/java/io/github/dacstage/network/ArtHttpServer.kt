package io.github.dacstage.network

import android.util.Log
import io.github.dacstage.artwork.ArtworkResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Servidor HTTP mínimo de carátulas del host. Solo GET, una petición por conexión, sin listado.
 *
 * Rutas aceptadas (id = SHA-256 hex de los bytes, token = artToken de [WireMessage.AuthOk]):
 *  - `GET /art/<id>?t=<token>`
 *  - `GET /t/<token>/art/<id>` (forma que usa [TvHostClient.artBaseUrl] + ArtRef.hostPath)
 * 403 si el token no es válido, 404 si la ruta o la carátula no existen, 405 si no es GET.
 * Sin cifrado (HTTP en claro dentro de la LAN), igual que el protocolo de estado.
 */
class ArtHttpServer(
    private val resolver: ArtworkResolver,
    private val isValidToken: (String) -> Boolean,
    private val ports: List<Int> = portCandidates(ART_PORT),
    private val maxConcurrent: Int = 8,
) {
    @Volatile private var server: ServerSocket? = null
    private var job: Job? = null
    private val active = AtomicInteger(0)

    val port: Int? get() = server?.takeIf { !it.isClosed }?.localPort

    /** Abre el puerto (el primero libre de [ports]) y atiende en [scope]. Devuelve el puerto real. */
    @Synchronized
    fun start(scope: CoroutineScope): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val ss = bindFirstFree(ports)
        server = ss
        job = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val s = try {
                        ss.accept()
                    } catch (e: IOException) {
                        if (ss.isClosed) break else continue
                    }
                    if (active.incrementAndGet() > maxConcurrent) {
                        active.decrementAndGet()
                        closeQuietly(s)
                        continue
                    }
                    launch {
                        try {
                            handle(s)
                        } finally {
                            active.decrementAndGet()
                            closeQuietly(s)
                        }
                    }
                }
            } finally {
                closeQuietly(ss)
            }
        }
        return ss.localPort
    }

    @Synchronized
    fun stop() {
        server?.let { closeQuietly(it) }
        server = null
        job?.cancel()
        job = null
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 5_000
            val head = readHead(s.getInputStream()) ?: return
            val out = BufferedOutputStream(s.getOutputStream())
            val requestLine = head.lineSequence().firstOrNull().orEmpty()
            val parts = requestLine.split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return respond(out, 400, "Bad Request")
            if (parts[0] != "GET") return respond(out, 405, "Method Not Allowed", extra = "Allow: GET\r\n")
            val (id, token) = parseTarget(parts[1]) ?: return respond(out, 404, "Not Found")
            if (!isValidToken(token)) return respond(out, 403, "Forbidden")
            val etag = "\"$id\""
            val ifNoneMatch = head.lineSequence().drop(1)
                .firstOrNull { it.startsWith("if-none-match:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            val found = resolver.bytesFor(id) ?: return respond(out, 404, "Not Found")
            val cacheHeaders = "Cache-Control: max-age=31536000, immutable\r\nETag: $etag\r\n"
            if (ifNoneMatch == etag) return respond(out, 304, "Not Modified", extra = cacheHeaders)
            val (bytes, mime) = found
            respond(out, 200, "OK", body = bytes, contentType = mime, extra = cacheHeaders)
        } catch (e: IOException) {
            Log.d(TAG, "art http: $e")
        }
    }

    /** Lee la cabecera hasta la línea vacía (máx. 8 KB). */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArray(MAX_HEAD)
        var n = 0
        while (n < MAX_HEAD) {
            val r = input.read(buf, n, MAX_HEAD - n)
            if (r < 0) return null
            n += r
            val text = String(buf, 0, n, Charsets.ISO_8859_1)
            if (text.contains("\r\n\r\n") || text.contains("\n\n")) return text
        }
        return null
    }

    private fun respond(
        out: OutputStream,
        code: Int,
        reason: String,
        body: ByteArray? = null,
        contentType: String? = null,
        extra: String = "",
    ) {
        val sb = StringBuilder()
            .append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            .append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
            .append("Connection: close\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append(extra).append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (body != null) out.write(body)
        out.flush()
    }

    companion object {
        private const val TAG = "DacStageArtHttp"
        private const val MAX_HEAD = 8 * 1024
        private val QUERY_FORM = Regex("^/art/([0-9a-f]{64})\\?t=([0-9a-f]{16,128})$")
        private val PATH_FORM = Regex("^/t/([0-9a-f]{16,128})/art/([0-9a-f]{64})$")

        /** (id, token) o null si la ruta no es exactamente una de las dos formas admitidas. */
        fun parseTarget(target: String): Pair<String, String>? {
            QUERY_FORM.matchEntire(target)?.let { return it.groupValues[1] to it.groupValues[2] }
            PATH_FORM.matchEntire(target)?.let { return it.groupValues[2] to it.groupValues[1] }
            return null
        }
    }
}

/** Puerto preferido y alternativas (de 2 en 2 para no pisar el otro servidor), y 0 = efímero. */
fun portCandidates(base: Int): List<Int> = (0 until 10).map { base + 2 * it } + 0

internal fun bindFirstFree(ports: List<Int>): ServerSocket {
    var last: IOException? = null
    for (p in ports) {
        val ss = ServerSocket()
        try {
            // En Windows (tests JVM) SO_REUSEADDR permitiría compartir un puerto ocupado.
            ss.reuseAddress = !IS_WINDOWS
            ss.bind(InetSocketAddress(p))
            return ss
        } catch (e: IOException) {
            closeQuietly(ss)
            last = e
        }
    }
    throw last ?: IOException("sin puertos")
}

private val IS_WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows")

internal fun closeQuietly(c: java.io.Closeable?) {
    try {
        c?.close()
    } catch (_: IOException) {
    }
}
