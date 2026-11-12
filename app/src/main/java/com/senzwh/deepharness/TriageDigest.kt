package com.senzwh.deepharness

import android.content.Context

/** F5 (v4.40.0): triage notifikasi -> laporan pagi.
 *  Mematangkan digest v4.12.0 yang sudah ada (bukan sistem baru):
 *  - Allowlist D1 (keputusan user): WA, Instagram, Telegram, Gmail,
 *    Threads, SSR SPACE. Paket lain tidak masuk laporan (dihitung transparan).
 *  - Scrub di titik kumpul: baris berisi kata OTP/kode verifikasi DIBUANG
 *    utuh; run digit >= 10 disensor (nomor rekening/kartu tidak boleh bocor
 *    ke laporan). Sisa batas: kode pendek telanjang tanpa kata kunci OTP di
 *    aplikasi allowlist TIDAK terdeteksi — didokumentasikan, bukan disembunyikan.
 *  - Kunci satu laporan per hari; mark hanya bila submit berhasil (pola F2:
 *    laporan yang terhalang gerbang dicoba lagi tick berikutnya, bukan hilang).
 *  - Arsip: notifikasi (type='notif') lebih dari 7 hari dihapus. Tipe event
 *    lain tidak disentuh. Aman: finance_summary baca tabel finance terpisah,
 *    get_timeline baca latest(30) berbasis resensi.
 *  - Draf tidak pernah auto-kirim: prompt melarang pengiriman; balasan hanya
 *    diajukan sebagai draf/pertanyaan untuk user.
 */
object TriageDigest {
    /** D1. SSR SPACE: package id tidak ditemukan di workspace, jadi dipakai
     *  heuristik: paket apa pun yang mengandung "ssr" dianggap SSR SPACE.
     *  False positive hampir mustahil (substring ini langka di package name).
     *  Threads = com.instagram.barcelona (package resmi Threads sejak lama). */
    val ALLOW = setOf(
        "com.whatsapp",
        "com.instagram.android",
        "org.telegram.messenger",
        "com.google.android.gm",
        "com.instagram.barcelona"
    )

    fun isAllowed(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p in ALLOW || "ssr" in p
    }

    private val OTP_WORDS = listOf(
        "otp", "kode otp", "kode verifikasi", "verification code",
        "kode autentikasi", "one-time", "one time password"
    )
    private val LONG_DIGITS = Regex("\\d{10,}")

    data class ScrubResult(val rows: List<HEvent>, val droppedOtp: Int, val droppedOther: Int)

    fun scrub(rows: List<HEvent>): ScrubResult {
        val kept = ArrayList<HEvent>()
        var droppedOtp = 0
        var droppedOther = 0
        for (r in rows) {
            if (!isAllowed(r.pkg)) { droppedOther++; continue }
            val low = r.text.lowercase()
            if (OTP_WORDS.any { low.contains(it) }) { droppedOtp++; continue }
            val redacted = LONG_DIGITS.replace(r.text, "[disensor]")
            kept.add(if (redacted == r.text) r else HEvent(r.ts, r.type, r.pkg, redacted))
        }
        return ScrubResult(kept, droppedOtp, droppedOther)
    }

    /** Bangun prompt laporan pagi dari notif 12 jam terakhir. Null bila tidak
     *  ada baris allowlist (tidak ada laporan kosong). */
    fun buildPrompt(ctx: Context, now: Long): String? {
        val rows = EventDb.get(ctx).latest(300, "notif").filter { now - it.ts < 12 * 3600_000 }
        if (rows.isEmpty()) return null
        val s = scrub(rows)
        if (s.rows.isEmpty()) return null
        val byApp = s.rows.groupingBy { it.pkg }.eachCount().entries.sortedByDescending { it.value }
        val sb = StringBuilder()
        sb.append("Total notifikasi 12 jam terakhir (aplikasi allowlist): ").append(s.rows.size)
        if (s.droppedOther > 0) sb.append("\n(").append(s.droppedOther).append(" notifikasi dari aplikasi di luar allowlist tidak dimasukkan.)")
        if (s.droppedOtp > 0) sb.append("\n(").append(s.droppedOtp).append(" notifikasi berisi OTP/kode verifikasi dibuang, tidak dimasukkan.)")
        sb.append("\nPer aplikasi:")
        for ((pkg2, n2) in byApp.take(8)) sb.append("\n- ").append(pkg2).append(": ").append(n2)
        sb.append("\n\nIsi notifikasi (terbaru dulu, maks 25; angka panjang sudah disensor):")
        for (rw in s.rows.take(25)) sb.append("\n- [").append(rw.pkg).append("] ").append(rw.text.take(140))
        return "Susun RINGKASAN NOTIFIKASI PAGI yang ringkas dan berguna (Bahasa Indonesia santai): kelompokkan per tema, naikkan yang penting (keuangan, keamanan, pesan dari orang), abaikan spam promo. Jangan mengarang isi di luar data. Tulis HANYA angka yang benar-benar tampil di data; bila data parsial, tulis 'data parsial'. JANGAN mengirim pesan/balasan apa pun — hanya ringkas; bila perlu balasan, ajukan sebagai draf/pertanyaan untuk user.\n\nDATA:\n" + sb.toString()
    }

    fun pruneOld(ctx: Context) {
        try {
            val db = EventDb.get(ctx).writableDatabase
            db.execSQL(
                "DELETE FROM events WHERE type='notif' AND ts<?",
                arrayOf((System.currentTimeMillis() - 7 * 86400_000L).toString())
            )
        } catch (e: Exception) {}
    }

    /** Dipanggil tiap tick service. Sekali sehari mulai jam 07:00 (D2). */
    fun runDaily(ctx: Context, now: Long, today: Int, hour: Int) {
        val p = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
        if (!p.getBoolean("notifDigest", true)) return
        if (hour < 7) return
        if (p.getInt("lastNotifDigestDay", -1) == today) return
        if (!BgBrain.bgAllowed(ctx)) return
        pruneOld(ctx)
        val prompt = buildPrompt(ctx, now) ?: return
        val started = BgBrain.submit(ctx, prompt, "digest", "📰 Ringkasan notifikasi pagi")
        if (started) {
            p.edit().putInt("lastNotifDigestDay", today).apply()
            // Grup Matang F3c: digest yang sama didiskusikan grup (SATU ronde
            // latar; hanya bila 1-1 benar-benar jalan — kunci harian di atas
            // mencegah ganda). Diposting DATA-nya saja (tanpa instruksi
            // penyusun ringkasan); bila grup sibuk, diskusi hari itu lewat
            // dan dicatat joblog (1-1 tetap terkirim).
            try {
                val dataPart = prompt.substringAfter("\n\nDATA:\n", prompt)
                GroupChat.runBackgroundRound(ctx, "Laporan pagi (F5):\n" + dataPart, "digest")
            } catch (e: Exception) {}
        }
        EventDb.get(ctx).add("digest", ctx.packageName, "Ringkasan notifikasi pagi dikirim (otak latar: $started)")
    }

    /** Tes manual (Setup 55): tidak menyentuh kunci harian.
     *  F1.0: mengembalikan "ok" / "empty" / "blocked:<alasan>" agar toast
     *  bisa membedakan "tidak ada notifikasi allowlist" vs "submit gagal"
     *  (baterai/jam hening/sesi mati/kuota). */
    fun runManual(ctx: Context): String {
        pruneOld(ctx)
        val prompt = buildPrompt(ctx, System.currentTimeMillis()) ?: return "empty"
        val started = BgBrain.submit(ctx, prompt, "digest", "📰 Ringkasan notifikasi pagi (tes)")
        EventDb.get(ctx).add("digest", ctx.packageName, "Ringkasan notifikasi pagi TES dikirim (otak latar: $started)")
        return if (started) "ok" else "blocked:" + BgBrain.lastBlockReason(ctx)
    }
}
