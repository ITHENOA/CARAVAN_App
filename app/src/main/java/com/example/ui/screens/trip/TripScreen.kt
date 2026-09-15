package com.example.ui.screens.trip

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TripMember
import com.example.data.voice.PttState
import com.example.ui.screens.home.InviteShareDialog
import com.example.ui.theme.*
import com.example.ui.viewmodel.CaravanConnectionStatus
import com.example.ui.viewmodel.CaravanViewModel
import com.example.ui.viewmodel.RoutingProvider
import com.example.util.ConvoyUtils

@Composable
fun TripScreen(
    viewModel: CaravanViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tripState by viewModel.tripState.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val isMyLocationActive by viewModel.isMyLocationActive.collectAsState()
    val connectionMethod = when {
        viewModel.prefs.useAetherProxy -> "AETHER"
        viewModel.prefs.isProxyEnabled -> "PROXY"
        viewModel.prefs.isDnsEnabled -> "DNS"
        else -> null
    }

    var showFleetSheet by remember { mutableStateOf(false) }
    var showChatSheet by remember { mutableStateOf(false) }
    var showDestinationDialog by remember { mutableStateOf(false) }
    var showLeaveConfirmDialog by remember { mutableStateOf(false) }
    var showQuickPrompts by remember { mutableStateOf(false) }
    var showInviteShare by remember { mutableStateOf(false) }
    var memberToFocus by remember { mutableStateOf<TripMember?>(null) }
    var fitAllRequestedAt by remember { mutableLongStateOf(0L) }
    var showReturnToDriving by remember { mutableStateOf(false) }
    var returnToDrivingDeadline by remember { mutableLongStateOf(0L) }
    var returnToDrivingProgress by remember { mutableFloatStateOf(0f) }
    var drivingViewResetToken by remember { mutableLongStateOf(0L) }

    BackHandler {
        showLeaveConfirmDialog = true
    }

    fun resetReturnToDrivingTimer() {
        if (!tripState.isNavigating) return
        showReturnToDriving = true
        returnToDrivingProgress = 0f
        returnToDrivingDeadline = System.currentTimeMillis() + 5_000L
    }

    LaunchedEffect(returnToDrivingDeadline, tripState.isNavigating) {
        if (!tripState.isNavigating || returnToDrivingDeadline == 0L) {
            showReturnToDriving = false
            returnToDrivingProgress = 0f
            return@LaunchedEffect
        }
        while (true) {
            val remaining = returnToDrivingDeadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            returnToDrivingProgress = 1f - (remaining / 5_000f).coerceIn(0f, 1f)
            kotlinx.coroutines.delay(50L)
        }
        returnToDrivingProgress = 1f
        showReturnToDriving = false
        returnToDrivingDeadline = 0L
        drivingViewResetToken++
        viewModel.startNavigation()
    }

    val quickPrompts = remember { viewModel.prefs.getQuickPrompts() }
    val mutedPeerIds by viewModel.mutedPeerIds.collectAsState()
    val remoteMemberIds = remember(tripState.members, userProfile.clientId) {
        tripState.members.filter { it.id != userProfile.clientId }.map { it.id }
    }
    val allMembersMuted = remoteMemberIds.isNotEmpty() &&
        remoteMemberIds.all { it in mutedPeerIds }

    // Keep display awake while driving (Google Maps / Neshan style)
    val view = LocalView.current
    DisposableEffect(tripState.isNavigating) {
        val previous = view.keepScreenOn
        view.keepScreenOn = tripState.isNavigating
        onDispose { view.keepScreenOn = previous }
    }

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
            drivingViewZoom = viewModel.prefs.drivingViewZoom,
            isMyLocationActive = isMyLocationActive,
            selfColorHex = userProfile.avatarColor,
            selfClientId = userProfile.clientId,
            selfDisplayName = userProfile.displayName,
            marks = tripState.marks,
            sharedRoutes = tripState.sharedRoutes,
            fitAllRequestedAt = fitAllRequestedAt,
            allMembersMuted = allMembersMuted,
            onToggleAllMembersMute = {
                viewModel.setAllPeersMuted(remoteMemberIds, !allMembersMuted)
            },
            onLongPressMark = { lat, lng ->
                viewModel.placeMapMark(lat, lng)
            },
            memberToFocus = memberToFocus,
            onMemberSelected = { memberToFocus = it },
            onMarkSelected = { mark ->
                // Only the mark owner can start nav from the pin; others use fleet "Go to Mark"
                if (mark.clientId == userProfile.clientId) {
                    viewModel.navigateToMemberMark(mark)
                }
            },
            onDrivingViewInterrupted = { resetReturnToDrivingTimer() },
            showReturnToDriving = showReturnToDriving,
            returnToDrivingProgress = returnToDrivingProgress,
            onReturnToDriving = {
                returnToDrivingDeadline = 0L
                showReturnToDriving = false
                returnToDrivingProgress = 0f
                drivingViewResetToken++
                viewModel.startNavigation()
            },
            drivingViewResetToken = drivingViewResetToken,
            onMyLocationClick = {
                if (!viewModel.onMyLocationButtonClick()) {
                    Toast.makeText(
                        context,
                        "برای نمایش موقعیت، لوکیشن گوشی را روشن کنید",
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.openLocationSettings()
                    false
                } else {
                    true
                }
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
                tripId = tripState.tripId,
                connectionStatus = tripState.connectionStatus,
                connectionMethod = connectionMethod,
                memberCount = tripState.members.count { it.id != userProfile.clientId },
                isDarkMode = isDarkMode,
                onToggleDarkMode = { viewModel.toggleDarkMode() },
                onOpenDestinationDialog = { showDestinationDialog = true },
                onToggleFleetList = { showFleetSheet = true },
                onOpenSettings = onNavigateToSettings,
                onLeaveTrip = { showLeaveConfirmDialog = true },
                onShareInvite = { showInviteShare = true }
            )

            tripState.connectionError?.takeIf {
                tripState.connectionStatus != CaravanConnectionStatus.CONNECTED
            }?.let { err ->
                val fixLabel = when (tripState.connectionFixStep) {
                    com.example.ui.viewmodel.ConnectionFixStep.TRY_GOOGLE_DNS -> "Google DNS"
                    com.example.ui.viewmodel.ConnectionFixStep.TRY_AETHER -> "Aether"
                    com.example.ui.viewmodel.ConnectionFixStep.OPEN_SETTINGS_OR_VPN -> "Settings / VPN"
                }
                Surface(
                    color = CaravanAmber.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("ws_connection_error_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = err,
                            color = Color.Black,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            onClick = {
                                viewModel.applySuggestedConnectionFix(
                                    context = context,
                                    onOpenSettings = onNavigateToSettings
                                )
                            },
                            color = Color.Black.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("ws_connection_fix_action")
                        ) {
                            Text(
                                text = fixLabel,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

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
                        // Pre-drive: same mini Regular/Neshan/X as driving mode.
                        // Start appears only after the user picks a provider.
                        val selectedProvider = tripState.routingProvider
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Default.Navigation,
                                    contentDescription = null,
                                    tint = CaravanBlue,
                                    modifier = Modifier.size(24.dp)
                                )

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dest.label ?: "Marked Point",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${ConvoyUtils.formatDistance(distM)} • ETA: $etaStr",
                                        fontSize = 11.sp,
                                        color = CaravanBlue,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(6.dp))

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

                                IconButton(
                                    onClick = { viewModel.stopNavigation() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Clear Route",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            if (selectedProvider != null) {
                                Spacer(modifier = Modifier.height(10.dp))
                                val isNeshan = selectedProvider == RoutingProvider.NESHAN
                                Button(
                                    onClick = { viewModel.startNavigation() },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isNeshan) neshanGreen else CaravanBlue,
                                        contentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("btn_start_navigation")
                                ) {
                                    Icon(
                                        Icons.Default.DirectionsCar,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isNeshan) {
                                            "Start Neshan Navigation"
                                        } else {
                                            "Start Regular Navigation"
                                        },
                                        fontSize = 13.sp,
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

        // 3. Right-Side Message Previews + live PTT speaker chip (stays clear of drive panel)
        RightSideMessagePreviews(
            previews = tripState.visiblePreviews,
            activeSpeakerName = tripState.activeSpeakerName,
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

                    // Record → send voice note (hold or tap-to-latch like PTT)
                    VoiceNoteButton(
                        isRecording = tripState.voiceNoteRecording,
                        isReadyToSend = tripState.voiceNoteReady,
                        audioAmplitude = tripState.audioAmplitude,
                        enabled = tripState.pttState != PttState.TRANSMITTING &&
                            tripState.pttState != PttState.REQUESTING,
                        onToggle = { viewModel.toggleVoiceNote() },
                        onStart = { viewModel.startVoiceNote() },
                        onRelease = { viewModel.finishVoiceNoteRecording() },
                        onCancel = { viewModel.cancelVoiceNote() }
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
                members = tripState.members.filter { it.id != userProfile.clientId },
                userLocation = currentLocation,
                sharedRoutes = tripState.sharedRoutes,
                marks = tripState.marks,
                mutedMemberIds = mutedPeerIds,
                allMuted = allMembersMuted,
                onFitAllMembers = { fitAllRequestedAt = System.currentTimeMillis() },
                onToggleAllMute = {
                    viewModel.setAllPeersMuted(remoteMemberIds, !allMembersMuted)
                },
                onToggleMemberMute = viewModel::togglePeerMute,
                onDismiss = { showFleetSheet = false },
                onSelectMember = { memberToFocus = it },
                onFollowMemberRoute = { memberId ->
                    viewModel.followSharedRoute(memberId)
                },
                onNavigateToMemberMark = { mark ->
                    viewModel.navigateToMemberMark(mark)
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
                        "You disconnect from this trip, but it stays in Your Convoys until you delete it.",
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

        if (showInviteShare) {
            InviteShareDialog(
                tripName = tripState.tripName,
                tripId = tripState.tripId,
                inviteCode = tripState.inviteCode,
                onDismiss = { showInviteShare = false }
            )
        }
    }
}
