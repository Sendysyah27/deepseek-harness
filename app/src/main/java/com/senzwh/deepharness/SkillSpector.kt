package com.senzwh.deepharness

// vc262 (Fase C): SkillSpector — pemindai keamanan skill SEBELUM impor.
// BUKAN checkpoint approval eksekusi tool: ini pemindai saat impor
// (seperti antivirus). Tool agen tetap jalan tanpa approval (vc247).
// Skor 0-100 dari pola berisiko di bawah; skor >= 80 (BLOCK_THRESHOLD)
// = instalasi DITOLAK dengan skor + temuan ditampilkan. Skor 40-79 =
// tampil sebagai peringatan, impor tetap jalan (skill baru NONAKTIF).
// Pola dipindai pada gabungan nama + description + body skill.
object SkillSpector {
    const val BLOCK_THRESHOLD = 80
    const val WARN_THRESHOLD = 40

    data class ScanResult(val score: Int, val findings: List<String>) {
        val blocked: Boolean get() = score >= BLOCK_THRESHOLD
        val warned: Boolean get() = score >= WARN_THRESHOLD
    }

    private data class Pattern(val regex: String, val weight: Int, val label: String)

    private val PATTERNS = listOf(
        Pattern("(api[_-]?key|apikey|client[_-]?secret|aws[_-]?secret|private[_-]?key)\\s*[:=]", 35, "pola kredensial (api key / secret / private key)"),
        Pattern("password\\s*[:=]", 30, "pola password mentah"),
        Pattern("bearer\\s+[A-Za-z0-9._-]+", 25, "token bearer mentah"),
        Pattern("(run_terminal|Runtime\\.exec|ProcessBuilder|os\\.system|subprocess\\.|shell\\s*\\()", 25, "eksekusi shell"),
        Pattern("(http_request|http\\.get|http\\.post|fetch\\s*\\(|curl\\s|wget\\s|XMLHttpRequest)", 20, "panggilan jaringan"),
        Pattern("(abaikan (instruksi|aturan|system prompt|prompt sistem)|ignore (previous|all) instructions|disregard (previous|all) instructions|jangan (patuhi|ikuti) (instruksi|aturan))", 25, "frasa prompt-injection"),
        Pattern("(/sdcard|/data/data|\\.\\./|rm\\s+-rf|delete\\s+\\*)", 15, "akses file luas / hapus massal"),
        Pattern("(gmail_send|send_sms|kirim (email|sms|pesan) ke)", 15, "pengiriman keluar"),
        Pattern("(delete_session|memory_forget|otak_write)", 10, "tool destruktif / tulis otak"),
        Pattern("(token|secret|password).{0,40}(http_request|gmail_send|upload|kirim)", 20, "kredensial + pengiriman keluar")
    )

    fun scan(name: String, descr: String, body: String): ScanResult {
        val text = (name + "\n" + descr + "\n" + body)
        var score = 0
        val found = ArrayList<String>()
        for (p in PATTERNS) {
            try {
                if (Regex(p.regex, RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    score += p.weight
                    found.add(p.label)
                }
            } catch (e: Exception) {
            }
        }
        if (score > 100) score = 100
        return ScanResult(score, found)
    }

    fun verdictText(r: ScanResult): String {
        return if (r.blocked) "DIBLOKIR SkillSpector (skor " + r.score + "/100, ambang tolak " + BLOCK_THRESHOLD + "): " + r.findings.joinToString("; ")
        else if (r.warned) "peringatan SkillSpector (skor " + r.score + "/100): " + r.findings.joinToString("; ")
        else "skor risiko SkillSpector " + r.score + "/100 (aman)"
    }
}
