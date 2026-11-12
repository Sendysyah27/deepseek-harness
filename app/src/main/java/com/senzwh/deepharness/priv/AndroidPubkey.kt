package com.senzwh.deepharness.priv

import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

/**
 * Format kunci publik ADB (AOSP android_pubkey): struktur biner little-endian
 * [modulus_size_words][n0inv][modulus LE][rr LE][exponent], dan bentuk teks
 * base64 + " nama\0" yang dipakai PeerInfo pairing & AUTH RSAPUBLICKEY.
 * Ditulis dari spesifikasi format AOSP (interop), bukan menyalin kode pihak ketiga.
 */
object AndroidPubkey {
    private const val MODULUS_SIZE = 2048 / 8 // 256
    private const val ENCODED_SIZE = 3 * 4 + 2 * MODULUS_SIZE // 524
    private const val MODULUS_WORDS = MODULUS_SIZE / 4 // 64

    // Awalan blok PKCS#1 v1.5 + DigestInfo SHA-1 untuk "digest" 20 byte (token AUTH):
    // 0x00 0x01 FF..FF 0x00 30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14 (total 236 byte).
    private val SIGNATURE_PADDING: ByteArray = run {
        val head = byteArrayOf(0x00, 0x01)
        val ff = ByteArray(236 - head.size - 16) { 0xFF.toByte() }
        val tail = byteArrayOf(
            0x00, 0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        head + ff + tail
    }

    fun encode(publicKey: RSAPublicKey): ByteArray {
        val buf = ByteBuffer.allocate(ENCODED_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MODULUS_WORDS)
        val r32 = BigInteger.ZERO.setBit(32)
        var n0inv = publicKey.modulus.mod(r32).modInverse(r32)
        n0inv = r32.subtract(n0inv)
        buf.putInt(n0inv.toInt())
        buf.put(bigToLittlePadded(MODULUS_SIZE, publicKey.modulus))
        var rr = BigInteger.ZERO.setBit(MODULUS_SIZE * 8)
        rr = rr.modPow(BigInteger.valueOf(2), publicKey.modulus)
        buf.put(bigToLittlePadded(MODULUS_SIZE, rr))
        buf.putInt(publicKey.publicExponent.toInt())
        return buf.array()
    }

    /** Bentuk teks: base64(struktur) + " nama\0", total 700 + nama + 2 byte. */
    fun encodeWithName(publicKey: RSAPublicKey, name: String): ByteArray {
        val b64 = Base64.encode(encode(publicKey), Base64.NO_WRAP)
        val suffix = " $name\u0000".toByteArray(Charsets.UTF_8)
        return b64 + suffix
    }

    /** Tanda tangan token AUTH ala adb: RSA op privat atas (padding || token 20 byte). */
    fun signToken(privateKey: PrivateKey, token: ByteArray): ByteArray {
        val block = SIGNATURE_PADDING + token
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(block)
    }

    private fun bigToLittlePadded(len: Int, v: BigInteger): ByteArray {
        val out = ByteArray(len)
        val be = v.toByteArray()
        // Buang byte tanda di depan bila membuat array lebih panjang dari nilai sebenarnya.
        val trimmed = if (be.size > 1 && be[0].toInt() == 0) be.copyOfRange(1, be.size) else be
        val le = trimmed.reversedArray()
        System.arraycopy(le, 0, out, 0, minOf(len, le.size))
        return out
    }
}
