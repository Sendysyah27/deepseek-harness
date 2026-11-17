//! Port 1:1 dari fungsi murni `AgentPrompt.kt` yang disebut peta §4 slice 6:
//! `selectCatalog` (+ anggaran 6000/6000, stopwords vc241), `continuationAnchorVc316`,
//! `normalizeDsmlDelimiters`, `stripRawToolMarkupForDisplay` (+ `stripTagCallSpans`),
//! `applyDoublePass`.
//!
//! Sisa AgentPrompt TETAP Kotlin (lihat BUILD-VC320.md §2 — antara lain
//! `toolCatalog`/`catalogForTask` yang butuh Context, parser tool-call
//! berbasis org.json, dan mesin claim-check pendukung).
//!
//! Quirk Kotlin yang direplika (dikunci tes + P6):
//! - `String.trim()` = `kotlin_trim` (Java isWhitespace — NBSP tepi dibuang).
//! - `String.length` / `take(n)` = unit UTF-16 (`utf16_len`/`utf16_take`).
//! - `String.lines()` = pisah `\r\n`/`\n`/`\r` + elemen kosong terakhir bila
//!   string berakhir terminator (`kotlin_lines`; `str::lines` Rust membuangnya).
//! - `String.lowercase()` Unicode = Rust `to_lowercase`.
//! - `pick()`: item pertama boleh melampaui anggaran; `list_tools` dipaksa
//!   masuk bila ada (kandidat pertama urutan ASLI).
//! - Regex `\s` Kotlin (Java) = ASCII SAJA — pola di sini memakai kelas
//!   eksplisit `[ \t\n\x0B\f\r]`, BUKAN `\s` regex crate (Unicode).
//! - `stripTagCallSpans`: lacak kurung + string + escape atas `char` Rust
//!   (setara unit UTF-16 Kotlin untuk `{`/`}`/`"`/`\` ASCII).
//! - `utf16_take` membuang high surrogate yang menggantung di batas potong
//!   (limitasi `String` Rust — sama seperti slice 5; hanya kena bila
//!   karakter ke-500 perintah user persis high surrogate emoji).

#![forbid(unsafe_code)]

use crate::concat_api_client::{is_blank, utf16_len, utf16_take};
use crate::ds_safety_strip::kotlin_trim;
use crate::tool_registry;
use regex::Regex;
use std::collections::HashSet;
use std::sync::OnceLock;

/// Anggaran baris tool di katalog ringkas (vc316).
pub const CATALOG_TOOL_BUDGET_VC316: usize = 6000;
/// Anggaran baris prose di katalog ringkas (vc316).
pub const CATALOG_PROSE_BUDGET_VC316: usize = 6000;

const TASK_STOPWORDS_VC241: &[&str] = &[
    "yang", "dan", "atau", "di", "ke", "dari", "untuk", "dengan", "saya", "aku", "kami",
    "kita", "kamu", "anda", "ini", "itu", "ada", "adalah", "bisa", "saja", "juga", "lagi",
    "apa", "bagaimana", "kenapa", "kapan", "dimana", "siapa", "berapa", "dong", "sih", "ya",
    "tidak", "bukan", "sudah", "belum", "akan", "mau",
];

fn regex_non_alnum() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"[^a-z0-9]+").unwrap())
}

fn regex_non_alnum_us() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"[^a-z0-9_]+").unwrap())
}

fn regex_tool_line() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"^- ([a-z][a-z0-9_]+) \{").unwrap())
}

fn regex_name_brace() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"([a-z][a-z0-9_]*)\s*\{").unwrap())
}

/// `\s` Java (ASCII saja) sebagai kelas eksplisit.
const WS: &str = r"[ \t\n\x0B\f\r]";

fn regex_dsml_calls() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| {
        Regex::new(&format!(
            r"(?s)<{ws}*[|]{{0,2}}{ws}*DSML{ws}*[|]{{0,2}}{ws}*calls{ws}*>.*?</{ws}*(?:[|]{{0,2}}{ws}*DSML{ws}*[|]{{0,2}}{ws}*)?calls{ws}*>",
            ws = WS
        ))
        .unwrap()
    })
}

fn regex_dsml_invoke() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| {
        Regex::new(&format!(
            r"(?s)<{ws}*[|]{{0,2}}{ws}*DSML{ws}*[|]{{0,2}}{ws}*invoke\b[^>]*>.*?</{ws}*(?:[|]{{0,2}}{ws}*DSML{ws}*[|]{{0,2}}{ws}*)?invoke{ws}*>",
            ws = WS
        ))
        .unwrap()
    })
}

fn regex_dsml_parameter() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| {
        Regex::new(&format!(
            r"(?s)<{ws}*[|]{{0,2}}{ws}*DSML{ws}*[|]{{0,2}}{ws}*parameter\b[^>]*>.*?</{ws}*(?:[|]{{0,2}}{ws}*DSML{ws}*[|]{{0,2}}{ws}*)?parameter{ws}*>",
            ws = WS
        ))
        .unwrap()
    })
}

fn regex_dsml_unterminated() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| {
        Regex::new(&format!(
            r"<{ws}*[|]{{0,2}}{ws}*DSML{ws}*[|]{{0,2}}{ws}*(calls|invoke)\b",
            ws = WS
        ))
        .unwrap()
    })
}

fn regex_dsml_stray_tag() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"<[^>]*DSML[^>]*>").unwrap())
}

fn regex_tag_call() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"<tool=[A-Za-z0-9_]+>").unwrap())
}

fn regex_blank_run() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"\n{3,}").unwrap())
}

/// `String.lines()` Kotlin: pisah `\r\n` / `\n` / `\r`; string kosong ->
/// `[""]`; string berakhir terminator -> elemen kosong terakhir dipertahankan.
pub(crate) fn kotlin_lines(s: &str) -> Vec<&str> {
    if s.is_empty() {
        return vec![""];
    }
    let mut out: Vec<&str> = Vec::new();
    let bytes = s.as_bytes();
    let mut start = 0usize;
    let mut i = 0usize;
    while i < bytes.len() {
        if bytes[i] == b'\n' {
            out.push(&s[start..i]);
            i += 1;
            start = i;
        } else if bytes[i] == b'\r' {
            if i + 1 < bytes.len() && bytes[i + 1] == b'\n' {
                out.push(&s[start..i]);
                i += 2;
            } else {
                out.push(&s[start..i]);
                i += 1;
            }
            start = i;
        } else {
            i += 1;
        }
    }
    out.push(&s[start..]);
    out
}

/// `String.substringAfter(delimiter, missingDelimiterValue)` Kotlin.
fn substring_after<'a>(s: &'a str, delim: &str) -> &'a str {
    match s.find(delim) {
        Some(i) => &s[i + delim.len()..],
        None => s,
    }
}

/// Seleksi katalog ringkas dari katalog penuh (murni, tanpa Context).
/// Pesan kosong -> katalog penuh (kontrak lama).
pub fn select_catalog(full: &str, task_text: &str) -> String {
    let task = kotlin_trim(task_text);
    if task.is_empty() {
        return full.to_string();
    }
    let low = task.to_lowercase();
    let head_end = match full.find("Daftar tool:") {
        Some(i) => i,
        None => return full.to_string(),
    };
    let trail_start = match full.find("\nAturan: kalau pertanyaan bisa dijawab") {
        Some(i) => i,
        None => return full.to_string(),
    };
    if trail_start <= head_end {
        return full.to_string();
    }
    let head = &full[..head_end + "Daftar tool:".len()];
    let section = &full[head_end + "Daftar tool:".len()..trail_start];
    let trailing = &full[trail_start..];
    let score_toks: Vec<String> = {
        let mut v: Vec<String> = Vec::new();
        for t in regex_non_alnum().split(&low) {
            if t.chars().count() >= 3 && !TASK_STOPWORDS_VC241.contains(&t) && !v.iter().any(|x| x == t) {
                v.push(t.to_string());
            }
        }
        v
    };
    let lines = kotlin_lines(section);
    let header_idx: Option<usize> = lines
        .iter()
        .position(|l| kotlin_trim(l) == "Tool lanjutan:");

    // Nilai tiap baris "- ": tool = awalan nama (10) + kata-utuh (5);
    // prose = kata kepala topik (20) + kata-utuh di isi (10).
    let line_score = |ln: &str| -> (bool, i32) {
        if let Some(_m) = regex_tool_line().find(ln) {
            // Grup 1 = nama tool (tanpa "- " dan " {").
            let name = regex_tool_line()
                .captures(ln)
                .and_then(|c| c.get(1))
                .map(|g| g.as_str())
                .unwrap_or("");
            let mut sc: i32 = 0;
            if score_toks.iter().any(|t| name.starts_with(t.as_str())) {
                sc += 10;
            }
            let desc = substring_after(ln, " — ").to_lowercase();
            let words: HashSet<&str> = regex_non_alnum_us().split(&desc).collect();
            sc += 5 * score_toks
                .iter()
                .filter(|t| words.contains(t.as_str()))
                .count() as i32;
            return (true, sc);
        }
        let head_word = ln
            .strip_prefix("- ")
            .unwrap_or(ln);
        let head_word = regex_non_alnum_us()
            .split(kotlin_trim(head_word))
            .next()
            .unwrap_or("")
            .to_lowercase();
        if !head_word.is_empty() && score_toks.iter().any(|t| head_word.starts_with(t.as_str())) {
            return (false, 20);
        }
        let low_ln = ln.to_lowercase();
        let words: HashSet<&str> = regex_non_alnum_us().split(&low_ln).collect();
        let sc = if score_toks.iter().any(|t| words.contains(t.as_str())) {
            10
        } else {
            0
        };
        (false, sc)
    };

    let mut passthrough: Vec<&str> = Vec::new();
    let mut tool_cands: Vec<(usize, String, i32)> = Vec::new();
    let mut prose_cands: Vec<(usize, String, i32)> = Vec::new();
    for (i, ln) in lines.iter().enumerate() {
        if ln.starts_with("- ") {
            let (is_tool, sc) = line_score(ln);
            if is_tool {
                tool_cands.push((i, ln.to_string(), sc));
            } else {
                prose_cands.push((i, ln.to_string(), sc));
            }
        } else if !is_blank(ln) && kotlin_trim(ln) != "Tool lanjutan:" {
            passthrough.push(ln);
        }
    }

    let pick = |cands: &[(usize, String, i32)], budget: usize, force_list_tools: bool| -> Vec<(usize, String, i32)> {
        let mut sorted: Vec<(usize, String, i32)> = cands.to_vec();
        sorted.sort_by(|a, b| b.2.cmp(&a.2).then_with(|| a.0.cmp(&b.0)));
        let mut kept: Vec<(usize, String, i32)> = Vec::new();
        let mut used: usize = 0;
        for c in sorted.iter() {
            let is_list_tools = force_list_tools && c.1.starts_with("- list_tools ");
            if c.2 <= 0 && !is_list_tools {
                continue;
            }
            if used > 0 && used + utf16_len(&c.1) + 1 > budget {
                continue;
            }
            kept.push(c.clone());
            used += utf16_len(&c.1) + 1;
        }
        if force_list_tools && !kept.iter().any(|c| c.1.starts_with("- list_tools ")) {
            if let Some(lt) = cands.iter().find(|c| c.1.starts_with("- list_tools ")) {
                kept.push(lt.clone());
            }
        }
        kept
    };

    let kept_tools = pick(&tool_cands, CATALOG_TOOL_BUDGET_VC316, true);
    let kept_prose = pick(&prose_cands, CATALOG_PROSE_BUDGET_VC316, false);
    let mut kept: Vec<(usize, String, i32)> = kept_tools.into_iter().chain(kept_prose).collect();
    kept.sort_by(|a, b| a.0.cmp(&b.0));
    let mut kept_names: HashSet<String> = HashSet::new();
    for c in kept.iter() {
        for m in regex_name_brace().captures_iter(&c.1) {
            if let Some(g) = m.get(1) {
                kept_names.insert(g.as_str().to_string());
            }
        }
    }
    let rest: Vec<&str> = tool_registry::names()
        .into_iter()
        .filter(|n| !kept_names.contains(*n))
        .collect();
    let mut sb = String::new();
    sb.push_str(head);
    sb.push('\n');
    let mut header_emitted = false;
    for c in kept.iter() {
        if !header_emitted {
            if let Some(hi) = header_idx {
                if c.0 > hi {
                    sb.push_str("Tool lanjutan:\n");
                    header_emitted = true;
                }
            }
        }
        sb.push_str(&c.1);
        sb.push('\n');
    }
    for p in passthrough.iter() {
        sb.push_str(p);
        sb.push('\n');
    }
    if !rest.is_empty() {
        sb.push_str("Tool lain yang tersedia (nama saja — bila tugas butuh salah satunya, panggil list_tools {} untuk katalog lengkapnya): ");
        sb.push_str(&rest.join(", "));
        sb.push('\n');
    }
    sb.push_str("Catatan: katalog di atas dipangkas ke tool yang relevan dengan pesan ini; deskripsi tool tidak diubah, hanya seleksinya. Katalog lengkap tersedia kapan saja lewat list_tools {}.");
    sb.push_str(trailing);
    sb
}

/// Jangkar lanjutan DELTA vc316 (murni; perintah user dicap 500 unit UTF-16).
pub fn continuation_anchor_vc316(user_command: &str) -> String {
    let cmd = utf16_take(kotlin_trim(user_command), 500);
    [
        "[LANJUTAN SISTEM — vc316 delta: JANGAN minta konteks diulang. System prompt, katalog tool, memori, dan riwayat giliran ini SUDAH kamu terima di pesan sebelumnya DALAM SESI SERVER YANG SAMA — semuanya masih berlaku. Perintah user giliran ini: \"",
        &cmd,
        "\". Lanjutkan dari situ memakai JEJAK + HASIL TOOL di bawah. Format tool-call tetap: ```json {\"tool\":\"<nama>\",\"args\":{...},\"id\":\"tc-<n>\"}``` — bila butuh tool yang tidak ada di katalog ringkasmu, panggil list_tools {} dulu untuk katalog lengkap.]",
    ]
    .concat()
}

/// Cermin `AgentPrompt.normalizeDsmlDelimiters` — fullwidth ke ASCII.
/// vc331: perluasan Q2a — semua look-alike delimiter (spasi/pipe/sudut
/// mirip-ASCII, bukti POVA 5) dinormalisasi ke ASCII sebelum parse/strip.
/// Cermin `AgentPrompt.normalizeDsmlDelimiters` — wajib sama persis.
pub fn normalize_dsml_delimiters(text: &str) -> String {
    if !text.chars().any(|c| {
        matches!(
            c,
            '｜' | '＜' | '＞' | '│' | '∣' | '〈' | '〉' | '‹' | '›' | '\u{00A0}'
                | '\u{2000}'..='\u{200A}'
                | '\u{202F}' | '\u{205F}' | '\u{3000}' | '\u{200B}' | '\u{FEFF}'
        )
    }) {
        return text.to_string();
    }
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '｜' | '│' | '∣' => out.push('|'),
            '＜' | '〈' | '‹' => out.push('<'),
            '＞' | '〉' | '›' => out.push('>'),
            '\u{00A0}' | '\u{2000}'..='\u{200A}' | '\u{202F}' | '\u{205F}' | '\u{3000}' => {
                out.push(' ')
            }
            '\u{200B}' | '\u{FEFF}' => {}
            _ => out.push(c),
        }
    }
    out
}

/// Hapus span `<tool=nama>{...}` (kurung seimbang) dari teks tampil.
/// Cermin `AgentPrompt.stripTagCallSpans` (privat Kotlin).
fn strip_tag_call_spans(text: &str) -> String {
    let mut sb = String::new();
    let mut i: usize = 0;
    while i < text.len() {
        let m = match regex_tag_call().find_at(text, i) {
            Some(m) => m,
            None => {
                sb.push_str(&text[i..]);
                break;
            }
        };
        sb.push_str(&text[i..m.start()]);
        let mut span_end = m.end();
        if let Some(rel) = text[m.end()..].find('{') {
            let brace_start = m.end() + rel;
            let mut depth: i32 = 0;
            let mut in_str = false;
            let mut esc = false;
            let mut end: Option<usize> = None;
            let mut j = brace_start;
            while j < text.len() {
                let c = text[j..].chars().next().unwrap_or('\0');
                let clen = c.len_utf8();
                if esc {
                    esc = false;
                    j += clen;
                    continue;
                }
                if c == '\\' {
                    esc = true;
                    j += clen;
                    continue;
                }
                if c == '"' {
                    in_str = !in_str;
                    j += clen;
                    continue;
                }
                if !in_str {
                    if c == '{' {
                        depth += 1;
                    } else if c == '}' {
                        depth -= 1;
                        if depth == 0 {
                            end = Some(j);
                            break;
                        }
                    }
                }
                j += clen;
            }
            if let Some(e) = end {
                if e > brace_start {
                    span_end = e + 1;
                }
            }
        }
        i = span_end;
    }
    sb
}

/// Bersihkan markup tool MENTAH dari teks TAMPIL (idempotent).
/// Cermin `AgentPrompt.stripRawToolMarkupForDisplay`.
pub fn strip_raw_tool_markup_for_display(text: &str) -> String {
    if !text.contains("DSML") && !text.contains("<tool=") {
        return text.to_string();
    }
    let mut t = normalize_dsml_delimiters(text);
    t = regex_dsml_calls().replace_all(&t, "").into_owned();
    t = regex_dsml_invoke().replace_all(&t, "").into_owned();
    t = regex_dsml_parameter().replace_all(&t, "").into_owned();
    if let Some(m) = regex_dsml_unterminated().find(&t) {
        let tail = &t[m.start()..];
        if tail.contains('{') {
            t.truncate(m.start());
        }
    }
    t = regex_dsml_stray_tag().replace_all(&t, "").into_owned();
    t = strip_tag_call_spans(&t);
    t = regex_blank_run().replace_all(&t, "\n\n").into_owned();
    kotlin_trim(&t).to_string()
}

/// Ganti klaim tak didukung dengan `[KLAIM DIHAPUS — tidak didukung bukti]`.
/// Cermin `AgentPrompt.applyDoublePass` — indeks 1-based; di luar jangkauan
/// di-skip; klaim kosong/blank di-skip; SEMUA kemunculan diganti.
pub fn apply_double_pass(answer: &str, claims: &[String], unsupported: &[i64]) -> String {
    let mut out = answer.to_string();
    for idx in unsupported.iter() {
        let pos = idx - 1;
        if pos < 0 {
            continue;
        }
        let cl = match claims.get(pos as usize) {
            Some(c) => c,
            None => continue,
        };
        if !is_blank(cl) {
            out = out.replace(cl.as_str(), "[KLAIM DIHAPUS — tidak didukung bukti]");
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Fixture katalog penuh (dibangun gaya IXL dari source vc319 —
    /// probe JVM `/tmp/probe6/catalog-full-vc319.txt`).
    fn full_catalog() -> &'static str {
        include_str!("../tests/fixtures/catalog-full-vc319.txt")
    }

    #[test]
    fn kotlin_lines_vectors() {
        assert_eq!(kotlin_lines(""), vec![""]);
        assert_eq!(kotlin_lines("a\n"), vec!["a", ""]);
        assert_eq!(kotlin_lines("a\nb"), vec!["a", "b"]);
        assert_eq!(kotlin_lines("a\r\nb\rc"), vec!["a", "b", "c"]);
        assert_eq!(kotlin_lines("a\r"), vec!["a", ""]);
    }

    #[test]
    fn select_catalog_empty_is_full() {
        let full = full_catalog();
        assert_eq!(select_catalog(full, ""), full);
        assert_eq!(select_catalog(full, "   "), full);
        assert_eq!(utf16_len(full), 94818);
    }

    #[test]
    fn select_catalog_ixl_lengths() {
        // Panjang persis probe JVM (probe6.log). Catatan: +21 vs angka
        // UKURAN-INPUT-VC316.md §6 karena fixture gaya IXL menyimpan
        // escape \" di API_LAYER_NOTE apa adanya (2 char, bukan 1).
        let full = full_catalog();
        assert_eq!(utf16_len(&select_catalog(full, "kita tes concat")), 22809);
        assert_eq!(utf16_len(&select_catalog(full, "cek concat")), 21122);
        assert_eq!(utf16_len(&select_catalog(full, "concat_version")), 18417);
        assert_eq!(utf16_len(&select_catalog(full, "bro?")), 13747);
        assert_eq!(utf16_len(&select_catalog(full, "halo")), 11459);
        assert_eq!(utf16_len(&select_catalog(full, "cek baterai")), 18479);
        assert_eq!(
            utf16_len(&select_catalog(full, "buatkan dokumen laporan")),
            21963
        );
    }

    #[test]
    fn select_catalog_content_ixl() {
        let full = full_catalog();
        let concat_names = [
            "concat_version", "concat_project_list", "concat_project_get",
            "concat_project_open", "concat_project_create", "concat_project_close",
            "concat_project_save", "concat_project_set_video", "concat_edit_apply",
            "concat_edit_undo", "concat_edit_redo", "concat_media_probe",
            "concat_media_import", "concat_catalogue_list", "concat_template_list",
            "concat_template_instantiate", "concat_template_save", "concat_export_run",
            "concat_export_cancel", "concat_export_status", "concat_preview_frame",
        ];
        let slim = select_catalog(full, "kita tes concat");
        for n in concat_names {
            assert!(slim.contains(&format!("- {} ", n)), "hilang {}", n);
        }
        // Junk substring vc241 hilang.
        assert!(!slim.contains("- remember_fact "));
        assert!(!slim.contains("PARLEMEN — debat"));
        // list_tools selalu dipertahankan.
        assert!(slim.contains("- list_tools "));
        assert!(slim.contains("Tool lain yang tersedia (nama saja"));
        let bro = select_catalog(full, "bro?");
        assert!(bro.contains("- browser_open "));
        assert!(bro.contains("- browser_read "));
        // Tanpa marker -> katalog penuh kembali.
        assert_eq!(select_catalog("tanpa Daftar tool di sini", "cek concat"), "tanpa Daftar tool di sini");
    }

    #[test]
    fn select_catalog_edges() {
        let full = full_catalog();
        // Hanya stopwords -> sama seperti "halo" (list_tools + fallback).
        assert_eq!(
            utf16_len(&select_catalog(full, "yang dan atau")),
            11459
        );
        // NBSP tepi di-trim; kapital dilowercase.
        assert_eq!(
            utf16_len(&select_catalog(full, "\u{a0}cek concat\u{a0}")),
            21122
        );
        assert_eq!(utf16_len(&select_catalog(full, "CEK CONCAT")), 21122);
        // Prefix nama ("conc") tetap cocok.
        assert_eq!(utf16_len(&select_catalog(full, "conc")), 16857);
    }

    #[test]
    fn continuation_anchor_vectors() {
        // Probe JVM (probe6.log): "halo" -> 492; 600 x -> 988 (cap 500).
        assert_eq!(utf16_len(&continuation_anchor_vc316("halo")), 492);
        assert_eq!(
            utf16_len(&continuation_anchor_vc316(&"x".repeat(600))),
            988
        );
        let a = continuation_anchor_vc316("halo");
        assert!(a.starts_with("[LANJUTAN SISTEM — vc316 delta:"));
        assert!(a.contains("Perintah user giliran ini: \"halo\"."));
        assert!(a.contains("panggil list_tools {} dulu"));
        assert!(a.ends_with(']'));
        assert!(continuation_anchor_vc316("  halo  ").contains("\"halo\"."));
    }

    #[test]
    fn dsml_stripper_vectors() {
        // Keluaran persis probe JVM (probe6.log).
        let cases: &[(&str, &str)] = &[
            ("halo biasa", "halo biasa"),
            (
                "teks <|DSML|calls>{\"tool\":\"get_battery\"}</|DSML|calls> lanjut",
                "teks  lanjut",
            ),
            (
                "sebelum <|DSML|invoke name=\"get_battery\">{\"a\":1}</|DSML|invoke> sesudah",
                "sebelum  sesudah",
            ),
            ("pikir <|DSML|invoke name=\"x\">{\"a\":1}", "pikir"),
            ("contoh DSML tanpa kurung", "contoh DSML tanpa kurung"),
            ("a <tool=get_battery>{\"x\":1} b", "a  b"),
            ("a <tool=get_battery> b tanpa json", "a  b tanpa json"),
            (
                "x\n\n\n\ny <|DSML|calls>z</|DSML|calls>\n\n\n\nz",
                "x\n\ny \n\nz",
            ),
            (
                "campur ｜＜|DSML|invoke name=\"a\"＞{\"k\":1}＜/|DSML|invoke＞ akhir",
                "campur | akhir",
            ),
            // Tanpa penutup + tanpa '{': tag hilang, prosa kept.
            (
                "teks <|DSML|invoke name=\"x\"> prosa sesudah",
                "teks  prosa sesudah",
            ),
            // Tanpa penutup + ada '{': dipotong sampai akhir.
            ("teks <|DSML|invoke name=\"x\"> {\"a\":1} prosa", "teks"),
            // Kurung tak seimbang: tag hilang, debris JSON kept.
            ("a <tool=abc>{\"k\":\"v} b", "a {\"k\":\"v} b"),
            ("a <tool=abc>{\"k\":\"v with } brace\"} b", "a  b"),
        ];
        for (input, expected) in cases {
            assert_eq!(
                strip_raw_tool_markup_for_display(input),
                *expected,
                "input {:?}",
                input
            );
        }
        assert_eq!(normalize_dsml_delimiters("a｜b＜c＞d"), "a|b<c>d");
    }

    #[test]
    fn dsml_stripper_vc331_pova5_spaced() {
        // Teks persis bocoran POVA 5 (17 Nov 23:37, vc330): blok <calls>
        // 4 invoke, delimiter ASCII pipe + SPASI. Harus hilang total
        // dari teks tampil (visible = "").
        let leaked = "< | DSML | calls>\n< | DSML | invoke name=\"list_files\">\n< | DSML | parameter name=\"path\">/sdcard/SHAREit/download</parameter>\n</ | DSML | invoke>\n< | DSML | invoke name=\"list_files\">\n< | DSML | parameter name=\"path\">/sdcard/SHAREit/videos</parameter>\n</ | DSML | invoke>\n< | DSML | invoke name=\"storage_info\">\n< | DSML | parameter name=\"path\">/sdcard/SHAREit/download</parameter>\n</ | DSML | invoke>\n< | DSML | invoke name=\"storage_info\">\n< | DSML | parameter name=\"path\">/sdcard/SHAREit/videos</parameter>\n</ | DSML | invoke>\n</ | DSML | calls>";
        assert_eq!(strip_raw_tool_markup_for_display(leaked), "");
        // Prosa sebelum/sesudah blok ber-spasi dipertahankan.
        assert_eq!(
            strip_raw_tool_markup_for_display(&format!("cek dulu\n{}\nselesai", leaked)),
            "cek dulu\n\nselesai"
        );
        // Varian look-alike: spasi fullwidth U+3000 (akar bocor vc330 —
        // strip lama ASCII-only, blok lolos, residu nilai parameter
        // + </parameter> tampil di bubble).
        let u3000 = leaked.replace(' ', "\u{3000}");
        assert_eq!(strip_raw_tool_markup_for_display(&u3000), "");
        // Varian look-alike: pipe box-drawing U+2502.
        let u2502 = leaked.replace('|', "\u{2502}");
        assert_eq!(strip_raw_tool_markup_for_display(&u2502), "");
        // Varian look-alike: pipe divides U+2223.
        let u2223 = leaked.replace('|', "\u{2223}");
        assert_eq!(strip_raw_tool_markup_for_display(&u2223), "");
        // Varian look-alike: NBSP U+00A0 sebagai spasi.
        let nbsp = leaked.replace(' ', "\u{a0}");
        assert_eq!(strip_raw_tool_markup_for_display(&nbsp), "");
        // Varian look-alike: sudut 〈 U+3008 / 〉 U+3009.
        let angle = leaked.replace('<', "\u{3008}").replace('>', "\u{3009}");
        assert_eq!(strip_raw_tool_markup_for_display(&angle), "");
        // Normalisasi look-alike ke ASCII (sebelum parse/strip).
        assert_eq!(
            normalize_dsml_delimiters("a\u{3000}b\u{a0}c\u{2003}d│e∣f〈g〉h"),
            "a b c d|e|f<g>h"
        );
        assert_eq!(normalize_dsml_delimiters("a｜b＜c＞d"), "a|b<c>d");
        // Idempotent pada teks bocoran.
        let once = strip_raw_tool_markup_for_display(leaked);
        assert_eq!(strip_raw_tool_markup_for_display(&once), once);
    }

    #[test]
    fn apply_double_pass_vectors() {
        // Probe JVM (probe6.log).
        let ans = "Hasilnya sudah dibuat dan sudah dikirim. File sudah dibuat kemarin.";
        let claims = vec![
            "sudah dibuat".to_string(),
            "sudah dikirim".to_string(),
            "tidak ada".to_string(),
        ];
        assert_eq!(
            apply_double_pass(ans, &claims, &[1, 2]),
            "Hasilnya [KLAIM DIHAPUS — tidak didukung bukti] dan [KLAIM DIHAPUS — tidak didukung bukti]. File [KLAIM DIHAPUS — tidak didukung bukti] kemarin."
        );
        assert_eq!(apply_double_pass(ans, &claims, &[5, 0, -1]), ans);
        assert_eq!(
            apply_double_pass(ans, &claims, &[1, 1, 3]),
            "Hasilnya [KLAIM DIHAPUS — tidak didukung bukti] dan sudah dikirim. File [KLAIM DIHAPUS — tidak didukung bukti] kemarin."
        );
        // Klaim blank di-skip; jawaban kosong tetap kosong.
        assert_eq!(
            apply_double_pass(ans, &["   ".to_string()], &[1]),
            ans
        );
        assert_eq!(apply_double_pass("", &claims, &[1]), "");
    }
}
