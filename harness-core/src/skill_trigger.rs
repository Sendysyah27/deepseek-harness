//! Port 1:1 dari scoring trigger vc312 di `SkillJudge.kt` (Opsi A):
//! `splitDescrSections`, `negWordsOf`, `triggerScore` (+ `contentWords`,
//! `STOPWORDS`, `NEG_WEIGHT_VC312 = 1`).
//!
//! CATATAN ASAL: ketiga fungsi ini tinggal di `SkillJudge.kt`, bukan di
//! `ToolRegistry.kt`/`AgentPrompt.kt` — diport ke sini karena tugas slice 6
//! menyebut "scoring trigger (marker `JANGAN PICU:`, bobot −1)" eksplisit.
//! Sisa SkillJudge (judge 120 poin, `evaluateTrigger`, dsb.) TETAP Kotlin.
//!
//! Quirk Kotlin yang direplika (dikunci tes + P6):
//! - Marker dikenali HANYA di awal baris (`^\s*jangan\s+picu\s*:`,
//!   case-insensitive, toleran spasi) — marker di tengah baris TIDAK
//!   dikenali; HANYA marker PERTAMA yang dipakai (marker kedua jadi isi
//!   bagian larangan, dan kata "picu" di baris itu ikut jadi negWord).
//! - `\s` Java = ASCII saja — pola memakai kelas eksplisit
//!   `[ \t\n\x0B\f\r]`, bukan `\s` regex crate (Unicode).
//! - Bagian larangan = sisa baris marker + baris berikut sampai baris
//!   kosong; baris kosong pembatas TIDAK masuk bagian positif
//!   (`i > end`, bukan `i >= end`).
//! - Aturan MINUS-POSITIF: `negWords = contentWords(negatif) −
//!   contentWords(positif)` — kata yang ada di kedua bagian TIDAK terpenalti.
//! - `contentWords`: lowercase Unicode, pisah `[^a-z0-9]+`, panjang ≥ 4,
//!   buang STOPWORDS.
//! - Skor dijepit min 0; TRIGGER bila net ≥ 2; tanpa marker = rumus lama
//!   (negWords kosong).

#![forbid(unsafe_code)]

use crate::agent_prompt::kotlin_lines;
use regex::Regex;
use std::collections::HashSet;
use std::sync::OnceLock;

/// Bobot negatif vc312 (S2=A, lunak).
pub const NEG_WEIGHT_VC312: i64 = 1;

const STOPWORDS: &[&str] = &[
    "yang", "dengan", "untuk", "adalah", "saya", "kamu", "tolong", "buatkan", "bagaimana",
    "cara", "bisa", "atau", "dari", "mau", "ingin", "agar", "supaya", "jika", "bila", "saat",
    "ketika", "sebuah", "dalam", "pada", "oleh", "juga", "sudah", "belum", "akan", "saja",
    "sangat", "lagi", "masih", "itu", "ini", "apa", "siapa", "dimana", "berapa", "the", "and",
    "for", "with", "how", "what", "please", "when", "this", "that", "your", "skill", "pakai",
    "guna", "harus", "jangan",
];

fn regex_split_non_alnum() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"[^a-z0-9]+").unwrap())
}

fn regex_neg_marker() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    // `^\s*jangan\s+picu\s*:\s*(.*)$` (IGNORE_CASE) — \s Java = ASCII saja.
    R.get_or_init(|| {
        Regex::new(r"(?i)^[ \t\n\x0B\f\r]*jangan[ \t\n\x0B\f\r]+picu[ \t\n\x0B\f\r]*:[ \t\n\x0B\f\r]*(.*)$")
            .unwrap()
    })
}

/// Kata konten teks — cermin `SkillJudge.contentWords` (privat Kotlin).
pub fn content_words(text: &str) -> HashSet<String> {
    let low = text.to_lowercase();
    regex_split_non_alnum()
        .split(&low)
        .filter(|t| t.chars().count() >= 4 && !STOPWORDS.contains(t))
        .map(|t| t.to_string())
        .collect()
}

/// Hasil pemecahan deskripsi — cermin `SkillJudge.DescrSections`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DescrSections {
    pub positive: String,
    pub negative: String,
    pub marker_found: bool,
}

/// Pecah deskripsi jadi bagian positif + bagian larangan (S1=A).
pub fn split_descr_sections(descr: &str) -> DescrSections {
    let lines = kotlin_lines(descr);
    let marker_idx = lines
        .iter()
        .position(|l| regex_neg_marker().is_match(l));
    let marker_idx = match marker_idx {
        Some(i) => i,
        None => {
            return DescrSections {
                positive: descr.to_string(),
                negative: String::new(),
                marker_found: false,
            }
        }
    };
    let marker_match = regex_neg_marker()
        .captures(lines[marker_idx])
        .and_then(|c| c.get(1))
        .map(|g| g.as_str().to_string())
        .unwrap_or_default();
    let mut neg_owned: Vec<String> = vec![marker_match];
    let mut end = marker_idx + 1;
    while end < lines.len() && !crate::concat_api_client::is_blank(lines[end]) {
        neg_owned.push(lines[end].to_string());
        end += 1;
    }
    let mut pos_lines: Vec<&str> = Vec::new();
    for (i, l) in lines.iter().enumerate() {
        if i < marker_idx || i > end {
            pos_lines.push(l);
        }
    }
    DescrSections {
        positive: pos_lines.join("\n"),
        negative: neg_owned.join("\n"),
        marker_found: true,
    }
}

/// Kata negatif sinyal trigger — cermin `SkillJudge.negWordsOf`.
pub fn neg_words_of(descr: &str) -> HashSet<String> {
    let sections = split_descr_sections(descr);
    if !sections.marker_found {
        return HashSet::new();
    }
    let neg = content_words(&sections.negative);
    let pos = content_words(&sections.positive);
    neg.difference(&pos).cloned().collect()
}

/// Skor sinyal trigger satu query — cermin `SkillJudge.TriggerScore`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TriggerScore {
    pub trigger: bool,
    pub pos_overlap: usize,
    pub neg_overlap: usize,
    pub net_score: i64,
}

/// Skor sinyal trigger satu query (vc312 Opsi A, w=1, ambang net ≥ 2).
pub fn trigger_score(descr: &str, query: &str) -> TriggerScore {
    let sections = split_descr_sections(descr);
    let pos_words = content_words(&sections.positive);
    let neg_words = if sections.marker_found {
        let neg = content_words(&sections.negative);
        neg.difference(&pos_words).cloned().collect()
    } else {
        HashSet::new()
    };
    let q_words = content_words(query);
    let pos = q_words.intersection(&pos_words).count();
    let neg = q_words.intersection(&neg_words).count();
    let net = (pos as i64 - NEG_WEIGHT_VC312 * neg as i64).max(0);
    TriggerScore {
        trigger: net >= 2,
        pos_overlap: pos,
        neg_overlap: neg,
        net_score: net,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    // kotlin_trim hanya dipakai tes ini (bukan kode lib) — import di sini
    // supaya `cargo build` tidak warning unused_imports.
    use crate::ds_safety_strip::kotlin_trim;

    fn sorted_set(s: &HashSet<String>) -> Vec<String> {
        let mut v: Vec<String> = s.iter().cloned().collect();
        v.sort();
        v
    }

    #[test]
    fn marker_mid_line_not_recognized() {
        // Probe JVM (probe6.log): marker di tengah baris TIDAK dikenali.
        let d = "Skill notifikasi WhatsApp. JANGAN PICU: Discord, Slack, WhatsApp";
        let s = split_descr_sections(d);
        assert!(!s.marker_found);
        assert_eq!(s.positive, d);
        assert_eq!(s.negative, "");
        assert!(neg_words_of(d).is_empty());
        // Tanpa marker: kata larangan ikut dihitung positif.
        let sc = trigger_score(d, "cek notifikasi whatsapp");
        assert_eq!(
            sc,
            TriggerScore { trigger: true, pos_overlap: 2, neg_overlap: 0, net_score: 2 }
        );
        let sc2 = trigger_score(d, "kirim slack dan discord");
        assert_eq!(sc2.pos_overlap, 2);
        assert!(sc2.trigger);
    }

    #[test]
    fn basic_negative_section() {
        // Probe JVM (probe6b.log T4).
        let d = "Skill pengingat jadwal.\nJANGAN PICU: Discord, Slack\n";
        let s = split_descr_sections(d);
        assert!(s.marker_found);
        assert_eq!(s.positive, "Skill pengingat jadwal.");
        assert_eq!(s.negative, "Discord, Slack");
        assert_eq!(sorted_set(&neg_words_of(d)), vec!["discord", "slack"]);
        assert_eq!(
            trigger_score(d, "buka discord"),
            TriggerScore { trigger: false, pos_overlap: 0, neg_overlap: 1, net_score: 0 }
        );
        assert_eq!(
            trigger_score(d, "pengingat jadwal discord"),
            TriggerScore { trigger: false, pos_overlap: 2, neg_overlap: 1, net_score: 1 }
        );
        assert_eq!(
            trigger_score(d, "pengingat jadwal harian"),
            TriggerScore { trigger: true, pos_overlap: 2, neg_overlap: 0, net_score: 2 }
        );
        assert_eq!(
            trigger_score(d, "jadwal slack"),
            TriggerScore { trigger: false, pos_overlap: 1, neg_overlap: 1, net_score: 0 }
        );
    }

    #[test]
    fn multiline_negative_and_case_insensitive_marker() {
        // Probe JVM (probe6b.log T5).
        let d = "Skill musik.\n  Jangan   Picu  :  Spotify, YouTube\nlanjut larangan baris dua\n\nSesudah kosong.";
        let s = split_descr_sections(d);
        assert!(s.marker_found);
        assert_eq!(s.positive, "Skill musik.\nSesudah kosong.");
        assert_eq!(s.negative, "Spotify, YouTube\nlanjut larangan baris dua");
        assert_eq!(
            sorted_set(&neg_words_of(d)),
            vec!["baris", "lanjut", "larangan", "spotify", "youtube"]
        );
        assert!(!trigger_score(d, "putar spotify").trigger);
        assert!(!trigger_score(d, "musik youtube").trigger);
    }

    #[test]
    fn minus_positive_rule() {
        // Probe JVM (probe6.log T3): "telegram" ada di kedua bagian -> TIDAK terpenalti.
        let d = "Berita Telegram harian.\nJANGAN PICU: berita Telegram, SMS\n\nBaris sesudah kosong.";
        let s = split_descr_sections(d);
        assert!(s.marker_found);
        assert_eq!(s.positive, "Berita Telegram harian.\nBaris sesudah kosong.");
        assert_eq!(s.negative, "berita Telegram, SMS");
        assert!(neg_words_of(d).is_empty());
        assert!(trigger_score(d, "berita telegram hari ini").trigger);
    }

    #[test]
    fn only_first_marker_recognized() {
        // Probe JVM (probe6b.log T7): marker kedua jadi isi larangan.
        let d = "JANGAN PICU: Discord\nJANGAN PICU: Slack";
        let s = split_descr_sections(d);
        assert!(s.marker_found);
        assert_eq!(s.positive, "");
        assert_eq!(s.negative, "Discord\nJANGAN PICU: Slack");
        assert_eq!(
            sorted_set(&neg_words_of(d)),
            vec!["discord", "picu", "slack"]
        );
        assert!(!trigger_score(d, "buka slack").trigger);
    }

    #[test]
    fn no_marker_old_formula() {
        let d = "Tanpa marker sama sekali, kata discord ada.";
        assert!(neg_words_of(d).is_empty());
        assert_eq!(
            trigger_score(d, "buka discord"),
            TriggerScore { trigger: false, pos_overlap: 1, neg_overlap: 0, net_score: 1 }
        );
    }

    #[test]
    fn content_words_vectors() {
        // "skill"/"jangan" stopword; kata < 4 huruf dibuang.
        let w = content_words("Skill notifikasi WhatsApp, jangan picu discord!");
        assert!(w.contains("notifikasi"));
        assert!(w.contains("whatsapp"));
        assert!(w.contains("discord"));
        assert!(!w.contains("skill"));
        assert!(!w.contains("jangan"));
        assert!(content_words("a b c").is_empty());
        // kotlin_trim dipakai di tempat lain; pastikan import hidup.
        assert_eq!(kotlin_trim("  x  "), "x");
    }
}
