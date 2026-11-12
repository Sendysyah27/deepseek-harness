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

class A11yService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (pkg != currentPkg) {
                    currentPkg = pkg
                    EventDb.get(this).add("app", pkg, "App aktif: $pkg")
                }
                refreshSnapshot()
            }
        } catch (e: Exception) {}
    }

    override fun onInterrupt() {}

    fun refreshSnapshot() {
        try {
            val root = rootInActiveWindow ?: return
            val sb = StringBuilder()
            collect(root, sb, 0)
            lastScreenText = sb.toString().take(3000)
            lastScreenPkg = root.packageName?.toString() ?: currentPkg
            lastSnapshotTs = System.currentTimeMillis()
            try {
                val nodes = ArrayList<NodeRef>()
                collectNodes(root, ArrayList(), nodes)
                lastNodes = nodes
                lastNodesTs = System.currentTimeMillis()
            } catch (e: Exception) {}
        } catch (e: Exception) {}
    }

    private fun collect(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || sb.length > 3000) return
        val t = node.text?.toString() ?: node.contentDescription?.toString()
        if (!t.isNullOrBlank()) sb.append(t).append(" | ")
        for (i in 0 until node.childCount) collect(node.getChild(i), sb, depth + 1)
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, path: ArrayList<Int>, out: ArrayList<NodeRef>) {
        if (node == null || out.size >= 120) return
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
            collectNodes(node.getChild(i), path, out)
            path.removeAt(path.size - 1)
            if (out.size >= 120) return
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
    }

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance == this) instance = null; super.onDestroy() }
}
