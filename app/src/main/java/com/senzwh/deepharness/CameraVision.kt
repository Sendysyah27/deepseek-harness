package com.senzwh.deepharness

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * vc217: pengendali kamera framework Camera2 (tanpa CameraX, tanpa AndroidX).
 * Satu kamera (belakang bila ada), preview TextureView, tangkap JPEG.
 * Tidak ada perekaman video, tidak ada tangkapan background: semua tangkapan
 * dipicu eksplisit dari UI foreground (tombol Tangkap atau loop live yang
 * preview-nya terlihat) lewat MainActivity. Kelas ini tidak pernah
 * mengambil gambar sendiri di luar panggilan captureStill yang eksplisit.
 */
class CameraVision(private val ctx: Context) {
    private var manager: CameraManager? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var textureView: TextureView? = null
    private var sensorOrientation = 90
    private var captureCb: ((ByteArray) -> Unit)? = null
    private var captureErr: ((String) -> Unit)? = null
    private var capturing = false
    private var closing = false
    private val mainHandler = Handler(Looper.getMainLooper())
    var isOpen = false
        private set

    companion object {
        fun hasCamera(ctx: Context): Boolean {
            return try {
                val m = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                m.cameraIdList.isNotEmpty()
            } catch (e: Exception) {
                false
            }
        }

        fun cameraDir(): File {
            val d = File("/sdcard/S&Harness/camera")
            try {
                d.mkdirs()
            } catch (e: Exception) {
            }
            return d
        }

        fun saveCapture(bytes: ByteArray, prefix: String): File? {
            return try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val f = File(cameraDir(), prefix + "_" + stamp + ".jpg")
                FileOutputStream(f).use { it.write(bytes) }
                f
            } catch (e: Exception) {
                null
            }
        }
    }

    fun open(tv: TextureView, onReady: () -> Unit, onError: (String) -> Unit) {
        closing = false
        textureView = tv
        try {
            bgThread = HandlerThread("cam2").apply { start() }
            bgHandler = Handler(bgThread!!.looper)
            manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (tv.isAvailable) {
                openInternal(onReady, onError)
            } else {
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        openInternal(onReady, onError)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        } catch (e: Exception) {
            mainHandler.post { onError(e.message ?: "gagal membuka kamera") }
        }
    }

    private fun openInternal(onReady: () -> Unit, onError: (String) -> Unit) {
        try {
            val mgr = manager ?: throw Exception("CameraManager tidak tersedia")
            val tv = textureView ?: throw Exception("preview tidak tersedia")
            var chosenId: String? = null
            var backId: String? = null
            for (id in mgr.cameraIdList) {
                if (chosenId == null) chosenId = id
                try {
                    val c = mgr.getCameraCharacteristics(id)
                    if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                        backId = id
                        break
                    }
                } catch (e: Exception) {
                }
            }
            val camId = backId ?: chosenId ?: throw Exception("tidak ada kamera yang bisa dipakai")
            val chars = mgr.getCameraCharacteristics(camId)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw Exception("konfigurasi stream kamera tidak tersedia")
            val viewW = if (tv.width > 0) tv.width else 720
            val viewH = if (tv.height > 0) tv.height else 1280
            var bestPreview: android.util.Size? = null
            var bestScore = Int.MAX_VALUE
            for (s in map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()) {
                val score = kotlin.math.abs(s.width - viewH) + kotlin.math.abs(s.height - viewW)
                if (score < bestScore) {
                    bestScore = score
                    bestPreview = s
                }
            }
            val previewSize = bestPreview ?: throw Exception("ukuran preview kamera tidak tersedia")
            tv.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
            var bestJpeg: android.util.Size? = null
            var bestPixels = 0
            for (s in map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()) {
                if (s.width <= 1920 && s.height <= 1920 && s.width * s.height > bestPixels) {
                    bestPixels = s.width * s.height
                    bestJpeg = s
                }
            }
            val jpegSize = bestJpeg ?: previewSize
            val rd = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            reader = rd
            rd.setOnImageAvailableListener({ r ->
                val img = try {
                    r.acquireLatestImage()
                } catch (e: Exception) {
                    null
                } ?: return@setOnImageAvailableListener
                try {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    val cb = captureCb
                    captureCb = null
                    captureErr = null
                    capturing = false
                    mainHandler.post { cb?.invoke(bytes) }
                } catch (e: Exception) {
                    val eb = captureErr
                    captureCb = null
                    captureErr = null
                    capturing = false
                    mainHandler.post { eb?.invoke(e.message ?: "gagal baca frame") }
                } finally {
                    try {
                        img.close()
                    } catch (e: Exception) {
                    }
                }
            }, bgHandler)
            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closing) {
                        try {
                            camera.close()
                        } catch (e: Exception) {
                        }
                        return
                    }
                    device = camera
                    try {
                        val surfaces = listOf(Surface(tv.surfaceTexture), rd.surface)
                        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (closing) return
                                session = s
                                try {
                                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    builder.addTarget(Surface(tv.surfaceTexture))
                                    try {
                                        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                                        if (afModes != null && afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                                            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        }
                                    } catch (e: Exception) {
                                    }
                                    s.setRepeatingRequest(builder.build(), null, bgHandler)
                                    isOpen = true
                                    mainHandler.post { onReady() }
                                } catch (e: Exception) {
                                    mainHandler.post { onError(e.message ?: "preview gagal dimulai") }
                                }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                mainHandler.post { onError("sesi preview kamera gagal dibuat") }
                            }
                        }, bgHandler)
                    } catch (e: Exception) {
                        mainHandler.post { onError(e.message ?: "sesi kamera gagal") }
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    isOpen = false
                    if (!closing) mainHandler.post { onError("kamera terputus") }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    isOpen = false
                    if (!closing) mainHandler.post { onError("kamera error ($error)") }
                }
            }, bgHandler)
        } catch (e: SecurityException) {
            mainHandler.post { onError("izin kamera belum diberikan") }
        } catch (e: CameraAccessException) {
            mainHandler.post { onError("kamera tidak bisa diakses (sibuk atau tidak tersedia)") }
        } catch (e: Exception) {
            mainHandler.post { onError(e.message ?: "gagal membuka kamera") }
        }
    }

    fun captureStill(onJpeg: (ByteArray) -> Unit, onError: (String) -> Unit) {
        val dev = device
        val sess = session
        val rd = reader
        if (!isOpen || dev == null || sess == null || rd == null) {
            onError("kamera belum siap")
            return
        }
        if (capturing) {
            onError("kamera sibuk")
            return
        }
        capturing = true
        captureCb = onJpeg
        captureErr = onError
        try {
            val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(rd.surface)
            builder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            try {
                val chars = manager?.getCameraCharacteristics(dev.id)
                val afModes = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                if (afModes != null && afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
            } catch (e: Exception) {
            }
            sess.capture(builder.build(), null, bgHandler)
        } catch (e: Exception) {
            capturing = false
            captureCb = null
            captureErr = null
            onError(e.message ?: "tangkap gagal")
        }
    }

    fun close() {
        closing = true
        isOpen = false
        capturing = false
        captureCb = null
        captureErr = null
        try {
            session?.close()
        } catch (e: Exception) {
        }
        try {
            device?.close()
        } catch (e: Exception) {
        }
        try {
            reader?.close()
        } catch (e: Exception) {
        }
        try {
            bgThread?.quitSafely()
        } catch (e: Exception) {
        }
        session = null
        device = null
        reader = null
        bgThread = null
        bgHandler = null
        textureView = null
    }
}
