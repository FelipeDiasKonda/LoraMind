package com.example.loramind.data.repository

import com.example.loramind.domain.repository.BluetoothRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class MockBluetoothRepository : BluetoothRepository {

    override suspend fun connectToDevice(deviceName: String): Boolean {
        delay(1000)
        return true
    }

    override suspend fun sendMessage(message: String): Result<Unit> {
        delay(500)
        return Result.success(Unit)
    }

    override fun observeIncomingMessages(): Flow<String> = flow {
    }

    override fun disconnect() {}
}