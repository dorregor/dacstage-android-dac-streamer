package io.github.dacstage.enrich

import android.content.Context
import android.util.Log
import io.github.dacstage.model.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** Consulta de información en línea por pista (null = sin dato: no encontrado, error o desactivado). */
interface InfoLookup {
    suspend fun lyrics(q: TrackQuery): Lyrics?
    suspend fun musicBrainz(q: TrackQuery): MbInfo?

    /**
     * Letra ya guardada, sin red: [Cached] con su valor (null = se sabe que no hay) o null si no está en
     * caché (o caducó).
     */
    suspend fun cachedLyrics(q: TrackQuery): Cached<Lyrics>? = null

    /** Deja la letra en caché para después. true si hubo que consultar la red (para espaciar peticiones). */
    suspend fun prefetchLyrics(q: TrackQuery): Boolean {
        lyrics(q)
        return true
    }
}

/** Resultado guardado en caché ([value] null = consultado y no existe). */
data class Cached<T>(val value: T?)

/**
 * Información en línea de la pista que suena (letra y datos del disco). Solo envía título, artista,
 * álbum y duración a LRCLIB y MusicBrainz, y solo si el usuario lo activó en el host. Resultados
 * en caché en disco: encontrado 30 días, no encontrado 3 días; los errores de red no se guardan.
 */
class EnrichmentService internal constructor(
    private val cacheDir: File,
    private val lrclib: LrclibClient,
    private val musicBrainz: MusicBrainzClient,
    private val now: () -> Long = System::currentTimeMillis,
) : InfoLookup {
    constructor(context: Context) : this(
        cacheDir = File(context.cacheDir, "enrich"),
        lrclib = LrclibClient(EnrichHttp()),
        musicBrainz = MusicBrainzClient(EnrichHttp(minIntervalMs = 1_100)),
    )

    override suspend fun lyrics(q: TrackQuery): Lyrics? =
        cached("lrclib", q, Lyrics.serializer()) { lrclib.find(q) }

    override suspend fun prefetchLyrics(q: TrackQuery): Boolean {
        if (fromCache("lrclib", q, Lyrics.serializer()) != null) return false
        lyrics(q)
        return true
    }

    override suspend fun cachedLyrics(q: TrackQuery): Cached<Lyrics>? = fromCache("lrclib", q, Lyrics.serializer())

    private suspend fun <T> fromCache(kind: String, q: TrackQuery, ser: KSerializer<T>): Cached<T>? {
        val file = File(cacheDir, kind + "-" + sha(q.cacheKey) + ".json")
        val e = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString(Entry.serializer(ser), file.readText()) }.getOrNull()
        } ?: return null
        val ttl = if (e.value != null) FOUND_TTL_MS else NOT_FOUND_TTL_MS
        return if (now() - e.savedAt < ttl) Cached(e.value) else null
    }

    override suspend fun musicBrainz(q: TrackQuery): MbInfo? =
        cached("mb", q, MbInfo.serializer()) { musicBrainz.find(q) }

    @Serializable
    private data class Entry<T>(val savedAt: Long, val value: T? = null)

    private suspend fun <T> cached(kind: String, q: TrackQuery, ser: KSerializer<T>, fetch: suspend () -> LookupResult<T>): T? {
        val file = File(cacheDir, kind + "-" + sha(q.cacheKey) + ".json")
        val entrySer = Entry.serializer(ser)
        withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString(entrySer, file.readText()) }.getOrNull()
        }?.let { e ->
            val ttl = if (e.value != null) FOUND_TTL_MS else NOT_FOUND_TTL_MS
            if (now() - e.savedAt < ttl) return e.value
        }
        return when (val r = fetch()) {
            is LookupResult.Found -> r.value.also { save(file, entrySer, Entry(now(), it)) }
            LookupResult.NotFound -> null.also { save(file, entrySer, Entry<T>(now(), null)) }
            is LookupResult.Error -> null.also { Log.i(TAG, "$kind: ${r.reason}") }
        }
    }

    private suspend fun <T> save(file: File, ser: KSerializer<Entry<T>>, e: Entry<T>) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ser, e))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { Log.w(TAG, "caché: ${it.message}") }
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "DacStageInfo"
        private const val FOUND_TTL_MS = 30L * 24 * 3600 * 1000
        private const val NOT_FOUND_TTL_MS = 3L * 24 * 3600 * 1000
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
