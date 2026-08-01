package com.example.loramind.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.loramind.data.local.entity.ConversationEntity
import com.example.loramind.data.local.entity.MessageEntity

@Database(
    entities = [MessageEntity::class, ConversationEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Cria tabela de conversas
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS conversations (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())

                // Cria uma conversa default para mensagens existentes
                val now = System.currentTimeMillis()
                db.execSQL("""
                    INSERT INTO conversations (id, title, createdAt, updatedAt)
                    VALUES ('default', 'Conversa 1', $now, $now)
                """.trimIndent())

                // Adiciona coluna conversationId na tabela messages
                db.execSQL("ALTER TABLE messages ADD COLUMN conversationId TEXT NOT NULL DEFAULT 'default'")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "loramind_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}