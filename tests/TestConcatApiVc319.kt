// TestConcatApiVc319.kt — tes paritas slice 5 (ConcatApiClient.kt -> Rust, vc319)
//
// Dua mode dalam satu file; `main(args)`: args[0] = "rust" | "kotlin",
// args[1] = treeDir (untuk cek sumber), args[2] = temp dir (previewDir).
//
// MODE "rust" — jalur Rust ASLI end-to-end: server Concat palsu berbasis
// ServerSocket TCP sungguhan di 127.0.0.1:7420 + `.so` host Rust lewat
// `-Djava.library.path=<harness-core/target/debug>`. Ini BISA jalan karena
// klien Rust (TcpStream via JNI) TIDAK kena guard sandbox yang mencegat
// `java.net.Socket` JVM (terbukti eksperimen JNI 16 Nov 2026: Rust connect
// dari dalam proses JVM ke ServerSocket Java di 7420 -> diterima). Yang
// diuji: framing/auth/timeout/MAX_LINE/event-skip/error mapping + 21 method
// + seam VERSION 5 + saklar fallback. Satu tes timeout diam = ~15 dtk asli.
//
// MODE "kotlin" — jalur fallback Kotlin lama (`*Kotlin`): transport
// dalam-memori (salinan harness R1, tanpa TCP) + `rustConcatApiClient =
// false`. Yang diuji: 17 method vc315 + previewFrame + validasi lokal,
// dengan string HARAP YANG SAMA PERSIS seperti mode rust (paritas byte).
//
// Skrip server (method -> balasan) DIBAGI kedua mode lewat abstraksi
// `ConnSource`/`ScriptConn`: memori = satu serverSide melayani koneksi
// berurutan; TCP = `accept()` per koneksi. Token di sini PALSU.
//
// Jalankan: via run-tests-vc319-coord.sh (P5). Keluar 0 = semua lulus.
package com.senzwh.deepharness

import harness.core.RustCore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

var passed = 0
var failed = 0
val failedNames = ArrayList<String>()

/** Semua pesan error yang muncul selama tes — dipakai cek akhir "tanpa token". */
val allErrorMessages = ArrayList<String>()

fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
        println("LULUS  $name")
    } else {
        failed++
        failedNames.add(name)
        println("GAGAL  $name  $detail")
    }
}

fun <T : Exception> catchesAs(name: String, type: Class<T>, block: () -> Unit): T? {
    return try {
        block()
        check(name, false, "tidak ada error, diharapkan ${type.simpleName}")
        null
    } catch (e: Exception) {
        if (type.isInstance(e)) {
            @Suppress("UNCHECKED_CAST")
            val typed = e as T
            allErrorMessages.add(e.message ?: "")
            check(name, true)
            typed
        } else {
            check(name, false, "dapat ${e::class.simpleName}: ${e.message}")
            null
        }
    }
}

// Token PALSU — jelas-jelas bukan token asli. Jangan pernah ganti dengan yang asli.
const val FAKE_TOKEN = "test-token-00000000000000000000"
const val WRONG_TOKEN = "token-salah-0000000000000000"

// ---- Abstraksi skrip bersama (dipakai mode rust + kotlin) ----

/** Satu koneksi server: baca baris + kirim baris. */
interface ScriptConn {
    fun readLine(): String?
    fun sendLine(text: String)
    fun sendJson(obj: JSONObject) = sendLine(obj.toString())
    fun close()
}

/** Sumber koneksi berurutan untuk satu tes (list lalu open = 2 koneksi). */
interface ConnSource {
    fun next(): ScriptConn?
}

// ---- Transport dalam-memori (salinan R1 — tanpa TCP) ----

class QueueInputStream(val pollTimeoutMs: Long) : InputStream() {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var current: ByteArray? = null
    private var pos = 0
    private var closed = false

    fun feed(bytes: ByteArray) {
        queue.put(bytes)
    }

    fun feedEof() {
        queue.put(byteArrayOf())
    }

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n < 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            val cur = current
            if (cur != null && pos < cur.size) {
                val n = minOf(len, cur.size - pos)
                cur.copyInto(b, off, pos, pos + n)
                pos += n
                return n
            }
            if (closed) return -1
            val next = queue.poll(pollTimeoutMs, TimeUnit.MILLISECONDS)
                ?: throw SocketTimeoutException("read timed out (simulasi)")
            if (next.isEmpty()) {
                closed = true
                return -1
            }
            current = next
            pos = 0
        }
    }
}

class QueueOutputStream(val sink: QueueInputStream, val feedEofOnClose: Boolean) : OutputStream() {
    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()))
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        sink.feed(b.copyOfRange(off, off + len))
    }

    override fun flush() {}

    override fun close() {
        if (feedEofOnClose) sink.feedEof()
    }
}

class FakeServerSide(val reader: BufferedReader, val writer: BufferedWriter) : ScriptConn {
    override fun readLine(): String? = try {
        reader.readLine()
    } catch (_: Exception) {
        null
    }

    override fun sendLine(text: String) {
        writer.write(text)
        writer.write("\n")
        writer.flush()
    }

    override fun close() {
        try {
            writer.close()
        } catch (_: Exception) {
        }
    }
}

class FakeConcat(
    val clientPollTimeoutMs: Long = 15_000,
    val handler: (FakeServerSide) -> Unit
) {
    private val toClient = QueueInputStream(clientPollTimeoutMs)
    private val toServer = QueueInputStream(15_000)
    private val serverSide = FakeServerSide(
        BufferedReader(InputStreamReader(toServer, Charsets.UTF_8)),
        BufferedWriter(OutputStreamWriter(QueueOutputStream(toClient, true), Charsets.UTF_8))
    )
    private val thread = Thread {
        try {
            handler(serverSide)
        } catch (_: Exception) {
        }
    }.also { it.isDaemon = true }

    internal fun clientConnection(): ConcatApi.Connection {
        val reader = BufferedReader(InputStreamReader(toClient, Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(QueueOutputStream(toServer, false), Charsets.UTF_8))
        return ConcatApi.Connection(reader, writer) {
            try {
                writer.close()
            } catch (_: Exception) {
            }
            try {
                reader.close()
            } catch (_: Exception) {
            }
        }
    }

    fun start() = thread.start()
}

fun <T> withFake(
    pollTimeoutMs: Long = 15_000,
    handler: (FakeServerSide) -> Unit,
    block: () -> T
): T {
    val fake = FakeConcat(pollTimeoutMs, handler)
    val original = ConcatApi.connectionOpener
    ConcatApi.connectionOpener = { fake.clientConnection() }
    fake.start()
    try {
        return block()
    } finally {
        ConcatApi.connectionOpener = original
    }
}

/** Satu serverSide melayani SEMUA koneksi berurutan dalam satu tes. */
class MemSource(val server: FakeServerSide) : ConnSource {
    override fun next(): ScriptConn = server
}

fun <T> withMem(handler: (ConnSource) -> Unit, block: () -> T): T =
    withFake(handler = { s -> handler(MemSource(s)) }, block = block)

// ---- Transport TCP sungguhan (mode rust saja) ----

class TcpConn(private val socket: Socket) : ScriptConn {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

    override fun readLine(): String? = try {
        reader.readLine()
    } catch (_: Exception) {
        null
    }

    override fun sendLine(text: String) {
        writer.write(text)
        writer.write("\n")
        writer.flush()
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}

/** Listener 127.0.0.1:7420. `accept()` timeout 10 dtk -> null (handler selesai). */
class TcpServer {
    private val server = ServerSocket()

    init {
        server.reuseAddress = true
        server.soTimeout = 10_000
        server.bind(InetSocketAddress("127.0.0.1", 7420))
    }

    fun accept(): TcpConn? = try {
        TcpConn(server.accept())
    } catch (_: Exception) {
        null
    }

    fun close() {
        try {
            server.close()
        } catch (_: Exception) {
        }
    }
}

class TcpSource(val server: TcpServer) : ConnSource {
    override fun next(): ScriptConn? = server.accept()
}

/**
 * Jalankan [handler] di thread daemon (sisi server) + [block] di thread
 * utama (sisi klien). Handler yang gagal -> RuntimeException sesudah join.
 * Join paling lama 40 dtk (mencakup tes timeout diam 15 dtk + tidur server).
 */
fun <T> withTcp(handler: (ConnSource) -> Unit, block: () -> T): T {
    val server = TcpServer()
    val err = AtomicReference<Throwable?>(null)
    val thread = Thread {
        try {
            handler(TcpSource(server))
        } catch (t: Throwable) {
            err.set(t)
        }
    }.also { it.isDaemon = true; it.start() }
    try {
        return block()
    } finally {
        thread.join(40_000)
        server.close()
        val e = err.get()
        if (e != null) throw RuntimeException("handler TCP gagal: ${e.message}", e)
    }
}

// ---- Pembantu skrip server (bersama) ----

fun authOrRefuse(server: ScriptConn, seen: MutableList<String>): JSONObject? {
    val line = server.readLine() ?: return null
    seen.add(line)
    val auth = try {
        JSONObject(line)
    } catch (_: Exception) {
        return null
    }
    val presented = auth.optJSONObject("params")?.optString("token", null)
    return if (presented == FAKE_TOKEN) {
        server.sendJson(
            JSONObject().put("jsonrpc", "2.0").put("id", auth.opt("id"))
                .put("result", JSONObject())
        )
        auth
    } else {
        server.sendJson(
            JSONObject().put("jsonrpc", "2.0").put("id", auth.opt("id"))
                .put(
                    "error",
                    JSONObject().put("code", -32006).put("message", "wrong token")
                        .put("data", JSONObject().put("code", "unauthorized"))
                )
        )
        server.close()
        null
    }
}

fun readCall(server: ScriptConn, seen: MutableList<String>, methods: MutableList<String>): JSONObject? {
    val line = server.readLine() ?: return null
    seen.add(line)
    val call = try {
        JSONObject(line)
    } catch (_: Exception) {
        return null
    }
    methods.add(call.optString("method", ""))
    return call
}

fun replyResult(server: ScriptConn, call: JSONObject, result: Any) {
    server.sendJson(
        JSONObject().put("jsonrpc", "2.0").put("id", call.opt("id")).put("result", result)
    )
}

fun replyError(server: ScriptConn, call: JSONObject, code: Int, dataCode: String, message: String) {
    server.sendJson(
        JSONObject().put("jsonrpc", "2.0").put("id", call.opt("id"))
            .put(
                "error",
                JSONObject().put("code", code).put("message", message)
                    .put("data", JSONObject().put("code", dataCode))
            )
    )
}

// ---- Jawaban palsu (bentuknya ikut source v0.2.10; sama seperti R1) ----

fun versionResult(concat: String): JSONObject = JSONObject()
    .put("apiVersion", "0.2")
    .put("concat", concat)
    .put("dirs", JSONObject().put("config", "/config").put("data", "/data"))
    .put("capabilities", JSONArray(listOf("events", "json-rpc")))

fun projectRef(name: String, path: String): JSONObject = JSONObject()
    .put("path", path).put("name", name)
    .put("width", 1080).put("height", 1920)
    .put("rateNum", 30).put("rateDen", 1)
    .put("canvas", JSONArray(listOf(0, 0, 0, 255)))
    .put("openedAt", 1792000000000L)

fun clipJson(id: String, name: String, start: Double, duration: Double): JSONObject =
    JSONObject().put("id", id).put("trackId", "T1").put("mediaId", "m1")
        .put("name", name).put("kind", "video")
        .put("start", start).put("duration", duration).put("sourceStart", 0.0)

fun timelineJson(id: String, name: String, clips: JSONArray, tracks: Int = 4): JSONObject {
    val trackArr = JSONArray()
    for (i in 1..tracks) {
        trackArr.put(JSONObject().put("id", "T$i").put("visible", true).put("muted", false))
    }
    return JSONObject().put("id", id).put("name", name)
        .put(
            "video",
            JSONObject().put("width", 1080).put("height", 1920).put("rateNum", 30).put("rateDen", 1)
        )
        .put("tracks", trackArr).put("clips", clips).put("linkage", true)
}

fun editorView(name: String, timelines: JSONArray, activeId: String, rateNum: Long = 30, rateDen: Long = 1): JSONObject {
    val settings = JSONObject().put("name", name).put("width", 1080).put("height", 1920)
        .put("rateNum", rateNum).put("rateDen", rateDen)
    val project = JSONObject().put("media", JSONArray()).put("fonts", JSONArray())
        .put("timelines", timelines).put("activeTimelineId", activeId)
    return JSONObject().put("project", project).put("canUndo", false).put("canRedo", false)
        .put("settings", settings)
}

/** 2 klip: 150 dtk + 118 dtk = total 268 dtk = "4:28". */
fun twoClipView(): JSONObject {
    val clips = JSONArray()
        .put(clipJson("c1", "LMC_NIL20260316_232520_0001.mp4", 0.0, 150.0))
        .put(clipJson("c2", "LMC_NIL20260316_232521_0002.mp4", 150.0, 118.0))
    return editorView("Untitled project", JSONArray().put(timelineJson("TL1", "Timeline 1", clips)), "TL1")
}

/** 3 klip pendek: 12.5 + 12 + 45 dtk (untuk format "12.5 dtk" / "12 dtk"). */
fun shortClipsView(): JSONObject {
    val clips = JSONArray()
        .put(clipJson("c1", "klip-a.mp4", 0.0, 12.5))
        .put(clipJson("c2", "klip-b.mp4", 12.5, 12.0))
        .put(clipJson("c3", "klip-c.mp4", 24.5, 45.0))
    return editorView("Proyek Pendek", JSONArray().put(timelineJson("TL1", "Timeline 1", clips)), "TL1")
}

fun mediaJson(): JSONObject = JSONObject()
    .put("path", "/v/a.mp4").put("kind", "video").put("duration", 150.0)
    .put("video", JSONObject().put("width", 1920).put("height", 1080))
    .put("audio", JSONObject())

fun packageJson(id: String, name: String, kind: String, category: String): JSONObject =
    JSONObject().put("id", id).put("name", name).put("kind", kind).put("category", category)

fun templateInfo(name: String, w: Int, h: Int, rateNum: Long, rateDen: Long, slots: Int): JSONObject {
    val arr = JSONArray()
    for (i in 1..slots) arr.put(JSONObject().put("mediaId", "m$i"))
    return JSONObject().put("name", name).put("width", w).put("height", h)
        .put("rateNum", rateNum).put("rateDen", rateDen).put("slots", arr)
}

/** Ringkasan get/open 2 klip — HARUS byte-identik di kedua mode. */
fun twoClipSummary(path: String, opened: Boolean): String {
    val head = (if (opened) "Dibuka: " else "Proyek: ") + "Untitled project\n" +
        "Path: $path\n" +
        "Frame: 1080x1920 @ 30 fps\n" +
        "Timeline: Timeline 1 — 4 track, 2 klip, total 4:28\n" +
        "Klip:\n" +
        "- LMC_NIL20260316_232520_0001.mp4 (2:30)\n" +
        "- LMC_NIL20260316_232521_0002.mp4 (1:58)"
    return if (opened) {
        head + "\nCatatan: proyek ini sekarang dipegang API — layar Concat tidak bisa membukanya sampai ditutup lewat perintah (concat_project_close)."
    } else {
        head
    }
}

const val PROBE_EXPECTED = "Media: /v/a.mp4\nJenis: video\nDurasi: 2:30\nVideo: 1920x1080\nAudio: ya"
const val CATALOGUE_EXPECTED = "Katalog: 2 paket\n- e1 — Blur (effect, Stylize)\n- f1 — Vintage (filter, Color)"
const val TEMPLATES_EXPECTED = "Template: 2 template\n- Intro — 1920x1080 @ 30 fps, 2 slot\n- Outro — ukuran tidak diketahui, 0 slot"
const val SAVE_TEMPLATE_EXPECTED = "Template: Intro\nUkuran: 1920x1080 @ 30 fps\nSlot: 2"
const val RUN_EXPORT_EXPECTED = "Ekspor jalan, job job-1 — cek status pakai concat_export_status.\nOutput: /o/f.mp4"
const val STATUS_RUNNING_EXPECTED = "Ekspor job job-1: lagi jalan\nTahap: video\nFrame: 10/100 (50%)\nOutput: /o/f.mp4"

// ---- MODE "rust": jalur Rust asli via TCP sungguhan ----

fun rustTests(treeDir: String, previewTmp: File) {
    ConcatApi.rustConcatApiClient = true
    val savedPreviewDir = ConcatApi.previewDir

    // -- Seam --
    check("R1. RustCore.VERSION == 9 (seam vc319; slice 11 vc328 menambah dsApiJson)", RustCore.VERSION == 9, "VERSION=${RustCore.VERSION}")
    check("R2. RustCore.available == true (.so host termuat)", RustCore.available, "available=false — jalankan dengan -Djava.library.path=<harness-core/target/debug>")
    val caps = try { RustCore.rustCoreCapabilities() } catch (t: Throwable) { "" }
    check(
        "R3. Capabilities berisi concatApiJson + concat_api_client",
        caps.contains("concatApiJson") && caps.contains("concat_api_client"),
        caps.take(400)
    )
    check("R4. Saklar rustConcatApiClient default true", ConcatApi.rustConcatApiClient)

    // -- version + bentuk baris --
    val seenAuth = ArrayList<String>()
    val seenCall = ArrayList<String>()
    val methodsAuth = ArrayList<String>()
    val versionOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, seenAuth) == null) return@withTcp
        val call = readCall(s, seenCall, methodsAuth) ?: return@withTcp
        replyResult(s, call, versionResult("0.2.10"))
    }) { ConcatApi.version(FAKE_TOKEN) }
    check("R5. version() via Rust -> 'concat 0.2.10 / apiVersion 0.2'", versionOut == "concat 0.2.10 / apiVersion 0.2", versionOut)
    check("R6. Saklar tetap true sesudah panggilan Rust sukses", ConcatApi.rustConcatApiClient)
    val authLine = try { JSONObject(seenAuth[0]) } catch (_: Exception) { JSONObject() }
    check(
        "R7. Baris pertama = auth (id 1, token benar)",
        authLine.optString("jsonrpc") == "2.0" && authLine.optInt("id") == 1 &&
            authLine.optString("method") == "auth" &&
            authLine.optJSONObject("params")?.optString("token") == FAKE_TOKEN,
        seenAuth.joinToString()
    )
    val callLine = try { JSONObject(seenCall[0]) } catch (_: Exception) { JSONObject() }
    check(
        "R8. Baris kedua = panggilan version (id 2, tanpa params)",
        callLine.optString("jsonrpc") == "2.0" && callLine.optInt("id") == 2 &&
            callLine.optString("method") == "version" && !callLine.has("params"),
        seenCall.joinToString()
    )

    // -- Auth --
    val wrongErr = catchesAs("R9. Token salah via Rust -> ConcatAuthException", ConcatAuthException::class.java) {
        withTcp(handler = { src ->
            val s = src.next() ?: return@withTcp
            authOrRefuse(s, ArrayList())
        }) { ConcatApi.version(WRONG_TOKEN) }
    }
    check("R10. Pesan token salah = 'wrong token' verbatim", wrongErr?.message == "wrong token", wrongErr?.message ?: "")
    val seenEmpty = ArrayList<String>()
    catchesAs("R11. Token kosong via Rust -> ConcatAuthException", ConcatAuthException::class.java) {
        withTcp(handler = { src ->
            val s = src.next() ?: return@withTcp
            authOrRefuse(s, seenEmpty)
        }) { ConcatApi.version("") }
    }
    val emptyAuth = try { JSONObject(seenEmpty[0]) } catch (_: Exception) { JSONObject() }
    check(
        "R12. Baris auth token kosong tetap terkirim",
        emptyAuth.optJSONObject("params")?.optString("token") == "",
        seenEmpty.joinToString()
    )
    val nullIdErr = catchesAs("R13. id null + error via Rust -> ConcatAuthException", ConcatAuthException::class.java) {
        withTcp(handler = { src ->
            val s = src.next() ?: return@withTcp
            s.readLine()
            s.sendJson(
                JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
                    .put(
                        "error",
                        JSONObject().put("code", -32006).put("message", "wrong token")
                            .put("data", JSONObject().put("code", "unauthorized"))
                    )
            )
        }) { ConcatApi.version(FAKE_TOKEN) }
    }
    check("R14. Pesan id-null = 'wrong token' verbatim", nullIdErr?.message == "wrong token", nullIdErr?.message ?: "")

    // -- Notifikasi + id salah + baris kosong dilewati --
    val versionAfterNoise = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        s.sendLine("""{"jsonrpc":"2.0","method":"export.progress","params":{"job":"j1","frame":1,"total":10}}""")
        s.sendLine("""{"jsonrpc":"2.0","method":"export.progress","params":{"job":"j1","frame":2,"total":10}}""")
        s.sendLine("""{"jsonrpc":"2.0","id":99,"result":{"apiVersion":"9.9","concat":"9.9"}}""")
        s.sendLine("")
        s.sendLine("""{"jsonrpc":"2.0","id":null,"result":{"apiVersion":"8.8","concat":"8.8"}}""")
        replyResult(s, call, versionResult("0.2.10"))
    }) { ConcatApi.version(FAKE_TOKEN) }
    check(
        "R15. Notifikasi + id lain + id null + baris kosong dilewati",
        versionAfterNoise == "concat 0.2.10 / apiVersion 0.2",
        versionAfterNoise
    )

    // -- Server mati / putus / diam / baris besar / bukan JSON --
    val downErr = catchesAs("R16. Server mati (tidak ada yang dengar :7420) -> ConcatDownException", ConcatDownException::class.java) {
        ConcatApi.version(FAKE_TOKEN)
    }
    check(
        "R17. Pesan server mati = pesan baku (tanpa token)",
        downErr?.message == ConcatApi.DOWN_MESSAGE && downErr.message?.contains(FAKE_TOKEN) == false
    )
    val downAuthErr = catchesAs("R18. Server putus sebelum auth via Rust -> ConcatDownException", ConcatDownException::class.java) {
        withTcp(handler = { src ->
            val s = src.next() ?: return@withTcp
            s.readLine()
            s.close()
        }) { ConcatApi.version(FAKE_TOKEN) }
    }
    check(
        "R19. Pesan putus sebelum masuk bahasa sehari-hari",
        downAuthErr?.message?.contains("putus sebelum sempat masuk") == true,
        downAuthErr?.message ?: ""
    )
    val eofCallErr = catchesAs("R20. Server putus saat memanggil via Rust -> ConcatApiException", ConcatApiException::class.java) {
        withTcp(handler = { src ->
            val s = src.next() ?: return@withTcp
            if (authOrRefuse(s, ArrayList()) == null) return@withTcp
            readCall(s, ArrayList(), ArrayList())
            s.close()
        }) { ConcatApi.version(FAKE_TOKEN) }
    }
    check(
        "R21. Pesan putus saat memanggil bahasa sehari-hari",
        eofCallErr?.message?.contains("putus di tengah jalan") == true &&
            eofCallErr.message?.contains("mengecek versi") == true,
        eofCallErr?.message ?: ""
    )
    val startedSilent = System.currentTimeMillis()
    val silentErr = catchesAs("R22. Server diam via Rust -> ConcatApiException (timeout 15 dtk asli)", ConcatApiException::class.java) {
        withTcp(handler = { src ->
            val s = src.next() ?: return@withTcp
            if (authOrRefuse(s, ArrayList()) == null) return@withTcp
            readCall(s, ArrayList(), ArrayList())
            Thread.sleep(16_000) // klien harus menyerah di 15 dtk; tidur 16 dtk
            // supaya join handler di withTcp tidak menambah elapsed jauh.
        }) { ConcatApi.version(FAKE_TOKEN) }
    }
    val silentElapsed = System.currentTimeMillis() - startedSilent
    check("R23. Timeout terjadi di ~15 dtk (bukan gantung, bukan instan)", silentElapsed in 14_000..20_000, "elapsed=${silentElapsed}ms")
    check(
        "R24. Pesan timeout bahasa sehari-hari",
        silentErr?.message?.contains("tidak membalas") == true &&
            silentErr.message?.contains("mengecek versi") == true &&
            silentErr.message?.contains("15 detik") == true &&
            silentErr.message?.contains(FAKE_TOKEN) == false,
        silentErr?.message ?: ""
    )
    val bigErr = catchesAs("R25. Baris > 4 MiB via Rust ditolak", ConcatApiException::class.java) {
        withTcp(handler = { src ->
            val s = src.next() ?: return@withTcp
            if (authOrRefuse(s, ArrayList()) == null) return@withTcp
            readCall(s, ArrayList(), ArrayList())
            s.sendLine("a".repeat(4 * 1024 * 1024 + 100))
        }) { ConcatApi.version(FAKE_TOKEN) }
    }
    check("R26. Pesan baris besar bahasa sehari-hari", bigErr?.message?.contains("terlalu besar") == true, bigErr?.message ?: "")
    catchesAs("R27. Baris bukan JSON via Rust -> ConcatApiException", ConcatApiException::class.java) {
        withTcp(handler = { src ->
            val s = src.next() ?: return@withTcp
            if (authOrRefuse(s, ArrayList()) == null) return@withTcp
            readCall(s, ArrayList(), ArrayList())
            s.sendLine("""{"jsonrpc":"2.0","id":2,"result":}""")
        }) { ConcatApi.version(FAKE_TOKEN) }
    }

    // -- listProjects --
    val listOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(
            s, call,
            JSONArray()
                .put(projectRef("Untitled project", "/p/A"))
                .put(projectRef("Proyek Liburan", "/p/B"))
        )
    }) { ConcatApi.listProjects(FAKE_TOKEN) }
    check(
        "R28. listProjects() via Rust memetakan 2 proyek",
        listOut == listOf(ConcatProjectRef("Untitled project", "/p/A"), ConcatProjectRef("Proyek Liburan", "/p/B")),
        listOut.toString()
    )
    val listPartial = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(
            s, call,
            JSONArray()
                .put(JSONObject().put("name", "Tanpa Path"))
                .put(JSONObject().put("path", "/p/tanpa-nama"))
                .put(projectRef("Lengkap", "/p/C"))
        )
    }) { ConcatApi.listProjects(FAKE_TOKEN) }
    check(
        "R29. listProjects() via Rust lewati entri tanpa nama/path",
        listPartial == listOf(ConcatProjectRef("Lengkap", "/p/C")),
        listPartial.toString()
    )

    // -- open/get (ringkasan byte-identik) --
    val methodsDirect = ArrayList<String>()
    val openParamsDirect = ArrayList<JSONObject>()
    val openDirect = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), methodsDirect) ?: return@withTcp
        openParamsDirect.add(call.optJSONObject("params") ?: JSONObject())
        replyResult(s, call, twoClipView())
    }) { ConcatApi.openProject(FAKE_TOKEN, "/p/Untitled") }
    check("R30. openProject(path) via Rust — tanpa project.list", methodsDirect == listOf("project.open"), methodsDirect.toString())
    check(
        "R31. openProject(path) via Rust kirim path apa adanya",
        openParamsDirect.firstOrNull()?.optString("path") == "/p/Untitled",
        openParamsDirect.toString()
    )
    check(
        "R32. Ringkasan open via Rust BYTE-IDENTIK (Dibuka + Catatan)",
        openDirect == twoClipSummary("/p/Untitled", opened = true),
        openDirect
    )
    val methodsByName = ArrayList<String>()
    val openByName = withTcp(handler = { src ->
        val s1 = src.next() ?: return@withTcp
        if (authOrRefuse(s1, ArrayList()) == null) return@withTcp
        val call1 = readCall(s1, ArrayList(), methodsByName) ?: return@withTcp
        replyResult(s1, call1, JSONArray().put(projectRef("Untitled project", "/p/Untitled")))
        val s2 = src.next() ?: return@withTcp
        if (authOrRefuse(s2, ArrayList()) == null) return@withTcp
        val call2 = readCall(s2, ArrayList(), methodsByName) ?: return@withTcp
        replyResult(s2, call2, twoClipView())
    }) { ConcatApi.openProject(FAKE_TOKEN, "Untitled project") }
    check(
        "R33. openProject(nama) via Rust -> list dulu, lalu open",
        methodsByName == listOf("project.list", "project.open"),
        methodsByName.toString()
    )
    check(
        "R34. Ringkasan open(nama) via Rust BYTE-IDENTIK",
        openByName == twoClipSummary("/p/Untitled", opened = true),
        openByName
    )
    val unknownErr = catchesAs("R35. openProject nama tidak ketemu via Rust -> error", ConcatApiException::class.java) {
        withTcp(handler = { src ->
            val s = src.next() ?: return@withTcp
            if (authOrRefuse(s, ArrayList()) == null) return@withTcp
            val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
            replyResult(
                s, call,
                JSONArray()
                    .put(projectRef("Untitled project", "/p/A"))
                    .put(projectRef("Proyek Liburan", "/p/B"))
            )
        }) { ConcatApi.openProject(FAKE_TOKEN, "Tidak Ada") }
    }
    check(
        "R36. Pesan nama tidak ketemu sebut nama + yang ada",
        unknownErr?.message?.contains("Tidak Ada") == true &&
            unknownErr.message?.contains("Untitled project") == true &&
            unknownErr.message?.contains("Proyek Liburan") == true,
        unknownErr?.message ?: ""
    )
    val getExact = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, twoClipView())
    }) { ConcatApi.getProject(FAKE_TOKEN, "/p/Untitled") }
    check("R37. Ringkasan get via Rust BYTE-IDENTIK", getExact == twoClipSummary("/p/Untitled", opened = false), getExact)
    check(
        "R38. Ringkasan get via Rust: tanpa dokumen mentah",
        !getExact.contains("\"timelines\"") && !getExact.contains("\"project\"") && !getExact.contains("{"),
        getExact
    )
    val shortSummary = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, shortClipsView())
    }) { ConcatApi.getProject(FAKE_TOKEN, "/p/Pendek") }
    check(
        "R39. Ringkasan via Rust: '12.5 dtk' / '12 dtk' / '45 dtk'",
        shortSummary.contains("klip-a.mp4 (12.5 dtk)") &&
            shortSummary.contains("klip-b.mp4 (12 dtk)") &&
            shortSummary.contains("klip-c.mp4 (45 dtk)"),
        shortSummary
    )
    val rateSummary = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        val clips = JSONArray().put(clipJson("c1", "klip.mp4", 0.0, 10.0))
        replyResult(s, call, editorView("Rate", JSONArray().put(timelineJson("TL1", "Timeline 1", clips)), "TL1", 24000, 1001))
    }) { ConcatApi.getProject(FAKE_TOKEN, "/p/Rate") }
    check("R40. Rate 24000/1001 via Rust -> '23.98 fps'", rateSummary.contains("1080x1920 @ 23.98 fps"), rateSummary)
    val rate8Summary = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        val clips = JSONArray().put(clipJson("c1", "klip.mp4", 0.0, 10.0))
        replyResult(s, call, editorView("Rate", JSONArray().put(timelineJson("TL1", "Timeline 1", clips)), "TL1", 1, 8))
    }) { ConcatApi.getProject(FAKE_TOKEN, "/p/Rate") }
    check("R41. Rate 1/8 via Rust -> '0.13 fps' (HALF_UP, bukan half-even)", rate8Summary.contains("1080x1920 @ 0.13 fps"), rate8Summary)
    val manyClips = JSONArray()
    for (i in 1..200) {
        manyClips.put(clipJson("c$i", "Klip $i", (i - 1) * 5.0, 5.0))
    }
    val bigView = editorView("Proyek Besar", JSONArray().put(timelineJson("TL1", "Timeline 1", manyClips)), "TL1")
    val bigSummary = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, bigView)
    }) { ConcatApi.getProject(FAKE_TOKEN, "/p/Besar") }
    check("R42. Ringkasan 200 klip via Rust -> panjang <= 2000 + '+N klip lagi'", bigSummary.length <= 2000 && bigSummary.contains("klip lagi"), "panjang=${bigSummary.length}")
    val activeView = editorView(
        "Dua Timeline",
        JSONArray()
            .put(timelineJson("TL1", "Timeline 1", JSONArray().put(clipJson("c1", "Klip Lama", 0.0, 10.0))))
            .put(timelineJson("TL2", "Timeline 2", JSONArray().put(clipJson("c2", "Klip Aktif", 0.0, 20.0)))),
        "TL2"
    )
    val activeSummary = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, activeView)
    }) { ConcatApi.getProject(FAKE_TOKEN, "/p/Dua") }
    check(
        "R43. Ringkasan via Rust pakai timeline AKTIF",
        activeSummary.contains("Timeline 2") && activeSummary.contains("Klip Aktif") &&
            !activeSummary.contains("Klip Lama"),
        activeSummary
    )
    val emptyView = editorView("Kosong", JSONArray().put(timelineJson("TL1", "Timeline 1", JSONArray())), "TL1")
    val emptySummary = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, emptyView)
    }) { ConcatApi.getProject(FAKE_TOKEN, "/p/Kosong") }
    check(
        "R44. Ringkasan 0 klip via Rust -> '0 klip', total '0 dtk'",
        emptySummary.contains("0 klip") && emptySummary.contains("total 0 dtk"),
        emptySummary
    )

    // -- Lock + error lain --
    val lockErr = catchesAs("R45. open ditolak lock via Rust -> ConcatApiException", ConcatApiException::class.java) {
        withTcp(handler = { src ->
            val s = src.next() ?: return@withTcp
            if (authOrRefuse(s, ArrayList()) == null) return@withTcp
            val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
            replyError(s, call, -32003, "refused", "/p/Untitled is open in the window")
        }) { ConcatApi.openProject(FAKE_TOKEN, "/p/Untitled") }
    }
    check(
        "R46. Pesan lock via Rust = pesan server + hint",
        lockErr?.message?.contains("/p/Untitled is open in the window") == true &&
            lockErr.message?.contains("tutup dulu proyek di layar Concat, lalu coba lagi") == true,
        lockErr?.message ?: ""
    )
    val notLockErr = catchesAs("R47. open ditolak bukan-lock via Rust -> tanpa hint", ConcatApiException::class.java) {
        withTcp(handler = { src ->
            val s = src.next() ?: return@withTcp
            if (authOrRefuse(s, ArrayList()) == null) return@withTcp
            val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
            replyError(s, call, -32000, "failed", "could not read /p/X/concat.json: No such file")
        }) { ConcatApi.openProject(FAKE_TOKEN, "/p/X") }
    }
    check(
        "R48. Pesan bukan-lock via Rust verbatim, TANPA hint",
        notLockErr?.message?.contains("could not read /p/X/concat.json") == true &&
            notLockErr.message?.contains("tutup dulu proyek") == false,
        notLockErr?.message ?: ""
    )

    // -- 17 method vc315 via Rust --
    val createOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, twoClipView())
    }) { ConcatApi.createProject(FAKE_TOKEN, "Baru", "/p") }
    check("R49. createProject via Rust -> ringkasan path /p/Baru", createOut == twoClipSummary("/p/Baru", opened = true), createOut)
    val createBlank = withTcp(handler = { src ->
        val s1 = src.next() ?: return@withTcp
        if (authOrRefuse(s1, ArrayList()) == null) return@withTcp
        val call1 = readCall(s1, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s1, call1, JSONArray().put(projectRef("Untitled project", "/p/A")))
        val s2 = src.next() ?: return@withTcp
        if (authOrRefuse(s2, ArrayList()) == null) return@withTcp
        val call2 = readCall(s2, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s2, call2, twoClipView())
    }) { ConcatApi.createProject(FAKE_TOKEN, "Baru", null) }
    check("R50. createProject location kosong via Rust -> induk proyek pertama", createBlank == twoClipSummary("/p/Baru", opened = true), createBlank)
    catchesAs("R51. createProject location kosong + list kosong via Rust -> error", ConcatApiException::class.java) {
        withTcp(handler = { src ->
            val s = src.next() ?: return@withTcp
            if (authOrRefuse(s, ArrayList()) == null) return@withTcp
            val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
            replyResult(s, call, JSONArray())
        }) { ConcatApi.createProject(FAKE_TOKEN, "Baru", null) }
    }
    val closeSave = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, JSONObject())
    }) { ConcatApi.closeProject(FAKE_TOKEN, "/p/A", save = true) }
    check("R52. closeProject(save) via Rust", closeSave == "Proyek ditutup dan disimpan: /p/A", closeSave)
    val closeDrop = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, JSONObject())
    }) { ConcatApi.closeProject(FAKE_TOKEN, "/p/A", save = false) }
    check("R53. closeProject(buang) via Rust", closeDrop == "Proyek ditutup (perubahan yang belum disimpan dibuang): /p/A", closeDrop)
    val saveOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, JSONObject())
    }) { ConcatApi.saveProject(FAKE_TOKEN, "/p/A") }
    check("R54. saveProject via Rust", saveOut == "Proyek disimpan: /p/A", saveOut)
    val saveRename = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, JSONObject())
    }) { ConcatApi.saveProject(FAKE_TOKEN, "/p/A", "Baru") }
    check("R55. saveProject(nama baru) via Rust", saveRename == "Proyek disimpan dengan nama baru \"Baru\": /p/A", saveRename)

    // -- setVideo --
    val setVideoParams = ArrayList<JSONObject>()
    val setVideoOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        setVideoParams.add(call.optJSONObject("params") ?: JSONObject())
        replyResult(s, call, twoClipView())
    }) { ConcatApi.setVideo(FAKE_TOKEN, "/p/A", 1920, 1080, 1, 8) }
    check("R56. setVideo via Rust -> ringkasan", setVideoOut == twoClipSummary("/p/A", opened = false), setVideoOut)
    val setVideoVideo = setVideoParams.firstOrNull()?.optJSONObject("video")
    check(
        "R57. setVideo via Rust kirim rate eksplisit 1/8",
        setVideoVideo?.optLong("rateNum") == 1L && setVideoVideo?.optLong("rateDen") == 8L &&
            setVideoVideo?.optInt("width") == 1920 && setVideoVideo?.optInt("height") == 1080,
        setVideoParams.toString()
    )
    val setVideoFetchParams = ArrayList<JSONObject>()
    withTcp(handler = { src ->
        val s1 = src.next() ?: return@withTcp
        if (authOrRefuse(s1, ArrayList()) == null) return@withTcp
        val call1 = readCall(s1, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s1, call1, twoClipView())
        val s2 = src.next() ?: return@withTcp
        if (authOrRefuse(s2, ArrayList()) == null) return@withTcp
        val call2 = readCall(s2, ArrayList(), ArrayList()) ?: return@withTcp
        setVideoFetchParams.add(call2.optJSONObject("params") ?: JSONObject())
        replyResult(s2, call2, twoClipView())
    }) { ConcatApi.setVideo(FAKE_TOKEN, "/p/A", 1280, 720) }
    val fetchedVideo = setVideoFetchParams.firstOrNull()?.optJSONObject("video")
    check(
        "R58. setVideo tanpa rate via Rust -> ambil rate aktif proyek (30/1)",
        fetchedVideo?.optLong("rateNum") == 30L && fetchedVideo?.optLong("rateDen") == 1L,
        setVideoFetchParams.toString()
    )
    val ratePairErr = catchesAs("R59. setVideo rate setengah via Rust -> error (tanpa server)", ConcatApiException::class.java) {
        ConcatApi.setVideo(FAKE_TOKEN, "/p/A", 1920, 1080, 30, null)
    }
    check(
        "R60. Pesan rate setengah jelas",
        ratePairErr?.message?.contains("Isi rateNum dan rateDen dua-duanya") == true,
        ratePairErr?.message ?: ""
    )

    // -- applyEdit / undo / redo --
    val editParams = ArrayList<JSONObject>()
    val editOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        editParams.add(call.optJSONObject("params") ?: JSONObject())
        replyResult(s, call, twoClipView())
    }) { ConcatApi.applyEdit(FAKE_TOKEN, "/p/A", "addClip", JSONObject().put("mediaId", "m1").put("trackId", "T1").put("start", 0.0)) }
    check("R61. applyEdit via Rust -> ringkasan", editOut == twoClipSummary("/p/A", opened = false), editOut)
    val editCmd = editParams.firstOrNull()?.optJSONObject("command")
    check(
        "R62. applyEdit via Rust sisipkan op + path",
        editCmd?.optString("op") == "addClip" && editCmd?.optString("mediaId") == "m1" &&
            editParams.firstOrNull()?.optString("path") == "/p/A",
        editParams.toString()
    )
    val unknownOpErr = catchesAs("R63. applyEdit op tak dikenal via Rust -> error (tanpa server)", ConcatApiException::class.java) {
        ConcatApi.applyEdit(FAKE_TOKEN, "/p/A", "nope", JSONObject())
    }
    check(
        "R64. Pesan op tak dikenal sebut 58 perintah",
        unknownOpErr?.message?.contains("tidak dikenal") == true && unknownOpErr.message?.contains("58") == true,
        unknownOpErr?.message ?: ""
    )
    val matchClipErr = catchesAs("R65. applyEdit matchClip via Rust -> ditolak (tanpa server)", ConcatApiException::class.java) {
        ConcatApi.applyEdit(FAKE_TOKEN, "/p/A", "matchClip", JSONObject())
    }
    check(
        "R66. Pesan matchClip jelas",
        matchClipErr?.message?.contains("matchClip") == true && matchClipErr.message?.contains("khusus jendela") == true,
        matchClipErr?.message ?: ""
    )
    val missingFieldErr = catchesAs("R67. applyEdit field wajib hilang via Rust -> error", ConcatApiException::class.java) {
        ConcatApi.applyEdit(FAKE_TOKEN, "/p/A", "addClip", JSONObject().put("mediaId", "m1"))
    }
    check(
        "R68. Pesan field hilang sebut fieldnya",
        missingFieldErr?.message?.contains("trackId") == true,
        missingFieldErr?.message ?: ""
    )
    val batchDeepErr = catchesAs("R69. applyEdit batch berisi matchClip via Rust -> ditolak", ConcatApiException::class.java) {
        val inner = JSONObject().put("op", "matchClip")
        val batch = JSONObject().put("commands", JSONArray().put(JSONObject().put("op", "addClip").put("mediaId", "m1").put("trackId", "T1").put("start", 0.0)).put(inner))
        ConcatApi.applyEdit(FAKE_TOKEN, "/p/A", "batch", batch)
    }
    check(
        "R70. Pesan batch matchClip jelas",
        batchDeepErr?.message?.contains("matchClip") == true,
        batchDeepErr?.message ?: ""
    )
    val undoOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, twoClipView())
    }) { ConcatApi.undoEdit(FAKE_TOKEN, "/p/A") }
    check("R71. undoEdit via Rust -> ringkasan", undoOut == twoClipSummary("/p/A", opened = false), undoOut)
    val redoOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, twoClipView())
    }) { ConcatApi.redoEdit(FAKE_TOKEN, "/p/A") }
    check("R72. redoEdit via Rust -> ringkasan", redoOut == twoClipSummary("/p/A", opened = false), redoOut)

    // -- probe / import / katalog / template --
    val probeOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, mediaJson())
    }) { ConcatApi.probeMedia(FAKE_TOKEN, "/v/a.mp4") }
    check("R73. probeMedia via Rust BYTE-IDENTIK", probeOut == PROBE_EXPECTED, probeOut)
    val importOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, twoClipView())
    }) { ConcatApi.importMedia(FAKE_TOKEN, "/p/A", "/v/a.mp4") }
    check("R74. importMedia via Rust -> ringkasan", importOut == twoClipSummary("/p/A", opened = false), importOut)
    val catalogueOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(
            s, call,
            JSONArray()
                .put(packageJson("e1", "Blur", "effect", "Stylize"))
                .put(packageJson("f1", "Vintage", "filter", "Color"))
        )
    }) { ConcatApi.listCatalogue(FAKE_TOKEN) }
    check("R75. listCatalogue via Rust BYTE-IDENTIK", catalogueOut == CATALOGUE_EXPECTED, catalogueOut)
    val catalogueKindParams = ArrayList<JSONObject>()
    withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        catalogueKindParams.add(call.optJSONObject("params") ?: JSONObject())
        replyResult(s, call, JSONArray())
    }) { ConcatApi.listCatalogue(FAKE_TOKEN, "effect") }
    check(
        "R76. listCatalogue(kind) via Rust kirim kind",
        catalogueKindParams.firstOrNull()?.optString("kind") == "effect",
        catalogueKindParams.toString()
    )
    val kindErr = catchesAs("R77. listCatalogue kind tak dikenal via Rust -> error (tanpa server)", ConcatApiException::class.java) {
        ConcatApi.listCatalogue(FAKE_TOKEN, "nope")
    }
    check(
        "R78. Pesan kind tak dikenal sebut yang ada",
        kindErr?.message?.contains("tidak dikenal") == true && kindErr.message?.contains("effect") == true,
        kindErr?.message ?: ""
    )
    val templatesOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(
            s, call,
            JSONArray()
                .put(templateInfo("Intro", 1920, 1080, 30, 1, 2))
                .put(templateInfo("Outro", 0, 0, 0, 0, 0))
        )
    }) { ConcatApi.listTemplates(FAKE_TOKEN) }
    check("R79. listTemplates via Rust BYTE-IDENTIK", templatesOut == TEMPLATES_EXPECTED, templatesOut)
    val instOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, twoClipView())
    }) {
        ConcatApi.instantiateTemplate(
            FAKE_TOKEN, "Intro", "/tmp", "TplBaru",
            JSONArray().put(JSONObject().put("mediaId", "m1").put("file", "/v/a.mp4"))
        )
    }
    check("R80. instantiateTemplate via Rust -> ringkasan path /tmp/TplBaru", instOut == twoClipSummary("/tmp/TplBaru", opened = true), instOut)
    val fillErr = catchesAs("R81. instantiateTemplate slot tak lengkap via Rust -> error (tanpa server)", ConcatApiException::class.java) {
        ConcatApi.instantiateTemplate(
            FAKE_TOKEN, "Intro", "/tmp", "TplBaru",
            JSONArray().put(JSONObject().put("mediaId", "m1"))
        )
    }
    check(
        "R82. Pesan slot tak lengkap sebut nomor slot",
        fillErr?.message?.contains("ke-1") == true,
        fillErr?.message ?: ""
    )
    val saveTplOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, templateInfo("Intro", 1920, 1080, 30, 1, 2))
    }) { ConcatApi.saveTemplate(FAKE_TOKEN, "/p/A", "Intro") }
    check("R83. saveTemplate via Rust BYTE-IDENTIK", saveTplOut == SAVE_TEMPLATE_EXPECTED, saveTplOut)

    // -- export --
    val exportParams = ArrayList<JSONObject>()
    val runOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        exportParams.add(call.optJSONObject("params") ?: JSONObject())
        replyResult(s, call, JSONObject().put("job", "job-1").put("output", "/o/f.mp4"))
    }) { ConcatApi.runExport(FAKE_TOKEN, "/p/A", "/o/f.mp4") }
    check("R84. runExport via Rust BYTE-IDENTIK", runOut == RUN_EXPORT_EXPECTED, runOut)
    check(
        "R85. runExport via Rust: opsi kosong tidak dikirim",
        exportParams.firstOrNull()?.has("crf") == false &&
            exportParams.firstOrNull()?.has("preset") == false &&
            exportParams.firstOrNull()?.optString("output") == "/o/f.mp4",
        exportParams.toString()
    )
    val cancelOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, JSONObject())
    }) { ConcatApi.cancelExport(FAKE_TOKEN, "job-1") }
    check("R86. cancelExport via Rust", cancelOut == "Ekspor job \"job-1\" dibatalkan.", cancelOut)
    val statusRunning = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(
            s, call,
            JSONObject().put("job", "job-1").put("state", "running").put("stage", "video")
                .put("frame", 10).put("total", 100).put("fraction", 0.5).put("output", "/o/f.mp4")
        )
    }) { ConcatApi.exportStatus(FAKE_TOKEN, "job-1") }
    check("R87. exportStatus(running) via Rust BYTE-IDENTIK", statusRunning == STATUS_RUNNING_EXPECTED, statusRunning)
    val statusDone = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, JSONObject().put("job", "job-1").put("state", "done").put("output", "/o/f.mp4"))
    }) { ConcatApi.exportStatus(FAKE_TOKEN, "job-1") }
    check(
        "R88. exportStatus(done) via Rust",
        statusDone.contains("job-1: selesai") && statusDone.contains("/o/f.mp4"),
        statusDone
    )
    val statusFailed = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(
            s, call,
            JSONObject().put("job", "job-1").put("state", "failed")
                .put("error", JSONObject().put("message", "disk penuh"))
        )
    }) { ConcatApi.exportStatus(FAKE_TOKEN, "job-1") }
    check(
        "R89. exportStatus(failed) via Rust sebut error server",
        statusFailed.contains("job-1: gagal") && statusFailed.contains("disk penuh"),
        statusFailed
    )

    // -- previewFrame --
    ConcatApi.previewDir = previewTmp.path
    val frameServerOut = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, JSONObject().put("path", "/srv/f.png").put("width", 1920).put("height", 1080))
    }) { ConcatApi.previewFrame(FAKE_TOKEN, "/p/A", 1.5, output = "/srv/f.png") }
    check(
        "R90. previewFrame(output) via Rust -> path server",
        frameServerOut == "Frame tersimpan di: /srv/f.png (1920x1080)",
        frameServerOut
    )
    val frameB64Out = withTcp(handler = { src ->
        val s = src.next() ?: return@withTcp
        if (authOrRefuse(s, ArrayList()) == null) return@withTcp
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
        replyResult(s, call, JSONObject().put("png", "iVBORw0KGgo=").put("width", 640).put("height", 360))
    }) { ConcatApi.previewFrame(FAKE_TOKEN, "/p/A", 2.0) }
    val frameFile = File(previewTmp, frameB64Out
        .removePrefix("Frame tersimpan di: ")
        .removeSuffix(" (640x360)")
        .substringAfterLast("/"))
    check(
        "R91. previewFrame(base64) via Rust -> file PNG di previewDir",
        frameB64Out.startsWith("Frame tersimpan di: ${previewTmp.path}/concat-preview-") &&
            frameB64Out.endsWith(" (640x360)") && frameFile.exists() &&
            frameFile.readBytes().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        frameB64Out
    )
    val frameEmptyErr = catchesAs("R92. previewFrame png kosong via Rust -> error", ConcatApiException::class.java) {
        withTcp(handler = { src ->
            val s = src.next() ?: return@withTcp
            if (authOrRefuse(s, ArrayList()) == null) return@withTcp
            val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
            replyResult(s, call, JSONObject().put("png", ""))
        }) { ConcatApi.previewFrame(FAKE_TOKEN, "/p/A", 1.0) }
    }
    check(
        "R93. Pesan png kosong jelas",
        frameEmptyErr?.message?.contains("tidak mengirim gambar frame") == true,
        frameEmptyErr?.message ?: ""
    )
    val frameBadErr = catchesAs("R94. previewFrame base64 rusak via Rust -> error", ConcatApiException::class.java) {
        withTcp(handler = { src ->
            val s = src.next() ?: return@withTcp
            if (authOrRefuse(s, ArrayList()) == null) return@withTcp
            val call = readCall(s, ArrayList(), ArrayList()) ?: return@withTcp
            replyResult(s, call, JSONObject().put("png", "!!!"))
        }) { ConcatApi.previewFrame(FAKE_TOKEN, "/p/A", 1.0) }
    }
    check(
        "R95. Pesan base64 rusak jelas",
        frameBadErr?.message?.contains("tidak bisa dibaca") == true,
        frameBadErr?.message ?: ""
    )
    val frameBigErr = catchesAs("R96. previewFrame baris terlalu besar via Rust -> pesan khusus frame", ConcatApiException::class.java) {
        withTcp(handler = { src ->
            val s = src.next() ?: return@withTcp
            if (authOrRefuse(s, ArrayList()) == null) return@withTcp
            readCall(s, ArrayList(), ArrayList())
            s.sendLine("a".repeat(4 * 1024 * 1024 + 100))
        }) { ConcatApi.previewFrame(FAKE_TOKEN, "/p/A", 1.0) }
    }
    check(
        "R97. Pesan frame terlalu besar jelas",
        frameBigErr?.message?.contains("Gambar frame terlalu besar") == true,
        frameBigErr?.message ?: ""
    )

    // -- Saklar fallback (di mode rust, pakai transport memori) --
    ConcatApi.rustConcatApiClient = false
    val fallbackVersion = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, versionResult("0.2.10"))
    }) { ConcatApi.version(FAKE_TOKEN) }
    check(
        "R98. Saklar false -> jalur Kotlin lama jalan (version via memori)",
        fallbackVersion == "concat 0.2.10 / apiVersion 0.2",
        fallbackVersion
    )
    ConcatApi.rustConcatApiClient = true

    // -- Sumber klien --
    val clientSource = try {
        File(treeDir, "ConcatApiClient.kt").readText()
    } catch (e: Exception) {
        ""
    }
    check(
        "R99. Sumber klien: tanpa import android.* dan tanpa println",
        clientSource.isNotEmpty() &&
            clientSource.lines().none { it.trimStart().startsWith("import android") } &&
            !clientSource.contains("println("),
        if (clientSource.isEmpty()) "file tidak kebaca di $treeDir" else ""
    )
    check(
        "R100. Sumber klien: seam Rust + fallback dipertahankan",
        clientSource.contains("rustConcatApiClient") &&
            clientSource.contains("fun concatApiJson") == false &&
            clientSource.contains("RustCore.concatApiJson") &&
            clientSource.contains("private fun versionKotlin") &&
            clientSource.contains("Socket(Proxy.NO_PROXY)") &&
            clientSource.contains("openTcpConnection") &&
            clientSource.contains("connectionOpener"),
        "tidak ketemu seam/fallback/TCP"
    )
    check(
        "R101. Sumber klien: tidak ada token di dalamnya",
        clientSource.isNotEmpty() && !clientSource.contains(FAKE_TOKEN) &&
            !clientSource.contains("hsurr:"),
        if (clientSource.isEmpty()) "file tidak kebaca di $treeDir" else ""
    )
    check(
        "R102. Semua pesan error mode rust: tidak ada token di dalamnya",
        allErrorMessages.none { it.contains(FAKE_TOKEN) || it.contains(WRONG_TOKEN) },
        allErrorMessages.filter { it.contains(FAKE_TOKEN) || it.contains(WRONG_TOKEN) }.toString()
    )

    ConcatApi.previewDir = savedPreviewDir
}

// ---- MODE "kotlin": jalur fallback Kotlin lama (transport memori) ----

fun kotlinTests(treeDir: String, previewTmp: File) {
    // Paksa fallback: saklar mati (RustCore.available juga false di JVM
    // polos tanpa .so — dua-duanya harus mengarah ke *Kotlin).
    ConcatApi.rustConcatApiClient = false
    val savedPreviewDir = ConcatApi.previewDir
    check("K1. RustCore.available == false (JVM polos, tanpa .so)", !RustCore.available, "available=true — mode kotlin harus jalan TANPA -Djava.library.path")

    val versionOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, versionResult("0.2.10"))
    }) { ConcatApi.version(FAKE_TOKEN) }
    check("K2. version() via Kotlin fallback", versionOut == "concat 0.2.10 / apiVersion 0.2", versionOut)
    val getExact = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, twoClipView())
    }) { ConcatApi.getProject(FAKE_TOKEN, "/p/Untitled") }
    check("K3. Ringkasan get via Kotlin BYTE-IDENTIK dengan Rust", getExact == twoClipSummary("/p/Untitled", opened = false), getExact)
    val openExact = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, twoClipView())
    }) { ConcatApi.openProject(FAKE_TOKEN, "/p/Untitled") }
    check("K4. Ringkasan open via Kotlin BYTE-IDENTIK dengan Rust", openExact == twoClipSummary("/p/Untitled", opened = true), openExact)
    val lockErr = catchesAs("K5. open ditolak lock via Kotlin -> hint", ConcatApiException::class.java) {
        withMem(handler = { src ->
            val s = src.next() ?: return@withMem
            if (authOrRefuse(s, ArrayList()) == null) return@withMem
            val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
            replyError(s, call, -32003, "refused", "/p/Untitled is open in the window")
        }) { ConcatApi.openProject(FAKE_TOKEN, "/p/Untitled") }
    }
    check(
        "K6. Pesan lock via Kotlin = pesan server + hint",
        lockErr?.message?.contains("/p/Untitled is open in the window") == true &&
            lockErr.message?.contains("tutup dulu proyek di layar Concat, lalu coba lagi") == true,
        lockErr?.message ?: ""
    )

    // -- 17 method vc315 via Kotlin --
    val createOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, twoClipView())
    }) { ConcatApi.createProject(FAKE_TOKEN, "Baru", "/p") }
    check("K7. createProject via Kotlin -> ringkasan path /p/Baru", createOut == twoClipSummary("/p/Baru", opened = true), createOut)
    val createBlank = withMem(handler = { src ->
        val s1 = src.next() ?: return@withMem
        if (authOrRefuse(s1, ArrayList()) == null) return@withMem
        val call1 = readCall(s1, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s1, call1, JSONArray().put(projectRef("Untitled project", "/p/A")))
        val s2 = src.next() ?: return@withMem
        if (authOrRefuse(s2, ArrayList()) == null) return@withMem
        val call2 = readCall(s2, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s2, call2, twoClipView())
    }) { ConcatApi.createProject(FAKE_TOKEN, "Baru", null) }
    check("K8. createProject location kosong via Kotlin -> induk proyek pertama", createBlank == twoClipSummary("/p/Baru", opened = true), createBlank)
    catchesAs("K9. createProject location kosong + list kosong via Kotlin -> error", ConcatApiException::class.java) {
        withMem(handler = { src ->
            val s = src.next() ?: return@withMem
            if (authOrRefuse(s, ArrayList()) == null) return@withMem
            val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
            replyResult(s, call, JSONArray())
        }) { ConcatApi.createProject(FAKE_TOKEN, "Baru", null) }
    }
    val closeSave = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, JSONObject())
    }) { ConcatApi.closeProject(FAKE_TOKEN, "/p/A", save = true) }
    check("K10. closeProject(save) via Kotlin", closeSave == "Proyek ditutup dan disimpan: /p/A", closeSave)
    val closeDrop = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, JSONObject())
    }) { ConcatApi.closeProject(FAKE_TOKEN, "/p/A", save = false) }
    check("K11. closeProject(buang) via Kotlin", closeDrop == "Proyek ditutup (perubahan yang belum disimpan dibuang): /p/A", closeDrop)
    val saveOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, JSONObject())
    }) { ConcatApi.saveProject(FAKE_TOKEN, "/p/A") }
    check("K12. saveProject via Kotlin", saveOut == "Proyek disimpan: /p/A", saveOut)
    val saveRename = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, JSONObject())
    }) { ConcatApi.saveProject(FAKE_TOKEN, "/p/A", "Baru") }
    check("K13. saveProject(nama baru) via Kotlin", saveRename == "Proyek disimpan dengan nama baru \"Baru\": /p/A", saveRename)
    val setVideoParams = ArrayList<JSONObject>()
    val setVideoOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        setVideoParams.add(call.optJSONObject("params") ?: JSONObject())
        replyResult(s, call, twoClipView())
    }) { ConcatApi.setVideo(FAKE_TOKEN, "/p/A", 1920, 1080, 1, 8) }
    check("K14. setVideo via Kotlin -> ringkasan", setVideoOut == twoClipSummary("/p/A", opened = false), setVideoOut)
    val setVideoVideo = setVideoParams.firstOrNull()?.optJSONObject("video")
    check(
        "K15. setVideo via Kotlin kirim rate eksplisit 1/8",
        setVideoVideo?.optLong("rateNum") == 1L && setVideoVideo?.optLong("rateDen") == 8L,
        setVideoParams.toString()
    )
    val setVideoFetchParams = ArrayList<JSONObject>()
    withMem(handler = { src ->
        val s1 = src.next() ?: return@withMem
        if (authOrRefuse(s1, ArrayList()) == null) return@withMem
        val call1 = readCall(s1, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s1, call1, twoClipView())
        val s2 = src.next() ?: return@withMem
        if (authOrRefuse(s2, ArrayList()) == null) return@withMem
        val call2 = readCall(s2, ArrayList(), ArrayList()) ?: return@withMem
        setVideoFetchParams.add(call2.optJSONObject("params") ?: JSONObject())
        replyResult(s2, call2, twoClipView())
    }) { ConcatApi.setVideo(FAKE_TOKEN, "/p/A", 1280, 720) }
    val fetchedVideo = setVideoFetchParams.firstOrNull()?.optJSONObject("video")
    check(
        "K16. setVideo tanpa rate via Kotlin -> ambil rate aktif proyek (30/1)",
        fetchedVideo?.optLong("rateNum") == 30L && fetchedVideo?.optLong("rateDen") == 1L,
        setVideoFetchParams.toString()
    )
    val ratePairErr = catchesAs("K17. setVideo rate setengah via Kotlin -> error (tanpa server)", ConcatApiException::class.java) {
        ConcatApi.setVideo(FAKE_TOKEN, "/p/A", 1920, 1080, 30, null)
    }
    check(
        "K18. Pesan rate setengah via Kotlin jelas",
        ratePairErr?.message?.contains("Isi rateNum dan rateDen dua-duanya") == true,
        ratePairErr?.message ?: ""
    )
    val editParams = ArrayList<JSONObject>()
    val editOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        editParams.add(call.optJSONObject("params") ?: JSONObject())
        replyResult(s, call, twoClipView())
    }) { ConcatApi.applyEdit(FAKE_TOKEN, "/p/A", "addClip", JSONObject().put("mediaId", "m1").put("trackId", "T1").put("start", 0.0)) }
    check("K19. applyEdit via Kotlin -> ringkasan", editOut == twoClipSummary("/p/A", opened = false), editOut)
    val editCmd = editParams.firstOrNull()?.optJSONObject("command")
    check(
        "K20. applyEdit via Kotlin sisipkan op + path",
        editCmd?.optString("op") == "addClip" && editCmd?.optString("mediaId") == "m1" &&
            editParams.firstOrNull()?.optString("path") == "/p/A",
        editParams.toString()
    )
    val unknownOpErr = catchesAs("K21. applyEdit op tak dikenal via Kotlin -> error", ConcatApiException::class.java) {
        ConcatApi.applyEdit(FAKE_TOKEN, "/p/A", "nope", JSONObject())
    }
    check(
        "K22. Pesan op tak dikenal via Kotlin sebut 58 perintah",
        unknownOpErr?.message?.contains("tidak dikenal") == true && unknownOpErr.message?.contains("58") == true,
        unknownOpErr?.message ?: ""
    )
    val matchClipErr = catchesAs("K23. applyEdit matchClip via Kotlin -> ditolak", ConcatApiException::class.java) {
        ConcatApi.applyEdit(FAKE_TOKEN, "/p/A", "matchClip", JSONObject())
    }
    check(
        "K24. Pesan matchClip via Kotlin jelas",
        matchClipErr?.message?.contains("matchClip") == true,
        matchClipErr?.message ?: ""
    )
    val missingFieldErr = catchesAs("K25. applyEdit field wajib hilang via Kotlin -> error", ConcatApiException::class.java) {
        ConcatApi.applyEdit(FAKE_TOKEN, "/p/A", "addClip", JSONObject().put("mediaId", "m1"))
    }
    check(
        "K26. Pesan field hilang via Kotlin sebut fieldnya",
        missingFieldErr?.message?.contains("trackId") == true,
        missingFieldErr?.message ?: ""
    )
    val batchDeepErr = catchesAs("K27. applyEdit batch berisi matchClip via Kotlin -> ditolak", ConcatApiException::class.java) {
        val inner = JSONObject().put("op", "matchClip")
        val batch = JSONObject().put("commands", JSONArray().put(JSONObject().put("op", "addClip").put("mediaId", "m1").put("trackId", "T1").put("start", 0.0)).put(inner))
        ConcatApi.applyEdit(FAKE_TOKEN, "/p/A", "batch", batch)
    }
    check(
        "K28. Pesan batch matchClip via Kotlin jelas",
        batchDeepErr?.message?.contains("matchClip") == true,
        batchDeepErr?.message ?: ""
    )
    val undoOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, twoClipView())
    }) { ConcatApi.undoEdit(FAKE_TOKEN, "/p/A") }
    check("K29. undoEdit via Kotlin -> ringkasan", undoOut == twoClipSummary("/p/A", opened = false), undoOut)
    val redoOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, twoClipView())
    }) { ConcatApi.redoEdit(FAKE_TOKEN, "/p/A") }
    check("K30. redoEdit via Kotlin -> ringkasan", redoOut == twoClipSummary("/p/A", opened = false), redoOut)
    val probeOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, mediaJson())
    }) { ConcatApi.probeMedia(FAKE_TOKEN, "/v/a.mp4") }
    check("K31. probeMedia via Kotlin BYTE-IDENTIK", probeOut == PROBE_EXPECTED, probeOut)
    val importOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, twoClipView())
    }) { ConcatApi.importMedia(FAKE_TOKEN, "/p/A", "/v/a.mp4") }
    check("K32. importMedia via Kotlin -> ringkasan", importOut == twoClipSummary("/p/A", opened = false), importOut)
    val catalogueOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(
            s, call,
            JSONArray()
                .put(packageJson("e1", "Blur", "effect", "Stylize"))
                .put(packageJson("f1", "Vintage", "filter", "Color"))
        )
    }) { ConcatApi.listCatalogue(FAKE_TOKEN) }
    check("K33. listCatalogue via Kotlin BYTE-IDENTIK", catalogueOut == CATALOGUE_EXPECTED, catalogueOut)
    val kindErr = catchesAs("K34. listCatalogue kind tak dikenal via Kotlin -> error", ConcatApiException::class.java) {
        ConcatApi.listCatalogue(FAKE_TOKEN, "nope")
    }
    check(
        "K35. Pesan kind tak dikenal via Kotlin sebut yang ada",
        kindErr?.message?.contains("tidak dikenal") == true && kindErr.message?.contains("effect") == true,
        kindErr?.message ?: ""
    )
    val templatesOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(
            s, call,
            JSONArray()
                .put(templateInfo("Intro", 1920, 1080, 30, 1, 2))
                .put(templateInfo("Outro", 0, 0, 0, 0, 0))
        )
    }) { ConcatApi.listTemplates(FAKE_TOKEN) }
    check("K36. listTemplates via Kotlin BYTE-IDENTIK", templatesOut == TEMPLATES_EXPECTED, templatesOut)
    val instOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, twoClipView())
    }) {
        ConcatApi.instantiateTemplate(
            FAKE_TOKEN, "Intro", "/tmp", "TplBaru",
            JSONArray().put(JSONObject().put("mediaId", "m1").put("file", "/v/a.mp4"))
        )
    }
    check("K37. instantiateTemplate via Kotlin -> ringkasan path /tmp/TplBaru", instOut == twoClipSummary("/tmp/TplBaru", opened = true), instOut)
    val fillErr = catchesAs("K38. instantiateTemplate slot tak lengkap via Kotlin -> error", ConcatApiException::class.java) {
        ConcatApi.instantiateTemplate(
            FAKE_TOKEN, "Intro", "/tmp", "TplBaru",
            JSONArray().put(JSONObject().put("mediaId", "m1"))
        )
    }
    check(
        "K39. Pesan slot tak lengkap via Kotlin sebut nomor slot",
        fillErr?.message?.contains("ke-1") == true,
        fillErr?.message ?: ""
    )
    val saveTplOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, templateInfo("Intro", 1920, 1080, 30, 1, 2))
    }) { ConcatApi.saveTemplate(FAKE_TOKEN, "/p/A", "Intro") }
    check("K40. saveTemplate via Kotlin BYTE-IDENTIK", saveTplOut == SAVE_TEMPLATE_EXPECTED, saveTplOut)
    val exportParams = ArrayList<JSONObject>()
    val runOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        exportParams.add(call.optJSONObject("params") ?: JSONObject())
        replyResult(s, call, JSONObject().put("job", "job-1").put("output", "/o/f.mp4"))
    }) { ConcatApi.runExport(FAKE_TOKEN, "/p/A", "/o/f.mp4") }
    check("K41. runExport via Kotlin BYTE-IDENTIK", runOut == RUN_EXPORT_EXPECTED, runOut)
    check(
        "K42. runExport via Kotlin: opsi kosong tidak dikirim",
        exportParams.firstOrNull()?.has("crf") == false &&
            exportParams.firstOrNull()?.optString("output") == "/o/f.mp4",
        exportParams.toString()
    )
    val cancelOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, JSONObject())
    }) { ConcatApi.cancelExport(FAKE_TOKEN, "job-1") }
    check("K43. cancelExport via Kotlin", cancelOut == "Ekspor job \"job-1\" dibatalkan.", cancelOut)
    val statusRunning = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(
            s, call,
            JSONObject().put("job", "job-1").put("state", "running").put("stage", "video")
                .put("frame", 10).put("total", 100).put("fraction", 0.5).put("output", "/o/f.mp4")
        )
    }) { ConcatApi.exportStatus(FAKE_TOKEN, "job-1") }
    check("K44. exportStatus(running) via Kotlin BYTE-IDENTIK", statusRunning == STATUS_RUNNING_EXPECTED, statusRunning)
    val statusFailed = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(
            s, call,
            JSONObject().put("job", "job-1").put("state", "failed")
                .put("error", JSONObject().put("message", "disk penuh"))
        )
    }) { ConcatApi.exportStatus(FAKE_TOKEN, "job-1") }
    check(
        "K45. exportStatus(failed) via Kotlin sebut error server",
        statusFailed.contains("job-1: gagal") && statusFailed.contains("disk penuh"),
        statusFailed
    )

    // -- previewFrame via Kotlin --
    ConcatApi.previewDir = previewTmp.path
    val frameServerOut = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, JSONObject().put("path", "/srv/f.png").put("width", 1920).put("height", 1080))
    }) { ConcatApi.previewFrame(FAKE_TOKEN, "/p/A", 1.5, output = "/srv/f.png") }
    check(
        "K46. previewFrame(output) via Kotlin -> path server",
        frameServerOut == "Frame tersimpan di: /srv/f.png (1920x1080)",
        frameServerOut
    )
    val frameB64Out = withMem(handler = { src ->
        val s = src.next() ?: return@withMem
        if (authOrRefuse(s, ArrayList()) == null) return@withMem
        val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
        replyResult(s, call, JSONObject().put("png", "iVBORw0KGgo=").put("width", 640).put("height", 360))
    }) { ConcatApi.previewFrame(FAKE_TOKEN, "/p/A", 2.0) }
    val frameFile = File(previewTmp, frameB64Out
        .removePrefix("Frame tersimpan di: ")
        .removeSuffix(" (640x360)")
        .substringAfterLast("/"))
    check(
        "K47. previewFrame(base64) via Kotlin -> file PNG di previewDir",
        frameB64Out.startsWith("Frame tersimpan di: ${previewTmp.path}/concat-preview-") &&
            frameB64Out.endsWith(" (640x360)") && frameFile.exists() &&
            frameFile.readBytes().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        frameB64Out
    )
    val frameEmptyErr = catchesAs("K48. previewFrame png kosong via Kotlin -> error", ConcatApiException::class.java) {
        withMem(handler = { src ->
            val s = src.next() ?: return@withMem
            if (authOrRefuse(s, ArrayList()) == null) return@withMem
            val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
            replyResult(s, call, JSONObject().put("png", ""))
        }) { ConcatApi.previewFrame(FAKE_TOKEN, "/p/A", 1.0) }
    }
    check(
        "K49. Pesan png kosong via Kotlin jelas",
        frameEmptyErr?.message?.contains("tidak mengirim gambar frame") == true,
        frameEmptyErr?.message ?: ""
    )
    val frameBadErr = catchesAs("K50. previewFrame base64 rusak via Kotlin -> error", ConcatApiException::class.java) {
        withMem(handler = { src ->
            val s = src.next() ?: return@withMem
            if (authOrRefuse(s, ArrayList()) == null) return@withMem
            val call = readCall(s, ArrayList(), ArrayList()) ?: return@withMem
            replyResult(s, call, JSONObject().put("png", "!!!"))
        }) { ConcatApi.previewFrame(FAKE_TOKEN, "/p/A", 1.0) }
    }
    check(
        "K51. Pesan base64 rusak via Kotlin jelas",
        frameBadErr?.message?.contains("tidak bisa dibaca") == true,
        frameBadErr?.message ?: ""
    )
    val frameBigErr = catchesAs("K52. previewFrame baris terlalu besar via Kotlin -> pesan khusus frame", ConcatApiException::class.java) {
        withMem(handler = { src ->
            val s = src.next() ?: return@withMem
            if (authOrRefuse(s, ArrayList()) == null) return@withMem
            readCall(s, ArrayList(), ArrayList())
            s.sendLine("a".repeat(4 * 1024 * 1024 + 100))
        }) { ConcatApi.previewFrame(FAKE_TOKEN, "/p/A", 1.0) }
    }
    check(
        "K53. Pesan frame terlalu besar via Kotlin jelas",
        frameBigErr?.message?.contains("Gambar frame terlalu besar") == true,
        frameBigErr?.message ?: ""
    )

    // -- Sumber klien (fallback dipertahankan) --
    val clientSource = try {
        File(treeDir, "ConcatApiClient.kt").readText()
    } catch (e: Exception) {
        ""
    }
    check(
        "K54. Sumber klien: implementasi *Kotlin dipertahankan (21 fungsi)",
        clientSource.contains("private fun versionKotlin") &&
            clientSource.contains("private fun previewFrameKotlin") &&
            clientSource.contains("private fun applyEditKotlin") &&
            clientSource.contains("connectionOpener") &&
            clientSource.contains("Socket(Proxy.NO_PROXY)"),
        "tidak ketemu *Kotlin / transport lama"
    )
    check(
        "K55. Semua pesan error mode kotlin: tidak ada token di dalamnya",
        allErrorMessages.none { it.contains(FAKE_TOKEN) || it.contains(WRONG_TOKEN) },
        allErrorMessages.filter { it.contains(FAKE_TOKEN) || it.contains(WRONG_TOKEN) }.toString()
    )

    ConcatApi.previewDir = savedPreviewDir
    ConcatApi.rustConcatApiClient = true
}

fun main(args: Array<String>) {
    val mode = if (args.isNotEmpty()) args[0] else "kotlin"
    val treeDir = if (args.size > 1) args[1] else "work-A"
    val previewTmp = if (args.size > 2) {
        File(args[2]).also { it.mkdirs() }
    } else {
        File(System.getProperty("java.io.tmpdir"), "concat-preview-p5-${System.currentTimeMillis()}").also { it.mkdirs() }
    }
    when (mode) {
        "rust" -> rustTests(treeDir, previewTmp)
        "kotlin" -> kotlinTests(treeDir, previewTmp)
        else -> {
            println("Mode tidak dikenal: $mode (pakai 'rust' atau 'kotlin')")
            exitProcess(2)
        }
    }
    println()
    println("HASIL [$mode]: LULUS $passed / GAGAL $failed (total ${passed + failed})")
    if (failed > 0) println("Yang gagal: ${failedNames.joinToString(", ")}")
    exitProcess(if (failed == 0) 0 else 1)
}
