package com.senzwh.deepharness

import android.content.Context
import android.system.Os
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * ProotEngine (T4b2, brief migrasi §11) — Ubuntu 24.04 tertanam via proot,
 * TANPA root dan TANPA Termux.
 *
 * Kunci W^X (targetSdk 34): binary proot TIDAK dieksekusi dari filesDir —
 * ia dibundel sebagai libproot.so/libprootloader.so/libtalloc.so/
 * libandroid-shmem.so di jniLibs dan diekstrak installer ke nativeLibraryDir
 * (konteks apk_data_file, boleh exec). libtalloc dicari proot sebagai
 * "libtalloc.so.2" (SONAME), jadi disalin ke dir writable dengan nama itu
 * dan LD_LIBRARY_PATH diarahkan ke sana + nativeLibraryDir.
 *
 * Rootfs: tarball resmi katalog proot-distro (Ubuntu noble aarch64),
 * SHA-256 dipatok dari plugin distro v4.28.0 — diverifikasi sebelum ekstrak.
 */
object ProotEngine {

    private const val ROOTFS_URL =
        "https://github.com/termux/proot-distro/releases/download/v4.18.0/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
    private const val ROOTFS_WRAPPER = "ubuntu-noble-aarch64"
    private const val ROOTFS_SHA256 =
        "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa"

    fun rootfsDir(ctx: Context): File = File(ctx.filesDir, "proot/ubuntu")
    private fun workDir(ctx: Context): File = File(ctx.filesDir, "proot")
    private fun libDir(ctx: Context): File = File(ctx.filesDir, "proot-lib")
    private fun tmpDir(ctx: Context): File = File(ctx.filesDir, "proot-tmp")

    fun isReady(ctx: Context): Boolean =
        File(rootfsDir(ctx), "usr/bin/bash").exists() || File(rootfsDir(ctx), "bin/bash").exists()

    fun status(ctx: Context): String {
        val dir = rootfsDir(ctx)
        if (!dir.exists()) return "Status: rootfs Ubuntu BELUM diunduh."
        val sizeMb = try { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1048576 } catch (e: Exception) { 0 }
        return if (isReady(ctx)) "Status: Ubuntu 24.04 SIAP (rootfs ±" + sizeMb + " MB)."
        else "Status: rootfs ada tapi belum lengkap (±" + sizeMb + " MB) — unduh ulang."
    }

    /** Salin libtalloc.so (dari nativeLibraryDir) menjadi libtalloc.so.2. */
    private fun ensureLibs(ctx: Context): Boolean {
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        if (!File(nativeDir, "libproot.so").exists()) return false
        val ld = libDir(ctx)
        ld.mkdirs()
        val dst = File(ld, "libtalloc.so.2")
        val src = File(nativeDir, "libtalloc.so")
        if (src.exists() && (!dst.exists() || dst.length() != src.length())) {
            try { src.copyTo(dst, overwrite = true) } catch (e: Exception) { return false }
        }
        tmpDir(ctx).mkdirs()
        return true
    }

    fun downloadAndInstall(ctx: Context, onProgress: (String) -> Unit): String {
        val msg = installInternal(ctx, onProgress)
        try {
            ctx.getSharedPreferences("harness", Context.MODE_PRIVATE).edit()
                .putString("prootLastResult", msg + " [" + java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale("id")).format(java.util.Date()) + "]")
                .apply()
        } catch (e: Exception) {}
        return msg
    }

    private fun installInternal(ctx: Context, onProgress: (String) -> Unit): String {
        val wd = workDir(ctx); wd.mkdirs()
        val tarFile = File(wd, "rootfs.tar.xz")
        try {
            onProgress("Mengunduh rootfs Ubuntu…")
            var conn = URL(ROOTFS_URL).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "Harness")
            conn.connect()
            if (conn.responseCode !in 200..299) return "Unduhan gagal: HTTP " + conn.responseCode
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { ins ->
                FileOutputStream(tarFile).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress("Mengunduh rootfs… " + pct + "% (" + (done / 1048576) + " MB)") }
                        }
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!sha.equals(ROOTFS_SHA256, ignoreCase = true)) {
                tarFile.delete()
                return "SHA-256 rootfs TIDAK cocok — unduhan dibatalkan demi keamanan."
            }
            onProgress("SHA-256 cocok. Mengekstrak rootfs…")
            val dest = rootfsDir(ctx)
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()
            val count = extractTarXz(tarFile, dest) { n -> if (n % 400 == 0) onProgress("Mengekstrak… " + n + " berkas") }
            tarFile.delete()
            // Sentuhan pasca-ekstrak ala proot-distro: DNS + hosts.
            touchUp(dest)
            return "✅ Ubuntu 24.04 terpasang (" + count + " berkas). Terminal mode 'proot' siap — tes dengan tombol Tes."
        } catch (e: Exception) {
            try { tarFile.delete() } catch (x: Exception) {}
            return "Gagal memasang Ubuntu: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun touchUp(dest: File) {
        try {
            File(dest, "etc").mkdirs()
            File(dest, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            val hosts = File(dest, "etc/hosts")
            if (!hosts.exists()) hosts.writeText("127.0.0.1 localhost\n")
            File(dest, ".harness-installed").writeText("ubuntu-noble pd-v4.18.0 " + System.currentTimeMillis())
        } catch (e: Exception) {}
    }

    /** Perbaiki tata letak rootfs dari pemasangan < v4.28.0: berkas terekstrak
     *  di dalam folder bungkus — pindahkan isinya naik satu tingkat.
     *  Menyelamatkan unduhan yang sudah ada TANPA unduh ulang. */
    fun repairLayout(ctx: Context): Boolean {
        return try {
            val root = rootfsDir(ctx)
            val wrap = File(root, ROOTFS_WRAPPER)
            if (!wrap.isDirectory) return false
            if (isReady(ctx)) return false
            if (!File(wrap, "usr/bin/bash").exists() && !File(wrap, "bin/bash").exists()) return false
            val kids = wrap.listFiles() ?: return false
            var moved = false
            for (k in kids) {
                val dst = File(root, k.name)
                if (dst.exists()) mergeMove(k, dst) else if (k.renameTo(dst)) moved = true
            }
            try { wrap.deleteRecursively() } catch (e: Exception) {}
            if (isReady(ctx)) { touchUp(root); moved = true }
            moved
        } catch (e: Exception) { false }
    }

    private fun mergeMove(src: File, dst: File) {
        if (src.isDirectory && dst.isDirectory) {
            val kids = src.listFiles() ?: return
            for (k in kids) {
                val d = File(dst, k.name)
                if (d.exists()) mergeMove(k, d) else k.renameTo(d)
            }
            try { src.delete() } catch (e: Exception) {}
        }
    }

    fun deleteRootfs(ctx: Context): String {
        return try {
            val d = rootfsDir(ctx)
            if (d.exists()) d.deleteRecursively()
            "Rootfs Ubuntu dihapus. Unduh lagi kapan pun dari tombol yang sama."
        } catch (e: Exception) { "Gagal menghapus: " + (e.message ?: "") }
    }

    /** Jalankan perintah di dalam Ubuntu proot. Blocking; panggil dari thread latar. */
    fun run(ctx: Context, command: String, timeoutSec: Int = 120): String {
        if (!isReady(ctx)) { try { repairLayout(ctx) } catch (e: Exception) {} }
        if (!isReady(ctx)) return "Ubuntu proot belum terpasang — pasang dulu dari Setup tombol 52 (Unduh & Pasang, sekali saja)."
        if (!ensureLibs(ctx)) return "Binary proot tidak ditemukan di paket aplikasi (libproot.so hilang)."
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val rootfs = rootfsDir(ctx).absolutePath
        val args = listOf(
            File(nativeDir, "libproot.so").absolutePath,
            "-r", rootfs,
            "--link2symlink", "--sysvipc", "--kill-on-exit", "--change-id=0:0",
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/sdcard",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "TERM=xterm",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/bash", "-lc", command
        )
        return try {
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["PROOT_TMP_DIR"] = tmpDir(ctx).absolutePath
            env["PROOT_LOADER"] = File(nativeDir, "libprootloader.so").absolutePath
            env["LD_LIBRARY_PATH"] = libDir(ctx).absolutePath + ":" + nativeDir
            env["HOME"] = ctx.filesDir.absolutePath
            val proc = pb.start()
            val sb = StringBuilder()
            // vc301 SF-D5: potongan di 64000 char TIDAK lagi diam-diam —
            // reader terus menguras output dan menghitung char yang dibuang.
            val droppedVc301 = IntArray(1)
            val reader = Thread {
                try {
                    proc.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            synchronized(sb) {
                                val room = 64000 - sb.length
                                if (room > 0) sb.append(buf, 0, minOf(n, room))
                                if (n > room) droppedVc301[0] += n - maxOf(room, 0)
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
            reader.isDaemon = true
            reader.start()
            val finished = proc.waitFor(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                try { proc.destroyForcibly() } catch (e: Exception) {}
                return "Timeout " + timeoutSec + " dtk di proot — perintah dibunuh. Output parsial:\n" + sb.toString().take(4000)
            }
            try { reader.join(1500) } catch (e: Exception) {}
            val dropped = synchronized(sb) { droppedVc301[0] }
            val outRaw = sb.toString().trim()
            val out = if (dropped > 0) outRaw + "\n…(output dipotong di 64000 char oleh proot, sisa $dropped char tidak ditampilkan)…" else outRaw
            val code = try { proc.exitValue() } catch (e: Exception) { -1 }
            val body = if (out.isBlank()) "(tanpa output)" else if (out.length > 8000) out.take(8000) + "\n…(dipotong, sisa ${out.length - 8000} char)…" else out
            body + "\n[proot exit=" + code + "]"
        } catch (e: Exception) {
            "proot gagal jalan: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------- Ekstraktor tar.xz (GNU tar + pax ringkas) ----------------

    private fun extractTarXz(tarFile: File, destDir: File, onEntries: (Int) -> Unit): Int {
        val destCanon = destDir.canonicalPath
        var count = 0
        var pendingName: String? = null
        var pendingLink: String? = null
        val dirModes = mutableListOf<Pair<File, Int>>()
        BufferedInputStream(tarFile.inputStream()).use { fis ->
            XZInputStream(fis).use { xz ->
                val header = ByteArray(512)
                while (true) {
                    if (!readFully(xz, header)) break
                    if (header.all { it == 0.toByte() }) break
                    var name = cstr(header, 0, 100)
                    val mode = parseOctal(header, 100, 8)
                    val size = parseSize(header, 124, 12)
                    val type = header[156].toInt().toChar()
                    var link = cstr(header, 157, 100)
                    val magic = cstr(header, 257, 5)
                    if (magic.startsWith("ustar")) {
                        val prefix = cstr(header, 345, 155)
                        if (prefix.isNotBlank()) name = prefix + "/" + name
                    }
                    val dataBlocks = ((size + 511) / 512) * 512
                    when (type) {
                        'L' -> { pendingName = readString(xz, size, dataBlocks); }
                        'K' -> { pendingLink = readString(xz, size, dataBlocks); }
                        'x', 'g' -> {
                            val body = readString(xz, size, dataBlocks) ?: ""
                            if (type == 'x') {
                                for (rec in body.split('\n')) {
                                    val eq = rec.indexOf('=')
                                    if (eq > 0) {
                                        val sp = rec.indexOf(' ')
                                        if (sp in 0 until eq) {
                                            when (rec.substring(sp + 1, eq)) {
                                                "path" -> pendingName = rec.substring(eq + 1)
                                                "linkpath" -> pendingLink = rec.substring(eq + 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        '5' -> {
                            val rel = normRel(pendingName ?: name); pendingName = null; pendingLink = null
                            skipFully(xz, dataBlocks)
                            if (rel != null) {
                                val d = File(destDir, rel)
                                if (d.canonicalPath.startsWith(destCanon)) { d.mkdirs(); dirModes.add(d to mode) }
                            }
                        }
                        '2', '1' -> {
                            val rel = normRel(pendingName ?: name)
                            val target = pendingLink ?: link
                            pendingName = null; pendingLink = null
                            skipFully(xz, dataBlocks)
                            if (rel != null && target.isNotBlank()) {
                                val f = File(destDir, rel)
                                if (f.canonicalPath.startsWith(destCanon)) {
                                    f.parentFile?.mkdirs()
                                    try { if (f.exists() || isSymlink(f)) f.delete() } catch (e: Exception) {}
                                    try {
                                        if (type == '2') Os.symlink(target, f.absolutePath)
                                        else Os.link(File(destDir, normRel(target) ?: target).absolutePath, f.absolutePath)
                                        count++
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                        '0', '\u0000' -> {
                            val rel = normRel(pendingName ?: name); pendingName = null; pendingLink = null
                            if (rel == null) { skipFully(xz, dataBlocks); continue }
                            val f = File(destDir, rel)
                            if (!f.canonicalPath.startsWith(destCanon)) { skipFully(xz, dataBlocks); continue }
                            f.parentFile?.mkdirs()
                            FileOutputStream(f).use { out ->
                                var remaining = size
                                val buf = ByteArray(128 * 1024)
                                while (remaining > 0) {
                                    val want = minOf(buf.size.toLong(), remaining).toInt()
                                    val n = xz.read(buf, 0, want)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    remaining -= n
                                }
                            }
                            skipFully(xz, dataBlocks - size)
                            try { Os.chmod(f.absolutePath, if (mode == 0) 420 else mode) } catch (e: Exception) {}
                            count++
                            onEntries(count)
                        }
                        else -> { pendingName = null; pendingLink = null; skipFully(xz, dataBlocks) }
                    }
                }
            }
        }
        for ((d, m) in dirModes.asReversed()) {
            try { Os.chmod(d.absolutePath, if (m == 0) 493 else (m or 448)) } catch (e: Exception) {}
        }
        return count
    }

    private fun isSymlink(f: File): Boolean =
        try { android.system.Os.lstat(f.absolutePath).st_mode and 0xF000 == 0xA000 } catch (e: Exception) { false }

    private fun normRel(name: String): String? {
        var n = name.trim()
        if (n.startsWith("./")) n = n.substring(2)
        // Tarball proot-distro membungkus rootfs dalam satu folder teratas
        // (ubuntu-noble-aarch64/) — komponen itu dibuang; isinya yang jadi
        // akar rootfs. Tanpa ini bash mendarat di ubuntu/ubuntu-noble-…/usr
        // dan isReady() tidak pernah menemukannya (bug v4.24.0–v4.27.0).
        if (n == ROOTFS_WRAPPER || n == "$ROOTFS_WRAPPER/") return null
        if (n.startsWith("$ROOTFS_WRAPPER/")) n = n.substring(ROOTFS_WRAPPER.length + 1)
        if (n.isBlank() || n == ".") return null
        if (n.contains("..")) return null
        return n
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = off + len
        while (end < max && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8).trim()
    }

    private fun parseOctal(b: ByteArray, off: Int, len: Int): Int {
        val s = cstr(b, off, len).trim()
        if (s.isEmpty()) return 0
        return try { s.toInt(8) } catch (e: Exception) { 0 }
    }

    private fun parseSize(b: ByteArray, off: Int, len: Int): Long {
        if ((b[off].toInt() and 0x80) != 0) {
            var v = (b[off].toInt() and 0x7F).toLong()
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
        val s = cstr(b, off, len).trim()
        if (s.isEmpty()) return 0
        return try { s.toLong(8) } catch (e: Exception) { 0 }
    }

    private fun readFully(ins: InputStream, buf: ByteArray): Boolean {
        var got = 0
        while (got < buf.size) {
            val n = ins.read(buf, got, buf.size - got)
            if (n < 0) return got > 0
            got += n
        }
        return true
    }

    private fun skipFully(ins: InputStream, bytes: Long) {
        var left = bytes
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            left -= n
        }
    }

    private fun readString(ins: InputStream, size: Long, blocks: Long): String? {
        if (size <= 0 || size > 1048576) { skipFully(ins, blocks); return null }
        val data = ByteArray(size.toInt())
        var got = 0
        while (got < data.size) {
            val n = ins.read(data, got, data.size - got)
            if (n < 0) break
            got += n
        }
        skipFully(ins, blocks - size)
        return String(data, 0, got, Charsets.UTF_8).trimEnd('\u0000', '\n').let { if (it.endsWith("\n")) it.dropLast(1) else it }
    }
}
