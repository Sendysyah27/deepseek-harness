// ChatThemeVc306.kt — tema chat "Mist" vc306 (samakan dsh-go).
//
// Sumber nilai mengikat: SPEC-SAMAKAN-CHAT-DSHGO.md (a.1)/(a.2)/(a.3),
// C1 (palet), C2 (radius), E6 (satu aksen Mist saja).
// Pure Kotlin object TANPA import Android — JVM-testable (lihat
// TestChatUiVc306.kt). Semua warna bertipe Int ARGB (0xFF... .toInt()
// karena literal hex 0xFF...... tidak muat di Int Kotlin bila ditulis
// mentah — nilainya tetap persis seperti spek).
//
// Penyimpangan vs skill #14 (mobile-design-room-ui) — SKILL MENANG bila
// konflik, tapi tugas vc306 ini MEMERINTAHKAN eksplisit nilai Mist di
// bawah (diasumsikan keputusan E1 user sudah diambil oleh koordinator):
//  - skill #14 §1/§5: identitas "dark navy gelap, kartu radius 18px,
//    jangan ganti palet tanpa perintah eksplisit" — diganti palet Mist +
//    skala radius 6/10/14/20/28 (C1/C2) atas perintah tugas ini.
//  - skill #14 §4: line-height acuan 1,3–1,5 — chat body memakai 1,65
//    (a.2/C3/C4) atas perintah tugas ini.
//  - skill #14 §8: "jangan campur radius di satu layar" — skala radius
//    C2 memang mencampur radius per peran (bubble 20, kartu 14, dialog
//    28) atas perintah tugas ini.
// Yang DIJAGA dari skill: target sentuh 48dp (skill #14 §3 / #15 §4.1),
// kontras AA >= 4.5:1 (skill #15 §2 — semua pasangan di bawah lolos,
// dibuktikan TestChatUiVc306.kt), contentDescription di tombol ikon
// (skill #15 §3.1), teks pakai sp (skill #15 §3.3).
package com.senzwh.deepharness

object ChatThemeVc306 {
    // ---- dark (Mist) ----
    val darkBgTop: Int = 0xFF1D222C.toInt()
    val darkBgBottom: Int = 0xFF1B1C23.toInt()
    val darkSurface: Int = 0xFF1C1F28.toInt()
    val darkContainerLow: Int = 0xFF14171E.toInt()
    val darkContainerHigh: Int = 0xFF343944.toInt()
    val darkText: Int = 0xFFE6E9F0.toInt()
    val darkDim: Int = 0xFFB6BBC5.toInt()
    val darkFaint: Int = 0xFF9A9EA9.toInt()
    val darkStroke: Int = 0xFF343841.toInt()
    val darkTileBg: Int = 0xFF232730.toInt()
    val darkUserBubble: Int = 0xFF293144.toInt()
    val darkAccentText: Int = 0xFF97B1F0.toInt()
    // ---- light (Mist) ----
    val lightBgTop: Int = 0xFFEBF1FE.toInt()
    val lightBgBottom: Int = 0xFFF6F7FC.toInt()
    val lightSurface: Int = 0xFFF0F4FD.toInt()
    val lightText: Int = 0xFF1E232E.toInt()
    val lightDim: Int = 0xFF4F5562.toInt()
    val lightFaint: Int = 0xFF646974.toInt()
    val lightCard: Int = 0xFFFBFCFF.toInt()
    val lightUserBubble: Int = 0xFFD9E4FD.toInt()
    // ---- accent (satu aksen Mist saja, E6) ----
    val accentSolid: Int = 0xFF4B67B4.toInt()
    val accentPressed: Int = 0xFF3D569D.toInt()
    val approveBlue: Int = 0xFF2E6BE6.toInt()
    // ---- radius dp (C2) ----
    val rXs: Int = 6
    val rSm: Int = 10
    val rMd: Int = 14
    val rLg: Int = 20
    val rXl: Int = 28

    // ---- ukuran ikon chat dp (vc307, Worker B) ----
    // Nilai DASAR sebelum skala smallest-width (lihat
    // MainActivity.chatIconSizeVc307): sw < 360dp -> -2dp, 360-599dp ->
    // nilai dasar, >= 600dp -> +2dp (dibatasi 16..28dp). Semua ukuran
    // ikon Chat UI memakai token ini + skala itu; TIDAK ADA ukuran ikon
    // px mentah di Chat UI.
    val iconXs: Int = 16 // kecil: chip sheet, status slot, latest
    val iconSm: Int = 18 // stop, robot otomasi
    val iconMd: Int = 20 // mic, kirim
    val iconLg: Int = 22 // lampirkan, menu header, chat baru
    val iconXl: Int = 24 // tab bar, tile glyph kartu tool
    // Target sentuh minimum (skill #15 §4.1 / #14 §3) — hit area ikon
    // TIDAK PERNAH di bawah nilai ini walau visual ikon mengecil.
    val touchTarget: Int = 48

    // ---- helper kontras WCAG 2.1 ----
    private fun relativeLuminance(argb: Int): Double {
        fun lin(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = lin((argb shr 16) and 0xFF)
        val g = lin((argb shr 8) and 0xFF)
        val b = lin(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Rasio kontras WCAG antara fg dan bg (1.0 s.d. 21.0). */
    fun contrastRatio(fg: Int, bg: Int): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val (hi, lo) = if (l1 >= l2) l1 to l2 else l2 to l1
        return (hi + 0.05) / (lo + 0.05)
    }

    /** True bila pasangan fg/bg lolos WCAG AA untuk teks normal (>= 4.5:1). */
    fun isAaOk(fg: Int, bg: Int): Boolean = contrastRatio(fg, bg) >= 4.5
}
