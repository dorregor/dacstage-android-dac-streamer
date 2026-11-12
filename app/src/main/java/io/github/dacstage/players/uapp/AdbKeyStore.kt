package io.github.dacstage.players.uapp

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Clave RSA + certificado autofirmado del cliente ADB local, en almacenamiento privado de la app
 * (PKCS#8 + DER). Es la identidad que el usuario empareja una vez en "Depuración inalámbrica".
 */
internal class AdbKeyStore(private val dir: File) {

    data class Identity(val privateKey: PrivateKey, val certificate: X509Certificate)

    private val keyFile get() = File(dir, "adb_key.pk8")
    private val certFile get() = File(dir, "adb_cert.der")

    @Synchronized
    fun loadOrCreate(): Identity = load() ?: create()

    @Synchronized
    fun load(): Identity? = runCatching {
        if (!keyFile.isFile || !certFile.isFile) return null
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val cert = certFile.inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        Identity(key, cert)
    }.getOrNull()

    @Synchronized
    fun delete() {
        keyFile.delete()
        certFile.delete()
    }

    private fun create(): Identity {
        dir.mkdirs()
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, SecureRandom())
        val pair = gen.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=DAC Stage")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - DAY_MS),
            Date(now + 30L * 365 * DAY_MS),
            name,
            pair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        writeAtomically(keyFile, pair.private.encoded)
        writeAtomically(certFile, cert.encoded)
        return Identity(pair.private, cert)
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
