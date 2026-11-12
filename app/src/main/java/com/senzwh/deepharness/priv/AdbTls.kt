package com.senzwh.deepharness.priv

import android.content.Context
import org.conscrypt.Conscrypt
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Lapisan TLS untuk ADB nirkabel memakai Conscrypt mandiri (vendor) —
 * deterministik di semua ROM: TLS 1.3 + exportKeyingMaterial publik,
 * tanpa bergantung API tersembunyi Conscrypt platform.
 *
 * Sertifikat server adbd tidak diverifikasi rantai (self-signed per perangkat);
 * keamanan pairing dijaga SPAKE2 yang terikat ke materi ekspor TLS sesi ini,
 * dan keamanan connect dijaga kepemilikan kunci privat kita di adbd.
 */
object AdbTls {
    @Volatile private var cached: SSLContext? = null

    fun sslContext(ctx: Context): SSLContext {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val provider = Conscrypt.newProvider()
            val pair = AdbKeyStore.keyPair(ctx)
            val cert = AdbKeyStore.certificate(ctx)
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            ks.setKeyEntry("harness", pair.private, charArrayOf(), arrayOf(cert))
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, charArrayOf())
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sc = SSLContext.getInstance("TLSv1.3", provider)
            sc.init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), SecureRandom())
            cached = sc
            return sc
        }
    }

    fun connectSocket(ctx: Context, host: String, port: Int, timeoutMs: Int = 15000): SSLSocket {
        val plain = java.net.Socket()
        plain.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        plain.tcpNoDelay = true
        val ssl = sslContext(ctx).socketFactory.createSocket(plain, host, port, true) as SSLSocket
        ssl.enabledProtocols = arrayOf("TLSv1.3")
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        return ssl
    }

    /** 64 byte materi ekspor TLS (label "adb-label\0") — pengikat SPAKE2 ke sesi TLS ini. */
    fun exportKeyingMaterial(socket: SSLSocket): ByteArray =
        Conscrypt.exportKeyingMaterial(socket, "adb-label\u0000", null, 64)
}
