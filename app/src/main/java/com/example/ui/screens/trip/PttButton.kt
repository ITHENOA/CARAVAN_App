package com.example.ui.screens.trip

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.voice.PttState
import com.example.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun PttButton(
    pttState: PttState,
    activeSpeakerName: String?,
    audioAmplitude: Float = 0f,
    onToggle: () -> Unit,
    onStartPtt: () -> Unit,
    onReleasePtt: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var isLatched by remember { mutableStateOf(false) }

    // Synchronize latch flag with external PTT state
    LaunchedEffect(pttState) {
        if (pttState == PttState.IDLE || pttState == PttState.BUSY) {
            isLatched = false
        }
    }

    // Elapsed talk timer
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(pttState) {
        if (pttState == PttState.TRANSMITTING) {
            elapsedSeconds = 0
            while (true) {
                delay(1000)
                elapsedSeconds++
            }
        } else {
            elapsedSeconds = 0
        }
    }

    val isTransmitting = pttState == PttState.TRANSMITTING
    val isBusy = pttState == PttState.BUSY
    val isRequesting = pttState == PttState.REQUESTING

    // Pulse animation when live
    val infiniteTransition = rememberInfiniteTransition(label = "ptt_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isTransmitting) 1.03f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ptt_scale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isTransmitting -> CaravanCrimson
            isRequesting -> CaravanAmber
            isBusy -> Color(0xFFEA580C)
            else -> MaterialTheme.colorScheme.primary
        },
        label = "ptt_bg_color"
    )

    val labelText = when {
        isTransmitting && isLatched -> "TRANSMITTING (Tap to stop)"
        isTransmitting -> "TRANSMITTING (${elapsedSeconds}s)"
        isRequesting -> "REQUESTING CHANNEL..."
        isBusy -> "${activeSpeakerName ?: "Driver"} is speaking..."
        else -> "PUSH TO TALK"
    }

    val subText = when {
        isTransmitting && isLatched -> "Hands-free on"
        isTransmitting -> "Microphone active (hold or release)"
        isBusy -> "Channel busy"
        else -> "Tap to toggle, or hold to talk"
    }

    // Capture latest state and callbacks in updated state holders to avoid stale references
    val currentPttState by rememberUpdatedState(pttState)
    val currentOnToggle by rememberUpdatedState(onToggle)
    val currentOnStartPtt by rememberUpdatedState(onStartPtt)
    val currentOnReleasePtt by rememberUpdatedState(onReleasePtt)

    Box(
        modifier = modifier
            .height(64.dp)
            .scale(pulseScale)
            .shadow(
                elevation = if (isTransmitting) 14.dp else 4.dp,
                shape = RoundedCornerShape(22.dp)
            )
            .clip(RoundedCornerShape(22.dp))
            .background(backgroundColor)
            .pointerInput(isBusy) {
                if (isBusy) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        // 1. Wait for touch down
                        awaitFirstDown(requireUnconsumed = false)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                        if (currentPttState == PttState.TRANSMITTING || currentPttState == PttState.REQUESTING) {
                            // If currently transmitting, tapping or releasing immediately toggles it OFF
                            waitForUpOrCancellation()
                            isLatched = false
                            currentOnReleasePtt()
                        } else {
                            // Currently IDLE: determine if short tap (toggle on) or long hold (momentary)
                            var didHold = false
                            try {
                                val up = withTimeout(280L) {
                                    waitForUpOrCancellation()
                                }
                                if (up != null) {
                                    // Tap detected (< 280ms): Toggle Latch ON!
                                    isLatched = true
                                    currentOnToggle()
                                }
                            } catch (_: PointerEventTimeoutCancellationException) {
                                // Held longer than 280ms: Momentary Hold Mode!
                                didHold = true
                                isLatched = false
                                currentOnStartPtt()
                                // Wait until user lifts finger
                                waitForUpOrCancellation()
                                currentOnReleasePtt()
                            }
                        }
                    }
                }
            }
            .testTag("ptt_button"),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isTransmitting) Icons.Default.Mic else if (isBusy) Icons.Default.VolumeUp else Icons.Default.MicNone,
                contentDescription = labelText,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = labelText,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subText,
                    color = Color.White.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }

            // Live waveform indicator when speaking
            if (isTransmitting) {
                Spacer(modifier = Modifier.width(10.dp))
                LiveAudioWaveform(amplitude = audioAmplitude)
            }
        }
    }
}

@Composable
fun LiveAudioWaveform(amplitude: Float, modifier: Modifier = Modifier) {
    val barCount = 4
    Row(
        modifier = modifier.height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { index ->
            val factor = when (index) {
                0 -> 0.6f
                1 -> 1.0f
                2 -> 0.8f
                else -> 0.5f
            }
            val targetHeight = (8f + (amplitude * 16f * factor)).coerceIn(6f, 22f).dp
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(targetHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
            )
        }
    }
}

@Composable
fun ChatFab(
    hasUnread: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onTap()
        },
        shape = RoundedCornerShape(22.dp),
        color = CaravanAmber,
        shadowElevation = 6.dp,
        modifier = modifier
            .size(64.dp)
            .testTag("chat_fab")
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ChatBubble,
                contentDescription = "Chat and quick prompts",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
            if (hasUnread) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                        .size(10.dp)
                        .background(CaravanCrimson, CircleShape)
                )
            }
        }
    }
}

/** Record-then-send voice note. Same hold / tap-to-latch UX as [PttButton]. */
@Composable
fun VoiceNoteButton(
    isRecording: Boolean,
    audioAmplitude: Float = 0f,
    enabled: Boolean = true,
    onToggle: () -> Unit,
    onStart: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var isLatched by remember { mutableStateOf(false) }

    LaunchedEffect(isRecording) {
        if (!isRecording) isLatched = false
    }

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isRecording -> CaravanPurple
            !enabled -> MaterialTheme.colorScheme.outline
            else -> CaravanEmerald
        },
        label = "voice_note_bg"
    )

    val currentRecording by rememberUpdatedState(isRecording)
    val currentOnToggle by rememberUpdatedState(onToggle)
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnRelease by rememberUpdatedState(onRelease)

    Box(
        modifier = modifier
            .size(64.dp)
            .shadow(elevation = if (isRecording) 12.dp else 4.dp, shape = RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(backgroundColor)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown(requireUnconsumed = false)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                        if (currentRecording) {
                            waitForUpOrCancellation()
                            isLatched = false
                            currentOnRelease()
                        } else {
                            try {
                                val up = withTimeout(280L) {
                                    waitForUpOrCancellation()
                                }
                                if (up != null) {
                                    isLatched = true
                                    currentOnToggle()
                                }
                            } catch (_: PointerEventTimeoutCancellationException) {
                                isLatched = false
                                currentOnStart()
                                waitForUpOrCancellation()
                                currentOnRelease()
                            }
                        }
                    }
                }
            }
            .testTag("voice_note_button"),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Mic else Icons.Default.MicNone,
                contentDescription = if (isRecording) "Stop voice note" else "Record voice note",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
            if (isRecording) {
                Spacer(modifier = Modifier.height(4.dp))
                LiveAudioWaveform(amplitude = audioAmplitude)
            }
        }
    }
}
