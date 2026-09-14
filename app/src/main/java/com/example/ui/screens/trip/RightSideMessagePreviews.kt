package com.example.ui.screens.trip

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CaravanEmerald
import com.example.ui.viewmodel.ChatPreviewItem

@Composable
fun RightSideMessagePreviews(
    previews: List<ChatPreviewItem>,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier,
    activeSpeakerName: String? = null,
) {
    Column(
        modifier = modifier
            .widthIn(max = 240.dp)
            .testTag("right_side_message_previews"),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AnimatedVisibility(
            visible = activeSpeakerName != null,
            enter = fadeIn(animationSpec = tween(200)) + expandVertically(),
            exit = fadeOut(animationSpec = tween(200)) + shrinkVertically()
        ) {
            ActiveSpeakerPreviewChip(speakerName = activeSpeakerName.orEmpty())
        }

        previews.forEach { preview ->
            key(preview.id) {
                AnimatedVisibility(
                    visible = true,
                    enter = slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(300)
                    ) + fadeIn(animationSpec = tween(300)),
                    exit = slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(250)
                    ) + fadeOut(animationSpec = tween(250))
                ) {
                    MessagePreviewBubble(
                        preview = preview,
                        onClick = onOpenChat
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveSpeakerPreviewChip(
    speakerName: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .testTag("active_speaker_preview"),
        color = CaravanEmerald.copy(alpha = 0.92f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Active Speaker",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$speakerName is talking...",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MessagePreviewBubble(
    preview: ChatPreviewItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val avatarColorParsed = try {
        Color(android.graphics.Color.parseColor(preview.avatarColor))
    } catch (_: Exception) {
        Color(0xFF0EA5E9)
    }

    Surface(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .border(
                1.dp,
                avatarColorParsed.copy(alpha = 0.5f),
                RoundedCornerShape(16.dp)
            ),
        color = Color(0xEE1E293B),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(avatarColorParsed)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = preview.senderName,
                        color = avatarColorParsed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = preview.text,
                color = Color.White,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDirection = TextDirection.Content
                )
            )
        }
    }
}
