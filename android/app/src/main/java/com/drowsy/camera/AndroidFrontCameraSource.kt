package com.drowsy.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Milestone 1 — first working camera (§6).
 * Wraps CameraX; emits Bitmap frames via Flow. No fatigue code depends on CameraX types.
 */
class AndroidFrontCameraSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val targetWidth: Int = 640,
    private val targetHeight: Int = 480,
) : CameraSource {

    override val name: String = "AndroidFrontCamera"
    override var isRunning: Boolean = false
        private set

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var executor: ExecutorService = Executors.newSingleThreadExecutor()

    private fun ensureExecutor(): ExecutorService {
        if (executor.isShutdown || executor.isTerminated) {
            executor = Executors.newSingleThreadExecutor()
        }
        return executor
    }

    override fun start() {
        if (isRunning) return
        // Guard: device without front camera — fail gracefully, caller handles empty flow
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cameraProvider = future.get()
                    bindAnalysis()
                    isRunning = true
                } catch (_: Exception) {
                    isRunning = false
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (_: Exception) {
            isRunning = false
        }
    }

    override fun stop() {
        isRunning = false
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
        imageAnalysis = null
        // Do not permanently kill executor; shut down gracefully but allow recreation on next start()
        try { executor.shutdown() } catch (_: Exception) {}
    }

    override fun frames(): Flow<CameraFrame> = callbackFlow {
        FrameHub.attach(channel)
        awaitClose { FrameHub.detach() }
    }

    private fun bindAnalysis() {
        val provider = cameraProvider ?: return
        val exec = ensureExecutor()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(targetWidth, targetHeight))
            .build()
        // We cannot capture callbackFlow channel here; instead use a shared MutableSharedFlow.
        // Simpler: use a singleton channel holder that frames() collectors share via callbackFlow.
        // For correctness with single collector (MonitorViewModel), use a global channel ref.
        analysis.setAnalyzer(exec) { imageProxy ->
            val bmp = imageProxy.toBitmapCorrect()
            if (bmp != null) {
                val ts = imageProxy.imageInfo.timestamp / 1_000_000L // ns → ms monotonic
                // Push to active collector if any — use global holder
                FrameHub.tryEmit(CameraFrame(bmp, ts))
            }
            imageProxy.close()
        }
        imageAnalysis = analysis
        try {
            provider.unbindAll()
            if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis,
                )
            } else {
                // No front camera — leave unbound, caller sees empty flow
                isRunning = false
            }
        } catch (_: Exception) {
            isRunning = false
        }
    }

    /** Correct YUV_420_888 → JPEG → Bitmap with rowStride handling. */
    private fun ImageProxy.toBitmapCorrect(): Bitmap? {
        return try {
            if (planes.size < 3) return null
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer
            val nv21 = ByteArray(width * height * 3 / 2)
            // Y plane
            yBuffer.get(nv21, 0, ySize)
            val yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride
            var pos = 0
            yBuffer.rewind()
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
            // UV interleaved VU for NV21
            val uvHeight = height / 2
            val uvWidth = width / 2
            vBuffer.rewind(); uBuffer.rewind()
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val vuPos = row * uvRowStride + col * uvPixelStride
                    // V first, then U
                    nv21[pos++] = vBuffer.get(vuPos)
                    nv21[pos++] = uBuffer.get(vuPos)
                }
            }
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            val jpegBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (_: Exception) {
            null
        }
    }
}

/** Hub to bridge ImageAnalysis (executor thread) to callbackFlow collectors. */
internal object FrameHub {
    private var channel: kotlinx.coroutines.channels.SendChannel<CameraFrame>? = null
    fun attach(ch: kotlinx.coroutines.channels.SendChannel<CameraFrame>) { channel = ch }
    fun detach() { channel = null }
    fun tryEmit(frame: CameraFrame) { try { channel?.trySend(frame) } catch (_: Exception) {} }
}
