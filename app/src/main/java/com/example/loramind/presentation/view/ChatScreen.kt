package com.example.loramind.presentation.view

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.loramind.R
import com.example.loramind.presentation.vm.ChatViewModel
import com.example.loramind.ui.theme.DeepBlack
import com.example.loramind.ui.theme.DarkSlate
import com.example.loramind.ui.theme.GlassBorder
import com.example.loramind.ui.theme.GlassDark
import com.example.loramind.ui.theme.HeaderDark
import com.example.loramind.ui.theme.LightGray
import com.example.loramind.ui.theme.MediumGray
import com.example.loramind.ui.theme.NeonGreen
import com.example.loramind.ui.theme.SoftSlate

@Composable
fun ChatScreen(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
            .imePadding()
    ) {
        // ── Top Bar ──
        LoraMindTopBar()

        // ── Chat Messages ──
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Typing indicator
            if (uiState.isWaitingForResponse) {
                item {
                    TypingIndicator()
                }
            }

            // Messages
            items(uiState.messages.reversed()) { message ->
                ChatBubble(
                    text = message.text,
                    isFromUser = message.isFromUser
                )
            }

            // Empty state
            if (uiState.messages.isEmpty() && !uiState.isWaitingForResponse) {
                item {
                    EmptyState()
                }
            }
        }

        // ── Input Area ──
        ChatInputBar(
            inputText = uiState.inputText,
            onInputChanged = { viewModel.onInputTextChanged(it) },
            onSend = { viewModel.sendMessage() },
            enabled = uiState.inputText.isNotBlank() && !uiState.isWaitingForResponse
        )
    }
}

// ═══════════════════════════════════════════
// Top Bar — LORA + MIND branding with icons
// ═══════════════════════════════════════════
@Composable
private fun LoraMindTopBar() {
    Surface(
        color = HeaderDark,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LORA (white) + MIND (green)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) {
                        append(stringResource(R.string.app_title_lora))
                    }
                    withStyle(SpanStyle(color = NeonGreen, fontWeight = FontWeight.Bold)) {
                        append(stringResource(R.string.app_title_mind))
                    }
                },
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 22.sp,
                    letterSpacing = 3.sp
                )
            )

            Spacer(modifier = Modifier.weight(1f))

            // Signal/Radio icon
            Icon(
                imageVector = Icons.Default.Sensors,
                contentDescription = null,
                tint = NeonGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))

            // Signal bars icon
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = null,
                tint = NeonGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))

            // Bluetooth icon
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                tint = NeonGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))

            // OFFLINE AI badge
            Surface(
                color = NeonGreen,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.offline_ai_badge),
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════
// Chat Bubble — User (neon green) / AI (glass dark)
// ═══════════════════════════════════════════
@Composable
private fun ChatBubble(text: String, isFromUser: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (isFromUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isFromUser) NeonGreen else GlassDark,
            shape = if (isFromUser) {
                RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 4.dp
                )
            } else {
                RoundedCornerShape(
                    topStart = 4.dp,
                    topEnd = 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp
                )
            },
            border = if (!isFromUser) {
                androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
            } else null,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = text,
                color = if (isFromUser) Color.Black else LightGray,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }

        // "Delivered" label for user messages
        if (isFromUser) {
            Text(
                text = stringResource(R.string.message_delivered),
                color = NeonGreen.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════
// Input Bar — Rounded dark field + circular green send button
// ═══════════════════════════════════════════
@Composable
private fun ChatInputBar(
    inputText: String,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Surface(
        color = HeaderDark,
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rounded text field
            TextField(
                value = inputText,
                onValueChange = onInputChanged,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp)),
                placeholder = {
                    Text(
                        text = stringResource(R.string.chat_input_placeholder),
                        color = MediumGray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SoftSlate,
                    unfocusedContainerColor = SoftSlate,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = NeonGreen,
                    focusedTextColor = LightGray,
                    unfocusedTextColor = LightGray
                ),
                shape = RoundedCornerShape(24.dp),
                singleLine = false,
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = LightGray)
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Circular send button
            IconButton(
                onClick = onSend,
                enabled = enabled,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) NeonGreen else NeonGreen.copy(alpha = 0.3f),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = stringResource(R.string.send_button_description),
                    tint = Color.Black,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════
// Typing Indicator — 3 bouncing dots in glass bubble
// ═══════════════════════════════════════════
@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    // Create 3 dots with staggered animations
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "dot3"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            color = GlassDark,
            shape = RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 3 animated dots
                listOf(dot1Alpha, dot2Alpha, dot3Alpha).forEach { alpha ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .alpha(alpha)
                            .background(NeonGreen, CircleShape)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// Empty State — Shown when no messages exist
// ═══════════════════════════════════════════
@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Radio wave icon
        Icon(
            imageVector = Icons.Default.Sensors,
            contentDescription = null,
            tint = NeonGreen.copy(alpha = 0.4f),
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.empty_chat_title),
            color = LightGray.copy(alpha = 0.7f),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.empty_chat_subtitle),
            color = MediumGray,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}