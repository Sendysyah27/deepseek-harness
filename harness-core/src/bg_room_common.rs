//! Slice 12 (vc329) — helper bersama modul latar/room.
//!
//! Emulasi semantik Kotlin/Java yang dipakai keempat modul slice 12
//! (`bg_brain`, `group_chat`, `ds_agent`, `oc_coord`):
//! - UTF-16 (`String.length`/`take`/`takeLast`/`substringBefore` Kotlin),
//! - `String.trim()` / `isBlank()` Kotlin (Java `Character.isWhitespace`),
//! - `String.lowercase()` + `equalsIgnoreCase`/`contains(ignoreCase=true)`
//!   ala Java (per-char `Character.toLowerCase/toUpperCase`),
//! - `String.toIntOrNull()` Kotlin (parse int ketat, tanpa fallback Double),
//! - regex Java `\s+` (ASCII saja — NBSP TIDAK cocok, terbukti probe JVM),
//! - `JSONObject.quote` (probe JVM: `/` TIDAK di-escape),
//! - `JSONObject.opt*` (pakai ulang `concat_api_client`).
//!
//! Semua helper di sini murni + tanpa I/O; panic tidak boleh terjadi
//! untuk input apa pun (panggil dari dispatch yang sudah `catch_unwind`
//! di JNI, tapi tetap defensif: tanpa `unwrap`/`expect` di jalur input).

#![forbid(unsafe_code)]

use crate::concat_api_client::{java_double_string, json_number_string};
use crate::ds_safety_strip::kotlin_trim;
use serde_json::Value as Json;

/// `{"ok":true,"value":...}` — amplop sukses seam (pola slice 8–11).
pub(crate) fn ok_json(value: Json) -> String {
    serde_json::json!({"ok": true, "value": value}).to_string()
}

/// `{"ok":false,"error":...}` — amplop gagal domain (input tak-valid /
/// op tak dikenal). Kotlin fallback TANPA mematikan saklar.
pub(crate) fn err_json(msg: &str) -> String {
    serde_json::json!({"ok": false, "error": msg}).to_string()
}

/// Parse request JSON + ambil `"op"`.
pub(crate) fn req_op(req_str: &str) -> Result<(Json, String), String> {
    let req: Json =
        serde_json::from_str(req_str).map_err(|_| "bad request json".to_string())?;
    let op = req
        .get("op")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing op".to_string())?
        .to_string();
    Ok((req, op))
}

/// Field string wajib.
pub(crate) fn get_str<'a>(req: &'a Json, key: &str) -> Result<&'a str, String> {
    req.get(key)
        .and_then(|v| v.as_str())
        .ok_or_else(|| format!("missing/invalid {key}"))
}

/// Field string opsional (default "").
pub(crate) fn get_str_d<'a>(req: &'a Json, key: &str) -> &'a str {
    req.get(key).and_then(|v| v.as_str()).unwrap_or("")
}

/// Field bool opsional.
pub(crate) fn get_bool_d(req: &Json, key: &str, default: bool) -> bool {
    match req.get(key) {
        Some(Json::Bool(b)) => *b,
        _ => default,
    }
}

/// Field i64 wajib/opsional (angka JSON; string TIDAK diterima — pemanggil
/// Kotlin selalu mengirim angka untuk field ini).
pub(crate) fn get_i64(req: &Json, key: &str, default: i64) -> Result<i64, String> {
    match req.get(key) {
        None | Some(Json::Null) => Ok(default),
        Some(Json::Number(n)) => n
            .as_i64()
            .ok_or_else(|| format!("{key} out of range")),
        Some(_) => Err(format!("{key} must be a number")),
    }
}

/// Field i32 opsional.
pub(crate) fn get_i32(req: &Json, key: &str, default: i32) -> Result<i32, String> {
    Ok(get_i64(req, key, default as i64)? as i32)
}

/// `String.take(n)` Kotlin — n unit UTF-16 pertama; high surrogate yang
/// menggantung DIBUANG (String Rust tak bisa menampung lone surrogate).
pub(crate) fn utf16_take(s: &str, n: usize) -> String {
    crate::concat_api_client::utf16_take(s, n)
}

/// `String.takeLast(n)` Kotlin — n unit UTF-16 TERAKHIR; low surrogate
/// yang menggantung di AWAL dibuang.
pub(crate) fn utf16_take_last(s: &str, n: usize) -> String {
    let units: Vec<u16> = s.encode_utf16().collect();
    if units.len() <= n {
        return s.to_string();
    }
    let mut tail: Vec<u16> = units[units.len() - n..].to_vec();
    if let Some(&first) = tail.first() {
        if (0xDC00..0xE000).contains(&first) {
            tail.remove(0);
        }
    }
    String::from_utf16_lossy(&tail)
}

/// Panjang dalam unit UTF-16 (`String.length` Kotlin).
pub(crate) fn utf16_len(s: &str) -> usize {
    crate::concat_api_client::utf16_len(s)
}

/// `String.substringBefore("\n")` Kotlin.
pub(crate) fn substring_before_nl(s: &str) -> &str {
    match s.find('\n') {
        Some(i) => &s[..i],
        None => s,
    }
}

/// `String.toIntOrNull()` Kotlin sesudah `trim()`: int ketat (boleh `+`/
/// `-` di depan, digit ASCII saja, dalam rentang i32); `"20.0"`,
/// `"0x10"`, `""`, `"2147483648"` -> `None` (terbukti probe JVM).
pub(crate) fn java_parse_int_strict(s: &str) -> Option<i32> {
    let t = kotlin_trim(s);
    if t.is_empty() {
        return None;
    }
    let v: i64 = t.parse().ok()?;
    i32::try_from(v).ok()
}

/// `Character.toLowerCase(char)` Java (pemetaan 1 char): `İ` (U+0130)
/// -> `'i'`; yang lain: hasil `to_lowercase` Rust bila tepat 1 char,
/// bila mengembang (>1 char, mis. `ß` tetap) -> char asal.
pub(crate) fn java_lower_char(c: char) -> char {
    if c == '\u{130}' {
        return 'i';
    }
    let mut it = c.to_lowercase();
    match (it.next(), it.next()) {
        (Some(one), None) => one,
        _ => c,
    }
}

/// `Character.toUpperCase(char)` Java (pemetaan 1 char): `ı` (U+0131)
/// -> `'I'`; yang lain: hasil `to_uppercase` Rust bila tepat 1 char.
pub(crate) fn java_upper_char(c: char) -> char {
    if c == '\u{131}' {
        return 'I';
    }
    let mut it = c.to_uppercase();
    match (it.next(), it.next()) {
        (Some(one), None) => one,
        _ => c,
    }
}

/// `String.equalsIgnoreCase` Java: bandingkan per unit UTF-16; tiap
/// unit cocok bila sama persis, atau `toUpperCase` sama, atau
/// `toLowerCase` sama (urutan cek sama seperti `String.regionMatches`
/// JDK). Terbukti probe: `"İ"` vs `"i"` true, `"I"` vs `"ı"` true.
pub(crate) fn java_equals_ignore_case(a: &str, b: &str) -> bool {
    let au: Vec<u16> = a.encode_utf16().collect();
    let bu: Vec<u16> = b.encode_utf16().collect();
    if au.len() != bu.len() {
        return false;
    }
    au.iter().zip(bu.iter()).all(|(&x, &y)| {
        if x == y {
            return true;
        }
        // Unit surrogate tanpa padanan char Rust: sudah pasti beda
        // (x != y di atas) -> false, jangan samakan lewat FFFD.
        let xc = match char::from_u32(x as u32) {
            Some(c) => c,
            None => return false,
        };
        let yc = match char::from_u32(y as u32) {
            Some(c) => c,
            None => return false,
        };
        if java_upper_char(xc) == java_upper_char(yc) {
            return true;
        }
        java_lower_char(xc) == java_lower_char(yc)
    })
}

/// `String.contains(other, ignoreCase=true)` Kotlin: `indexOf` dengan
/// `regionMatches` per posisi (unit UTF-16). Jarum kosong -> true.
pub(crate) fn java_contains_ignore_case(hay: &str, needle: &str) -> bool {
    if needle.is_empty() {
        return true;
    }
    let hu: Vec<u16> = hay.encode_utf16().collect();
    let nu: Vec<u16> = needle.encode_utf16().collect();
    if nu.len() > hu.len() {
        return false;
    }
    (0..=(hu.len() - nu.len())).any(|start| {
        hu[start..start + nu.len()]
            .iter()
            .zip(nu.iter())
            .all(|(&x, &y)| {
                if x == y {
                    return true;
                }
                let xc = match char::from_u32(x as u32) {
                    Some(c) => c,
                    None => return false,
                };
                let yc = match char::from_u32(y as u32) {
                    Some(c) => c,
                    None => return false,
                };
                if java_upper_char(xc) == java_upper_char(yc) {
                    return true;
                }
                java_lower_char(xc) == java_lower_char(yc)
            })
    })
}

/// Apakah char ini cocok regex Java `\s` (ASCII saja: spasi, tab,
/// LF, VT, FF, CR — NBSP TIDAK cocok, terbukti probe JVM).
pub(crate) fn java_is_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{000B}' | '\u{000C}' | '\r')
}

/// `replace(Regex("\\s+"), " ")` Java: runtun whitespace ASCII -> 1 spasi.
pub(crate) fn java_collapse_ws(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut in_run = false;
    for c in s.chars() {
        if java_is_space(c) {
            if !in_run {
                out.push(' ');
                in_run = true;
            }
        } else {
            out.push(c);
            in_run = false;
        }
    }
    out
}

/// Normalisasi baris detektor spinning (vc277/vc303): `lowercase()`,
/// `[0-9]+` -> `"#"`, `\s+` -> `" "`, `trim()`. Dipakai
/// `spinningPatternVc277` + `OcSpinWatchVc303.observe/flush` — SATU
/// implementasi supaya ketiganya tidak bisa meleset satu sama lain.
pub(crate) fn spin_normalize(raw: &str) -> String {
    let lower = raw.to_lowercase();
    let mut dedigited = String::with_capacity(lower.len());
    let mut in_digits = false;
    for c in lower.chars() {
        if c.is_ascii_digit() {
            if !in_digits {
                dedigited.push('#');
                in_digits = true;
            }
        } else {
            dedigited.push(c);
            in_digits = false;
        }
    }
    kotlin_trim(&java_collapse_ws(&dedigited)).to_string()
}

/// `JSONObject.quote` (org.json, terbukti probe JVM): `"`/`\` + kontrol
/// pendek (`\b \f \n \r \t`) + kontrol lain -> `\u00xx` hex lowercase;
/// `/` TIDAK di-escape; DEL + non-ASCII mentah; dibungkus `"..."`.
pub(crate) fn json_quote(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\u{0008}' => out.push_str("\\b"),
            '\u{000C}' => out.push_str("\\f"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// `JSONObject.opt(key)?.toString()` — nilai mentah org.json sebagai
/// string: String -> apa adanya; Number -> `numberToString` Java;
/// Boolean -> `"true"/"false"`; NULL -> `"null"`; Object/Array -> JSON
/// kompak (URUTAN KEY BISA BEDA dari org.json — hash order — pemakaian
/// di sini hanya untuk `args=` di detail izin, dibandingkan P15
/// secara semantik bila objek). `None` = key tidak ada.
pub(crate) fn opt_raw_string(v: &Json, key: &str) -> Option<String> {
    match v.get(key) {
        None => None,
        Some(Json::Null) => Some("null".to_string()),
        Some(Json::String(s)) => Some(s.clone()),
        Some(Json::Number(n)) => Some(json_number_string(n)),
        Some(Json::Bool(b)) => Some(b.to_string()),
        Some(other) => Some(other.to_string()),
    }
}

/// `JSONObject.optString(key)` — pakai ulang helper slice 5 (probe:
/// angka/bool dicoerce, null/hilang -> default, objek -> JSON kompak).
pub(crate) fn opt_string(v: &Json, key: &str, default: &str) -> String {
    crate::concat_api_client::opt_string(v, key, default)
}

/// `JSONObject.optInt(key, default)` / `optLong` / `optBoolean` — pakai
/// ulang helper slice 5.
pub(crate) fn opt_int(v: &Json, key: &str, default: i32) -> i32 {
    crate::concat_api_client::opt_int(v, key, default)
}

pub(crate) fn opt_long(v: &Json, key: &str, default: i64) -> i64 {
    crate::concat_api_client::opt_long(v, key, default)
}

pub(crate) fn opt_bool(v: &Json, key: &str, default: bool) -> bool {
    crate::concat_api_client::opt_bool(v, key, default)
}

/// `JSONObject.optJSONObject(key)` — null/bukan-objek -> None.
pub(crate) fn opt_obj<'a>(v: &'a Json, key: &str) -> Option<&'a Json> {
    match v.get(key) {
        Some(o @ Json::Object(_)) => Some(o),
        _ => None,
    }
}

/// `JSONObject.optJSONArray(key)` — null/bukan-array -> None.
pub(crate) fn opt_arr<'a>(v: &'a Json, key: &str) -> Option<&'a Vec<Json>> {
    match v.get(key) {
        Some(Json::Array(a)) => Some(a),
        _ => None,
    }
}

/// Parse JSON (objek/array) — `None` bila tak-valid (Kotlin: `catch`
/// -> nilai fallback, mis. `JSONArray()` kosong / `-1`).
pub(crate) fn parse_json(s: &str) -> Option<Json> {
    serde_json::from_str(s).ok()
}

/// `String.isBlank()` Kotlin (semua char `isWhitespace` Java).
pub(crate) fn is_blank(s: &str) -> bool {
    crate::concat_api_client::is_blank(s)
}

/// `String.substringAfter("=")` Kotlin: sesudah `=` pertama; bila tak
/// ada `=` -> string utuh.
pub(crate) fn substring_after_eq(s: &str) -> &str {
    match s.find('=') {
        Some(i) => &s[i + 1..],
        None => s,
    }
}

/// Angka JSON -> string ala `JSONObject.numberToString` Java (dipakai
/// `opt_string` untuk Number + `parse_eval_result` DsAgent).
pub(crate) fn java_number_string(n: &serde_json::Number) -> String {
    json_number_string(n)
}

/// `Double.toString` Java untuk f64 (dipakai `parse_eval_result`).
pub(crate) fn java_double(f: f64) -> String {
    java_double_string(f)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn utf16_take_last_drops_dangling_low_surrogate() {
        // "ab😀cd": unit UTF-16 [a,b,H,L,c,d]; takeLast(4) = [H,L,c,d] utuh.
        assert_eq!(utf16_take_last("ab\u{1F600}cd", 4), "\u{1F600}cd");
        // takeLast(3) = [L,c,d] -> low surrogate menggantung dibuang -> "cd".
        assert_eq!(utf16_take_last("ab\u{1F600}cd", 3), "cd");
        assert_eq!(utf16_take_last("abc", 9), "abc");
        assert_eq!(utf16_take_last("", 5), "");
    }

    #[test]
    fn parse_int_strict_matches_probe() {
        assert_eq!(java_parse_int_strict("+20"), Some(20));
        assert_eq!(java_parse_int_strict("20.0"), None);
        assert_eq!(java_parse_int_strict("0x10"), None);
        assert_eq!(java_parse_int_strict(""), None);
        assert_eq!(java_parse_int_strict("2147483648"), None);
        assert_eq!(java_parse_int_strict("  -7  "), Some(-7));
        assert_eq!(java_parse_int_strict("2147483647"), Some(2147483647));
    }

    #[test]
    fn equals_ignore_case_matches_probe() {
        assert!(java_equals_ignore_case("\u{130}", "i"));
        assert!(java_equals_ignore_case("I", "\u{131}"));
        assert!(java_equals_ignore_case("AbC", "aBc"));
        assert!(!java_equals_ignore_case("ab", "abc"));
        assert!(java_contains_ignore_case("izIn ToOl", "izin tool"));
        assert!(java_contains_ignore_case("abc", ""));
        assert!(!java_contains_ignore_case("abc", "d"));
    }

    #[test]
    fn json_quote_matches_probe() {
        assert_eq!(json_quote("a/b\"c\\d\te\u{1}f\u{7F}g"), "\"a/b\\\"c\\\\d\\te\\u0001f\u{7F}g\"");
        assert_eq!(json_quote(""), "\"\"");
    }

    #[test]
    fn spin_normalize_matches_kotlin() {
        assert_eq!(spin_normalize("Error 123  di  baris\t4"), "error # di baris #");
        assert_eq!(spin_normalize("  A\u{00A0}B  "), "a\u{00A0}b");
    }

    #[test]
    fn collapse_ws_java_ascii_only() {
        // NBSP (U+00A0) BUKAN \s Java -> dipertahankan, bukan collapse.
        assert_eq!(java_collapse_ws("a\u{00A0}b\t\nc"), "a\u{00A0}b c");
    }
}
