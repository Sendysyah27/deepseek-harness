package com.senzwh.deepharness

import org.json.JSONObject

// vc274 O6+O7: mesin batch multi-tool BERSAMA untuk lane latar (BgBrain),
// grup (GroupChat), tim (TeamRun), dan room (MainActivity.handleRoomReply).
// Lane 1-1 TIDAK memakai mesin ini — ia memakai mesin vc273-nya sendiri
// (execBatchAndContinueVc273 di MainActivity), yang JANGAN diubah.
// Aturan (sama seperti 1-1 vc273, dari rencana pasal 3 O6/O7):
// - maks 4 call per respons; lebih dari itu hanya 4 pertama dieksekusi,
//   sisanya diminta ulang di giliran berikutnya.
// - tiap call yang dieksekusi = 1 langkah terhadap cap lane (latar 50,
//   grup 3 per anggota, tim 6, room 50 kumulatif per sesi); penolakan
//   validasi O3, call yang ditunda (O7), dan call yang dilewati TIDAK
//   memakan langkah dan TIDAK dihitung StuckDetector — KECUALI sejak
//   vc303 P1-5 (LP-8): rejected IKUT recordOutcome (memakan 1 langkah +
//   dihitung StuckWatch, kunci ke-3); deferred/skipped tetap tidak.
// - tool READ yang independen dan berurutan dieksekusi KONKUREN (pool
//   maks 4 — aman karena Tools.execute memakai ThreadLocal untuk
//   lastResultId dan lastFullLength, dan execute() me-reset keduanya di
//   awal tiap panggilan); tool tulis/efek (non-READ) SEQUENSIAL sesuai
//   urutan dan berhenti di kegagalan pertama; call dependen (O7) tidak
//   pernah paralel.
// - hasil digabung dalam SATU pesan continuation oleh pemanggil
//   (combinedMessage), satu blok per call dengan ID pengikat tc-<n>.
// - batch TIDAK auto-retry per call (seperti 1-1): kegagalan dilaporkan
//   di blok gabungan dan model yang memutuskan strategi berikutnya.
// - cap lane tercapai di tengah batch = sisa batch dilewati (kind
//   skipped) dan jalur final lane berjalan seperti biasa.
// - TIDAK ADA approval checkpoint baru di sini (vc247 tetap berlaku);
//   eksekutor lane (executeOne) yang disediakan pemanggil-lah yang
//   menjalankan tool, mesin ini hanya mengatur urutan + pembukuan.
object BatchToolVc274 {

    // vc274 O6: tool jalur-khusus yang TIDAK PERNAH di-batch — bila ada di
    // dalam respons multi-tool, lane jatuh ke jalur tunggalnya (call
    // pertama saja, seperti fallback 1-1 vc273). make_plan/update_plan
    // punya eksekutor sendiri di 1-1/latar/tim; analyze_photo hanya dari
    // chat 1-1; delete_session punya aturan sesi aktif; create_subroom
    // room memakai dialog approval; run_team tim ditolak dari dalam lane.
    val SPECIAL_TOOLS: Set<String> = setOf("make_plan", "update_plan", "analyze_photo", "delete_session", "create_subroom", "run_team")

    // vc274 O6+O7: hasil satu call di dalam batch.
    // kind: ok / failed / unknown / rejected (validasi O3) / deferred (O7:
    // prasyarat belum punya id hasil) / skipped (batas langkah / batch dihentikan).
    data class Outcome(
        val tool: String,
        val args: JSONObject,
        val callId: String,
        val result: String,
        val resultId: String?,
        val resultTotal: Int,
        val ok: Boolean,
        val kind: String,
        val prereqFailed: Boolean,
        val ms: Long,
        val stuckText: String = ""
    )

    // vc274 O6+O7: ringkasan satu batch — leftover = call di luar maks 4
    // yang BELUM dieksekusi; stoppedReason = alasan batch dihentikan di
    // tengah (null bila batch jalan sampai habis / cap).
    data class BatchReport(
        val outcomes: List<Outcome>,
        val leftover: Int,
        val stoppedReason: String?
    )

    fun useBatch(calls: List<JSONObject>): Boolean {
        if (calls.size <= 1) return false
        if (calls.any { it.optString("tool").isBlank() }) return false
        if (calls.any { it.optString("tool") in SPECIAL_TOOLS }) return false
        return true
    }

    // vc274 O6: id pengikat kanonis satu call batch — id model dipakai bila
    // cocok pola dan belum dipakai di batch ini; bila tidak, app memberi
    // tc-<n> terkecil yang belum dipakai (sama seperti nextCallIdVc272 1-1).
    fun nextCallId(modelId: String, used: Set<String>): String {
        if (AgentPrompt.isValidCallId(modelId) && modelId !in used) return modelId
        var n = used.size + 1
        while ("tc-$n" in used) n++
        return "tc-$n"
    }

    // vc274 O7: sama seperti resolveBatchArgsVc273 (lane 1-1) — call
    // dependen yang memakai id pengikat call sebelumnya sebagai id
    // get_result/grep_result: ganti id itu dengan resultId nyata yang
    // dihasilkan call tersebut (bila ada); bila call itu tidak
    // menghasilkan id hasil, call ini DITUNDA (bukan dieksekusi dengan id
    // yang salah). Argumen lain tidak diubah.
    fun resolveArgs(tool: String, args: JSONObject, prior: List<Outcome>): Pair<JSONObject, String?> {
        if (tool != "get_result" && tool != "grep_result") return Pair(args, null)
        if (args.optString("id").isBlank()) return Pair(args, null)
        val idArg = args.optString("id")
        if (!AgentPrompt.isValidCallId(idArg)) return Pair(args, null)
        val prev = prior.firstOrNull { it.callId == idArg } ?: return Pair(args, null)
        val rid = prev.resultId
        if (rid.isNullOrBlank()) return Pair(args, "prasyarat " + idArg + " (" + prev.tool + ") tidak menghasilkan id hasil — call ini butuh id hasil itu")
        return try {
            val copy = JSONObject(args.toString())
            copy.put("id", rid)
            Pair(copy, null)
        } catch (e: Exception) { Pair(args, null) }
    }

    // vc274 O6: eksekusi satu call batch. Validasi O3 dulu — penolakan
    // validasi TIDAK memakan langkah dan TIDAK dihitung StuckDetector.
    // Dipanggil di thread pekerja (pool baca) maupun thread koordinator
    // (jalur sekuensial) — execute WAJIB membaca Tools.lastResultId /
    // lastFullLength di thread yang sama, langsung sesudah Tools.execute,
    // karena keduanya ThreadLocal (execute() me-reset keduanya di awal,
    // jadi tidak ada id basi dari call sebelumnya di thread pool itu).
    fun runOne(
        ctx: android.content.Context,
        tool: String,
        args: JSONObject,
        callId: String,
        depReason: String?,
        execute: (String, JSONObject, String) -> String
    ): Outcome {
        val t0 = System.currentTimeMillis()
        val validation = Tools.validateArgs(ctx, tool, args)
        if (validation != null) {
            return Outcome(tool, args, callId, "⚠️ PANGGILAN TOOL DITOLAK SISTEM:\n" + validation, null, 0, false, "rejected", false, System.currentTimeMillis() - t0)
        }
        val result = try { execute(tool, args, callId) } catch (e: Exception) { "Gagal: " + (e.message ?: "") }
        val resultId = Tools.lastResultId()
        val resultTotal = Tools.lastFullLength()
        // vc282: status terstruktur (classify) menggantikan isFailed di
        // sini — aturannya sama (mark disalin), tetapi penanda [STATUS: ...]
        // bila ada menang atas substring-scan.
        val ok = ApiToolLayer.classifyResult(result) == ApiToolLayer.ToolStatus.OK
        val kind = when {
            result.startsWith("Tool tidak dikenal") -> "unknown"
            !ok -> "failed"
            else -> "ok"
        }
        // vc274 O7: call dependen yang gagal karena prasyarat bukan
        // pengulangan model — jangan dihitung StuckDetector.
        val prereqFailed = depReason != null && !ok
        return Outcome(tool, args, callId, result, resultId, resultTotal, ok, kind, prereqFailed, System.currentTimeMillis() - t0)
    }

    // vc274 O6: kelompok baca independen dieksekusi KONKUREN (pool maks 4).
    private fun runReadGroup(
        pairs: List<Pair<JSONObject, String>>,
        executeOne: (String, JSONObject, String, String?) -> Outcome
    ): List<Outcome> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, pairs.size))
        try {
            val futures = pairs.map { (obj, callId) ->
                pool.submit(java.util.concurrent.Callable {
                    val tool = obj.optString("tool")
                    val args = obj.optJSONObject("args") ?: JSONObject()
                    executeOne(tool, args, callId, null)
                })
            }
            return futures.mapIndexed { idx, f ->
                try {
                    // vc282: timeout batch — satu call baca yang menggantung
                    // tidak boleh menggantung seluruh batch selamanya.
                    val toVc282 = ApiToolLayer.batchTimeoutSec(pairs[idx].first.optString("tool"), pairs[idx].first.optJSONObject("args") ?: JSONObject())
                    f.get(toVc282.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    val obj = pairs[idx].first
                    val tool = obj.optString("tool")
                    val args = obj.optJSONObject("args") ?: JSONObject()
                    try { f.cancel(true) } catch (e2: Exception) {}
                    val toVc282 = ApiToolLayer.batchTimeoutSec(tool, args)
                    Outcome(tool, args, pairs[idx].second, "ERROR: tool " + tool + " melewati batas " + toVc282 + " dtk — dibatalkan. Giliran tidak menunggu lagi; eksekusi di latar mungkin masih berjalan sampai batas internalnya. Jangan kirim ulang perintah yang sama persis — pakai pola background + file hasil bila memang butuh lama, atau pecah tugasnya. Putuskan: lanjut dengan cara lain, atau berhenti dan beri tahu user.", null, 0, false, "failed", false, 0)
                } catch (e: Exception) {
                    val obj = pairs[idx].first
                    val tool = obj.optString("tool")
                    val args = obj.optJSONObject("args") ?: JSONObject()
                    Outcome(tool, args, pairs[idx].second, "Gagal: " + (e.message ?: ""), null, 0, false, "failed", false, 0)
                }
            }
        } finally {
            pool.shutdown()
        }
    }

    // vc274 O6+O7: eksekusi satu batch penuh.
    // - stepsLeft: sisa langkah lane saat ini (engine berhenti mengeksekusi
    //   bila 0 — sisa call jadi skipped "batas langkah tercapai").
    // - executeOne: eksekutor SATU call milik lane (validasi O3 +
    //   eksekusi tool + baca resultId di thread yang sama); dipanggil di
    //   thread pekerja untuk kelompok baca, di thread koordinator untuk
    //   jalur sekuensial.
    // - recordOutcome: pembukuan SATU call yang dieksekusi, di thread
    //   koordinator dan sesuai urutan (langkah lane +1, korpus/transkrip,
    //   EventDb, dsb.); dipanggil untuk kind ok/failed/unknown DAN —
    //   sejak vc303 P1-5 (LP-8) — rejected (dihitung StuckDetector);
    //   TIDAK untuk deferred/skipped. Mengembalikan teks
    //   StuckDetector ("" bila tidak ada); bila mengandung tanda berhenti,
    //   engine menghentikan sisa batch.
    fun runBatch(
        calls: List<JSONObject>,
        callIds: List<String>,
        stepsLeft: () -> Int,
        executeOne: (String, JSONObject, String, String?) -> Outcome,
        recordOutcome: (Outcome) -> String
    ): BatchReport {
        val totalCalls = calls.size
        val batch = calls.take(AgentPrompt.MAX_BATCH_CALLS_VC273)
        val leftover = totalCalls - batch.size
        val batchIds = (0 until batch.size).map { callIds.getOrElse(it) { "tc-" + (it + 1) } }
        val outcomes = ArrayList<Outcome>()
        val writtenPaths = HashSet<String>()
        val priorIds = ArrayList<String>()
        var stopBatch = false
        var stopReason = ""
        var i = 0
        while (i < batch.size) {
            if (stopBatch) {
                val o = batch[i]
                outcomes.add(Outcome(o.optString("tool"), o.optJSONObject("args") ?: JSONObject(), batchIds[i], "Tidak dieksekusi — batch dihentikan: " + stopReason, null, 0, false, "skipped", false, 0))
                i++
                continue
            }
            if (stepsLeft() <= 0) {
                val o = batch[i]
                outcomes.add(Outcome(o.optString("tool"), o.optJSONObject("args") ?: JSONObject(), batchIds[i], "Tidak dieksekusi — batas langkah tool tercapai.", null, 0, false, "skipped", false, 0))
                i++
                continue
            }
            val tool = batch[i].optString("tool")
            val args = batch[i].optJSONObject("args") ?: JSONObject()
            val callId = batchIds[i]
            val depReason = AgentPrompt.batchDependencyReason(args, priorIds, writtenPaths)
            val isRead = tool in Tools.READ_TOOLS
            if (isRead && depReason == null) {
                val group = ArrayList<Int>()
                var j = i
                while (j < batch.size && group.size < AgentPrompt.MAX_BATCH_CALLS_VC273) {
                    val tj = batch[j].optString("tool")
                    val aj = batch[j].optJSONObject("args") ?: JSONObject()
                    if (tj !in Tools.READ_TOOLS) break
                    if (AgentPrompt.batchDependencyReason(aj, priorIds + group.map { batchIds[it] }, writtenPaths) != null) break
                    if (stepsLeft() - group.size <= 0) break
                    group.add(j)
                    j++
                }
                val groupOutcomes = runReadGroup(group.map { batch[it] to batchIds[it] }, executeOne)
                for (oc in groupOutcomes) {
                    // VC303-P1-5 (LP-8): rejected IKUT recordOutcome —
                    // dulu dilewati (""), sehingga panggilan tervalidasi-
                    // gagal berulang di batch tidak pernah dihitung
                    // StuckDetector. Efek samping dicatat: rejected kini
                    // memakan 1 langkah lane (konsisten dengan jalur
                    // tunggal 1-1 yang mencatat rejected sebagai step).
                    val stuck = recordOutcome(oc)
                    outcomes.add(if (stuck.isBlank()) oc else oc.copy(stuckText = stuck))
                    priorIds.add(oc.callId)
                    val wp = AgentPrompt.writtenPathOf(oc.tool, oc.args)
                    if (wp.isNotBlank()) writtenPaths.add(wp)
                    if (AgentPrompt.StuckWatch.isStop(stuck)) { stopBatch = true; stopReason = "StuckDetector mengunci giliran" }
                }
                i = j
                continue
            }
            val (effArgs, deferredReason) = resolveArgs(tool, args, outcomes)
            if (deferredReason != null) {
                outcomes.add(Outcome(tool, args, callId, "Ditunda — " + deferredReason + ". Hasil prasyarat sudah ada di atas; kirim ulang call ini di giliran berikutnya dengan argumen lengkap.", null, 0, false, "deferred", false, 0))
                priorIds.add(callId)
                i++
                continue
            }
            val oc = executeOne(tool, effArgs, callId, depReason)
            if (oc.kind == "rejected") {
                // VC303-P1-5 (LP-8): rejected IKUT recordOutcome (lihat
                // catatan di kelompok baca di atas) — StuckDetector
                // menghitung rejected berulang per (tool, jenis penolakan)
                // dan mengunci di ke-3.
                val stuckRejVc303 = recordOutcome(oc)
                outcomes.add(if (stuckRejVc303.isBlank()) oc else oc.copy(stuckText = stuckRejVc303))
                if (AgentPrompt.StuckWatch.isStop(stuckRejVc303)) { stopBatch = true; stopReason = "StuckDetector mengunci giliran" }
            } else {
                val stuck = recordOutcome(oc)
                outcomes.add(if (stuck.isBlank()) oc else oc.copy(stuckText = stuck))
                if (AgentPrompt.StuckWatch.isStop(stuck)) { stopBatch = true; stopReason = "StuckDetector mengunci giliran" }
            }
            priorIds.add(oc.callId)
            val wp = AgentPrompt.writtenPathOf(tool, effArgs)
            if (wp.isNotBlank()) writtenPaths.add(wp)
            if (!isRead && (oc.kind == "failed" || oc.kind == "rejected")) {
                stopBatch = true
                stopReason = "tool tulis " + tool + " " + if (oc.kind == "failed") "GAGAL" else "DITOLAK SISTEM"
            }
            i++
        }
        return BatchReport(outcomes, leftover, if (stopBatch) stopReason else null)
    }

    // vc274 O6: gabungkan hasil batch dalam SATU pesan continuation (pola
    // Anthropic: semua tool_result dalam satu pesan, sebelum teks apa pun)
    // — cerminan builder continuation 1-1 vc273, dengan label lane.
    fun combinedMessage(laneLabel: String, report: BatchReport): String {
        val sb = StringBuilder()
        sb.append("Hasil batch — " + report.outcomes.size + " panggilan tool dalam satu respons (lane " + laneLabel + ", vc274 O6). Satu blok hasil per call, dengan ID pengikat tc-<n>:\n")
        for (oc in report.outcomes) {
            val disp = RedactSecrets.redact(oc.result)
            val cap = AgentPrompt.BATCH_RESULT_CAP_VC273
            val capped = if (disp.length > cap) disp.take(cap) + "…(dipotong, sisa " + (disp.length - cap) + " char" + (if ((oc.resultId ?: "").isNotBlank()) ", pakai get_result[" + oc.resultId + "] untuk sisanya" else "") + ")…" else disp
            sb.append("\nHasil tool " + oc.tool + " " + ApiToolLayer.statusTag(ApiToolLayer.classifyResult(oc.result)) + " [HASIL TOOL " + oc.callId + "]" + (if (oc.resultId != null) " [id=" + oc.resultId + ", total " + oc.resultTotal + " char]" else "") + ":\n" + capped + "\n")
            if (oc.kind == "rejected") sb.append("PERINTAH SISTEM: keluarkan ULANG blok json tool '" + oc.tool + "' dengan argumen yang sudah DIPERBAIKI sesuai pesan di atas.\n")
            if (oc.kind == "failed") sb.append("WAJIB: coba strategi atau tool LAIN untuk tujuan yang sama — jangan ulangi panggilan yang persis sama.\n")
            if (oc.prereqFailed) sb.append("ERROR PRASYARAT: call ini bergantung pada hasil call sebelumnya yang gagal atau belum selesai — prasyarat belum selesai, kirim ulang setelah prasyarat selesai.\n")
            if (oc.stuckText.isNotBlank()) sb.append(oc.stuckText).append("\n")
        }
        if (report.leftover > 0) sb.append("\nSisa " + report.leftover + " panggilan tool dari respons ini BELUM dieksekusi (batas batch maks " + AgentPrompt.MAX_BATCH_CALLS_VC273 + " per respons) — kirim ulang di giliran berikutnya.\n")
        if (report.outcomes.any { it.kind == "skipped" }) sb.append("\n⛔ CAP LANGKAH TERCAPAI di tengah batch ini (ada call yang tidak dieksekusi karena batas langkah). Bila tidak ada langkah tersisa, jawab FINAL dengan jujur: \"berhenti di tengah pada langkah N dari tugas — ini yang sudah selesai: …; ini yang belum: …\". Jangan panggil tool lagi bila cap sudah habis.\n")
        sb.append("\nLanjutkan untuk user: bila butuh tool lain keluarkan blok json lagi (gabungkan hanya call yang independen, maks 4 per respons), bila selesai jawab final tanpa blok json.")
        return sb.toString()
    }
}
