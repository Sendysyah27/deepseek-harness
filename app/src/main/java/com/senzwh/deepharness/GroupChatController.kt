package com.senzwh.deepharness

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

// ===== Grup Chat (G1) — ruang user + 4 agen =====
// Audit: pecah MainActivity. Extension MainActivity; state tetap di
// MainActivity (internal). Body VERBATIM.

    internal fun MainActivity.buildGroupBox(): LinearLayout {
        val th = currentTheme()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.bg) }
        // Header grup v4.48.1: 2 baris (mockup disetujui user 16:07).
        // Baris 1: judul (weight 1 + ellipsize, tidak pernah mendorong tombol
        // keluar layar) + Chat 1-1 + gear. Baris 2: chip kontrol dalam
        // HorizontalScrollView — muat di 360dp default, menggeser (bukan
        // terpotong) bila font sistem diperbesar. Semua ukuran dp().
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(th.card)
            setPadding(dp(10), dp(8), dp(10), dp(4))
        }
        // vc309 Worker B: header dilonggarkan — "tanpa thinking" mengecil
        // (11sp→10.5sp, singleLine + ellipsize, padding 6dp→4dp) dan
        // padding/margin tombol dipangkas (10dp→8dp, margin 8dp→6dp) supaya
        // judul "👥 Grup Agen" (weight 1) tidak lagi terpotong jadi "Gru..."
        // di layar ~360dp; tombol naik ke hit area 48dp (dulu ~30dp —
        // di bawah standar sentuh). Urutan + handler TIDAK BERUBAH.
        head.addView(TextView(this).apply {
            text = "👥 Grup Agen"
            setTextColor(th.text); textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        // vc246 GELOMBANG 5 (GR-m6): penanda persisten "tanpa thinking"
        // di header grup — dulu hanya disebut sekali di pesan routing.
        head.addView(TextView(this).apply {
            text = "tanpa thinking"
            setTextColor(th.dim); textSize = 10.5f
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), dp(2), dp(4), dp(2))
        })
        head.addView(Button(this).apply {
            text = "← Chat 1-1"
            isAllCaps = false
            background = rounded(th.tileBg, 16); setTextColor(th.text); textSize = 12f
            minWidth = 0; minHeight = dp(ChatThemeVc306.touchTarget)
            setPadding(dp(8), dp(7), dp(8), dp(7))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) }
            setOnClickListener { showTab("Chat") }
        })
        head.addView(Button(this).apply {
            text = "☰"
            background = rounded(th.tileBg, 16); setTextColor(th.text)
            minWidth = 0; minHeight = dp(ChatThemeVc306.touchTarget)
            setPadding(dp(8), dp(7), dp(8), dp(7))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) }
            setOnClickListener { showGroupSessionsPanel() }
        })
        head.addView(Button(this).apply {
            text = "⚙️"
            background = rounded(th.tileBg, 16); setTextColor(th.text)
            minWidth = 0; minHeight = dp(ChatThemeVc306.touchTarget)
            setPadding(dp(8), dp(7), dp(8), dp(7))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) }
            setOnClickListener { showGroupSettingsDialog() }
        })
        box.addView(head)
        val head2Inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(th.card)
            setPadding(dp(10), dp(4), dp(10), dp(8))
        }
        groupAllChip = Button(this).apply {
            text = "Semua jawab"
            // vc309 Worker B: chip tidak lagi allCaps bawaan Button
            // ("SEMUA JAWAB" memanjang + berteriak), padding dilonggarkan
            // (12/7dp→14/9dp), jarak antar chip 8dp→10dp, hit area 48dp
            // (dulu ~30dp). Warna ikut state via refreshGroupChips().
            isAllCaps = false
            textSize = 12f
            minWidth = 0; minHeight = dp(ChatThemeVc306.touchTarget)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) }
            setOnClickListener { GroupChat.setGroupAll(host, !GroupChat.isGroupAllOn(host)); refreshGroupChips() }
        }
        head2Inner.addView(groupAllChip)
        groupExecChip = Button(this).apply {
            text = "Mode Eksekusi"
            isAllCaps = false
            textSize = 12f
            minWidth = 0; minHeight = dp(ChatThemeVc306.touchTarget)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) }
            setOnClickListener { GroupChat.setGroupExec(host, !GroupChat.isGroupExecOn(host)); refreshGroupChips() }
        }
        head2Inner.addView(groupExecChip)
        head2Inner.addView(Button(this).apply {
            text = "📌 Rencana"
            isAllCaps = false
            background = rounded(th.tileBg, 16); setTextColor(th.text); textSize = 12f
            minWidth = 0; minHeight = dp(ChatThemeVc306.touchTarget)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener {
                val msg = GroupChat.saveLastRoundAsPlan(host)
                Toast.makeText(host, msg, Toast.LENGTH_LONG).show()
                showGroupPlanDialog()
            }
        })
        val head2 = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(th.card)
            isHorizontalScrollBarEnabled = false
            addView(head2Inner)
        }
        box.addView(head2)
        val dividerView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(th.stroke)
        }
        box.addView(dividerView)
        refreshGroupChips()
        groupScroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        groupList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
        groupScroll.addView(groupList)
        box.addView(groupScroll)
        groupTypingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(2), dp(12), dp(2))
            visibility = View.GONE
        }
        groupTypingTv = TextView(this).apply {
            setTextColor(th.dim); textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        groupTypingRow.addView(groupTypingTv)
        box.addView(groupTypingRow)
        groupReplyChipTv = TextView(this).apply {
            setTextColor(th.tileTint); textSize = 12f
            setPadding(dp(12), dp(4), dp(12), dp(4)); visibility = View.GONE
            setOnClickListener { groupReplyTargetName = null; groupReplyTargetText = null; refreshGroupReplyChip() }
        }
        box.addView(groupReplyChipTv)
        // vc309 Worker B (b) — AKAR composer HITAM di tema cerah: latar
        // baris ini dulu `rounded(Color.rgb(38,38,38), 18)` HARDCODED gelap
        // (tidak pernah membaca tema — tidak seperti composer 1-1 yang
        // memakai glassDrawableVc306 + diwarnai ulang di
        // refreshComposerButtonsVc306/applyVc306Chrome). Kini latarnya
        // glassDrawableVc306(rXl, isDarkTheme()) — PERSIS composer 1-1
        // (kaca cerah di tema cerah, kaca gelap di tema gelap) — plus
        // margin luar 12dp supaya jadi pill mengambang seperti 1-1 (dulu
        // menempel ke tepi layar) dan padding vertikal simetris.
        // groupInputRowRef dipakai refreshGroupChrome() agar ganti tema
        // tanpa recreate tetap mewarnai ulang composer grup.
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = glassDrawableVc306(ChatThemeVc306.rXl, isDarkTheme()); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(6), dp(12), dp(12)) }; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        groupInputRowRef = inputRow
        groupInput = EditText(this).apply {
            hint = "Pesan grup… (@Asisten @Mandor @Mentor @Agen 4)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(th.text); setHintTextColor(if (isDarkTheme()) ChatThemeVc306.darkFaint else ChatThemeVc306.lightFaint)
            background = null
            setPadding(dp(8), dp(8), dp(8), dp(8))
            minLines = 1
            maxLines = 5
            minWidth = dp(56)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRow.addView(groupInput)
        inputRow.addView(Button(this).apply {
            text = "➤"
            textSize = 20f
            setTextColor(Color.rgb(59, 130, 246))
            background = null
            minWidth = 0
            setPadding(dp(6), dp(0), dp(10), dp(0))
            setOnClickListener {
                var t = groupInput.text.toString().trim()
                if (t.isNotEmpty()) {
                    val rt = groupReplyTargetText
                    val rn = groupReplyTargetName
                    if (!rt.isNullOrBlank()) {
                        t = "↩️ Membalas ${rn ?: ""}: \"" + rt.take(200) + "\"\n\n" + t
                        groupReplyTargetName = null; groupReplyTargetText = null; refreshGroupReplyChip()
                    }
                    groupInput.setText(""); onGroupUserMessage(t)
                }
            }
        })
        box.addView(inputRow)
        // vc222: paksa lebar groupInput dari hasil ukur nyata (mekanisme
        // sama persis seperti composer 1-1). Listener ini HANYA mengatur
        // LEBAR input.
        inputRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val pw = inputRow.width
            if (pw == 0) return@addOnLayoutChangeListener
            var used = inputRow.paddingLeft + inputRow.paddingRight
            for (i in 0 until inputRow.childCount) {
                val v = inputRow.getChildAt(i)
                if (v !== groupInput && v.visibility != View.GONE) used += v.width
            }
            val avail = pw - used - groupInput.paddingLeft - groupInput.paddingRight
            val cur = groupInput.layoutParams.width
            if (avail > dp(56) && cur != avail) {
                groupInput.layoutParams = LinearLayout.LayoutParams(avail, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        groupBoxRef = box
        groupHeadRef = head
        groupHead2Ref = head2
        groupDividerRef = dividerView
        groupInputRef = groupInput
        return box
    }

    internal fun MainActivity.refreshGroupRoom() {
        if (groupLoaded) return
        groupLoaded = true
        try {
            groupList.removeAllViews()
            for (row in MemoryDb.get(this).groupMessages(200)) {
                when (row.role) {
                    "user" -> addGroupBubble(if (row.name.isNotBlank()) row.name else "Anda", "#4d8cff", row.text, true)
                    "sistem" -> addGroupBubble("Sistem", "#7d8aa0", row.text, false, true)
                    "istirahat" -> addGroupBubble(row.name, "#7d8aa0", row.text, false)
                    "tool" -> addGroupBubble(row.name, GroupChat.accentFor(row.name), row.text, false, isTool = true)
                    else -> addGroupBubble(row.name, GroupChat.accentFor(row.name), row.text, false)
                }
            }
        } catch (e: Exception) {}
    }

    internal fun MainActivity.addGroupBubble(name: String, accentHex: String, text: String, isUser: Boolean, isSystem: Boolean = false, isTool: Boolean = false) {
        val th = currentTheme()
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isUser) Gravity.END else Gravity.START
            setPadding(0, dp(3), 0, dp(3))
        }
        if (!isUser) {
            val ac = try { Color.parseColor(accentHex) } catch (e: Exception) { DIM }
            wrap.addView(TextView(this).apply {
                this.text = name
                // vc309 Worker B: label "Sistem" memakai errText, bukan
                // abu #7d8aa0 hardcoded (kontras label itu 3.17:1 di tema
                // cerah — di bawah AA; errText di atas bg halaman 5.9:1).
                setTextColor(if (isSystem) th.errText else ac); textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(12), 0, dp(12), dp(2))
            })
        }
        // vc254: pesan tool grup berformat aktivitas dirender sebagai
        // baris lipat (label nama anggota di atas tetap tampil).
        if (isTool) {
            val actVc254 = ActivityTitle.parseText(text)
            if (actVc254 != null) {
                val rowVc254 = buildActivityRow(actVc254.title, ActivityTitle.statusText(actVc254.ms, actVc254.ok), (if (actVc254.args.isNotBlank()) "Argumen: " + actVc254.args + "\n" else "") + "Hasil: " + actVc254.result, actVc254.tool)
                wrap.addView(rowVc254.root)
                groupList.addView(wrap)
                groupScroll.post { groupScroll.fullScroll(View.FOCUS_DOWN) }
                return
            }
        }
        val isAgentAnswer = !isUser && !isSystem && !isTool
        // G11 vc310 (S8=A): jalur tabel/kode grup — dibangun dari nol,
        // SAMA dengan 1-1 (T1–T5): tabel jadi view tabel, lipat +
        // "Lihat tabel (N baris)", HorizontalScrollView, cap 50 + muat
        // lagi, tombol salin per blok (kode/tabel/tiap blok teks).
        // Pesan user grup tetap teks polos (tidak diubah). Pesan agen
        // grup tanpa tabel/kode tetap lewat jalur tv lama di bawah.
        if (isAgentAnswer) {
            val gBlocks = MdMini.splitBlocks(text)
            val gHasBlocks = gBlocks.any { it is MdMini.Block.Table || it is MdMini.Block.Code }
            if (gHasBlocks) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(th.card, 24)
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    layoutParams = LinearLayout.LayoutParams(
                        (resources.displayMetrics.widthPixels * 0.92).toInt(),
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                card.addView(buildFoldableBlocksVc310(text, 15f) { card.performLongClick() })
                val groupMenu = { showBubbleMenu(text) { groupReplyTargetName = name; groupReplyTargetText = text; refreshGroupReplyChip() }; true }
                card.setOnLongClickListener { groupMenu() }
                wrap.setOnLongClickListener { groupMenu() }
                wrap.addView(card)
                groupList.addView(wrap)
                groupScroll.post { groupScroll.fullScroll(View.FOCUS_DOWN) }
                return
            }
        }
        val tv = TextView(this).apply {
            this.text = if (isAgentAnswer) (if (text.length > 1500) MdMini.render(text.substring(0, 1500) + "\n… [ketuk untuk baca selengkapnya]", th.codeText, th.codeBg) else MdMini.render(text, th.codeText, th.codeBg)) else text
            textSize = 15f
            setTextColor(if (isSystem) th.errText else th.text)
            // vc309 Worker B (c): kartu sistem TIDAK LAGI blok pink penuh
            // (th.errBg #FDE8E8 + teks merah — bentrok dengan palet Mist
            // walau kontrasnya lolos 5.51:1). Kini kartu Mist biasa
            // (th.card, sama seperti bubble asisten) + garis tepi 1dp
            // errText + teks tetap errText + label "Sistem" merah — tetap
            // terbaca sebagai peringatan, kontras justru naik (cerah
            // 6.31:1, gelap 9.02:1 — keduanya lolos AA 4.5:1). Palet
            // ChatThemeVc306.kt TIDAK diubah — hanya pemakaian tokennya.
            background = when {
                isUser -> rounded(Color.rgb(59, 130, 246), 24)
                isSystem -> roundedS(th.card, 24, th.errText)
                isTool -> rounded(th.tileBg, 24)
                else -> rounded(th.card, 24)
            }
            setPadding(dp(12), dp(9), dp(12), dp(9))
            maxWidth = (resources.displayMetrics.widthPixels * 0.92).toInt()
        }
        if (isAgentAnswer && text.length > 1500) {
            tv.tag = "short"
            tv.setOnClickListener {
                if (tv.tag == "short") { tv.text = MdMini.render(text, th.codeText, th.codeBg); tv.tag = "full" }
                else { tv.text = MdMini.render(text.substring(0, 1500) + "\n… [ketuk untuk baca selengkapnya]", th.codeText, th.codeBg); tv.tag = "short" }
            }
        }
        if (!isSystem) {
            val groupMenu = { showBubbleMenu(text) { groupReplyTargetName = name; groupReplyTargetText = text; refreshGroupReplyChip() }; true }
            tv.setOnLongClickListener { groupMenu() }
            wrap.setOnLongClickListener { groupMenu() }
        }
        wrap.addView(tv)
        groupList.addView(wrap)
        groupScroll.post { groupScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // vc246 GELOMBANG 5 (K-07): kartu opsi grup (komponen yang sama
    // polanya dengan kartu room: pertanyaan + tombol per opsi).
    // vc246 (GR-m5): indikator per anggota — kartu ini menyebut nama
    // anggota yang bertanya, bukan hanya "grup".
    internal fun MainActivity.addGroupAskCard(name: String, question: String, options: List<String>) {
        val th = currentTheme()
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)) }
        wrap.addView(TextView(this).apply { text = "❓ $name bertanya: $question"; setTextColor(th.text); textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD })
        for (opt in options) {
            wrap.addView(thBtn("▸ " + opt) { onGroupUserMessage(opt) })
        }
        wrap.addView(TextView(this).apply { text = "Ketuk salah satu opsi — jawabanmu dikirim ke grup."; setTextColor(th.dim); textSize = 11f })
        groupList.addView(wrap)
        groupScroll.post { groupScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // vc246 GELOMBANG 5 (GR-m4): LOG grup — dialog daftar pesan grup
    // terakhir dari tabel group_msgs (setara LOG per room). Dipanggil dari
    // tombol LOG di header grup bila tersedia; bila tidak, fungsi ini
    // tetap bisa dipanggil dari Setting Grup di iterasi berikutnya.
    internal fun MainActivity.showGroupLogDialog() {
        val th = currentTheme()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("LOG GRUP", 12f, th.dim, true))
        val rows = try { MemoryDb.get(this).groupMessages(50) } catch (e: Exception) { emptyList() }
        if (rows.isEmpty()) col.addView(tv("Belum ada pesan grup.", 13f, th.dim))
        else for (r in rows) col.addView(tv(r.name + " (" + r.role + "): " + r.text.take(160), 12f, th.text).apply { setPadding(0, 0, 0, dp(4)) })
        thDialog("📋 Log Grup", col, listOf(Triple("Tutup", false) { d: android.app.AlertDialog -> d.dismiss() }))
    }

    internal fun MainActivity.showGroupTyping(name: String) {
        groupTypingName = name
        groupTypingDots = 0
        groupTypingTv.removeCallbacks(groupTypingTick)
        groupTypingTv.text = name + " sedang merespon"
        groupTypingRow.visibility = View.VISIBLE
        groupTypingTv.post(groupTypingTick)
        groupScroll.post { groupScroll.fullScroll(View.FOCUS_DOWN) }
    }

    internal fun MainActivity.hideGroupTyping() {
        groupTypingTv.removeCallbacks(groupTypingTick)
        groupTypingRow.visibility = View.GONE
    }

    // vc246 GELOMBANG 5 (GR-m8): hentikan ronde grup yang sedang jalan
    // (setara Hentikan room). Dipanggil tombol di dialog Setting Grup +
    // bisa dipanggil dari chat dengan pesan "hentikan ronde grup".
    internal fun MainActivity.stopGroupRound() {
        GroupChat.requestStop()
        try { MemoryDb.get(this).addGroupMsg("sistem", "Sistem", "⏹️ Ronde grup dihentikan user.") } catch (e: Exception) {}
        addGroupBubble("Sistem", "#7d8aa0", "⏹️ Ronde grup dihentikan — anggota berhenti di giliran berikutnya.", false, true)
    }

    internal fun MainActivity.onGroupUserMessage(text: String) {
        try { MemoryDb.get(this).addGroupMsg("user", "Anda", text) } catch (e: Exception) {}
        addGroupBubble("Anda", "#4d8cff", text, true)
        GroupChat.sendRound(this, text, object : GroupChat.Listener {
            override fun onAgent(name: String, accent: String, text: String) {
                runOnUiThread { addGroupBubble(name, accent, text, false) }
            }
            override fun onSystem(text: String) {
                runOnUiThread { addGroupBubble("Sistem", "#7d8aa0", text, false, true) }
            }
            override fun onTurnStart(name: String) {
                runOnUiThread { if (groupTypingRowReady()) showGroupTyping(name) }
            }
            override fun onRest(name: String, text: String) {
                runOnUiThread { addGroupBubble(name, "#7d8aa0", text, false) }
            }
            override fun onTool(name: String, accent: String, text: String) {
                runOnUiThread { addGroupBubble(name, accent, text, false, isTool = true) }
            }
            // vc246 GELOMBANG 5 (K-07): kartu opsi grup — ketuk opsi =
            // kirim opsi itu sebagai pesan grup (diantrekan bila ronde
            // masih berjalan, lihat GR-m7).
            override fun onAsk(name: String, question: String, options: List<String>) {
                runOnUiThread { addGroupAskCard(name, question, options) }
            }
            override fun onToolApproval(req: ToolRequest, cb: (Boolean) -> Unit) {
                if (isFinishing || isDestroyed) { cb(false); return }
                runOnUiThread {
                    try {
                        val prefsR = getSharedPreferences("harness", Context.MODE_PRIVATE)
                        val pinHash = prefsR.getString("pinHash", "") ?: ""
                        val pinNeeded = ToolGate.needsPin(req.tool, pinHash.isNotBlank(), req.args, host)
                        val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)) }
                        box.addView(TextView(host).apply { this.text = "Grup Agen ingin menjalankan: ${req.tool}\n" + ToolGate.label(req.risk) + (if (req.preview.isNotBlank()) "\nPratinjau: ${req.preview}" else "") + "\n" + req.explanation + (if (req.affected.isNotEmpty()) "\nSasaran: ${req.affected.joinToString(", ")}" else "") + "\nArgumen: ${req.args}"; setTextColor(TEXT) })
                        var pinField: EditText? = null
                        if (pinNeeded) {
                            pinField = EditText(host).apply {
                                hint = "PIN aksi sensitif"
                                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                                setTextColor(TEXT); setHintTextColor(DIM)
                            }
                            box.addView(pinField)
                        }
                        val alwaysBox = CheckBox(host).apply { this.text = "Selalu izinkan tool ini (jangan tanya lagi)"; setTextColor(DIM); textSize = 12f }
                        box.addView(alwaysBox)
                        AlertDialog.Builder(host)
                            .setTitle("Izinkan aksi grup?")
                            .setView(ScrollView(host).apply { addView(box) })
                            .setPositiveButton("Izinkan") { _, _ ->
                                val pinTyped = pinField?.text?.toString() ?: ""
                                if (pinField != null && pinTyped.isBlank()) {
                                    Toast.makeText(host, "PIN belum diisi — aksi belum jalan.", Toast.LENGTH_LONG).show()
                                    cb(false)
                                } else if (pinField != null && sha256Hex(pinTyped) != pinHash) {
                                    Toast.makeText(host, "PIN salah — aksi dibatalkan.", Toast.LENGTH_LONG).show()
                                    cb(false)
                                } else {
                                    if (alwaysBox.isChecked) {
                                        // vc229 R-04(d): sama seperti dialog 1-1 —
                                        // HIGH tanpa PIN terpasang tidak dibuatkan autoAllow.
                                        if (ToolGate.level(req.tool, req.args) == RiskLevel.HIGH && pinHash.isBlank()) {
                                            Toast.makeText(host, "PIN belum diatur — autoAllow untuk tool HIGH tidak dibuat.", Toast.LENGTH_LONG).show()
                                        } else {
                                            ApprovalPanelStore.grantAutoAllow(host, req.tool, -1L)
                                        }
                                    }
                                    cb(true)
                                }
                            }
                            .setNegativeButton("Tolak") { _, _ -> cb(false) }
                            .setOnCancelListener { cb(false) }
                            .show()
                    } catch (e: Exception) { cb(false) }
                }
            }
            override fun onDone() {
                // Busy-path onDone (ronde lama masih jalan) tidak boleh mematikan indikator.
                runOnUiThread { if (!GroupChat.running && groupTypingRowReady()) hideGroupTyping() }
            }
        })
    }

    /** F5: dialog Setting Grup — CheckBox + Button eksplisit (bukan list dialog). */
    internal fun MainActivity.showGroupSettingsDialog() {
        val th = currentTheme()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { this.text = "Auto-approve tools/action di grup"; setTextColor(th.text); textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD })
        val autoBox = CheckBox(this).apply { this.text = "Jalankan tool tanpa dialog (foreground + Mode Eksekusi ON)"; setTextColor(th.text); textSize = 12f; isChecked = GroupChat.isGroupAutoApproveOn(host) }
        col.addView(autoBox)
        col.addView(TextView(this).apply { this.text = "Hanya berlaku untuk ronde foreground DAN bila Mode Eksekusi aktif. Bila Mode Eksekusi OFF, tidak ada tool apa pun sehingga toggle ini tak berpengaruh. Ronde latar tidak pernah memakai tool. CATATAN vc262: gerbang persetujuan tool NONAKTIF sejak vc247 (keputusan user) — semua tool grup jalan langsung tanpa dialog/PIN, jadi toggle ini saat ini TIDAK mengubah apa pun. Toggle dipertahankan untuk kemungkinan penghidupan gerbang di masa depan."; setTextColor(th.dim); textSize = 12f })
        col.addView(TextView(this).apply { this.text = "Anggota grup yang aktif"; setTextColor(th.text); textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD })
        val memberBoxes = GroupChat.MEMBERS.map { m ->
            CheckBox(this).apply { this.text = m.name; setTextColor(th.text); textSize = 13f; isChecked = GroupChat.isMemberActive(host, m.id) } to m.id
        }
        for ((box, _) in memberBoxes) col.addView(box)
        thDialog("⚙️ Setting Grup", col, listOf(
            Triple("Simpan", true) { d: android.app.AlertDialog ->
                // vc242 GR-M2 (opsi peringatan): mengaktifkan auto-approve
                // grup dari posisi OFF wajib dikonfirmasi eksplisit, karena
                // gerbangnya TIDAK disamakan dengan 1-1 vc229 (tanpa TTL,
                // tanpa ikatan sesi, tanpa re-PIN untuk HIGH).
                val turningOn = autoBox.isChecked && !GroupChat.isGroupAutoApproveOn(host)
                fun saveGroupSettings() {
                    GroupChat.setGroupAutoApprove(host, autoBox.isChecked)
                    for ((box, id) in memberBoxes) {
                        val was = GroupChat.isMemberActive(host, id)
                        GroupChat.setMemberActive(host, id, box.isChecked)
                        // vc246 GELOMBANG 5 (GR-m3): anggota yang dihapus/
                        // dinonaktifkan mendapat pemberitahuan di grup —
                        // dulu hanya prefs berubah tanpa jejak.
                        if (was && !box.isChecked) {
                            try { MemoryDb.get(host).addGroupMsg("sistem", "Sistem", "👋 " + (GroupChat.MEMBERS.firstOrNull { it.id == id }?.name ?: id) + " dinonaktifkan dari grup.") } catch (e: Exception) {}
                        }
                    }
                    refreshGroupChips()
                    Toast.makeText(host, "Setting grup disimpan.", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                if (turningOn) {
                    AlertDialog.Builder(host)
                        .setTitle("Aktifkan auto-approve grup?")
                        .setMessage("Auto-approve grup TIDAK kedaluwarsa, TIDAK meminta PIN untuk tool HIGH, dan TIDAK terikat sesi — semua anggota grup yang aktif bisa menjalankan tool tanpa dialog sampai toggle ini dimatikan manual. Tool kelas terkunci (mis. priv_input) dan create_subroom tetap lewat dialog. Lanjutkan mengaktifkan?")
                        .setPositiveButton("Saya mengerti, aktifkan") { _, _ -> saveGroupSettings() }
                        .setNegativeButton("Batal", null)
                        .show()
                } else saveGroupSettings()
            },
            // vc246 GELOMBANG 5 (GR-m8): tombol hentikan ronde grup
            // (setara Hentikan room) — dulu tidak ada tombol stop ronde
            // grup di UI sama sekali.
            Triple("⏹ Hentikan ronde", false) { d: android.app.AlertDialog -> d.dismiss(); stopGroupRound() },
            Triple("Tutup", false) { d: android.app.AlertDialog -> d.dismiss() }
        ))
    }

    /** F3d: dialog plan grup — Button eksplisit per entri (bukan list dialog). */
    internal fun MainActivity.showGroupPlanDialog() {
        val th = currentTheme()
        val plans = GroupChat.groupPlans(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (plans.isEmpty()) {
            col.addView(TextView(this).apply { this.text = "Belum ada rencana grup tersimpan."; setTextColor(th.dim); textSize = 13f })
        }
        var dlgHolder: android.app.AlertDialog? = null
        for (pl in plans) {
            col.addView(TextView(this).apply { this.text = "📌 ${pl.title}"; setTextColor(th.text); textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD })
            col.addView(TextView(this).apply { this.text = pl.body.take(300); setTextColor(th.dim); textSize = 12f })
            col.addView(thBtn("Hapus entri ini") { GroupChat.deleteGroupPlan(host, pl.ts); Toast.makeText(host, "Entri rencana dihapus.", Toast.LENGTH_SHORT).show(); dlgHolder?.dismiss() })
        }
        col.addView(thBtn("Hapus Semua") { GroupChat.clearGroupPlans(host); Toast.makeText(host, "Semua rencana grup dihapus.", Toast.LENGTH_SHORT).show(); dlgHolder?.dismiss() })
        dlgHolder = thDialog("Rencana Grup", col, listOf(Triple("Tutup", false) { d: android.app.AlertDialog -> d.dismiss() }))
    }
