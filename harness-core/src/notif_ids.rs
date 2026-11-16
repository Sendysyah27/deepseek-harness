//! Port 1:1 dari `NotifIds.kt` (vc246, registry ID notifikasi terpusat).
//!
//! Perilaku HARUS identik dengan Kotlin, termasuk:
//! - Semua konstanta ID (angka mentah TIDAK BOLEH berubah — tabrakan
//!   historis 7703/7704/7708 didokumentasikan di file Kotlin).
//! - Alokator dinamis `bgResultId`/`bgFailId`/`bgSummaryId`/
//!   `bgWatchdogId`/`workerJanitorId` memakai `String.hashCode()`
//!   Java — direplika eksak di `java_string_hash` (iterasi unit
//!   UTF-16, `h = 31*h + unit`, wrapping i32). Iterasi `char` Rust
//!   (scalar Unicode) atau byte UTF-8 akan memberi hash BERBEDA untuk
//!   string berisi emoji (surrogate pair) — karena itu TIDAK dipakai.
//! - `checkDuplicates()` memakai urutan kemunculan seperti Kotlin
//!   (HashSet + ArrayList penampung duplikat).

#![forbid(unsafe_code)]

pub const SCHEDULE: i32 = 7701;
pub const PARK_QUEUE: i32 = 7703;
pub const SECOND_CONFIRM: i32 = 7704;
pub const MEMORY_PROPOSAL: i32 = 7706;
pub const PRIV_PAIR: i32 = 7708;
pub const BG_SUMMARY_BASE: i32 = 7790;
pub const KILL_SWITCH_CANCEL: i32 = 7798;
pub const GOVERNOR_DELAY: i32 = 7799;
pub const SERVE_DOWN: i32 = 7103;
pub const HEAL_DIAGNOSIS: i32 = 7102;
pub const GROUP_DONE: i32 = 7900;
pub const MITM_DOWN: i32 = 7722;
pub const BG_WATCHDOG: i32 = 7723;
pub const BG_WATCHDOG_BASE: i32 = 8400;
pub const WORKER_JANITOR_BASE: i32 = 8800;
pub const BG_RESULT_BASE: i32 = 7800;
pub const BG_FAIL_BASE: i32 = 8100;

/// Daftar registry persis seperti `NotifIds.all()` Kotlin
/// (urutan sama — dipakai `checkDuplicates()`).
pub const ALL: [(&str, i32); 12] = [
    ("SCHEDULE", SCHEDULE),
    ("PARK_QUEUE", PARK_QUEUE),
    ("SECOND_CONFIRM", SECOND_CONFIRM),
    ("MEMORY_PROPOSAL", MEMORY_PROPOSAL),
    ("PRIV_PAIR", PRIV_PAIR),
    ("KILL_SWITCH_CANCEL", KILL_SWITCH_CANCEL),
    ("GOVERNOR_DELAY", GOVERNOR_DELAY),
    ("SERVE_DOWN", SERVE_DOWN),
    ("HEAL_DIAGNOSIS", HEAL_DIAGNOSIS),
    ("GROUP_DONE", GROUP_DONE),
    ("MITM_DOWN", MITM_DOWN),
    ("BG_WATCHDOG", BG_WATCHDOG),
];

pub fn all() -> Vec<(&'static str, i32)> {
    ALL.to_vec()
}

/// Replika eksak `String.hashCode()` Java/Kotlin.
pub fn java_string_hash(s: &str) -> i32 {
    let mut h: i32 = 0;
    for unit in s.encode_utf16() {
        h = h.wrapping_mul(31).wrapping_add(unit as i32);
    }
    h
}

/// `BG_RESULT_BASE + (origin.hashCode() and 0xFF)` — 0..255, tak negatif.
pub fn bg_result_id(origin: &str) -> i32 {
    BG_RESULT_BASE + (java_string_hash(origin) & 0xFF)
}

pub fn bg_fail_id(origin: &str) -> i32 {
    BG_FAIL_BASE + (java_string_hash(origin) & 0xFF)
}

pub fn bg_summary_id(origin: &str) -> i32 {
    BG_SUMMARY_BASE + ((java_string_hash(origin) & 0xFF) % 8)
}

pub fn bg_watchdog_id(key: &str) -> i32 {
    BG_WATCHDOG_BASE + (java_string_hash(key) & 0xFF)
}

pub fn worker_janitor_id(worker_id: &str) -> i32 {
    WORKER_JANITOR_BASE + (java_string_hash(worker_id) & 0xFF)
}

/// Mengembalikan id yang dipakai lebih dari satu nama (harus kosong).
pub fn check_duplicates() -> Vec<i32> {
    let mut seen: Vec<i32> = Vec::new();
    let mut dup: Vec<i32> = Vec::new();
    for (_, id) in ALL {
        if seen.contains(&id) {
            dup.push(id);
        } else {
            seen.push(id);
        }
    }
    dup
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Nilai-nilai ini disalin VERBATIM dari `NotifIds.kt`
    /// (bukan dihitung ulang dari modul — tes harus menangkap typo pindah data).
    #[test]
    fn constants_match_kotlin_verbatim() {
        assert_eq!(SCHEDULE, 7701);
        assert_eq!(PARK_QUEUE, 7703);
        assert_eq!(SECOND_CONFIRM, 7704);
        assert_eq!(MEMORY_PROPOSAL, 7706);
        assert_eq!(PRIV_PAIR, 7708);
        assert_eq!(BG_SUMMARY_BASE, 7790);
        assert_eq!(KILL_SWITCH_CANCEL, 7798);
        assert_eq!(GOVERNOR_DELAY, 7799);
        assert_eq!(SERVE_DOWN, 7103);
        assert_eq!(HEAL_DIAGNOSIS, 7102);
        assert_eq!(GROUP_DONE, 7900);
        assert_eq!(MITM_DOWN, 7722);
        assert_eq!(BG_WATCHDOG, 7723);
        assert_eq!(BG_WATCHDOG_BASE, 8400);
        assert_eq!(WORKER_JANITOR_BASE, 8800);
        assert_eq!(BG_RESULT_BASE, 7800);
        assert_eq!(BG_FAIL_BASE, 8100);
    }

    #[test]
    fn all_list_matches_kotlin() {
        let got = all();
        assert_eq!(got.len(), 12);
        assert_eq!(got[0], ("SCHEDULE", 7701));
        assert_eq!(got[4], ("PRIV_PAIR", 7708));
        assert_eq!(got[11], ("BG_WATCHDOG", 7723));
    }

    #[test]
    fn check_duplicates_empty() {
        assert!(check_duplicates().is_empty());
    }

    /// Vektor hashCode dihitung independen (Python, algoritma Java
    /// `31*h + unit UTF-16`); "test" = 3556498 adalah nilai Java
    /// yang sudah dikenal — validasi algoritma, bukan modul.
    #[test]
    fn java_string_hash_vectors() {
        assert_eq!(java_string_hash("test"), 3556498);
        assert_eq!(java_string_hash(""), 0);
        assert_eq!(java_string_hash("a"), 97);
        assert_eq!(java_string_hash("jadwal"), -1167599179);
        assert_eq!(java_string_hash("worker-1"), 35352994);
        assert_eq!(java_string_hash("BgBrain"), 1458948497);
        // Emoji = surrogate pair D83D DE00: hash dihitung atas DUA
        // unit UTF-16, bukan satu scalar U+1F600.
        assert_eq!(java_string_hash("\u{1F600}emoji"), -1233692477);
    }

    /// Contoh pemakaian nyata di tree: `MainActivity.kt:15962`
    /// memakai origin "jadwal"; `BgBrain.kt:775` memakai key tugas.
    #[test]
    fn allocator_ids_match_kotlin_usage() {
        assert_eq!(bg_result_id("jadwal"), 7981);
        assert_eq!(bg_fail_id("jadwal"), 8281);
        assert_eq!(bg_summary_id("jadwal"), 7795);
        assert_eq!(bg_result_id("worker-1"), 7962);
        assert_eq!(bg_fail_id("worker-1"), 8262);
        assert_eq!(bg_summary_id("worker-1"), 7792);
        assert_eq!(bg_watchdog_id("tugas-abc"), 8605);
        assert_eq!(worker_janitor_id("worker-1"), 8962);
        assert_eq!(worker_janitor_id("worker-42"), 8845);
        // Batas: string kosong -> base persis; hash negatif -> tetap
        // di dalam rentang (and 0xFF tak pernah negatif).
        assert_eq!(bg_result_id(""), 7800);
        assert_eq!(bg_fail_id(""), 8100);
        assert_eq!(bg_summary_id(""), 7790);
        assert_eq!(bg_watchdog_id(""), 8400);
        assert_eq!(worker_janitor_id(""), 8800);
        for origin in ["jadwal", "tugas-abc", "Tugas latar", "\u{1F600}emoji"] {
            let r = bg_result_id(origin);
            assert!((7800..=8055).contains(&r), "{origin} -> {r}");
            let s = bg_summary_id(origin);
            assert!((7790..=7797).contains(&s), "{origin} -> {s}");
        }
    }

    /// Alokasi latar (vc263) TIDAK BOLEH menabrak registry —
    /// replika cek `MainActivity.kt:15961-15962`.
    #[test]
    fn allocated_ids_do_not_collide_with_registry() {
        let reg: Vec<i32> = all().into_iter().map(|(_, id)| id).collect();
        for origin in ["jadwal", "BgBrain", "harness", "sesi-123"] {
            assert!(!reg.contains(&bg_result_id(origin)));
            assert!(!reg.contains(&bg_fail_id(origin)));
            assert!(!reg.contains(&bg_summary_id(origin)));
            // Alokasi latar juga tidak boleh sama dengan GROUP_DONE (7900).
            assert_ne!(bg_result_id(origin), GROUP_DONE);
            assert_ne!(bg_fail_id(origin), GROUP_DONE);
            assert_ne!(bg_summary_id(origin), GROUP_DONE);
        }
    }
}
