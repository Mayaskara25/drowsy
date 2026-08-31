package com.drowsy.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await

data class GpsFix(val lat: Double, val lng: Double, val accuracyM: Float? = null)

interface LocationProvider { suspend fun lastFix(): GpsFix }

class FusedLocationProvider(private val context: Context) : LocationProvider {
    @SuppressLint("MissingPermission")
    override suspend fun lastFix(): GpsFix {
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val loc = client.lastLocation.await() ?: client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
            if (loc != null) GpsFix(loc.latitude, loc.longitude, loc.accuracy) else GpsFix(0.0, 0.0)
        } catch (_: Exception) { GpsFix(0.0, 0.0) }
    }
}
class FixedLocationProvider(private val fix: GpsFix = GpsFix(0.0, 0.0)) : LocationProvider {
    override suspend fun lastFix() = fix
}
