package com.drowsy.camera

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    private var executor: ExecutorService? = null

    private fun ensureExecutor(): ExecutorService {
        if (executor == null || executor!!.isShutdown || executor!!.isTerminated) {
            executor = Executors.newSingleThreadExecutor()
        }
        return executor!!
    }

    override fun start() {
        if (isRunning) return
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
        try { executor?.shutdown() } catch (_: Exception) {}
        executor = null
    }

    override fun frames(): Flow<CameraFrame> = callbackFlow {
        FrameHub.attach(channel)
        awaitClose { FrameHub.detach() }
    }

    private var previewView: androidx.camera.view.PreviewView? = null
    fun attachPreview(previewView: androidx.camera.view.PreviewView) { this.previewView = previewView }

    private fun bindAnalysis() {
        val provider = cameraProvider ?: return
        val exec = ensureExecutor()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(targetWidth, targetHeight))
            .build()
        analysis.setAnalyzer(exec) { imageProxy ->
            val bmp = imageProxy.toBitmap()
            if (bmp != null) {
                val ts = imageProxy.imageInfo.timestamp / 1_000_000L
                FrameHub.tryEmit(CameraFrame(bmp, ts))
            }
            imageProxy.close()
        }
        imageAnalysis = analysis
        val preview = previewView?.let {
            androidx.camera.core.Preview.Builder().setTargetResolution(android.util.Size(targetWidth, targetHeight)).build().also { p -> p.setSurfaceProvider(it.surfaceProvider) }
        }
        try {
            provider.unbindAll()
            if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                if (preview != null) {
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                } else {
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                }
            } else {
                isRunning = false
            }
        } catch (_: Exception) {
            isRunning = false
        }
    }
}

internal object FrameHub {
    private var channel: kotlinx.coroutines.channels.SendChannel<CameraFrame>? = null
    fun attach(ch: kotlinx.coroutines.channels.SendChannel<CameraFrame>) { channel = ch }
    fun detach() { channel = null }
    fun tryEmit(frame: CameraFrame) { try { channel?.trySend(frame) } catch (_: Exception) {} }
}