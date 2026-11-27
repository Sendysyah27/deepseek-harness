package com.senzwh.deepharness

import android.Manifest
import com.senzwh.deepharness.priv.AdbDiscovery
import com.senzwh.deepharness.priv.AdbPairing
import com.senzwh.deepharness.priv.PrivPairNotif
import com.senzwh.deepharness.priv.PrivServer
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ===== Setup native (T3) + Setup tab + kartu pengaturan =====
// Audit: pecah MainActivity. Extension MainActivity; state tetap di
// MainActivity (internal). Body VERBATIM.

    // ===== T3 (migrasi UI native): Setup native 6 kategori =====

    private val SETUP_CATS = listOf(
        Triple("monitor", "🔐 Izin & Monitor HP", "Izin lanjutan, PIN, kontak darurat, mode siaga"),
        Triple("koneksi", "🔌 Koneksi", "Login AI, izin, koordinator, Mode ADB, Proot"),
        Triple("mesin", "⚙️ Mesin Agen", "Models API / Lokal (offline)"),
        Triple("konektor", "🔗 Konektor", "Panel konektor, MCP, Shizuku legacy"),
        Triple("persona", "🎭 Persona & Mode Chat", "Mode chat bawaan, perilaku, otak & profil agen"),
        Triple("otomasi", "🧠 Memori & Otomasi", "Gubernur latar, rutinitas agen, memori, backup otak"),
        Triple("skill", "🧩 Skill Agen", "Impor & kelola skill agen"),
        Triple("tampilan", "🎚️ Langkah & Tampilan", "Batas langkah, ukuran font chat, bubble, asisten digital"),
        Triple("debug", "🛠️ Debug & Diagnostik", "Dump prompt, tes pola redaksi, uji mandiri, ANR, rekaman Mode Operator")
    )

    // v4.48.8.3: kategori memakai LABEL tombol, BUKAN posisi. Posisi
    // bergeser karena ada tombol tanpa nomor (Simpan Persona) dan
    // bersufiks huruf (12b/12c/12d) di awal daftar: posisi 57 adalah
    // label 54. Pencarian label kebal terhadap sisipan tombol baru.
    internal fun MainActivity.catLabels(key: String): List<String> = when (key) {
        "monitor" -> listOf("18.", "19.", "20.", "21.", "22.", "23.", "24.", "25.", "55.")
        "koneksi" -> listOf("2.", "3.", "4.", "5.", "6.", "9.", "10.", "33.", "34.", "35.", "36.", "37.", "38.", "52.", "63.", "64.", "67.")
        "mesin" -> listOf("39.", "73.")
        "konektor" -> listOf("7.", "8.", "61.", "62.", "71.")
        "persona" -> listOf("40.", "41.", "42.")
        "otomasi" -> listOf("13.", "14.", "15.", "16.", "17.", "28.", "29.", "30.", "31.", "32.", "49.", "50.", "53.", "54.", "56.", "65.", "65a.", "65b.", "66.", "68.", "69.", "70.")
        "skill" -> listOf("51.")
        "tampilan" -> listOf("26.", "27.", "44.", "45.", "46.", "47.", "48.")
        "debug" -> listOf("57.", "58.", "59.", "60.")
        else -> emptyList()
    }

    internal data class AksiMeta(val icon: String, val label: String, val sub: String, val style: Int)
    internal fun MainActivity.aksiMeta(prefix: String): AksiMeta = when (prefix) {
        "18." -> AksiMeta("📩", "Izin SMS, Telepon & Kalender", "Untuk tool terkait SMS, telepon & kalender", 0)
        "19." -> AksiMeta("🔑", "PIN Aksi Sensitif", "Tool berisiko wajib PIN", 0)
        "20." -> AksiMeta("💾", "Backup Memori", "Ekspor memori ke JSON di Download", 0)
        "22." -> AksiMeta("🆘", "Kontak Darurat", "Untuk tombol SOS & penjaga inaktivitas", 0)
        "23." -> AksiMeta("👂", "Mode Siaga Wake Word", "Hai Asisten — dengar terus, boros baterai", 0)
        "24." -> AksiMeta("💬", "Komentar Layar Proaktif", "Tawaran bantuan dari isi layar", 0)
        "25." -> AksiMeta("🔄", "Akun Google & Sinkron", "Gmail + frasa sandi memori", 0)
        "55." -> AksiMeta("📰", "Tes Laporan Pagi", "Tes sekali jalan — hasil via notifikasi", 0)
        "2." -> AksiMeta("🔔", "Akses Notifikasi", "Izin baca notifikasi untuk agen", 0)
        "3." -> AksiMeta("♿", "Aksesibilitas", "Izin aksesibilitas Android", 0)
        "4." -> AksiMeta("📊", "Statistik Pemakaian", "Izin Usage Stats — pemakaian app", 0)
        "5." -> AksiMeta("📍", "Izin Lokasi & Termux", "Lokasi + notifikasi app + Termux RUN_COMMAND", 0)
        "6." -> AksiMeta("🔋", "Abaikan Optimasi Baterai", "Supaya app tidak dimatikan sistem", 0)
        "9." -> AksiMeta("📁", "Akses Semua File", "Izin kelola semua file", 0)
        "10." -> AksiMeta("ℹ️", "Info Aplikasi", "Buka setelan app — Allow restricted settings", 0)
        "33." -> AksiMeta("🛰️", "Cek Jalur Serve", "Periksa jalur serve koordinator", 0)
        "34." -> AksiMeta("🚀", "Pasang Serve Mandiri + Boot", "Port 4100 + pasang boot script", 0)
        "35." -> AksiMeta("🐧", "Nyalakan Server", "Mode ADB via Termux + ADB", 0)
        "36." -> AksiMeta("📶", "Tes Server", "Ping server Mode ADB", 0)
        "37." -> AksiMeta("🔗", "Pairing Mandiri", "Pairing Mode ADB S3, tanpa Termux", 0)
        "38." -> AksiMeta("📦", "Via Klien Tertanam", "Nyalakan via klien tertanam S3", 0)
        "52." -> AksiMeta("🐧", "Ubuntu Proot", "Unduh & tes Ubuntu di dalam app", 0)
        "39." -> AksiMeta("⚙️", "Mesin Agen", "Models API (dialog 73) / Lokal (offline)", 0)
        "7." -> AksiMeta("📜", "Shizuku: File rish", "Jalur lama — pakai hanya bila perlu", 0)
        "8." -> AksiMeta("📦", "Shizuku: File dex", "Jalur lama — pakai hanya bila perlu", 0)
        "61." -> AksiMeta("🔗", "Konektor MCP", "Server MCP resmi + kustom", 0)
        "62." -> AksiMeta("🔌", "Token API-Buatan", "Token klien API-Buatan, status ADA/BELUM", 0)
        "73." -> AksiMeta("🧠", "Provider LLM (OpenAI-compatible)", "Atur base URL, model, API key, aktifkan otak", 0)
        // vc314 Worker B — Concat (kategori konektor, tombol backing 71.).
        "71." -> AksiMeta("🎬", "Concat", "Buka proyek Concat, token tersimpan aman", 0)
        "63." -> AksiMeta("🧑‍🏭", "Jobs Proot (Supervisor)", "Job latar proot: status, ekor log, kill, restart", 0)
        "64." -> AksiMeta("🔍", "Mitmproxy Terkelola", "Resident mitmproxy: mulai, stop, sehat, route, proxy env", 0)
        "67." -> AksiMeta("👷", "Worker (Device Server)", "Daftar & kendali worker proot/Python", 0)
        "40." -> AksiMeta("🎭", "Pak Perilaku Agen", "Preset gaya kerja", 0)
        "41." -> AksiMeta("🧠", "Otak Agen", "Edit file SOUL / AGENTS / MEMORY", 0)
        "42." -> AksiMeta("👤", "Profil Agen", "Ganti / buat agen", 0)
        "13." -> AksiMeta("🫧", "Bubble Melayang", "Panggil asisten dari app mana pun", 0)
        "14." -> AksiMeta("⚡", "Mode Proaktif", "Pengingat + briefing pagi + rekap malam", 0)
        "15." -> AksiMeta("🌅", "Jam Briefing Pagi", "Ketuk untuk ganti jam", 0)
        "16." -> AksiMeta("🤖", "Asisten Default Android", "Jadikan Harness asisten default", 0)
        "17." -> AksiMeta("🗑️", "Hapus Semua Memori & Riwayat", "Destruktif — hapus permanen, konfirmasi dulu", 1)
        "65." -> AksiMeta("🧠", "Fakta Menunggu Persetujuan", "Setujui/hapus fakta belum dikonfirmasi + usulan ekstraksi", 0)
        "65a." -> AksiMeta("📜", "Riwayat Fakta — Superseded/Withdrawn", "Fakta diganti/ditarik — ketuk untuk memulihkan ke aktif", 0)
        "65b." -> AksiMeta("⚡", "Konflik Memori & Graph Blast", "Flag konflik memori — ketuk untuk graph blast", 0)
        "66." -> AksiMeta("🗄️", "Arsip Hapusan — Pulihkan", "Hapusan model 30 hari terakhir, ketuk untuk memulihkan", 0)
        "68." -> AksiMeta("📰", "Briefing Harian", "Digest rilis AI + update harian, jadwal 08:00", 0)
        "69." -> AksiMeta("🔁", "Ringkasan Notifikasi", "Pipeline deterministik 5 langkah (rote PoC), jadwal 21:30", 0)
        "70." -> AksiMeta("🎨", "Artifact Anti-Slop", "Konstitusi desain 2 pass + linter gaya + bundle satu file (vc297)", 0)
        "28." -> AksiMeta("🧠", "Unduh Paket Memori Vektor", "135 MB, sekali unduh", 0)
        "29." -> AksiMeta("🧪", "Tes Memori Vektor", "Self-test embedding", 0)
        "30." -> AksiMeta("🗑️", "Hapus Paket Model", "Hapus file model memori vektor", 1)
        "31." -> AksiMeta("🧪", "Tes Otak Latar", "Tugas jalan di latar — hasil via notifikasi", 0)
        "32." -> AksiMeta("🧹", "Konsolidasi Memori", "Jalankan sekarang — rapikan memori", 0)
        "49." -> AksiMeta("💾", "Backup Otak & Pengaturan", "Zip di Download: file otak + pengaturan + ekspor memori DB (graph/riwayat/goal TIDAK ikut)", 0)
        "50." -> AksiMeta("♻️", "Pulihkan Otak", "Timpa otak dari backup — konfirmasi dulu", 1)
        "53." -> AksiMeta("🛰️", "Serve Standby", "Service + autostart serve", 0)
        "54." -> AksiMeta("📋", "Job Latar", "Kelola rutinitas terjadwal & log", 0)
        "56." -> AksiMeta("⛔", "Kill Switch", "Hentikan job lintas aplikasi", 1)
        "51." -> AksiMeta("📥", "Impor Skill", "Dari file .md di folder Download", 0)
        "26." -> AksiMeta("🎚️", "Batas Langkah Tool", "Maksimal langkah tool per perintah", 0)
        "27." -> AksiMeta("⚛️", "UI React (tidak dipakai lagi)", "Tombol penanda — UI native permanen", 3)
        "44." -> AksiMeta("🤖", "Asisten Digital", "Jadikan asisten default (tombol power)", 0)
        "45." -> AksiMeta("⚠️", "Mod Root", "Eksperimental — hanya bila paham risikonya", 2)
        "46." -> AksiMeta("🫧", "Bubble Asisten: Tampil/Sembunyi", "Atur bubble melayang asisten", 0)
        "47." -> AksiMeta("🫧", "Bubble Agen", "Ukuran, transparansi, aksi ketuk, posisi", 0)
        "48." -> AksiMeta("🎥", "Izin Rekam Layar", "Berikan & tes MediaProjection", 0)
        "57." -> AksiMeta("📄", "Dump Prompt", "Aktif/mati — tulis prompt teredaksi ke dump", 0)
        "58." -> AksiMeta("🕵️", "Tes Pola Redaksi", "Uji penyamaran data sensitif di log", 0)
        "59." -> AksiMeta("✅", "Uji Mandiri", "Tes kesehatan app tanpa model & jaringan", 0)
        "60." -> AksiMeta("🐢", "Jejak ANR Terakhir", "Laporan macet (ANR) terakhir", 0)
        else -> AksiMeta("⚙️", "Aksi " + prefix, "Buka aksi", 0)
    }

    internal fun MainActivity.setupBtnByLabel(prefix: String): Button? =
        setupBtnViews.firstOrNull { it.text.toString().startsWith(prefix) }

    internal fun MainActivity.inputDialog(title: String, hint: String, initial: String, cb: (String) -> Unit) {
        val th = currentTheme()
        val et = EditText(this).apply { this.hint = hint; setText(initial); setTextColor(th.text); setHintTextColor(th.dim); background = roundedS(th.bg, 12, th.stroke); setPadding(dp(12), dp(10), dp(12), dp(10)); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        thDialog(title, et, listOf(
            Triple("Simpan", true) { d: android.app.AlertDialog -> cb(et.text.toString().trim()); d.dismiss() },
            Triple("Batal", false) { d: android.app.AlertDialog -> d.dismiss() }
        ))
    }

    internal fun MainActivity.toggleRow(parent: LinearLayout, label: String, on: Boolean, cb: (Boolean) -> Unit) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        r.addView(TextView(this).apply { text = label; setTextColor(currentTheme().text); textSize = 13f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        r.addView(Button(this).apply { text = if (on) "🟢 AKTIF" else "⚪ MATI"; setOnClickListener { cb(!on) } })
        parent.addView(r)
    }

    internal fun MainActivity.refreshSetupNative() {
        val box = setupNativeBox ?: return
        runOnUiThread {
            box.removeAllViews()
            // vc310 Worker A (B13): state detail skill hanya hidup di
            // setupView="skill" — keluar dari kategori itu, reset supaya
            // kembali nanti selalu mulai dari daftar.
            if (setupView != "skill") {
                skillDetailIdVc310 = -1
                skillDetailTabVc310 = "ringkasan"
                skillDetailVersionVc310 = -1
                skillDetailCodeVc310 = false
            }
            val th = currentTheme()
            try { (setupNativeBox?.parent?.parent as? android.view.View)?.setBackgroundColor(th.bg) } catch (e: Exception) {}
            try { setupTitleTv?.setTextColor(th.text) } catch (e: Exception) {}
            try { setupStatusTv.setTextColor(th.dim) } catch (e: Exception) {}
            val st = try { JSONObject(uiSettingsStatus()) } catch (e: Exception) { JSONObject() }
            val badges = st.optJSONObject("badges") ?: JSONObject()
            if (setupView == "menu") {
                // Identitas ala mockup Settings: ubin S& + nama app
                val idRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(8)) }
                idRow.addView(TextView(this).apply {
                    text = "S&"; textSize = 19f; setTextColor(Color.rgb(34, 34, 34)); typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(120, 170, 255), Color.rgb(46, 107, 230))).apply { cornerRadius = dp(14).toFloat() }
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                })
                val idCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                idCol.addView(tv("Harness", 17f, th.text, true))
                idCol.addView(tv("Pengaturan agen & perangkat", 12f, th.dim))
                idRow.addView(idCol)
                box.addView(idRow)
                box.addView(themeSwitchRow())
                val catIcons = mapOf("monitor" to R.drawable.ic_shield, "koneksi" to R.drawable.ic_power, "mesin" to R.drawable.ic_build, "konektor" to R.drawable.ic_attach, "persona" to R.drawable.ic_person, "otomasi" to R.drawable.ic_history, "skill" to R.drawable.ic_work, "tampilan" to R.drawable.ic_tune)
                for ((key, title, desc) in SETUP_CATS) {
                    box.addView(setupCatRow(catIcons[key] ?: R.drawable.ic_settings, title.removePrefix("🔐 ").removePrefix("🔌 ").removePrefix("🎭 ").removePrefix("🧠 ").removePrefix("🧩 ").removePrefix("🎚️ ").removePrefix("⚙️ ").removePrefix("🔗 ").trim(), desc) { setupView = key; refreshSetupNative() })
                }
                box.addView(setupCatRow(R.drawable.ic_explore, "Setup Awal", "Jalankan ulang wizard perkenalan") { setupBtnByLabel("43.")?.performClick() })
                // vc183: log crash DIHAPUS dari menu — pindah ke Debug & Diagnostik
                // sebagai ubin "Log Crash Terakhir" (hanya bila ada crash).
                return@runOnUiThread
            }
            val cat = SETUP_CATS.firstOrNull { it.first == setupView }
            // vc310 Worker A (B13): di detail skill, tombol kembali =
            // "← Skills Anda" (ke daftar), bukan ke menu kategori.
            val inSkillDetailVc310 = setupView == "skill" && skillDetailIdVc310 >= 0
            box.addView(Button(this).apply {
                text = if (inSkillDetailVc310) "← Skills Anda" else "← Semua Kategori"
                gravity = Gravity.START
                background = roundedS(th.card, 14, th.stroke); setTextColor(th.text)
                setOnClickListener {
                    if (inSkillDetailVc310) {
                        skillDetailIdVc310 = -1
                        skillDetailTabVc310 = "ringkasan"
                        skillDetailVersionVc310 = -1
                        skillDetailCodeVc310 = false
                        refreshSetupNative()
                    } else {
                        setupView = "menu"; refreshSetupNative()
                    }
                }
            })
            // vc310 Worker A (B13): judul detail = nama skill.
            val skillDetailNameVc310 = if (inSkillDetailVc310) try { MemoryDb.get(this).skills().firstOrNull { it.id == skillDetailIdVc310 }?.name } catch (e: Exception) { null } else null
            // vc314 Worker B: judul layar detail Concat (setupView di luar
            // SETUP_CATS — tanpa ini judulnya "concat" huruf kecil).
            box.addView(tv(if (setupView == "concat") "Concat" else skillDetailNameVc310 ?: cat?.second ?: setupView, 15f, th.text, true))
            box.addView(themeSwitchRow())
            when (setupView) {
                "monitor" -> box.addView(setupPermsCard())
                "koneksi" -> box.addView(setupModelsApiCard())
                "mesin" -> box.addView(setupEngineCard())
                "konektor" -> box.addView(setupConnectorsCard(st))
                "persona" -> { box.addView(setupDefModeCard()); val pPers = getSharedPreferences("harness", Context.MODE_PRIVATE); val persEt = EditText(this).apply { hint = "Persona / instruksi asisten"; setText(pPers.getString("persona", "")); setTextColor(th.text); setHintTextColor(th.dim); background = roundedS(th.card, 14, th.stroke); minLines = 2 }; box.addView(persEt); box.addView(Button(this).apply { text = "Simpan Persona"; gravity = Gravity.START; background = roundedS(th.card, 14, th.stroke); setTextColor(th.text); setOnClickListener { pPers.edit().putString("persona", persEt.text.toString()).apply(); setupStatusTv.text = "Persona tersimpan ✓ (berlaku mulai pesan chat berikutnya)" } }) }
                "otomasi" -> { box.addView(setupGovCard(st)); box.addView(setupRoutinesCard(st)) }
                "skill" -> box.addView(if (skillDetailIdVc310 >= 0) buildSkillDetailVc310(skillDetailIdVc310) else setupSkillsCard(st))
                // vc314 Worker B: layar detail Concat (Setup → Konektor → Concat).
                "concat" -> box.addView(buildConcatCardVc314())
                "tampilan" -> box.addView(setupFontCard(st))
            }
            if (setupView == "debug") {
                val dc = cardBoxT()
                dc.addView(tv("DEBUG & DIAGNOSTIK", 12f, th.dim, true))
                dc.addView(tv("Alat diagnosa — dipakai saat ada yang aneh. Sehari-hari tidak perlu disentuh.", 12.5f, th.dim))
                box.addView(dc)
                try {
                    val crash = CrashGuard.lastCrash(this)
                    if (crash != null) {
                        box.addView(thFuncTile("💥", "Log Crash Terakhir", "Ketuk untuk lihat trace lengkap") {
                            val tvCrash = TextView(this).apply { text = crash; textSize = 11f; setTextColor(th.text); typeface = android.graphics.Typeface.MONOSPACE }
                            thDialog("Log Crash Terakhir", tvCrash, listOf(Triple("Tutup", false) { d: android.app.AlertDialog -> d.dismiss() }))
                        })
                    }
                } catch (e: Exception) {}
            }
            box.addView(tv("AKSI KATEGORI INI", 12f, th.dim, true))
            for (prefix in catLabels(setupView)) {
                if (setupView == "koneksi") {
                    val grp = when (prefix) { "33." -> "KOORDINATOR"; "35." -> "MODE ADB"; "52." -> "LAINNYA"; else -> null }
                    if (grp != null) box.addView(tv(grp, 12f, th.dim, true).apply { setPadding(0, dp(6), 0, dp(2)) })
                }
                if (prefix == "21.") {
                    box.addView(thFuncTile("📥", "Impor Backup", "Impor fakta, skill, jadwal & aturan dari JSON") {
                        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/json" }
                        startActivityForResult(i, 43)
                    })
                    box.addView(thFuncTile("🔄", "Reset Izin Tool", "Hapus semua izin selalu izinkan tool") {
                        getSharedPreferences("harness", Context.MODE_PRIVATE).edit().remove("autoAllowTools").apply()
                        ApprovalPanelStore.clearAutoAllow(host)
                        setupStatusTv.text = "Semua izin 'selalu izinkan' direset — tool berisiko akan ditanya lagi."
                    })
                    continue
                }
                val b = setupBtnByLabel(prefix) ?: continue
                val idx = setupBtnViews.indexOf(b)
                val badge = badges.optString(idx.toString(), "")
                val meta = aksiMeta(prefix)
                val subFull = meta.sub + (if (badge.isNotBlank()) " • " + badge else "")
                val tile = thFuncTile(meta.icon, meta.label, subFull, meta.style == 1, meta.style == 2) {
                    b.performClick()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ refreshSetupNative() }, 900)
                }
                if (meta.style == 3) tile.alpha = 0.55f
                box.addView(tile)
            }
        }
    }

    internal fun MainActivity.setupPermsCard(): LinearLayout {
        val c = cardBoxT()
        val th = currentTheme()
        c.addView(tv("STATUS IZIN UTAMA", 12f, th.dim, true))
        val st = try { permStatusJson() } catch (e: Exception) { JSONObject() }
        val labels = listOf(
            "notif" to "Notifikasi", "notifListener" to "Akses Notifikasi", "a11y" to "Aksesibilitas",
            "usage" to "Akses Penggunaan", "overlay" to "Tampil di Atas Aplikasi", "battery" to "Baterai Tanpa Batas",
            "files" to "Akses Semua File", "alarm" to "Alarm Tepat", "assistant" to "Asisten Digital", "autostart" to "Autostart (MIUI)")
        for ((key, label) in labels) {
            val v = st.optString(key, "no")
            val rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }
            rowL.addView(TextView(this).apply { text = label; setTextColor(th.text); textSize = 13f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            if (v == "ok") rowL.addView(tv("✓ Aktif", 12.5f, Color.rgb(80, 220, 140), true))
            else rowL.addView(Button(this).apply { text = "Izinkan"; setOnClickListener { uiPermOpen(key); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ refreshSetupNative() }, 1200) } })
            c.addView(rowL)
        }
        return c
    }

    /** F5c §5.9 — kartu Models API (pengganti kartu login lama):
     *  status otak aktif + tombol ke dialog 73. */
    internal fun MainActivity.setupModelsApiCard(): LinearLayout {
        val c = cardBoxT()
        val th = currentTheme()
        c.addView(tv("MODELS API", 12f, th.dim, true))
        val engine = try { AgentEngines.current(this) } catch (e: Exception) { "openai" }
        val prov = try {
            LlmProviderStore.hydrate(this)
            LlmProviders.activeProvider()?.takeIf { it.configured }
        } catch (e: Exception) { null }
        c.addView(tv(
            if (engine == "local") "Otak aktif: Lokal (offline) — agen aturan tanpa LLM."
            else if (prov != null) "Otak aktif: ${prov.label} (${prov.id}) • model ${prov.model}"
            else "Otak belum diatur — atur provider dulu.",
            13f, th.text))
        c.addView(Button(this).apply { text = "🧠 Atur Provider (73)"; setOnClickListener { showLlmProviderDialog() } })
        return c
    }

    /** F5c §5.9 — kartu Mesin Agen (pengganti kartu 5 Browser slot):
     *  status engine + jalan ke dialog 73 / tombol 39. */
    internal fun MainActivity.setupEngineCard(): LinearLayout {
        val c = cardBoxT()
        val th = currentTheme()
        c.addView(tv("MESIN AGEN", 12f, th.dim, true))
        val engine = try { AgentEngines.current(this) } catch (e: Exception) { "openai" }
        c.addView(tv(
            if (engine == "local") "Mesin: Lokal (offline)"
            else "Mesin: Models API (OpenAI-compatible)",
            13f, th.text))
        val rowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowB.addView(Button(this).apply { text = "🧠 Provider LLM (73)"; setOnClickListener { showLlmProviderDialog() } })
        rowB.addView(Button(this).apply { text = "⚙️ Mesin Agen (39)"; setOnClickListener { setupBtnByLabel("39.")?.performClick() } })
        c.addView(rowB)
        return c
    }

    // F5c (sapu settings): setupSlotCard() DIHAPUS — pemilih slot Browser
    // 1–5, persona per slot, tombol Buka/Pakai Browser N, tombol Otomatis
    // slot, + picker varian model (dsModelVariant) sudah
    // tidak ada lagi. Kategori "mesin" kini memakai setupEngineCard().

    internal fun MainActivity.setupConnectorsCard(st: JSONObject): LinearLayout {
        val c = cardBoxT()
        val th = currentTheme()
        c.addView(tv("PANEL KONEKTOR", 12f, th.dim, true))
        val arr = st.optJSONArray("connectors") ?: org.json.JSONArray()
        if (arr.length() == 0) c.addView(tv("Daftar konektor tidak tersedia.", 12.5f, th.dim))
        for (i in 0 until arr.length()) {
            val cn = arr.optJSONObject(i) ?: continue
            val id = cn.optString("id")
            val enabled = cn.optBoolean("enabled", true)
            val status = cn.optString("status", "no")
            val statusTxt = when { !enabled -> "Nonaktif"; status == "ok" -> "🟢 Terhubung"; status == "perm" -> "🟠 Perlu diatur"; else -> "⚪ Tidak tersedia" }
            c.addView(tv(cn.optString("icon") + " " + cn.optString("name") + " — " + statusTxt, 13f, th.text, true))
            c.addView(tv(cn.optString("desc"), 11.5f, th.dim))
            val rowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (cn.optBoolean("canToggle", true)) {
                rowB.addView(Button(this).apply {
                    text = if (enabled) "Matikan" else "Nyalakan"
                    setOnClickListener { uiConnectorToggle(id, !enabled); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ refreshSetupNative() }, 400) }
                })
            }
            if (enabled && status != "ok") {
                rowB.addView(Button(this).apply { text = "Hubungkan"; setOnClickListener { uiConnectorOpen(id) } })
            }
            if (rowB.childCount > 0) c.addView(rowB)
        }
        return c
    }

    // vc314 Worker B — layar setup Concat (Setup → Konektor → Concat,
    // tombol backing "71.", setupView="concat" — bukan Activity baru,
    // pola layar detail skill vc310). Desain Mist (keputusan user
    // 18:54): palet penuh via currentTheme() (token ChatThemeVc306,
    // TANPA hex baru), kartu cardBoxT, label seksi pola
    // skillSectionLabelVc310, baris kunci/nilai pola skillKvRowVc310,
    // ubin ikon pola skillIconTileVc310 (ikon path "play", bukan emoji),
    // Switch asli pola skillSwitchVc310 (tint aksen Mist), tombol
    // thBtn/thRowBtn. Token: EditText masked
    // (TYPE_TEXT_VARIATION_PASSWORD), simpan HANYA via SecureStore.put
    // "concat_api_token"; nilai TIDAK PERNAH tampil di toast/snackbar/
    // teks mana pun, TIDAK PERNAH di-log. "Uji koneksi" jalan di Thread
    // (panggilan jaringan blokir — pelajaran ANR vc193).
    internal fun MainActivity.concatSwitchVc314(checked: Boolean, desc: String, onChange: (Boolean) -> Unit): android.widget.Switch = android.widget.Switch(this).apply {
        val th = currentTheme()
        isChecked = checked
        contentDescription = desc
        try {
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, th.dim)
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ACCENT, th.track)
            )
        } catch (e: Exception) {}
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    internal fun MainActivity.buildConcatCardVc314(): LinearLayout {
        val th = currentTheme()
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Kartu header ala skillCardVc310: ubin ikon + nama + meta + Switch.
        val head = cardBoxT()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = rounded(th.tileBg, 12)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            addView(ImageView(host).apply {
                setImageDrawable(pathIconVc306("play", th.tileTint, chatIconSizeVc307(ChatThemeVc306.iconLg)))
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            })
        })
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(10), 0, dp(8), 0) }
        }
        mid.addView(tv("Concat", 15f, th.text, true))
        mid.addView(tv(ConcatTools.cardSubtitle(this), 12.5f, th.dim))
        top.addView(mid)
        top.addView(concatSwitchVc314(ConcatTools.isEnabled(this), "Aktifkan tool Concat") { on ->
            ConcatTools.setEnabled(host, on)
            setupStatusTv.text = if (on) "Perintah Concat dinyalakan." else "Perintah Concat dimatikan — tidak jalan sampai dinyalakan lagi."
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ refreshSetupNative() }, 400)
        })
        head.addView(top)
        head.addView(tv("Di sini kamu bisa buka proyek Concat dan lihat isinya tanpa membuka layarnya. Penting: tutup dulu proyeknya di layar Concat sebelum dibuka dari sini — layar Concat tidak ikut berubah kalau proyek diubah dari sini.", 13f, th.dim).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) }
        })
        wrap.addView(head)
        // Status server + token (nilai token TIDAK PERNAH tampil).
        wrap.addView(skillSectionLabelVc310("Status server"))
        val st = cardBoxT()
        st.addView(skillKvRowVc310("Server", ConcatTools.lastCheck(this)))
        st.addView(skillKvRowVc310("Token", if (ConcatTools.hasToken(this)) "ADA (isinya tidak ditampilkan)" else "BELUM"))
        st.addView(skillKvRowVc310("Perintah", if (ConcatTools.isEnabled(this)) "Aktif" else "Nonaktif"))
        wrap.addView(st)
        // Token.
        wrap.addView(skillSectionLabelVc310("Token Concat"))
        val tk = cardBoxT()
        tk.addView(tv("Token itu seperti kunci rahasia Concat (paling sedikit 20 karakter). Ketik sekali saja — kunci tersimpan aman di HP, tidak pernah ditampilkan.", 12.5f, th.dim))
        val et = EditText(this).apply {
            hint = "Tempel token Concat"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(th.text); setHintTextColor(th.faint)
            background = roundedS(th.card, ChatThemeVc306.rMd, th.stroke)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, dp(8)) }
        }
        tk.addView(et)
        val rowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        thRowBtn(rowB, "Simpan", true) {
            val v = et.text.toString().trim()
            val err = ConcatTools.validateTokenForSave(v)
            if (err != null) { setupStatusTv.text = err; return@thRowBtn }
            SecureStore.put(host, ConcatTools.TOKEN_KEY, v)
            et.setText("")
            setupStatusTv.text = "Token tersimpan (isinya tidak ditampilkan). Tekan 'Uji koneksi' untuk memastikan server Concat menjawab."
            refreshSetupNative()
        }
        thRowBtn(rowB, "Uji koneksi") { concatTestConnectionVc314() }
        thRowBtn(rowB, "Hapus token") { concatDeleteTokenVc314() }
        tk.addView(rowB)
        wrap.addView(tk)
        wrap.addView(tv("Server Concat hanya jalan di HP ini, tidak dibuka ke internet. Kalau server mati atau token salah, aplikasi akan bilang terus terang — tidak akan diam-diam menekan-nekan layar.", 11.5f, th.faint))
        return wrap
    }

    internal fun MainActivity.concatTestConnectionVc314() {
        Toast.makeText(this, "Menguji koneksi Concat…", Toast.LENGTH_SHORT).show()
        Thread {
            val token = try { SecureStore.get(host, ConcatTools.TOKEN_KEY) } catch (e: Exception) { null }
            val result = if (token.isNullOrBlank()) {
                ConcatTools.NO_TOKEN_MESSAGE
            } else {
                try {
                    val v = ConcatApi.version(token)
                    // vc315: simpan versi LIVE untuk subtitle kartu setup
                    // (bila belum pernah terhubung, dipakai 0.2.10).
                    ConcatTools.setLastVersion(host, v)
                    "ok: " + v
                } catch (e: ConcatApiException) { "gagal: " + (e.message ?: "kesalahan tidak dikenal") } catch (e: Exception) { "gagal: " + (e.message ?: "kesalahan tidak dikenal") }
            }
            ConcatTools.setLastCheck(host, result)
            val shown = ConcatTools.lastCheck(host)
            runOnUiThread {
                setupStatusTv.text = if (shown.startsWith("ok:")) "Uji koneksi Concat berhasil." else shown
                refreshSetupNative()
            }
        }.start()
    }

    internal fun MainActivity.concatDeleteTokenVc314() {
        AlertDialog.Builder(this).setTitle("Hapus token Concat?")
            .setMessage("Perintah Concat tidak bisa jalan sampai kamu masukkan token baru di layar ini.")
            .setPositiveButton("Hapus") { _, _ ->
                SecureStore.delete(host, ConcatTools.TOKEN_KEY)
                ConcatTools.setLastCheck(host, "belum dicek")
                setupStatusTv.text = "Token Concat dihapus."
                refreshSetupNative()
            }
            .setNegativeButton("Batal", null).show()
    }

    internal fun MainActivity.setupDefModeCard(): LinearLayout {
        val c = cardBoxT()
        val th = currentTheme()
        c.addView(tv("MODE CHAT DEFAULT", 12f, th.dim, true))
        val p = getSharedPreferences("harness", Context.MODE_PRIVATE)
        toggleRow(c, "Deep Search bawaan (tiap pesan baru)", p.getBoolean("defSearch", true)) { on -> uiSetDefMode("search", on); refreshSetupNative() }
        toggleRow(c, "DeepThink bawaan (tiap pesan baru)", p.getBoolean("defThink", false)) { on -> uiSetDefMode("think", on); refreshSetupNative() }
        toggleRow(c, "Mode Bebas", p.getBoolean("modeBebas", false)) { on -> p.edit().putBoolean("modeBebas", on).apply(); refreshSetupNative() }
        // F5c (sapu settings): baris "Mode chat aktif WebView/API Internal"
        // + tombol "Ganti Mode Chat" (tombol 12) DIHAPUS — tidak ada lagi
        // slot WebView vs API Internal (K1).
        return c
    }

    internal fun MainActivity.setupGovCard(st: JSONObject): LinearLayout {
        val c = cardBoxT()
        val th = currentTheme()
        c.addView(tv("GUBERNUR LATAR", 12f, th.dim, true))
        val cap = st.optInt("govCap", 500)
        c.addView(tv("Pemakaian hari ini: " + st.optInt("govCount", 0) + " / " + (if (cap == 0) "∞" else cap.toString()) + " unit", 12.5f, th.text))
        c.addView(tv("1 unit = 1 panggilan; +1 unit tiap 50.000 char prompt yang dikirim (vc277). Tiap jenis — interaktif, latar, tim, reviewer — punya jatah 500 unit/hari sendiri.", 11.5f, th.dim))
        // §3.2 (Fase 1): satu baris per provider Models API yang
        // configured — counter per (provider, bucket), mengikuti pola
        // tampilan Governor.count() di atas. Interaktif dicatat tapi
        // tidak pernah digerbang (chat user tidak pernah diblokir).
        try {
            LlmProviderStore.hydrate(this)
            val govActiveId = LlmProviderStore.activeId(this)
            val govProviders = LlmProviders.all().filter { it.kind == LlmKind.OPENAI_COMPAT && it.configured }
            if (govProviders.isEmpty()) {
                c.addView(tv("Models API: belum ada provider — atur di Setup > 73. Provider LLM.", 12.5f, th.dim))
            } else {
                for (p in govProviders) {
                    val pCap = Governor.capModels(this, p)
                    val capStr = if (pCap == 0) "∞" else pCap.toString()
                    val bg = Governor.countModels(this, p.id, Governor.MODELS_BUCKET_BG)
                    val team = Governor.countModels(this, p.id, Governor.MODELS_BUCKET_TEAM)
                    val rev = Governor.countModels(this, p.id, Governor.MODELS_BUCKET_REVIEWER)
                    val inter = Governor.countModels(this, p.id, Governor.MODELS_BUCKET_INTERACTIVE)
                    c.addView(tv(p.id + (if (p.id == govActiveId) " (AKTIF)" else "") + " — latar $bg/$capStr, tim $team/$capStr, reviewer $rev/$capStr, interaktif $inter (tidak dibatasi)", 12.5f, th.text))
                }
            }
        } catch (e: Exception) { }
        c.addView(Button(this).apply {
            text = "Atur Kuota Harian unit (0 = tanpa batas)"
            setOnClickListener { inputDialog("Kuota Harian Gubernur", "angka 0..5000", cap.toString()) { v -> uiGovSet("cap", v); refreshSetupNative() } }
        })
        val qs = st.optInt("govQuietStart", -1); val qe = st.optInt("govQuietEnd", -1)
        c.addView(tv("Jam tenang: " + (if (qs < 0) "mati" else qs.toString().padStart(2, '0') + ".00") + " → " + (if (qe < 0) "mati" else qe.toString().padStart(2, '0') + ".00"), 12.5f, th.text))
        val rowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowB.addView(Button(this).apply { text = "Atur Mulai"; setOnClickListener { inputDialog("Jam Tenang — Mulai", "0..23, -1 = mati", qs.toString()) { v -> uiGovSet("quietStart", v); refreshSetupNative() } } })
        rowB.addView(Button(this).apply { text = "Atur Akhir"; setOnClickListener { inputDialog("Jam Tenang — Akhir", "0..23, -1 = mati", qe.toString()) { v -> uiGovSet("quietEnd", v); refreshSetupNative() } } })
        c.addView(rowB)
        toggleRow(c, "Kerja latar hanya saat Wi-Fi", st.optBoolean("govWifiOnly", false)) { on -> uiGovSet("wifiOnly", if (on) "1" else "0"); refreshSetupNative() }
        toggleRow(c, "Ringkasan notifikasi pagi (07.00)", st.optBoolean("notifDigest", true)) { on -> uiGovSet("notifDigest", if (on) "1" else "0"); refreshSetupNative() }
        return c
    }

    internal fun MainActivity.setupRoutinesCard(st: JSONObject): LinearLayout {
        val c = cardBoxT()
        val th = currentTheme()
        c.addView(tv("RUTINITAS AGEN", 12f, th.dim, true))
        val arr = try { org.json.JSONArray(st.optString("routines", "[]")) } catch (e: Exception) { org.json.JSONArray() }
        if (arr.length() == 0) c.addView(tv("Belum ada rutinitas. Rutinitas = perintah yang dijalankan agen otomatis tiap hari pada jamnya.", 12.5f, th.dim))
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val time = r.optString("time", "??:??")
            val prompt = r.optString("prompt", "")
            val en = r.optBoolean("enabled", true)
            c.addView(tv("🕐 " + time + " — " + prompt.take(60) + (if (r.optString("target", "") == "grup") " [👥 grup]" else ""), 12.5f, th.text))
            // vc246 GELOMBANG 5 (K-13, pilihan label jujur): rutinitas
            // target grup TIDAK mengeksekusi tool (latar grup selalu
            // diskusi saja) — dilabeli di kartu, bukan dilabeli "selesai"
            // seolah aksi jalan. Jalur eksekusi grup latar penuh TIDAK
            // dibangun di gelombang ini (butuh gerbang persetujuan grup
            // latar yang belum disepakati).
            if (r.optString("target", "") == "grup") c.addView(tv("Grup = diskusi saja, tanpa eksekusi tool.", 11.5f, th.dim))
            val rowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowB.addView(Button(this).apply {
                text = if (en) "🟢 Aktif" else "⚪ Mati"
                setOnClickListener { r.put("enabled", !en); uiRoutinesSet(arr.toString()); refreshSetupNative() }
            })
            val isGrupTarget = r.optString("target", "") == "grup"
            rowB.addView(Button(this).apply {
                text = if (isGrupTarget) "👥 Grup" else "🤖 1-1"
                setOnClickListener { if (isGrupTarget) r.remove("target") else r.put("target", "grup"); uiRoutinesSet(arr.toString()); refreshSetupNative() }
            })
            rowB.addView(Button(this).apply {
                text = "Hapus"
                setOnClickListener {
                    android.app.AlertDialog.Builder(host).setTitle("Hapus rutinitas?")
                        .setMessage(time + " — " + prompt.take(80))
                        .setPositiveButton("Hapus") { _, _ -> arr.remove(i); uiRoutinesSet(arr.toString()); refreshSetupNative() }
                        .setNegativeButton("Batal", null).show()
                }
            })
            c.addView(rowB)
        }
        c.addView(Button(this).apply {
            text = "＋ Tambah Rutinitas"
            setOnClickListener {
                val lay = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
                val etTime = EditText(host).apply { hint = "Jam (HH:mm, mis. 07:30)"; setTextColor(th.text); setHintTextColor(th.dim) }
                val etPrompt = EditText(host).apply { hint = "Perintah untuk agen"; setTextColor(th.text); setHintTextColor(th.dim) }
                // vc246 GELOMBANG 5 (XL-m3): isian allowlist tool (opsional,
                // pisah koma) — field "tools" selama ini hanya dibaca
                // HarnessService, tidak pernah ditulis dialog mana pun.
                val etTools = EditText(host).apply { hint = "Allowlist tool (opsional, pisah koma, mis. get_battery,read_file)"; setTextColor(th.text); setHintTextColor(th.dim) }
                lay.addView(etTime); lay.addView(etPrompt); lay.addView(etTools)
                android.app.AlertDialog.Builder(host).setTitle("Rutinitas Baru").setView(lay)
                    .setPositiveButton("Simpan") { _, _ ->
                        val t = etTime.text.toString().trim(); val pr = etPrompt.text.toString().trim(); val tl = etTools.text.toString().trim()
                        if (t.isNotBlank() && pr.isNotBlank()) {
                            val ro = JSONObject().put("id", System.currentTimeMillis()).put("time", t).put("prompt", pr).put("enabled", true)
                            if (tl.isNotBlank()) ro.put("tools", tl)
                            arr.put(ro)
                            uiRoutinesSet(arr.toString()); refreshSetupNative()
                        }
                    }
                    .setNegativeButton("Batal", null).show()
            }
        })
        return c
    }

    internal fun MainActivity.buildSetupTab(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(currentTheme().bg) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        scroll.addView(box)
        setupTitleTv = TextView(this).apply { text = "Pengaturan"; textSize = 16f; setTextColor(currentTheme().text) }
        box.addView(setupTitleTv)
        setupStatusTv = TextView(this).apply { textSize = 12f; setTextColor(currentTheme().dim) }
        box.addView(setupStatusTv)
        // T3: permukaan Setup native (menu 6 kategori) di atas daftar tombol
        // klasik — daftar tombol di bawahnya tetap utuh (lapisan aksi React
        // + fallback kompatibilitas).
        val nb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupNativeBox = nb
        box.addView(nb)
        // vc183: tombol backing tetap DIBANGUN + didaftar (pil AKSI performClick),
        // tapi TIDAK dirender — lapisan klasik hilang total dari UI.
        fun addBtn(label: String, fn: () -> Unit) {
            val b = Button(this).apply { text = label; gravity = Gravity.START; setOnClickListener { fn() } }
            setupBtnViews.add(b)
        }
        // vc183: header klasik tidak dirender lagi.
        fun addHeader(label: String) { }
        addHeader("🔌 KONEKSI & MESIN")
        // F5c (sapu settings): tombol 1 "Login (tab Web)" DIHAPUS (K1).
        addBtn("2. Aktifkan akses Notifikasi") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        addBtn("3. Aktifkan Accessibility (baca layar + tap/swipe)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        addBtn("4. Izinkan Usage Stats (pemakaian app)") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        addBtn("5. Minta izin Lokasi + Notifikasi app + Termux RUN_COMMAND") {
            val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS, "com.termux.permission.RUN_COMMAND")
            requestPermissions(perms, 99)
        }
        addBtn("6. Abaikan optimasi baterai (biar harness tidak dibunuh)") {
            try {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                i.data = Uri.parse("package:$packageName")
                startActivity(i)
            } catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        addBtn("7. Setup Shizuku (legacy) — pilih file 'rish' dari ekspor Shizuku") { pickFile(41) }
        addBtn("8. Setup Shizuku (legacy) — pilih file 'rish_shizuku.dex'") { pickFile(42) }
        addBtn("9. Izinkan Akses Semua File (baca storage penuh)") {
            try {
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                i.data = Uri.parse("package:$packageName")
                startActivity(i)
            } catch (e: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
        addBtn("10. Info app (untuk 'Allow restricted settings' Android 13+)") {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        }
        // vc183: field persona klasik tidak dirender (objek tetap dibuat di bawah).
        val personaInput = EditText(this).apply {
            hint = "Persona / instruksi asisten (mis. 'Panggil aku bos. Jawab singkat & to the point.')"
            setText(getSharedPreferences("harness", Context.MODE_PRIVATE).getString("persona", ""))
            setTextColor(TEXT); setHintTextColor(DIM); setBackgroundColor(PANEL)
            minLines = 2
        }
        personaFieldRef = personaInput
        // vc183: personaInput klasik tidak dirender (backing tetap ada).
        addBtn("Simpan Persona") {
            getSharedPreferences("harness", Context.MODE_PRIVATE).edit().putString("persona", personaInput.text.toString()).apply()
            setupStatusTv.text = "Persona tersimpan ✓ (berlaku mulai pesan chat berikutnya)"
        }
        // F5c (sapu settings): tombol 11 "Tes Solver PoW" DIHAPUS (K1 —
        // solver PoW mode API internal lama).
        // F5c (sapu settings): tombol 12 "Ganti Mode Chat WebView ⇄ API
        // Internal" DIHAPUS (K1 — toggle terkait WebView).
        // F5c (sapu settings): tombol 12b "Mode Operator" DIHAPUS (K1).
        // F5c (sapu settings): tombol 12c "Lihat Rekaman Diagnostik API"
        // DIHAPUS (K1).
        // F5c (sapu settings): tombol 12d "Bandingin Rekaman Browser
        // lama" DIHAPUS (K1).
        addHeader("🧠 MEMORI, OTOMASI & PEMANGGILAN")
        addBtn("13. Bubble Melayang (panggil asisten dari app mana pun)") {
            val prefs = getSharedPreferences("harness", Context.MODE_PRIVATE)
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                setupStatusTv.text = "Izinkan 'Tampilkan di atas aplikasi lain' untuk Harness, lalu tekan tombol 13 lagi."
                return@addBtn
            }
            val now = !prefs.getBoolean("overlayOn", false)
            prefs.edit().putBoolean("overlayOn", now).apply()
            try { startService(Intent(this, HarnessService::class.java)) } catch (e: Exception) {}
            setupStatusTv.text = "Bubble melayang: ${if (now) "AKTIF — lingkaran 🤖 di tepi layar, ketuk untuk membuka chat." else "MATI."}"
        }
        addBtn("14. Mode Proaktif (pengingat + briefing pagi + rekap malam)") {
            val prefs = getSharedPreferences("harness", Context.MODE_PRIVATE)
            val now = !prefs.getBoolean("proactiveOn", true)
            prefs.edit().putBoolean("proactiveOn", now).apply()
            setupStatusTv.text = "Mode proaktif: ${if (now) "AKTIF — pengingat dari tool set_reminder akan muncul sebagai notifikasi; briefing pagi jam ${prefs.getIntSafe("briefingHour", 7)}.00; rekap malam jam 20.00." else "MATI — notifikasi proaktif berhenti (layanan pantau tetap jalan)."}"
        }
        addBtn("15. Jam Briefing Pagi (ketuk untuk ganti)") {
            val prefs = getSharedPreferences("harness", Context.MODE_PRIVATE)
            val next = (prefs.getIntSafe("briefingHour", 7) + 1).let { if (it > 10) 5 else it }
            prefs.edit().putInt("briefingHour", next).apply()
            setupStatusTv.text = "Jam briefing pagi sekarang: $next.00 WIB (ketuk lagi untuk geser 5–10)."
        }
        addBtn("16. Jadikan Asisten Default Android") {
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                setupStatusTv.text = "Di pengaturan asisten: pilih 'Harness' sebagai aplikasi asisten default. Setelah itu tahan tombol Home/Power atau gesture asisten akan membuka chat harness."
            } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e2: Exception) {}
                setupStatusTv.text = "Buka Pengaturan > Aplikasi > Aplikasi default > Asisten digital > pilih Harness."
            }
        }
        addBtn("17. Hapus Semua Memori & Riwayat (reset asisten)") {
            AlertDialog.Builder(this)
                .setTitle("Hapus memori?")
                .setMessage("Semua fakta tersimpan, riwayat percakapan, dan pengingat akan dihapus permanen.")
                .setPositiveButton("Hapus") { _, _ ->
                    MemoryDb.get(this).wipeAll()
                    // v4.48.6 (Butir 3): digest in-memory + stempel + snapshot
                    // metrik ikut dihapus (digest tidak pernah persisten).
                    prevTurnEvidence = ""
                    prevTurnEvidenceTs = 0
                    prevTurnEvidenceSessionId = -1 // vc234 K3: reset sesi digest ikut wipe
                    prevTurnResultIdsVc286.clear() // vc286: jendela 2 giliran ikut wipe
                    prevTurnResultIdsSessionVc286 = -1
                    WorkNotesVc301.clearSession(this, currentSessionId) // vc301 SF-D2: catatan kerja + status ikut wipe
                    try { SessionMessagesStore(filesDir).deleteAll() } catch (e: Exception) {} // vc335 R1 (D1): file messages semua sesi ikut wipe
                    TurnMetrics.clearSnapshot(this)
                    try { ResultStore.wipe(this) } catch (e: Exception) {}
                    try { java.io.File(filesDir, "dump/harness-prompt-last.txt").delete() } catch (e: Exception) {}
                    try { java.io.File("/sdcard/Download/harness-prompt-last.txt").delete() } catch (e: Exception) {}
                    getSharedPreferences("harness", Context.MODE_PRIVATE).edit().remove("redactMigrationReport").remove("redactMigration4488").apply()
                    setupStatusTv.text = "Memori, riwayat, dan pengingat sudah dihapus. Asisten mulai dari nol lagi."
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        addHeader("🔐 IZIN, KEAMANAN & AKUN")
        addBtn("18. Izin SMS, Telepon & Kalender (untuk tool terkait)") {
            try {
                requestPermissions(arrayOf(
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.SEND_SMS,
                    android.Manifest.permission.READ_CALL_LOG,
                    android.Manifest.permission.READ_CALENDAR
                ), 99)
                setupStatusTv.text = "Dialog izin ditampilkan — izinkan yang kamu perlukan (SMS untuk read_sms/send_sms + darurat, telepon untuk read_call_log, kalender untuk get_calendar_today)."
            } catch (e: Exception) { setupStatusTv.text = "Gagal meminta izin: ${e.message}" }
        }
        addBtn("19. PIN Aksi Sensitif (tool berisiko wajib PIN)") {
            val prefs = getSharedPreferences("harness", Context.MODE_PRIVATE)
            val field = EditText(this).apply {
                hint = "PIN angka baru (kosongkan + simpan untuk mematikan PIN)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                setTextColor(TEXT); setHintTextColor(DIM)
            }
            AlertDialog.Builder(this)
                .setTitle("Atur PIN aksi sensitif")
                .setView(field)
                .setPositiveButton("Simpan") { _, _ ->
                    val pin = field.text.toString().trim()
                    prefs.edit().putString("pinHash", if (pin.isBlank()) "" else sha256Hex(pin)).apply()
                    setupStatusTv.text = if (pin.isBlank()) "PIN dimatikan." else "PIN aktif — dialog izinkan tool berisiko sekarang meminta PIN. Reset izin 'selalu izinkan' ada di tombol 21."
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        addBtn("20. Backup Memori (ekspor JSON ke Download)") {
            Thread {
                try {
                    val json = MemoryDb.get(this).exportJson()
                    val f = File("/sdcard/Download/harness-backup-" + System.currentTimeMillis() + ".json")
                    f.writeText(json, Charsets.UTF_8)
                    runOnUiThread { setupStatusTv.text = "Backup tersimpan: ${f.absolutePath} (${json.length} byte) — berisi fakta, skill, jadwal, aturan. Impor lewat tombol 21." }
                } catch (e: Exception) {
                    runOnUiThread { setupStatusTv.text = "Backup gagal: ${e.message}" }
                }
            }.start()
        }
        addBtn("21. Impor Backup Memori (JSON) / Reset Izin Tool") {
            val prefs = getSharedPreferences("harness", Context.MODE_PRIVATE)
            AlertDialog.Builder(this)
                .setTitle("Impor / reset izin")
                .setMessage("Pilih file backup JSON untuk mengimpor fakta, skill, jadwal & aturan. Atau reset semua izin 'selalu izinkan tool'.")
                .setPositiveButton("Pilih file backup") { _, _ ->
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                    }
                    startActivityForResult(i, 43)
                }
                .setNeutralButton("Reset izin tool") { _, _ ->
                    prefs.edit().remove("autoAllowTools").apply()
                    ApprovalPanelStore.clearAutoAllow(host)
                    setupStatusTv.text = "Semua izin 'selalu izinkan' direset — tool berisiko akan ditanya lagi."
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        addBtn("22. Kontak Darurat (untuk tombol SOS & penjaga inaktivitas)") {
            val prefs = getSharedPreferences("harness", Context.MODE_PRIVATE)
            val field = EditText(this).apply {
                hint = "Nomor HP kontak darurat (contoh 0812xxxxxxx)"
                inputType = android.text.InputType.TYPE_CLASS_PHONE
                setText(prefs.getString("emergencyNumber", "") ?: "")
                setTextColor(TEXT); setHintTextColor(DIM)
            }
            AlertDialog.Builder(this)
                .setTitle("Kontak darurat")
                .setView(field)
                .setPositiveButton("Simpan") { _, _ ->
                    val num = field.text.toString().trim()
                    prefs.edit().putString("emergencyNumber", num).apply()
                    setupStatusTv.text = "Kontak darurat: ${if (num.isBlank()) "(kosong)" else num}. Penjaga inaktivitas: bila tidak ada aktivitas HP 6 jam pada jam 06–22, harness mengirim peringatan; tombol SOS ada di tab Monitor."
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        addBtn("23. Mode Siaga Wake Word ('Hai Asisten') — dengar terus, boros baterai") {
            val prefs = getSharedPreferences("harness", Context.MODE_PRIVATE)
            val turnOn = !prefs.getBoolean("wakeWordOn", false)
            if (turnOn) {
                try { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 98) } catch (e: Exception) {}
            }
            prefs.edit().putBoolean("wakeWordOn", turnOn).apply()
            try { startService(Intent(this, HarnessService::class.java)) } catch (e: Exception) {}
            setupStatusTv.text = if (turnOn)
                "Mode siaga AKTIF: ucapkan 'Hai Asisten' kapan pun untuk memanggil asisten (ia mendengarkan terus lewat mikrofon — baterai lebih boros, dan recognizer bawaan butuh internet). Matikan lewat tombol ini juga."
            else "Mode siaga MATI."
        }
        addBtn("24. Komentar Layar Proaktif (tawaran bantuan dari isi layar)") {
            val prefs = getSharedPreferences("harness", Context.MODE_PRIVATE)
            val turnOn = !prefs.getBoolean("screenWatchOn", false)
            prefs.edit().putBoolean("screenWatchOn", turnOn).apply()
            setupStatusTv.text = if (turnOn)
                "Komentar layar AKTIF: harness mengamati teks layar (lokal, tanpa kirim ke mana pun) dan menawari bantuan saat mendeteksi: pembayaran berhasil, kode verifikasi, atau sampai tujuan di Maps. Maks 1 tawaran per 30 menit. Butuh Accessibility aktif."
            else "Komentar layar MATI."
        }
        addBtn("25. Akun Google & Sinkron (Gmail + frasa sandi memori)") {
            val box2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)) }
            fun field(hint: String, prefill: String, password: Boolean): EditText {
                val f = EditText(this).apply {
                    this.hint = hint
                    setText(prefill)
                    setTextColor(TEXT); setHintTextColor(DIM); textSize = 13f
                    if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                box2.addView(f)
                return f
            }
            val emailF = field("Alamat Gmail", SecureStore.get(this, "gmail_email") ?: "", false)
            val passF = field("App Password Google (16 huruf, dari myaccount.google.com/apppasswords)", "", true)
            val syncF = field("Frasa sandi sinkron memori (untuk enkripsi backup)", "", true)
            box2.addView(TextView(this).apply {
                text = "Semua disimpan TERENKRIPSI (Android Keystore) di HP ini — tidak dikirim ke mana pun selain Gmail/Google langsung. Kosongkan kolom sandi untuk mempertahankan yang lama."
                setTextColor(DIM); textSize = 11f
            })
            AlertDialog.Builder(this)
                .setTitle("Akun Google & sinkron")
                .setView(box2)
                .setPositiveButton("Simpan") { _, _ ->
                    val email = emailF.text.toString().trim()
                    if (email.isNotBlank()) SecureStore.put(this, "gmail_email", email)
                    val ap = passF.text.toString().trim()
                    if (ap.isNotBlank()) SecureStore.put(this, "gmail_app_pass", ap)
                    val sp = syncF.text.toString().trim()
                    if (sp.isNotBlank()) SecureStore.put(this, "sync_passphrase", sp)
                    setupStatusTv.text = "Tersimpan terenkripsi. Gmail: ${if (SecureStore.has(this, "gmail_app_pass")) "App Password ADA" else "App Password BELUM diisi"} • Frasa sandi sinkron: ${if (SecureStore.has(this, "sync_passphrase")) "ADA" else "belum"}."
                }
                .setNeutralButton("Hapus semua kredensial") { _, _ ->
                    SecureStore.delete(this, "gmail_email"); SecureStore.delete(this, "gmail_app_pass"); SecureStore.delete(this, "sync_passphrase")
                    setupStatusTv.text = "Kredensial Google & frasa sandi dihapus dari HP ini."
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        addHeader("⚙️ LANGKAH & TAMPILAN")
        addBtn("26. Batas Langkah Tool per Perintah (fleksibel)") {
            val prefs = getSharedPreferences("harness", Context.MODE_PRIVATE)
            val box3 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)) }
            val numF = EditText(this).apply {
                hint = "Batas langkah (3–200)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(prefs.getIntSafe("maxToolSteps", 50).toString())
                setTextColor(TEXT); setHintTextColor(DIM)
            }
            val askB = CheckBox(this).apply {
                text = "Minta persetujuan tambah 10 langkah saat batas tercapai (bila mati: berhenti + rangkum)"
                isChecked = prefs.getBoolean("askExtendSteps", true)
                setTextColor(DIM); textSize = 12f
            }
            box3.addView(numF); box3.addView(askB)
            AlertDialog.Builder(this)
                .setTitle("Batas langkah tool")
                .setView(box3)
                .setPositiveButton("Simpan") { _, _ ->
                    val raw = numF.text.toString().trim().toIntOrNull() ?: 10
                    val v = raw.coerceIn(3, 200)
                    prefs.edit().putInt("maxToolSteps", v).putBoolean("askExtendSteps", askB.isChecked).apply()
                    if (raw > 200) Toast.makeText(this, "Maksimal 200 langkah per perintah", Toast.LENGTH_SHORT).show()
                    setupStatusTv.text = "Batas langkah tool: $v per perintah • Minta tambah saat mentok: ${if (askB.isChecked) "AKTIF" else "MATI"}. Berlaku mulai perintah berikutnya."
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        addBtn("27. UI React (sudah pensiun — UI native permanen)") {
            Toast.makeText(this, "UI React sudah pensiun sejak v4.26.0 — Harness sekarang 100% native.", Toast.LENGTH_LONG).show()
        }
        addBtn("28. Memori Vektor — Unduh Paket Model (135 MB, sekali saja)") {
            val wifiBox = CheckBox(this).apply { text = "Unduh lewat WiFi saja (hemat kuota)"; isChecked = false }
            val msg28 = { info: String -> "$info\n\nPaket = model embedding multibahasa paraphrase-multilingual-MiniLM-L12-v2 (ONNX int8 ±118 MB) + tokenizer (±17 MB), diunduh dari HuggingFace dan diverifikasi SHA-256. Setelah terpasang: recall_search memakai pencarian MAKNA (kosinus vektor), dan konteks relevan dari memori jangka panjang disuntikkan otomatis tiap pesan. Unduhan terputus bisa DILANJUTKAN dari posisi terakhir." }
            val quick28 = Embedder.packInfoQuick(this)
            val dlg28 = AlertDialog.Builder(this)
                .setTitle("Memori Vektor (Tahap B)")
                .setMessage(msg28(quick28))
                .setView(wifiBox)
                .setPositiveButton("Unduh") { _, _ ->
                    setupStatusTv.text = "Memori vektor: mulai mengunduh…"
                    Embedder.downloadPack(this, wifiBox.isChecked) { st ->
                        runOnUiThread { setupStatusTv.text = "Memori vektor: $st" }
                    }
                }
                .setNegativeButton("Tutup", null)
                .show()
            // vc193 ANR fix: message memakai quick (non-blokir). Bila masih
            // placeholder, satu background check lalu update message dialog
            // HANYA bila dialog masih tampil (tanpa crash bila sudah ditutup).
            // Dialog ini modal — maksimal satu dlg28 hidup — jadi tanpa flag.
            if (quick28 == Embedder.ENGINE_CHECKING) {
                Thread {
                    try { Embedder.isReady(this) } catch (e: Exception) {}
                    runOnUiThread {
                        if (!isFinishing && dlg28.isShowing) dlg28.setMessage(msg28(Embedder.packInfoQuick(this)))
                    }
                }.start()
            }
        }
        addBtn("29. Tes Memori Vektor (self-test embedding)") {
            setupStatusTv.text = "Memori vektor: menjalankan tes…"
            Thread {
                val r = Embedder.selfTest(this)
                runOnUiThread { setupStatusTv.text = "Memori vektor:\n$r" }
            }.start()
        }
        addBtn("30. Hapus Paket Model Memori Vektor") {
            AlertDialog.Builder(this)
                .setTitle("Hapus paket model?")
                .setMessage("Model embedding (±135 MB) dihapus dari penyimpanan app. Pencarian memori kembali ke mode kata kunci sampai paket diunduh lagi. Vektor yang sudah tersimpan di database tidak ikut terhapus.")
                .setPositiveButton("Hapus") { _, _ ->
                    setupStatusTv.text = "Memori vektor: menghapus paket model…"
                    Thread {
                        try {
                            Embedder.deletePack(this)
                            runOnUiThread {
                                setupStatusTv.text = "Memori vektor: paket model dihapus."
                                Toast.makeText(this, "Paket model dihapus — pencarian kembali ke kata kunci", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                setupStatusTv.text = "Memori vektor: gagal menghapus paket model."
                                Toast.makeText(this, "Gagal menghapus paket model: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        addBtn("31. Tes Otak Latar (tugas jalan di latar — hasil via notifikasi)") {
            val ok = BgBrain.submit(this, "TES OTAK LATAR: panggil tool get_battery dan get_location, lalu tulis laporan singkat 2-3 kalimat tentang kondisi HP user saat ini.", "tes", "🧠 Tes Otak Latar")
            setupStatusTv.text = if (ok) "Otak Latar: tes dikirim — hasil tiba sebagai notifikasi & masuk riwayat chat. App BOLEH ditutup sekarang."
                else "Otak Latar: tes tidak dikirim (alasan: " + BgBrain.lastBlockReason(this).ifBlank { "tidak diketahui" } + ") — coba lagi nanti."
        }
        addBtn("32. Konsolidasi Memori — Jalankan Sekarang") {
            setupStatusTv.text = "Konsolidasi memori berjalan di latar (arsip riwayat tua + gabung fakta mirip + ringkasan LLM)…"
            val act = this
            Thread { BgBrain.consolidate(act) }.start()
        }
        addBtn("65. Fakta Menunggu Persetujuan (vc228)") { vc228ShowUnconfirmedFactsDialog() }
        addBtn("65a. Riwayat Fakta — Superseded/Withdrawn (vc285)") { vc285ShowFactHistoryDialog() }
        addBtn("65b. Konflik Memori & Graph Blast (vc294)") { vc294ShowConflictDialog() }
        addBtn("66. Arsip Hapusan — Pulihkan (vc228)") { vc228ShowArchiveDialog() }
        addBtn("68. 📰 Briefing Harian (vc295)") { vc295ShowBriefingDialog() }
        addBtn("69. 🔁 Ringkasan Notifikasi (vc296)") { vc296ShowRoteDialog() }
        addBtn("70. 🎨 Artifact Anti-Slop (vc297)") { vc297ShowArtifactDialog() }
        addBtn("33. Koordinator OpenCode — Cek Jalur Serve") {
            val act = this
            setupStatusTv.text = "Mengecek serve 4097/4096/4100…"
            Thread {
                val s = OcCoord.statusText(act, forceProbe = true)
                runOnUiThread { setupStatusTv.text = "🤖 KOORDINATOR OPENCODE\n$s" }
            }.start()
        }
        addBtn("34. Koordinator — Pasang Serve Mandiri (4100) + Boot Script") {
            val act = this
            setupStatusTv.text = "Memasang boot script + menyalakan serve mandiri 4100 via Termux… (butuh izin RUN_COMMAND)"
            Thread {
                val s = OcCoord.installBootAndStart(act)
                runOnUiThread { setupStatusTv.text = "🤖 SERVE MANDIRI\n$s" }
            }.start()
        }
        addBtn("35. Mode ADB — Nyalakan Server Privileged (via Termux+ADB)") {
            val act = this
            val prefs = getSharedPreferences("harness", Context.MODE_PRIVATE)
            val input = EditText(this).apply {
                setText(prefs.getString("privAdbPort", "") ?: "")
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                hint = "Tempel baris IP address & Port apa adanya, atau port saja"
            }
            AlertDialog.Builder(this)
                .setTitle("Mode ADB — Nyalakan Server")
                .setMessage("Server privileged milik harness dinyalakan lewat Termux + ADB (Wireless debugging AKTIF, Termux sudah pernah pairing). Tempel baris 'IP address and port' dari layar Wireless debugging APA ADANYA (boleh lengkap dengan IP-nya), atau ketik port-nya saja. Koneksi dikunci ke 127.0.0.1:<port>:")
                .setView(input)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Nyalakan") { _, _ ->
                    // Terima tempelan "100.125.208.59:45907", "192.168.1.15:45907",
                    // atau port polos "45907" — ambil digit setelah ':' terakhir.
                    val raw = input.text.toString().trim()
                    val port = raw.substringAfterLast(':').filter { it.isDigit() }
                    val portNum = port.toIntOrNull()
                    if (portNum == null || portNum !in 1..65535) {
                        setupStatusTv.text = "❌ MODE ADB: port tidak terbaca dari \"$raw\". Tempel baris 'IP address and port' dari layar Wireless debugging (contoh: 192.168.1.15:45907) atau ketik port-nya saja (contoh: 45907)."
                        return@setPositiveButton
                    }
                    // vc170: simpan juga IP tempelan (bila ada) sebagai fallback —
                    // loopback tetap dicoba dulu, IP tampil hanya bila loopback gagal.
                    val hostPart = raw.substringBeforeLast(':', "")
                    if (hostPart.contains('.') && hostPart.all { it.isDigit() || it == '.' }) {
                        prefs.edit().putString("privAdbHost", hostPart).apply()
                    }
                    prefs.edit().putString("privAdbPort", port).apply()
                    setupStatusTv.text = "MODE ADB: menulis start.sh, mencoba menyalakan server (port connect $port)…"
                    Thread {
                        val path = PrivServer.writeStartScript(act)
                        val log = StringBuilder()
                        fun waitAlive(sec: Int): Boolean {
                            var n = 0
                            while (n < sec) { if (PrivServer.isAlive()) return true; Thread.sleep(1000); n++ }
                            return PrivServer.isAlive()
                        }
                        fun status(s: String) { runOnUiThread { setupStatusTv.text = s } }
                        var alive = PrivServer.isAlive()
                        var triedTarget = "127.0.0.1"
                        // vc170: loopback dulu; gagal → IP tempelan (bila ada & bukan loopback).
                        if (!alive) {
                            status("MODE ADB: menyalakan via Termux (adb connect 127.0.0.1:$port)…")
                            val out = try { Tools.runTerminal(act, "adb connect 127.0.0.1:$port ; adb -s 127.0.0.1:$port shell sh \"$path\"", "termux") } catch (e: Exception) { "gagal: ${e.message}" }
                            log.append("Termux loopback: $out\n")
                            alive = waitAlive(12)
                            if (!alive) {
                                val fb = prefs.getString("privAdbHost", "") ?: ""
                                if (fb.isNotBlank() && fb != "127.0.0.1") {
                                    triedTarget = fb
                                    status("MODE ADB: loopback gagal, mencoba via IP tampil ($fb:$port)…")
                                    val out2 = try { Tools.runTerminal(act, "adb connect $fb:$port ; adb -s $fb:$port shell sh \"$path\"", "termux") } catch (e: Exception) { "gagal: ${e.message}" }
                                    log.append("Termux $fb: $out2\n")
                                    alive = waitAlive(12)
                                }
                            }
                        }
                        val msg = if (alive) {
                            "✅ MODE ADB AKTIF via $triedTarget — server privileged hidup (" + (PrivServer.ping() ?: "?") + "). Agen kini bisa memakai tool run_privileged (hak uid shell)."
                        } else {
                            "❌ Server belum merespons.\n$log\nCek: Wireless debugging aktif, port connect benar (bukan port pairing), Termux sudah adb pair, dan tidak ada entri device 'offline' nyangkut di adb devices (putuskan dengan adb disconnect bila ada). Log server: /data/local/tmp/harness_priv.log"
                        }
                        runOnUiThread { setupStatusTv.text = msg }
                    }.start()
                }
                .show()
        }
        addBtn("36. Mode ADB — Tes Server (Ping)") {
            val act = this
            setupStatusTv.text = "MODE ADB: ping server…"
            Thread {
                val r = if (PrivServer.isAlive()) (PrivServer.ping() ?: "binder hidup tapi ping gagal") else PrivServer.privGateMessage()
                runOnUiThread { setupStatusTv.text = "MODE ADB: $r" }
            }.start()
        }
        addBtn("37. Mode ADB — Pairing Mandiri (S3, tanpa Termux)") {
            val act = this
            val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(56, 24, 56, 8) }
            val statusTv = TextView(this).apply {
                text = if (AdbPairing.isPaired(act)) "Status: SUDAH pairing ✅ — pairing ulang hanya perlu bila daftar perangkat di Wireless debugging pernah di-reset."
                else "Status: BELUM pairing. Langkah: Opsi pengembang → Wireless debugging → 'Pair device with pairing code'. Biarkan dialog kode TAMPIL, lalu isi kode di sini dan ketuk Pairing Sekarang (port dicari otomatis). Atau ketuk '📲 Input via Notifikasi' — notifikasi muncul, tarik shade & ketik kode TANPA keluar dari Settings."
                textSize = 13f
            }
            val codeInput = EditText(this).apply {
                hint = "Kode pairing 6 digit"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            val portInput = EditText(this).apply {
                hint = "Port pairing (dicari otomatis via NSD…)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            lay.addView(statusTv); lay.addView(codeInput); lay.addView(portInput)
            var discoveredHost = ""
            val notifBtn = Button(this).apply {
                text = "📲 Input via Notifikasi"
                setOnClickListener {
                    PrivPairNotif.postPairNotif(act)
                    setupStatusTv.text = "MODE ADB (S3): notifikasi input kode dikirim — buka Wireless debugging, tampilkan dialog kode, tarik shade & ketik 6 digit di notifikasi (tanpa keluar dari Settings)."
                }
            }
            val retryBtn = Button(this).apply {
                text = "🔍 Cari Port Lagi"
                setOnClickListener {
                    Thread {
                        val (h, p) = AdbDiscovery.findService(act, AdbDiscovery.TYPE_PAIRING, 9000)
                        runOnUiThread {
                            if (p > 0) {
                                discoveredHost = h
                                if (h.isNotBlank()) act.getSharedPreferences("harness", Context.MODE_PRIVATE).edit().putString("privAdbHost", h).apply()
                                portInput.setText(p.toString()); setupStatusTv.text = "MODE ADB (S3): port pairing ketemu: $p" + (if (h.isNotBlank()) " (host $h)" else "")
                            }
                            else setupStatusTv.text = "MODE ADB (S3): port pairing tidak ketemu — pastikan dialog kode pairing sedang TAMPIL di Wireless debugging, lalu ketuk Cari Port Lagi."
                        }
                    }.start()
                }
            }
            lay.addView(notifBtn); lay.addView(retryBtn)
            val dlg = AlertDialog.Builder(this)
                .setTitle("Mode ADB — Pairing Mandiri (S3)")
                .setView(lay)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Pairing Sekarang") { _, _ ->
                    val code = codeInput.text.toString().trim()
                    val port = portInput.text.toString().trim().toIntOrNull()
                    if (port == null) {
                        setupStatusTv.text = "❌ MODE ADB (S3): port pairing belum terisi/ketemu. Pastikan dialog kode pairing di Wireless debugging sedang tampil, lalu buka tombol 37 lagi."
                        return@setPositiveButton
                    }
                    setupStatusTv.text = "MODE ADB (S3): pairing ke 127.0.0.1:$port…"
                    Thread {
                        val r = AdbPairing.pairAuto(act, port, code, discoveredHost)
                        runOnUiThread { setupStatusTv.text = "MODE ADB (S3 PAIRING): $r" }
                    }.start()
                }
                .show()
            Thread {
                val (h, p) = AdbDiscovery.findService(act, AdbDiscovery.TYPE_PAIRING, 9000)
                if (p > 0) {
                    discoveredHost = h
                    if (h.isNotBlank()) act.getSharedPreferences("harness", Context.MODE_PRIVATE).edit().putString("privAdbHost", h).apply()
                    runOnUiThread { if (portInput.text.isBlank()) portInput.setText(p.toString()) }
                }
            }.start()
            dlg
        }
        addBtn("38. Mode ADB — Nyalakan via Klien Tertanam (S3)") {
            val act = this
            setupStatusTv.text = "MODE ADB (S3): mencari port connect & menyalakan server via klien tertanam…"
            Thread {
                val r = PrivServer.startViaEmbedded(act)
                runOnUiThread { setupStatusTv.text = "MODE ADB (S3): $r" }
            }.start()
        }
        addBtn("39. Mesin Agen — Models API / Lokal (offline)") {
            // F5c §5.9/frame 24: pemilih engine — hanya Models API (via
            // dialog 73) / Lokal (offline). TANPA opsi lama (K1).
            val engine = AgentEngines.current(this)
            val prov = try {
                LlmProviderStore.hydrate(this)
                LlmProviders.activeProvider()?.takeIf { it.configured }
            } catch (e: Exception) { null }
            val statusLine = when {
                engine == "local" -> "Otak aktif: Lokal (offline) — agen aturan tanpa LLM."
                prov != null -> "Otak aktif: ${prov.label} (${prov.id}) • model ${prov.model}"
                else -> "Otak belum diatur — atur provider di Setup > 73. Provider LLM."
            }
            val capLine = if (prov != null) "\nKuota: ${try { Governor.capModels(this, prov) } catch (e: Exception) { 500 }} unit/hari (bucket Models API)" else ""
            AlertDialog.Builder(this)
                .setTitle("Mesin Agen")
                .setMessage("Mesin: " + (if (engine == "local") "Lokal (offline)" else "Models API") + "\n" + statusLine + capLine)
                .setPositiveButton("Provider LLM…") { _, _ -> showLlmProviderDialog() }
                .setNeutralButton("Lokal (offline)") { _, _ ->
                    AgentEngines.setCurrent(this, "local")
                    // Pilihan Lokal sadar = bukan "skip": banner NO_PROVIDER mati.
                    try { getSharedPreferences("harness", Context.MODE_PRIVATE).edit().putBoolean("flowProviderSkipped", false).apply() } catch (e: Exception) {}
                    setupStatusTv.text = "MESIN AGEN: Lokal (offline) aktif — agen aturan tanpa LLM."
                }
                .setNegativeButton("Tutup", null)
                .show()
        }
        addBtn("40. Pak Perilaku Agen (J6) — preset gaya kerja") { setupBehaviorDialog() }
        addBtn("41. Otak Agen — SOUL/AGENTS/MEMORY (Agen Native)") { setupBrainDialog() }
        addBtn("42. Profil Agen — ganti/buat agen (Agen Native)") { setupAgentsDialog() }
        addBtn("43. Setup Awal — jalankan ulang wizard (onboarding/provider/izin)") {
            uiSetFlow("reset")
            setupStatusTv.text = "🧭 SETUP AWAL: wizard direset — wizard tampil otomatis di UI utama dari langkah onboarding."
        }
        addBtn("44. Asisten Digital — jadikan asisten default (tombol power)") { uiPermOpen("assistant") }
        addBtn("45. Mod Root (EKSPERIMENTAL) — deteksi & aktifkan root") {
            val p = getSharedPreferences("harness", Context.MODE_PRIVATE)
            val cur = p.getBoolean("rootMode", false)
            AlertDialog.Builder(this)
                .setTitle("Mod Root (Eksperimental)")
                .setMessage("Status: " + (p.getString("rootStatus", "Belum dites") ?: "Belum dites") + "\nMode saat ini: " + (if (cur) "AKTIF" else "nonaktif") + "\n\nPERINGATAN: root = akses PENUH ke sistem (uid=0). Hanya aktifkan di perangkat yang memang sudah di-root dan kamu pahami risikonya. Tool berisiko tetap lewat gerbang persetujuan + PIN.")
                .setPositiveButton(if (cur) "Matikan" else "Aktifkan") { _, _ ->
                    p.edit().putBoolean("rootMode", !cur).apply()
                    if (!cur) uiRootProbe()
                    setupStatusTv.text = "MOD ROOT: " + (if (!cur) "AKTIF (eksperimental)" else "nonaktif")
                }
                .setNeutralButton("Tes Root") { _, _ -> uiRootProbe() }
                .setNegativeButton("Tutup", null)
                .show()
        }
        addBtn("46. Bubble Melayang Asisten — tampil/sembunyi") {
            val p = getSharedPreferences("harness", Context.MODE_PRIVATE)
            val on = !p.getBoolean("overlayOn", false)
            uiSetBubble(on)
            setupStatusTv.text = "BUBBLE ASISTEN: " + (if (on) "AKTIF — ketuk bubble ikon paus untuk bicara dengan agen." else "nonaktif")
        }
        addBtn("47. Bubble Agen — ukuran, transparansi, aksi ketuk, posisi") {
            val items = arrayOf("Ukuran: Kecil (44)", "Ukuran: Sedang (56)", "Ukuran: Besar (68)", "Transparansi: 60%", "Transparansi: 100%", "Aksi Ketuk: Panel Suara", "Aksi Ketuk: Buka App", "Reset Posisi Bubble", "Goyang HP Panggil Agen: Ganti")
            AlertDialog.Builder(this)
                .setTitle("Pengaturan Bubble Agen")
                .setItems(items) { _, w ->
                    when (w) {
                        0 -> uiBubblePref("size", "44")
                        1 -> uiBubblePref("size", "56")
                        2 -> uiBubblePref("size", "68")
                        3 -> uiBubblePref("alpha", "60")
                        4 -> uiBubblePref("alpha", "100")
                        5 -> uiBubblePref("tap", "panel")
                        6 -> uiBubblePref("tap", "app")
                        7 -> uiBubblePref("resetPos", "")
                        8 -> {
                            val p2 = getSharedPreferences("harness", Context.MODE_PRIVATE)
                            uiBubblePref("shake", if (p2.getBoolean("shakeSummon", false)) "false" else "true")
                        }
                    }
                    setupStatusTv.text = "BUBBLE: pengaturan diterapkan — " + items[w]
                }
                .setNegativeButton("Tutup", null)
                .show()
        }
        addBtn("48. Izin Rekam Layar (MediaProjection) — berikan & tes") {
            // v4.11.1: MediaProjection bukan izin persisten di Android —
            // yang resmi hanya dialog persetujuan sistem per sesi. Tombol
            // ini memicunya langsung dari Setup + mencatat statusnya.
            ScreenWatch.armManual(this)
            setupStatusTv.text = "REKAM LAYAR: dialog izin dibuka — pilih 'Mulai sekarang'. Sesi pantau mulai sebagai tes (maks 20 mnt, berhenti dari pill/notifikasi)."
        }
        addBtn("49. Backup Otak & Pengaturan (zip ke Download)") { uiBackupBrain() }
        addBtn("50. Pulihkan Otak & Pengaturan (dari Download/harness-restore.zip)") { uiRestoreBrainWithConfirm() }
        addBtn("51. Impor Skill (.md dari Download)") { uiImportSkills() }
        addBtn("52. Ubuntu Proot — Unduh & Tes (Terminal mode proot)") { prootDialog() }
        addBtn("53. Serve Standby (F3) — service + autostart") { serveStandbyDialog() }
        addBtn("54. Job Latar (F2) — kelola & log") { jobDialog() }
        addBtn("55. Tes Laporan Pagi (F5)") { val r = try { TriageDigest.runManual(host) } catch (e: Exception) { "error" }; val msg = when { r == "ok" -> "Laporan pagi tes dikirim ke Otak Latar — hasilnya masuk notifikasi + riwayat."; r == "empty" -> "Tidak ada notifikasi allowlist 12 jam terakhir — tidak ada laporan yang dibuat."; r.startsWith("blocked:baterai") -> "Otak Latar tidak jalan: baterai di bawah 20% dan tidak dicas — colok charger dulu."; r.startsWith("blocked:gerbang") -> "Otak Latar tidak jalan: jam hening / Wi-Fi-only aktif."; r.startsWith("blocked:sesi mati") -> "Otak Latar tidak jalan: latar sedang jeda (sesi mati, flag lama) — coba lagi beberapa menit lagi."; r.startsWith("blocked:kuota") -> "Otak Latar tidak jalan: kuota Governor habis."; else -> "Otak Latar tidak jalan sekarang ($r) — coba lagi nanti." }; Toast.makeText(host, msg, Toast.LENGTH_LONG).show() }
        addBtn("56. Kill Switch F1") { f1Dialog() }
        addBtn("57. Dump Prompt (debug) — aktif/mati") { val p = host.getSharedPreferences("harness", Context.MODE_PRIVATE); val on = !p.getBoolean("debugPromptDump", false); p.edit().putBoolean("debugPromptDump", on).apply(); host.setupStatusTv.text = if (on) "Dump prompt AKTIF: prompt teredaksi ditulis ke filesDir/dump/harness-prompt-last.txt tiap kirim API. Matikan lagi sesudah mengukur." else "Dump prompt MATI." }
        addBtn("58. Tes Pola Redaksi") { host.setupStatusTv.text = RedactSecrets.selfTest().joinToString("\n") }
        addBtn("59. Uji Mandiri (tanpa model, tanpa jaringan)") { runUjiMandiri() }
        addBtn("60. Jejak ANR Terakhir") { showAnrTrace() }
        addBtn("61. Konektor MCP") { showMcpServers() }
        addBtn("62. 🔌 Token API-Buatan") { showApiTokenDialog() }
        addBtn("63. 🧑‍🏭 Jobs Proot (Supervisor)") { showProotJobsDialog() }
        addBtn("64. 🔍 Mitmproxy Terkelola") { showMitmProxyDialog() }
        addBtn("67. 👷 Worker (Device Server)") { showWorkerDialog() }
        // vc314 Worker B — Concat: entri Setup → Konektor → Concat
        // (layar detail buildConcatCardVc314, desain Mist).
        addBtn("71. Concat") { setupView = "concat"; refreshSetupNative() }
        // vc183: catatan klasik tidak dirender lagi.
        // vc183: klasik tidak dirender sama sekali (dulu T8: dibangun lalu GONE).
        addBtn("73. Provider LLM (OpenAI-compatible)") { showLlmProviderDialog() }
        // setupBtnViews tetap terdaftar & performClick tetap bekerja.
        return scroll
    }

    /** Profil Agen (v3.13.0): ganti/buat/hapus agen sebagai data. */
    internal fun MainActivity.setupAgentsDialog() {
        val th = currentTheme()
        val all = Agents.all(this)
        val cur = Agents.active(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("Aktif: " + cur.name, 12.5f, th.dim).apply { setPadding(0, 0, 0, dp(8)) })
        for (p in all) {
            val accent = try { Color.parseColor(p.accent) } catch (e: Exception) { ACCENT }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), dp(4), dp(6)) }
            row.addView(TextView(this).apply { text = if (p.id == cur.id) "●" else "○"; textSize = 16f; setTextColor(if (p.id == cur.id) accent else th.dim); setPadding(0, 0, dp(10), 0) })
            val colP = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            colP.addView(tv(p.name, 14.5f, th.text, true))
            colP.addView(tv("mesin " + p.engine + ", pak " + p.pack, 11.5f, th.dim))
            row.addView(colP)
            row.addView(thBtn("Edit") { setupAgentEdit(p) }.apply { setPadding(dp(10), dp(8), dp(10), dp(8)) })
            row.setOnClickListener {
                Agents.apply(this, p.id)
                setupStatusTv.text = "🤖 AGEN AKTIF: ${p.name} (mesin ${p.engine}, pak ${p.pack}, aksen ${p.accent}). Berlaku mulai pesan berikutnya."
                refreshProfil(); refreshBeranda()
            }
            col.addView(row)
        }
        col.addView(thBtn("＋ Buat agen baru…") { setupAgentCreate() })
        thDialog("Profil Agen", col, listOf(Triple("Tutup", false) { d: android.app.AlertDialog -> d.dismiss() }))
    }

    internal fun MainActivity.setupAgentEdit(p: Agents.Profile) {
        val th = currentTheme()
        val prefs = getSharedPreferences("harness", Context.MODE_PRIVATE)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("Mengedit: " + p.name, 12.5f, th.dim).apply { setPadding(0, 0, 0, dp(8)) })
        col.addView(tv("Nama", 12f, th.dim, true))
        val eName = EditText(this).apply { setText(p.name); setTextColor(th.text); setHintTextColor(th.dim) }
        col.addView(eName)
        col.addView(tv("Peran", 12f, th.dim, true).apply { setPadding(0, dp(10), 0, 0) })
        val eRole = EditText(this).apply { setText(agentRole(p.id, p.engine)); setTextColor(th.text); setHintTextColor(th.dim) }
        col.addView(eRole)
        col.addView(tv("Pak Perilaku", 12f, th.dim, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        var selPack = p.pack
        val packRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val packBtns = mutableMapOf<String, Button>()
        for (pk in Behaviors.PACKS) {
            val b = Button(this).apply {
                text = pk.name
                textSize = 11f
                background = rounded(if (pk.id == selPack) ACCENT else th.tileBg, 12)
                setTextColor(if (pk.id == selPack) Color.WHITE else th.text)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                minWidth = 0
                setOnClickListener {
                    selPack = pk.id
                    for ((id2, bb) in packBtns) { val on2 = id2 == selPack; bb.background = rounded(if (on2) ACCENT else th.tileBg, 12); bb.setTextColor(if (on2) Color.WHITE else th.text) }
                }
            }
            packBtns[pk.id] = b
            packRow.addView(b)
        }
        col.addView(HorizontalScrollView(this).apply { addView(packRow); isHorizontalScrollBarEnabled = false })
        col.addView(tv("Mesin: " + p.engine, 12f, th.dim).apply { setPadding(0, dp(10), 0, 0) })
        val buttons = mutableListOf<Triple<String, Boolean, (android.app.AlertDialog) -> Unit>>(
            Triple("Simpan", true) { d: android.app.AlertDialog ->
                val nm = eName.text.toString().trim()
                val rl = eRole.text.toString().trim()
                if (nm.isNotBlank()) prefs.edit().putString("agentName_" + p.id, nm).apply()
                if (rl.isNotBlank()) prefs.edit().putString("agentRole_" + p.id, rl).apply()
                prefs.edit().putString("agentPack_" + p.id, selPack).apply()
                if (Agents.active(this).id == p.id) Agents.apply(this, p.id)
                setupStatusTv.text = "🤖 Agen diperbarui: " + (if (nm.isNotBlank()) nm else p.name) + "."
                refreshProfil(); refreshBeranda()
                d.dismiss()
            },
            Triple("Batal", false) { d: android.app.AlertDialog -> d.dismiss() }
        )
        if (!p.builtin) {
            buttons.add(Triple("Hapus", false) { d: android.app.AlertDialog ->
                Agents.delete(this, p.id)
                try { File(filesDir, "otak-" + p.id).deleteRecursively() } catch (e: Exception) {}
                setupStatusTv.text = "🤖 Agen ${p.name} dihapus (file otaknya ikut dibuang)."
                refreshProfil(); refreshBeranda()
                d.dismiss()
            })
        }
        thDialog("Edit Agen", col, buttons, danger = setOf("Hapus"))
    }

    internal fun MainActivity.setupAgentCreate() {
        val th = currentTheme()
        val input = EditText(this).apply {
            hint = "Nama agen baru (mis. Penulis)"
            setTextColor(th.text); setHintTextColor(th.dim)
        }
        thDialog("Buat Agen Baru", input, listOf(
            Triple("Buat & Aktifkan", true) { d: android.app.AlertDialog ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    val p = Agents.create(this, name)
                    Agents.apply(this, p.id)
                    setupStatusTv.text = "🤖 Agen baru '${p.name}' dibuat & aktif (otak sendiri: namespace ${p.id}, aksen ${p.accent}). Atur jiwanya lewat Setup 41."
                }
                d.dismiss()
            },
            Triple("Batal", false) { d: android.app.AlertDialog -> d.dismiss() }
        ))
    }

    /** Agen Native (v3.12.0): editor file otak SOUL/AGENTS/MEMORY. */
    internal fun MainActivity.setupBrainDialog() {
        val th = currentTheme()
        val names = arrayOf("SOUL.md", "AGENTS.md", "MEMORY.md")
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dlgRef: android.app.AlertDialog? = null
        for (n in names) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10)) }
            row.addView(tv(n, 14f, th.text).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            row.addView(tv("›", 18f, th.dim, true))
            row.setOnClickListener { dlgRef?.dismiss(); setupBrainEdit(n) }
            col.addView(row)
        }
        dlgRef = thDialog("Otak Agen — pilih file yang diedit", col, listOf(Triple("Tutup", false) { d: android.app.AlertDialog -> d.dismiss() }))
    }

    internal fun MainActivity.setupBrainEdit(name: String) {
        val th = currentTheme()
        val input = EditText(this).apply {
            setText(AgentBrain.read(host, name))
            setTextColor(th.text); setHintTextColor(th.dim); background = roundedS(th.bg, 12, th.stroke)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minLines = 10
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        thDialog("Edit $name", input, listOf(
            Triple("Simpan", true) { d: android.app.AlertDialog ->
                AgentBrain.writeRaw(this, name, input.text.toString())
                setupStatusTv.text = "🧠 OTAK AGEN: $name disimpan. Berlaku mulai pesan berikutnya."
                d.dismiss()
            },
            Triple("Batal", false) { d: android.app.AlertDialog -> d.dismiss() }
        ))
    }

    /** J6: pilih pak perilaku agen (preset gaya kerja sebagai data). */
    internal fun MainActivity.setupBehaviorDialog() {
        val th = currentTheme()
        val cur = Behaviors.byId(getSharedPreferences("harness", Context.MODE_PRIVATE).getString("behaviorPack", "asisten") ?: "asisten")
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dlgRef: android.app.AlertDialog? = null
        for (p in Behaviors.PACKS) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10)) }
            row.addView(TextView(this).apply { text = if (p.id == cur.id) "●" else "○"; textSize = 16f; setTextColor(if (p.id == cur.id) th.tileTint else th.dim); setPadding(0, 0, dp(10), 0) })
            val colP = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            colP.addView(tv(p.name, 14f, th.text, true))
            colP.addView(tv(p.desc, 12f, th.dim))
            row.addView(colP)
            row.setOnClickListener {
                getSharedPreferences("harness", Context.MODE_PRIVATE).edit().putString("behaviorPack", p.id).apply()
                setupStatusTv.text = "🎭 PAK PERILAKU (J6): aktif = ${p.name}. Berlaku mulai pesan berikutnya (semua mesin)."
                dlgRef?.dismiss()
            }
            col.addView(row)
        }
        dlgRef = thDialog("Pak Perilaku Agen (aktif: ${cur.name})", col, listOf(Triple("Tutup", false) { d: android.app.AlertDialog -> d.dismiss() }))
    }

    // Flag satu-shot refresh status embed: tab Setup dibuka-tutup cepat tidak
    // boleh menumpuk thread background check (ANR fix vc193).
    @Volatile private var embedStatusPending = false

    internal fun MainActivity.refreshSetupStatus() {
        if (!setupStatusTvReady()) return
        val nls = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val notifOn = nls.contains(packageName)
        val a11yOn = A11yService.instance != null
        val termuxOn = isPkgInstalled("com.termux")
        val shizukuOn = isPkgInstalled("moe.shizuku.privileged.api")
        val rishOn = File(filesDir, "rish").exists() && File(filesDir, "rish_shizuku.dex").exists()
        val pinOn = getSharedPreferences("harness", Context.MODE_PRIVATE).getString("pinHash", "")?.isNotBlank() == true
        val memInfo = Embedder.packInfoQuick(this)
        // vc301 SF-D6: status Mode ADB dua lapis — "Server" dibaca dari
        // pidfile proses server (hidup/mati), "Binder app" dari binder
        // in-memory (tersambung/putus). Dulu satu lapis (isAlive saja):
        // server hidup tapi binder putus sesudah app restart tampil "mati".
        val statusLine = "Status: Notifikasi=${if (notifOn) "AKTIF" else "mati"} • Accessibility=${if (a11yOn) "AKTIF" else "mati"} • Termux=${if (termuxOn) "ada" else "tidak ada"} • Shizuku=${if (shizukuOn) "ada" else "tidak ada"} • rish=${if (rishOn) "siap" else "belum"} • PIN tool sensitif=${if (pinOn) "AKTIF (Setup 19 untuk ubah/matikan)" else "nonaktif"} • Mode ADB server=${if (PrivServer.serverAliveByPidfile()) "hidup (pidfile)" else "mati"} • Binder app=${if (PrivServer.isAlive()) "tersambung" else "putus"}\nMemori vektor: $memInfo"
        // v4.48.2: log crash TIDAK lagi di status atas — pindah ke bawah
        // menu Setup (refreshSetupNative, cabang menu). Status atas hanya
        // statusLine, supaya tidak ketimpa pesan tombol Setup.
        setupStatusTv.text = statusLine
        // vc193 ANR fix: status placeholder berarti paket terunduh tapi mesin
        // belum init — satu background check (isReady BOLEH blokir di latar),
        // lalu refresh lagi di UI; pemanggilan kedua menampilkan status final
        // (SIAP atau error) sehingga tidak ada loop. Gagal permanen jadi error
        // final, bukan placeholder.
        if (memInfo == Embedder.ENGINE_CHECKING && !embedStatusPending) {
            embedStatusPending = true
            Thread {
                try { Embedder.isReady(this) } catch (e: Exception) {}
                embedStatusPending = false
                runOnUiThread { if (!isFinishing) refreshSetupStatus() }
            }.start()
        }
    }

    internal fun MainActivity.isPkgInstalled(pkg: String): Boolean =
        try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    internal fun MainActivity.pickFile(code: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.type = "*/*"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(i, code)
    }

    internal fun MainActivity.handleFilePickerResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK || data?.data == null) return
        if (requestCode == 43) {
            Thread {
                try {
                    val text = contentResolver.openInputStream(data.data!!)?.bufferedReader()?.readText() ?: ""
                    val root = JSONObject(text)
                    val mdb = MemoryDb.get(this)
                    var n = 0
                    val fa = root.optJSONArray("facts")
                    if (fa != null) for (i in 0 until fa.length()) {
                        // vc265 N2-M2: meniru restore vc261 (BrainBackup) —
                        // fakta diimpor sebagai BELUM TERKONFIRMASI
                        // (hormati "confirmed" dari ekspor, default false),
                        // bukan langsung terkonfirmasi.
                        val o = fa.getJSONObject(i); mdb.putFact(o.optString("k"), o.optString("v"), o.optBoolean("confirmed", false), "restore:backup"); n++
                    }
                    var blockedVc265 = 0
                    val sk = root.optJSONArray("skills")
                    if (sk != null) for (i in 0 until sk.length()) {
                        // vc265 N2-M2: meniru restore vc261 — skill via
                        // forgeUpsert (tanpa duplikat, slug sistem ditolak,
                        // descr + enabled ikut) + dipindai SkillSpector;
                        // skor >= 80 DITOLAK, tidak diimpor.
                        val o = sk.getJSONObject(i)
                        val sName = o.optString("name")
                        if (Tools.slugifySkillName(sName) in Tools.SYSTEM_SKILL_SLUGS) continue
                        val specVc265 = try { SkillSpector.scan(sName, o.optString("descr"), o.optString("instruction")) } catch (e: Exception) { null }
                        if (specVc265 != null && specVc265.blocked) { blockedVc265++; try { AttentionBudget.noteSkillBlocked(this) } catch (e: Exception) {}; continue }
                        val (sId, _) = mdb.forgeUpsert(sName, o.optString("instruction"), o.optString("descr"))
                        if (sId > 0 && o.has("enabled")) mdb.setSkillEnabled(sId, o.optBoolean("enabled"))
                        if (sId > 0) n++
                    }
                    val sc = root.optJSONArray("schedules")
                    if (sc != null) for (i in 0 until sc.length()) {
                        // vc265 N2-M2: jadwal diberi ownerSession — pakai
                        // dari berkas bila ada, kalau tidak ada pakai sesi
                        // aktif saat impor.
                        val o = sc.getJSONObject(i); mdb.addSchedule(o.optInt("hour", 7), o.optInt("minute", 0), o.optString("prompt"), o.optLong("ownerSession", currentSessionId)); n++
                    }
                    val ru = root.optJSONArray("rules")
                    if (ru != null) for (i in 0 until ru.length()) {
                        val o = ru.getJSONObject(i); mdb.addRule(o.optString("pkg"), o.optString("needle"), o.optString("action")); n++
                    }
                    runOnUiThread { addBubble("📥 Backup diimpor: $n item (fakta/skill/jadwal/aturan) masuk ke memori." + (if (blockedVc265 > 0) " $blockedVc265 skill DITOLAK SkillSpector (skor risiko >= 80, tidak diimpor)." else ""), false) }
                } catch (e: Exception) {
                    runOnUiThread { addBubble("Impor backup gagal: ${e.message}", false) }
                }
            }.start()
            return
        }
        if (requestCode != 41 && requestCode != 42) return
        val name = if (requestCode == 41) "rish" else "rish_shizuku.dex"
        Thread {
            try {
                val input = contentResolver.openInputStream(data.data!!)
                val out = File(filesDir, name)
                input?.use { inp -> out.outputStream().use { o -> inp.copyTo(o) } }
                out.setExecutable(true, false)
                runOnUiThread { refreshSetupStatus(); addBubble("File Shizuku '$name' tersimpan.", false) }
            } catch (e: Exception) {
                runOnUiThread { addBubble("Gagal menyimpan $name: ${e.message}", false) }
            }
        }.start()
    }
