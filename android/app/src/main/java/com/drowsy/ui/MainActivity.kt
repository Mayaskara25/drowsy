package com.drowsy.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drowsy.camera.AndroidFrontCameraSource
import com.drowsy.fatigue.DriverState
import com.drowsy.perception.MediaPipeLandmarkerEngine
import com.drowsy.perception.MockPerceptionEngine
import com.drowsy.ui.MonitorViewModelFactory

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) recreate() // restart camera binding after grant
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasPermissions()) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
        }

        // Wiring: Phone Camera → Face Landmarker → EAR/MAR → Temporal → Fatigue → Alert → Room
        // Camera source is per-device config (gitignored) — see assets/config.json.example + docs/hardware.md:5
        // Defaults to front camera so clone without ESP32 still demos.
        val camera = createCameraSource()
        val perception = try {
            MediaPipeLandmarkerEngine(applicationContext).also { it.initialize() }
        } catch (_: Exception) {
            MockPerceptionEngine() // fallback for emulator / no model asset
        }
        val locationProvider = FusedLocationProvider(applicationContext)

        setContent {
            MaterialTheme {
                val factory = remember { MonitorViewModelFactory(application, camera, perception, locationProvider) }
                val vm: MonitorViewModel = viewModel(factory = factory)
                LaunchedEffect(Unit) { if (hasPermissions()) vm.start() }
                DisposableEffect(Unit) { onDispose { vm.stop() } }
                DrowsyScreen(vm, camera)
            }
        }
    }

    private fun createCameraSource(): com.drowsy.camera.CameraSource {
        // Read assets/config.json if present (gitignored per-device)
        val cfg = try {
            assets.open("config.json").bufferedReader().readText().let { org.json.JSONObject(it) }
        } catch (_: Exception) { null }
        val source = cfg?.optString("camera_source", "front") ?: "front"
        val url = cfg?.optString("network_camera_url", "http://192.168.4.1") ?: "http://192.168.4.1"
        return if (source == "network") {
            com.drowsy.camera.NetworkCameraSource(url)
        } else if (source == "uvc") {
            com.drowsy.camera.UsbUvcCameraSource()
        } else {
            AndroidFrontCameraSource(applicationContext, this)
        }
    }

    private fun hasPermissions(): Boolean {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return cam
    }
}

@Composable
fun DrowsyScreen(vm: MonitorViewModel, camera: AndroidFrontCameraSource? = null) {
    val ui by vm.ui.collectAsState()
    val perf by vm.perf.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DRIVER SAFETY", style = MaterialTheme.typography.headlineSmall)
        Box(Modifier.fillMaxWidth().height(220.dp).background(Color(0xFF111111))) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        if (camera != null) camera.attachPreview(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(if (ui.facePresent) "Driver detected" else "No face", color = Color.White)
            }
        }
        StatusCard(ui)
        // Dev overlay (§21) — auto-hidden in release via BuildConfig
        if (com.drowsy.BuildConfig.ENABLE_PERF_OVERLAY) {
            PerfOverlay(perf)
        }
        Text("Events today: ${ui.eventsToday}", style = MaterialTheme.typography.bodyMedium)
        if (ui.state == DriverState.FATIGUE || ui.state == DriverState.HIGH_RISK) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFB00020))) {
                Column(Modifier.padding(16.dp)) {
                    Text("⚠ FATIGUE DETECTED", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Fatigue score: ${ui.score} / 100", color = Color.White)
                    Text(if (ui.alertActive) "🔊 ALERT PLAYING" else "", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun StatusCard(ui: UiState) {
    val color = when (ui.state) {
        DriverState.NORMAL -> Color(0xFF2E7D32)
        DriverState.ATTENTION -> Color(0xFFF9A825)
        DriverState.FATIGUE -> Color(0xFFEF6C00)
        DriverState.HIGH_RISK -> Color(0xFFB00020)
    }
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status: ${ui.state.name}", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text("Fatigue score: ${ui.score} / 100", color = Color.White)
            Text("Eyes: ${if (ui.maxClosureMs > 1000) "Prolonged closure ${ui.maxClosureMs}ms" else "Normal"}", color = Color.White)
            Text("Blinking/Yawns: ${ui.yawnCount} yawns", color = Color.White)
            Text("Head pose: ${if (ui.headAbnormal) "Abnormal" else "Normal"}", color = Color.White)
            Text("Tracking: ${when { ui.trackingQuality > 0.7f -> "Good"; ui.trackingQuality > 0.35f -> "Fair"; else -> "Poor" }} (${String.format("%.2f", ui.trackingQuality)})", color = Color.White)
        }
    }
}

@Composable
fun PerfOverlay(perf: com.drowsy.metrics.PerfSnapshot) {
    // Dev-only (§21)
    Text(
        "FPS: ${String.format("%.1f", perf.inferenceFps)}  Inference: ${perf.inferenceMs}ms  " +
        "Network: ${String.format("%.0f", perf.networkKbps)}kbps  Face: ${String.format("%.2f", perf.faceConfidence)}  " +
        "Tracking: ${String.format("%.2f", perf.trackingQuality)}  Fatigue: ${perf.fatigueScore}  State: ${perf.state}",
        style = MaterialTheme.typography.labelSmall, color = Color.Gray
    )
}
