package com.senzwh.deepharness

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** vc258 Fase 2 — logika MURNI endpoint worker :4198 (tanpa import Android).
 *  Dipakai WorkerEndpoint (server socket) + diuji kotlinc di JVM.
 *  Kontrak: BRIEF-DEVICE-SERVER.md §4 Fase 2 + §3.1/§3.4/§3.7.
 *  Auth: token Bearer, banding waktu-konstan (tolak timing leak).
 *  Routing: parse method + path mentah -> aksi + parameter.
 *  Cap: baca hasil endpoint maks READ_MAX_CHARS; preview default
 *  PREVIEW_DEFAULT_CHARS, maks PREVIEW_MAX_CHARS (WorkerLogic).
 *
 *  Slice 9 (vc325): tiap fun publik mencoba seam Rust dulu
 *  (`WorkerEndpointSeam`, modul `worker_endpoint`), bila seam
 *  tak-tersedia/gagal -> implementasi Kotlin lama (`*Kotlin`, body
 *  ASLI vc316 yang diganti nama). API publik TETAP PERSIS — pemakai
 *  (`WorkerEndpoint`, `WorkerQueue`) tidak berubah. */
object WorkerEndpointLogic {
    const val PORT = 4198
    const val MAX_BODY_BYTES = 1024 * 1024

    /** Banding token waktu-konstan. Kosong -> tolak. */
    fun isAuthorized(provided: String?, expected: String): Boolean {
        WorkerEndpointSeam.isAuthorized(provided, expected)?.let { return it }
        return isAuthorizedKotlin(provided, expected)
    }

    private fun isAuthorizedKotlin(provided: String?, expected: String): Boolean {
        if (provided.isNullOrBlank() || expected.isBlank()) return false
        val p = provided.trim().toByteArray(Charsets.UTF_8)
        val e = expected.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(p, e)
    }

    /** Ambil token dari header Authorization "Bearer <token>". */
    fun bearerToken(authHeader: String?): String? {
        // Seam mengembalikan null baik bila token tidak ada maupun
        // bila seam gagal — keduanya dijawab fallback Kotlin yang
        // menghitung hasil yang sama.
        val viaRust = if (authHeader != null) WorkerEndpointSeam.bearerToken(authHeader) else null
        if (viaRust != null) return viaRust
        if (authHeader == null) return bearerTokenKotlin(null)
        // Bila seam hidup tapi token memang tidak ada, fallback Kotlin
        // mengembalikan null yang sama; bila seam mati, fallback yang bekerja.
        return bearerTokenKotlin(authHeader)
    }

    private fun bearerTokenKotlin(authHeader: String?): String? {
        if (authHeader.isNullOrBlank()) return null
        val h = authHeader.trim()
        if (!h.startsWith("Bearer ")) return null
        return h.removePrefix("Bearer ").trim().takeIf { it.isNotEmpty() }
    }

    fun previewChars(requested: Int): Int {
        WorkerEndpointSeam.previewChars(requested)?.let { return it }
        return previewCharsKotlin(requested)
    }

    private fun previewCharsKotlin(requested: Int): Int = WorkerLogic.previewChars(requested)

    /** Potong teks ke maks char; kembalikan (potongan, terpotong?). */
    fun slicePreview(text: String, max: Int): Pair<String, Boolean> {
        WorkerEndpointSeam.slicePreview(text, max)?.let { return Pair(it.slice, it.truncated) }
        return slicePreviewKotlin(text, max)
    }

    private fun slicePreviewKotlin(text: String, max: Int): Pair<String, Boolean> =
        WorkerLogic.slicePreview(text, max)

    /** Nama file tugas/hasil aman: tanpa "/" , ".." , backslash. */
    fun isSafeName(name: String): Boolean {
        WorkerEndpointSeam.isSafeName(name)?.let { return it }
        return isSafeNameKotlin(name)
    }

    private fun isSafeNameKotlin(name: String): Boolean =
        name.isNotBlank() && !name.contains("/") && !name.contains("..") && !name.contains("\\")

    fun parseQuery(rawPath: String): Map<String, String> {
        WorkerEndpointSeam.parseQuery(rawPath)?.let { return it }
        return parseQueryKotlin(rawPath)
    }

    private fun parseQueryKotlin(rawPath: String): Map<String, String> {
        val q = rawPath.substringAfter("?", "")
        if (q.isBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (part in q.split("&")) {
            val kv = part.split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) {
                try { out[kv[0]] = java.net.URLDecoder.decode(kv[1], "UTF-8") } catch (e: Exception) { out[kv[0]] = kv[1] }
            }
        }
        return out
    }

    data class Route(val action: String, val workerId: String = "", val name: String = "")

    /** Parse method + path (boleh bawa query) -> Route, atau null bila
     *  tidak dikenal. Segmen path di-URL-decode. Nama file tidak aman
     *  tetap di-parse di sini (penolakan di dispatcher, supaya pesan
     *  errornya jelas 400, bukan 404). */
    fun parseRoute(method: String, rawPath: String): Route? {
        WorkerEndpointSeam.parseRoute(method, rawPath)?.let { return it.route }
        return parseRouteKotlin(method, rawPath)
    }

    private fun parseRouteKotlin(method: String, rawPath: String): Route? {
        val path = rawPath.substringBefore("?").trim()
        val seg = path.split("/").filter { it.isNotEmpty() }.map {
            try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
        }
        val m = method.uppercase()
        if (seg.isEmpty()) return null
        if (seg.size == 1 && seg[0] == "health" && m == "GET") return Route("health")
        if (seg.size == 1 && seg[0] == "workers" && m == "GET") return Route("listWorkers")
        if (seg.size == 2 && seg[0] == "workers" && seg[1] == "spawn" && m == "POST") return Route("spawnWorker")
        if (seg.size == 2 && seg[0] == "workers") {
            return when (m) {
                "GET" -> Route("workerDetail", workerId = seg[1])
                else -> null
            }
        }
        if (seg.size == 3 && seg[0] == "workers") {
            return when {
                seg[2] == "stop" && m == "POST" -> Route("stopWorker", workerId = seg[1])
                seg[2] == "restart" && m == "POST" -> Route("restartWorker", workerId = seg[1])
                seg[2] == "quota" && m == "POST" -> Route("setQuota", workerId = seg[1])
                seg[2] == "reset" && m == "POST" -> Route("resetWorker", workerId = seg[1])
                seg[2] == "verify-task" && m == "POST" -> Route("verifyTask", workerId = seg[1])
                seg[2] == "log" && m == "GET" -> Route("workerLog", workerId = seg[1])
                seg[2] == "tasks" && m == "GET" -> Route("listTasks", workerId = seg[1])
                seg[2] == "tasks" && m == "POST" -> Route("enqueueTask", workerId = seg[1])
                seg[2] == "results" && m == "GET" -> Route("listResults", workerId = seg[1])
                else -> null
            }
        }
        if (seg.size == 4 && seg[0] == "workers" && seg[2] == "tasks" && m == "GET")
            return Route("readTask", workerId = seg[1], name = seg[3])
        if (seg.size == 4 && seg[0] == "workers" && seg[2] == "results" && m == "GET")
            return Route("readResult", workerId = seg[1], name = seg[3])
        if (seg.size == 5 && seg[0] == "workers" && seg[2] == "tasks" && seg[4] == "result" && m == "POST")
            return Route("writeTaskResult", workerId = seg[1], name = seg[3])
        if (seg.isNotEmpty() && seg[0] == "queue" && seg.size >= 2 && seg[1] == "jhon") {
            if (seg.size == 3 && seg[2] == "tasks") {
                return when (m) {
                    "GET" -> Route("jhonListTasks")
                    "POST" -> Route("jhonEnqueue")
                    else -> null
                }
            }
            if (seg.size == 3 && seg[2] == "results" && m == "GET") return Route("jhonListResults")
            if (seg.size == 4 && seg[2] == "tasks" && m == "GET") return Route("jhonReadTask", name = seg[3])
            if (seg.size == 5 && seg[2] == "tasks" && seg[4] == "result" && m == "POST") return Route("jhonWriteResult", name = seg[3])
        }
        return null
    }

    // vc261 N-m2: path cocok dengan rute yang dikenal tapi method-nya
    // salah (mis. DELETE /workers) -> pemanggil menjawab 405, bukan 404.
    fun pathMatchesOtherMethod(method: String, rawPath: String): Boolean {
        WorkerEndpointSeam.pathMatchesOtherMethod(method, rawPath)?.let { return it }
        return pathMatchesOtherMethodKotlin(method, rawPath)
    }

    private fun pathMatchesOtherMethodKotlin(method: String, rawPath: String): Boolean {
        if (parseRoute(method, rawPath) != null) return false
        return parseRoute("GET", rawPath) != null || parseRoute("POST", rawPath) != null
    }

    // vc261 N-M4: aksi yang BOLEH diakses dengan token per-worker (hanya
    // rute ber-workerId itu sendiri). listWorkers/spawn/jhon*/setQuota
    // dan health HANYA token master. vc281: resetWorker HANYA token
    // master (destruktif). vc284 N4-M2: verifyTask BOLEH token per-worker
    // — worker memverifikasi tugasnya lewat endpoint (kunci HMAC tinggal
    // app-side); verifikasi TIDAK memberi worker apa pun selain jawaban
    // VALID/TIDAK VALID atas satu file tugas miliknya sendiri.
    val WORKER_SCOPED_ACTIONS = setOf("workerDetail", "stopWorker", "restartWorker", "workerLog", "listTasks", "enqueueTask", "readTask", "writeTaskResult", "listResults", "readResult", "verifyTask")

    // vc261 N-M5: HMAC-SHA256 file antrean. Kunci = queue key SecureStore
    // (WorkerEndpoint.queueKey) — sejak vc284 (N4-M2) kunci TIDAK PERNAH
    // keluar dari app: worker memverifikasi tugas lewat rute verifyTask
    // (kunci dibaca app-side), bukan lewat verifier di proot. Hasil yang
    // ditulis agen lewat endpoint ditandatangani app-side dengan kunci
    // ini; hasil yang ditulis worker langsung ke cermin TIDAK bertanda
    // tangan. Format: baris pertama "HMAC-SHA256:<hex>" lalu isi asli.
    // verifyQueueText kembalikan isi bila tanda tangan valid, null bila
    // tidak ada/tidak valid (pemanggil membedakan lewat
    // hasQueueSignature: tanpa tanda tangan = diterima-apa-adanya +
    // dilabeli TIDAK TEPERCAYA di preview; tanda tangan TIDAK VALID =
    // DITOLAK di collectFromMirror).
    fun hmacSha256Hex(key: String, text: String): String {
        WorkerEndpointSeam.hmacSha256Hex(key, text)?.let { return it }
        return hmacSha256HexKotlin(key, text)
    }

    private fun hmacSha256HexKotlin(key: String, text: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val out = mac.doFinal(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in out) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    fun signQueueText(key: String, text: String): String {
        WorkerEndpointSeam.signQueueText(key, text)?.let { return it }
        return signQueueTextKotlin(key, text)
    }

    private fun signQueueTextKotlin(key: String, text: String): String =
        "HMAC-SHA256:" + hmacSha256Hex(key, text) + "\n" + text

    fun hasQueueSignature(text: String): Boolean {
        WorkerEndpointSeam.hasQueueSignature(text)?.let { return it }
        return hasQueueSignatureKotlin(text)
    }

    private fun hasQueueSignatureKotlin(text: String): Boolean = text.startsWith("HMAC-SHA256:")

    fun verifyQueueText(key: String, signed: String): String? {
        // Seam mengembalikan null baik bila tanda tangan tidak valid
        // maupun bila seam gagal — keduanya dijawab fallback Kotlin
        // yang menghitung hasil yang sama.
        val viaRust = WorkerEndpointSeam.verifyQueueText(key, signed)
        if (viaRust != null) return viaRust
        return verifyQueueTextKotlin(key, signed)
    }

    private fun verifyQueueTextKotlin(key: String, signed: String): String? {
        if (!hasQueueSignature(signed)) return null
        val nl = signed.indexOf("\n")
        if (nl < 0) return null
        val sig = signed.substring("HMAC-SHA256:".length, nl).trim()
        val body = signed.substring(nl + 1)
        if (sig.isBlank()) return null
        val expected = hmacSha256Hex(key, body)
        return if (MessageDigest.isEqual(sig.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))) body else null
    }
}
