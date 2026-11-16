//! Port 1:1 dari `SessionBackgroundVc304.kt` (vc304 B2) — gerbang
//! render live giliran background. Logika murni: perbandingan id sesi
//! + satu string penanda. String penanda disalin VERBATIM (● U+25CF,
//! — U+2014).

#![forbid(unsafe_code)]

/// Output live giliran hanya dirender bila sesi pemilik giliran sedang aktif.
pub fn render_allowed(turn_session_id: i64, current_session_id: i64) -> bool {
    turn_session_id == current_session_id
}

/// Keputusan giliran (dialog +10 / approval tool) harus parkir bila
/// giliran berjalan di background — JANGAN tampil di sesi lain,
/// JANGAN auto-approve, JANGAN eksekusi HIGH tanpa PIN.
pub fn should_park_decision(
    turn_active: bool,
    turn_session_id: i64,
    current_session_id: i64,
) -> bool {
    turn_active && turn_session_id != current_session_id
}

/// Teks penanda terpisah untuk indikator status saat giliran sesi lain
/// berjalan di background — tanpa isi proses giliran itu.
pub fn background_notice() -> &'static str {
    "● Giliran sesi lain berjalan di background — kembali ke sesi itu untuk melihat progres."
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Contoh pemakaian nyata di tree: `MainActivity.kt:3118`
    /// (`renderAllowed(turnSessionId, currentSessionId)`),
    /// `:12925` (`shouldParkDecision(turnActive, ...)`),
    /// `:9283` (`backgroundNotice()`).
    #[test]
    fn render_allowed_matches_kotlin() {
        assert!(render_allowed(5, 5));
        assert!(!render_allowed(5, 6));
        assert!(!render_allowed(6, 5));
        assert!(render_allowed(0, 0));
        assert!(render_allowed(-1, -1));
        assert!(!render_allowed(i64::MAX, i64::MIN));
    }

    #[test]
    fn should_park_decision_matches_kotlin() {
        // Giliran aktif di sesi lain -> parkir.
        assert!(should_park_decision(true, 5, 6));
        // Giliran aktif di sesi sendiri -> tampil, jangan parkir.
        assert!(!should_park_decision(true, 5, 5));
        // Tidak ada giliran aktif -> tidak ada yang diparkir.
        assert!(!should_park_decision(false, 5, 6));
        assert!(!should_park_decision(false, 5, 5));
    }

    #[test]
    fn background_notice_verbatim() {
        assert_eq!(
            background_notice(),
            "● Giliran sesi lain berjalan di background — kembali ke sesi itu untuk melihat progres."
        );
    }
}
