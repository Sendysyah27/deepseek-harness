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

use crate::ds_failure;
use crate::pow_solver;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Once;

/// Versi seam JNI. Naikkan tiap kali signature berubah; Kotlin cek
/// saat load (`RustCore.available` false bila mismatch -> fallback).
/// v2 = slice 2 (pow_solver): +6 fungsi PoW.
pub const RUST_CORE_VERSION: jint = 2;

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
            "functions": ["rustCoreVersion", "rustCoreCapabilities", "echo", "classifyFailureJson",
                "powSha3Hex", "powDsHashV1Hex", "powSha3Bytes", "powDsHashV1Bytes",
                "powSolveDeepSeekJson", "powSolveFastJson", "powSolveFmtJson", "powSolveJson"],
            "modules": ["ds_failure", "pow_solver"]
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
