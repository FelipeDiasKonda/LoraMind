package com.example.loramind.presentation.vm

import androidx.lifecycle.ViewModel
import com.example.loramind.domain.model.Message
import com.example.loramind.presentation.vo.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

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

        _uiState.update { currentState ->
            currentState.copy(
                messages = currentState.messages + newMessage,
                inputText = ""
            )
        }

        // TODO: Call usecase to send message via bluetooth to ESP32
    }
}
