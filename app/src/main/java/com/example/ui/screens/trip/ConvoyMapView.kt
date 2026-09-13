package com.example.ui.screens.trip

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.location.DeviceLocation
import com.example.data.model.MapMark
import com.example.data.model.SharedRoute
import com.example.data.model.TripDestination
import com.example.data.model.TripMember
import com.example.data.network.RouteResult
import com.example.ui.theme.*
import com.example.ui.viewmodel.RoutingProvider
import kotlin.math.*

@OptIn(ExperimentalTextApi::class)
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
    marks: Map<String, MapMark> = emptyMap(),
    sharedRoutes: Map<String, SharedRoute> = emptyMap(),
    onLongPressMark: (latitude: Double, longitude: Double) -> Unit,
    onSelectRouteProvider: (RoutingProvider) -> Unit,
    onMemberSelected: (TripMember) -> Unit,
    modifier: Modifier = Modifier
) {
    // Zoom level continuous (11.0f to 18.5f)
    var zoomLevel by remember { mutableFloatStateOf(15.0f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }

    // When entering driving navigation mode, adjust camera perspective
    LaunchedEffect(isNavigating) {
        if (isNavigating) {
            panOffsetX = 0f
            panOffsetY = 130f
            zoomLevel = 16.2f
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val selfColor = remember(selfColorHex) {
        try {
            Color(android.graphics.Color.parseColor(selfColorHex))
        } catch (e: Exception) {
            CaravanBlue
        }
    }

    // Pulse animation for active speaker and destination beacon
    val infiniteTransition = rememberInfiniteTransition(label = "map_animations")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 18f,
        targetValue = 54f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_radius"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_alpha"
    )

    // Mathematical coordinate projection (Web Mercator)
    fun lonToTileX(lon: Double, z: Int): Double =
        (lon + 180.0) / 360.0 * (1 shl z)

    fun latToTileY(lat: Double, z: Int): Double {
        val latRad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * (1 shl z)
    }

    fun tileXToLon(x: Double, z: Int): Double =
        x / (1 shl z) * 360.0 - 180.0

    fun tileYToLat(y: Double, z: Int): Double {
        return try {
            val n = (Math.PI - 2.0 * Math.PI * y / (1 shl z)).coerceIn(-20.0, 20.0)
            Math.toDegrees(atan(sinh(n))).coerceIn(-85.05112878, 85.05112878)
        } catch (_: Exception) {
            0.0
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isDarkMode) Color(0xFF090D16) else Color(0xFFF1F5F9))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    panOffsetX += pan.x
                    panOffsetY += pan.y
                    zoomLevel = (zoomLevel * gestureZoom).coerceIn(11f, 18.5f)
                }
            }
            .pointerInput(currentLocation, zoomLevel, panOffsetX, panOffsetY) {
                detectTapGestures(
                    onDoubleTap = {
                        zoomLevel = (zoomLevel + 1.0f).coerceAtMost(18.5f)
                    },
                    onLongPress = { tapOffset ->
                        try {
                            val intZ = zoomLevel.toInt().coerceIn(11, 18)
                            val scale = 2.0.pow((zoomLevel - intZ).toDouble())
                            val tileSize = 256.0 * scale
                            val centerTileX = lonToTileX(currentLocation.longitude, intZ)
                            val centerTileY = latToTileY(currentLocation.latitude, intZ)
                            val screenCenterX = size.width / 2f + panOffsetX
                            val screenCenterY = size.height / 2f + panOffsetY

                            val tappedTileX = centerTileX + (tapOffset.x - screenCenterX) / tileSize
                            val tappedTileY = centerTileY + (tapOffset.y - screenCenterY) / tileSize

                            val tappedLng = tileXToLon(tappedTileX, intZ)
                            val tappedLat = tileYToLat(tappedTileY, intZ)

                            if (!tappedLat.isNaN() && !tappedLng.isNaN() && !tappedLat.isInfinite() && !tappedLng.isInfinite()) {
                                val safeLat = tappedLat.coerceIn(-85.0, 85.0)
                                val safeLng = tappedLng.coerceIn(-180.0, 180.0)
                                onLongPressMark(safeLat, safeLng)
                            }
                        } catch (_: Exception) {
                            // Guard against any gesture crash
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val intZ = zoomLevel.toInt().coerceIn(11, 18)
            val scale = 2.0.pow((zoomLevel - intZ).toDouble())
            val tileSize = (256.0 * scale).toFloat()

            val centerTileX = lonToTileX(currentLocation.longitude, intZ)
            val centerTileY = latToTileY(currentLocation.latitude, intZ)

            val screenCenterX = size.width / 2f + panOffsetX
            val screenCenterY = size.height / 2f + panOffsetY

            fun toScreenOffset(lat: Double, lng: Double): Offset {
                val tx = lonToTileX(lng, intZ)
                val ty = latToTileY(lat, intZ)
                val sx = screenCenterX + ((tx - centerTileX) * tileSize).toFloat()
                val sy = screenCenterY + ((ty - centerTileY) * tileSize).toFloat()
                return Offset(sx, sy)
            }

            // 1. Draw Free Tactical Vector Grid & Radar (100% free, runs offline, zero API required)
            val gridColor = if (isDarkMode) Color(0xFF1E293B).copy(alpha = 0.5f) else Color(0xFFCBD5E1).copy(alpha = 0.7f)
            val gridAccent = if (isDarkMode) Color(0xFF334155).copy(alpha = 0.35f) else Color(0xFF94A3B8).copy(alpha = 0.45f)

            val minTileX = floor(centerTileX - (screenCenterX) / tileSize).toInt()
            val maxTileX = ceil(centerTileX + (size.width - screenCenterX) / tileSize).toInt()
            val minTileY = floor(centerTileY - (screenCenterY) / tileSize).toInt()
            val maxTileY = ceil(centerTileY + (size.height - screenCenterY) / tileSize).toInt()

            for (tx in minTileX..maxTileX) {
                val screenX = screenCenterX + ((tx - centerTileX) * tileSize).toFloat()
                drawLine(
                    color = if (tx % 2 == 0) gridColor else gridAccent,
                    start = Offset(screenX, 0f),
                    end = Offset(screenX, size.height),
                    strokeWidth = if (tx % 2 == 0) 1.5f else 0.8f
                )
            }

            for (ty in minTileY..maxTileY) {
                val screenY = screenCenterY + ((ty - centerTileY) * tileSize).toFloat()
                drawLine(
                    color = if (ty % 2 == 0) gridColor else gridAccent,
                    start = Offset(0f, screenY),
                    end = Offset(size.width, screenY),
                    strokeWidth = if (ty % 2 == 0) 1.5f else 0.8f
                )
            }

            // Tactical Distance Radar Rings centered on self vehicle
            val selfPos = toScreenOffset(currentLocation.latitude, currentLocation.longitude)
            val metersPerPixel = (156543.03392 * cos(Math.toRadians(currentLocation.latitude)) / (2.0.pow(zoomLevel.toDouble()))).toFloat()

            if (metersPerPixel > 0.001f) {
                val rangesMeters = listOf(200f, 500f, 1000f, 2500f, 5000f)
                val ringColor = if (isDarkMode) CaravanBlue.copy(alpha = 0.10f) else Color(0xFF0284C7).copy(alpha = 0.12f)
                rangesMeters.forEach { distM ->
                    val rPx = distM / metersPerPixel
                    if (rPx in 30f..1400f) {
                        drawCircle(
                            color = ringColor,
                            radius = rPx,
                            center = selfPos,
                            style = Stroke(
                                width = 1.2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                            )
                        )
                    }
                }
            }

            // 2a. Draw Shared Convoy Routes from other members
            sharedRoutes.values.forEach { sRoute ->
                if (sRoute.points.size > 1) {
                    val sPath = Path()
                    val firstPt = toScreenOffset(sRoute.points.first().latitude, sRoute.points.first().longitude)
                    sPath.moveTo(firstPt.x, firstPt.y)
                    for (i in 1 until sRoute.points.size) {
                        val pt = toScreenOffset(sRoute.points[i].latitude, sRoute.points[i].longitude)
                        sPath.lineTo(pt.x, pt.y)
                    }
                    val sRouteColor = try {
                        Color(android.graphics.Color.parseColor(sRoute.colorHex))
                    } catch (e: Exception) {
                        Color(0xFF0284C7)
                    }
                    // Glow layer
                    drawPath(
                        path = sPath,
                        color = sRouteColor.copy(alpha = 0.25f),
                        style = Stroke(width = 14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    // Core line
                    drawPath(
                        path = sPath,
                        color = sRouteColor,
                        style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }

            // 2b. Draw Local User's Active Route Polyline (if calculated)
            val routePoints = route?.points ?: emptyList()
            if (routePoints.size > 1) {
                val path = Path()
                val firstPt = toScreenOffset(routePoints.first().latitude, routePoints.first().longitude)
                path.moveTo(firstPt.x, firstPt.y)
                for (i in 1 until routePoints.size) {
                    val pt = toScreenOffset(routePoints[i].latitude, routePoints[i].longitude)
                    path.lineTo(pt.x, pt.y)
                }

                // "بجز وقتی از مسیر نشان استفاده میکنه که باید رنگ مسیر فقط رنگ ترافیک باشه ولی ایکن خودش و مارک تارگتش باید همون رنگ یوزر باشه."
                val localRouteColor = if (activeRoutingProvider == RoutingProvider.NESHAN) {
                    Color(0xFF10B981) // Strictly traffic green for Neshan
                } else {
                    selfColor // User's own distinct color
                }

                // Glow layer
                drawPath(
                    path = path,
                    color = localRouteColor.copy(alpha = 0.30f),
                    style = Stroke(width = 16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                // Core line
                drawPath(
                    path = path,
                    color = localRouteColor,
                    style = Stroke(
                        width = 7f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // 3a. Draw Destination Beacon Marker (in creator's distinct color)
            destination?.let { dest ->
                val destPos = toScreenOffset(dest.latitude, dest.longitude)

                val destColor: Color = run {
                    if (!dest.colorHex.isNullOrBlank()) {
                        try { Color(android.graphics.Color.parseColor(dest.colorHex)) } catch (e: Exception) { null }
                    } else null
                } ?: run {
                    members.firstOrNull { it.id == dest.updatedById }?.let { m ->
                        try { Color(android.graphics.Color.parseColor(m.avatarColor)) } catch (e: Exception) { null }
                    }
                } ?: selfColor

                // Beacon pulse rings in creator's color
                drawCircle(
                    color = destColor.copy(alpha = pulseAlpha),
                    radius = pulseRadius * 1.5f,
                    center = destPos
                )
                // Outer ring
                drawCircle(
                    color = Color.White,
                    radius = 14f,
                    center = destPos
                )
                // Inner center in creator's color
                drawCircle(
                    color = destColor,
                    radius = 11f,
                    center = destPos
                )

                // Label tag
                val destLabel = if (!dest.updatedByName.isNullOrBlank() && dest.updatedByName != "Driver") {
                    "${dest.updatedByName} • ${dest.label ?: "Target"}"
                } else {
                    dest.label ?: "Destination"
                }
                val textLayout = textMeasurer.measure(
                    text = AnnotatedString(destLabel),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                val badgePadX = 14f
                val badgePadY = 6f
                val badgeW = textLayout.size.width + badgePadX * 2
                val badgeH = textLayout.size.height + badgePadY * 2
                val badgeTopLeft = Offset(destPos.x - badgeW / 2f, destPos.y - 36f - badgeH)

                drawRoundRect(
                    color = Color(0xFF1E293B).copy(alpha = 0.92f),
                    topLeft = badgeTopLeft,
                    size = androidx.compose.ui.geometry.Size(badgeW, badgeH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
                )
                drawRoundRect(
                    color = destColor.copy(alpha = 0.85f),
                    topLeft = badgeTopLeft,
                    size = androidx.compose.ui.geometry.Size(badgeW, badgeH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
                    style = Stroke(width = 2f)
                )
                drawText(
                    textLayoutResult = textLayout,
                    topLeft = Offset(badgeTopLeft.x + badgePadX, badgeTopLeft.y + badgePadY)
                )
            }

            // 3b. Draw Shared Map Marks from convoy members
            marks.values.forEach { mark ->
                // Skip duplicate if exactly matches destination
                if (destination != null &&
                    abs(destination.latitude - mark.latitude) < 0.0001 &&
                    abs(destination.longitude - mark.longitude) < 0.0001
                ) {
                    return@forEach
                }
                val markPos = toScreenOffset(mark.latitude, mark.longitude)
                val markColor = try {
                    Color(android.graphics.Color.parseColor(mark.color))
                } catch (e: Exception) {
                    CaravanAmber
                }

                // Beacon pulse rings
                drawCircle(
                    color = markColor.copy(alpha = pulseAlpha),
                    radius = pulseRadius * 1.3f,
                    center = markPos
                )
                // Outer ring
                drawCircle(
                    color = Color.White,
                    radius = 13f,
                    center = markPos
                )
                // Inner center in mark color
                drawCircle(
                    color = markColor,
                    radius = 10f,
                    center = markPos
                )

                // Label tag
                val markLabel = "${mark.displayName}'s Mark"
                val markLayout = textMeasurer.measure(
                    text = AnnotatedString(markLabel),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                val bPadX = 10f
                val bPadY = 4f
                val bW = markLayout.size.width + bPadX * 2
                val bH = markLayout.size.height + bPadY * 2
                val bTopLeft = Offset(markPos.x - bW / 2f, markPos.y - 30f - bH)

                drawRoundRect(
                    color = Color(0xFF1E293B).copy(alpha = 0.92f),
                    topLeft = bTopLeft,
                    size = androidx.compose.ui.geometry.Size(bW, bH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                )
                drawRoundRect(
                    color = markColor.copy(alpha = 0.85f),
                    topLeft = bTopLeft,
                    size = androidx.compose.ui.geometry.Size(bW, bH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                    style = Stroke(width = 2f)
                )
                drawText(
                    textLayoutResult = markLayout,
                    topLeft = Offset(bTopLeft.x + bPadX, bTopLeft.y + bPadY)
                )
            }

            // 4. Draw Remote Convoy Members
            members.forEach { member ->
                val mLat = member.latitude ?: return@forEach
                val mLng = member.longitude ?: return@forEach
                val pos = toScreenOffset(mLat, mLng)
                val memberColor = try {
                    val colStr = member.avatarColor ?: "#0EA5E9"
                    Color(android.graphics.Color.parseColor(colStr))
                } catch (e: Exception) {
                    CaravanBlue
                }
                val isSpeaking = activeSpeakerName != null &&
                        (member.displayName.equals(activeSpeakerName, ignoreCase = true) || member.id == activeSpeakerName)

                // Speaking pulse
                if (isSpeaking) {
                    drawCircle(
                        color = CaravanCrimson.copy(alpha = pulseAlpha),
                        radius = pulseRadius,
                        center = pos
                    )
                }

                // Member vehicle shadow & circle
                drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = 16f,
                    center = pos + Offset(0f, 3f)
                )
                drawCircle(
                    color = Color.White,
                    radius = 15f,
                    center = pos
                )
                drawCircle(
                    color = memberColor,
                    radius = 12f,
                    center = pos
                )

                // Car Heading Arrow (if heading available)
                member.heading?.let { headingDeg ->
                    val angleRad = Math.toRadians(headingDeg - 90.0)
                    val arrowLen = 22f
                    val arrowTip = Offset(
                        pos.x + (cos(angleRad) * arrowLen).toFloat(),
                        pos.y + (sin(angleRad) * arrowLen).toFloat()
                    )
                    drawLine(
                        color = memberColor,
                        start = pos,
                        end = arrowTip,
                        strokeWidth = 4f,
                        cap = StrokeCap.Round
                    )
                }

                // Member Name Tag
                val speedKm = ((member.speed ?: 0.0) * 3.6).roundToInt()
                val infoText = "${member.displayName} • ${speedKm}km/h"
                val memberLayout = textMeasurer.measure(
                    text = AnnotatedString(infoText),
                    style = TextStyle(
                        color = if (isDarkMode) Color.White else Color(0xFF0F172A),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                val tagW = memberLayout.size.width + 16f
                val tagH = memberLayout.size.height + 8f
                val tagPos = Offset(pos.x - tagW / 2f, pos.y + 18f)

                drawRoundRect(
                    color = if (isDarkMode) Color(0xFF0F172A).copy(alpha = 0.88f) else Color.White.copy(alpha = 0.92f),
                    topLeft = tagPos,
                    size = androidx.compose.ui.geometry.Size(tagW, tagH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                )
                drawText(
                    textLayoutResult = memberLayout,
                    topLeft = Offset(tagPos.x + 8f, tagPos.y + 4f)
                )
            }

            // 5. Draw Self Vehicle Marker (Centered GPS marker or Driving Vehicle Chevron)
            val isSelfSpeaking = activeSpeakerName == "Self" || activeSpeakerName == "You"

            if (isSelfSpeaking) {
                drawCircle(
                    color = CaravanEmerald.copy(alpha = pulseAlpha),
                    radius = pulseRadius * 1.2f,
                    center = selfPos
                )
            }

            if (isNavigating) {
                // Driving Navigation Mode: 3D-styled vehicle chevron with headlight beam
                rotate(degrees = currentLocation.heading.toFloat(), pivot = selfPos) {
                    val beamPath = Path().apply {
                        moveTo(selfPos.x, selfPos.y - 12f)
                        lineTo(selfPos.x - 36f, selfPos.y - 130f)
                        lineTo(selfPos.x + 36f, selfPos.y - 130f)
                        close()
                    }
                    drawPath(
                        path = beamPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(selfColor.copy(alpha = 0.28f), Color.Transparent),
                            startY = selfPos.y - 130f,
                            endY = selfPos.y - 12f
                        )
                    )

                    val carPath = Path().apply {
                        moveTo(selfPos.x, selfPos.y - 24f)
                        lineTo(selfPos.x - 16f, selfPos.y + 16f)
                        lineTo(selfPos.x, selfPos.y + 7f)
                        lineTo(selfPos.x + 16f, selfPos.y + 16f)
                        close()
                    }
                    drawPath(path = carPath, color = Color.Black.copy(alpha = 0.35f))
                    drawPath(path = carPath, color = selfColor)
                    drawLine(
                        color = Color.White,
                        start = Offset(selfPos.x, selfPos.y - 20f),
                        end = Offset(selfPos.x, selfPos.y + 5f),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                }
            } else {
                // Outer precision accuracy ring
                drawCircle(
                    color = selfColor.copy(alpha = 0.18f),
                    radius = 28f,
                    center = selfPos
                )
                // White halo
                drawCircle(
                    color = Color.White,
                    radius = 16f,
                    center = selfPos
                )
                // Core
                drawCircle(
                    color = selfColor,
                    radius = 12f,
                    center = selfPos
                )

                // Self Heading Arrow
                val angleRad = Math.toRadians(currentLocation.heading - 90.0)
                val arrowTip = Offset(
                    selfPos.x + (cos(angleRad) * 24f).toFloat(),
                    selfPos.y + (sin(angleRad) * 24f).toFloat()
                )
                drawLine(
                    color = selfColor,
                    start = selfPos,
                    end = arrowTip,
                    strokeWidth = 5f,
                    cap = StrokeCap.Round
                )
            }
        }

        // 6. Map Controls Cluster (Zoom In, Zoom Out, and GPS + Manual Routing Circles)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 126.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Zoom In
            SmallFloatingActionButton(
                onClick = { zoomLevel = (zoomLevel + 0.8f).coerceAtMost(18.5f) },
                containerColor = if (isDarkMode) NightSlateCard else Color.White,
                contentColor = if (isDarkMode) Color.White else Color(0xFF0F172A),
                shape = CircleShape,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("map_zoom_in")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom In", modifier = Modifier.size(20.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Zoom Out
            SmallFloatingActionButton(
                onClick = { zoomLevel = (zoomLevel - 0.8f).coerceAtLeast(11f) },
                containerColor = if (isDarkMode) NightSlateCard else Color.White,
                contentColor = if (isDarkMode) Color.White else Color(0xFF0F172A),
                shape = CircleShape,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("map_zoom_out")
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom Out", modifier = Modifier.size(20.dp))
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom row: When navigating or destination is set, show the two small routing circles beside the GPS circle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isNavigating || destination != null) {
                    // 1. Normal Routing Circle (Manual update on click only to save tokens)
                    val isOsrmActive = activeRoutingProvider == RoutingProvider.OSRM
                    SmallFloatingActionButton(
                        onClick = { onSelectRouteProvider(RoutingProvider.OSRM) },
                        containerColor = if (isOsrmActive) CaravanBlue else (if (isDarkMode) NightSlateCard else Color.White),
                        contentColor = if (isOsrmActive) Color.White else CaravanBlue,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("route_normal_circle")
                    ) {
                        if (isCalculatingRoute && isOsrmActive) {
                            CircularProgressIndicator(
                                strokeWidth = 2.5.dp,
                                color = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                Icons.Default.Directions,
                                contentDescription = "Normal Route",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // 2. Neshan Routing Circle (Manual update on click only to save tokens)
                    val isNeshanActive = activeRoutingProvider == RoutingProvider.NESHAN
                    val neshanGreen = Color(0xFF10B981)
                    SmallFloatingActionButton(
                        onClick = { onSelectRouteProvider(RoutingProvider.NESHAN) },
                        containerColor = if (isNeshanActive) neshanGreen else (if (isDarkMode) NightSlateCard else Color.White),
                        contentColor = if (isNeshanActive) Color.White else neshanGreen,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("route_neshan_circle")
                    ) {
                        if (isCalculatingRoute && isNeshanActive) {
                            CircularProgressIndicator(
                                strokeWidth = 2.5.dp,
                                color = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                Icons.Default.Traffic,
                                contentDescription = "Neshan Route",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // 3. Recenter GPS Circle
                SmallFloatingActionButton(
                    onClick = {
                        panOffsetX = 0f
                        panOffsetY = if (isNavigating) 130f else 0f
                        zoomLevel = if (isNavigating) 16.2f else 15.0f
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
