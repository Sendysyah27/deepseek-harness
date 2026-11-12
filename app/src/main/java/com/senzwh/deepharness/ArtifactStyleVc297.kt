package com.senzwh.deepharness

/** vc297 — Artifacts anti-slop + bundle.
 *  Fungsi MURNI (tanpa Android, tanpa DB, tanpa jaringan) — bisa unit-test
 *  standalone seperti SkillJudge vc293 / MemoryLoopVc294 / BriefingVc295 /
 *  RoteVc296. File + Termux + ArtifactStore dilakukan pemanggil (Tools.kt).
 *
 *  Sumber pola (SKILL.md dibaca langsung, bukan impor mentah):
 *  - frontend-design: 2 pass (plan token 4-6 hex + ASCII wireframe -> review
 *    keunikan -> build); "satu elemen berani, sisanya diam". Diterjemahkan:
 *    PROSE_ARTIFACT_VC297 (ToolRegistry) + linter di bawah.
 *  - premium-web-design: blacklist font/gradien/layout AI + katalog 15 DNA
 *    struktural + "satu elemen berani". Diterjemahkan: linter R1-R9 +
 *    prose (DNA wajib dinamai satu konsep).
 *  - artifacts-builder (== web-artifacts-builder, diff hanya nama): bundle
 *    semua kode jadi SATU file HTML. Diterjemahkan: bundleInline (Kotlin)
 *    + skrip worker bundle-artifact.mjs (Node stdlib, tanpa dep).
 *  - ui-ux-pro-max: 50 style / 21 palet / 50 font pairing (data CSV).
 *    Diterjemahkan MINIMAL: 6 pairing + 5 palet (font utamanya TIDAK masuk
 *    blacklist) sebagai data statis.
 *  - theme-factory: 10 tema siap pakai. Diterjemahkan VERBATIM (nama + 4 hex
 *    + font + bestFor). Font tema ditulis apa adanya dari folder themes
 *    (DejaVu/FreeSans = font desktop skill) — untuk artifact web, agen
 *    memetakan ke pairing Google Fonts terdekat (lihat prose).
 *
 *  Linter = PERINGATAN, bukan blokir keras: tidak ada cek yang bisa
 *  memastikan "jelek" vs "sengaja" (mis. gradien ungu bisa jadi pilihan
 *  sadar untuk tema Midnight Galaxy; emoji bisa jadi isi). Yang linter
 *  pastikan = sinyal template terdeteksi + dilaporkan; keputusan akhir di
 *  tangan agen (prose: SLOP wajib diperbaiki sebelum dinyatakan jadi).
 */
object ArtifactStyleVc297 {
    // Skrip worker Node stdlib (bundle-artifact.mjs) — di-embed base64 oleh
    // patch-vc297.py dari vc297-work/bundle-artifact.mjs. Dijalankan di
    // Termux via runTerminal (mode worker): tanpa dep, tanpa npm install.
    const val BUNDLE_SCRIPT_B64 = "Ly8gYnVuZGxlLWFydGlmYWN0Lm1qcyDigJQgdmMyOTcgQVJUSUZBQ1RTIEFOVEktU0xPUCArIEJVTkRMRQovLyBTa3JpcCBidWlsZCBhcnRpZmFjdCBzZWJhZ2FpIFRPT0wgV09SS0VSIChUZXJtdXgpOiBnYWJ1bmdrYW4ga29tcG9uZW4KLy8gcHJveWVrIChIVE1MICsgQ1NTICsgSlMgKyBnYW1iYXIgbG9rYWwga2VjaWwpIGphZGkgU0FUVSBmaWxlIEhUTUwgbWFuZGlyaQovLyB5YW5nIHRldGFwIHNhdHUgYmFyaXMgZGkgQXJ0aWZhY3RTdG9yZSBTUUxpdGUsIGRpYnVrYSBkaSBXZWJWaWV3IHRlcmlzb2xhc2kKLy8gdGFucGEgc2VydmVyLiBOb2RlIFNURExJQiBTQUpBIOKAlCB0YW5wYSBkZXBlbmRlbnNpICh0YW5wYSBWaXRlL1BhcmNlbC9ucG0KLy8gaW5zdGFsbCk6IGphbGFuIG9mZmxpbmUgZGkgVGVybXV4IEhQIChub2RlIHYyNiB0ZXJ2ZXJpZmlrYXNpIDExIE5vdiAyMDI2KS4KLy8KLy8gUGVtYWthaWFuOiBub2RlIGJ1bmRsZS1hcnRpZmFjdC5tanMgPGRpci1wcm95ZWs+IDxvdXQuaHRtbD4gW2VudHJ5PWluZGV4Lmh0bWxdCi8vICAgLSA8bGluayByZWw9InN0eWxlc2hlZXQiIGhyZWY9Imxva2FsLmNzcyI+ICAtPiA8c3R5bGU+IC4uLiA8L3N0eWxlPgovLyAgICAgKHRlcm1hc3VrIEBpbXBvcnQgImxva2FsLmNzcyIgcmVrdXJzaWYsIGtlZGFsYW1hbiBtYWtzIDUsIGFudGktc2lrbHVzKQovLyAgIC0gPHNjcmlwdCBzcmM9Imxva2FsLmpzIj48L3NjcmlwdD4gICAgICAgICAgLT4gPHNjcmlwdD4gLi4uIDwvc2NyaXB0PgovLyAgICAgKGltcG9ydCByZWxhdGlmICIuL3guanMiIGRpIGRhbGFtIEpTIFRJREFLIGRpLXJlc29sdmUg4oCUIGRpY2F0YXQgd2FybmluZykKLy8gICAtIDxpbWcgc3JjPSJsb2thbC5wbmciPiAoPD0yMDAgS0IpICAgICAgICAgIC0+IGRhdGEgVVJJIGJhc2U2NAovLyAgICAgKGxlYmloIGJlc2FyIGRpYmlhcmthbiBhcGEgYWRhbnlhICsgd2FybmluZzsgVVJMIGh0dHAocykgZGliaWFya2FuKQovLyAgIC0gZmlsZSBsb2thbCBoaWxhbmcgLT4ga29tZW50YXIgSFRNTCArIGRpY2F0YXQgZGkgcmluZ2thc2FuIChidWthbiBmYXRhbCkKLy8gS2VsdWFyOiBiYXJpcyBKU09OIHJpbmdrYXNhbiBrZSBzdGRvdXQ7IGV4aXQgMCBiaWxhIGVudHJ5IHRlcmJhY2EKLy8gKHdhbGF1IGFkYSBmaWxlIGhpbGFuZyDigJQgaXR1IHdhcm5pbmcpLCBleGl0IDEgYmlsYSBlbnRyeSB0aWRhayBhZGEuCmltcG9ydCBmcyBmcm9tICdub2RlOmZzJzsKaW1wb3J0IHBhdGggZnJvbSAnbm9kZTpwYXRoJzsKCmNvbnN0IE1BWF9JTUdfQllURVMgPSAyMDAgKiAxMDI0Owpjb25zdCBNQVhfQ1NTX0RFUFRIID0gNTsKCmNvbnN0IGRpciA9IHByb2Nlc3MuYXJndlsyXTsKY29uc3Qgb3V0ID0gcHJvY2Vzcy5hcmd2WzNdOwpjb25zdCBlbnRyeU5hbWUgPSBwcm9jZXNzLmFyZ3ZbNF0gfHwgJ2luZGV4Lmh0bWwnOwpjb25zdCByb290RGlyID0gcGF0aC5yZXNvbHZlKGRpcik7IC8vIGZvbGRlciBwcm95ZWssIGFic29sdXQg4oCUIGphbmdrYXIgY2xhbXAgam9pbkluc2lkZSAoTjYtbTIyKS4KCmNvbnN0IHN1bW1hcnkgPSB7IG9rOiBmYWxzZSwgb3V0OiBvdXQgfHwgJycsIGJ5dGVzOiAwLCBjc3M6IDAsIGpzOiAwLCBpbWc6IDAsIG1pc3Npbmc6IFtdLCB3YXJuaW5nczogW10gfTsKCmZ1bmN0aW9uIGlzRXh0ZXJuYWwoaHJlZikgewogIHJldHVybiAvXihodHRwcz86KT9cL1wvL2kudGVzdChocmVmKSB8fCBocmVmLnN0YXJ0c1dpdGgoJ2RhdGE6JykgfHwgaHJlZi5zdGFydHNXaXRoKCcjJyk7Cn0KCmZ1bmN0aW9uIG1pbWVGb3IoZmlsZSkgewogIGNvbnN0IGV4dCA9IHBhdGguZXh0bmFtZShmaWxlKS50b0xvd2VyQ2FzZSgpOwogIGlmIChleHQgPT09ICcucG5nJykgcmV0dXJuICdpbWFnZS9wbmcnOwogIGlmIChleHQgPT09ICcuanBnJyB8fCBleHQgPT09ICcuanBlZycpIHJldHVybiAnaW1hZ2UvanBlZyc7CiAgaWYgKGV4dCA9PT0gJy5naWYnKSByZXR1cm4gJ2ltYWdlL2dpZic7CiAgaWYgKGV4dCA9PT0gJy5zdmcnKSByZXR1cm4gJ2ltYWdlL3N2Zyt4bWwnOwogIGlmIChleHQgPT09ICcud2VicCcpIHJldHVybiAnaW1hZ2Uvd2VicCc7CiAgaWYgKGV4dCA9PT0gJy5pY28nKSByZXR1cm4gJ2ltYWdlL3gtaWNvbic7CiAgcmV0dXJuICdhcHBsaWNhdGlvbi9vY3RldC1zdHJlYW0nOwp9CgpmdW5jdGlvbiBqb2luSW5zaWRlKGJhc2VEaXIsIGhyZWYpIHsKICAvLyBDZXJtaW4gS290bGluIGpvaW5EaXIgKEFydGlmYWN0U3R5bGVWYzI5Ny5rdCk6ICIuLiIgeWFuZyBtZWxld2F0aSByb290CiAgLy8gcHJveWVrIERJQlVBTkcgKGJ1a2FuIGxvbG9zIGtlIGx1YXIgZm9sZGVyKSwgcGF0aCBhYnNvbHV0IGRpcGVybGFrdWthbgogIC8vIHNlYmFnYWkgcmVsYXRpZiB0ZXJoYWRhcCBkaXJla3RvcmkgZmlsZSB5YW5nIHNlZGFuZyBkaXByb3Nlcy4KICAvLyBiYXNlRGlyIFNFTEFMVSBkaSBkYWxhbSByb290RGlyIChlbnRyeSB0ZXItY2xhbXAsIENTUyB0dXJ1bmFuIGRhcmkgZmlsZQogIC8vIHlhbmcgc3VkYWggdGVyLWNsYW1wKSwgamFkaSB0dW1wdWthbiBhd2FsID0gcG9zaXNpIGJhc2VEaXIgcmVsYXRpZgogIC8vIHRlcmhhZGFwIHJvb3REaXIg4oCUIHBlcnNpcyBzZXBlcnRpIGpvaW5EaXIoZGlyLCBocmVmKSBkaSBLb3RsaW4uCiAgY29uc3QgcmVsQmFzZSA9IHBhdGgucmVsYXRpdmUocm9vdERpciwgYmFzZURpcik7CiAgY29uc3Qgc3RhY2sgPSAocmVsQmFzZSA9PT0gJycgfHwgcmVsQmFzZSA9PT0gJy4uJyB8fCByZWxCYXNlLnN0YXJ0c1dpdGgoJy4uJyArIHBhdGguc2VwKSkKICAgID8gW10KICAgIDogcmVsQmFzZS5zcGxpdChwYXRoLnNlcCkuZmlsdGVyKHMgPT4gcyAhPT0gJycgJiYgcyAhPT0gJy4nKTsKICBjb25zdCBjbGVhbiA9IGhyZWYudHJpbSgpLnJlcGxhY2UoL15cLlwvLywgJycpLnJlcGxhY2UoL15cLysvLCAnJyk7CiAgZm9yIChjb25zdCBwIG9mIGNsZWFuLnNwbGl0KCcvJykpIHsKICAgIGlmIChwID09PSAnLi4nKSB7IGlmIChzdGFjay5sZW5ndGggPiAwKSBzdGFjay5wb3AoKTsgfQogICAgZWxzZSBpZiAocCAhPT0gJy4nICYmIHAgIT09ICcnKSBzdGFjay5wdXNoKHApOwogIH0KICByZXR1cm4gcGF0aC5qb2luKHJvb3REaXIsIC4uLnN0YWNrKTsKfQoKZnVuY3Rpb24gaW5saW5lQ3NzKGNzcywgYmFzZURpciwgZGVwdGgsIHNlZW4pIHsKICAvLyBSZXNvbHZlIEBpbXBvcnQgInguY3NzIiAvIEBpbXBvcnQgdXJsKCJ4LmNzcyIpIHJla3Vyc2lmLgogIHJldHVybiBjc3MucmVwbGFjZSgvQGltcG9ydFxzKyg/OnVybFwoXHMqWyInXT8oW14iJyldKylbIiddP1xzKlwpfFsiJ10oW14iJ10rKVsiJ10pXHMqOy9naSwgKG0sIHUxLCB1MikgPT4gewogICAgY29uc3QgaHJlZiA9ICh1MSB8fCB1MiB8fCAnJykudHJpbSgpOwogICAgaWYgKCFocmVmIHx8IGlzRXh0ZXJuYWwoaHJlZikpIHJldHVybiBtOwogICAgaWYgKGRlcHRoID49IE1BWF9DU1NfREVQVEgpIHsgc3VtbWFyeS53YXJuaW5ncy5wdXNoKCdDU1MgQGltcG9ydCB0ZXJsYWx1IGRhbGFtIChtYWtzIDUpOiAnICsgaHJlZik7IHJldHVybiBtOyB9CiAgICBjb25zdCBmID0gam9pbkluc2lkZShiYXNlRGlyLCBocmVmKTsKICAgIGlmIChzZWVuLmhhcyhmKSkgeyBzdW1tYXJ5Lndhcm5pbmdzLnB1c2goJ0NTUyBAaW1wb3J0IHNpa2x1cywgZGlsZXdhdGk6ICcgKyBocmVmKTsgcmV0dXJuICcnOyB9CiAgICBpZiAoIWZzLmV4aXN0c1N5bmMoZikpIHsgc3VtbWFyeS5taXNzaW5nLnB1c2goaHJlZik7IHJldHVybiAnLyogTUlTU0lORyBDU1M6ICcgKyBocmVmICsgJyAqLyc7IH0KICAgIHNlZW4uYWRkKGYpOwogICAgc3VtbWFyeS5jc3MrKzsKICAgIHJldHVybiAnXG4nICsgaW5saW5lQ3NzKGZzLnJlYWRGaWxlU3luYyhmLCAndXRmOCcpLCBwYXRoLmRpcm5hbWUoZiksIGRlcHRoICsgMSwgc2VlbikgKyAnXG4nOwogIH0pOwp9CgpmdW5jdGlvbiBtYWluKCkgewogIGNvbnN0IGVudHJ5UGF0aCA9IGpvaW5JbnNpZGUoZGlyLCBlbnRyeU5hbWUpOwogIGlmICghZnMuZXhpc3RzU3luYyhlbnRyeVBhdGgpKSB7CiAgICBjb25zb2xlLmxvZyhKU09OLnN0cmluZ2lmeSh7IG9rOiBmYWxzZSwgZXJyb3I6ICdlbnRyeSB0aWRhayBhZGE6ICcgKyBlbnRyeVBhdGggfSkpOwogICAgcHJvY2Vzcy5leGl0KDEpOwogIH0KICBjb25zdCBiYXNlRGlyID0gcGF0aC5kaXJuYW1lKHBhdGgucmVzb2x2ZShlbnRyeVBhdGgpKTsKICBsZXQgaHRtbCA9IGZzLnJlYWRGaWxlU3luYyhlbnRyeVBhdGgsICd1dGY4Jyk7CgogIGh0bWwgPSBodG1sLnJlcGxhY2UoLzxsaW5rW14+XSpyZWw9WyInXXN0eWxlc2hlZXRbIiddW14+XSpocmVmPVsiJ10oW14iJ10rKVsiJ11bXj5dKj4vZ2ksIChtLCBocmVmKSA9PiB7CiAgICBpZiAoaXNFeHRlcm5hbChocmVmKSkgcmV0dXJuIG07CiAgICBjb25zdCBmID0gam9pbkluc2lkZShiYXNlRGlyLCBocmVmKTsKICAgIGlmICghZnMuZXhpc3RzU3luYyhmKSkgeyBzdW1tYXJ5Lm1pc3NpbmcucHVzaChocmVmKTsgcmV0dXJuICc8IS0tIE1JU1NJTkcgQ1NTOiAnICsgaHJlZiArICcgLS0+JzsgfQogICAgc3VtbWFyeS5jc3MrKzsKICAgIGNvbnN0IGNzcyA9IGlubGluZUNzcyhmcy5yZWFkRmlsZVN5bmMoZiwgJ3V0ZjgnKSwgcGF0aC5kaXJuYW1lKGYpLCAwLCBuZXcgU2V0KFtmXSkpOwogICAgcmV0dXJuICc8c3R5bGU+XG4nICsgY3NzICsgJ1xuPC9zdHlsZT4nOwogIH0pOwoKICBodG1sID0gaHRtbC5yZXBsYWNlKC88c2NyaXB0W14+XSpzcmM9WyInXShbXiInXSspWyInXVtePl0qPlxzKjxcL3NjcmlwdD4vZ2ksIChtLCBzcmMpID0+IHsKICAgIGlmIChpc0V4dGVybmFsKHNyYykpIHJldHVybiBtOwogICAgY29uc3QgZiA9IGpvaW5JbnNpZGUoYmFzZURpciwgc3JjKTsKICAgIGlmICghZnMuZXhpc3RzU3luYyhmKSkgeyBzdW1tYXJ5Lm1pc3NpbmcucHVzaChzcmMpOyByZXR1cm4gJzwhLS0gTUlTU0lORyBKUzogJyArIHNyYyArICcgLS0+JzsgfQogICAgY29uc3QganMgPSBmcy5yZWFkRmlsZVN5bmMoZiwgJ3V0ZjgnKTsKICAgIGlmICgvZnJvbVxzK1siJ11cLlteIiddKlsiJ10vLnRlc3QoanMpIHx8IC9pbXBvcnRcKFxzKlsiJ11cLlteIiddKlsiJ11ccypcKS8udGVzdChqcykpIHsKICAgICAgc3VtbWFyeS53YXJuaW5ncy5wdXNoKCdKUyAnICsgc3JjICsgJyBwdW55YSBpbXBvcnQgcmVsYXRpZiDigJQgdGlkYWsgZGktcmVzb2x2ZSBkaSBidW5kbGUgc2F0dSBmaWxlOyBnYWJ1bmdrYW4gbWFudWFsIGF0YXUgamFkaWthbiBzYXR1IGZpbGUuJyk7CiAgICB9CiAgICBzdW1tYXJ5LmpzKys7CiAgICByZXR1cm4gJzxzY3JpcHQ+XG4nICsganMgKyAnXG48L3NjcmlwdD4nOwogIH0pOwoKICBodG1sID0gaHRtbC5yZXBsYWNlKC88aW1nKFtePl0qKVxzc3JjPVsiJ10oW14iJ10rKVsiJ10vZ2ksIChtLCBhdHRycywgc3JjKSA9PiB7CiAgICBpZiAoaXNFeHRlcm5hbChzcmMpKSByZXR1cm4gbTsKICAgIGNvbnN0IGYgPSBqb2luSW5zaWRlKGJhc2VEaXIsIHNyYyk7CiAgICBpZiAoIWZzLmV4aXN0c1N5bmMoZikpIHsgc3VtbWFyeS5taXNzaW5nLnB1c2goc3JjKTsgcmV0dXJuICc8IS0tIE1JU1NJTkcgSU1HOiAnICsgc3JjICsgJyAtLT4nICsgbS5yZXBsYWNlKC9zcmM9WyInXVteIiddK1siJ10vLCAnc3JjPSIiJyk7IH0KICAgIGNvbnN0IHNpemUgPSBmcy5zdGF0U3luYyhmKS5zaXplOwogICAgaWYgKHNpemUgPiBNQVhfSU1HX0JZVEVTKSB7CiAgICAgIHN1bW1hcnkud2FybmluZ3MucHVzaCgnSU1HICcgKyBzcmMgKyAnICcgKyBzaXplICsgJyBieXRlICg+MjAwIEtCKSDigJQgZGliaWFya2FuIHNlYmFnYWkgZmlsZSwgdGlkYWsgZGktaW5saW5lOyBhcnRpZmFjdCBidXR1aCBmaWxlIGl0dSB0ZXRhcCBhZGEuJyk7CiAgICAgIHJldHVybiBtOwogICAgfQogICAgc3VtbWFyeS5pbWcrKzsKICAgIGNvbnN0IGRhdGFVcmkgPSAnZGF0YTonICsgbWltZUZvcihmKSArICc7YmFzZTY0LCcgKyBmcy5yZWFkRmlsZVN5bmMoZikudG9TdHJpbmcoJ2Jhc2U2NCcpOwogICAgcmV0dXJuICc8aW1nJyArIGF0dHJzICsgJyBzcmM9IicgKyBkYXRhVXJpICsgJyInOwogIH0pOwoKICBmcy53cml0ZUZpbGVTeW5jKG91dCwgaHRtbCwgJ3V0ZjgnKTsKICBzdW1tYXJ5Lm9rID0gdHJ1ZTsKICBzdW1tYXJ5LmJ5dGVzID0gQnVmZmVyLmJ5dGVMZW5ndGgoaHRtbCwgJ3V0ZjgnKTsKICBjb25zb2xlLmxvZyhKU09OLnN0cmluZ2lmeShzdW1tYXJ5KSk7Cn0KCm1haW4oKTsK"
    const val MAX_BUNDLED_CHARS = 500000
    const val MAX_IMG_BYTES = 200 * 1024
    const val MAX_CSS_DEPTH = 5

    // ---------- linter gaya (deterministik) ----------

    data class StyleWarning(val rule: String, val detail: String)
    data class LintResult(val warnings: List<StyleWarning>) {
        val verdict: String
            get() = when {
                warnings.isEmpty() -> "BERSIH"
                warnings.size <= 2 -> "PERINGATAN"
                else -> "SLOP"
            }
    }

    // Blacklist font premium-web-design (Inter Tight BOLEH — keluarga lain).
    private val BLACKLIST_FONTS = setOf("inter", "poppins", "montserrat", "raleway", "space grotesk", "outfit")
    private val PURPLE_TOKENS = listOf("#8b5cf6", "#7c3aed", "#6366f1", "#a855f7", "#9333ea", "#6d28d9", "violet", "purple")
    private val BLUE_TOKENS = listOf("#3b82f6", "#2563eb", "#1d4ed8", "#60a5fa", "#1e40af", "blue")

    // Isi tiap gradient(...) — jendela = isi fungsi sampai tutup kurung
    // yang seimbang (+ margin 4 char), BUKAN 400 char mentah: jendela lama
    // bisa menjalar ke aturan CSS berikut dan salah memicu R1 (N6-m24).
    // Margin 4 sengaja sekecil ini: token warna terpendek ("blue", 4 char)
    // dari aturan berikut butuh pemisah dulu, jadi butuh >=5 char sesudah
    // ")" untuk termuat utuh — margin 4 tidak pernah memuatnya.
    private fun gradientBodies(html: String): List<String> {
        val out = ArrayList<String>()
        val low = html.lowercase()
        var idx = 0
        while (true) {
            val g = low.indexOf("gradient(", idx)
            if (g < 0) break
            var depth = 1
            var i = g + 9 // tepat sesudah "gradient("
            while (i < low.length && depth > 0) {
                when (low[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            out.add(low.substring(g, minOf(i + 4, html.length)))
            idx = g + 9
        }
        return out
    }

    private fun firstFamilies(html: String): List<String> {
        val out = ArrayList<String>()
        val re = Regex("font-family\\s*:\\s*([^;}{]+)", RegexOption.IGNORE_CASE)
        for (m in re.findAll(html)) {
            val first = m.groupValues[1].split(",").firstOrNull()?.trim()?.trim('\'', '"', ' ')?.lowercase() ?: continue
            if (first.isNotEmpty()) out.add(first)
        }
        return out
    }

    private fun hexColors(html: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (m in Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})\\b").findAll(html)) out.add(m.value.lowercase())
        return out
    }

    private fun containsEmoji(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if ((cp in 0x1F300..0x1FAFF) || (cp in 0x2600..0x27BF)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun countRe(html: String, pattern: String): Int =
        Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).count()

    /** 9 aturan deterministik. R1-R9 dipetakan ke blacklist
     *  premium-web-design + frontend-design (lihat header file). */
    fun lint(html: String): LintResult {
        val w = ArrayList<StyleWarning>()
        // R1 gradien ungu-biru (klise AI terbesar).
        val bodies = gradientBodies(html)
        if (bodies.any { b -> PURPLE_TOKENS.any { b.contains(it) } && BLUE_TOKENS.any { b.contains(it) } }) {
            w.add(StyleWarning("GRADIEN-UNGU-BIRU", "gradien mengandung ungu + biru sekaligus — klise AI terbesar; ganti dengan satu warna aksen yang punya alasan."))
        }
        // R2 font blacklist (font pertama tiap font-family).
        val fams = firstFamilies(html)
        for (f in fams.filter { it in BLACKLIST_FONTS }.toSet()) {
            w.add(StyleWarning("FONT-BLACKLIST", "font utama '$f' masuk daftar hitam (Inter/Poppins/Montserrat/Raleway/Space Grotesk/Outfit) — ganti dengan pairing dari artifact_themes."))
        }
        // R3 satu font untuk semua.
        if (fams.isNotEmpty() && fams.toSet().size == 1) {
            w.add(StyleWarning("SATU-FONT-SEMUA", "hanya satu font ('${fams[0]}') untuk semua — pakai dua font yang jelas beda (display + body)."))
        }
        // R4 centered berlebihan.
        val centered = countRe(html, "text-align\\s*:\\s*center")
        if (centered >= 4) {
            w.add(StyleWarning("CENTER-BERLEBIHAN", "text-align:center muncul ${centered}x — layout semua-tengah terbaca template; ratakan kiri + satu momen tengah saja."))
        }
        // R5 radius seragam (8px >=4x, atau satu nilai radius >=5x).
        val r8 = countRe(html, "border-radius\\s*:\\s*8px")
        if (r8 >= 4) {
            w.add(StyleWarning("RADIUS-SERAGAM", "border-radius:8px muncul ${r8}x — radius seragam di semua kartu; bedakan radius per hierarki (0 tajam editorial / besar hanya untuk satu elemen)."))
        } else {
            val radii = Regex("border-radius\\s*:\\s*([^;}{]+)", RegexOption.IGNORE_CASE).findAll(html).map { it.groupValues[1].trim().lowercase() }.toList()
            val top = radii.groupingBy { it }.eachCount().maxByOrNull { it.value }
            if (top != null && top.value >= 5) {
                w.add(StyleWarning("RADIUS-SERAGAM", "radius '${top.key}' muncul ${top.value}x — radius seragam di mana-mana; bedakan per hierarki."))
            }
        }
        // R6 gradien sebagai dekorasi (>=2x background gradient).
        val gradCount = countRe(html, "background(-image)?\\s*:\\s*[^;{]*?(linear|radial)-gradient")
        if (gradCount >= 2) {
            w.add(StyleWarning("GRADIEN-DEKORASI", "gradien dipakai sebagai dekorasi ${gradCount}x — gradien tanpa alasan = wash; pakai warna solid + satu aksen."))
        }
        // R7 blob blur (border-radius:50% + blur).
        if ((html.contains("border-radius: 50%", ignoreCase = true) || html.contains("border-radius:50%", ignoreCase = true)) && html.contains("blur(", ignoreCase = true)) {
            w.add(StyleWarning("BLOB", "blob blur (border-radius:50% + blur) sebagai dekorasi — hapus, ganti dengan satu elemen visual yang relevan dengan isi."))
        }
        // R8 emoji sebagai ikon (ui-ux-pro-max: pakai SVG, bukan emoji).
        if (containsEmoji(html)) {
            w.add(StyleWarning("EMOJI-IKON", "emoji terdeteksi di konten — jangan pakai emoji sebagai ikon (pakai SVG); emoji di teks biasa boleh bila memang isi."))
        }
        // R9 palet tipis (plan minta token 4-6 hex).
        val hex = hexColors(html)
        if (hex.size <= 3) {
            w.add(StyleWarning("PALET-TIPIS", "palet hanya ${hex.size} warna hex — plan minta token 4-6 hex bernama."))
        }
        return LintResult(w)
    }

    fun lintSummary(r: LintResult): String {
        val sb = StringBuilder()
        sb.append("verdict: ").append(r.verdict).append(" (").append(r.warnings.size).append(" peringatan)")
        for (w in r.warnings) sb.append("\n- [").append(w.rule).append("] ").append(w.detail)
        if (r.verdict == "SLOP") sb.append("\nWajib diperbaiki sebelum artifact dinyatakan jadi (edit_artifact + lint ulang via artifact_lint).")
        return sb.toString()
    }

    // ---------- database desain (statis, minimal) ----------

    // 10 tema theme-factory — VERBATIM dari folder themes (nama + 4 hex +
    // font + bestFor). Font ditulis apa adanya (DejaVu/FreeSans = font
    // desktop skill); untuk artifact web petakan ke pairing di bawah.
    data class DesignTheme(val name: String, val colors: List<Pair<String, String>>, val headerFont: String, val bodyFont: String, val bestFor: String)
    val THEMES: List<DesignTheme> = listOf(
        DesignTheme("Ocean Depths", listOf("Deep Navy" to "#1a2332", "Teal" to "#2d8b8b", "Seafoam" to "#a8dadc", "Cream" to "#f1faee"), "DejaVu Sans Bold", "DejaVu Sans", "korporat, laporan keuangan, konsultan"),
        DesignTheme("Sunset Boulevard", listOf("Burnt Orange" to "#e76f51", "Coral" to "#f4a261", "Warm Sand" to "#e9c46a", "Deep Purple" to "#264653"), "DejaVu Serif Bold", "DejaVu Sans", "creative pitch, marketing, lifestyle"),
        DesignTheme("Forest Canopy", listOf("Forest Green" to "#2d4a2b", "Sage" to "#7d8471", "Olive" to "#a4ac86", "Ivory" to "#faf9f6"), "FreeSerif Bold", "FreeSans", "lingkungan, sustainability, wellness"),
        DesignTheme("Modern Minimalist", listOf("Charcoal" to "#36454f", "Slate Gray" to "#708090", "Light Gray" to "#d3d3d3", "White" to "#ffffff"), "DejaVu Sans Bold", "DejaVu Sans", "tech, arsitektur, portofolio desain"),
        DesignTheme("Golden Hour", listOf("Mustard Yellow" to "#f4a900", "Terracotta" to "#c1666b", "Warm Beige" to "#d4b896", "Chocolate Brown" to "#4a403a"), "FreeSans Bold", "FreeSans", "restoran, hospitality, artisan"),
        DesignTheme("Arctic Frost", listOf("Ice Blue" to "#d4e4f7", "Steel Blue" to "#4a6fa5", "Silver" to "#c0c0c0", "Crisp White" to "#fafafa"), "DejaVu Sans Bold", "DejaVu Sans", "kesehatan, teknologi, clean tech"),
        DesignTheme("Desert Rose", listOf("Dusty Rose" to "#d4a5a5", "Clay" to "#b87d6d", "Sand" to "#e8d5c4", "Deep Burgundy" to "#5d2e46"), "FreeSans Bold", "FreeSans", "fashion, beauty, wedding, boutique"),
        DesignTheme("Tech Innovation", listOf("Electric Blue" to "#0066ff", "Neon Cyan" to "#00ffff", "Dark Gray" to "#1e1e1e", "White" to "#ffffff"), "DejaVu Sans Bold", "DejaVu Sans", "tech startup, AI/ML, software"),
        DesignTheme("Botanical Garden", listOf("Fern Green" to "#4a7c59", "Marigold" to "#f9a620", "Terracotta" to "#b7472a", "Cream" to "#f5f3ed"), "DejaVu Serif Bold", "DejaVu Sans", "garden, food, farm-to-table, botanical"),
        DesignTheme("Midnight Galaxy", listOf("Deep Purple" to "#2b1e3e", "Cosmic Blue" to "#4a4e8f", "Lavender" to "#a490c2", "Silver" to "#e6e6fa"), "FreeSans Bold", "FreeSans", "entertainment, gaming, nightlife")
    )

    // 6 font pairing — font display/body dari premium-web-design (yang
    // direkomendasikan) + ui-ux-pro-max typography.csv; font utamanya
    // TIDAK ADA yang masuk blacklist (Inter/Poppins/Montserrat/Raleway/
    // Space Grotesk/Outfit tidak dipakai sebagai display di sini).
    data class FontPairing(val name: String, val display: String, val body: String, val bestFor: String)
    val FONT_PAIRINGS: List<FontPairing> = listOf(
        FontPairing("Classic Elegant", "Playfair Display", "DM Sans", "luxury, fashion, editorial, spa"),
        FontPairing("Fraunces Modern", "Fraunces", "Instrument Sans", "editorial modern, premium"),
        FontPairing("Editorial Classic", "Cormorant Garamond", "Libre Baskerville", "publishing, blog, majalah"),
        FontPairing("Fashion Forward", "Syne", "Manrope", "fashion, agensi kreatif, galeri"),
        FontPairing("Developer Mono", "JetBrains Mono", "IBM Plex Sans", "developer tools, dokumentasi, tech"),
        FontPairing("Premium Clean", "Instrument Serif", "DM Sans", "premium bersih, korporat modern")
    )

    // 5 palet ui-ux-pro-max colors.csv (per tipe produk; hex verbatim).
    data class Palette(val name: String, val hexes: List<String>, val bestFor: String)
    val PALETTES: List<Palette> = listOf(
        Palette("Luxury Gelap", listOf("#1C1917", "#44403C", "#CA8A04", "#FAFAF9"), "e-commerce luxury, premium"),
        Palette("B2B Profesional", listOf("#0F172A", "#334155", "#0369A1", "#F8FAFC"), "B2B, layanan profesional, pemerintahan"),
        Palette("Kesehatan Tenang", listOf("#0891B2", "#22D3EE", "#059669", "#ECFEFF"), "kesehatan, edukasi"),
        Palette("Agensi Berani", listOf("#EC4899", "#F472B6", "#06B6D4", "#FDF2F8"), "agensi kreatif, portofolio"),
        Palette("Portofolio Monokrom", listOf("#18181B", "#3F3F46", "#2563EB", "#FAFAFA"), "portofolio personal, dashboard")
    )

    /** Daftar tema/pairing/palet — tanpa query = semua; dengan query =
     *  yang namanya / bestFor-nya / font-nya cocok (case-insensitive).
     *  Tidak cocok = daftar nama (jangan mengarang tema). */
    fun themesText(query: String?): String {
        val q = query?.trim()?.lowercase() ?: ""
        val themes = if (q.isEmpty()) THEMES else THEMES.filter { it.name.lowercase().contains(q) || it.bestFor.lowercase().contains(q) }
        val pairs = if (q.isEmpty()) FONT_PAIRINGS else FONT_PAIRINGS.filter { it.name.lowercase().contains(q) || it.bestFor.lowercase().contains(q) || it.display.lowercase().contains(q) || it.body.lowercase().contains(q) }
        val pals = if (q.isEmpty()) PALETTES else PALETTES.filter { it.name.lowercase().contains(q) || it.bestFor.lowercase().contains(q) }
        if (themes.isEmpty() && pairs.isEmpty() && pals.isEmpty()) {
            return "Tidak ada tema/pairing/palet yang cocok dengan '$query'.\nTema: " + THEMES.joinToString(", ") { it.name } + "\nPairing font: " + FONT_PAIRINGS.joinToString(", ") { it.name } + "\nPalet: " + PALETTES.joinToString(", ") { it.name }
        }
        val sb = StringBuilder()
        if (themes.isNotEmpty()) {
            sb.append("TEMA (theme-factory, 10 — font ditulis apa adanya dari skill; untuk artifact web petakan ke pairing Google Fonts di bawah):\n")
            for (t in themes) {
                sb.append("- ").append(t.name).append(": ").append(t.colors.joinToString(", ") { it.first + " " + it.second }).append(" | header ").append(t.headerFont).append(" / body ").append(t.bodyFont).append(" | ").append(t.bestFor).append("\n")
            }
        }
        if (pairs.isNotEmpty()) {
            sb.append("PAIRING FONT (tanpa font blacklist):\n")
            for (p in pairs) sb.append("- ").append(p.name).append(": display '").append(p.display).append("' + body '").append(p.body).append("' | ").append(p.bestFor).append("\n")
        }
        if (pals.isNotEmpty()) {
            sb.append("PALET (ui-ux-pro-max):\n")
            for (p in pals) sb.append("- ").append(p.name).append(": ").append(p.hexes.joinToString(", ")).append(" | ").append(p.bestFor).append("\n")
        }
        return sb.toString().trim()
    }

    fun designConstitutionSummary(): String {
        return "KONSTITUSI DESAIN ARTIFACT (vc297):\n" +
            "1) DUA PASS: plan dulu (token 4-6 hex + ASCII wireframe + 1 DNA struktur + 1 elemen berani + tema) -> review keunikan -> baru build.\n" +
            "2) BLACKLIST: font Inter/Poppins/Montserrat/Raleway/Space Grotesk/Outfit; gradien ungu-biru; semua-tengah; radius 8px di mana-mana; blob blur; emoji sebagai ikon.\n" +
            "3) LINTER: otomatis di create/edit artifact html/app (peringatan, bukan blokir); SLOP (>=3 peringatan) wajib diperbaiki sebelum dinyatakan jadi.\n" +
            "4) BUNDLE: artifact_bundle {dir, mode} — auto/inline/vite@4/worker; hasil tetap SATU file HTML di store, dibuka WebView tanpa server.\n" +
            "5) TEMA: artifact_themes {query} — 10 tema + 6 pairing font + 5 palet."
    }

    fun bundleHowTo(): String {
        return "CARA BUNDLE ARTIFACT (vc297):\n" +
            "1) Tulis komponen ke satu folder di /sdcard (mis. /sdcard/Harness/artifacts/kalkulator/): index.html + style.css + app.js (+ gambar kecil) — pakai write_file.\n" +
            "2) Di chat 1-1 panggil artifact_bundle {\"dir\": \"/sdcard/Harness/artifacts/kalkulator\"} — mode auto: tanpa package.json langsung inline; ada package.json -> coba Vite dulu.\n" +
            "3) Mode lain: {\"mode\": \"inline\"} (Kotlin, tanpa Node), {\"mode\": \"vite\"} (proyek Vite — HANYA vite@4; pasang dulu: run_terminal proot worker1 -> cd <dir> && npm install -D vite@4), {\"mode\": \"worker\"} (skrip Node stdlib di Termux).\n" +
            "4) Hasil = artifact baru (tipe app/html) di store — buka dari kartu chat / list_artifacts. Gambar >200 KB dan import JS relatif TIDAK ikut ter-inline (dicatat di hasil tool)."
    }

    // ---------- bundler inline (cermin bundle-artifact.mjs) ----------

    data class BundleResult(val html: String, val cssCount: Int, val jsCount: Int, val imgCount: Int, val missing: List<String>, val warnings: List<String>)

    /** Gabung komponen jadi SATU file HTML — cermin persis logika
     *  bundle-artifact.mjs (Node stdlib): <link rel=stylesheet href>
     *  lokal -> <style> (termasuk @import rekursif, maks 5, anti-siklus);
     *  <script src> lokal -> <script> (import relatif "./x.js" TIDAK
     *  di-resolve — dicatat warning); <img src> lokal <=200 KB -> data URI
     *  base64; URL http(s)/data: dibiarkan; file hilang -> komentar HTML +
     *  dicatat (bukan fatal). readText/readBytes/encodeBase64 disuntik
     *  pemanggil supaya fungsi ini tetap murni (tanpa File/Android).
     *  `rel` = path relatif terhadap root proyek, pemisah "/".
     *  `entryRel` = path entry relatif terhadap root (default "index.html"):
     *  href/src di entry di-resolve relatif terhadap DIREKTORI entry
     *  (vc299, samakan dengan Node — dulu relatif terhadap root). */
    fun bundleInline(
        entryHtml: String,
        readText: (String) -> String?,
        readBytes: (String) -> ByteArray?,
        encodeBase64: (ByteArray) -> String,
        entryRel: String = "index.html"
    ): BundleResult {
        val missing = ArrayList<String>()
        val warnings = ArrayList<String>()
        var cssN = 0
        var jsN = 0
        var imgN = 0
        fun isExternal(h: String): Boolean {
            val l = h.lowercase()
            return l.startsWith("http://") || l.startsWith("https://") || l.startsWith("//") || l.startsWith("data:") || l.startsWith("#")
        }
        fun joinDir(dir: String, href: String): String {
            val clean = href.trim().removePrefix("./")
            val parts = (if (dir.isEmpty()) emptyList() else dir.split("/")) + clean.split("/")
            val out = ArrayList<String>()
            for (p in parts) {
                when {
                    p == ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                    p != "." && p.isNotEmpty() -> out.add(p)
                }
            }
            return out.joinToString("/")
        }
        fun dirOf(path: String): String = path.substringBeforeLast("/", "")
        fun inlineCss(css: String, fromDir: String, depth: Int, seen: MutableSet<String>): String {
            val re = Regex("""@import\s+(?:url\(\s*["']?([^"')]+)["']?\s*\)|["']([^"']+)["'])\s*;""", RegexOption.IGNORE_CASE)
            return re.replace(css) { m ->
                val href = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
                if (href.isEmpty() || isExternal(href)) m.value
                else if (depth >= MAX_CSS_DEPTH) {
                    warnings.add("CSS @import terlalu dalam (maks 5): $href")
                    m.value
                } else {
                    val rel = joinDir(fromDir, href)
                    if (rel in seen) {
                        warnings.add("CSS @import siklus, dilewati: $href")
                        ""
                    } else {
                        val txt = readText(rel)
                        if (txt == null) {
                            missing.add(href)
                            "/* MISSING CSS: $href */"
                        } else {
                            seen.add(rel)
                            cssN++
                            "\n" + inlineCss(txt, dirOf(rel), depth + 1, seen) + "\n"
                        }
                    }
                }
            }
        }
        val entryDir = dirOf(entryRel)
        var html = entryHtml
        val linkRe = Regex("""<link[^>]*rel=["']stylesheet["'][^>]*href=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
        html = linkRe.replace(html) { m ->
            val href = m.groupValues[1]
            if (isExternal(href)) m.value
            else {
                val rel = joinDir(entryDir, href)
                val txt = readText(rel)
                if (txt == null) {
                    missing.add(href)
                    "<!-- MISSING CSS: $href -->"
                } else {
                    cssN++
                    "<style>\n" + inlineCss(txt, dirOf(rel), 0, HashSet<String>().also { it.add(rel) }) + "\n</style>"
                }
            }
        }
        val scriptRe = Regex("""<script[^>]*src=["']([^"']+)["'][^>]*>\s*</script>""", RegexOption.IGNORE_CASE)
        html = scriptRe.replace(html) { m ->
            val src = m.groupValues[1]
            if (isExternal(src)) m.value
            else {
                val rel = joinDir(entryDir, src)
                val txt = readText(rel)
                if (txt == null) {
                    missing.add(src)
                    "<!-- MISSING JS: $src -->"
                } else {
                    if (Regex("""from\s+["']\.[^"']*["']""").containsMatchIn(txt) || Regex("""import\(\s*["']\.[^"']*["']\s*\)""").containsMatchIn(txt)) {
                        warnings.add("JS $src punya import relatif — tidak di-resolve di bundle satu file; gabungkan manual atau jadikan satu file.")
                    }
                    jsN++
                    "<script>\n" + txt + "\n</script>"
                }
            }
        }
        val imgRe = Regex("""<img([^>]*)\ssrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        html = imgRe.replace(html) { m ->
            val attrs = m.groupValues[1]
            val src = m.groupValues[2]
            if (isExternal(src)) m.value
            else {
                val rel = joinDir(entryDir, src)
                val bytes = readBytes(rel)
                if (bytes == null) {
                    missing.add(src)
                    "<!-- MISSING IMG: $src -->" + m.value.replace(Regex("""src=["'][^"']+["']"""), "src=\"\"")
                } else if (bytes.size > MAX_IMG_BYTES) {
                    warnings.add("IMG $src ${bytes.size} byte (>200 KB) — dibiarkan sebagai file, tidak di-inline; artifact butuh file itu tetap ada.")
                    m.value
                } else {
                    imgN++
                    val mime = when (rel.substringAfterLast(".", "").lowercase()) {
                        "png" -> "image/png"
                        "jpg", "jpeg" -> "image/jpeg"
                        "gif" -> "image/gif"
                        "svg" -> "image/svg+xml"
                        "webp" -> "image/webp"
                        "ico" -> "image/x-icon"
                        else -> "application/octet-stream"
                    }
                    "<img" + attrs + " src=\"data:" + mime + ";base64," + encodeBase64(bytes) + "\""
                }
            }
        }
        return BundleResult(html, cssN, jsN, imgN, missing, warnings)
    }
}
