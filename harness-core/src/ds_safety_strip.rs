//! Port 1:1 dari `DsSafetyStrip.kt` (vc270) — hapus blok tag safety
//! internal DeepSeek (`<ds_safety>...</ds_safety>`) dari teks jawaban.
//!
//! Pemetaan regex Java -> crate `regex`:
//! - `(?i)` + `DOT_MATCHES_ALL` -> `(?is)`; TAPI `\b`/`\s`/`\w`
//!   Java ber-semantik ASCII (tanpa `UNICODE_CHARACTER_CLASS`),
//!   sementara crate `regex` Unicode-aware: `\w` Rust akan ikut
//!   memakan huruf non-ASCII (mis. "café" sesudah "Safety:") dan
//!   `\b` Rust menolak batas sebelum huruf non-ASCII — keduanya
//!   menyimpang dari Kotlin. Karena itu dipakai kelas eksplisit:
//!   `\s` -> `[ \t\n\x0B\f\r]`, `\w` -> `[A-Za-z0-9_]`, dan `\b`
//!   sesudah `ds_safety` -> `(?:>|[^A-Za-z0-9_][^>]*>)` (karakter
//!   berikut TIDAK BOLEH huruf/angka/underscore ASCII — ekuivalen
//!   dengan batas kata Java di posisi itu; `regex` tidak mendukung
//!   lookahead, jadi batasnya dikonsumsi sebagai bagian match).
//!   CATATAN JEBAKAN: flag `(?-u)` (mode ASCII) TIDAK BISA dipakai
//!   di sini — crate `regex` menolak pola itu ("pattern can match
//!   invalid UTF-8") karena `.` mode-byte bisa membelah karakter
//!   multi-byte; kompilasi gagal dan `Option<Regex>` jatuh ke None.
//! - `trim()` Kotlin (tanpa argumen) = `trim { it.isWhitespace() }`
//!   (Kotlin `Char.isWhitespace()` = Java `Character.isWhitespace`).
//!   TERBUKTI via probe JVM (kotlinc 1.9.20 + JDK 21): NBSP U+00A0 dan
//!   EM SPACE U+2003 di tepi DIBUANG trim() Kotlin (klaim awal "<= 0x20"
//!   SALAH — koreksi integrator 16 Nov 2026). `str::trim()` Rust
//!   (Unicode White_Space, tanpa U+00A0/U+001C-U+001F) juga menyimpang,
//!   jadi direplika eksplisit dengan himpunan Java di `kotlin_trim`.
//! - `Regex.replace` Java = `replace_all` Rust (non-overlapping,
//!   kiri-ke-kanan); keempat pola tak bisa match string kosong, jadi
//!   tidak ada perbedaan empty-match.
//! - Regex dikompilasi sekali via `OnceLock<Option<Regex>>`: bila
//!   kompilasi gagal (mustahil untuk literal yang lolos tes — tapi
//!   kode dilarang `unwrap`/`expect`), `strip` degradasi aman ke
//!   "tanpa stripping + trim" alih-alih panic.

#![forbid(unsafe_code)]

use regex::Regex;
use std::sync::OnceLock;

fn block_re() -> &'static Option<Regex> {
    static R: OnceLock<Option<Regex>> = OnceLock::new();
    R.get_or_init(|| {
        Regex::new(r"(?is)<ds_safety(?:>|[^A-Za-z0-9_][^>]*>).*?</ds_safety>([ \t\n\x0B\f\r]*Safety:[ \t\n\x0B\f\r]*[A-Za-z0-9_]+)?").ok()
    })
}

fn close_stray_re() -> &'static Option<Regex> {
    static R: OnceLock<Option<Regex>> = OnceLock::new();
    R.get_or_init(|| {
        Regex::new(r"(?i)</ds_safety>([ \t\n\x0B\f\r]*Safety:[ \t\n\x0B\f\r]*[A-Za-z0-9_]+)?").ok()
    })
}

fn open_tag_re() -> &'static Option<Regex> {
    static R: OnceLock<Option<Regex>> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"(?i)<ds_safety(?:>|[^A-Za-z0-9_][^>]*>)").ok())
}

fn blank_run_re() -> &'static Option<Regex> {
    static R: OnceLock<Option<Regex>> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"\n{3,}").ok())
}

/// Himpunan Java `Character.isWhitespace` (dipakai Kotlin
/// `Char.isWhitespace()` = predikat `String.trim()` tanpa argumen):
/// 0x09-0x0D, 0x20, 0x1C-0x1F, 0x85, 0xA0, 0x1680, 0x2000-0x200A,
/// 0x2028, 0x2029, 0x202F, 0x205F, 0x3000.
pub(crate) fn java_is_whitespace(c: char) -> bool {
    matches!(c as u32,
        0x09..=0x0D | 0x20 | 0x1C..=0x1F | 0x85 | 0xA0 | 0x1680
        | 0x2000..=0x200A | 0x2028 | 0x2029 | 0x202F | 0x205F | 0x3000)
}

/// `String.trim()` Kotlin (tanpa argumen): buang char awal/akhir yang
/// `isWhitespace()` (Java). BUKAN `<= 0x20`, BUKAN `str::trim()` Rust.
pub(crate) fn kotlin_trim(s: &str) -> &str {
    s.trim_matches(java_is_whitespace)
}

pub fn strip(text: &str) -> String {
    let mut t = text.to_string();
    if let Some(re) = block_re() {
        t = re.replace_all(&t, "").into_owned();
    }
    if let Some(re) = close_stray_re() {
        t = re.replace_all(&t, "").into_owned();
    }
    if let Some(re) = open_tag_re() {
        if let Some(m) = re.find(&t) {
            t.truncate(m.start());
        }
    }
    if let Some(re) = blank_run_re() {
        t = re.replace_all(&t, "\n\n").into_owned();
    }
    kotlin_trim(&t).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Contoh-contoh ini meniru pemakaian nyata di tree
    /// (`DsApiClient.kt:536`, `SlotClient.kt:441` — strip jawaban
    /// + thinking) dan kasus desain `DESIGN-STRIP-DS-SAFETY.md`.
    #[test]
    fn strip_plain_text_unchanged() {
        assert_eq!(strip("Jawaban biasa tanpa tag."), "Jawaban biasa tanpa tag.");
        assert_eq!(strip(""), "");
        assert_eq!(strip("   "), "");
    }

    #[test]
    fn strip_full_block() {
        // Instance lapangan 2026-11-08: tag + verdict Mandarin bocor.
        assert_eq!(
            strip("Jawaban <ds_safety>[分类]色情 Safety: safe</ds_safety> lanjut"),
            "Jawaban  lanjut"
        );
        // Tag + "Safety: safe" DI LUAR tag (pola lapangan kedua).
        assert_eq!(
            strip("Jawaban <ds_safety>[分类]色情</ds_safety>Safety: safe lanjut"),
            "Jawaban  lanjut"
        );
        assert_eq!(
            strip("<ds_safety>xxx</ds_safety> Jawaban"),
            "Jawaban"
        );
        // Multiline di dalam blok (DOT_MATCHES_ALL) + atribut di tag.
        assert_eq!(
            strip("A <ds_safety foo=\"bar\">\nbaris1\nbaris2\n</ds_safety> B"),
            "A  B"
        );
        // Case-insensitive.
        assert_eq!(strip("A <DS_SAFETY>x</DS_SAFETY> B"), "A  B");
        assert_eq!(strip("A <Ds_Safety>x</Ds_Safety> B"), "A  B");
    }

    #[test]
    fn strip_stray_close_and_unterminated_open() {
        assert_eq!(strip("teks </ds_safety> lanjut"), "teks  lanjut");
        assert_eq!(strip("teks </ds_safety>Safety: safe lanjut"), "teks  lanjut");
        // Tag buka tanpa tutup -> semua sesudah tag dibuang.
        assert_eq!(strip("awal <ds_safety foo=bar> sisa hilang"), "awal");
        assert_eq!(strip("awal <ds_safety> sisa hilang"), "awal");
    }

    #[test]
    fn strip_blank_runs_collapsed_and_trimmed() {
        assert_eq!(strip("a\n\n\n\nb"), "a\n\nb");
        assert_eq!(strip("a\n\n\nb\n\n\n\n\nc"), "a\n\nb\n\nc");
        // Dua newline dipertahankan (bukan dipadatkan jadi satu).
        assert_eq!(strip("a\n\nb"), "a\n\nb");
        assert_eq!(strip("\n\n  teks  \n\n"), "teks");
    }

    #[test]
    fn strip_does_not_touch_mentions_without_tags() {
        // Sebutan "ds_safety" tanpa kurung sudut + "Safety: safe"
        // tanpa tag utuh TIDAK dihapus (dok file Kotlin).
        assert_eq!(strip("ds_safety disebut di sini"), "ds_safety disebut di sini");
        assert_eq!(strip("Safety: safe tanpa tag"), "Safety: safe tanpa tag");
    }

    #[test]
    fn strip_idempotent() {
        let cases = [
            "Jawaban <ds_safety>[分类]色情</ds_safety>Safety: safe lanjut",
            "teks </ds_safety> lanjut",
            "awal <ds_safety> sisa hilang",
            "a\n\n\n\nb",
            "Jawaban biasa",
        ];
        for c in cases {
            assert_eq!(strip(&strip(c)), strip(c), "idempotensi gagal: {c:?}");
        }
    }

    #[test]
    fn strip_ascii_word_class_matches_java() {
        // `[A-Za-z0-9_]` (ASCII, seperti `\w` Java): "Safety: café" ->
        // "caf" termakan, "é" tersisa — SAMA seperti Kotlin. (`\w`
        // Unicode Rust akan memakan "café" utuh dan menyimpang.)
        assert_eq!(
            strip("x <ds_safety>t</ds_safety> Safety: café y"),
            "x é y"
        );
        // Batas kata Java: `<ds_safetyfoo>` BUKAN tag (huruf ASCII sesudah
        // nama tag) — tidak disentuh sama sekali (literal `</ds_safety>`
        // butuh `>` tepat sesudah `y`, jadi close-stray juga tidak match).
        assert_eq!(strip("A <ds_safetyfoo>x</ds_safetyfoo> B"), "A <ds_safetyfoo>x</ds_safetyfoo> B");
        // `<ds_safetyé>` ADALAH tag bagi Java ('é' bukan word-char ASCII
        // -> batas kata ada -> tag buka match -> semua sesudahnya dibuang).
        // Perhatian: Python `re` memberi hasil beda di sini (\b Unicode),
        // jadi kasus ini TIDAK bisa dicek silang via Python — acuannya
        // semantik `java.util.regex` di atas.
        assert_eq!(strip("A <ds_safetyé>x</ds_safetyé> B"), "A");
    }

    #[test]
    fn kotlin_trim_matches_kotlin_is_whitespace() {
        // Koreksi integrator (probe JVM kotlinc 1.9.20 + JDK 21, 16 Nov
        // 2026): trim() Kotlin tanpa argumen == `trim { it.isWhitespace() }`
        // (Java Character.isWhitespace), BUKAN `<= 0x20` seperti klaim
        // awal PATCH-S4 §4.3. NBSP (U+00A0) dan EM SPACE (U+2003) di tepi
        // DIBUANG trim() Kotlin; `str::trim()` Rust (Unicode White_Space,
        // tanpa U+00A0/U+001C-U+001F) juga menyimpang.
        assert_eq!(kotlin_trim("\u{a0}teks\u{a0}"), "teks");
        assert_eq!(kotlin_trim("\u{2003}teks\u{2003}"), "teks");
        assert_eq!(kotlin_trim("\u{1c}teks\u{1c}"), "teks");
        assert_eq!(kotlin_trim("  \t\nteks\r\n "), "teks");
        assert_eq!(kotlin_trim(""), "");
        // NBSP di TENGAH dipertahankan (trim hanya tepi).
        assert_eq!(kotlin_trim("a\u{a0}b"), "a\u{a0}b");
        // strip() ujung-ke-ujung: NBSP tepi ikut dibuang trim() Kotlin.
        assert_eq!(strip("\u{a0}teks\u{a0}"), "teks");
    }
}
