package com.example.loramind.presentation.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.loramind.domain.model.Message
import com.example.loramind.domain.repository.MessageRepository
import com.example.loramind.domain.usecase.SendMessageUseCase
import com.example.loramind.presentation.vo.ChatUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val sendMessageUseCase: SendMessageUseCase,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            messageRepository.getAllMessages().collect { messagesFromDb ->
                _uiState.update { it.copy(messages = messagesFromDb) }
            }
        }
    }

    fun onInputTextChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val currentText = _uiState.value.inputText
        if (currentText.isBlank()) return

        val newMessage = Message(
            id = UUID.randomUUID().toString(),
            text = currentText,
            isFromUser = true,
            timestamp = System.currentTimeMillis()
        )

        _uiState.update { it.copy(inputText = "") }

        viewModelScope.launch {
            messageRepository.saveMessage(newMessage)

            val result = sendMessageUseCase(currentText)

            if (result.isSuccess) {
                delay(1000)

                val botReply = Message(
                    id = UUID.randomUUID().toString(),
                    text = "Mensagem enviada com sucesso aguarde a resposta...",
                    isFromUser = false,
                    timestamp = System.currentTimeMillis()
                )

                messageRepository.saveMessage(botReply)
            }
        }
    }
}