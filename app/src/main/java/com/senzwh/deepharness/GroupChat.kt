package com.senzwh.deepharness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Grup Chat Bareng Agent (v4.47.0, G1).
 *
 *  Satu ruang berisi user + 4 agen (Asisten, Mandor, Mentor, Agen 4) dengan
 *  transkrip bersama. Berbeda dari run_team (lane paralel independen):
 *  di sini giliran berjalan SEQUENSIAL dan tiap agen menerima transkrip
 *  grup sejauh itu sebagai konteks, sehingga bisa menanggapi agen lain.
 *
 *  Disiplin giliran (K1, Fase 6): tiap anggota menjawab lewat helper
 *  Models API (LlmEngineChat.chatSyncForEngine, kind ROOM) — tanpa slot,
 *  tanpa sesi server. Semua anggota aktif selalu
 *  menjawab; tidak ada lagi "istirahat karena slot habis".
 *
 *  Riwayat grup disimpan di tabel khusus group_msgs (MemoryDb) — TIDAK
 *  PERNAH menyentuh baris history 1-1. */
object GroupChat {

    data class Member(val id: String, val name: String, val accent: String, val persona: String)

    val MEMBERS = listOf(
        Member("asisten", "Asisten", "#4d8cff", "peneliti & penulis utama: rapi, jelas, terstruktur"),
        Member("mandor", "Mandor", "#e8a33d", "eksekutor lapangan: konkret langkah demi langkah, laporkan hasil apa adanya"),
        Member("mentor", "Mentor", "#2fbf9a", "pemeriksa kritis: teliti, tandai risiko dan ketidakpastian secara eksplisit"),
        Member("agen4", "Agen 4", "#9a6bff", "generalis serbaguna: riset singkat + eksekusi langsung, laporan ringkas")
    )

    /** Cap anti-loop: maksimal bubble agen per pesan user. */
    const val MAX_AGENT_BUBBLES = 6

    interface Listener {
        fun onAgent(name: String, accent: String, text: String)
        fun onSystem(text: String)
        // vc246 GELOMBANG 5 (K-07): kartu opsi grup — jawaban grup yang
        // memuat blok ask kini diteruskan ke UI sebagai kartu yang bisa
        // diketuk, bukan JSON mentah. Default no-op agar listener lama
        // (mis. latar, yang tidak punya UI kartu) tetap jalan.
        fun onAsk(name: String, question: String, options: List<String>) {}
        fun onDone()
        /** v4.47.1: dipanggil saat giliran agen dimulai (untuk indikator). Default kosong. */
        fun onTurnStart(name: String) {}
        /** Catatan istirahat: anggota tanpa slot (tenang, bukan peringatan). Default kosong. */
        fun onRest(name: String, text: String) {}
        /** v4.48.0 F2: bubble hasil tool (warna tool 1-1). Default kosong. */
        fun onTool(name: String, accent: String, text: String) {}
        /** v4.48.0 F2: minta persetujuan tool berisiko; cb(true)=izinkan.
         *  Default = tolak (dipakai listener tanpa UI). */
        fun onToolApproval(req: ToolRequest, cb: (Boolean) -> Unit) { cb(false) }
    }

    @Volatile var running = false
    // vc246 GELOMBANG 5 (GR-m7/GR-m8): antrean pesan grup yang dikirim
    // saat ronde berjalan (dulu hilang, user harus kirim ulang) + bendera
    // hentikan ronde (tombol Hentikan di UI grup).
    @Volatile var stopRequested = false
    fun queueMessage(ctx: Context, text: String): Boolean {
        return try {
            val p = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            val arr = org.json.JSONArray(p.getString("groupQueuedMsgs", "[]") ?: "[]")
            if (arr.length() >= 5) false else { arr.put(text); p.edit().putString("groupQueuedMsgs", arr.toString()).apply(); true }
        } catch (e: Exception) { false }
    }
    fun popQueued(ctx: Context): String? {
        return try {
            val p = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            val arr = org.json.JSONArray(p.getString("groupQueuedMsgs", "[]") ?: "[]")
            if (arr.length() == 0) null else { val t = arr.optString(0); arr.remove(0); p.edit().putString("groupQueuedMsgs", arr.toString()).apply(); t }
        } catch (e: Exception) { null }
    }
    fun requestStop() { stopRequested = true }

    /** v4.47.1: agen yang sedang merespon saat ini (null bila idle). Untuk indikator grup. */
    @Volatile var currentAgent: String? = null

    /** G2: toggle "Semua jawab" (tersimpan di pref groupAll). */
    fun isGroupAllOn(ctx: Context): Boolean =
        ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getBoolean("groupAll", false)

    fun setGroupAll(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).edit().putBoolean("groupAll", on).apply()
    }

    /** F2: Mode Eksekusi (pref groupExec, default false). */
    fun isGroupExecOn(ctx: Context): Boolean =
        ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getBoolean("groupExec", false)

    fun setGroupExec(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).edit().putBoolean("groupExec", on).apply()
    }

    /** F2 (UI-nya di dialog Setting Grup fase 5): auto-approve grup
     *  (pref groupAutoApprove, default false). Bila ON + foreground +
     *  Mode Eksekusi ON, tier jalan tanpa dialog/PIN (Koreksi 2),
     *  KECUALI vc242: kelas terkunci mandat + create_subroom selalu
     *  lewat dialog listener. Blokir guiBlockReason (bank/e-wallet)
     *  TIDAK PERNAH diangkat. */
    fun isGroupAutoApproveOn(ctx: Context): Boolean =
        ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getBoolean("groupAutoApprove", false)

    fun setGroupAutoApprove(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).edit().putBoolean("groupAutoApprove", on).apply()
    }

    /** F5: anggota aktif (pref groupMemberActive_<id>, default true untuk
     *  semua — perilaku lama tidak berubah sampai user mengubah). */
    fun isMemberActive(app: Context, id: String): Boolean =
        try { app.getSharedPreferences("harness", Context.MODE_PRIVATE).getBoolean("groupMemberActive_$id", true) } catch (e: Exception) { true }

    fun setMemberActive(app: Context, id: String, on: Boolean) {
        try { app.getSharedPreferences("harness", Context.MODE_PRIVATE).edit().putBoolean("groupMemberActive_$id", on).apply() } catch (e: Exception) {}
    }

    fun personaNameFor(id: String): String =
        GroupChatSeam.memberInfo(MEMBERS, id, id)?.first ?: personaNameForKotlin(id)

    /** Slice 12 (vc329): nama persona dari id — dipakai bila seam
     *  Rust tak-tersedia. */
    private fun personaNameForKotlin(id: String): String =
        MEMBERS.firstOrNull { it.id == id }?.name ?: "—"

    fun accentFor(name: String): String =
        GroupChatSeam.memberInfo(MEMBERS, "", name)?.second ?: accentForKotlin(name)

    /** Slice 12 (vc329): aksen dari nama — dipakai bila seam Rust
     *  tak-tersedia. */
    private fun accentForKotlin(name: String): String =
        MEMBERS.firstOrNull { it.name.equals(name, ignoreCase = true) }?.accent ?: "#4d8cff"

    /** Parse @mention (case-insensitive, urutan kemunculan): @asisten, @mandor,
     *  @mentor, @agen4 / @agen 4. Anggota yang sama hanya disebut sekali. */
    fun parseMentions(text: String): List<Member> {
        val ids = GroupChatSeam.parseMentions(text, MEMBERS) ?: return parseMentionsKotlin(text)
        return ids.mapNotNull { id -> MEMBERS.firstOrNull { it.id == id } }
    }

    /** Slice 12 (vc329): parse @mention — dipakai bila seam Rust
     *  tak-tersedia. */
    private fun parseMentionsKotlin(text: String): List<Member> {
        val norm = " " + text.lowercase().replace(Regex("\\s+"), " ") + " "
        val hits = ArrayList<Pair<Int, Member>>()
        for (m in MEMBERS) {
            val toks = when (m.id) {
                "agen4" -> listOf("@agen 4", "@agen4")
                else -> listOf("@" + m.id)
            }
            var best = -1
            for (t in toks) {
                val i = norm.indexOf(t)
                if (i >= 0 && (best < 0 || i < best)) best = i
            }
            if (best >= 0) hits.add(best to m)
        }
        hits.sortBy { it.first }
        return hits.map { it.second }
    }

    /** Satu ronde grup: userText SUDAH disimpan sebagai pesan user sebelum
     *  pemanggilan ini. Callback listener dipanggil dari thread latar. */
    fun sendRound(ctx: Context, userText: String, listener: Listener) {
        startRound(ctx, userText, isGroupExecOn(ctx), listener)
    }

    /** Mesin ronde (foreground + latar F3). execMode=true hanya untuk
     *  foreground dengan Mode Eksekusi ON; latar selalu false (diskusi saja). */
    private fun selectQueue(mentioned: List<Member>, allOn: Boolean, activeMembers: List<Member>): Triple<List<Member>, List<String>, String> {
        val chairId = activeMembers.firstOrNull()?.id ?: "asisten"
        val r = GroupChatSeam.selectQueue(mentioned.map { it.id }, allOn, activeMembers.map { it.id }, MEMBERS)
        if (r != null) {
            val queue = r.first.mapNotNull { id -> MEMBERS.firstOrNull { it.id == id } }
            return Triple(queue, r.second, r.third)
        }
        return selectQueueKotlin(mentioned, allOn, activeMembers, chairId)
    }

    /** Slice 12 (vc329): pemilihan antrean ronde (catatan verbatim
     *  dikembalikan sebagai data — pemanggil yang menulis ke DB +
     *  listener) — dipakai bila seam Rust tak-tersedia. */
    private fun selectQueueKotlin(mentioned: List<Member>, allOn: Boolean, activeMembers: List<Member>, chairId: String): Triple<List<Member>, List<String>, String> {
        val queue = ArrayList<Member>()
        val notes = ArrayList<String>()
        if (activeMembers.isEmpty()) {
            // Nol aktif: satu catatan, tanpa LLM dan tanpa slot.
            notes.add("Tidak ada anggota grup yang aktif — aktifkan lewat ⚙️ Setting Grup.")
        } else if (allOn) {
            // Semua jawab: hanya aktif yang menjawab; nonaktif
            // dilewati diam-diam (tanpa catatan, agar tidak spam).
            queue.addAll(activeMembers)
        } else if (mentioned.isNotEmpty()) {
            for (mm in mentioned) {
                if (activeMembers.any { it.id == mm.id }) queue.add(mm)
                else {
                    // Mention ke nonaktif: SATU catatan sistem
                    // (bukan istirahat) per ronde untuk member itu.
                    notes.add("💤 ${mm.name} nonaktif di grup — aktifkan lewat ⚙️ Setting Grup.")
                }
            }
        } else queue.add(activeMembers[0])
        return Triple(queue, notes, chairId)
    }

    /** Slice 12 (vc329): pesan berhenti-jujur cap 3 langkah per
     *  anggota per ronde (vc277 O15(b)) — dipakai P15 untuk uji paritas. */
    private fun honestCapStopGroup(usedTools: List<String>, userText: String): String {
        val doneVc277 = usedTools.distinct().joinToString(", ").ifBlank { "belum ada tool yang dijalankan" }
        return GroupChatSeam.honestCapStopGroup(doneVc277, userText.take(300))
            ?: honestCapStopGroupKotlin(doneVc277, userText)
    }

    /** Slice 12 (vc329): pesan cap grup (salinan asli) — dipakai bila
     *  seam Rust tak-tersedia. */
    private fun honestCapStopGroupKotlin(done: String, userText: String): String =
        "berhenti di tengah pada langkah 3 dari tugas: " + userText.take(300) + " — tiap anggota maks 3 langkah tool per ronde. Ini yang sudah selesai: " + done + "; ini yang belum: sisa tugas belum dikerjakan dalam ronde ini — lanjutkan di ronde berikutnya atau minta anggota lain."

    /** Slice 12 (vc329): chair-hop — anggota yang disebut di balasan
     *  ketua ikut menjawab satu hop (maks 2, tanpa yang sudah di
     *  antrean). Dipakai P15 untuk uji paritas. */
    private fun chairHop(clean: String, allOn: Boolean, mentionedEmpty: Boolean, isChair: Boolean, bubbles: Int, responded: Set<String>, queue: List<Member>): List<String> {
        val r = GroupChatSeam.chairHop(clean, allOn, mentionedEmpty, isChair, bubbles, MAX_AGENT_BUBBLES, responded.toList(), queue.map { it.id }, MEMBERS)
        if (r != null) return r
        return chairHopKotlin(clean, allOn, mentionedEmpty, isChair, bubbles, responded, queue)
    }

    /** Slice 12 (vc329): chair-hop (salinan asli) — dipakai bila seam
     *  Rust tak-tersedia. */
    private fun chairHopKotlin(clean: String, allOn: Boolean, mentionedEmpty: Boolean, isChair: Boolean, bubbles: Int, responded: Set<String>, queue: List<Member>): List<String> {
        if (allOn || !mentionedEmpty || !isChair || bubbles >= MAX_AGENT_BUBBLES) return emptyList()
        return parseMentions(clean).filter { !responded.contains(it.id) && !queue.contains(it) }.map { it.id }
    }

    private fun startRound(ctx: Context, userText: String, execMode: Boolean, listener: Listener, onFinish: (() -> Unit)? = null) {
        if (running) {
            // vc246 GELOMBANG 5 (GR-m7): pesan TIDAK dibuang — diantrekan
            // (maks 5) dan dieksekusi otomatis setelah ronde selesai.
            if (queueMessage(ctx, userText)) listener.onSystem("⏳ Grup masih menjawab — pesanmu diantrekan dan akan dikirim otomatis setelah ronde ini selesai.")
            else listener.onSystem("⏳ Grup masih menjawab dan antrean penuh (5) — kirim ulang setelah ronde selesai.")
            listener.onDone()
            return
        }
        stopRequested = false
        // vc302 Fix A: ronde grup baru = giliran baru — reset guard
        // baca-ulang identik (ditaruh sesudah cabang antrean di atas,
        // supaya pesan yang diantrekan TIDAK mereset guard ronde yang
        // sedang berjalan).
        Tools.resetRereadGuardVc302()
        if (ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getBoolean("f1Off", false)) {
            listener.onSystem("⛔ F1 dimatikan oleh user (kill switch) — grup dijeda. Aktifkan lagi F1 lewat Profil > Kill Switch F1 untuk melanjutkan.")
            listener.onDone()
            return
        }
        running = true
        Thread {
            try {
                val app = ctx.applicationContext
                val mdb = MemoryDb.get(app)
                val mentioned = parseMentions(userText)
                val allOn = isGroupAllOn(app)
                val activeMembers = MEMBERS.filter { isMemberActive(app, it.id) }
                // Slice 12 (vc329): pemilihan antrean lewat seam Rust
                // (catatan verbatim diterapkan di sini — efek samping
                // tetap Kotlin).
                val (queueList, queueNotes, chairId) = selectQueue(mentioned, allOn, activeMembers)
                val queue = ArrayList(queueList)
                for (note in queueNotes) {
                    try { mdb.addGroupMsg("sistem", "Sistem", note) } catch (e: Exception) {}
                    listener.onSystem(note)
                }
                val memories = retrieveMemories(app, userText)
                // vc334 R4 (G-4): blok bersama (fakta/goals/skills/
                // katalog/transkrip) dihitung SEKALI per ronde — dulu
                // buildPrompt menghitung ulang per anggota termasuk
                // query MemoryDb yang sama.
                val sharedVc334 = sharedBlockVc334(app, userText, memories, execMode)
                // K1 (Fase 6): semua anggota aktif selalu menjawab via
                // helper Models API (tanpa slot/auth).
                val responded = HashSet<String>()
                var bubbles = 0
                var i = 0
                while (i < queue.size && bubbles < MAX_AGENT_BUBBLES && !stopRequested) {
                    val m = queue[i]; i++
                    if (responded.contains(m.id)) continue
                    responded.add(m.id)
                    currentAgent = m.name
                    try { listener.onTurnStart(m.name) } catch (e: Exception) {}
                    var prompt = buildPrompt(app, m, userText, memories, execMode, sharedVc334)
                    // VC303-P2-11 (CX-4): jejak tool ronde anggota ini —
                    // ditulis ke kunci grup di akhir giliran anggota.
                    // Dideklarasi DI LUAR try di bawah supaya terlihat juga
                    // dari catch (local fun di dalam try tidak terlihat di
                    // catch); list dibuat baru tiap anggota.
                    val roundWorkVc303 = ArrayList<WorkItem>()
                    fun flushGroupWorkVc303() {
                        if (roundWorkVc303.isNotEmpty()) WorkNotesVc301.appendToKey(app, WorkNotesVc301.groupNotesKey(), roundWorkVc303, { s -> RedactSecrets.redact(s) })
                    }
                    try {
                        // K1 (Fase 6): jawaban anggota via helper Models
                        // API (kind ROOM).
                        var finalText: String? = null
                        var toolIter = 0
                        var proseBeforeTool = ""
                        val roundToolsVc249 = ArrayList<String>()
                        val roundCorpusVc249 = StringBuilder()
                        // vc263 (N-m21): detektor putaran per ronde anggota.
                        val stuckWatchGrpVc263 = AgentPrompt.StuckWatch()
                        // vc277 O17(c): pengunci giliran grup — sekali 🛑, tool
                        // berikutnya ditolak tanpa eksekusi dan tanpa memakan langkah.
                        var turnLockedGrpVc277 = false
                        // vc277 O15(b): berhenti yang jujur saat cap 3/ronde tercapai.
                        fun honestCapStopGrpVc277(): String = honestCapStopGroup(roundToolsVc249, userText)
                        // vc274 O6+O7: eksekusi batch multi-tool grup (per
                        // anggota). Fungsi lokal agar bisa memakai var ronde
                        // (toolIter, prompt, roundTools, roundCorpus) lewat
                        // closure. Mengembalikan true bila ronde anggota
                        // harus BERHENTI sekarang (StuckDetector —
                        // finalText sudah diisi di sini).
                        // Hasil per call dicatat seperti jalur tunggal
                        // (pesan tool + korpus per call), lalu
                        // SATU continuation gabungan (combinedMessage)
                        // ditempel ke prompt berikutnya.
                        fun execBatchGrpVc274(calls: List<JSONObject>, visibleGrpVc274: String): Boolean {
                            val usedGrpVc274 = HashSet<String>()
                            val callIdsGrpVc274 = calls.map { BatchToolVc274.nextCallId(it.optString("id"), usedGrpVc274).also { usedGrpVc274.add(it) } }
                            val reportGrpVc274 = BatchToolVc274.runBatch(
                                calls, callIdsGrpVc274,
                                stepsLeft = { 3 - toolIter },
                                executeOne = { t, a, c, dep ->
                                    BatchToolVc274.runOne(app, t, a, c, dep) { tt, aa, cc -> runGroupTool(app, tt, aa, listener, cc) }
                                },
                                recordOutcome = { oc ->
                                    toolIter++
                                    roundToolsVc249.add(oc.tool)
                                    // VC303-P2-11 (CX-4): kumpulkan jejak tool grup (jalur batch).
                                    roundWorkVc303.add(WorkItem(oc.tool, oc.ms, oc.ok, RedactSecrets.redact(oc.result).take(120), evidence = oc.result, args = oc.args.toString().take(4000), resultId = oc.resultId ?: "", totalChars = oc.resultTotal, callId = oc.callId))
                                    roundCorpusVc249.append("🔧 ").append(if (oc.callId.isNotBlank()) "[" + oc.callId + "] " else "").append(oc.tool).append(": ").append(RedactSecrets.redact(oc.result).take(4000)).append("\n")
                                    val toolTextGrpVc274 = ActivityTitle.formatText(oc.tool, oc.args, RedactSecrets.redact(oc.result), oc.ms, !WorkLog.isFailed(oc.result))
                                    try { mdb.addGroupMsg("tool", m.name, toolTextGrpVc274) } catch (e: Exception) {}
                                    try { listener.onTool(m.name, m.accent, toolTextGrpVc274) } catch (e: Exception) {}
                                    if (oc.prereqFailed) "" else stuckWatchGrpVc263.observe(oc.tool, oc.args, oc.result)
                                }
                            )
                            // vc321 IXL: lanjutan batch = delta (jangkar +
                            // pesan gabungan), bukan rebuild prompt penuh.
                            prompt = continuationPromptVc321(userText, BatchToolVc274.combinedMessage("grup", reportGrpVc274))
                            val stopGrpVc274 = reportGrpVc274.outcomes.firstOrNull { AgentPrompt.StuckWatch.isStop(it.stuckText) }
                            if (stopGrpVc274 != null) {
                                turnLockedGrpVc277 = true
                                finalText = (visibleGrpVc274.ifBlank { "" }) + "\n\n⛔ Giliran grup dihentikan: StuckDetector — tool " + stopGrpVc274.tool + " berputar tanpa progres (pola/hasil sama berulang)."
                                return true
                            }
                            // vc277 O15(b): cap 3/ronde tercapai di tengah batch.
                            if (toolIter >= 3) {
                                finalText = ((visibleGrpVc274.ifBlank { "" }) + "\n\n⛔ " + honestCapStopGrpVc277()).trim()
                                return true
                            }
                            return false
                        }
                        while (true) {
                            // Helper sinkron kind ROOM (Governor +
                            // TurnMetrics otomatis di helper, jawaban kosong
                            // = error). Throw helper naik ke catch giliran →
                            // catatan "⚠️ <nama> gagal: ..." (bukan diam).
                            val answer: String = LlmEngineChat.chatSyncForEngine(app, prompt, LlmCallKind.ROOM)
                            if (!execMode) { finalText = answer.trim(); break }
                            val multiGrpVc249 = AgentPrompt.extractToolCalls(answer)
                            val (visible, toolObj) = AgentPrompt.extractToolCall(answer)
                            // vc274 O6+O7: >1 tool per respons di lane grup
                            // DIEKSEKUSI sebagai batch per anggota
                            // (BatchToolVc274 — aturan sama seperti 1-1
                            // vc273; tiap call = 1 langkah cap 3 per
                            // anggota per ronde). Bila batch tidak dipakai
                            // (tool jalur-khusus di dalamnya), jatuh ke
                            // jalur tunggal di bawah (toolObj = call pertama).
                            if (multiGrpVc249.size > 1 && BatchToolVc274.useBatch(multiGrpVc249)) {
                                if (visible.isNotBlank()) proseBeforeTool = visible.trim()
                                if (execBatchGrpVc274(multiGrpVc249, visible)) break
                                continue
                            }
                            if (toolObj == null) {
                                finalText = visible.trim(); break
                            }
                            if (toolIter >= 3) { finalText = ((visible.trim()) + "\n\n⛔ " + honestCapStopGrpVc277()).trim(); break }
                            toolIter++
                            if (visible.isNotBlank()) proseBeforeTool = visible.trim()
                            val tool = toolObj.optString("tool")
                            val args = toolObj.optJSONObject("args") ?: JSONObject()
                            val callIdGrpVc272 = toolObj?.optString("id") ?: ""
                            // vc277 O17(c): giliran terkunci StuckDetector — tolak
                            // tanpa eksekusi dan tanpa memakan langkah.
                            if (turnLockedGrpVc277) { finalText = ((visible.trim()) + "\n\n⛔ Giliran grup dikunci StuckDetector — tool $tool ditolak tanpa dieksekusi (tidak memakan langkah).").trim(); break }
                            if (app.getSharedPreferences("harness", Context.MODE_PRIVATE).getBoolean("f1Off", false)) {
                                val note = "⛔ F1 dimatikan — giliran ${m.name} dihentikan."
                                try { mdb.addGroupMsg("sistem", "Sistem", note) } catch (e: Exception) {}
                                listener.onSystem(note)
                                finalText = visible.trim()
                                break
                            }
                            val t0GrpVc254 = System.currentTimeMillis()
                            // VC303-P2-11 (CX-4): R-id sebelum eksekusi —
                            // runGroupTool bisa kembali tanpa mengeksekusi
                            // (mis. analyze_photo di grup) sehingga R-id
                            // lama di ThreadLocal TIDAK boleh dipakai.
                            val grpRidBeforeVc303 = Tools.lastResultId()
                            val result = runGroupTool(app, tool, args, listener, callIdGrpVc272)
                            val msGrpVc254 = System.currentTimeMillis() - t0GrpVc254
                            roundToolsVc249.add(tool)
                            roundCorpusVc249.append("🔧 ").append(if (callIdGrpVc272.isNotBlank()) "[" + callIdGrpVc272 + "] " else "").append(tool).append(": ").append(RedactSecrets.redact(result).take(4000)).append("\n")
                            // vc263 (N-m21): putaran terdeteksi = suffix ke
                            // prompt lanjutan (peringatan) atau hentikan
                            // ronde ini (tanda berhenti) — tanpa retry.
                            val stuckGrpVc263 = stuckWatchGrpVc263.observe(tool, args, result)
                            var stuckSuffixGrpVc263 = ""
                            if (stuckGrpVc263.isNotBlank()) {
                                if (AgentPrompt.StuckWatch.isStop(stuckGrpVc263)) {
                                    turnLockedGrpVc277 = true
                                    finalText = (visible.ifBlank { "" }) + "\n\n⛔ Giliran grup dihentikan: StuckDetector — tool $tool berputar tanpa progres (pola/hasil sama berulang)."
                                    break
                                }
                                stuckSuffixGrpVc263 = stuckGrpVc263
                            }
                            val grpId = Tools.lastResultId()
                            val grpTotal = Tools.lastFullLength()
                            // VC303-P2-11 (CX-4): kumpulkan jejak tool grup (jalur tunggal).
                            run {
                                val grpRidVc303 = if (grpId != null && grpId != grpRidBeforeVc303) grpId else ""
                                roundWorkVc303.add(WorkItem(tool, msGrpVc254, !WorkLog.isFailed(result), RedactSecrets.redact(result).take(120), evidence = result, args = args.toString().take(4000), resultId = grpRidVc303, totalChars = if (grpRidVc303.isBlank()) 0 else grpTotal, callId = callIdGrpVc272))
                            }
                            // vc254: teks simpanan grup jadi format aktivitas
                            // (judul + status + argumen + hasil).
                            val toolText = ActivityTitle.formatText(tool, args, RedactSecrets.redact(result), msGrpVc254, !WorkLog.isFailed(result))
                            try { mdb.addGroupMsg("tool", m.name, toolText) } catch (e: Exception) {}
                            listener.onTool(m.name, m.accent, toolText)
                            // vc321 IXL: lanjutan sesudah tool = delta
                            // (jangkar + hasil tool + ucapan + instruksi
                            // lanjut), bukan rebuild prompt penuh. Isi
                            // suffix byte-identik dengan versi lama.
                            prompt = continuationPromptVc321(userText,
                                "Hasil tool $tool" + (if (grpId != null) " [id=$grpId, total $grpTotal char]" else "") + ":\n${RedactSecrets.redact(result).take(8000)}\n" +
                                (if (proseBeforeTool.isNotBlank()) "\nUcapanmu sebelum tool tadi: ${proseBeforeTool.take(2000)}\n" else "") +
                                "\nLanjutkan: bila butuh tool lain keluarkan blok json tool (boleh sampai 4 blok sekaligus bila independen, maks 4 per respons), bila selesai jawab final TANPA blok json." + stuckSuffixGrpVc263)
                        }
                        val cleanRaw = (finalText ?: "").trim()
                        // vc246 GELOMBANG 5 (K-07): strip blok ask dari teks
                        // tampil + teruskan kartu opsi ke UI lewat onAsk.
                        // Tanpa execMode, jawaban adalah prosa (prompt grup
                        // melarang blok json) sehingga ask di sini praktis
                        // hanya muncul di mode Eksekusi.
                        val askPair = try { AgentPrompt.extractAsk(cleanRaw) } catch (e: Exception) { null }
                        var clean = askPair?.second?.trim() ?: cleanRaw
                        if (clean.isNotEmpty()) {
                            try { mdb.addGroupMsg("agen", m.name, clean) } catch (e: Exception) {}
                            listener.onAgent(m.name, m.accent, clean)
                            if (askPair != null) {
                                try {
                                    val aq = askPair.first.optString("question")
                                    val ao = ArrayList<String>()
                                    val aarr = askPair.first.optJSONArray("options")
                                    if (aarr != null) for (k in 0 until aarr.length()) ao.add(aarr.optString(k))
                                    if (aq.isNotBlank() && ao.isNotEmpty()) listener.onAsk(m.name, aq, ao)
                                } catch (e: Exception) {}
                            }
                            bubbles++
                            // Hop ketua (G1, digeneralisasi F5): hanya bila pesan user TANPA
                            // mention dan penjawabnya ketua saat itu — bila
                            // balasannya menyebut agen lain, agen itu ikut
                            // menjawab SATU hop, cap tetap 6 bubble.
                            // Slice 12 (vc329): undangan hop lewat seam Rust.
                            for (id in chairHop(clean, allOn, mentioned.isEmpty(), m.id == chairId, bubbles, responded, queue)) {
                                val inv = MEMBERS.firstOrNull { it.id == id } ?: continue
                                queue.add(inv)
                            }
                        }
                        // VC303-P2-11 (CX-4): akhir giliran anggota — tulis
                        // catatan kerja grup untuk ronde ini.
                        flushGroupWorkVc303()
                    } catch (e: Exception) {
                        // VC303-P2-11 (CX-4): giliran anggota gagal di tengah —
                        // tetap tulis jejak tool yang sudah jalan.
                        try { flushGroupWorkVc303() } catch (e2: Exception) {}
                        val note = "⚠️ ${m.name} gagal: " + (e.message ?: "").take(200)
                        try { mdb.addGroupMsg("sistem", "Sistem", note) } catch (e: Exception) {}
                        listener.onSystem(note)
                    }
                    currentAgent = null
                }
            } catch (e: Exception) {
                try { listener.onSystem("⚠️ Grup gagal: " + (e.message ?: "").take(200)) } catch (e2: Exception) {}
            } finally {
                // vc246 GELOMBANG 5 (GR-m7, perbaikan pasca-patch):
                // kuras SATU pesan antrean di finally yang sudah ada
                // (bukan finally kedua — Kotlin hanya mengizinkan satu).
                // Ronde berikutnya menguras sisanya satu per satu.
                try {
                    val nxt = popQueued(ctx)
                    if (nxt != null && !stopRequested) {
                        listener.onSystem("▶️ Mengirim pesan grup yang tadi diantrekan…")
                        running = false
                        startRound(ctx, nxt, execMode, listener, onFinish)
                        return@Thread
                    }
                } catch (e: Exception) {}
                running = false
                currentAgent = null
                try { listener.onDone() } catch (e: Exception) {}
                try { onFinish?.invoke() } catch (e: Exception) {}
            }
        }.apply { isDaemon = true }.start()
    }

    /** Grup Matang F1: retrieval otak kedua (read-only, sekali per ronde).
     *  Memakai embedding yang SUDAH ADA; pesan grup BELUM di-embed di fase
     *  ini. Maks 5 cuplikan, masing-masing <=300 karakter, berlabel lewat
     *  format tampilan semantik/kata-kunci yang sudah ada. Bila paket vektor
     *  belum siap, jatuh ke pencarian kata kunci. */
    fun retrieveMemories(app: Context, userText: String): List<String> {
        if (userText.isBlank()) return emptyList()
        val raw = try {
            val vec = Embedder.embedQuery(app, userText)
            if (vec != null) MemoryDb.get(app).semanticSearch(vec, 5)
            else MemoryDb.get(app).searchMemory(userText, 5)
        } catch (e: Exception) { "" }
        if (raw.isBlank()) return emptyList()
        return GroupChatSeam.retrieveMemoriesFilter(raw) ?: retrieveMemoriesKotlin(raw)
    }

    /** Slice 12 (vc329): saring baris memori (trim, non-kosong,
     *  bukan "Tidak ada memori"/"Kata kunci", maks 10 x 1000 char) —
     *  dipakai bila seam Rust tak-tersedia. */
    private fun retrieveMemoriesKotlin(raw: String): List<String> =
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .filter { !it.startsWith("Tidak ada memori") && !it.startsWith("Kata kunci") }
            .take(10).map { it.take(1000) }

    /** vc321 IXL — lanjutan DELTA room (mirror sendContinuation 1-1
     *  vc316): ronde lanjutan anggota yang sama TIDAK lagi membangun
     *  ulang prompt penuh (base + katalog + transkrip ~70.000 char)
     *  — cukup jangkar delta (~503 char) + isi baru (hasil tool /
     *  pesan gabungan / nudge). Jangkar membawa jalan keluar list_tools
     *  {} seperti 1-1. Ronde pertama tiap anggota tetap kirim prompt
     *  penuh (buildPrompt). */
    private fun continuationPromptVc321(userText: String, suffix: String): String {
        return GroupChatSeam.continuationPromptRoom(userText, suffix) ?: continuationPromptVc321Kotlin(userText, suffix)
    }

    /** Slice 12 (vc329): lanjutan delta room (jangkar vc316 + isi
     *  baru) — dipakai bila seam Rust tak-tersedia. */
    private fun continuationPromptVc321Kotlin(userText: String, suffix: String): String {
        return AgentPrompt.continuationAnchorVc316(userText) + "\n\n" + suffix
    }

    /** vc334 R4 (G-4): blok prompt grup yang SAMA untuk semua anggota
     *  satu ronde — dihitung sekali per ronde (sharedBlockVc334),
     *  bukan per anggota. Berisi semua yang tidak bergantung pada
     *  anggota: persona, fakta PIN, fakta/goals ber-query, skill,
     *  katalog, catatan kerja, transkrip grup, WorldState, Mode Bebas.
     *  Yang tetap per anggota: pack + blok otak anggota
     *  (AgentBrain.promptBlocksFor) + nama. */
    private data class GroupSharedVc334(
        val persona: String,
        val pinned: String,
        val memoriesFiltered: List<String>,
        val facts: String,
        val goals: String,
        val workNotes: String,
        val catalog: String,
        val skills: String,
        val transcript: List<String>,
        val worldLine: String,
        val modeBebas: String
    )

    /** vc334 R4 (G-4): hitung blok bersama satu ronde grup (sekali
     *  per ronde — query MemoryDb + selectCatalog + groupMessages
     *  di sini saja). Dipanggil dari startRound; buildPrompt memakai
     *  hasilnya bila diberikan, bila tidak (call-site lain) menghitung
     *  sendiri seperti dulu. */
    private fun sharedBlockVc334(app: Context, userText: String, memories: List<String>, toolsEnabled: Boolean): GroupSharedVc334 {
        val persona = try { app.getSharedPreferences("harness", Context.MODE_PRIVATE).getString("persona", "")?.trim().orEmpty() } catch (e: Exception) { "" }
        val pinnedRawGrpVc263 = try { MemoryDb.get(app).pinnedFactsText() } catch (e: Exception) { "" }
        val pinnedGrpVc248 = try { RedactSecrets.redact(pinnedRawGrpVc263) } catch (e: Exception) { "" }
        val memoriesVc241 = try { val keysVc241 = MemoryDb.get(app).factsAllWithStatus().map { it.first.lowercase() }.filter { it.length >= 4 }; memories.filter { ln -> keysVc241.none { k -> ln.lowercase().contains(k) } } } catch (e: Exception) { memories }
        val factsVc240 = try { RedactSecrets.redact(MemoryDb.get(app).dedupFactsVsPinned(MemoryDb.get(app).factsText(userText), pinnedRawGrpVc263)) } catch (e: Exception) { "" }
        val goalsVc240 = try { RedactSecrets.redact(MemoryDb.get(app).goalsText(userText)) } catch (e: Exception) { "" }
        val workNotesVc303 = WorkNotesVc301.blockForKey(app, WorkNotesVc301.groupNotesKey())
        val catSlimVc321 = if (toolsEnabled) AgentPrompt.catalogForTask(app, userText) else ""
        val catVc240 = if (catSlimVc321.length > 30000) catSlimVc321.take(30000) + "\n…(katalog grup dipotong, sisa " + (catSlimVc321.length - 30000) + " char)…" else catSlimVc321
        val skills = if (toolsEnabled) try { RedactSecrets.redact(MemoryDb.get(app).skillsText(userText)) } catch (e: Exception) { "" } else ""
        val rows = try { MemoryDb.get(app).groupMessages(30) } catch (e: Exception) { emptyList() }
        val transcript = ArrayList<String>()
        for (r in rows) {
            val who = when (r.role) {
                "user" -> "User"
                "sistem" -> "Sistem"
                "tool" -> "Tool"
                else -> r.name
            }
            transcript.add(who + ": " + (if (r.role == "user") r.text.take(2000) else RedactSecrets.redact(r.text.take(2000))))
        }
        return GroupSharedVc334(
            persona, pinnedGrpVc248, memoriesVc241, factsVc240, goalsVc240,
            workNotesVc303, catVc240, skills, transcript,
            WorldState.line(app, false, true), AgentPrompt.modeBebasBlock(app)
        )
    }

    /** Grup Matang F1: prompt penuh — persona user + pak perilaku anggota +
     *  blok otaknya sendiri + keadaan sekarang + cuplikan otak kedua
     *  berlabel + transkrip grup. Anggaran: total maks 200000 karakter;
     *  cuplikan retrieval sudah dipotong (maks 10 x 1000); bila masih lewat,
     *  baris transkrip tertua dipotong dulu (minimal 5 baris dipertahankan).
     *  History 1-1 TIDAK disalin mentah — jembatan satu-satunya adalah
     *  cuplikan retrieval berlabel di bawah. toolsEnabled=true menyertakan
     *  katalog tool 1-1 + katalog skill; false = teks biasa tanpa katalog.
     *  vc321: katalog lewat selectCatalog (bukan penuh dicap 30.000);
     *  lanjutan ronde memakai continuationPromptVc321 (delta), bukan
     *  rebuild buildPrompt. */
    private fun buildPrompt(app: Context, me: Member, userText: String, memories: List<String>, toolsEnabled: Boolean, shared: GroupSharedVc334? = null): String {
        // Slice 12 (vc329): semua blok dihitung di Kotlin (DB/prefs/
        // katalog + redact), perakitan string lewat seam Rust. Katalog
        // lewat selectCatalog + backstop 30k vc321 — JANGAN kirim
        // katalog penuh dari Rust. `memories` dikirim SUDAH di-redact
        // (Rust tidak me-redact baris memori).
        val pack = try { Agents.all(app).firstOrNull { it.id == me.id }?.pack ?: "asisten" } catch (e: Exception) { "asisten" }
        // vc334 R4 (G-4): blok bersama dari ronde (sekali per ronde);
        // tanpa itu, hitung sendiri seperti dulu.
        val sh = shared ?: sharedBlockVc334(app, userText, memories, toolsEnabled)
        val r = GroupChatSeam.buildPrompt(
            me.name, sh.persona, Behaviors.block(pack), AgentBrain.promptBlocksFor(app, me.id),
            AgentPrompt.CTX_LABEL_RULES, AgentPrompt.PAGING_TOTAL_RULE_VC302,
            sh.pinned, sh.worldLine,
            sh.memoriesFiltered.map { RedactSecrets.redact(it) }, sh.facts, sh.goals, sh.workNotes,
            toolsEnabled, sh.catalog, sh.skills, sh.modeBebas,
            AgentPrompt.BATCH_TERMINAL_RULE_VC302, sh.transcript, userText
        )
        if (r != null) return r
        return buildPromptKotlin(app, me, userText, memories, toolsEnabled, sh)
    }

    /** Slice 12 (vc329): prompt penuh grup (salinan asli) — dipakai
     *  bila seam Rust tak-tersedia. */
    private fun buildPromptKotlin(app: Context, me: Member, userText: String, memories: List<String>, toolsEnabled: Boolean, shared: GroupSharedVc334? = null): String {
        val pack = try { Agents.all(app).firstOrNull { it.id == me.id }?.pack ?: "asisten" } catch (e: Exception) { "asisten" }
        // vc334 R4 (G-4): blok bersama dari ronde (sekali per ronde).
        val sh = shared ?: sharedBlockVc334(app, userText, memories, toolsEnabled)
        val persona = sh.persona
        val base = StringBuilder()
        base.append("Kamu adalah ${me.name} di grup chat (anggota: Asisten, Mandor, Mentor, Agen 4 + user).\n")
        if (persona.isNotEmpty()) base.append("PERSONA & INSTRUKSI DARI USER:\n").append(persona).append("\n\n")
        base.append(Behaviors.block(pack))
        base.append(AgentBrain.promptBlocksFor(app, me.id))
        base.append(AgentPrompt.CTX_LABEL_RULES).append("\n\n")
        // vc302 Fix B: aturan total paging (total dari header, jangan jumlahkan).
        base.append(AgentPrompt.PAGING_TOTAL_RULE_VC302).append("\n\n")
        val pinnedGrpVc248 = sh.pinned
        if (pinnedGrpVc248.isNotBlank()) base.append("[FAKTA PIN — selalu berlaku, tidak dibuang diam-diam]:\n").append(pinnedGrpVc248).append("\n\n")
        // vc241 WorldState: grup kini punya blok TUJUAN AKTIF (vc240 M18) sehingga goal-teratas WorldState dibuang; baterai/tanggal dipertahankan karena grup tidak punya blok khusus untuk keduanya.
        base.append(sh.worldLine).append("\n\n")
        // vc241 O2: cuplikan memori grup yang menyebut kunci fakta dibuang — fakta itu sudah tampil di blok MEMORI PERSISTEN di bawah.
        val memoriesVc241 = sh.memoriesFiltered
        if (memoriesVc241.isNotEmpty()) {
            base.append("Memori relevan (otak kedua) — cuplikan berlabel dari memori lama, BUKAN riwayat grup saat ini:\n")
            for (line in memoriesVc241) base.append("- ").append(RedactSecrets.redact(line)).append("\n")
            base.append("\n")
        }
        // vc240 M18 (pilihan: samakan blok fakta/goals ber-cap,
        // bukan sekadar dokumentasi perbedaan): anggota grup kini melihat
        // fakta/goals user seperti 1-1 (cap fakta 20000, goals 10000 dari
        // factsText/goalsText, vc248). Thinking grup tetap false (routing
        // tidak diubah).
        val factsVc240 = sh.facts
        if (factsVc240.isNotBlank()) base.append("MEMORI PERSISTEN (fakta user):\n").append(factsVc240).append("\n\n")
        val goalsVc240 = sh.goals
        if (goalsVc240.isNotBlank()) base.append("TUJUAN AKTIF:\n").append(goalsVc240).append("\n\n")
        // VC303-P2-11 (CX-4): catatan kerja grup (satu kunci untuk seluruh
        // grup — grup tidak punya sessionId) — ronde berikut melihat hasil
        // tool ronde sebelumnya walau sudah keluar dari 30 pesan terakhir.
        run { val wbGrpVc303 = sh.workNotes; if (wbGrpVc303.isNotBlank()) base.append("[CATATAN KERJA GRUP]\n").append(wbGrpVc303).append("\n\n") }
        if (toolsEnabled) {
            // vc321 IXL: katalog room kini lewat selectCatalog (pola vc316
            // 1-1) — katalog penuh 94.809 char selama ini dicap 30.000,
            // tapi tiap ronde rebuild + POST parent NULL mengakumulasi
            // konteks server; slim ~12-22k char per tugas ("cek skill
            // lists" 22.429 vs 30.000, "bro?" 13.726). Backstop 30.000
            // dipertahankan (tugas kosong -> katalog penuh, kontrak lama).
            // Trade-off sama seperti vc316: tool di luar seleksi didapat
            // lewat list_tools {} (catatan fallback di tiap slim).
            val catVc240 = sh.catalog
            base.append("KATALOG TOOL (sama seperti chat 1-1 — pakai bila tugas butuh aksi HP):\n").append(catVc240).append("\n\n")
            // vc262 N-m23: teruskan teks tugas sebagai query agar ranking
            // relevansi skill hidup (tanpa query semua skor 0).
            val skills = sh.skills
            if (skills.isNotBlank()) base.append("SKILL AKTIF (nama+description saja; muat isi lewat use_skill bila relevan):\n").append(skills).append("\n\n")
            base.append("Jawablah pesan terakhir sebagai ${me.name} di grup ini: ringkas, langsung, menanggapi pesan sebelumnya bila relevan. Jangan mengulang transkrip, jangan menyebut dirimu sebagai model lain, jangan keluar dari peran ${me.name}. Bila perlu aksi HP, keluarkan blok json tool (boleh sampai 4 blok sekaligus bila call-nya independen, maks 4 per respons); hasil tool akan diberikan padamu untuk dilanjutkan. Tiap anggota maks 3 langkah tool per ronde — bila cap tercapai, giliran berhenti dengan penjelasan jujur (yang sudah selesai / yang belum).\n\n")
        } else {
            base.append("Jawablah pesan terakhir sebagai ${me.name} di grup ini: ringkas, langsung, menanggapi pesan sebelumnya bila relevan. Jangan mengulang transkrip, jangan menyebut dirimu sebagai model lain, jangan keluar dari peran ${me.name}. Di mode ini jawablah dengan teks biasa saja (tanpa blok json tool).\n\n")
        }
        // vc252 D-M7: blok Mode Bebas TIDAK lagi ditempel di base (vc251
        // menempel di sini lalu ikut terpotong take(60000) di bawah) —
        // ditempel ke baseStrVc240 SETELAH cap, sebelum transkrip.
        // vc334 R4 (G-4): transkrip dari blok bersama ronde.
        val transcript = sh.transcript
        // vc240 M18: pengaman akhir base — bila base sendiri sudah
        // di atas 12000 (persona panjang), base dipotong dengan penanda
        // sebelum transkrip, sehingga total dengan 5 baris transkrip
        // minimal tidak lagi melebihi cap 14000 secara diam-diam.
        var baseStrVc240 = base.toString()
        if (baseStrVc240.length > 60000) baseStrVc240 = baseStrVc240.take(60000) + "\n…(base grup dipotong ke 60000 char; cap total 200000)…\n"
        // vc252 D-M7: blok Mode Bebas ditempel SETELAH cap base — toggle
        // aktif = framing pasti sampai ke model, tidak lagi hilang diam-diam
        // bila base di atas 60000. Total tetap jauh di bawah cap 200000.
        // vc302 Fix F: aturan batching terminal (grup — sesudah cap base
        // 60000 supaya tidak terpangkas, sebelum Mode Bebas yang tetap
        // paling akhir). Ditempel juga bila toolsEnabled=false: agen tanpa
        // tool tidak terpengaruh, dan tidak ada cabang prompt kedua.
        baseStrVc240 += AgentPrompt.BATCH_TERMINAL_RULE_VC302 + "\n\n"
        baseStrVc240 += sh.modeBebas
        var drop = 0
        while (drop < transcript.size - 5) {
            val candidate = baseStrVc240 + "Transkrip grup:\n" + transcript.drop(drop).joinToString("\n")
            if (candidate.length <= 200000) break
            drop++
        }
        val sb = StringBuilder(baseStrVc240)
        sb.append("[RIWAYAT]\nTranskrip grup:\n")
        for (line in transcript.drop(drop)) sb.append(line).append("\n")
        sb.append("[/RIWAYAT]\n")
        sb.append("[USER — PESAN PEMICU VERBATIM]:\n").append(userText).append("\n[/USER]\n")
        return sb.toString()
    }

    /** F2: eksekusi satu tool grup lewat funnel Tools.execute penuh
     *  (guiBlockReason + Connectors.gate + REQUIRED_ARGS — blokir GUI bank/
     *  e-wallet TIDAK PERNAH diangkat auto-approve). Approval: auto-approve
     *  ON (foreground) → tier tanpa dialog/PIN (Koreksi 2), kecuali
     *  vc242 (kelas terkunci + create_subroom, lihat bawah); OFF →
     *  SAFE langsung, REVIEW/HIGH lewat dialog listener (HIGH + PIN di
     *  sisi UI). vc233 K2a: tool tulis/hapus memori selalu lewat dialog
     *  listener, tidak pernah lewat shortcut auto-approve/autoAllow.
     *  vc229 R-06(d): analyze_photo diintersepsi di sini (tidak
     *  tersedia di grup — analisis foto hanya dari chat 1-1); tool lain yang
     *  tak dikenal funnel dikembalikan sebagai teks hasil oleh funnel itu
     *  sendiri. */
    private fun runGroupTool(app: Context, tool: String, args: JSONObject, listener: Listener, callId: String = ""): String {
        return try {
            // Slice 12 (vc329): keputusan rute lewat seam Rust;
            // ledger/eksekusi/dialog listener tetap Kotlin.
            // vc334 R5 F2: konfirmasi khusus (delete_skill) berlaku juga
            // di grup walau GATES_DISABLED=true — arahkan ke dialog
            // listener (Ya/Batal) alih-alih "execute_gates". Dicek
            // SEBELUM runGroupToolDecision supaya seam Rust pun tidak
            // bisa mengembalikan "execute" untuk tool ini.
            val route = if (ToolGate.GATES_DISABLED && ConcatTools.requiresSpecialConfirmVc316(tool, args)) {
                "dialog" to null
            } else runGroupToolDecision(app, tool, args)
            when (route.first) {
                "msg_analyze_photo" -> route.second ?: ""
                // vc247: gate dimatikan — eksekusi langsung tanpa syarat
                // auto-approve, tanpa dialog listener, tanpa antrean.
                // vc262 GR-M6 (visibilitas, BUKAN gate): eksekusi tool grup
                // tercatat di ledger (panel membaca ledger); tidak ada
                // approval grup yang menggantung — gate vc247 nonaktif.
                "execute_gates" -> {
                    try { DecisionLedger.add(app, "grup", "tool", (if (callId.isNotBlank()) "[$callId] " else "") + tool + " " + RedactSecrets.redact(args.toString().take(200))) } catch (e: Exception) {}
                    Tools.execute(app, tool, args, callId)
                }
                "execute" -> Tools.execute(app, tool, args, callId)
                else -> {
                    val req = ToolGate.describe(tool, args, app)
                    val latch = CountDownLatch(1)
                    val approved = AtomicBoolean(false)
                    try {
                        listener.onToolApproval(req) { ok -> approved.set(ok); latch.countDown() }
                    } catch (e: Exception) { latch.countDown() }
                    val decided = try { latch.await(180, TimeUnit.SECONDS) } catch (e: Exception) { false }
                    if (!decided || !approved.get()) {
                        "DITOLAK USER: tool $tool tidak diizinkan (atau dialog persetujuan tidak tampil karena app tidak di depan). Lanjutkan tugas tanpa tool itu — alternatif atau jawaban final."
                    } else Tools.execute(app, tool, args, callId)
                }
            }
        } catch (e: Exception) { "Tool $tool gagal: ${(e.message ?: "").take(200)}" }
    }

    /** Slice 12 (vc329): keputusan `runGroupTool` (route + pesan) —
     *  dipakai P15 untuk uji paritas. Route: "execute" |
     *  "execute_gates" | "dialog" | "msg_analyze_photo". */
    private fun runGroupToolDecision(app: Context, tool: String, args: JSONObject): Pair<String, String?> {
        val r = GroupChatSeam.groupToolRoute(
            tool, ToolGate.GATES_DISABLED, Tools.isMemoryWrite(tool),
            ApprovalPanelStore.isAutoAllowBlocked(tool), isGroupAutoApproveOn(app),
            Tools.isRisky(tool, args), ApprovalPanelStore.autoAllowValid(app, tool, -1L)
        )
        if (r != null) return r
        return runGroupToolDecisionKotlin(app, tool, args)
    }

    /** Slice 12 (vc329): keputusan `runGroupTool` (salinan asli:
     *  intersepsi analyze_photo, gate, shortcut, dialog) — dipakai
     *  bila seam Rust tak-tersedia. */
    private fun runGroupToolDecisionKotlin(app: Context, tool: String, args: JSONObject): Pair<String, String?> {
        // vc229 R-06(d): intersepsi analyze_photo di grup — jangan sampai
        // jatuh ke else "tidak dikenal" lalu model mendeskripsikan tebakan.
        if (tool == "analyze_photo") return "msg_analyze_photo" to "analyze_photo tidak tersedia di grup/latar — analisis foto hanya dari chat 1-1. Bila foto belum dianalisis, tulis persis 'foto belum berhasil dianalisis', jangan mendeskripsikan tebakan isi foto."
        // vc247: gate dimatikan — eksekusi langsung tanpa syarat
        // auto-approve, tanpa dialog listener, tanpa antrean.
        if (ToolGate.GATES_DISABLED) return "execute_gates" to null
        // vc233 K2a: tool tulis/hapus memori TIDAK PERNAH lewat shortcut
        // auto-approve maupun autoAllow — selalu jatuh ke dialog listener
        // di bawah, walau auto-approve grup ON. Shortcut di bawah hanya
        // untuk tool non-memori, perilaku auto-approve lainnya tidak berubah.
        // vc242 K-01/GR-M7: shortcut auto-approve/autoAllow TIDAK
        // berlaku untuk tool tulis/hapus memori, kelas terkunci mandat
        // (termasuk priv_input HIGH), maupun create_subroom — ketiganya
        // selalu jatuh ke dialog listener di bawah.
        if (!Tools.isMemoryWrite(tool) && tool != "create_subroom" && !ApprovalPanelStore.isAutoAllowBlocked(tool)) {
            if (isGroupAutoApproveOn(app)) return "execute" to null
            if (!Tools.isRisky(tool, args)) return "execute" to null
            // vc229 R-04(a)(b): autoAllow grup memakai gerbang yang sama
            // (blokir kelas terkunci + TTL 24 jam; tanpa ikatan sesi di grup).
            if (ApprovalPanelStore.autoAllowValid(app, tool, -1L)) return "execute" to null
        }
        return "dialog" to null
    }

    /** F3a: jam hening eksplisit ronde grup latar (23:00-05:00, D2). */
    private fun isQuietHour(): Boolean {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return GroupChatSeam.quietHourGroup(h) ?: (h >= 23 || h < 5)
    }

    /** F3a: ronde grup latar (DISKUSI SAJA, tanpa tools — tidak ada dialog
     *  approval di background). Dipakai job target grup + digest grup.
     *  Gerbang: f1Off, jobsOff, jam hening 23:00-05:00, baterai
     *  (BgBrain.batteryOk). Bila ronde foreground sedang berjalan ->
     *  joblog "grup sibuk", TIDAK mengantre ganda. Return true = ronde
     *  dimulai (startRound mengeset running secara sinkron sebelum Thread
     *  jalan; false sesudahnya = gerbang memblokir). Topik disimpan sebagai
     *  pesan user bernama "Sistem" sebelum ronde dimulai. */
    fun runBackgroundRound(ctx: Context, topic: String, jobId: String): Boolean {
        val app = ctx.applicationContext
        // Slice 12 (vc329): gerbang lewat seam Rust (keputusan murni);
        // logJob + startRound tetap Kotlin.
        val (decision, reason, jobStatus) = bgRoundGateDecision(app)
        if (decision != "proceed") {
            try { MemoryDb.get(app).logJob(jobId, jobStatus ?: "diblokir", reason ?: "") } catch (e: Exception) {}
            return false
        }
        try { MemoryDb.get(app).addGroupMsg("user", "Sistem", topic) } catch (e: Exception) {}
        val silent = object : Listener {
            override fun onAgent(name: String, accent: String, text: String) {}
            override fun onSystem(text: String) {}
            override fun onDone() {}
        }
        startRound(app, topic, false, silent, onFinish = {
            try { MemoryDb.get(app).logJob(jobId, "selesai", topic.take(120)) } catch (e: Exception) {}
            notifyGroupDone(app, topic)
        })
        if (!running) {
            val prefs = app.getSharedPreferences("harness", Context.MODE_PRIVATE)
            val reason = if (prefs.getBoolean("f1Off", false)) "F1 dimatikan (kill switch)" else "grup sibuk — ronde lain dimulai bersamaan"
            val status = if (prefs.getBoolean("f1Off", false)) "diblokir" else "sibuk"
            try { MemoryDb.get(app).logJob(jobId, status, reason) } catch (e: Exception) {}
            return false
        }
        try { MemoryDb.get(app).logJob(jobId, "berjalan", topic.take(120)) } catch (e: Exception) {}
        return true
    }

    /** Slice 12 (vc329): gerbang `runBackgroundRound` — dipakai P15
     *  untuk uji paritas. Decision: "proceed" | "busy" | "blocked". */
    private fun bgRoundGateDecision(app: Context): Triple<String, String?, String?> {
        val prefs = app.getSharedPreferences("harness", Context.MODE_PRIVATE)
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val r = GroupChatSeam.bgRoundGate(running, prefs.getBoolean("f1Off", false), BgBrain.jobsOff(app), h, BgBrain.batteryOk(app))
        if (r != null) return r
        return bgRoundGateKotlin(app)
    }

    /** Slice 12 (vc329): gerbang ronde latar (salinan asli) — dipakai
     *  bila seam Rust tak-tersedia. */
    private fun bgRoundGateKotlin(app: Context): Triple<String, String?, String?> {
        if (running) return Triple("busy", "grup sibuk — ronde foreground sedang berjalan", "sibuk")
        val prefs = app.getSharedPreferences("harness", Context.MODE_PRIVATE)
        val reason = when {
            prefs.getBoolean("f1Off", false) -> "F1 dimatikan (kill switch)"
            BgBrain.jobsOff(app) -> "job dimatikan"
            isQuietHour() -> "jam hening 23:00-05:00"
            !BgBrain.batteryOk(app) -> "baterai di bawah 20% dan tidak dicas"
            else -> null
        }
        return if (reason == null) Triple("proceed", null, null) else Triple("blocked", reason, "diblokir")
    }

    /** F3a: notifikasi selesai ronde latar (pola BgBrain, channel sama). */
    private fun notifyGroupDone(app: Context, topic: String) {
        try {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("harness-alert", "Pengingat & briefing", NotificationManager.IMPORTANCE_HIGH))
            val grpIntentVc262 = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("openGroup", true)
            val pi = PendingIntent.getActivity(app, 7, grpIntentVc262, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val last = try { MemoryDb.get(app).groupMessages(5).lastOrNull { it.role == "agen" }?.text?.take(180) } catch (e: Exception) { null }
            val body = last ?: topic
            // vc262 GR-m9: render notifikasi dibatasi wajar — judul + 3
            // baris pertama + penanda sisa, agar tidak tenggelam di bawah.
            // Slice 12 (vc329): render lewat seam Rust.
            val renderGrpVc262 = GroupChatSeam.renderNotif3Lines(body) ?: renderNotif3LinesKotlin(body)
            val n = Notification.Builder(app, "harness-alert")
                .setContentTitle("👥 Grup Agen — ronde latar selesai")
                .setContentText(body.take(180))
                .setStyle(Notification.BigTextStyle().bigText(renderGrpVc262))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi).setAutoCancel(true).build()
            nm.notify(NotifIds.GROUP_DONE, n)
        } catch (e: Exception) {}
    }

    /** Slice 12 (vc329): render notifikasi 3 baris — dipakai bila
     *  seam Rust tak-tersedia. */
    private fun renderNotif3LinesKotlin(body: String): String {
        val linesVc262 = body.lines()
        return if (linesVc262.size <= 3) body else linesVc262.take(3).joinToString("\n") + "\n…(" + (linesVc262.size - 3) + " baris lagi)…"
    }

    /** F3d: plan grup (pref planJson_group, TERPISAH dari planJson_<session>).
     *  Ringkasan tersimpan, BUKAN auto-continue T15 (Command Center tak disentuh). */
    data class GroupPlan(val title: String, val body: String, val ts: Long)

    fun groupPlans(app: Context): List<GroupPlan> {
        return try {
            val raw = app.getSharedPreferences("harness", Context.MODE_PRIVATE).getString("planJson_group", "") ?: ""
            if (raw.isBlank()) return emptyList()
            GroupChatSeam.parsePlans(raw) ?: groupPlansKotlin(raw)
        } catch (e: Exception) { emptyList() }
    }

    /** Slice 12 (vc329): parse + sort plan grup (salinan asli) —
     *  dipakai bila seam Rust tak-tersedia. */
    private fun groupPlansKotlin(raw: String): List<GroupPlan> {
        val arr = org.json.JSONArray(raw)
        val out = ArrayList<GroupPlan>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(GroupPlan(o.optString("title"), o.optString("body"), o.optLong("ts")))
        }
        return out.sortedByDescending { it.ts }
    }

    private fun saveGroupPlans(app: Context, plans: List<GroupPlan>) {
        try {
            val arr = org.json.JSONArray()
            for (pl in plans) arr.put(JSONObject().put("title", pl.title).put("body", pl.body).put("ts", pl.ts))
            app.getSharedPreferences("harness", Context.MODE_PRIVATE).edit().putString("planJson_group", arr.toString()).apply()
        } catch (e: Exception) {}
    }

    /** Simpan ronde terakhir sebagai satu entri plan. Dedup: ronde yang
     *  sama (judul + isi identik) tidak disimpan dua kali — tombol header
     *  jadi aman dipakai juga untuk membuka dialog plan. */
    fun saveLastRoundAsPlan(app: Context): String {
        return try {
            val rows = MemoryDb.get(app).groupMessages(200)
            val plans = groupPlans(app)
            // Slice 12 (vc329): dedup + cap + serialisasi lewat seam
            // Rust; tulis prefs tetap Kotlin.
            val rowsJson = org.json.JSONArray()
            for (r in rows) rowsJson.put(JSONObject().put("role", r.role).put("name", r.name).put("text", r.text))
            val plansJson = org.json.JSONArray()
            for (pl in plans) plansJson.put(JSONObject().put("title", pl.title).put("body", pl.body).put("ts", pl.ts))
            val (message, newPlansJson) = GroupChatSeam.saveLastRoundPlan(rowsJson, plansJson, System.currentTimeMillis())
                ?: return saveLastRoundAsPlanKotlin(app)
            if (newPlansJson != null) {
                app.getSharedPreferences("harness", Context.MODE_PRIVATE).edit().putString("planJson_group", newPlansJson).apply()
            }
            message
        } catch (e: Exception) { "Gagal menyimpan rencana grup: ${(e.message ?: "").take(100)}" }
    }

    /** Slice 12 (vc329): simpan ronde terakhir sebagai plan (salinan
     *  asli) — dipakai bila seam Rust tak-tersedia. */
    private fun saveLastRoundAsPlanKotlin(app: Context): String {
        return try {
            val rows = MemoryDb.get(app).groupMessages(200)
            val lastUser = rows.indexOfLast { it.role == "user" }
            if (lastUser < 0) return "Belum ada pesan user di grup untuk disimpan."
            val userText = rows[lastUser].text
            val agents = rows.drop(lastUser + 1).filter { it.role == "agen" }
            if (agents.isEmpty()) return "Ronde terakhir belum ada jawaban agen untuk disimpan."
            val body = StringBuilder()
            for (a in agents) {
                val part = "— ${a.name}: ${a.text.take(600)}"
                if (body.length + part.length > 4000) break
                if (body.isNotEmpty()) body.append("\n\n")
                body.append(part)
            }
            val title = userText.take(60)
            val plans = groupPlans(app).toMutableList()
            if (plans.any { it.title == title && it.body == body.toString() }) return "Ronde terakhir sudah tersimpan sebelumnya."
            plans.add(GroupPlan(title, body.toString(), System.currentTimeMillis()))
            saveGroupPlans(app, plans)
            "📌 Rencana grup tersimpan: '$title'"
        } catch (e: Exception) { "Gagal menyimpan rencana grup: ${(e.message ?: "").take(100)}" }
    }

    fun deleteGroupPlan(app: Context, ts: Long) {
        saveGroupPlans(app, groupPlans(app).filter { it.ts != ts })
    }

    fun clearGroupPlans(app: Context) {
        saveGroupPlans(app, emptyList())
    }
}
