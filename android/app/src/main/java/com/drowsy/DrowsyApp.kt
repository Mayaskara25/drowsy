package com.drowsy

import android.app.Application
import com.drowsy.data.AppDatabase
import com.drowsy.sync.SyncWorker
import org.json.JSONObject

class DrowsyApp : Application() {
    val db by lazy { AppDatabase.get(this) }
    override fun onCreate() {
        super.onCreate()
        // Sync when online (§16) — periodic WorkManager (never blocks safety loop)
        // Reads from assets/config.json (per-device, gitignored) → SharedPreferences → fallback.
        // Clone-safe: no hard-coded IP needed; see docs/hardware.md:5 and assets/README.md
        val backendUrl = resolveBackendUrl()
        SyncWorker.enqueuePeriodic(this, backendUrl)
    }

    private fun resolveBackendUrl(): String {
        // 1. SharedPreferences override (set by Settings UI if added)
        getSharedPreferences("drowsy", MODE_PRIVATE).getString("backend_url", null)?.let { if (it.isNotBlank()) return it }
        // 2. assets/config.json (gitignored per-device, see config.json.example)
        try {
            assets.open("config.json").use { s ->
                val json = JSONObject(s.bufferedReader().readText())
                json.optString("backend_url", "").takeIf { it.isNotBlank() }?.let { return it }
            }
        } catch (_: Exception) {}
        // 3. Emulator default for local dev
        return "http://10.0.2.2:8000"
    }
}
