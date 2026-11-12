package io.github.dacstage.players.uapp

/** Contenido de PosA.txt/PosB.txt: START / posición_ms / índice_actual / total / END (CRLF). */
data class UappPosition(val positionMs: Long, val index: Int, val total: Int)

object PosFileParser {
    /** null si el archivo está incompleto o mal formado (UAPP lo borra y reescribe cada segundo). */
    fun parse(bytes: ByteArray): UappPosition? = parseText(String(bytes, Charsets.UTF_8))

    fun parseText(text: String): UappPosition? {
        val lines = text.trimStart('﻿').split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 5 || lines[0] != "START" || lines[4] != "END") return null
        val pos = lines[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val index = lines[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val total = lines[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return UappPosition(pos, index, total)
    }
}

/** Reescritura de ArtURL de UAPP (CDN de TIDAL: ".../<uuid>/750x750.jpg") a tamaños mayores. */
object UappArtUrls {
    private val SIZE_SEGMENT = Regex("/(\\d{2,4})x(\\d{2,4})\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)
    private val TARGETS = listOf(1280, 1080)

    /** Candidatas en orden: 1280x1280, 1080x1080 y la original (sin duplicados ni reducciones). */
    fun candidates(url: String?): List<String> {
        val original = url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return emptyList()
        val m = SIZE_SEGMENT.find(original) ?: return listOf(original)
        val w = m.groupValues[1].toInt()
        val h = m.groupValues[2].toInt()
        val ext = m.groupValues[3]
        val prefix = original.substring(0, m.range.first)
        val out = LinkedHashSet<String>()
        for (t in TARGETS) if (t > maxOf(w, h)) out += "$prefix/${t}x$t.$ext"
        out += original
        return out.toList()
    }
}

/** Salida de `stat -c '%n|%Y|%y|%s'` (toybox). %y aporta los milisegundos. */
object UappStatParser {
    private val FRACTION = Regex(":\\d{2}\\.(\\d+)")

    fun parse(text: String): List<UappFileStat> = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split('|')
        if (parts.size < 4) return@mapNotNull null
        val name = parts[0].substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val seconds = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
        val millis = FRACTION.find(parts[2])?.groupValues?.get(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        val size = parts[3].trim().toLongOrNull() ?: return@mapNotNull null
        UappFileStat(name, seconds * 1000 + millis, size)
    }.toList()
}
