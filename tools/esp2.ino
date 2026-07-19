/*
 * LoraMind — ESP32 Base Station (Mesh Gateway)
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
 * Hardware:
 *   - ESP32 com módulo LoRa SX1276 (915MHz)
 *   - Conectado ao PC via cabo USB
 */

#include <SPI.h>
#include <LoRa.h>

// ============================================================================
// CONFIGURAÇÃO
// ============================================================================
#define DEFAULT_TTL 3          // TTL padrão para respostas enviadas

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
// PROTOCOLO
// ============================================================================
#define PACKET_DELIMITER '|'
#define PACKET_PREFIX    "LM"

const String MSG_PREFIX  = "MSG_LORAMIND:";
const String RESP_PREFIX = "RESP_AI:";

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
// ESTRUTURA DO PACOTE PARSED
// ============================================================================
struct MeshPacket {
  bool valid;
  char type;        // 'Q' ou 'R'
  String msgId;     // ID da mensagem
  int ttl;          // Hops restantes
  String payload;   // Conteúdo
};

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
  Serial.println("[BS] ============================");
  Serial.println("[BS] LoraMind Base Station - INICIO");
  Serial.println("[BS] Modo: MESH GATEWAY");
  Serial.println("[BS] LoRa<->Serial (bidirecional)");
  Serial.println("[BS] ============================");

  // Inicializa LoRa
  SPI.begin(SCK, MISO, MOSI, SS);
  LoRa.setPins(SS, RST, DIO0);

  if (!LoRa.begin(915E6)) {
    Serial.println("[BS] ERRO: LoRa nao inicializou!");
    while (1);
  }

  Serial.println("[BS] LoRa 915MHz OK");
  Serial.println("[BS] Pronto! Aguardando dados...");
}

// ============================================================================
// LOOP PRINCIPAL
// ============================================================================

void loop() {

  // =====================================================================
  // CAMINHO DE IDA: LoRa -> Serial USB (pergunta da mesh para o Python)
  // =====================================================================
  int packetSize = LoRa.parsePacket();
  if (packetSize) {
    String recebido = "";
    while (LoRa.available()) {
      recebido += (char)LoRa.read();
    }
    recebido.trim();

    int rssi = LoRa.packetRssi();
    float snr = LoRa.packetSnr();

    Serial.print("[BS] LoRa raw (RSSI:");
    Serial.print(rssi);
    Serial.print(" SNR:");
    Serial.print(snr);
    Serial.print("): \"");
    Serial.print(recebido);
    Serial.println("\"");

    // Parse do header mesh
    MeshPacket pkt = parsePacket(recebido);

    if (!pkt.valid) {
      Serial.println("[BS] DESCARTADO (header invalido)");
      return;
    }

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
        return;
      }

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
    } else {
      // Tipo R na BS? Não deveria acontecer em topologia normal, mas relay se preciso
      Serial.println("[BS] R recebido na BS — ignorando (inesperado)");
    }
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

          // Monta pacote mesh de resposta e envia via LoRa
          String packet = buildPacket('R', msgId, DEFAULT_TTL, payload);

          LoRa.beginPacket();
          LoRa.print(packet);
          LoRa.endPacket();

          Serial.print("[BS] LoRa enviado: \"");
          Serial.print(packet);
          Serial.println("\"");

          LoRa.receive();
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