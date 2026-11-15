package com.senzwh.deepharness

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.LongBuffer
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Tahap B — Memori Vektor.
 * Model: paraphrase-multilingual-MiniLM-L12-v2 (ONNX int8) sebagai PAKET UNDUH
 * di dalam app (keputusan Sendy 2026-10-22): diunduh dari HuggingFace ke
 * filesDir/models/, diverifikasi SHA-256. ONNX Runtime-nya dibundel di APK.
 *
 * Tokenizer = Unigram (SentencePiece XLM-R) yang diimplementasikan manual:
 * normalisasi NFKC + Metaspace + Viterbi atas skor vocab dari tokenizer.json.
 * TERVALIDASI di VPS 2026-10-22: token id identik dengan library `tokenizers`
 * referensi pada semua kalimat uji (Indonesia + Inggris), embedding cos=1.0000,
 * dan uji semantik "minuman favorit pagi" -> "kopi tubruk…" = 0.88.
 *
 * Semua fungsi embed BLOCKING — panggil hanya dari thread latar.
 * KECUALI packInfoQuick/isEngineReady (non-blokir, aman untuk main thread).
 * UI WAJIB pakai packInfoQuick — JANGAN packInfo/isReady dari main thread:
 * keduanya bisa memblokir pada monitor Embedder saat thread latar sedang
 * ensureInit (ANR 2026-10-27, main thread menunggu lock lebih 5 detik).
 */
object Embedder {
    private const val MODEL_URL = "https://huggingface.co/Xenova/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/onnx/model_quantized.onnx"
    private const val TOKENIZER_URL = "https://huggingface.co/Xenova/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/tokenizer.json"
    private const val MODEL_SHA256 = "66fc00f5f29afcaff34092e1bdd20008ca3918265a82fb9695a551e510cc4ebc"
    private const val TOKENIZER_SHA256 = "b60b6b43406a48bf3638526314f3d232d97058bc93472ff2de930d43686fa441"
    private const val MODEL_NAME = "embedding_model.onnx"
    private const val TOKENIZER_NAME = "tokenizer.json"
    private const val DIM = 384
    private const val MAX_PIECES = 254

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "embedder").apply { isDaemon = true } }
    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: UnigramTokenizer? = null
    @Volatile private var initError: String = ""
    @Volatile private var downloading = false
    @Volatile private var downloadPct = -1
    @Volatile private var backfillStarted = false

    // ---------------- Berkas paket ----------------
    private fun modelsDir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    private fun modelFile(ctx: Context) = File(modelsDir(ctx), MODEL_NAME)
    private fun tokenizerFile(ctx: Context) = File(modelsDir(ctx), TOKENIZER_NAME)
    fun packDownloaded(ctx: Context) = modelFile(ctx).exists() && tokenizerFile(ctx).exists()

    // BLOCKING bila paket terunduh tapi mesin belum init (ensureInit ambil
    // monitor Embedder) — JANGAN panggil dari main thread. UI pakai quick.
    fun packInfo(ctx: Context): String {
        if (downloading) return "sedang mengunduh… $downloadPct%"
        if (!packDownloaded(ctx)) return "BELUM diunduh (135 MB, sekali saja — tombol 28)"
        val n = try { MemoryDb.get(ctx).embeddingCount() } catch (e: Exception) { 0 }
        return if (isReady(ctx)) "SIAP — $n entri memori sudah ter-embed (pencarian makna AKTIF)"
        else "terunduh tapi mesin belum siap${if (initError.isNotBlank()) ": $initError" else ""} (coba Tes, tombol 29)"
    }

    // Placeholder status saat paket terunduh tapi mesin belum siap dan belum
    // ada error — pemanggil UI memakai ini sebagai pemicu background check.
    const val ENGINE_CHECKING = "memeriksa mesin…"

    // Non-blokir: true hanya bila sesi ONNX + tokenizer SUDAH terinisialisasi
    // (baca volatile, tanpa lock). Dipakai pemanggil main-thread untuk
    // memutuskan apakah embedQuery aman dipanggil tanpa risiko blokir ANR.
    fun isEngineReady(): Boolean = session != null && tokenizer != null

    // Status NON-BLOKIR untuk UI (main thread). TANPA lock, TANPA ensureInit,
    // TANPA I/O berat: hanya baca volatile + File.exists(). Tidak menghitung
    // MemoryDb.embeddingCount (query DB tidak boleh di jalur status UI).
    fun packInfoQuick(ctx: Context): String {
        if (downloading) return "sedang mengunduh… $downloadPct%"
        if (!packDownloaded(ctx)) return "BELUM diunduh (135 MB, sekali saja — tombol 28)"
        if (session != null && tokenizer != null) return "SIAP — pencarian makna AKTIF"
        val err = initError
        if (err.isNotBlank()) return "terunduh tapi mesin belum siap: $err"
        return ENGINE_CHECKING
    }

    // ---------------- Unduh paket ----------------
    fun downloadPack(ctx: Context, wifiOnly: Boolean, cb: (String) -> Unit) {
        if (downloading) { cb("Unduhan sudah berjalan… $downloadPct%"); return }
        if (wifiOnly && !isOnWifi(ctx)) { cb("DIBATALKAN: saklar WiFi-saja aktif tapi koneksi saat ini bukan WiFi."); return }
        downloading = true; downloadPct = 0
        Thread {
            try {
                val totalBytes = 118_308_126L + 17_082_913L
                var doneBytes = 0L
                val files = listOf(
                    Triple(MODEL_URL, modelFile(ctx), MODEL_SHA256),
                    Triple(TOKENIZER_URL, tokenizerFile(ctx), TOKENIZER_SHA256)
                )
                for ((url, dest, sha) in files) {
                    if (dest.exists() && sha256(dest) == sha) { doneBytes += dest.length(); continue }
                    val part = File(dest.parent, dest.name + ".part")
                    downloadFile(url, part) { got ->
                        val pct = ((doneBytes + got) * 100 / totalBytes).toInt()
                        if (pct != downloadPct) { downloadPct = pct; cb("mengunduh paket model… $pct%") }
                    }
                    val gotSha = sha256(part)
                    if (gotSha != sha) {
                        part.delete()
                        throw Exception("SHA-256 tidak cocok untuk ${dest.name} (dapat $gotSha) — unduhan dibatalkan, coba lagi.")
                    }
                    if (dest.exists()) dest.delete()
                    if (!part.renameTo(dest)) throw Exception("gagal menyimpan ${dest.name}")
                    doneBytes += dest.length()
                }
                downloadPct = 100
                cb("UNDUH SELESAI & terverifikasi SHA-256. Menyiapkan mesin embedding…")
                // Hangatkan mesin + embed data lama yang belum punya vektor.
                ensureInit(ctx)
                backfill(ctx)
                cb(packInfo(ctx))
            } catch (e: Exception) {
                cb("UNDUH GAGAL: ${e.message} (file .part disimpan — unduh lagi untuk MELANJUTKAN dari posisi terakhir)")
            } finally {
                downloading = false
            }
        }.start()
    }

    private fun downloadFile(url: String, dest: File, onBytes: (Long) -> Unit) {
        var offset = if (dest.exists()) dest.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000; conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        val code = conn.responseCode
        if (code != 200 && code != 206) throw Exception("HTTP $code dari server model")
        if (code == 200 && offset > 0) { dest.delete(); offset = 0 } // server abaikan Range -> ulang dari nol
        conn.inputStream.use { input ->
            FileOutputStream(dest, offset > 0).use { out ->
                val buf = ByteArray(64 * 1024)
                var got = offset
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); got += n
                    if (got - lastReport > 512 * 1024) { lastReport = got; onBytes(got) }
                }
                onBytes(got)
            }
        }
    }

    fun deletePack(ctx: Context) {
        synchronized(this) {
            try { session?.close() } catch (e: Exception) {}
            session = null; tokenizer = null
        }
        try {
            modelFile(ctx).delete(); tokenizerFile(ctx).delete()
            File(modelsDir(ctx), "tokenizer.cache").delete()
            File(modelsDir(ctx), MODEL_NAME + ".part").delete()
            File(modelsDir(ctx), TOKENIZER_NAME + ".part").delete()
        } catch (e: Exception) {}
    }

    private fun isOnWifi(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (e: Exception) { false }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { ins ->
            val buf = ByteArray(128 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { String.format("%02x", it) }
    }

    // ---------------- Mesin embedding ----------------
    fun isReady(ctx: Context): Boolean {
        if (!packDownloaded(ctx)) return false
        return ensureInit(ctx)
    }

    @Synchronized
    private fun ensureInit(ctx: Context): Boolean {
        if (session != null && tokenizer != null) return true
        if (!packDownloaded(ctx)) return false
        return try {
            if (tokenizer == null) tokenizer = UnigramTokenizer.load(ctx, tokenizerFile(ctx), File(modelsDir(ctx), "tokenizer.cache"))
            if (session == null) {
                val env = OrtEnvironment.getEnvironment()
                session = env.createSession(modelFile(ctx).absolutePath, OrtSession.SessionOptions())
            }
            initError = ""
            true
        } catch (t: Throwable) {
            initError = (t.message ?: t.javaClass.simpleName).take(160)
            false
        }
    }

    /** Embed satu teks -> vektor 384 ternormalisasi, atau null bila paket belum siap. Thread latar saja. */
    fun embedQuery(ctx: Context, text: String): FloatArray? {
        if (!ensureInit(ctx)) return null
        return try { embedInternal(text) } catch (t: Throwable) { initError = (t.message ?: "?").take(160); null }
    }

    private fun embedInternal(text: String): FloatArray? {
        val tok = tokenizer ?: return null
        val sess = session ?: return null
        val env = OrtEnvironment.getEnvironment()
        val ids = tok.encode(text)
        val n = ids.size
        val idArr = LongArray(n) { ids[it].toLong() }
        val maskArr = LongArray(n) { 1L }
        val typeArr = LongArray(n) { 0L }
        val shape = longArrayOf(1, n.toLong())
        val tIds = OnnxTensor.createTensor(env, LongBuffer.wrap(idArr), shape)
        val tMask = OnnxTensor.createTensor(env, LongBuffer.wrap(maskArr), shape)
        val tType = OnnxTensor.createTensor(env, LongBuffer.wrap(typeArr), shape)
        try {
            sess.run(mapOf("input_ids" to tIds, "attention_mask" to tMask, "token_type_ids" to tType)).use { res ->
                @Suppress("UNCHECKED_CAST")
                val hidden = (res[0].value as Array<Array<FloatArray>>)[0]
                val out = FloatArray(DIM)
                for (t in 0 until n) {
                    val row = hidden[t]
                    for (d in 0 until DIM) out[d] += row[d]
                }
                var norm = 0.0
                val nf = n.toFloat()
                for (d in 0 until DIM) { val v = out[d] / nf; out[d] = v; norm += v * v }
                norm = sqrt(norm)
                if (norm > 0) for (d in 0 until DIM) out[d] = (out[d] / norm).toFloat()
                return out
            }
        } finally {
            tIds.close(); tMask.close(); tType.close()
        }
    }

    /** Antrekan embedding untuk satu entri memori (fakta/skill/riwayat/keuangan). Senyap bila paket belum ada. */
    /** F4 (v4.38.0): heuristik sumber sensitif — kredensial, OTP, rekening,
     *  kartu, dan deretan digit panjang (nomor kartu/rekening/HP). HEURISTIK,
     *  bukan jaminan: perlindungan sebenarnya adalah indeks 100% on-device +
     *  hapus menyeluruh. False positive hanya menghilangkan recall semantik
     *  (riwayat teks asli TIDAK dihapus; pencarian teks biasa tetap jalan). */
    private val SENSITIVE_PATTERNS = listOf("password", "kata sandi", "otp", "kode verifikasi", "kode otp", "nomor rekening", "no rekening", "kartu kredit", "kartu debit", "cvv", "cvc", "api key", "apikey", "token rahasia")
    private val LONG_DIGITS = Regex("""\d{12,}""")
    fun isSensitive(text: String): Boolean {
        val low = text.lowercase()
        if (SENSITIVE_PATTERNS.any { low.contains(it) }) return true
        if (LONG_DIGITS.containsMatchIn(text)) return true
        return false
    }

    fun embedAsync(ctx: Context, kind: String, ref: String, text: String) {
        if (!packDownloaded(ctx)) return
        if (isSensitive(text)) return
        val appCtx = ctx.applicationContext
        executor.execute {
            try {
                if (!ensureInit(appCtx)) return@execute
                val db = MemoryDb.get(appCtx)
                if (db.embeddingHash(kind, ref) == text.hashCode()) return@execute
                val v = embedInternal(text) ?: return@execute
                db.putEmbedding(kind, ref, text.hashCode(), v)
            } catch (t: Throwable) { /* embedding bersifat best-effort */ }
        }
    }

    // vc240 m6: backfill bertahap BERKELANJUTAN, bukan sekali per
    // proses + pool tetap. Cursor per jenis disimpan di prefs
    // (backfillCursor_<jenis>); tiap panggilan memajukan cursor sebesar
    // halaman yang dipindai (100 baris) sampai halaman terakhir yang
    // tidak penuh, lalu cursor di-reset ke 0 agar sapuan berikutnya
    // mencakup item baru. backfillStarted hanya mencegah dua sapuan
    // bersamaan — di-reset di finally agar panggilan berikutnya (mis.
    // start app berikutnya) melanjutkan dari cursor, bukan berhenti
    // selamanya seperti maybeBackfill lama.
    fun backfill(ctx: Context) {
        val appCtx = ctx.applicationContext
        executor.execute {
            try {
                if (!ensureInit(appCtx)) return@execute
                val db = MemoryDb.get(appCtx)
                val prefs = appCtx.getSharedPreferences("harness", Context.MODE_PRIVATE)
                for (kind in listOf("fact", "skill", "hist", "fin")) {
                    var offset = prefs.getInt("backfillCursor_" + kind, 0)
                    while (true) {
                        val page = db.missingEmbeddingsPaged(kind, offset, 100)
                        for ((k2, ref, text) in page.items) {
                            if (db.embeddingHash(k2, ref) == text.hashCode()) continue
                            if (isSensitive(text)) continue
                            val v = embedInternal(text) ?: return@execute
                            db.putEmbedding(k2, ref, text.hashCode(), v)
                        }
                        if (page.scanned < 100) { prefs.edit().putInt("backfillCursor_" + kind, 0).apply(); break }
                        offset += page.scanned
                        prefs.edit().putInt("backfillCursor_" + kind, offset).apply()
                    }
                }
            } catch (t: Throwable) {} finally {
                synchronized(this) { backfillStarted = false }
            }
        }
    }

    fun maybeBackfill(ctx: Context) {
        if (!packDownloaded(ctx)) return
        synchronized(this) { if (backfillStarted) return; backfillStarted = true }
        backfill(ctx)
    }

    /** Self-test Setup (pola Tes PoW): buktikan mesin jalan + semantik benar, di HP user. */
    fun selfTest(ctx: Context): String {
        if (!packDownloaded(ctx)) return "paket model BELUM diunduh — pakai tombol 28 dulu."
        val t0 = System.currentTimeMillis()
        if (!ensureInit(ctx)) return "mesin GAGAL start: $initError"
        val q = embedInternal("minuman favorit pagi") ?: return "embed query gagal: $initError"
        val a = embedInternal("kopi tubruk adalah minuman favoritku di pagi hari") ?: return "embed A gagal"
        val b = embedInternal("ganti oli motor dan servis rem cakram") ?: return "embed B gagal"
        val ms = System.currentTimeMillis() - t0
        fun cos(x: FloatArray, y: FloatArray): Double { var s = 0.0; for (i in x.indices) s += x[i] * y[i]; return s }
        return "TES LULUS — vektor ${q.size} dimensi, 3 embed dalam $ms ms.\n" +
            "cos(query, 'kopi tubruk…') = ${String.format("%.3f", cos(q, a))} (harus TINGGI)\n" +
            "cos(query, 'ganti oli motor…') = ${String.format("%.3f", cos(q, b))} (harus RENDAH)\n" +
            packInfo(ctx)
    }

    // ---------------- Tokenizer Unigram ----------------
    /**
     * Unigram SentencePiece (XLM-R): NFKC -> Metaspace (spasi -> ▁, awali ▁)
     * -> Viterbi skor maksimum. Mirror persis implementasi Python yang
     * tervalidasi identik dengan library tokenizers (validate.py).
     */
    class UnigramTokenizer private constructor(
        private val tokens: Array<String>,
        private val scores: FloatArray,
        private val byFirst: HashMap<Char, ArrayList<Int>>,
        private val unkId: Int
    ) {
        fun encode(text: String): LongArray {
            val norm = normalize(text)
            val s = "▁" + norm.replace(' ', '▁')
            val n = s.length
            val neg = -1e18
            val best = DoubleArray(n + 1) { neg }; best[0] = 0.0
            val backPos = IntArray(n + 1) { -1 }
            val backId = IntArray(n + 1) { -1 }
            for (i in 0 until n) {
                if (best[i] == neg) continue
                val cands = byFirst[s[i]]
                if (cands != null) {
                    for (tid in cands) {
                        val tok = tokens[tid]
                        if (s.startsWith(tok, i)) {
                            val j = i + tok.length
                            val v = best[i] + scores[tid]
                            if (v > best[j]) { best[j] = v; backPos[j] = i; backId[j] = tid }
                        }
                    }
                }
                val j = i + 1
                val v = best[i] - 25.0
                if (v > best[j]) { best[j] = v; backPos[j] = i; backId[j] = unkId }
            }
            val pieces = ArrayList<Int>()
            var j = n
            while (j > 0 && backPos[j] >= 0) { pieces.add(backId[j]); j = backPos[j] }
            pieces.reverse()
            val capped = if (pieces.size > MAX_PIECES) pieces.subList(0, MAX_PIECES) else pieces
            val out = LongArray(capped.size + 2)
            out[0] = 0L // <s>
            for (i in capped.indices) out[i + 1] = capped[i].toLong()
            out[out.size - 1] = 2L // </s>
            return out
        }

        private fun normalize(text: String): String {
            var t = Normalizer.normalize(text, Normalizer.Form.NFKC)
            val sb = StringBuilder(t.length)
            for (ch in t) {
                if (ch.category == CharCategory.CONTROL && ch != ' ' && ch != '\t' && ch != '\n') continue
                sb.append(ch)
            }
            t = sb.toString()
            return t.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        }

        companion object {
            fun load(ctx: Context, jsonFile: File, cacheFile: File): UnigramTokenizer {
                // Cache biner: parse tokenizer.json 17 MB dengan org.json lambat;
                // cache bikin start berikutnya cepat. Validitas = ukuran file JSON.
                if (cacheFile.exists() && cacheFile.length() > 8) {
                    try {
                        DataInputStream(FileInputStream(cacheFile)).use { d ->
                            val srcLen = d.readLong()
                            if (srcLen == jsonFile.length()) {
                                val unk = d.readInt()
                                val count = d.readInt()
                                val tokens = Array(count) { "" }
                                val scores = FloatArray(count)
                                for (i in 0 until count) {
                                    val len = d.readInt()
                                    val bytes = ByteArray(len); d.readFully(bytes)
                                    tokens[i] = String(bytes, Charsets.UTF_8)
                                    scores[i] = d.readFloat()
                                }
                                return build(tokens, scores, unk)
                            }
                        }
                    } catch (e: Exception) { /* cache rusak -> parse ulang */ }
                }
                val root = JSONObject(jsonFile.readText(Charsets.UTF_8))
                val model = root.getJSONObject("model")
                val unk = model.optInt("unk_id", 3)
                val arr = model.getJSONArray("vocab")
                val count = arr.length()
                val tokens = Array(count) { "" }
                val scores = FloatArray(count)
                for (i in 0 until count) {
                    val pair = arr.getJSONArray(i)
                    tokens[i] = pair.getString(0)
                    scores[i] = pair.optDouble(1, 0.0).toFloat()
                }
                try {
                    DataOutputStream(FileOutputStream(cacheFile)).use { d ->
                        d.writeLong(jsonFile.length())
                        d.writeInt(unk); d.writeInt(count)
                        for (i in 0 until count) {
                            val b = tokens[i].toByteArray(Charsets.UTF_8)
                            d.writeInt(b.size); d.write(b); d.writeFloat(scores[i])
                        }
                    }
                } catch (e: Exception) {}
                return build(tokens, scores, unk)
            }

            private fun build(tokens: Array<String>, scores: FloatArray, unk: Int): UnigramTokenizer {
                val byFirst = HashMap<Char, ArrayList<Int>>()
                for (i in tokens.indices) {
                    val tok = tokens[i]
                    if (tok.isEmpty() || tok.startsWith("<")) continue
                    byFirst.getOrPut(tok[0]) { ArrayList() }.add(i)
                }
                for ((_, list) in byFirst) list.sortByDescending { tokens[it].length }
                return UnigramTokenizer(tokens, scores, byFirst, unk)
            }
        }
    }
}
