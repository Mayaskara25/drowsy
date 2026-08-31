package com.drowsy.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.HttpURLConnection
import java.net.URL

/**
 * ESP32-S3 / generic MJPEG + snapshot (§17-20).
 * Supports: GET /stream (MJPEG multipart) and GET /snapshot (single JPEG).
 * Handles reconnect, timeout, malformed frames, dropped frames, latency.
 * Fatigue pipeline sees only CameraFrame Flow.
 */
class NetworkCameraSource(
    private val baseUrl: String, // e.g. http://192.168.4.1
    private val preferMjpeg: Boolean = true,
    private val reconnectDelayMs: Long = 2000,
    private val jpegQualityTimeoutMs: Int = 4000,
) : CameraSource {

    override val name: String = "NetworkCamera:$baseUrl"
    override var isRunning: Boolean = false
        private set

    @Volatile private var latencyMs: Long = -1
    fun lastLatencyMs(): Long = latencyMs

    override fun start() { isRunning = true }
    override fun stop() { isRunning = false }

    override fun frames(): Flow<CameraFrame> = flow {
        while (isRunning) {
            try {
                if (preferMjpeg) emitMjpeg(this) else emitSnapshotLoop(this)
            } catch (_: Exception) {
                // reconnect with backoff
                delay(reconnectDelayMs)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun emitMjpeg(collector: kotlinx.coroutines.flow.FlowCollector<CameraFrame>) {
        val url = URL("$baseUrl/stream")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = jpegQualityTimeoutMs
            readTimeout = 0 // streaming
            setRequestProperty("Accept", "multipart/x-mixed-replace")
        }
        conn.connect()
        if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
        val input = conn.inputStream
        val boundary = conn.headerFields["Content-Type"]?.firstOrNull()
            ?.substringAfter("boundary=")?.trim() ?: "--frame"
        val buffer = ByteArray(128 * 1024)
        var leftover = ByteArray(0)
        try {
            while (isRunning) {
                val n = input.read(buffer)
                if (n <= 0) break
                val chunk = leftover + buffer.copyOf(n)
                // Scan for JPEG SOI/EOI (FF D8 ... FF D9)
                var start = -1; var end = -1
                for (i in 0 until chunk.size - 1) {
                    if (chunk[i] == 0xFF.toByte() && chunk[i+1] == 0xD8.toByte() && start==-1) start=i
                    if (chunk[i] == 0xFF.toByte() && chunk[i+1] == 0xD9.toByte() && start!=-1) { end=i+1; break }
                }
                if (start!=-1 && end!=-1) {
                    val jpeg = chunk.copyOfRange(start, end+1)
                    val t0 = System.currentTimeMillis()
                    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    if (bmp != null) {
                        latencyMs = System.currentTimeMillis() - t0
                        collector.emit(CameraFrame(bmp, System.currentTimeMillis()))
                    }
                    leftover = if (end+1 < chunk.size) chunk.copyOfRange(end+1, chunk.size) else ByteArray(0)
                } else {
                    leftover = chunk
                    if (leftover.size > 512*1024) leftover = leftover.takeLast(256*1024).toByteArray()
                }
            }
        } finally { try { input.close() } catch (_: Exception) {}; conn.disconnect() }
    }

    private suspend fun emitSnapshotLoop(collector: kotlinx.coroutines.flow.FlowCollector<CameraFrame>) {
        while (isRunning) {
            val t0 = System.currentTimeMillis()
            try {
                val url = URL("$baseUrl/snapshot")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = jpegQualityTimeoutMs; readTimeout = jpegQualityTimeoutMs
                }
                val bytes = conn.inputStream.readBytes()
                conn.disconnect()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    latencyMs = System.currentTimeMillis() - t0
                    collector.emit(CameraFrame(bmp, System.currentTimeMillis()))
                }
            } catch (_: Exception) { /* dropped frame — tolerated */ }
            delay(100) // ~10 FPS snapshot polling (§19)
        }
    }
}
