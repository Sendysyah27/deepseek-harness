//! Port split `DsApiClient` slice 11 (vc328) — logika murni klien API
//! internal DeepSeek + transport unggah berkas.
//!
//! Yang pindah ke sini (modul `ds_api`, seam `dsApiJson`):
//! - `find_session_id` / `find_challenge` / `find_file_id` — ekstraksi
//!   JSON org.json (`optJSONObject`/`optString` + `ifBlank`) dari
//!   `DsApiClient.kt` (dipakai `createServerSession`, `solvePowHeader`,
//!   `uploadFile`).
//! - `est_tokens` — estimasi token lokal `Math.round(chars / perChar)`
//!   + `maxOf(1, ...)` dari `DsApiClient.recordTokens` (PERHATIAN: rumus
//!   ini BEDA dari `SlotClient.estTokens` yang memotong desimal — yang
//!   di-port adalah rumus DsApiClient, dikunci tes).
//! - `safe_file_name` — sanitasi nama berkas unggahan (satuan UTF-16,
//!   hanya 32..126 kecuali `"` yang lolos; sisanya `_`; blank ->
//!   `"file.bin"`).
//! - `upload_file` — POST multipart `/api/v0/file/upload_file` via ureq
//!   (boundary TETAP `----WebKitFormBoundaryTB0pXOQR2RL219Hu`, header
//!   `x-file-size`, body biner via base64 in — JSON tidak bisa membawa
//!   byte mentah).
//!
//! Yang SENGAJA TIDAK di sini (tetap Kotlin, BUILD-VC328.md §2):
//! - Transport JSON non-streaming (`postJson`/`getJson`) memakai seam
//!   `httpJson` slice 8 (`HttpSeam.request`, ureq) — tanpa kode Rust baru.
//! - Parse stream SSE memakai `sse_apply` slice 8 (`HttpSeam.sseApply`).
//! - Solve PoW memakai `pow_solver` slice 2 (`PowSolver.solveDeepSeek`).
//! - Klasifikasi error memakai `ds_failure` slice 1.
//! - Panen cookie/token (`CookieManager`/`WebView`/`evaluateJavascript`)
//!   + mesin sesi (`runChat`/`chat`, guard/primed/cooldown/accum) +
//!   loop baca stream + `onPartial` + antrean + Governor + prefs/diag
//!   tetap Kotlin (peta §2.8 blocker keras + §3.4: Rust tidak callback
//!   ke Kotlin di slice awal — transport `postCompletion` tetap
//!   `HttpURLConnection` Kotlin seperti `SlotClient` slice 8).
//!
//! Konvensi respons (pola `http_json`): op sukses -> `{"ok":true,...}`;
//! op gagal (input tak-valid) -> `{"ok":false,"error":...}`; transport
//! gagal (`upload_file`) -> `{"ok":false,"error":...}` — pemanggil
//! Kotlin fallback ke transport Kotlin lama (saklar tetap hidup).
//! Non-2xx TETAP `{"ok":true,"code":...,"body":...}` — Kotlin yang
//! memutuskan throw/return, sama seperti membaca `errorStream`.
//!
//! Quirk Kotlin yang direplikasi (dikunci tes):
//! - `optJSONObject` -> null bila kunci hilang ATAU nilainya bukan objek;
//!   `optString` koersi (angka/bool -> teks kompak, null/hilang -> "").
//! - `String.ifBlank` = kosong atau semua `Character.isWhitespace`
//!   (NBSP tepi dihitung blank — `kotlin_trim`/`java_is_whitespace`).
//! - `Math.round(Double)` Java = `floor(x + 0.5)` (bukan `round` Rust
//!   yang membulatkan -1.5 ke -2); NaN -> 0, ±inf -> ±MAX.
//! - `safeFileName` bekerja atas satuan UTF-16: satu emoji (2 surrogate)
//!   menjadi `"__"`, bukan `"_"`.
//! - Multipart: head + byte berkas mentah + tail, persis `uploadFile`
//!   Kotlin (tanpa encode ulang isi berkas).

#![forbid(unsafe_code)]

use crate::ds_safety_strip::java_is_whitespace;
use crate::http::{opt_string_coerce, valid_header_name, valid_header_value};
use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use serde_json::{json, Value as Json};
use std::io::Read;
use std::time::Duration;

/// Satu entry point seam: request JSON -> respons JSON (String).
/// Error op dikembalikan sebagai `{"ok":false,"error":...}` — BUKAN
/// `Err` — supaya boundary JNI mengembalikan `null` HANYA bila
/// panic/error FFI (pola `http_json`).
pub fn ds_api_json(req_str: &str) -> Result<String, String> {
    Ok(dispatch(req_str).unwrap_or_else(|e| err_json(&e)))
}

fn dispatch(req_str: &str) -> Result<String, String> {
    let req: Json =
        serde_json::from_str(req_str).map_err(|_| "bad request json".to_string())?;
    let op = req
        .get("op")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing op".to_string())?;
    match op {
        "find_session_id" => op_find_session_id(&req),
        "find_challenge" => op_find_challenge(&req),
        "find_file_id" => op_find_file_id(&req),
        "est_tokens" => op_est_tokens(&req),
        "safe_file_name" => op_safe_file_name(&req),
        "upload_file" => op_upload_file(&req),
        _ => Err(format!("unknown op: {op}")),
    }
}

fn err_json(msg: &str) -> String {
    json!({"ok": false, "error": msg}).to_string()
}

fn get_str<'a>(req: &'a Json, key: &str) -> Result<&'a str, String> {
    req.get(key)
        .and_then(|v| v.as_str())
        .ok_or_else(|| format!("missing/invalid {key}"))
}

fn get_i64(req: &Json, key: &str, default: i64) -> Result<i64, String> {
    match req.get(key) {
        None | Some(Json::Null) => Ok(default),
        Some(Json::Number(n)) => n
            .as_i64()
            .ok_or_else(|| format!("{key} out of range")),
        Some(_) => Err(format!("{key} must be a number")),
    }
}

/// `JSONObject.optJSONObject(key)` org.json: `Some` hanya bila nilai
/// ada DAN berupa objek; hilang / null / bukan-objek -> `None`.
fn opt_obj<'a>(v: &'a Json, key: &str) -> Option<&'a Json> {
    match v {
        Json::Object(m) => m.get(key).filter(|x| x.is_object()),
        _ => None,
    }
}

/// `String.isBlank()` Kotlin = kosong atau semua `isWhitespace()`.
fn is_blank_java(s: &str) -> bool {
    s.chars().all(java_is_whitespace)
}

/// `optString` + `ifBlank { null }`: "" bila hilang/null/blank.
fn opt_string_non_blank(v: Option<&Json>) -> Option<String> {
    let s = opt_string_coerce(v);
    if is_blank_java(&s) {
        None
    } else {
        Some(s)
    }
}

// ------------------------------------------------------- find_session_id --

/// `DsApiClient.findSessionId` 1:1:
/// `data.biz_data.chat_session.id | data.biz_data.id`
/// (`biz_data` hilang/bukan-objek -> `data` sendiri).
fn find_session_id(root: &Json) -> Option<String> {
    let data = opt_obj(root, "data")?;
    let biz = opt_obj(data, "biz_data").unwrap_or(data);
    if let Some(sess) = opt_obj(biz, "chat_session") {
        if let Some(id) = opt_string_non_blank(sess.get("id")) {
            return Some(id);
        }
    }
    opt_string_non_blank(biz.get("id"))
}

fn op_find_session_id(req: &Json) -> Result<String, String> {
    let root_str = get_str(req, "root")?;
    let root: Json =
        serde_json::from_str(root_str).map_err(|_| "root bukan JSON".to_string())?;
    Ok(json!({"ok": true, "value": find_session_id(&root)}).to_string())
}

// --------------------------------------------------------- find_challenge --

/// `DsApiClient.findChallenge` 1:1:
/// `data.biz_data.challenge` (objek) — bila kunci `challenge` ADA tapi
/// bukan objek, kembalikan `biz` sendiri (`biz.has("challenge")`).
fn find_challenge(root: &Json) -> Option<Json> {
    let data = opt_obj(root, "data")?;
    let biz = opt_obj(data, "biz_data").unwrap_or(data);
    if let Some(ch) = opt_obj(biz, "challenge") {
        return Some(ch.clone());
    }
    match biz {
        Json::Object(m) if m.contains_key("challenge") => Some(biz.clone()),
        _ => None,
    }
}

fn op_find_challenge(req: &Json) -> Result<String, String> {
    let root_str = get_str(req, "root")?;
    let root: Json =
        serde_json::from_str(root_str).map_err(|_| "root bukan JSON".to_string())?;
    Ok(json!({"ok": true, "value": find_challenge(&root)}).to_string())
}

// ------------------------------------------------------------ find_file_id --

/// `DsApiClient.findFileId` 1:1:
/// `biz.id | biz.file_id | biz.file.id | biz.uploaded_file.id`.
/// PERHATIAN: bila `biz.file` ADA sebagai objek tapi `id`-nya blank,
/// hasilnya null — `uploaded_file` TIDAK dicoba (Kotlin memakai
/// `optJSONObject("file") ?: optJSONObject("uploaded_file")` sekali).
fn find_file_id(root: &Json) -> Option<String> {
    let data = opt_obj(root, "data")?;
    let biz = opt_obj(data, "biz_data").unwrap_or(data);
    if let Some(id) = opt_string_non_blank(biz.get("id")) {
        return Some(id);
    }
    if let Some(id) = opt_string_non_blank(biz.get("file_id")) {
        return Some(id);
    }
    let f = opt_obj(biz, "file").or_else(|| opt_obj(biz, "uploaded_file"))?;
    opt_string_non_blank(f.get("id"))
}

fn op_find_file_id(req: &Json) -> Result<String, String> {
    let root_str = get_str(req, "root")?;
    let root: Json =
        serde_json::from_str(root_str).map_err(|_| "root bukan JSON".to_string())?;
    Ok(json!({"ok": true, "value": find_file_id(&root)}).to_string())
}

// -------------------------------------------------------------- est_tokens --

/// `Math.round(Double)` Java: `floor(x + 0.5)`; NaN -> 0; ±inf -> ±MAX.
/// (BEDA dari `f64::round` Rust untuk negatif: -1.5 -> -1 di Java,
/// -2 di Rust — dipakai `floor` eksplisit.)
fn java_round(x: f64) -> i64 {
    if x.is_nan() {
        return 0;
    }
    if x == f64::INFINITY {
        return i64::MAX;
    }
    if x == f64::NEG_INFINITY {
        return i64::MIN;
    }
    (x + 0.5).floor() as i64
}

/// `DsApiClient.estTokens` 1:1: `chars <= 0 -> 0`, selain itu
/// `maxOf(1, Math.round(chars / perChar))`.
fn est_tokens(chars: i64, per_char: f64) -> i64 {
    if chars <= 0 {
        0
    } else {
        java_round(chars as f64 / per_char).max(1)
    }
}

fn op_est_tokens(req: &Json) -> Result<String, String> {
    let chars = get_i64(req, "chars", 0)?;
    let per_char = req
        .get("per_char")
        .and_then(|v| v.as_f64())
        .ok_or_else(|| "missing/invalid per_char".to_string())?;
    Ok(json!({"ok": true, "value": est_tokens(chars, per_char)}).to_string())
}

// ----------------------------------------------------------- safe_file_name --

/// `DsApiClient.uploadFile` sanitasi nama: tiap satuan UTF-16 di luar
/// 32..126 atau `"` -> `_`; hasil blank -> `"file.bin"`.
fn safe_file_name(name: &str) -> String {
    let mapped: Vec<u16> = name
        .encode_utf16()
        .map(|u| {
            if (32..=126).contains(&u) && u != 34 {
                u
            } else {
                95
            }
        })
        .collect();
    let s = String::from_utf16_lossy(&mapped);
    if is_blank_java(&s) {
        "file.bin".to_string()
    } else {
        s
    }
}

fn op_safe_file_name(req: &Json) -> Result<String, String> {
    let name = get_str(req, "name")?;
    Ok(json!({"ok": true, "value": safe_file_name(name)}).to_string())
}

// -------------------------------------------------------------- upload_file --

/// Boundary multipart TETAP — sama persis dengan `DsApiClient.uploadFile`
/// (server tidak peduli nilainya, tapi wire harus stabil + teruji).
const BOUNDARY: &str = "----WebKitFormBoundaryTB0pXOQR2RL219Hu";

/// POST multipart `/api/v0/file/upload_file` via ureq.
/// Request: `{"op":"upload_file","url":...,"headers":{...},
/// "file_b64":"...","file_name":"...","mime":"...",
/// "connect_ms":20000,"read_ms":120000}`.
/// Respons: `{"ok":true,"code":...,"body":...}` (non-2xx TETAP ok:true —
/// Kotlin yang memutuskan throw) atau `{"ok":false,"error":...}` bila
/// transport gagal (fallback Kotlin, saklar tetap hidup).
fn op_upload_file(req: &Json) -> Result<String, String> {
    let url = get_str(req, "url")?;
    if !(url.starts_with("http://") || url.starts_with("https://")) {
        return Err("bad url".to_string());
    }
    let file_b64 = get_str(req, "file_b64")?;
    let file_bytes = B64
        .decode(file_b64)
        .map_err(|_| "bad base64".to_string())?;
    let file_name = get_str(req, "file_name")?;
    let mime = get_str(req, "mime")?;
    let connect_ms = get_i64(req, "connect_ms", 20000)?;
    let read_ms = get_i64(req, "read_ms", 120000)?;
    if connect_ms < 0 || read_ms < 0 {
        return Err("negative timeout".to_string());
    }
    let mut headers: Vec<(String, String)> = Vec::new();
    if let Some(Json::Object(h)) = req.get("headers") {
        for (k, v) in h {
            let val = v
                .as_str()
                .ok_or_else(|| "header value must be a string".to_string())?;
            if !valid_header_name(k) || !valid_header_value(val) {
                return Err("bad header".to_string());
            }
            headers.push((k.clone(), val.to_string()));
        }
    }
    let safe = safe_file_name(file_name);
    let mut body: Vec<u8> = Vec::new();
    body.extend_from_slice(
        format!(
            "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{safe}\"\r\nContent-Type: {mime}\r\n\r\n"
        )
        .as_bytes(),
    );
    body.extend_from_slice(&file_bytes);
    body.extend_from_slice(format!("\r\n--{BOUNDARY}--\r\n").as_bytes());

    let mut builder = ureq::AgentBuilder::new();
    if connect_ms > 0 {
        builder = builder.timeout_connect(Duration::from_millis(connect_ms as u64));
    }
    if read_ms > 0 {
        builder = builder.timeout_read(Duration::from_millis(read_ms as u64));
    }
    let agent = builder.build();
    let mut r = agent.request("POST", url);
    for (k, v) in &headers {
        // Content-Type dari pemanggil (application/json) DITIMPA multipart
        // — sama seperti Kotlin yang memanggil setRequestProperty
        // Content-Type multipart SESUDAH headers().
        if k.eq_ignore_ascii_case("content-type") {
            continue;
        }
        r = r.set(k, v);
    }
    r = r.set(
        "Content-Type",
        &format!("multipart/form-data; boundary={BOUNDARY}"),
    );
    r = r.set("x-file-size", &file_bytes.len().to_string());
    let result = r.send_bytes(&body);
    match result {
        Ok(resp) => finish_upload_response(resp),
        Err(ureq::Error::Status(_, resp)) => finish_upload_response(resp),
        Err(e) => Ok(err_json(
            &e.to_string().chars().take(300).collect::<String>(),
        )),
    }
}

fn finish_upload_response(resp: ureq::Response) -> Result<String, String> {
    let code = resp.status();
    let mut bytes: Vec<u8> = Vec::new();
    resp.into_reader()
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    let body = String::from_utf8_lossy(&bytes).into_owned();
    Ok(json!({"ok": true, "code": code, "body": body}).to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::thread;

    fn req_json(v: Json) -> String {
        ds_api_json(&v.to_string()).expect("ds_api_json ok")
    }

    fn call(op: &str, extra: Json) -> Json {
        let mut m = serde_json::Map::new();
        m.insert("op".to_string(), Json::String(op.to_string()));
        if let Json::Object(e) = extra {
            for (k, v) in e {
                m.insert(k, v);
            }
        }
        serde_json::from_str(&req_json(Json::Object(m))).expect("resp json")
    }

    fn root(s: &str) -> Json {
        json!({"root": s})
    }

    #[test]
    fn find_session_id_cases() {
        let f = |s: &str| call("find_session_id", root(s))["value"].clone();
        assert_eq!(
            f(r#"{"data":{"biz_data":{"chat_session":{"id":"sess-123"}}}}"#),
            json!("sess-123")
        );
        // biz.id fallback bila chat_session hilang.
        assert_eq!(
            f(r#"{"data":{"biz_data":{"id":"biz-9"}}}"#),
            json!("biz-9")
        );
        // biz_data hilang -> data sendiri.
        assert_eq!(
            f(r#"{"data":{"chat_session":{"id":"d-1"}}}"#),
            json!("d-1")
        );
        assert_eq!(f(r#"{"data":{"id":"d-2"}}"#), json!("d-2"));
        // id blank -> fallback biz.id; keduanya blank -> null.
        assert_eq!(
            f(r#"{"data":{"biz_data":{"chat_session":{"id":"  "},"id":"biz-1"}}}"#),
            json!("biz-1")
        );
        assert_eq!(
            f(r#"{"data":{"biz_data":{"chat_session":{"id":""},"id":""}}}"#),
            Json::Null
        );
        // data hilang / bukan objek -> null.
        assert_eq!(f(r#"{"x":1}"#), Json::Null);
        assert_eq!(f(r#"{"data":"teks"}"#), Json::Null);
        // chat_session bukan objek -> biz.id.
        assert_eq!(
            f(r#"{"data":{"biz_data":{"chat_session":"x","id":"b-3"}}}"#),
            json!("b-3")
        );
        // id angka -> koersi optString ("123").
        assert_eq!(
            f(r#"{"data":{"biz_data":{"chat_session":{"id":123}}}}"#),
            json!("123")
        );
        // id null -> fallback.
        assert_eq!(
            f(r#"{"data":{"biz_data":{"chat_session":{"id":null},"id":"b-4"}}}"#),
            json!("b-4")
        );
    }

    #[test]
    fn find_challenge_cases() {
        let f = |s: &str| call("find_challenge", root(s))["value"].clone();
        let ch = json!({"algorithm":"DeepSeekHashV1","challenge":"abc"});
        assert_eq!(
            f(r#"{"data":{"biz_data":{"challenge":{"algorithm":"DeepSeekHashV1","challenge":"abc"}}}}"#),
            ch
        );
        // biz_data hilang -> data sendiri.
        assert_eq!(
            f(r#"{"data":{"challenge":{"algorithm":"X"}}}"#),
            json!({"algorithm":"X"})
        );
        // challenge ADA tapi bukan objek -> kembalikan biz sendiri.
        assert_eq!(
            f(r#"{"data":{"biz_data":{"challenge":"mentah","salt":"s1"}}}"#),
            json!({"challenge":"mentah","salt":"s1"})
        );
        // challenge null -> biz (has == true).
        assert_eq!(
            f(r#"{"data":{"biz_data":{"challenge":null,"salt":"s2"}}}"#),
            json!({"challenge":null,"salt":"s2"})
        );
        // tanpa challenge -> null; tanpa data -> null.
        assert_eq!(f(r#"{"data":{"biz_data":{"salt":"s"}}}"#), Json::Null);
        assert_eq!(f(r#"{"x":1}"#), Json::Null);
    }

    #[test]
    fn find_file_id_cases() {
        let f = |s: &str| call("find_file_id", root(s))["value"].clone();
        assert_eq!(f(r#"{"data":{"biz_data":{"id":"f-1"}}}"#), json!("f-1"));
        assert_eq!(
            f(r#"{"data":{"biz_data":{"file_id":"f-2"}}}"#),
            json!("f-2")
        );
        assert_eq!(
            f(r#"{"data":{"biz_data":{"file":{"id":"f-3"}}}}"#),
            json!("f-3")
        );
        assert_eq!(
            f(r#"{"data":{"biz_data":{"uploaded_file":{"id":"f-4"}}}}"#),
            json!("f-4")
        );
        // file ADA tapi id blank -> null (uploaded_file TIDAK dicoba).
        assert_eq!(
            f(r#"{"data":{"biz_data":{"file":{"id":""},"uploaded_file":{"id":"f-5"}}}}"#),
            Json::Null
        );
        // file bukan objek -> uploaded_file dicoba.
        assert_eq!(
            f(r#"{"data":{"biz_data":{"file":"x","uploaded_file":{"id":"f-6"}}}}"#),
            json!("f-6")
        );
        // id blank -> file_id; semua kosong -> null.
        assert_eq!(
            f(r#"{"data":{"biz_data":{"id":"","file_id":"f-7"}}}"#),
            json!("f-7")
        );
        assert_eq!(f(r#"{"data":{"biz_data":{}}}"#), Json::Null);
        assert_eq!(f(r#"{"x":1}"#), Json::Null);
    }

    #[test]
    fn est_tokens_cases() {
        let e = |chars: i64, per: f64| {
            call(
                "est_tokens",
                json!({"chars": chars, "per_char": per}),
            )["value"]
                .as_i64()
                .unwrap()
        };
        assert_eq!(e(340, 3.4), 100); // 340/3.4 = 100.0
        assert_eq!(e(0, 3.4), 0);
        assert_eq!(e(-5, 3.4), 0);
        assert_eq!(e(1, 3.4), 1); // 0.29 -> round 0 -> maxOf 1
        assert_eq!(e(2, 3.4), 1); // 0.59 -> round 1
        assert_eq!(e(5, 3.4), 1); // 1.47 -> 1
        assert_eq!(e(6, 3.4), 2); // 1.76 -> 2
        // .5 membulat ke ATAS (floor(x+0.5)): 5/2 = 2.5 -> 3.
        assert_eq!(e(5, 2.0), 3);
        // BEDA dari round Rust untuk negatif — tapi chars>0 & per>0
        // selalu positif; per negatif -> Java floor(-0.5+0.5)=0 -> maxOf 1.
        assert_eq!(e(10, -2.0), 1); // -5.0 -> round -5 -> maxOf 1
        // per_char 0 -> +inf -> MAX.
        assert_eq!(e(10, 0.0), i64::MAX);
        // java_round langsung: NaN -> 0, -1.5 -> -1 (bukan -2).
        assert_eq!(java_round(f64::NAN), 0);
        assert_eq!(java_round(-1.5), -1);
        assert_eq!(java_round(2.5), 3);
        assert_eq!(java_round(f64::INFINITY), i64::MAX);
    }

    #[test]
    fn safe_file_name_cases() {
        let s = |n: &str| {
            call("safe_file_name", json!({"name": n}))["value"]
                .as_str()
                .unwrap()
                .to_string()
        };
        assert_eq!(s("foto.jpg"), "foto.jpg");
        assert_eq!(s("a\"b"), "a_b");
        assert_eq!(s("a\tb"), "a_b");
        assert_eq!(s("café.png"), "caf_.png");
        // Satu emoji = 2 surrogate UTF-16 -> "__".
        assert_eq!(s("a\u{1F600}b"), "a__b");
        assert_eq!(s("   "), "file.bin");
        assert_eq!(s(""), "file.bin");
        assert_eq!(s("a b"), "a b");
    }

    /// Server HTTP mentah sekali-pakai di 127.0.0.1:0 (pola http.rs).
    fn serve_once<F>(handler: F) -> u16
    where
        F: FnOnce(String, Vec<u8>) -> String + Send + 'static,
    {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let port = listener.local_addr().expect("addr").port();
        thread::spawn(move || {
            if let Ok((mut stream, _)) = listener.accept() {
                // Baca header dulu (sampai \r\n\r\n), lalu body
                // sepanjang Content-Length.
                let mut head = Vec::new();
                let mut byte = [0u8; 1];
                while head.len() < 65536 {
                    match stream.read(&mut byte) {
                        Ok(0) | Err(_) => break,
                        Ok(_) => {
                            head.push(byte[0]);
                            if head.ends_with(b"\r\n\r\n") {
                                break;
                            }
                        }
                    }
                }
                let head_text = String::from_utf8_lossy(&head).into_owned();
                let clen: usize = head_text
                    .lines()
                    .find_map(|l| {
                        let low = l.to_ascii_lowercase();
                        low.strip_prefix("content-length:")
                            .and_then(|v| v.trim().parse().ok())
                    })
                    .unwrap_or(0);
                let mut body = vec![0u8; clen];
                let _ = stream.read_exact(&mut body);
                let resp = handler(head_text, body);
                let _ = stream.write_all(resp.as_bytes());
                let _ = stream.flush();
            }
        });
        port
    }

    fn upload_call(url: &str, file_b64: &str, name: &str, mime: &str) -> Json {
        call(
            "upload_file",
            json!({
                "url": url,
                "headers": {"Authorization": "Bearer tok", "User-Agent": "UA-test", "Content-Type": "application/json"},
                "file_b64": file_b64, "file_name": name, "mime": mime,
                "connect_ms": 3000, "read_ms": 3000,
            }),
        )
    }

    #[test]
    fn upload_file_wire_exact() {
        let port = serve_once(|head, body| {
            let low = head.to_ascii_lowercase();
            assert!(low.contains("content-type: multipart/form-data; boundary=----webkitformboundarytb0pxoqr2rl219hu"),
                "Content-Type multipart harus menimpa application/json: {head}");
            assert!(low.contains("x-file-size: 4"), "x-file-size: {head}");
            assert!(low.contains("authorization: bearer tok"), "auth: {head}");
            // Body: head + 4 byte biner (incl. 0x00, 0xFF) + tail.
            let expect_head = b"------WebKitFormBoundaryTB0pXOQR2RL219Hu\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a__b.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n";
            assert!(body.starts_with(expect_head), "multipart head: {:?}", &body[..body.len().min(200)]);
            assert_eq!(&body[expect_head.len()..expect_head.len() + 4], &[0u8, 1, 2, 255]);
            assert!(body.ends_with(b"\r\n------WebKitFormBoundaryTB0pXOQR2RL219Hu--\r\n"), "multipart tail");
            let b = "{\"code\":0,\"data\":{\"biz_data\":{\"id\":\"up-1\",\"status\":\"SUCCESS\"}}}";
            format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                b.len(), b
            )
        });
        let r = upload_call(
            &format!("http://127.0.0.1:{port}/api/v0/file/upload_file"),
            "AAEC/w==",
            "a\u{1F600}b.bin",
            "application/octet-stream",
        );
        assert_eq!(r["ok"], true);
        assert_eq!(r["code"], 200);
        assert!(r["body"].as_str().unwrap().contains("\"id\":\"up-1\""));
    }

    #[test]
    fn upload_file_non2xx_and_transport_error() {
        let port = serve_once(|_, _| {
            let b = "{\"err\":\"nope\"}";
            format!(
                "HTTP/1.1 500 Internal Server Error\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                b.len(), b
            )
        });
        // Non-2xx TETAP ok:true + code + body (Kotlin yang melempar).
        let r = upload_call(
            &format!("http://127.0.0.1:{port}/api/v0/file/upload_file"),
            "aGk=",
            "f.bin",
            "text/plain",
        );
        assert_eq!(r["ok"], true);
        assert_eq!(r["code"], 500);
        assert_eq!(r["body"], "{\"err\":\"nope\"}");
        // Transport gagal (port 1 refused) -> ok:false.
        let r2 = upload_call(
            "http://127.0.0.1:1/api/v0/file/upload_file",
            "aGk=",
            "f.bin",
            "text/plain",
        );
        assert_eq!(r2["ok"], false);
        // Base64 tak-valid -> ok:false (bukan panic).
        let r3 = upload_call(
            &format!("http://127.0.0.1:{port}/api/v0/file/upload_file"),
            "!!!",
            "f.bin",
            "text/plain",
        );
        assert_eq!(r3["ok"], false);
    }

    #[test]
    fn bad_ops_are_ok_false() {
        let bad = ds_api_json(r#"{"op":"nope"}"#).expect("json");
        let v: Json = serde_json::from_str(&bad).expect("json");
        assert_eq!(v["ok"], false);
        let bad2 = ds_api_json("bukan json").expect("json");
        let v2: Json = serde_json::from_str(&bad2).expect("json");
        assert_eq!(v2["ok"], false);
        let bad3 = ds_api_json(r#"{"op":"find_session_id","root":"bukan json"}"#).expect("json");
        let v3: Json = serde_json::from_str(&bad3).expect("json");
        assert_eq!(v3["ok"], false);
    }
}
