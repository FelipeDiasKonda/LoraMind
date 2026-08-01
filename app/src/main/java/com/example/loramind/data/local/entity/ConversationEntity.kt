package com.example.loramind.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.loramind.domain.model.Conversation

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomainModel(): Conversation {
        return Conversation(
            id = id,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}

fun Conversation.toEntity(): ConversationEntity {
    return ConversationEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
