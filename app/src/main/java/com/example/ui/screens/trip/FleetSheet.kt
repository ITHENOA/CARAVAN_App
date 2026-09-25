package com.example.ui.screens.trip

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.location.DeviceLocation
import com.example.data.model.MapMark
import com.example.data.model.MemberConnectionStatus
import com.example.data.model.TripMember
import com.example.ui.theme.*
import com.example.ui.viewmodel.CaravanConnectionStatus
import com.example.util.ConvoyUtils
import kotlinx.coroutines.delay

@Composable
fun ConvoyTopBar(
    tripName: String,
    inviteCode: String,
    tripId: String = "",
    connectionStatus: CaravanConnectionStatus,
    connectionMethod: String? = null,
    memberCount: Int,
    isDarkMode: Boolean = false,
    onToggleDarkMode: () -> Unit = {},
    onOpenDestinationDialog: () -> Unit = {},
    onToggleFleetList: () -> Unit,
    onOpenSettings: () -> Unit,
    onLeaveTrip: () -> Unit,
    onShareInvite: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp),
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Trip Title & Invite Code
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tripName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Invite Code", inviteCode))
                                    Toast.makeText(context, "Code $inviteCode copied!", Toast.LENGTH_SHORT).show()
                                }
                                .testTag("copy_invite_code_button")
                        ) {
                            Text(
                                text = "Code: ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = inviteCode,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = CaravanBlue
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy code",
                                tint = CaravanBlue,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Default.QrCode2,
                            contentDescription = "Share QR",
                            tint = CaravanBlue,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable(onClick = onShareInvite)
                                .testTag("share_invite_qr_button")
                        )
                    }
                }

                // Status & Member Count Chips & Theme Switch & Destination Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Search Places Button
                    IconButton(
                        onClick = onOpenDestinationDialog,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("open_search_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search Places",
                            tint = CaravanBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Theme Switcher Button
                    IconButton(
                        onClick = onToggleDarkMode,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("toggle_dark_mode")
                    ) {
                        Icon(
                            imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = if (isDarkMode) "Switch to Light Mode" else "Switch to Dark Mode",
                            tint = if (isDarkMode) CaravanAmber else CaravanBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Small shortcut to app settings
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("open_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    // Connection Status
                    Surface(
                        color = when (connectionStatus) {
                            CaravanConnectionStatus.CONNECTED -> CaravanEmerald.copy(alpha = 0.2f)
                            CaravanConnectionStatus.CONNECTING,
                            CaravanConnectionStatus.RECONNECTING -> CaravanAmber.copy(alpha = 0.2f)
                            else -> CaravanCrimson.copy(alpha = 0.2f)
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (connectionStatus) {
                                            CaravanConnectionStatus.CONNECTED -> CaravanEmerald
                                            CaravanConnectionStatus.CONNECTING,
                                            CaravanConnectionStatus.RECONNECTING -> CaravanAmber
                                            else -> CaravanCrimson
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(
                                    text = when (connectionStatus) {
                                        CaravanConnectionStatus.CONNECTED -> "Live"
                                        CaravanConnectionStatus.CONNECTING -> "Connecting"
                                        CaravanConnectionStatus.RECONNECTING -> "Syncing"
                                        else -> "Offline"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = when (connectionStatus) {
                                        CaravanConnectionStatus.CONNECTED -> CaravanEmerald
                                        CaravanConnectionStatus.CONNECTING,
                                        CaravanConnectionStatus.RECONNECTING -> CaravanAmber
                                        else -> CaravanCrimson
                                    }
                                )
                                if (connectionStatus == CaravanConnectionStatus.CONNECTED && connectionMethod != null) {
                                    Text(
                                        text = connectionMethod,
                                        fontSize = 7.sp,
                                        lineHeight = 7.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = CaravanEmerald.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }

                    // Member Count Button
                    FilledTonalButton(
                        onClick = onToggleFleetList,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(30.dp)
                            .testTag("toggle_fleet_button"),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            Icons.Default.DirectionsCar,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$memberCount",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Leave button
                    IconButton(
                        onClick = onLeaveTrip,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("leave_trip_button")
                    ) {
                        Icon(
                            Icons.Default.ExitToApp,
                            contentDescription = "Leave Convoy",
                            tint = CaravanCrimson,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FleetBottomSheet(
    members: List<TripMember>,
    userLocation: DeviceLocation,
    mutedMemberIds: Set<String> = emptySet(),
    onToggleAllMute: () -> Unit = {},
    onToggleMemberMute: (String) -> Unit = {},
    allMuted: Boolean = false,
    onFitAllMembers: () -> Unit = {},
    sharedRoutes: Map<String, com.example.data.model.SharedRoute> = emptyMap(),
    marks: Map<String, MapMark> = emptyMap(),
    isLeader: Boolean = false,
    onKickMember: (String) -> Unit = {},
    onDismiss: () -> Unit,
    onSelectMember: (TripMember) -> Unit,
    onFollowMemberRoute: (String) -> Unit = {},
    onNavigateToMemberMark: (MapMark) -> Unit = {}
) {
    var presenceTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            presenceTick++
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Convoy (${members.size} members)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = onFitAllMembers,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 36.dp, max = 40.dp)
                            .testTag("fleet_fit_all_button")
                    ) {
                        Icon(
                            Icons.Default.FitScreen,
                            contentDescription = "See All",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "See All",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    SmallCircleAction(
                        icon = if (allMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (allMuted) "Unmute all members" else "Mute all members",
                        active = allMuted,
                        onClick = onToggleAllMute
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 270.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(members, key = { "${it.id}-$presenceTick" }) { member ->
                    val hasSharedRoute = sharedRoutes[member.id]?.points?.isNotEmpty() == true
                    val memberMark = marks[member.id]
                    FleetMemberRow(
                        member = member,
                        userLocation = userLocation,
                        hasSharedRoute = hasSharedRoute,
                        hasMark = memberMark != null,
                        isMuted = mutedMemberIds.contains(member.id),
                        canKick = isLeader && !member.isLeader,
                        onKick = { onKickMember(member.id) },
                        onToggleMute = { onToggleMemberMute(member.id) },
                        onFollowRoute = {
                            onFollowMemberRoute(member.id)
                            onDismiss()
                        },
                        onNavigateToMark = {
                            memberMark?.let(onNavigateToMemberMark)
                            onDismiss()
                        },
                        onClick = {
                            onSelectMember(member)
                            onDismiss()
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun FleetMemberRow(
    member: TripMember,
    userLocation: DeviceLocation,
    hasSharedRoute: Boolean = false,
    hasMark: Boolean = false,
    isMuted: Boolean = false,
    canKick: Boolean = false,
    onKick: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onFollowRoute: () -> Unit = {},
    onNavigateToMark: () -> Unit = {},
    onClick: () -> Unit
) {
    var showKickConfirm by remember { mutableStateOf(false) }

    if (showKickConfirm) {
        AlertDialog(
            onDismissRequest = { showKickConfirm = false },
            title = { Text("Remove Member") },
            text = { Text("Are you sure you want to remove ${member.displayName} from this convoy?") },
            confirmButton = {
                Button(
                    onClick = {
                        showKickConfirm = false
                        onKick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CaravanCrimson)
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showKickConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    val mColor = try {
        Color(android.graphics.Color.parseColor(member.avatarColor ?: "#10B981"))
    } catch (_: Exception) {
        CaravanEmerald
    }

    val distanceText = if (member.latitude != null && member.longitude != null) {
        val distM = ConvoyUtils.distanceMeters(
            userLocation.latitude,
            userLocation.longitude,
            member.latitude,
            member.longitude
        )
        ConvoyUtils.formatDistance(distM)
    } else {
        "--"
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("member_row_${member.id}")
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with vehicle color
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(mColor.copy(alpha = 0.2f))
            ) {
                Icon(
                    if (member.isPerson) Icons.Default.Person else Icons.Default.DirectionsCar,
                    contentDescription = null,
                    tint = mColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = member.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (member.isLeader) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = CaravanAmber.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "LEADER",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = CaravanAmber,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                val latestTimestamp = maxOf(member.lastLocationAt ?: 0L, member.lastSeenAt)
                val timeAgo = if (member.connectionStatus == MemberConnectionStatus.CONNECTED) null else ConvoyUtils.formatTimeAgo(latestTimestamp)
                val statusLine = when {
                    member.connectionStatus == MemberConnectionStatus.OFFLINE && timeAgo != null ->
                        "Offline · $timeAgo"
                    member.connectionStatus == MemberConnectionStatus.OFFLINE ->
                        "Offline · last known location"
                    member.connectionStatus == MemberConnectionStatus.RECONNECTING && timeAgo != null ->
                        "GPS weak · $timeAgo"
                    timeAgo != null ->
                        "Last seen $timeAgo"
                    member.isPerson -> "On foot"
                    !member.carName.isNullOrBlank() -> member.carName
                    else -> "Vehicle"
                }
                Text(
                    text = statusLine,
                    fontSize = 12.sp,
                    color = when (member.connectionStatus) {
                        MemberConnectionStatus.OFFLINE -> CaravanCrimson
                        MemberConnectionStatus.RECONNECTING -> CaravanAmber
                        else -> if (timeAgo != null) CaravanAmber else MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            // Route follow takes priority; otherwise offer nav to their mark only
            when {
                hasSharedRoute -> {
                    FilledTonalButton(
                        onClick = onFollowRoute,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = mColor.copy(alpha = 0.18f),
                            contentColor = mColor
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .padding(end = 8.dp)
                            .testTag("follow_route_button_${member.id}")
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = "Follow Path", modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Follow", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                hasMark -> {
                    FilledTonalButton(
                        onClick = onNavigateToMark,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = mColor.copy(alpha = 0.18f),
                            contentColor = mColor
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .padding(end = 8.dp)
                            .testTag("nav_to_mark_button_${member.id}")
                    ) {
                        Icon(Icons.Default.Place, contentDescription = "Go to Mark", modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Go to Mark", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (canKick) {
                IconButton(
                    onClick = { showKickConfirm = true },
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("kick_member_button_${member.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonRemove,
                        contentDescription = "Remove member from convoy",
                        tint = CaravanCrimson,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            IconButton(
                onClick = onToggleMute,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("mute_member_button_${member.id}")
            ) {
                Icon(
                    if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = if (isMuted) "Unmute member" else "Mute member",
                    tint = if (isMuted) CaravanCrimson else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }

            // Speed & Distance
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = ConvoyUtils.formatSpeed(member.speed),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(android.graphics.Color.parseColor(ConvoyUtils.speedColorHex(member.speed)))
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = distanceText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallCircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) CaravanBlue else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
    }
}
