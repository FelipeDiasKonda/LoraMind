package com.example.loramind.data.repository

import com.example.loramind.data.local.MessageDao
import com.example.loramind.data.local.entity.toEntity
import com.example.loramind.domain.model.Message
import com.example.loramind.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MessageRepositoryImpl(
    private val dao: MessageDao
) : MessageRepository {

    override fun getAllMessages(): Flow<List<Message>> {
        return dao.getAllMessages().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    override suspend fun saveMessage(message: Message) {
        dao.insertMessage(message.toEntity())
    }
}