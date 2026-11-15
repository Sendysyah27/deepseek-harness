package com.senzwh.deepharness

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

// vc198 Fase 3: konsolidasi graph latar jam sepi (default 03:00, bisa
// diubah seperti routine F2 lainnya).
// Isolasi = isolasi sesi (panggilan latar memakai wadah bgMode vc186,
// terpisah dari sesi foreground) + single-flight global + jam sepi +
// skip bila turn user aktif. JUJUR: isolasi ini TIDAK menciptakan kuota
// rate-limit baru — akun tetap 5 slot; job latar berbagi kuota yang sama
// dengan turn user, hanya dijadwalkan di jam sepi dan tidak menabrak turn.
object GraphJobs {
    private val running = AtomicBoolean(false)

    // Seed SEKALI: routine kind=graph 03:00. Bila user menghapus routine
    // ini, flag sudah set sehingga TIDAK dibuat ulang (pilihan user
    // dihormati); bila routine kind=graph sudah ada (buatan manual),
    // pakai itu saja.
    fun ensureSeedRoutine(ctx: Context) {
        try {
            val sp = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            if (sp.getBoolean("graphRoutineSeeded", false)) return
            val raw = sp.getString("routines", "[]") ?: "[]"
            val arr = try { org.json.JSONArray(raw) } catch (e: Exception) { org.json.JSONArray() }
            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optString("kind") == "graph") {
                    sp.edit().putBoolean("graphRoutineSeeded", true).apply()
                    return
                }
            }
            val o = org.json.JSONObject()
            o.put("id", "graphconsol")
            o.put("kind", "graph")
            o.put("time", "03:00")
            o.put("prompt", "Konsolidasi graph latar (jam sepi)")
            o.put("enabled", true)
            arr.put(o)
            sp.edit().putString("routines", arr.toString()).apply()
            sp.edit().putBoolean("graphRoutineSeeded", true).apply()
        } catch (e: Exception) {}
    }

    // vc204 T3 Otak Bermimpi (deterministik, TANPA LLM): maks 3 pertanyaan
    // pagi dari sinyal graph room, disimpan ke prefs dreamQs_<roomId>
    // (JSON tanggal + daftar, ditimpa tiap konsolidasi). TIDAK PERNAH
    // menulis edge/node baru, TIDAK PERNAH mengubah tag/confidence.
    // Sinyal perubahan = id monotonik (edge/node id lebih besar dari
    // penanda terakhir), bukan recordedAt (edge lama hasil backfill vc198
    // punya recordedAt = waktu migrasi, bukan tanggal fakta).
    fun dreamForRoom(ctx: Context, roomJson: org.json.JSONObject) {
        try {
            val qs = computeDreamQuestions(ctx, roomJson)
            val o = org.json.JSONObject()
            o.put("date", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()))
            val arr = org.json.JSONArray()
            for (q in qs) arr.put(q)
            o.put("questions", arr)
            ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).edit()
                .putString("dreamQs_" + roomJson.optString("id"), o.toString()).apply()
        } catch (e: Exception) {}
    }

    fun dreamQuestionsFor(ctx: Context, roomId: String): List<String> {
        return try {
            val raw = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getString("dreamQs_" + roomId, null) ?: return emptyList()
            val o = org.json.JSONObject(raw)
            val arr = o.optJSONArray("questions") ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) out.add(arr.optString(i))
            out.filter { it.isNotBlank() }.take(3)
        } catch (e: Exception) { emptyList() }
    }

    private fun cleanDreamLabel(s: String): String {
        return s.replace("\n", " ").replace("\r", " ").trim().take(60)
    }

    fun computeDreamQuestions(ctx: Context, roomJson: org.json.JSONObject): List<String> {
        return try {
            val roomId = roomJson.optString("id")
            val title = roomJson.optString("title")
            val g = GraphDb.get(ctx)
            val d = g.mapData(title)
            val sp = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            val markRaw = sp.getString("dreamMark_" + roomId, null)
            val firstRun = (markRaw == null)
            val markObj = try { if (markRaw == null) org.json.JSONObject() else org.json.JSONObject(markRaw) } catch (e: Exception) { org.json.JSONObject() }
            val lastEdgeId = markObj.optLong("edgeId", 0L)
            val lastNodeId = markObj.optLong("nodeId", 0L)
            val byId = d.nodes.associateBy { it.id }
            fun lab(id: Long): String = byId[id]?.label ?: ("#" + id)
            val newEdges = d.edges.filter { it.id > lastEdgeId }
            val newNodes = d.nodes.filter { it.id > lastNodeId }
            // Perbarui penanda SELALU (termasuk saat 0 pertanyaan, dan
            // tidak pernah mundur) agar konsolidasi berikutnya hanya
            // melihat item yang lebih baru.
            val nm = org.json.JSONObject()
            nm.put("edgeId", maxOf(lastEdgeId, d.edges.maxOfOrNull { it.id } ?: lastEdgeId))
            nm.put("nodeId", maxOf(lastNodeId, d.nodes.maxOfOrNull { it.id } ?: lastNodeId))
            sp.edit().putString("dreamMark_" + roomId, nm.toString()).apply()
            val out = ArrayList<String>()
            // 1. AMBIGUOUS menunggu review (paling bisa ditindaklanjuti;
            // valid juga pada run pertama karena bukan klaim kebaruan).
            val amb = d.ambiguous.firstOrNull()
            if (amb != null) {
                out.add("Apakah hubungan " + cleanDreamLabel(lab(amb.from)) + " --" + amb.rel + "--> " + cleanDreamLabel(lab(amb.to)) + " sudah benar, atau perlu dikoreksi?")
            }
            if (!firstRun) {
                // 2. Koneksi baru yang menjembatani dua komunitas
                // (surprising tidak membawa id edge; cocokkan via label).
                val newIds = newEdges.map { it.id }.toSet()
                val surpNew = d.surprising.firstOrNull { s ->
                    d.edges.any { it.id in newIds && lab(it.from) == s.fromLabel && lab(it.to) == s.toLabel }
                }
                if (surpNew != null) {
                    out.add("Koneksi baru " + cleanDreamLabel(surpNew.fromLabel) + " --" + surpNew.rel + "--> " + cleanDreamLabel(surpNew.toLabel) + " menjembatani dua topik — apakah ini disengaja?")
                }
                // 3. God node yang tersentuh edge baru.
                val newEnds = newEdges.flatMap { listOf(it.from, it.to) }.toSet()
                val godTouch = d.gods.firstOrNull { newEnds.contains(it.first.id) }
                if (godTouch != null) {
                    out.add("Apakah " + cleanDreamLabel(godTouch.first.label) + " masih jadi fokus utama, atau prioritasnya sudah bergeser?")
                }
                // 4. Topik/node baru pertama.
                val topic = newNodes.firstOrNull()
                if (topic != null) {
                    out.add("Topik baru " + cleanDreamLabel(topic.label) + " muncul sejak konsolidasi sebelumnya — apakah perlu ditindaklanjuti?")
                }
            }
            out.distinct().filter { it.endsWith("?") }.take(3)
        } catch (e: Exception) { emptyList() }
    }

    // vc236 M24: fase LLM konsolidasi graph, dipakai bersama oleh jalur
    // room dan jalur chat 1-1. Isi = blok inline lama runQuietConsol
    // (relations, lalu verifier bila ada edge baru, lalu adjudikasi bila
    // ada kandidat); candidateSink disalin dulu karena applyRelations
    // menambah kandidat dari relations. Kegagalan LLM ditangkap dan hanya
    // dicatat (failLog), sama seperti perilaku room sebelumnya.
    // vc240 M11: mengembalikan true hanya bila relations + verifier
    // (bila ada edge baru) + adjudikasi (bila ada kandidat) semuanya
    // sukses. Kegagalan satu fase membuat mark ditahan pemanggil.
    private fun runLlmConsolPhase(ctx: Context, transcript: String, candBatch: List<Triple<String, Long, List<Long>>>, failLog: String): Boolean {
        val g = GraphDb.get(ctx)
        var okVc240 = true
        try {
            val allCands = ArrayList<Triple<String, Long, List<Long>>>(candBatch)
            val outcome = g.applyRelations(BgBrain.callBgSync(ctx, g.relationsPrompt(transcript)), allCands)
            if (outcome.newEdges.isNotEmpty()) {
                try {
                    val involved = outcome.newEdges.flatMap { listOf(it.fromLabel, it.toLabel) }.toSet()
                    val labCache = HashMap<Long, String>()
                    fun labOf(id: Long): String {
                        if (!labCache.containsKey(id)) labCache[id] = g.nodeById(id)?.label ?: ("#" + id)
                        return labCache[id] ?: ("#" + id)
                    }
                    val oldList = g.activeEdgesTouching(involved).joinToString("\n") { "old:" + it.id + ": " + labOf(it.from) + " | " + it.rel + " | " + labOf(it.to) }
                    val vAns = BgBrain.callBgSync(ctx, g.verifierPrompt(outcome.newEdges, oldList, transcript))
                    g.applyVerifier(vAns, outcome.newEdges)
                } catch (e: Exception) { okVc240 = false }
            }
            if (allCands.isNotEmpty()) {
                try {
                    val aAns = BgBrain.callBgSync(ctx, g.adjudicationPrompt(allCands))
                    g.applyAdjudications(aAns, allCands)
                } catch (e: Exception) { okVc240 = false }
            }
        } catch (e: Exception) {
            okVc240 = false
            try { EventDb.get(ctx).add("routine", ctx.packageName, failLog) } catch (e2: Exception) {}
        }
        return okVc240
    }

    fun runQuietConsol(ctx: Context) {
        if (!running.compareAndSet(false, true)) {
            try { EventDb.get(ctx).add("routine", ctx.packageName, "Konsolidasi graph latar dilewati: job lain sedang jalan.") } catch (e: Exception) {}
            return
        }
        try {
            if (MainActivity.userTurnActive) {
                try { EventDb.get(ctx).add("routine", ctx.packageName, "Konsolidasi graph latar dilewati: turn user sedang aktif.") } catch (e: Exception) {}
                return
            }
            val rooms = Automation.loadRooms(ctx)
            var done = 0
            for (i in 0 until rooms.length()) {
                val roomJson = rooms.optJSONObject(i) ?: continue
                if (roomJson.optBoolean("archived", false)) continue
                try {
                    val roomIdStr = roomJson.optString("id")
                    val msgs = Automation.loadMessages(ctx, roomIdStr)
                    val mark = Automation.graphMark(ctx, roomIdStr)
                    val skippedMsgs = minOf(mark.optInt("msgCount", 0), msgs.length())
                    val tail = ArrayList<String>()
                    for (j in skippedMsgs until msgs.length()) {
                        val m = msgs.optJSONObject(j) ?: continue
                        tail.add(m.optString("sender") + ": " + m.optString("text"))
                    }
                    // vc240 M11: mark ditunda (deferMark) sampai fase
                    // LLM sukses; backlog diproses per chunk 20 SAMPAI
                    // HABIS (bukan hanya 20 ekor terakhir).
                    val det = Automation.consolidateDeterministic(ctx, roomJson, true)
                    if (tail.isEmpty()) {
                        Automation.saveGraphMark(ctx, roomIdStr, det.pendingMsgCount, det.pendingTaskTexts)
                        dreamForRoom(ctx, roomJson); done++; continue
                    }
                    var okAllVc240 = true
                    for (chunkVc240 in tail.chunked(20)) {
                        val transcript = StringBuilder()
                        for (s in chunkVc240) transcript.append(s).append("\n")
                        val okChunk = runLlmConsolPhase(ctx, transcript.toString(), det.candBatch, "Konsolidasi graph latar room dilewati (LLM gagal): " + roomJson.optString("title"))
                        if (!okChunk) okAllVc240 = false
                    }
                    if (okAllVc240) Automation.saveGraphMark(ctx, roomIdStr, det.pendingMsgCount, det.pendingTaskTexts)
                    else try { EventDb.get(ctx).add("routine", ctx.packageName, "Konsolidasi graph room mark DITAHAN (LLM gagal) — batch dicoba lagi run berikutnya: " + roomJson.optString("title")) } catch (e2: Exception) {}
                    dreamForRoom(ctx, roomJson)
                    done++
                } catch (e: Exception) {
                    try { EventDb.get(ctx).add("routine", ctx.packageName, "Konsolidasi graph latar room gagal: " + e.message) } catch (e2: Exception) {}
                }
            }
            // vc236 M24: konsolidasi graph chat 1-1 dengan pipa yang SAMA
            // (deterministik + relations + verifier + adjudikasi). Mark
            // per-sesi "chat1-1" dipegang Automation.graphMark (kunci
            // graphMark_chat1-1). Dijalankan di job latar yang sama (guard
            // running + skip turn user sudah berlaku di atas); kegagalan
            // 1-1 tidak menggagalkan laporan room.
            try {
                // vc240 M11: sama seperti room — chunk per 20 sampai
                // habis, mark hanya sesudah semua chunk LLM sukses.
                val det11 = Automation.consolidateDeterministicChat11(ctx, true)
                if (det11.tail.isEmpty()) {
                    Automation.saveGraphMark(ctx, "chat1-1", det11.pendingMsgCount, det11.pendingTaskTexts)
                } else {
                    var ok11Vc240 = true
                    for (chunkVc240 in det11.tail.chunked(20)) {
                        val okChunk = runLlmConsolPhase(ctx, chunkVc240.joinToString("\n") + "\n", det11.candBatch, "Konsolidasi graph latar 1-1 dilewati (LLM gagal)")
                        if (!okChunk) ok11Vc240 = false
                    }
                    if (ok11Vc240) Automation.saveGraphMark(ctx, "chat1-1", det11.pendingMsgCount, det11.pendingTaskTexts)
                    else try { EventDb.get(ctx).add("routine", ctx.packageName, "Konsolidasi graph 1-1 mark DITAHAN (LLM gagal) — batch dicoba lagi run berikutnya.") } catch (e2: Exception) {}
                }
                try { EventDb.get(ctx).add("routine", ctx.packageName, "Konsolidasi graph 1-1 selesai: " + det11.tail.size + " episode baru diproses (" + det11.nodes + " node, " + det11.edges + " edge).") } catch (e2: Exception) {}
            } catch (e: Exception) {
                try { EventDb.get(ctx).add("routine", ctx.packageName, "Konsolidasi graph 1-1 gagal: " + e.message) } catch (e2: Exception) {}
            }
            try { EventDb.get(ctx).add("routine", ctx.packageName, "Konsolidasi graph latar selesai: " + done + " room diproses.") } catch (e: Exception) {}
        } finally {
            running.set(false)
        }
    }
}
