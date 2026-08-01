package com.example.loramind.presentation.vo

import com.example.loramind.domain.model.Conversation
import com.example.loramind.domain.model.Message

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isWaitingForResponse: Boolean = false,
    val conversations: List<Conversation> = emptyList(),
    val activeConversation: Conversation? = null,
    val isDrawerOpen: Boolean = false
)
