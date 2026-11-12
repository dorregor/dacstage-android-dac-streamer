package io.github.dacstage.players.uapp

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Coherencia entre la cola de archivos de UAPP y su MediaSession. Si no coinciden, la cola NO se muestra
 * (nunca se simula): el llamador publica QueueUnavailableReason.UAPP_OUT_OF_SYNC.
 */
object UappQueueSync {

    sealed interface Result {
        data class InSync(val currentIndex: Int) : Result
        data class OutOfSync(val reason: Reason) : Result
        data object Empty : Result
    }

    enum class Reason { NO_SESSION_TITLE, TOTAL_MISMATCH, TITLE_MISMATCH, AMBIGUOUS }

    /**
     * @param position PosA/PosB más reciente (índice vivo y total de UAPP), si se pudo leer.
     * @param xmlCurrentItem CurrentItem del XML (respaldo si no hay PosA/PosB).
     * @param sessionTitle título que publica la MediaSession ahora.
     * @param hintIndex índice esperado (p. ej. durante un salto) para desempatar títulos repetidos.
     */
    fun resolve(
        items: List<UappQueueEntry>,
        position: UappPosition?,
        xmlCurrentItem: Int?,
        sessionTitle: String?,
        hintIndex: Int? = null,
    ): Result {
        if (items.isEmpty()) return Result.Empty
        if (sessionTitle.isNullOrBlank()) return Result.OutOfSync(Reason.NO_SESSION_TITLE)
        if (position != null && position.total != items.size) return Result.OutOfSync(Reason.TOTAL_MISMATCH)

        val primary = position?.index ?: xmlCurrentItem
        if (primary != null && primary in items.indices && entryMatches(items[primary], sessionTitle)) {
            return Result.InSync(primary)
        }
        // El índice de PosA puede ir hasta ~1 s por detrás de la sesión: se deduce por título.
        val candidates = items.indices.filter { entryMatches(items[it], sessionTitle) }
        return when {
            candidates.isEmpty() -> Result.OutOfSync(Reason.TITLE_MISMATCH)
            candidates.size == 1 -> Result.InSync(candidates[0])
            else -> {
                val anchor = hintIndex ?: primary ?: return Result.OutOfSync(Reason.AMBIGUOUS)
                val best = candidates.minBy { abs(it - anchor) }
                val tie = candidates.count { abs(it - anchor) == abs(best - anchor) } > 1
                if (tie) Result.OutOfSync(Reason.AMBIGUOUS) else Result.InSync(best)
            }
        }
    }

    /**
     * UAPP publica en la sesión "<TrackNr>. <Title>" (p. ej. "10. Familia Garfield") mientras el XML guarda
     * solo "Familia Garfield" con TrackNr="10". Se acepta ese prefijo solo si el número coincide con el TrackNr.
     */
    fun entryMatches(entry: UappQueueEntry, sessionTitle: String?): Boolean {
        if (titleMatches(entry.title, sessionTitle)) return true
        val m = sessionTitle?.let { TRACK_PREFIX.matchEntire(it) } ?: return false
        val nr = m.groupValues[1].toIntOrNull() ?: return false
        return entry.trackNr == nr && titleMatches(entry.title, m.groupValues[2])
    }

    fun titleMatches(queueTitle: String?, sessionTitle: String?): Boolean {
        if (queueTitle.isNullOrBlank() || sessionTitle.isNullOrBlank()) return false
        val a = normalize(queueTitle)
        val b = normalize(sessionTitle)
        if (a.isNotEmpty() && a == b) return true
        // Tolera sufijos entre paréntesis/corchetes que una de las dos fuentes omita.
        val a2 = normalize(stripBrackets(queueTitle))
        val b2 = normalize(stripBrackets(sessionTitle))
        return a2.isNotEmpty() && a2 == b2
    }

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(NON_ALNUM, " ")
            .trim()

    private fun stripBrackets(s: String) = BRACKETS.replace(s, " ")

    private val TRACK_PREFIX = Regex("""^\s*(\d{1,3})\s*[.\-)]\s+(.+)$""")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val BRACKETS = Regex("\\([^)]*\\)|\\[[^\\]]*]")
}
