package com.senzwh.deepharness

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONArray
import org.json.JSONObject

/** vc261 — kepercayaan endpoint (N-M3..N-M8, N-m1..N-m19): kode HTTP
 *  jujur, token per-worker, HMAC antrean, rotasi jujur, cap 5 tersinkron,
 *  status segar, jejak audit. RESIDU JUJUR (N-M4): vc281 Fase 3
 *  mencabut bind /sdcard dari sesi worker (allowlist cermin saja) — tapi
 *  residu ini TETAP: worker root di proot yang sama masih bisa membaca
 *  file token worker lain + folder cermin worker lain yang berbagi sesi
 *  yang sama. Isolasi sejati butuh worker non-root / proot terpisah.
 *  Token per-worker di
 *  sini membatasi lewat endpoint (rute + token), BUKAN isolasi filesystem.
 *  vc284 N4-M2: kunci HMAC antrean TIDAK LAGI keluar dari app — file
 *  kunci + verifier proot (verify-queue.py) dihapus dari /root/.harness/
 *  dan kunci dirotasi satu kali (flag prefs); verifikasi tugas pindah
 *  ke sisi server (POST /workers/<id>/verify-task, boleh token
 *  per-worker). "VALID" dari rute itu kini benar-benar bukti asal
 *  agen, karena kunci hanya dibaca app-side. Token master/per-worker
 *  TETAP harus bisa dibaca worker (itu cara worker memanggil endpoint)
 *  — residu N-M4 di atas tidak berubah. Hasil yang ditulis worker
 *  langsung ke cermin (tanpa lewat endpoint) kini TIDAK bertanda tangan
 *  dan tetap dilabeli DATA TIDAK TEPERCAYA saat collect.
 *  vc258 Fase 2 — control endpoint HTTP worker, localhost-only :4200.
 *  Satu pintu HTTP milik harness: daftar/spawn/stop/health/log worker +
 *  ambil/tulis tugas antrean (Jalur C brief §3.4). Auth token Bearer dari
 *  SecureStore (pola mcpToken_); token salah/tidak ada -> 401. Bind
 *  127.0.0.1 SAJA — JANGAN 0.0.0.0, JANGAN tailnet (itu Fase 4).
 *  Token TIDAK PERNAH ditulis ke log/chat/file biasa; file token untuk
 *  opencode hanya ada di dalam proot (/root/.harness/..., 0600) — bukan
 *  di /sdcard, bukan di chat.
 *  Hidup/mati: start dari ServeStandbyService (saat service standby
 *  jalan) + tombol panel Worker (Setup 67); watchdog tick 5 mnt
 *  memastikan hidup selama service hidup; mati bila app/service
 *  dimatikan (MIUI bisa membunuh service — panel bisa mulai ulang,
 *  status di panel jujur). Server: ServerSocket mentah pola McpOAuth
 *  (tanpa library baru). Header dibaca byte-per-byte supaya body
 *  (Content-Length, UTF-8) tidak tertelan buffer reader.
 *  Semua handler jalan di thread koneksi — JANGAN panggil dari main
 *  thread (fungsi blokir WorkerRegistry/ProotSession di dalamnya). */
object WorkerEndpoint {
    const val PORT = WorkerEndpointLogic.PORT
    const val TOKEN_KEY = "workerEndpointToken"
    const val TOKEN_FILE_PROOT = "/root/.harness/worker-endpoint-token"

    // vc284 N4-M2: verifier tugas sisi worker (verify-queue.py) DIHAPUS
    // dari proot — kunci HMAC tidak lagi keluar dari app, jadi verifier
    // yang butuh kunci tidak bisa tinggal di rootfs bersama. Pengganti:
    // POST /workers/<id>/verify-task (rute "verifyTask", boleh token
    // per-worker; kunci dibaca app-side dari SecureStore). Worker yang
    // masih memanggil verify-queue.py langsung akan gagal "file tidak
    // ditemukan" — pakai rute endpoint itu.

    @Volatile private var server: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    // vc259 — cache token Bearer di memori: SecureStore (AndroidKeyStore
    // AES/GCM) dibaca sekali, pemakaian berikutnya dari cache. Tanpa cache,
    // tiap request bayar dekripsi keystore + buka SQLite dalam keadaan
    // dingin — request pertama setelah idle stall >30 dtk dan mati di
    // soTimeout (curl exit 28 di perangkat). Cache TIDAK melemahkan auth:
    // nilai yang dibandingkan tetap token penuh dan perbandingan tetap
    // waktu-konstan (isEqual); cache dihapus saat token diputar.
    @Volatile private var cachedToken: String? = null

    // vc260 N-M1/N-M9: dispatch endpoint ber-timeout sendiri (25 dtk, di
    // bawah soTimeout 30 dtk) — bila sesi proot sibuk (cmdLock dipegang
    // exec panjang), request dijawab 503 + Retry-After, bukan menggantung
    // sampai timeout klien. Dispatch yang timeout tetap jalan di latar
    // (tidak bisa diinterupsi saat antre lock) — 503 berarti "coba lagi",
    // bukan "gagal". Pool thread terpisah supaya satu dispatch macet tidak
    // memblokir thread accept.
    private val dispatchPool = Executors.newCachedThreadPool { r -> Thread(r, "worker-ep-dispatch").apply { isDaemon = true } }
    const val DISPATCH_TIMEOUT_MS = 25_000L

    // vc265 N2-M3: idempotensi spawn/enqueue. Dispatch >25 dtk dijawab
    // 503 sementara dispatch latar tetap jalan — retry klien yang
    // mengikuti instruksi "coba lagi" TIDAK BOLEH membuat worker/tugas
    // kedua. Kunci = header Idempotency-Key klien (bila dikirim), atau
    // dedup server-side (spawn: nama+jenis+sesi+hash isi; enqueue:
    // worker+hash isi tugas). Hasil SUKSES (200) disimpan 10 menit;
    // dispatch identik yang masih jalan dipakai bersama (retry menunggu
    // hasilnya, bukan submit baru). Gagal TIDAK disimpan — retry boleh
    // mencoba ulang segar. Yang disimpan hanya kode + JSON hasil
    // (worker_id / nama tugas), TIDAK PERNAH secret. Jendela 10 menit:
    // retry sesudah 503 datang dalam hitungan detik/menit; enqueue teks
    // identik yang disengaja setelah 10 menit tetap dibuat sebagai tugas
    // baru (bukan duplikat yang ditahan selamanya).
    private const val IDEM_TTL_MS = 10 * 60 * 1000L
    private const val IDEM_MAX_ENTRIES = 500
    private val idemLock = Object()
    private val inFlight = HashMap<String, java.util.concurrent.Future<Pair<Int, JSONObject>>>()
    private val idemDone = HashMap<String, Pair<Long, Pair<Int, JSONObject>>>()

    fun isRunning(): Boolean = try { val s = server; s != null && !s.isClosed } catch (e: Exception) { false }

    /** Token: buat sekali (SecureRandom 32 byte, base64url), simpan di
     *  SecureStore. TIDAK PERNAH ditulis ke log. */
    fun token(ctx: Context): String {
        cachedToken?.takeIf { it.isNotBlank() }?.let { return it }
        val existing = try { SecureStore.get(ctx, TOKEN_KEY) } catch (e: Exception) { null }
        if (!existing.isNullOrBlank()) { cachedToken = existing; return existing }
        val buf = ByteArray(32)
        SecureRandom().nextBytes(buf)
        val fresh = Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        try { SecureStore.put(ctx, TOKEN_KEY, fresh) } catch (e: Exception) {}
        // vc260 N-m12: SecureStore.put menelan error diam-diam — verifikasi
        // simpan dengan baca-ulang. Bila tidak tersimpan, token TIDAK
        // dikembalikan seolah sukses (itu dulu bikin 401 permanen dengan
        // panel yang bilang token tersimpan).
        val savedVc260 = try { SecureStore.get(ctx, TOKEN_KEY) } catch (e: Exception) { null }
        if (savedVc260 != fresh) {
            try { android.util.Log.w("WorkerEp", "token() gagal menyimpan token baru ke SecureStore — token tidak dikembalikan") } catch (ignored: Exception) {}
            throw IllegalStateException("Token endpoint gagal disimpan ke SecureStore — endpoint tidak bisa mengautentikasi request sampai penyimpanan pulih.")
        }
        cachedToken = fresh
        return fresh
    }

    /** Putar token baru (dipakai bila token dicurigai bocor). Token lama
     *  langsung tidak berlaku; file token proot ditulis ulang. */
    // vc261 N-M6: kembalikan Pair(token, sinkronOk) — pemanggil WAJIB
    // menampilkan pesan jujur bila file proot gagal ditulis (dulu:
    // nilai balik syncTokenFile dibuang + panel klaim sukses unconditional).
    fun rotateToken(ctx: Context): Pair<String, Boolean> {
        val fresh = newTokenString()
        try { SecureStore.put(ctx, TOKEN_KEY, fresh) } catch (e: Exception) {}
        // vc260 N-m12 (MERGE vc261): verifikasi simpan — bila gagal, cache
        // TIDAK diganti (token lama tetap berlaku) dan rotasi dilaporkan gagal.
        val savedRotateVc260 = try { SecureStore.get(ctx, TOKEN_KEY) } catch (e: Exception) { null }
        if (savedRotateVc260 != fresh) {
            try { android.util.Log.w("WorkerEp", "rotateToken() gagal menyimpan token baru ke SecureStore — token lama tetap berlaku") } catch (ignored: Exception) {}
            throw IllegalStateException("Rotasi token gagal disimpan ke SecureStore — token lama tetap berlaku.")
        }
        // vc259 — cache langsung ke token baru: token lama langsung mati,
        // token baru langsung berlaku tanpa menunggu baca SecureStore.
        cachedToken = fresh
        val ok = syncTokenFileOk(ctx)
        return Pair(fresh, ok)
    }

    /** Tulis token ke file di dalam proot (0600) supaya worker opencode
     *  bisa membacanya — tanpa file ini opencode tidak bisa memanggil
     *  endpoint (401). File TIDAK ada di /sdcard, TIDAK di chat. */
    // vc261 N-M6: inti sinkronisasi mengembalikan Boolean (dipakai
    // rotateToken + panel); wrapper pesan di bawah untuk kompatibilitas.
    // vc284 N4-M2: kunci HMAC + verifier TIDAK LAGI ditulis ke proot —
    // kunci tinggal di SecureStore dan verifikasi tugas pindah ke sisi
    // server (rute verifyTask). File kunci + verifier lama DIHAPUS dari
    // proot di perintah yang sama (worker mana pun yang bisa baca rootfs
    // bersama bisa memalsukan VALID bila kunci tinggal di sana). Kunci
    // juga dirotasi satu kali (flag prefs) supaya salinan kunci yang
    // sempat dibaca worker sebelum vc284 ikut mati; tugas yang sudah
    // antre dengan tanda tangan kunci lama akan dilaporkan TIDAK VALID
    // oleh verify-task sesudah rotasi — enqueue ulang bila perlu.
    // Token master/per-worker TETAP ditulis ke proot: itu cara worker
    // memanggil endpoint (residu N-M4 yang didokumentasikan di header).
    fun syncTokenFileOk(ctx: Context): Boolean {
        try { rotateQueueKeyOnceVc284(ctx) } catch (e: Exception) {
            try { android.util.Log.w("WorkerEp", "rotasi kunci antrean vc284 gagal (lanjut tanpa rotasi): ${e.message}") } catch (ignored: Exception) {}
        }
        val tok = token(ctx)
        val b64 = Base64.encodeToString(tok.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val cmd = "mkdir -p /root/.harness && printf '%s' '$b64' | base64 -d > $TOKEN_FILE_PROOT && chmod 600 $TOKEN_FILE_PROOT && rm -f /root/.harness/worker-queue-key /root/.harness/verify-queue.py && echo TOKEN_FILE_OK"
        val out = try { ProotSession.execServe(ctx, cmd, 60) } catch (e: Exception) { "error: ${e.message}" }
        return out.contains("TOKEN_FILE_OK")
    }

    /** vc284 N4-M2: rotasi kunci HMAC antrean SATU KALI (flag prefs) —
     *  kunci lama sempat tinggal di /root/.harness/ (bisa dibaca worker
     *  mana pun) jadi salinannya harus dimatikan. Gagal simpan (cek
     *  baca-ulang, pola token() vc260 N-m12) = throw, flag tidak di-set,
     *  rotasi dicoba lagi di sinkronisasi berikut. */
    private fun rotateQueueKeyOnceVc284(ctx: Context) {
        val prefs = ctx.getSharedPreferences("harness", Context.MODE_PRIVATE)
        if (prefs.getBoolean("workerQueueKeyRotatedVc284", false)) return
        val fresh = newTokenString()
        try { SecureStore.put(ctx, "workerQueueHmacKey", fresh) } catch (e: Exception) {}
        val saved = try { SecureStore.get(ctx, "workerQueueHmacKey") } catch (e: Exception) { null }
        if (saved != fresh) throw IllegalStateException("rotasi kunci HMAC gagal disimpan ke SecureStore")
        prefs.edit().putBoolean("workerQueueKeyRotatedVc284", true).apply()
    }

    fun syncTokenFile(ctx: Context): String {
        return if (syncTokenFileOk(ctx)) "File token proot tertulis ($TOKEN_FILE_PROOT, 0600)."
        else "File token proot BELUM tertulis (proot/serve mati?) — opencode belum bisa baca token sampai proot hidup, lalu tekan 'Sinkron Token' di panel Worker."
    }

    // vc259 — pemanasan sekali saat start: baca token (isi cache) + satu
    // akses ringan WorkerRegistry supaya SecureStore + SQLite sudah hangat
    // sebelum request pertama datang. Gagal (exception) TIDAK BOLEH
    // menggagalkan start — endpoint tetap HIDUP, request pertama tinggal
    // bayar dingin seperti biasa.
    // vc260 N-M1: dibuka aksesnya (dulu private) supaya tick 5 menit ServeStandbyService bisa memanaskan token + SQLite berkala, bukan cuma saat start.
    fun warmUp(ctx: Context) {
        try { token(ctx) } catch (e: Exception) {}
        try { WorkerRegistry.list(ctx) } catch (e: Exception) {}
    }

    // Token yang dipakai untuk auth request: dari cache bila ada, bila
    // belum ada baca SecureStore sekali lalu simpan ke cache.
    private fun expectedToken(ctx: Context): String {
        cachedToken?.takeIf { it.isNotBlank() }?.let { return it }
        val tok = try { SecureStore.get(ctx, TOKEN_KEY) } catch (e: Exception) { null } ?: ""
        if (tok.isNotBlank()) cachedToken = tok
        return tok
    }

    // vc261 N-M4/N-M5: token per-worker + queue key HMAC + otorisasi
    // ber-scope + jejak audit (N-m15). Token TIDAK PERNAH ditulis ke log.
    fun newTokenString(): String {
        val buf = ByteArray(32)
        SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun workerTokenKey(id: String): String = "workerEndpointToken_$id"
    fun workerTokenFile(id: String): String = "/root/.harness/worker-endpoint-token-$id"

    // Token milik satu worker: dibuat saat spawn sukses, disimpan di
    // SecureStore + file proot /root/.harness/worker-endpoint-token-<id>.
    fun workerToken(ctx: Context, id: String): String {
        val key = workerTokenKey(id)
        val existing = try { SecureStore.get(ctx, key) } catch (e: Exception) { null }
        if (!existing.isNullOrBlank()) return existing
        val fresh = newTokenString()
        try { SecureStore.put(ctx, key, fresh) } catch (e: Exception) {}
        // vc265 N2-m1: pola gagal-simpan-diam-diam DITUTUP (sama seperti
        // token() vc260 N-m12) — verifikasi simpan dengan baca-ulang.
        // Bila tidak tersimpan, token TIDAK dikembalikan seolah sukses
        // (itu dulu bikin 401 permanen: file token proot ditolak endpoint
        // sementara panel mengklaim token sudah dibuat).
        val saved = try { SecureStore.get(ctx, key) } catch (e: Exception) { null }
        if (saved != fresh) {
            try { android.util.Log.w("WorkerEp", "workerToken() gagal menyimpan token worker $id ke SecureStore — token tidak dikembalikan") } catch (ignored: Exception) {}
            throw IllegalStateException("Token worker '$id' gagal disimpan ke SecureStore — worker belum bisa diautentikasi sampai penyimpanan pulih.")
        }
        return fresh
    }

    // Kunci HMAC antrean (N-M5): dibuat sekali, disimpan di SecureStore,
    // TIDAK PERNAH keluar dari app sejak vc284 (N4-M2) — dulu disinkron
    // ke proot sebagai file 0600, tapi rootfs bersama membuat kunci itu
    // bisa dibaca worker mana pun. Verifikasi tugas kini sisi server
    // (rute verifyTask); hasil yang ditulis agen lewat endpoint tetap
    // ditandatangani app-side dengan kunci ini.
    fun queueKey(ctx: Context): String {
        val existing = try { SecureStore.get(ctx, "workerQueueHmacKey") } catch (e: Exception) { null }
        if (!existing.isNullOrBlank()) return existing
        val fresh = newTokenString()
        try { SecureStore.put(ctx, "workerQueueHmacKey", fresh) } catch (e: Exception) {}
        // vc265 N2-m1: verifikasi simpan dengan baca-ulang (pola token()
        // vc260 N-m12). Bila tidak tersimpan, kunci TIDAK dikembalikan
        // seolah sukses — kunci desinkron dulu bikin hasil sah DITOLAK
        // diam-diam sebagai "HMAC tidak valid". Pemanggil (syncTokenFileOk
        // / writeTaskResult / collectResults) menangkap exception ini dan
        // melaporkannya secara jujur, bukan memakai kunci yang belum
        // terverifikasi.
        val saved = try { SecureStore.get(ctx, "workerQueueHmacKey") } catch (e: Exception) { null }
        if (saved != fresh) {
            try { android.util.Log.w("WorkerEp", "queueKey() gagal menyimpan kunci HMAC antrean ke SecureStore — kunci tidak dikembalikan") } catch (ignored: Exception) {}
            throw IllegalStateException("Kunci HMAC antrean gagal disimpan ke SecureStore — hasil worker belum bisa ditandatangani/diverifikasi sampai penyimpanan pulih.")
        }
        return fresh
    }

    fun syncWorkerTokenFile(ctx: Context, id: String): Boolean {
        val tok = workerToken(ctx, id)
        val b64 = Base64.encodeToString(tok.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val path = workerTokenFile(id)
        val cmd = "mkdir -p /root/.harness && printf '%s' '$b64' | base64 -d > $path && chmod 600 $path && echo WORKER_TOKEN_OK"
        val out = try { ProotSession.execServe(ctx, cmd, 60) } catch (e: Exception) { "" }
        return out.contains("WORKER_TOKEN_OK")
    }

    fun deleteWorkerToken(ctx: Context, id: String) {
        try { SecureStore.delete(ctx, workerTokenKey(id)) } catch (e: Exception) {}
        try { ProotSession.execServe(ctx, "rm -f ${workerTokenFile(id)} && echo WORKER_TOKEN_RM_OK", 30) } catch (e: Exception) {}
    }

    // Otorisasi ber-scope: token master berlaku untuk semua rute; token
    // per-worker HANYA untuk rute ber-workerId itu sendiri dan HANYA aksi
    // di WORKER_SCOPED_ACTIONS. Token per-worker TIDAK bisa list/spawn/
    // antrean Operator/setQuota. RESIDU (N-M4): worker root masih bisa membaca
    // file token worker lain di proot yang sama — batas ini di endpoint,
    // bukan di filesystem (isolasi penuh = Fase 3).
    fun authorizeFor(ctx: Context, tok: String?, route: WorkerEndpointLogic.Route): Boolean {
        if (WorkerEndpointLogic.isAuthorized(tok, expectedToken(ctx))) return true
        if (tok.isNullOrBlank()) return false
        if (route.workerId.isBlank()) return false
        if (route.action !in WorkerEndpointLogic.WORKER_SCOPED_ACTIONS) return false
        val expected = try { SecureStore.get(ctx, workerTokenKey(route.workerId)) } catch (e: Exception) { null } ?: return false
        return WorkerEndpointLogic.isAuthorized(tok, expected)
    }

    // vc261 N-m15: jejak audit endpoint ke EventDb (auth gagal +
    // spawn/stop/restart/setQuota). TIDAK PERNAH mencatat token/isi body.
    fun audit(ctx: Context, event: String) {
        try { EventDb.get(ctx).add("endpoint", "worker-endpoint", event.take(500)) } catch (e: Exception) {}
    }

    fun status(ctx: Context): String {
        val run = if (isRunning()) "HIDUP :4200 (127.0.0.1)" else "MATI"
        val hasTok = try { SecureStore.has(ctx, TOKEN_KEY) } catch (e: Exception) { false }
        // vc260 N-m12: has() hanya cek keberadaan key di prefs — verifikasi
        // keterbacaan (decrypt) supaya panel tidak mengklaim "tersimpan"
        // padahal blob rusak dan get() mengembalikan null.
        val readableVc260 = if (!hasTok) false else try { !SecureStore.get(ctx, TOKEN_KEY).isNullOrBlank() } catch (e: Exception) { false }
        val tokText = when {
            !hasTok -> "belum dibuat"
            readableVc260 -> "tersimpan di SecureStore (tidak ditampilkan)"
            else -> "ADA di SecureStore tapi TIDAK BISA DIBACA (blob rusak?) — tekan Putar Token untuk membuat token baru"
        }
        return "Endpoint worker :4200: $run • Token: $tokText • File token proot: $TOKEN_FILE_PROOT"
    }

    fun start(ctx: Context): String {
        synchronized(this) {
            if (isRunning()) return "Endpoint :4200 sudah jalan."
            val srv = try { ServerSocket(PORT, 0, InetAddress.getByName("127.0.0.1")) }
                catch (e: Exception) { return "Endpoint :4200 gagal start: ${e.message} (port dipakai?)" }
            server = srv
            val t = Thread({ acceptLoop(ctx) }, "worker-endpoint-4200")
            t.isDaemon = true
            t.start()
            acceptThread = t
        }
        warmUp(ctx)
        val sync = try { syncTokenFile(ctx) } catch (e: Exception) { "File token proot BELUM tertulis: ${e.message}" }
        return "Endpoint worker :4200 HIDUP (127.0.0.1 saja, auth token). $sync"
    }

    fun ensureStarted(ctx: Context): String {
        if (isRunning()) return "Endpoint :4200 sudah jalan."
        return start(ctx)
    }

    fun stop(): String {
        synchronized(this) {
            try { server?.close() } catch (e: Exception) {}
            server = null
            acceptThread = null
        }
        return "Endpoint :4200 di-stop."
    }

    private fun acceptLoop(ctx: Context) {
        // vc260 N-M2: accept() throw TIDAK BOLEH lagi return diam-diam
        // (socket tetap terbuka, isRunning() tetap true, tick no-op).
        // Error transient -> continue (jeda 200 ms supaya tidak hot-loop);
        // stop() yang menutup socket -> return; 10 error beruntun ->
        // socket ditutup + server=null supaya isRunning() jadi false dan
        // tick ServeStandbyService me-restart endpoint.
        var consecutiveErrors = 0
        while (true) {
            val srv = server ?: return
            val sock: Socket = try {
                val s = srv.accept()
                consecutiveErrors = 0
                s
            } catch (e: Exception) {
                if (server == null || srv.isClosed) return
                consecutiveErrors++
                try { android.util.Log.w("WorkerEp", "accept() gagal ($consecutiveErrors): ${e.message}") } catch (ignored: Exception) {}
                if (consecutiveErrors >= 10) {
                    try { srv.close() } catch (ignored: Exception) {}
                    synchronized(this) { if (server === srv) server = null }
                    try { android.util.Log.e("WorkerEp", "acceptLoop fatal: socket ditutup setelah $consecutiveErrors error beruntun — isRunning()=false, tick akan me-restart endpoint") } catch (ignored: Exception) {}
                    return
                }
                try { Thread.sleep(200) } catch (ignored: Exception) {}
                continue
            }
            Thread({ handleConn(ctx, sock) }, "worker-ep-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handleConn(ctx: Context, sock: Socket) {
        // vc260 N-M1: log waktu-per-tahap per request (Logcat tag WorkerEp) — tanpa token, tanpa isi body.
        val tAccept = System.currentTimeMillis()
        var routeLabel = "?"
        var codeOut = 0
        var authMs = 0L
        var dispatchMs = 0L
        try {
            sock.soTimeout = 30000
            val input = sock.getInputStream()
            val headers = readHeaders(input) ?: return
            // vc261 N-m16: header >16 KB dijawab 431, bukan putus diam-diam.
            if (headers.first == "HEADER_TOO_LARGE") { codeOut = 431; sendJson(sock, 431, errJson("header terlalu besar (maks 16 KB)")); return }
            val reqLine = headers.first
            val hmap = headers.second
            val parts = reqLine.split(" ")
            if (parts.size < 2) { codeOut = 400; sendJson(sock, 400, errJson("bad request")); return }
            val method = parts[0].uppercase()
            val rawPath = parts[1]
            // vc261 N-m17: Content-Length diparse sebagai Long (nilai
            // >2^31 dulu jadi null -> 0 dan lolos batas 413); chunked
            // DIDUKUNG via readChunkedBody (dulu: body kosong -> 400
            // membingungkan). Keduanya tetap dibatasi 1 MB.
            val body: String
            val chunked = hmap["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
            if (chunked) {
                val chunkedBody = readChunkedBody(input)
                if (chunkedBody == null) { codeOut = 400; sendJson(sock, 400, errJson("chunked body tidak valid atau terlalu besar (maks 1 MB)")); return }
                body = chunkedBody
            } else {
                val contentLength = hmap["content-length"]?.toLongOrNull() ?: 0L
                if (contentLength < 0L) { codeOut = 400; sendJson(sock, 400, errJson("content-length tidak valid")); return }
                if (contentLength > WorkerEndpointLogic.MAX_BODY_BYTES) { codeOut = 413; sendJson(sock, 413, errJson("body terlalu besar (maks 1 MB)")); return }
                body = readBody(input, contentLength.toInt())
            }
            val route = WorkerEndpointLogic.parseRoute(method, rawPath)
            if (route == null) {
                // vc261 N-m2: rute ada tapi method salah -> 405.
                if (WorkerEndpointLogic.pathMatchesOtherMethod(method, rawPath)) { codeOut = 405; sendJson(sock, 405, errJson("method tidak diizinkan untuk rute ini")); return }
                codeOut = 404; sendJson(sock, 404, errJson("rute tidak dikenal")); return
            }
            routeLabel = method + " " + rawPath.substringBefore("?")
            val tAuth0 = System.currentTimeMillis()
            if (route.action != "health") {
                val tok = WorkerEndpointLogic.bearerToken(hmap["authorization"])
                // vc261 N-M4: otorisasi ber-scope (master / per-worker).
                if (!authorizeFor(ctx, tok, route)) {
                    // vc260 N-M1: baca ulang token master sekali dari SecureStore sebelum menolak
                    // (menangani rotasi eksternal yang tidak lewat rotateToken()).
                    val reread = try { SecureStore.get(ctx, TOKEN_KEY) } catch (e: Exception) { null } ?: ""
                    if (reread.isNotBlank() && reread != expectedToken(ctx)) { cachedToken = reread }
                    if (!authorizeFor(ctx, tok, route)) {
                        audit(ctx, "auth gagal: $method $rawPath")
                        codeOut = 401; sendJson(sock, 401, errJson("unauthorized — token Bearer salah / tidak ada")); return
                    }
                }
            }
            authMs = System.currentTimeMillis() - tAuth0
            val query = WorkerEndpointLogic.parseQuery(rawPath)
            val tDispatch0 = System.currentTimeMillis()
            // vc265 N2-M3: submit idempoten — hasil sukses yang sudah ada
            // dikembalikan langsung; dispatch identik yang masih jalan
            // dipakai bersama; hanya bila belum ada keduanya dispatch baru
            // di-submit (lihat idempotentFuture).
            val idemKey = idempotencyKey(route, hmap, body)
            val (storedResult, future) = idempotentFuture(ctx, route, query, body, idemKey)
            if (storedResult != null) {
                dispatchMs = System.currentTimeMillis() - tDispatch0
                codeOut = storedResult.first
                sendJson(sock, storedResult.first, storedResult.second)
                return
            }
            val fut = future!!
            val (code, obj) = try {
                fut.get(DISPATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // vc265 N2-M3: future idempoten TIDAK BOLEH di-cancel —
                // retry lain mungkin sedang menunggu hasil yang sama, dan
                // wrapper idempotentFuture menyimpan hasilnya saat dispatch
                // latar selesai. Hanya future non-idempoten yang di-cancel
                // (perilaku lama — dispatch latar tetap jalan).
                if (idemKey == null) fut.cancel(true)
                try { android.util.Log.w("WorkerEp", "dispatch timeout route=$routeLabel setelah ${DISPATCH_TIMEOUT_MS}ms — dijawab 503, dispatch latar tetap jalan") } catch (ignored: Exception) {}
                codeOut = 503
                sendJson(sock, 503, errJson("endpoint sibuk — sesi proot sedang dipakai perintah lain; coba lagi beberapa detik (retry dengan Idempotency-Key / isi yang sama tidak membuat duplikat)"), retryAfterSec = 5)
                return
            }
            dispatchMs = System.currentTimeMillis() - tDispatch0
            codeOut = code
            sendJson(sock, code, obj)
        } catch (e: Exception) {
            try { sendJson(sock, 500, errJson("error: ${e.message}")) } catch (ignored: Exception) {}
            if (codeOut == 0) codeOut = 500
        } finally {
            try { android.util.Log.d("WorkerEp", "req route=$routeLabel code=$codeOut totalMs=${System.currentTimeMillis() - tAccept} authMs=$authMs dispatchMs=$dispatchMs") } catch (ignored: Exception) {}
            try { sock.close() } catch (e: Exception) {}
        }
    }

    /** Baca header HTTP byte-per-byte sampai CRLFCRLF (batas 16 KB).
     *  Sengaja tidak pakai BufferedReader: reader bisa menelan byte
     *  body (buffer 8 KB) sehingga Content-Length jadi tidak akurat. */
    private fun readHeaders(input: InputStream): Pair<String, Map<String, String>>? {
        val headerBytes = ByteArrayOutputStream()
        var prev3 = -1
        var prev2 = -1
        var prev = -1
        var count = 0
        while (true) {
            val c = input.read()
            if (c == -1) return null
            headerBytes.write(c)
            count++
            if (count > 16384) return Pair("HEADER_TOO_LARGE", emptyMap())
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev == '\r'.code && c == '\n'.code) break
            prev3 = prev2
            prev2 = prev
            prev = c
        }
        val text = headerBytes.toString(Charsets.UTF_8)
        val lines = text.split("\r\n")
        val reqLine = lines.firstOrNull() ?: return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val idx = l.indexOf(":")
            if (idx > 0) headers[l.substring(0, idx).trim().lowercase()] = l.substring(idx + 1).trim()
        }
        return Pair(reqLine, headers)
    }

    // vc261 N-m17: baca body Transfer-Encoding chunked (RFC 9112
    // §7.1): ukuran hex per chunk, trailer diabaikan, total maks 1 MB.
    // Kembalikan null bila format tidak valid / melebihi batas.
    private fun readChunkedBody(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLineRaw(input) ?: return null
            val sizeStr = sizeLine.trim().substringBefore(";")
            val size = try { sizeStr.toLong(16) } catch (e: Exception) { return null }
            if (size < 0L) return null
            if (size == 0L) {
                readLineRaw(input)
                break
            }
            if (out.size() + size > WorkerEndpointLogic.MAX_BODY_BYTES) return null
            var remaining = size
            val buf = ByteArray(4096)
            while (remaining > 0) {
                val r = input.read(buf, 0, minOf(buf.size, remaining.toInt()))
                if (r <= 0) return null
                out.write(buf, 0, r)
                remaining -= r
            }
            readLineRaw(input) ?: return null
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun readLineRaw(input: InputStream): String? {
        val sb = StringBuilder()
        var count = 0
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            count++
            if (count > 16384) return null
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var remaining = length
        while (remaining > 0) {
            val r = input.read(buf, 0, minOf(buf.size, remaining))
            if (r <= 0) break
            out.write(buf, 0, r)
            remaining -= r
        }
        return out.toString(Charsets.UTF_8)
    }

    /** Folder (kanonis, cermin) untuk worker id — "operator" = antrean Operator
     *  (bukan worker registry). Null bila worker tidak ada. */
    private fun dirsFor(ctx: Context, workerId: String): Pair<File, File>? {
        if (workerId == "operator") return Pair(WorkerRegistry.operatorCanonicalDir(ctx), WorkerRegistry.operatorMirrorDir())
        val w = WorkerRegistry.get(ctx, workerId) ?: return null
        return Pair(WorkerRegistry.canonicalDir(ctx, w.id), WorkerRegistry.mirrorDir(w.id))
    }

    private fun bodyText(body: String): String {
        return try { JSONObject(body).optString("text") } catch (e: Exception) { "" }
    }

    // vc265 N2-M3: helper idempotensi (lihat IDEM_TTL_MS di atas).
    private fun sha256Hex16(text: String): String {
        return try {
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (i in 0 until 8) sb.append(String.format("%02x", d[i].toInt() and 0xff))
            sb.toString()
        } catch (e: Exception) { text.hashCode().toString() }
    }

    /** Kunci idempotensi satu permintaan, atau null bila aksi ini tidak
     *  butuh idempotensi (hanya spawn + enqueue yang non-idempotent).
     *  Header Idempotency-Key klien didahulukan; tanpa header, kunci
     *  diturunkan dari isi permintaan supaya retry sesudah 503 yang
     *  tidak membawa header pun tetap ter-dedup. */
    private fun idempotencyKey(route: WorkerEndpointLogic.Route, headers: Map<String, String>, body: String): String? {
        val hdr = headers["idempotency-key"]?.trim()
        if (!hdr.isNullOrBlank()) return "hdr:" + hdr.take(128)
        return when (route.action) {
            "spawnWorker" -> {
                val j = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                val name = j.optString("name").trim().lowercase()
                if (name.isBlank()) return null
                val kind = j.optString("kind", "proot")
                val session = j.optString("session", "worker1")
                val content = if (kind == "python") j.optString("code") else j.optString("command")
                "spawn:$name|$kind|$session|" + sha256Hex16(content)
            }
            "enqueueTask" -> {
                val text = bodyText(body)
                if (text.isBlank()) null else "enqueue:${route.workerId}|" + sha256Hex16(text)
            }
            "operatorEnqueue" -> {
                val text = bodyText(body)
                if (text.isBlank()) null else "enqueue:operator|" + sha256Hex16(text)
            }
            else -> null
        }
    }

    /** WAJIB dipanggil dengan idemLock dipegang. Buang entri hasil yang
     *  kedaluwarsa + batasi jumlah entri supaya peta tidak tumbuh tanpa
     *  batas bila klien mengirim kunci unik terus-menerus. */
    private fun pruneIdemLocked() {
        val now = System.currentTimeMillis()
        val it = idemDone.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.first > IDEM_TTL_MS) it.remove()
        }
        if (idemDone.size > IDEM_MAX_ENTRIES) {
            val sorted = idemDone.entries.sortedBy { it.value.first }
            for (e in sorted.take(idemDone.size - 400)) idemDone.remove(e.key)
        }
    }

    /** Submit dispatch secara idempoten. Kembalikan (hasil-tersimpan,
     *  future): bila hasil sukses sudah ada -> (hasil, null); bila
     *  dispatch identik sedang jalan -> (null, future yang sama);
     *  bila belum ada -> (null, future dispatch baru). Wrapper
     *  menyimpan hasil sukses (200) saat dispatch selesai + selalu
     *  membersihkan entri in-flight (finally) supaya future gagal tidak
     *  menggantung selamanya. */
    private fun idempotentFuture(ctx: Context, route: WorkerEndpointLogic.Route, query: Map<String, String>, body: String, idemKey: String?): Pair<Pair<Int, JSONObject>?, java.util.concurrent.Future<Pair<Int, JSONObject>>?> {
        if (idemKey == null) {
            return Pair(null, dispatchPool.submit<Pair<Int, JSONObject>> { dispatch(ctx, route, query, body) })
        }
        synchronized(idemLock) {
            pruneIdemLocked()
            val done = idemDone[idemKey]
            if (done != null) return Pair(done.second, null)
            val infl = inFlight[idemKey]
            if (infl != null) return Pair(null, infl)
            val f = dispatchPool.submit<Pair<Int, JSONObject>> {
                try {
                    val res = dispatch(ctx, route, query, body)
                    // Hanya sukses yang disimpan: gagal transient harus
                    // bisa dicoba ulang segar oleh retry berikutnya.
                    if (res.first == 200) synchronized(idemLock) { idemDone[idemKey] = System.currentTimeMillis() to res }
                    res
                } finally {
                    synchronized(idemLock) { inFlight.remove(idemKey) }
                }
            }
            inFlight[idemKey] = f
            return Pair(null, f)
        }
    }

    /** vc265 N2-M3: jaring pengaman kedua untuk spawn duplikat — bila
     *  kunci idempotensi sudah kedaluwarsa (10 mnt) tapi worker RUNNING
     *  dengan nama+jenis+sesi+isi identik sudah ada, kembalikan worker
     *  itu (200), bukan spawn kedua. Nama sama tapi isi beda BUKAN
     *  duplikat (validateSpawn menolaknya 409 — lihat WorkerLogic).
     *  Python dibanding lewat isi script.py di cermin (command worker
     *  Python memuat id, jadi tidak bisa dibanding langsung). */
    private fun findDuplicateRunningWorker(ctx: Context, name: String, kind: String, session: String, bodyJson: JSONObject): WorkerRegistry.Worker? {
        val target = name.trim()
        if (target.isBlank()) return null
        val content = if (kind == "python") bodyJson.optString("code") else bodyJson.optString("command")
        for (w in WorkerRegistry.list(ctx)) {
            if (w.status != "RUNNING") continue
            if (!w.name.equals(target, ignoreCase = true)) continue
            if (w.kind != kind || w.session != session) continue
            if (kind == "python") {
                val saved = try { File(WorkerRegistry.mirrorDir(w.id), "script.py").readText(Charsets.UTF_8) } catch (e: Exception) { null } ?: continue
                if (saved != content) continue
            } else {
                if (w.command != content) continue
            }
            return w
        }
        return null
    }

    private fun dispatch(ctx: Context, route: WorkerEndpointLogic.Route, query: Map<String, String>, body: String): Pair<Int, JSONObject> {
        return try {
            when (route.action) {
                "health" -> Pair(200, JSONObject().put("ok", true).put("port", PORT).put("endpoint", "worker"))
                "listWorkers" -> {
                    // vc261 N-M8: segarkan status RUNNING basi sebelum
                    // menjawab (throttled 30 detik per worker di dalam).
                    try { WorkerRegistry.refreshRunningStatus(ctx) } catch (e: Exception) {}
                    val arr = JSONArray()
                    for (w in WorkerRegistry.list(ctx)) arr.put(workerJson(ctx, w))
                    Pair(200, JSONObject().put("ok", true).put("workers", arr))
                }
                "spawnWorker" -> {
                    val j = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                    val name = j.optString("name")
                    val kind = j.optString("kind", "proot")
                    val session = j.optString("session", "worker1")
                    // vc261 N-m4: kind tak dikenal DITOLAK 400 (dulu diam-
                    // diam jadi proot).
                    if (kind != "proot" && kind != "python") return Pair(400, errJson("jenis worker tidak dikenal: '$kind' (proot/python)"))
                    // vc261 N-M8: segarkan status sebelum cek maks-5.
                    try { WorkerRegistry.refreshRunningStatus(ctx) } catch (e: Exception) {}
                    // vc265 N2-M3: worker RUNNING identik sudah ada (retry
                    // sesudah kunci idempotensi kedaluwarsa) -> 200 +
                    // worker itu, bukan spawn kedua.
                    val dupWorker = findDuplicateRunningWorker(ctx, name, kind, session, j)
                    if (dupWorker != null) {
                        audit(ctx, "spawn duplikat dikembalikan: worker=${dupWorker.id} kind=$kind")
                        return Pair(200, JSONObject().put("ok", true).put("message", "Worker '${dupWorker.name}' sudah JALAN (spawn duplikat dikembalikan, bukan dibuat baru).").put("worker_id", dupWorker.id))
                    }
                    val res = when (kind) {
                        "python" -> WorkerRegistry.spawnPythonOp(ctx, name, session, j.optString("code"), "endpoint")
                        else -> WorkerRegistry.spawnProotOp(ctx, name, session, j.optString("command"), "endpoint")
                    }
                    if (res.ok) {
                        // vc261 N-M4: token per-worker dibuat + ditulis ke
                        // proot saat spawn sukses (best-effort, jujur).
                        val synced = try { syncWorkerTokenFile(ctx, res.workerId) } catch (e: Exception) { false }
                        audit(ctx, "spawn ok: worker=${res.workerId} kind=$kind")
                        val extra = if (synced) " Token per-worker ditulis ke proot." else " Token per-worker GAGAL ditulis ke proot — worker belum bisa memanggil endpoint dengan token sendiri sampai proot hidup."
                        Pair(200, JSONObject().put("ok", true).put("message", res.message + extra).put("worker_id", res.workerId))
                    } else {
                        audit(ctx, "spawn gagal: kind=$kind ${res.message.take(120)}")
                        Pair(res.httpCode, errJson(res.message))
                    }
                }
                "workerDetail" -> {
                    val w = WorkerRegistry.get(ctx, route.workerId) ?: return Pair(404, errJson("worker tidak ditemukan"))
                    Pair(200, JSONObject().put("ok", true).put("worker", workerJson(ctx, w)))
                }
                "stopWorker" -> {
                    val res = WorkerRegistry.stopWorkerOp(ctx, route.workerId)
                    if (res.ok) {
                        try { deleteWorkerToken(ctx, route.workerId) } catch (e: Exception) {}
                        audit(ctx, "stop ok: worker=${route.workerId}")
                        Pair(200, JSONObject().put("ok", true).put("message", res.message))
                    } else {
                        audit(ctx, "stop gagal: worker=${route.workerId} ${res.message.take(120)}")
                        Pair(res.httpCode, errJson(res.message))
                    }
                }
                "restartWorker" -> {
                    val res = WorkerRegistry.restartWorkerOp(ctx, route.workerId)
                    if (res.ok) audit(ctx, "restart ok: worker=${route.workerId}")
                    else audit(ctx, "restart gagal: worker=${route.workerId} ${res.message.take(120)}")
                    if (res.ok) Pair(200, JSONObject().put("ok", true).put("message", res.message)) else Pair(res.httpCode, errJson(res.message))
                }
                // vc281 Fase 3 (D6): reset worker (token MASTER saja —
                // resetWorker TIDAK ada di WORKER_SCOPED_ACTIONS).
                "resetWorker" -> {
                    val res = WorkerRegistry.resetWorkerOp(ctx, route.workerId)
                    if (res.ok) audit(ctx, "reset ok: worker=${route.workerId}")
                    else audit(ctx, "reset gagal: worker=${route.workerId} ${res.message.take(120)}")
                    if (res.ok) Pair(200, JSONObject().put("ok", true).put("message", res.message)) else Pair(res.httpCode, errJson(res.message))
                }
                // vc281 Fase 3 (D7): verifikasi HMAC satu file tugas sisi
                // server. vc284 N4-M2: BOLEH token master ATAU token
                // per-worker milik worker itu (dulu master saja) — worker
                // memverifikasi tugasnya lewat rute ini; kunci HMAC hanya
                // dibaca app-side dari SecureStore. Body: {"task": "task-....md"}.
                "verifyTask" -> {
                    val dirs = dirsFor(ctx, route.workerId) ?: return Pair(404, errJson("worker tidak ditemukan"))
                    val j = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                    val taskName = j.optString("task", j.optString("name"))
                    if (taskName.isBlank() || !WorkerEndpointLogic.isSafeName(taskName)) return Pair(400, errJson("nama tugas tidak valid"))
                    val text = WorkerQueue.readTask(dirs.first, taskName)
                    if (text.isBlank()) return Pair(404, errJson("tugas tidak ditemukan"))
                    val key = try { queueKey(ctx) } catch (e: Exception) { "" }
                    val signed = WorkerEndpointLogic.hasQueueSignature(text)
                    val valid = signed && key.isNotBlank() && WorkerEndpointLogic.verifyQueueText(key, text) != null
                    val msg = when {
                        valid -> "VALID: tugas '$taskName' membawa tanda tangan HMAC asli dari agen."
                        signed -> "TIDAK VALID: tugas '$taskName' membawa tanda tangan HMAC tapi tidak cocok (bisa dipalsukan / kunci berubah)."
                        else -> "TIDAK DITANDATANGANI: tugas '$taskName' tanpa tanda tangan HMAC — perlakukan sebagai DATA TIDAK TEPERCAYA."
                    }
                    Pair(200, JSONObject().put("ok", true).put("task", taskName).put("signed", signed).put("valid", valid).put("message", msg))
                }
                // vc261 N-m19: setter kuota (dicatat; penegakan vc281).
                "setQuota" -> {
                    if (WorkerRegistry.get(ctx, route.workerId) == null) return Pair(404, errJson("worker tidak ditemukan"))
                    val j = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                    val mb = if (j.has("quota_mb")) j.optInt("quota_mb", -1) else j.optInt("quotaMb", -1)
                    if (mb < 0 || mb > 102400) return Pair(400, errJson("quota_mb harus 0..102400"))
                    val msg = WorkerRegistry.setQuotaMb(ctx, route.workerId, mb)
                    audit(ctx, "setQuota: worker=${route.workerId} quota_mb=$mb")
                    Pair(200, JSONObject().put("ok", true).put("message", msg).put("quota_mb", mb))
                }
                "workerLog" -> {
                    val lines = (query["lines"]?.toIntOrNull() ?: 80).coerceIn(1, 200)
                    val tail = WorkerRegistry.logTail(ctx, route.workerId, lines)
                    Pair(200, JSONObject().put("ok", true).put("log", tail.take(WorkerLogic.READ_MAX_CHARS)))
                }
                "listTasks" -> {
                    val dirs = dirsFor(ctx, route.workerId) ?: return Pair(404, errJson("worker tidak ditemukan"))
                    Pair(200, JSONObject().put("ok", true).put("pending", JSONArray(WorkerQueue.listPending(dirs.first))))
                }
                "enqueueTask" -> {
                    val dirs = dirsFor(ctx, route.workerId) ?: return Pair(404, errJson("worker tidak ditemukan"))
                    val w = WorkerRegistry.get(ctx, route.workerId) ?: return Pair(404, errJson("worker tidak ditemukan"))
                    val text = bodyText(body)
                    if (text.isBlank()) return Pair(400, errJson("text kosong"))
                    if (text.length > WorkerLogic.TASK_MAX_CHARS) return Pair(413, errJson("teks tugas terlalu besar (maks 256 KB)"))
                    // vc281 Fase 3 (D3): janitor bersihkan dulu; bila folder
                    // MASIH melewati kuota, tugas ditolak 409. vc284 N4-M4:
                    // janitor kini bisa membebaskan dari file tak terdaftar
                    // juga (hasil + file kerja lama >7 hari); bila tetap
                    // over-kuota, yang tersisa hanya tugas pending +
                    // script.py + file kerja baru yang TIDAK PERNAH dihapus
                    // janitor — pesan menjelaskan cara pulih supaya 409
                    // tidak jadi jalan buntu permanen.
                    try { WorkerJanitor.sweepWorker(ctx, w) } catch (e: Exception) {}
                    val quota = WorkerJanitorLogic.quotaBytes(w.quotaMb)
                    val size = WorkerJanitor.folderSizeBytes(ctx, w.id)
                    if (size >= quota) return Pair(409, errJson("folder worker sudah melewati kuota (${WorkerJanitorLogic.formatBytes(size)} dari ${WorkerJanitorLogic.formatBytes(quota)}) — tugas ditolak; janitor sudah menghapus hasil + file kerja lama (>7 hari) yang bisa dihapus. Pulih: hapus file manual dari /sdcard/Harness-Workers/${w.id}/ atau naikkan kuota (POST /workers/${w.id}/quota) — tugas pending + script.py tidak pernah dihapus janitor."))
                    // vc281 Fase 3 (D7): tugas ditandatangani HMAC.
                    val key = try { queueKey(ctx) } catch (e: Exception) { null }
                    val name = WorkerQueue.enqueue(dirs.first, dirs.second, text, signKey = key)
                    if (name.isBlank()) Pair(500, errJson("gagal menulis tugas")) else Pair(200, JSONObject().put("ok", true).put("task", name))
                }
                "readTask" -> {
                    if (!WorkerEndpointLogic.isSafeName(route.name)) return Pair(400, errJson("nama file tidak aman"))
                    val dirs = dirsFor(ctx, route.workerId) ?: return Pair(404, errJson("worker tidak ditemukan"))
                    val text = WorkerQueue.readTask(dirs.first, route.name)
                    if (text.isBlank()) return Pair(404, errJson("tugas tidak ditemukan"))
                    val (slice, cut) = WorkerEndpointLogic.slicePreview(text, WorkerLogic.READ_MAX_CHARS)
                    Pair(200, JSONObject().put("ok", true).put("task", route.name).put("total_chars", text.length).put("truncated", cut).put("content", slice))
                }
                "writeTaskResult" -> {
                    if (!WorkerEndpointLogic.isSafeName(route.name)) return Pair(400, errJson("nama file tidak aman"))
                    val dirs = dirsFor(ctx, route.workerId) ?: return Pair(404, errJson("worker tidak ditemukan"))
                    val text = bodyText(body)
                    if (text.isBlank()) return Pair(400, errJson("text kosong"))
                    val name = WorkerQueue.writeResult(dirs.first, dirs.second, route.name, text, queueKey(ctx))
                    if (name.isBlank()) Pair(500, errJson("gagal menulis hasil")) else Pair(200, JSONObject().put("ok", true).put("result", name))
                }
                "listResults" -> {
                    val dirs = dirsFor(ctx, route.workerId) ?: return Pair(404, errJson("worker tidak ditemukan"))
                    Pair(200, JSONObject().put("ok", true).put("results", JSONArray(WorkerQueue.listResults(dirs.first))))
                }
                "readResult" -> {
                    if (!WorkerEndpointLogic.isSafeName(route.name)) return Pair(400, errJson("nama file tidak aman"))
                    val dirs = dirsFor(ctx, route.workerId) ?: return Pair(404, errJson("worker tidak ditemukan"))
                    val text = WorkerQueue.readResult(dirs.first, route.name)
                    if (text.isBlank()) return Pair(404, errJson("hasil tidak ditemukan"))
                    val max = WorkerEndpointLogic.previewChars(query["chars"]?.toIntOrNull() ?: 0)
                    val (slice, cut) = WorkerEndpointLogic.slicePreview(text, max)
                    Pair(200, JSONObject().put("ok", true).put("result", route.name).put("total_chars", text.length).put("shown_chars", slice.length).put("truncated", cut).put("preview", slice))
                }
                "operatorListTasks" -> {
                    val dirs = dirsFor(ctx, "operator")!!
                    Pair(200, JSONObject().put("ok", true).put("pending", JSONArray(WorkerQueue.listPending(dirs.first))))
                }
                "operatorEnqueue" -> {
                    val dirs = dirsFor(ctx, "operator")!!
                    val text = bodyText(body)
                    if (text.isBlank()) return Pair(400, errJson("text kosong"))
                    if (text.length > WorkerLogic.TASK_MAX_CHARS) return Pair(413, errJson("teks tugas terlalu besar (maks 256 KB)"))
                    // vc284 N4-M3: antrean Operator ikut kuota + janitor
                    // (pola yang sama dengan enqueueTask worker biasa).
                    try { WorkerJanitor.sweepOperator(ctx) } catch (e: Exception) {}
                    val operatorQuota = WorkerJanitorLogic.quotaBytes(0)
                    val operatorSize = WorkerJanitor.folderSizeBytes(ctx, "operator")
                    if (operatorSize >= operatorQuota) return Pair(409, errJson("folder antrean Operator sudah melewati kuota (${WorkerJanitorLogic.formatBytes(operatorSize)} dari ${WorkerJanitorLogic.formatBytes(operatorQuota)}) — tugas ditolak; janitor sudah menghapus hasil + file kerja lama (>7 hari) yang bisa dihapus. Pulih: hapus file manual dari /sdcard/Harness-Workers/operator/ lalu coba lagi."))
                    // vc281 Fase 3 (D7): tugas ditandatangani HMAC.
                    val key = try { queueKey(ctx) } catch (e: Exception) { null }
                    val name = WorkerQueue.enqueue(dirs.first, dirs.second, text, signKey = key)
                    if (name.isBlank()) Pair(500, errJson("gagal menulis tugas")) else Pair(200, JSONObject().put("ok", true).put("task", name))
                }
                "operatorReadTask" -> {
                    if (!WorkerEndpointLogic.isSafeName(route.name)) return Pair(400, errJson("nama file tidak aman"))
                    val dirs = dirsFor(ctx, "operator")!!
                    val text = WorkerQueue.readTask(dirs.first, route.name)
                    if (text.isBlank()) return Pair(404, errJson("tugas tidak ditemukan"))
                    val (slice, cut) = WorkerEndpointLogic.slicePreview(text, WorkerLogic.READ_MAX_CHARS)
                    Pair(200, JSONObject().put("ok", true).put("task", route.name).put("total_chars", text.length).put("truncated", cut).put("content", slice))
                }
                "operatorWriteResult" -> {
                    if (!WorkerEndpointLogic.isSafeName(route.name)) return Pair(400, errJson("nama file tidak aman"))
                    val dirs = dirsFor(ctx, "operator")!!
                    val text = bodyText(body)
                    if (text.isBlank()) return Pair(400, errJson("text kosong"))
                    val name = WorkerQueue.writeResult(dirs.first, dirs.second, route.name, text, queueKey(ctx))
                    if (name.isBlank()) Pair(500, errJson("gagal menulis hasil")) else Pair(200, JSONObject().put("ok", true).put("result", name))
                }
                "operatorListResults" -> {
                    val dirs = dirsFor(ctx, "operator")!!
                    Pair(200, JSONObject().put("ok", true).put("results", JSONArray(WorkerQueue.listResults(dirs.first))))
                }
                else -> Pair(404, errJson("rute tidak dikenal"))
            }
        } catch (e: Exception) {
            Pair(500, errJson("error: ${e.message}"))
        }
    }

    private fun workerJson(ctx: Context, w: WorkerRegistry.Worker): JSONObject {
        val disp = try { WorkerRegistry.displayStatus(ctx, w) } catch (e: Exception) { w.status }
        // vc281 Fase 3 (D3): status kuota — ukuran folder saat ini vs
        // kuota efektif + flag over-quota (dipakai panel + klien endpoint).
        val quotaEff = WorkerJanitorLogic.effectiveQuotaMb(w.quotaMb)
        val folderBytes = try { WorkerJanitor.folderSizeBytes(ctx, w.id) } catch (e: Exception) { 0L }
        val overQuota = folderBytes >= WorkerJanitorLogic.quotaBytes(w.quotaMb)
        return JSONObject()
            .put("id", w.id).put("name", w.name).put("kind", w.kind)
            .put("session", w.session).put("pid", w.pid)
            .put("status", w.status).put("display_status", disp)
            .put("work_dir", w.workDir).put("last_event", w.lastEvent)
            .put("quota_mb_set", w.quotaMb).put("quota_mb", quotaEff)
            .put("folder_bytes", folderBytes).put("over_quota", overQuota)
    }

    private fun errJson(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    private fun sendJson(sock: Socket, code: Int, obj: JSONObject, retryAfterSec: Int? = null) {
        val body = obj.toString().toByteArray(Charsets.UTF_8)
        val retryHead = if (retryAfterSec != null) "Retry-After: $retryAfterSec\r\n" else ""
        val head = "HTTP/1.1 $code ${reason(code)}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n$retryHead\r\n"
        val os = sock.getOutputStream()
        os.write(head.toByteArray(Charsets.UTF_8))
        os.write(body)
        os.flush()
    }
}
