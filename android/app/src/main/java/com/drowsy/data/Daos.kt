package com.drowsy.data

import androidx.room.*

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles") suspend fun all(): List<Vehicle>
    @Query("SELECT * FROM vehicles WHERE vehicleId = :id LIMIT 1") suspend fun byId(id: String): Vehicle?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(v: Vehicle)
}

@Dao
interface FatigueEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(e: FatigueEvent)
    @Query("SELECT * FROM fatigue_events WHERE synced = 0") suspend fun unsynced(): List<FatigueEvent>
    @Query("SELECT * FROM fatigue_events WHERE vehicleId = :vid ORDER BY timestampStart DESC LIMIT :limit") suspend fun byVehicle(vid: String, limit: Int = 50): List<FatigueEvent>
    @Query("SELECT * FROM fatigue_events ORDER BY timestampStart DESC LIMIT :limit") suspend fun recent(limit: Int = 100): List<FatigueEvent>
    @Query("UPDATE fatigue_events SET synced = 1 WHERE eventId = :id") suspend fun markSynced(id: String)
    @Query("SELECT COUNT(*) FROM fatigue_events WHERE timestampStart >= :since") suspend fun countSince(since: Long): Int
}

@Dao
interface SyncQueueDao {
    @Insert suspend fun enqueue(q: SyncQueue)
    @Query("SELECT * FROM sync_queue") suspend fun all(): List<SyncQueue>
    @Query("DELETE FROM sync_queue WHERE eventId = :eid") suspend fun deleteFor(eid: String)
    @Query("UPDATE sync_queue SET attempts = attempts + 1, lastAttempt = :now WHERE eventId = :eid") suspend fun bumpAttempt(eid: String, now: Long = System.currentTimeMillis())
}

@Dao
interface AlertEventDao {
    @Insert suspend fun insert(a: AlertEvent)
    @Query("SELECT * FROM alert_events ORDER BY timestamp DESC LIMIT :limit") suspend fun recent(limit: Int = 50): List<AlertEvent>
}

@Dao
interface AppSettingsDao {
    @Query("SELECT value FROM app_settings WHERE `key` = :k LIMIT 1") suspend fun get(k: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun set(s: AppSettings)
}

@Dao
interface CalibrationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(c: Calibration)
    @Query("SELECT * FROM calibration WHERE deviceId = :did ORDER BY createdAt DESC LIMIT 1") suspend fun latest(did: String): Calibration?
}
