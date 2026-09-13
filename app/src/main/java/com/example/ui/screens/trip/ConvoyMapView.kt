package com.example.ui.screens.trip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.data.location.DeviceLocation
import com.example.data.model.MapMark
import com.example.data.model.SharedRoute
import com.example.data.model.TripDestination
import com.example.data.model.TripMember
import com.example.data.network.RouteResult
import com.example.ui.theme.*
import com.example.ui.viewmodel.RoutingProvider
import com.example.util.ConvoyUtils
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
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
    activeRoutingProvider: RoutingProvider,
    isCalculatingRoute: Boolean,
    isDarkMode: Boolean,
    isNavigating: Boolean = false,
    activeSpeakerName: String?,
    selfColorHex: String = "#0EA5E9",
    selfClientId: String = "",
    selfDisplayName: String = "",
    marks: Map<String, MapMark> = emptyMap(),
    sharedRoutes: Map<String, SharedRoute> = emptyMap(),
    onLongPressMark: (latitude: Double, longitude: Double) -> Unit,
    onMemberSelected: (TripMember) -> Unit,
    onMarkSelected: ((MapMark) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var isMapReady by remember { mutableStateOf(false) }
    var initialCameraSet by remember { mutableStateOf(false) }

    // Retained map annotation references to eliminate flickering, GPU stalls, and render lag
    var activeRoutePolyline by remember { mutableStateOf<org.maplibre.android.annotations.Polyline?>(null) }
    var activeSharedPolylines by remember { mutableStateOf<List<org.maplibre.android.annotations.Polyline>>(emptyList()) }
    var activeMarkMarkers by remember { mutableStateOf<List<org.maplibre.android.annotations.Marker>>(emptyList()) }
    var activeMemberMarkers by remember { mutableStateOf<List<org.maplibre.android.annotations.Marker>>(emptyList()) }

    var destScreenPoint by remember { mutableStateOf<PointF?>(null) }
    var selfScreenPoint by remember { mutableStateOf<PointF?>(null) }
    var currentMapBearing by remember { mutableDoubleStateOf(0.0) }
    val currentDestination by rememberUpdatedState(destination)

    fun updateScreenLocations(map: MapLibreMap?) {
        val m = map ?: mapLibreMap ?: return
        try {
            currentMapBearing = m.cameraPosition.bearing
            val dest = currentDestination
            if (dest != null) {
                destScreenPoint = m.projection.toScreenLocation(LatLng(dest.latitude, dest.longitude))
            } else {
                destScreenPoint = null
            }
            if (currentLocation.latitude != 0.0 || currentLocation.longitude != 0.0) {
                selfScreenPoint = m.projection.toScreenLocation(LatLng(currentLocation.latitude, currentLocation.longitude))
            } else {
                selfScreenPoint = null
            }
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
                mapView.onDestroy()
            } catch (_: Exception) {}
        }
    }

    // Initialize MapLibre Style (OpenFreeMap Liberty - exactly matches Flutter v1)
    val mapStyleUrl = "https://tiles.openfreemap.org/styles/liberty"
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.setStyle(mapStyleUrl) {
                isMapReady = true
                updateScreenLocations(map)
            }
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.isCompassEnabled = true
            map.uiSettings.isRotateGesturesEnabled = true
            map.uiSettings.isTiltGesturesEnabled = true
            map.uiSettings.isZoomGesturesEnabled = true
            map.uiSettings.isScrollGesturesEnabled = true

            map.addOnCameraMoveListener {
                updateScreenLocations(map)
            }
            map.addOnCameraIdleListener {
                updateScreenLocations(map)
            }

            map.addOnMapLongClickListener { point ->
                onLongPressMark(point.latitude, point.longitude)
                true
            }

            map.setOnMarkerClickListener { marker ->
                val found = members.firstOrNull { it.displayName == marker.title }
                if (found != null) {
                    onMemberSelected(found)
                    return@setOnMarkerClickListener true
                }
                val foundMark = marks.values.firstOrNull { it.displayName == marker.title }
                if (foundMark != null) {
                    onMarkSelected?.invoke(foundMark)
                    return@setOnMarkerClickListener true
                }
                true
            }
        }
    }

    // Keep screen projection in sync whenever destination, location, or map state changes
    LaunchedEffect(
        isMapReady,
        destination?.latitude,
        destination?.longitude,
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

    // Initial camera placement on user location
    LaunchedEffect(isMapReady, currentLocation.latitude, currentLocation.longitude) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect
        if (!initialCameraSet && (currentLocation.latitude != 0.0 || currentLocation.longitude != 0.0)) {
            initialCameraSet = true
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(currentLocation.latitude, currentLocation.longitude))
                .zoom(14.5)
                .build()
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
                        .zoom(15.0)
                        .tilt(0.0)
                        .bearing(0.0)
                        .build()
                ),
                800
            )
        }
        wasNavigating = isNavigating
    }

    // Follow user location smoothly when navigating (3D tilt)
    LaunchedEffect(isNavigating, currentLocation.latitude, currentLocation.longitude, currentLocation.heading) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect
        if (isNavigating && (currentLocation.latitude != 0.0 || currentLocation.longitude != 0.0)) {
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(currentLocation.latitude, currentLocation.longitude))
                        .zoom(16.5)
                        .tilt(50.0)
                        .bearing(currentLocation.heading)
                        .build()
                ),
                350
            )
        }
    }

    // 1. Synchronize Active Route polyline (ONLY when route, routing provider, or theme changes)
    LaunchedEffect(isMapReady, route, activeRoutingProvider, selfColorHex) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect

        try {
            activeRoutePolyline?.let {
                try { map.removePolyline(it) } catch (_: Exception) {}
            }
            activeRoutePolyline = null

            route?.points?.let { pts ->
                if (pts.size >= 2) {
                    val latLngs = pts.map { LatLng(it.latitude, it.longitude) }
                    val routeColorInt = if (activeRoutingProvider == RoutingProvider.NESHAN) {
                        android.graphics.Color.parseColor("#10B981") // Neshan traffic green
                    } else {
                        try {
                            android.graphics.Color.parseColor(selfColorHex)
                        } catch (_: Exception) {
                            android.graphics.Color.parseColor("#0EA5E9")
                        }
                    }
                    activeRoutePolyline = map.addPolyline(
                        PolylineOptions()
                            .addAll(latLngs)
                            .color(routeColorInt)
                            .width(6f)
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ConvoyMapView", "Failed to update active route polyline", e)
        }
    }

    // 2. Synchronize Shared Routes from convoy members
    LaunchedEffect(isMapReady, sharedRoutes) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect

        try {
            activeSharedPolylines.forEach {
                try { map.removePolyline(it) } catch (_: Exception) {}
            }
            val newPolylines = mutableListOf<org.maplibre.android.annotations.Polyline>()
            sharedRoutes.values.forEach { sr ->
                if (sr.clientId != selfClientId && sr.points.size >= 2) {
                    val latLngs = sr.points.map { LatLng(it.latitude, it.longitude) }
                    val c = try {
                        android.graphics.Color.parseColor(sr.colorHex)
                    } catch (_: Exception) {
                        android.graphics.Color.parseColor("#F59E0B")
                    }
                    newPolylines.add(
                        map.addPolyline(
                            PolylineOptions()
                                .addAll(latLngs)
                                .color(c)
                                .width(4.5f)
                        )
                    )
                }
            }
            activeSharedPolylines = newPolylines
        } catch (e: Exception) {
            android.util.Log.e("ConvoyMapView", "Failed to update shared polylines", e)
        }
    }

    // 3. Synchronize Map Marks (skip dest duplicate — radiating overlay owns that spot)
    LaunchedEffect(isMapReady, marks, destination?.latitude, destination?.longitude) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect

        try {
            activeMarkMarkers.forEach {
                try { map.removeMarker(it) } catch (_: Exception) {}
            }
            val newMarkers = mutableListOf<org.maplibre.android.annotations.Marker>()
            val dest = destination
            marks.values.forEach { mark ->
                if (dest != null &&
                    kotlin.math.abs(mark.latitude - dest.latitude) < 1e-6 &&
                    kotlin.math.abs(mark.longitude - dest.longitude) < 1e-6
                ) {
                    return@forEach
                }
                val icon = createMarkPinIcon(context, mark.displayName, mark.color)
                val opt = MarkerOptions()
                    .position(LatLng(mark.latitude, mark.longitude))
                    .title(mark.displayName)
                if (icon != null) opt.icon = icon
                newMarkers.add(map.addMarker(opt))
            }
            activeMarkMarkers = newMarkers
        } catch (e: Exception) {
            android.util.Log.e("ConvoyMapView", "Failed to update marks", e)
        }
    }

    // 4. Synchronize Convoy Member Markers
    LaunchedEffect(isMapReady, members, selfClientId) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect

        try {
            activeMemberMarkers.forEach {
                try { map.removeMarker(it) } catch (_: Exception) {}
            }
            val newMemberMarkers = mutableListOf<org.maplibre.android.annotations.Marker>()
            members.forEach { member ->
                if (member.id == selfClientId) return@forEach
                val mLat = member.latitude
                val mLng = member.longitude
                if (mLat != null && mLng != null && mLat != 0.0 && mLng != 0.0) {
                    val mColor = member.avatarColor ?: "#0EA5E9"
                    val timeAgo = ConvoyUtils.formatTimeAgo(member.lastLocationAt ?: member.lastSeenAt)
                    val mIcon = createVehicleMarkerIcon(context, member.displayName, mColor, timeAgo)
                    val opt = MarkerOptions()
                        .position(LatLng(mLat, mLng))
                        .title(member.displayName)
                        .snippet(if (timeAgo != null) "Last seen $timeAgo" else "Speed: ${((member.speed ?: 0.0) * 3.6).toInt()} km/h")
                    if (mIcon != null) opt.icon = mIcon
                    newMemberMarkers.add(map.addMarker(opt))
                }
            }
            activeMemberMarkers = newMemberMarkers
        } catch (e: Exception) {
            android.util.Log.e("ConvoyMapView", "Failed to update member markers", e)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Real MapLibre Map View
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxSize()
                .testTag("convoy_map_view")
        )

        // Self Location Puck / 3D Vehicle Navigation Arrow Overlay
        // Immune to orientation changes, 3D tilt clipping, and map recreation
        if (currentLocation.latitude != 0.0 || currentLocation.longitude != 0.0) {
            val density = LocalDensity.current
            val screenW = constraints.maxWidth.toFloat()
            val screenH = constraints.maxHeight.toFloat()
            val fallbackPt = PointF(screenW / 2f, screenH / 2f)
            val pt = if (isNavigating) {
                selfScreenPoint ?: fallbackPt
            } else {
                selfScreenPoint
            }

            if (pt != null && (isNavigating || (pt.x in -120f..(screenW + 120f) && pt.y in -120f..(screenH + 120f)))) {
                val userColor = try {
                    Color(android.graphics.Color.parseColor(selfColorHex))
                } catch (_: Exception) {
                    CaravanBlue
                }

                SelfPuckOrVehicleArrow(
                    isNavigating = isNavigating,
                    heading = currentLocation.heading,
                    mapBearing = currentMapBearing,
                    userColor = userColor,
                    initial = selfDisplayName.trim().take(1).uppercase().ifEmpty { "•" },
                    modifier = Modifier.offset {
                        val sizeDp = if (isNavigating) 60.dp else 52.dp
                        val halfPx = with(density) { (sizeDp / 2f).toPx() }
                        IntOffset(
                            x = (pt.x - halfPx).roundToInt(),
                            y = (pt.y - halfPx).roundToInt()
                        )
                    }
                )
            }
        }

        // Destination: initial circle + radiating rings (single marker)
        destination?.let { dest ->
            destScreenPoint?.let { pt ->
                val density = LocalDensity.current
                val beaconSizeDp = 130.dp
                val halfBeaconPx = with(density) { (beaconSizeDp / 2f).toPx() }
                val targetColor = try {
                    Color(android.graphics.Color.parseColor(dest.colorHex ?: selfColorHex))
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
                            .offset {
                                IntOffset(
                                    x = (pt.x - halfBeaconPx).roundToInt(),
                                    y = (pt.y - halfBeaconPx).roundToInt()
                                )
                            }
                    )
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

        // Active Speaker Radio Indicator (Top HUD)
        AnimatedVisibility(
            visible = activeSpeakerName != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 70.dp)
        ) {
            Surface(
                color = CaravanEmerald.copy(alpha = 0.92f),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 8.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Active Speaker",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$activeSpeakerName is talking...",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
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
            Spacer(modifier = Modifier.weight(1f))

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

                // Recenter GPS
                SmallFloatingActionButton(
                    onClick = {
                        if (currentLocation.latitude != 0.0 || currentLocation.longitude != 0.0) {
                            mapLibreMap?.animateCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(LatLng(currentLocation.latitude, currentLocation.longitude))
                                        .zoom(if (isNavigating) 16.5 else 15.0)
                                        .tilt(if (isNavigating) 50.0 else 0.0)
                                        .bearing(if (isNavigating) currentLocation.heading else 0.0)
                                        .build()
                                ),
                                800
                            )
                        }
                    },
                    containerColor = if (isDarkMode) NightSlateCard else Color.White,
                    contentColor = CaravanBlue,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("map_recenter")
                ) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = "Recenter",
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
 * Creates user-placed map mark pin icons.
 */
private fun createMarkPinIcon(
    context: Context,
    label: String,
    colorHex: String
): Icon? {
    return try {
        val density = context.resources.displayMetrics.density
        val sizeDp = 38
        val sizePx = (sizeDp * density).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val paint = Paint().apply { isAntiAlias = true }

        val pinColor = try {
            android.graphics.Color.parseColor(colorHex)
        } catch (_: Exception) {
            android.graphics.Color.parseColor("#06B6D4")
        }

        // Soft shadow
        paint.color = android.graphics.Color.parseColor("#33000000")
        canvas.drawCircle(cx, cy + (1.5f * density), 13.5f * density, paint)

        // Outer white border
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(cx, cy, 13 * density, paint)

        // Inner fill
        paint.color = pinColor
        canvas.drawCircle(cx, cy, 10.5f * density, paint)

        // Clean initial
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 11.5f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        val initial = label.trim().take(1).uppercase().ifEmpty { "•" }
        val textY = cy - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(initial, cx, textY, paint)

        IconFactory.getInstance(context).fromBitmap(bitmap)
    } catch (e: Exception) {
        null
    }
}

/**
 * Destination marker: user-initial circle with radiating rings.
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
        modifier = modifier.size(130.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val coreR = 14.dp.toPx()

            val r1 = coreR + (wave1Progress * 42.dp.toPx())
            drawCircle(
                color = color.copy(alpha = (1f - wave1Progress).coerceIn(0f, 1f) * 0.85f),
                radius = r1,
                center = center,
                style = Stroke(width = 2.4.dp.toPx())
            )

            val r2 = coreR + (wave2Progress * 42.dp.toPx())
            drawCircle(
                color = color.copy(alpha = (1f - wave2Progress).coerceIn(0f, 1f) * 0.85f),
                radius = r2,
                center = center,
                style = Stroke(width = 2.0.dp.toPx())
            )

            drawCircle(
                color = Color.Black.copy(alpha = 0.3f),
                radius = coreR,
                center = Offset(center.x, center.y + 1.5.dp.toPx())
            )
            drawCircle(color = Color.White, radius = coreR + 2.5.dp.toPx(), center = center)
            drawCircle(color = color, radius = coreR, center = center)
        }

        Text(
            text = initial,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Self overlay: nav chevron while driving; idle = initial circle + facing triangle (O>).
 */
@Composable
fun SelfPuckOrVehicleArrow(
    isNavigating: Boolean,
    heading: Double,
    mapBearing: Double,
    userColor: Color,
    initial: String,
    modifier: Modifier = Modifier
) {
    if (isNavigating) {
        androidx.compose.foundation.Canvas(modifier = modifier.size(60.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f

            drawCircle(
                color = Color.Black.copy(alpha = 0.28f),
                radius = 24.dp.toPx(),
                center = Offset(cx, cy + 4.dp.toPx())
            )

            val outerPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, cy - 22.dp.toPx())
                lineTo(cx + 18.dp.toPx(), cy + 16.dp.toPx())
                lineTo(cx, cy + 9.dp.toPx())
                lineTo(cx - 18.dp.toPx(), cy + 16.dp.toPx())
                close()
            }
            drawPath(path = outerPath, color = Color.White)

            val innerPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, cy - 18.dp.toPx())
                lineTo(cx + 14.dp.toPx(), cy + 13.dp.toPx())
                lineTo(cx, cy + 7.dp.toPx())
                lineTo(cx - 14.dp.toPx(), cy + 13.dp.toPx())
                close()
            }
            drawPath(path = innerPath, color = CaravanAmber)

            val spinePath = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, cy - 18.dp.toPx())
                lineTo(cx + 1.5.dp.toPx(), cy + 7.dp.toPx())
                lineTo(cx - 1.5.dp.toPx(), cy + 7.dp.toPx())
                close()
            }
            drawPath(path = spinePath, color = Color.White.copy(alpha = 0.75f))
        }
    } else {
        val relativeHeading = ((heading - mapBearing + 360.0) % 360.0).toFloat()

        Box(
            modifier = modifier.size(52.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val coreR = 12.dp.toPx()

                rotate(degrees = relativeHeading, pivot = Offset(cx, cy)) {
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
}
