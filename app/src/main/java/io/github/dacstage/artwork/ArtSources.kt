package io.github.dacstage.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/** Tamaño real medido de una imagen codificada. */
data class ImageInfo(val width: Int, val height: Int, val mimeType: String) {
    val area: Long get() = width.toLong() * height
}

/** Descarga/lectura de bytes de una candidata (null si no responde o no está permitida). */
fun interface ArtOpener {
    fun open(url: String): ByteArray?
}

/** Mide sin decodificar píxeles. null si no es una imagen. */
fun interface ImageMeasurer {
    fun measure(bytes: ByteArray): ImageInfo?
}

/** Codifica un bitmap embebido (sin reescalar). */
fun interface BitmapEncoder {
    fun encode(bitmap: Bitmap): ByteArray?
}

/** http/https con HttpURLConnection; content:// y android.resource:// con ContentResolver. */
class AndroidArtOpener(context: Context) : ArtOpener {
    private val resolver = context.applicationContext.contentResolver

    override fun open(url: String): ByteArray? = when (url.substringBefore(':', "").lowercase()) {
        "http", "https" -> HttpArtFetch.get(url)
        "content", "android.resource" -> try {
            resolver.openInputStream(Uri.parse(url))?.use { readCapped(it, HttpArtFetch.MAX_BYTES) }
        } catch (_: SecurityException) {
            null // URI de otro paquete sin permiso concedido
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        else -> null
    }
}

/** GET http(s) sin dependencias: timeouts de 5 s, User-Agent genérico, redirecciones limitadas. */
object HttpArtFetch {
    const val MAX_BYTES = 20 * 1024 * 1024
    private const val TIMEOUT_MS = 5_000
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) DacStage"

    fun get(start: String, timeoutMs: Int = TIMEOUT_MS): ByteArray? {
        var current = start
        repeat(4) {
            val conn = try {
                URL(current).openConnection() as? HttpURLConnection ?: return null
            } catch (_: IOException) {
                return null
            }
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "image/webp,image/jpeg,image/png,image/*;q=0.8")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = URL(URL(current), loc).toString()
                    if (!current.startsWith("http://") && !current.startsWith("https://")) return null
                    return@repeat
                }
                if (code != 200) return null
                if (conn.contentLengthLong > MAX_BYTES) return null
                return conn.inputStream.use { readCapped(it, MAX_BYTES) }
            } catch (_: IOException) {
                return null
            } catch (_: RuntimeException) {
                return null // URL mal formada, SecurityException de red, etc.
            } finally {
                conn.disconnect()
            }
        }
        return null
    }
}

internal fun readCapped(input: InputStream, max: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(32 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray().takeIf { it.isNotEmpty() }
}

/** Medición real con BitmapFactory (inJustDecodeBounds: no reserva píxeles). */
object BitmapFactoryMeasurer : ImageMeasurer {
    override fun measure(bytes: ByteArray): ImageInfo? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return ImageInfo(opts.outWidth, opts.outHeight, opts.outMimeType ?: sniffMime(bytes) ?: OCTET)
    }
}

/** Bitmap embebido → JPEG calidad 92, a su tamaño real. */
object Jpeg92Encoder : BitmapEncoder {
    override fun encode(bitmap: Bitmap): ByteArray? = try {
        if (bitmap.isRecycled) null else compress(bitmap) ?: compress(bitmap.copy(Bitmap.Config.ARGB_8888, false))
    } catch (_: RuntimeException) {
        null
    }

    private fun compress(b: Bitmap?): ByteArray? {
        if (b == null) return null
        val out = ByteArrayOutputStream()
        return if (b.compress(Bitmap.CompressFormat.JPEG, 92, out)) out.toByteArray() else null
    }
}

internal const val OCTET = "application/octet-stream"

/** Tipo por firma de bytes (para servir lo leído de disco). */
fun sniffMime(b: ByteArray): String? {
    fun at(i: Int, s: String) = b.size >= i + s.length && s.indices.all { b[i + it] == s[it].code.toByte() }
    return when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 8 && b[0] == 0x89.toByte() && at(1, "PNG") -> "image/png"
        at(0, "RIFF") && at(8, "WEBP") -> "image/webp"
        at(0, "GIF8") -> "image/gif"
        at(4, "ftypavif") -> "image/avif"
        at(4, "ftyphei") || at(4, "ftypmif1") -> "image/heif"
        else -> null
    }
}

/** URL http(s) alcanzable desde la TV sin pasar por el host (no loopback/LAN/.local). */
fun isPublicHttpUrl(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return false
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".lan")) return false
    val isLiteral = IPV4.matches(host) || host.contains(':')
    if (!isLiteral) return host.contains('.')
    val addr = try {
        InetAddress.getByName(host) // literal: no hace DNS
    } catch (_: Exception) {
        return false
    }
    if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress ||
        addr.isAnyLocalAddress || addr.isMulticastAddress
    ) return false
    val raw = addr.address
    if (addr is Inet6Address && (raw[0].toInt() and 0xfe) == 0xfc) return false // ULA fc00::/7
    if (raw.size == 4 && (raw[0].toInt() and 0xff) == 100 && (raw[1].toInt() and 0xc0) == 64) return false // CGNAT
    return true
}

private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
