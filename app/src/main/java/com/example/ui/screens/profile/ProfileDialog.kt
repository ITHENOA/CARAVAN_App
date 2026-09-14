package com.example.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.MemberKind
import com.example.data.model.UserProfile
import com.example.ui.theme.*
import com.example.util.ConvoyUtils

@Composable
fun ProfileDialog(
    currentProfile: UserProfile,
    onDismiss: () -> Unit,
    onSaveProfile: (String, String, String, MemberKind) -> Unit
) {
    var name by remember { mutableStateOf(currentProfile.displayName) }
    var car by remember { mutableStateOf(currentProfile.carName) }
    var kind by remember { mutableStateOf(currentProfile.memberKind) }
    var selectedColor by remember { mutableStateOf(currentProfile.avatarColor) }
    val scheme = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (kind == MemberKind.PERSON) Icons.Default.Person else Icons.Default.DirectionsCar,
                    contentDescription = null,
                    tint = CaravanBlue
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Your Profile",
                    color = scheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "How are you joining?",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = kind == MemberKind.VEHICLE,
                        onClick = { kind = MemberKind.VEHICLE },
                        label = { Text("In a car") },
                        leadingIcon = {
                            Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("kind_vehicle"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CaravanBlue.copy(alpha = 0.25f),
                            selectedLabelColor = scheme.onSurface,
                            selectedLeadingIconColor = CaravanBlue
                        )
                    )
                    FilterChip(
                        selected = kind == MemberKind.PERSON,
                        onClick = { kind = MemberKind.PERSON },
                        label = { Text("On foot") },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("kind_person"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CaravanBlue.copy(alpha = 0.25f),
                            selectedLabelColor = scheme.onSurface,
                            selectedLeadingIconColor = CaravanBlue
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (kind == MemberKind.PERSON) "Your Name" else "Driver Name") },
                    placeholder = { Text("e.g. Alex") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("driver_name_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = scheme.onSurface,
                        unfocusedTextColor = scheme.onSurface,
                        focusedBorderColor = CaravanBlue,
                        unfocusedBorderColor = scheme.outline
                    )
                )

                if (kind == MemberKind.VEHICLE) {
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = car,
                        onValueChange = { car = it },
                        label = { Text("Vehicle Description") },
                        placeholder = { Text("e.g. Blue Tacoma or Silver Prius") },
                        leadingIcon = { Icon(Icons.Default.DirectionsCar, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("car_name_input"),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = scheme.onSurface,
                            unfocusedTextColor = scheme.onSurface,
                            focusedBorderColor = CaravanBlue,
                            unfocusedBorderColor = scheme.outline
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (kind == MemberKind.PERSON) "Map marker color:" else "Convoy Vehicle Color:",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(ConvoyUtils.palette) { hexColor ->
                        val color = try {
                            Color(android.graphics.Color.parseColor(hexColor))
                        } catch (_: Exception) {
                            CaravanBlue
                        }
                        val isSelected = ConvoyUtils.normalizeHex(selectedColor) ==
                            ConvoyUtils.normalizeHex(hexColor)

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { selectedColor = hexColor }
                                .then(
                                    if (isSelected) {
                                        Modifier.border(3.dp, Color.White, CircleShape)
                                    } else Modifier
                                )
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveProfile(name, car, selectedColor, kind)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = CaravanBlue),
                modifier = Modifier.testTag("save_profile_button")
            ) {
                Text("Save Profile", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = scheme.onSurfaceVariant)
            }
        }
    )
}
