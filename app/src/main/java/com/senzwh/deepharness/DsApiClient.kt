package com.senzwh.deepharness

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Jalur B (EKSPERIMEN): klien API internal chat.deepseek.com.
 * Auth = token sesi userToken dari localStorage WebView (JWT) + cookie sesi.
 * Tiap completion: create session -> create_pow_challenge -> solve PoW (DeepSeekHashV1)
 * -> POST completion (SSE, format JSON-Patch difragments) dengan header x-ds-pow-response.
 * Token TIDAK pernah disimpan ke disk oleh kelas ini; dibaca segar dari WebView tiap panggilan.
 */
class DsApiClient(private val webView: WebView) {

    private val main = Handler(Looper.getMainLooper())
    // vc271 O5: antrean FIFO ganti penolakan busy (lihat ApiRequestQueue).
    private val queue = ApiRequestQueue("ds")

    fun chat(prompt: String, thinking: Boolean, search: Boolean,
             onPartial: (String) -> Unit, onDone: (String, String) -> Unit, onError: (String) -> Unit,
             bgMode: Boolean = false, reviewer: Boolean = false, forceFresh: Boolean = false, deltaPrompt: String? = null) {
        val run: () -> Unit = {
            Thread {
                // vc271 O5: Governor dihitung di pintu masuk EKSEKUSI request,
                // bukan saat masuk antre — antrean tidak menambah hitungan.
                // vc245 GELOMBANG 4 (XL-Kuota): panggilan latar (bgMode=true, dari
                // BgBrain) masuk hitungan latar terpisah, bukan hitungan interaktif.
                // vc277 O16(a): bump berbobot ukuran prompt yang dikirim.
                val wVc277 = Governor.weightFor(prompt.length)
                try { if (reviewer) Governor.bumpReviewer(webView.context, wVc277) else if (bgMode) Governor.bumpBg(webView.context, wVc277) else Governor.bump(webView.context, wVc277) } catch (e: Exception) {}
                try {
                // vc265 N2-m6: SEMUA percobaan ulang di bawah meneruskan
                // forceFresh — reviewer (forceFresh=true dari BgBrain) harus
                // memakai sesi server segar juga pada retry, bukan cuma
                // pada percobaan pertama. Panggilan biasa tidak berubah
                // (forceFresh=false diteruskan apa adanya).
                val res = try {
                    runChat(prompt, thinking, search, onPartial, forceFresh = forceFresh, bgMode = bgMode, deltaPrompt = deltaPrompt)
                } catch (e: Exception) {
                    // vc316 P1: klasifikasi via DsFailure.classifyFailure —
                    // JANGAN lagi contains(...) manual di sini. Kode HTTP di-parse
                    // dari pesan ("HTTP <code> di ...") dan/atau dari body;
                    // Retry-After dibaca dari suffix "[retry_after=<n>]"
                    // yang ditempel postJson/postCompletion.
                    val failure = DsFailure.classifyFailure(0, "", e)
                    val m = e.message ?: ""
                    if (DsFailure.isStreamCut(m)) {
                        // v4.12.2: stream putus / selesai tanpa jawaban —
                        // ulang maks 2x di sesi yang sama (klien stateless:
                        // POST ulang tidak menumpuk konteks server).
                        var lastErr2: Exception = e
                        var out2: Pair<String, String>? = null
                        val waits2 = longArrayOf(2_000, 5_000)
                        for (w in waits2) {
                            onPartial("⏳ Stream DeepSeek terputus sebelum jawaban selesai — mengulang di sesi yang sama…")
                            Thread.sleep(w)
                            try { out2 = runChat(prompt, thinking, search, onPartial, forceFresh = forceFresh, bgMode = bgMode, deltaPrompt = deltaPrompt); break }
                            catch (e2: Exception) {
                                val m2 = e2.message ?: ""
                                if (DsFailure.isSuspendSignal(m2)) throw e2
                                if (!DsFailure.isStreamCut(m2)) throw e2
                                lastErr2 = e2
                            }
                        }
                        out2 ?: run {
                            // v4.43.17: sesi yang sama putus 2x — jangan bakar
                            // waktu di sesi beracun itu lagi. SEKALI coba sesi
                            // server baru (forceFresh menimpa simpanan), baru
                            // menyerah. Error terbaru dilempar: paling
                            // informatif (401 berarti auth, putus lagi
                            // berarti bukan sesi penyebabnya).
                            onPartial("⏳ Stream putus 2x di sesi yang sama — coba SATU sesi server baru sebelum menyerah…")
                            try { runChat(prompt, thinking, search, onPartial, forceFresh = true, bgMode = bgMode, deltaPrompt = deltaPrompt) }
                            catch (e3: Exception) { throw e3 }
                        }
                    } else if (DsFailure.isSuspendSignal(m)) {
                        // v4.10.2: akun suspend — runChat sudah menandai akun
                        // ini mati. Pindah SEKALI ke akun sehat (failover kini
                        // melewati akun mati), lalu coba sekali. Tanpa akun
                        // sehat: menyerah dengan alasan yang sebenarnya.
                        // (Failover NONAKTIF — AccountPool.FAILOVER_ENABLED
                        // = false — jadi cabang ini praktis melempar dengan
                        // suffix di bawah; jangan hapus, failover bisa
                        // dihidupkan lagi sesudah SlotManager dispek ulang.)
                        val switched = try { AccountPool.failover(webView.context) } catch (e3: Exception) { false }
                        if (!switched) throw Exception((e.message ?: "") + " [failover antar slot dinonaktifkan — akun cadangan tidak dipakai otomatis]")
                        onPartial("⏳ Akun DeepSeek ter-suspended — pindah ke akun cadangan yang sehat, lanjut…")
                        runChat(prompt, thinking, search, onPartial, forceFresh = forceFresh, bgMode = bgMode, deltaPrompt = deltaPrompt)
                    } else when (failure.kind) {
                        FailureKind.RATE_LIMIT -> {
                            // v4.6.0: tangga backoff rate-limit — tugas panjang banyak
                            // langkah tidak boleh putus hanya karena jendela laju server.
                            // vc316 P1: jeda PERTAMA = header Retry-After bila
                            // server mengirimnya (fallback 10/20/35 dtk).
                            var lastErr: Exception = e
                            var out: Pair<String, String>? = null
                            val waits = DsFailure.rateLimitWaits(DsFailure.parseRetryAfterSeconds(m))
                            for (w in waits) {
                                onPartial("⏳ Kena batas laju DeepSeek — jeda ${w / 1000} detik, lanjut otomatis…")
                                Thread.sleep(w)
                                try { out = runChat(prompt, thinking, search, onPartial, forceFresh = forceFresh, bgMode = bgMode, deltaPrompt = deltaPrompt); break }
                                catch (e2: Exception) {
                                    if (DsFailure.classifyFailure(0, "", e2).kind != FailureKind.RATE_LIMIT) throw e2
                                    lastErr = e2
                                }
                            }
                            if (out == null) {
                                val switched = try { AccountPool.failover(webView.context) } catch (e3: Exception) { false }
                                if (switched) {
                                    onPartial("⏳ Slot akun kena batas laju — pindah ke slot akun cadangan, lanjut…")
                                    try { out = runChat(prompt, thinking, search, onPartial, forceFresh = forceFresh, bgMode = bgMode, deltaPrompt = deltaPrompt) } catch (e4: Exception) { lastErr = e4 }
                                }
                            }
                            out ?: throw Exception((lastErr.message ?: "") + " [failover antar slot dinonaktifkan — akun cadangan tidak dipakai otomatis]")
                        }
                        FailureKind.UPSTREAM, FailureKind.OVERLOADED -> {
                            // vc316 P1: 5xx kini RETRYABLE (dulu langsung
                            // dilempar — kebalikan dari yang benar). Maks 2x,
                            // jeda pendek 2/5 dtk; gagal ketiga dilempar apa
                            // adanya. Error lain di tengah jalan dilempar.
                            var lastErr: Exception = e
                            var out: Pair<String, String>? = null
                            for (w in DsFailure.UPSTREAM_WAITS_MS) {
                                onPartial("⏳ Server DeepSeek error (HTTP ${failure.statusCode}) — coba lagi sebentar lagi…")
                                Thread.sleep(w)
                                try { out = runChat(prompt, thinking, search, onPartial, forceFresh = forceFresh, bgMode = bgMode, deltaPrompt = deltaPrompt); break }
                                catch (e2: Exception) {
                                    val k2 = DsFailure.classifyFailure(0, "", e2).kind
                                    if (k2 != FailureKind.UPSTREAM && k2 != FailureKind.OVERLOADED) throw e2
                                    lastErr = e2
                                }
                            }
                            out ?: throw lastErr
                        }
                        FailureKind.TIMEOUT, FailureKind.UNAVAILABLE -> {
                            // Gangguan jaringan sesaat (termasuk DNS ngadat: "Unable to resolve
                            // host", terbukti screenshot 2026-10-22 09:05) -> coba sekali lagi
                            // sebelum menyerah ke jalur WebView.
                            Thread.sleep(DsFailure.TRANSIENT_WAIT_MS)
                            runChat(prompt, thinking, search, onPartial, forceFresh = forceFresh, bgMode = bgMode, deltaPrompt = deltaPrompt)
                        }
                        else -> throw e // AUTH / CONTEXT_WINDOW: non-retryable.
                    }
                }
                queue.finish()
                onDone(res.first, res.second)
            } catch (e: Exception) {
                queue.finish()
                onError("Mode API internal gagal: ${e.message}")
            }
            }.start()
        }
        val accepted = queue.submit(reviewer, run) { onError("Mode API: antrean penuh — permintaan menunggu lebih dari 60 detik tanpa dieksekusi. Coba lagi.") }
        if (!accepted) onError("Mode API: antrean penuh (maks 3 menunggu). Coba lagi sebentar lagi.")
    }

    private fun runChat(prompt: String, thinking: Boolean, search: Boolean, onPartial: (String) -> Unit, fileIds: List<String> = emptyList(), forceFresh: Boolean = false, bgMode: Boolean = false, deltaPrompt: String? = null): Pair<String, String> {
        val pooled = authOverride ?: try { AccountPool.resolve(webView.context) } catch (e: Exception) { null }
        val token = pooled?.first ?: extractToken() ?: throw Exception("token sesi tidak ditemukan di WebView (userToken kosong). Pastikan tab Web sudah LOGIN, lalu coba lagi.")
        val cookie = pooled?.second ?: (CookieManager.getInstance().getCookie("https://chat.deepseek.com") ?: "")
        val ua = snapshotUa()

        val ctx = webView.context
        val fp = try { AccountPool.tokenFp(token) } catch (e: Exception) { "?" }
        // v4.10.2 PEMUTUS ARUS: akun yang sudah ditandai suspend TIDAK dihubungi
        // lagi sama sekali — panggilan dihentikan sebelum menyentuh jaringan.
        if (AccountPool.isDead(ctx, fp)) throw Exception("AKUN DITANDAI TIDAK AKTIF oleh pemutus arus lokal (dari respons server sebelumnya) — tidak ada panggilan yang dikirim untuk akun ini. Bila akun sebenarnya sehat (token tadi mungkin cuma kedaluwarsa): login ulang, atau buka Pool Akun > Reset Tanda Akun. Pakai akun/slot lain juga bisa.")

        // v4.10.2 — SATU SESI HARNESS = SATU SESI SERVER (spesifikasi Sendy
        // 2026-10-23 09:17). Design lama membuat sesi server BARU pada SETIAP
        // panggilan -> ratusan sesi menumpuk di akun -> suspend otomatis.
        // Kini sesi server dibuat SEKALI per (akun, sesi harness) lalu dipakai
        // ulang oleh semua panggilan dalam sesi harness itu.
        // v4.48.9.20 (Q4): tugas latar memakai wadah server SENDIRI.
        // Kunci dsSrvSessId_<fp>_-100 — tidak pernah menyentuh kunci
        // foreground; -100 bukan nomor sesi harness (sesi asli >= 1).
        // Efek samping: celah konkurensi foreground x latar pada wadah
        // yang sama ikut tertutup (wadah kini berbeda).
        val harnessNo = if (bgMode) -100L else try { hprefs().getLong("currentSession", 1L) } catch (e: Exception) { 1L }
        val sessLabel = if (bgMode) "latar" else "#$harnessNo"
        // v4.43.17: forceFresh melewati sesi tersimpan sepenuhnya (dipakai
        // chat() sesudah stream putus 2x di sesi yang sama — sesi lama
        // diduga beracun, jangan dipakai lagi untuk percobaan ini).
        var storedPair = if (forceFresh) null else storedServerSessionAny(fp, harnessNo)
        var stored = storedPair?.second
        // vc321 IXL — RESET PROAKTIF wadah server (mirror SlotClient):
        // akumulasi + prompt berikut akan menembus DEFAULT_CAP =
        // wadah lama dibuang SEBELUM POST. Kunci akumulasi = kunci sesi
        // yang tersimpan (sidik pemilik wadah, bukan sidik token saat
        // ini — samakan dengan storedServerSessionAny). Tanpa ini, sesi
        // panjang menabrak input_exceeds_limit walau tiap prompt di
        // bawah cap (akumulasi ~90.500 char/giliran, terukur probe
        // vc321); tutup-buka app tidak membantu (kunci dsSrvSessId_
        // tersimpan di prefs "harness").
        val accumKeyVc321 = "dsSrvSessId_" + (storedPair?.first ?: fp) + "_" + harnessNo
        // vc322 SESI — kirim DELTA bila wadah sudah ada DAN sudah primed
        // (pernah menerima satu prompt penuh sukses): sesi server
        // menyimpan konteks antar POST, jadi giliran lanjutan hanya
        // membawa yang baru (~1-2k char, bukan ~90-150k). Prompt PENUH
        // tetap dipakai bila wadah belum ada / baru di-reset / forceFresh.
        // Guard reset vc321 TIDAK berubah: hanya berlaku untuk giliran
        // yang mengirim prompt penuh (delta tidak pernah memicu reset —
        // akumulasi + delta selalu di bawah cap bila shouldSendDelta
        // benar, dan bila tidak, giliran ini mengirim penuh + guard
        // jalan seperti biasa).
        val accumVc321 = TurnMetrics.serverAccumVc321(ctx, accumKeyVc321)
        val useDeltaVc322 = TurnMetrics.shouldSendDeltaVc322(stored != null, deltaPrompt != null, forceFresh, false, false, TurnMetrics.isServerSessionPrimedVc322(ctx, accumKeyVc321), accumVc321, deltaPrompt?.length ?: 0)
        val sendPromptVc322 = if (useDeltaVc322) deltaPrompt!! else prompt
        if (!useDeltaVc322 && stored != null && TurnMetrics.shouldResetServerSessionVc321(accumVc321, prompt.length)) {
            logSessEvent("Wadah server di-reset proaktif vc321 (akumulasi $accumVc321 char + prompt ${prompt.length} char mendekati batas input server) — giliran ini memakai wadah baru; konteks dikirim ulang penuh di prompt awal.")
            clearServerSession(storedPair!!.first, harnessNo)
            TurnMetrics.resetServerAccumVc321(ctx, accumKeyVc321)
            TurnMetrics.setServerSessionPrimedVc322(ctx, accumKeyVc321, false)
            storedPair = null
            stored = null
        }
        try {
            val sid = stored ?: run {
                val c = createServerSession(token, cookie, ua)
                storeServerSession(fp, harnessNo, c)
                // vc322 SESI: wadah baru belum primed (belum menerima
                // prompt penuh sukses) + hitung kelahiran untuk
                // penghitung sesi per akun (JEJAK SESI SERVER).
                TurnMetrics.setServerSessionPrimedVc322(ctx, accumKeyVc321, false)
                val sessNvc322 = TurnMetrics.noteServerSessionCreatedVc322(ctx, accumKeyVc321)
                // v4.10.8: kelahiran wadah pertama untuk sesi harness yang
                // SUDAH punya riwayat wajib terlihat — bukan kelahiran diam.
                val hadHistory = try { MemoryDb.get(ctx).historyForSession(harnessNo, 1).isNotEmpty() } catch (e: Exception) { false }
                if (forceFresh) {
                    logSessEvent("Wadah PENGGANTI lahir untuk sesi harness " + sessLabel + " (stream putus 2x di sesi lama — coba sesi baru). [Sesi server ke-" + sessNvc322 + " untuk sesi harness ini.]")
                } else {
                    logSessEvent("Wadah BARU lahir untuk sesi harness " + sessLabel + (if (hadHistory) " (sesi lama, wadah pertamanya di rezim pemetaan)" else " (sesi baru)") + " [Sesi server ke-" + sessNvc322 + " untuk sesi harness ini.]")
                    if (hadHistory) {
                        val note4109 = "♻️ Sesi harness ini belum punya wadah server — membuat satu sekarang. Selanjutnya wadah ini dipakai ulang terus untuk sesi ini."
                        try { onPartial(note4109) } catch (e: Exception) {}
                        try { MemoryDb.get(ctx).addHistory("asisten", note4109, harnessNo) } catch (e: Exception) {}
                    }
                }
                c
            }
            val res = completeWithSession(sid, sendPromptVc322, thinking, search, token, cookie, ua, onPartial, fileIds)
            // vc321 IXL: POST sukses menambah akumulasi wadah ini —
            // vc322: yang dihitung panjang prompt YANG DIKIRIM (delta
            // bila giliran ini delta). Prompt penuh sukses = wadah primed.
            TurnMetrics.addServerAccumVc321(ctx, accumKeyVc321, sendPromptVc322.length)
            if (!useDeltaVc322) TurnMetrics.setServerSessionPrimedVc322(ctx, accumKeyVc321, true)
            // v4.14.2: satu sukses membuktikan token ini HIDUP — hapus
            // tanda matinya (sembuh otomatis sesudah login ulang/rotasi).
            try { AccountPool.clearDead(ctx, fp) } catch (e: Exception) {}
            if (storedPair != null && storedPair.first != fp && stored != null) {
                // v4.10.7: wadah pinjaman lintas sidik BERHASIL dipakai — token
                // hanya berotasi; migrasikan catatan sesi ke sidik aktif.
                storeServerSession(fp, harnessNo, sid)
            }
            return res
        } catch (e: Exception) {
            if (isSuspendSignal(e.message)) {
                // Tandai akun mati + hentikan kerja latar DeepSeek, supaya tidak
                // ada jalur lain yang ikut menembak akun yang sama berulang kali.
                try { AccountPool.markDeadSmart(ctx, fp, e.message) } catch (e2: Exception) {}
                if (AccountPool.isHardSuspend(e.message)) {
                    try { hprefs().edit().putBoolean("bgSessionDead", true).apply() } catch (e2: Exception) {}
                }
                logSessEvent("Akun ditandai " + (if (AccountPool.isHardSuspend(e.message)) "suspend" else "token basi (tanda lunak 45 mnt)") + " — wadah sesi harness #" + harnessNo + " dipertahankan.")
                // v4.10.8: simpanan wadah TIDAK dihapus saat suspend — wadah
                // milik sesi harness ini dan dilanjutkan lagi sesudah akun
                // pulih / login ulang, bukan lahir baru.
                throw e
            }
            if (stored != null && (isSessionInvalidSignal(e.message) || (storedPair != null && storedPair.first != fp))) {
                // v4.10.6: sesi pengganti HANYA bila server menolak sesi itu
                // secara eksplisit. Error lain (jaringan putus, timeout, batas
                // laju) TIDAK membakar sesi baru — error dilempar apa adanya
                // dan tangga retry di chat() memakai ulang sesi yang sama.
                val noteR = "♻️ " + (if (isSessionInvalidSignal(e.message)) "Sesi server tersimpan ditolak server (kemungkinan terhapus)" else "Sesi server tersimpan gagal dipakai setelah ganti akun/token") + " — membuat SATU sesi pengganti untuk sesi harness ini. (Alasan: " + (e.message ?: "").take(120) + ")"
                try { onPartial(noteR) } catch (e3: Exception) {}
                try { MemoryDb.get(ctx).addHistory("asisten", noteR, harnessNo) } catch (e3: Exception) {}
                clearServerSession(fp, harnessNo)
                if (storedPair != null && storedPair.first != fp) clearServerSession(storedPair.first, harnessNo)
                val fresh = createServerSession(token, cookie, ua)
                storeServerSession(fp, harnessNo, fresh)
                // vc322 SESI: pengganti lahir = hitung + belum primed;
                // JEJAK dicatat sesudah wadah benar-benar lahir (bila
                // create gagal, tidak ada kelahiran untuk dicatat).
                // Kunci akumulasi = sidik token saat ini (wadah baru
                // disimpan di bawah fp, bukan sidik pemilik wadah lama).
                val accumKeyFreshVc321 = "dsSrvSessId_" + fp + "_" + harnessNo
                TurnMetrics.setServerSessionPrimedVc322(ctx, accumKeyFreshVc321, false)
                val sessNvc322 = TurnMetrics.noteServerSessionCreatedVc322(ctx, accumKeyFreshVc321)
                logSessEvent("Wadah PENGGANTI lahir untuk sesi harness " + sessLabel + " — " + (e.message ?: "").take(80) + " [Sesi server ke-" + sessNvc322 + " untuk sesi harness ini.]")
                try {
                    val res2 = completeWithSession(fresh, prompt, thinking, search, token, cookie, ua, onPartial, fileIds)
                    // vc321 IXL: wadah pengganti mulai dari 0 + prompt ini.
                    TurnMetrics.resetServerAccumVc321(ctx, accumKeyFreshVc321)
                    TurnMetrics.addServerAccumVc321(ctx, accumKeyFreshVc321, prompt.length)
                    TurnMetrics.setServerSessionPrimedVc322(ctx, accumKeyFreshVc321, true)
                    return res2
                } catch (e2: Exception) {
                    if (isSuspendSignal(e2.message)) {
                        try { AccountPool.markDeadSmart(ctx, fp, e2.message) } catch (e3: Exception) {}
                        if (AccountPool.isHardSuspend(e2.message)) {
                            try { hprefs().edit().putBoolean("bgSessionDead", true).apply() } catch (e3: Exception) {}
                        }
                        // v4.10.8: simpanan wadah dipertahankan saat suspend.
                    }
                    throw e2
                }
            }
            throw e
        }
    }

    private fun hprefs() = webView.context.getSharedPreferences("harness", android.content.Context.MODE_PRIVATE)

    /** v4.43.17 MODE JHON: saklar diagnostik — paksa API Internal + rekam
     *  diagnostik mentah. Token TIDAK PERNAH direkam utuh: hanya 12 karakter
     *  pertama (pola rekaman browser); nilai x-ds-pow-response tidak
     *  direkam sama sekali, hanya panjangnya. */
    private fun jhonMode(): Boolean = try { hprefs().getBoolean("jhonMode", false) } catch (e: Exception) { false }

    private fun bearer12(token: String): String = try { token.take(12) } catch (e: Exception) { "" }

    /** Satu baris diagnostik per panggilan API (maks 20 baris, terlama dibuang).
     *  Hanya aktif bila Mode Jhon nyala. Dibaca viewer Setup Mode Jhon. */
    private fun recordDiag(kind: String, code: Int, dataLines: Int, sawFinish: Boolean, token: String, powLen: Int, err: String, sample: String) {
        if (!jhonMode()) return
        try {
            val p = hprefs()
            val stamp = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val line = "[$stamp] $kind | HTTP $code | baris=$dataLines | finish=$sawFinish | bearer12=${bearer12(token)} | powLen=$powLen | err=${err.take(120)} | sampel=${sample.take(300)}"
            val lines = (p.getString("dsApiDiag", "") ?: "").split("\n").filter { it.isNotBlank() }.toMutableList()
            lines.add(line)
            while (lines.size > 20) lines.removeAt(0)
            p.edit().putString("dsApiDiag", lines.joinToString("\n")).apply()
        } catch (e: Exception) {}
    }

    /** v4.10.9 JEJAK SESI: catatan persisten kelahiran/penggantian wadah —
     * tampil di Profil ("JEJAK SESI SERVER"). Tidak ada entri baru = tidak
     * ada kelahiran; bukti keras dua arah. */
    private fun logSessEvent(text: String) {
        try {
            val p = hprefs()
            val stamp = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date())
            val lines = (p.getString("dsSessEvents", "") ?: "").split("\n").filter { it.isNotBlank() }.toMutableList()
            lines.add("[" + stamp + "] " + text)
            while (lines.size > 30) lines.removeAt(0)
            p.edit().putString("dsSessEvents", lines.joinToString("\n")).apply()
        } catch (e: Exception) {}
    }

    /** v4.10.2: sinyal suspend/blokir akun dari teks error server.
     *  vc316 P1: daftar sinyal hidup di DsFailure (satu tempat). */
    private fun isSuspendSignal(msg: String?): Boolean = DsFailure.isSuspendSignal(msg)

    /** v4.10.6: sinyal EKSPLISIT dari server bahwa sesi chat tidak berlaku
     *  lagi. Hanya sinyal ini yang boleh memicu pembuatan sesi pengganti.
     *  vc316 P1: daftar sinyal hidup di DsFailure (satu tempat). */
    private fun isSessionInvalidSignal(msg: String?): Boolean = DsFailure.isSessionInvalidSignal(msg)

    /** v4.10.6: cari sesi server tersimpan untuk sesi harness ini — sidik
     * token persis. H1 (4.48.8.1): adopsi lintas sidik dinonaktifkan;
     * rotasi token melahirkan wadah segar (prompt dibangun penuh tiap
     * giliran, jadi tidak ada data percakapan yang hilang). */
    private fun storedServerSessionAny(fp: String, harnessNo: Long): Pair<String, String>? {
        storedServerSession(fp, harnessNo)?.let { return Pair(fp, it) }
        // H1 (4.48.8.1): adopsi lintas sidik DINONAKTIFKAN. Hanya sidik
        // persis (akun yang sama) yang dipakai — pencocokan persis sudah
        // ditangani storedServerSession() di atas. Kunci tidak membawa
        // info slot, jadi "slot yang sama" tidak bisa dibuktikan dari
        // kunci; padanan yang bisa ditegakkan adalah sidik yang sama
        // (= akun yang sama). Ganti slot manual maupun failover kini
        // melahirkan wadah segar, bukan memakai wadah akun lain.
        // (storedServerSession tetap menangani migrasi skema lama
        // sidik-yang-sama.) Adopsi slot-keyed menjadi bagian spek ulang
        // SlotManager.
        return null
    }

    private fun storedServerSession(fp: String, harnessNo: Long): String? {
        return try {
            val p = hprefs()
            // v4.10.9: kunci per (sidik, sesi harness). Skema lama memakai
            // SATU slot per sidik untuk semua sesi harness — pindah sesi
            // saling menimpa pemetaan dan melahirkan wadah baru terus.
            val id = p.getString("dsSrvSessId_" + fp + "_" + harnessNo, "") ?: ""
            if (id.isNotBlank()) return id
            // Adopsi skema lama bila entri tunggal itu memang milik sesi ini.
            val legacy = p.getString("dsSrvSessId_" + fp, "") ?: ""
            if (legacy.isNotBlank() && p.getLong("dsSrvSessFor_" + fp, -1L) == harnessNo) {
                storeServerSession(fp, harnessNo, legacy)
                legacy
            } else null
        } catch (e: Exception) { null }
    }

    private fun storeServerSession(fp: String, harnessNo: Long, id: String) {
        // v4.10.9: satu kunci per (sidik, sesi harness) — sesi harness lain
        // tidak bisa menimpa pemetaan sesi ini.
        try { hprefs().edit().putString("dsSrvSessId_" + fp + "_" + harnessNo, id).apply() } catch (e: Exception) {}
    }

    private fun clearServerSession(fp: String, harnessNo: Long) {
        try {
            val p = hprefs()
            val e = p.edit().remove("dsSrvSessId_" + fp + "_" + harnessNo)
            // Bersihkan juga sisa skema lama milik sesi harness ini.
            if (p.getLong("dsSrvSessFor_" + fp, -1L) == harnessNo) e.remove("dsSrvSessId_" + fp).remove("dsSrvSessFor_" + fp)
            e.apply()
        } catch (e: Exception) {}
    }

    private fun createServerSession(token: String, cookie: String, ua: String): String {
        val sessionResp = postJson("/api/v0/chat_session/create", JSONObject(), token, cookie, ua, null)
        return findSessionId(sessionResp) ?: throw Exception("create session: id sesi tidak ditemukan di respon: ${sessionResp.toString().take(200)}")
    }

    /** v4.10.5: PoW DeepSeekHashV1 untuk target_path tertentu (completion / upload_file). */
    private fun solvePowHeader(targetPath: String, token: String, cookie: String, ua: String, onNote: ((String) -> Unit)?): String {
        // PoW skema ASLI: preimage — cari nonce agar
        // DeepSeekHashV1(salt + "_" + expire_at + "_" + nonce) == challenge.
        val powReq = JSONObject().put("target_path", targetPath)
        val powResp = postJson("/api/v0/chat/create_pow_challenge", powReq, token, cookie, ua, null)
        val ch = findChallenge(powResp) ?: throw Exception("create_pow_challenge: objek challenge tidak ditemukan: ${powResp.toString().take(200)}")
        val algorithm = ch.optString("algorithm", "DeepSeekHashV1")
        val challenge = ch.optString("challenge")
        val salt = ch.optString("salt")
        val difficulty = ch.optLong("difficulty", 144000)
        val signature = ch.optString("signature")
        val expireAt = ch.optLong("expire_at", 0)
        val tp = ch.optString("target_path", targetPath)
        if (challenge.isBlank()) throw Exception("challenge PoW kosong.")
        try { onNote?.invoke("Mode API: memecahkan PoW (DeepSeekHashV1, ruang cari $difficulty)…") } catch (e: Exception) {}
        val sol = PowSolver.solveDeepSeek(challenge, salt, expireAt, difficulty)
            ?: throw Exception("nonce PoW tidak ditemukan dalam rentang [0, $difficulty) — skema server mungkin berubah. (alg=$algorithm expire=$expireAt)")
        val powJson = JSONObject()
            .put("algorithm", algorithm)
            .put("challenge", challenge)
            .put("salt", salt)
            .put("signature", signature)
            .put("answer", sol.nonce)
            .put("target_path", tp)
        return Base64.encodeToString(powJson.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /** PoW + completion untuk SATU sesi server yang sudah ada (dipakai ulang). */
    private fun completeWithSession(sessionId: String, prompt: String, thinking: Boolean, search: Boolean, token: String, cookie: String, ua: String, onPartial: (String) -> Unit, fileIds: List<String> = emptyList()): Pair<String, String> {
        val powHeader = solvePowHeader("/api/v0/chat/completion", token, cookie, ua, onPartial)
        // vc230 R-10(a): temperature/max_tokens/top_p TIDAK dikirim.
        // Bukti: body resmi yang teramati hanya chat_session_id,
        // parent_message_id, prompt, ref_file_ids, thinking_enabled,
        // search_enabled (lihat juga pencatatan field body browser di
        // bawah); tidak ada bukti server menerima field decoding tambahan.
        // Mengirim field tak dikenal pada API internal tak resmi berisiko
        // ditolak atau dimakan diam-diam, jadi JANGAN pura-pura terkirim:
        // ini batas arsitektur, dan pengendalian halusinasi diandalkan
        // pada verifikasi pasca-jawaban R-02, bukan tuning sampling.
        val body = JSONObject()
            .put("chat_session_id", sessionId)
            .put("parent_message_id", JSONObject.NULL)
            .put("prompt", prompt)
            .put("ref_file_ids", JSONArray(fileIds))
            .put("thinking_enabled", thinking)
            .put("search_enabled", search)
        val res = postCompletion(body, token, cookie, ua, powHeader, onPartial)
        recordTokens(prompt, res.first, res.second)
        return res
    }

    private fun headers(conn: HttpURLConnection, token: String, cookie: String, ua: String, powHeader: String?) {
        conn.setRequestProperty("Authorization", "Bearer $token")
        if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
        conn.setRequestProperty("User-Agent", ua)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Origin", "https://chat.deepseek.com")
        conn.setRequestProperty("Referer", "https://chat.deepseek.com/")
        conn.setRequestProperty("x-client-platform", "web")
        conn.setRequestProperty("x-client-locale", "id_ID")
        if (powHeader != null) conn.setRequestProperty("x-ds-pow-response", powHeader)
    }

    private fun postJson(path: String, body: JSONObject, token: String, cookie: String, ua: String, powHeader: String?): JSONObject {
        val conn = URL("https://chat.deepseek.com$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.doOutput = true
        headers(conn, token, cookie, ua, powHeader)
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.readText() ?: ""
        } catch (e: Exception) { "" }
        if (code !in 200..299) {
            // vc316 P1: baca Retry-After; ditempel sebagai suffix
            // "[retry_after=<n>]" supaya DsFailure.classifyFailure di
            // chat() bisa memakainya sebagai jeda pertama tangga
            // rate-limit (tanpa state bersama antar-thread).
            val retryAfterSec = DsFailure.parseRetryAfterHeader(conn.getHeaderField("Retry-After"))
            recordDiag(path, code, 0, false, token, powHeader?.length ?: 0, "HTTP $code", text.take(200))
            throw Exception("HTTP $code di $path: ${text.take(250)}" + DsFailure.retryAfterSuffix(retryAfterSec))
        }
        recordDiag(path, code, 0, false, token, powHeader?.length ?: 0, "", "")
        return try { JSONObject(text) } catch (e: Exception) { throw Exception("respon bukan JSON di $path: ${text.take(200)}") }
    }

    private fun postCompletion(body: JSONObject, token: String, cookie: String, ua: String, powHeader: String, onPartial: (String) -> Unit): Pair<String, String> {
        val conn = URL("https://chat.deepseek.com/api/v0/chat/completion").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 240000
        conn.doOutput = true
        headers(conn, token, cookie, ua, powHeader)
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        // vc316 P1: tangkap Retry-After SEKALI di sini — dipakai (a) untuk
        // error HTTP non-2xx di bawah, dan (b) untuk rate_limit yang baru
        // ketahuan dari body SSE (HTTP 200 tapi stream kosong).
        val retryAfterSec = DsFailure.parseRetryAfterHeader(conn.getHeaderField("Retry-After"))
        if (code !in 200..299) {
            val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(250) ?: "" } catch (e: Exception) { "" }
            recordDiag("completion", code, 0, false, token, powHeader.length, "HTTP $code", err)
            throw Exception("HTTP $code di completion: $err" + DsFailure.retryAfterSuffix(retryAfterSec))
        }
        val content = StringBuilder()
        val thinkingBuf = StringBuilder()
        val state = StreamState()
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        var lastPartialAt = 0L
        val rawSample = StringBuilder()
        var dataLines = 0
        while (true) {
            val line = reader.readLine() ?: break
            val payload = when {
                line.startsWith("data:") -> line.substring(5).trim()
                line.startsWith("{") -> line.trim()
                else -> continue
            }
            if (payload.isEmpty() || payload == "[DONE]") continue
            dataLines++
            if (rawSample.length < 900) rawSample.append(payload.take(300)).append(" | ")
            val obj = try { JSONObject(payload) } catch (e: Exception) { continue }
            if (obj.has("code") && obj.optInt("code", 0) != 0) {
                recordDiag("completion", code, dataLines, state.sawFinish, token, powHeader.length, "SERVER ${obj.optInt("code")}", rawSample.toString())
                throw Exception("SERVER ${obj.optInt("code")}: ${obj.optString("msg")}")
            }
            applyChunk(obj, content, thinkingBuf, state)
            val now = System.currentTimeMillis()
            if (content.isNotEmpty() && now - lastPartialAt > 250) {
                lastPartialAt = now
                onPartial(content.toString())
            }
        }
        // Jawaban final HANYA dari fragmen RESPONSE. Fragmen berpikir TIDAK PERNAH
        // jadi jawaban — dulu fallback ke thinkingBuf bikin proses mikir model
        // tampil mentah sebagai jawaban (bug v3.0, screenshot 2026-10-22 08:00).
        // Sejak v3.1.4 teks berpikir diteruskan TERPISAH agar UI bisa menampilkannya
        // sebagai blok terlipat ala web asli (bukan dicampur ke jawaban).
        val full = content.toString()
        if (full.isBlank()) {
            val rawStr = rawSample.toString()
            if (rawStr.contains("rate_limit") || rawStr.contains("too frequent", ignoreCase = true)) {
                recordDiag("completion", code, dataLines, state.sawFinish, token, powHeader.length, "rate_limit", rawStr)
                throw Exception("rate_limit_reached — server DeepSeek membatasi laju permintaan (Messages too frequent); stream berhenti tanpa jawaban final." + DsFailure.retryAfterSuffix(retryAfterSec))
            }
            // v4.12.2: bedakan PUTUS (tanpa penanda selesai resmi) dari
            // selesai-resmi-tanpa-jawaban. Keduanya ditandai agar chat()
            // mengulang di sesi yang sama alih-alih menyerah ke WebView.
            if (!state.sawFinish) {
                recordDiag("completion", code, dataLines, false, token, powHeader.length, "stream_terputus", rawStr)
                throw Exception("stream_terputus: koneksi ke server putus sebelum jawaban selesai (baris data: $dataLines, fragmen berpikir ${thinkingBuf.length} karakter). Cuplikan mentah: ${rawStr.take(400)}")
            }
            recordDiag("completion", code, dataLines, true, token, powHeader.length, "jawaban_kosong", rawStr)
            throw Exception("jawaban_kosong: stream selesai resmi tapi tanpa fragmen jawaban (fragmen berpikir ${thinkingBuf.length} karakter dibuang, baris data: $dataLines). Cuplikan mentah: ${rawStr.take(400)}")
        }
        recordDiag("completion", code, dataLines, state.sawFinish, token, powHeader.length, "", rawSample.toString())
        return Pair(DsSafetyStrip.strip(full), DsSafetyStrip.strip(thinkingBuf.toString().trim()))
    }

    /** Keadaan stream: path patch efektif terakhir + peta indeks fragmen -> fragmen berpikir? */
    private class StreamState {
        var lastPath: String = ""
        /** v4.12.2: true hanya bila event selesai RESMI (response/status
         * FINISHED) terlihat; EOF tanpanya berarti stream PUTUS. */
        var sawFinish = false
        val fragmentThinking = HashMap<Int, Boolean>()
        var fragmentCount = 0
        /** Tipe fragmen yang SEDANG berjalan (terakhir dideklarasikan). Stream asli
         *  (terbukti dari cuplikan mentah 2026-10-22 08:38) menargetkan fragmen
         *  berjalan via indeks -1 atau chunk {"v":...} polos tanpa path sama sekali. */
        var currentThinking = false
    }

    private val fragIdxRe = Regex("fragments/(-?\\d+)")

    /** Daftarkan tipe sebuah fragmen; opsional jadikan fragmen berjalan. */
    private fun declareFragment(state: StreamState, idx: Int, isThink: Boolean, makeCurrent: Boolean = true) {
        state.fragmentThinking[idx] = isThink
        if (idx >= state.fragmentCount) state.fragmentCount = idx + 1
        if (makeCurrent) state.currentThinking = isThink
    }

    /** Tentukan apakah chunk pada path ini milik fragmen berpikir (THINK) atau jawaban (RESPONSE). */
    private fun routeIsThinking(state: StreamState, path: String): Boolean {
        if (path.contains("thinking", ignoreCase = true) || path.contains("reasoning", ignoreCase = true)) return true
        val m = fragIdxRe.find(path) ?: return state.currentThinking
        val raw = m.groupValues[1].toIntOrNull() ?: return state.currentThinking
        if (raw == -1) return state.currentThinking
        val known = state.fragmentThinking[raw]
        if (known != null) return known
        // Indeks baru tepat sesudah fragmen terakhir = fragmen berikutnya; sesudah
        // fase berpikir selesai, fragmen baru selalu fragmen jawaban.
        if (raw == state.fragmentCount && state.fragmentCount > 0) {
            declareFragment(state, raw, false)
            return false
        }
        return state.currentThinking
    }

    /**
     * Terapkan satu chunk SSE DeepSeek (JSON-Patch-ish) ke buffer jawaban.
     * Routing fragmen: stream memakai path fragments/<i>/content untuk fragmen
     * THINK maupun RESPONSE — pembedanya indeks + type fragmen, dan path hanya
     * dikirim sesekali (chunk berikutnya sering tanpa "p"). Karena itu routing
     * memakai path efektif terakhir + peta indeks fragmen dari keadaan awal.
     * Versi lama merutekan berdasar "p" chunk itu sendiri saja (p kosong ->
     * jawaban) sehingga potongan berpikir bocor ke bubble jawaban.
     */
    private fun applyChunk(obj: JSONObject, content: StringBuilder, thinking: StringBuilder, state: StreamState) {
        val p = obj.optString("p", "")
        if (p == "response/status" && obj.optString("v") == "FINISHED") state.sawFinish = true
        // Path status BUKAN path isi: jangan jadikan path efektif, supaya chunk isi
        // berikutnya (yang sering tanpa "p") tidak salah rute gara-gara status.
        if (p.isNotEmpty() && !p.contains("/status")) state.lastPath = p
        if (p.contains("thinking", ignoreCase = true)) state.currentThinking = true
        val effPath = state.lastPath
        if (obj.has("o") && obj.optString("o") == "BATCH") {
            val arr = obj.optJSONArray("v") ?: return
            for (i in 0 until arr.length()) {
                val sub = arr.optJSONObject(i) ?: continue
                applyChunk(sub, content, thinking, state)
            }
            return
        }
        val v = obj.opt("v") ?: return
        when (v) {
            is String -> {
                if (v.isEmpty()) return
                // Label status fragmen ("FINISHED" / "WIP" di path .../status) adalah
                // metadata stream, BUKAN isi jawaban. Dulu lolos ke perakitan teks ->
                // jawaban tercemar "FINISHEDFINISHED" (bug v3.1, screenshot 08:27).
                if (p.contains("/status") || effPath.contains("/status")) {
                    // v4.12.1: FINISHED pada fragmen berpikir = fase berpikir
                    // SELESAI; chunk "fragmen berjalan" (-1) sesudah ini adalah
                    // JAWABAN. Stream mode search tidak mendeklarasikan fragmen
                    // RESPONSE, jadi tanpa flip ini seluruh jawaban terbuang ke
                    // buffer berpikir ("jawaban final kosong", 2026-10-23 14:54).
                    if (v == "FINISHED") {
                        val sIdx = fragIdxRe.find(effPath)?.groupValues?.get(1)?.toIntOrNull()
                        if (sIdx == null || sIdx < 0 || state.fragmentThinking[sIdx] == true) state.currentThinking = false
                    }
                    return
                }
                if ((v == "FINISHED" || v == "WIP") && !p.endsWith("content")) return
                // Hanya rakit string yang memang menargetkan field isi (content),
                // atau yang datang tepat sesudah deklarasi fragmen (path .../fragments).
                val contentish = effPath.isEmpty() || effPath.endsWith("content") || effPath.endsWith("fragments") ||
                    effPath.contains("thinking") || effPath.contains("reasoning")
                if (!contentish) return
                if (routeIsThinking(state, effPath)) thinking.append(v) else content.append(v)
            }
            is JSONArray -> {
                // Deklarasi fragmen bisa datang sebagai ARRAY berisi objek fragmen:
                // {"p":"response/fragments","o":"APPEND","v":[{"type":"RESPONSE","content":"```",...}]}
                // Format asli terbukti dari cuplikan mentah screenshot 08:54. Versi
                // <= 3.1.2 menjatuhkan bentuk array ini diam-diam -> deklarasi fragmen
                // jawaban + isi pembukanya hilang, sisa teks salah rute, jawaban kosong.
                for (i in 0 until v.length()) {
                    val el = v.optJSONObject(i)
                    if (el != null) applyValueObject(obj, el, effPath, content, thinking, state)
                    else {
                        val s = v.optString(i, "")
                        if (s.isNotEmpty()) { if (routeIsThinking(state, effPath)) thinking.append(s) else content.append(s) }
                    }
                }
            }
            is JSONObject -> applyValueObject(obj, v, effPath, content, thinking, state)
        }
    }

    /** Terapkan satu objek nilai stream: deklarasi fragmen / objek berisi content / keadaan awal. */
    private fun applyValueObject(obj: JSONObject, v: JSONObject, effPath: String, content: StringBuilder, thinking: StringBuilder, state: StreamState) {
        val typeStr = v.optString("type", "")
        if (typeStr.isNotEmpty()) {
            // Deklarasi/append SATU fragmen — daftarkan tipenya WALAU content
            // masih kosong. Ini yang dulu hilang: fragmen RESPONSE tiba dengan
            // content "" sehingga tak pernah terdaftar; isi jawaban berikutnya
            // menargetkan fragmen -1 yang peta indeksnya cuma berisi THINK,
            // jadi seluruh jawaban salah rute ke buffer berpikir dan terbuang
            // ("jawaban final kosong", bug v3.1.1 screenshot 08:38).
            val isThink = typeStr.contains("THINK", ignoreCase = true)
            val raw = fragIdxRe.find(effPath)?.groupValues?.get(1)?.toIntOrNull()
            val idx = if (raw != null && raw >= 0) raw else state.fragmentCount
            declareFragment(state, idx, isThink)
            val c = v.optString("content", "")
            if (c.isNotEmpty()) { if (isThink) thinking.append(c) else content.append(c) }
            return
        }
        // Objek berisi content tanpa type: rute berdasar fragmen berjalan.
        val directContent = v.optString("content", "")
        if (directContent.isNotEmpty()) {
            if (routeIsThinking(state, effPath)) thinking.append(directContent) else content.append(directContent)
            return
        }
        // Keadaan awal: v.response.fragments[]
        val resp = v.optJSONObject("response") ?: obj.optJSONObject("response") ?: v
        val frags = resp.optJSONArray("fragments") ?: return
        val firstDecl = state.fragmentCount == 0
        for (i in 0 until frags.length()) {
            val f = frags.optJSONObject(i) ?: continue
            val isThink = f.optString("type", "").contains("THINK", ignoreCase = true)
            // Deklarasi massal tidak menggeser fragmen berjalan (kecuali awal stream).
            declareFragment(state, i, isThink, makeCurrent = false)
            val c = f.optString("content", "")
            if (c.isNotEmpty()) { if (isThink) thinking.append(c) else content.append(c) }
        }
        if (firstDecl && frags.length() > 0) {
            val f0 = frags.optJSONObject(0)
            if (f0 != null) state.currentThinking = f0.optString("type", "").contains("THINK", ignoreCase = true)
        }
    }

    private fun findSessionId(root: JSONObject): String? {
        // data.biz_data.chat_session.id | data.biz_data.id
        val data = root.optJSONObject("data") ?: return null
        val biz = data.optJSONObject("biz_data") ?: data
        val sess = biz.optJSONObject("chat_session")
        val id = sess?.optString("id")?.ifBlank { null } ?: biz.optString("id").ifBlank { null }
        return id
    }

    private fun findChallenge(root: JSONObject): JSONObject? {
        val data = root.optJSONObject("data") ?: return null
        val biz = data.optJSONObject("biz_data") ?: data
        return biz.optJSONObject("challenge") ?: if (biz.has("challenge")) biz else null
    }

    /** UA WebView WAJIB dibaca di UI thread — akses WebView dari thread latar bisa RuntimeException. */
    private fun snapshotUa(): String {
        val latch = CountDownLatch(1)
        var ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
        main.post {
            try { webView.settings.userAgentString?.let { ua = it } } catch (e: Exception) {}
            latch.countDown()
        }
        latch.await(4, TimeUnit.SECONDS)
        return ua
    }

    /** v4.8.0: kredensial tetap untuk instance ini (jalur tim per slot). */
    @Volatile var authOverride: Pair<String, String>? = null

    /** v4.10.3: analisa berkas/gambar LEWAT API INTERNAL — unggah berkas lalu
     *  completion dengan ref_file_ids di sesi server yang sama (dipakai
     *  analyze_photo; perintah Sendy 2026-10-23 agar gambar tidak lagi membuka
     *  sesi chat baru lewat jalur Web). */
    fun uploadFileAndAsk(bytes: ByteArray, fileName: String, mime: String, prompt: String, thinking: Boolean,
                         onDone: (String) -> Unit, onError: (String) -> Unit, onNote: (String) -> Unit = { }) {
        // vc271 O5: unggah berkas ikut antrean klien yang sama (bukan busy).
        val run: () -> Unit = {
            Thread {
                // vc271 O5: Governor dihitung di pintu masuk EKSEKUSI request.
                // vc277 O16(a): bump berbobot ukuran prompt yang dikirim.
                try { Governor.bump(webView.context, Governor.weightFor(prompt.length)) } catch (e: Exception) {}
                try {
                val pooled = authOverride ?: try { AccountPool.resolve(webView.context) } catch (e: Exception) { null }
                val token = pooled?.first ?: extractToken() ?: throw Exception("token sesi tidak ditemukan di WebView (userToken kosong). Pastikan tab Web sudah LOGIN.")
                val cookie = pooled?.second ?: (CookieManager.getInstance().getCookie("https://chat.deepseek.com") ?: "")
                val ua = snapshotUa()
                val fp = try { AccountPool.tokenFp(token) } catch (e: Exception) { "?" }
                if (AccountPool.isDead(webView.context, fp)) throw Exception("AKUN DITANDAI TIDAK AKTIF oleh pemutus arus lokal — unggah foto dihentikan sebelum jaringan. Login ulang atau Reset Tanda Akun di Pool Akun bila akun sebenarnya sehat.")
                val fileId = uploadFile(bytes, fileName, mime, token, cookie, ua)
                    ?: throw Exception("unggah berkas: id berkas tidak ditemukan di respons server.")
                val res = runChat(prompt, thinking, false, onNote, listOf(fileId))
                queue.finish()
                onDone(res.first)
            } catch (e: Exception) {
                queue.finish()
                if (isSuspendSignal(e.message)) {
                    try {
                        val pooled2 = authOverride ?: try { AccountPool.resolve(webView.context) } catch (e2: Exception) { null }
                        val t2 = pooled2?.first ?: extractToken()
                        if (t2 != null) AccountPool.markDeadSmart(webView.context, AccountPool.tokenFp(t2), e.message)
                        if (AccountPool.isHardSuspend(e.message)) hprefs().edit().putBoolean("bgSessionDead", true).apply()
                    } catch (e2: Exception) {}
                }
                onError("Analisa foto via API internal gagal: " + RedactSecrets.redact(e.message ?: "gagal"))
            }
            }.start()
        }
        val accepted = queue.submit(false, run) { onError("Mode API: antrean penuh (maks 3 menunggu). Coba lagi sebentar lagi.") }
        if (!accepted) onError("Mode API: antrean penuh (maks 3 menunggu). Coba lagi sebentar lagi.")
    }

    /** vc212 Item 2+4: unggah BANYAK berkas lalu satu completion dengan seluruh ref_file_ids.
     *  Berkas yang gagal diunggah dicatat di failed (tidak menggagalkan seluruh tugas);
     *  bila semuanya gagal -> onError. Gubernur di-bump sekali (pola uploadFileAndAsk).
     *  vc269: tiap entri failed membawa alasan ("nama: alasan", alasan dipangkas
     *  250 char dan diredaksi) agar banner + failNote + agen bisa membaca penyebab
     *  sebenarnya, bukan cuma nama file. */
    fun uploadFilesAndAsk(files: List<Triple<ByteArray, String, String>>, prompt: String, thinking: Boolean,
                          onDone: (String, List<String>) -> Unit, onError: (String) -> Unit, onNote: (String) -> Unit = { }) {
        // vc271 O5: unggah multi-berkas ikut antrean klien yang sama (bukan busy).
        val run: () -> Unit = {
            Thread {
                // vc271 O5: Governor dihitung di pintu masuk EKSEKUSI request.
                // vc277 O16(a): bump berbobot ukuran prompt yang dikirim.
                try { Governor.bump(webView.context, Governor.weightFor(prompt.length)) } catch (e: Exception) {}
                try {
                val pooled = authOverride ?: try { AccountPool.resolve(webView.context) } catch (e: Exception) { null }
                val token = pooled?.first ?: extractToken() ?: throw Exception("token sesi tidak ditemukan di WebView (userToken kosong). Pastikan tab Web sudah LOGIN.")
                val cookie = pooled?.second ?: (CookieManager.getInstance().getCookie("https://chat.deepseek.com") ?: "")
                val ua = snapshotUa()
                val fp = try { AccountPool.tokenFp(token) } catch (e: Exception) { "?" }
                if (AccountPool.isDead(webView.context, fp)) throw Exception("AKUN DITANDAI TIDAK AKTIF oleh pemutus arus lokal — unggah dihentikan sebelum jaringan.")
                val ids = ArrayList<String>()
                val failed = ArrayList<String>()
                for ((b, fn, mm) in files) {
                    try {
                        val id = uploadFile(b, fn, mm, token, cookie, ua)
                        if (id != null) ids.add(id) else failed.add(fn + ": id berkas tidak ditemukan di respons server")
                    } catch (e: Exception) { failed.add(fn + ": " + RedactSecrets.redact((e.message ?: "gagal").take(250))) }
                }
                if (ids.isEmpty()) throw Exception("Semua berkas gagal diunggah: " + failed.joinToString("; "))
                val res = runChat(prompt, thinking, false, onNote, ids)
                queue.finish()
                onDone(res.first, failed)
            } catch (e: Exception) {
                queue.finish()
                if (isSuspendSignal(e.message)) {
                    try {
                        val pooled2 = authOverride ?: try { AccountPool.resolve(webView.context) } catch (e2: Exception) { null }
                        val t2 = pooled2?.first ?: extractToken()
                        if (t2 != null) AccountPool.markDeadSmart(webView.context, AccountPool.tokenFp(t2), e.message)
                        if (AccountPool.isHardSuspend(e.message)) hprefs().edit().putBoolean("bgSessionDead", true).apply()
                    } catch (e2: Exception) {}
                }
                onError("Unggah multi-file via API internal gagal: " + RedactSecrets.redact(e.message ?: "gagal"))
            }
            }.start()
        }
        val accepted = queue.submit(false, run) { onError("Mode API: antrean penuh (maks 3 menunggu). Coba lagi sebentar lagi.") }
        if (!accepted) onError("Mode API: antrean penuh (maks 3 menunggu). Coba lagi sebentar lagi.")
    }

    /** Unggah satu berkas (multipart) ke API internal; kembalikan id berkas. */
    /** v4.10.7: meteran token lokal. Server web DeepSeek tidak melaporkan
     * usage resmi, jadi token diESTIMASI dari teks persis yang dikirim /
     * diterima — dikalibrasi dengan tokenizer DeepSeek asli (terukur:
     * prosa Indonesia 3,92 kar/token; JSON tool 3,14; campuran prompt 3,44;
     * keluaran memakai 3,7). Hanya giliran yang SELESAI yang tercatat. */
    private fun estTokens(chars: Int, perChar: Double): Long =
        if (chars <= 0) 0L else maxOf(1L, Math.round(chars / perChar))

    private val tokLock = Object()

    private fun recordTokens(prompt: String, reply: String, think: String) {
        try {
            val inTok = estTokens(prompt.length, 3.4)
            val outTok = estTokens(reply.length + think.length, 3.7)
            if (inTok == 0L && outTok == 0L) return
            val p = hprefs()
            synchronized(tokLock) {
                val cur = try { p.getLong("currentSession", 1L) } catch (e: Exception) { 1L }
                val e = p.edit()
                e.putLong("tokInAll", p.getLong("tokInAll", 0L) + inTok)
                e.putLong("tokOutAll", p.getLong("tokOutAll", 0L) + outTok)
                if (p.getLong("tokSessNo", -1L) != cur) {
                    e.putLong("tokSessNo", cur)
                    e.putLong("tokInSess", inTok)
                    e.putLong("tokOutSess", outTok)
                } else {
                    e.putLong("tokInSess", p.getLong("tokInSess", 0L) + inTok)
                    e.putLong("tokOutSess", p.getLong("tokOutSess", 0L) + outTok)
                }
                e.apply()
            }
        } catch (e: Exception) {}
    }

    private fun getJson(path: String, token: String, cookie: String, ua: String): JSONObject {
        val conn = URL("https://chat.deepseek.com$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        headers(conn, token, cookie, ua, null)
        val code = conn.responseCode
        val text = try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.readText() ?: ""
        } catch (e: Exception) { "" }
        if (code !in 200..299) throw Exception("HTTP $code di $path: ${text.take(250)}")
        return try { JSONObject(text) } catch (e: Exception) { throw Exception("respon bukan JSON di $path: ${text.take(200)}") }
    }

    /** v4.10.5: kontrak upload ASLI (riset kode RE terawat, terverifikasi 2026-10):
     * POST /api/v0/file/upload_file + PoW target upload_file + header x-file-size,
     * lalu polling GET /api/v0/file/fetch_files sampai status SUCCESS. */
    private fun uploadFile(bytes: ByteArray, fileName: String, mime: String, token: String, cookie: String, ua: String): String? {
        val powHeader = solvePowHeader("/api/v0/file/upload_file", token, cookie, ua, null)
        val boundary = "----WebKitFormBoundaryTB0pXOQR2RL219Hu"
        val safeName = fileName.map { c -> if (c.code in 32..126 && c != '"') c else '_' }.joinToString("").ifBlank { "file.bin" }
        val conn = URL("https://chat.deepseek.com/api/v0/file/upload_file").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.doOutput = true
        headers(conn, token, cookie, ua, powHeader)
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
        conn.setRequestProperty("x-file-size", bytes.size.toString())
        conn.outputStream.use { out ->
            val head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"\r\nContent-Type: " + mime + "\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.write(("\r\n--" + boundary + "--\r\n").toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val text = try {
            val s = if (code in 200..299) conn.inputStream else conn.errorStream
            s?.bufferedReader()?.readText() ?: ""
        } catch (e: Exception) { "" }
        if (code !in 200..299) throw Exception("HTTP $code di upload berkas: ${text.take(250)}")
        val root = try { JSONObject(text) } catch (e: Exception) { throw Exception("respons upload bukan JSON: ${text.take(200)}") }
        if (root.has("code") && root.optInt("code", 0) != 0) throw Exception("SERVER ${root.optInt("code")}: ${root.optString("msg")}")
        val fileId = findFileId(root) ?: return null
        var status = root.optJSONObject("data")?.optJSONObject("biz_data")?.optString("status") ?: ""
        val deadline = System.currentTimeMillis() + 120000
        var polls = 0
        while (status != "SUCCESS" && System.currentTimeMillis() < deadline) {
            if (status.isNotBlank() && status != "PENDING" && status != "PARSING") break
            if (status.isBlank() && polls >= 3) break
            try { Thread.sleep(800) } catch (e: Exception) {}
            polls++
            val fr = getJson("/api/v0/file/fetch_files?file_ids=" + fileId, token, cookie, ua)
            val f0 = fr.optJSONObject("data")?.optJSONObject("biz_data")?.optJSONArray("files")?.optJSONObject(0)
                ?: throw Exception("fetch_files: berkas tidak ditemukan di respons: ${fr.toString().take(200)}")
            status = f0.optString("status")
        }
        if (status == "SUCCESS" || status.isBlank()) return fileId
        throw Exception("status berkas di server: '" + status + "' (bukan SUCCESS) — file_id=" + fileId)
    }

    private fun findFileId(root: JSONObject): String? {
        val data = root.optJSONObject("data") ?: return null
        val biz = data.optJSONObject("biz_data") ?: data
        biz.optString("id").ifBlank { null }?.let { return it }
        biz.optString("file_id").ifBlank { null }?.let { return it }
        val f = biz.optJSONObject("file") ?: biz.optJSONObject("uploaded_file")
        return f?.optString("id")?.ifBlank { null }
    }

    /** v4.7.0: ambil token sesi WebView saat ini (untuk disimpan ke slot). */
    fun captureTokenNow(): String? = try { extractToken() } catch (e: Exception) { null }

    fun currentCookie(): String = try { CookieManager.getInstance().getCookie("https://chat.deepseek.com") ?: "" } catch (e: Exception) { "" }

    private fun extractToken(): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        main.post {
            try {
                webView.evaluateJavascript(
                    "(function(){try{var t=localStorage.getItem('userToken');if(!t)return '';try{var o=JSON.parse(t);return o.value||t;}catch(e){return t;}}catch(e){return '';}})()"
                ) { res ->
                    result = try {
                        if (res == null) null else JSONArray("[$res]").optString(0).ifBlank { null }
                    } catch (e: Exception) { null }
                    latch.countDown()
                }
            } catch (e: Exception) { latch.countDown() }
        }
        latch.await(6, TimeUnit.SECONDS)
        return result
    }

    /** v4.43.17 MODE JHON: shim rekaman browser DeepSeek — hanya untuk /api/v0/chat/completion.
     *  Pasif: membungkus fetch/XHR halaman, merekam request terakhir ke
     *  window.__dsCap + localStorage __dsCapComp/__dsCap. Disuntik dari
     *  MainActivity.onPageFinished hanya bila Mode Jhon nyala. */
    private val dsCaptureShim = """(function(){if(window.__dsCap)return;window.__dsCap={count:0,last:null};function normHeaders(h){var o={};try{if(!h)return o;if(typeof h.forEach==="function"){h.forEach(function(v,k){o[k]=v;});}else if(h instanceof Array){for(var i=0;i<h.length;i++){o[h[i][0]]=h[i][1];}}else{for(var k in h){o[k]=h[k];}}}catch(e){}return o;}function rec(url,headers,body){try{var b=null;if(typeof body==="string")b=body;else if(body)b="[non-string]";var item={url:String(url),headers:normHeaders(headers),body:b,ts:Date.now()};window.__dsCap.last=item;window.__dsCap.count++;try{localStorage.setItem("__dsCapComp",JSON.stringify(item));}catch(e){}try{localStorage.setItem("__dsCap",JSON.stringify(item));}catch(e){}}catch(e){}}var of=window.fetch;window.fetch=function(url,opts){try{var u=String(url);if(u.indexOf("/api/v0/chat/completion")!==-1){rec(u,(opts&&opts.headers)||null,(opts&&opts.body)||null);}}catch(e){}return of.apply(this,arguments);};var ox=XMLHttpRequest.prototype.open,xs=XMLHttpRequest.prototype.send,xh=XMLHttpRequest.prototype.setRequestHeader;XMLHttpRequest.prototype.setRequestHeader=function(k,v){try{this.__dsH=this.__dsH||{};this.__dsH[k]=v;}catch(e){}return xh.apply(this,arguments);};XMLHttpRequest.prototype.open=function(m,u){this.__dsU=String(u);return ox.apply(this,arguments);};XMLHttpRequest.prototype.send=function(b){try{if(this.__dsU&&this.__dsU.indexOf("/api/v0/chat/completion")!==-1){rec(this.__dsU,this.__dsH||null,b);}}catch(e){}return xs.apply(this,arguments);};})();"""

    fun injectDeepSeekCaptureShim() {
        try { main.post { try { webView.evaluateJavascript(dsCaptureShim, null) } catch (e: Exception) {} } } catch (e: Exception) {}
    }

    private fun evalJsSync(js: String, timeoutMs: Long = 8000): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        main.post {
            try {
                webView.evaluateJavascript(js) { res ->
                    result = try {
                        if (res == null) null else JSONArray("[$res]").optString(0).ifBlank { null }
                    } catch (e: Exception) { null }
                    latch.countDown()
                }
            } catch (e: Exception) { latch.countDown() }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }

    /** Baca rekaman browser DeepSeek terakhir; Bearer hanya 12 char awal +
     *  status SAMA/BEDA vs token panenan WebView — nilai token tidak pernah
     *  ditampilkan. Body prompt tidak ditampilkan (hanya nama field +
     *  panjang) — yang dibandingkan adalah header (Bearer, PoW, UA) vs
     *  request native di tombol 12c. */
    fun readDeepSeekCapture(onResult: (String) -> Unit) {
        Thread {
            val lines = mutableListOf<String>()
            try {
                val raw = evalJsSync("""(function(){try{var w=window.__dsCap||{};var c=w.last||null;if(!c){var s=localStorage.getItem('__dsCapComp')||localStorage.getItem('__dsCap');if(s)c=JSON.parse(s);}return JSON.stringify({count:(w.count||0),last:c});}catch(e){return JSON.stringify({error:String(e)});}})()""")
                if (raw == null) {
                    lines.add("Belum ada rekaman. Nyalakan Mode Jhon (tombol 12b), buka tab Web, chat di chat.deepseek.com, lalu tap tombol ini lagi.")
                } else {
                    val rep = JSONObject(raw)
                    if (rep.has("error")) {
                        lines.add("Gagal baca rekaman: " + rep.optString("error").take(150))
                    } else {
                        val last = rep.optJSONObject("last")
                        if (last == null) {
                            lines.add("Belum ada rekaman (0 chat terekam). Nyalakan Mode Jhon, buka tab Web, chat di chat.deepseek.com, lalu tap tombol ini lagi.")
                        } else {
                            lines.add("Rekaman ke-" + rep.optInt("count", 0) + " • URL: " + last.optString("url").take(120))
                            val headers = last.optJSONObject("headers") ?: JSONObject()
                            val auth = headers.optString("Authorization", "")
                            val bearer = auth.removePrefix("Bearer ").trim()
                            val harvested = try { captureTokenNow() } catch (e: Exception) { null }
                            if (bearer.isNotBlank()) {
                                lines.add("Bearer browser: " + bearer.take(12) + "… • " + if (!harvested.isNullOrBlank() && bearer == harvested) "SAMA dengan token panenan WebView ✅" else "BEDA dari token panenan WebView ⚠️")
                            } else {
                                lines.add("Bearer browser: tidak terlihat dari JS (halaman mungkin mengandalkan cookie saja)")
                            }
                            val pow = headers.optString("x-ds-pow-response", "")
                            if (pow.isNotBlank()) lines.add("x-ds-pow-response browser: ada, panjang " + pow.length + " (bandingkan powLen rekaman native di tombol 12c)")
                            else lines.add("x-ds-pow-response browser: TIDAK ADA ⚠️")
                            val ua = headers.optString("User-Agent", "")
                            if (ua.isNotBlank()) lines.add("User-Agent browser: " + ua.take(80))
                            val bodyRaw = last.optString("body", "")
                            if (bodyRaw.isNotBlank() && bodyRaw != "[non-string]") {
                                try {
                                    val b = JSONObject(bodyRaw)
                                    val keys = mutableListOf<String>()
                                    val it = b.keys()
                                    while (it.hasNext()) keys.add(it.next())
                                    lines.add("Body browser — field: " + keys.joinToString(", ") + " • thinking_enabled: " + b.optBoolean("thinking_enabled") + " • search_enabled: " + b.optBoolean("search_enabled") + " • prompt: " + b.optString("prompt", "").length + " karakter (isi tidak ditampilkan)")
                                } catch (e: Exception) {
                                    lines.add("Body browser: bukan JSON (panjang " + bodyRaw.length + ").")
                                }
                            } else {
                                lines.add("Body tidak terekam (kosong / non-string).")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                lines.add("Gagal baca rekaman: ${(e.message ?: e.toString()).take(200)}")
            }
            val report = lines.joinToString("\n")
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(report) }
        }.start()
    }
}
