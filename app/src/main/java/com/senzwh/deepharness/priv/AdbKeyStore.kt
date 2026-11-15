package com.senzwh.deepharness.priv

import android.content.Context
import org.bouncycastle.x509.X509V3CertificateGenerator
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Kunci RSA 2048 MILIK harness untuk ADB (pairing + connect TLS).
 * Disimpan di filesDir aplikasi (privat): adb_key.pk8 (kunci privat PKCS#8)
 * dan adb_cert.der (sertifikat self-signed). Dibuat sekali, dipakai selamanya —
 * inilah identitas yang didaftarkan ke adbd saat pairing S3.
 */
object AdbKeyStore {
    const val KEY_NAME = "harness@senz"
    private const val PRIV_FILE = "adb_key.pk8"
    private const val CERT_FILE = "adb_cert.der"

    private var cachedPair: KeyPair? = null
    private var cachedCert: X509Certificate? = null

    /**
     * Daftarkan BouncyCastle PENUH (vendor di APK) sebagai SATU-SATUNYA
     * provider "BC". Pelajaran 2026-10-26 (vc171): guard lama (lewati bila
     * nama kelas provider sama) GAGAL di ROM ini — stub BC bawaan Android
     * tetap menjawab nama "BC" sehingga generator sertifikat melempar
     * NoSuchAlgorithmException SHA256WithRSAEncryption for provider BC,
     * padahal TCP ke host NSD sudah tersambung. Karena itu: HAPUS dulu
     * provider "BC" apa pun, SISIPKAN milik kita di posisi 1, lalu
     * VERIFIKASI (provider yang menjawab nama "BC" harus instans kita,
     * dan Signature yang dipakai generator harus bisa dibuat). Gagal di
     * sini = IllegalStateException dengan sebab jelas (tidak lagi
     * ditelan diam-diam lalu meledak sebagai error kripto misterius).
     */
    private fun ensureFullBc() {
        val ours = org.bouncycastle.jce.provider.BouncyCastleProvider()
        java.security.Security.removeProvider("BC")
        java.security.Security.insertProviderAt(ours, 1)
        val active = java.security.Security.getProvider("BC")
        if (active !== ours) {
            throw IllegalStateException(
                "BC penuh tidak aktif: yang menjawab nama BC = " +
                    (active?.javaClass?.name ?: "tidak ada")
            )
        }
        try {
            java.security.Signature.getInstance("SHA256WithRSAEncryption", "BC")
        } catch (e: Exception) {
            throw IllegalStateException("BC penuh tanpa SHA256WithRSAEncryption: $e", e)
        }
    }

    @Synchronized
    fun keyPair(ctx: Context): KeyPair {
        ensureFullBc()
        cachedPair?.let { return it }
        val privFile = File(ctx.filesDir, PRIV_FILE)
        val certFile = File(ctx.filesDir, CERT_FILE)
        if (privFile.exists() && certFile.exists()) {
            try {
                val privBytes = privFile.readBytes()
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certFile.inputStream()) as X509Certificate
                val pair = KeyPair(cert.publicKey, priv)
                cachedPair = pair; cachedCert = cert
                return pair
            } catch (e: Exception) {
                // File korup → buat ulang di bawah.
            }
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val cert = makeSelfSigned(pair)
        privFile.writeBytes(pair.private.encoded)
        certFile.writeBytes(cert.encoded)
        cachedPair = pair; cachedCert = cert
        return pair
    }

    @Synchronized
    fun certificate(ctx: Context): X509Certificate {
        cachedCert?.let { return it }
        keyPair(ctx)
        return cachedCert!!
    }

    fun publicKey(ctx: Context): RSAPublicKey = keyPair(ctx).public as RSAPublicKey
    fun privateKey(ctx: Context): PrivateKey = keyPair(ctx).private

    @Suppress("DEPRECATION")
    private fun makeSelfSigned(pair: KeyPair): X509Certificate {
        val gen = X509V3CertificateGenerator()
        val dn = X500Principal("CN=harness")
        gen.setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
        gen.setIssuerDN(dn)
        gen.setSubjectDN(dn)
        gen.setNotBefore(Date(System.currentTimeMillis() - 24L * 3600 * 1000))
        gen.setNotAfter(Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000))
        gen.setPublicKey(pair.public)
        gen.setSignatureAlgorithm("SHA256WithRSAEncryption")
        return gen.generateX509Certificate(pair.private)
    }

    /** Rekonstruksi kunci publik RSA dari modulus+eksponen (untuk verifikasi mandiri). */
    fun rsaPublic(modulus: BigInteger, exponent: BigInteger): RSAPublicKey =
        KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
}
