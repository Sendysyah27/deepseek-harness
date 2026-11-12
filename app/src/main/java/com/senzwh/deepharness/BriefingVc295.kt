package com.senzwh.deepharness

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** vc295 — Proactive briefing: ai-release-hunter + daily-meeting-update.
 *  Fungsi MURNI (tanpa Android, tanpa DB, tanpa jaringan) — bisa unit-test
 *  standalone seperti SkillJudge vc293 / MemoryLoopVc294. Jaringan + file
 *  dilakukan pemanggil (Tools.kt); penjadwalan + room dilakukan
 *  Automation.kt menumpang mekanisme routines yang sudah ada.
 *
 *  Sumber pola (SKILL.md dibaca langsung, bukan impor mentah):
 *  - ai-release-hunter: digest harian, dedup seen.json, snapshot,
 *    jadwal ~08:00, repo 404 di-skip, "Why it matters to AUDIENCE" per
 *    item, state file ringan tanpa database baru.
 *  - daily-meeting-update (+ claude_digest.py): 3 fase — tarik aktivitas
 *    kemarin DULU, wawancara 4 pertanyaan dengan data sebagai konteks,
 *    generate update Markdown rapi (maks 15 butir).
 */
object BriefingVc295 {
    const val ROOM_TITLE = "Briefing Harian"
    const val ROUTINE_ID = "briefing_daily"
    const val ROUTINE_TIME = "08:00"
    const val DIR_NAME = "briefing"
    const val SEEN_CAP = 500
    const val MAX_BULLETS = 15
    const val AUDIENCE = "user — builder AI-agent mobile (Harness), kreator konten finansial & motion graphics"

    data class BriefItem(val source: String, val title: String, val url: String, val date: String, val extra: String)

    /** Repo yang dipantau — ketiganya TERVERIFIKASI ADA via GitHub public
     *  API 11 Nov 2026 (tanpa token). Label = pengelompokan di digest. */
    val WATCHED_REPOS: List<Triple<String, String, String>> = listOf(
        Triple("deepseek-ai", "DeepSeek-V3", "DeepSeek"),
        Triple("deepseek-ai", "DeepSeek-R1", "DeepSeek"),
        Triple("anthropics", "claude-code", "Claude Code")
    )
    val HN_KEYWORDS: List<String> = listOf("DeepSeek", "Claude Code", "AI agent", "MCP")

    /** Prompt routine 08:00 — jawaban akhir agen = digest lengkap, yang
     *  dikirim HarnessService/BgBrain sebagai laporan ke room Briefing
     *  Harian (routing appendJobReportEx via sessionId+roomId routine). */
    fun briefingPrompt(): String = """BRIEFING HARIAN (vc295) — buat digest pagi ini untuk room Briefing Harian:
(1) Panggil briefing_scan (ambil rilis/commit baru + HN, sudah ter-dedup via seen.json) dan briefing_yesterday (aktivitas kemarin).
(2) Susun digest Markdown: judul "# Briefing Harian — <tanggal hari ini>", seksi per sumber (## Rilis DeepSeek, ## Claude Code, ## Repo Agentic Baru, ## Hacker News, ## Kemarin di Harness), tiap item rilis/HN dengan SATU baris "Kenapa penting: ..." untuk user (builder AI-agent mobile + kreator konten) + tautannya. Bila TIDAK ADA item baru sama sekali, tulis SATU baris "Tidak ada pergerakan relevan hari ini." — jangan mengarang item.
(3) Akhiri digest dengan 4 pertanyaan wawancara (satu per satu, pakai data kemarin sebagai konteks, mis. "Kemarin kamu ... , apa lagi?"): (a) kemarin — apa lagi yang dikerjakan selain yang tercatat? (b) hari ini — apa yang dikerjakan? (c) blocker — ada yang menghambat? (d) topik diskusi — ada yang mau dibahas?
Jawaban akhirmu = digest lengkap itu (ini yang terkirim sebagai laporan ke room). Jangan kirim email, jangan pakai API berbayar, jangan laporkan item yang sudah ada di seen.json."""

    // ---------- waktu ----------

    fun isoUtc(millis: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(millis))
    }

    fun dateStr(millis: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        f.timeZone = TimeZone.getDefault()
        return f.format(Date(millis))
    }

    fun dateTimeStr(millis: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        f.timeZone = TimeZone.getDefault()
        return f.format(Date(millis))
    }

    /** Rentang "kemarin" (00:00–24:00) dalam zona waktu perangkat. */
    fun yesterdayRange(nowMs: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val end = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, -1)
        return Pair(cal.timeInMillis, end)
    }

    /** Parse ISO-8601 ("2026-11-10T08:30:00Z" / dengan offset). Gagal -> 0. */
    fun parseIsoToMillis(s: String): Long {
        if (s.isBlank()) return 0
        val t = s.trim()
        val fmts = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd"
        )
        for (pat in fmts) {
            try {
                val f = SimpleDateFormat(pat, Locale.US)
                if (pat.endsWith("'Z'")) f.timeZone = TimeZone.getTimeZone("UTC")
                val d = f.parse(t) ?: continue
                return d.time
            } catch (e: Exception) {}
        }
        return 0
    }

    // ---------- dedup seen.json ----------

    fun seenKey(source: String, id: String): String = source.trim() + "|" + id.trim()

    fun seenFromJson(text: String): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) out.add(s)
            }
        } catch (e: Exception) {}
        return out
    }

    fun seenToJson(keys: Collection<String>): String {
        val arr = JSONArray()
        for (k in keys) arr.put(k)
        return arr.toString()
    }

    /** vc299 m11 (N6-m11): lastCheck hanya maju bila ≥1 sumber briefing
     *  sukses (HTTP 200). Bila semua gagal, jendela TIDAK maju supaya
     *  rilis di jendela itu dicek ulang di scan berikut, bukan hilang
     *  diam-diam. */
    fun lastCheckShouldAdvance(okSources: Int): Boolean = okSources > 0

    /** vc299 m12 (N6-m12): hasil tulis daily-update gagal bila string
     *  simpannya diawali "gagal simpan" (lihat Tools.kt
     *  briefing_generate). */
    fun generateFailed(saved: String): Boolean = saved.startsWith("gagal simpan")

    /** vc299 m20 (N6-m20): catatan jujur bila cap latest(300) memotong
     *  jendela kemarin — null bila tidak ada pemotongan. */
    fun yesterdayTruncationNote(totalInWindow: Int, processed: Int, cap: Int): String? {
        return if (totalInWindow > processed) "Catatan: hanya $processed dari $totalInWindow peristiwa kemarin yang diproses (batas ambil $cap peristiwa terbaru) — ringkasan mungkin tidak lengkap."
        else null
    }

    /** Item yang kuncinya belum ada di seen (dedup nyata — yang sudah
     *  pernah dikirim tidak dikirim ulang). */
    fun filterNew(items: List<BriefItem>, seen: Set<String>): List<BriefItem> {
        val used = HashSet<String>()
        val out = ArrayList<BriefItem>()
        for (it in items) {
            val k = seenKey(it.source, itemId(it))
            if (k in seen || k in used) continue
            used.add(k)
            out.add(it)
        }
        return out
    }

    /** Id stabil per item untuk dedup: rilis = repo|tag, commit =
     *  repo|sha 7 char, search = full_name, HN = objectID — disimpan di
     *  extra sebagai "id:<nilai>" (+ "repo:<owner>/<repo>" untuk
     *  rilis/commit) oleh parser (lihat parse* di bawah). Tanpa marker
     *  repo (item lama / HN / search) kembali id polos — mundur
     *  kompatibel. vc299 m10 (N6-m10): repo masuk kunci supaya tag sama
     *  dari dua repo tidak saling membuang. */
    fun itemId(it: BriefItem): String {
        val m = Regex("id:([^\\s|]+)").find(it.extra)
        val id = m?.groupValues?.get(1) ?: (it.title + "|" + it.url)
        val r = Regex("repo:([^\\s]+)").find(it.extra)
        return if (r != null) r.groupValues[1] + "|" + id else id
    }

    /** Gabung kunci baru ke seen, buang yang tertua bila > cap (500). */
    fun mergeSeen(seen: LinkedHashSet<String>, newKeys: Collection<String>): LinkedHashSet<String> {
        val out = LinkedHashSet(seen)
        for (k in newKeys) out.add(k)
        while (out.size > SEEN_CAP) {
            val first = out.iterator().next()
            out.remove(first)
        }
        return out
    }

    // ---------- parser sumber (JSON mentah -> item) ----------

    /** Commit berisik (dependabot, merge, CI, typo) — di-skip, sesuai
     *  aturan "Filter noise" ai-release-hunter. */
    fun noiseCommit(msg: String): Boolean {
        val m = msg.trim().lowercase(Locale.US)
        if (m.isEmpty()) return true
        val prefixes = listOf("bump ", "bump(", "chore(deps", "merge ", "merge pull", "ci:", "revert ", "wip", "docs: fix typo", "fix typo")
        return prefixes.any { m.startsWith(it) }
    }

    /** vc299 m21 (N6-m21): jumlah item yang dilewati parse terakhir
     *  karena tanggal tak ter-parse saat sinceMs > 0 (tidak bisa
     *  dibuktikan baru). Di-reset tiap parse*; dibaca pemanggil
     *  (Tools.briefingScan) untuk dicatat jujur. */
    var lastSkippedUndated: Int = 0
        private set

    /** GET /repos/{o}/{r}/releases — hanya yang published sejak sinceMs;
     *  draf di-skip; prerelease ditandai di extra. */
    fun parseGithubReleases(json: String, sinceMs: Long, owner: String, repo: String): List<BriefItem> {
        val out = ArrayList<BriefItem>()
        lastSkippedUndated = 0
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("draft", false)) continue
                val tag = o.optString("tag_name", "").trim()
                if (tag.isEmpty()) continue
                val pub = parseIsoToMillis(o.optString("published_at", o.optString("created_at", "")))
                // vc299 m21: tanggal tak ter-parse (pub == 0) saat
                // sinceMs > 0 -> DILEWATI (tidak bisa dibuktikan baru).
                if (sinceMs > 0 && pub < sinceMs) {
                    if (pub == 0L) lastSkippedUndated++
                    continue
                }
                val name = o.optString("name", "").trim().ifBlank { tag }
                val pre = if (o.optBoolean("prerelease", false)) " (prerelease)" else ""
                out.add(BriefItem("github-release", name, o.optString("html_url", ""), dateStr(if (pub > 0) pub else System.currentTimeMillis()), "id:$tag · rilis$pre · repo:$owner/$repo"))
            }
        } catch (e: Exception) {}
        return out
    }

    /** GET /repos/{o}/{r}/commits?since=... — commit fitur/perubahan;
     *  noise di-skip (lihat noiseCommit). */
    fun parseGithubCommits(json: String, sinceMs: Long, owner: String, repo: String): List<BriefItem> {
        val out = ArrayList<BriefItem>()
        lastSkippedUndated = 0
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sha = o.optString("sha", "").take(7)
                val msg = o.optJSONObject("commit")?.optString("message", "") ?: ""
                val first = msg.lineSequence().firstOrNull()?.trim() ?: ""
                if (noiseCommit(first)) continue
                val d = o.optJSONObject("commit")?.optJSONObject("author")?.optString("date", "")
                    ?: o.optJSONObject("commit")?.optJSONObject("committer")?.optString("date", "") ?: ""
                val ts = parseIsoToMillis(d)
                // vc299 m21: seperti rilis — tanggal tak ter-parse saat
                // sinceMs > 0 -> DILEWATI.
                if (sinceMs > 0 && ts < sinceMs) {
                    if (ts == 0L) lastSkippedUndated++
                    continue
                }
                out.add(BriefItem("github-commit", first.take(120), o.optString("html_url", ""), dateStr(if (ts > 0) ts else System.currentTimeMillis()), "id:$sha · commit · repo:$owner/$repo"))
            }
        } catch (e: Exception) {}
        return out
    }

    /** GET /search/repositories?q=topic:ai-agents+created:>... — repo
     *  agentic baru 7 hari terakhir, teratas by stars. */
    fun parseGithubSearchRepos(json: String): List<BriefItem> {
        val out = ArrayList<BriefItem>()
        try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("items") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val full = o.optString("full_name", "").trim()
                if (full.isEmpty()) continue
                val desc = o.optString("description", "").trim().take(100)
                val title = o.optString("name", full) + (if (desc.isNotBlank()) " — $desc" else "")
                val lang = o.optString("language", "")
                out.add(BriefItem("github-trending", title, o.optString("html_url", ""), dateStr(parseIsoToMillis(o.optString("created_at", "")).takeIf { it > 0 } ?: System.currentTimeMillis()), "id:$full · ★${o.optInt("stargazers_count", 0)}" + (if (lang.isNotBlank()) " · $lang" else "")))
            }
        } catch (e: Exception) {}
        return out
    }

    /** GET hn.algolia.com search — story relevan 24 jam terakhir. */
    fun parseHnAlgolia(json: String): List<BriefItem> {
        val out = ArrayList<BriefItem>()
        try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("hits") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("objectID", "").trim()
                val title = o.optString("title", "").trim()
                if (id.isEmpty() || title.isEmpty()) continue
                val url = o.optString("url", "").trim().ifBlank { "https://news.ycombinator.com/item?id=$id" }
                val ts = parseIsoToMillis(o.optString("created_at", ""))
                out.add(BriefItem("hacker-news", title, url, dateStr(if (ts > 0) ts else System.currentTimeMillis()), "id:$id · ▲${o.optInt("points", 0)} poin · ${o.optInt("num_comments", 0)} komentar"))
            }
        } catch (e: Exception) {}
        return out
    }

    // ---------- render ----------

    /** Ringkasan aktivitas kemarin — dari data nyata (EventDb + room +
     *  unduhan + ledger); seksi kosong ditulis "tidak ada", bukan diisi
     *  karangan. */
    fun renderYesterdayDigest(date: String, eventLines: List<String>, roomLines: List<String>, downloadLines: List<String>, ledgerLines: List<String>): String {
        val sb = StringBuilder()
        sb.append("Aktivitas kemarin (").append(date).append("):\n")
        sb.append("— Peristiwa HP:\n")
        if (eventLines.isEmpty()) sb.append("  (tidak ada peristiwa tercatat)\n") else for (l in eventLines) sb.append("  ").append(l).append("\n")
        sb.append("— Room & tugas:\n")
        if (roomLines.isEmpty()) sb.append("  (tidak ada aktivitas room kemarin)\n") else for (l in roomLines) sb.append("  ").append(l).append("\n")
        sb.append("— Unduhan baru:\n")
        if (downloadLines.isEmpty()) sb.append("  (tidak ada unduhan baru kemarin)\n") else for (l in downloadLines) sb.append("  ").append(l).append("\n")
        sb.append("— Keputusan/flag ledger:\n")
        if (ledgerLines.isEmpty()) sb.append("  (tidak ada)\n") else for (l in ledgerLines) sb.append("  ").append(l).append("\n")
        return sb.toString().trim()
    }

    /** 4 pertanyaan wawancara daily-meeting-update — Q1 memakai data
     *  kemarin sebagai konteks ("Kemarin kamu ..., apa lagi?"). */
    fun interviewQuestions(yesterdayLines: List<String>): List<String> {
        val ctx = if (yesterdayLines.isEmpty()) "tidak ada aktivitas yang tercatat kemarin"
            else yesterdayLines.take(3).joinToString("; ")
        return listOf(
            "Kemarin: tercatat — $ctx. Apa lagi yang kamu kerjakan kemarin yang tidak tercatat di sini?",
            "Hari ini: apa yang akan kamu kerjakan?",
            "Blocker: ada yang menghambatmu? (jawab \"tidak ada\" bila lancar)",
            "Topik diskusi: ada yang mau kamu bahas di akhir daily? (jawab \"tidak ada\" bila tidak ada)"
        )
    }

    /** Update harian Markdown (Fase 3 daily-meeting-update) — gabung
     *  jawaban wawancara + data; MAKS 15 butir total (standup dibaca
     *  <2 menit); kelebihan dipotong dari Topics dulu, lalu Today,
     *  lalu Yesterday; catatan pemotongan ditulis jujur. */
    fun renderDailyUpdate(date: String, yesterday: List<String>, today: List<String>, blockers: List<String>, topics: List<String>, links: List<String>): String {
        val y = ArrayList(yesterday.filter { it.isNotBlank() })
        val t = ArrayList(today.filter { it.isNotBlank() })
        val b = ArrayList(blockers.filter { it.isNotBlank() })
        val p = ArrayList(topics.filter { it.isNotBlank() })
        var trimmed = 0
        fun total(): Int = y.size + t.size + b.size + p.size
        while (total() > MAX_BULLETS) {
            when {
                p.isNotEmpty() -> { p.removeAt(p.lastIndex); trimmed++ }
                t.isNotEmpty() -> { t.removeAt(t.lastIndex); trimmed++ }
                y.isNotEmpty() -> { y.removeAt(y.lastIndex); trimmed++ }
                else -> { b.removeAt(b.lastIndex); trimmed++ }
            }
        }
        val sb = StringBuilder()
        sb.append("# Daily Update - ").append(date).append("\n\n")
        sb.append("## Yesterday\n")
        if (y.isEmpty()) sb.append("- (tidak ada)\n") else for (s in y) sb.append("- ").append(s).append("\n")
        sb.append("\n## Today\n")
        if (t.isEmpty()) sb.append("- (belum diisi — tanyakan ke user)\n") else for (s in t) sb.append("- ").append(s).append("\n")
        sb.append("\n## Blockers\n")
        if (b.isEmpty()) sb.append("- No blockers\n") else for (s in b) sb.append("- ").append(s).append("\n")
        sb.append("\n## Topics for Discussion\n")
        if (p.isEmpty()) sb.append("- None\n") else for (s in p) sb.append("- ").append(s).append("\n")
        if (links.isNotEmpty()) {
            sb.append("\n---\n*Links:*\n")
            for (l in links) sb.append("- ").append(l).append("\n")
        }
        if (trimmed > 0) sb.append("\n*(Dipotong $trimmed butir ke batas 15 — standup dibaca <2 menit.)*\n")
        return sb.toString().trim()
    }

    /** Argumen tool -> daftar string: terima JSONArray string, string
     *  tunggal, atau string multi-baris (dipisah \n). */
    fun argToList(args: JSONObject, key: String): List<String> {
        val out = ArrayList<String>()
        try {
            val arr = args.optJSONArray(key)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotEmpty()) out.add(s)
                }
                return out
            }
            val s = args.optString(key, "").trim()
            if (s.isNotEmpty()) {
                for (line in s.lines()) {
                    val t = line.trim().trimStart('-', '•', ' ').trim()
                    if (t.isNotEmpty()) out.add(t)
                }
            }
        } catch (e: Exception) {}
        return out
    }
}
