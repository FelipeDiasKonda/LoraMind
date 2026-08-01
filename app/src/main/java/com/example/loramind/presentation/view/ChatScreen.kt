package com.example.loramind.presentation.view

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.loramind.R
import com.example.loramind.domain.model.Conversation
import com.example.loramind.presentation.vm.ChatViewModel
import com.example.loramind.ui.theme.DarkSlate
import com.example.loramind.ui.theme.DeepBlack
import com.example.loramind.ui.theme.GlassBorder
import com.example.loramind.ui.theme.GlassDark
import com.example.loramind.ui.theme.HeaderDark
import com.example.loramind.ui.theme.LightGray
import com.example.loramind.ui.theme.MediumGray
import com.example.loramind.ui.theme.NeonGreen
import com.example.loramind.ui.theme.SoftSlate
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Sincroniza o estado do drawer com o UI state
    LaunchedEffect(uiState.isDrawerOpen) {
        if (uiState.isDrawerOpen) {
            drawerState.open()
        } else {
            drawerState.close()
        }
    }

    // Sincroniza o fechamento do drawer via gesto com o UI state
    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed && uiState.isDrawerOpen) {
            viewModel.closeDrawer()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ConversationDrawer(
                conversations = uiState.conversations,
                activeConversation = uiState.activeConversation,
                onConversationClick = { viewModel.selectConversation(it) },
                onNewConversation = { viewModel.createConversation() },
                onDeleteConversation = { viewModel.deleteConversation(it) }
            )
        }
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(DeepBlack)
                .imePadding()
        ) {
            // ── Top Bar ──
            LoraMindTopBar(
                onMenuClick = {
                    scope.launch {
                        viewModel.toggleDrawer()
                    }
                }
            )

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
}

// ═══════════════════════════════════════════
// Conversation Drawer — sidebar com lista de conversas
// ═══════════════════════════════════════════
@Composable
private fun ConversationDrawer(
    conversations: List<Conversation>,
    activeConversation: Conversation?,
    onConversationClick: (Conversation) -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: (Conversation) -> Unit
) {
    // Dialog de confirmação para deletar
    var conversationToDelete by remember { mutableStateOf<Conversation?>(null) }

    if (conversationToDelete != null) {
        AlertDialog(
            onDismissRequest = { conversationToDelete = null },
            title = {
                Text(
                    text = stringResource(R.string.delete_conversation_title),
                    color = LightGray
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.delete_conversation_message),
                    color = MediumGray
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    conversationToDelete?.let { onDeleteConversation(it) }
                    conversationToDelete = null
                }) {
                    Text(
                        text = stringResource(R.string.delete_confirm),
                        color = Color(0xFFEF4444)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { conversationToDelete = null }) {
                    Text(
                        text = stringResource(R.string.delete_cancel),
                        color = MediumGray
                    )
                }
            },
            containerColor = DarkSlate,
            shape = RoundedCornerShape(16.dp)
        )
    }

    ModalDrawerSheet(
        drawerContainerColor = HeaderDark,
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 16.dp)
        ) {
            // ── Header ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = null,
                    tint = NeonGreen,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.drawer_title),
                    color = LightGray,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Botão Nova Conversa ──
            Surface(
                color = NeonGreen.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable { onNewConversation() }
                    .border(
                        width = 1.dp,
                        color = NeonGreen.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.new_conversation),
                        tint = NeonGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.new_conversation),
                        color = NeonGreen,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Linha separadora ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(1.dp)
                    .background(GlassBorder)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Lista de conversas ──
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(conversations) { conversation ->
                    ConversationItem(
                        conversation = conversation,
                        isActive = conversation.id == activeConversation?.id,
                        onClick = { onConversationClick(conversation) },
                        onLongClick = { conversationToDelete = conversation }
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// Conversation Item — cada conversa no drawer
// ═══════════════════════════════════════════
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    conversation: Conversation,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    Surface(
        color = if (isActive) NeonGreen.copy(alpha = 0.08f) else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .then(
                if (isActive) {
                    Modifier.border(
                        width = 1.dp,
                        color = NeonGreen.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Ícone de chat com indicador de ativo
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = if (isActive) NeonGreen.copy(alpha = 0.15f) else GlassDark,
                        shape = CircleShape
                    )
                    .then(
                        if (isActive) {
                            Modifier.border(1.dp, NeonGreen.copy(alpha = 0.4f), CircleShape)
                        } else {
                            Modifier.border(1.dp, GlassBorder, CircleShape)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = null,
                    tint = if (isActive) NeonGreen else MediumGray,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    color = if (isActive) LightGray else MediumGray,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = dateFormat.format(Date(conversation.updatedAt)),
                    color = MediumGray.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

// ═══════════════════════════════════════════
// Top Bar — LORA + MIND branding with hamburger menu
// ═══════════════════════════════════════════
@Composable
private fun LoraMindTopBar(onMenuClick: () -> Unit = {}) {
    Surface(
        color = HeaderDark,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hamburger menu button
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(R.string.open_drawer),
                    tint = LightGray,
                    modifier = Modifier.size(24.dp)
                )
            }

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