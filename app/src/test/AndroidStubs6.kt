// AndroidStubs6.kt — stub android.graphics (Bitmap/BitmapFactory).
// Test-support repo. Lihat AndroidStubs.kt.
// F4a: shim kompilasi JVM untuk LlmEngineChat.askWithFiles — TIDAK
// meniru perilaku decode/kompres Android; tes tidak memanggil jalur
// gambar (Android-only), stub hanya supaya berkas ikut terkompilasi.

package android.graphics

import java.io.OutputStream

class Bitmap(val width: Int, val height: Int) {
    enum class Config { ARGB_8888, RGB_565 }
    enum class CompressFormat { JPEG, PNG, WEBP }

    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean = true
    fun recycle() {}

    companion object {
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap =
            Bitmap(width, height)

        fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap =
            Bitmap(dstWidth, dstHeight)
    }
}

object BitmapFactory {
    class Options {
        var inJustDecodeBounds: Boolean = false
        var outWidth: Int = 0
        var outHeight: Int = 0
    }

    fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options? = null): Bitmap? =
        null
}
