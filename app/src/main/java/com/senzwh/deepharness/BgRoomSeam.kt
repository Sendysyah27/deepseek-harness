package com.senzwh.deepharness

import harness.core.RustCore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Slice 12 (vc329) — helper seam otak latar/room ke core Rust
 * (`bgBrainJson`, `groupChatJson`, `ocCoordJson`;
 * modul `bg_brain`, `group_chat`, `oc_coord`).
 *
 * Pola: SATU tempat membangun request JSON + mem-parse respons.
 * `{"ok":false}`/null = Rust tak-tersedia / operasi gagal ->
 * pemanggil WAJIB fallback ke implementasi Kotlin lama (`*Kotlin`
 * di `BgBrain`/`GroupChat`/`OcCoord`); saklar hanya dimatikan bila
 * seam mengembalikan `null`, JSON tak-terparse, atau exception di seam.
 *
 * AMBIGUITAS `null`: `spinningPattern` dan `parsePermEvent`
 * mengembalikan `null` sebagai HASIL SAH Kotlin (tidak ada pola /
 * bukan event izin) MAUPUN sebagai sinyal "seam gagal". Pemanggil
 * SELALU fallback ke `*Kotlin` bila seam null, dan `*Kotlin`
 * menghitung ulang hasil yang sama. Benar, hanya sedikit boros.
 *
 * Yang TIDAK lewat sini (tetap Kotlin, peta §2.6): WebView latar
 * BgBrain, notifikasi Android, dialog Views, thread/loop orkestrasi,
 * pemanggilan DB/network, dan semua callback listener.
 * Seam ini hanya membawa LOGIKA MURNI (string/angka/keputusan).
 */
object BgBrainSeam {
    /** Saklar slice 12 — default ikut `RustCore.available`. */
    var rustBgBrain = RustCore.available

    private fun opRaw(request: JSONObject): JSONObject? {
        if (!rustBgBrain) return null
        return try {
            val raw = RustCore.bgBrainJson(request.toString()) ?: run { rustBgBrain = false; return null }
            JSONObject(raw)
        } catch (e: Exception) {
            rustBgBrain = false
            null
        }
    }

    /** Op murni generik: `{"ok":true,...}` atau null (fallback).
     *  `ok:false` = operasi gagal (input tak-valid) -> null TANPA
     *  mematikan saklar (bukan cacat seam). */
    private fun opValue(op: String, build: JSONObject.() -> Unit): JSONObject? {
        val req = JSONObject().put("op", op)
        req.build()
        val o = opRaw(req) ?: return null
        if (!o.optBoolean("ok")) return null
        return o
    }

    private fun opStr(op: String, build: JSONObject.() -> Unit): String? {
        val o = opValue(op, build) ?: return null
        return o.optString("value")
    }

    private fun opObj(op: String, build: JSONObject.() -> Unit): JSONObject? {
        val o = opValue(op, build) ?: return null
        return o.optJSONObject("value")
    }

    /** `BgBrain.cutAtMessageBoundary`: potong di `\n` bila `cut > max/2`,
     *  bila tidak `take(max)` (satuan UTF-16). */
    fun cutBoundary(text: String, max: Int): String? =
        opStr("cut_boundary") { put("text", text); put("max", max) }

    /** `BgBrain.trimTranscriptVc263`: (teks baru, char yang dibuang). */
    fun trimTranscript(text: String, maxChars: Int): Pair<String, Int>? {
        val v = opObj("trim_transcript") { put("text", text); put("max_chars", maxChars) } ?: return null
        return v.optString("text") to v.optInt("dropped")
    }

    /** `BgBrain.bgAllowed` bagian jam hening: `qs..qe` (jam hening
     *  23:00-05:00 bila `qs/qe` -1). */
    fun bgQuietHour(qs: Int, qe: Int, hour: Int): Boolean? {
        val o = opValue("bg_quiet_hour") { put("qs", qs); put("qe", qe); put("hour", hour) } ?: return null
        return o.optBoolean("value")
    }

    /** `BgBrain.batteryOk`: `level*100/scale>=20 || plugged!=0`
     *  (level<0 -> true). */
    fun batteryFormula(level: Int, scale: Int, plugged: Int): Boolean? {
        val o = opValue("battery_formula") { put("level", level); put("scale", scale); put("plugged", plugged) } ?: return null
        return o.optBoolean("value")
    }

    /** Keputusan gerbang `BgBrain.submit`: cooldown 15 mnt, retry
     *  sessionDead 15 mnt, overCapBg, bgAllowed, jobsOff, baterai.
     *  `decision`: "proceed" | "cooldown" | "sesi mati" | "kuota" |
     *  "gerbang" | "job dimatikan" | "baterai". `governorNotify` =
     *  pesan `notifyGovernorDelay` (hanya "kuota"/"gerbang"). */
    data class SubmitDecision(val decision: String, val clearSessionDead: Boolean, val governorNotify: String?)

    fun submitDecision(
        lastRl: Long, now: Long, sessionDead: Boolean, lastRetry: Long,
        overCapBg: Boolean, bgAllowed: Boolean, jobsOff: Boolean,
        origin: String, batteryOk: Boolean
    ): SubmitDecision? {
        val v = opObj("submit_decision") {
            put("last_rl", lastRl); put("now", now); put("session_dead", sessionDead)
            put("last_retry", lastRetry); put("over_cap_bg", overCapBg); put("bg_allowed", bgAllowed)
            put("jobs_off", jobsOff); put("origin", origin); put("battery_ok", batteryOk)
        } ?: return null
        return SubmitDecision(
            v.optString("decision"),
            v.optBoolean("clear_session_dead"),
            if (v.isNull("governor_notify")) null else v.optString("governor_notify")
        )
    }

    /** `BgBrain.continuationPrompt`: anchor + baris pertama tugas +
     *  blok work-notes + `[RIWAYAT]` + transkrip. */
    fun continuationPromptBg(transcript: String, workNotes: String): String? =
        opStr("continuation_prompt_bg") { put("transcript", transcript); put("work_notes", workNotes) }

    /** `honestCapStopBgVc277` (fun lokal `loopInnerVc263`): pesan
     *  berhenti-jujur cap langkah latar. */
    fun honestCapStopBg(done: String, steps: Int, task300: String, transcriptTail: String): String? =
        opStr("honest_cap_stop_bg") {
            put("done", done); put("steps", steps); put("task300", task300); put("transcript_tail", transcriptTail)
        }

    /** Tabel keputusan `when` jalur tunggal `loopInnerVc263`.
     *  Route: "execute" | "msg_analyze_photo" |
     *  "msg_delete_session" | "park_memory_jobtools" | "memory_write" |
     *  "park_memory_noprop" | "park_risky". `message` hanya untuk dua
     *  pesan tetap + pesan proposal memori. */
    fun bgToolRoute(
        tool: String, gatesDisabled: Boolean, isMemoryWrite: Boolean,
        jobToolsNull: Boolean, jobToolsContains: Boolean, isRisky: Boolean,
        autoAllowed: Boolean, hasFactProposal: Boolean, propKey: String, propValue: String
    ): Pair<String, String?>? {
        val v = opObj("bg_tool_route") {
            put("tool", tool); put("gates_disabled", gatesDisabled); put("is_memory_write", isMemoryWrite)
            put("job_tools_null", jobToolsNull); put("job_tools_contains", jobToolsContains)
            put("is_risky", isRisky); put("auto_allowed", autoAllowed)
            put("has_fact_proposal", hasFactProposal); put("prop_key", propKey); put("prop_value", propValue)
        } ?: return null
        return v.optString("route") to (if (v.isNull("message")) null else v.optString("message"))
    }

    /** Keputusan per kunci `BgBrain.watchdogTick`. Action: "skip" |
     *  "cleanup" | "notify". `reason` hanya untuk "notify" (teks
     *  alasan verbatim). */
    fun watchdogDecision(start: Long, hb: Long, lastOk: Long, now: Long, notified: Boolean): Pair<String, String?>? {
        val v = opObj("watchdog_decision") {
            put("start", start); put("hb", hb); put("last_ok", lastOk); put("now", now); put("notified", notified)
        } ?: return null
        return v.optString("action") to (if (v.isNull("reason")) null else v.optString("reason"))
    }

    /** `BgBrain.queueBgFactProposal`: (json array baru, jumlah entri).
     *  `count` -1 + json null = input tak-valid (Kotlin mengembalikan
     *  -1 tanpa menulis prefs — sama seperti fallback). */
    fun queueFactProposal(raw: String, key: String, value: String): Pair<String?, Int>? {
        val v = opObj("queue_fact_proposal") { put("raw", raw); put("key", key); put("value", value) } ?: return null
        return (if (v.isNull("json")) null else v.optString("json")) to v.optInt("count")
    }

    /** Judul + teks `BgBrain.deliver`: judul dari `notifTitle`/`origin`
     *  + fallback jawaban kosong + prefix route gagal. */
    fun deliverTitle(notifTitle: String?, origin: String, routeFailed: Boolean, answer: String): Pair<String, String>? {
        val v = opObj("deliver_title") {
            if (notifTitle != null) put("notif_title", notifTitle)
            put("origin", origin); put("route_failed", routeFailed); put("answer", answer)
        } ?: return null
        return v.optString("title") to v.optString("text")
    }
}

object GroupChatSeam {
    /** Saklar slice 12 — default ikut `RustCore.available`. */
    var rustGroupChat = RustCore.available

    private fun opRaw(request: JSONObject): JSONObject? {
        if (!rustGroupChat) return null
        return try {
            val raw = RustCore.groupChatJson(request.toString()) ?: run { rustGroupChat = false; return null }
            JSONObject(raw)
        } catch (e: Exception) {
            rustGroupChat = false
            null
        }
    }

    private fun opValue(op: String, build: JSONObject.() -> Unit): JSONObject? {
        val req = JSONObject().put("op", op)
        req.build()
        val o = opRaw(req) ?: return null
        if (!o.optBoolean("ok")) return null
        return o
    }

    private fun opStr(op: String, build: JSONObject.() -> Unit): String? {
        val o = opValue(op, build) ?: return null
        return o.optString("value")
    }

    private fun opObj(op: String, build: JSONObject.() -> Unit): JSONObject? {
        val o = opValue(op, build) ?: return null
        return o.optJSONObject("value")
    }

    private fun membersJson(members: List<GroupChat.Member>): JSONArray {
        val a = JSONArray()
        for (m in members) a.put(JSONObject().put("id", m.id).put("name", m.name).put("accent", m.accent))
        return a
    }

    private fun strArray(list: List<String>): JSONArray {
        val a = JSONArray()
        for (s in list) a.put(s)
        return a
    }

    /** `GroupChat.parseMentions`: id anggota yang disebut, urut posisi
     *  kemunculan pertama (`@agen 4`/`@agen4` untuk agen4). */
    fun parseMentions(text: String, members: List<GroupChat.Member>): List<String>? {
        val o = opValue("parse_mentions") { put("text", text); put("members", membersJson(members)) } ?: return null
        val a = o.optJSONArray("value") ?: return null
        return (0 until a.length()).map { a.optString(it) }
    }

    /** `GroupChat.Member.personaNameFor(id)` + `accentFor(name)`. */
    fun memberInfo(members: List<GroupChat.Member>, id: String, name: String): Pair<String, String>? {
        val v = opObj("member_info") { put("members", membersJson(members)); put("id", id); put("name", name) } ?: return null
        return v.optString("persona_name") to v.optString("accent")
    }

    /** Pemilihan antrean `GroupChat.startRound`: (antrean, catatan,
     *  chairId). Catatan verbatim ("Tidak ada anggota grup yang aktif —
     *  aktifkan lewat ⚙️ Setting Grup." / "💤 {nama} nonaktif di grup —
     *  aktifkan lewat ⚙️ Setting Grup."). */
    fun selectQueue(
        mentioned: List<String>, allOn: Boolean, active: List<String>, members: List<GroupChat.Member>
    ): Triple<List<String>, List<String>, String>? {
        val v = opObj("select_queue") {
            put("mentioned", strArray(mentioned)); put("all_on", allOn)
            put("active", strArray(active)); put("members", membersJson(members))
        } ?: return null
        val q = v.optJSONArray("queue") ?: return null
        val n = v.optJSONArray("notes") ?: return null
        return Triple(
            (0 until q.length()).map { q.optString(it) },
            (0 until n.length()).map { n.optString(it) },
            v.optString("chair_id")
        )
    }

    /** Chair-hop `GroupChat.startRound`: id anggota yang diundang
     *  (subset `responded`, maks 2, tanpa yang sudah di antrean). */
    fun chairHop(
        clean: String, allOn: Boolean, mentionedEmpty: Boolean, isChair: Boolean,
        bubbles: Int, maxBubbles: Int, responded: List<String>, queue: List<String>,
        members: List<GroupChat.Member>
    ): List<String>? {
        val o = opValue("chair_hop") {
            put("clean", clean); put("all_on", allOn); put("mentioned_empty", mentionedEmpty)
            put("is_chair", isChair); put("bubbles", bubbles); put("max_bubbles", maxBubbles)
            put("responded", strArray(responded)); put("queue", strArray(queue))
            put("members", membersJson(members))
        } ?: return null
        val a = o.optJSONArray("value") ?: return null
        return (0 until a.length()).map { a.optString(it) }
    }

    /** `GroupChat.buildPrompt`: perakitan prompt grup penuh. `catSlim`
     *  HARUS sudah dipotong di Kotlin (`catalogForTask` + backstop
     *  30k vc321 — JANGAN kirim katalog penuh dari Rust). `memories`
     *  HARUS sudah di-redact di Kotlin (Rust tidak me-redact baris
     *  memori). Blok `BATCH_TERMINAL_RULE` + `modeBebasBlock` dirakit
     *  SESUDAH cap 60k; transkrip drop-to-fit <=200000 (min 5 baris). */
    fun buildPrompt(
        meName: String, persona: String, behaviorsBlock: String, brainBlocks: String,
        ctxLabelRules: String, pagingTotalRule: String, pinned: String, worldStateLine: String,
        memories: List<String>, facts: String, goals: String, workNotes: String,
        toolsEnabled: Boolean, catSlim: String, skills: String, modeBebasBlock: String,
        batchTerminalRule: String, transcriptRows: List<String>, userText: String
    ): String? = opStr("build_prompt") {
        put("me_name", meName); put("persona", persona); put("behaviors_block", behaviorsBlock)
        put("brain_blocks", brainBlocks); put("ctx_label_rules", ctxLabelRules)
        put("paging_total_rule", pagingTotalRule); put("pinned", pinned)
        put("world_state_line", worldStateLine); put("memories", strArray(memories))
        put("facts", facts); put("goals", goals); put("work_notes", workNotes)
        put("tools_enabled", toolsEnabled); put("cat_slim", catSlim); put("skills", skills)
        put("mode_bebas_block", modeBebasBlock); put("batch_terminal_rule", batchTerminalRule)
        put("transcript_rows", strArray(transcriptRows)); put("user_text", userText)
    }

    /** `GroupChat.continuationPromptVc321`: anchor + "\n\n" + suffix. */
    fun continuationPromptRoom(userText: String, suffix: String): String? =
        opStr("continuation_prompt_room") { put("user_text", userText); put("suffix", suffix) }

    /** `honestCapStopGrpVc277` (fun lokal `startRound`): pesan
     *  berhenti-jujur cap 3 langkah per anggota per ronde. */
    fun honestCapStopGroup(done: String, userText300: String): String? =
        opStr("honest_cap_stop_group") { put("done", done); put("user_text300", userText300) }

    /** `GroupChat.retrieveMemories`: saring baris memori (trim,
     *  non-kosong, bukan "Tidak ada memori"/"Kata kunci", maks 10 ×
     *  1000 char). */
    fun retrieveMemoriesFilter(raw: String): List<String>? {
        val o = opValue("retrieve_memories_filter") { put("raw", raw) } ?: return null
        val a = o.optJSONArray("value") ?: return null
        return (0 until a.length()).map { a.optString(it) }
    }

    /** `GroupChat.isQuietHour`: `h >= 23 || h < 5`. */
    fun quietHourGroup(hour: Int): Boolean? {
        val o = opValue("quiet_hour_group") { put("hour", hour) } ?: return null
        return o.optBoolean("value")
    }

    /** Gerbang `GroupChat.runBackgroundRound`. Decision: "proceed" |
     *  "busy" | "blocked". `reason` = teks blockReason verbatim;
     *  `jobStatus` = status `logJob` ("sibuk"/"diblokir"). */
    fun bgRoundGate(running: Boolean, f1Off: Boolean, jobsOff: Boolean, hour: Int, batteryOk: Boolean): Triple<String, String?, String?>? {
        val v = opObj("bg_round_gate") {
            put("running", running); put("f1_off", f1Off); put("jobs_off", jobsOff)
            put("hour", hour); put("battery_ok", batteryOk)
        } ?: return null
        return Triple(
            v.optString("decision"),
            if (v.isNull("reason")) null else v.optString("reason"),
            if (v.isNull("job_status")) null else v.optString("job_status")
        )
    }

    /** Keputusan `GroupChat.runGroupTool`. Route: "execute" |
     *  "execute_gates" | "dialog" | "msg_analyze_photo".
     *  "execute_gates" = jalur GATES_DISABLED (Kotlin wajib menulis
     *  ledger sebelum execute); "execute" = shortcut tanpa ledger. */
    fun groupToolRoute(
        tool: String, gatesDisabled: Boolean, isMemoryWrite: Boolean,
        isAutoAllowBlocked: Boolean, autoApproveOn: Boolean, isRisky: Boolean, autoAllowValid: Boolean
    ): Pair<String, String?>? {
        val v = opObj("group_tool_route") {
            put("tool", tool); put("gates_disabled", gatesDisabled); put("is_memory_write", isMemoryWrite)
            put("is_auto_allow_blocked", isAutoAllowBlocked); put("auto_approve_on", autoApproveOn)
            put("is_risky", isRisky); put("auto_allow_valid", autoAllowValid)
        } ?: return null
        return v.optString("route") to (if (v.isNull("message")) null else v.optString("message"))
    }

    /** `GroupChat.saveLastRoundAsPlan`: (pesan hasil, json plan baru
     *  atau null bila tidak ada yang disimpan). */
    fun saveLastRoundPlan(rows: JSONArray, plans: JSONArray, nowMs: Long): Pair<String, String?>? {
        val v = opObj("save_last_round_plan") { put("rows", rows); put("plans", plans); put("now_ms", nowMs) } ?: return null
        return v.optString("message") to (if (v.isNull("plans_json")) null else v.optString("plans_json"))
    }

    /** `GroupChat.groupPlans`: parse + sort desc ts (stabil). */
    fun parsePlans(raw: String): List<GroupChat.GroupPlan>? {
        val o = opValue("parse_plans") { put("raw", raw) } ?: return null
        val a = o.optJSONArray("value") ?: return null
        return (0 until a.length()).map {
            val p = a.optJSONObject(it) ?: JSONObject()
            GroupChat.GroupPlan(p.optString("title"), p.optString("body"), p.optLong("ts"))
        }
    }

    /** Render notifikasi grup `notifyGroupDone`: 3 baris + "(+N
     *  lainnya...)" bila lebih. */
    fun renderNotif3Lines(body: String): String? =
        opStr("render_notif_3lines") { put("body", body) }
}
object OcCoordSeam {
    /** Saklar slice 12 — default ikut `RustCore.available`. */
    var rustOcCoord = RustCore.available

    private fun opRaw(request: JSONObject): JSONObject? {
        if (!rustOcCoord) return null
        return try {
            val raw = RustCore.ocCoordJson(request.toString()) ?: run { rustOcCoord = false; return null }
            JSONObject(raw)
        } catch (e: Exception) {
            rustOcCoord = false
            null
        }
    }

    private fun opValue(op: String, build: JSONObject.() -> Unit): JSONObject? {
        val req = JSONObject().put("op", op)
        req.build()
        val o = opRaw(req) ?: return null
        if (!o.optBoolean("ok")) return null
        return o
    }

    private fun opStr(op: String, build: JSONObject.() -> Unit): String? {
        val o = opValue(op, build) ?: return null
        return o.optString("value")
    }

    private fun opObj(op: String, build: JSONObject.() -> Unit): JSONObject? {
        val o = opValue(op, build) ?: return null
        return o.optJSONObject("value")
    }

    /** `OcCoord.resultNotifIdFor`: `(40000 + taskId).toInt()` (wrap). */
    fun resultNotifIdFor(taskId: Long): Int? {
        val o = opValue("result_notif_id") { put("task_id", taskId) } ?: return null
        return o.optInt("value")
    }

    /** `OcCoord.RULE_DEFAULTS` (10 entri, urutan sumber). */
    fun ruleDefaults(): List<Pair<String, String>>? {
        val o = opValue("rule_defaults") {} ?: return null
        val a = o.optJSONArray("value") ?: return null
        return (0 until a.length()).map {
            val p = a.optJSONArray(it) ?: JSONArray()
            p.optString(0) to p.optString(1)
        }
    }

    /** Sanitasi `OcCoord.rule`: path_mode resmi/mandiri, auto_approve
     *  on/true/yes/aktif -> "" (bila tidak, raw TANPA trim), lain ->
     *  raw apa adanya. */
    fun sanitizeRule(key: String, raw: String): String? =
        opStr("sanitize_rule") { put("key", key); put("raw", raw) }

    /** `OcCoord.ruleInt`: sanitasi + `trim().toIntOrNull()` + default. */
    fun ruleInt(key: String, raw: String, def: Int): Int? {
        val o = opValue("rule_int") { put("key", key); put("raw", raw); put("def", def) } ?: return null
        return o.optInt("value")
    }

    /** `OcCoord.spinningPatternVc277`: cuplikan baris pertama yang
     *  norm-nya muncul >=3x (baris <20 char diabaikan), atau null =
     *  tidak ada pola ATAU seam gagal -> pemanggil fallback. */
    fun spinningPattern(text: String): String? {
        val o = opValue("spinning_pattern") { put("text", text) } ?: return null
        return if (o.isNull("value")) null else o.optString("value")
    }

    /** Satu langkah `OcSpinWatchVc303.observe`: (hit, state JSON baru).
     *  State JSON `{"counts":{...},"first_seen":{...},"pending":"..."}`. */
    fun spinObserve(stateJson: String, chunk: String): Pair<String?, String>? {
        val v = opObj("spin_observe") { put("state", stateJson); put("chunk", chunk) } ?: return null
        return (if (v.isNull("hit")) null else v.optString("hit")) to v.optString("state")
    }

    /** `OcSpinWatchVc303.flush`: (hit, state JSON baru — pending
     *  selalu dibersihkan walau norm <20). */
    fun spinFlush(stateJson: String): Pair<String?, String>? {
        val v = opObj("spin_flush") { put("state", stateJson) } ?: return null
        return (if (v.isNull("hit")) null else v.optString("hit")) to v.optString("state")
    }

    /** `OcTaskBudgetVc303.overSteps`: `assistantMessages > max`. */
    fun budgetOver(assistantMessages: Int, maxSteps: Int): Boolean? {
        val o = opValue("budget_over") { put("assistant_messages", assistantMessages); put("max_steps", maxSteps) } ?: return null
        return o.optBoolean("value")
    }

    /** `OcCoord.looksFailedVc262`: 5 jarum (`❌` dll). */
    fun looksFailed(text: String): Boolean? {
        val o = opValue("looks_failed") { put("text", text) } ?: return null
        return o.optBoolean("value")
    }

    /** `OcCoord.isQuietNow`: parse spec jam hening + jam saat ini
     *  (start==end -> false). */
    fun quietNow(spec: String, hour: Int): Boolean? {
        val o = opValue("quiet_now") { put("spec", spec); put("hour", hour) } ?: return null
        return o.optBoolean("value")
    }

    /** `OcCoord.incrementalAssistantTextVc303`: teks asisten baru
     *  setelah baseline (gabung per baris, tiap baris take(3000)). */
    fun incrementalText(msgs: JSONArray, baseline: Int): String? =
        opStr("incremental_text") { put("msgs", msgs); put("baseline", baseline) }

    /** `OcCoord.collectResult`: (teks hasil, ctxSize terakhir). */
    fun collectResult(msgs: JSONArray, baseline: Int): Pair<String, Long>? {
        val v = opObj("collect_result") { put("msgs", msgs); put("baseline", baseline) } ?: return null
        return v.optString("text") to v.optLong("ctx_size")
    }

    /** Pindaian `OcCoord.awaitCompletion`: (jumlah pesan asisten,
     *  finishedSeen, agentError terakhir — null bila tidak ada). */
    fun scanMessages(msgs: JSONArray, baseline: Int): Triple<Int, Boolean, String?>? {
        val v = opObj("scan_messages") { put("msgs", msgs); put("baseline", baseline) } ?: return null
        return Triple(
            v.optInt("asst_count"), v.optBoolean("finished_seen"),
            if (v.isNull("agent_error")) null else v.optString("agent_error")
        )
    }

    /** Badan akhir `OcCoord.awaitCompletion`: (status, body).
     *  Status: "done" | "failed" | "cancelled" | "timeout". */
    fun awaitBody(txt: String, timedOut: Boolean, sid: String): Pair<String, String>? {
        val v = opObj("await_body") { put("txt", txt); put("timed_out", timedOut); put("sid", sid) } ?: return null
        return v.optString("status") to v.optString("body")
    }

    /** Status jujur `OcCoord.finishTask`: (status, resultFull).
     *  `spinning` = cuplikan `spinningPatternVc277` (atau null). */
    fun honestStatus(status: String, spinning: String?, resultFull: String): Pair<String, String>? {
        val v = opObj("honest_status") {
            put("status", status)
            if (spinning != null) put("spinning", spinning)
            put("result_full", resultFull)
        } ?: return null
        return v.optString("status") to v.optString("result")
    }

    /** Gerbang `OcCoord.runLoop` (tugas lanjutan): (action, note,
     *  delaySec). Action: "run" | "delay_battery" | "delay_quiet". */
    fun runloopGate(
        continuation: Boolean, batteryPct: Int, battMin: Int,
        quietSpec: String, hour: Int, delaySec: Int
    ): Triple<String, String?, Long>? {
        val v = opObj("runloop_gate") {
            put("continuation", continuation); put("battery_pct", batteryPct); put("batt_min", battMin)
            put("quiet_spec", quietSpec); put("hour", hour); put("delay_sec", delaySec)
        } ?: return null
        return Triple(
            v.optString("action"),
            if (v.isNull("note")) null else v.optString("note"),
            v.optLong("delay_sec")
        )
    }

    /** Rotasi sesi `OcCoord.runTask`: (perlu buat sesi baru, catatan
     *  rotasi, judul sesi baru atau null bila tidak perlu). */
    data class SessionRotate(val needCreate: Boolean, val rotateNote: String, val title: String?)

    fun sessionRotate(sid: String, prevCtx: Long, ctxLimit: Long, taskId: Long, taskText: String): SessionRotate? {
        val v = opObj("session_rotate") {
            put("sid", sid); put("prev_ctx", prevCtx); put("ctx_limit", ctxLimit)
            put("task_id", taskId); put("task_text", taskText)
        } ?: return null
        return SessionRotate(
            v.optBoolean("need_create"), v.optString("rotate_note"),
            if (v.isNull("title")) null else v.optString("title")
        )
    }

    /** Pohon keputusan `OcCoord.ensureServe` dua fase. Fase 0 = probe
     *  awal (action "start_4099"/"start_4097" = Kotlin menyalakan
     *  serve + tidur 6 dtk + probe ulang -> fase 1). Fase 1 = sesudah
     *  nyala. Action: "return" | "start_4099" | "start_4097" | "fail".
     *  `basePort` null = tidak ada jalur hidup. */
    data class ServeSelect(val basePort: Int?, val note: String, val action: String)

    fun serveSelect(phase: Int, mode: String, p4099: Boolean, p4097: Boolean, p4096: Boolean): ServeSelect? {
        val v = opObj("serve_select") {
            put("phase", phase); put("mode", mode); put("p4099", p4099); put("p4097", p4097); put("p4096", p4096)
        } ?: return null
        return ServeSelect(
            if (v.isNull("base_port")) null else v.optInt("base_port"),
            v.optString("note"), v.optString("action")
        )
    }

    /** `OcCoord.probeCached` (baca): (cache kena, peta port->hidup).
     *  `probes` null bila cache tidak kena. */
    data class ProbeCacheRead(val hit: Boolean, val probes: Map<Int, Boolean>?)

    fun probeCacheRead(force: Boolean, nowMs: Long, cachedTs: Long, cachedRaw: String): ProbeCacheRead? {
        val v = opObj("probe_cache_read") {
            put("force", force); put("now_ms", nowMs); put("cached_ts", cachedTs); put("cached_raw", cachedRaw)
        } ?: return null
        val hit = v.optBoolean("hit")
        val probes = if (!hit || v.isNull("probes")) null else {
            val p = v.optJSONObject("probes") ?: return null
            val m = mutableMapOf<Int, Boolean>()
            val keys = p.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                m[k.toIntOrNull() ?: continue] = p.optBoolean(k)
            }
            m
        }
        return ProbeCacheRead(hit, probes)
    }

    /** `OcCoord.probeCached` (tulis): string cache `"4097=..,4096=..,4100=.."`. */
    fun probeCacheWrite(p4097: Boolean, p4096: Boolean, p4099: Boolean): String? =
        opStr("probe_cache_write") { put("p4097", p4097); put("p4096", p4096); put("p4099", p4099) }

    /** `OcCoord.statusText`: teks panel status (aturan hilang ->
     *  `"null"`; `recent` = array `{id, status, text, result}`). */
    fun statusText(
        mode: String, p4097: Boolean, p4096: Boolean, p4099: Boolean,
        curTask: Long, queued: Long, running: Long, pendingTitle: String,
        rules: JSONObject, gatesDisabled: Boolean, recent: JSONArray
    ): String? = opStr("status_text") {
        put("mode", mode); put("p4097", p4097); put("p4096", p4096); put("p4099", p4099)
        put("cur_task", curTask); put("queued", queued); put("running", running)
        put("pending_title", pendingTitle); put("rules", rules)
        put("gates_disabled", gatesDisabled); put("recent", recent)
    }

    /** `OcCoord.taskResultText`: teks hasil tugas (task null =
     *  "tidak ditemukan"; done/failed/cancelled -> label + hasil;
     *  lain -> status + intipan progres). */
    fun taskResultText(id: Long, task: JSONObject?, peek: String): String? =
        opStr("task_result_text") {
            put("id", id)
            if (task != null) put("task", task)
            put("peek", peek)
        }

    /** `OcCoord.peekSessionText`: teks asisten terakhir (take 700). */
    fun peekText(msgs: JSONArray): String? =
        opStr("peek_text") { put("msgs", msgs) }

    /** Parse event izin SSE `OcCoord`: (permId, judul, detail) atau
     *  null = bukan event izin ATAU seam gagal -> pemanggil fallback
     *  ke `*Kotlin` (hitung ulang, hasil sama). */
    fun parsePermEvent(line: String, sid: String): Triple<String, String, String>? {
        val v = opObj("parse_perm_event") { put("line", line); put("sid", sid) } ?: return null
        return Triple(v.optString("perm_id"), v.optString("title"), v.optString("detail"))
    }

    /** Basi izin `OcCoord`: `ts > 0 && now - ts > ttlMin * 60_000`. */
    fun permStale(ts: Long, nowMs: Long, ttlMin: Int): Boolean? {
        val o = opValue("perm_stale") { put("ts", ts); put("now_ms", nowMs); put("ttl_min", ttlMin) } ?: return null
        return o.optBoolean("value")
    }

    /** Keputusan `OcCoord.handlePermission`: "auto_gate" |
     *  "auto_pattern" | "park". Dua varian auto dibedakan agar Kotlin
     *  menulis pesan log yang tepat ("gate vc247 nonaktif" vs "pola
     *  aturan"). */
    fun permDecision(gatesDisabled: Boolean, autoApprove: String, title: String): String? =
        opStr("perm_decision") { put("gates_disabled", gatesDisabled); put("auto_approve", autoApprove); put("title", title) }

    /** `OcCoord.batteryPct`: `level*100/scale` (wrap) atau 100. */
    fun batteryPct(level: Int, scale: Int): Int? {
        val o = opValue("battery_pct") { put("level", level); put("scale", scale) } ?: return null
        return o.optInt("value")
    }
}
