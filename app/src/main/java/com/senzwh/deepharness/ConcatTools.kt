package com.senzwh.deepharness

import android.content.Context
import org.json.JSONObject

// ConcatTools.kt — handler tool concat_* vc314+vc315 (Worker B).
// Pola AgentBrowser (BrowserHost.kt): satu object, dispatch dari
// Tools.execute memanggil ConcatTools.run(ctx, name, args).
// Token: SecureStore key "concat_api_token" — TIDAK PERNAH di-log,
// TIDAK PERNAH tampil di toast/snackbar/teks mana pun, TIDAK PERNAH
// dibaca dari file/automation.json. Kosong -> error jujur persis
// NO_TOKEN_MESSAGE (tanpa fallback, tanpa retry diam-diam).
// ConcatApiException -> pesannya dikembalikan verbatim.
// concat WRITE (project_open vc314 + semua WRITE vc315) adalah WRITE ->
// otomatis REVIEW bila gerbang hidup (turunan RISKY_TOOLS, tanpa
// pengecualian ala browser); BUKAN HIGH (tidak ada di ToolGate.HIGH_TOOLS).
// vc316 N9-B1/C1: selagi gerbang MATI, op yang MERUSAK tetap butuh
// KONFIRMASI KHUSUS (dialog Ya/Batal di MainActivity — lihat
// requiresSpecialConfirmVc316 / describeDestructiveConcatCallVc316);
// READ + WRITE non-destruktif tidak kena konfirmasi ini.
// Saklar "Perintah Concat" tetap SATU (tanpa daftar enable per-tool).
object ConcatTools {

    const val TOKEN_KEY = "concat_api_token"
    const val MIN_TOKEN_LENGTH = 20
    const val NO_TOKEN_MESSAGE = "token Concat belum di-set — buka Setup → Concat, ketik token sekali"

    private const val PREFS = "harness"
    private const val ENABLED_KEY = "concat_enabled"
    private const val LAST_CHECK_KEY = "concat_last_check"
    private const val LAST_VERSION_KEY = "concat_last_version"

    /** Versi fallback bila belum pernah terhubung (fork v0.2.10). */
    const val FALLBACK_VERSION = "0.2.10"

    /** Allowlist 58 op edit.apply (OPS-TABLE.md, fork v0.2.10).
     *  `matchClip` TIDAK masuk — tidak menyeberang API (source: #[serde(skip)]). */
    val EDIT_OPS: Set<String> = setOf(
        "addMedia", "removeMedia", "setMediaPlaceholder", "fillSlot", "batch",
        "addClip", "addClipAtFirstFree", "addClipsInSequence",
        "addTextClip", "addShapeClip", "addLayerClip", "addTransitionClip",
        "setClipSpeedCurve", "setClipKey", "clearClipKey", "clearClipKeys",
        "setEffectKey", "clearEffectKey", "clearEffectKeys",
        "setMaskKey", "clearMaskKey", "clearMaskKeys",
        "setClipCutout", "setClipStabilize", "setClipVoice", "addCutoutStroke",
        "moveClips", "trimClip", "splitClips", "replaceClipMedia",
        "setClipEnhanced", "freezeFrame", "mergeClips", "consolidateClips",
        "removeClips", "updateClip", "styleTextRange", "clearTextStyles",
        "setClipSpeed", "setClipTransform", "frameClip",
        "detachAudio", "reattachAudio",
        "addTrack", "removeTrack", "setTrackFlag",
        "addTimeline", "removeTimeline", "setTimelineVideo",
        "setTimelineBackground", "setTimelineLinkage", "renameTimeline",
        "selectTimeline", "moveTimeline",
        "addFont", "removeFont", "updateMediaPath", "setMediaColorRange"
    )

    /** vc316 N9-B1/C1 — op `concat_edit_apply` yang MERUSAK (denylist
     *  eksplisit, cocok persis nama op — BUKAN substring). Daftar ini
     *  keputusan user 15 Nov 09:00 (audit N9-C1): removeMedia,
     *  removeClips, removeTrack, removeTimeline, removeFont,
     *  clearClipKeys, clearEffectKeys, clearMaskKeys, clearTextStyles
     *  (tanpa rentang), mergeClips, consolidateClips. Op lain (tambah /
     *  ubah / set / undo / redo) TIDAK masuk — bisa dibatalkan (undo)
     *  atau tidak menghapus apa pun. `clearClipKey` / `clearEffectKey` /
     *  `clearMaskKey` (tunggal, satu keyframe) TIDAK masuk — hanya
     *  menghapus satu keyframe, bukan seluruh deret. */
    val DESTRUCTIVE_EDIT_OPS_VC316: Set<String> = setOf(
        "removeMedia", "removeClips", "removeTrack", "removeTimeline",
        "removeFont", "clearClipKeys", "clearEffectKeys", "clearMaskKeys",
        "clearTextStyles", "mergeClips", "consolidateClips"
    )

    /** Penjelasan bahasa Indonesia per op destruktif (untuk teks dialog). */
    private val DESTRUCTIVE_EDIT_OP_DESCRIPTIONS_VC316: Map<String, String> = mapOf(
        "removeMedia" to "menghapus media dari katalog proyek",
        "removeClips" to "menghapus klip dari timeline",
        "removeTrack" to "menghapus satu track beserta semua klip di dalamnya",
        "removeTimeline" to "menghapus satu timeline beserta isinya",
        "removeFont" to "menghapus satu font dari proyek",
        "clearClipKeys" to "menghapus SEMUA keyframe animasi dari klip",
        "clearEffectKeys" to "menghapus SEMUA keyframe efek dari klip",
        "clearMaskKeys" to "menghapus SEMUA keyframe mask dari klip",
        "clearTextStyles" to "menghapus SEMUA gaya teks dari klip",
        "mergeClips" to "menggabungkan beberapa klip menjadi satu (klip asal hilang)",
        "consolidateClips" to "menggabungkan klip + media menjadi satu (klip asal hilang)"
    )

    /** Cek rekursif: apakah nilai JSON ini memuat op destruktif di mana
     *  pun (batch bersarang)? Objek dengan kunci "op" yang nilainya ada
     *  di denylist = destruktif. Dipakai untuk op "batch" (wadah banyak
     *  perintah edit) supaya op destruktif tidak bisa diselundupkan
     *  lewat batch. */
    private fun jsonHasDestructiveOpVc316(v: Any?): Boolean {
        return when (v) {
            is JSONObject -> {
                if (v.optString("op", "") in DESTRUCTIVE_EDIT_OPS_VC316) true
                else {
                    var hit = false
                    val keys = v.keys()
                    while (keys.hasNext()) {
                        if (jsonHasDestructiveOpVc316(v.opt(keys.next()))) { hit = true; break }
                    }
                    hit
                }
            }
            is org.json.JSONArray -> {
                var hit = false
                for (i in 0 until v.length()) {
                    if (jsonHasDestructiveOpVc316(v.opt(i))) { hit = true; break }
                }
                hit
            }
            else -> false
        }
    }

    /** Daftar op destruktif di dalam nilai JSON (untuk teks dialog). */
    private fun destructiveOpsInJsonVc316(v: Any?, out: LinkedHashSet<String>) {
        when (v) {
            is JSONObject -> {
                val op = v.optString("op", "")
                if (op in DESTRUCTIVE_EDIT_OPS_VC316) out.add(op)
                val keys = v.keys()
                while (keys.hasNext()) destructiveOpsInJsonVc316(v.opt(keys.next()), out)
            }
            is org.json.JSONArray -> for (i in 0 until v.length()) destructiveOpsInJsonVc316(v.opt(i), out)
        }
    }

    /** vc316 N9-B1/C1: true bila panggilan tool Concat ini MERUSAK dan
     *  WAJIB minta KONFIRMASI KHUSUS sebelum dieksekusi — berlaku selagi
     *  GATES_DISABLED=true (bila gerbang hidup, WRITE Concat sudah REVIEW
     *  lewat ToolGate; cek ini tidak menggantikan gerbang). BUKAN gate,
     *  BUKAN HIGH+PIN: hanya penanda "tanya user dulu".
     *  - concat_project_open (B1): SELALU — membuka proyek apa pun.
     *  - concat_project_close (C1): hanya bila save=false (default) =
     *    edit yang belum disimpan DIBUANG; save=true aman.
     *  - concat_edit_apply (C1): hanya bila op-nya ada di denylist
     *    DESTRUCTIVE_EDIT_OPS_VC316; op "batch" dicek isinya (rekursif).
     *  - delete_skill (vc334 R5 F2): SELALU — penghapusan skill agen
     *    tidak punya varian aman; dialog Ya/Batal sekali per panggilan
     *    walau GATES_DISABLED=true (fail-closed).
     *  - Tool lain (READ + WRITE non-destruktif): false.
     *  Murni fungsi atas (tool, args) — tanpa Context, tanpa I/O —
     *  supaya bisa dites di JVM. */
    fun requiresSpecialConfirmVc316(tool: String, args: JSONObject): Boolean = when (tool) {
        "concat_project_open" -> true
        "concat_project_close" -> !args.optBoolean("save", false)
        "concat_edit_apply" -> {
            val op = args.optString("op").trim()
            if (op.isBlank()) false
            else if (op in DESTRUCTIVE_EDIT_OPS_VC316) true
            else if (op == "batch") jsonHasDestructiveOpVc316(args.opt("args"))
            else false
        }
        // vc334 R5 F2: delete_skill ikut pengecualian konfirmasi khusus
        // (hipotesis H-1 audit W3: satu panggilan model = satu skill
        // hilang seketika di semua lane selagi GATES_DISABLED=true).
        // SELALU true — penghapusan skill tidak punya varian aman.
        "delete_skill" -> true
        else -> false
    }

    /** vc316 N9-B1/C1: teks dialog konfirmasi khusus untuk panggilan
     *  yang requiresSpecialConfirmVc316-nya true. Panggilan lain -> "".
     *  Menyebut NAMA proyek + op-nya (dipotong 80 char) supaya user
     *  tahu persis apa yang akan dijalankan. */
    fun describeDestructiveConcatCallVc316(tool: String, args: JSONObject): String {
        val proj = args.optString("project").trim().take(80)
        return when (tool) {
            "concat_project_open" -> "Membuka proyek Concat \"$proj\" via API. Proyek yang sedang terbuka di layar Concat harus sudah ditutup dulu — bila belum, server menolak (lock)."
            "concat_project_close" -> "Menutup proyek \"$proj\" TANPA MENYIMPAN — semua edit yang belum disimpan akan DIBUANG dan tidak bisa dipulihkan (undo tidak menolong sesudah close)."
            "concat_edit_apply" -> {
                val op = args.optString("op").trim()
                if (op == "batch") {
                    val found = LinkedHashSet<String>()
                    destructiveOpsInJsonVc316(args.opt("args"), found)
                    if (found.isEmpty()) "" else "Menjalankan sekumpulan perintah edit (batch) pada proyek \"$proj\" yang berisi perintah MERUSAK: ${found.joinToString(", ")} — isi yang dihapus tidak bisa dikembalikan kecuali undo sebelum proyek ditutup."
                } else {
                    val d = DESTRUCTIVE_EDIT_OP_DESCRIPTIONS_VC316[op] ?: return ""
                    "Menjalankan perintah edit \"$op\" pada proyek \"$proj\" — $d. Isi yang dihapus tidak bisa dikembalikan kecuali undo (concat_edit_undo) sebelum proyek ditutup."
                }
            }
            // vc334 R5 F2: teks dialog hapus skill — menyebut id + arsip.
            "delete_skill" -> "Menghapus skill #${args.optLong("id", -1)} secara permanen — skill hilang dari tabel skills. Hapusan diarsipkan 30 hari — pulihkan lewat Setup 66."
            else -> ""
        }
    }

    /** Nilai kind yang dikenal concat_catalogue_list. */
    private val CATALOGUE_KINDS: Set<String> =
        setOf("effect", "filter", "audio", "transition", "generator")

    /** Validasi token saat Simpan (Setup → Concat). null = lolos.
     *  Aturan: tidak kosong, >= 20 karakter (syarat server, SPEC §4.2).
     *  Aturan non-repetitif TIDAK dicek lokal — diserahkan ke server. */
    fun validateTokenForSave(token: String): String? {
        val t = token.trim()
        if (t.isBlank()) return "Token masih kosong — belum disimpan."
        if (t.length < MIN_TOKEN_LENGTH) return "Token kependekan: baru " + t.length + " karakter, paling sedikit " + MIN_TOKEN_LENGTH + " karakter. Belum disimpan."
        return null
    }

    /** Switch "Tool Concat aktif" di layar setup. Default true. */
    fun isEnabled(ctx: Context?): Boolean {
        if (ctx == null) return true
        return try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED_KEY, true) } catch (e: Exception) { true }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED_KEY, on).apply() } catch (e: Exception) {}
    }

    /** Token ADA (nilai tidak pernah dikembalikan ke pemanggil UI). */
    fun hasToken(ctx: Context?): Boolean {
        if (ctx == null) return false
        return try { !SecureStore.get(ctx, TOKEN_KEY).isNullOrBlank() } catch (e: Exception) { false }
    }

    /** Hasil "Uji koneksi" terakhir (teks status saja, tanpa token). */
    fun lastCheck(ctx: Context?): String {
        if (ctx == null) return "belum dicek"
        return try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_CHECK_KEY, "belum dicek") ?: "belum dicek" } catch (e: Exception) { "belum dicek" }
    }

    fun setLastCheck(ctx: Context, text: String) {
        try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST_CHECK_KEY, text.take(300)).apply() } catch (e: Exception) {}
    }

    /** String versi dari "Uji koneksi" terakhir yang BERHASIL
     *  (mis. "concat 0.2.10 / apiVersion 0.2"), "" bila belum pernah. */
    fun lastVersion(ctx: Context?): String {
        if (ctx == null) return ""
        return try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_VERSION_KEY, "") ?: "" } catch (e: Exception) { "" }
    }

    fun setLastVersion(ctx: Context, text: String) {
        try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST_VERSION_KEY, text.take(300)).apply() } catch (e: Exception) {}
    }

    /** "concat 0.2.10 / apiVersion 0.2" -> "0.2.10"; "0.2.10" -> "0.2.10";
     *  apa pun yang tidak terbaca (kosong / pesan error) -> fallback
     *  "0.2.10" (jujur: belum pernah terhubung). */
    fun shortVersion(full: String): String {
        val t = full.trim()
        val core = if (t.startsWith("concat ")) t.removePrefix("concat ").trim() else t
        val first = core.split(' ', '/', '\t').firstOrNull { it.isNotBlank() } ?: return FALLBACK_VERSION
        return if (first.matches(Regex("\\d+(\\.\\d+)*"))) first else FALLBACK_VERSION
    }

    /** Subtitle kartu Setup Concat — versi LIVE bila pernah terhubung,
     *  fallback "0.2.10" bila belum. TIDAK PERNAH hardcode versi lama. */
    fun cardSubtitle(ctx: Context?): String =
        "Editor video " + shortVersion(lastVersion(ctx)) + " · server hanya di HP ini"

    private fun optIntOrNull(args: JSONObject, key: String): Int? =
        if (args.has(key) && !args.isNull(key)) args.optInt(key) else null

    private fun optLongOrNull(args: JSONObject, key: String): Long? =
        if (args.has(key) && !args.isNull(key)) args.optLong(key) else null

    /** Cek rekursif satu level (OPS-TABLE.md): tolak `matchClip` di dalam
     *  batch — op itu tidak menyeberang API. */
    private fun batchHasMatchClip(opArgs: JSONObject): Boolean {
        val cmds = opArgs.optJSONArray("commands") ?: return false
        for (i in 0 until cmds.length()) {
            val cmd = cmds.optJSONObject(i) ?: continue
            if (cmd.optString("op") == "matchClip") return true
        }
        return false
    }

    fun run(ctx: Context?, name: String, args: JSONObject): String {
        if (!isEnabled(ctx)) return "Perintah Concat lagi mati — nyalakan dulu di Setup → Concat."
        val token = try { if (ctx == null) null else SecureStore.get(ctx, TOKEN_KEY) } catch (e: Exception) { null }
        if (token.isNullOrBlank()) return NO_TOKEN_MESSAGE
        return try {
            when (name) {
                "concat_version" -> ConcatApi.version(token)
                "concat_project_list" -> {
                    val rows = ConcatApi.listProjects(token)
                    if (rows.isEmpty()) "Belum ada proyek Concat."
                    else rows.joinToString("\n") { "- " + it.name + " (" + it.path + ")" }
                }
                "concat_project_get" -> {
                    val p = args.optString("project").trim()
                    if (p.isBlank()) "concat_project_get butuh argumen 'project' (nama atau path proyek)."
                    else ConcatApi.getProject(token, p)
                }
                "concat_project_open" -> {
                    val p = args.optString("project").trim()
                    if (p.isBlank()) "concat_project_open butuh argumen 'project' (nama atau path proyek)."
                    else ConcatApi.openProject(token, p)
                }
                // ---- vc315: 17 tool baru (METHODS-TABLE.md) ----
                "concat_project_create" -> {
                    val n = args.optString("name").trim()
                    if (n.isBlank()) "concat_project_create butuh argumen 'name' (nama proyek baru)."
                    else ConcatApi.createProject(
                        token, n,
                        args.optString("location").trim().takeIf { it.isNotEmpty() },
                        optIntOrNull(args, "width"), optIntOrNull(args, "height"),
                        optLongOrNull(args, "rateNum"), optLongOrNull(args, "rateDen")
                    )
                }
                "concat_project_close" -> {
                    val p = args.optString("project").trim()
                    if (p.isBlank()) "concat_project_close butuh argumen 'project' (nama atau path proyek)."
                    else ConcatApi.closeProject(token, p, args.optBoolean("save", false))
                }
                "concat_project_save" -> {
                    val p = args.optString("project").trim()
                    if (p.isBlank()) "concat_project_save butuh argumen 'project' (nama atau path proyek)."
                    else ConcatApi.saveProject(token, p, args.optString("name").trim().takeIf { it.isNotEmpty() })
                }
                "concat_project_set_video" -> {
                    val p = args.optString("project").trim()
                    if (p.isBlank()) "concat_project_set_video butuh argumen 'project' (nama atau path proyek)."
                    else {
                        val w = optIntOrNull(args, "width")
                        val h = optIntOrNull(args, "height")
                        if (w == null || w <= 0) "concat_project_set_video butuh argumen 'width' (lebar frame, angka lebih dari 0)."
                        else if (h == null || h <= 0) "concat_project_set_video butuh argumen 'height' (tinggi frame, angka lebih dari 0)."
                        else {
                            val rn = optLongOrNull(args, "rateNum")
                            val rd = optLongOrNull(args, "rateDen")
                            if ((rn == null) != (rd == null)) "concat_project_set_video: kalau isi 'rateNum', isi juga 'rateDen' (dan sebaliknya) — kecepatan frame butuh sepasang angka."
                            else ConcatApi.setVideo(token, p, w, h, rn, rd)
                        }
                    }
                }
                "concat_edit_apply" -> {
                    val p = args.optString("project").trim()
                    if (p.isBlank()) "concat_edit_apply butuh argumen 'project' (nama atau path proyek)."
                    else {
                        val op = args.optString("op").trim()
                        if (op.isBlank()) "concat_edit_apply butuh argumen 'op' (nama perintah edit, mis. addClip)."
                        else if (op !in EDIT_OPS) "concat_edit_apply: op '$op' tidak dikenal — pakai salah satu dari 58 op yang dikenal (mis. addClip, trimClip, splitClips). 'matchClip' tidak bisa dipakai lewat API."
                        else if (!args.has("args") || args.isNull("args")) "concat_edit_apply butuh argumen 'args' (objek JSON berisi field untuk op '$op')."
                        else {
                            val opArgs = args.optJSONObject("args")
                            if (opArgs == null) "concat_edit_apply: argumen 'args' harus berupa objek JSON (bukan teks atau angka)."
                            else if (op == "batch" && batchHasMatchClip(opArgs)) "concat_edit_apply: op 'matchClip' di dalam batch tidak bisa dipakai lewat API."
                            else ConcatApi.applyEdit(token, p, op, opArgs)
                        }
                    }
                }
                "concat_edit_undo" -> {
                    val p = args.optString("project").trim()
                    if (p.isBlank()) "concat_edit_undo butuh argumen 'project' (nama atau path proyek)."
                    else ConcatApi.undoEdit(token, p)
                }
                "concat_edit_redo" -> {
                    val p = args.optString("project").trim()
                    if (p.isBlank()) "concat_edit_redo butuh argumen 'project' (nama atau path proyek)."
                    else ConcatApi.redoEdit(token, p)
                }
                "concat_media_probe" -> {
                    val f = args.optString("file").trim()
                    if (f.isBlank()) "concat_media_probe butuh argumen 'file' (path file di HP, mis. /sdcard/Download/klip.mp4)."
                    else ConcatApi.probeMedia(token, f)
                }
                "concat_media_import" -> {
                    val p = args.optString("project").trim()
                    if (p.isBlank()) "concat_media_import butuh argumen 'project' (nama atau path proyek)."
                    else {
                        val f = args.optString("file").trim()
                        if (f.isBlank()) "concat_media_import butuh argumen 'file' (path file di HP, mis. /sdcard/Download/klip.mp4)."
                        else ConcatApi.importMedia(token, p, f)
                    }
                }
                "concat_catalogue_list" -> {
                    val kind = args.optString("kind").trim()
                    if (kind.isNotEmpty() && kind !in CATALOGUE_KINDS) "concat_catalogue_list: 'kind' hanya boleh effect, filter, audio, transition, atau generator — '$kind' tidak dikenal."
                    else ConcatApi.listCatalogue(token, kind.takeIf { it.isNotEmpty() })
                }
                "concat_template_list" -> ConcatApi.listTemplates(token)
                "concat_template_instantiate" -> {
                    val t = args.optString("template").trim()
                    if (t.isBlank()) "concat_template_instantiate butuh argumen 'template' (folder template)."
                    else {
                        val loc = args.optString("location").trim()
                        if (loc.isBlank()) "concat_template_instantiate butuh argumen 'location' (folder tempat proyek baru dibuat)."
                        else {
                            val n = args.optString("name").trim()
                            if (n.isBlank()) "concat_template_instantiate butuh argumen 'name' (nama proyek baru)."
                            else {
                                val fills = args.optJSONArray("fills")
                                if (fills == null) "concat_template_instantiate butuh argumen 'fills' (daftar slot: array {mediaId, file} — semua slot wajib terisi)."
                                else ConcatApi.instantiateTemplate(token, t, loc, n, fills)
                            }
                        }
                    }
                }
                "concat_template_save" -> {
                    val p = args.optString("project").trim()
                    if (p.isBlank()) "concat_template_save butuh argumen 'project' (nama atau path proyek)."
                    else {
                        val n = args.optString("name").trim()
                        if (n.isBlank()) "concat_template_save butuh argumen 'name' (nama template baru)."
                        else ConcatApi.saveTemplate(token, p, n)
                    }
                }
                "concat_export_run" -> {
                    val p = args.optString("project").trim()
                    if (p.isBlank()) "concat_export_run butuh argumen 'project' (nama atau path proyek)."
                    else {
                        val out = args.optString("output").trim()
                        if (out.isBlank()) "concat_export_run butuh argumen 'output' (path file hasil, mis. /sdcard/Download/hasil.mp4)."
                        else ConcatApi.runExport(
                            token, p, out,
                            optIntOrNull(args, "crf"),
                            args.optString("preset").trim().takeIf { it.isNotEmpty() },
                            optIntOrNull(args, "width"),
                            optIntOrNull(args, "height"),
                            optLongOrNull(args, "rateNum"),
                            optLongOrNull(args, "rateDen"),
                            args.optString("codec").trim().takeIf { it.isNotEmpty() },
                            if (args.has("tenBit") && !args.isNull("tenBit")) args.optBoolean("tenBit") else null,
                            args.optString("colorRange").trim().takeIf { it.isNotEmpty() },
                            if (args.has("hdr") && !args.isNull("hdr")) args.optBoolean("hdr") else null
                        )
                    }
                }
                "concat_export_cancel" -> {
                    val j = args.optString("job").trim()
                    if (j.isBlank()) "concat_export_cancel butuh argumen 'job' (nama job dari concat_export_run, mis. j1)."
                    else ConcatApi.cancelExport(token, j)
                }
                "concat_export_status" -> {
                    val j = args.optString("job").trim()
                    if (j.isBlank()) "concat_export_status butuh argumen 'job' (nama job dari concat_export_run, mis. j1)."
                    else ConcatApi.exportStatus(token, j)
                }
                "concat_preview_frame" -> {
                    val p = args.optString("project").trim()
                    if (p.isBlank()) "concat_preview_frame butuh argumen 'project' (nama atau path proyek)."
                    else if (!args.has("time") || args.isNull("time")) "concat_preview_frame butuh argumen 'time' (detik ke berapa, mis. 12.5)."
                    else ConcatApi.previewFrame(
                        token, p, args.optDouble("time"),
                        args.optString("output").trim().takeIf { it.isNotEmpty() },
                        optIntOrNull(args, "width"), optIntOrNull(args, "height")
                    )
                }
                else -> "Tool Concat tidak dikenal: " + name
            }
        } catch (e: ConcatApiException) {
            e.message ?: "Concat gagal tanpa pesan."
        } catch (e: Exception) {
            "Concat gagal: " + (e.message ?: "kesalahan tidak dikenal")
        }
    }
}
