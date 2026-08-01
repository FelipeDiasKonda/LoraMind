package com.example.loramind.domain.model

data class Message(
    val id: String,
    val text: String,
    val isFromUser: Boolean,
    val timestamp: Long,
    val conversationId: String
)
