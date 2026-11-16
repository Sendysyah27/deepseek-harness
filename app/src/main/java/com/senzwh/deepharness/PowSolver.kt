package com.senzwh.deepharness

import harness.core.RustCore

/**
 * Solver Proof-of-Work DeepSeekHashV1: SHA3-256 murni Kotlin (Keccak-f[1600]),
 * tanpa dependensi platform — untuk mode API internal DeepSeek (eksperimen).
 * Algoritma: cari nonce agar int(sha3_256(challenge + salt + nonce)) < (1<<256)/(difficulty+1).
 * Implementasi Keccak mengikuti struktur publik tiny_sha3 (M.-J. Saarinen).
 */
object PowSolver {

    /**
     * Saklar A/B slice 2 (vc317): bila core Rust tersedia (.so ke-load
     * + versi seam cocok), solve + hash jalan di Rust; bila tidak,
     * fallback ke implementasi Kotlin lama di bawah (*Kotlin — JANGAN
     * dihapus). Sekali Rust melempar, saklar dimatikan untuk sisa proses.
     * Rust TIDAK dipanggil di main thread (pemanggil sudah di worker
     * thread: DsApiClient/SlotClient solve di latar).
     */
    var rustPowSolver: Boolean = true

    private val RC = longArrayOf(
        0x0000000000000001L, 0x0000000000008082L, (0x800000000000808auL).toLong(),
        (0x8000000080008000uL).toLong(), 0x000000000000808bL, 0x0000000080000001L,
        (0x8000000080008081uL).toLong(), (0x8000000000008009uL).toLong(), 0x000000000000008aL,
        0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
        0x000000008000808bL, (0x800000000000008buL).toLong(), (0x8000000000008089uL).toLong(),
        (0x8000000000008003uL).toLong(), (0x8000000000008002uL).toLong(), (0x8000000000000080uL).toLong(),
        0x000000000000800aL, (0x800000008000000auL).toLong(), (0x8000000080008081uL).toLong(),
        (0x8000000000008080uL).toLong(), 0x0000000080000001L, (0x8000000080008008uL).toLong()
    )
    private val ROTC = intArrayOf(1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44)
    private val PILN = intArrayOf(10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1)

    private fun keccakF(st: LongArray, fromRound: Int = 0) {
        val bc = LongArray(5)
        for (round in fromRound until 24) {
            // Theta
            for (i in 0 until 5) bc[i] = st[i] xor st[i + 5] xor st[i + 10] xor st[i + 15] xor st[i + 20]
            for (i in 0 until 5) {
                val t = bc[(i + 4) % 5] xor java.lang.Long.rotateLeft(bc[(i + 1) % 5], 1)
                var j = 0
                while (j < 25) { st[j + i] = st[j + i] xor t; j += 5 }
            }
            // Rho Pi
            var t = st[1]
            for (i in 0 until 24) {
                val j = PILN[i]
                val tmp = st[j]
                st[j] = java.lang.Long.rotateLeft(t, ROTC[i])
                t = tmp
            }
            // Chi
            var j = 0
            while (j < 25) {
                for (i in 0 until 5) bc[i] = st[j + i]
                for (i in 0 until 5) st[j + i] = bc[i] xor (bc[(i + 1) % 5].inv() and bc[(i + 2) % 5])
                j += 5
            }
            // Iota
            st[0] = st[0] xor RC[round]
        }
    }

    /** SHA3-256 (rate 136 byte, suffix 0x06, pad10*1). */
    private fun sha3_256Kotlin(input: ByteArray): ByteArray = keccak256(input, 0)

    /** DeepSeekHashV1 ASLI (dari reverse-engineering WASM resmi): SHA3-256 tetapi
     *  Keccak-f[1600] MELOMPATI round 0 — hanya rounds 1..23. */
    private fun dsHashV1Kotlin(input: ByteArray): ByteArray = keccak256(input, 1)

    private fun keccak256(input: ByteArray, fromRound: Int): ByteArray {
        val rate = 136
        val st = LongArray(25)
        var offset = 0
        while (input.size - offset >= rate) {
            absorb(st, input, offset, rate)
            keccakF(st, fromRound)
            offset += rate
        }
        val last = ByteArray(rate)
        val rem = input.size - offset
        System.arraycopy(input, offset, last, 0, rem)
        last[rem] = 0x06
        last[rate - 1] = (last[rate - 1].toInt() or 0x80).toByte()
        absorb(st, last, 0, rate)
        keccakF(st, fromRound)
        val out = ByteArray(32)
        var o = 0
        while (o < 32) {
            val lane = st[o / 8]
            out[o] = (lane ushr (8 * (o % 8))).toByte()
            o++
        }
        return out
    }

    private fun absorb(st: LongArray, block: ByteArray, off: Int, len: Int) {
        var i = 0
        while (i < len) {
            val laneIdx = i / 8
            st[laneIdx] = st[laneIdx] xor ((block[off + i].toLong() and 0xFF) shl (8 * (i % 8)))
            i++
        }
    }

    private fun sha3HexKotlin(input: String): String {
        val digest = sha3_256Kotlin(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun dsHashV1HexKotlin(input: String): String {
        val digest = dsHashV1Kotlin(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < out.size) {
            out[i] = ((Character.digit(hex[2 * i], 16) shl 4) + Character.digit(hex[2 * i + 1], 16)).toByte()
            i++
        }
        return out
    }

    /** Skema PoW DeepSeek ASLI: server memilih answer di [0, difficulty) dan menerbitkan
     *  challenge = DeepSeekHashV1(salt + "_" + expire_at + "_" + answer).
     *  Kita cari nonce n di rentang yang sama sampai hash-nya PERSIS sama dengan challenge. */
    private fun solveDeepSeekKotlin(challengeHex: String, salt: String, expireAt: Long, difficulty: Long): PowResult? {
        val start = System.currentTimeMillis()
        val target = hexToBytes(challengeHex)
        val prefix = (salt + "_" + expireAt.toString() + "_").toByteArray(Charsets.UTF_8)
        var n = 0L
        while (n < difficulty) {
            val nonceBytes = n.toString().toByteArray(Charsets.UTF_8)
            val input = ByteArray(prefix.size + nonceBytes.size)
            System.arraycopy(prefix, 0, input, 0, prefix.size)
            System.arraycopy(nonceBytes, 0, input, prefix.size, nonceBytes.size)
            val digest = dsHashV1Kotlin(input)
            if (digest.contentEquals(target)) {
                val sb = StringBuilder()
                for (b in digest) sb.append(String.format("%02x", b))
                return PowResult(n, sb.toString(), n + 1, System.currentTimeMillis() - start)
            }
            n++
        }
        return null
    }

    data class PowResult(val nonce: Long, val hashHex: String, val tries: Long, val elapsedMs: Long)

    /** Versi cepat untuk difficulty asli DeepSeek (puluhan ribu+): tanpa hex/BigInteger per percobaan,
     *  bandingkan byte digest langsung ke target 32-byte. Kondisi: digest < target (unsigned). */
    private fun solveFastKotlin(challenge: String, salt: String, difficulty: Long, maxTries: Long = 50_000_000): PowResult? =
        solveFmtKotlin(challenge, salt, difficulty, "A", maxTries)

    /** Varian format string PoW (n = nonce, e = expire_at):
     *  A = c+s+n (referensi) | B = s+c+n | C = c_s_n | D = c+s+e+n | E = c_s_e_n
     *  F = n+c+s | G = c+n+s | H = s+n+c */
    private fun solveFmtKotlin(challenge: String, salt: String, difficulty: Long, format: String, expireAt: Long = 0, maxTries: Long = 3_000_000): PowResult? {
        val start = System.currentTimeMillis()
        val targetBI = java.math.BigInteger.ONE.shiftLeft(256)
            .divide(java.math.BigInteger.valueOf(difficulty + 1))
        val raw = targetBI.toByteArray()
        val target = ByteArray(32)
        val copyLen = minOf(raw.size, 32)
        System.arraycopy(raw, raw.size - copyLen, target, 32 - copyLen, copyLen)
        val e = expireAt.toString()
        val (pre, post) = when (format) {
            "B" -> Pair(salt + challenge, "")
            "C" -> Pair(challenge + "_" + salt + "_", "")
            "D" -> Pair(challenge + salt + e, "")
            "E" -> Pair(challenge + "_" + salt + "_" + e + "_", "")
            "F" -> Pair("", challenge + salt)
            "G" -> Pair(challenge, salt)
            "H" -> Pair(salt, challenge)
            else -> Pair(challenge + salt, "")
        }
        val prefix = pre.toByteArray(Charsets.UTF_8)
        val suffix = post.toByteArray(Charsets.UTF_8)
        var nonce = 0L
        while (nonce < maxTries) {
            val nonceBytes = nonce.toString().toByteArray(Charsets.UTF_8)
            val input = ByteArray(prefix.size + nonceBytes.size + suffix.size)
            System.arraycopy(prefix, 0, input, 0, prefix.size)
            System.arraycopy(nonceBytes, 0, input, prefix.size, nonceBytes.size)
            System.arraycopy(suffix, 0, input, prefix.size + nonceBytes.size, suffix.size)
            val digest = sha3_256Kotlin(input)
            var cmp = 0
            for (i in 0 until 32) {
                val a = digest[i].toInt() and 0xFF
                val b = target[i].toInt() and 0xFF
                if (a != b) { cmp = if (a < b) -1 else 1; break }
            }
            if (cmp < 0) {
                val sb = StringBuilder()
                for (b in digest) sb.append(String.format("%02x", b))
                return PowResult(nonce, sb.toString(), nonce + 1, System.currentTimeMillis() - start)
            }
            nonce++
        }
        return null
    }

    /** Cari nonce: int(hash) < (2^256)/(difficulty+1). Perbandingan via byte:
     *  hash (32 byte big-endian dari hex) dibandingkan ke target yang dihitung
     *  sebagai threshold byte array sederhana: pakai BigInteger biar eksak. */
    private fun solveKotlin(challenge: String, salt: String, difficulty: Long, maxTries: Long = 5_000_000): PowResult? {
        val start = System.currentTimeMillis()
        val target = java.math.BigInteger.ONE.shiftLeft(256)
            .divide(java.math.BigInteger.valueOf(difficulty + 1))
        var nonce = 0L
        while (nonce < maxTries) {
            val hex = sha3HexKotlin(challenge + salt + nonce.toString())
            val v = java.math.BigInteger(hex, 16)
            if (v < target) {
                return PowResult(nonce, hex, nonce + 1, System.currentTimeMillis() - start)
            }
            nonce++
        }
        return null
    }

    // ---- Seam Rust (vc317, slice 2) ----

    /** Panggil Rust bila tersedia; null = fallback ke Kotlin.
     *  Sekali Rust melempar, saklar `rustPowSolver` dimatikan. */
    private inline fun <T> rustCall(block: () -> T): T? {
        if (!rustPowSolver || !RustCore.available) return null
        return try {
            block()
        } catch (t: Throwable) {
            rustPowSolver = false
            null
        }
    }

    /** Validasi string hex hasil Rust: tepat 64 digit hex lowercase.
     *  Panic Rust mengembalikan JSON error lewat seam — bentuk itu
     *  gagal validasi -> fallback Kotlin. */
    private fun validHashHex(hex: String?): Boolean =
        hex != null && hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }

    private val rustNonceRe = Regex("\"nonce\":(-?\\d+)")
    private val rustHashRe = Regex("\"hash_hex\":\"([0-9a-f]{64})\"")
    private val rustTriesRe = Regex("\"tries\":(-?\\d+)")
    private val rustElapsedRe = Regex("\"elapsed_ms\":(-?\\d+)")

    /** Parse JSON solve dari Rust. null = bukan hasil Rust yang valid
     *  (ok:false / bentuk tak dikenal) -> fallback Kotlin. `found:false`
     *  DITANGANI pemanggil (return null = tak ketemu, definitif). */
    private fun parseRustPowResult(json: String): PowResult? {
        if (!json.contains("\"ok\":true")) return null
        if (!json.contains("\"found\":true")) return null
        val nonce = rustNonceRe.find(json)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return null
        val hashHex = rustHashRe.find(json)?.groupValues?.getOrNull(1) ?: return null
        val tries = rustTriesRe.find(json)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return null
        val elapsed = rustElapsedRe.find(json)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return null
        return PowResult(nonce, hashHex, tries, elapsed)
    }

    /** Satu jalur solve Rust: tiga keadaan (bukan nullable — `null` saja
     *  tidak bisa membedakan "tak ketemu" dari "fallback"). */
    private sealed class RustSolve {
        object Fallback : RustSolve()
        object NotFound : RustSolve()
        class Found(val result: PowResult) : RustSolve()
    }

    /** Parse JSON solve dari Rust. `found:false` -> NotFound (definitif,
     *  sama seperti `null` Kotlin lama); `ok:false` / bentuk tak dikenal
     *  -> Fallback (pakai Kotlin lama). */
    private fun rustSolveResult(json: String?): RustSolve {
        if (json == null || !json.contains("\"ok\":true")) return RustSolve.Fallback
        if (json.contains("\"found\":false")) return RustSolve.NotFound
        val r = parseRustPowResult(json) ?: return RustSolve.Fallback
        return RustSolve.Found(r)
    }

    /** SHA3-256 (rate 136 byte, suffix 0x06, pad10*1). */
    fun sha3_256(input: ByteArray): ByteArray {
        rustCall { RustCore.powSha3Bytes(input) }?.let { out ->
            if (out.size == 32) return out
        }
        return sha3_256Kotlin(input)
    }

    /** DeepSeekHashV1 ASLI (dari reverse-engineering WASM resmi): SHA3-256 tetapi
     *  Keccak-f[1600] MELOMPATI round 0 — hanya rounds 1..23. */
    fun dsHashV1(input: ByteArray): ByteArray {
        rustCall { RustCore.powDsHashV1Bytes(input) }?.let { out ->
            if (out.size == 32) return out
        }
        return dsHashV1Kotlin(input)
    }

    fun sha3Hex(input: String): String {
        rustCall { RustCore.powSha3Hex(input) }?.let { hex ->
            if (validHashHex(hex)) return hex
        }
        return sha3HexKotlin(input)
    }

    fun dsHashV1Hex(input: String): String {
        rustCall { RustCore.powDsHashV1Hex(input) }?.let { hex ->
            if (validHashHex(hex)) return hex
        }
        return dsHashV1HexKotlin(input)
    }

    /** Skema PoW DeepSeek ASLI: server memilih answer di [0, difficulty) dan menerbitkan
     *  challenge = DeepSeekHashV1(salt + "_" + expire_at + "_" + answer).
     *  Kita cari nonce n di rentang yang sama sampai hash-nya PERSIS sama dengan challenge. */
    fun solveDeepSeek(challengeHex: String, salt: String, expireAt: Long, difficulty: Long): PowResult? {
        rustCall { RustCore.powSolveDeepSeekJson(challengeHex, salt, expireAt, difficulty) }?.let { json ->
            when (val r = rustSolveResult(json)) {
                is RustSolve.Found -> return r.result
                RustSolve.NotFound -> return null
                RustSolve.Fallback -> { /* jatuh ke fallback Kotlin */ }
            }
        }
        return solveDeepSeekKotlin(challengeHex, salt, expireAt, difficulty)
    }

    /** Versi cepat untuk difficulty asli DeepSeek (puluhan ribu+): tanpa hex/BigInteger per percobaan,
     *  bandingkan byte digest langsung ke target 32-byte. Kondisi: digest < target (unsigned).
     *  CATATAN: `maxTries` diteruskan ke Rust apa adanya — quirk Kotlin
     *  (maxTries solveFast masuk ke expireAt, batas efektif 3 jt)
     *  direplikasi di Rust (`pow_solver::solve_fast`), jadi kedua jalur sama. */
    fun solveFast(challenge: String, salt: String, difficulty: Long, maxTries: Long = 50_000_000): PowResult? {
        rustCall { RustCore.powSolveFastJson(challenge, salt, difficulty, maxTries) }?.let { json ->
            when (val r = rustSolveResult(json)) {
                is RustSolve.Found -> return r.result
                RustSolve.NotFound -> return null
                RustSolve.Fallback -> { /* jatuh ke fallback Kotlin */ }
            }
        }
        return solveFastKotlin(challenge, salt, difficulty, maxTries)
    }

    /** Varian format string PoW (n = nonce, e = expire_at):
     *  A = c+s+n (referensi) | B = s+c+n | C = c_s_n | D = c+s+e+n | E = c_s_e_n
     *  F = n+c+s | G = c+n+s | H = s+n+c */
    fun solveFmt(challenge: String, salt: String, difficulty: Long, format: String, expireAt: Long = 0, maxTries: Long = 3_000_000): PowResult? {
        rustCall { RustCore.powSolveFmtJson(challenge, salt, difficulty, format, expireAt, maxTries) }?.let { json ->
            when (val r = rustSolveResult(json)) {
                is RustSolve.Found -> return r.result
                RustSolve.NotFound -> return null
                RustSolve.Fallback -> { /* jatuh ke fallback Kotlin */ }
            }
        }
        return solveFmtKotlin(challenge, salt, difficulty, format, expireAt, maxTries)
    }

    /** Cari nonce: int(hash) < (2^256)/(difficulty+1). Perbandingan via byte:
     *  hash (32 byte big-endian dari hex) dibandingkan ke target yang dihitung
     *  sebagai threshold byte array sederhana: pakai BigInteger biar eksak. */
    fun solve(challenge: String, salt: String, difficulty: Long, maxTries: Long = 5_000_000): PowResult? {
        rustCall { RustCore.powSolveJson(challenge, salt, difficulty, maxTries) }?.let { json ->
            when (val r = rustSolveResult(json)) {
                is RustSolve.Found -> return r.result
                RustSolve.NotFound -> return null
                RustSolve.Fallback -> { /* jatuh ke fallback Kotlin */ }
            }
        }
        return solveKotlin(challenge, salt, difficulty, maxTries)
    }
}
