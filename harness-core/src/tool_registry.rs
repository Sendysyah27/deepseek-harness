//! Port 1:1 dari `ToolRegistry.kt` (vc272 O2 — SATU sumber kebenaran katalog
//! tool): 188 definisi tool + urutan segmen katalog + 21 blok prose +
//! detail skill browser, `catalogSection`, `names`, `namesByKind`,
//! `sectionCounts`, `nearestNames`/`editDistance`.
//!
//! Data (ALL/SEGMENTS/PROSE/BROWSER_SKILL_DETAIL) tinggal di
//! `tool_registry_data.rs` — GENERATED mekanis dari `ToolRegistry.kt`
//! (skrip `vc317-work/work-impl/gen-tool-registry.py`), BUKAN disalin
//! tangan. Tes hash di bawah membuktikan byte-identik dengan Kotlin
//! (nilai harap dari probe JVM `/tmp/probe6/probe6.log`, 16 Nov 2026).
//!
//! Quirk yang direplika:
//! - `catalogSection`: baris tool `"- {nama} {argsExample} \u{2014} {desc}\n"`
//!   (em dash U+2014, persis seperti Kotlin `"\u2014"`); prose/header +
//!   `"\n"`; entri `BY_NAME` yang hilang di-skip (`?: continue`).
//! - `nearestNames`: Levenshtein atas `char` Kotlin (= unit UTF-16);
//!   nama tool ASCII semua, jadi iterasi `char` Rust identik.
//! - `sectionCounts`: urutan map tidak deterministik di kedua bahasa —
//!   pemakai seam JSON menerima object (tanpa janji urutan).

#![forbid(unsafe_code)]

use crate::tool_registry_data as data;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Read,
    Write,
    Memory,
}

#[derive(Debug, Clone, Copy)]
pub struct ArgSpec {
    pub name: &'static str,
    pub arg_type: &'static str,
    pub required: bool,
    pub enum_values: &'static [&'static str],
}

#[derive(Debug, Clone, Copy)]
pub struct ToolDef {
    pub name: &'static str,
    pub section: &'static str,
    pub args_example: &'static str,
    pub desc: &'static str,
    pub kind: Kind,
    pub args: &'static [ArgSpec],
}

/// Cermin `ToolRegistry.ALL` (urutan sama).
pub fn all() -> &'static [ToolDef] {
    data::ALL
}

/// Cermin `ToolRegistry.BY_NAME[name]`.
pub fn by_name(name: &str) -> Option<&'static ToolDef> {
    data::ALL.iter().find(|d| d.name == name)
}

/// Cermin `ToolRegistry.names()`.
pub fn names() -> Vec<&'static str> {
    data::ALL.iter().map(|d| d.name).collect()
}

/// Cermin `ToolRegistry.namesByKind(kind)`.
pub fn names_by_kind(kind: Kind) -> Vec<&'static str> {
    data::ALL
        .iter()
        .filter(|d| d.kind == kind)
        .map(|d| d.name)
        .collect()
}

/// Cermin `ToolRegistry.sectionCounts()` — (section, jumlah), tanpa janji urutan.
pub fn section_counts() -> Vec<(&'static str, usize)> {
    let mut out: Vec<(&'static str, usize)> = Vec::new();
    for d in data::ALL.iter() {
        match out.iter_mut().find(|(s, _)| *s == d.section) {
            Some((_, n)) => *n += 1,
            None => out.push((d.section, 1)),
        }
    }
    out
}

/// Cermin `ToolRegistry.catalogSection()` — byte-identik (tes hash).
pub fn catalog_section() -> String {
    let mut sb = String::new();
    for (kind, reference) in data::SEGMENTS.iter() {
        match *kind {
            "tool" => {
                if let Some(d) = by_name(reference) {
                    sb.push_str("- ");
                    sb.push_str(d.name);
                    sb.push(' ');
                    sb.push_str(d.args_example);
                    sb.push_str(" \u{2014} ");
                    sb.push_str(d.desc);
                    sb.push('\n');
                }
            }
            "prose" => {
                if let Some((_, prose)) = data::PROSE.iter().find(|(k, _)| k == reference) {
                    sb.push_str(prose);
                    sb.push('\n');
                }
            }
            "header" => {
                sb.push_str(reference);
                sb.push('\n');
            }
            _ => {}
        }
    }
    sb
}

/// Cermin `ToolRegistry.browserSkillDetail()`.
pub fn browser_skill_detail() -> String {
    data::BROWSER_SKILL_DETAIL.to_string()
}

/// Jarak edit Levenshtein — cermin `ToolRegistry.editDistance` (privat Kotlin).
/// Iterasi unit UTF-16 (`char` Kotlin); nama tool ASCII -> sama dengan `char` Rust.
pub fn edit_distance(a: &str, b: &str) -> usize {
    let a: Vec<u16> = a.encode_utf16().collect();
    let b: Vec<u16> = b.encode_utf16().collect();
    let m = a.len();
    let n = b.len();
    if m == 0 {
        return n;
    }
    if n == 0 {
        return m;
    }
    let mut prev: Vec<usize> = (0..=n).collect();
    let mut cur: Vec<usize> = vec![0; n + 1];
    for i in 1..=m {
        cur[0] = i;
        for j in 1..=n {
            let cost = if a[i - 1] == b[j - 1] { 0 } else { 1 };
            cur[j] = (prev[j] + 1).min(cur[j - 1] + 1).min(prev[j - 1] + cost);
        }
        std::mem::swap(&mut prev, &mut cur);
    }
    prev[n]
}

/// Cermin `ToolRegistry.nearestNames(name, count)` — jarak edit naik,
/// lalu nama abjad sebagai penyeimbang.
pub fn nearest_names(name: &str, count: usize) -> Vec<&'static str> {
    let mut scored: Vec<(usize, &'static str)> = data::ALL
        .iter()
        .map(|d| (edit_distance(name, d.name), d.name))
        .collect();
    scored.sort_by(|a, b| a.0.cmp(&b.0).then_with(|| a.1.cmp(b.1)));
    scored.into_iter().take(count).map(|(_, n)| n).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    /// SHA-256 hex (tes saja — tanpa dependensi sha2; implementasi kecil).
    fn sha256_hex(s: &str) -> String {
        // Pakai crate yang sudah ada? Tidak ada sha2 di Cargo.toml slice 6.
        // Implementasi SHA-256 ringkas di bawah (FIPS 180-4, diverifikasi
        // vektor "abc" di tes).
        sha256(s.as_bytes())
            .iter()
            .map(|b| format!("{:02x}", b))
            .collect()
    }

    fn sha256(msg: &[u8]) -> [u8; 32] {
        const K: [u32; 64] = [
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
            0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
            0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
            0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
            0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
            0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
            0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
        ];
        let mut h: [u32; 8] = [
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c,
            0x1f83d9ab, 0x5be0cd19,
        ];
        let bitlen = (msg.len() as u64) * 8;
        let mut data = msg.to_vec();
        data.push(0x80);
        while data.len() % 64 != 56 {
            data.push(0);
        }
        data.extend_from_slice(&bitlen.to_be_bytes());
        for chunk in data.chunks_exact(64) {
            let mut w = [0u32; 64];
            for i in 0..16 {
                w[i] = u32::from_be_bytes([chunk[4 * i], chunk[4 * i + 1], chunk[4 * i + 2], chunk[4 * i + 3]]);
            }
            for i in 16..64 {
                let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
                let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
                w[i] = w[i - 16]
                    .wrapping_add(s0)
                    .wrapping_add(w[i - 7])
                    .wrapping_add(s1);
            }
            let (mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut hh) =
                (h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7]);
            for i in 0..64 {
                let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
                let ch = (e & f) ^ ((!e) & g);
                let t1 = hh
                    .wrapping_add(s1)
                    .wrapping_add(ch)
                    .wrapping_add(K[i])
                    .wrapping_add(w[i]);
                let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
                let maj = (a & b) ^ (a & c) ^ (b & c);
                let t2 = s0.wrapping_add(maj);
                hh = g;
                g = f;
                f = e;
                e = d.wrapping_add(t1);
                d = c;
                c = b;
                b = a;
                a = t1.wrapping_add(t2);
            }
            h[0] = h[0].wrapping_add(a);
            h[1] = h[1].wrapping_add(b);
            h[2] = h[2].wrapping_add(c);
            h[3] = h[3].wrapping_add(d);
            h[4] = h[4].wrapping_add(e);
            h[5] = h[5].wrapping_add(f);
            h[6] = h[6].wrapping_add(g);
            h[7] = h[7].wrapping_add(hh);
        }
        let mut out = [0u8; 32];
        for i in 0..8 {
            out[4 * i..4 * i + 4].copy_from_slice(&h[i].to_be_bytes());
        }
        out
    }

    #[test]
    fn sha256_selftest_abc() {
        assert_eq!(
            sha256_hex("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn registry_count_188_unique() {
        assert_eq!(all().len(), 188);
        let names_set: HashSet<&str> = names().into_iter().collect();
        assert_eq!(names_set.len(), 188);
    }

    #[test]
    fn catalog_section_hash_byte_identical() {
        // Probe JVM 16 Nov 2026 (probe6.log): 86710 unit UTF-16.
        // (Rust String::len = byte — pakai utf16_len, seperti Kotlin length.)
        use crate::concat_api_client::utf16_len;
        let s = catalog_section();
        assert_eq!(utf16_len(&s), 86710);
        assert_eq!(
            sha256_hex(&s),
            "c3357c572acdcd593e23ca15b40169dca431ca6cc1f30f250db1e4c5d3fe3668"
        );
    }

    #[test]
    fn browser_skill_detail_hash_byte_identical() {
        use crate::concat_api_client::utf16_len;
        let s = browser_skill_detail();
        assert_eq!(utf16_len(&s), 2866);
        assert_eq!(
            sha256_hex(&s),
            "bac16af6ef7d01e5ee99394790f3a36fd66afe0f1054b23c9874a101780c31d6"
        );
    }

    #[test]
    fn names_hash_and_kinds() {
        assert_eq!(
            sha256_hex(&names().join(",")),
            "38ee24a6c668b3171ed0b017456f9a1cf15525aa5b8a12a70a0fdee63912a912"
        );
        assert_eq!(names_by_kind(Kind::Read).len(), 84);
        assert_eq!(names_by_kind(Kind::Write).len(), 82);
        assert_eq!(names_by_kind(Kind::Memory).len(), 22);
        let counts: std::collections::HashMap<&str, usize> =
            section_counts().into_iter().collect();
        assert_eq!(counts.get("utama"), Some(&53));
        assert_eq!(counts.get("lanjutan"), Some(&135));
    }

    #[test]
    fn nearest_names_vectors() {
        // Probe JVM (probe6.log).
        assert_eq!(
            nearest_names("get_batery", 3),
            vec!["get_battery", "get_screen", "set_alarm"]
        );
        assert_eq!(
            nearest_names("concat_versio", 3),
            vec!["concat_version", "concat_edit_redo", "concat_edit_undo"]
        );
        assert_eq!(nearest_names("xyz", 3), vec!["tap", "swipe", "go_back"]);
        assert_eq!(nearest_names("get_battery", 3)[0], "get_battery");
    }

    #[test]
    fn edit_distance_vectors() {
        assert_eq!(edit_distance("", ""), 0);
        assert_eq!(edit_distance("", "abc"), 3);
        assert_eq!(edit_distance("kitten", "sitting"), 3);
        assert_eq!(edit_distance("get_battery", "get_battery"), 0);
        assert_eq!(edit_distance("abc", "abc"), 0);
    }

    #[test]
    fn catalog_section_shape() {
        let s = catalog_section();
        assert!(s.starts_with("- list_tools {}"));
        assert!(s.contains("- forge_trigger_eval "));
        assert!(s.contains("Tool lanjutan:\n"));
        assert!(s.contains("- PROOT (run_terminal"));
        assert!(s.contains("- CONCAT (vc315)"));
        // Tiap baris diakhiri newline; 210 segmen -> 210 baris + header?
        assert!(s.ends_with('\n'));
        // Baris tool terpanjang (forge_trigger_eval) 1490 unit UTF-16.
        // (UKURAN-INPUT-VC316.md §3a: 1.490 runtime; angka 1.491 di §7
        // adalah replika Python yang salah hitung 1 — Rust cocok runtime.)
        let longest = s
            .lines()
            .filter(|l| l.starts_with("- forge_trigger_eval "))
            .next()
            .unwrap();
        assert_eq!(crate::concat_api_client::utf16_len(longest), 1490);
    }
}
