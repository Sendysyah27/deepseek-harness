package com.senzwh.deepharness

import org.json.JSONObject

/**
 * vc335 R2 — logika MURNI (tanpa Context/Activity) untuk 4 keputusan ronde 2:
 *  D2  evict riwayat dalam-giliran (memakai SessionMessagesVc335.trimD2 —
 *      lihat tes di Vc335R2Test; tidak ada logika baru di sini),
 *  D4  ekstraksi latar lepas dari endUserTurn (gate single-flight +
 *      parse proposal dari tool call),
 *  D8  anggaran planner terpisah maks 3/giliran,
 *  D12 indeks jendela 2 giliran untuk baca hasil via ResultStore.
 *
 * Semua yang di sini bisa diuji JVM (tools/run_tests.sh). Wiring ke
 * MainActivity / Tools / ToolRegistry ada di file masing-masing.
 */

/**
 * D8 — anggaran planner TERPISAH dari plafon langkah (maxToolSteps).
 * make_plan/update_plan TIDAK LAGI agentSteps++ dan TIDAK memakan cap
 * langkah; sebagai gantinya tiap giliran hanya boleh 3 panggilan planner
 * (valid) — panggilan ke-4+ DITOLAK dengan pesan jelas (bukan diam-diam
 * dihitung ke plafon). Penolakan (validasi skema / guard T15 / index di
 * luar rentang) TIDAK memanggil tryConsume — penolakan tidak memakan
 * anggaran, sama seperti dulu penolakan tidak memakan langkah.
 *
 * Anti-loop: penolakan pertama dikembalikan sebagai [Outcome.Denied]
 * (pemanggil mengirim continuation penolakan — model masih boleh lanjut
 * dengan tool lain / jawab final); bila model TETAP memanggil planner
 * sesudah ditolak, [Outcome.DeniedRepeat] — pemanggil MENUTUP giliran
 * dengan jujur (tanpa continuation lagi, supaya tidak ada loop
 * tolak → continuation → panggil lagi tanpa batas).
 */
class PlannerBudgetVc335R2 {
    companion object {
        /** Maks panggilan planner (make_plan + update_plan) per giliran. */
        const val MAX_CALLS = 3
    }

    /** Panggilan planner valid yang sudah dipakai giliran ini. */
    var calls: Int = 0
        private set

    /** Dari [calls], berapa yang berupa make_plan (dipakai guard T15). */
    var makePlanCalls: Int = 0
        private set

    /** Berapa kali anggaran habis lalu model tetap memanggil planner. */
    var denials: Int = 0
        private set

    fun reset() {
        calls = 0
        makePlanCalls = 0
        denials = 0
    }

    sealed class Outcome {
        /** Anggaran masih ada — panggilan dicatat, jalan. */
        object Allowed : Outcome()

        /** Anggaran habis, penolakan PERTAMA giliran ini — kirim pesan
         *  penolakan ke model (continuation), giliran lanjut. */
        object Denied : Outcome()

        /** Anggaran habis DAN model sudah pernah ditolak giliran ini —
         *  JANGAN kirim continuation lagi; tutup giliran. */
        object DeniedRepeat : Outcome()
    }

    /** Pesan penolakan jelas untuk model (dipakai pemanggil saat Denied). */
    fun denialMessage(): String =
        "⛔ ANGGARAN PLANNER HABIS — giliran ini sudah memakai $MAX_CALLS dari $MAX_CALLS panggilan planner (make_plan/update_plan). " +
            "JANGAN panggil make_plan atau update_plan lagi di giliran ini. " +
            "Lanjutkan pekerjaan dengan tool lain mengikuti rencana yang sudah ada (lihat [RENCANA AKTIF] di konteks), " +
            "atau jawab final dengan jujur bila pekerjaan sudah sejauh yang bisa dilakukan."

    fun tryConsume(tool: String): Outcome {
        if (calls < MAX_CALLS) {
            calls++
            if (tool == "make_plan") makePlanCalls++
            return Outcome.Allowed
        }
        denials++
        return if (denials == 1) Outcome.Denied else Outcome.DeniedRepeat
    }

    /**
     * Guard anti-rencana-ulang T15 (dulu mati di jalur native karena
     * bersyarat `autoContinueCount > 0` — counter itu hanya naik sesudah
     * jawaban final teks, sementara make_plan ulang datang SEBELUM jawaban
     * final). Kini bersyarat counter make_plan giliran ini sendiri:
     * rencana aktif + sudah ada make_plan giliran ini = JANGAN buat ulang.
     */
    fun canRemakePlan(planActive: Boolean): Boolean =
        !(planActive && makePlanCalls > 0)
}

/**
 * D12 — indeks jendela 2 giliran untuk baca hasil tool lama via ResultStore.
 * SATU-SATUNYA penyimpanan jendela (dulu ArrayDeque di MainActivity yang
 * ditulis tapi TIDAK PERNAH dibaca siapa pun). MainActivity mendorong
 * resultId giliran lama ke sini tiap onUserMessage; Tools.execute
 * (read_previous_result) membacanya sebagai indeks — id yang tidak ada di
 * jendela DITOLAK dengan pesan jelas, bukan dibaca dari store.
 */
object PrevTurnIndexVc335R2 {
    /** Jendela harfiah 2 giliran (sama seperti vc286). */
    const val WINDOW_TURNS = 2

    private val turns = ArrayDeque<List<String>>()

    /** Sesi pemilik jendela (-1 = kosong / sesudah wipe). */
    var sessionId: Long = -1
        private set

    fun clear() {
        turns.clear()
        sessionId = -1
    }

    /**
     * Dorong resultId giliran lama (cermin onUserMessage lama):
     * giliran dari sesi lain MEMBUANG jendela (tanpa menambah); giliran
     * sesi sama menggeser antrean (maks [WINDOW_TURNS]).
     */
    fun pushTurn(ids: List<String>, turnSession: Long, currentSession: Long) {
        if (turnSession != currentSession) {
            clear()
            return
        }
        turns.addLast(ids.filter { it.isNotBlank() })
        while (turns.size > WINDOW_TURNS) turns.removeFirst()
        sessionId = turnSession
    }

    /** True bila id ada di jendela 2 giliran terakhir. */
    fun contains(id: String): Boolean {
        if (id.isBlank()) return false
        return turns.any { it.contains(id) }
    }

    /** Semua id di jendela (untuk pesan error yang menyebut id sah). */
    fun allIds(): List<String> = turns.flatten()

    /** Salinan jendela (giliran lama -> giliran baru). */
    fun snapshot(): List<List<String>> = turns.toList()
}

/**
 * D4 — parse proposal ekstraksi dari tool call model (cermin cabang
 * internalTurn di handleAgentResponse — satu definisi, dipakai job latar
 * yang lepas dari endUserTurn). remember_fact -> (key, value);
 * learn_rule -> ("aturan:<hash>", rule). Tool lain / argumen kosong /
 * JSON rusak dilewati (bukan error — model boleh menjawab TIDAK ADA).
 */
object ExtractProposalsVc335R2 {
    fun fromToolCalls(calls: List<NativeToolCall>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (tc in calls) {
            if (tc.name != "remember_fact" && tc.name != "learn_rule") continue
            val args = try {
                JSONObject(tc.argumentsJson.ifBlank { "{}" })
            } catch (e: Exception) {
                continue
            }
            val k = if (tc.name == "remember_fact") args.optString("key").trim()
            else "aturan:" + args.optString("rule").trim().hashCode()
            val v = if (tc.name == "remember_fact") args.optString("value").trim()
            else args.optString("rule").trim()
            if (k.isNotBlank() && v.isNotBlank()) out.add(k to v)
        }
        return out
    }
}

/**
 * D4 — gate single-flight job ekstraksi latar: hanya SATU ekstraksi boleh
 * jalan dalam satu waktu. Pemicu yang datang saat job lama masih jalan
 * dilewati (fakta tetap di riwayat — pemicu 10-pesan berikutnya
 * meng-gate ulang atas jendela yang mencakup fakta itu). Tanpa gate,
 * dua giliran cepat yang sama-sama memicu ekstraksi akan menembak 2
 * panggilan LLM + 2 dialog batch atas fakta yang sama.
 */
class ExtractJobGateVc335R2 {
    private val lock = Any()
    private var running = false

    /** True bila job boleh mulai (tidak ada job lain berjalan). */
    fun tryStart(): Boolean {
        synchronized(lock) {
            if (running) return false
            running = true
            return true
        }
    }

    fun finish() {
        synchronized(lock) { running = false }
    }

    fun isRunning(): Boolean = synchronized(lock) { running }
}
