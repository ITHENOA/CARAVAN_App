package com.example.ui.screens.trip

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TripMember
import com.example.ui.theme.*
import com.example.ui.viewmodel.CaravanViewModel
import com.example.ui.viewmodel.RoutingProvider
import com.example.util.ConvoyUtils

@Composable
fun TripScreen(
    viewModel: CaravanViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tripState by viewModel.tripState.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()

    var showFleetSheet by remember { mutableStateOf(false) }
    var showChatSheet by remember { mutableStateOf(false) }
    var showDestinationDialog by remember { mutableStateOf(false) }
    var showLeaveConfirmDialog by remember { mutableStateOf(false) }
    var showQuickPrompts by remember { mutableStateOf(false) }

    val quickPrompts = remember { viewModel.prefs.getQuickPrompts() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 1. Full Screen Interactive Map with Real OpenFreeMap Liberty Basemap (MapLibre Client)
        ConvoyMapView(
            currentLocation = currentLocation,
            members = tripState.members,
            destination = tripState.destination,
            route = tripState.route,
            activeRoutingProvider = tripState.routingProvider,
            isCalculatingRoute = tripState.isCalculatingRoute,
            isDarkMode = isDarkMode,
            isNavigating = tripState.isNavigating,
            activeSpeakerName = tripState.activeSpeakerName,
            selfColorHex = userProfile.avatarColor,
            selfClientId = userProfile.clientId,
            selfDisplayName = userProfile.displayName,
            marks = tripState.marks,
            sharedRoutes = tripState.sharedRoutes,
            onLongPressMark = { lat, lng ->
                viewModel.setDestination(lat, lng, "Marked Point")
            },
            onMemberSelected = { /* Focus member */ },
            onMarkSelected = { mark ->
                viewModel.navigateToMemberMark(mark)
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Top Controls & HUD Area
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .fillMaxWidth()
        ) {
            // Convoy Top Bar with Light/Dark Theme Switcher & Destination Setting
            ConvoyTopBar(
                tripName = tripState.tripName,
                inviteCode = tripState.inviteCode,
                connectionStatus = tripState.connectionStatus,
                memberCount = tripState.members.size + 1,
                isDarkMode = isDarkMode,
                onToggleDarkMode = { viewModel.toggleDarkMode() },
                onOpenDestinationDialog = { showDestinationDialog = true },
                onToggleFleetList = { showFleetSheet = true },
                onOpenSettings = { /* Settings */ },
                onLeaveTrip = { showLeaveConfirmDialog = true }
            )

            // Active Destination & Routing HUD Card (Only shown when destination is set)
            tripState.destination?.let { dest ->
                val distM = ConvoyUtils.distanceMeters(
                    currentLocation.latitude,
                    currentLocation.longitude,
                    dest.latitude,
                    dest.longitude
                )
                val etaStr = tripState.route?.durationSeconds?.let {
                    ConvoyUtils.formatEta(it)
                } ?: ConvoyUtils.formatEta(distM / 15.0)

                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        1.dp,
                        if (tripState.isNavigating) Color(0xFF10B981).copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    ),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .testTag("destination_hud_card")
                ) {
                    val isOsrmActive = tripState.routingProvider == RoutingProvider.OSRM
                    val isNeshanActive = tripState.routingProvider == RoutingProvider.NESHAN
                    val isCalculating = tripState.isCalculatingRoute
                    val neshanGreen = Color(0xFF10B981)

                    if (tripState.isNavigating) {
                        // Driving Mode HUD: Single sleek horizontal bar:
                        // car icon -> Marked point text and path information under it -> little button for regular nav -> little button for neshan nav -> cross close button "x"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            // 1. Car Icon
                            Icon(
                                Icons.Default.DirectionsCar,
                                contentDescription = "Driving",
                                tint = neshanGreen,
                                modifier = Modifier.size(24.dp)
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            // 2. Marked point text and path information under it
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = dest.label ?: "Marked Point",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val speedKmh = ((currentLocation.speed ?: 0.0) * 3.6).toInt()
                                val statusText = "${ConvoyUtils.formatDistance(distM)} • ETA: $etaStr • ${speedKmh} km/h"
                                Text(
                                    text = statusText,
                                    fontSize = 11.sp,
                                    color = neshanGreen,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // 3. Little button for regular nav (no text)
                            Surface(
                                onClick = { viewModel.calculateRouteWithProvider(RoutingProvider.OSRM) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isOsrmActive) CaravanBlue else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, if (isOsrmActive) CaravanBlue else MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("btn_regular_nav_mini")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (isCalculating && isOsrmActive) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            color = if (isOsrmActive) Color.White else CaravanBlue,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Navigation,
                                            contentDescription = "Regular Route",
                                            tint = if (isOsrmActive) Color.White else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // 4. Little button for neshan nav (no text)
                            Surface(
                                onClick = { viewModel.calculateRouteWithProvider(RoutingProvider.NESHAN) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isNeshanActive) neshanGreen else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, if (isNeshanActive) neshanGreen else MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("btn_neshan_nav_mini")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (isCalculating && isNeshanActive) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            color = if (isNeshanActive) Color.White else neshanGreen,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Traffic,
                                            contentDescription = "Neshan Route",
                                            tint = if (isNeshanActive) Color.White else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // 5. Cross close button "x"
                            IconButton(
                                onClick = { viewModel.stopNavigation() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    } else {
                        // Non-driving mode: Shows point info and 1x2 buttons to launch navigation
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.Navigation,
                                        contentDescription = null,
                                        tint = CaravanBlue,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = dest.label ?: "Marked Point",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${ConvoyUtils.formatDistance(distM)} • ETA: $etaStr",
                                            fontSize = 12.sp,
                                            color = CaravanBlue,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                // Dismiss / Clear Destination
                                IconButton(
                                    onClick = { viewModel.stopNavigation() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Clear Route",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 1x2 Buttons: Regular Navigation (Left) and Neshan Navigation (Right)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        viewModel.calculateRouteWithProvider(RoutingProvider.OSRM)
                                        viewModel.startNavigation()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isOsrmActive) CaravanBlue else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (isOsrmActive) Color.White else MaterialTheme.colorScheme.onSurface
                                    ),
                                    border = if (isOsrmActive) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("btn_regular_navigation")
                                ) {
                                    if (isCalculating && isOsrmActive) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            color = if (isOsrmActive) Color.White else CaravanBlue,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Navigation,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Regular Nav",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }

                                Button(
                                    onClick = {
                                        viewModel.calculateRouteWithProvider(RoutingProvider.NESHAN)
                                        viewModel.startNavigation()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isNeshanActive) neshanGreen else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (isNeshanActive) Color.White else MaterialTheme.colorScheme.onSurface
                                    ),
                                    border = if (isNeshanActive) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("btn_neshan_navigation")
                                ) {
                                    if (isCalculating && isNeshanActive) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            color = if (isNeshanActive) Color.White else neshanGreen,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Traffic,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Neshan Nav",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Right-Side Message Previews Stack (preview of chat messages aligned to the right side of the screen)
        RightSideMessagePreviews(
            previews = tripState.visiblePreviews,
            onOpenChat = { showChatSheet = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 80.dp, end = 12.dp)
        )

        // 4. Bottom Cockpit Control Panel (PTT Button + Chat FAB beside it)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            if (isDarkMode) NightSlateBg.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.9f),
                            if (isDarkMode) NightSlateBg else Color.White
                        )
                    )
                )
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Floating Quick Messages Popup Card (above bottom bar, toggled by Chat FAB)
                AnimatedVisibility(
                    visible = showQuickPrompts,
                    enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()
                ) {
                    QuickMessagesCard(
                        prompts = quickPrompts,
                        onPromptSelected = { prompt ->
                            viewModel.sendQuickPrompt(prompt)
                            showQuickPrompts = false
                        },
                        onOpenFullChat = {
                            showQuickPrompts = false
                            showChatSheet = true
                        },
                        onDismiss = { showQuickPrompts = false }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Bottom Row: PTT Button (with dual-mode latch and hold) + Chat FAB on the right!
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Wide Push-To-Talk Button (Tap to latch toggle, hold to talk)
                    PttButton(
                        pttState = tripState.pttState,
                        activeSpeakerName = tripState.activeSpeakerName,
                        audioAmplitude = tripState.audioAmplitude,
                        onToggle = { viewModel.togglePtt() },
                        onStartPtt = { viewModel.startPtt() },
                        onReleasePtt = { viewModel.releasePtt() },
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    // Chat FAB restored to its exact previous position beside the PTT button!
                    ChatFab(
                        hasUnread = tripState.chatMessages.isNotEmpty(),
                        onTap = { showQuickPrompts = !showQuickPrompts },
                        onLongPress = { showChatSheet = true }
                    )
                }
            }
        }

        // 4. Modal Sheets and Dialogs
        if (showFleetSheet) {
            FleetBottomSheet(
                members = tripState.members,
                userLocation = currentLocation,
                sharedRoutes = tripState.sharedRoutes,
                onDismiss = { showFleetSheet = false },
                onSelectMember = { /* center */ },
                onFollowMemberRoute = { memberId ->
                    viewModel.followSharedRoute(memberId)
                }
            )
        }

        if (showChatSheet) {
            ChatBottomSheet(
                messages = tripState.chatMessages,
                currentClientId = userProfile.clientId,
                onSendMessage = { text -> viewModel.sendChatMessage(text) },
                onDismiss = { showChatSheet = false }
            )
        }

        if (showDestinationDialog) {
            DestinationDialog(
                userLocation = currentLocation,
                onDismiss = { showDestinationDialog = false },
                onSetDestination = { lat, lng, label ->
                    viewModel.setDestination(lat, lng, label)
                    viewModel.startNavigation()
                }
            )
        }

        // Leave Trip Confirmation Dialog
        if (showLeaveConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showLeaveConfirmDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = {
                    Text(
                        "Leave Convoy?",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Text(
                        "You will stop sharing your live location and disconnect from this trip.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showLeaveConfirmDialog = false
                            viewModel.leaveTrip()
                            onNavigateBack()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CaravanCrimson)
                    ) {
                        Text("Leave Trip", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLeaveConfirmDialog = false }) {
                        Text("Stay", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }
    }
}
