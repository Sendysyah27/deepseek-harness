package com.senzwh.deepharness

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** v4.8.0 — TIM AGEN (pola ruang kerja, meniru cara Operator membagi kerja ke
 *  banyak ruang chat): orkestrator (agen utama di chat) memanggil tool
 *  run_team; tiap sub-tugas jalan PARALEL di "ruang" sendiri — klien
 *  DeepSeek sendiri, slot akun pool sendiri, profil agen sendiri. Status
 *  tiap ruang ditulis ke papan misi (prefs teamBoard) untuk Command Center.
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
        if (lanes.size > 4) return "run_team: maks 4 ruang paralel (1 lane = 1 slot)."
        val app = ctx.applicationContext
        // vc262 N-M10: kill switch F1 kini menjangkau lane tim — cek di
        // pintu masuk run(), sebelum kuota/baterai/lane jalan.
        if (prefs(app).getBoolean("f1Off", false)) return "run_team DIBATALKAN: kill switch F1 dimatikan user — aktifkan lagi F1 lewat Profil > Kill Switch F1, lalu kirim ulang tugas tim."
        // vc262 XL-Kuota: penolakan tim memakai hitungan tim terpisah.
        Governor.refusalTeam(app)?.let { return "run_team ditunda — $it" }
        try {
            val bi = app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val lvl = bi?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bi?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val st = bi?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL
            if (lvl in 0..19 && !charging) return "run_team ditunda: baterai ${lvl * 100 / maxOf(scale, 1)}% & tidak mengisi — tim paralel boros daya. Isi daya dulu, atau kerjakan dengan satu agen saja."
        } catch (e: Exception) {}
        // v4.46.0 F3: 1 lane = 1 slot. Slot 0 = otomatis (slot login pertama
        // yang belum dipakai lane lain); slot tetap yang kosong / sudah
        // dipakai = -1 (lane gagal jelas, tanpa meminjam sesi akun lain).
        val filled = try { AccountPool.filledSlots(app) } catch (e: Exception) { emptyList() }
        val used = HashSet<Int>()
        val eff = lanes.map { lane ->
            val s = when {
                lane.slot in 1..5 && filled.contains(lane.slot) && !used.contains(lane.slot) -> lane.slot
                lane.slot == 0 -> filled.firstOrNull { !used.contains(it) } ?: -1
                else -> -1
            }
            if (s > 0) used.add(s)
            lane.copy(slot = s)
        }
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
        val verify = verify(app, goal, eff, done)
        val sb = StringBuilder()
        sb.append("HASIL TIM (").append(eff.size).append(" ruang paralel, status papan: ").append(honestBoardStatus(done)).append(") — tujuan: ").append(goal).append("\n")
        eff.forEachIndexed { i, l ->
            sb.append("\n— Ruang ").append(l.agentName).append(" (slot ").append(if (l.slot < 0) "tanpa slot" else "Browser " + l.slot).append("): ").append(done[i].take(8000)).append("\n")
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
        if (lane.slot < 0) return "Ruang ${lane.agentName}: tidak ada akun login untuk lane ini (slot yang diminta kosong, atau semua slot login sudah dipakai lane lain) — buka Browser di Setup, login, lalu ulangi." to emptyList()
        val api = makeClient(app, lane.slot) ?: return "Ruang ${lane.agentName}: Browser ${lane.slot} belum login — buka Browser ${lane.slot} di Setup, login, lalu ulangi." to emptyList()
        val laneRidsVc303 = ArrayList<String>()
        val role = when (lane.agentId.lowercase()) {
            "mandor" -> "Kamu berperan sebagai Mandor — eksekutor lapangan: kerjakan tugas secara konkret langkah demi langkah, pakai tool yang perlu, laporkan hasil apa adanya termasuk kegagalan."
            "mentor" -> "Kamu berperan sebagai Mentor — pemeriksa kritis: kerjakan tugas dengan teliti, tandai risiko, asumsi, dan ketidakpastian secara eksplisit."
            "agen4" -> "Kamu berperan sebagai Agen 4 — generalis serbaguna: kerjakan sub-tugas secara praktis, riset singkat + eksekusi langsung, laporkan hasil secara ringkas dan jelas."
            else -> "Kamu berperan sebagai Asisten — peneliti & penulis utama: kerjakan tugas dengan rapi, jelas, dan terstruktur."
        }
        val sb = StringBuilder(AgentPrompt.buildSystemPrompt(app))
        sb.append(memoryBlockForTask(app, lane.task))
        sb.append("\n\n").append(AgentPrompt.CTX_LABEL_RULES)
        sb.append("\n\n[MODE TIM — ruang ").append(lane.agentName).append("] ").append(role)
        sb.append("\nKamu mengerjakan SATU sub-tugas dari tujuan tim yang lebih besar. Fokus hanya pada sub-tugasmu; agen lain mengerjakan bagian lain secara paralel.")
        sb.append("\n[USER — SUB-TUGAS VERBATIM]:\n").append(lane.task).append("\n[/USER]")
        val transcript = StringBuilder("Sub-tugas ruang ${lane.agentName}: ${lane.task}")
        var gateRetriedVc249 = false
        val usedToolsVc249 = ArrayList<String>()
        // vc298 BAGIAN 1 (1B): jejak tool lane TERSTRUKTUR untuk reviewer.
        // Temuan: bukti reviewer lane dulu = transcript mentah (.take
        // 16000 dari kepala) — barisnya "[HASIL TOOL] <tool>: <hasil>"
        // TANPA penanda [ok]/[gagal], tanpa label sumber yang dikenal
        // prompt reviewer, dan hasil tool AKHIR (yang paling relevan
        // dengan klaim jawaban final) terpotong karena transcript
        // kepalanya dipenuhi narasi + hasil awal. Akibatnya aturan
        // JEJAK TOOL vc257 ("<tool> [ok]" pada file yang sama = DIDUKUNG)
        // tidak pernah menyala untuk lane, dan klaim berbukti tool
        // dihapus reviewer. Jejak ini dicatat di TIAP eksekusi tool
        // (jalur tunggal + batch) dengan format yang SAMA seperti jejak
        // 1-1 (vc228FullTrailText): "- <tool> [ok|gagal] <hasil ringkas>".
        val toolTraceVc298 = StringBuilder()
        fun appendTraceVc298(tool: String, ok: Boolean, result: String) {
            toolTraceVc298.append(ReviewerVc298.formatTraceStep(tool, ok, RedactSecrets.redact(result)))
        }
        var modelFinalVc249 = false
        // vc263 (N-m21): detektor putaran per lane tim.
        val stuckWatchTeamVc263 = AgentPrompt.StuckWatch()
        // vc266: FAKTA PIN penuh sekali per lane — dipakai gate bukti, warning, dan filter reviewer.
        val pinnedTeamVc266 = try { MemoryDb.get(app).pinnedFactsText() } catch (e: Exception) { "" }
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
                    // vc298 (1B): tiap outcome batch masuk jejak tool lane.
                    appendTraceVc298(oc.tool, oc.ok, oc.result)
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
            val resp = try { chatSync(app, api, prompt) } catch (e: Exception) { finalText = "Ruang ${lane.agentName} berhenti: ${e.message}"; "" }
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
                prompt = rebuildPrompt(app, lane, transcript)
                continue
            }
            if (obj == null) {
                modelFinalVc249 = true
                val rawTeamVc262 = visible.ifBlank { resp.trim() }
                var fTeamVc249 = if (rawTeamVc262.length > 20000) rawTeamVc262.take(20000) + "…(dipotong, jawaban akhir tim melebihi 20000 karakter — sisa tidak ditampilkan)…" else rawTeamVc262
                // vc266: jawaban yang seluruh klaimnya didukung memori tidak kena gate bukti.
                val allMemTeamVc266 = AgentPrompt.allClaimsSupportedByMemory(AgentPrompt.splitClaims(fTeamVc249), pinnedTeamVc266.lines(), lane.task)
                if (!gateRetriedVc249 && AgentPrompt.hasFactualClaim(fTeamVc249) && usedToolsVc249.isNotEmpty() && !allMemTeamVc266 && !AgentPrompt.evidenceMarkerPresent(fTeamVc249)) {
                    gateRetriedVc249 = true
                    transcript.append("\n[SISTEM] ").append(AgentPrompt.EVIDENCE_GATE_NUDGE)
                    prompt = rebuildPrompt(app, lane, transcript)
                    continue
                }
                if (AgentPrompt.hasFactualClaim(fTeamVc249)) {
                    val warnTeamVc249 = AgentPrompt.claimWarningForCorpus(fTeamVc249, usedToolsVc249, transcript.toString(), pinnedTeamVc266, lane.task)
                    if (warnTeamVc249 != null) fTeamVc249 = "⚠️ Peringatan sistem: klaim ini tidak didukung hasil tool di giliran ini — perbaiki atau nyatakan sebagai dugaan. Detail: $warnTeamVc249\n\n" + fTeamVc249
                }
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
            // vc298 (1B): jalur tunggal ikut masuk jejak tool lane.
            appendTraceVc298(tool, !result.startsWith("Gagal:"), result)
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
            prompt = rebuildPrompt(app, lane, transcript)
        }
        var outTeamVc249 = finalText ?: "Ruang ${lane.agentName}: tanpa hasil."
        // vc249: reviewer konteks-nol tim — hanya klaim + bukti (sub-tugas, jejak tool lane, fakta PIN); reviewer WAJIB slot Browser 5
        // (vc298) — bila lane ini memakai slot 5, verdict dilabeli tidak independen (reviewerIndependenceNote di bawah).
        if (modelFinalVc249 && (AgentPrompt.hasFactualClaim(outTeamVc249) || usedToolsVc249.isNotEmpty())) {
            try {
                val claimsTeamVc249 = AgentPrompt.splitClaims(outTeamVc249)
                if (claimsTeamVc249.isNotEmpty()) {
                    // vc298 BAGIAN 1 (1A): reviewer WAJIB slot Browser 5 —
                    // tanpa fallback ke slot lain.
                    val rSlotTeam = try { AccountPool.reviewerSlot(app) } catch (e: Exception) { null }
                    val rAuthTeam = if (rSlotTeam != null) try { AccountPool.slotAuth(app, rSlotTeam) } catch (e: Exception) { null } else null
                    if (rSlotTeam == null || rAuthTeam == null) {
                        outTeamVc249 = ReviewerVc298.SKIP_NOTE + "\n\n" + outTeamVc249
                    } else if (Governor.overCapReviewer(app)) {
                        // vc277 O16(b): kuota reviewer habis — reviewer
                        // dilewati dengan penanda, bukan diam-diam lolos.
                        outTeamVc249 = Governor.reviewerOverCapText(app) + "\n\n" + outTeamVc249
                    } else {
                        val pinnedTeamR = try { RedactSecrets.redact(MemoryDb.get(app).pinnedFactsText()) } catch (e: Exception) { "" }
                        // vc298 (1B): bukti reviewer lane = JEJAK TOOL LANE
                        // terstruktur (didahulukan — bukan transcript mentah
                        // yang terpotong dari kepala) + jawaban akhir lane
                        // + FAKTA PIN. Format jejak sama seperti 1-1
                        // ("- <tool> [ok|gagal] <hasil>") supaya aturan
                        // JEJAK TOOL vc257 di prompt reviewer menyala.
                        val traceTeamVc298 = toolTraceVc298.toString()
                        val evTeam = ReviewerVc298.buildLaneEvidence(lane.task, traceTeamVc298, outTeamVc249, pinnedTeamR)
                        val rClientTeam = SlotClient(app, rSlotTeam, rAuthTeam.first, rAuthTeam.second)
                        // vc275 O11: verdict tak terparse -> retry 1x format ketat (counter govCountReviewer — chatSync reviewer=true bump reviewer + sesi server TETAP reviewer slot 5 sejak vc298); tetap gagal -> penanda "belum terverifikasi", bukan lolos diam-diam.
                        var unsTeam: List<Int> = emptyList()
                        var concTeam = false
                        var reviewFailTeamVc275 = false
                        try {
                            val (rawTeam, _) = rClientTeam.chatSync(AgentPrompt.zeroCtxReviewPrompt(claimsTeamVc249, evTeam), false, false, reviewer = true)
                            val (u1TeamVc275, c1TeamVc275) = AgentPrompt.parseZeroCtxReview(rawTeam)
                            if (c1TeamVc275) { unsTeam = u1TeamVc275; concTeam = true } else {
                                val (rawTeam2Vc275, _) = rClientTeam.chatSync(AgentPrompt.reviewerRetryPrompt(claimsTeamVc249, evTeam), false, false, reviewer = true)
                                val (u2TeamVc275, c2TeamVc275) = AgentPrompt.parseZeroCtxReview(rawTeam2Vc275)
                                if (c2TeamVc275) { unsTeam = u2TeamVc275; concTeam = true } else reviewFailTeamVc275 = true
                            }
                        } catch (e: Exception) { reviewFailTeamVc275 = true }
                        // vc266: klaim yang didukung memori dikeluarkan dari daftar tak didukung reviewer.
                        val unsTeamFiltVc266 = AgentPrompt.filterUnsupportedByMemory(claimsTeamVc249, unsTeam, pinnedTeamR.lines(), lane.task)
                        // vc275 O11(a): label jujur bila reviewer memakai slot yang sama dengan giliran agen (hanya 1 slot login).
                        val indepTeamVc275 = AgentPrompt.reviewerIndependenceNote(lane.slot, rSlotTeam)
                        if (reviewFailTeamVc275) outTeamVc249 = AgentPrompt.reviewerNotVerifiedNote() + indepTeamVc275 + "\n\n" + outTeamVc249
                        else if (unsTeamFiltVc266.isNotEmpty()) outTeamVc249 = AgentPrompt.reviewerNoteText(claimsTeamVc249, unsTeamFiltVc266) + indepTeamVc275 + "\n\n" + AgentPrompt.applyDoublePass(outTeamVc249, claimsTeamVc249, unsTeamFiltVc266)
                        else if (indepTeamVc275.isNotBlank()) outTeamVc249 = "🔍 Reviewer konteks-nol: verdict diterima" + indepTeamVc275 + ".\n\n" + outTeamVc249
                    }
                }
            } catch (e: Exception) {
                outTeamVc249 = AgentPrompt.reviewerNotVerifiedNote() + "\n\n" + outTeamVc249
            }
        }
        return outTeamVc249 to laneRidsVc303
    }

    private fun rebuildPrompt(app: Context, lane: Lane, transcript: StringBuilder): String {
        val sb = StringBuilder(AgentPrompt.buildSystemPrompt(app))
        sb.append(memoryBlockForTask(app, lane.task))
        sb.append("\n\n").append(AgentPrompt.CTX_LABEL_RULES)
        sb.append("\n\n[MODE TIM — ruang ").append(lane.agentName).append("] Catatan giliran sejauh ini:\n")
        sb.append(transcript)
        sb.append("\n\nLanjutkan sub-tugasmu: bila butuh tool lain keluarkan blok json lagi, bila selesai jawab final tanpa blok json.")
        return sb.toString()
    }

    private fun verify(app: Context, goal: String, lanes: List<Lane>, results: List<String>): String {
        val api = makeClient(app, 0) ?: return "Verifikasi tidak jalan (tidak ada browser login) — hasil ruang belum diperiksa silang."
        val sb = StringBuilder(AgentPrompt.buildSystemPrompt(app))
        sb.append(memoryBlockForTask(app, goal))
        sb.append("\n\n").append(AgentPrompt.CTX_LABEL_RULES)
        sb.append("\n\n[MODE TIM — VERIFIKASI SILANG] Kamu Mentor, pemeriksa independen yang TIDAK ikut mengerjakan. Tujuan tim: ").append(goal).append("\nHasil tiap ruang:\n")
        lanes.forEachIndexed { i, l -> sb.append("— ").append(l.agentName).append(": ").append(results[i].take(4000)).append("\n") }
        sb.append("\nNilai dengan tegas & singkat TANPA tool: mana yang konsisten, mana yang bertentangan atau belum terbukti, apa yang masih kurang untuk tujuan tim.")
        return try { chatSync(app, api, sb.toString()).take(8000) } catch (e: Exception) { "Verifikasi gagal: ${e.message}" }
    }

    /** v4.46.0 F3: klien slot TANPA WebView latar. Slot 0 = otomatis (slot
     *  login pertama); 1–4 = slot tetap; null bila slot kosong. */
    private fun makeClient(app: Context, slot: Int): SlotClient? {
        val s = if (slot in 1..5) slot else try { AccountPool.filledSlots(app).firstOrNull() } catch (e: Exception) { null } ?: return null
        val auth = try { AccountPool.slotAuth(app, s) } catch (e: Exception) { null } ?: return null
        return SlotClient(app, s, auth.first, auth.second)
    }

    private fun chatSync(app: Context, api: SlotClient, prompt: String): String {
        // vc262 XL-Kuota: tiap giliran lane bump hitungan TIM (bukan interaktif).
        // vc277 O16(a): bump berbobot ukuran prompt yang dikirim.
        try { Governor.bumpTeam(app, Governor.weightFor(prompt.length)) } catch (e: Exception) {}
        return api.chatSync(prompt, false, false).first
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
    private fun honestBoardStatus(done: List<String>): String {
        val bad = done.any { d ->
            val t = d.lowercase()
            t.contains("gagal") || t.contains("belum selesai") || t.contains("tidak ada akun") || t.contains("berhenti:") || t.contains("dihentikan:")
        }
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
