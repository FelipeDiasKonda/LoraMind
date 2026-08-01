package com.example.loramind.domain.repository

import com.example.loramind.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun getAllConversations(): Flow<List<Conversation>>
    suspend fun createConversation(title: String): Conversation
    suspend fun deleteConversation(id: String)
    suspend fun updateTitle(id: String, title: String)
}
