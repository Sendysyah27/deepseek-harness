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
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Once;

/// Versi seam JNI. Naikkan tiap kali signature berubah; Kotlin cek
/// saat load (`RustCore.available` false bila mismatch -> fallback).
pub const RUST_CORE_VERSION: jint = 1;

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
            "functions": ["rustCoreVersion", "rustCoreCapabilities", "echo", "classifyFailureJson"],
            "modules": ["ds_failure"]
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
