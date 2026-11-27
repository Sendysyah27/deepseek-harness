package com.senzwh.deepharness

import android.content.SharedPreferences

/**
 * vc332 G7 — tipe kanonik + bacaan defensif SharedPreferences "harness".
 *
 * AKAR: backup menulis prefs ke settings.json (org.json); angka kecil yang
 * semula Long di-parse balik jadi Integer; restore lama menulisnya via
 * putInt; bacaan getLong atas nilai Integer melempar ClassCastException
 * (FC di MainActivity.buildClassicUi:1961, currentSession, 27 Nov 07:18).
 *
 * Dua lapis pertahanan, dan KEDUANYA perlu:
 *  1. BACAAN (getLongSafe/getIntSafe): menyembuhkan prefs yang SUDAH
 *     rusak di perangkat — perbaikan restore saja tidak menyembuhkan
 *     nilai Int yang sudah tertulis sebelum update ini.
 *  2. TULISAN (putRestoreNumber + PREF_LONG_KEYS/PREF_INT_KEYS):
 *     restore menulis angka dengan tipe kanonik kuncinya, supaya
 *     restore berikutnya tidak pernah lagi menciptakan salah tipe.
 */
internal fun SharedPreferences.getLongSafe(key: String, defValue: Long): Long {
    return try {
        getLong(key, defValue)
    } catch (e: Exception) {
        try {
            getInt(key, defValue.toInt()).toLong()
        } catch (e2: Exception) {
            defValue
        }
    }
}

internal fun SharedPreferences.getIntSafe(key: String, defValue: Int): Int {
    return try {
        getInt(key, defValue)
    } catch (e: Exception) {
        try {
            val v = getLong(key, defValue.toLong())
            if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else defValue
        } catch (e2: Exception) {
            defValue
        }
    }
}

/**
 * Kunci prefs "harness" yang dibaca app via getLong (audit vc332 G7 —
 * semua kunci ini ada di SETTINGS_ALLOWLIST BrainBackup, jadi BISA
 * ditulis restore; kunci-Long lain di luar allowlist tidak pernah
 * ditulis restore dan selalu ditulis app via putLong).
 */
internal val PREF_LONG_KEYS = setOf(
    "currentSession", "tokInAll", "tokOutAll", "tokInSess", "tokOutSess",
    "ckptPending", "ocCurrentTask", "turnCkptResumedFor", "serveF3TickTs",
    "serveDownNotifTs", "bgLastRateLimitTs", "bgDeadRetryTs",
    "govDelayNotifTs", "notifSessionId"
)

/**
 * Kunci prefs "harness" yang dibaca app via getInt (audit vc332 G7).
 * Semua ditulis app via putInt; restore lama bisa menulisnya Long bila
 * angka di settings.json tidak muat Int (zip buatan tangan / nilai
 * raksasa) — arah sebaliknya dari bug Long, sama-sama ClassCastException.
 */
internal val PREF_INT_KEYS = setOf(
    "msgCount", "maxToolSteps", "resultSeq", "briefingHour", "chatFontPx",
    "bubbleSizeDp", "bubbleAlphaPct", "govQuietStart", "govQuietEnd"
)

/** Awalan kunci dinamis yang dibaca via getInt (msgCount_<sessionId>). */
internal val PREF_INT_KEY_PREFIXES = setOf("msgCount_")

/**
 * Tulis satu nilai angka dari settings.json ke editor dengan tipe kanonik
 * kuncinya. Kunci di luar kedua daftar ditulis apa adanya (Int->putInt,
 * Long->putLong, Double->putFloat — sama seperti restore lama).
 * Mengembalikan false bila nilai tidak bisa diwakili (Long di luar
 * jangkauan Int untuk kunci-Int) — pemanggil harus melewati kunci itu,
 * bukan menulis tipe yang akan meledak di bacaan berikutnya.
 * Double untuk kunci-Long/Int dipotong ke Long (hanya mungkin dari zip
 * buatan tangan; backup app tidak pernah menulis Double untuk kunci ini).
 */
internal fun SharedPreferences.Editor.putRestoreNumber(key: String, value: Number): Boolean {
    return when {
        key in PREF_LONG_KEYS -> {
            putLong(key, value.toLong()); true
        }
        key in PREF_INT_KEYS || PREF_INT_KEY_PREFIXES.any { key.startsWith(it) } -> {
            val v = value.toLong()
            if (v in Int.MIN_VALUE..Int.MAX_VALUE) {
                putInt(key, v.toInt()); true
            } else {
                false
            }
        }
        value is Int -> {
            putInt(key, value); true
        }
        value is Long -> {
            putLong(key, value); true
        }
        value is Double -> {
            putFloat(key, value.toFloat()); true
        }
        else -> {
            putLong(key, value.toLong()); true
        }
    }
}
