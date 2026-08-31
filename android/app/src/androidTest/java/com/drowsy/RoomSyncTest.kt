package com.drowsy

import androidx.test.core.app.ApplicationProvider
import com.drowsy.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoomSyncTest {
    @Test fun saveAndQueryUnsynced() = runBlocking {
        val db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        val ev = FatigueEvent(deviceId="D1", vehicleId="V1", timestampStart=1000, timestampEnd=5000, durationMs=4000, severity=Severity.MEDIUM.name, maxFatigueScore=73, eyeClosure=true, yawning=true, alertTriggered=true, recovered=true)
        saveEvent(db, ev)
        assertEquals(1, db.fatigueEventDao().unsynced().size)
        db.fatigueEventDao().markSynced(ev.eventId)
        db.syncQueueDao().deleteFor(ev.eventId)
        assertEquals(0, db.fatigueEventDao().unsynced().size)
        db.close()
    }
}
