package com.example.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import com.example.data.model.MemberConnectionStatus
import com.example.data.model.TripMember
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.sin

data class DeviceLocation(
    val latitude: Double = 37.7749,
    val longitude: Double = -122.4194,
    val speed: Double = 14.5, // m/s (~52 km/h)
    val heading: Double = 45.0,
    val accuracy: Double = 5.0,
    val timestamp: Long = System.currentTimeMillis()
)

class LocationProvider(private val context: Context) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _currentLocation = MutableStateFlow(DeviceLocation())
    val currentLocation: StateFlow<DeviceLocation> = _currentLocation.asStateFlow()

    private val _mockFleet = MutableStateFlow<List<TripMember>>(emptyList())
    val mockFleet: StateFlow<List<TripMember>> = _mockFleet.asStateFlow()

    private var mockJob: Job? = null
    private var isListeningGps = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _currentLocation.value = DeviceLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                speed = if (location.hasSpeed()) location.speed.toDouble() else 12.0,
                heading = if (location.hasBearing()) location.bearing.toDouble() else 45.0,
                accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 5.0,
                timestamp = location.time
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (locationManager == null || isListeningGps) return
        try {
            val hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val hasNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (hasGps) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    3f,
                    locationListener
                )
                isListeningGps = true
            } else if (hasNetwork) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    3f,
                    locationListener
                )
                isListeningGps = true
            }
        } catch (e: SecurityException) {
            Log.w("LocationProvider", "Location permission not granted, using simulated driving", e)
        } catch (e: Exception) {
            Log.w("LocationProvider", "Failed to start GPS", e)
        }
    }

    fun stopLocationUpdates() {
        if (isListeningGps && locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener)
            } catch (_: Exception) {}
            isListeningGps = false
        }
        stopMockFleet()
    }

    fun startMockFleet(scope: CoroutineScope) {
        stopMockFleet()
        mockJob = scope.launch {
            var step = 0
            while (isActive) {
                step++
                val myLoc = _currentLocation.value

                // If GPS is stationary, gently simulate gentle driving progress for the user as well
                val rad = Math.toRadians(myLoc.heading)
                val advanceDist = 0.00018 // approx ~20 meters per 2 sec (~36 km/h)
                val nextUserLat = myLoc.latitude + advanceDist * cos(rad)
                val nextUserLng = myLoc.longitude + advanceDist * sin(rad)

                // Only move user if GPS isn't actively providing real movement
                if (!isListeningGps) {
                    _currentLocation.value = myLoc.copy(
                        latitude = nextUserLat,
                        longitude = nextUserLng,
                        speed = 13.8 + sin(step * 0.2) * 2.0,
                        heading = (myLoc.heading + sin(step * 0.1) * 2.0 + 360.0) % 360.0,
                        timestamp = System.currentTimeMillis()
                    )
                }

                val baseLat = _currentLocation.value.latitude
                val baseLng = _currentLocation.value.longitude
                val heading = _currentLocation.value.heading

                // Car 1: Scout / Ahead vehicle (Reza - Jeep)
                val aheadDist = 0.0025 + sin(step * 0.15) * 0.0004
                val car1Lat = baseLat + aheadDist * cos(rad) + 0.0003 * sin(rad)
                val car1Lng = baseLng + aheadDist * sin(rad) - 0.0003 * cos(rad)

                // Car 2: Follower / Mid vehicle (Sara - Tesla)
                val behindDist = -0.0018 + cos(step * 0.2) * 0.0003
                val car2Lat = baseLat + behindDist * cos(rad) - 0.0002 * sin(rad)
                val car2Lng = baseLng + behindDist * sin(rad) + 0.0002 * cos(rad)

                // Car 3: Tail / Sweep vehicle (Mina - Camper Van)
                val tailDist = -0.0042 + sin(step * 0.1) * 0.0005
                val car3Lat = baseLat + tailDist * cos(rad) + 0.0004 * sin(rad)
                val car3Lng = baseLng + tailDist * sin(rad) - 0.0004 * cos(rad)

                val fleet = listOf(
                    TripMember(
                        id = "mock-reza",
                        displayName = "Reza",
                        carName = "Jeep Wrangler",
                        avatarColor = "#10B981",
                        latitude = car1Lat,
                        longitude = car1Lng,
                        speed = 15.2 + sin(step * 0.25) * 2.5,
                        heading = heading,
                        accuracy = 4.2,
                        lastLocationAt = System.currentTimeMillis(),
                        connectionStatus = MemberConnectionStatus.CONNECTED,
                        isLeader = false
                    ),
                    TripMember(
                        id = "mock-sara",
                        displayName = "Sara",
                        carName = "Tesla Model Y",
                        avatarColor = "#F59E0B",
                        latitude = car2Lat,
                        longitude = car2Lng,
                        speed = 14.1 + cos(step * 0.3) * 1.8,
                        heading = heading,
                        accuracy = 3.8,
                        lastLocationAt = System.currentTimeMillis(),
                        connectionStatus = MemberConnectionStatus.CONNECTED,
                        isLeader = false
                    ),
                    TripMember(
                        id = "mock-mina",
                        displayName = "Mina",
                        carName = "Camper Van",
                        avatarColor = "#8B5CF6",
                        latitude = car3Lat,
                        longitude = car3Lng,
                        speed = 12.5 + sin(step * 0.15) * 1.5,
                        heading = heading,
                        accuracy = 6.0,
                        lastLocationAt = System.currentTimeMillis(),
                        connectionStatus = MemberConnectionStatus.CONNECTED,
                        isLeader = false
                    )
                )
                _mockFleet.value = fleet
                delay(2000L)
            }
        }
    }

    fun stopMockFleet() {
        mockJob?.cancel()
        mockJob = null
        _mockFleet.value = emptyList()
    }
}
