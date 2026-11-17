// ConcatApiClient.kt — klien JSON-RPC Concat (Worker A, vc315: vc314 + 17 method baru)
// vc319 slice 5: API publik Rust-first (harness.core.RustCore.concatApiJson)
// + fallback ke implementasi Kotlin lama (private *Kotlin) bila Rust tak bisa.
// Perilaku publik TIDAK berubah: signature, pesan error, dan ringkasan sama.
//
// Satu-satunya file source baru Worker A. Pure-Kotlin: TANPA import android.*
// (bisa diuji JVM). Token diterima sebagai parameter String, bukan dibaca dari
// Context/SecureStore — itu urusan Worker B (SecureStore key "concat_api_token").
//
// Protokol (dari source fork v0.2.10 — lihat work-A/CONCAT-API-MAP.md):
// JSON-RPC 2.0 per baris via TCP 127.0.0.1:7420; baris pertama WAJIB auth
// {"jsonrpc":"2.0","id":1,"method":"auth","params":{"token":"..."}}; auth sukses
// dibalas {"id":1,"result":{}}; auth gagal dibalas error -32006 "wrong token"
// lalu server menutup koneksi. Tiap panggilan = satu koneksi (connect -> auth
// -> call -> close). Balasan dicocokkan lewat "id"; baris notifikasi (tanpa
// "id", mis. event export.progress) dilewati. Baris > 4 MiB ditolak.
//
// SEAM TES: `connectionOpener` (internal) — default = TCP langsung ke
// 127.0.0.1:7420. Tes JVM menyetelnya ke transport dalam-memori (antrean
// byte, tanpa TCP sama sekali), karena sandbox VM ini melarang TCP langsung
// dari JVM (pesan guard: "Other TCP connections is turned off", other_tcp =
// Deny). Seam ini BUKAN bagian kontrak publik; Tool/Worker B tidak boleh
// menyentuhnya. Jalur TCP produksinya sendiri tidak berubah dan hanya bisa
// dibuktikan di perangkat (T-API-06/07) atau sesudah izin other_tcp dibuka.
//
// TOKEN TIDAK PERNAH muncul di log / pesan exception / toString / hasil.
// File ini tidak me-log apa pun; pesan exception hanya berisi pesan server
// verbatim, nama metode, nama/path proyek — tidak pernah token.
package com.senzwh.deepharness

import harness.core.RustCore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64

/** Gagal memanggil API Concat (pesan = pesan server verbatim, tanpa token). */
open class ConcatApiException(message: String) : Exception(message)

/** Auth ditolak server (token salah / throttling). Pesan = pesan server verbatim. */
class ConcatAuthException(message: String) : ConcatApiException(message)

/** Server tidak menjawab di 127.0.0.1:7420 (mati / belum nyala / koneksi ditolak). */
class ConcatDownException(message: String) : ConcatApiException(message)

/** Satu entri dari `project.list`: nama tampil + folder proyek. */
data class ConcatProjectRef(val name: String, val path: String)

/**
 * Klien JSON-RPC Concat. Semua fungsi membuka satu koneksi per panggilan.
 * `nameOrPath` pada open/get: path (mengandung "/") dipakai langsung; nama
 * diselesaikan lewat `project.list` (cocok persis, ambil entri pertama).
 */
object ConcatApi {

    const val HOST: String = "127.0.0.1"
    const val PORT: Int = 7420

    /** Batas connect (json.rs AUTH_TIMEOUT ikut 5 dtk; connect pakai nilai yang sama). */
    const val CONNECT_TIMEOUT_MS: Int = 5_000

    /** Batas baca satu panggilan. */
    const val READ_TIMEOUT_MS: Int = 15_000

    /** Batas satu baris balasan (json.rs MAX_LINE). */
    const val MAX_LINE_BYTES: Int = 4 * 1024 * 1024

    /** Cap ringkasan proyek (get/open) — jangan dump dokumen mentah ke LLM. */
    const val SUMMARY_CAP_CHARS: Int = 2000

    /** Pesan baku saat server tidak menjawab (spek §4.2 — gagal jujur). */
    const val DOWN_MESSAGE: String =
        "server Concat tidak menjawab di 127.0.0.1:7420 — server mati atau token salah"

    /** Hint S6 untuk open yang gagal karena lock (proyek masih terbuka di layar). */
    const val LOCK_HINT: String = "tutup dulu proyek di layar Concat, lalu coba lagi"

    // ---- API publik (kontrak FINAL — jangan ubah signature) ----
    // vc319 slice 5: tiap fungsi publik di bawah ini Rust-first —
    // satu panggilan stateless ke Rust (`RustCore.concatApiJson`) yang
    // mengerjakan SELURUH op (validasi + transport + ringkasan).
    // Bila Rust tak bisa (`.so` absen / versi tak cocok / panic /
    // jawaban bentuk tak dikenal), saklar dimatikan dan implementasi
    // Kotlin lama (`*Kotlin`) yang jalan. Error protokol ASLI dari
    // server (auth/down/api) DITERUSKAN apa adanya — bukan fallback.

    /** Saklar seam Rust (mirip `rustPowSolver`): false = pakai Kotlin lama. */
    internal var rustConcatApiClient: Boolean = true

    /** Sinyal internal: Rust tak bisa dipakai -> fallback ke `*Kotlin`. */
    private class RustFallback : Exception()

    /**
     * Satu panggilan Rust. Kembalikan `value` respons (`String` untuk
     * 20 op, `JSONArray` untuk listProjects) — atau lempar:
     * [ConcatAuthException]/[ConcatDownException]/[ConcatApiException]
     * untuk jawaban server ASLI, [RustFallback] bila Rust tak bisa.
     * Sekali Rust gagal, saklar `rustConcatApiClient` dimatikan.
     */
    private fun rustCall(token: String, op: String, args: JSONObject): Any? {
        if (!rustConcatApiClient || !RustCore.available) throw RustFallback()
        val request = JSONObject()
            .put("op", op)
            .put("token", token)
            .put("args", args)
            .put("previewDir", previewDir)
        val raw = try {
            RustCore.concatApiJson(request.toString())
        } catch (t: Throwable) {
            null
        } ?: run {
            rustConcatApiClient = false
            throw RustFallback()
        }
        val resp = try {
            JSONObject(raw)
        } catch (e: Exception) {
            rustConcatApiClient = false
            throw RustFallback()
        }
        if (!resp.optBoolean("ok", false)) {
            val kind = resp.optString("kind", "")
            val message = resp.optString("message", "")
            when (kind) {
                "auth" -> throw ConcatAuthException(message)
                "down" -> throw ConcatDownException(message)
                "api" -> throw ConcatApiException(message)
                else -> {
                    // {"ok":false,"error":"rust core error"} (panic/JNI) -> fallback.
                    rustConcatApiClient = false
                    throw RustFallback()
                }
            }
        }
        return resp.opt("value")
    }

    /** Seperti [rustCall], tapi `value` HARUS String — bila bukan, fallback. */
    private fun rustString(token: String, op: String, args: JSONObject): String {
        val value = rustCall(token, op, args)
        return value as? String ?: run {
            rustConcatApiClient = false
            throw RustFallback()
        }
    }

    /** "concat <versi build> / apiVersion <versi kontrak>" — Rust-first, fallback [versionKotlin]. */
    fun version(token: String): String {
        try {
            return rustString(token, "version", JSONObject())
        } catch (e: RustFallback) {
            return versionKotlin(token)
        }
    }

    /** Daftar proyek (recents, terbaru dulu) — Rust-first, fallback [listProjectsKotlin]. */
    fun listProjects(token: String): List<ConcatProjectRef> {
        try {
            val value = rustCall(token, "listProjects", JSONObject())
            val arr = value as? JSONArray ?: run {
                rustConcatApiClient = false
                throw RustFallback()
            }
            val refs = ArrayList<ConcatProjectRef>(arr.length())
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val name = entry.optString("name", "")
                val path = entry.optString("path", "")
                if (name.isEmpty() || path.isEmpty()) continue
                refs.add(ConcatProjectRef(name, path))
            }
            return refs
        } catch (e: RustFallback) {
            return listProjectsKotlin(token)
        }
    }

    /** Buka proyek, kembalikan RINGKASAN view — Rust-first, fallback [openProjectKotlin]. */
    fun openProject(token: String, nameOrPath: String): String {
        try {
            return rustString(token, "openProject", JSONObject().put("nameOrPath", nameOrPath))
        } catch (e: RustFallback) {
            return openProjectKotlin(token, nameOrPath)
        }
    }

    /** Baca status/timeline, kembalikan RINGKASAN — Rust-first, fallback [getProjectKotlin]. */
    fun getProject(token: String, nameOrPath: String): String {
        try {
            return rustString(token, "getProject", JSONObject().put("nameOrPath", nameOrPath))
        } catch (e: RustFallback) {
            return getProjectKotlin(token, nameOrPath)
        }
    }

    /** Buat proyek baru — Rust-first, fallback [createProjectKotlin]. */
    fun createProject(
        token: String,
        name: String,
        location: String? = null,
        width: Int? = null,
        height: Int? = null,
        rateNum: Long? = null,
        rateDen: Long? = null
    ): String {
        try {
            val args = JSONObject().put("name", name).put("location", location)
                .put("width", width).put("height", height)
                .put("rateNum", rateNum).put("rateDen", rateDen)
            return rustString(token, "createProject", args)
        } catch (e: RustFallback) {
            return createProjectKotlin(token, name, location, width, height, rateNum, rateDen)
        }
    }

    /** Tutup proyek — Rust-first, fallback [closeProjectKotlin]. */
    fun closeProject(token: String, nameOrPath: String, save: Boolean = false): String {
        try {
            val args = JSONObject().put("nameOrPath", nameOrPath).put("save", save)
            return rustString(token, "closeProject", args)
        } catch (e: RustFallback) {
            return closeProjectKotlin(token, nameOrPath, save)
        }
    }

    /** Simpan proyek — Rust-first, fallback [saveProjectKotlin]. */
    fun saveProject(token: String, nameOrPath: String, name: String? = null): String {
        try {
            val args = JSONObject().put("nameOrPath", nameOrPath).put("name", name)
            return rustString(token, "saveProject", args)
        } catch (e: RustFallback) {
            return saveProjectKotlin(token, nameOrPath, name)
        }
    }

    /** Atur frame + rate timeline aktif — Rust-first, fallback [setVideoKotlin]. */
    fun setVideo(
        token: String,
        nameOrPath: String,
        width: Int,
        height: Int,
        rateNum: Long? = null,
        rateDen: Long? = null
    ): String {
        try {
            val args = JSONObject().put("nameOrPath", nameOrPath)
                .put("width", width).put("height", height)
                .put("rateNum", rateNum).put("rateDen", rateDen)
            return rustString(token, "setVideo", args)
        } catch (e: RustFallback) {
            return setVideoKotlin(token, nameOrPath, width, height, rateNum, rateDen)
        }
    }

    /** Terapkan satu perintah edit — Rust-first, fallback [applyEditKotlin]. */
    fun applyEdit(token: String, nameOrPath: String, op: String, args: JSONObject): String {
        try {
            val req = JSONObject().put("nameOrPath", nameOrPath).put("op", op).put("args", args)
            return rustString(token, "applyEdit", req)
        } catch (e: RustFallback) {
            return applyEditKotlin(token, nameOrPath, op, args)
        }
    }

    /** Mundur satu edit — Rust-first, fallback [undoEditKotlin]. */
    fun undoEdit(token: String, nameOrPath: String): String {
        try {
            return rustString(token, "undoEdit", JSONObject().put("nameOrPath", nameOrPath))
        } catch (e: RustFallback) {
            return undoEditKotlin(token, nameOrPath)
        }
    }

    /** Maju satu edit — Rust-first, fallback [redoEditKotlin]. */
    fun redoEdit(token: String, nameOrPath: String): String {
        try {
            return rustString(token, "redoEdit", JSONObject().put("nameOrPath", nameOrPath))
        } catch (e: RustFallback) {
            return redoEditKotlin(token, nameOrPath)
        }
    }

    /** Periksa isi file media — Rust-first, fallback [probeMediaKotlin]. */
    fun probeMedia(token: String, file: String): String {
        try {
            return rustString(token, "probeMedia", JSONObject().put("file", file))
        } catch (e: RustFallback) {
            return probeMediaKotlin(token, file)
        }
    }

    /** Impor file ke bin proyek — Rust-first, fallback [importMediaKotlin]. */
    fun importMedia(token: String, nameOrPath: String, file: String): String {
        try {
            val args = JSONObject().put("nameOrPath", nameOrPath).put("file", file)
            return rustString(token, "importMedia", args)
        } catch (e: RustFallback) {
            return importMediaKotlin(token, nameOrPath, file)
        }
    }

    /** Daftar paket katalog — Rust-first, fallback [listCatalogueKotlin]. */
    fun listCatalogue(token: String, kind: String? = null): String {
        try {
            return rustString(token, "listCatalogue", JSONObject().put("kind", kind))
        } catch (e: RustFallback) {
            return listCatalogueKotlin(token, kind)
        }
    }

    /** Daftar template — Rust-first, fallback [listTemplatesKotlin]. */
    fun listTemplates(token: String): String {
        try {
            return rustString(token, "listTemplates", JSONObject())
        } catch (e: RustFallback) {
            return listTemplatesKotlin(token)
        }
    }

    /** Buat proyek dari template — Rust-first, fallback [instantiateTemplateKotlin]. */
    fun instantiateTemplate(
        token: String,
        template: String,
        location: String,
        name: String,
        fills: JSONArray
    ): String {
        try {
            val args = JSONObject().put("template", template).put("location", location)
                .put("name", name).put("fills", fills)
            return rustString(token, "instantiateTemplate", args)
        } catch (e: RustFallback) {
            return instantiateTemplateKotlin(token, template, location, name, fills)
        }
    }

    /** Simpan proyek terbuka jadi template — Rust-first, fallback [saveTemplateKotlin]. */
    fun saveTemplate(token: String, nameOrPath: String, name: String): String {
        try {
            val args = JSONObject().put("nameOrPath", nameOrPath).put("name", name)
            return rustString(token, "saveTemplate", args)
        } catch (e: RustFallback) {
            return saveTemplateKotlin(token, nameOrPath, name)
        }
    }

    /** Jalankan ekspor (kembali langsung, tanpa polling) — Rust-first, fallback [runExportKotlin]. */
    fun runExport(
        token: String,
        nameOrPath: String,
        output: String,
        crf: Int? = null,
        preset: String? = null,
        width: Int? = null,
        height: Int? = null,
        rateNum: Long? = null,
        rateDen: Long? = null,
        codec: String? = null,
        tenBit: Boolean? = null,
        colorRange: String? = null,
        hdr: Boolean? = null
    ): String {
        try {
            val args = JSONObject().put("nameOrPath", nameOrPath).put("output", output)
                .put("crf", crf).put("preset", preset)
                .put("width", width).put("height", height)
                .put("rateNum", rateNum).put("rateDen", rateDen)
                .put("codec", codec).put("tenBit", tenBit)
                .put("colorRange", colorRange).put("hdr", hdr)
            return rustString(token, "runExport", args)
        } catch (e: RustFallback) {
            return runExportKotlin(token, nameOrPath, output, crf, preset, width, height, rateNum, rateDen, codec, tenBit, colorRange, hdr)
        }
    }

    /** Batalkan ekspor yang jalan — Rust-first, fallback [cancelExportKotlin]. */
    fun cancelExport(token: String, job: String): String {
        try {
            return rustString(token, "cancelExport", JSONObject().put("job", job))
        } catch (e: RustFallback) {
            return cancelExportKotlin(token, job)
        }
    }

    /** Cek status job ekspor — Rust-first, fallback [exportStatusKotlin]. */
    fun exportStatus(token: String, job: String): String {
        try {
            return rustString(token, "exportStatus", JSONObject().put("job", job))
        } catch (e: RustFallback) {
            return exportStatusKotlin(token, job)
        }
    }

    /** Ambil satu frame pratinjau — Rust-first, fallback [previewFrameKotlin]. */
    fun previewFrame(
        token: String,
        nameOrPath: String,
        time: Double,
        output: String? = null,
        width: Int? = null,
        height: Int? = null
    ): String {
        try {
            val args = JSONObject().put("nameOrPath", nameOrPath).put("time", time)
                .put("output", output).put("width", width).put("height", height)
            return rustString(token, "previewFrame", args)
        } catch (e: RustFallback) {
            return previewFrameKotlin(token, nameOrPath, time, output, width, height)
        }
    }

    // ---- Implementasi Kotlin lama (fallback; vc315, TIDAK diubah isinya) ----

    /** "concat <versi build> / apiVersion <versi kontrak>", mis. "concat 0.2.10 / apiVersion 0.2". */
    private fun versionKotlin(token: String): String {
        val result = call(token, "version", null) as? JSONObject
            ?: throw ConcatApiException("Jawaban Concat untuk pengecekan versi tidak bisa dibaca.")
        val concat = result.optString("concat", "")
        val apiVersion = result.optString("apiVersion", "")
        if (concat.isEmpty() || apiVersion.isEmpty()) {
            throw ConcatApiException("Concat tidak menyebut versinya — coba lagi.")
        }
        return "concat $concat / apiVersion $apiVersion"
    }

    /** Daftar proyek (recents, terbaru dulu) dari `project.list`. */
    private fun listProjectsKotlin(token: String): List<ConcatProjectRef> {
        val result = call(token, "project.list", null)
        if (result !is JSONArray) {
            throw ConcatApiException("Daftar proyek dari Concat tidak bisa dibaca.")
        }
        val refs = ArrayList<ConcatProjectRef>(result.length())
        for (i in 0 until result.length()) {
            val entry = result.optJSONObject(i) ?: continue
            val name = entry.optString("name", "")
            val path = entry.optString("path", "")
            if (name.isEmpty() || path.isEmpty()) continue
            refs.add(ConcatProjectRef(name, path))
        }
        return refs
    }

    /**
     * Buka proyek via `project.open` (argumen server = `path` saja).
     * Nama diselesaikan lewat `project.list`; mengembalikan RINGKASAN view
     * (bukan dokumen mentah). Gagal karena lock -> pesan server + hint S6.
     */
    private fun openProjectKotlin(token: String, nameOrPath: String): String {
        val path = resolvePath(token, nameOrPath)
        val result = call(token, "project.open", JSONObject().put("path", path))
        val view = result as? JSONObject
            ?: throw ConcatApiException("Jawaban Concat untuk membuka proyek tidak bisa dibaca.")
        return summarizeView(view, path, opened = true)
    }

    /**
     * Baca status/timeline via `project.get` (proyek harus sudah dibuka).
     * Mengembalikan RINGKASAN (judul, jumlah klip, daftar klip nama+durasi,
     * track, durasi total), cap ~2000 karakter — BUKAN dokumen mentah.
     */
    private fun getProjectKotlin(token: String, nameOrPath: String): String {
        val path = resolvePath(token, nameOrPath)
        val view = fetchView(token, path)
        return summarizeView(view, path, opened = false)
    }

    /** Baca `project.get` mentah (dipakai [getProject] dan [setVideo] untuk rate aktif). */
    private fun fetchView(token: String, path: String): JSONObject {
        val result = call(token, "project.get", JSONObject().put("path", path))
        return result as? JSONObject
            ?: throw ConcatApiException("Jawaban Concat untuk membaca proyek tidak bisa dibaca.")
    }

    // ---- API baru vc315 (17 method; kontrak METHODS-TABLE.md) ----

    /**
     * Buat proyek baru via `project.create`.
     * `location` kosong -> pakai folder induk proyek pertama dari `project.list`;
     * bila list kosong, tolak jujur minta location. Video: bila semua kosong,
     * field `video` dihilangkan (server pakai 1080p@30); bila sebagian diisi,
     * sisanya diisi default 1920x1080 @ 30/1.
     */
    private fun createProjectKotlin(
        token: String,
        name: String,
        location: String? = null,
        width: Int? = null,
        height: Int? = null,
        rateNum: Long? = null,
        rateDen: Long? = null
    ): String {
        val loc = if (location.isNullOrBlank()) {
            val refs = listProjectsKotlin(token)
            val first = refs.firstOrNull()
                ?: throw ConcatApiException(
                    "Belum ada proyek sama sekali di Concat — sebutkan folder tempat proyek baru dibuat (location)."
                )
            parentFolder(first.path)
                ?: throw ConcatApiException(
                    "Tidak bisa menebak folder induk dari \"${first.path}\" — sebutkan folder tempat proyek baru dibuat (location)."
                )
        } else {
            location
        }
        val params = JSONObject().put("location", loc).put("name", name)
        if (width != null || height != null || rateNum != null || rateDen != null) {
            params.put(
                "video", JSONObject()
                    .put("width", width ?: 1920)
                    .put("height", height ?: 1080)
                    .put("rateNum", rateNum ?: 30L)
                    .put("rateDen", rateDen ?: 1L)
            )
        }
        val result = call(token, "project.create", params)
        val view = result as? JSONObject
            ?: throw ConcatApiException("Jawaban Concat untuk membuat proyek tidak bisa dibaca.")
        return summarizeView(view, loc.trimEnd('/') + "/" + name, opened = true)
    }

    /**
     * Tutup proyek via `project.close`. `save = false` = edit yang belum
     * disimpan DIBUANG (perilaku server). Balasan Done -> pesan konfirmasi.
     */
    private fun closeProjectKotlin(token: String, nameOrPath: String, save: Boolean = false): String {
        val path = resolvePath(token, nameOrPath)
        val result = call(token, "project.close", JSONObject().put("path", path).put("save", save))
        requireDone(result, "menutup proyek")
        return if (save) {
            "Proyek ditutup dan disimpan: $path"
        } else {
            "Proyek ditutup (perubahan yang belum disimpan dibuang): $path"
        }
    }

    /** Simpan proyek via `project.save`; `name` diisi = ganti nama proyek. */
    private fun saveProjectKotlin(token: String, nameOrPath: String, name: String? = null): String {
        val path = resolvePath(token, nameOrPath)
        val params = JSONObject().put("path", path)
        if (!name.isNullOrBlank()) params.put("name", name)
        val result = call(token, "project.save", params)
        requireDone(result, "menyimpan proyek")
        return if (!name.isNullOrBlank()) {
            "Proyek disimpan dengan nama baru \"$name\": $path"
        } else {
            "Proyek disimpan: $path"
        }
    }

    /**
     * Atur frame + rate timeline aktif via `project.setVideo`.
     * `rateNum`/`rateDen`: isi dua-duanya atau kosongkan dua-duanya; bila
     * kosong, diambil dari rate proyek yang sedang aktif (`project.get`).
     */
    private fun setVideoKotlin(
        token: String,
        nameOrPath: String,
        width: Int,
        height: Int,
        rateNum: Long? = null,
        rateDen: Long? = null
    ): String {
        if ((rateNum == null) != (rateDen == null)) {
            throw ConcatApiException(
                "Isi rateNum dan rateDen dua-duanya, atau kosongkan dua-duanya (nanti diambil dari proyek yang sedang aktif)."
            )
        }
        val path = resolvePath(token, nameOrPath)
        val (num, den) = if (rateNum != null && rateDen != null) {
            rateNum to rateDen
        } else {
            val view = fetchView(token, path)
            val settings = view.optJSONObject("settings")
            val n = settings?.optLong("rateNum", 0) ?: 0
            val d = settings?.optLong("rateDen", 0) ?: 0
            if (n > 0 && d > 0) n to d else 30L to 1L
        }
        val params = JSONObject().put("path", path).put(
            "video", JSONObject()
                .put("width", width).put("height", height)
                .put("rateNum", num).put("rateDen", den)
        )
        val result = call(token, "project.setVideo", params)
        val view = result as? JSONObject
            ?: throw ConcatApiException("Jawaban Concat untuk mengatur frame proyek tidak bisa dibaca.")
        return summarizeView(view, path, opened = false)
    }

    /**
     * Terapkan satu perintah edit via `edit.apply`.
     * `op` = nama wire camelCase, HARUS ada di allowlist 58 ([EDIT_OP_REQUIRED]);
     * `args` = objek JSON field perintah itu (tanpa `"op"` — klien yang
     * menyisipkannya). Validasi LOKAL sebelum kirim: op tak dikenal dan
     * `matchClip` ditolak; field wajib yang hilang ditolak dengan menyebut
     * fieldnya; `batch.commands` dipindai rekursif — `matchClip` di mana pun
     * di dalamnya ditolak. Objek bersarang (style/patch/curve/moves/item)
     * pass-through: server yang memvalidasi isinya.
     */
    private fun applyEditKotlin(token: String, nameOrPath: String, op: String, args: JSONObject): String {
        val required = EDIT_OP_REQUIRED[op]
            ?: throw ConcatApiException(
                if (op == "matchClip") {
                    "Op \"matchClip\" tidak bisa dipakai lewat API — itu perintah khusus jendela Concat, bukan perintah API."
                } else {
                    "Op \"$op\" tidak dikenal — bukan salah satu dari 58 perintah edit Concat."
                }
            )
        for (field in required) {
            if (!args.has(field) || args.opt(field) == JSONObject.NULL) {
                throw ConcatApiException("Perintah \"$op\" butuh field \"$field\" — isi dulu baru kirim.")
            }
        }
        if (op == "batch") {
            val commands = args.optJSONArray("commands")
                ?: throw ConcatApiException("Perintah \"batch\" butuh field \"commands\" — isi dulu baru kirim.")
            rejectMatchClipDeep(commands)
        }
        val command = JSONObject(args.toString())
        command.put("op", op)
        val path = resolvePath(token, nameOrPath)
        val params = JSONObject().put("path", path).put("command", command)
        val result = call(token, "edit.apply", params)
        val view = result as? JSONObject
            ?: throw ConcatApiException("Jawaban Concat untuk menerapkan edit tidak bisa dibaca.")
        return summarizeView(view, path, opened = false)
    }

    /** Mundur satu edit via `edit.undo`. */
    private fun undoEditKotlin(token: String, nameOrPath: String): String {
        val path = resolvePath(token, nameOrPath)
        val result = call(token, "edit.undo", JSONObject().put("path", path))
        val view = result as? JSONObject
            ?: throw ConcatApiException("Jawaban Concat untuk mengurungkan edit tidak bisa dibaca.")
        return summarizeView(view, path, opened = false)
    }

    /** Maju satu edit via `edit.redo`. */
    private fun redoEditKotlin(token: String, nameOrPath: String): String {
        val path = resolvePath(token, nameOrPath)
        val result = call(token, "edit.redo", JSONObject().put("path", path))
        val view = result as? JSONObject
            ?: throw ConcatApiException("Jawaban Concat untuk mengulangi edit tidak bisa dibaca.")
        return summarizeView(view, path, opened = false)
    }

    /** Periksa isi file media via `media.probe` (tanpa menyentuh proyek). */
    private fun probeMediaKotlin(token: String, file: String): String {
        val result = call(token, "media.probe", JSONObject().put("path", file))
        val media = result as? JSONObject
            ?: throw ConcatApiException("Jawaban Concat untuk memeriksa file tidak bisa dibaca.")
        return summarizeMedia(media)
    }

    /** Impor file ke bin proyek via `media.import` (server yang probe). */
    private fun importMediaKotlin(token: String, nameOrPath: String, file: String): String {
        val path = resolvePath(token, nameOrPath)
        val result = call(token, "media.import", JSONObject().put("path", path).put("file", file))
        val view = result as? JSONObject
            ?: throw ConcatApiException("Jawaban Concat untuk mengimpor media tidak bisa dibaca.")
        return summarizeView(view, path, opened = false)
    }

    /**
     * Daftar paket katalog via `catalogue.list`. `kind` kosong = semua;
     * bila diisi harus salah satu dari effect/filter/audio/transition/generator.
     */
    private fun listCatalogueKotlin(token: String, kind: String? = null): String {
        val cleanKind = kind?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanKind != null && cleanKind !in CATALOGUE_KINDS) {
            throw ConcatApiException(
                "Jenis katalog \"$cleanKind\" tidak dikenal — yang ada: effect, filter, audio, transition, generator."
            )
        }
        val params = if (cleanKind != null) JSONObject().put("kind", cleanKind) else null
        val result = call(token, "catalogue.list", params)
        val arr = result as? JSONArray
            ?: throw ConcatApiException("Daftar katalog dari Concat tidak bisa dibaca.")
        return summarizePackages(arr)
    }

    /** Daftar template via `template.list`. */
    private fun listTemplatesKotlin(token: String): String {
        val result = call(token, "template.list", null)
        val arr = result as? JSONArray
            ?: throw ConcatApiException("Daftar template dari Concat tidak bisa dibaca.")
        return summarizeTemplates(arr)
    }

    /**
     * Buat proyek dari template via `template.instantiate`.
     * `fills` = array {mediaId, file} — SEMUA slot wajib terisi, kalau tidak
     * server menolak tanpa membuat apa pun.
     */
    private fun instantiateTemplateKotlin(
        token: String,
        template: String,
        location: String,
        name: String,
        fills: JSONArray
    ): String {
        for (i in 0 until fills.length()) {
            val fill = fills.optJSONObject(i)
            if (fill == null || !fill.has("mediaId") || !fill.has("file")) {
                throw ConcatApiException(
                    "Isian slot template ke-${i + 1} harus berisi \"mediaId\" dan \"file\"."
                )
            }
        }
        val params = JSONObject()
            .put("template", template).put("location", location)
            .put("name", name).put("fills", fills)
        val result = call(token, "template.instantiate", params)
        val view = result as? JSONObject
            ?: throw ConcatApiException("Jawaban Concat untuk membuat proyek dari template tidak bisa dibaca.")
        return summarizeView(view, location.trimEnd('/') + "/" + name, opened = true)
    }

    /** Simpan proyek terbuka jadi template via `template.save`. */
    private fun saveTemplateKotlin(token: String, nameOrPath: String, name: String): String {
        val path = resolvePath(token, nameOrPath)
        val result = call(token, "template.save", JSONObject().put("path", path).put("name", name))
        val info = result as? JSONObject
            ?: throw ConcatApiException("Jawaban Concat untuk menyimpan template tidak bisa dibaca.")
        return summarizeTemplate(info)
    }

    /**
     * Jalankan ekspor via `export.run`. Kembali LANGSUNG dengan ringkasan
     * Started (job + output) — TIDAK polling, TIDAK blokir; pantau lewat
     * [exportStatus]. Semua opsi render opsional (default server: crf 20,
     * preset medium, codec h264, limited); yang kosong tidak dikirim.
     */
    private fun runExportKotlin(
        token: String,
        nameOrPath: String,
        output: String,
        crf: Int? = null,
        preset: String? = null,
        width: Int? = null,
        height: Int? = null,
        rateNum: Long? = null,
        rateDen: Long? = null,
        codec: String? = null,
        tenBit: Boolean? = null,
        colorRange: String? = null,
        hdr: Boolean? = null
    ): String {
        val path = resolvePath(token, nameOrPath)
        val params = JSONObject().put("path", path).put("output", output)
        if (crf != null) params.put("crf", crf)
        if (preset != null) params.put("preset", preset)
        if (width != null) params.put("width", width)
        if (height != null) params.put("height", height)
        if (rateNum != null) params.put("rateNum", rateNum)
        if (rateDen != null) params.put("rateDen", rateDen)
        if (codec != null) params.put("codec", codec)
        if (tenBit != null) params.put("tenBit", tenBit)
        if (colorRange != null) params.put("colorRange", colorRange)
        if (hdr != null) params.put("hdr", hdr)
        val result = call(token, "export.run", params)
        val started = result as? JSONObject
            ?: throw ConcatApiException("Jawaban Concat untuk menjalankan ekspor tidak bisa dibaca.")
        return summarizeStarted(started)
    }

    /** Batalkan ekspor yang jalan via `export.cancel`. */
    private fun cancelExportKotlin(token: String, job: String): String {
        val result = call(token, "export.cancel", JSONObject().put("job", job))
        requireDone(result, "membatalkan ekspor")
        return "Ekspor job \"$job\" dibatalkan."
    }

    /** Cek status job ekspor via `export.status` (polling — tanpa event stream). */
    private fun exportStatusKotlin(token: String, job: String): String {
        val result = call(token, "export.status", JSONObject().put("job", job))
        val status = result as? JSONObject
            ?: throw ConcatApiException("Jawaban Concat untuk status ekspor tidak bisa dibaca.")
        return summarizeJobStatus(status)
    }

    /**
     * Ambil satu frame via `preview.frame`.
     * `output` diisi -> server yang menulis PNG, kembalikan path itu (Written).
     * `output` kosong -> server kirim Picture (PNG base64); klien decode lalu
     * tulis ke `[previewDir]/concat-preview-<millis>.png` (folder dibuat bila
     * belum ada; pure java.io). Gagal tulis -> error jujur. PNG melebihi cap
     * baris 4 MiB -> error jujur (minta width/height lebih kecil atau isi output).
     */
    private fun previewFrameKotlin(
        token: String,
        nameOrPath: String,
        time: Double,
        output: String? = null,
        width: Int? = null,
        height: Int? = null
    ): String {
        val path = resolvePath(token, nameOrPath)
        val params = JSONObject().put("path", path).put("time", time)
        val out = output?.trim()?.takeIf { it.isNotEmpty() }
        if (out != null) params.put("output", out)
        if (width != null) params.put("width", width)
        if (height != null) params.put("height", height)
        val result = try {
            call(token, "preview.frame", params)
        } catch (e: ConcatApiException) {
            if (e.message?.contains("terlalu besar") == true) {
                throw ConcatApiException(
                    "Gambar frame terlalu besar untuk dibaca — minta width/height lebih kecil atau isi output supaya server yang menulis filenya."
                )
            }
            throw e
        }
        val obj = result as? JSONObject
            ?: throw ConcatApiException("Jawaban Concat untuk frame pratinjau tidak bisa dibaca.")
        if (out != null) {
            val writtenPath = obj.optString("path", out)
            val w = obj.optInt("width", 0)
            val h = obj.optInt("height", 0)
            return if (w > 0 && h > 0) {
                "Frame tersimpan di: $writtenPath (${w}x$h)"
            } else {
                "Frame tersimpan di: $writtenPath"
            }
        }
        val png = obj.optString("png", "")
        if (png.isEmpty()) {
            throw ConcatApiException("Concat tidak mengirim gambar frame — coba lagi.")
        }
        val bytes = try {
            Base64.getDecoder().decode(png)
        } catch (e: IllegalArgumentException) {
            throw ConcatApiException("Gambar frame dari Concat tidak bisa dibaca — coba lagi.")
        }
        val fileName = "concat-preview-${System.currentTimeMillis()}.png"
        val dir = File(previewDir)
        val file = File(dir, fileName)
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("tidak bisa membuat folder $previewDir")
            }
            FileOutputStream(file).use { it.write(bytes) }
        } catch (e: IOException) {
            throw ConcatApiException("Gambar frame tidak bisa ditulis ke ${file.path} — periksa foldernya lalu coba lagi.")
        } catch (e: SecurityException) {
            throw ConcatApiException("Gambar frame tidak bisa ditulis ke ${file.path} — periksa foldernya lalu coba lagi.")
        }
        val w = obj.optInt("width", 0)
        val h = obj.optInt("height", 0)
        return if (w > 0 && h > 0) {
            "Frame tersimpan di: ${file.path} (${w}x$h)"
        } else {
            "Frame tersimpan di: ${file.path}"
        }
    }

    // ---- Allowlist edit.apply (58 op) + validasi lokal ----

    /**
     * Allowlist 58 op `edit.apply` (nama wire camelCase) + field wajib tiap op.
     * Sumber: OPS-TABLE.md / COMMANDS-EXTRACT.txt (fork v0.2.10, enum Command).
     * `matchClip` sengaja TIDAK ada di sini (`#[serde(skip)]` — tidak menyeberang API).
     * Field bertanda [d]/[?] di OPS-TABLE (default/opsional) tidak masuk daftar wajib.
     */
    internal val EDIT_OP_REQUIRED: Map<String, List<String>> = mapOf(
        "addMedia" to listOf("item"),
        "removeMedia" to listOf("mediaId"),
        "setMediaPlaceholder" to listOf("mediaId", "placeholder"),
        "fillSlot" to listOf("mediaId", "item"),
        "batch" to listOf("commands"),
        "addClip" to listOf("mediaId", "trackId", "start"),
        "addClipAtFirstFree" to listOf("mediaId", "start"),
        "addClipsInSequence" to listOf("mediaIds", "start"),
        "addTextClip" to listOf("start"),
        "addShapeClip" to listOf("start", "name"),
        "addLayerClip" to listOf("start", "effectId", "name"),
        "addTransitionClip" to listOf("start", "transitionId", "name"),
        "setClipSpeedCurve" to listOf("clipId"),
        "setClipKey" to listOf("clipId", "property", "at", "value"),
        "clearClipKey" to listOf("clipId", "property", "at"),
        "clearClipKeys" to listOf("clipId", "property"),
        "setEffectKey" to listOf("clipId", "entry", "key", "at", "value"),
        "clearEffectKey" to listOf("clipId", "entry", "key", "at"),
        "clearEffectKeys" to listOf("clipId", "entry", "key"),
        "setMaskKey" to listOf("clipId", "maskId", "number", "at", "value"),
        "clearMaskKey" to listOf("clipId", "maskId", "number", "at"),
        "clearMaskKeys" to listOf("clipId", "maskId", "number"),
        "setClipCutout" to listOf("clipId"),
        "setClipStabilize" to listOf("clipId"),
        "setClipVoice" to listOf("clipId"),
        "addCutoutStroke" to listOf("clipId", "stroke"),
        "moveClips" to listOf("moves"),
        "trimClip" to listOf("clipId", "edge", "delta"),
        "splitClips" to listOf("clipIds", "time"),
        "replaceClipMedia" to listOf("clipId", "item"),
        "setClipEnhanced" to listOf("clipId", "on"),
        "freezeFrame" to listOf("clipId", "time"),
        "mergeClips" to listOf("clipIds"),
        "consolidateClips" to listOf("clipIds", "item"),
        "removeClips" to listOf("clipIds"),
        "updateClip" to listOf("clipId", "patch"),
        "styleTextRange" to listOf("clipId", "start", "end", "patch"),
        "clearTextStyles" to listOf("clipId"),
        "setClipSpeed" to listOf("clipId", "speed"),
        "setClipTransform" to listOf("clipId"),
        "frameClip" to listOf("clipId", "framing"),
        "detachAudio" to listOf("clipId"),
        "reattachAudio" to listOf("clipId"),
        "addTrack" to emptyList(),
        "removeTrack" to listOf("trackId"),
        "setTrackFlag" to listOf("trackId", "flag", "value"),
        "addTimeline" to emptyList(),
        "removeTimeline" to listOf("timelineId"),
        "setTimelineVideo" to listOf("timelineId", "video"),
        "setTimelineBackground" to listOf("timelineId", "background"),
        "setTimelineLinkage" to listOf("timelineId", "on"),
        "renameTimeline" to listOf("timelineId", "name"),
        "selectTimeline" to listOf("timelineId"),
        "moveTimeline" to listOf("timelineId", "index"),
        "addFont" to listOf("family", "path"),
        "removeFont" to listOf("family"),
        "updateMediaPath" to listOf("mediaId", "newPath"),
        "setMediaColorRange" to listOf("mediaId")
    )

    /** Jenis katalog yang dikenal server (`catalogue.list`, message.rs). */
    private val CATALOGUE_KINDS: Set<String> = setOf("effect", "filter", "audio", "transition", "generator")

    /** Pindai rekursif: tolak `matchClip` di mana pun di dalam nilai JSON. */
    private fun rejectMatchClipDeep(value: Any?) {
        when (value) {
            is JSONObject -> {
                if (value.optString("op", "") == "matchClip") {
                    throw ConcatApiException(
                        "Op \"matchClip\" tidak bisa dipakai lewat API — itu perintah khusus jendela Concat, bukan perintah API."
                    )
                }
                val keys = value.keys()
                while (keys.hasNext()) rejectMatchClipDeep(value.opt(keys.next()))
            }
            is JSONArray -> {
                for (i in 0 until value.length()) rejectMatchClipDeep(value.opt(i))
            }
        }
    }

    /** Folder induk sebuah path proyek ("/p/A" -> "/p"); null bila tak ada induk. */
    private fun parentFolder(path: String): String? {
        val clean = path.trimEnd('/')
        if (clean.isEmpty()) return null
        val idx = clean.lastIndexOf('/')
        if (idx <= 0) return null
        return clean.substring(0, idx)
    }

    /** Balasan Done ({}) harus objek JSON; kalau bukan, jawabannya tak bisa dibaca. */
    private fun requireDone(result: Any?, action: String) {
        if (result !is JSONObject) {
            throw ConcatApiException("Jawaban Concat untuk $action tidak bisa dibaca.")
        }
    }

    /** Potong ringkasan ke cap ~2000 karakter (pola SUMMARY_CAP_CHARS). */
    private fun capSummary(text: String): String =
        if (text.length > SUMMARY_CAP_CHARS) text.take(SUMMARY_CAP_CHARS - 1) + "…" else text

    // ---- Penyelesaian nama -> path (klien; server hanya kenal path) ----

    private fun resolvePath(token: String, nameOrPath: String): String {
        if (nameOrPath.contains('/')) return nameOrPath
        val name = nameOrPath.trim()
        val refs = listProjectsKotlin(token)
        val match = refs.firstOrNull { it.name == name }
        if (match != null) return match.path
        val available = if (refs.isEmpty()) {
            "tidak ada proyek yang tersedia"
        } else {
            refs.joinToString(", ") { it.name }
        }
        throw ConcatApiException(
            "Proyek \"$name\" tidak ketemu — " + if (refs.isEmpty()) {
                "belum ada proyek sama sekali di Concat"
            } else {
                "yang ada: $available"
            }
        )
    }

    // ---- Ringkasan EditorView (judul, klip, track, durasi total) ----

    /** Satu baris klip untuk ringkasan (nama + awal + durasi, detik). */
    private class ClipLine(val clipName: String, val start: Double, val duration: Double)

    /**
     * Ringkas satu `EditorView` (result `project.open`/`project.get`).
     * Timeline yang diringkas = timeline AKTIF (`activeTimelineId`, jatuh ke
     * yang pertama); klip diurutkan pakai `start` (wire menyimpan urutan
     * sisipan, bukan urutan waktu). Tidak pernah memuat dokumen mentah.
     */
    internal fun summarizeView(view: JSONObject, path: String, opened: Boolean): String {
        val settings = view.optJSONObject("settings")
        val rawName = settings?.optString("name", "") ?: ""
        val name = if (rawName.isEmpty()) "Tanpa nama" else rawName
        val width = settings?.optInt("width", 0) ?: 0
        val height = settings?.optInt("height", 0) ?: 0
        val rate = formatRate(
            settings?.optLong("rateNum", 0) ?: 0,
            settings?.optLong("rateDen", 0) ?: 0
        )

        val project = view.optJSONObject("project")
        val timelines = project?.optJSONArray("timelines")
        val activeId = project?.optString("activeTimelineId", "") ?: ""
        var timeline: JSONObject? = null
        if (timelines != null) {
            for (i in 0 until timelines.length()) {
                val candidate = timelines.optJSONObject(i) ?: continue
                if (candidate.optString("id", "") == activeId) {
                    timeline = candidate
                    break
                }
            }
            if (timeline == null && timelines.length() > 0) {
                timeline = timelines.optJSONObject(0)
            }
        }
        val rawTimelineName = timeline?.optString("name", "") ?: ""
        val timelineName = if (rawTimelineName.isEmpty()) "Timeline" else rawTimelineName
        val trackCount = timeline?.optJSONArray("tracks")?.length() ?: 0
        val clipsJson = timeline?.optJSONArray("clips")

        val clips = ArrayList<ClipLine>()
        if (clipsJson != null) {
            for (i in 0 until clipsJson.length()) {
                val clip = clipsJson.optJSONObject(i) ?: continue
                val rawClipName = clip.optString("name", "")
                val clipName = (if (rawClipName.isEmpty()) clip.optString("id", "klip") else rawClipName)
                    .replace('\n', ' ').replace('\r', ' ').take(80)
                clips.add(
                    ClipLine(
                        clipName,
                        clip.optDouble("start", 0.0),
                        clip.optDouble("duration", 0.0)
                    )
                )
            }
        }
        clips.sortBy { it.start }
        val totalSeconds = clips.maxOfOrNull { it.start + it.duration } ?: 0.0

        val out = StringBuilder()
        out.append(if (opened) "Dibuka: " else "Proyek: ").append(name).append('\n')
        out.append("Path: ").append(path).append('\n')
        if (width > 0 && height > 0) {
            out.append("Frame: ").append(width).append('x').append(height)
            if (rate.isNotEmpty()) out.append(" @ ").append(rate)
            out.append('\n')
        }
        out.append("Timeline: ").append(timelineName)
            .append(" — ").append(trackCount).append(" track, ")
            .append(clips.size).append(" klip, ")
            .append("total ").append(formatDuration(totalSeconds)).append('\n')
        if (clips.isEmpty()) {
            out.append("Klip: (belum ada klip)")
        } else {
            out.append("Klip:")
            var shown = 0
            for (clip in clips) {
                val line = "\n- ${clip.clipName} (${formatDuration(clip.duration)})"
                // Sisa ruang untuk baris "+N klip lagi" (~40 karakter) bila masih ada klip.
                val reserve = if (shown < clips.size - 1) 48 else 0
                if (out.length + line.length + reserve > SUMMARY_CAP_CHARS) break
                out.append(line)
                shown++
            }
            val remaining = clips.size - shown
            if (remaining > 0) {
                out.append("\n… +").append(remaining).append(" klip lagi (ringkasan dipotong)")
            }
        }
        val text = out.toString()
        val capped = if (text.length > SUMMARY_CAP_CHARS) {
            text.take(SUMMARY_CAP_CHARS - 1) + "…"
        } else {
            text
        }
        // Temuan lapangan vc314: sesudah open via API, layar Concat menolak
        // membuka proyek yang sama (banner "open through the Remote API") —
        // proyek dipegang API sampai ditutup lewat perintah. Catatan ini
        // ditempel SETELAH cap supaya selalu ada di akhir ringkasan open.
        return if (opened) {
            capped + "\nCatatan: proyek ini sekarang dipegang API — layar Concat tidak bisa membukanya sampai ditutup lewat perintah (concat_project_close)."
        } else {
            capped
        }
    }

    private fun formatRate(rateNum: Long, rateDen: Long): String {
        if (rateNum <= 0 || rateDen <= 0) return ""
        val rate = rateNum.toDouble() / rateDen.toDouble()
        val whole = rate.toLong()
        return if (rate == whole.toDouble()) "$whole fps" else "%.2f fps".format(rate)
    }

    /** Detik -> "12.5 dtk" (< 60 dtk) atau "4:28" (>= 60 dtk). */
    private fun formatDuration(seconds: Double): String {
        val safe = if (seconds.isFinite() && seconds > 0) seconds else 0.0
        if (safe < 60.0) {
            val rounded = Math.round(safe * 10) / 10.0
            return if (rounded == Math.floor(rounded)) {
                "${rounded.toLong()} dtk"
            } else {
                "$rounded dtk"
            }
        }
        val totalSeconds = safe.toLong()
        val minutes = totalSeconds / 60
        val rest = totalSeconds % 60
        return "$minutes:%02d".format(rest)
    }

    // ---- Ringkasan balasan baru vc315 (cap ~2000 karakter, tanpa dump mentah) ----

    /** Ringkas satu `MediaSummary` (probe): path, jenis, durasi, video WxH, audio ya/tidak. */
    internal fun summarizeMedia(media: JSONObject): String {
        val path = media.optString("path", "")
        val kind = media.optString("kind", "")
        val out = StringBuilder()
        out.append("Media: ").append(if (path.isEmpty()) "(tanpa path)" else path).append('\n')
        out.append("Jenis: ").append(if (kind.isEmpty()) "tidak diketahui" else kind)
        if (media.has("duration") && !media.isNull("duration")) {
            out.append('\n').append("Durasi: ").append(formatDuration(media.optDouble("duration", 0.0)))
        }
        val video = media.optJSONObject("video")
        if (video != null) {
            val w = video.optInt("width", 0)
            val h = video.optInt("height", 0)
            if (w > 0 && h > 0) out.append('\n').append("Video: ").append(w).append('x').append(h)
        }
        val hasAudio = media.optJSONObject("audio") != null ||
            (media.optJSONArray("audioTracks")?.length() ?: 0) > 0
        out.append('\n').append("Audio: ").append(if (hasAudio) "ya" else "tidak")
        return capSummary(out.toString())
    }

    /** Ringkas `PackageInfo[]` (katalog): id + nama + kind + kategori, cap 30 + "+N lagi". */
    internal fun summarizePackages(packages: JSONArray): String {
        val out = StringBuilder()
        out.append("Katalog: ").append(packages.length()).append(" paket")
        var shown = 0
        var i = 0
        while (i < packages.length()) {
            if (shown >= 30) break
            val pkg = packages.optJSONObject(i)
            if (pkg == null) {
                i++
                continue
            }
            val line = "\n- ${pkg.optString("id", "?")} — ${pkg.optString("name", "?")} " +
                "(${pkg.optString("kind", "?")}, ${pkg.optString("category", "?")})"
            if (out.length + line.length > SUMMARY_CAP_CHARS) break
            out.append(line)
            shown++
            i++
        }
        val remaining = packages.length() - i
        if (remaining > 0) {
            out.append("\n… +").append(remaining).append(" lagi (ringkasan dipotong)")
        }
        return capSummary(out.toString())
    }

    /** Satu baris `TemplateInfo`: nama + ukuran + jumlah slot. */
    private fun summarizeTemplateLine(info: JSONObject): String {
        val name = info.optString("name", "").ifEmpty { "Tanpa nama" }
        val w = info.optInt("width", 0)
        val h = info.optInt("height", 0)
        val rate = formatRate(info.optLong("rateNum", 0), info.optLong("rateDen", 0))
        val slots = info.optJSONArray("slots")?.length() ?: 0
        val size = if (w > 0 && h > 0) {
            "${w}x$h" + (if (rate.isNotEmpty()) " @ $rate" else "")
        } else {
            "ukuran tidak diketahui"
        }
        return "$name — $size, $slots slot"
    }

    /** Ringkas `TemplateInfo[]`: nama + ukuran + jumlah slot. */
    internal fun summarizeTemplates(templates: JSONArray): String {
        val out = StringBuilder()
        out.append("Template: ").append(templates.length()).append(" template")
        var shown = 0
        var i = 0
        while (i < templates.length()) {
            if (shown >= 30) break
            val info = templates.optJSONObject(i)
            if (info == null) {
                i++
                continue
            }
            val line = "\n- " + summarizeTemplateLine(info)
            if (out.length + line.length > SUMMARY_CAP_CHARS) break
            out.append(line)
            shown++
            i++
        }
        val remaining = templates.length() - i
        if (remaining > 0) {
            out.append("\n… +").append(remaining).append(" lagi (ringkasan dipotong)")
        }
        return capSummary(out.toString())
    }

    /** Ringkas satu `TemplateInfo` (template.save): nama + ukuran + slot. */
    internal fun summarizeTemplate(info: JSONObject): String {
        val name = info.optString("name", "").ifEmpty { "Tanpa nama" }
        val w = info.optInt("width", 0)
        val h = info.optInt("height", 0)
        val rate = formatRate(info.optLong("rateNum", 0), info.optLong("rateDen", 0))
        val slots = info.optJSONArray("slots")?.length() ?: 0
        val out = StringBuilder()
        out.append("Template: ").append(name).append('\n')
        if (w > 0 && h > 0) {
            out.append("Ukuran: ").append(w).append('x').append(h)
            if (rate.isNotEmpty()) out.append(" @ ").append(rate)
            out.append('\n')
        }
        out.append("Slot: ").append(slots)
        return capSummary(out.toString())
    }

    /** Ringkas `Started` (export.run): job + output, tanpa polling. */
    internal fun summarizeStarted(started: JSONObject): String {
        val job = started.optString("job", "")
        val output = started.optString("output", "")
        val out = StringBuilder()
        out.append("Ekspor jalan, job ").append(if (job.isEmpty()) "?" else job)
            .append(" — cek status pakai concat_export_status.")
        if (output.isNotEmpty()) out.append("\nOutput: ").append(output)
        return capSummary(out.toString())
    }

    /** Ringkas `JobStatus` (export.status): job, state, stage, frame/total, persen, output, error. */
    internal fun summarizeJobStatus(status: JSONObject): String {
        val job = status.optString("job", "")
        val state = when (status.optString("state", "")) {
            "running" -> "lagi jalan"
            "done" -> "selesai"
            "cancelled" -> "dibatalkan"
            "failed" -> "gagal"
            else -> status.optString("state", "tidak diketahui")
        }
        val out = StringBuilder()
        out.append("Ekspor job ").append(if (job.isEmpty()) "?" else job).append(": ").append(state)
        val stage = status.optString("stage", "")
        if (stage.isNotEmpty()) out.append("\nTahap: ").append(stage)
        val frame = status.optLong("frame", 0)
        val total = status.optLong("total", 0)
        if (total > 0) {
            val pct = Math.round(status.optDouble("fraction", 0.0) * 100)
            out.append("\nFrame: ").append(frame).append('/').append(total)
                .append(" ($pct%)")
        } else if (frame > 0) {
            out.append("\nFrame: ").append(frame)
        }
        val output = status.optString("output", "")
        if (output.isNotEmpty()) out.append("\nOutput: ").append(output)
        val errMsg = status.optJSONObject("error")?.optString("message", "") ?: ""
        if (errMsg.isNotEmpty()) out.append("\nError: ").append(errMsg)
        return capSummary(out.toString())
    }

    // ---- Transport: satu koneksi per panggilan ----

    /** Satu koneksi: alur baca + tulis + tutup. Dibuka lewat [connectionOpener]. */
    internal class Connection(
        val reader: BufferedReader,
        val writer: BufferedWriter,
        private val onClose: () -> Unit
    ) {
        fun close() {
            try {
                onClose()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Pembuka koneksi. Default = TCP langsung ([openTcpConnection]).
     * Tes JVM menyetel ini ke transport dalam-memori (lihat header file).
     * BUKAN bagian kontrak publik — jangan dipakai dari Tool/Worker B.
     */
    internal var connectionOpener: () -> Connection = ::openTcpConnection

    /**
     * Folder tempat `previewFrame` (tanpa output) menulis PNG.
     * Default /sdcard/Pictures/Harness. Seam tes: tes JVM mengarahkannya ke
     * temp dir. Pure java.io — tanpa android.*. BUKAN bagian kontrak publik.
     */
    internal var previewDir: String = "/sdcard/Pictures/Harness"

    /** Koneksi TCP langsung ke 127.0.0.1:7420, tanpa proxy (loopback milik sendiri). */
    private fun openTcpConnection(): Connection {
        val socket = Socket(Proxy.NO_PROXY)
        socket.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
        socket.soTimeout = READ_TIMEOUT_MS
        val writer = BufferedWriter(
            OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
        )
        val reader = BufferedReader(
            InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
        )
        return Connection(reader, writer) { socket.close() }
    }

    /**
     * connect -> auth (id 1) -> call (id 2) -> close.
     * Mengembalikan nilai `"result"` balasan, atau melempar:
     * [ConcatAuthException] bila auth ditolak (pesan server verbatim),
     * [ConcatDownException] bila koneksi ditolak/timeout atau server menutup
     * koneksi sebelum auth dijawab, [ConcatApiException] untuk error lain
     * (pesan server verbatim; open yang gagal karena lock + hint S6).
     */
    private fun call(token: String, method: String, params: JSONObject?): Any? {
        val conn: Connection = try {
            connectionOpener()
        } catch (e: SocketTimeoutException) {
            throw ConcatDownException(DOWN_MESSAGE)
        } catch (e: IOException) {
            throw ConcatDownException(DOWN_MESSAGE)
        }
        try {
            var phase = "auth"
            try {
                val out = conn.writer
                val reader = conn.reader
                // 1. Auth — baris pertama, id 1.
                out.write(authLine(token))
                out.write("\n")
                out.flush()
                val authReply = readMatching(reader, 1, "auth")
                val authError = authReply.optJSONObject("error")
                if (authError != null) throw mapAuthError(authError)
                if (!authReply.has("result")) {
                    throw ConcatApiException("Concat tidak memberi jawaban saat masuk — coba lagi.")
                }
                // 2. Panggilan — id 2.
                phase = method
                out.write(callLine(method, params))
                out.write("\n")
                out.flush()
                val reply = readMatching(reader, 2, method)
                val error = reply.optJSONObject("error")
                if (error != null) throw mapCallError(method, error)
                if (!reply.has("result")) {
                    throw ConcatApiException(
                        "Concat tidak memberi jawaban untuk ${plainAction(method)} — coba lagi."
                    )
                }
                return reply.get("result")
            } catch (e: SocketTimeoutException) {
                throw ConcatApiException(timeoutMessage(phase))
            } catch (e: ConcatApiException) {
                throw e
            } catch (e: IOException) {
                throw ConcatApiException("Concat putus saat ${plainAction(phase)} — coba lagi.")
            }
        } finally {
            conn.close()
        }
    }

    private fun authLine(token: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "auth")
            .put("params", JSONObject().put("token", token))
            .toString()

    private fun callLine(method: String, params: JSONObject?): String {
        val line = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 2)
            .put("method", method)
        if (params != null) line.put("params", params)
        return line.toString()
    }

    /**
     * Baca baris sampai ketemu balasan dengan `"id"` yang cocok.
     * Notifikasi (tanpa `"id"`) dan balasan id lain dilewati — sesuai
     * `client.rs` referensi: `id: null` + `"error"` juga dihitung jawaban.
     * EOF sebelum auth dijawab = server mati ([ConcatDownException]);
     * EOF saat memanggil = koneksi putus ([ConcatApiException]).
     */
    private fun readMatching(reader: BufferedReader, id: Int, what: String): JSONObject {
        while (true) {
            val line = readLineCapped(reader)
                ?: if (what == "auth") {
                    throw ConcatDownException(
                        "Concat putus sebelum sempat masuk — servernya mungkin mati atau baru saja restart, coba lagi."
                    )
                } else {
                    throw ConcatApiException(
                        "Concat putus di tengah jalan saat ${plainAction(what)} — coba lagi."
                    )
                }
            if (line.isBlank()) continue
            val obj = try {
                JSONObject(line)
            } catch (e: Exception) {
                throw ConcatApiException("Jawaban Concat tidak bisa dibaca — coba lagi.")
            }
            if (!obj.has("id")) continue
            val lineId = obj.opt("id")
            if (lineId == JSONObject.NULL) {
                if (obj.has("error")) return obj
                continue
            }
            val number = lineId as? Number ?: continue
            if (number.toLong() != id.toLong()) continue
            return obj
        }
    }

    /**
     * Satu baris dengan cap byte (UTF-8). Baris yang melewati cap ditolak
     * (server sendiri memakai MAX_LINE 4 MiB untuk baris masuk).
     */
    private fun readLineCapped(reader: BufferedReader): String? {
        val sb = StringBuilder()
        var bytes = 0
        while (true) {
            val c = reader.read()
            if (c == -1) {
                if (sb.isEmpty()) return null
                return sb.toString().trimEnd('\r')
            }
            val ch = c.toChar()
            if (ch == '\n') return sb.toString().trimEnd('\r')
            bytes += when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                Character.isHighSurrogate(ch) || Character.isLowSurrogate(ch) -> 2
                else -> 3
            }
            if (bytes > MAX_LINE_BYTES) {
                throw ConcatApiException(
                    "Jawaban Concat terlalu besar untuk dibaca — proyeknya mungkin terlalu besar."
                )
            }
            sb.append(ch)
        }
    }

    private fun mapAuthError(error: JSONObject): ConcatApiException {
        val message = error.optString("message", "Concat menolak masuk — tokennya mungkin salah.")
        val code = error.optInt("code", 0)
        val dataCode = error.optJSONObject("data")?.optString("code", "") ?: ""
        return if (code == -32006 || dataCode == "unauthorized") {
            ConcatAuthException(message)
        } else {
            ConcatApiException(message)
        }
    }

    private fun mapCallError(method: String, error: JSONObject): ConcatApiException {
        val message = error.optString("message", "Concat menolak ${plainAction(method)}.")
        if (method != "project.open") return ConcatApiException(message)
        val code = error.optInt("code", 0)
        val dataCode = error.optJSONObject("data")?.optString("code", "") ?: ""
        val isLock = code == -32003 || dataCode == "refused" ||
            message.contains("open in", ignoreCase = true)
        return if (isLock) {
            ConcatApiException("$message — $LOCK_HINT")
        } else {
            ConcatApiException(message)
        }
    }

    /**
     * Pesan timeout baca (bahasa bayi). Temuan lapangan vc314: timeout saat
     * `project.open` hampir selalu berarti proyek lagi kebuka di layar
     * (lock dua arah — GUI menolak proyek yang dipegang API, dan sebaliknya
     * open via API saat proyek kebuka di layar bermanifestasi sebagai
     * timeout, bukan error lock eksplisit) atau server lagi nyangkut — jadi
     * pesannya menyebut lock secara eksplisit + cara menutup + cara restart
     * server. Timeout method lain: server lagi sibuk, coba lagi + petunjuk
     * restart bila berulang. Timeout BUKAN masalah token — kata "token"
     * sengaja tidak ada di pesan ini (masalah token HANYA kegagalan auth
     * -32006 "wrong token", yang pesannya dibiarkan apa adanya).
     */
    private fun timeoutMessage(phase: String): String {
        val base = "Concat tidak membalas dalam ${formatTimeout(READ_TIMEOUT_MS)} saat ${plainAction(phase)}"
        val restart = "Kalau berulang, restart server Concat (matikan lalu nyalakan tombol Automation di Concat)."
        return if (phase == "project.open") {
            "$base — proyek ini mungkin lagi kebuka di layar Concat (lock), atau server lagi sibuk/nyangkut. " +
                "Tutup dulu proyek di layar Concat, lalu coba lagi. $restart"
        } else {
            "$base — mungkin lagi sibuk, coba lagi. $restart"
        }
    }

    /**
     * Nama metode teknis -> kata kerja sehari-hari untuk pesan yang dibaca
     * manusia. Nama metode yang belum dikenal dipakai apa adanya (fallback).
     */
    private fun plainAction(method: String): String = when (method) {
        "auth" -> "masuk"
        "version" -> "mengecek versi"
        "project.list" -> "melihat daftar proyek"
        "project.open" -> "membuka proyek"
        "project.get" -> "membaca proyek"
        "project.create" -> "membuat proyek"
        "project.close" -> "menutup proyek"
        "project.save" -> "menyimpan proyek"
        "project.setVideo" -> "mengatur frame proyek"
        "edit.apply" -> "menerapkan edit"
        "edit.undo" -> "mengurungkan edit"
        "edit.redo" -> "mengulangi edit"
        "media.probe" -> "memeriksa file media"
        "media.import" -> "mengimpor media"
        "catalogue.list" -> "melihat katalog"
        "template.list" -> "melihat daftar template"
        "template.instantiate" -> "membuat proyek dari template"
        "template.save" -> "menyimpan template"
        "export.run" -> "menjalankan ekspor"
        "export.cancel" -> "membatalkan ekspor"
        "export.status" -> "mengecek status ekspor"
        "preview.frame" -> "mengambil frame pratinjau"
        else -> method
    }

    private fun formatTimeout(ms: Int): String =
        if (ms % 1000 == 0) "${ms / 1000} detik" else "$ms ms"
}
