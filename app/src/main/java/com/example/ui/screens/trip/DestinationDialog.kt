package com.example.ui.screens.trip

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.location.DeviceLocation
import com.example.ui.theme.*

data class DestinationPreset(
    val name: String,
    val deltaLat: Double,
    val deltaLng: Double
)

val PRESETS = listOf(
    DestinationPreset("Gas Station ⛽", 0.015, 0.02),
    DestinationPreset("Scenic Overlook 🌄", 0.03, -0.025),
    DestinationPreset("Rest Stop 🚻", -0.02, 0.018),
    DestinationPreset("Summit Pass 🏔️", 0.045, 0.04),
    DestinationPreset("Rendezvous Point 📍", 0.02, -0.01)
)

@Composable
fun DestinationDialog(
    userLocation: DeviceLocation,
    onDismiss: () -> Unit,
    onSetDestination: (Double, Double, String) -> Unit
) {
    var label by remember { mutableStateOf("Scenic Overlook") }
    var latStr by remember { mutableStateOf(String.format(java.util.Locale.US, "%.5f", userLocation.latitude + 0.025)) }
    var lngStr by remember { mutableStateOf(String.format(java.util.Locale.US, "%.5f", userLocation.longitude + 0.025)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NightSlateCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Flag,
                    contentDescription = null,
                    tint = CaravanAmber,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Set Convoy Destination",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Quick Presets:",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(PRESETS) { preset ->
                        FilterChip(
                            selected = label == preset.name,
                            onClick = {
                                label = preset.name
                                latStr = String.format(java.util.Locale.US, "%.5f", userLocation.latitude + preset.deltaLat)
                                lngStr = String.format(java.util.Locale.US, "%.5f", userLocation.longitude + preset.deltaLng)
                            },
                            label = { Text(preset.name, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = NightSlateSurface,
                                selectedContainerColor = CaravanAmber.copy(alpha = 0.3f),
                                labelColor = TextPrimary,
                                selectedLabelColor = CaravanAmber
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Destination Name") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("destination_name_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CaravanAmber,
                        unfocusedBorderColor = NightSlateBorder
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = latStr,
                        onValueChange = { latStr = it },
                        label = { Text("Latitude") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("destination_lat_input"),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = CaravanAmber,
                            unfocusedBorderColor = NightSlateBorder
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = lngStr,
                        onValueChange = { lngStr = it },
                        label = { Text("Longitude") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("destination_lng_input"),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = CaravanAmber,
                            unfocusedBorderColor = NightSlateBorder
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanLat = latStr.replace(',', '.').trim()
                    val cleanLng = lngStr.replace(',', '.').trim()
                    val lat = cleanLat.toDoubleOrNull() ?: (userLocation.latitude + 0.02)
                    val lng = cleanLng.toDoubleOrNull() ?: (userLocation.longitude + 0.02)
                    val safeLabel = if (label.isNotBlank()) label.trim() else "Destination"
                    onSetDestination(lat.coerceIn(-85.0, 85.0), lng.coerceIn(-180.0, 180.0), safeLabel)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = CaravanAmber),
                modifier = Modifier.testTag("confirm_set_destination_button")
            ) {
                Text("Broadcast Route", color = NightSlateBg, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
