package com.drowsy

import android.app.Application
import com.drowsy.data.AppDatabase
import com.drowsy.sync.SyncWorker

class DrowsyApp : Application() {
    val db by lazy { AppDatabase.get(this) }
    override fun onCreate() {
        super.onCreate()
        // Sync when online (§16) — periodic WorkManager (never blocks safety loop)
        SyncWorker.enqueuePeriodic(this, "http://10.0.2.2:8000")
    }
}
