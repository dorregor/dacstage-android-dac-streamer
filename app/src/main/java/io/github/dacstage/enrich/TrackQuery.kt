package io.github.dacstage.enrich

import io.github.dacstage.model.TrackInfo
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Lo que se busca en los servicios públicos: solo título, artista, álbum y duración de la pista que suena.
 * [cacheKey] es estable entre reproductores (no depende del id de sesión).
 */
data class TrackQuery(val title: String, val artist: String, val album: String?, val durationMs: Long?) {
    val cacheKey: String
        get() = listOf(TextMatch.norm(title), TextMatch.norm(artist), TextMatch.norm(album.orEmpty()), ((durationMs ?: 0) / 1000).toString())
            .joinToString("\u0001")

    companion object {
        fun from(item: io.github.dacstage.model.QueueItem): TrackQuery? =
            from(TrackInfo(item.title, item.artist, item.album, item.durationMs))

        fun from(t: TrackInfo?): TrackQuery? {
            val title = t?.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val artist = t.artist?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return TrackQuery(title, artist, t.album?.trim()?.takeIf { it.isNotEmpty() }, t.durationMs?.takeIf { it > 0 })
        }
    }
}

/** Comparación tolerante de títulos/artistas entre fuentes (sin adivinar: o coincide o no). */
object TextMatch {
    const val DURATION_TOLERANCE_MS = 3_000L

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(NON_ALNUM, " ")
            .trim()

    /** Sin "(…)", "[…]", sufijos " - Remastered 2011" ni "feat. X". */
    fun loose(s: String): String {
        var t = BRACKETS.replace(s, " ")
        t = DASH_SUFFIX.replace(t, "")
        t = FEAT.replace(t, "")
        return norm(t)
    }

    fun titleEq(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val na = norm(a)
        if (na.isNotEmpty() && na == norm(b)) return true
        val la = loose(a)
        return la.isNotEmpty() && la == loose(b)
    }

    /** Primer artista de "A, B", "A & B", "A feat. B", "A x B". */
    fun primaryArtist(s: String): String = ARTIST_SPLIT.split(s, 2).first().trim()

    /** El artista consultado coincide con el crédito del resultado (completo o su primer artista). */
    fun artistMatches(query: String, credit: String?): Boolean {
        if (credit.isNullOrBlank()) return false
        if (titleEq(query, credit)) return true
        val qp = loose(primaryArtist(query))
        val cp = loose(primaryArtist(credit))
        return qp.isNotEmpty() && qp == cp
    }

    /** null en alguno de los dos = no se puede comprobar (el llamador decide si basta con lo demás). */
    fun durationOk(aMs: Long?, bMs: Long?, toleranceMs: Long = DURATION_TOLERANCE_MS): Boolean? {
        if (aMs == null || bMs == null || aMs <= 0 || bMs <= 0) return null
        return abs(aMs - bMs) <= toleranceMs
    }

    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val BRACKETS = Regex("\\([^)]*\\)|\\[[^\\]]*]")
    private val DASH_SUFFIX = Regex(
        "\\s+[-–—]\\s+.*\\b(remaster(ed)?|version|mix|edit|live|mono|stereo|deluxe|edition|bonus|demo|acoustic)\\b.*$",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT = Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex("\\s*(,|&|;|/|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+featuring\\s+|\\s+x\\s+|\\s+y\\s+|\\s+and\\s+)\\s*", RegexOption.IGNORE_CASE)
}
