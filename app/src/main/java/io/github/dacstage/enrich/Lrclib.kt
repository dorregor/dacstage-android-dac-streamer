package io.github.dacstage.enrich

import io.github.dacstage.model.InfoSource
import io.github.dacstage.model.LyricLine
import io.github.dacstage.model.Lyrics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** Letras sincronizadas en formato LRC: "[mm:ss.xx] texto" (varias marcas por línea permitidas). */
object LrcParser {
    private val TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(lrc: String?): List<LyricLine> {
        if (lrc.isNullOrBlank()) return emptyList()
        val out = ArrayList<LyricLine>()
        for (raw in lrc.lineSequence()) {
            var rest = raw.trim()
            val times = ArrayList<Long>(2)
            while (true) {
                val m = TAG.matchAt(rest, 0) ?: break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.toLong()
                }
                times += min * 60_000 + sec * 1000 + ms
                rest = rest.substring(m.range.last + 1).trimStart()
            }
            if (times.isEmpty()) continue // metadatos ([ar:], [ti:]…) u otras líneas
            for (t in times) out += LyricLine(t, rest.trim())
        }
        out.sortBy { it.timeMs }
        return out
    }

    /** Índice de la línea vigente en [positionMs] (−1 antes de la primera). Búsqueda binaria. */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid; lo = mid + 1
            } else hi = mid - 1
        }
        return ans
    }
}

/**
 * LRCLIB (lrclib.net): API pública y gratuita, sin clave. Primero /api/get (coincidencia exacta con
 * duración); si no hay, /api/search y se elige solo un resultado que coincida en título, artista y
 * duración (±3 s).
 */
class LrclibClient(private val http: EnrichHttp) {

    suspend fun find(q: TrackQuery): LookupResult<Lyrics> {
        val exact = http.get(getUrl(q))
        when (exact) {
            is HttpResult.Ok -> LrclibPick.parseOne(exact.body)?.let { e ->
                if (LrclibPick.accepts(e, q)) return LookupResult.Found(e.toLyrics())
            }
            is HttpResult.Status -> if (exact.code != 404) return LookupResult.Error("lrclib ${exact.code}")
            is HttpResult.Failed -> return LookupResult.Error(exact.reason)
        }
        return when (val s = http.get(searchUrl(q))) {
            is HttpResult.Ok -> LrclibPick.best(LrclibPick.parseList(s.body), q)?.let { LookupResult.Found(it.toLyrics()) }
                ?: LookupResult.NotFound
            is HttpResult.Status -> if (s.code == 404) LookupResult.NotFound else LookupResult.Error("lrclib ${s.code}")
            is HttpResult.Failed -> LookupResult.Error(s.reason)
        }
    }

    private fun getUrl(q: TrackQuery): String = buildString {
        append(BASE).append("/api/get?track_name=").append(enc(q.title)).append("&artist_name=").append(enc(q.artist))
        q.album?.let { append("&album_name=").append(enc(it)) }
        q.durationMs?.let { append("&duration=").append((it + 500) / 1000) }
    }

    private fun searchUrl(q: TrackQuery): String =
        "$BASE/api/search?track_name=${enc(TextMatch.loose(q.title).ifEmpty { q.title })}&artist_name=${enc(TextMatch.primaryArtist(q.artist))}"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val BASE = "https://lrclib.net"
    }
}

/** Entrada de LRCLIB y criterio de aceptación (lógica pura, testeable). */
object LrclibPick {
    data class Entry(
        val track: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
        val instrumental: Boolean,
        val plain: String?,
        val synced: String?,
    ) {
        fun toLyrics(): Lyrics {
            val lines = LrcParser.parse(synced)
            return Lyrics(
                synced = lines.takeIf { it.isNotEmpty() },
                plain = plain?.takeIf { it.isNotBlank() && lines.isEmpty() },
                instrumental = instrumental && lines.isEmpty() && plain.isNullOrBlank(),
                source = InfoSource.LRCLIB,
            )
        }

        val hasText: Boolean get() = !synced.isNullOrBlank() || !plain.isNullOrBlank() || instrumental
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parseOne(body: String): Entry? = runCatching { entry(json.parseToJsonElement(body)) }.getOrNull()

    fun parseList(body: String): List<Entry> = runCatching {
        (json.parseToJsonElement(body) as? JsonArray)?.mapNotNull(::entry).orEmpty()
    }.getOrDefault(emptyList())

    private fun entry(e: JsonElement): Entry? {
        val o = e as? JsonObject ?: return null
        fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        return Entry(
            track = s("trackName"),
            artist = s("artistName"),
            album = s("albumName"),
            durationMs = (o["duration"]?.jsonPrimitive?.doubleOrNull)?.let { (it * 1000).toLong() },
            instrumental = o["instrumental"]?.jsonPrimitive?.booleanOrNull == true,
            plain = s("plainLyrics"),
            synced = s("syncedLyrics"),
        )
    }

    /** Título y artista iguales y, si hay duración en ambos lados, ±3 s. Sin duración propia no se acepta. */
    fun accepts(e: Entry, q: TrackQuery): Boolean {
        if (!e.hasText) return false
        if (!TextMatch.titleEq(e.track, q.title) || !TextMatch.artistMatches(q.artist, e.artist)) return false
        return TextMatch.durationOk(e.durationMs, q.durationMs) == true
    }

    /** Mejor resultado aceptable: con letra sincronizada, luego mismo álbum, luego duración más cercana. */
    fun best(list: List<Entry>, q: TrackQuery): Entry? =
        list.filter { accepts(it, q) }.minWithOrNull(
            compareBy<Entry>(
                { if (!it.synced.isNullOrBlank()) 0 else 1 },
                { if (q.album != null && TextMatch.titleEq(it.album, q.album)) 0 else 1 },
                { kotlin.math.abs((it.durationMs ?: 0) - (q.durationMs ?: 0)) },
            ),
        )
}
