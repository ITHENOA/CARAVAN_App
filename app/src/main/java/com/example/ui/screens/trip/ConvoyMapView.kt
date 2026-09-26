package com.example.ui.screens.trip

import android.content.Context
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.data.location.DeviceLocation
import com.example.data.model.MapMark
import com.example.data.model.MemberConnectionStatus
import com.example.data.model.RouteSegment
import com.example.data.model.SharedRoute
import com.example.data.model.TripDestination
import com.example.data.model.TripMember
import com.example.data.network.RouteResult
import com.example.ui.theme.*
import com.example.ui.viewmodel.RoutingProvider
import com.example.util.ConvoyUtils
import kotlinx.coroutines.delay
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.roundToInt

/**
 * Real MapLibre Basemap View (matches Flutter client v1).
 * Uses OpenFreeMap Liberty style: https://tiles.openfreemap.org/styles/liberty
 * Renders real vector/raster road network, buildings, and geography.
 */
@Composable
fun ConvoyMapView(
    currentLocation: DeviceLocation,
    members: List<TripMember>,
    destination: TripDestination?,
    route: RouteResult?,
    activeRoutingProvider: RoutingProvider?,
    isCalculatingRoute: Boolean,
    isDarkMode: Boolean,
    isNavigating: Boolean = false,
    drivingViewZoom: Float = 16.5f,
    drivingMarkerPosition: Float = 0.68f,
    isMyLocationActive: Boolean = false,
    selfColorHex: String = "#0EA5E9",
    selfClientId: String = "",
    selfDisplayName: String = "",
    marks: Map<String, MapMark> = emptyMap(),
    sharedRoutes: Map<String, SharedRoute> = emptyMap(),
    fitAllRequestedAt: Long = 0L,
    allMembersMuted: Boolean = false,
    onToggleAllMembersMute: () -> Unit = {},
    onToggleFreeDriving: () -> Unit = {},
    isLiveConvoyFramingActive: Boolean = false,
    onToggleLiveConvoyFraming: () -> Unit = {},
    onFitAll: () -> Unit = {},
    convoyFramingRadiusMeters: Int = -1,
    memberToFocus: TripMember? = null,
    onLongPressMark: (latitude: Double, longitude: Double) -> Unit,
    onMemberSelected: (TripMember) -> Unit,
    onMarkSelected: ((MapMark) -> Unit)? = null,
    onDrivingViewInterrupted: () -> Unit = {},
    showReturnToDriving: Boolean = false,
    returnToDrivingProgress: Float = 0f,
    onReturnToDriving: () -> Unit = {},
    drivingViewResetToken: Long = 0L,
    /** @return true if my-location was activated / refreshed; false if system location is off. */
    onMyLocationClick: () -> Boolean = { true },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var isMapReady by remember { mutableStateOf(false) }
    var initialCameraSet by remember { mutableStateOf(false) }

    // Retained map annotation references to eliminate flickering, GPU stalls, and render lag
    var activeRoutePolylines by remember { mutableStateOf<List<org.maplibre.android.annotations.Polyline>>(emptyList()) }
    var activeSharedPolylines by remember { mutableStateOf<List<org.maplibre.android.annotations.Polyline>>(emptyList()) }
    val activeMemberMarkers = remember { mutableMapOf<String, org.maplibre.android.annotations.Marker>() }
    val activeMarkerAnimators = remember { mutableMapOf<String, ValueAnimator>() }
    val memberIconCache = remember { mutableMapOf<String, org.maplibre.android.annotations.Icon?>() }
    val memberMarkerSignature = remember { mutableMapOf<String, String>() }
    val memberLastKnownLocations = remember { mutableMapOf<String, LatLng>() }
    var memberScreenPoints by remember { mutableStateOf<Map<String, PointF>>(emptyMap()) }
    var wasLiveConvoyFraming by remember { mutableStateOf(false) }

    var destScreenPoint by remember { mutableStateOf<PointF?>(null) }
    var selfScreenPoint by remember { mutableStateOf<PointF?>(null) }
    var markScreenPoints by remember { mutableStateOf<Map<String, PointF>>(emptyMap()) }
    var currentMapBearing by remember { mutableDoubleStateOf(0.0) }
    var currentMapTilt by remember { mutableDoubleStateOf(0.0) }
    var recenterRequestedAt by remember { mutableLongStateOf(0L) }
    var lastMapSize by remember { mutableStateOf(IntSize.Zero) }
    var followDrivingCamera by remember { mutableStateOf(true) }
    var followLocationCamera by remember { mutableStateOf(false) }
    var movementBearing by remember { mutableStateOf<Double?>(null) }
    var previousLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val currentMembers by rememberUpdatedState(members)
    val currentSelfClientId by rememberUpdatedState(selfClientId)
    val onMemberSelectedState by rememberUpdatedState(onMemberSelected)
    val onLongPressMarkState by rememberUpdatedState(onLongPressMark)
    val currentDestination by rememberUpdatedState(destination)
    val currentMarks by rememberUpdatedState(marks)
    val currentLocationState by rememberUpdatedState(currentLocation)
    val isMyLocationActiveState by rememberUpdatedState(isMyLocationActive)
    val onDrivingViewInterruptedState by rememberUpdatedState(onDrivingViewInterrupted)

    LaunchedEffect(currentLocation.latitude, currentLocation.longitude) {
        val latitude = currentLocation.latitude
        val longitude = currentLocation.longitude
        if (latitude == 0.0 && longitude == 0.0) return@LaunchedEffect

        previousLocation?.let { (previousLatitude, previousLongitude) ->
            val distance = ConvoyUtils.distanceMeters(
                previousLatitude,
                previousLongitude,
                latitude,
                longitude
            )
            val speedMps = currentLocation.speed ?: 0.0
            // Only update bearing when vehicle is genuinely moving to avoid camera spin while stopped
            if (distance >= 2.0 || speedMps >= 0.8) {
                val newBearing = ConvoyUtils.calculateBearing(
                    previousLatitude,
                    previousLongitude,
                    latitude,
                    longitude
                )
                val current = movementBearing
                if (current == null) {
                    movementBearing = newBearing
                } else {
                    var diff = (newBearing - current) % 360.0
                    if (diff < -180.0) diff += 360.0
                    if (diff > 180.0) diff -= 360.0
                    // Smooth 25% blend towards new bearing to prevent sudden heading snaps
                    movementBearing = (current + diff * 0.25 + 360.0) % 360.0
                }
            }
        }
        previousLocation = latitude to longitude
    }

    fun cameraPadding(map: MapLibreMap, driving: Boolean): DoubleArray {
        if (!driving) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val topRatio = ((drivingMarkerPosition * 2.0 - 1.0).coerceIn(0.0, 0.85))
        return doubleArrayOf(0.0, map.height * topRatio, 0.0, 0.0)
    }

    fun updateScreenLocations(map: MapLibreMap?) {
        val m = map ?: mapLibreMap ?: return
        try {
            currentMapBearing = m.cameraPosition.bearing
            currentMapTilt = m.cameraPosition.tilt
            val dest = currentDestination
            if (dest != null) {
                try {
                    val pt = m.projection.toScreenLocation(LatLng(dest.latitude, dest.longitude))
                    if (pt.x.isFinite() && pt.y.isFinite()) {
                        destScreenPoint = pt
                    }
                } catch (_: Exception) {}
            } else {
                destScreenPoint = null
            }
            val loc = currentLocationState
            if (loc.latitude != 0.0 || loc.longitude != 0.0) {
                try {
                    val pt = m.projection.toScreenLocation(LatLng(loc.latitude, loc.longitude))
                    if (pt.x.isFinite() && pt.y.isFinite()) {
                        selfScreenPoint = pt
                    }
                } catch (_: Exception) {}
            } else {
                selfScreenPoint = null
            }
            val nextMarks = LinkedHashMap<String, PointF>(currentMarks.size)
            currentMarks.forEach { (id, mark) ->
                try {
                    val pt = m.projection.toScreenLocation(LatLng(mark.latitude, mark.longitude))
                    if (pt.x.isFinite() && pt.y.isFinite()) {
                        nextMarks[id] = pt
                    }
                } catch (_: Exception) {}
            }
            markScreenPoints = nextMarks

            val memberList = currentMembers
            val selfId = currentSelfClientId
            val nextMemberPoints = HashMap<String, PointF>(memberList.size)
            memberList.forEach { member ->
                if (member.id != selfId) {
                    val lat = member.latitude
                    val lng = member.longitude
                    val pos = if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                        LatLng(lat, lng).also { memberLastKnownLocations[member.id] = it }
                    } else {
                        memberLastKnownLocations[member.id]
                    }
                    if (pos != null) {
                        try {
                            val screenPt = m.projection.toScreenLocation(pos)
                            if (screenPt.x.isFinite() && screenPt.y.isFinite()) {
                                nextMemberPoints[member.id] = screenPt
                            } else {
                                memberScreenPoints[member.id]?.let { nextMemberPoints[member.id] = it }
                            }
                        } catch (_: Exception) {
                            memberScreenPoints[member.id]?.let { nextMemberPoints[member.id] = it }
                        }
                    }
                }
            }
            memberScreenPoints = nextMemberPoints
        } catch (_: Exception) {}
    }

    // MapView instance
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
        }
    }

    // Bind MapView lifecycle to the Compose Lifecycle
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                mapLibreMap?.let { map ->
                    activeMemberMarkers.values.forEach {
                        try { map.removeMarker(it) } catch (_: Exception) {}
                    }
                }
                activeMarkerAnimators.values.forEach { it.cancel() }
                activeMarkerAnimators.clear()
                activeMemberMarkers.clear()
                memberIconCache.clear()
                memberMarkerSignature.clear()
            } catch (_: Exception) {}
            try {
                mapView.onDestroy()
            } catch (_: Exception) {}
        }
    }

    // Initialize MapLibre Style: OpenFreeMap Liberty for daylight, OpenFreeMap Dark for night/cockpit
    val targetStyleUrl = if (isDarkMode) {
        "https://tiles.openfreemap.org/styles/dark"
    } else {
        "https://tiles.openfreemap.org/styles/liberty"
    }
    var appliedStyleUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.isCompassEnabled = true
            map.uiSettings.setCompassMargins(0, (96 * context.resources.displayMetrics.density).roundToInt(), (16 * context.resources.displayMetrics.density).roundToInt(), 0)
            map.uiSettings.isRotateGesturesEnabled = true
            map.uiSettings.isTiltGesturesEnabled = true
            map.uiSettings.isZoomGesturesEnabled = true
            map.uiSettings.isScrollGesturesEnabled = true
            map.uiSettings.setAllGesturesEnabled(true)

            map.addOnCameraMoveListener {
                updateScreenLocations(map)
            }
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    followDrivingCamera = false
                    followLocationCamera = false
                    onDrivingViewInterruptedState()
                }
            }
            map.addOnShoveListener(object : MapLibreMap.OnShoveListener {
                override fun onShoveBegin(detector: org.maplibre.android.gestures.ShoveGestureDetector) {
                    followDrivingCamera = false
                    followLocationCamera = false
                    onDrivingViewInterruptedState()
                }

                override fun onShove(detector: org.maplibre.android.gestures.ShoveGestureDetector) = Unit

                override fun onShoveEnd(detector: org.maplibre.android.gestures.ShoveGestureDetector) = Unit
            })
            map.addOnCameraIdleListener {
                updateScreenLocations(map)
            }

            map.addOnMapClickListener {
                followLocationCamera = false
                onDrivingViewInterruptedState()
                false
            }

            map.addOnMapLongClickListener { point ->
                onLongPressMarkState(point.latitude, point.longitude)
                true
            }

            map.setOnMarkerClickListener { marker ->
                val found = currentMembers.firstOrNull { it.displayName == marker.title }
                if (found != null) {
                    onMemberSelectedState(found)
                    return@setOnMarkerClickListener true
                }
                true
            }
        }
    }

    // Apply / change map style dynamically on theme change (Liberty for Day, Dark for Night)
    LaunchedEffect(mapLibreMap, targetStyleUrl) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (appliedStyleUrl == targetStyleUrl) return@LaunchedEffect

        try {
            activeRoutePolylines.forEach { try { map.removePolyline(it) } catch (_: Exception) {} }
            activeRoutePolylines = emptyList()

            activeSharedPolylines.forEach { try { map.removePolyline(it) } catch (_: Exception) {} }
            activeSharedPolylines = emptyList()

            activeMemberMarkers.values.forEach { try { map.removeMarker(it) } catch (_: Exception) {} }
            activeMemberMarkers.clear()
            memberMarkerSignature.clear()
            activeMarkerAnimators.values.forEach { it.cancel() }
            activeMarkerAnimators.clear()
        } catch (_: Exception) {}

        isMapReady = false
        appliedStyleUrl = targetStyleUrl
        map.setStyle(targetStyleUrl) {
            isMapReady = true
            updateScreenLocations(map)
        }
    }

    // Keep screen projection in sync whenever destination, marks, location, or map state changes
    LaunchedEffect(
        isMapReady,
        destination?.latitude,
        destination?.longitude,
        marks,
        isMyLocationActive,
        currentLocation.latitude,
        currentLocation.longitude,
        currentLocation.heading,
        isNavigating
    ) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (isMapReady) {
            updateScreenLocations(map)
        }
    }

    var wasNavigating by remember { mutableStateOf(false) }

    // GPS button: prefer a fix from after the tap; fall back after a short wait
    val locationForRecenter by rememberUpdatedState(currentLocation)
    val navigatingForRecenter by rememberUpdatedState(isNavigating)
    LaunchedEffect(recenterRequestedAt, isMapReady) {
        if (recenterRequestedAt == 0L || !isMapReady) return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        val softDeadline = recenterRequestedAt + 2500L
        val hardDeadline = recenterRequestedAt + 5000L
        while (true) {
            val loc = locationForRecenter
            val hasLoc = loc.latitude != 0.0 || loc.longitude != 0.0
            val fresh = loc.timestamp >= recenterRequestedAt - 500L
            val now = System.currentTimeMillis()
            if (hasLoc && (fresh || loc.isRealGps || now >= softDeadline)) {
                followLocationCamera = true
                if (navigatingForRecenter) {
                    followDrivingCamera = true
                }
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(loc.latitude, loc.longitude))
                            .zoom(if (navigatingForRecenter) drivingViewZoom.toDouble() else 15.5)
                            .tilt(if (navigatingForRecenter) 50.0 else 0.0)
                            .bearing(if (navigatingForRecenter) movementBearing ?: map.cameraPosition.bearing else 0.0)
                            .padding(cameraPadding(map, navigatingForRecenter))
                            .build()
                    ),
                    950
                )
                initialCameraSet = true
                recenterRequestedAt = 0L
                return@LaunchedEffect
            }
            if (now >= hardDeadline) {
                recenterRequestedAt = 0L
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(200)
        }
    }

    // Reset camera to flat 2D when navigation ends or is stopped
    LaunchedEffect(isNavigating) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect
        if (wasNavigating && !isNavigating) {
            val targetLat = if (currentLocation.latitude != 0.0) currentLocation.latitude else (map.cameraPosition.target?.latitude ?: 0.0)
            val targetLng = if (currentLocation.longitude != 0.0) currentLocation.longitude else (map.cameraPosition.target?.longitude ?: 0.0)
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(targetLat, targetLng))
                        .zoom(15.5)
                        .tilt(0.0)
                        .bearing(0.0)
                        .padding(cameraPadding(map, false))
                        .build()
                ),
                950
            )
        }
        wasNavigating = isNavigating
    }

    // Camera follow user location smoothly:
    // 1) In Driving / Navigation mode (3D tilt, bearing, vehicle offset)
    // 2) In Regular mode when followLocationCamera is enabled (2D top-down, centered)
    LaunchedEffect(
        isNavigating,
        currentLocation.latitude,
        currentLocation.longitude,
        movementBearing,
        followDrivingCamera,
        followLocationCamera,
        drivingViewZoom,
        drivingMarkerPosition,
        isLiveConvoyFramingActive,
        members,
        convoyFramingRadiusMeters
    ) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isMapReady || isLiveConvoyFramingActive) return@LaunchedEffect
        val hasSelfLocation = currentLocation.latitude != 0.0 || currentLocation.longitude != 0.0
        if (!hasSelfLocation) return@LaunchedEffect

        if (isNavigating && followDrivingCamera) {
            val effectiveZoom = if (isLiveConvoyFramingActive) {
                var maxDistance = 0.0
                members.forEach { m ->
                    val lat = m.latitude
                    val lng = m.longitude
                    if (m.id != selfClientId && lat != null && lng != null && lat != 0.0 && lng != 0.0 && m.connectionStatus != MemberConnectionStatus.OFFLINE) {
                        val d = ConvoyUtils.distanceMeters(currentLocation.latitude, currentLocation.longitude, lat, lng)
                        if (convoyFramingRadiusMeters <= 0 || d <= convoyFramingRadiusMeters) {
                            if (d > maxDistance) maxDistance = d
                        }
                    }
                }
                if (maxDistance > 80.0) {
                    val logVal = kotlin.math.ln(maxDistance / 80.0) / kotlin.math.ln(2.0)
                    val z = 16.5 - logVal * 0.65
                    z.coerceIn(13.0, 16.5).toFloat()
                } else {
                    drivingViewZoom
                }
            } else {
                drivingViewZoom
            }

            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(currentLocation.latitude, currentLocation.longitude))
                        .zoom(effectiveZoom.toDouble())
                        .tilt(50.0)
                        .bearing(movementBearing ?: map.cameraPosition.bearing)
                        .padding(cameraPadding(map, true))
                        .build()
                ),
                950
            )
        } else if (!isNavigating && followLocationCamera) {
            val currentZoom = if (map.cameraPosition.zoom < 10.0) 15.5 else map.cameraPosition.zoom
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(currentLocation.latitude, currentLocation.longitude))
                        .zoom(currentZoom)
                        .tilt(0.0)
                        .bearing(0.0)
                        .padding(cameraPadding(map, false))
                        .build()
                ),
                950
            )
        }
    }

    // Real-time framing of live convoy members when NOT in driving camera mode (2D overhead overview)
    var lastOverviewFramingTime by remember { mutableStateOf(0L) }
    LaunchedEffect(isLiveConvoyFramingActive, isMapReady, currentLocation.latitude, currentLocation.longitude, members, convoyFramingRadiusMeters, isNavigating, followDrivingCamera) {
        if (!isLiveConvoyFramingActive || !isMapReady) return@LaunchedEffect
        // If navigating with driving camera, the driving camera above handles it with priority!
        if (isNavigating && followDrivingCamera) return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect

        val now = System.currentTimeMillis()
        if (now - lastOverviewFramingTime < 1800L) return@LaunchedEffect
        lastOverviewFramingTime = now

        val hasSelfLocation = currentLocation.latitude != 0.0 || currentLocation.longitude != 0.0
        val livePoints = buildList {
            if (hasSelfLocation) {
                add(LatLng(currentLocation.latitude, currentLocation.longitude))
            }
            members.forEach { m ->
                val lat = m.latitude
                val lng = m.longitude
                if (m.id != selfClientId && lat != null && lng != null && lat != 0.0 && lng != 0.0 && m.connectionStatus != MemberConnectionStatus.OFFLINE) {
                    val withinRadius = if (convoyFramingRadiusMeters > 0 && hasSelfLocation) {
                        val dist = ConvoyUtils.distanceMeters(
                            currentLocation.latitude,
                            currentLocation.longitude,
                            lat,
                            lng
                        )
                        dist <= convoyFramingRadiusMeters
                    } else {
                        true
                    }
                    if (withinRadius) {
                        add(LatLng(lat, lng))
                    }
                }
            }
        }

        if (livePoints.isEmpty()) return@LaunchedEffect

        val densityVal = context.resources.displayMetrics.density
        val padSide = (55 * densityVal).toInt()
        val padTop = (130 * densityVal).toInt()
        val padBottom = (120 * densityVal).toInt()

        if (livePoints.size == 1) {
            val pt = livePoints.first()
            if (convoyFramingRadiusMeters > 0 && hasSelfLocation) {
                val dLat = convoyFramingRadiusMeters / 111320.0
                val cosLat = kotlin.math.cos(Math.toRadians(pt.latitude)).coerceAtLeast(0.1)
                val dLng = convoyFramingRadiusMeters / (111320.0 * cosLat)
                val radiusBounds = LatLngBounds.Builder()
                    .include(LatLng(pt.latitude - dLat, pt.longitude - dLng))
                    .include(LatLng(pt.latitude + dLat, pt.longitude + dLng))
                    .build()
                map.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        radiusBounds,
                        padSide,
                        padTop,
                        padSide,
                        padBottom
                    ),
                    950
                )
            } else {
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(pt)
                            .zoom(15.5)
                            .tilt(0.0)
                            .bearing(0.0)
                            .padding(doubleArrayOf(padSide.toDouble(), padTop.toDouble(), padSide.toDouble(), padBottom.toDouble()))
                            .build()
                    ),
                    950
                )
            }
        } else {
            val builder = LatLngBounds.Builder()
            livePoints.forEach { builder.include(it) }
            val bounds = builder.build()
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(
                    bounds,
                    padSide,
                    padTop,
                    padSide,
                    padBottom
                ),
                950
            )
        }
    }

    // Animate smoothly back to previous view when Live Convoy Framing is toggled off
    LaunchedEffect(isLiveConvoyFramingActive) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect
        if (wasLiveConvoyFraming && !isLiveConvoyFramingActive) {
            if (isNavigating && (currentLocation.latitude != 0.0 || currentLocation.longitude != 0.0)) {
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(currentLocation.latitude, currentLocation.longitude))
                            .zoom(drivingViewZoom.toDouble())
                            .tilt(50.0)
                            .bearing(movementBearing ?: map.cameraPosition.bearing)
                            .padding(cameraPadding(map, true))
                            .build()
                    ),
                    700
                )
            } else if (currentLocation.latitude != 0.0 || currentLocation.longitude != 0.0) {
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(currentLocation.latitude, currentLocation.longitude))
                            .zoom(15.5)
                            .tilt(0.0)
                            .bearing(0.0)
                            .padding(cameraPadding(map, false))
                            .build()
                    ),
                    700
                )
            }
        }
        wasLiveConvoyFraming = isLiveConvoyFramingActive
    }

    LaunchedEffect(isNavigating) {
        if (isNavigating) followDrivingCamera = true
    }

    LaunchedEffect(drivingViewResetToken) {
        if (drivingViewResetToken == 0L) return@LaunchedEffect
        followDrivingCamera = true
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isMapReady || !isNavigating) return@LaunchedEffect
        if (currentLocation.latitude != 0.0 || currentLocation.longitude != 0.0) {
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(currentLocation.latitude, currentLocation.longitude))
                        .zoom(drivingViewZoom.toDouble())
                        .tilt(50.0)
                        .bearing(movementBearing ?: map.cameraPosition.bearing)
                        .padding(cameraPadding(map, true))
                        .build()
                ),
                950
            )
        }
    }

    LaunchedEffect(memberToFocus?.id, isMapReady) {
        val member = memberToFocus ?: return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        val memberLat = member.latitude ?: return@LaunchedEffect
        val memberLng = member.longitude ?: return@LaunchedEffect
        val selfLat = currentLocation.latitude
        val selfLng = currentLocation.longitude
        if ((selfLat == 0.0 && selfLng == 0.0) || (memberLat == 0.0 && memberLng == 0.0)) return@LaunchedEffect

        followLocationCamera = false
        followDrivingCamera = false

        val densityVal = context.resources.displayMetrics.density
        val padSide = (60 * densityVal).toInt()
        val padTop = (130 * densityVal).toInt()
        val padBottom = (140 * densityVal).toInt()

        val bounds = LatLngBounds.Builder()
            .include(LatLng(selfLat, selfLng))
            .include(LatLng(memberLat, memberLng))
            .build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padSide, padTop, padSide, padBottom), 950)
    }

    LaunchedEffect(fitAllRequestedAt, isMapReady) {
        if (fitAllRequestedAt == 0L || !isMapReady) return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        followLocationCamera = false
        followDrivingCamera = false

        val points = buildList {
            if (currentLocation.latitude != 0.0 || currentLocation.longitude != 0.0) {
                add(LatLng(currentLocation.latitude, currentLocation.longitude))
            }
            members.forEach { member ->
                if (member.latitude != null && member.longitude != null && member.latitude != 0.0 && member.longitude != 0.0) {
                    add(LatLng(member.latitude, member.longitude))
                }
            }
        }
        if (points.isEmpty()) return@LaunchedEffect
        val densityVal = context.resources.displayMetrics.density
        val padSide = (70 * densityVal).toInt()
        val padTop = (140 * densityVal).toInt()
        val padBottom = (150 * densityVal).toInt()

        if (points.size == 1) {
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(points.first())
                        .zoom(14.5)
                        .tilt(0.0)
                        .bearing(0.0)
                        .build()
                ),
                950
            )
        } else {
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLng = Double.MAX_VALUE
            var maxLng = -Double.MAX_VALUE
            points.forEach { pt ->
                if (pt.latitude < minLat) minLat = pt.latitude
                if (pt.latitude > maxLat) maxLat = pt.latitude
                if (pt.longitude < minLng) minLng = pt.longitude
                if (pt.longitude > maxLng) maxLng = pt.longitude
            }
            val latDiff = maxLat - minLat
            val lngDiff = maxLng - minLng
            val minSpan = 0.005
            val centerLat = (minLat + maxLat) / 2.0
            val centerLng = (minLng + maxLng) / 2.0
            val effectiveMinLat = if (latDiff < minSpan) centerLat - (minSpan / 2.0) else minLat
            val effectiveMaxLat = if (latDiff < minSpan) centerLat + (minSpan / 2.0) else maxLat
            val effectiveMinLng = if (lngDiff < minSpan) centerLng - (minSpan / 2.0) else minLng
            val effectiveMaxLng = if (lngDiff < minSpan) centerLng + (minSpan / 2.0) else maxLng

            val bounds = LatLngBounds.Builder()
                .include(LatLng(effectiveMinLat, effectiveMinLng))
                .include(LatLng(effectiveMaxLat, effectiveMaxLng))
                .build()
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(bounds, padSide, padTop, padSide, padBottom),
                1000
            )
        }
    }

    // Progress watermarks so the consumed route never reappears if GPS jitters backward
    var routeTrimVertex by remember { mutableIntStateOf(0) }
    var routeTrimSeg by remember { mutableIntStateOf(0) }
    var routeTrimSegVertex by remember { mutableIntStateOf(0) }
    LaunchedEffect(route) {
        routeTrimVertex = 0
        routeTrimSeg = 0
        routeTrimSegVertex = 0
    }

    // 1. Active route: OSRM = self color; Neshan = per-segment traffic colors.
    LaunchedEffect(
        isMapReady,
        route,
        selfColorHex
    ) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect

        try {
            activeRoutePolylines.forEach {
                try { map.removePolyline(it) } catch (_: Exception) {}
            }
            val newLines = mutableListOf<org.maplibre.android.annotations.Polyline>()

            val r = route
            val stretches = when {
                r == null -> emptyList()
                r.segments.isNotEmpty() -> r.segments
                r.points.size >= 2 -> listOf(
                    RouteSegment(points = r.points, colorHex = selfColorHex)
                )
                else -> emptyList()
            }

            stretches.forEach { stretch ->
                if (stretch.points.size < 2) return@forEach
                val latLngs = stretch.points.map { LatLng(it.latitude, it.longitude) }
                val colorInt = try {
                    android.graphics.Color.parseColor(stretch.colorHex)
                } catch (_: Exception) {
                    android.graphics.Color.parseColor("#0EA5E9")
                }
                newLines.add(
                    map.addPolyline(
                        PolylineOptions()
                            .addAll(latLngs)
                            .color(colorInt)
                            .width(6f)
                    )
                )
            }
            activeRoutePolylines = newLines
        } catch (e: Exception) {
            android.util.Log.e("ConvoyMapView", "Failed to update active route polyline", e)
        }
    }

    // 2. Shared routes: traffic segments if present, else owner's unique color
    LaunchedEffect(isMapReady, sharedRoutes, members, selfClientId, selfColorHex) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect

        try {
            val desired = mutableListOf<Pair<List<LatLng>, Int>>()
            val colorById = members.associate { it.id to (it.avatarColor ?: "#0EA5E9") }
            sharedRoutes.values.forEach { sr ->
                if (sr.clientId == selfClientId) return@forEach
                val ownerHex = colorById[sr.clientId] ?: sr.colorHex
                val stretches = when {
                    sr.segments.isNotEmpty() -> sr.segments
                    sr.points.size >= 2 -> listOf(
                        RouteSegment(points = sr.points, colorHex = ownerHex)
                    )
                    else -> emptyList()
                }
                stretches.forEach { stretch ->
                    if (stretch.points.size < 2) return@forEach
                    val latLngs = stretch.points.map { LatLng(it.latitude, it.longitude) }
                    val c = try {
                        android.graphics.Color.parseColor(stretch.colorHex)
                    } catch (_: Exception) {
                        android.graphics.Color.parseColor("#F59E0B")
                    }
                    desired.add(latLngs to c)
                }
            }
            if (desired.size == activeSharedPolylines.size) {
                desired.forEachIndexed { index, (points, color) ->
                    val line = activeSharedPolylines[index]
                    line.points = points
                    line.color = color
                    line.width = 4.5f
                    map.updatePolyline(line)
                }
            } else {
                activeSharedPolylines.forEach {
                    try { map.removePolyline(it) } catch (_: Exception) {}
                }
                activeSharedPolylines = desired.map { (points, color) ->
                    map.addPolyline(
                        PolylineOptions().addAll(points).color(color).width(4.5f)
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ConvoyMapView", "Failed to update shared polylines", e)
        }
    }

    // 3. Map marks are Compose RadiatingTargetMarker overlays (same size/pulse as destination)

    // Retain member location cache and keep screen projections updated on list changes
    LaunchedEffect(members, selfClientId) {
        val currentIds = members.map { it.id }.toSet()
        memberLastKnownLocations.keys.retainAll(currentIds)
        mapLibreMap?.let { updateScreenLocations(it) }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat().coerceAtLeast(1080f)
        val screenH = constraints.maxHeight.toFloat().coerceAtLeast(1920f)

        // Real MapLibre Map View
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (lastMapSize != IntSize.Zero && size != lastMapSize) {
                        onDrivingViewInterrupted()
                    }
                    lastMapSize = size
                }
                .testTag("convoy_map_view")
        )

        // Self Location Puck — always visible when location is known (Maps / Neshan style)
        if (currentLocation.latitude != 0.0 || currentLocation.longitude != 0.0) {
            val density = LocalDensity.current
            val pt = selfScreenPoint

            if (pt != null && (isNavigating || (pt.x in -200f..(screenW + 200f) && pt.y in -200f..(screenH + 200f)))) {
                val userColor = try {
                    Color(android.graphics.Color.parseColor(selfColorHex))
                } catch (_: Exception) {
                    CaravanBlue
                }

                SelfPuckOrVehicleArrow(
                    isNavigating = isNavigating,
                    heading = currentLocation.heading,
                    mapBearing = currentMapBearing,
                    mapTilt = currentMapTilt,
                    userColor = userColor,
                    initial = selfDisplayName.trim().take(1).uppercase().ifEmpty { "•" },
                    modifier = Modifier.absoluteOffset {
                        val sizeDp = 52.dp
                        val halfPx = with(density) { (sizeDp / 2f).toPx() }
                        IntOffset(
                            x = (pt.x - halfPx).roundToInt(),
                            y = (pt.y - halfPx).roundToInt()
                        )
                    }
                )
            }
        }

        // Convoy Member Markers (Compose Overlays - flicker-free, stable, smooth rendering)
        val validMembers = members.filter { it.id != selfClientId }
        validMembers.forEach { member ->
            val pt = memberScreenPoints[member.id]
            if (pt != null && (pt.x in -300f..(screenW + 300f) && pt.y in -300f..(screenH + 300f))) {
                key(member.id) {
                    ConvoyMemberOverlayMarker(
                        member = member,
                        screenPoint = pt,
                        mapTilt = currentMapTilt,
                        mapBearing = currentMapBearing,
                        onClick = { onMemberSelected(member) }
                    )
                }
            }
        }

        // Destination: initial circle + radiating rings (single marker)
        destination?.let { dest ->
            destScreenPoint?.let { pt ->
                val density = LocalDensity.current
                val beaconSizeDp = 100.dp
                val halfBeaconPx = with(density) { (beaconSizeDp / 2f).toPx() }
                val targetColor = try {
                    val ownerId = dest.updatedById
                    val ownerHex = when {
                        ownerId.isBlank() || ownerId == selfClientId -> selfColorHex
                        else -> members.find { it.id == ownerId }?.avatarColor
                            ?: dest.colorHex
                            ?: selfColorHex
                    }
                    Color(android.graphics.Color.parseColor(ownerHex))
                } catch (_: Exception) {
                    Color(0xFF0EA5E9)
                }
                val destInitial = dest.updatedByName
                    .ifBlank { selfDisplayName }
                    .trim().take(1).uppercase().ifEmpty { "•" }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                ) {
                    RadiatingTargetMarker(
                        color = targetColor,
                        initial = destInitial,
                        modifier = Modifier
                            .absoluteOffset {
                                IntOffset(
                                    x = (pt.x - halfBeaconPx).roundToInt(),
                                    y = (pt.y - halfBeaconPx).roundToInt()
                                )
                            }
                    )
                }
            }
        }

        // Other convoy marks: same small radiating beacon as destination
        if (marks.isNotEmpty()) {
            val density = LocalDensity.current
            val beaconSizeDp = 100.dp
            val halfBeaconPx = with(density) { (beaconSizeDp / 2f).toPx() }
            val screenW = constraints.maxWidth.toFloat()
            val screenH = constraints.maxHeight.toFloat()
            val dest = destination
            val colorById = members.associate { it.id to (it.avatarColor ?: "#0EA5E9") }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
            ) {
                marks.forEach { (markId, mark) ->
                    if (dest != null &&
                        kotlin.math.abs(mark.latitude - dest.latitude) < 1e-6 &&
                        kotlin.math.abs(mark.longitude - dest.longitude) < 1e-6
                    ) {
                        return@forEach
                    }
                    val pt = markScreenPoints[markId] ?: return@forEach
                    if (pt.x !in -120f..(screenW + 120f) || pt.y !in -120f..(screenH + 120f)) {
                        return@forEach
                    }
                    val markHex = when {
                        mark.clientId == selfClientId -> selfColorHex
                        else -> colorById[mark.clientId] ?: mark.color
                    }
                    val markColor = try {
                        Color(android.graphics.Color.parseColor(markHex))
                    } catch (_: Exception) {
                        Color(0xFF0EA5E9)
                    }
                    val initial = mark.displayName.trim().take(1).uppercase().ifEmpty { "•" }

                    key(markId) {
                        RadiatingTargetMarker(
                            color = markColor,
                            initial = initial,
                            modifier = Modifier
                                .absoluteOffset {
                                    IntOffset(
                                        x = (pt.x - halfBeaconPx).roundToInt(),
                                        y = (pt.y - halfBeaconPx).roundToInt()
                                    )
                                }
                                .clickable {
                                    onMarkSelected?.invoke(mark)
                                }
                        )
                    }
                }
            }
        }

        // Loading Overlay while OpenFreeMap style loads
        if (!isMapReady) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(18.dp),
                        color = CaravanAmber
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Loading OpenFreeMap Basemap...",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Floating Action Buttons & Route Provider Selectors (Bottom Area)
        // Positioned 110dp above navigation bars so they sit cleanly above the PTT bar and Chat FAB
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 110.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column(
                horizontalAlignment = Alignment.Start
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmallFloatingActionButton(
                        onClick = onToggleAllMembersMute,
                        containerColor = if (allMembersMuted) CaravanBlue else if (isDarkMode) NightSlateCard else Color.White,
                        contentColor = if (allMembersMuted) Color.White else MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("map_mute_all")
                    ) {
                        Icon(
                            if (allMembersMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = if (allMembersMuted) "Unmute all members" else "Mute all members",
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    SmallFloatingActionButton(
                        onClick = onToggleFreeDriving,
                        containerColor = if (isNavigating) Color(0xFF10B981) else if (isDarkMode) NightSlateCard else Color.White,
                        contentColor = if (isNavigating) Color.White else MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("map_free_driving_toggle")
                    ) {
                        Icon(
                            Icons.Default.DirectionsCar,
                            contentDescription = if (isNavigating) "Exit Driving Mode" else "Free Driving Mode",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    SmallFloatingActionButton(
                        onClick = {
                            if (isLiveConvoyFramingActive) {
                                onToggleLiveConvoyFraming()
                            } else {
                                onFitAll()
                            }
                        },
                        containerColor = if (isLiveConvoyFramingActive) Color(0xFF3B82F6) else if (isDarkMode) NightSlateCard else Color.White,
                        contentColor = if (isLiveConvoyFramingActive) Color.White else MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape,
                        modifier = Modifier
                            .heightIn(min = 40.dp, max = 48.dp)
                            .size(40.dp)
                            .testTag("map_live_convoy_framing_toggle")
                    ) {
                        Icon(
                            Icons.Default.FitScreen,
                            contentDescription = if (isLiveConvoyFramingActive) "Reset to Normal View" else "See All Members",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    if (showReturnToDriving) {
                        val progress = returnToDrivingProgress.coerceIn(0f, 1f)
                        Surface(
                            onClick = onReturnToDriving,
                            shape = RoundedCornerShape(10.dp),
                            color = CaravanBlue,
                            contentColor = Color.White,
                            modifier = Modifier
                                .width(80.dp)
                                .height(40.dp)
                                .testTag("btn_return_to_driving")
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(progress)
                                        .background(Color(0xFF0284C7))
                                )
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Return to",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        lineHeight = 10.sp,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "Driving Mode",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        lineHeight = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Right: Zoom In, Zoom Out, and Recenter
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End
            ) {
                // Zoom In
                SmallFloatingActionButton(
                    onClick = { mapLibreMap?.animateCamera(CameraUpdateFactory.zoomIn()) },
                    containerColor = if (isDarkMode) NightSlateCard else Color.White,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("map_zoom_in")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Zoom In", modifier = Modifier.size(20.dp))
                }

                // Zoom Out
                SmallFloatingActionButton(
                    onClick = { mapLibreMap?.animateCamera(CameraUpdateFactory.zoomOut()) },
                    containerColor = if (isDarkMode) NightSlateCard else Color.White,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("map_zoom_out")
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Zoom Out", modifier = Modifier.size(20.dp))
                }

                // GPS / my-location — Neshan / Maps style:
                // Camera follows ONLY when tapped; manual drag or gesture disables follow
                val isCameraFollowing = if (isNavigating) followDrivingCamera else followLocationCamera
                SmallFloatingActionButton(
                    onClick = {
                        if (onMyLocationClick()) {
                            followLocationCamera = true
                            if (isNavigating) {
                                followDrivingCamera = true
                            }
                            recenterRequestedAt = System.currentTimeMillis()
                        }
                    },
                    containerColor = if (isCameraFollowing) {
                        CaravanBlue
                    } else if (isDarkMode) {
                        NightSlateCard
                    } else {
                        Color.White
                    },
                    contentColor = if (isCameraFollowing) Color.White else {
                        if (isDarkMode) Color.White.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    },
                    shape = CircleShape,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("map_recenter")
                ) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = "My location",
                        modifier = Modifier.size(22.dp)
                    )
                }

            }
        }
    }
}

/**
 * Creates convoy member markers with sleek drop shadow, white border, member color, initial,
 * and a clear label pill displaying their name and (if stale) time ago indicator (e.g. "Reza • 2m ago").
 */
private fun createVehicleMarkerIcon(
    context: Context,
    name: String,
    colorHex: String,
    timeAgo: String? = null
): Icon? {
    return try {
        val density = context.resources.displayMetrics.density
        val isStale = timeAgo != null

        val memberColor = try {
            android.graphics.Color.parseColor(colorHex)
        } catch (_: Exception) {
            android.graphics.Color.parseColor("#0EA5E9")
        }

        val paint = Paint().apply { isAntiAlias = true }

        // Setup label text
        val nameText = name.trim().ifEmpty { "Member" }
        val labelText = if (isStale) "$nameText • $timeAgo" else nameText
        val textSizePx = 11f * density
        paint.textSize = textSizePx
        paint.typeface = Typeface.DEFAULT_BOLD

        val textWidth = paint.measureText(labelText)
        val pillPaddingH = 8f * density
        val pillWidth = textWidth + (pillPaddingH * 2f)
        val pillHeight = 18f * density

        val circleRadius = 14f * density
        val totalWidth = Math.max(pillWidth + (12f * density), (circleRadius * 2f) + (16f * density))
        val totalHeight = (circleRadius * 2f) + pillHeight + (8f * density)

        val wPx = totalWidth.toInt()
        val hPx = totalHeight.toInt()
        val bitmap = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        val cx = wPx / 2f
        val pillTop = 2f * density
        val pillBottom = pillTop + pillHeight
        val circleCenterY = pillBottom + (3f * density) + circleRadius

        // 1. Draw Pill Shadow
        paint.color = android.graphics.Color.parseColor("#40000000")
        paint.style = Paint.Style.FILL
        val pillRect = RectF(cx - (pillWidth / 2f), pillTop, cx + (pillWidth / 2f), pillBottom)
        canvas.drawRoundRect(pillRect, pillHeight / 2f, pillHeight / 2f, paint)

        // 2. Pill Body
        paint.color = android.graphics.Color.parseColor("#EE0F172A") // Deep dark slate
        canvas.drawRoundRect(pillRect, pillHeight / 2f, pillHeight / 2f, paint)

        // 3. Pill Border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = if (isStale) android.graphics.Color.parseColor("#F59E0B") else android.graphics.Color.parseColor("#475569")
        canvas.drawRoundRect(pillRect, pillHeight / 2f, pillHeight / 2f, paint)

        // 4. Draw Pill Text
        paint.style = Paint.Style.FILL
        val textY = pillTop + (pillHeight / 2f) - ((paint.descent() + paint.ascent()) / 2f)

        if (isStale) {
            val bulletTime = " • $timeAgo"
            val totalTextW = paint.measureText(labelText)
            var startX = cx - (totalTextW / 2f)

            paint.color = android.graphics.Color.WHITE
            canvas.drawText(nameText, startX, textY, paint)
            startX += paint.measureText(nameText)

            paint.color = android.graphics.Color.parseColor("#FBBF24")
            canvas.drawText(bulletTime, startX, textY, paint)
        } else {
            paint.color = android.graphics.Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(nameText, cx, textY, paint)
        }

        // 5. Circle Shadow
        paint.textAlign = Paint.Align.CENTER
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.parseColor("#44000000")
        canvas.drawCircle(cx, circleCenterY + (1.5f * density), circleRadius, paint)

        // 6. Outer Border
        paint.color = if (isStale) android.graphics.Color.parseColor("#F59E0B") else android.graphics.Color.WHITE
        canvas.drawCircle(cx, circleCenterY, circleRadius, paint)

        // 7. Inner Member Color
        paint.color = memberColor
        canvas.drawCircle(cx, circleCenterY, circleRadius - (2.2f * density), paint)

        // 8. Member Initial
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 12f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        val letter = nameText.take(1).uppercase()
        val letterY = circleCenterY - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(letter, cx, letterY, paint)

        IconFactory.getInstance(context).fromBitmap(bitmap)
    } catch (e: Exception) {
        null
    }
}

/**
 * Destination / mark marker: user-initial circle with radiating rings.
 */
@Composable
fun RadiatingTargetMarker(
    color: Color,
    initial: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "target_radiating_beacon")

    val wave1Progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave1"
    )

    val wave2Progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, delayMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave2"
    )

    Box(
        modifier = modifier.size(100.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val coreR = 8.dp.toPx()

            val r1 = coreR + (wave1Progress * 32.dp.toPx())
            drawCircle(
                color = color.copy(alpha = (1f - wave1Progress).coerceIn(0f, 1f) * 0.85f),
                radius = r1,
                center = center,
                style = Stroke(width = 2.0.dp.toPx())
            )

            val r2 = coreR + (wave2Progress * 32.dp.toPx())
            drawCircle(
                color = color.copy(alpha = (1f - wave2Progress).coerceIn(0f, 1f) * 0.85f),
                radius = r2,
                center = center,
                style = Stroke(width = 1.6.dp.toPx())
            )

            drawCircle(
                color = Color.Black.copy(alpha = 0.3f),
                radius = coreR,
                center = Offset(center.x, center.y + 1.2.dp.toPx())
            )
            drawCircle(color = Color.White, radius = coreR + 1.8.dp.toPx(), center = center)
            drawCircle(color = color, radius = coreR, center = center)
        }

        Text(
            text = initial,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Self overlay: same initial circle + facing triangle (O>) in idle and driving.
 * While navigating, pitch the puck to match the map's 3D tilt so it sits on the ground plane.
 */
@Composable
fun SelfPuckOrVehicleArrow(
    isNavigating: Boolean,
    heading: Double,
    mapBearing: Double,
    mapTilt: Double,
    userColor: Color,
    initial: String,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val relativeHeading = ((heading - mapBearing + 360.0) % 360.0).toFloat()
    val markerSize = 52.dp
    val coreRadius = 12.dp

    Box(
        modifier = modifier
            .size(markerSize)
            .graphicsLayer {
                if (isNavigating || mapTilt > 0.0) {
                    rotationX = mapTilt.toFloat()
                    cameraDistance = 16f * density.density
                    transformOrigin = TransformOrigin(0.5f, 0.85f)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val coreR = coreRadius.toPx()

            rotate(degrees = if (isNavigating) 0f else relativeHeading, pivot = Offset(cx, cy)) {
                // Small forward triangle (O>) — tip points in heading direction
                val baseY = cy - coreR - 1.dp.toPx()
                val tri = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx, baseY - 9.dp.toPx())
                    lineTo(cx + 5.5.dp.toPx(), baseY)
                    lineTo(cx - 5.5.dp.toPx(), baseY)
                    close()
                }
                drawPath(path = tri, color = userColor)
                drawPath(
                    path = tri,
                    color = Color.White,
                    style = Stroke(width = 1.5.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round)
                )
            }

            drawCircle(
                color = Color.Black.copy(alpha = 0.28f),
                radius = coreR,
                center = Offset(cx, cy + 1.2.dp.toPx())
            )
            drawCircle(color = Color.White, radius = coreR + 2.2.dp.toPx(), center = Offset(cx, cy))
            drawCircle(color = userColor, radius = coreR, center = Offset(cx, cy))
        }

        Text(
            text = initial,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * High-performance, flicker-free convoy member marker rendered as a Compose overlay.
 * Supports 3D perspective tilt aligned with the map ground plane and directional heading pointer.
 */
@Composable
fun ConvoyMemberOverlayMarker(
    member: TripMember,
    screenPoint: PointF,
    mapTilt: Double,
    mapBearing: Double = 0.0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val mColor = try {
        Color(android.graphics.Color.parseColor(member.avatarColor ?: "#0EA5E9"))
    } catch (_: Exception) {
        CaravanBlue
    }
    val latestTs = maxOf(member.lastLocationAt ?: 0L, member.lastSeenAt)
    val isStale = member.connectionStatus != MemberConnectionStatus.CONNECTED
    val timeAgo = if (isStale) ConvoyUtils.formatTimeAgo(latestTs) else null
    val labelText = when {
        member.connectionStatus == MemberConnectionStatus.OFFLINE && timeAgo != null -> "${member.displayName} • $timeAgo"
        member.connectionStatus == MemberConnectionStatus.OFFLINE -> "${member.displayName} • offline"
        member.connectionStatus == MemberConnectionStatus.RECONNECTING && timeAgo != null -> "${member.displayName} • $timeAgo"
        isStale && timeAgo != null -> "${member.displayName} • $timeAgo"
        else -> member.displayName
    }

    val hasHeading = member.heading != null && !member.isPerson
    val relativeHeading = if (hasHeading) {
        ((member.heading!! - mapBearing + 360.0) % 360.0).toFloat()
    } else 0f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .absoluteOffset {
                val halfW = with(density) { 50.dp.toPx() }
                val totalH = with(density) { 62.dp.toPx() }
                IntOffset(
                    x = (screenPoint.x - halfW).roundToInt(),
                    y = (screenPoint.y - totalH).roundToInt()
                )
            }
            .width(100.dp)
            .graphicsLayer {
                if (mapTilt > 0.0) {
                    rotationX = mapTilt.toFloat()
                    cameraDistance = 16f * density.density
                    transformOrigin = TransformOrigin(0.5f, 1.0f)
                }
            }
            .clickable(onClick = onClick)
    ) {
        // Label Pill
        Surface(
            color = Color(0xEE0F172A),
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(
                1.dp,
                if (isStale) Color(0xFFF59E0B) else Color(0xFF475569)
            ),
            shadowElevation = 4.dp
        ) {
            Text(
                text = labelText,
                color = if (isStale) Color(0xFFFBBF24) else Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Vehicle / Member Circular Badge with optional directional heading indicator
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp)
        ) {
            if (hasHeading) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val coreR = 15.dp.toPx()

                    rotate(degrees = relativeHeading, pivot = Offset(cx, cy)) {
                        val baseY = cy - coreR - 1.dp.toPx()
                        val tri = androidx.compose.ui.graphics.Path().apply {
                            moveTo(cx, baseY - 6.dp.toPx())
                            lineTo(cx + 4.5.dp.toPx(), baseY)
                            lineTo(cx - 4.5.dp.toPx(), baseY)
                            close()
                        }
                        drawPath(path = tri, color = if (isStale) Color(0xFFF59E0B) else mColor)
                        drawPath(
                            path = tri,
                            color = Color.White,
                            style = Stroke(width = 1.2.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round)
                        )
                    }
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        color = if (isStale) Color(0xFFF59E0B) else Color.White,
                        shape = CircleShape
                    )
                    .padding(2.dp)
                    .background(color = mColor, shape = CircleShape)
            ) {
                if (member.isPerson) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                } else {
                    Text(
                        text = member.displayName.trim().take(1).uppercase().ifEmpty { "•" },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
