package com.drowsy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Vehicle::class, Device::class, FatigueEvent::class, AlertEvent::class, SyncQueue::class, AppSettings::class, Calibration::class, Trip::class],
    version = 1, exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao
    abstract fun fatigueEventDao(): FatigueEventDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun alertEventDao(): AlertEventDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun calibrationDao(): CalibrationDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "drowsy.db")
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }
        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }
}

/** Helper: save event + enqueue (Local DB §19-20, never blocks detection loop). */
suspend fun saveEvent(db: AppDatabase, event: FatigueEvent) {
    db.fatigueEventDao().insert(event)
    db.syncQueueDao().enqueue(SyncQueue(eventId = event.eventId))
    // upsert vehicle last state
    val v = db.vehicleDao().byId(event.vehicleId) ?: Vehicle(vehicleId = event.vehicleId, name = event.vehicleId)
    db.vehicleDao().upsert(v.copy(lastScore = event.maxFatigueScore, lastState = event.severity, lastSeen = System.currentTimeMillis()))
}
