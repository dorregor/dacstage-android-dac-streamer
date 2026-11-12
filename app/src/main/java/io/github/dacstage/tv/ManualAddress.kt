package io.github.dacstage.tv

import io.github.dacstage.network.DEFAULT_PORT

/** Parseo de la IP manual que escribe el usuario en la TV (IPv4 con puerto opcional). */
object ManualAddress {
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?::(\d{1,5}))?$""")

    fun parse(input: String): Pair<String, Int>? {
        val m = IPV4.matchEntire(input.trim()) ?: return null
        val octets = (1..4).map { m.groupValues[it].toInt() }
        if (octets.any { it > 255 }) return null
        val port = m.groupValues[5].takeIf { it.isNotEmpty() }?.toInt() ?: DEFAULT_PORT
        if (port !in 1..65535) return null
        return octets.joinToString(".") to port
    }
}
