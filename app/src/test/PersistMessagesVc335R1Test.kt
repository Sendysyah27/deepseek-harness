package com.senzwh.deepharness

// Test JVM murni untuk D1 vc335 R1 — PERSIST MESSAGES lintas giliran.
// Dijalankan oleh tools/run_tests.sh.
//
// BUG (akar, vc334 R3 B7): mesin delta (AgentPrompt.deltaTurnPromptVc327)
// TIDAK DIPAKAI — sendToAgent tidak lagi menerima delta karena daftar
// messages giliran (`openaiTurnMessagesVc5b`) HANYA hidup di memori
// volatil: tiap giliran di-clear lalu diisi SATU pesan user berisi prompt
// PENUH yang dibangun ulang. Akibatnya tiap giliran mengirim ulang
// ~60–90rb char prompt penuh, dan delta (jangkar + perintah + work-notes)
// tidak pernah bisa dipakai — provider Models API stateless, jadi delta
// hanya valid bila riwayat messages percakapan itu PERSIST lintas
// giliran dan dikirim ulang apa adanya.
//
// FIX (D1, ronde ini): SessionMessagesVc335 — daftar messages per sesi
// disimpan lokal sebagai SATU file JSON per sesi
// (filesDir/session-messages/<sessionId>.json), dimuat ulang saat sesi
// dibuka, dipakai deltaTurnPromptVc327 sebagai pesan baru di atas
// messages terpersist (bukan rebuild prompt penuh dari memori volatil).
//
// APA YANG DITES DI SINI:
//  1. REPRO persist → muat ulang (store BARU = "restart") —
//     round-trip ChatMessage utuh (role/content/toolCallId/toolCalls).
//  2. REPRO delta dari messages terpersist — nextTurnMessages: bila
//     persisted kosong -> SATU pesan user prompt penuh; bila ada ->
//     persisted + pesan user delta (pesan pertama TETAP prompt penuh
//     giliran pertama, bukan rebuild).
//  3. REPRO trim D2 — pesan user pertama + 40 terakhir; pasangan
//     assistant<->tool tetap utuh (potongan di tengah pasangan ikut
//     menyertakan assistant pemiliknya; hasil tool yatim dibuang).
//  4. REPRO cap char — total >200.000 char dipangkas ke <=cap, anchor
//     (pesan user pertama) dipertahankan, pesan tunggal yang kelewat
//     cap dipotong kepala+ekor dengan penanda.
//  5. REPRO gambar TIDAK disimpan sebagai base64 — imageUrl data:
//     diganti referensi singkat; file JSON TIDAK mengandung payload
//     base64; imageUrl http/path biasa dipertahankan.
//  6. File korup / hilang -> load kosong tanpa throw; delete -> hilang.
//  7. JSON toleran — field tak dikenal diabaikan, entri tanpa role
//     dilewati.
//
// APA YANG TIDAK BISA DITES DI JVM (residu):
//  - MainActivity.sendToAgent/dispatchTurn/endUserTurn ITU SENDIRI:
//    Activity ~18,5 rb baris tidak bisa diinstansiasi di JVM. Yang
//    dites adalah fungsi murni + store yang dipakai wiring itu
//    (nextTurnMessages/canUseDelta/save/load) + verifikasi grep bahwa
//    wiring memanggilnya di titik yang benar (lihat catatan di akhir
//    file — koordinator cek via build APK / CI).
//  - Perilaku runtime di HP (file benar-benar tertulis di filesDir,
//    delta benar-benar dipakai provider) — hanya bisa dibuktikan
//    build APK + tes di POVA 5.

import java.io.File

private var failedVc335R1 = 0

private fun checkVc335R1(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("LULUS: $name")
    else {
        failedVc335R1++
        println("GAGAL: $name -- $detail")
    }
}

private fun tmpDirVc335R1(): File {
    val d = File(System.getProperty("java.io.tmpdir"), "vc335r1-" + System.nanoTime())
    d.mkdirs()
    return d
}

private fun sampleTurnMessagesVc335R1(): List<ChatMessage> {
    val fullPrompt = "[SISTEM] prompt penuh giliran pertama " + "x".repeat(100)
    return listOf(
        ChatMessage("user", fullPrompt),
        ChatMessage("assistant", "Baik, saya cek dulu.", toolCalls = listOf(
            NativeToolCall("tc-1", "read_file", "{\"path\":\"/sdcard/a.txt\"}")
        )),
        ChatMessage("tool", "isi file a", toolCallId = "tc-1"),
        ChatMessage("assistant", "Selesai — file berisi data."),
        ChatMessage("user", "lanjutkan ke langkah 2")
    )
}

fun main() {
    println("== PersistMessagesVc335R1Test ==")

    // ------------------------------------------------ 1. persist -> muat ulang
    run {
        val root = tmpDirVc335R1()
        val store = SessionMessagesStore(root)
        val msgs = sampleTurnMessagesVc335R1()
        checkVc335R1("1a: save mengembalikan true", store.save(7L, msgs))
        checkVc335R1("1b: file sesi tertulis", SessionMessagesStore(root).fileFor(7L).exists())
        // "Restart": store BARU atas direktori yang sama.
        val reloaded = SessionMessagesStore(root).load(7L)
        checkVc335R1("1c: muat ulang jumlah sama", reloaded.size == msgs.size,
            "dapat ${reloaded.size}, harap ${msgs.size}")
        checkVc335R1("1d: muat ulang isi identik (role+content)",
            reloaded.zip(msgs).all { (a, b) -> a.role == b.role && a.content == b.content },
            "reloaded=$reloaded")
        checkVc335R1("1e: toolCallId round-trip",
            reloaded[2].toolCallId == "tc-1", "dapat ${reloaded[2].toolCallId}")
        checkVc335R1("1f: toolCalls round-trip (nama+args)",
            reloaded[1].toolCalls.size == 1 && reloaded[1].toolCalls[0].name == "read_file" &&
                reloaded[1].toolCalls[0].argumentsJson == "{\"path\":\"/sdcard/a.txt\"}",
            "dapat ${reloaded[1].toolCalls}")
        checkVc335R1("1g: load sesi tak dikenal -> kosong",
            SessionMessagesStore(root).load(999L).isEmpty())
    }

    // --------------------------------------- 2. delta dari messages terpersist
    run {
        val root = tmpDirVc335R1()
        val store = SessionMessagesStore(root)
        store.save(3L, sampleTurnMessagesVc335R1())
        // Simulasi restart: objek sesi dibuat ulang dari storage.
        val persisted = SessionMessagesStore(root).load(3L)
        checkVc335R1("2a: canUseDelta true bila ada pesan user terpersist",
            SessionMessagesVc335.canUseDelta(persisted))
        checkVc335R1("2b: canUseDelta false bila kosong",
            !SessionMessagesVc335.canUseDelta(emptyList()))
        val fullPrompt = "[SISTEM] prompt penuh BARU — JANGAN dipakai bila delta bisa"
        val delta = "[LANJUTAN SISTEM — vc316 delta] tugas baru user"
        val next = SessionMessagesVc335.nextTurnMessages(persisted, fullPrompt, delta)
        checkVc335R1("2c: delta = persisted + 1 pesan",
            next.size == persisted.size + 1, "dapat ${next.size}")
        checkVc335R1("2d: pesan pertama TETAP prompt penuh giliran pertama (bukan rebuild)",
            next.first().role == "user" && next.first().content.startsWith("[SISTEM] prompt penuh giliran pertama"),
            "dapat ${next.first().content.take(60)}")
        checkVc335R1("2e: pesan terakhir = delta, BUKAN prompt penuh baru",
            next.last().role == "user" && next.last().content == delta &&
                !next.last().content.contains("prompt penuh BARU"),
            "dapat ${next.last().content.take(60)}")
        checkVc335R1("2f: prompt penuh baru TIDAK muncul di mana pun di daftar delta",
            next.none { it.content.contains("prompt penuh BARU") })
        val fresh = SessionMessagesVc335.nextTurnMessages(emptyList(), fullPrompt, delta)
        checkVc335R1("2g: sesi baru (kosong) -> SATU pesan user prompt penuh",
            fresh.size == 1 && fresh[0].role == "user" && fresh[0].content == fullPrompt,
            "dapat $fresh")
        // Ronde penuh: simpan hasil delta, muat ulang, delta lagi tetap jalan.
        store.save(3L, next)
        val persisted2 = SessionMessagesStore(root).load(3L)
        val next2 = SessionMessagesVc335.nextTurnMessages(persisted2, fullPrompt, "delta giliran 3")
        checkVc335R1("2h: delta giliran ke-3 tetap memakai anchor giliran pertama",
            next2.first().content.startsWith("[SISTEM] prompt penuh giliran pertama") &&
                next2.last().content == "delta giliran 3",
            "first=${next2.first().content.take(40)} last=${next2.last().content}")
    }

    // ------------------------------------------------------- 3. trim D2
    run {
        // 60 pesan: anchor user + 59 pesan campuran user/assistant.
        val many = ArrayList<ChatMessage>()
        many.add(ChatMessage("user", "ANCHOR prompt penuh giliran pertama"))
        for (i in 1..59) {
            many.add(if (i % 2 == 0) ChatMessage("assistant", "jawaban $i")
            else ChatMessage("user", "perintah $i"))
        }
        val trimmed = SessionMessagesVc335.trimD2(many)
        checkVc335R1("3a: trim 60 -> anchor + 40 terakhir = 41",
            trimmed.size == 41, "dapat ${trimmed.size}")
        checkVc335R1("3b: anchor (pesan user pertama) dipertahankan",
            trimmed.first().content == "ANCHOR prompt penuh giliran pertama")
        checkVc335R1("3c: 40 terakhir utuh (ekor sama dengan ekor asli)",
            trimmed.takeLast(40) == many.takeLast(40))
        checkVc335R1("3d: daftar pendek (<=41) tidak dipangkas",
            SessionMessagesVc335.trimD2(sampleTurnMessagesVc335R1()).size == 5)

        // Pasangan assistant<->tool: potongan JATUH di tengah pasangan.
        // Susun agar batas 40-terakhir jatuh tepat pada pesan tool kedua
        // dari sepasang hasil tool (assistant tc-9 + tool tc-9a + tool tc-9b):
        // total 45 pesan: anchor + 41 filler + assistant(tc-9) + tool a + tool b.
        val pair = ArrayList<ChatMessage>()
        pair.add(ChatMessage("user", "ANCHOR"))
        for (i in 1..41) pair.add(ChatMessage("user", "filler $i"))
        pair.add(ChatMessage("assistant", "panggil dua tool", toolCalls = listOf(
            NativeToolCall("tc-9a", "tool_a", "{}"),
            NativeToolCall("tc-9b", "tool_b", "{}")
        )))
        pair.add(ChatMessage("tool", "hasil a", toolCallId = "tc-9a"))
        pair.add(ChatMessage("tool", "hasil b", toolCallId = "tc-9b"))
        checkVc335R1("3e: susunan uji pasangan = 45 pesan", pair.size == 45, "dapat ${pair.size}")
        val trimmedPair = SessionMessagesVc335.trimD2(pair)
        // Batas 40-terakhir = indeks 5..44; assistant ada di indeks 42
        // (di DALAM jendela) — pasangan utuh tanpa perpanjangan.
        checkVc335R1("3f: pasangan utuh di dalam jendela (assistant + 2 tool hadir)",
            trimmedPair.any { it.role == "assistant" && it.toolCalls.size == 2 } &&
                trimmedPair.count { it.role == "tool" } == 2,
            "trimmed=$trimmedPair")
        checkVc335R1("3g: tidak ada pesan tool yatim di kepala (pesan tool pertama punya assistant pemilik sebelumnya)",
            trimmedPair.first().role != "tool")

        // Potongan tepat pada pesan tool: 46 pesan — assistant di indeks 42,
        // tool a di 43, tool b di 44, plus 1 pesan user di 45; jendela 40 =
        // indeks 6..45 (assistant + tools di dalam). Varian pemotong:
        // 47 pesan dengan 2 pesan user sesudah pasangan -> jendela mulai
        // indeks 7..46 — masih di dalam. Untuk MEMAKSA potongan di tengah
        // pasangan, geser: assistant di indeks 5 (tepat sebelum jendela
        // 40-terakhir bila total 46 dan jendela mulai indeks 6).
        val cut = ArrayList<ChatMessage>()
        cut.add(ChatMessage("user", "ANCHOR"))
        for (i in 1..4) cut.add(ChatMessage("user", "filler $i"))
        cut.add(ChatMessage("assistant", "panggil tool", toolCalls = listOf(
            NativeToolCall("tc-x", "tool_x", "{}")
        )))
        cut.add(ChatMessage("tool", "hasil x", toolCallId = "tc-x"))
        for (i in 1..39) cut.add(ChatMessage("user", "sesudah $i"))
        checkVc335R1("3h: susunan uji potong-pasangan = 46 pesan", cut.size == 46, "dapat ${cut.size}")
        val trimmedCut = SessionMessagesVc335.trimD2(cut)
        // Jendela 40-terakhir mulai indeks 6 = pesan tool "hasil x" YATIM
        // (assistant pemiliknya di indeks 5, di luar jendela) -> trim WAJIB
        // menyertakan assistant itu (41 pesan) ATAU membuang tool yatim.
        val firstToolIdx = trimmedCut.indexOfFirst { it.role == "tool" }
        val ownerKept = firstToolIdx < 0 ||
            (firstToolIdx > 0 && trimmedCut[firstToolIdx - 1].role == "assistant" &&
                trimmedCut[firstToolIdx - 1].toolCalls.any { it.id == trimmedCut[firstToolIdx].toolCallId })
        checkVc335R1("3i: potongan tengah pasangan -> assistant pemilik ikut ATAU tool yatim dibuang",
            ownerKept, "trimmed head=${trimmedCut.take(3)}")
        checkVc335R1("3j: anchor tetap pertama sesudah trim potong-pasangan",
            trimmedCut.first().content == "ANCHOR")
    }

    // ------------------------------------------------------- 4. cap char
    run {
        val big = ArrayList<ChatMessage>()
        big.add(ChatMessage("user", "ANCHOR " + "a".repeat(1000)))
        for (i in 1..10) big.add(ChatMessage("assistant", "b".repeat(30_000) + " #$i"))
        val capped = SessionMessagesVc335.applyCharCap(big, 200_000)
        val total = SessionMessagesVc335.totalChars(capped)
        checkVc335R1("4a: total sesudah cap <= 200.000 char",
            total <= 200_000, "dapat $total")
        checkVc335R1("4b: anchor dipertahankan sesudah cap",
            capped.first().content.startsWith("ANCHOR"))
        checkVc335R1("4c: cap membuang pesan lama dulu (pesan terbaru bertahan)",
            capped.last().content.contains("#10"), "last=${capped.last().content.takeLast(10)}")
        // Satu pesan raksasa sendirian (anchor > cap) -> dipotong kepala+ekor.
        val solo = listOf(ChatMessage("user", "A".repeat(100_000) + "TENGAH" + "B".repeat(100_000)))
        val cappedSolo = SessionMessagesVc335.applyCharCap(solo, 200_000)
        checkVc335R1("4d: pesan tunggal > cap dipotong ke <= cap dengan penanda",
            SessionMessagesVc335.totalChars(cappedSolo) <= 200_000 &&
                cappedSolo.size == 1 && cappedSolo[0].content.contains("dipotong"),
            "len=${cappedSolo[0].content.length} head=${cappedSolo[0].content.take(20)}")
        checkVc335R1("4e: kepala+ekor pesan tunggal dipertahankan",
            cappedSolo[0].content.startsWith("A") && cappedSolo[0].content.endsWith("B"))
        // save() menegakkan cap juga (bukan hanya fungsi murni).
        val root = tmpDirVc335R1()
        val store = SessionMessagesStore(root)
        store.save(5L, big)
        val loaded = store.load(5L)
        checkVc335R1("4f: save() menegakkan cap 200.000 di file",
            SessionMessagesVc335.totalChars(loaded) <= 200_000,
            "dapat ${SessionMessagesVc335.totalChars(loaded)}")
        checkVc335R1("4g: konstanta cap terdokumentasi = 200.000",
            SessionMessagesVc335.MAX_TOTAL_CHARS == 200_000)
    }

    // ------------------------------------------- 5. gambar: tanpa base64
    run {
        val b64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val payload = b64.repeat(200) // ~18rb char base64
        val msgs = listOf(
            ChatMessage("user", "lihat gambar ini", imageUrl = "data:image/png;base64,$payload"),
            ChatMessage("user", "dan file ini", imageUrl = "/sdcard/Download/foto.jpg"),
            ChatMessage("user", "serta tautan ini", imageUrl = "https://example.com/gambar.png")
        )
        val root = tmpDirVc335R1()
        val store = SessionMessagesStore(root)
        store.save(9L, msgs)
        val rawFile = SessionMessagesStore(root).fileFor(9L).readText()
        checkVc335R1("5a: file JSON TIDAK mengandung payload base64",
            !rawFile.contains(payload.take(100)) && !rawFile.contains("iVBORw0KGgo"),
            "file mengandung base64!")
        val loaded = store.load(9L)
        checkVc335R1("5b: imageUrl data: diganti referensi (bukan data-uri)",
            loaded[0].imageUrl != null && !loaded[0].imageUrl!!.startsWith("data:") &&
                loaded[0].imageUrl!!.contains("tidak dipersist"),
            "dapat ${loaded[0].imageUrl}")
        checkVc335R1("5c: referensi menyebut jenis + ukuran perkiraan",
            loaded[0].imageUrl!!.contains("image/png") && loaded[0].imageUrl!!.contains("B"),
            "dapat ${loaded[0].imageUrl}")
        checkVc335R1("5d: imageUrl path lokal dipertahankan apa adanya",
            loaded[1].imageUrl == "/sdcard/Download/foto.jpg", "dapat ${loaded[1].imageUrl}")
        checkVc335R1("5e: imageUrl http dipertahankan apa adanya",
            loaded[2].imageUrl == "https://example.com/gambar.png", "dapat ${loaded[2].imageUrl}")
        checkVc335R1("5f: sanitize murni — data-uri -> referensi, non-data -> utuh",
            SessionMessagesVc335.sanitizeImageRef("data:image/jpeg;base64,AAAA")?.startsWith("data:") == false &&
                SessionMessagesVc335.sanitizeImageRef("/x/y.jpg") == "/x/y.jpg" &&
                SessionMessagesVc335.sanitizeImageRef(null) == null)
    }

    // --------------------------------------- 6. korup / hilang / hapus
    run {
        val root = tmpDirVc335R1()
        val store = SessionMessagesStore(root)
        val f = store.fileFor(11L)
        f.parentFile?.mkdirs()
        f.writeText("{ ini bukan json [[[")
        checkVc335R1("6a: file korup -> load kosong tanpa throw", store.load(11L).isEmpty())
        f.writeText("{\"v\":1,\"messages\":[{\"role\":\"user\",\"content\":\"ok\"},{\"content\":\"tanpa role\"},{\"role\":\"\",\"content\":\"role kosong\"}]}")
        val loaded = store.load(11L)
        checkVc335R1("6b: entri tanpa role dilewati, entri valid lolos",
            loaded.size == 1 && loaded[0].content == "ok", "dapat $loaded")
        f.writeText("{\"v\":1,\"messages\":[],\"field_tak_dikenal\":{\"x\":1}}")
        checkVc335R1("6c: field tak dikenal diabaikan", store.load(11L).isEmpty())
        store.save(11L, sampleTurnMessagesVc335R1())
        checkVc335R1("6d: save menimpa file korup dengan benar",
            store.load(11L).size == 5)
        checkVc335R1("6e: delete menghapus file + load kosong sesudahnya",
            store.delete(11L) && !store.fileFor(11L).exists() && store.load(11L).isEmpty())
    }

    // --------------------------------------- 7. append + JSON toleran
    run {
        val root = tmpDirVc335R1()
        val store = SessionMessagesStore(root)
        store.save(13L, listOf(ChatMessage("user", "pertama")))
        store.append(13L, ChatMessage("assistant", "kedua"))
        val loaded = store.load(13L)
        checkVc335R1("7a: append menambah tanpa menghapus yang lama",
            loaded.size == 2 && loaded[0].content == "pertama" && loaded[1].content == "kedua",
            "dapat $loaded")
        val json = SessionMessagesVc335.toJson(sampleTurnMessagesVc335R1())
        val parsed = SessionMessagesVc335.fromJson(json)
        checkVc335R1("7b: toJson/fromJson round-trip utuh",
            parsed.size == 5 && parsed[1].toolCalls[0].name == "read_file")
        checkVc335R1("7c: fromJson teks kosong -> kosong", SessionMessagesVc335.fromJson("").isEmpty())
        checkVc335R1("7d: fromJson teks bukan JSON -> kosong",
            SessionMessagesVc335.fromJson("bukan json").isEmpty())
    }

    println(if (failedVc335R1 == 0) "SEMUA LULUS" else "GAGAL: $failedVc335R1 cek")
    if (failedVc335R1 != 0) kotlin.system.exitProcess(1)
}

// CATATAN VERIFIKASI GREP (untuk koordinator — wiring MainActivity):
//  - dispatchTurn HARUS memanggil AgentPrompt.deltaTurnPromptVc327 +
//    mengirim hasilnya sebagai deltaVc335 ke sendToAgent, BUKAN prompt penuh.
//  - sendToAgent HARUS memuat SessionMessagesStore(filesDir).load
//    (currentSessionId) dan memakai SessionMessagesVc335.nextTurnMessages
//    bila canUseDelta(persisted) true.
//  - endUserTurn HARUS memanggil SessionMessagesStore(filesDir).save
//    (turnSessionId, openaiTurnMessagesVc5b) untuk giliran native.
//  - MemoryDb.deleteSession HARUS menghapus file messages sesi itu.
//  - Setup wipe-all HARUS menghapus seluruh folder session-messages.
