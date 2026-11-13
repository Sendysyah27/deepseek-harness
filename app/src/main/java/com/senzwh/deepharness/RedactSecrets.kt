package com.senzwh.deepharness

/** v4.48.8 — Redaksi pola kredensial KEYAKINAN TINGGI saja, satu fungsi untuk
 *  semua jalur presentasi (live, JEJAK, digest, get_result, grep_result, dump,
 *  lapisan memori). Store tetap mentah; redaksi HANYA saat disajikan, supaya
 *  satu pola berlaku konsisten di semua jalur. Penanda terlihat model
 *  ([REDAKSI: ...]) supaya model tahu ada nilai yang disembunyikan, bukan
 *  hilang diam-diam. Hash hex (MD5/SHA/signer) TIDAK cocok pola mana pun dan
 *  lolos utuh. Redaksi IDEMPOTEN: redact(redact(x)) == redact(x) — guard
 *  [REDAKSI mencegah penanda menimpa dirinya sendiri. App password hanya
 *  dikenali bila satu baris utuh 4x4 huruf ATAU berlabel, supaya kalimat
 *  biasa ("saya suka baca buku di rumah") tidak kena. */
object RedactSecrets {
    private val PEM_RE = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", RegexOption.DOT_MATCHES_ALL)
    private val APP_LABELED_RE = Regex("(?i)(app[- ]?password|kata sandi aplikasi|sandi aplikasi)\\s*[:=]?\\s*[a-z]{4}( [a-z]{4}){3}")
    private val BEARER_RE = Regex("(?i)Bearer\\s+(?!\\[REDAKSI\\])\\S+")
    private val KEYVAL_RE = Regex("(?i)(password|passwd|pwd|pass|token|api[-_]?key|apikey|secret|client[-_]?secret)\\s*[\"'=:]+\\s*(?!\\[REDAKSI\\])\\S+(?: \\S+){0,3}")
    private val PREFIXED_RE = Regex("(sk-(live|test|proj|ant)-[A-Za-z0-9]{16,}|ghp_[A-Za-z0-9]{20,}|gho_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{35}|AKIA[0-9A-Z]{16})")
    private val APP_LINE_RE = Regex("(?m)^[a-z]{4}( [a-z]{4}){3}$")

    fun redact(s: String): String {
        if (s.isEmpty()) return s
        var out = PEM_RE.replace(s, "[REDAKSI: kunci privat PEM]")
        out = APP_LABELED_RE.replace(out, "[REDAKSI: app-password, 16 karakter]")
        out = BEARER_RE.replace(out, "Bearer [REDAKSI: bearer token]")
        out = KEYVAL_RE.replace(out) { m -> m.groupValues[1] + "=[REDAKSI: " + m.groupValues[1].lowercase() + "]" }
        out = PREFIXED_RE.replace(out, "[REDAKSI: api-key berprefiks]")
        out = APP_LINE_RE.replace(out, "[REDAKSI: app-password, 16 karakter]")
        return out
    }

    fun containsSecret(s: String): Boolean = redact(s) != s

    fun countMatches(s: String): Int {
        val before = Regex("\\[REDAKSI").findAll(s).count()
        val after = Regex("\\[REDAKSI").findAll(redact(s)).count()
        return (after - before).coerceAtLeast(0)
    }

    /** Self-test: hanya label + PASS/FAIL, TIDAK PERNAH mencetak input/output. */
    fun selfTest(): List<String> {
        val cases = ArrayList<Triple<String, String, Boolean>>()
        cases.add(Triple("PEM block", "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBg==\n-----END PRIVATE KEY-----", true))
        cases.add(Triple("Bearer", "Authorization: Bearer abcdef123456", true))
        cases.add(Triple("password=", "password=rahasia123", true))
        cases.add(Triple("token:", "token: abcdef123456", true))
        cases.add(Triple("app password berlabel", "App Password: abcd efgh ijkl mnop", true))
        cases.add(Triple("app password satu baris", "abcd efgh ijkl mnop", true))
        cases.add(Triple("api key sk-", "key sk-live-abcdefghij1234567890", true))
        cases.add(Triple("api key ghp_", "ghp_abcdefghij1234567890abcdefghij1234", true))
        cases.add(Triple("MD5 utuh", "md5 d41d8cd98f00b204e9800998ecf8427e", false))
        cases.add(Triple("SHA-256 utuh", "sha256 e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177", false))
        cases.add(Triple("prose token listrik", "token listrik bulan ini sudah dibayar", false))
        cases.add(Triple("kalimat 4 kata biasa", "kemarin saya suka baca buku di rumah", false))
        cases.add(Triple("kata password saja", "jangan lupa passwordnya ya", false))
        cases.add(Triple("passwd=", "passwd=palsu123", true))
        cases.add(Triple("pwd=", "pwd=palsu123", true))
        cases.add(Triple("pass=", "pass=palsu123", true))
        cases.add(Triple("secret=", "secret=palsu123", true))
        cases.add(Triple("api-key=", "api-key=palsu123", true))
        cases.add(Triple("apikey=", "apikey=palsu123", true))
        cases.add(Triple("client-secret=", "client-secret=palsu123", true))
        cases.add(Triple("api key gho_", "gho_abcdefghij1234567890abcdefghij1234", true))
        cases.add(Triple("api key github_pat_", "github_pat_abcdefghij1234567890abcd", true))
        cases.add(Triple("api key xoxb-", "xoxb-1234567890ab", true))
        cases.add(Triple("api key xoxp-", "xoxp-1234567890ab", true))
        cases.add(Triple("api key xoxa-", "xoxa-1234567890ab", true))
        cases.add(Triple("api key xoxr-", "xoxr-1234567890ab", true))
        cases.add(Triple("api key xoxs-", "xoxs-1234567890ab", true))
        cases.add(Triple("api key AIza", "AIza0123456789abcdefghijklmnopqrstuvwxy", true))
        cases.add(Triple("api key AKIA", "AKIAABCDEFGHIJKLMNOP", true))
        cases.add(Triple("api key sk-test-", "sk-test-abcdefghij1234567890", true))
        cases.add(Triple("api key sk-proj-", "sk-proj-abcdefghij1234567890", true))
        cases.add(Triple("api key sk-ant-", "sk-ant-abcdefghij1234567890", true))
        val out = ArrayList<String>()
        for ((label, input, expect) in cases) {
            val got = redact(input) != input
            out.add("$label: " + if (got == expect) "PASS" else "FAIL")
        }
        val sample = "password=rahasia123 dan Bearer abcdef123456"
        val once = redact(sample)
        val twice = redact(once)
        out.add("idempotence: " + if (once == twice) "PASS" else "FAIL")
        return out
    }
}
