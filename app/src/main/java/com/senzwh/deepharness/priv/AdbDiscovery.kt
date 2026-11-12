package com.senzwh.deepharness.priv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Penemu port Wireless debugging lewat NSD/mDNS lokal:
 * "_adb-tls-pairing._tcp" (port pairing — hanya tampil saat menu pairing terbuka)
 * dan "_adb-tls-connect._tcp" (port connect — tampil selama Wireless debugging aktif).
 */
object AdbDiscovery {
    const val TYPE_PAIRING = "_adb-tls-pairing._tcp"
    const val TYPE_CONNECT = "_adb-tls-connect._tcp"

    /** Cari port untuk tipe layanan; -1 bila tidak ketemu dalam timeout. Blocking. */
    fun findPort(ctx: Context, serviceType: String, timeoutMs: Long = 9000): Int =
        findService(ctx, serviceType, timeoutMs).second

    /** vc171: cari host + port layanan; ("", -1) bila tidak ketemu. Blocking.
     *  Host = alamat IP hasil resolve NSD (biasanya IP perangkat di interface
     *  mDNS) — dipakai pairing/connect agar tidak bergantung pada loopback. */
    fun findService(ctx: Context, serviceType: String, timeoutMs: Long = 9000): Pair<String, Int> {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val latch = CountDownLatch(1)
        val foundPort = java.util.concurrent.atomic.AtomicInteger(-1)
        val foundHost = java.util.concurrent.atomic.AtomicReference("")
        val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                if (foundPort.get() > 0 || !resolving.compareAndSet(false, true)) return
                try {
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            foundPort.set(info.port)
                            foundHost.set(info.host?.hostAddress ?: "")
                            latch.countDown()
                        }
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                            resolving.set(false)
                        }
                    })
                } catch (e: Exception) {
                    resolving.set(false)
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // NSD tidak tersedia → pemanggil memakai jalur manual.
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        }
        return foundHost.get() to foundPort.get()
    }
}
