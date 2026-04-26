package com.example.loramind.domain.repository

import kotlinx.coroutines.flow.Flow

interface BluetoothRepository {
    suspend fun connectToDevice(deviceName: String): Boolean

    suspend fun sendMessage(message: String): Result<Unit>

    fun observeIncomingMessages(): Flow<String>

    fun disconnect()
}