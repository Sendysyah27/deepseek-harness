package com.senzwh.deepharness

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Koordinator OpenCode Persisten (Tahap D, brief §0.7/§0.8 + Tahap D).
 *
 * Tugas dari chat (delegate_session / chain_tasks) masuk antrean oc_tasks,
 * lalu dikerjakan SATU per SATU ke opencode serve lewat OcClient:
 * sesi PERSISTEN per rantai (dipakai ulang selama konteks belum melewati
 * batas aturan), izin tool agen di-auto-approve bila cocok pola aturan —
 * selain itu DIPARKIR ke notifikasi persetujuan + Panel Koordinator,
 * hasil akhir dikirim ke riwayat chat + notifikasi (pola Otak Latar).
 *
 * Jalur serve: "resmi" (4097 utama, 4096 cadangan — harness bisa menyalakan
 * 4097 sendiri via Termux) atau "mandiri" (serve MILIK harness di 4100,
 * working dir ~/harness-pure-oc, boot script 44-harness-pure-oc.sh dipasang harness).
 * Aturan koordinasi tersimpan di tabel coord_rules — bisa diedit dari
 * Panel/Setup maupun bahasa natural lewat tool coord_rule.
 */
object OcCoord {

    val RULE_DEFAULTS: Map<String, String> = linkedMapOf(
        "path_mode" to "mandiri",
        "auto_approve" to "",
        "session_ctx_limit" to "350000",
        "chain_max" to "3",
        "chain_delay_sec" to "10",
        "chain_batt_min" to "20",
        "quiet_hours" to "",
        "task_timeout_min" to "20",
        "perm_timeout_min" to "30",
        // VC303-P3-22 (LP-9): anggaran langkah tugas koordinator — jumlah
        // pesan asisten OpenCode per tugas (default 200, cermin cap tool).
        "oc_task_max_steps_vc303" to "200"
    )

    const val APPROVAL_NOTIF_ID = 7711
    const val RESULT_NOTIF_ID = 7712

    // vc243 GELOMBANG 2 (NT-M6): ID notifikasi hasil PER TUGAS, bukan 7712
    // tetap — hasil kedua tidak lagi menimpa hasil pertama di shade.
    // Basis 40000 di luar rentang parkir BgBrain (20000-24999) dan ID tetap
    // lama (7701-7712); task.id autoincrement membuat tiap tugas unik.
    fun resultNotifIdFor(taskId: Long): Int =
        OcCoordSeam.resultNotifIdFor(taskId) ?: (40000 + taskId).toInt()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "oc-coord").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    @Volatile private var permWaiterActive = false
    @Volatile private var watcherStop = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
    private fun mdb(ctx: Context) = MemoryDb.get(ctx.applicationContext)
    private fun rule(ctx: Context, key: String): String {
        val raw = mdb(ctx).coordRule(key, RULE_DEFAULTS[key] ?: "")
        return OcCoordSeam.sanitizeRule(key, raw) ?: ruleSanitizeKotlin(key, raw)
    }

    /** Slice 12 (vc329): sanitasi aturan (salinan asli) — dipakai
     *  bila seam Rust tak-tersedia. */
    private fun ruleSanitizeKotlin(key: String, raw: String): String {
        // Sanitasi baca (v3.3.2): nilai tidak sah yang sempat tersimpan oleh
        // model sebelum ada validasi tulis dinetralkan di sini.
        return when (key) {
            "path_mode" -> if (raw.trim().lowercase() in listOf("resmi", "mandiri")) raw.trim().lowercase() else "mandiri"
            "auto_approve" -> if (raw.trim().lowercase() in listOf("on", "true", "yes", "aktif")) "" else raw
            else -> raw
        }
    }
    private fun ruleInt(ctx: Context, key: String, def: Int): Int {
        val raw = mdb(ctx).coordRule(key, RULE_DEFAULTS[key] ?: "")
        return OcCoordSeam.ruleInt(key, raw, def) ?: rule(ctx, key).trim().toIntOrNull() ?: def
    }

    // vc265 N2-m7: kill switch F1 dicek PER LANGKAH, bukan cuma di pintu
    // masuk runTask — rantai yang sedang berjalan berhenti saat user
    // mematikan F1. Ini jangkauan kill-switch milik user sendiri, bukan
    // gate/izin baru.
    private fun f1Off(ctx: Context): Boolean =
        try { prefs(ctx).getBoolean("f1Off", false) } catch (e: Exception) { false }

    private fun f1StopText(): String =
        "⛔ Tugas koordinator dihentikan: kill switch F1 dimatikan user — aktifkan lagi F1 lewat Profil > Kill Switch F1, lalu kirim ulang tugas."

    // ---------------- Antrean ----------------

    // vc243 GELOMBANG 2 (K-04): tangkap sesi 1-1 aktif SAAT ENQUEUE.
    fun enqueueSingle(ctx: Context, text: String, dir: String = ""): Long {
        val originSession = prefs(ctx).getLongSafe("currentSession", 1)
        val id = mdb(ctx).addOcTask("single-" + System.currentTimeMillis(), text, dir, originSession)
        kick(ctx)
        return id
    }

    fun enqueueChain(ctx: Context, texts: List<String>, dir: String = ""): Pair<String, List<Long>> {
        val chainId = "chain-" + System.currentTimeMillis()
        val originSession = prefs(ctx).getLongSafe("currentSession", 1)
        val ids = texts.map { mdb(ctx).addOcTask(chainId, it, dir, originSession) }
        kick(ctx)
        return Pair(chainId, ids)
    }

    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try { runLoop(app) } catch (e: Exception) {
                try { EventDb.get(app).add("occoord", app.packageName, "Loop error: ${e.message}") } catch (x: Exception) {}
            } finally { running.set(false) }
        }
    }

    fun isRunning(): Boolean = running.get()

    @Volatile private var sweptStale = false

    private fun runLoop(ctx: Context) {
        val db = mdb(ctx)
        if (!sweptStale) {
            sweptStale = true
            // Tugas 'running' saat loop BARU mulai = yatim dari proses app
            // sebelumnya (mesinnya sudah mati, mis. habis update app) — tandai
            // gagal agar tidak menggantung selamanya (v3.3.3).
            try {
                for (t in db.recentOcTasks(30)) if (t.status == "running")
                    db.setOcTask(t.id, status = "failed", result = "❌ Proses app sempat berhenti/restart saat tugas berjalan (kemungkinan habis update app) — kirim ulang tugasnya.")
            } catch (e: Exception) {}
        }
        while (true) {
            val task = db.nextQueuedOcTask() ?: break
            val continuation = db.chainDoneCount(task.chainId) > 0
            if (!runloopGate(ctx, continuation)) break
            runTask(ctx, task)
        }
    }

    /** Slice 12 (vc329): gerbang rantai `runLoop` (tugas lanjutan:
     *  baterai min, jam hening, jeda antar tugas) — dipakai P15
     *  untuk uji paritas. True = lanjut ke `runTask`, false = berhenti. */
    private fun runloopGate(ctx: Context, continuation: Boolean): Boolean {
        if (!continuation) return true
        val battMin = ruleInt(ctx, "chain_batt_min", 20)
        val battery = batteryPct(ctx)
        val quietSpec = rule(ctx, "quiet_hours")
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val delay = ruleInt(ctx, "chain_delay_sec", 10)
        val g = OcCoordSeam.runloopGate(true, battery, battMin, quietSpec, hour, delay)
        if (g != null) {
            return when (g.first) {
                "run" -> {
                    if (g.third > 0) Thread.sleep(g.third * 1000L)
                    true
                }
                "delay_battery", "delay_quiet" -> {
                    EventDb.get(ctx).add("occoord", ctx.packageName, g.second ?: "")
                    false
                }
                else -> runloopGateKotlin(ctx)
            }
        }
        return runloopGateKotlin(ctx)
    }

    /** Slice 12 (vc329): gerbang rantai (salinan asli) — dipakai bila
     *  seam Rust tak-tersedia. */
    private fun runloopGateKotlin(ctx: Context): Boolean {
        val battMin = ruleInt(ctx, "chain_batt_min", 20)
        if (batteryPct(ctx) < battMin) {
            EventDb.get(ctx).add("occoord", ctx.packageName, "Rantai ditunda: baterai di bawah $battMin%")
            return false
        }
        if (isQuietNow(rule(ctx, "quiet_hours"))) {
            EventDb.get(ctx).add("occoord", ctx.packageName, "Rantai ditunda: jam hening aktif")
            return false
        }
        val delay = ruleInt(ctx, "chain_delay_sec", 10)
        if (delay > 0) Thread.sleep(delay * 1000L)
        return true
    }

    /** Slice 12 (vc329): keputusan rotasi sesi (salinan asli) —
     *  dipakai bila seam Rust tak-tersedia. */
    private fun sessionRotateKotlin(sid: String, prevCtx: Long, ctxLimit: Long, taskId: Long, taskText: String): OcCoordSeam.SessionRotate {
        val need = sid.isBlank() || prevCtx > ctxLimit
        if (!need) return OcCoordSeam.SessionRotate(false, "", null)
        val title = "🤖 Harness #${taskId} " + taskText.take(42)
        val note = if (sid.isNotBlank() && prevCtx > ctxLimit) {
            "(Sesi dirotasi: konteks sesi sebelumnya $prevCtx token melewati batas $ctxLimit — sesi lama dibiarkan utuh di serve, tidak dihapus.)\n"
        } else ""
        return OcCoordSeam.SessionRotate(true, note, title)
    }

    // ---------------- Eksekusi satu tugas ----------------

    private data class Outcome(val status: String, val text: String, val ctxSize: Long)

    private fun runTask(ctx: Context, task: MemoryDb.OcTask) {
        // vc262 N-M10: kill switch F1 kini menjangkau koordinator — cek di
        // pintu masuk tugas, sebelum serve/sesi/checkpoint jalan.
        // vc265 N2-m7: cek yang sama diulang per langkah di bawah (sesudah
        // serve, sebelum kirim prompt, di loop konfirmasi kirim, dan di
        // tiap iterasi awaitCompletion) supaya rantai yang sedang berjalan
        // berhenti saat user mematikan F1 di tengah jalan.
        if (f1Off(ctx)) {
            try { EventDb.get(ctx).add("occoord", ctx.packageName, "Tugas #${task.id} DIBATALKAN: kill switch F1 dimatikan user.") } catch (e: Exception) {}
            finishTask(ctx, task, "failed", f1StopText())
            return
        }
        val db = mdb(ctx)
        val p = prefs(ctx)
        db.setOcTask(task.id, status = "running")
        p.edit().putLong("ocCurrentTask", task.id).apply()
        if (task.dir.isNotBlank()) {
            val okSnap = Checkpoint.snapshot(ctx, task.id, task.dir)
            try { EventDb.get(ctx).add("occoord", ctx.packageName, "Checkpoint tugas #${task.id}: ${if (okSnap) "snapshot dibuat (${task.dir})" else "GAGAL — dir tidak valid/tidak bisa disalin"}") } catch (e: Exception) {}
        }
        EventDb.get(ctx).add("occoord", ctx.packageName, "Tugas #${task.id} mulai: ${task.text.take(80)}")
        try {
            val (base, serveNote) = ensureServe(ctx)
            if (base == null) {
                finishTask(ctx, task, "failed", "❌ Koordinator berhenti: $serveNote")
                return
            }
            // vc265 N2-m7: berhenti sebelum sesi dibuat bila F1 dimatikan
            // selama ensureServe menunggu serve menyala.
            if (f1Off(ctx)) {
                finishTask(ctx, task, "failed", f1StopText())
                return
            }
            // Sesi persisten per rantai + rotasi bila konteks melewati batas.
            // Slice 12 (vc329): keputusan rotasi lewat seam Rust;
            // pembuatan sesi + tulis prefs tetap Kotlin.
            val ctxLimit = ruleInt(ctx, "session_ctx_limit", 350000).toLong()
            val sKey = "ocChainSession:${task.chainId}"
            val cKey = "ocChainCtx:${task.chainId}"
            var sid = p.getString(sKey, "") ?: ""
            val prevCtx = p.getLong(cKey, 0)
            var rotateNote = ""
            val rot = OcCoordSeam.sessionRotate(sid, prevCtx, ctxLimit, task.id, task.text)
                ?: sessionRotateKotlin(sid, prevCtx, ctxLimit, task.id, task.text)
            if (rot.needCreate) {
                val title = rot.title ?: ("🤖 Harness #${task.id} " + task.text.take(42))
                val created = OcClient.createSession(base, title)
                if (created == null) {
                    finishTask(ctx, task, "failed", "❌ Gagal membuat sesi OpenCode di $base.")
                    return
                }
                rotateNote = rot.rotateNote
                sid = created
                p.edit().putString(sKey, sid).putLong(cKey, 0).apply()
            }
            db.setOcTask(task.id, sessionId = sid)
            val baseline = OcClient.messages(base, sid).length()
            val prompt = "Tugas dari Harness (asisten di HP user — hasil akhirmu diteruskan ke chat HP user, jadi akhiri dengan ringkasan jelas tentang apa yang dikerjakan & hasilnya):\n\n" + task.text
            // vc265 N2-m7: berhenti sebelum prompt dikirim bila F1
            // dimatikan selama pembuatan/rotasi sesi.
            if (f1Off(ctx)) {
                finishTask(ctx, task, "failed", f1StopText())
                return
            }
            if (!OcClient.sendPrompt(base, sid, prompt)) {
                finishTask(ctx, task, "failed", "❌ Gagal mengirim tugas ke sesi OpenCode ($base).")
                return
            }
            // v3.3.3: konfirmasi prompt BENAR-BENAR tercatat di sesi (prompt_async
            // bisa balas 204 tapi pesan hilang). Belum tercatat → kirim ulang 1x →
            // masih gagal → tugas gagal cepat dengan diagnosis, bukan menggantung.
            var delivered = false
            var tries = 0
            while (tries < 8 && !delivered) {
                if (f1Off(ctx)) {
                    finishTask(ctx, task, "failed", f1StopText())
                    return
                }
                Thread.sleep(2500); tries++
                if (OcClient.messages(base, sid).length() > baseline) delivered = true
            }
            if (!delivered) {
                OcClient.sendPrompt(base, sid, prompt)
                var tries2 = 0
                while (tries2 < 6 && !delivered) {
                    if (f1Off(ctx)) {
                        finishTask(ctx, task, "failed", f1StopText())
                        return
                    }
                    Thread.sleep(2500); tries2++
                    if (OcClient.messages(base, sid).length() > baseline) delivered = true
                }
            }
            if (!delivered) {
                finishTask(ctx, task, "failed", "❌ Prompt tidak tercatat di sesi serve setelah 2x pengiriman — serve/model kemungkinan sedang bermasalah. Coba lagi nanti.")
                return
            }
            val watcher = startPermWatcher(ctx, base, sid, task.id)
            val outcome = awaitCompletion(ctx, base, sid, task.id, baseline)
            stopWatcher(watcher)
            p.edit().putLong(cKey, outcome.ctxSize).apply()
            val head = listOf(serveNote, rotateNote).filter { it.isNotBlank() }.joinToString("\n")
            val full = (if (head.isNotBlank()) head + "\n" else "") + outcome.text
            finishTask(ctx, task, outcome.status, full)
        } catch (e: Exception) {
            finishTask(ctx, task, "failed", "❌ Koordinator error: ${e.message}")
        } finally {
            if (prefs(ctx).getLongSafe("ocCurrentTask", 0) == task.id) {
                prefs(ctx).edit().putLong("ocCurrentTask", 0).apply()
            }
        }
    }

    // VC303-P3-22 (LP-9): detektor spinning atas output INKREMENTAL —
    // pendamping spinningPatternVc277 (yang pasca-hoc atas teks hasil).
    // observe() dipanggil tiap poll dengan potongan teks BARU saja; baris
    // ternormalisasi (angka dihapus, <20 char diabaikan) yang sama >= 3x
    // mengembalikan cuplikan baris itu. Baris yang terpotong di tengah
    // chunk ditahan di buffer sampai baris lengkap (newline) tiba.
    class OcSpinWatchVc303 {
        private val counts = HashMap<String, Int>()
        private val firstSeen = HashMap<String, String>()
        private var pending = ""
        fun observe(chunk: String): String? {
            // Slice 12 (vc329): satu langkah lewat seam Rust (state
            // JSON bolak-balik); fallback = observe asli.
            val r = OcCoordSeam.spinObserve(stateJson(), chunk) ?: return observeKotlin(chunk)
            applyState(r.second)
            return r.first
        }
        // Baris terakhir tanpa newline ikut dihitung saat tugas selesai.
        fun flush(): String? {
            // Slice 12 (vc329): flush lewat seam Rust (pending selalu
            // dibersihkan walau norm <20); fallback = flush asli.
            val r = OcCoordSeam.spinFlush(stateJson()) ?: return flushKotlin()
            applyState(r.second)
            return r.first
        }
        fun reset() { counts.clear(); firstSeen.clear(); pending = "" }

        /** Slice 12 (vc329): serialisasi state watch (counts +
         *  first_seen + pending) untuk seam Rust. */
        private fun stateJson(): String {
            val o = JSONObject()
            val c = JSONObject()
            for ((k, v) in counts) c.put(k, v)
            val f = JSONObject()
            for ((k, v) in firstSeen) f.put(k, v)
            o.put("counts", c)
            o.put("first_seen", f)
            o.put("pending", pending)
            return o.toString()
        }

        /** Slice 12 (vc329): terapkan state JSON dari seam Rust. */
        private fun applyState(json: String) {
            try {
                val o = JSONObject(json)
                counts.clear()
                val c = o.optJSONObject("counts")
                if (c != null) {
                    val keys = c.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        counts[k] = c.optInt(k)
                    }
                }
                firstSeen.clear()
                val f = o.optJSONObject("first_seen")
                if (f != null) {
                    val keys = f.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        firstSeen[k] = f.optString(k)
                    }
                }
                pending = o.optString("pending")
            } catch (e: Exception) {
                // State rusak: reset (watch hanya memengaruhi deteksi
                // spinning — tidak ada data pengguna yang hilang).
                counts.clear(); firstSeen.clear(); pending = ""
            }
        }

        /** Slice 12 (vc329): observe asli — dipakai bila seam Rust
         *  tak-tersedia. */
        private fun observeKotlin(chunk: String): String? {
            if (chunk.isEmpty()) return null
            pending += chunk
            val lines = pending.split("\n")
            pending = lines.last()
            for (i in 0 until lines.size - 1) {
                val raw = lines[i]
                val norm = raw.lowercase().replace(Regex("[0-9]+"), "#").replace(Regex("\\s+"), " ").trim()
                if (norm.length < 20) continue
                val n = (counts[norm] ?: 0) + 1
                counts[norm] = n
                if (!firstSeen.containsKey(norm)) firstSeen[norm] = raw.trim()
                if (n >= 3) return firstSeen[norm]?.take(120)
            }
            return null
        }

        /** Slice 12 (vc329): flush asli — dipakai bila seam Rust
         *  tak-tersedia. */
        private fun flushKotlin(): String? {
            if (pending.isBlank()) return null
            val raw = pending
            pending = ""
            val norm = raw.lowercase().replace(Regex("[0-9]+"), "#").replace(Regex("\\s+"), " ").trim()
            if (norm.length < 20) return null
            val n = (counts[norm] ?: 0) + 1
            counts[norm] = n
            if (!firstSeen.containsKey(norm)) firstSeen[norm] = raw.trim()
            return if (n >= 3) firstSeen[norm]?.take(120) else null
        }
    }

    // VC303-P3-22 (LP-9): anggaran langkah tugas koordinator — jumlah pesan
    // asisten OpenCode sejak baseline (tiap pesan ≈ 1 langkah agen).
    // Default 200 (cermin cap tool 200); bisa diubah lewat aturan
    // oc_task_max_steps_vc303. Polling delegate_wait/delegate_status tetap
    // dikecualikan dari StuckDetector (AgentPrompt.isPollingExemptVc277 —
    // tidak disentuh); anggaran ini menghitung pesan ASISTEN serve, bukan
    // poll Harness, jadi tidak menghukum polling yang sah.
    class OcTaskBudgetVc303(val maxSteps: Int = 200) {
        fun overSteps(assistantMessages: Int): Boolean =
            OcCoordSeam.budgetOver(assistantMessages, maxSteps) ?: (assistantMessages > maxSteps)
    }

    // vc277 O17(d): OcCoord tetap tanpa StuckDetector proses (OpenCode
    // punya loop sendiri) — tetapi finishTask menolak hasil yang
    // terdeteksi berputar: satu pola (baris ternormalisasi, angka
    // dihapus) yang sama berulang >=3x dalam teks hasil. Mengembalikan
    // cuplikan pola yang berulang, atau null bila tidak berputar.
    // Baris pendek (<20 char) diabaikan agar header/pemisah umum tidak
    // memicu deteksi.
    private fun spinningPatternVc277(text: String): String? =
        OcCoordSeam.spinningPattern(text) ?: spinningPatternVc277Kotlin(text)

    /** Slice 12 (vc329): pola berputar pasca-hoc (salinan asli) —
     *  dipakai bila seam Rust tak-tersedia. */
    private fun spinningPatternVc277Kotlin(text: String): String? {
        val counts = HashMap<String, Int>()
        val firstSeen = HashMap<String, String>()
        for (raw in text.lines()) {
            val norm = raw.lowercase().replace(Regex("[0-9]+"), "#").replace(Regex("\\s+"), " ").trim()
            if (norm.length < 20) continue
            val n = (counts[norm] ?: 0) + 1
            counts[norm] = n
            if (!firstSeen.containsKey(norm)) firstSeen[norm] = raw.trim()
            if (n >= 3) return firstSeen[norm]?.take(120)
        }
        return null
    }

    // vc262 K-13: penanda status JUJUR dari isi hasil.
    private fun looksFailedVc262(text: String): Boolean =
        OcCoordSeam.looksFailed(text) ?: looksFailedVc262Kotlin(text)

    /** Slice 12 (vc329): penanda gagal (salinan asli) — dipakai bila
     *  seam Rust tak-tersedia. */
    private fun looksFailedVc262Kotlin(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("gagal") || t.contains("❌") || t.contains("tidak berhasil") || t.contains("error") || t.contains("tidak selesai")
    }

    /** Slice 12 (vc329): prefix spinning + status JUJUR (salinan
     *  asli, vc262 K-13) — dipakai bila seam Rust tak-tersedia. */
    private fun honestStatusKotlin(status: String, spinning: String?, resultFull: String): Pair<String, String> {
        var res = resultFull
        if (spinning != null) res = "⛔ Hasil koordinator terdeteksi berputar: pola yang sama berulang 3x atau lebih dalam teks hasil (contoh: \"" + spinning + "\") — status SEBAGIAN, periksa hasilnya sebelum dipakai.\n\n" + res
        // vc262 K-13: status JUJUR — hasil yang isinya gagal/sebagian
        // ditandai "partial", bukan "done".
        val honest = when {
            status == "failed" -> "failed"
            spinning != null -> "partial"
            looksFailedVc262(res) -> "partial"
            else -> status
        }
        return honest to res
    }

    private fun finishTask(ctx: Context, task: MemoryDb.OcTask, status: String, result: String) {
        var resultFull = AgentPrompt.stripSafetyTags(result)
        if (Checkpoint.has(ctx, task.id)) {
            val diffText = Checkpoint.diff(ctx, task.id)
            if (diffText != null) {
                resultFull = result + "\n\n" + diffText + "\nCheckpoint menunggu keputusan: accept = perubahan dipertahankan & snapshot dibuang; undo = file dikembalikan ke kondisi sebelum tugas. Pakai tool oc_checkpoint {action, task_id: ${task.id}} atau tombol di Panel Koordinator (tab Monitor)."
                prefs(ctx).edit().putLong("ckptPending", task.id).putString("ckptDiff", diffText).apply()
            } else {
                Checkpoint.discard(ctx, task.id)
            }
        }
        // vc275 O12(b): claim warning + gate bukti koordinator SEBELUM hasil
        // ditulis ke riwayat/notifikasi/laporan. Hasil koordinator datang
        // dari OpenCode (bukan tool app), jadi tidak ada daftar tool giliran
        // untuk diperiksa penanda buktinya — pemeriksanya adalah
        // claimWarningForCorpus dengan daftar tool kosong: klaim faktual
        // yang tidak didukung memori sama sekali mendapat peringatan jujur
        // di kepala hasil (bukan dibuang, bukan diblokir).
        // StuckDetector untuk ISI kerja OpenCode TETAP TIDAK ADA (OpenCode
        // punya loop sendiri) — yang diverifikasi di sini hanya HASIL,
        // bukan pengawasan proses.
        if (AgentPrompt.hasFactualClaim(resultFull)) {
            val pinnedOcWarnVc275 = try { mdb(ctx).pinnedFactsText() } catch (e: Exception) { "" }
            val warnOcVc275 = AgentPrompt.claimWarningForCorpus(resultFull, emptyList(), "TUGAS KOORDINATOR:\n" + task.text + "\nHASIL:\n" + resultFull, pinnedOcWarnVc275, task.text)
            if (warnOcVc275 != null) resultFull = "⚠️ Peringatan sistem (koordinator): klaim faktual di hasil ini tidak didukung bukti tool app (" + warnOcVc275 + ") — OpenCode mengerjakan tugas di luar app, jadi periksa file/hasilnya sebelum dipakai sebagai fakta.\n\n" + resultFull
        }
        // vc262 N-M13: reviewer konteks-nol koordinator — klaim + bukti
        // (teks tugas + hasil), lewat helper Models API (stateless),
        // lalu double-pass sebelum hasil ditulis ke
        // riwayat/notifikasi/laporan.
        if (AgentPrompt.hasFactualClaim(resultFull)) {
            try {
                val claimsVc262 = AgentPrompt.splitClaims(resultFull)
                if (claimsVc262.isNotEmpty()) {
                    // F2c (K1, Fase 6): reviewer lewat helper Models API —
                    // tanpa slot. Reviewer stateless (independen).
                    // Gate over-cap openai: refusal per-provider bucket reviewer; provider null → lanjut ke helper (throw-nya tertangkap catch → penanda "belum terverifikasi").
                    val providerOcVcF2c = try { LlmEngineChat.resolveProvider(ctx) } catch (e: Exception) { null }
                    val refusalOcVcF2c = providerOcVcF2c?.let { try { Governor.refusalModels(ctx, it, Governor.MODELS_BUCKET_REVIEWER) } catch (e: Exception) { null } }
                    if (refusalOcVcF2c != null) {
                        resultFull = refusalOcVcF2c + "\n\n" + resultFull
                    } else {
                        // vc266: FAKTA PIN masuk bukti reviewer + klaim yang didukung memori dikeluarkan dari daftar tak didukung.
                        val pinnedOcVc266 = try { mdb(ctx).pinnedFactsText() } catch (e: Exception) { "" }
                        val evVc262 = ("TUGAS KOORDINATOR:\n" + task.text + "\nHASIL:\n" + resultFull + "\nFAKTA PIN:\n" + pinnedOcVc266).take(16000)
                        // vc275 O11: verdict tak terparse -> retry 1x format ketat; tetap gagal -> penanda "belum terverifikasi". (Helper: kind REVIEWER → bucket reviewer, jawaban kosong = error.)
                        var unsVc262: List<Int> = emptyList()
                        var concVc262 = false
                        var reviewFailOcVc275 = false
                        try {
                            val rawVc262 = LlmEngineChat.chatSyncForEngine(ctx, AgentPrompt.zeroCtxReviewPrompt(claimsVc262, evVc262), LlmCallKind.REVIEWER)
                            val (u1OcVc275, c1OcVc275) = AgentPrompt.parseZeroCtxReview(rawVc262)
                            if (c1OcVc275) { unsVc262 = u1OcVc275; concVc262 = true } else {
                                val rawOc2Vc275 = LlmEngineChat.chatSyncForEngine(ctx, AgentPrompt.reviewerRetryPrompt(claimsVc262, evVc262), LlmCallKind.REVIEWER)
                                val (u2OcVc275, c2OcVc275) = AgentPrompt.parseZeroCtxReview(rawOc2Vc275)
                                if (c2OcVc275) { unsVc262 = u2OcVc275; concVc262 = true } else reviewFailOcVc275 = true
                            }
                        } catch (e: Exception) { reviewFailOcVc275 = true }
                        val unsVc262FiltVc266 = AgentPrompt.filterUnsupportedByMemory(claimsVc262, unsVc262, pinnedOcVc266.lines(), task.text)
                        if (reviewFailOcVc275) resultFull = AgentPrompt.reviewerNotVerifiedNote() + "\n\n" + resultFull
                        else if (unsVc262FiltVc266.isNotEmpty()) {
                            // vc289 Fase 3: footer "Yang belum diketahui" di koordinator (paritas 1-1 vc288) — HANYA bila ada klaim dihapus.
                            // Jendela 2 giliran TIDAK berlaku di sini: hasil koordinator datang dari OpenCode tanpa giliran tool app,
                            // jadi tidak ada "2 giliran sebelumnya" untuk diperluas; bukti = teks tugas + hasil + FAKTA PIN.
                            val correctedOcVc289 = AgentPrompt.applyDoublePass(resultFull, claimsVc262, unsVc262FiltVc266)
                            val footerOcVc289 = AgentPrompt.unknownFooterVc288(claimsVc262, unsVc262FiltVc266, emptyList())
                            resultFull = AgentPrompt.reviewerNoteText(claimsVc262, unsVc262FiltVc266) + "\n\n" + correctedOcVc289 + (if (footerOcVc289.isNotEmpty()) "\n\n" + footerOcVc289 else "")
                        }
                    }
                }
            } catch (e: Exception) {
                resultFull = AgentPrompt.reviewerNotVerifiedNote() + "\n\n" + resultFull
            }
        }
        // vc277 O17(d): hasil berputar (>=3x pola sama) = partial + alasan.
        val spinningVc277 = spinningPatternVc277(result)
        // Slice 12 (vc329): prefix spinning + status jujur lewat seam
        // Rust (Kotlin menghitung ulang bila seam tak-tersedia).
        val honestPair = OcCoordSeam.honestStatus(status, spinningVc277, resultFull)
            ?: honestStatusKotlin(status, spinningVc277, resultFull)
        val honestStatusVc262 = honestPair.first
        resultFull = honestPair.second
        val db = mdb(ctx)
        db.setOcTask(task.id, status = honestStatusVc262, result = resultFull)
        // vc243 GELOMBANG 2 (K-04): hasil pulang ke SESI ASAL enqueue, bukan
        // sesi aktif saat selesai. Sesi asal hilang (dihapus/diarsip) = hasil
        // TIDAK ditulis ke mana-mana; notifikasi menyebut sesi asalnya.
        // vc243 GELOMBANG 2 (GR-M3, pilihan sumber terpisah): hasil juga
        // dicatat ke daftar laporan koordinator yang ditampilkan di tab
        // Laporan (berlabel sumber terpisah, bukan laporan room itu).
        val origin = task.originSession
        val originTitle = try { db.sessions().firstOrNull { it.id == origin }?.title } catch (e: Exception) { null }
        val originAlive = origin > 0 && originTitle != null
        if (originAlive) {
            try {
                db.addHistory("user", "🤖 [KOORDINATOR] " + task.text.take(300), origin)
                db.addHistory("asisten", resultFull.take(4000), origin)
            } catch (e: Exception) {}
        }
        try { Automation.appendCoordReport(ctx, task.id, task.text, resultFull, origin, originTitle ?: "") } catch (e: Exception) {}
        val icon = if (honestStatusVc262 == "done") "🤖 Koordinator selesai" else if (honestStatusVc262 == "partial") "🤖 Koordinator: SEBAGIAN" else "🤖 Koordinator: $honestStatusVc262"
        val originNote = if (originAlive) " (sesi asal: $originTitle)" else " (sesi asal #$origin sudah dihapus/diarsip — hasil tidak ditulis ke riwayat chat mana pun)"
        notifyTask(ctx, task, "$icon — tugas #${task.id}$originNote", resultFull.take(400))
        EventDb.get(ctx).add("occoord", ctx.packageName, "Tugas #${task.id} $honestStatusVc262 (sesi asal #$origin, hidup=$originAlive)")
    }

    /**
     * Tunggu agen selesai. Selesai = serve tidak busy, ATAU ada pesan
     * finish=stop dan daftar pesan diam 3 poll berturut (serve kadang tetap
     * busy untuk langkah ringkasan internal — terverifikasi di perangkat).
     */
    private fun awaitCompletion(ctx: Context, base: String, sid: String, taskId: Long, baseline: Int): Outcome {
        val timeoutMs = ruleInt(ctx, "task_timeout_min", 20) * 60_000L
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastCount = -1
        var stable = 0
        var finishedSeen = false
        val loopStart = System.currentTimeMillis()
        var lastChangeAt = loopStart
        // VC303-P3-22 (LP-9): anggaran langkah + deteksi spinning inkremental.
        val budgetVc303 = OcTaskBudgetVc303(ruleInt(ctx, "oc_task_max_steps_vc303", 200))
        val spinWatchVc303 = OcSpinWatchVc303()
        var checkedCharsVc303 = 0
        while (System.currentTimeMillis() < deadline) {
            // vc265 N2-m7: cek F1 tiap iterasi poll — rantai yang sedang
            // menunggu agen selesai berhenti di sini, bukan sampai timeout.
            if (f1Off(ctx)) {
                OcClient.abort(base, sid)
                val (txtF1, ctxF1) = collectResult(base, sid, baseline)
                val bodyF1 = "⛔ Tugas dihentikan: kill switch F1 dimatikan user — aktifkan lagi F1 lewat Profil > Kill Switch F1, lalu kirim ulang tugas." + (if (txtF1.isNotBlank()) "\nHasil parsial:\n$txtF1" else "")
                return Outcome("cancelled", bodyF1, ctxF1)
            }
            if (prefs(ctx).getLong("ocCancelTask", 0) == taskId) {
                OcClient.abort(base, sid)
                prefs(ctx).edit().putLong("ocCancelTask", 0).apply()
                val (txt, ctxSize) = collectResult(base, sid, baseline)
                return Outcome("cancelled", "⏹ Tugas dihentikan user dari panel. Hasil parsial:\n$txt", ctxSize)
            }
            val msgs = OcClient.messages(base, sid)
            if (msgs.length() == lastCount) stable++ else { stable = 0; lastCount = msgs.length(); lastChangeAt = System.currentTimeMillis() }
            // Slice 12 (vc329): pindaian pesan lewat seam Rust
            // (finishedSeen bersifat persisten antar poll).
            val (asstCountVc303, finishedNew, agentError) = scanMessages(msgs, baseline)
            finishedSeen = finishedSeen || finishedNew
            if (agentError != null) {
                return Outcome("failed", "❌ Agen OpenCode error dari serve: $agentError\nIni gangguan di sisi opencode.ai/model-nya, bukan harness. Tugas bisa dikirim ulang nanti.", 0)
            }
            // VC303-P3-22 (LP-9): anggaran langkah koordinator — jumlah pesan
            // asisten OpenCode sejak baseline (tiap pesan ≈ 1 langkah agen).
            // Bukan gate approval: hanya menghentikan tugas yang berputar
            // tanpa selesai sebelum timeout 20 menit menghabiskan waktu.
            if (budgetVc303.overSteps(asstCountVc303)) {
                OcClient.abort(base, sid)
                return Outcome("failed", "⛔ Anggaran langkah koordinator tercapai: agen OpenCode sudah mengirim $asstCountVc303 pesan asisten (maks ${budgetVc303.maxSteps}) tanpa selesai — tugas dihentikan, bukan ditunggu sampai timeout. Cek sesi langsung di serve/Termux.", 0)
            }
            // VC303-P3-22 (LP-9): deteksi spinning atas output INKREMENTAL
            // (bukan hanya hasil akhir) — baris yang sama >= 3x di tengah
            // jalan sudah menghentikan tugas. Hanya potongan BARU yang
            // diperiksa tiap poll (checkedCharsVc303); spinningPatternVc277
            // pasca-hoc di finishTask tetap jadi jaring pengaman akhir.
            val incTextVc303 = try { incrementalAssistantTextVc303(msgs, baseline) } catch (e: Exception) { "" }
            if (incTextVc303.length > checkedCharsVc303) {
                val newChunkVc303 = incTextVc303.substring(checkedCharsVc303)
                checkedCharsVc303 = incTextVc303.length
                val spinVc303 = spinWatchVc303.observe(newChunkVc303)
                if (spinVc303 != null) {
                    OcClient.abort(base, sid)
                    return Outcome("failed", "⛔ Koordinator berputar (output inkremental mengulang pola yang sama >= 3x): \"$spinVc303\" — tugas dihentikan di tengah jalan; hasil parsial bisa dicek di sesi serve.", 0)
                }
            }
            val busy = OcClient.statusMap(base).optJSONObject(sid)?.optString("type") == "busy"
            if (finishedSeen && (!busy || stable >= 3)) break
            // Detektor macet: tidak sibuk + nol progres >90 dtk = serve/model beku.
            if (!busy && System.currentTimeMillis() - lastChangeAt > 90_000) {
                return Outcome("failed", "❌ Agen tidak merespons sama sekali selama 90 detik (serve/model kemungkinan sedang down). Coba lagi nanti.", 0)
            }
            Thread.sleep(5000)
        }
        val (txt, ctxSize) = collectResult(base, sid, baseline)
        val timedOut = System.currentTimeMillis() >= deadline && !finishedSeen
        if (timedOut) OcClient.abort(base, sid)
        // Slice 12 (vc329): badan akhir + status lewat seam Rust.
        val (status, body) = awaitBody(txt, timedOut, sid)
        return Outcome(status, body, ctxSize)
    }

    /** Slice 12 (vc329): pindaian pesan `awaitCompletion` (jumlah
     *  asisten, finishedSeen, agentError terakhir) — dipakai P15
     *  untuk uji paritas. */
    private fun scanMessages(msgs: JSONArray, baseline: Int): Triple<Int, Boolean, String?> {
        val r = OcCoordSeam.scanMessages(msgs, baseline)
        if (r != null) return r
        return scanMessagesKotlin(msgs, baseline)
    }

    /** Slice 12 (vc329): pindaian pesan (salinan asli) — dipakai bila
     *  seam Rust tak-tersedia. */
    private fun scanMessagesKotlin(msgs: JSONArray, baseline: Int): Triple<Int, Boolean, String?> {
        var agentError: String? = null
        var asstCountVc303 = 0
        var finishedSeen = false
        for (i in baseline until msgs.length()) {
            val info = msgs.optJSONObject(i)?.optJSONObject("info") ?: continue
            if (info.optString("role") != "assistant") continue
            asstCountVc303++
            if (info.optString("finish") == "stop") finishedSeen = true
            // v3.3.3: pesan asisten ber-error (mis. endpoint model HTTP 402
            // "Endpoint is unavailable" dari opencode.ai) = tugas GAGAL
            // seketika — dulu finish kosong bikin tugas menggantung sampai
            // timeout 20 menit (insiden tugas #1, 2026-10-22).
            val errObj = info.optJSONObject("error")
            if (errObj != null) {
                val d = errObj.optJSONObject("data")
                val msg = d?.optString("message")?.ifEmpty { null } ?: errObj.optString("name").ifEmpty { "error tak dikenal" }
                val code = d?.optInt("statusCode", 0) ?: 0
                agentError = msg + if (code > 0) " (HTTP $code)" else ""
            }
        }
        return Triple(asstCountVc303, finishedSeen, agentError)
    }

    /** Slice 12 (vc329): badan akhir `awaitCompletion` — dipakai P15
     *  untuk uji paritas. */
    private fun awaitBody(txt: String, timedOut: Boolean, sid: String): Pair<String, String> {
        val r = OcCoordSeam.awaitBody(txt, timedOut, sid)
        if (r != null) return r
        return awaitBodyKotlin(txt, timedOut, sid)
    }

    /** Slice 12 (vc329): badan akhir (salinan asli) — dipakai bila
     *  seam Rust tak-tersedia. */
    private fun awaitBodyKotlin(txt: String, timedOut: Boolean, sid: String): Pair<String, String> {
        val body = when {
            txt.isBlank() && timedOut -> "⏱ Timeout tanpa jawaban teks dari agen (sesi $sid). Cek sesi langsung di serve/Termux."
            txt.isBlank() -> "(Agen selesai tanpa teks jawaban — kemungkinan semua hasil berupa aksi tool. Cek sesi $sid di Panel/serve.)"
            timedOut -> "⏱ Timeout — agen dihentikan; hasil parsial:\n$txt"
            else -> txt
        }
        return (if (timedOut) "failed" else "done") to body
    }

    // VC303-P3-22 (LP-9): teks jawaban asisten sejak baseline dari JSONArray
    // pesan yang SUDAH diambil (tanpa fetch ulang) — cermin collectResult
    // tanpa cap 3000 char, supaya deteksi spinning inkremental melihat
    // seluruh output, bukan hanya 3000 char pertama.
    private fun incrementalAssistantTextVc303(msgs: JSONArray, baseline: Int): String {
        return OcCoordSeam.incrementalText(msgs, baseline) ?: incrementalAssistantTextVc303Kotlin(msgs, baseline)
    }

    /** Slice 12 (vc329): teks asisten inkremental (salinan asli) —
     *  dipakai bila seam Rust tak-tersedia. */
    private fun incrementalAssistantTextVc303Kotlin(msgs: JSONArray, baseline: Int): String {
        val sb = StringBuilder()
        for (i in baseline until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            val info = m.optJSONObject("info") ?: continue
            if (info.optString("role") != "assistant") continue
            val parts = m.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.optString("type") == "text") {
                    val t = part.optString("text").trim()
                    if (t.isNotEmpty() && !t.startsWith("## Objective")) {
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append(t)
                    }
                }
            }
        }
        return sb.toString()
    }

    /** Gabung teks jawaban asisten setelah baseline; lewati ringkasan "## Objective". */
    private fun collectResult(base: String, sid: String, baseline: Int): Pair<String, Long> {
        val msgs = OcClient.messages(base, sid)
        return OcCoordSeam.collectResult(msgs, baseline) ?: collectResultKotlin(msgs, baseline)
    }

    /** Slice 12 (vc329): gabung hasil asisten (salinan asli, pesan
     *  sudah diambil sekali) — dipakai bila seam Rust tak-tersedia. */
    private fun collectResultKotlin(msgs: JSONArray, baseline: Int): Pair<String, Long> {
        val sb = StringBuilder()
        var ctxSize = 0L
        for (i in baseline until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            val info = m.optJSONObject("info") ?: continue
            if (info.optString("role") != "assistant") continue
            val tok = info.optJSONObject("tokens")
            if (tok != null) {
                val cs = tok.optLong("input") + (tok.optJSONObject("cache")?.optLong("read") ?: 0)
                if (cs > 0) ctxSize = cs
            }
            val parts = m.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.optString("type") == "text") {
                    val t = part.optString("text").trim()
                    if (t.isNotEmpty() && !t.startsWith("## Objective")) {
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append(t)
                    }
                }
            }
        }
        return Pair(sb.toString().take(3000), ctxSize)
    }

    // ---------------- Izin tool agen (SSE /event) ----------------

    private class WatcherHandle(@Volatile var conn: java.net.HttpURLConnection? = null, @Volatile var thread: Thread? = null)

    private fun startPermWatcher(ctx: Context, base: String, sid: String, taskId: Long): WatcherHandle {
        watcherStop = false
        val handle = WatcherHandle()
        // v3.3.3: stream event dibuka DI DALAM thread watcher. Membukanya di
        // thread koordinator bisa MEMBLOKIR tanpa batas (header SSE serve kadang
        // baru mengalir saat event pertama) sehingga prompt tidak pernah
        // terkirim dan tugas menggantung "running" selamanya (insiden #1).
        val t = Thread {
            val stream = OcClient.openEventStream(base)
            if (stream == null) {
                try { EventDb.get(ctx).add("occoord", ctx.packageName, "Stream event serve tidak terbuka — izin tool tidak bisa dipantau sesi ini") } catch (e: Exception) {}
                return@Thread
            }
            handle.conn = stream.first
            val reader = stream.second
            try {
                while (!watcherStop) {
                    val line = reader.readLine() ?: break
                    // Slice 12 (vc329): parse baris SSE lewat seam Rust.
                    val ev = parsePermEvent(line, sid) ?: continue
                    handlePermission(ctx, base, sid, taskId, ev.first, ev.second, ev.third)
                }
            } catch (e: Exception) {
                // stream ditutup saat tugas selesai — normal
            }
        }
        t.isDaemon = true
        t.start()
        handle.thread = t
        return handle
    }

    /** Slice 12 (vc329): parse SATU baris SSE `data:` jadi event izin
     *  (permId, judul, detail) — dipakai P15 untuk uji paritas.
     *  Null = bukan event izin ATAU seam gagal (pemanggil fallback). */
    private fun parsePermEvent(line: String, sid: String): Triple<String, String, String>? {
        val r = OcCoordSeam.parsePermEvent(line, sid)
        if (r != null) return r
        return parsePermEventKotlin(line, sid)
    }

    /** Slice 12 (vc329): parse event izin SSE (salinan asli) —
     *  dipakai bila seam Rust tak-tersedia. */
    private fun parsePermEventKotlin(line: String, sid: String): Triple<String, String, String>? {
        if (!line.startsWith("data:")) return null
        val payload = line.substring(5).trim()
        if (payload.isEmpty()) return null
        val obj = try { JSONObject(payload) } catch (e: Exception) { return null }
        val type = obj.optString("type")
        if (!type.contains("permission")) return null
        val props = obj.optJSONObject("properties") ?: return null
        if (props.optString("sessionID") != sid) return null
        val permId = props.optString("id")
        if (permId.isBlank()) return null
        var title = props.optString("title")
        if (title.isBlank()) title = props.optString("pattern")
        if (title.isBlank()) title = "izin tool OpenCode"
        // vc260 K-02/K-12: ringkasan argumen izin dari SEMUA field event yang ada (tool/pola/perintah/args + objek permission bersarang) — dipakai jejak audit + notifikasi, supaya user melihat APA yang disetujui, bukan cuma judul.
        val nestedPermVc260 = props.optJSONObject("permission")?.toString().orEmpty()
        val permDetailVc260 = listOf(
            "tool=" + props.optString("tool"),
            "pattern=" + props.optString("pattern"),
            "command=" + props.optString("command"),
            "args=" + props.opt("args")?.toString().orEmpty(),
            "permission=" + nestedPermVc260
        ).filter { it.substringAfter("=").isNotBlank() }.joinToString(" | ").take(2000)
        return Triple(permId, title.take(300), permDetailVc260)
    }

    private fun stopWatcher(h: WatcherHandle) {
        watcherStop = true
        try { h.conn?.disconnect() } catch (e: Exception) {}
    }

    /** Slice 12 (vc329): keputusan izin (auto_gate / auto_pattern /
     *  park) — dipakai P15 untuk uji paritas. */
    private fun permDecision(ctx: Context, title: String): String {
        val r = OcCoordSeam.permDecision(ToolGate.GATES_DISABLED, rule(ctx, "auto_approve"), title)
        if (r != null) return r
        return permDecisionKotlin(ctx, title)
    }

    /** Slice 12 (vc329): keputusan izin (salinan asli) — dipakai bila
     *  seam Rust tak-tersedia. */
    private fun permDecisionKotlin(ctx: Context, title: String): String {
        // vc247: gate dimatikan — izin koordinator langsung disetujui
        // tanpa parkir notifikasi / tunggu keputusan user.
        if (ToolGate.GATES_DISABLED) return "auto_gate"
        val patterns = rule(ctx, "auto_approve").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (patterns.any { it == "*" || title.contains(it, ignoreCase = true) }) return "auto_pattern"
        return "park"
    }

    /** Slice 12 (vc329): basi izin — dipakai P15 untuk uji paritas. */
    private fun permStale(ts: Long, nowMs: Long, ttlMin: Int): Boolean {
        return OcCoordSeam.permStale(ts, nowMs, ttlMin) ?: (ts > 0 && nowMs - ts > ttlMin * 60_000L)
    }

    private fun handlePermission(ctx: Context, base: String, sid: String, taskId: Long, permId: String, title: String, detail: String = "") {
        // Slice 12 (vc329): keputusan izin lewat seam Rust
        // (auto_gate / auto_pattern / park); balas + log + parkir
        // tetap Kotlin.
        when (permDecision(ctx, title)) {
            "auto_gate" -> {
                val ok = OcClient.replyPermission(base, sid, permId, "once")
                // vc260 K-02: VISIBILITAS — tiap izin yang di-auto-approve tercatat (judul + ringkasan argumen + waktu otomatis dari EventDb). Auto-approve TETAP, tidak ada gate baru.
                EventDb.get(ctx).add("occoord", ctx.packageName, "Izin AUTO-DISETUJUI (gate vc247 nonaktif): $title" + (if (detail.isNotBlank()) " | $detail" else "") + " (terkirim: $ok)")
                return
            }
            "auto_pattern" -> {
                val ok = OcClient.replyPermission(base, sid, permId, "once")
                EventDb.get(ctx).add("occoord", ctx.packageName, "Izin AUTO-DISETUJUI (pola aturan): $title" + (if (detail.isNotBlank()) " | $detail" else "") + " (terkirim: $ok)")
                return
            }
            else -> { /* parkir di bawah */ }
        }
        // Parkir: tunggu keputusan user (notifikasi / Panel Koordinator).
        val p = prefs(ctx)
        val pending = JSONObject()
            .put("permId", permId).put("sid", sid).put("base", base)
            .put("taskId", taskId).put("title", title).put("ts", System.currentTimeMillis())
        permWaiterActive = true
        p.edit().putString("ocPending", pending.toString())
            .putString("ocPendingDecision", "")
            .putString("ocPendingTitle", title).apply()
        notifyApproval(ctx, title, detail)
        EventDb.get(ctx).add("occoord", ctx.packageName, "Izin DIPARKIR menunggu user: $title")
        val deadline = System.currentTimeMillis() + ruleInt(ctx, "perm_timeout_min", 30) * 60_000L
        var decision = ""
        while (System.currentTimeMillis() < deadline) {
            decision = prefs(ctx).getString("ocPendingDecision", "") ?: ""
            if (decision.isNotEmpty()) break
            if (watcherStop) { decision = "reject"; break }
            // vc265 N2-m7: izin yang diparkir ditolak bila F1 dimatikan
            // selama menunggu keputusan.
            if (f1Off(ctx)) { decision = "reject"; break }
            Thread.sleep(2000)
        }
        val response = if (decision == "approve") "once" else "reject"
        val ok = OcClient.replyPermission(base, sid, permId, response)
        EventDb.get(ctx).add("occoord", ctx.packageName, "Izin '$title' dijawab $response oleh ${if (decision == "approve") "user" else if (decision == "reject") "user" else "timeout"} (terkirim: $ok)")
        p.edit().remove("ocPending").remove("ocPendingTitle").remove("ocPendingDecision").apply()
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(APPROVAL_NOTIF_ID)
        } catch (e: Exception) {}
        permWaiterActive = false
    }

    /** Dipanggil OcApprovalReceiver + Panel Koordinator (UiBridge). */
    fun resolvePermission(ctx: Context, approved: Boolean) {
        val app = ctx.applicationContext
        val p = prefs(app)
        val raw = p.getString("ocPending", "") ?: ""
        if (raw.isBlank()) return
        if (permWaiterActive) {
            p.edit().putString("ocPendingDecision", if (approved) "approve" else "reject").apply()
            return
        }
        // vc242 K-12 (opsi minimal): jalur "proses sempat mati, balas
        // langsung ke serve" kini memakai batas staleness yang SAMA dengan
        // waiter (perm_timeout_min, default 30 menit). Izin basi TIDAK
        // dibalas ke serve — ocPending dibatalkan + dicatat.
        try {
            val o = JSONObject(raw)
            val ts = o.optLong("ts", 0)
            val ttlMin = ruleInt(app, "perm_timeout_min", 30)
            if (permStale(ts, System.currentTimeMillis(), ttlMin)) {
                p.edit().remove("ocPending").remove("ocPendingTitle").remove("ocPendingDecision").apply()
                try { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(APPROVAL_NOTIF_ID) } catch (e: Exception) {}
                EventDb.get(app).add("occoord", app.packageName, "Izin koordinator BASI dibatalkan (melewati perm_timeout_min) — tidak dibalas ke serve: ${o.optString("title")}")
                return
            }
        } catch (e: Exception) {}
        // Mesin tidak sedang menunggu (proses sempat mati) — balas langsung ke serve.
        try {
            val o = JSONObject(raw)
            OcClient.replyPermission(o.optString("base"), o.optString("sid"), o.optString("permId"), if (approved) "once" else "reject")
        } catch (e: Exception) {}
        p.edit().remove("ocPending").remove("ocPendingTitle").remove("ocPendingDecision").apply()
        try {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(APPROVAL_NOTIF_ID)
        } catch (e: Exception) {}
    }

    // vc242 K-12: sapu ocPending basi tanpa ketukan user pun (dipanggil
    // tiap tick service). Waiter yang sedang aktif tidak disentuh — ia
    // sudah punya deadline sendiri yang menghapus ocPending saat habis.
    fun sweepStalePending(ctx: Context) {
        try {
            if (permWaiterActive) return
            val p = prefs(ctx)
            val raw = p.getString("ocPending", "") ?: ""
            if (raw.isBlank()) return
            val o = JSONObject(raw)
            val ts = o.optLong("ts", 0)
            val ttlMin = ruleInt(ctx, "perm_timeout_min", 30)
            if (permStale(ts, System.currentTimeMillis(), ttlMin)) {
                p.edit().remove("ocPending").remove("ocPendingTitle").remove("ocPendingDecision").apply()
                try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(APPROVAL_NOTIF_ID) } catch (e: Exception) {}
                EventDb.get(ctx).add("occoord", ctx.packageName, "Izin koordinator basi disapu (melewati perm_timeout_min) — tidak dibalas ke serve: ${o.optString("title")}")
            }
        } catch (e: Exception) {}
    }

    // ---------------- Mode mandor: hasil penuh + tunggu tugas ----------------

    /** Hasil PENUH satu tugas; bila masih jalan, sertakan intipan progres agen. */
    /** J5: terima (buang snapshot) / urungkan (pulihkan file) checkpoint tugas. */
    fun checkpointAction(ctx: Context, id: Long, action: String): String {
        val p = prefs(ctx)
        val out = when (action) {
            "accept" -> if (Checkpoint.discard(ctx, id)) "Checkpoint tugas #$id DITERIMA — perubahan file dipertahankan, snapshot dibuang." else "Tidak ada checkpoint aktif untuk tugas #$id."
            "undo" -> Checkpoint.restore(ctx, id)
            else -> "Aksi checkpoint tidak dikenal: $action (pakai accept/undo)."
        }
        if (p.getLongSafe("ckptPending", 0) == id) p.edit().remove("ckptPending").remove("ckptDiff").apply()
        try { EventDb.get(ctx).add("occoord", ctx.packageName, "Checkpoint #$id $action -> ${out.take(120)}") } catch (e: Exception) {}
        return out
    }

    fun taskResultText(ctx: Context, id: Long): String {
        val db = mdb(ctx)
        val t = db.ocTask(id) ?: return "Tugas #$id tidak ditemukan."
        // Slice 12 (vc329): intipan progres hanya bila tugas masih
        // berjalan (hindari probe network yang tidak perlu).
        val peek = if (t.status !in listOf("done", "failed", "cancelled") && t.sessionId.isNotBlank()) peekSessionText(t.sessionId) else ""
        val taskJson = JSONObject()
            .put("status", t.status).put("text", t.text).put("result", t.result).put("sessionId", t.sessionId)
        return OcCoordSeam.taskResultText(id, taskJson, peek) ?: taskResultTextKotlin(t, peek)
    }

    /** Slice 12 (vc329): teks hasil tugas (salinan asli) — dipakai
     *  bila seam Rust tak-tersedia. */
    private fun taskResultTextKotlin(t: MemoryDb.OcTask, peek: String): String {
        val head = "Tugas #${t.id} [${t.status}] — ${t.text.take(120)}"
        return when (t.status) {
            "done" -> "$head\nHASIL:\n${t.result}"
            "failed" -> "$head\nGAGAL: ${t.result}"
            "cancelled" -> "$head\nDibatalkan. Catatan: ${t.result}"
            else -> {
                val sb = StringBuilder("$head\nMasih ${t.status}.")
                if (peek.isNotEmpty()) sb.append("\nProgres terakhir agen: ").append(peek)
                sb.toString()
            }
        }
    }

    /** Intip teks asisten terakhir di sesi serve tugas (progres langsung). */
    private fun peekSessionText(sid: String): String {
        val base = listOf(4097, 4096, 4100).firstOrNull { OcClient.probe(it) }?.let { OcClient.base(it) } ?: return ""
        return try {
            val msgs = OcClient.messages(base, sid)
            OcCoordSeam.peekText(msgs) ?: peekSessionTextKotlin(msgs)
        } catch (e: Exception) { "" }
    }

    /** Slice 12 (vc329): intip teks asisten terakhir (salinan asli)
     *  — dipakai bila seam Rust tak-tersedia. */
    private fun peekSessionTextKotlin(msgs: JSONArray): String {
        var last = ""
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            if (m.optJSONObject("info")?.optString("role") != "assistant") continue
            val parts = m.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.optString("type") == "text") part.optString("text").trim().takeIf { it.isNotEmpty() }?.let { last = it }
            }
        }
        return last.take(700)
    }

    /**
     * TUNGGU tugas sampai selesai/gagal/dibatalkan (maks timeoutSec), lalu
     * kembalikan HASILNYA — perilaku mandor: mantau koordinator sampai beres,
     * bukan berhenti di laporan status (permintaan user, v3.3.3).
     */
    fun waitForTaskText(ctx: Context, id: Long, timeoutSec: Int): String {
        val cap = timeoutSec.coerceIn(15, 600)
        val db = mdb(ctx)
        db.ocTask(id) ?: return "Tugas #$id tidak ditemukan."
        kick(ctx)
        val start = System.currentTimeMillis()
        while ((System.currentTimeMillis() - start) / 1000 < cap) {
            val t = db.ocTask(id) ?: return "Tugas #$id hilang."
            if (t.status == "done" || t.status == "failed" || t.status == "cancelled") return taskResultText(ctx, id)
            Thread.sleep(8000)
        }
        return taskResultText(ctx, id) + "\n(Catatan mandor: sudah ditunggu $cap dtk, belum selesai — panggil delegate_wait lagi untuk lanjut memantau, atau sampaikan progres ini ke user.)"
    }

    /** Tombol Stop di panel: hentikan tugas berjalan + tolak izin yang diparkir. */
    fun stopCurrent(ctx: Context) {
        val app = ctx.applicationContext
        val cur = prefs(app).getLongSafe("ocCurrentTask", 0)
        if (cur > 0) prefs(app).edit().putLong("ocCancelTask", cur).apply()
        if (permWaiterActive) prefs(app).edit().putString("ocPendingDecision", "reject").apply()
        EventDb.get(app).add("occoord", app.packageName, "Stop diminta user dari panel (tugas #$cur)")
    }

    /** Pending izin saat ini (untuk state UI React): judulnya, atau "" bila tidak ada. */
    fun pendingTitle(ctx: Context): String = prefs(ctx).getString("ocPendingTitle", "") ?: ""

    // ---------------- Jalur serve ----------------

    /** Kembalikan (baseUrl, catatan). base null = tidak ada jalur hidup. */
    fun ensureServe(ctx: Context): Pair<String?, String> {
        val mode = rule(ctx, "path_mode")
        // Slice 12 (vc329): pohon keputusan dua fase lewat seam Rust;
        // probe/start/Termux tetap Kotlin. Fallback = ensureServe asli
        // bila seam tak-tersedia di fase mana pun.
        val s0 = OcCoordSeam.serveSelect(0, mode, OcClient.probe(4100), OcClient.probe(4097), OcClient.probe(4096))
        if (s0 != null) {
            when (s0.action) {
                "return" -> return Pair(s0.basePort?.let { OcClient.base(it) }, s0.note)
                "start_4099" -> { startServeViaTermux(ctx, 4100); Thread.sleep(6000) }
                "start_4097" -> { startServeViaTermux(ctx, 4097); Thread.sleep(6000) }
                else -> return ensureServeKotlin(ctx)
            }
            val s1 = OcCoordSeam.serveSelect(1, mode, OcClient.probe(4100), OcClient.probe(4097), OcClient.probe(4096))
            if (s1 != null) {
                return when (s1.action) {
                    "return" -> Pair(s1.basePort?.let { OcClient.base(it) }, s1.note)
                    "fail" -> Pair(null, s1.note)
                    else -> ensureServeKotlin(ctx)
                }
            }
            return ensureServeKotlin(ctx)
        }
        return ensureServeKotlin(ctx)
    }

    /** Slice 12 (vc329): ensureServe asli (dua fase) — dipakai bila
     *  seam Rust tak-tersedia. */
    private fun ensureServeKotlin(ctx: Context): Pair<String?, String> {
        val mode = rule(ctx, "path_mode")
        if (mode == "mandiri") {
            if (OcClient.probe(4100)) return Pair(OcClient.base(4100), "")
            startServeViaTermux(ctx, 4100)
            Thread.sleep(6000)
            if (OcClient.probe(4100)) return Pair(OcClient.base(4100), "(Serve mandiri 4100 dinyalakan harness.)")
            for (port in listOf(4097, 4096)) {
                if (OcClient.probe(port)) return Pair(OcClient.base(port), "(Serve mandiri gagal — fallback jalur resmi $port.)")
            }
            return Pair(null, "serve mandiri 4100 gagal dinyalakan dan jalur resmi (4097/4096) mati semua.")
        }
        for (port in listOf(4097, 4096)) {
            if (OcClient.probe(port)) return Pair(OcClient.base(port), "")
        }
        startServeViaTermux(ctx, 4097)
        Thread.sleep(6000)
        for (port in listOf(4097, 4096)) {
            if (OcClient.probe(port)) return Pair(OcClient.base(port), "(Serve $port dinyalakan harness via Termux.)")
        }
        return Pair(null, "tidak ada opencode serve yang hidup (4097/4096 mati; upaya menyalakan via Termux gagal). Nyalakan dari Setup tombol 34 atau buka Termux.")
    }

    private fun startServeViaTermux(ctx: Context, port: Int) {
        val cmd = if (port == 4100) {
            "mkdir -p ~/harness-pure-oc && cd ~/harness-pure-oc && (curl -s -m 2 http://127.0.0.1:4100/session >/dev/null 2>&1 || (nohup opencode serve --hostname 127.0.0.1 --port 4100 >> ~/harness-pure-oc/serve.log 2>&1 &)) ; echo START-DIKIRIM"
        } else {
            "cd ~/serve-cwd && (curl -s -m 2 http://127.0.0.1:4097/session >/dev/null 2>&1 || (nohup opencode serve --hostname 127.0.0.1 --port 4097 >> ~/opencode-serve-4097.log 2>&1 &)) ; echo START-DIKIRIM"
        }
        try { Tools.runTerminal(ctx, cmd, "termux") } catch (e: Exception) {}
    }

    /** Setup 34: pasang boot script serve mandiri + nyalakan sekarang. */
    fun installBootAndStart(ctx: Context): String {
        val script = """
            #!/data/data/com.termux/files/usr/bin/sh
            # Serve OpenCode MILIK Harness (port 4100, dir ~/harness-pure-oc)
            # Dipasang oleh Harness v3.3.0 (Tahap D) — pola guard sama seperti serve 4097.
            termux-wake-lock
            pgrep -af 'opencode serve.*--port 409[9]' >/dev/null 2>&1 || {
              mkdir -p ~/harness-pure-oc
              cd ~/harness-pure-oc || exit 1
              while true; do
                opencode serve --hostname 127.0.0.1 --port 4100 >> ~/harness-pure-oc/serve.log 2>&1
                sleep 5
              done
            }
        """.trimIndent() + "\n"
        val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val install = "mkdir -p ~/.termux/boot && printf %s '$b64' | base64 -d > ~/.termux/boot/44-harness-pure-oc.sh && chmod +x ~/.termux/boot/44-harness-pure-oc.sh && echo BOOT-OK"
        val r = try { Tools.runTerminal(ctx, install, "termux") } catch (e: Exception) { "gagal: ${e.message}" }
        startServeViaTermux(ctx, 4100)
        Thread.sleep(6000)
        val up = OcClient.probe(4100)
        return "Boot script 44-harness-pure-oc.sh: ${if (r.contains("BOOT-OK")) "TERPASANG" else "cek manual ($r)"}. " +
            "Serve mandiri 4100: ${if (up) "HIDUP ✅" else "belum merespons — tunggu beberapa detik lalu cek lagi (Setup 33)."}" +
            "\nAktifkan sebagai jalur utama lewat aturan path_mode=mandiri (tool coord_rule / chat: 'jadikan jalur koordinator mandiri')."
    }

    // ---------------- Status / panel ----------------

    fun probeCached(ctx: Context, force: Boolean): Map<Int, Boolean> {
        val p = prefs(ctx)
        val ts = p.getLong("ocProbeTs", 0)
        val raw = p.getString("ocProbeCache", "") ?: ""
        val now = System.currentTimeMillis()
        // Slice 12 (vc329): baca cache lewat seam Rust; bila seam
        // tak-tersedia, baca cache seperti aslinya (tanpa probe baru).
        val cached = OcCoordSeam.probeCacheRead(force, now, ts, raw)
        if (cached != null) {
            if (cached.hit) return cached.probes ?: emptyMap()
        } else {
            val fromCache = probeCacheReadKotlin(force, now, ts, raw)
            if (fromCache != null) return fromCache
        }
        val res = mapOf(4097 to OcClient.probe(4097), 4096 to OcClient.probe(4096), 4100 to OcClient.probe(4100))
        val cacheStr = OcCoordSeam.probeCacheWrite(res[4097] == true, res[4096] == true, res[4100] == true)
            ?: res.entries.joinToString(",") { "${it.key}=${if (it.value) 1 else 0}" }
        p.edit().putString("ocProbeCache", cacheStr)
            .putLong("ocProbeTs", System.currentTimeMillis()).apply()
        return res
    }

    /** Slice 12 (vc329): baca cache probe (salinan asli) — dipakai
     *  bila seam Rust tak-tersedia. */
    private fun probeCacheReadKotlin(force: Boolean, now: Long, ts: Long, raw: String): Map<Int, Boolean>? {
        if (!force && now - ts < 20_000) {
            if (raw.isNotBlank()) {
                return raw.split(",").mapNotNull {
                    val kv = it.split("=")
                    if (kv.size == 2) kv[0].toIntOrNull()?.let { k -> k to (kv[1] == "1") } else null
                }.toMap()
            }
        }
        return null
    }

    fun statusText(ctx: Context, forceProbe: Boolean = false): String {
        val db = mdb(ctx)
        val probes = probeCached(ctx, forceProbe)
        val cur = prefs(ctx).getLongSafe("ocCurrentTask", 0)
        val pend = pendingTitle(ctx)
        val rules = db.allCoordRules()
        val rulesJson = JSONObject()
        for ((k, v) in rules) rulesJson.put(k, v)
        val recent = db.recentOcTasks(5)
        val recentJson = JSONArray()
        for (t in recent) {
            recentJson.put(JSONObject().put("id", t.id).put("status", t.status).put("text", t.text).put("result", t.result))
        }
        // Slice 12 (vc329): perakitan teks status lewat seam Rust;
        // pembacaan DB/prefs/probe tetap Kotlin.
        val r = OcCoordSeam.statusText(
            rule(ctx, "path_mode"), probes[4097] == true, probes[4096] == true, probes[4100] == true,
            cur, db.countOcTasks("queued").toLong(), db.countOcTasks("running").toLong(), pend,
            rulesJson, ToolGate.GATES_DISABLED, recentJson
        )
        if (r != null) return r
        return statusTextKotlin(ctx, forceProbe)
    }

    /** Slice 12 (vc329): teks status (salinan asli) — dipakai bila
     *  seam Rust tak-tersedia. */
    private fun statusTextKotlin(ctx: Context, forceProbe: Boolean = false): String {
        val db = mdb(ctx)
        val probes = probeCached(ctx, forceProbe)
        fun st(b: Boolean) = if (b) "HIDUP" else "MATI"
        val sb = StringBuilder()
        sb.append("Jalur mode: ${rule(ctx, "path_mode")} • 4097 (resmi): ${st(probes[4097] == true)} • 4096 (cadangan): ${st(probes[4096] == true)} • 4100 (mandiri): ${st(probes[4100] == true)}\n")
        val cur = prefs(ctx).getLongSafe("ocCurrentTask", 0)
        sb.append("Antrean: ${db.countOcTasks("queued")} menunggu • ${db.countOcTasks("running")} berjalan")
        if (cur > 0) sb.append(" (tugas #$cur)")
        sb.append("\n")
        val pend = pendingTitle(ctx)
        if (pend.isNotBlank()) sb.append("⏳ IZIN MENUNGGU KEPUTUSAN: $pend — setujui/tolak dari notifikasi atau tombol di panel ini.\n")
        val rules = db.allCoordRules()
        sb.append("Aturan: batas konteks sesi ${rules["session_ctx_limit"]} token • rantai maks ${rules["chain_max"]} tugas • baterai min rantai ${rules["chain_batt_min"]}% • jam hening '${rules["quiet_hours"]}' • auto-approve '${rules["auto_approve"]}'\n")
        // vc260 K-02: satu baris jujur — auto-approve koordinator terlihat di panel (gate nonaktif, tiap izin tercatat di jejak audit EventDb occoord).
        if (ToolGate.GATES_DISABLED) sb.append("Izin koordinator disetujui otomatis (gate nonaktif) — tiap izin tercatat di jejak audit.\n")
        val recent = db.recentOcTasks(5)
        if (recent.isNotEmpty()) {
            sb.append("Tugas terakhir:\n")
            for (t in recent) {
                sb.append("- #${t.id} [${t.status}] ${t.text.take(60)}")
                if (t.result.isNotBlank()) sb.append(" → ${t.result.replace("\n", " ").take(90)}")
                sb.append("\n")
            }
        }
        return sb.toString().trim()
    }

    // ---------------- Util ----------------

    private fun batteryPct(ctx: Context): Int {
        return try {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 100
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            // Slice 12 (vc329): rumus persentase lewat seam Rust.
            OcCoordSeam.batteryPct(level, scale) ?: batteryPctKotlin(level, scale)
        } catch (e: Exception) { 100 }
    }

    /** Slice 12 (vc329): rumus persentase baterai (salinan asli) —
     *  dipakai bila seam Rust tak-tersedia. */
    private fun batteryPctKotlin(level: Int, scale: Int): Int =
        if (level >= 0 && scale > 0) level * 100 / scale else 100

    private fun isQuietNow(spec: String): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        // Slice 12 (vc329): parse spek jam hening lewat seam Rust.
        return OcCoordSeam.quietNow(spec, hour) ?: isQuietNowKotlin(spec, hour)
    }

    /** Slice 12 (vc329): parse spek jam hening (salinan asli) —
     *  dipakai bila seam Rust tak-tersedia. */
    private fun isQuietNowKotlin(spec: String, hour: Int): Boolean {
        val s = spec.trim()
        if (s.isEmpty()) return false
        val parts = s.split("-")
        if (parts.size != 2) return false
        val start = parts[0].trim().toIntOrNull() ?: return false
        val end = parts[1].trim().toIntOrNull() ?: return false
        return if (start <= end) hour in start until end else (hour >= start || hour < end)
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel("harness-alert") == null) {
                    nm.createNotificationChannel(NotificationChannel("harness-alert", "Harness — Peringatan & Hasil", NotificationManager.IMPORTANCE_DEFAULT))
                }
            } catch (e: Exception) {}
        }
    }

    private fun contentIntent(ctx: Context, req: Int): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(ctx, req, intent, flags)
    }

    private fun notify(ctx: Context, title: String, body: String, id: Int) {
        try {
            ensureChannel(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val b = if (Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(ctx, "harness-alert")
            } else {
                @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
            }
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body.take(120))
                .setStyle(android.app.Notification.BigTextStyle().bigText(body.take(900)))
                .setContentIntent(contentIntent(ctx, id))
                .setAutoCancel(true)
            nm.notify(id, b.build())
        } catch (e: Exception) {}
    }

    // vc260 K-12: notifikasi izin WAJIB menampilkan argumen izin secara penuh (detail dari event serve, maks 4000 char di BigText) — user melihat APA yang disetujui, bukan cuma judul dipotong.
    // vc262 NT-m1: notifikasi hasil koordinator kini membawa deep-link
    // (id tugas + sesi asal) — ketuk notif membuka sesi asal di Chat,
    // bukan sekadar beranda. Ditangani handleIncomingIntent.
    private fun notifyTask(ctx: Context, task: MemoryDb.OcTask, title: String, body: String) {
        try {
            ensureChannel(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("ocTaskId", task.id)
                putExtra("ocOriginSession", task.originSession)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getActivity(ctx, resultNotifIdFor(task.id), intent, flags)
            val b = if (Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(ctx, "harness-alert")
            } else {
                @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
            }
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body.take(120))
                .setStyle(android.app.Notification.BigTextStyle().bigText(body.take(900)))
                .setContentIntent(pi)
                .setAutoCancel(true)
            nm.notify(resultNotifIdFor(task.id), b.build())
        } catch (e: Exception) {}
    }

    private fun notifyApproval(ctx: Context, title: String, detail: String = "") {
        try {
            ensureChannel(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
            val approveIntent = Intent(OcApprovalReceiver.ACTION_APPROVE).setPackage(ctx.packageName)
            val rejectIntent = Intent(OcApprovalReceiver.ACTION_REJECT).setPackage(ctx.packageName)
            val b = if (Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(ctx, "harness-alert")
            } else {
                @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
            }
            b.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("🛡️ Koordinator OpenCode minta izin")
                .setContentText(title.take(120))
                .setStyle(android.app.Notification.BigTextStyle().bigText((title + (if (detail.isNotBlank()) "\nArgumen: $detail" else "")).take(4000)))
                .setContentIntent(contentIntent(ctx, APPROVAL_NOTIF_ID))
                .addAction(android.R.drawable.checkbox_on_background, "Setujui", PendingIntent.getBroadcast(ctx, 7713, approveIntent, flags))
                .addAction(android.R.drawable.checkbox_off_background, "Tolak", PendingIntent.getBroadcast(ctx, 7714, rejectIntent, flags))
                .setAutoCancel(false)
            nm.notify(APPROVAL_NOTIF_ID, b.build())
        } catch (e: Exception) {}
    }
}
