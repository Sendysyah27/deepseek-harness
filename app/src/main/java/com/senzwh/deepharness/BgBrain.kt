package com.senzwh.deepharness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * OTAK LATAR (Tahap C, brief §0.9 — keputusan user 2026-10-22 07:36:
 * panggilan LLM di background AKTIF, tidak menunggu app dibuka).
 *
 * Mesin: sebuah WebView DeepSeek dipelihara komponen ini (cookie &
 * localStorage WebView bersifat per-aplikasi, jadi sesi login yang sama
 * dengan tab Web utama). DsApiClient membaca token SEGAR dari WebView itu
 * tiap panggilan — token tidak pernah ditulis ke penyimpanan.
 *
 * Aturan main:
 * - Tool baca jalan otomatis; tool BERISIKO diparkir → notifikasi
 *   persetujuan (Setujui/Tolak); tidak pernah dieksekusi diam-diam.
 * - Hasil akhir dikirim sebagai notifikasi (tidak lagi masuk riwayat chat — vc186 Q4).
 * - Sesi DeepSeek mati (token tidak terbaca) → Otak Latar jeda, notif
 *   "login ulang" sekali per episode, tugas jatuh ke antrean lama
 *   (enqueuePrompt — jalan saat app dibuka berikutnya).
 * - Semua tugas diserialkan lewat satu executor agar tidak balapan.
 */
object BgBrain {
    const val APPROVAL_NOTIF_ID = 7701
    // vc246 GELOMBANG 5 (NT-M2): satu definisi saluran per tujuan.
    // Persetujuan/parkir latar = "harness_high" (IMPORTANCE_HIGH, di sini).
    // Pengingat/alert umum = "harness-alert" (HIGH, HarnessService).
    // Diagnosis self-healing = "harness_auto_heal" (MainActivity).
    // Dulu BgBrain memakai "harness-alert" yang sama dengan pengingat
    // sehingga persetujuan HIGH tidak bisa dibedakan/dibisukan terpisah;
    // klaim audit soal "harness_channel" ganda tidak ditemukan di tree
    // vc245 (saluran itu tidak ada) — pemisahan ini yang diimplementasikan.
    private const val CHANNEL = "harness_high"
    // vc263 (AG-M6 + autoloop): cap transkrip latar + ambang watchdog.
    // Transkrip 60000 char = kira-kira 15 ribu token; tanpa cap, 50 langkah
    // x 10000 char = 500 ribu char dikirim ulang tiap iterasi.
    private const val BG_TRANSCRIPT_MAX_CHARS = 60000
    private const val BG_TRANSCRIPT_MIN_KEEP_CHARS = 20000
    private const val BG_WATCHDOG_STALL_MS = 10 * 60_000L
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "bg-brain").apply { isDaemon = true } }
    @Volatile private var webView: WebView? = null
    @Volatile private var client: DsApiClient? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)

    fun sessionDead(ctx: Context) = prefs(ctx).getBoolean("bgSessionDead", false)

    /** v4.12.0: gerbang Panel Gubernur untuk kerja OTONOM — jam hening +
     * Wi-Fi-only. Chat interaktif tidak pernah melewati gerbang ini. */
    fun bgAllowed(ctx: Context): Boolean {
        val p = prefs(ctx)
        try {
            val qs = p.getInt("govQuietStart", -1)
            val qe = p.getInt("govQuietEnd", -1)
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val inQuiet = BgBrainSeam.bgQuietHour(qs, qe, h) ?: bgQuietHourKotlin(qs, qe, h)
            if (inQuiet) return false
            if (p.getBoolean("govWifiOnly", false)) {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                if (caps == null || !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return false
            }
        } catch (e: Exception) {}
        return true
    }

    /** Slice 12 (vc329): jam hening `bgAllowed` — dipakai bila seam
     *  Rust tak-tersedia. */
    private fun bgQuietHourKotlin(qs: Int, qe: Int, h: Int): Boolean {
        if (qs < 0 || qe < 0 || qs == qe) return false
        return if (qs < qe) (h >= qs && h < qe) else (h >= qs || h < qe)
    }

    /**
     * Serahkan tugas latar. Return false bila sesi diketahui mati → pemanggil
     * memakai jalur antre lama. Tugas yang lolos antrean dieksekusi async.
     */
    /** F2 (v4.39.0): gerbang daya — baterai < 20% + tidak dicas = jangan
     *  mulai kerja latar. Baterai tak terbaca (-1) dianggap OK (jangan blokir
     *  karena sensor gagal). */
    fun batteryOk(ctx: Context): Boolean {
        return try {
            val i = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plugged = i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            BgBrainSeam.batteryFormula(level, scale, plugged) ?: batteryFormulaKotlin(level, scale, plugged)
        } catch (e: Exception) { true }
    }

    /** Slice 12 (vc329): rumus daya `batteryOk` — dipakai bila seam
     *  Rust tak-tersedia. */
    private fun batteryFormulaKotlin(level: Int, scale: Int, plugged: Int): Boolean =
        if (level < 0) true else (level * 100 / scale.coerceAtLeast(1) >= 20 || plugged != 0)

    /** F2: saklar darurat job terjadwal (origin jadwal/rutinitas). */
    fun jobsOff(ctx: Context): Boolean = prefs(ctx).getBoolean("jobsOff", false)

    /** F2: alasan penolakan submit terakhir (untuk tick: fallback antre +
     *  alert HANYA bila bukan penolakan gerbang). */
    fun lastBlockReason(ctx: Context): String = prefs(ctx).getString("bgLastBlock", "") ?: ""

    private fun block(ctx: Context, reason: String): Boolean {
        try { prefs(ctx).edit().putString("bgLastBlock", reason).apply() } catch (e: Exception) {}
        return false
    }

    // vc243 GELOMBANG 2 (AG-M4): ownerSessionId = sesi 1-1 pemilik tugas.
    // Bila tidak dioper eksplisit, ditangkap dari currentSession SAAT SUBMIT
    // (bukan saat eksekusi/selesai) lalu diwariskan ke runTask/loop/park/
    // resume/deliver. Pemanggil terjadwal mengoper ikatan eksplisitnya.
    fun submit(ctx: Context, task: String, origin: String, notifTitle: String? = null, jobId: String? = null, jobTools: Set<String>? = null, ownerSessionId: Long? = null): Boolean {
        // v4.48.9.2: cooldown global pasca-rate-limit — 15 menit sejak
        // handleError mencatat bgLastRateLimitTs. Dicek PERTAMA supaya
        // alasan "cooldown" menang atas "sesi mati" dalam jendela ini;
        // tick jadwal/rutinitas hanya fallback antre bila alasan "sesi
        // mati", jadi tugas yang ditahan cooldown tetap jatuh tempo dan
        // dicoba lagi di tick berikut (bukan jadi prompt depan).
        // BgBrain saja — chat interaktif tidak pernah memanggil submit.
        // Slice 12 (vc329): gerbang submit lewat seam Rust (keputusan
        // murni); efek samping (block/notify/clear sessionDead) tetap
        // Kotlin. Fallback = blok gerbang asli bila seam tak-tersedia.
        val lastRL = prefs(ctx).getLong("bgLastRateLimitTs", 0)
        val lastRetry = prefs(ctx).getLong("bgDeadRetryTs", 0)
        val gate = BgBrainSeam.submitDecision(
            lastRL, System.currentTimeMillis(), sessionDead(ctx), lastRetry,
            Governor.overCapBg(ctx), bgAllowed(ctx), origin in setOf("jadwal", "rutinitas") && jobsOff(ctx),
            origin, batteryOk(ctx)
        )
        if (gate != null) {
            if (gate.clearSessionDead) prefs(ctx).edit().putBoolean("bgSessionDead", false).putLong("bgDeadRetryTs", System.currentTimeMillis()).apply()
            when (gate.decision) {
                "proceed" -> { /* lanjut ke antrean di bawah */ }
                "cooldown" -> return block(ctx, "cooldown")
                "sesi mati" -> return block(ctx, "sesi mati")
                "kuota" -> { notifyGovernorDelay(ctx, "kuota harian Governor habis"); return block(ctx, "kuota") }
                "gerbang" -> { notifyGovernorDelay(ctx, "gerbang jam hening / WiFi-only"); return block(ctx, "gerbang") }
                "job dimatikan" -> return block(ctx, "job dimatikan")
                "baterai" -> return block(ctx, "baterai")
                else -> if (!submitGateKotlin(ctx, origin)) return false
            }
        } else {
            if (!submitGateKotlin(ctx, origin)) return false
        }
        val app = ctx.applicationContext
        try { if (jobId != null) MemoryDb.get(app).logJob(jobId, "berjalan", task.take(120)) } catch (e: Exception) {}
        // vc246 GELOMBANG 5 (AG-M6): catat ke antrean terlihat.
        try {
            val q = HashSet(prefs(app).getStringSet("bgQueue", emptySet()) ?: emptySet())
            q.add((jobId ?: ("tanpa-id-" + System.currentTimeMillis())) + " | " + origin + " | " + task.take(80))
            prefs(app).edit().putStringSet("bgQueue", q).apply()
        } catch (e: Exception) {}
        val owner = ownerSessionId ?: prefs(app).getLong("currentSession", 1L)
        executor.execute { runTask(app, task, origin, notifTitle, jobId, jobTools, owner) }
        return true
    }

    /** Slice 12 (vc329): gerbang `submit` (urutan asli: cooldown,
     *  retry sessionDead, kuota, gerbang, job, baterai) — dipakai bila
     *  seam Rust tak-tersedia. True = lolos, false = diblokir. */
    private fun submitGateKotlin(ctx: Context, origin: String): Boolean {
        val lastRL = prefs(ctx).getLong("bgLastRateLimitTs", 0)
        if (lastRL > 0 && System.currentTimeMillis() - lastRL < 15 * 60_000) return block(ctx, "cooldown")
        if (sessionDead(ctx)) {
            // F2.1: jangan mengunci mati selamanya — coba lagi maks 1x per
            // 15 menit. Bila percobaan gagal, handleError mengunci lagi +
            // antrean (yang kini ter-dedupe) menunggu giliran depan.
            val lastRetry = prefs(ctx).getLong("bgDeadRetryTs", 0)
            if (System.currentTimeMillis() - lastRetry < 15 * 60_000) return block(ctx, "sesi mati")
            prefs(ctx).edit().putBoolean("bgSessionDead", false).putLong("bgDeadRetryTs", System.currentTimeMillis()).apply()
        }
        if (Governor.overCapBg(ctx)) { notifyGovernorDelay(ctx, "kuota harian Governor habis"); return block(ctx, "kuota") }
        if (!bgAllowed(ctx)) { notifyGovernorDelay(ctx, "gerbang jam hening / WiFi-only"); return block(ctx, "gerbang") }
        if (origin in setOf("jadwal", "rutinitas") && jobsOff(ctx)) return block(ctx, "job dimatikan")
        if (!batteryOk(ctx)) return block(ctx, "baterai")
        return true
    }

    // ---------------- Mesin sesi ----------------

    private fun ensureClient(ctx: Context): DsApiClient? {
        client?.let { return it }
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                if (webView == null) {
                    val wv = WebView(ctx)
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { latch.countDown() }
                    }
                    webView = wv
                    wv.loadUrl("https://chat.deepseek.com/")
                } else latch.countDown()
            } catch (e: Exception) { latch.countDown() }
        }
        try { latch.await(30, TimeUnit.SECONDS) } catch (e: Exception) {}
        val wv = webView ?: return null
        if (client == null) client = DsApiClient(wv)
        return client
    }

    private fun chatSync(ctx: Context, api: DsApiClient, prompt: String, reviewer: Boolean = false): String {
        // FULL MIGRATION: engine "openai" → Models API; bypass DsApiClient/WebView.
        if (AgentEngines.current(ctx) == "openai") {
            LlmProviderStore.hydrate(ctx)
            val actId = LlmProviderStore.activeId(ctx)
            val prov = LlmProviders.activeProvider()
            val key = LlmProviderStore.apiKey(ctx, actId)
            if (prov.kind != LlmKind.OPENAI_COMPAT || key.isNullOrBlank()) {
                throw Exception("Otak Latar: provider '$actId' belum siap (butuh OpenAI-compatible + API key).")
            }
            val latchBg = CountDownLatch(1)
            val outBg = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val errBg = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val thinkBg2 = prefs(ctx).getString("dsModelVariant", "v3") == "r1"
            LlmProviders.chat(key, prov, prompt, thinkBg2, { },
                { text, _ -> outBg.set(text); latchBg.countDown() },
                { e -> errBg.set(e); latchBg.countDown() })
            latchBg.await(300, TimeUnit.SECONDS)
            errBg.get()?.let { throw Exception(it) }
            return outBg.get() ?: throw Exception("Otak Latar: Models API timeout / tanpa hasil.")
        }
        val latch = CountDownLatch(1)
        val out = AtomicReference<String?>(null)
        val err = AtomicReference<String?>(null)
        // v4.48.9.20: otak latar ikut picker varian model (Mesin) +
        // wadah server sendiri (bgMode) — lihat DsApiClient.runChat.
        val thinkBg = prefs(ctx).getString("dsModelVariant", "v3") == "r1"
        api.chat(prompt, thinkBg, false, { },
            { text, _ -> out.set(text); latch.countDown() },
            { e -> err.set(e); latch.countDown() }, bgMode = true, reviewer = reviewer, forceFresh = reviewer)
        latch.await(300, TimeUnit.SECONDS)
        err.get()?.let { throw Exception(it) }
        return out.get() ?: throw Exception("Otak Latar: panggilan API timeout / tanpa hasil.")
    }

    // vc198 Fase 3: panggilan LLM latar SILENT untuk job graph (relasi,
    // verifier, adjudikasi). BUKAN runTask/submit — tanpa delivery /
    // notifikasi. Timeout 120 dtk; gagal -> throw. Memakai wadah bgMode
    // (vc186) agar tidak mencemari sesi foreground.
    fun callBgSync(ctx: Context, prompt: String): String {
        val api = ensureClient(ctx) ?: throw Exception("Otak Latar: WebView tidak siap.")
        val latch = CountDownLatch(1)
        val out = AtomicReference<String?>(null)
        val err = AtomicReference<String?>(null)
        val thinkBg = prefs(ctx).getString("dsModelVariant", "v3") == "r1"
        api.chat(prompt, thinkBg, false, { },
            { text, _ -> out.set(text); latch.countDown() },
            { e -> err.set(e); latch.countDown() }, bgMode = true)
        latch.await(120, TimeUnit.SECONDS)
        err.get()?.let { throw Exception(it) }
        return out.get() ?: throw Exception("Otak Latar: panggilan latar timeout / tanpa hasil.")
    }

    // vc228 R-01(c): ringkas riwayat lama yang terbuang guard prompt.
    // Dipanggil dari Thread latar (bukan UI). Di bawah Gubernur: bila kuota
    // habis -> null (tidak ada ringkasan, tidak ada bypass). Gagal apa pun
    // -> null. Hasil dipangkas 2000 char; penyimpanan + cap 3000 char ada
    // di pemanggil (MainActivity). Potongan dipotong di batas pesan, bukan
    // take mentah, supaya satu pesan tidak terbelah di tengah angka/nama.
    fun cutAtMessageBoundary(text: String, max: Int): String =
        BgBrainSeam.cutBoundary(text, max) ?: cutAtMessageBoundaryKotlin(text, max)

    /** Slice 12 (vc329): potong di batas pesan — dipakai bila seam
     *  Rust tak-tersedia. */
    private fun cutAtMessageBoundaryKotlin(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.lastIndexOf('\n', max)
        return if (cut > max / 2) text.substring(0, cut) else text.take(max)
    }

    fun summarizeOldHistory(ctx: Context, droppedText: String): String? {
        return try {
            if (droppedText.isBlank()) return null
            if (Governor.overCapBg(ctx)) return null
            val prompt = "Ringkas percakapan lama berikut dalam 1-2 kalimat per 10 pesan. WAJIB mempertahankan secara VERBATIM (tanpa parafrase, tanpa pembulatan, tanpa mengubah angka) field-field berikut bila ada di teks: semua angka, semua nama, semua tanggal, keputusan eksplisit user, serta penolakan dan persetujuan user. Bila suatu angka/nama/tanggal tidak yakin, tulis apa adanya dari teks atau hilangkan — jangan mengarang, jangan menambah informasi baru. Hanya fakta, keputusan, dan konteks penting yang masih relevan. Keluarkan HANYA ringkasannya, satu paragraf.\n\nPERCAKAPAN LAMA:\n" + cutAtMessageBoundary(droppedText, 6000)
            val out = callBgSync(ctx, prompt)
            val s = out.trim().take(8000)
            if (s.isBlank()) null else s
        } catch (e: Exception) { null }
    }

    // ---------------- Loop agen latar ----------------

    private fun runTask(ctx: Context, task: String, origin: String, notifTitle: String?, jobId: String? = null, jobTools: Set<String>? = null, ownerSessionId: Long? = null) {
        // vc302 Fix A: tugas latar baru = giliran baru — reset guard
        // baca-ulang identik (baca yang sama boleh lagi di tugas berikut).
        Tools.resetRereadGuardVc302()
        // v4.48.9.26 (vc192): job kind=digest tidak memakai LLM — digest
        // disusun dari data nyata lalu dikirim lewat deliver() seperti biasa
        // (notifikasi + routing laporan ke sesi terikat).
        if (jobId != null) {
            try {
                val raw = prefs(ctx).getString("routines", "[]") ?: "[]"
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    if (r.optString("id") == jobId && r.optString("kind") == "digest") {
                        val digest = Automation.buildRoomDigest(ctx, r.optString("digestRoomId"))
                        val digestOwner = ownerSessionId ?: prefs(ctx).getLong("currentSession", 1L)
                        deliver(ctx, origin, notifTitle, digestOwner, digest, jobId)
                        return
                    }
                }
            } catch (e: Exception) {}
        }
        val api = ensureClient(ctx) ?: run {
            MemoryDb.enqueuePrompt(ctx, task)
            EventDb.get(ctx).add("bgbrain", ctx.packageName, "WebView latar gagal dibuat — tugas diantrekan ($origin)")
            return
        }
        val mdb = MemoryDb.get(ctx)
        // vc243 GELOMBANG 2 (AG-M4): konteks + hasil memakai sesi PEMILIK
        // tugas (ditangkap submit), bukan sesi aktif saat eksekusi.
        val sessionId = ownerSessionId ?: prefs(ctx).getLong("currentSession", 1L)
        // vc241: latar memakai katalog per tugas + WorldState tanpa goal-teratas (blok TUJUAN AKTIF sendiri sudah ada di bawah).
        val sb = StringBuilder(AgentPrompt.buildSystemPrompt(ctx, false, task, true))
        // vc240 M23 + m18: pemanggil latar kini mengoper teks tugas
        // sebagai query relevansi (dulu tanpa argumen -> semua skor 0 ->
        // urutan mentah). BATAS m18 yang dicatat eksplisit: API tugas
        // latar tidak menyimpan agen/sesi asal tugas, jadi otak yang
        // dipakai tetap agen aktif + sesi currentSession — itu nilai paling
        // tepat yang tersedia, bukan ID agen/sesi yang direkayasa.
        val skills = mdb.skillsText(task); if (skills.isNotBlank()) sb.append("\n\nSKILL AKTIF (prosedur yang dibuat user — ikuti bila relevan):\n").append(skills)
        // vc263 (optimasi): pinnedFactsText() dulu dipanggil 2x per tugas
        // (sekali langsung, sekali di dalam argumen dedup) — tiap panggilan
        // memindai SEMUA fakta + membangun string penuh. Kini sekali saja;
        // versi mentah dipakai dedup (redaksi tidak boleh ikut dicocokkan).
        val pinnedRawBgVc263 = try { mdb.pinnedFactsText() } catch (e: Exception) { "" }
        val pinnedBgVc248 = try { RedactSecrets.redact(pinnedRawBgVc263) } catch (e: Exception) { "" }
        if (pinnedBgVc248.isNotBlank()) sb.append("\n\n[FAKTA PIN — selalu berlaku, tidak dibuang diam-diam]:\n").append(pinnedBgVc248)
        val facts = mdb.dedupFactsVsPinned(mdb.factsText(task), pinnedRawBgVc263); if (facts.isNotBlank()) sb.append("\n\nMEMORI PERSISTEN (fakta tentang user):\n").append(facts)
        val goals = mdb.goalsText(task); if (goals.isNotBlank()) sb.append("\n\nTUJUAN AKTIF:\n").append(goals)
        // vc235 M2: transkrip Vc230; sessionId di sini = sesi aktif saat
        // tugas dieksekusi (pref currentSession) karena API BgBrain tidak
        // menyimpan sesi pemilik tugas — itu nilai paling tepat yang tersedia.
        // vc263 (kompresi): bila ada brief sesi latar sebelumnya dengan
        // origin yang sama, brief itu dibawa (<=2000 char) dan riwayat
        // mentah diperkecil 30000 -> 15000 char — kontinuitas tanpa
        // mengirim riwayat penuh dua kali.
        // vc265 V-m3: brief dikonsumsi sekali pakai + TTL 24 jam. Dibaca
        // di sini lalu pref-nya langsung dihapus (loop finally menulis
        // brief segar di akhir tugas ini); brief basi (>24 jam) diabaikan
        // dan dibersihkan supaya tidak dipakai tugas jauh kemudian.
        val briefPrevVc263 = try {
            val tsVc265 = prefs(ctx).getLong("bgBriefLastTs_" + origin, 0)
            val rawVc265 = prefs(ctx).getString("bgBriefLast_" + origin, "") ?: ""
            if (rawVc265.isBlank()) "" else {
                prefs(ctx).edit().remove("bgBriefLast_" + origin).remove("bgBriefLastTs_" + origin).apply()
                if (tsVc265 > 0 && System.currentTimeMillis() - tsVc265 > 24 * 3600_000L) "" else rawVc265
            }
        } catch (e: Exception) { "" }
        val hist = try { mdb.historyTranscriptVc230(30, if (briefPrevVc263.isNotBlank()) 15000 else 30000, sessionId) } catch (e: Exception) { "" }
        if (briefPrevVc263.isNotBlank()) sb.append("\n\n[RINGKASAN SESI LATAR SEBELUMNYA — brief ringkas vc263, bukan riwayat mentah]:\n").append(briefPrevVc263)
        if (hist.isNotBlank()) sb.append("\n\n[RIWAYAT]\nRIWAYAT PERCAKAPAN TERAKHIR:\n").append(hist).append("\n[/RIWAYAT]")
        // vc302 Fix C: catatan kerja sesi pemilik tugas DITEMPEL ke prompt
        // awal latar — tugas latar kerap melanjutkan pekerjaan 1-1 sesi yang
        // sama; tanpa blok ini agen latar mengulang langkah yang sudah selesai
        // di chat. Kunci = sessionId pemilik (sudah diwariskan submit).
        run { val wb = AgentPrompt.workNotesBlockVc302(ctx, sessionId); if (wb.isNotBlank()) sb.append("\n\n").append(wb) }
        // VC303-P2-10 (CX-3): catatan kerja latar origin yang sama,
        // berdampingan dengan WorkNotes sesi pemilik — tugas latar
        // berikut melihat hasil tool tugas latar sebelumnya (bukan hanya
        // brief sekali-pakai yang sudah dihapus saat dibaca).
        run { val wbBgVc303 = WorkNotesVc301.blockForKey(ctx, WorkNotesVc301.bgNotesKey(origin)); if (wbBgVc303.isNotBlank()) sb.append("\n\n[CATATAN KERJA LATAR — origin $origin]\n").append(wbBgVc303) }
        sb.append("\n\n").append(AgentPrompt.CTX_LABEL_RULES)
        // vc302 Fix B: aturan total paging (total dari header, jangan jumlahkan) — tugas latar.
        sb.append("\n\n").append(AgentPrompt.PAGING_TOTAL_RULE_VC302)
        sb.append("\n\n[MODE LATAR — origin: $origin] Tugas berikut dijalankan OTOMATIS di latar; user tidak sedang membuka chat. Hasil akhirmu dikirim sebagai notifikasi (tidak masuk riwayat chat). Tugas: ").append("[USER — TUGAS LATAR VERBATIM]\n").append(task).append("\n[/USER]")
        val transcript = StringBuilder("Tugas ($origin): $task")
        // v4.48.9.20 (Q4): tugas latar TIDAK lagi disuntikkan ke riwayat chat sesi aktif — hasil hanya lewat notifikasi (deliver).
        loop(ctx, api, task, origin, notifTitle, sessionId, transcript, 0, sb.toString(), jobId, jobTools)
    }

    // vc302 Fix C: sessionIdVc302 = sesi pemilik tugas (diteruskan loopInner);
    // -1 = tanpa catatan kerja (pemanggil lama / tanpa sesi).
    // vc316 IXL — LANJUTAN DELTA latar (akar yang sama seperti 1-1,
    // lihat UKURAN-INPUT-VC316.md §4): sesi server latar (harnessNo
    // -100) dipakai ulang tiap iterasi, jadi prompt AWAL tugas ini —
    // system prompt + katalog + skills/pin/fakta/goals + riwayat +
    // work-notes — SUDAH tersimpan di sesi server; mengirim ulang
    // semuanya tiap iterasi menumpuk ~1 prompt penuh per langkah tool
    // sampai input_exceeds_limit. Lanjutan kini hanya membawa yang
    // BARU: jangkar delta (format tool-call + jalan keluar list_tools,
    // pengaman bila server ternyata tidak menyimpan konteks antar
    // POST), tugas verbatim, work-notes sesi (cap kecil, aturan
    // jangan-ulangi — dipertahankan seperti 1-1), dan transkrip
    // (hasil tool sejauh ini — info baru, dibatasi 60.000 char oleh
    // trimTranscriptVc263 + guard cap di loop). Yang DIHAPUS dari
    // lanjutan: buildSystemPrompt, CTX/PAGING rules, pinned, fakta,
    // goals — semuanya sudah ada di prompt awal tugas ini di sesi
    // server yang sama. Perilaku tool latar tidak berubah.
    private fun continuationPrompt(ctx: Context, transcript: StringBuilder, sessionIdVc302: Long = -1L): String {
        val wb = if (sessionIdVc302 > 0) AgentPrompt.workNotesBlockVc302(ctx, sessionIdVc302) else ""
        return BgBrainSeam.continuationPromptBg(transcript.toString(), wb) ?: continuationPromptKotlin(transcript, wb)
    }

    /** Slice 12 (vc329): lanjutan delta latar (jangkar vc316 + tugas
     *  verbatim + work-notes + `[RIWAYAT]`) — dipakai bila seam Rust
     *  tak-tersedia. */
    private fun continuationPromptKotlin(transcript: StringBuilder, workNotes: String): String {
        val sb = StringBuilder()
        sb.append("[LANJUTAN SISTEM — vc316 delta: system prompt, katalog tool, memori, dan riwayat tugas ini SUDAH kamu terima di pesan pertama tugas ini DALAM SESI SERVER YANG SAMA — semuanya masih berlaku, jangan minta diulang. Lanjutkan tugas di bawah memakai transkrip hasil tool sejauh ini. Format tool-call tetap: ```json {\"tool\":\"<nama>\",\"args\":{...},\"id\":\"tc-<n>\"}``` — bila butuh tool yang tidak ada di katalog ringkasmu, panggil list_tools {} dulu untuk katalog lengkap.]")
        sb.append("\n\nTUGAS (verbatim baris pertama transkrip giliran ini — jangan diubah, jangan ditambah):\n").append(transcript.toString().substringBefore("\n"))
        // vc302 Fix C: prompt lanjutan latar dibangun ulang tiap iterasi —
        // tempel catatan kerja di tiap giliran berikut, seperti prompt awal.
        if (workNotes.isNotBlank()) sb.append("\n\n").append(workNotes)
        sb.append("\n\n[MODE LATAR] Kamu sedang menjalankan tugas otomatis di latar. Catatan giliran sejauh ini:\n")
        sb.append("[RIWAYAT]\n").append(transcript).append("\n[/RIWAYAT]")
        sb.append("\n\nLanjutkan tugasnya: bila butuh tool lain keluarkan blok json lagi, bila selesai jawab final tanpa blok json.")
        return sb.toString()
    }

    /** Slice 12 (vc329): pesan berhenti-jujur cap langkah latar
     *  (vc277 O15(b)) — dipakai bila seam Rust tak-tersedia. */
    private fun honestCapStopBg(task: String, steps: Int, usedTools: List<String>, transcript: StringBuilder): String {
        val doneVc277 = usedTools.distinct().joinToString(", ").ifBlank { "belum ada tool yang dijalankan" }
        return BgBrainSeam.honestCapStopBg(doneVc277, steps, task.take(300), transcript.toString().takeLast(2000))
            ?: honestCapStopBgKotlin(doneVc277, steps, task, transcript)
    }

    private fun honestCapStopBgKotlin(done: String, steps: Int, task: String, transcript: StringBuilder): String =
        "Tugas latar berhenti di tengah pada langkah " + steps + " dari tugas: " + task.take(300) + " — ini yang sudah selesai: " + done + "; ini yang belum: tugas belum selesai — sisa langkah tidak dikerjakan. Transkrip terakhir: " + transcript.toString().takeLast(2000)

    /** Slice 12 (vc329): keputusan rute tool jalur tunggal
     *  `loopInnerVc263` (route + pesan) — dipakai P15 untuk uji
     *  paritas Rust-vs-Kotlin. */
    private fun bgToolRouteDecision(
        tool: String, gatesDisabled: Boolean, isMemoryWrite: Boolean,
        jobTools: Set<String>?, isRisky: Boolean, autoAllowed: Boolean,
        prop: Pair<String, String>?
    ): Pair<String, String?>? {
        val r = BgBrainSeam.bgToolRoute(
            tool, gatesDisabled, isMemoryWrite, jobTools == null,
            jobTools?.contains(tool) ?: false, isRisky, autoAllowed,
            prop != null, prop?.first ?: "", prop?.second ?: ""
        )
        if (r != null) return r
        return bgToolRouteDecisionKotlin(tool, gatesDisabled, isMemoryWrite, jobTools, isRisky, autoAllowed, prop)
    }

    /** Slice 12 (vc329): tabel keputusan rute tool (salinan `when`
     *  asli) — dipakai bila seam Rust tak-tersedia. */
    private fun bgToolRouteDecisionKotlin(
        tool: String, gatesDisabled: Boolean, isMemoryWrite: Boolean,
        jobTools: Set<String>?, isRisky: Boolean, autoAllowed: Boolean,
        prop: Pair<String, String>?
    ): Pair<String, String?> {
        if (tool == "make_plan" || tool == "update_plan") return "execute" to null
        if (tool == "analyze_photo") return "msg_analyze_photo" to "Tool analyze_photo hanya tersedia dari chat 1-1 — di mode latar tidak dijalankan. Selesaikan tugas tanpa tool itu."
        if (tool == "delete_session") return "msg_delete_session" to "Tool delete_session hanya tersedia dari UI chat aktif — di mode latar tidak dijalankan. Selesaikan tugas tanpa tool itu."
        if (gatesDisabled) return "execute" to null
        if (isMemoryWrite) {
            if (jobTools != null && tool !in jobTools) return "park_memory_jobtools" to null
            if (prop != null) return "memory_write" to "Proposal memori latar DITAMPUNG di antrean persetujuan batch (Setup 65) — BELUM tersimpan dan BELUM dieksekusi, menunggu persetujuan user: ${prop.first} = ${prop.second.take(140)}. Lanjutkan tugas tanpa menganggap fakta ini sudah tersimpan."
            return "park_memory_noprop" to null
        }
        if (isRisky && !(autoAllowed && (jobTools == null || tool in jobTools))) return "park_risky" to null
        return "execute" to null
    }

    private fun loop(ctx: Context, api: DsApiClient, task: String, origin: String, notifTitle: String?,
                     sessionId: Long, transcript: StringBuilder, steps0: Int, firstPrompt: String?, jobId: String? = null, jobTools: Set<String>? = null) {
        // vc263 (autoloop): pembungkus loop latar — tandai tugas AKTIF +
        // heartbeat agar watchdog (watchdogTick, dipanggil tick service)
        // bisa mendeteksi tugas macet/berputar. finally SELALU jalan
        // (semua return loopInner lewat sini): hapus penanda aktif +
        // simpan brief sesi ringkas untuk tugas latar berikut dengan
        // origin yang sama. TIDAK ada retry diam-diam di sini.
        val keyVc263 = (jobId ?: ("origin:" + origin + ":" + task.hashCode())).replace(" ", "_")
        val startVc263 = System.currentTimeMillis()
        // VC303-P2-10 (CX-3): jejak tool tugas latar ini — dikumpulkan
        // loopInner, ditulis ke kunci ber-namespace di finally (di bawah).
        val bgWorkVc303 = ArrayList<WorkItem>()
        try {
            prefs(ctx).edit().putLong("bgActive_" + keyVc263, startVc263).putLong("bgHeartbeat_" + keyVc263, startVc263).putLong("bgLastSuccess_" + keyVc263, startVc263).apply()
            loopInnerVc263(ctx, api, task, origin, notifTitle, sessionId, transcript, steps0, firstPrompt, jobId, jobTools, bgWorkVc303)
        } finally {
            try {
                val pVc263 = prefs(ctx)
                pVc263.edit().remove("bgActive_" + keyVc263).remove("bgHeartbeat_" + keyVc263).remove("bgLastSuccess_" + keyVc263).remove("bgWatchdogNotified_" + keyVc263).apply()
                val briefVc263 = AgentPrompt.compressSessionBrief(task, transcript.toString())
                if (briefVc263.isNotBlank()) {
                    // vc265 V-m3: bgBrief_<jobId> TIDAK ditulis lagi — kunci
                    // itu tidak pernah dibaca di mana pun (hanya ditulis),
                    // jadi menumpuk selamanya. Hanya bgBriefLast_<origin>
                    // yang dibaca (runTask) + diberi stempel waktu TTL.
                    // Sisa bgBrief_<jobId> lama ikut dihapus bila ada.
                    val edVc263 = pVc263.edit()
                    if (jobId != null) edVc263.remove("bgBrief_" + jobId)
                    edVc263.putString("bgBriefLast_" + origin, briefVc263)
                    edVc263.putLong("bgBriefLastTs_" + origin, System.currentTimeMillis()).apply()
                }
                // VC303-P2-10 (CX-3): selain brief sekali-pakai, tulis
                // WorkNotes ber-namespace per origin (tool+R-id+ok) — tugas
                // latar berikut (origin sama/beda) dan 1-1 tidak lagi buta
                // terhadap hasil latar; brief tetap ditulis seperti dulu.
                WorkNotesVc301.appendToKey(ctx, WorkNotesVc301.bgNotesKey(origin), bgWorkVc303, { s -> RedactSecrets.redact(s) })
            } catch (e: Exception) {}
        }
    }

    private fun loopInnerVc263(ctx: Context, api: DsApiClient, task: String, origin: String, notifTitle: String?,
                     sessionId: Long, transcript: StringBuilder, steps0: Int, firstPrompt: String?, jobId: String? = null, jobTools: Set<String>? = null, workVc303: MutableList<WorkItem>? = null) {
        // vc265 V-m7 (vc299: plafon naik 50 → 200): batas atas dicocokkan dengan UI (200).
        val maxSteps = prefs(ctx).getInt("maxToolSteps", 50).coerceIn(5, 200)
        // vc266: FAKTA PIN penuh sekali per tugas latar — dipakai gate bukti, warning, dan filter reviewer.
        val pinnedBgVc266 = try { MemoryDb.get(ctx).pinnedFactsText() } catch (e: Exception) { "" }
        var steps = steps0
        var prompt = firstPrompt ?: continuationPrompt(ctx, transcript, sessionId)
        var lastRejected: String? = null
        var rejectNudges = 0
        var gateRetriedVc249 = false
        val usedToolsVc249 = ArrayList<String>()
        // vc277 O17(c): pengunci giliran latar — sekali StuckDetector 🛑,
        // tool berikutnya ditolak tanpa eksekusi dan tanpa memakan langkah.
        var turnLockedBgVc277 = false
        // vc277 O15(b): berhenti yang jujur saat cap tercapai.
        fun honestCapStopBgVc277(): String = honestCapStopBg(task, steps, usedToolsVc249, transcript)
        // vc263 (N-m21 + AG-M6): detektor putaran per tugas latar +
        // stempel mulai (dipakai watchdog + ukuran prompt akhir).
        val stuckWatchVc263 = AgentPrompt.StuckWatch()
        val loopKeyVc263 = (jobId ?: ("origin:" + origin + ":" + task.hashCode())).replace(" ", "_")
        val loopStartMsVc263 = System.currentTimeMillis()
        // vc274 O6+O7: eksekusi batch multi-tool latar. Fungsi lokal agar
        // bisa memakai var loop (steps, transcript, stuckWatch) lewat
        // closure. Mengembalikan true bila tugas harus BERHENTI sekarang
        // (StuckDetector — deliver sudah dilakukan di sini).
        // Catatan: hasil per call TIDAK ditempel satu-satu ke transkrip —
        // yang ditempel hanya pesan gabungan SATU continuation (seperti
        // 1-1 vc273), supaya hasil tidak ganda di prompt berikutnya.
        // Cabang LEGACY parkir/antrean (Tools.isMemoryWrite / isRisky)
        // TIDAK dipakai di batch: di bawah GATES_DISABLED (vc247) jalur
        // tunggal pun langsung Tools.execute — eksekutor batch menyamai
        // perilaku hidup itu; tool jalur-khusus tidak pernah masuk batch
        // (useBatch menolaknya) sehingga cabang make_plan / analyze_photo /
        // delete_session di jalur tunggal tidak perlu diduplikasi di sini.
        fun execBatchBgVc274(calls: List<JSONObject>): Boolean {
            val usedBgVc274 = HashSet<String>()
            val callIdsBgVc274 = calls.map { BatchToolVc274.nextCallId(it.optString("id"), usedBgVc274).also { usedBgVc274.add(it) } }
            val reportBgVc274 = BatchToolVc274.runBatch(
                calls, callIdsBgVc274,
                stepsLeft = { maxSteps - steps },
                executeOne = { t, a, c, dep ->
                    BatchToolVc274.runOne(ctx, t, a, c, dep) { tt, aa, cc -> Tools.execute(ctx, tt, aa, cc) }
                },
                recordOutcome = { oc ->
                    steps++
                    usedToolsVc249.add(oc.tool)
                    // VC303-P2-10 (CX-3): kumpulkan jejak tool latar (jalur batch).
                    workVc303?.add(WorkItem(oc.tool, oc.ms, oc.ok, RedactSecrets.redact(oc.result).take(120), evidence = oc.result, args = oc.args.toString().take(4000), resultId = oc.resultId ?: "", totalChars = oc.resultTotal, callId = oc.callId))
                    try { EventDb.get(ctx).add("bgbrain", ctx.packageName, "[$origin] tool " + oc.tool + " -> " + oc.result.take(160)) } catch (e: Exception) {}
                    try {
                        val hbBgVc274 = System.currentTimeMillis()
                        val edBgVc274 = prefs(ctx).edit().putLong("bgHeartbeat_" + loopKeyVc263, hbBgVc274)
                        if (!oc.result.startsWith("Gagal:") && !oc.result.startsWith("⚠️ PANGGILAN TOOL DITOLAK")) edBgVc274.putLong("bgLastSuccess_" + loopKeyVc263, hbBgVc274)
                        edBgVc274.apply()
                    } catch (e: Exception) {}
                    if (oc.prereqFailed) "" else stuckWatchVc263.observe(oc.tool, oc.args, oc.result)
                }
            )
            transcript.append("\n").append(BatchToolVc274.combinedMessage("latar", reportBgVc274))
            trimTranscriptVc263(transcript, BG_TRANSCRIPT_MAX_CHARS)
            val stopBgVc274 = reportBgVc274.outcomes.firstOrNull { AgentPrompt.StuckWatch.isStop(it.stuckText) }
            if (stopBgVc274 != null) {
                turnLockedBgVc277 = true
                try { EventDb.get(ctx).add("bgbrain", ctx.packageName, "[$origin] StuckDetector latar: tool " + stopBgVc274.tool + " berputar — tugas dihentikan") } catch (e: Exception) {}
                deliver(ctx, origin, notifTitle, sessionId, "⛔ Tugas latar dihentikan: StuckDetector — tool " + stopBgVc274.tool + " berputar tanpa progres (pola/hasil sama berulang). Hasil sementara ada di Laporan.", jobId)
                return true
            }
            // vc277 O15(b): cap tercapai di tengah batch — berhenti jujur.
            if (steps >= maxSteps) {
                deliver(ctx, origin, notifTitle, sessionId, honestCapStopBgVc277(), jobId)
                return true
            }
            return false
        }
        while (true) {
            // F1.0: kill switch — berhenti dalam 1 langkah berikutnya.
            if (prefs(ctx).getBoolean("f1Off", false)) {
                // vc246 GELOMBANG 5 (AG-M1): pembatalan kill switch kini
                // memberi notifikasi SEKALI per tugas (dulu hanya log) —
                // throttle per jobId agar satu tugas tidak men-spam.
                try {
                    val fk = "killNotif_" + (jobId ?: origin)
                    if (!prefs(ctx).getBoolean(fk, false)) {
                        prefs(ctx).edit().putBoolean(fk, true).apply()
                        notify(ctx, 7798, "⛔ Tugas latar dibatalkan (kill switch)", "Tugas latar dihentikan karena Kill Switch F1 dimatikan.", "Tugas latar ($origin) dibatalkan karena Kill Switch F1 — aktifkan lagi F1 bila tugas boleh jalan.")
                    }
                } catch (e: Exception) {}
                deliver(ctx, origin, notifTitle, sessionId, "⛔ F1 dimatikan oleh user (kill switch) — tugas latar dihentikan.", jobId)
                return
            }
            // F2: berhenti rapi tiap iterasi bila baterai jatuh di bawah ambang
            // di tengah jalan (progres sudah di riwayat lewat deliver).
            if (!batteryOk(ctx)) {
                deliver(ctx, origin, notifTitle, sessionId, "Tugas latar berhenti rapi: baterai di bawah 20% dan tidak dicas — hasil dikirim sebagai notifikasi, lanjutkan manual bila perlu.", jobId)
                return
            }
            val resp = try { chatSync(ctx, api, prompt) } catch (e: Exception) {
                handleError(ctx, task, origin, e, jobId); return
            }
            if (sessionDead(ctx)) prefs(ctx).edit().putBoolean("bgSessionDead", false).apply()
            val multiBgVc249 = AgentPrompt.extractToolCalls(resp)
            val (visible, obj) = AgentPrompt.extractToolCall(resp)
            val callIdBgVc272 = obj?.optString("id") ?: ""
            // vc274 O6+O7: >1 tool per respons di lane latar DIEKSEKUSI
            // sebagai batch (BatchToolVc274 — aturan sama seperti 1-1
            // vc273: baca independen konkuren maks 4, tulis sekuensial
            // berhenti di gagal pertama, 1 call = 1 langkah cap 50).
            // Bila batch tidak dipakai (tool jalur-khusus di dalamnya),
            // jatuh ke jalur tunggal di bawah (obj = call pertama).
            if (multiBgVc249.size > 1 && BatchToolVc274.useBatch(multiBgVc249)) {
                if (visible.isNotBlank()) transcript.append("\nAsisten: ").append(visible.take(2000))
                if (execBatchBgVc274(multiBgVc249)) return
                prompt = continuationPrompt(ctx, transcript, sessionId)
                continue
            }
            if (obj == null) {
                // Model mencoba menutup tugas dengan prosa padahal panggilan
                // terakhirnya DITOLAK (argumen kosong) — paksa satu kali ulang.
                val rj = lastRejected
                if (rj != null && rejectNudges < 1) {
                    rejectNudges++
                    transcript.append("\n[SISTEM] Jawaban ditolak: kamu belum memanggil ulang tool $rj dengan argumen terisi lengkap dari tugas di atas. Panggil tool itu SEKARANG — dilarang mengakhiri tugas dengan pertanyaan atau prosa.")
                    prompt = continuationPrompt(ctx, transcript, sessionId)
                    continue
                }
                val finalBgVc249 = visible.ifBlank { resp.trim() }
                // vc249: gate bukti latar — minta ulang SEKALI, jawaban pertama dibuang.
                // vc266: jawaban yang seluruh klaimnya didukung memori tidak kena gate bukti.
                val allMemBgVc266 = AgentPrompt.allClaimsSupportedByMemory(AgentPrompt.splitClaims(finalBgVc249), pinnedBgVc266.lines(), task)
                if (!gateRetriedVc249 && AgentPrompt.hasFactualClaim(finalBgVc249) && usedToolsVc249.isNotEmpty() && !allMemBgVc266 && !AgentPrompt.evidenceMarkerPresent(finalBgVc249)) {
                    gateRetriedVc249 = true
                    transcript.append("\n[SISTEM] ").append(AgentPrompt.EVIDENCE_GATE_NUDGE)
                    prompt = continuationPrompt(ctx, transcript, sessionId)
                    continue
                }
                var deliverBgVc249 = finalBgVc249
                if (AgentPrompt.hasFactualClaim(finalBgVc249)) {
                    val warnBgVc249 = AgentPrompt.claimWarningForCorpus(finalBgVc249, usedToolsVc249, transcript.toString(), pinnedBgVc266, task)
                    if (warnBgVc249 != null) deliverBgVc249 = "⚠️ Peringatan sistem: klaim ini tidak didukung hasil tool di giliran ini — perbaiki atau nyatakan sebagai dugaan. Detail: $warnBgVc249\n\n" + deliverBgVc249
                }
                // vc249 + vc262 N-M12: reviewer konteks-nol latar — hanya klaim + bukti (tugas, hasil tool, fakta PIN), kini di SESI SERVER
                // SEGAR (forceFresh via DsApiClient) — bukan lagi sesi yang sama dengan giliran agen.
                if (AgentPrompt.hasFactualClaim(finalBgVc249) || usedToolsVc249.isNotEmpty()) {
                    // vc277 O16(b): kuota reviewer habis — reviewer
                    // dilewati dengan penanda, bukan diam-diam lolos.
                    val overCapReviewerBgVc277 = Governor.overCapReviewer(ctx)
                    if (overCapReviewerBgVc277) deliverBgVc249 = Governor.reviewerOverCapText(ctx) + " (reviewer tidak independen — reviewer latar memakai sesi yang sama dengan giliran agen)\n\n" + deliverBgVc249
                    try {
                        val claimsBgVc249 = AgentPrompt.splitClaims(finalBgVc249)
                        if (!overCapReviewerBgVc277 && claimsBgVc249.isNotEmpty()) {
                            val pinnedBgR = try { RedactSecrets.redact(MemoryDb.get(ctx).pinnedFactsText()) } catch (e: Exception) { "" }
                            val evBg = ("TUGAS:\n" + task + "\nHASIL TOOL:\n" + transcript.toString() + "\nFAKTA PIN:\n" + pinnedBgR).take(16000)
                            // vc275 O11: verdict tak terparse -> retry 1x format ketat (counter govCountReviewer — chatSync reviewer=true bump reviewer); tetap gagal -> penanda "belum terverifikasi". Reviewer latar memakai klien DsApiClient yang sama dengan giliran agen, jadi verdict selalu berlabel tidak independen.
                            var unsBg: List<Int> = emptyList()
                            var concBg = false
                            var reviewFailBgVc275 = false
                            try {
                                val rawBgVc249 = chatSync(ctx, api, AgentPrompt.zeroCtxReviewPrompt(claimsBgVc249, evBg), reviewer = true)
                                val (u1BgVc275, c1BgVc275) = AgentPrompt.parseZeroCtxReview(rawBgVc249)
                                if (c1BgVc275) { unsBg = u1BgVc275; concBg = true } else {
                                    val rawBg2Vc275 = chatSync(ctx, api, AgentPrompt.reviewerRetryPrompt(claimsBgVc249, evBg), reviewer = true)
                                    val (u2BgVc275, c2BgVc275) = AgentPrompt.parseZeroCtxReview(rawBg2Vc275)
                                    if (c2BgVc275) { unsBg = u2BgVc275; concBg = true } else reviewFailBgVc275 = true
                                }
                            } catch (e: Exception) { reviewFailBgVc275 = true }
                            // vc266: klaim yang didukung memori dikeluarkan dari daftar tak didukung reviewer.
                            val unsBgFiltVc266 = AgentPrompt.filterUnsupportedByMemory(claimsBgVc249, unsBg, pinnedBgR.lines(), task)
                            val indepBgVc275 = " (reviewer tidak independen — reviewer latar memakai sesi yang sama dengan giliran agen)"
                            if (reviewFailBgVc275) deliverBgVc249 = AgentPrompt.reviewerNotVerifiedNote() + indepBgVc275 + "\n\n" + deliverBgVc249
                            else if (unsBgFiltVc266.isNotEmpty()) deliverBgVc249 = AgentPrompt.reviewerNoteText(claimsBgVc249, unsBgFiltVc266) + indepBgVc275 + "\n\n" + AgentPrompt.applyDoublePass(deliverBgVc249, claimsBgVc249, unsBgFiltVc266)
                        }
                    } catch (e: Exception) {
                        deliverBgVc249 = AgentPrompt.reviewerNotVerifiedNote() + " (reviewer tidak independen — reviewer latar memakai sesi yang sama dengan giliran agen)\n\n" + deliverBgVc249
                    }
                }
                try { EventDb.get(ctx).add("bgbrain", ctx.packageName, "[$origin] selesai: $steps langkah, ${System.currentTimeMillis() - loopStartMsVc263} ms, prompt terakhir ${prompt.length} char, transkrip ${transcript.length} char") } catch (e: Exception) {}
                deliver(ctx, origin, notifTitle, sessionId, deliverBgVc249, jobId)
                return
            }
            val tool = obj.optString("tool")
            val args = obj.optJSONObject("args") ?: JSONObject()
            // vc277 O17(c): giliran terkunci StuckDetector — tolak tanpa
            // eksekusi dan tanpa memakan langkah.
            if (turnLockedBgVc277) {
                deliver(ctx, origin, notifTitle, sessionId, "⛔ Tugas latar dihentikan: giliran dikunci StuckDetector — tool $tool ditolak tanpa dieksekusi (tidak memakan langkah). Hasil sementara ada di Laporan.", jobId)
                return
            }
            usedToolsVc249.add(tool)
            steps++
            if (steps > maxSteps) {
                deliver(ctx, origin, notifTitle, sessionId, honestCapStopBgVc277(), jobId)
                return
            }
            if (visible.isNotBlank()) transcript.append("\nAsisten: ").append(visible.take(2000))
            // VC303-P2-10 (CX-3): R-id sebelum eksekusi — Tools.execute
            // membersihkan ThreadLocal R-id di awal, jadi bila sesudah
            // `when` nilainya BERUBAH, itulah R-id tool ini; bila sama /
            // null, tool tidak dieksekusi / tidak tersimpan (cabang pesan
            // analyze_photo/delete_session/parkir) — jangan pakai id basi.
            val t0BgVc303 = System.currentTimeMillis()
            val ridBeforeBgVc303 = Tools.lastResultId()
            // Slice 12 (vc329): keputusan rute tool lewat seam Rust;
            // eksekusi/parkir/notifikasi tetap Kotlin. Fallback = `when`
            // asli bila seam tak-tersedia.
            val propBgVc329 = Tools.factProposalForMemoryTool(tool, args)
            val routeBgVc329 = bgToolRouteDecision(
                tool, ToolGate.GATES_DISABLED, Tools.isMemoryWrite(tool),
                jobTools, Tools.isRisky(tool, args), autoAllowed(ctx, tool), propBgVc329
            )
            val result: String = if (routeBgVc329 != null) {
                when (routeBgVc329.first) {
                    "execute" -> Tools.execute(ctx, tool, args, callIdBgVc272)
                    "msg_analyze_photo" -> routeBgVc329.second ?: ""
                    "msg_delete_session" -> routeBgVc329.second ?: ""
                    "park_memory_jobtools", "park_memory_noprop", "park_risky" -> {
                        if (!park(ctx, task, origin, notifTitle, transcript, steps, tool, args, visible, jobId, jobTools, sessionId)) deliver(ctx, origin, notifTitle, sessionId, "⛔ Tugas latar dibatalkan: antrean persetujuan Otak Latar penuh (maks 8) — tool $tool tidak diparkir dan tidak dieksekusi.", jobId, "dibatalkan")
                        return
                    }
                    "memory_write" -> {
                        val prop = propBgVc329
                        if (prop == null) {
                            if (!park(ctx, task, origin, notifTitle, transcript, steps, tool, args, visible, jobId, jobTools, sessionId)) deliver(ctx, origin, notifTitle, sessionId, "⛔ Tugas latar dibatalkan: antrean persetujuan Otak Latar penuh (maks 8) — tool $tool tidak diparkir dan tidak dieksekusi.", jobId, "dibatalkan")
                            return
                        }
                        val n = queueBgFactProposal(ctx, prop)
                        try { notify(ctx, 7706, "Proposal memori latar menunggu persetujuan", "Proposal memori latar ditampung (belum tersimpan) — setujui lewat Setup 65. Antrean: $n proposal.") } catch (e: Exception) {}
                        routeBgVc329.second ?: ""
                    }
                    else -> Tools.execute(ctx, tool, args, callIdBgVc272)
                }
            } else {
                when {
                // vc246 GELOMBANG 5 (TL-M9, pilihan implementasi):
                // make_plan/update_plan latar kini BENAR-BENAR menulis
                // rencana lewat Tools.execute (PlanStore vc245), bukan
                // pesan sukses palsu. Batasan didokumentasikan: PlanStore
                // memakai sesi 1-1 aktif saat eksekusi (bukan sesi pemilik
                // tugas latar) — rencana latar tampil di kartu Rencana
                // sesi 1-1 itu, bukan di papan tugas latar.
                tool == "make_plan" || tool == "update_plan" -> Tools.execute(ctx, tool, args, callIdBgVc272)
                tool == "analyze_photo" ->
                    "Tool analyze_photo hanya tersedia dari chat 1-1 — di mode latar tidak dijalankan. Selesaikan tugas tanpa tool itu."
                tool == "delete_session" ->
                    "Tool delete_session hanya tersedia dari UI chat aktif — di mode latar tidak dijalankan. Selesaikan tugas tanpa tool itu."
                // vc247 + vc260: gate dimatikan — semua tool latar
                // dieksekusi langsung. Cabang di bawah ini LEGACY-ONLY
                // dan TIDAK TERJANGKAU selama GATES_DISABLED true: cabang
                // memori (antrean Setup 65) dan cabang parkir notifikasi
                // hanya hidup bila gate dihidupkan lagi. Cabang
                // ketersediaan di atas (make_plan, analyze_photo,
                // delete_session) tetap.
                ToolGate.GATES_DISABLED -> Tools.execute(ctx, tool, args, callIdBgVc272)
                Tools.isMemoryWrite(tool) -> {
                    // vc260: cabang LEGACY-ONLY — mati di bawah
                    // GATES_DISABLED (lihat komentar di atas). Komentar
                    // vc233 di bawah berlaku HANYA bila gate hidup lagi.
                    // vc233 K2b: tool tulis/hapus memori latar TIDAK PERNAH
                    // Tools.execute langsung walau autoAllow ada. Tool
                    // berbentuk fakta ditampung ke antrean persetujuan batch
                    // yang sama dengan ekstraksi 1-1 (prefs
                    // pendingExtractProposals, diputuskan lewat Setup 65)
                    // dan loop LANJUT tanpa menganggapnya tersimpan; tool
                    // memori lain diparkir ke persetujuan notifikasi yang
                    // sudah ada (jalur resume tidak diubah). Tool SAFE tidak
                    // tersentuh cabang ini.
                    if (jobTools != null && tool !in jobTools) {
                        if (!park(ctx, task, origin, notifTitle, transcript, steps, tool, args, visible, jobId, jobTools, sessionId)) deliver(ctx, origin, notifTitle, sessionId, "⛔ Tugas latar dibatalkan: antrean persetujuan Otak Latar penuh (maks 8) — tool $tool tidak diparkir dan tidak dieksekusi.", jobId, "dibatalkan")
                        return
                    }
                    val prop = Tools.factProposalForMemoryTool(tool, args)
                    if (prop != null) {
                        val n = queueBgFactProposal(ctx, prop)
                        try { notify(ctx, 7706, "Proposal memori latar menunggu persetujuan", "Proposal memori latar ditampung (belum tersimpan) — setujui lewat Setup 65. Antrean: $n proposal.") } catch (e: Exception) {}
                        "Proposal memori latar DITAMPUNG di antrean persetujuan batch (Setup 65) — BELUM tersimpan dan BELUM dieksekusi, menunggu persetujuan user: ${prop.first} = ${prop.second.take(140)}. Lanjutkan tugas tanpa menganggap fakta ini sudah tersimpan."
                    } else {
                        if (!park(ctx, task, origin, notifTitle, transcript, steps, tool, args, visible, jobId, jobTools, sessionId)) deliver(ctx, origin, notifTitle, sessionId, "⛔ Tugas latar dibatalkan: antrean persetujuan Otak Latar penuh (maks 8) — tool $tool tidak diparkir dan tidak dieksekusi.", jobId, "dibatalkan")
                        return
                    }
                }
                Tools.isRisky(tool, args) && !(autoAllowed(ctx, tool) && (jobTools == null || tool in jobTools)) -> {
                    if (!park(ctx, task, origin, notifTitle, transcript, steps, tool, args, visible, jobId, jobTools, sessionId)) deliver(ctx, origin, notifTitle, sessionId, "⛔ Tugas latar dibatalkan: antrean persetujuan Otak Latar penuh (maks 8) — tool $tool tidak diparkir dan tidak dieksekusi.", jobId, "dibatalkan")
                    return
                }
                else -> Tools.execute(ctx, tool, args, callIdBgVc272)
                }
            }
            // VC303-P2-10 (CX-3): kumpulkan jejak tool latar (jalur tunggal).
            run {
                val ridAfterBgVc303 = Tools.lastResultId()
                val ridBgVc303 = if (ridAfterBgVc303 != null && ridAfterBgVc303 != ridBeforeBgVc303) ridAfterBgVc303 else ""
                workVc303?.add(WorkItem(tool, System.currentTimeMillis() - t0BgVc303, !WorkLog.isFailed(result), RedactSecrets.redact(result).take(120), evidence = result, args = args.toString().take(4000), resultId = ridBgVc303, totalChars = if (ridBgVc303.isBlank()) 0 else Tools.lastFullLength(), callId = callIdBgVc272))
            }
            lastRejected = if (result.startsWith("⚠️ PANGGILAN TOOL DITOLAK SISTEM")) tool else null
            try { EventDb.get(ctx).add("bgbrain", ctx.packageName, "[$origin] tool $tool -> ${result.take(160)}") } catch (e: Exception) {}
            transcript.append("\n[HASIL TOOL] $tool" + (if (callIdBgVc272.isNotBlank()) " [$callIdBgVc272]" else "") + ": ").append(result.take(8000))
            // vc263 (AG-M6): pangkas transkrip SEBELUM giliran berikut
            // (transkrip penuh dulu disisipkan tanpa batas di [RIWAYAT]).
            trimTranscriptVc263(transcript, BG_TRANSCRIPT_MAX_CHARS)
            // vc263 (N-m21): detektor putaran — suffix ditempel ke transkrip
            // agar model melihatnya; tanda berhenti = tugas dihentikan
            // (bukan retry diam-diam).
            val stuckVc263 = stuckWatchVc263.observe(tool, args, result)
            if (stuckVc263.isNotBlank()) {
                transcript.append(stuckVc263)
                if (AgentPrompt.StuckWatch.isStop(stuckVc263)) {
                    turnLockedBgVc277 = true
                    try { EventDb.get(ctx).add("bgbrain", ctx.packageName, "[$origin] StuckDetector latar: tool $tool berputar — tugas dihentikan") } catch (e: Exception) {}
                    deliver(ctx, origin, notifTitle, sessionId, "⛔ Tugas latar dihentikan: StuckDetector — tool $tool berputar tanpa progres (pola/hasil sama berulang). Hasil sementara ada di Laporan.", jobId)
                    return
                }
            }
            // vc263 (autoloop): heartbeat tiap iterasi; sukses = hasil tool
            // bukan gagal/ditolak. Watchdog membaca kedua stempel ini.
            try {
                val hbVc263 = System.currentTimeMillis()
                val edHbVc263 = prefs(ctx).edit().putLong("bgHeartbeat_" + loopKeyVc263, hbVc263)
                if (!result.startsWith("Gagal:") && !result.startsWith("⚠️ PANGGILAN TOOL DITOLAK")) edHbVc263.putLong("bgLastSuccess_" + loopKeyVc263, hbVc263)
                edHbVc263.apply()
            } catch (e: Exception) {}
            prompt = continuationPrompt(ctx, transcript, sessionId)
            // vc263 (AG-M6): guard cap prompt latar — dulu promptCap sama
            // sekali tidak dipakai di lane ini. Bila masih di atas cap,
            // transkrip dipangkas lagi lalu prompt dibangun ulang SEKALI.
            // vc278 O19: ambang diselaraskan dengan 1-1 — PROAKTIF 80%
            // cap (TurnMetrics.proactiveThreshold), bukan baru di atas
            // cap. Target pangkas + rebuild SEKALI tetap sama.
            val capBgVc278 = TurnMetrics.promptCap(ctx)
            val proactiveBgVc278 = TurnMetrics.proactiveThreshold(capBgVc278)
            if (prompt.length > proactiveBgVc278) {
                val droppedVc263 = trimTranscriptVc263(transcript, BG_TRANSCRIPT_MIN_KEEP_CHARS)
                prompt = continuationPrompt(ctx, transcript, sessionId)
                try { EventDb.get(ctx).add("bgbrain", ctx.packageName, "[$origin] prompt latar melewati ambang proaktif 80% cap ($proactiveBgVc278 dari $capBgVc278) — transkrip dipangkas $droppedVc263 char lalu dibangun ulang (panjang akhir ${prompt.length})") } catch (e: Exception) {}
            }
        }
    }

    // vc263 (AG-M6): pangkas transkrip latar dari AWAL (langkah terlama
    // dibuang dulu, langkah terakhir dipertahankan) dengan penanda jujur
    // di kepala. Mengembalikan char yang dibuang (0 bila sudah muat).
    // vc265 V-M1: baris PERTAMA transkrip adalah header TUGAS VERBATIM
    // ("Tugas ($origin): $task", ditulis runTask) dan dipakai lagi oleh
    // continuationPrompt sebagai "TUGAS (verbatim baris pertama...)".
    // Kompresi TIDAK BOLEH menghapus baris itu: potong hanya konten
    // SETELAH baris pertama; penanda potong disisipkan sesudah header,
    // bukan di kepala transkrip. Bila transkrip satu baris (tanpa "\n")
    // maka seluruh isinya adalah tugas verbatim — jangan dipotong.
    private fun trimTranscriptVc263(transcript: StringBuilder, maxChars: Int): Int {
        val r = BgBrainSeam.trimTranscript(transcript.toString(), maxChars)
        if (r != null) {
            // Rust mengembalikan teks baru (marker sudah disisipkan);
            // ganti isi StringBuilder yang sama (identitas dipertahankan).
            transcript.setLength(0)
            transcript.append(r.first)
            return r.second
        }
        return trimTranscriptVc263Kotlin(transcript, maxChars)
    }

    /** Slice 12 (vc329): pangkas transkrip latar — dipakai bila seam
     *  Rust tak-tersedia. */
    private fun trimTranscriptVc263Kotlin(transcript: StringBuilder, maxChars: Int): Int {
        if (transcript.length <= maxChars) return 0
        val before = transcript.length
        val nl = transcript.indexOf("\n")
        if (nl < 0) return 0
        val headerLen = nl + 1
        if (headerLen >= maxChars) return 0
        val minKeep = BG_TRANSCRIPT_MIN_KEEP_CHARS.coerceAtMost(maxChars)
        val markerReserve = 110
        var keepTotal = maxChars
        if (keepTotal < minKeep) keepTotal = minKeep
        val keepBody = (keepTotal - headerLen - markerReserve).coerceAtLeast(1000)
        val bodyLen = before - headerLen
        if (bodyLen <= keepBody) return 0
        var cutAt = headerLen + (bodyLen - keepBody)
        val nl2 = transcript.indexOf("\n", cutAt)
        if (nl2 >= 0 && nl2 < before - 1000) cutAt = nl2 + 1
        if (cutAt <= headerLen || cutAt >= before - 1000) return 0
        val dropped = cutAt - headerLen
        transcript.delete(headerLen, cutAt)
        transcript.insert(headerLen, "…(transkrip latar dipotong: $dropped char awal dibuang — hanya langkah terakhir dipertahankan)…\n")
        return dropped
    }

    // vc263 (autoloop): watchdog tugas latar — dipanggil tick service
    // latar (HarnessService.proactiveTick, tiap 30 dtk; murah: baca prefs
    // saja). Tugas AKTIF yang heartbeat-nya macet >10 menit ATAU tidak ada
    // tool sukses >10 menit = macet/berputar: SATU notifikasi per tugas +
    // jejak EventDb. TIDAK membunuh/mengulang tugas (bukan retry
    // diam-diam); user yang memutuskan. Penanda proses mati (>2 jam)
    // dibersihkan diam-diam tanpa notifikasi.
    fun watchdogTick(ctx: Context) {
        try {
            val p = prefs(ctx)
            val now = System.currentTimeMillis()
            val keys = ArrayList<String>()
            for ((k, v) in p.all) if (k.startsWith("bgActive_") && v is Long) keys.add(k)
            for (k in keys) {
                val key = k.removePrefix("bgActive_")
                val start = p.getLong(k, 0)
                val hb = p.getLong("bgHeartbeat_" + key, start)
                val lastOk = p.getLong("bgLastSuccess_" + key, start)
                val notified = p.getBoolean("bgWatchdogNotified_" + key, false)
                val (action, reason) = watchdogDecisionKey(start, hb, lastOk, now, notified)
                when (action) {
                    "cleanup" -> {
                        p.edit().remove(k).remove("bgHeartbeat_" + key).remove("bgLastSuccess_" + key).remove("bgWatchdogNotified_" + key).apply()
                        continue
                    }
                    "notify" -> {
                        p.edit().putBoolean("bgWatchdogNotified_" + key, true).apply()
                        val r = reason ?: "tidak ada kemajuan selama 10 menit (giliran macet)"
                        try { EventDb.get(ctx).add("bgbrain", ctx.packageName, "Watchdog latar: tugas $key macet — $r (tugas TIDAK di-retry otomatis)") } catch (e: Exception) {}
                        // vc265 V-m1: ID notifikasi per tugas (bukan satu ID
                        // bersama 7723) — dua tugas macet tidak lagi saling menimpa.
                        try { notify(ctx, NotifIds.bgWatchdogId(key), "⚠️ Tugas latar macet", "Tugas latar ($key) $r. Tidak di-retry otomatis — cek Laporan, lalu jalankan ulang manual bila perlu.", "Tugas latar $key macet: $r. Tugas TIDAK diulang otomatis oleh watchdog — buka Laporan untuk hasil sementara, lalu kirim ulang tugasnya bila masih dibutuhkan.") } catch (e: Exception) {}
                    }
                    else -> continue
                }
            }
        } catch (e: Exception) {}
    }

    /** Slice 12 (vc329): keputusan per kunci `watchdogTick` —
     *  dipakai P15 untuk uji paritas. Action: "skip" | "cleanup" |
     *  "notify". */
    private fun watchdogDecisionKey(start: Long, hb: Long, lastOk: Long, now: Long, notified: Boolean): Pair<String, String?> {
        val r = BgBrainSeam.watchdogDecision(start, hb, lastOk, now, notified)
        if (r != null) return r
        return watchdogDecisionKotlin(start, hb, lastOk, now, notified)
    }

    /** Slice 12 (vc329): keputusan per kunci watchdog (salinan
     *  kondisi asli) — dipakai bila seam Rust tak-tersedia. */
    private fun watchdogDecisionKotlin(start: Long, hb: Long, lastOk: Long, now: Long, notified: Boolean): Pair<String, String?> {
        if (start <= 0) return "skip" to null
        if (now - start > 2 * 3600_000L) return "cleanup" to null
        if (notified) return "skip" to null
        val stalled = now - hb > BG_WATCHDOG_STALL_MS
        val spinning = now - lastOk > BG_WATCHDOG_STALL_MS
        if (!stalled && !spinning) return "skip" to null
        val reason = if (stalled) "tidak ada kemajuan selama 10 menit (giliran macet)" else "tidak ada tool sukses selama 10 menit (berputar tanpa progres)"
        return "notify" to reason
    }

    private fun autoAllowed(ctx: Context, tool: String): Boolean {
        // vc229 R-04(a)(b): gerbang autoAllow yang sama (blokir kelas
        // terkunci + TTL 24 jam + ikatan sesi saat ini).
        return ApprovalPanelStore.autoAllowValid(ctx, tool, prefs(ctx).getLong("currentSession", 1L))
    }

    // vc233 K2b: penampung proposal fakta latar — pola yang sama dengan
    // vc228PersistProposals (prefs harness, JSONArray {k, v}); dedup per
    // key (proposal baru menimpa proposal lama dengan key sama). Diputuskan
    // user lewat Setup 65 (vc228ShowUnconfirmedFactsDialog membaca prefs
    // yang sama dan menyimpan sebagai TERKONFIRMASI bila disetujui).
    private fun queueBgFactProposal(ctx: Context, prop: Pair<String, String>): Int {
        val raw = prefs(ctx).getString("pendingExtractProposals", "") ?: ""
        val r = BgBrainSeam.queueFactProposal(raw, prop.first, prop.second)
        if (r != null) {
            if (r.second < 0) return -1
            val json = r.first ?: return queueBgFactProposalKotlin(ctx, prop)
            prefs(ctx).edit().putString("pendingExtractProposals", json).apply()
            return r.second
        }
        return queueBgFactProposalKotlin(ctx, prop)
    }

    /** Slice 12 (vc329): penampung proposal fakta latar (dedup per
     *  key, replace-or-append) — dipakai bila seam Rust tak-tersedia. */
    private fun queueBgFactProposalKotlin(ctx: Context, prop: Pair<String, String>): Int {
        return try {
            val raw = prefs(ctx).getString("pendingExtractProposals", "") ?: ""
            val old = if (raw.isBlank()) JSONArray() else JSONArray(raw)
            val out = JSONArray()
            var replaced = false
            for (i in 0 until old.length()) {
                val o = old.optJSONObject(i) ?: continue
                if (o.optString("k") == prop.first) {
                    out.put(JSONObject().put("k", prop.first).put("v", prop.second))
                    replaced = true
                } else out.put(o)
            }
            if (!replaced) out.put(JSONObject().put("k", prop.first).put("v", prop.second))
            prefs(ctx).edit().putString("pendingExtractProposals", out.toString()).apply()
            out.length()
        } catch (e: Exception) { -1 }
    }

    // ---------------- Persetujuan tool berisiko ----------------

    // vc242 K-03: antrean parkir per tugas, menggantikan kunci tunggal
    // bgPending/bgPendingTool. Kap maks 8 — parkir ke-9+ DITOLAK eksplisit
    // (park mengembalikan false), tidak pernah menimpa parkir lama. Tiap
    // entri membawa pid (jobId bila ada, sonst id unik) + notifId sendiri,
    // sehingga PendingIntent Setujui/Tolak tiap parkir mengikat parkirnya.
    // Daftar parkir dibaca UI lewat parkedList (Monitor + FloatingAgent).
    const val PARK_QUEUE_MAX = 8
    private const val PARK_QUEUE_KEY = "bgPendingQueue"
    private val parkLock = Any()

    // vc305 H-K1: sumber kebenaran parkir = SQLite durable
    // (ParkedApprovalStoreVc305), BUKAN lagi JSONArray prefs
    // "bgPendingQueue" (tanpa field status, ts==0 abadi).
    // loadParkQueue di bawah dipertahankan sebagai ADAPTER baca saja
    // (dipakai parkedList + migrasi legacy) — tidak ada lagi penulis
    // prefs antrean. notifIdForPid (hashCode % 5000, bisa tabrakan)
    // DIGANTI counter persisten store.nextNotifId().
    fun parkedList(ctx: Context): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        try {
            for (row in ParkedApprovalStoreVc305.get(ctx).listActive()) {
                out.add(Triple(row.pid, row.tool, row.args))
            }
        } catch (e: Exception) {}
        return out
    }

    /** Render ulang kartu parkir yang sudah diputuskan / sedang
     *  diproses sebagai notifikasi "Processing" TANPA tombol aksi.
     *  Dipakai BgApprovalReceiver + resumeApproval saat ketukan
     *  datang untuk pid yang bukan PENDING lagi. */
    fun renderParkedProcessing(ctx: Context, pid: String) {
        try {
            val row = ParkedApprovalStoreVc305.get(ctx).get(pid) ?: return
            val statusTxt = when (row.status) {
                "PROCESSING" -> "sedang diproses"
                "APPROVED" -> "sudah disetujui dan dieksekusi"
                "REJECTED" -> "sudah ditolak"
                "EXPIRED" -> "sudah kedaluwarsa"
                else -> "sudah diputuskan"
            }
            notify(ctx, row.notifId, "\u23f3 Persetujuan $statusTxt",
                "Tool ${row.tool} $statusTxt — ketukan ulang tidak mengeksekusi ulang.",
                "Persetujuan tool ${row.tool} (id: $pid) $statusTxt. Keputusan sudah dicatat secara durable; ketukan notifikasi lama tidak akan mengeksekusi tool apa pun.")
        } catch (e: Exception) {}
    }

    private fun loadParkQueue(ctx: Context): JSONArray {
        // vc305 H-K1: adapter kompat — baca dari store durable.
        // Bila prefs legacy masih berisi entri, impor SEKALI lewat
        // store.migrateLegacy (ts<=0 -> kedaluwarsa, tidak diimpor),
        // batalkan notif entri kedaluwarsa, lalu KOSONGKAN prefs agar
        // tidak ganda. Migrasi kunci tunggal pra-vc242 ("bgPending")
        // dipertahankan di jalur yang sama.
        try {
            val raw = prefs(ctx).getString(PARK_QUEUE_KEY, "") ?: ""
            if (raw.isNotBlank()) {
                val arr = JSONArray(raw)
                val (imported, expired) = ParkedApprovalStoreVc305.get(ctx).migrateLegacy(arr)
                for (o in expired) {
                    try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(o.optInt("notifId", APPROVAL_NOTIF_ID)) } catch (e: Exception) {}
                }
                prefs(ctx).edit().remove(PARK_QUEUE_KEY).apply()
                if (imported > 0 || expired.isNotEmpty()) {
                    try { EventDb.get(ctx).add("bgbrain", ctx.packageName, "Migrasi parkir legacy vc305: $imported diimpor ke store durable, ${expired.size} entri ts<=0 kedaluwarsa") } catch (e: Exception) {}
                }
            } else {
                val legacy = prefs(ctx).getString("bgPending", "") ?: ""
                if (legacy.isNotBlank()) {
                    val o = JSONObject(legacy)
                    if (!o.has("pid")) o.put("pid", o.optString("job").ifBlank { "legacy-" + o.optLong("ts", System.currentTimeMillis()) })
                    if (!o.has("notifId")) o.put("notifId", ParkedApprovalStoreVc305.get(ctx).nextNotifId(ctx))
                    ParkedApprovalStoreVc305.get(ctx).migrateLegacy(JSONArray().put(o))
                    prefs(ctx).edit().remove("bgPending").remove("bgPendingTool").apply()
                }
            }
        } catch (e: Exception) {}
        // Kembalikan JSONArray dari store (bentuk lama, + field status)
        // agar pembaca lama (parkedList dsb.) tetap jalan.
        return try {
            val arr = JSONArray()
            for (row in ParkedApprovalStoreVc305.get(ctx).listActive()) arr.put(row.toJson())
            arr
        } catch (e: Exception) { JSONArray() }
    }

    private fun park(ctx: Context, task: String, origin: String, notifTitle: String?,
                     transcript: StringBuilder, steps: Int, tool: String, args: JSONObject, visible: String, jobId: String? = null, jobTools: Set<String>? = null, ownerSessionId: Long = 0): Boolean {
        var pid: String
        val notifId: Int
        val nowMs = System.currentTimeMillis()
        synchronized(parkLock) {
            try {
                val store = ParkedApprovalStoreVc305.get(ctx)
                // Migrasi legacy dulu supaya hitungan antrean akurat.
                loadParkQueue(ctx)
                if (store.listActive().size >= PARK_QUEUE_MAX) {
                    try { notify(ctx, 7703, "\u23f0 Antrean persetujuan penuh", "Antrean persetujuan Otak Latar penuh (maks $PARK_QUEUE_MAX) \u2014 tool $tool tidak diparkir. Setujui/tolak parkir yang menggantung dulu.") } catch (e: Exception) {}
                    EventDb.get(ctx).add("bgbrain", ctx.packageName, "Parkir DITOLAK (antrean penuh $PARK_QUEUE_MAX): $tool ($origin) \u2014 parkir lama tidak ditimpa")
                    return false
                }
                pid = if (jobId.isNullOrBlank()) ("park-" + nowMs + "-" + store.listActive().size) else jobId
                // vc305 H-K1: notifId UNIK dari counter persisten (bukan
                // hashCode % 5000); entri legacy yang sudah punya notifId
                // tersimpan tetap dipakai apa adanya (di migrasi).
                notifId = store.nextNotifId(ctx)
                val risk = if (ToolGate.isHighStatic(tool, args, ctx)) "HIGH" else "SAFE"
                val row = ParkedApprovalStoreVc305.Row(
                    pid = pid, sessionId = ownerSessionId.toString(), tool = tool,
                    args = args.toString(), risk = risk, status = "PENDING",
                    decision = null, decidedAtMs = 0,
                    createdAtMs = if (nowMs > 0) nowMs else 1L,
                    timeoutMs = ParkedApprovalMachineVc305.staleMsFor(risk),
                    timeoutOwnerMs = 0, notifId = notifId,
                    task = task, origin = origin, title = notifTitle ?: "",
                    transcript = transcript.toString(), steps = steps,
                    job = jobId ?: "", tools = jobTools?.joinToString(",") ?: "",
                    ownerSession = ownerSessionId
                )
                var ok = store.insertIfAbsent(row)
                if (!ok) {
                    // pid bentrok (jobId dipakai ulang): coba sekali
                    // dengan pid unik; bila masih gagal, tolak parkir.
                    pid = pid + "-" + nowMs
                    ok = store.insertIfAbsent(row.copy(pid = pid))
                    if (!ok) return false
                }
            } catch (e: Exception) { return false }
        }
        notifyApproval(ctx, tool, args, task, pid, notifId)
        EventDb.get(ctx).add("bgbrain", ctx.packageName, "Tool berisiko [${ToolGate.level(tool, args, ctx).name}] diparkir menunggu persetujuan: $tool ($origin, id: $pid)")
        return true
    }

    /** v4.6.0: parkir persetujuan dari alur UI depan saat dialog tidak bisa
     *  tampil (activity tidak di depan — dulu crash BadTokenException).
     *  vc242: mengembalikan false bila antrean penuh (tidak diparkir). */
    fun parkExternal(ctx: Context, task: String, transcriptText: String, tool: String, args: JSONObject): Boolean {
        return park(ctx, task, "panel", null, StringBuilder(transcriptText), 1, tool, args, "", null, null, prefs(ctx).getLong("currentSession", 1L))
    }

    /** Dipanggil BgApprovalReceiver saat user mengetuk Setujui/Tolak.
     *  vc305 H-K1 (DURABLE state machine): SEBELUM Tools.execute apa
     *  pun, status durable dibaca + diklaim atomik PENDING->PROCESSING.
     *  - Bukan PENDING (PROCESSING/APPROVED/REJECTED/EXPIRED/tidak ada)
     *    -> render ulang "Processing" TANPA tombol + return TANPA eksekusi.
     *  - Kedaluwarsa (HIGH 15 mnt / lain 24 jam, ts<=0 selalu expired)
     *    -> status=EXPIRED + deliver "dibatalkan" + return.
     *  - HIGH (risk tersimpan == HIGH atau isHighStatic) + disetujui
     *    -> TIDAK PERNAH dieksekusi langsung: isi slot "bgSecondConfirm"
     *    + notif 7704; eksekusi hanya dari resumeSecondConfirm sesudah
     *    konfirmasi kedua lolos (verifikasi ulang status PROCESSING).
     *  - SAFE + disetujui -> eksekusi langsung seperti sebelumnya,
     *    lalu status=APPROVED; ditolak -> status=REJECTED.
     *  Setiap parkir yang keluar dari antrean lewat jalur ini selalu
     *  berakhir dengan deliver (lanjut loop -> deliver, atau dibatalkan
     *  -> deliver di bawah). */
    fun resumeApproval(ctx: Context, approved: Boolean, pid: String? = null) {
        val app = ctx.applicationContext
        executor.execute {
            val store = ParkedApprovalStoreVc305.get(app)
            // Migrasi legacy bila ada (sekali, idempotent).
            loadParkQueue(app)
            val nowMs = System.currentTimeMillis()
            val row: ParkedApprovalStoreVc305.Row?
            if (pid != null) {
                row = store.get(pid)
                if (row == null) return@execute
            } else {
                val actives = store.listActive()
                if (actives.size == 1) row = actives[0]
                else {
                    if (actives.size > 1) {
                        try { notify(app, 7703, "\u23f3 Banyak parkir menunggu", "Ada ${actives.size} persetujuan Otak Latar yang menggantung \u2014 buka app dan putuskan dari daftar parkir (Monitor), jangan dari notifikasi lama.") } catch (e: Exception) {}
                    }
                    return@execute
                }
            }
            val pidVc305 = row.pid
            // Bukan PENDING -> render Processing tanpa tombol, tanpa eksekusi.
            if (row.status != "PENDING") {
                try { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(row.notifId) } catch (e: Exception) {}
                renderParkedProcessing(app, pidVc305)
                return@execute
            }
            // Klaim atomik: tap kedua / balapan kalah -> Processing, tanpa eksekusi.
            if (!store.claim(pidVc305, nowMs)) {
                renderParkedProcessing(app, pidVc305)
                return@execute
            }
            val claimed = store.get(pidVc305) ?: return@execute
            val jobIdForCancel: String? = claimed.job.ifBlank { null }
            val sessionIdForCancel = claimed.ownerSession.takeIf { it > 0 } ?: prefs(app).getLong("currentSession", 1L)
            val tool = claimed.tool
            val args = try { JSONObject(claimed.args) } catch (e: Exception) { JSONObject() }
            // Staleness via machine (ts<=0 SELALU expired — lubang vc304 ditutup).
            if (ParkedApprovalMachineVc305.isExpired(claimed.createdAtMs, claimed.risk, nowMs)) {
                store.markExpired(pidVc305, nowMs)
                try { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(claimed.notifId) } catch (e: Exception) {}
                val isHighVc305 = claimed.risk == "HIGH" || ToolGate.isHighStatic(tool, args, app)
                deliver(app, claimed.origin, claimed.title.ifBlank { null }, sessionIdForCancel, "\u26d4 Tugas latar dibatalkan: persetujuan tool $tool sudah KEDALUWARSA (melewati batas " + (if (isHighVc305) "15 menit (HIGH)" else "24 jam") + ") \u2014 tidak dieksekusi.", jobIdForCancel, "dibatalkan")
                return@execute
            }
            // vc305 H-K1: HIGH dari parkir notifikasi TIDAK dieksekusi dari
            // ketukan notifikasi saja — butuh konfirmasi kedua dalam-app
            // (dialog argumen lengkap + PIN, lihat MainActivity
            // showBgSecondConfirmVc229 + resumeSecondConfirm di bawah).
            // Berbeda dengan vc304, cabang ini HIDUP: gerbangnya klasifikasi
            // statis isHighStatic + label risk tersimpan (BUKAN level()
            // yang selalu SAFE di bawah GATES_DISABLED — GATES_DISABLED
            // sendiri TIDAK disentuh).
            val isHighVc305 = claimed.risk == "HIGH" || ToolGate.isHighStatic(tool, args, app)
            if (approved && isHighVc305) {
                val busy = (prefs(app).getString("bgSecondConfirm", "") ?: "").isNotBlank()
                if (busy) {
                    store.decide(pidVc305, "REJECTED", nowMs)
                    try { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(claimed.notifId) } catch (e: Exception) {}
                    deliver(app, claimed.origin, claimed.title.ifBlank { null }, sessionIdForCancel, "\u26d4 Tugas latar dibatalkan: konfirmasi kedua HIGH sedang dipakai tugas lain \u2014 tool $tool tidak dieksekusi.", jobIdForCancel, "dibatalkan")
                    return@execute
                }
                val sc = JSONObject()
                sc.put("tool", tool); sc.put("args", args.toString()); sc.put("task", claimed.task)
                sc.put("origin", claimed.origin); sc.put("title", claimed.title)
                sc.put("transcript", claimed.transcript); sc.put("steps", claimed.steps)
                sc.put("job", claimed.job); sc.put("tools", claimed.tools); sc.put("ownerSession", sessionIdForCancel)
                sc.put("ts", nowMs); sc.put("pid", pidVc305); sc.put("notifId", claimed.notifId)
                sc.put("risk", claimed.risk)
                prefs(app).edit().putString("bgSecondConfirm", sc.toString()).apply()
                try { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(claimed.notifId) } catch (e: Exception) {}
                notify(app, 7704, "\ud83d\udee1\ufe0f Konfirmasi kedua diperlukan", "Tool HIGH $tool disetujui dari notifikasi \u2014 buka app untuk konfirmasi kedua (argumen lengkap + PIN) sebelum dieksekusi.")
                return@execute
            }
            // SAFE (atau HIGH yang DITOLAK): putuskan set-once, lalu jalankan.
            store.decide(pidVc305, if (approved) "APPROVED" else "REJECTED", nowMs)
            try { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(claimed.notifId) } catch (e: Exception) {}
            val result = if (approved) Tools.execute(app, tool, args)
                else "User MENOLAK tool $tool dari notifikasi persetujuan Otak Latar. Jangan ulangi tool itu; selesaikan tugas tanpanya atau laporkan keterbatasannya dengan jujur."
            val transcript = StringBuilder(claimed.transcript)
            transcript.append("\n[HASIL TOOL] $tool (persetujuan notifikasi: ${if (approved) "DISETUJUI" else "DITOLAK"}): ").append(result.take(8000))
            val sessionId = claimed.ownerSession.takeIf { it > 0 } ?: prefs(app).getLong("currentSession", 1L)
            val api = ensureClient(app) ?: run {
                MemoryDb.enqueuePrompt(app, claimed.task); return@execute
            }
            val jobId = claimed.job.ifBlank { null }
            val jobTools = claimed.tools.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet().takeIf { it.isNotEmpty() }
            loop(app, api, claimed.task, claimed.origin,
                claimed.title.ifBlank { null }, sessionId, transcript, claimed.steps, null, jobId, jobTools)
        }
    }

    /** vc229 R-04(g): konfirmasi kedua dalam-app untuk tool HIGH yang
     *  disetujui dari notifikasi. MainActivity memanggil ini SETELAH user
     *  menyetujui dialog argumen lengkap + PIN (atau menolaknya).
     *  vc305 H-K1: SEBELUM Tools.execute, verifikasi ulang status durable
     *  masih PROCESSING + belum expired (klaim tidak kedaluwarsa).
     *  Slot tanpa pid (legacy pra-vc305) TIDAK dieksekusi — tidak ada
     *  baris durable yang bisa diverifikasi. Keputusan ditulis set-once
     *  di sini (pemilik tunggal decision); MainActivity hanya menghapus
     *  slot prefs + membatalkan notif 7704. */
    fun resumeSecondConfirm(ctx: Context, approved: Boolean, stored: JSONObject) {
        val app = ctx.applicationContext
        executor.execute {
            val o = stored
            val pidVc305 = o.optString("pid", "")
            if (pidVc305.isBlank()) {
                // Slot legacy tanpa pid: tidak ada status durable yang
                // bisa diverifikasi -> TIDAK PERNAH dieksekusi.
                deliver(app, o.optString("origin"), o.optString("title").ifBlank { null }, o.optLong("ownerSession", 0).takeIf { it > 0 } ?: prefs(app).getLong("currentSession", 1L), "\u26d4 Tugas latar dibatalkan: konfirmasi kedua tanpa identitas parkir durable (slot lama pra-vc305) \u2014 tidak dieksekusi.", o.optString("job").ifBlank { null }, "dibatalkan")
                return@execute
            }
            val store = ParkedApprovalStoreVc305.get(app)
            val row = store.get(pidVc305)
            if (row == null || row.status != "PROCESSING") {
                // Sudah diputuskan / kedaluwarsa / hilang: render Processing,
                // jangan eksekusi ulang.
                renderParkedProcessing(app, pidVc305)
                return@execute
            }
            val nowMs = System.currentTimeMillis()
            val tool = row.tool
            val args = try { JSONObject(row.args) } catch (e: Exception) { JSONObject() }
            if (ParkedApprovalMachineVc305.isExpired(row.createdAtMs, row.risk, nowMs) ||
                ParkedApprovalMachineVc305.isClaimExpired(row.toState(), nowMs) ||
                nowMs - o.optLong("ts", 0) > 15 * 60_000L) {
                store.markExpired(pidVc305, nowMs)
                deliver(app, row.origin, row.title.ifBlank { null }, row.ownerSession.takeIf { it > 0 } ?: prefs(app).getLong("currentSession", 1L), "\u26d4 Tugas latar dibatalkan: konfirmasi kedua tool $tool lewat 15 menit \u2014 tidak dieksekusi.", row.job.ifBlank { null }, "dibatalkan")
                return@execute
            }
            store.decide(pidVc305, if (approved) "APPROVED" else "REJECTED", nowMs)
            val result = if (approved) Tools.execute(app, tool, args)
                else "User MENOLAK tool $tool pada konfirmasi kedua dalam-app. Jangan ulangi tool itu; selesaikan tugas tanpanya atau laporkan keterbatasannya dengan jujur."
            val transcript = StringBuilder(row.transcript)
            transcript.append("\n[HASIL TOOL] $tool (konfirmasi kedua dalam-app: ${if (approved) "DISETUJUI" else "DITOLAK"}): ").append(result.take(8000))
            val sessionId = row.ownerSession.takeIf { it > 0 } ?: prefs(app).getLong("currentSession", 1L)
            val api = ensureClient(app) ?: run {
                MemoryDb.enqueuePrompt(app, row.task); return@execute
            }
            val jobId = row.job.ifBlank { null }
            val jobTools = row.tools.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet().takeIf { it.isNotEmpty() }
            loop(app, api, row.task, row.origin,
                row.title.ifBlank { null }, sessionId, transcript, row.steps, null, jobId, jobTools)
        }
    }

    // vc260 H-K1: sapu antrean parkir legacy pra-vc247.
    // Produsen parkir baru sudah mati sejak vc247 (shortcut GATES_DISABLED
    // di loop() + early-return MainActivity), jadi SEMUA isi antrean ini
    // adalah parkir pra-vc247 yang masih bisa di-resume lewat notifikasi
    // lama — dan jendelanya memburuk 15 mnt menjadi 24 jam untuk HIGH.
    // Sapu ini menghapus semuanya + membatalkan notifikasi persetujuan
    // lama + membersihkan slot konfirmasi kedua legacy. Dijalankan dari
    // tick HarnessService; guard staleness di resumeApproval/sweepParked
    // tetap jadi lapis kedua.
    // vc265 N2-M1 (belt-and-braces, sapu ini BUKAN gate): versi vc260
    // hanya jalan SEKALI (flag prefs sekali-jalan vc260) — parkir yang
    // muncul belakangan (mis. restore backup / antrean lama yang lolos)
    // tidak pernah tersapu. Kini sapu JALAN ULANG tiap tick selama masih
    // ada state (antrean parkir tidak kosong ATAU bgSecondConfirm tidak
    // kosong); bila keduanya kosong, no-op diam-diam tanpa notifikasi
    // dan tanpa jejak EventDb supaya tick tidak men-spam.
    // vc305 H-K1: sapu legacy — SEKARANG pemilik timeout, bukan
    // penghapus buta. vc260/vc265 menghapus SELURUH antrean tiap tick;
    // itu akan membunuh store durable yang baru dibangun (resume
    // sesudah restart tidak pernah sampai). Penggantinya: migrasi
    // legacy + sapu kedaluwarsa (sweepExpired) + bersihkan slot
    // konfirmasi kedua yang tidak lagi berpasangan dengan baris
    // PROCESSING. Entri legacy pra-vc247 tetap tidak bisa dieksekusi:
    // semuanya sudah lewat jendela 15 mnt / 24 jam sejak dibuat
    // (berbulan-bulan lalu), jadi sweepExpired men-EXPIRED-kan mereka;
    // entri ts<=0 ikut EXPIRED. Dijalankan dari tick HarnessService.
    fun sweepLegacyParkedOnce(ctx: Context) {
        var removed = 0
        var hadSecondConfirm = false
        try {
            loadParkQueue(ctx)
            val expired = ParkedApprovalStoreVc305.get(ctx).sweepExpired(System.currentTimeMillis())
            for (row in expired) {
                removed++
                try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(row.notifId) } catch (e: Exception) {}
                deliver(ctx, row.origin, row.title.ifBlank { null }, row.ownerSession.takeIf { it > 0 } ?: prefs(ctx).getLong("currentSession", 1L), "\u26d4 Tugas latar dibatalkan: persetujuan tool ${row.tool} sudah KEDALUWARSA \u2014 tidak dieksekusi.", row.job.ifBlank { null }, "dibatalkan")
            }
            val sc = prefs(ctx).getString("bgSecondConfirm", "") ?: ""
            if (sc.isNotBlank()) {
                val so = try { JSONObject(sc) } catch (e: Exception) { null }
                val pidSc = so?.optString("pid", "") ?: ""
                val rowSc = if (pidSc.isNotBlank()) ParkedApprovalStoreVc305.get(ctx).get(pidSc) else null
                val staleSc = so == null || System.currentTimeMillis() - so.optLong("ts", 0) > 15 * 60_000L ||
                    rowSc == null || rowSc.status != "PROCESSING"
                if (staleSc) {
                    hadSecondConfirm = true
                    prefs(ctx).edit().remove("bgSecondConfirm").apply()
                    try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(7704) } catch (e: Exception) {}
                    if (rowSc != null && rowSc.status == "PROCESSING") {
                        ParkedApprovalStoreVc305.get(ctx).markExpired(pidSc, System.currentTimeMillis())
                    }
                }
            }
            if (removed == 0) {
                try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(APPROVAL_NOTIF_ID) } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        if (removed == 0 && !hadSecondConfirm) return
        try { EventDb.get(ctx).add("bgbrain", ctx.packageName, "Sapu parkir vc305 (pemilik timeout): $removed parkir kedaluwarsa di-EXPIRED-kan + notifikasi persetujuan lama dibatalkan" + (if (hadSecondConfirm) " + slot konfirmasi kedua legacy dibersihkan" else "") + " \u2014 parkir lama TIDAK bisa dieksekusi lagi") } catch (e: Exception) {}
        if (removed > 0 || hadSecondConfirm) {
            try { notify(ctx, 7703, "\ud83e\uddf9 Parkir persetujuan lama dibersihkan", "Parkir persetujuan pra-upgrade ($removed item) dihapus demi keamanan \u2014 ketukan notifikasi persetujuan lama tidak akan mengeksekusi tool apa pun.", "Parkir persetujuan dari sebelum upgrade ($removed item) sudah dibersihkan saat upgrade vc305 \u2014 notifikasi persetujuan lama tidak akan mengeksekusi tool apa pun bila diketuk.") } catch (e: Exception) {}
        }
    }

    /** F2: sapu parkir kedaluwarsa — dipanggil tiap tick service.
     *  Persetujuan basi TIDAK PERNAH dieksekusi. vc305: pemilik
     *  timeout adalah store durable (sweepExpired) — PENDING lewat
     *  batas -> EXPIRED, PROCESSING yang klaimnya kedaluwarsa ->
     *  EXPIRED, ts<=0 -> EXPIRED. Tiap parkir basi berakhir dengan
     *  deliver dibatalkan (bukan sekadar notifikasi). Slot konfirmasi
     *  kedua yang kedaluwarsa tetap disapu seperti sebelumnya. */
    fun sweepParked(ctx: Context) {
        try {
            // vc229: sapu konfirmasi kedua yang kedaluwarsa (HIGH, 15 menit).
            val sc = prefs(ctx).getString("bgSecondConfirm", "") ?: ""
            if (sc.isNotBlank()) {
                val so = JSONObject(sc)
                if (System.currentTimeMillis() - so.optLong("ts", 0) > 15 * 60_000L) {
                    prefs(ctx).edit().remove("bgSecondConfirm").apply()
                    val pidSc = so.optString("pid", "")
                    if (pidSc.isNotBlank()) {
                        ParkedApprovalStoreVc305.get(ctx).markExpired(pidSc, System.currentTimeMillis())
                    }
                    deliver(ctx, so.optString("origin"), so.optString("title").ifBlank { null }, so.optLong("ownerSession", 0).takeIf { it > 0 } ?: prefs(ctx).getLong("currentSession", 1L), "\u26d4 Tugas latar dibatalkan: konfirmasi kedua tool ${so.optString("tool")} lewat 15 menit \u2014 tidak dieksekusi.", so.optString("job").ifBlank { null }, "dibatalkan")
                }
            }
        } catch (e: Exception) {}
        try {
            loadParkQueue(ctx)
            val expired = ParkedApprovalStoreVc305.get(ctx).sweepExpired(System.currentTimeMillis())
            for (row in expired) {
                try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(row.notifId) } catch (e: Exception) {}
                deliver(ctx, row.origin, row.title.ifBlank { null }, row.ownerSession.takeIf { it > 0 } ?: prefs(ctx).getLong("currentSession", 1L), "\u26d4 Tugas latar dibatalkan: persetujuan tool ${row.tool} sudah KEDALUWARSA (melewati batas staleness) \u2014 tidak dieksekusi.", row.job.ifBlank { null }, "dibatalkan")
                EventDb.get(ctx).add("bgbrain", ctx.packageName, "Parkir kedaluwarsa dibatalkan: ${row.tool} (id: ${row.pid})")
            }
        } catch (e: Exception) {}
    }

    // ---------------- Pengantaran & error ----------------

    /** Slice 12 (vc329): judul + teks `deliver` — dipakai P15 untuk
     *  uji paritas. */
    private fun deliverTitleText(notifTitle: String?, origin: String, routeFailed: Boolean, answer: String): Pair<String, String> {
        val r = BgBrainSeam.deliverTitle(notifTitle, origin, routeFailed, answer)
        if (r != null) return r
        return deliverTitleKotlin(notifTitle, origin, routeFailed, answer)
    }

    /** Slice 12 (vc329): judul + teks `deliver` (salinan asli) —
     *  dipakai bila seam Rust tak-tersedia. */
    private fun deliverTitleKotlin(notifTitle: String?, origin: String, routeFailed: Boolean, answer: String): Pair<String, String> {
        val text = answer.trim().ifBlank { "(tugas selesai tanpa teks jawaban)" }
        val baseTitle = notifTitle ?: when (origin) {
            "jadwal" -> "🤖 Tugas terjadwal selesai"
            "aturan" -> "⚡ Aturan notifikasi dijalankan"
            "tujuan" -> "🎯 Langkah tujuan dikerjakan"
            "briefing" -> "☀️ Briefing pagi"
            "rekap" -> "🌙 Rekap malam"
            "konsolidasi" -> "🌙 Konsolidasi memori"
            else -> "🤖 Otak Latar — $origin"
        }
        val title = if (routeFailed) "⚠️ Laporan gagal tersimpan — $baseTitle" else baseTitle
        return title to text
    }

    private fun deliver(ctx: Context, origin: String, notifTitle: String?, sessionId: Long, answer: String, jobId: String? = null, jobStatus: String = "selesai") {
        // vc246 GELOMBANG 5 (AG-M6): tugas yang berakhir keluar dari antrean.
        try {
            if (jobId != null) {
                val q = HashSet(prefs(ctx).getStringSet("bgQueue", emptySet()) ?: emptySet())
                val drop = q.filter { it.startsWith(jobId + " |") }
                if (drop.isNotEmpty()) { q.removeAll(drop); prefs(ctx).edit().putStringSet("bgQueue", q).apply() }
            }
        } catch (e: Exception) {}
        // v4.48.9.20 (Q4): hasil latar TIDAK lagi ditulis ke riwayat chat sesi aktif — hanya notifikasi di bawah.
        // vc242 K-03: pembatalan parkir memakai jobStatus "dibatalkan".
        try { if (jobId != null) MemoryDb.get(ctx).logJob(jobId, jobStatus, answer.take(120)) } catch (e: Exception) {}
        // vc243 GELOMBANG 2 (GR-M5): laporan gagal-route TIDAK hilang diam-diam.
        // appendJobReportEx mengembalikan alasan gagal ("" = berhasil); bila
        // gagal, laporan disimpan ke daftar laporan gagal-route (bisa dilihat
        // di dialog LOG) + dicatat EventDb + judul notifikasi diberi tanda.
        val routeReason = try { Automation.appendJobReportEx(ctx, jobId, origin, answer) } catch (e: Exception) { "gagal tulis laporan: ${e.message}" }
        if (routeReason.isNotBlank()) {
            try { Automation.appendUndeliveredReport(ctx, jobId, origin, answer, routeReason) } catch (e: Exception) {}
            try { EventDb.get(ctx).add("bgbrain", ctx.packageName, "Laporan gagal route ($origin, $jobId): $routeReason") } catch (e: Exception) {}
        }
        // Slice 12 (vc329): judul + teks lewat seam Rust (teks =
        // jawaban di-trim + fallback kosong; judul = notifTitle/origin
        // + prefix bila laporan gagal route).
        val (title, text) = deliverTitleText(notifTitle, origin, routeReason.isNotBlank(), answer)
        try { prefs(ctx).edit().putLong("notifSessionId", sessionId).apply() } catch (e: Exception) {}
        // vc246 GELOMBANG 5 (NT-M3): hasil latar dikelompokkan per origin
        // (grup notifikasi per origin di builder notify) + ringkasan tiap
        // kelipatan 5 hasil dari origin yang sama, agar shade tidak
        // bertumpuk puluhan notif tanpa ringkasan.
        val cntKey = "bgNotifCount_" + origin
        val cnt = prefs(ctx).getInt(cntKey, 0) + 1
        prefs(ctx).edit().putInt(cntKey, cnt).apply()
        // vc262 AG-M8: notifikasi 24 jam kini berlabel sumber (jadwal/room/Solo/dll) di teks pendek maupun penuh.
        notify(ctx, NotifIds.bgResultId(origin), title, ("[Sumber: " + origin + "] " + text).take(180), "[Sumber: " + origin + "] " + text, "bg_" + origin)
        if (cnt % 5 == 0) {
            try { notify(ctx, NotifIds.bgSummaryId(origin), "📬 Ringkasan tugas latar ($origin)", "$cnt hasil latar dari $origin — buka Laporan untuk detail tiap tugas.", "Hasil latar $origin sudah $cnt — buka tab Laporan / LOG untuk membaca tiap hasil.", "bg_" + origin) } catch (e: Exception) {}
        }
        EventDb.get(ctx).add("bgbrain", ctx.packageName, "[$origin] selesai: ${text.take(160)}")
    }

    private fun handleError(ctx: Context, task: String, origin: String, e: Exception, jobId: String? = null) {
        try { if (jobId != null) MemoryDb.get(ctx).logJob(jobId, "gagal", (e.message ?: "").take(120)) } catch (e: Exception) {}
        val msg = e.message ?: ""
        EventDb.get(ctx).add("bgbrain", ctx.packageName, "[$origin] GAGAL: ${msg.take(180)}")
        // vc316 P1: klasifikasi via DsFailure.bgActionFor — JANGAN lagi
        // contains(...) manual di sini. Rate-limit MURNI = COOLDOWN (catat
        // bgLastRateLimitTs 15 mnt, antrekan tugas) dan JANGAN tandai
        // bgSessionDead=true + JANGAN notif 7702: rate-limit bukan sesi
        // mati, hanya perlu menunggu. submit() sudah menahan tugas baru
        // selama cooldown (alasan "cooldown") dan tick berikutnya
        // mencoba lagi. Suspend/token tetap SESSION_DEAD seperti dulu.
        when (DsFailure.bgActionFor(e)) {
            DsFailure.BgErrorAction.COOLDOWN -> {
                prefs(ctx).edit().putLong("bgLastRateLimitTs", System.currentTimeMillis()).apply()
                MemoryDb.enqueuePrompt(ctx, task)
                return
            }
            DsFailure.BgErrorAction.SESSION_DEAD -> {
                // Sesi DeepSeek mati: jeda Otak Latar, minta login ulang (sekali per
                // episode), tugas jatuh ke antrean lama sampai login lagi.
                val p = prefs(ctx)
                val firstTime = !p.getBoolean("bgSessionDead", false)
                p.edit().putBoolean("bgSessionDead", true).apply()
                MemoryDb.enqueuePrompt(ctx, task)
                if (firstTime) {
                    notify(ctx, 7702, "🔐 Sesi DeepSeek berakhir — Otak Latar jeda",
                        "Buka app & login ulang di tab Web. Tugas latar sementara diantrekan dan jalan saat app dibuka.",
                        "Sesi DeepSeek untuk Otak Latar tidak terbaca (kemungkinan kedaluwarsa). Buka Harness, login ulang di tab Web; setelah itu tugas terjadwal, aturan, briefing & konsolidasi kembali dieksekusi langsung di latar. Tugas yang datang selama jeda diantrekan seperti pola lama.")
                }
                return
            }
            DsFailure.BgErrorAction.GENERIC -> { /* jatuh ke notif gagal per origin di bawah */ }
        }
        if (origin in setOf("jadwal", "tujuan", "briefing", "rekap", "tes")) {
            notify(ctx, NotifIds.bgFailId(origin), "⚠️ Tugas latar gagal ($origin)", msg.take(180), msg)
        }
    }

    // ---------------- Konsolidasi memori malam ----------------

    /**
     * Housekeeping lokal (arsip riwayat >30 hari + cari pasangan fakta mirip
     * via embedding) lalu ringkasan & penggabungan oleh LLM lewat Otak Latar.
     * Dipanggil HarnessService saat charging malam hari, atau manual (Setup 32).
     */
    fun consolidate(ctx: Context) {
        val app = ctx.applicationContext
        val mdb = MemoryDb.get(app)
        val archived = try { mdb.archiveOldHistory(System.currentTimeMillis() - 30L * 24 * 3600_000) } catch (e: Exception) { 0 }
        var pairsText = ""
        if (Embedder.packDownloaded(app)) {
            try {
                // vc236 M4: pasangan mirip memakai pool penuh (factsAll),
                // bukan 40 terbaru — fakta ke-41+ ikut dicari pasangannya.
                val facts = mdb.factsAll()
                val byRef = mdb.allEmbeddings().filter { it.kind == "fact" }.associateBy { it.ref }
                val sims = ArrayList<String>()
                for (i in facts.indices) for (j in i + 1 until facts.size) {
                    val a = byRef[facts[i].first]; val b = byRef[facts[j].first]
                    if (a != null && b != null && a.vec.size == b.vec.size) {
                        var s = 0.0
                        for (k in a.vec.indices) s += a.vec[k] * b.vec[k]
                        if (s >= 0.88) sims.add("- '${facts[i].first}': \"${facts[i].second.take(90)}\" ≈ '${facts[j].first}': \"${facts[j].second.take(90)}\" (kemiripan ${(s * 100).toInt()}%)")
                    }
                }
                if (sims.isNotEmpty()) pairsText = "\n\nPASANGAN FAKTA MIRIP (gabungkan jadi satu fakta utuh: remember_fact dengan kunci paling jelas berisi gabungannya, lalu forget_fact kunci gandanya):\n" + sims.take(6).joinToString("\n")
            } catch (e: Exception) {}
        }
        val sessionId = prefs(app).getLong("currentSession", 1L)
        // vc235 M2: transkrip Vc230; sessionId = sesi aktif saat
        // konsolidasi jalan (pref currentSession), sama seperti runTask.
        val hist = try { mdb.historyTranscriptVc230(60, 30000, sessionId) } catch (e: Exception) { "" }
        val task = "KONSOLIDASI MEMORI MALAM. Housekeeping lokal sudah jalan: $archived entri riwayat lama (>30 hari) diarsipkan. Tugasmu: (1) Baca transkrip terakhir di bawah — simpan fakta BARU yang permanen tentang user dengan remember_fact (lewati yang sudah ada). (2) Bila ada pasangan fakta mirip di bawah, gabungkan sesuai instruksinya. (3) Akhiri dengan ringkasan 2-3 kalimat tentang konsolidasi malam ini.\n\nTRANKSRIP TERAKHIR:\n$hist$pairsText"
        if (!submit(app, task, "konsolidasi", "🌙 Konsolidasi memori")) {
            MemoryDb.enqueuePrompt(app, task)
        }
        EventDb.get(app).add("konsolidasi", app.packageName, "Konsolidasi dijalankan (arsip $archived entri)")
    }

    // ---------------- Notifikasi ----------------

    private fun ensureChannel(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // vc246 GELOMBANG 5 (NT-M2): nama saluran = tujuan baru
            // (persetujuan latar), bukan lagi "Pengingat & briefing".
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Persetujuan Otak Latar", NotificationManager.IMPORTANCE_HIGH))
        } catch (e: Exception) {}
    }

    // vc246 GELOMBANG 5 (NT-m1/XL-m1): intent notif membawa sesi
    // terakhir (pref notifSessionId, ditulis deliver sebelum notify)
    // agar ketuk notif membuka sesi itu, bukan sekadar beranda.
    private fun openAppIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 7,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("bgSessionId", try { prefs(ctx).getLong("notifSessionId", -1) } catch (e: Exception) { -1L }),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // vc243 GELOMBANG 2 (NT-M1): SATU fungsi sapu bersama untuk semua
    // pemanggil. Daftar ini = registry ID notifikasi harness; 7701 (ID
    // persetujuan latar) sengaja diikutkan — audit mencatatnya tertinggal
    // dari daftar sapu lama. cancelAll menutup ID dinamis (hasil latar acak,
    // hasil koordinator per tugas vc243) yang tidak bisa dienumerasi.
    // Catatan jujur: di sumber vc242 tidak ditemukan pemanggil sapu
    // "bersihkan semua" (daftar HarnessService:190-194 yang dikutip audit
    // tidak ada di tree ini) — fungsi ini menjadi registry + pemanggil
    // tunggalnya mulai vc243, bukan migrasi pemanggil lama.
    fun clearAllHarnessNotifs(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            for (id in listOf(7701, 7702, 7703, 7704, 7705, 7706, 7708, 7710, 7711, 7712)) {
                try { nm.cancel(id) } catch (e: Exception) {}
            }
            try { nm.cancelAll() } catch (e: Exception) {}
        } catch (e: Exception) {}
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String, bigText: String = text, group: String? = null) {
        try {
            ensureChannel(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val nb = Notification.Builder(ctx, CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(bigText))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openAppIntent(ctx))
                .setAutoCancel(true)
            if (group != null) nb.setGroup(group)
            nm.notify(id, nb.build())
        } catch (e: Exception) {}
    }

    // vc246 GELOMBANG 5 (AG-M2): notifikasi tertahan SEKALI per jam bila
    // tugas latar ditunda Governor (jam hening / WiFi-only / kuota) —
    // sebelumnya hanya tercatat di log sehingga user tidak tahu tugasnya
    // ditunda. AG-M1 ada di cabang kill switch loop() di bawah.
    fun notifyGovernorDelay(ctx: Context, reason: String) {
        try {
            val p = prefs(ctx)
            val last = p.getLong("govDelayNotifTs", 0)
            if (System.currentTimeMillis() - last < 3600_000) return
            p.edit().putLong("govDelayNotifTs", System.currentTimeMillis()).apply()
            notify(ctx, 7799, "⏸️ Tugas latar ditunda Governor", "Ditunda karena: $reason. Tugas dicoba lagi di tick berikutnya bila gerbang sudah lolos.", "Tugas latar ditunda Governor ($reason) — bukan gagal. Bila gerbang (jam hening/WiFi/kuota) masih menahan, tugas tetap ditunda sampai gerbang lolos.")
        } catch (e: Exception) {}
    }

    // vc246 GELOMBANG 5 (AG-M6): antrean tugas latar yang terlihat di UI.
    // submit() mencatat job ke set antrean; deliver() menghapusnya.
    // Monitor/LOG membaca snapshot ini (lihat MainActivity refreshMonitor).
    fun queueSnapshot(ctx: Context): List<String> {
        return try { ArrayList(prefs(ctx).getStringSet("bgQueue", emptySet()) ?: emptySet()).sorted() } catch (e: Exception) { emptyList() }
    }
    fun clearQueueSnapshot(ctx: Context) {
        try { prefs(ctx).edit().remove("bgQueue").apply() } catch (e: Exception) {}
    }

    private fun notifyApproval(ctx: Context, tool: String, args: JSONObject, task: String, pid: String, notifId: Int) {
        try {
            ensureChannel(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // vc242 K-03: PendingIntent per parkir — data Uri + ekstra pid
            // mengikat ketukan ke parkirnya (bukan kunci tunggal lagi).
            val approvePi = PendingIntent.getBroadcast(ctx, notifId,
                Intent(ctx, BgApprovalReceiver::class.java).setAction(BgApprovalReceiver.ACTION_APPROVE)
                    .setData(android.net.Uri.parse("harness://bgapproval/$pid"))
                    .putExtra("pid", pid).putExtra("notifId", notifId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // vc305 H-K1: requestCode Tolak = notifId + 5000 (rentang
            // 25000..29999, disjoin dari Setujui 20000..24999). notifId+1
            // lama bisa menabrak requestCode Setujui parkir lain.
            val rejectPi = PendingIntent.getBroadcast(ctx, notifId + 5000,
                Intent(ctx, BgApprovalReceiver::class.java).setAction(BgApprovalReceiver.ACTION_REJECT)
                    .setData(android.net.Uri.parse("harness://bgapproval/$pid"))
                    .putExtra("pid", pid).putExtra("notifId", notifId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val argsFullVc229 = args.toString()
            val argsTooLongVc229 = argsFullVc229.length > 2000
            val body = "Tool berisiko: $tool\nArgs: $argsFullVc229\nTugas: ${task.take(120)}" + (if (argsTooLongVc229) "\n(Argumen >2000 char — persetujuan hanya dari dalam app: buka app.)" else "")
            val nb = Notification.Builder(ctx, CHANNEL)
                .setContentTitle("🛡️ Otak Latar minta persetujuan")
                .setContentText("Tool berisiko: $tool — " + (if (argsTooLongVc229) "buka app untuk persetujuan" else "ketuk Setujui/Tolak"))
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(openAppIntent(ctx))
                .setAutoCancel(false)
            // vc229 R-04(h): argumen lengkap tampil di BigText (scrollable saat
            // notifikasi dibuka); bila >2000 char, tombol Setujui/Tolak
            // DIHILANGKAN — persetujuan wajib dari dalam app.
            if (!argsTooLongVc229) {
                nb.addAction(android.R.drawable.ic_input_add, "Setujui", approvePi)
                nb.addAction(android.R.drawable.ic_delete, "Tolak", rejectPi)
            }
            nm.notify(notifId, nb.build())
        } catch (e: Exception) {}
    }
}
