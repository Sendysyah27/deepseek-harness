// TestConcatApiVc314.kt — tes JVM Worker A vc314 untuk ConcatApiClient.kt
//
// Yang diuji: klien JSON-RPC Concat (auth dulu, cocokkan id, lewati notifikasi,
// petakan error, selesaikan nama proyek lewat list, ringkas proyek tanpa dump
// mentah, token tidak pernah bocor ke pesan apa pun).
//
// Cara kerja: server Concat PALSU dalam-memori (antrean byte, TANPA TCP dan
// tanpa HP). Kenapa bukan socket sungguhan: sandbox VM ini melarang TCP
// langsung dari JVM — koneksi apa pun ke 127.0.0.1:7420 dijawab guard dengan
// teks "muse: Other TCP connections is turned off..." (other_tcp = Deny),
// jadi server palsu berbasis ServerSocket tidak akan pernah terjangkau klien.
// Tes menyetel `ConcatApi.connectionOpener` ke transport dalam-memori; jalur
// TCP produksi (Socket + connect + soTimeout) tetap seperti biasa dan hanya
// bisa dibuktikan di perangkat (T-API-06/07) atau sesudah izin other_tcp
// dibuka. Yang diuji di sini: seluruh logika protokol + pemetaan error-nya.
//
// Token di sini PALSU yang jelas-jelas bukan token asli. Tidak butuh HP,
// tidak butuh Concat asli. Cepat (tidak ada tes yang menunggu 15 detik).
//
// Jalankan: ./run-tests-vc314-A.sh
// Keluar 0 = semua lulus; selain itu daftar GAGAL + exit 1.
package com.senzwh.deepharness

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
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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

/**
 * Jalankan [block], harapkan error berjenis [type]. Bila cocok: LULUS + pesan
 * errornya dicatat untuk cek akhir "tanpa token". Bila tidak cocok: GAGAL.
 */
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

// ---- Transport dalam-memori (pengganti TCP) ----

/**
 * Aliran masuk dari antrean byte. `read` menunggu satu potong paling lama
 * [pollTimeoutMs]; bila tidak ada juga, lempar SocketTimeoutException
 * (meniru soTimeout pada socket). Potongan kosong = EOF.
 */
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

/** Aliran keluar: tulis -> antrean milik [sink]. Tutup hanya kirim EOF bila diminta. */
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

/** Sisi server palsu: baca baris + kirim baris. `close()` = putus ke klien (EOF). */
class FakeServerSide(val reader: BufferedReader, val writer: BufferedWriter) {
    fun readLine(): String? = try {
        reader.readLine()
    } catch (_: Exception) {
        null
    }

    fun sendLine(text: String) {
        writer.write(text)
        writer.write("\n")
        writer.flush()
    }

    fun sendJson(obj: JSONObject) = sendLine(obj.toString())

    fun close() {
        try {
            writer.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Server Concat palsu dalam-memori. Klien (ConcatApi) bicara lewat
 * [clientConnection]; skrip server jalan di thread daemon ([handler]).
 * Satu FakeConcat melayani beberapa koneksi berurutan (list lalu open/get):
 * penulis klien TIDAK mengirim EOF saat tutup, supaya koneksi berikutnya
 * masih bisa jalan di antrean yang sama.
 */
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

/** Jalankan [block] dengan server palsu sebagai `connectionOpener` klien. */
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

/** Jalankan [block] dengan opener yang selalu gagal seperti [error]. */
fun <T> withFailingOpener(error: IOException, block: () -> T): T {
    val original = ConcatApi.connectionOpener
    ConcatApi.connectionOpener = { throw error }
    try {
        return block()
    } finally {
        ConcatApi.connectionOpener = original
    }
}

// ---- Pembantu skrip server ----

/**
 * Baca baris auth, catat mentahnya di [seen]. Bila token cocok: jawab OK
 * (result kosong) lalu kembalikan baris auth-nya. Bila tidak: kirim
 * "wrong token" + tutup, kembalikan null.
 */
fun authOrRefuse(server: FakeServerSide, seen: MutableList<String>): JSONObject? {
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

/** Baca satu baris panggilan, catat mentah + nama metodenya. */
fun readCall(server: FakeServerSide, seen: MutableList<String>, methods: MutableList<String>): JSONObject? {
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

fun replyResult(server: FakeServerSide, call: JSONObject, result: Any) {
    server.sendJson(
        JSONObject().put("jsonrpc", "2.0").put("id", call.opt("id")).put("result", result)
    )
}

fun replyError(server: FakeServerSide, call: JSONObject, code: Int, dataCode: String, message: String) {
    server.sendJson(
        JSONObject().put("jsonrpc", "2.0").put("id", call.opt("id"))
            .put(
                "error",
                JSONObject().put("code", code).put("message", message)
                    .put("data", JSONObject().put("code", dataCode))
            )
    )
}

// ---- Jawaban palsu (bentuknya ikut source v0.2.10) ----

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

fun editorView(name: String, timelines: JSONArray, activeId: String): JSONObject {
    val settings = JSONObject().put("name", name).put("width", 1080).put("height", 1920)
        .put("rateNum", 30).put("rateDen", 1)
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

// ---- Tes ----

fun main(args: Array<String>) {
    val treeDir = if (args.isNotEmpty()) args[0] else "work-A"

    // -- 1. Server mati: koneksi ditolak / timeout saat connect --
    // (Simulasi refused/timeout dari opener — TCP sungguhan tidak bisa
    // diuji di VM ini karena guard other_tcp; lihat header file.)
    val downErr = catchesAs(
        "1. Server mati (koneksi ditolak) -> error 'server tidak menjawab'",
        ConcatDownException::class.java
    ) {
        withFailingOpener(IOException("Connection refused (simulasi)")) {
            ConcatApi.version(FAKE_TOKEN)
        }
    }
    check(
        "1b. Pesan server mati = pesan baku (tanpa token)",
        downErr?.message == ConcatApi.DOWN_MESSAGE &&
            downErr.message?.contains(FAKE_TOKEN) == false
    )
    catchesAs(
        "1c. Connect timeout -> ConcatDownException juga",
        ConcatDownException::class.java
    ) {
        withFailingOpener(SocketTimeoutException("connect timed out (simulasi)")) {
            ConcatApi.version(FAKE_TOKEN)
        }
    }

    // -- 2. Auth + bentuk baris --
    val seenAuth = ArrayList<String>()
    val seenCall = ArrayList<String>()
    val methodsAuth = ArrayList<String>()
    val versionOut = withFake(handler = { server ->
        if (authOrRefuse(server, seenAuth) == null) return@withFake
        val call = readCall(server, seenCall, methodsAuth) ?: return@withFake
        replyResult(server, call, versionResult("0.2.10"))
    }) { ConcatApi.version(FAKE_TOKEN) }
    check("2. version() -> 'concat 0.2.10 / apiVersion 0.2'", versionOut == "concat 0.2.10 / apiVersion 0.2", versionOut)
    val authLine = try { JSONObject(seenAuth[0]) } catch (_: Exception) { JSONObject() }
    check(
        "3. Baris pertama = auth (id 1, token benar)",
        authLine.optString("jsonrpc") == "2.0" && authLine.optInt("id") == 1 &&
            authLine.optString("method") == "auth" &&
            authLine.optJSONObject("params")?.optString("token") == FAKE_TOKEN,
        seenAuth.joinToString()
    )
    val callLine = try { JSONObject(seenCall[0]) } catch (_: Exception) { JSONObject() }
    check(
        "4. Baris kedua = panggilan version (id 2, tanpa params)",
        callLine.optString("jsonrpc") == "2.0" && callLine.optInt("id") == 2 &&
            callLine.optString("method") == "version" && !callLine.has("params"),
        seenCall.joinToString()
    )

    // -- 3. version() baca angka dari server, bukan hafalan --
    val versionOld = withFake(handler = { server ->
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
        replyResult(server, call, versionResult("0.2.8"))
    }) { ConcatApi.version(FAKE_TOKEN) }
    check("5. version() 0.2.8 -> 'concat 0.2.8 / apiVersion 0.2'", versionOld == "concat 0.2.8 / apiVersion 0.2", versionOld)

    catchesAs("6. version() tanpa concat/apiVersion -> error jelas", ConcatApiException::class.java) {
        withFake(handler = { server ->
            if (authOrRefuse(server, ArrayList()) == null) return@withFake
            val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
            replyResult(server, call, JSONObject().put("concat", "0.2.10"))
        }) { ConcatApi.version(FAKE_TOKEN) }
    }

    // -- 4. Notifikasi + id salah + baris kosong dilewati --
    val versionAfterNoise = withFake(handler = { server ->
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
        server.sendLine(
            """{"jsonrpc":"2.0","method":"export.progress","params":{"job":"j1","path":"p","frame":1,"total":10,"stage":"video"}}"""
        )
        server.sendLine(
            """{"jsonrpc":"2.0","method":"export.progress","params":{"job":"j1","path":"p","frame":2,"total":10,"stage":"video"}}"""
        )
        server.sendLine("""{"jsonrpc":"2.0","id":99,"result":{"apiVersion":"9.9","concat":"9.9"}}""")
        server.sendLine("")
        replyResult(server, call, versionResult("0.2.10"))
    }) { ConcatApi.version(FAKE_TOKEN) }
    check(
        "7. Notifikasi (tanpa id) + id lain + baris kosong dilewati",
        versionAfterNoise == "concat 0.2.10 / apiVersion 0.2",
        versionAfterNoise
    )

    // -- 5. id null + error dihitung jawaban (auth) --
    val nullIdErr = catchesAs("8. id null + error -> ConcatAuthException", ConcatAuthException::class.java) {
        withFake(handler = { server ->
            server.readLine() // baris auth, tidak dijawab normal
            server.sendJson(
                JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
                    .put(
                        "error",
                        JSONObject().put("code", -32006).put("message", "wrong token")
                            .put("data", JSONObject().put("code", "unauthorized"))
                    )
            )
        }) { ConcatApi.version(FAKE_TOKEN) }
    }
    check("8b. Pesan id-null = 'wrong token' verbatim", nullIdErr?.message == "wrong token", nullIdErr?.message ?: "")

    // -- 6. Token salah --
    val wrongErr = catchesAs("9. Token salah -> ConcatAuthException", ConcatAuthException::class.java) {
        withFake(handler = { server ->
            authOrRefuse(server, ArrayList())
        }) { ConcatApi.version(WRONG_TOKEN) }
    }
    check("9b. Pesan token salah = 'wrong token' verbatim", wrongErr?.message == "wrong token", wrongErr?.message ?: "")
    check(
        "10. Pesan error token salah tidak berisi token",
        wrongErr?.message?.contains(WRONG_TOKEN) != true &&
            wrongErr?.message?.contains(FAKE_TOKEN) != true
    )

    // -- 7. Token kosong tetap dikirim, server yang menolak --
    val seenEmpty = ArrayList<String>()
    catchesAs("11. Token kosong -> ConcatAuthException", ConcatAuthException::class.java) {
        withFake(handler = { server ->
            authOrRefuse(server, seenEmpty)
        }) { ConcatApi.version("") }
    }
    val emptyAuth = try { JSONObject(seenEmpty[0]) } catch (_: Exception) { JSONObject() }
    check(
        "11b. Baris auth token kosong tetap terkirim",
        emptyAuth.optJSONObject("params")?.optString("token") == "",
        seenEmpty.joinToString()
    )

    // -- 8. Server diam (timeout baca) --
    // Antrean klien disetel timeout 300 ms: server diam -> klien menyerah cepat.
    val startedSilent = System.currentTimeMillis()
    val silentErr = catchesAs("12. Server diam -> ConcatApiException (timeout)", ConcatApiException::class.java) {
        withFake(pollTimeoutMs = 300, handler = { server ->
            if (authOrRefuse(server, ArrayList()) == null) return@withFake
            readCall(server, ArrayList(), ArrayList())
            Thread.sleep(5_000) // sengaja diam; klien harus menyerah
        }) { ConcatApi.version(FAKE_TOKEN) }
    }
    val silentElapsed = System.currentTimeMillis() - startedSilent
    check("12b. Timeout terjadi cepat (< 5 dtk, bukan gantung)", silentElapsed < 5_000, "elapsed=${silentElapsed}ms")
    check(
        "12c. Pesan timeout bahasa sehari-hari",
        silentErr?.message?.contains("tidak membalas") == true &&
            silentErr.message?.contains("mengecek versi") == true &&
            silentErr.message?.contains("15 detik") == true &&
            silentErr.message?.contains(FAKE_TOKEN) == false,
        silentErr?.message ?: ""
    )

    // -- 9. Server putus --
    val downAuthErr = catchesAs("13. Server putus sebelum auth -> ConcatDownException", ConcatDownException::class.java) {
        withFake(handler = { server ->
            server.readLine() // baca baris auth dulu, lalu tutup tanpa menjawab
            server.close()
        }) { ConcatApi.version(FAKE_TOKEN) }
    }
    check(
        "13b. Pesan putus sebelum masuk bahasa sehari-hari",
        downAuthErr?.message?.contains("putus sebelum sempat masuk") == true,
        downAuthErr?.message ?: ""
    )
    val eofCallErr = catchesAs("14. Server putus saat memanggil -> ConcatApiException", ConcatApiException::class.java) {
        withFake(handler = { server ->
            if (authOrRefuse(server, ArrayList()) == null) return@withFake
            readCall(server, ArrayList(), ArrayList())
            server.close() // tutup tanpa menjawab panggilan
        }) { ConcatApi.version(FAKE_TOKEN) }
    }
    check(
        "14b. Pesan putus saat memanggil bahasa sehari-hari",
        eofCallErr?.message?.contains("putus di tengah jalan") == true &&
            eofCallErr.message?.contains("mengecek versi") == true,
        eofCallErr?.message ?: ""
    )

    // -- 10. Baris terlalu besar + bukan JSON --
    val bigErr = catchesAs("15. Baris > 4 MiB ditolak", ConcatApiException::class.java) {
        withFake(handler = { server ->
            if (authOrRefuse(server, ArrayList()) == null) return@withFake
            readCall(server, ArrayList(), ArrayList())
            server.sendLine("a".repeat(4 * 1024 * 1024 + 100))
        }) { ConcatApi.version(FAKE_TOKEN) }
    }
    check("15b. Pesan baris besar bahasa sehari-hari", bigErr?.message?.contains("terlalu besar") == true, bigErr?.message ?: "")
    catchesAs("16. Baris bukan JSON -> ConcatApiException", ConcatApiException::class.java) {
        withFake(handler = { server ->
            if (authOrRefuse(server, ArrayList()) == null) return@withFake
            readCall(server, ArrayList(), ArrayList())
            server.sendLine("""{"jsonrpc":"2.0","id":2,"result":}""")
        }) { ConcatApi.version(FAKE_TOKEN) }
    }

    // -- 11. project.list --
    val listOut = withFake(handler = { server ->
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
        replyResult(
            server, call,
            JSONArray()
                .put(projectRef("Untitled project", "/p/A"))
                .put(projectRef("Proyek Liburan", "/p/B"))
        )
    }) { ConcatApi.listProjects(FAKE_TOKEN) }
    check(
        "17. listProjects() memetakan 2 proyek (nama+path, urutan sama)",
        listOut == listOf(ConcatProjectRef("Untitled project", "/p/A"), ConcatProjectRef("Proyek Liburan", "/p/B")),
        listOut.toString()
    )
    val listEmpty = withFake(handler = { server ->
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
        replyResult(server, call, JSONArray())
    }) { ConcatApi.listProjects(FAKE_TOKEN) }
    check("18. listProjects() kosong -> list kosong", listEmpty.isEmpty())
    catchesAs("19. listProjects() bukan array -> error jelas", ConcatApiException::class.java) {
        withFake(handler = { server ->
            if (authOrRefuse(server, ArrayList()) == null) return@withFake
            val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
            replyResult(server, call, JSONObject())
        }) { ConcatApi.listProjects(FAKE_TOKEN) }
    }
    val listPartial = withFake(handler = { server ->
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
        replyResult(
            server, call,
            JSONArray()
                .put(JSONObject().put("name", "Tanpa Path"))
                .put(JSONObject().put("path", "/p/tanpa-nama"))
                .put(projectRef("Lengkap", "/p/C"))
        )
    }) { ConcatApi.listProjects(FAKE_TOKEN) }
    check(
        "20. listProjects() lewati entri tanpa nama/path",
        listPartial == listOf(ConcatProjectRef("Lengkap", "/p/C")),
        listPartial.toString()
    )

    // -- 12. openProject: path langsung vs nama --
    val methodsDirect = ArrayList<String>()
    val openParamsDirect = ArrayList<JSONObject>()
    val openDirect = withFake(handler = { server ->
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call = readCall(server, ArrayList(), methodsDirect) ?: return@withFake
        openParamsDirect.add(call.optJSONObject("params") ?: JSONObject())
        replyResult(server, call, twoClipView())
    }) { ConcatApi.openProject(FAKE_TOKEN, "/storage/concat/Untitled project") }
    check("21. openProject(path) langsung — tanpa project.list", methodsDirect == listOf("project.open"), methodsDirect.toString())
    check(
        "21b. openProject(path) kirim path apa adanya",
        openParamsDirect.firstOrNull()?.optString("path") == "/storage/concat/Untitled project",
        openParamsDirect.toString()
    )
    check("21c. Hasil open berisi 'Dibuka:' + nama", openDirect.contains("Dibuka:") && openDirect.contains("Untitled project"), openDirect)

    val methodsByName = ArrayList<String>()
    val openParamsByName = ArrayList<JSONObject>()
    val openByName = withFake(handler = { server ->
        // Koneksi 1: project.list — selesaikan nama jadi path.
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call1 = readCall(server, ArrayList(), methodsByName) ?: return@withFake
        replyResult(server, call1, JSONArray().put(projectRef("Untitled project", "/p/Untitled")))
        // Koneksi 2: project.open — pakai path hasil list.
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call2 = readCall(server, ArrayList(), methodsByName) ?: return@withFake
        openParamsByName.add(call2.optJSONObject("params") ?: JSONObject())
        replyResult(server, call2, twoClipView())
    }) { ConcatApi.openProject(FAKE_TOKEN, "Untitled project") }
    check(
        "22. openProject(nama) -> list dulu, lalu open",
        methodsByName == listOf("project.list", "project.open"),
        methodsByName.toString()
    )
    check(
        "22b. openProject(nama) kirim path hasil list",
        openParamsByName.firstOrNull()?.optString("path") == "/p/Untitled",
        openParamsByName.toString()
    )
    check("22c. Hasil open(nama) berisi 'Dibuka:'", openByName.contains("Dibuka: Untitled project"), openByName)

    // -- 13. openProject: nama tidak ketemu --
    val unknownErr = catchesAs("23. openProject nama tidak ketemu -> error", ConcatApiException::class.java) {
        withFake(handler = { server ->
            if (authOrRefuse(server, ArrayList()) == null) return@withFake
            val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
            replyResult(
                server, call,
                JSONArray()
                    .put(projectRef("Untitled project", "/p/A"))
                    .put(projectRef("Proyek Liburan", "/p/B"))
            )
        }) { ConcatApi.openProject(FAKE_TOKEN, "Tidak Ada") }
    }
    check(
        "23b. Pesan nama tidak ketemu sebut nama + yang ada",
        unknownErr?.message?.contains("Tidak Ada") == true &&
            unknownErr.message?.contains("Untitled project") == true &&
            unknownErr.message?.contains("Proyek Liburan") == true &&
            unknownErr.message?.contains("tidak ketemu") == true,
        unknownErr?.message ?: ""
    )
    val unknownEmptyErr = catchesAs("24. openProject nama tidak ketemu, daftar kosong -> error", ConcatApiException::class.java) {
        withFake(handler = { server ->
            if (authOrRefuse(server, ArrayList()) == null) return@withFake
            val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
            replyResult(server, call, JSONArray())
        }) { ConcatApi.openProject(FAKE_TOKEN, "Tidak Ada") }
    }
    check(
        "24b. Pesan daftar kosong = 'belum ada proyek sama sekali'",
        unknownEmptyErr?.message?.contains("belum ada proyek sama sekali") == true,
        unknownEmptyErr?.message ?: ""
    )
    catchesAs("25. Nama proyek cocok persis (huruf besar/kecil beda)", ConcatApiException::class.java) {
        withFake(handler = { server ->
            if (authOrRefuse(server, ArrayList()) == null) return@withFake
            val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
            replyResult(server, call, JSONArray().put(projectRef("Untitled project", "/p/A")))
        }) { ConcatApi.openProject(FAKE_TOKEN, "untitled project") }
    }

    // -- 14. getProject --
    val methodsGetByName = ArrayList<String>()
    val getByName = withFake(handler = { server ->
        // Koneksi 1: project.list.
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call1 = readCall(server, ArrayList(), methodsGetByName) ?: return@withFake
        replyResult(server, call1, JSONArray().put(projectRef("Untitled project", "/p/Untitled")))
        // Koneksi 2: project.get.
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call2 = readCall(server, ArrayList(), methodsGetByName) ?: return@withFake
        replyResult(server, call2, twoClipView())
    }) { ConcatApi.getProject(FAKE_TOKEN, "Untitled project") }
    check(
        "26. getProject(nama) -> list dulu lalu get",
        methodsGetByName == listOf("project.list", "project.get"),
        methodsGetByName.toString()
    )
    check(
        "26b. Ringkasan get ada judul + total 4:28",
        getByName.contains("Proyek: Untitled project") && getByName.contains("4:28"),
        getByName
    )
    val methodsGetDirect = ArrayList<String>()
    withFake(handler = { server ->
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call = readCall(server, ArrayList(), methodsGetDirect) ?: return@withFake
        replyResult(server, call, twoClipView())
    }) { ConcatApi.getProject(FAKE_TOKEN, "/p/Untitled") }
    check("27. getProject(path) langsung — tanpa project.list", methodsGetDirect == listOf("project.get"), methodsGetDirect.toString())

    // -- 15. Lock + error lain --
    val lockErr = catchesAs("28. open ditolak lock -> ConcatApiException", ConcatApiException::class.java) {
        withFake(handler = { server ->
            if (authOrRefuse(server, ArrayList()) == null) return@withFake
            val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
            replyError(server, call, -32003, "refused", "/p/Untitled is open in the window")
        }) { ConcatApi.openProject(FAKE_TOKEN, "/p/Untitled") }
    }
    check(
        "28b. Pesan lock = pesan server + hint 'tutup dulu proyek di layar'",
        lockErr?.message?.contains("/p/Untitled is open in the window") == true &&
            lockErr.message?.contains("tutup dulu proyek di layar Concat, lalu coba lagi") == true,
        lockErr?.message ?: ""
    )
    val notLockErr = catchesAs("29. open ditolak bukan-lock -> ConcatApiException", ConcatApiException::class.java) {
        withFake(handler = { server ->
            if (authOrRefuse(server, ArrayList()) == null) return@withFake
            val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
            replyError(server, call, -32000, "failed", "could not read /p/X/concat.json: No such file")
        }) { ConcatApi.openProject(FAKE_TOKEN, "/p/X") }
    }
    check(
        "29b. Pesan bukan-lock verbatim, TANPA hint tutup layar",
        notLockErr?.message?.contains("could not read /p/X/concat.json") == true &&
            notLockErr.message?.contains("tutup dulu proyek") == false,
        notLockErr?.message ?: ""
    )
    val notOpenErr = catchesAs("30. get proyek belum dibuka -> ConcatApiException", ConcatApiException::class.java) {
        withFake(handler = { server ->
            if (authOrRefuse(server, ArrayList()) == null) return@withFake
            val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
            replyError(server, call, -32001, "notOpen", "/p/X is not open - open it first")
        }) { ConcatApi.getProject(FAKE_TOKEN, "/p/X") }
    }
    check(
        "30b. Pesan belum-dibuka verbatim",
        notOpenErr?.message == "/p/X is not open - open it first",
        notOpenErr?.message ?: ""
    )

    // -- 16. Isi ringkasan --
    val summary = withFake(handler = { server ->
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
        replyResult(server, call, twoClipView())
    }) { ConcatApi.getProject(FAKE_TOKEN, "/p/Untitled") }
    check(
        "31. Ringkasan: judul, frame, timeline, track, klip, total",
        summary.contains("Proyek: Untitled project") &&
            summary.contains("1080x1920 @ 30 fps") &&
            summary.contains("Timeline 1") &&
            summary.contains("4 track") &&
            summary.contains("2 klip") &&
            summary.contains("4:28"),
        summary
    )
    check(
        "31b. Ringkasan: klip ada nama + durasi ('2:30', '1:58')",
        summary.contains("LMC_NIL20260316_232520_0001.mp4 (2:30)") &&
            summary.contains("LMC_NIL20260316_232521_0002.mp4 (1:58)"),
        summary
    )
    check(
        "32. Ringkasan: tanpa dokumen mentah",
        !summary.contains("\"timelines\"") && !summary.contains("\"project\"") &&
            !summary.contains("\"canUndo\"") && !summary.contains("{"),
        summary
    )
    val shortSummary = withFake(handler = { server ->
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
        replyResult(server, call, shortClipsView())
    }) { ConcatApi.getProject(FAKE_TOKEN, "/p/Pendek") }
    check(
        "33. Ringkasan: durasi detik ditulis '12.5 dtk' / '12 dtk'",
        shortSummary.contains("klip-a.mp4 (12.5 dtk)") &&
            shortSummary.contains("klip-b.mp4 (12 dtk)") &&
            shortSummary.contains("klip-c.mp4 (45 dtk)"),
        shortSummary
    )

    // 200 klip -> ringkasan dipotong di ~2000 karakter.
    val manyClips = JSONArray()
    for (i in 1..200) {
        manyClips.put(clipJson("c$i", "Klip $i", (i - 1) * 5.0, 5.0))
    }
    val bigView = editorView("Proyek Besar", JSONArray().put(timelineJson("TL1", "Timeline 1", manyClips)), "TL1")
    val bigSummary = withFake(handler = { server ->
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
        replyResult(server, call, bigView)
    }) { ConcatApi.getProject(FAKE_TOKEN, "/p/Besar") }
    check("34. Ringkasan 200 klip -> panjang <= 2000", bigSummary.length <= 2000, "panjang=${bigSummary.length}")
    check("34b. Ringkasan 200 klip -> ada '+N klip lagi'", bigSummary.contains("klip lagi"), bigSummary.takeLast(120))

    // Timeline aktif (bukan yang pertama).
    val activeView = editorView(
        "Dua Timeline",
        JSONArray()
            .put(timelineJson("TL1", "Timeline 1", JSONArray().put(clipJson("c1", "Klip Lama", 0.0, 10.0))))
            .put(timelineJson("TL2", "Timeline 2", JSONArray().put(clipJson("c2", "Klip Aktif", 0.0, 20.0)))),
        "TL2"
    )
    val activeSummary = withFake(handler = { server ->
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
        replyResult(server, call, activeView)
    }) { ConcatApi.getProject(FAKE_TOKEN, "/p/Dua") }
    check(
        "35. Ringkasan pakai timeline AKTIF",
        activeSummary.contains("Timeline 2") && activeSummary.contains("Klip Aktif") &&
            !activeSummary.contains("Klip Lama"),
        activeSummary
    )

    // 0 klip.
    val emptyView = editorView("Kosong", JSONArray().put(timelineJson("TL1", "Timeline 1", JSONArray())), "TL1")
    val emptySummary = withFake(handler = { server ->
        if (authOrRefuse(server, ArrayList()) == null) return@withFake
        val call = readCall(server, ArrayList(), ArrayList()) ?: return@withFake
        replyResult(server, call, emptyView)
    }) { ConcatApi.getProject(FAKE_TOKEN, "/p/Kosong") }
    check(
        "36. Ringkasan 0 klip -> '0 klip', total '0 dtk'",
        emptySummary.contains("0 klip") && emptySummary.contains("total 0 dtk"),
        emptySummary
    )

    // -- 17. Nilai default + sumber klien --
    check(
        "37. Nilai default: connect 5 dtk, baca 15 dtk, baris maks 4 MiB, 127.0.0.1:7420",
        ConcatApi.CONNECT_TIMEOUT_MS == 5_000 && ConcatApi.READ_TIMEOUT_MS == 15_000 &&
            ConcatApi.MAX_LINE_BYTES == 4 * 1024 * 1024 &&
            ConcatApi.HOST == "127.0.0.1" && ConcatApi.PORT == 7420
    )
    val clientSource = try {
        File(treeDir, "ConcatApiClient.kt").readText()
    } catch (e: Exception) {
        ""
    }
    check(
        "38. Sumber klien: tanpa import android.* dan tanpa println",
        clientSource.isNotEmpty() &&
            clientSource.lines().none { it.trimStart().startsWith("import android") } &&
            !clientSource.contains("println("),
        if (clientSource.isEmpty()) "file tidak kebaca di $treeDir" else ""
    )
    check(
        "39. Sumber klien: signature kontrak ada semua",
        clientSource.contains("fun version(token: String): String") &&
            clientSource.contains("fun listProjects(token: String): List<ConcatProjectRef>") &&
            clientSource.contains("fun openProject(token: String, nameOrPath: String): String") &&
            clientSource.contains("fun getProject(token: String, nameOrPath: String): String") &&
            clientSource.contains("open class ConcatApiException") &&
            clientSource.contains("class ConcatAuthException") &&
            clientSource.contains("class ConcatDownException") &&
            clientSource.contains("data class ConcatProjectRef")
    )
    check(
        "40. Sumber klien: tidak ada token di dalamnya",
        clientSource.isNotEmpty() && !clientSource.contains(FAKE_TOKEN) &&
            !clientSource.contains("hsurr:"),
        if (clientSource.isEmpty()) "file tidak kebaca di $treeDir" else ""
    )
    check(
        "41. Sumber klien: TCP langsung tanpa proxy (jalur produksi)",
        clientSource.contains("Socket(Proxy.NO_PROXY)") &&
            clientSource.contains("openTcpConnection") &&
            clientSource.contains("connectionOpener"),
        "tidak ketemu Socket(Proxy.NO_PROXY) / openTcpConnection"
    )

    // -- 18. Cek akhir: tidak ada token di pesan error mana pun --
    check(
        "42. Semua pesan error: tidak ada token di dalamnya",
        allErrorMessages.none { it.contains(FAKE_TOKEN) || it.contains(WRONG_TOKEN) },
        allErrorMessages.filter { it.contains(FAKE_TOKEN) || it.contains(WRONG_TOKEN) }.toString()
    )

    println()
    println("HASIL: LULUS $passed / GAGAL $failed (total ${passed + failed})")
    if (failed > 0) println("Yang gagal: ${failedNames.joinToString(", ")}")
    exitProcess(if (failed == 0) 0 else 1)
}
