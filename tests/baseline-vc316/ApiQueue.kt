package com.senzwh.deepharness

// vc271 O5: antrean request ganti penolakan busy. Satu antrean FIFO per
// klien (satu instance DsApiClient / satu SlotClient). Chat maks 3 menunggu,
// reviewer antrean terpisah maks 1 dan didahulukan saat slot kosong (jadi
// reviewer tidak mengantre tanpa batas di belakang chat panjang), timeout
// antre 60 detik (lewat itu ditolak "antrean penuh"). Governor TIDAK dihitung
// di sini — hitungan tetap di pintu masuk eksekusi request (di dalam lambda
// run yang dieksekusi), bukan saat masuk antre.
class ApiRequestQueue(private val owner: String) {

    companion object {
        const val MAX_CHAT_WAITING = 3
        const val MAX_REVIEWER_WAITING = 1
        const val QUEUE_TIMEOUT_MS = 60_000L
    }

    private val lock = Any()
    private var active = false
    private val chatQueue = ArrayDeque<Waiter>()
    private val reviewerQueue = ArrayDeque<Waiter>()

    private class Waiter(
        val enqueuedAt: Long,
        val run: () -> Unit,
        val onTimeout: () -> Unit,
        var expired: Boolean = false
    )

    // Return true bila run() sudah dipanggil langsung atau berhasil diantrekan.
    // Return false bila antrean penuh — pemanggil menyampaikan "antrean penuh".
    fun submit(reviewer: Boolean, run: () -> Unit, onTimeout: () -> Unit): Boolean {
        var toRun: (() -> Unit)? = null
        synchronized(lock) {
            if (!active && chatQueue.isEmpty() && reviewerQueue.isEmpty()) {
                active = true
                toRun = run
            } else {
                val q = if (reviewer) reviewerQueue else chatQueue
                val max = if (reviewer) MAX_REVIEWER_WAITING else MAX_CHAT_WAITING
                if (q.size >= max) {
                    try { TurnMetrics.recordQueueRejected() } catch (e: Exception) {}
                    return false
                }
                val w = Waiter(System.currentTimeMillis(), run, onTimeout)
                q.addLast(w)
                startExpiryWatch(w, reviewer)
            }
        }
        toRun?.invoke()
        return true
    }

    // Dipanggil klien saat satu eksekusi selesai (sukses/gagal). Menjalankan
    // waiter berikutnya di luar lock: reviewer dulu, baru chat FIFO.
    fun finish() {
        var next: Waiter? = null
        var waitedMs = 0L
        val expired = ArrayList<Waiter>()
        synchronized(lock) {
            val now = System.currentTimeMillis()
            var w = pollFresh(reviewerQueue, now, expired)
            if (w == null) w = pollFresh(chatQueue, now, expired)
            if (w == null) {
                active = false
            } else {
                next = w
                waitedMs = now - w.enqueuedAt
            }
        }
        for (e in expired) {
            try { TurnMetrics.recordQueueRejected() } catch (ex: Exception) {}
            try { e.onTimeout() } catch (ex: Exception) {}
        }
        val n = next ?: return
        try { TurnMetrics.recordQueueWait(waitedMs) } catch (e: Exception) {}
        try { n.run() } catch (e: Exception) { finish() }
    }

    private fun pollFresh(q: ArrayDeque<Waiter>, now: Long, expired: MutableList<Waiter>): Waiter? {
        while (q.isNotEmpty()) {
            val w = q.removeFirst()
            if (w.expired) continue
            if (now - w.enqueuedAt > QUEUE_TIMEOUT_MS) {
                w.expired = true
                expired.add(w)
                continue
            }
            return w
        }
        return null
    }

    private fun startExpiryWatch(w: Waiter, reviewer: Boolean) {
        Thread {
            try { Thread.sleep(QUEUE_TIMEOUT_MS) } catch (e: Exception) { return@Thread }
            var fire = false
            synchronized(lock) {
                val q = if (reviewer) reviewerQueue else chatQueue
                if (!w.expired && q.remove(w)) {
                    w.expired = true
                    fire = true
                }
            }
            if (fire) {
                try { TurnMetrics.recordQueueRejected() } catch (e: Exception) {}
                try { w.onTimeout() } catch (e: Exception) {}
            }
        }.apply { isDaemon = true; name = "vc271-queue-expiry" }.start()
    }
}
