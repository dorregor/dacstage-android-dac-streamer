package io.github.dacstage.artwork

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import io.github.dacstage.model.ArtRef
import io.github.dacstage.model.ArtSource
import io.github.dacstage.players.ArtCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

/**
 * Cadena de carátulas del host. Para cada candidata (en orden) obtiene los bytes —URL: `url` y luego
 * `fallbackUrls`, quedándose con la primera que responda con una imagen; Embedded: el bitmap— y mide
 * su tamaño REAL. Elige la de mayor área (empate: la anterior en la lista). Nunca reescala: los bytes
 * descargados se guardan tal cual y un bitmap embebido se codifica a JPEG 92 a su tamaño.
 *
 * Resultado: id = SHA-256 hex de los bytes, `hostPath = /art/<id>` siempre y `url` solo si la ganadora
 * es una URL http(s) pública. Caché por (trackKey, candidatas) para no re-resolver la misma pista.
 */
class HostArtworkResolver internal constructor(
    private val scope: CoroutineScope,
    private val store: ArtStore,
    private val opener: ArtOpener,
    private val measurer: ImageMeasurer,
    private val encoder: BitmapEncoder,
) : ArtworkResolver {

    constructor(context: Context, scope: CoroutineScope) : this(
        scope = scope,
        store = ArtStore(File(context.cacheDir, "art")),
        opener = AndroidArtOpener(context),
        measurer = BitmapFactoryMeasurer,
        encoder = Jpeg92Encoder,
    )

    private val lock = Any()
    private val byTrack = object : LinkedHashMap<String, ArtRef>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtRef>?) = size > MAX_TRACKS
    }
    private val inflight = HashMap<String, Deferred<ArtRef?>>()

    private class Chosen(val bytes: ByteArray, val info: ImageInfo, val source: ArtSource, val url: String?)

    override suspend fun resolve(trackKey: String, candidates: List<ArtCandidate>): ArtRef? {
        if (candidates.isEmpty()) return null
        val key = trackKey + "|" + fingerprint(candidates)
        val deferred = synchronized(lock) {
            byTrack[key]?.let { if (store.contains(it.id)) return it else byTrack.remove(key) }
            inflight[key] ?: scope.async(Dispatchers.IO) {
                try {
                    resolveNow(trackKey, candidates).also { r -> synchronized(lock) { if (r != null) byTrack[key] = r } }
                } finally {
                    synchronized(lock) { inflight.remove(key) }
                }
            }.also { inflight[key] = it }
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // si el cancelado es el llamador, propagar
            null // se canceló el scope del resolvedor
        }
    }

    override fun bytesFor(id: String): Pair<ByteArray, String>? {
        if (!ID.matches(id)) return null
        return store.get(id)
    }

    private fun resolveNow(trackKey: String, candidates: List<ArtCandidate>): ArtRef? {
        var best: Chosen? = null
        for (c in candidates) {
            when (c) {
                is ArtCandidate.Url -> for (u in listOf(c.url) + c.fallbackUrls) {
                    val bytes = opener.open(u) ?: continue
                    val info = measurer.measure(bytes) ?: continue
                    if (best == null || info.area > best.info.area) best = Chosen(bytes, info, c.source, u)
                    break // primera que responde en esta candidata
                }
                is ArtCandidate.Embedded -> {
                    val (w, h) = bitmapSize(c.bitmap) ?: continue
                    if (best != null && w.toLong() * h <= best.info.area) continue // no hace falta codificarlo
                    val bytes = encoder.encode(c.bitmap) ?: continue
                    best = Chosen(bytes, ImageInfo(w, h, "image/jpeg"), c.source, null)
                }
            }
        }
        val b = best
        if (b == null) {
            Log.w(TAG, "art $trackKey: ninguna candidata disponible")
            return null
        }
        val id = sha256Hex(b.bytes)
        store.put(id, b.bytes, b.info.mimeType)
        val w = b.info.width
        val h = b.info.height
        Log.i(TAG, "art $trackKey: ${w}x$h ${b.source} ${b.url?.take(200) ?: "embedded"}")
        if (w < MIN_SIDE || h < MIN_SIDE) {
            Log.w(TAG, "art $trackKey: ninguna carátula llega a ${MIN_SIDE}x$MIN_SIDE (mejor ${w}x$h)")
        }
        return ArtRef(
            id = id,
            widthPx = w,
            heightPx = h,
            source = b.source,
            url = b.url?.takeIf(::isPublicHttpUrl),
            hostPath = "/art/$id",
            mimeType = b.info.mimeType,
        )
    }

    private fun bitmapSize(b: Bitmap): Pair<Int, Int>? = try {
        if (b.isRecycled || b.width <= 0 || b.height <= 0) null else b.width to b.height
    } catch (_: RuntimeException) {
        null
    }

    private fun fingerprint(candidates: List<ArtCandidate>): String {
        val s = candidates.joinToString("\n") { c ->
            when (c) {
                is ArtCandidate.Url -> "u:${c.source}:${c.url}|" + c.fallbackUrls.joinToString("|")
                is ArtCandidate.Embedded -> "e:${c.source}:" + (bitmapSize(c.bitmap)?.let { "${it.first}x${it.second}" } ?: "-")
            }
        }
        return sha256Hex(s.toByteArray(Charsets.UTF_8)).take(24)
    }

    companion object {
        private const val TAG = "DacStageArt"
        const val MIN_SIDE = 1000
        private const val MAX_TRACKS = 256
        private val ID = Regex("^[0-9a-f]{64}$")

        internal fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
