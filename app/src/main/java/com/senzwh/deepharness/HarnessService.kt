package com.senzwh.deepharness

import android.app.Notification
import android.app.NotificationChannel
import com.senzwh.deepharness.priv.AdbPairing
import com.senzwh.deepharness.priv.PrivServer
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.Calendar

/** Foreground service: pantau app foreground (UsageStats) + mesin proaktif
 *  (pengingat jatuh tempo, briefing pagi, rekap malam) + bubble overlay. */
class HarnessService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    // vc175: loop tick (poll 30 detik) jalan di thread khusus, BUKAN main
    // thread — tick lama di main thread = biang lag 2-5 detik saat buka app
    // + jank tiap 30 detik + tersangka ANR intermiten. Handler utama tetap
    // main thread karena wake-word (SpeechRecognizer) wajib di sana.
    private var tickThread: HandlerThread? = null
    private lateinit var tickHandler: Handler
    private var lastPkg = ""
    @Volatile private var running = false
    private var overlayView: View? = null

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            try { pollForeground() } catch (e: Exception) {}
            try { proactiveTick() } catch (e: Exception) {}
            try { privGuardTick() } catch (e: Exception) {}
            tickHandler.postDelayed(this, 30_000)
        }
    }

    private fun pollForeground() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - 120_000, now)
        val e = UsageEvents.Event()
        var fg = ""
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) fg = e.packageName
        }
        if (fg.isNotEmpty() && fg != lastPkg && fg != packageName) {
            lastPkg = fg
            EventDb.get(this).add("app", fg, "App aktif (usage): $fg")
        }
    }

    // ---------------- Proaktif ----------------

    private fun prefs() = getSharedPreferences("harness", MODE_PRIVATE)

    // ---- S7: penjaga server Mode ADB ----
    private var privTick = 0

    /** Cek tiap ±5 menit (10 tik x 30 dtk): server Mode ADB pernah hidup
     *  (privEverOn) tapi sekarang mati -> SATU notifikasi per episode mati.
     *  Bendera direset saat server terdeteksi hidup lagi. */
    private fun privGuardTick() {
        if (++privTick % 10 != 0) return
        val p = prefs()
        if (PrivServer.isAlive()) {
            if (p.getBoolean("privDeathNotified", false)) p.edit().putBoolean("privDeathNotified", false).apply()
            if (p.getBoolean("privAutoFailed", false)) p.edit().putBoolean("privAutoFailed", false).apply()
            return
        }
        if (!p.getBoolean("privEverOn", false)) return
        // S7 PENUH (v3.6.0): sudah pairing S3 → nyalakan SENDIRI via klien
        // tertanam (backoff 15 menit). Gagal → jatuh ke notifikasi di bawah.
        if (AdbPairing.isPaired(this)) {
            val now = System.currentTimeMillis()
            if (now - p.getLong("privAutoTryAt", 0) > 15 * 60_000L) {
                p.edit().putLong("privAutoTryAt", now).apply()
                Thread {
                    val ok = try { PrivServer.tryAutoStartEmbedded(this) } catch (e: Exception) { false }
                    if (!ok) prefs().edit().putBoolean("privAutoFailed", true).apply()
                }.start()
                return
            }
            if (!p.getBoolean("privAutoFailed", false)) return
        }
        if (p.getBoolean("privDeathNotified", false)) return
        p.edit().putBoolean("privDeathNotified", true).apply()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("harness-alert", "Pengingat & briefing", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(this, 7721,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "harness-alert")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Mode ADB mati")
            .setContentText("Server privileged berhenti & auto-start tertanam gagal (Wireless debugging mungkin mati — nyalakan lagi di Opsi pengembang). Ketuk untuk membuka harness, lalu Setup 38/35.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(7721, n)
    }

    // vc263 (NT-M5): penjaga serve :4199 + mitmproxy dari service latar —
    // dulu notif serve-mati hanya jalan di serveF3Tick() saat onResume
    // (app dibuka), mitmproxy tanpa notif sama sekali. Cek tiap ±5 menit
    // (10 tick x 30 dtk, pola privGuardTick). Serve: check() saja (TANPA
    // revive — revive tetap tugas foreground); mati = notif ID 7103 yang
    // sama + throttle 1 jam yang sama (pref serveDownNotifTs) dengan
    // foreground, hidup lagi = batalkan 7103. Mitmproxy: hanya bila ada
    // job tercatat (user pernah menjalankannya) dan statusnya bukan
    // RUNNING — SATU notifikasi per episode (flag mitmDeathNotified),
    // direset saat RUNNING lagi. TIDAK ada auto-start di sini.
    private var serveMitmTick = 0

    private fun serveMitmGuardTick() {
        if (++serveMitmTick % 10 != 0) return
        val p = prefs()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            val st = ServeHealth.check(this)
            if (st.alive) {
                try { nm.cancel(NotifIds.SERVE_DOWN) } catch (e: Exception) {}
            } else {
                val lastN = p.getLong("serveDownNotifTs", 0)
                if (System.currentTimeMillis() - lastN > 3600_000) {
                    p.edit().putLong("serveDownNotifTs", System.currentTimeMillis()).apply()
                    if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("harness_auto_heal") == null) nm.createNotificationChannel(android.app.NotificationChannel("harness_auto_heal", "Automation Perlu Perhatian", android.app.NotificationManager.IMPORTANCE_DEFAULT))
                    val pi = android.app.PendingIntent.getActivity(this, 73, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                    val nb = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, "harness_auto_heal") else android.app.Notification.Builder(this)
                    nm.notify(NotifIds.SERVE_DOWN, nb.setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("Serve :4199 tidur — perlu tindakan").setContentText("Serve mati selagi app tertutup. Buka app > Setup serve untuk menyalakan ulang serve.").setContentIntent(pi).setAutoCancel(true).build())
                }
            }
        } catch (e: Exception) {}
        try {
            val job = MitmProxy.findJob(this)
            if (job == null) {
                if (p.getBoolean("mitmDeathNotified", false)) p.edit().putBoolean("mitmDeathNotified", false).apply()
                return
            }
            if (job.status == "RUNNING") {
                if (p.getBoolean("mitmDeathNotified", false)) p.edit().putBoolean("mitmDeathNotified", false).apply()
                return
            }
            if (p.getBoolean("mitmDeathNotified", false)) return
            p.edit().putBoolean("mitmDeathNotified", true).apply()
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("harness-alert") == null) nm.createNotificationChannel(android.app.NotificationChannel("harness-alert", "Pengingat & briefing", android.app.NotificationManager.IMPORTANCE_HIGH))
            val pi = android.app.PendingIntent.getActivity(this, 7722, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = android.app.Notification.Builder(this, "harness-alert").setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("Mitmproxy mati").setContentText("Job mitmproxy tercatat tapi tidak jalan (status ${job.status}). Buka app > Setup 64 untuk menyalakan ulang.").setContentIntent(pi).setAutoCancel(true).build()
            nm.notify(NotifIds.MITM_DOWN, n)
        } catch (e: Exception) {}
    }

    // vc265 N2-m4: ID notifikasi deterministik per judul (tanpa acak) —
    // judul yang sama menimpa dirinya sendiri, judul berbeda tidak saling
    // menimpa. Rentang 7300..7363 di luar registry NotifIds (7101-7103,
    // 7108-7109, 7701-7723, 7790-7799, 7800+, 8100+, 40000+).
    private fun alertNotifId(title: String): Int = 7300 + (title.hashCode() and 0x3F)

    private fun alert(title: String, text: String, bigText: String = text) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("harness-alert", "Pengingat & briefing", NotificationManager.IMPORTANCE_HIGH))
            val nidAlert = alertNotifId(title)
            val pi = PendingIntent.getActivity(
                this, nidAlert,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(this, "harness-alert")
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(bigText))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(nidAlert, n)
        } catch (e: Exception) {}
    }

    /** Notifikasi pengingat + tombol "Tunda 10 mnt". */
    private fun reminderAlert(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("harness-alert", "Pengingat & briefing", NotificationManager.IMPORTANCE_HIGH))
            // vc265 N2-m4: ID deterministik per isi pengingat (tanpa acak) —
            // rentang 7400..7655, di luar registry NotifIds dan di luar
            // rentang alert() (7300..7363). Pengingat yang sama menimpa
            // dirinya sendiri; ID tetap disimpan agar receiver bisa
            // membatalkannya (lihat SnoozeReceiver ACTION_DONE).
            val nidReminder = 7400 + (text.hashCode() and 0xFF)
            val openPi = PendingIntent.getActivity(
                this, nidReminder,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val snoozeIntent = Intent(this, SnoozeReceiver::class.java).apply {
                action = SnoozeReceiver.ACTION_SNOOZE
                putExtra("text", text)
            }
            val snoozePi = PendingIntent.getBroadcast(
                this, (text.hashCode() and 0xFFFF),
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // vc246 GELOMBANG 5 (NT-m3): tombol "Tandai selesai" — dulu
            // pengingat hanya punya snooze. ID notif disimpan agar receiver
            // bisa membatalkannya (lihat SnoozeReceiver ACTION_DONE).
            try { getSharedPreferences("harness", MODE_PRIVATE).edit().putInt("lastReminderNotifId", nidReminder).apply() } catch (e: Exception) {}
            val doneIntent = Intent(this, SnoozeReceiver::class.java).apply { action = SnoozeReceiver.ACTION_DONE }
            val donePi = PendingIntent.getBroadcast(
                this, (text.hashCode() and 0xFFFF) + 1,
                doneIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(this, "harness-alert")
                .setContentTitle("⏰ Pengingat")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_lock_idle_alarm, "Tunda 10 mnt", snoozePi)
                .addAction(android.R.drawable.ic_menu_save, "Tandai selesai", donePi)
                .setAutoCancel(true)
                .build()
            nm.notify(nidReminder, n)
        } catch (e: Exception) { alert("⏰ Pengingat", text) }
    }

    private fun batteryPct(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (scale > 0) level * 100 / scale else level
    }

    private fun proactiveTick() {
        val p = prefs()
        if (!p.getBoolean("proactiveOn", true)) return
        try { BgBrain.sweepParked(this) } catch (e: Exception) {}
        // vc260 H-K1: sapu sekali-jalan parkir legacy pra-vc247 (flag prefs di dalam fungsi; no-op setelah jalan sekali).
        try { BgBrain.sweepLegacyParkedOnce(this) } catch (e: Exception) {}
        try { OcCoord.sweepStalePending(this) } catch (e: Exception) {}
        // vc263 (NT-M4): pangkas EventDb sekali sehari (retensi 30 hari —
        // pruneOlderThan30d sudah ada sejak vc246 tapi NOL pemanggil).
        try {
            val todayVc263 = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
            if (p.getInt("lastEventPruneDay", -1) != todayVc263) {
                p.edit().putInt("lastEventPruneDay", todayVc263).apply()
                val prunedVc263 = EventDb.get(this).pruneOlderThan30d()
                if (prunedVc263 > 0) EventDb.get(this).add("eventdb", packageName, "Retensi vc263: $prunedVc263 baris log >30 hari dihapus")
            }
        } catch (e: Exception) {}
        // vc263 (autoloop): watchdog tugas latar macet/berputar.
        try { BgBrain.watchdogTick(this) } catch (e: Exception) {}
        // vc263 (NT-M5): cek serve + mitmproxy dari tick latar (±5 mnt).
        try { serveMitmGuardTick() } catch (e: Exception) {}
        val now = System.currentTimeMillis()
        val mdb = MemoryDb.get(this)

        // 0) Koordinator OpenCode (Tahap D): lanjutkan antrean tugas yang
        // tertunda (gerbang baterai/jam hening diperiksa di dalam loop-nya).
        try { OcCoord.kick(this) } catch (e: Exception) {}

        // 1) Pengingat jatuh tempo (dengan aksi tunda 10 menit)
        for (r in mdb.dueReminders(now)) {
            reminderAlert(r.text)
            EventDb.get(this).add("reminder", packageName, "Pengingat jatuh tempo: ${r.text}")
            mdb.markReminderDone(r.id)
        }

        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR)
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        // 2) Briefing pagi (sekali sehari pada jam yang diatur)
        val briefingHour = p.getInt("briefingHour", 7)
        if (hour == briefingHour && p.getInt("lastBriefingDay", -1) != today) {
            p.edit().putInt("lastBriefingDay", today).apply()
            val upcoming = mdb.upcoming(5)
            val notifCount = EventDb.get(this).latest(200, "notif").count { now - it.ts < 12 * 3600_000 }
            val freeGb = try {
                val sf = StatFs(Environment.getExternalStorageDirectory().path)
                (sf.availableBytes / 1_000_000_000.0)
            } catch (e: Exception) { -1.0 }
            val sb = StringBuilder()
            sb.append("Baterai ${batteryPct()}%")
            if (freeGb >= 0) sb.append(" • storage bebas %.1f GB".format(freeGb))
            sb.append("\nNotifikasi 12 jam terakhir: $notifCount")
            if (upcoming.isEmpty()) sb.append("\nTidak ada pengingat aktif.")
            else {
                sb.append("\nPengingat berikutnya:")
                for (u in upcoming) sb.append("\n- [${MemoryDb.fmtTs(u.dueTs)}] ${u.text}")
            }
            // Tahap C: briefing disusun LLM oleh Otak Latar tepat di jamnya;
            // versi lokal ini tetap jadi fallback bila otak Models API mati.
            val goalsTxt = mdb.goalsText()
            val dataBrief = sb.toString() + if (goalsTxt.isNotBlank()) "\n\nTujuan aktif:\n$goalsTxt" else ""
            val started = BgBrain.submit(this,
                "Susun BRIEFING PAGI yang hangat dan ringkas (Bahasa Indonesia, santai) dari data berikut. Boleh melengkapi dengan tool baca (get_notifications, get_calendar_today, finance_summary). Jangan mengarang data di luar yang diberikan/dibaca.\n\nDATA:\n$dataBrief",
                "briefing", "☀️ Briefing pagi")
            if (!started) alert("☀️ Briefing pagi", sb.toString())
            EventDb.get(this).add("briefing", packageName, "Briefing pagi terkirim (otak latar: $started)")
        }

        // 3) Rekap malam jam 20:00 (sekali sehari)
        if (hour == 20 && p.getInt("lastDigestDay", -1) != today) {
            p.edit().putInt("lastDigestDay", today).apply()
            val rows = EventDb.get(this).latest(300)
            val todayRows = rows.filter { now - it.ts < 12 * 3600_000 }
            val notifN = todayRows.count { it.type == "notif" }
            val topApps = todayRows.filter { it.type == "app" }
                .groupingBy { it.pkg }.eachCount().entries
                .sortedByDescending { it.value }.take(3)
            val sb = StringBuilder("Hari ini: $notifN notifikasi tercatat.")
            if (topApps.isNotEmpty()) {
                sb.append("\nApp paling sering dibuka:")
                for ((pkg, n) in topApps) sb.append("\n- $pkg ($n x)")
            }
            sb.append("\nIstirahat yang cukup ya.")
            val started = BgBrain.submit(this,
                "Susun REKAP MALAM singkat dan hangat (Bahasa Indonesia, santai) dari data berikut. Boleh melengkapi dengan tool baca (finance_summary, get_timeline). Jangan mengarang data di luar yang diberikan/dibaca.\n\nDATA:\n$sb",
                "rekap", "🌙 Rekap malam")
            if (!started) alert("🌙 Rekap malam", sb.toString())
            EventDb.get(this).add("briefing", packageName, "Rekap malam terkirim (otak latar: $started)")
        }

        val minute = cal.get(Calendar.MINUTE)

        // 4) Tugas AI terjadwal yang jatuh tempo menit ini — dieksekusi
        // LANGSUNG oleh Otak Latar di jamnya (Tahap C); antre lama hanya
        // jadi fallback bila otak Models API mati.
        // vc243 GELOMBANG 2 (AG-M3): catch-up hari yang sama — jadwal yang
        // tick-nya terlewat tetap jalan sekali hari itu (lihat
        // dueSchedulesCatchUp). (GR-M5/AG-M4): ikatan sesi eksplisit jadwal
        // dioper ke submit agar konteks + laporan memakai sesi pemilik.
        // Jadwal terblokir gerbang dicatat sekali/hari (bukan tiap tick).
        for (sch in mdb.dueSchedulesCatchUp(hour, minute, today)) {
            if (BgBrain.jobsOff(this)) continue
            val schOwner = sch.ownerSession.takeIf { it > 0 }
            val started = BgBrain.submit(this, sch.prompt, "jadwal", null, "jadwal:${sch.id}", null, schOwner)
            if (started) {
                mdb.markScheduleRun(sch.id, today)
                p.edit().remove("schedBlockLogged_${sch.id}_$today").apply()
            } else if (BgBrain.lastBlockReason(this) == "sesi mati" || BgBrain.lastBlockReason(this) == "kuota") {
                // vc245 GELOMBANG 4 (XL-Kuota): job terblokir kuota diantrekan
                // seperti job terblokir "sesi mati" (sekali/hari + alert).
                // vc243: catch-up membuat jadwal ini muncul tiap tick — antre
                // + alert cukup sekali/hari, bukan tiap 30 detik.
                if (!p.getBoolean("schedBlockLogged_${sch.id}_$today", false)) {
                    p.edit().putBoolean("schedBlockLogged_${sch.id}_$today", true).apply()
                    MemoryDb.enqueuePrompt(this, sch.prompt)
                    alert("🤖 Tugas terjadwal siap", "Ketuk untuk menjalankan: ${sch.prompt}")
                }
            } else if (p.getBoolean("schedBlockLogged_${sch.id}_$today", false)) {
            } else {
                p.edit().putBoolean("schedBlockLogged_${sch.id}_$today", true).apply()
                EventDb.get(this).add("schedule", packageName, "Jadwal #${sch.id} tertahan (${BgBrain.lastBlockReason(this)}), dicoba lagi tick berikut (catch-up hari yang sama): ${sch.prompt}")
            }
            if (started) EventDb.get(this).add("schedule", packageName, "Jadwal #${sch.id} jatuh tempo: ${sch.prompt} (otak latar: $started)")
        }

        // 4b) Langkah tujuan jatuh tempo → diingatkan + dikerjakan Otak Latar
        try {
            val pinged = (p.getStringSet("bgGoalPinged", emptySet()) ?: emptySet()).toMutableSet()
            var pingChanged = false
            for ((g, s) in mdb.dueGoalSteps(now)) {
                if (pinged.contains(s.id.toString())) continue
                pinged.add(s.id.toString()); pingChanged = true
                reminderAlert("🎯 Langkah tujuan '${g.title}': ${s.text}")
                val goalOwner = p.getLong("goalOwnerSession_" + g.id, 0).takeIf { it > 0 }
                val gStarted = BgBrain.submit(this,
                    "Langkah tujuan jatuh tempo. Tujuan: '${g.title}'. Langkah (step_id=${s.id}): ${s.text}. Kerjakan sekarang dengan tool yang tersedia. Bila langkah benar-benar selesai, panggil update_goal action=complete_step step_id=${s.id}. Bila tidak bisa dikerjakan otomatis, jelaskan singkat apa yang perlu dilakukan user.",
                    "tujuan", "🎯 Langkah tujuan: ${g.title}", "tujuan:${g.id}", null, goalOwner)
                if (!gStarted) MemoryDb.enqueuePrompt(this, "Langkah tujuan '${g.title}' jatuh tempo: ${s.text} (step_id=${s.id})")
                EventDb.get(this).add("goal", packageName, "Langkah #${s.id} tujuan '${g.title}' jatuh tempo (otak latar: $gStarted)")
            }
            if (pingChanged) p.edit().putStringSet("bgGoalPinged", pinged).apply()
        } catch (e: Exception) {}

        // 4c) Kepemilikan tujuan (v4.9.0): tujuan aktif yang langkahnya
        // belum jatuh tempo pun dimajukan berkala (maks 1x / 6 jam, satu
        // tujuan per tick) — agen tidak menunggu diingatkan terus.
        try {
            val lastAdv = p.getLong("lastGoalAdvance", 0)
            if (now - lastAdv > 6 * 3600_000) {
                val cand = mdb.goals(true).firstOrNull { g -> mdb.goalSteps(g.id).any { !it.done } }
                if (cand != null) {
                    val nextStep = mdb.goalSteps(cand.id).first { !it.done }
                    p.edit().putLong("lastGoalAdvance", now).apply()
                    val advOwner = p.getLong("goalOwnerSession_" + cand.id, 0).takeIf { it > 0 }
                    val advStarted = BgBrain.submit(this,
                        "Majukan tujuan aktifmu secara mandiri (kepemilikan tujuan). Tujuan: '${cand.title}'. Langkah berikutnya yang belum selesai (step_id=${nextStep.id}): ${nextStep.text}. Kerjakan sekarang dengan tool yang tersedia semampumu. Bila selesai, panggil update_goal action=complete_step step_id=${nextStep.id}. Bila belum bisa selesai, buat kemajuan parsial yang nyata dan jelaskan sisanya di jawaban akhir.",
                        "tujuan-maju", "🎯 Memajukan tujuan: ${cand.title}", "tujuan:${cand.id}", null, advOwner)
                    EventDb.get(this).add("goal", packageName, "Kepemilikan tujuan: majukan '${cand.title}' langkah #${nextStep.id} (otak latar: $advStarted)")
                }
            }
        } catch (e: Exception) {}

        // 6) Rutinitas Agen (v4.12.0): tugas terjadwal yang ditulis user
        // sendiri di Settings. Sekali per rutinitas per hari, dieksekusi
        // Otak Latar; gerbang Gubernur (jam hening/Wi-Fi/kuota) berlaku.
        try {
            val rArr = try { org.json.JSONArray(p.getString("routines", "[]") ?: "[]") } catch (e: Exception) { org.json.JSONArray() }
            val nowMin = hour * 60 + minute
            for (i in 0 until rArr.length()) {
                if (BgBrain.jobsOff(this)) break
                val r = rArr.optJSONObject(i) ?: continue
                if (!r.optBoolean("enabled", true)) continue
                // vc195: routine terikat sesi yang sudah arsip TIDAK dieksekusi
                // (laporannya pun akan ditolak appendJobReport). Digest
                // dikecualikan: ia level room, routingnya ditangani sendiri.
                // vc246 GELOMBANG 5 (FT-m13): pengecualian digest dipersempit
                // — digest ke sesi yang SUDAH diarsip tetap dilewati (dulu
                // tidak dicek sama sekali, berbeda dengan routine biasa).
                val bindSid = r.optString("sessionId", "")
                if (bindSid.isNotBlank() && r.optString("kind", "") != "digest") {
                    val found = Automation.findSessionRoom(this, bindSid)
                    if (found != null) {
                        try { Automation.refreshSessionArchive(this, found.first, found.second) } catch (e: Exception) {}
                        if (found.second.optBoolean("archived", false)) {
                            EventDb.get(this).add("routine", packageName, "Rutinitas dilewati: sesi terikat sudah arsip - " + r.optString("prompt", "").take(80))
                            // vc246 GELOMBANG 5 (NT-m7 + rantai 20): beri tahu
                            // user SEKALI (bukan tiap tick) + simpan ke daftar
                            // laporan gagal-route agar bisa ditemukan di LOG.
                            try {
                                // vc246 perbaikan pasca-patch: rid di scope
                                // ini belum didefinisikan (didefinisikan di
                                // bawah blok ini) — pakai id routine lokal.
                                val ridVc246 = r.optString("id", i.toString())
                                val nk = "routineArchivedNotified_" + ridVc246
                                if (!p.getBoolean(nk, false)) {
                                    p.edit().putBoolean(nk, true).apply()
                                    alert("⏸️ Rutinitas berhenti (sesi arsip)", "Rutinitas '" + r.optString("prompt", "").take(80) + "' berhenti karena sesi terikatnya sudah diarsip.")
                                }
                                Automation.appendUndeliveredReport(this, "rutinitas:$ridVc246", "rutinitas", "Rutinitas dilewati: sesi terikat sudah arsip.", "sesi terikat sudah arsip")
                            } catch (e: Exception) {}
                            continue
                        }
                    }
                }
                val rid = r.optString("id", i.toString())
                // F2: job interval (interval_min > 0) vs harian (jam:menit).
                val intervalMin = r.optInt("interval_min", 0)
                if (intervalMin > 0) {
                    val lastTs = p.getLong("routineRunTs_$rid", 0)
                    if (lastTs > 0 && now - lastTs < intervalMin * 60_000) continue
                } else {
                    val tParts = (r.optString("time", "")).split(":")
                    if (tParts.size != 2) continue
                    val tMin = (tParts[0].toIntOrNull() ?: -1) * 60 + (tParts[1].toIntOrNull() ?: 0)
                    if (tMin < 0 || nowMin < tMin) continue
                    val rKey = "routineRun_" + rid
                    if (p.getInt(rKey, -1) == today) continue
                }
                // vc245 GELOMBANG 4 (FT-M5, prompt dinamis): routine sesi
                // menjalankan tugas pertama papan SAAT INI, bukan bekuan
                // saat dialog sesi disimpan.
                val prompt = Automation.effectiveRoutinePrompt(this, r, r.optString("prompt", ""))
                if (prompt.isBlank()) continue
                if (r.optString("target", "") == "grup") {
                    // vc246 GELOMBANG 5 (FT-M6): rutinitas grup kini lewat
                    // gerbang bgAllowed yang sama dengan rutinitas biasa
                    // (jam hening / WiFi-only) — dulu melewatinya begitu saja.
                    if (!BgBrain.bgAllowed(this)) {
                        EventDb.get(this).add("routine", packageName, "Rutinitas grup ditahan gerbang (jam hening/WiFi-only): " + prompt.take(80))
                        continue
                    }
                    val startedG = try { GroupChat.runBackgroundRound(this, prompt, "rutinitas:$rid") } catch (e: Exception) { false }
                    if (startedG) {
                        if (intervalMin > 0) p.edit().putLong("routineRunTs_$rid", now).apply()
                        else p.edit().putInt("routineRun_$rid", today).apply()
                    }
                    EventDb.get(this).add("routine", packageName, "Rutinitas grup jalan: " + prompt.take(120) + " (grup latar: " + startedG + ")")
                    continue
                }
                if (!BgBrain.bgAllowed(this)) break
                // vc198 Fase 3: routine kind=graph = konsolidasi latar jam
                // sepi (bukan tugas agen). Dijalankan di Thread sendiri
                // agar tick tidak tertahan panggilan LLM latar.
                if (r.optString("kind", "") == "graph") {
                    if (intervalMin > 0) p.edit().putLong("routineRunTs_$rid", now).apply()
                    else p.edit().putInt("routineRun_$rid", today).apply()
                    Thread { try { GraphJobs.runQuietConsol(this) } catch (e: Exception) {} }.start()
                    EventDb.get(this).add("routine", packageName, "Konsolidasi graph latar jalan (jam sepi).")
                    continue
                }
                val jobTools = r.optString("tools", "").split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet().takeIf { it.isNotEmpty() }
                val started = BgBrain.submit(this, prompt, "rutinitas", "⏰ Rutinitas: " + prompt.take(40), "rutinitas:$rid", jobTools)
                if (started) {
                    if (intervalMin > 0) p.edit().putLong("routineRunTs_$rid", now).apply()
                    else p.edit().putInt("routineRun_$rid", today).apply()
                } else if (BgBrain.lastBlockReason(this) == "sesi mati" || BgBrain.lastBlockReason(this) == "kuota") {
                    // vc245 GELOMBANG 4 (XL-Kuota): routine terblokir kuota
                    // diantrekan seperti routine terblokir "sesi mati".
                    MemoryDb.enqueuePrompt(this, prompt)
                }
                EventDb.get(this).add("routine", packageName, "Rutinitas jalan: " + prompt.take(120) + " (otak latar: " + started + ")")
            }
        } catch (e: Exception) {}

        // 7) Ringkasan Notifikasi Pagi (F5, v4.40.0): triage allowlist D1 +
        // scrub OTP/angka panjang + arsip notif 7 hari. Logika di TriageDigest.
        try { TriageDigest.runDaily(this, now, today, hour) } catch (e: Exception) {}

        // 5) Geofence: transisi keluar-masuk area
        try {
            val loc = Tools.lastLocation(this)
            if (loc != null && now - loc.time < 30 * 60_000) {
                for (g in mdb.geofences()) {
                    val target = android.location.Location("geofence")
                    target.latitude = g.lat; target.longitude = g.lng
                    val dist = loc.distanceTo(target)
                    val insideNow = dist <= g.radiusM
                    if (insideNow != g.inside) {
                        mdb.setGeofenceInside(g.id, insideNow)
                        val arah = if (insideNow) "MASUK" else "KELUAR dari"
                        val gPrompt = "${g.prompt}\n\n(Konteks otomatis: user baru saja $arah area '${g.name}'.)"
                        val gfStarted = BgBrain.submit(this, gPrompt, "geofence", "📍 Geofence '${g.name}'", "geofence:${g.id}", null, g.ownerSession.takeIf { it > 0 })
                        if (!gfStarted) MemoryDb.enqueuePrompt(this, gPrompt)
                        alert("📍 Geofence '${g.name}'", "Kamu $arah area ini — aksi terkait ${if (gfStarted) "dijalankan Otak Latar" else "diantrekan"}: ${g.prompt}")
                        EventDb.get(this).add("geofence", packageName, "Geofence ${g.name}: $arah (jarak ${dist.toInt()}m)")
                    }
                }
            }
        } catch (e: Exception) {}

        // 8) Konsolidasi memori malam (saat charging, sekali sehari — Tahap C)
        try {
            val battI = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = battI?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            if (plugged != 0 && (hour >= 22 || hour < 4) && p.getInt("lastConsolDay", -1) != today) {
                p.edit().putInt("lastConsolDay", today).apply()
                BgBrain.consolidate(this)
            }
        } catch (e: Exception) {}

        // 7) Komentar layar proaktif: detektor lokal di snapshot accessibility (tanpa LLM)
        try {
            if (p.getBoolean("screenWatchOn", false)) {
                val snapTs = A11yService.lastSnapshotTs
                if (snapTs > 0 && now - snapTs < 3 * 60_000 && now - p.getLong("lastScreenNudgeTs", 0) > 30 * 60_000) {
                    val low = A11yService.lastScreenText.lowercase()
                    val pkgNow = A11yService.lastScreenPkg
                    val payKeys = listOf("pembayaran berhasil", "transaksi berhasil", "berhasil dibayar", "payment successful", "pembayaran diterima")
                    val otpKeys = listOf("kode verifikasi", "kode otp", "verification code")
                    val arriveKeys = listOf("anda telah sampai", "kamu telah sampai", "you have arrived", "telah tiba di tujuan")
                    val fired: Pair<String, String>? = when {
                        payKeys.any { low.contains(it) } ->
                            "🧾 Pembayaran terdeteksi" to "User sepertinya baru menyelesaikan pembayaran (terdeteksi dari isi layar). Sapa singkat, tanyakan nominal dan keperluannya, lalu tawarkan mencatatnya. Konteks: pakai finance_summary untuk melihat catatan keuangan terakhirnya."
                        otpKeys.any { low.contains(it) } ->
                            "🔐 Kode verifikasi di layar" to "Ada kode verifikasi yang tampil di layar user barusan. Tanyakan apakah ia perlu dibantu (mis. dibacakan atau dicatat sementara). Jangan menebak isi kodenya."
                        pkgNow.contains("maps") && arriveKeys.any { low.contains(it) } ->
                            "📍 Sampai di tujuan" to "User baru sampai di tujuan (navigasi selesai). Tanyakan apakah ada yang perlu diingatkan atau dicatat terkait tempat ini."
                        else -> null
                    }
                    if (fired != null) {
                        p.edit().putLong("lastScreenNudgeTs", now).apply()
                        MemoryDb.enqueuePrompt(this, fired.second)
                        alert(fired.first, "Ketuk untuk membuka asisten — ada tawaran bantuan terkait layar terakhirmu.")
                        EventDb.get(this).add("screenwatch", pkgNow, "Komentar layar proaktif: ${fired.first}")
                    }
                }
            }
        } catch (e: Exception) {}

        // 6) Penjaga inaktivitas (pasca operasi): 6 jam tanpa aktivitas pada jam aktif
        try {
            val emergency = p.getString("emergencyNumber", "") ?: ""
            if (emergency.isNotBlank()) {
                val latestTs = EventDb.get(this).latest(1).firstOrNull()?.ts ?: now
                val alertTs = p.getLong("inactivityAlertTs", 0)
                if (alertTs > 0) {
                    if (latestTs > alertTs) {
                        p.edit().remove("inactivityAlertTs").apply()
                    } else if (now - alertTs > 30 * 60_000 && p.getInt("inactivityDay", -1) != today) {
                        p.edit().putInt("inactivityDay", today).remove("inactivityAlertTs").apply()
                        val loc2 = Tools.lastLocation(this)
                        val locTxt = if (loc2 != null) "https://maps.google.com/?q=${loc2.latitude},${loc2.longitude}" else "(lokasi tidak tersedia)"
                        try {
                            val ownerD1 = Flavor.ownerNameOr(this, "ini")
                            SmsManager.getDefault().sendTextMessage(emergency, null,
                                "[HARNESS] Tidak ada aktivitas HP $ownerD1 selama 6+ jam dan peringatan di HP tidak direspons 30 menit. Mohon cek kondisinya. Lokasi terakhir: $locTxt", null, null)
                            EventDb.get(this).add("safety", packageName, "SMS inaktivitas terkirim ke kontak darurat")
                        } catch (e: Exception) {
                            EventDb.get(this).add("safety", packageName, "SMS inaktivitas GAGAL: ${e.message}")
                        }
                    }
                } else if (now - latestTs > 6 * 3600_000 && hour in 6..22 && p.getInt("inactivityDay", -1) != today) {
                    p.edit().putLong("inactivityAlertTs", now).apply()
                    alert("⚠️ Kamu baik-baik saja?", "Tidak ada aktivitas HP terdeteksi selama 6 jam. Buka app apa pun untuk mematikan peringatan ini — bila 30 menit tidak ada respons, harness mengirim SMS ke kontak darurat.")
                    EventDb.get(this).add("safety", packageName, "Peringatan inaktivitas dikirim ke user")
                }
            }
        } catch (e: Exception) {}
    }

    // ---------------- Wake word (mode siaga) ----------------

    private var speechRec: SpeechRecognizer? = null
    private var wakeRestarting = false

    private fun startWakeWord() {
        if (speechRec != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        handler.post {
            try {
                val rec = SpeechRecognizer.createSpeechRecognizer(this)
                rec.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p0: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(p0: Float) {}
                    override fun onBufferReceived(p0: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(p0: Int, p1: Bundle?) {}
                    override fun onError(e: Int) { restartWake(2500) }
                    override fun onPartialResults(b: Bundle?) { checkWake(b) }
                    override fun onResults(b: Bundle?) {
                        if (!checkWake(b)) restartWake(800)
                    }
                })
                speechRec = rec
                listenWake()
            } catch (e: Exception) {}
        }
    }

    private fun listenWake() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            speechRec?.startListening(i)
        } catch (e: Exception) { restartWake(3000) }
    }

    private fun checkWake(b: Bundle?): Boolean {
        val texts = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        for (t in texts) {
            val low = t.lowercase()
            if (low.contains("hai asisten") || low.contains("hei asisten") || low.contains("halo asisten") ||
                low.contains("hai operator") || low.contains("ok asisten") || low.contains("oke asisten")) {
                try {
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("AUTO_VOICE", true)
                    })
                    EventDb.get(this).add("wake", packageName, "Wake word terdeteksi: $t")
                } catch (e: Exception) {}
                restartWake(12000)
                return true
            }
        }
        return false
    }

    private fun restartWake(delayMs: Long) {
        if (wakeRestarting) return
        wakeRestarting = true
        handler.postDelayed({
            wakeRestarting = false
            if (prefs().getBoolean("wakeWordOn", false) && speechRec != null) listenWake()
        }, delayMs)
    }

    private fun stopWakeWord() {
        handler.post {
            try { speechRec?.stopListening(); speechRec?.destroy() } catch (e: Exception) {}
            speechRec = null
        }
    }

    // ---------------- Overlay bubble ----------------

    private fun setupOverlay() {
        try {
            if (!prefs().getBoolean("overlayOn", false)) return
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return
            if (overlayView != null) return
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val sizeDp = prefs().getInt("bubbleSizeDp", 56)
            val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
            val iv = android.widget.ImageView(this)
            try { iv.setImageDrawable(packageManager.getApplicationIcon(packageName)) } catch (e: Exception) {}
            iv.alpha = (prefs().getInt("bubbleAlphaPct", 100).coerceIn(30, 100)) / 100f
            var dragStartX = 0f
            var dragStartY = 0f
            var lpStartX = 0
            var lpStartY = 0
            var downAt = 0L
            var moved = false
            val type = if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            val dm = resources.displayMetrics
            lp.x = prefs().getInt("bubbleX", dm.widthPixels - sizePx - 24)
            lp.y = prefs().getInt("bubbleY", dm.heightPixels / 3)
            iv.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartX = ev.rawX; dragStartY = ev.rawY
                        lpStartX = lp.x; lpStartY = lp.y
                        downAt = System.currentTimeMillis(); moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - dragStartX).toInt()
                        val dy = (ev.rawY - dragStartY).toInt()
                        if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 14) moved = true
                        if (moved) {
                            lp.x = (lpStartX + dx).coerceIn(0, dm.widthPixels - sizePx)
                            lp.y = (lpStartY + dy).coerceIn(0, dm.heightPixels - sizePx)
                            try { wm.updateViewLayout(iv, lp) } catch (e: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            try { prefs().edit().putInt("bubbleX", lp.x).putInt("bubbleY", lp.y).apply() } catch (e: Exception) {}
                        } else if (System.currentTimeMillis() - downAt < 400) {
                            if ((prefs().getString("bubbleTap", "panel") ?: "panel") == "app") {
                                try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                            } else {
                                openPanelOrNotify()
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            wm.addView(iv, lp)
            overlayView = iv
        } catch (e: Exception) {}
    }

    private fun removeOverlay() {
        try {
            val v = overlayView ?: return
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
            overlayView = null
        } catch (e: Exception) {}
    }

    // v4.10.1: panel gagal dibuka dari latar (MIUI kerap memblokir start
    // aktivitas dari service) TIDAK lagi diam-diam membuka app penuh —
    // tawarkan lewat notifikasi (ketukan notifikasi kebal blokir latar)
    // agar user sendiri yang memutuskan.
    private fun openPanelOrNotify() {
        try { startActivity(Intent(this, FloatingAgentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) {}
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val pi = PendingIntent.getActivity(this, 73, Intent(this, FloatingAgentActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notif = Notification.Builder(this, "harness")
                .setContentTitle("Panel asisten gagal dibuka langsung")
                .setContentText("Ketuk untuk membuka panel — atau aktifkan izin MIUI 'pop-up saat berjalan di latar' untuk Harness.")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(7108, notif)
        } catch (e: Exception) {}
    }

    // ---------------- Gestur goyang (v4.3.0) ----------------
    private var shakeMgr: SensorManager? = null
    private var shakeListener: SensorEventListener? = null
    private val shakeHits = ArrayList<Long>()
    private var lastShakeLaunch = 0L

    private fun startShake() {
        if (shakeListener != null) return
        try {
            val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            shakeMgr = sm
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent?) {
                    val ev = e ?: return
                    val g = kotlin.math.sqrt((ev.values[0] * ev.values[0] + ev.values[1] * ev.values[1] + ev.values[2] * ev.values[2]).toDouble()) / 9.81
                    if (g > 2.6) {
                        val now = System.currentTimeMillis()
                        shakeHits.add(now)
                        shakeHits.removeAll { now - it > 700 }
                        if (shakeHits.size >= 3 && now - lastShakeLaunch > 4000) {
                            lastShakeLaunch = now
                            shakeHits.clear()
                            try { startActivity(Intent(this@HarnessService, FloatingAgentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (ex: Exception) {}
                        }
                    }
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            shakeListener = l
            sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_UI)
        } catch (e: Exception) {}
    }

    private fun stopShake() {
        try { shakeListener?.let { shakeMgr?.unregisterListener(it) } } catch (e: Exception) {}
        shakeListener = null
    }

    // ---------------- Lifecycle ----------------

    override fun onCreate() {
        super.onCreate()
        // vc196: migrasi id sesi (service bisa hidup tanpa MainActivity —
        // START_STICKY — jadi tick scheduler tidak boleh jalan sebelum ini).
        try { Automation.migrateSessionIds(this) } catch (e: Exception) {}
        // vc198 Fase 3: seed SEKALI routine konsolidasi graph latar 03:00.
        try { GraphJobs.ensureSeedRoutine(this) } catch (e: Exception) {}
        // vc200 Fondasi: pasang tools proot di latar, sekali saja (skip bila
        // proot belum terpasang / sudah terpasang; tidak memblokir service).
        Thread { try { ProotTools.ensureToolsIfNeeded(this@HarnessService) } catch (e: Exception) {} }.start()
        // vc201 T2: pasang mitmproxy di latar (pola Fondasi; antre di belakang
        // installer Fondasi bila sedang jalan — tanpa memblokir service).
        Thread { try { ProotTools.ensureApiToolsIfNeeded(this@HarnessService) } catch (e: Exception) {} }.start()
        // vc208 L1: supervisor job proot (daemon, tick 60 dtk; hanya sesi
        // worker yang memegang job; tangan serve TIDAK PERNAH disentuh).
        Thread { try { ProotJobs.startSupervisor(this@HarnessService) } catch (e: Exception) {} }.start()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("harness", "Harness aktif", NotificationManager.IMPORTANCE_LOW))
        val openPi = PendingIntent.getActivity(
            this, 7, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, "harness")
            .setContentTitle("Harness aktif")
            .setContentText("Asisten tinggal di HP ini: memantau konteks, pengingat, dan briefing (lokal).")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openPi)
            .build()
        startForeground(7, notif)
        running = true
        val tt = HandlerThread("harness-tick")
        tt.start()
        tickThread = tt
        tickHandler = Handler(tt.looper)
        tickHandler.post(poll)
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Terapkan ulang preferensi overlay tiap start (dipanggil ulang dari Setup).
        removeOverlay()
        setupOverlay()
        if (prefs().getBoolean("wakeWordOn", false)) startWakeWord() else stopWakeWord()
        if (prefs().getBoolean("shakeSummon", false)) startShake() else stopShake()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { tickThread?.quitSafely() } catch (e: Exception) {}
        tickThread = null
        removeOverlay()
        stopWakeWord()
        stopShake()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
