package com.example.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.ui.viewmodel.CaravanViewModel

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
    var hapticsEnabled by remember { mutableStateOf(prefs.isHapticsEnabled) }
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    var neshanApiKey by remember { mutableStateOf(prefs.neshanApiKey) }

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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Convoy Simulation Card
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CaravanEmerald)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Fleet Simulation",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
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
            }

            // Units & Preferences Card
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Appearance & Preferences",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Theme Switcher (Dark vs Light)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Dark Cockpit Theme", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                            Text(if (isDarkMode) "Night high-contrast dark theme" else "Clean daylight theme", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
                        Text("Haptic Feedback", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = hapticsEnabled,
                            onCheckedChange = {
                                hapticsEnabled = it
                                prefs.isHapticsEnabled = it
                            }
                        )
                    }
                }
            }

            // Routing & Maps Integration Card
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Traffic, contentDescription = null, tint = Color(0xFF10B981))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Neshan Navigation API",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Enter your Neshan Developer API Key to calculate live traffic routes via Neshan. You can also open the Neshan App directly from the trip screen.",
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
            }

            // Server Backend Configuration Card
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudQueue, contentDescription = null, tint = CaravanBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Cloudflare Worker Server",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

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
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
