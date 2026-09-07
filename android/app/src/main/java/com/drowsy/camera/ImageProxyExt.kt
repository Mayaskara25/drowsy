package com.drowsy.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * YUV_420_888 → Bitmap fallback for CameraX ImageProxy.
 * The recent fix replaced a buggy toBitmapCorrect() with a non-existent
 * ImageProxy.toBitmap() extension. This file restores a correct conversion
 * so the project builds on camera-core:1.3.2 without experimental APIs.
 */
fun ImageProxy.toBitmap(): Bitmap? {
    return try {
        if (planes.size < 3) return null
        val w = width
        val h = height
        val nv21 = ByteArray(w * h * 3 / 2)
        // Y plane
        val yBuf = planes[0].buffer
        val yRowStride = planes[0].rowStride
        var pos = 0
        yBuf.rewind()
        for (row in 0 until h) {
            yBuf.position(row * yRowStride)
            val rowLen = minOf(w, yBuf.remaining())
            yBuf.get(nv21, pos, rowLen)
            pos += w
        }
        // UV planes — interleave VU for NV21
        val uvH = h / 2
        val ySize = w * h
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride
        // Some devices interleave incorrectly — handle both strides
        if (uvPixelStride == 1) {
            // planar
            for (row in 0 until uvH) {
                uBuf.position(row * uvRowStride)
                vBuf.position(row * uvRowStride)
                for (col in 0 until w / 2) {
                    nv21[ySize + row * w + col * 2] = vBuf.get()
                    nv21[ySize + row * w + col * 2 + 1] = uBuf.get()
                }
            }
        } else {
            for (row in 0 until uvH) {
                for (col in 0 until w / 2) {
                    val vuPos = row * uvRowStride + col * uvPixelStride
                    nv21[ySize + row * w + col * 2] = vBuf.get(vuPos)
                    nv21[ySize + row * w + col * 2 + 1] = uBuf.get(vuPos)
                }
            }
        }
        val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, w, h), 85, out)
        val jpeg = out.toByteArray()
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    } catch (_: Exception) { null }
}
