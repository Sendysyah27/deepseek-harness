package com.senzwh.deepharness

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import com.senzwh.deepharness.MainActivity.ThemeColors

// ===== Terminal Pro + Command Center + Proot (T4a/T4b2) — dipindah dari MainActivity =====
// Audit: pecah MainActivity. Extension MainActivity; state tetap di MainActivity
// (internal). Body VERBATIM.

    internal fun MainActivity.termAppend(s: String) {
        if (!termOutReady()) return
        val cur = termOut.text?.toString() ?: ""
        var next = if (cur.isBlank() || cur == "Output terminal tampil di sini.") s else cur + "\n" + s
        if (next.length > 60000) next = next.substring(next.length - 60000)
        termOut.text = next
        termScroll?.post { try { termScroll?.fullScroll(View.FOCUS_DOWN) } catch (e: Exception) {} }
    }

    internal fun MainActivity.termHistList(): MutableList<String> {
        val raw = getSharedPreferences("harness", Context.MODE_PRIVATE).getString("termHistNative", "") ?: ""
        return if (raw.isBlank()) mutableListOf() else raw.split("\n").toMutableList()
    }

    internal fun MainActivity.termHistPush(cmd: String) {
        val h = termHistList()
        if (h.lastOrNull() != cmd) h.add(cmd)
        while (h.size > 100) h.removeAt(0)
        getSharedPreferences("harness", Context.MODE_PRIVATE).edit().putString("termHistNative", h.joinToString("\n")).apply()
        termHistIdx = -1
    }

    internal fun MainActivity.termHistNav(dir: Int) {
        val h = termHistList()
        if (h.isEmpty() || !termInputReady()) return
        termHistIdx = if (termHistIdx < 0) { if (dir < 0) h.size - 1 else return } else (termHistIdx + dir).coerceIn(0, h.size - 1)
        termInput.setText(h[termHistIdx])
        try { termInput.setSelection(termInput.text.length) } catch (e: Exception) {}
    }

    /** Jalankan perintah dari UI Terminal: mode sh = shell PERSISTEN
     *  (termRunPersistent), mode lain lewat Tools.runTerminal seperti biasa. */
    internal fun MainActivity.termRunFromUi() {
        val cmd = if (termInputReady()) termInput.text.toString().trim() else ""
        if (cmd.isEmpty()) return
        termHistPush(cmd)
        val mode = if (termModeReady()) termMode.selectedItem?.toString() ?: "sh" else "sh"
        termAppend("$mode> $cmd")
        Thread {
            val r = try { if (mode == "sh") termRunPersistent(cmd) else Tools.runTerminal(this, cmd, mode) } catch (e: Exception) { "Error: " + (e.message ?: "") }
            runOnUiThread { termAppend(r) }
        }.start()
    }

    internal fun MainActivity.showCommandCenter() {
        val th = currentTheme()
        val st = try { JSONObject(uiSettingsStatus()) } catch (e: Exception) { JSONObject() }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        lateinit var dlg: android.app.AlertDialog
        val lt = st.optString("liveTurn", "")
        val live = cardBoxT()
        live.addView(tv("AGEN LIVE", 12f, th.dim, true))
        live.addView(tv(if (lt.isNotBlank()) "⏳ " + lt else "Tidak ada giliran berjalan.", 12.5f, th.text))
        val j = st.optJSONObject("journal")
        if (j != null) {
            val lines = j.optJSONArray("lines")
            if (lines != null) {
                val from = maxOf(0, lines.length() - 8)
                for (i in from until lines.length()) live.addView(tv(lines.optString(i), 11.5f, th.dim))
            }
            if (j.optInt("ageSec", 0) > 90 && lt.isNotBlank()) live.addView(tv("⚠️ Tidak ada jejak baru >90 dtk — mungkin macet.", 12f, th.errText))
        }
        content.addView(live)
        val ocP = st.optString("ocPending", "")
        val ocB = st.optBoolean("ocBusy", false)
        val oc = cardBoxT()
        oc.addView(tv("KOORDINATOR OPENCODE", 12f, th.dim, true))
        oc.addView(tv(when { ocP.isNotBlank() -> "⏸ Menunggu keputusan: " + ocP; ocB -> "🤖 Koordinator sedang bekerja"; else -> "Koordinator siaga." }, 12.5f, th.text))
        if (ocP.isNotBlank() || ocB) {
            val rowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (ocP.isNotBlank()) {
                thRowBtn(rowB, "✅ Setujui") { dlg.dismiss(); uiOcResolve(true) }
                thRowBtn(rowB, "❌ Tolak") { dlg.dismiss(); uiOcResolve(false) }
            }
            thRowBtn(rowB, "⏹ Stop") { dlg.dismiss(); uiOcStop() }
            oc.addView(rowB)
            val rowC = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            thRowBtn(rowC, "✔ Terima Checkpoint") { dlg.dismiss(); uiOcCkpt(true) }
            thRowBtn(rowC, "↩ Urungkan") { dlg.dismiss(); uiOcCkpt(false) }
            oc.addView(rowC)
        }
        content.addView(oc)
        val team = st.optJSONObject("team")
        if (team != null) {
            val tc = cardBoxT()
            tc.addView(tv("TIM MULTI-AGEN", 12f, th.dim, true))
            tc.addView(tv("🎯 " + team.optString("goal", "") + " — " + team.optString("status", ""), 12.5f, th.text, true))
            val lanes = team.optJSONArray("lanes")
            if (lanes != null) for (i in 0 until lanes.length()) {
                val l = lanes.optJSONObject(i) ?: continue
                tc.addView(tv((if (l.optBoolean("done")) "✅ " else "⏳ ") + l.optString("slot") + ": " + l.optString("task"), 11.5f, th.text))
                val res = l.optString("result")
                if (res.isNotBlank()) tc.addView(tv(res, 11f, th.dim))
            }
            content.addView(tc)
        }
        val gov = cardBoxT()
        gov.addView(tv("STATUS LATAR", 12f, th.dim, true))
        gov.addView(tv("Gubernur: " + st.optInt("govCount", 0) + " / " + st.optInt("govCap", 500) + " panggilan hari ini", 12.5f, th.text))
        val ck = st.optLong("ckptTask", 0)
        gov.addView(tv(if (ck > 0) "⏸ Ada checkpoint tugas yang terputus — buka Chat untuk melanjutkan." else "Tidak ada checkpoint tertunda.", 12.5f, th.text))
        content.addView(gov)
        content.addView(thBtn("🔄 Segarkan") { dlg.dismiss(); showCommandCenter() })
        dlg = thDialog("🎛 Command Center", content, listOf(Triple("Tutup", false) { d: android.app.AlertDialog -> d.dismiss() }))
    }


    // ===== T4b2 (migrasi native): Ubuntu Proot =====

    internal fun MainActivity.prootDialog() {
        try { ProotEngine.repairLayout(this) } catch (e: Exception) {}
        val ready = ProotEngine.isReady(this)
        val lastRes = getSharedPreferences("harness", Context.MODE_PRIVATE).getString("prootLastResult", "") ?: ""
        val msg = ProotEngine.status(this) + "\n\nUbuntu 24.04 (aarch64) di dalam aplikasi — tanpa root, tanpa Termux. Rootfs diunduh sekali (±61 MB dari katalog proot-distro, SHA-256 diverifikasi sebelum ekstrak). Setelah terpasang: Terminal mode 'proot' & run_terminal mode \"proot\" menjalankan Ubuntu penuh (apt, python3, node, git). Terminal mode 'proot' kini 4 sesi pekerja (worker1..worker4, default worker1) + tangan serve terpisah yang tidak bisa dipakai run_terminal." + (if (lastRes.isNotBlank()) "\n\nHasil pasang terakhir: " + lastRes else "")
        val tv = TextView(this).apply { text = msg; setTextColor(TEXT); textSize = 13f; setPadding(dp(20), dp(16), dp(20), dp(16)) }
        var dlg: android.app.AlertDialog? = null
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv)
            fun btn(label: String, fn: () -> Unit) {
                addView(android.widget.Button(host).apply { text = label; setOnClickListener { dlg?.dismiss(); fn() } })
            }
            btn("⬇️ Unduh & Pasang") { prootInstallUi() }
            btn("🧪 Tes") {
                Thread {
                    val r = ProotEngine.run(host, "echo PROOT_OK; uname -m; head -2 /etc/os-release; id; python3 --version", 60)
                    runOnUiThread { android.app.AlertDialog.Builder(host).setTitle("Hasil Tes Proot").setMessage(r.take(3000)).setPositiveButton("OK", null).show() }
                }.start()
            }
            btn("🧰 Pasang Tools Proot") {
                Thread {
                    val r = ProotTools.ensureToolsSync(host, true)
                    val v = ProotTools.versions(host)
                    runOnUiThread { android.app.AlertDialog.Builder(host).setTitle("Tools Proot").setMessage((r + "\n\n" + v).take(3000)).setPositiveButton("OK", null).show() }
                }.start()
            }
            if (ready) {
                btn("🗑 Hapus Rootfs") {
                    android.app.AlertDialog.Builder(host).setTitle("Hapus rootfs Ubuntu?")
                        .setMessage("Seluruh isi Ubuntu (±61 MB + paket yang kamu pasang) dihapus. Bisa diunduh lagi kapan pun.")
                        .setPositiveButton("Hapus") { _, _ -> Thread { val m = ProotEngine.deleteRootfs(host); runOnUiThread { Toast.makeText(host, m, Toast.LENGTH_LONG).show() } }.start() }
                        .setNegativeButton("Batal", null).show()
                }
            }
        }
        dlg = android.app.AlertDialog.Builder(this).setTitle("🐧 Ubuntu Proot (Terminal)").setView(col)
            .setNegativeButton("Tutup", null).create()
        dlg?.show()
    }

    internal fun MainActivity.prootInstallUi() {
        val tv = TextView(this).apply { text = "Menyiapkan…"; setTextColor(TEXT); textSize = 13f; setPadding(dp(20), dp(16), dp(20), dp(16)) }
        val dlg = android.app.AlertDialog.Builder(this).setTitle("Memasang Ubuntu…").setView(tv).setCancelable(false).create()
        dlg.show()
        Thread {
            val msg = ProotEngine.downloadAndInstall(this) { note -> runOnUiThread { tv.text = note } }
            runOnUiThread {
                try { dlg.dismiss() } catch (e: Exception) {}
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                prootDialog()
            }
        }.start()
    }


    internal fun MainActivity.buildTerminalTab(): View {
        val th = currentTheme()
        val dark = isDarkTheme()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.bg); setPadding(dp(10), dp(8), dp(10), dp(8)) }
        box.addView(tv("Terminal", 16f, th.text, true))
        box.addView(tv("Mode sh = shell PERSISTEN di dalam app (cwd & env bertahan antar perintah, sesi app). Mode termux = kirim perintah ke Termux (background, perlu allow-external-apps). Mode shizuku = shell privilege ADB via rish (setup dulu di tab Setup). Mode priv = Mode ADB.", 12f, th.dim))
        termMode = Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(host, android.R.layout.simple_spinner_item, listOf("sh", "termux", "shizuku", "priv", "proot")) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    v.setTextColor(th.text)
                    return v
                }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getDropDownView(position, convertView, parent) as TextView
                    v.setTextColor(th.text); v.setBackgroundColor(th.card)
                    return v
                }
            }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        box.addView(termMode)
        termInput = EditText(this).apply { hint = "Masukkan perintah…"; setTextColor(th.text); setHintTextColor(th.dim); background = roundedS(th.card, 22, th.stroke); setPadding(dp(14), dp(10), dp(14), dp(10)); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        box.addView(termInput)
        val runBtn = Button(this).apply {
            text = "➤ Jalankan"
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(59, 130, 246), 22)
            setOnClickListener { termRunFromUi() }
        }
        termRunBtnRef = runBtn
        box.addView(runBtn)
        // T4a: baris utilitas + chip cepat Terminal Pro
        val utilRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        utilRow.addView(Button(this).apply { text = "▲"; background = rounded(th.tileBg, 16); setTextColor(th.text); minWidth = 0; minHeight = 0; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }; setOnClickListener { termHistNav(-1) } })
        utilRow.addView(Button(this).apply { text = "▼"; background = rounded(th.tileBg, 16); setTextColor(th.text); minWidth = 0; minHeight = 0; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }; setOnClickListener { termHistNav(1) } })
        utilRow.addView(Button(this).apply { text = "🧹 Bersihkan"; background = rounded(th.tileBg, 16); setTextColor(th.text); minWidth = 0; minHeight = 0; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }; setOnClickListener { if (termOutReady()) termOut.text = "" } })
        utilRow.addView(Button(this).apply { text = "↺ Reset Sesi"; background = rounded(th.tileBg, 16); setTextColor(th.text); minWidth = 0; minHeight = 0; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }; setOnClickListener { uiTermReset(); termAppend("(sesi shell direset)") } })
        val utilScroll = HorizontalScrollView(this)
        utilScroll.addView(utilRow)
        box.addView(utilScroll)
        // F3 (v4.37.0): status + bangkit serve satu ketukan.
        val serveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        serveStatusTv = TextView(this).apply { text = "Serve :4199 — " + ServeHealth.lastStatus(host); textSize = 12f; setTextColor(th.dim); setPadding(dp(8), dp(4), dp(8), dp(4)); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        serveRow.addView(serveStatusTv)
        serveRow.addView(Button(this).apply { text = "⚡ Bangkitkan Serve"; background = rounded(th.tileBg, 16); setTextColor(th.text); minWidth = 0; minHeight = 0; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), 0, 0, 0) }; setOnClickListener {
            serveStatusTv?.text = "Serve :4199 — membangkitkan…"
            Thread { try { val r = ServeHealth.revive(host); runOnUiThread { serveStatusTv?.text = "Serve :4199 — $r"; termAppend(r) } } catch (e: Exception) { runOnUiThread { serveStatusTv?.text = "Serve :4199 — gagal: ${e.message}" } } }.start()
        } })
        box.addView(serveRow)
        // F3-Tailscale (v4.48.4): baris status tailscale (baca saja; watchdog service yang menjaga).
        val tsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        tsStatusTv = TextView(this).apply { text = "Tailscale — " + TailscaleHealth.lastStatus(host) + " (" + TailscaleHealth.lastCheckText(host) + ")"; textSize = 12f; setTextColor(th.dim); setPadding(dp(8), dp(4), dp(8), dp(4)); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        tsRow.addView(tsStatusTv)
        box.addView(tsRow)
        val chipScroll = HorizontalScrollView(this)
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (chip in listOf("id", "pwd; ls -la", "ls /sdcard", "df -h", "free -m", "ps -A | head -40", "getprop ro.build.version.release")) {
            chipRow.addView(Button(this).apply {
                text = chip
                background = rounded(th.tileBg, 16); setTextColor(th.text)
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
                setOnClickListener { if (termInputReady()) termInput.setText(chip); termRunFromUi() }
            })
        }
        chipScroll.addView(chipRow)
        box.addView(chipScroll)
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        termScroll = scroll
        termOut = TextView(this).apply { text = "Output terminal tampil di sini."; textSize = 12f; setTextColor(if (dark) Color.rgb(176, 226, 186) else Color.rgb(21, 128, 61)); typeface = android.graphics.Typeface.MONOSPACE; background = rounded(if (dark) Color.rgb(10, 10, 10) else th.card, 18); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        scroll.addView(termOut)
        box.addView(scroll)
        return box
    }
