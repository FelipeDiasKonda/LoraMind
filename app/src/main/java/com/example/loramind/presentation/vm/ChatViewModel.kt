package com.example.loramind.presentation.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.loramind.domain.model.Message
import com.example.loramind.domain.repository.BluetoothRepository
import com.example.loramind.domain.repository.MessageRepository
import com.example.loramind.domain.usecase.SendMessageUseCase
import com.example.loramind.presentation.vo.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val sendMessageUseCase: SendMessageUseCase,
    private val messageRepository: MessageRepository,
    private val bluetoothRepository: BluetoothRepository
) : ViewModel() {

    private val TAG = "ChatViewModel"

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        Log.d(TAG, "ChatViewModel init - carregando mensagens e observando BT")

        // Carrega mensagens do banco de dados
        viewModelScope.launch {
            messageRepository.getAllMessages().collect { messagesFromDb ->
                Log.d(TAG, "Mensagens do DB: ${messagesFromDb.size}")
                _uiState.update { it.copy(messages = messagesFromDb) }
            }
        }

        // Observa mensagens recebidas via Bluetooth (respostas da IA)
        viewModelScope.launch {
            Log.d(TAG, "Iniciando observação de mensagens BT...")
            bluetoothRepository.observeIncomingMessages()
                .catch { e ->
                    Log.e(TAG, "Erro ao observar mensagens BT", e)
                }
                .collect { incomingMessage ->
                    Log.d(TAG, "Mensagem recebida via BT: \"$incomingMessage\"")

                    if (incomingMessage.isNotBlank()) {
                        val aiMessage = Message(
                            id = UUID.randomUUID().toString(),
                            text = incomingMessage,
                            isFromUser = false,
                            timestamp = System.currentTimeMillis()
                        )

                        messageRepository.saveMessage(aiMessage)
                        Log.d(TAG, "Resposta da IA salva no banco: \"$incomingMessage\"")

                        // Para de mostrar indicador de "digitando"
                        _uiState.update { it.copy(isWaitingForResponse = false) }
                    }
                }
        }
    }

    fun onInputTextChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val currentText = _uiState.value.inputText
        if (currentText.isBlank()) return

        Log.d(TAG, "sendMessage: \"$currentText\"")

        val newMessage = Message(
            id = UUID.randomUUID().toString(),
            text = currentText,
            isFromUser = true,
            timestamp = System.currentTimeMillis()
        )

        _uiState.update {
            it.copy(
                inputText = "",
                isWaitingForResponse = true
            )
        }

        viewModelScope.launch {
            messageRepository.saveMessage(newMessage)
            Log.d(TAG, "Mensagem do usuário salva no banco")

            val result = sendMessageUseCase(currentText)

            if (result.isSuccess) {
                Log.d(TAG, "Mensagem enviada via BT com sucesso! Aguardando resposta da IA...")
            } else {
                Log.e(TAG, "Falha ao enviar mensagem via BT: ${result.exceptionOrNull()}")
                _uiState.update { it.copy(isWaitingForResponse = false) }
            }
        }
    }
}