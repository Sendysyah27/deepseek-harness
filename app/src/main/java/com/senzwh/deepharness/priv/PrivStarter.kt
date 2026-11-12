package com.senzwh.deepharness.priv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Titik masuk server privileged Mode ADB (S1) — dijalankan app_process dari
 * start.sh yang ditulis harness (classpath = APK harness sendiri):
 *   app_process -Djava.class.path=<APK> /system/bin --nice-name=harness_priv
 *     com.senzwh.deepharness.priv.PrivStarter <token> <package>
 *
 * Membuat PrivService, menyerahkan binder-nya ke aplikasi lewat broadcast
 * eksplisit (binder menumpang Bundle "data" — pola transmisi yang sama
 * seperti REQUEST_BINDER Shizuku, dibuktikan di bedah 2026-10-22), lalu
 * Looper.loop() supaya proses hidup sebagai uid shell.
 */
object PrivStarter {

    const val ACTION = "com.senzwh.deepharness.action.PRIV_BINDER"
    // vc301 SF-D6: app yang restart meminta kirim ulang binder lewat
    // broadcast ini (PrivServer.requestRebind). Server pra-vc301 tidak
    // punya penerima ini — rebind penuh baru berlaku sesudah server
    // di-start ulang dari APK vc301 (Setup 35/38).
    const val ACTION_REBIND = "com.senzwh.deepharness.action.PRIV_BINDER_REBIND"
    private const val TAG = "HarnessPriv"

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val token = args.getOrNull(0) ?: ""
        val pkg = args.getOrNull(1) ?: "com.senzwh.deepharness"
        val service = PrivService()
        try {
            sendBinder(token, pkg, service)
            Log.i(TAG, "binder server terkirim ke $pkg (uid=" + android.system.Os.getuid() + ")")
        } catch (e: Throwable) {
            Log.e(TAG, "kirim binder gagal: " + e.message)
        }
        // vc301 SF-D6: penerima rebind best-effort — bila app restart dan
        // binder in-memory-nya hilang, app meminta server mengirim ulang
        // binder tanpa perlu start ulang server. Token harus cocok dengan
        // token startup server, kalau tidak permintaan diabaikan. Gagal
        // daftar tidak boleh menggagalkan server (try/catch).
        try {
            val sysCtx = systemContext()
            if (sysCtx != null) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        try {
                            val t = intent?.getStringExtra("token") ?: ""
                            if (t.isNotEmpty() && t == token) {
                                sendBinder(t, pkg, service)
                                Log.i(TAG, "binder server dikirim ulang atas permintaan app")
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "rebind gagal: " + e.message)
                        }
                    }
                }
                sysCtx.registerReceiver(receiver, IntentFilter(ACTION_REBIND))
                Log.i(TAG, "penerima rebind terdaftar")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "daftar penerima rebind gagal: " + e.message)
        }
        Looper.loop()
    }

    private fun sendBinder(token: String, pkg: String, binder: IBinder) {
        val ctx = systemContext() ?: throw IllegalStateException("systemContext null")
        val data = Bundle()
        data.putBinder("binder", binder)
        data.putString("token", token)
        val intent = Intent(ACTION)
            .setPackage(pkg)
            .putExtra("data", data)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        ctx.sendBroadcast(intent)
    }

    /** Context sistem dari proses app_process telanjang (refleksi ActivityThread). */
    private fun systemContext(): Context? {
        val atClass = Class.forName("android.app.ActivityThread")
        try {
            val thread = atClass.getMethod("systemMain").invoke(null)
            return atClass.getMethod("getSystemContext").invoke(thread) as? Context
        } catch (e: Throwable) {
            // Cadangan: ActivityThread yang sudah ada di proses ini.
            return try {
                val thread = atClass.getMethod("currentActivityThread").invoke(null)
                atClass.getMethod("getSystemContext").invoke(thread) as? Context
            } catch (e2: Throwable) {
                null
            }
        }
    }
}
