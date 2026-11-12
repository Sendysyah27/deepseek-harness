package com.senzwh.deepharness

import org.json.JSONObject

// vc282 — LAPISAN API INTERNAL (internal API layer) untuk otak DeepSeek WEB.
// Perintah user 2026-11-09 21:06: otak web dibungkus antarmuka internal
// yang kompatibel-OpenAI (chat completions + tools -> tool_calls terstruktur
// -> eksekusi -> tool messages -> loop), sehingga dari sisi agen, web brain
// diperlakukan PERSIS seperti model API lainnya. File ini MURNI (tanpa
// Android, tanpa Context): semua fungsi di sini bisa diuji unit via kotlinc
// standalone bersama ToolRegistry + WorkLog saja.
//
// BATAS JUJUR (dari rencana OPTIMASI §2.2, TIDAK berubah di vc ini): ini
// BUKAN function calling native server-side. Web chat hanya mengembalikan
// teks; tidak ada constrained decoding, tidak ada tool_choice deterministik,
// tidak ada finish_reason. Yang dibangun di sini adalah KONTRAK KLIEN:
// format kanonik tunggal + parser strict-dulu-repair-kemudian + validasi
// skema + pseudo tool_choice (instruksi + validasi client-side, 1x repair
// terarah lalu berhenti jujur) + pesan hasil berstatus terstruktur.
// Otak TETAP DeepSeek web (DsApiClient/SlotClient); TIDAK ADA klien API
// eksternal, TIDAK ADA field API key, TIDAK ADA perubahan jalur API.
object ApiToolLayer {

    // ---- 1. Status terstruktur hasil tool (prioritas 5) ----
    enum class ToolStatus { OK, FAILED, TIMEOUT, UNKNOWN }

    // Penanda status yang sama dipakai WorkLog.isFailed: bila hasil membawa
    // "[STATUS: ok]" maka isFailed = false walau teksnya mengandung kata
    // yang masuk daftar substring; "[STATUS: failed/timeout/unknown]" =
    // gagal. Substring-scan hanya fallback bila tidak ada penanda.
    fun statusTag(status: ToolStatus): String = when (status) {
        ToolStatus.OK -> "[STATUS: ok]"
        ToolStatus.FAILED -> "[STATUS: failed]"
        ToolStatus.TIMEOUT -> "[STATUS: timeout]"
        ToolStatus.UNKNOWN -> "[STATUS: unknown]"
    }

    fun statusOfTag(result: String): ToolStatus? {
        return when {
            // vc299 m16: simulasi gagal = GAGAL (dicek dulu; tag ini
            // tidak mengandung "[STATUS: ok]" tapi eksplisit lebih aman).
            result.contains("[STATUS: simulasi-gagal]") -> ToolStatus.FAILED
            result.contains("[STATUS: ok]") -> ToolStatus.OK
            result.contains("[STATUS: failed]") -> ToolStatus.FAILED
            result.contains("[STATUS: timeout]") -> ToolStatus.TIMEOUT
            result.contains("[STATUS: unknown]") -> ToolStatus.UNKNOWN
            else -> null
        }
    }

    // Klasifikasi deterministik hasil tool mentah -> status. Aturan SAMA
    // dengan WorkLog.isFailed (daftar mark disalin, bukan diubah) supaya
    // keduanya tidak pernah berpendapat beda: "id tidak ditemukan" TETAP
    // gagal (tes C vc280), timeout O18 TETAP timeout, "Tool tidak dikenal"
    // TETAP unknown (bukan ok).
    private val FAIL_MARKS = listOf(
        "gagal", "tidak terbaca", "exception", "tidak ditemukan", "tidak ada",
        "tool tidak dikenal", "ditolak", "tidak bisa", "tidak berhasil",
        "tidak tersimpan", "dibatalkan"
    )

    fun classifyResult(result: String): ToolStatus {
        statusOfTag(result)?.let { return it }
        if (result.startsWith("Tool tidak dikenal")) return ToolStatus.UNKNOWN
        val low = result.lowercase()
        if (result.startsWith("⚠️") || result.startsWith("Error") || result.startsWith("Gagal")) {
            if (low.contains("melewati batas") && low.contains("dtk")) return ToolStatus.TIMEOUT
            return ToolStatus.FAILED
        }
        if (low.contains("melewati batas") && low.contains("dtk")) return ToolStatus.TIMEOUT
        if (FAIL_MARKS.any { low.contains(it) }) return ToolStatus.FAILED
        return ToolStatus.OK
    }

    fun isTimeoutResult(result: String): Boolean = classifyResult(result) == ToolStatus.TIMEOUT

    // ---- 2. Format kanonik tool-call + tool-message (prioritas 1) ----
    // Satu format blok JSON tool-call yang sama di semua lane (dari
    // ToolRegistry): {"tool": nama, "args": {...}, "id": "tc-<n>"}.
    fun canonicalCallJson(tool: String, args: JSONObject, callId: String): String {
        val o = JSONObject()
        o.put("tool", tool)
        o.put("args", args)
        o.put("id", callId)
        return "```json\n" + o.toString() + "\n```"
    }

    // Pesan hasil ala API (analog role:"tool" OpenAI / tool_result
    // Anthropic): satu blok per call, id pengikat + status + isi. Dipakai
    // pesan continuation gabungan (batch) supaya model membaca hasil dalam
    // format yang sama di tiap giliran, bukan teks bebas.
    // vc301 SF-D7 — DEAD CODE: fungsi ini tidak dipakai jalur mana pun di
    // tree vc300 (grep: 0 pemanggil di luar definisi; jalur 1-1 mengirim
    // displayResult langsung ke sendContinuation, bukan lewat sini).
    // Dipertahankan (bukan dihapus) supaya jalur batch yang suatu saat
    // diaktifkan tidak pecah diam-diam — tapi capnya disamakan ke cap
    // hidup 9000 di Tools.execute, supaya bila dipakai ia tidak memotong
    // di 3000 seperti dulu. Cap hidup = 9000 di Tools.execute.
    fun formatToolResult(callId: String, tool: String, result: String, resultId: String?, totalChars: Int): String {
        val status = classifyResult(result)
        val cap = AgentPromptBatchCap
        val disp = RedactSecrets.redact(result)
        val capped = if (disp.length > cap) disp.take(cap) + "…(dipotong, sisa " + (disp.length - cap) + " char" + (if (!resultId.isNullOrBlank()) ", pakai get_result[" + resultId + "] untuk sisanya" else "") + ")…" else disp
        val sb = StringBuilder()
        sb.append("[TOOL ").append(callId).append("] tool=").append(tool).append(" ")
        sb.append(statusTag(status))
        if (!resultId.isNullOrBlank()) sb.append(" [id=").append(resultId).append(", total ").append(totalChars).append(" char]")
        sb.append(":\n").append(capped)
        return sb.toString()
    }

    // vc301 SF-D7: 3000 -> 9000, selaras cap hidup Tools.execute.
    const val AgentPromptBatchCap = 9000

    // ---- 3. Parser: strict dulu, repair kemudian (prioritas 1) ----
    data class ParseOutcome(val calls: List<JSONObject>, val repaired: Boolean, val strictError: String?)

    private fun balancedEnd(text: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        var j = start
        while (j < text.length) {
            val c = text[j]
            if (esc) { esc = false; j++; continue }
            if (c == '\\') { esc = true; j++; continue }
            if (c == '"') { inStr = !inStr; j++; continue }
            if (!inStr) {
                if (c == '{') depth++
                else if (c == '}') { depth--; if (depth == 0) return j + 1 }
            }
            j++
        }
        return -1
    }

    // Perbaikan ringan deterministik (cermin AgentPrompt.repairToolJson
    // O1, ditulis ulang di sini supaya lapisan ini tetap murni dan bisa
    // diuji tanpa kompilasi seluruh AgentPrompt): hapus trailing comma,
    // tutup kurung/bracket yang kurang, buang teks di luar blok JSON.
    // TIDAK mengubah makna args.
    fun repairToolJson(raw: String): String? {
        var s = raw.trim()
        val first = s.indexOf('{')
        val last = s.lastIndexOf('}')
        if (first < 0) return null
        s = if (last > first) s.substring(first, last + 1) else s.substring(first)
        var prev: String
        do {
            prev = s
            s = s.replace(Regex(""",\s*([}\]])"""), "\$1")
        } while (s != prev)
        val stack = ArrayDeque<Char>()
        var inStr = false
        var esc = false
        for (c in s) {
            if (esc) { esc = false; continue }
            if (c == '\\') { esc = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (inStr) continue
            if (c == '{' || c == '[') stack.addLast(c)
            else if (c == '}') { if (stack.isNotEmpty() && stack.last() == '{') stack.removeLast() }
            else if (c == ']') { if (stack.isNotEmpty() && stack.last() == '[') stack.removeLast() }
        }
        if (inStr) s += "\""
        val sb = StringBuilder(s)
        while (stack.isNotEmpty()) sb.append(if (stack.removeLast() == '{') '}' else ']')
        val out = sb.toString()
        return try { JSONObject(out); out } catch (e: Exception) { null }
    }

    private fun normalizeCall(obj: JSONObject): JSONObject? {
        val toolName = obj.optString("tool").ifBlank { obj.optString("name") }
        if (toolName.isBlank()) return null
        var args = obj.optJSONObject("args") ?: obj.optJSONObject("arguments") ?: obj.optJSONObject("parameters")
        if (args == null) {
            args = JSONObject()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k != "tool" && k != "name" && k != "id") try { args.put(k, obj.get(k)) } catch (e: Exception) {}
            }
        }
        val out = JSONObject().put("tool", toolName).put("args", args)
        if (obj.has("id")) try { out.put("id", obj.optString("id")) } catch (e: Exception) {}
        return out
    }

    private fun assignIds(calls: List<JSONObject>): List<JSONObject> {
        val used = HashSet<String>()
        val out = ArrayList<JSONObject>()
        for ((idx, c) in calls.withIndex()) {
            val modelId = c.optString("id")
            val id = if (modelId.matches(Regex("^tc-\\d+$")) && modelId !in used) modelId else "tc-" + (idx + 1)
            used.add(id)
            val copy = JSONObject(c.toString())
            copy.put("id", id)
            out.add(copy)
        }
        return out
    }

    // Strict: HANYA blok pagar ```json yang diakui; tiap blok yang memuat
    // "tool" WAJIB ter-parse (langsung atau sesudah repair) dan membawa
    // "args" berupa objek. Satu blok tool yang gagal = seluruh strict
    // GAGAL (error menjelaskan bloknya) — pemanggil jatuh ke lenient.
    fun parseStrict(text: String): ParseOutcome {
        val fenceRe = Regex("```(?:json)?[ \t]*\n(.*?)\n?```", RegexOption.DOT_MATCHES_ALL)
        val calls = ArrayList<JSONObject>()
        var sawToolFence = false
        var repairedAny = false
        for (m in fenceRe.findAll(text)) {
            val gtxt = m.groupValues[1]
            if (!gtxt.contains("\"tool\"")) continue
            sawToolFence = true
            val braceIdx = gtxt.indexOf('{')
            if (braceIdx < 0) return ParseOutcome(emptyList(), false, "blok json tool tanpa '{' (posisi ${m.range.first})")
            var rawSub = gtxt.substring(braceIdx)
            val end = balancedEnd(rawSub, 0)
            if (end >= 0) rawSub = rawSub.substring(0, end)
            var obj = try { JSONObject(rawSub) } catch (e: Exception) { null }
            var repaired = false
            if (obj == null) {
                val rep = repairToolJson(rawSub)
                obj = try { rep?.let { JSONObject(it) } } catch (e: Exception) { null }
                repaired = obj != null
            }
            if (obj == null) return ParseOutcome(emptyList(), false, "blok json tool tidak bisa dibaca walau sesudah repair (posisi ${m.range.first})")
            if (!obj.has("args") || obj.opt("args") !is JSONObject) {
                return ParseOutcome(emptyList(), false, "blok json tool '" + obj.optString("tool") + "' tanpa objek args")
            }
            val norm = normalizeCall(obj) ?: return ParseOutcome(emptyList(), false, "blok json tool tanpa nama tool")
            calls.add(norm)
            if (repaired) repairedAny = true
        }
        if (!sawToolFence) return ParseOutcome(emptyList(), false, null)
        if (calls.isEmpty()) return ParseOutcome(emptyList(), false, "blok json tool tidak menghasilkan call")
        return ParseOutcome(assignIds(calls), repairedAny, null)
    }

    // Lenient (cermin aturan vc229 R-05): JSON polos HANYA di posisi AKHIR
    // respons, atau dialek tag <tool=nama>{json}. JSON "tool" di tengah
    // prosa/kutipan/contoh DIABAIKAN.
    fun parseLenient(text: String): List<JSONObject> {
        val stripped = text.replace(Regex("\\[(USER|HASIL TOOL|RIWAYAT|SISTEM|FAKTA PIN)[^\\]]*\\]"), "").trim()
        var i = 0
        while (i < stripped.length) {
            if (stripped[i] != '{') { i++; continue }
            val end = balancedEnd(stripped, i)
            if (end < 0) {
                val tail = stripped.substring(i)
                val rep = repairToolJson(tail)
                val repObj = try { rep?.let { JSONObject(it) } } catch (e: Exception) { null }
                val repNorm = repObj?.let { normalizeCall(it) }
                if (repNorm != null) return assignIds(listOf(repNorm))
                break
            }
            val rawSub = stripped.substring(i, end)
            var obj = try { JSONObject(rawSub) } catch (e: Exception) { null }
            if (obj == null) {
                val rep = repairToolJson(rawSub)
                obj = try { rep?.let { JSONObject(it) } } catch (e: Exception) { null }
            }
            val norm = obj?.let { normalizeCall(it) }
            if (norm != null) {
                val rest = stripped.substring(end).trim()
                val trailingOk = rest.isEmpty() || rest.lines().all { it.trim() in listOf("```", "```json", "json") }
                if (trailingOk) return assignIds(listOf(norm))
            }
            i = end
        }
        val tagRe = Regex("<tool=([A-Za-z0-9_]+)>\\s*(\\{.*)", RegexOption.DOT_MATCHES_ALL)
        val tm = tagRe.find(stripped)
        if (tm != null) {
            val body = tm.groupValues[2]
            val end = balancedEnd(body, 0)
            val sub = if (end >= 0) body.substring(0, end) else body
            val obj = try { JSONObject(sub) } catch (e: Exception) { null }
            if (obj != null) {
                val out = JSONObject().put("tool", tm.groupValues[1]).put("args", obj)
                return assignIds(listOf(out))
            }
        }
        return emptyList()
    }

    // Strict dulu; bila strict gagal (atau tidak ada pagar tool), jatuh ke
    // lenient. repaired=true hanya bila ada call yang lolos lewat repair
    // atau jalur lenient sesudah strict gagal.
    fun parseWithRepair(text: String): ParseOutcome {
        val strict = parseStrict(text)
        if (strict.calls.isNotEmpty()) return strict
        val lenient = parseLenient(text)
        if (lenient.isNotEmpty()) return ParseOutcome(lenient, true, strict.strictError)
        return ParseOutcome(emptyList(), false, strict.strictError)
    }

    // ---- 4. Validasi skema murni dari ToolRegistry (prioritas 1) ----
    // Cermin Tools.validateArgs TANPA Context (tanpa daftar nama valid dari
    // katalog): nama tak dikenal / tipe salah / enum di luar daftar /
    // argumen wajib hilang -> pesan instruktif; null bila lolos. Dipakai
    // unit test + jalur yang mem-bypass Tools.execute (planner, foto).
    // Argumen yang TIDAK ada di skema diabaikan (fail-open): hanya yang
    // PASTI salah yang ditolak.
    fun validateCall(name: String, args: JSONObject): String? {
        val def = ToolRegistry.BY_NAME[name]
        if (def == null) {
            val near = ToolRegistry.nearestNames(name, 3)
            return "Tool tidak dikenal: " + name + ". Mungkin maksudmu: " + near.joinToString(", ") + "? Pilih PERSIS salah satu nama itu, atau panggil list_tools {} untuk katalog lengkap."
        }
        for (spec in def.args) {
            val raw = if (args.has(spec.name)) args.opt(spec.name) else null
            if (raw == null || raw == JSONObject.NULL) {
                if (spec.required) return "PANGGILAN TOOL DITOLAK SISTEM: argumen `" + spec.name + "` pada tool `" + name + "` wajib diisi (" + spec.type + "), tetapi tidak ada / kosong. Coba lagi dengan `" + spec.name + "` terisi — contoh format benar: {\"tool\":\"" + name + "\",\"args\":" + def.argsExample + "}."
                continue
            }
            if (!typeMatches(spec.type, raw)) {
                return "PANGGILAN TOOL DITOLAK SISTEM: argumen `" + spec.name + "` pada tool `" + name + "` wajib " + spec.type + ", diterima: " + describeValue(raw) + ". Coba lagi dengan `" + spec.name + "` berupa " + spec.type + " — contoh format benar: {\"tool\":\"" + name + "\",\"args\":" + def.argsExample + "}."
            }
            if (spec.enumValues.isNotEmpty()) {
                val v = raw.toString()
                if (spec.enumValues.none { it.equals(v, ignoreCase = true) }) {
                    return "PANGGILAN TOOL DITOLAK SISTEM: argumen `" + spec.name + "` pada tool `" + name + "` harus salah satu dari: " + spec.enumValues.joinToString(", ") + " — diterima: \"" + v.take(40) + "\"."
                }
            }
        }
        return null
    }

    private fun describeValue(v: Any?): String {
        return when (v) {
            is String -> "string (\"" + v.take(30) + "\")"
            is Number -> "angka (" + v.toString().take(30) + ")"
            is Boolean -> "boolean (" + v.toString() + ")"
            is org.json.JSONArray -> "array"
            is JSONObject -> "objek"
            else -> "nilai (" + (v?.toString()?.take(30) ?: "kosong") + ")"
        }
    }

    private fun typeMatches(type: String, v: Any?): Boolean {
        return when (type) {
            "string" -> v is String
            "int" -> v is Int || v is Long || (v is Double && v == kotlin.math.floor(v) && v >= Int.MIN_VALUE && v <= Int.MAX_VALUE)
            "num" -> v is Number
            "bool" -> v is Boolean
            "array" -> v is org.json.JSONArray
            "object" -> v is JSONObject
            else -> true
        }
    }

    // ---- 5. Pseudo tool_choice (prioritas 2) ----
    // BUKAN enforcement server-side: hanya instruksi + validasi
    // client-side. Model diminta memanggil tool tertentu; bila tidak,
    // SATU kali repair terarah, lalu giliran lanjut jujur tanpa tool itu.
    enum class ChoiceKind { AUTO, NONE, REQUIRED }

    data class ToolChoice(val kind: ChoiceKind, val tool: String = "")

    fun choiceInstruction(choice: ToolChoice): String {
        return when (choice.kind) {
            ChoiceKind.AUTO -> ""
            ChoiceKind.NONE -> "TOOL_CHOICE=none (vc282): pada giliran ini JANGAN memanggil tool apa pun — jawab final langsung dari konteks yang ada."
            ChoiceKind.REQUIRED -> "TOOL_CHOICE=" + choice.tool + " (vc282, pseudo tool_choice — instruksi + validasi client-side, BUKAN enforcement server-side): tugas ini MENUNTUT tool '" + choice.tool + "' dipanggil dulu sebelum jawaban final. Panggil '" + choice.tool + "' sekarang dengan argumen lengkap dari perintah user; bila tool itu tidak bisa dipakai, jawab final dengan jujur bahwa tool itu tidak bisa dipakai — jangan menjawab dari ingatan seolah sudah memeriksa."
        }
    }

    // Kembalikan (terpenuhi?, teks repair terarah atau null).
    fun checkToolChoice(calls: List<JSONObject>, choice: ToolChoice): Pair<Boolean, String?> {
        return when (choice.kind) {
            ChoiceKind.AUTO -> Pair(true, null)
            ChoiceKind.NONE -> if (calls.isEmpty()) Pair(true, null) else Pair(false, "SISTEM (pseudo tool_choice=none, vc282): giliran ini dilarang memanggil tool — jawabanmu memuat " + calls.size + " panggilan tool. Abaikan panggilan itu dan jawab final langsung tanpa tool.")
            ChoiceKind.REQUIRED -> if (calls.any { it.optString("tool") == choice.tool }) Pair(true, null) else Pair(false, "SISTEM (pseudo tool_choice, vc282 — repair terarah 1x): tugas ini menuntut tool '" + choice.tool + "' dipanggil dulu, tetapi jawabanmu tidak memanggilnya. Panggil '" + choice.tool + "' SEKARANG dengan argumen lengkap dari perintah user (blok json saja), jangan menjawab final dulu.")
        }
    }

    // Heuristik pemicu tool wajib dari teks tugas user (deterministik,
    // konservatif — hanya pola eksplisit "… dulu baru …"). Bukan
    // klasifikasi umum: tugas tanpa kata "dulu" TIDAK pernah memaksa tool.
    fun detectForcedTool(taskText: String): String? {
        val low = taskText.lowercase()
        if (!low.contains("dulu")) return null
        if (low.contains("baca") && (low.contains("file") || low.contains("/sdcard") || low.contains(".txt") || low.contains(".md"))) return "read_file"
        if (low.contains("baterai")) return "get_battery"
        if (low.contains("penyimpanan") || low.contains("storage")) return "storage_info"
        if (low.contains("daftar file") || low.contains("isi folder")) return "list_files"
        return null
    }

    fun toolChoiceRepairText(tool: String): String {
        return "SISTEM (pseudo tool_choice, vc282 — repair terarah 1x): tugas ini menuntut tool '" + tool + "' dipanggil dulu, tetapi jawabanmu tidak memanggilnya. Panggil '" + tool + "' SEKARANG dengan argumen lengkap dari perintah user (blok json saja), jangan menjawab final dulu."
    }

    // ---- 6. Timeout batch (prioritas 3) ----
    // Cermin tabel vc278 O18(a) jalur tunggal: baca cepat 30 dtk,
    // tulis/shell umum 120 dtk, run_terminal mengikuti timeout yang sudah
    // ada (proot 900 dtk, termux 90 dtk). Dipakai Future.get di kelompok
    // baca batch (BatchToolVc274 + 1-1) supaya satu call yang menggantung
    // tidak menggantung seluruh batch selamanya.
    val READ_FAST_TOOLS_VC282 = setOf(
        "read_file", "list_files", "recall_search", "otak_read",
        "storage_info", "list_tools", "get_result", "grep_result",
        "list_sessions", "list_goals", "list_rooms", "list_workers",
        "list_artifacts", "list_skills", "list_tasks",
        "list_skill_versions", "read_skill_version"
    )

    fun batchTimeoutSec(tool: String, args: JSONObject): Int {
        if (tool == "run_terminal") {
            return if (args.optString("mode", "") == "proot") 900 else 90
        }
        return if (tool in READ_FAST_TOOLS_VC282) 30 else 120
    }

    // ---- 7. Pesan sistem sebagai bukti reviewer (prioritas 6) ----
    // Klaim yang mengutip pesan sistem (peringatan StuckDetector, kunci
    // giliran, cap langkah) TIDAK BOLEH dihapus filter [KLAIM DIHAPUS]:
    // bukti reviewer boleh memakai pesan sistem. Kembalikan daftar
    // unsupported SESUDAH mengeluarkan klaim yang didukung sistem.
    // Aturan sempit: klaim harus mengandung frasa sistem yang khas DAN
    // frasa itu memang ada di catatan sistem giliran ini, atau klaim
    // berbagi >=3 kata isi (panjang >=5) dengan catatan sistem.
    val SYSTEM_PHRASES = listOf("stuckdetector", "detektor putaran", "berputar", "cap langkah", "giliran dihentikan", "giliran dikunci")

    fun dropSystemBackedClaims(claims: List<String>, unsupported: List<Int>, systemNotes: List<String>): List<Int> {
        if (systemNotes.isEmpty()) return unsupported
        val joined = systemNotes.joinToString("\n").lowercase()
        val out = ArrayList<Int>()
        for (idx in unsupported) {
            val cl = claims.getOrNull(idx - 1) ?: continue
            val lowCl = cl.lowercase()
            val phraseBacked = SYSTEM_PHRASES.any { ph -> lowCl.contains(ph) && joined.contains(ph) }
            if (phraseBacked) continue
            val words = lowCl.split(Regex("[^a-z0-9]+")).filter { it.length >= 5 }.distinct()
            val hits = words.count { joined.contains(it) }
            if (words.size >= 3 && hits >= 3) continue
            out.add(idx)
        }
        return out
    }
}
