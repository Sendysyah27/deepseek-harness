package com.senzwh.deepharness

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import android.widget.Toast

/** Widget home screen: tombol Chat + Perintah Suara. */
class HarnessWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_harness)
            val chatPi = PendingIntent.getActivity(
                context, 101,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val voicePi = PendingIntent.getActivity(
                context, 102,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("AUTO_VOICE", true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnWidgetChat, chatPi)
            views.setOnClickPendingIntent(R.id.btnWidgetVoice, voicePi)
            mgr.updateAppWidget(id, views)
        }
    }
}

/** Quick Settings tile: ketuk -> buka asisten dalam mode dengar suara. */
class QsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.label = "Senz Asisten"
            it.state = Tile.STATE_ACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("AUTO_VOICE", true)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(
                    this, 103, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {}
    }
}

/** Aksi "Tunda 10 mnt" pada notifikasi pengingat. */
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action == ACTION_SNOOZE) {
                val text = intent.getStringExtra("text") ?: return
                MemoryDb.get(context).addReminder(System.currentTimeMillis() + 10 * 60_000, text)
                Toast.makeText(context, "Pengingat ditunda 10 menit: $text", Toast.LENGTH_SHORT).show()
            }
            // vc246 GELOMBANG 5 (NT-m3): "Tandai selesai" membatalkan
            // notif pengingat terakhir (ID disimpan HarnessService).
            if (intent.action == ACTION_DONE) {
                try {
                    val nid = context.getSharedPreferences("harness", Context.MODE_PRIVATE).getInt("lastReminderNotifId", -1)
                    if (nid >= 0) (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(nid)
                    Toast.makeText(context, "Pengingat ditandai selesai.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }

    companion object {
        const val ACTION_SNOOZE = "com.senzwh.deepharness.SNOOZE_REMINDER"
        const val ACTION_DONE = "com.senzwh.deepharness.DONE_REMINDER"
    }
}
