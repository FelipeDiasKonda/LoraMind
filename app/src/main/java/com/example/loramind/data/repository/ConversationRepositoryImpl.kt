package com.example.loramind.data.repository

import com.example.loramind.data.local.ConversationDao
import com.example.loramind.data.local.entity.toEntity
import com.example.loramind.domain.model.Conversation
import com.example.loramind.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class ConversationRepositoryImpl(
    private val dao: ConversationDao
) : ConversationRepository {

    override fun getAllConversations(): Flow<List<Conversation>> {
        return dao.getAllConversations().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    override suspend fun createConversation(title: String): Conversation {
        val now = System.currentTimeMillis()
        val conversation = Conversation(
            id = UUID.randomUUID().toString().take(6),
            title = title,
            createdAt = now,
            updatedAt = now
        )
        dao.insertConversation(conversation.toEntity())
        return conversation
    }

    override suspend fun deleteConversation(id: String) {
        dao.deleteConversation(id)
    }

    override suspend fun updateTitle(id: String, title: String) {
        dao.updateConversation(id, title, System.currentTimeMillis())
    }
}
