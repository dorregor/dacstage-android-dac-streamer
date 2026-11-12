package io.github.dacstage.network

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Primitivas de emparejado/autenticación (ver formato en Protocol.kt). */
object AuthCrypto {
    private val rng = SecureRandom()
    private val HEX = Regex("^[0-9a-f]+$")

    fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        rng.nextBytes(b)
        return b.toHex()
    }

    /** Código de 6 dígitos que muestra la TV. */
    fun pairingCode(): String = rng.nextInt(1_000_000).toString().padStart(6, '0')

    fun hmacHex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun pairProof(code: String, nonce: String): String = hmacHex(code, nonce)
    fun authProof(token: String, nonce: String): String = hmacHex(token, nonce)

    /** Token del servidor de carátulas: derivado del token del emparejado (estable entre reconexiones). */
    fun artTokenFor(token: String): String = hmacHex(token, "dacstage-art").take(32)

    /** Comparación en tiempo constante (MessageDigest.isEqual no corta en el primer byte distinto). */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    fun isHex(s: String, length: Int): Boolean = s.length == length && HEX.matches(s)

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String {
        val out = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xff
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0f]
        }
        return String(out)
    }

    private val DIGITS = "0123456789abcdef".toCharArray()
}

/**
 * Límite de intentos fallidos por clave (IP): [maxFailures] en [windowMs] bloquea hasta que caducan.
 * Acotado en memoria ([maxKeys]).
 */
class FailureRateLimiter(
    private val maxFailures: Int = 5,
    private val windowMs: Long = 60_000,
    private val maxKeys: Int = 256,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val failures = LinkedHashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun isBlocked(key: String): Boolean {
        val q = failures[key] ?: return false
        prune(q)
        if (q.isEmpty()) failures.remove(key)
        return q.size >= maxFailures
    }

    @Synchronized
    fun recordFailure(key: String) {
        val q = failures.getOrPut(key) { ArrayDeque() }
        prune(q)
        q.addLast(now())
        if (failures.size > maxKeys) {
            failures.entries.removeAll { prune(it.value); it.value.isEmpty() }
            while (failures.size > maxKeys) failures.remove(failures.keys.first())
        }
    }

    private fun prune(q: ArrayDeque<Long>) {
        val limit = now() - windowMs
        while (q.isNotEmpty() && q.first() < limit) q.removeFirst()
    }
}
