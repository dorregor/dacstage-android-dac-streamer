package io.github.dacstage.players.uapp

import kotlin.math.roundToLong

/** Una pista de PlayQueue.xml de UAPP. Campos ausentes o vacíos → null (nunca se rellenan). */
data class UappQueueEntry(
    val index: Int,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNr: Int? = null,
    /** ID de pista (TIDAL en pistas de TIDAL). */
    val id: String? = null,
    val albumId: String? = null,
    val artUrl: String? = null,
    val durationMs: Long? = null,
    /** Atributo Resolution (bits). */
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,
)

data class UappPlayQueue(
    val items: List<UappQueueEntry>,
    /** CurrentTrack/@CurrentItem (solo se actualiza al reescribir el XML; el índice vivo está en PosA/PosB). */
    val currentItem: Int? = null,
    val shuffleMode: Int? = null,
    val repeatMode: Int? = null,
)

/**
 * Parser tolerante de PlayQueue.xml (UAPP lo escribe en UTF-16 con BOM, sin escritura atómica).
 * Devuelve null si el documento está incompleto (p. ej. leído a mitad de escritura) o es ilegible.
 */
object PlayQueueXmlParser {

    fun parse(bytes: ByteArray): UappPlayQueue? = parseText(decode(bytes))

    /** Decodifica según BOM; sin BOM, deduce UTF-16 por los bytes nulos o cae a UTF-8. */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = if (bytes.size > 1) bytes[1].toInt() and 0xFF else -1
        val b2 = if (bytes.size > 2) bytes[2].toInt() and 0xFF else -1
        val text = when {
            b0 == 0xFE && b1 == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            b0 == 0xFF && b1 == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            b0 == 0xEF && b1 == 0xBB && b2 == 0xBF -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            b0 == 0 && b1 > 0 -> String(bytes, Charsets.UTF_16BE)
            b0 > 0 && b1 == 0 -> String(bytes, Charsets.UTF_16LE)
            else -> String(bytes, Charsets.UTF_8)
        }
        return text.trimStart('\uFEFF')
    }

    fun parseText(text: String): UappPlayQueue? {
        val tags = START_TAG.findAll(text).toList()
        val root = tags.firstOrNull { !it.groupValues[1].startsWith("?") } ?: return null
        val rootName = root.groupValues[1]
        val rootSelfClosed = root.groupValues[3] == "/"
        // Documento completo: termina cerrando la raíz.
        val complete = if (rootSelfClosed) {
            text.substring(root.range.last + 1).isBlank()
        } else {
            Regex("</\\s*${Regex.escape(rootName)}\\s*>\\s*$").containsMatchIn(text)
        }
        if (!complete) return null

        val items = ArrayList<UappQueueEntry>()
        var currentItem: Int? = null
        var shuffle: Int? = null
        var repeat: Int? = null
        for (tag in tags) {
            val name = tag.groupValues[1]
            val attrs by lazy { attributes(tag.groupValues[2]) }
            when {
                name.equals("Item", ignoreCase = true) -> items += entry(items.size, attrs)
                name.equals("CurrentTrack", ignoreCase = true) -> currentItem = attrs.int("CurrentItem")?.takeIf { it >= 0 }
                name.equals("Misc", ignoreCase = true) -> {
                    shuffle = attrs.int("ShuffleMode"); repeat = attrs.int("RepeatMode")
                }
            }
        }
        return UappPlayQueue(items, currentItem, shuffle, repeat)
    }

    private fun entry(index: Int, a: Map<String, String>) = UappQueueEntry(
        index = index,
        title = a.str("Title"),
        artist = a.str("Artist"),
        album = a.str("Album"),
        trackNr = a.int("TrackNr")?.takeIf { it > 0 },
        id = a.str("ID"),
        albumId = a.str("AlbumID"),
        artUrl = a.str("ArtURL"),
        // UAPP escribe Duration en segundos (Double.toString).
        durationMs = a.str("Duration")?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).roundToLong() },
        bitDepth = a.int("Resolution")?.takeIf { it > 0 },
        sampleRateHz = a.int("SampleRate")?.takeIf { it > 0 },
    )

    private fun attributes(raw: String): Map<String, String> =
        ATTR.findAll(raw).associate { m ->
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
            m.groupValues[1] to unescape(value)
        }

    private fun Map<String, String>.str(key: String): String? = this[key]?.trim()?.takeIf { it.isNotEmpty() }
    private fun Map<String, String>.int(key: String): Int? = str(key)?.toIntOrNull()

    internal fun unescape(s: String): String {
        if ('&' !in s) return s
        return ENTITY.replace(s) { m ->
            val body = m.groupValues[1]
            when {
                body == "amp" -> "&"
                body == "lt" -> "<"
                body == "gt" -> ">"
                body == "quot" -> "\""
                body == "apos" -> "'"
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> m.value
            }
        }
    }

    // Etiqueta de apertura con atributos entrecomillados (admite '>' dentro de las comillas).
    private val START_TAG = Regex(
        "<([?A-Za-z_][\\w.:-]*)((?:\\s+[\\w.:-]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'))*)\\s*([/?]?)>"
    )
    private val ATTR = Regex("([\\w.:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[A-Za-z]+);")
}
