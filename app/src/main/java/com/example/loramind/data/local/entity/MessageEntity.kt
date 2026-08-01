package com.example.loramind.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.loramind.domain.model.Message

// O @Entity avisa ao Room que esta classe deve ser transformada em uma tabela do SQLite
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val text: String,
    val isFromUser: Boolean,
    val timestamp: Long,
    val conversationId: String
) {
    fun toDomainModel(): Message {
        return Message(
            id = id,
            text = text,
            isFromUser = isFromUser,
            timestamp = timestamp,
            conversationId = conversationId
        )
    }
}

fun Message.toEntity(): MessageEntity {
    return MessageEntity(
        id = id,
        text = text,
        isFromUser = isFromUser,
        timestamp = timestamp,
        conversationId = conversationId
    )
}
