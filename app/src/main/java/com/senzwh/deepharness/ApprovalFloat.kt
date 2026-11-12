package com.senzwh.deepharness

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v4.10.4 — IZIN MELAYANG: kartu persetujuan sebagai popup WindowManager yang
 * tampil DI ATAS aplikasi apa pun, bukan dialog activity.
 *
 * Akar masalah (kasus user 2026-10-23 10:47): persetujuan tool go_back
 * menunggu 26 menit tak terlihat — dialog AlertDialog pada activity yang
 * hidup tapi sedang di latar TIDAK melempar exception saat show(), jadi
 * jalur parkir v4.6.0 tidak pernah terpicu dan giliran menggantung sampai
 * user membuka app. Kini pemanggil memeriksa activityResumed lebih dulu:
 * app tidak di depan -> kartu ini yang tampil (atau parkir notifikasi bila
 * izin overlay tidak ada).
 *
 * Keamanan: tool ber-PIN TIDAK bisa disetujui di sini — tombol Izinkan
 * diganti "Buka App" (PIN hanya diketik di dialog dalam app). Keputusan
 * selalu eksplisit: tidak ada dismiss lewat sentuhan di luar kartu.
 */
object ApprovalFloat {

    private var root: View? = null

    private fun wm(ctx: Context) = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    @Synchronized
    fun isShowing(): Boolean = root != null

    @Synchronized
    fun hide(ctx: Context) {
        val v = root ?: return
        root = null
        try { wm(ctx).removeView(v) } catch (e: Exception) {}
    }

    private fun cardBg(stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.rgb(20, 20, 20))
        cornerRadius = 36f
        setStroke(2, stroke)
    }

    private fun btn(ctx: Context, label: String, bg: Int, stroke: Int): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 14f
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = 22f
            if (stroke != 0) setStroke(2, stroke)
        }
    }

    /**
     * Tampilkan kartu persetujuan. false = izin overlay tidak ada / gagal
     * pasang (pemanggil wajib jatuh ke jalur parkir notifikasi).
     * needsPin=true -> tombol setuju menjadi "Buka App" (onOpenApp).
     */
    @Synchronized
    fun show(ctx: Context, title: String, message: String, approveLabel: String, rejectLabel: String,
             needsPin: Boolean, onApprove: () -> Unit, onReject: () -> Unit, onOpenApp: () -> Unit): Boolean {
        if (!Settings.canDrawOverlays(ctx)) return false
        val app = ctx.applicationContext
        hide(app)
        val amber = Color.rgb(232, 163, 61)
        val card = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(app, 16), dp(app, 14), dp(app, 16), dp(app, 14))
            background = cardBg(amber)
        }
        card.addView(TextView(app).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val bodyText = if (needsPin) message + "\n\n🔒 Tool ini butuh PIN — persetujuan hanya bisa di dalam app." else message
        card.addView(TextView(app).apply {
            text = bodyText
            setTextColor(Color.rgb(198, 205, 217))
            textSize = 13f
            setPadding(0, dp(app, 6), 0, dp(app, 10))
        })
        val row = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        val rejectBtn = btn(app, rejectLabel, Color.rgb(32, 32, 32), Color.rgb(80, 80, 80))
        rejectBtn.setOnClickListener { hide(app); onReject() }
        row.addView(rejectBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val approveBtn = if (needsPin) btn(app, "Buka App", Color.rgb(46, 107, 230), 0)
            else btn(app, approveLabel, Color.rgb(46, 107, 230), 0)
        approveBtn.setOnClickListener {
            hide(app)
            if (needsPin) onOpenApp() else onApprove()
        }
        val lp2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp2.leftMargin = dp(app, 10)
        row.addView(approveBtn, lp2)
        card.addView(row)

        val dm = app.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            dm.widthPixels - dp(app, 28),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = dp(app, 84)
        return try {
            wm(app).addView(card, lp)
            root = card
            true
        } catch (e: Exception) {
            root = null
            false
        }
    }
}
