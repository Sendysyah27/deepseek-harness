package com.senzwh.deepharness

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** vc296 — Rote PoC: kolektor notifikasi sebagai pipeline deterministik.
 *  Fungsi MURNI (tanpa Android, tanpa DB, tanpa jaringan) — bisa unit-test
 *  standalone seperti SkillJudge vc293 / MemoryLoopVc294 / BriefingVc295.
 *  Jaringan + file + EventDb dilakukan pemanggil (Tools.kt);
 *  penjadwalan + room dilakukan Automation.kt menumpang routines.
 *
 *  Sumber pola (SKILL.md dibaca langsung, bukan impor mentah):
 *  - rote: compile skill terbukti -> pipeline deterministik tanpa LLM
 *    di loop -> sajikan sebagai tool. Node-kind rote di sini: 5/5
 *    pure_function, 0 external_call (data lokal EventDb), 0 llm_judge,
 *    0 agent_loop, 0 hitl_gate (digest read-only).
 *  - workflow-automation: durable execution — resume TEPAT di titik
 *    gagal; checkpointed steps; idempotency keys. Dedup key
 *    "pkg|ts|hash(teks)" = idempotency key per notifikasi.
 *
 *  Rutinitas terpilih = KOLEKTOR NOTIFIKASI (bukan cek review Adobe —
 *  yang itu butuh login browser + penilaian LLM atas pola penolakan,
 *  lihat BUILD-VC296.md §1: tidak bisa dideterministikkan, ditolak
 *  dengan bukti, bukan dipaksakan).
 */
object RoteVc296 {
    const val ROOM_TITLE = "Ringkasan Notifikasi"
    const val ROUTINE_ID = "rote_notif_daily"
    const val ROUTINE_TIME = "21:30"
    const val DIR_NAME = "rote/notif-collect"
    const val SEEN_CAP = 1000
    const val FETCH_CAP = 500
    const val MAX_ITEMS = 30
    const val TOTAL_STEPS = 5
    val STEP_NAMES: List<String> = listOf("LOAD_STATE", "FETCH_NEW", "CLASSIFY_DEDUP", "RENDER", "COMMIT")

    data class NotifItem(val pkg: String, val ts: Long, val text: String)
    data class ClassifiedItem(val item: NotifItem, val category: String, val amount: Long?, val finKind: String?)
    data class RoteState(val runId: String, val stepsDone: LinkedHashSet<Int>, val lastTs: Long, val seen: LinkedHashSet<String>, val lastOutput: String, val lastRunAt: Long)

    fun emptyState(runId: String): RoteState = RoteState(runId, LinkedHashSet(), 0L, LinkedHashSet(), "", 0L)

    /** Langkah berikutnya yang belum selesai (1..5); 0 = semua selesai. */
    fun nextStep(stepsDone: Set<Int>): Int {
        for (s in 1..TOTAL_STEPS) if (s !in stepsDone) return s
        return 0
    }

    fun isComplete(stepsDone: Set<Int>): Boolean = (1..TOTAL_STEPS).all { it in stepsDone }

    /** vc299 m13 (N6-m13): checkpoint commit dengan fetch terlama-dulu —
     *  SELALU ke ts terbaru yang terproses (maxTs); kosong -> now.
     *  Cabang lama `fetched.size >= FETCH_CAP -> minTs` DIHAPUS: dengan
     *  fetch terbaru-dulu, minTs membuat notifikasi terlama (ts di
     *  antara lastTs lama dan minTs) hilang permanen. Dengan
     *  terlama-dulu, maxTs aman: run berikut mengambil 500 berikutnya. */
    fun commitTs(items: List<ClassifiedItem>, now: Long): Long {
        val maxTs = items.maxOfOrNull { it.item.ts } ?: 0L
        return if (maxTs > 0) maxTs else now
    }

    /** Prompt routine 21:30 — jawaban akhir agen = digest rote_run
     *  apa adanya, yang dikirim sebagai laporan ke room Ringkasan
     *  Notifikasi (routing appendJobReportEx via sessionId+roomId). */
    fun rotePrompt(): String = """RINGKASAN NOTIFIKASI — ROTE vc296 (pipeline deterministik, jadwal 21:30 WIB, room "Ringkasan Notifikasi"):
(1) Panggil rote_run {} SATU KALI — pipeline 5 langkah (LOAD_STATE -> FETCH_NEW -> CLASSIFY_DEDUP -> RENDER -> COMMIT) mengumpulkan notifikasi baru dari EventDb, mengklasifikasi deterministik (KEUANGAN/CHAT/YOUTUBE/ADOBE/SISTEM/LAIN), dedup via seen, lalu me-render digest Markdown + menulis state.json. SEMUA langkah deterministik — TIDAK ADA panggilan LLM di dalam pipeline; tugasmu hanya memanggil rote_run sekali lalu mengembalikan hasilnya apa adanya.
(2) Jawaban akhirmu = digest yang dikembalikan rote_run, LENGKAP (ini yang terkirim sebagai laporan ke room Ringkasan Notifikasi). Bila rote_run melaporkan "SIMULASI GAGAL" / resume, ikuti pesannya: jalankan rote_run lagi untuk melanjutkan dari langkah yang tertinggal — JANGAN mulai dari awal, JANGAN mengulang langkah yang sudah selesai.
(3) Bila tidak ada notifikasi baru, kembalikan satu baris "Tidak ada notifikasi baru." — jangan mengarang notifikasi.
ATURAN KERAS: jangan menilai/menafsir isi notifikasi (klasifikasi sudah deterministik di tool); jangan kirim email; jadwal diatur user lewat Setup 69."""

    // ---------- klasifikasi deterministik (tanpa LLM) ----------

    /** Nominal Rp — regex + batas MINIMAL SAMA PERSIS dengan
     *  Sensors.parseFinance (NotifListener), supaya angka digest =
     *  angka ledger keuangan. < Rp1000 atau tanpa Rp = null. */
    fun parseAmount(text: String): Long? {
        return try {
            val m = Regex("Rp\\.?\\s*([\\d\\.]{4,})").find(text) ?: return null
            val amount = m.groupValues[1].replace(".", "").toLongOrNull() ?: return null
            if (amount < 1000) null else amount
        } catch (e: Exception) { null }
    }

    /** Arah uang — daftar kata kunci SAMA PERSIS dengan
     *  Sensors.parseFinance. Dua arah sekaligus = null (ambigu). */
    fun financeKind(text: String): String? {
        val low = text.lowercase(Locale.US)
        val masukKeys = listOf("masuk", "diterima", "telah diterima", "kredit", "bertambah", "top up berhasil", "refund")
        val keluarKeys = listOf("keluar", "dibayar", "pembayaran", "debit", "terdebet", "terpotong", "belanja", "kirim uang", "transfer berhasil", "pembelian")
        return when {
            masukKeys.any { low.contains(it) } && !keluarKeys.any { low.contains(it) } -> "MASUK"
            keluarKeys.any { low.contains(it) } && !masukKeys.any { low.contains(it) } -> "KELUAR"
            else -> null
        }
    }

    /** Kategori: KEUANGAN dulu (ada nominal Rp), lalu paket aplikasi,
     *  lalu LAIN. Tidak ada penilaian isi — murni aturan. */
    fun classify(pkg: String, text: String): String {
        if (parseAmount(text) != null) return "KEUANGAN"
        val p = pkg.lowercase(Locale.US)
        return when {
            p.contains("whatsapp") || p.contains("telegram") || p.contains("instagram") || p.contains("threads") || p.contains("facebook") || p.contains("messenger") || p.contains("twitter") -> "CHAT"
            p.contains("youtube") -> "YOUTUBE"
            p.contains("adobe") -> "ADOBE"
            p.contains("android") || p.contains("systemui") -> "SISTEM"
            else -> "LAIN"
        }
    }

    /** vc299 m14 (N6-m14): klasifikasi + dedup dari fetched — dipakai
     *  langkah 3 baru DAN resume bila file items hilang/rusak (dulu
     *  resume memberi emptyList tanpa fallback). */
    fun classifyFetched(fetched: List<NotifItem>, seen: Set<String>): List<ClassifiedItem> {
        val seenNow = HashSet<String>()
        val out = ArrayList<ClassifiedItem>()
        for (f in fetched) {
            val k = dedupKey(f.pkg, f.ts, f.text)
            if (k in seen || k in seenNow) continue
            seenNow.add(k)
            val cat = classify(f.pkg, f.text)
            val amt = if (cat == "KEUANGAN") parseAmount(f.text) else null
            val fk = if (cat == "KEUANGAN") financeKind(f.text) else null
            out.add(ClassifiedItem(f, cat, amt, fk))
        }
        return out
    }

    /** Idempotency key per notifikasi (workflow-automation): paket +
     *  stempel waktu + hash teks. String.hashCode() deterministik
     *  lintas run JVM untuk string yang sama. */
    fun dedupKey(pkg: String, ts: Long, text: String): String = pkg + "|" + ts + "|" + text.hashCode()

    // ---------- dedup seen (pola ai-release-hunter seen.json) ----------

    fun seenFromJson(text: String): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) out.add(s)
            }
        } catch (e: Exception) {}
        return out
    }

    fun seenToJson(keys: Collection<String>): String {
        val arr = JSONArray()
        for (k in keys) arr.put(k)
        return arr.toString()
    }

    /** Gabung kunci baru ke seen, buang yang tertua bila > cap (1000). */
    fun mergeSeen(seen: LinkedHashSet<String>, newKeys: Collection<String>): LinkedHashSet<String> {
        val out = LinkedHashSet(seen)
        for (k in newKeys) out.add(k)
        while (out.size > SEEN_CAP) {
            val first = out.iterator().next()
            out.remove(first)
        }
        return out
    }

    // ---------- state.json (checkpoint antar-langkah) ----------

    fun stateFromJson(text: String): RoteState {
        return try {
            val o = JSONObject(text)
            val done = LinkedHashSet<Int>()
            val arr = o.optJSONArray("stepsDone")
            if (arr != null) for (i in 0 until arr.length()) {
                val n = arr.optInt(i, 0)
                if (n in 1..TOTAL_STEPS) done.add(n)
            }
            RoteState(
                o.optString("runId", ""),
                done,
                o.optLong("lastTs", 0),
                seenFromJson(o.optJSONArray("seen")?.toString() ?: "[]"),
                o.optString("lastOutput", ""),
                o.optLong("lastRunAt", 0)
            )
        } catch (e: Exception) { emptyState("") }
    }

    fun stateToJson(s: RoteState): String {
        val o = JSONObject()
        o.put("runId", s.runId)
        val arr = JSONArray()
        for (n in s.stepsDone.sorted()) arr.put(n)
        o.put("stepsDone", arr)
        o.put("lastTs", s.lastTs)
        o.put("seen", JSONArray(seenToJson(s.seen)))
        o.put("lastOutput", s.lastOutput)
        o.put("lastRunAt", s.lastRunAt)
        return o.toString()
    }

    // ---------- file run (hasil per langkah, untuk resume) ----------

    fun fetchedToJson(items: List<NotifItem>): String {
        val arr = JSONArray()
        for (it in items) {
            val o = JSONObject()
            o.put("pkg", it.pkg); o.put("ts", it.ts); o.put("text", it.text)
            arr.put(o)
        }
        return arr.toString()
    }

    fun fetchedFromJson(text: String): List<NotifItem> {
        val out = ArrayList<NotifItem>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(NotifItem(o.optString("pkg", ""), o.optLong("ts", 0), o.optString("text", "")))
            }
        } catch (e: Exception) {}
        return out
    }

    fun itemsToJson(items: List<ClassifiedItem>): String {
        val arr = JSONArray()
        for (ci in items) {
            val o = JSONObject()
            o.put("pkg", ci.item.pkg); o.put("ts", ci.item.ts); o.put("text", ci.item.text)
            o.put("category", ci.category)
            if (ci.amount != null) o.put("amount", ci.amount)
            if (ci.finKind != null) o.put("finKind", ci.finKind)
            arr.put(o)
        }
        return arr.toString()
    }

    fun itemsFromJson(text: String): List<ClassifiedItem> {
        val out = ArrayList<ClassifiedItem>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val amt = if (o.has("amount")) o.optLong("amount", 0) else null
                out.add(ClassifiedItem(
                    NotifItem(o.optString("pkg", ""), o.optLong("ts", 0), o.optString("text", "")),
                    o.optString("category", "LAIN"),
                    amt,
                    if (o.has("finKind")) o.optString("finKind", "") else null
                ))
            }
        } catch (e: Exception) {}
        return out
    }

    /** vc299 m14 (N6-m14): pilih file digest MILIK run ini (lastOutput
     *  yang dicatat langkah 4) — BUKAN yang terbaru global. Diterima
     *  hanya bila file ada DAN ditulis sesudah run ini dibuat
     *  (lastModified >= runStartedAt): file digest run LAIN ditulis
     *  sebelum run ini dibuat sehingga tertolak; run lama tanpa
     *  lastRunAt (<= 0) juga tertolak -> pemanggil menghitung ulang
     *  digest dari item run ini. */
    fun runDigestFile(outDir: java.io.File, lastOutput: String, runStartedAt: Long): java.io.File? {
        if (lastOutput.isBlank() || runStartedAt <= 0) return null
        val f = java.io.File(outDir, lastOutput)
        return if (f.exists() && f.lastModified() >= runStartedAt) f else null
    }

    // ---------- render ----------

    /** Digest Markdown deterministik — seksi per kategori (urutan
     *  tetap), maks MAX_ITEMS butir tampil (sisanya dicatat jujur),
     *  total keuangan dari notifikasi ber-nominal. Kosong = satu baris
     *  "tidak ada notifikasi baru" (jangan mengarang notifikasi). */
    fun renderDigest(date: String, timeStr: String, windowStart: String, windowEnd: String, items: List<ClassifiedItem>, financeMasuk: Long, financeKeluar: Long, financeN: Int, capped: Boolean): String {
        val sb = StringBuilder()
        sb.append("# Ringkasan Notifikasi — ").append(date).append(" ").append(timeStr).append("\n\n")
        sb.append("Jendela: ").append(windowStart).append(" s.d. ").append(windowEnd).append(" · ").append(items.size).append(" notifikasi baru.\n\n")
        if (items.isEmpty()) {
            sb.append("Tidak ada notifikasi baru sejak ").append(windowStart).append(".\n")
            return sb.toString().trim()
        }
        if (financeN > 0) {
            sb.append("Keuangan (dari notifikasi ber-nominal Rp): masuk Rp").append(financeMasuk)
                .append(" · keluar Rp").append(financeKeluar)
                .append(" · net Rp").append(financeMasuk - financeKeluar)
                .append(" (").append(financeN).append(" notifikasi).\n\n")
        }
        val order = listOf("KEUANGAN", "CHAT", "YOUTUBE", "ADOBE", "SISTEM", "LAIN")
        var shown = 0
        for (cat in order) {
            val group = items.filter { it.category == cat }
            if (group.isEmpty()) continue
            sb.append("## ").append(cat).append(" (").append(group.size).append(")\n")
            for (ci in group) {
                if (shown >= MAX_ITEMS) break
                val hhmm = BriefingVc295.dateTimeStr(ci.item.ts).substringAfter(" ")
                sb.append("- [").append(hhmm).append("] ").append(ci.item.pkg).append(": ").append(ci.item.text.take(160)).append("\n")
                shown++
            }
            sb.append("\n")
        }
        val hidden = items.size - shown
        if (hidden > 0) sb.append("(+" ).append(hidden).append(" notifikasi lain tidak ditampilkan di digest ini — semuanya tersimpan di file output.)\n")
        if (capped) sb.append("\n*Catatan: notifikasi baru mencapai batas ambil ").append(FETCH_CAP).append(" per run — jalankan rote_run sekali lagi untuk memproses sisanya.*\n")
        return sb.toString().trim()
    }
}
