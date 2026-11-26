// AndroidStubs7.kt — stub android.graphics.pdf (PdfRenderer).
// Test-support repo. Lihat AndroidStubs.kt.
// F4a: shim kompilasi JVM untuk LlmEngineChat.askWithFiles (render
// halaman 1 PDF) — TIDAK meniru perilaku PdfRenderer Android.

package android.graphics.pdf

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor

class PdfRenderer(@Suppress("unused") val parcelFileDescriptor: ParcelFileDescriptor) {
    val pageCount: Int = 0

    fun openPage(index: Int): Page = Page()
    fun close() {}

    class Page {
        val width: Int = 0
        val height: Int = 0

        fun render(bitmap: Bitmap, destClip: Any?, transform: Any?, mode: Int) {}
        fun close() {}

        companion object {
            const val RENDER_MODE_FOR_DISPLAY: Int = 1
        }
    }
}
