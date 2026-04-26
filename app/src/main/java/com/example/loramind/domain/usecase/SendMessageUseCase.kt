package com.example.loramind.domain.usecase

import com.example.loramind.domain.repository.BluetoothRepository

class SendMessageUseCase(private val repository: BluetoothRepository) {

    suspend operator fun invoke(message: String): Result<Unit> {
        if (message.isBlank()) {
            return Result.failure(Exception("A mensagem não pode ser vazia"))
        }
        return repository.sendMessage(message)
    }
}