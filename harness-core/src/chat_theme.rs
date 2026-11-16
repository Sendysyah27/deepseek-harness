//! Port 1:1 dari `ChatThemeVc306.kt` — tema chat "Mist" vc306.
//!
//! Semua warna bertipe `i32` ARGB, nilai persis seperti spek
//! (`0xFF...... .toInt()` Kotlin = `0xFF......u32 as i32` Rust —
//! literal di atas `i32::MAX`, jadi ditulis via `u32` lalu `as`).
//! Helper kontras WCAG 2.1 direplika eksak: `Math.pow` Kotlin ->
//! `f64::powf` Rust, ambang `0.03928`, eksponen `2.4`, bobot
//! `0.2126 / 0.7152 / 0.0722`.
//!
//! Catatan: `darkCodeBg` di Kotlin = alias `darkContainerLow`
//! (bukan hex baru) — dipertahankan sebagai alias di sini juga.

#![forbid(unsafe_code)]

// ---- dark (Mist) ----
pub const DARK_BG_TOP: i32 = 0xFF1D_222Cu32 as i32;
pub const DARK_BG_BOTTOM: i32 = 0xFF1B_1C23u32 as i32;
pub const DARK_SURFACE: i32 = 0xFF1C_1F28u32 as i32;
pub const DARK_CONTAINER_LOW: i32 = 0xFF14_171Eu32 as i32;
pub const DARK_CONTAINER_HIGH: i32 = 0xFF34_3944u32 as i32;
pub const DARK_TEXT: i32 = 0xFFE6_E9F0u32 as i32;
pub const DARK_DIM: i32 = 0xFFB6_BBC5u32 as i32;
pub const DARK_FAINT: i32 = 0xFF9A_9EA9u32 as i32;
pub const DARK_STROKE: i32 = 0xFF34_3841u32 as i32;
pub const DARK_TILE_BG: i32 = 0xFF23_2730u32 as i32;
pub const DARK_USER_BUBBLE: i32 = 0xFF29_3144u32 as i32;
pub const DARK_ACCENT_TEXT: i32 = 0xFF97_B1F0u32 as i32;
// ---- kode (G11 vc310) ----
pub const DARK_CODE_TEXT: i32 = 0xFFBE_E0FFu32 as i32;
pub const LIGHT_CODE_TEXT: i32 = 0xFF17_3F65u32 as i32;
pub const DARK_CODE_BG: i32 = DARK_CONTAINER_LOW;
pub const LIGHT_CODE_BG: i32 = 0xFFED_F0F6u32 as i32;
// ---- light (Mist) ----
pub const LIGHT_BG_TOP: i32 = 0xFFEB_F1FEu32 as i32;
pub const LIGHT_BG_BOTTOM: i32 = 0xFFF6_F7FCu32 as i32;
pub const LIGHT_SURFACE: i32 = 0xFFF0_F4FDu32 as i32;
pub const LIGHT_TEXT: i32 = 0xFF1E_232Eu32 as i32;
pub const LIGHT_DIM: i32 = 0xFF4F_5562u32 as i32;
pub const LIGHT_FAINT: i32 = 0xFF64_6974u32 as i32;
pub const LIGHT_CARD: i32 = 0xFFFB_FCFFu32 as i32;
pub const LIGHT_USER_BUBBLE: i32 = 0xFFD9_E4FDu32 as i32;
// ---- accent (satu aksen Mist saja, E6) ----
pub const ACCENT_SOLID: i32 = 0xFF4B_67B4u32 as i32;
pub const ACCENT_PRESSED: i32 = 0xFF3D_569Du32 as i32;
pub const APPROVE_BLUE: i32 = 0xFF2E_6BE6u32 as i32;
// ---- radius dp (C2) ----
pub const R_XS: i32 = 6;
pub const R_SM: i32 = 10;
pub const R_MD: i32 = 14;
pub const R_LG: i32 = 20;
pub const R_XL: i32 = 28;
// ---- ukuran ikon chat dp (vc307, Worker B) ----
pub const ICON_XS: i32 = 16;
pub const ICON_SM: i32 = 18;
pub const ICON_MD: i32 = 20;
pub const ICON_LG: i32 = 22;
pub const ICON_XL: i32 = 24;
// Target sentuh minimum — hit area ikon TIDAK PERNAH di bawah ini.
pub const TOUCH_TARGET: i32 = 48;

fn lin(channel: i32) -> f64 {
    let c = channel as f64 / 255.0;
    if c <= 0.03928 {
        c / 12.92
    } else {
        ((c + 0.055) / 1.055).powf(2.4)
    }
}

fn relative_luminance(argb: i32) -> f64 {
    let r = lin((argb >> 16) & 0xFF);
    let g = lin((argb >> 8) & 0xFF);
    let b = lin(argb & 0xFF);
    0.2126 * r + 0.7152 * g + 0.0722 * b
}

/// Rasio kontras WCAG antara fg dan bg (1.0 s.d. 21.0).
pub fn contrast_ratio(fg: i32, bg: i32) -> f64 {
    let l1 = relative_luminance(fg);
    let l2 = relative_luminance(bg);
    let (hi, lo) = if l1 >= l2 { (l1, l2) } else { (l2, l1) };
    (hi + 0.05) / (lo + 0.05)
}

/// True bila pasangan fg/bg lolos WCAG AA untuk teks normal (>= 4.5:1).
pub fn is_aa_ok(fg: i32, bg: i32) -> bool {
    contrast_ratio(fg, bg) >= 4.5
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Nilai-nilai ini disalin VERBATIM dari `ChatThemeVc306.kt`
    /// (hex spek); bandingkan sebagai i32 — `0xFF...... .toInt()`
    /// Kotlin = bilangan negatif yang sama di Rust.
    #[test]
    fn constants_match_kotlin_verbatim() {
        assert_eq!(DARK_BG_TOP, 0xFF1D_222Cu32 as i32);
        assert_eq!(DARK_BG_BOTTOM, 0xFF1B_1C23u32 as i32);
        assert_eq!(DARK_SURFACE, 0xFF1C_1F28u32 as i32);
        assert_eq!(DARK_CONTAINER_LOW, 0xFF14_171Eu32 as i32);
        assert_eq!(DARK_CONTAINER_HIGH, 0xFF34_3944u32 as i32);
        assert_eq!(DARK_TEXT, 0xFFE6_E9F0u32 as i32);
        assert_eq!(DARK_DIM, 0xFFB6_BBC5u32 as i32);
        assert_eq!(DARK_FAINT, 0xFF9A_9EA9u32 as i32);
        assert_eq!(DARK_STROKE, 0xFF34_3841u32 as i32);
        assert_eq!(DARK_TILE_BG, 0xFF23_2730u32 as i32);
        assert_eq!(DARK_USER_BUBBLE, 0xFF29_3144u32 as i32);
        assert_eq!(DARK_ACCENT_TEXT, 0xFF97_B1F0u32 as i32);
        assert_eq!(DARK_CODE_TEXT, 0xFFBE_E0FFu32 as i32);
        assert_eq!(LIGHT_CODE_TEXT, 0xFF17_3F65u32 as i32);
        assert_eq!(DARK_CODE_BG, DARK_CONTAINER_LOW);
        assert_eq!(LIGHT_CODE_BG, 0xFFED_F0F6u32 as i32);
        assert_eq!(LIGHT_BG_TOP, 0xFFEB_F1FEu32 as i32);
        assert_eq!(LIGHT_BG_BOTTOM, 0xFFF6_F7FCu32 as i32);
        assert_eq!(LIGHT_SURFACE, 0xFFF0_F4FDu32 as i32);
        assert_eq!(LIGHT_TEXT, 0xFF1E_232Eu32 as i32);
        assert_eq!(LIGHT_DIM, 0xFF4F_5562u32 as i32);
        assert_eq!(LIGHT_FAINT, 0xFF64_6974u32 as i32);
        assert_eq!(LIGHT_CARD, 0xFFFB_FCFFu32 as i32);
        assert_eq!(LIGHT_USER_BUBBLE, 0xFFD9_E4FDu32 as i32);
        assert_eq!(ACCENT_SOLID, 0xFF4B_67B4u32 as i32);
        assert_eq!(ACCENT_PRESSED, 0xFF3D_569Du32 as i32);
        assert_eq!(APPROVE_BLUE, 0xFF2E_6BE6u32 as i32);
        // Spot-check numerik (i32 desimal, dihitung independen):
        // 0xFF1D222C = -14867924 ; 0xFFE6E9F0 = -1644048 ; 0xFFEBF1FE = -1314306.
        assert_eq!(DARK_BG_TOP, -14867924);
        assert_eq!(DARK_TEXT, -1644048);
        assert_eq!(LIGHT_BG_TOP, -1314306);
        assert_eq!(R_XS, 6);
        assert_eq!(R_SM, 10);
        assert_eq!(R_MD, 14);
        assert_eq!(R_LG, 20);
        assert_eq!(R_XL, 28);
        assert_eq!(ICON_XS, 16);
        assert_eq!(ICON_SM, 18);
        assert_eq!(ICON_MD, 20);
        assert_eq!(ICON_LG, 22);
        assert_eq!(ICON_XL, 24);
        assert_eq!(TOUCH_TARGET, 48);
    }

    /// Rasio kontras dihitung independen (Python, rumus WCAG yang sama);
    /// toleransi 1e-9 menyerap beda ulp `Math.pow` vs `powf`.
    #[test]
    fn contrast_ratio_vectors() {
        // Hitam vs putih = 21.0 persis (batas atas WCAG).
        assert!((contrast_ratio(0xFF00_0000u32 as i32, 0xFFFF_FFFFu32 as i32) - 21.0).abs() < 1e-9);
        // Warna sama = 1.0 persis.
        assert!((contrast_ratio(DARK_TEXT, DARK_TEXT) - 1.0).abs() < 1e-9);
        // Simetris: fg/bg tertukar hasilnya sama.
        let a = contrast_ratio(DARK_TEXT, DARK_BG_TOP);
        let b = contrast_ratio(DARK_BG_TOP, DARK_TEXT);
        assert!((a - b).abs() < 1e-12);
        // Pasangan utama tema (klaim file Kotlin: semua lolos AA).
        assert!((contrast_ratio(DARK_TEXT, DARK_BG_TOP) - 13.115315986926786).abs() < 1e-9);
        assert!((contrast_ratio(LIGHT_TEXT, LIGHT_BG_TOP) - 13.891206116589315).abs() < 1e-9);
        assert!((contrast_ratio(DARK_TEXT, DARK_SURFACE) - 13.540972873182085).abs() < 1e-9);
        assert!((contrast_ratio(LIGHT_TEXT, LIGHT_CARD) - 15.332558737095383).abs() < 1e-9);
    }

    #[test]
    fn is_aa_ok_matches_kotlin_claim() {
        // Semua pasangan teks/latar utama lolos AA (>= 4.5).
        assert!(is_aa_ok(DARK_TEXT, DARK_BG_TOP));
        assert!(is_aa_ok(DARK_DIM, DARK_BG_TOP));
        assert!(is_aa_ok(LIGHT_TEXT, LIGHT_BG_TOP));
        assert!(is_aa_ok(LIGHT_DIM, LIGHT_BG_TOP));
        assert!(is_aa_ok(DARK_TEXT, DARK_SURFACE));
        assert!(is_aa_ok(LIGHT_TEXT, LIGHT_CARD));
        // Pasangan yang jelas gagal: abu-abu redup di atas abu-abu,
        // dan bubble user (latar, bukan teks) di atas latar halaman.
        assert!(!is_aa_ok(DARK_FAINT, DARK_CONTAINER_HIGH));
        assert!(!is_aa_ok(DARK_USER_BUBBLE, DARK_BG_TOP));
    }
}
