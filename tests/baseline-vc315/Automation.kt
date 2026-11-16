package com.senzwh.deepharness

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// v4.48.9.29 (vc195): durasi + waktu PINDAH ke level SESI; room hanya wadah.
// Kedaluwarsa per sesi (refreshSessionArchive); expiry level room dihapus.
// v4.48.9.26 (vc192): Room -> Mode -> Sesi (lihat BRIEF-MODEL-SESI.md).
// v4.48.9.21 (vc187): Room Automation ("Room 24 Jam" = NAMA, bukan timer
// harian). Satu sesi automation per konfigurasi, 2 sub-room: solo + multi.
// Durasi diatur di awal (N hari / rutinitas / fullwork 24 jam) dan berlaku
// untuk kedua sub-room. Setelah habis, room jadi arsip read-only.
// Penyimpanan: prefs "harness" key "autoRooms" (JSONArray) + pesan per room
// key "autoMsgs_<roomId>" (JSONArray, dipangkas 300 terakhir).
object Automation {

    fun prefs(ctx: Context) = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)

    fun loadRooms(ctx: Context): JSONArray {
        val raw = prefs(ctx).getString("autoRooms", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    fun saveRooms(ctx: Context, arr: JSONArray) {
        prefs(ctx).edit().putString("autoRooms", arr.toString()).apply()
    }

    fun roomById(ctx: Context, id: String): JSONObject? {
        val arr = loadRooms(ctx)
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (r.optString("id") == id) return r
        }
        return null
    }

    fun updateRoom(ctx: Context, room: JSONObject) {
        val arr = loadRooms(ctx)
        var done = false
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (r.optString("id") == room.optString("id")) { arr.put(i, room); done = true; break }
        }
        if (!done) arr.put(room)
        saveRooms(ctx, arr)
    }

    fun createRoom(ctx: Context, title: String, task: String, durKind: String, nDays: Int, blocklist: List<String>, criteria: String = ""): JSONObject {
        val id = "auto_" + System.currentTimeMillis()
        val now = System.currentTimeMillis()
        val endAt = when (durKind) {
            "full24" -> now + 24L * 60L * 60L * 1000L
            "days" -> now + (if (nDays > 0) nDays else 3).toLong() * 24L * 60L * 60L * 1000L
            else -> 0L
        }
        val room = JSONObject()
        room.put("id", id)
        room.put("title", title)
        room.put("task", task)
        room.put("durKind", durKind)
        room.put("nDays", if (durKind == "days") (if (nDays > 0) nDays else 3) else 0)
        room.put("endAt", endAt)
        room.put("archived", false)
        room.put("stopped", false)
        room.put("criteria", criteria)
        room.put("lastSub", "solo")
        room.put("tasks", JSONArray())
        val bl = JSONArray()
        for (b in blocklist) bl.put(b)
        room.put("blocklist", bl)
        val arr = loadRooms(ctx)
        arr.put(room)
        saveRooms(ctx, arr)
        return room
    }

    fun durationText(room: JSONObject): String {
        val kind = room.optString("durKind", "days")
        if (kind == "routine") return "Durasi: rutinitas"
        if (kind == "full24") return "Durasi: fullwork 24 jam"
        return "Durasi: " + room.optInt("nDays", 3) + " hari"
    }

    // v4.48.9.29 (vc195): satu-satunya sumber hitungan endAt (dipakai
    // createRoom lama, createSession, dan edit sesi — semantik sama).
    fun endAtFor(durKind: String, nDays: Int): Long {
        val now = System.currentTimeMillis()
        return when (durKind) {
            "full24" -> now + 24L * 60L * 60L * 1000L
            "days" -> now + (if (nDays > 0) nDays else 3).toLong() * 24L * 60L * 60L * 1000L
            else -> 0L
        }
    }

    // v4.48.9.29 (vc195): teks durasi PER SESI (dipakai info sesi + digest).
    fun sessionDurationText(sess: JSONObject): String {
        val kind = sess.optString("durKind", "days")
        val base = when (kind) {
            "routine" -> "Durasi: rutinitas (terbuka)"
            "full24" -> "Durasi: fullwork 24 jam"
            else -> "Durasi: " + sess.optInt("nDays", 3) + " hari"
        }
        val endAt = sess.optLong("endAt", 0)
        val endTxt = if (endAt > 0) {
            try { " - s.d. " + java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(endAt)) } catch (e: Exception) { "" }
        } else ""
        return base + endTxt + (if (sess.optBoolean("archived", false)) " - ARSIP" else "")
    }

    // v4.48.9.29 (vc195): kedaluwarsa level ROOM DIHAPUS — room hanya wadah.
    // Fungsi dipertahankan sebagai no-op agar pemanggil lama (Beranda, panel
    // sesi, room view) tetap jalan; expiry kini per sesi lewat
    // refreshSessionArchive. Flag archived room yang sudah ada (mis. dari
    // stop) tetap dibaca apa adanya di tempat lain.
    fun refreshArchive(ctx: Context, room: JSONObject): JSONObject {
        return room
    }

    fun activeRoom(ctx: Context): JSONObject? {
        val arr = loadRooms(ctx)
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            refreshArchive(ctx, r)
            if (!r.optBoolean("archived", false)) return r
        }
        return null
    }

    fun loadMessages(ctx: Context, roomId: String): JSONArray {
        val raw = prefs(ctx).getString("autoMsgs_" + roomId, "[]") ?: "[]"
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    fun appendMessage(ctx: Context, roomId: String, sub: String, sender: String, text: String, sess: String = "") {
        val arr = loadMessages(ctx, roomId)
        val m = JSONObject()
        m.put("sub", sub)
        m.put("sess", sess)
        m.put("sender", sender)
        m.put("text", text)
        m.put("ts", System.currentTimeMillis())
        arr.put(m)
        var toSave = arr
        if (arr.length() > 300) {
            // vc246 GELOMBANG 5 (GR-m9): sebelum dipangkas, pesan
            // "laporan" yang terpotong DIARSIPKAN ke prefs (bukan hilang
            // diam-diam bersama badge unread-nya). Arsip dibaca dari LOG.
            try {
                val archRaw = prefs(ctx).getString("laporanArchive_" + roomId, "[]") ?: "[]"
                val arch = try { JSONArray(archRaw) } catch (e: Exception) { JSONArray() }
                for (i in 0 until arr.length() - 300) {
                    val old = arr.optJSONObject(i) ?: continue
                    if (old.optString("sender") == "laporan") arch.put(old)
                }
                while (arch.length() > 200) arch.remove(0)
                prefs(ctx).edit().putString("laporanArchive_" + roomId, arch.toString()).apply()
            } catch (e: Exception) {}
            val cut = JSONArray()
            for (i in arr.length() - 300 until arr.length()) cut.put(arr.opt(i))
            toSave = cut
        }
        prefs(ctx).edit().putString("autoMsgs_" + roomId, toSave.toString()).apply()
        if (sender == "laporan") {
            val k = if (sess.isNotBlank()) "roomReportUnread_" + roomId + "_" + sess else "roomReportUnread_" + roomId
            prefs(ctx).edit().putInt(k, prefs(ctx).getInt(k, 0) + 1).apply()
        }
        // vc198 Fase 1: hook episode NON-BLOCKING — tidak boleh crash
        // atau melambatkan pengiriman pesan (Thread + try/catch).
        val epSess = if (sess.isNotBlank()) sess else roomId
        val epSender = sender
        val epText = text
        Thread {
            try { GraphDb.get(ctx).getOrCreateEpisode(epSess, epSender, epText, "room") } catch (e: Exception) {}
        }.start()
    }

    fun reportUnreadFor(ctx: Context, roomId: String, sess: String): Int =
        prefs(ctx).getInt("roomReportUnread_" + roomId + "_" + sess, 0)

    fun clearReportUnreadFor(ctx: Context, roomId: String, sess: String) {
        prefs(ctx).edit().remove("roomReportUnread_" + roomId + "_" + sess).apply()
    }

    fun reportUnread(ctx: Context, roomId: String): Int = prefs(ctx).getInt("roomReportUnread_" + roomId, 0)

    fun clearReportUnread(ctx: Context, roomId: String) {
        prefs(ctx).edit().remove("roomReportUnread_" + roomId).apply()
    }

    fun addTask(ctx: Context, room: JSONObject, text: String, agent: String, criteria: String = "") {
        val tasks = room.optJSONArray("tasks") ?: JSONArray()
        val t = JSONObject()
        t.put("text", text)
        t.put("agent", agent)
        t.put("status", "todo")
        t.put("criteria", if (criteria.isNotBlank()) criteria else "Tugas tuntas bila: " + text)
        t.put("verify", "")
        t.put("verifyNote", "")
        tasks.put(t)
        room.put("tasks", tasks)
        updateRoom(ctx, room)
        logAction(ctx, room.optString("id"), agent, "tambah tugas", text)
    }

    fun setTaskStatus(ctx: Context, room: JSONObject, index: Int, status: String) {
        val tasks = room.optJSONArray("tasks") ?: return
        val t = tasks.optJSONObject(index) ?: return
        t.put("status", status)
        room.put("tasks", tasks)
        updateRoom(ctx, room)
        logAction(ctx, room.optString("id"), t.optString("agent"), "status tugas", t.optString("text") + " → " + status)
    }

    fun setTaskDone(ctx: Context, room: JSONObject, index: Int) {
        setTaskStatus(ctx, room, index, "done")
    }

    // v4.48.9.22 (vc188): self-check. TERVERIFIKASI/SEBAGIAN = ditandai
    // selesai + status verifikasi + bukti. GAGAL = TIDAK ditandai selesai
    // (tetap terbuka) + status verifikasi + bukti, supaya user bisa perbaiki.
    fun setTaskVerified(ctx: Context, room: JSONObject, index: Int, status: String, note: String) {
        val tasks = room.optJSONArray("tasks") ?: return
        val t = tasks.optJSONObject(index) ?: return
        t.put("status", "done")
        t.put("verify", status)
        t.put("verifyNote", note)
        room.put("tasks", tasks)
        updateRoom(ctx, room)
        logAction(ctx, room.optString("id"), t.optString("agent"), "verifikasi tugas", status + " — " + note)
    }

    fun setTaskVerifyOnly(ctx: Context, room: JSONObject, index: Int, status: String, note: String) {
        val tasks = room.optJSONArray("tasks") ?: return
        val t = tasks.optJSONObject(index) ?: return
        t.put("verify", status)
        t.put("verifyNote", note)
        room.put("tasks", tasks)
        updateRoom(ctx, room)
        logAction(ctx, room.optString("id"), t.optString("agent"), "verifikasi tugas", status + " — " + note)
    }

    // v4.48.9.21: mode laporan job dari routines (default solo, backward compat).
    fun routineMode(routine: JSONObject): String {
        val m = routine.optString("mode", "solo")
        return if (m == "multi") "multi" else "solo"
    }

    // v4.48.9.26 (vc192): routing laporan ke SESI PEMILIK (bukan room aktif).
    // Routine dengan sessionId -> sesi itu (bila room-nya belum arsip).
    // Routine lama tanpa sessionId -> sesi #1 dari modenya (fallback).
    // Tanpa room aktif -> false (notifikasi tetap dikirim dari deliver()).
    fun appendJobReport(ctx: Context, jobId: String?, origin: String, answer: String): Boolean {
        return appendJobReportEx(ctx, jobId, origin, answer).isEmpty()
    }

    // vc243 GELOMBANG 2 (GR-M5): seperti appendJobReport, tapi mengembalikan
    // ALASAN gagal ("" = berhasil) agar pemanggil bisa memberi jejak yang
    // bisa ditemukan, bukan membuang false. Aturan routing baru:
    // - routine terikat sesi -> sesi itu (lama); arsip -> alasan, TANPA
    //   fallback ke room aktif (laporan arsip tidak boleh nyasar).
    // - jadwal/geofence/tujuan dengan ikatan sesi 1-1 eksplisit -> TIDAK
    //   ditulis ke room aktif sembarangan; alasan dikembalikan dan deliver
    //   menyimpannya ke daftar laporan gagal-route.
    // - tanpa ikatan (data lama / briefing / rekap / konsolidasi) -> fallback
    //   lama ke room aktif; tanpa room aktif -> alasan.
    fun appendJobReportEx(ctx: Context, jobId: String?, origin: String, answer: String): String {
        var targetRoom: JSONObject? = null
        var targetSess: JSONObject? = null
        var targetMode = "solo"
        if (jobId != null) {
            val raw = prefs(ctx).getString("routines", "[]") ?: "[]"
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    // vc195: scheduler mengirim jobId "rutinitas:<id>";
                    // id routine telanjang — cocokkan keduanya.
                    val rid = jobId.removePrefix("rutinitas:")
                    if (r.optString("id") == jobId || r.optString("id") == rid) {
                        targetMode = routineMode(r)
                        val sid = r.optString("sessionId", "")
                        if (sid.isNotBlank()) {
                            // vc196: pasangan (roomId, sessionId) dulu.
                            // roomId ditulis syncSessionSchedule + edit F2
                            // sejak vc196. Tanpa roomId (routine lama) =
                            // pencarian global lama. Pasangan ketemu tapi
                            // arsip -> return false (sama seperti dulu);
                            // pasangan tidak ketemu -> pencarian global,
                            // lalu fallback room aktif seperti dulu.
                            var found: Pair<JSONObject, JSONObject>? = null
                            val ridRoom = r.optString("roomId", "")
                            if (ridRoom.isNotBlank()) {
                                val rr = roomById(ctx, ridRoom)
                                if (rr != null) {
                                    val ss = sessionById(rr, sid)
                                    if (ss != null) found = Pair(rr, ss)
                                }
                            }
                            if (found == null) found = findSessionRoom(ctx, sid)
                            if (found != null) {
                                refreshSessionArchive(ctx, found.first, found.second)
                                if (!found.first.optBoolean("archived", false) && !found.second.optBoolean("archived", false)) {
                                    targetRoom = found.first
                                    targetSess = found.second
                                } else {
                                    return "sesi arsip: routine terikat sesi sudah diarsip"
                                }
                            }
                        }
                        break
                    }
                }
            } catch (e: Exception) {}
        }
        if (targetRoom == null) {
            // vc243 GELOMBANG 2 (GR-M5): ikatan sesi eksplisit untuk
            // non-rutinitas — jangan jatuh ke room aktif bila terikat.
            val bound = boundOneToOneSession(ctx, jobId)
            if (bound > 0) {
                return "terikat sesi 1-1 #$bound (bukan room) — laporan tidak ditulis ke room aktif"
            }
            val room = activeRoom(ctx) ?: return "tidak ada room aktif untuk laporan"
            ensureSessions(ctx, room)
            targetRoom = room
            targetSess = sessionsOfMode(room, targetMode).firstOrNull() ?: sessions(room).optJSONObject(0)
        }
        val finalRoom = targetRoom ?: return "tidak ada room tujuan"
        val finalSess = targetSess
        if (finalSess != null) {
            refreshSessionArchive(ctx, finalRoom, finalSess)
            if (finalSess.optBoolean("archived", false)) return "sesi arsip: sesi tujuan sudah diarsip"
        }
        val sessId = finalSess?.optString("id", "") ?: ""
        val mode = finalSess?.optString("mode", targetMode) ?: targetMode
        appendMessage(ctx, finalRoom.optString("id"), mode, "laporan", "[" + origin + "] " + answer, sessId)
        logAction(ctx, finalRoom.optString("id"), "sistem", "laporan job", origin, sessId)
        finalRoom.put("lastSub", mode)
        if (sessId.isNotBlank()) finalRoom.put("lastSessionId", sessId)
        updateRoom(ctx, finalRoom)
        return ""
    }

    // vc243 GELOMBANG 2 (GR-M5): ikatan sesi 1-1 eksplisit per asal tugas.
    // 0 = tidak terikat (data lama / asal tanpa ikatan) -> fallback lama.
    fun boundOneToOneSession(ctx: Context, jobId: String?): Long {
        if (jobId == null) return 0
        try {
            if (jobId.startsWith("jadwal:")) {
                val id = jobId.removePrefix("jadwal:").toLongOrNull() ?: return 0
                return MemoryDb.get(ctx).schedules().firstOrNull { it.id == id }?.ownerSession ?: 0
            }
            if (jobId.startsWith("geofence:")) {
                val id = jobId.removePrefix("geofence:").toLongOrNull() ?: return 0
                return MemoryDb.get(ctx).geofences().firstOrNull { it.id == id }?.ownerSession ?: 0
            }
            if (jobId.startsWith("tujuan:")) {
                val gid = jobId.removePrefix("tujuan:").toLongOrNull() ?: return 0
                return prefs(ctx).getLong("goalOwnerSession_" + gid, 0)
            }
        } catch (e: Exception) {}
        return 0
    }

    // vc243 GELOMBANG 2 (GR-M5): daftar laporan gagal-route — SATU tempat
    // yang bisa ditemukan (dialog LOG menampilkan daftar ini) + jejak
    // EventDb di pemanggil. Cap 50, terbaru dulu.
    fun appendUndeliveredReport(ctx: Context, jobId: String?, origin: String, answer: String, reason: String) {
        try {
            val raw = prefs(ctx).getString("jobReportUndelivered", "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            val o = JSONObject()
            o.put("ts", System.currentTimeMillis())
            o.put("job", jobId ?: "")
            o.put("origin", origin)
            o.put("reason", reason)
            o.put("answer", answer.take(600))
            val out = JSONArray().put(o)
            var n = 0
            for (i in 0 until arr.length()) {
                if (n >= 49) break
                out.put(arr.opt(i)); n++
            }
            prefs(ctx).edit().putString("jobReportUndelivered", out.toString()).apply()
        } catch (e: Exception) {}
    }

    fun undeliveredReports(ctx: Context): JSONArray {
        return try { JSONArray(prefs(ctx).getString("jobReportUndelivered", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
    }

    // vc243 GELOMBANG 2 (GR-M3, sumber terpisah): hasil koordinator dicatat
    // di sini (cap 20, terbaru dulu) dan ditampilkan di tab Laporan dengan
    // label sumber terpisah — bukan sebagai laporan room itu.
    fun appendCoordReport(ctx: Context, taskId: Long, taskText: String, result: String, originSession: Long, originTitle: String) {
        try {
            val raw = prefs(ctx).getString("coordReports", "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            val o = JSONObject()
            o.put("ts", System.currentTimeMillis())
            o.put("taskId", taskId)
            o.put("task", taskText.take(200))
            o.put("result", result.take(1200))
            o.put("originSession", originSession)
            o.put("originTitle", originTitle)
            val out = JSONArray().put(o)
            var n = 0
            for (i in 0 until arr.length()) {
                if (n >= 19) break
                out.put(arr.opt(i)); n++
            }
            prefs(ctx).edit().putString("coordReports", out.toString()).apply()
        } catch (e: Exception) {}
    }

    fun coordReports(ctx: Context): JSONArray {
        return try { JSONArray(prefs(ctx).getString("coordReports", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
    }

    // v4.48.9.22 (vc188): audit trail per room. Catatan AKSI (bukan duplikat
    // transkrip): stempel waktu, agen, aksi, hasil. Disimpan di prefs
    // autoLog_<roomId>, dipangkas 200 terakhir.
    fun logAction(ctx: Context, roomId: String, agent: String, action: String, result: String, sess: String = "") {
        if (roomId.isBlank()) return
        val key = "autoLog_" + roomId
        val raw = prefs(ctx).getString(key, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val o = JSONObject()
        o.put("ts", System.currentTimeMillis())
        o.put("agent", agent)
        o.put("action", action)
        o.put("result", result.take(300))
        o.put("sess", sess)
        arr.put(o)
        var toSave = arr
        if (arr.length() > 200) {
            val cut = JSONArray()
            for (i in arr.length() - 200 until arr.length()) cut.put(arr.opt(i))
            toSave = cut
        }
        prefs(ctx).edit().putString(key, toSave.toString()).apply()
    }

    fun loadLog(ctx: Context, roomId: String): JSONArray {
        val raw = prefs(ctx).getString("autoLog_" + roomId, "[]") ?: "[]"
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    // vc263 (H-M17): batalkan routine terikat room — predikat SAMA dengan
    // deleteRoomOne (id sess_<id> / schedRoutineId / sessionId / roomId /
    // digest kind=digest + digestRoomId). stopRoom dulu tidak menyentuh
    // routines sama sekali sehingga digest tetap di-submit tiap hari lalu
    // gagal-route; deleteRoomOne sengaja TIDAK di-refactor ke helper ini
    // (jalur hapus total sudah terverifikasi — duplikasi predikat dicatat).
    fun cancelRoutinesForRoom(ctx: Context, roomId: String): Int {
        return try {
            val room = roomById(ctx, roomId) ?: return 0
            val sessIds = ArrayList<String>()
            val dropIds = HashSet<String>()
            val ss = sessions(room)
            for (i in 0 until ss.length()) {
                val s = ss.optJSONObject(i) ?: continue
                val sid = s.optString("id")
                if (sid.isNotBlank()) {
                    sessIds.add(sid)
                    dropIds.add("sess_" + sid)
                    val sr = s.optString("schedRoutineId", "")
                    if (sr.isNotBlank()) dropIds.add(sr)
                }
            }
            val raw = prefs(ctx).getString("routines", "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            val kept = JSONArray()
            var dropped = 0
            for (i in 0 until arr.length()) {
                val rt = arr.optJSONObject(i) ?: continue
                val drop = dropIds.contains(rt.optString("id")) ||
                    sessIds.contains(rt.optString("sessionId", "")) ||
                    rt.optString("roomId", "") == roomId ||
                    (rt.optString("kind", "") == "digest" && rt.optString("digestRoomId", "") == roomId)
                if (drop) dropped++ else kept.put(rt)
            }
            if (dropped > 0) prefs(ctx).edit().putString("routines", kept.toString()).apply()
            dropped
        } catch (e: Exception) { 0 }
    }

    // v4.48.9.22 (vc188): stop per room. Menghentikan automation itu (tidak
    // ada lagi job/balasan baru — activeRoom melewati room arsip) dan
    // mengarsipkan room (read-only, masih bisa dibuka). Peluncuran ulang
    // lewat template. Tanpa batas per room di build ini (keputusan user).
    fun stopRoom(ctx: Context, room: JSONObject) {
        // vc195: hentikan room = hentikan SEMUA sesinya (tiap sesi arsip).
        val ss = sessions(room)
        for (i in 0 until ss.length()) {
            val s = ss.optJSONObject(i) ?: continue
            s.put("archived", true)
            s.put("stopped", true)
        }
        room.put("archived", true)
        room.put("stopped", true)
        updateRoom(ctx, room)
        val cancelledVc263 = cancelRoutinesForRoom(ctx, room.optString("id"))
        logAction(ctx, room.optString("id"), "sistem", "hentikan room", "room + semua sesi diarsipkan (read-only); routine terikat dibatalkan: $cancelledVc263 (termasuk digest)")
        // vc206 T5: stop menular — arsipkan SEMUA sub-room (anak + cucu,
        // rekursif; depth maks 2 jadi rantai pendek, tapi kode tetap
        // rekursif). Stop per sesi (stopSession) TIDAK berubah.
        try {
            val all = loadRooms(ctx)
            for (i in 0 until all.length()) {
                val ch = all.optJSONObject(i) ?: continue
                if (ch.optString("parentRoomId", "") == room.optString("id") && !ch.optBoolean("archived", false)) {
                    stopRoom(ctx, ch)
                }
            }
        } catch (e: Exception) {}
    }

    // vc245 GELOMBANG 4 (GR-M1): traversal anak yang SAMA dengan stopRoom
    // (parentRoomId, rekursif) — dipakai deleteRoom + dialog konfirmasi
    // hapus, supaya tidak ada lagi room yatim. Guard visited menangkal
    // siklus parentRoomId (stopRoom sendiri tidak punya guard ini).
    fun descendantRooms(ctx: Context, roomId: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        seen.add(roomId)
        fun walk(parentId: String) {
            val all = loadRooms(ctx)
            for (i in 0 until all.length()) {
                val ch = all.optJSONObject(i) ?: continue
                if (ch.optString("parentRoomId", "") == parentId && seen.add(ch.optString("id"))) {
                    out.add(ch)
                    walk(ch.optString("id"))
                }
            }
        }
        walk(roomId)
        return out
    }

    // vc245 GELOMBANG 4 (GR-M1): deleteRoom kini rekursif — anak dihapus
    // dulu (urutan terbalik: cucu sebelum anak sebelum induk) supaya
    // tidak ada sesi/routine/laporan anak yang tertinggal hidup.
    fun deleteRoom(ctx: Context, roomId: String): JSONObject? {
        val descendants = try { descendantRooms(ctx, roomId) } catch (e: Exception) { emptyList() }
        var totalChildren = 0
        val childTitles = JSONArray()
        for (ch in descendants.reversed()) {
            val cr = deleteRoomOne(ctx, ch.optString("id")) ?: continue
            totalChildren++
            childTitles.put(ch.optString("title"))
        }
        val res = deleteRoomOne(ctx, roomId) ?: return null
        res.put("childrenDeleted", totalChildren)
        res.put("childTitles", childTitles)
        return res
    }

    // v4.48.9.31 (vc211): hapus grup TOTAL dari panel Sesi Grup.
    // Menghapus record room dari autoRooms beserta SEMUA sesinya, pesan
    // (autoMsgs_), log (autoLog_), penanda graf (graphMark_), pertanyaan
    // pagi (dreamQs_), laporan belum dibaca, dan papan koordinator per
    // sesi. Membatalkan SEMUA routine F2 yang terikat ke room/sesi ini:
    // cocokkan id routine (schedRoutineId / sess_<id>), sessionId, roomId,
    // dan digest (kind=digest + digestRoomId). Scheduler membaca prefs
    // routines segar tiap tick, jadi routine yang terhapus tidak jalan
    // lagi; fallback room aktif dihitung dinamis (activeRoom() = room
    // non-arsip pertama), jadi tidak ada pointer yang perlu diperbarui.
    // Folder output sesi dihapus HANYA bila path kanonisnya berada di
    // bawah sessOutRoot DAN sama dengan outDir tersimpan / hasil
    // outputDirFor sesi itu DAN tidak diklaim sesi room lain yang tersisa
    // (folder bersama tidak dihapus). vc245 GELOMBANG 4 (GR-M1):
    // sub-room (parentRoomId = room ini) SUDAH ikut dihapus oleh
    // pembungkus deleteRoom (rekursif, anak dulu); fungsi satu-room ini
    // tidak lagi dipanggil langsung dari UI.
    // Mengembalikan JSONObject hasil (title, sessions, routinesCancelled,
    // foldersDeleted, foldersSkipped) atau null bila room tidak ketemu.
    private fun deleteRoomOne(ctx: Context, roomId: String): JSONObject? {
        val rooms = loadRooms(ctx)
        var target: JSONObject? = null
        val keptRooms = JSONArray()
        for (i in 0 until rooms.length()) {
            val r = rooms.optJSONObject(i) ?: continue
            if (target == null && r.optString("id") == roomId) target = r else keptRooms.put(r)
        }
        val room = target ?: return null
        val title = room.optString("title")
        val sessArr = sessions(room)
        val sessIds = ArrayList<String>()
        val dropRoutineIds = HashSet<String>()
        for (i in 0 until sessArr.length()) {
            val s = sessArr.optJSONObject(i) ?: continue
            val sid = s.optString("id")
            if (sid.isNotBlank()) {
                sessIds.add(sid)
                dropRoutineIds.add("sess_" + sid)
                val sr = s.optString("schedRoutineId", "")
                if (sr.isNotBlank()) dropRoutineIds.add(sr)
            }
        }
        val rootRaw = prefs(ctx).getString("sessOutRoot", "/sdcard/S&Harness/") ?: "/sdcard/S&Harness/"
        val rootCanon = try { File(rootRaw).canonicalPath } catch (e: Exception) { rootRaw }
        val candidates = LinkedHashSet<String>()
        for (i in 0 until sessArr.length()) {
            val s = sessArr.optJSONObject(i) ?: continue
            val stored = s.optString("outDir", "")
            val cand = if (stored.isNotBlank()) stored else outputDirFor(ctx, room, s)
            if (cand.isNotBlank()) {
                try { candidates.add(File(cand).canonicalPath) } catch (e: Exception) { }
            }
        }
        val claimedByOthers = HashSet<String>()
        for (i in 0 until keptRooms.length()) {
            val r = keptRooms.optJSONObject(i) ?: continue
            val ss = sessions(r)
            for (j in 0 until ss.length()) {
                val s = ss.optJSONObject(j) ?: continue
                val stored = s.optString("outDir", "")
                if (stored.isNotBlank()) {
                    try { claimedByOthers.add(File(stored).canonicalPath) } catch (e: Exception) { }
                }
                try { claimedByOthers.add(File(outputDirFor(ctx, r, s)).canonicalPath) } catch (e: Exception) { }
            }
        }
        var routinesCancelled = 0
        try {
            val raw = prefs(ctx).getString("routines", "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val rt = arr.optJSONObject(i) ?: continue
                val drop = dropRoutineIds.contains(rt.optString("id")) ||
                    sessIds.contains(rt.optString("sessionId", "")) ||
                    rt.optString("roomId", "") == roomId ||
                    (rt.optString("kind", "") == "digest" && rt.optString("digestRoomId", "") == roomId)
                if (drop) routinesCancelled++ else kept.put(rt)
            }
            prefs(ctx).edit().putString("routines", kept.toString()).apply()
        } catch (e: Exception) { }
        try {
            val ed = prefs(ctx).edit()
            ed.remove("autoMsgs_" + roomId)
            ed.remove("autoLog_" + roomId)
            ed.remove("graphMark_" + roomId)
            ed.remove("dreamQs_" + roomId)
            ed.remove("roomReportUnread_" + roomId)
            for (sid in sessIds) {
                ed.remove("roomReportUnread_" + roomId + "_" + sid)
                ed.remove("coordDiv_" + roomId + "_" + sid)
            }
            ed.apply()
        } catch (e: Exception) { }
        saveRooms(ctx, keptRooms)
        val foldersDeleted = ArrayList<String>()
        val foldersSkipped = ArrayList<String>()
        for (cand in candidates) {
            val underRoot = cand == rootCanon || cand.startsWith(rootCanon + "/")
            if (!underRoot || claimedByOthers.contains(cand)) {
                foldersSkipped.add(cand)
                continue
            }
            try {
                val d = File(cand)
                if (d.exists()) {
                    if (d.deleteRecursively()) foldersDeleted.add(cand) else foldersSkipped.add(cand)
                }
            } catch (e: Exception) {
                foldersSkipped.add(cand)
            }
        }
        val res = JSONObject()
        res.put("title", title)
        res.put("sessions", sessIds.size)
        res.put("routinesCancelled", routinesCancelled)
        val fd = JSONArray()
        for (f in foldersDeleted) fd.put(f)
        res.put("foldersDeleted", fd)
        val fs = JSONArray()
        for (f in foldersSkipped) fs.put(f)
        res.put("foldersSkipped", fs)
        return res
    }

    // v4.48.9.29 (vc195): kedaluwarsa PER SESI. Bila lewat endAt, sesi itu
    // menjadi arsip read-only; sesi lain di room yang sama tidak terpengaruh.
    fun refreshSessionArchive(ctx: Context, room: JSONObject, sess: JSONObject): JSONObject {
        if (sess.optBoolean("archived", false)) return sess
        val endAt = sess.optLong("endAt", 0)
        if (endAt > 0 && System.currentTimeMillis() > endAt) {
            sess.put("archived", true)
            updateSession(ctx, room, sess)
            logAction(ctx, room.optString("id"), "sistem", "sesi diarsipkan", "durasi habis: " + sess.optString("title"), sess.optString("id"))
        }
        return sess
    }

    // v4.48.9.29 (vc195): hentikan SATU sesi (tombol stop per sesi di room
    // view). Sesi lain tidak terpengaruh; room tetap wadah.
    // vc243 GELOMBANG 2 (RM-M3, pilihan hapus): stopSession menghapus
    // routine F2 yang terikat sesi ini (sessionId / id sess_<id> /
    // schedRoutineId sesi) agar tidak ada routine zombie. Scheduler sudah
    // melewati sesi arsip di tick, jadi ini pembersihan + kejujuran status,
    // bukan satu-satunya penahan eksekusi.
    fun stopSession(ctx: Context, room: JSONObject, sess: JSONObject) {
        sess.put("archived", true)
        sess.put("stopped", true)
        updateSession(ctx, room, sess)
        logAction(ctx, room.optString("id"), "sistem", "hentikan sesi", "sesi diarsipkan (read-only): " + sess.optString("title"), sess.optString("id"))
        try {
            val sid = sess.optString("id")
            val schedRid = sess.optString("schedRoutineId", "")
            val raw = prefs(ctx).getString("routines", "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            val kept = JSONArray()
            var dropped = 0
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val drop = (sid.isNotBlank() && (r.optString("sessionId", "") == sid || r.optString("id") == "sess_" + sid)) ||
                    (schedRid.isNotBlank() && r.optString("id") == schedRid)
                if (drop) dropped++ else kept.put(r)
            }
            if (dropped > 0) {
                prefs(ctx).edit().putString("routines", kept.toString()).apply()
                logAction(ctx, room.optString("id"), "sistem", "hentikan sesi", "routine terikat sesi dihapus: $dropped (sesi: " + sess.optString("title") + ")", sid)
            }
        } catch (e: Exception) {}
    }

    // v4.48.9.22 (vc188): template automation (resep). JSON di prefs
    // autoTemplates: nama + tugas + durasi + blocklist + mode + kriteria.
    fun saveTemplate(ctx: Context, name: String, room: JSONObject): Boolean {
        if (name.isBlank()) return false
        val raw = prefs(ctx).getString("autoTemplates", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val o = JSONObject()
        o.put("name", name)
        o.put("task", room.optString("task"))
        // vc195: template menyimpan durasi SESI aktif (bukan room, karena
        // durasi kini per sesi); fallback ke field room lama bila tak ada sesi.
        val tplSess = sessionById(room, room.optString("lastSessionId"))
        val tplHasDur = tplSess != null && tplSess.optString("durKind", "").isNotBlank()
        o.put("durKind", if (tplHasDur) tplSess!!.optString("durKind", "days") else room.optString("durKind", "days"))
        o.put("nDays", if (tplHasDur) tplSess!!.optInt("nDays", 3) else room.optInt("nDays", 3))
        o.put("blocklist", room.optJSONArray("blocklist") ?: JSONArray())
        o.put("mode", room.optString("lastSub", "solo"))
        var crit = room.optString("criteria", "")
        if (crit.isBlank()) {
            val tasks = room.optJSONArray("tasks") ?: JSONArray()
            val t0 = tasks.optJSONObject(0)
            if (t0 != null) crit = t0.optString("criteria", "")
        }
        o.put("criteria", crit)
        // vc263 (FT-M7): template kini menyimpan jadwal sesi, rerank,
        // digest, dan member multi — dulu hanya nama/tugas/durasi/blocklist/
        // mode/kriteria sehingga room dari template lahir tanpa semuanya.
        val tplSess2Vc263 = sessionById(room, room.optString("lastSessionId")) ?: tplSess
        o.put("schedTime", tplSess2Vc263?.optString("schedTime", "") ?: "")
        o.put("schedIntervalMin", tplSess2Vc263?.optInt("schedIntervalMin", 0) ?: 0)
        o.put("rerank", room.optBoolean("rerank", false))
        val tplMultiVc263 = run {
            var found: JSONObject? = null
            val ssTpl = sessions(room)
            for (i in 0 until ssTpl.length()) {
                val s = ssTpl.optJSONObject(i) ?: continue
                if (s.optString("mode") == "multi") { found = s; break }
            }
            found
        }
        o.put("members", tplMultiVc263?.optJSONArray("members") ?: JSONArray())
        o.put("divMode", tplMultiVc263?.optString("divMode", "bebas") ?: "bebas")
        val tplDigestVc263 = run {
            var foundTime = ""
            try {
                val rtArr = JSONArray(prefs(ctx).getString("routines", "[]") ?: "[]")
                for (i in 0 until rtArr.length()) {
                    val r = rtArr.optJSONObject(i) ?: continue
                    if (r.optString("kind", "") == "digest" && r.optString("digestRoomId", "") == room.optString("id")) { foundTime = r.optString("time", "07:00"); break }
                }
            } catch (e: Exception) {}
            foundTime
        }
        o.put("digest", tplDigestVc263.isNotBlank())
        o.put("digestTime", if (tplDigestVc263.isNotBlank()) tplDigestVc263 else "07:00")
        arr.put(o)
        prefs(ctx).edit().putString("autoTemplates", arr.toString()).apply()
        logAction(ctx, room.optString("id"), "sistem", "simpan template", name)
        return true
    }

    // vc246 GELOMBANG 5 (FT-m14): hapus template berdasarkan nama.
    fun deleteTemplate(ctx: Context, name: String): Boolean {
        return try {
            val raw = prefs(ctx).getString("autoTemplates", "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            val kept = JSONArray()
            var dropped = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!dropped && o.optString("name") == name) { dropped = true; continue }
                kept.put(o)
            }
            if (dropped) prefs(ctx).edit().putString("autoTemplates", kept.toString()).apply()
            dropped
        } catch (e: Exception) { false }
    }

    // vc246 GELOMBANG 5 (rantai 2): tandai tugas papan selesai secara
    // manual dan jujur (status=done), tanpa verifikasi agen.
    fun markSessionTaskDone(ctx: Context, room: JSONObject, sess: JSONObject, index: Int): Boolean {
        return try {
            val tasks = sessionTasks(sess)
            val t = tasks.optJSONObject(index) ?: return false
            t.put("status", "done")
            tasks.put(index, t)
            sess.put("tasks", tasks)
            updateSession(ctx, room, sess)
            true
        } catch (e: Exception) { false }
    }

    fun loadTemplates(ctx: Context): JSONArray {
        val raw = prefs(ctx).getString("autoTemplates", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    // v4.48.9.23 (vc189): penanda konsolidasi inkremental per room.
    // msgCount = jumlah pesan yang sudah diproses; taskTexts = teks tugas
    // yang sudah diproses. Konsolidasi berikutnya hanya memproses konten
    // baru (pesan di luar penanda + tugas baru).
    fun graphMark(ctx: Context, roomId: String): JSONObject {
        val raw = prefs(ctx).getString("graphMark_" + roomId, "{}") ?: "{}"
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
    }

    fun saveGraphMark(ctx: Context, roomId: String, msgCount: Int, taskTexts: List<String>) {
        val o = JSONObject()
        o.put("ts", System.currentTimeMillis())
        o.put("msgCount", msgCount)
        val arr = JSONArray()
        for (t in taskTexts) arr.put(t)
        o.put("taskTexts", arr)
        prefs(ctx).edit().putString("graphMark_" + roomId, o.toString()).apply()
    }

    fun clearGraphMark(ctx: Context, roomId: String) {
        prefs(ctx).edit().remove("graphMark_" + roomId).apply()
    }

    // v4.48.9.26 (vc192): SESI dalam room. Satu room punya banyak sesi
    // (tanpa batas); tiap sesi punya tugas, aliran pesan, dan laporan
    // sendiri. Pesan membawa "sess"; laporan belum dibaca per sesi.
    fun sessions(room: JSONObject): JSONArray {
        return room.optJSONArray("sessions") ?: JSONArray()
    }

    fun sessionById(room: JSONObject, id: String): JSONObject? {
        val arr = sessions(room)
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            if (s.optString("id") == id) return s
        }
        return null
    }

    fun sessionsOfMode(room: JSONObject, mode: String): ArrayList<JSONObject> {
        val out = ArrayList<JSONObject>()
        val arr = sessions(room)
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            if (s.optString("mode", "solo") == mode) out.add(s)
        }
        return out
    }

    fun findSessionRoom(ctx: Context, sessId: String): Pair<JSONObject, JSONObject>? {
        val arr = loadRooms(ctx)
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val s = sessionById(r, sessId)
            if (s != null) return Pair(r, s)
        }
        return null
    }

    fun sanitizeTitle(s: String): String {
        val clean = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        val cut = if (clean.length > 48) clean.substring(0, 48) else clean
        return if (cut.isBlank()) "sesi" else cut
    }

    fun outputDirFor(ctx: Context, room: JSONObject, sess: JSONObject): String {
        val stored = sess.optString("outDir", "")
        if (stored.isNotBlank()) return stored
        val root = prefs(ctx).getString("sessOutRoot", "/sdcard/S&Harness/") ?: "/sdcard/S&Harness/"
        val base = if (root.endsWith("/")) root else root + "/"
        return base + sanitizeTitle(room.optString("title")) + "__" + sanitizeTitle(sess.optString("title")) + "/"
    }

    fun ensureOutputDir(ctx: Context, room: JSONObject, sess: JSONObject) {
        try {
            val d = File(outputDirFor(ctx, room, sess))
            if (!d.exists()) d.mkdirs()
        } catch (e: Exception) {}
    }

    fun createSession(ctx: Context, room: JSONObject, mode: String, title: String, agent: String, members: List<String>, divMode: String, primaryAgent: String, workerSlots: List<Int>, durKind: String, nDays: Int, schedTime: String, schedIntervalMin: Int): JSONObject {
        val s = JSONObject()
        s.put("id", "s" + System.currentTimeMillis() + "_" + (System.nanoTime() % 1000))
        s.put("mode", mode)
        s.put("title", if (title.isNotBlank()) title else if (mode == "multi") "Sesi Multi" else "Sesi Solo")
        s.put("agent", agent)
        val mem = JSONArray()
        for (m in members) mem.put(m)
        s.put("members", mem)
        s.put("divMode", divMode)
        s.put("primaryAgent", primaryAgent)
        s.put("primarySlot", 0)
        // vc246 GELOMBANG 5 (FT-m18): slot 0 punya DUA arti lama (default
        // vs belum dipilih). Kini dipisah: primarySlotChosen=false berarti
        // "belum dipilih — otomatis pakai slot login pertama"; true berarti
        // user/sistem sudah memilih slot eksplisit. Nilai 0 + chosen=false
        // = otomatis, bukan slot yang dipilih.
        s.put("primarySlotChosen", false)
        val ws = JSONArray()
        for (w in workerSlots) ws.put(w)
        s.put("workerSlots", ws)
        s.put("tasks", JSONArray())
        s.put("createdAt", System.currentTimeMillis())
        s.put("durKind", durKind)
        s.put("nDays", if (durKind == "days") (if (nDays > 0) nDays else 3) else 0)
        s.put("endAt", endAtFor(durKind, nDays))
        s.put("archived", false)
        s.put("stopped", false)
        s.put("schedTime", schedTime)
        s.put("schedIntervalMin", schedIntervalMin)
        s.put("schedRoutineId", "")
        val root = prefs(ctx).getString("sessOutRoot", "/sdcard/S&Harness/") ?: "/sdcard/S&Harness/"
        val base = if (root.endsWith("/")) root else root + "/"
        s.put("outDir", base + sanitizeTitle(room.optString("title")) + "__" + sanitizeTitle(s.optString("title")) + "/")
        val arr = sessions(room)
        arr.put(s)
        room.put("sessions", arr)
        updateRoom(ctx, room)
        ensureOutputDir(ctx, room, s)
        return s
    }

    fun updateSession(ctx: Context, room: JSONObject, sess: JSONObject) {
        val arr = sessions(room)
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            if (s.optString("id") == sess.optString("id")) { arr.put(i, sess); break }
        }
        room.put("sessions", arr)
        updateRoom(ctx, room)
    }

    // v4.48.9.29 (vc195): jadwal sesi (field Waktu di dialog sesi) diwujudkan
    // sebagai SATU routine F2 terikat ke sesi ini (prefs "routines").
    // Laporan routine itu masuk sesi ini lewat appendJobReport (per 3d).
    // Dikosongkan = routine jadwal sesi ini dihapus. Prompt routine = tugas
    // pertama sesi, atau judul sesi bila belum ada tugas. Hanya dipanggil
    // dari dialog sesi (yang sudah memvalidasi format jam / interval).
    // vc245 GELOMBANG 4 (FT-M5): prompt beku ini hanya fallback — tick F2
    // memakai effectiveRoutinePrompt (tugas pertama SAAT INI).
    fun syncSessionSchedule(ctx: Context, room: JSONObject, sess: JSONObject): JSONObject {
        val time = sess.optString("schedTime", "").trim()
        val interval = sess.optInt("schedIntervalMin", 0)
        val timeValid = if (interval > 0) true else time.matches(Regex("\\d{2}:\\d{2}")) && (time.substring(0, 2).toIntOrNull() ?: -1) in 0..23 && (time.substring(3, 5).toIntOrNull() ?: -1) in 0..59
        val raw = prefs(ctx).getString("routines", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val kept = JSONArray()
        val existingId = sess.optString("schedRoutineId", "")
        val selfId = "sess_" + sess.optString("id")
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if ((existingId.isNotBlank() && r.optString("id") == existingId) || r.optString("id") == selfId) continue
            kept.put(r)
        }
        if (!timeValid && interval <= 0) {
            sess.put("schedRoutineId", "")
            updateSession(ctx, room, sess)
            prefs(ctx).edit().putString("routines", kept.toString()).apply()
            return sess
        }
        val prompt = run {
            val ts = sess.optJSONArray("tasks") ?: JSONArray()
            val t0 = ts.optJSONObject(0)
            val tx = t0?.optString("text", "") ?: ""
            if (tx.isNotBlank()) tx else sess.optString("title")
        }
        val rid = if (existingId.isNotBlank()) existingId else selfId
        val o = JSONObject()
        o.put("id", rid)
        if (interval > 0) {
            o.put("interval_min", interval)
            o.put("time", "")
        } else {
            o.put("time", time)
            o.put("interval_min", 0)
        }
        o.put("prompt", prompt)
        o.put("enabled", true)
        o.put("mode", sess.optString("mode", "solo"))
        o.put("sessionId", sess.optString("id"))
        o.put("roomId", room.optString("id"))
        kept.put(o)
        prefs(ctx).edit().putString("routines", kept.toString()).apply()
        sess.put("schedRoutineId", rid)
        updateSession(ctx, room, sess)
        return sess
    }

    // vc245 GELOMBANG 4 (FT-M5, pilihan prompt dinamis): prompt routine
    // sesi TIDAK lagi dibekukan di syncSessionSchedule sebagai satu-satunya
    // kebenaran — saat tick F2, prompt efektif = tugas pertama papan sesi
    // SAAT INI bila ada, bila tidak ada maka prompt tersimpan (judul sesi / bekuan lama)
    // sebagai fallback. Ini menutup basi untuk SEMUA perubahan papan
    // (tambah/ubah tugas lewat jalur apa pun), bukan hanya tambah lewat
    // addSessionTask. Routine tanpa sessionId tidak berubah.
    fun effectiveRoutinePrompt(ctx: Context, routine: JSONObject, storedPrompt: String): String {
        return try {
            val sid = routine.optString("sessionId", "")
            if (sid.isBlank()) return storedPrompt
            val found = findSessionRoom(ctx, sid) ?: return storedPrompt
            val first = sessionTasks(found.second).optJSONObject(0)?.optString("text", "") ?: ""
            if (first.isNotBlank()) first else storedPrompt
        } catch (e: Exception) { storedPrompt }
    }

    // Migrasi satu kali per room: isi Solo lama -> Sesi Solo #1, isi Multi
    // lama -> Sesi Multi #1. Papan tugas lama (satu papan bersama) disalin
    // ke KEDUA sesi #1 (itulah yang dulu tampil di kedua sub-room); pesan
    // lama dibagi menurut sub-nya. Aman dipanggil berulang (no-op bila
    // sessions sudah ada).
    // vc196: sesi #1 sebuah room = sesi yang id-nya "<roomId>_solo1" /
    // "<roomId>_multi1" (buatan ensureSessions vc196, atau hasil migrasi
    // dari "solo1"/"multi1" — field oldId menyimpan id lamanya). Pesan
    // lama tanpa sess hanya tampil di sesi #1 (aturan lama yang memakai
    // literal "solo1"/"multi1" — kini room-aware agar tidak salah room).
    fun isFirstSession(room: JSONObject, sess: JSONObject): Boolean {
        val rid = room.optString("id")
        val sid = sess.optString("id")
        return sid == rid + "_solo1" || sid == rid + "_multi1" || sess.optString("oldId") == "solo1" || sess.optString("oldId") == "multi1"
    }

    // vc196: pemilik routine ber-sessionId telanjang = room yang sesinya
    // mengklaim routine itu (schedRoutineId == id routine). Bila beberapa
    // room mengklaim (id routine sess_* juga kembar), pilih yang prompt-nya
    // sama dengan tugas pertama sesi / judul sesi (aturan prompt
    // syncSessionSchedule); bila masih seri, room terakhir (penulis terakhir
    // menang, konsisten dengan filter-kept syncSessionSchedule). Tanpa
    // klaim (mis. routine dari edit F2) -> null: routine dibiarkan apa
    // adanya (lihat komentar di migrateSessionIds).
    private fun resolveRoutineOwnerRoom(rooms: JSONArray, routine: JSONObject, bareId: String): String? {
        val rid = routine.optString("id", "")
        val prompt = routine.optString("prompt", "")
        var best: String? = null
        var bestScore = -1
        for (i in 0 until rooms.length()) {
            val r = rooms.optJSONObject(i) ?: continue
            val s = sessionById(r, bareId) ?: continue
            if (rid.isBlank() || s.optString("schedRoutineId", "") != rid) continue
            var score = 2
            val firstTask = s.optJSONArray("tasks")?.optJSONObject(0)?.optString("text", "") ?: ""
            if (prompt.isNotBlank() && (firstTask == prompt || s.optString("title", "") == prompt)) score += 1
            if (score >= bestScore) { best = r.optString("id"); bestScore = score }
        }
        return best
    }

    // v4.48.9.30 (vc196): migrasi id sesi telanjang -> namespaced.
    // Latar: ensureSessions lama memberi tiap room id tetap "solo1" /
    // "multi1" sehingga dua room punya sesi ber-id sama; findSessionRoom
    // (pencarian global ber-id telanjang) selalu mengembalikan room
    // pertama -> laporan routine nyasar ke room aktif pertama (SSR ROOM),
    // bukan room pemilik (TES VC195). Migrasi ini menamai ulang id
    // telanjang menjadi "<roomId>_solo1" / "<roomId>_multi1" di semua room
    // dan semua referensinya. Satu kali + idempotent: tanpa id telanjang
    // = no-op (tanpa tulis). Aman dipanggil berulang dan aman bila crash
    // di tengah (setiap penulisan bersyarat pada nilai lama yang masih
    // ada). Sinkron by design: data kecil (JSON prefs) dan harus selesai
    // sebelum routing routine dipercaya.
    // Yang TIDAK diubah: id room, id routine (sess_solo1 tetap — hanya
    // sessionId-nya yang berubah; schedRoutineId sesi juga tetap), isi
    // pesan, autoLog, graphMark (level room), outDir (berbasis judul),
    // routine kind=digest, sesi tombol + Sesi (id sudah unik).
    fun migrateSessionIds(ctx: Context): Boolean {
        val rooms = loadRooms(ctx)
        var foundBare = false
        for (i in 0 until rooms.length()) {
            val r = rooms.optJSONObject(i) ?: continue
            if (r.optString("id").isBlank()) continue
            val ss = sessions(r)
            for (j in 0 until ss.length()) {
                val s = ss.optJSONObject(j) ?: continue
                val old = s.optString("id")
                if (old == "solo1" || old == "multi1") foundBare = true
            }
        }
        if (!foundBare) return false
        // 1. Routine dulu (resolusi pemilik butuh snapshot pra-rename,
        // karena sesudah rename id telanjang sudah tidak ada).
        try {
            val raw = prefs(ctx).getString("routines", "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            var changed = false
            for (i in 0 until arr.length()) {
                val rt = arr.optJSONObject(i) ?: continue
                if (rt.optString("kind", "") == "digest") continue
                val sid = rt.optString("sessionId", "")
                if (sid != "solo1" && sid != "multi1") continue
                val ownerRoom = resolveRoutineOwnerRoom(rooms, rt, sid) ?: continue
                rt.put("sessionId", ownerRoom + "_" + sid)
                rt.put("roomId", ownerRoom)
                changed = true
            }
            if (changed) prefs(ctx).edit().putString("routines", arr.toString()).apply()
        } catch (e: Exception) {}
        // 2. Sesi + lastSessionId per room.
        val renames = HashMap<String, HashMap<String, String>>()
        var roomsChanged = false
        for (i in 0 until rooms.length()) {
            val r = rooms.optJSONObject(i) ?: continue
            val rid = r.optString("id")
            if (rid.isBlank()) continue
            val ss = sessions(r)
            val used = HashSet<String>()
            for (j in 0 until ss.length()) {
                val s = ss.optJSONObject(j) ?: continue
                used.add(s.optString("id"))
            }
            val m = HashMap<String, String>()
            for (j in 0 until ss.length()) {
                val s = ss.optJSONObject(j) ?: continue
                val old = s.optString("id")
                if (old != "solo1" && old != "multi1") continue
                val nw = rid + "_" + old
                if (used.contains(nw)) continue
                s.put("oldId", old)
                s.put("id", nw)
                used.add(nw)
                m[old] = nw
                roomsChanged = true
            }
            if (m.isNotEmpty()) {
                renames[rid] = m
                val last = r.optString("lastSessionId", "")
                val nl = m[last]
                if (nl != null) r.put("lastSessionId", nl)
            }
        }
        if (roomsChanged) saveRooms(ctx, rooms)
        // 3. Pesan per room (autoMsgs_<roomId>): field sess.
        for (i in 0 until rooms.length()) {
            val r = rooms.optJSONObject(i) ?: continue
            val rid = r.optString("id")
            val m = renames[rid] ?: continue
            try {
                val msgs = loadMessages(ctx, rid)
                var changed = false
                for (j in 0 until msgs.length()) {
                    val msg = msgs.optJSONObject(j) ?: continue
                    val nw = m[msg.optString("sess", "")] ?: continue
                    msg.put("sess", nw)
                    changed = true
                }
                if (changed) prefs(ctx).edit().putString("autoMsgs_" + rid, msgs.toString()).apply()
            } catch (e: Exception) {}
        }
        // 4. Kunci prefs ber-sesi: roomReportUnread_<roomId>_<lama> dan
        // coordDiv_<roomId>_<lama> -> kunci baru (nilai dipertahankan,
        // kunci lama dihapus).
        try {
            val all = prefs(ctx).all
            val ed = prefs(ctx).edit()
            var changed = false
            for ((rid, m) in renames) {
                for ((old, nw) in m) {
                    for (prefix in arrayOf("roomReportUnread_", "coordDiv_")) {
                        val ok = prefix + rid + "_" + old
                        val nk = prefix + rid + "_" + nw
                        val v = all[ok] ?: continue
                        when (v) {
                            is Int -> ed.putInt(nk, v)
                            is Long -> ed.putLong(nk, v)
                            is Float -> ed.putFloat(nk, v)
                            is Boolean -> ed.putBoolean(nk, v)
                            is String -> ed.putString(nk, v)
                            is Set<*> -> ed.putStringSet(nk, v.filterIsInstance<String>().toMutableSet())
                        }
                        ed.remove(ok)
                        changed = true
                    }
                }
            }
            if (changed) ed.apply()
        } catch (e: Exception) {}
        return true
    }

    fun ensureSessions(ctx: Context, room: JSONObject): JSONObject {
        // vc196: backstop migrasi id sesi (pemindaian murah, no-op bila
        // sudah bersih). Bila migrasi mengubah data, segarkan objek room
        // ini dari prefs agar pemanggil tidak bekerja dengan id telanjang
        // yang sudah usang.
        try {
            if (migrateSessionIds(ctx)) {
                val fresh = roomById(ctx, room.optString("id"))
                if (fresh != null) {
                    val keys = ArrayList<String>()
                    val it = room.keys()
                    while (it.hasNext()) keys.add(it.next())
                    for (k in keys) room.remove(k)
                    val it2 = fresh.keys()
                    while (it2.hasNext()) {
                        val k = it2.next()
                        room.put(k, fresh.opt(k))
                    }
                }
            }
        } catch (e: Exception) {}
        if (sessions(room).length() > 0) {
            // vc195: sesi lama (vc192) belum punya field durasi - salin dari
            // room (endAt disalin apa adanya, bukan dihitung ulang; tidak ada
            // sesi yang mendadak memendek). Sesi yang sudah punya durKind
            // tidak disentuh.
            var changed = false
            val ex = sessions(room)
            for (i in 0 until ex.length()) {
                val s = ex.optJSONObject(i) ?: continue
                if (s.optString("durKind", "").isBlank()) {
                    s.put("durKind", room.optString("durKind", "days"))
                    s.put("nDays", room.optInt("nDays", 3))
                    s.put("endAt", room.optLong("endAt", 0))
                    s.put("archived", room.optBoolean("archived", false))
                    s.put("stopped", room.optBoolean("stopped", false))
                    s.put("schedTime", "")
                    s.put("schedIntervalMin", 0)
                    s.put("schedRoutineId", "")
                    changed = true
                }
            }
            if (changed) updateRoom(ctx, room)
            return room
        }
        val oldTasks = room.optJSONArray("tasks") ?: JSONArray()
        val copyTasks = JSONArray()
        for (i in 0 until oldTasks.length()) {
            val t = oldTasks.optJSONObject(i) ?: continue
            val c = JSONObject(t.toString())
            copyTasks.put(c)
        }
        val copyTasks2 = JSONArray()
        for (i in 0 until oldTasks.length()) {
            val t = oldTasks.optJSONObject(i) ?: continue
            copyTasks2.put(JSONObject(t.toString()))
        }
        val solo = JSONObject()
        solo.put("id", room.optString("id") + "_solo1")
        solo.put("mode", "solo")
        solo.put("title", room.optString("task", "").take(60).ifBlank { "Sesi Solo" })
        solo.put("agent", "senz")
        solo.put("members", JSONArray())
        solo.put("divMode", "bebas")
        solo.put("primaryAgent", "")
        solo.put("primarySlot", 0)
        solo.put("workerSlots", JSONArray())
        solo.put("tasks", copyTasks)
        solo.put("createdAt", System.currentTimeMillis())
        solo.put("durKind", room.optString("durKind", "days"))
        solo.put("nDays", room.optInt("nDays", 3))
        solo.put("endAt", room.optLong("endAt", 0))
        solo.put("archived", room.optBoolean("archived", false))
        solo.put("stopped", room.optBoolean("stopped", false))
        solo.put("schedTime", "")
        solo.put("schedIntervalMin", 0)
        solo.put("schedRoutineId", "")
        val multi = JSONObject()
        multi.put("id", room.optString("id") + "_multi1")
        multi.put("mode", "multi")
        multi.put("title", room.optString("task", "").take(60).ifBlank { "Sesi Multi" })
        multi.put("agent", "")
        val mem = JSONArray()
        mem.put("senz"); mem.put("mandor"); mem.put("mentor"); mem.put("agen4")
        multi.put("members", mem)
        multi.put("divMode", "bebas")
        multi.put("primaryAgent", "mandor")
        multi.put("primarySlot", 0)
        multi.put("workerSlots", JSONArray())
        multi.put("tasks", copyTasks2)
        multi.put("createdAt", System.currentTimeMillis())
        multi.put("durKind", room.optString("durKind", "days"))
        multi.put("nDays", room.optInt("nDays", 3))
        multi.put("endAt", room.optLong("endAt", 0))
        multi.put("archived", room.optBoolean("archived", false))
        multi.put("stopped", room.optBoolean("stopped", false))
        multi.put("schedTime", "")
        multi.put("schedIntervalMin", 0)
        multi.put("schedRoutineId", "")
        val arr = JSONArray()
        arr.put(solo); arr.put(multi)
        room.put("sessions", arr)
        val lastSub = room.optString("lastSub", "solo")
        room.put("lastSessionId", if (lastSub == "multi") room.optString("id") + "_multi1" else room.optString("id") + "_solo1")
        updateRoom(ctx, room)
        try {
            val msgs = loadMessages(ctx, room.optString("id"))
            var changed = false
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                if (m.optString("sess", "").isBlank()) {
                    val sub = m.optString("sub", "")
                    if (sub == "solo") m.put("sess", room.optString("id") + "_solo1")
                    else if (sub == "multi") m.put("sess", room.optString("id") + "_multi1")
                    changed = true
                }
            }
            if (changed) prefs(ctx).edit().putString("autoMsgs_" + room.optString("id"), msgs.toString()).apply()
        } catch (e: Exception) {}
        ensureOutputDir(ctx, room, solo)
        ensureOutputDir(ctx, room, multi)
        return room
    }

    fun sessionTasks(sess: JSONObject): JSONArray {
        return sess.optJSONArray("tasks") ?: JSONArray()
    }

    fun allSessionTasks(room: JSONObject): JSONArray {
        val arr = sessions(room)
        if (arr.length() == 0) return room.optJSONArray("tasks") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val ts = s.optJSONArray("tasks") ?: JSONArray()
            for (j in 0 until ts.length()) {
                val t = ts.optJSONObject(j) ?: continue
                out.put(t)
            }
        }
        return out
    }

    fun addSessionTask(ctx: Context, room: JSONObject, sess: JSONObject, text: String, agent: String, criteria: String = "") {
        val tasks = sessionTasks(sess)
        val t = JSONObject()
        t.put("text", text)
        t.put("agent", agent)
        t.put("status", "todo")
        t.put("criteria", if (criteria.isNotBlank()) criteria else "Tugas tuntas bila: " + text)
        t.put("verify", "")
        t.put("verifyNote", "")
        tasks.put(t)
        sess.put("tasks", tasks)
        updateSession(ctx, room, sess)
        logAction(ctx, room.optString("id"), agent, "tambah tugas", text, sess.optString("id"))
    }

    fun setSessionTaskStatus(ctx: Context, room: JSONObject, sess: JSONObject, index: Int, status: String) {
        val tasks = sessionTasks(sess)
        val t = tasks.optJSONObject(index) ?: return
        t.put("status", status)
        sess.put("tasks", tasks)
        updateSession(ctx, room, sess)
        logAction(ctx, room.optString("id"), t.optString("agent"), "status tugas", t.optString("text") + " -> " + status, sess.optString("id"))
    }

    fun setSessionTaskVerified(ctx: Context, room: JSONObject, sess: JSONObject, index: Int, status: String, note: String) {
        val tasks = sessionTasks(sess)
        val t = tasks.optJSONObject(index) ?: return
        // vc263 (H-M19): status papan MENCERMINKAN hasil verifikasi.
        // Dulu selalu "done" apa pun verify — "SEBAGIAN" dari engine yang
        // belum siap/gagal ikut tercatat selesai. Kini: TERVERIFIKASI =
        // done; SEBAGIAN = sebagian (tugas belum tuntas, butuh lanjut);
        // GAGAL/lainnya = status lama dipertahankan (hanya kolom verify
        // yang ditulis). Pemanggil GAGAL yang sudah benar memakai
        // setSessionTaskVerifyOnly tidak berubah.
        when (status) {
            "TERVERIFIKASI" -> t.put("status", "done")
            "SEBAGIAN" -> t.put("status", "sebagian")
            else -> { }
        }
        t.put("verify", status)
        t.put("verifyNote", note)
        sess.put("tasks", tasks)
        updateSession(ctx, room, sess)
        logAction(ctx, room.optString("id"), t.optString("agent"), "verifikasi tugas", status + " - " + note, sess.optString("id"))
    }

    fun setSessionTaskVerifyOnly(ctx: Context, room: JSONObject, sess: JSONObject, index: Int, status: String, note: String) {
        val tasks = sessionTasks(sess)
        val t = tasks.optJSONObject(index) ?: return
        t.put("verify", status)
        t.put("verifyNote", note)
        sess.put("tasks", tasks)
        updateSession(ctx, room, sess)
        logAction(ctx, room.optString("id"), t.optString("agent"), "verifikasi tugas", status + " - " + note, sess.optString("id"))
    }

    // v4.48.9.26 (vc192): indeks hasil per sesi. Dipindai dari ISI folder
    // (bukan dari klaim agen): index.json + INDEX.md ditulis ulang tiap
    // pemanggilan. Mengembalikan ringkasan "N file".
    fun rebuildSessionIndex(room: JSONObject, sess: JSONObject): String {
        return try {
            val dir = File(sess.optString("outDir", ""))
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles()?.filter { f -> f.isFile && !f.name.startsWith(".") && f.name != "index.json" && f.name != "INDEX.md" }?.sortedBy { it.name } ?: emptyList()
                val idx = JSONObject()
                idx.put("generatedAt", System.currentTimeMillis())
                idx.put("dir", dir.absolutePath)
                val arr = JSONArray()
                val md = StringBuilder()
                md.append("# Indeks hasil - ").append(room.optString("title")).append(" / ").append(sess.optString("title")).append("\n\n")
                for (f in files) {
                    val o = JSONObject()
                    o.put("name", f.name)
                    o.put("path", f.absolutePath)
                    o.put("size", f.length())
                    o.put("modified", f.lastModified())
                    arr.put(o)
                    md.append("- ").append(f.name).append(" (").append(f.length()).append(" byte) - ").append(f.absolutePath).append("\n")
                }
                idx.put("files", arr)
                File(dir, "index.json").writeText(idx.toString())
                File(dir, "INDEX.md").writeText(md.toString())
                files.size.toString() + " file"
            } else {
                "0 file"
            }
        } catch (e: Exception) {
            "indeks gagal: " + (e.message ?: "error")
        }
    }


    // ---- vc295: Proactive briefing (ai-release-hunter + daily-meeting-update) ----
    // Jadwal menumpang routines prefs (tick HarnessService) — TIDAK ada
    // scheduler baru. Room "Briefing Harian" = room Automation biasa;
    // laporan routine terikat sessionId+roomId masuk room itu via
    // appendJobReportEx. Cleanup otomatis via roomId (cancelRoutinesForRoom
    // / deleteRoomOne sudah membuang routine yang roomId-nya cocok).
    fun briefingRoutine(ctx: Context): JSONObject? {
        return try {
            val arr = JSONArray(prefs(ctx).getString("routines", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                if (r.optString("id") == BriefingVc295.ROUTINE_ID) return r
            }
            null
        } catch (e: Exception) { null }
    }

    fun ensureBriefingRoom(ctx: Context): Pair<JSONObject, JSONObject> {
        val rooms = loadRooms(ctx)
        var room: JSONObject? = null
        for (i in 0 until rooms.length()) {
            val r = rooms.optJSONObject(i) ?: continue
            if (!r.optBoolean("archived", false) && r.optString("title").trim().equals(BriefingVc295.ROOM_TITLE, ignoreCase = true)) { room = r; break }
        }
        if (room == null) {
            room = createRoom(ctx, BriefingVc295.ROOM_TITLE, "Room briefing harian (vc295): digest rilis AI + update harian. Digest otomatis tiap 08:00; wawancara 4 pertanyaan di sini.", "none", 0, emptyList())
        }
        ensureSessions(ctx, room)
        val solo = sessionsOfMode(room, "solo").firstOrNull() ?: sessions(room).optJSONObject(0) ?: JSONObject()
        return Pair(room, solo)
    }

    fun setBriefingSchedule(ctx: Context, enabled: Boolean): String {
        return try {
            val raw = prefs(ctx).getString("routines", "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                if (r.optString("id") != BriefingVc295.ROUTINE_ID) kept.put(r)
            }
            if (!enabled) {
                prefs(ctx).edit().putString("routines", kept.toString()).apply()
                return "Jadwal Briefing Harian DIMATIKAN (08:00 tidak jalan lagi; room Briefing Harian tetap ada, bisa dibuka manual)."
            }
            val (room, sess) = ensureBriefingRoom(ctx)
            val o = JSONObject()
            o.put("id", BriefingVc295.ROUTINE_ID)
            o.put("kind", "briefing")
            o.put("time", BriefingVc295.ROUTINE_TIME)
            o.put("interval_min", 0)
            o.put("enabled", true)
            o.put("prompt", BriefingVc295.briefingPrompt())
            o.put("mode", "solo")
            o.put("sessionId", sess.optString("id"))
            o.put("roomId", room.optString("id"))
            o.put("briefingRoomId", room.optString("id"))
            kept.put(o)
            prefs(ctx).edit().putString("routines", kept.toString()).apply()
            "Jadwal Briefing Harian AKTIF: tiap hari ${BriefingVc295.ROUTINE_TIME} WIB, laporan masuk ke room 'Briefing Harian' (sesi solo). Matikan lagi dari tombol yang sama bila tidak mau."
        } catch (e: Exception) { "Gagal mengatur jadwal briefing: " + e.message }
    }

    fun briefingStatusText(ctx: Context): String {
        val sb = StringBuilder()
        val r = briefingRoutine(ctx)
        sb.append("Briefing Harian (vc295) — digest rilis AI + update harian.\n")
        sb.append("Jadwal: ").append(if (r != null && r.optBoolean("enabled", true)) "AKTIF tiap hari " + r.optString("time", "08:00") else "MATI").append("\n")
        var found = false
        val rooms = loadRooms(ctx)
        for (i in 0 until rooms.length()) {
            val rm = rooms.optJSONObject(i) ?: continue
            if (rm.optString("title").trim().equals(BriefingVc295.ROOM_TITLE, ignoreCase = true) && !rm.optBoolean("archived", false)) { found = true; break }
        }
        sb.append("Room: ").append(if (found) "ada ('Briefing Harian')" else "belum dibuat (dibuat otomatis saat jadwal diaktifkan)").append("\n")
        val lastCheck = prefs(ctx).getLong("briefingLastCheckMs", 0)
        sb.append("Scan terakhir: ").append(if (lastCheck > 0) BriefingVc295.dateTimeStr(lastCheck) else "belum pernah").append("\n")
        try {
            val dir = File(ctx.filesDir, BriefingVc295.DIR_NAME)
            val seenF = File(dir, "seen.json")
            val seenN = if (seenF.exists()) BriefingVc295.seenFromJson(seenF.readText()).size else 0
            sb.append("Item sudah dilihat (dedup seen.json): ").append(seenN).append("\n")
            val outDir = File(dir, "output")
            val outs = outDir.listFiles()?.filter { it.name.endsWith(".md") }?.sortedByDescending { it.lastModified() }?.take(3) ?: emptyList()
            if (outs.isNotEmpty()) {
                sb.append("Output terakhir:\n")
                for (f in outs) sb.append("- ").append(f.name).append("\n")
            }
        } catch (e: Exception) {
            sb.append("(status file tidak terbaca: ").append(e.message).append(")\n")
        }
        return sb.toString().trim()
    }

    fun briefingLastOutput(ctx: Context): String? {
        return try {
            val outDir = File(File(ctx.filesDir, BriefingVc295.DIR_NAME), "output")
            val files = outDir.listFiles()?.filter { it.name.endsWith(".md") }?.sortedByDescending { it.lastModified() } ?: return null
            val f = files.firstOrNull() ?: return null
            f.name + "\n\n" + f.readText().take(4000)
        } catch (e: Exception) { null }
    }

    // ---- vc296: Rote PoC — kolektor notifikasi deterministik ----
    // Jadwal menumpang routines prefs (tick HarnessService) seperti
    // vc295 — TIDAK ada scheduler baru. Room "Ringkasan Notifikasi" =
    // room Automation biasa; laporan routine terikat sessionId+roomId
    // masuk room itu via appendJobReportEx. Cleanup otomatis via
    // roomId (cancelRoutinesForRoom / deleteRoomOne sudah membuang
    // routine yang roomId-nya cocok).
    fun roteRoutine(ctx: Context): JSONObject? {
        return try {
            val arr = JSONArray(prefs(ctx).getString("routines", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                if (r.optString("id") == RoteVc296.ROUTINE_ID) return r
            }
            null
        } catch (e: Exception) { null }
    }

    fun ensureRoteRoom(ctx: Context): Pair<JSONObject, JSONObject> {
        val rooms = loadRooms(ctx)
        var room: JSONObject? = null
        for (i in 0 until rooms.length()) {
            val r = rooms.optJSONObject(i) ?: continue
            if (!r.optBoolean("archived", false) && r.optString("title").trim().equals(RoteVc296.ROOM_TITLE, ignoreCase = true)) { room = r; break }
        }
        if (room == null) {
            room = createRoom(ctx, RoteVc296.ROOM_TITLE, "Room ringkasan notifikasi (vc296 rote PoC): digest notifikasi harian 21:30, pipeline deterministik 5 langkah tanpa LLM.", "none", 0, emptyList())
        }
        ensureSessions(ctx, room)
        val solo = sessionsOfMode(room, "solo").firstOrNull() ?: sessions(room).optJSONObject(0) ?: JSONObject()
        return Pair(room, solo)
    }

    fun setRoteSchedule(ctx: Context, enabled: Boolean): String {
        return try {
            val raw = prefs(ctx).getString("routines", "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                if (r.optString("id") != RoteVc296.ROUTINE_ID) kept.put(r)
            }
            if (!enabled) {
                prefs(ctx).edit().putString("routines", kept.toString()).apply()
                return "Jadwal Ringkasan Notifikasi DIMATIKAN (21:30 tidak jalan lagi; room Ringkasan Notifikasi tetap ada, bisa dibuka manual)."
            }
            val (room, sess) = ensureRoteRoom(ctx)
            val o = JSONObject()
            o.put("id", RoteVc296.ROUTINE_ID)
            o.put("kind", "rote")
            o.put("time", RoteVc296.ROUTINE_TIME)
            o.put("interval_min", 0)
            o.put("enabled", true)
            o.put("prompt", RoteVc296.rotePrompt())
            o.put("mode", "solo")
            o.put("sessionId", sess.optString("id"))
            o.put("roomId", room.optString("id"))
            o.put("roteRoomId", room.optString("id"))
            kept.put(o)
            prefs(ctx).edit().putString("routines", kept.toString()).apply()
            "Jadwal Ringkasan Notifikasi AKTIF: tiap hari ${RoteVc296.ROUTINE_TIME} WIB, laporan masuk ke room 'Ringkasan Notifikasi' (sesi solo). Matikan lagi dari tombol yang sama bila tidak mau."
        } catch (e: Exception) { "Gagal mengatur jadwal ringkasan notifikasi: " + e.message }
    }

    fun roteStatusText(ctx: Context): String {
        val sb = StringBuilder()
        val r = roteRoutine(ctx)
        sb.append("Ringkasan Notifikasi (vc296) — pipeline deterministik 5 langkah (rote PoC, 0 langkah LLM).\n")
        sb.append("Jadwal: ").append(if (r != null && r.optBoolean("enabled", true)) "AKTIF tiap hari " + r.optString("time", "21:30") else "MATI").append("\n")
        var found = false
        val rooms = loadRooms(ctx)
        for (i in 0 until rooms.length()) {
            val rm = rooms.optJSONObject(i) ?: continue
            if (rm.optString("title").trim().equals(RoteVc296.ROOM_TITLE, ignoreCase = true) && !rm.optBoolean("archived", false)) { found = true; break }
        }
        sb.append("Room: ").append(if (found) "ada ('Ringkasan Notifikasi')" else "belum dibuat (dibuat otomatis saat jadwal diaktifkan)").append("\n")
        try {
            val dir = File(ctx.filesDir, RoteVc296.DIR_NAME)
            val stateFile = File(dir, "state.json")
            if (stateFile.exists()) {
                val st = RoteVc296.stateFromJson(stateFile.readText())
                sb.append("Run terakhir: ").append(if (st.lastRunAt > 0) BriefingVc295.dateTimeStr(st.lastRunAt) else "belum pernah").append("\n")
                sb.append("Run id: ").append(if (st.runId.isNotBlank()) st.runId else "-").append(" · langkah selesai: ")
                    .append(st.stepsDone.sorted().joinToString(",")).append(if (st.stepsDone.isNotEmpty()) " (" + st.stepsDone.map { RoteVc296.STEP_NAMES[it - 1] }.joinToString(",") + ")" else "").append("\n")
                sb.append("Output terakhir: ").append(if (st.lastOutput.isNotBlank()) st.lastOutput else "-").append("\n")
                sb.append("Notifikasi sudah dilihat (dedup seen): ").append(st.seen.size).append("\n")
            } else {
                sb.append("Run terakhir: belum pernah (state.json belum ada — jalankan rote sekali).\n")
            }
            val outDir = File(dir, "output")
            val outs = outDir.listFiles()?.filter { it.name.endsWith(".md") }?.sortedByDescending { it.lastModified() }?.take(3) ?: emptyList()
            if (outs.isNotEmpty()) {
                sb.append("Digest terakhir:\n")
                for (f in outs) sb.append("- ").append(f.name).append("\n")
            }
        } catch (e: Exception) {
            sb.append("(status file tidak terbaca: ").append(e.message).append(")\n")
        }
        return sb.toString().trim()
    }

    fun roteLastOutput(ctx: Context): String? {
        return try {
            val outDir = File(File(ctx.filesDir, RoteVc296.DIR_NAME), "output")
            val files = outDir.listFiles()?.filter { it.name.endsWith(".md") }?.sortedByDescending { it.lastModified() } ?: return null
            val f = files.firstOrNull() ?: return null
            f.name + "\n\n" + f.readText().take(4000)
        } catch (e: Exception) { null }
    }

    // v4.48.9.26 (vc192): digest harian per room (dipakai tombol Digest di
    // room + job kind=digest di BgBrain). Disusun dari data nyata: sesi,
    // tugas, laporan belum dibaca, routine terikat, indeks hasil.
    fun buildRoomDigest(ctx: Context, roomId: String): String {
        val room = roomById(ctx, roomId) ?: return "Digest gagal: room tidak ditemukan."
        ensureSessions(ctx, room)
        val sb = StringBuilder()
        sb.append("Digest harian - ").append(room.optString("title")).append("\n")
        sb.append("Room wadah - durasi per sesi di bawah.\n")
        val routines = try { JSONArray(prefs(ctx).getString("routines", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        val arr = sessions(room)
        var todo = 0; var doing = 0; var done = 0
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val ts = s.optJSONArray("tasks") ?: JSONArray()
            var tTodo = 0; var tDoing = 0; var tDone = 0
            for (j in 0 until ts.length()) {
                when (ts.optJSONObject(j)?.optString("status")) {
                    "todo" -> tTodo++
                    "doing" -> tDoing++
                    "done" -> tDone++
                }
            }
            todo += tTodo; doing += tDoing; done += tDone
            val unread = reportUnreadFor(ctx, roomId, s.optString("id"))
            val sched = ArrayList<String>()
            for (k in 0 until routines.length()) {
                val r = routines.optJSONObject(k) ?: continue
                if (r.optString("sessionId", "") == s.optString("id")) {
                    val whenText = if (r.optInt("interval_min", 0) > 0) "tiap " + r.optInt("interval_min", 0) + " mnt" else "harian " + r.optString("time", "")
                    sched.add(whenText)
                }
            }
            val idxCount = try {
                val idxFile = File(s.optString("outDir", ""), "index.json")
                if (idxFile.exists()) JSONObject(idxFile.readText()).optJSONArray("files")?.length() ?: 0 else 0
            } catch (e: Exception) { 0 }
            sb.append("- Sesi ").append(s.optString("title")).append(" (").append(s.optString("mode")).append("): ")
            sb.append(sessionDurationText(s)).append("; ")
            sb.append(tTodo).append(" todo, ").append(tDoing).append(" berjalan, ").append(tDone).append(" selesai; ")
            sb.append("laporan belum dibaca: ").append(unread).append("; ")
            sb.append("jadwal: ").append(if (sched.isEmpty()) "-" else sched.joinToString(", ")).append("; ")
            sb.append("file hasil: ").append(idxCount).append("\n")
        }
        sb.append("Total tugas: ").append(todo).append(" todo, ").append(doing).append(" berjalan, ").append(done).append(" selesai.\n")
        val msgs = loadMessages(ctx, roomId)
        var shown = 0
        for (i in msgs.length() - 1 downTo 0) {
            if (shown >= 5) break
            val m = msgs.optJSONObject(i) ?: continue
            if (m.optString("sender") == "laporan") {
                sb.append("Laporan terakhir: ").append(m.optString("text").take(120)).append("\n")
                shown++
            }
        }
        if (shown == 0) sb.append("Belum ada laporan.\n")
        // vc204 T3: seksi pertanyaan pagi deterministik (dari prefs
        // dreamQs_<roomId>, dihitung konsolidasi jam sepi; TANPA LLM).
        // Bila kosong, seksi TIDAK tampil (tanpa placeholder).
        try {
            val dq = GraphJobs.dreamQuestionsFor(ctx, roomId)
            if (dq.isNotEmpty()) {
                sb.append("❓ Pertanyaan pagi (dari graf, bukan fakta):\n")
                for ((qi, q) in dq.withIndex()) sb.append(qi + 1).append(". ").append(q).append("\n")
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    // v4.48.9.26 (vc192): papan pembagian koordinator (persistensi).
    fun coordDivKey(roomId: String, sessId: String): String {
        return "coordDiv_" + roomId + "_" + sessId
    }

    fun loadDiv(ctx: Context, roomId: String, sessId: String): JSONArray {
        val raw = prefs(ctx).getString(coordDivKey(roomId, sessId), "[]") ?: "[]"
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    fun saveDiv(ctx: Context, roomId: String, sessId: String, arr: JSONArray) {
        prefs(ctx).edit().putString(coordDivKey(roomId, sessId), arr.toString()).apply()
    }

    fun clearDiv(ctx: Context, roomId: String, sessId: String) {
        prefs(ctx).edit().remove(coordDivKey(roomId, sessId)).apply()
    }

    // vc198 Fase 3: pass deterministik konsolidasi, DIPINDAH dari
    // MainActivity.consolidateRoomGraph agar dipakai UI dan job latar
    // (GraphJobs) dengan SATU implementasi. Perilaku dipertahankan:
    // incremental via graphMark, episode per pesan (Fase 1), guard
    // fence/quote (Fase 1), rebuild index per sesi. TIDAK ada UI di sini
    // (tanpa appendSystem/renderMessages) — pemanggil yang melapor.
    // candBatch = kandidat resolusi entitas konten (keputusan, temuan,
    // laporan, task) untuk adjudikasi LLM per batch (Fase 3).
    // vc240 M11: deferMark = true menunda penyimpanan graphMark sampai
    // fase LLM (relations + verifier) sukses — pemanggil (GraphJobs) yang
    // menyimpan mark sesudah sukses. Bila LLM gagal, mark lama bertahan
    // sehingga batch yang sama dicoba lagi pada run berikutnya, bukan
    // tertutup mark dan hilang selamanya seperti sebelumnya.
    fun consolidateDeterministic(ctx: Context, roomJson: JSONObject, deferMark: Boolean = false): DetResult {
        val roomIdStr = roomJson.optString("id")
        val msgs = loadMessages(ctx, roomIdStr)
        val mark = graphMark(ctx, roomIdStr)
        val prevMsgCount = mark.optInt("msgCount", 0)
        val prevTasks = HashSet<String>()
        val ptArr = mark.optJSONArray("taskTexts") ?: JSONArray()
        for (i in 0 until ptArr.length()) prevTasks.add(ptArr.optString(i))
        val skippedMsgs = minOf(prevMsgCount, msgs.length())
        val g = GraphDb.get(ctx)
        val nodesBefore = g.countNodes()
        val edgesBefore = g.countEdges()
        val rn = g.getOrCreateNode("room", roomJson.optString("title"), "automation:" + roomIdStr)
        val goalText = roomJson.optString("task")
        if (goalText.isNotBlank()) {
            val gn = g.getOrCreateNode("goal", goalText.take(120), "automation:" + roomIdStr)
            if (gn > 0 && rn > 0) g.addEdge(rn, gn, "bertujuan")
        }
        val grp = g.getOrCreateNode("grup", "Room: " + roomJson.optString("title"), "automation:" + roomIdStr)
        fun linkMember(id: Long) {
            if (id > 0 && grp > 0) g.addEdge(grp, id, "anggota")
        }
        val candBatch = ArrayList<Triple<String, Long, List<Long>>>()
        fun collectCand(label: String, nodeId: Long, existedBefore: Boolean) {
            if (existedBefore || nodeId <= 0) return
            val c = try { g.fuzzyCandidates(label) } catch (e: Exception) { emptyList() }
            val ids = c.filter { it.id != nodeId }.map { it.id }
            if (ids.isNotEmpty()) candBatch.add(Triple(label, nodeId, ids))
        }
        for (i in skippedMsgs until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            val t = m.optString("text")
            val sender = m.optString("sender")
            val epId = try { g.getOrCreateEpisode(if (m.optString("sess").isNotBlank()) m.optString("sess") else roomIdStr, sender, t, "room") } catch (e: Exception) { -1L }
            if (sender == "laporan") {
                val lab = t.take(120)
                val existed = g.nodeByLabel(lab) != null
                val ln = g.getOrCreateNode("laporan", lab, "room:" + roomIdStr)
                collectCand(lab, ln, existed)
                if (ln > 0) { linkMember(ln); if (rn > 0) g.addEdge(rn, ln, "menerima", episodeId = epId) }
            }
            if (sender != "user" && sender != "sistem" && sender != "laporan" && sender.isNotBlank()) {
                val an = g.getOrCreateNode("agent", sender, "builtin")
                if (an > 0) linkMember(an)
            }
            var inFence = false
            for (line in t.lines()) {
                val lt = line.trim()
                if (lt.startsWith("```")) { inFence = !inFence; continue }
                if (inFence || lt.startsWith(">")) continue
                if (lt.startsWith("Keputusan:", true) && lt.length > 12) {
                    val body = lt.substringAfter(":").trim()
                    val kIdx = body.indexOf("karena", ignoreCase = true)
                    val decText = if (kIdx >= 0) body.substring(0, kIdx).trim().trimEnd(',', '.', ' ') else body
                    val lab = decText.take(160)
                    val existed = g.nodeByLabel(lab) != null
                    val dn = g.getOrCreateNode("keputusan", lab, "room:" + roomIdStr)
                    collectCand(lab, dn, existed)
                    if (dn > 0) { linkMember(dn); if (rn > 0) g.addEdge(rn, dn, "menghasilkan", episodeId = epId) }
                    if (kIdx >= 0) {
                        val reason = body.substring(kIdx + 6).trim().trimStart(':', ' ').take(160)
                        if (reason.length > 3) {
                            val ran = g.getOrCreateNode("rationale", reason, "room:" + roomIdStr)
                            if (ran > 0 && dn > 0) { g.addEdge(ran, dn, "rationale_for", episodeId = epId); linkMember(ran) }
                        }
                    }
                } else if ((lt.startsWith("Penting:", true) || lt.startsWith("Temuan:", true)) && lt.length > 12) {
                    val lab = lt.take(160)
                    val existed = g.nodeByLabel(lab) != null
                    val nn = g.getOrCreateNode("temuan", lab, "room:" + roomIdStr)
                    collectCand(lab, nn, existed)
                    if (nn > 0) { linkMember(nn); if (rn > 0) g.addEdge(rn, nn, "menghasilkan", episodeId = epId) }
                }
            }
        }
        val tasks = allSessionTasks(roomJson)
        val curTaskTexts = ArrayList<String>()
        var skippedTasks = 0
        for (i in 0 until tasks.length()) {
            val t = tasks.optJSONObject(i) ?: continue
            val tt = t.optString("text")
            curTaskTexts.add(tt)
            if (prevTasks.contains(tt)) skippedTasks++
            val lab = tt.take(120)
            val existed = g.nodeByLabel(lab) != null
            val tn = g.getOrCreateNode("task", lab, "automation:" + roomIdStr)
            collectCand(lab, tn, existed)
            if (tn > 0) {
                linkMember(tn)
                if (rn > 0) g.addEdge(rn, tn, "memiliki")
                val an = g.getOrCreateNode("agent", t.optString("agent"), "builtin")
                if (an > 0) g.addEdge(an, tn, "mengerjakan")
            }
        }
        val detNodes = g.countNodes() - nodesBefore
        val detEdges = g.countEdges() - edgesBefore
        val detSkipped = skippedMsgs + skippedTasks
        val pendingMsgCountVc240 = msgs.length()
        val pendingTaskTextsVc240 = curTaskTexts.toList()
        if (!deferMark) saveGraphMark(ctx, roomIdStr, msgs.length(), curTaskTexts)
        try {
            val rr = roomById(ctx, roomIdStr) ?: roomJson
            val ss = sessions(rr)
            for (si in 0 until ss.length()) { val s = ss.optJSONObject(si) ?: continue; rebuildSessionIndex(rr, s) }
        } catch (e: Exception) {}
        return DetResult(detNodes, detEdges, detSkipped, candBatch, emptyList(), pendingMsgCountVc240, pendingTaskTextsVc240)
    }

    // vc236 M24: konsolidasi deterministik atas episode chat 1-1.
    // Cerminan consolidateDeterministic room, tetapi sumbernya episode
    // "chat1-1" (ditulis tiap pesan oleh MemoryDb.addHistory), bukan
    // pesan room. Mark per-sesi memakai graphMark yang sama dengan kunci
    // "chat1-1" (msgCount = jumlah episode yang sudah diproses; id room
    // selalu "auto_<ts>" atau "s<ts>_..." sehingga tidak bertabrakan);
    // run berikutnya hanya memproses episode baru. Node grup "Chat 1-1"
    // menampung keputusan/temuan; kandidat resolusi entitas dikumpulkan
    // untuk adjudikasi LLM oleh GraphJobs — sama seperti room. Jalur
    // room tidak diubah; fungsi ini hanya membaca episode dan menulis
    // node/edge graph baru. Fase LLM (relations + verifier + adjudikasi)
    // dipakai ulang lewat GraphJobs.runLlmConsolPhase.
    fun consolidateDeterministicChat11(ctx: Context, deferMark: Boolean = false): DetResult {
        val g = GraphDb.get(ctx)
        val mark = graphMark(ctx, "chat1-1")
        val prevCount = mark.optInt("msgCount", 0)
        val total = g.countEpisodes("chat1-1")
        val skipped = minOf(prevCount, total)
        val eps = if (total > skipped) g.episodesForSession("chat1-1", skipped, total - skipped) else emptyList()
        val nodesBefore = g.countNodes()
        val edgesBefore = g.countEdges()
        val cn = g.getOrCreateNode("chat", "Chat 1-1", "chat1-1")
        val grp = g.getOrCreateNode("grup", "Chat 1-1", "chat1-1")
        fun linkMember(id: Long) {
            if (id > 0 && grp > 0) g.addEdge(grp, id, "anggota")
        }
        val candBatch = ArrayList<Triple<String, Long, List<Long>>>()
        fun collectCand(label: String, nodeId: Long, existedBefore: Boolean) {
            if (existedBefore || nodeId <= 0) return
            val c = try { g.fuzzyCandidates(label) } catch (e: Exception) { emptyList() }
            val ids = c.filter { it.id != nodeId }.map { it.id }
            if (ids.isNotEmpty()) candBatch.add(Triple(label, nodeId, ids))
        }
        val tail = ArrayList<String>()
        for (ep in eps) {
            val t = ep.text
            val sender = ep.role
            tail.add(sender + ": " + t)
            if (sender != "user" && sender != "sistem" && sender != "laporan" && sender.isNotBlank()) {
                val an = g.getOrCreateNode("agent", sender, "builtin")
                if (an > 0) linkMember(an)
            }
            var inFence = false
            for (line in t.lines()) {
                val lt = line.trim()
                if (lt.startsWith("```")) { inFence = !inFence; continue }
                if (inFence || lt.startsWith(">")) continue
                if (lt.startsWith("Keputusan:", true) && lt.length > 12) {
                    val body = lt.substringAfter(":").trim()
                    val kIdx = body.indexOf("karena", ignoreCase = true)
                    val decText = if (kIdx >= 0) body.substring(0, kIdx).trim().trimEnd(',', '.', ' ') else body
                    val lab = decText.take(160)
                    val existed = g.nodeByLabel(lab) != null
                    val dn = g.getOrCreateNode("keputusan", lab, "chat1-1")
                    collectCand(lab, dn, existed)
                    if (dn > 0) { linkMember(dn); if (cn > 0) g.addEdge(cn, dn, "menghasilkan", episodeId = ep.id) }
                    if (kIdx >= 0) {
                        val reason = body.substring(kIdx + 6).trim().trimStart(':', ' ').take(160)
                        if (reason.length > 3) {
                            val ran = g.getOrCreateNode("rationale", reason, "chat1-1")
                            if (ran > 0 && dn > 0) { g.addEdge(ran, dn, "rationale_for", episodeId = ep.id); linkMember(ran) }
                        }
                    }
                } else if ((lt.startsWith("Penting:", true) || lt.startsWith("Temuan:", true)) && lt.length > 12) {
                    val lab = lt.take(160)
                    val existed = g.nodeByLabel(lab) != null
                    val nn = g.getOrCreateNode("temuan", lab, "chat1-1")
                    collectCand(lab, nn, existed)
                    if (nn > 0) { linkMember(nn); if (cn > 0) g.addEdge(cn, nn, "menghasilkan", episodeId = ep.id) }
                }
            }
        }
        val detNodes = g.countNodes() - nodesBefore
        val detEdges = g.countEdges() - edgesBefore
        if (!deferMark) saveGraphMark(ctx, "chat1-1", total, emptyList())
        return DetResult(detNodes, detEdges, skipped, candBatch, tail, total, emptyList())
    }
}

data class DetResult(val nodes: Int, val edges: Int, val skipped: Int, val candBatch: List<Triple<String, Long, List<Long>>>, val tail: List<String> = emptyList(), val pendingMsgCount: Int = 0, val pendingTaskTexts: List<String> = emptyList())
