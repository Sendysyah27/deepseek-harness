package com.senzwh.deepharness.priv

import android.content.Context
import com.flyfish233.crypto.spake2.Spake2Context
import com.flyfish233.crypto.spake2.Spake2Role
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * S3 — Pairing ADB nirkabel MANDIRI (protokol AOSP pairing_auth):
 * TLS 1.3 (Conscrypt vendor) → materi ekspor TLS ditempel ke kode pairing →
 * SPAKE2 (Alice) → HKDF-SHA256 → AES-128-GCM → tukar PeerInfo (kunci publik kita).
 * Sekali berhasil, adbd menyimpan kunci harness — connect berikutnya tanpa kode.
 */
object AdbPairing {
    private val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
    private val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.UTF_8)
    private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)
    private const val HEADER_SIZE = 6
    private const val TYPE_SPAKE2_MSG: Byte = 0
    private const val TYPE_PEER_INFO: Byte = 1
    private const val MAX_PEER_INFO = 8192

    /** Hasil pairing: teks siap tampil (sukses/gagal + sebab). Blocking — panggil dari thread. */
    fun pair(ctx: Context, port: Int, code: String, host: String = "127.0.0.1"): String {
        if (port !in 1..65535) return "Port pairing tidak valid."
        if (code.length != 6 || !code.all { it.isDigit() }) return "Kode pairing harus 6 digit."
        var ssl: javax.net.ssl.SSLSocket? = null
        try {
            ssl = AdbTls.connectSocket(ctx, host, port, 20000)
            val input = DataInputStream(ssl.inputStream)
            val output = DataOutputStream(ssl.outputStream)

            val exported = AdbTls.exportKeyingMaterial(ssl)
            val password = code.toByteArray(Charsets.UTF_8) + exported

            val spake = Spake2Context(Spake2Role.Alice, CLIENT_NAME, SERVER_NAME)
            val myMsg = spake.generateMessage(password)
            writePacket(output, TYPE_SPAKE2_MSG, myMsg)
            val (type1, theirMsg) = readPacket(input)
            if (type1 != TYPE_SPAKE2_MSG) return "Pairing gagal: balasan server bukan pesan SPAKE2."
            val keyMaterial = spake.processMessage(theirMsg)
                ?: return "Pairing GAGAL — kode pairing salah atau sudah kedaluwarsa. Buka lagi menu 'Pair device with pairing code' lalu coba cepat."
            spake.destroy()

            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(keyMaterial, null, HKDF_INFO))
            val secretKey = ByteArray(16)
            hkdf.generateBytes(secretKey, 0, secretKey.size)

            var encIv = 0L
            var decIv = 0L
            fun gcm(forEncryption: Boolean, data: ByteArray, ivCounter: Long): ByteArray {
                val iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(ivCounter).array()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    if (forEncryption) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                    SecretKeySpec(secretKey, "AES"), GCMParameterSpec(128, iv)
                )
                return cipher.doFinal(data)
            }

            val peerInfo = ByteArray(MAX_PEER_INFO)
            peerInfo[0] = 0 // ADB_RSA_PUB_KEY
            val keyData = AndroidPubkey.encodeWithName(AdbKeyStore.publicKey(ctx), AdbKeyStore.KEY_NAME)
            System.arraycopy(keyData, 0, peerInfo, 1, minOf(keyData.size, MAX_PEER_INFO - 1))
            val encrypted = gcm(true, peerInfo, encIv++)
            writePacket(output, TYPE_PEER_INFO, encrypted)

            val (type2, theirInfoEnc) = readPacket(input)
            if (type2 != TYPE_PEER_INFO) return "Pairing gagal: balasan PeerInfo tidak diterima."
            val theirInfo = try {
                gcm(false, theirInfoEnc, decIv++)
            } catch (e: Exception) {
                return "Pairing GAGAL — dekripsi PeerInfo gagal (kode salah/kedaluwarsa)."
            }
            if (theirInfo.size != MAX_PEER_INFO) return "Pairing gagal: ukuran PeerInfo server janggal."

            ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).edit()
                .putBoolean("privPaired", true)
                .putLong("privPairedAt", System.currentTimeMillis())
                .apply()
            return "PAIRING BERHASIL ✅ Kunci harness (${AdbKeyStore.KEY_NAME}) sekarang terdaftar di adbd HP ini. " +
                "Mode ADB bisa dinyalakan sendiri oleh harness (Setup 38 / otomatis) — tanpa Termux, tanpa Shizuku."
        } catch (e: Exception) {
            return "Pairing gagal: ${e.javaClass.simpleName}: ${e.message ?: "(tanpa pesan)"}. " +
                "Pastikan menu Wireless debugging terbuka & kode masih tampil saat pairing dijalankan."
        } finally {
            try { ssl?.close() } catch (_: Exception) {}
        }
    }

    // vc171: coba kandidat host berurutan — host NSD (tersegar) →
    // host tersimpan (pernah ditempel user) → loopback (terbukti mati di
    // HP Sendy, jadi terakhir). Hanya gagal level KONEKSI yang lanjut ke
    // kandidat berikut; kode salah/kedaluwarsa langsung kembali (tidak
    // ada gunanya mencoba host lain dengan kode yang sama). Sukses via
    // host non-loopback ditandai "[via <host>]" untuk diagnosis.
    fun pairAuto(ctx: Context, port: Int, code: String, nsdHost: String = ""): String {
        val stored = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getString("privAdbHost", "") ?: ""
        val candidates = listOf(nsdHost, stored, "127.0.0.1").filter { it.isNotBlank() }.distinct()
        var last = ""
        for (h in candidates) {
            val r = pair(ctx, port, code, h)
            if (r.startsWith("PAIRING BERHASIL")) return if (h == "127.0.0.1") r else "$r [via $h]"
            last = r
            val connFail = listOf("ConnectException", "SocketTimeoutException", "UnknownHostException", "SSLHandshakeException", "SSLException", "SocketException").any { r.contains(it) }
            if (!connFail) return r
        }
        return "$last [host dicoba: ${candidates.joinToString()}]"
    }

    fun isPaired(ctx: Context): Boolean =
        ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getBoolean("privPaired", false)

    private fun writePacket(out: DataOutputStream, type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.put(1.toByte()).put(type).putInt(payload.size)
        out.write(header.array())
        out.write(payload)
        out.flush()
    }

    private fun readPacket(input: DataInputStream): Pair<Byte, ByteArray> {
        val header = ByteArray(HEADER_SIZE)
        input.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get()
        val type = buf.get()
        val size = buf.int
        require(version.toInt() == 1) { "versi header pairing tidak didukung: $version" }
        require(size in 1..(2 * MAX_PEER_INFO)) { "ukuran payload pairing janggal: $size" }
        val payload = ByteArray(size)
        input.readFully(payload)
        return type to payload
    }
}
