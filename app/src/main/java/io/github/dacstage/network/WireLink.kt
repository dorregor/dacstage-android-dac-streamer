package io.github.dacstage.network

import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Un canal ya abierto entre host y cliente (TV o control remoto) por el que viaja el protocolo de líneas
 * JSON: un socket TCP de la red local o un canal Bluetooth L2CAP. El protocolo (emparejado, HMAC, estado y
 * comandos) es el mismo en ambos.
 */
internal interface WireLink {
    val input: InputStream
    val output: OutputStream
    /** Para límites por origen y registros: IP, o "bt:" + dirección Bluetooth. */
    val remoteAddress: String
    /** Puerto remoto (0 en Bluetooth). */
    val remotePort: Int
    /** true = red local (hay servidor HTTP de carátulas y dirección reutilizable). */
    val isNetwork: Boolean

    /** Cierre por falta de datos entrantes en [ms] (0 = sin límite). Reiniciable. */
    fun setReadTimeout(ms: Int)
    fun shutdownOutput()
    fun close()
}

internal class SocketLink(private val socket: Socket) : WireLink {
    init {
        try {
            socket.tcpNoDelay = true
        } catch (_: IOException) {
        }
    }

    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()
    override val remoteAddress: String = socket.inetAddress?.hostAddress ?: "?"
    override val remotePort: Int get() = socket.port
    override val isNetwork = true

    override fun setReadTimeout(ms: Int) {
        socket.soTimeout = ms
    }

    override fun shutdownOutput() {
        try {
            socket.shutdownOutput()
        } catch (_: IOException) {
        }
    }

    override fun close() = closeQuietly(socket)
}

/**
 * Canal Bluetooth (L2CAP orientado a conexión). BluetoothSocket no tiene tiempo de espera de lectura:
 * un hilo vigila la última lectura y cierra el canal si no llega nada en el plazo, y la lectura bloqueada
 * termina con [SocketTimeoutException] (igual que un socket TCP).
 */
internal class BluetoothLink(private val socket: BluetoothSocket) : WireLink {
    @Volatile private var lastReadMs = System.nanoTime() / 1_000_000
    @Volatile private var timeoutMs = 0
    @Volatile private var closed = false
    @Volatile private var timedOut = false

    override val remoteAddress: String = "bt:" + (socket.remoteDevice?.address ?: "?")
    override val remotePort = 0
    override val isNetwork = false

    override val input: InputStream = object : InputStream() {
        private val raw = socket.inputStream
        private fun touched(n: Int): Int {
            if (n > 0) lastReadMs = System.nanoTime() / 1_000_000
            return n
        }
        private fun check(e: IOException): Nothing = if (timedOut) throw SocketTimeoutException("sin datos") else throw e

        override fun read(): Int = try {
            raw.read().also { if (it >= 0) touched(1) }
        } catch (e: IOException) {
            check(e)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            touched(raw.read(b, off, len))
        } catch (e: IOException) {
            check(e)
        }

        override fun available(): Int = raw.available()
        override fun close() = this@BluetoothLink.close()
    }

    override val output: OutputStream get() = socket.outputStream

    private val watchdog = Thread({
        while (!closed) {
            try {
                Thread.sleep(1_000)
            } catch (_: InterruptedException) {
                break
            }
            val t = timeoutMs
            if (t > 0 && System.nanoTime() / 1_000_000 - lastReadMs > t) {
                timedOut = true
                close()
            }
        }
    }, "bt-link-watchdog").apply { isDaemon = true; start() }

    override fun setReadTimeout(ms: Int) {
        lastReadMs = System.nanoTime() / 1_000_000
        timeoutMs = ms
    }

    override fun shutdownOutput() {
        try {
            socket.outputStream.flush()
        } catch (_: IOException) {
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        watchdog.interrupt()
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}
