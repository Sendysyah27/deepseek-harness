package com.senzwh.deepharness

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * vc335 R1 (D1) — PERSIST MESSAGES lintas giliran.
 *
 * MASALAH (vc334 R3 B7): daftar messages giliran (`openaiTurnMessagesVc5b`
 * di MainActivity) HANYA hidup di memori volatil — tiap giliran di-clear
 * lalu diisi SATU pesan user berisi prompt PENUH yang dibangun ulang
 * (~60–90rb char). Mesin delta (AgentPrompt.deltaTurnPromptVc327) tidak
 * pernah bisa dipakai: provider Models API stateless, jadi delta hanya
 * valid bila riwayat messages percakapan itu PERSIST lintas giliran dan
 * dikirim ulang apa adanya. Selama messages tidak dipersist, tiap
 * giliran TERPAKSA mengirim ulang prompt penuh.
 *
 * DESAIN (dipilih sesudah audit arsitektur yang ada):
 *  - PENYIMPANAN = SATU FILE JSON PER SESI, BUKAN tabel DB baru.
 *    Alasan: MemoryDb adalah Android SQLiteOpenHelper (tidak bisa diuji
 *    di suite JVM — dan SqliteSeam-nya lewat RustCore JNI yang tidak ada
 *    di JVM); menambah tabel di sana berarti migrasi skema di file 3.236
 *    baris + logika yang TIDAK bisa dibuktikan tes. File JSON per sesi
 *    mengikuti pola yang SUDAH ada dan terbukti di codebase ini:
 *    AgentBrain (filesDir/otak/ — file SOUL/AGENTS/MEMORY.md), Checkpoint (filesDir per tugas),
 *    BrainBackup (zip file). Lokasi: filesDir/session-messages/
 *    <sessionId>.json. Satu sesi = satu file; tulis atomik (tmp + rename).
 *  - ISI = daftar ChatMessage persis seperti yang dikirim ke provider
 *    (role/content/imageUrl/toolCallId/toolCalls) — jadi "restart"
 *    (buat ulang objek sesi dari storage) menghasilkan daftar yang
 *    SAMA dengan yang dipakai giliran sebelumnya, dan delta giliran
 *    berikut tinggal di-append di atasnya (nextTurnMessages).
 *  - TRIM mengikuti kebijakan D2: pesan user pertama (anchor — pesan
 *    prompt penuh giliran pertama) + 40 TERAKHIR; pasangan
 *    assistant<->tool tetap utuh (potongan di tengah pasangan ikut
 *    menyertakan assistant pemiliknya; hasil tool yang assistant-nya
 *    sudah tidak ada dibuang, bukan disimpan yatim — pesan role:tool
 *    yatim DITOLAK provider OpenAI).
 *  - CAP CHAR TOTAL per sesi = 200.000 char (dijumlah dari content +
 *    imageUrl + arguments tool — lihat totalChars). Angka wajar: ~2–3x
 *    prompt penuh giliran pertama (~60–90rb char), ~50rb token, file
 *    maks ~200 KB per sesi. Cap ditegakkan di save() — pemanggil tidak
 *    perlu ingat memangkas. Pesan tertua dibuang dulu (anchor terakhir
 *    yang kena); satu pesan yang sendirian melebihi cap dipotong
 *    kepala+ekor dengan penanda (pola historyTranscriptVc230).
 *  - LAMPIRAN/GAMBAR: imageUrl "data:..." (base64) TIDAK PERNAH
 *    disimpan — diganti referensi singkat "[gambar <mime> ±<n> B —
 *    base64 tidak dipersist]". imageUrl yang memang sudah referensi
 *    (path file / URL http) disimpan apa adanya. Alasan: satu gambar
 *    vision bisa ~1 MB base64; menyimpannya per giliran akan meledakkan
 *    storage + membuat delta raksasa. Konsekuensi jujur: giliran
 *    berikut TIDAK lagi membawa gambar giliran lama ke provider —
 *    model tetap punya teks prompt + jawaban sebelumnya; bila user
 *    bertanya soal gambar lama, agen bisa minta kirim ulang.
 *  - TIDAK ADA redaksi isi saat persist: isinya persis apa yang sudah
 *    dikirim ke provider (prompt penuh dibangun dari blok yang SUDAH
 *    teredaksi AgentPrompt; MemoryDb.history juga menyimpan teks user
 *    verbatim). Storage lokal filesDir, tingkat kepercayaan sama
 *    dengan riwayat chat yang sudah ada.
 *  - BACKUP: BrainBackup TIDAK mencakup folder ini (konsisten dengan
 *    "riwayat chat TIDAK ikut" di BrainBackup.kt) — messages adalah
 *    cache percakapan perangkat, bukan memori kurasi.
 *
 * WIRING (MainActivity — di luar file ini):
 *  - dispatchTurn membangun delta (deltaTurnPromptVc327) dan
 *    mengopernya ke sendToAgent; sendToAgent memuat persisted dan,
 *    bila canUseDelta, memakai nextTurnMessages (persisted + delta)
 *    sebagai daftar kirim — BUKAN rebuild prompt penuh.
 *  - endUserTurn (satu corong semua akhir giliran) menyimpan
 *    openaiTurnMessagesVc5b untuk giliran native 1-1.
 *  - maybeResumeTurn memuat persisted ke openaiTurnMessagesVc5b
 *    sebelum sendContinuation (resume sesudah proses mati).
 *  - MemoryDb.deleteSession + wipe-all Setup menghapus file sesi ini.
 *  - Dialek TEKS (provider text_fallback) TIDAK dipersist: daftar
 *    messages-nya di-clear tiap lanjutan dan hanya berisi satu pesan
 *    teks — menyimpannya akan menimpa riwayat native yang benar.
 *
 * Semua fungsi di object ini MURNI (tanpa Context) dan bisa diuji JVM;
 * SessionMessagesStore hanya membungkus java.io.File.
 */
object SessionMessagesVc335 {

    /** Subfolder di filesDir untuk file messages per sesi. */
    const val DIR_NAME = "session-messages"

    /** Versi format file JSON (bila format berubah, naikkan + migrasi). */
    const val FILE_VERSION = 1

    /** D2: jumlah pesan TERAKHIR yang dipertahankan di samping anchor. */
    const val KEEP_LAST = 40

    /** Cap char total per sesi (content + imageUrl + arguments tool). */
    const val MAX_TOTAL_CHARS = 200_000

    /** Jumlah char yang dihitung per pesan (role/toolCallId diabaikan — kecil). */
    fun totalChars(messages: List<ChatMessage>): Long {
        var total = 0L
        for (m in messages) {
            total += m.content.length
            total += m.imageUrl?.length ?: 0
            for (tc in m.toolCalls) total += tc.argumentsJson.length
        }
        return total
    }

    /**
     * Trim D2: pesan user pertama (anchor) + [keepLast] pesan terakhir.
     * Pasangan assistant<->tool tetap utuh: bila batas potong jatuh di
     * tengah pasangan (pesan tool yang assistant pemiliknya tepat di
     * luar jendela), assistant itu ikut disertakan; bila assistant
     * pemiliknya SUDAH tidak ada (terpotong di trim sebelumnya), hasil
     * tool yatim di kepala jendela dibuang.
     */
    fun trimD2(messages: List<ChatMessage>, keepLast: Int = KEEP_LAST): List<ChatMessage> {
        if (messages.size <= keepLast + 1) return messages.toList()
        val anchorIdx = messages.indexOfFirst { it.role == "user" }
        var start = messages.size - keepLast
        if (start > 0 && messages[start].role == "tool") {
            var j = start
            while (j >= 0 && messages[j].role == "tool") j--
            if (j >= 0 && messages[j].role == "assistant" && messages[j].toolCalls.isNotEmpty()) {
                start = j // sertakan assistant pemilik pasangan yang terpotong
            } else {
                // Assistant pemilik sudah tidak ada — buang tool yatim di kepala.
                while (start < messages.size && messages[start].role == "tool") start++
            }
        }
        val out = ArrayList<ChatMessage>()
        if (anchorIdx >= 0 && anchorIdx < start) out.add(messages[anchorIdx])
        for (i in start until messages.size) out.add(messages[i])
        return out
    }

    /** Potong satu pesan di tengah (kepala + ekor + penanda), pola vc230. */
    private fun truncateMiddle(msg: ChatMessage, maxChars: Int): ChatMessage {
        val c = msg.content
        if (c.length <= maxChars) return msg
        val marker = "\n…(pesan ini dipotong karena melebihi sisa anggaran penyimpanan; kepala + ekor dipertahankan, tengahnya tidak disertakan)…\n"
        val keep = (maxChars - marker.length).coerceAtLeast(0)
        val head = keep / 2
        val tail = keep - head
        return msg.copy(content = c.take(head) + marker + c.takeLast(tail))
    }

    /**
     * Cap char total: buang pesan tertua dulu (anchor dipertahankan
     * selama mungkin), pasangan assistant<->tool dibuang utuh; bila
     * tinggal anchor sendirian dan masih melebihi cap, anchor dipotong
     * kepala+ekor.
     */
    fun applyCharCap(messages: List<ChatMessage>, maxChars: Int = MAX_TOTAL_CHARS): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        if (totalChars(messages) <= maxChars) return messages.toList()
        val anchor = messages.firstOrNull { it.role == "user" }
        val kept = messages.toMutableList()
        while (kept.size > 1 && totalChars(kept) > maxChars) {
            var idx = 0
            if (kept[0] === anchor) idx = 1
            when {
                kept[idx].role == "assistant" && kept[idx].toolCalls.isNotEmpty() -> {
                    // Buang assistant + SEMUA hasil tool-nya (utuh).
                    var end = idx + 1
                    while (end < kept.size && kept[end].role == "tool") end++
                    repeat(end - idx) { kept.removeAt(idx) }
                }
                kept[idx].role == "tool" -> {
                    var end = idx
                    while (end < kept.size && kept[end].role == "tool") end++
                    repeat(end - idx) { kept.removeAt(idx) }
                }
                else -> kept.removeAt(idx)
            }
        }
        if (kept.size == 1 && totalChars(kept) > maxChars) {
            kept[0] = truncateMiddle(kept[0], maxChars)
        }
        return kept
    }

    /**
     * Sanitasi SATU imageUrl: "data:..." (base64) -> referensi singkat
     * "[gambar <mime> ±<n> B — base64 tidak dipersist]"; path/URL biasa
     * dipertahankan apa adanya (itu sudah referensi). Null -> null.
     */
    fun sanitizeImageRef(imageUrl: String?): String? {
        if (imageUrl == null) return null
        if (!imageUrl.startsWith("data:")) return imageUrl
        val afterData = imageUrl.removePrefix("data:")
        val mime = afterData.substringBefore(",").substringBefore(";").ifBlank { "image" }
        val b64 = afterData.substringAfter(",", "")
        val estBytes = b64.length * 3 / 4
        return "[gambar $mime ±$estBytes B — base64 tidak dipersist]"
    }

    /** Satu pesan disanitasi (hanya imageUrl yang berubah bila data:). */
    fun sanitize(msg: ChatMessage): ChatMessage {
        val ref = sanitizeImageRef(msg.imageUrl)
        return if (ref == msg.imageUrl) msg else msg.copy(imageUrl = ref)
    }

    fun sanitizeAll(messages: List<ChatMessage>): List<ChatMessage> =
        messages.map { sanitize(it) }

    /** Serialisasi daftar -> JSON string (format v1). */
    fun toJson(messages: List<ChatMessage>): String {
        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject()
            o.put("role", m.role)
            o.put("content", m.content)
            m.imageUrl?.let { o.put("imageUrl", it) }
            m.toolCallId?.let { o.put("toolCallId", it) }
            if (m.toolCalls.isNotEmpty()) {
                val ta = JSONArray()
                for (tc in m.toolCalls) {
                    val t = JSONObject()
                    t.put("id", tc.id)
                    t.put("name", tc.name)
                    t.put("argumentsJson", tc.argumentsJson)
                    if (tc.argumentsBroken) t.put("argumentsBroken", true)
                    if (tc.index != 0) t.put("index", tc.index)
                    ta.put(t)
                }
                o.put("toolCalls", ta)
            }
            arr.put(o)
        }
        return JSONObject()
            .put("v", FILE_VERSION)
            .put("savedAt", System.currentTimeMillis())
            .put("messages", arr)
            .toString()
    }

    /**
     * Parse toleran: field tak dikenal diabaikan, entri tanpa role /
     * tool-call tanpa id/nama dilewati, teks kosong/korup -> kosong
     * (TANPA throw — storage korup tidak boleh mematikan giliran).
     */
    fun fromJson(text: String): List<ChatMessage> {
        if (text.isBlank()) return emptyList()
        return try {
            val root = JSONObject(text)
            val arr = root.optJSONArray("messages") ?: return emptyList()
            val out = ArrayList<ChatMessage>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = o.optString("role", "").trim()
                if (role.isEmpty()) continue
                val content = o.optString("content", "")
                val imageUrl = if (o.has("imageUrl")) {
                    o.optString("imageUrl").takeIf { it.isNotEmpty() }
                } else null
                val toolCallId = if (o.has("toolCallId")) {
                    o.optString("toolCallId").takeIf { it.isNotEmpty() }
                } else null
                val tcs = ArrayList<NativeToolCall>()
                val ta = o.optJSONArray("toolCalls")
                if (ta != null) {
                    for (j in 0 until ta.length()) {
                        val t = ta.optJSONObject(j) ?: continue
                        val id = t.optString("id", "")
                        val name = t.optString("name", "")
                        if (id.isEmpty() || name.isEmpty()) continue
                        tcs.add(
                            NativeToolCall(
                                id, name, t.optString("argumentsJson", ""),
                                t.optBoolean("argumentsBroken", false),
                                t.optInt("index", 0)
                            )
                        )
                    }
                }
                out.add(ChatMessage(role, content, imageUrl, toolCallId, tcs))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * True bila messages terpersist bisa dipakai sebagai basis delta:
     * ada pesan user (anchor prompt penuh giliran pertama). Tanpa itu
     * model belum pernah menerima prompt penuh di percakapan ini, jadi
     * delta TIDAK valid — kirim prompt penuh.
     */
    fun canUseDelta(persisted: List<ChatMessage>): Boolean =
        persisted.any { it.role == "user" }

    /**
     * Daftar kirim giliran berikut. Bila persisted kosong (sesi baru /
     * sesudah reset) -> SATU pesan user prompt penuh. Bila ada ->
     * persisted + SATU pesan user delta — prompt penuh TIDAK dibangun
     * ulang (pesan pertama tetap prompt penuh giliran pertama).
     */
    fun nextTurnMessages(
        persisted: List<ChatMessage>,
        fullPrompt: String,
        deltaPrompt: String
    ): List<ChatMessage> {
        if (!canUseDelta(persisted)) return listOf(ChatMessage("user", fullPrompt))
        val out = ArrayList(persisted)
        out.add(ChatMessage("user", deltaPrompt))
        return out
    }
}

/**
 * Store file JSON per sesi di bawah [root] (di HP: ctx.filesDir).
 * Semua operasi best-effort: gagal baca -> kosong, gagal tulis -> false,
 * TIDAK PERNAH throw ke pemanggil (giliran tetap jalan dengan prompt
 * penuh bila storage gagal).
 */
class SessionMessagesStore(private val root: File) {

    fun dir(): File = File(root, SessionMessagesVc335.DIR_NAME)

    fun fileFor(sessionId: Long): File = File(dir(), "$sessionId.json")

    fun load(sessionId: Long): List<ChatMessage> {
        return try {
            val f = fileFor(sessionId)
            if (!f.exists()) return emptyList()
            SessionMessagesVc335.fromJson(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Simpan daftar messages sesi ini. Urutan penegakan: sanitasi
     * (base64 -> referensi) -> trim D2 -> cap char -> tulis atomik
     * (tmp + rename; fallback salin bila rename gagal).
     */
    fun save(sessionId: Long, messages: List<ChatMessage>): Boolean {
        return try {
            val d = dir()
            d.mkdirs()
            val prepared = SessionMessagesVc335.applyCharCap(
                SessionMessagesVc335.trimD2(
                    SessionMessagesVc335.sanitizeAll(messages)
                )
            )
            val tmp = File(d, "$sessionId.json.tmp")
            tmp.writeText(SessionMessagesVc335.toJson(prepared), Charsets.UTF_8)
            val dest = fileFor(sessionId)
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Tambah pesan ke daftar tersimpan (load + tambah + save). */
    fun append(sessionId: Long, vararg msgs: ChatMessage): Boolean {
        val cur = ArrayList(load(sessionId))
        cur.addAll(msgs)
        return save(sessionId, cur)
    }

    /** Hapus file messages satu sesi (dipakai deleteSession). */
    fun delete(sessionId: Long): Boolean {
        return try {
            fileFor(sessionId).delete()
        } catch (e: Exception) {
            false
        }
    }

    /** Hapus SEMUA file messages (dipakai wipe-all). */
    fun deleteAll(): Boolean {
        return try {
            val d = dir()
            if (!d.exists()) return true
            var ok = true
            d.listFiles()?.forEach {
                if (it.isFile && it.name.endsWith(".json")) ok = it.delete() && ok
            }
            ok
        } catch (e: Exception) {
            false
        }
    }
}
