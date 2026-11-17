//! Slice 12 (vc329) — logika murni `BgBrain.kt` (otak latar).
//!
//! Yang dipindah HANYA logika murni (keputusan + perakit string); yang
//! tetap Kotlin: WebView (`ensureClient`/`chatSync`), thread/executor,
//! `SharedPreferences`/DB/notifikasi, `Tools.execute`, parkir/approval,
//! `MemoryDb`, `AgentPrompt.buildSystemPrompt`, loop `while(true)`.
//!
//! Op (request `{"op", ...}` -> `{"ok":true,"value":...}`):
//! - `cut_boundary` — `cutAtMessageBoundary(text, max)`.
//! - `trim_transcript` — `trimTranscriptVc263` sebagai op string murni
//!   (masukan teks + maxChars -> teks baru + jumlah dibuang).
//! - `bg_quiet_hour` — bagian jam-hening `bgAllowed` (qs/qe/jam).
//! - `battery_formula` — rumus `batteryOk` (level/scale/plugged).
//! - `submit_decision` — urutan gerbang `submit()` (cooldown -> sesi
//!   mati -> kuota -> gerbang -> job dimatikan -> baterai); Kotlin
//!   yang menerapkan efek samping (bersihkan `bgSessionDead`,
//!   `notifyGovernorDelay`, `block`, antrean, `executor`).
//! - `continuation_prompt_bg` — `continuationPrompt` (jangkar vc316
//!   latar + baris pertama + catatan kerja + transkrip).
//! - `honest_cap_stop_bg` — `honestCapStopBgVc277` lokal loopInner.
//! - `bg_tool_route` — tabel keputusan `when` jalur tunggal loopInner
//!   (make_plan/analyze_photo/delete_session/GATES_DISABLED/memori/
//!   berisiko); parkir + eksekusi tetap Kotlin.
//! - `watchdog_decision` — keputusan per kunci `watchdogTick`.
//! - `queue_fact_proposal` — `queueBgFactProposal` (JSONArray {k,v}
//!   ganti-atau-tambah; JSON keluar di-emit urutan org.json
//!   `{"v":...,"k":...}` — terbukti probe JVM — karena konsumen
//!   me-reparse, P15 juga membandingkan secara semantik).
//! - `deliver_title` — judul + teks `deliver()` (peta origin + fallback
//!   jawaban kosong + penanda laporan gagal route).

#![forbid(unsafe_code)]

use crate::bg_room_common::*;
use serde_json::Value as Json;

pub fn bg_brain_json(req_str: &str) -> Result<String, String> {
    Ok(dispatch(req_str).unwrap_or_else(|e| err_json(&e)))
}

fn dispatch(req_str: &str) -> Result<String, String> {
    let (req, op) = req_op(req_str)?;
    match op.as_str() {
        "cut_boundary" => {
            let text = get_str(&req, "text")?;
            let max = get_i64(&req, "max", 0)?;
            Ok(ok_json(Json::String(cut_boundary(text, max))))
        }
        "trim_transcript" => {
            let text = get_str(&req, "text")?;
            let max = get_i64(&req, "max_chars", 0)?;
            let (new_text, dropped) = trim_transcript(text, max);
            Ok(ok_json(serde_json::json!({"text": new_text, "dropped": dropped})))
        }
        "bg_quiet_hour" => {
            let qs = get_i64(&req, "qs", -1)?;
            let qe = get_i64(&req, "qe", -1)?;
            let hour = get_i64(&req, "hour", 0)?;
            Ok(ok_json(Json::Bool(bg_quiet_hour(qs, qe, hour))))
        }
        "battery_formula" => {
            let level = get_i64(&req, "level", -1)? as i32;
            let scale = get_i64(&req, "scale", 100)? as i32;
            let plugged = get_i64(&req, "plugged", 0)? as i32;
            Ok(ok_json(Json::Bool(battery_formula(level, scale, plugged))))
        }
        "submit_decision" => {
            let last_rl = get_i64(&req, "last_rl", 0)?;
            let now = get_i64(&req, "now", 0)?;
            let session_dead = get_bool_d(&req, "session_dead", false);
            let last_retry = get_i64(&req, "last_retry", 0)?;
            let over_cap_bg = get_bool_d(&req, "over_cap_bg", false);
            let bg_allowed = get_bool_d(&req, "bg_allowed", true);
            let jobs_off = get_bool_d(&req, "jobs_off", false);
            let origin = get_str_d(&req, "origin");
            let battery_ok = get_bool_d(&req, "battery_ok", true);
            let (decision, clear, notify) = submit_decision(
                last_rl, now, session_dead, last_retry, over_cap_bg, bg_allowed, jobs_off,
                origin, battery_ok,
            );
            Ok(ok_json(serde_json::json!({
                "decision": decision,
                "clear_session_dead": clear,
                "governor_notify": notify,
            })))
        }
        "continuation_prompt_bg" => {
            let transcript = get_str(&req, "transcript")?;
            let work_notes = get_str_d(&req, "work_notes");
            Ok(ok_json(Json::String(continuation_prompt_bg(
                transcript, work_notes,
            ))))
        }
        "honest_cap_stop_bg" => {
            let done = get_str(&req, "done")?;
            let steps = get_i64(&req, "steps", 0)?;
            let task300 = get_str(&req, "task300")?;
            let tail = get_str(&req, "transcript_tail")?;
            Ok(ok_json(Json::String(format!(
                "Tugas latar berhenti di tengah pada langkah {steps} dari tugas: {task300} — ini yang sudah selesai: {done}; ini yang belum: tugas belum selesai — sisa langkah tidak dikerjakan. Transkrip terakhir: {tail}"
            ))))
        }
        "bg_tool_route" => {
            let tool = get_str(&req, "tool")?;
            let gates_disabled = get_bool_d(&req, "gates_disabled", false);
            let is_memory_write = get_bool_d(&req, "is_memory_write", false);
            let job_tools_null = get_bool_d(&req, "job_tools_null", true);
            let job_tools_contains = get_bool_d(&req, "job_tools_contains", false);
            let is_risky = get_bool_d(&req, "is_risky", false);
            let auto_allowed = get_bool_d(&req, "auto_allowed", false);
            let has_fact_proposal = get_bool_d(&req, "has_fact_proposal", false);
            let prop_key = get_str_d(&req, "prop_key");
            let prop_value = get_str_d(&req, "prop_value");
            let (route, message) = bg_tool_route(
                tool, gates_disabled, is_memory_write, job_tools_null, job_tools_contains,
                is_risky, auto_allowed, has_fact_proposal, prop_key, prop_value,
            );
            Ok(ok_json(serde_json::json!({"route": route, "message": message})))
        }
        "watchdog_decision" => {
            let start = get_i64(&req, "start", 0)?;
            let hb = get_i64(&req, "hb", 0)?;
            let last_ok = get_i64(&req, "last_ok", 0)?;
            let now = get_i64(&req, "now", 0)?;
            let notified = get_bool_d(&req, "notified", false);
            let (action, reason) = watchdog_decision(start, hb, last_ok, now, notified);
            Ok(ok_json(serde_json::json!({"action": action, "reason": reason})))
        }
        "queue_fact_proposal" => {
            let raw = get_str(&req, "raw")?;
            let key = get_str(&req, "key")?;
            let value = get_str(&req, "value")?;
            let (json, count) = queue_fact_proposal(raw, key, value);
            Ok(ok_json(serde_json::json!({"json": json, "count": count})))
        }
        "deliver_title" => {
            let notif_title = match req.get("notif_title") {
                None | Some(Json::Null) => None,
                Some(Json::String(s)) => Some(s.as_str()),
                Some(_) => return Err("notif_title must be a string or null".to_string()),
            };
            let origin = get_str(&req, "origin")?;
            let route_failed = get_bool_d(&req, "route_failed", false);
            let answer = get_str(&req, "answer")?;
            let (title, text) = deliver_title(notif_title, origin, route_failed, answer);
            Ok(ok_json(serde_json::json!({"title": title, "text": text})))
        }
        _ => Err(format!("unknown op: {op}")),
    }
}

/// `cutAtMessageBoundary`: `lastIndexOf('\n', max)` (startIndex
/// dijepit ke len-1, inklusif — terbukti probe JVM); potong di `\n`
/// bila `cut > max/2`, bila tidak `take(max)`. Semua hitungan unit
/// UTF-16 (`String.length` Kotlin).
pub(crate) fn cut_boundary(text: &str, max: i64) -> String {
    let units: Vec<u16> = text.encode_utf16().collect();
    let len = units.len() as i64;
    if len <= max {
        return text.to_string();
    }
    let mut cut: i64 = -1;
    if max >= 0 {
        let start = max.min(len - 1);
        let mut i = start;
        while i >= 0 {
            if units[i as usize] == b'\n' as u16 {
                cut = i;
                break;
            }
            i -= 1;
        }
    }
    if cut > max / 2 {
        String::from_utf16_lossy(&units[..cut as usize])
    } else if max <= 0 {
        String::new()
    } else {
        utf16_take(text, max as usize)
    }
}

/// `trimTranscriptVc263` sebagai fungsi string murni. Konstanta:
/// MIN_KEEP 20000, markerReserve 110, teks marker verbatim.
pub(crate) fn trim_transcript(text: &str, max_chars: i64) -> (String, i64) {
    let units: Vec<u16> = text.encode_utf16().collect();
    let before = units.len() as i64;
    if before <= max_chars {
        return (text.to_string(), 0);
    }
    let nl = units.iter().position(|&u| u == b'\n' as u16);
    let nl = match nl {
        Some(i) => i as i64,
        None => return (text.to_string(), 0),
    };
    let header_len = nl + 1;
    if header_len >= max_chars {
        return (text.to_string(), 0);
    }
    let min_keep = 20_000i64.min(max_chars);
    let mut keep_total = max_chars;
    if keep_total < min_keep {
        keep_total = min_keep;
    }
    let keep_body = (keep_total - header_len - 110).max(1000);
    let body_len = before - header_len;
    if body_len <= keep_body {
        return (text.to_string(), 0);
    }
    let mut cut_at = header_len + (body_len - keep_body);
    let nl2 = units
        .iter()
        .skip(cut_at.max(0) as usize)
        .position(|&u| u == b'\n' as u16)
        .map(|p| p as i64 + cut_at.max(0));
    if let Some(n) = nl2 {
        if n >= 0 && n < before - 1000 {
            cut_at = n + 1;
        }
    }
    if cut_at <= header_len || cut_at >= before - 1000 {
        return (text.to_string(), 0);
    }
    let dropped = cut_at - header_len;
    let marker = format!(
        "…(transkrip latar dipotong: {dropped} char awal dibuang — hanya langkah terakhir dipertahankan)…\n"
    );
    let mut out: Vec<u16> = Vec::with_capacity(
        (before - dropped) as usize + marker.encode_utf16().count(),
    );
    out.extend_from_slice(&units[..header_len as usize]);
    out.extend(marker.encode_utf16());
    out.extend_from_slice(&units[cut_at as usize..]);
    (String::from_utf16_lossy(&out), dropped)
}

/// Bagian jam-hening `bgAllowed`: `true` = jam hening aktif (gerbang
/// menutup). `qs/qe` tak-valid (`<0` atau sama) -> tidak aktif.
pub(crate) fn bg_quiet_hour(qs: i64, qe: i64, hour: i64) -> bool {
    if qs >= 0 && qe >= 0 && qs != qe {
        if qs < qe {
            hour >= qs && hour < qe
        } else {
            hour >= qs || hour < qe
        }
    } else {
        false
    }
}

/// Rumus `batteryOk`: baterai tak-terbaca (`level < 0`) = OK; bila tidak
/// `level*100/scale >= 20 || plugged != 0` (aritmetika Int Kotlin =
/// wrapping; `scale` dijepit >= 1).
pub(crate) fn battery_formula(level: i32, scale: i32, plugged: i32) -> bool {
    if level < 0 {
        return true;
    }
    let s = scale.max(1);
    level.wrapping_mul(100).wrapping_div(s) >= 20 || plugged != 0
}

/// Urutan gerbang `submit()`. Mengembalikan (keputusan, apakah
/// `bgSessionDead` harus dibersihkan + `bgDeadRetryTs` ditulis,
/// pesan `notifyGovernorDelay` atau null).
/// Keputusan: "proceed" | "cooldown" | "sesi mati" | "kuota" |
/// "gerbang" | "job dimatikan" | "baterai".
pub(crate) fn submit_decision(
    last_rl: i64,
    now: i64,
    session_dead: bool,
    last_retry: i64,
    over_cap_bg: bool,
    bg_allowed: bool,
    jobs_off: bool,
    origin: &str,
    battery_ok: bool,
) -> (&'static str, bool, Option<&'static str>) {
    if last_rl > 0 && now - last_rl < 15 * 60_000 {
        return ("cooldown", false, None);
    }
    let mut clear = false;
    if session_dead {
        if now - last_retry < 15 * 60_000 {
            return ("sesi mati", false, None);
        }
        clear = true;
    }
    if over_cap_bg {
        return ("kuota", clear, Some("kuota harian Governor habis"));
    }
    if !bg_allowed {
        return ("gerbang", clear, Some("gerbang jam hening / WiFi-only"));
    }
    if (origin == "jadwal" || origin == "rutinitas") && jobs_off {
        return ("job dimatikan", clear, None);
    }
    if !battery_ok {
        return ("baterai", clear, None);
    }
    ("proceed", clear, None)
}

/// `continuationPrompt` latar: jangkar vc316 latar (literal verbatim
/// dari sumber) + baris pertama transkrip + catatan kerja (bila
/// non-blank) + `[RIWAYAT]` + penutup latar.
pub(crate) fn continuation_prompt_bg(transcript: &str, work_notes: &str) -> String {
    const ANCHOR: &str = r##"[LANJUTAN SISTEM — vc316 delta: system prompt, katalog tool, memori, dan riwayat tugas ini SUDAH kamu terima di pesan pertama tugas ini DALAM SESI SERVER YANG SAMA — semuanya masih berlaku, jangan minta diulang. Lanjutkan tugas di bawah memakai transkrip hasil tool sejauh ini. Format tool-call tetap: ```json {"tool":"<nama>","args":{...},"id":"tc-<n>"}``` — bila butuh tool yang tidak ada di katalog ringkasmu, panggil list_tools {} dulu untuk katalog lengkap.]"##;
    let mut sb = String::new();
    sb.push_str(ANCHOR);
    sb.push_str("\n\nTUGAS (verbatim baris pertama transkrip giliran ini — jangan diubah, jangan ditambah):\n");
    sb.push_str(substring_before_nl(transcript));
    if !is_blank(work_notes) {
        sb.push_str("\n\n");
        sb.push_str(work_notes);
    }
    sb.push_str("\n\n[MODE LATAR] Kamu sedang menjalankan tugas otomatis di latar. Catatan giliran sejauh ini:\n");
    sb.push_str("[RIWAYAT]\n");
    sb.push_str(transcript);
    sb.push_str("\n[/RIWAYAT]");
    sb.push_str("\n\nLanjutkan tugasnya: bila butuh tool lain keluarkan blok json lagi, bila selesai jawab final tanpa blok json.");
    sb
}

/// Tabel keputusan `when` jalur tunggal `loopInnerVc263`.
/// Route: "execute" | "msg_analyze_photo" | "msg_delete_session" |
/// "park_memory_jobtools" | "memory_write" | "park_memory_noprop" |
/// "park_risky". (Berbeda dengan GroupChat: cabang GATES_DISABLED di
/// sini memanggil `Tools.execute` polos tanpa ledger, jadi rutenya
/// sama dengan shortcut — "execute".) `message` hanya untuk dua pesan
/// tetap + pesan proposal memori (yang terakhir memakai
/// `prop_value.take(140)`).
pub(crate) fn bg_tool_route(
    tool: &str,
    gates_disabled: bool,
    is_memory_write: bool,
    job_tools_null: bool,
    job_tools_contains: bool,
    is_risky: bool,
    auto_allowed: bool,
    has_fact_proposal: bool,
    prop_key: &str,
    prop_value: &str,
) -> (&'static str, Option<String>) {
    if tool == "make_plan" || tool == "update_plan" {
        return ("execute", None);
    }
    if tool == "analyze_photo" {
        return (
            "msg_analyze_photo",
            Some("Tool analyze_photo hanya tersedia dari chat 1-1 — di mode latar tidak dijalankan. Selesaikan tugas tanpa tool itu.".to_string()),
        );
    }
    if tool == "delete_session" {
        return (
            "msg_delete_session",
            Some("Tool delete_session hanya tersedia dari UI chat aktif — di mode latar tidak dijalankan. Selesaikan tugas tanpa tool itu.".to_string()),
        );
    }
    if gates_disabled {
        return ("execute", None);
    }
    if is_memory_write {
        if !job_tools_null && !job_tools_contains {
            return ("park_memory_jobtools", None);
        }
        if has_fact_proposal {
            let msg = format!(
                "Proposal memori latar DITAMPUNG di antrean persetujuan batch (Setup 65) — BELUM tersimpan dan BELUM dieksekusi, menunggu persetujuan user: {prop_key} = {}. Lanjutkan tugas tanpa menganggap fakta ini sudah tersimpan.",
                utf16_take(prop_value, 140)
            );
            return ("memory_write", Some(msg));
        }
        return ("park_memory_noprop", None);
    }
    if is_risky && !(auto_allowed && (job_tools_null || job_tools_contains)) {
        return ("park_risky", None);
    }
    ("execute", None)
}

/// Keputusan per kunci `watchdogTick`. Action: "skip" | "cleanup" |
/// "notify". `reason` hanya untuk "notify" (teks alasan verbatim).
pub(crate) fn watchdog_decision(
    start: i64,
    hb: i64,
    last_ok: i64,
    now: i64,
    notified: bool,
) -> (&'static str, Option<&'static str>) {
    if start <= 0 {
        return ("skip", None);
    }
    if now - start > 2 * 3600_000 {
        return ("cleanup", None);
    }
    if notified {
        return ("skip", None);
    }
    let stalled = now - hb > 10 * 60_000;
    let spinning = now - last_ok > 10 * 60_000;
    if !stalled && !spinning {
        return ("skip", None);
    }
    let reason = if stalled {
        "tidak ada kemajuan selama 10 menit (giliran macet)"
    } else {
        "tidak ada tool sukses selama 10 menit (berputar tanpa progres)"
    };
    ("notify", Some(reason))
}

/// Emit satu elemen proposal `{k, v}` urutan org.json (`"v"` dulu —
/// terbukti probe JVM; konsumen me-reparse sehingga urutan tak
/// bermakna, tapi byte-identik untuk bentuk produksi {k,v}).
fn emit_kv(key: &str, value: &str) -> String {
    format!("{{\"v\":{},\"k\":{}}}", json_quote(value), json_quote(key))
}

/// `queueBgFactProposal` sebagai op string murni. Masukan `raw` =
/// isi prefs `pendingExtractProposals`. `count == -1` (dan `json`
/// null) = JSON `raw` tak-valid (Kotlin: `catch` -> `-1`, tanpa tulis).
/// Elemen array yang bukan objek dilewati (`optJSONObject` -> null);
/// elemen `{k, v}` dipertahankan byte-identik urutan org.json; elemen
/// bentuk lain di-emit ulang JSON kompak (konsumen me-reparse).
pub(crate) fn queue_fact_proposal(raw: &str, key: &str, value: &str) -> (Option<String>, i64) {
    let old: Vec<Json> = if crate::bg_room_common::is_blank(raw) {
        Vec::new()
    } else {
        match parse_json(raw) {
            Some(Json::Array(a)) => a,
            _ => return (None, -1),
        }
    };
    let mut out: Vec<String> = Vec::new();
    let mut replaced = false;
    for el in &old {
        let obj = match el {
            Json::Object(_) => el,
            _ => continue,
        };
        if opt_string(obj, "k", "") == key {
            out.push(emit_kv(key, value));
            replaced = true;
        } else if obj.as_object().map(|m| m.len() == 2 && m.contains_key("k") && m.contains_key("v")).unwrap_or(false) {
            out.push(emit_kv(&opt_string(obj, "k", ""), &opt_string(obj, "v", "")));
        } else {
            out.push(el.to_string());
        }
    }
    if !replaced {
        out.push(emit_kv(key, value));
    }
    let count = out.len() as i64;
    (Some(format!("[{}]", out.join(","))), count)
}

/// Judul + teks `deliver()`: `notifTitle` (bila non-null, dipakai
/// apa adanya — `?:` Kotlin hanya mengganti null) atau peta origin;
/// `route_failed` -> awalan "⚠️ Laporan gagal tersimpan — ";
/// teks = `answer.trim()` atau fallback bila blank.
pub(crate) fn deliver_title(
    notif_title: Option<&str>,
    origin: &str,
    route_failed: bool,
    answer: &str,
) -> (String, String) {
    let base = match notif_title {
        Some(t) => t.to_string(),
        None => match origin {
            "jadwal" => "🤖 Tugas terjadwal selesai".to_string(),
            "aturan" => "⚡ Aturan notifikasi dijalankan".to_string(),
            "tujuan" => "🎯 Langkah tujuan dikerjakan".to_string(),
            "briefing" => "☀️ Briefing pagi".to_string(),
            "rekap" => "🌙 Rekap malam".to_string(),
            "konsolidasi" => "🌙 Konsolidasi memori".to_string(),
            other => format!("🤖 Otak Latar — {other}"),
        },
    };
    let title = if route_failed {
        format!("⚠️ Laporan gagal tersimpan — {base}")
    } else {
        base
    };
    let trimmed = crate::ds_safety_strip::kotlin_trim(answer);
    let text = if is_blank(trimmed) {
        "(tugas selesai tanpa teks jawaban)".to_string()
    } else {
        trimmed.to_string()
    };
    (title, text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cut_boundary_matches_kotlin() {
        let t = "a\nb\ncdefgh";
        assert_eq!(cut_boundary(t, 100), t);
        assert_eq!(cut_boundary("a\nb\ncdefgh", 4), "a\nb");
        assert_eq!(cut_boundary("abcdefgh", 4), "abcd");
        // max=100 pada len 8 -> lastIndexOf dijepit len-1 (probe JVM).
        assert_eq!(cut_boundary("a\nb\nc\n", 100), "a\nb\nc\n");
    }

    #[test]
    fn trim_transcript_matches_kotlin() {
        let header = "Tugas (jadwal): kerjakan ini\n";
        let body = "x".repeat(70_000);
        let text = format!("{header}{body}");
        let (out, dropped) = trim_transcript(&text, 60_000);
        assert!(dropped > 0);
        assert!(out.starts_with(header));
        assert!(out.contains("transkrip latar dipotong"));
        assert!(utf16_len(&out) <= 60_000 + 200);
        // Di bawah cap -> tidak berubah.
        let (out2, d2) = trim_transcript("pendek\nisi", 60_000);
        assert_eq!(d2, 0);
        assert_eq!(out2, "pendek\nisi");
        // Tanpa newline -> tidak dipotong.
        let (out3, d3) = trim_transcript(&"y".repeat(70_000), 60_000);
        assert_eq!(d3, 0);
        assert_eq!(out3.len(), 70_000);
    }

    #[test]
    fn quiet_hour_and_battery() {
        assert!(bg_quiet_hour(22, 6, 23));
        assert!(bg_quiet_hour(22, 6, 3));
        assert!(!bg_quiet_hour(22, 6, 12));
        assert!(!bg_quiet_hour(-1, 6, 23));
        assert!(!bg_quiet_hour(5, 5, 5));
        assert!(bg_quiet_hour(9, 17, 10));
        assert!(!bg_quiet_hour(9, 17, 18));
        assert!(battery_formula(-1, 100, 0));
        assert!(battery_formula(19, 100, 0) == false);
        assert!(battery_formula(19, 100, 1));
        assert!(battery_formula(20, 100, 0));
        assert!(battery_formula(50, 0, 0)); // scale 0 -> dijepit 1
    }

    #[test]
    fn submit_decision_gate_order() {
        let base = |last_rl: i64, now: i64| {
            submit_decision(last_rl, now, false, 0, false, true, false, "jadwal", true)
        };
        // Cooldown menang atas segalanya.
        assert_eq!(base(1_000_000, 1_000_000 + 100_000).0, "cooldown");
        assert_eq!(base(1_000_000, 1_000_000 + 900_000).0, "proceed");
        // Sesi mati: dalam 15 mnt -> blok; lewat -> clear + lanjut.
        assert_eq!(
            submit_decision(0, 2_000_000, true, 1_900_000, false, true, false, "jadwal", true).0,
            "sesi mati"
        );
        let (d, clear, _) =
            submit_decision(0, 2_000_000, true, 1_000_000, false, true, false, "jadwal", true);
        assert_eq!(d, "proceed");
        assert!(clear);
        // Kuota -> gerbang -> job dimatikan -> baterai.
        assert_eq!(
            submit_decision(0, 0, false, 0, true, true, false, "jadwal", true).0,
            "kuota"
        );
        assert_eq!(
            submit_decision(0, 0, false, 0, false, false, false, "jadwal", true).0,
            "gerbang"
        );
        assert_eq!(
            submit_decision(0, 0, false, 0, false, true, true, "rutinitas", true).0,
            "job dimatikan"
        );
        assert_eq!(
            submit_decision(0, 0, false, 0, false, true, true, "manual", true).0,
            "proceed"
        );
        assert_eq!(
            submit_decision(0, 0, false, 0, false, true, false, "jadwal", false).0,
            "baterai"
        );
    }

    #[test]
    fn tool_route_table() {
        let base = |tool: &str| {
            bg_tool_route(tool, false, false, true, false, false, false, false, "", "")
        };
        assert_eq!(base("make_plan").0, "execute");
        assert_eq!(base("update_plan").0, "execute");
        assert_eq!(base("analyze_photo").0, "msg_analyze_photo");
        assert_eq!(base("delete_session").0, "msg_delete_session");
        // GATES_DISABLED menang atas memori/berisiko (execute polos).
        assert_eq!(
            bg_tool_route("hapus_fakta", true, true, true, false, true, false, false, "", "").0,
            "execute"
        );
        // Memori + jobTools membatasi -> parkir.
        assert_eq!(
            bg_tool_route("hapus_fakta", false, true, false, false, false, false, false, "", "").0,
            "park_memory_jobtools"
        );
        // Memori + proposal -> tampung.
        let (r, m) = bg_tool_route(
            "tambah_fakta", false, true, true, false, false, false, true, "kota", "Jakarta",
        );
        assert_eq!(r, "memory_write");
        assert!(m.unwrap().contains("kota = Jakarta"));
        // Berisiko tanpa autoAllow -> parkir; dengan autoAllow -> execute.
        assert_eq!(
            bg_tool_route("kirim_wa", false, false, true, false, true, false, false, "", "").0,
            "park_risky"
        );
        assert_eq!(
            bg_tool_route("kirim_wa", false, false, true, false, true, true, false, "", "").0,
            "execute"
        );
    }

    #[test]
    fn watchdog_decision_matrix() {
        assert_eq!(watchdog_decision(0, 0, 0, 100, false).0, "skip");
        assert_eq!(watchdog_decision(100, 100, 100, 100 + 7_200_001, false).0, "cleanup");
        // Batas 2 jam persis -> bukan cleanup; heartbeat segar -> skip.
        assert_eq!(
            watchdog_decision(100, 100 + 7_200_000, 100 + 7_200_000, 100 + 7_200_000, false).0,
            "skip"
        );
        assert_eq!(watchdog_decision(100, 100, 100, 1000, true).0, "skip");
        assert_eq!(
            watchdog_decision(100, 100, 100, 100 + 600_001, false),
            ("notify", Some("tidak ada kemajuan selama 10 menit (giliran macet)"))
        );
        // Stalled menang atas spinning bila keduanya.
        assert_eq!(
            watchdog_decision(100, 100, 100, 100 + 600_001, false).1,
            Some("tidak ada kemajuan selama 10 menit (giliran macet)")
        );
        // Hanya spinning (hb segar, lastOk lama).
        assert_eq!(
            watchdog_decision(100, 100 + 600_000, 100, 100 + 600_001, false),
            ("notify", Some("tidak ada tool sukses selama 10 menit (berputar tanpa progres)"))
        );
    }

    #[test]
    fn queue_fact_proposal_replace_append_bad() {
        let (j, c) = queue_fact_proposal("", "kota", "Jakarta");
        assert_eq!(c, 1);
        assert_eq!(j.as_deref(), Some("[{\"v\":\"Jakarta\",\"k\":\"kota\"}]"));
        // Ganti key sama, tambah key baru.
        let (j2, c2) = queue_fact_proposal(
            "[{\"v\":\"Lama\",\"k\":\"kota\"},{\"v\":\"x\",\"k\":\"lain\"}]",
            "kota",
            "Baru",
        );
        assert_eq!(c2, 2);
        assert_eq!(
            j2.as_deref(),
            Some("[{\"v\":\"Baru\",\"k\":\"kota\"},{\"v\":\"x\",\"k\":\"lain\"}]")
        );
        // JSON rusak -> -1.
        let (j3, c3) = queue_fact_proposal("{bukan json", "k", "v");
        assert_eq!(c3, -1);
        assert!(j3.is_none());
        // Elemen bukan objek dilewati.
        let (j4, c4) = queue_fact_proposal("[1,\"s\",{\"v\":\"a\",\"k\":\"k\"}]", "k2", "v2");
        assert_eq!(c4, 2);
        assert_eq!(
            j4.as_deref(),
            Some("[{\"v\":\"a\",\"k\":\"k\"},{\"v\":\"v2\",\"k\":\"k2\"}]")
        );
    }

    #[test]
    fn deliver_title_map() {
        assert_eq!(
            deliver_title(None, "jadwal", false, "  hasil  ").0,
            "🤖 Tugas terjadwal selesai"
        );
        assert_eq!(
            deliver_title(None, "konsolidasi", false, "x").0,
            "🌙 Konsolidasi memori"
        );
        assert_eq!(
            deliver_title(None, "aneh", true, "x").0,
            "⚠️ Laporan gagal tersimpan — 🤖 Otak Latar — aneh"
        );
        assert_eq!(deliver_title(Some("Judulku"), "jadwal", false, "x").0, "Judulku");
        assert_eq!(deliver_title(None, "jadwal", false, "   ").1, "(tugas selesai tanpa teks jawaban)");
        assert_eq!(deliver_title(None, "jadwal", false, "  isi ").1, "isi");
    }
}
