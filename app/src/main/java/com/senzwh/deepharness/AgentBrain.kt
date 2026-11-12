package com.senzwh.deepharness

import android.content.Context
import java.io.File

/**
 * Agen Native (Brief v4 Rel 2, v3.12.0) — otak agen sebagai FILE, meniru
 * tumpukan operator Operator: SOUL.md (jiwa), AGENTS.md (aturan kerja & flow),
 * MEMORY.md (ingatan terkurasi). Disimpan di filesDir/otak/, diedit user dari
 * Setup 41 atau oleh agen sendiri lewat tool otak_read/otak_write, dan
 * ditempel berlapis ke prompt sistem oleh AgentPrompt.buildSystemPrompt.
 */
object AgentBrain {
    val FILES = listOf("SOUL.md", "AGENTS.md", "MEMORY.md")

    private fun activeId(ctx: Context): String =
        ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).getString("activeAgent", "asisten") ?: "asisten"

    /** Namespace otak per agen: Asisten = otak/ (warisan v3.12), agen lain otak-<id>/. */
    private fun dir(ctx: Context): File {
        val id = activeId(ctx)
        val name = if (id == "asisten") "otak" else "otak-$id"
        return File(ctx.filesDir, name).apply { mkdirs() }
    }

    fun canonical(name: String): String? =
        FILES.firstOrNull { it.equals(name.trim(), ignoreCase = true) }

    fun isSoulOrAgents(name: String): Boolean {
        val c = canonical(name) ?: return false
        return c == "SOUL.md" || c == "AGENTS.md"
    }

    private val DEFAULT_SOUL = """# SOUL — Jiwa Agen Harness

Kamu adalah asisten pribadi yang tinggal di HP pengguna. Kamu BUKAN chatbot — kamu operatornya: yang penting kerjaan beres.

## Kebiasaan inti
- Kerjakan, jangan tanya-tanya. Kalau permintaan sudah cukup jelas, langsung kerjakan. Sampaikan asumsimu dalam satu baris.
- Temen nongkrong energy: santai, Bahasa Indonesia casual, becanda ringan secukupnya — bukan badut tiap kalimat. Pas kerja tetap sat-set dan kompeten.
- Pilih sendiri yang terbaik. Jangan sodorkan menu pilihan kalau satu opsi sudah jelas paling bagus.
- Cari akal dulu: pakai tool, cek konteks HP, baca memori. Tanya hanya kalau benar-benar mentok — satu pertanyaan spesifik plus tebakan terbaikmu.
- Selesaikan kerjaannya: serahkan HASIL, bukan rencana. Verifikasi dulu sebelum bilang "beres".
- Punya pendirian: kalau pendekatan user ada cacat nyata, bilang dalam satu-dua baris, lalu ikuti maunya.
- Laporan singkat: apa yang beres, apa yang berubah, apa selanjutnya. Angka dan bukti dulu, basa-basi belakangan.
"""

    private val DEFAULT_AGENTS = """# AGENTS — Aturan Kerja & Flow

## Flow tiap perintah (agentic loop)
1. BERPIKIR — pahami maksud user + konteks (MEMORY.md, riwayat, konteks HP) sebelum bertindak.
2. RENCANA — tugas multi-langkah WAJIB make_plan dulu; kerjakan per langkah, tandai selesai per langkah (update_plan).
3. JALANKAN — eksekusi dengan tool yang tepat; hormati gerbang risiko, PIN, dan batas langkah.
4. VERIFIKASI — cek hasil tool sebelum menyimpulkan. Klaim tanpa bukti hasil tool = belum selesai.
5. LAPOR — tutup dengan hasil + bukti (angka/status/file), bukan rencana. Kalau gagal: apa yang dicoba, kenapa gagal, alternatifnya apa.

## Peran MANDOR atas Koordinator OpenCode
- Setelah mendelegasikan (delegate_session/chain_tasks), kamu mandornya: pantau dengan delegate_status/delegate_wait, ambil ISI hasil dengan delegate_result, sampaikan ke user.
- DILARANG berhenti di jawaban pasif seperti "tidak ada tindakan lanjutan yang diperlukan" selama masih ada langkah wajar berikutnya: cek hasil, susul perintah perbaikan/spesifik ke koordinator, atau rangkum temuan untuk user.
- Tugas yang mengubah file: pakai arg dir (folder /sdcard) agar checkpoint aktif — perubahan bisa diterima/diurungkan user.

## Memori
- Fakta/keputusan penting BARU tentang user → remember_fact dan/atau catat ringkas ke MEMORY.md (otak_write, mode append, beri tanggal).
- MEMORY.md dibaca dari AWAL tiap giliran — taruh yang terpenting di atas.
- Jangan mengarang preferensi/keputusan lama: cek MEMORY.md dan riwayat dulu.
"""

    private val DEFAULT_MEMORY = """# MEMORY — Ingatan Jangka Panjang Agen
# Fakta & keputusan terkurasi tentang user. Yang terpenting di atas.
# Agen menambah lewat tool otak_write (mode append); user mengedit dari Setup 41.

- User: (belum ada catatan — pelajari dari percakapan dan catat di sini). Bahasa: Indonesia casual, sesuaikan dengan gaya user.
"""

    /** Buat file otak bawaan bila belum ada; migrasi persona lama ke SOUL. */
    fun ensureSeeded(ctx: Context) {
        val d = dir(ctx)
        val soulF = File(d, "SOUL.md")
        if (!soulF.exists()) {
            var soul = DEFAULT_SOUL
            val legacy = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
                .getString("persona", "")?.trim().orEmpty()
            if (legacy.isNotBlank() && activeId(ctx) == "asisten") {
                soul += "\n## Persona warisan dari pengaturan lama\n" + legacy + "\n"
            }
            soulF.writeText(soul)
        }
        val agentsF = File(d, "AGENTS.md")
        if (!agentsF.exists()) agentsF.writeText(DEFAULT_AGENTS)
        val memF = File(d, "MEMORY.md")
        if (!memF.exists()) memF.writeText(DEFAULT_MEMORY)
    }

    fun read(ctx: Context, name: String): String {
        val c = canonical(name) ?: return "File otak tidak dikenal: $name (pilihan: SOUL.md, AGENTS.md, MEMORY.md)."
        ensureSeeded(ctx)
        val f = File(dir(ctx), c)
        return if (f.exists()) f.readText() else "(file $c belum ada)"
    }

    /** Tulis bebas dari editor Setup (tanpa format status tool). */
    fun writeRaw(ctx: Context, name: String, content: String) {
        val c = canonical(name) ?: return
        ensureSeeded(ctx)
        File(dir(ctx), c).writeText(content)
    }

    /** Tulis lewat tool agen: mode append (ditambah di akhir) atau replace. */
    fun write(ctx: Context, name: String, content: String, mode: String): String {
        val c = canonical(name) ?: return "otak_write gagal: file tidak dikenal: $name (pilihan: SOUL.md, AGENTS.md, MEMORY.md)."
        if (RedactSecrets.containsSecret(content)) return "otak_write DITOLAK: isinya mengandung pola kredensial keyakinan tinggi (password/token/key) — kredensial WAJIB disimpan lewat SecureStore, bukan otak. Tidak ada yang ditulis."
        ensureSeeded(ctx)
        val f = File(dir(ctx), c)
        return try {
            if (mode.equals("replace", ignoreCase = true)) {
                f.writeText(content)
                "🧠 $c DITULIS ULANG (${content.length} karakter). Berlaku mulai giliran berikutnya."
            } else {
                val cur = if (f.exists()) f.readText() else ""
                val sep = if (cur.isBlank() || cur.endsWith("\n")) "" else "\n"
                f.writeText(cur + sep + content + "\n")
                "🧠 Ditambahkan ke $c (${content.length} karakter). Berlaku mulai giliran berikutnya."
            }
        } catch (e: Exception) {
            "otak_write gagal: ${e.message}"
        }
    }

    /** Blok berlapis untuk prompt sistem (dipotong wajar per file). */
    /** Strip blok persona warisan bila isinya SAMA PERSIS dengan persona aktif (dedup 4.48.8). */
    fun stripLegacyPersonaText(text: String, persona: String): String {
        if (persona.isBlank()) return text
        val header = "## Persona warisan dari pengaturan lama"
        val ix = text.indexOf(header)
        if (ix < 0) return text
        val bodyStart = text.indexOf("\n", ix)
        if (bodyStart < 0) return text
        val next = text.indexOf("\n## ", bodyStart + 1)
        val bodyEnd = if (next < 0) text.length else next
        val body = text.substring(bodyStart, bodyEnd).trim()
        // vc240 m13: dedup persona warisan dengan ambang kemiripan
        // (Jaccard token >= 0.80), bukan hanya sama persis — persona yang
        // diedit sedikit tidak lagi muncul dua kali (personaBlock + blok
        // warisan di SOUL). Pilihan yang dipakai: ambang kemiripan (bukan
        // hapus blok warisan saat persona diubah) agar edit warisan yang
        // memang berbeda tetap dipertahankan.
        val tok = { s: String -> s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet() }
        val bt = tok(body)
        val pt = tok(persona.trim())
        val jac = if (bt.isEmpty() || pt.isEmpty()) 0.0 else bt.intersect(pt).size.toDouble() / bt.union(pt).size.toDouble()
        if (body != persona.trim() && jac < 0.80) return text
        return text.substring(0, ix).trimEnd() + if (next < 0) "" else "\n" + text.substring(next + 1)
    }

    /** Satu blok otak (header + isi teredaksi, dicap) — dipakai promptBlocks, promptBlocksFor, dan blockLengths. */
    private fun blockText(dir: File, file: String, header: String, cap: Int, personaLegacy: String?): String {
        val f = File(dir, file)
        if (!f.exists()) return ""
        var text = try { f.readText(Charsets.UTF_8).trim() } catch (e: Exception) { return "" }
        if (file == "SOUL.md" && personaLegacy != null) text = stripLegacyPersonaText(text, personaLegacy)
        text = RedactSecrets.redact(text)
        if (text.isBlank()) return ""
        val capped = if (text.length > cap) text.take(cap) + "…(dipotong, sisa " + (text.length - cap) + " char)…" else text
        return header + "\n" + capped + "\n\n"
    }

    /** Panjang blok SOUL/AGENTS/MEMORY (sesudah redaksi) — untuk Monitor + L_MEMORY. */
    fun blockLengths(ctx: Context): Triple<Int, Int, Int> {
        ensureSeeded(ctx)
        val prefs = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
        val persona = prefs.getString("persona", "")?.trim().orEmpty()
        val dir = dir(ctx)
        val legacy = if (dir.name == "otak") persona else null
        val soul = blockText(dir, "SOUL.md", "JIWA AGEN (SOUL.md — kepribadian & kebiasaan inti, ikuti):", 3000, legacy)
        val agents = blockText(dir, "AGENTS.md", "ATURAN KERJA & FLOW (AGENTS.md — alur agentic wajib):", 20000, null)
        val mem = blockText(dir, "MEMORY.md", "INGATAN JANGKA PANJANG (MEMORY.md — fakta terkurasi tentang user; perlakukan sebagai kebenaran yang berlaku, jangan diumumkan sebagai 'hasil pencarian'):", 20000, null)
        return Triple(soul.length, agents.length, mem.length)
    }

    fun promptBlocks(ctx: Context): String {
        ensureSeeded(ctx)
        val prefs = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
        val persona = prefs.getString("persona", "")?.trim().orEmpty()
        val d = dir(ctx)
        val legacy = if (d.name == "otak") persona else null
        return blockText(d, "SOUL.md", "JIWA AGEN (SOUL.md — kepribadian & kebiasaan inti, ikuti):", 3000, legacy) +
            blockText(d, "AGENTS.md", "ATURAN KERJA & FLOW (AGENTS.md — alur agentic wajib):", 20000, null) +
            blockText(d, "MEMORY.md", "INGATAN JANGKA PANJANG (MEMORY.md — fakta terkurasi tentang user; perlakukan sebagai kebenaran yang berlaku, jangan diumumkan sebagai 'hasil pencarian'):", 20000, null)
    }

    /** Grup Matang (v4.48.0, F1): blok otak per anggota grup, TANPA mengubah
     *  pref activeAgent. promptBlocks() milik 1-1 mengikuti agen aktif saja,
     *  jadi grup tidak boleh memakainya mentah-mentah (semua anggota akan
     *  dapat otak agen aktif). Versi ini hanya MEMBACA file yang sudah ada
     *  (tanpa ensureSeeded — anggota yang belum pernah aktif tidak dibuatkan
     *  file). Cap lebih ketat dari 1-1 demi anggaran prompt grup. */
    fun promptBlocksFor(ctx: Context, id: String): String {
        val d = File(ctx.filesDir, if (id == "asisten") "otak" else "otak-$id")
        if (!d.exists()) return ""
        val prefs = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
        val persona = prefs.getString("persona", "")?.trim().orEmpty()
        val legacy = if (id == "asisten") persona else null
        return blockText(d, "SOUL.md", "JIWA AGEN (SOUL.md):", 1000, legacy) +
            blockText(d, "AGENTS.md", "ATURAN KERJA (AGENTS.md):", 1000, null) +
            blockText(d, "MEMORY.md", "INGATAN JANGKA PANJANG (MEMORY.md):", 1500, null)
    }
}
