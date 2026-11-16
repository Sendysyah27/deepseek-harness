//! Port 1:1 dari `ConcatApiClient.kt` (vc315, 1.209 baris) — klien
//! JSON-RPC Concat: TCP 127.0.0.1:7420, auth baris pertama, 21 method,
//! cap baris 4 MiB, satu koneksi per panggilan.
//!
//! Desain seam (BUILD-VC319.md §2): SATU fungsi JNI stateless
//! `concatApiJson(request)` — request JSON `{"op","token","args",
//! "previewDir"}`; respons JSON `{"ok":true,"value":...}` atau
//! `{"ok":false,"kind":"auth"|"down"|"api","message":...}`. Tiap
//! panggilan = satu koneksi TCP (buka -> auth id 1 -> call id 2 ->
//! tutup). TIDAK ADA state persisten Rust (larangan peta §3.3:
//! tanpa pointer mentah jlong); `ConcatApiClient.kt` Kotlin tetap
//! memegang `connectionOpener`/`previewDir` untuk jalur fallback.
//!
//! Porting MURNI: quirk Kotlin DIREPLIKASI (dikunci tes), bukan
//! diperbaiki. Yang paling penting (semua dibuktikan probe JVM
//! 16 Nov 2026, /tmp/probe5):
//! - `String.trim()` tanpa argumen = `Character.isWhitespace`
//!   (NBSP/EM SPACE tepi DIBUANG) — pakai `kotlin_trim` slice 4,
//!   BUKAN `str::trim()` Rust (tanpa U+00A0/U+001C-U+001F).
//! - `String.format("%.2f")` (Java Formatter) membulatkan HALF_UP
//!   atas desimal `Double.toString` (terpendek): 0.125 -> "0.13",
//!   2.675 -> "2.68". Rust `{:.2}` (half-even) MENYIMPANG —
//!   karena itu `java_format_2f` membulatkan sendiri di atas string
//!   desimal terpendek (`java_double_string`).
//! - `Double.toString` Java: "12.5", "0.3", tapi "1.0E16" / "1.0E-4"
//!   (notasi ilmiah >= 1e7 atau < 1e-3) — `java_double_string`
//!   menirunya; Rust `{}` tidak pernah ilmiah.
//! - `JSONObject.optInt/optLong/optDouble/optString/optBoolean`:
//!   angka String ikut diparse ("12.5" -> 12 int; "abc" -> default),
//!   boolean -> default, NULL/hilang -> default; `has()` TRUE untuk
//!   nilai NULL (probe); `opt()` mengembalikan sentinel NULL.
//!   org.json 20240303 mem-parse desimal sebagai BigDecimal:
//!   `optString` memberi "1E+16" (bukan "1.0E16" ala Double.toString)
//!   dan "12345678.0" (skala literal dipertahankan) — f64 Rust tak
//!   menyimpan skala literal, jadi `json_number_string` memakai
//!   pendekatan: bulat -> polos, pecahan -> gaya Double.toString.
//!   Hanya terjangkau bila server mengirim angka di tempat string.
//! - `JSONObject(String)` LENIENT: single-quote + trailing-comma
//!   diterima, `NaN` literal jadi STRING "NaN", tapi duplikat key
//!   DITOLAK. `serde_json` ketat di semua itu — divergensi dicatat
//!   (server Concat `json.rs` memancarkan JSON ketat, jadi tidak
//!   terjangkau di lapangan; lihat BUILD-VC319.md §5).
//! - `Base64.getDecoder()` Java: padding boleh hilang ("aGVsbG8",
//!   "aGk" OK), whitespace/karakter ilegal DITOLAK, panjang % 4 == 1
//!   DITOLAK, trailing-bit tidak dicek ("AB" -> [0]).
//! - Cap baris dihitung Kotlin per CHAR UTF-16 (surrogate = 2 byte
//!   per unit) — untuk UTF-8 valid SAMA PERSIS dengan hitungan byte
//!   mentah (astral 4 byte = 2+2), jadi modul ini menghitung byte
//!   mentah; hanya UTF-8 tak-valid menyimpang (Java mengganti tiap
//!   byte rusak jadi U+FFFD = 3 byte; Rust `from_utf8_lossy` tetap
//!   dipakai untuk dekode — perilaku barisnya sama, cap-nya bisa
//!   lebih longgar; tidak terjangkau — server kirim JSON valid).
//! - `String.take(n)`/`length` = unit UTF-16; `utf16_take`
//!   menirunya — bila potongan membelah surrogate pair, high
//!   surrogate yang menggantung DIBUANG (String Rust tak bisa
//!   menampung lone surrogate; Kotlin membiarkannya — hanya
//!   terjangkau bila emoji tepat di batas potong).
//! - `String.format("%.2f")` memakai locale default JVM — di VM/CI
//!   (en_US) pemisahnya "."; di perangkat ber-locale id_ID Kotlin
//!   akan mencetak "23,98 fps". Rust SELALU ".". Divergensi dicatat
//!   (BUILD-VC319.md §5); suite JVM/CI tidak bisa membedakannya.
//! - `JSONObject.toString()` untuk baris keluar: urutan key
//!   org.json (HashMap, tak tentu) vs serde_json (BTreeMap, urut
//!   alfabet) — server mem-parse JSON, jadi urutan tak bermakna;
//!   tes memeriksa baris keluar sebagai JSON, bukan string.
//! - `maxOfOrNull`/`sortBy` atas Double memakai `compareTo`
//!   (NaN > segalanya) — `cmp_f64` menirunya; serde_json menolak
//!   NaN di baris masuk (org.json menerimanya sebagai literal).

#![forbid(unsafe_code)]

use crate::ds_safety_strip::{java_is_whitespace, kotlin_trim};
use serde_json::{json, Map, Value};
use std::collections::VecDeque;
use std::io::{BufReader, BufWriter, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::rc::Rc;
use std::time::Duration;
use std::{cell::RefCell, io::ErrorKind};

/// Host server Concat (loopback milik sendiri).
pub const HOST: &str = "127.0.0.1";
/// Port server Concat.
pub const PORT: u16 = 7420;
/// Batas connect (Kotlin CONNECT_TIMEOUT_MS).
pub const CONNECT_TIMEOUT_MS: u64 = 5_000;
/// Batas baca satu panggilan (Kotlin READ_TIMEOUT_MS).
pub const READ_TIMEOUT_MS: u64 = 15_000;
/// Batas satu baris balasan (Kotlin MAX_LINE_BYTES = 4 MiB).
pub const MAX_LINE_BYTES: usize = 4 * 1024 * 1024;
/// Cap ringkasan proyek (Kotlin SUMMARY_CAP_CHARS).
pub const SUMMARY_CAP_CHARS: usize = 2000;
/// Pesan baku saat server tidak menjawab (Kotlin DOWN_MESSAGE).
pub const DOWN_MESSAGE: &str =
    "server Concat tidak menjawab di 127.0.0.1:7420 — server mati atau token salah";
/// Hint S6 untuk open yang gagal karena lock (Kotlin LOCK_HINT).
pub const LOCK_HINT: &str = "tutup dulu proyek di layar Concat, lalu coba lagi";

// ------------------------------------------------------------------ error --

/// Tiga jenis kegagalan `ConcatApiClient.kt` (satu-satu dengan hierarki
/// exception Kotlin: `ConcatAuthException` / `ConcatDownException` /
/// `ConcatApiException`). Pesan = pesan server verbatim, tanpa token.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConcatError {
    Auth(String),
    Down(String),
    Api(String),
}

impl ConcatError {
    pub fn auth(msg: impl Into<String>) -> Self {
        ConcatError::Auth(msg.into())
    }
    pub fn down(msg: impl Into<String>) -> Self {
        ConcatError::Down(msg.into())
    }
    pub fn api(msg: impl Into<String>) -> Self {
        ConcatError::Api(msg.into())
    }
    /// Nama jenis untuk envelope JSON seam (`kind`).
    pub fn kind(&self) -> &'static str {
        match self {
            ConcatError::Auth(_) => "auth",
            ConcatError::Down(_) => "down",
            ConcatError::Api(_) => "api",
        }
    }
    pub fn message(&self) -> &str {
        match self {
            ConcatError::Auth(m) | ConcatError::Down(m) | ConcatError::Api(m) => m,
        }
    }
}

// ------------------------------------------------------- helper org.json ---

/// `JSONObject.has(key)`: TRUE bila key ada — termasuk bila nilainya
/// JSON null (terbukti probe: `has("n")` true untuk JSONObject.NULL).
pub fn has(v: &Value, key: &str) -> bool {
    v.as_object().map_or(false, |o| o.contains_key(key))
}

/// `JSONObject.optJSONObject(key)`: hanya bila benar-benar objek.
pub fn opt_obj<'a>(v: &'a Value, key: &str) -> Option<&'a Map<String, Value>> {
    v.get(key).and_then(|x| x.as_object())
}

/// `JSONObject.optJSONArray(key)`: hanya bila benar-benar array.
pub fn opt_arr<'a>(v: &'a Value, key: &str) -> Option<&'a Vec<Value>> {
    v.get(key).and_then(|x| x.as_array())
}

/// Angka JSON -> string ala `JSONObject.numberToString` / `optString`:
/// integer polos; double BULAT -> polos (org.json mem-parse desimal
/// sebagai BigDecimal — literal "12345678.0" memberi "12345678.0",
/// sedangkan f64 tak menyimpan skala literalnya, jadi ".0" tidak
/// bisa dipulihkan; hanya terjangkau bila server mengirim angka di
/// tempat string — tidak pernah di lapangan); double pecahan ->
/// `java_double_string` (untuk ilmiah: "1.0E16" ala Double.toString,
/// BUKAN "1E+16" ala BigDecimal — deviasi tercatat, sama-sama tak
/// terjangkau di lapangan).
pub fn json_number_string(n: &serde_json::Number) -> String {
    if let Some(i) = n.as_i64() {
        return i.to_string();
    }
    if let Some(u) = n.as_u64() {
        return u.to_string();
    }
    if let Some(f) = n.as_f64() {
        if f.is_finite() && f.fract() == 0.0 && f.abs() < 1e15 {
            return format!("{:.0}", f);
        }
        return java_double_string(f);
    }
    n.to_string()
}

/// `JSONObject.optString(key, default)`: hilang/NULL -> default; String
/// -> apa adanya; Number -> `numberToString`; Boolean -> "true"/"false";
/// Object/Array -> JSON kompak (urutan key bisa beda dari org.json —
/// hanya terjangkau bila server mengirim objek di tempat string).
pub fn opt_string(v: &Value, key: &str, default: &str) -> String {
    match v.get(key) {
        None | Some(Value::Null) => default.to_string(),
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => json_number_string(n),
        Some(Value::Bool(b)) => b.to_string(),
        Some(other) => other.to_string(),
    }
}

/// `JSONObject.optInt(key, default)`: Number -> `intValue` (potong ke
/// nol, bungkus 32-bit); String -> `Integer.parseInt`, gagal -> parse
/// Double lalu potong (terbukti probe: "12.5" -> 12); lain -> default.
pub fn opt_int(v: &Value, key: &str, default: i32) -> i32 {
    match v.get(key) {
        None | Some(Value::Null) => default,
        Some(Value::Number(n)) => opt_int_from_number(n, default),
        Some(Value::String(s)) => {
            let t = s.trim();
            if let Ok(i) = t.parse::<i64>() {
                return i as i32;
            }
            if let Ok(f) = t.parse::<f64>() {
                return f as i64 as i32;
            }
            default
        }
        Some(_) => default,
    }
}

fn opt_int_from_number(n: &serde_json::Number, default: i32) -> i32 {
    if let Some(i) = n.as_i64() {
        return i as i32;
    }
    if let Some(u) = n.as_u64() {
        return u as i64 as i32;
    }
    if let Some(f) = n.as_f64() {
        return f as i64 as i32;
    }
    default
}

/// `JSONObject.optLong(key, default)`: seperti `opt_int` (64-bit).
pub fn opt_long(v: &Value, key: &str, default: i64) -> i64 {
    match v.get(key) {
        None | Some(Value::Null) => default,
        Some(Value::Number(n)) => {
            if let Some(i) = n.as_i64() {
                i
            } else if let Some(u) = n.as_u64() {
                u as i64
            } else if let Some(f) = n.as_f64() {
                f as i64
            } else {
                default
            }
        }
        Some(Value::String(s)) => {
            let t = s.trim();
            if let Ok(i) = t.parse::<i64>() {
                return i;
            }
            if let Ok(f) = t.parse::<f64>() {
                return f as i64;
            }
            default
        }
        Some(_) => default,
    }
}

/// `JSONObject.optDouble(key, default)`: Number -> `doubleValue`;
/// String -> `Double.parseDouble` (trim; gagal -> default); lain ->
/// default. (Catatan: `optDouble(key)` TANPA default mengembalikan
/// NaN — Kotlin di file ini selalu memakai default eksplisit.)
pub fn opt_double(v: &Value, key: &str, default: f64) -> f64 {
    match v.get(key) {
        None | Some(Value::Null) => default,
        Some(Value::Number(n)) => n.as_f64().unwrap_or(default),
        Some(Value::String(s)) => s.trim().parse::<f64>().unwrap_or(default),
        Some(_) => default,
    }
}

/// `JSONObject.optBoolean(key, default)`: Boolean -> nilainya; String
/// -> "true" case-insensitive saja (probe: "True" -> true, 1 -> false);
/// lain -> default.
pub fn opt_bool(v: &Value, key: &str, default: bool) -> bool {
    match v.get(key) {
        None | Some(Value::Null) => default,
        Some(Value::Bool(b)) => *b,
        Some(Value::String(s)) => s.eq_ignore_ascii_case("true"),
        Some(_) => default,
    }
}

/// Angka JSON -> i64 ala `Number.longValue` (untuk pencocokan `id`):
/// double dipotong ke nol + saturasi (sama seperti Java); u64
/// dibungkus (sama seperti `longValue` BigInteger).
pub fn json_to_long(v: &Value) -> Option<i64> {
    match v {
        Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                Some(i)
            } else if let Some(u) = n.as_u64() {
                Some(u as i64)
            } else {
                n.as_f64().map(|f| f as i64)
            }
        }
        _ => None,
    }
}

// ------------------------------------------------- format angka ala Java ---

/// `Double.toString` Java: desimal terpendek yang round-trip, tapi
/// notasi ilmiah untuk |v| >= 1e7 atau 0 < |v| < 1e-3 ("1.0E16",
/// "1.0E-4", "12.0", "-0.0"); Rust `{}` tidak pernah ilmiah dan
/// menulis "12" untuk 12.0 — karena itu dikonversi di sini.
/// Dipakai `optString` atas angka + `formatDuration`.
pub fn java_double_string(f: f64) -> String {
    if f.is_nan() {
        return "NaN".to_string();
    }
    if f.is_infinite() {
        return if f > 0.0 {
            "Infinity".to_string()
        } else {
            "-Infinity".to_string()
        };
    }
    if f == 0.0 {
        return if f.is_sign_negative() {
            "-0.0".to_string()
        } else {
            "0.0".to_string()
        };
    }
    let a = f.abs();
    if a >= 1e7 || a < 1e-3 {
        // Notasi ilmiah Java: mantissa 1 digit + "." + sisa digit
        // (".0" bila tak ada sisa), eksponen bertanda ("E+16").
        let e = format!("{f:e}"); // mis. "1e16", "-1.5e-7"
        let (mant, exp) = match e.split_once(['e', 'E']) {
            Some((m, x)) => (m, x.parse::<i32>().unwrap_or(0)),
            None => (e.as_str(), 0),
        };
        let (neg, mant) = match mant.strip_prefix('-') {
            Some(m) => (true, m),
            None => (false, mant),
        };
        let mant = match mant.split_once('.') {
            Some((d1, rest)) => {
                let rest = rest.trim_end_matches('0');
                if rest.is_empty() {
                    format!("{d1}.0")
                } else {
                    format!("{d1}.{rest}")
                }
            }
            None => format!("{mant}.0"),
        };
        // Java Double.toString: eksponen TANPA '+' ("1.0E16", "1.0E-4").
        return format!("{}{mant}E{exp}", if neg { "-" } else { "" });
    }
    let s = format!("{f}");
    if s.contains('.') {
        s
    } else {
        format!("{s}.0")
    }
}

/// `String.format("%.2f", rate)` (Java Formatter): dibulatkan HALF_UP
/// atas desimal `Double.toString` (terpendek) — terbukti probe:
/// 0.125 -> "0.13", 2.675 -> "2.68". Rust `{:.2}` half-even MENYIMPANG
/// (0.125 -> "0.12"), jadi pembulatan dilakukan manual di atas string
/// desimal terpendek.
pub fn java_format_2f(rate: f64) -> String {
    if !rate.is_finite() {
        return format!("{rate:.2}");
    }
    let s = java_double_string(rate);
    // Sains tidak terjangkau untuk fps; bila muncul, fallback Rust.
    if s.contains(['e', 'E']) {
        return format!("{rate:.2}");
    }
    let (neg, s) = match s.strip_prefix('-') {
        Some(r) => (true, r),
        None => (false, s.as_str()),
    };
    let (int_part, frac_part) = match s.split_once('.') {
        Some((i, f)) => (i, f),
        None => (s, ""),
    };
    let mut frac: Vec<u8> = frac_part.bytes().collect();
    while frac.len() < 3 {
        frac.push(b'0');
    }
    let mut digits: Vec<u8> = int_part.bytes().chain(frac.iter().take(2).cloned()).collect();
    if frac[2] >= b'5' {
        // HALF_UP (jauhi nol): tambah 1 di digit desimal ke-2.
        let mut i = digits.len() - 1;
        loop {
            if digits[i] == b'9' {
                digits[i] = b'0';
                if i == 0 {
                    digits.insert(0, b'1');
                    break;
                }
                i -= 1;
            } else {
                digits[i] += 1;
                break;
            }
        }
        // Bila carry menambah digit di depan, panjang int bertambah 1
        // (dihitung ulang di bawah dari `digits.len() - 2`).
    }
    // Panjang bagian int bisa tumbuh 1 digit karena carry (99.999 -> 100.00).
    let int_digits = digits.len() - 2;
    let int_s = String::from_utf8_lossy(&digits[..int_digits]).into_owned();
    let frac_s = String::from_utf8_lossy(&digits[int_digits..]).into_owned();
    format!("{}{int_s}.{frac_s}", if neg { "-" } else { "" })
}

/// `String.isBlank()` Kotlin (kosong atau semua `isWhitespace`).
pub fn is_blank(s: &str) -> bool {
    s.chars().all(java_is_whitespace)
}

/// Panjang string dalam unit UTF-16 (`String.length` Kotlin).
pub fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

/// `String.take(n)` Kotlin: n unit UTF-16 pertama. Bila potongan
/// membelah surrogate pair, high surrogate yang menggantung DIBUANG
/// (String Rust tak bisa menampung lone surrogate — lihat header).
pub fn utf16_take(s: &str, n: usize) -> String {
    let mut units: Vec<u16> = s.encode_utf16().take(n).collect();
    if let Some(&last) = units.last() {
        if (0xD800..0xDC00).contains(&last) {
            units.pop();
        }
    }
    String::from_utf16_lossy(&units)
}

// ------------------------------------------------------------- Base64 ---

/// `Base64.getDecoder().decode()` Java (basic decoder) — dibuktikan
/// probe JVM: padding boleh hilang, whitespace/karakter ilegal
/// ditolak, panjang % 4 == 1 ditolak, trailing-bit TIDAK dicek.
pub fn java_base64_decode(input: &str) -> Result<Vec<u8>, ()> {
    fn val(c: u8) -> Option<u32> {
        match c {
            b'A'..=b'Z' => Some((c - b'A') as u32),
            b'a'..=b'z' => Some((c - b'a' + 26) as u32),
            b'0'..=b'9' => Some((c - b'0' + 52) as u32),
            b'+' => Some(62),
            b'/' => Some(63),
            _ => None,
        }
    }
    let bytes = input.as_bytes();
    if bytes.is_empty() {
        return Ok(Vec::new());
    }
    // '=' hanya di akhir (maks 2); bila ada, total harus kelipatan 4
    // dengan kuantum akhir yang lengkap (2 data + "==", 3 data + "=").
    let pad = bytes.iter().rev().take_while(|&&b| b == b'=').count();
    if pad > 2 {
        return Err(());
    }
    if pad > 0 {
        if bytes.len() % 4 != 0 {
            return Err(());
        }
        let data_len = bytes.len() - pad;
        if data_len % 4 == 2 && pad != 2 {
            return Err(());
        }
        if data_len % 4 == 3 && pad != 1 {
            return Err(());
        }
        if data_len % 4 == 0 || data_len % 4 == 1 {
            return Err(());
        }
    } else if bytes.len() % 4 == 1 {
        return Err(());
    }
    // Validasi alfabet (termasuk '=' di tengah -> tolak).
    for (i, &b) in bytes.iter().enumerate() {
        if b == b'=' {
            if i < bytes.len() - pad {
                return Err(());
            }
        } else if val(b).is_none() {
            return Err(());
        }
    }
    let data = &bytes[..bytes.len() - pad];
    let mut out = Vec::with_capacity(data.len() * 3 / 4 + 3);
    let mut chunks = data.chunks_exact(4);
    for q in &mut chunks {
        let v0 = val(q[0]).ok_or(())?;
        let v1 = val(q[1]).ok_or(())?;
        let v2 = val(q[2]).ok_or(())?;
        let v3 = val(q[3]).ok_or(())?;
        out.push(((v0 << 2) | (v1 >> 4)) as u8);
        out.push((((v1 & 0xF) << 4) | (v2 >> 2)) as u8);
        out.push((((v2 & 0x3) << 6) | v3) as u8);
    }
    let rem = chunks.remainder();
    if rem.len() == 2 {
        let v0 = val(rem[0]).ok_or(())?;
        let v1 = val(rem[1]).ok_or(())?;
        out.push(((v0 << 2) | (v1 >> 4)) as u8);
    } else if rem.len() == 3 {
        let v0 = val(rem[0]).ok_or(())?;
        let v1 = val(rem[1]).ok_or(())?;
        let v2 = val(rem[2]).ok_or(())?;
        out.push(((v0 << 2) | (v1 >> 4)) as u8);
        out.push((((v1 & 0xF) << 4) | (v2 >> 2)) as u8);
    }
    Ok(out)
}

// ------------------------------------------------------------- transport --

/// Kegagalan tingkat baris (dipetakan ke `ConcatError` oleh `call` /
/// `read_matching` dengan konteks fase — sama seperti `catch` di
/// `call()` Kotlin).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LineError {
    /// Baca kehabisan waktu (soTimeout) — Kotlin: `SocketTimeoutException`.
    Timeout,
    /// I/O lain (putus, reset) — Kotlin: `IOException`.
    Io,
    /// Baris melewati `MAX_LINE_BYTES` — Kotlin: `ConcatApiException`
    /// "terlalu besar" (dilempar langsung dari `readLineCapped`).
    TooBig,
}

/// Satu koneksi: alur baca + tulis + tutup — padanan `Connection`
/// Kotlin (reader/writer + onClose). `read_byte` dipakai `read_line_capped`
/// supaya hitungan cap per byte sama dengan hitungan per char Kotlin
/// (untuk UTF-8 valid keduanya identik — lihat header).
pub trait Conn {
    /// Baca satu byte; `None` = EOF. `Timeout` = server diam.
    fn read_byte(&mut self) -> Result<Option<u8>, LineError>;
    /// Tulis satu baris + "\n" + flush.
    fn write_line(&mut self, line: &str) -> Result<(), LineError>;
    /// Tutup (error ditelan — seperti `Connection.close()` Kotlin).
    fn close(&mut self);
}

/// Pembuka koneksi — padanan `connectionOpener` Kotlin (seam tes).
/// Produksi: `TcpConnector` (TCP langsung 127.0.0.1:7420).
/// Tes Rust: `MockConnector` (dalam-memori, tanpa TCP).
pub trait Connector {
    fn connect(&mut self) -> Result<Box<dyn Conn>, ConcatError>;
}

/// Koneksi TCP langsung ke 127.0.0.1:7420, tanpa proxy (loopback
/// milik sendiri) — padanan `openTcpConnection()` Kotlin.
/// `set_read_timeout` = soTimeout; TIDAK ada write timeout (Kotlin
/// juga hanya men-set soTimeout — tulis memblokir tanpa batas).
pub struct TcpConn {
    reader: BufReader<TcpStream>,
    writer: BufWriter<TcpStream>,
}

impl TcpConn {
    fn new(stream: TcpStream) -> std::io::Result<Self> {
        let reader = BufReader::new(stream.try_clone()?);
        let writer = BufWriter::new(stream);
        Ok(Self { reader, writer })
    }
}

impl Conn for TcpConn {
    fn read_byte(&mut self) -> Result<Option<u8>, LineError> {
        let mut buf = [0u8; 1];
        match self.reader.read(&mut buf) {
            Ok(0) => Ok(None),
            Ok(_) => Ok(Some(buf[0])),
            Err(e) if e.kind() == ErrorKind::TimedOut || e.kind() == ErrorKind::WouldBlock => {
                Err(LineError::Timeout)
            }
            Err(_) => Err(LineError::Io),
        }
    }

    fn write_line(&mut self, line: &str) -> Result<(), LineError> {
        match self.writer.write_all(line.as_bytes()).and_then(|_| {
            self.writer.write_all(b"\n").and_then(|_| self.writer.flush())
        }) {
            Ok(()) => Ok(()),
            Err(_) => Err(LineError::Io),
        }
    }

    fn close(&mut self) {
        let _ = self.writer.flush();
    }
}

/// Pembuka TCP produksi (satu koneksi per panggilan).
pub struct TcpConnector;

impl Connector for TcpConnector {
    fn connect(&mut self) -> Result<Box<dyn Conn>, ConcatError> {
        let addr = SocketAddr::from(([127, 0, 0, 1], PORT));
        match TcpStream::connect_timeout(&addr, Duration::from_millis(CONNECT_TIMEOUT_MS)) {
            Err(_) => Err(ConcatError::down(DOWN_MESSAGE)),
            Ok(s) => {
                let _ = s.set_read_timeout(Some(Duration::from_millis(READ_TIMEOUT_MS)));
                match TcpConn::new(s) {
                    Ok(c) => Ok(Box::new(c)),
                    Err(_) => Err(ConcatError::down(DOWN_MESSAGE)),
                }
            }
        }
    }
}

/// Koneksi dalam-memori untuk tes (seam tes, padanan `FakeConcat`
/// Kotlin di `TestConcatApiVc314.kt`): byte yang akan dibalas server
/// di-`feed`, EOF di-`feed_eof`; antrean kosong + belum EOF = server
/// diam -> `Timeout` (seketika, tanpa menunggu 15 dtk). Baris yang
/// ditulis klien tercatat di `written` (dibagi via `Rc` supaya tes
/// tetap bisa membacanya sesudah koneksi dipakai `call()`).
pub struct MockConn {
    inbound: VecDeque<u8>,
    eof: bool,
    pub written: Rc<RefCell<Vec<String>>>,
}

impl MockConn {
    pub fn new() -> Self {
        Self {
            inbound: VecDeque::new(),
            eof: false,
            written: Rc::new(RefCell::new(Vec::new())),
        }
    }

    /// Umpan byte balasan server (boleh dipanggil berkali-kali).
    pub fn feed(&mut self, text: &str) {
        self.inbound.extend(text.as_bytes());
    }

    /// Tandai EOF sesudah byte yang sudah diumpan habis.
    pub fn feed_eof(&mut self) {
        self.eof = true;
    }

    /// Satu balasan JSON-RPC lengkap (objek -> satu baris).
    pub fn feed_json(&mut self, v: &Value) {
        let mut s = v.to_string();
        s.push('\n');
        self.feed(&s);
    }

    /// Balasan `{"jsonrpc":"2.0","id":<id>,"result":<result>}`.
    pub fn feed_result(&mut self, id: i64, result: Value) {
        self.feed_json(&json!({"jsonrpc": "2.0", "id": id, "result": result}));
    }

    /// Balasan `{"jsonrpc":"2.0","id":<id>,"error":{...}}`.
    pub fn feed_error(&mut self, id: i64, code: i64, data_code: &str, message: &str) {
        self.feed_json(
            &json!({"jsonrpc": "2.0", "id": id,
                "error": {"code": code, "message": message,
                    "data": {"code": data_code}}}),
        );
    }
}

impl Conn for MockConn {
    fn read_byte(&mut self) -> Result<Option<u8>, LineError> {
        if let Some(b) = self.inbound.pop_front() {
            return Ok(Some(b));
        }
        if self.eof {
            return Ok(None);
        }
        Err(LineError::Timeout)
    }

    fn write_line(&mut self, line: &str) -> Result<(), LineError> {
        self.written.borrow_mut().push(line.to_string());
        Ok(())
    }

    fn close(&mut self) {}
}

/// Pembuka mock: tiap `connect()` mengeluarkan koneksi berikutnya
/// dari antrean (habis -> `Down`, seperti koneksi ditolak).
pub struct MockConnector {
    conns: VecDeque<MockConn>,
}

impl MockConnector {
    pub fn new(conns: Vec<MockConn>) -> Self {
        Self {
            conns: conns.into(),
        }
    }
}

impl Connector for MockConnector {
    fn connect(&mut self) -> Result<Box<dyn Conn>, ConcatError> {
        match self.conns.pop_front() {
            Some(c) => Ok(Box::new(c)),
            None => Err(ConcatError::down(DOWN_MESSAGE)),
        }
    }
}

// ------------------------------------------------------- framing / call ---

/// Satu baris dengan cap byte — padanan `readLineCapped` Kotlin.
/// Hitungan byte MENTAH (identik dengan hitungan per char Kotlin
/// untuk UTF-8 valid — lihat header). `'\n'` mengakhiri baris
/// (tidak dihitung); `'\r'` akhir dibuang semua; EOF di tengah
/// baris mengembalikan baris parsial; EOF di awal -> `None`.
fn read_line_capped(conn: &mut dyn Conn) -> Result<Option<String>, LineError> {
    let mut bytes: Vec<u8> = Vec::new();
    let mut count: usize = 0;
    loop {
        match conn.read_byte()? {
            None => {
                if bytes.is_empty() {
                    return Ok(None);
                }
                break;
            }
            Some(b'\n') => break,
            Some(b) => {
                count += 1;
                if count > MAX_LINE_BYTES {
                    return Err(LineError::TooBig);
                }
                bytes.push(b);
            }
        }
    }
    while bytes.last() == Some(&b'\r') {
        bytes.pop();
    }
    Ok(Some(String::from_utf8_lossy(&bytes).into_owned()))
}

/// Nama metode teknis -> kata kerja sehari-hari (Kotlin `plainAction`;
/// metode tak dikenal dipakai apa adanya).
pub fn plain_action(method: &str) -> &str {
    match method {
        "auth" => "masuk",
        "version" => "mengecek versi",
        "project.list" => "melihat daftar proyek",
        "project.open" => "membuka proyek",
        "project.get" => "membaca proyek",
        "project.create" => "membuat proyek",
        "project.close" => "menutup proyek",
        "project.save" => "menyimpan proyek",
        "project.setVideo" => "mengatur frame proyek",
        "edit.apply" => "menerapkan edit",
        "edit.undo" => "mengurungkan edit",
        "edit.redo" => "mengulangi edit",
        "media.probe" => "memeriksa file media",
        "media.import" => "mengimpor media",
        "catalogue.list" => "melihat katalog",
        "template.list" => "melihat daftar template",
        "template.instantiate" => "membuat proyek dari template",
        "template.save" => "menyimpan template",
        "export.run" => "menjalankan ekspor",
        "export.cancel" => "membatalkan ekspor",
        "export.status" => "mengecek status ekspor",
        "preview.frame" => "mengambil frame pratinjau",
        _ => method,
    }
}

fn format_timeout(ms: u64) -> String {
    if ms % 1000 == 0 {
        format!("{} detik", ms / 1000)
    } else {
        format!("{ms} ms")
    }
}

/// Pesan timeout baca (bahasa bayi) — padanan `timeoutMessage` Kotlin
/// (termasuk pesan lock khusus `project.open`; kata "token" sengaja
/// tidak ada — masalah token HANYA kegagalan auth -32006).
pub fn timeout_message(phase: &str) -> String {
    let base = format!(
        "Concat tidak membalas dalam {} saat {}",
        format_timeout(READ_TIMEOUT_MS),
        plain_action(phase)
    );
    let restart = "Kalau berulang, restart server Concat (matikan lalu nyalakan tombol Automation di Concat).";
    if phase == "project.open" {
        format!(
            "{base} — proyek ini mungkin lagi kebuka di layar Concat (lock), atau server lagi sibuk/nyangkut. Tutup dulu proyek di layar Concat, lalu coba lagi. {restart}"
        )
    } else {
        format!("{base} — mungkin lagi sibuk, coba lagi. {restart}")
    }
}

/// Baca baris sampai ketemu balasan dengan `"id"` yang cocok — padanan
/// `readMatching` Kotlin. Notifikasi (tanpa `"id"`), `id` null tanpa
/// error, `id` bukan angka, dan `id` lain dilewati; `id: null` +
/// `"error"` dihitung jawaban (sesuai `client.rs` referensi).
fn read_matching(conn: &mut dyn Conn, id: i64, what: &str) -> Result<Value, ConcatError> {
    loop {
        let line = match read_line_capped(conn) {
            Ok(None) => {
                return Err(if what == "auth" {
                    ConcatError::down(
                        "Concat putus sebelum sempat masuk — servernya mungkin mati atau baru saja restart, coba lagi.",
                    )
                } else {
                    ConcatError::api(format!(
                        "Concat putus di tengah jalan saat {} — coba lagi.",
                        plain_action(what)
                    ))
                });
            }
            Ok(Some(l)) => l,
            Err(LineError::Timeout) => return Err(ConcatError::api(timeout_message(what))),
            Err(LineError::Io) => {
                return Err(ConcatError::api(format!(
                    "Concat putus saat {} — coba lagi.",
                    plain_action(what)
                )))
            }
            Err(LineError::TooBig) => {
                return Err(ConcatError::api(
                    "Jawaban Concat terlalu besar untuk dibaca — proyeknya mungkin terlalu besar.",
                ))
            }
        };
        if is_blank(&line) {
            continue;
        }
        let obj = match serde_json::from_str::<Value>(&line) {
            Ok(Value::Object(o)) => o,
            _ => {
                return Err(ConcatError::api(
                    "Jawaban Concat tidak bisa dibaca — coba lagi.",
                ))
            }
        };
        if !obj.contains_key("id") {
            continue;
        }
        match obj.get("id") {
            Some(Value::Null) => {
                if obj.contains_key("error") {
                    return Ok(Value::Object(obj));
                }
                continue;
            }
            Some(v) => match json_to_long(v) {
                Some(n) if n == id => return Ok(Value::Object(obj)),
                _ => continue,
            },
            None => continue,
        }
    }
}

fn auth_line(token: &str) -> String {
    json!({"jsonrpc": "2.0", "id": 1, "method": "auth", "params": {"token": token}}).to_string()
}

fn call_line(method: &str, params: Option<&Value>) -> String {
    let mut o = Map::new();
    o.insert("jsonrpc".to_string(), json!("2.0"));
    o.insert("id".to_string(), json!(2));
    o.insert("method".to_string(), json!(method));
    if let Some(p) = params {
        o.insert("params".to_string(), p.clone());
    }
    Value::Object(o).to_string()
}

fn map_auth_error(error: &Map<String, Value>) -> ConcatError {
    let ev = Value::Object(error.clone());
    let message = opt_string(
        &ev,
        "message",
        "Concat menolak masuk — tokennya mungkin salah.",
    );
    let code = opt_int(&ev, "code", 0);
    let data_code = opt_obj(&ev, "data")
        .map(|d| opt_string(&Value::Object(d.clone()), "code", ""))
        .unwrap_or_default();
    if code == -32006 || data_code == "unauthorized" {
        ConcatError::auth(message)
    } else {
        ConcatError::api(message)
    }
}

fn map_call_error(method: &str, error: &Map<String, Value>) -> ConcatError {
    let ev = Value::Object(error.clone());
    let message = opt_string(
        &ev,
        "message",
        &format!("Concat menolak {}.", plain_action(method)),
    );
    if method != "project.open" {
        return ConcatError::api(message);
    }
    let code = opt_int(&ev, "code", 0);
    let data_code = opt_obj(&ev, "data")
        .map(|d| opt_string(&Value::Object(d.clone()), "code", ""))
        .unwrap_or_default();
    let is_lock = code == -32003
        || data_code == "refused"
        || message.to_lowercase().contains("open in");
    if is_lock {
        ConcatError::api(format!("{message} — {LOCK_HINT}"))
    } else {
        ConcatError::api(message)
    }
}

fn write_line_or(conn: &mut dyn Conn, line: &str, phase: &str) -> Result<(), ConcatError> {
    conn.write_line(line).map_err(|e| match e {
        LineError::Timeout => ConcatError::api(timeout_message(phase)),
        _ => ConcatError::api(format!(
            "Concat putus saat {} — coba lagi.",
            plain_action(phase)
        )),
    })
}

/// connect -> auth (id 1) -> call (id 2) -> close — padanan `call()`
/// Kotlin. Mengembalikan nilai `"result"` balasan.
pub fn call(
    connector: &mut dyn Connector,
    token: &str,
    method: &str,
    params: Option<&Value>,
) -> Result<Value, ConcatError> {
    let mut conn = connector.connect()?;
    let result = call_on(&mut *conn, token, method, params);
    conn.close();
    result
}

fn call_on(
    conn: &mut dyn Conn,
    token: &str,
    method: &str,
    params: Option<&Value>,
) -> Result<Value, ConcatError> {
    // 1. Auth — baris pertama, id 1.
    write_line_or(conn, &auth_line(token), "auth")?;
    let auth_reply = read_matching(conn, 1, "auth")?;
    if let Some(err) = opt_obj(&auth_reply, "error") {
        return Err(map_auth_error(err));
    }
    if !has(&auth_reply, "result") {
        return Err(ConcatError::api(
            "Concat tidak memberi jawaban saat masuk — coba lagi.",
        ));
    }
    // 2. Panggilan — id 2.
    write_line_or(conn, &call_line(method, params), method)?;
    let reply = read_matching(conn, 2, method)?;
    if let Some(err) = opt_obj(&reply, "error") {
        return Err(map_call_error(method, err));
    }
    if !has(&reply, "result") {
        return Err(ConcatError::api(format!(
            "Concat tidak memberi jawaban untuk {} — coba lagi.",
            plain_action(method)
        )));
    }
    Ok(reply.get("result").cloned().unwrap_or(Value::Null))
}

// -------------------------------------------------- format ringkasan ---

/// `"30 fps"` / `"23.98 fps"` — padanan `formatRate` Kotlin
/// (whole -> "{n} fps"; pecahan -> `java_format_2f` HALF_UP).
pub fn format_rate(rate_num: i64, rate_den: i64) -> String {
    if rate_num <= 0 || rate_den <= 0 {
        return String::new();
    }
    let rate = rate_num as f64 / rate_den as f64;
    let whole = rate as i64;
    if rate == whole as f64 {
        format!("{whole} fps")
    } else {
        format!("{} fps", java_format_2f(rate))
    }
}

/// Detik -> "12.5 dtk" (< 60 dtk) atau "4:28" (>= 60 dtk) — padanan
/// `formatDuration` Kotlin (`Math.round(safe*10)/10.0` lalu
/// `Double.toString`; bulat -> `{n} dtk`).
pub fn format_duration(seconds: f64) -> String {
    let safe = if seconds.is_finite() && seconds > 0.0 {
        seconds
    } else {
        0.0
    };
    if safe < 60.0 {
        let rounded = ((safe * 10.0 + 0.5).floor()) / 10.0;
        if rounded == rounded.floor() {
            format!("{} dtk", rounded as i64)
        } else {
            format!("{} dtk", java_double_string(rounded))
        }
    } else {
        let total = safe as i64;
        format!("{}:{:02}", total / 60, total % 60)
    }
}

/// Potong ringkasan ke cap ~2000 karakter — padanan `capSummary`
/// Kotlin (`take(1999) + "…"`, dihitung unit UTF-16).
pub fn cap_summary(text: &str) -> String {
    if utf16_len(text) > SUMMARY_CAP_CHARS {
        format!("{}…", utf16_take(text, SUMMARY_CAP_CHARS - 1))
    } else {
        text.to_string()
    }
}

// --------------------------------------------------------- ringkasan ---

/// `Double.compareTo` Kotlin (untuk `sortBy`/`maxOfOrNull`): NaN >
/// segalanya (termasuk NaN lain tidak — NaN vs NaN = sama).
fn cmp_f64(a: f64, b: f64) -> std::cmp::Ordering {
    match a.partial_cmp(&b) {
        Some(o) => o,
        None => {
            if a.is_nan() && b.is_nan() {
                std::cmp::Ordering::Equal
            } else if a.is_nan() {
                std::cmp::Ordering::Greater
            } else {
                std::cmp::Ordering::Less
            }
        }
    }
}

struct ClipLine {
    name: String,
    start: f64,
    duration: f64,
}

/// Ringkas satu `EditorView` — padanan `summarizeView` Kotlin
/// (timeline AKTIF, jatuh ke yang pertama; klip diurutkan pakai
/// `start`; cap ~2000 karakter dengan cadangan 48 untuk baris
/// "+N klip lagi"; catatan API ditempel SETELAH cap bila `opened`).
pub fn summarize_view(view: &Value, path: &str, opened: bool) -> String {
    let settings = view.get("settings");
    let raw_name = settings
        .map(|s| opt_string(s, "name", ""))
        .unwrap_or_default();
    let name = if raw_name.is_empty() {
        "Tanpa nama".to_string()
    } else {
        raw_name
    };
    let width = settings.map(|s| opt_int(s, "width", 0)).unwrap_or(0);
    let height = settings.map(|s| opt_int(s, "height", 0)).unwrap_or(0);
    let rate = format_rate(
        settings.map(|s| opt_long(s, "rateNum", 0)).unwrap_or(0),
        settings.map(|s| opt_long(s, "rateDen", 0)).unwrap_or(0),
    );

    let project = view.get("project");
    let timelines = project.and_then(|p| opt_arr(p, "timelines"));
    let active_id = project
        .map(|p| opt_string(p, "activeTimelineId", ""))
        .unwrap_or_default();
    let mut timeline: Option<&Value> = None;
    if let Some(tl) = timelines {
        for candidate in tl {
            if opt_string(candidate, "id", "") == active_id {
                timeline = Some(candidate);
                break;
            }
        }
        if timeline.is_none() && !tl.is_empty() {
            timeline = Some(&tl[0]);
        }
    }
    let raw_timeline_name = timeline
        .map(|t| opt_string(t, "name", ""))
        .unwrap_or_default();
    let timeline_name = if raw_timeline_name.is_empty() {
        "Timeline".to_string()
    } else {
        raw_timeline_name
    };
    let track_count = timeline
        .and_then(|t| opt_arr(t, "tracks"))
        .map(|a| a.len())
        .unwrap_or(0);
    let clips_json = timeline.and_then(|t| opt_arr(t, "clips"));

    let mut clips: Vec<ClipLine> = Vec::new();
    if let Some(arr) = clips_json {
        for clip in arr {
            if clip.as_object().is_none() {
                continue;
            }
            let raw_clip_name = opt_string(clip, "name", "");
            let clip_name = (if raw_clip_name.is_empty() {
                opt_string(clip, "id", "klip")
            } else {
                raw_clip_name
            })
            .replace('\n', " ")
            .replace('\r', " ");
            let clip_name = utf16_take(&clip_name, 80);
            clips.push(ClipLine {
                name: clip_name,
                start: opt_double(clip, "start", 0.0),
                duration: opt_double(clip, "duration", 0.0),
            });
        }
    }
    clips.sort_by(|a, b| cmp_f64(a.start, b.start));
    let mut total_seconds: Option<f64> = None;
    for c in &clips {
        let v = c.start + c.duration;
        total_seconds = Some(match total_seconds {
            None => v,
            Some(m) => {
                if cmp_f64(v, m) == std::cmp::Ordering::Greater {
                    v
                } else {
                    m
                }
            }
        });
    }
    let total_seconds = total_seconds.unwrap_or(0.0);

    let mut out = String::new();
    out.push_str(if opened { "Dibuka: " } else { "Proyek: " });
    out.push_str(&name);
    out.push('\n');
    out.push_str("Path: ");
    out.push_str(path);
    out.push('\n');
    if width > 0 && height > 0 {
        out.push_str(&format!("Frame: {width}x{height}"));
        if !rate.is_empty() {
            out.push_str(&format!(" @ {rate}"));
        }
        out.push('\n');
    }
    out.push_str(&format!(
        "Timeline: {timeline_name} — {track_count} track, {} klip, total {}\n",
        clips.len(),
        format_duration(total_seconds)
    ));
    if clips.is_empty() {
        out.push_str("Klip: (belum ada klip)");
    } else {
        out.push_str("Klip:");
        let mut shown = 0usize;
        for clip in &clips {
            let line = format!("\n- {} ({})", clip.name, format_duration(clip.duration));
            let reserve = if shown < clips.len() - 1 { 48 } else { 0 };
            if utf16_len(&out) + utf16_len(&line) + reserve > SUMMARY_CAP_CHARS {
                break;
            }
            out.push_str(&line);
            shown += 1;
        }
        let remaining = clips.len() - shown;
        if remaining > 0 {
            out.push_str(&format!(
                "\n… +{remaining} klip lagi (ringkasan dipotong)"
            ));
        }
    }
    let capped = cap_summary(&out);
    if opened {
        format!(
            "{capped}\nCatatan: proyek ini sekarang dipegang API — layar Concat tidak bisa membukanya sampai ditutup lewat perintah (concat_project_close)."
        )
    } else {
        capped
    }
}

/// Ringkas satu `MediaSummary` — padanan `summarizeMedia` Kotlin.
pub fn summarize_media(media: &Value) -> String {
    let path = opt_string(media, "path", "");
    let kind = opt_string(media, "kind", "");
    let mut out = String::new();
    out.push_str("Media: ");
    out.push_str(if path.is_empty() { "(tanpa path)" } else { &path });
    out.push('\n');
    out.push_str("Jenis: ");
    out.push_str(if kind.is_empty() {
        "tidak diketahui"
    } else {
        &kind
    });
    if has(media, "duration") && !media.get("duration").map_or(true, |v| v.is_null()) {
        out.push_str(&format!(
            "\nDurasi: {}",
            format_duration(opt_double(media, "duration", 0.0))
        ));
    }
    if let Some(video) = opt_obj(media, "video") {
        let vv = Value::Object(video.clone());
        let w = opt_int(&vv, "width", 0);
        let h = opt_int(&vv, "height", 0);
        if w > 0 && h > 0 {
            out.push_str(&format!("\nVideo: {w}x{h}"));
        }
    }
    let has_audio = opt_obj(media, "audio").is_some()
        || opt_arr(media, "audioTracks").map_or(0, |a| a.len()) > 0;
    out.push_str(&format!("\nAudio: {}", if has_audio { "ya" } else { "tidak" }));
    cap_summary(&out)
}

/// Ringkas `PackageInfo[]` — padanan `summarizePackages` Kotlin
/// (cap 30 + "+N lagi"; `remaining = len - i` dihitung persis
/// seperti Kotlin — termasuk item yang belum sempat ditempel).
pub fn summarize_packages(packages: &[Value]) -> String {
    let mut out = format!("Katalog: {} paket", packages.len());
    let mut shown = 0usize;
    let mut i = 0usize;
    while i < packages.len() {
        if shown >= 30 {
            break;
        }
        let pkg = match packages[i].as_object() {
            Some(p) => p,
            None => {
                i += 1;
                continue;
            }
        };
        let pv = Value::Object(pkg.clone());
        let line = format!(
            "\n- {} — {} ({}, {})",
            opt_string(&pv, "id", "?"),
            opt_string(&pv, "name", "?"),
            opt_string(&pv, "kind", "?"),
            opt_string(&pv, "category", "?")
        );
        if utf16_len(&out) + utf16_len(&line) > SUMMARY_CAP_CHARS {
            break;
        }
        out.push_str(&line);
        shown += 1;
        i += 1;
    }
    let remaining = packages.len() - i;
    if remaining > 0 {
        out.push_str(&format!("\n… +{remaining} lagi (ringkasan dipotong)"));
    }
    cap_summary(&out)
}

fn summarize_template_line(info: &Value) -> String {
    let name = opt_string(info, "name", "");
    let name = if name.is_empty() { "Tanpa nama" } else { &name };
    let w = opt_int(info, "width", 0);
    let h = opt_int(info, "height", 0);
    let rate = format_rate(opt_long(info, "rateNum", 0), opt_long(info, "rateDen", 0));
    let slots = opt_arr(info, "slots").map_or(0, |a| a.len());
    let size = if w > 0 && h > 0 {
        format!("{w}x{h}{}", if rate.is_empty() { String::new() } else { format!(" @ {rate}") })
    } else {
        "ukuran tidak diketahui".to_string()
    };
    format!("{name} — {size}, {slots} slot")
}

/// Ringkas `TemplateInfo[]` — padanan `summarizeTemplates` Kotlin.
pub fn summarize_templates(templates: &[Value]) -> String {
    let mut out = format!("Template: {} template", templates.len());
    let mut shown = 0usize;
    let mut i = 0usize;
    while i < templates.len() {
        if shown >= 30 {
            break;
        }
        if templates[i].as_object().is_none() {
            i += 1;
            continue;
        }
        let line = format!("\n- {}", summarize_template_line(&templates[i]));
        if utf16_len(&out) + utf16_len(&line) > SUMMARY_CAP_CHARS {
            break;
        }
        out.push_str(&line);
        shown += 1;
        i += 1;
    }
    let remaining = templates.len() - i;
    if remaining > 0 {
        out.push_str(&format!("\n… +{remaining} lagi (ringkasan dipotong)"));
    }
    cap_summary(&out)
}

/// Ringkas satu `TemplateInfo` — padanan `summarizeTemplate` Kotlin.
pub fn summarize_template(info: &Value) -> String {
    let name = opt_string(info, "name", "");
    let name = if name.is_empty() { "Tanpa nama" } else { &name };
    let w = opt_int(info, "width", 0);
    let h = opt_int(info, "height", 0);
    let rate = format_rate(opt_long(info, "rateNum", 0), opt_long(info, "rateDen", 0));
    let slots = opt_arr(info, "slots").map_or(0, |a| a.len());
    let mut out = format!("Template: {name}\n");
    if w > 0 && h > 0 {
        out.push_str(&format!("Ukuran: {w}x{h}"));
        if !rate.is_empty() {
            out.push_str(&format!(" @ {rate}"));
        }
        out.push('\n');
    }
    out.push_str(&format!("Slot: {slots}"));
    cap_summary(&out)
}

/// Ringkas `Started` — padanan `summarizeStarted` Kotlin.
pub fn summarize_started(started: &Value) -> String {
    let job = opt_string(started, "job", "");
    let output = opt_string(started, "output", "");
    let mut out = format!(
        "Ekspor jalan, job {} — cek status pakai concat_export_status.",
        if job.is_empty() { "?" } else { &job }
    );
    if !output.is_empty() {
        out.push_str(&format!("\nOutput: {output}"));
    }
    cap_summary(&out)
}

/// Ringkas `JobStatus` — padanan `summarizeJobStatus` Kotlin.
pub fn summarize_job_status(status: &Value) -> String {
    let job = opt_string(status, "job", "");
    // Kotlin: `else -> optString("state", "tidak diketahui")` — state
    // HILANG/null -> "tidak diketahui", state kosong eksplisit -> "".
    let state = if !has(status, "state")
        || status.get("state").map_or(false, |v| v.is_null())
    {
        "tidak diketahui".to_string()
    } else {
        match opt_string(status, "state", "").as_str() {
            "running" => "lagi jalan".to_string(),
            "done" => "selesai".to_string(),
            "cancelled" => "dibatalkan".to_string(),
            "failed" => "gagal".to_string(),
            other => other.to_string(),
        }
    };
    let mut out = format!(
        "Ekspor job {}: {}",
        if job.is_empty() { "?" } else { &job },
        state
    );
    let stage = opt_string(status, "stage", "");
    if !stage.is_empty() {
        out.push_str(&format!("\nTahap: {stage}"));
    }
    let frame = opt_long(status, "frame", 0);
    let total = opt_long(status, "total", 0);
    if total > 0 {
        let pct = ((opt_double(status, "fraction", 0.0) * 100.0 + 0.5).floor()) as i64;
        out.push_str(&format!("\nFrame: {frame}/{total} ({pct}%)"));
    } else if frame > 0 {
        out.push_str(&format!("\nFrame: {frame}"));
    }
    let output = opt_string(status, "output", "");
    if !output.is_empty() {
        out.push_str(&format!("\nOutput: {output}"));
    }
    let err_msg = status
        .get("error")
        .and_then(|e| e.as_object())
        .map(|e| opt_string(&Value::Object(e.clone()), "message", ""))
        .unwrap_or_default();
    if !err_msg.is_empty() {
        out.push_str(&format!("\nError: {err_msg}"));
    }
    cap_summary(&out)
}

// ------------------------------------------------- operasi (21 method) ---

/// Allowlist 58 op `edit.apply` + field wajib tiap op — salinan
/// verbatim `EDIT_OP_REQUIRED` Kotlin (sumber: OPS-TABLE.md /
/// COMMANDS-EXTRACT.txt, fork v0.2.10). `matchClip` sengaja TIDAK
/// ada (`#[serde(skip)]` — tidak menyeberang API).
pub static EDIT_OP_REQUIRED: &[(&str, &[&str])] = &[
    ("addMedia", &["item"]),
    ("removeMedia", &["mediaId"]),
    ("setMediaPlaceholder", &["mediaId", "placeholder"]),
    ("fillSlot", &["mediaId", "item"]),
    ("batch", &["commands"]),
    ("addClip", &["mediaId", "trackId", "start"]),
    ("addClipAtFirstFree", &["mediaId", "start"]),
    ("addClipsInSequence", &["mediaIds", "start"]),
    ("addTextClip", &["start"]),
    ("addShapeClip", &["start", "name"]),
    ("addLayerClip", &["start", "effectId", "name"]),
    ("addTransitionClip", &["start", "transitionId", "name"]),
    ("setClipSpeedCurve", &["clipId"]),
    ("setClipKey", &["clipId", "property", "at", "value"]),
    ("clearClipKey", &["clipId", "property", "at"]),
    ("clearClipKeys", &["clipId", "property"]),
    ("setEffectKey", &["clipId", "entry", "key", "at", "value"]),
    ("clearEffectKey", &["clipId", "entry", "key", "at"]),
    ("clearEffectKeys", &["clipId", "entry", "key"]),
    ("setMaskKey", &["clipId", "maskId", "number", "at", "value"]),
    ("clearMaskKey", &["clipId", "maskId", "number", "at"]),
    ("clearMaskKeys", &["clipId", "maskId", "number"]),
    ("setClipCutout", &["clipId"]),
    ("setClipStabilize", &["clipId"]),
    ("setClipVoice", &["clipId"]),
    ("addCutoutStroke", &["clipId", "stroke"]),
    ("moveClips", &["moves"]),
    ("trimClip", &["clipId", "edge", "delta"]),
    ("splitClips", &["clipIds", "time"]),
    ("replaceClipMedia", &["clipId", "item"]),
    ("setClipEnhanced", &["clipId", "on"]),
    ("freezeFrame", &["clipId", "time"]),
    ("mergeClips", &["clipIds"]),
    ("consolidateClips", &["clipIds", "item"]),
    ("removeClips", &["clipIds"]),
    ("updateClip", &["clipId", "patch"]),
    ("styleTextRange", &["clipId", "start", "end", "patch"]),
    ("clearTextStyles", &["clipId"]),
    ("setClipSpeed", &["clipId", "speed"]),
    ("setClipTransform", &["clipId"]),
    ("frameClip", &["clipId", "framing"]),
    ("detachAudio", &["clipId"]),
    ("reattachAudio", &["clipId"]),
    ("addTrack", &[]),
    ("removeTrack", &["trackId"]),
    ("setTrackFlag", &["trackId", "flag", "value"]),
    ("addTimeline", &[]),
    ("removeTimeline", &["timelineId"]),
    ("setTimelineVideo", &["timelineId", "video"]),
    ("setTimelineBackground", &["timelineId", "background"]),
    ("setTimelineLinkage", &["timelineId", "on"]),
    ("renameTimeline", &["timelineId", "name"]),
    ("selectTimeline", &["timelineId"]),
    ("moveTimeline", &["timelineId", "index"]),
    ("addFont", &["family", "path"]),
    ("removeFont", &["family"]),
    ("updateMediaPath", &["mediaId", "newPath"]),
    ("setMediaColorRange", &["mediaId"]),
];

/// Jenis katalog yang dikenal server (`catalogue.list`).
pub static CATALOGUE_KINDS: &[&str] = &["effect", "filter", "audio", "transition", "generator"];

// ---- Pembantu argumen dispatch (nilai hilang == null) ----

fn a_str(args: &Value, key: &str) -> String {
    opt_string(args, key, "")
}

fn a_opt_str(args: &Value, key: &str) -> Option<String> {
    match args.get(key) {
        None | Some(Value::Null) => None,
        Some(_) => Some(opt_string(args, key, "")),
    }
}

fn a_opt_i32(args: &Value, key: &str) -> Option<i32> {
    match args.get(key) {
        None | Some(Value::Null) => None,
        Some(_) => Some(opt_int(args, key, 0)),
    }
}

fn a_opt_i64(args: &Value, key: &str) -> Option<i64> {
    match args.get(key) {
        None | Some(Value::Null) => None,
        Some(_) => Some(opt_long(args, key, 0)),
    }
}

fn a_opt_f64(args: &Value, key: &str) -> Option<f64> {
    match args.get(key) {
        None | Some(Value::Null) => None,
        Some(_) => Some(opt_double(args, key, 0.0)),
    }
}

fn a_opt_bool(args: &Value, key: &str) -> Option<bool> {
    match args.get(key) {
        None | Some(Value::Null) => None,
        Some(_) => Some(opt_bool(args, key, false)),
    }
}

// ---- Pembantu internal operasi ----

/// Folder induk sebuah path ("/p/A" -> "/p"); None bila tak ada induk
/// — padanan `parentFolder` Kotlin (`idx <= 0` -> None).
fn parent_folder(path: &str) -> Option<String> {
    let clean = path.trim_end_matches('/');
    if clean.is_empty() {
        return None;
    }
    let idx = clean.rfind('/')?;
    if idx == 0 {
        return None;
    }
    Some(clean[..idx].to_string())
}

/// Balasan Done ({}) harus objek JSON — padanan `requireDone` Kotlin.
fn require_done(result: &Value, action: &str) -> Result<(), ConcatError> {
    if result.as_object().is_none() {
        return Err(ConcatError::api(format!(
            "Jawaban Concat untuk {action} tidak bisa dibaca."
        )));
    }
    Ok(())
}

/// Baca `project.get` mentah — padanan `fetchView` Kotlin.
fn fetch_view(
    connector: &mut dyn Connector,
    token: &str,
    path: &str,
) -> Result<Value, ConcatError> {
    let result = call(connector, token, "project.get", Some(&json!({"path": path})))?;
    match result {
        Value::Object(_) => Ok(result),
        _ => Err(ConcatError::api(
            "Jawaban Concat untuk membaca proyek tidak bisa dibaca.",
        )),
    }
}

/// Penyelesaian nama -> path — padanan `resolvePath` Kotlin (path
/// mengandung "/" dipakai langsung; nama dicocokkan persis lewat
/// `project.list`, ambil entri pertama).
fn resolve_path(
    connector: &mut dyn Connector,
    token: &str,
    name_or_path: &str,
) -> Result<String, ConcatError> {
    if name_or_path.contains('/') {
        return Ok(name_or_path.to_string());
    }
    let name = kotlin_trim(name_or_path);
    let refs = list_projects(connector, token)?;
    if let Some((_, path)) = refs.iter().find(|(n, _)| n == name) {
        return Ok(path.clone());
    }
    Err(ConcatError::api(format!(
        "Proyek \"{name}\" tidak ketemu — {}",
        if refs.is_empty() {
            "belum ada proyek sama sekali di Concat".to_string()
        } else {
            format!(
                "yang ada: {}",
                refs.iter()
                    .map(|(n, _)| n.clone())
                    .collect::<Vec<_>>()
                    .join(", ")
            )
        }
    )))
}

/// Pindai rekursif: tolak `matchClip` di mana pun di dalam nilai JSON
/// — padanan `rejectMatchClipDeep` Kotlin.
fn reject_match_clip_deep(value: &Value) -> Result<(), ConcatError> {
    match value {
        Value::Object(m) => {
            if opt_string(value, "op", "") == "matchClip" {
                return Err(ConcatError::api(
                    "Op \"matchClip\" tidak bisa dipakai lewat API — itu perintah khusus jendela Concat, bukan perintah API.",
                ));
            }
            for (_, v) in m {
                reject_match_clip_deep(v)?;
            }
            Ok(())
        }
        Value::Array(a) => {
            for v in a {
                reject_match_clip_deep(v)?;
            }
            Ok(())
        }
        _ => Ok(()),
    }
}

fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

// ---- 21 operasi (nilai balik: String ringkasan, kecuali listProjects) ----

pub fn op_version(connector: &mut dyn Connector, token: &str) -> Result<Value, ConcatError> {
    let result = call(connector, token, "version", None)?;
    if result.as_object().is_none() {
        return Err(ConcatError::api(
            "Jawaban Concat untuk pengecekan versi tidak bisa dibaca.",
        ));
    }
    let concat = opt_string(&result, "concat", "");
    let api_version = opt_string(&result, "apiVersion", "");
    if concat.is_empty() || api_version.is_empty() {
        return Err(ConcatError::api(
            "Concat tidak menyebut versinya — coba lagi.",
        ));
    }
    Ok(Value::String(format!(
        "concat {concat} / apiVersion {api_version}"
    )))
}

pub fn list_projects(
    connector: &mut dyn Connector,
    token: &str,
) -> Result<Vec<(String, String)>, ConcatError> {
    let result = call(connector, token, "project.list", None)?;
    let arr = match result.as_array() {
        Some(a) => a,
        None => {
            return Err(ConcatError::api(
                "Daftar proyek dari Concat tidak bisa dibaca.",
            ))
        }
    };
    let mut refs = Vec::new();
    for entry in arr {
        if entry.as_object().is_none() {
            continue;
        }
        let name = opt_string(entry, "name", "");
        let path = opt_string(entry, "path", "");
        if name.is_empty() || path.is_empty() {
            continue;
        }
        refs.push((name, path));
    }
    Ok(refs)
}

fn view_or_err(result: Value, action: &str) -> Result<Value, ConcatError> {
    match result {
        Value::Object(_) => Ok(result),
        _ => Err(ConcatError::api(format!(
            "Jawaban Concat untuk {action} tidak bisa dibaca."
        ))),
    }
}

pub fn op_open_project(
    connector: &mut dyn Connector,
    token: &str,
    name_or_path: &str,
) -> Result<Value, ConcatError> {
    let path = resolve_path(connector, token, name_or_path)?;
    let result = call(
        connector,
        token,
        "project.open",
        Some(&json!({"path": path})),
    )?;
    let view = view_or_err(result, "membuka proyek")?;
    Ok(Value::String(summarize_view(&view, &path, true)))
}

pub fn op_get_project(
    connector: &mut dyn Connector,
    token: &str,
    name_or_path: &str,
) -> Result<Value, ConcatError> {
    let path = resolve_path(connector, token, name_or_path)?;
    let view = fetch_view(connector, token, &path)?;
    Ok(Value::String(summarize_view(&view, &path, false)))
}

pub fn op_create_project(
    connector: &mut dyn Connector,
    token: &str,
    args: &Value,
) -> Result<Value, ConcatError> {
    let name = a_str(args, "name");
    let location = a_opt_str(args, "location");
    let loc = match location {
        Some(l) if !is_blank(&l) => l,
        _ => {
            let refs = list_projects(connector, token)?;
            let first = match refs.first() {
                Some(f) => f,
                None => {
                    return Err(ConcatError::api(
                        "Belum ada proyek sama sekali di Concat — sebutkan folder tempat proyek baru dibuat (location).",
                    ))
                }
            };
            match parent_folder(&first.1) {
                Some(p) => p,
                None => {
                    return Err(ConcatError::api(format!(
                        "Tidak bisa menebak folder induk dari \"{}\" — sebutkan folder tempat proyek baru dibuat (location).",
                        first.1
                    )))
                }
            }
        }
    };
    let width = a_opt_i32(args, "width");
    let height = a_opt_i32(args, "height");
    let rate_num = a_opt_i64(args, "rateNum");
    let rate_den = a_opt_i64(args, "rateDen");
    let mut params = json!({"location": loc, "name": name});
    if width.is_some() || height.is_some() || rate_num.is_some() || rate_den.is_some() {
        params["video"] = json!({
            "width": width.unwrap_or(1920),
            "height": height.unwrap_or(1080),
            "rateNum": rate_num.unwrap_or(30),
            "rateDen": rate_den.unwrap_or(1),
        });
    }
    let result = call(connector, token, "project.create", Some(&params))?;
    let view = view_or_err(result, "membuat proyek")?;
    let full_path = format!("{}/{}", loc.trim_end_matches('/'), name);
    Ok(Value::String(summarize_view(&view, &full_path, true)))
}

pub fn op_close_project(
    connector: &mut dyn Connector,
    token: &str,
    name_or_path: &str,
    save: bool,
) -> Result<Value, ConcatError> {
    let path = resolve_path(connector, token, name_or_path)?;
    let result = call(
        connector,
        token,
        "project.close",
        Some(&json!({"path": path, "save": save})),
    )?;
    require_done(&result, "menutup proyek")?;
    Ok(Value::String(if save {
        format!("Proyek ditutup dan disimpan: {path}")
    } else {
        format!("Proyek ditutup (perubahan yang belum disimpan dibuang): {path}")
    }))
}

pub fn op_save_project(
    connector: &mut dyn Connector,
    token: &str,
    name_or_path: &str,
    name: Option<String>,
) -> Result<Value, ConcatError> {
    let path = resolve_path(connector, token, name_or_path)?;
    let mut params = json!({"path": path});
    let rename = name.as_deref().map_or(false, |n| !is_blank(n));
    if rename {
        params["name"] = json!(name.clone().unwrap_or_default());
    }
    let result = call(connector, token, "project.save", Some(&params))?;
    require_done(&result, "menyimpan proyek")?;
    Ok(Value::String(if rename {
        format!(
            "Proyek disimpan dengan nama baru \"{}\": {path}",
            name.unwrap_or_default()
        )
    } else {
        format!("Proyek disimpan: {path}")
    }))
}

pub fn op_set_video(
    connector: &mut dyn Connector,
    token: &str,
    args: &Value,
) -> Result<Value, ConcatError> {
    let name_or_path = a_str(args, "nameOrPath");
    let width = opt_int(args, "width", 0);
    let height = opt_int(args, "height", 0);
    let rate_num = a_opt_i64(args, "rateNum");
    let rate_den = a_opt_i64(args, "rateDen");
    // Cek pasangan rate DULU (sebelum resolvePath — tanpa panggilan
    // server bila salah) — urutan persis Kotlin.
    if rate_num.is_none() != rate_den.is_none() {
        return Err(ConcatError::api(
            "Isi rateNum dan rateDen dua-duanya, atau kosongkan dua-duanya (nanti diambil dari proyek yang sedang aktif).",
        ));
    }
    let path = resolve_path(connector, token, &name_or_path)?;
    let (num, den) = match (rate_num, rate_den) {
        (Some(n), Some(d)) => (n, d),
        _ => {
            let view = fetch_view(connector, token, &path)?;
            let settings = view.get("settings");
            let n = settings.map(|s| opt_long(s, "rateNum", 0)).unwrap_or(0);
            let d = settings.map(|s| opt_long(s, "rateDen", 0)).unwrap_or(0);
            if n > 0 && d > 0 {
                (n, d)
            } else {
                (30, 1)
            }
        }
    };
    let params = json!({"path": path, "video": {"width": width, "height": height, "rateNum": num, "rateDen": den}});
    let result = call(connector, token, "project.setVideo", Some(&params))?;
    let view = view_or_err(result, "mengatur frame proyek")?;
    Ok(Value::String(summarize_view(&view, &path, false)))
}

pub fn op_apply_edit(
    connector: &mut dyn Connector,
    token: &str,
    args: &Value,
) -> Result<Value, ConcatError> {
    let name_or_path = a_str(args, "nameOrPath");
    let op = a_str(args, "op");
    let edit_args = args.get("args").cloned().unwrap_or(Value::Null);
    let required = match EDIT_OP_REQUIRED.iter().find(|(name, _)| *name == op) {
        Some((_, req)) => *req,
        None => {
            return Err(ConcatError::api(if op == "matchClip" {
                "Op \"matchClip\" tidak bisa dipakai lewat API — itu perintah khusus jendela Concat, bukan perintah API."
                    .to_string()
            } else {
                format!("Op \"{op}\" tidak dikenal — bukan salah satu dari 58 perintah edit Concat.")
            }))
        }
    };
    for field in required {
        if !has(&edit_args, field)
            || edit_args.get(field).map_or(false, |v| v.is_null())
        {
            return Err(ConcatError::api(format!(
                "Perintah \"{op}\" butuh field \"{field}\" — isi dulu baru kirim."
            )));
        }
    }
    if op == "batch" {
        let commands = match opt_arr(&edit_args, "commands") {
            Some(c) => c,
            None => {
                return Err(ConcatError::api(
                    "Perintah \"batch\" butuh field \"commands\" — isi dulu baru kirim.",
                ))
            }
        };
        reject_match_clip_deep(&Value::Array(commands.clone()))?;
    }
    let mut command = match edit_args.as_object() {
        Some(m) => m.clone(),
        None => {
            return Err(ConcatError::api(format!(
                "Perintah \"{op}\" tidak bisa dibaca — coba lagi."
            )))
        }
    };
    command.insert("op".to_string(), json!(op));
    let path = resolve_path(connector, token, &name_or_path)?;
    let params = json!({"path": path, "command": Value::Object(command)});
    let result = call(connector, token, "edit.apply", Some(&params))?;
    let view = view_or_err(result, "menerapkan edit")?;
    Ok(Value::String(summarize_view(&view, &path, false)))
}

pub fn op_undo_edit(
    connector: &mut dyn Connector,
    token: &str,
    name_or_path: &str,
) -> Result<Value, ConcatError> {
    let path = resolve_path(connector, token, name_or_path)?;
    let result = call(
        connector,
        token,
        "edit.undo",
        Some(&json!({"path": path})),
    )?;
    let view = view_or_err(result, "mengurungkan edit")?;
    Ok(Value::String(summarize_view(&view, &path, false)))
}

pub fn op_redo_edit(
    connector: &mut dyn Connector,
    token: &str,
    name_or_path: &str,
) -> Result<Value, ConcatError> {
    let path = resolve_path(connector, token, name_or_path)?;
    let result = call(
        connector,
        token,
        "edit.redo",
        Some(&json!({"path": path})),
    )?;
    let view = view_or_err(result, "mengulangi edit")?;
    Ok(Value::String(summarize_view(&view, &path, false)))
}

pub fn op_probe_media(
    connector: &mut dyn Connector,
    token: &str,
    file: &str,
) -> Result<Value, ConcatError> {
    let result = call(
        connector,
        token,
        "media.probe",
        Some(&json!({"path": file})),
    )?;
    let media = match result {
        Value::Object(_) => result,
        _ => {
            return Err(ConcatError::api(
                "Jawaban Concat untuk memeriksa file tidak bisa dibaca.",
            ))
        }
    };
    Ok(Value::String(summarize_media(&media)))
}

pub fn op_import_media(
    connector: &mut dyn Connector,
    token: &str,
    name_or_path: &str,
    file: &str,
) -> Result<Value, ConcatError> {
    let path = resolve_path(connector, token, name_or_path)?;
    let result = call(
        connector,
        token,
        "media.import",
        Some(&json!({"path": path, "file": file})),
    )?;
    let view = view_or_err(result, "mengimpor media")?;
    Ok(Value::String(summarize_view(&view, &path, false)))
}

pub fn op_list_catalogue(
    connector: &mut dyn Connector,
    token: &str,
    kind: Option<String>,
) -> Result<Value, ConcatError> {
    let clean_kind = kind
        .as_deref()
        .map(kotlin_trim)
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string());
    if let Some(k) = &clean_kind {
        if !CATALOGUE_KINDS.contains(&k.as_str()) {
            return Err(ConcatError::api(format!(
                "Jenis katalog \"{k}\" tidak dikenal — yang ada: effect, filter, audio, transition, generator."
            )));
        }
    }
    let params = clean_kind.map(|k| json!({"kind": k}));
    let result = call(connector, token, "catalogue.list", params.as_ref())?;
    let arr = match result.as_array() {
        Some(a) => a,
        None => {
            return Err(ConcatError::api(
                "Daftar katalog dari Concat tidak bisa dibaca.",
            ))
        }
    };
    Ok(Value::String(summarize_packages(arr)))
}

pub fn op_list_templates(connector: &mut dyn Connector, token: &str) -> Result<Value, ConcatError> {
    let result = call(connector, token, "template.list", None)?;
    let arr = match result.as_array() {
        Some(a) => a,
        None => {
            return Err(ConcatError::api(
                "Daftar template dari Concat tidak bisa dibaca.",
            ))
        }
    };
    Ok(Value::String(summarize_templates(arr)))
}

pub fn op_instantiate_template(
    connector: &mut dyn Connector,
    token: &str,
    args: &Value,
) -> Result<Value, ConcatError> {
    let template = a_str(args, "template");
    let location = a_str(args, "location");
    let name = a_str(args, "name");
    let fills = opt_arr(args, "fills").cloned().unwrap_or_default();
    // Validasi slot: tiap isian harus berisi "mediaId" dan "file".
    // Catatan: `has()` Kotlin TRUE untuk nilai NULL — jadi slot
    // dengan mediaId null LULUS validasi (berbeda dari applyEdit
    // yang menolak NULL eksplisit) — direplika persis.
    for (i, fill) in fills.iter().enumerate() {
        let ok = fill.as_object().map_or(false, |m| {
            m.contains_key("mediaId") && m.contains_key("file")
        });
        if !ok {
            return Err(ConcatError::api(format!(
                "Isian slot template ke-{} harus berisi \"mediaId\" dan \"file\".",
                i + 1
            )));
        }
    }
    let params = json!({"template": template, "location": location, "name": name, "fills": fills});
    let result = call(connector, token, "template.instantiate", Some(&params))?;
    let view = view_or_err(result, "membuat proyek dari template")?;
    let full_path = format!("{}/{}", location.trim_end_matches('/'), name);
    Ok(Value::String(summarize_view(&view, &full_path, true)))
}

pub fn op_save_template(
    connector: &mut dyn Connector,
    token: &str,
    name_or_path: &str,
    name: &str,
) -> Result<Value, ConcatError> {
    let path = resolve_path(connector, token, name_or_path)?;
    let result = call(
        connector,
        token,
        "template.save",
        Some(&json!({"path": path, "name": name})),
    )?;
    let info = match result {
        Value::Object(_) => result,
        _ => {
            return Err(ConcatError::api(
                "Jawaban Concat untuk menyimpan template tidak bisa dibaca.",
            ))
        }
    };
    Ok(Value::String(summarize_template(&info)))
}

pub fn op_run_export(
    connector: &mut dyn Connector,
    token: &str,
    args: &Value,
) -> Result<Value, ConcatError> {
    let name_or_path = a_str(args, "nameOrPath");
    let path = resolve_path(connector, token, &name_or_path)?;
    let mut params = json!({"path": path, "output": a_str(args, "output")});
    if let Some(v) = a_opt_i32(args, "crf") {
        params["crf"] = json!(v);
    }
    if let Some(v) = a_opt_str(args, "preset") {
        params["preset"] = json!(v);
    }
    if let Some(v) = a_opt_i32(args, "width") {
        params["width"] = json!(v);
    }
    if let Some(v) = a_opt_i32(args, "height") {
        params["height"] = json!(v);
    }
    if let Some(v) = a_opt_i64(args, "rateNum") {
        params["rateNum"] = json!(v);
    }
    if let Some(v) = a_opt_i64(args, "rateDen") {
        params["rateDen"] = json!(v);
    }
    if let Some(v) = a_opt_str(args, "codec") {
        params["codec"] = json!(v);
    }
    if let Some(v) = a_opt_bool(args, "tenBit") {
        params["tenBit"] = json!(v);
    }
    if let Some(v) = a_opt_str(args, "colorRange") {
        params["colorRange"] = json!(v);
    }
    if let Some(v) = a_opt_bool(args, "hdr") {
        params["hdr"] = json!(v);
    }
    let result = call(connector, token, "export.run", Some(&params))?;
    let started = match result {
        Value::Object(_) => result,
        _ => {
            return Err(ConcatError::api(
                "Jawaban Concat untuk menjalankan ekspor tidak bisa dibaca.",
            ))
        }
    };
    Ok(Value::String(summarize_started(&started)))
}

pub fn op_cancel_export(
    connector: &mut dyn Connector,
    token: &str,
    job: &str,
) -> Result<Value, ConcatError> {
    let result = call(
        connector,
        token,
        "export.cancel",
        Some(&json!({"job": job})),
    )?;
    require_done(&result, "membatalkan ekspor")?;
    Ok(Value::String(format!("Ekspor job \"{job}\" dibatalkan.")))
}

pub fn op_export_status(
    connector: &mut dyn Connector,
    token: &str,
    job: &str,
) -> Result<Value, ConcatError> {
    let result = call(
        connector,
        token,
        "export.status",
        Some(&json!({"job": job})),
    )?;
    let status = match result {
        Value::Object(_) => result,
        _ => {
            return Err(ConcatError::api(
                "Jawaban Concat untuk status ekspor tidak bisa dibaca.",
            ))
        }
    };
    Ok(Value::String(summarize_job_status(&status)))
}

pub fn op_preview_frame(
    connector: &mut dyn Connector,
    token: &str,
    args: &Value,
    preview_dir: &str,
) -> Result<Value, ConcatError> {
    let name_or_path = a_str(args, "nameOrPath");
    let path = resolve_path(connector, token, &name_or_path)?;
    let time = a_opt_f64(args, "time").unwrap_or(0.0);
    let out = match a_opt_str(args, "output") {
        Some(o) => {
            let t = kotlin_trim(&o);
            if t.is_empty() {
                None
            } else {
                Some(t.to_string())
            }
        }
        None => None,
    };
    let mut params = json!({"path": path, "time": time});
    if let Some(o) = &out {
        params["output"] = json!(o);
    }
    if let Some(w) = a_opt_i32(args, "width") {
        params["width"] = json!(w);
    }
    if let Some(h) = a_opt_i32(args, "height") {
        params["height"] = json!(h);
    }
    // Quirk Kotlin: error apa pun dari `call` yang pesannya
    // mengandung "terlalu besar" dipetakan ulang ke pesan frame
    // (termasuk bila asalnya Auth/Down — catch ConcatApiException
    // lalu lempar ConcatApiException biasa).
    let result = match call(connector, token, "preview.frame", Some(&params)) {
        Err(e) if e.message().contains("terlalu besar") => {
            return Err(ConcatError::api(
                "Gambar frame terlalu besar untuk dibaca — minta width/height lebih kecil atau isi output supaya server yang menulis filenya.",
            ))
        }
        other => other?,
    };
    let obj = match result {
        Value::Object(_) => result,
        _ => {
            return Err(ConcatError::api(
                "Jawaban Concat untuk frame pratinjau tidak bisa dibaca.",
            ))
        }
    };
    if let Some(o) = &out {
        let written_path = opt_string(&obj, "path", o);
        let w = opt_int(&obj, "width", 0);
        let h = opt_int(&obj, "height", 0);
        return Ok(Value::String(if w > 0 && h > 0 {
            format!("Frame tersimpan di: {written_path} ({w}x{h})")
        } else {
            format!("Frame tersimpan di: {written_path}")
        }));
    }
    let png = opt_string(&obj, "png", "");
    if png.is_empty() {
        return Err(ConcatError::api(
            "Concat tidak mengirim gambar frame — coba lagi.",
        ));
    }
    let bytes = match java_base64_decode(&png) {
        Ok(b) => b,
        Err(()) => {
            return Err(ConcatError::api(
                "Gambar frame dari Concat tidak bisa dibaca — coba lagi.",
            ))
        }
    };
    let file_name = format!("concat-preview-{}.png", now_millis());
    let dir = std::path::Path::new(preview_dir);
    let file_path = dir.join(&file_name);
    let path_str = file_path.to_string_lossy().into_owned();
    if std::fs::create_dir_all(dir).is_err() || std::fs::write(&file_path, &bytes).is_err() {
        return Err(ConcatError::api(format!(
            "Gambar frame tidak bisa ditulis ke {path_str} — periksa foldernya lalu coba lagi."
        )));
    }
    let w = opt_int(&obj, "width", 0);
    let h = opt_int(&obj, "height", 0);
    Ok(Value::String(if w > 0 && h > 0 {
        format!("Frame tersimpan di: {path_str} ({w}x{h})")
    } else {
        format!("Frame tersimpan di: {path_str}")
    }))
}

// ------------------------------------------------------------ dispatch ---

/// Jalankan satu operasi ConcatApi lewat `connector` (dipakai JNI
/// dengan `TcpConnector`, tes dengan `MockConnector`).
/// `args` = objek argumen operasi (lihat `dispatch` di bawah untuk
/// bentuk tiap op); `preview_dir` hanya dipakai `previewFrame`.
/// Nilai balik: String ringkasan — kecuali `listProjects`
/// (array `[{"name","path"}]`).
pub fn dispatch(
    connector: &mut dyn Connector,
    op: &str,
    token: &str,
    args: &Value,
    preview_dir: &str,
) -> Result<Value, ConcatError> {
    match op {
        "version" => op_version(connector, token),
        "listProjects" => {
            let refs = list_projects(connector, token)?;
            Ok(Value::Array(
                refs.iter()
                    .map(|(n, p)| json!({"name": n, "path": p}))
                    .collect(),
            ))
        }
        "openProject" => op_open_project(connector, token, &a_str(args, "nameOrPath")),
        "getProject" => op_get_project(connector, token, &a_str(args, "nameOrPath")),
        "createProject" => op_create_project(connector, token, args),
        "closeProject" => op_close_project(
            connector,
            token,
            &a_str(args, "nameOrPath"),
            opt_bool(args, "save", false),
        ),
        "saveProject" => op_save_project(
            connector,
            token,
            &a_str(args, "nameOrPath"),
            a_opt_str(args, "name"),
        ),
        "setVideo" => op_set_video(connector, token, args),
        "applyEdit" => op_apply_edit(connector, token, args),
        "undoEdit" => op_undo_edit(connector, token, &a_str(args, "nameOrPath")),
        "redoEdit" => op_redo_edit(connector, token, &a_str(args, "nameOrPath")),
        "probeMedia" => op_probe_media(connector, token, &a_str(args, "file")),
        "importMedia" => op_import_media(
            connector,
            token,
            &a_str(args, "nameOrPath"),
            &a_str(args, "file"),
        ),
        "listCatalogue" => op_list_catalogue(connector, token, a_opt_str(args, "kind")),
        "listTemplates" => op_list_templates(connector, token),
        "instantiateTemplate" => op_instantiate_template(connector, token, args),
        "saveTemplate" => op_save_template(
            connector,
            token,
            &a_str(args, "nameOrPath"),
            &a_str(args, "name"),
        ),
        "runExport" => op_run_export(connector, token, args),
        "cancelExport" => op_cancel_export(connector, token, &a_str(args, "job")),
        "exportStatus" => op_export_status(connector, token, &a_str(args, "job")),
        "previewFrame" => op_preview_frame(connector, token, args, preview_dir),
        _ => Err(ConcatError::api(format!(
            "Op \"{op}\" tidak dikenal — bukan salah satu dari 21 operasi ConcatApi."
        ))),
    }
}

// ------------------------------------------------------------------ tes ---

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    const FAKE_TOKEN: &str = "test-token-00000000000000000000";
    const WRONG_TOKEN: &str = "token-salah-0000000000000000";

    static PREVIEW_COUNTER: AtomicUsize = AtomicUsize::new(0);

    fn preview_tmpdir() -> String {
        let n = PREVIEW_COUNTER.fetch_add(1, Ordering::SeqCst);
        let dir = std::env::temp_dir().join(format!("concat-p5-{n}"));
        dir.to_string_lossy().into_owned()
    }

    // ---- Fixture (cermin TestConcatApiVc314.kt) ----

    fn version_result(concat: &str) -> Value {
        json!({"apiVersion": "0.2", "concat": concat,
            "dirs": {"config": "/config", "data": "/data"},
            "capabilities": ["events", "json-rpc"]})
    }

    fn project_ref(name: &str, path: &str) -> Value {
        json!({"path": path, "name": name, "width": 1080, "height": 1920,
            "rateNum": 30, "rateDen": 1, "canvas": [0, 0, 0, 255],
            "openedAt": 1792000000000i64})
    }

    fn clip_json(id: &str, name: &str, start: f64, duration: f64) -> Value {
        json!({"id": id, "trackId": "T1", "mediaId": "m1", "name": name,
            "kind": "video", "start": start, "duration": duration, "sourceStart": 0.0})
    }

    fn timeline_json(id: &str, name: &str, clips: Value, tracks: usize) -> Value {
        let mut track_arr = Vec::new();
        for i in 1..=tracks {
            track_arr.push(json!({"id": format!("T{i}"), "visible": true, "muted": false}));
        }
        json!({"id": id, "name": name,
            "video": {"width": 1080, "height": 1920, "rateNum": 30, "rateDen": 1},
            "tracks": track_arr, "clips": clips, "linkage": true})
    }

    fn editor_view(name: &str, timelines: Value, active_id: &str) -> Value {
        json!({"project": {"media": [], "fonts": [], "timelines": timelines,
                "activeTimelineId": active_id},
            "canUndo": false, "canRedo": false,
            "settings": {"name": name, "width": 1080, "height": 1920,
                "rateNum": 30, "rateDen": 1}})
    }

    fn two_clip_view() -> Value {
        let clips = json!([
            clip_json("c1", "LMC_NIL20260316_232520_0001.mp4", 0.0, 150.0),
            clip_json("c2", "LMC_NIL20260316_232521_0002.mp4", 150.0, 118.0),
        ]);
        editor_view(
            "Untitled project",
            json!([timeline_json("TL1", "Timeline 1", clips, 4)]),
            "TL1",
        )
    }

    fn short_clips_view() -> Value {
        let clips = json!([
            clip_json("c1", "klip-a.mp4", 0.0, 12.5),
            clip_json("c2", "klip-b.mp4", 12.5, 12.0),
            clip_json("c3", "klip-c.mp4", 24.5, 45.0),
        ]);
        editor_view(
            "Proyek Pendek",
            json!([timeline_json("TL1", "Timeline 1", clips, 4)]),
            "TL1",
        )
    }

    fn auth_ok_conn() -> MockConn {
        let mut c = MockConn::new();
        c.feed_json(&json!({"jsonrpc": "2.0", "id": 1, "result": {}}));
        c
    }

    /// Koneksi: auth OK + satu balasan `result` untuk id 2.
    fn reply_conn(result: Value) -> MockConn {
        let mut c = auth_ok_conn();
        c.feed_result(2, result);
        c
    }

    fn dispatch_mock(
        conns: Vec<MockConn>,
        op: &str,
        args: Value,
    ) -> Result<Value, ConcatError> {
        let mut mc = MockConnector::new(conns);
        dispatch(&mut mc, op, FAKE_TOKEN, &args, "/tmp/concat-preview-test")
    }

    fn err_message(e: &ConcatError) -> &str {
        e.message()
    }

    // ---- framing / auth ----

    #[test]
    fn framing_auth_line_then_call_line() {
        let c = reply_conn(version_result("0.2.10"));
        let written = c.written.clone();
        let out = dispatch_mock(vec![c], "version", json!({})).unwrap();
        assert_eq!(out.as_str().unwrap(), "concat 0.2.10 / apiVersion 0.2");
        let lines = written.borrow();
        assert_eq!(lines.len(), 2);
        let auth: Value = serde_json::from_str(&lines[0]).unwrap();
        assert_eq!(auth["jsonrpc"], "2.0");
        assert_eq!(auth["id"], 1);
        assert_eq!(auth["method"], "auth");
        assert_eq!(auth["params"]["token"], FAKE_TOKEN);
        let call: Value = serde_json::from_str(&lines[1]).unwrap();
        assert_eq!(call["jsonrpc"], "2.0");
        assert_eq!(call["id"], 2);
        assert_eq!(call["method"], "version");
        assert!(call.get("params").is_none());
    }

    #[test]
    fn framing_call_with_params() {
        let c = reply_conn(two_clip_view());
        let written = c.written.clone();
        let out = dispatch_mock(
            vec![c],
            "openProject",
            json!({"nameOrPath": "/p/Untitled"}),
        )
        .unwrap();
        assert!(out.as_str().unwrap().starts_with("Dibuka: Untitled project"));
        let lines = written.borrow();
        let call: Value = serde_json::from_str(&lines[1]).unwrap();
        assert_eq!(call["method"], "project.open");
        assert_eq!(call["params"]["path"], "/p/Untitled");
    }

    #[test]
    fn auth_wrong_token_is_auth_error_verbatim() {
        let mut c = MockConn::new();
        c.feed_json(
            &json!({"jsonrpc": "2.0", "id": 1,
                "error": {"code": -32006, "message": "wrong token",
                    "data": {"code": "unauthorized"}}}),
        );
        let mut mc = MockConnector::new(vec![c]);
        let e = dispatch(&mut mc, "version", WRONG_TOKEN, &json!({}), "/tmp/x").unwrap_err();
        assert_eq!(e, ConcatError::auth("wrong token"));
        assert!(!e.message().contains(WRONG_TOKEN));
        assert!(!e.message().contains(FAKE_TOKEN));
    }

    #[test]
    fn auth_id_null_with_error_is_answer() {
        let mut c = MockConn::new();
        c.feed_json(
            &json!({"jsonrpc": "2.0", "id": Value::Null,
                "error": {"code": -32006, "message": "wrong token",
                    "data": {"code": "unauthorized"}}}),
        );
        let mut mc = MockConnector::new(vec![c]);
        let e = dispatch(&mut mc, "version", FAKE_TOKEN, &json!({}), "/tmp/x").unwrap_err();
        assert_eq!(e, ConcatError::auth("wrong token"));
    }

    #[test]
    fn auth_id_null_without_error_is_skipped() {
        let mut c = MockConn::new();
        c.feed_json(&json!({"jsonrpc": "2.0", "id": Value::Null, "result": {}}));
        c.feed_json(&json!({"jsonrpc": "2.0", "id": 1, "result": {}}));
        c.feed_result(2, version_result("0.2.10"));
        let out = dispatch_mock(vec![c], "version", json!({})).unwrap();
        assert_eq!(out.as_str().unwrap(), "concat 0.2.10 / apiVersion 0.2");
    }

    #[test]
    fn auth_empty_token_still_sent() {
        let c = reply_conn(version_result("0.2.10"));
        let written = c.written.clone();
        let mut mc = MockConnector::new(vec![c]);
        let out = dispatch(&mut mc, "version", "", &json!({}), "/tmp/x").unwrap();
        assert_eq!(out.as_str().unwrap(), "concat 0.2.10 / apiVersion 0.2");
        let lines = written.borrow();
        let auth: Value = serde_json::from_str(&lines[0]).unwrap();
        assert_eq!(auth["params"]["token"], "");
    }

    #[test]
    fn auth_missing_result_is_api_error() {
        let mut c = MockConn::new();
        c.feed_json(&json!({"jsonrpc": "2.0", "id": 1}));
        let e = dispatch_mock(vec![c], "version", json!({})).unwrap_err();
        assert_eq!(
            e.message(),
            "Concat tidak memberi jawaban saat masuk — coba lagi."
        );
    }

    #[test]
    fn auth_other_code_is_api_not_auth() {
        let mut c = MockConn::new();
        c.feed_json(
            &json!({"jsonrpc": "2.0", "id": 1,
                "error": {"code": -32000, "message": "boom",
                    "data": {"code": "other"}}}),
        );
        let e = dispatch_mock(vec![c], "version", json!({})).unwrap_err();
        assert_eq!(e, ConcatError::api("boom"));
    }

    #[test]
    fn auth_default_message_when_missing() {
        let mut c = MockConn::new();
        c.feed_json(
            &json!({"jsonrpc": "2.0", "id": 1,
                "error": {"code": -32006, "data": {}}}),
        );
        let e = dispatch_mock(vec![c], "version", json!({})).unwrap_err();
        assert_eq!(
            e,
            ConcatError::auth("Concat menolak masuk — tokennya mungkin salah.")
        );
    }

    // ---- timeout / down / EOF ----

    #[test]
    fn timeout_silent_server_is_api_timeout_message() {
        // Auth dijawab, lalu server diam saat panggilan -> Timeout
        // (fase "version", bukan "auth") — seperti R1 #12.
        let c = auth_ok_conn();
        let e = dispatch_mock(vec![c], "version", json!({})).unwrap_err();
        let m = err_message(&e);
        assert!(m.contains("tidak membalas"), "{m}");
        assert!(m.contains("mengecek versi"), "{m}");
        assert!(m.contains("15 detik"), "{m}");
        assert!(!m.contains(FAKE_TOKEN), "{m}");
        assert_eq!(e.kind(), "api");
    }

    #[test]
    fn down_mock_out_of_connections() {
        let e = dispatch_mock(vec![], "version", json!({})).unwrap_err();
        assert_eq!(e, ConcatError::down(DOWN_MESSAGE));
    }

    #[test]
    fn down_real_tcp_connect_refused() {
        // Port 7420 harus bebas di mesin tes; bila terpakai, lewati
        // (bukan kegagalan porting) — buktikan dengan bind dulu.
        if std::net::TcpListener::bind("127.0.0.1:7420").is_err() {
            return;
        }
        let mut tcp = TcpConnector;
        let e = dispatch(&mut tcp, "version", FAKE_TOKEN, &json!({}), "/tmp/x").unwrap_err();
        assert_eq!(e, ConcatError::down(DOWN_MESSAGE));
    }

    #[test]
    fn eof_before_auth_is_down() {
        let mut c = MockConn::new();
        c.feed_eof();
        let e = dispatch_mock(vec![c], "version", json!({})).unwrap_err();
        assert_eq!(e.kind(), "down");
        assert!(e.message().contains("putus sebelum sempat masuk"), "{}", e.message());
    }

    #[test]
    fn eof_during_call_is_api_putus() {
        let mut c = auth_ok_conn();
        c.feed_eof();
        c.feed_eof();
        let e = dispatch_mock(vec![c], "version", json!({})).unwrap_err();
        assert_eq!(e.kind(), "api");
        assert!(e.message().contains("putus di tengah jalan"), "{}", e.message());
        assert!(e.message().contains("mengecek versi"), "{}", e.message());
    }

    // ---- MAX_LINE / framing rusak ----

    #[test]
    fn max_line_too_big_rejected() {
        let mut c = auth_ok_conn();
        c.feed(&"a".repeat(MAX_LINE_BYTES + 100));
        c.feed("\n");
        let e = dispatch_mock(vec![c], "version", json!({})).unwrap_err();
        assert!(e.message().contains("terlalu besar"), "{}", e.message());
    }

    #[test]
    fn max_line_exact_limit_ok() {
        let prefix = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"";
        let suffix = "\"}";
        let pad_len = MAX_LINE_BYTES - prefix.len() - suffix.len();
        let mut c = auth_ok_conn();
        c.feed(prefix);
        c.feed(&"x".repeat(pad_len));
        c.feed(suffix);
        c.feed("\n");
        let mut mc = MockConnector::new(vec![c]);
        let v = call(&mut mc, FAKE_TOKEN, "version", None).unwrap();
        assert_eq!(v.as_str().unwrap().len(), pad_len);
    }

    #[test]
    fn max_line_one_over_rejected() {
        let prefix = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"";
        let suffix = "\"}";
        let pad_len = MAX_LINE_BYTES - prefix.len() - suffix.len() + 1;
        let mut c = auth_ok_conn();
        c.feed(prefix);
        c.feed(&"x".repeat(pad_len));
        c.feed(suffix);
        c.feed("\n");
        let mut mc = MockConnector::new(vec![c]);
        let e = call(&mut mc, FAKE_TOKEN, "version", None).unwrap_err();
        assert!(e.message().contains("terlalu besar"), "{}", e.message());
    }

    #[test]
    fn non_json_line_is_unreadable() {
        let mut c = auth_ok_conn();
        c.feed("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":}\n");
        let e = dispatch_mock(vec![c], "version", json!({})).unwrap_err();
        assert_eq!(e.message(), "Jawaban Concat tidak bisa dibaca — coba lagi.");
    }

    #[test]
    fn json_array_line_is_unreadable() {
        let mut c = auth_ok_conn();
        c.feed("[1, 2, 3]\n");
        let e = dispatch_mock(vec![c], "version", json!({})).unwrap_err();
        assert_eq!(e.message(), "Jawaban Concat tidak bisa dibaca — coba lagi.");
    }

    #[test]
    fn event_lines_wrong_id_blank_and_string_id_skipped() {
        let mut c = auth_ok_conn();
        c.feed("{\"jsonrpc\":\"2.0\",\"method\":\"export.progress\",\"params\":{\"job\":\"j1\",\"frame\":1,\"total\":10}}\n");
        c.feed("{\"jsonrpc\":\"2.0\",\"method\":\"export.progress\",\"params\":{\"job\":\"j1\",\"frame\":2,\"total\":10}}\n");
        c.feed("{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{\"apiVersion\":\"9.9\",\"concat\":\"9.9\"}}\n");
        c.feed("\n");
        c.feed("{\"jsonrpc\":\"2.0\",\"id\":null,\"result\":{}}\n");
        c.feed("{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{}}\n");
        c.feed("   \n");
        c.feed_result(2, version_result("0.2.10"));
        let out = dispatch_mock(vec![c], "version", json!({})).unwrap();
        assert_eq!(out.as_str().unwrap(), "concat 0.2.10 / apiVersion 0.2");
    }

    #[test]
    fn missing_result_call_is_api_error() {
        let mut c = auth_ok_conn();
        c.feed_json(&json!({"jsonrpc": "2.0", "id": 2}));
        let e = dispatch_mock(vec![c], "version", json!({})).unwrap_err();
        assert_eq!(
            e.message(),
            "Concat tidak memberi jawaban untuk mengecek versi — coba lagi."
        );
    }

    #[test]
    fn call_error_verbatim() {
        let mut c = auth_ok_conn();
        c.feed_error(2, -32001, "notOpen", "/p/X is not open - open it first");
        let e = dispatch_mock(vec![c], "getProject", json!({"nameOrPath": "/p/X"})).unwrap_err();
        assert_eq!(e.message(), "/p/X is not open - open it first");
    }

    // ---- error mapping (lock) ----

    #[test]
    fn open_lock_by_code_gets_hint() {
        let mut c = auth_ok_conn();
        c.feed_error(2, -32003, "refused", "/p/Untitled is open in the window");
        let e = dispatch_mock(vec![c], "openProject", json!({"nameOrPath": "/p/Untitled"})).unwrap_err();
        assert!(e.message().contains("/p/Untitled is open in the window"), "{}", e.message());
        assert!(e.message().contains(LOCK_HINT), "{}", e.message());
    }

    #[test]
    fn open_lock_by_message_text_gets_hint() {
        let mut c = auth_ok_conn();
        c.feed_error(2, -32000, "other", "Project is Open In the window already");
        let e = dispatch_mock(vec![c], "openProject", json!({"nameOrPath": "/p/X"})).unwrap_err();
        assert!(e.message().contains(LOCK_HINT), "{}", e.message());
    }

    #[test]
    fn open_non_lock_no_hint() {
        let mut c = auth_ok_conn();
        c.feed_error(2, -32000, "failed", "could not read /p/X/concat.json: No such file");
        let e = dispatch_mock(vec![c], "openProject", json!({"nameOrPath": "/p/X"})).unwrap_err();
        assert!(e.message().contains("could not read /p/X/concat.json"), "{}", e.message());
        assert!(!e.message().contains("tutup dulu proyek"), "{}", e.message());
    }

    #[test]
    fn non_open_method_never_gets_lock_hint() {
        let mut c = auth_ok_conn();
        c.feed_error(2, -32003, "refused", "something open in here");
        let e = dispatch_mock(vec![c], "getProject", json!({"nameOrPath": "/p/X"})).unwrap_err();
        assert!(!e.message().contains(LOCK_HINT), "{}", e.message());
    }

    // ---- version / list / open / get ----

    #[test]
    fn version_reads_server_numbers_not_hardcoded() {
        let out = dispatch_mock(vec![reply_conn(version_result("0.2.8"))], "version", json!({})).unwrap();
        assert_eq!(out.as_str().unwrap(), "concat 0.2.8 / apiVersion 0.2");
    }

    #[test]
    fn version_missing_fields_is_error() {
        let out = dispatch_mock(
            vec![reply_conn(json!({"concat": "0.2.10"}))],
            "version",
            json!({}),
        );
        assert!(out.is_err());
    }

    #[test]
    fn version_non_object_result_is_error() {
        let out = dispatch_mock(vec![reply_conn(json!("0.2.10"))], "version", json!({})).unwrap_err();
        assert_eq!(
            out.message(),
            "Jawaban Concat untuk pengecekan versi tidak bisa dibaca."
        );
    }

    #[test]
    fn list_projects_maps_two_refs_in_order() {
        let refs = json!([project_ref("Untitled project", "/p/A"), project_ref("Proyek Liburan", "/p/B")]);
        let out = dispatch_mock(vec![reply_conn(refs)], "listProjects", json!({})).unwrap();
        assert_eq!(
            out,
            json!([{"name": "Untitled project", "path": "/p/A"}, {"name": "Proyek Liburan", "path": "/p/B"}])
        );
    }

    #[test]
    fn list_projects_empty_and_partial() {
        let out = dispatch_mock(vec![reply_conn(json!([]))], "listProjects", json!({})).unwrap();
        assert_eq!(out, json!([]));
        let partial = json!([
            {"name": "Tanpa Path"},
            {"path": "/p/tanpa-nama"},
            project_ref("Lengkap", "/p/C"),
        ]);
        let out = dispatch_mock(vec![reply_conn(partial)], "listProjects", json!({})).unwrap();
        assert_eq!(out, json!([{"name": "Lengkap", "path": "/p/C"}]));
    }

    #[test]
    fn list_projects_non_array_is_error() {
        let e = dispatch_mock(vec![reply_conn(json!({}))], "listProjects", json!({})).unwrap_err();
        assert_eq!(e.message(), "Daftar proyek dari Concat tidak bisa dibaca.");
    }

    const TWO_CLIP_GET: &str = "Proyek: Untitled project\nPath: /p/Untitled\nFrame: 1080x1920 @ 30 fps\nTimeline: Timeline 1 — 4 track, 2 klip, total 4:28\nKlip:\n- LMC_NIL20260316_232520_0001.mp4 (2:30)\n- LMC_NIL20260316_232521_0002.mp4 (1:58)";

    #[test]
    fn open_project_path_direct_exact_summary() {
        let c = reply_conn(two_clip_view());
        let written = c.written.clone();
        let out = dispatch_mock(vec![c], "openProject", json!({"nameOrPath": "/p/Untitled"})).unwrap();
        let s = out.as_str().unwrap();
        assert!(s.starts_with("Dibuka: Untitled project\nPath: /p/Untitled\n"), "{s}");
        assert!(s.contains("1080x1920 @ 30 fps"), "{s}");
        assert!(s.contains("4:28"), "{s}");
        assert!(s.ends_with("Catatan: proyek ini sekarang dipegang API — layar Concat tidak bisa membukanya sampai ditutup lewat perintah (concat_project_close)."), "{s}");
        // Tanpa project.list: hanya 1 koneksi, 2 baris tertulis.
        let lines = written.borrow();
        assert_eq!(lines.len(), 2);
        let call: Value = serde_json::from_str(&lines[1]).unwrap();
        assert_eq!(call["method"], "project.open");
        assert_eq!(call["params"]["path"], "/p/Untitled");
    }

    #[test]
    fn open_project_by_name_lists_first_then_opens() {
        let list = reply_conn(json!([project_ref("Untitled project", "/p/Untitled")]));
        let open = reply_conn(two_clip_view());
        let w1 = list.written.clone();
        let w2 = open.written.clone();
        let out = dispatch_mock(vec![list, open], "openProject", json!({"nameOrPath": "Untitled project"})).unwrap();
        assert!(out.as_str().unwrap().contains("Dibuka: Untitled project"));
        let m1: Value = serde_json::from_str(&w1.borrow()[1]).unwrap();
        let m2: Value = serde_json::from_str(&w2.borrow()[1]).unwrap();
        assert_eq!(m1["method"], "project.list");
        assert_eq!(m2["method"], "project.open");
        assert_eq!(m2["params"]["path"], "/p/Untitled");
    }

    #[test]
    fn open_unknown_name_error_lists_available() {
        let list = reply_conn(json!([project_ref("Untitled project", "/p/A"), project_ref("Proyek Liburan", "/p/B")]));
        let e = dispatch_mock(vec![list], "openProject", json!({"nameOrPath": "Tidak Ada"})).unwrap_err();
        let m = e.message();
        assert!(m.contains("Tidak Ada"), "{m}");
        assert!(m.contains("Untitled project"), "{m}");
        assert!(m.contains("Proyek Liburan"), "{m}");
        assert!(m.contains("tidak ketemu"), "{m}");
    }

    #[test]
    fn open_unknown_name_empty_list() {
        let list = reply_conn(json!([]));
        let e = dispatch_mock(vec![list], "openProject", json!({"nameOrPath": "Tidak Ada"})).unwrap_err();
        assert!(e.message().contains("belum ada proyek sama sekali"), "{}", e.message());
    }

    #[test]
    fn open_name_match_is_case_sensitive() {
        let list = reply_conn(json!([project_ref("Untitled project", "/p/A")]));
        let e = dispatch_mock(vec![list], "openProject", json!({"nameOrPath": "untitled project"})).unwrap_err();
        assert!(e.message().contains("tidak ketemu"), "{}", e.message());
    }

    #[test]
    fn get_project_exact_summary() {
        let out = dispatch_mock(vec![reply_conn(two_clip_view())], "getProject", json!({"nameOrPath": "/p/Untitled"})).unwrap();
        assert_eq!(out.as_str().unwrap(), TWO_CLIP_GET);
    }

    #[test]
    fn get_project_by_name_lists_first() {
        let list = reply_conn(json!([project_ref("Untitled project", "/p/Untitled")]));
        let get = reply_conn(two_clip_view());
        let w1 = list.written.clone();
        let w2 = get.written.clone();
        let out = dispatch_mock(vec![list, get], "getProject", json!({"nameOrPath": "Untitled project"})).unwrap();
        assert_eq!(out.as_str().unwrap(), TWO_CLIP_GET);
        let m1: Value = serde_json::from_str(&w1.borrow()[1]).unwrap();
        let m2: Value = serde_json::from_str(&w2.borrow()[1]).unwrap();
        assert_eq!(m1["method"], "project.list");
        assert_eq!(m2["method"], "project.get");
    }

    #[test]
    fn summary_short_clips_durations() {
        let out = dispatch_mock(vec![reply_conn(short_clips_view())], "getProject", json!({"nameOrPath": "/p/Pendek"})).unwrap();
        let s = out.as_str().unwrap();
        assert!(s.contains("klip-a.mp4 (12.5 dtk)"), "{s}");
        assert!(s.contains("klip-b.mp4 (12 dtk)"), "{s}");
        assert!(s.contains("klip-c.mp4 (45 dtk)"), "{s}");
        assert!(s.contains("total 1:09"), "{s}");
        assert!(!s.contains("\"timelines\""), "{s}");
        assert!(!s.contains('{'), "{s}");
    }

    #[test]
    fn summary_200_clips_capped_with_remainder() {
        let mut clips = Vec::new();
        for i in 1..=200 {
            clips.push(clip_json(&format!("c{i}"), &format!("Klip {i}"), (i - 1) as f64 * 5.0, 5.0));
        }
        let view = editor_view(
            "Proyek Besar",
            json!([timeline_json("TL1", "Timeline 1", Value::Array(clips), 4)]),
            "TL1",
        );
        let out = dispatch_mock(vec![reply_conn(view)], "getProject", json!({"nameOrPath": "/p/Besar"})).unwrap();
        let s = out.as_str().unwrap();
        assert!(utf16_len(s) <= 2000, "panjang={}", utf16_len(s));
        assert!(s.contains("klip lagi"), "{s}");
    }

    #[test]
    fn summary_uses_active_timeline_not_first() {
        let view = editor_view(
            "Dua Timeline",
            json!([
                timeline_json("TL1", "Timeline 1", json!([clip_json("c1", "Klip Lama", 0.0, 10.0)]), 4),
                timeline_json("TL2", "Timeline 2", json!([clip_json("c2", "Klip Aktif", 0.0, 20.0)]), 4),
            ]),
            "TL2",
        );
        let out = dispatch_mock(vec![reply_conn(view)], "getProject", json!({"nameOrPath": "/p/Dua"})).unwrap();
        let s = out.as_str().unwrap();
        assert!(s.contains("Timeline 2"), "{s}");
        assert!(s.contains("Klip Aktif"), "{s}");
        assert!(!s.contains("Klip Lama"), "{s}");
    }

    #[test]
    fn summary_zero_clips() {
        let view = editor_view(
            "Kosong",
            json!([timeline_json("TL1", "Timeline 1", json!([]), 4)]),
            "TL1",
        );
        let out = dispatch_mock(vec![reply_conn(view)], "getProject", json!({"nameOrPath": "/p/Kosong"})).unwrap();
        let s = out.as_str().unwrap();
        assert!(s.contains("0 klip"), "{s}");
        assert!(s.contains("total 0 dtk"), "{s}");
    }

    // ---- operasi vc315 ----

    #[test]
    fn create_project_location_given_no_video() {
        let c = reply_conn(two_clip_view());
        let written = c.written.clone();
        let out = dispatch_mock(
            vec![c],
            "createProject",
            json!({"name": "Baru", "location": "/p"}),
        )
        .unwrap();
        assert!(out.as_str().unwrap().contains("Dibuka: Untitled project"));
        assert!(out.as_str().unwrap().contains("Path: /p/Baru"));
        let lines = written.borrow();
        let call: Value = serde_json::from_str(&lines[1]).unwrap();
        assert_eq!(call["method"], "project.create");
        assert_eq!(call["params"]["location"], "/p");
        assert_eq!(call["params"]["name"], "Baru");
        assert!(call["params"].get("video").is_none());
    }

    #[test]
    fn create_project_blank_location_uses_parent_of_first() {
        let list = reply_conn(json!([project_ref("A", "/p/A")]));
        let create = reply_conn(two_clip_view());
        let w2 = create.written.clone();
        let out = dispatch_mock(
            vec![list, create],
            "createProject",
            json!({"name": "Baru", "location": "   "}),
        )
        .unwrap();
        assert!(out.as_str().unwrap().contains("Path: /p/Baru"));
        let call: Value = serde_json::from_str(&w2.borrow()[1]).unwrap();
        assert_eq!(call["params"]["location"], "/p");
    }

    #[test]
    fn create_project_empty_list_is_honest_error() {
        let list = reply_conn(json!([]));
        let e = dispatch_mock(vec![list], "createProject", json!({"name": "Baru"})).unwrap_err();
        assert!(e.message().contains("Belum ada proyek sama sekali"), "{}", e.message());
    }

    #[test]
    fn create_project_no_parent_is_honest_error() {
        let list = reply_conn(json!([project_ref("A", "/p")]));
        let e = dispatch_mock(vec![list], "createProject", json!({"name": "Baru"})).unwrap_err();
        assert!(e.message().contains("Tidak bisa menebak folder induk"), "{}", e.message());
    }

    #[test]
    fn create_project_partial_video_gets_defaults() {
        let c = reply_conn(two_clip_view());
        let written = c.written.clone();
        dispatch_mock(vec![c], "createProject", json!({"name": "Baru", "location": "/p", "width": 1280})).unwrap();
        let call: Value = serde_json::from_str(&written.borrow()[1]).unwrap();
        assert_eq!(call["params"]["video"]["width"], 1280);
        assert_eq!(call["params"]["video"]["height"], 1080);
        assert_eq!(call["params"]["video"]["rateNum"], 30);
        assert_eq!(call["params"]["video"]["rateDen"], 1);
    }

    #[test]
    fn close_and_save_messages() {
        let out = dispatch_mock(
            vec![reply_conn(json!({}))],
            "closeProject",
            json!({"nameOrPath": "/p/A", "save": true}),
        )
        .unwrap();
        assert_eq!(out.as_str().unwrap(), "Proyek ditutup dan disimpan: /p/A");
        let out = dispatch_mock(
            vec![reply_conn(json!({}))],
            "closeProject",
            json!({"nameOrPath": "/p/A", "save": false}),
        )
        .unwrap();
        assert_eq!(
            out.as_str().unwrap(),
            "Proyek ditutup (perubahan yang belum disimpan dibuang): /p/A"
        );
        let out = dispatch_mock(
            vec![reply_conn(json!({}))],
            "saveProject",
            json!({"nameOrPath": "/p/A", "name": "Baru"}),
        )
        .unwrap();
        assert_eq!(out.as_str().unwrap(), "Proyek disimpan dengan nama baru \"Baru\": /p/A");
        let out = dispatch_mock(
            vec![reply_conn(json!({}))],
            "saveProject",
            json!({"nameOrPath": "/p/A"}),
        )
        .unwrap();
        assert_eq!(out.as_str().unwrap(), "Proyek disimpan: /p/A");
        // Nama blank -> bukan rename.
        let out = dispatch_mock(
            vec![reply_conn(json!({}))],
            "saveProject",
            json!({"nameOrPath": "/p/A", "name": "   "}),
        )
        .unwrap();
        assert_eq!(out.as_str().unwrap(), "Proyek disimpan: /p/A");
    }

    #[test]
    fn set_video_rate_pair_checked_before_any_call() {
        // Nol koneksi disediakan: bila validasi jalan dulu, hasilnya
        // error pasangan (bukan Down karena kehabisan koneksi).
        let e = dispatch_mock(
            vec![],
            "setVideo",
            json!({"nameOrPath": "/p/A", "width": 1920, "height": 1080, "rateNum": 30}),
        )
        .unwrap_err();
        assert!(e.message().contains("dua-duanya"), "{}", e.message());
    }

    #[test]
    fn set_video_rate_from_server_view() {
        let get = reply_conn(editor_view(
            "V",
            json!([timeline_json("TL1", "Timeline 1", json!([]), 4)]),
            "TL1",
        ));
        // settings view di atas rateNum 30/rateDen 1 -> pakai itu.
        let set = reply_conn(two_clip_view());
        let w2 = set.written.clone();
        dispatch_mock(
            vec![get, set],
            "setVideo",
            json!({"nameOrPath": "/p/A", "width": 1920, "height": 1080}),
        )
        .unwrap();
        let call: Value = serde_json::from_str(&w2.borrow()[1]).unwrap();
        assert_eq!(call["method"], "project.setVideo");
        assert_eq!(call["params"]["video"]["rateNum"], 30);
        assert_eq!(call["params"]["video"]["rateDen"], 1);
    }

    #[test]
    fn set_video_rate_given_is_used_verbatim() {
        let set = reply_conn(two_clip_view());
        let written = set.written.clone();
        dispatch_mock(
            vec![set],
            "setVideo",
            json!({"nameOrPath": "/p/A", "width": 1280, "height": 720, "rateNum": 24000, "rateDen": 1001}),
        )
        .unwrap();
        let call: Value = serde_json::from_str(&written.borrow()[1]).unwrap();
        assert_eq!(call["params"]["video"]["rateNum"], 24000);
        assert_eq!(call["params"]["video"]["rateDen"], 1001);
    }

    #[test]
    fn apply_edit_validation_before_call() {
        // Op tak dikenal.
        let e = dispatch_mock(
            vec![],
            "applyEdit",
            json!({"nameOrPath": "/p/A", "op": "nope", "args": {}}),
        )
        .unwrap_err();
        assert!(e.message().contains("tidak dikenal"), "{}", e.message());
        // matchClip.
        let e = dispatch_mock(
            vec![],
            "applyEdit",
            json!({"nameOrPath": "/p/A", "op": "matchClip", "args": {}}),
        )
        .unwrap_err();
        assert!(e.message().contains("matchClip"), "{}", e.message());
        // Field wajib hilang.
        let e = dispatch_mock(
            vec![],
            "applyEdit",
            json!({"nameOrPath": "/p/A", "op": "trimClip", "args": {"clipId": "c1"}}),
        )
        .unwrap_err();
        assert!(e.message().contains("butuh field \"edge\""), "{}", e.message());
        // Field wajib null.
        let e = dispatch_mock(
            vec![],
            "applyEdit",
            json!({"nameOrPath": "/p/A", "op": "trimClip", "args": {"clipId": "c1", "edge": null, "delta": 1.0}}),
        )
        .unwrap_err();
        assert!(e.message().contains("butuh field \"edge\""), "{}", e.message());
        // batch tanpa commands (bukan array).
        let e = dispatch_mock(
            vec![],
            "applyEdit",
            json!({"nameOrPath": "/p/A", "op": "batch", "args": {"commands": "x"}}),
        )
        .unwrap_err();
        assert!(e.message().contains("butuh field \"commands\""), "{}", e.message());
        // matchClip jauh di dalam batch.
        let e = dispatch_mock(
            vec![],
            "applyEdit",
            json!({"nameOrPath": "/p/A", "op": "batch", "args": {"commands": [{"op": "addTrack"}, {"op": "batch", "commands": [{"op": "matchClip"}]}]}}),
        )
        .unwrap_err();
        assert!(e.message().contains("matchClip"), "{}", e.message());
    }

    #[test]
    fn apply_edit_success_sends_op_in_command() {
        let c = reply_conn(two_clip_view());
        let written = c.written.clone();
        let out = dispatch_mock(
            vec![c],
            "applyEdit",
            json!({"nameOrPath": "/p/A", "op": "addTrack", "args": {}}),
        )
        .unwrap();
        assert!(out.as_str().unwrap().contains("Proyek: Untitled project"));
        let call: Value = serde_json::from_str(&written.borrow()[1]).unwrap();
        assert_eq!(call["method"], "edit.apply");
        assert_eq!(call["params"]["command"]["op"], "addTrack");
    }

    #[test]
    fn undo_redo_summaries() {
        let out = dispatch_mock(vec![reply_conn(two_clip_view())], "undoEdit", json!({"nameOrPath": "/p/Untitled"})).unwrap();
        assert_eq!(out.as_str().unwrap(), TWO_CLIP_GET);
        let out = dispatch_mock(vec![reply_conn(two_clip_view())], "redoEdit", json!({"nameOrPath": "/p/Untitled"})).unwrap();
        assert_eq!(out.as_str().unwrap(), TWO_CLIP_GET);
    }

    #[test]
    fn probe_and_import_media() {
        let media = json!({"path": "/v/a.mp4", "kind": "video", "duration": 12.5,
            "video": {"width": 1920, "height": 1080}, "audioTracks": [{"id": "a1"}]});
        let out = dispatch_mock(vec![reply_conn(media)], "probeMedia", json!({"file": "/v/a.mp4"})).unwrap();
        let s = out.as_str().unwrap();
        assert!(s.contains("Media: /v/a.mp4"), "{s}");
        assert!(s.contains("Jenis: video"), "{s}");
        assert!(s.contains("Durasi: 12.5 dtk"), "{s}");
        assert!(s.contains("Video: 1920x1080"), "{s}");
        assert!(s.contains("Audio: ya"), "{s}");
        let out = dispatch_mock(vec![reply_conn(two_clip_view())], "importMedia", json!({"nameOrPath": "/p/Untitled", "file": "/v/a.mp4"})).unwrap();
        assert_eq!(out.as_str().unwrap(), TWO_CLIP_GET);
    }

    #[test]
    fn list_catalogue_kind_validation_and_params() {
        let e = dispatch_mock(vec![], "listCatalogue", json!({"kind": "aneh"})).unwrap_err();
        assert!(e.message().contains("tidak dikenal"), "{}", e.message());
        let pkgs = json!([{"id": "fx1", "name": "Blur", "kind": "effect", "category": "Blur"}]);
        let c = reply_conn(pkgs);
        let written = c.written.clone();
        let out = dispatch_mock(vec![c], "listCatalogue", json!({"kind": "  effect  "})).unwrap();
        assert!(out.as_str().unwrap().contains("Katalog: 1 paket"), "{}", out.as_str().unwrap());
        assert!(out.as_str().unwrap().contains("fx1 — Blur (effect, Blur)"));
        let call: Value = serde_json::from_str(&written.borrow()[1]).unwrap();
        assert_eq!(call["params"]["kind"], "effect");
        // kind kosong -> semua, tanpa params.
        let c = reply_conn(json!([]));
        let written = c.written.clone();
        dispatch_mock(vec![c], "listCatalogue", json!({"kind": "   "})).unwrap();
        let call: Value = serde_json::from_str(&written.borrow()[1]).unwrap();
        assert!(call.get("params").is_none());
    }

    #[test]
    fn list_templates_and_save_template() {
        let tpls = json!([{"name": "Intro", "width": 1920, "height": 1080, "rateNum": 30, "rateDen": 1, "slots": [{}, {}]}]);
        let out = dispatch_mock(vec![reply_conn(tpls)], "listTemplates", json!({})).unwrap();
        assert!(out.as_str().unwrap().contains("Template: 1 template"));
        assert!(out.as_str().unwrap().contains("Intro — 1920x1080 @ 30 fps, 2 slot"));
        let info = json!({"name": "Intro", "width": 1920, "height": 1080, "rateNum": 30, "rateDen": 1, "slots": [{}]});
        let out = dispatch_mock(vec![reply_conn(info)], "saveTemplate", json!({"nameOrPath": "/p/A", "name": "Intro"})).unwrap();
        let s = out.as_str().unwrap();
        assert!(s.contains("Template: Intro"), "{s}");
        assert!(s.contains("Ukuran: 1920x1080 @ 30 fps"), "{s}");
        assert!(s.contains("Slot: 1"), "{s}");
    }

    #[test]
    fn instantiate_template_fill_validation() {
        // Elemen bukan objek.
        let e = dispatch_mock(
            vec![],
            "instantiateTemplate",
            json!({"template": "T", "location": "/p", "name": "N", "fills": ["x"]}),
        )
        .unwrap_err();
        assert!(e.message().contains("ke-1"), "{}", e.message());
        // Slot ke-2 tanpa file.
        let e = dispatch_mock(
            vec![],
            "instantiateTemplate",
            json!({"template": "T", "location": "/p", "name": "N", "fills": [{"mediaId": "m1", "file": "f1"}, {"mediaId": "m2"}]}),
        )
        .unwrap_err();
        assert!(e.message().contains("ke-2"), "{}", e.message());
        // mediaId NULL lulus validasi (quirk `has()` Kotlin) -> server menjawab.
        let out = dispatch_mock(
            vec![reply_conn(two_clip_view())],
            "instantiateTemplate",
            json!({"template": "T", "location": "/p", "name": "N", "fills": [{"mediaId": null, "file": "f1"}]}),
        )
        .unwrap();
        assert!(out.as_str().unwrap().contains("Path: /p/N"));
    }

    #[test]
    fn run_export_cancel_status() {
        let c = reply_conn(json!({"job": "j1", "output": "/o/v.mp4"}));
        let written = c.written.clone();
        let out = dispatch_mock(
            vec![c],
            "runExport",
            json!({"nameOrPath": "/p/A", "output": "/o/v.mp4", "crf": 23}),
        )
        .unwrap();
        let s = out.as_str().unwrap();
        assert!(s.contains("Ekspor jalan, job j1"), "{s}");
        assert!(s.contains("Output: /o/v.mp4"), "{s}");
        let call: Value = serde_json::from_str(&written.borrow()[1]).unwrap();
        assert_eq!(call["params"]["crf"], 23);
        assert!(call["params"].get("preset").is_none());
        let out = dispatch_mock(vec![reply_conn(json!({}))], "cancelExport", json!({"job": "j1"})).unwrap();
        assert_eq!(out.as_str().unwrap(), "Ekspor job \"j1\" dibatalkan.");
        let status = json!({"job": "j1", "state": "running", "stage": "video", "frame": 75, "total": 100, "fraction": 0.75, "output": "/o/v.mp4"});
        let out = dispatch_mock(vec![reply_conn(status)], "exportStatus", json!({"job": "j1"})).unwrap();
        let s = out.as_str().unwrap();
        assert!(s.contains("Ekspor job j1: lagi jalan"), "{s}");
        assert!(s.contains("Tahap: video"), "{s}");
        assert!(s.contains("Frame: 75/100 (75%)"), "{s}");
        let status = json!({"job": "j1", "state": "failed", "error": {"message": "disk penuh"}});
        let out = dispatch_mock(vec![reply_conn(status)], "exportStatus", json!({"job": "j1"})).unwrap();
        assert!(out.as_str().unwrap().contains("gagal"), "{}", out.as_str().unwrap());
        assert!(out.as_str().unwrap().contains("Error: disk penuh"));
        // State hilang -> "tidak diketahui"; state kosong eksplisit -> "".
        let out = dispatch_mock(vec![reply_conn(json!({"job": "j1"}))], "exportStatus", json!({"job": "j1"})).unwrap();
        assert!(out.as_str().unwrap().contains("tidak diketahui"));
        let out = dispatch_mock(vec![reply_conn(json!({"job": "j1", "state": ""}))], "exportStatus", json!({"job": "j1"})).unwrap();
        assert_eq!(out.as_str().unwrap(), "Ekspor job j1: ");
    }

    // ---- previewFrame ----

    fn dispatch_mock_dir(
        conns: Vec<MockConn>,
        op: &str,
        args: Value,
        dir: &str,
    ) -> Result<Value, ConcatError> {
        let mut mc = MockConnector::new(conns);
        dispatch(&mut mc, op, FAKE_TOKEN, &args, dir)
    }

    #[test]
    fn preview_frame_server_writes_file() {
        let obj = json!({"path": "/srv/f.png", "width": 1920, "height": 1080});
        let out = dispatch_mock(
            vec![reply_conn(obj)],
            "previewFrame",
            json!({"nameOrPath": "/p/A", "time": 1.5, "output": "/srv/f.png"}),
        )
        .unwrap();
        assert_eq!(
            out.as_str().unwrap(),
            "Frame tersimpan di: /srv/f.png (1920x1080)"
        );
        // Server tak kirim path -> pakai `output` apa adanya.
        let out = dispatch_mock(
            vec![reply_conn(json!({}))],
            "previewFrame",
            json!({"nameOrPath": "/p/A", "time": 1.5, "output": "/srv/f.png"}),
        )
        .unwrap();
        assert_eq!(out.as_str().unwrap(), "Frame tersimpan di: /srv/f.png");
    }

    #[test]
    fn preview_frame_png_written_to_preview_dir() {
        // "iVBORw0KGgo=" = 8 byte signature PNG (89 50 4E 47 0D 0A 1A 0A).
        let dir = preview_tmpdir();
        let obj = json!({"png": "iVBORw0KGgo=", "width": 640, "height": 360});
        let out = dispatch_mock_dir(
            vec![reply_conn(obj)],
            "previewFrame",
            json!({"nameOrPath": "/p/A", "time": 2.0}),
            &dir,
        )
        .unwrap();
        let s = out.as_str().unwrap();
        assert!(s.starts_with(&format!("Frame tersimpan di: {dir}/concat-preview-")), "{s}");
        assert!(s.ends_with(" (640x360)"), "{s}");
        let path = s
            .trim_start_matches("Frame tersimpan di: ")
            .trim_end_matches(" (640x360)");
        let bytes = std::fs::read(path).unwrap();
        assert_eq!(bytes, vec![0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn preview_frame_errors() {
        // PNG kosong.
        let e = dispatch_mock(
            vec![reply_conn(json!({"png": ""}))],
            "previewFrame",
            json!({"nameOrPath": "/p/A", "time": 1.0}),
        )
        .unwrap_err();
        assert_eq!(e.message(), "Concat tidak mengirim gambar frame — coba lagi.");
        // Base64 rusak.
        let e = dispatch_mock(
            vec![reply_conn(json!({"png": "!!!"}))],
            "previewFrame",
            json!({"nameOrPath": "/p/A", "time": 1.0}),
        )
        .unwrap_err();
        assert_eq!(
            e.message(),
            "Gambar frame dari Concat tidak bisa dibaca — coba lagi."
        );
        // Baris terlalu besar -> pesan khusus frame (bukan "terlalu besar" mentah).
        let mut c = auth_ok_conn();
        c.feed(&"a".repeat(MAX_LINE_BYTES + 10));
        c.feed("\n");
        let e = dispatch_mock(
            vec![c],
            "previewFrame",
            json!({"nameOrPath": "/p/A", "time": 1.0}),
        )
        .unwrap_err();
        assert!(
            e.message().contains("Gambar frame terlalu besar"),
            "{}",
            e.message()
        );
        // Folder tak bisa dibuat (previewDir = file biasa).
        let dir = preview_tmpdir();
        std::fs::write(&dir, b"x").unwrap();
        let obj = json!({"png": "iVBORw0KGgo="});
        let e = dispatch_mock_dir(
            vec![reply_conn(obj)],
            "previewFrame",
            json!({"nameOrPath": "/p/A", "time": 1.0}),
            &dir,
        )
        .unwrap_err();
        assert!(e.message().contains("tidak bisa ditulis ke"), "{}", e.message());
        let _ = std::fs::remove_file(&dir);
    }

    // ---- dispatch ----

    #[test]
    fn dispatch_unknown_op_is_api_error() {
        let e = dispatch_mock(vec![], "bukanOp", json!({})).unwrap_err();
        assert_eq!(e.kind(), "api");
        assert!(e.message().contains("tidak dikenal"), "{}", e.message());
    }

    #[test]
    fn edit_op_table_has_58_entries() {
        assert_eq!(EDIT_OP_REQUIRED.len(), 58);
        assert_eq!(
            EDIT_OP_REQUIRED.iter().find(|(n, _)| *n == "batch").unwrap().1,
            &["commands"]
        );
        assert_eq!(
            EDIT_OP_REQUIRED.iter().find(|(n, _)| *n == "addTrack").unwrap().1,
            &[] as &[&str]
        );
        assert!(EDIT_OP_REQUIRED.iter().find(|(n, _)| *n == "matchClip").is_none());
    }

    // ---- format angka (dibuktikan probe JVM) ----

    #[test]
    fn java_format_2f_half_up_not_half_even() {
        assert_eq!(java_format_2f(0.125), "0.13");
        assert_eq!(java_format_2f(2.675), "2.68");
        assert_eq!(java_format_2f(24000.0 / 1001.0), "23.98");
        assert_eq!(java_format_2f(29.97), "29.97");
        assert_eq!(java_format_2f(30.0), "30.00");
        assert_eq!(java_format_2f(0.004), "0.00");
        assert_eq!(java_format_2f(0.005), "0.01");
        assert_eq!(java_format_2f(1.995), "2.00");
        assert_eq!(java_format_2f(99.999), "100.00");
        assert_eq!(java_format_2f(0.0), "0.00");
    }

    #[test]
    fn java_double_string_matches_java() {
        assert_eq!(java_double_string(12.0), "12.0");
        assert_eq!(java_double_string(12.5), "12.5");
        assert_eq!(java_double_string(0.3), "0.3");
        assert_eq!(java_double_string(1e16), "1.0E16");
        assert_eq!(java_double_string(0.0001), "1.0E-4");
        assert_eq!(java_double_string(-0.0), "-0.0");
        assert_eq!(java_double_string(12345678.0), "1.2345678E7");
        assert_eq!(java_double_string(59.9), "59.9");
        assert_eq!(java_double_string(f64::NAN), "NaN");
        assert_eq!(java_double_string(f64::INFINITY), "Infinity");
    }

    #[test]
    fn format_rate_cases() {
        assert_eq!(format_rate(30, 1), "30 fps");
        assert_eq!(format_rate(24000, 1001), "23.98 fps");
        assert_eq!(format_rate(1, 8), "0.13 fps");
        assert_eq!(format_rate(2997, 100), "29.97 fps");
        assert_eq!(format_rate(0, 1), "");
        assert_eq!(format_rate(30, 0), "");
        assert_eq!(format_rate(-5, 1), "");
    }

    #[test]
    fn format_duration_cases() {
        assert_eq!(format_duration(12.5), "12.5 dtk");
        assert_eq!(format_duration(12.0), "12 dtk");
        assert_eq!(format_duration(0.0), "0 dtk");
        assert_eq!(format_duration(150.0), "2:30");
        assert_eq!(format_duration(59.9), "59.9 dtk");
        assert_eq!(format_duration(60.0), "1:00");
        assert_eq!(format_duration(268.0), "4:28");
        assert_eq!(format_duration(-5.0), "0 dtk");
        assert_eq!(format_duration(f64::NAN), "0 dtk");
        assert_eq!(format_duration(0.25), "0.3 dtk");
    }

    // ---- koersi org.json (dibuktikan probe JVM) ----

    #[test]
    fn opt_coercions_match_org_json() {
        let o = json!({"i": 12, "d": 12.9, "s": "12", "s2": "12.5", "s3": "abc",
            "b": true, "n": null, "big": 3000000000i64, "neg": -3.7});
        assert_eq!(opt_int(&o, "i", 0), 12);
        assert_eq!(opt_int(&o, "d", 0), 12);
        assert_eq!(opt_int(&o, "s", 0), 12);
        assert_eq!(opt_int(&o, "s2", 0), 12);
        assert_eq!(opt_int(&o, "s3", 0), 0);
        assert_eq!(opt_int(&o, "b", 0), 0);
        assert_eq!(opt_int(&o, "n", 0), 0);
        assert_eq!(opt_int(&o, "miss", 0), 0);
        assert_eq!(opt_int(&o, "big", 0), -1294967296);
        assert_eq!(opt_int(&o, "neg", 0), -3);
        assert_eq!(opt_long(&o, "big", 0), 3000000000);
        assert_eq!(opt_long(&o, "d", 0), 12);
        assert_eq!(opt_double(&o, "s", 0.0), 12.0);
        assert_eq!(opt_double(&o, "s3", 0.0), 0.0);
        assert_eq!(opt_string(&o, "i", ""), "12");
        assert_eq!(opt_string(&o, "d", ""), "12.9");
        assert_eq!(opt_string(&o, "b", ""), "true");
        assert_eq!(opt_string(&o, "n", ""), "");
        assert_eq!(opt_bool(&json!({"x": "True"}), "x", false), true);
        assert_eq!(opt_bool(&json!({"x": 1}), "x", false), false);
        assert!(has(&o, "n"));
        assert!(!has(&o, "miss"));
        // Angka double bulat di posisi string -> polos ("12345678";
        // org.json literal "12345678.0" memberi "12345678.0" — skala
        // literal tak bisa dipulihkan dari f64, deviasi tercatat).
        assert_eq!(opt_string(&json!({"x": 12345678.0}), "x", ""), "12345678");
        assert_eq!(opt_string(&json!({"x": 12.5}), "x", ""), "12.5");
    }

    // ---- UTF-16 / cap / Base64 ----

    #[test]
    fn utf16_take_drops_dangling_high_surrogate() {
        let s = format!("{}{}", "x".repeat(79), "\u{1F600}");
        assert_eq!(utf16_len(&s), 81);
        assert_eq!(utf16_len(&utf16_take(&s, 80)), 79);
        assert_eq!(utf16_take(&s, 81), s);
        assert_eq!(utf16_take("abc", 2), "ab");
    }

    #[test]
    fn cap_summary_uses_utf16_units() {
        let long = "x".repeat(2001);
        let capped = cap_summary(&long);
        assert_eq!(utf16_len(&capped), 2000);
        assert!(capped.ends_with('…'));
        assert_eq!(cap_summary(&"y".repeat(2000)).len(), 2000);
    }

    #[test]
    fn kotlin_trim_nbsp_is_blank() {
        assert!(is_blank("\u{a0}"));
        assert!(is_blank(""));
        assert!(!is_blank("a\u{a0}b"));
        assert_eq!(kotlin_trim("\u{a0}teks\u{a0}"), "teks");
    }

    #[test]
    fn base64_java_vectors() {
        assert_eq!(java_base64_decode("aGVsbG8=").unwrap(), b"hello");
        assert_eq!(java_base64_decode("aGVsbG8").unwrap(), b"hello");
        assert_eq!(java_base64_decode("aGk").unwrap(), b"hi");
        assert_eq!(java_base64_decode("").unwrap(), b"");
        assert_eq!(java_base64_decode("AB").unwrap(), vec![0]);
        assert_eq!(java_base64_decode("AR").unwrap(), vec![1]);
        assert_eq!(java_base64_decode("YQ==").unwrap(), b"a");
        assert_eq!(java_base64_decode("YQ").unwrap(), b"a");
        assert_eq!(java_base64_decode("QUJD").unwrap(), b"ABC");
        assert_eq!(java_base64_decode("ABCDEF==").unwrap(), vec![0, 16, 131, 16]);
        assert!(java_base64_decode("aGVs\nbG8=").is_err());
        assert!(java_base64_decode("aGVsbG8!").is_err());
        assert!(java_base64_decode("a").is_err());
        assert!(java_base64_decode("aGk==").is_err());
        assert!(java_base64_decode("YQ=").is_err());
        assert!(java_base64_decode("ABCDE").is_err());
        assert!(java_base64_decode("YQ==YQ==").is_err());
        assert!(java_base64_decode("YWJjZA=").is_err());
    }
}
