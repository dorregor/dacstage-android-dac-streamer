package io.github.dacstage.network

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** La línea supera el límite: se cierra esa conexión (el servidor sigue vivo). */
class LineTooLongException(limit: Int) : IOException("línea > $limit bytes")

/**
 * Lector de líneas UTF-8 con límite de tamaño (no usa BufferedReader.readLine, que no tiene tope).
 * Devuelve null en EOF. Admite "\r\n".
 */
class BoundedLineReader(private val input: InputStream, private val maxLineBytes: Int) {
    private val buf = ByteArray(16 * 1024)
    private var pos = 0
    private var lim = 0
    private var line = ByteArray(1024)
    private var len = 0

    fun readLine(): String? {
        len = 0
        while (true) {
            if (pos >= lim) {
                val n = input.read(buf)
                if (n < 0) return null
                pos = 0
                lim = n
                continue
            }
            var i = pos
            while (i < lim && buf[i] != NL) i++
            append(i - pos)
            if (i < lim) {
                pos = i + 1
                var end = len
                if (end > 0 && line[end - 1] == CR) end--
                return String(line, 0, end, Charsets.UTF_8)
            }
            pos = lim
        }
    }

    private fun append(n: Int) {
        if (n == 0) return
        if (len + n > maxLineBytes) throw LineTooLongException(maxLineBytes)
        if (len + n > line.size) line = line.copyOf(maxOf(len + n, minOf(line.size * 2, maxLineBytes)))
        System.arraycopy(buf, pos, line, len, n)
        len += n
    }

    private companion object {
        const val NL = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
    }
}

/** Escribe un mensaje por línea y vacía el buffer (lo llama un único hilo escritor). */
internal fun OutputStream.writeLine(text: String) {
    write(text.toByteArray(Charsets.UTF_8))
    write('\n'.code)
    flush()
}
