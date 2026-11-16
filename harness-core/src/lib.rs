//! harness-core — core Rust Harness (hybrid Kotlin -> Rust).
//!
//! Satu crate, satu `.so` (`libharness_core.so`) untuk semua slice.
//! Modul Rust diorganisasi sebagai `mod` di crate yang sama
//! (`ds_failure`, `pow_solver`, `redact`, ...).
//!
//! Aturan seam (HYBRID-MIGRATION-MAP.md §3):
//! - String JSON in/out (default); `byte[]` untuk biner.
//! - JANGAN passing `Context`/objek Android ke Rust.
//! - Panic TIDAK BOLEH menyeberang FFI: `catch_unwind` di SETIAP
//!   boundary JNI, kembalikan JSON error.
//! - Jangan `unwrap()`/`expect()` di jalur input luar.
//! - Panic hook tanpa secret; jangan log input mentah.

// CATATAN: `#![forbid(unsafe_code)]` TIDAK ditaruh di crate root —
// `#[no_mangle]` di glue JNI masuk grup lint `unsafe_code` dan forbid
// crate-level tidak bisa di-override per modul. Sebagai gantinya,
// SETIAP modul non-glue membawa `#![forbid(unsafe_code)]` sendiri
// (lihat ds_failure.rs); glue JNI satu-satunya modul tanpa forbid.

pub mod behaviors;
pub mod chat_theme;
pub mod concat_api_client;
pub mod ds_failure;
pub mod ds_safety_strip;
pub mod mcp_helper;
pub mod notif_ids;
pub mod pow_solver;
pub mod redact_secrets;
pub mod session_background;
pub mod system_bar_spec;

mod jni_glue;

pub use jni_glue::RUST_CORE_VERSION;
