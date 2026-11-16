package com.senzwh.deepharness.priv

import com.senzwh.deepharness.NotifIds // vc246 perbaikan pasca-patch: registry di paket induk
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build

// vc170 — Input kode pairing S3 lewat notifikasi (ala Shizuku):
// user tetap di layar Settings (dialog kode tampil) — cukup tarik shade,
// ketik 6 digit, kirim. Kode TIDAK pernah ditulis ke notifikasi hasil,
// log, atau prefs. Notifikasi disembunyikan dari lock screen.
object PrivPairNotif {
    // vc171: pindah dari 7703 — id itu dipakai notif parkir BgBrain.
    // vc246 GELOMBANG 5 (K-11): pindah lagi dari 7704 — id itu dipakai
    // notif konfirmasi kedua BgBrain (BgBrain.kt notify 7704). ID baru 7708
    // tercatat di NotifIds.kt (registry terpusat, bebas tabrakan) dan ikut
    // daftar sapu BgBrain/HarnessService.
    // Slice 4 (vc317): `const val` -> `val` dengan getter — NotifIds
    // kini Rust-backed (bukan konstanta compile-time), dan nilai ini
    // dipakai hanya saat runtime (nm.notify), jadi bentuk `val` cukup.
    val NOTIF_ID: Int get() = NotifIds.PRIV_PAIR
    const val ACTION_REPLY = "com.senzwh.deepharness.PRIV_PAIR_REPLY"
    const val KEY_CODE = "pair_code"
    private const val CHANNEL = "harness-alert"

    private fun nm(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26 && nm(ctx).getNotificationChannel(CHANNEL) == null) {
            nm(ctx).createNotificationChannel(
                NotificationChannel(CHANNEL, "Pengingat & briefing", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun replyAction(ctx: Context): Notification.Action {
        val replyIntent = Intent(ctx, PrivPairReceiver::class.java).apply { action = ACTION_REPLY }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(ctx, 0, replyIntent, flags)
        val input = RemoteInput.Builder(KEY_CODE).setLabel("Kode pairing 6 digit").build()
        return Notification.Action.Builder(android.R.drawable.ic_input_add, "Ketik kode", pi)
            .addRemoteInput(input).build()
    }

    // Kirim notifikasi input kode (ongoing, tidak bisa di-swipe selama pairing).
    fun postPairNotif(ctx: Context) {
        ensureChannel(ctx)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Mode ADB — Input Kode Pairing")
            .setContentText("Tampilkan dialog kode di Wireless debugging, lalu ketik 6 digit di sini (tanpa keluar dari Settings).")
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .addAction(replyAction(ctx))
            .build()
        nm(ctx).notify(NOTIF_ID, n)
    }

    // Perbarui notifikasi jadi hasil. keepReply=true: aksi balas tetap ada
    // (gagal → user bisa balas lagi dengan kode baru). Kode tidak pernah
    // ditulis ke teks hasil. ongoing=true hanya untuk status "memasangkan".
    fun updateResult(ctx: Context, text: String, keepReply: Boolean, ongoing: Boolean = false) {
        ensureChannel(ctx)
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Mode ADB — Pairing")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(ongoing)
        if (keepReply) b.addAction(replyAction(ctx))
        nm(ctx).notify(NOTIF_ID, b.build())
    }
}
