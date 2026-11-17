//! Slice 12 (vc329) — logika murni `OcCoord.kt` (koordinator OpenCode).
//!
//! Yang dipindah HANYA logika murni; yang tetap Kotlin: `OcClient`
//! (HTTP/SSE serve), `MemoryDb`/prefs, thread + `Thread.sleep`,
//! notifikasi, `Checkpoint`, `Automation`, reviewer, `WorkNotes`,
//! `Tools.runTerminal`, watcher SSE (loop baca baris — parse SATU
//! barisnya dipindah sebagai `parse_perm_event`).
//!
//! Op:
//! - `result_notif_id` — `(40000 + taskId).toInt()` (wrapping).
//! - `rule_defaults` — 10 aturan default (urutan `linkedMapOf`).
//! - `sanitize_rule` — sanitasi baca `rule()` (path_mode/auto_approve).
//! - `rule_int` — `ruleInt` (sanitasi + `trim().toIntOrNull()` + def).
//! - `spinning_pattern` — `spinningPatternVc277` (pasca-hoc).
//! - `spin_observe` / `spin_flush` — `OcSpinWatchVc303` (state
//!   diserahkan bolak-balik sebagai JSON; observe berhenti di hit
//!   pertama TANPA memproses baris sesudahnya — sama seperti Kotlin).
//! - `budget_over` — `OcTaskBudgetVc303.overSteps`.
//! - `looks_failed` — `looksFailedVc262` (5 penanda).
//! - `quiet_now` — `isQuietNow` (spesifikasi `jam-hening`).
//! - `incremental_text` — `incrementalAssistantTextVc303` (tanpa cap).
//! - `collect_result` — `collectResult` (cap 3000 + `ctxSize`).
//! - `scan_messages` — pindai pesan per poll `awaitCompletion`
//!   (asstCount / finishedSeen / agentError terakhir-menang).
//! - `await_body` — badan + status akhir `awaitCompletion`.
//! - `honest_status` — status JUJUR `finishTask` (spin -> partial).
//! - `runloop_gate` — gerbang lanjutan rantai `runLoop`.
//! - `session_rotate` — rotasi sesi per rantai `runTask`.
//! - `serve_select` — pohon keputusan `ensureServe` dua fase
//!   (fase 0: probe awal; fase 1: sesudah nyalakan serve).
//! - `probe_cache_read` / `probe_cache_write` — `probeCached`.
//! - `status_text` — `statusText` (perakit teks panel).
//! - `task_result_text` — `taskResultText` (perakit hasil tugas).
//! - `peek_text` — `peekSessionText` (teks asisten terakhir).
//! - `parse_perm_event` — SATU baris SSE izin (filter `data:` +
//!   `permission` + `sessionID` + judul + detail vc260).
//! - `perm_stale` — basi izin (`ts > 0 && now - ts > ttl`).
//! - `perm_decision` — keputusan izin `handlePermission`
//!   (GATES_DISABLED / pola `*` / cocok judul ignoreCase).
//! - `battery_pct` — `batteryPct` (level*100/scale, default 100).
//!
//! CATATAN KEJUJURAN: `opt("args")` di `parse_perm_event` memakai
//! JSON kompak serde (key terurut) sementara org.json hash order —
//! P15 membandingkan detail itu secara semantik bila argumen berupa
//! objek; untuk string/angka/array dibandingkan persis.

#![forbid(unsafe_code)]

use crate::bg_room_common::*;
use serde_json::Value as Json;
use std::collections::HashMap;

pub fn oc_coord_json(req_str: &str) -> Result<String, String> {
    Ok(dispatch(req_str).unwrap_or_else(|e| err_json(&e)))
}

fn dispatch(req_str: &str) -> Result<String, String> {
    let (req, op) = req_op(req_str)?;
    match op.as_str() {
        "result_notif_id" => {
            let task_id = get_i64(&req, "task_id", 0)?;
            let id = (40_000i64.wrapping_add(task_id)) as i32;
            Ok(ok_json(Json::from(id)))
        }
        "rule_defaults" => {
            let pairs: Vec<Json> = rule_defaults()
                .iter()
                .map(|(k, v)| Json::Array(vec![Json::String(k.to_string()), Json::String(v.to_string())]))
                .collect();
            Ok(ok_json(Json::Array(pairs)))
        }
        "sanitize_rule" => {
            let key = get_str(&req, "key")?;
            let raw = get_str(&req, "raw")?;
            Ok(ok_json(Json::String(sanitize_rule(key, raw))))
        }
        "rule_int" => {
            let key = get_str(&req, "key")?;
            let raw = get_str(&req, "raw")?;
            let def = get_i64(&req, "def", 0)? as i32;
            Ok(ok_json(Json::from(rule_int(key, raw, def))))
        }
        "spinning_pattern" => {
            let text = get_str(&req, "text")?;
            Ok(ok_json(match spinning_pattern(text) {
                Some(s) => Json::String(s),
                None => Json::Null,
            }))
        }
        "spin_observe" => {
            let state = get_str(&req, "state")?;
            let chunk = get_str(&req, "chunk")?;
            let (hit, new_state) = spin_observe(state, chunk)?;
            Ok(ok_json(serde_json::json!({"hit": hit, "state": new_state})))
        }
        "spin_flush" => {
            let state = get_str(&req, "state")?;
            let (hit, new_state) = spin_flush(state)?;
            Ok(ok_json(serde_json::json!({"hit": hit, "state": new_state})))
        }
        "budget_over" => {
            let asst = get_i64(&req, "assistant_messages", 0)?;
            let max = get_i64(&req, "max_steps", 200)?;
            Ok(ok_json(Json::Bool(budget_over(asst, max))))
        }
        "looks_failed" => {
            let text = get_str(&req, "text")?;
            Ok(ok_json(Json::Bool(looks_failed(text))))
        }
        "quiet_now" => {
            let spec = get_str(&req, "spec")?;
            let hour = get_i64(&req, "hour", 0)?;
            Ok(ok_json(Json::Bool(quiet_now(spec, hour))))
        }
        "incremental_text" => {
            let msgs = msgs_array(&req)?;
            let baseline = get_i64(&req, "baseline", 0)?;
            Ok(ok_json(Json::String(incremental_text(&msgs, baseline))))
        }
        "collect_result" => {
            let msgs = msgs_array(&req)?;
            let baseline = get_i64(&req, "baseline", 0)?;
            let (text, ctx_size) = collect_result(&msgs, baseline);
            Ok(ok_json(serde_json::json!({"text": text, "ctx_size": ctx_size})))
        }
        "scan_messages" => {
            let msgs = msgs_array(&req)?;
            let baseline = get_i64(&req, "baseline", 0)?;
            let (asst_count, finished_seen, agent_error) = scan_messages(&msgs, baseline);
            Ok(ok_json(serde_json::json!({
                "asst_count": asst_count,
                "finished_seen": finished_seen,
                "agent_error": agent_error,
            })))
        }
        "await_body" => {
            let txt = get_str(&req, "txt")?;
            let timed_out = get_bool_d(&req, "timed_out", false);
            let sid = get_str(&req, "sid")?;
            let (status, body) = await_body(txt, timed_out, sid);
            Ok(ok_json(serde_json::json!({"status": status, "body": body})))
        }
        "honest_status" => {
            let status = get_str(&req, "status")?;
            let spinning = match req.get("spinning") {
                None | Some(Json::Null) => None,
                Some(Json::String(s)) => Some(s.as_str()),
                Some(_) => return Err("spinning must be a string or null".to_string()),
            };
            let result_full = get_str(&req, "result_full")?;
            let (st, res) = honest_status(status, spinning, result_full);
            Ok(ok_json(serde_json::json!({"status": st, "result": res})))
        }
        "runloop_gate" => {
            let continuation = get_bool_d(&req, "continuation", false);
            let battery_pct_v = get_i64(&req, "battery_pct", 100)?;
            let batt_min = get_i64(&req, "batt_min", 20)?;
            let quiet_spec = get_str_d(&req, "quiet_spec");
            let hour = get_i64(&req, "hour", 0)?;
            let delay_sec = get_i64(&req, "delay_sec", 10)?;
            let (action, note) =
                runloop_gate(continuation, battery_pct_v, batt_min, quiet_spec, hour);
            Ok(ok_json(serde_json::json!({
                "action": action, "note": note, "delay_sec": delay_sec,
            })))
        }
        "session_rotate" => {
            let sid = get_str(&req, "sid")?;
            let prev_ctx = get_i64(&req, "prev_ctx", 0)?;
            let ctx_limit = get_i64(&req, "ctx_limit", 350000)?;
            let task_id = get_i64(&req, "task_id", 0)?;
            let task_text = get_str(&req, "task_text")?;
            let (need, note, title) = session_rotate(sid, prev_ctx, ctx_limit, task_id, task_text);
            Ok(ok_json(serde_json::json!({
                "need_create": need, "rotate_note": note, "title": title,
            })))
        }
        "serve_select" => {
            let phase = get_i64(&req, "phase", 0)?;
            let mode = get_str(&req, "mode")?;
            let p4099 = get_bool_d(&req, "p4099", false);
            let p4097 = get_bool_d(&req, "p4097", false);
            let p4096 = get_bool_d(&req, "p4096", false);
            let (port, note, action) = serve_select(phase, mode, p4099, p4097, p4096);
            Ok(ok_json(serde_json::json!({
                "base_port": port, "note": note, "action": action,
            })))
        }
        "probe_cache_read" => {
            let force = get_bool_d(&req, "force", false);
            let now_ms = get_i64(&req, "now_ms", 0)?;
            let cached_ts = get_i64(&req, "cached_ts", 0)?;
            let cached_raw = get_str_d(&req, "cached_raw");
            let (hit, probes) = probe_cache_read(force, now_ms, cached_ts, cached_raw);
            Ok(ok_json(serde_json::json!({"hit": hit, "probes": probes})))
        }
        "probe_cache_write" => {
            let p4097 = get_bool_d(&req, "p4097", false);
            let p4096 = get_bool_d(&req, "p4096", false);
            let p4099 = get_bool_d(&req, "p4099", false);
            let bit = |b: bool| if b { "1" } else { "0" };
            Ok(ok_json(Json::String(format!(
                "4097={},4096={},4099={}",
                bit(p4097),
                bit(p4096),
                bit(p4099)
            ))))
        }
        "status_text" => {
            let st = StatusTextIn {
                mode: get_str(&req, "mode")?.to_string(),
                p4097: get_bool_d(&req, "p4097", false),
                p4096: get_bool_d(&req, "p4096", false),
                p4099: get_bool_d(&req, "p4099", false),
                cur_task: get_i64(&req, "cur_task", 0)?,
                queued: get_i64(&req, "queued", 0)?,
                running: get_i64(&req, "running", 0)?,
                pending_title: get_str_d(&req, "pending_title").to_string(),
                rules: req.get("rules").cloned().unwrap_or(Json::Null),
                gates_disabled: get_bool_d(&req, "gates_disabled", false),
                recent: match req.get("recent") {
                    Some(Json::Array(a)) => a.clone(),
                    _ => Vec::new(),
                },
            };
            Ok(ok_json(Json::String(status_text(&st))))
        }
        "task_result_text" => {
            let id = get_i64(&req, "id", 0)?;
            let task = req.get("task").cloned().unwrap_or(Json::Null);
            let peek = get_str_d(&req, "peek");
            Ok(ok_json(Json::String(task_result_text(id, &task, peek))))
        }
        "peek_text" => {
            let msgs = msgs_array(&req)?;
            Ok(ok_json(Json::String(peek_text(&msgs))))
        }
        "parse_perm_event" => {
            let line = get_str(&req, "line")?;
            let sid = get_str(&req, "sid")?;
            Ok(ok_json(match parse_perm_event(line, sid) {
                Some((perm_id, title, detail)) => serde_json::json!({
                    "perm_id": perm_id, "title": title, "detail": detail,
                }),
                None => Json::Null,
            }))
        }
        "perm_stale" => {
            let ts = get_i64(&req, "ts", 0)?;
            let now_ms = get_i64(&req, "now_ms", 0)?;
            let ttl_min = get_i64(&req, "ttl_min", 30)?;
            Ok(ok_json(Json::Bool(perm_stale(ts, now_ms, ttl_min))))
        }
        "perm_decision" => {
            let gates_disabled = get_bool_d(&req, "gates_disabled", false);
            let auto_approve = get_str_d(&req, "auto_approve");
            let title = get_str(&req, "title")?;
            Ok(ok_json(Json::String(
                perm_decision(gates_disabled, auto_approve, title).to_string(),
            )))
        }
        "battery_pct" => {
            let level = get_i64(&req, "level", -1)? as i32;
            let scale = get_i64(&req, "scale", 100)? as i32;
            Ok(ok_json(Json::from(battery_pct(level, scale))))
        }
        _ => Err(format!("unknown op: {op}")),
    }
}

/// Baca `"msgs"`: array JSON pesan serve (atau string JSON array).
fn msgs_array(req: &Json) -> Result<Vec<Json>, String> {
    match req.get("msgs") {
        Some(Json::Array(a)) => Ok(a.clone()),
        Some(Json::String(s)) => match parse_json(s) {
            Some(Json::Array(a)) => Ok(a),
            _ => Err("msgs must be a JSON array".to_string()),
        },
        _ => Err("msgs must be an array".to_string()),
    }
}

/// `RULE_DEFAULTS` — urutan `linkedMapOf` sumber (dipertahankan).
pub(crate) fn rule_defaults() -> Vec<(&'static str, &'static str)> {
    vec![
        ("path_mode", "resmi"),
        ("auto_approve", ""),
        ("session_ctx_limit", "350000"),
        ("chain_max", "3"),
        ("chain_delay_sec", "10"),
        ("chain_batt_min", "20"),
        ("quiet_hours", ""),
        ("task_timeout_min", "20"),
        ("perm_timeout_min", "30"),
        ("oc_task_max_steps_vc303", "200"),
    ]
}

/// Sanitasi baca `rule()`: `path_mode` -> trim+lowercase bila
/// resmi/mandiri, bila tidak "resmi"; `auto_approve` ->
/// on/true/yes/aktif -> `""`, bila tidak RAW TANPA TRIM; lain -> raw.
pub(crate) fn sanitize_rule(key: &str, raw: &str) -> String {
    match key {
        "path_mode" => {
            let t = crate::ds_safety_strip::kotlin_trim(raw).to_lowercase();
            if t == "resmi" || t == "mandiri" {
                t
            } else {
                "resmi".to_string()
            }
        }
        "auto_approve" => {
            let t = crate::ds_safety_strip::kotlin_trim(raw).to_lowercase();
            if t == "on" || t == "true" || t == "yes" || t == "aktif" {
                String::new()
            } else {
                raw.to_string()
            }
        }
        _ => raw.to_string(),
    }
}

/// `ruleInt`: sanitasi + `trim().toIntOrNull()` + default.
pub(crate) fn rule_int(key: &str, raw: &str, def: i32) -> i32 {
    let s = sanitize_rule(key, raw);
    java_parse_int_strict(&s).unwrap_or(def)
}

/// `spinningPatternVc277`: satu pola (baris ternormalisasi) yang sama
/// >= 3x dalam teks hasil -> cuplikan baris pertama (take 120).
/// Baris < 20 char (unit UTF-16) diabaikan.
pub(crate) fn spinning_pattern(text: &str) -> Option<String> {
    let mut counts: HashMap<String, i64> = HashMap::new();
    let mut first_seen: HashMap<String, String> = HashMap::new();
    for raw in crate::agent_prompt::kotlin_lines(text) {
        let norm = spin_normalize(raw);
        if utf16_len(&norm) < 20 {
            continue;
        }
        let n = counts.get(&norm).copied().unwrap_or(0) + 1;
        counts.insert(norm.clone(), n);
        if !first_seen.contains_key(&norm) {
            first_seen.insert(
                norm.clone(),
                crate::ds_safety_strip::kotlin_trim(raw).to_string(),
            );
        }
        if n >= 3 {
            return Some(utf16_take(&first_seen[&norm], 120));
        }
    }
    None
}

/// State `OcSpinWatchVc303` sebagai JSON:
/// `{"counts": {norm: n}, "first_seen": {norm: raw}, "pending": "..."}`.
struct SpinState {
    counts: HashMap<String, i64>,
    first_seen: HashMap<String, String>,
    pending: String,
}

fn spin_state_parse(s: &str) -> Result<SpinState, String> {
    let v: Json = serde_json::from_str(s).map_err(|_| "bad spin state json".to_string())?;
    let counts = match v.get("counts") {
        Some(Json::Object(m)) => {
            let mut out = HashMap::new();
            for (k, val) in m {
                if let Some(n) = val.as_i64() {
                    out.insert(k.clone(), n);
                }
            }
            out
        }
        _ => return Err("spin state counts must be an object".to_string()),
    };
    let first_seen = match v.get("first_seen") {
        Some(Json::Object(m)) => {
            let mut out = HashMap::new();
            for (k, val) in m {
                if let Some(s) = val.as_str() {
                    out.insert(k.clone(), s.to_string());
                }
            }
            out
        }
        _ => return Err("spin state first_seen must be an object".to_string()),
    };
    let pending = match v.get("pending") {
        Some(Json::String(s)) => s.clone(),
        _ => return Err("spin state pending must be a string".to_string()),
    };
    Ok(SpinState { counts, first_seen, pending })
}

/// State rusak (bukan JSON / bentuk salah) dianggap SEGAR (state kosong),
/// bukan error — deteksi spin tidak boleh hilang gara-gara state korup.
/// State di produksi selalu berasal dari `stateJson()` Kotlin (valid);
/// jalur ini hanya berjaga untuk input korup (P15 §6.9).
fn spin_state_fresh_or_parse(s: &str) -> SpinState {
    spin_state_parse(s).unwrap_or(SpinState {
        counts: HashMap::new(),
        first_seen: HashMap::new(),
        pending: String::new(),
    })
}

fn spin_state_emit(st: &SpinState) -> Json {
    let mut counts = serde_json::Map::new();
    for (k, v) in &st.counts {
        counts.insert(k.clone(), Json::from(*v));
    }
    let mut first_seen = serde_json::Map::new();
    for (k, v) in &st.first_seen {
        first_seen.insert(k.clone(), Json::String(v.clone()));
    }
    serde_json::json!({"counts": counts, "first_seen": first_seen, "pending": st.pending})
}

/// Satu langkah normalisasi+hitung spinning; mengembalikan hit
/// (cuplikan take 120) bila pola mencapai 3x. Dipakai observe/flush.
fn spin_hit(st: &mut SpinState, raw: &str) -> Option<String> {
    let norm = spin_normalize(raw);
    if utf16_len(&norm) < 20 {
        return None;
    }
    let n = st.counts.get(&norm).copied().unwrap_or(0) + 1;
    st.counts.insert(norm.clone(), n);
    if !st.first_seen.contains_key(&norm) {
        st.first_seen.insert(
            norm.clone(),
            crate::ds_safety_strip::kotlin_trim(raw).to_string(),
        );
    }
    if n >= 3 {
        Some(utf16_take(&st.first_seen[&norm], 120))
    } else {
        None
    }
}

/// `OcSpinWatchVc303.observe`: chunk kosong -> null (state utuh);
/// baris terakhir tanpa newline ditahan di `pending`; BERHENTI di hit
/// pertama tanpa memproses baris sesudahnya di chunk yang sama.
/// State rusak dianggap segar (bukan error).
pub(crate) fn spin_observe(state_json: &str, chunk: &str) -> Result<(Option<String>, Json), String> {
    let mut st = spin_state_fresh_or_parse(state_json);
    if chunk.is_empty() {
        return Ok((None, spin_state_emit(&st)));
    }
    st.pending.push_str(chunk);
    // `split("\n")` Kotlin: pisah `\n` saja (bukan `lines()`).
    // Ambil dulu keluar agar borrow tidak bentrok dengan penulisan
    // `pending` baru di bawah.
    let pending = std::mem::take(&mut st.pending);
    let lines: Vec<&str> = pending.split('\n').collect();
    st.pending = lines.last().copied().unwrap_or("").to_string();
    for raw in &lines[..lines.len().saturating_sub(1)] {
        if let Some(hit) = spin_hit(&mut st, raw) {
            return Ok((Some(hit), spin_state_emit(&st)));
        }
    }
    Ok((None, spin_state_emit(&st)))
}

/// `OcSpinWatchVc303.flush`: `pending` blank -> null (pending TETAP);
/// bila tidak, pending dikosongkan dulu lalu dihitung (norm < 20 ->
/// null, hit hanya bila mencapai 3x). State rusak dianggap segar.
pub(crate) fn spin_flush(state_json: &str) -> Result<(Option<String>, Json), String> {
    let mut st = spin_state_fresh_or_parse(state_json);
    if is_blank(&st.pending) {
        return Ok((None, spin_state_emit(&st)));
    }
    let raw = std::mem::take(&mut st.pending);
    let hit = spin_hit(&mut st, &raw);
    Ok((hit, spin_state_emit(&st)))
}

/// `OcTaskBudgetVc303.overSteps`.
pub(crate) fn budget_over(assistant_messages: i64, max_steps: i64) -> bool {
    assistant_messages > max_steps
}

/// `looksFailedVc262`: 5 penanda (sesudah `lowercase()`).
pub(crate) fn looks_failed(text: &str) -> bool {
    let t = text.to_lowercase();
    t.contains("gagal")
        || t.contains("❌")
        || t.contains("tidak berhasil")
        || t.contains("error")
        || t.contains("tidak selesai")
}

/// `isQuietNow`: spesifikasi kosong -> false; `start-end` tak-valid
/// -> false; rentang normal `start <= h < end`, rentang lewat tengah
/// malam `h >= start || h < end`.
pub(crate) fn quiet_now(spec: &str, hour: i64) -> bool {
    let s = crate::ds_safety_strip::kotlin_trim(spec);
    if s.is_empty() {
        return false;
    }
    let parts: Vec<&str> = s.split('-').collect();
    if parts.len() != 2 {
        return false;
    }
    let start = match java_parse_int_strict(parts[0]) {
        Some(v) => v as i64,
        None => return false,
    };
    let end = match java_parse_int_strict(parts[1]) {
        Some(v) => v as i64,
        None => return false,
    };
    if start <= end {
        hour >= start && hour < end
    } else {
        hour >= start || hour < end
    }
}

/// Jalan pesan asisten (`info.role == "assistant"`, `parts[].type ==
/// "text"`, `trim()`, lewati `"## Objective"`): dipakai
/// `incrementalAssistantTextVc303` (tanpa cap) + `collectResult`
/// (cap 3000 + ctxSize). `baseline` negatif -> mulai 0 (Kotlin:
/// `optJSONObject` negatif -> null -> dilewati).
fn walk_assistant_texts(msgs: &[Json], baseline: i64) -> (Vec<String>, i64) {
    let mut out: Vec<String> = Vec::new();
    let mut ctx_size: i64 = 0;
    let start = baseline.max(0) as usize;
    for m in msgs.iter().skip(start) {
        let info = match m.get("info") {
            Some(o @ Json::Object(_)) => o,
            _ => continue,
        };
        if opt_string(info, "role", "") != "assistant" {
            continue;
        }
        if let Some(tok) = opt_obj(info, "tokens") {
            let cs = opt_long(tok, "input", 0)
                .wrapping_add(opt_obj(tok, "cache").map(|c| opt_long(c, "read", 0)).unwrap_or(0));
            if cs > 0 {
                ctx_size = cs;
            }
        }
        let parts = match opt_arr(m, "parts") {
            Some(p) => p,
            None => continue,
        };
        for part in parts {
            if opt_string(part, "type", "") != "text" {
                continue;
            }
            let t = crate::ds_safety_strip::kotlin_trim(&opt_string(part, "text", "")).to_string();
            if !t.is_empty() && !t.starts_with("## Objective") {
                out.push(t);
            }
        }
    }
    (out, ctx_size)
}

/// `incrementalAssistantTextVc303`: gabung teks asisten sejak
/// baseline dengan `"\n\n"`, TANPA cap 3000.
pub(crate) fn incremental_text(msgs: &[Json], baseline: i64) -> String {
    walk_assistant_texts(msgs, baseline).0.join("\n\n")
}

/// `collectResult`: gabung teks asisten + `ctxSize` (input + cache
/// read terakhir yang > 0); teks dicap `take(3000)` di AKHIR.
pub(crate) fn collect_result(msgs: &[Json], baseline: i64) -> (String, i64) {
    let (parts, ctx_size) = walk_assistant_texts(msgs, baseline);
    (utf16_take(&parts.join("\n\n"), 3000), ctx_size)
}

/// Pindai pesan per poll `awaitCompletion`: jumlah pesan asisten,
/// `finish == "stop"` terlihat, dan error agen (pesan + HTTP code;
/// error TERAKHIR menang — loop menimpa `agentError`).
pub(crate) fn scan_messages(msgs: &[Json], baseline: i64) -> (i64, bool, Option<String>) {
    let mut asst_count: i64 = 0;
    let mut finished_seen = false;
    let mut agent_error: Option<String> = None;
    let start = baseline.max(0) as usize;
    for m in msgs.iter().skip(start) {
        let info = match m.get("info") {
            Some(o @ Json::Object(_)) => o,
            _ => continue,
        };
        if opt_string(info, "role", "") != "assistant" {
            continue;
        }
        asst_count += 1;
        if opt_string(info, "finish", "") == "stop" {
            finished_seen = true;
        }
        if let Some(err_obj) = opt_obj(info, "error") {
            let d = opt_obj(err_obj, "data");
            let msg = match d {
                Some(dd) => {
                    let m = opt_string(dd, "message", "");
                    if m.is_empty() {
                        None
                    } else {
                        Some(m)
                    }
                }
                None => None,
            };
            let msg = match msg {
                Some(m) => m,
                None => {
                    let n = opt_string(err_obj, "name", "");
                    if n.is_empty() {
                        "error tak dikenal".to_string()
                    } else {
                        n
                    }
                }
            };
            let code = d.map(|dd| opt_int(dd, "statusCode", 0)).unwrap_or(0);
            agent_error = Some(if code > 0 {
                format!("{msg} (HTTP {code})")
            } else {
                msg
            });
        }
    }
    (asst_count, finished_seen, agent_error)
}

/// Badan + status akhir `awaitCompletion` (sesudah loop poll).
pub(crate) fn await_body(txt: &str, timed_out: bool, sid: &str) -> (&'static str, String) {
    let body = if is_blank(txt) && timed_out {
        format!("⏱ Timeout tanpa jawaban teks dari agen (sesi {sid}). Cek sesi langsung di serve/Termux.")
    } else if is_blank(txt) {
        format!("(Agen selesai tanpa teks jawaban — kemungkinan semua hasil berupa aksi tool. Cek sesi {sid} di Panel/serve.)")
    } else if timed_out {
        format!("⏱ Timeout — agen dihentikan; hasil parsial:\n{txt}")
    } else {
        txt.to_string()
    };
    (if timed_out { "failed" } else { "done" }, body)
}

/// Status JUJUR `finishTask`: spin -> awalan peringatan + partial;
/// gagal -> failed; isi gagal -> partial; bila tidak status asal.
/// `looksFailed` diterapkan ke hasil SESUDAH awalan spin ditempel.
pub(crate) fn honest_status(
    status: &str,
    spinning: Option<&str>,
    result_full: &str,
) -> (String, String) {
    let mut result = result_full.to_string();
    if let Some(sp) = spinning {
        result = format!(
            "⛔ Hasil koordinator terdeteksi berputar: pola yang sama berulang 3x atau lebih dalam teks hasil (contoh: \"{sp}\") — status SEBAGIAN, periksa hasilnya sebelum dipakai.\n\n{result}"
        );
    }
    let honest = if status == "failed" {
        "failed"
    } else if spinning.is_some() {
        "partial"
    } else if looks_failed(&result) {
        "partial"
    } else {
        status
    };
    (honest.to_string(), result)
}

/// Gerbang lanjutan rantai `runLoop`. Action: "run" |
/// "delay_battery" | "delay_quiet". `note` = teks `EventDb` verbatim.
/// Tidur `delay_sec` + `break` tetap Kotlin.
pub(crate) fn runloop_gate(
    continuation: bool,
    battery_pct: i64,
    batt_min: i64,
    quiet_spec: &str,
    hour: i64,
) -> (&'static str, Option<String>) {
    if !continuation {
        return ("run", None);
    }
    if battery_pct < batt_min {
        return (
            "delay_battery",
            Some(format!("Rantai ditunda: baterai di bawah {batt_min}%")),
        );
    }
    if quiet_now(quiet_spec, hour) {
        return ("delay_quiet", Some("Rantai ditunda: jam hening aktif".to_string()));
    }
    ("run", None)
}

/// Rotasi sesi per rantai `runTask`: buat sesi baru bila `sid` blank
/// atau konteks lama melewati batas. `title` + `rotate_note` hanya
/// bila perlu dibuat.
pub(crate) fn session_rotate(
    sid: &str,
    prev_ctx: i64,
    ctx_limit: i64,
    task_id: i64,
    task_text: &str,
) -> (bool, String, Option<String>) {
    let need = is_blank(sid) || prev_ctx > ctx_limit;
    if !need {
        return (false, String::new(), None);
    }
    let title = format!("🤖 Harness #{task_id} {}", utf16_take(task_text, 42));
    let note = if !is_blank(sid) && prev_ctx > ctx_limit {
        format!("(Sesi dirotasi: konteks sesi sebelumnya {prev_ctx} token melewati batas {ctx_limit} — sesi lama dibiarkan utuh di serve, tidak dihapus.)\n")
    } else {
        String::new()
    };
    (true, note, Some(title))
}

/// Pohon keputusan `ensureServe` dua fase. Fase 0 = probe awal
/// (action "start_4099"/"start_4097" = Kotlin menyalakan serve +
/// tidur 6 dtk + probe ulang -> fase 1). Fase 1 = sesudah nyala.
/// `base_port` null = tidak ada jalur hidup. Action: "return" |
/// "start_4099" | "start_4097" | "fail".
pub(crate) fn serve_select(
    phase: i64,
    mode: &str,
    p4099: bool,
    p4097: bool,
    p4096: bool,
) -> (Option<i64>, String, &'static str) {
    if phase == 0 {
        if mode == "mandiri" {
            if p4099 {
                return (Some(4099), String::new(), "return");
            }
            return (None, String::new(), "start_4099");
        }
        if p4097 {
            return (Some(4097), String::new(), "return");
        }
        if p4096 {
            return (Some(4096), String::new(), "return");
        }
        return (None, String::new(), "start_4097");
    }
    // Fase 1.
    if mode == "mandiri" {
        if p4099 {
            return (
                Some(4099),
                "(Serve mandiri 4099 dinyalakan harness.)".to_string(),
                "return",
            );
        }
        if p4097 {
            return (
                Some(4097),
                "(Serve mandiri gagal — fallback jalur resmi 4097.)".to_string(),
                "return",
            );
        }
        if p4096 {
            return (
                Some(4096),
                "(Serve mandiri gagal — fallback jalur resmi 4096.)".to_string(),
                "return",
            );
        }
        return (
            None,
            "serve mandiri 4099 gagal dinyalakan dan jalur resmi (4097/4096) mati semua.".to_string(),
            "fail",
        );
    }
    if p4097 {
        return (
            Some(4097),
            "(Serve 4097 dinyalakan harness via Termux.)".to_string(),
            "return",
        );
    }
    if p4096 {
        return (
            Some(4096),
            "(Serve 4096 dinyalakan harness via Termux.)".to_string(),
            "return",
        );
    }
    (
        None,
        "tidak ada opencode serve yang hidup (4097/4096 mati; upaya menyalakan via Termux gagal). Nyalakan dari Setup tombol 34 atau buka Termux.".to_string(),
        "fail",
    )
}

/// `probeCached` (baca): cache < 20 dtk + raw non-blank -> parse
/// `k=v,k=v` (key `toIntOrNull`, nilai `"1"` = hidup). Raw blank /
/// force / kedaluwarsa -> hit false (Kotlin probe ulang + tulis).
pub(crate) fn probe_cache_read(
    force: bool,
    now_ms: i64,
    cached_ts: i64,
    cached_raw: &str,
) -> (bool, Json) {
    if !force && now_ms - cached_ts < 20_000 && !cached_raw.is_empty() {
        let mut map = serde_json::Map::new();
        for kv in cached_raw.split(',') {
            let parts: Vec<&str> = kv.split('=').collect();
            if parts.len() == 2 {
                if let Some(k) = java_parse_int_strict(parts[0]) {
                    map.insert(k.to_string(), Json::Bool(parts[1] == "1"));
                }
            }
        }
        return (true, Json::Object(map));
    }
    (false, Json::Null)
}

/// Masukan `statusText`: semua nilai sudah dibaca Kotlin.
pub(crate) struct StatusTextIn {
    pub mode: String,
    pub p4097: bool,
    pub p4096: bool,
    pub p4099: bool,
    pub cur_task: i64,
    pub queued: i64,
    pub running: i64,
    pub pending_title: String,
    pub rules: Json,
    pub gates_disabled: bool,
    pub recent: Vec<Json>,
}

/// `statusText`: perakit teks panel (aturan hilang -> `"null"`,
/// seperti template Kotlin atas `Map` yang tak punya key itu).
pub(crate) fn status_text(st: &StatusTextIn) -> String {
    let rule = |k: &str| -> String {
        match st.rules.get(k) {
            None => "null".to_string(),
            Some(_) => opt_raw_string(&st.rules, k).unwrap_or_else(|| "null".to_string()),
        }
    };
    let on = |b: bool| if b { "HIDUP" } else { "MATI" };
    let mut sb = String::new();
    sb.push_str(&format!(
        "Jalur mode: {} • 4097 (resmi): {} • 4096 (cadangan): {} • 4099 (mandiri): {}\n",
        st.mode,
        on(st.p4097),
        on(st.p4096),
        on(st.p4099)
    ));
    sb.push_str(&format!(
        "Antrean: {} menunggu • {} berjalan",
        st.queued, st.running
    ));
    if st.cur_task > 0 {
        sb.push_str(&format!(" (tugas #{})", st.cur_task));
    }
    sb.push('\n');
    if !is_blank(&st.pending_title) {
        sb.push_str(&format!(
            "⏳ IZIN MENUNGGU KEPUTUSAN: {} — setujui/tolak dari notifikasi atau tombol di panel ini.\n",
            st.pending_title
        ));
    }
    sb.push_str(&format!(
        "Aturan: batas konteks sesi {} token • rantai maks {} tugas • baterai min rantai {}% • jam hening '{}' • auto-approve '{}'\n",
        rule("session_ctx_limit"),
        rule("chain_max"),
        rule("chain_batt_min"),
        rule("quiet_hours"),
        rule("auto_approve")
    ));
    if st.gates_disabled {
        sb.push_str("Izin koordinator disetujui otomatis (gate nonaktif) — tiap izin tercatat di jejak audit.\n");
    }
    if !st.recent.is_empty() {
        sb.push_str("Tugas terakhir:\n");
        for t in &st.recent {
            sb.push_str(&format!(
                "- #{} [{}] {}",
                opt_long(t, "id", 0),
                opt_string(t, "status", ""),
                utf16_take(&opt_string(t, "text", ""), 60)
            ));
            let res = opt_string(t, "result", "");
            if !is_blank(&res) {
                sb.push_str(&format!(
                    " → {}",
                    utf16_take(&res.replace('\n', " "), 90)
                ));
            }
            sb.push('\n');
        }
    }
    crate::ds_safety_strip::kotlin_trim(&sb).to_string()
}

/// `taskResultText`: `task` null -> "tidak ditemukan"; done/failed/
/// cancelled -> label + hasil; lain -> status + intipan progres
/// (peek sudah dihitung Kotlin — network tetap di sana).
pub(crate) fn task_result_text(id: i64, task: &Json, peek: &str) -> String {
    if matches!(task, Json::Null) {
        return format!("Tugas #{id} tidak ditemukan.");
    }
    let status = opt_string(task, "status", "");
    let text = opt_string(task, "text", "");
    let result = opt_string(task, "result", "");
    let session_id = opt_string(task, "sessionId", "");
    let head = format!(
        "Tugas #{id} [{status}] — {}",
        utf16_take(&text, 120)
    );
    match status.as_str() {
        "done" => format!("{head}\nHASIL:\n{result}"),
        "failed" => format!("{head}\nGAGAL: {result}"),
        "cancelled" => format!("{head}\nDibatalkan. Catatan: {result}"),
        _ => {
            let mut sb = format!("{head}\nMasih {status}.");
            if !is_blank(&session_id) && !peek.is_empty() {
                sb.push_str(&format!("\nProgres terakhir agen: {peek}"));
            }
            sb
        }
    }
}

/// `peekSessionText`: teks asisten TERAKHIR (tiap `type == "text"`,
/// `trim()`, non-blank -> ganti `last`), dicap take 700.
pub(crate) fn peek_text(msgs: &[Json]) -> String {
    let mut last = String::new();
    for m in msgs {
        let info = match m.get("info") {
            Some(o @ Json::Object(_)) => o,
            _ => continue,
        };
        if opt_string(info, "role", "") != "assistant" {
            continue;
        }
        let parts = match opt_arr(m, "parts") {
            Some(p) => p,
            None => continue,
        };
        for part in parts {
            if opt_string(part, "type", "") != "text" {
                continue;
            }
            let t = crate::ds_safety_strip::kotlin_trim(&opt_string(part, "text", "")).to_string();
            if !t.is_empty() {
                last = t;
            }
        }
    }
    utf16_take(&last, 700)
}

/// SATU baris SSE izin (watcher): `data:` + JSON + `type` mengandung
/// `"permission"` + `sessionID` cocok + `id` non-blank. Judul:
/// `title` -> `pattern` -> `"izin tool OpenCode"` (take 300).
/// Detail vc260: 5 field `k=v` yang nilainya non-blank, gabung
/// `" | "`, take 2000.
pub(crate) fn parse_perm_event(line: &str, sid: &str) -> Option<(String, String, String)> {
    if !line.starts_with("data:") {
        return None;
    }
    let payload = crate::ds_safety_strip::kotlin_trim(&line[5..]);
    if payload.is_empty() {
        return None;
    }
    let obj: Json = serde_json::from_str(payload).ok()?;
    if !opt_string(&obj, "type", "").contains("permission") {
        return None;
    }
    let props = opt_obj(&obj, "properties")?;
    if opt_string(props, "sessionID", "") != sid {
        return None;
    }
    let perm_id = opt_string(props, "id", "");
    if is_blank(&perm_id) {
        return None;
    }
    let mut title = opt_string(props, "title", "");
    if is_blank(&title) {
        title = opt_string(props, "pattern", "");
    }
    if is_blank(&title) {
        title = "izin tool OpenCode".to_string();
    }
    let title = utf16_take(&title, 300);
    let nested = opt_obj(props, "permission")
        .map(|v| v.to_string())
        .unwrap_or_default();
    let fields: Vec<String> = vec![
        format!("tool={}", opt_string(props, "tool", "")),
        format!("pattern={}", opt_string(props, "pattern", "")),
        format!("command={}", opt_string(props, "command", "")),
        format!("args={}", opt_raw_string(props, "args").unwrap_or_default()),
        format!("permission={nested}"),
    ];
    let detail = fields
        .iter()
        .filter(|f| !substring_after_eq(f).is_empty())
        .cloned()
        .collect::<Vec<_>>()
        .join(" | ");
    Some((perm_id, title, utf16_take(&detail, 2000)))
}

/// Basi izin: `ts > 0 && now - ts > ttlMin * 60_000`.
pub(crate) fn perm_stale(ts: i64, now_ms: i64, ttl_min: i64) -> bool {
    ts > 0 && now_ms - ts > ttl_min.wrapping_mul(60_000)
}

/// Keputusan izin `handlePermission`: "auto_gate" | "auto_pattern" |
/// "park". Dua varian auto dibedakan agar Kotlin menulis pesan log
/// yang tepat ("gate vc247 nonaktif" vs "pola aturan").
pub(crate) fn perm_decision(gates_disabled: bool, auto_approve: &str, title: &str) -> &'static str {
    if gates_disabled {
        return "auto_gate";
    }
    let patterns: Vec<&str> = auto_approve
        .split(',')
        .map(|p| crate::ds_safety_strip::kotlin_trim(p))
        .filter(|p| !p.is_empty())
        .collect();
    if patterns
        .iter()
        .any(|p| *p == "*" || java_contains_ignore_case(title, p))
    {
        return "auto_pattern";
    }
    "park"
}

/// `batteryPct`: `level >= 0 && scale > 0` -> `level*100/scale`
/// (wrapping, ala Int Kotlin), bila tidak 100.
pub(crate) fn battery_pct(level: i32, scale: i32) -> i32 {
    if level >= 0 && scale > 0 {
        level.wrapping_mul(100).wrapping_div(scale)
    } else {
        100
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn msgs_sample() -> Vec<Json> {
        serde_json::from_str(
            r###"[
              {"info": {"role": "user", "tokens": {"input": 5}}, "parts": [{"type": "text", "text": " halo "}]},
              {"info": {"role": "assistant", "tokens": {"input": 100, "cache": {"read": 20}}, "finish": "stop"}, "parts": [{"type": "text", "text": "  Jawaban satu  "}, {"type": "text", "text": "## Objective abaikan"}, {"type": "tool", "text": "x"}]},
              {"info": {"role": "assistant", "tokens": {"input": 150}}, "parts": [{"type": "text", "text": "Jawaban dua"}]},
              {"info": {"role": "assistant", "error": {"name": "Boom", "data": {"message": "Endpoint is unavailable", "statusCode": 402}}}, "parts": []}
            ]"###,
        )
        .unwrap()
    }

    #[test]
    fn result_notif_id_wraps() {
        assert_eq!((40_000i64.wrapping_add(5)) as i32, 40005);
        assert_eq!((40_000i64.wrapping_add(3_000_000_000)) as i32, -1294927296);
    }

    #[test]
    fn rule_defaults_order() {
        let d = rule_defaults();
        assert_eq!(d.len(), 10);
        assert_eq!(d[0], ("path_mode", "resmi"));
        assert_eq!(d[9], ("oc_task_max_steps_vc303", "200"));
    }

    #[test]
    fn sanitize_and_rule_int() {
        assert_eq!(sanitize_rule("path_mode", " MANDIRI "), "mandiri");
        assert_eq!(sanitize_rule("path_mode", "aneh"), "resmi");
        assert_eq!(sanitize_rule("path_mode", ""), "resmi");
        assert_eq!(sanitize_rule("auto_approve", "ON"), "");
        assert_eq!(sanitize_rule("auto_approve", "Aktif"), "");
        // RAW tanpa trim dipertahankan bila bukan kata kunci.
        assert_eq!(sanitize_rule("auto_approve", " edit, hapus "), " edit, hapus ");
        assert_eq!(sanitize_rule("chain_max", " 7 "), " 7 ");
        assert_eq!(rule_int("chain_max", "7", 3), 7);
        assert_eq!(rule_int("chain_max", "20.0", 3), 3);
        assert_eq!(rule_int("chain_max", "", 3), 3);
        assert_eq!(rule_int("task_timeout_min", "+25", 20), 25);
    }

    #[test]
    fn spinning_pattern_matrix() {
        let line = "Mengerjakan langkah penting nomor 1 dengan teliti";
        let text = format!("{line} 1\nacak\n{line} 2\n{line} 3\n");
        // firstSeen = baris mentah PERTAMA (termasuk " 1" — angka hanya
        // dihapus di NORM, bukan di cuplikan).
        assert_eq!(spinning_pattern(&text).as_deref(), Some(format!("{line} 1").as_str()));
        assert!(spinning_pattern("satu\ndua\ntiga").is_none());
        // Baris pendek diabaikan.
        assert!(spinning_pattern("ok\nok\nok\n").is_none());
        // Baru 2x -> belum.
        let text2 = format!("{line} 1\n{line} 2\n");
        assert!(spinning_pattern(&text2).is_none());
    }

    #[test]
    fn spin_watch_observe_flush() {
        let empty = spin_state_emit(&SpinState { counts: HashMap::new(), first_seen: HashMap::new(), pending: String::new() }).to_string();
        let line = "Mengerjakan langkah penting nomor 1 dengan teliti";
        // Chunk kosong -> null, state utuh.
        let (h0, s0) = spin_observe(&empty, "").unwrap();
        assert!(h0.is_none());
        assert_eq!(s0.to_string(), empty);
        // Dua baris sama dalam satu chunk (tanpa newline akhir -> pending).
        let chunk = format!("{line} 1\n{line} 2\n");
        let (h1, s1) = spin_observe(&empty, &chunk).unwrap();
        assert!(h1.is_none());
        // Baris ketiga tiba -> hit (cuplikan baris PERTAMA, "{line} 1").
        let (h2, _) = spin_observe(&s1.to_string(), &format!("{line} 3\n")).unwrap();
        assert_eq!(h2.as_deref(), Some(format!("{line} 1").as_str()));
        // Flush: pending blank -> null; pending pendek -> null tapi pending bersih.
        let st = SpinState { counts: HashMap::new(), first_seen: HashMap::new(), pending: "pendek".to_string() };
        let sj = spin_state_emit(&st).to_string();
        let (hf, sf) = spin_flush(&sj).unwrap();
        assert!(hf.is_none());
        assert_eq!(sf.get("pending").and_then(|v| v.as_str()), Some(""));
        // Flush baris panjang ke-3 (pending tanpa newline) -> hit.
        let st3 = SpinState { counts: HashMap::new(), first_seen: HashMap::new(), pending: String::new() };
        let (_, s3a) = spin_observe(&spin_state_emit(&st3).to_string(), &format!("{line} 1\n{line} 2\n")).unwrap();
        let mut st3b = spin_state_parse(&s3a.to_string()).unwrap();
        st3b.pending = format!("{line} 3");
        let (hf3, _) = spin_flush(&spin_state_emit(&st3b).to_string()).unwrap();
        assert_eq!(hf3.as_deref(), Some(format!("{line} 1").as_str()));
    }

    #[test]
    fn spin_watch_corrupt_state_is_fresh() {
        // State rusak (bukan JSON) dianggap segar: observe tetap hit di
        // kemunculan ke-3, flush pada state rusak -> null (pending kosong).
        let line = "Mengerjakan langkah penting nomor 1 dengan teliti";
        let chunk = format!("{line} 1\n{line} 2\n{line} 3\n");
        let (h, _) = spin_observe("bukan json", &chunk).unwrap();
        assert_eq!(h.as_deref(), Some(format!("{line} 1").as_str()));
        let (hf, sf) = spin_flush("bukan json").unwrap();
        assert!(hf.is_none());
        assert_eq!(sf.get("pending").and_then(|v| v.as_str()), Some(""));
    }

    #[test]
    fn looks_failed_quiet_now_battery() {
        assert!(looks_failed("Tugas GAGAL total"));
        assert!(looks_failed("ada ❌ di sini"));
        assert!(looks_failed("Error koneksi"));
        assert!(looks_failed("tidak selesai semua"));
        assert!(!looks_failed("semua beres"));
        assert!(quiet_now("22-5", 23));
        assert!(quiet_now("22-5", 3));
        assert!(!quiet_now("22-5", 12));
        assert!(quiet_now("9-17", 10));
        assert!(!quiet_now("9-17", 17));
        assert!(!quiet_now("", 23));
        assert!(!quiet_now("22", 23));
        assert!(!quiet_now("a-b", 23));
        assert_eq!(battery_pct(50, 100), 50);
        assert_eq!(battery_pct(-1, 100), 100);
        assert_eq!(battery_pct(50, 0), 100);
    }

    #[test]
    fn collect_and_incremental() {
        let msgs = msgs_sample();
        assert_eq!(incremental_text(&msgs, 0), "Jawaban satu\n\nJawaban dua");
        let (txt, ctx) = collect_result(&msgs, 0);
        assert_eq!(txt, "Jawaban satu\n\nJawaban dua");
        assert_eq!(ctx, 150); // terakhir > 0 menang
        // Baseline 2 -> hanya pesan ke-3 ("Jawaban dua"); baseline 3 -> kosong.
        assert_eq!(incremental_text(&msgs, 2), "Jawaban dua");
        let (txt2, ctx2) = collect_result(&msgs, 2);
        assert_eq!(txt2, "Jawaban dua");
        assert_eq!(ctx2, 150);
        assert_eq!(incremental_text(&msgs, 3), "");
        let (txt3, _) = collect_result(&msgs, 3);
        assert_eq!(txt3, "");
    }

    #[test]
    fn scan_messages_matrix() {
        let msgs = msgs_sample();
        let (n, fin, err) = scan_messages(&msgs, 0);
        assert_eq!(n, 3);
        assert!(fin);
        assert_eq!(err.as_deref(), Some("Endpoint is unavailable (HTTP 402)"));
        let (n2, fin2, err2) = scan_messages(&msgs, 3);
        assert_eq!(n2, 1);
        assert!(!fin2);
        assert_eq!(err2.as_deref(), Some("Endpoint is unavailable (HTTP 402)"));
    }

    #[test]
    fn await_body_and_honest_status() {
        assert_eq!(await_body("", true, "s1").0, "failed");
        assert!(await_body("", true, "s1").1.contains("Timeout tanpa jawaban"));
        assert_eq!(await_body("", false, "s1").0, "done");
        assert!(await_body("", false, "s1").1.contains("selesai tanpa teks"));
        assert_eq!(await_body("isi", true, "s1").1, "⏱ Timeout — agen dihentikan; hasil parsial:\nisi");
        assert_eq!(await_body("isi", false, "s1").1, "isi");
        // Spin -> partial + awalan.
        let (st, res) = honest_status("done", Some("pola x"), "hasil oke");
        assert_eq!(st, "partial");
        assert!(res.starts_with("⛔ Hasil koordinator terdeteksi berputar"));
        // Isi gagal -> partial.
        assert_eq!(honest_status("done", None, "tugas gagal total").0, "partial");
        assert_eq!(honest_status("failed", None, "x").0, "failed");
        assert_eq!(honest_status("done", None, "beres").0, "done");
    }

    #[test]
    fn runloop_gate_and_session_rotate() {
        assert_eq!(runloop_gate(false, 10, 20, "", 12).0, "run");
        assert_eq!(runloop_gate(true, 10, 20, "", 12).0, "delay_battery");
        assert_eq!(
            runloop_gate(true, 10, 20, "", 12).1.as_deref(),
            Some("Rantai ditunda: baterai di bawah 20%")
        );
        assert_eq!(runloop_gate(true, 90, 20, "22-5", 23).0, "delay_quiet");
        assert_eq!(runloop_gate(true, 90, 20, "22-5", 12).0, "run");
        let (need, note, title) = session_rotate("", 0, 350000, 7, "kerjakan laporan keuangan bulanan dengan teliti dan lengkap sekali");
        assert!(need);
        assert_eq!(note, "");
        assert!(title.unwrap().starts_with("🤖 Harness #7 kerjakan laporan keuangan bulanan dengan"));
        let (need2, note2, _) = session_rotate("s1", 400000, 350000, 7, "x");
        assert!(need2);
        assert!(note2.contains("Sesi dirotasi"));
        assert!(!session_rotate("s1", 100, 350000, 7, "x").0);
    }

    #[test]
    fn serve_select_phases() {
        // Resmi fase 0: 4097 hidup.
        assert_eq!(serve_select(0, "resmi", false, true, false), (Some(4097), String::new(), "return"));
        // Resmi fase 0: mati semua -> nyalakan 4097.
        assert_eq!(serve_select(0, "resmi", false, false, false).2, "start_4097");
        // Resmi fase 1: 4096 hidup sesudah start.
        assert_eq!(
            serve_select(1, "resmi", false, false, true),
            (Some(4096), "(Serve 4096 dinyalakan harness via Termux.)".to_string(), "return")
        );
        // Resmi fase 1: tetap mati.
        assert_eq!(serve_select(1, "resmi", false, false, false).2, "fail");
        // Mandiri fase 0: 4099 hidup.
        assert_eq!(serve_select(0, "mandiri", true, false, false), (Some(4099), String::new(), "return"));
        // Mandiri fase 1: 4099 hidup sesudah start.
        assert_eq!(
            serve_select(1, "mandiri", true, false, false).1,
            "(Serve mandiri 4099 dinyalakan harness.)"
        );
        // Mandiri fase 1: fallback 4097.
        assert_eq!(
            serve_select(1, "mandiri", false, true, false).1,
            "(Serve mandiri gagal — fallback jalur resmi 4097.)"
        );
    }

    #[test]
    fn probe_cache_read_write() {
        let (hit, probes) = probe_cache_read(false, 15_000, 0, "4097=1,4096=0,4099=1");
        assert!(hit);
        assert_eq!(probes.get("4097").and_then(|v| v.as_bool()), Some(true));
        assert_eq!(probes.get("4096").and_then(|v| v.as_bool()), Some(false));
        // Kedaluwarsa / force / raw kosong -> miss.
        assert!(!probe_cache_read(false, 25_000, 0, "4097=1").0);
        assert!(!probe_cache_read(true, 1000, 0, "4097=1").0);
        assert!(!probe_cache_read(false, 1000, 0, "").0);
        // Entri rusak dilewati.
        let (hit2, p2) = probe_cache_read(false, 1000, 0, "4097=1,rusak,4096=x");
        assert!(hit2);
        assert_eq!(p2.get("4096").and_then(|v| v.as_bool()), Some(false));
    }

    #[test]
    fn status_text_assembly() {
        let st = StatusTextIn {
            mode: "resmi".to_string(),
            p4097: true,
            p4096: false,
            p4099: false,
            cur_task: 5,
            queued: 2,
            running: 1,
            pending_title: "".to_string(),
            rules: serde_json::json!({"session_ctx_limit": "350000", "chain_max": "3", "chain_batt_min": "20", "quiet_hours": "22-5", "auto_approve": ""}),
            gates_disabled: true,
            recent: vec![serde_json::json!({"id": 9, "status": "done", "text": "tugas penting sekali", "result": "hasil\nbaris dua"})],
        };
        let out = status_text(&st);
        assert!(out.starts_with("Jalur mode: resmi • 4097 (resmi): HIDUP • 4096 (cadangan): MATI • 4099 (mandiri): MATI"));
        assert!(out.contains("Antrean: 2 menunggu • 1 berjalan (tugas #5)"));
        assert!(out.contains("batas konteks sesi 350000 token • rantai maks 3 tugas • baterai min rantai 20% • jam hening '22-5' • auto-approve ''"));
        assert!(out.contains("Izin koordinator disetujui otomatis (gate nonaktif)"));
        assert!(out.contains("- #9 [done] tugas penting sekali → hasil baris dua"));
        // Aturan hilang -> "null".
        let mut st2 = StatusTextIn {
            mode: "resmi".to_string(),
            p4097: false,
            p4096: false,
            p4099: false,
            cur_task: 0,
            queued: 0,
            running: 0,
            pending_title: "Izin hapus".to_string(),
            rules: serde_json::json!({}),
            gates_disabled: false,
            recent: vec![],
        };
        let out2 = status_text(&st2);
        assert!(out2.contains("batas konteks sesi null token"));
        assert!(out2.contains("⏳ IZIN MENUNGGU KEPUTUSAN: Izin hapus"));
        let _ = &mut st2;
    }

    #[test]
    fn task_result_and_peek() {
        assert_eq!(
            task_result_text(3, &Json::Null, ""),
            "Tugas #3 tidak ditemukan."
        );
        let t = serde_json::json!({"status": "done", "text": "kerjakan x", "result": "beres", "sessionId": ""});
        assert_eq!(task_result_text(3, &t, ""), "Tugas #3 [done] — kerjakan x\nHASIL:\nberes");
        let t2 = serde_json::json!({"status": "running", "text": "kerjakan x", "result": "", "sessionId": "s9"});
        assert_eq!(
            task_result_text(3, &t2, "progres 50%"),
            "Tugas #3 [running] — kerjakan x\nMasih running.\nProgres terakhir agen: progres 50%"
        );
        let msgs = msgs_sample();
        assert_eq!(peek_text(&msgs), "Jawaban dua");
        assert_eq!(peek_text(&[]), "");
    }

    #[test]
    fn parse_perm_event_matrix() {
        let line = r#"data: {"type": "permission_request", "properties": {"sessionID": "s1", "id": "p1", "title": "Izin hapus", "tool": "hapus", "pattern": "", "command": "rm -rf", "args": "x", "permission": {"a": 1}}}"#;
        let (pid, title, detail) = parse_perm_event(line, "s1").unwrap();
        assert_eq!(pid, "p1");
        assert_eq!(title, "Izin hapus");
        assert!(detail.contains("tool=hapus"));
        assert!(detail.contains("command=rm -rf"));
        assert!(detail.contains("args=x"));
        assert!(!detail.contains("pattern=")); // kosong -> dibuang
        assert!(detail.contains("permission="));
        // Bukan data: / tipe lain / sid beda / id kosong -> null.
        assert!(parse_perm_event("event: x", "s1").is_none());
        assert!(parse_perm_event(r#"data: {"type": "other", "properties": {}}"#, "s1").is_none());
        assert!(parse_perm_event(r#"data: {"type": "permission", "properties": {"sessionID": "s2", "id": "p1"}}"#, "s1").is_none());
        assert!(parse_perm_event(r#"data: {"type": "permission", "properties": {"sessionID": "s1", "id": "  "}}"#, "s1").is_none());
        // Judul fallback: pattern -> default.
        let line2 = r#"data: {"type": "permission", "properties": {"sessionID": "s1", "id": "p2", "pattern": "edit *"}}"#;
        assert_eq!(parse_perm_event(line2, "s1").unwrap().1, "edit *");
        let line3 = r#"data: {"type": "permission", "properties": {"sessionID": "s1", "id": "p3"}}"#;
        assert_eq!(parse_perm_event(line3, "s1").unwrap().1, "izin tool OpenCode");
    }

    #[test]
    fn perm_stale_and_decision() {
        assert!(perm_stale(1000, 1000 + 30 * 60_000 + 1, 30));
        assert!(!perm_stale(1000, 1000 + 30 * 60_000, 30));
        assert!(!perm_stale(0, 999_999_999, 30));
        assert_eq!(perm_decision(true, "", "apa saja"), "auto_gate");
        assert_eq!(perm_decision(false, "edit, hapus", "Izin EDIT file"), "auto_pattern");
        assert_eq!(perm_decision(false, "*", "apa saja"), "auto_pattern");
        assert_eq!(perm_decision(false, "hapus", "Izin baca"), "park");
        assert_eq!(perm_decision(false, "", "x"), "park");
    }
}
