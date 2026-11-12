package com.senzwh.deepharness

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * vc226 — Panel Approval "Apa yang Butuh Manusia" (BRIEF-PANEL-APPROVAL,
 * disetujui user 2026-11-01). Pola attention budget dari
 * deepseek-ai/dsh-superagent-prototype (dipinjam polanya, bukan kodenya):
 * satu inbox untuk semua approval pending lintas room/sesi + Mandat
 * (otorisasi bermandat: sekali setujui satu kelas aksi di satu scope,
 * berlaku 7 hari, bisa dicabut kapan saja).
 *
 * ATURAN KERAS (spek §5):
 *  - Permintaan approval TIDAK PERNAH diposting sebagai pesan chat. File ini
 *    tidak memanggil addBubble/addMsg/appendMessage sama sekali; pencatatan
 *    hanya ke store ini + audit log room (Automation.logAction, bukan chat)
 *    + Logcat.
 *  - Kelas risiko tinggi TIDAK BISA dimandatkan: hapus_data, ubah_pengaturan,
 *    keluar_uang, kirim_luar. Tombol "setujui selalu" tidak tampil untuk
 *    kelas ini, dan createMandate menolaknya.
 *  - vc227 (Gas user 2026-11-01, kunci mandat shell): kelas shell juga
 *    TIDAK BISA dimandatkan: eksekusi_shell (run_terminal, run_privileged),
 *    delegate_session, chain_tasks, oc_checkpoint. Mandat shell lama tidak
 *    dihormati jalan pintas dan tidak tampil di daftar mandat aktif; tool
 *    shell tetap bisa dipakai lewat dialog + PIN HIGH normal.
 *  - wait_confirm (konfirmasi_manual) juga tidak bisa dimandatkan: itu
 *    checkpoint manusia, bukan aksi — memandatkannya berarti menekan tombol
 *    "Udah, lanjut" tanpa user benar-benar melakukan langkah manualnya.
 *  - Kill switch (prefs harness f1Off) mengalahkan semua: panel menampilkan
 *    status killed dan tombol setujui tidak bisa dipakai.
 *  - Approval pending kedaluwarsa saat sesi berakhir (expireSession, dipanggil
 *    dari switchSession) atau saat TTL-nya habis (khusus wait_confirm).
 *  - Mandat terikat scope: cocok bila sessionId sama DAN (roomId sama atau
 *    salah satu sisi kosong — jalur chat tidak punya roomId).
 */
enum class PanelRisk { RENDAH, SEDANG, TINGGI }

data class ApprovalItem(
    val id: String,
    val roomId: String,
    val sessionId: Long,
    val agent: String,
    val tool: String,
    val kelasAksi: String,
    val risiko: PanelRisk,
    val ringkasan: String,
    val alasan: String,
    val dimintaPada: Long,
    val expiresAt: Long,
    var status: String,
    val argsFull: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("roomId", roomId); put("sessionId", sessionId)
        put("agent", agent); put("tool", tool); put("kelasAksi", kelasAksi)
        put("risiko", risiko.name); put("ringkasan", ringkasan); put("alasan", alasan)
        put("dimintaPada", dimintaPada); put("expiresAt", expiresAt); put("status", status); put("argsFull", argsFull)
    }

    companion object {
        fun fromJson(o: JSONObject): ApprovalItem? = try {
            ApprovalItem(
                o.optString("id"), o.optString("roomId"), o.optLong("sessionId"),
                o.optString("agent"), o.optString("tool"), o.optString("kelasAksi"),
                try { PanelRisk.valueOf(o.optString("risiko")) } catch (e: Exception) { PanelRisk.SEDANG },
                o.optString("ringkasan"), o.optString("alasan"), o.optLong("dimintaPada"),
                o.optLong("expiresAt"), o.optString("status", "pending"), o.optString("argsFull")
            )
        } catch (e: Exception) { null }
    }
}

data class Mandate(
    val id: String,
    val roomId: String,
    val sessionId: Long,
    val kelasAksi: String,
    val syarat: String,
    val diberiPada: Long,
    val berlakuSampai: Long,
    var status: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("roomId", roomId); put("sessionId", sessionId)
        put("kelasAksi", kelasAksi); put("syarat", syarat)
        put("diberiPada", diberiPada); put("berlakuSampai", berlakuSampai); put("status", status)
    }

    companion object {
        fun fromJson(o: JSONObject): Mandate? = try {
            Mandate(
                o.optString("id"), o.optString("roomId"), o.optLong("sessionId"),
                o.optString("kelasAksi"), o.optString("syarat"), o.optLong("diberiPada"),
                o.optLong("berlakuSampai"), o.optString("status", "aktif")
            )
        } catch (e: Exception) { null }
    }
}

object ApprovalPanelStore {
    const val MANDATE_TTL_MS = 7L * 24 * 3600 * 1000

    /** Kelas aksi yang selalu minta approval per kejadian (spek §5.4). */
    val HIGH_RISK_CLASSES = setOf("hapus_data", "ubah_pengaturan", "keluar_uang", "kirim_luar")

    /** Kelas yang tidak bisa dimandatkan = risiko tinggi + checkpoint manual
     *  + kelas shell (vc227: mandat shell dikunci, selalu dialog + PIN HIGH). */
    private val MANDATE_BLOCKED_CLASSES = HIGH_RISK_CLASSES + setOf("konfirmasi_manual",
        "eksekusi_shell", "delegate_session", "chain_tasks", "oc_checkpoint")

    private const val PREFS = "approval_panel"
    private const val TAG = "ApprovalPanel"

    /** Callback resolusi in-memory (tidak dipersist): id -> (disetujui) -> Unit.
     *  Dipakai panel untuk melanjutkan giliran agen yang menunggu (dialog/float
     *  memakai jalurnya sendiri + markResolved, jadi callback tidak ganda). */
    private val callbacks = HashMap<String, (Boolean) -> Unit>()

    fun canMandate(kelasAksi: String): Boolean = kelasAksi !in MANDATE_BLOCKED_CLASSES

    // vc246 GELOMBANG 5 (XL-Panel): panel TIDAK BOLEH menampilkan tombol
    // Setujui untuk item yang callback-nya hilang (proses restart) —
    // dulu ketukan itu menandai "disetujui" tanpa eksekusi apa pun.
    // UI panel memakai hasCallback() untuk menandai item kedaluwarsa.
    fun hasCallback(id: String): Boolean = callbacks.containsKey(id)

    // ---------- vc229 R-04: autoAllow terkunci + mandat terikat argumen ----------
    const val AUTALLOW_TTL_MS = 24L * 3600 * 1000

    /** Kelas tool ini masuk daftar blokir mandat -> autoAllow-nya juga diblokir. */
    fun isAutoAllowBlocked(tool: String): Boolean = classForTool(tool) in MANDATE_BLOCKED_CLASSES

    /** autoAllow valid hanya bila: tidak diblokir, ada metadata (ts + sesi),
     *  belum lewat TTL 24 jam, dan sesinya sama (sessionId < 0 = tanpa ikatan
     *  sesi, dipakai jalur grup). Entri lawas tanpa metadata = TIDAK valid
     *  (sekali pakai memicu dialog ulang + pruning di pemanggil). */
    fun autoAllowValid(ctx: Context, tool: String, sessionId: Long): Boolean {
        if (isAutoAllowBlocked(tool)) return false
        val meta = try { JSONObject(prefs(ctx).getString("autoAllowMeta", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val e = meta.optJSONObject(tool) ?: return false
        val ts = e.optLong("ts", 0)
        if (ts <= 0 || System.currentTimeMillis() - ts > AUTALLOW_TTL_MS) return false
        val s = e.optLong("sessionId", -1L)
        if (sessionId >= 0 && s >= 0 && s != sessionId) return false
        return true
    }

    fun grantAutoAllow(ctx: Context, tool: String, sessionId: Long) {
        try {
            val meta = try { JSONObject(prefs(ctx).getString("autoAllowMeta", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
            meta.put(tool, JSONObject().put("ts", System.currentTimeMillis()).put("sessionId", sessionId))
            prefs(ctx).edit().putString("autoAllowMeta", meta.toString()).apply()
        } catch (e: Exception) { Log.w(TAG, "grantAutoAllow meta gagal: ${e.message}") }
        try {
            val hp = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            val set = HashSet(hp.getStringSet("autoAllowTools", emptySet()) ?: emptySet())
            set.add(tool)
            hp.edit().putStringSet("autoAllowTools", set).apply()
        } catch (e: Exception) { Log.w(TAG, "grantAutoAllow legacy gagal: ${e.message}") }
    }

    fun revokeAutoAllow(ctx: Context, tool: String) {
        try {
            val meta = try { JSONObject(prefs(ctx).getString("autoAllowMeta", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
            meta.remove(tool)
            prefs(ctx).edit().putString("autoAllowMeta", meta.toString()).apply()
        } catch (e: Exception) {}
        try {
            val hp = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            val set = HashSet(hp.getStringSet("autoAllowTools", emptySet()) ?: emptySet())
            set.remove(tool)
            hp.edit().putStringSet("autoAllowTools", set).apply()
        } catch (e: Exception) {}
    }

    fun clearAutoAllow(ctx: Context) {
        try { prefs(ctx).edit().remove("autoAllowMeta").apply() } catch (e: Exception) {}
        try { ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).edit().remove("autoAllowTools").apply() } catch (e: Exception) {}
    }

    /** Daftar autoAllow aktif (belum kedaluwarsa): (tool, diberiPada, sessionId). */
    fun activeAutoAllow(ctx: Context): List<Triple<String, Long, Long>> {
        val out = ArrayList<Triple<String, Long, Long>>()
        val meta = try { JSONObject(prefs(ctx).getString("autoAllowMeta", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val now = System.currentTimeMillis()
        val keys = meta.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val e = meta.optJSONObject(k) ?: continue
            val ts = e.optLong("ts", 0)
            if (ts > 0 && now - ts <= AUTALLOW_TTL_MS) out.add(Triple(k, ts, e.optLong("sessionId", -1L)))
        }
        return out.sortedByDescending { it.second }
    }

    /** Cocokkan kolom syarat mandat: segmen "tool=X" wajib sama dengan tool
     *  yang diminta; segmen "arg=Y" wajib terkandung di string argumen.
     *  Syarat kosong: diterima untuk kelas non-kirim_luar (mandat lawas),
     *  DITOLAK untuk kirim_luar (mandat kirim_luar tanpa ikatan tool = celah
     *  K-26 — harus dibuat ulang lewat panel dengan syarat tool=...). */
    private fun syaratMatches(syarat: String, tool: String, args: JSONObject, kelasAksi: String): Boolean {
        if (syarat.isBlank()) return kelasAksi != "kirim_luar"
        val argStr = args.toString()
        for (seg in syarat.split(";")) {
            val s = seg.trim()
            if (s.startsWith("tool=")) { if (s.substring(5) != tool) return false }
            else if (s.startsWith("arg=")) { if (!argStr.contains(s.substring(4))) return false }
        }
        return true
    }

    /** Jalan pintas mandat yang SADAR TOOL + ARGUMEN (vc229 R-04e).
     *  Pemanggil lama tanpa tool WAJIB memakai ini; hasActiveMandate lama
     *  di bawah menolak kirim_luar tanpa tool. */
    @Synchronized
    fun hasActiveMandateForTool(ctx: Context, roomId: String, sessionId: Long, tool: String, args: JSONObject): Boolean {
        val kelas = classForTool(tool)
        if (!canMandate(kelas)) return false
        return activeMandates(ctx).any { m ->
            m.kelasAksi == kelas && m.sessionId == sessionId &&
                (m.roomId == roomId || m.roomId.isBlank() || roomId.isBlank()) &&
                syaratMatches(m.syarat, tool, args, kelas)
        }
    }

    /** Mandat chat (roomId kosong) dari sesi yang ditinggalkan = kedaluwarsa
     *  saat switchSession (vc229 R-04f: tidak ada mandat yang hidup kembali
     *  saat sesi lama dibuka lagi). Mandat room (roomId terisi) tidak disentuh. */
    @Synchronized
    fun expireMandatesForSession(ctx: Context, sessionId: Long): Int {
        val mandates = loadMandates(ctx)
        var n = 0
        for (m in mandates) {
            if (m.status == "aktif" && m.sessionId == sessionId && m.roomId.isBlank()) {
                m.status = "kedaluwarsa"; n++
            }
        }
        if (n > 0) {
            saveMandates(ctx, mandates)
            logDecision(ctx, "kedaluwarsa_sesi_mandat", "-", "", sessionId, "$n mandat chat kedaluwarsa (pindah sesi)")
        }
        return n
    }

    fun riskLabel(r: PanelRisk): String = when (r) {
        PanelRisk.TINGGI -> "🔴 Risiko tinggi"
        PanelRisk.SEDANG -> "🟡 Perlu konfirmasi"
        PanelRisk.RENDAH -> "🟢 Risiko rendah"
    }

    /** Pemetaan nama tool -> kelas aksi spek. Tool tak dikenal = kelasnya sendiri. */
    fun classForTool(tool: String): String = when (tool) {
        "delete_session", "memory_forget" -> "hapus_data"
        "priv_settings", "priv_app", "coord_rule" -> "ubah_pengaturan"
        "gmail_send", "send_sms", "sync_memory", "http_request", "ask_operator", "api_write", "mcp_call" -> "kirim_luar"
        "run_terminal", "run_privileged", "priv_input" -> "eksekusi_shell"
        "reply_notification" -> "kirim_pesan"
        "wait_confirm" -> "konfirmasi_manual"
        else -> tool
    }

    // vc262 N-M20: TABEL RISIKO STATIS (tool -> risiko), independen dari
    // ToolGate.level() — level() selalu SAFE sejak vc247, jadi pemetaan
    // lama selalu melabeli RENDAH (termasuk otak_write SOUL/AGENTS).
    // Ini PELABELAN INFORMASIONAL saja di panel legacy/stale — bukan gate,
    // tidak menahan eksekusi apa pun.
    private val STATIC_HIGH = setOf("delete_session", "memory_forget", "run_terminal", "run_privileged", "priv_input", "priv_app", "priv_settings", "coord_rule", "sync_memory", "gmail_send", "send_sms", "http_request", "api_write", "mcp_call", "ask_operator", "delegate_session", "chain_tasks", "oc_checkpoint", "write_file", "edit_file", "generate_document", "generate_presentation", "schedule_task", "create_subroom", "run_team", "open_app", "tap", "swipe", "type_text", "tap_ref", "set_text_ref", "go_home", "go_back", "reply_notification", "otak_write", "forget_fact", "delete_skill", "delete_rule_trigger", "delete_geofence", "cancel_schedule", "cancel_reminder")
    private val STATIC_REVIEW = setOf("create_skill", "remember_fact", "learn_rule", "memory_add", "make_plan", "update_plan", "make_goal", "update_goal", "analyze_photo", "browser_tap", "browser_type", "browser_open", "delegate_task", "mcp_list_tools", "use_skill", "wait_confirm", "wait_change", "wait_for_app")

    fun riskForTool(tool: String): PanelRisk = when (tool) {
        in STATIC_HIGH -> PanelRisk.TINGGI
        in STATIC_REVIEW -> PanelRisk.SEDANG
        else -> PanelRisk.RENDAH
    }

    // vc237 M10 + vc262 N-M20: varian peka-argumen — otak_write SOUL/AGENTS
    // tampil TINGGI di panel, MEMORY.md tetap SEDANG. Dipakai register()
    // yang menyimpan argsFull; pemanggil tanpa argumen tetap memakai
    // varian lama (tabel statis di atas).
    fun riskForTool(tool: String, args: JSONObject): PanelRisk {
        if (tool == "otak_write") {
            val f = args.optString("file", "").trim().uppercase()
            if (f == "SOUL.MD" || f == "AGENTS.MD") return PanelRisk.TINGGI
            return PanelRisk.SEDANG
        }
        return riskForTool(tool)
    }

    fun isKilled(ctx: Context): Boolean =
        ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getBoolean("f1Off", false)

    fun timeStr(ts: Long): String = try {
        java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale("id", "ID")).format(java.util.Date(ts))
    } catch (e: Exception) { "" }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadItems(ctx: Context): MutableList<ApprovalItem> {
        val out = ArrayList<ApprovalItem>()
        val raw = prefs(ctx).getString("items", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            ApprovalItem.fromJson(arr.optJSONObject(i) ?: continue)?.let { out.add(it) }
        }
        return out
    }

    private fun saveItems(ctx: Context, items: List<ApprovalItem>) {
        val arr = JSONArray()
        for (it in items) arr.put(it.toJson())
        prefs(ctx).edit().putString("items", arr.toString()).apply()
    }

    private fun loadMandates(ctx: Context): MutableList<Mandate> {
        val out = ArrayList<Mandate>()
        val raw = prefs(ctx).getString("mandates", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            Mandate.fromJson(arr.optJSONObject(i) ?: continue)?.let { out.add(it) }
        }
        return out
    }

    private fun saveMandates(ctx: Context, mandates: List<Mandate>) {
        val arr = JSONArray()
        for (m in mandates) arr.put(m.toJson())
        prefs(ctx).edit().putString("mandates", arr.toString()).apply()
    }

    private fun logDecision(ctx: Context, action: String, itemId: String, roomId: String, sessionId: Long, detail: String) {
        try {
            val raw = prefs(ctx).getString("decisions", "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            val o = JSONObject()
            o.put("ts", System.currentTimeMillis()); o.put("action", action); o.put("itemId", itemId)
            o.put("roomId", roomId); o.put("sessionId", sessionId); o.put("detail", detail.take(300))
            arr.put(o)
            var toSave = arr
            if (arr.length() > 200) {
                val cut = JSONArray()
                for (i in arr.length() - 200 until arr.length()) cut.put(arr.opt(i))
                toSave = cut
            }
            prefs(ctx).edit().putString("decisions", toSave.toString()).apply()
        } catch (e: Exception) { Log.w(TAG, "logDecision gagal: ${e.message}") }
        Log.i(TAG, "$action item=$itemId room=$roomId sess=$sessionId :: $detail")
    }

    /**
     * Daftarkan approval pending. Bila item pending identik (room+sesi+tool+
     * ringkasan) sudah ada, callback diganti dan id lama dikembalikan — ini
     * mencegah duplikat saat proceedTool dipanggil ulang (jalur onOpenApp
     * float memanggil proceedTool lagi setelah 700ms).
     */
    @Synchronized
    fun register(ctx: Context, roomId: String, sessionId: Long, agent: String, tool: String,
                 ringkasan: String, alasan: String, ttlMs: Long = 0, argsFull: String = "",
                 onResolve: ((Boolean) -> Unit)? = null): String {
        val items = loadItems(ctx)
        val kelas = classForTool(tool)
        val existing = items.firstOrNull {
            it.status == "pending" && it.roomId == roomId && it.sessionId == sessionId &&
                it.tool == tool && it.ringkasan == ringkasan
        }
        if (existing != null) {
            if (onResolve != null) callbacks[existing.id] = onResolve
            return existing.id
        }
        val now = System.currentTimeMillis()
        val id = "ap_" + now + "_" + (0..9999).random()
        val panelArgs = try { JSONObject(argsFull) } catch (e: Exception) { JSONObject() }
        val item = ApprovalItem(id, roomId, sessionId, agent, tool, kelas, riskForTool(tool, panelArgs),
            ringkasan, alasan, now, if (ttlMs > 0) now + ttlMs else 0L, "pending", argsFull)
        items.add(item)
        saveItems(ctx, items)
        if (onResolve != null) callbacks[id] = onResolve
        Log.i(TAG, "register $id tool=$tool kelas=$kelas room=$roomId sess=$sessionId")
        return id
    }

    /** Tandai item selesai TANPA memanggil callback (dipakai dialog/float yang
     *  menjalankan kelanjutannya sendiri). */
    @Synchronized
    fun markResolved(ctx: Context, id: String, approved: Boolean, reason: String): Boolean {
        val items = loadItems(ctx)
        val it = items.firstOrNull { it.id == id && it.status == "pending" } ?: return false
        it.status = if (approved) "disetujui" else "ditolak"
        saveItems(ctx, items)
        callbacks.remove(id)
        logDecision(ctx, it.status, id, it.roomId, it.sessionId, "$reason :: ${it.tool}")
        return true
    }

    /** Resolusi dari panel: tandai + panggil callback sekali (melanjutkan
     *  giliran agen yang menunggu). */
    fun resolve(ctx: Context, id: String, approved: Boolean, reason: String): Boolean {
        val cb: ((Boolean) -> Unit)?
        synchronized(this) {
            val items = loadItems(ctx)
            val it = items.firstOrNull { it.id == id && it.status == "pending" } ?: return false
            it.status = if (approved) "disetujui" else "ditolak"
            saveItems(ctx, items)
            cb = callbacks.remove(id)
            logDecision(ctx, it.status, id, it.roomId, it.sessionId, "$reason :: ${it.tool}")
        }
        try { cb?.invoke(approved) } catch (e: Exception) { Log.w(TAG, "callback resolve gagal: ${e.message}") }
        return true
    }

    /** Tarik item pending dari daftar (mis. approval pindah ke jalur parkir
     *  notifikasi — diputuskan di jalur itu, bukan di panel). */
    @Synchronized
    fun withdraw(ctx: Context, id: String, reason: String): Boolean {
        val items = loadItems(ctx)
        val it = items.firstOrNull { it.id == id } ?: return false
        if (it.status != "pending") return false
        items.remove(it)
        saveItems(ctx, items)
        callbacks.remove(id)
        logDecision(ctx, "ditarik", id, it.roomId, it.sessionId, reason)
        return true
    }

    /** Item pending, risiko tinggi dulu lalu terlama. Kedaluwarsa TTL malas
     *  diterapkan di sini (khusus wait_confirm). */
    @Synchronized
    fun pending(ctx: Context, roomFilter: String? = null): List<ApprovalItem> {
        val items = loadItems(ctx)
        val now = System.currentTimeMillis()
        var changed = false
        for (it in items) {
            if (it.status == "pending" && it.expiresAt > 0 && now > it.expiresAt) {
                it.status = "kedaluwarsa"; changed = true
                callbacks.remove(it.id)
            }
        }
        if (changed) saveItems(ctx, items)
        val rank = mapOf(PanelRisk.TINGGI to 0, PanelRisk.SEDANG to 1, PanelRisk.RENDAH to 2)
        return items.filter { it.status == "pending" && (roomFilter == null || it.roomId == roomFilter) }
            .sortedWith(compareBy({ rank[it.risiko] ?: 9 }, { it.dimintaPada }))
    }

    @Synchronized
    fun pendingCount(ctx: Context, roomFilter: String? = null): Int = pending(ctx, roomFilter).size

    @Synchronized
    fun createMandate(ctx: Context, roomId: String, sessionId: Long, kelasAksi: String, syarat: String = ""): Mandate? {
        if (!canMandate(kelasAksi)) return null
        val mandates = loadMandates(ctx)
        val now = System.currentTimeMillis()
        val m = Mandate("md_" + now + "_" + (0..9999).random(), roomId, sessionId, kelasAksi,
            syarat, now, now + MANDATE_TTL_MS, "aktif")
        mandates.add(m)
        saveMandates(ctx, mandates)
        logDecision(ctx, "buat_mandat", m.id, roomId, sessionId, "kelas=$kelasAksi berlaku 7 hari")
        return m
    }

    @Synchronized
    fun activeMandates(ctx: Context, roomFilter: String? = null): List<Mandate> {
        val mandates = loadMandates(ctx)
        val now = System.currentTimeMillis()
        var changed = false
        for (m in mandates) {
            if (m.status == "aktif" && now > m.berlakuSampai) { m.status = "kedaluwarsa"; changed = true }
        }
        if (changed) saveMandates(ctx, mandates)
        return mandates.filter { it.status == "aktif" && canMandate(it.kelasAksi) && (roomFilter == null || it.roomId == roomFilter) }
            .sortedBy { it.berlakuSampai }
    }

    @Synchronized
    fun hasActiveMandate(ctx: Context, roomId: String, sessionId: Long, kelasAksi: String): Boolean {
        if (!canMandate(kelasAksi)) return false
        // vc229: kirim_luar TANPA nama tool tidak boleh lolos lewat jalur lama
        // (celah K-26) — pemanggil wajib memakai hasActiveMandateForTool.
        if (kelasAksi == "kirim_luar") return false
        return activeMandates(ctx).any { m ->
            m.kelasAksi == kelasAksi && m.sessionId == sessionId &&
                (m.roomId == roomId || m.roomId.isBlank() || roomId.isBlank())
        }
    }

    @Synchronized
    fun revokeMandate(ctx: Context, id: String): Boolean {
        val mandates = loadMandates(ctx)
        val m = mandates.firstOrNull { it.id == id && it.status == "aktif" } ?: return false
        m.status = "dicabut"
        saveMandates(ctx, mandates)
        logDecision(ctx, "cabut_mandat", id, m.roomId, m.sessionId, "kelas=${m.kelasAksi}")
        return true
    }

    /** Approval pending kedaluwarsa saat sesi berakhir (spek §5 + §7.3). */
    @Synchronized
    fun expireSession(ctx: Context, sessionId: Long): Int {
        val items = loadItems(ctx)
        var n = 0
        for (it in items) {
            if (it.status == "pending" && it.sessionId == sessionId) {
                it.status = "kedaluwarsa"; n++
                callbacks.remove(it.id)
            }
        }
        if (n > 0) {
            saveItems(ctx, items)
            logDecision(ctx, "kedaluwarsa_sesi", "-", "", sessionId, "$n approval pending kedaluwarsa (sesi berakhir)")
        }
        return n
    }
}
