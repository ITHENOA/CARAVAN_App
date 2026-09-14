package com.example.ui.screens.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.model.SavedTrip
import com.example.ui.screens.profile.ProfileDialog
import com.example.ui.theme.*
import com.example.ui.viewmodel.CaravanViewModel
import com.example.util.InviteQr
import com.example.util.QrInviteScanner
import com.journeyapps.barcodescanner.ScanContract

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
    val savedTrips by viewModel.savedTrips.collectAsState()

    var showProfileDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var shareTrip by remember { mutableStateOf<SavedTrip?>(null) }

    var createTripName by remember { mutableStateOf("") }
    var joinInviteCode by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    fun submitJoin(raw: String) {
        if (raw.isBlank() || isSubmitting) return
        isSubmitting = true
        viewModel.joinTrip(raw) { success ->
            isSubmitting = false
            showJoinDialog = false
            if (success) onNavigateToTrip()
            else Toast.makeText(context, "Could not join trip", Toast.LENGTH_SHORT).show()
        }
    }

    // Local camera QR (no Google Play Services) — works when GMS scanner fails/cancels in IR.
    val qrScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim().orEmpty()
        if (raw.isEmpty()) return@rememberLauncherForActivityResult
        joinInviteCode = InviteQr.parseInviteCode(raw)
        showJoinDialog = true
        submitJoin(raw)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) qrScanLauncher.launch(QrInviteScanner.scanOptions())
        else Toast.makeText(context, "Camera permission needed to scan QR", Toast.LENGTH_SHORT).show()
    }
    fun launchQrScan() {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED ->
                qrScanLauncher.launch(QrInviteScanner.scanOptions())
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

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
                        Text("Caravan", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("home_settings_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            val avatarColor = try {
                Color(android.graphics.Color.parseColor(userProfile.avatarColor))
            } catch (_: Exception) {
                CaravanBlue
            }

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showProfileDialog = true }
                    .testTag("driver_profile_card")
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(avatarColor.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            if (userProfile.isPerson) Icons.Default.Person else Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = avatarColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = userProfile.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (userProfile.isPerson) {
                                "On foot"
                            } else {
                                userProfile.carName.ifBlank { "Vehicle" }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    createTripName = "${userProfile.displayName}'s Road Trip"
                    showCreateDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("create_trip_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CaravanBlue)
            ) {
                Icon(Icons.Default.AddRoad, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Create Convoy", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = { showJoinDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("join_trip_button"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.5.dp, CaravanBlue),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CaravanBlue)
            ) {
                Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Join with Code / QR", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Your Convoys",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (savedTrips.isEmpty()) {
                    "Create or join a trip — it stays here until you delete it."
                } else {
                    "${savedTrips.size} saved · leave never deletes"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (savedTrips.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No trips yet.",
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                savedTrips.forEach { trip ->
                    SavedTripCard(
                        trip = trip,
                        onRejoin = {
                            viewModel.rejoinSavedTrip(trip)
                            onNavigateToTrip()
                        },
                        onShare = { shareTrip = trip },
                        onDelete = {
                            viewModel.deleteSavedTrip(trip.tripId)
                            Toast.makeText(context, "Removed from list", Toast.LENGTH_SHORT).show()
                        }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showProfileDialog) {
        ProfileDialog(
            currentProfile = userProfile,
            onDismiss = { showProfileDialog = false },
            onSaveProfile = { name, car, color, kind ->
                viewModel.updateProfile(name, car, color, kind)
                Toast.makeText(context, "Profile updated", Toast.LENGTH_SHORT).show()
            }
        )
    }

    shareTrip?.let { trip ->
        InviteShareDialog(
            tripName = trip.tripName,
            tripId = trip.tripId,
            inviteCode = trip.inviteCode,
            onDismiss = { shareTrip = null }
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSubmitting) showCreateDialog = false },
            title = { Text("Start New Convoy", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Name your trip:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = createTripName,
                        onValueChange = { createTripName = it },
                        label = { Text("Convoy Name") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("create_trip_name_input"),
                        shape = RoundedCornerShape(12.dp)
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
                            if (success) onNavigateToTrip()
                            else Toast.makeText(context, "Could not create trip", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.buttonColors(containerColor = CaravanBlue),
                    modifier = Modifier.testTag("submit_create_trip_button")
                ) {
                    if (isSubmitting) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    else Text("Launch", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }, enabled = !isSubmitting) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSubmitting) showJoinDialog = false },
            title = { Text("Join Convoy", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Enter the invite code, or scan the convoy QR:",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = joinInviteCode,
                        onValueChange = { joinInviteCode = it.trim() },
                        label = { Text("Invite Code") },
                        placeholder = { Text("7K4-M2P") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("join_invite_code_input"),
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            IconButton(
                                onClick = { launchQrScan() },
                                enabled = !isSubmitting,
                                modifier = Modifier.testTag("join_scan_qr_button")
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR")
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { launchQrScan() },
                        enabled = !isSubmitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("join_scan_qr_full_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CaravanBlue),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CaravanBlue)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan QR Code", fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { submitJoin(joinInviteCode) },
                    enabled = !isSubmitting && joinInviteCode.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = CaravanBlue),
                    modifier = Modifier.testTag("submit_join_trip_button")
                ) {
                    if (isSubmitting) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    else Text("Join", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showJoinDialog = false }, enabled = !isSubmitting) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SavedTripCard(
    trip: SavedTrip,
    onRejoin: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CaravanBlue.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("saved_trip_card")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(trip.tripName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Code ${trip.inviteCode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (trip.isLeader) {
                    Surface(color = CaravanAmber.copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "LEADER",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = CaravanAmber,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRejoin,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("rejoin_trip_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = CaravanBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Rejoin", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.QrCode2, contentDescription = "Share QR")
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("delete_saved_trip_button"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CaravanCrimson),
                    border = BorderStroke(1.dp, CaravanCrimson.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete")
                }
            }
        }
    }
}

@Composable
fun InviteShareDialog(
    tripName: String,
    tripId: String,
    inviteCode: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val payload = remember(tripId, inviteCode) { InviteQr.joinPayload(tripId, inviteCode) }
    val qrBitmap = remember(payload) {
        runCatching { InviteQr.bitmap(payload, 640) }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share Convoy", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(tripName, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    inviteCode,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = CaravanBlue,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Invite QR",
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(8.dp)
                    )
                } else {
                    Text("QR unavailable", color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Scan or enter the code",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "Join my Caravan convoy \"$tripName\"\nCode: $inviteCode\n$payload"
                        )
                    }
                    context.startActivity(Intent.createChooser(send, "Share convoy"))
                }
            ) {
                Text("Share")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
