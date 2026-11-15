package com.senzwh.deepharness

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Brankas kredensial: AES-256/GCM dengan kunci di Android Keystore (tidak pernah keluar dari HP).
 *  Dipakai untuk app password Gmail dsb — bukan untuk token DeepSeek (token itu tetap aturan lama:
 *  dibaca segar dari WebView tiap panggilan, tidak pernah disimpan). */
object SecureStore {

    private const val PREFS = "harness_secure"
    private const val ALIAS = "harness_secure_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(ctx: Context, name: String, value: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ct, Base64.NO_WRAP)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(name, blob).apply()
        } catch (e: Exception) {}
    }

    fun get(ctx: Context, name: String): String? {
        return try {
            val blob = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return null
            val parts = blob.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    fun has(ctx: Context, name: String): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(name)

    fun delete(ctx: Context, name: String) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(name).apply()
        } catch (e: Exception) {}
    }
}
