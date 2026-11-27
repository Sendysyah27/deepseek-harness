package com.senzwh.deepharness

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** v4.8.0 — TIM AGEN (pola ruang kerja, meniru cara Operator membagi kerja ke
 *  banyak ruang chat): orkestrator (agen utama di chat) memanggil tool
 *  run_team; tiap sub-tugas jalan PARALEL di "ruang" sendiri — helper
 *  Models API, profil agen sendiri. Status tiap ruang ditulis ke papan
 *  misi (prefs teamBoard) untuk Command Center.
 *  Setelah semua ruang selesai: satu putaran VERIFIKASI SILANG oleh profil
 *  pemeriksa (Mentor) atas hasil gabungan. Tool BERISIKO di dalam ruang
 *  TIDAK dieksekusi — gerbang persetujuan tetap satu pintu di alur utama. */
object TeamRun {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)

    data class Lane(val agentId: String, val agentName: String, val slot: Int, val task: String)

    // vc244 GELOMBANG 3 (XL-Memori): blok konteks tim ber-query +
    // ber-cap (skill/fakta/goals dari teks tugas), seperti solo/1-1.
    // Graph TIDAK ikut: tim tidak punya scope room, dan fallback
    // graph global akan menyesatkan (lihat FT-M4).
    private fun memoryBlockForTask(app: Context, query: String): String {
        return try {
            val mdb = MemoryDb.get(app)
            val sb = StringBuilder()
            val sk = try { RedactSecrets.redact(mdb.skillsText(query)) } catch (e: Exception) { "" }
            if (sk.isNotBlank()) sb.append("SKILL AKTIF (prosedur yang dibuat user — ikuti bila relevan):\n").append(sk).append("\n\n")
            val pinnedRawTeamVc263 = try { mdb.pinnedFactsText() } catch (e: Exception) { "" }
            val pinnedTeamVc248 = try { RedactSecrets.redact(pinnedRawTeamVc263) } catch (e: Exception) { "" }
            if (pinnedTeamVc248.isNotBlank()) sb.append("[FAKTA PIN — selalu berlaku, tidak dibuang diam-diam]:\n").append(pinnedTeamVc248).append("\n\n")
            val fa = try { RedactSecrets.redact(mdb.dedupFactsVsPinned(mdb.factsText(query), pinnedRawTeamVc263)) } catch (e: Exception) { "" }
            if (fa.isNotBlank()) sb.append("MEMORI PERSISTEN (fakta tentang user):\n").append(fa).append("\n\n")
            val go = try { RedactSecrets.redact(mdb.goalsText(query)) } catch (e: Exception) { "" }
            if (go.isNotBlank()) sb.append("TUJUAN AKTIF:\n").append(go).append("\n\n")
            if (sb.isEmpty()) "" else "Konteks memori user (fakta PIN selalu penuh + fakta/goals/skill ber-query, ber-cap):\n" + sb.toString()
        } catch (e: Exception) { "" }
    }

    fun run(ctx: Context, goal: String, lanes: List<Lane>): String {
        if (lanes.isEmpty()) return "run_team: daftar tugas kosong."
        if (lanes.size > 4) return "run_team: maks 4 ruang paralel."
        val app = ctx.applicationContext
        // vc262 N-M10: kill switch F1 kini menjangkau lane tim — cek di
        // pintu masuk run(), sebelum kuota/baterai/lane jalan.
        if (prefs(app).getBoolean("f1Off", false)) return "run_team DIBATALKAN: kill switch F1 dimatikan user — aktifkan lagi F1 lewat Profil > Kill Switch F1, lalu kirim ulang tugas tim."
        // vc262 XL-Kuota + F3 koordinator: gate kuota engine-aware (K4) —
        // counter per-provider bucket team. Provider belum siap → jangan
        // tolak di sini, helper menolak jujur saat lane jalan.
        try {
            val provTeamVcF3 = LlmEngineChat.resolveProvider(app)
            Governor.refusalModels(app, provTeamVcF3, Governor.MODELS_BUCKET_TEAM)?.let { return "run_team ditunda — $it" }
        } catch (e: Exception) { /* provider belum siap — lanjut */ }
        try {
            val bi = app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val lvl = bi?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bi?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val st = bi?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL
            if (lvl in 0..19 && !charging) return "run_team ditunda: baterai ${lvl * 100 / maxOf(scale, 1)}% & tidak mengisi — tim paralel boros daya. Isi daya dulu, atau kerjakan dengan satu agen saja."
        } catch (e: Exception) {}
        // K1 (Fase 6): lane jalan tanpa slot — field Lane.slot hanya
        // diteruskan apa adanya (argumen tool + papan misi), bukan gerbang.
        val eff = lanes
        val results = arrayOfNulls<String>(eff.size)
        // VC303-P2-11 (CX-5): R-id tiap tool per lane — dikumpulkan
        // runLane, ditulis ke teamBoard + disisipkan ke string hasil
        // run_team supaya masuk baris WorkNotes 1-1.
        val laneRidsArr = Array(eff.size) { emptyList<String>() }
        writeBoard(app, goal, eff, results, "berjalan")
        val latch = CountDownLatch(eff.size)
        eff.forEachIndexed { i, lane ->
            Thread {
                try { val prVc303 = runLane(app, lane); results[i] = prVc303.first; laneRidsArr[i] = prVc303.second } catch (e: Exception) { results[i] = "Ruang gagal jalan: ${e.message}" }
                writeBoard(app, goal, eff, results, "berjalan", laneRidsArr.toList())
                latch.countDown()
            }.apply { isDaemon = true }.start()
        }
        try { latch.await(7, TimeUnit.MINUTES) } catch (e: Exception) {}
        val done = results.mapIndexed { i, r -> r ?: "Ruang ${eff[i].agentName}: belum selesai dalam batas 7 menit — dicatat belum tuntas." }
        writeBoard(app, goal, eff, done.toTypedArray(), honestBoardStatus(done), laneRidsArr.toList())
        // vc335 R3 (D14): bila SEMUA lane gagal, verify DILEWATI — cukup
        // log ke EventDb, giliran TIDAK diblokir (tidak ada hasil yang
        // layak diverifikasi; memanggil Mentor atas hasil gagal semua
        // hanya membakar kuota + menunda jawaban jujur ke user).
        val verify = if (Vc335R3Pure.allLanesFailedVc335R3(done)) {
            try {
                EventDb.get(app).add("team", app.packageName,
                    "Verifikasi tim dilewati: semua ${done.size} ruang gagal — giliran tidak diblokir")
            } catch (e: Exception) {}
            "dilewati — semua ruang gagal, tidak ada hasil untuk diverifikasi (giliran tidak diblokir)."
        } else {
            verify(app, goal, eff, done)
        }
        val sb = StringBuilder()
        sb.append("HASIL TIM (").append(eff.size).append(" ruang paralel, status papan: ").append(honestBoardStatus(done)).append(") — tujuan: ").append(goal).append("\n")
        eff.forEachIndexed { i, l ->
            sb.append("\n— Ruang ").append(l.agentName).append(": ").append(done[i].take(8000)).append("\n")
            // VC303-P2-11 (CX-5): daftar R-id lane disisipkan di sini —
            // string hasil run_team ini yang masuk SATU baris WorkNotes
            // 1-1, jadi R-id tiap tool lane tetap bisa ditelusur.
            if (laneRidsArr[i].isNotEmpty()) sb.append("[R-id lane ").append(l.agentName).append(": ").append(laneRidsArr[i].joinToString(", ")).append("]\n")
        }
        sb.append("\nVERIFIKASI SILANG (Mentor): ").append(verify.take(8000))
        sb.append("\n\nSintesiskan hasil tim ini menjadi jawaban final untuk user: gabungkan temuan tiap ruang, sebutkan yang belum tuntas apa adanya, jangan mengarang di luar hasil ruang.")
        return sb.toString()
    }

    // VC303-P2-11 (CX-5): mengembalikan Pair(hasil, daftar R-id tool lane)
    // — R-id dikumpulkan dari BatchToolVc274 outcomes (recordOutcome) +
    // jalur tunggal (Tools.lastResultId sesudah execute).
    private fun runLane(app: Context, lane: Lane): Pair<String, List<String>> {
        // K1 (Fase 6): lane jalan via helper Models API (tanpa slot,
        // tanpa klien) — guard slot<0 sudah tidak ada.
        val laneRidsVc303 = ArrayList<String>()
        val role = when (lane.agentId.lowercase()) {
            "mandor" -> "Kamu berperan sebagai Mandor — eksekutor lapangan: kerjakan tugas secara konkret langkah demi langkah, pakai tool yang perlu, laporkan hasil apa adanya termasuk kegagalan."
            "mentor" -> "Kamu berperan sebagai Mentor — pemeriksa kritis: kerjakan tugas dengan teliti, tandai risiko, asumsi, dan ketidakpastian secara eksplisit."
            "agen4" -> "Kamu berperan sebagai Agen 4 — generalis serbaguna: kerjakan sub-tugas secara praktis, riset singkat + eksekusi langsung, laporkan hasil secara ringkas dan jelas."
            else -> "Kamu berperan sebagai Asisten — peneliti & penulis utama: kerjakan tugas dengan rapi, jelas, dan terstruktur."
        }
        val sb = StringBuilder(AgentPrompt.buildSystemPrompt(app))
        // vc334 R4 (G-2): blok memori dihitung SEKALI di awal lane —
        // dulu memoryBlockForTask (4+ query MemoryDb) dipanggil ulang
        // tiap langkah (maks 20 langkah × 4 lane).
        val laneMemoryVc334 = memoryBlockForTask(app, lane.task)
        sb.append(laneMemoryVc334)
        sb.append("\n\n").append(AgentPrompt.CTX_LABEL_RULES)
        sb.append("\n\n[MODE TIM — ruang ").append(lane.agentName).append("] ").append(role)
        sb.append("\nKamu mengerjakan SATU sub-tugas dari tujuan tim yang lebih besar. Fokus hanya pada sub-tugasmu; agen lain mengerjakan bagian lain secara paralel.")
        sb.append("\n[USER — SUB-TUGAS VERBATIM]:\n").append(lane.task).append("\n[/USER]")
        val transcript = StringBuilder("Sub-tugas ruang ${lane.agentName}: ${lane.task}")
        val usedToolsVc249 = ArrayList<String>()
        // vc263 (N-m21): detektor putaran per lane tim.
        val stuckWatchTeamVc263 = AgentPrompt.StuckWatch()
        var prompt = sb.toString()
        // vc277 O15(a): cap tim naik 6 -> 20 (tugas tim memang
        // multi-langkah; tetap di bawah cap 1-1/latar 50).
        val maxSteps = 20
        var steps = 0
        var finalText: String? = null
        // vc277 O17(c): pengunci giliran tim — sekali StuckDetector 🛑,
        // tool berikutnya ditolak tanpa eksekusi dan tanpa memakan langkah.
        var turnLockedVc277 = false
        // vc277 O15(b): berhenti yang jujur saat cap tercapai — sebut
        // langkah, tugas, yang sudah selesai (tool yang jalan) dan yang
        // belum (sub-tugas belum selesai), bukan diam-diam final.
        fun honestCapStopVc277(): String {
            val doneVc277 = usedToolsVc249.distinct().joinToString(", ").ifBlank { "belum ada tool yang dijalankan" }
            return "Ruang " + lane.agentName + " berhenti di tengah pada langkah " + steps + " dari tugas: " + lane.task.take(300) + " — ini yang sudah selesai: " + doneVc277 + "; ini yang belum: sub-tugas belum selesai — sisa langkah tidak dikerjakan dalam lane ini. Catatan terakhir: " + transcript.takeLast(4000)
        }
        // vc274 O6+O7: eksekusi batch multi-tool tim (per lane). Fungsi
        // lokal agar bisa memakai var lane (steps, transcript,
        // stuckWatch) lewat closure. Mengembalikan true bila lane harus
        // BERHENTI sekarang (StuckDetector — finalText sudah diisi).
        // Hasil per call TIDAK ditempel satu-satu ke transkrip — yang
        // ditempel hanya pesan gabungan SATU continuation (seperti 1-1
        // vc273), supaya hasil tidak ganda di prompt berikutnya.
        // Eksekutor menyamai jalur tunggal yang hidup: di bawah
        // GATES_DISABLED (vc247) cabang isRisky mati dan semua tool
        // dieksekusi lewat Tools.execute (hanya dicatat di ledger bila
        // RISKY_TOOLS); run_team tidak pernah masuk batch (useBatch
        // menolaknya) sehingga penolakan run_team jalur tunggal tidak
        // perlu diduplikasi di sini.
        fun execBatchTeamVc274(calls: List<JSONObject>): Boolean {
            val usedTeamVc274 = HashSet<String>()
            val callIdsTeamVc274 = calls.map { BatchToolVc274.nextCallId(it.optString("id"), usedTeamVc274).also { usedTeamVc274.add(it) } }
            val reportTeamVc274 = BatchToolVc274.runBatch(
                calls, callIdsTeamVc274,
                stepsLeft = { maxSteps - steps },
                executeOne = { t, a, c, dep ->
                    BatchToolVc274.runOne(app, t, a, c, dep) { tt, aa, cc ->
                        if (tt in Tools.RISKY_TOOLS) {
                            try { DecisionLedger.add(app, "tim:" + lane.agentName, "flag", "Tool berisiko '" + tt + "' dieksekusi di lane tim (gerbang vc247 nonaktif — cabang penahan isRisky mati; hanya dicatat, tidak diblokir).") } catch (e: Exception) {}
                        }
                        Tools.execute(app, tt, aa, cc)
                    }
                },
                recordOutcome = { oc ->
                    steps++
                    usedToolsVc249.add(oc.tool)
                    // VC303-P2-11 (CX-5): R-id lane (jalur batch).
                    oc.resultId?.takeIf { it.isNotBlank() }?.let { if (it !in laneRidsVc303) laneRidsVc303.add(it) }
                    if (oc.prereqFailed) "" else stuckWatchTeamVc263.observe(oc.tool, oc.args, oc.result)
                }
            )
            transcript.append("\n").append(BatchToolVc274.combinedMessage("tim", reportTeamVc274))
            val stopTeamVc274 = reportTeamVc274.outcomes.firstOrNull { AgentPrompt.StuckWatch.isStop(it.stuckText) }
            if (stopTeamVc274 != null) {
                turnLockedVc277 = true
                finalText = "Ruang " + lane.agentName + " dihentikan: StuckDetector — tool " + stopTeamVc274.tool + " berputar tanpa progres (pola/hasil sama berulang). Catatan terakhir: " + transcript.takeLast(4000)
                return true
            }
            // vc277 O15(b): cap tercapai di tengah batch — berhenti jujur
            // sekarang (bukan menunggu model menjawab final tanpa penjelasan).
            if (steps >= maxSteps) {
                finalText = honestCapStopVc277()
                return true
            }
            return false
        }
        while (finalText == null) {
            // vc262 N-M10: F1 dicek tiap putaran lane — lane berhenti dalam
            // 1 langkah berikutnya bila user mematikan kill switch.
            if (prefs(app).getBoolean("f1Off", false)) { finalText = "Ruang ${lane.agentName} dihentikan: kill switch F1 dimatikan user."; break }
            // F3c (B9): helper sinkron kind TEAM (Governor + TurnMetrics
            // otomatis di helper, jawaban kosong = error). Throw helper
            // (provider belum siap/kuota) → pesan gagal lane yang sudah
            // ada di bawah.
            val resp = try {
                LlmEngineChat.chatSyncForEngine(app, prompt, LlmCallKind.TEAM)
            } catch (e: Exception) { finalText = "Ruang ${lane.agentName} berhenti: ${e.message}"; "" }
            if (finalText != null) break
            val multiTeamVc249 = AgentPrompt.extractToolCalls(resp)
            val (visible, obj) = AgentPrompt.extractToolCall(resp)
            // vc274 O6+O7: >1 tool per respons di lane tim DIEKSEKUSI
            // sebagai batch (BatchToolVc274 — aturan sama seperti 1-1
            // vc273; tiap call = 1 langkah cap 6 tim). Bila batch tidak
            // dipakai (tool jalur-khusus di dalamnya), jatuh ke jalur
            // tunggal di bawah (obj = call pertama).
            if (multiTeamVc249.size > 1 && BatchToolVc274.useBatch(multiTeamVc249)) {
                if (visible.isNotBlank()) transcript.append("\nAsisten: ").append(visible.take(1000))
                if (execBatchTeamVc274(multiTeamVc249)) break
                prompt = continuationPromptVc334(lane, role, transcript)
                continue
            }
            if (obj == null) {
                val rawTeamVc262 = visible.ifBlank { resp.trim() }
                var fTeamVc249 = if (rawTeamVc262.length > 20000) rawTeamVc262.take(20000) + "…(dipotong, jawaban akhir tim melebihi 20000 karakter — sisa tidak ditampilkan)…" else rawTeamVc262
                finalText = fTeamVc249; break
            }
            val tool = obj.optString("tool")
            val args = obj.optJSONObject("args") ?: JSONObject()
            val callIdTeamVc272 = obj.optString("id")
            // vc277 O17(c): giliran terkunci StuckDetector — tolak tanpa
            // eksekusi dan tanpa memakan langkah.
            if (turnLockedVc277) { finalText = "Ruang " + lane.agentName + " dihentikan: giliran dikunci StuckDetector — tool $tool ditolak tanpa dieksekusi (tidak memakan langkah). Catatan terakhir: " + transcript.takeLast(4000); break }
            usedToolsVc249.add(tool)
            steps++
            if (steps > maxSteps) { finalText = honestCapStopVc277(); break }
            if (visible.isNotBlank()) transcript.append("\nAsisten: ").append(visible.take(1000))
            // VC303-P2-11 (CX-5): R-id sebelum eksekusi — Tools.execute
            // membersihkan ThreadLocal R-id di awal; cabang pesan (tool
            // BERISIKO / run_team di dalam lane) tidak mengeksekusi, jadi
            // R-id lama TIDAK boleh dipakai.
            val ridBeforeTeamVc303 = Tools.lastResultId()
            val result = when {
                // vc246 GELOMBANG 5 (TL-M9, pilihan implementasi): sama
                // seperti latar — rencana tim kini ditulis lewat
                // Tools.execute (PlanStore), bukan pesan sukses palsu.
                tool == "make_plan" || tool == "update_plan" -> Tools.execute(app, tool, args, callIdTeamVc272)
                tool == "run_team" -> "run_team tidak boleh dipanggil dari dalam ruang tim."
                Tools.isRisky(tool) -> "Tool $tool BERISIKO — di mode tim tidak dieksekusi; kebutuhan ini dicatat agar dikerjakan alur utama dengan persetujuan user. Selesaikan bagian yang bisa tanpa tool itu."
                else -> {
                    // vc262 H-M3 (visibilitas, BUKAN gate): isRisky() selalu
                    // false sejak vc247, jadi cabang BERISIKO di atas mati
                    // dan tool RISKY_TOOLS ikut dieksekusi di sini — dicatat
                    // di ledger agar terlihat, eksekusi tetap jalan.
                    if (tool in Tools.RISKY_TOOLS) {
                        try { DecisionLedger.add(app, "tim:" + lane.agentName, "flag", "Tool berisiko '$tool' dieksekusi di lane tim (gerbang vc247 nonaktif — cabang penahan isRisky mati; hanya dicatat, tidak diblokir).") } catch (e: Exception) {}
                    }
                    Tools.execute(app, tool, args, callIdTeamVc272)
                }
            }
            // VC303-P2-11 (CX-5): R-id lane (jalur tunggal).
            run {
                val ridAfterTeamVc303 = Tools.lastResultId()
                if (ridAfterTeamVc303 != null && ridAfterTeamVc303 != ridBeforeTeamVc303 && ridAfterTeamVc303 !in laneRidsVc303) laneRidsVc303.add(ridAfterTeamVc303)
            }
            transcript.append("\n[HASIL TOOL] $tool" + (if (callIdTeamVc272.isNotBlank()) " [$callIdTeamVc272]" else "") + ": ").append(result.take(4000))
            // vc263 (N-m21): putaran terdeteksi = suffix ke transkrip
            // (peringatan) atau hentikan lane ini (tanda berhenti).
            val stuckTeamVc263 = stuckWatchTeamVc263.observe(tool, args, result)
            if (stuckTeamVc263.isNotBlank()) {
                transcript.append(stuckTeamVc263)
                if (AgentPrompt.StuckWatch.isStop(stuckTeamVc263)) {
                    turnLockedVc277 = true
                    finalText = "Ruang ${lane.agentName} dihentikan: StuckDetector — tool $tool berputar tanpa progres (pola/hasil sama berulang). Catatan terakhir: " + transcript.takeLast(4000)
                    break
                }
            }
            prompt = continuationPromptVc334(lane, role, transcript)
        }
        var outTeamVc249 = finalText ?: "Ruang ${lane.agentName}: tanpa hasil."
        return outTeamVc249 to laneRidsVc303
    }

    /**
     * vc334 R4 (G-2): prompt LANJUTAN lane = DELTA (pola grup vc321 /
     * 1-1 vc316) — peran + sub-tugas verbatim + ekor transkrip (hasil
     * tool terakhir, maks 4000 char) + instruksi lanjut. Prompt PENUH
     * (system prompt + blok memori + CTX rules) hanya dikirim sekali
     * di awal lane. Dulu rebuildPrompt membangun ulang semuanya tiap
     * langkah (system prompt + 4+ query MemoryDb + transkrip penuh).
     */
    private fun continuationPromptVc334(lane: Lane, role: String, transcript: StringBuilder): String =
        Vc334R4Pure.teamLaneDeltaPrompt(
            lane.agentName, role, lane.task, transcript.toString().takeLast(4000)
        )

    private fun verify(app: Context, goal: String, lanes: List<Lane>, results: List<String>): String {
        // vc335 R3 (D10): verifikasi silang via helper Models API kind
        // REVIEWER — kuotanya dari bucket Governor REVIEWER (bukan TEAM),
        // supaya verifikasi tidak memakan kuota kerja lane tim.
        val sb = StringBuilder(AgentPrompt.buildSystemPrompt(app))
        sb.append(memoryBlockForTask(app, goal))
        sb.append("\n\n").append(AgentPrompt.CTX_LABEL_RULES)
        sb.append("\n\n[MODE TIM — VERIFIKASI SILANG] Kamu Mentor, pemeriksa independen yang TIDAK ikut mengerjakan. Tujuan tim: ").append(goal).append("\nHasil tiap ruang:\n")
        lanes.forEachIndexed { i, l -> sb.append("— ").append(l.agentName).append(": ").append(results[i].take(4000)).append("\n") }
        sb.append("\nNilai dengan tegas & singkat TANPA tool: mana yang konsisten, mana yang bertentangan atau belum terbukti, apa yang masih kurang untuk tujuan tim.")
        // vc335 R3 (D10): helper kind REVIEWER (bucket Governor REVIEWER).
        // Pesan gagal yang sudah ada dipakai ulang.
        return try {
            LlmEngineChat.chatSyncForEngine(app, sb.toString(), LlmCallKind.REVIEWER).take(8000)
        } catch (e: Exception) { "Verifikasi gagal: ${e.message}" }
    }

    // vc246 GELOMBANG 5 (AG-M4): papan tim dibaca Monitor (lihat
    // MainActivity refreshMonitor: seksi HASIL PER LANE TIM) — dulu hasil
    // per lane hanya di prefs teamBoard tanpa tampilan UI.
    fun boardText(app: Context): String {
        return try {
            val root = org.json.JSONObject(prefs(app).getString("teamBoard", "{}") ?: "{}")
            val sb = StringBuilder()
            sb.append("Tujuan: ").append(root.optString("goal")).append(" • Status: ").append(root.optString("status")).append("\n")
            val lanes = root.optJSONArray("lanes") ?: org.json.JSONArray()
            for (i in 0 until lanes.length()) {
                val l = lanes.optJSONObject(i) ?: continue
                // VC303-P2-11 (CX-5): tampilkan R-id lane dari teamBoard.
                val ridsVc303 = l.optJSONArray("rids")
                val ridsTxtVc303 = if (ridsVc303 != null && ridsVc303.length() > 0) " [R-id: " + (0 until ridsVc303.length()).joinToString(", ") { ridsVc303.optString(it) } + "]" else ""
                sb.append("— Lane ").append(l.optString("agent")).append(": ").append(if (l.optBoolean("done", false)) "selesai" else "berjalan").append(" — ").append(l.optString("result").take(300)).append(ridsTxtVc303).append("\n")
            }
            sb.toString()
        } catch (e: Exception) { "" }
    }

    // vc262 K-13: status papan JUJUR — "selesai" hanya bila tidak ada
    // lane yang gagal/sebagian; bila ada, statusnya "sebagian".
    // vc335 R3 (D14): predikat gagal tinggal di Vc335R3Pure
    // (satu definisi untuk papan + gerbang lewati-verify).
    private fun honestBoardStatus(done: List<String>): String {
        val bad = done.any { d -> Vc335R3Pure.isLaneFailedVc335R3(d) }
        return if (bad) "sebagian" else "selesai"
    }

    private fun writeBoard(app: Context, goal: String, lanes: List<Lane>, results: Array<String?>, status: String, rids: List<List<String>>? = null) {
        try {
            val arr = JSONArray()
            lanes.forEachIndexed { i, l ->
                val o = JSONObject()
                o.put("agent", l.agentName)
                o.put("slot", l.slot)
                o.put("task", l.task.take(120))
                o.put("done", results[i] != null)
                o.put("result", (results[i] ?: "").take(220))
                // VC303-P2-11 (CX-5): R-id lane disimpan TERPISAH dari
                // result 220 char (tidak muat di situ) — dibaca Monitor
                // (boardText) + dibawa ke string hasil run_team.
                if (rids != null && i < rids.size && rids[i].isNotEmpty()) {
                    val ra = JSONArray()
                    for (r in rids[i]) ra.put(r)
                    o.put("rids", ra)
                }
                arr.put(o)
            }
            val root = JSONObject()
                .put("goal", goal.take(140))
                .put("status", status)
                .put("ts", System.currentTimeMillis())
                .put("lanes", arr)
            prefs(app).edit().putString("teamBoard", root.toString()).apply()
        } catch (e: Exception) {}
    }
}
