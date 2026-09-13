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
import com.example.data.model.MemberConnectionStatus
import com.example.data.model.TripMember
import com.example.ui.theme.*
import com.example.ui.viewmodel.CaravanConnectionStatus
import com.example.util.ConvoyUtils

@Composable
fun ConvoyTopBar(
    tripName: String,
    inviteCode: String,
    connectionStatus: CaravanConnectionStatus,
    memberCount: Int,
    isDarkMode: Boolean = false,
    onToggleDarkMode: () -> Unit = {},
    onToggleFleetList: () -> Unit,
    onOpenSettings: () -> Unit,
    onLeaveTrip: () -> Unit,
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
                }

                // Status & Member Count Chips & Theme Switch
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
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
                            containerColor = NightSlateBorder,
                            contentColor = TextPrimary
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
    onDismiss: () -> Unit,
    onSelectMember: (TripMember) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NightSlateSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = NightSlateBorder) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Convoy Fleet (${members.size} vehicles)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(members, key = { it.id }) { member ->
                    FleetMemberRow(
                        member = member,
                        userLocation = userLocation,
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
    onClick: () -> Unit
) {
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
        color = NightSlateCard,
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
                    Icons.Default.DirectionsCar,
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
                        color = TextPrimary
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

                Text(
                    text = member.carName ?: "Vehicle",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            // Speed & Distance
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = ConvoyUtils.formatSpeed(member.speed),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(android.graphics.Color.parseColor(ConvoyUtils.speedColorHex(member.speed)))
                )
                Text(
                    text = distanceText,
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }
    }
}
