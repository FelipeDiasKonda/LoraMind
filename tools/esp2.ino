/*
 * LoraMind — ESP32 Nó 2 (Base / Servidor)
 * 
 * BIDIRECIONAL:
 *   [IDA]   LoRa -> Serial USB  (mensagem do app chega no PC com prefixo MSG_LORAMIND:)
 *   [VOLTA] Serial USB -> LoRa  (resposta da IA volta com prefixo RESP_AI:)
 * 
 * Hardware:
 *   - ESP32 com módulo LoRa SX1276 (915MHz)
 *   - Conectado ao PC via cabo USB
 */

#include <SPI.h>
#include <LoRa.h>

// ============================================================================
// PINOS
// ============================================================================
#define SCK  5
#define MISO 19
#define MOSI 27
#define SS   18
#define RST  14
#define DIO0 26

// ============================================================================
// PREFIXOS DO PROTOCOLO
// ============================================================================
const String MSG_PREFIX  = "MSG_LORAMIND:";
const String RESP_PREFIX = "RESP_AI:";

// ============================================================================
// BUFFER SERIAL
// ============================================================================
String serialBuffer = "";

// ============================================================================
// SETUP
// ============================================================================

void setup() {
  Serial.begin(115200);
  Serial.println("[ESP2] ============================");
  Serial.println("[ESP2] LoraMind Node 2 (Base) - INICIO");
  Serial.println("[ESP2] Modo: BIDIRECIONAL");
  Serial.println("[ESP2] LoRa->Serial (ida) | Serial->LoRa (volta)");
  Serial.println("[ESP2] ============================");

  // Inicializa LoRa
  SPI.begin(SCK, MISO, MOSI, SS);
  LoRa.setPins(SS, RST, DIO0);

  if (!LoRa.begin(915E6)) {
    Serial.println("[ESP2] ERRO: LoRa nao inicializou!");
    while (1); // Trava se LoRa falhar
  }

  Serial.println("[ESP2] LoRa 915MHz OK");
  Serial.println("[ESP2] Pronto! Aguardando dados...");
}

// ============================================================================
// LOOP PRINCIPAL
// ============================================================================

void loop() {
  // ----- CAMINHO DE IDA: LoRa -> Serial USB (para o Python) -----
  int packetSize = LoRa.parsePacket();
  if (packetSize) {
    String recebido = "";
    while (LoRa.available()) {
      recebido += (char)LoRa.read();
    }
    recebido.trim();

    int rssi = LoRa.packetRssi();
    float snr = LoRa.packetSnr();

    Serial.print("[ESP2] LoRa recebido (RSSI:");
    Serial.print(rssi);
    Serial.print(" SNR:");
    Serial.print(snr);
    Serial.print("): \"");
    Serial.print(recebido);
    Serial.println("\"");

    if (recebido.length() > 0) {
      // Verifica se tem o prefixo de validação "LM:"
      // Isso filtra ruído LoRa (pacotes aleatórios/interferência)
      if (recebido.startsWith("LM:")) {
        // Remove o prefixo LM: e envia para o script Python com MSG_LORAMIND:
        String mensagem = recebido.substring(3);
        mensagem.trim();

        if (mensagem.length() > 0) {
          Serial.print(MSG_PREFIX);
          Serial.println(mensagem);

          Serial.print("[ESP2] Enviado para Python: ");
          Serial.print(MSG_PREFIX);
          Serial.println(mensagem);
        }
      } else {
        // Pacote sem prefixo LM: = ruído/interferência — descarta
        Serial.print("[ESP2] DESCARTADO (ruido LoRa): \"");
        Serial.print(recebido.substring(0, 30));
        Serial.println("\"");
      }
    }
  }

  // ----- CAMINHO DE VOLTA: Serial USB -> LoRa (resposta da IA) -----
  while (Serial.available()) {
    char c = (char)Serial.read();

    if (c == '\n') {
      // Linha completa recebida
      serialBuffer.trim();

      if (serialBuffer.startsWith(RESP_PREFIX)) {
        // É uma resposta da IA!
        String resposta = serialBuffer.substring(RESP_PREFIX.length());
        resposta.trim();

        Serial.print("[ESP2] Resposta IA recebida do Python: \"");
        Serial.print(resposta);
        Serial.println("\"");

        if (resposta.length() > 0) {
          // Transmite via LoRa de volta para o ESP1
          // Prefixo "LM:" para validação (filtrar ruído LoRa)
          LoRa.beginPacket();
          LoRa.print("LM:");
          LoRa.print(resposta);
          LoRa.endPacket();

          Serial.print("[ESP2] LoRa enviado (resposta): \"");
          Serial.print(resposta);
          Serial.println("\"");

          // Volta para modo recepção LoRa
          LoRa.receive();
        }
      } else if (serialBuffer.length() > 0) {
        // Dados da serial que não são RESP_AI: — ignora
        Serial.print("[ESP2] Serial ignorado (sem prefixo RESP_AI:): \"");
        Serial.print(serialBuffer);
        Serial.println("\"");
      }

      // Limpa buffer
      serialBuffer = "";
    } else {
      serialBuffer += c;
    }
  }
}