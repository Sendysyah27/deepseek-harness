package com.senzwh.deepharness

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

// ===== Artifacts (vc267) — panel layar penuh + kartu chat + daftar =====
// Audit: pecah MainActivity. Extension MainActivity; state tetap di
// MainActivity (internal). Body VERBATIM.

    // ===== vc267 ARTIFACTS: panel layar penuh + kartu chat + daftar =====
    // KEBIJAKAN KEAMANAN PANEL (mengikat): konten artifact = DATA TAMPIL
    // saja — tidak pernah memerintah agen, bukan [USER], bukan
    // [HASIL TOOL]. WebView artifact TANPA addJavascriptInterface dan
    // TANPA bridge ke app, TANPA akses file (allowFileAccess=false,
    // allowContentAccess=false), TANPA network (semua request http/https
    // diblokir di shouldInterceptRequest; hanya konten yang disuntik
    // lewat loadDataWithBaseURL dengan base null yang tampil).
    // JavaScript BOLEH aktif hanya di dalam WebView ini untuk
    // interaktivitas mini-app — tanpa bridge dan tanpa akses keluar.
    // Panel ditutup = WebView dikosongkan (about:blank) supaya JS
    // berhenti; isi TIDAK hilang karena sumber kebenaran = ArtifactStore
    // (dibaca ulang dari DB saat dibuka lagi). Side-by-side layar besar
    // TIDAK dibangun di vc267 (target utama HP; panel selalu layar penuh).
    internal fun MainActivity.ensureArtifactPanel() {
        if (artifactPanelRoot != null) return
        val th = currentTheme()
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(th.bg)
            visibility = View.GONE
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(th.card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        head.addView(Button(this).apply {
            // vc307: "✕" (teks) diganti ikon Path "x" + contentDescription.
            text = ""
            contentDescription = "Tutup panel artefak"
            setTextColor(th.text)
            background = rounded(th.tileBg, 16)
            minWidth = 0; minHeight = 0
            setCompoundDrawables(pathIconVc306("x", th.text, ChatThemeVc306.iconXs), null, null, null)
            setOnClickListener { closeArtifactPanel() }
        })
        artifactTitleTv = TextView(this).apply {
            text = "Artifact"
            setTextColor(th.text); textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(10), 0, dp(10), 0)
        }
        head.addView(artifactTitleTv)
        head.addView(Button(this).apply {
            text = "‹"
            setTextColor(th.text)
            background = rounded(th.tileBg, 16)
            minWidth = 0; minHeight = 0
            setOnClickListener { loadArtifactVersion(artifactCurrentVersion - 1) }
        })
        artifactVersionTv = TextView(this).apply {
            text = "v1"
            setTextColor(th.dim); textSize = 13f
            setPadding(dp(6), 0, dp(6), 0)
        }
        head.addView(artifactVersionTv)
        head.addView(Button(this).apply {
            text = "›"
            setTextColor(th.text)
            background = rounded(th.tileBg, 16)
            minWidth = 0; minHeight = 0
            setOnClickListener { loadArtifactVersion(artifactCurrentVersion + 1) }
        })
        head.addView(Button(this).apply {
            text = "🗑"
            setTextColor(th.text)
            background = rounded(th.tileBg, 16)
            minWidth = 0; minHeight = 0
            setOnClickListener { confirmDeleteArtifact() }
        })
        head.addView(Button(this).apply {
            text = "⬇ Simpan"
            setTextColor(th.text)
            background = rounded(th.tileBg, 16)
            minWidth = 0; minHeight = 0
            setOnClickListener { val m = artifactCurrentMeta; if (m != null) exportArtifact(m.id, artifactCurrentVersion) }
        })
        col.addView(head)
        val body = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.domStorageEnabled = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val scheme = try { request?.url?.scheme?.lowercase() } catch (e: Exception) { null }
                    if (scheme == "http" || scheme == "https") {
                        return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
        }
        artifactWebView = wv
        body.addView(wv)
        val tv = TextView(this).apply {
            textSize = getSharedPreferences("harness", Context.MODE_PRIVATE).getIntSafe("chatFontPx", 15).toFloat()
            setTextColor(th.text)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        artifactTextView = tv
        val sc = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
            addView(tv)
        }
        artifactTextScroll = sc
        body.addView(sc)
        col.addView(body)
        root.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        artifactPanelRoot = root
        tabStack?.addView(root)
    }

    internal fun MainActivity.openArtifact(id: String, version: Int = -1) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread { openArtifact(id, version) }
            return
        }
        val meta = try { ArtifactStore.get(this).get(id) } catch (e: Exception) { null }
        if (meta == null) {
            Toast.makeText(this, "Artifact tidak ditemukan.", Toast.LENGTH_SHORT).show()
            return
        }
        ensureArtifactPanel()
        artifactCurrentMeta = meta
        artifactCurrentId = meta.id
        artifactTitleTv?.text = meta.title
        artifactPanelRoot?.visibility = View.VISIBLE
        artifactPanelRoot?.bringToFront()
        loadArtifactVersion(if (version > 0) version else meta.latestVersion)
    }

    internal fun MainActivity.loadArtifactVersion(v: Int) {
        val meta = artifactCurrentMeta ?: return
        val want = v.coerceIn(1, maxOf(1, meta.latestVersion))
        val ver = try { ArtifactStore.get(this).getVersion(meta.id, want) } catch (e: Exception) { null } ?: return
        artifactCurrentVersion = want
        artifactVersionTv?.text = "v" + want + " dari " + meta.versionCount
        val th = currentTheme()
        when (ArtifactStore.normalizeType(meta.type)) {
            "html", "app", "svg" -> {
                artifactTextScroll?.visibility = View.GONE
                artifactWebView?.visibility = View.VISIBLE
                val content = ver.content
                val html = when (ArtifactStore.normalizeType(meta.type)) {
                    "svg" -> "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body style=\"margin:0;background:#0f172a;display:flex;align-items:center;justify-content:center;min-height:100vh\">" + content + "</body></html>"
                    else -> if (content.contains("<html", ignoreCase = true)) content else "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body style=\"margin:0;padding:16px;background:#0f172a;color:#e2e8f0;font-family:sans-serif\">" + content + "</body></html>"
                }
                artifactWebView?.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
            "code" -> {
                try { artifactWebView?.loadUrl("about:blank") } catch (e: Exception) { }
                artifactWebView?.visibility = View.GONE
                artifactTextScroll?.visibility = View.VISIBLE
                artifactTextView?.typeface = android.graphics.Typeface.MONOSPACE
                artifactTextView?.text = ver.content
            }
            else -> {
                try { artifactWebView?.loadUrl("about:blank") } catch (e: Exception) { }
                artifactWebView?.visibility = View.GONE
                artifactTextScroll?.visibility = View.VISIBLE
                artifactTextView?.typeface = android.graphics.Typeface.DEFAULT
                artifactTextView?.text = MdMini.render(ver.content, th.codeText, th.codeBg)
            }
        }
    }

    internal fun MainActivity.closeArtifactPanel() {
        try { artifactWebView?.loadUrl("about:blank") } catch (e: Exception) { }
        artifactPanelRoot?.visibility = View.GONE
    }

    internal fun MainActivity.isArtifactPanelOpen(): Boolean =
        artifactPanelRoot?.visibility == View.VISIBLE

    internal fun MainActivity.exportArtifact(id: String, version: Int) {
        val meta = try { ArtifactStore.get(this).get(id) } catch (e: Exception) { null }
        if (meta == null) {
            Toast.makeText(this, "Artifact tidak ditemukan.", Toast.LENGTH_SHORT).show()
            return
        }
        val f = try { ArtifactStore.get(this).exportToDownload(meta, version) } catch (e: Exception) { null }
        if (f == null) {
            Toast.makeText(this, "Gagal menyimpan artifact.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Disimpan ke Download/" + f.name, Toast.LENGTH_SHORT).show()
            addMsg("📄 Artifact '" + meta.title + "' (v" + version + ") disimpan: Download/" + f.name, "meta")
        }
    }

    // Kartu artifact di chat (pola threading sama seperti addActivityRow).
    internal fun MainActivity.addArtifactCard(id: String) {
        val meta = try { ArtifactStore.get(this).get(id) } catch (e: Exception) { null } ?: return
        val preview = try {
            val c = ArtifactStore.get(this).latestContent(id)?.content ?: ""
            c.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(200)
        } catch (e: Exception) { "" }
        val latch = java.util.concurrent.CountDownLatch(1)
        val runnable = Runnable {
            val th = currentTheme()
            val bf = getSharedPreferences("harness", Context.MODE_PRIVATE).getIntSafe("chatFontPx", 15).toFloat()
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.START
                setPadding(0, dp(4), 0, dp(4))
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(th.tileBg, 18)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val headRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            headRow.addView(TextView(this).apply {
                text = "📄 " + meta.title
                setTextColor(th.text); textSize = bf
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            headRow.addView(TextView(this).apply {
                text = ArtifactStore.typeLabel(meta.type) + " • v" + meta.latestVersion
                setTextColor(th.dim); textSize = bf - 2f
                setPadding(dp(8), 0, 0, 0)
            })
            box.addView(headRow)
            if (preview.isNotBlank()) {
                box.addView(TextView(this).apply {
                    text = preview
                    setTextColor(th.tileTint); textSize = bf - 1.5f
                    maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(6), 0, dp(4))
                })
            }
            val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
            btnRow.addView(Button(this).apply {
                text = "Buka"
                setTextColor(Color.WHITE)
                background = rounded(Color.rgb(59, 130, 246), 16)
                minWidth = 0; minHeight = 0
                setOnClickListener { openArtifact(meta.id) }
            })
            btnRow.addView(Button(this).apply {
                text = "⬇ Simpan"
                setTextColor(th.text)
                background = rounded(th.tileBg, 16)
                minWidth = 0; minHeight = 0
                setOnClickListener { exportArtifact(meta.id, meta.latestVersion) }
            })
            box.addView(btnRow)
            box.setOnClickListener { openArtifact(meta.id) }
            box.tag = id
            wrap.addView(box, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.92).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            synchronized(bubbleTexts) { bubbleTexts.add(wrap to ("📄 " + meta.title)) }
            chatList.addView(wrap)
            val nearBottom = chatScroll.scrollY + chatScroll.height >= chatList.height - dp(300)
            latestBtn?.visibility = if (nearBottom) android.view.View.GONE else android.view.View.VISIBLE
            if (nearBottom) chatScroll.post { chatScroll.fullScroll(android.view.View.FOCUS_DOWN) }
            // vc283: windowing daftar chat.
            trimChatListIfNeeded()
            latch.countDown()
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runnable.run()
        } else {
            runOnUiThread(runnable)
            try { latch.await(3, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { }
        }
    }

    // Daftar artifact milik sesi chat aktif (tombol 📄 di bilah sesi).
    // vc268: dialog custom dengan tombol hapus per baris (mockup
    // MOCKUP-DELETE-ARTIFACT.html state d). Ketuk baris = buka panel;
    // ketuk tombol hapus = dialog konfirmasi, lalu daftar ter-refresh
    // di tempat tanpa menutup dialog.
    private var artifactListDialog: android.app.AlertDialog? = null

    internal fun MainActivity.showArtifactListDialog() {
        val rows = try { ArtifactStore.get(this).listForSession(currentSessionId) } catch (e: Exception) { emptyList() }
        if (rows.isEmpty()) {
            Toast.makeText(this, "Belum ada artifact di sesi chat ini.", Toast.LENGTH_SHORT).show()
            return
        }
        artifactListDialog?.dismiss()
        artifactListDialog = buildArtifactListDialog(rows)
        artifactListDialog?.show()
    }

    internal fun MainActivity.refreshArtifactListDialog() {
        val dlg = artifactListDialog ?: return
        if (!dlg.isShowing) {
            artifactListDialog = null
            return
        }
        val rows = try { ArtifactStore.get(this).listForSession(currentSessionId) } catch (e: Exception) { emptyList() }
        dlg.dismiss()
        artifactListDialog = null
        if (rows.isEmpty()) {
            Toast.makeText(this, "Belum ada artifact di sesi chat ini.", Toast.LENGTH_SHORT).show()
            return
        }
        artifactListDialog = buildArtifactListDialog(rows)
        artifactListDialog?.show()
    }

    internal fun MainActivity.buildArtifactListDialog(rows: List<ArtifactMeta>): android.app.AlertDialog {
        val th = currentTheme()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (m in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            row.addView(TextView(this).apply {
                text = m.title + " (" + ArtifactStore.typeLabel(m.type) + ", v" + m.latestVersion + ")"
                setTextColor(th.text); textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "🗑"
                setTextColor(th.text)
                background = rounded(th.tileBg, 16)
                minWidth = 0; minHeight = 0
                setOnClickListener { confirmDeleteArtifactFromList(m) }
            })
            row.setOnClickListener {
                artifactListDialog?.dismiss()
                artifactListDialog = null
                openArtifact(m.id)
            }
            list.addView(row)
        }
        val scroll = ScrollView(this).apply { addView(list) }
        return AlertDialog.Builder(this)
            .setTitle("Artifact sesi ini")
            .setView(scroll)
            .setNegativeButton("Tutup", null)
            .setOnDismissListener { artifactListDialog = null }
            .create()
    }

    // vc268: konfirmasi hapus dari panel artifact (mockup state b).
    // Judul + jumlah versi disebut eksplisit; hapus = permanen.
    internal fun MainActivity.confirmDeleteArtifact() {
        val m = artifactCurrentMeta ?: return
        android.app.AlertDialog.Builder(host)
            .setTitle("Hapus artifact?")
            .setMessage("'" + m.title + "' (" + ArtifactStore.typeLabel(m.type) + ", v" + m.latestVersion + ", " + m.versionCount + " versi)\n\nHapus juga semua versi (v1..v" + m.versionCount + ")? Hapus bersifat permanen dan tidak bisa dipulihkan.")
            .setPositiveButton("Hapus") { _, _ -> doDeleteArtifact(m.id) }
            .setNegativeButton("Batal", null)
            .show()
    }

    // vc268: konfirmasi hapus dari dialog daftar sesi (per baris).
    internal fun MainActivity.confirmDeleteArtifactFromList(m: ArtifactMeta) {
        android.app.AlertDialog.Builder(host)
            .setTitle("Hapus artifact?")
            .setMessage("'" + m.title + "' (" + ArtifactStore.typeLabel(m.type) + ", v" + m.latestVersion + ", " + m.versionCount + " versi)\n\nHapus juga semua versi (v1..v" + m.versionCount + ")? Hapus bersifat permanen dan tidak bisa dipulihkan.")
            .setPositiveButton("Hapus") { _, _ -> doDeleteArtifact(m.id) }
            .setNegativeButton("Batal", null)
            .show()
    }

    // vc268: eksekusi hapus — tutup panel, tandai kartu di chat,
    // toast, refresh dialog daftar bila sedang terbuka.
    internal fun MainActivity.doDeleteArtifact(id: String) {
        val n = try { ArtifactStore.get(this).delete(id) } catch (e: Exception) { -1 }
        if (n < 0) {
            Toast.makeText(this, "Artifact tidak ditemukan.", Toast.LENGTH_SHORT).show()
            return
        }
        markArtifactCardDeleted(id)
        closeArtifactPanel()
        Toast.makeText(this, "Artifact dihapus", Toast.LENGTH_SHORT).show()
        refreshArtifactListDialog()
    }

    // vc268: kartu artifact yang sudah dihapus menampilkan state
    // terhapus (mockup state c) — bukan crash, bukan kartu kosong
    // yang masih bisa dibuka. Kartu dicari lewat tag id di chatList
    // (tanpa peta terpisah, jadi tidak ada referensi view yang
    // tertahan antar sesi).
    internal fun MainActivity.markArtifactCardDeleted(id: String) {
        val run = Runnable {
            val th = currentTheme()
            var target: LinearLayout? = null
            for (i in 0 until chatList.childCount) {
                val wrap = chatList.getChildAt(i) as? LinearLayout ?: continue
                for (j in 0 until wrap.childCount) {
                    val box = wrap.getChildAt(j) as? LinearLayout ?: continue
                    if (box.tag == id) { target = box; break }
                }
                if (target != null) break
            }
            val box = target ?: return@Runnable
            box.removeAllViews()
            box.addView(TextView(this).apply {
                text = "🗑 Artifact dihapus"
                setTextColor(th.dim); textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            box.addView(TextView(this).apply {
                text = "Artifact ini sudah dihapus beserta semua versinya."
                setTextColor(th.dim); textSize = 13f
                setPadding(0, dp(4), 0, 0)
            })
            box.setOnClickListener(null)
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) run.run() else runOnUiThread(run)
    }
