package io.github.dacstage.players

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Prohibido en todo app/src/main/java: en UAPP vacían la cola del usuario y detienen la reproducción.
 * Se analiza el código (literales de cadena incluidos, para cazar reflexión); los comentarios se ignoran
 * porque el contrato documenta la prohibición.
 */
class BannedApiTest {

    private val banned = listOf("playFromSearch", "playFromMediaId")

    @Test
    fun noBannedTransportCalls() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: return fail("No se encuentra src/main/java (cwd=${File(".").absolutePath})")
        val sources = root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        assertTrue("No se encontraron fuentes", sources.isNotEmpty())
        val hits = sources.flatMap { file ->
            val code = stripComments(file.readText())
            banned.filter { it in code }.map { "${file.path}: $it" }
        }
        assertTrue("APIs prohibidas encontradas:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun stripperKeepsStringsAndDropsComments() {
        val src = "val a = \"playFromSearch\" // playFromMediaId\n/* playFromMediaId /* anidado */ */ x()"
        val code = stripComments(src)
        assertTrue("playFromSearch" in code)
        assertTrue("playFromMediaId" !in code)
    }

    /** Quita comentarios // y /* */ (anidados, como en Kotlin) respetando literales de cadena y carácter. */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            val next = if (i + 1 < n) src[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    while (i < n && src[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    var depth = 1
                    i += 2
                    while (i < n && depth > 0) {
                        if (src.startsWith("/*", i)) { depth++; i += 2 }
                        else if (src.startsWith("*/", i)) { depth--; i += 2 }
                        else i++
                    }
                    out.append(' ')
                }
                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it + 3 }
                    out.append(src, i, end); i = end
                }
                c == '"' || c == '\'' -> {
                    val start = i
                    i++
                    while (i < n && src[i] != c && src[i] != '\n') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    i = minOf(n, i + 1)
                    out.append(src, start, i)
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
