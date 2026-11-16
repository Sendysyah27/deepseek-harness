//! Port server :4198 slice 9 (vc325) — `WorkerEndpoint.kt` +
//! `WorkerEndpointLogic.kt` (logika murni endpoint worker).
//!
//! Satu seam JSON in/out (`worker_endpoint_json`, op dispatch).
//! Yang di-port: routing, auth Bearer, HMAC antrean, preview,
//! framing HTTP (parse header + bangun head respons), kunci
//! idempotensi. Yang SENGAJA TIDAK di sini (tetap Kotlin,
//! BUILD-VC325.md §2.2–§2.3): loop server hidup (`ServerSocket`/
//! `acceptLoop`), dispatch (memanggil `WorkerRegistry`/
//! `WorkerQueue`/`WorkerJanitor`/`ProotSession`/`SecureStore`/
//! `EventDb`), token/SecureStore, baca body dari stream, peta
//! idempotensi stateful. TANPA tokio — server Rust penuh butuh
//! callback Rust→Kotlin yang dilarang peta §3.4 di slice awal.
//!
//! Pemetaan perilaku Kotlin -> Rust (quirk DIREPLIKASI):
//! - `URLDecoder.decode` mengubah `+` jadi spasi (path DAN query);
//!   bila SATU `%` tak-valid, SELURUH segmen/nilai dikembalikan
//!   mentah (catch Kotlin per segmen/nilai).
//! - `JSONObject.optString` melakukan koersi (angka/bool -> teks
//!   kompak, hilang/null -> default/`""`).
//! - `String.length`/`take(n)` Kotlin = satuan UTF-16 (astral =
//!   2 unit); potongan tepat di tengah pasangan surrogate ->
//!   lone high-surrogate di string JVM (probe JVM: `toByteArray`
//!   UTF-8 = `?` 0x3F, `JSONObject.toString()` = `"a?"`). Seam
//!   mereplikanya dengan menulis escape JSON `\uXXXX` mentah
//!   (Rust String tidak bisa memegang lone surrogate) sehingga
//!   org.json mem-parse-nya kembali jadi lone surrogate —
//!   perilaku hilir identik 1:1.
//! - `String.trim()` Kotlin = `kotlin_trim` (Java isWhitespace —
//!   NBSP tepi dibuang, pelajaran slice 4); `lowercase()` =
//!   Unicode-lowercase.
//! - `MessageDigest.isEqual` = banding waktu-konstan (tanpa
//!   early-exit isi; panjang beda -> false); kosong -> tolak.
//! - HMAC-SHA256: key > 64 byte di-hash dulu (blok SHA-256).
//! - `previewChars(0)` -> 2000 (bukan 1); `slicePreview` memakai
//!   `<=` (teks tepat sepanjang maks TIDAK terpotong).
//! - `GET /workers/spawn` = workerDetail id=`spawn` (spawnWorker
//!   hanya POST).
//! - Header HTTP: kunci di-lowercase, duplikat menimpa (yang
//!   terakhir menang), baris tanpa `:` (idx <= 0) dilewati.
//!
//! TANPA dependensi Cargo baru: HMAC di atas `sha2` 0.10.9
//! (sudah ada sejak slice 8).

#![forbid(unsafe_code)]

use crate::ds_safety_strip::kotlin_trim;
use serde_json::{json, Map, Value as Json};
use sha2::{Digest, Sha256};

/// Satu entry point seam: request JSON -> respons JSON (String).
/// Error op dikembalikan sebagai `{"ok":false,"error":...}` — BUKAN
/// `Err` — supaya boundary JNI mengembalikan `null` HANYA bila
/// panic/error FFI (pola perbaikan `http_json` vc324 §7.1).
pub fn worker_endpoint_json(req_str: &str) -> Result<String, String> {
    Ok(dispatch(req_str).unwrap_or_else(|e| err_json(&e)))
}

fn err_json(msg: &str) -> String {
    json!({"ok": false, "error": msg}).to_string()
}

fn dispatch(req_str: &str) -> Result<String, String> {
    let req: Json =
        serde_json::from_str(req_str).map_err(|_| "bad request json".to_string())?;
    let op = req
        .get("op")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing op".to_string())?;
    match op {
        "parse_route" => {
            let method = req_str_field(&req, "method")?;
            let raw_path = req_str_field(&req, "raw_path")?;
            let route = parse_route(&method, &raw_path);
            Ok(match route {
                Some((action, worker_id, name)) => json!({"ok": true, "route": {"action": action, "worker_id": worker_id, "name": name}}).to_string(),
                None => json!({"ok": true, "route": null}).to_string(),
            })
        }
        "path_matches_other_method" => {
            let method = req_str_field(&req, "method")?;
            let raw_path = req_str_field(&req, "raw_path")?;
            let m = path_matches_other_method(&method, &raw_path);
            Ok(json!({"ok": true, "value": m}).to_string())
        }
        "parse_query" => {
            let raw_path = req_str_field(&req, "raw_path")?;
            let q = parse_query(&raw_path);
            let mut obj = Map::new();
            for (k, v) in q {
                obj.insert(k, Json::String(v));
            }
            Ok(json!({"ok": true, "query": Json::Object(obj)}).to_string())
        }
        "bearer_token" => {
            let header = req.get("header").and_then(|v| v.as_str());
            let tok = bearer_token(header);
            Ok(match tok {
                Some(t) => json!({"ok": true, "token": t}).to_string(),
                None => json!({"ok": true, "token": null}).to_string(),
            })
        }
        "is_authorized" => {
            let provided = req.get("provided").and_then(|v| v.as_str());
            let expected = req_str_field(&req, "expected")?;
            Ok(json!({"ok": true, "value": is_authorized(provided, &expected)}).to_string())
        }
        "is_safe_name" => {
            let name = req_str_field(&req, "name")?;
            Ok(json!({"ok": true, "value": is_safe_name(&name)}).to_string())
        }
        "slice_preview" => {
            let text = req_str_field(&req, "text")?;
            let max = req_i64_field(&req, "max")?;
            let (slice, cut, split) = slice_preview(&text, max);
            Ok(match split {
                // Potongan membelah pasangan surrogate: tulis escape
                // JSON `\uXXXX` mentah (bukan via serde_json — Rust
                // String tidak bisa memegang lone surrogate) supaya
                // org.json mem-parse-nya kembali jadi lone surrogate
                // di string JVM — identik 1:1 dengan `take(n)` Kotlin.
                Some(high) => {
                    let mut prefix = slice.clone();
                    prefix.pop();
                    let q = serde_json::to_string(&prefix)
                        .map_err(|_| "json encode gagal".to_string())?;
                    let inner = &q[1..q.len() - 1];
                    format!(
                        "{{\"ok\": true, \"slice\": \"{inner}\\u{high:04x}\", \"truncated\": {cut}}}"
                    )
                }
                None => json!({"ok": true, "slice": slice, "truncated": cut}).to_string(),
            })
        }
        "preview_chars" => {
            let requested = req_i64_field(&req, "requested")?;
            Ok(json!({"ok": true, "value": preview_chars(requested)}).to_string())
        }
        "hmac_sha256_hex" => {
            let key = req_str_field(&req, "key")?;
            let text = req_str_field(&req, "text")?;
            Ok(json!({"ok": true, "value": hmac_sha256_hex(&key, &text)}).to_string())
        }
        "sign_queue_text" => {
            let key = req_str_field(&req, "key")?;
            let text = req_str_field(&req, "text")?;
            Ok(json!({"ok": true, "value": sign_queue_text(&key, &text)}).to_string())
        }
        "has_queue_signature" => {
            let text = req_str_field(&req, "text")?;
            Ok(json!({"ok": true, "value": has_queue_signature(&text)}).to_string())
        }
        "verify_queue_text" => {
            let key = req_str_field(&req, "key")?;
            let signed = req_str_field(&req, "signed")?;
            Ok(match verify_queue_text(&key, &signed) {
                Some(body) => json!({"ok": true, "value": body}).to_string(),
                None => json!({"ok": true, "value": null}).to_string(),
            })
        }
        "worker_scoped" => {
            let action = req_str_field(&req, "action")?;
            Ok(json!({"ok": true, "value": worker_scoped(&action)}).to_string())
        }
        "parse_headers" => {
            let text = req_str_field(&req, "text")?;
            if text.as_bytes().len() > 16384 {
                return Err("header terlalu besar (maks 16 KB)".to_string());
            }
            let (req_line, headers) = parse_headers(&text);
            let mut obj = Map::new();
            for (k, v) in headers {
                obj.insert(k, Json::String(v));
            }
            Ok(json!({"ok": true, "req_line": req_line, "headers": Json::Object(obj)}).to_string())
        }
        "build_response" => {
            let code = req_i64_field(&req, "code")?;
            let body_bytes = req_i64_field(&req, "body_bytes")?;
            let retry_after = req.get("retry_after").and_then(|v| v.as_i64());
            Ok(json!({"ok": true, "head": build_response(code, body_bytes, retry_after)}).to_string())
        }
        "idempotency_key" => {
            let action = req_str_field(&req, "action")?;
            let worker_id = req_str_field(&req, "worker_id")?;
            let idem_header = req.get("idem_header").and_then(|v| v.as_str());
            let body = req.get("body").and_then(|v| v.as_str()).unwrap_or("");
            let key = idempotency_key(&action, &worker_id, idem_header, body);
            Ok(match key {
                Some(k) => json!({"ok": true, "key": k}).to_string(),
                None => json!({"ok": true, "key": null}).to_string(),
            })
        }
        _ => Err(format!("op tak dikenal: {op}")),
    }
}

fn req_str_field(req: &Json, field: &str) -> Result<String, String> {
    req.get(field)
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .ok_or_else(|| format!("missing field: {field}"))
}

fn req_i64_field(req: &Json, field: &str) -> Result<i64, String> {
    req.get(field)
        .and_then(|v| v.as_i64())
        .ok_or_else(|| format!("missing field: {field}"))
}

// ---------------------------------------------------------------------------
// UTF-16: `String.length` / `take(n)` Kotlin menghitung satuan UTF-16.
// ---------------------------------------------------------------------------

/// Panjang string dalam satuan UTF-16 (seperti `String.length` Kotlin).
fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

/// `String.take(n)` Kotlin: n satuan UTF-16 pertama. Bila potongan
/// jatuh tepat di tengah pasangan surrogate, kembalikan separuh itu
/// sebagai `U+FFFD` DI DALAM string Rust (Rust tidak bisa memegang
/// lone surrogate) + `Some(high)` = unit high-surrogate aslinya.
/// Pemanggil seam memakai `high` untuk menulis escape JSON
/// `\uXXXX` mentah — org.json mem-parse-nya kembali jadi lone
/// surrogate di string JVM, sehingga perilaku hilir Kotlin identik
/// 1:1 (probe JVM: `take(2)` "a😀b" -> `toByteArray(UTF_8)` =
/// `61 3F` dan `JSONObject.toString()` = `{"content":"a?"}` —
/// lone surrogate jadi `?` 0x3F, BUKAN U+FFFD).
fn utf16_take(s: &str, max: usize) -> (String, Option<u16>) {
    let mut out = String::new();
    let mut used = 0usize;
    for c in s.chars() {
        let units = c.len_utf16();
        if used + units > max {
            if units == 2 && used < max {
                out.push('\u{FFFD}');
                let mut buf = [0u16; 2];
                c.encode_utf16(&mut buf);
                return (out, Some(buf[0]));
            }
            break;
        }
        out.push(c);
        used += units;
    }
    (out, None)
}

/// `String.trim()` Kotlin = trim Java-`isWhitespace` (bukan trim
/// Unicode Rust — NBSP tepi dibuang, pelajaran slice 4).
fn kotlin_lowercase(s: &str) -> String {
    s.to_lowercase()
}

// ---------------------------------------------------------------------------
// URL-decode gaya `java.net.URLDecoder.decode(s, "UTF-8")`.
// ---------------------------------------------------------------------------

/// Decode satu string gaya URLDecoder: `+` -> spasi, `%XX` -> byte.
/// `Err` bila ada `%` tak-valid — pemanggil mengembalikan string
/// mentah (catch Kotlin per segmen/nilai).
fn url_decode(s: &str) -> Result<String, String> {
    let bytes = s.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
    let mut i = 0usize;
    while i < bytes.len() {
        match bytes[i] {
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b'%' => {
                if i + 2 >= bytes.len() {
                    return Err("bad % escape".to_string());
                }
                let hi = hex_val(bytes[i + 1]).ok_or_else(|| "bad % escape".to_string())?;
                let lo = hex_val(bytes[i + 2]).ok_or_else(|| "bad % escape".to_string())?;
                out.push((hi << 4) | lo);
                i += 3;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    Ok(String::from_utf8_lossy(&out).into_owned())
}

fn hex_val(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// Koersi `JSONObject.optString` (org.json).
// ---------------------------------------------------------------------------

/// `optString(key)` / `optString(key, default)`: hilang/null -> default;
/// string -> apa adanya; angka/bool/array/objek -> teks JSON kompak.
fn opt_string(obj: &Json, key: &str, default: &str) -> String {
    match obj.get(key) {
        None | Some(Json::Null) => default.to_string(),
        Some(Json::String(s)) => s.clone(),
        Some(other) => other.to_string(),
    }
}

/// Parse body JSON; gagal parse -> objek kosong (catch Kotlin).
fn body_obj(body: &str) -> Json {
    serde_json::from_str(body).unwrap_or(Json::Object(Map::new()))
}

// ---------------------------------------------------------------------------
// Routing + query (WorkerEndpointLogic.parseRoute / parseQuery).
// ---------------------------------------------------------------------------

/// Parse method + path mentah -> (aksi, workerId, nama), atau None
/// bila tidak dikenal. Segmen path di-URL-decode (gagal decode satu
/// segmen -> segmen itu mentah).
pub fn parse_route(method: &str, raw_path: &str) -> Option<(String, String, String)> {
    let path = kotlin_trim(raw_path.split('?').next().unwrap_or(""));
    let seg: Vec<String> = path
        .split('/')
        .filter(|s| !s.is_empty())
        .map(|s| url_decode(s).unwrap_or_else(|_| s.to_string()))
        .collect();
    let m = method.to_uppercase();
    if seg.is_empty() {
        return None;
    }
    if seg.len() == 1 && seg[0] == "health" && m == "GET" {
        return Some(("health".to_string(), String::new(), String::new()));
    }
    if seg.len() == 1 && seg[0] == "workers" && m == "GET" {
        return Some(("listWorkers".to_string(), String::new(), String::new()));
    }
    if seg.len() == 2 && seg[0] == "workers" && seg[1] == "spawn" && m == "POST" {
        return Some(("spawnWorker".to_string(), String::new(), String::new()));
    }
    if seg.len() == 2 && seg[0] == "workers" {
        if m == "GET" {
            return Some(("workerDetail".to_string(), seg[1].clone(), String::new()));
        }
        return None;
    }
    if seg.len() == 3 && seg[0] == "workers" {
        let action = match (seg[2].as_str(), m.as_str()) {
            ("stop", "POST") => "stopWorker",
            ("restart", "POST") => "restartWorker",
            ("quota", "POST") => "setQuota",
            ("reset", "POST") => "resetWorker",
            ("verify-task", "POST") => "verifyTask",
            ("log", "GET") => "workerLog",
            ("tasks", "GET") => "listTasks",
            ("tasks", "POST") => "enqueueTask",
            ("results", "GET") => "listResults",
            _ => return None,
        };
        return Some((action.to_string(), seg[1].clone(), String::new()));
    }
    if seg.len() == 4 && seg[0] == "workers" && seg[2] == "tasks" && m == "GET" {
        return Some(("readTask".to_string(), seg[1].clone(), seg[3].clone()));
    }
    if seg.len() == 4 && seg[0] == "workers" && seg[2] == "results" && m == "GET" {
        return Some(("readResult".to_string(), seg[1].clone(), seg[3].clone()));
    }
    if seg.len() == 5 && seg[0] == "workers" && seg[2] == "tasks" && seg[4] == "result" && m == "POST" {
        return Some(("writeTaskResult".to_string(), seg[1].clone(), seg[3].clone()));
    }
    if !seg.is_empty() && seg[0] == "queue" && seg.len() >= 2 && seg[1] == "jhon" {
        if seg.len() == 3 && seg[2] == "tasks" {
            return match m.as_str() {
                "GET" => Some(("jhonListTasks".to_string(), String::new(), String::new())),
                "POST" => Some(("jhonEnqueue".to_string(), String::new(), String::new())),
                _ => None,
            };
        }
        if seg.len() == 3 && seg[2] == "results" && m == "GET" {
            return Some(("jhonListResults".to_string(), String::new(), String::new()));
        }
        if seg.len() == 4 && seg[2] == "tasks" && m == "GET" {
            return Some(("jhonReadTask".to_string(), String::new(), seg[3].clone()));
        }
        if seg.len() == 5 && seg[2] == "tasks" && seg[4] == "result" && m == "POST" {
            return Some(("jhonWriteResult".to_string(), String::new(), seg[3].clone()));
        }
    }
    None
}

/// Path cocok rute dikenal tapi method-nya salah -> pemanggil jawab
/// 405, bukan 404 (vc261 N-m2).
pub fn path_matches_other_method(method: &str, raw_path: &str) -> bool {
    if parse_route(method, raw_path).is_some() {
        return false;
    }
    parse_route("GET", raw_path).is_some() || parse_route("POST", raw_path).is_some()
}

/// `parseQuery`: kunci TIDAK di-decode, nilai di-URL-decode (gagal
/// decode nilai -> nilai mentah); pasangan tanpa `=` dilewati.
pub fn parse_query(raw_path: &str) -> Vec<(String, String)> {
    let q = match raw_path.split_once('?') {
        Some((_, q)) => q,
        None => "",
    };
    if kotlin_trim(q).is_empty() {
        return Vec::new();
    }
    let mut out: Vec<(String, String)> = Vec::new();
    for part in q.split('&') {
        if let Some((k, v)) = part.split_once('=') {
            if !kotlin_trim(k).is_empty() {
                let val = url_decode(v).unwrap_or_else(|_| v.to_string());
                out.push((k.to_string(), val));
            }
        }
    }
    out
}

// ---------------------------------------------------------------------------
// Auth (WorkerEndpointLogic.isAuthorized / bearerToken).
// ---------------------------------------------------------------------------

/// Banding token waktu-konstan. Kosong -> tolak. Provided di-trim
/// (Kotlin), expected TIDAK. Panjang beda -> false (seperti
/// `MessageDigest.isEqual`); isi dibanding tanpa early-exit.
pub fn is_authorized(provided: Option<&str>, expected: &str) -> bool {
    let p = match provided {
        Some(s) => kotlin_trim(s),
        None => return false,
    };
    if p.is_empty() || expected.is_empty() {
        return false;
    }
    let pb = p.as_bytes();
    let eb = expected.as_bytes();
    if pb.len() != eb.len() {
        return false;
    }
    let mut diff: u8 = 0;
    for i in 0..pb.len() {
        diff |= pb[i] ^ eb[i];
    }
    diff == 0
}

/// Ambil token dari header `Authorization: Bearer <token>`.
pub fn bearer_token(auth_header: Option<&str>) -> Option<String> {
    let h = kotlin_trim(auth_header?);
    if h.is_empty() {
        return None;
    }
    if !h.starts_with("Bearer ") {
        return None;
    }
    let tok = kotlin_trim(&h["Bearer ".len()..]);
    if tok.is_empty() {
        None
    } else {
        Some(tok.to_string())
    }
}

// ---------------------------------------------------------------------------
// Nama aman + preview (WorkerEndpointLogic / WorkerLogic).
// ---------------------------------------------------------------------------

/// Nama file tugas/hasil aman: tanpa `/`, `..`, backslash.
pub fn is_safe_name(name: &str) -> bool {
    !kotlin_trim(name).is_empty()
        && !name.contains('/')
        && !name.contains("..")
        && !name.contains('\\')
}

/// Potong teks ke maks char (satuan UTF-16); kembalikan
/// (potongan, terpotong?, high-surrogate bila potongan membelah
/// pasangan surrogate — lihat `utf16_take`).
pub fn slice_preview(text: &str, max: i64) -> (String, bool, Option<u16>) {
    if max < 0 {
        return (String::new(), true, None);
    }
    let max = max as usize;
    if utf16_len(text) <= max {
        (text.to_string(), false, None)
    } else {
        let (slice, split) = utf16_take(text, max);
        (slice, true, split)
    }
}

/// `WorkerLogic.previewChars`: <=0 -> 2000, sisanya clamp 1..8000.
pub fn preview_chars(requested: i64) -> i64 {
    if requested <= 0 {
        2000
    } else if requested > 8000 {
        8000
    } else {
        requested
    }
}

// ---------------------------------------------------------------------------
// HMAC antrean (WorkerEndpointLogic — HMAC-SHA256, format
// `HMAC-SHA256:<hex>\n<isi>`).
// ---------------------------------------------------------------------------

fn hmac_sha256(key: &[u8], msg: &[u8]) -> [u8; 32] {
    let mut k: Vec<u8> = key.to_vec();
    if k.len() > 64 {
        let mut h = Sha256::new();
        h.update(&k);
        k = h.finalize().to_vec();
    }
    k.resize(64, 0);
    let mut ipad = [0u8; 64];
    let mut opad = [0u8; 64];
    for i in 0..64 {
        ipad[i] = k[i] ^ 0x36;
        opad[i] = k[i] ^ 0x5c;
    }
    let mut inner = Sha256::new();
    inner.update(ipad);
    inner.update(msg);
    let inner_sum = inner.finalize();
    let mut outer = Sha256::new();
    outer.update(opad);
    outer.update(inner_sum);
    let out = outer.finalize();
    let mut res = [0u8; 32];
    res.copy_from_slice(&out);
    res
}

fn hex_lower(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

/// HMAC-SHA256 key+teks (UTF-8) sebagai hex lowercase.
pub fn hmac_sha256_hex(key: &str, text: &str) -> String {
    hex_lower(&hmac_sha256(key.as_bytes(), text.as_bytes()))
}

/// Tandatangani teks antrean: `HMAC-SHA256:<hex>\n<teks>`.
pub fn sign_queue_text(key: &str, text: &str) -> String {
    format!("HMAC-SHA256:{}\n{}", hmac_sha256_hex(key, text), text)
}

/// Apakah teks membawa baris tanda tangan HMAC.
pub fn has_queue_signature(text: &str) -> bool {
    text.starts_with("HMAC-SHA256:")
}

/// Verifikasi teks bertanda tangan: isi bila valid, None bila tidak
/// ada / tidak valid (pemanggil membedakan lewat
/// `has_queue_signature`).
pub fn verify_queue_text(key: &str, signed: &str) -> Option<String> {
    if !has_queue_signature(signed) {
        return None;
    }
    let nl = signed.find('\n')?;
    let sig = kotlin_trim(&signed["HMAC-SHA256:".len()..nl]);
    let body = &signed[nl + 1..];
    if sig.is_empty() {
        return None;
    }
    let expected = hmac_sha256_hex(key, body);
    if constant_time_eq_str(sig, &expected) {
        Some(body.to_string())
    } else {
        None
    }
}

fn constant_time_eq_str(a: &str, b: &str) -> bool {
    let ab = a.as_bytes();
    let bb = b.as_bytes();
    if ab.len() != bb.len() {
        return false;
    }
    let mut diff: u8 = 0;
    for i in 0..ab.len() {
        diff |= ab[i] ^ bb[i];
    }
    diff == 0
}

/// Aksi yang BOLEH diakses token per-worker (11 aksi).
pub fn worker_scoped(action: &str) -> bool {
    matches!(
        action,
        "workerDetail"
            | "stopWorker"
            | "restartWorker"
            | "workerLog"
            | "listTasks"
            | "enqueueTask"
            | "readTask"
            | "writeTaskResult"
            | "listResults"
            | "readResult"
            | "verifyTask"
    )
}

// ---------------------------------------------------------------------------
// Framing HTTP (WorkerEndpoint.parseHeaders / sendJson).
// ---------------------------------------------------------------------------

/// Parse teks header (sudah diakumulasi dari stream, maks 16 KB):
/// baris pertama = request line; baris berikut `Kunci: nilai`
/// (kunci lowercase + trim, nilai trim, duplikat menimpa, baris
/// tanpa `:` dilewati).
pub fn parse_headers(text: &str) -> (String, Vec<(String, String)>) {
    let lines: Vec<&str> = text.split("\r\n").collect();
    let req_line = lines.first().copied().unwrap_or("").to_string();
    let mut headers: Vec<(String, String)> = Vec::new();
    for l in lines.iter().skip(1) {
        if let Some(idx) = l.find(':') {
            if idx > 0 {
                let key = kotlin_lowercase(kotlin_trim(&l[..idx]));
                let val = kotlin_trim(&l[idx + 1..]).to_string();
                if let Some(slot) = headers.iter_mut().find(|(k, _)| *k == key) {
                    slot.1 = val;
                } else {
                    headers.push((key, val));
                }
            }
        }
    }
    (req_line, headers)
}

/// Frasa reason HTTP (WorkerEndpoint.reason).
fn reason(code: i64) -> &'static str {
    match code {
        200 => "OK",
        400 => "Bad Request",
        401 => "Unauthorized",
        404 => "Not Found",
        405 => "Method Not Allowed",
        409 => "Conflict",
        413 => "Payload Too Large",
        431 => "Request Header Fields Too Large",
        500 => "Internal Server Error",
        503 => "Service Unavailable",
        _ => "OK",
    }
}

/// Bangun head respons HTTP. `body_bytes` = panjang body dalam byte
/// UTF-8 (dihitung Kotlin dari `ByteArray.size`).
pub fn build_response(code: i64, body_bytes: i64, retry_after: Option<i64>) -> String {
    let retry_head = match retry_after {
        Some(n) => format!("Retry-After: {n}\r\n"),
        None => String::new(),
    };
    format!(
        "HTTP/1.1 {code} {reason}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {body_bytes}\r\nConnection: close\r\n{retry_head}\r\n",
        reason = reason(code)
    )
}

// ---------------------------------------------------------------------------
// Kunci idempotensi (WorkerEndpoint.idempotencyKey + sha256Hex16).
// ---------------------------------------------------------------------------

/// 8 byte pertama SHA-256 sebagai 16 digit hex lowercase.
fn sha256_hex16(text: &str) -> String {
    let mut h = Sha256::new();
    h.update(text.as_bytes());
    let sum = h.finalize();
    hex_lower(&sum[..8])
}

/// Kunci idempotensi satu permintaan, atau None bila aksi ini tidak
/// butuh idempotensi (hanya spawn + enqueue yang non-idempotent).
/// Header `Idempotency-Key` klien didahulukan; tanpa header, kunci
/// diturunkan dari isi permintaan.
pub fn idempotency_key(
    action: &str,
    worker_id: &str,
    idem_header: Option<&str>,
    body: &str,
) -> Option<String> {
    if let Some(hdr) = idem_header {
        let h = kotlin_trim(hdr);
        if !h.is_empty() {
            return Some(format!("hdr:{}", utf16_take(h, 128).0));
        }
    }
    match action {
        "spawnWorker" => {
            let j = body_obj(body);
            let name = kotlin_lowercase(kotlin_trim(&opt_string(&j, "name", "")));
            if name.is_empty() {
                return None;
            }
            let kind = opt_string(&j, "kind", "proot");
            let session = opt_string(&j, "session", "worker1");
            let content = if kind == "python" {
                opt_string(&j, "code", "")
            } else {
                opt_string(&j, "command", "")
            };
            Some(format!(
                "spawn:{}|{}|{}|{}",
                name,
                kind,
                session,
                sha256_hex16(&content)
            ))
        }
        "enqueueTask" => {
            let text = opt_string(&body_obj(body), "text", "");
            if kotlin_trim(&text).is_empty() {
                None
            } else {
                Some(format!("enqueue:{}|{}", worker_id, sha256_hex16(&text)))
            }
        }
        "jhonEnqueue" => {
            let text = opt_string(&body_obj(body), "text", "");
            if kotlin_trim(&text).is_empty() {
                None
            } else {
                Some(format!("enqueue:jhon|{}", sha256_hex16(&text)))
            }
        }
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// Tes
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn ep(op: &str, extra: Json) -> Json {
        let mut req = json!({"op": op});
        if let Json::Object(m) = extra {
            if let Json::Object(reqm) = &mut req {
                for (k, v) in m {
                    reqm.insert(k, v);
                }
            }
        }
        let out = worker_endpoint_json(&req.to_string()).expect("seam ok");
        serde_json::from_str(&out).expect("json ok")
    }

    fn route(action: &str, worker_id: &str, name: &str) -> Json {
        json!({"action": action, "worker_id": worker_id, "name": name})
    }

    #[test]
    fn parse_route_health_and_list() {
        assert_eq!(ep("parse_route", json!({"method": "GET", "raw_path": "/health"})).get("route"), Some(&route("health", "", "")));
        assert_eq!(ep("parse_route", json!({"method": "GET", "raw_path": "/health?x=1"})).get("route"), Some(&route("health", "", "")));
        assert_eq!(ep("parse_route", json!({"method": "POST", "raw_path": "/health"})).get("route"), Some(&Json::Null));
        assert_eq!(ep("parse_route", json!({"method": "GET", "raw_path": "/workers"})).get("route"), Some(&route("listWorkers", "", "")));
        assert_eq!(ep("parse_route", json!({"method": "get", "raw_path": "/workers"})).get("route"), Some(&route("listWorkers", "", "")));
        assert_eq!(ep("parse_route", json!({"method": "GET", "raw_path": "/"})).get("route"), Some(&Json::Null));
        assert_eq!(ep("parse_route", json!({"method": "GET", "raw_path": ""})).get("route"), Some(&Json::Null));
    }

    #[test]
    fn parse_route_workers_all_actions() {
        let cases: Vec<(&str, &str, &str, &str, &str)> = vec![
            ("POST", "/workers/spawn", "spawnWorker", "", ""),
            ("GET", "/workers/w_1", "workerDetail", "w_1", ""),
            ("POST", "/workers/w_1/stop", "stopWorker", "w_1", ""),
            ("POST", "/workers/w_1/restart", "restartWorker", "w_1", ""),
            ("POST", "/workers/w_1/quota", "setQuota", "w_1", ""),
            ("POST", "/workers/w_1/reset", "resetWorker", "w_1", ""),
            ("POST", "/workers/w_1/verify-task", "verifyTask", "w_1", ""),
            ("GET", "/workers/w_1/log", "workerLog", "w_1", ""),
            ("GET", "/workers/w_1/tasks", "listTasks", "w_1", ""),
            ("POST", "/workers/w_1/tasks", "enqueueTask", "w_1", ""),
            ("GET", "/workers/w_1/results", "listResults", "w_1", ""),
            ("GET", "/workers/w_1/tasks/task-1.md", "readTask", "w_1", "task-1.md"),
            ("GET", "/workers/w_1/results/task-1.md.result.md", "readResult", "w_1", "task-1.md.result.md"),
            ("POST", "/workers/w_1/tasks/task-1.md/result", "writeTaskResult", "w_1", "task-1.md"),
            ("GET", "/workers/spawn", "workerDetail", "spawn", ""),
            ("POST", "/workers/w_1/tasks", "enqueueTask", "w_1", ""),
        ];
        for (m, p, a, w, n) in cases {
            let got = ep("parse_route", json!({"method": m, "raw_path": p}));
            assert_eq!(got.get("route"), Some(&route(a, w, n)), "route {m} {p}");
        }
        // Method salah -> null (pemanggil jawab 405 bila path cocok).
        assert_eq!(ep("parse_route", json!({"method": "DELETE", "raw_path": "/workers"})).get("route"), Some(&Json::Null));
        assert_eq!(ep("parse_route", json!({"method": "GET", "raw_path": "/workers/w_1/stop"})).get("route"), Some(&Json::Null));
        assert_eq!(ep("parse_route", json!({"method": "POST", "raw_path": "/workers/w_1/log"})).get("route"), Some(&Json::Null));
        assert_eq!(ep("parse_route", json!({"method": "GET", "raw_path": "/tidak-ada"})).get("route"), Some(&Json::Null));
    }

    #[test]
    fn parse_route_jhon_queue() {
        let cases: Vec<(&str, &str, &str, &str)> = vec![
            ("GET", "/queue/jhon/tasks", "jhonListTasks", ""),
            ("POST", "/queue/jhon/tasks", "jhonEnqueue", ""),
            ("GET", "/queue/jhon/results", "jhonListResults", ""),
            ("GET", "/queue/jhon/tasks/task-9.md", "jhonReadTask", "task-9.md"),
            ("POST", "/queue/jhon/tasks/task-9.md/result", "jhonWriteResult", "task-9.md"),
        ];
        for (m, p, a, n) in cases {
            let got = ep("parse_route", json!({"method": m, "raw_path": p}));
            assert_eq!(got.get("route"), Some(&route(a, "", n)), "route {m} {p}");
        }
        assert_eq!(ep("parse_route", json!({"method": "DELETE", "raw_path": "/queue/jhon/tasks"})).get("route"), Some(&Json::Null));
        assert_eq!(ep("parse_route", json!({"method": "GET", "raw_path": "/queue/bukan/tasks"})).get("route"), Some(&Json::Null));
    }

    #[test]
    fn parse_route_url_decode_plus() {
        // '+' di path -> spasi (quirk URLDecoder); %XX di-decode.
        let got = ep("parse_route", json!({"method": "GET", "raw_path": "/workers/w+1/tasks/task%2D1.md"}));
        assert_eq!(got.get("route"), Some(&route("readTask", "w 1", "task-1.md")));
        // Satu '%' tak-valid -> segmen itu mentah seluruhnya.
        let got = ep("parse_route", json!({"method": "GET", "raw_path": "/workers/w%zz/tasks"}));
        assert_eq!(got.get("route"), Some(&route("listTasks", "w%zz", "")));
        // Query tidak memengaruhi routing; spasi di ujung path di-trim.
        let got = ep("parse_route", json!({"method": "GET", "raw_path": "  /health  "}));
        assert_eq!(got.get("route"), Some(&route("health", "", "")));
    }

    #[test]
    fn path_matches_other_method_matrix() {
        assert!(ep("path_matches_other_method", json!({"method": "DELETE", "raw_path": "/workers"})).get("value").unwrap().as_bool().unwrap());
        assert!(ep("path_matches_other_method", json!({"method": "GET", "raw_path": "/workers/w_1/stop"})).get("value").unwrap().as_bool().unwrap());
        assert!(!ep("path_matches_other_method", json!({"method": "GET", "raw_path": "/health"})).get("value").unwrap().as_bool().unwrap());
        assert!(!ep("path_matches_other_method", json!({"method": "DELETE", "raw_path": "/tidak-ada"})).get("value").unwrap().as_bool().unwrap());
    }

    #[test]
    fn parse_query_matrix() {
        let got = ep("parse_query", json!({"raw_path": "/workers/w_1/log?lines=50&foo=bar+baz"}));
        assert_eq!(got["query"]["lines"], json!("50"));
        assert_eq!(got["query"]["foo"], json!("bar baz"));
        let got = ep("parse_query", json!({"raw_path": "/x?k=a%26b&empty=&noeq&k2=%zz"}));
        assert_eq!(got["query"]["k"], json!("a&b"));
        assert_eq!(got["query"]["empty"], json!(""));
        assert!(got["query"].get("noeq").is_none());
        assert_eq!(got["query"]["k2"], json!("%zz"));
        let got = ep("parse_query", json!({"raw_path": "/x"}));
        assert_eq!(got["query"], json!({}));
        let got = ep("parse_query", json!({"raw_path": "/x?"}));
        assert_eq!(got["query"], json!({}));
    }

    #[test]
    fn bearer_token_matrix() {
        assert_eq!(ep("bearer_token", json!({"header": "Bearer abc123"})).get("token"), Some(&json!("abc123")));
        assert_eq!(ep("bearer_token", json!({"header": "  Bearer   abc123  "})).get("token"), Some(&json!("abc123")));
        assert_eq!(ep("bearer_token", json!({"header": "Bearer "})).get("token"), Some(&Json::Null));
        assert_eq!(ep("bearer_token", json!({"header": "bearer abc"})).get("token"), Some(&Json::Null));
        assert_eq!(ep("bearer_token", json!({"header": "Basic abc"})).get("token"), Some(&Json::Null));
        assert_eq!(ep("bearer_token", json!({"header": ""})).get("token"), Some(&Json::Null));
        assert_eq!(ep("bearer_token", json!({})).get("token"), Some(&Json::Null));
    }

    #[test]
    fn is_authorized_matrix() {
        assert!(ep("is_authorized", json!({"provided": "tok123", "expected": "tok123"})).get("value").unwrap().as_bool().unwrap());
        assert!(!ep("is_authorized", json!({"provided": "tok123", "expected": "tok124"})).get("value").unwrap().as_bool().unwrap());
        assert!(!ep("is_authorized", json!({"provided": "tok12", "expected": "tok123"})).get("value").unwrap().as_bool().unwrap());
        assert!(!ep("is_authorized", json!({"provided": "", "expected": "tok123"})).get("value").unwrap().as_bool().unwrap());
        assert!(!ep("is_authorized", json!({"provided": "   ", "expected": "tok123"})).get("value").unwrap().as_bool().unwrap());
        assert!(!ep("is_authorized", json!({"provided": "tok123", "expected": ""})).get("value").unwrap().as_bool().unwrap());
        assert!(!ep("is_authorized", json!({"expected": "tok123"})).get("value").unwrap().as_bool().unwrap());
        // Provided di-trim (Kotlin); expected TIDAK di-trim.
        assert!(ep("is_authorized", json!({"provided": "  tok123 ", "expected": "tok123"})).get("value").unwrap().as_bool().unwrap());
        assert!(!ep("is_authorized", json!({"provided": "tok123", "expected": " tok123"})).get("value").unwrap().as_bool().unwrap());
    }

    #[test]
    fn is_safe_name_matrix() {
        for (name, ok) in [
            ("task-1.md", true),
            ("task-1.md.result.md", true),
            ("a b.md", true),
            ("", false),
            ("   ", false),
            ("../task.md", false),
            ("a/b.md", false),
            ("a\\b.md", false),
            ("..", false),
            ("a..b", false),
        ] {
            assert_eq!(ep("is_safe_name", json!({"name": name})).get("value").unwrap().as_bool().unwrap(), ok, "name {name}");
        }
    }

    #[test]
    fn slice_preview_utf16() {
        assert_eq!(ep("slice_preview", json!({"text": "hello", "max": 10})).get("slice"), Some(&json!("hello")));
        assert!(!ep("slice_preview", json!({"text": "hello", "max": 10})).get("truncated").unwrap().as_bool().unwrap());
        // Tepat sepanjang maks -> TIDAK terpotong (<=).
        assert!(!ep("slice_preview", json!({"text": "hello", "max": 5})).get("truncated").unwrap().as_bool().unwrap());
        assert_eq!(ep("slice_preview", json!({"text": "hello world", "max": 5})).get("slice"), Some(&json!("hello")));
        assert!(ep("slice_preview", json!({"text": "hello world", "max": 5})).get("truncated").unwrap().as_bool().unwrap());
        // Astral = 2 unit UTF-16: "a😀b" panjang 4; take(2) = "a" +
        // escape JSON mentah `\ud83d` (lone surrogate — serde_json
        // TIDAK BISA mem-parse-nya, jadi cek string mentah; org.json
        // di JVM mem-parse-nya kembali jadi lone surrogate, identik
        // dengan `take(2)` Kotlin — probe JVM: byte wire `61 3F`).
        let raw = worker_endpoint_json("{\"op\":\"slice_preview\",\"text\":\"a\u{1F600}b\",\"max\":2}").expect("seam ok");
        assert!(raw.contains("\"slice\": \"a\\ud83d\""), "raw={raw}");
        assert!(raw.contains("\"truncated\": true"), "raw={raw}");
        let got = ep("slice_preview", json!({"text": "a\u{1F600}b", "max": 3}));
        assert_eq!(got.get("slice"), Some(&json!("a\u{1F600}")));
        assert!(got.get("truncated").unwrap().as_bool().unwrap());
        let got = ep("slice_preview", json!({"text": "a\u{1F600}b", "max": 4}));
        assert_eq!(got.get("slice"), Some(&json!("a\u{1F600}b")));
        assert!(!got.get("truncated").unwrap().as_bool().unwrap());
    }

    #[test]
    fn preview_chars_bounds() {
        for (req, want) in [(0, 2000), (-5, 2000), (1, 1), (2000, 2000), (8000, 8000), (8001, 8000), (99999, 8000)] {
            assert_eq!(ep("preview_chars", json!({"requested": req})).get("value").unwrap().as_i64().unwrap(), want, "req {req}");
        }
    }

    #[test]
    fn hmac_rfc4231_vectors() {
        // RFC 4231 kasus 1 + 2 lewat API str (kunci/teks UTF-8, seperti
        // Kotlin `toByteArray(UTF_8)`).
        let k1 = "\u{000B}".repeat(20);
        assert_eq!(hmac_sha256_hex(&k1, "Hi There"), "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
        assert_eq!(ep("hmac_sha256_hex", json!({"key": "Jefe", "text": "what do ya want for nothing?"})).get("value"), Some(&json!("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")));
        // Kasus 3 + 6 memakai kunci biner (0xAA) yang TIDAK BISA
        // diwakili String UTF-8 — diuji lewat `hmac_sha256` byte
        // langsung (fungsi yang sama dipakai `hmac_sha256_hex`).
        assert_eq!(hex_lower(&hmac_sha256(&[0xAA; 20], &[0xDD; 50])), "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe");
        assert_eq!(hex_lower(&hmac_sha256(&[0xAA; 131], b"Test Using Larger Than Block-Size Key - Hash Key First")), "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54");
        // Key > 64 byte di-hash dulu — banding dengan kunci pendek yang berbeda hasilnya.
        assert_ne!(hmac_sha256_hex(&"\u{00AA}".repeat(131), "x"), hmac_sha256_hex("aa", "x"));
    }

    #[test]
    fn sign_verify_queue_roundtrip() {
        let signed = sign_queue_text("kunci-rahasia", "kerjakan laporan");
        assert!(signed.starts_with("HMAC-SHA256:"));
        assert_eq!(verify_queue_text("kunci-rahasia", &signed), Some("kerjakan laporan".to_string()));
        // Kunci salah -> None; isi diubah -> None; tanpa tanda tangan -> None.
        assert_eq!(verify_queue_text("kunci-lain", &signed), None);
        assert_eq!(verify_queue_text("kunci-rahasia", &signed.replace("laporan", "LAPORAN")), None);
        assert_eq!(verify_queue_text("kunci-rahasia", "kerjakan laporan"), None);
        assert!(!has_queue_signature("kerjakan laporan"));
        assert!(has_queue_signature(&signed));
        // Tanda tangan kosong / tanpa newline -> None.
        assert_eq!(verify_queue_text("k", "HMAC-SHA256:\nisi"), None);
        assert_eq!(verify_queue_text("k", "HMAC-SHA256:abc"), None);
        // Via seam JSON.
        let got = ep("sign_queue_text", json!({"key": "k", "text": "isi tugas"}));
        let s = got.get("value").unwrap().as_str().unwrap().to_string();
        let got = ep("verify_queue_text", json!({"key": "k", "signed": s}));
        assert_eq!(got.get("value"), Some(&json!("isi tugas")));
    }

    #[test]
    fn worker_scoped_set() {
        for a in ["workerDetail", "stopWorker", "restartWorker", "workerLog", "listTasks", "enqueueTask", "readTask", "writeTaskResult", "listResults", "readResult", "verifyTask"] {
            assert!(worker_scoped(a), "{a} scoped");
        }
        for a in ["health", "listWorkers", "spawnWorker", "setQuota", "resetWorker", "jhonListTasks", "jhonEnqueue", "jhonReadTask", "jhonWriteResult", "jhonListResults", ""] {
            assert!(!worker_scoped(a), "{a} bukan scoped");
        }
    }

    #[test]
    fn parse_headers_matrix() {
        let text = "GET /health HTTP/1.1\r\nAuthorization: Bearer tok123\r\nContent-Length: 42\r\nX-Dup: satu\r\nX-Dup: dua\r\nBarisTanpaColon\r\n:tanpa-kunci\r\n\r\n";
        let got = ep("parse_headers", json!({"text": text}));
        assert_eq!(got.get("req_line"), Some(&json!("GET /health HTTP/1.1")));
        assert_eq!(got["headers"]["authorization"], json!("Bearer tok123"));
        assert_eq!(got["headers"]["content-length"], json!("42"));
        assert_eq!(got["headers"]["x-dup"], json!("dua"));
        assert!(got["headers"].get("baristanpacolon").is_none());
        // Kunci di-lowercase + trim; nilai di-trim.
        let got = ep("parse_headers", json!({"text": "POST /x HTTP/1.1\r\n  X-Custom :   v v  \r\n\r\n"}));
        assert_eq!(got["headers"]["x-custom"], json!("v v"));
        // Header > 16 KB -> ok:false.
        let big = "X".repeat(17000);
        let got = ep("parse_headers", json!({"text": big}));
        assert_eq!(got.get("ok"), Some(&json!(false)));
    }

    #[test]
    fn build_response_matrix() {
        let got = ep("build_response", json!({"code": 200, "body_bytes": 13, "retry_after": null}));
        assert_eq!(got.get("head"), Some(&json!("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: 13\r\nConnection: close\r\n\r\n")));
        let got = ep("build_response", json!({"code": 503, "body_bytes": 100, "retry_after": 5}));
        assert_eq!(got.get("head"), Some(&json!("HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: 100\r\nConnection: close\r\nRetry-After: 5\r\n\r\n")));
        let got = ep("build_response", json!({"code": 431, "body_bytes": 0, "retry_after": null}));
        assert!(got.get("head").unwrap().as_str().unwrap().starts_with("HTTP/1.1 431 Request Header Fields Too Large\r\n"));
        // Kode tak dikenal -> reason "OK" (quirk Kotlin: else -> "OK").
        let got = ep("build_response", json!({"code": 418, "body_bytes": 1, "retry_after": null}));
        assert!(got.get("head").unwrap().as_str().unwrap().starts_with("HTTP/1.1 418 OK\r\n"));
    }

    #[test]
    fn idempotency_key_matrix() {
        // Header didahulukan (take 128).
        let got = ep("idempotency_key", json!({"action": "spawnWorker", "worker_id": "", "idem_header": "klien-123", "body": "{}"}));
        assert_eq!(got.get("key"), Some(&json!("hdr:klien-123")));
        // Spawn proot: sha16("echo hi") = 56a79f3b11544807.
        let got = ep("idempotency_key", json!({"action": "spawnWorker", "worker_id": "", "idem_header": null, "body": "{\"name\":\"Pekerja\",\"kind\":\"proot\",\"session\":\"worker1\",\"command\":\"echo hi\"}"}));
        assert_eq!(got.get("key"), Some(&json!("spawn:pekerja|proot|worker1|56a79f3b11544807")));
        // Nama di-trim + lowercase; default kind/session bila hilang.
        let got = ep("idempotency_key", json!({"action": "spawnWorker", "worker_id": "", "idem_header": null, "body": "{\"name\":\"  PEKERJA \",\"command\":\"echo hi\"}"}));
        assert_eq!(got.get("key"), Some(&json!("spawn:pekerja|proot|worker1|56a79f3b11544807")));
        // Spawn python memakai "code".
        let got = ep("idempotency_key", json!({"action": "spawnWorker", "worker_id": "", "idem_header": null, "body": "{\"name\":\"py\",\"kind\":\"python\",\"session\":\"worker2\",\"code\":\"print(1)\"}"}));
        assert!(got.get("key").unwrap().as_str().unwrap().starts_with("spawn:py|python|worker2|"));
        // Nama kosong -> null; body bukan JSON -> nama kosong -> null.
        assert_eq!(ep("idempotency_key", json!({"action": "spawnWorker", "worker_id": "", "idem_header": null, "body": "{\"name\":\"\"}"})).get("key"), Some(&Json::Null));
        assert_eq!(ep("idempotency_key", json!({"action": "spawnWorker", "worker_id": "", "idem_header": null, "body": "bukan json"})).get("key"), Some(&Json::Null));
        // Enqueue: sha16("kerjakan laporan") = aee2a04a144e10dc.
        let got = ep("idempotency_key", json!({"action": "enqueueTask", "worker_id": "w_7", "idem_header": null, "body": "{\"text\":\"kerjakan laporan\"}"}));
        assert_eq!(got.get("key"), Some(&json!("enqueue:w_7|aee2a04a144e10dc")));
        let got = ep("idempotency_key", json!({"action": "jhonEnqueue", "worker_id": "", "idem_header": null, "body": "{\"text\":\"kerjakan laporan\"}"}));
        assert_eq!(got.get("key"), Some(&json!("enqueue:jhon|aee2a04a144e10dc")));
        // Teks kosong -> null; aksi lain -> null.
        assert_eq!(ep("idempotency_key", json!({"action": "enqueueTask", "worker_id": "w_7", "idem_header": null, "body": "{\"text\":\"   \"}"})).get("key"), Some(&Json::Null));
        assert_eq!(ep("idempotency_key", json!({"action": "listWorkers", "worker_id": "", "idem_header": null, "body": "{}"})).get("key"), Some(&Json::Null));
    }

    #[test]
    fn seam_errors_are_json_not_panic() {
        let out = worker_endpoint_json("bukan json").expect("seam ok");
        let v: Json = serde_json::from_str(&out).expect("json ok");
        assert_eq!(v.get("ok"), Some(&json!(false)));
        let out = worker_endpoint_json("{\"op\":\"tidak-ada\"}").expect("seam ok");
        let v: Json = serde_json::from_str(&out).expect("json ok");
        assert_eq!(v.get("ok"), Some(&json!(false)));
        let out = worker_endpoint_json("{\"op\":\"parse_route\"}").expect("seam ok");
        let v: Json = serde_json::from_str(&out).expect("json ok");
        assert_eq!(v.get("ok"), Some(&json!(false)));
    }
}
