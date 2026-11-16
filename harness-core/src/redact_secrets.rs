//! Port 1:1 dari `RedactSecrets.kt` (vc316 — redaksi kredensial).
//!
//! Perilaku HARUS identik dengan Kotlin, termasuk kekhasan aslinya:
//! - 6 regex, diterapkan BERURUTAN: PEM, APP_LABELED, BEARER, KEYVAL,
//!   PREFIXED, APP_LINE.
//! - Guard lookahead Kotlin `(?!\[REDAKSI\])` di BEARER/KEYVAL memeriksa
//!   literal `"[REDAKSI]"` (tutup kurung) — sementara SEMUA marker yang
//!   dihasilkan berbentuk `"[REDAKSI: ...]"` (titik dua). Akibatnya guard
//!   itu praktis tidak pernah memblokir marker asli; yang menjaga
//!   idempotence pada sampel self-test adalah (a) replacement KEYVAL yang
//!   berupa titik-tetap untuk nilai 2-token, dan (b) `(?: \S+){0,3}` yang
//!   ikut menelan kata-kata berikut. Port ini MEREPRODUKSI perilaku itu
//!   apa adanya — termasuk non-idempotence Kotlin pada teks yang SUDAH
//!   ter-redaksi (mis. `"Bearer [REDAKSI: bearer token]"` di-redact lagi
//!   menjadi `"Bearer [REDAKSI: bearer token] Bearer [REDAKSI: bearer
//!   token]"`, karena BEARER `(?i)` ikut me-match kata "bearer" DI DALAM
//!   marker). Itu BUKAN deviasi port; dibuktikan model independen
//!   (Python `re`) + tes di bawah. JANGAN "memperbaiki" perilaku ini
//!   diam-diam — perubahan perilaku adalah keputusan koordinator.
//!
//! ## Lookahead: opsi (b) restrukturisasi — DIPILIH (lihat PATCH-S3.md)
//! Crate `regex` TIDAK mendukung negative lookahead. Dua opsi diuji
//! (`/tmp/s3exp`, 16 Nov 2026):
//! - (a) `fancy-regex` 0.17 (lookahead 1:1): lolos semua kasus, TAPI
//!   error `BacktrackLimitExceeded` pada teks biasa 273 KB di limit
//!   default 1.000.000, dan ~20–160x lebih lambat dari (b) bahkan di
//!   limit 100.000.000 (1906 ms vs 87 ms; PEM-terbuka 100 KB: 362 ms
//!   vs 73 ms). Backtracking + teks dump besar = risiko nyata.
//! - (b) restrukturisasi: pola lookahead DIHAPUS, nilai yang cocok
//!   diperiksa manual sesudah match (`starts_with("[REDAKSI]")` —
//!   persis predikat lookahead Kotlin), match dibiarkan utuh bila
//!   cocok. Separator KEYVAL dibuat LAZY (`["'=:]+?`) supaya split
//!   separator yang ditemukan sama dengan yang ditemukan backtracking
//!   Java (replacement hanya memakai group1, jadi ini cukup).
//!   Hasil: 32/32 self-test, 38/38 kasus ekstra, 5/5 kasus
//!   diskriminator — IDENTIK dengan (a) dan dengan model Python
//!   (`re`, semantik backtracking Java). Tanpa dependensi baru selain
//!   crate `regex` (yang memang sudah diantisipasi peta migrasi).
//!
//! ## Penyimpangan yang disengaja vs Kotlin (terdokumentasi)
//! - `\s` / `\S` Kotlin (`java.util.regex`) = whitespace ASCII saja
//!   (`[\t\n\x0B\f\r ]`). Di sini ditulis eksplisit sebagai kelas
//!   ASCII (`[\t\n\v\f\r ]` / `[^\t\n\v\f\r ]`) karena `\s` Rust =
//!   Unicode (beda pada NBSP dkk — dibuktikan tes E18/E19).
//! - `(?i)` Rust memakai Unicode case folding (superset dari `(?i)`
//!   Java yang ASCII-only): `[a-z]` ikut cocok `ſ`/`K` dkk. Praktis
//!   tidak ada dampak (kata kunci ASCII); dicatat, bukan diperbaiki.
//! - APP_LINE: `(?m)$` Java ikut berhenti sebelum `\r` (pemisah baris
//!   `\r` tunggal); `$` Rust hanya sebelum `\n`. Pola memakai `\r?$`
//!   + closure yang mempertahankan `\r`, jadi teks CRLF (`\r\n`)
//!   berperilaku sama; hanya `\r` TUNGGAL sebagai pemisah baris yang
//!   tidak direplikasi (amat jarang di teks Android yang memakai `\n`).
//! - Tidak ada `unwrap()`/`expect()` di jalur produksi. Regex
//!   dikompilasi sekali via `OnceLock<Option<Regex>>`; bila kompilasi
//!   gagal (mustahil untuk pola literal yang sudah dites — tapi
//!   ditangani), langkah itu dilewati tanpa panic.

#![forbid(unsafe_code)]

use regex::Regex;
use std::sync::OnceLock;

/// Whitespace ASCII (persis `\s` Java): spasi, tab, LF, VT, FF, CR.
const WS: &str = r"[\t\n\v\f\r ]";
/// Bukan-whitespace ASCII (persis `\S` Java).
const NW: &str = r"[^\t\n\v\f\r ]";

/// Marker yang dicari guard lookahead Kotlin — literal `"[REDAKSI]"`
/// (tutup kurung). Perhatikan: marker ASLI berbentuk `"[REDAKSI: ...]"`
/// sehingga predikat ini hampir tidak pernah benar (lihat header modul).
const REDACTED_MARKER: &str = "[REDAKSI]";

fn pem_re() -> Option<&'static Regex> {
    static RE: OnceLock<Option<Regex>> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r"(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----")
            .ok()
    })
    .as_ref()
}

fn app_labeled_re() -> Option<&'static Regex> {
    static RE: OnceLock<Option<Regex>> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(&format!(
            r"(?i)(app[- ]?password|kata sandi aplikasi|sandi aplikasi){WS}*[:=]?{WS}*[a-z]{{4}}( [a-z]{{4}}){{3}}"
        ))
        .ok()
    })
    .as_ref()
}

fn bearer_re() -> Option<&'static Regex> {
    static RE: OnceLock<Option<Regex>> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(&format!(r"(?i)Bearer{WS}+({NW}+)")).ok()
    })
    .as_ref()
}

fn keyval_re() -> Option<&'static Regex> {
    static RE: OnceLock<Option<Regex>> = OnceLock::new();
    RE.get_or_init(|| {
        // Separator LAZY (`+?`) — lihat header modul (keputusan lookahead).
        Regex::new(&format!(
            r#"(?i)(password|passwd|pwd|pass|token|api[-_]?key|apikey|secret|client[-_]?secret){WS}*["'=:]+?{WS}*({NW}+(?: {NW}+){{0,3}})"#
        ))
        .ok()
    })
    .as_ref()
}

fn prefixed_re() -> Option<&'static Regex> {
    static RE: OnceLock<Option<Regex>> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r"(sk-(live|test|proj|ant)-[A-Za-z0-9]{16,}|ghp_[A-Za-z0-9]{20,}|gho_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{35}|AKIA[0-9A-Z]{16})")
            .ok()
    })
    .as_ref()
}

fn app_line_re() -> Option<&'static Regex> {
    static RE: OnceLock<Option<Regex>> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"(?m)^[a-z]{4}( [a-z]{4}){3}\r?$").ok())
    .as_ref()
}

/// Redaksi kredensial — port 1:1 `RedactSecrets.redact` (Kotlin).
/// Urutan langkah dipertahankan persis; string kosong dikembalikan utuh.
pub fn redact(s: &str) -> String {
    if s.is_empty() {
        return s.to_string();
    }
    let mut out = s.to_string();
    // 1. Blok PEM (DOTALL, non-greedy).
    if let Some(re) = pem_re() {
        out = re
            .replace_all(&out, "[REDAKSI: kunci privat PEM]")
            .into_owned();
    }
    // 2. App password berlabel.
    if let Some(re) = app_labeled_re() {
        out = re
            .replace_all(&out, "[REDAKSI: app-password, 16 karakter]")
            .into_owned();
    }
    // 3. Bearer — guard lookahead Kotlin diganti cek manual: nilai yang
    //    diawali "[REDAKSI]" dibiarkan utuh.
    if let Some(re) = bearer_re() {
        out = re
            .replace_all(&out, |caps: &regex::Captures| {
                let token = match caps.get(1) {
                    Some(m) => m.as_str(),
                    None => "",
                };
                if token.starts_with(REDACTED_MARKER) {
                    match caps.get(0) {
                        Some(m) => m.as_str().to_string(),
                        None => String::new(),
                    }
                } else {
                    "Bearer [REDAKSI: bearer token]".to_string()
                }
            })
            .into_owned();
    }
    // 4. Key=value — guard yang sama; label dipertahankan apa adanya,
    //    label di dalam marker di-lowercase (persis Kotlin).
    if let Some(re) = keyval_re() {
        out = re
            .replace_all(&out, |caps: &regex::Captures| {
                let key = match caps.get(1) {
                    Some(m) => m.as_str(),
                    None => "",
                };
                let value = match caps.get(2) {
                    Some(m) => m.as_str(),
                    None => "",
                };
                if value.starts_with(REDACTED_MARKER) {
                    match caps.get(0) {
                        Some(m) => m.as_str().to_string(),
                        None => String::new(),
                    }
                } else {
                    format!("{}=[REDAKSI: {}]", key, key.to_lowercase())
                }
            })
            .into_owned();
    }
    // 5. API key berprefiks.
    if let Some(re) = prefixed_re() {
        out = re
            .replace_all(&out, "[REDAKSI: api-key berprefiks]")
            .into_owned();
    }
    // 6. App password satu baris utuh. `\r?$` menelan `\r` CRLF —
    //    kembalikan `\r`-nya supaya baris berikut tetap CRLF.
    if let Some(re) = app_line_re() {
        out = re
            .replace_all(&out, |caps: &regex::Captures| {
                let m = match caps.get(0) {
                    Some(m) => m.as_str(),
                    None => "",
                };
                if m.ends_with('\r') {
                    "[REDAKSI: app-password, 16 karakter]\r".to_string()
                } else {
                    "[REDAKSI: app-password, 16 karakter]".to_string()
                }
            })
            .into_owned();
    }
    out
}

/// Port `containsSecret`: ada yang berubah saat di-redact?
pub fn contains_secret(s: &str) -> bool {
    redact(s) != s
}

/// Port `countMatches`: selisih jumlah marker `"[REDAKSI"` sebelum dan
/// sesudah redact, clamp >= 0 (persis Kotlin).
pub fn count_matches(s: &str) -> usize {
    let before = s.matches("[REDAKSI").count();
    let after = redact(s).matches("[REDAKSI").count();
    after.saturating_sub(before)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 32 kasus self-test Kotlin 1:1 (label, input, expect_changed).
    /// Kotlin membandingkan `redact(input) != input` dengan expect.
    const SELF_TEST: &[(&str, &str, bool)] = &[
        ("PEM block", "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBg==\n-----END PRIVATE KEY-----", true),
        ("Bearer", "Authorization: Bearer abcdef123456", true),
        ("password=", "password=rahasia123", true),
        ("token:", "token: abcdef123456", true),
        ("app password berlabel", "App Password: abcd efgh ijkl mnop", true),
        ("app password satu baris", "abcd efgh ijkl mnop", true),
        ("api key sk-", "key sk-live-abcdefghij1234567890", true),
        ("api key ghp_", "ghp_abcdefghij1234567890abcdefghij1234", true),
        ("MD5 utuh", "md5 d41d8cd98f00b204e9800998ecf8427e", false),
        ("SHA-256 utuh", "sha256 e738145b516a07e9572e9ca7268c813edb892737b402873246efa49a64951177", false),
        ("prose token listrik", "token listrik bulan ini sudah dibayar", false),
        ("kalimat 4 kata biasa", "kemarin saya suka baca buku di rumah", false),
        ("kata password saja", "jangan lupa passwordnya ya", false),
        ("passwd=", "passwd=palsu123", true),
        ("pwd=", "pwd=palsu123", true),
        ("pass=", "pass=palsu123", true),
        ("secret=", "secret=palsu123", true),
        ("api-key=", "api-key=palsu123", true),
        ("apikey=", "apikey=palsu123", true),
        ("client-secret=", "client-secret=palsu123", true),
        ("api key gho_", "gho_abcdefghij1234567890abcdefghij1234", true),
        ("api key github_pat_", "github_pat_abcdefghij1234567890abcd", true),
        ("api key xoxb-", "xoxb-1234567890ab", true),
        ("api key xoxp-", "xoxp-1234567890ab", true),
        ("api key xoxa-", "xoxa-1234567890ab", true),
        ("api key xoxr-", "xoxr-1234567890ab", true),
        ("api key xoxs-", "xoxs-1234567890ab", true),
        ("api key AIza", "AIza0123456789abcdefghijklmnopqrstuvwxy", true),
        ("api key AKIA", "AKIAABCDEFGHIJKLMNOP", true),
        ("api key sk-test-", "sk-test-abcdefghij1234567890", true),
        ("api key sk-proj-", "sk-proj-abcdefghij1234567890", true),
        ("api key sk-ant-", "sk-ant-abcdefghij1234567890", true),
    ];

    #[test]
    fn all_patterns_compile() {
        assert!(pem_re().is_some());
        assert!(app_labeled_re().is_some());
        assert!(bearer_re().is_some());
        assert!(keyval_re().is_some());
        assert!(prefixed_re().is_some());
        assert!(app_line_re().is_some());
    }

    #[test]
    fn self_test_32_cases() {
        assert_eq!(SELF_TEST.len(), 32);
        for (label, input, expect) in SELF_TEST {
            let got = redact(input) != *input;
            assert_eq!(got, *expect, "self-test case failed: {label}");
        }
    }

    #[test]
    fn self_test_idempotence_sample() {
        // Persis sampel idempotence Kotlin.
        let sample = "password=rahasia123 dan Bearer abcdef123456";
        let once = redact(sample);
        let twice = redact(&once);
        assert_eq!(once, twice);
        // Sekali jalan: KEYVAL menelan "dan Bearer [REDAKSI:" (3 kata
        // ekstra) sehingga hasil akhirnya titik-tetap.
        assert_eq!(once, "password=[REDAKSI: password] bearer token]");
    }

    #[test]
    fn empty_and_no_secret() {
        assert_eq!(redact(""), "");
        assert_eq!(redact("halo dunia, tidak ada rahasia"), "halo dunia, tidak ada rahasia");
        assert!(!contains_secret("halo dunia"));
        assert!(contains_secret("password=rahasia123"));
        assert_eq!(count_matches("halo dunia"), 0);
    }

    /// Kasus ekstra — expected = perilaku Kotlin, diverifikasi model
    /// independen (Python `re`, mesin backtracking yang sama semantiknya
    /// dengan `java.util.regex`). Lihat PATCH-S3.md § bukti lookahead.
    const EXTRA: &[(&str, &str, &str)] = &[
        // E1: backtracking separator — Kotlin menemukan split `=` +
        // nilai `"[REDAKSI: ...]`; varian restrukturisasi greedy GAGAL
        // di sini, separator LAZY yang dipakai modul ini lolos.
        ("E1", "password=\"[REDAKSI: password]\"", "password=[REDAKSI: password]"),
        ("E2", "password= \"[REDAKSI: password]\"", "password=[REDAKSI: password]"),
        // E3/E4/E6/E17: BEARER (?i) ikut me-match kata "bearer" DI DALAM
        // marker — perilaku asli Kotlin, bukan deviasi port.
        ("E3", "token: [REDAKSI: bearer token]", "token=[REDAKSI: token] token]"),
        ("E4", "Bearer [REDAKSI: bearer token]", "Bearer [REDAKSI: bearer token] Bearer [REDAKSI: bearer token]"),
        ("E5", "password=[REDAKSI: password]", "password=[REDAKSI: password]"),
        ("E6", "password=[REDAKSI: bearer token]", "password=[REDAKSI: password] token]"),
        ("E7", "Authorization: Bearer abcdef123456 dan password=rahasia123", "Authorization: Bearer [REDAKSI: bearer token] dan password=[REDAKSI: password]"),
        ("E8", "BEARER ABCDEF123", "Bearer [REDAKSI: bearer token]"),
        ("E9", "PASSWORD=Secret123", "PASSWORD=[REDAKSI: password]"),
        // E10: (?i)[a-z] ikut cocok huruf kapital (Kotlin maupun Rust).
        ("E10", "App Password: ABCD EFGH IJKL MNOP", "[REDAKSI: app-password, 16 karakter]"),
        // E11: CRLF — \r dipertahankan.
        ("E11", "abcd efgh ijkl mnop\r\nbaris lain", "[REDAKSI: app-password, 16 karakter]\r\nbaris lain"),
        ("E12", "kata sandi aplikasi abcd efgh ijkl mnop", "[REDAKSI: app-password, 16 karakter]"),
        ("E13", "api-key=rahasia saya yang panjang sekali kata", "api-key=[REDAKSI: api-key] sekali kata"),
        ("E14", "secret=abc, def", "secret=[REDAKSI: secret]"),
        ("E15", "", ""),
        ("E16", "token listrik bulan ini sudah dibayar", "token listrik bulan ini sudah dibayar"),
        ("E17", "Bearer abcdef [REDAKSI: bearer token]", "Bearer [REDAKSI: bearer token] [REDAKSI: Bearer [REDAKSI: bearer token]"),
        // E18/E19: \s ASCII (NBSP bukan whitespace Java) — alasan kelas
        // whitespace ditulis eksplisit, bukan \s Unicode Rust.
        ("E18", "password\u{a0}=secret", "password\u{a0}=secret"),
        ("E19", "password=rahasia\u{a0}dan", "password=[REDAKSI: password]"),
        ("E20", "Password: Secret123", "Password=[REDAKSI: password]"),
        ("E21", "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBg==\n-----END PRIVATE KEY-----", "[REDAKSI: kunci privat PEM]"),
        ("E22", "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----", "[REDAKSI: kunci privat PEM]"),
        ("E23", "sk-ant-abcdefghij1234567890", "[REDAKSI: api-key berprefiks]"),
        ("E24", "xoxb-1234567890ab", "[REDAKSI: api-key berprefiks]"),
        ("E25", "github_pat_abcdefghij1234567890abcd", "[REDAKSI: api-key berprefiks]"),
        ("E26", "AIza0123456789abcdefghijklmnopqrstuvwxy", "[REDAKSI: api-key berprefiks]"),
        ("E27", "AKIAABCDEFGHIJKLMNOP", "[REDAKSI: api-key berprefiks]"),
        ("E28", "password = rahasia123", "password=[REDAKSI: password]"),
        ("E29", "token:abc def ghi jkl mno pqr", "token=[REDAKSI: token] mno pqr"),
        ("E30", "[REDAKSI: app-password, 16 karakter]", "[REDAKSI: app-password, 16 karakter]"),
        ("E31", "laporan [REDAKSI] selesai", "laporan [REDAKSI] selesai"),
        ("E32", "Bearer", "Bearer"),
        ("E33", "Bearer ", "Bearer "),
        ("E34", "pass=ok", "pass=[REDAKSI: pass]"),
        ("E35", "client_secret=abc", "client_secret=[REDAKSI: client_secret]"),
        ("E36", "API-KEY=abc", "API-KEY=[REDAKSI: api-key]"),
        ("E37", "token: [REDAKSI: password]", "token=[REDAKSI: token]"),
        ("E38", "sandi aplikasi abcd efgh ijkl mnop", "[REDAKSI: app-password, 16 karakter]"),
    ];

    #[test]
    fn extra_38_cases_match_kotlin() {
        assert_eq!(EXTRA.len(), 38);
        for (label, input, expect) in EXTRA {
            let got = redact(input);
            assert_eq!(got, *expect, "extra case failed: {label}");
        }
    }

    /// Diskriminator lookahead F1–F5: satu-satunya bentuk di mana guard
    /// Kotlin benar-benar memblokir — nilai `"[REDAKSI]"` POLOS (tutup
    /// kurung, tanpa ": ..."). Varian restrukturisasi greedy gagal F1;
    /// separator LAZY yang dipakai modul ini lolos 5/5 (cocok
    /// fancy-regex + model Python).
    const DISC: &[(&str, &str, &str)] = &[
        ("F1", "password=\"[REDAKSI]\"", "password=[REDAKSI: password]"),
        ("F2", "Bearer [REDAKSI]", "Bearer [REDAKSI]"),
        ("F3", "password=[REDAKSI]", "password=[REDAKSI]"),
        ("F4", "token: \"[REDAKSI]\"", "token=[REDAKSI: token]"),
        ("F5", "password=\"[REDAKSI: x]\"", "password=[REDAKSI: password]"),
    ];

    #[test]
    fn discriminator_lookahead_f1_f5() {
        assert_eq!(DISC.len(), 5);
        for (label, input, expect) in DISC {
            let got = redact(input);
            assert_eq!(got, *expect, "discriminator failed: {label}");
        }
    }

    #[test]
    fn count_matches_api() {
        assert_eq!(count_matches("password=rahasia123"), 1);
        // Sampel gabungan: HANYA 1 marker baru — KEYVAL menelan
        // "dan Bearer [REDAKSI:" sebagai 3 kata nilai ekstra, jadi
        // marker Bearer ikut termakan (perilaku asli Kotlin juga).
        assert_eq!(count_matches("password=rahasia123 dan Bearer abcdef123456"), 1);
        // Bearer dulu, password di akhir: kedua marker selamat.
        assert_eq!(count_matches("Bearer abcdef123456 dan password=rahasia123"), 2);
        assert_eq!(count_matches("tidak ada apa-apa"), 0);
        // Marker yang sudah ada sebelumnya tidak dihitung sebagai baru.
        assert_eq!(count_matches("[REDAKSI: password]"), 0);
        // Marker lama yang berdiri sendiri (bukan kata nilai KEYVAL)
        // tetap tidak dihitung; hanya redaksi baru yang dihitung.
        assert_eq!(
            count_matches("laporan [REDAKSI: password] selesai password=rahasia123"),
            1
        );
    }

    #[test]
    fn contains_secret_api() {
        assert!(contains_secret("token: abcdef123456"));
        assert!(!contains_secret("md5 d41d8cd98f00b204e9800998ecf8427e"));
        assert!(!contains_secret(""));
    }

    /// Non-idempotence ASLI Kotlin pada teks yang sudah ter-redaksi
    /// (BEARER (?i) me-match "bearer" di dalam marker). Port
    /// mereproduksinya dengan setia — tes ini MENGUNCI perilaku itu
    /// supaya tidak "diperbaiki" diam-diam tanpa keputusan koordinator.
    #[test]
    fn kotlin_original_is_not_idempotent_on_pre_redacted_text() {
        let once = redact("Bearer [REDAKSI: bearer token]");
        assert_eq!(
            once,
            "Bearer [REDAKSI: bearer token] Bearer [REDAKSI: bearer token]"
        );
        assert_ne!(redact(&once), once);
        let once = redact("api-key=rahasia saya yang panjang sekali kata");
        assert_eq!(once, "api-key=[REDAKSI: api-key] sekali kata");
        assert_ne!(redact(&once), once);
    }

    #[test]
    fn unicode_emoji_in_value() {
        // Emoji = non-whitespace di Java maupun Rust: ikut jadi nilai.
        assert_eq!(
            redact("password=🔑rahasia123"),
            "password=[REDAKSI: password]"
        );
        assert!(contains_secret("token: 🔑abc"));
    }
}
