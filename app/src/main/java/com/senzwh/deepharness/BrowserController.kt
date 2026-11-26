package com.senzwh.deepharness

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.senzwh.deepharness.MainActivity.BrowserTabMetaVc313
import com.senzwh.deepharness.MainActivity.BrowserNetworkEntryVc313

// ===== Browser agen (T4b1) — dipindah dari MainActivity =====
// Audit: pecah MainActivity. Fungsi di sini adalah extension MainActivity;
// state (bTabs, bActive, dll.) tetap di MainActivity sebagai `internal`.
// Body fungsi VERBATIM dari versi lama (hanya visibility yang berubah).

    internal fun MainActivity.setBrowserMode(browser: Boolean) {
        try {
            // vc181: baris ubin Web SELALU terlihat -- ia memuat saklar modenya
            // sendiri; menyembunyikannya di mode browser menghilangkan jalan
            // kembali ke login (dulu segRow yang selalu terlihat).
            if (webViewReady()) webView.visibility = if (browser) View.GONE else View.VISIBLE
            browserBox?.visibility = if (browser) View.VISIBLE else View.GONE
        } catch (e: Exception) {}
    }

    internal fun MainActivity.bActiveWv(): WebView? = if (bActive in bTabs.indices) bTabs[bActive] else null

    // vc313 Worker B — UA desktop (Spek §3.7; K-07 default mobile per tab).
    private val BROWSER_DESKTOP_UA_VC313: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    internal fun MainActivity.browserHostPath(url: String): String {
        return try {
            val u = android.net.Uri.parse(url)
            (u.host ?: "") + (u.path ?: "")
        } catch (e: Exception) {
            url
        }
    }

    internal fun MainActivity.formatBrowserBytesVc313(sizeBytes: Long): String {
        if (sizeBytes < 0) return "—"
        if (sizeBytes < 1024) return "$sizeBytes B"
        if (sizeBytes < 1024 * 1024) return "%.1f KB".format(sizeBytes / 1024.0)
        return "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
    }

    internal fun MainActivity.updateBrowserVisibility() {
        for ((i, wv) in bTabs.withIndex()) wv?.visibility = if (i == bActive) View.VISIBLE else View.GONE
        val wv = bActiveWv()
        val meta = if (bActive in bTabMeta.indices) bTabMeta[bActive] else null
        // vc316 Worker BR (b): label dipotong di AgentBrowser.tabLabelVc316
        // (judul ≤60, URL ≤120 char) — pertahanan kedua sesudah ellipsize
        // di bUrlTv.
        if (wv != null) bUrlTv?.text = AgentBrowser.tabLabelVc316(bActive, bTabs.size, wv.title ?: "", wv.url ?: "", false)
        else if (meta != null) bUrlTv?.text = AgentBrowser.tabLabelVc316(bActive, bTabs.size, meta.title, meta.url, true)
        if (browserCursorVisible && wv != null) browserCursorView?.bringToFront()
    }

    /** vc313: simpan metadata tab ke SharedPreferences 'browserTabsVc313'
     *  (JSON array: url, judul, scrollY, lastActiveMs). Dipanggil tiap
     *  perubahan tab, saat tab ditutup, dan di onPause. Bila dipanggil dari
     *  UI thread, meta live (url/judul WebView) disegarkan dulu. */
    internal fun MainActivity.saveBrowserTabsVc313() {
        try {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) refreshBrowserTabMetaFromLive()
            val arr = org.json.JSONArray()
            val copy: List<BrowserTabMetaVc313> = synchronized(bTabMeta) { bTabMeta.toList() }
            for (m in copy) {
                val o = org.json.JSONObject()
                o.put("url", m.url)
                o.put("title", m.title)
                o.put("scrollY", m.scrollY)
                o.put("lastActiveMs", m.lastActiveMs)
                arr.put(o)
            }
            getSharedPreferences("browserTabsVc313", Context.MODE_PRIVATE).edit().putString("tabs", arr.toString()).apply()
        } catch (e: Exception) {}
    }

    /** Hanya dari UI thread: salin url/judul WebView hidup ke bTabMeta. */
    internal fun MainActivity.refreshBrowserTabMetaFromLive() {
        synchronized(bTabMeta) {
            for (i in bTabs.indices) {
                val wv = bTabs[i] ?: continue
                val m = bTabMeta.getOrNull(i) ?: continue
                try { m.url = wv.url ?: m.url } catch (e: Exception) {}
                try { m.title = wv.title ?: m.title } catch (e: Exception) {}
            }
        }
    }

    /** vc313: pulihkan DAFTAR tab dari metadata tersimpan (T-BR-14).
     *  WebView TIDAK dibuat di sini — tab pulih sebagai dorman (null) dan
     *  dibuat malas saat di-switch/dibaca. Hanya dari UI thread. */
    internal fun MainActivity.restoreBrowserTabsVc313(): Boolean {
        if (bTabs.isNotEmpty() || bTabMeta.isNotEmpty()) return false
        val raw = try {
            getSharedPreferences("browserTabsVc313", Context.MODE_PRIVATE).getString("tabs", "") ?: ""
        } catch (e: Exception) {
            ""
        }
        if (raw.isBlank()) return false
        return try {
            val arr = org.json.JSONArray(raw)
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url", "")
                if (url.isBlank()) continue
                synchronized(bTabMeta) {
                    bTabMeta.add(
                        BrowserTabMetaVc313(
                            url = url,
                            title = o.optString("title", ""),
                            scrollY = o.optInt("scrollY", 0),
                            lastActiveMs = o.optLong("lastActiveMs", 0L)
                        )
                    )
                }
                bTabs.add(null)
                bTabIds.add(browserNextTabId++)
                n++
            }
            if (n > 0) {
                bActive = 0
                updateBrowserVisibility()
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    /** vc313: tandai tab dipakai (lastActiveMs = sekarang) + simpan. */
    internal fun MainActivity.touchBrowserTab(idx: Int) {
        try {
            synchronized(bTabMeta) {
                val m = bTabMeta.getOrNull(idx) ?: return
                m.lastActiveMs = System.currentTimeMillis()
            }
            saveBrowserTabsVc313()
        } catch (e: Exception) {}
    }

    /** vc313: geser kunci map per-tab sesudah satu tab dihapus (indeks > yang
     *  dihapus turun 1; kunci yang dihapus dibuang). */
    internal fun <V> MainActivity.shiftBrowserTabMapKeys(m: MutableMap<Int, V>, removed: Int) {
        val entries = m.entries.toList()
        m.clear()
        for ((k, v) in entries) {
            if (k == removed) continue
            m[if (k > removed) k - 1 else k] = v
        }
    }

    internal fun MainActivity.shiftBrowserTabMaps(removed: Int) {
        shiftBrowserTabMapKeys(browserLastSnapshot, removed)
        shiftBrowserTabMapKeys(browserLastFullUrl, removed)
        shiftBrowserTabMapKeys(browserConsoleLog, removed)
        shiftBrowserTabMapKeys(browserNetworkLog, removed)
        shiftBrowserTabMapKeys(bTabUaMode, removed)
        shiftBrowserTabMapKeys(bTabDefaultUa, removed)
        shiftBrowserTabMapKeys(browserPendingScrollY, removed)
    }

    // ---------- vc313: kursor agen (§3.10) ----------
    // Tampilan: lingkaran cincin luar PUTIH (kontras di tema gelap) +
    // cincin aksen app + titik tengah putih — kecil (44dp), tidak menutupi
    // elemen target (titik tengah menandai posisi klik persis).

    internal fun MainActivity.ensureBrowserCursor() {
        val frame = bFrame ?: return
        if (browserCursorView != null) return
        val size = dp(44)
        val v = object : View(this) {
            private val paintWhite = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                color = android.graphics.Color.WHITE
            }
            private val paintAccent = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                color = ACCENT
            }
            private val paintDot = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.FILL
                color = android.graphics.Color.WHITE
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val c = canvas
                val cx = width / 2f
                val cy = height / 2f
                c.drawCircle(cx, cy, width / 2f - 3f, paintWhite)
                c.drawCircle(cx, cy, width / 2f - 9f, paintAccent)
                c.drawCircle(cx, cy, 4f, paintDot)
            }
        }
        v.visibility = View.GONE
        v.isClickable = false
        v.isFocusable = false
        frame.addView(v, FrameLayout.LayoutParams(size, size))
        browserCursorView = v
    }

    /** Hanya dari UI thread. */
    internal fun MainActivity.moveBrowserCursorTo(x: Int, y: Int) {
        browserCursorX = x
        browserCursorY = y
        browserUserTakeover = false
        browserCursorVisible = true
        val v = browserCursorView ?: return
        val size = dp(44)
        try {
            val lp = (v.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(size, size)
            lp.width = size
            lp.height = size
            lp.leftMargin = (x - size / 2).coerceAtLeast(0)
            lp.topMargin = (y - size / 2).coerceAtLeast(0)
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            v.layoutParams = lp
            v.visibility = View.VISIBLE
            v.bringToFront()
        } catch (e: Exception) {}
    }

    /** Hanya dari UI thread: kursor ke tengah viewport. */
    internal fun MainActivity.moveBrowserCursorToViewportCenter(wv: WebView) {
        val x = if (wv.width > 0) wv.width / 2 else 540
        val y = if (wv.height > 0) wv.height / 2 else 800
        moveBrowserCursorTo(x, y)
    }

    /** Kursor ke tengah elemen target (koordinat ref) atau tengah viewport
     *  bila ref tidak dikenal; lalu jeda ±150 ms sebelum aksi. Dipanggil
     *  dari thread worker: pindah via onUiSync, jeda di thread worker. */
    internal fun MainActivity.showBrowserCursorForRefThenWait(ref: Int, wv: WebView) {
        val entry = try { AgentBrowser.lastRefs[ref] } catch (e: Exception) { null }
        onUiSync {
            if (entry != null) moveBrowserCursorTo(entry.cx, entry.cy)
            else moveBrowserCursorToViewportCenter(wv)
            Unit
        }
        try { Thread.sleep(150) } catch (e: Exception) {}
    }

    internal fun MainActivity.moveBrowserCursorToViewportCenterSync(wv: WebView) {
        onUiSync { moveBrowserCursorToViewportCenter(wv); Unit }
    }

    internal fun MainActivity.hideBrowserCursor() {
        browserCursorVisible = false
        try {
            runOnUiThread { try { browserCursorView?.visibility = View.GONE } catch (e: Exception) {} }
        } catch (e: Exception) {}
    }

    /** Gambar kursor ke bitmap screenshot pada posisi terakhirnya (bila
     *  sedang tampil). offsetY = scrollY halaman saat tangkapan (0 untuk
     *  tangkapan area terlihat; scroll awal untuk shot_full). */
    internal fun MainActivity.drawBrowserCursorOnCanvas(canvas: android.graphics.Canvas, offsetY: Int) {
        if (!browserCursorVisible) return
        try {
            val cx = browserCursorX.toFloat()
            val cy = browserCursorY.toFloat() + offsetY
            val white = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                color = android.graphics.Color.WHITE
            }
            val accent = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                color = ACCENT
            }
            val dot = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.FILL
                color = android.graphics.Color.WHITE
            }
            canvas.drawCircle(cx, cy, dp(22).toFloat(), white)
            canvas.drawCircle(cx, cy, dp(16).toFloat(), accent)
            canvas.drawCircle(cx, cy, dp(4).toFloat(), dot)
        } catch (e: Exception) {}
    }

    // ---------- vc313: pembuatan / pemulihan tab ----------

    /** Bangun SATU WebView tab agen (dipakai tab baru + revive tab dorman).
     *  Hanya dari UI thread. idx = indeks tab (untuk log konsol/jaringan
     *  dan UA per tab). */
    internal fun MainActivity.createBrowserWebView(idx: Int): WebView {
        val wv = WebView(this)
        val self = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        try {
            bTabDefaultUa[idx] = wv.settings.userAgentString ?: ""
        } catch (e: Exception) {}
        // UA per tab yang tersimpan (mis. desktop sebelum tab idle) ikut pulih.
        if (bTabUaMode[idx] == "desktop") {
            try { wv.settings.userAgentString = BROWSER_DESKTOP_UA_VC313 } catch (e: Exception) {}
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view == bActiveWv()) updateBrowserVisibility()
                val i = if (view != null) bTabs.indexOf(view) else -1
                if (i >= 0) {
                    synchronized(bTabMeta) {
                        val m = bTabMeta.getOrNull(i)
                        if (m != null) {
                            try { m.url = view?.url ?: m.url } catch (e: Exception) {}
                            try { m.title = view?.title ?: m.title } catch (e: Exception) {}
                            if (i == bActive) m.lastActiveMs = System.currentTimeMillis()
                        }
                    }
                    val pending = browserPendingScrollY.remove(i)
                    if (pending != null && pending > 0) {
                        try { view?.evaluateJavascript("window.scrollTo(0, $pending)", null) } catch (e: Exception) {}
                    }
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                // vc313 §3.7: catat URL + metode + ukuran header bila bisa.
                // Isi body TIDAK dicatat. Status HTTP tidak dibawa
                // shouldInterceptRequest — dicatat '—'. Kembalikan null
                // supaya WebView memuat normal.
                try {
                    val req = request ?: return null
                    val i = bTabs.indexOf(self)
                    if (i >= 0) {
                        synchronized(browserNetworkLog) {
                            val list = browserNetworkLog.getOrPut(i) { mutableListOf() }
                            list.add(
                                BrowserNetworkEntryVc313(
                                    method = try { req.method ?: "GET" } catch (e: Exception) { "GET" },
                                    status = "—",
                                    url = try { req.url.toString() } catch (e: Exception) { "" },
                                    sizeBytes = -1L
                                )
                            )
                            while (list.size > 500) list.removeAt(0)
                        }
                    }
                } catch (e: Exception) {}
                return null
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                val m = message ?: return true
                try {
                    val i = bTabs.indexOf(self)
                    if (i >= 0) {
                        synchronized(browserConsoleLog) {
                            val list = browserConsoleLog.getOrPut(i) { mutableListOf() }
                            list.add("[${m.messageLevel()}] ${m.message()} (sumber:${m.sourceId()}:${m.lineNumber()})")
                            while (list.size > 200) list.removeAt(0)
                        }
                    }
                } catch (e: Exception) {}
                return true
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>,
                fileChooserParams: android.webkit.WebChromeClient.FileChooserParams?
            ): Boolean {
                try { browserFileChooserCallback?.onReceiveValue(null) } catch (e: Exception) {}
                browserFileChooserCallback = filePathCallback
                try {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    startActivityForResult(Intent.createChooser(intent, "Pilih file untuk browser agen"), 4101)
                } catch (e: Exception) {
                    try { filePathCallback.onReceiveValue(null) } catch (e2: Exception) {}
                    browserFileChooserCallback = null
                }
                return true
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                // vc313 §1.4 / K-06: kamera/mikrofon DILARANG di tab agen.
                // VIDEO_CAPTURE dan AUDIO_CAPTURE ditolak eksplisit di
                // bawah; resource lain juga ditolak — TIDAK ADA jalur grant
                // di tab agen.
                val wantsMedia = (request.resources ?: emptyArray()).any {
                    it == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                        it == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (wantsMedia) {
                    try { request.deny() } catch (e: Exception) {}
                    return
                }
                try { request.deny() } catch (e: Exception) {}
            }
        }
        wv.setDownloadListener { url, _, contentDisposition, mimetype, contentLength ->
            try {
                BrowserDownloadManager.enqueue(url, contentDisposition, mimetype, contentLength)
            } catch (e: Exception) {}
        }
        // Sentuhan user = user mengambil alih tab: kursor agen hilang
        // sampai aksi agen berikutnya (Spek §3.10).
        wv.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                browserUserTakeover = true
                hideBrowserCursor()
            }
            false
        }
        return wv
    }

    /** Buat WebView baru untuk tab dorman + muat ulang URL tersimpan
     *  (cookie ikut — sesi tidak hilang). Hanya dari UI thread. */
    internal fun MainActivity.reviveBrowserTab(idx: Int): WebView? {
        val frame = bFrame ?: return null
        if (idx !in bTabs.indices) return null
        bTabs[idx]?.let { return it }
        val wv = createBrowserWebView(idx)
        bTabs[idx] = wv
        frame.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        browserCursorView?.bringToFront()
        val meta = synchronized(bTabMeta) { bTabMeta.getOrNull(idx) }
        if (meta != null && meta.url.isNotBlank()) {
            if (meta.scrollY > 0) browserPendingScrollY[idx] = meta.scrollY
            try { wv.loadUrl(meta.url) } catch (e: Exception) {}
        }
        touchBrowserTab(idx)
        updateBrowserVisibility()
        return wv
    }

    internal fun MainActivity.ensureBrowserTab(forceNew: Boolean): WebView? {
        val frame = bFrame ?: return null
        try { BrowserDownloadManager.init(this) } catch (e: Exception) {}
        // vc313: pulihkan daftar tab tersimpan bila daftar kosong (supaya
        // ketuk ＋ Tab manual sesudah restart tidak menimpa metadata lama
        // sebelum browserTool pertama sempat memulihkan — T-BR-14).
        if (bTabs.isEmpty()) restoreBrowserTabsVc313()
        if (!forceNew) {
            val cur = bActiveWv()
            if (cur != null) {
                touchBrowserTab(bActive)
                return cur
            }
            if (bActive in bTabs.indices) return reviveBrowserTab(bActive)
            if (bTabs.isNotEmpty()) {
                bActive = 0
                return reviveBrowserTab(0)
            }
        }
        // vc313 §3.4: batas 5 tab aktif — tab ke-6 ditolak, tidak ada tab
        // yang ditutup otomatis.
        if (bTabs.size >= MainActivity.MAX_BROWSER_TABS_VC313) return null
        val idx = bTabs.size
        val wv = createBrowserWebView(idx)
        bTabs.add(wv)
        synchronized(bTabMeta) {
            bTabMeta.add(BrowserTabMetaVc313(lastActiveMs = System.currentTimeMillis()))
        }
        bTabIds.add(browserNextTabId++)
        bActive = idx
        frame.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ensureBrowserCursor()
        browserCursorView?.bringToFront()
        // vc316 N9-A1: debugging WebView DIMATIKAN — pemanggilan untuk
        // MENYALAKAN flag debugging DIHAPUS dari sini. Alasan:
        // klien CDP (BrowserCdpClient) tidak pernah dipakai di vc313-vc315
        // (NOL pemanggil: connect/evaluate/captureScreenshotFull), jadi
        // debugging rilis hanya membuka permukaan inspeksi USB/ADB
        // (termasuk sesi login yang berbagi CookieManager) tanpa manfaat
        // fungsional apa pun. Nyalakan lagi HANYA saat CDP benar-benar
        // dipakai — dan pasangkan on/off seperti dulu (nyala di tab
        // pertama, mati di tab terakhir, lihat closeBrowserTab).
        updateBrowserVisibility()
        saveBrowserTabsVc313()
        return wv
    }

    /** vc313 §3.4: tab idle >30 menit -> simpan state lalu destroy WebView.
     *  Hanya dipanggil dari thread worker browserTool (evalSync tidak boleh
     *  dari UI thread); mutasi daftar + destroy lewat onUiSync. */
    internal fun MainActivity.sweepIdleBrowserTabs() {
        if (bTabs.isEmpty()) return
        val now = System.currentTimeMillis()
        for (i in bTabs.indices) {
            try {
            if (i == bActive) continue
            val wv = bTabs[i] ?: continue
            val meta = synchronized(bTabMeta) { bTabMeta.getOrNull(i) } ?: continue
            if (now - meta.lastActiveMs < 30 * 60 * 1000L) continue
            var scrollY = meta.scrollY
            try {
                evalSync(wv, "window.scrollY")?.toIntOrNull()?.let { scrollY = it }
            } catch (e: Exception) {}
            val sy = scrollY
            onUiSync {
                try {
                    synchronized(bTabMeta) {
                        val m = bTabMeta.getOrNull(i)
                        if (m != null) {
                            try { m.url = wv.url ?: m.url } catch (e: Exception) {}
                            try { m.title = wv.title ?: m.title } catch (e: Exception) {}
                            m.scrollY = sy
                        }
                    }
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.destroy()
                    if (i in bTabs.indices) bTabs[i] = null
                } catch (e: Exception) {}
                Unit
            }
            saveBrowserTabsVc313()
            } catch (e: Exception) {}
        }
    }

    /** Tutup satu tab: lepas + destroy WebView, geser state per-tab, flush
     *  cookie, matikan debugging bila ini tab terakhir. Hanya UI thread. */
    internal fun MainActivity.closeBrowserTab(idx: Int): Boolean {
        if (idx !in bTabs.indices) return false
        val wv = bTabs.removeAt(idx)
        synchronized(bTabMeta) { if (idx in bTabMeta.indices) bTabMeta.removeAt(idx) }
        if (idx in bTabIds.indices) bTabIds.removeAt(idx)
        try {
            wv?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
        } catch (e: Exception) {}
        shiftBrowserTabMaps(idx)
        bActive = when {
            bTabs.isEmpty() -> -1
            idx < bActive -> bActive - 1
            bActive >= bTabs.size -> bTabs.size - 1
            else -> bActive
        }
        try { CookieManager.getInstance().flush() } catch (e: Exception) {}
        if (bTabs.isEmpty()) {
            // vc316 N9-A1: debugging TIDAK PERNAH dinyalakan lagi (lihat
            // pembukaan tab di atas) — panggilan false ini JARING PENGAMAN
            // agar flag berakhir MATI apa pun yang terjadi sebelumnya.
            try { WebView.setWebContentsDebuggingEnabled(false) } catch (e: Exception) {}
            hideBrowserCursor()
        }
        updateBrowserVisibility()
        saveBrowserTabsVc313()
        return true
    }

    internal fun <T> MainActivity.onUiSync(block: () -> T): T? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return try { block() } catch (e: Exception) { null }
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: T? = null
        runOnUiThread { try { out = block() } catch (e: Exception) {} finally { latch.countDown() } }
        return if (latch.await(20, java.util.concurrent.TimeUnit.SECONDS)) out else null
    }

    internal fun MainActivity.evalSync(wv: WebView, js: String): String? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var raw: String? = null
        runOnUiThread {
            try {
                wv.evaluateJavascript(js) { r -> raw = r; latch.countDown() }
            } catch (e: Exception) { latch.countDown() }
        }
        if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) return null
        val r = raw ?: return null
        return try { org.json.JSONTokener(r).nextValue() as? String ?: r } catch (e: Exception) { r }
    }

    internal fun MainActivity.waitBrowserReady(wv: WebView, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val st = evalSync(wv, "document.readyState") ?: return
            if (st == "complete" || st == "interactive") {
                try { Thread.sleep(400) } catch (e: Exception) {}
                return
            }
            try { Thread.sleep(300) } catch (e: Exception) {}
        }
    }

    internal fun MainActivity.browserSnapshot(wv: WebView): String {
        val json = evalSync(wv, AgentBrowser.SNAPSHOT_JS) ?: return "Snapshot gagal — halaman belum siap atau JS mati."
        return AgentBrowser.formatSnapshot(json)
    }

    /** vc313 §3.2/§5: snapshot penuh hanya di giliran pertama per tab,
     *  sesudah URL (host+path) berubah, atau bila dipaksa; selebihnya diff
     *  vs baseline + baseline diperbarui ke snapshot penuh terbaru. */
    internal fun MainActivity.browserSnapshotForTab(wv: WebView, forceFull: Boolean, prefix: String): String {
        val full = browserSnapshot(wv)
        val idx = bActive
        val url = try { wv.url ?: "" } catch (e: Exception) { "" }
        val hostPath = browserHostPath(url)
        val baseline = browserLastSnapshot[idx]
        val prevUrl = browserLastFullUrl[idx]
        if (forceFull || baseline == null || prevUrl == null || prevUrl != hostPath) {
            browserLastSnapshot[idx] = full
            browserLastFullUrl[idx] = hostPath
            return prefix + full
        }
        val diff = try { AgentBrowser.diffSnapshots(baseline, full) } catch (e: Exception) { full }
        browserLastSnapshot[idx] = full
        if (diff.isBlank()) return prefix + "(Tidak ada perubahan sejak snapshot terakhir.)"
        return prefix + diff
    }

    internal fun MainActivity.browserWrapNonce(content: String): String {
        return try { AgentBrowser.wrapNonce(browserNonce, content) } catch (e: Exception) { content }
    }

    internal fun MainActivity.staleRefMessageVc313(ref: Int): String =
        "ref @e$ref sudah basi — snapshot ulang (browser_read)."

    /** Stale-ref retry (§3.2): REF_NOT_FOUND -> cari ulang SATU elemen via
     *  role+nama dari lastRefs; bila unik, coba SEKALI dengan ref baru.
     *  Mengembalikan hasil mentah tapJs/typeJs ("OK...", "REF_NOT_FOUND",
     *  "PASSWORD_FIELD"), "EXEC_FAIL" bila eval gagal, atau pesan basi. */
    internal fun MainActivity.browserTapOrTypeWithStaleRetry(wv: WebView, ref: Int, isType: Boolean, text: String): String {
        val first = evalSync(wv, if (isType) AgentBrowser.typeJs(ref, text) else AgentBrowser.tapJs(ref))
            ?: return "EXEC_FAIL"
        if (first != "REF_NOT_FOUND") return first
        val entry = try { AgentBrowser.lastRefs[ref] } catch (e: Exception) { null }
            ?: return staleRefMessageVc313(ref)
        val findJs = try { AgentBrowser.findByRoleJs(entry.role, entry.label) } catch (e: Exception) { null }
            ?: return staleRefMessageVc313(ref)
        val found = evalSync(wv, findJs) ?: return staleRefMessageVc313(ref)
        val newRef = try { AgentBrowser.findResultToRef(found) } catch (e: Exception) { null }
            ?: return staleRefMessageVc313(ref)
        val second = evalSync(wv, if (isType) AgentBrowser.typeJs(newRef, text) else AgentBrowser.tapJs(newRef))
            ?: return staleRefMessageVc313(ref)
        if (second == "REF_NOT_FOUND" || second.contains("AMBIGUOUS")) return staleRefMessageVc313(ref)
        return second
    }

    /** WebView tab aktif untuk tool (worker thread): pulihkan daftar tab
     *  sesudah restart bila perlu, sapu tab idle, revive tab dorman. */
    internal fun MainActivity.browserActiveWvForTool(): WebView? {
        if (bTabs.isEmpty()) {
            onUiSync { restoreBrowserTabsVc313(); Unit }
        }
        sweepIdleBrowserTabs()
        val cur = onUiSync { bActiveWv() }
        if (cur != null) {
            touchBrowserTab(bActive)
            return cur
        }
        if (bActive in bTabs.indices) {
            val revived = onUiSync { reviveBrowserTab(bActive) }
            if (revived != null) {
                waitBrowserReady(revived, 8000)
                return revived
            }
        }
        return null
    }

    /** Simpan JPEG bitmap ke /sdcard/Pictures/Harness; "" bila gagal. */
    internal fun MainActivity.saveBrowserShotJpeg(bmp: android.graphics.Bitmap, prefix: String): String {
        return try {
            val dir = java.io.File("/sdcard/Pictures/Harness")
            dir.mkdirs()
            val f = java.io.File(dir, prefix + System.currentTimeMillis() + ".jpg")
            val fos = java.io.FileOutputStream(f)
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos)
            fos.close()
            f.absolutePath
        } catch (e: Exception) {
            ""
        }
    }

    /** Gambar area terlihat WebView ke JPEG; "" bila gagal. Hanya dari
     *  UI thread (dipanggil lewat onUiSync). */
    internal fun MainActivity.drawVisibleShotToFile(wv: WebView): String {
        return try {
            // vc316 Worker BR (c): ukur bila lebar ATAU tinggi 0 (dulu
            // hanya lebar) — tinggi 0 menghasilkan bitmap 1px.
            if (wv.width == 0 || wv.height == 0) {
                wv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
                wv.layout(0, 0, 1080, 1600)
            }
            val bmp = android.graphics.Bitmap.createBitmap(wv.width.coerceAtLeast(1), wv.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            wv.draw(canvas)
            drawBrowserCursorOnCanvas(canvas, 0)
            val p = saveBrowserShotJpeg(bmp, "browser-")
            try { bmp.recycle() } catch (e: Exception) {}
            p
        } catch (e: Exception) {
            ""
        }
    }

    /** Tangkapan area terlihat (dipakai browser_shot + fallback shot_full). */
    internal fun MainActivity.browserShotVisible(wv: WebView): String {
        // vc316 Worker BR (c): coba sekali lagi bila pengambilan pertama
        // gagal — fallback shot_full bergantung pada file ini.
        val path = onUiSync { drawVisibleShotToFile(wv) } ?: ""
        if (path.isNotBlank()) return "📸 Halaman tersimpan: $path — bisa dilanjut analyze_photo bila perlu membaca visualnya."
        val retry = onUiSync { drawVisibleShotToFile(wv) } ?: ""
        return if (retry.isBlank()) "Tangkapan browser gagal." else "📸 Halaman tersimpan: $retry — bisa dilanjut analyze_photo bila perlu membaca visualnya."
    }

    /** vc313 §3.7: screenshot satu halaman penuh (scroll-stitch). Batas
     *  total 12000px; bila halaman lebih tinggi, hanya 12000px pertama +
     *  catatan jujur. Bila stitch gagal total -> area terlihat + alasan.
     *  vc316 Worker BR (c): viewport degenerat (WebView belum ter-layout,
     *  tinggi 0 -> coerceAtLeast(1) = 1 -> 12000 iterasi × 250ms) DULU
     *  menggantung sampai timeout luar 120 dtk memotong TANPA file —
     *  padahal pesan fallback sudah ada tapi tidak pernah tercapai karena
     *  gantungnya di LOOP, bukan di exception. Kini: viewport diperiksa
     *  dulu (AgentBrowser.shotFullPlanVc316) + anggaran internal 100 dtk
     *  (di bawah timeout luar 120 dtk) — habis anggaran -> area terlihat
     *  + alasan jujur, SELALU ada file. Timeout luar TIDAK diubah.
     *  vc316 Worker BR (d): tiap segmen menunggu scroll benar-benar
     *  mereda (poll scrollY, waitBrowserScrollSettledVc316) sebelum draw —
     *  tanpa ini segmen di bawah viewport pertama hitam (draw hanya
     *  menggambar porsi yang sudah di-raster; lihat
     *  enableSlowWholeDocumentDraw di onCreate). */
    internal fun MainActivity.browserShotFull(wv: WebView): String {
        val startMs = System.currentTimeMillis()
        val deadlineMs = startMs + AgentBrowser.SHOT_FULL_BUDGET_MS_VC316
        val startScrollY = (evalSync(wv, "window.scrollY")?.toIntOrNull()) ?: 0
        var viewportH = onUiSync { wv.height.coerceAtLeast(1) } ?: 800
        var viewportW = onUiSync { wv.width.coerceAtLeast(1) } ?: 1080
        val pageH = (evalSync(wv, "document.body.scrollHeight")?.toIntOrNull())
            ?: (evalSync(wv, "document.documentElement.scrollHeight")?.toIntOrNull())
            ?: viewportH
        // (c) viewport degenerat -> coba SEKALI ukur/layout paksa seperti
        // browserShotVisible; masih degenerat -> fallback area terlihat.
        var plan = AgentBrowser.shotFullPlanVc316(pageH, viewportW, viewportH)
        if (!plan.viewportOk) {
            onUiSync {
                try {
                    if (wv.width == 0 || wv.height == 0) {
                        wv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
                        wv.layout(0, 0, 1080, 1600)
                    }
                    viewportW = wv.width.coerceAtLeast(1)
                    viewportH = wv.height.coerceAtLeast(1)
                } catch (e: Exception) {}
                Unit
            }
            plan = AgentBrowser.shotFullPlanVc316(pageH, viewportW, viewportH)
        }
        if (!plan.viewportOk) {
            return browserShotVisible(wv) + " (browser_shot_full gagal: viewport WebView belum ter-layout (" + viewportW + "×" + viewportH + ") — dikembalikan tangkapan area terlihat.)"
        }
        val totalH = plan.totalH
        val capped = pageH > 12000
        try {
            val full = android.graphics.Bitmap.createBitmap(viewportW, totalH, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(full)
            var y = 0
            var timedOut = false
            while (y < totalH) {
                // (c) anggaran internal: habis -> berhenti, fallback.
                if (System.currentTimeMillis() > deadlineMs) { timedOut = true; break }
                val segY = y
                onUiSync { wv.scrollTo(0, segY); Unit }
                // (d) tunggu scroll mereda sebelum draw — draw sebelum
                // raster = segmen hitam.
                waitBrowserScrollSettledVc316(wv, segY)
                val segH = minOf(viewportH, totalH - y)
                val seg = onUiSync {
                    try {
                        val b = android.graphics.Bitmap.createBitmap(viewportW, viewportH, android.graphics.Bitmap.Config.ARGB_8888)
                        val c = android.graphics.Canvas(b)
                        wv.draw(c)
                        b
                    } catch (e: Exception) {
                        null
                    }
                }
                if (seg != null) {
                    val src = android.graphics.Rect(0, 0, viewportW, segH)
                    val dst = android.graphics.Rect(0, y, viewportW, y + segH)
                    canvas.drawBitmap(seg, src, dst, null)
                    try { seg.recycle() } catch (e: Exception) {}
                }
                y += viewportH
            }
            if (timedOut) {
                try { full.recycle() } catch (e: Exception) {}
                try { onUiSync { wv.scrollTo(0, startScrollY); Unit } } catch (e2: Exception) {}
                return browserShotVisible(wv) + " (browser_shot_full gagal: batas waktu stitch 100 dtk habis di segmen ke-" + (y / viewportH) + " dari " + plan.segments + " — dikembalikan tangkapan area terlihat.)"
            }
            // Kursor digambar di koordinat halaman (viewport + scroll awal).
            drawBrowserCursorOnCanvas(canvas, startScrollY)
            val path = saveBrowserShotJpeg(full, "browser-full-")
            try { full.recycle() } catch (e: Exception) {}
            if (path.isBlank()) throw IllegalStateException("gagal menyimpan JPEG")
            val note = if (capped) " (halaman lebih tinggi dari batas 12000px — hanya 12000 px pertama)" else ""
            return "📸 Halaman penuh tersimpan: $path$note — bisa dilanjut analyze_photo bila perlu membaca visualnya."
        } catch (e: Exception) {
            try { onUiSync { wv.scrollTo(0, startScrollY); Unit } } catch (e2: Exception) {}
            return browserShotVisible(wv) + " (browser_shot_full gagal: " + (e.message ?: "alasan tidak diketahui") + " — dikembalikan tangkapan area terlihat.)"
        } finally {
            try { onUiSync { wv.scrollTo(0, startScrollY); Unit } } catch (e: Exception) {}
        }
    }

    /** vc316 Worker BR (d): tunggu scrollY WebView mencapai target (atau
     *  mentok di ujung halaman) sebelum draw segmen. Minimal 250ms (waktu
     *  raster), maks 2 dtk per segmen. */
    internal fun MainActivity.waitBrowserScrollSettledVc316(wv: WebView, targetY: Int) {
        val deadline = System.currentTimeMillis() + AgentBrowser.SHOT_FULL_SEGMENT_SETTLE_MS_VC316
        try { Thread.sleep(250) } catch (e: Exception) {}
        while (System.currentTimeMillis() < deadline) {
            val cur = onUiSync { wv.scrollY } ?: targetY
            if (cur >= targetY) return
            // Sudah mentok (tidak bisa scroll ke bawah lagi) -> lanjut.
            // (computeVerticalScrollRange() itu protected di WebView —
            // tidak bisa dipanggil dari MainActivity; canScrollVertically
            // itu API publik View dan cukup untuk "sudah mentok".)
            val atBottom = onUiSync { !wv.canScrollVertically(1) } ?: false
            if (atBottom) return
            try { Thread.sleep(100) } catch (e: Exception) {}
        }
    }

    /** Implementasi tool browser_* — dipanggil dari thread worker Tools.
     *  SEMUA return dibungkus nonce sesi (content boundaries §4.3). */
    internal fun MainActivity.browserTool(name: String, args: JSONObject): String {
        return browserWrapNonce(browserToolInner(name, args))
    }

    internal fun MainActivity.browserToolInner(name: String, args: JSONObject): String {
        return when (name) {
            "browser_open" -> {
                // vc313 §1.3: blocklist DIHAPUS — tidak ada penolakan domain.
                // §4.6: skema non-http(s) ditangani eksplisit.
                val raw = args.optString("url").trim()
                if (raw.isBlank()) return "URL kosong."
                val lower = raw.lowercase()
                if (lower.startsWith("intent://")) return "⛔ Skema intent:// ditolak — browser agen tidak membuka aplikasi lain (§4.6)."
                if (lower.startsWith("mailto:")) return "Skema mailto: butuh perintah user — tidak dibuka otomatis oleh agen."
                if (lower.startsWith("tel:")) return "Skema tel: butuh perintah user — tidak dibuka otomatis oleh agen."
                var url = raw
                if (!lower.startsWith("http") && !lower.startsWith("view-source:") && !lower.startsWith("about:")) url = "https://" + raw
                val wv = onUiSync { ensureBrowserTab(false) } ?: return "Browser agen belum siap."
                onUiSync { wv.loadUrl(url); Unit }
                waitBrowserReady(wv, 15000)
                moveBrowserCursorToViewportCenterSync(wv)
                browserSnapshotForTab(wv, true, "")
            }
            "browser_read" -> {
                val wv = browserActiveWvForTool() ?: return "Belum ada tab browser agen — buka URL dulu dengan browser_open."
                browserSnapshotForTab(wv, false, "")
            }
            "browser_tap" -> {
                val ref = args.optInt("ref", -1)
                if (ref <= 0) return "Argumen ref tidak valid."
                val wv = browserActiveWvForTool() ?: return "Belum ada tab browser agen."
                showBrowserCursorForRefThenWait(ref, wv)
                val r = browserTapOrTypeWithStaleRetry(wv, ref, false, "")
                if (r == "EXEC_FAIL") return "browser_tap gagal dieksekusi."
                if (r == "REF_NOT_FOUND") return "Elemen #$ref tidak ditemukan — panggil browser_read untuk snapshot baru."
                if (r.startsWith("ref @e")) return r
                waitBrowserReady(wv, 6000)
                moveBrowserCursorToViewportCenterSync(wv)
                browserSnapshotForTab(wv, false, "Diklik: " + r.removePrefix("OK:") + "\n")
            }
            "browser_type" -> {
                val ref = args.optInt("ref", -1)
                val text = args.optString("text")
                if (ref <= 0) return "Argumen ref tidak valid."
                val wv = browserActiveWvForTool() ?: return "Belum ada tab browser agen."
                showBrowserCursorForRefThenWait(ref, wv)
                val r = browserTapOrTypeWithStaleRetry(wv, ref, true, text)
                if (r == "EXEC_FAIL") return "browser_type gagal dieksekusi."
                when (r) {
                    "REF_NOT_FOUND" -> "Elemen #$ref tidak ditemukan — panggil browser_read untuk snapshot baru."
                    "PASSWORD_FIELD" -> "⛔ Kolom kata sandi — browser agen tidak mengisi kata sandi. Minta user mengisinya sendiri."
                    else -> {
                        if (r.startsWith("ref @e")) r
                        else {
                            waitBrowserReady(wv, 6000)
                            moveBrowserCursorToViewportCenterSync(wv)
                            browserSnapshotForTab(wv, false, "Diketik ke elemen #$ref.\n")
                        }
                    }
                }
            }
            "browser_back" -> {
                val wv = browserActiveWvForTool() ?: return "Belum ada tab browser agen."
                val canBack = onUiSync { wv.canGoBack() } ?: false
                if (!canBack) return "Tidak ada histori mundur."
                onUiSync { wv.goBack(); Unit }
                waitBrowserReady(wv, 6000)
                moveBrowserCursorToViewportCenterSync(wv)
                browserSnapshotForTab(wv, false, "")
            }
            "browser_forward" -> {
                val wv = browserActiveWvForTool() ?: return "Belum ada tab browser agen."
                val canFwd = onUiSync { wv.canGoForward() } ?: false
                if (!canFwd) return "Tidak ada histori maju."
                onUiSync { wv.goForward(); Unit }
                waitBrowserReady(wv, 6000)
                moveBrowserCursorToViewportCenterSync(wv)
                browserSnapshotForTab(wv, false, "")
            }
            "browser_reload" -> {
                val wv = browserActiveWvForTool() ?: return "Belum ada tab browser agen."
                onUiSync { wv.reload(); Unit }
                waitBrowserReady(wv, 15000)
                moveBrowserCursorToViewportCenterSync(wv)
                browserSnapshotForTab(wv, false, "")
            }
            "browser_find" -> {
                val query = args.optString("query")
                if (query.isBlank()) return "Argumen query kosong."
                val wv = browserActiveWvForTool() ?: return "Belum ada tab browser agen."
                val latch = java.util.concurrent.CountDownLatch(1)
                var found = -1
                onUiSync {
                    try {
                        wv.setFindListener(object : WebView.FindListener {
                            override fun onFindResultReceived(activeMatchOrdinal: Int, numberOfMatches: Int, isDoneCounting: Boolean) {
                                if (isDoneCounting) {
                                    found = numberOfMatches
                                    latch.countDown()
                                }
                            }
                        })
                        wv.findAllAsync(query)
                    } catch (e: Exception) {
                        latch.countDown()
                    }
                    Unit
                }
                val done = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                if (!done || found < 0) "Pencarian \"$query\" belum selesai dalam 5 detik — coba lagi."
                else if (found == 0) "Tidak ada kecocokan untuk \"$query\"."
                else "Ditemukan $found kecocokan untuk \"$query\"."
            }
            "browser_ua" -> {
                val mode = args.optString("mode").trim().lowercase()
                if (mode != "mobile" && mode != "desktop") return "Mode UA tidak valid: \"$mode\". Nilai yang sah: \"mobile\" atau \"desktop\"."
                val wv = browserActiveWvForTool() ?: return "Belum ada tab browser agen."
                val ua = if (mode == "desktop") BROWSER_DESKTOP_UA_VC313 else (bTabDefaultUa[bActive] ?: "")
                if (ua.isBlank()) return "UA default tab ini tidak tercatat — tidak bisa kembali ke mobile."
                onUiSync {
                    try { wv.settings.userAgentString = ua } catch (e: Exception) {}
                    bTabUaMode[bActive] = mode
                    Unit
                }
                onUiSync { wv.reload(); Unit }
                waitBrowserReady(wv, 15000)
                "UA tab ini: $mode ($ua)"
            }
            "browser_downloads" -> {
                val items = try { BrowserDownloadManager.list() } catch (e: Exception) { emptyList() }
                if (items.isEmpty()) "Belum ada unduhan sesi ini."
                else items.joinToString("\n") { it.name + " — " + formatBrowserBytesVc313(it.sizeBytes) + " — " + it.status + " — " + it.path }
            }
            "browser_console" -> {
                val wv = browserActiveWvForTool() ?: return "Belum ada tab browser agen."
                val rows = synchronized(browserConsoleLog) { browserConsoleLog[bActive]?.toList() ?: emptyList() }
                if (rows.isEmpty()) "Konsol kosong." else rows.takeLast(200).joinToString("\n")
            }
            "browser_network" -> {
                val wv = browserActiveWvForTool() ?: return "Belum ada tab browser agen."
                val rows = synchronized(browserNetworkLog) { browserNetworkLog[bActive]?.toList() ?: emptyList() }
                if (rows.isEmpty()) "Belum ada request tercatat."
                else rows.joinToString("\n") { e -> e.method + " " + e.status + " " + e.url + " (" + formatBrowserBytesVc313(e.sizeBytes) + ")" }
            }
            "browser_shot_full" -> {
                val wv = browserActiveWvForTool() ?: return "Belum ada tab browser agen."
                browserShotFull(wv)
            }
            "browser_snapshot_full" -> {
                val wv = browserActiveWvForTool() ?: return "Belum ada tab browser agen."
                browserSnapshotForTab(wv, true, "")
            }
            "browser_tabs" -> {
                val action = args.optString("action", "list")
                if (bTabs.isEmpty()) {
                    onUiSync { restoreBrowserTabsVc313(); Unit }
                }
                when (action) {
                    "new" -> {
                        val created = onUiSync { ensureBrowserTab(true) }
                        if (created == null) "Batas 5 tab aktif — tutup satu tab dulu (browser_tabs close), tidak ada tab yang ditutup otomatis."
                        else "Tab browser baru dibuka (total " + bTabs.size + ")."
                    }
                    "switch" -> {
                        val idx = args.optInt("index", -1)
                        if (idx !in bTabs.indices) "Indeks tab tidak valid."
                        else {
                            val wv = onUiSync {
                                bActive = idx
                                touchBrowserTab(idx)
                                val live = bTabs[idx] ?: reviveBrowserTab(idx)
                                updateBrowserVisibility()
                                live
                            }
                            if (wv == null) "Tab tidak bisa dibuka kembali."
                            else {
                                moveBrowserCursorToViewportCenterSync(wv)
                                waitBrowserReady(wv, 4000)
                                browserSnapshotForTab(wv, false, "")
                            }
                        }
                    }
                    "close" -> {
                        val idx = args.optInt("index", bActive)
                        val ok = onUiSync { closeBrowserTab(idx) } ?: false
                        if (ok) "Tab ditutup. Sisa " + bTabs.size + " tab." else "Indeks tab tidak valid."
                    }
                    else -> {
                        val rows = onUiSync {
                            bTabs.indices.map { i ->
                                val live = bTabs[i]
                                val meta = synchronized(bTabMeta) { bTabMeta.getOrNull(i) }
                                val title = live?.title ?: meta?.title ?: ""
                                val url = live?.url ?: meta?.url ?: ""
                                (if (i == bActive) "● " else "○ ") + "Tab " + (i + 1) + ": " + title + " — " + url + (if (live == null) " (belum dimuat)" else "")
                            }
                        } ?: emptyList()
                        if (rows.isEmpty()) "Belum ada tab browser agen." else rows.joinToString("\n")
                    }
                }
            }
            "browser_shot" -> {
                val wv = browserActiveWvForTool() ?: return "Belum ada tab browser agen."
                browserShotVisible(wv)
            }
            else -> "Tool browser tidak dikenal: $name"
        }
    }


    // ===== T4a (migrasi UI native): Terminal Pro + panel sesi + Command Center =====

