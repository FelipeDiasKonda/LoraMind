/*
 * LoraMind — ESP32-S3 Base Station (Heltec WiFi LoRa 32 V3)
 * 
 * Hardware: Heltec WiFi LoRa 32 V3 (ESP32-S3 + SX1262)
 * Biblioteca LoRa: RadioLib (compatível com SX1262)
 * 
 * IMPORTANTE: Os parâmetros LoRa DEVEM ser idênticos aos do ESP1 (SX1276)
 *   para que os dois chips consigam se comunicar.
 *   - 915 MHz, BW 125kHz, SF7, CR 4/5, SyncWord 0x12
 * 
 * Protocolo Mesh (sem IDs de nó — roteamento por MSG_ID):
 *   Pacote LoRa: LM|TIPO|MSG_ID|TTL|payload
 * 
 * Comportamento:
 *   [IDA]   LoRa -> Serial USB  (pergunta chega da mesh, envia para Python)
 *                                Formato: MSG_LORAMIND:MSG_ID|payload
 *   [VOLTA] Serial USB -> LoRa  (resposta do Python, envia para a mesh)
 *                                Formato recebido: RESP_AI:MSG_ID|payload
 * 
 * A Base Station é o único nó que PROCESSA perguntas (Q).
 * Respostas (R) são enviadas de volta para a mesh com o mesmo MSG_ID.
 * 
 * Dependências:
 *   - RadioLib (instalar via Library Manager do Arduino IDE)
 *   - Board: "Heltec WiFi LoRa 32(V3)" no Board Manager
 */

#include <SPI.h>
#include <RadioLib.h>
#include "mesh_protocol.h"

// ============================================================================
// CONFIGURAÇÃO
// ============================================================================
#define DEFAULT_TTL 3          // TTL padrão para respostas enviadas

// ============================================================================
// PINOS — Heltec WiFi LoRa 32 V3 (ESP32-S3 + SX1262)
// ============================================================================
#define LORA_NSS    8          // Chip Select (NSS/CS)
#define LORA_DIO1   14         // Interrupção de recepção (substitui DIO0 do SX1276)
#define LORA_RST    12         // Reset do módulo LoRa
#define LORA_BUSY   13         // Pino BUSY do SX1262 (não existe no SX1276)
#define LORA_SCK    9          // SPI Clock
#define LORA_MISO   11         // SPI MISO
#define LORA_MOSI   10         // SPI MOSI

// ============================================================================
// PROTOCOLO
// ============================================================================
#define PACKET_DELIMITER '|'
#define PACKET_PREFIX    "LM"

const String MSG_PREFIX  = "MSG_LORAMIND:";
const String RESP_PREFIX = "RESP_AI:";

// ============================================================================
// LORA — RadioLib SX1262
// ============================================================================
SPIClass loraSPI(FSPI);
SX1262 radio = new Module(LORA_NSS, LORA_DIO1, LORA_RST, LORA_BUSY, loraSPI);

// Flag de interrupção — setada quando um pacote LoRa chega
volatile bool rxFlag = false;

#if defined(ESP32)
  // ISR no ESP32 precisa do atributo IRAM_ATTR
  void IRAM_ATTR rxDone(void) {
    rxFlag = true;
  }
#else
  void rxDone(void) {
    rxFlag = true;
  }
#endif

// ============================================================================
// CACHE DE DUPLICATAS (para Q packets)
// ============================================================================
#define SEEN_CACHE_SIZE 20
String seenCache[SEEN_CACHE_SIZE];
int seenIndex = 0;

// ============================================================================
// BUFFER SERIAL
// ============================================================================
String serialBuffer = "";

// ============================================================================
// CACHE DE RESPOSTAS ENVIADAS (para ignorar ecos R que voltam via mesh)
// ============================================================================
#define SENT_R_CACHE_SIZE 20
String sentRCache[SENT_R_CACHE_SIZE];
int sentRIndex = 0;

bool isSentResponse(String msgId) {
  for (int i = 0; i < SENT_R_CACHE_SIZE; i++) {
    if (sentRCache[i] == msgId) {
      return true;
    }
  }
  return false;
}

void markSentResponse(String msgId) {
  sentRCache[sentRIndex] = msgId;
  sentRIndex = (sentRIndex + 1) % SENT_R_CACHE_SIZE;
}

// MeshPacket definido em mesh_protocol.h

// ============================================================================
// FUNÇÕES DE PROTOCOLO
// ============================================================================

/**
 * Monta um pacote LoRa com header mesh.
 * Formato: LM|TIPO|MSG_ID|TTL|payload
 */
String buildPacket(char type, String msgId, int ttl, String payload) {
  String pkt = PACKET_PREFIX;
  pkt += PACKET_DELIMITER;
  pkt += type;
  pkt += PACKET_DELIMITER;
  pkt += msgId;
  pkt += PACKET_DELIMITER;
  pkt += String(ttl);
  pkt += PACKET_DELIMITER;
  pkt += payload;
  return pkt;
}

/**
 * Faz parse de um pacote LoRa recebido.
 * Formato: LM|TIPO|MSG_ID|TTL|payload (4 delimitadores)
 */
MeshPacket parsePacket(String raw) {
  MeshPacket pkt;
  pkt.valid = false;

  if (raw.length() < 13) return pkt;

  int delimiters[4];
  int count = 0;
  for (int i = 0; i < (int)raw.length() && count < 4; i++) {
    if (raw.charAt(i) == PACKET_DELIMITER) {
      delimiters[count++] = i;
    }
  }

  if (count < 4) return pkt;

  String prefix = raw.substring(0, delimiters[0]);
  if (prefix != PACKET_PREFIX) return pkt;

  String typeStr = raw.substring(delimiters[0] + 1, delimiters[1]);
  if (typeStr.length() != 1) return pkt;
  pkt.type = typeStr.charAt(0);
  if (pkt.type != 'Q' && pkt.type != 'R') return pkt;

  pkt.msgId = raw.substring(delimiters[1] + 1, delimiters[2]);
  if (pkt.msgId.length() < 2) return pkt;

  String ttlStr = raw.substring(delimiters[2] + 1, delimiters[3]);
  pkt.ttl = ttlStr.toInt();

  pkt.payload = raw.substring(delimiters[3] + 1);
  pkt.payload.trim();

  pkt.valid = true;
  return pkt;
}

/**
 * Verifica duplicata de Q packets.
 */
bool isDuplicate(String msgId) {
  for (int i = 0; i < SEEN_CACHE_SIZE; i++) {
    if (seenCache[i] == msgId) {
      return true;
    }
  }

  seenCache[seenIndex] = msgId;
  seenIndex = (seenIndex + 1) % SEEN_CACHE_SIZE;
  return false;
}

/**
 * Faz parse de uma resposta do Python no formato: MSG_ID|payload
 */
bool parseSerialResponse(String data, String &msgId, String &payload) {
  int pipePos = data.indexOf('|');
  if (pipePos < 0) return false;

  msgId   = data.substring(0, pipePos);
  payload = data.substring(pipePos + 1);
  payload.trim();

  return (msgId.length() > 0 && payload.length() > 0);
}

// ============================================================================
// SETUP
// ============================================================================

void setup() {
  Serial.begin(115200);
  delay(1500);  // Aguarda serial e hardware estabilizarem

  Serial.println("[BS] ============================");
  Serial.println("[BS] LoraMind Base Station V3.2");
  Serial.println("[BS] HW: Heltec V3.2 (ESP32-S3 + SX1262)");
  Serial.println("[BS] Modo: MESH GATEWAY");
  Serial.println("[BS] LoRa<->Serial (bidirecional)");
  Serial.println("[BS] ============================");

  // HELTEC V3.2: Ativa Vext para alimentar periféricos LoRa/OLED
  // V3.2 usa LDO para Vext — LOW = ON na maioria dos V3.x
  // Se não funcionar, troque para HIGH (lógica invertida em alguns V3.2)
  pinMode(36, OUTPUT);
  digitalWrite(36, LOW);
  delay(100);  // Aguarda Vext estabilizar
  Serial.println("[BS] Vext habilitado (GPIO 36 = LOW)");

  // Inicializa SPI com os pinos do Heltec V3
  loraSPI.begin(LORA_SCK, LORA_MISO, LORA_MOSI, LORA_NSS);
  Serial.println("[BS] SPI inicializado");

  // Inicializa SX1262 com parâmetros compatíveis com o SX1276 dos nós ESP1
  // Parâmetros: freq, BW, SF, CR, syncWord, power, preamble, tcxoVoltage, useLDO
  Serial.print("[BS] Inicializando SX1262... ");

  int state = radio.begin(
    915.0,    // Frequência: 915 MHz (deve bater com ESP1)
    250.0,    // Bandwidth: 250 kHz (absorve desvio de cristal com folga e reduz tempo no ar)
    7,        // Spreading Factor: 7
    5,        // Coding Rate: 4/5
    0x12,     // Sync Word: 0x12 (RadioLib trata compatibilidade)
    10,       // Potência TX: 10 dBm
    8,        // Preamble: 8 símbolos
    1.6,      // TCXO Voltage: 1.6V (Heltec V3.2 — se não funcionar, tente 1.7 ou 1.8)
    false     // Regulador: DC-DC (Heltec V3 usa DC-DC, não LDO)
  );

  if (state != RADIOLIB_ERR_NONE) {
    Serial.print("FALHOU! Código de erro: ");
    Serial.println(state);
    Serial.println("[BS] Verifique:");
    Serial.println("[BS]   1. Placa selecionada correta no Arduino IDE?");
    Serial.println("[BS]   2. Módulo LoRa conectado?");
    Serial.println("[BS]   3. Pinos corretos para seu modelo?");

    // Loop com delay para não disparar watchdog
    while (true) {
      delay(10000);
    }
  }

  Serial.println("OK!");

  // CRÍTICO: No Heltec V3, o SX1262 usa DIO2 para controlar o switch de antena (TX/RX).
  // Sem isso, a antena não é ativada e o rádio não recebe nem transmite.
  radio.setDio2AsRfSwitch(true);
  Serial.println("[BS] DIO2 configurado como RF Switch");

  // Habilita CRC 16-bit (2 bytes) — compatível com SX1276
  radio.setCRC(2);
  Serial.println("[BS] CRC: habilitado (2 bytes)");

  // Desativa Low Data Rate Optimize (LDRO) para compatibilidade perfeita em SF7/125kHz
  radio.forceLDRO(false);

  // Garante polaridade padrão de IQ (não invertido)
  radio.invertIQ(false);

  // Configura interrupção DIO1 para recepção não-bloqueante
  radio.setDio1Action(rxDone);

  // Inicia modo recepção contínua
  state = radio.startReceive();
  if (state != RADIOLIB_ERR_NONE) {
    Serial.print("[BS] ERRO ao iniciar recepção! Código: ");
    Serial.println(state);
  }

  Serial.println("[BS] LoRa 915MHz OK (SX1262 — compativel com SX1276)");
  Serial.println("[BS] Pronto! Aguardando dados...");
}

// ============================================================================
// LOOP PRINCIPAL
// ============================================================================

void loop() {

  // =====================================================================
  // HEARTBEAT — confirma que o loop está rodando
  // =====================================================================
  static unsigned long lastHeartbeat = 0;
  if (millis() - lastHeartbeat > 5000) {
    lastHeartbeat = millis();
    Serial.print("[BS] Heartbeat | DIO1=");
    Serial.print(digitalRead(LORA_DIO1));
    Serial.print(" | rxFlag=");
    Serial.println(rxFlag ? "true" : "false");
  }

  // =====================================================================
  // CAMINHO DE IDA: LoRa -> Serial USB (pergunta da mesh para o Python)
  // =====================================================================
  // Backup: polling no pino DIO1 caso a interrupção não funcione
  if (digitalRead(LORA_DIO1) == HIGH && !rxFlag) {
    Serial.println("[BS] DIO1 HIGH detectado por polling (interrupção falhou!)");
    rxFlag = true;
  }

  if (rxFlag) {
    // Limpa a flag ANTES de processar (para não perder interrupções)
    rxFlag = false;

    String recebido;
    int state = radio.readData(recebido);

    if (state == RADIOLIB_ERR_NONE || state == RADIOLIB_ERR_CRC_MISMATCH) {
      recebido.trim();

      float rssi = radio.getRSSI();
      float snr  = radio.getSNR();
      float fErr = radio.getFrequencyError();

      Serial.print("[BS] LoRa raw (RSSI:");
      Serial.print(rssi, 1);
      Serial.print(" SNR:");
      Serial.print(snr, 1);
      Serial.print(" FErr:");
      Serial.print(fErr / 1000.0, 1);
      Serial.print("kHz");
      if (state == RADIOLIB_ERR_CRC_MISMATCH) {
        Serial.print(" CRC-warn");
      }
      Serial.print("): \"");
      Serial.print(recebido);
      Serial.println("\"");

      // Parse do header mesh
      MeshPacket pkt = parsePacket(recebido);

      if (!pkt.valid) {
        Serial.println("[BS] DESCARTADO (header invalido ou corrompido)");
      } else {
        Serial.print("[BS] Parsed: TIPO=");
        Serial.print(pkt.type);
        Serial.print(" ID=");
        Serial.print(pkt.msgId);
        Serial.print(" TTL=");
        Serial.println(pkt.ttl);

        if (pkt.type == 'Q') {
          // Verificar duplicata
          if (isDuplicate(pkt.msgId)) {
            Serial.println("[BS] Q DESCARTADO (duplicata: " + pkt.msgId + ")");
          } else {
            // Pergunta para processar — enviar para Python
            // Formato: MSG_LORAMIND:MSG_ID|payload
            Serial.print(MSG_PREFIX);
            Serial.print(pkt.msgId);
            Serial.print("|");
            Serial.println(pkt.payload);

            Serial.print("[BS] -> Python: [");
            Serial.print(pkt.msgId);
            Serial.print("] \"");
            Serial.print(pkt.payload);
            Serial.println("\"");
          }
        } else {
          // Tipo R na BS — verificar se é eco de uma resposta que nós mesmos enviamos
          if (isSentResponse(pkt.msgId)) {
            // Eco normal da mesh — ignorar silenciosamente
          } else {
            Serial.println("[BS] R externo recebido (ID: " + pkt.msgId + ") — ignorando");
          }
        }
      }
    } else {
      Serial.print("[BS] Erro na recepção! Código: ");
      Serial.println(state);
    }

    // Volta para modo recepção (obrigatório após readData)
    radio.startReceive();
  }

  // =====================================================================
  // CAMINHO DE VOLTA: Serial USB -> LoRa (resposta do Python para a mesh)
  // =====================================================================
  while (Serial.available()) {
    char c = (char)Serial.read();

    if (c == '\n') {
      serialBuffer.trim();

      if (serialBuffer.startsWith(RESP_PREFIX)) {
        // É uma resposta da IA
        String data = serialBuffer.substring(RESP_PREFIX.length());

        // Parse: MSG_ID|payload
        String msgId, payload;
        if (parseSerialResponse(data, msgId, payload)) {
          Serial.print("[BS] Resposta IA [");
          Serial.print(msgId);
          Serial.print("]: \"");
          Serial.print(payload);
          Serial.println("\"");

          // Monta pacote mesh de resposta
          String packet = buildPacket('R', msgId, DEFAULT_TTL, payload);

          // Marca como enviado (para ignorar ecos que voltam da mesh)
          markSentResponse(msgId);

          // Para recepção, transmite, e volta a receber
          radio.standby();
          int state = radio.transmit(packet);

          if (state == RADIOLIB_ERR_NONE) {
            Serial.print("[BS] LoRa enviado: \"");
            Serial.print(packet);
            Serial.println("\"");
          } else {
            Serial.print("[BS] ERRO ao transmitir! Código: ");
            Serial.println(state);
          }

          // Volta para modo recepção
          radio.startReceive();
        } else {
          Serial.println("[BS] ERRO: formato invalido: \"" + data + "\"");
        }
      }

      serialBuffer = "";
    } else {
      serialBuffer += c;
    }
  }
}