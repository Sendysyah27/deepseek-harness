package com.senzwh.deepharness.priv

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

/**
 * S3 — Klien ADB-over-TLS: konek ke port connect Wireless debugging (loopback),
 * otentikasi pakai kunci RSA harness (AUTH tanda tangan token / kirim kunci publik),
 * lalu jalankan SATU perintah shell (OPEN shell:...) dan kumpulkan outputnya.
 * Dipakai menyalakan server Mode ADB tanpa Termux sama sekali.
 */
object AdbClient {
    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_CLSE = 0x45534c43
    private const val A_WRTE = 0x45545257
    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3
    private const val VERSION = 0x01000001
    private const val MAX_DATA = 4096

    private class Msg(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    /** Jalankan perintah shell satu kali; kembalikan output (atau teks diawali "ERROR:"). Blocking. */
    fun shell(ctx: Context, port: Int, command: String, timeoutMs: Int = 25000, host: String = "127.0.0.1"): String {
        var ssl: SSLSocket? = null
        try {
            ssl = AdbTls.connectSocket(ctx, host, port, timeoutMs)
            val input = DataInputStream(ssl.inputStream)
            val output = DataOutputStream(ssl.outputStream)

            writeMsg(output, A_CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray(Charsets.UTF_8))
            var connected = false
            var sigSent = false
            var keySent = false
            var guard = 0
            while (!connected) {
                if (++guard > 12) return "ERROR: negosiasi ADB berputar (terlalu banyak ronde AUTH)."
                val msg = readMsg(input) ?: return "ERROR: koneksi ADB ditutup server saat negosiasi."
                when (msg.command) {
                    A_CNXN -> connected = true
                    A_AUTH -> {
                        if (msg.arg0 != AUTH_TOKEN) return "ERROR: tipe AUTH tak dikenal (${msg.arg0})."
                        when {
                            !sigSent -> {
                                val sig = AndroidPubkey.signToken(AdbKeyStore.privateKey(ctx), msg.payload)
                                writeMsg(output, A_AUTH, AUTH_SIGNATURE, 0, sig)
                                sigSent = true
                            }
                            !keySent -> {
                                val pub = AndroidPubkey.encodeWithName(AdbKeyStore.publicKey(ctx), AdbKeyStore.KEY_NAME)
                                writeMsg(output, A_AUTH, AUTH_RSAPUBLICKEY, 0, pub)
                                keySent = true
                            }
                            else -> return "ERROR: kunci harness DITOLAK adbd. Pairing ulang (Setup 37) — mungkin daftar perangkat terpasang di Wireless debugging pernah di-reset."
                        }
                    }
                    else -> return "ERROR: pesan tak terduga saat negosiasi (cmd=${Integer.toHexString(msg.command)})."
                }
            }

            val localId = 1
            writeMsg(output, A_OPEN, localId, 0, "shell:$command\u0000".toByteArray(Charsets.UTF_8))
            val out = StringBuilder()
            var remoteId = 0
            guard = 0
            while (true) {
                if (++guard > 4000) break
                val msg = readMsg(input) ?: break
                when (msg.command) {
                    A_OKAY -> remoteId = msg.arg0
                    A_WRTE -> {
                        out.append(String(msg.payload, Charsets.UTF_8))
                        writeMsg(output, A_OKAY, localId, msg.arg0, ByteArray(0))
                    }
                    A_CLSE -> {
                        writeMsg(output, A_CLSE, localId, msg.arg0, ByteArray(0))
                        break
                    }
                }
                if (out.length > 60000) break
            }
            return out.toString().trim()
        } catch (e: Exception) {
            return "ERROR: ${e.javaClass.simpleName}: ${e.message ?: "(tanpa pesan)"}"
        } finally {
            try { ssl?.close() } catch (_: Exception) {}
        }
    }

    private fun writeMsg(out: DataOutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        var checksum = 0
        for (b in payload) checksum += b.toInt() and 0xFF
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command).putInt(arg0).putInt(arg1)
            .putInt(payload.size).putInt(checksum).putInt(command.inv())
        out.write(header.array())
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readMsg(input: DataInputStream): Msg? {
        val header = ByteArray(24)
        try {
            input.readFully(header)
        } catch (e: Exception) {
            return null
        }
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int; val arg0 = buf.int; val arg1 = buf.int
        val len = buf.int; buf.int; buf.int // checksum + magic dilewati
        if (len < 0 || len > 1_000_000) return null
        val payload = ByteArray(len)
        if (len > 0) input.readFully(payload)
        return Msg(command, arg0, arg1, payload)
    }
}
