package com.senzwh.deepharness

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Base64
import android.webkit.WebView
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * BrowserCdpClient — klien CDP (Chrome DevTools Protocol) in-process ke
 * WebView sendiri (vc313, spek SPEC-BROWSER-WEBVIEW-MAX.md §3.1).
 *
 * VARIAN KONEKSI YANG DIPAKAI: WebSocket LANGSUNG di atas LocalSocket
 * (TANPA jembatan/pompa TCP loopback). Alasan: WebSocket framing hanyalah
 * byte di atas sebuah stream — LocalSocket sudah menyediakan
 * InputStream/OutputStream seperti Socket TCP, jadi frame WS bisa ditulis
 * dan dibaca langsung di atas socket abstract yang sama dengan HTTP
 * /json/list. Pompa TCP loopback (pola So7o Webview: byte LocalSocket
 * dipompa ke port TCP lokal 9334) hanya dibutuhkan bila ada klien TCP
 * eksternal yang harus bicara ke socket itu; di sini kliennya in-process,
 * jadi pompa itu lapisan yang tidak perlu + satu port tambahan yang harus
 * dijaga. TIDAK ADA port TCP yang dibuka modul ini — satu-satunya socket
 * adalah abstract unix socket milik proses sendiri.
 *
 * Transport: LocalSocket -> abstract socket
 * "webview_devtools_remote_<pid>" (pid = Process.myPid()), lalu:
 *   1. HTTP "GET /json/list" di socket itu -> pilih target type=page ->
 *      webSocketDebuggerUrl.
 *   2. UPGRADE WebSocket di socket yang SAMA (Sec-WebSocket-Key acak,
 *      verifikasi Sec-WebSocket-Accept) -> kirim perintah CDP sebagai
 *      frame WS teks (Runtime.evaluate, Page.captureScreenshot,
 *      Network.enable, Log.enable).
 *
 * Siklus debugging DULU dilakukan Worker B di MainActivity via framework
 * langsung (nyala saat tab agen pertama dibuka, mati saat tab terakhir
 * ditutup). vc316 N9-A1: MainActivity TIDAK LAGI menyalakan debugging —
 * klien CDP ini tidak pernah dipakai (NOL pemanggil di vc313-vc315), jadi
 * debugging rilis hanya membuka permukaan inspeksi USB/ADB tanpa manfaat.
 * setDebuggingEnabled() di bawah tetap jadi saklar on/off BERPASANGAN
 * untuk pemakaian CDP resmi nanti; modul ini TIDAK PERNAH memanggilnya
 * otomatis. Tanpa debugging aktif tidak ada socket, dan connect()
 * mengembalikan false (fail-soft).
 *
 * Fail-soft: SEMUA fungsi publik mengembalikan null/false/empty saat gagal
 * dan TIDAK PERNAH melempar exception ke pemanggil. SEMUA I/O di thread
 * latar (thread pembaca) atau memakai latch/timeout maks 10 dtk
 * (pola evalSync MainActivity). TIDAK ADA daemon, TIDAK ADA toggle
 * permanen, TIDAK ADA server CDP yang bisa dijangkau aplikasi lain.
 */
object BrowserCdpClient {

    data class NetworkEntry(val url: String, val method: String, val status: Int, val sizeBytes: Long)

    private const val TIMEOUT_MS = 10_000
    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAX_NETWORK_ENTRIES = 500
    private const val MAX_LOG_ENTRIES = 200

    @Volatile private var debuggingEnabled = false
    @Volatile private var socket: LocalSocket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var wsUrl: String? = null

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, ArrayBlockingQueue<String>>()
    private val sendLock = Any()
    private val networkLock = Any()
    private val logLock = Any()
    private val networkList = ArrayList<NetworkEntry>()
    private val requestMethods = HashMap<String, Pair<String, String>>() // requestId -> (url, method)
    private val logList = ArrayList<String>()

    // ---- Debugging flag (dipanggil Worker B dari MainActivity) ----

    /** WebView.setWebContentsDebuggingEnabled(enabled). Fail-soft. */
    fun setDebuggingEnabled(enabled: Boolean) {
        try {
            WebView.setWebContentsDebuggingEnabled(enabled)
            debuggingEnabled = enabled
        } catch (e: Exception) {
            // Fail-soft: flag tidak berubah, connect() nanti gagal sendiri.
        }
    }

    fun isDebuggingEnabled(): Boolean = try {
        debuggingEnabled
    } catch (e: Exception) { false }

    // ---- Kunci evaluate() (vc316 N9-A3) ----

    /**
     * vc316 N9-A3 — KUNCI evaluate(): eval JS arbitrer TANPA gate.
     * Default TERKUNCI (false): evaluate() menolak tanpa menyentuh socket.
     * Hanya dibuka lewat unlockEvaluateVc316() — dan itu HANYA boleh
     * dipanggil dari JALUR CDP RESMI yang sudah disetujui (pemakaian CDP
     * Fase 2; belum ada di vc316). JANGAN dipanggil dari tool, worker,
     * atau jalur lain. connect() / captureScreenshotFull() TIDAK dikunci
     * (bukan eval — screenshot tidak bisa membaca kolom password atau
     * document.cookie; network/log capture juga tidak mengeksekusi JS).
     * Alasan kunci ini: evaluate() bisa membaca nilai kolom password
     * (typeJs PASSWORD_FIELD tidak berlaku di jalur CDP) + document.cookie
     * (sesi browser agen + semua situs login, cookie dibagi per proses).
     */
    @Volatile
    var evaluateUnlockedVc316: Boolean = false
        private set

    /** Buka kunci evaluate() — HANYA untuk jalur CDP resmi (lihat atas). */
    fun unlockEvaluateVc316() {
        evaluateUnlockedVc316 = true
    }

    // ---- Koneksi ----

    /**
     * LocalSocket -> GET /json/list -> pilih target type=page -> handshake
     * WebSocket di socket yang SAMA -> mulai thread pembaca.
     * false bila gagal di langkah mana pun (debugging mati / socket tidak
     * ada / tidak ada target page / handshake WS gagal).
     */
    fun connect(): Boolean {
        try {
            if (isConnected()) return true
            disconnect()
            val pid = android.os.Process.myPid()
            val sock = LocalSocket()
            sock.soTimeout = TIMEOUT_MS
            sock.connect(LocalSocketAddress("webview_devtools_remote_$pid", LocalSocketAddress.Namespace.ABSTRACT), TIMEOUT_MS)
            val inp = sock.inputStream
            val out = sock.outputStream
            // 1. Daftar target via HTTP di socket yang sama.
            val httpReq = "GET /json/list HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            out.write(httpReq.toByteArray(Charsets.UTF_8))
            out.flush()
            val body = readHttpBody(inp) ?: run { try { sock.close() } catch (e: Exception) {}; return false }
            val urls = parseWebSocketUrls(body)
            val target = urls.firstOrNull() ?: run { try { sock.close() } catch (e: Exception) {}; return false }
            // 2. Upgrade WebSocket di socket yang SAMA. Path diambil dari URL
            // ws://.../devtools/page/<id> (host/port diabaikan — transportnya
            // tetap LocalSocket ini, bukan TCP).
            val path = target.substringAfter("://").substringAfter("/", "")
            val wsPath = if (path.isEmpty()) "/" else "/$path"
            val keyBytes = ByteArray(16)
            SecureRandom().nextBytes(keyBytes)
            val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
            val upgrade = "GET $wsPath HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
            out.write(upgrade.toByteArray(Charsets.UTF_8))
            out.flush()
            val headers = readHttpHeaders(inp) ?: run { try { sock.close() } catch (e: Exception) {}; return false }
            if (!headers.startsWith("HTTP/1.1 101")) {
                try { sock.close() } catch (e: Exception) {}
                return false
            }
            val accept = headers.lines()
                .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
                ?.substringAfter(":", "")?.trim() ?: ""
            val expected = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )
            if (accept != expected) {
                try { sock.close() } catch (e: Exception) {}
                return false
            }
            socket = sock
            input = inp
            output = out
            wsUrl = target
            running = true
            val t = Thread({ readerLoop() }, "browser-cdp-reader")
            t.isDaemon = true
            t.start()
            readerThread = t
            return true
        } catch (e: Exception) {
            try { disconnect() } catch (ignored: Exception) {}
            return false
        }
    }

    /** Tutup socket + hentikan thread pembaca. Fail-soft. */
    fun disconnect() {
        running = false
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        input = null
        output = null
        wsUrl = null
        // Bangunkan semua penunggu eval/enable dengan null.
        val keys = pending.keys().toList()
        for (k in keys) pending.remove(k)?.offer("")
        try { readerThread?.join(500) } catch (e: Exception) {}
        readerThread = null
    }

    fun isConnected(): Boolean = try {
        val s = socket
        s != null && s.isConnected && running
    } catch (e: Exception) { false }

    // ---- Perintah CDP ----

    /**
     * Runtime.evaluate; kembalikan JSON hasil (teks JSON dari
     * result.result.value — string dikutip JSON, angka/bool/obj apa adanya)
     * atau null bila gagal/timeout. Timeout maks 10 dtk.
     *
     * vc316 N9-A3: TERKUNCI secara default — bila belum dibuka lewat
     * unlockEvaluateVc316() (jalur CDP resmi), tolak langsung dengan
     * null TANPA menyentuh socket.
     */
    fun evaluate(expression: String): String? {
        if (!evaluateUnlockedVc316) return null
        try {
            val id = nextId.getAndIncrement()
            val params = "{\"expression\":" + JSONObject.quote(expression) +
                ",\"returnByValue\":true,\"awaitPromise\":false}"
            val resp = sendAndWait(id, "Runtime.evaluate", params) ?: return null
            val obj = JSONObject(resp)
            if (obj.has("error")) return null
            val result = obj.optJSONObject("result") ?: return null
            val inner = result.optJSONObject("result") ?: return null
            if (inner.has("value")) {
                val v = inner.get("value")
                return if (v is String) JSONObject.quote(v) else v.toString()
            }
            if (inner.optString("subtype") == "null" || inner.optString("type") == "undefined") return "null"
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Page.captureScreenshot captureBeyondViewport=true; kembalikan PNG
     * satu halaman penuh, atau null bila tidak tersedia/gagal.
     */
    fun captureScreenshotFull(): ByteArray? {
        try {
            val id = nextId.getAndIncrement()
            val params = "{\"format\":\"png\",\"captureBeyondViewport\":true}"
            val resp = sendAndWait(id, "Page.captureScreenshot", params) ?: return null
            val obj = JSONObject(resp)
            if (obj.has("error")) return null
            val data = obj.optJSONObject("result")?.optString("data") ?: return null
            if (data.isBlank()) return null
            return Base64.decode(data, Base64.DEFAULT)
        } catch (e: Exception) {
            return null
        }
    }

    /** Network.enable — mulai sadap request. false bila gagal. */
    fun enableNetworkCapture(): Boolean {
        return try {
            val id = nextId.getAndIncrement()
            val resp = sendAndWait(id, "Network.enable", "{}") ?: return false
            !JSONObject(resp).has("error")
        } catch (e: Exception) { false }
    }

    /** Ringkasan request jaringan sejak enable (URL, metode, status, ukuran — tanpa isi body). */
    fun networkEntries(): List<NetworkEntry> {
        return try {
            synchronized(networkLock) { networkList.toList() }
        } catch (e: Exception) { emptyList() }
    }

    /** Log.enable — mulai tangkap log konsol halaman. false bila gagal. */
    fun enableLogCapture(): Boolean {
        return try {
            val id = nextId.getAndIncrement()
            val resp = sendAndWait(id, "Log.enable", "{}") ?: return false
            !JSONObject(resp).has("error")
        } catch (e: Exception) { false }
    }

    /** Log konsol halaman aktif (maks 200 baris terakhir). */
    fun logEntries(): List<String> {
        return try {
            synchronized(logLock) { logList.toList() }
        } catch (e: Exception) { emptyList() }
    }

    // ---- Mesin kirim/terima (internal) ----

    private fun sendAndWait(id: Int, method: String, paramsJson: String): String? {
        try {
            if (!isConnected()) return null
            val q = ArrayBlockingQueue<String>(1)
            pending[id] = q
            val msg = buildCdpMessage(id, method, paramsJson)
            synchronized(sendLock) {
                val out = output ?: run { pending.remove(id); return null }
                out.write(wsEncodeFrame(msg.toByteArray(Charsets.UTF_8)))
                out.flush()
            }
            val resp = q.poll(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            pending.remove(id)
            if (resp.isNullOrEmpty()) return null
            return resp
        } catch (e: Exception) {
            pending.remove(id)
            return null
        }
    }

    private fun readerLoop() {
        val frag = StringBuilder()
        try {
            while (running) {
                val inp = input ?: break
                val frame = readWsFrame(inp) ?: break
                val opcode = frame.first
                val payload = frame.second
                when (opcode) {
                    8 -> break // close
                    9 -> { // ping -> pong
                        try {
                            synchronized(sendLock) {
                                output?.write(wsEncodeFrame(payload, opcode = 10))
                                output?.flush()
                            }
                        } catch (e: Exception) {}
                    }
                    0 -> { // continuation
                        frag.append(String(payload, Charsets.UTF_8))
                    }
                    1 -> { // text (FIN diasumsikan; fragmentasi multi-frame diakumulasi)
                        if (frag.isNotEmpty()) {
                            frag.append(String(payload, Charsets.UTF_8))
                            handleMessage(frag.toString())
                            frag.setLength(0)
                        } else {
                            handleMessage(String(payload, Charsets.UTF_8))
                        }
                    }
                    else -> { /* binary / opcode lain: abaikan */ }
                }
            }
        } catch (e: Exception) {
            // Socket mati / disconnect — keluar fail-soft.
        } finally {
            if (running) {
                // Reader mati sendiri (mis. socket putus): tandai putus.
                try { disconnect() } catch (ignored: Exception) {}
            }
        }
    }

    private fun handleMessage(text: String) {
        try {
            val obj = JSONObject(text)
            if (obj.has("id")) {
                val id = obj.optInt("id", -1)
                pending[id]?.offer(text)
                return
            }
            when (obj.optString("method")) {
                "Network.requestWillBeSent" -> {
                    val p = obj.optJSONObject("params") ?: return
                    val req = p.optJSONObject("request") ?: return
                    val reqId = p.optString("requestId")
                    if (reqId.isBlank()) return
                    synchronized(networkLock) {
                        requestMethods[reqId] = req.optString("url") to req.optString("method")
                    }
                }
                "Network.responseReceived" -> {
                    val p = obj.optJSONObject("params") ?: return
                    val reqId = p.optString("requestId")
                    val resp = p.optJSONObject("response") ?: return
                    val url = resp.optString("url")
                    val status = resp.optInt("status", 0)
                    val size = resp.optLong("encodedDataLength", 0L)
                    val method = synchronized(networkLock) { requestMethods[reqId]?.second ?: "" }
                    if (url.isBlank()) return
                    synchronized(networkLock) {
                        networkList.add(NetworkEntry(url, method, status, size))
                        while (networkList.size > MAX_NETWORK_ENTRIES) networkList.removeAt(0)
                    }
                }
                "Log.entryAdded" -> {
                    val entry = obj.optJSONObject("params")?.optJSONObject("entry") ?: return
                    val line = "[" + entry.optString("level") + "] " + entry.optString("text")
                    synchronized(logLock) {
                        logList.add(line)
                        while (logList.size > MAX_LOG_ENTRIES) logList.removeAt(0)
                    }
                }
                else -> { /* event lain: abaikan */ }
            }
        } catch (e: Exception) {
            // Pesan bukan JSON / tak dikenal: abaikan.
        }
    }

    /** Baca satu frame WS dari stream (header + extended length + mask + payload). null bila EOF/rusak. */
    private fun readWsFrame(inp: InputStream): Pair<Int, ByteArray>? {
        val h = readExact(inp, 2) ?: return null
        val opcode = h[0].toInt() and 0x0F
        val masked = (h[1].toInt() and 0x80) != 0
        var len = (h[1].toInt() and 0x7F).toLong()
        if (len == 126L) {
            val ext = readExact(inp, 2) ?: return null
            len = ((ext[0].toLong() and 0xFF) shl 8) or (ext[1].toLong() and 0xFF)
        } else if (len == 127L) {
            val ext = readExact(inp, 8) ?: return null
            var v = 0L
            for (b in ext) v = (v shl 8) or (b.toLong() and 0xFF)
            len = v
        }
        if (len < 0 || len > 64L * 1024 * 1024) return null
        val mask: ByteArray? = if (masked) {
            readExact(inp, 4) ?: return null
        } else null
        val payload = readExact(inp, len.toInt()) ?: return null
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor (mask[i % 4].toInt() and 0xFF)).toByte()
        }
        return opcode to payload
    }

    private fun readExact(inp: InputStream, n: Int): ByteArray? {
        if (n == 0) return ByteArray(0)
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = try { inp.read(buf, off, n - off) } catch (e: Exception) { return null }
            if (r < 0) return null
            off += r
        }
        return buf
    }

    /** Baca header HTTP sampai "\r\n\r\n". null bila EOF/rusak. */
    private fun readHttpHeaders(inp: InputStream): String? {
        val sb = StringBuilder()
        var matched = 0 // jumlah char "\r\n\r\n" yang sudah cocok berurutan
        val target = "\r\n\r\n"
        while (sb.length < 65536) {
            val b = try { inp.read() } catch (e: Exception) { return null }
            if (b < 0) return null
            val c = b.toChar()
            sb.append(c)
            if (c == target[matched]) {
                matched++
                if (matched == 4) return sb.toString()
            } else {
                matched = if (c == target[0]) 1 else 0
            }
        }
        return null
    }

    /** Baca respons HTTP lengkap (header + body sesuai Content-Length). null bila gagal. */
    private fun readHttpBody(inp: InputStream): String? {
        val headers = readHttpHeaders(inp) ?: return null
        var length = 0
        for (line in headers.lines()) {
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                length = line.substringAfter(":", "").trim().toIntOrNull() ?: 0
            }
        }
        if (length <= 0) return ""
        val body = readExact(inp, length) ?: return null
        return String(body, Charsets.UTF_8)
    }

    // ---- Pure helpers (JVM-testable, TANPA kelas Android di signature) ----

    /**
     * Encode satu frame WebSocket teks dari klien: FIN + opcode teks (atau
     * opcode yang diminta), MASK wajib (klien->server), panjang 126/127
     * extended bila perlu, mask key acak.
     */
    fun wsEncodeFrame(payload: ByteArray, opcode: Int = 1): ByteArray {
        val maskKey = ByteArray(4)
        SecureRandom().nextBytes(maskKey)
        val headerLen = when {
            payload.size < 126 -> 2
            payload.size <= 0xFFFF -> 4
            else -> 10
        }
        val out = ByteArray(headerLen + 4 + payload.size)
        out[0] = (0x80 or (opcode and 0x0F)).toByte()
        when {
            payload.size < 126 -> out[1] = (0x80 or payload.size).toByte()
            payload.size <= 0xFFFF -> {
                out[1] = (0x80 or 126).toByte()
                out[2] = ((payload.size ushr 8) and 0xFF).toByte()
                out[3] = (payload.size and 0xFF).toByte()
            }
            else -> {
                out[1] = (0x80 or 127).toByte()
                val n = payload.size.toLong()
                for (i in 0 until 8) out[2 + i] = ((n ushr ((7 - i) * 8)) and 0xFF).toByte()
            }
        }
        val maskPos = headerLen
        for (i in 0 until 4) out[maskPos + i] = maskKey[i]
        for (i in payload.indices) {
            out[maskPos + 4 + i] = (payload[i].toInt() xor (maskKey[i % 4].toInt() and 0xFF)).toByte()
        }
        return out
    }

    /**
     * Decode SATU frame WebSocket lengkap (header + payload) dari byte;
     * kembalikan payload (di-unmask bila bermask), atau null bila byte
     * tidak lengkap / rusak. Frame lanjutan di byte yang sama diabaikan
     * (pemanggil memakai readWsFrame untuk stream).
     */
    fun wsDecodeFrame(bytes: ByteArray): ByteArray? {
        try {
            if (bytes.size < 2) return null
            val masked = (bytes[1].toInt() and 0x80) != 0
            var len = (bytes[1].toInt() and 0x7F).toLong()
            var pos = 2
            if (len == 126L) {
                if (bytes.size < 4) return null
                len = ((bytes[2].toLong() and 0xFF) shl 8) or (bytes[3].toLong() and 0xFF)
                pos = 4
            } else if (len == 127L) {
                if (bytes.size < 10) return null
                var v = 0L
                for (i in 0 until 8) v = (v shl 8) or (bytes[2 + i].toLong() and 0xFF)
                len = v
                pos = 10
            }
            if (len < 0 || len > Int.MAX_VALUE) return null
            val mask = if (masked) {
                if (bytes.size < pos + 4) return null
                bytes.copyOfRange(pos, pos + 4).also { pos += 4 }
            } else null
            if (bytes.size < pos + len) return null
            val payload = bytes.copyOfRange(pos, pos + len.toInt())
            if (mask != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor (mask[i % 4].toInt() and 0xFF)).toByte()
            }
            return payload
        } catch (e: Exception) {
            return null
        }
    }

    /** Bangun pesan CDP JSON: {"id":N,"method":"...","params":{...}}. */
    fun buildCdpMessage(id: Int, method: String, paramsJson: String): String {
        val params = if (paramsJson.isBlank()) "{}" else paramsJson.trim()
        return "{\"id\":" + id + ",\"method\":" + JSONObject.quote(method) +
            ",\"params\":" + params + "}"
    }

    /**
     * Parse body /json/list: kembalikan webSocketDebuggerUrl dari target
     * yang type-nya "page" saja (target non-page di-skip). Rusak -> empty.
     */
    fun parseWebSocketUrls(jsonList: String): List<String> {
        return try {
            val arr = JSONArray(jsonList)
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") != "page") continue
                val u = o.optString("webSocketDebuggerUrl")
                if (u.isNotBlank()) out.add(u)
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
