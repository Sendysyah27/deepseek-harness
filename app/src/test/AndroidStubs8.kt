// AndroidStubs8.kt — stub android.os (ParcelFileDescriptor).
// Test-support repo. Lihat AndroidStubs.kt.
// F4a: shim kompilasi JVM untuk LlmEngineChat.askWithFiles (buka PDF
// sementara) — TIDAK meniru perilaku ParcelFileDescriptor Android.

package android.os

import java.io.File

class ParcelFileDescriptor {
    fun close() {}

    companion object {
        const val MODE_READ_ONLY: Int = 268435456

        fun open(file: File, mode: Int): ParcelFileDescriptor = ParcelFileDescriptor()
    }
}
