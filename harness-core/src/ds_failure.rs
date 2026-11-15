//! Port 1:1 dari `DsFailure.kt` (vc316 P1 — Pola 1, taksonomi error).
//!
//! Perilaku HARUS identik dengan Kotlin, termasuk:
//! - `input_exceeds_limit` -> CONTEXT_WINDOW non-retryable.
//! - Tangga rate-limit 10/20/35 dtk; Retry-After jadi jeda pertama.
//! - HTTP-date Retry-After dihitung selisihnya ke sekarang, clamp 1..120.
//! - `classify` memakai pasangan (kind, code) untuk retryable.
//!
//! Satu-satunya JVM-isme di Kotlin adalah `SimpleDateFormat` untuk
//! HTTP-date (baris 165 DsFailure.kt) — di sini diganti crate `httpdate`.
//! `lowercase(Locale.US)` Kotlin dipetakan ke `to_lowercase()` Rust
//! (sinyal yang dicari semuanya ASCII, jadi hasilnya sama).
//! Aritmetika integer di sini tidak ada yang bisa overflow diam-diam
//! (clamp sebelum kali 1000: maks 120 * 1000); tetap, tidak ada
//! `unwrap`/`expect` di jalur input luar.

#![forbid(unsafe_code)]

use std::time::{SystemTime, UNIX_EPOCH};

/// Tangga lama rate-limit (dipertahankan): 10 -> 20 -> 35 dtk.
pub const RATE_LIMIT_WAITS_MS: [i64; 3] = [10_000, 20_000, 35_000];

/// Jeda pendek retry 5xx (baru vc316): maks 2x.
pub const UPSTREAM_WAITS_MS: [i64; 2] = [2_000, 5_000];

/// Jeda retry transien jaringan (lama, dipertahankan): sekali 1,5 dtk.
pub const TRANSIENT_WAIT_MS: i64 = 1_500;

/// Batas clamp Retry-After sebagai jeda pertama tangga (detik).
pub const RETRY_AFTER_MIN_SEC: i64 = 1;
pub const RETRY_AFTER_MAX_SEC: i64 = 120;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FailureKind {
    RateLimit,
    Overloaded,
    Timeout,
    Upstream,
    Unavailable,
    Auth,
    ContextWindow,
}

impl FailureKind {
    /// Nama varian persis seperti enum Kotlin (`FailureKind.name`).
    pub fn as_str(&self) -> &'static str {
        match self {
            FailureKind::RateLimit => "RATE_LIMIT",
            FailureKind::Overloaded => "OVERLOADED",
            FailureKind::Timeout => "TIMEOUT",
            FailureKind::Upstream => "UPSTREAM",
            FailureKind::Unavailable => "UNAVAILABLE",
            FailureKind::Auth => "AUTH",
            FailureKind::ContextWindow => "CONTEXT_WINDOW",
        }
    }

    pub fn from_str(s: &str) -> Option<FailureKind> {
        match s {
            "RATE_LIMIT" => Some(FailureKind::RateLimit),
            "OVERLOADED" => Some(FailureKind::Overloaded),
            "TIMEOUT" => Some(FailureKind::Timeout),
            "UPSTREAM" => Some(FailureKind::Upstream),
            "UNAVAILABLE" => Some(FailureKind::Unavailable),
            "AUTH" => Some(FailureKind::Auth),
            "CONTEXT_WINDOW" => Some(FailureKind::ContextWindow),
            _ => None,
        }
    }
}

/// Hasil klasifikasi terstruktur (padanan CCC ExecutionFailure).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ExecutionFailure {
    pub kind: FailureKind,
    pub status_code: i32,
    pub retryable: bool,
}

fn lower(msg: Option<&str>) -> String {
    msg.unwrap_or("").to_lowercase()
}

/// Sinyal rate-limit dari body/pesan (sudah ada di kode lama).
pub fn is_rate_limit_signal(msg: Option<&str>) -> bool {
    let m = lower(msg);
    m.contains("rate_limit") || m.contains("too frequent")
}

/// Sinyal suspend/blokir akun (sudah ada di kode lama).
pub fn is_suspend_signal(msg: Option<&str>) -> bool {
    let m = lower(msg);
    m.contains("suspended")
        || m.contains("violation")
        || m.contains("40003")
        || m.contains("invalid_token")
        || m.contains("account_banned")
}

/// Sinyal EKSPLISIT server bahwa sesi chat tidak berlaku lagi (lama).
pub fn is_session_invalid_signal(msg: Option<&str>) -> bool {
    let m = lower(msg);
    if m.contains("session_not_found")
        || m.contains("invalid_session")
        || m.contains("session not found")
    {
        return true;
    }
    m.contains("session")
        && (m.contains("not found")
            || m.contains("invalid")
            || m.contains("not exist")
            || m.contains("does not exist")
            || m.contains("deleted")
            || m.contains("no such"))
}

/// Stream putus / selesai tanpa jawaban — penanda internal postCompletion.
pub fn is_stream_cut(msg: Option<&str>) -> bool {
    let m = lower(msg);
    m.contains("stream_terputus") || m.contains("jawaban_kosong")
}

/// Sinyal timeout (sudah ada di kode lama).
pub fn is_timeout_signal(msg: Option<&str>) -> bool {
    let m = lower(msg);
    m.contains("timeout") || m.contains("timed out")
}

/// Sinyal transien jaringan: gagal konek / DNS (sudah ada di kode lama).
pub fn is_network_signal(msg: Option<&str>) -> bool {
    let m = lower(msg);
    m.contains("failed to connect")
        || m.contains("resolve host")
        || m.contains("no address associated")
        || m.contains("unknownhost")
}

/// Sinyal context-window (TERAMATI di lapangan + acuan CCC). PARSIAL:
/// hanya dua bentuk ini yang terverifikasi; jangan tambah tebakan.
pub fn is_context_window_signal(msg: Option<&str>) -> bool {
    let m = lower(msg);
    m.contains("context-window-exceeded")
        || m.contains("context_window_exceeded")
        || m.contains("input_exceeds_limit")
}

/// Sinyal overload eksplisit di body (untuk 503 / pesan server).
pub fn is_overload_signal(msg: Option<&str>) -> bool {
    lower(msg).contains("overload")
}

/// Ambil kode HTTP dari pesan "HTTP <code> di ..." (postJson/postCompletion
/// menempel kode ke string sejak dulu). 0 = tidak ada kode di pesan.
/// Padanan `Regex("HTTP\\s+(\\d{3})")` Kotlin — tanpa crate regex:
/// cari "HTTP", lewati spasi, ambil tepat 3 digit pertama.
pub fn parse_http_code(message: Option<&str>) -> i32 {
    let msg = match message {
        Some(m) if !m.trim().is_empty() => m,
        _ => return 0,
    };
    let bytes = msg.as_bytes();
    let mut i = 0usize;
    while i + 4 <= bytes.len() {
        if &bytes[i..i + 4] == b"HTTP" {
            let mut j = i + 4;
            // \s+ : minimal satu whitespace (spasi/tab/dsb — byte ASCII).
            let mut skipped = 0usize;
            while j < bytes.len() && (bytes[j] as char).is_ascii_whitespace() {
                j += 1;
                skipped += 1;
            }
            if skipped > 0
                && j + 3 <= bytes.len()
                && bytes[j].is_ascii_digit()
                && bytes[j + 1].is_ascii_digit()
                && bytes[j + 2].is_ascii_digit()
            {
                let d = ((bytes[j] - b'0') as i32) * 100
                    + ((bytes[j + 1] - b'0') as i32) * 10
                    + (bytes[j + 2] - b'0') as i32;
                return d;
            }
        }
        i += 1;
    }
    0
}

/// Ambil detik Retry-After dari suffix " [retry_after=<n>]" yang ditempel
/// postJson/postCompletion ke pesan error. null = tidak ada / tak terbaca.
/// Padanan `Regex("retry_after=(\\d+)")` Kotlin.
pub fn parse_retry_after_seconds(message: Option<&str>) -> Option<i64> {
    let msg = match message {
        Some(m) if !m.trim().is_empty() => m,
        _ => return None,
    };
    let key = "retry_after=";
    let idx = msg.find(key)?;
    let rest = &msg[idx + key.len()..];
    let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
    if digits.is_empty() {
        return None;
    }
    // Kotlin `toLongOrNull()`: overflow -> null (bukan clamp).
    let v: i64 = digits.parse().ok()?;
    Some(v.clamp(RETRY_AFTER_MIN_SEC, RETRY_AFTER_MAX_SEC))
}

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Parse nilai mentah header Retry-After: detik ("120") atau tanggal
/// HTTP-date (dihitung selisihnya ke sekarang). null bila tak terbaca.
/// Hasil di-clamp 1..120 detik.
pub fn parse_retry_after_header(value: Option<&str>) -> Option<i64> {
    let v = value?.trim();
    if v.is_empty() {
        return None;
    }
    if let Ok(n) = v.parse::<i64>() {
        return Some(n.clamp(RETRY_AFTER_MIN_SEC, RETRY_AFTER_MAX_SEC));
    }
    // Bentuk tanggal: "Wed, 21 Oct 2015 07:28:00 GMT" (crate httpdate).
    let date = httpdate::parse_http_date(v).ok()?;
    let date_ms = date
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .ok()?;
    // Kotlin: `(date.time - now) / 1000` — pembagian Long trunc ke nol,
    // sama seperti pembagian i64 Rust.
    let secs = (date_ms - now_millis()) / 1000;
    if secs < 0 {
        None
    } else {
        Some(secs.clamp(RETRY_AFTER_MIN_SEC, RETRY_AFTER_MAX_SEC))
    }
}

/// Suffix yang ditempel postJson/postCompletion ke pesan error bila
/// header Retry-After ada. "" bila tidak ada.
pub fn retry_after_suffix(retry_after_sec: Option<i64>) -> String {
    match retry_after_sec {
        Some(v) => format!(" [retry_after={v}]"),
        None => String::new(),
    }
}

/// Tangga rate-limit: Retry-After (bila ada) jadi jeda PERTAMA,
/// diikuti 20/35 dtk; tanpa header = tangga lama 10/20/35 dtk.
pub fn rate_limit_waits(retry_after_sec: Option<i64>) -> [i64; 3] {
    match retry_after_sec {
        None => RATE_LIMIT_WAITS_MS,
        Some(sec) => {
            let first =
                sec.clamp(RETRY_AFTER_MIN_SEC, RETRY_AFTER_MAX_SEC) * 1000;
            [first, 20_000, 35_000]
        }
    }
}

/// Apakah (kind, code) ini boleh diulang? UPSTREAM hanya retryable
/// bila code 5xx — 400/402/413 dipetakan ke UPSTREAM tapi TIDAK
/// retryable (lihat classify).
pub fn is_retryable(kind: FailureKind, code: i32) -> bool {
    match kind {
        FailureKind::RateLimit => true,
        FailureKind::Overloaded => true,
        FailureKind::Timeout => true,
        FailureKind::Unavailable => true,
        FailureKind::Upstream => (500..=599).contains(&code),
        FailureKind::Auth => false,
        FailureKind::ContextWindow => false,
    }
}

/// Klasifikasi satu kegagalan. `code` = kode HTTP bila pemanggil
/// mengetahuinya (0 = tidak tahu — kode lalu dicari di pesan exception,
/// pola "HTTP <code>"). `body` = cuplikan body/pesan server bila ada
/// (boleh "" — exception message dipakai sebagai fallback).
/// Urutan: context-window dulu (bisa datang dengan HTTP 200 di SSE),
/// lalu suspend, lalu kode HTTP, lalu sinyal substring di pesan.
pub fn classify(code: i32, body: &str, exception_message: &str) -> FailureKind {
    let text = format!("{body} {exception_message}").to_lowercase();
    // 1. Context-window: bisa datang sebagai finish_reason di stream
    //    HTTP 200 (bukan error HTTP), jadi dicek sebelum kode.
    if is_context_window_signal(Some(&text)) {
        return FailureKind::ContextWindow;
    }
    // 2. Suspend akun: non-retryable; runChat sudah menandai akun mati.
    if is_suspend_signal(Some(&text)) {
        return FailureKind::Auth;
    }
    // 3. Kode HTTP (argumen, atau ter-parse dari pesan "HTTP <code>").
    let eff_code = if code != 0 {
        code
    } else {
        parse_http_code(Some(exception_message))
    };
    match eff_code {
        429 => return FailureKind::RateLimit,
        401 | 403 => return FailureKind::Auth,
        400 | 402 | 413 => return FailureKind::Upstream, // non-retryable
        500..=599 => {
            return if is_overload_signal(Some(&text)) {
                FailureKind::Overloaded
            } else {
                FailureKind::Upstream
            }
        }
        _ => {}
    }
    // 4. Sinyal substring di pesan (jalur lama, kini terpusat).
    if is_rate_limit_signal(Some(&text)) {
        return FailureKind::RateLimit;
    }
    if is_overload_signal(Some(&text)) {
        return FailureKind::Overloaded;
    }
    if is_timeout_signal(Some(&text)) {
        return FailureKind::Timeout;
    }
    if is_network_signal(Some(&text)) {
        return FailureKind::Unavailable;
    }
    // 5. Tidak dikenal: jangan retry membabi-buta.
    FailureKind::Upstream
}

/// classify() + statusCode efektif + retryable dalam satu paket.
pub fn classify_failure(
    code: i32,
    body: &str,
    exception_message: &str,
) -> ExecutionFailure {
    let kind = classify(code, body, exception_message);
    let eff_code = if code != 0 {
        code
    } else {
        parse_http_code(Some(exception_message))
    };
    ExecutionFailure {
        kind,
        status_code: eff_code,
        retryable: is_retryable(kind, eff_code),
    }
}

/// Hasil keputusan handleError: COOLDOWN = rate-limit murni (tahan
/// 15 mnt, JANGAN tandai sesi mati); SESSION_DEAD = suspend/token
/// (bgSessionDead=true + notif 7702 sekali per episode); GENERIC =
/// gagal biasa (notif gagal per origin bila origin terjadwal).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BgErrorAction {
    Cooldown,
    SessionDead,
    Generic,
}

impl BgErrorAction {
    pub fn as_str(&self) -> &'static str {
        match self {
            BgErrorAction::Cooldown => "COOLDOWN",
            BgErrorAction::SessionDead => "SESSION_DEAD",
            BgErrorAction::Generic => "GENERIC",
        }
    }
}

pub fn bg_action_for(exception_message: &str) -> BgErrorAction {
    let failure = classify_failure(0, "", exception_message);
    if failure.kind == FailureKind::RateLimit
        && !is_suspend_signal(Some(exception_message))
    {
        return BgErrorAction::Cooldown;
    }
    if exception_message.to_lowercase().contains("token")
        || is_suspend_signal(Some(exception_message))
    {
        return BgErrorAction::SessionDead;
    }
    BgErrorAction::Generic
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, SystemTime};

    // Pemetaan hitung: 103 tes = 63 port langsung cek perilaku P1
    // (§1: 36, §2: 2.1–2.14, §3: 3.1–3.10, §5: 5.1–5.3)
    // + 40 tes granular helper yang sama fungsinya
    // (sinyal per-helper + tiap cabang is_retryable).
    // Cek P1 yang berupa grep sumber Kotlin (2.15–2.20, 3.11–3.18,
    // 4.x, 5.4–5.20) TIDAK bisa diport ke Rust — itu dibuktikan
    // suite JVM yang tetap jalan melawan wrapper Kotlin (103/103).

    fn ex(msg: &str) -> &str {
        msg
    }

    // ================= 1. classify / classifyFailure (36) =================
    #[test]
    fn t1_1_429_rate_limit_retryable() {
        let f = classify_failure(429, "", ex("HTTP 429 di completion: too many requests"));
        assert_eq!(f.kind, FailureKind::RateLimit);
        assert!(f.retryable);
        assert_eq!(f.status_code, 429);
    }
    #[test]
    fn t1_2_http_429_di_pesan_rate_limit() {
        let f = classify_failure(0, "", ex("HTTP 429 di /api/v0/chat/completion: Messages too frequent"));
        assert_eq!(f.kind, FailureKind::RateLimit);
        assert!(f.retryable);
        assert_eq!(f.status_code, 429);
    }
    #[test]
    fn t1_3_rate_limit_body_sse() {
        let f = classify_failure(0, "", ex("rate_limit_reached — server DeepSeek membatasi laju permintaan (Messages too frequent); stream berhenti tanpa jawaban final."));
        assert_eq!(f.kind, FailureKind::RateLimit);
        assert!(f.retryable);
    }
    #[test]
    fn t1_4_http_500_upstream_retryable() {
        let f = classify_failure(500, "", ex("HTTP 500 di completion: boom"));
        assert_eq!(f.kind, FailureKind::Upstream);
        assert!(f.retryable);
        assert_eq!(f.status_code, 500);
    }
    #[test]
    fn t1_4_http_502_upstream_retryable() {
        let f = classify_failure(502, "", ex("HTTP 502 di completion: boom"));
        assert_eq!(f.kind, FailureKind::Upstream);
        assert!(f.retryable);
        assert_eq!(f.status_code, 502);
    }
    #[test]
    fn t1_4_http_503_upstream_retryable() {
        let f = classify_failure(503, "", ex("HTTP 503 di completion: boom"));
        assert_eq!(f.kind, FailureKind::Upstream);
        assert!(f.retryable);
        assert_eq!(f.status_code, 503);
    }
    #[test]
    fn t1_4_http_504_upstream_retryable() {
        let f = classify_failure(504, "", ex("HTTP 504 di completion: boom"));
        assert_eq!(f.kind, FailureKind::Upstream);
        assert!(f.retryable);
        assert_eq!(f.status_code, 504);
    }
    #[test]
    fn t1_5_503_overload_overloaded_retryable() {
        let f = classify_failure(503, "the server is overloaded, try again later", ex("HTTP 503 di completion: overloaded"));
        assert_eq!(f.kind, FailureKind::Overloaded);
        assert!(f.retryable);
    }
    #[test]
    fn t1_6_http_401_auth_non_retryable() {
        let f = classify_failure(401, "", ex("HTTP 401 di completion: unauthorized"));
        assert_eq!(f.kind, FailureKind::Auth);
        assert!(!f.retryable);
    }
    #[test]
    fn t1_6_http_403_auth_non_retryable() {
        let f = classify_failure(403, "", ex("HTTP 403 di completion: unauthorized"));
        assert_eq!(f.kind, FailureKind::Auth);
        assert!(!f.retryable);
    }
    #[test]
    fn t1_7_suspend_suspended_auth() {
        let f = classify_failure(0, "", ex("SERVER suspended: akun bermasalah"));
        assert_eq!(f.kind, FailureKind::Auth);
        assert!(!f.retryable);
    }
    #[test]
    fn t1_7_suspend_violation_auth() {
        let f = classify_failure(0, "", ex("SERVER violation: akun bermasalah"));
        assert_eq!(f.kind, FailureKind::Auth);
        assert!(!f.retryable);
    }
    #[test]
    fn t1_7_suspend_40003_auth() {
        let f = classify_failure(0, "", ex("SERVER 40003: akun bermasalah"));
        assert_eq!(f.kind, FailureKind::Auth);
        assert!(!f.retryable);
    }
    #[test]
    fn t1_7_suspend_invalid_token_auth() {
        let f = classify_failure(0, "", ex("SERVER invalid_token: akun bermasalah"));
        assert_eq!(f.kind, FailureKind::Auth);
        assert!(!f.retryable);
    }
    #[test]
    fn t1_7_suspend_account_banned_auth() {
        let f = classify_failure(0, "", ex("SERVER account_banned: akun bermasalah"));
        assert_eq!(f.kind, FailureKind::Auth);
        assert!(!f.retryable);
    }
    #[test]
    fn t1_7b_server_40003_auth() {
        let f = classify_failure(0, "", ex("SERVER 40003: account suspended"));
        assert_eq!(f.kind, FailureKind::Auth);
        assert!(!f.retryable);
    }
    #[test]
    fn t1_8_http_400_non_retryable() {
        let f = classify_failure(400, "", ex("HTTP 400 di completion: bad request"));
        assert!(!f.retryable);
    }
    #[test]
    fn t1_8_http_402_non_retryable() {
        let f = classify_failure(402, "", ex("HTTP 402 di completion: bad request"));
        assert!(!f.retryable);
    }
    #[test]
    fn t1_8_http_413_non_retryable() {
        let f = classify_failure(413, "", ex("HTTP 413 di completion: bad request"));
        assert!(!f.retryable);
    }
    #[test]
    fn t1_9_input_exceeds_limit_context_window() {
        let f = classify_failure(0, "{\"finish_reason\":\"input_exceeds_limit\"}", ex("jawaban_kosong: stream selesai resmi tapi tanpa fragmen jawaban"));
        assert_eq!(f.kind, FailureKind::ContextWindow);
        assert!(!f.retryable);
    }
    #[test]
    fn t1_9b_context_window_exceeded() {
        let f = classify_failure(0, "", ex("context-window-exceeded: prompt too long"));
        assert_eq!(f.kind, FailureKind::ContextWindow);
        assert!(!f.retryable);
    }
    #[test]
    fn t1_9c_context_window_exceeded_underscore() {
        let f = classify_failure(0, "", ex("context_window_exceeded"));
        assert_eq!(f.kind, FailureKind::ContextWindow);
        assert!(!f.retryable);
    }
    #[test]
    fn t1_10_400_plus_input_exceeds_limit_context_window() {
        let f = classify_failure(400, "input_exceeds_limit", ex("HTTP 400 di completion: input_exceeds_limit"));
        assert_eq!(f.kind, FailureKind::ContextWindow);
        assert!(!f.retryable);
    }
    #[test]
    fn t1_11_timeout_read_timed_out() {
        let f = classify_failure(0, "", ex("Read timed out"));
        assert_eq!(f.kind, FailureKind::Timeout);
        assert!(f.retryable);
    }
    #[test]
    fn t1_11_timeout_after_ms() {
        let f = classify_failure(0, "", ex("timeout after 20000ms"));
        assert_eq!(f.kind, FailureKind::Timeout);
        assert!(f.retryable);
    }
    #[test]
    fn t1_11_timeout_socket_timeout() {
        let f = classify_failure(0, "", ex("SocketTimeoutException: timeout"));
        assert_eq!(f.kind, FailureKind::Timeout);
        assert!(f.retryable);
    }
    #[test]
    fn t1_12_jaringan_failed_to_connect() {
        let f = classify_failure(0, "", ex("failed to connect to chat.deepseek.com"));
        assert_eq!(f.kind, FailureKind::Unavailable);
        assert!(f.retryable);
    }
    #[test]
    fn t1_12_jaringan_resolve_host() {
        let f = classify_failure(0, "", ex("Unable to resolve host \"chat.deepseek.com\""));
        assert_eq!(f.kind, FailureKind::Unavailable);
        assert!(f.retryable);
    }
    #[test]
    fn t1_12_jaringan_unknownhost() {
        let f = classify_failure(0, "", ex("UnknownHostException: chat.deepseek.com"));
        assert_eq!(f.kind, FailureKind::Unavailable);
        assert!(f.retryable);
    }
    #[test]
    fn t1_12_jaringan_no_address() {
        let f = classify_failure(0, "", ex("No address associated with hostname"));
        assert_eq!(f.kind, FailureKind::Unavailable);
        assert!(f.retryable);
    }
    #[test]
    fn t1_13_is_stream_cut_kedua_penanda() {
        assert!(is_stream_cut(Some("stream_terputus: koneksi putus")));
        assert!(is_stream_cut(Some("jawaban_kosong: stream selesai resmi")));
    }
    #[test]
    fn t1_13b_stream_terputus_bukan_rate_limit() {
        let f = classify_failure(0, "", ex("stream_terputus: koneksi putus"));
        assert_ne!(f.kind, FailureKind::RateLimit);
    }
    #[test]
    fn t1_14_error_tak_dikenal_non_retryable() {
        let f = classify_failure(0, "", ex("token sesi tidak ditemukan di WebView (userToken kosong)."));
        assert!(!f.retryable);
    }
    #[test]
    fn t1_15_parse_http_code_429() {
        assert_eq!(parse_http_code(Some("HTTP 429 di completion: x")), 429);
    }
    #[test]
    fn t1_15b_parse_http_code_tanpa_kode_nol() {
        assert_eq!(parse_http_code(Some("rate_limit_reached")), 0);
    }
    #[test]
    fn t1_15c_parse_http_code_none_nol() {
        assert_eq!(parse_http_code(None), 0);
    }
    #[test]
    fn t1_16_enum_7_kind() {
        let kinds = [
            FailureKind::RateLimit,
            FailureKind::Overloaded,
            FailureKind::Timeout,
            FailureKind::Upstream,
            FailureKind::Unavailable,
            FailureKind::Auth,
            FailureKind::ContextWindow,
        ];
        let names: Vec<&str> = kinds.iter().map(|k| k.as_str()).collect();
        assert_eq!(
            names,
            vec![
                "RATE_LIMIT",
                "OVERLOADED",
                "TIMEOUT",
                "UPSTREAM",
                "UNAVAILABLE",
                "AUTH",
                "CONTEXT_WINDOW"
            ]
        );
        for (k, n) in kinds.iter().zip(names.iter()) {
            assert_eq!(FailureKind::from_str(n), Some(*k));
        }
        assert_eq!(FailureKind::from_str("TIDAK_ADA"), None);
    }

    // ================= 2. Retry-After perilaku (2.1–2.14) =================
    #[test]
    fn t2_1_header_detik_30() {
        assert_eq!(parse_retry_after_header(Some("30")), Some(30));
    }
    #[test]
    fn t2_2_header_0_clamp_1() {
        assert_eq!(parse_retry_after_header(Some("0")), Some(1));
    }
    #[test]
    fn t2_3_header_600_clamp_120() {
        assert_eq!(parse_retry_after_header(Some("600")), Some(120));
    }
    #[test]
    fn t2_4_header_none_null() {
        assert_eq!(parse_retry_after_header(None), None);
    }
    #[test]
    fn t2_5_header_sampah_null() {
        assert_eq!(parse_retry_after_header(Some("nanti dulu")), None);
    }
    #[test]
    fn t2_6_header_http_date_masa_depan_60() {
        let future = httpdate::fmt_http_date(SystemTime::now() + Duration::from_secs(60));
        let v = parse_retry_after_header(Some(&future));
        assert!(v.is_some() && (55..=60).contains(&v.unwrap()), "dapat {v:?} dari {future}");
    }
    #[test]
    fn t2_7_header_http_date_masa_lalu_null() {
        let past = httpdate::fmt_http_date(SystemTime::now() - Duration::from_secs(60));
        assert_eq!(parse_retry_after_header(Some(&past)), None);
    }
    #[test]
    fn t2_8_parse_retry_after_seconds_suffix() {
        assert_eq!(
            parse_retry_after_seconds(Some("HTTP 429 di completion: x [retry_after=45]")),
            Some(45)
        );
    }
    #[test]
    fn t2_9_parse_retry_after_seconds_tanpa_suffix_null() {
        assert_eq!(
            parse_retry_after_seconds(Some("HTTP 429 di completion: x")),
            None
        );
    }
    #[test]
    fn t2_10_retry_after_suffix_30() {
        assert_eq!(retry_after_suffix(Some(30)), " [retry_after=30]");
    }
    #[test]
    fn t2_11_retry_after_suffix_none_kosong() {
        assert_eq!(retry_after_suffix(None), "");
    }
    #[test]
    fn t2_12_rate_limit_waits_none_tangga_lama() {
        assert_eq!(rate_limit_waits(None), [10_000, 20_000, 35_000]);
    }
    #[test]
    fn t2_13_rate_limit_waits_45() {
        assert_eq!(rate_limit_waits(Some(45)), [45_000, 20_000, 35_000]);
    }
    #[test]
    fn t2_14_rate_limit_waits_300_clamp_120() {
        let w = rate_limit_waits(Some(300));
        assert_eq!(w[0], 120_000);
        assert_eq!(w[1], 20_000);
        assert_eq!(w[2], 35_000);
    }

    // ================= 3. bgActionFor perilaku (3.1–3.10) =================
    #[test]
    fn t3_1_rate_limit_murni_cooldown() {
        assert_eq!(
            bg_action_for(ex("rate_limit_reached — server membatasi laju")),
            BgErrorAction::Cooldown
        );
    }
    #[test]
    fn t3_2_http_429_cooldown() {
        assert_eq!(
            bg_action_for(ex("HTTP 429 di completion: too frequent [retry_after=30]")),
            BgErrorAction::Cooldown
        );
    }
    #[test]
    fn t3_3_too_frequent_cooldown() {
        assert_eq!(
            bg_action_for(ex("Messages too frequent")),
            BgErrorAction::Cooldown
        );
    }
    #[test]
    fn t3_4_suspended_session_dead() {
        assert_eq!(
            bg_action_for(ex("account suspended")),
            BgErrorAction::SessionDead
        );
    }
    #[test]
    fn t3_5_server_40003_session_dead() {
        assert_eq!(
            bg_action_for(ex("SERVER 40003: account suspended")),
            BgErrorAction::SessionDead
        );
    }
    #[test]
    fn t3_6_token_hilang_session_dead() {
        assert_eq!(
            bg_action_for(ex("token sesi tidak ditemukan di WebView")),
            BgErrorAction::SessionDead
        );
    }
    #[test]
    fn t3_7_http_500_generic() {
        assert_eq!(
            bg_action_for(ex("HTTP 500 di completion: boom")),
            BgErrorAction::Generic
        );
    }
    #[test]
    fn t3_8_timeout_generic() {
        assert_eq!(
            bg_action_for(ex("Read timed out")),
            BgErrorAction::Generic
        );
    }
    #[test]
    fn t3_9_rate_limit_plus_suspended_session_dead() {
        assert_eq!(
            bg_action_for(ex("rate_limit + account suspended")),
            BgErrorAction::SessionDead
        );
    }
    #[test]
    fn t3_10_context_window_generic() {
        assert_eq!(
            bg_action_for(ex("input_exceeds_limit")),
            BgErrorAction::Generic
        );
    }

    // ================= 5. Konstanta tangga lama (5.1–5.3) =================
    #[test]
    fn t5_1_rate_limit_waits_10_20_35() {
        assert_eq!(RATE_LIMIT_WAITS_MS, [10_000, 20_000, 35_000]);
    }
    #[test]
    fn t5_2_upstream_waits_2_5() {
        assert_eq!(UPSTREAM_WAITS_MS, [2_000, 5_000]);
    }
    #[test]
    fn t5_3_transient_wait_1500() {
        assert_eq!(TRANSIENT_WAIT_MS, 1_500);
    }

    // ================= Granular helper (40) =================
    #[test]
    fn h_rate_limit_signal_rate_limit() {
        assert!(is_rate_limit_signal(Some("rate_limit_reached")));
    }
    #[test]
    fn h_rate_limit_signal_too_frequent() {
        assert!(is_rate_limit_signal(Some("Messages too frequent")));
    }
    #[test]
    fn h_rate_limit_signal_false() {
        assert!(!is_rate_limit_signal(Some("semua baik-baik saja")));
        assert!(!is_rate_limit_signal(None));
    }
    #[test]
    fn h_suspend_signal_suspended() {
        assert!(is_suspend_signal(Some("account suspended")));
    }
    #[test]
    fn h_suspend_signal_violation() {
        assert!(is_suspend_signal(Some("policy violation")));
    }
    #[test]
    fn h_suspend_signal_40003() {
        assert!(is_suspend_signal(Some("SERVER 40003")));
    }
    #[test]
    fn h_suspend_signal_invalid_token() {
        assert!(is_suspend_signal(Some("invalid_token")));
    }
    #[test]
    fn h_suspend_signal_account_banned() {
        assert!(is_suspend_signal(Some("account_banned")));
    }
    #[test]
    fn h_suspend_signal_false() {
        assert!(!is_suspend_signal(Some("rate_limit")));
        assert!(!is_suspend_signal(None));
    }
    #[test]
    fn h_session_invalid_session_not_found() {
        assert!(is_session_invalid_signal(Some("session_not_found")));
    }
    #[test]
    fn h_session_invalid_invalid_session() {
        assert!(is_session_invalid_signal(Some("invalid_session")));
    }
    #[test]
    fn h_session_invalid_session_not_found_spasi() {
        assert!(is_session_invalid_signal(Some("session not found")));
    }
    #[test]
    fn h_session_invalid_session_plus_not_found() {
        assert!(is_session_invalid_signal(Some("the session was not found")));
    }
    #[test]
    fn h_session_invalid_session_deleted() {
        assert!(is_session_invalid_signal(Some("session deleted by server")));
    }
    #[test]
    fn h_session_invalid_session_saja_false() {
        assert!(!is_session_invalid_signal(Some("session aktif")));
    }
    #[test]
    fn h_session_invalid_not_found_tanpa_session_false() {
        assert!(!is_session_invalid_signal(Some("not found")));
    }
    #[test]
    fn h_stream_cut_terputus() {
        assert!(is_stream_cut(Some("stream_terputus")));
    }
    #[test]
    fn h_stream_cut_jawaban_kosong() {
        assert!(is_stream_cut(Some("jawaban_kosong")));
    }
    #[test]
    fn h_stream_cut_false() {
        assert!(!is_stream_cut(Some("stream selesai normal")));
    }
    #[test]
    fn h_timeout_signal_timeout() {
        assert!(is_timeout_signal(Some("timeout after 20000ms")));
    }
    #[test]
    fn h_timeout_signal_timed_out() {
        assert!(is_timeout_signal(Some("Read timed out")));
    }
    #[test]
    fn h_timeout_signal_false() {
        assert!(!is_timeout_signal(Some("selesai cepat")));
    }
    #[test]
    fn h_network_signal_failed_to_connect() {
        assert!(is_network_signal(Some("failed to connect")));
    }
    #[test]
    fn h_network_signal_resolve_host() {
        assert!(is_network_signal(Some("Unable to resolve host")));
    }
    #[test]
    fn h_network_signal_no_address() {
        assert!(is_network_signal(Some("No address associated with hostname")));
    }
    #[test]
    fn h_network_signal_unknownhost() {
        assert!(is_network_signal(Some("UnknownHostException")));
    }
    #[test]
    fn h_network_signal_false() {
        assert!(!is_network_signal(Some("HTTP 500")));
    }
    #[test]
    fn h_context_window_dash() {
        assert!(is_context_window_signal(Some("context-window-exceeded")));
    }
    #[test]
    fn h_context_window_underscore() {
        assert!(is_context_window_signal(Some("context_window_exceeded")));
    }
    #[test]
    fn h_context_window_input_exceeds() {
        assert!(is_context_window_signal(Some("input_exceeds_limit")));
    }
    #[test]
    fn h_context_window_false() {
        assert!(!is_context_window_signal(Some("prompt biasa")));
    }
    #[test]
    fn h_is_retryable_rate_limit() {
        assert!(is_retryable(FailureKind::RateLimit, 429));
    }
    #[test]
    fn h_is_retryable_overloaded() {
        assert!(is_retryable(FailureKind::Overloaded, 503));
    }
    #[test]
    fn h_is_retryable_timeout() {
        assert!(is_retryable(FailureKind::Timeout, 0));
    }
    #[test]
    fn h_is_retryable_unavailable() {
        assert!(is_retryable(FailureKind::Unavailable, 0));
    }
    #[test]
    fn h_is_retryable_upstream_5xx() {
        assert!(is_retryable(FailureKind::Upstream, 500));
        assert!(is_retryable(FailureKind::Upstream, 599));
    }
    #[test]
    fn h_is_retryable_upstream_4xx_false() {
        assert!(!is_retryable(FailureKind::Upstream, 400));
        assert!(!is_retryable(FailureKind::Upstream, 413));
    }
    #[test]
    fn h_is_retryable_auth_false() {
        assert!(!is_retryable(FailureKind::Auth, 401));
    }
    #[test]
    fn h_is_retryable_context_window_false() {
        assert!(!is_retryable(FailureKind::ContextWindow, 200));
    }
}
