package com.drowsy.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drowsy.camera.AndroidFrontCameraSource
import com.drowsy.fatigue.DriverState
import com.drowsy.perception.MediaPipeLandmarkerEngine
import com.drowsy.perception.MockPerceptionEngine
import com.drowsy.ui.MonitorViewModelFactory

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))

        // Wiring: Phone Camera → Face Landmarker → EAR/MAR → Temporal → Fatigue → Alert → Room
        // Later swap AndroidFrontCameraSource → NetworkCameraSource for ESP32 (§17-20)
        val camera = AndroidFrontCameraSource(this, this)
        val perception = try {
            MediaPipeLandmarkerEngine(this).also { it.initialize() }
        } catch (_: Exception) {
            MockPerceptionEngine() // fallback for emulator / no model asset
        }

        setContent {
            MaterialTheme {
                val factory = MonitorViewModelFactory(application, camera, perception)
                val vm: MonitorViewModel = viewModel(factory = factory)
                LaunchedEffect(Unit) { vm.start() }
                DrowsyScreen(vm)
            }
        }
    }
}

@Composable
fun DrowsyScreen(vm: MonitorViewModel) {
    val ui by vm.ui.collectAsState()
    val perf by vm.perf.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DRIVER SAFETY", style = MaterialTheme.typography.headlineSmall)
        // Live camera preview placeholder — real: AndroidView(PreviewView) + CameraX preview
        Box(Modifier.fillMaxWidth().height(220.dp).background(Color(0xFF111111)), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(if (ui.facePresent) "Driver detected" else "No face", color = Color.White)
        }
        StatusCard(ui)
        // Dev overlay (§21) — disable in clean demo
        if (androidx.compose.ui.platform.LocalInspectionMode.current.not()) {
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
