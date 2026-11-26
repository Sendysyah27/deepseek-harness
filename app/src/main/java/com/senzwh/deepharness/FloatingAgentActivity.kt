package com.senzwh.deepharness

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** v4.3.0 — Panel Asisten (Asisten Digital Home + bubble agen).
 *  Muncul saat asisten dipanggil (tahan power/home bila Harness = asisten
 *  default), dari bubble melayang yang bisa digeser, gestur goyang, atau
 *  tombol tes. Mikrofon otomatis aktif (pref voiceAuto). Perintah kini
 *  DIJALANKAN LANGSUNG di panel lewat Otak Latar (BgBrain) — tanpa masuk
 *  app: hasil tampil di panel & dibacakan (pref panelSpeak). Tool berisiko
 *  tetap diparkir ke notifikasi persetujuan seperti mode latar lain. */
class FloatingAgentActivity : Activity(), RecognitionListener {
    private var recog: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var statusTv: TextView
    private lateinit var textTv: TextView
    private lateinit var resultTv: TextView
    private lateinit var apprCard: LinearLayout
    private lateinit var apprBody: TextView
    private lateinit var pulse: View
    @Volatile private var listening = false
    private var finalText = ""
    private val ui = Handler(Looper.getMainLooper())
    private var pulseOn = false
    private var parkPidVc242: String? = null
    private var headlessRunning = false
    private var pendingAppQuery = ""
    private var beforeId = 0L
    private var pollStart = 0L
    private val pulseTick = object : Runnable {
        override fun run() {
            pulseOn = !pulseOn
            try { pulse.alpha = if (pulseOn) 1f else 0.35f } catch (e: Exception) {}
            if (listening) ui.postDelayed(this, 450)
        }
    }
    private val pollTick = object : Runnable {
        override fun run() {
            try { refreshApproval() } catch (e: Exception) {}
            if (!headlessRunning) return
            val found = try { assistantAfter(beforeId) } catch (e: Exception) { null }
            if (!found.isNullOrBlank()) {
                headlessRunning = false
                statusTv.text = "Selesai — hasil dari agen:"
                resultTv.text = found
                speak(found.take(420))
                return
            }
            if (System.currentTimeMillis() - pollStart > 200_000) {
                headlessRunning = false
                statusTv.text = "Masih berjalan di latar — hasil akhir masuk notifikasi & riwayat chat. Tool berisiko mungkin menunggu persetujuan di notifikasi."
                return
            }
            ui.postDelayed(this, 2500)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(color: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) g.setStroke(dp(1), stroke)
        return g
    }

    private fun refreshApproval() {
        try {
            // vc242 K-03: kartu menampilkan parkir TERLAMA dari antrean
            // (bukan kunci tunggal) + pid-nya untuk tombol Setujui/Tolak.
            val parked = BgBrain.parkedList(this)
            if (parked.isEmpty()) { parkPidVc242 = null; apprCard.visibility = View.GONE; return }
            val first = parked.first()
            parkPidVc242 = first.first
            val tool = first.second
            val argsTxt = first.third
            apprBody.text = "Tool berisiko menunggu keputusan: $tool" + (if (parked.size > 1) " (+${parked.size - 1} parkir lain di antrean)" else "") + if (argsTxt.isNotBlank()) "\n" + argsTxt.take(160) else ""
            apprCard.visibility = View.VISIBLE
        } catch (e: Exception) {}
    }
    private fun prefB(k: String, d: Boolean): Boolean =
        try { getSharedPreferences("harness", MODE_PRIVATE).getBoolean(k, d) } catch (e: Exception) { d }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.BOTTOM
        root.setBackgroundColor(Color.argb(120, 0, 0, 0))
        root.setOnClickListener { finish() }

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val cardBg = GradientDrawable()
        cardBg.setColor(Color.rgb(18, 18, 18))
        cardBg.cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
        card.background = cardBg
        card.setPadding(dp(20), dp(16), dp(20), dp(22))
        card.setOnClickListener { }

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val icon = ImageView(this)
        try { icon.setImageDrawable(packageManager.getApplicationIcon(packageName)) } catch (e: Exception) {}
        head.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
        val title = TextView(this)
        title.text = "Asisten Harness"
        title.setTextColor(Color.WHITE)
        title.textSize = 16.5f
        title.setPadding(dp(10), 0, 0, 0)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(this)
        close.text = "✕"
        close.setTextColor(Color.rgb(160, 170, 190))
        close.textSize = 18f
        close.setPadding(dp(10), dp(4), dp(6), dp(4))
        close.setOnClickListener { finish() }
        head.addView(close)
        card.addView(head)

        val statusRow = LinearLayout(this)
        statusRow.orientation = LinearLayout.HORIZONTAL
        statusRow.gravity = Gravity.CENTER_VERTICAL
        statusRow.setPadding(0, dp(14), 0, 0)
        pulse = View(this)
        val pd = GradientDrawable()
        pd.shape = GradientDrawable.OVAL
        pd.setColor(Color.rgb(77, 140, 255))
        pulse.background = pd
        statusRow.addView(pulse, LinearLayout.LayoutParams(dp(11), dp(11)))
        statusTv = TextView(this)
        statusTv.text = "Menyiapkan mikrofon…"
        statusTv.setTextColor(Color.rgb(190, 200, 220))
        statusTv.textSize = 13.5f
        statusTv.setPadding(dp(9), 0, 0, 0)
        statusRow.addView(statusTv)
        card.addView(statusRow)

        textTv = TextView(this)
        textTv.text = "…"
        textTv.setTextColor(Color.WHITE)
        textTv.textSize = 17f
        textTv.minLines = 2
        textTv.setPadding(0, dp(10), 0, dp(4))
        card.addView(textTv)

        resultTv = TextView(this)
        resultTv.setTextColor(Color.rgb(205, 226, 255))
        resultTv.textSize = 14.5f
        resultTv.maxLines = 9
        resultTv.movementMethod = ScrollingMovementMethod()
        resultTv.visibility = View.GONE
        resultTv.setPadding(0, dp(2), 0, dp(6))
        card.addView(resultTv)

        apprCard = LinearLayout(this)
        apprCard.orientation = LinearLayout.VERTICAL
        apprCard.background = roundedBg(Color.argb(36, 232, 163, 61), 16, Color.rgb(232, 163, 61))
        apprCard.setPadding(dp(14), dp(12), dp(14), dp(12))
        val apprTitle = TextView(this)
        apprTitle.text = "⚠ Permintaan Izin"
        apprTitle.setTextColor(Color.rgb(232, 163, 61))
        apprTitle.textSize = 14.5f
        apprTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        apprCard.addView(apprTitle)
        apprBody = TextView(this)
        apprBody.setTextColor(Color.WHITE)
        apprBody.textSize = 13.5f
        apprBody.setPadding(0, dp(4), 0, dp(8))
        apprCard.addView(apprBody)
        val apprRow = LinearLayout(this)
        apprRow.orientation = LinearLayout.HORIZONTAL
        val bYes = Button(this)
        bYes.text = "Setujui"
        bYes.isAllCaps = false
        bYes.setTextColor(Color.WHITE)
        bYes.background = roundedBg(Color.rgb(47, 191, 154), 12)
        bYes.setOnClickListener {
            try { BgBrain.resumeApproval(applicationContext, true, parkPidVc242) } catch (e: Exception) {}
            apprCard.visibility = View.GONE
            beforeId = lastHistoryId()
            headlessRunning = true
            pollStart = System.currentTimeMillis()
            resultTv.visibility = View.VISIBLE
            resultTv.text = "Agen sedang bekerja…"
            statusTv.text = "Disetujui — tugas dilanjutkan di latar."
            ui.removeCallbacks(pollTick)
            ui.postDelayed(pollTick, 2500)
        }
        apprRow.addView(bYes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val bNo = Button(this)
        bNo.text = "Tolak"
        bNo.isAllCaps = false
        bNo.setTextColor(Color.WHITE)
        bNo.background = roundedBg(Color.rgb(32, 32, 32), 12, Color.rgb(64, 64, 64))
        bNo.setOnClickListener {
            try { BgBrain.resumeApproval(applicationContext, false, parkPidVc242) } catch (e: Exception) {}
            apprCard.visibility = View.GONE
            statusTv.text = "Ditolak — agen diberi tahu untuk tidak menjalankan tool itu."
        }
        val bNoLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bNoLp.leftMargin = dp(10)
        apprRow.addView(bNo, bNoLp)
        apprCard.addView(apprRow)
        apprCard.visibility = View.GONE
        val apprLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        apprLp.topMargin = dp(10)
        card.addView(apprCard, apprLp)

        val inputRow = LinearLayout(this)
        inputRow.orientation = LinearLayout.HORIZONTAL
        val input = EditText(this)
        input.hint = "atau ketik perintah…"
        input.setHintTextColor(Color.rgb(130, 140, 160))
        input.setTextColor(Color.WHITE)
        input.textSize = 14.5f
        input.background = roundedBg(Color.rgb(26, 26, 26), 14, Color.rgb(64, 64, 64))
        input.setPadding(dp(12), dp(10), dp(12), dp(10))
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val bRun = Button(this)
        bRun.text = "Jalankan"
        bRun.isAllCaps = false
        bRun.setTextColor(Color.WHITE)
        bRun.background = roundedBg(Color.rgb(37, 99, 235), 12)
        bRun.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotBlank()) runHeadless(t)
        }
        val bRunLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        bRunLp.leftMargin = dp(8)
        inputRow.addView(bRun, bRunLp)
        card.addView(inputRow)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(10), 0, 0)
        val bMic = Button(this)
        bMic.text = "Mic"
        bMic.isAllCaps = false
        bMic.setTextColor(Color.WHITE)
        bMic.background = roundedBg(Color.rgb(77, 140, 255), 12)
        bMic.setOnClickListener { startListening() }
        row.addView(bMic, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val bOpen = Button(this)
        bOpen.text = "Buka di App"
        bOpen.isAllCaps = false
        bOpen.setTextColor(Color.WHITE)
        bOpen.background = roundedBg(Color.rgb(32, 32, 32), 12, Color.rgb(64, 64, 64))
        bOpen.setOnClickListener {
            try {
                val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (pendingAppQuery.isNotBlank()) i.putExtra("VOICE_QUERY", pendingAppQuery)
                startActivity(i)
            } catch (e: Exception) {}
            finish()
        }
        val bOpenLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bOpenLp.leftMargin = dp(8)
        row.addView(bOpen, bOpenLp)
        val bClose = Button(this)
        bClose.text = "Tutup"
        bClose.isAllCaps = false
        bClose.setTextColor(Color.WHITE)
        bClose.background = roundedBg(Color.rgb(32, 32, 32), 12, Color.rgb(64, 64, 64))
        bClose.setOnClickListener { finish() }
        val bCloseLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bCloseLp.leftMargin = dp(8)
        row.addView(bClose, bCloseLp)
        card.addView(row)

        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        refreshApproval()
        if (!prefB("voiceAuto", true)) {
            statusTv.text = "Suara otomatis nonaktif — ketuk Mic untuk bicara, atau ketik perintah."
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 77)
        } else {
            startListening()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startListening()
            else statusTv.text = "Izin mikrofon ditolak — aktifkan di Pengaturan agar asisten bisa mendengar."
        }
    }

    private fun startListening() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                statusTv.text = "Pengenalan suara tidak tersedia di HP ini — ketik perintah saja."
                return
            }
            stopRecog()
            recog = SpeechRecognizer.createSpeechRecognizer(this)
            recog?.setRecognitionListener(this)
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            listening = true
            pulseOn = false
            ui.removeCallbacks(pulseTick)
            ui.post(pulseTick)
            recog?.startListening(i)
        } catch (e: Exception) {
            listening = false
            statusTv.text = "Mikrofon gagal dimulai: ${e.message}"
        }
    }

    private fun stopRecog() {
        try { recog?.stopListening() } catch (e: Exception) {}
        try { recog?.destroy() } catch (e: Exception) {}
        recog = null
    }

    /** Jalankan perintah LANGSUNG dari panel lewat Otak Latar — tanpa masuk app. */
    private fun runHeadless(text: String) {
        val t = text.trim()
        if (t.isBlank() || headlessRunning) return
        finalText = t
        textTv.text = t
        beforeId = lastHistoryId()
        val ok = try { BgBrain.submit(applicationContext, t, "panel") } catch (e: Exception) { false }
        if (!ok) {
            // v4.10.1: mesin latar menolak (otak Models API belum siap / kuota Governor)
            // TIDAK lagi otomatis meneruskan ke app. Tetap di panel, jujur soal
            // alasan, dan serahkan pilihan ke user lewat tombol Buka di App.
            pendingAppQuery = t
            statusTv.text = "Mesin latar belum siap (otak Models API belum siap / kuota latar tercapai) — perintah TIDAK dijalankan. Ketuk 'Buka di App' untuk menjalankannya di dalam app, atau coba lagi nanti."
            resultTv.visibility = View.VISIBLE
            resultTv.text = "Tidak dijalankan di latar."
            return
        }
        headlessRunning = true
        pollStart = System.currentTimeMillis()
        resultTv.visibility = View.VISIBLE
        resultTv.text = "Agen sedang bekerja…"
        statusTv.text = "Perintah jalan di latar — hasil tampil di sini, tidak perlu masuk app."
        ui.removeCallbacks(pollTick)
        ui.postDelayed(pollTick, 2500)
    }

    private fun lastHistoryId(): Long {
        return try {
            val c = MemoryDb.get(this).readableDatabase.rawQuery("SELECT MAX(id) FROM history", null)
            val v = if (c.moveToFirst()) c.getLong(0) else 0L
            c.close()
            v
        } catch (e: Exception) { 0L }
    }

    private fun assistantAfter(afterId: Long): String? {
        return try {
            val c = MemoryDb.get(this).readableDatabase.rawQuery(
                "SELECT text FROM history WHERE id > ? AND role <> 'user' ORDER BY id ASC LIMIT 1",
                arrayOf(afterId.toString())
            )
            val v = if (c.moveToFirst()) c.getString(0) else null
            c.close()
            v
        } catch (e: Exception) { null }
    }

    private fun speak(text: String) {
        if (!prefB("panelSpeak", true)) return
        try {
            if (tts == null) {
                tts = TextToSpeech(this) { st ->
                    if (st == TextToSpeech.SUCCESS) {
                        try { tts?.language = Locale("id", "ID") } catch (e: Exception) {}
                        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "panel-tts") } catch (e: Exception) {}
                    }
                }
            } else {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "panel-tts")
            }
        } catch (e: Exception) {}
    }

    override fun onReadyForSpeech(params: Bundle?) { statusTv.text = "Mendengarkan… ucapkan perintahmu" }
    override fun onBeginningOfSpeech() { statusTv.text = "Mendengarkan…" }
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        listening = false
        try { pulse.alpha = 0.35f } catch (e: Exception) {}
    }
    override fun onError(error: Int) {
        listening = false
        try { pulse.alpha = 0.35f } catch (e: Exception) {}
        statusTv.text = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "Tidak terdengar jelas — ketuk Mic, atau ketik perintah."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tidak ada suara — ketuk Mic, atau ketik perintah."
            SpeechRecognizer.ERROR_AUDIO -> "Mikrofon bermasalah — ketik perintah saja."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Jaringan pengenalan suara bermasalah — ketik perintah saja."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin mikrofon belum diberikan."
            else -> "Suara berhenti (kode $error) — ketuk Mic untuk coba lagi."
        }
    }
    override fun onResults(results: Bundle?) {
        listening = false
        try { pulse.alpha = 0.35f } catch (e: Exception) {}
        val arr = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val t = arr?.firstOrNull()?.trim().orEmpty()
        if (t.isBlank()) {
            statusTv.text = "Tidak terdengar jelas — ketuk Mic, atau ketik perintah."
            return
        }
        runHeadless(t)
    }
    override fun onPartialResults(partialResults: Bundle?) {
        val arr = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val t = arr?.firstOrNull()?.trim().orEmpty()
        if (t.isNotBlank()) textTv.text = t
    }
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onResume() {
        super.onResume()
        try { refreshApproval() } catch (e: Exception) {}
    }

    override fun onDestroy() {
        listening = false
        headlessRunning = false
        ui.removeCallbacks(pulseTick)
        ui.removeCallbacks(pollTick)
        stopRecog()
        try { tts?.stop() } catch (e: Exception) {}
        try { tts?.shutdown() } catch (e: Exception) {}
        super.onDestroy()
    }
}
