package com.drowsy.sync

import android.content.Context
import androidx.work.*
import com.drowsy.data.AppDatabase
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Offline-first sync §16 / §24. Room → SyncQueue → Backend when online.
 * Never blocks safety loop; WorkManager retries with backoff.
 * Mirrors Python sync.sync_pending().
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val backendUrl = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val db = AppDatabase.get(applicationContext)
        val pending = db.fatigueEventDao().unsynced()
        if (pending.isEmpty()) return@withContext Result.success()
        val moshi = Moshi.Builder().build()
        val payloads = pending.map { e ->
            mapOf(
                "eventId" to e.eventId, "deviceId" to e.deviceId, "vehicleId" to e.vehicleId,
                "timestampStart" to java.time.Instant.ofEpochMilli(e.timestampStart).toString(),
                "timestampEnd" to java.time.Instant.ofEpochMilli(e.timestampEnd).toString(),
                "durationMs" to e.durationMs, "severity" to e.severity, "maxFatigueScore" to e.maxFatigueScore,
                "eyeClosure" to e.eyeClosure, "yawning" to e.yawning, "headPoseAbnormal" to e.headPoseAbnormal,
                "alertTriggered" to e.alertTriggered, "recovered" to e.recovered,
                "gps" to mapOf("lat" to e.gpsLat, "lng" to e.gpsLng), "trackingQuality" to e.trackingQuality,
            )
        }
        val json = moshi.adapter(Any::class.java).toJson(payloads)
        return@withContext try {
            val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
            val req = Request.Builder().url("$backendUrl/api/events/batch")
                .post(json.toRequestBody("application/json".toMediaType())).build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                pending.forEach { db.fatigueEventDao().markSynced(it.eventId); db.syncQueueDao().deleteFor(it.eventId) }
                Result.success()
            } else {
                pending.forEach { db.syncQueueDao().bumpAttempt(it.eventId) }
                Result.retry()
            }
        } catch (_: Exception) {
            // offline — keep queued, retry later
            pending.forEach { db.syncQueueDao().bumpAttempt(it.eventId) }
            Result.retry()
        }
    }

    companion object {
        const val KEY_URL = "backend_url"
        fun enqueuePeriodic(context: Context, backendUrl: String) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_URL to backendUrl))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("drowsy-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
        fun enqueueOneTime(context: Context, backendUrl: String) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_URL to backendUrl))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
