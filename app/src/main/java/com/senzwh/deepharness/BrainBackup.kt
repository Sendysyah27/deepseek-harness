package com.senzwh.deepharness

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * v4.12.0 — Backup & Pulihkan Otak: zip berisi folder otak semua agen
 * (filesDir/otak*) + settings.json (snapshot SharedPreferences "harness"
 * TANPA kunci berbau token/kredensial). Token DeepSeek TIDAK ikut —
 * sesudah pulih, login/slot diisi ulang manual (itu memang desain aman).
 * vc245 GELOMBANG 4 (K-09, pilihan ekspor DB): zip JUGA berisi
 * memory-db.json (exportJson: fakta/skill/jadwal/aturan) yang diimpor
 * kembali saat restore. vc301 SF-A1: exportJson kini ikut eval per skill
 * + riwayat versi skill (skill_versions). JUJUR: graph, riwayat chat,
 * dan goal TIDAK ikut — exportJson tidak mencakupnya, dan pesan sukses
 * menyebut itu eksplisit.
 */
object BrainBackup {

    private fun excluded(key: String): Boolean {
        val k = key.lowercase()
        // vc261 H-M16/N-M18: kunci pengaman TIDAK PERNAH ikut backup /
        // restore — zip buatan tidak bisa mematikan kill switch, job-off,
        // auto-allow, root mode, atau blocklist GUI.
        return k.contains("token") || k.startsWith("dsslot") || k.contains("cookie") || k.contains("password") || k.contains("pin") ||
            k == "f1off" || k == "f1" || k == "jobsoff" || k == "autoallow" || k == "autoallowtools" || k == "rootmode" || k == "guiblocklist"
    }

    // vc261 N-M18: ALLOWLIST kunci settings.json yang BOLEH diterapkan
    // saat restore. Kunci di luar daftar ini dilewati + dihitung di pesan
    // sukses (jujur, bukan diam-diam). Kunci pengaman (f1Off, jobsOff,
    // autoAllow, rootMode, guiBlocklist, PIN) TIDAK ADA di daftar ini —
    // restore tidak pernah mengubahnya. Awalan dinamis ditangani
    // SETTINGS_ALLOW_PREFIXES di bawah.
    // vc265 N2-M1 + N2-m5: bgPending, bgSecondConfirm (antrean parkir
    // legacy — restore tidak boleh membangkitkan H-K1) dan browserBlocklist
    // (daftar blokir domain sensitif) DIKELUARKAN dari allowlist.
    val SETTINGS_ALLOWLIST = setOf(
        "apiMode", "askExtendSteps", "bgSessionDead", "debugPromptDump", "defSearch", "defThink",
        "dsLogin", "f2QuietInit", "f2p1PromptFix", "f4Purged", "flowLogin", "flowOnboarded", "flowSetup",
        "govWifiOnly", "operatorMode", "modeBebas", "notifDigest", "overlayOn", "panelSpeak", "proactiveOn",
        "profilLanjutanOpen", "screenWatchOn", "serveStandby", "shakeSummon", "voiceAuto", "wakeWordOn",
        "persona", "bubbleTap", "bubbleAlphaPct", "bubbleSizeDp", "ckptDiff", "dsSessEvents", "emergencyNumber",
        "privAdbHost", "privAdbPort", "rootStatus", "routines", "serveSeenVersion", "briefingHour",
        "chatFontPx", "govQuietEnd", "govQuietStart", "maxToolSteps", "msgCount", "resultSeq",
        "bgDeadRetryTs", "bgLastRateLimitTs", "ckptPending", "currentSession", "govDelayNotifTs",
        "notifSessionId", "ocCurrentTask", "serveDownNotifTs", "serveF3TickTs", "tokInAll", "tokInSess",
        "tokOutAll", "tokOutSess", "turnCkptResumedFor", "agentEngine", "behaviorPack", "bgLastBlock",
        "dsApiDiag", "dsModelVariant",
        "pendingExtractProposals", "quickChipsNative", "redactMigrationReport", "sessDefDivMode",
        "sessOutRoot", "teamBoard", "termHistNative", "themeMode", "turnCkpt", "turnJournal"
    )
    val SETTINGS_ALLOW_PREFIXES = setOf("msgCount_", "agentRole_", "daily_", "histSummary_", "histSummaryHash_")

    private fun settingsAllowed(k: String): Boolean =
        k in SETTINGS_ALLOWLIST || SETTINGS_ALLOW_PREFIXES.any { k.startsWith(it) }

    // vc261 H-M16: validasi entri server MCP kustom dari zip — tiap entri
    // wajib punya id + transport http/stdio + headerKind yang dikenal.
    private fun isValidMcpCustom(v: String): Boolean {
        if (v.length > 100000) return false
        return try {
            val arr = org.json.JSONArray(v)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: return false
                if (o.optString("id").isBlank()) return false
                if (o.optString("transport") !in setOf("http", "stdio")) return false
                if (o.optString("headerKind") !in setOf("bearer", "goog-api-key", "none")) return false
            }
            true
        } catch (e: Exception) { false }
    }

    fun backup(ctx: Context): String {
        return try {
            // vc246 GELOMBANG 5 (FT-m15): stempel detik (dulu menit —
            // dua backup dalam menit yang sama bertabrakan nama file).
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val out = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HarnessBackup-$stamp.zip")
            val zos = ZipOutputStream(FileOutputStream(out))
            var files = 0
            val base = ctx.filesDir
            base.listFiles()?.forEach { d ->
                if (d.isDirectory && (d.name == "otak" || d.name.startsWith("otak-"))) {
                    d.walkTopDown().filter { it.isFile }.forEach { f ->
                        zos.putNextEntry(ZipEntry(d.name + "/" + f.relativeTo(d).path))
                        if (f.name.endsWith(".md")) {
                            // vc246 GELOMBANG 5 (FT-m17): bila redaksi
                            // GAGAL, backup DIBATALKAN — dulu fallback
                            // menulis teks mentah (token bisa lolos).
                            try { zos.write(RedactSecrets.redact(f.readText(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)) } catch (ex: Exception) { throw Exception("redaksi gagal untuk ${f.name} — backup dibatalkan, teks mentah tidak ditulis") }
                        } else FileInputStream(f).use { it.copyTo(zos) }
                        zos.closeEntry(); files++
                    }
                }
            }
            val prefs = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
            val sj = JSONObject()
            for ((k, v) in prefs.all) {
                if (excluded(k)) continue
                when (v) {
                    is Boolean -> sj.put(k, v)
                    is Int -> sj.put(k, v)
                    is Long -> sj.put(k, v)
                    is Float -> sj.put(k, v.toDouble())
                    is String -> sj.put(k, RedactSecrets.redact(v))
                }
            }
            zos.putNextEntry(ZipEntry("settings.json"))
            zos.write(sj.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            // vc246 GELOMBANG 5 (FT-m16): Client ID MCP + daftar server
            // kustom + status aktif ikut di zip sebagai mcp-servers.json
            // (dulu tidak ikut backup sama sekali). Token TIDAK ikut —
            // token hanya di SecureStore dan sengaja dikecualikan.
            try {
                val mp = ctx.getSharedPreferences("mcpServers", Context.MODE_PRIVATE)
                val mj = JSONObject()
                for ((k, v) in mp.all) {
                    when (v) {
                        is Boolean -> mj.put(k, v)
                        is String -> mj.put(k, RedactSecrets.redact(v))
                        is Int -> mj.put(k, v)
                        is Long -> mj.put(k, v)
                    }
                }
                zos.putNextEntry(ZipEntry("mcp-servers.json"))
                zos.write(mj.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            } catch (e: Exception) {}
            // vc245 GELOMBANG 4 (K-09, pilihan ekspor DB): ekspor memori DB
            // ikut di zip sebagai memory-db.json. Redaksi yang sama dengan
            // berkas .md (pola kredensial disamarkan, struktur JSON utuh).
            // vc301 SF-A1: exportJson kini ikut eval per skill + riwayat
            // versi skill (skill_versions) — backup tidak lagi meratakan
            // newline (esc() diperbaiki) dan tidak lagi menghilangkan
            // eval/versi. Jujur: graph, riwayat CHAT, dan goal tetap
            // TIDAK ikut — exportJson tidak mencakupnya.
            var dbItems = 0
            var dbVersionsVc301 = 0
            try {
                val dbJson = RedactSecrets.redact(MemoryDb.get(ctx).exportJson())
                val dj = JSONObject(dbJson)
                dbItems = (dj.optJSONArray("facts")?.length() ?: 0) + (dj.optJSONArray("skills")?.length() ?: 0) + (dj.optJSONArray("schedules")?.length() ?: 0) + (dj.optJSONArray("rules")?.length() ?: 0)
                dbVersionsVc301 = dj.optJSONArray("skill_versions")?.length() ?: 0
                zos.putNextEntry(ZipEntry("memory-db.json"))
                zos.write(dbJson.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            } catch (e: Exception) { dbItems = -1 }
            zos.close()
            "Backup selesai: ${out.name} ($files berkas otak + pengaturan" + (if (dbItems >= 0) " + ekspor memori DB $dbItems item (fakta/skill/jadwal/aturan)" + (if (dbVersionsVc301 > 0) " + riwayat versi skill $dbVersionsVc301 versi" else " + riwayat versi skill 0 versi") else " — ekspor memori DB GAGAL, tidak ikut") + ") di folder Download. Token/login TIDAK ikut — itu disengaja. Graph/riwayat chat/goal TIDAK ikut backup ini."
        } catch (e: Exception) {
            "Backup GAGAL: ${e.message}"
        }
    }

    fun restore(ctx: Context): String {
        val src = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "harness-restore.zip")
        if (!src.exists()) return "File harness-restore.zip tidak ditemukan di folder Download. Taruh hasil backup dengan nama persis itu dulu."
        return try {
            var files = 0
            val zf = ZipFile(src)
            val en = zf.entries()
            var settingsJson: String? = null
            var memoryDbJson: String? = null
            var mcpJson: String? = null
            var skippedMd = 0
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                val name = e.name
                if (name == "memory-db.json") {
                    memoryDbJson = zf.getInputStream(e).readBytes().toString(Charsets.UTF_8)
                    continue
                }
                if (name == "settings.json") {
                    settingsJson = zf.getInputStream(e).readBytes().toString(Charsets.UTF_8)
                    continue
                }
                // vc261 N-M14: baca SEBELUM zf.close() — dulu dibaca
                // setelah close sehingga selalu IllegalStateException
                // yang ditelan catch kosong (restore MCP tidak pernah jalan).
                if (name == "mcp-servers.json") {
                    mcpJson = zf.getInputStream(e).readBytes().toString(Charsets.UTF_8)
                    continue
                }
                // Penjaga jalur: hanya entri otak*/ yang boleh ditulis.
                if (name.contains("..") || name.startsWith("/") || !(name.startsWith("otak/") || name.startsWith("otak-"))) continue
                val target = File(ctx.filesDir, name)
                target.parentFile?.mkdirs()
                if (!e.isDirectory) {
                    if (name.endsWith(".md")) {
                        // vc261 N-M18: fail-CLOSED — bila redaksi gagal,
                        // file DILEWATI (dulu: disalin mentah, token bisa
                        // lolos). Bukan .md tetap disalin apa adanya.
                        try {
                            val txt = zf.getInputStream(e).readBytes().toString(Charsets.UTF_8)
                            FileOutputStream(target).use { it.write(RedactSecrets.redact(txt).toByteArray(Charsets.UTF_8)) }
                            files++
                        } catch (ex: Exception) { skippedMd++ }
                    } else { zf.getInputStream(e).use { input -> FileOutputStream(target).use { input.copyTo(it) } }; files++ }
                }
            }
            zf.close()
            var skippedSettings = 0
            if (settingsJson != null) {
                val sj = JSONObject(settingsJson)
                val ed = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).edit()
                val keys = sj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    // vc261 N-M18: hanya kunci allowlist yang diterapkan;
                    // sisanya dilewati + dihitung (pesan sukses jujur).
                    if (excluded(k) || !settingsAllowed(k)) { skippedSettings++; continue }
                    // vc265 V-m7 (vc299: plafon naik 50 → 200): maxToolSteps hasil restore di-clamp ke 200
                    // (batas UI) — backup buatan tidak boleh menaikkan cap
                    // langkah di atas 200 tanpa lewat UI.
                    val vv: Any = if (k == "maxToolSteps") when (val raw = sj.get(k)) {
                        is Int -> raw.coerceIn(0, 200)
                        is Long -> raw.coerceIn(0L, 200L)
                        is Number -> raw.toLong().coerceIn(0L, 200L)
                        else -> raw
                    } else sj.get(k)
                    when (val v = vv) {
                        is Boolean -> ed.putBoolean(k, v)
                        is Int -> ed.putInt(k, v)
                        is Long -> ed.putLong(k, v)
                        is Double -> ed.putFloat(k, v.toFloat())
                        is String -> ed.putString(k, RedactSecrets.redact(v))
                        is Number -> ed.putLong(k, v.toLong())
                    }
                }
                ed.apply()
                // vc265 N2-M1 (defense in depth): backup yang di-restore
                // SEBELUM fix ini mungkin sudah menulis antrean parkir
                // legacy ke prefs — hapus eksplisit sesudah restore,
                // supaya H-K1 tidak bangkit lagi lewat state lama.
                // Ini pembersihan state, bukan gate/dialog.
                ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).edit()
                    .remove("bgPending").remove("bgSecondConfirm").apply()
            }
            // vc245 GELOMBANG 4 (K-09): impor memory-db.json bila ada.
            // Semantik sama dengan restore sync_memory: fakta di-upsert,
            // skill/jadwal/aturan ditambahkan (bukan hapus total), jadi
            // item lama yang tidak ada di backup tetap ada.
            // vc301 SF-A1b/c: eval per skill ikut pulih (setSkillEval
            // sesudah forgeUpsert) + riwayat versi ikut pulih dengan
            // nomor asli (importSkillVersion, sesudah loop skill).
            var dbImported = 0
            var skillsBlockedVc265 = 0
            var versionsRestoredVc301 = 0
            var versionsSkippedVc301 = 0
            if (memoryDbJson != null) {
                try {
                    val root = JSONObject(memoryDbJson)
                    val mdb = MemoryDb.get(ctx)
                    // vc261 N-M16: hormati "confirmed" dari ekspor
                    // (default false bila zip lama tanpa kolom itu) — fakta
                    // belum-dikonfirmasi tidak lagi naik jadi terkonfirmasi.
                    root.optJSONArray("facts")?.let { fa -> for (i in 0 until fa.length()) { val o = fa.optJSONObject(i) ?: continue; mdb.putFact(o.optString("k"), o.optString("v"), o.optBoolean("confirmed", false), "restore:backup"); dbImported++ } }
                    // vc261 N-M17: restore skill via forgeUpsert (tanpa
                    // duplikat, descr + enabled ikut, slug sistem ditolak).
                    // vc265 N2-m2: skill hasil restore dipindai dulu lewat
                    // SkillSpector — skor >= 80 DITOLAK (tidak diimpor)
                    // dan penolakan dicatat di hasil restore.
                    // vc301: source='restore' (bukan default 'forge') +
                    // eval ikut pulih; snapshot yang dibuat forgeUpsert
                    // dihapus bila skill itu sebelumnya TANPA versi
                    // (dedup restore — penomoran asli dari backup pulih
                    // utuh lewat importSkillVersion di bawah).
                    root.optJSONArray("skills")?.let { sk -> for (i in 0 until sk.length()) { val o = sk.optJSONObject(i) ?: continue; val sName = o.optString("name"); if (Tools.slugifySkillName(sName) in Tools.SYSTEM_SKILL_SLUGS) continue; val specVc265 = try { SkillSpector.scan(sName, o.optString("descr"), o.optString("instruction")) } catch (e: Exception) { null }; if (specVc265 != null && specVc265.blocked) { skillsBlockedVc265++; try { AttentionBudget.noteSkillBlocked(ctx) } catch (e: Exception) {}; continue }; val preVc301 = try { mdb.skillByName(sName)?.let { mdb.latestSkillVersion(it.id) } } catch (e: Exception) { null }; val (sId, _) = mdb.forgeUpsert(sName, o.optString("instruction"), o.optString("descr"), "restore"); if (sId > 0 && o.has("enabled")) mdb.setSkillEnabled(sId, o.optBoolean("enabled")); if (sId > 0 && o.optString("eval").isNotBlank()) mdb.setSkillEval(sId, o.optString("eval")); if (sId > 0 && preVc301 == null) { try { mdb.latestSkillVersion(sId)?.let { if (it.source == "restore") mdb.deleteSkillVersionRow(it.id) } } catch (e: Exception) {} }; if (sId > 0) dbImported++ } }
                    // vc301 SF-A1c: pulihkan riwayat versi dengan nomor
                    // asli. Skill dicari by NAME (id bisa beda sesudah
                    // restore); versi yang nomornya sudah ada dilewati.
                    root.optJSONArray("skill_versions")?.let { vs -> for (i in 0 until vs.length()) { val o = vs.optJSONObject(i) ?: continue; val sidVc301 = try { mdb.skillByName(o.optString("skill_name"))?.id ?: -1 } catch (e: Exception) { -1 }; if (sidVc301 <= 0) { versionsSkippedVc301++; continue }; if (mdb.importSkillVersion(sidVc301, o.optInt("version_no", -1), o.optString("instruction"), o.optString("descr"), o.optInt("judge_total", -1), o.optString("judge_grade"), o.optString("eval_json"), o.optString("source", "forge"), o.optLong("created_ts", 0))) versionsRestoredVc301++ else versionsSkippedVc301++ } }
                    root.optJSONArray("schedules")?.let { sc -> for (i in 0 until sc.length()) { val o = sc.optJSONObject(i) ?: continue; mdb.addSchedule(o.optInt("hour", 7), o.optInt("minute", 0), o.optString("prompt"), o.optLong("ownerSession", 0)); dbImported++ } }
                    root.optJSONArray("rules")?.let { ru -> for (i in 0 until ru.length()) { val o = ru.optJSONObject(i) ?: continue; mdb.addRule(o.optString("pkg"), o.optString("needle"), o.optString("action")); dbImported++ } }
                } catch (e: Exception) { dbImported = -1 }
            }
            // vc246 GELOMBANG 5 (FT-m15 + FT-m16 restore): terapkan
            // mcp-servers.json bila ada + nyatakan semantik gabung vs
            // timpa per bagian (dulu restore "menggabung sebagian data"
            // tanpa pernyataan).
            var mcpRestoredVc246 = false
            var mcpSkipped = 0
            var mcpApplied = 0
            if (mcpJson != null) {
                try {
                    val mj = JSONObject(mcpJson)
                    val ed2 = ctx.getSharedPreferences("mcpServers", Context.MODE_PRIVATE).edit()
                    val keys2 = mj.keys()
                    while (keys2.hasNext()) {
                        val k = keys2.next()
                        // vc261 H-M16: hanya "custom" (tervalidasi) dan
                        // "clientId_<id>" yang diterima; kunci lain dilewati.
                        if (k != "custom" && !k.startsWith("clientId_")) { mcpSkipped++; continue }
                        when (val v = mj.get(k)) {
                            is Boolean -> { ed2.putBoolean(k, v); mcpApplied++ }
                            is String -> {
                                if (k == "custom" && !isValidMcpCustom(v)) { mcpSkipped++; continue }
                                ed2.putString(k, RedactSecrets.redact(v)); mcpApplied++
                            }
                            is Int -> { ed2.putInt(k, v); mcpApplied++ }
                            is Long -> { ed2.putLong(k, v); mcpApplied++ }
                            else -> { mcpSkipped++; continue }
                        }
                    }
                    ed2.apply(); mcpRestoredVc246 = mcpApplied > 0
                } catch (e: Exception) { mcpRestoredVc246 = false }
            }
            "Pulih selesai: $files berkas otak dipulihkan" + (if (skippedMd > 0) " ($skippedMd berkas .md dilewati — redaksi gagal, tidak disalin mentah)" else "") + (if (settingsJson != null) " + pengaturan diterapkan" + (if (skippedSettings > 0) " ($skippedSettings kunci dilewati — tidak diizinkan restore)" else "") else "") + (if (memoryDbJson != null) (if (dbImported >= 0) " + memori DB diimpor ($dbImported item: fakta/skill/jadwal/aturan" + (if (versionsRestoredVc301 > 0 || versionsSkippedVc301 > 0) "; riwayat versi skill: $versionsRestoredVc301 dipulihkan, $versionsSkippedVc301 dilewati (sudah ada / skill tidak ditemukan)" else "; riwayat versi skill: tidak ada di backup ini (zip lama — versi TIDAK ikut pulih)") + ")" + (if (skillsBlockedVc265 > 0) " ($skillsBlockedVc265 skill DITOLAK SkillSpector — skor risiko >= 80, tidak diimpor)" else "") else " + memori DB GAGAL diimpor") else " + TANPA memori DB (zip lama tanpa memory-db.json — fakta/skill tidak ikut pulih)") + (if (mcpRestoredVc246) " + Client ID/daftar server MCP dipulihkan" else " + TANPA data server MCP (zip lama)") + (if (mcpSkipped > 0) " ($mcpSkipped entri MCP dilewati — tidak valid)" else "") + ". Semantik restore: pengaturan DITIMPA per kunci (hanya allowlist); memori DB DIGABUNG (upsert — item lama yang tidak ada di backup tetap ada). Graph/riwayat chat/goal TIDAK dipulihkan oleh backup ini. Restart app agar semua kebaca bersih. Login DeepSeek/slot isi ulang manual (token tidak ikut backup)."
        } catch (e: Exception) {
            "Pulih GAGAL: ${e.message}"
        }
    }
}
