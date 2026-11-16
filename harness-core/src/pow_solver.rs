//! Port 1:1 dari `PowSolver.kt` (217 baris, merge/vc317) — solver PoW
//! DeepSeekHashV1: SHA3-256 (Keccak-f[1600] hand-rolled) + `solveDeepSeek` /
//! `solveFast` / `solveFmt` / `solve`.
//!
//! SEMANTIK IDENTIK dengan Kotlin, termasuk:
//! - Keccak-f[1600] mengikuti tiny_sha3 (M.-J. Saarinen); `dsHashV1`
//!   MELOMPATI round 0 — hanya rounds 1..23 (dari reverse-engineering
//!   WASM resmi DeepSeek).
//! - Aritmetika `Long` Kotlin wrap diam-diam -> di sini `wrapping_*`
//!   eksplisit di semua operasi yang bisa overflow (`wrapping_add`);
//!   `^`/`&`/`rotate_left`/`shl` memang wrapping-safe di Rust
//!   (tidak ada `wrapping_xor` — XOR tidak bisa overflow).
//! - `solveFast` tetap single-thread di tahap ini (tanpa rayon —
//!   paritas dulu, sesuai HYBRID-MIGRATION-MAP.md §2.1).
//! - `String` Kotlin = UTF-8 di sini (`toByteArray(Charsets.UTF_8)`);
//!   `str` Rust selalu UTF-8 valid, jadi `as_bytes()` sama persis.
//!   (Satu-satunya celah: lone surrogate di String Kotlin diganti
//!   U+FFFD oleh encoder Java — JNI `get_string` juga lossy ke U+FFFD,
//!   jadi perilakunya tetap sama; lihat PATCH-S2.md.)
//! - `String.format("%02x", b)` Kotlin untuk byte -> `{:02x}` Rust
//!   (keduanya dua digit hex lowercase, byte diperlakukan unsigned).
//! - `hexToBytes`: `Character.digit` Kotlin mengembalikan -1 untuk
//!   karakter non-hex — dipetakan persis (byte hasil wrap), bukan error.
//!
//! DEVIASI TERDOKUMENTASI (kasus tepi yang tak terjangkau di lapangan):
//! - `solveFmt`/`solve` dengan `difficulty + 1 <= 0` (yakni
//!   `difficulty == -1` atau `difficulty == Long.MAX_VALUE`): Kotlin
//!   melempar `ArithmeticException` (bagi nol) atau menghitung target
//!   NEGATIF; Rust mengembalikan `None`. Difficulty server DeepSeek
//!   puluhan ribu — kasus ini tidak terjangkau; lihat PATCH-S2.md.

#![forbid(unsafe_code)]

use std::time::{SystemTime, UNIX_EPOCH};

/// Default `maxTries` Kotlin: `solveFast` = 50 jt, `solveFmt` = 3 jt,
/// `solve` = 5 jt.
pub const SOLVE_FAST_DEFAULT_MAX_TRIES: i64 = 50_000_000;
pub const SOLVE_FMT_DEFAULT_MAX_TRIES: i64 = 3_000_000;
pub const SOLVE_DEFAULT_MAX_TRIES: i64 = 5_000_000;

/// Hasil satu solve — padanan `PowSolver.PowResult` Kotlin.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PowResult {
    pub nonce: i64,
    pub hash_hex: String,
    pub tries: i64,
    pub elapsed_ms: i64,
}

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn hex_encode(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        // `{:02x}` pada u8 = dua digit hex lowercase — sama seperti
        // `String.format("%02x", b)` Kotlin pada Byte.
        s.push_str(&format!("{b:02x}"));
    }
    s
}

// ---------------------------------------------------------------- Keccak ---

/// Round constant Keccak-f[1600] — pola bit SAMA seperti `RC` Kotlin
/// (Long bertanda di Kotlin = bit pattern yang sama di u64).
const RC: [u64; 24] = [
    0x0000_0000_0000_0001,
    0x0000_0000_0000_8082,
    0x8000_0000_0000_808a,
    0x8000_0000_8000_8000,
    0x0000_0000_0000_808b,
    0x0000_0000_8000_0001,
    0x8000_0000_8000_8081,
    0x8000_0000_0000_8009,
    0x0000_0000_0000_008a,
    0x0000_0000_0000_0088,
    0x0000_0000_8000_8009,
    0x0000_0000_8000_000a,
    0x0000_0000_8000_808b,
    0x8000_0000_0000_008b,
    0x8000_0000_0000_8089,
    0x8000_0000_0000_8003,
    0x8000_0000_0000_8002,
    0x8000_0000_0000_0080,
    0x0000_0000_0000_800a,
    0x8000_0000_8000_000a,
    0x8000_0000_8000_8081,
    0x8000_0000_0000_8080,
    0x0000_0000_8000_0001,
    0x8000_0000_8000_8008,
];

const ROTC: [u32; 24] = [
    1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44,
];

const PILN: [usize; 24] = [
    10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1,
];

fn keccak_f(st: &mut [u64; 25], from_round: usize) {
    let mut bc = [0u64; 5];
    for rc in RC.iter().skip(from_round) {
        // Theta
        for i in 0..5 {
            // XOR tidak bisa overflow — `^` sudah wrapping secara inheren
            // (tidak ada `wrapping_xor` di Rust).
            bc[i] = st[i] ^ st[i + 5] ^ st[i + 10] ^ st[i + 15] ^ st[i + 20];
        }
        for i in 0..5 {
            let t = bc[(i + 4) % 5] ^ bc[(i + 1) % 5].rotate_left(1);
            let mut j = 0;
            while j < 25 {
                st[j + i] ^= t;
                j += 5;
            }
        }
        // Rho Pi
        let mut t = st[1];
        for i in 0..24 {
            let j = PILN[i];
            let tmp = st[j];
            st[j] = t.rotate_left(ROTC[i]);
            t = tmp;
        }
        // Chi
        let mut j = 0;
        while j < 25 {
            bc.copy_from_slice(&st[j..j + 5]);
            for i in 0..5 {
                st[j + i] = bc[i] ^ ((!bc[(i + 1) % 5]) & bc[(i + 2) % 5]);
            }
            j += 5;
        }
        // Iota
        st[0] ^= *rc;
    }
}

fn absorb(st: &mut [u64; 25], block: &[u8], off: usize, len: usize) {
    let mut i = 0;
    while i < len {
        let lane_idx = i / 8;
        // Kotlin: `(block[off+i].toLong() and 0xFF) shl (8 * (i % 8))`
        // — byte dipandang unsigned lalu shift; shift max 56, aman.
        st[lane_idx] ^= (block[off + i] as u64) << (8 * (i % 8));
        i += 1;
    }
}

fn keccak256(input: &[u8], from_round: usize) -> [u8; 32] {
    const RATE: usize = 136;
    let mut st = [0u64; 25];
    let mut offset = 0;
    while input.len() - offset >= RATE {
        absorb(&mut st, input, offset, RATE);
        keccak_f(&mut st, from_round);
        offset += RATE;
    }
    let mut last = [0u8; RATE];
    let rem = input.len() - offset;
    last[..rem].copy_from_slice(&input[offset..]);
    last[rem] = 0x06;
    last[RATE - 1] |= 0x80;
    absorb(&mut st, &last, 0, RATE);
    keccak_f(&mut st, from_round);
    let mut out = [0u8; 32];
    let mut o = 0;
    while o < 32 {
        let lane = st[o / 8];
        out[o] = (lane >> (8 * (o % 8))) as u8;
        o += 1;
    }
    out
}

/// SHA3-256 (rate 136 byte, suffix 0x06, pad10*1).
pub fn sha3_256(input: &[u8]) -> [u8; 32] {
    keccak256(input, 0)
}

/// DeepSeekHashV1 ASLI: SHA3-256 tetapi Keccak-f[1600] MELOMPATI round 0.
pub fn ds_hash_v1(input: &[u8]) -> [u8; 32] {
    keccak256(input, 1)
}

pub fn sha3_hex(input: &str) -> String {
    hex_encode(&sha3_256(input.as_bytes()))
}

pub fn ds_hash_v1_hex(input: &str) -> String {
    hex_encode(&ds_hash_v1(input.as_bytes()))
}

// -------------------------------------------------------------- Hex util ---

/// Padanan `Character.digit(c, 16)` Kotlin: -1 bila bukan digit hex.
fn hex_digit(c: u8) -> i32 {
    match c {
        b'0'..=b'9' => (c - b'0') as i32,
        b'a'..=b'f' => (c - b'a') as i32 + 10,
        b'A'..=b'F' => (c - b'A') as i32 + 10,
        _ => -1,
    }
}

/// Padanan `hexToBytes` Kotlin: `out.size = hex.length / 2` (hex ganjil:
/// karakter terakhir diabaikan); digit invalid -> -1 lalu wrap ke byte
/// (bukan error) — perilaku Kotlin dipertahankan persis.
pub fn hex_to_bytes(hex: &str) -> Vec<u8> {
    let b = hex.as_bytes();
    let mut out = Vec::with_capacity(b.len() / 2);
    let mut i = 0;
    while i < out.capacity() {
        let v = ((hex_digit(b[2 * i]) << 4) + hex_digit(b[2 * i + 1])) as u8;
        out.push(v);
        i += 1;
    }
    out
}

// ------------------------------------------------------------- Threshold ---

/// `floor(2^256 / d)` sebagai 32 byte big-endian — padanan eksak rantai
/// Kotlin `BigInteger.ONE.shiftLeft(256).divide(valueOf(d))` +
/// `toByteArray()` + copy 32 byte terakhir (termasuk kasus d == 1 yang
/// menghasilkan target 32 byte NOL).
///
/// `d` HARUS >= 1 (pemanggil menjamin; `difficulty + 1 <= 0` ditangani
/// di pemanggil — lihat catatan deviasi di header modul).
fn threshold_32(d: u64) -> [u8; 32] {
    debug_assert!(d >= 1);
    // Divisi panjang bit-per-bit atas 2^256 (bit 256 = 1, sisanya 0).
    // Sisa < 2*d <= 2^64 muat di u128 dengan aman.
    let mut q = [0u8; 33]; // big-endian; byte 0 = bit 256..263
    let mut r: u128 = 0;
    for i in (0..=256).rev() {
        let bit: u128 = if i == 256 { 1 } else { 0 };
        r = (r << 1) | bit;
        if r >= d as u128 {
            r -= d as u128;
            q[32 - (i / 8)] |= 1u8 << (i % 8);
        }
    }
    let mut out = [0u8; 32];
    out.copy_from_slice(&q[1..33]);
    out
}

/// `difficulty + 1` dengan wrap Kotlin, lalu threshold. `None` bila
/// `difficulty + 1 <= 0` (deviasi terdokumentasi — Kotlin melempar /
/// menghitung target negatif; tak terjangkau di lapangan).
fn threshold_for_difficulty(difficulty: i64) -> Option<[u8; 32]> {
    let d = difficulty.wrapping_add(1);
    if d <= 0 {
        return None;
    }
    Some(threshold_32(d as u64))
}

// ----------------------------------------------------------------- Solve ---

/// Skema PoW DeepSeek ASLI: server memilih answer di [0, difficulty)
/// dan menerbitkan challenge = DeepSeekHashV1(salt + "_" + expire_at +
/// "_" + answer). Cari nonce n di rentang yang sama sampai hash-nya
/// PERSIS sama dengan challenge.
pub fn solve_deep_seek(
    challenge_hex: &str,
    salt: &str,
    expire_at: i64,
    difficulty: i64,
) -> Option<PowResult> {
    let start = now_millis();
    let target = hex_to_bytes(challenge_hex);
    let prefix = format!("{salt}_{expire_at}_");
    let prefix = prefix.as_bytes();
    let mut n: i64 = 0;
    while n < difficulty {
        let nonce_bytes = n.to_string();
        let nonce_bytes = nonce_bytes.as_bytes();
        let mut input = Vec::with_capacity(prefix.len() + nonce_bytes.len());
        input.extend_from_slice(prefix);
        input.extend_from_slice(nonce_bytes);
        let digest = ds_hash_v1(&input);
        if digest.as_slice() == target.as_slice() {
            return Some(PowResult {
                nonce: n,
                hash_hex: hex_encode(&digest),
                tries: n.wrapping_add(1),
                elapsed_ms: now_millis() - start,
            });
        }
        n = n.wrapping_add(1);
    }
    None
}

/// Versi cepat untuk difficulty asli DeepSeek (puluhan ribu+): tanpa
/// hex/BigInteger per percobaan, bandingkan byte digest langsung ke
/// target 32-byte. Kondisi: digest < target (unsigned).
///
/// CATATAN PARITAS — QUIRK KOTLIN DIREPLIKASI, BUKAN DIPERBAIKI:
/// `solveFast` Kotlin memanggil
/// `solveFmt(challenge, salt, difficulty, "A", maxTries)` — argumen
/// posisi ke-5 `solveFmt` adalah `expireAt`, BUKAN `maxTries`. Akibatnya
/// `maxTries` yang diterima `solveFast` hanya masuk ke `expireAt`
/// (yang format "A" abaikan) dan batas percobaan efektif SELALU default
/// `solveFmt` = 3.000.000. Di sini direplikasi persis: `max_tries`
/// diteruskan sebagai `expire_at`, batas pakai
/// `SOLVE_FMT_DEFAULT_MAX_TRIES`.
pub fn solve_fast(
    challenge: &str,
    salt: &str,
    difficulty: i64,
    max_tries: i64,
) -> Option<PowResult> {
    solve_fmt(
        challenge,
        salt,
        difficulty,
        "A",
        max_tries,
        SOLVE_FMT_DEFAULT_MAX_TRIES,
    )
}

/// Varian format string PoW (n = nonce, e = expire_at):
/// A = c+s+n (referensi) | B = s+c+n | C = c_s_n | D = c+s+e+n |
/// E = c_s_e_n | F = n+c+s | G = c+n+s | H = s+n+c.
/// Format tak dikenal -> cabang `else` Kotlin = A.
pub fn solve_fmt(
    challenge: &str,
    salt: &str,
    difficulty: i64,
    format: &str,
    expire_at: i64,
    max_tries: i64,
) -> Option<PowResult> {
    let start = now_millis();
    let target = threshold_for_difficulty(difficulty)?;
    let e = expire_at.to_string();
    let (pre, post): (String, String) = match format {
        "B" => (format!("{salt}{challenge}"), String::new()),
        "C" => (format!("{challenge}_{salt}_"), String::new()),
        "D" => (format!("{challenge}{salt}{e}"), String::new()),
        "E" => (format!("{challenge}_{salt}_{e}_"), String::new()),
        "F" => (String::new(), format!("{challenge}{salt}")),
        "G" => (challenge.to_string(), salt.to_string()),
        "H" => (salt.to_string(), challenge.to_string()),
        _ => (format!("{challenge}{salt}"), String::new()),
    };
    let prefix = pre.as_bytes();
    let suffix = post.as_bytes();
    let mut nonce: i64 = 0;
    while nonce < max_tries {
        let nonce_bytes = nonce.to_string();
        let nonce_bytes = nonce_bytes.as_bytes();
        let mut input = Vec::with_capacity(prefix.len() + nonce_bytes.len() + suffix.len());
        input.extend_from_slice(prefix);
        input.extend_from_slice(nonce_bytes);
        input.extend_from_slice(suffix);
        let digest = sha3_256(&input);
        // Perbandingan unsigned big-endian byte-per-byte — sama seperti
        // loop `cmp` Kotlin (`a < b -> -1`, `a > b -> 1`).
        if digest < target {
            return Some(PowResult {
                nonce,
                hash_hex: hex_encode(&digest),
                tries: nonce.wrapping_add(1),
                elapsed_ms: now_millis() - start,
            });
        }
        nonce = nonce.wrapping_add(1);
    }
    None
}

/// Cari nonce: int(hash) < (2^256)/(difficulty+1). Perbandingan via byte:
/// hash (32 byte big-endian dari hex) dibandingkan ke target yang
/// dihitung sebagai threshold byte array. Implementasi literal rantai
/// Kotlin (hex per percobaan -> parse -> bandingkan) — hasilnya identik
/// dengan `solve_fmt(..., "A", ...)`; dipertahankan terpisah supaya
/// jejak perilakunya 1:1 dengan Kotlin.
pub fn solve(challenge: &str, salt: &str, difficulty: i64, max_tries: i64) -> Option<PowResult> {
    let start = now_millis();
    let target = threshold_for_difficulty(difficulty)?;
    let mut nonce: i64 = 0;
    while nonce < max_tries {
        let hex = sha3_hex(&format!("{challenge}{salt}{nonce}"));
        // `BigInteger(hex, 16)` Kotlin = nilai unsigned dari 64 digit hex.
        let v = hex_to_bytes(&hex);
        if v.as_slice() < target.as_slice() {
            return Some(PowResult {
                nonce,
                hash_hex: hex,
                tries: nonce.wrapping_add(1),
                elapsed_ms: now_millis() - start,
            });
        }
        nonce = nonce.wrapping_add(1);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(b: &[u8; 32]) -> String {
        hex_encode(b)
    }

    // ================= SHA3-256: vektor standar FIPS 202 =================
    // Nilai harapan = hashlib.sha3_256 (CPython) + vektor NIST yang dikenal.
    #[test]
    fn sha3_empty() {
        assert_eq!(
            hex(&sha3_256(b"")),
            "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a"
        );
    }
    #[test]
    fn sha3_abc() {
        assert_eq!(
            hex(&sha3_256(b"abc")),
            "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532"
        );
    }
    #[test]
    fn sha3_long_56() {
        // 56 byte — pesan multi-blok parsial (rate 136, satu blok + pad).
        assert_eq!(
            hex(&sha3_256(
                b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
            )),
            "41c0dba2a9d6240849100376a8235e2c82e1b9998a999e21db32dd97496d3376"
        );
    }
    #[test]
    fn sha3_long_112() {
        assert_eq!(
            hex(&sha3_256(
                b"abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstenopqrtu"
            )),
            "eaba0e21e965e8dfab58e90b9f462f8042627b44e52d888ccc6e59762a1e2628"
        );
    }
    #[test]
    fn sha3_long_200_multi_block() {
        // 200 byte > rate 136: dua blok absorb (136 + 64) — uji loop absorb.
        assert_eq!(
            hex(&sha3_256(&[b'a'; 200])),
            "cce34485baf2bf2aca99b94833892a4f52896d3d153f7b840cc4f9fe695f1387"
        );
    }
    #[test]
    fn sha3_hex_fn() {
        assert_eq!(
            sha3_hex("abc"),
            "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532"
        );
    }
    #[test]
    fn sha3_non_ascii_utf8() {
        // String Kotlin di-encode UTF-8 — byte multi-byte harus sama.
        // "héllo😀" UTF-8 = 68 c3 a9 6c 6c 6f f0 9f 98 80.
        let h = sha3_hex("héllo\u{1F600}");
        assert_eq!(h.len(), 64);
        assert_eq!(hex(&sha3_256("héllo\u{1F600}".as_bytes())), h);
    }

    // ============ dsHashV1: vektor dari referensi Keccak independen =======
    // Dihasilkan /tmp/pow_ref.py (implementasi Keccak dari spesifikasi
    // FIPS 202, from_round=1) — BUKAN dari kode Rust ini.
    #[test]
    fn dsv1_empty() {
        assert_eq!(
            hex(&ds_hash_v1(b"")),
            "e594808bc5b7151ac160c6d39a02e0a8e261ed588578403099e3561dc40c26b3"
        );
    }
    #[test]
    fn dsv1_abc() {
        assert_eq!(
            hex(&ds_hash_v1(b"abc")),
            "f841106c601ce9be9bc38525e90d4178d47f21dd8eb9f238fc55ffaa4ca94506"
        );
    }
    #[test]
    fn dsv1_differs_from_sha3() {
        // Skip round 0 HARUS mengubah hasil (bukti from_round dipakai).
        assert_ne!(ds_hash_v1(b"abc"), sha3_256(b"abc"));
        assert_ne!(ds_hash_v1(b""), sha3_256(b""));
    }
    #[test]
    fn dsv1_hello_world() {
        assert_eq!(
            hex(&ds_hash_v1(b"hello world")),
            "d3861d3283d610f532813c30dd15162c6980aed6d34f05aa26caac9baf306f95"
        );
    }
    #[test]
    fn dsv1_boundary_135() {
        // 135 byte: rem = 135, pad 0x06 di byte 135, 0x80 di byte 135 juga
        // (last[135] |= 0x80 setelah last[135] = 0x06 -> 0x86).
        assert_eq!(
            hex(&ds_hash_v1(&[b'x'; 135])),
            "83b37f61450bade766525ba50e2989c301c7a6e1dea2a3b75a7ea76c95d220ab"
        );
    }
    #[test]
    fn dsv1_boundary_136() {
        // Tepat 1 blok penuh + blok pad terpisah.
        assert_eq!(
            hex(&ds_hash_v1(&[b'y'; 136])),
            "d64717c313c6ddbabebdfc6de1c25ba66f04ff0b9d8f95ad0b1c7acb8fb9e564"
        );
    }
    #[test]
    fn dsv1_boundary_137() {
        assert_eq!(
            hex(&ds_hash_v1(&[b'z'; 137])),
            "99b41bd1ea50dbb2434d524ec949ae7e2d70c4ebd2b8735d5106ac4cfc37edc2"
        );
    }
    #[test]
    fn dsv1_300_multi_block() {
        assert_eq!(
            hex(&ds_hash_v1(&[b'w'; 300])),
            "4a211e996ee088011e41aac5377caa3f82ee360bf99352445ed9fe38cf0e8f7e"
        );
    }
    #[test]
    fn dsv1_hex_fn() {
        assert_eq!(
            ds_hash_v1_hex("harness-test_1792611672443_4242"),
            "006e9152b954a1f141c28e00f64b88f32a3ddc79fa8cdab70671dfc1aca8d655"
        );
    }

    // ============================ hexToBytes ===============================
    #[test]
    fn hex_to_bytes_roundtrip() {
        assert_eq!(hex_to_bytes("00ff10ab"), vec![0x00, 0xff, 0x10, 0xab]);
        assert_eq!(hex_to_bytes("006E9152"), vec![0x00, 0x6e, 0x91, 0x52]);
        assert_eq!(hex_to_bytes(""), Vec::<u8>::new());
    }
    #[test]
    fn hex_to_bytes_odd_length_ignores_last() {
        // Kotlin: out.size = len/2 — karakter terakhir diabaikan.
        assert_eq!(hex_to_bytes("abc"), vec![0xab]);
    }
    #[test]
    fn hex_to_bytes_invalid_digit_kotlin_semantics() {
        // Character.digit -> -1: ((−1)<<4)+(−1) = −17 -> byte 0xEF.
        assert_eq!(hex_to_bytes("zz"), vec![0xef]);
        assert_eq!(hex_to_bytes("g0"), vec![0xf0]);
    }

    // ============================ threshold ================================
    #[test]
    fn threshold_d1_is_all_zero() {
        // difficulty 0 -> 2^256/1 = 2^256 -> 32 byte terakhir = NOL.
        assert_eq!(threshold_for_difficulty(0), Some([0u8; 32]));
    }
    #[test]
    fn threshold_d2_is_half() {
        // difficulty 1 -> 2^256/2 = 2^255 -> 0x80 diikuti 31 byte NOL.
        let mut want = [0u8; 32];
        want[0] = 0x80;
        assert_eq!(threshold_for_difficulty(1), Some(want));
    }
    #[test]
    fn threshold_difficulty_minus1_none() {
        // Deviasi terdokumentasi: Kotlin melempar ArithmeticException.
        assert_eq!(threshold_for_difficulty(-1), None);
    }

    // ========================= solveDeepSeek ===============================
    #[test]
    fn solve_deep_seek_planted_main_activity() {
        // Nilai HARAPAN EKSPLISIT dari source Kotlin (MainActivity.kt
        // work-impl/integrated:16192-16193): challenge ditanam dari
        // dsHashV1Hex("harness-test_1792611672443_4242"), answer 4242.
        let r = solve_deep_seek(
            "006e9152b954a1f141c28e00f64b88f32a3ddc79fa8cdab70671dfc1aca8d655",
            "harness-test",
            1792611672443,
            100000,
        );
        let r = r.expect("harus ketemu nonce 4242");
        assert_eq!(r.nonce, 4242);
        assert_eq!(r.tries, 4243);
        assert_eq!(
            r.hash_hex,
            "006e9152b954a1f141c28e00f64b88f32a3ddc79fa8cdab70671dfc1aca8d655"
        );
        assert!(r.elapsed_ms >= 0);
    }
    #[test]
    fn solve_deep_seek_not_found_returns_none() {
        // Challenge acak yang tak ada jawabannya di [0, 100).
        let r = solve_deep_seek(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "salt-tidak-ada",
            1,
            100,
        );
        assert_eq!(r, None);
    }
    #[test]
    fn solve_deep_seek_difficulty_zero_returns_none() {
        let r = solve_deep_seek(
            "006e9152b954a1f141c28e00f64b88f32a3ddc79fa8cdab70671dfc1aca8d655",
            "harness-test",
            1792611672443,
            0,
        );
        assert_eq!(r, None);
    }

    // ============================ solveFmt =================================
    #[test]
    fn solve_fmt_a_difficulty_1000() {
        // Vektor dari referensi Python independen (brute force).
        let r =
            solve_fmt("testchallenge", "testsalt", 1000, "A", 0, 3_000_000).expect("harus ketemu");
        assert_eq!(r.nonce, 2357);
        assert_eq!(r.tries, 2358);
        assert_eq!(
            r.hash_hex,
            "000c0bd6d07267df7eff06a1a3eab29dd10300a9357c43c0925682dfb3ab9551"
        );
        assert!(r.elapsed_ms >= 0);
    }
    #[test]
    fn solve_fmt_a_difficulty_1() {
        let r = solve_fmt("testchallenge", "testsalt", 1, "A", 0, 3_000_000).expect("harus ketemu");
        assert_eq!(r.nonce, 1);
        assert_eq!(
            r.hash_hex,
            "16108e9e48abebe4d0e1ba28f0978c12d777a2e30535c5a9439ee54e7fa8123f"
        );
    }
    #[test]
    fn solve_fmt_a_difficulty_10_same_as_1() {
        // Nonce 1 sudah < 2^256/11 juga.
        let r =
            solve_fmt("testchallenge", "testsalt", 10, "A", 0, 3_000_000).expect("harus ketemu");
        assert_eq!(r.nonce, 1);
    }
    #[test]
    fn solve_fmt_all_formats_difficulty_1000() {
        // Vektor brute-force Python independen (challenge "c", salt "s",
        // expire_at 12345 untuk D/E).
        let cases = [
            ("B", 65),
            ("C", 895),
            ("D", 345),
            ("E", 145),
            ("F", 1679),
            ("G", 307),
            ("H", 6),
        ];
        for (fmt, want) in cases {
            let r = solve_fmt("c", "s", 1000, fmt, 12345, 3_000_000)
                .unwrap_or_else(|| panic!("format {fmt} harus ketemu"));
            assert_eq!(r.nonce, want, "format {fmt}");
            assert_eq!(r.tries, want + 1);
        }
    }
    #[test]
    fn solve_fmt_unknown_format_falls_back_to_a() {
        // Kotlin: cabang else = A.
        let a = solve_fmt("testchallenge", "testsalt", 1000, "A", 0, 3_000_000)
            .expect("A harus ketemu");
        let z = solve_fmt("testchallenge", "testsalt", 1000, "ZZZ", 0, 3_000_000)
            .expect("ZZZ harus ketemu (= A)");
        // Bandingkan semua field KECUALI elapsed_ms (waktu wall-clock
        // wajar beda antar run).
        assert_eq!(a.nonce, z.nonce);
        assert_eq!(a.hash_hex, z.hash_hex);
        assert_eq!(a.tries, z.tries);
    }
    #[test]
    fn solve_fmt_max_tries_exhausted_returns_none() {
        // maxTries 10 terlalu kecil untuk difficulty 1000 di input ini.
        let r = solve_fmt("testchallenge", "testsalt", 1000, "A", 0, 10);
        assert_eq!(r, None);
    }
    #[test]
    fn solve_fmt_difficulty_0_never_succeeds() {
        // Target 32 byte NOL — digest < 0 mustahil.
        let r = solve_fmt("testchallenge", "testsalt", 0, "A", 0, 1000);
        assert_eq!(r, None);
    }

    // ============================ solveFast ================================
    #[test]
    fn solve_fast_matches_solve_fmt_a() {
        let f = solve_fast("testchallenge", "testsalt", 1000, 3_000_000).expect("harus ketemu");
        let a =
            solve_fmt("testchallenge", "testsalt", 1000, "A", 0, 3_000_000).expect("harus ketemu");
        // Bandingkan semua field KECUALI elapsed_ms (waktu wall-clock
        // wajar beda antar run).
        assert_eq!(f.nonce, a.nonce);
        assert_eq!(f.hash_hex, a.hash_hex);
        assert_eq!(f.tries, a.tries);
        assert_eq!(f.nonce, 2357);
    }
    #[test]
    fn solve_fast_max_tries_arg_is_ignored_kotlin_quirk() {
        // QUIRK KOTLIN (lihat dok `solve_fast`): argumen maxTries
        // solveFast masuk ke expireAt, batas efektif tetap 3 jt.
        // Dengan max_tries=10 pun nonce 2357 HARUS ketemu.
        let f = solve_fast("testchallenge", "testsalt", 1000, 10)
            .expect("quirk: batas tetap 3 jt, nonce 2357 harus ketemu");
        assert_eq!(f.nonce, 2357);
    }

    // ============================== solve ==================================
    #[test]
    fn solve_matches_solve_fmt_a() {
        let s = solve("testchallenge", "testsalt", 1000, 3_000_000).expect("harus ketemu");
        assert_eq!(s.nonce, 2357);
        assert_eq!(
            s.hash_hex,
            "000c0bd6d07267df7eff06a1a3eab29dd10300a9357c43c0925682dfb3ab9551"
        );
    }
    #[test]
    fn solve_max_tries_exhausted_returns_none() {
        assert_eq!(solve("testchallenge", "testsalt", 1000, 10), None);
    }
}
