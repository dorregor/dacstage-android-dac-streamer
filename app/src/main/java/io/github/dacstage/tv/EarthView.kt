package io.github.dacstage.tv

import android.content.Context
import android.util.Log
import io.github.dacstage.enrich.EnrichHttp
import io.github.dacstage.enrich.HttpResult
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Foto de Google Earth View con sus metadatos tal como los da el servicio (lo que falta queda en null). */
data class EarthPhoto(
    val id: String,
    val slug: String,
    val imageUrl: String,
    val name: String? = null,
    val region: String? = null,
    val country: String? = null,
    val attribution: String? = null,
    /** Color dominante (ARGB) que publica el catálogo; sirve para ajustar el contraste. */
    val primaryColor: Int? = null,
) {
    /** "Sonora, Mexico"; si no hay nombre, región y país. */
    val place: String?
        get() = name ?: listOfNotNull(region, country).joinToString(", ").ifEmpty { null }
}

/** Entrada del catálogo (/_api/photos.json): solo slug y color. */
data class EarthCatalogEntry(val slug: String, val primaryColor: Int?)

/** Lectura del JSON de Earth View (API interna, no documentada: todo es opcional y se valida). */
object EarthViewParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun catalog(body: String): List<EarthCatalogEntry> = runCatching {
        json.parseToJsonElement(body).jsonArray.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val slug = o.str("slug")?.takeIf { SLUG.matches(it) } ?: return@mapNotNull null
            EarthCatalogEntry(slug, o.color("primaryColor"))
        }
    }.getOrDefault(emptyList())

    /** Metadatos de una foto; la imagen solo puede venir de gstatic (si no, se reconstruye por id). */
    fun photo(body: String, entry: EarthCatalogEntry): EarthPhoto? {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val id = o.str("id")?.takeIf { ID.matches(it) } ?: idFromSlug(entry.slug) ?: return null
        return EarthPhoto(
            id = id,
            slug = entry.slug,
            imageUrl = normalizeImageUrl(o.str("photoUrl")) ?: imageUrlFor(id),
            name = o.str("name"),
            region = o.str("region"),
            country = o.str("country"),
            attribution = o.str("attribution"),
            primaryColor = o.color("primaryColor") ?: entry.primaryColor,
        )
    }

    fun idFromSlug(slug: String): String? = slug.substringAfterLast('-').takeIf { ID.matches(it) }

    fun imageUrlFor(id: String) = "https://www.gstatic.com/prettyearth/assets/full/$id.jpg"

    fun normalizeImageUrl(raw: String?): String? {
        val u = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val full = when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("https://") -> u
            u.startsWith("http://") -> "https://" + u.removePrefix("http://")
            else -> "https://$u"
        }
        return full.takeIf { it.startsWith("https://www.gstatic.com/prettyearth/") }
    }

    /** Al azar, evitando las mostradas hace poco. */
    fun pick(entries: List<EarthCatalogEntry>, recent: Collection<String>, random: Random = Random): EarthCatalogEntry? {
        if (entries.isEmpty()) return null
        val fresh = entries.filter { it.slug !in recent }
        val pool = fresh.ifEmpty { entries }
        return pool[random.nextInt(pool.size)]
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.color(key: String): Int? {
        val a = this[key] as? JsonArray ?: return null
        val c = a.mapNotNull { it.jsonPrimitive.intOrNull?.coerceIn(0, 255) }
        if (c.size < 3) return null
        return (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
    }

    private val SLUG = Regex("[a-z0-9-]{3,120}")
    private val ID = Regex("\\d{1,8}")
}

/**
 * Catálogo de Earth View cacheado en disco (se renueva cada [CATALOG_TTL_MS]; si falla la red, se usa el
 * guardado) y metadatos de una foto al azar. Lo usa solo la TV, que descarga las fotos directamente.
 */
class EarthViewClient private constructor(
    private val cacheFile: File,
    private val http: EnrichHttp,
) {
    private val mutex = Mutex()
    private var catalog: List<EarthCatalogEntry> = emptyList()
    private val recent = ArrayDeque<String>()

    suspend fun nextPhoto(): EarthPhoto? = mutex.withLock {
        val entries = loadCatalog()
        repeat(3) {
            val entry = EarthViewParser.pick(entries, recent) ?: return@withLock null
            remember(entry.slug)
            when (val r = http.get("$API/${entry.slug}.json")) {
                is HttpResult.Ok -> EarthViewParser.photo(r.body, entry)?.let { return@withLock it }
                else -> Log.d(TAG, "metadatos ${entry.slug}: $r")
            }
        }
        null
    }

    private fun remember(slug: String) {
        recent.addLast(slug)
        while (recent.size > RECENT_MAX) recent.removeFirst()
    }

    private suspend fun loadCatalog(): List<EarthCatalogEntry> {
        if (catalog.isNotEmpty()) return catalog
        val cached = withContext(Dispatchers.IO) {
            runCatching { cacheFile.takeIf { it.exists() }?.let { it.lastModified() to it.readText() } }.getOrNull()
        }
        val fresh = cached != null && System.currentTimeMillis() - cached.first < CATALOG_TTL_MS
        if (cached != null && fresh) {
            catalog = EarthViewParser.catalog(cached.second)
            if (catalog.isNotEmpty()) return catalog
        }
        when (val r = http.get("$API/photos.json")) {
            is HttpResult.Ok -> {
                val parsed = EarthViewParser.catalog(r.body)
                if (parsed.isNotEmpty()) {
                    catalog = parsed
                    withContext(Dispatchers.IO) { runCatching { cacheFile.writeText(r.body) } }
                    Log.i(TAG, "catálogo: ${parsed.size} fotos")
                    return catalog
                }
            }
            else -> Log.w(TAG, "catálogo: $r")
        }
        // Sin red: el catálogo guardado, aunque esté viejo.
        if (cached != null) catalog = EarthViewParser.catalog(cached.second)
        return catalog
    }

    companion object {
        private const val TAG = "EarthView"
        private const val API = "https://new-images-preview-dot-earth-viewer.appspot.com/_api"
        private const val CATALOG_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val RECENT_MAX = 60

        @Volatile private var instance: EarthViewClient? = null

        fun get(context: Context): EarthViewClient = instance ?: synchronized(this) {
            instance ?: EarthViewClient(
                File(context.applicationContext.cacheDir, "earthview_catalog.json"),
                EnrichHttp(),
            ).also { instance = it }
        }
    }
}
