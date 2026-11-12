package com.senzwh.deepharness

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Jembatan hasil Termux RUN_COMMAND: Termux mengirim balik stdout/stderr/exitCode
 * lewat PendingIntent yang kita buat. Token membedakan tiap panggilan.
 */
object TermuxBridge {
    const val ACTION = "com.senzwh.deepharness.TERMUX_RESULT"

    class Holder {
        val latch = CountDownLatch(1)
        @Volatile var text: String? = null
    }

    private val pending = ConcurrentHashMap<String, Holder>()
    private val counter = AtomicLong(0)

    fun newCall(ctx: Context): Pair<String, PendingIntent> {
        val token = "t" + counter.incrementAndGet() + "_" + System.currentTimeMillis()
        pending[token] = Holder()
        val intent = Intent(ACTION).apply {
            setPackage(ctx.packageName)
            putExtra("token", token)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(ctx, token.hashCode(), intent, flags)
        return Pair(token, pi)
    }

    fun deliver(intent: Intent) {
        val token = intent.getStringExtra("token") ?: return
        val holder = pending[token] ?: return
        val sb = StringBuilder()
        try {
            val bundle = intent.getBundleExtra("result") ?: intent.extras
            if (bundle != null) {
                val stdout = bundle.getString("stdout") ?: ""
                val stderr = bundle.getString("stderr") ?: ""
                val exit = if (bundle.containsKey("exitCode")) bundle.getInt("exitCode").toString() else "?"
                val errmsg = bundle.getString("errmsg") ?: ""
                sb.append("Exit ").append(exit).append(":\n")
                if (stdout.isNotBlank()) sb.append(stdout).append("\n")
                if (stderr.isNotBlank()) sb.append("[stderr] ").append(stderr).append("\n")
                if (errmsg.isNotBlank()) sb.append("[err] ").append(errmsg)
            } else {
                sb.append("(hasil Termux kosong)")
            }
        } catch (e: Exception) {
            sb.append("(gagal membaca hasil Termux: ${e.message})")
        }
        holder.text = sb.toString().trim()
        holder.latch.countDown()
    }

    fun await(token: String, timeoutSec: Long): String? {
        val holder = pending[token] ?: return null
        return try {
            if (holder.latch.await(timeoutSec, TimeUnit.SECONDS)) holder.text else null
        } catch (e: Exception) { null } finally {
            pending.remove(token)
        }
    }
}
