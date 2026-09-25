package com.example.ui.screens.settings

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.roundToInt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import com.example.data.local.PreferencesManager
import com.example.data.network.AetherHelper
import com.example.data.network.DnsPresets
import com.example.data.update.UpdateDownloadState
import com.example.data.update.UpdateInfo
import com.example.ui.theme.CaravanBlue
import com.example.ui.theme.CaravanEmerald
import com.example.ui.viewmodel.CaravanViewModel
import com.example.util.ConvoyUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CaravanViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs = viewModel.prefs
    var apiBaseUrl by remember { mutableStateOf(prefs.apiBaseUrl) }
    var wsBaseUrl by remember { mutableStateOf(prefs.wsBaseUrl) }
    var mockFleet by remember { mutableStateOf(prefs.isMockFleetEnabled) }
    var metricUnits by remember { mutableStateOf(prefs.isMetricUnits) }
    var soundEnabled by remember { mutableStateOf(prefs.isSoundEnabled) }
    var messageSoundEnabled by remember { mutableStateOf(prefs.isMessageSoundEnabled) }
    var hapticsEnabled by remember { mutableStateOf(prefs.isHapticsEnabled) }
    var drivingViewZoom by remember { mutableStateOf(prefs.drivingViewZoom) }
    var drivingMarkerPosition by remember { mutableStateOf(prefs.drivingMarkerPosition) }
    var convoyFramingRadius by remember { mutableStateOf(prefs.convoyFramingRadiusMeters) }
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val mapTheme by viewModel.mapTheme.collectAsState()
    var neshanApiKey by remember { mutableStateOf(prefs.neshanApiKey) }
    var proxyEnabled by remember { mutableStateOf(prefs.isProxyEnabled) }
    var proxyHost by remember { mutableStateOf(prefs.proxyHost) }
    var proxyPortText by remember { mutableStateOf(prefs.proxyPort.toString()) }
    var proxyType by remember { mutableStateOf(prefs.proxyType) }
    var useAether by remember { mutableStateOf(prefs.useAetherProxy) }
    var aetherProtocol by remember { mutableStateOf(prefs.aetherProtocol) }
    var aetherScan by remember { mutableStateOf(prefs.aetherScan) }
    var aetherNoize by remember { mutableStateOf(prefs.aetherNoize) }
    var aetherIp by remember { mutableStateOf(prefs.aetherIpMode) }
    var dnsEnabled by remember { mutableStateOf(prefs.isDnsEnabled) }
    var dnsPreset by remember {
        mutableStateOf(if (dnsEnabled) prefs.dnsPreset.ifBlank { "google" } else "system")
    }
    var dnsCustomPrimary by remember { mutableStateOf(prefs.dnsCustomPrimary) }
    var dnsCustomSecondary by remember { mutableStateOf(prefs.dnsCustomSecondary) }
    var dnsMenuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var aetherListening by remember { mutableStateOf(false) }
    var termuxInstalled by remember { mutableStateOf(AetherHelper.isTermuxInstalled(context)) }

    LaunchedEffect(Unit) {
        while (isActive) {
            termuxInstalled = AetherHelper.isTermuxInstalled(context)
            aetherListening = AetherHelper.isProxyListening()
            delay(2_500L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings & Diagnostics",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsExpandableCard(
                title = "Fleet Simulation",
                subtitle = if (mockFleet) "On" else "Off",
                icon = Icons.Default.DirectionsCar,
                iconTint = CaravanEmerald
            ) {
                Text(
                    "Spawns virtual convoy vehicles (Jeep, Tesla, Camper Van) driving alongside you for multi-car demo testing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Simulated Convoy Cars", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                    Switch(
                        checked = mockFleet,
                        onCheckedChange = {
                            mockFleet = it
                            viewModel.toggleMockFleet(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = CaravanEmerald
                        ),
                        modifier = Modifier.testTag("mock_fleet_switch")
                    )
                }
            }

            SettingsExpandableCard(
                title = "Appearance & Preferences",
                subtitle = if (isDarkMode) "Dark" else "Light",
                icon = Icons.Default.Palette,
                iconTint = CaravanBlue
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dark Cockpit Theme", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        Text(
                            if (isDarkMode) "Night high-contrast cockpit & dark map" else "Clean daylight cockpit & light map",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = isDarkMode,
                        onCheckedChange = { viewModel.toggleDarkMode() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = CaravanBlue
                        )
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 10.dp)
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Map Style", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                    Text(
                        when (mapTheme) {
                            PreferencesManager.MAP_THEME_DARK -> "Dark Night Map"
                            PreferencesManager.MAP_THEME_LIGHT -> "Light Day Map"
                            else -> "Auto (Follow App Theme)"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            PreferencesManager.MAP_THEME_AUTO to "Auto",
                            PreferencesManager.MAP_THEME_DARK to "Dark",
                            PreferencesManager.MAP_THEME_LIGHT to "Light"
                        ).forEach { (mode, label) ->
                            val selected = mapTheme == mode
                            Surface(
                                onClick = { viewModel.setMapTheme(mode) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) CaravanBlue else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 10.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Metric Units", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        Text("km/h and km instead of mph/mi", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(
                        checked = metricUnits,
                        onCheckedChange = {
                            metricUnits = it
                            prefs.isMetricUnits = it
                        }
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 10.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("PTT Radio Audio Chime", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                    Switch(
                        checked = soundEnabled,
                        onCheckedChange = {
                            soundEnabled = it
                            prefs.isSoundEnabled = it
                        }
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 10.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Message Notification Sound", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        Text("Play a sound when a chat message arrives", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(
                        checked = messageSoundEnabled,
                        onCheckedChange = {
                            messageSoundEnabled = it
                            prefs.isMessageSoundEnabled = it
                        },
                        modifier = Modifier.testTag("message_sound_switch")
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 10.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Haptic Feedback", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                    Switch(
                        checked = hapticsEnabled,
                        onCheckedChange = {
                            hapticsEnabled = it
                            prefs.isHapticsEnabled = it
                        }
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 10.dp)
                )

                // Voice & Media Volume Slider
                val audioManager = remember(context) {
                    context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                }
                val maxVol = remember(audioManager) {
                    audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                }
                var musicVolume by remember(audioManager) {
                    mutableFloatStateOf((audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 10).toFloat())
                }
                val volumePercent = if (maxVol > 0) ((musicVolume / maxVol) * 100).roundToInt() else 0

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                if (musicVolume > 0f) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
                                contentDescription = null,
                                tint = CaravanBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    "Voice & Media Volume",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "PTT radio and chime output level",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Text(
                            "$volumePercent%",
                            color = CaravanBlue,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Slider(
                        value = musicVolume,
                        onValueChange = { newVal ->
                            musicVolume = newVal
                            audioManager?.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                newVal.roundToInt(),
                                0
                            )
                        },
                        valueRange = 0f..maxVol.toFloat(),
                        steps = if (maxVol > 1) maxVol - 1 else 0,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            SettingsExpandableCard(
                title = "Driving & Map Framing",
                subtitle = "Zoom ${"%.1f".format(drivingViewZoom)}x • Framing ${ConvoyUtils.formatFramingRadius(convoyFramingRadius)}",
                icon = Icons.Default.DirectionsCar,
                iconTint = CaravanBlue
            ) {
                Text(
                    "Adjust camera zoom, vehicle marker position, and the dynamic framing radius for online convoy members.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Slider 1: Camera Zoom / Distance
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Camera distance (Zoom)", fontWeight = FontWeight.Medium)
                    Text(
                        "${"%.1f".format(drivingViewZoom)}x",
                        color = CaravanBlue,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = drivingViewZoom,
                    onValueChange = {
                        drivingViewZoom = it
                        viewModel.updateDrivingViewZoom(it)
                    },
                    valueRange = 14.0f..18.5f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_driving_view_zoom")
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Far (Wide view)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Close (Detail)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(14.dp))

                // Slider 2: Vehicle Marker Position on Screen
                val rearPercent = ((1f - drivingMarkerPosition) * 100).toInt()
                val positionDescriptor = when {
                    drivingMarkerPosition <= 0.58f -> "Centered (${rearPercent}% rear view)"
                    drivingMarkerPosition <= 0.72f -> "Balanced (${rearPercent}% rear view)"
                    else -> "Lower (${rearPercent}% rear view)"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Vehicle screen position", fontWeight = FontWeight.Medium)
                    Text(
                        positionDescriptor,
                        color = CaravanBlue,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Moves your vehicle marker higher up to reveal more road and convoy members behind you, or lower for a wider view ahead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Slider(
                    value = drivingMarkerPosition,
                    onValueChange = {
                        drivingMarkerPosition = it
                        viewModel.updateDrivingMarkerPosition(it)
                    },
                    valueRange = 0.50f..0.85f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_driving_marker_position")
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Higher (See behind you)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Lower (See ahead)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(14.dp))

                // Slider 3: Dynamic Convoy Framing Radius
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Convoy framing distance", fontWeight = FontWeight.Medium)
                    Text(
                        ConvoyUtils.formatFramingRadius(convoyFramingRadius),
                        color = CaravanBlue,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Specifies how far the map framing button reaches to include online members around you. Drag all the way to the right to frame all online members regardless of distance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                val framingStepIndex = remember(convoyFramingRadius) {
                    val idx = ConvoyUtils.CONVOY_FRAMING_RADIUS_STEPS.indexOf(convoyFramingRadius)
                    if (idx >= 0) idx.toFloat() else (ConvoyUtils.CONVOY_FRAMING_RADIUS_STEPS.size - 1).toFloat()
                }
                Slider(
                    value = framingStepIndex,
                    onValueChange = { stepVal ->
                        val stepIdx = kotlin.math.round(stepVal).toInt().coerceIn(0, ConvoyUtils.CONVOY_FRAMING_RADIUS_STEPS.lastIndex)
                        val newRadius = ConvoyUtils.CONVOY_FRAMING_RADIUS_STEPS[stepIdx]
                        convoyFramingRadius = newRadius
                        viewModel.updateConvoyFramingRadiusMeters(newRadius)
                    },
                    valueRange = 0f..(ConvoyUtils.CONVOY_FRAMING_RADIUS_STEPS.size - 1).toFloat(),
                    steps = ConvoyUtils.CONVOY_FRAMING_RADIUS_STEPS.size - 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_convoy_framing_radius")
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("500 m", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("10 km", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("All Convoy", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SettingsExpandableCard(
                title = "Neshan Navigation API",
                subtitle = if (neshanApiKey.isNotBlank()) "Key set" else "Optional",
                icon = Icons.Default.Traffic,
                iconTint = Color(0xFF10B981)
            ) {
                Text(
                    "Enter your Neshan Developer API Key to calculate live traffic routes via Neshan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = neshanApiKey,
                    onValueChange = {
                        neshanApiKey = it
                        prefs.neshanApiKey = it
                    },
                    label = { Text("Neshan API Key (Optional)") },
                    placeholder = { Text("service.xxxxxx") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_neshan_key_input"),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            SettingsExpandableCard(
                title = "DNS",
                subtitle = DnsPresets.byId(dnsPreset).title,
                icon = Icons.Default.Dns,
                iconTint = CaravanEmerald
            ) {
                Text(
                    "Used when Caravan resolves API and WebSocket hosts. Does not change the whole phone DNS.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Use custom DNS", fontWeight = FontWeight.Medium)
                    Switch(
                        checked = dnsEnabled,
                        onCheckedChange = {
                            dnsEnabled = it
                            prefs.isDnsEnabled = it
                            if (!it) {
                                dnsPreset = "system"
                                prefs.dnsPreset = "system"
                            } else if (dnsPreset == "system") {
                                dnsPreset = DnsPresets.ALL.first { preset -> preset.id != "system" }.id
                                prefs.dnsPreset = dnsPreset
                            }
                            viewModel.applyProxySettings()
                        },
                        modifier = Modifier.testTag("dns_enabled")
                    )
                }
                if (dnsEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))

                    ExposedDropdownMenuBox(
                        expanded = dnsMenuExpanded,
                        onExpandedChange = { dnsMenuExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = DnsPresets.byId(dnsPreset).let { "${it.title} — ${it.subtitle}" },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("DNS server") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dnsMenuExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .testTag("dns_preset_dropdown"),
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = dnsMenuExpanded,
                            onDismissRequest = { dnsMenuExpanded = false }
                        ) {
                            DnsPresets.ALL.filterNot { it.id == "system" }.forEach { preset ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(preset.title, fontWeight = FontWeight.Medium)
                                            Text(preset.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = {
                                        dnsPreset = preset.id
                                        prefs.dnsPreset = preset.id
                                        dnsMenuExpanded = false
                                        viewModel.applyProxySettings()
                                    },
                                    modifier = Modifier.testTag("dns_preset_${preset.id}")
                                )
                            }
                        }
                    }
                }

                if (dnsEnabled && dnsPreset == "custom") {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = dnsCustomPrimary,
                        onValueChange = {
                            dnsCustomPrimary = it
                            prefs.dnsCustomPrimary = it
                            if (it.trim().length >= 7) viewModel.applyProxySettings()
                        },
                        label = { Text("Primary DNS") },
                        placeholder = { Text("1.1.1.1") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dns_custom_primary"),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dnsCustomSecondary,
                        onValueChange = {
                            dnsCustomSecondary = it
                            prefs.dnsCustomSecondary = it
                            viewModel.applyProxySettings()
                        },
                        label = { Text("Secondary DNS (optional)") },
                        placeholder = { Text("1.0.0.1") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dns_custom_secondary"),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            SettingsExpandableCard(
                title = "Proxy",
                subtitle = when {
                    useAether -> "Aether"
                    proxyEnabled -> "Manual"
                    else -> "System"
                },
                icon = Icons.Default.SettingsEthernet,
                iconTint = CaravanBlue
            ) {
                Text(
                    "Manual proxy or Aether SOCKS for API and WebSocket traffic.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                Text("Manual Proxy", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable Manual Proxy", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        Text(
                            if (proxyEnabled && !useAether) "Custom host and port" else "Off",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = proxyEnabled && !useAether,
                        onCheckedChange = {
                            proxyEnabled = it
                            prefs.isProxyEnabled = it
                            if (it) {
                                useAether = false
                                prefs.useAetherProxy = false
                            }
                            viewModel.applyProxySettings()
                        },
                        enabled = !useAether,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = CaravanBlue
                        ),
                        modifier = Modifier.testTag("proxy_enabled_switch")
                    )
                }

                if (proxyEnabled && !useAether) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = proxyType == "http",
                            onClick = {
                                proxyType = "http"
                                prefs.proxyType = "http"
                                viewModel.applyProxySettings()
                            },
                            label = { Text("HTTP") }
                        )
                        FilterChip(
                            selected = proxyType == "socks",
                            onClick = {
                                proxyType = "socks"
                                prefs.proxyType = "socks"
                                viewModel.applyProxySettings()
                            },
                            label = { Text("SOCKS5") }
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = {
                            proxyHost = it
                            prefs.proxyHost = it
                            if (it.trim().length >= 3) viewModel.applyProxySettings()
                        },
                        label = { Text("Proxy Host") },
                        placeholder = { Text("127.0.0.1") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_proxy_host_input"),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = proxyPortText,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }.take(5)
                            proxyPortText = digits
                            digits.toIntOrNull()?.takeIf { it in 1..65535 }?.let { port ->
                                prefs.proxyPort = port
                                if (proxyHost.trim().length >= 3) viewModel.applyProxySettings()
                            }
                        },
                        label = { Text("Proxy Port") },
                        placeholder = { Text("8080") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_proxy_port_input"),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 14.dp)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Aether", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Local SOCKS5 via Termux at ${AetherHelper.SOCKS_HOST}:${AetherHelper.SOCKS_PORT}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable Aether", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        Text(
                            "SOCKS ${AetherHelper.SOCKS_HOST}:${AetherHelper.SOCKS_PORT}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = useAether,
                        onCheckedChange = {
                            useAether = it
                            prefs.useAetherProxy = it
                            if (it) {
                                proxyEnabled = false
                                prefs.isProxyEnabled = false
                            }
                            viewModel.applyProxySettings()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFFF59E0B)
                        ),
                        modifier = Modifier.testTag("aether_use_switch")
                    )
                }

                if (useAether) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = when {
                                aetherListening -> "Status: running on :${AetherHelper.SOCKS_PORT}"
                                termuxInstalled -> "Status: Termux found — Aether not listening yet"
                                else -> "Status: Termux not installed"
                            },
                            color = if (aetherListening) CaravanEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    if (!termuxInstalled) {
                        FilledTonalButton(
                            onClick = { AetherHelper.openTermuxOrStore(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Install Termux")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    OutlinedButton(
                        onClick = { AetherHelper.runInTermux(context, AetherHelper.INSTALL_COMMAND) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("aether_install_button")
                    ) {
                        Text(if (termuxInstalled) "Install / Update Aether in Termux" else "Copy Aether install command")
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Protocol", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("masque" to "MASQUE", "wg" to "WireGuard", "gool" to "gool").forEach { (id, label) ->
                            FilterChip(
                                selected = aetherProtocol == id,
                                onClick = {
                                    aetherProtocol = id
                                    prefs.aetherProtocol = id
                                    if (id == "masque" && aetherNoize !in listOf("firewall", "gfw", "off")) {
                                        aetherNoize = "firewall"
                                        prefs.aetherNoize = "firewall"
                                    } else if (id != "masque" && aetherNoize !in listOf("balanced", "aggressive", "light", "off")) {
                                        aetherNoize = "balanced"
                                        prefs.aetherNoize = "balanced"
                                    }
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Scan", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRowChips(
                        options = listOf("turbo", "balanced", "thorough", "stealth", "ironclad"),
                        selected = aetherScan,
                        onSelect = {
                            aetherScan = it
                            prefs.aetherScan = it
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("IP", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("4" to "IPv4", "6" to "IPv6", "dual" to "Dual").forEach { (id, label) ->
                            FilterChip(
                                selected = aetherIp == id,
                                onClick = {
                                    aetherIp = id
                                    prefs.aetherIpMode = id
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Obfuscation", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRowChips(
                        options = if (aetherProtocol == "masque") {
                            listOf("firewall", "gfw", "off")
                        } else {
                            listOf("balanced", "aggressive", "light", "off")
                        },
                        selected = aetherNoize,
                        onSelect = {
                            aetherNoize = it
                            prefs.aetherNoize = it
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val cmd = AetherHelper.buildStartCommand(
                                protocol = aetherProtocol,
                                scan = aetherScan,
                                noize = aetherNoize,
                                ipMode = aetherIp
                            )
                            AetherHelper.runInTermux(context, cmd)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("aether_start_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                    ) {
                        Text("Start Aether in Termux")
                    }
                }
            }

            SettingsExpandableCard(
                title = "Cloudflare Worker Server",
                subtitle = "API & WebSocket",
                icon = Icons.Default.CloudQueue,
                iconTint = CaravanBlue
            ) {
                OutlinedTextField(
                    value = apiBaseUrl,
                    onValueChange = {
                        apiBaseUrl = it
                        prefs.apiBaseUrl = it
                        viewModel.apiClient.updateBaseUrl(it)
                    },
                    label = { Text("API Base URL") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_api_url_input"),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = wsBaseUrl,
                    onValueChange = {
                        wsBaseUrl = it
                        prefs.wsBaseUrl = it
                        viewModel.wsClient.updateWsBaseUrl(it)
                    },
                    label = { Text("WebSocket Base URL") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_ws_url_input"),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = {
                        apiBaseUrl = "https://caravan-backend.ithenoa.workers.dev"
                        wsBaseUrl = "wss://caravan-backend.ithenoa.workers.dev"
                        prefs.apiBaseUrl = apiBaseUrl
                        prefs.wsBaseUrl = wsBaseUrl
                        viewModel.apiClient.updateBaseUrl(apiBaseUrl)
                        viewModel.wsClient.updateWsBaseUrl(wsBaseUrl)
                    },
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = CaravanBlue
                    )
                ) {
                    Text("Reset to Default Server")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // App Updates (Cloudflare Worker & Differential Delta Patching)
            val updateState by viewModel.updateState.collectAsState()
            SettingsExpandableCard(
                title = "App Updates",
                subtitle = "Check for delta patches and new releases",
                icon = Icons.Default.SystemUpdate,
                iconTint = CaravanBlue,
                initiallyExpanded = true
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Installed version: v${com.example.BuildConfig.VERSION_NAME} (code ${com.example.BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Server updates endpoint: ${prefs.apiBaseUrl}/api/version",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    when (val state = updateState) {
                        is UpdateDownloadState.Idle -> {
                            Button(
                                onClick = { viewModel.checkForAppUpdates() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("check_for_updates_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = CaravanBlue)
                            ) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Check for Updates")
                            }
                        }
                        is UpdateDownloadState.Checking -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CaravanBlue)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Connecting to server and checking version...", fontSize = 13.sp)
                            }
                        }
                        is UpdateDownloadState.UpToDate -> {
                            Surface(
                                color = CaravanEmerald.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CaravanEmerald, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        "You are using the latest version of Caravan.",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.checkForAppUpdates() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Check Again")
                            }
                        }
                        is UpdateDownloadState.Available -> {
                            val info = state.info
                            Surface(
                                color = CaravanBlue.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, CaravanBlue.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "Version ${info.versionName} available",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = CaravanBlue
                                        )
                                        if (info.isPatchAvailable) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                color = CaravanEmerald,
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    "DELTA PATCH",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        info.changelog,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    if (info.isPatchAvailable) {
                                        Text(
                                            "Smart Delta Update: Only changes will be downloaded, saving data and time.",
                                            fontSize = 11.sp,
                                            color = CaravanEmerald
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                    }
                                    Button(
                                        onClick = { viewModel.downloadAndInstallUpdate(info) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("download_update_button"),
                                        colors = ButtonDefaults.buttonColors(containerColor = CaravanBlue)
                                    ) {
                                        Text(if (info.isPatchAvailable) "Download & Apply Delta Patch" else "Download & Install Update")
                                    }
                                }
                            }
                        }
                        is UpdateDownloadState.Downloading -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(state.statusText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("${state.progressPercent}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CaravanBlue)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { state.progressPercent / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp),
                                    color = CaravanBlue,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }
                        is UpdateDownloadState.ReadyToInstall -> {
                            Surface(
                                color = CaravanEmerald.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Package downloaded and ready for installation.", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = { viewModel.updateManager.promptInstall(state.apkFile) },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = CaravanEmerald)
                                    ) {
                                        Text("Open Package Installer")
                                    }
                                }
                            }
                        }
                        is UpdateDownloadState.Error -> {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "Update Error: ${state.message}",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        OutlinedButton(onClick = { viewModel.dismissUpdateState() }) {
                                            Text("Dismiss")
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(onClick = { viewModel.checkForAppUpdates() }) {
                                            Text("Retry")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Caravan",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Built by ITHENOA · Version ${com.example.BuildConfig.VERSION_NAME}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SettingsExpandableCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    content()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { id ->
            FilterChip(
                selected = selected == id,
                onClick = { onSelect(id) },
                label = { Text(id) }
            )
        }
    }
}
