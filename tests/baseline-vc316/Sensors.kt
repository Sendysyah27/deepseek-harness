package com.senzwh.deepharness

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class NotifListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val ex = sbn.notification.extras
            val title = ex.getCharSequence("android.title")?.toString() ?: ""
            val text = ex.getCharSequence("android.text")?.toString() ?: ""
            val line = (title + " — " + text).trim().take(500)
            if (line.isNotBlank() && sbn.packageName != packageName) {
                EventDb.get(this).add("notif", sbn.packageName, line)
                lastNotifs.add(0, "${sbn.packageName}: $line")
                while (lastNotifs.size > 50) lastNotifs.removeAt(lastNotifs.size - 1)
                parseFinance(sbn.packageName, title + " " + text, line)
                fireRules(sbn.packageName, title + " " + text)
            }
        } catch (e: Exception) {}
    }

    /** Parser keuangan: notif berisi nominal Rp + kata transaksi -> ledger lokal. */
    private fun parseFinance(pkg: String, full: String, line: String) {
        try {
            if (!full.contains("Rp")) return
            val m = Regex("Rp\\.?\\s*([\\d\\.]{4,})").find(full) ?: return
            val amount = m.groupValues[1].replace(".", "").toLongOrNull() ?: return
            if (amount < 1000) return
            val low = full.lowercase()
            val masukKeys = listOf("masuk", "diterima", "telah diterima", "kredit", "bertambah", "top up berhasil", "refund")
            val keluarKeys = listOf("keluar", "dibayar", "pembayaran", "debit", "terdebet", "terpotong", "belanja", "kirim uang", "transfer berhasil", "pembelian")
            val kind = when {
                masukKeys.any { low.contains(it) } && !keluarKeys.any { low.contains(it) } -> "MASUK"
                keluarKeys.any { low.contains(it) } && !masukKeys.any { low.contains(it) } -> "KELUAR"
                else -> return
            }
            MemoryDb.get(this).addFinance(pkg, amount, kind, line)
        } catch (e: Exception) {}
    }

    /** Rules engine: notif cocok dengan aturan -> antrekan aksi + beri tahu user. */
    private fun fireRules(pkg: String, full: String) {
        try {
            val rules = MemoryDb.get(this).rules()
            if (rules.isEmpty()) return
            val prefs = getSharedPreferences("harness", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val low = full.lowercase()
            for (r in rules) {
                val pkgOk = r.pkgContains.isBlank() || pkg.contains(r.pkgContains, ignoreCase = true)
                val txtOk = r.textContains.isBlank() || low.contains(r.textContains.lowercase())
                if (!pkgOk || !txtOk) continue
                val lastKey = "ruleFire${r.id}"
                if (now - prefs.getLong(lastKey, 0) < 5 * 60_000) continue
                prefs.edit().putLong(lastKey, now).apply()
                val rulePrompt = r.actionPrompt + "\n\n(Konteks pemicu: notifikasi dari $pkg: ${full.take(240)})"
                val rStarted = BgBrain.submit(this, rulePrompt, "aturan")
                if (!rStarted) MemoryDb.enqueuePrompt(this, rulePrompt)
                alertLocal("⚡ Aturan terpicu", if (rStarted) "Aksi dijalankan Otak Latar: ${r.actionPrompt}" else "Aksi diantrekan: ${r.actionPrompt}")
                EventDb.get(this).add("rule", pkg, "Aturan #${r.id} terpicu oleh notif: ${full.take(160)}")
            }
        } catch (e: Exception) {}
    }

    private fun alertLocal(title: String, text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("harness-alert", "Pengingat & briefing", NotificationManager.IMPORTANCE_HIGH))
            // vc265 N2-m4: ID tetap 7364 untuk aturan terpicu (tanpa acak) —
            // di luar rentang alert() HarnessService (7300..7363) dan di luar
            // registry NotifIds; pemicu berikutnya menimpa yang sebelumnya.
            val nidRule = 7364
            val pi = PendingIntent.getActivity(
                this, nidRule,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(this, "harness-alert")
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(nidRule, n)
        } catch (e: Exception) {}
    }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance == this) instance = null }

    /** Balas notifikasi chat terakhir (WA/TG/dll) yang mendukung quick-reply RemoteInput. */
    fun replyToLatest(text: String, pkgHint: String): String {
        return try {
            val actives = activeNotifications ?: return "Tidak ada notifikasi aktif terbaca."
            val sorted = actives.sortedByDescending { it.postTime }
            for (sbn in sorted) {
                if (pkgHint.isNotBlank() && !sbn.packageName.contains(pkgHint, ignoreCase = true)) continue
                val actions = sbn.notification.actions ?: continue
                for (a in actions) {
                    val ris = a.remoteInputs ?: continue
                    if (ris.isEmpty()) continue
                    val ri = ris[0]
                    val fill = Intent()
                    val b = Bundle()
                    b.putCharSequence(ri.resultKey, text)
                    RemoteInput.addResultsToIntent(arrayOf(ri), fill, b)
                    a.actionIntent.send(this, 0, fill)
                    return "Balasan terkirim ke ${sbn.packageName}: \"$text\""
                }
            }
            "Tidak ditemukan notifikasi yang bisa dibalas" + if (pkgHint.isNotBlank()) " untuk paket mengandung '$pkgHint'." else "."
        } catch (e: Exception) { "Gagal membalas notifikasi: ${e.message}" }
    }

    companion object {
        val lastNotifs = ArrayList<String>()
        @Volatile var instance: NotifListener? = null
    }
}

/** v4.14.0 P2: satu elemen layar dari snapshot terstruktur. `path` =
 * indeks anak dari root — dipakai menyelesaikan ulang node yang sama di
 * pohon live saat tap_ref/set_text_ref dipanggil. Teks field password
 * TIDAK pernah disimpan (password=true, text kosong). */
data class NodeRef(
    val ref: Int, val path: List<Int>, val text: String, val desc: String,
    val cls: String, val viewId: String, val cx: Int, val cy: Int,
    val clickable: Boolean, val editable: Boolean, val password: Boolean
)

/** vc307: anggaran satu kali walk snapshot — cap node, depth, dan
 * time-budget. Murni Kotlin (jam bisa di-inject) supaya bisa diuji di
 * JVM tanpa pohon AccessibilityNodeInfo sungguhan. */
internal class WalkBudget(
    val deadlineMs: Long,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    val maxNodes: Int = A11yService.SNAPSHOT_MAX_COLLECT_NODES,
    val maxDepth: Int = A11yService.SNAPSHOT_MAX_DEPTH
) {
    var visited: Int = 0
        private set

    /** true bila node di depth ini masih boleh dikunjungi (di bawah
     * cap depth/node/waktu); false berarti walk harus berhenti. */
    fun allowVisit(depth: Int): Boolean {
        if (depth > maxDepth) return false
        if (visited >= maxNodes) return false
        if (nowMs() > deadlineMs) return false
        visited++
        return true
    }
}

/** vc307: penjadwal walk snapshot — debounce + coalesce + "lewati bila
 * sedang berjalan". Murni Kotlin (jam + executor bisa di-inject)
 * supaya bisa diuji di JVM tanpa AccessibilityService sungguhan.
 *
 * Kontrak:
 * - request(): minta satu walk. Paling cepat 1x per minIntervalMs
 *   sejak walk terakhir selesai; request yang datang saat walk berjalan
 *   digabung jadi SATU walk susulan (pending); request dalam jeda
 *   digabung jadi SATU walk tertunda (delayedScheduled).
 * - runNow(): jalankan walk SEKARANG di thread pemanggil (jalur
 *   sinkron pemanggil latar); bila walk lain sedang jalan, tandai
 *   pending dan kembali TANPA menunggu — pemanggil tidak pernah
 *   di-block oleh walk lain.
 * - walkCount/walkThreadName: diagnostik + tes (thread walk terakhir). */
internal class SnapshotScheduler(
    private val minIntervalMs: Long = A11yService.SNAPSHOT_MIN_INTERVAL_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val post: (Runnable) -> Unit = { r -> Thread(r, "a11y-snapshot").apply { isDaemon = true }.start() },
    private val postDelayed: (Runnable, Long) -> Unit = { r, ms -> Thread { Thread.sleep(ms); r.run() }.apply { isDaemon = true }.start() },
    private val walk: () -> Unit = {}
) {
    private val lock = Any()

    @Volatile var running: Boolean = false
        private set
    @Volatile var pending: Boolean = false
        private set
    @Volatile var delayedScheduled: Boolean = false
        private set
    @Volatile var lastEndMs: Long = 0
        private set
    @Volatile var walkCount: Int = 0
        private set
    @Volatile var walkThreadName: String? = null
        private set

    fun request() {
        val now = nowMs()
        var delayMs = -1L
        synchronized(lock) {
            if (running) { pending = true; return }
            val elapsed = now - lastEndMs
            if (elapsed < minIntervalMs) {
                if (delayedScheduled) return
                delayedScheduled = true
                delayMs = minIntervalMs - elapsed
            } else {
                delayMs = 0
            }
        }
        try {
            if (delayMs == 0L) post(Runnable { runWalkGuarded() })
            else postDelayed(Runnable {
                synchronized(lock) { delayedScheduled = false }
                runWalkGuarded()
            }, delayMs)
        } catch (e: Exception) {
            synchronized(lock) { delayedScheduled = false }
        }
    }

    fun runNow() {
        synchronized(lock) {
            if (running) { pending = true; return }
            running = true
        }
        runWalkBody()
    }

    private fun runWalkGuarded() {
        synchronized(lock) {
            if (running) { pending = true; return }
            running = true
        }
        runWalkBody()
    }

    private fun runWalkBody() {
        try {
            walkCount++
            walkThreadName = Thread.currentThread().name
            walk()
        } finally {
            var again = false
            synchronized(lock) {
                running = false
                lastEndMs = nowMs()
                if (pending) { pending = false; again = true }
            }
            if (again) request()
        }
    }
}

class A11yService : AccessibilityService() {

    // vc307 ANR fix (ANR 2026-11-13 14:16:09, ganti tema ke cerah):
    // refreshSnapshot() lama jalan SINKRON di main thread — tiap getChild
    // = IPC sinkron, tanpa cap node/depth/waktu; ganti tema me-rebuild
    // seluruh tree chat + banjir TYPE_WINDOW_STATE_CHANGED -> main thread
    // ke-block >5 dtk. Sekarang onAccessibilityEvent HANYA menjadwalkan
    // (scheduleSnapshot -> SnapshotScheduler.request) di executor
    // single-thread; walk TIDAK PERNAH jalan di main thread. Pemanggil
    // latar (Tools: get_screen/wait_change/wait_for_app/guiBlockReason)
    // tetap sinkron lewat refreshSnapshot() — mereka jalan di thread
    // agen dan membaca lastNodes/lastScreenText tepat sesudah panggilan
    // kembali, jadi perilakunya tidak berubah (lihat laporan vc307).
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (pkg != currentPkg) {
                    currentPkg = pkg
                    EventDb.get(this).add("app", pkg, "App aktif: $pkg")
                }
                // vc307: JANGAN walk di sini — main thread. Hanya jadwalkan.
                scheduleSnapshot()
            }
        } catch (e: Exception) {}
    }

    override fun onInterrupt() {}

    /** Apakah thread saat ini main thread service (tempat
     * onAccessibilityEvent/onServiceConnected jalan). */
    fun isMainThread(): Boolean {
        val mt = mainThread
        if (mt != null) return Thread.currentThread() == mt
        return try { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() } catch (e: Exception) { false }
    }

    /** Minta satu walk snapshot di executor latar (debounce ~500 ms +
     * coalesce). Aman dipanggil dari main thread. */
    fun scheduleSnapshot() {
        try { snapshotScheduler.request() } catch (e: Exception) {}
    }

    /** Snapshot layar ke lastScreenText/lastNodes.
     * vc307: dari main thread HANYA menjadwalkan walk di executor latar
     * (tidak pernah block main thread -> ANR); dari thread latar jalan
     * sinkron seperti dulu, supaya get_screen/wait_change/wait_for_app/
     * guiBlockReason yang membaca hasil tepat sesudah panggilan ini
     * tidak berubah perilakunya. */
    fun refreshSnapshot() {
        if (isMainThread()) { scheduleSnapshot(); return }
        try { snapshotScheduler.runNow() } catch (e: Exception) {}
    }

    /** Isi walk lama refreshSnapshot() + cap node/depth/waktu vc307.
     * Berjalan di executor latar atau thread pemanggil latar — TIDAK
     * PERNAH di main thread. Dipanggil lewat SnapshotScheduler.walk. */
    private fun doSnapshotWalk() {
        try {
            val root = rootInActiveWindow ?: return
            val deadline = System.currentTimeMillis() + SNAPSHOT_TIME_BUDGET_MS
            val sb = StringBuilder()
            collect(root, sb, 0, WalkBudget(deadline))
            lastScreenText = sb.toString().take(3000)
            lastScreenPkg = root.packageName?.toString() ?: currentPkg
            lastSnapshotTs = System.currentTimeMillis()
            try {
                val nodes = ArrayList<NodeRef>()
                collectNodes(root, ArrayList(), nodes, 0, WalkBudget(deadline, maxNodes = SNAPSHOT_MAX_VISIT_NODES))
                lastNodes = nodes
                lastNodesTs = System.currentTimeMillis()
            } catch (e: Exception) {}
        } catch (e: Exception) {}
    }

    private fun collect(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int, budget: WalkBudget) {
        if (node == null || sb.length > 3000) return
        if (!budget.allowVisit(depth)) return
        val t = node.text?.toString() ?: node.contentDescription?.toString()
        if (!t.isNullOrBlank()) sb.append(t).append(" | ")
        for (i in 0 until node.childCount) collect(node.getChild(i), sb, depth + 1, budget)
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, path: ArrayList<Int>, out: ArrayList<NodeRef>, depth: Int, budget: WalkBudget) {
        if (node == null || out.size >= SNAPSHOT_MAX_NODES) return
        if (!budget.allowVisit(depth)) return
        try {
            val isPw = try { node.isPassword } catch (e: Exception) { false }
            val t = if (isPw) "" else (node.text?.toString() ?: "")
            val d = node.contentDescription?.toString() ?: ""
            val click = node.isClickable
            val edit = node.isEditable
            if (node.isVisibleToUser && (click || edit || t.isNotBlank() || d.isNotBlank())) {
                val r = android.graphics.Rect()
                try { node.getBoundsInScreen(r) } catch (e: Exception) {}
                out.add(NodeRef(out.size + 1, ArrayList(path), t.take(80), d.take(80),
                    (node.className?.toString() ?: "").substringAfterLast('.'),
                    node.viewIdResourceName ?: "", (r.left + r.right) / 2, (r.top + r.bottom) / 2,
                    click, edit, isPw))
            }
        } catch (e: Exception) {}
        for (i in 0 until node.childCount) {
            path.add(i)
            collectNodes(node.getChild(i), path, out, depth + 1, budget)
            path.removeAt(path.size - 1)
            if (out.size >= SNAPSHOT_MAX_NODES) return
        }
    }

    /** Selesaikan ref snapshot ke node live lewat path; verifikasi kasar
     * teks/bounds supaya pohon yang sudah berubah ditolak dengan jujur. */
    fun resolveNode(ref: Int): Pair<NodeRef, AccessibilityNodeInfo?> {
        val stored = lastNodes.firstOrNull { it.ref == ref }
            ?: return NodeRef(ref, emptyList(), "", "", "", "", 0, 0, false, false, false) to null
        val root = rootInActiveWindow ?: return stored to null
        var cur: AccessibilityNodeInfo? = root
        for (idx in stored.path) {
            cur = try { cur?.getChild(idx) } catch (e: Exception) { null } ?: return stored to null
        }
        val node = cur ?: return stored to null
        val liveT = try { if (node.isPassword) "" else (node.text?.toString() ?: "") } catch (e: Exception) { "" }
        val liveD = try { node.contentDescription?.toString() ?: "" } catch (e: Exception) { "" }
        if ((stored.text.isNotBlank() || stored.desc.isNotBlank()) && liveT != stored.text && liveD != stored.desc) return stored to null
        return stored to node
    }

    fun clickRef(ref: Int): String {
        val (stored, node) = resolveNode(ref)
        if (stored.path.isEmpty() && stored.text.isBlank() && stored.desc.isBlank()) return "Ref #$ref tidak dikenal — ambil get_screen dulu untuk daftar elemen terbaru."
        if (node == null) return "Ref #$ref tidak bisa diselesaikan: layar sudah berubah sejak snapshot. Ambil get_screen lagi."
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 5) {
            try {
                if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tap ref #$ref: OK (klik node '${stored.text.ifBlank { stored.desc }}')"
            } catch (e: Exception) {}
            cur = try { cur.parent } catch (e: Exception) { null }
            hops++
        }
        return if (tap(stored.cx.toFloat(), stored.cy.toFloat())) "Tap ref #$ref: OK (tap koordinat tengah elemen ${stored.cx},${stored.cy})"
        else "Tap ref #$ref: GAGAL (node tidak klikabel & gesture gagal)."
    }

    fun setTextRef(ref: Int, text: String): String {
        val (stored, node) = resolveNode(ref)
        if (stored.path.isEmpty() && stored.text.isBlank() && stored.desc.isBlank()) return "Ref #$ref tidak dikenal — ambil get_screen dulu untuk daftar elemen terbaru."
        if (stored.password) return "Ref #$ref adalah field PASSWORD — agen tidak boleh mengisi password. Minta user mengisinya manual."
        if (node == null) return "Ref #$ref tidak bisa diselesaikan: layar sudah berubah sejak snapshot. Ambil get_screen lagi."
        try {
            val b = Bundle()
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return "Isi teks ref #$ref: OK"
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return "Isi teks ref #$ref: OK (sesudah fokus)"
        } catch (e: Exception) {}
        return "Isi teks ref #$ref: GAGAL (elemen tidak menerima ACTION_SET_TEXT)."
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path(); path.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        return dispatchGesture(g, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path(); path.moveTo(x1, y1); path.lineTo(x2, y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
        return dispatchGesture(g, null, null)
    }

    // vc262 H-M11: apakah kolom input yang sedang fokus adalah password.
    fun passwordFocused(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            try { focus.isPassword } catch (e: Exception) { false }
        } catch (e: Exception) { false }
    }

    fun typeText(text: String): Boolean {
        try {
            val root = rootInActiveWindow ?: return false
            val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            // vc262 H-M11: jangan pernah isi kolom password (cerminan set_text_ref).
            if (try { focus.isPassword } catch (e: Exception) { false }) return false
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) { return false }
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Screenshot layar via AccessibilityService.takeScreenshot (API 30+). PNG bytes / null. */
    fun screenshotPng(): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val latch = CountDownLatch(1)
            var out: ByteArray? = null
            takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val hb = result.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            if (bmp != null) {
                                val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                                val bos = ByteArrayOutputStream()
                                copy.compress(Bitmap.CompressFormat.PNG, 100, bos)
                                out = bos.toByteArray()
                                bmp.recycle(); copy.recycle()
                            }
                            hb.close()
                        } catch (e: Exception) {}
                        latch.countDown()
                    }
                    override fun onFailure(errorCode: Int) { latch.countDown() }
                })
            latch.await(6, TimeUnit.SECONDS)
            out
        } catch (e: Exception) { null }
    }

    companion object {
        @Volatile var instance: A11yService? = null
        @Volatile var currentPkg: String = ""
        @Volatile var lastScreenText: String = ""
        @Volatile var lastScreenPkg: String = ""
        @Volatile var lastSnapshotTs: Long = 0
        @Volatile var lastNodes: List<NodeRef> = emptyList()
        @Volatile var lastNodesTs: Long = 0

        // ---- vc307: batas snapshot (ANR ganti tema 2026-11-13) ----
        /** Jeda minimum antar walk snapshot (debounce/coalesce). */
        const val SNAPSHOT_MIN_INTERVAL_MS = 500L
        /** Time-budget satu walk (collect + collectNodes). */
        const val SNAPSHOT_TIME_BUDGET_MS = 300L
        /** Depth maksimum rekursi walk. */
        const val SNAPSHOT_MAX_DEPTH = 24
        /** Node maksimum yang dikunjungi collect() (teks layar). */
        const val SNAPSHOT_MAX_COLLECT_NODES = 2000
        /** Node maksimum yang dikunjungi collectNodes() (ref bernomor). */
        const val SNAPSHOT_MAX_VISIT_NODES = 5000
        /** Elemen maksimum yang disimpan di lastNodes (nilai lama: 120). */
        const val SNAPSHOT_MAX_NODES = 120

        /** Main thread service — dicatat di onServiceConnected (jalan
         * di main thread). Dipakai isMainThread(). */
        @Volatile var mainThread: Thread? = null

        /** Executor latar snapshot: single-thread, daemon (tidak menahan
         * proses/JVM). Sengaja TIDAK di-shutdown di onDestroy — service
         * bisa connect lagi di proses yang sama dan companion ini dipakai
         * bersama; satu thread daemon yang menganggur tidak berbahaya. */
        val snapshotExecutor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "a11y-snapshot").apply { isDaemon = true }
            }

        /** Penjadwal walk snapshot (debounce + coalesce + skip-bila-jalan).
         * walk = doSnapshotWalk() instance aktif; bila service belum
         * connect (instance null) walk jadi no-op tapi penjadwalan tetap
         * terhitung — dipakai tes JVM (AccessibilityService tidak bisa
         * diinstansiasi dengan android.jar stub). */
        @Volatile internal var snapshotScheduler: SnapshotScheduler = SnapshotScheduler(
            minIntervalMs = SNAPSHOT_MIN_INTERVAL_MS,
            nowMs = { System.currentTimeMillis() },
            post = { r -> snapshotExecutor.execute(r) },
            postDelayed = { r, ms -> snapshotExecutor.schedule(r, ms, TimeUnit.MILLISECONDS) },
            walk = { instance?.doSnapshotWalk() }
        )
    }

    override fun onServiceConnected() {
        instance = this
        mainThread = Thread.currentThread()
    }
    override fun onDestroy() { if (instance == this) instance = null; super.onDestroy() }
}
