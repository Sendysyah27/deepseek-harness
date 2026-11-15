package com.senzwh.deepharness

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** Perekam crash: simpan stack trace ke filesDir + Download/harness-crash.txt
 *  supaya bisa didiagnosis tanpa logcat. */
object CrashGuard {
    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = "=== CRASH ${Date()} thread=${t.name} ===\n$sw"
                File(ctx.filesDir, "last-crash.txt").writeText(text)
                try {
                    val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    File(dl, "harness-crash.txt").writeText(text)
                } catch (x: Exception) {}
            } catch (x: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    fun lastCrash(ctx: Context): String? {
        val f = File(ctx.filesDir, "last-crash.txt")
        if (!f.exists()) return null
        return try { f.readText().take(1200) } catch (e: Exception) { null }
    }
}
