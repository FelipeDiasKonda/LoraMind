package com.example.loramind.presentation.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.loramind.domain.model.Conversation
import com.example.loramind.domain.model.Message
import com.example.loramind.domain.repository.BluetoothRepository
import com.example.loramind.domain.repository.ConversationRepository
import com.example.loramind.domain.repository.MessageRepository
import com.example.loramind.domain.usecase.SendMessageUseCase
import com.example.loramind.presentation.vo.ChatUiState
import kotlinx.coroutines.Job
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
    private val bluetoothRepository: BluetoothRepository,
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    private val TAG = "ChatViewModel"

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Job para coletar mensagens da conversa ativa (cancelado ao trocar de conversa)
    private var messagesJob: Job? = null

    init {
        Log.d(TAG, "ChatViewModel init - carregando conversas e observando BT")

        // Carrega lista de conversas
        viewModelScope.launch {
            conversationRepository.getAllConversations().collect { conversations ->
                Log.d(TAG, "Conversas do DB: ${conversations.size}")

                val currentActive = _uiState.value.activeConversation

                if (conversations.isEmpty()) {
                    // Primeira vez: cria uma conversa default
                    Log.d(TAG, "Nenhuma conversa encontrada, criando default...")
                    val newConv = conversationRepository.createConversation("Nova Conversa")
                    // O flow vai emitir de novo com a nova conversa, então não precisa setar aqui
                } else {
                    // Se não tem conversa ativa, ou a ativa foi deletada, seleciona a primeira
                    val activeStillExists = currentActive != null &&
                            conversations.any { it.id == currentActive.id }

                    val newActive = if (activeStillExists) {
                        conversations.first { it.id == currentActive!!.id }
                    } else {
                        conversations.first()
                    }

                    _uiState.update {
                        it.copy(
                            conversations = conversations,
                            activeConversation = newActive
                        )
                    }

                    // Começa a observar mensagens da conversa ativa
                    observeMessagesForConversation(newActive.id)
                }
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

                    val activeConv = _uiState.value.activeConversation

                    if (incomingMessage.isNotBlank() && activeConv != null) {
                        val aiMessage = Message(
                            id = UUID.randomUUID().toString(),
                            text = incomingMessage,
                            isFromUser = false,
                            timestamp = System.currentTimeMillis(),
                            conversationId = activeConv.id
                        )

                        messageRepository.saveMessage(aiMessage)
                        Log.d(TAG, "Resposta da IA salva na conversa ${activeConv.id}")

                        // Para de mostrar indicador de "digitando"
                        _uiState.update { it.copy(isWaitingForResponse = false) }
                    }
                }
        }
    }

    /**
     * Observa mensagens de uma conversa específica.
     * Cancela a observação anterior ao trocar de conversa.
     */
    private fun observeMessagesForConversation(conversationId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            messageRepository.getMessagesByConversation(conversationId).collect { messages ->
                Log.d(TAG, "Mensagens da conversa $conversationId: ${messages.size}")
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }

    // =========================================================================
    // INPUT
    // =========================================================================

    fun onInputTextChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    // =========================================================================
    // ENVIO DE MENSAGEM
    // =========================================================================

    fun sendMessage() {
        val currentText = _uiState.value.inputText
        val activeConv = _uiState.value.activeConversation
        if (currentText.isBlank() || activeConv == null) return

        Log.d(TAG, "sendMessage: \"$currentText\" na conversa ${activeConv.id}")

        val newMessage = Message(
            id = UUID.randomUUID().toString(),
            text = currentText,
            isFromUser = true,
            timestamp = System.currentTimeMillis(),
            conversationId = activeConv.id
        )

        _uiState.update {
            it.copy(
                inputText = "",
                isWaitingForResponse = true
            )
        }

        viewModelScope.launch {
            messageRepository.saveMessage(newMessage)
            Log.d(TAG, "Mensagem do usuário salva na conversa ${activeConv.id}")

            // Atualiza o título da conversa com a primeira mensagem (se ainda é "Nova Conversa")
            if (activeConv.title == "Nova Conversa") {
                val newTitle = if (currentText.length > 30) {
                    currentText.take(30) + "..."
                } else {
                    currentText
                }
                conversationRepository.updateTitle(activeConv.id, newTitle)
                Log.d(TAG, "Título da conversa atualizado: \"$newTitle\"")
            }

            // Prefixa a mensagem com o conv_id para o Python bridge
            val messageWithConvId = "${activeConv.id}:$currentText"
            val result = sendMessageUseCase(messageWithConvId)

            if (result.isSuccess) {
                Log.d(TAG, "Mensagem enviada via BT com conv_id! Aguardando resposta da IA...")
            } else {
                Log.e(TAG, "Falha ao enviar mensagem via BT: ${result.exceptionOrNull()}")
                _uiState.update { it.copy(isWaitingForResponse = false) }
            }
        }
    }

    // =========================================================================
    // GESTÃO DE CONVERSAS
    // =========================================================================

    /**
     * Abre/fecha o drawer lateral.
     */
    fun toggleDrawer() {
        _uiState.update { it.copy(isDrawerOpen = !it.isDrawerOpen) }
    }

    /**
     * Fecha o drawer lateral.
     */
    fun closeDrawer() {
        _uiState.update { it.copy(isDrawerOpen = false) }
    }

    /**
     * Cria uma nova conversa e a torna ativa.
     */
    fun createConversation() {
        viewModelScope.launch {
            val newConv = conversationRepository.createConversation("Nova Conversa")
            Log.d(TAG, "Nova conversa criada: ${newConv.id}")

            _uiState.update {
                it.copy(
                    activeConversation = newConv,
                    isDrawerOpen = false,
                    isWaitingForResponse = false
                )
            }

            observeMessagesForConversation(newConv.id)
        }
    }

    /**
     * Seleciona uma conversa existente.
     */
    fun selectConversation(conversation: Conversation) {
        Log.d(TAG, "Selecionando conversa: ${conversation.id} - ${conversation.title}")

        _uiState.update {
            it.copy(
                activeConversation = conversation,
                isDrawerOpen = false,
                isWaitingForResponse = false
            )
        }

        observeMessagesForConversation(conversation.id)
    }

    /**
     * Deleta uma conversa e suas mensagens.
     */
    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            Log.d(TAG, "Deletando conversa: ${conversation.id}")

            // Deleta mensagens da conversa
            messageRepository.deleteMessagesByConversation(conversation.id)

            // Deleta a conversa em si
            conversationRepository.deleteConversation(conversation.id)

            // Se a conversa deletada era a ativa, a lógica do collect de conversas
            // vai selecionar automaticamente a próxima disponível (ou criar uma nova)
            Log.d(TAG, "Conversa ${conversation.id} deletada com sucesso")
        }
    }
}