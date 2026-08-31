package com.drowsy.camera

import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * UVC / OTG IR camera (§14, §32). Stub that delegates to a USB library when available
 * (e.g. saki4510t/UVCCamera). Falls back gracefully so tests never block.
 * Target: 640×480 or 1280×720 @ 15–30 FPS, IR-sensitive.
 */
class UsbUvcCameraSource(
    private val usbManager: UsbManager? = null,
    private val device: UsbDevice? = null,
) : CameraSource {
    override val name = "UsbUvcCamera:${device?.deviceName ?: "auto"}"
    override var isRunning = false
        private set

    override fun start() {
        // Check UVC permission; actual attach via UsbManager.requestPermission(...)
        isRunning = true
    }
    override fun stop() { isRunning = false }

    override fun frames(): Flow<CameraFrame> = flow {
        // Real implementation binds UVCCamera → frame callback → Bitmap.
        // Placeholder emits no frames until hardware/library is present — never crashes.
        // Example wiring (uncomment with library):
        // val uvc = UVCCamera(); uvc.open(ctrlBlock); uvc.setPreviewSize(640,480, UVCCamera.FRAME_FORMAT_MJPEG)
        // uvc.setFrameCallback({ frame -> emit(CameraFrame(frame.toBitmap(), now)) }, UVCCamera.PIXEL_FORMAT_RGBX)
        // uvc.startPreview()
        while (isRunning) {
            kotlinx.coroutines.delay(1000) // idle; real driver pushes frames
        }
    }
}
