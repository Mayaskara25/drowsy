package com.drowsy.sync

import com.squareup.moshi.JsonClass
import retrofit2.http.*

@JsonClass(generateAdapter = true)
data class FatigueEventPayload(
    val eventId: String, val deviceId: String, val vehicleId: String,
    val timestampStart: String, val timestampEnd: String,
    val durationMs: Int, val severity: String, val maxFatigueScore: Int,
    val eyeClosure: Boolean = false, val yawning: Boolean = false,
    val headPoseAbnormal: Boolean = false, val alertTriggered: Boolean = false,
    val recovered: Boolean = false,
    val gps: Gps = Gps(), val trackingQuality: Float = 1f,
)
@JsonClass(generateAdapter = true) data class Gps(val lat: Double = 0.0, val lng: Double = 0.0)

@JsonClass(generateAdapter = true) data class VehicleDto(val vehicleId: String, val name: String?, val lastState: String?, val lastScore: Int?, val lastSeen: String?)
@JsonClass(generateAdapter = true) data class SummaryDto(val totalVehicles: Int, val active: Int, val fatigueEventsToday: Int, val highRiskEvents: Int)

interface ApiService {
    @POST("api/events/batch") suspend fun postBatch(@Body events: List<FatigueEventPayload>): Map<String, Any>
    @POST("api/events") suspend fun postOne(@Body e: FatigueEventPayload): Map<String, Any>
    @GET("api/vehicles") suspend fun vehicles(): List<VehicleDto>
    @GET("api/dashboard/summary") suspend fun summary(): SummaryDto
    @POST("api/vehicles/register") suspend fun registerVehicle(@Body b: Map<String, String>): Map<String, Any>
}
