package com.senzwh.deepharness

/** vc334 R3 (L4): akumulator teks streaming — SATU StringBuilder per
 *  giliran, diakumulasi per fragmen SSE.
 *
 *  Dulu MainActivity.updateStreamBubble memakai `streamTv.text = clean`
 *  atas SATU fragmen (assignment tanpa akumulator) — bubble hanya
 *  menampilkan potongan terakhir yang berkedip, bukan teks yang tumbuh.
 *  Kini fragmen diakumulasi di sini; UI menampilkan snapshot akumulasi
 *  (disanitasi atas akumulasi, bukan per fragmen — supaya
 *  indexOf("{\"tool\"") / "```" tidak memotong fragmen di tengah).
 *
 *  Murni + thread-safe (dipanggil dari thread LLM, dibaca/direset dari
 *  UI thread) — bisa diuji di JVM. Reset di beginStreamBubble tiap
 *  giliran; snapshot dipakai updateStreamBubble saat tick UI jatuh tempo.
 */
class StreamAccumVc334 {
    private val sb = StringBuilder()

    @Synchronized fun append(fragment: String) {
        sb.append(fragment)
    }

    @Synchronized fun snapshot(): String = sb.toString()

    @Synchronized fun reset() {
        sb.clear()
    }

    @Synchronized fun length(): Int = sb.length
}
