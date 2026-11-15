package com.senzwh.deepharness

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v4.46.0 (F1/F2/F3) — Klien API Internal DeepSeek per SLOT BROWSER.
 *
 * Jalur chat untuk satu akun DeepSeek yang loginnya disimpan di satu slot
 * browser proses-terpisah (SlotBrowser). Auth = token + cookie yang dipanen
 * dari storage slot itu sendiri, diteruskan sebagai parameter konstruktor —
 * kelas ini TIDAK membaca WebView apa pun dan TIDAK pernah menyimpan
 * token/cookie ke disk (penyimpanan hanya di AccountPool / SecureStore,
 * lewat SlotHarvestReceiver).
 *
 * Protokol DISALIN PERSIS dari DsApiClient (jalur WebView utama):
 * header, PoW DeepSeekHashV1, body completion (parent_message_id SELALU
 * null — prompt dibangun penuh tiap giliran), parsing SSE JSON-Patch
 * berfragmen, klasifikasi error. Yang SENGAJA BERBEDA (alasan keamanan /
 * disiplin multi-akun):
 *  1. Pemetaan sesi server EKSKET per slot: kunci
 *     "dsSlotSrv_<slot>_<harnessNo>". TIDAK ADA adopsi lintas slot
 *     (DsApiClient punya storedServerSessionAny yang bisa mengadopsi sesi
 *     sidik lain — di sini dilarang: satu slot tidak boleh memakai sesi
 *     server milik akun lain).
 *  2. TIDAK ADA failover global: bila akun slot ini suspend / kena batas
 *     laju, error dilempar apa adanya sesudah tangga retry. Memindahkan
 *     giliran ke akun lain diam-diam akan melanggar pilihan slot user.
 *  3. TIDAK ADA recordDiag (diagnostik Mode Jhon tetap milik DsApiClient).
 *  4. TIDAK ADA unggah berkas (slot chat = teks; analisa foto tetap lewat
 *     jalur utama).
 *  5. Token/cookie TIDAK PERNAH di-log, di-print, atau dimasukkan ke
 *     bubble/UI. Log jejak sesi hanya berisi nomor slot + nomor sesi
 *     harness + jenis peristiwa.
 *
 * Disiplin sesi (spesifikasi Sendy 2026-10-23 09:17, berlaku per slot):
 * SATU slot = SATU sesi server per sesi harness. Sesi dibuat SEKALI lalu
 * dipakai ulang; sesi pengganti HANYA bila server menolak sesi itu secara
 * eksplisit (isSessionInvalidSignal), atau SEKALI via forceFresh sesudah
 * stream putus 2x di sesi yang sama.
 */
class SlotClient(private val ctx: Context, private val slot: Int, private val token: String, private val cookie: String) {

    companion object {
        /** UA yang SAMA dengan WebView utama app (MainActivity.setupWebView)
         *  — identitas app sendiri, bukan penyamaran aplikasi resmi. */
        const val SLOT_UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }

    // vc271 O5: antrean FIFO ganti penolakan busy (lihat ApiRequestQueue).
    private val queue = ApiRequestQueue("slot" + slot)
    private val tokLock = Any()

    private fun hprefs() = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
    private fun fp(): String = try { AccountPool.tokenFp(token) } catch (e: Exception) { "?" }
    private fun sessKey(harnessNo: Long) = "dsSlotSrv_" + slot + "_" + harnessNo
    /** vc298 BAGIAN 1 (1A): kunci sesi server TETAP reviewer — SATU sesi
     *  server per (slot reviewer, sesi harness), dipakai ulang antar
     *  review. SENGAJA kunci terpisah dari kunci giliran agen di atas:
     *  sesi reviewer tidak boleh menimpa/memakai sesi giliran agen
     *  (alasan vc262 yang sama), tapi juga tidak boleh lahir baru tiap
     *  review (itu yang menumpuk chat di Browser 1 sampai suspend).
     *  Bila server menolak sesi ini, runChat membuat SATU pengganti dan
     *  menyimpannya lagi — sama seperti jalur giliran. */
    private fun reviewerSessKey(harnessNo: Long) = ReviewerVc298.reviewerSessionKey(slot, harnessNo)

    /** vc298 BAGIAN 1 (1A): reviewer=true = panggilan reviewer
     *  konteks-nol. Bedanya dengan freshEphemeral: reviewer memakai
     *  sesi server TETAP (reviewerSessKey, dipakai ulang antar review),
     *  bukan sesi segar yang dibuang tiap panggilan. Kuota tetap dihitung
     *  sebagai reviewer (Governor.bumpReviewer) dan antrean reviewer
     *  tetap didahulukan — sama seperti freshEphemeral dulu. */
    fun chat(prompt: String, thinking: Boolean, search: Boolean,
             onPartial: (String) -> Unit, onDone: (String, String) -> Unit, onError: (String) -> Unit, freshEphemeral: Boolean = false, reviewer: Boolean = false) {
        val run: () -> Unit = {
            Thread {
                // vc271 O5: Governor dihitung di pintu masuk EKSEKUSI request,
                // bukan saat masuk antre — antrean tidak menambah hitungan.
                // vc262 N-M12 + XL-Kuota: panggilan reviewer (freshEphemeral
                // atau reviewer=true sejak vc298) bump counter reviewer
                // terpisah, bukan hitungan interaktif.
                // vc277 O16(a): bump berbobot ukuran prompt yang dikirim.
                val wVc277 = Governor.weightFor(prompt.length)
                try { if (freshEphemeral || reviewer) Governor.bumpReviewer(ctx, wVc277) else Governor.bump(ctx, wVc277) } catch (e: Exception) {}
                try {
                // vc265 N2-m6: SEMUA percobaan ulang di bawah meneruskan
                // freshEphemeral / reviewer — reviewer harus memakai
                // sesi server yang sama (tetap reviewer sejak vc298,
                // segar-ephemeral sebelum vc298), juga pada retry,
                // bukan cuma pada percobaan pertama.
                val res = try {
                    runChat(prompt, thinking, search, onPartial, freshEphemeral = freshEphemeral, reviewer = reviewer)
                } catch (e: Exception) {
                    // vc316 P1: klasifikasi via DsFailure.classifyFailure —
                    // SAMA dengan DsApiClient (hapus duplikasi string-matching).
                    // Retry-After dibaca dari suffix "[retry_after=<n>]"
                    // yang ditempel postJson/postCompletion.
                    val failure = DsFailure.classifyFailure(0, "", e)
                    val m = e.message ?: ""
                    if (DsFailure.isStreamCut(m)) {
                        var lastErr2: Exception = e
                        var out2: Pair<String, String>? = null
                        val waits2 = longArrayOf(2_000, 5_000)
                        for (w in waits2) {
                            onPartial("⏳ Stream Browser $slot terputus sebelum jawaban selesai — mengulang di sesi yang sama…")
                            Thread.sleep(w)
                            try { out2 = runChat(prompt, thinking, search, onPartial, freshEphemeral = freshEphemeral, reviewer = reviewer); break }
                            catch (e2: Exception) {
                                val m2 = e2.message ?: ""
                                if (DsFailure.isSuspendSignal(m2)) throw e2
                                if (!DsFailure.isStreamCut(m2)) throw e2
                                lastErr2 = e2
                            }
                        }
                        out2 ?: run {
                            onPartial("⏳ Stream putus 2x di sesi yang sama — coba SATU sesi server baru sebelum menyerah…")
                            try { runChat(prompt, thinking, search, onPartial, forceFresh = true, freshEphemeral = freshEphemeral, reviewer = reviewer) }
                            catch (e3: Exception) { throw e3 }
                        }
                    } else if (DsFailure.isSuspendSignal(m)) {
                        // Slot ini ditandai mati di runChat. TIDAK ADA failover
                        // ke slot lain: pilihan slot adalah pilihan user.
                        throw e
                    } else when (failure.kind) {
                        FailureKind.RATE_LIMIT -> {
                            // vc316 P1: jeda PERTAMA = header Retry-After
                            // bila ada (fallback 10/20/35 dtk).
                            var lastErr: Exception = e
                            var out: Pair<String, String>? = null
                            val waits = DsFailure.rateLimitWaits(DsFailure.parseRetryAfterSeconds(m))
                            for (w in waits) {
                                onPartial("⏳ Browser $slot kena batas laju DeepSeek — jeda ${w / 1000} detik, lanjut otomatis…")
                                Thread.sleep(w)
                                try { out = runChat(prompt, thinking, search, onPartial, freshEphemeral = freshEphemeral, reviewer = reviewer); break }
                                catch (e2: Exception) {
                                    if (DsFailure.classifyFailure(0, "", e2).kind != FailureKind.RATE_LIMIT) throw e2
                                    lastErr = e2
                                }
                            }
                            out ?: throw lastErr
                        }
                        FailureKind.UPSTREAM, FailureKind.OVERLOADED -> {
                            // vc316 P1: 5xx kini RETRYABLE (dulu langsung
                            // dilempar). Maks 2x, jeda pendek 2/5 dtk.
                            var lastErr: Exception = e
                            var out: Pair<String, String>? = null
                            for (w in DsFailure.UPSTREAM_WAITS_MS) {
                                onPartial("⏳ Server DeepSeek error (HTTP ${failure.statusCode}) — coba lagi sebentar lagi…")
                                Thread.sleep(w)
                                try { out = runChat(prompt, thinking, search, onPartial, freshEphemeral = freshEphemeral, reviewer = reviewer); break }
                                catch (e2: Exception) {
                                    val k2 = DsFailure.classifyFailure(0, "", e2).kind
                                    if (k2 != FailureKind.UPSTREAM && k2 != FailureKind.OVERLOADED) throw e2
                                    lastErr = e2
                                }
                            }
                            out ?: throw lastErr
                        }
                        FailureKind.TIMEOUT, FailureKind.UNAVAILABLE -> {
                            Thread.sleep(DsFailure.TRANSIENT_WAIT_MS)
                            runChat(prompt, thinking, search, onPartial, freshEphemeral = freshEphemeral, reviewer = reviewer)
                        }
                        else -> throw e // AUTH / CONTEXT_WINDOW: non-retryable.
                    }
                }
                queue.finish()
                onDone(res.first, res.second)
            } catch (e: Exception) {
                queue.finish()
                onError("Browser $slot gagal: ${e.message}")
            }
            }.start()
        }
        val accepted = queue.submit(freshEphemeral || reviewer, run) { onError("Browser $slot: antrean penuh — permintaan menunggu lebih dari 60 detik tanpa dieksekusi. Coba lagi.") }
        if (!accepted) onError("Browser $slot: antrean penuh (maks 3 menunggu). Coba lagi sebentar lagi.")
    }

    /** Varian sinkron untuk jalur tim (TeamRun): blokir maks 240 detik.
     *  reviewer=true (vc298): sesi server TETAP reviewer, bukan ephemeral. */
    fun chatSync(prompt: String, thinking: Boolean, search: Boolean, freshEphemeral: Boolean = false, reviewer: Boolean = false): Pair<String, String> {
        val latch = CountDownLatch(1)
        var out: Pair<String, String>? = null
        var err: String? = null
        chat(prompt, thinking, search, {}, { a, t -> out = Pair(a, t); latch.countDown() }, { e -> err = e; latch.countDown() }, freshEphemeral = freshEphemeral, reviewer = reviewer)
        latch.await(240, TimeUnit.SECONDS)
        out?.let { return it }
        throw Exception(err ?: "Browser $slot: tidak ada jawaban (timeout 240 detik).")
    }

    private fun runChat(prompt: String, thinking: Boolean, search: Boolean, onPartial: (String) -> Unit, forceFresh: Boolean = false, freshEphemeral: Boolean = false, reviewer: Boolean = false): Pair<String, String> {
        val ua = SLOT_UA
        val f = fp()
        // PEMUTUS ARUS (sama seperti jalur utama): akun slot yang sudah
        // ditandai suspend TIDAK dihubungi lagi sama sekali.
        if (AccountPool.isDead(ctx, f)) throw Exception("AKUN BROWSER $slot DITANDAI TIDAK AKTIF oleh pemutus arus lokal (dari respons server sebelumnya) — tidak ada panggilan yang dikirim untuk akun ini. Bila akun sebenarnya sehat (token mungkin kedaluwarsa): buka Browser $slot di Setup, login ulang, lalu kirim ulang.")
        val harnessNo = try { hprefs().getLong("currentSession", 1L) } catch (e: Exception) { 1L }
        val stored = if (forceFresh || freshEphemeral) null else storedServerSession(f, harnessNo, reviewer)
        try {
            val sid = stored ?: run {
                val c = createServerSession(token, cookie, ua)
                // vc262 N-M12: sesi ephemeral reviewer (freshEphemeral)
                // TIDAK disimpan — sesi tersimpan milik giliran agen
                // tidak boleh tertimpa oleh sesi reviewer.
                // vc298: reviewer=true menyimpan SESINYA SENDIRI di kunci
                // reviewer (reviewerSessKey) — dipakai ulang antar review,
                // dan TIDAK PERNAH menyentuh kunci giliran agen.
                if (!freshEphemeral) storeServerSession(f, harnessNo, c, reviewer)
                val hadHistory = try { MemoryDb.get(ctx).historyForSession(harnessNo, 1).isNotEmpty() } catch (e: Exception) { false }
                if (forceFresh) {
                    logSessEvent("[Browser $slot] Wadah PENGGANTI lahir untuk sesi harness #$harnessNo (stream putus 2x di sesi lama — coba sesi baru).")
                } else {
                    // vc298: kelahiran wadah REVIEWER dicatat sebagai
                    // reviewer (bukan wadah giliran) dan TIDAK menulis
                    // catatan ke riwayat chat — reviewer tidak boleh
                    // mencemari riwayat dengan urusan sesinya sendiri.
                    logSessEvent("[Browser $slot] " + (if (reviewer) "Wadah REVIEWER BARU lahir untuk sesi harness #$harnessNo (dipakai ulang antar review)" else "Wadah BARU lahir untuk sesi harness #$harnessNo") + (if (hadHistory && !reviewer) " (sesi lama, wadah pertamanya di rezim slot)" else if (!reviewer) " (sesi baru)" else ""))
                    if (hadHistory && !reviewer) {
                        val note = "♻️ [Browser $slot] Sesi harness ini belum punya wadah server — membuat satu sekarang. Selanjutnya wadah ini dipakai ulang terus untuk sesi ini."
                        try { onPartial(note) } catch (e: Exception) {}
                        try { MemoryDb.get(ctx).addHistory("asisten", note, harnessNo) } catch (e: Exception) {}
                    }
                }
                c
            }
            val res = completeWithSession(sid, prompt, thinking, search, token, cookie, ua, onPartial)
            try { AccountPool.clearDead(ctx, f) } catch (e: Exception) {}
            return res
        } catch (e: Exception) {
            if (isSuspendSignal(e.message)) {
                try { AccountPool.markDeadSmart(ctx, f, e.message) } catch (e2: Exception) {}
                if (AccountPool.isHardSuspend(e.message)) {
                    try { hprefs().edit().putBoolean("bgSessionDead", true).apply() } catch (e2: Exception) {}
                }
                logSessEvent("[Browser $slot] Akun ditandai " + (if (AccountPool.isHardSuspend(e.message)) "suspend" else "token basi (tanda lunak 45 mnt)") + " — wadah sesi harness #$harnessNo dipertahankan.")
                throw e
            }
            if (stored != null && isSessionInvalidSignal(e.message)) {
                val noteR = "♻️ [Browser $slot] Sesi server tersimpan ditolak server (kemungkinan terhapus) — membuat SATU sesi pengganti untuk sesi harness ini. (Alasan: " + (e.message ?: "").take(120) + ")"
                logSessEvent("[Browser $slot] Wadah PENGGANTI lahir untuk sesi harness #$harnessNo — " + (e.message ?: "").take(80) + (if (reviewer) " (wadah reviewer)" else ""))
                if (!reviewer) {
                    try { onPartial(noteR) } catch (e3: Exception) {}
                    try { MemoryDb.get(ctx).addHistory("asisten", noteR, harnessNo) } catch (e3: Exception) {}
                }
                clearServerSession(f, harnessNo, reviewer)
                val fresh = createServerSession(token, cookie, ua)
                storeServerSession(f, harnessNo, fresh, reviewer)
                try {
                    return completeWithSession(fresh, prompt, thinking, search, token, cookie, ua, onPartial)
                } catch (e2: Exception) {
                    if (isSuspendSignal(e2.message)) {
                        try { AccountPool.markDeadSmart(ctx, f, e2.message) } catch (e3: Exception) {}
                        if (AccountPool.isHardSuspend(e2.message)) {
                            try { hprefs().edit().putBoolean("bgSessionDead", true).apply() } catch (e3: Exception) {}
                        }
                    }
                    throw e2
                }
            }
            throw e
        }
    }

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

    /** vc316 P1: daftar sinyal hidup di DsFailure (satu tempat) —
     *  hapus duplikasi dengan DsApiClient. */
    private fun isSuspendSignal(msg: String?): Boolean = DsFailure.isSuspendSignal(msg)

    private fun isSessionInvalidSignal(msg: String?): Boolean = DsFailure.isSessionInvalidSignal(msg)

    /** Pemetaan EKSKET per slot — TIDAK ADA adopsi lintas slot.
     *  vc298: reviewer=true memakai kunci reviewer sendiri
     *  (reviewerSessKey) — giliran agen dan reviewer di slot yang sama
     *  tidak pernah saling membaca/menimpa sesi server. */
    private fun storedServerSession(f: String, harnessNo: Long, reviewer: Boolean = false): String? {
        return try {
            val id = hprefs().getString(if (reviewer) reviewerSessKey(harnessNo) else sessKey(harnessNo), "") ?: ""
            if (id.isNotBlank()) id else null
        } catch (e: Exception) { null }
    }

    private fun storeServerSession(f: String, harnessNo: Long, id: String, reviewer: Boolean = false) {
        try { hprefs().edit().putString(if (reviewer) reviewerSessKey(harnessNo) else sessKey(harnessNo), id).apply() } catch (e: Exception) {}
    }

    private fun clearServerSession(f: String, harnessNo: Long, reviewer: Boolean = false) {
        try { hprefs().edit().remove(if (reviewer) reviewerSessKey(harnessNo) else sessKey(harnessNo)).apply() } catch (e: Exception) {}
    }

    private fun createServerSession(token: String, cookie: String, ua: String): String {
        val sessionResp = postJson("/api/v0/chat_session/create", JSONObject(), token, cookie, ua, null)
        return findSessionId(sessionResp) ?: throw Exception("create session: id sesi tidak ditemukan di respon: ${sessionResp.toString().take(200)}")
    }

    private fun solvePowHeader(targetPath: String, token: String, cookie: String, ua: String, onNote: ((String) -> Unit)?): String {
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

    private fun completeWithSession(sessionId: String, prompt: String, thinking: Boolean, search: Boolean, token: String, cookie: String, ua: String, onPartial: (String) -> Unit): Pair<String, String> {
        val powHeader = solvePowHeader("/api/v0/chat/completion", token, cookie, ua, onPartial)
        val body = JSONObject()
            .put("chat_session_id", sessionId)
            .put("parent_message_id", JSONObject.NULL)
            .put("prompt", prompt)
            .put("ref_file_ids", JSONArray())
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
            // vc316 P1: baca Retry-After; suffix "[retry_after=<n>]" dibaca
            // DsFailure.classifyFailure di chat() sebagai jeda pertama
            // tangga rate-limit.
            val retryAfterSec = DsFailure.parseRetryAfterHeader(conn.getHeaderField("Retry-After"))
            throw Exception("HTTP $code di $path: ${text.take(250)}" + DsFailure.retryAfterSuffix(retryAfterSec))
        }
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
        // vc316 P1: tangkap Retry-After SEKALI di sini (lihat DsApiClient).
        val retryAfterSec = DsFailure.parseRetryAfterHeader(conn.getHeaderField("Retry-After"))
        if (code !in 200..299) {
            val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(250) ?: "" } catch (e: Exception) { "" }
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
                throw Exception("SERVER ${obj.optInt("code")}: ${obj.optString("msg")}")
            }
            applyChunk(obj, content, thinkingBuf, state)
            val now = System.currentTimeMillis()
            if (content.isNotEmpty() && now - lastPartialAt > 250) {
                lastPartialAt = now
                onPartial(content.toString())
            }
        }
        val full = content.toString()
        if (full.isBlank()) {
            val rawStr = rawSample.toString()
            if (rawStr.contains("rate_limit") || rawStr.contains("too frequent", ignoreCase = true)) {
                throw Exception("rate_limit_reached — server DeepSeek membatasi laju permintaan (Messages too frequent); stream berhenti tanpa jawaban final." + DsFailure.retryAfterSuffix(retryAfterSec))
            }
            if (!state.sawFinish) {
                throw Exception("stream_terputus: koneksi ke server putus sebelum jawaban selesai (baris data: $dataLines, fragmen berpikir ${thinkingBuf.length} karakter). Cuplikan mentah: ${rawStr.take(400)}")
            }
            throw Exception("jawaban_kosong: stream selesai resmi tapi tanpa fragmen jawaban (fragmen berpikir ${thinkingBuf.length} karakter dibuang, baris data: $dataLines). Cuplikan mentah: ${rawStr.take(400)}")
        }
        return Pair(DsSafetyStrip.strip(full), DsSafetyStrip.strip(thinkingBuf.toString().trim()))
    }

    private class StreamState {
        var lastPath: String = ""
        var sawFinish = false
        val fragmentThinking = HashMap<Int, Boolean>()
        var fragmentCount = 0
        var currentThinking = false
    }

    private val fragIdxRe = Regex("fragments/(-?\\d+)")

    private fun declareFragment(state: StreamState, idx: Int, isThink: Boolean, makeCurrent: Boolean = true) {
        state.fragmentThinking[idx] = isThink
        if (idx >= state.fragmentCount) state.fragmentCount = idx + 1
        if (makeCurrent) state.currentThinking = isThink
    }

    private fun routeIsThinking(state: StreamState, path: String): Boolean {
        if (path.contains("thinking", ignoreCase = true) || path.contains("reasoning", ignoreCase = true)) return true
        val m = fragIdxRe.find(path) ?: return state.currentThinking
        val raw = m.groupValues[1].toIntOrNull() ?: return state.currentThinking
        if (raw == -1) return state.currentThinking
        val known = state.fragmentThinking[raw]
        if (known != null) return known
        if (raw == state.fragmentCount && state.fragmentCount > 0) {
            declareFragment(state, raw, false)
            return false
        }
        return state.currentThinking
    }

    private fun applyChunk(obj: JSONObject, content: StringBuilder, thinking: StringBuilder, state: StreamState) {
        val p = obj.optString("p", "")
        if (p == "response/status" && obj.optString("v") == "FINISHED") state.sawFinish = true
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
                if (p.contains("/status") || effPath.contains("/status")) {
                    if (v == "FINISHED") {
                        val sIdx = fragIdxRe.find(effPath)?.groupValues?.get(1)?.toIntOrNull()
                        if (sIdx == null || sIdx < 0 || state.fragmentThinking[sIdx] == true) state.currentThinking = false
                    }
                    return
                }
                if ((v == "FINISHED" || v == "WIP") && !p.endsWith("content")) return
                val contentish = effPath.isEmpty() || effPath.endsWith("content") || effPath.endsWith("fragments") ||
                    effPath.contains("thinking") || effPath.contains("reasoning")
                if (!contentish) return
                if (routeIsThinking(state, effPath)) thinking.append(v) else content.append(v)
            }
            is JSONArray -> {
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

    private fun applyValueObject(obj: JSONObject, v: JSONObject, effPath: String, content: StringBuilder, thinking: StringBuilder, state: StreamState) {
        val typeStr = v.optString("type", "")
        if (typeStr.isNotEmpty()) {
            val isThink = typeStr.contains("THINK", ignoreCase = true)
            val raw = fragIdxRe.find(effPath)?.groupValues?.get(1)?.toIntOrNull()
            val idx = if (raw != null && raw >= 0) raw else state.fragmentCount
            declareFragment(state, idx, isThink)
            val c = v.optString("content", "")
            if (c.isNotEmpty()) { if (isThink) thinking.append(c) else content.append(c) }
            return
        }
        val directContent = v.optString("content", "")
        if (directContent.isNotEmpty()) {
            if (routeIsThinking(state, effPath)) thinking.append(directContent) else content.append(directContent)
            return
        }
        val resp = v.optJSONObject("response") ?: obj.optJSONObject("response") ?: v
        val frags = resp.optJSONArray("fragments") ?: return
        val firstDecl = state.fragmentCount == 0
        for (i in 0 until frags.length()) {
            val f = frags.optJSONObject(i) ?: continue
            val isThink = f.optString("type", "").contains("THINK", ignoreCase = true)
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

    private fun estTokens(chars: Int, perChar: Double): Long =
        try { (chars / perChar).toLong() } catch (e: Exception) { 0L }

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
}
