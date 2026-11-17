//! Glue JNI — SATU-SATUNYA modul yang boleh menyentuh FFI.
//!
//! Simbol JNI statis: `Java_harness_core_RustCore_*` — cocok dengan
//! package `harness.core` + kelas `RustCore` di Kotlin. Package ini
//! SENGAJA bukan `com.senzwh.deepharness`: build flavor Pure me-rewrite
//! string itu jadi `com.harness.app` dan akan merusak linkage JNI.
//!
//! Setiap fungsi di sini membungkus kerjanya dengan `catch_unwind`:
//! panic TIDAK BOLEH menyeberang FFI (undefined behavior) — bila panic,
//! kembalikan JSON error / nilai sentinel, bukan crash.

use crate::agent_prompt;
use crate::behaviors;
use crate::bg_brain;
use crate::chat_theme;
use crate::concat_api_client;
use crate::ds_agent;
use crate::ds_api;
use crate::ds_failure;
use crate::ds_safety_strip;
use crate::group_chat;
use crate::http;
use crate::mcp_helper;
use crate::notif_ids;
use crate::oc_coord;
use crate::pow_solver;
use crate::redact_secrets;
use crate::session_background;
use crate::skill_trigger;
use crate::sqlite;
use crate::system_bar_spec;
use crate::tool_registry;
use crate::tools_leaves;
use crate::worker_endpoint;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Once;

/// Versi seam JNI. Naikkan tiap kali signature berubah; Kotlin cek
/// saat load (`RustCore.available` false bila mismatch -> fallback).
/// v2 = slice 2 (pow_solver): +8 fungsi PoW; slice 3 (redact_secrets):
/// +1 fungsi redactSecrets; slice 4: +7 fungsi bulk JSON modul kecil.
/// v3 = slice 5 (concat_api_client): +1 fungsi `concatApiJson`.
/// v4 = slice 6 (tool_registry/agent_prompt/skill_trigger):
/// +3 fungsi bulk JSON `toolRegistryJson`/`agentPromptJson`/`skillTriggerJson`.
/// v5 = slice 7 (sqlite): +1 fungsi `sqliteJson` (rusqlite generik).
/// v6 = slice 8 (http): +1 fungsi `httpJson` (ureq + logika murni HTTP).
/// v7 = slice 9 (worker_endpoint): +1 fungsi `workerEndpointJson`
/// (routing/auth/HMAC/framing/idempotensi server :4198 — TANPA tokio;
/// loop server hidup tetap Kotlin, BUILD-VC325.md §2.2).
/// v8 = slice 10 (tools_leaves): +1 fungsi `toolsLeavesJson`
/// (parse DuckDuckGo + parse YouTube + stripHtml + truncateMarked
/// Tools.kt — dispatcher + daun Android + daun jaringan tetap
/// Kotlin, BUILD-VC326.md §2).
/// v9 = slice 11 (ds_api): +1 fungsi `dsApiJson`
/// (find_session_id/find_challenge/find_file_id/est_tokens/
/// safe_file_name/upload_file DsApiClient — transport JSON + parse SSE
/// memakai seam slice 8, PoW slice 2, klasifikasi slice 1; panen cookie
/// + mesin sesi + loop stream tetap Kotlin, BUILD-VC328.md §2).
/// v10 = slice 12 (bg_brain/group_chat/ds_agent/oc_coord): +4 fungsi
/// `bgBrainJson`/`groupChatJson`/`dsAgentJson`/`ocCoordJson` (logika
/// murni otak latar/room — BgBrain/GroupChat/DsAgent/OcCoord; WebView,
/// notifikasi, thread, DB, `Tools.execute`, loop poll tetap Kotlin,
/// BUILD-VC329.md §2). Migrasi hybrid PENUH sesudah slice ini.
pub const RUST_CORE_VERSION: jint = 10;

static PANIC_HOOK: Once = Once::new();
static PANICKED: AtomicBool = AtomicBool::new(false);

/// Panic hook TANPA secret: hanya menandai bahwa panic terjadi.
/// Pesan panic TIDAK disimpan/dilog — bisa mengandung input mentah.
fn install_panic_hook() {
    PANIC_HOOK.call_once(|| {
        std::panic::set_hook(Box::new(|_| {
            PANICKED.store(true, Ordering::Relaxed);
        }));
    });
}

/// JString Kotlin -> String Rust. `null` Kotlin -> "" (bukan error).
fn jstring_to_rust(env: &mut JNIEnv, s: &JString) -> String {
    if s.is_null() {
        return String::new();
    }
    match env.get_string(s) {
        Ok(java_str) => String::from(java_str),
        Err(_) => String::new(),
    }
}

/// String Rust -> jstring. Gagal (mis. OOM JVM) -> null.
fn return_string(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn error_json() -> String {
    "{\"ok\":false,\"error\":\"rust core error\"}".to_string()
}

/// Jalankan `f` dengan `catch_unwind`; hasil String dikembalikan ke JVM,
/// panic/error -> JSON error. Dipakai di SETIAP boundary string.
fn guarded_string<F>(mut env: JNIEnv, f: F) -> jstring
where
    F: FnOnce(&mut JNIEnv) -> Result<String, String>,
{
    install_panic_hook();
    let result = catch_unwind(AssertUnwindSafe(|| f(&mut env)));
    match result {
        Ok(Ok(s)) => return_string(&mut env, &s),
        Ok(Err(_)) => return_string(&mut env, &error_json()),
        Err(_) => return_string(&mut env, &error_json()),
    }
}

/// Seperti `guarded_string`, tapi kembalikan `null` ke JVM bila panic
/// atau error — dipakai `redactSecrets`: string polos (bukan JSON),
/// jadi JSON error tidak boleh bocor sebagai "teks ter-redaksi".
/// Kotlin memperlakukan `null` sebagai "Rust gagal" -> fallback ke
/// implementasi Kotlin lama + matikan saklar Rust slice itu.
fn guarded_string_nullable<F>(mut env: JNIEnv, f: F) -> jstring
where
    F: FnOnce(&mut JNIEnv) -> Option<String>,
{
    install_panic_hook();
    let result = catch_unwind(AssertUnwindSafe(|| f(&mut env)));
    match result {
        Ok(Some(s)) => return_string(&mut env, &s),
        Ok(None) => std::ptr::null_mut(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Jalankan `f` dengan `catch_unwind`; hasil byte[] dikembalikan ke JVM,
/// panic/error -> null (pemanggil Kotlin WAJIB fallback ke Kotlin lama).
fn guarded_bytes<F>(mut env: JNIEnv, f: F) -> jbyteArray
where
    F: FnOnce(&mut JNIEnv) -> Result<Vec<u8>, String>,
{
    install_panic_hook();
    let result = catch_unwind(AssertUnwindSafe(|| f(&mut env)));
    match result {
        Ok(Ok(bytes)) => match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Ok(Err(_)) => std::ptr::null_mut(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// JByteArray Kotlin -> Vec<u8> Rust. `null` Kotlin -> Err.
fn jbytes_to_rust(env: &mut JNIEnv, a: &JByteArray) -> Result<Vec<u8>, String> {
    if a.is_null() {
        return Err("null byte array".to_string());
    }
    env.convert_byte_array(a)
        .map_err(|_| "convert_byte_array gagal".to_string())
}

/// Satu hasil solve -> JSON seam. `found:false` bila nonce tak ketemu
/// dalam batas (bukan error — pemanggil Kotlin mendapat null seperti
/// implementasi Kotlin lama).
fn pow_result_json(r: Option<pow_solver::PowResult>) -> String {
    match r {
        Some(p) => serde_json::json!({
            "ok": true,
            "found": true,
            "nonce": p.nonce,
            "hash_hex": p.hash_hex,
            "tries": p.tries,
            "elapsed_ms": p.elapsed_ms
        })
        .to_string(),
        None => serde_json::json!({"ok": true, "found": false}).to_string(),
    }
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_rustCoreVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    install_panic_hook();
    // Tanpa input luar; tetap dijaga catch_unwind supaya panic
    // tidak pernah menyeberang FFI.
    match catch_unwind(|| RUST_CORE_VERSION) {
        Ok(v) => v,
        Err(_) => -1,
    }
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_rustCoreCapabilities(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    guarded_string(env, |_| {
        Ok(serde_json::json!({
            "version": RUST_CORE_VERSION,
            "functions": ["rustCoreVersion", "rustCoreCapabilities", "echo", "classifyFailureJson", "redactSecrets",
                "powSha3Hex", "powDsHashV1Hex", "powSha3Bytes", "powDsHashV1Bytes",
                "powSolveDeepSeekJson", "powSolveFastJson", "powSolveFmtJson", "powSolveJson",
                "notifIdsJson", "systemBarSpecJson", "dsSafetyStripJson", "mcpHelperJson",
                "sessionBackgroundJson", "behaviorsJson", "chatThemeJson",
                "concatApiJson",
                "toolRegistryJson", "agentPromptJson", "skillTriggerJson",
                "sqliteJson",
                "httpJson",
                "workerEndpointJson",
                "toolsLeavesJson",
                "dsApiJson",
                "bgBrainJson", "groupChatJson", "dsAgentJson", "ocCoordJson"],
            "modules": ["ds_failure", "pow_solver", "redact_secrets",
                "notif_ids", "system_bar_spec", "ds_safety_strip", "mcp_helper",
                "session_background", "behaviors", "chat_theme",
                "concat_api_client",
                "tool_registry", "agent_prompt", "skill_trigger",
                "sqlite", "sqlite_schema",
                "http", "sse",
                "worker_endpoint",
                "tools_leaves",
                "ds_api",
                "bg_brain", "group_chat", "ds_agent", "oc_coord"]
        })
        .to_string())
    })
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_echo(
    env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    guarded_string(env, |env| Ok(jstring_to_rust(env, &input)))
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_classifyFailureJson(
    env: JNIEnv,
    _class: JClass,
    code: jint,
    body: JString,
    exception_message: JString,
) -> jstring {
    guarded_string(env, |env| {
        let body = jstring_to_rust(env, &body);
        let exc = jstring_to_rust(env, &exception_message);
        let f = ds_failure::classify_failure(code, &body, &exc);
        Ok(serde_json::json!({
            "ok": true,
            "kind": f.kind.as_str(),
            "status_code": f.status_code,
            "retryable": f.retryable
        })
        .to_string())
    })
}

// ------------------------------------------------------------- Slice 2: PoW --
//
// Hash hex dikembalikan sebagai string polos (64 digit hex); bila panic,
// `guarded_string` mengembalikan JSON error — pemanggil Kotlin WAJIB
// memvalidasi bentuk hex (panjang 64 + karakter hex) dan fallback bila
// tidak cocok. Solve mengembalikan JSON `pow_result_json` (found:true
// berisi nonce/hash_hex/tries/elapsed_ms; found:false = tak ketemu,
// sama seperti `null` Kotlin). byte[] dipakai untuk digest mentah
// (sha3_256/dsHashV1) supaya tidak ada encode hex bolak-balik.

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_powSha3Hex(
    env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    guarded_string(env, |env| {
        let s = jstring_to_rust(env, &input);
        Ok(pow_solver::sha3_hex(&s))
    })
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_powDsHashV1Hex(
    env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    guarded_string(env, |env| {
        let s = jstring_to_rust(env, &input);
        Ok(pow_solver::ds_hash_v1_hex(&s))
    })
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_powSha3Bytes(
    env: JNIEnv,
    _class: JClass,
    input: JByteArray,
) -> jbyteArray {
    guarded_bytes(env, |env| {
        let bytes = jbytes_to_rust(env, &input)?;
        Ok(pow_solver::sha3_256(&bytes).to_vec())
    })
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_powDsHashV1Bytes(
    env: JNIEnv,
    _class: JClass,
    input: JByteArray,
) -> jbyteArray {
    guarded_bytes(env, |env| {
        let bytes = jbytes_to_rust(env, &input)?;
        Ok(pow_solver::ds_hash_v1(&bytes).to_vec())
    })
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_powSolveDeepSeekJson(
    env: JNIEnv,
    _class: JClass,
    challenge_hex: JString,
    salt: JString,
    expire_at: jlong,
    difficulty: jlong,
) -> jstring {
    guarded_string(env, |env| {
        let challenge_hex = jstring_to_rust(env, &challenge_hex);
        let salt = jstring_to_rust(env, &salt);
        Ok(pow_result_json(pow_solver::solve_deep_seek(
            &challenge_hex,
            &salt,
            expire_at,
            difficulty,
        )))
    })
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_powSolveFastJson(
    env: JNIEnv,
    _class: JClass,
    challenge: JString,
    salt: JString,
    difficulty: jlong,
    max_tries: jlong,
) -> jstring {
    guarded_string(env, |env| {
        let challenge = jstring_to_rust(env, &challenge);
        let salt = jstring_to_rust(env, &salt);
        Ok(pow_result_json(pow_solver::solve_fast(
            &challenge, &salt, difficulty, max_tries,
        )))
    })
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_powSolveFmtJson(
    env: JNIEnv,
    _class: JClass,
    challenge: JString,
    salt: JString,
    difficulty: jlong,
    format: JString,
    expire_at: jlong,
    max_tries: jlong,
) -> jstring {
    guarded_string(env, |env| {
        let challenge = jstring_to_rust(env, &challenge);
        let salt = jstring_to_rust(env, &salt);
        let format = jstring_to_rust(env, &format);
        Ok(pow_result_json(pow_solver::solve_fmt(
            &challenge, &salt, difficulty, &format, expire_at, max_tries,
        )))
    })
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_powSolveJson(
    env: JNIEnv,
    _class: JClass,
    challenge: JString,
    salt: JString,
    difficulty: jlong,
    max_tries: jlong,
) -> jstring {
    guarded_string(env, |env| {
        let challenge = jstring_to_rust(env, &challenge);
        let salt = jstring_to_rust(env, &salt);
        Ok(pow_result_json(pow_solver::solve(
            &challenge, &salt, difficulty, max_tries,
        )))
    })
}

// ------------------------------------------------------------- Slice 3: RedactSecrets --
//
// String polos in/out (bukan JSON: payload-nya teks presentasi;
// envelope JSON akan memaksa Kotlin meng-unescape string). Sukses ->
// teks ter-redaksi; panic/error -> `null` (Kotlin fallback ke
// implementasi Kotlin lama + matikan saklar rustRedactSecrets).
#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_redactSecrets(
    env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    guarded_string_nullable(env, |env| {
        let s = jstring_to_rust(env, &input);
        Some(redact_secrets::redact(&s))
    })
}

// ---- Slice 4: satu fungsi JSON bulk per modul kecil ----

/// `NotifIds`: semua konstanta + 5 alokator (origin/key/workerId
/// dilewat sekaligus supaya satu panggilan mencakup semua fungsi).
#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_notifIdsJson(
    env: JNIEnv,
    _class: JClass,
    origin: JString,
    key: JString,
    worker_id: JString,
) -> jstring {
    guarded_string(env, |env| {
        let origin = jstring_to_rust(env, &origin);
        let key = jstring_to_rust(env, &key);
        let worker_id = jstring_to_rust(env, &worker_id);
        Ok(serde_json::json!({
            "ok": true,
            "constants": {
                "SCHEDULE": notif_ids::SCHEDULE,
                "PARK_QUEUE": notif_ids::PARK_QUEUE,
                "SECOND_CONFIRM": notif_ids::SECOND_CONFIRM,
                "MEMORY_PROPOSAL": notif_ids::MEMORY_PROPOSAL,
                "PRIV_PAIR": notif_ids::PRIV_PAIR,
                "BG_SUMMARY_BASE": notif_ids::BG_SUMMARY_BASE,
                "KILL_SWITCH_CANCEL": notif_ids::KILL_SWITCH_CANCEL,
                "GOVERNOR_DELAY": notif_ids::GOVERNOR_DELAY,
                "SERVE_DOWN": notif_ids::SERVE_DOWN,
                "HEAL_DIAGNOSIS": notif_ids::HEAL_DIAGNOSIS,
                "GROUP_DONE": notif_ids::GROUP_DONE,
                "MITM_DOWN": notif_ids::MITM_DOWN,
                "BG_WATCHDOG": notif_ids::BG_WATCHDOG,
                "BG_WATCHDOG_BASE": notif_ids::BG_WATCHDOG_BASE,
                "WORKER_JANITOR_BASE": notif_ids::WORKER_JANITOR_BASE,
                "BG_RESULT_BASE": notif_ids::BG_RESULT_BASE,
                "BG_FAIL_BASE": notif_ids::BG_FAIL_BASE
            },
            "bgResultId": notif_ids::bg_result_id(&origin),
            "bgFailId": notif_ids::bg_fail_id(&origin),
            "bgSummaryId": notif_ids::bg_summary_id(&origin),
            "bgWatchdogId": notif_ids::bg_watchdog_id(&key),
            "workerJanitorId": notif_ids::worker_janitor_id(&worker_id),
            "duplicates": notif_ids::check_duplicates()
        })
        .to_string())
    })
}

/// `SystemBarSpecVc309`: pemetaan tema -> system bars.
#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_systemBarSpecJson(
    env: JNIEnv,
    _class: JClass,
    is_dark: jboolean,
) -> jstring {
    guarded_string(env, |_| {
        let s = system_bar_spec::spec(is_dark != 0);
        Ok(serde_json::json!({
            "ok": true,
            "statusColor": s.status_color,
            "navColor": s.nav_color,
            "lightIcons": s.light_icons
        })
        .to_string())
    })
}

/// `DsSafetyStrip`: hapus blok tag safety dari teks jawaban.
#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_dsSafetyStripJson(
    env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jstring {
    guarded_string(env, |env| {
        let text = jstring_to_rust(env, &text);
        Ok(serde_json::json!({
            "ok": true,
            "stripped": ds_safety_strip::strip(&text)
        })
        .to_string())
    })
}

/// `McpHelper`: skrip helper MCP dalam base64.
#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_mcpHelperJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    guarded_string(env, |_| {
        Ok(serde_json::json!({
            "ok": true,
            "scriptB64": mcp_helper::SCRIPT_B64
        })
        .to_string())
    })
}

/// `SessionBackgroundVc304`: gerbang render live + parkir keputusan.
#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_sessionBackgroundJson(
    env: JNIEnv,
    _class: JClass,
    turn_session_id: jlong,
    current_session_id: jlong,
    turn_active: jboolean,
) -> jstring {
    guarded_string(env, |_| {
        Ok(serde_json::json!({
            "ok": true,
            "renderAllowed": session_background::render_allowed(turn_session_id, current_session_id),
            "shouldParkDecision": session_background::should_park_decision(
                turn_active != 0, turn_session_id, current_session_id
            ),
            "backgroundNotice": session_background::background_notice()
        })
        .to_string())
    })
}

fn behavior_pack_json(p: behaviors::Pack) -> serde_json::Value {
    serde_json::json!({
        "id": p.id,
        "name": p.name,
        "desc": p.desc,
        "block": p.block
    })
}

/// `Behaviors`: pak perilaku untuk `id` + daftar semua pak + blok
/// siap tempel (`block(id)` — "" untuk pak bawaan).
#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_behaviorsJson(
    env: JNIEnv,
    _class: JClass,
    id: JString,
) -> jstring {
    guarded_string(env, |env| {
        let id = jstring_to_rust(env, &id);
        let packs: Vec<serde_json::Value> =
            behaviors::PACKS.iter().map(|p| behavior_pack_json(*p)).collect();
        Ok(serde_json::json!({
            "ok": true,
            "pack": behavior_pack_json(behaviors::by_id(&id)),
            "packs": packs,
            "block": behaviors::block(&id)
        })
        .to_string())
    })
}

/// `ChatThemeVc306`: semua konstanta tema + kontras WCAG fg/bg.
#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_chatThemeJson(
    env: JNIEnv,
    _class: JClass,
    fg: jint,
    bg: jint,
) -> jstring {
    guarded_string(env, |_| {
        Ok(serde_json::json!({
            "ok": true,
            "constants": {
                "darkBgTop": chat_theme::DARK_BG_TOP,
                "darkBgBottom": chat_theme::DARK_BG_BOTTOM,
                "darkSurface": chat_theme::DARK_SURFACE,
                "darkContainerLow": chat_theme::DARK_CONTAINER_LOW,
                "darkContainerHigh": chat_theme::DARK_CONTAINER_HIGH,
                "darkText": chat_theme::DARK_TEXT,
                "darkDim": chat_theme::DARK_DIM,
                "darkFaint": chat_theme::DARK_FAINT,
                "darkStroke": chat_theme::DARK_STROKE,
                "darkTileBg": chat_theme::DARK_TILE_BG,
                "darkUserBubble": chat_theme::DARK_USER_BUBBLE,
                "darkAccentText": chat_theme::DARK_ACCENT_TEXT,
                "darkCodeText": chat_theme::DARK_CODE_TEXT,
                "lightCodeText": chat_theme::LIGHT_CODE_TEXT,
                "darkCodeBg": chat_theme::DARK_CODE_BG,
                "lightCodeBg": chat_theme::LIGHT_CODE_BG,
                "lightBgTop": chat_theme::LIGHT_BG_TOP,
                "lightBgBottom": chat_theme::LIGHT_BG_BOTTOM,
                "lightSurface": chat_theme::LIGHT_SURFACE,
                "lightText": chat_theme::LIGHT_TEXT,
                "lightDim": chat_theme::LIGHT_DIM,
                "lightFaint": chat_theme::LIGHT_FAINT,
                "lightCard": chat_theme::LIGHT_CARD,
                "lightUserBubble": chat_theme::LIGHT_USER_BUBBLE,
                "accentSolid": chat_theme::ACCENT_SOLID,
                "accentPressed": chat_theme::ACCENT_PRESSED,
                "approveBlue": chat_theme::APPROVE_BLUE,
                "rXs": chat_theme::R_XS,
                "rSm": chat_theme::R_SM,
                "rMd": chat_theme::R_MD,
                "rLg": chat_theme::R_LG,
                "rXl": chat_theme::R_XL,
                "iconXs": chat_theme::ICON_XS,
                "iconSm": chat_theme::ICON_SM,
                "iconMd": chat_theme::ICON_MD,
                "iconLg": chat_theme::ICON_LG,
                "iconXl": chat_theme::ICON_XL,
                "touchTarget": chat_theme::TOUCH_TARGET
            },
            "contrastRatio": chat_theme::contrast_ratio(fg, bg),
            "isAaOk": chat_theme::is_aa_ok(fg, bg)
        })
        .to_string())
    })
}

// ------------------------------------------------------------- Slice 5: ConcatApiClient --
//
// Satu fungsi JSON in/out (stateless per panggilan): request
// `{"op","token","args","previewDir"}`; respons
// `{"ok":true,"value":...}` atau
// `{"ok":false,"kind":"auth"|"down"|"api","message":...}`.
// Panic/error di boundary -> JSON error generik (`guarded_string`)
// -> Kotlin fallback ke implementasi Kotlin lama + matikan saklar
// `rustConcatApiClient`. Transport: `TcpConnector` (satu koneksi per
// panggilan, auth id 1, call id 2, cap baris 4 MiB).
#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_concatApiJson(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    guarded_string(env, |env| {
        let req = jstring_to_rust(env, &request);
        let parsed: serde_json::Value =
            serde_json::from_str(&req).map_err(|_| "bad request json".to_string())?;
        let op = concat_api_client::opt_string(&parsed, "op", "");
        let token = concat_api_client::opt_string(&parsed, "token", "");
        let args = parsed
            .get("args")
            .cloned()
            .unwrap_or(serde_json::Value::Null);
        let preview_dir = concat_api_client::opt_string(
            &parsed,
            "previewDir",
            "/sdcard/Pictures/Harness",
        );
        let mut tcp = concat_api_client::TcpConnector;
        match concat_api_client::dispatch(&mut tcp, &op, &token, &args, &preview_dir) {
            Ok(value) => Ok(serde_json::json!({"ok": true, "value": value}).to_string()),
            Err(e) => Ok(serde_json::json!({
                "ok": false,
                "kind": e.kind(),
                "message": e.message()
            })
            .to_string()),
        }
    })
}

// ------------------------------------------------------------- Slice 6: ToolRegistry + AgentPrompt + SkillTrigger --
//
// Tiga fungsi bulk JSON (pola slice 4): request `{"op", ...}`; respons
// `{"ok":true,"value":...}`; panic/error di boundary -> JSON error generik
// (`guarded_string`) -> Kotlin fallback ke implementasi Kotlin lama +
// matikan saklar `rustToolRegistry` / `rustAgentPrompt` / `rustSkillTrigger`.

fn json_string_array(v: &serde_json::Value, key: &str) -> Result<Vec<String>, String> {
    match v.get(key) {
        Some(serde_json::Value::Array(a)) => {
            let mut out = Vec::with_capacity(a.len());
            for item in a {
                match item.as_str() {
                    Some(s) => out.push(s.to_string()),
                    None => return Err(format!("{} bukan array string", key)),
                }
            }
            Ok(out)
        }
        _ => Err(format!("{} hilang/bukan array", key)),
    }
}

fn json_i64_array(v: &serde_json::Value, key: &str) -> Result<Vec<i64>, String> {
    match v.get(key) {
        Some(serde_json::Value::Array(a)) => {
            let mut out = Vec::with_capacity(a.len());
            for item in a {
                match item.as_i64() {
                    Some(n) => out.push(n),
                    None => return Err(format!("{} bukan array angka", key)),
                }
            }
            Ok(out)
        }
        _ => Err(format!("{} hilang/bukan array", key)),
    }
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_toolRegistryJson(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    guarded_string(env, |env| {
        let req = jstring_to_rust(env, &request);
        let parsed: serde_json::Value =
            serde_json::from_str(&req).map_err(|_| "bad request json".to_string())?;
        let op = concat_api_client::opt_string(&parsed, "op", "");
        let value = match op.as_str() {
            "catalogSection" => {
                serde_json::Value::String(tool_registry::catalog_section())
            }
            "browserSkillDetail" => {
                serde_json::Value::String(tool_registry::browser_skill_detail())
            }
            "names" => serde_json::json!(tool_registry::names()),
            "namesByKind" => {
                let kind = match concat_api_client::opt_string(&parsed, "kind", "").as_str() {
                    "READ" => tool_registry::Kind::Read,
                    "WRITE" => tool_registry::Kind::Write,
                    "MEMORY" => tool_registry::Kind::Memory,
                    _ => return Err("kind tidak dikenal".to_string()),
                };
                serde_json::json!(tool_registry::names_by_kind(kind))
            }
            "sectionCounts" => {
                let mut obj = serde_json::Map::new();
                for (section, n) in tool_registry::section_counts() {
                    obj.insert(section.to_string(), serde_json::json!(n));
                }
                serde_json::Value::Object(obj)
            }
            "nearestNames" => {
                let name = concat_api_client::opt_string(&parsed, "name", "");
                let count = parsed
                    .get("count")
                    .and_then(|c| c.as_i64())
                    .unwrap_or(3);
                let count = if count <= 0 { 0 } else { count as usize };
                serde_json::json!(tool_registry::nearest_names(&name, count))
            }
            _ => return Err("unknown op".to_string()),
        };
        Ok(serde_json::json!({"ok": true, "value": value}).to_string())
    })
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_agentPromptJson(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    guarded_string(env, |env| {
        let req = jstring_to_rust(env, &request);
        let parsed: serde_json::Value =
            serde_json::from_str(&req).map_err(|_| "bad request json".to_string())?;
        let op = concat_api_client::opt_string(&parsed, "op", "");
        let value = match op.as_str() {
            "selectCatalog" => {
                let full = concat_api_client::opt_string(&parsed, "full", "");
                let task = concat_api_client::opt_string(&parsed, "task", "");
                serde_json::Value::String(agent_prompt::select_catalog(&full, &task))
            }
            "continuationAnchor" => {
                let command = concat_api_client::opt_string(&parsed, "command", "");
                serde_json::Value::String(agent_prompt::continuation_anchor_vc316(&command))
            }
            "stripRawToolMarkup" => {
                let text = concat_api_client::opt_string(&parsed, "text", "");
                serde_json::Value::String(agent_prompt::strip_raw_tool_markup_for_display(&text))
            }
            "normalizeDsmlDelimiters" => {
                let text = concat_api_client::opt_string(&parsed, "text", "");
                serde_json::Value::String(agent_prompt::normalize_dsml_delimiters(&text))
            }
            "applyDoublePass" => {
                let answer = concat_api_client::opt_string(&parsed, "answer", "");
                let claims = json_string_array(&parsed, "claims")?;
                let unsupported = json_i64_array(&parsed, "unsupported")?;
                serde_json::Value::String(agent_prompt::apply_double_pass(
                    &answer,
                    &claims,
                    &unsupported,
                ))
            }
            _ => return Err("unknown op".to_string()),
        };
        Ok(serde_json::json!({"ok": true, "value": value}).to_string())
    })
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_skillTriggerJson(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    guarded_string(env, |env| {
        let req = jstring_to_rust(env, &request);
        let parsed: serde_json::Value =
            serde_json::from_str(&req).map_err(|_| "bad request json".to_string())?;
        let op = concat_api_client::opt_string(&parsed, "op", "");
        let value = match op.as_str() {
            "splitDescrSections" => {
                let descr = concat_api_client::opt_string(&parsed, "descr", "");
                let s = skill_trigger::split_descr_sections(&descr);
                serde_json::json!({
                    "positive": s.positive,
                    "negative": s.negative,
                    "markerFound": s.marker_found
                })
            }
            "negWordsOf" => {
                let descr = concat_api_client::opt_string(&parsed, "descr", "");
                let mut words: Vec<String> =
                    skill_trigger::neg_words_of(&descr).into_iter().collect();
                words.sort();
                serde_json::json!(words)
            }
            "triggerScore" => {
                let descr = concat_api_client::opt_string(&parsed, "descr", "");
                let query = concat_api_client::opt_string(&parsed, "query", "");
                let sc = skill_trigger::trigger_score(&descr, &query);
                serde_json::json!({
                    "trigger": sc.trigger,
                    "posOverlap": sc.pos_overlap,
                    "negOverlap": sc.neg_overlap,
                    "netScore": sc.net_score
                })
            }
            _ => return Err("unknown op".to_string()),
        };
        Ok(serde_json::json!({"ok": true, "value": value}).to_string())
    })
}

// ------------------------------------------------------- Slice 7: SQLite --
//
// Satu fungsi JNI generik untuk semua DB (rusqlite). Request JSON:
// ------------------------------------------------------------- Slice 8: HTTP --
// `{"op":"request"|"mcp_extract"|"shape_fingerprint"|"decode_word"|
// "gmail_headers"|"pkce_challenge"|"form_encode"|"form_body"|
// "oauth_auth_url"|"oauth_parse_callback"|"sse_apply",...}`.
// Respons `{"ok":true,...}` / `{"ok":false,...}`; `null` = Rust gagal
// (panic/error di boundary) -> pemanggil Kotlin WAJIB fallback ke
// implementasi lama.
#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_httpJson(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    guarded_string_nullable(env, |env| {
        let req = jstring_to_rust(env, &request);
        http::http_json(&req).ok()
    })
}

// `{"op":"ensure"|"query"|"exec"|"tx","db":<path>,"key":<kunci>,...}`.
// Respons `{"ok":true,...}`; `null` = Rust gagal (panic/error di
// boundary) -> pemanggil Kotlin WAJIB fallback ke implementasi lama.
#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_sqliteJson(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    guarded_string_nullable(env, |env| {
        let req = jstring_to_rust(env, &request);
        sqlite::sqlite_json(&req).ok()
    })
}

// Slice 9: server :4198 — `{"op":"parse_route"|"path_matches_other_method"|
// "parse_query"|"bearer_token"|"is_authorized"|"is_safe_name"|
// "slice_preview"|"preview_chars"|"hmac_sha256_hex"|"sign_queue_text"|
// "has_queue_signature"|"verify_queue_text"|"worker_scoped"|
// "parse_headers"|"build_response"|"idempotency_key",...}`.
// Respons `{"ok":true,...}` / `{"ok":false,...}`; `null` = Rust gagal
// (panic/error di boundary) -> pemanggil Kotlin WAJIB fallback ke
// implementasi lama.
#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_workerEndpointJson(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    guarded_string_nullable(env, |env| {
        let req = jstring_to_rust(env, &request);
        worker_endpoint::worker_endpoint_json(&req).ok()
    })
}

// Slice 10: daun murni Tools.kt — `{"op":"strip_html"|"parse_ddg"|
// "truncate_marked"|"yt_runs_text"|"yt_parse_video_renderer"|
// "extract_balanced_json"|"yt_walk_results"|"yt_scan_renderers"|
// "parse_yt_search"|"format_yt_results",...}`.
// Respons `{"ok":true,...}` / `{"ok":false,...}`; `null` = Rust gagal
// (panic/error di boundary) -> pemanggil Kotlin WAJIB fallback ke
// implementasi lama.
#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_toolsLeavesJson(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    guarded_string_nullable(env, |env| {
        let req = jstring_to_rust(env, &request);
        tools_leaves::tools_leaves_json(&req).ok()
    })
}

// Slice 11: split DsApiClient — `{"op":"find_session_id"|
// "find_challenge"|"find_file_id"|"est_tokens"|"safe_file_name"|
// "upload_file",...}`.
// Respons `{"ok":true,...}` / `{"ok":false,...}`; `null` = Rust gagal
// (panic/error di boundary) -> pemanggil Kotlin WAJIB fallback ke
// implementasi lama.
#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_dsApiJson(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    guarded_string_nullable(env, |env| {
        let req = jstring_to_rust(env, &request);
        ds_api::ds_api_json(&req).ok()
    })
}

// Slice 12: otak latar/room — `{"op":...}` per modul (BgBrain /
// GroupChat / DsAgent / OcCoord). Respons `{"ok":true,...}` /
// `{"ok":false,...}`; `null` = Rust gagal (panic/error di boundary)
// -> pemanggil Kotlin WAJIB fallback ke implementasi lama.
#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_bgBrainJson(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    guarded_string_nullable(env, |env| {
        let req = jstring_to_rust(env, &request);
        bg_brain::bg_brain_json(&req).ok()
    })
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_groupChatJson(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    guarded_string_nullable(env, |env| {
        let req = jstring_to_rust(env, &request);
        group_chat::group_chat_json(&req).ok()
    })
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_dsAgentJson(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    guarded_string_nullable(env, |env| {
        let req = jstring_to_rust(env, &request);
        ds_agent::ds_agent_json(&req).ok()
    })
}

#[no_mangle]
pub extern "C" fn Java_harness_core_RustCore_ocCoordJson(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    guarded_string_nullable(env, |env| {
        let req = jstring_to_rust(env, &request);
        oc_coord::oc_coord_json(&req).ok()
    })
}
