package io.github.dacstage.enrich

import io.github.dacstage.model.InfoSource
import io.github.dacstage.model.TrackDetails
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/** Lo que se guarda de MusicBrainz: datos para mostrar + si el disco tiene portada en Cover Art Archive. */
@Serializable
data class MbInfo(val details: TrackDetails, val hasFrontCover: Boolean = false) {
    /** Portada a 1200 px (y 500 px de respaldo) del disco identificado. null si no hay disco o portada. */
    val coverUrls: List<String>
        get() = details.releaseMbid?.takeIf { hasFrontCover }?.let { id ->
            listOf("$CAA/release/$id/front-1200", "$CAA/release/$id/front-500")
        }.orEmpty()

    companion object {
        const val CAA = "https://coverartarchive.org"
    }
}

/**
 * MusicBrainz (gratis, sin clave, máximo 1 petición/s): busca la grabación por título + artista (+ álbum)
 * y solo la acepta si coinciden título, artista y duración (±3 s). El disco solo se toma si su título
 * coincide con el álbum que publica el reproductor; de él salen el sello y si hay portada.
 */
class MusicBrainzClient(private val http: EnrichHttp) {

    suspend fun find(q: TrackQuery): LookupResult<MbInfo> {
        val search = when (val r = http.get(searchUrl(q))) {
            is HttpResult.Ok -> r.body
            is HttpResult.Status -> return LookupResult.Error("musicbrainz ${r.code}")
            is HttpResult.Failed -> return LookupResult.Error(r.reason)
        }
        val pick = MbPick.pick(search, q) ?: return LookupResult.NotFound
        var label: String? = null
        var front = false
        if (pick.releaseMbid != null) {
            when (val r = http.get("$BASE/ws/2/release/${pick.releaseMbid}?inc=labels&fmt=json")) {
                is HttpResult.Ok -> MbPick.parseRelease(r.body)?.let { label = it.first; front = it.second }
                is HttpResult.Status, is HttpResult.Failed -> Unit // sin sello ni portada; el resto vale
            }
        }
        return LookupResult.Found(
            MbInfo(
                details = TrackDetails(
                    year = pick.year,
                    label = label,
                    releaseTitle = pick.releaseTitle,
                    recordingMbid = pick.recordingMbid,
                    releaseMbid = pick.releaseMbid,
                    source = InfoSource.MUSICBRAINZ,
                ),
                hasFrontCover = front,
            ),
        )
    }

    private fun searchUrl(q: TrackQuery): String {
        val parts = buildList {
            add("recording:\"${lucene(TextMatch.loose(q.title).ifEmpty { q.title })}\"")
            add("artist:\"${lucene(TextMatch.primaryArtist(q.artist))}\"")
            q.album?.let { add("release:\"${lucene(TextMatch.loose(it).ifEmpty { it })}\"") }
        }
        val query = URLEncoder.encode(parts.joinToString(" AND "), "UTF-8")
        return "$BASE/ws/2/recording?query=$query&fmt=json&limit=15"
    }

    private fun lucene(s: String) = s.replace("\\", " ").replace("\"", " ")

    companion object {
        const val BASE = "https://musicbrainz.org"
    }
}

/** Selección pura sobre la respuesta JSON de MusicBrainz (testeable). */
object MbPick {
    data class Pick(
        val recordingMbid: String,
        val year: Int?,
        val releaseMbid: String?,
        val releaseTitle: String?,
    )

    private const val MIN_SCORE = 85
    private val json = Json { ignoreUnknownKeys = true }

    fun pick(body: String, q: TrackQuery): Pick? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val recordings = root["recordings"] as? JsonArray ?: return null
        for (r in recordings) {
            val o = r as? JsonObject ?: continue
            val score = o.int("score") ?: 0
            if (score < MIN_SCORE) continue
            if (!TextMatch.titleEq(o.str("title"), q.title)) continue
            if (!TextMatch.artistMatches(q.artist, creditOf(o))) continue
            val release = matchingRelease(o, q.album)
            val dur = TextMatch.durationOk(o.long("length"), q.durationMs)
            // Sin duración comprobable solo se acepta si además coincide el disco.
            if (dur == false || (dur == null && release == null)) continue
            val id = o.str("id") ?: continue
            return Pick(
                recordingMbid = id,
                year = o.str("first-release-date")?.take(4)?.toIntOrNull()?.takeIf { it in 1800..2200 },
                releaseMbid = release?.str("id"),
                releaseTitle = release?.str("title"),
            )
        }
        return null
    }

    /** Sello (primero de label-info) y si el disco tiene portada frontal en Cover Art Archive. */
    fun parseRelease(body: String): Pair<String?, Boolean>? {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val label = (o["label-info"] as? JsonArray)?.firstNotNullOfOrNull { li ->
            ((li as? JsonObject)?.get("label") as? JsonObject)?.str("name")?.takeIf { it.isNotBlank() && it != "[no label]" }
        }
        val front = ((o["cover-art-archive"] as? JsonObject)?.get("front") as? JsonPrimitive)?.booleanOrNull == true
        return label to front
    }

    private fun creditOf(o: JsonObject): String? {
        val credits = o["artist-credit"] as? JsonArray ?: return null
        return credits.joinToString("") { c ->
            val co = c as? JsonObject ?: return@joinToString ""
            (co.str("name") ?: "") + (co.str("joinphrase") ?: "")
        }.takeIf { it.isNotBlank() }
    }

    /** Disco oficial cuyo título coincide con el álbum; sin álbum consultado, ninguno. */
    private fun matchingRelease(o: JsonObject, album: String?): JsonObject? {
        album ?: return null
        val releases = (o["releases"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val same = releases.filter { TextMatch.titleEq(it.str("title"), album) }
        return same.firstOrNull { it.str("status") == "Official" } ?: same.firstOrNull()
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
}
