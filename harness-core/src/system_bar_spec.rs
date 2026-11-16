//! Port 1:1 dari `SystemBarSpecVc309.kt` — pemetaan MURNI tema ->
//! system bars (vc309 Worker A). Nilai warna diambil dari modul
//! `chat_theme` (bukan diduplikasi di sini) supaya satu sumber nilai.

#![forbid(unsafe_code)]

use crate::chat_theme;

/// Hasil pemetaan: warna status bar, warna nav bar, dan apakah ikon
/// system bar harus terang (true = tema gelap, flag appearance MATI).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Spec {
    pub status_color: i32,
    pub nav_color: i32,
    pub light_icons: bool,
}

pub fn spec(is_dark: bool) -> Spec {
    if is_dark {
        Spec {
            status_color: chat_theme::DARK_BG_TOP,
            nav_color: chat_theme::DARK_SURFACE,
            light_icons: true,
        }
    } else {
        Spec {
            status_color: chat_theme::LIGHT_BG_TOP,
            nav_color: chat_theme::LIGHT_CARD,
            light_icons: false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::chat_theme;

    /// Nilai-nilai ini disalin VERBATIM dari `SystemBarSpecVc309.kt`
    /// + `ChatThemeVc306.kt` (status = gradasi atas halaman,
    /// nav = warna tab bar / th.card).
    #[test]
    fn spec_matches_kotlin_verbatim() {
        let dark = spec(true);
        assert_eq!(dark.status_color, 0xFF1D_222Cu32 as i32);
        assert_eq!(dark.nav_color, 0xFF1C_1F28u32 as i32);
        assert!(dark.light_icons);
        assert_eq!(dark.status_color, chat_theme::DARK_BG_TOP);
        assert_eq!(dark.nav_color, chat_theme::DARK_SURFACE);

        let light = spec(false);
        assert_eq!(light.status_color, 0xFFEB_F1FEu32 as i32);
        assert_eq!(light.nav_color, 0xFFFB_FCFFu32 as i32);
        assert!(!light.light_icons);
        assert_eq!(light.status_color, chat_theme::LIGHT_BG_TOP);
        assert_eq!(light.nav_color, chat_theme::LIGHT_CARD);

        // Kedua tema berbeda di semua bidang.
        assert_ne!(dark, light);
    }
}
