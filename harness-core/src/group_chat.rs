//! Slice 12 (vc329) — logika murni `GroupChat.kt` (room/grup).
//!
//! Yang dipindah HANYA logika murni; yang tetap Kotlin: `SlotClient`/
//! `DsApiClient` + thread ronde, `MemoryDb`/prefs, listener callback,
//! `Tools.execute`, dialog approval (`CountDownLatch`), notifikasi,
//! reviewer konteks-nol, `WorkNotesVc301`, `AgentPrompt` (kecuali
//! jangkar delta yang sudah Rust slice 6 — dipakai ulang di sini).
//!
//! PERHATIAN vc321 (IXL): penghematan room DIPERTAHANKAN — katalog
//! tetap `selectCatalog` + backstop 30.000 DI KOTLIN (dicek P7/D12);
//! op `build_prompt` menerima katalog YANG SUDAH dicap sebagai input,
//! TIDAK PERNAH membangun katalog penuh dari Rust.
//!
//! Op:
//! - `parse_mentions` — `@mention` (urutan kemunculan; `@agen 4`/`@agen4`).
//! - `member_info` — `personaNameFor` + `accentFor` (default `—`/`#4d8cff`).
//! - `select_queue` — pemilihan antrean ronde (allOn/mention/ketua) +
//!   catatan nonaktif (teks verbatim).
//! - `chair_hop` — hop ketua: balasan ketua yang menyebut agen lain.
//! - `slot_resolve` — slot terikat vs fallback slot bebas.
//! - `build_prompt` — perakit prompt penuh room (semua blok sebagai
//!   input string yang sudah dihitung Kotlin — termasuk redact).
//! - `continuation_prompt_room` — delta room vc321 (jangkar + suffix).
//! - `honest_cap_stop_group` — `honestCapStopGrpVc277` lokal.
//! - `retrieve_memories_filter` — saring baris retrieval (take 10x1000).
//! - `quiet_hour_group` — jam hening grup 23:00-05:00.
//! - `bg_round_gate` — gerbang `runBackgroundRound`.
//! - `group_tool_route` — keputusan `runGroupTool` (analyze_photo /
//!   GATES_DISABLED / shortcut auto-approve / dialog).
//! - `save_last_round_plan` — `saveLastRoundAsPlan` (dedup + cap 4000).
//! - `parse_plans` — `groupPlans` (parse + sort desc ts, stabil).
//! - `render_notif_3lines` — render notifikasi grup (3 baris + sisa).

#![forbid(unsafe_code)]

use crate::agent_prompt::continuation_anchor_vc316;
use crate::bg_room_common::*;
use serde_json::Value as Json;

pub fn group_chat_json(req_str: &str) -> Result<String, String> {
    Ok(dispatch(req_str).unwrap_or_else(|e| err_json(&e)))
}

fn dispatch(req_str: &str) -> Result<String, String> {
    let (req, op) = req_op(req_str)?;
    match op.as_str() {
        "parse_mentions" => {
            let text = get_str(&req, "text")?;
            let members = members_of(&req)?;
            let ids = parse_mentions(text, &members);
            Ok(ok_json(Json::Array(
                ids.into_iter().map(Json::String).collect(),
            )))
        }
        "member_info" => {
            let members = members_of(&req)?;
            let id = get_str(&req, "id")?;
            let name = get_str(&req, "name")?;
            let (persona_name, accent) = member_info(&members, id, name);
            Ok(ok_json(serde_json::json!({"persona_name": persona_name, "accent": accent})))
        }
        "select_queue" => {
            let members = members_of(&req)?;
            let mentioned = str_array(&req, "mentioned")?;
            let all_on = get_bool_d(&req, "all_on", false);
            let active = str_array(&req, "active")?;
            let (queue, notes, chair_id) = select_queue(&mentioned, all_on, &active, &members);
            Ok(ok_json(serde_json::json!({
                "queue": queue, "notes": notes, "chair_id": chair_id,
            })))
        }
        "chair_hop" => {
            let members = members_of(&req)?;
            let clean = get_str(&req, "clean")?;
            let all_on = get_bool_d(&req, "all_on", false);
            let mentioned_empty = get_bool_d(&req, "mentioned_empty", true);
            let is_chair = get_bool_d(&req, "is_chair", false);
            let bubbles = get_i64(&req, "bubbles", 0)?;
            let max_bubbles = get_i64(&req, "max_bubbles", 6)?;
            let responded = str_array(&req, "responded")?;
            let queue = str_array(&req, "queue")?;
            let invited = chair_hop(
                clean, all_on, mentioned_empty, is_chair, bubbles, max_bubbles, &responded,
                &queue, &members,
            );
            Ok(ok_json(Json::Array(
                invited.into_iter().map(Json::String).collect(),
            )))
        }
        "slot_resolve" => {
            let bound = match req.get("bound") {
                None | Some(Json::Null) => None,
                Some(Json::Number(n)) => Some(
                    n.as_i64()
                        .ok_or_else(|| "bound out of range".to_string())?,
                ),
                Some(_) => return Err("bound must be a number or null".to_string()),
            };
            let used = int_array(&req, "used")?;
            let filled = int_array(&req, "filled")?;
            let persona = &req;
            let slot = slot_resolve(bound, &used, &filled, persona);
            Ok(ok_json(match slot {
                Some(s) => Json::from(s),
                None => Json::Null,
            }))
        }
        "build_prompt" => {
            let p = BuildPromptIn {
                me_name: get_str(&req, "me_name")?.to_string(),
                persona: get_str_d(&req, "persona").to_string(),
                behaviors_block: get_str_d(&req, "behaviors_block").to_string(),
                brain_blocks: get_str_d(&req, "brain_blocks").to_string(),
                ctx_label_rules: get_str_d(&req, "ctx_label_rules").to_string(),
                paging_total_rule: get_str_d(&req, "paging_total_rule").to_string(),
                pinned: get_str_d(&req, "pinned").to_string(),
                world_state_line: get_str_d(&req, "world_state_line").to_string(),
                memories: str_array(&req, "memories")?,
                facts: get_str_d(&req, "facts").to_string(),
                goals: get_str_d(&req, "goals").to_string(),
                work_notes: get_str_d(&req, "work_notes").to_string(),
                tools_enabled: get_bool_d(&req, "tools_enabled", false),
                cat_slim: get_str_d(&req, "cat_slim").to_string(),
                skills: get_str_d(&req, "skills").to_string(),
                mode_bebas_block: get_str_d(&req, "mode_bebas_block").to_string(),
                batch_terminal_rule: get_str_d(&req, "batch_terminal_rule").to_string(),
                transcript_rows: str_array(&req, "transcript_rows")?,
                user_text: get_str(&req, "user_text")?.to_string(),
            };
            Ok(ok_json(Json::String(build_prompt(&p))))
        }
        "continuation_prompt_room" => {
            let user_text = get_str(&req, "user_text")?;
            let suffix = get_str(&req, "suffix")?;
            Ok(ok_json(Json::String(format!(
                "{}\n\n{suffix}",
                continuation_anchor_vc316(user_text)
            ))))
        }
        "honest_cap_stop_group" => {
            let done = get_str(&req, "done")?;
            let user_text300 = get_str(&req, "user_text300")?;
            Ok(ok_json(Json::String(format!(
                "berhenti di tengah pada langkah 3 dari tugas: {user_text300} — tiap anggota maks 3 langkah tool per ronde. Ini yang sudah selesai: {done}; ini yang belum: sisa tugas belum dikerjakan dalam ronde ini — lanjutkan di ronde berikutnya atau minta anggota lain."
            ))))
        }
        "retrieve_memories_filter" => {
            let raw = get_str(&req, "raw")?;
            let out = retrieve_memories_filter(raw);
            Ok(ok_json(Json::Array(
                out.into_iter().map(Json::String).collect(),
            )))
        }
        "quiet_hour_group" => {
            let hour = get_i64(&req, "hour", 0)?;
            Ok(ok_json(Json::Bool(hour >= 23 || hour < 5)))
        }
        "bg_round_gate" => {
            let running = get_bool_d(&req, "running", false);
            let f1_off = get_bool_d(&req, "f1_off", false);
            let jobs_off = get_bool_d(&req, "jobs_off", false);
            let hour = get_i64(&req, "hour", 0)?;
            let battery_ok = get_bool_d(&req, "battery_ok", true);
            let (decision, reason, job_status) =
                bg_round_gate(running, f1_off, jobs_off, hour, battery_ok);
            Ok(ok_json(serde_json::json!({
                "decision": decision, "reason": reason, "job_status": job_status,
            })))
        }
        "group_tool_route" => {
            let tool = get_str(&req, "tool")?;
            let gates_disabled = get_bool_d(&req, "gates_disabled", false);
            let is_memory_write = get_bool_d(&req, "is_memory_write", false);
            let is_auto_allow_blocked = get_bool_d(&req, "is_auto_allow_blocked", false);
            let auto_approve_on = get_bool_d(&req, "auto_approve_on", false);
            let is_risky = get_bool_d(&req, "is_risky", false);
            let auto_allow_valid = get_bool_d(&req, "auto_allow_valid", false);
            let (route, message) = group_tool_route(
                tool, gates_disabled, is_memory_write, is_auto_allow_blocked, auto_approve_on,
                is_risky, auto_allow_valid,
            );
            Ok(ok_json(serde_json::json!({"route": route, "message": message})))
        }
        "save_last_round_plan" => {
            let rows = obj_array(&req, "rows")?;
            let plans = obj_array(&req, "plans")?;
            let now_ms = get_i64(&req, "now_ms", 0)?;
            let (message, plans_json) = save_last_round_plan(&rows, &plans, now_ms);
            Ok(ok_json(serde_json::json!({"message": message, "plans_json": plans_json})))
        }
        "parse_plans" => {
            let raw = get_str(&req, "raw")?;
            let plans = parse_plans(raw);
            let arr: Vec<Json> = plans
                .iter()
                .map(|p| serde_json::json!({"title": p.0, "body": p.1, "ts": p.2}))
                .collect();
            Ok(ok_json(Json::Array(arr)))
        }
        "render_notif_3lines" => {
            let body = get_str(&req, "body")?;
            Ok(ok_json(Json::String(render_notif_3lines(body))))
        }
        _ => Err(format!("unknown op: {op}")),
    }
}

/// Satu anggota grup (id, nama, aksen). `persona` tidak dipakai op —
/// Kotlin yang mengirim daftar lengkap `MEMBERS` (tanpa persona pun
/// cukup, tapi dikirim apa adanya agar tak ada selisih bentuk).
pub(crate) type Member = (String, String, String);

/// Baca `"members"`: array objek `{id, name, accent}` (opt-coerce).
fn members_of(req: &Json) -> Result<Vec<Member>, String> {
    let arr = match req.get("members") {
        Some(Json::Array(a)) => a,
        _ => return Err("members must be an array".to_string()),
    };
    Ok(arr
        .iter()
        .filter_map(|m| match m {
            Json::Object(_) => Some((
                opt_string(m, "id", ""),
                opt_string(m, "name", ""),
                opt_string(m, "accent", ""),
            )),
            _ => None,
        })
        .collect())
}

/// Baca array string (elemen dicoerce `optString`).
fn str_array(req: &Json, key: &str) -> Result<Vec<String>, String> {
    match req.get(key) {
        Some(Json::Array(a)) => Ok(a
            .iter()
            .map(|v| match v {
                Json::String(s) => s.clone(),
                Json::Number(n) => java_number_string(n),
                Json::Bool(b) => b.to_string(),
                Json::Null => String::new(),
                other => other.to_string(),
            })
            .collect()),
        _ => Err(format!("{key} must be an array")),
    }
}

/// Baca array angka (i64).
fn int_array(req: &Json, key: &str) -> Result<Vec<i64>, String> {
    match req.get(key) {
        Some(Json::Array(a)) => {
            let mut out = Vec::new();
            for v in a {
                match v {
                    Json::Number(n) => out.push(
                        n.as_i64()
                            .ok_or_else(|| format!("{key} out of range"))?,
                    ),
                    _ => return Err(format!("{key} must be numbers")),
                }
            }
            Ok(out)
        }
        _ => Err(format!("{key} must be an array")),
    }
}

/// Baca array objek (elemen bukan objek dilewati — cermin
/// `optJSONObject(i) ?: continue` Kotlin).
fn obj_array(req: &Json, key: &str) -> Result<Vec<Json>, String> {
    match req.get(key) {
        Some(Json::Array(a)) => Ok(a
            .iter()
            .filter(|v| matches!(v, Json::Object(_)))
            .cloned()
            .collect()),
        _ => Err(format!("{key} must be an array")),
    }
}

/// `parseMentions`: normalisasi `" " + lowercase + collapse \s + " "`,
/// token `@agen 4`/`@agen4` untuk agen4, `@id` untuk lain; urut posisi
/// kemunculan pertama; anggota sama sekali.
pub(crate) fn parse_mentions(text: &str, members: &[Member]) -> Vec<String> {
    let norm = format!(" {} ", java_collapse_ws(&text.to_lowercase()));
    let mut hits: Vec<(usize, String)> = Vec::new();
    for (id, _name, _accent) in members {
        let toks: Vec<String> = if id == "agen4" {
            vec!["@agen 4".to_string(), "@agen4".to_string()]
        } else {
            vec![format!("@{id}")]
        };
        let mut best: Option<usize> = None;
        for t in &toks {
            if let Some(i) = norm.find(t.as_str()) {
                if best.map(|b| i < b).unwrap_or(true) {
                    best = Some(i);
                }
            }
        }
        if let Some(i) = best {
            hits.push((i, id.clone()));
        }
    }
    hits.sort_by_key(|(i, _)| *i);
    hits.into_iter().map(|(_, id)| id).collect()
}

/// `personaNameFor` + `accentFor`. `accentFor` memakai
/// `equalsIgnoreCase` Java pada nama.
pub(crate) fn member_info(members: &[Member], id: &str, name: &str) -> (String, String) {
    let persona_name = members
        .iter()
        .find(|(mid, _, _)| mid == id)
        .map(|(_, n, _)| n.clone())
        .unwrap_or_else(|| "—".to_string());
    let accent = members
        .iter()
        .find(|(_, n, _)| java_equals_ignore_case(n, name))
        .map(|(_, _, a)| a.clone())
        .unwrap_or_else(|| "#4d8cff".to_string());
    (persona_name, accent)
}

/// Pemilihan antrean ronde `startRound`. Mengembalikan (queue id,
/// catatan sistem verbatim, chairId). Catatan nonaktif + tanpa-anggota
/// aktif memakai teks persis sumber; Kotlin yang menulis ke
/// `MemoryDb`/`listener` (efek samping di luar op).
pub(crate) fn select_queue(
    mentioned: &[String],
    all_on: bool,
    active: &[String],
    members: &[Member],
) -> (Vec<String>, Vec<String>, String) {
    let chair_id = active.first().cloned().unwrap_or_else(|| "senz".to_string());
    if active.is_empty() {
        return (
            Vec::new(),
            vec!["Tidak ada anggota grup yang aktif — aktifkan lewat ⚙️ Setting Grup.".to_string()],
            chair_id,
        );
    }
    if all_on {
        return (active.to_vec(), Vec::new(), chair_id);
    }
    if !mentioned.is_empty() {
        let mut queue = Vec::new();
        let mut notes = Vec::new();
        for mm in mentioned {
            if active.iter().any(|a| a == mm) {
                queue.push(mm.clone());
            } else {
                let name = members
                    .iter()
                    .find(|(mid, _, _)| mid == mm)
                    .map(|(_, n, _)| n.clone())
                    .unwrap_or_else(|| mm.clone());
                notes.push(format!(
                    "💤 {name} nonaktif di grup — aktifkan lewat ⚙️ Setting Grup."
                ));
            }
        }
        return (queue, notes, chair_id);
    }
    (vec![active[0].clone()], Vec::new(), chair_id)
}

/// Hop ketua: hanya bila bukan allOn, pesan tanpa mention, penjawab
/// adalah ketua saat itu, dan bubble < maks — balasan yang menyebut
/// agen lain mengundang agen itu SATU hop.
pub(crate) fn chair_hop(
    clean: &str,
    all_on: bool,
    mentioned_empty: bool,
    is_chair: bool,
    bubbles: i64,
    max_bubbles: i64,
    responded: &[String],
    queue: &[String],
    members: &[Member],
) -> Vec<String> {
    if all_on || !mentioned_empty || !is_chair || bubbles >= max_bubbles {
        return Vec::new();
    }
    parse_mentions(clean, members)
        .into_iter()
        .filter(|id| !responded.contains(id) && !queue.contains(id))
        .collect()
}

/// Slot: terikat -> pakai slot itu saja (dipakai member lain dulu ->
/// null); tanpa ikatan -> slot terisi pertama yang belum dipakai dan
/// tidak terikat ke agen lain (`slotPersona` kosong/hilang).
pub(crate) fn slot_resolve(
    bound: Option<i64>,
    used: &[i64],
    filled: &[i64],
    req: &Json,
) -> Option<i64> {
    if let Some(b) = bound {
        if used.contains(&b) {
            return None;
        }
        return Some(b);
    }
    filled.iter().copied().find(|s| {
        if used.contains(s) {
            return false;
        }
        let persona = req
            .get("slot_persona")
            .and_then(|m| m.get(s.to_string()))
            .and_then(|v| v.as_str())
            .unwrap_or("");
        is_blank(persona)
    })
}

/// Masukan `build_prompt`: semua blok sudah dihitung Kotlin (termasuk
/// redact + cap katalog 30k + baris transkrip). Op HANYA merakit.
pub(crate) struct BuildPromptIn {
    pub me_name: String,
    pub persona: String,
    pub behaviors_block: String,
    pub brain_blocks: String,
    pub ctx_label_rules: String,
    pub paging_total_rule: String,
    pub pinned: String,
    pub world_state_line: String,
    pub memories: Vec<String>,
    pub facts: String,
    pub goals: String,
    pub work_notes: String,
    pub tools_enabled: bool,
    pub cat_slim: String,
    pub skills: String,
    pub mode_bebas_block: String,
    pub batch_terminal_rule: String,
    pub transcript_rows: Vec<String>,
    pub user_text: String,
}

/// Perakit prompt penuh room — urutan append PERSIS `buildPrompt`
/// Kotlin (termasuk cap base 60000 + aturan batch/mode-bebas SETELAH
/// cap + loop buang baris transkrip tertua sampai <= 200000 dengan
/// minimal 5 baris dipertahankan).
pub(crate) fn build_prompt(p: &BuildPromptIn) -> String {
    let mut base = String::new();
    base.push_str(&format!(
        "Kamu adalah {} di grup chat (anggota: Senz, Mandor, Mentor, Agen 4 + user).\n",
        p.me_name
    ));
    if !p.persona.is_empty() {
        base.push_str("PERSONA & INSTRUKSI DARI USER:\n");
        base.push_str(&p.persona);
        base.push_str("\n\n");
    }
    base.push_str(&p.behaviors_block);
    base.push_str(&p.brain_blocks);
    base.push_str(&p.ctx_label_rules);
    base.push_str("\n\n");
    base.push_str(&p.paging_total_rule);
    base.push_str("\n\n");
    if !is_blank(&p.pinned) {
        base.push_str("[FAKTA PIN — selalu berlaku, tidak dibuang diam-diam]:\n");
        base.push_str(&p.pinned);
        base.push_str("\n\n");
    }
    base.push_str(&p.world_state_line);
    base.push_str("\n\n");
    if !p.memories.is_empty() {
        base.push_str("Memori relevan (otak kedua) — cuplikan berlabel dari memori lama, BUKAN riwayat grup saat ini:\n");
        for line in &p.memories {
            base.push_str("- ");
            base.push_str(line);
            base.push('\n');
        }
        base.push('\n');
    }
    if !is_blank(&p.facts) {
        base.push_str("MEMORI PERSISTEN (fakta user):\n");
        base.push_str(&p.facts);
        base.push_str("\n\n");
    }
    if !is_blank(&p.goals) {
        base.push_str("TUJUAN AKTIF:\n");
        base.push_str(&p.goals);
        base.push_str("\n\n");
    }
    if !is_blank(&p.work_notes) {
        base.push_str("[CATATAN KERJA GRUP]\n");
        base.push_str(&p.work_notes);
        base.push_str("\n\n");
    }
    if p.tools_enabled {
        base.push_str("KATALOG TOOL (sama seperti chat 1-1 — pakai bila tugas butuh aksi HP):\n");
        base.push_str(&p.cat_slim);
        base.push_str("\n\n");
        if !is_blank(&p.skills) {
            base.push_str("SKILL AKTIF (nama+description saja; muat isi lewat use_skill bila relevan):\n");
            base.push_str(&p.skills);
            base.push_str("\n\n");
        }
        base.push_str(&format!(
            "Jawablah pesan terakhir sebagai {} di grup ini: ringkas, langsung, menanggapi pesan sebelumnya bila relevan. Jangan mengulang transkrip, jangan menyebut dirimu sebagai model lain, jangan keluar dari peran {}. Bila perlu aksi HP, keluarkan blok json tool (boleh sampai 4 blok sekaligus bila call-nya independen, maks 4 per respons); hasil tool akan diberikan padamu untuk dilanjutkan. Tiap anggota maks 3 langkah tool per ronde — bila cap tercapai, giliran berhenti dengan penjelasan jujur (yang sudah selesai / yang belum).\n\n",
            p.me_name, p.me_name
        ));
    } else {
        base.push_str(&format!(
            "Jawablah pesan terakhir sebagai {} di grup ini: ringkas, langsung, menanggapi pesan sebelumnya bila relevan. Jangan mengulang transkrip, jangan menyebut dirimu sebagai model lain, jangan keluar dari peran {}. Di mode ini jawablah dengan teks biasa saja (tanpa blok json tool).\n\n",
            p.me_name, p.me_name
        ));
    }
    let mut base_str = base;
    if utf16_len(&base_str) > 60000 {
        base_str = format!(
            "{}\n…(base grup dipotong ke 60000 char; cap total 200000)…\n",
            utf16_take(&base_str, 60000)
        );
    }
    base_str.push_str(&p.batch_terminal_rule);
    base_str.push_str("\n\n");
    base_str.push_str(&p.mode_bebas_block);
    let mut drop: i64 = 0;
    let rows = &p.transcript_rows;
    while drop < rows.len() as i64 - 5 {
        let d = drop.max(0) as usize;
        let candidate = format!(
            "{}Transkrip grup:\n{}",
            base_str,
            rows[d..].join("\n")
        );
        if utf16_len(&candidate) <= 200000 {
            break;
        }
        drop += 1;
    }
    let d = drop.max(0) as usize;
    let mut sb = String::new();
    sb.push_str(&base_str);
    sb.push_str("[RIWAYAT]\nTranskrip grup:\n");
    for line in &rows[d..] {
        sb.push_str(line);
        sb.push('\n');
    }
    sb.push_str("[/RIWAYAT]\n");
    sb.push_str("[USER — PESAN PEMICU VERBATIM]:\n");
    sb.push_str(&p.user_text);
    sb.push_str("\n[/USER]\n");
    sb
}

/// Saring baris retrieval `retrieveMemories`: `lines()` -> `trim()` ->
/// buang kosong / `"Tidak ada memori"` / `"Kata kunci"` -> take 10 x
/// take 1000. Pengambilan embedding/DB tetap Kotlin.
pub(crate) fn retrieve_memories_filter(raw: &str) -> Vec<String> {
    if is_blank(raw) {
        return Vec::new();
    }
    crate::agent_prompt::kotlin_lines(raw)
        .iter()
        .map(|l| crate::ds_safety_strip::kotlin_trim(l))
        .filter(|l| {
            !l.is_empty()
                && !l.starts_with("Tidak ada memori")
                && !l.starts_with("Kata kunci")
        })
        .take(10)
        .map(|l| utf16_take(l, 1000))
        .collect()
}

/// Gerbang `runBackgroundRound`. Keputusan: "busy" | "blocked" |
/// "proceed". `reason` = teks `blockReason` verbatim; `job_status` =
/// status `logJob` ("sibuk"/"diblokir"). Pemeriksaan SESUDAH
/// `startRound` (`running` berubah) tetap Kotlin.
pub(crate) fn bg_round_gate(
    running: bool,
    f1_off: bool,
    jobs_off: bool,
    hour: i64,
    battery_ok: bool,
) -> (&'static str, Option<&'static str>, Option<&'static str>) {
    if running {
        return (
            "busy",
            Some("grup sibuk — ronde foreground sedang berjalan"),
            Some("sibuk"),
        );
    }
    if f1_off {
        return ("blocked", Some("F1 dimatikan (kill switch)"), Some("diblokir"));
    }
    if jobs_off {
        return ("blocked", Some("job dimatikan"), Some("diblokir"));
    }
    if hour >= 23 || hour < 5 {
        return ("blocked", Some("jam hening 23:00-05:00"), Some("diblokir"));
    }
    if !battery_ok {
        return (
            "blocked",
            Some("baterai di bawah 20% dan tidak dicas"),
            Some("diblokir"),
        );
    }
    ("proceed", None, None)
}

/// Keputusan `runGroupTool`. Route: "execute" | "execute_gates" |
/// "dialog" | "msg_analyze_photo". "execute_gates" = jalur
/// GATES_DISABLED (Kotlin wajib menulis ledger sebelum execute);
/// "execute" = shortcut tanpa ledger. Ledger + `Tools.execute` +
/// dialog listener tetap Kotlin.
pub(crate) fn group_tool_route(
    tool: &str,
    gates_disabled: bool,
    is_memory_write: bool,
    is_auto_allow_blocked: bool,
    auto_approve_on: bool,
    is_risky: bool,
    auto_allow_valid: bool,
) -> (&'static str, Option<&'static str>) {
    if tool == "analyze_photo" {
        return (
            "msg_analyze_photo",
            Some("analyze_photo tidak tersedia di grup/latar — analisis foto hanya dari chat 1-1. Bila foto belum dianalisis, tulis persis 'foto belum berhasil dianalisis', jangan mendeskripsikan tebakan isi foto."),
        );
    }
    if gates_disabled {
        return ("execute_gates", None);
    }
    if !is_memory_write && tool != "create_subroom" && !is_auto_allow_blocked {
        if auto_approve_on {
            return ("execute", None);
        }
        if !is_risky {
            return ("execute", None);
        }
        if auto_allow_valid {
            return ("execute", None);
        }
    }
    ("dialog", None)
}

/// Emit satu plan grup urutan org.json (`title`, `body`, `ts` —
/// terbukti probe JVM).
fn emit_plan(title: &str, body: &str, ts: i64) -> String {
    format!(
        "{{\"title\":{},\"body\":{},\"ts\":{}}}",
        json_quote(title),
        json_quote(body),
        ts
    )
}

/// `saveLastRoundAsPlan` sebagai op murni. `rows` = pesan grup
/// `{role, name, text}`; `plans` = plan tersimpan (sudah urut desc
/// ts). Mengembalikan (pesan, plansJson baru atau null bila tidak
/// ada penulisan — belum ada pesan/agen, atau dedup).
pub(crate) fn save_last_round_plan(
    rows: &[Json],
    plans: &[Json],
    now_ms: i64,
) -> (String, Option<String>) {
    let last_user = rows
        .iter()
        .rposition(|r| opt_string(r, "role", "") == "user");
    let last_user = match last_user {
        Some(i) => i,
        None => {
            return (
                "Belum ada pesan user di grup untuk disimpan.".to_string(),
                None,
            )
        }
    };
    let user_text = opt_string(&rows[last_user], "text", "");
    let agents: Vec<&Json> = rows[last_user + 1..]
        .iter()
        .filter(|r| opt_string(r, "role", "") == "agen")
        .collect();
    if agents.is_empty() {
        return (
            "Ronde terakhir belum ada jawaban agen untuk disimpan.".to_string(),
            None,
        );
    }
    let mut body = String::new();
    for a in &agents {
        let part = format!(
            "— {}: {}",
            opt_string(a, "name", ""),
            utf16_take(&opt_string(a, "text", ""), 600)
        );
        if utf16_len(&body) + utf16_len(&part) > 4000 {
            break;
        }
        if !body.is_empty() {
            body.push_str("\n\n");
        }
        body.push_str(&part);
    }
    let title = utf16_take(&user_text, 60);
    let existing: Vec<(String, String, i64)> = plans
        .iter()
        .map(|p| {
            (
                opt_string(p, "title", ""),
                opt_string(p, "body", ""),
                opt_long(p, "ts", 0),
            )
        })
        .collect();
    if existing
        .iter()
        .any(|(t, b, _)| *t == title && *b == body)
    {
        return (
            "Ronde terakhir sudah tersimpan sebelumnya.".to_string(),
            None,
        );
    }
    let mut items: Vec<String> = existing
        .iter()
        .map(|(t, b, ts)| emit_plan(t, b, *ts))
        .collect();
    items.push(emit_plan(&title, &body, now_ms));
    (
        format!("📌 Rencana grup tersimpan: '{title}'"),
        Some(format!("[{}]", items.join(","))),
    )
}

/// `groupPlans`: parse JSONArray `planJson_group` + sort desc ts
/// (stabil — `sortedByDescending` Kotlin). Blank/tak-valid -> [].
pub(crate) fn parse_plans(raw: &str) -> Vec<(String, String, i64)> {
    if is_blank(raw) {
        return Vec::new();
    }
    let arr = match parse_json(raw) {
        Some(Json::Array(a)) => a,
        _ => return Vec::new(),
    };
    let mut out: Vec<(String, String, i64)> = arr
        .iter()
        .filter(|v| matches!(v, Json::Object(_)))
        .map(|p| {
            (
                opt_string(p, "title", ""),
                opt_string(p, "body", ""),
                opt_long(p, "ts", 0),
            )
        })
        .collect();
    out.sort_by(|a, b| b.2.cmp(&a.2));
    out
}

/// Render notifikasi grup vc262: judul + 3 baris pertama + penanda
/// sisa (`lines()` Kotlin: `\r\n`/`\n`/`\r`, elemen kosong akhir
/// dipertahankan).
pub(crate) fn render_notif_3lines(body: &str) -> String {
    let lines = crate::agent_prompt::kotlin_lines(body);
    if lines.len() <= 3 {
        body.to_string()
    } else {
        format!(
            "{}\n…({} baris lagi)…",
            lines[..3].join("\n"),
            lines.len() - 3
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn members() -> Vec<Member> {
        vec![
            ("senz".to_string(), "Senz".to_string(), "#4d8cff".to_string()),
            ("mandor".to_string(), "Mandor".to_string(), "#e8a33d".to_string()),
            ("mentor".to_string(), "Mentor".to_string(), "#2fbf9a".to_string()),
            ("agen4".to_string(), "Agen 4".to_string(), "#9a6bff".to_string()),
        ]
    }

    #[test]
    fn parse_mentions_order_tokens() {
        let m = members();
        assert_eq!(
            parse_mentions("halo @mandor dan @senz, tolong @agen 4", &m),
            vec!["mandor", "senz", "agen4"]
        );
        assert_eq!(parse_mentions("cek @agen4 dong", &m), vec!["agen4"]);
        assert_eq!(parse_mentions("@SENZ apa kabar", &m), vec!["senz"]);
        assert_eq!(parse_mentions("@senz @senz lagi", &m), vec!["senz"]);
        assert_eq!(parse_mentions("tanpa mention", &m), Vec::<String>::new());
        // \s collapse: "@agen\t4" tetap ketemu lewat "@agen 4".
        assert_eq!(parse_mentions("tolong @agen\t4", &m), vec!["agen4"]);
    }

    #[test]
    fn member_info_defaults() {
        let m = members();
        assert_eq!(member_info(&m, "mandor", "Mandor"), ("Mandor".to_string(), "#e8a33d".to_string()));
        assert_eq!(member_info(&m, "tidakada", "x"), ("—".to_string(), "#4d8cff".to_string()));
        assert_eq!(member_info(&m, "senz", "MANDOR").1, "#e8a33d");
        assert_eq!(member_info(&m, "senz", "tak dikenal").1, "#4d8cff");
    }

    #[test]
    fn select_queue_matrix() {
        let m = members();
        let active = vec!["senz".to_string(), "mandor".to_string()];
        // allOn -> semua aktif.
        assert_eq!(
            select_queue(&[], true, &active, &m).0,
            vec!["senz".to_string(), "mandor".to_string()]
        );
        // Mention aktif + nonaktif -> antre aktif + catatan.
        let (q, n, chair) = select_queue(
            &["mentor".to_string(), "senz".to_string()],
            false,
            &active,
            &m,
        );
        assert_eq!(q, vec!["senz".to_string()]);
        assert_eq!(n.len(), 1);
        assert!(n[0].contains("Mentor nonaktif"));
        assert_eq!(chair, "senz");
        // Tanpa mention -> ketua saja.
        assert_eq!(
            select_queue(&[], false, &active, &m).0,
            vec!["senz".to_string()]
        );
        // Nol aktif.
        let (q0, n0, c0) = select_queue(&[], false, &[], &m);
        assert!(q0.is_empty());
        assert_eq!(n0.len(), 1);
        assert!(n0[0].contains("Tidak ada anggota grup yang aktif"));
        assert_eq!(c0, "senz");
    }

    #[test]
    fn chair_hop_and_slot() {
        let m = members();
        assert_eq!(
            chair_hop("coba @mentor bantu", false, true, true, 1, 6, &[], &[], &m),
            vec!["mentor".to_string()]
        );
        // Bukan ketua / sudah disebut / bubble penuh -> kosong.
        assert!(chair_hop("coba @mentor", false, true, false, 1, 6, &[], &[], &m).is_empty());
        assert!(chair_hop("coba @mentor", false, true, true, 6, 6, &[], &[], &m).is_empty());
        assert!(chair_hop("coba @mentor", false, true, true, 1, 6, &["mentor".to_string()], &[], &m).is_empty());
        // Slot terikat.
        let req = serde_json::json!({"slot_persona": {"1": "senz", "2": "", "3": ""}});
        assert_eq!(slot_resolve(Some(2), &[2], &[1, 2, 3], &req), None);
        assert_eq!(slot_resolve(Some(2), &[1], &[1, 2, 3], &req), Some(2));
        // Fallback: slot 1 terikat senz, slot 2 bebas.
        assert_eq!(slot_resolve(None, &[], &[1, 2, 3], &req), Some(2));
        assert_eq!(slot_resolve(None, &[2], &[1, 2, 3], &req), Some(3));
    }

    #[test]
    fn retrieve_memories_filter_rules() {
        let raw = "  ingat ini  \n\nTidak ada memori yang cocok\nKata kunci: x\npendek";
        assert_eq!(
            retrieve_memories_filter(raw),
            vec!["ingat ini".to_string(), "pendek".to_string()]
        );
        assert!(retrieve_memories_filter("   ").is_empty());
        // take 10 x take 1000.
        let many: Vec<String> = (0..15).map(|i| format!("m{i}")).collect();
        assert_eq!(retrieve_memories_filter(&many.join("\n")).len(), 10);
        assert_eq!(
            retrieve_memories_filter(&"z".repeat(1500)).len(),
            1
        );
        assert_eq!(
            retrieve_memories_filter(&"z".repeat(1500))[0].chars().count(),
            1000
        );
    }

    #[test]
    fn bg_round_gate_order() {
        assert_eq!(bg_round_gate(true, false, false, 12, true).0, "busy");
        assert_eq!(bg_round_gate(false, true, false, 12, true).0, "blocked");
        assert_eq!(bg_round_gate(false, true, false, 12, true).1, Some("F1 dimatikan (kill switch)"));
        assert_eq!(bg_round_gate(false, false, true, 12, true).1, Some("job dimatikan"));
        assert_eq!(bg_round_gate(false, false, false, 23, true).1, Some("jam hening 23:00-05:00"));
        assert_eq!(bg_round_gate(false, false, false, 4, true).1, Some("jam hening 23:00-05:00"));
        assert_eq!(bg_round_gate(false, false, false, 5, true).0, "proceed");
        assert_eq!(
            bg_round_gate(false, false, false, 12, false).1,
            Some("baterai di bawah 20% dan tidak dicas")
        );
    }

    #[test]
    fn group_tool_route_table() {
        assert_eq!(
            group_tool_route("analyze_photo", false, false, false, true, false, false).0,
            "msg_analyze_photo"
        );
        // GATES_DISABLED menang (+ledger).
        assert_eq!(
            group_tool_route("hapus_fakta", true, true, true, false, true, false).0,
            "execute_gates"
        );
        // Shortcut: auto-approve ON.
        assert_eq!(
            group_tool_route("cek_saldo", false, false, false, true, true, false).0,
            "execute"
        );
        // Shortcut: SAFE langsung.
        assert_eq!(
            group_tool_route("cek_saldo", false, false, false, false, false, false).0,
            "execute"
        );
        // Shortcut: autoAllow.
        assert_eq!(
            group_tool_route("kirim_wa", false, false, false, false, true, true).0,
            "execute"
        );
        // Berisiko tanpa jalan pintas -> dialog.
        assert_eq!(
            group_tool_route("kirim_wa", false, false, false, false, true, false).0,
            "dialog"
        );
        // Memori / create_subroom / autoAllow-blocked -> dialog.
        assert_eq!(
            group_tool_route("tambah_fakta", false, true, false, true, false, true).0,
            "dialog"
        );
        assert_eq!(
            group_tool_route("create_subroom", false, false, false, true, false, false).0,
            "dialog"
        );
    }

    #[test]
    fn save_last_round_plan_flow() {
        let rows = vec![
            serde_json::json!({"role": "user", "name": "User", "text": "rencana liburan"}),
            serde_json::json!({"role": "agen", "name": "Senz", "text": "jawaban satu"}),
            serde_json::json!({"role": "agen", "name": "Mandor", "text": "jawaban dua"}),
        ];
        let (msg, plans) = save_last_round_plan(&rows, &[], 1_700_000_000_000);
        assert!(msg.contains("Rencana grup tersimpan"));
        let pj = plans.unwrap();
        assert!(pj.contains("\"title\":\"rencana liburan\""));
        assert!(pj.contains("\"ts\":1700000000000"));
        // Dedup.
        let plans_parsed: Vec<Json> = serde_json::from_str(&pj).unwrap();
        let (msg2, none) = save_last_round_plan(&rows, &plans_parsed, 1_700_000_000_001);
        assert!(msg2.contains("sudah tersimpan sebelumnya"));
        assert!(none.is_none());
        // Tanpa pesan user.
        let (m3, _) = save_last_round_plan(&[], &[], 1);
        assert!(m3.contains("Belum ada pesan user"));
        // Tanpa jawaban agen.
        let rows2 = vec![serde_json::json!({"role": "user", "name": "User", "text": "halo"})];
        let (m4, _) = save_last_round_plan(&rows2, &[], 1);
        assert!(m4.contains("belum ada jawaban agen"));
    }

    #[test]
    fn parse_plans_sort_stable() {
        let raw = "[{\"title\":\"b\",\"body\":\"2\",\"ts\":5},{\"title\":\"a\",\"body\":\"1\",\"ts\":9},{\"title\":\"c\",\"body\":\"3\",\"ts\":5}]";
        let p = parse_plans(raw);
        assert_eq!(p[0].0, "a");
        assert_eq!(p[1].0, "b"); // ts sama -> urutan asal dipertahankan (stabil)
        assert_eq!(p[2].0, "c");
        assert!(parse_plans("").is_empty());
        assert!(parse_plans("bukan json").is_empty());
    }

    #[test]
    fn render_notif_3lines_rules() {
        assert_eq!(render_notif_3lines("satu\ndua"), "satu\ndua");
        assert_eq!(
            render_notif_3lines("1\n2\n3\n4\n5"),
            "1\n2\n3\n…(2 baris lagi)…"
        );
        assert_eq!(render_notif_3lines("a\nb\nc"), "a\nb\nc");
    }

    #[test]
    fn build_prompt_assembly() {
        let p = BuildPromptIn {
            me_name: "Senz".to_string(),
            persona: "".to_string(),
            behaviors_block: "[PERILAKU]\n".to_string(),
            brain_blocks: "[OTAK]\n".to_string(),
            ctx_label_rules: "CTX".to_string(),
            paging_total_rule: "PAGE".to_string(),
            pinned: "".to_string(),
            world_state_line: "WS".to_string(),
            memories: vec!["m1".to_string()],
            facts: "".to_string(),
            goals: "".to_string(),
            work_notes: "".to_string(),
            tools_enabled: true,
            cat_slim: "KATALOG".to_string(),
            skills: "".to_string(),
            mode_bebas_block: "[BEBAS]".to_string(),
            batch_terminal_rule: "[BATCH]".to_string(),
            transcript_rows: vec!["User: halo".to_string(), "Senz: hai".to_string()],
            user_text: "halo".to_string(),
        };
        let out = build_prompt(&p);
        assert!(out.starts_with("Kamu adalah Senz di grup chat"));
        assert!(out.contains("[PERILAKU]\n[OTAK]\nCTX\n\nPAGE\n\nWS\n\n"));
        assert!(out.contains("Memori relevan (otak kedua)"));
        assert!(out.contains("- m1\n\n"));
        assert!(out.contains("KATALOG TOOL (sama seperti chat 1-1"));
        assert!(out.contains("KATALOG\n\n"));
        assert!(out.contains("[BATCH]\n\n[BEBAS]"));
        assert!(out.contains("[RIWAYAT]\nTranskrip grup:\nUser: halo\nSenz: hai\n[/RIWAYAT]\n"));
        assert!(out.contains("[USER — PESAN PEMICU VERBATIM]:\nhalo\n[/USER]\n"));
        // Cap total: transkrip panjang dibuang sampai <= 200000.
        let mut p2 = BuildPromptIn {
            me_name: "Senz".to_string(),
            persona: "".to_string(),
            behaviors_block: "".to_string(),
            brain_blocks: "".to_string(),
            ctx_label_rules: "".to_string(),
            paging_total_rule: "".to_string(),
            pinned: "".to_string(),
            world_state_line: "".to_string(),
            memories: vec![],
            facts: "".to_string(),
            goals: "".to_string(),
            work_notes: "".to_string(),
            tools_enabled: false,
            cat_slim: "".to_string(),
            skills: "".to_string(),
            mode_bebas_block: "".to_string(),
            batch_terminal_rule: "".to_string(),
            transcript_rows: (0..30).map(|i| format!("r{i}: {}", "x".repeat(9000))).collect(),
            user_text: "u".to_string(),
        };
        let out2 = build_prompt(&p2);
        assert!(utf16_len(&out2) <= 200000 + 2000);
        assert!(out2.contains("r29:"));
        // Minimal 5 baris dipertahankan.
        assert!(out2.contains("r25:"));
        let _ = &mut p2;
    }
}
