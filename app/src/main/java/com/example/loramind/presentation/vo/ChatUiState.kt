package com.example.loramind.presentation.vo

import com.example.loramind.domain.model.Message

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isModelTyping: Boolean = false
)
