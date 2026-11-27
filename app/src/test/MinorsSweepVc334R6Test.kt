package com.senzwh.deepharness

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Test JVM untuk Ronde 6 vc334 (minors sweep): F1-1..F1-5, G-3, D-2, D-3, P-2, L11.
// Dijalankan oleh tools/run_tests.sh (dengan -Dproj.dir untuk cek sumber).
//
// Pola sama seperti tes ronde sebelumnya: runner main() + check(name, cond).
// Tiap bug direproduksi dulu sebelum fix (run pertama: compile gagal karena
// API baru belum ada / cek FAIL) — lihat BUILD-VC334.md § Ronde 6.
//
// CATATAN: literal nama simbol yang dihapus (mis. nama akumulator /
// predikat kartu) HANYA muncul di string cek sumber di file ini — cek
// membaca file main, bukan file tes ini, jadi tidak ada false-positive
// dari komentar file ini sendiri.

private var failedR6 = 0

private fun checkR6(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedR6++; println("  FAIL $name" + detailSuffix)
    }
}

private fun r6Ok(vararg ids: String, total: Int = ids.size, fetchedAt: Long = 1_700_000_000_000L): ModelsResult.Ok =
    ModelsResult.Ok(ids.map { ModelInfo(it) }, fetchedAt, total)

/** Harness controller + daftar state yang ditangkap (urut kedatangan). */
private class R6PickerHarness(
    val ctx: FakeContext = FakeContext(),
    val providerId: String = "prov-r6",
    var fake: (String, String?) -> ModelsResult = { _, _ -> r6Ok("m-a", "m-b") },
    useRealListModels: Boolean = false
) {
    val states: MutableList<ModelPickerState> =
        Collections.synchronizedList(mutableListOf<ModelPickerState>())
    val calls = AtomicInteger(0)
    val ctrl: ModelPickerController = if (useRealListModels) {
        ModelPickerController(ctx, providerId, { states.add(it) })
    } else {
        ModelPickerController(ctx, providerId, { states.add(it) }) { url, key ->
            calls.incrementAndGet()
            fake(url, key)
        }
    }
    fun close() = ctrl.close()
}

private fun r6WaitFor(
    ctrl: ModelPickerController,
    timeoutMs: Long,
    pred: (ModelPickerState) -> Boolean
): ModelPickerState? {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last = ctrl.currentState()
    while (System.currentTimeMillis() < deadline) {
        last = ctrl.currentState()
        if (pred(last)) return last
        Thread.sleep(25)
    }
    return if (pred(last)) last else null
}

private fun r6MainText(proj: String, rel: String): String =
    try { java.io.File(proj, rel).readText() } catch (e: Exception) { "" }

fun main() {
    println("== MinorsSweepVc334R6Test ==")
    val proj = System.getProperty("proj.dir", "")
    checkR6("proj.dir diteruskan run_tests.sh", proj.isNotBlank(), "kosong")

    // ------------------------------------------- F1-1: TTL murni
    run {
        val now = System.currentTimeMillis()
        checkR6("F1-1 CACHE_TTL_MS 10 menit", ModelPickerController.CACHE_TTL_MS == 10 * 60 * 1000L,
            "dapat=${ModelPickerController.CACHE_TTL_MS}")
        checkR6("F1-1 cache 9 menit lalu = segar",
            ModelPickerController.isCacheFresh(now - 9 * 60 * 1000L, now))
        checkR6("F1-1 cache 1 menit lalu = segar",
            ModelPickerController.isCacheFresh(now - 60 * 1000L, now))
        checkR6("F1-1 cache tepat 10 menit lalu = TIDAK segar (batas)",
            !ModelPickerController.isCacheFresh(now - 10 * 60 * 1000L, now))
        checkR6("F1-1 cache 11 menit lalu = TIDAK segar",
            !ModelPickerController.isCacheFresh(now - 11 * 60 * 1000L, now))
        checkR6("F1-1 fetchedAt 0 (tak ada cache) = TIDAK segar",
            !ModelPickerController.isCacheFresh(0L, now))
    }

    // ------------------------------------------- F1-1 live: fetch kedua dalam 10 menit tanpa HTTP
    run {
        val ctx = FakeContext()
        val seeded = LlmProviderStore.saveModels(
            ctx, "prov-ttl",
            listOf(ModelCaps.CachedModel("ttl-a", "unknown"), ModelCaps.CachedModel("ttl-b", "supported")),
            System.currentTimeMillis()
        )
        checkR6("F1-1 seed cache segar tersimpan", seeded)
        val h = R6PickerHarness(ctx = ctx, providerId = "prov-ttl",
            fake = { _, _ -> r6Ok("fresh-a", "fresh-b") })
        try {
            h.ctrl.onOpened("https://api.example.com/v1", null)
            Thread.sleep(800)
            val st = h.ctrl.currentState()
            checkR6("F1-1 onOpened cache segar -> LOADED fromCache",
                st.kind == ModelPickerKind.LOADED && st.fromCache, "dapat=$st")
            checkR6("F1-1 onOpened cache segar -> daftar = seed",
                st.models.map { it.id } == listOf("ttl-a", "ttl-b"),
                "dapat=${st.models.map { it.id }}")
            checkR6("F1-1 onOpened cache segar -> fake TIDAK dipanggil", h.calls.get() == 0,
                "calls=${h.calls.get()}")
            // Controller baru untuk id yang sama (simulasi buka dialog lagi).
            val h2 = R6PickerHarness(ctx = ctx, providerId = "prov-ttl",
                fake = { _, _ -> r6Ok("fresh-a", "fresh-b") })
            try {
                h2.ctrl.onOpened("https://api.example.com/v1", null)
                Thread.sleep(800)
                checkR6("F1-1 buka kedua dalam TTL -> fake tetap TIDAK dipanggil",
                    h2.calls.get() == 0, "calls=${h2.calls.get()}")
                checkR6("F1-1 buka kedua -> tetap LOADED fromCache",
                    h2.ctrl.currentState().kind == ModelPickerKind.LOADED &&
                        h2.ctrl.currentState().fromCache,
                    "dapat=${h2.ctrl.currentState()}")
                // Tombol ⟳ tetap memaksa fetch walau cache segar.
                h2.ctrl.refresh()
                val fresh = r6WaitFor(h2.ctrl, 5000) {
                    it.kind == ModelPickerKind.LOADED && !it.fromCache &&
                        it.models.map { m -> m.id } == listOf("fresh-a", "fresh-b")
                }
                checkR6("F1-1 refresh() bypass TTL -> LOADED segar", fresh != null,
                    "akhir=${h2.ctrl.currentState()}")
                checkR6("F1-1 refresh() -> tepat 1 panggilan HTTP", h2.calls.get() == 1,
                    "calls=${h2.calls.get()}")
            } finally {
                h2.close()
            }
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- F1-1 live: cache basi tetap fetch
    run {
        val ctx = FakeContext()
        LlmProviderStore.saveModels(
            ctx, "prov-stale",
            listOf(ModelCaps.CachedModel("stale-a", "unknown")),
            System.currentTimeMillis() - 11 * 60 * 1000L
        )
        val h = R6PickerHarness(ctx = ctx, providerId = "prov-stale",
            fake = { _, _ -> r6Ok("fresh-a", "fresh-b") })
        try {
            h.ctrl.onOpened("https://api.example.com/v1", null)
            val fresh = r6WaitFor(h.ctrl, 5000) {
                it.kind == ModelPickerKind.LOADED && !it.fromCache &&
                    it.models.map { m -> m.id } == listOf("fresh-a", "fresh-b")
            }
            checkR6("F1-1 cache 11 menit lalu -> fetch jalan -> LOADED segar", fresh != null,
                "akhir=${h.ctrl.currentState()}")
            checkR6("F1-1 cache basi -> 1 panggilan HTTP", h.calls.get() == 1,
                "calls=${h.calls.get()}")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- F1-2: ganti id = controller baru, cache segar -> tanpa fetch
    run {
        val ctx = FakeContext()
        LlmProviderStore.saveModels(
            ctx, "prov-b",
            listOf(ModelCaps.CachedModel("b-a", "unknown")),
            System.currentTimeMillis()
        )
        // Controller A untuk id lain (tanpa cache) — fetch seperti biasa.
        val hA = R6PickerHarness(ctx = ctx, providerId = "prov-a",
            fake = { _, _ -> r6Ok("a-a") })
        try {
            hA.ctrl.onOpened("https://api.example.com/v1", null)
            val loadedA = r6WaitFor(hA.ctrl, 5000) { it.kind == ModelPickerKind.LOADED }
            checkR6("F1-2 id prov-a tanpa cache -> fetch jalan", loadedA != null,
                "akhir=${hA.ctrl.currentState()}")
            checkR6("F1-2 id prov-a -> 1 panggilan HTTP", hA.calls.get() == 1,
                "calls=${hA.calls.get()}")
        } finally {
            hA.close()
        }
        // "Ganti id" ke prov-b (controller baru, base URL sama) — cache prov-b segar.
        val hB = R6PickerHarness(ctx = ctx, providerId = "prov-b",
            fake = { _, _ -> r6Ok("b-fresh") })
        try {
            hB.ctrl.onOpened("https://api.example.com/v1", null)
            Thread.sleep(800)
            checkR6("F1-2 ganti id ke prov-b (cache segar) -> fake TIDAK dipanggil",
                hB.calls.get() == 0, "calls=${hB.calls.get()}")
            checkR6("F1-2 ganti id -> LOADED fromCache daftar prov-b",
                hB.ctrl.currentState().kind == ModelPickerKind.LOADED &&
                    hB.ctrl.currentState().fromCache &&
                    hB.ctrl.currentState().models.map { it.id } == listOf("b-a"),
                "dapat=${hB.ctrl.currentState()}")
        } finally {
            hB.close()
        }
    }

    // ------------------------------------------- F1-3: id kosong tanpa cache
    run {
        val ctx = FakeContext()
        val seeded = LlmProviderStore.saveModels(
            ctx, "",
            listOf(ModelCaps.CachedModel("seed-kosong", "unknown")),
            System.currentTimeMillis()
        )
        checkR6("F1-3 seed cache id kosong tersimpan (setup)", seeded &&
            LlmProviderStore.loadModels(ctx, "").map { it.id } == listOf("seed-kosong"),
            "dapat=${LlmProviderStore.loadModels(ctx, "").map { it.id }}")
        val h = R6PickerHarness(ctx = ctx, providerId = "",
            fake = { _, _ -> r6Ok("fresh-a", "fresh-b") })
        try {
            h.ctrl.onOpened("https://api.example.com/v1", null)
            val st = r6WaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.LOADED }
            checkR6("F1-3 id kosong -> tetap LOADED via fetch", st != null,
                "akhir=${h.ctrl.currentState()}")
            checkR6("F1-3 id kosong -> TIDAK ADA state LOADED fromCache",
                h.states.none { it.kind == ModelPickerKind.LOADED && it.fromCache },
                "states=${h.states.map { "${it.kind}:fromCache=${it.fromCache}" }}")
            checkR6("F1-3 id kosong -> daftar = hasil fetch, BUKAN seed",
                st?.models?.map { it.id } == listOf("fresh-a", "fresh-b"),
                "dapat=${st?.models?.map { it.id }}")
            checkR6("F1-3 id kosong -> fetch sukses TIDAK menimpa cache id kosong",
                LlmProviderStore.loadModels(ctx, "").map { it.id } == listOf("seed-kosong"),
                "dapat=${LlmProviderStore.loadModels(ctx, "").map { it.id }}")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- F1-4: LOADING mempertahankan daftar (controller + UI)
    run {
        val h = R6PickerHarness(fake = { _, _ -> r6Ok("m-a", "m-b") })
        try {
            h.ctrl.onOpened("https://api.example.com/v1", null)
            val loaded = r6WaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.LOADED }
            checkR6("F1-4 setup: LOADED dulu", loaded != null,
                "akhir=${h.ctrl.currentState()}")
            h.ctrl.selectModel("m-b")
            h.ctrl.onBaseUrlChanged("https://baru.example.com/v1")
            val loading = h.ctrl.currentState()
            checkR6("F1-4 controller: state langsung LOADING sesudah ketik",
                loading.kind == ModelPickerKind.LOADING, "dapat=$loading")
            checkR6("F1-4 controller: LOADING tetap membawa daftar lama",
                loading.models.map { it.id } == listOf("m-a", "m-b"),
                "dapat=${loading.models.map { it.id }}")
            checkR6("F1-4 controller: LOADING tetap membawa pilihan lama",
                loading.selectedModel == "m-b", "selected=${loading.selectedModel}")
        } finally {
            h.close()
        }
        // UI: cabang LOADING renderPickerState tidak boleh mengosongkan spinner.
        if (proj.isNotBlank()) {
            val main = r6MainText(proj, "app/src/main/java/com/senzwh/deepharness/MainActivity.kt")
            checkR6("F1-4 sumber MainActivity terbaca", main.isNotBlank())
            if (main.isNotBlank()) {
                val start = main.indexOf("ModelPickerKind.LOADING -> {")
                checkR6("F1-4 cabang LOADING ditemukan di renderPickerState", start >= 0)
                if (start >= 0) {
                    val end = main.indexOf("ModelPickerKind.", start + 10)
                    val branch = if (end > start) main.substring(start, end) else ""
                    checkR6("F1-4 cabang LOADING memakai daftar lama (state.models)",
                        branch.contains("state.models"), "cabang=$branch")
                    checkR6("F1-4 cabang LOADING TIDAK mengosongkan spinner",
                        !branch.contains("emptyList()"), "cabang=$branch")
                }
            }
        }
    }

    // ------------------------------------------- F1-5 live: fetch lambat dibatalkan saat generasi naik
    // Server lokal: request PERTAMA tidur 5 dtk lalu jawab slow-a;
    // request berikut langsung jawab fresh-b. refresh() saat fetch pertama
    // masih tidur HARUS memutus koneksinya (probe 27 Nov: disconnect()
    // menginterupsi getResponseCode yang blokir di JDK dalam ~5 ms) —
    // fetch kedua tidak boleh antre 5 dtk di belakang fetch pertama.
    run {
        val requests = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("::1", 0), 0)
        server.createContext("/v1/models") { ex ->
            val n = requests.incrementAndGet()
            try {
                if (n == 1) Thread.sleep(5000)
                val body = if (n == 1) "{\"data\":[{\"id\":\"slow-a\"}]}" else "{\"data\":[{\"id\":\"fresh-b\"}]}"
                val bytes = body.toByteArray(Charsets.UTF_8)
                ex.responseHeaders.set("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            } catch (e: Exception) {
                // fetch pertama yang diputus: tulis gagal sesudah disconnect — diharapkan.
                try { ex.responseBody.close() } catch (_: Exception) {}
            }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        try {
            // "localhost" lolos isValidBaseUrl; run_tests.sh menjalankan
            // tes ini dengan -Djava.net.preferIPv6Addresses=true supaya
            // localhost tersambung ke server ::1 (IPv4 loopback di VM ini
            // diintersep proxy egress — lihat BUILD-VC334.md § Ronde 6).
            val base = "http://localhost:${server.address.port}"
            val h = R6PickerHarness(providerId = "prov-f15", useRealListModels = true)
            try {
                h.ctrl.onOpened(base, null)
                // Tunggu fetch pertama benar-benar masuk ke server.
                val deadline = System.currentTimeMillis() + 5000
                while (requests.get() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(25)
                checkR6("F1-5 fetch pertama mulai (server terima request)", requests.get() >= 1,
                    "requests=${requests.get()}")
                val t0 = System.currentTimeMillis()
                h.ctrl.refresh() // generasi naik -> disconnect fetch lambat
                val fresh = r6WaitFor(h.ctrl, 8000) {
                    it.kind == ModelPickerKind.LOADED &&
                        it.models.map { m -> m.id } == listOf("fresh-b")
                }
                val elapsed = System.currentTimeMillis() - t0
                checkR6("F1-5 fetch kedua menang (LOADED fresh-b)", fresh != null,
                    "akhir=${h.ctrl.currentState()} elapsed=${elapsed}ms")
                checkR6("F1-5 fetch kedua TIDAK antre di belakang tidur 5 dtk (elapsed < 4 dtk)",
                    fresh != null && elapsed < 4000, "elapsed=${elapsed}ms")
                checkR6("F1-5 server terima tepat 2 request", requests.get() == 2,
                    "requests=${requests.get()}")
                checkR6("F1-5 TIDAK ADA state LOADED berisi model lambat",
                    h.states.none { it.kind == ModelPickerKind.LOADED && it.models.any { m -> m.id == "slow-a" } },
                    "states=${h.states.map { "${it.kind}:${it.models.map { m -> m.id }}" }}")
            } finally {
                h.close()
            }
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------- G-3 + D-2 + D-3 + P-2: cek sumber
    if (proj.isNotBlank()) {
        val group = r6MainText(proj, "app/src/main/java/com/senzwh/deepharness/GroupChat.kt")
        val main = r6MainText(proj, "app/src/main/java/com/senzwh/deepharness/MainActivity.kt")
        val llmProviders = r6MainText(proj, "app/src/main/java/com/senzwh/deepharness/LlmProviders.kt")
        val client = r6MainText(proj, "app/src/main/java/com/senzwh/deepharness/OpenAiCompatClient.kt")
        checkR6("G-3 sumber GroupChat terbaca", group.isNotBlank())
        checkR6("D-2/P-2 sumber MainActivity terbaca", main.isNotBlank())
        checkR6("P-2 sumber LlmProviders terbaca", llmProviders.isNotBlank())
        checkR6("P-2 sumber OpenAiCompatClient terbaca", client.isNotBlank())

        // G-3: akumulator write-only hilang total.
        checkR6("G-3 roundCorpusVc249 hilang dari GroupChat",
            !group.contains("roundCorpusVc249"), "masih ada")
        checkR6("G-3 komentar korpus basi hilang dari GroupChat",
            !group.contains("korpus per call"), "masih ada")

        // D-2: predikat + kartu tone warning claim-check hilang; kartu StuckDetector hidup.
        checkR6("D-2 isClaimWarningText hilang dari MainActivity",
            !main.contains("isClaimWarningText"), "masih ada")
        checkR6("D-2 teks produsen warning klaim tidak ada di MainActivity",
            !main.contains("Peringatan sistem: klaim") &&
                !main.contains("JAWABAN INI TIDAK DIDUKUNG HASIL TOOL"), "masih ada")
        checkR6("D-2 claimContainerHex hilang (warna kartu warning)",
            !main.contains("claimContainerHex"), "masih ada")
        checkR6("D-2 claimTextHex hilang (teks kartu warning)",
            !main.contains("claimTextHex"), "masih ada")
        checkR6("D-2 kartu StuckDetector DIPERTAHANKAN (isStuckStoppedText)",
            main.contains("isStuckStoppedText"), "hilang")
        checkR6("D-2 buildToneCardVc306 DIPERTAHANKAN (kartu danger)",
            main.contains("buildToneCardVc306"), "hilang")
        checkR6("D-2 buildToneCardVc306 kini satu argumen (tanpa toneKind)",
            main.contains("buildToneCardVc306(text)"), "tidak ditemukan")

        // D-3: komentar claim-check basi di file yang disentuh R6 sudah dirapikan;
        // komentar JUJUR di :276 dipertahankan (W4 §7: benar, jangan dihapus).
        checkR6("D-3 'kartu tone attention (claim-check)' hilang",
            !main.contains("kartu tone attention (claim-check)"), "masih ada")
        checkR6("D-3 'label claim-check' hilang",
            !main.contains("label claim-check"), "masih ada")
        checkR6("D-3 'Container kartu claim-check' hilang",
            !main.contains("Container kartu claim-check"), "masih ada")
        checkR6("D-3 komentar jujur :276 dipertahankan",
            main.contains("Dulu dibaca claim-check + reviewer 1-1, keduanya dihapus vc333."),
            "hilang — jangan hapus komentar jujur ini")

        // P-2: jalur pintas tanpa Governor/D4 hilang dari main (refleksi + grep).
        val providersClass = Class.forName("com.senzwh.deepharness.LlmProviders")
        checkR6("P-2 LlmProviders.chat hilang (refleksi)",
            providersClass.declaredMethods.none { it.name == "chat" },
            "metode=${providersClass.declaredMethods.map { it.name }}")
        val clientClass = Class.forName("com.senzwh.deepharness.OpenAiCompatClient")
        checkR6("P-2 OpenAiCompatClient.chat hilang (refleksi)",
            clientClass.declaredMethods.none { it.name == "chat" },
            "metode=${clientClass.declaredMethods.filter { it.name.startsWith("chat") }.map { it.name }}")
        checkR6("P-2 LlmProviders.chat hilang dari LlmProviders.kt",
            !llmProviders.contains("fun chat("), "masih ada")
        var shortcutCalls = 0
        val mainDir = java.io.File(proj, "app/src/main/java/com/senzwh/deepharness")
        mainDir.listFiles()?.filter { it.name.endsWith(".kt") }?.forEach { f ->
            val t = try { f.readText() } catch (e: Exception) { "" }
            if (t.contains("LlmProviders.chat(") || t.contains("OpenAiCompatClient.chat(")) shortcutCalls++
        }
        checkR6("P-2 TIDAK ADA pemanggil LlmProviders.chat / OpenAiCompatClient.chat di main",
            shortcutCalls == 0, "file pemanggil=$shortcutCalls")
    }

    // ------------------------------------------- L11: prune daily_* sekali sehari
    run {
        val ctx = FakeContext()
        val prefs = ctx.getSharedPreferences("turnMetrics", android.content.Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        TurnMetrics.beginTurn(ctx)
        TurnMetrics.recordPromptTotal(123)
        prefs.edit().putString("daily_2000-01-01", "{\"p\":1,\"s\":1}").apply()
        TurnMetrics.finish(ctx, "ok")
        checkR6("L11 finish pertama: daily_ lama (>30 hari) dibersihkan",
            prefs.getString("daily_2000-01-01", null) == null,
            "masih ada=${prefs.getString("daily_2000-01-01", null)}")
        checkR6("L11 finish pertama: penanda hari prune = hari ini",
            prefs.getString("daily_prune_day", null) == today,
            "dapat=${prefs.getString("daily_prune_day", null)}")
        checkR6("L11 finish pertama: agregat hari ini tetap ditulis",
            prefs.getString("daily_$today", null) != null,
            "tidak ada")
        TurnMetrics.beginTurn(ctx)
        TurnMetrics.recordPromptTotal(45)
        prefs.edit().putString("daily_2000-01-02", "{\"p\":1,\"s\":1}").apply()
        TurnMetrics.finish(ctx, "ok")
        checkR6("L11 finish kedua hari yang sama: daily_ lama TIDAK dibersihkan (prune sekali sehari)",
            prefs.getString("daily_2000-01-02", null) != null,
            "ikut terhapus — prune jalan tiap giliran")
        checkR6("L11 finish kedua: agregat hari ini tetap diakumulasi",
            prefs.getString("daily_$today", null)?.contains("\"p\":168") == true,
            "dapat=${prefs.getString("daily_$today", null)}")
    }

    println(if (failedR6 == 0) "SEMUA LULUS" else "GAGAL: $failedR6 cek")
    if (failedR6 != 0) kotlin.system.exitProcess(1)
}
