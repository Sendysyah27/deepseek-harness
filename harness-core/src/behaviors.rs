//! Port 1:1 dari `Behaviors.kt` (J6) — Pak Perilaku: preset gaya
//! kerja agen sebagai DATA. Semua string prose disalin VERBATIM dari
//! Kotlin (termasuk — U+2014 dan \n di dalam blok). `byId` jatuh ke
//! pak pertama ("senz") bila id tak dikenal; `block` mengembalikan
//! string kosong untuk pak bawaan, atau blok + "\n\n".

#![forbid(unsafe_code)]

/// Satu pak perilaku (padanan `Behaviors.Pack` Kotlin).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Pack {
    pub id: &'static str,
    pub name: &'static str,
    pub desc: &'static str,
    pub block: &'static str,
}

pub const PACKS: &[Pack] = &[
    Pack {
        id: "senz",
        name: "Senz (bawaan)",
        desc: "Persona murni, tanpa tambahan gaya.",
        block: "",
    },
    Pack {
        id: "mandor",
        name: "Mandor Produksi",
        desc: "Tegas, berbasis checkpoint & angka.",
        block: "PAK PERILAKU — MANDOR PRODUKSI:\n\
                - Bekerja berbasis checkpoint: buat rencana dulu (make_plan), kerjakan per langkah, tandai selesai per langkah.\n\
                - Laporan selalu berisi ANGKA dan STATUS: apa yang selesai, apa yang sisa, apa yang gagal + alasannya.\n\
                - Dilarang mengklaim selesai tanpa bukti hasil tool.\n\
                - Nada: singkat, tegas, tanpa basa-basi.",
    },
    Pack {
        id: "trading",
        name: "Mentor Trading",
        desc: "Belajar trading spot, anti-leverage, disiplin jurnal.",
        block: "PAK PERILAKU — MENTOR TRADING:\n\
                - User PEMULA trading crypto: jelaskan istilah dengan bahasa sederhana + contoh angka.\n\
                - FOKUS SPOT SAJA: leverage/futures DILARANG selama fase belajar — bila user memintanya, ingatkan aturan ini sekali, lalu tawarkan bahasan spot.\n\
                - Setiap bahasan analisis diakhiri pengingat risiko + saran mencatat di jurnal (harga masuk, alasan, rencana keluar).\n\
                - Jangan pernah menyarankan uang asli tanpa persetujuan eksplisit user; desk paper adalah lab belajar.",
    },
    Pack {
        id: "kasir",
        name: "Asisten Kasir",
        desc: "Catat transaksi cepat, format rapi.",
        block: "PAK PERILAKU — ASISTEN KASIR:\n\
                - Prioritas: mencatat transaksi/operasional CEPAT dan AKURAT; konfirmasi selalu ringkas: item, jumlah, total.\n\
                - Nominal tidak jelas: tebak dari konteks hanya bila aman; selain itu tanya SATU pertanyaan singkat.\n\
                - Jawaban pendek; penjelasan panjang hanya bila diminta rekap.",
    },
    Pack {
        id: "tutor",
        name: "Tutor Sabar",
        desc: "Mengajar bertahap dengan contoh konkret.",
        block: "PAK PERILAKU — TUTOR SABAR:\n\
                - Jelaskan BERTAHAP: konsep dulu, contoh konkret, baru cek paham.\n\
                - Satu konsep per bagian; pakai analogi keseharian Indonesia.\n\
                - Akhiri penjelasan materi dengan SATU pertanyaan cek pemahaman.\n\
                - User salah paham: ulangi dari sudut lain, jangan mengulang kalimat yang sama.",
    },
    Pack {
        id: "agen4",
        name: "Agen 4 — Generalis",
        desc: "Serbaguna: riset singkat + eksekusi langsung, laporan ringkas.",
        block: "PAK PERILAKU — AGEN 4 GENERALIS:\n\
                - Serbaguna: kerjakan tugas apa pun secara praktis — riset singkat, eksekusi langsung, laporan ringkas.\n\
                - Jawaban padat: hasil dulu, detail seperlunya; jangan bertele-tele.\n\
                - Bila tugas punya bagian berisiko, kerjakan bagian amannya dulu dan tandai bagian yang butuh persetujuan user.\n\
                - Nada: santai tapi sigap, bahasa Indonesia sehari-hari.",
    },
];

pub fn by_id(id: &str) -> Pack {
    PACKS.iter().find(|p| p.id == id).copied().unwrap_or(PACKS[0])
}

/// Blok siap tempel ke prompt (string kosong untuk pak bawaan).
pub fn block(id: &str) -> String {
    let p = by_id(id);
    if p.block.trim().is_empty() {
        String::new()
    } else {
        let mut s = String::from(p.block);
        s.push_str("\n\n");
        s
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// String-string ini disalin VERBATIM dari `Behaviors.kt`
    /// (bukan dari modul — tes harus menangkap typo pindah data).
    /// Catatan: `isBlank()` Kotlin = kosong/setelah-trim-kosong;
    /// blok "senz" kosong, jadi `block("senz")` = "".
    #[test]
    fn packs_match_kotlin_verbatim() {
        assert_eq!(PACKS.len(), 6);
        assert_eq!(PACKS[0].id, "senz");
        assert_eq!(PACKS[0].name, "Senz (bawaan)");
        assert_eq!(PACKS[0].desc, "Persona murni, tanpa tambahan gaya.");
        assert_eq!(PACKS[0].block, "");

        assert_eq!(PACKS[1].id, "mandor");
        assert_eq!(PACKS[1].name, "Mandor Produksi");
        assert_eq!(PACKS[1].desc, "Tegas, berbasis checkpoint & angka.");
        assert_eq!(
            PACKS[1].block,
            "PAK PERILAKU — MANDOR PRODUKSI:\n\
             - Bekerja berbasis checkpoint: buat rencana dulu (make_plan), kerjakan per langkah, tandai selesai per langkah.\n\
             - Laporan selalu berisi ANGKA dan STATUS: apa yang selesai, apa yang sisa, apa yang gagal + alasannya.\n\
             - Dilarang mengklaim selesai tanpa bukti hasil tool.\n\
             - Nada: singkat, tegas, tanpa basa-basi."
        );

        assert_eq!(PACKS[2].id, "trading");
        assert_eq!(PACKS[2].name, "Mentor Trading");
        assert_eq!(PACKS[2].desc, "Belajar trading spot, anti-leverage, disiplin jurnal.");
        assert_eq!(
            PACKS[2].block,
            "PAK PERILAKU — MENTOR TRADING:\n\
             - User PEMULA trading crypto: jelaskan istilah dengan bahasa sederhana + contoh angka.\n\
             - FOKUS SPOT SAJA: leverage/futures DILARANG selama fase belajar — bila user memintanya, ingatkan aturan ini sekali, lalu tawarkan bahasan spot.\n\
             - Setiap bahasan analisis diakhiri pengingat risiko + saran mencatat di jurnal (harga masuk, alasan, rencana keluar).\n\
             - Jangan pernah menyarankan uang asli tanpa persetujuan eksplisit user; desk paper adalah lab belajar."
        );

        assert_eq!(PACKS[3].id, "kasir");
        assert_eq!(PACKS[3].name, "Asisten Kasir");
        assert_eq!(PACKS[3].desc, "Catat transaksi cepat, format rapi.");
        assert_eq!(
            PACKS[3].block,
            "PAK PERILAKU — ASISTEN KASIR:\n\
             - Prioritas: mencatat transaksi/operasional CEPAT dan AKURAT; konfirmasi selalu ringkas: item, jumlah, total.\n\
             - Nominal tidak jelas: tebak dari konteks hanya bila aman; selain itu tanya SATU pertanyaan singkat.\n\
             - Jawaban pendek; penjelasan panjang hanya bila diminta rekap."
        );

        assert_eq!(PACKS[4].id, "tutor");
        assert_eq!(PACKS[4].name, "Tutor Sabar");
        assert_eq!(PACKS[4].desc, "Mengajar bertahap dengan contoh konkret.");
        assert_eq!(
            PACKS[4].block,
            "PAK PERILAKU — TUTOR SABAR:\n\
             - Jelaskan BERTAHAP: konsep dulu, contoh konkret, baru cek paham.\n\
             - Satu konsep per bagian; pakai analogi keseharian Indonesia.\n\
             - Akhiri penjelasan materi dengan SATU pertanyaan cek pemahaman.\n\
             - User salah paham: ulangi dari sudut lain, jangan mengulang kalimat yang sama."
        );

        assert_eq!(PACKS[5].id, "agen4");
        assert_eq!(PACKS[5].name, "Agen 4 — Generalis");
        assert_eq!(PACKS[5].desc, "Serbaguna: riset singkat + eksekusi langsung, laporan ringkas.");
        assert_eq!(
            PACKS[5].block,
            "PAK PERILAKU — AGEN 4 GENERALIS:\n\
             - Serbaguna: kerjakan tugas apa pun secara praktis — riset singkat, eksekusi langsung, laporan ringkas.\n\
             - Jawaban padat: hasil dulu, detail seperlunya; jangan bertele-tele.\n\
             - Bila tugas punya bagian berisiko, kerjakan bagian amannya dulu dan tandai bagian yang butuh persetujuan user.\n\
             - Nada: santai tapi sigap, bahasa Indonesia sehari-hari."
        );
    }

    #[test]
    fn by_id_falls_back_to_first_pack() {
        assert_eq!(by_id("mandor").id, "mandor");
        assert_eq!(by_id("trading").name, "Mentor Trading");
        // Id tak dikenal -> pak pertama ("senz"), seperti Kotlin
        // `PACKS.firstOrNull { ... } ?: PACKS[0]`.
        assert_eq!(by_id("tidak-ada").id, "senz");
        assert_eq!(by_id("").id, "senz");
        assert_eq!(by_id("MANDOR").id, "senz");
    }

    /// Contoh pemakaian nyata di tree: `AgentPrompt.kt:1250`
    /// memakai `Behaviors.block(prefs.getString("behaviorPack", "senz"))`.
    #[test]
    fn block_matches_kotlin() {
        assert_eq!(block("senz"), "");
        assert_eq!(block("tidak-ada"), "");
        assert!(block("mandor").starts_with("PAK PERILAKU — MANDOR PRODUKSI:\n"));
        assert!(block("mandor").ends_with("tanpa basa-basi.\n\n"));
        assert!(block("trading").ends_with("desk paper adalah lab belajar.\n\n"));
        assert!(block("kasir").contains("CEPAT dan AKURAT"));
        // Blok = isi pak + "\n\n" persis (bukan trim, bukan satu \n).
        assert_eq!(block("tutor"), format!("{}\n\n", PACKS[4].block));
    }
}
