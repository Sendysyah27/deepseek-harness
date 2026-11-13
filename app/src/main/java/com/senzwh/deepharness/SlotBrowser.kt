package com.senzwh.deepharness

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

/**
 * v4.46.0 (F1) — Browser slot DeepSeek di PROSES TERPISAH.
 *
 * KENAPA PROSES TERPISAH: satu aplikasi Android berbagi SATU cookie jar +
 * SATU localStorage per origin. Lima akun DeepSeek tidak bisa login
 * bersamaan dalam satu proses — login keempat akan menimpa login pertama.
 * Karena itu tiap slot berjalan di prosesnya sendiri (:slot1..:slot5) dan
 * memanggil WebView.setDataDirectorySuffix("slotN") SEBELUM WebView apa pun
 * dibuat di proses itu, sehingga tiap slot punya data-dir WebView sendiri
 * (cookie + localStorage terpisah per akun).
 *
 * Alur: user buka Browser N dari kartu "4 Browser" di Setup -> halaman
 * login DeepSeek tampil -> user login dengan mengetik di browser resmi
 * itu (password/OTP TIDAK PERNAH disentuh atau disimpan app) -> begitu
 * halaman chat.deepseek.com selesai dimuat, token + cookie dipanen dari
 * storage slot itu sendiri dan dikirim ke proses utama via broadcast
 * EKSPLISIT (setPackage + receiver exported=false). Proses utama
 * menyimpannya ke pool yang sudah ada (dsSlotN_token/cookie).
 *
 * STANDBY yang jujur: proses slot HANYA jalan saat user membukanya
 * (login/refresh). "Siap (login tersimpan)" di UI berarti token cache
 * sudah ada di pool + sesi tersimpan di data-dir slot — bukan berarti
 * proses slot sedang berjalan di latar.
 *
 * KEAMANAN: token/cookie TIDAK PERNAH ditampilkan di UI, di Toast, atau
 * di log dari file ini. Status hanya bilang terkirim / belum login.
 */
object SlotHarvest {
    const val ACTION = "com.senzwh.deepharness.SLOT_HARVEST"

    /** Kirim hasil panen ke proses utama — broadcast eksplisit ke paket sendiri. */
    fun send(ctx: Context, slot: Int, token: String, cookie: String) {
        try {
            val i = Intent(ACTION).setPackage(ctx.packageName)
            i.putExtra("slot", slot)
            i.putExtra("token", token)
            i.putExtra("cookie", cookie)
            ctx.sendBroadcast(i)
        } catch (e: Exception) {}
    }
}

/** Penerima panen slot — berjalan di proses utama (tanpa android:process
 *  di manifest), exported=false, hanya menerima broadcast eksplisit dari
 *  paket sendiri. Menyimpan ke pool yang sudah ada. */
class SlotHarvestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            if (intent.action != SlotHarvest.ACTION) return
            val slot = intent.getIntExtra("slot", 0)
            val token = intent.getStringExtra("token") ?: ""
            if (slot !in 1..5 || token.isBlank()) return
            val cookie = intent.getStringExtra("cookie") ?: ""
            AccountPool.saveSlot(ctx, slot, token, cookie)
        } catch (e: Exception) {}
    }
}

open class SlotBrowserActivity : Activity() {
    private var slot = 0
    private lateinit var webView: WebView
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var root: LinearLayout
    private var slotText = 0
    private var slotGreen = 0
    private fun dpSlot(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun slotRounded(color: Int, stroke: Int): android.graphics.drawable.GradientDrawable = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dpSlot(14).toFloat(); setColor(color); setStroke(dpSlot(1), stroke) }
    private fun setSlotStatus(title: String, sub: String, ok: Boolean) {
        statusTitle.text = title
        statusTitle.setTextColor(if (ok) slotGreen else slotText)
        statusSub.text = sub
    }

    /** JS panen token — PERSIS sama dengan DsApiClient.extractToken. */
    private val jsToken =
        "(function(){try{var t=localStorage.getItem('userToken');if(!t)return '';try{var o=JSON.parse(t);return o.value||t;}catch(e){return t;}}catch(e){return '';}})()"

    /** vc309 Worker A — tema gelap SlotBrowser, pola sama seperti
     *  MainActivity.isDarkTheme(): prefs "themeMode" + night mode OS untuk
     *  mode "sistem". Dipakai onCreate + onResume (bar ikut tema app). */
    private fun slotIsDarkVc309(): Boolean {
        val slotMode = try { getSharedPreferences("harness", MODE_PRIVATE).getString("themeMode", "gelap") } catch (e: Exception) { "gelap" }
        return when (slotMode) {
            "terang" -> false
            "sistem" -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
    }

    override fun onResume() {
        super.onResume()
        // vc309 Worker A — bar ikut tema bila night mode OS berubah saat
        // SlotBrowser di latar (mode "sistem"). Listener inset tidak
        // dipasang ulang (tetap hidup dari onCreate).
        try { SystemBarsVc309.applySystemBars(window, slotIsDarkVc309()) } catch (e: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        slot = intent.getIntExtra("slot", 0)
        if (slot !in 1..5) { finish(); return }
        // WAJIB: dipanggil SEBELUM WebView apa pun dibuat di proses ini.
        // Tanpa ini, keempat slot berbagi data-dir yang sama dan login
        // saling menimpa (persis masalah yang ingin dipecahkan F1).
        if (Build.VERSION.SDK_INT >= 28) {
            try { WebView.setDataDirectorySuffix("slot$slot") } catch (e: Exception) {}
        }
        val slotDark = slotIsDarkVc309()
        val cBg = if (slotDark) Color.rgb(0, 0, 0) else Color.rgb(244, 246, 251)
        val cCard = if (slotDark) Color.rgb(28, 28, 28) else Color.rgb(255, 255, 255)
        val cStroke = if (slotDark) Color.rgb(72, 72, 72) else Color.rgb(226, 232, 240)
        val cText = if (slotDark) Color.rgb(235, 238, 245) else Color.rgb(15, 27, 51)
        val cDim = if (slotDark) Color.rgb(150, 158, 175) else Color.rgb(100, 116, 139)
        val cTile = if (slotDark) Color.rgb(38, 38, 38) else Color.rgb(227, 236, 253)
        val cAccent = Color.rgb(77, 140, 255)
        slotText = cText
        slotGreen = if (slotDark) Color.rgb(80, 220, 140) else Color.rgb(21, 128, 61)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpSlot(16), dpSlot(12), dpSlot(16), dpSlot(8))
        }
        bar.addView(TextView(this).apply {
            text = "Browser $slot — DeepSeek"
            textSize = 16f
            setTextColor(cText)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(Button(this).apply {
            text = "📤 Kirim Sesi"
            background = slotRounded(cAccent, cAccent)
            setTextColor(Color.WHITE)
            setPadding(dpSlot(12), dpSlot(8), dpSlot(12), dpSlot(8))
            minWidth = 0; minHeight = 0
            setOnClickListener { tryHarvest(manual = true) }
        })
        bar.addView(Button(this).apply {
            text = "✕ Tutup"
            background = slotRounded(cCard, cStroke)
            setTextColor(cText)
            setPadding(dpSlot(12), dpSlot(8), dpSlot(12), dpSlot(8))
            minWidth = 0; minHeight = 0
            setOnClickListener { finish() }
        })
        root.addView(bar)
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpSlot(12), 0, dpSlot(12), dpSlot(8))
        }
        navRow.addView(Button(this).apply {
            text = "← Kembali"
            background = slotRounded(cTile, cStroke)
            setTextColor(cText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dpSlot(6), 0) }
            setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        })
        navRow.addView(Button(this).apply {
            text = "↻ Muat Ulang"
            background = slotRounded(cTile, cStroke)
            setTextColor(cText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { webView.reload() }
        })
        root.addView(navRow)
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = slotRounded(cCard, cStroke)
            setPadding(dpSlot(12), dpSlot(10), dpSlot(12), dpSlot(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dpSlot(12), 0, dpSlot(12), dpSlot(8)) }
        }
        statusTitle = TextView(this).apply { textSize = 13f; setTextColor(cText); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        statusSub = TextView(this).apply { textSize = 12f; setTextColor(cDim) }
        statusCard.addView(statusTitle)
        statusCard.addView(statusSub)
        root.addView(statusCard)
        setSlotStatus("Memuat halaman login DeepSeek…", "Login dengan akun DeepSeek-mu di browser ini.", false)
        root.addView(TextView(this).apply {
            text = "Login dengan akun DeepSeek-mu di browser ini. Password/OTP tidak pernah disimpan app — yang dipanen hanya token + cookie sesi, dan nilainya tidak pernah ditampilkan di sini. Biasanya sesi terkirim otomatis begitu login selesai — tombol Kirim Sesi hanya bila perlu kirim ulang."
            textSize = 11f
            setTextColor(cDim)
            setPadding(dpSlot(16), 0, dpSlot(16), dpSlot(8))
        })
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(webView)
        setContentView(root)
        // vc309 Worker A — bar ikut tema app (§2) + edge-to-edge penuh:
        // bar judul turun di bawah status bar, WebView terangkat di atas
        // nav bar / keyboard saat login. Warna konten SlotBrowser sendiri
        // (rgb hardcoded) tidak diubah — hanya bar + inset.
        try { SystemBarsVc309.applySystemBars(window, slotDark) } catch (e: Exception) {}
        try { SystemBarsVc309.installEdgeToEdge(window, root) } catch (e: Exception) {}
        setupWebView()
        webView.loadUrl("https://chat.deepseek.com/")
    }

    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.userAgentString = SlotClient.SLOT_UA
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        // Login Google butuh jendela popup OAuth — cerminan MainActivity.setupWebView.
        s.setSupportMultipleWindows(true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                var popupRef: WebView? = null
                val closePopup: () -> Unit = {
                    popupRef?.let { p -> (p.parent as? ViewGroup)?.removeView(p); p.destroy() }
                    popupRef = null
                    webView.visibility = View.VISIBLE
                }
                val popup = WebView(this@SlotBrowserActivity)
                popupRef = popup
                val ps = popup.settings
                ps.javaScriptEnabled = true
                ps.domStorageEnabled = true
                try { ps.userAgentString = s.userAgentString } catch (e: Exception) {}
                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
                popup.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) { closePopup() }
                }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean {
                        val u = url ?: return false
                        return if (u.contains("chat.deepseek.com")) { webView.loadUrl(u); closePopup(); true } else false
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                webView.visibility = View.GONE
                root.addView(popup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                // Panen otomatis tiap halaman DeepSeek selesai dimuat: login
                // yang baru selesai langsung terkirim tanpa tombol tambahan.
                // Mode otomatis DIAM bila belum login (tidak menimpa status
                // dengan "Belum login" di tiap halaman perantara).
                if (url.contains("chat.deepseek.com")) tryHarvest(manual = false)
            }
        }
    }

    /** Panen token + cookie dari storage slot ini, kirim ke proses utama.
     *  Nilai token/cookie TIDAK PERNAH ditulis ke UI/log. */
    private fun tryHarvest(manual: Boolean) {
        try {
            webView.evaluateJavascript(jsToken) { res ->
                val tok = try {
                    if (res == null) null else JSONArray("[$res]").optString(0).ifBlank { null }
                } catch (e: Exception) { null }
                if (tok.isNullOrBlank()) {
                    if (manual) {
                        setSlotStatus("Belum login", "Token tidak ditemukan di browser ini. Login dulu, lalu ketuk Kirim Sesi.", false)
                        Toast.makeText(this, "Belum login di Browser $slot.", Toast.LENGTH_SHORT).show()
                    }
                    return@evaluateJavascript
                }
                val ck = try { CookieManager.getInstance().getCookie("https://chat.deepseek.com") ?: "" } catch (e: Exception) { "" }
                SlotHarvest.send(this, slot, tok, ck)
                setSlotStatus("✓ Sesi Browser $slot terkirim ke app", "Token tidak ditampilkan. Kembali ke Setup → Mesin → 4 Browser untuk memakai slot ini.", true)
                if (manual) Toast.makeText(this, "Sesi Browser $slot terkirim.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            if (manual) setSlotStatus("Panen gagal", e.message ?: "", false)
        }
    }

    override fun onDestroy() {
        try { webView.destroy() } catch (e: Exception) {}
        super.onDestroy()
    }
}

/** Empat subclass tipis — satu per proses (lihat manifest). */
class SlotBrowser1 : SlotBrowserActivity()
class SlotBrowser2 : SlotBrowserActivity()
class SlotBrowser3 : SlotBrowserActivity()
class SlotBrowser4 : SlotBrowserActivity()
class SlotBrowser5 : SlotBrowserActivity()
