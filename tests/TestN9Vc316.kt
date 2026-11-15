// TestN9Vc316.kt — tes Worker N9 vc316 (fix audit 15 Nov: N9-A1, N9-A3,
// N9-C2, N9-B1/C1; N9-A2 TETAP). Logika murni (ToolRegistry, Tools set
// klasifikasi, ToolGate, ConcatTools, BrowserCdpClient) + assert sumber
// atas tree OVERLAY (merge/ + 3 file work-N9). File tes + runner BUKAN
// bagian APK (tinggal di work-N9/, di luar tree app — jangan disalin ke
// tree app).
//
// Jalankan: ./run-tests-vc316-N9.sh
// Keluar 0 = semua lulus; selain itu daftar GAGAL + exit 1.
//
// CATATAN JUJUR: MainActivity (Android UI: AlertDialog, Thread UI,
// runOnUiThread) TIDAK bisa dites perilakunya di JVM — yang dites hanya
// pure helper + assert sumber + kompilasi tree 0 error. Dialog konfirmasi
// khusus (teks, tombol Ya/Batal, fail-closed) + eksekusi Concat room di
// worker thread WAJIB uji perangkat (koordinator: tambah ke TES-VC316).
// Jalur handler DENGAN token juga tidak bisa dites di JVM (SecureStore
// butuh Android Keystore).
package com.senzwh.deepharness

import java.io.File
import kotlin.system.exitProcess
import org.json.JSONArray
import org.json.JSONObject

var passedN9 = 0
var failedN9 = 0
fun checkN9(name: String, ok: Boolean, detail: String = "") {
    if (ok) { passedN9++; println("LULUS  $name") }
    else { failedN9++; println("GAGAL  $name  $detail") }
}

fun readOrNullN9(dir: String, name: String): String? =
    try { File(dir, name).readText() } catch (e: Exception) { null }

fun countOccN9(hay: String, needle: String): Int {
    var n = 0; var idx = hay.indexOf(needle)
    while (idx >= 0) { n++; idx = hay.indexOf(needle, idx + needle.length) }
    return n
}

/** Potongan sumber dari kemunculan pertama startMarker sampai kemunculan
 *  pertama endMarker SESUDAH itu ("" bila tidak ketemu). */
fun regionN9(hay: String, startMarker: String, endMarker: String): String {
    val s = hay.indexOf(startMarker)
    if (s < 0) return ""
    val e = hay.indexOf(endMarker, s + startMarker.length)
    if (e < 0) return ""
    return hay.substring(s, e)
}

fun editArgs(op: String, project: String = "ProyekTes"): JSONObject =
    JSONObject().put("project", project).put("op", op).put("args", JSONObject())

fun batchArgs(vararg ops: String, project: String = "ProyekTes"): JSONObject {
    val cmds = JSONArray()
    for (op in ops) cmds.put(JSONObject().put("op", op).put("args", JSONObject()))
    return JSONObject().put("project", project).put("op", "batch")
        .put("args", JSONObject().put("commands", cmds))
}

fun main(args: Array<String>) {
    val dir = if (args.isNotEmpty()) args[0] else "."
    val main = readOrNullN9(dir, "MainActivity.kt")
    val cdp = readOrNullN9(dir, "BrowserCdpClient.kt")
    val handler = readOrNullN9(dir, "ConcatTools.kt")
    val tools = readOrNullN9(dir, "Tools.kt")
    val gate = readOrNullN9(dir, "ToolGate.kt")
    checkN9("0. sumber overlay terbaca (MainActivity/BrowserCdpClient/ConcatTools/Tools/ToolGate)",
        main != null && cdp != null && handler != null && tools != null && gate != null)
    if (main == null || cdp == null || handler == null || tools == null || gate == null) {
        println("OVERALL LULUS=$passedN9 GAGAL=$failedN9"); exitProcess(1)
    }

    // ================= 1. N9-A1 — debugging WebView mati =================
    checkN9("1.1 N9-A1: TIDAK ADA pemanggilan untuk menyalakan debugging di MainActivity",
        !main.contains("setWebContentsDebuggingEnabled(true)"))
    checkN9("1.2 N9-A1: TEPAT 1 pemanggilan pematian (jaring pengaman, tab terakhir)",
        countOccN9(main, "setWebContentsDebuggingEnabled(false)") == 1)
    checkN9("1.3 N9-A1: komentar vc316 N9-A1 ada di pembukaan + penutupan tab",
        main.contains("vc316 N9-A1") && countOccN9(main, "vc316 N9-A1") >= 2)
    checkN9("1.4 N9-A1: MainActivity TIDAK memakai BrowserCdpClient sama sekali (nol pemanggil)",
        !main.contains("BrowserCdpClient."))

    // ================= 2. N9-A3 — evaluate() terkunci =================
    checkN9("2.1 N9-A3: evaluate TERKUNCI secara default (flag false)",
        BrowserCdpClient.evaluateUnlockedVc316 == false)
    checkN9("2.2 N9-A3: evaluate() langsung ditolak (null) tanpa menyentuh socket",
        BrowserCdpClient.evaluate("1+1") == null)
    checkN9("2.3 N9-A3: evaluate() menolak ekspresi pembaca cookie juga",
        BrowserCdpClient.evaluate("document.cookie") == null)
    BrowserCdpClient.unlockEvaluateVc316()
    checkN9("2.4 N9-A3: unlockEvaluateVc316() membuka kunci (flag true)",
        BrowserCdpClient.evaluateUnlockedVc316 == true)
    checkN9("2.5 N9-A3: sesudah unlock, evaluate tetap null tanpa koneksi (fail-soft, bukan jalan)",
        BrowserCdpClient.evaluate("1+1") == null)
    checkN9("2.6 N9-A3: guard ada di sumber evaluate() (tolak sebelum socket)",
        cdp.contains("if (!evaluateUnlockedVc316) return null"))
    checkN9("2.7 N9-A3: connect() + captureScreenshotFull() TIDAK dihapus (modul utuh)",
        cdp.contains("fun connect(): Boolean") && cdp.contains("fun captureScreenshotFull(): ByteArray?"))
    checkN9("2.8 N9-A3: kunci hanya dibuka lewat unlockEvaluateVc316 (satu fungsi bernama)",
        cdp.contains("fun unlockEvaluateVc316()") && countOccN9(cdp, "unlockEvaluateVc316()") == 3)

    // ================= 3. N9-C2 — Concat room ke worker thread =================
    val roomReply = regionN9(main, "fun handleRoomReply", "fun healDiagnose")
    checkN9("3.1 N9-C2: region handleRoomReply ditemukan",
        roomReply.isNotBlank())
    val safeRegion = regionN9(roomReply, "RiskLevel.SAFE -> {", "RiskLevel.REVIEW -> {")
    checkN9("3.2 N9-C2: cabang SAFE room TIDAK memanggil Tools.execute langsung",
        safeRegion.isNotBlank() && !safeRegion.contains("Tools.execute("))
    checkN9("3.3 N9-C2: cabang SAFE room mendelegasikan ke runRoomSafeToolVc316",
        safeRegion.contains("runRoomSafeToolVc316(tool, args, callIdRoomVc316, t0RoomVc254)"))
    val runFn = regionN9(main, "fun runRoomSafeToolVc316(", "fun askRoomConcatConfirmVc316(")
    checkN9("3.4 N9-C2: runRoomSafeToolVc316 ada + routing Concat ke worker thread",
        runFn.contains("if (tVc316.startsWith(\"concat_\"))") &&
        runFn.contains("Thread {") && runFn.contains("isDaemon = true") &&
        runFn.contains("runOnUiThread") && runFn.contains("Tools.execute(this, tVc316, aVc316, callIdVc316)"))
    checkN9("3.5 N9-C2: resultId/totalChars ditangkap di thread eksekusi (ThreadLocal aman)",
        runFn.contains("Tools.lastResultId()") && runFn.contains("Tools.lastFullLength()") &&
        runFn.contains("finishRoomSafeToolVc316(tVc316, aVc316, resVc316, msVc316, callIdVc316, ridVc316, totVc316)"))
    checkN9("3.6 N9-C2: tool non-Concat tetap sinkron (tidak berubah)",
        runFn.contains("} else {") && runFn.contains("finishRoomSafeToolVc316(tVc316, aVc316, resVc316, msVc316, callIdVc316, Tools.lastResultId(), Tools.lastFullLength())"))
    val finishFn = regionN9(main, "fun finishRoomSafeToolVc316(", "fun runRoomSafeToolVc316(")
    checkN9("3.7 N9-C2: penuntasan SAFE utuh (jejak + StuckDetector + log + self-healing)",
        finishFn.contains("roomTurnWorkVc303") && finishFn.contains("roomStuckWatchesVc263") &&
        finishFn.contains("noteRoomReadVc303") && finishFn.contains("Automation.logAction") &&
        finishFn.contains("healCounts") && finishFn.contains("ActivityTitle.formatText"))
    checkN9("3.8 N9-C2: jalur 1-1 TIDAK diubah (daemon thread + timeout 120 dtk)",
        runFn.isNotBlank() && regionN9(main, "private fun executeToolWithTimeoutVc278", "private fun askConcatDestructiveConfirmVc316").contains("t.isDaemon = true") &&
        regionN9(main, "private fun executeToolWithTimeoutVc278", "private fun askConcatDestructiveConfirmVc316").contains("Tools.execute(this, tool, args)"))
    checkN9("3.9 N9-C2: batch room menolak WRITE Concat (jatuh ke jalur tunggal worker-thread)",
        main.contains("roomBatchHasConcatWriteVc316") &&
        main.contains("tVc274.startsWith(\"concat_\") && tVc274 !in Tools.READ_TOOLS") &&
        main.contains("!roomBatchHasConcatWriteVc316"))

    // ================= 4. N9-B1/C1 — konfirmasi khusus =================
    val destructiveOps = listOf("removeMedia", "removeClips", "removeTrack", "removeTimeline",
        "removeFont", "clearClipKeys", "clearEffectKeys", "clearMaskKeys",
        "clearTextStyles", "mergeClips", "consolidateClips")
    var allDestructive = true
    for (op in destructiveOps) {
        if (!ConcatTools.requiresSpecialConfirmVc316("concat_edit_apply", editArgs(op))) allDestructive = false
    }
    checkN9("4.1 B1/C1: ke-11 op destruktif edit_apply minta konfirmasi khusus", allDestructive)
    checkN9("4.2 B1/C1: denylist berisi TEPAT 11 op (tanpa clearClipKey tunggal / addClip / matchClip)",
        ConcatTools.DESTRUCTIVE_EDIT_OPS_VC316.size == 11 &&
        ConcatTools.DESTRUCTIVE_EDIT_OPS_VC316.containsAll(destructiveOps) &&
        !ConcatTools.DESTRUCTIVE_EDIT_OPS_VC316.contains("clearClipKey") &&
        !ConcatTools.DESTRUCTIVE_EDIT_OPS_VC316.contains("addClip") &&
        !ConcatTools.DESTRUCTIVE_EDIT_OPS_VC316.contains("matchClip"))
    checkN9("4.3 B1: concat_project_open SELALU minta konfirmasi khusus",
        ConcatTools.requiresSpecialConfirmVc316("concat_project_open", JSONObject().put("project", "P1")))
    checkN9("4.4 C1: concat_project_close save=false minta konfirmasi; save=true TIDAK",
        ConcatTools.requiresSpecialConfirmVc316("concat_project_close", JSONObject().put("project", "P1").put("save", false)) &&
        ConcatTools.requiresSpecialConfirmVc316("concat_project_close", JSONObject().put("project", "P1")) &&
        !ConcatTools.requiresSpecialConfirmVc316("concat_project_close", JSONObject().put("project", "P1").put("save", true)))
    checkN9("4.5 C1: batch berisi op destruktif minta konfirmasi (termasuk batch bersarang)",
        ConcatTools.requiresSpecialConfirmVc316("concat_edit_apply", batchArgs("addClip", "removeClips")) &&
        ConcatTools.requiresSpecialConfirmVc316("concat_edit_apply",
            JSONObject().put("project", "P1").put("op", "batch").put("args",
                JSONObject().put("commands", JSONArray().put(
                    JSONObject().put("op", "batch").put("args",
                        JSONObject().put("commands", JSONArray().put(
                            JSONObject().put("op", "removeTrack").put("args", JSONObject())))))))))
    checkN9("4.6 C1: batch berisi op aman saja TIDAK minta konfirmasi",
        !ConcatTools.requiresSpecialConfirmVc316("concat_edit_apply", batchArgs("addClip", "trimClip", "splitClips")))
    val nonDestructiveWrite = listOf("concat_project_create", "concat_project_save", "concat_project_set_video",
        "concat_edit_undo", "concat_edit_redo", "concat_media_import", "concat_template_instantiate",
        "concat_template_save", "concat_export_run", "concat_export_cancel")
    var noFalsePositiveWrite = true
    for (t in nonDestructiveWrite) {
        if (ConcatTools.requiresSpecialConfirmVc316(t, JSONObject().put("project", "P1").put("name", "X").put("file", "/sdcard/x.mp4").put("job", "j1"))) noFalsePositiveWrite = false
    }
    checkN9("4.7 B1/C1: WRITE non-destruktif TIDAK minta konfirmasi ini (10 tool)", noFalsePositiveWrite)
    val readTools = listOf("concat_version", "concat_project_list", "concat_project_get",
        "concat_media_probe", "concat_catalogue_list", "concat_template_list",
        "concat_export_status", "concat_preview_frame")
    var noFalsePositiveRead = true
    for (t in readTools) {
        if (ConcatTools.requiresSpecialConfirmVc316(t, JSONObject().put("project", "P1").put("file", "/sdcard/x.mp4").put("job", "j1"))) noFalsePositiveRead = false
    }
    checkN9("4.8 B1/C1: semua READ Concat TIDAK minta konfirmasi ini (8 tool)", noFalsePositiveRead)
    checkN9("4.9 B1/C1: op edit non-destruktif + op tak dikenal TIDAK minta konfirmasi",
        !ConcatTools.requiresSpecialConfirmVc316("concat_edit_apply", editArgs("addClip")) &&
        !ConcatTools.requiresSpecialConfirmVc316("concat_edit_apply", editArgs("trimClip")) &&
        !ConcatTools.requiresSpecialConfirmVc316("concat_edit_apply", editArgs("clearClipKey")) &&
        !ConcatTools.requiresSpecialConfirmVc316("concat_edit_apply", editArgs("clearEffectKey")) &&
        !ConcatTools.requiresSpecialConfirmVc316("concat_edit_apply", editArgs("clearMaskKey")) &&
        !ConcatTools.requiresSpecialConfirmVc316("concat_edit_apply", editArgs("opTidakAda")) &&
        !ConcatTools.requiresSpecialConfirmVc316("concat_edit_apply", JSONObject().put("project", "P1")) &&
        !ConcatTools.requiresSpecialConfirmVc316("tool_tidak_ada", JSONObject()))
    checkN9("4.10 B1/C1: teks dialog menyebut proyek + op destruktif; non-destruktif -> kosong",
        ConcatTools.describeDestructiveConcatCallVc316("concat_project_open", JSONObject().put("project", "ProyekTes")).contains("ProyekTes") &&
        ConcatTools.describeDestructiveConcatCallVc316("concat_project_close", JSONObject().put("project", "ProyekTes")).contains("DIBUANG") &&
        ConcatTools.describeDestructiveConcatCallVc316("concat_edit_apply", editArgs("removeClips")).contains("removeClips") &&
        ConcatTools.describeDestructiveConcatCallVc316("concat_edit_apply", editArgs("removeClips")).contains("ProyekTes") &&
        ConcatTools.describeDestructiveConcatCallVc316("concat_edit_apply", batchArgs("addClip", "mergeClips")).contains("mergeClips") &&
        ConcatTools.describeDestructiveConcatCallVc316("concat_project_save", JSONObject().put("project", "P1")).isBlank() &&
        ConcatTools.describeDestructiveConcatCallVc316("concat_version", JSONObject()).isBlank())
    checkN9("4.11 B1/C1: cek konfirmasi 1-1 ada SEBELUM early-return gerbang mati di proceedTool",
        countOccN9(main, "if (ToolGate.GATES_DISABLED && ConcatTools.requiresSpecialConfirmVc316(tool, args))") == 2 &&
        regionN9(main, "private fun proceedTool", "if (ToolGate.GATES_DISABLED) { execToolAndContinue(tool, args, callId); return }").contains("askConcatDestructiveConfirmVc316(tool, args, callId)"))
    val dlg11 = regionN9(main, "private fun askConcatDestructiveConfirmVc316", "private fun execToolAndContinue")
    checkN9("4.12 B1/C1: dialog 1-1 = Ya/Batal, tanpa PIN, tanpa autoAllow, fail-closed",
        dlg11.contains("setTitle(\"Jalankan perintah Concat yang merusak?\")") &&
        dlg11.contains("setPositiveButton(\"Ya, jalankan\")") &&
        dlg11.contains("setNegativeButton(\"Batal\")") &&
        dlg11.contains("setCancelable(false)") &&
        dlg11.contains("execToolAndContinue(tool, args, callId)") &&
        !dlg11.contains("EditText") && !dlg11.contains("ApprovalPanelStore") &&
        !dlg11.contains("grantAutoAllow") && !dlg11.contains("autoAllowValid") &&
        dlg11.contains("TIDAK dijalankan"))
    val dlgRoom = regionN9(main, "fun askRoomConcatConfirmVc316(", "val blocked = ArrayList<String>()")
    checkN9("4.13 B1/C1: dialog room = Ya/Batal, fail-closed, Ya -> worker thread (bukan eksekusi langsung)",
        dlgRoom.contains("setTitle(\"Jalankan perintah Concat yang merusak di room?\")") &&
        dlgRoom.contains("setPositiveButton(\"Ya, jalankan\")") &&
        dlgRoom.contains("setNegativeButton(\"Batal\")") &&
        dlgRoom.contains("setCancelable(false)") &&
        dlgRoom.contains("runRoomSafeToolVc316(toolVc316, argsVc316, callIdVc316, t0Vc316)") &&
        !dlgRoom.contains("Tools.execute(") && !dlgRoom.contains("EditText"))
    checkN9("4.14 B1/C1: cabang SAFE room mengecek konfirmasi SEBELUM eksekusi",
        safeRegion.contains("if (ToolGate.GATES_DISABLED && ConcatTools.requiresSpecialConfirmVc316(tool, args))") &&
        safeRegion.contains("askRoomConcatConfirmVc316(roomSub, agentName, tool, args, effSess, roomNow, callIdRoomVc316, t0RoomVc254)"))
    checkN9("4.15 B1/C1: batch 1-1 menolak batch berisi op destruktif (useBatchVc273)",
        regionN9(main, "private fun useBatchVc273", "private data class BatchOutcomeVc273").contains("requiresSpecialConfirmVc316"))
    checkN9("4.16 B1/C1: batch room menolak batch berisi op destruktif",
        main.contains("roomBatchHasConcatConfirmVc316") && main.contains("!roomBatchHasConcatConfirmVc316"))

    // ================= 5. N9-A2 TETAP — browser keluar dari ToolGate =================
    checkN9("5.1 N9-A2: RISKY_TOOLS TIDAK berisi satu pun tool browser_*",
        Tools.RISKY_TOOLS.none { it.startsWith("browser_") })
    val browserTools = ToolRegistry.ALL.filter { it.name.startsWith("browser_") }.map { it.name }
    checkN9("5.2 N9-A2: tool browser_* ADA di registry (" + browserTools.size + ") dan semuanya di luar RISKY_TOOLS",
        browserTools.isNotEmpty() && browserTools.none { it in Tools.RISKY_TOOLS })
    checkN9("5.3 N9-A2: pengecualian K-03 TIDAK diperluas ke Concat (open tetap RISKY)",
        "concat_project_open" in Tools.RISKY_TOOLS)
    checkN9("5.4 N9-A2: rumus RISKY_TOOLS di sumber tidak berubah (WRITE+MEMORY minus browser_*)",
        tools.contains("ToolRegistry.namesByKind(ToolRegistry.Kind.WRITE)") &&
        tools.contains("it.name.startsWith(\"browser_\")"))
    checkN9("5.5 N9-A2: ToolGate.kt TIDAK disentuh (tidak ada 'concat' di dalamnya)",
        !gate.contains("concat"))

    // ================= 6. Gerbang tetap MATI =================
    checkN9("6.1 Gerbang tetap GATES_DISABLED=true",
        ToolGate.GATES_DISABLED == true)
    checkN9("6.2 Gerbang mati -> level() Concat WRITE = SAFE (konfirmasi khusus satu-satunya penjaga)",
        ToolGate.level("concat_project_open") == RiskLevel.SAFE)
    checkN9("6.3 Gerbang mati -> isRisky() Concat WRITE = false",
        Tools.isRisky("concat_project_open") == false)

    println("OVERALL LULUS=$passedN9 GAGAL=$failedN9")
    if (failedN9 > 0) exitProcess(1)
}
