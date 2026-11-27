package com.senzwh.deepharness

// Test JVM murni untuk bug FC sesudah backup restore (G7, vc332).
// Dijalankan oleh tools/run_tests.sh.
//
// BUG (Jhon, dari HP Sendy langsung 27 Nov 07:18): sesudah restore
// harness-restore.zip (dibuat 26 Nov 17:28, era vc331), app FC tiap
// launch — ClassCastException java.lang.Integer cannot be cast to
// java.lang.Long di SharedPreferencesImpl.getLong <- MainActivity.
// buildClassicUi:1961 <- buildUi:1827 <- onCreate:1731. Baris :1961:
// currentSessionId = getSharedPreferences("harness", MODE_PRIVATE)
//   .getLong("currentSession", 1L).
// settings.json di zip berisi currentSession = 1 (int). RANTAI: backup
// serialize prefs ke JSON (angka jadi number) -> restore BrainBackup.kt
// parse org.json (angka yang muat int -> Integer) -> `is Int ->
// ed.putInt(k, v)` -> launch berikut getLong("currentSession") lempar
// ClassCastException -> FC. Kunci-long lain yang sama-sama ditulis
// restore (tokInAll/tokOutAll/tokInSess/tokOutSess, turnCkptResumedFor,
// serveF3TickTs, serveDownNotifTs, ...) tinggal menunggu giliran FC.
//
// FIX (dua lapis, keduanya perlu):
//  1. BACAAN — PrefsSafe.getLongSafe/getIntSafe: getLong, fallback
//     getInt().toLong() (dan sebaliknya). Ini satu-satunya lapis yang
//     menyembuhkan prefs yang SUDAH rusak di perangkat.
//  2. TULISAN — PrefsSafe.putRestoreNumber + PREF_LONG_KEYS/PREF_INT_KEYS:
//     BrainBackup.restore menulis angka dengan tipe kanonik kuncinya,
//     supaya restore berikutnya tidak pernah lagi menciptakan salah tipe.
//     (Termasuk clamp maxToolSteps yang dulu menulis Long.)
//
// APA YANG DITES DI SINI (jujur):
//  1. REPRO — StrictPrefs meniru SharedPreferencesImpl Android ASLI
//     (getLong atas nilai Integer melempar ClassCastException — perilaku
//     yang TIDAK ditiru FakeSharedPreferences yang permisif `as? Long`).
//     currentSession tersimpan Int -> getLong lama MELEMPAR (bug terbukti),
//     getLongSafe mengembalikan 1L (fix terbukti). Sama untuk ke-14 kunci-long.
//  2. RANTAI JSON ASLI — org.json sungguhan: JSONObject.put(Long) ->
//     toString -> parse ulang -> get() mengembalikan Integer untuk angka
//     kecil (inilah downgrade-nya) -> putRestoreNumber menulisnya kembali
//     sebagai Long untuk kunci-long / Int untuk kunci-int.
//  3. putRestoreNumber — semua kunci-long (Int/Long/Double masuk ->
//     Long keluar), semua kunci-int (Long masuk dalam jangkauan -> Int;
//     Long di luar jangkauan Int -> false + tidak menulis apa pun),
//     kunci tak dikenal (tipe dipertahankan: Int->Int, Long->Long,
//     Double->Float), awalan msgCount_ (diperlakukan sebagai kunci-Int).
//  4. Daftar kunci — PREF_LONG_KEYS/PREF_INT_KEYS sama persis dengan
//     hasil audit grep (14 kunci-long, 9 kunci-int + awalan msgCount_).
//
// APA YANG TIDAK BISA DITES DI JVM (residu):
//  - BrainBackup.restore ITU SENDIRI: butuh android.os.Environment,
//    ctx.filesDir, NotificationManager, MemoryDb, ParkedApprovalStore —
//    tidak ada stub-nya di app/src/test. Loop restore-nya tipis (satu
//    `when` yang kini memanggil putRestoreNumber yang dites di sini),
//    tapi kompilasi + perilaku ujung-ke-ujung BrainBackup.kt HANYA bisa
//    dibuktikan build APK / CI — koordinator.
//  - MainActivity.buildClassicUi:1961 itu sendiri: Activity ~18,5 rb
//    baris tidak bisa diinstansiasi di JVM. Yang dites adalah bentuk
//    panggilan persisnya (getLongSafe atas prefs berisi Int) + verifikasi
//    grep bahwa baris :1961 kini memakai getLongSafe.

import android.content.SharedPreferences
import org.json.JSONObject

private var failedG7 = 0

private fun checkG7(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("LULUS: $name")
    else {
        failedG7++
        println("GAGAL: $name -- $detail")
    }
}

/**
 * SharedPreferences yang meniru perilaku tipe Android ASLI:
 * getLong atas nilai Integer melempar ClassCastException (bukan
 * mengembalikan default diam-diam seperti FakeSharedPreferences).
 */
private class StrictPrefs : SharedPreferences {
    val data = HashMap<String, Any?>()
    override fun getString(key: String, defValue: String?): String? =
        (data[key] as? String) ?: defValue
    override fun getInt(key: String, defValue: Int): Int {
        val v = data[key] ?: return defValue
        if (v !is Int) throw ClassCastException("java.lang.${v.javaClass.simpleName} cannot be cast to java.lang.Integer")
        return v
    }
    override fun getLong(key: String, defValue: Long): Long {
        val v = data[key] ?: return defValue
        if (v !is Long) throw ClassCastException("java.lang.${v.javaClass.simpleName} cannot be cast to java.lang.Long")
        return v
    }
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        (data[key] as? Boolean) ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = StrictEditor(this)
    override val all: Map<String, *>
        get() = HashMap(data)
}

private class StrictEditor(private val prefs: StrictPrefs) : SharedPreferences.Editor {
    private val pending = HashMap<String, Any?>()
    private val removals = HashSet<String>()
    override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
    override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
    override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
    override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
    override fun remove(key: String): SharedPreferences.Editor { removals.add(key); return this }
    override fun clear(): SharedPreferences.Editor { prefs.data.clear(); return this }
    override fun apply() { commit() }
    override fun commit(): Boolean {
        for (k in removals) prefs.data.remove(k)
        prefs.data.putAll(pending)
        return true
    }
}

private val LONG_KEYS_AUDIT = listOf(
    "currentSession", "tokInAll", "tokOutAll", "tokInSess", "tokOutSess",
    "ckptPending", "ocCurrentTask", "turnCkptResumedFor", "serveF3TickTs",
    "serveDownNotifTs", "bgLastRateLimitTs", "bgDeadRetryTs",
    "govDelayNotifTs", "notifSessionId"
)
private val INT_KEYS_AUDIT = listOf(
    "msgCount", "maxToolSteps", "resultSeq", "briefingHour", "chatFontPx",
    "bubbleSizeDp", "bubbleAlphaPct", "govQuietStart", "govQuietEnd"
)

fun main() {
    // 4. Daftar kunci sama persis dengan audit.
    checkG7("PREF_LONG_KEYS == audit (14 kunci)", PREF_LONG_KEYS == LONG_KEYS_AUDIT.toSet(), "isi=$PREF_LONG_KEYS")
    checkG7("PREF_INT_KEYS == audit (9 kunci)", PREF_INT_KEYS == INT_KEYS_AUDIT.toSet(), "isi=$PREF_INT_KEYS")
    checkG7("PREF_INT_KEY_PREFIXES == {msgCount_}", PREF_INT_KEY_PREFIXES == setOf("msgCount_"), "isi=$PREF_INT_KEY_PREFIXES")

    // 1. REPRO + FIX bacaan, untuk tiap kunci-long.
    for (k in LONG_KEYS_AUDIT) {
        val p = StrictPrefs()
        p.edit().putInt(k, 7).apply() // simulasi hasil restore lama
        val oldThrows = try {
            p.getLong(k, 0); false
        } catch (e: ClassCastException) {
            true
        }
        checkG7("REPRO $k: getLong lama atas Int MELEMPAR ClassCastException", oldThrows)
        checkG7("FIX $k: getLongSafe(Int 7) == 7L", p.getLongSafe(k, 0) == 7L, "dapat=${try { p.getLongSafe(k, 0).toString() } catch (e: Exception) { "lempar ${e.javaClass.simpleName}" }}")
        val p2 = StrictPrefs()
        p2.edit().putLong(k, 1234567890123L).apply()
        checkG7("FIX $k: getLongSafe(Long besar) utuh", p2.getLongSafe(k, 0) == 1234567890123L)
        val p3 = StrictPrefs()
        checkG7("FIX $k: kunci absen -> default", p3.getLongSafe(k, 42L) == 42L)
    }
    // Bentuk persis MainActivity.kt:1961 sesudah fix.
    val startup = StrictPrefs()
    startup.edit().putInt("currentSession", 1).apply()
    val startupSession = startup.getLongSafe("currentSession", 1L)
    checkG7("STARTUP :1961: currentSession Int 1 -> dibaca 1L, tidak crash", startupSession == 1L, "dapat=$startupSession")

    // getIntSafe arah sebaliknya.
    for (k in INT_KEYS_AUDIT) {
        val p = StrictPrefs()
        p.edit().putLong(k, 77L).apply()
        checkG7("FIX $k: getIntSafe(Long 77) == 77", p.getIntSafe(k, 0) == 77, "dapat=${p.getIntSafe(k, 0)}")
        val pBig = StrictPrefs()
        pBig.edit().putLong(k, 3_000_000_000L).apply()
        checkG7("FIX $k: getIntSafe(Long di luar Int) -> default", pBig.getIntSafe(k, 9) == 9, "dapat=${pBig.getIntSafe(k, 9)}")
    }
    val pMsg = StrictPrefs()
    pMsg.edit().putLong("msgCount_1", 5L).apply()
    checkG7("FIX msgCount_1: getIntSafe(Long 5) == 5", pMsg.getIntSafe("msgCount_1", 0) == 5)

    // 2. RANTAI JSON ASLI (org.json): Long -> teks -> parse -> Integer.
    val sj = JSONObject()
    sj.put("currentSession", 1L)
    sj.put("tokInAll", 12345L)
    sj.put("serveF3TickTs", 1_700_000_000_000L) // Long besar, tetap Long
    sj.put("msgCount", 42) // Int tetap Int
    sj.put("maxToolSteps", 50)
    val parsed = JSONObject(sj.toString())
    checkG7("JSON: currentSession=1 parse-balik jadi Integer (downgrade terbukti)",
        parsed.get("currentSession") is Int, "tipe=${parsed.get("currentSession").javaClass.simpleName}")
    checkG7("JSON: tokInAll=12345 parse-balik jadi Integer",
        parsed.get("tokInAll") is Int, "tipe=${parsed.get("tokInAll").javaClass.simpleName}")
    checkG7("JSON: serveF3TickTs besar tetap Long",
        parsed.get("serveF3TickTs") is Long, "tipe=${parsed.get("serveF3TickTs").javaClass.simpleName}")
    // Umpankan hasil parse itu ke putRestoreNumber (logika yang dipakai restore).
    val restored = FakeSharedPreferences()
    val ed = restored.edit()
    for (k in listOf("currentSession", "tokInAll", "serveF3TickTs", "msgCount", "maxToolSteps")) {
        checkG7("RESTORE $k: putRestoreNumber menerima nilai parse JSON", ed.putRestoreNumber(k, parsed.get(k) as Number))
    }
    ed.apply()
    checkG7("RESTORE currentSession: tersimpan sebagai Long (bukan Int)",
        restored.data["currentSession"] is Long, "tipe=${restored.data["currentSession"]?.javaClass?.simpleName}")
    checkG7("RESTORE tokInAll: tersimpan sebagai Long",
        restored.data["tokInAll"] is Long, "tipe=${restored.data["tokInAll"]?.javaClass?.simpleName}")
    checkG7("RESTORE serveF3TickTs: tersimpan sebagai Long 1700000000000",
        restored.data["serveF3TickTs"] == 1_700_000_000_000L, "nilai=${restored.data["serveF3TickTs"]}")
    checkG7("RESTORE msgCount: tersimpan sebagai Int 42",
        restored.data["msgCount"] is Int && restored.data["msgCount"] == 42, "nilai=${restored.data["msgCount"]}")
    checkG7("RESTORE maxToolSteps: tersimpan sebagai Int 50",
        restored.data["maxToolSteps"] is Int && restored.data["maxToolSteps"] == 50, "nilai=${restored.data["maxToolSteps"]}")
    // Sesudah restore baru, bacaan ketat Android tidak lagi melempar.
    val strictAfter = StrictPrefs()
    strictAfter.data.putAll(restored.data)
    checkG7("RESTORE: getLong(currentSession) pasca-restore-baru tidak melempar",
        try { strictAfter.getLong("currentSession", 1L) == 1L } catch (e: Exception) { false })

    // 3. putRestoreNumber — kasus tepi.
    val e2 = FakeSharedPreferences()
    val ed2 = e2.edit()
    val ok2 = ed2.putRestoreNumber("ckptPending", 3)
    ed2.apply()
    checkG7("putRestoreNumber: kunci-long + Int -> true + tersimpan Long 3",
        ok2 && e2.data["ckptPending"] is Long && e2.data["ckptPending"] == 3L, "data=${e2.data}")
    val e3 = FakeSharedPreferences()
    val ed3 = e3.edit()
    val ok3 = ed3.putRestoreNumber("ocCurrentTask", 1.9)
    ed3.apply()
    checkG7("putRestoreNumber: kunci-long + Double 1.9 -> Long 1 (potong, hanya zip buatan)",
        ok3 && e3.data["ocCurrentTask"] == 1L, "data=${e3.data}")
    val e4 = FakeSharedPreferences()
    val ed4 = e4.edit()
    val ok4 = ed4.putRestoreNumber("msgCount", 3_000_000_000L)
    ed4.apply()
    checkG7("putRestoreNumber: kunci-int + Long di luar Int -> false + tidak menulis",
        !ok4 && !e4.data.containsKey("msgCount"), "data=${e4.data}")
    val e5 = FakeSharedPreferences()
    val ed5 = e5.edit()
    val ok5 = ed5.putRestoreNumber("msgCount_9", 8L)
    ed5.apply()
    checkG7("putRestoreNumber: msgCount_9 + Long 8 -> Int 8",
        ok5 && e5.data["msgCount_9"] is Int && e5.data["msgCount_9"] == 8, "data=${e5.data}")
    val e6 = FakeSharedPreferences()
    val ed6 = e6.edit()
    ed6.putRestoreNumber("kunciTakDikenal", 4)
    ed6.putRestoreNumber("kunciTakDikenal2", 5L)
    ed6.putRestoreNumber("kunciTakDikenal3", 0.5)
    ed6.apply()
    checkG7("putRestoreNumber: kunci tak dikenal Int->Int, Long->Long, Double->Float",
        e6.data["kunciTakDikenal"] is Int && e6.data["kunciTakDikenal2"] is Long &&
            e6.data["kunciTakDikenal3"] is Float && e6.data["kunciTakDikenal3"] == 0.5f,
        "data=${e6.data}")

    println(if (failedG7 == 0) "SEMUA LULUS" else "GAGAL: $failedG7 cek")
    if (failedG7 != 0) kotlin.system.exitProcess(1)
}
