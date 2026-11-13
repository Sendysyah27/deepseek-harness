package com.senzwh.deepharness

// vc304 B2 — Giliran jalan di background, output live-nya TIDAK bocor ke sesi lain.
//
// Syarat user (seperti Claude sessions): pindah sesi TIDAK menghentikan /
// membatalkan giliran yang sedang jalan. Yang di-gate HANYA render live-nya:
// bubble jawaban, baris aktivitas tool, kartu kerja, status "sedang bekerja",
// dialog +10 / approval — semuanya hanya tampil di sesi pemilik giliran
// (turnSessionId). Di sesi lain render live DILEWATI; isinya tidak hilang
// karena jawaban final + kartu kerja TETAP tersimpan ke histori DB sesi
// pemilik (MainActivity.handleAgentResponse memakai turnSessionId), dan
// switchSession memuat ulang histori itu saat user kembali ke sesi pemilik.
//
// Keputusan yang butuh user (dialog +10 langkah, approval tool) TIDAK
// ditampilkan di sesi lain dan TIDAK di-auto-approve: diparkir sampai user
// kembali ke sesi pemilik, lalu ditawarkan ulang dari switchSession.
//
// File ini SENGAJA murni Kotlin tanpa dependensi Android supaya gerbangnya
// bisa diuji di JVM (TestSessionBackgroundVc304.kt). MainActivity memanggil
// dua predikat di bawah dari addMsg/addBubble/addActivityRow (param
// `turn = true` = output giliran) dan dari titik-titik dialog/status.
object SessionBackgroundVc304 {

    /** Output live giliran hanya dirender bila sesi pemilik giliran sedang aktif. */
    fun renderAllowed(turnSessionId: Long, currentSessionId: Long): Boolean =
        turnSessionId == currentSessionId

    /** Keputusan giliran (dialog +10 / approval tool) harus parkir bila
     *  giliran berjalan di background — JANGAN tampil di sesi lain,
     *  JANGAN auto-approve, JANGAN eksekusi HIGH tanpa PIN. */
    fun shouldParkDecision(turnActive: Boolean, turnSessionId: Long, currentSessionId: Long): Boolean =
        turnActive && turnSessionId != currentSessionId

    /** Teks penanda terpisah untuk indikator status saat giliran sesi lain
     *  berjalan di background — tanpa isi proses giliran itu. */
    fun backgroundNotice(): String =
        "● Giliran sesi lain berjalan di background — kembali ke sesi itu untuk melihat progres."
}
