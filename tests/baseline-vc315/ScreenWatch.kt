package com.senzwh.deepharness

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v4.10.0 — Kerangka PANTAU LAYAR (permintaan Sendy: popup tombol Izinkan di
 * LUAR app agar bot bisa terus mengecek pergerakan layar).
 *
 * Alur: agen memanggil tool watch_screen{action:"start"} -> popup melayang
 * (WindowManager, tampil di atas aplikasi apa pun) berisi alasan + tombol
 * [Izinkan]/[Tolak]. Izinkan -> CaptureConsentActivity menjalankan dialog
 * izin MediaProjection bawaan Android -> ScreenWatchService (FGS
 * mediaProjection) mulai memantau: tiap ~1,2 dtk bingkai layar dibandingkan,
 * perubahan >3% dicatat (momen + % + aplikasi terdepan). Yang disimpan HANYA
 * momen perubahan + satu potret JPEG terakhir — bukan rekaman video.
 * Berhenti otomatis 20 menit; pill melayang + notifikasi menyediakan tombol
 * Berhenti kapan pun.
 */
object ScreenWatch {

    private const val PREFS = "harness"
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var consentLatch: CountDownLatch? = null
    @Volatile private var consentResult: String = ""
    private var consentView: View? = null
    private var pillView: View? = null
    private var pillText: TextView? = null
    @Volatile private var lastTickWrite = 0L

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun wm(ctx: Context): WindowManager =
        ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    fun frameFile(ctx: Context): File = File(ctx.filesDir, "watch-frame.jpg")

    fun isActive(ctx: Context): Boolean = prefs(ctx).getBoolean("watchActive", false)

    fun tickFresh(ctx: Context): Boolean =
        System.currentTimeMillis() - prefs(ctx).getLong("watchLastTick", 0) < 20_000

    // ---------------- API untuk tool ---------------------------------------

    /** Dipanggil dari thread agen — BLOKIR sampai user memutuskan (maks 75 dtk). */
    fun requestStart(ctx: Context, reason: String): String {
        if (isActive(ctx) && tickFresh(ctx)) return "Pantau layar SUDAH aktif.\n" + statusText(ctx)
        if (isActive(ctx)) stop(ctx, "sesi lama tidak merespons")
        if (!Settings.canDrawOverlays(ctx)) {
            return "Belum bisa menampilkan popup izin: izin 'Tampilkan di atas aplikasi lain' (overlay) belum aktif. Minta user mengaktifkannya lewat Setup (kartu Izin -> Overlay), lalu panggil watch_screen start lagi."
        }
        val agent = try { Agents.active(ctx).name } catch (e: Exception) { "Agen" }
        consentResult = ""
        val latch = CountDownLatch(1)
        consentLatch = latch
        showConsent(ctx, agent, reason.ifBlank { "memantau progres di layar" })
        val answered = try { latch.await(75, TimeUnit.SECONDS) } catch (e: Exception) { false }
        hideConsent()
        consentLatch = null
        if (!answered) {
            return "Popup izin pantau layar tidak dijawab dalam 75 detik — pantauan TIDAK dimulai. Sampaikan ke user bahwa izin bisa diberikan saat popup muncul, lalu tawarkan mencoba lagi."
        }
        return when (consentResult) {
            "granted" -> "Pantau layar DIMULAI (maks 20 menit). Layar kini dipantau: setiap perubahan berarti dicatat. Gunakan watch_screen action=status untuk membaca perubahan, action=frame untuk potret terakhir, action=stop untuk berhenti. JANGAN memanggil start lagi selama aktif."
            else -> "User MENOLAK izin pantau layar (atau dialog izin sistem dibatalkan). Pantauan tidak dimulai — hormati keputusan ini, jangan memaksa, lanjutkan tugas dengan cara lain atau tanya user."
        }
    }

    fun statusText(ctx: Context): String {
        val p = prefs(ctx)
        if (!p.getBoolean("watchActive", false)) return "Pantau layar TIDAK aktif. Mulai dengan watch_screen action=start (akan memunculkan popup izin ke user)."
        val since = p.getLong("watchSince", 0)
        val mins = if (since > 0) ((System.currentTimeMillis() - since) / 60000).toInt() else 0
        val changes = p.getInt("watchChanges", 0)
        val fresh = tickFresh(ctx)
        val sb = StringBuilder()
        sb.append("Pantau layar AKTIF sejak $mins mnt lalu")
        if (!fresh) sb.append(" — TAPI tidak ada detak >20 dtk (layanan mungkin terhenti; panggil stop lalu start lagi)")
        sb.append(". Total perubahan berarti: $changes.")
        try {
            val arr = JSONArray(p.getString("watchEvents", "[]") ?: "[]")
            if (arr.length() > 0) {
                sb.append("\nKejadian terakhir:")
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                val from = maxOf(0, arr.length() - 6)
                for (i in from until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    sb.append("\n- ").append(fmt.format(Date(e.optLong("ts"))))
                        .append(" berubah ").append(e.optInt("diff")).append("%")
                    val pkg = e.optString("pkg")
                    if (pkg.isNotBlank()) sb.append(" • ").append(pkg.substringAfterLast('.'))
                }
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    fun frameInfo(ctx: Context): String {
        val f = frameFile(ctx)
        if (!f.exists() || f.length() == 0L) return "Belum ada potret layar. Potret disimpan otomatis saat perubahan layar terdeteksi selama pantauan aktif."
        val age = ((System.currentTimeMillis() - f.lastModified()) / 1000).toInt()
        return "Potret layar terakhir: ${f.absolutePath} (${f.length() / 1024} KB, $age dtk lalu). Untuk MELIHAT isinya pakai analyze_photo pada path ini (mesin DeepSeek) — mesin Bridge belum bisa membaca gambar."
    }

    fun stop(ctx: Context, why: String): String {
        try {
            val i = Intent(ctx, ScreenWatchService::class.java).setAction(ScreenWatchService.ACTION_STOP)
            ctx.startService(i)
        } catch (e: Exception) {}
        onWatchStopped(ctx, why)
        return "Pantau layar dihentikan ($why)."
    }

    // ---------------- Callback dari consent activity & service -------------

    fun onConsentGranted(ctx: Context, resultCode: Int, data: Intent) {
        noteConsent(ctx, true)
        try {
            prefs(ctx).edit().putString("watchReason", lastReason).apply()
            val i = Intent(ctx, ScreenWatchService::class.java)
            i.putExtra("rc", resultCode)
            i.putExtra("capData", data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            consentResult = "granted"
        } catch (e: Exception) {
            consentResult = "denied"
        }
        consentLatch?.countDown()
    }

    fun onConsentDenied() {
        consentResult = "denied"
        consentLatch?.countDown()
    }

    /** v4.11.1: catat hasil izin rekam layar terakhir (untuk badge Setup 48).
     * Android tidak menyediakan izin MediaProjection yang persisten; yang
     * bisa dicatat hanya hasil persetujuan terakhir. */
    fun noteConsent(ctx: Context, granted: Boolean) {
        try {
            prefs(ctx).edit()
                .putString("mpStatus", if (granted) "diberikan" else "ditolak")
                .putLong("mpTs", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {}
    }

    fun consentStatusText(ctx: Context): String {
        val p = prefs(ctx)
        if (p.getBoolean("watchActive", false)) return "Pantau layar AKTIF"
        val st = p.getString("mpStatus", "") ?: ""
        if (st.isBlank()) return "Belum pernah diminta"
        val ts = p.getLong("mpTs", 0)
        val jam = if (ts > 0) SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(ts)) else ""
        return (if (st == "diberikan") "Terakhir diberikan" else "Terakhir ditolak") + (if (jam.isNotBlank()) " • " + jam else "")
    }

    /** v4.11.1: jalur manual dari Setup 48 — user memberikan izin langsung
     * lewat dialog sistem Android, sekaligus tes: sesi pantau dimulai dan
     * bisa dihentikan kapan pun dari pill/notifikasi (maks 20 menit). */
    fun armManual(ctx: Context) {
        lastReason = "tes manual dari Setup — izin diberikan langsung oleh user"
        try { prefs(ctx).edit().putString("watchReason", lastReason).apply() } catch (e: Exception) {}
        val i = Intent(ctx, CaptureConsentActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    @Volatile private var lastReason: String = ""

    fun onWatchStarted(ctx: Context, reason: String) {
        val now = System.currentTimeMillis()
        prefs(ctx).edit()
            .putBoolean("watchActive", true)
            .putLong("watchSince", now)
            .putLong("watchLastTick", now)
            .putInt("watchChanges", 0)
            .putString("watchEvents", "[]")
            .apply()
        try { EventDb.get(ctx).add("watch", currentPkg(ctx), "Pantau layar mulai: $reason") } catch (e: Exception) {}
        showPill(ctx)
    }

    fun onWatchStopped(ctx: Context, why: String) {
        if (!prefs(ctx).getBoolean("watchActive", false)) { hidePill(); return }
        prefs(ctx).edit().putBoolean("watchActive", false).apply()
        try { EventDb.get(ctx).add("watch", "", "Pantau layar berhenti: $why") } catch (e: Exception) {}
        hidePill()
    }

    fun tick(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastTickWrite < 4000) return
        lastTickWrite = now
        try { prefs(ctx).edit().putLong("watchLastTick", now).apply() } catch (e: Exception) {}
    }

    fun recordChange(ctx: Context, diffPct: Int) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val pkg = currentPkg(ctx)
        try {
            val arr = JSONArray(p.getString("watchEvents", "[]") ?: "[]")
            arr.put(JSONObject().put("ts", now).put("diff", diffPct).put("pkg", pkg))
            while (arr.length() > 14) arr.remove(0)
            p.edit()
                .putInt("watchChanges", p.getInt("watchChanges", 0) + 1)
                .putString("watchEvents", arr.toString())
                .putLong("watchLastTick", now)
                .apply()
        } catch (e: Exception) {}
        val n = p.getInt("watchChanges", 0)
        main.post { try { pillText?.text = "👁 Memantau • $n perubahan" } catch (e: Exception) {} }
    }

    fun currentPkg(ctx: Context): String {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 600_000, now)
            var pkg = ""
            val e = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) pkg = e.packageName
            }
            pkg
        } catch (ex: Exception) { "" }
    }

    // ---------------- Overlay: popup persetujuan + pill ---------------------

    private fun cardBg(ctx: Context, fill: Int, stroke: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(ctx, 16).toFloat()
        g.setStroke(dp(ctx, 1), stroke)
        return g
    }

    private fun showConsent(ctx: Context, agent: String, reason: String) {
        lastReason = reason
        main.post {
            try {
                hideConsentNow()
                val app = ctx.applicationContext
                val root = LinearLayout(app)
                root.orientation = LinearLayout.VERTICAL
                root.setPadding(dp(app, 16), dp(app, 14), dp(app, 16), dp(app, 14))
                root.background = cardBg(app, Color.rgb(20, 20, 20), Color.rgb(232, 163, 61))
                val title = TextView(app)
                title.text = "👁 Izin Pantau Layar"
                title.setTextColor(Color.rgb(240, 242, 248))
                title.textSize = 16f
                title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
                root.addView(title)
                val body = TextView(app)
                body.text = "$agent mau memantau perubahan layar: $reason.\nYang dicatat hanya momen layar berubah + aplikasi di depan — bukan rekaman video. Otomatis berhenti dalam 20 menit."
                body.setTextColor(Color.rgb(178, 186, 202))
                body.textSize = 13f
                root.addView(body)
                val row = LinearLayout(app)
                row.orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val btnYes = Button(app)
                btnYes.text = "Izinkan"
                val btnNo = Button(app)
                btnNo.text = "Tolak"
                row.addView(btnNo, lp)
                row.addView(btnYes, lp)
                root.addView(row)
                btnNo.setOnClickListener {
                    consentResult = "denied"
                    hideConsent()
                    consentLatch?.countDown()
                }
                btnYes.setOnClickListener {
                    hideConsent()
                    try {
                        val i = Intent(app, CaptureConsentActivity::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        app.startActivity(i)
                    } catch (e: Exception) {
                        consentResult = "denied"
                        consentLatch?.countDown()
                    }
                }
                val params = WindowManager.LayoutParams(
                    (app.resources.displayMetrics.widthPixels * 0.92).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.y = dp(app, 64)
                wm(app).addView(root, params)
                consentView = root
            } catch (e: Exception) {
                consentResult = "denied"
                consentLatch?.countDown()
            }
        }
    }

    private fun hideConsentNow() {
        val v = consentView ?: return
        consentView = null
        try { wm(v.context).removeView(v) } catch (e: Exception) {}
    }

    private fun hideConsent() {
        main.post { hideConsentNow() }
    }

    private fun showPill(ctx: Context) {
        main.post {
            try {
                hidePillNow()
                val app = ctx.applicationContext
                val root = LinearLayout(app)
                root.orientation = LinearLayout.HORIZONTAL
                root.setPadding(dp(app, 12), dp(app, 7), dp(app, 6), dp(app, 7))
                root.background = cardBg(app, Color.rgb(20, 20, 20), Color.rgb(77, 140, 255))
                val tv = TextView(app)
                tv.text = "👁 Memantau • 0 perubahan"
                tv.setTextColor(Color.rgb(240, 242, 248))
                tv.textSize = 12.5f
                root.addView(tv)
                pillText = tv
                val btn = Button(app)
                btn.text = "Berhenti"
                btn.textSize = 12f
                btn.setOnClickListener { stop(app, "tombol Berhenti pada pill melayang") }
                root.addView(btn)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.BOTTOM or Gravity.END
                params.y = dp(app, 96)
                params.x = dp(app, 10)
                wm(app).addView(root, params)
                pillView = root
            } catch (e: Exception) {}
        }
    }

    private fun hidePillNow() {
        val v = pillView ?: return
        pillView = null
        pillText = null
        try { wm(v.context).removeView(v) } catch (e: Exception) {}
    }

    private fun hidePill() {
        main.post { hidePillNow() }
    }
}
