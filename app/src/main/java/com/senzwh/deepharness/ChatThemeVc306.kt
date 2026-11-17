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

import harness.core.RustCore

object ChatThemeVc306 {
    /**
     * Saklar A/B slice 4 (vc317): bila core Rust tersedia, konstanta +
     * kontras jalan di Rust; bila tidak, fallback ke implementasi Kotlin
     * lama di bawah (nilai literal + contrastRatioKotlin — JANGAN dihapus).
     * Konstanta dibaca sekali (lazy, di-cache) lewat satu panggilan JSON
     * bulk; tiap akses berikutnya hanya baca map (tanpa JNI).
     */
    var rustChatTheme: Boolean = true

    // ---- Seam Rust (vc317) ----

    private val themeNames = listOf(
        "darkBgTop", "darkBgBottom", "darkSurface", "darkContainerLow",
        "darkContainerHigh", "darkText", "darkDim", "darkFaint", "darkStroke",
        "darkTileBg", "darkUserBubble", "darkAccentText", "darkCodeText",
        "lightCodeText", "darkCodeBg", "lightCodeBg", "lightBgTop",
        "lightBgBottom", "lightSurface", "lightText", "lightDim", "lightFaint",
        "lightCard", "lightUserBubble", "accentSolid", "accentPressed",
        "approveBlue", "rXs", "rSm", "rMd", "rLg", "rXl", "iconXs", "iconSm",
        "iconMd", "iconLg", "iconXl", "touchTarget"
    )

    /** Konstanta tema dari Rust (sekali baca, di-cache); null = fallback Kotlin. */
    private val rustThemeConsts: Map<String, Int>? by lazy { rustThemeConstsOrNull() }

    private fun rustThemeConstsOrNull(): Map<String, Int>? {
        if (!rustChatTheme || !RustCore.available) return null
        return try {
            val json = RustCore.chatThemeJson(0, 0)
            if (!json.contains("\"ok\":true")) return null
            val out = HashMap<String, Int>()
            for (name in themeNames) {
                val v = Regex("\"" + name + "\":(-?\\d+)").find(json)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
                out[name] = v
            }
            out
        } catch (e: Throwable) {
            rustChatTheme = false
            null
        }
    }

    private fun themeConst(name: String, fallback: Int): Int = rustThemeConsts?.get(name) ?: fallback

    private fun rustContrastRatio(fg: Int, bg: Int): Double? {
        if (!rustChatTheme || !RustCore.available) return null
        return try {
            val json = RustCore.chatThemeJson(fg, bg)
            if (!json.contains("\"ok\":true")) return null
            Regex("\"contrastRatio\":([0-9eE+\\-.]+)").find(json)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        } catch (e: Throwable) {
            rustChatTheme = false
            null
        }
    }

    // ---- dark (Mist) ----
    val darkBgTop: Int get() = themeConst("darkBgTop", 0xFF1D222C.toInt())
    val darkBgBottom: Int get() = themeConst("darkBgBottom", 0xFF1B1C23.toInt())
    val darkSurface: Int get() = themeConst("darkSurface", 0xFF1C1F28.toInt())
    val darkContainerLow: Int get() = themeConst("darkContainerLow", 0xFF14171E.toInt())
    val darkContainerHigh: Int get() = themeConst("darkContainerHigh", 0xFF343944.toInt())
    val darkText: Int get() = themeConst("darkText", 0xFFE6E9F0.toInt())
    val darkDim: Int get() = themeConst("darkDim", 0xFFB6BBC5.toInt())
    val darkFaint: Int get() = themeConst("darkFaint", 0xFF9A9EA9.toInt())
    val darkStroke: Int get() = themeConst("darkStroke", 0xFF343841.toInt())
    val darkTileBg: Int get() = themeConst("darkTileBg", 0xFF232730.toInt())
    val darkUserBubble: Int get() = themeConst("darkUserBubble", 0xFF293144.toInt())
    val darkAccentText: Int get() = themeConst("darkAccentText", 0xFF97B1F0.toInt())
    // ---- kode (G11 vc310) ----
    // Warna blok kode / kode inline, dari mockup MOCKUP-SKILL-UI-B13-G11.html
    // (palet Mist mockup — acuan presisi user). darkCodeText #BEE0FF ~
    // hardcode lama rgb(190,220,255) di MdMini; darkCodeBg #14171E =
    // darkContainerLow yang sudah ada (dipakai ulang, bukan hex baru);
    // lightCodeText #173F65 + lightCodeBg #EDF0F6 dari mockup (hex baru
    // hanya karena mockup yang menetapkannya, bukan karangan).
    val darkCodeText: Int get() = themeConst("darkCodeText", 0xFFBEE0FF.toInt())
    val lightCodeText: Int get() = themeConst("lightCodeText", 0xFF173F65.toInt())
    val darkCodeBg: Int get() = themeConst("darkCodeBg", darkContainerLow)
    val lightCodeBg: Int get() = themeConst("lightCodeBg", 0xFFEDF0F6.toInt())
    // ---- light (Mist) ----
    val lightBgTop: Int get() = themeConst("lightBgTop", 0xFFEBF1FE.toInt())
    val lightBgBottom: Int get() = themeConst("lightBgBottom", 0xFFF6F7FC.toInt())
    val lightSurface: Int get() = themeConst("lightSurface", 0xFFF0F4FD.toInt())
    val lightText: Int get() = themeConst("lightText", 0xFF1E232E.toInt())
    val lightDim: Int get() = themeConst("lightDim", 0xFF4F5562.toInt())
    val lightFaint: Int get() = themeConst("lightFaint", 0xFF646974.toInt())
    val lightCard: Int get() = themeConst("lightCard", 0xFFFBFCFF.toInt())
    val lightUserBubble: Int get() = themeConst("lightUserBubble", 0xFFD9E4FD.toInt())
    // ---- accent (satu aksen Mist saja, E6) ----
    val accentSolid: Int get() = themeConst("accentSolid", 0xFF4B67B4.toInt())
    val accentPressed: Int get() = themeConst("accentPressed", 0xFF3D569D.toInt())
    val approveBlue: Int get() = themeConst("approveBlue", 0xFF2E6BE6.toInt())
    // ---- radius dp (C2) ----
    val rXs: Int get() = themeConst("rXs", 6)
    val rSm: Int get() = themeConst("rSm", 10)
    val rMd: Int get() = themeConst("rMd", 14)
    val rLg: Int get() = themeConst("rLg", 20)
    val rXl: Int get() = themeConst("rXl", 28)

    // ---- ukuran ikon chat dp (vc307, Worker B) ----
    // Nilai DASAR sebelum skala smallest-width (lihat
    // MainActivity.chatIconSizeVc307): sw < 360dp -> -2dp, 360-599dp ->
    // nilai dasar, >= 600dp -> +2dp (dibatasi 16..28dp). Semua ukuran
    // ikon Chat UI memakai token ini + skala itu; TIDAK ADA ukuran ikon
    // px mentah di Chat UI.
    // kecil: chip sheet, status slot, latest
    val iconXs: Int get() = themeConst("iconXs", 16)
    // stop, robot otomasi
    val iconSm: Int get() = themeConst("iconSm", 18)
    // mic, kirim
    val iconMd: Int get() = themeConst("iconMd", 20)
    // lampirkan, menu header, chat baru
    val iconLg: Int get() = themeConst("iconLg", 22)
    // tab bar, tile glyph kartu tool
    val iconXl: Int get() = themeConst("iconXl", 24)
    // Target sentuh minimum (skill #15 §4.1 / #14 §3) — hit area ikon
    // TIDAK PERNAH di bawah nilai ini walau visual ikon mengecil.
    val touchTarget: Int get() = themeConst("touchTarget", 48)

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
        rustContrastRatio(fg, bg)?.let { return it }
        return contrastRatioKotlin(fg, bg)
    }

    /** Implementasi Kotlin lama (fallback) — DIPERTAHANKAN. */
    private fun contrastRatioKotlin(fg: Int, bg: Int): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val (hi, lo) = if (l1 >= l2) l1 to l2 else l2 to l1
        return (hi + 0.05) / (lo + 0.05)
    }

    /** True bila pasangan fg/bg lolos WCAG AA untuk teks normal (>= 4.5:1). */
    fun isAaOk(fg: Int, bg: Int): Boolean = contrastRatio(fg, bg) >= 4.5
}
