package com.drowsy.camera

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow

/**
 * Camera abstraction §6 / §14. Fatigue pipeline never knows the source.
 * Mirrors Python CameraSource.open/read/close/name (§14).
 */
data class CameraFrame(
    val bitmap: Bitmap,
    val timestampMs: Long,
    val width: Int = bitmap.width,
    val height: Int = bitmap.height,
)

interface CameraSource {
    fun start()
    fun stop()
    fun frames(): Flow<CameraFrame>
    val name: String
    val isRunning: Boolean
}
