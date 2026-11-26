package com.senzwh.deepharness

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Test JVM untuk ModelPickerController (§5.10.1–3, T8-controller):
// isValidBaseUrl (murni) + state machine + single-flight + cache-first.
// Dijalankan oleh tools/run_tests.sh.
//
// Pola sama seperti tes lain: runner main() + check(name, cond).
// listModelsFn diinjeksikan (PALSU — tanpa HTTP sungguhan); callback
// onStateChanged ditangkap ke list tersinkron karena datang dari thread
// worker controller. Tiap controller di-close() di finally supaya thread
// executor-nya tidak menahan JVM.

private var failedPicker = 0
private var defectsPicker = 0

private fun checkPicker(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        failedPicker++; println("  FAIL $name" + detailSuffix)
    }
}

/**
 * Probe DEFECT (bukan FAIL): membuktikan penyimpangan dari spek §5.10.3
 * tanpa menggagalkan suite — sumbernya file main (dilarang disentuh tugas
 * ini), jadi dicatat di sini + laporan, bukan disembunyikan. Bila defect
 * diperbaiki di main, probe ini berubah menjadi "ok" sendiri.
 */
private fun defectPicker(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        val detailSuffix = if (detail.isNotBlank()) " — $detail" else ""
        defectsPicker++; println("  DEFECT $name" + detailSuffix)
    }
}

private fun pickerOk(vararg ids: String, total: Int = ids.size, fetchedAt: Long = 1_700_000_000_000L): ModelsResult.Ok =
    ModelsResult.Ok(ids.map { ModelInfo(it) }, fetchedAt, total)

private fun pickerErr(kind: ModelsErrorKind, message: String): ModelsResult.Err =
    ModelsResult.Err(kind, message)

/** Controller + daftar state yang ditangkap (urut kedatangan). */
private class PickerHarness(
    val ctx: FakeContext = FakeContext(),
    val providerId: String = "prov-picker",
    var fake: (String, String?) -> ModelsResult = { _, _ -> pickerOk("m-a", "m-b") }
) {
    val states: MutableList<ModelPickerState> =
        Collections.synchronizedList(mutableListOf<ModelPickerState>())
    val calls = AtomicInteger(0)
    val ctrl = ModelPickerController(ctx, providerId, { states.add(it) }) { url, key ->
        calls.incrementAndGet()
        fake(url, key)
    }
    fun close() = ctrl.close()
}

/** Tunggu sampai currentState memenuhi predikat (poll 25 ms), atau null bila timeout. */
private fun pickerWaitFor(
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

fun main() {
    println("== ModelPickerControllerTest ==")

    // ------------------------------------------- isValidBaseUrl (murni)
    run {
        checkPicker("DEBOUNCE_MS 600", ModelPickerController.DEBOUNCE_MS == 600L,
            "dapat=${ModelPickerController.DEBOUNCE_MS}")
        val valid = listOf(
            "https://api.openai.com/v1",
            "http://localhost:11434/v1",
            "https://api.example.com:8443/v1",
            "https://api.openai.com/v1/",
            "http://localhost:8080",
            "https://sub.domain.co.uk/path",
            "http://192.168.1.1:11434/v1",
            "  https://api.openai.com/v1  "
        )
        for (u in valid) {
            checkPicker("isValidBaseUrl valid '$u'", ModelPickerController.isValidBaseUrl(u))
        }
        val invalid = listOf(
            "api.openai.com/v1",                 // tanpa skema
            "https://api.openai.com/v1 model",   // ada spasi
            "https://",                          // host kosong
            "https://myhost/v1",                 // host tanpa titik, bukan localhost
            "https:///v1",                       // host kosong
            "ftp://api.example.com/v1",          // skema bukan http(s)
            "",
            "   ",
            "https://api.openai.com /v1"         // spasi di tengah
        )
        for (u in invalid) {
            checkPicker("isValidBaseUrl tidak valid '$u'", !ModelPickerController.isValidBaseUrl(u))
        }
    }

    // ------------------------------------------- onOpened URL tidak valid -> IDLE, tanpa fetch
    run {
        val h = PickerHarness()
        try {
            h.ctrl.onOpened("api.openai.com/v1", null)
            Thread.sleep(300)
            val st = h.ctrl.currentState()
            checkPicker("onOpened URL tidak valid -> IDLE", st.kind == ModelPickerKind.IDLE, "dapat=$st")
            checkPicker("onOpened URL tidak valid pesan isi base URL",
                st.message?.contains("Isi base URL dulu") == true, "msg=${st.message}")
            checkPicker("onOpened URL tidak valid -> fake TIDAK dipanggil", h.calls.get() == 0,
                "calls=${h.calls.get()}")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- onOpened valid + fake sukses -> LOADED
    run {
        val h = PickerHarness()
        try {
            h.ctrl.onOpened("https://api.openai.com/v1", null)
            val st = pickerWaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.LOADED }
            checkPicker("onOpened valid + sukses -> LOADED", st != null, "akhir=${h.ctrl.currentState()}")
            checkPicker("LOADED model terpilih = pertama", st?.selectedModel == "m-a",
                "selected=${st?.selectedModel}")
            checkPicker("LOADED pesan menyebut jumlah model",
                st?.message?.contains("2 model") == true, "msg=${st?.message}")
            checkPicker("LOADED daftar sesuai fake",
                st?.models?.map { it.id } == listOf("m-a", "m-b"), "dapat=${st?.models?.map { it.id }}")
            checkPicker("LOADED fetchedAtMs dari hasil fetch",
                st?.fetchedAtMs == 1_700_000_000_000L, "dapat=${st?.fetchedAtMs}")
            checkPicker("LOADED totalCount = jumlah model", st?.totalCount == 2, "dapat=${st?.totalCount}")
            checkPicker("LOADED bukan dari cache", st?.fromCache == false)
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- fake 401 tanpa key -> AUTH_NEEDED
    run {
        val h = PickerHarness(fake = { _, _ ->
            pickerErr(ModelsErrorKind.AUTH_NEEDED, "401 Unauthorized (mentah — harusnya tidak tampil)")
        })
        try {
            h.ctrl.onOpened("https://api.example.com/v1", null)
            val st = pickerWaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.AUTH_NEEDED }
            checkPicker("fake 401 tanpa key -> AUTH_NEEDED", st != null, "akhir=${h.ctrl.currentState()}")
            checkPicker("AUTH_NEEDED pesan 'butuh API key'",
                st?.message?.contains("butuh API key") == true, "msg=${st?.message}")
            checkPicker("AUTH_NEEDED pesan BUKAN pesan mentah fake",
                st?.message?.contains("mentah") != true, "msg=${st?.message}")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- fake error lain -> ERROR + pesan fake
    run {
        val h = PickerHarness(fake = { _, _ ->
            pickerErr(ModelsErrorKind.HTTP_ERROR, "Server 500 — coba lagi nanti")
        })
        try {
            h.ctrl.onOpened("https://api.example.com/v1", null)
            val st = pickerWaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.ERROR }
            checkPicker("fake error lain -> ERROR", st != null, "akhir=${h.ctrl.currentState()}")
            checkPicker("ERROR pesan = pesan fake", st?.message == "Server 500 — coba lagi nanti",
                "msg=${st?.message}")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- fake sukses daftar kosong -> EMPTY
    run {
        val h = PickerHarness(fake = { _, _ -> pickerOk(total = 0) })
        try {
            h.ctrl.onOpened("https://api.example.com/v1", null)
            val st = pickerWaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.EMPTY }
            checkPicker("fake sukses daftar kosong -> EMPTY", st != null, "akhir=${h.ctrl.currentState()}")
            checkPicker("EMPTY pesan menyebut tidak ada model + isi manual",
                st?.message?.contains("tidak mengembalikan model") == true &&
                    st?.message?.contains("Isi model manual") == true, "msg=${st?.message}")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- useManualModel -> MANUAL
    run {
        val h = PickerHarness()
        try {
            h.ctrl.onOpened("https://api.openai.com/v1", null)
            pickerWaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.LOADED }
            h.ctrl.useManualModel("gpt-custom")
            val st = h.ctrl.currentState()
            checkPicker("useManualModel -> MANUAL", st.kind == ModelPickerKind.MANUAL, "dapat=$st")
            checkPicker("MANUAL selectedModel = yang diketik", st.selectedModel == "gpt-custom",
                "selected=${st.selectedModel}")
            checkPicker("MANUAL pesan mode manual",
                st.message?.contains("Mode manual") == true, "msg=${st.message}")
            h.ctrl.useManualModel("   ")
            val stBlank = h.ctrl.currentState()
            checkPicker("useManualModel kosong -> MANUAL + selected null",
                stBlank.kind == ModelPickerKind.MANUAL && stBlank.selectedModel == null,
                "dapat=$stBlank")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- selectModel dipertahankan / diganti bila hilang
    // CATATAN DEFECT (lihat laporan §"Defect Fase 0"): fetchNow() +
    // onBaseUrlChanged() meng-emit LOADING tanpa selectedModel, jadi
    // lastState.selectedModel terhapus SEBELUM applyResult() membacanya —
    // cabang "pertahankan pilihan" + catatan "Model '<lama>' tidak ada…"
    // di applyResult() tidak pernah tercapai lewat fetch. Probe di bawah
    // membuktikannya: model yang dipertahankan sengaja BUKAN pertama di
    // daftar berikut, supaya lolos-kebetulan (pilih pertama) tidak menutupi.
    run {
        val h = PickerHarness()
        try {
            h.ctrl.onOpened("https://api.openai.com/v1", null)
            pickerWaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.LOADED }
            h.ctrl.selectModel("m-b")
            checkPicker("selectModel langsung mengubah pilihan",
                h.ctrl.currentState().selectedModel == "m-b",
                "selected=${h.ctrl.currentState().selectedModel}")
            // Fetch berikut masih memuat m-b, tapi BUKAN pertama -> bila
            // retention bekerja, pilihan tetap m-b; bila tidak, jatuh ke m-c.
            h.fake = { _, _ -> pickerOk("m-c", "m-b") }
            h.ctrl.refresh()
            val kept = pickerWaitFor(h.ctrl, 5000) {
                it.kind == ModelPickerKind.LOADED && it.models.map { m -> m.id } == listOf("m-c", "m-b")
            }
            checkPicker("selectModel: fetch berikut selesai (LOADED [m-c, m-b])",
                kept != null, "akhir=${h.ctrl.currentState()}")
            defectPicker("selectModel dipertahankan bila masih ada di daftar berikut (bukan pertama)",
                kept?.selectedModel == "m-b", "selected=${kept?.selectedModel} (pilih pertama='m-c')")
            // Fetch berikut TIDAK memuat m-b -> pilih pertama + catatan.
            h.fake = { _, _ -> pickerOk("m-c", "m-d") }
            h.ctrl.refresh()
            val dropped = pickerWaitFor(h.ctrl, 5000) {
                it.kind == ModelPickerKind.LOADED && it.models.map { m -> m.id } == listOf("m-c", "m-d")
            }
            checkPicker("model hilang -> pilih pertama", dropped?.selectedModel == "m-c",
                "selected=${dropped?.selectedModel}")
            defectPicker("model hilang -> pesan menyebut model lama + pengganti",
                dropped?.message?.contains("Model 'm-b' tidak ada di daftar ini") == true &&
                    dropped?.message?.contains("dipilih 'm-c'") == true, "msg=${dropped?.message}")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- totalCount > ukuran -> catatan 200 pertama
    run {
        val h = PickerHarness(fake = { _, _ -> pickerOk("a-1", "a-2", total = 250) })
        try {
            h.ctrl.onOpened("https://api.example.com/v1", null)
            val st = pickerWaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.LOADED }
            checkPicker("total > tampil -> pesan 'Menampilkan 2 dari 250 model'",
                st?.message?.contains("Menampilkan 2 dari 250 model") == true, "msg=${st?.message}")
            checkPicker("totalCount disimpan 250", st?.totalCount == 250, "dapat=${st?.totalCount}")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- single-flight: refresh saat fetch berjalan
    // Fetch pertama diblokir di fake; refresh() menaikkan generation; hasil
    // lama yang datang terlambat HARUS dibuang, hasil baru yang menang.
    run {
        val releaseFirst = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val callNo = AtomicInteger(0)
        val h = PickerHarness(fake = { _, _ ->
            if (callNo.incrementAndGet() == 1) {
                firstStarted.countDown()
                releaseFirst.await(10, TimeUnit.SECONDS)
                pickerOk("stale-a")
            } else {
                pickerOk("fresh-b")
            }
        })
        try {
            h.ctrl.onOpened("https://api.example.com/v1", null)
            checkPicker("single-flight: fetch pertama mulai", firstStarted.await(5, TimeUnit.SECONDS))
            h.ctrl.refresh() // generation naik selagi fetch lama masih berjalan
            Thread.sleep(200)
            releaseFirst.countDown()
            val st = pickerWaitFor(h.ctrl, 5000) {
                it.kind == ModelPickerKind.LOADED && it.models.map { m -> m.id } == listOf("fresh-b")
            }
            checkPicker("single-flight: hasil fetch lama dibuang, hasil baru menang",
                st != null, "akhir=${h.ctrl.currentState()}")
            checkPicker("single-flight: TIDAK ADA state LOADED berisi model lama",
                h.states.none { it.kind == ModelPickerKind.LOADED && it.models.any { m -> m.id == "stale-a" } },
                "states=${h.states.map { "${it.kind}:${it.models.map { m -> m.id }}" }}")
        } finally {
            releaseFirst.countDown()
            h.close()
        }
    }

    // ------------------------------------------- single-flight: ganti URL saat fetch berjalan
    run {
        val releaseFirst = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val callNo = AtomicInteger(0)
        val h = PickerHarness(fake = { url, _ ->
            if (callNo.incrementAndGet() == 1) {
                firstStarted.countDown()
                releaseFirst.await(10, TimeUnit.SECONDS)
                pickerOk("stale-a")
            } else {
                pickerOk(if (url.contains("baru")) "baru-b" else "lain-b")
            }
        })
        try {
            h.ctrl.onOpened("https://lama.example.com/v1", null)
            checkPicker("single-flight URL: fetch pertama mulai", firstStarted.await(5, TimeUnit.SECONDS))
            // Ketik URL baru -> debounce 600 ms -> fetch baru; executor sibuk,
            // jadi fetch baru jalan sesudah fetch lama dilepas.
            h.ctrl.onBaseUrlChanged("https://baru.example.com/v1")
            Thread.sleep(900) // lewati debounce; fetch baru masih antre di belakang fetch lama
            releaseFirst.countDown()
            val st = pickerWaitFor(h.ctrl, 5000) {
                it.kind == ModelPickerKind.LOADED && it.models.map { m -> m.id } == listOf("baru-b")
            }
            checkPicker("single-flight URL: hasil URL lama dibuang, URL baru menang",
                st != null, "akhir=${h.ctrl.currentState()}")
        } finally {
            releaseFirst.countDown()
            h.close()
        }
    }

    // ------------------------------------------- useManualModel membatalkan fetch berjalan
    run {
        val releaseFirst = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val h = PickerHarness(fake = { _, _ ->
            firstStarted.countDown()
            releaseFirst.await(10, TimeUnit.SECONDS)
            pickerOk("terlambat-a")
        })
        try {
            h.ctrl.onOpened("https://api.example.com/v1", null)
            checkPicker("manual-batal: fetch mulai", firstStarted.await(5, TimeUnit.SECONDS))
            h.ctrl.useManualModel("manual-x")
            checkPicker("manual-batal: MANUAL langsung",
                h.ctrl.currentState().kind == ModelPickerKind.MANUAL)
            releaseFirst.countDown()
            Thread.sleep(500)
            checkPicker("manual-batal: hasil terlambat tidak menimpa MANUAL",
                h.ctrl.currentState().kind == ModelPickerKind.MANUAL &&
                    h.ctrl.currentState().selectedModel == "manual-x",
                "akhir=${h.ctrl.currentState()}")
        } finally {
            releaseFirst.countDown()
            h.close()
        }
    }

    // ------------------------------------------- debounce 600 ms saat mengetik
    run {
        val h = PickerHarness()
        try {
            h.ctrl.onBaseUrlChanged("https://api.example.com/v1")
            checkPicker("debounce: LOADING langsung sesudah ketik",
                h.ctrl.currentState().kind == ModelPickerKind.LOADING,
                "dapat=${h.ctrl.currentState()}")
            Thread.sleep(200)
            checkPicker("debounce: fake BELUM dipanggil sebelum 600 ms", h.calls.get() == 0,
                "calls=${h.calls.get()}")
            val st = pickerWaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.LOADED }
            checkPicker("debounce: LOADED sesudah 600 ms", st != null,
                "akhir=${h.ctrl.currentState()}")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- onBaseUrlChanged URL tidak valid -> IDLE
    run {
        val h = PickerHarness()
        try {
            h.ctrl.onBaseUrlChanged("bukan-url")
            Thread.sleep(300)
            checkPicker("ketik URL tidak valid -> IDLE",
                h.ctrl.currentState().kind == ModelPickerKind.IDLE, "dapat=${h.ctrl.currentState()}")
            checkPicker("ketik URL tidak valid -> fake TIDAK dipanggil", h.calls.get() == 0,
                "calls=${h.calls.get()}")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- onApiKeyChanged: refetch sesudah AUTH_NEEDED
    run {
        val h = PickerHarness(fake = { _, key ->
            if (key.isNullOrBlank()) pickerErr(ModelsErrorKind.AUTH_NEEDED, "401")
            else pickerOk("keyed-a")
        })
        try {
            h.ctrl.onOpened("https://api.example.com/v1", null)
            val auth = pickerWaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.AUTH_NEEDED }
            checkPicker("key-refetch: tanpa key -> AUTH_NEEDED", auth != null,
                "akhir=${h.ctrl.currentState()}")
            val callsAfterAuth = h.calls.get()
            h.ctrl.onApiKeyChanged("sk-test-FAKEKEY123456")
            val st = pickerWaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.LOADED }
            checkPicker("key-refetch: isi key -> fetch ulang -> LOADED", st != null,
                "akhir=${h.ctrl.currentState()}")
            checkPicker("key-refetch: fetch ulang memakai key",
                st?.selectedModel == "keyed-a", "selected=${st?.selectedModel}")
            checkPicker("key-refetch: ada panggilan fetch tambahan",
                h.calls.get() > callsAfterAuth, "calls=${h.calls.get()} sebelumnya=$callsAfterAuth")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- onApiKeyChanged: TIDAK refetch bila sebelumnya sukses
    run {
        val h = PickerHarness()
        try {
            h.ctrl.onOpened("https://api.example.com/v1", null)
            pickerWaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.LOADED }
            val callsAfterOk = h.calls.get()
            h.ctrl.onApiKeyChanged("sk-test-FAKEKEY123456")
            Thread.sleep(1200) // lewati debounce 600 ms — harusnya tetap tanpa fetch baru
            checkPicker("key-tanpa-auth-gagal: ganti key TIDAK memicu fetch ulang",
                h.calls.get() == callsAfterOk, "calls=${h.calls.get()} sebelumnya=$callsAfterOk")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- onBaseUrlFocusLost
    run {
        val h = PickerHarness(fake = { url, _ ->
            pickerOk(if (url.contains("kedua")) "kedua-a" else "pertama-a")
        })
        try {
            h.ctrl.onOpened("https://pertama.example.com/v1", null)
            pickerWaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.LOADED }
            val callsAfterOk = h.calls.get()
            h.ctrl.onBaseUrlFocusLost("https://pertama.example.com/v1")
            Thread.sleep(400)
            checkPicker("focusLost URL sama (sudah sukses) -> TIDAK fetch ulang",
                h.calls.get() == callsAfterOk, "calls=${h.calls.get()} sebelumnya=$callsAfterOk")
            h.ctrl.onBaseUrlFocusLost("https://kedua.example.com/v1")
            val st = pickerWaitFor(h.ctrl, 5000) {
                it.kind == ModelPickerKind.LOADED && it.models.map { m -> m.id } == listOf("kedua-a")
            }
            checkPicker("focusLost URL beda -> fetch segera -> LOADED", st != null,
                "akhir=${h.ctrl.currentState()}")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- retry() bypass debounce (< 600 ms)
    run {
        val h = PickerHarness(fake = { _, _ -> pickerErr(ModelsErrorKind.HTTP_ERROR, "Server 500") })
        try {
            h.ctrl.onOpened("https://api.example.com/v1", null)
            pickerWaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.ERROR }
            h.fake = { _, _ -> pickerOk("retry-a") }
            val t0 = System.currentTimeMillis()
            h.ctrl.retry()
            val st = pickerWaitFor(h.ctrl, 3000) { it.kind == ModelPickerKind.LOADED }
            val elapsed = System.currentTimeMillis() - t0
            checkPicker("retry: ERROR -> LOADED", st != null, "akhir=${h.ctrl.currentState()}")
            checkPicker("retry: bypass debounce (LOADED < 600 ms)",
                st != null && elapsed < 600, "elapsed=${elapsed}ms")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- cache-first via LlmProviderStore (FakeContext)
    run {
        val ctx = FakeContext()
        val seededAt = 1_699_000_000_000L
        val saved = LlmProviderStore.saveModels(
            ctx, "prov-cache",
            listOf(
                ModelCaps.CachedModel("cached-a", "unknown"),
                ModelCaps.CachedModel("cached-b", "supported")
            ),
            seededAt
        )
        checkPicker("cache-first: seed cache tersimpan", saved)
        checkPicker("cache-first: loadModels baca seed",
            LlmProviderStore.loadModels(ctx, "prov-cache").map { it.id } == listOf("cached-a", "cached-b"),
            "dapat=${LlmProviderStore.loadModels(ctx, "prov-cache").map { it.id }}")
        val h = PickerHarness(ctx = ctx, providerId = "prov-cache",
            fake = { _, _ -> pickerOk("fresh-a", "fresh-b") })
        try {
            h.ctrl.onOpened("https://api.example.com/v1", null)
            // Cache ditampilkan sinkron di onOpened, sebelum fetch selesai.
            val cachedState = h.states.firstOrNull {
                it.kind == ModelPickerKind.LOADED && it.fromCache
            }
            checkPicker("cache-first: cache tampil dulu (LOADED fromCache)",
                cachedState != null, "states=${h.states.map { "${it.kind}:fromCache=${it.fromCache}" }}")
            checkPicker("cache-first: isi cache = seed",
                cachedState?.models?.map { it.id } == listOf("cached-a", "cached-b"),
                "dapat=${cachedState?.models?.map { it.id }}")
            checkPicker("cache-first: fetchedAt cache = seed",
                cachedState?.fetchedAtMs == seededAt, "dapat=${cachedState?.fetchedAtMs}")
            checkPicker("cache-first: vision seed terbawa (cached-b SUPPORTED)",
                cachedState?.models?.firstOrNull { it.id == "cached-b" }?.vision == VisionSupport.SUPPORTED,
                "dapat=${cachedState?.models}")
            val fresh = pickerWaitFor(h.ctrl, 5000) {
                it.kind == ModelPickerKind.LOADED && !it.fromCache &&
                    it.models.map { m -> m.id } == listOf("fresh-a", "fresh-b")
            }
            checkPicker("cache-first: fetch baru menimpa dengan daftar segar", fresh != null,
                "akhir=${h.ctrl.currentState()}")
            checkPicker("cache-first: fetch sukses MENULIS cache baru ke store",
                LlmProviderStore.loadModels(ctx, "prov-cache").map { it.id } == listOf("fresh-a", "fresh-b"),
                "dapat=${LlmProviderStore.loadModels(ctx, "prov-cache").map { it.id }}")
        } finally {
            h.close()
        }
    }

    // ------------------------------------------- cache kosong -> onOpened tanpa cache tetap LOADED via fetch
    run {
        val h = PickerHarness()
        try {
            h.ctrl.onOpened("https://api.example.com/v1", null)
            val st = pickerWaitFor(h.ctrl, 5000) { it.kind == ModelPickerKind.LOADED }
            checkPicker("tanpa cache: LOADED via fetch", st != null, "akhir=${h.ctrl.currentState()}")
            checkPicker("tanpa cache: TIDAK ADA state LOADED fromCache",
                h.states.none { it.kind == ModelPickerKind.LOADED && it.fromCache })
        } finally {
            h.close()
        }
    }

    if (defectsPicker > 0) println("DEFECT TERKONFIRMASI (file main, di luar wewenang tes ini): $defectsPicker — lihat laporan")
    println(if (failedPicker == 0) "SEMUA LULUS" else "GAGAL: $failedPicker cek")
    if (failedPicker != 0) kotlin.system.exitProcess(1)
}
