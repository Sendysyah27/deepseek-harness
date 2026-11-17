//! Slice 12 (vc329) — logika murni `DsAgent.kt` (kontrol chat DeepSeek
//! web lewat injeksi JS ke WebView).
//!
//! Yang dipindah HANYA logika murni + template JS; yang tetap Kotlin:
//! WebView + `Handler`/`Looper` (main thread), `evaluateJavascript`,
//! `postDelayed` (polling 1200 ms + fallback klik kirim), flag `busy`,
//! callback `onPartial/onDone/onError`.
//!
//! Op:
//! - `poll_step` — SATU langkah `pollAnswer` (timeout 150 / berubah /
//!   stabil 3x / selesai); Kotlin yang mengulang + memanggil
//!   `lastState` + `postDelayed`.
//! - `parse_eval_result` — parse hasil `evaluateJavascript`
//!   (`JSONTokener.nextValue`: string -> dirinya, null -> `"null"`,
//!   angka/bool -> `toString`, objek/array -> JSON kompak, gagal -> `""`).
//! - `json_quote` — `JSONObject.quote` (probe JVM).
//! - `build_attach_js` — template `attachFile` (ganti literal
//!   B64HERE/NAMEHERE/MIMEHERE, urutan sama seperti Kotlin).
//! - `build_send_js` — template kirim `sendAndAwait` (toggle
//!   DeepThink/Search + prompt ter-quote).
//! - `ds_js` — literal JS `probe` / `page_text` / `last_state`
//!   (byte-identik — konstanta diekstrak dari sumber Kotlin, lihat
//!   `ds_agent_js.rs`).
//!
//! CATATAN KEJUJURAN (urutan key JSON): objek/array di
//! `parse_eval_result` di-emit serde (key terurut) sementara org.json
//! memakai hash order — P15 membandingkan hasil objek/array secara
//! semantik (parse ulang), bukan string. Nilai string/angka/bool/null
//! dibandingkan persis.

#![forbid(unsafe_code)]

use crate::bg_room_common::*;
use crate::ds_agent_js::{ATTACH_JS_TEMPLATE, LAST_STATE_JS, PAGE_TEXT_JS, PROBE_JS, SEND_JS_TEMPLATE};
use serde_json::Value as Json;

pub fn ds_agent_json(req_str: &str) -> Result<String, String> {
    Ok(dispatch(req_str).unwrap_or_else(|e| err_json(&e)))
}

fn dispatch(req_str: &str) -> Result<String, String> {
    let (req, op) = req_op(req_str)?;
    match op.as_str() {
        "poll_step" => {
            let tries = get_i64(&req, "tries", 0)?;
            let base_count = get_i64(&req, "base_count", 0)?;
            let base_text = get_str(&req, "base_text")?;
            let last_text = get_str(&req, "last_text")?;
            let stable = get_i64(&req, "stable", 0)?;
            let count = get_i64(&req, "count", 0)?;
            let text = get_str(&req, "text")?;
            let (outcome, fire_partial, new_stable, new_last_text) =
                poll_step(tries, base_count, base_text, last_text, stable, count, text);
            Ok(ok_json(serde_json::json!({
                "outcome": outcome,
                "fire_partial": fire_partial,
                "new_stable": new_stable,
                "new_last_text": new_last_text,
            })))
        }
        "parse_eval_result" => {
            let raw = get_str(&req, "raw")?;
            Ok(ok_json(Json::String(parse_eval_result(raw))))
        }
        "json_quote" => {
            let s = get_str(&req, "s")?;
            Ok(ok_json(Json::String(json_quote(s))))
        }
        "build_attach_js" => {
            let b64 = get_str(&req, "b64")?;
            let name = get_str(&req, "name")?;
            let mime = get_str(&req, "mime")?;
            Ok(ok_json(Json::String(
                ATTACH_JS_TEMPLATE
                    .replace("B64HERE", b64)
                    .replace("NAMEHERE", name)
                    .replace("MIMEHERE", mime),
            )))
        }
        "build_send_js" => {
            let thinking = get_bool_d(&req, "thinking", false);
            let search = get_bool_d(&req, "search", false);
            let quoted = get_str(&req, "quoted")?;
            Ok(ok_json(Json::String(
                SEND_JS_TEMPLATE
                    .replace("$thinking", if thinking { "true" } else { "false" })
                    .replace("$search", if search { "true" } else { "false" })
                    .replace("$quoted", quoted),
            )))
        }
        "ds_js" => {
            let name = get_str(&req, "name")?;
            let js = match name {
                "probe" => PROBE_JS,
                "page_text" => PAGE_TEXT_JS,
                "last_state" => LAST_STATE_JS,
                _ => return Err(format!("unknown ds_js: {name}")),
            };
            Ok(ok_json(Json::String(js.to_string())))
        }
        _ => Err(format!("unknown op: {op}")),
    }
}

/// Satu langkah `pollAnswer`. Outcome: "timeout" | "unchanged" |
/// "continue" | "done". `fire_partial` = panggil `onPartial(text)`
/// (teks berubah + non-blank). `new_last_text`/`new_stable` = state
/// untuk langkah berikut (unchanged: lastText lama + stable 0).
pub(crate) fn poll_step(
    tries: i64,
    base_count: i64,
    base_text: &str,
    last_text: &str,
    stable: i64,
    count: i64,
    text: &str,
) -> (&'static str, bool, i64, String) {
    if tries > 150 {
        return ("timeout", false, stable, last_text.to_string());
    }
    let changed = count > base_count || (text != base_text && !is_blank(text));
    if !changed {
        return ("unchanged", false, 0, last_text.to_string());
    }
    let fire_partial = text != last_text && !is_blank(text);
    let new_stable = if text == last_text && !is_blank(text) {
        stable + 1
    } else {
        0
    };
    if new_stable >= 3 {
        ("done", fire_partial, new_stable, text.to_string())
    } else {
        ("continue", fire_partial, new_stable, text.to_string())
    }
}

/// Parse hasil `evaluateJavascript` ala `eval()` DsAgent:
/// `JSONTokener(raw).nextValue()` — string -> dirinya; null ->
/// `"null"`; angka/bool -> `toString` Java; objek/array -> JSON
/// kompak; gagal parse -> `""`. Nilai pertama saja (sisa diabaikan —
/// `StreamDeserializer` serde).
pub(crate) fn parse_eval_result(raw: &str) -> String {
    let mut stream = serde_json::Deserializer::from_str(raw).into_iter::<Json>();
    let v = match stream.next() {
        Some(Ok(v)) => v,
        _ => return String::new(),
    };
    match v {
        Json::String(s) => s,
        Json::Null => "null".to_string(),
        Json::Bool(b) => b.to_string(),
        Json::Number(n) => {
            if let Some(i) = n.as_i64() {
                i.to_string()
            } else if let Some(u) = n.as_u64() {
                u.to_string()
            } else if let Some(f) = n.as_f64() {
                java_double(f)
            } else {
                n.to_string()
            }
        }
        other => other.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn poll_step_matrix() {
        // Timeout.
        assert_eq!(poll_step(151, 0, "", "", 0, 0, "").0, "timeout");
        assert_eq!(poll_step(150, 0, "", "", 0, 0, "").0, "unchanged");
        // Belum berubah -> ulang, stable reset 0.
        assert_eq!(
            poll_step(0, 2, "base", "last", 2, 2, "base"),
            ("unchanged", false, 0, "last".to_string())
        );
        // Berubah + teks baru -> continue + partial.
        assert_eq!(
            poll_step(0, 2, "base", "last", 0, 3, "baru"),
            ("continue", true, 0, "baru".to_string())
        );
        // Sama seperti lastText + non-blank -> stabil naik.
        assert_eq!(
            poll_step(1, 2, "base", "sama", 2, 3, "sama"),
            ("done", false, 3, "sama".to_string())
        );
        assert_eq!(
            poll_step(1, 2, "base", "sama", 1, 3, "sama"),
            ("continue", false, 2, "sama".to_string())
        );
        // Teks kosong tidak memicu partial/stabil walau count naik.
        assert_eq!(
            poll_step(0, 2, "base", "", 0, 3, ""),
            ("continue", false, 0, "".to_string())
        );
        // count > baseCount saja sudah "berubah" walau teks == base.
        assert_eq!(
            poll_step(0, 2, "base", "base", 0, 3, "base"),
            ("continue", false, 1, "base".to_string())
        );
    }

    #[test]
    fn parse_eval_result_matrix() {
        assert_eq!(parse_eval_result("\"READY\""), "READY");
        assert_eq!(parse_eval_result("null"), "null");
        assert_eq!(parse_eval_result("true"), "true");
        assert_eq!(parse_eval_result("123"), "123");
        assert_eq!(parse_eval_result("1.5"), "1.5");
        assert_eq!(parse_eval_result("1.0"), "1.0");
        assert_eq!(parse_eval_result(""), "");
        assert_eq!(parse_eval_result("bukan json"), "");
        assert_eq!(parse_eval_result("  \"x\"  trailing"), "x");
        // Objek/array: bandingkan semantik (urutan key boleh beda).
        let o: Json = serde_json::from_str(&parse_eval_result("{\"c\":1,\"t\":\"hi\"}")).unwrap();
        assert_eq!(o, serde_json::json!({"c": 1, "t": "hi"}));
        let a: Json = serde_json::from_str(&parse_eval_result("[1, \"a\"]")).unwrap();
        assert_eq!(a, serde_json::json!([1, "a"]));
    }

    #[test]
    fn attach_and_send_templates() {
        let js = ATTACH_JS_TEMPLATE
            .replace("B64HERE", "QUJD")
            .replace("NAMEHERE", "foto.jpg")
            .replace("MIMEHERE", "image/jpeg");
        assert!(js.contains("atob('QUJD')"));
        assert!(js.contains("new File([arr],'foto.jpg',{type:'image/jpeg'})"));
        assert!(!js.contains("B64HERE"));
        let send = SEND_JS_TEMPLATE
            .replace("$thinking", "true")
            .replace("$search", "false")
            .replace("$quoted", "\"halo\"");
        assert!(send.contains("setToggle('DeepThink', true);"));
        assert!(send.contains("setToggle('Search', false);"));
        assert!(send.contains("setter.call(ta, \"halo\");"));
        assert!(send.contains("return 'SET';"));
        assert!(PROBE_JS.contains("return 'READY';"));
        assert!(PAGE_TEXT_JS.contains("document.title + '\\n' +"));
        assert!(LAST_STATE_JS.contains("ds-markdown"));
        assert!(LAST_STATE_JS.contains("JSON.stringify({c: n.length, t: t})"));
    }
}
