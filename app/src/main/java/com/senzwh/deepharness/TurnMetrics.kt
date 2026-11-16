package com.senzwh.deepharness

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/** v4.48.6 (Butir 2): metrik per giliran + guard total prompt + klasifikasi
 *  bukti untuk JEJAK. Snapshot giliran terakhir disimpan di prefs file
 *  "turnMetrics" (TERPISAH dari prefs "harness") sehingga tidak pernah ikut
 *  tersalin oleh BrainBackup / sync_memory — snapshot metrik bukan memori
 *  otak dan bukan riwayat kerja. Semua char di sini TERUKUR (jumlah char
 *  yang benar-benar dipotong/dihitung di kode); yang tidak terukur
 *  dilaporkan literal "tidak terukur", tidak pernah dikarang. */
object TurnMetrics {
    private const val PREFS = "turnMetrics"
    private const val KEY_LAST = "last"
    private const val KEY_CAP = "maxPromptChars"
    // 4.48.9 giliran_ke: counter persisten (tidak pernah di-reset Setup 17 —
    // clearSnapshot hanya menghapus KEY_LAST), naik tiap beginTurn.
    private const val KEY_TURN = "giliran_ke"
    // vc316 IXL: 200.000 char ≈ 58.800 token (@3,4 char/token, kalibrasi
    // DsApiClient.recordTokens) — DI BAWAH buffer konteks chat web
    // DeepSeek ~64.000 token (batas yang mengikat di chat.deepseek.com;
    // API V3 128k / V4 1M lebih longgar). Cap lama 400.000 char
    // (≈117.600 token) berada DI ATAS batas server, jadi guard lokal
    // tidak pernah bisa mencegah input_exceeds_limit — prompt yang
    // lolos guard pun ditolak server. Cap ini per-prompt; akumulasi
    // antar-POST di sesi server yang sama ditangani lanjutan delta
    // (MainActivity.sendContinuation + BgBrain.continuationPrompt).
    // KEY_CAP tidak pernah ditulis UI/kode lain, jadi nilai ini berlaku
    // untuk semua instalasi (bukan hanya instalasi baru).
    const val DEFAULT_CAP = 200000

    // Lapisan prompt yang diukur (7 lapis, sesuai brief Butir 2).
    const val L_SYSTEM = "sistem_minus_katalog"
    const val L_CATALOG = "katalog"
    const val L_MEMORY = "memori"
    const val L_HISTORY = "riwayat"
    const val L_TRACE = "jejak"
    const val L_DIGEST = "digest"
    const val L_RESULT = "hasil_tool"
    // vc238 M14: lapisan Monitor baru. semantik = extra semantik yang
    // benar-benar dikirim (0 bila dibuang atau tiada); lainnya = residu
    // total dikurangi semua lapisan bernama, sehingga jumlah lapisan
    // SAMA PERSIS dengan total yang dikirim (bentuk selisih eksplisit).
    const val L_SEMANTIK = "semantik"
    const val L_OTHER = "lainnya"
    // Lapisan baru 4.48.8 — disimpan sebagai field snapshot TERPISAH (bukan di map lapisan) agar total tidak double-count persona.
    const val L_PERSONA = "persona"
    const val L_PLAN = "rencana"
    const val L_TRAILER = "trailer"

    private var begun = false
    private var startMs = 0L
    private var layers: Map<String, Int> = emptyMap()
    private var truncatedChars = 0
    private var steps = 0
    private var unknownCount = 0
    private val seenKeys = HashSet<String>()
    private var repeats = 0
    private var totalPrompt = 0
    private var extraPersona = 0
    private var extraPlan = 0
    private var extraTrailer = 0
    private var prootCutChars = 0
    private var jejakDroppedN = 0
    private var jejakDroppedChars = 0
    private var turnNo = 0
    // vc271 O5: metrik antrean request (lihat ApiRequestQueue).
    private var queueCount = 0
    private var queueWaitMsTotal = 0L
    private var queueRejected = 0
    // vc278 O19(c): char yang dipangkas pemangkas PROAKTIF per giliran
    // (bukan guard darurat) — ikut snapshot JSON giliran.
    private var proactiveTrimChars = 0

    fun beginTurn(ctx: Context) {
        begun = true
        startMs = System.currentTimeMillis()
        turnNo = try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val n = p.getInt(KEY_TURN, 0) + 1
            p.edit().putInt(KEY_TURN, n).apply()
            n
        } catch (e: Exception) { turnNo + 1 }
        layers = emptyMap()
        truncatedChars = 0
        steps = 0
        unknownCount = 0
        seenKeys.clear()
        repeats = 0
        totalPrompt = 0
        extraPersona = 0
        extraPlan = 0
        extraTrailer = 0
        prootCutChars = 0
        jejakDroppedN = 0
        jejakDroppedChars = 0
        queueCount = 0
        queueWaitMsTotal = 0L
        queueRejected = 0
        proactiveTrimChars = 0
    }

    fun isBegun(): Boolean = begun

    /** Nomor giliran berjalan (0 bila belum ada beginTurn) — dipakai header
     *  dump prompt agar pasangan Monitor↔dump bisa dicocokkan. */
    fun currentTurnNo(): Int = turnNo

    /** Catat lapisan prompt yang TERAKHIR dibangun (awal atau continuation
     *  terbaru menang). Lapisan yang tidak ada di prompt itu = 0. */
    fun recordPrompt(l: Map<String, Int>) { if (begun) layers = l }

    /** Satu langkah tool. prootCut = char yang dipotong ProotSession dari
     *  hasil run_terminal (dibaca dari penanda "tengah dipotong"), 0 bila
     *  tidak ada. Char terpotong proot dihitung ke truncatedChars. */
    fun recordStep(tool: String, isUnknown: Boolean, prootCut: Int = 0, argsHash: Int = 0, isAutoRetry: Boolean = false) {
        if (!begun) return
        steps++
        if (isUnknown) unknownCount++
        else {
            val key = "$tool|$argsHash"
            if (key in seenKeys && !isAutoRetry) repeats++
            seenKeys.add(key)
        }
        if (prootCut > 0) { prootCutChars += prootCut; truncatedChars += prootCut }
    }

    fun recordPromptTotal(n: Int) { if (begun) totalPrompt = n }

    fun recordExtra(persona: Int, plan: Int, trailer: Int) { if (begun) { extraPersona = persona; extraPlan = plan; extraTrailer = trailer } }

    /** Char yang dipotong guard prompt (trim) — hanya yang benar-benar
     *  dipotong guard, bukan estimasi. */
    fun recordGuardTrim(chars: Int) { if (begun && chars > 0) truncatedChars += chars }

    fun recordJejakDropped(n: Int, chars: Int) { if (begun) { jejakDroppedN = n; jejakDroppedChars = chars } }

    /** vc271 O5: metrik antrean request — berapa kali antre + total lama
     *  menunggu (ms) + berapa kali ditolak karena antre penuh/timeout.
     *  Dicatat saat waiter mulai dieksekusi / ditolak; ikut snapshot JSON
     *  giliran (finish) seperti metrik lain. */
    fun recordQueueWait(waitedMs: Long) { if (begun) { queueCount++; queueWaitMsTotal += waitedMs } }
    fun recordQueueRejected() { if (begun) queueRejected++ }

    /** vc278 O19: ambang proaktif pemangkas prompt = 80% cap (160.000
     *  char pada cap default vc316 200.000; dulu 320.000 pada cap
     *  400.000). Dipakai 1-1 (sendContinuation) dan latar (BgBrain)
     *  supaya ambangnya selaras. */
    fun proactiveThreshold(cap: Int): Int = (cap * 4) / 5

    // ---- vc321 IXL (fix room-lane + sesi panjang 1-1) ----
    // AKAR (diukur probe vc321, default/seed): parent_message_id SELALU
    // NULL + sesi server dipakai ulang per (akun/slot, sesi harness) —
    // konteks server tumbuh ~1 prompt penuh per POST. Prompt awal 1-1
    // sesi panjang ~90.500 char (~26.600 token) sehingga kumulatif
    // menembus buffer server ~64.000 token (~217.600 char) di giliran
    // ke-3, bahkan untuk tool sepele ("cek skill lists"). Kunci sesi
    // tersimpan di SharedPreferences "harness" (dsSrvSessId_<fp>_<no> /
    // dsSlotSrv_<slot>_<no>) — bertahan melewati tutup-buka app, itu
    // kenapa keluar-masuk app TIDAK me-reset akumulasi.
    // PERBAIKAN: (a) akumulasi per sesi server dihitung (char prompt
    // sukses) dan sesi di-reset PROAKTIF sebelum POST yang akan
    // menembus cap — prompt awal 1-1/room SELALU membawa ulang system
    // prompt + riwayat, jadi wadah baru tidak kehilangan konteks yang
    // tidak bisa dikirim ulang; (b) riwayat awal sesi panjang dipangkas
    // lebih keras (30 pesan/30.000 char bila sesi sudah >=40 pesan).
    /** Ambang sesi panjang: jumlah pesan user sesi ini SEBELUM giliran ini. */
    const val LONG_SESSION_MSG_THRESHOLD_VC321 = 40
    /** Anggaran riwayat awal sesi panjang (vs normal 60 pesan/60.000 char). */
    const val HISTORY_LONG_N_VC321 = 30
    const val HISTORY_LONG_CHARS_VC321 = 30000
    /** Anggaran riwayat awal sesi normal/pendek — TIDAK berubah dari vc230. */
    const val HISTORY_NORMAL_N_VC321 = 60
    const val HISTORY_NORMAL_CHARS_VC321 = 60000

    /** Anggaran [RIWAYAT] prompt awal dari jumlah pesan user sesi ini
     *  sebelum giliran ini. Murni, tanpa Context — bisa diuji di JVM.
     *  Sesi pendek/normal: (60, 60000) persis seperti vc230; sesi
     *  panjang (>=40 pesan): (30, 30000) — hemat 30.000 char per POST. */
    fun historyBudgetVc321(priorMsgCount: Int): Pair<Int, Int> =
        if (priorMsgCount >= LONG_SESSION_MSG_THRESHOLD_VC321) Pair(HISTORY_LONG_N_VC321, HISTORY_LONG_CHARS_VC321)
        else Pair(HISTORY_NORMAL_N_VC321, HISTORY_NORMAL_CHARS_VC321)

    /** Keputusan reset sesi server (murni, tanpa Context). Reset bila
     *  akumulasi yang sudah terkirim + prompt berikut akan menembus
     *  DEFAULT_CAP (200.000 char ~= 58.800 token, di bawah buffer
     *  server ~64.000 token). accum==0 tidak pernah reset (prompt
     *  tunggal di bawah cap selalu lolos — sesi baru tidak dibakar
     *  untuk satu POST besar). Batas atas laju reset: satu wadah baru
     *  per giliran bila tiap prompt di cap; tipikal ~1 reset per 3-5
     *  giliran sesi panjang (prompt awal baru ~60.500 char). */
    fun shouldResetServerSessionVc321(accumChars: Int, nextPromptChars: Int): Boolean =
        accumChars > 0 && accumChars + nextPromptChars > DEFAULT_CAP

    private const val KEY_SRV_ACCUM_VC321 = "srvAccumVc321_"

    /** Estimasi akumulasi server sesi ini (jumlah char prompt sukses
     *  yang sudah dikirim ke wadah server yang sama). 0 = belum ada /
     *  sesudah reset. Disimpan di prefs "turnMetrics" (bukan "harness")
     *  supaya tidak ikut tersalin BrainBackup/sync_memory. */
    fun serverAccumVc321(ctx: Context, key: String): Int = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SRV_ACCUM_VC321 + key, 0).coerceAtLeast(0)
    } catch (e: Exception) { 0 }

    fun addServerAccumVc321(ctx: Context, key: String, chars: Int) {
        if (chars <= 0) return
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cur = p.getInt(KEY_SRV_ACCUM_VC321 + key, 0).coerceAtLeast(0)
            // Jepit 10 juta supaya tidak overflow bila prefs korup.
            p.edit().putInt(KEY_SRV_ACCUM_VC321 + key, (cur + chars).coerceAtMost(10_000_000)).apply()
        } catch (e: Exception) { }
    }

    fun resetServerAccumVc321(ctx: Context, key: String) {
        try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SRV_ACCUM_VC321 + key).apply() } catch (e: Exception) { }
    }

    // ---- vc322 SESI (fix bocor sesi server: 1 giliran = 1 sesi baru) ----
    // AKAR (diukur probe vc322, UKURAN-SESI-VC322.md): guard reset vc321
    // bekerja benar, tapi prompt PENUH dikirim ulang tiap giliran —
    // begitu prompt nyata >100.000 char (DB Sendy, bukan seed 60.500),
    // accum + prompt berikut > 200.000 DI TIAP giliran: 4 perintah =
    // 4 sesi server baru (ambang terukur persis: P=100.000 -> 2 sesi,
    // P=100.001 -> 4 sesi). Memangkas riwayat saja TIDAK menolong
    // (75.500 char tetap 5 sesi / 10 giliran, sama seperti 90.500).
    // PERBAIKAN: giliran 1-1 lanjutan dikirim sebagai DELTA (jangkar
    // vc316 + perintah user verbatim + work-notes segar, ~1-2k char)
    // karena sesi server MENYIMPAN konteks antar POST — mekanisme yang
    // sama sudah terbukti di lanjutan tool vc316 + delta room vc321.
    // Prompt PENUH hanya dikirim bila wadah belum ada / baru lahir /
    // baru di-reset / pengganti / forceFresh. "Primed" = wadah ini sudah
    // menerima SATU prompt penuh yang sukses — delta HANYA boleh ke
    // wadah primed (tanpanya, retry sesudah reset + stream putus bisa
    // mengirim delta ke wadah kosong yang belum punya konteks).
    /** Keputusan kirim delta (murni, tanpa Context). Delta hanya bila
     *  ada wadah tersimpan YANG SUDAH primed, ada prompt delta, bukan
     *  forceFresh / ephemeral / reviewer, dan akumulasi + delta tetap
     *  di bawah DEFAULT_CAP. Semua kondisi lain -> prompt penuh. */
    fun shouldSendDeltaVc322(hasStoredSession: Boolean, hasDeltaPrompt: Boolean, forceFresh: Boolean, freshEphemeral: Boolean, reviewer: Boolean, primed: Boolean, accumChars: Int, deltaLen: Int): Boolean =
        hasStoredSession && hasDeltaPrompt && !forceFresh && !freshEphemeral && !reviewer && primed &&
        deltaLen > 0 && accumChars + deltaLen <= DEFAULT_CAP

    private const val KEY_SESS_CREATED_VC322 = "sessCreatedVc322_"
    private const val KEY_PRIMED_VC322 = "srvPrimedVc322_"

    /** Penghitung sesi server per (akun/slot, sesi harness) — jumlah
     *  wadah server yang pernah lahir untuk sesi harness ini (reset /
     *  forceFresh / invalid ikut dihitung; yang yatim TIDAK dikurangi,
     *  karena server tidak memberi jalan menghapusnya). Disimpan di
     *  prefs "turnMetrics" (bukan "harness"). */
    fun serverSessionCountVc322(ctx: Context, key: String): Int = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SESS_CREATED_VC322 + key, 0).coerceAtLeast(0)
    } catch (e: Exception) { 0 }

    /** Catat satu kelahiran wadah server; mengembalikan hitungan baru. */
    fun noteServerSessionCreatedVc322(ctx: Context, key: String): Int = try {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = p.getInt(KEY_SESS_CREATED_VC322 + key, 0).coerceAtLeast(0)
        val next = (cur + 1).coerceAtMost(10_000_000)
        p.edit().putInt(KEY_SESS_CREATED_VC322 + key, next).apply()
        next
    } catch (e: Exception) { 0 }

    /** Apakah wadah kunci ini sudah menerima satu prompt penuh sukses. */
    fun isServerSessionPrimedVc322(ctx: Context, key: String): Boolean = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PRIMED_VC322 + key, false)
    } catch (e: Exception) { false }

    fun setServerSessionPrimedVc322(ctx: Context, key: String, primed: Boolean) {
        try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PRIMED_VC322 + key, primed).apply() } catch (e: Exception) { }
    }

    /** vc278 O19(c): char yang dipangkas PEMANGKAS PROAKTIF per giliran
     *  — ikut snapshot JSON giliran ("char_dipangkas_proaktif") dan
     *  ikut char_terpotong (sama seperti guard darurat). */
    fun recordProactiveTrimVc278(chars: Int) { if (begun && chars > 0) { proactiveTrimChars += chars; truncatedChars += chars } }

    fun promptCap(ctx: Context): Int {
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CAP, DEFAULT_CAP)
        // vc248: nilai default lama (100000) dimigrasikan ke default baru agar
        // instalasi lama ikut naik; nilai kustom user yang bukan 100000 tetap.
        if (v == 100000) return DEFAULT_CAP
        return v
    }

    fun finish(ctx: Context, reason: String) {
        if (!begun) return
        begun = false
        val endMs = System.currentTimeMillis()
        val total = if (totalPrompt > 0) totalPrompt else layers.values.sum()
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"durasi_ms\":").append(endMs - startMs).append(",")
        sb.append("\"alasan_akhir\":\"").append(esc(reason)).append("\",")
        sb.append("\"total_prompt_char\":").append(total).append(",")
        sb.append("\"persona\":").append(extraPersona).append(",")
        sb.append("\"rencana\":").append(extraPlan).append(",")
        sb.append("\"trailer\":").append(extraTrailer).append(",")
        sb.append("\"lapisan\":{")
        var first = true
        for ((k, v) in layers) {
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(k).append("\":").append(v)
        }
        sb.append("},")
        sb.append("\"char_terpotong\":").append(truncatedChars).append(",")
        sb.append("\"char_dipangkas_proaktif\":").append(proactiveTrimChars).append(",")
        sb.append("\"char_dipotong_proot\":").append(prootCutChars).append(",")
        sb.append("\"langkah\":").append(steps).append(",")
        sb.append("\"eksekusi_ulang_tool_sama\":").append(repeats).append(",")
        sb.append("\"unknown_tool\":").append(unknownCount).append(",")
        sb.append("\"jejak_dibuang_langkah\":").append(jejakDroppedN).append(",")
        sb.append("\"jejak_dibuang_char\":").append(jejakDroppedChars).append(",")
        sb.append("\"antrean_jumlah\":").append(queueCount).append(",")
        sb.append("\"antrean_total_ms\":").append(queueWaitMsTotal).append(",")
        sb.append("\"antrean_ditolak\":").append(queueRejected).append(",")
        sb.append("\"giliran_ke\":").append(turnNo)
        sb.append("}")
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST, sb.toString()).apply()
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val cur = try { JSONObject(prefs.getString("daily_$day", null) ?: "{}") } catch (e: Exception) { JSONObject() }
            val np = cur.optInt("p", 0) + total
            val ns = cur.optInt("s", 0) + steps
            val ed = prefs.edit().putString("daily_$day", JSONObject().put("p", np).put("s", ns).toString())
            val cutoff = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000))
            for (k in prefs.all.keys) if (k.startsWith("daily_") && k.substring(6) < cutoff) ed.remove(k)
            ed.apply()
        } catch (e: Exception) { }
    }

    fun loadSnapshot(ctx: Context): String? {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)
    }

    fun clearSnapshot(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST).apply()
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    // vc301 SF-D8: penanda potong "…(dipotong, sisa N char)…" — N = char yang
    // dipotong (gross, sama seperti nilai kedua Pair yang sudah ada).
    private fun cutMarkerVc301(cut: Int): String = "…(dipotong, sisa $cut char)…"

    /** Potong teks dari baris TERLAMA sampai panjang <= keepChars.
     *  Mengembalikan (teksTerjaga, charYangDibuang). Bila satu baris
     *  terakhir pun masih melebihi batas, ambil ekornya (baris terbaru
     *  lebih penting daripada baris lama).
     *  vc301 SF-D8: penanda potong ditempel DI DEPAN hasil (yang dibuang
     *  adalah kepala) — panjang penanda dicadangkan dari budget agar
     *  kontrak panjang <= keepChars tetap berlaku (loop ≤3x sampai muat).
     *  Pengecualian: bila keepChars == 0, hasil = penanda saja (seluruh
     *  blok dibuang, tapi model diberi tahu). */
    fun dropOldestLines(text: String, keepChars: Int): Pair<String, Int> {
        if (text.length <= keepChars) return Pair(text, 0)
        var t = text
        while (t.length > keepChars) {
            val nl = t.indexOf('\n')
            if (nl < 0) break
            t = t.substring(nl + 1)
        }
        if (t.length > keepChars) t = t.substring(t.length - keepChars)
        var cut = text.length - t.length
        var marker = cutMarkerVc301(cut)
        var guard = 0
        while (t.length + marker.length > keepChars && t.isNotEmpty() && guard < 3) {
            val excess = t.length + marker.length - keepChars
            t = t.substring(minOf(excess, t.length))
            cut = text.length - t.length
            marker = cutMarkerVc301(cut)
            guard++
        }
        return Pair(marker + t, cut)
    }

    /** vc235 M3: kebalikan dropOldestLines — potong dari AKHIR blok
     *  (baris terbaru / relevansi terendah) sampai panjang <= keepChars.
     *  Untuk blok yang sudah diurutkan relevansi-menurun (fakta/goals
     *  R-10): awal blok adalah yang paling relevan dan dipertahankan,
     *  yang dibuang adalah akhir blok. Pemisah akhir blok dipertahankan
     *  (satu newline terakhir dilindungi dari pemotongan) agar header
     *  blok berikutnya tidak menempel pada baris terakhir yang tersisa;
     *  bila tidak ada yang tersisa, pemisah ikut dibuang sehingga hasil
     *  tepat kosong. Bila satu baris pun masih melebihi anggaran, ambil
     *  kepalanya (cerminan fallback dropOldestLines).
     *  vc301 SF-D8: penanda potong ditempel DI AKHIR hasil (yang dibuang
     *  adalah ekor), sebelum pemisah akhir blok — panjang penanda
     *  dicadangkan dari budget agar kontrak panjang <= keepChars tetap
     *  berlaku (loop ≤3x sampai muat). Bila tidak ada yang tersisa, hasil
     *  tetap kosong seperti sebelumnya. */
    fun dropNewestLines(text: String, keepChars: Int): Pair<String, Int> {
        if (text.length <= keepChars) return Pair(text, 0)
        val sep = if (text.endsWith("\n")) "\n" else ""
        val body = if (sep.isNotEmpty()) text.dropLast(1) else text
        val budget = (keepChars - sep.length).coerceAtLeast(0)
        var t = body
        while (t.length > budget) {
            val nl = t.lastIndexOf('\n')
            if (nl < 0) break
            t = t.substring(0, nl)
        }
        if (t.length > budget) t = t.take(budget)
        var cut = text.length - (if (t.isEmpty()) "" else t + sep).length
        var marker = cutMarkerVc301(cut)
        var guard = 0
        while (t.length + marker.length > budget && t.isNotEmpty() && guard < 3) {
            val excess = t.length + marker.length - budget
            t = t.substring(0, (t.length - excess).coerceAtLeast(0))
            cut = text.length - (if (t.isEmpty()) "" else t + sep).length
            marker = cutMarkerVc301(cut)
            guard++
        }
        val out = if (t.isEmpty()) "" else t + marker + sep
        return Pair(out, cut)
    }

    /** Klasifikasi BUKTI untuk urutan JEJAK (Butir 4). Ini HANYA petunjuk
     *  urutan: salah klasifikasi hanya mengubah urutan dalam cap, TIDAK
     *  PERNAH mengubah eksekusi tool apa pun.
     *  vc276 O13(b): untuk tool tulis file/artifact, status "bukti" TIDAK
     *  lagi ditentukan nama tool - hanya langkah yang flag read-back-nya
     *  "terverifikasi: ya" (ada di evidence langkah itu) yang dihitung
     *  bukti. Tool tulis tanpa flag (tidak terverifikasi / tidak dapat
     *  diverifikasi) turun ke urutan non-bukti. Tool lain tidak berubah. */
    private val EVIDENCE_TOOLS = setOf(
        "read_file", "web_fetch", "web_search", "get_screen", "browser_read",
        "gmail_search", "recall_search", "otak_read", "list_files", "storage_info"
    )
    // vc276 O13(b): cermin WRITE_TOOLS_VC276 di Tools.kt (set yang sama).
    private val WRITE_VERIFY_TOOLS_VC276 = setOf(
        "write_file", "edit_file", "generate_document", "generate_presentation",
        "create_artifact", "edit_artifact", "delete_artifact"
    )
    private val MUTATION_WORD = Regex("""\b(rm|mv|cp|mkdir|tee|install|apt|pip|npm|write|edit|delete|push)\b""")

    fun isEvidenceStep(tool: String, args: String, evidence: String = ""): Boolean {
        if (tool in WRITE_VERIFY_TOOLS_VC276) return evidence.contains("terverifikasi: ya")
        if (tool in EVIDENCE_TOOLS) return true
        if (tool != "run_terminal") return false
        if (args.contains(">")) return false
        if (args.contains("sed -i")) return false
        return !MUTATION_WORD.containsMatchIn(args)
    }
}
