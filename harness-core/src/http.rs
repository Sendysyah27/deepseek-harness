//! Port HTTP clients slice 8 (vc324) — `OcClient` + `ApiClients` +
//! `McpHttp` + `SlotClient` (transport non-streaming) + `Gmail` (logika
//! murni) + `McpOAuth` (PKCE + form + POST token).
//!
//! Satu seam JSON in/out (`http_json`, op dispatch) di atas `ureq`
//! 2.12 (blocking, rustls + webpki-roots, direct tanpa proxy —
//! cocok dengan `OcClient` yang memakai `Proxy.NO_PROXY`; klien
//! lain memakai `URL.openConnection()` tanpa proxy eksplisit).
//! Fitur default ureq DIMATIKAN (tanpa gzip/charset/cookies/json)
//! supaya perilaku wire sedekat mungkin dengan `HttpURLConnection`
//! Kotlin (tidak ada `Accept-Encoding: gzip` tambahan, tidak ada
//! cookie jar). `tokio` TIDAK dipakai (peta §7: baru slice 9).
//!
//! Pemetaan perilaku Kotlin -> Rust (quirk DIREPLIKASI):
//! - Respons non-2xx TETAP dikembalikan (`ok:true` + code + body) —
//!   Kotlin yang memutuskan throw/return, sama seperti membaca
//!   `errorStream` (bukan `inputStream`) di Kotlin.
//! - Transport gagal (timeout, refused, DNS, TLS) -> `{"ok":false}`;
//!   pemanggil Kotlin fallback ke transport Kotlin (saklar TIDAK
//!   dimatikan untuk gagal transport — lihat BUILD-VC324.md §2.3).
//! - `cap_chars`: 0 = tanpa cap; >0 = baca maks itu char (OcClient:
//!   2.000.000 — replikasi batas baca Kotlin). Dihitung sesudah
//!   decode UTF-8-lossy (Kotlin menghitung char dari `BufferedReader`);
//!   untuk ASCII identik.
//! - `String.trim()` Kotlin = `kotlin_trim` (Java isWhitespace —
//!   NBSP tepi dibuang, pelajaran slice 4); `Regex("\\s+")` Java =
//!   ASCII `[ \t\n\x0B\f\r]` saja — keduanya direplika eksplisit,
//!   BUKAN `str::trim()` / `\s` regex Rust (Unicode-aware).
//! - `URLEncoder.encode` UTF-8: spasi -> `+`, `[A-Za-z0-9._*-]`
//!   lolos, sisanya `%XX` hex huruf besar.
//! - `JSONObject.optString` melakukan koersi (`toString()` untuk
//!   non-string) — direplika `opt_string_coerce` di sini.
//! - `extractResult` MCP: JSON langsung dulu, lalu pindai baris
//!   `data:` SSE; `result` TERAKHIR menang; `error` -> MCP error.
//! - `shapeFingerprint`: kunci JSON level-atas TERURUT; non-JSON /
//!   non-objek -> `"non-json"`.
//! - `decodeWord` Gmail (RFC 2047): kata B/Q tak-valid -> data
//!   mentah (bukan error); collapse spasi Java lalu `trim()` Kotlin.
//! - `fetchHeaders` Gmail TIDAK meng-unfold header lipat (tiap baris
//!   dicek sendiri, seperti Kotlin).
//!
//! Yang SENGAJA TIDAK di sini (tetap Kotlin, BUILD-VC324.md §2.3):
//! `OcClient.openEventStream` (handle hidup), transport IMAP/SMTP
//! Gmail (socket + SSLSocketFactory + STARTTLS), listener loopback
//! `McpOAuth` (ServerSocket + Intent), transport `postCompletion`
//! SlotClient (loop baca + `onPartial` — Rust tidak callback ke
//! Kotlin di slice awal, peta §3.4).

#![forbid(unsafe_code)]

use crate::ds_safety_strip::{java_is_whitespace, kotlin_trim};
use crate::sse;
use base64::{
    engine::general_purpose::{STANDARD as B64, URL_SAFE_NO_PAD as B64URL},
    Engine as _,
};
use regex::Regex;
use serde_json::{json, Map, Value as Json};
use sha2::{Digest, Sha256};
use std::io::Read;
use std::sync::OnceLock;
use std::time::Duration;

/// Satu entry point seam: request JSON -> respons JSON (String).
/// Error op (request malformed, op tak-dikenal, input tak-valid)
/// dikembalikan sebagai `{"ok":false,"error":...}` — BUKAN `Err` —
/// supaya boundary JNI mengembalikan `null` HANYA bila panic/error
/// FFI (pemanggil Kotlin membedakan "operasi gagal -> fallback
/// per-panggilan, saklar hidup" dari "seam rusak -> matikan saklar").
pub fn http_json(req_str: &str) -> Result<String, String> {
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
        "request" => op_request(&req),
        "mcp_extract" => op_mcp_extract(&req),
        "shape_fingerprint" => op_shape_fingerprint(&req),
        "decode_word" => op_decode_word(&req),
        "gmail_headers" => op_gmail_headers(&req),
        "pkce_challenge" => op_pkce_challenge(&req),
        "form_encode" => op_form_encode(&req),
        "form_body" => op_form_body(&req),
        "oauth_auth_url" => op_oauth_auth_url(&req),
        "oauth_parse_callback" => op_oauth_parse_callback(&req),
        "sse_apply" => sse::sse_apply(&req),
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

/// Koersi `JSONObject.optString` org.json: null -> "", string -> diri
/// sendiri, number/bool/array/object -> `toString()` compact.
pub(crate) fn opt_string_coerce(v: Option<&Json>) -> String {
    match v {
        None | Some(Json::Null) => String::new(),
        Some(Json::String(s)) => s.clone(),
        Some(other) => other.to_string(),
    }
}

/// `String.contains(x, ignoreCase = true)` untuk needle ASCII.
pub(crate) fn contains_ignore_ascii_case(hay: &str, needle: &str) -> bool {
    hay.to_ascii_lowercase().contains(&needle.to_ascii_lowercase())
}

/// Java `Regex("\\s+")` = ASCII `[ \t\n\x0B\f\r]` saja.
fn java_is_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{000B}' | '\u{000C}' | '\r')
}

/// `String.isBlank()` Kotlin = kosong atau semua `isWhitespace()`.
fn kotlin_is_blank(s: &str) -> bool {
    s.chars().all(java_is_whitespace)
}

// ---------------------------------------------------------- op: request --

const METHODS: [&str; 8] = [
    "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE",
];

/// Dipakai ulang `ds_api` (upload multipart): nama header valid.
pub(crate) fn valid_header_name(name: &str) -> bool {
    !name.is_empty()
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&b))
}

/// Dipakai ulang `ds_api` (upload multipart): nilai header valid.
pub(crate) fn valid_header_value(value: &str) -> bool {
    // Header value tidak boleh mengandung CTL / CR / LF (ureq juga
    // menolaknya; validasi di sini supaya gagal rapi, bukan panic).
    !value.chars().any(|c| (c as u32) < 0x20 || (c as u32) == 0x7F)
}

fn read_capped(reader: impl Read, cap_chars: i64) -> Result<String, String> {
    let mut bytes: Vec<u8> = Vec::new();
    let limit: u64 = if cap_chars > 0 {
        // Maks 4 byte UTF-8 per char; decode dulu, potong per char sesudahnya.
        (cap_chars as u64).saturating_mul(4)
    } else {
        u64::MAX
    };
    reader
        .take(limit)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    let text = String::from_utf8_lossy(&bytes);
    if cap_chars > 0 {
        Ok(text.chars().take(cap_chars as usize).collect())
    } else {
        Ok(text.into_owned())
    }
}

fn finish_response(
    resp: ureq::Response,
    code: u16,
    want_headers: &[String],
    cap_chars: i64,
) -> Result<String, String> {
    let mut headers = Map::new();
    for w in want_headers {
        if let Some(v) = resp.header(w) {
            headers.insert(w.clone(), Json::String(v.to_string()));
        }
    }
    let body = read_capped(resp.into_reader(), cap_chars)?;
    Ok(json!({"ok": true, "code": code, "body": body, "headers": headers}).to_string())
}

fn op_request(req: &Json) -> Result<String, String> {
    let method = get_str(req, "method")?;
    if !METHODS.contains(&method) {
        return Err("bad method".to_string());
    }
    let url = get_str(req, "url")?;
    if !(url.starts_with("http://") || url.starts_with("https://")) {
        return Err("bad url".to_string());
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
    let body: Option<String> = match req.get("body") {
        None | Some(Json::Null) => None,
        Some(Json::String(s)) => Some(s.clone()),
        Some(_) => return Err("body must be a string".to_string()),
    };
    let connect_ms = get_i64(req, "connect_ms", 4000)?;
    let read_ms = get_i64(req, "read_ms", 15000)?;
    let cap_chars = get_i64(req, "cap_chars", 0)?;
    if connect_ms < 0 || read_ms < 0 || cap_chars < 0 {
        return Err("negative timeout/cap".to_string());
    }
    let mut want_headers: Vec<String> = Vec::new();
    if let Some(Json::Array(a)) = req.get("want_headers") {
        for v in a {
            if let Some(s) = v.as_str() {
                want_headers.push(s.to_string());
            }
        }
    }
    let mut builder = ureq::AgentBuilder::new();
    if connect_ms > 0 {
        // connect_ms == 0: Kotlin = infinite; ureq default 30 dtk dipakai
        // (tidak ada pemanggil yang memakai 0 — dicatat di BUILD-VC324).
        builder = builder.timeout_connect(Duration::from_millis(connect_ms as u64));
    }
    if read_ms > 0 {
        // read_ms == 0: Kotlin = infinite; ureq default None = infinite.
        builder = builder.timeout_read(Duration::from_millis(read_ms as u64));
    }
    let agent = builder.build();
    let mut r = agent.request(method, url);
    for (k, v) in &headers {
        r = r.set(k, v);
    }
    let result = match &body {
        Some(b) => r.send_string(b),
        None => r.call(),
    };
    match result {
        Ok(resp) => {
            let code = resp.status();
            finish_response(resp, code, &want_headers, cap_chars)
        }
        Err(ureq::Error::Status(code, resp)) => {
            finish_response(resp, code, &want_headers, cap_chars)
        }
        Err(e) => Ok(err_json(
            &e.to_string().chars().take(300).collect::<String>(),
        )),
    }
}

// ------------------------------------------------------ op: mcp_extract --

/// `McpHttp.extractResult` 1:1. Keluar `{"ok":true,"result":<obj|null>}`
/// atau `{"ok":false,"error":"MCP error: ..."}`.
fn op_mcp_extract(req: &Json) -> Result<String, String> {
    let raw = get_str(req, "raw")?;
    let trimmed = kotlin_trim(raw);
    if trimmed.starts_with('{') {
        if let Ok(Json::Object(o)) = serde_json::from_str::<Json>(trimmed) {
            if o.contains_key("result") {
                let result = match o.get("result") {
                    Some(Json::Object(_)) => o.get("result").cloned(),
                    _ => None,
                };
                return Ok(json!({"ok": true, "result": result}).to_string());
            }
            if let Some(Json::Object(_)) = o.get("error") {
                let err_text = o
                    .get("error")
                    .map(|e| e.to_string())
                    .unwrap_or_default();
                let cut: String = err_text.chars().take(300).collect();
                return Ok(err_json(&format!("MCP error: {cut}")));
            }
        }
        // Parse gagal / tanpa result+error -> jatuh ke pindai SSE
        // (persis `catch` Kotlin yang menelan kecuali MCP error).
    }
    let mut last: Option<Json> = None;
    for line in raw.lines() {
        if !line.starts_with("data:") {
            continue;
        }
        let d = kotlin_trim(&line["data:".len()..]);
        if !d.starts_with('{') {
            continue;
        }
        let parsed = match serde_json::from_str::<Json>(d) {
            Ok(Json::Object(o)) => o,
            _ => continue,
        };
        if parsed.contains_key("result") {
            last = match parsed.get("result") {
                Some(Json::Object(_)) => parsed.get("result").cloned(),
                _ => None,
            };
        }
        if let Some(Json::Object(_)) = parsed.get("error") {
            let err_text = parsed
                .get("error")
                .map(|e| e.to_string())
                .unwrap_or_default();
            let cut: String = err_text.chars().take(300).collect();
            return Ok(err_json(&format!("MCP error: {cut}")));
        }
    }
    Ok(json!({"ok": true, "result": last}).to_string())
}

// ------------------------------------------------- op: shape_fingerprint --

/// `ApiClients.shapeFingerprint` 1:1.
fn op_shape_fingerprint(req: &Json) -> Result<String, String> {
    let body = get_str(req, "body")?;
    let value = match serde_json::from_str::<Json>(body) {
        Ok(Json::Object(o)) => {
            let mut keys: Vec<&String> = o.keys().collect();
            keys.sort();
            format!(
                "json:{}",
                keys.iter()
                    .map(|k| k.as_str())
                    .collect::<Vec<_>>()
                    .join(",")
            )
        }
        _ => "non-json".to_string(),
    };
    Ok(json!({"ok": true, "value": value}).to_string())
}

// -------------------------------------------------------- op: decode_word --

fn encoded_word_re() -> &'static Option<Regex> {
    static R: OnceLock<Option<Regex>> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"=\?[^?]+\?([bBqQ])\?([^?]*)\?=").ok())
}

/// `Gmail.decodeWord` 1:1 (RFC 2047 B/Q + collapse spasi Java + trim Kotlin).
pub(crate) fn decode_word(s: &str) -> String {
    let mut out = s.to_string();
    let Some(re) = encoded_word_re().as_ref() else {
        // Regex literal lolos tes; bila kompilasi gagal, degradasi aman:
        // kembalikan input + collapse + trim (tanpa decode kata).
        return finish_decode_word(&out);
    };
    loop {
        let Some(caps) = re.captures(&out) else { break };
        let Some(m) = caps.get(0) else { break };
        let enc = caps.get(1).map(|g| g.as_str().to_ascii_uppercase());
        let data = caps.get(2).map(|g| g.as_str()).unwrap_or("");
        let decoded: String = match enc.as_deref() {
            Some("B") => {
                // android.util.Base64 DEFAULT: lewati whitespace dulu.
                let compact: String =
                    data.chars().filter(|c| !c.is_ascii_whitespace()).collect();
                match B64.decode(&compact) {
                    Ok(bytes) => String::from_utf8_lossy(&bytes).into_owned(),
                    Err(_) => data.to_string(),
                }
            }
            Some("Q") => {
                let mut bytes: Vec<u8> = Vec::new();
                let mut ok = true;
                let mut i = 0;
                let b = data.as_bytes();
                while i < data.len() {
                    if data.as_bytes()[i] == b'_' {
                        bytes.push(32);
                        i += 1;
                    } else if data.as_bytes()[i] == b'=' && i + 2 < data.len() {
                        match u8::from_str_radix(&data[i + 1..i + 3], 16) {
                            Ok(v) => {
                                bytes.push(v);
                                i += 3;
                            }
                            Err(_) => {
                                ok = false;
                                break;
                            }
                        }
                    } else {
                        bytes.push(b[i]);
                        i += 1;
                    }
                }
                if ok {
                    String::from_utf8_lossy(&bytes).into_owned()
                } else {
                    data.to_string()
                }
            }
            _ => data.to_string(),
        };
        out = format!("{}{}{}", &out[..m.start()], decoded, &out[m.end()..]);
    }
    finish_decode_word(&out)
}

fn finish_decode_word(out: &str) -> String {
    // `replace(Regex("\\s+"), " ")` Java (\s ASCII) lalu `trim()` Kotlin.
    let mut collapsed = String::new();
    let mut in_run = false;
    for c in out.chars() {
        if java_is_space(c) {
            if !in_run {
                collapsed.push(' ');
                in_run = true;
            }
        } else {
            collapsed.push(c);
            in_run = false;
        }
    }
    kotlin_trim(&collapsed).to_string()
}

fn op_decode_word(req: &Json) -> Result<String, String> {
    let text = get_str(req, "text")?;
    Ok(json!({"ok": true, "value": decode_word(text)}).to_string())
}

// ------------------------------------------------------ op: gmail_headers --

/// `Gmail.fetchHeaders` untuk SATU id: baris mentah FETCH -> satu
/// entri `"- [date] from — subject"`. Dipanggil per id oleh Kotlin
/// (yang menggabung + trim akhir seperti `fetchHeaders` lama).
fn op_gmail_headers(req: &Json) -> Result<String, String> {
    let lines = match req.get("lines") {
        Some(Json::Array(a)) => a,
        _ => return Err("lines must be an array".to_string()),
    };
    let mut from = String::new();
    let mut subj = String::new();
    let mut date = String::new();
    for lv in lines {
        let Some(l) = lv.as_str() else { continue };
        let low = l.to_ascii_lowercase();
        if low.starts_with("from:") {
            from = decode_word(l.get(5..).unwrap_or(""));
        } else if low.starts_with("subject:") {
            subj = decode_word(l.get(8..).unwrap_or(""));
        } else if low.starts_with("date:") {
            let d = kotlin_trim(l.get(5..).unwrap_or(""));
            date = d.chars().take(31).collect();
        }
    }
    let subj = if kotlin_is_blank(&subj) {
        "(tanpa subjek)".to_string()
    } else {
        subj
    };
    Ok(json!({"ok": true, "value": format!("- [{date}] {from} — {subj}")}).to_string())
}

// ----------------------------------------------------- op: pkce_challenge --

/// `McpOAuth.challenge` 1:1 (SHA-256 verifier ASCII -> b64url tanpa pad).
fn op_pkce_challenge(req: &Json) -> Result<String, String> {
    let verifier = get_str(req, "verifier")?;
    let bytes: Vec<u8> = verifier
        .chars()
        .map(|c| {
            if (c as u32) < 128 {
                c as u8
            } else {
                b'?'
            }
        })
        .collect();
    let digest = Sha256::digest(&bytes);
    Ok(json!({"ok": true, "value": B64URL.encode(digest)}).to_string())
}

// -------------------------------------------------------- op: form_encode --

/// `URLEncoder.encode(s, "UTF-8")` 1:1.
pub(crate) fn form_encode(s: &str) -> String {
    let mut out = String::new();
    for &b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => {
                out.push(b as char)
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

fn op_form_encode(req: &Json) -> Result<String, String> {
    let text = get_str(req, "text")?;
    Ok(json!({"ok": true, "value": form_encode(text)}).to_string())
}

/// `postFormRaw`: gabung pasangan `[k, v]` (ARRAY — urutan dipertahankan;
/// `JSONObject` org.json tidak menjamin urutan) jadi `k=v&k=v`.
fn op_form_body(req: &Json) -> Result<String, String> {
    let pairs = match req.get("params") {
        Some(Json::Array(a)) => a,
        _ => return Err("params must be an array of [k,v]".to_string()),
    };
    let mut parts: Vec<String> = Vec::new();
    for p in pairs {
        let (Some(k), Some(v)) = (
            p.get(0).and_then(|x| x.as_str()),
            p.get(1).and_then(|x| x.as_str()),
        ) else {
            return Err("params entries must be [k,v] strings".to_string());
        };
        parts.push(format!("{}={}", form_encode(k), form_encode(v)));
    }
    Ok(json!({"ok": true, "value": parts.join("&")}).to_string())
}

// ------------------------------------------------------ op: oauth_auth_url --

fn op_oauth_auth_url(req: &Json) -> Result<String, String> {
    let auth_url = get_str(req, "auth_url")?;
    let client_id = get_str(req, "client_id")?;
    let redirect_uri = get_str(req, "redirect_uri")?;
    let scopes = get_str(req, "scopes")?;
    let state = get_str(req, "state")?;
    let challenge = get_str(req, "challenge")?;
    let offline = req
        .get("offline")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let mut q = String::new();
    q.push_str(auth_url);
    q.push_str("?response_type=code&client_id=");
    q.push_str(&form_encode(client_id));
    q.push_str("&redirect_uri=");
    q.push_str(&form_encode(redirect_uri));
    q.push_str("&scope=");
    q.push_str(&form_encode(scopes));
    q.push_str("&state=");
    q.push_str(&form_encode(state));
    q.push_str("&code_challenge=");
    q.push_str(&form_encode(challenge));
    q.push_str("&code_challenge_method=S256");
    if offline {
        q.push_str("&access_type=offline&prompt=consent");
    }
    Ok(json!({"ok": true, "value": q}).to_string())
}

// ------------------------------------------------- op: oauth_parse_callback --

/// `URLDecoder.decode(s, "UTF-8")`: `+` -> spasi, `%XX` -> byte;
/// `%` tak-valid -> Err (Kotlin melempar -> `onDone` gagal).
fn url_decode(s: &str) -> Result<String, String> {
    let mut bytes: Vec<u8> = Vec::new();
    let b = s.as_bytes();
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'+' => {
                bytes.push(b' ');
                i += 1;
            }
            b'%' => {
                if i + 2 >= b.len() {
                    return Err("bad percent encoding".to_string());
                }
                match u8::from_str_radix(&s[i + 1..i + 3], 16) {
                    Ok(v) => {
                        bytes.push(v);
                        i += 3;
                    }
                    Err(_) => return Err("bad percent encoding".to_string()),
                }
            }
            _ => {
                bytes.push(b[i]);
                i += 1;
            }
        }
    }
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

fn op_oauth_parse_callback(req: &Json) -> Result<String, String> {
    let query = get_str(req, "query")?;
    let mut params = Map::new();
    for part in query.split('&') {
        let mut kv = part.splitn(2, '=');
        if let (Some(k), Some(v)) = (kv.next(), kv.next()) {
            params.insert(k.to_string(), Json::String(url_decode(v)?));
        }
    }
    Ok(json!({"ok": true, "params": params}).to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::thread;

    /// Server HTTP mentah sekali-pakai di 127.0.0.1:0 — membalas respons
    /// yang ditulis handler, lalu menutup koneksi (Connection: close).
    fn serve_once<F>(handler: F) -> u16
    where
        F: FnOnce(String) -> String + Send + 'static,
    {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let port = listener.local_addr().expect("addr").port();
        thread::spawn(move || {
            if let Ok((mut stream, _)) = listener.accept() {
                let mut buf = [0u8; 8192];
                let n = stream.read(&mut buf).unwrap_or(0);
                let req_text = String::from_utf8_lossy(&buf[..n]).into_owned();
                let resp = handler(req_text);
                let _ = stream.write_all(resp.as_bytes());
                let _ = stream.flush();
            }
        });
        port
    }

    fn req_json(v: Json) -> String {
        http_json(&v.to_string()).expect("http_json ok")
    }

    fn call_request(url: &str, extra: Json) -> Json {
        let mut m = Map::new();
        m.insert("op".to_string(), Json::String("request".to_string()));
        m.insert("method".to_string(), Json::String("GET".to_string()));
        m.insert("url".to_string(), Json::String(url.to_string()));
        m.insert("connect_ms".to_string(), Json::Number(3000.into()));
        m.insert("read_ms".to_string(), Json::Number(3000.into()));
        if let Json::Object(e) = extra {
            for (k, v) in e {
                m.insert(k, v);
            }
        }
        serde_json::from_str(&req_json(Json::Object(m))).expect("resp json")
    }

    #[test]
    fn request_get_echo_headers_body() {
        let port = serve_once(|req| {
            let body = "{\"echo\":true}";
            let low = req.to_ascii_lowercase();
            let has_ct = low.contains("content-type: application/json");
            let has_x = low.contains("x-test: abc");
            assert!(has_ct, "Content-Type json harus dikirim: {req}");
            assert!(has_x, "header custom harus dikirim: {req}");
            format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nRetry-After: 7\r\nConnection: close\r\n\r\n{}",
                body.len(),
                body
            )
        });
        let r = call_request(
            &format!("http://127.0.0.1:{port}/x"),
            json!({"headers": {"Content-Type": "application/json", "X-Test": "abc"}, "want_headers": ["Retry-After"]}),
        );
        assert_eq!(r["ok"], true);
        assert_eq!(r["code"], 200);
        assert_eq!(r["body"], "{\"echo\":true}");
        assert_eq!(r["headers"]["Retry-After"], "7");
    }

    #[test]
    fn request_post_body_and_error_status() {
        let port = serve_once(|req| {
            assert!(req.starts_with("POST /s HTTP/1.1"), "method+path: {req}");
            let body = "{\"err\":\"nope\"}";
            format!(
                "HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                body.len(),
                body
            )
        });
        let r = call_request(
            &format!("http://127.0.0.1:{port}/s"),
            json!({"method": "POST", "body": "{\"a\":1}", "headers": {"Content-Type": "application/json"}}),
        );
        // Non-2xx TETAP ok:true + code + body (Kotlin baca errorStream).
        assert_eq!(r["ok"], true);
        assert_eq!(r["code"], 404);
        assert_eq!(r["body"], "{\"err\":\"nope\"}");
    }

    #[test]
    fn request_transport_error_is_ok_false() {
        // Port 1 di loopback: refused (bukan panic, bukan crash).
        let r = call_request("http://127.0.0.1:1/x", json!({}));
        assert_eq!(r["ok"], false);
        assert!(r["error"].is_string());
    }

    #[test]
    fn request_bad_url_and_method_are_ok_false() {
        // Error op -> {"ok":false} (bukan Err) supaya boundary JNI
        // mengembalikan null HANYA bila panic (lihat http_json).
        let bad_url = http_json(r#"{"op":"request","method":"GET","url":"ftp://x/y"}"#)
            .expect("http_json ok");
        let v: Json = serde_json::from_str(&bad_url).expect("json");
        assert_eq!(v["ok"], false);
        let bad_method = http_json(r#"{"op":"request","method":"GE T","url":"http://127.0.0.1:1/"}"#)
            .expect("http_json ok");
        let v: Json = serde_json::from_str(&bad_method).expect("json");
        assert_eq!(v["ok"], false);
        let bad_op = http_json(r#"{"op":"nope"}"#).expect("http_json ok");
        let v: Json = serde_json::from_str(&bad_op).expect("json");
        assert_eq!(v["ok"], false);
    }

    #[test]
    fn request_cap_chars_truncates() {
        let port = serve_once(|_| {
            let body = "0123456789ABCDEF";
            format!(
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                body.len(),
                body
            )
        });
        let r = call_request(
            &format!("http://127.0.0.1:{port}/"),
            json!({"cap_chars": 10}),
        );
        assert_eq!(r["body"], "0123456789");
    }

    #[test]
    fn request_timeout_is_ok_false() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let port = listener.local_addr().expect("addr").port();
        thread::spawn(move || {
            // Terima tapi JANGAN balas — read timeout harus memutus.
            if let Ok((_s, _)) = listener.accept() {
                thread::sleep(Duration::from_secs(5));
            }
        });
        let r = call_request(
            &format!("http://127.0.0.1:{port}/slow"),
            json!({"read_ms": 300}),
        );
        assert_eq!(r["ok"], false);
    }

    #[test]
    fn mcp_extract_direct_and_sse() {
        let direct = req_json(
            json!({"op": "mcp_extract", "raw": "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}"}),
        );
        let v: Json = serde_json::from_str(&direct).expect("json");
        assert_eq!(v["ok"], true);
        assert_eq!(v["result"]["tools"], json!([]));
        // Error langsung.
        let err = req_json(
            json!({"op": "mcp_extract", "raw": "{\"error\":{\"code\":-1,\"message\":\"boom\"}}"}),
        );
        let v: Json = serde_json::from_str(&err).expect("json");
        assert_eq!(v["ok"], false);
        assert!(v["error"].as_str().unwrap().starts_with("MCP error:"));
        // SSE: result TERAKHIR menang; error di tengah melempar.
        let sse = req_json(json!({"op": "mcp_extract", "raw": "event: x\ndata: {\"result\":{\"a\":1}}\ndata: {\"result\":{\"a\":2}}\n"}));
        let v: Json = serde_json::from_str(&sse).expect("json");
        assert_eq!(v["result"]["a"], 2);
        // Tanpa result -> null.
        let none = req_json(json!({"op": "mcp_extract", "raw": "data: {\"jsonrpc\":\"2.0\"}\n"}));
        let v: Json = serde_json::from_str(&none).expect("json");
        assert_eq!(v["ok"], true);
        assert_eq!(v["result"], Json::Null);
        // result non-objek -> null (optJSONObject Kotlin).
        let nonobj = req_json(json!({"op": "mcp_extract", "raw": "{\"result\":\"teks\"}"}));
        let v: Json = serde_json::from_str(&nonobj).expect("json");
        assert_eq!(v["result"], Json::Null);
    }

    #[test]
    fn shape_fingerprint_cases() {
        let f = |body: &str| {
            let r = req_json(json!({"op": "shape_fingerprint", "body": body}));
            let v: Json = serde_json::from_str(&r).expect("json");
            v["value"].as_str().unwrap().to_string()
        };
        assert_eq!(f("{\"b\":1,\"a\":2}"), "json:a,b");
        assert_eq!(f("[1,2]"), "non-json");
        assert_eq!(f("bukan json"), "non-json");
        assert_eq!(f("{}"), "json:");
        assert_eq!(f("{\"z\":1,\"m\":2,\"a\":3}"), "json:a,m,z");
    }

    #[test]
    fn decode_word_cases() {
        let d = |text: &str| {
            let r = req_json(json!({"op": "decode_word", "text": text}));
            let v: Json = serde_json::from_str(&r).expect("json");
            v["value"].as_str().unwrap().to_string()
        };
        // B: "Halo" base64.
        assert_eq!(d("=?UTF-8?B?SGFsbw==?="), "Halo");
        // Q: underscore -> spasi, =XX hex.
        assert_eq!(d("=?UTF-8?Q?Halo_Dunia?="), "Halo Dunia");
        assert_eq!(d("=?UTF-8?Q?caf=C3=A9?="), "café");
        // Kata tak-valid -> data mentah (catch Kotlin).
        assert_eq!(d("=?UTF-8?B?!!!?="), "!!!");
        assert_eq!(d("=?UTF-8?Q?bad=ZZ?="), "bad=ZZ");
        // Campur teks + collapse spasi + trim (NBSP tepi dibuang).
        assert_eq!(d("  Halo   =?UTF-8?B?RHVuaWE=?=  "), "Halo Dunia");
        assert_eq!(d("\u{a0}Halo\u{a0}"), "Halo");
        assert_eq!(d("tanpa kode"), "tanpa kode");
    }

    #[test]
    fn gmail_headers_cases() {
        let h = |lines: Vec<&str>| {
            let r = req_json(json!({"op": "gmail_headers", "lines": lines}));
            let v: Json = serde_json::from_str(&r).expect("json");
            v["value"].as_str().unwrap().to_string()
        };
        assert_eq!(
            h(vec![
                "From: Budi <budi@x.id>",
                "Subject: =?UTF-8?B?SGFsbw==?=",
                "Date: Mon, 16 Nov 2026 10:00:00 +0700 (WIB)"
            ]),
            "- [Mon, 16 Nov 2026 10:00:00 +0700] Budi <budi@x.id> — Halo"
        );
        // Tanpa subjek -> "(tanpa subjek)"; header lipat TIDAK di-unfold.
        assert_eq!(
            h(vec!["From: a@b", "Subject: ", "Date: Tue, 17 Nov 2026 11:00:00 +0700"]),
            "- [Tue, 17 Nov 2026 11:00:00 +0700] a@b — (tanpa subjek)"
        );
        // Case-insensitive + Date dipotong 31 char.
        assert_eq!(
            h(vec!["FROM: c@d", "SUBJECT: Hi", "DATE: Wed, 18 Nov 2026 12:00:00 +0700 extra panjang"]),
            "- [Wed, 18 Nov 2026 12:00:00 +0700] c@d — Hi"
        );
    }

    #[test]
    fn pkce_rfc7636_vector() {
        // RFC 7636 Appendix B.
        let r = req_json(json!({"op": "pkce_challenge", "verifier": "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"}));
        let v: Json = serde_json::from_str(&r).expect("json");
        assert_eq!(v["value"], "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    #[test]
    fn form_encode_cases() {
        let e = |text: &str| {
            let r = req_json(json!({"op": "form_encode", "text": text}));
            let v: Json = serde_json::from_str(&r).expect("json");
            v["value"].as_str().unwrap().to_string()
        };
        assert_eq!(e("a b"), "a+b");
        assert_eq!(e("a+b&c=d"), "a%2Bb%26c%3Dd");
        assert_eq!(e(".-*_"), ".-*_");
        assert_eq!(e("café"), "caf%C3%A9");
        assert_eq!(e(""), "");
        // form_body: urutan pasangan ARRAY dipertahankan.
        let r = req_json(json!({"op": "form_body", "params": [["grant_type", "refresh_token"], ["refresh_token", "rt 1"], ["client_id", "cid"]]}));
        let v: Json = serde_json::from_str(&r).expect("json");
        assert_eq!(
            v["value"],
            "grant_type=refresh_token&refresh_token=rt+1&client_id=cid"
        );
    }

    #[test]
    fn oauth_url_and_callback() {
        let r = req_json(json!({"op": "oauth_auth_url", "auth_url": "https://a.example/auth", "client_id": "cid", "redirect_uri": "http://127.0.0.1:8931/", "scopes": "read write", "state": "s1", "challenge": "ch", "offline": true}));
        let v: Json = serde_json::from_str(&r).expect("json");
        assert_eq!(
            v["value"],
            "https://a.example/auth?response_type=code&client_id=cid&redirect_uri=http%3A%2F%2F127.0.0.1%3A8931%2F&scope=read+write&state=s1&code_challenge=ch&code_challenge_method=S256&access_type=offline&prompt=consent"
        );
        let r = req_json(json!({"op": "oauth_parse_callback", "query": "state=s1&code=abc%2Bdef&x=hello+world"}));
        let v: Json = serde_json::from_str(&r).expect("json");
        assert_eq!(v["params"]["state"], "s1");
        assert_eq!(v["params"]["code"], "abc+def");
        assert_eq!(v["params"]["x"], "hello world");
        // % tak-valid -> {"ok":false} (bukan Err — lihat http_json).
        let bad = http_json(r#"{"op":"oauth_parse_callback","query":"code=%ZZ"}"#)
            .expect("http_json ok");
        let v: Json = serde_json::from_str(&bad).expect("json");
        assert_eq!(v["ok"], false);
        // Bagian tanpa '=' dilewati.
        let r = req_json(json!({"op": "oauth_parse_callback", "query": "lonely&code=c1"}));
        let v: Json = serde_json::from_str(&r).expect("json");
        assert_eq!(v["params"]["code"], "c1");
        assert!(v["params"].get("lonely").is_none());
    }
}
