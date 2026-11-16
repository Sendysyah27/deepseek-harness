//! Port daun murni `Tools.kt` slice 10 (vc326) — parse DuckDuckGo
//! (`webSearch`), parse YouTube (`searchYoutube` + klaster
//! `yt*`/`extractBalancedJson`), plus dua helper murni rantai web:
//! `stripHtml` (dipakai `webFetch`/`webSearch`) dan
//! `truncateMarkedVc230` (dipakai `webFetch` + cap hasil tool).
//!
//! Satu seam JSON in/out (`tools_leaves_json`, op dispatch).
//! Yang SENGAJA TIDAK di sini (tetap Kotlin, BUILD-VC326.md §2):
//! dispatcher `Tools.execute` + SEMUA daun Android (SMS, kalender,
//! call log, lokasi, baterai, clipboard, UsageStats, AlarmClock,
//! `deviceInfo`, `lastLocation`), daun ctx/DB/file (skill, sub-room,
//! briefing, rote, artifact, sync, terminal, storage), daun jaringan
//! (`httpGet`/`httpGetStatus`/`httpRequest` + bagian jaringan
//! `webFetch`/`webSearch`/`searchYoutube` — HTTP tetap Kotlin),
//! dan daun murni LAIN yang peta TIDAK tugaskan ke slice 10
//! (`slugifySkillName`, `createSkillTruncateGuardVc311`, `ruleKey`,
//! `factProposalForMemoryTool`, `parseDateRange`, `dateRangeLabel`,
//! `sha256Hex8Vc301`, `fmtDateVc300`, `judgeInfoVc300`,
//! `skillLabelVc300`, `describeJsonValueVc272`,
//! `typeMatchesVc272`, `guardKeyForVc302`, `isRisky`,
//! `isMemoryWrite`, `fmt`, `deriveKey`).
//! DSML (peta §2.5) TIDAK di-port ulang di sini: stripper DSML
//! sudah Rust sejak slice 6 (`agent_prompt.rs` —
//! `normalizeDsmlDelimiters`, `stripRawToolMarkupForDisplay`);
//! di `Tools.kt` sendiri TIDAK ADA fungsi DSML.
//!
//! Pemetaan perilaku Kotlin -> Rust (quirk DIREPLIKASI):
//! - `Regex("\\s+")` Java = ASCII `[ \t\n\x0B\f\r]` saja —
//!   TERBUKTI probe JVM (kotlinc + JDK 21, /tmp/probe326):
//!   NBSP U+00A0 dan EM SPACE U+2003 di TENGAH TIDAK collapse
//!   (kode 160/8195 dipertahankan); `trim()` = Java isWhitespace
//!   (NBSP tepi DIBUANG — `kotlin_trim` slice 4). Rust `\s`
//!   (Unicode White_Space) akan menyimpang, jadi dipakai kelas
//!   eksplisit `[ \t\n\x0B\f\r]+` (pola `ds_safety_strip.rs`).
//! - Regex DDG `class="result__a"[^>]*href="([^"]+)"[^>]*>
//!   ([\s\S]*?)</a>` — `findAll` kiri-ke-kanan non-overlapping,
//!   `take(6)`; `[\s\S]` Rust = sembarang char (seperti Java).
//! - `JSONObject.optString` melakukan koersi (angka/bool -> teks
//!   kompak, hilang/null -> default/`""`) — `opt_string_coerce`
//!   slice 8; `optJSONObject`/`optJSONArray` null bila bukan
//!   objek/array; `has(k)` TRUE walau nilai JSON null (probe JVM:
//!   `HAS_NULL=true`, `getJSONObject(null)` THROW -> jalur
//!   `catch` Kotlin = lewati item — Rust: kunci ada tapi bukan
//!   objek -> lewati, hasil sama).
//! - `String.length`/`take(n)` Kotlin = satuan UTF-16 (astral =
//!   2 unit); potongan tepat di tengah pasangan surrogate ->
//!   lone high-surrogate di string JVM (probe JVM:
//!   `truncateMarkedVc230("a😀b", 2)` -> len 27, kode 97,55357,
//!   `toByteArray` UTF-8 = `61 3F`, `JSONObject.toString()` =
//!   `{"v":"a?\u2026..."}`). Seam mereplikanya dengan menulis
//!   escape JSON `\uXXXX` mentah (pola `slice_preview` slice 9)
//!   supaya org.json mem-parse-nya kembali jadi lone surrogate.
//! - `String.isBlank()`/`ifBlank` = semua char Java-isWhitespace
//!   (`is_blank` slice 5); `contains(x, ignoreCase=true)` /
//!   `lowercase()` untuk needle ASCII (`"live"`, `"short"`) =
//!   lowercase Unicode Rust (identik untuk needle ini; locale
//!   default perangkat id-ID/en tidak memetakan huruf ASCII
//!   berbeda — dicatat, bukan diasumsikan).
//! - `extractBalancedJson`/`ytScanRenderers`/`parseYtSearch`:
//!   `indexOf` + `substring` Kotlin = indeks UTF-16 —
//!   implementasi di sini bekerja atas unit UTF-16 (`Vec<u16>`),
//!   bukan byte/char Rust.
//! - `formatYtResults`: `maxResults.coerceIn(1, 10)` di Rust;
//!   label `[Shorts]` bila `meta.lowercase()` mengandung
//!   `"short"`; format `"N. title[Shorts]\n   Channel: …"` +
//!   `.trim()` akhir.
//!
//! TANPA dependensi Cargo baru: `regex` + `serde_json` sudah ada
//! (dipakai sejak slice 1/3).

#![forbid(unsafe_code)]

use crate::concat_api_client::is_blank;
use crate::ds_safety_strip::kotlin_trim;
use crate::http::opt_string_coerce;
use serde_json::{json, Value as Json};
use std::collections::HashSet;
use std::sync::OnceLock;

/// Satu entry point seam: request JSON -> respons JSON (String).
/// Error op dikembalikan sebagai `{"ok":false,"error":...}` — BUKAN
/// `Err` — supaya boundary JNI mengembalikan `null` HANYA bila
/// panic/error FFI (pola `http_json` vc324 §7.1 / `worker_endpoint`).
pub fn tools_leaves_json(req_str: &str) -> Result<String, String> {
    Ok(dispatch(req_str).unwrap_or_else(|e| err_json(&e)))
}

fn err_json(msg: &str) -> String {
    json!({"ok": false, "error": msg}).to_string()
}

fn req_str_field(req: &Json, key: &str) -> Result<String, String> {
    req.get(key)
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .ok_or_else(|| format!("missing/invalid {key}"))
}

fn req_i64_field(req: &Json, key: &str) -> Result<i64, String> {
    match req.get(key) {
        None | Some(Json::Null) => Err(format!("missing {key}")),
        Some(Json::Number(n)) => n
            .as_i64()
            .ok_or_else(|| format!("{key} out of range")),
        Some(_) => Err(format!("{key} must be a number")),
    }
}

/// `JSONObject.optString(key, "")`: hilang/null -> `""`, string ->
/// diri sendiri, angka/bool/array/objek -> `toString()` kompak.
fn opt_str(v: &Json, key: &str) -> String {
    opt_string_coerce(v.get(key))
}

/// `contains(x, ignoreCase = true)` untuk needle ASCII lowercase.
fn contains_ignore_case(hay: &str, needle: &str) -> bool {
    hay.to_lowercase().contains(needle)
}

// ------------------------------------------------------------ regex ---

fn script_re() -> &'static Option<regex::Regex> {
    static R: OnceLock<Option<regex::Regex>> = OnceLock::new();
    R.get_or_init(|| regex::Regex::new(r"(?i)<script[\s\S]*?</script>").ok())
}

fn style_re() -> &'static Option<regex::Regex> {
    static R: OnceLock<Option<regex::Regex>> = OnceLock::new();
    R.get_or_init(|| regex::Regex::new(r"(?i)<style[\s\S]*?</style>").ok())
}

fn tag_re() -> &'static Option<regex::Regex> {
    static R: OnceLock<Option<regex::Regex>> = OnceLock::new();
    R.get_or_init(|| regex::Regex::new(r"<[^>]+>").ok())
}

/// `\s+` Java (ASCII SAJA — probe JVM: NBSP/EM SPACE tengah TIDAK
/// collapse). JANGAN pakai `\s` Rust (Unicode White_Space).
fn ws_ascii_re() -> &'static Option<regex::Regex> {
    static R: OnceLock<Option<regex::Regex>> = OnceLock::new();
    R.get_or_init(|| regex::Regex::new(r"[ \t\n\x0B\f\r]+").ok())
}

fn ddg_re() -> &'static Option<regex::Regex> {
    static R: OnceLock<Option<regex::Regex>> = OnceLock::new();
    R.get_or_init(|| {
        regex::Regex::new(r#"class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>"#).ok()
    })
}

// --------------------------------------------------------- UTF-16 ----

/// Panjang string dalam unit UTF-16 (`String.length` Kotlin).
fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

/// `String.take(n)` Kotlin atas unit UTF-16. Bila potongan membelah
/// pasangan surrogate, kembalikan head + high-surrogate yang
/// menggantung (pola `utf16_take` `worker_endpoint.rs` slice 9 —
/// placeholder U+FFFD di akhir head, dilaporkan via `Some(high)`).
fn utf16_take_split(s: &str, max: usize) -> (String, Option<u16>) {
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

/// Cari `needle` (unit UTF-16) di `hay` mulai `from` (indeks UTF-16).
/// Ekuivalen `String.indexOf(String, fromIndex)` Kotlin.
fn index_of_units(hay: &[u16], needle: &[u16], from: usize) -> Option<usize> {
    if needle.is_empty() || hay.len() < needle.len() {
        return None;
    }
    let start = from.min(hay.len());
    if start + needle.len() > hay.len() {
        return None;
    }
    (start..=(hay.len() - needle.len()))
        .find(|&i| hay[i..i + needle.len()] == *needle)
}

/// `extractBalancedJson` atas unit UTF-16 (Kotlin `text[i]` +
/// `substring(startBrace, i + 1)` = indeks UTF-16).
// Konstanta pola (literal `b'x' as u16` TIDAK BOLEH di pola `match`).
const DQ_U16: u16 = b'"' as u16;
const LB_U16: u16 = b'{' as u16;
const RB_U16: u16 = b'}' as u16;
const BS_U16: u16 = b'\\' as u16;

fn extract_balanced_units(units: &[u16], start_brace: usize) -> Option<String> {
    if start_brace >= units.len() {
        return None;
    }
    let mut depth: i64 = 0;
    let mut in_str = false;
    let mut esc = false;
    for i in start_brace..units.len() {
        let c = units[i];
        if in_str {
            if esc {
                esc = false;
            } else if c == BS_U16 {
                esc = true;
            } else if c == DQ_U16 {
                in_str = false;
            }
            continue;
        }
        match c {
            DQ_U16 => in_str = true,
            LB_U16 => depth += 1,
            RB_U16 => {
                depth -= 1;
                if depth == 0 {
                    return Some(String::from_utf16_lossy(&units[start_brace..=i]));
                }
            }
            _ => {}
        }
    }
    None
}

// ------------------------------------------------------ daun murni ---

/// `Tools.stripHtml`.
pub fn strip_html(html: &str) -> String {
    let mut t = html.to_string();
    if let Some(re) = script_re() {
        t = re.replace_all(&t, " ").into_owned();
    }
    if let Some(re) = style_re() {
        t = re.replace_all(&t, " ").into_owned();
    }
    if let Some(re) = tag_re() {
        t = re.replace_all(&t, " ").into_owned();
    }
    // Urutan entitas PENTING (sama seperti Kotlin): &nbsp; dulu,
    // lalu &amp; — supaya `&amp;nbsp;` jadi `&nbsp;` (bukan spasi).
    t = t
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"");
    if let Some(re) = ws_ascii_re() {
        t = re.replace_all(&t, " ").into_owned();
    }
    kotlin_trim(&t).to_string()
}

/// Parse hasil DuckDuckGo — badan `Tools.webSearch` sesudah `httpGet`.
/// Kembalikan string hasil LENGKAP (format `"- judul\n  url"`, maks 6).
pub fn parse_ddg(html: &str, query: &str) -> String {
    let mut results: Vec<String> = Vec::new();
    if let Some(re) = ddg_re() {
        for cap in re.captures_iter(html).take(6) {
            let href = cap.get(1).map(|m| m.as_str()).unwrap_or("");
            let title = strip_html(cap.get(2).map(|m| m.as_str()).unwrap_or(""));
            results.push(format!("- {title}\n  {href}"));
        }
    }
    if results.is_empty() {
        format!("Tidak ada hasil terbaca untuk '{query}' (coba web_fetch langsung ke situsnya).")
    } else {
        results.join("\n")
    }
}

/// `Tools.truncateMarkedVc230`: (hasil, terpotong?, high-surrogate
/// bila potongan membelah pasangan surrogate).
pub fn truncate_marked(text: &str, cap: i64, result_id: &str) -> (String, bool, Option<u16>) {
    if cap < 0 {
        return (String::new(), true, None);
    }
    let cap = cap as usize;
    let len = utf16_len(text);
    if len <= cap {
        return (text.to_string(), false, None);
    }
    let (head, split) = utf16_take_split(text, cap);
    let rest = len - cap;
    let id_part = if !is_blank(result_id) {
        format!(", pakai get_result[{result_id}] untuk sisanya")
    } else {
        String::new()
    };
    (
        format!("{head}…(dipotong, sisa {rest} char{id_part})…"),
        true,
        split,
    )
}

// ------------------------------------------------------- YouTube ------

/// Cermin `Tools.YtHit` (private di Kotlin — seam mengembalikan JSON).
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct YtHit {
    pub id: String,
    pub title: String,
    pub channel: String,
    pub meta: String,
}

fn yt_hit_json(h: &YtHit) -> Json {
    json!({"id": h.id, "title": h.title, "channel": h.channel, "meta": h.meta})
}

fn yt_hit_from_json(v: &Json) -> Option<YtHit> {
    let _ = v.as_object()?;
    Some(YtHit {
        id: opt_str(v, "id"),
        title: opt_str(v, "title"),
        channel: opt_str(v, "channel"),
        meta: opt_str(v, "meta"),
    })
}

/// `Tools.ytRunsText`.
pub fn yt_runs_text(o: Option<&Json>) -> String {
    let Some(obj) = o else {
        return String::new();
    };
    let Some(runs) = obj.get("runs").and_then(|v| v.as_array()) else {
        return opt_str(obj, "simpleText");
    };
    let mut sb = String::new();
    for r in runs {
        if let Some(ro) = r.as_object() {
            sb.push_str(&opt_str(&Json::Object(ro.clone()), "text"));
        }
    }
    let joined = kotlin_trim(&sb);
    if !joined.is_empty() {
        joined.to_string()
    } else {
        opt_str(obj, "simpleText")
    }
}

/// `Tools.ytParseVideoRenderer`.
pub fn yt_parse_video_renderer(vr: &Json, force_meta: Option<&str>) -> Option<YtHit> {
    let id = opt_str(vr, "videoId");
    if is_blank(&id) {
        return None;
    }
    let title = {
        let t = yt_runs_text(vr.get("title"));
        if is_blank(&t) {
            "-".to_string()
        } else {
            t
        }
    };
    let mut channel = yt_runs_text(vr.get("longBylineText"));
    if is_blank(&channel) {
        channel = yt_runs_text(vr.get("ownerText"));
    }
    if is_blank(&channel) {
        channel = "-".to_string();
    }
    if let Some(fm) = force_meta {
        return Some(YtHit {
            id,
            title,
            channel,
            meta: fm.to_string(),
        });
    }
    let mut meta = String::new();
    if let Some(badges) = vr.get("badges").and_then(|v| v.as_array()) {
        for b in badges {
            let label = b
                .get("metadataBadgeRenderer")
                .map(|m| opt_str(m, "label"))
                .unwrap_or_default();
            if contains_ignore_case(&label, "live") {
                meta = "Live".to_string();
                break;
            }
        }
    }
    if meta.is_empty() {
        let len = vr
            .get("lengthText")
            .map(|m| opt_str(m, "simpleText"))
            .unwrap_or_default();
        meta = if !len.is_empty() {
            format!("Durasi: {len}")
        } else {
            "Durasi: tidak diketahui".to_string()
        };
    }
    Some(YtHit {
        id,
        title,
        channel,
        meta,
    })
}

/// `Tools.extractBalancedJson` (indeks UTF-16).
pub fn extract_balanced_json(text: &str, start_brace: i64) -> Option<String> {
    if start_brace < 0 {
        return None;
    }
    let units: Vec<u16> = text.encode_utf16().collect();
    extract_balanced_units(&units, start_brace as usize)
}

/// `Tools.ytWalkResults`.
pub fn yt_walk_results(root: &Json) -> Vec<YtHit> {
    let mut out: Vec<YtHit> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    let contents = root
        .get("contents")
        .and_then(|v| v.get("twoColumnSearchResultsRenderer"))
        .and_then(|v| v.get("primaryContents"))
        .and_then(|v| v.get("sectionListRenderer"))
        .and_then(|v| v.get("contents"))
        .and_then(|v| v.as_array());
    let Some(contents) = contents else {
        return out;
    };
    for section_v in contents {
        let Some(items) = section_v
            .get("itemSectionRenderer")
            .and_then(|v| v.get("contents"))
            .and_then(|v| v.as_array())
        else {
            continue;
        };
        for item in items {
            let Some(io) = item.as_object() else {
                continue;
            };
            // `has(k)` Kotlin TRUE walau nilai JSON null (probe JVM)
            // — tapi `getJSONObject` lalu THROW -> catch -> lewati.
            // Rust: kunci ada tapi bukan objek -> lewati (hasil sama).
            if io.contains_key("videoRenderer") {
                if let Some(vr) = io.get("videoRenderer") {
                    if let Some(h) = yt_parse_video_renderer(vr, None) {
                        if seen.insert(h.id.clone()) {
                            out.push(h);
                        }
                    }
                }
            } else if io.contains_key("reelItemRenderer") {
                if let Some(rr) = io.get("reelItemRenderer") {
                    let id = opt_str(rr, "videoId");
                    if !is_blank(&id) {
                        let title = {
                            let t = yt_runs_text(rr.get("headline"));
                            if is_blank(&t) {
                                "-".to_string()
                            } else {
                                t
                            }
                        };
                        let mut ch = yt_runs_text(rr.get("longBylineText"));
                        if is_blank(&ch) {
                            ch = yt_runs_text(rr.get("shortBylineText"));
                        }
                        if is_blank(&ch) {
                            ch = "-".to_string();
                        }
                        let h = YtHit {
                            id,
                            title,
                            channel: ch,
                            meta: "Short".to_string(),
                        };
                        if seen.insert(h.id.clone()) {
                            out.push(h);
                        }
                    }
                }
            }
        }
    }
    out
}

/// `Tools.ytScanRenderers` (indeks UTF-16; maks 30 hit).
pub fn yt_scan_renderers(html: &str) -> Vec<YtHit> {
    let mut out: Vec<YtHit> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    let units: Vec<u16> = html.encode_utf16().collect();
    let needle: Vec<u16> = "\"videoRenderer\":{".encode_utf16().collect();
    let mut idx = 0usize;
    loop {
        let Some(k) = index_of_units(&units, &needle, idx) else {
            break;
        };
        let brace = units[k..]
            .iter()
            .position(|&c| c == b'{' as u16)
            .map(|p| k + p);
        if let Some(b) = brace {
            if let Some(js) = extract_balanced_units(&units, b) {
                if let Ok(v) = serde_json::from_str::<Json>(&js) {
                    if let Some(h) = yt_parse_video_renderer(&v, None) {
                        if seen.insert(h.id.clone()) {
                            out.push(h);
                        }
                    }
                }
            }
        }
        idx = match brace {
            Some(b) => b + 1,
            None => k + 1,
        };
        if out.len() >= 30 {
            break;
        }
    }
    out
}

/// `Tools.parseYtSearch`: jalan `ytInitialData` dulu, fallback scan.
pub fn parse_yt_search(html: &str) -> Vec<YtHit> {
    let units: Vec<u16> = html.encode_utf16().collect();
    let marker: Vec<u16> = "ytInitialData".encode_utf16().collect();
    if let Some(m) = index_of_units(&units, &marker, 0) {
        if let Some(b) = units[m..]
            .iter()
            .position(|&c| c == b'{' as u16)
            .map(|p| m + p)
        {
            if let Some(js) = extract_balanced_units(&units, b) {
                if let Ok(root) = serde_json::from_str::<Json>(&js) {
                    let walked = yt_walk_results(&root);
                    if !walked.is_empty() {
                        return walked;
                    }
                }
            }
        }
    }
    yt_scan_renderers(html)
}

/// Format hasil `searchYoutube` (ekor murni sesudah HTTP): nomor +
/// label `[Shorts]` + trim akhir. `max_results` di-`coerceIn(1, 10)`
/// di sini (seperti Kotlin).
pub fn format_yt_results(hits: &[YtHit], max_results: i64) -> String {
    let limit = max_results.clamp(1, 10) as usize;
    let mut sb = String::new();
    for (i, r) in hits.iter().take(limit).enumerate() {
        let shorts = if r.meta.to_lowercase().contains("short") {
            " [Shorts]"
        } else {
            ""
        };
        sb.push_str(&format!(
            "{}. {}{}\n   Channel: {} | {}\n   https://www.youtube.com/watch?v={}\n",
            i + 1,
            r.title,
            shorts,
            r.channel,
            r.meta,
            r.id
        ));
    }
    kotlin_trim(&sb).to_string()
}

// ---------------------------------------------------------- dispatch --

fn hits_json(hits: &[YtHit]) -> Json {
    Json::Array(hits.iter().map(yt_hit_json).collect())
}

/// Escape JSON string tanpa tanda kutip luar (untuk rakitan manual
/// kasus lone-surrogate — pola `slice_preview` slice 9).
fn json_inner(s: &str) -> Result<String, String> {
    let q = serde_json::to_string(s).map_err(|_| "json encode gagal".to_string())?;
    Ok(q[1..q.len() - 1].to_string())
}

fn dispatch(req_str: &str) -> Result<String, String> {
    let req: Json =
        serde_json::from_str(req_str).map_err(|_| "bad request json".to_string())?;
    let op = req
        .get("op")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing op".to_string())?;
    match op {
        "strip_html" => {
            let html = req_str_field(&req, "html")?;
            Ok(json!({"ok": true, "value": strip_html(&html)}).to_string())
        }
        "parse_ddg" => {
            let html = req_str_field(&req, "html")?;
            let query = req_str_field(&req, "query")?;
            Ok(json!({"ok": true, "value": parse_ddg(&html, &query)}).to_string())
        }
        "truncate_marked" => {
            let text = req_str_field(&req, "text")?;
            let cap = req_i64_field(&req, "cap")?;
            let result_id = req.get("result_id").and_then(|v| v.as_str()).unwrap_or("");
            if cap < 0 {
                return Err("bad cap".to_string());
            }
            let (value, truncated, split) = truncate_marked(&text, cap, result_id);
            Ok(match split {
                // Potongan membelah pasangan surrogate: tulis escape
                // JSON `\uXXXX` mentah (bukan via serde_json — Rust
                // String tidak bisa memegang lone surrogate) supaya
                // org.json mem-parse-nya kembali jadi lone surrogate
                // di string JVM — identik 1:1 dengan `take(n)` Kotlin
                // (probe JVM: wire `61 3F`). Rakit dari komponen:
                // head (tanpa placeholder U+FFFD) + escape + suffix.
                Some(high) => {
                    let (head_raw, _) = utf16_take_split(&text, cap as usize);
                    let mut head_esc = head_raw.clone();
                    head_esc.pop();
                    let head_inner = json_inner(&head_esc)?;
                    let len = utf16_len(&text);
                    let rest = len - cap as usize;
                    let id_part = if !is_blank(result_id) {
                        format!(", pakai get_result[{result_id}] untuk sisanya")
                    } else {
                        String::new()
                    };
                    let suffix = format!("…(dipotong, sisa {rest} char{id_part})…");
                    let suffix_inner = json_inner(&suffix)?;
                    format!(
                        "{{\"ok\": true, \"value\": \"{head_inner}\\u{high:04x}{suffix_inner}\", \"truncated\": {truncated}}}"
                    )
                }
                None => json!({"ok": true, "value": value, "truncated": truncated}).to_string(),
            })
        }
        "yt_runs_text" => {
            let raw = req.get("json");
            let parsed: Option<Json> = match raw {
                None | Some(Json::Null) => None,
                Some(Json::String(s)) => Some(
                    serde_json::from_str(s).map_err(|_| "bad json".to_string())?,
                ),
                Some(v) => Some(v.clone()),
            };
            Ok(json!({"ok": true, "value": yt_runs_text(parsed.as_ref())}).to_string())
        }
        "yt_parse_video_renderer" => {
            let raw = req
                .get("json")
                .ok_or_else(|| "missing json".to_string())?;
            let vr: Json = match raw {
                Json::String(s) => {
                    serde_json::from_str(s).map_err(|_| "bad json".to_string())?
                }
                v => v.clone(),
            };
            let force_meta = req.get("force_meta").and_then(|v| v.as_str());
            if req.get("force_meta").is_some_and(|v| !v.is_null() && !v.is_string()) {
                return Err("bad force_meta".to_string());
            }
            let hit = yt_parse_video_renderer(&vr, force_meta);
            Ok(match hit {
                Some(h) => json!({"ok": true, "hit": yt_hit_json(&h)}).to_string(),
                None => json!({"ok": true, "hit": null}).to_string(),
            })
        }
        "extract_balanced_json" => {
            let text = req_str_field(&req, "text")?;
            let start = req_i64_field(&req, "start")?;
            let out = extract_balanced_json(&text, start);
            Ok(match out {
                Some(s) => json!({"ok": true, "json": s}).to_string(),
                None => json!({"ok": true, "json": null}).to_string(),
            })
        }
        "yt_walk_results" => {
            let raw = req
                .get("json")
                .ok_or_else(|| "missing json".to_string())?;
            let root: Json = match raw {
                Json::String(s) => {
                    serde_json::from_str(s).map_err(|_| "bad json".to_string())?
                }
                v => v.clone(),
            };
            Ok(json!({"ok": true, "hits": hits_json(&yt_walk_results(&root))}).to_string())
        }
        "yt_scan_renderers" => {
            let html = req_str_field(&req, "html")?;
            Ok(json!({"ok": true, "hits": hits_json(&yt_scan_renderers(&html))}).to_string())
        }
        "parse_yt_search" => {
            let html = req_str_field(&req, "html")?;
            Ok(json!({"ok": true, "hits": hits_json(&parse_yt_search(&html))}).to_string())
        }
        "format_yt_results" => {
            let hits_v = req
                .get("hits")
                .and_then(|v| v.as_array())
                .ok_or_else(|| "missing hits".to_string())?;
            let mut hits: Vec<YtHit> = Vec::new();
            for v in hits_v {
                if let Some(h) = yt_hit_from_json(v) {
                    hits.push(h);
                }
            }
            let limit = req_i64_field(&req, "limit")?;
            Ok(json!({"ok": true, "value": format_yt_results(&hits, limit)}).to_string())
        }
        _ => Err(format!("unknown op {op}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn ep(op: &str, req: Json) -> Json {
        let mut m = req.as_object().unwrap().clone();
        m.insert("op".to_string(), Json::String(op.to_string()));
        let out = tools_leaves_json(&Json::Object(m).to_string()).expect("seam ok");
        serde_json::from_str(&out).expect("seam json ok")
    }

    #[test]
    fn strip_html_basic() {
        assert_eq!(
            strip_html("<p>Hello <b>dunia</b></p>"),
            "Hello dunia"
        );
        assert_eq!(strip_html("<script>var x=1;</script>teks"), "teks");
        assert_eq!(
            strip_html("<SCRIPT>alert(1)</SCRIPT>teks"),
            "teks"
        );
        assert_eq!(strip_html("<style>.a{color:red}</style>teks"), "teks");
        assert_eq!(
            strip_html("a&nbsp;b &amp; c &lt;d&gt; &quot;e&quot;"),
            "a b & c <d> \"e\""
        );
        assert_eq!(strip_html("a   b\n\tc"), "a b c");
        assert_eq!(strip_html(""), "");
        assert_eq!(strip_html("   "), "");
        // Tag tak-tutup: `<[^>]+>` tidak match -> dipertahankan, lalu trim.
        assert_eq!(strip_html("teks <p"), "teks <p");
        // Script tak-tutup: pola lazy butuh `</script>` -> tidak match.
        assert_eq!(strip_html("<script>var x"), "var x");
    }

    #[test]
    fn strip_html_nbsp_not_collapsed() {
        // Probe JVM (/tmp/probe326): NBSP/EM SPACE TENGAH dipertahankan
        // (`\s+` Java = ASCII); NBSP tepi DIBUANG trim() Java-isWhitespace.
        assert_eq!(strip_html("<p>a\u{a0}b</p>"), "a\u{a0}b");
        assert_eq!(strip_html("<p>\u{a0}teks\u{a0}</p>"), "teks");
        assert_eq!(strip_html("a\u{a0}\u{a0}b"), "a\u{a0}\u{a0}b");
        assert_eq!(strip_html("a\u{2003}b"), "a\u{2003}b");
        assert_eq!(strip_html("a\u{2003} b"), "a\u{2003} b");
    }

    #[test]
    fn strip_html_via_seam() {
        let got = ep("strip_html", json!({"html": "<p>x <b>y</b></p>"}));
        assert_eq!(got.get("value"), Some(&json!("x y")));
    }

    #[test]
    fn parse_ddg_basic() {
        let html = r#"<div><a class="result__a" href="https://contoh.id/a">Judul <b>Satu</b></a></div><a class="result__a" href="https://contoh.id/b">Dua</a>"#;
        assert_eq!(
            parse_ddg(html, "tes"),
            "- Judul Satu\n  https://contoh.id/a\n- Dua\n  https://contoh.id/b"
        );
        // Kosong -> pesan khas.
        assert_eq!(
            parse_ddg("<html>tidak ada hasil</html>", "zzz"),
            "Tidak ada hasil terbaca untuk 'zzz' (coba web_fetch langsung ke situsnya)."
        );
        // take(6): 7 link -> hanya 6.
        let many = (1..=7)
            .map(|i| format!(r#"<a class="result__a" href="https://x/{i}">T{i}</a>"#))
            .collect::<Vec<_>>()
            .join("");
        let got = parse_ddg(&many, "q");
        assert_eq!(got.lines().count(), 12);
        assert!(got.contains("https://x/6"));
        assert!(!got.contains("https://x/7"));
        // Atribut class sesudah href TIDAK match (urutan pola Kotlin).
        assert_eq!(
            parse_ddg(r#"<a href="https://x" class="result__a">T</a>"#, "q"),
            "Tidak ada hasil terbaca untuk 'q' (coba web_fetch langsung ke situsnya)."
        );
    }

    #[test]
    fn parse_ddg_via_seam() {
        let got = ep(
            "parse_ddg",
            json!({"html": r#"<a class="result__a" href="https://x">T</a>"#, "query": "q"}),
        );
        assert_eq!(got.get("value"), Some(&json!("- T\n  https://x")));
    }

    #[test]
    fn truncate_marked_basic() {
        assert_eq!(
            truncate_marked("hello", 10, "").0,
            "hello"
        );
        assert_eq!(truncate_marked("hello", 5, "").0, "hello");
        assert_eq!(
            truncate_marked("hello world", 5, "").0,
            "hello…(dipotong, sisa 6 char)…"
        );
        assert_eq!(
            truncate_marked("hello world", 5, "abc").0,
            "hello…(dipotong, sisa 6 char, pakai get_result[abc] untuk sisanya)…"
        );
        // resultId blank (spasi saja) -> tanpa idPart.
        assert_eq!(
            truncate_marked("hello world", 5, "   ").0,
            "hello…(dipotong, sisa 6 char)…"
        );
        // Kosong + cap 0.
        assert_eq!(truncate_marked("", 0, "").0, "");
        assert_eq!(truncate_marked("ab", 0, "").0, "…(dipotong, sisa 2 char)…");
    }

    #[test]
    fn truncate_marked_astral_escape() {
        // Probe JVM: take(2) "a😀b" = "a" + lone high surrogate,
        // wire UTF-8 `61 3F`; seam menulis `\ud83d` mentah.
        let raw = tools_leaves_json(
            "{\"op\":\"truncate_marked\",\"text\":\"a\u{1F600}b\",\"cap\":2,\"result_id\":\"\"}",
        )
        .expect("seam ok");
        assert!(
            raw.contains("\"value\": \"a\\ud83d…(dipotong, sisa 2 char)…\""),
            "raw={raw}"
        );
        assert!(raw.contains("\"truncated\": true"), "raw={raw}");
        // Tanpa belah: utuh.
        let got = ep(
            "truncate_marked",
            json!({"text": "a\u{1F600}b", "cap": 4, "result_id": ""}),
        );
        assert_eq!(got.get("value"), Some(&json!("a\u{1F600}b")));
        assert_eq!(got.get("truncated"), Some(&json!(false)));
    }

    #[test]
    fn yt_runs_text_cases() {
        assert_eq!(yt_runs_text(None), "");
        let o = json!({"simpleText": "Langsung"});
        assert_eq!(yt_runs_text(Some(&o)), "Langsung");
        let o = json!({"runs": [{"text": "A"}, {"text": "B"}, {"text": "C"}]});
        assert_eq!(yt_runs_text(Some(&o)), "ABC");
        // runs kosong -> fallback simpleText; runs tanpa simpleText -> "".
        let o = json!({"runs": []});
        assert_eq!(yt_runs_text(Some(&o)), "");
        let o = json!({"runs": [{"text": "  "}, {"text": "x"}]});
        assert_eq!(yt_runs_text(Some(&o)), "x");
        // Elemen bukan objek -> dilewati (optJSONObject null).
        let o = json!({"runs": ["teks", {"text": "Y"}]});
        assert_eq!(yt_runs_text(Some(&o)), "Y");
        // Koersi optString: angka -> "5".
        let o = json!({"runs": [{"text": 5}]});
        assert_eq!(yt_runs_text(Some(&o)), "5");
        // Via seam.
        let got = ep(
            "yt_runs_text",
            json!({"json": "{\"runs\":[{\"text\":\"A\"},{\"text\":\"B\"}]}"}),
        );
        assert_eq!(got.get("value"), Some(&json!("AB")));
    }

    #[test]
    fn yt_parse_video_renderer_cases() {
        // videoId kosong -> None.
        let vr = json!({"title": {"simpleText": "T"}});
        assert_eq!(yt_parse_video_renderer(&vr, None), None);
        // Jalur normal: title runs + longBylineText + lengthText.
        let vr = json!({
            "videoId": "abc123",
            "title": {"runs": [{"text": "Judul"}]},
            "longBylineText": {"simpleText": "Kanal"},
            "lengthText": {"simpleText": "10:23"}
        });
        assert_eq!(
            yt_parse_video_renderer(&vr, None),
            Some(YtHit {
                id: "abc123".into(),
                title: "Judul".into(),
                channel: "Kanal".into(),
                meta: "Durasi: 10:23".into()
            })
        );
        // ownerText fallback + tanpa lengthText.
        let vr = json!({
            "videoId": "v2",
            "title": {"simpleText": "T2"},
            "ownerText": {"simpleText": "Pemilik"}
        });
        assert_eq!(
            yt_parse_video_renderer(&vr, None).unwrap().meta,
            "Durasi: tidak diketahui"
        );
        assert_eq!(
            yt_parse_video_renderer(&vr, None).unwrap().channel,
            "Pemilik"
        );
        // title blank -> "-"; channel blank -> "-".
        let vr = json!({"videoId": "v3"});
        let h = yt_parse_video_renderer(&vr, None).unwrap();
        assert_eq!((h.title.as_str(), h.channel.as_str()), ("-", "-"));
        // Badge Live menang atas lengthText.
        let vr = json!({
            "videoId": "v4",
            "title": {"simpleText": "T"},
            "badges": [{"metadataBadgeRenderer": {"label": "LIVE"}}],
            "lengthText": {"simpleText": "1:00"}
        });
        assert_eq!(
            yt_parse_video_renderer(&vr, None).unwrap().meta,
            "Live"
        );
        // Badge bukan live -> lengthText.
        let vr = json!({
            "videoId": "v5",
            "title": {"simpleText": "T"},
            "badges": [{"metadataBadgeRenderer": {"label": "Baru"}}],
            "lengthText": {"simpleText": "2:00"}
        });
        assert_eq!(
            yt_parse_video_renderer(&vr, None).unwrap().meta,
            "Durasi: 2:00"
        );
        // forceMeta menang (dipakai videoRenderer path utama).
        let vr = json!({"videoId": "v6", "title": {"simpleText": "T"}});
        assert_eq!(
            yt_parse_video_renderer(&vr, Some("Short")).unwrap().meta,
            "Short"
        );
        // Via seam: hit null bila videoId kosong.
        let got = ep(
            "yt_parse_video_renderer",
            json!({"json": "{\"title\":{\"simpleText\":\"T\"}}"}),
        );
        assert_eq!(got.get("hit"), Some(&Json::Null));
    }

    #[test]
    fn extract_balanced_json_cases() {
        assert_eq!(
            extract_balanced_json(r#"xx {"a": {"b": 1}, "c": [1,2]} yy"#, 3),
            Some(r#"{"a": {"b": 1}, "c": [1,2]}"#.to_string())
        );
        // Kurung di dalam string diabaikan (nilai `" } {" }`? bukan —
        // nilai string = `} {`, lalu ` }` menutup objek: hasil =
        // SELURUH input).
        assert_eq!(
            extract_balanced_json("{\"a\": \"} {\" }", 0),
            Some("{\"a\": \"} {\" }".to_string())
        );
        // Escape di dalam string.
        assert_eq!(
            extract_balanced_json(r#"{"a": "x\"y"} tail"#, 0),
            Some(r#"{"a": "x\"y"}"#.to_string())
        );
        // Tak-berakhir -> None; start negatif -> None; start di luar -> None.
        assert_eq!(extract_balanced_json(r#"{"a": 1"#, 0), None);
        assert_eq!(extract_balanced_json(r#"{"a": 1}"#, -1), None);
        assert_eq!(extract_balanced_json(r#"{"a": 1}"#, 99), None);
        // Via seam.
        let got = ep(
            "extract_balanced_json",
            json!({"text": "xx {\"a\":1} yy", "start": 3}),
        );
        assert_eq!(got.get("json"), Some(&json!("{\"a\":1}")));
    }

    fn walk_root() -> Json {
        json!({
            "contents": {"twoColumnSearchResultsRenderer": {"primaryContents": {"sectionListRenderer": {"contents": [
                {"itemSectionRenderer": {"contents": [
                    {"videoRenderer": {"videoId": "v1", "title": {"simpleText": "Satu"}, "longBylineText": {"simpleText": "K1"}, "lengthText": {"simpleText": "3:00"}}},
                    {"reelItemRenderer": {"videoId": "s1", "headline": {"simpleText": "Short Satu"}, "shortBylineText": {"simpleText": "K2"}}},
                    {"videoRenderer": {"videoId": "v1", "title": {"simpleText": "Duplikat"}, "longBylineText": {"simpleText": "K1"}}},
                    {"videoRenderer": null},
                    {"videoRenderer": {"videoId": "v2", "title": {"simpleText": "Dua"}}}
                ]}}
            ]}}}}
        })
    }

    #[test]
    fn yt_walk_results_cases() {
        let hits = yt_walk_results(&walk_root());
        // v1 sekali (dedup), s1 Short, v2 tanpa channel/length.
        assert_eq!(hits.len(), 3);
        assert_eq!(hits[0].id, "v1");
        assert_eq!(hits[0].title, "Satu");
        assert_eq!(hits[1].id, "s1");
        assert_eq!(hits[1].meta, "Short");
        assert_eq!(hits[1].channel, "K2");
        assert_eq!(hits[2].id, "v2");
        assert_eq!(hits[2].channel, "-");
        assert_eq!(hits[2].meta, "Durasi: tidak diketahui");
        // Struktur hilang -> kosong.
        assert!(yt_walk_results(&json!({})).is_empty());
        assert!(yt_walk_results(&json!({"contents": {}})).is_empty());
        // Via seam.
        let got = ep("yt_walk_results", json!({"json": walk_root()}));
        assert_eq!(
            got.get("hits").unwrap().as_array().unwrap().len(),
            3
        );
    }

    #[test]
    fn yt_scan_renderers_cases() {
        let html = [
            r#"xx "videoRenderer":{"videoId":"a1","title":{"simpleText":"A"},"lengthText":{"simpleText":"1:00"}} yy"#,
            r#""videoRenderer":{"videoId":"a1","title":{"simpleText":"Duplikat"}}"#,
            r#""videoRenderer":{"videoId":"a2","title":{"simpleText":"B"}}"#,
            r#""videoRenderer":{"videoId":""#,
        ]
        .concat();
        let hits = yt_scan_renderers(&html);
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[0].id, "a1");
        assert_eq!(hits[0].title, "A");
        assert_eq!(hits[1].id, "a2");
        // Cap 30.
        let many = (0..35)
            .map(|i| format!(r#""videoRenderer":{{"videoId":"v{i}","title":{{"simpleText":"T{i}"}}}}"#))
            .collect::<Vec<_>>()
            .join(" ");
        assert_eq!(yt_scan_renderers(&many).len(), 30);
        // Via seam.
        let got = ep("yt_scan_renderers", json!({"html": html}));
        assert_eq!(
            got.get("hits").unwrap().as_array().unwrap().len(),
            2
        );
    }

    #[test]
    fn parse_yt_search_cases() {
        // Jalan ytInitialData -> walk.
        let html = format!(
            "awalan var ytInitialData = {}; akhiran",
            walk_root().to_string()
        );
        let hits = parse_yt_search(&html);
        assert_eq!(hits.len(), 3);
        assert_eq!(hits[0].id, "v1");
        // Tanpa ytInitialData -> scan fallback.
        let html2 = r#"x "videoRenderer":{"videoId":"z9","title":{"simpleText":"Z"}} y"#;
        let hits2 = parse_yt_search(html2);
        assert_eq!(hits2.len(), 1);
        assert_eq!(hits2[0].id, "z9");
        // ytInitialData ada tapi walk kosong -> scan fallback.
        let html3 = "ytInitialData = {\"contents\":{}} \"videoRenderer\":{\"videoId\":\"q1\",\"title\":{\"simpleText\":\"Q\"}}";
        assert_eq!(parse_yt_search(html3).len(), 1);
        // Via seam.
        let got = ep("parse_yt_search", json!({"html": html}));
        assert_eq!(
            got.get("hits").unwrap().as_array().unwrap().len(),
            3
        );
    }

    #[test]
    fn format_yt_results_cases() {
        let hits = vec![
            YtHit {
                id: "v1".into(),
                title: "Satu".into(),
                channel: "K1".into(),
                meta: "Durasi: 3:00".into(),
            },
            YtHit {
                id: "s1".into(),
                title: "Short Satu".into(),
                channel: "K2".into(),
                meta: "Short".into(),
            },
        ];
        assert_eq!(
            format_yt_results(&hits, 5),
            "1. Satu\n   Channel: K1 | Durasi: 3:00\n   https://www.youtube.com/watch?v=v1\n2. Short Satu [Shorts]\n   Channel: K2 | Short\n   https://www.youtube.com/watch?v=s1"
        );
        // coerceIn(1,10): 0 -> 1; 99 -> 10.
        assert_eq!(format_yt_results(&hits, 0).lines().count(), 3);
        assert_eq!(format_yt_results(&hits, 99).lines().count(), 6);
        // Meta "SHORTS" (kapital) juga label Shorts (lowercase).
        let hits2 = vec![YtHit {
            id: "x".into(),
            title: "T".into(),
            channel: "C".into(),
            meta: "SHORTS".into(),
        }];
        assert!(format_yt_results(&hits2, 5).contains("[Shorts]"));
        // Kosong -> "".
        assert_eq!(format_yt_results(&[], 5), "");
        // Via seam.
        let got = ep(
            "format_yt_results",
            json!({"hits": [{"id": "v1", "title": "Satu", "channel": "K1", "meta": "Durasi: 3:00"}], "limit": 5}),
        );
        assert_eq!(
            got.get("value"),
            Some(&json!("1. Satu\n   Channel: K1 | Durasi: 3:00\n   https://www.youtube.com/watch?v=v1"))
        );
    }

    #[test]
    fn unknown_op_is_error_json() {
        let got = tools_leaves_json("{\"op\":\"tidak_ada\"}").expect("seam ok");
        assert!(got.contains("\"ok\":false"), "got={got}");
        let got = tools_leaves_json("bukan json").expect("seam ok");
        assert!(got.contains("\"ok\":false"), "got={got}");
    }
}
