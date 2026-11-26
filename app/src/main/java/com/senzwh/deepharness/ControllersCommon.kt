package com.senzwh.deepharness

// Helper untuk controller hasil pemecahan MainActivity (audit).
// Extension function tidak bisa memakai label `this@MainActivity`; `host`
// memberi akses ke instance MainActivity dari dalam lambda bersarang
// (mis. .apply { ... }) tanpa bergantung pada label kelas.
internal val MainActivity.host: MainActivity get() = this
