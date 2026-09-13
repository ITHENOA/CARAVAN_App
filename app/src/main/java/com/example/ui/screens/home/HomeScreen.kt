package com.example.ui.screens.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.profile.ProfileDialog
import com.example.ui.theme.*
import com.example.ui.viewmodel.CaravanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: CaravanViewModel,
    onNavigateToTrip: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val userProfile by viewModel.userProfile.collectAsState()
    val savedTrip by viewModel.savedTrip.collectAsState()

    var showProfileDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }

    var createTripName by remember { mutableStateOf("") }
    var joinInviteCode by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = CaravanBlue,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Caravan",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("home_settings_button")
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NightSlateBg)
            )
        },
        containerColor = NightSlateBg
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Driver Profile Card
            val avatarColor = try {
                Color(android.graphics.Color.parseColor(userProfile.avatarColor))
            } catch (_: Exception) {
                CaravanBlue
            }

            Surface(
                color = NightSlateCard,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, NightSlateBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showProfileDialog = true }
                    .testTag("driver_profile_card")
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(avatarColor.copy(alpha = 0.22f))
                    ) {
                        Icon(
                            Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = avatarColor,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = userProfile.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = userProfile.carName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }

                    FilledTonalIconButton(
                        onClick = { showProfileDialog = true },
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = NightSlateSurface,
                            contentColor = TextSecondary
                        )
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit Profile",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Previous / Active Trip Card
            savedTrip?.let { trip ->
                Surface(
                    color = CaravanBlueDark.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CaravanBlue.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("saved_trip_card")
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = null,
                                    tint = CaravanBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Active Convoy",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CaravanBlue,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (trip.isLeader) {
                                Surface(
                                    color = CaravanAmber.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "LEADER",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CaravanAmber,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = trip.tripName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Text(
                            text = "Invite code: ${trip.inviteCode}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    viewModel.rejoinSavedTrip()
                                    onNavigateToTrip()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("rejoin_trip_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = CaravanBlue),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Rejoin Convoy", fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            OutlinedButton(
                                onClick = {
                                    viewModel.deleteSavedTrip()
                                    Toast.makeText(context, "Convoy record cleared", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("delete_saved_trip_button"),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CaravanCrimson),
                                border = androidx.compose.foundation.BorderStroke(1.dp, CaravanCrimson.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Info Card about Caravan
            Surface(
                color = NightSlateCard.copy(alpha = 0.6f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ShareLocation,
                        contentDescription = null,
                        tint = CaravanEmerald,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = "Realtime fleet radar, road telemetry, shared destination routing, and instant walkie-talkie PTT voice.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        lineHeight = 19.sp
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))
            Spacer(modifier = Modifier.height(32.dp))

            // Primary Actions: Create Convoy & Join Convoy
            Button(
                onClick = {
                    createTripName = "${userProfile.displayName}'s Road Trip"
                    showCreateDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("create_trip_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CaravanBlue)
            ) {
                Icon(
                    Icons.Default.AddRoad,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Create Convoy Trip",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedButton(
                onClick = { showJoinDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("join_trip_button"),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, CaravanBlue),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CaravanBlue)
            ) {
                Icon(
                    Icons.Default.QrCode2,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Join Convoy with Code",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Profile Dialog
        if (showProfileDialog) {
            ProfileDialog(
                currentProfile = userProfile,
                onDismiss = { showProfileDialog = false },
                onSaveProfile = { name, car, color ->
                    viewModel.updateProfile(name, car, color)
                    Toast.makeText(context, "Profile updated", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Create Trip Dialog
        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { if (!isSubmitting) showCreateDialog = false },
                containerColor = NightSlateCard,
                title = {
                    Text(
                        "Start New Convoy",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Name your road trip to invite other vehicles into the convoy:",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = createTripName,
                            onValueChange = { createTripName = it },
                            label = { Text("Convoy Name") },
                            placeholder = { Text("e.g. Yosemite Convoy") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("create_trip_name_input"),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = CaravanBlue,
                                unfocusedBorderColor = NightSlateBorder
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isSubmitting = true
                            viewModel.createTrip(createTripName) { success ->
                                isSubmitting = false
                                showCreateDialog = false
                                if (success) {
                                    onNavigateToTrip()
                                } else {
                                    Toast.makeText(context, "Could not create trip", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isSubmitting,
                        colors = ButtonDefaults.buttonColors(containerColor = CaravanBlue),
                        modifier = Modifier.testTag("submit_create_trip_button")
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                        } else {
                            Text("Launch Convoy", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showCreateDialog = false },
                        enabled = !isSubmitting
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }
                }
            )
        }

        // Join Trip Dialog
        if (showJoinDialog) {
            AlertDialog(
                onDismissRequest = { if (!isSubmitting) showJoinDialog = false },
                containerColor = NightSlateCard,
                title = {
                    Text(
                        "Join Existing Convoy",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Enter the 7-character invite code (e.g. 7K4-M2P) or paste the join URL:",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = joinInviteCode,
                            onValueChange = { joinInviteCode = it.uppercase() },
                            label = { Text("Invite Code") },
                            placeholder = { Text("e.g. 7K4-M2P") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("join_invite_code_input"),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = CaravanBlue,
                                unfocusedBorderColor = NightSlateBorder
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (joinInviteCode.isNotBlank()) {
                                isSubmitting = true
                                viewModel.joinTrip(joinInviteCode) { success ->
                                    isSubmitting = false
                                    showJoinDialog = false
                                    if (success) {
                                        onNavigateToTrip()
                                    } else {
                                        Toast.makeText(context, "Could not join trip", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        enabled = !isSubmitting && joinInviteCode.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = CaravanBlue),
                        modifier = Modifier.testTag("submit_join_trip_button")
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                        } else {
                            Text("Join Convoy", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showJoinDialog = false },
                        enabled = !isSubmitting
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }
                }
            )
        }
    }
}
