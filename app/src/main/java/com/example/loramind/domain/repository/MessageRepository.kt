package com.example.loramind.domain.repository

import com.example.loramind.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getAllMessages(): Flow<List<Message>>
    suspend fun saveMessage(message: Message)
}