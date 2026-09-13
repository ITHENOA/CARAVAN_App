package com.example.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.example.data.model.MemberConnectionStatus
import com.example.data.model.TripMember
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.sin

data class DeviceLocation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val speed: Double = 0.0,
    val heading: Double = 0.0,
    val accuracy: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    val isRealGps: Boolean = false
)

class LocationProvider(private val context: Context) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val _currentLocation = MutableStateFlow(DeviceLocation())
    val currentLocation: StateFlow<DeviceLocation> = _currentLocation.asStateFlow()

    private val _mockFleet = MutableStateFlow<List<TripMember>>(emptyList())
    val mockFleet: StateFlow<List<TripMember>> = _mockFleet.asStateFlow()

    private var mockJob: Job? = null
    private var isListeningGps = false
    private var isListeningSensors = false

    // Sensor calculations
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val accelReading = FloatArray(3)
    private val magReading = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false
    private var lastHeading = 0.0

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            var rawHeading: Double? = null

            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                rawHeading = computeHeadingFromMatrix(rotationMatrix)
            } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, accelReading, 0, 3)
                hasAccel = true
                if (hasMag) {
                    if (SensorManager.getRotationMatrix(rotationMatrix, null, accelReading, magReading)) {
                        rawHeading = computeHeadingFromMatrix(rotationMatrix)
                    }
                }
            } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(event.values, 0, magReading, 0, 3)
                hasMag = true
                if (hasAccel) {
                    if (SensorManager.getRotationMatrix(rotationMatrix, null, accelReading, magReading)) {
                        rawHeading = computeHeadingFromMatrix(rotationMatrix)
                    }
                }
            }

            rawHeading?.let { targetHeading ->
                // Apply shortest-angular-distance low-pass filter to eliminate magnetometer jitter
                val diff = (targetHeading - lastHeading + 540.0) % 360.0 - 180.0
                if (Math.abs(diff) > 1.2) { // 1.2 degree threshold to prevent jitter when holding still
                    val smoothed = (lastHeading + diff * 0.35 + 360.0) % 360.0
                    lastHeading = smoothed
                    _currentLocation.value = _currentLocation.value.copy(
                        heading = smoothed
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun computeHeadingFromMatrix(matrix: FloatArray): Double {
        val rotation = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
        } catch (_: Exception) {
            Surface.ROTATION_0
        }

        when (rotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedMatrix)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remappedMatrix)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedMatrix)
            else -> System.arraycopy(matrix, 0, remappedMatrix, 0, 9)
        }

        SensorManager.getOrientation(remappedMatrix, orientationAngles)
        val azimuthRadians = orientationAngles[0]
        return (Math.toDegrees(azimuthRadians.toDouble()) + 360.0) % 360.0
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val hasGpsBearing = location.hasBearing() && location.hasSpeed() && location.speed > 3.0f
            val heading = if (hasGpsBearing) {
                lastHeading = location.bearing.toDouble()
                location.bearing.toDouble()
            } else {
                _currentLocation.value.heading
            }

            _currentLocation.value = DeviceLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
                heading = heading,
                accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 5.0,
                timestamp = location.time,
                isRealGps = true
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {
            startLocationUpdates()
        }
        override fun onProviderDisabled(provider: String) {}
    }

    fun setManualLocation(latitude: Double, longitude: Double, heading: Double = 0.0) {
        _currentLocation.value = _currentLocation.value.copy(
            latitude = latitude,
            longitude = longitude,
            heading = heading,
            timestamp = System.currentTimeMillis(),
            isRealGps = false
        )
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        startSensorUpdates()
        if (locationManager == null) return
        try {
            // 1. Immediately fetch last known location from all available providers
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var bestLocation: Location? = null
            for (p in providers) {
                try {
                    val loc = locationManager.getLastKnownLocation(p)
                    if (loc != null) {
                        if (bestLocation == null || loc.time > bestLocation.time) {
                            bestLocation = loc
                        }
                    }
                } catch (_: Exception) {}
            }

            bestLocation?.let { loc ->
                _currentLocation.value = DeviceLocation(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                    heading = if (loc.hasBearing()) loc.bearing.toDouble() else _currentLocation.value.heading,
                    accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 5.0,
                    timestamp = loc.time,
                    isRealGps = true
                )
            }

            // 2. Request live updates from both GPS and Network providers simultaneously
            if (!isListeningGps) {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    try {
                        locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            1000L,
                            1f,
                            locationListener
                        )
                    } catch (e: Exception) {
                        Log.w("LocationProvider", "GPS update request failed", e)
                    }
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    try {
                        locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            1000L,
                            1f,
                            locationListener
                        )
                    } catch (e: Exception) {
                        Log.w("LocationProvider", "Network update request failed", e)
                    }
                }
                isListeningGps = true
            }
        } catch (e: SecurityException) {
            Log.w("LocationProvider", "Location permission not granted", e)
        } catch (e: Exception) {
            Log.w("LocationProvider", "Failed to start GPS", e)
        }
    }

    private fun startSensorUpdates() {
        if (isListeningSensors || sensorManager == null) return
        try {
            if (rotationVectorSensor != null) {
                sensorManager.registerListener(
                    sensorEventListener,
                    rotationVectorSensor,
                    SensorManager.SENSOR_DELAY_UI
                )
                isListeningSensors = true
            } else {
                var registered = false
                if (accelerometerSensor != null) {
                    sensorManager.registerListener(
                        sensorEventListener,
                        accelerometerSensor,
                        SensorManager.SENSOR_DELAY_UI
                    )
                    registered = true
                }
                if (magnetometerSensor != null) {
                    sensorManager.registerListener(
                        sensorEventListener,
                        magnetometerSensor,
                        SensorManager.SENSOR_DELAY_UI
                    )
                    registered = true
                }
                isListeningSensors = registered
            }
        } catch (e: Exception) {
            Log.w("LocationProvider", "Failed to start orientation sensors", e)
        }
    }

    private fun stopSensorUpdates() {
        if (isListeningSensors && sensorManager != null) {
            try {
                sensorManager.unregisterListener(sensorEventListener)
            } catch (_: Exception) {}
            isListeningSensors = false
        }
    }

    fun stopLocationUpdates() {
        stopSensorUpdates()
        if (isListeningGps && locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener)
            } catch (_: Exception) {}
            isListeningGps = false
        }
        stopMockFleet()
    }

    fun isGpsEnabled(): Boolean {
        val lm = locationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun openLocationSettings() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("LocationProvider", "Failed to open location settings", e)
        }
    }

    fun requestImmediateLocation() {
        startLocationUpdates()
    }

    fun startMockFleet(scope: CoroutineScope) {
        stopMockFleet()
        mockJob = scope.launch {
            var step = 0
            while (isActive) {
                step++
                val myLoc = _currentLocation.value
                val rad = Math.toRadians(myLoc.heading)

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
