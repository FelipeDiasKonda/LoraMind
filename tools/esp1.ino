/*
 * LoraMind — ESP32 Client Node (Mesh-Capable)
 * 
 * Protocolo Mesh (sem IDs de nó — roteamento por MSG_ID):
 *   Pacote LoRa: LM|TIPO|MSG_ID|TTL|payload
 *     TIPO:    Q = Question (pergunta)  |  R = Response (resposta)
 *     MSG_ID:  4 chars alfanuméricos gerados pelo remetente (ex: a7f3)
 *     TTL:     Hops restantes (0-9), decrementado a cada relay
 *
 * Roteamento:
 *   - Pergunta (Q): sempre encaminhada (relay) — só BS processa
 *   - Resposta (R): se MSG_ID está na lista de perguntas pendentes → entregar
 *                   senão → relay (é resposta de outro nó)
 * 
 * Comportamento:
 *   [IDA]   Bluetooth -> LoRa  (app envia mensagem, nó empacota com header)
 *   [VOLTA] LoRa -> Bluetooth  (resposta com MSG_ID pendente)
 *   [RELAY] LoRa -> LoRa       (pacote de outro nó, retransmite se TTL > 0)
 * 
 * Hardware:
 *   - ESP32 com módulo LoRa SX1276 (915MHz)
 *   - Display OLED SSD1306 128x64
 *   - Bluetooth Clássico (SPP/RFCOMM)
 * 
 * Mesmo firmware para todos os nós clientes — sem nada hardcoded.
 */

#include <SPI.h>
#include <LoRa.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include "BluetoothSerial.h"

// ============================================================================
// CONFIGURAÇÃO
// ============================================================================
#define DEFAULT_TTL    3       // TTL padrão para pacotes criados aqui
#define MSG_ID_LEN     4       // Tamanho do MSG_ID (4 chars = ~1.7M combinações)
#define RELAY_DELAY_MS 100     // Delay antes de retransmitir (evita colisão)

// ============================================================================
// PINOS
// ============================================================================
#define OLED_RST 16
#define SCK      5
#define MISO     19
#define MOSI     27
#define SS       18
#define RST      14
#define DIO0     26

// ============================================================================
// PROTOCOLO
// ============================================================================
#define PACKET_DELIMITER '|'
#define PACKET_PREFIX    "LM"

// ============================================================================
// LISTA DE PERGUNTAS PENDENTES (MSG_IDs que EU enviei)
// Se uma resposta chegar com um desses IDs, é para mim.
// ============================================================================
#define PENDING_SIZE 10
String pendingMsgIds[PENDING_SIZE];
int pendingIndex = 0;

// ============================================================================
// CACHE DE DUPLICATAS (para Q packets — evita relay duplicado)
// ============================================================================
#define SEEN_CACHE_SIZE 20
String seenCache[SEEN_CACHE_SIZE];
int seenIndex = 0;

// ============================================================================
// CONTADORES (para display)
// ============================================================================
unsigned long msgsSent = 0;
unsigned long msgsReceived = 0;
unsigned long msgsRelayed = 0;

// ============================================================================
// NOME BLUETOOTH (gerado com sufixo aleatório no setup)
// ============================================================================
String btName = "LoraMind";

// ============================================================================
// OBJETOS GLOBAIS
// ============================================================================
Adafruit_SSD1306 display(128, 64, &Wire, OLED_RST);
BluetoothSerial SerialBT;

// ============================================================================
// ESTRUTURA DO PACOTE PARSED
// ============================================================================
struct MeshPacket {
  bool valid;
  char type;        // 'Q' ou 'R'
  String msgId;     // ID da mensagem (ex: "a7f3")
  int ttl;          // Hops restantes
  String payload;   // Conteúdo da mensagem
};

// ============================================================================
// FUNÇÕES DE PROTOCOLO
// ============================================================================

/**
 * Gera um MSG_ID aleatório de MSG_ID_LEN caracteres alfanuméricos.
 * 4 chars = 36^4 = ~1.7 milhão de combinações.
 */
String generateMsgId() {
  const char chars[] = "abcdefghijklmnopqrstuvwxyz0123456789";
  String id = "";
  for (int i = 0; i < MSG_ID_LEN; i++) {
    id += chars[random(0, 36)];
  }
  return id;
}

/**
 * Gera um sufixo hex aleatório de 4 chars para o nome Bluetooth.
 */
String generateBtSuffix() {
  const char hex[] = "0123456789ABCDEF";
  String suffix = "";
  for (int i = 0; i < 4; i++) {
    suffix += hex[random(0, 16)];
  }
  return suffix;
}

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
 * Formato esperado: LM|TIPO|MSG_ID|TTL|payload (4 delimitadores)
 */
MeshPacket parsePacket(String raw) {
  MeshPacket pkt;
  pkt.valid = false;

  // Precisa ter pelo menos "LM|Q|abcd|0|x" = 13 chars
  if (raw.length() < 13) return pkt;

  // Encontra os 4 delimitadores
  int delimiters[4];
  int count = 0;
  for (int i = 0; i < (int)raw.length() && count < 4; i++) {
    if (raw.charAt(i) == PACKET_DELIMITER) {
      delimiters[count++] = i;
    }
  }

  if (count < 4) return pkt;  // Formato inválido

  // Campo 0: prefixo "LM"
  String prefix = raw.substring(0, delimiters[0]);
  if (prefix != PACKET_PREFIX) return pkt;

  // Campo 1: tipo (Q ou R)
  String typeStr = raw.substring(delimiters[0] + 1, delimiters[1]);
  if (typeStr.length() != 1) return pkt;
  pkt.type = typeStr.charAt(0);
  if (pkt.type != 'Q' && pkt.type != 'R') return pkt;

  // Campo 2: MSG_ID
  pkt.msgId = raw.substring(delimiters[1] + 1, delimiters[2]);
  if (pkt.msgId.length() < 2) return pkt;  // MSG_ID muito curto

  // Campo 3: TTL
  String ttlStr = raw.substring(delimiters[2] + 1, delimiters[3]);
  pkt.ttl = ttlStr.toInt();

  // Campo 4: payload (tudo após o último delimitador)
  pkt.payload = raw.substring(delimiters[3] + 1);
  pkt.payload.trim();

  pkt.valid = true;
  return pkt;
}

// ============================================================================
// PERGUNTAS PENDENTES
// ============================================================================

/**
 * Adiciona um MSG_ID à lista de perguntas pendentes.
 */
void addPending(String msgId) {
  pendingMsgIds[pendingIndex] = msgId;
  pendingIndex = (pendingIndex + 1) % PENDING_SIZE;
}

/**
 * Verifica se um MSG_ID está na lista de perguntas pendentes.
 * NÃO remove o ID (para suportar respostas multi-chunk com mesmo MSG_ID).
 */
bool isPending(String msgId) {
  for (int i = 0; i < PENDING_SIZE; i++) {
    if (pendingMsgIds[i] == msgId) {
      return true;
    }
  }
  return false;
}

// ============================================================================
// CACHE DE DUPLICATAS (só para pacotes Q)
// ============================================================================

/**
 * Verifica se um pacote Q com esse MSG_ID já foi visto.
 * Se não, adiciona ao cache e retorna false.
 * Se sim, retorna true (é duplicata).
 */
bool isDuplicate(String msgId) {
  // Procura no cache
  for (int i = 0; i < SEEN_CACHE_SIZE; i++) {
    if (seenCache[i] == msgId) {
      return true;
    }
  }

  // Não visto — adiciona ao cache circular
  seenCache[seenIndex] = msgId;
  seenIndex = (seenIndex + 1) % SEEN_CACHE_SIZE;
  return false;
}

// ============================================================================
// FUNÇÕES AUXILIARES - DISPLAY OLED
// ============================================================================

void displayInit() {
  pinMode(OLED_RST, OUTPUT);
  digitalWrite(OLED_RST, LOW);
  delay(20);
  digitalWrite(OLED_RST, HIGH);

  Wire.begin(4, 15);
  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    Serial.println("[NODE] ERRO: Display OLED nao encontrado!");
  }

  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(WHITE);
  display.setCursor(0, 0);
  display.println("LoraMind Mesh");
  display.println(btName);
  display.println("Aguardando...");
  display.display();
}

void displayStatus(String line1, String line2, String line3) {
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(WHITE);

  display.setCursor(0, 0);
  display.println("= " + btName + " =");
  
  display.setCursor(0, 16);
  display.println(line1);
  
  display.setCursor(0, 32);
  display.println(line2);
  
  display.setCursor(0, 48);
  display.println(line3);

  display.display();
}

// ============================================================================
// SETUP
// ============================================================================

void setup() {
  Serial.begin(115200);
  
  // Seed para geração de IDs aleatórios
  randomSeed(analogRead(0) ^ (micros() << 16) ^ (millis() << 8));

  // Gera nome Bluetooth único: "LoraMind_XXXX"
  btName = "LoraMind_" + generateBtSuffix();

  Serial.println("[NODE] ============================");
  Serial.println("[NODE] LoraMind Mesh Node - INICIO");
  Serial.println("[NODE] Modo: MESH (BT + LoRa + Relay)");
  Serial.print("[NODE] Bluetooth: ");
  Serial.println(btName);
  Serial.println("[NODE] ============================");

  // Inicializa Display OLED
  displayInit();
  Serial.println("[NODE] Display OLED OK");

  // Inicializa Bluetooth com nome único
  SerialBT.begin(btName);
  Serial.println("[NODE] Bluetooth OK");

  // Inicializa LoRa
  SPI.begin(SCK, MISO, MOSI, SS);
  LoRa.setPins(SS, RST, DIO0);

  if (!LoRa.begin(915E6)) {
    Serial.println("[NODE] ERRO: LoRa nao inicializou!");
    displayStatus("ERRO", "LoRa falhou", "Reinicie");
    while (1);
  }

  Serial.println("[NODE] LoRa 915MHz OK");
  Serial.println("[NODE] Mesh pronto!");

  displayStatus("Status: OK", "BT: " + btName, "LoRa: 915MHz");
}

// ============================================================================
// LOOP PRINCIPAL
// ============================================================================

void loop() {

  // =====================================================================
  // CAMINHO DE IDA: Bluetooth -> LoRa (pergunta do app local)
  // =====================================================================
  if (SerialBT.available()) {
    String msg = SerialBT.readStringUntil('\n');
    msg.trim();

    if (msg.length() > 0) {
      Serial.print("[NODE] BT recebido: \"");
      Serial.print(msg);
      Serial.println("\"");

      // Gera MSG_ID e empacota
      String msgId = generateMsgId();
      String packet = buildPacket('Q', msgId, DEFAULT_TTL, msg);

      // Registra como pergunta pendente (para reconhecer a resposta)
      addPending(msgId);

      // Também marca como "visto" no cache de duplicatas (evita eco)
      isDuplicate(msgId);

      // Transmite via LoRa
      LoRa.beginPacket();
      LoRa.print(packet);
      LoRa.endPacket();

      msgsSent++;

      Serial.print("[NODE] LoRa enviado [");
      Serial.print(msgId);
      Serial.print("]: \"");
      Serial.print(packet);
      Serial.println("\"");

      displayStatus("ENVIANDO...", "ID:" + msgId, msg.substring(0, 20));

      // Volta para modo recepção LoRa
      LoRa.receive();
    }
  }

  // =====================================================================
  // CAMINHO LoRa: Receber pacotes e decidir (entregar / relay / descartar)
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

    Serial.print("[NODE] LoRa raw (RSSI:");
    Serial.print(rssi);
    Serial.print(" SNR:");
    Serial.print(snr);
    Serial.print("): \"");
    Serial.print(recebido);
    Serial.println("\"");

    // Parse do header mesh
    MeshPacket pkt = parsePacket(recebido);

    if (!pkt.valid) {
      Serial.println("[NODE] DESCARTADO (header invalido)");
      return;
    }

    Serial.print("[NODE] Parsed: TIPO=");
    Serial.print(pkt.type);
    Serial.print(" ID=");
    Serial.print(pkt.msgId);
    Serial.print(" TTL=");
    Serial.println(pkt.ttl);

    // ===================================================================
    // TIPO Q (Pergunta) — Nó cliente nunca processa, apenas faz relay
    // ===================================================================
    if (pkt.type == 'Q') {
      // Verificar duplicata (evita relay de perguntas já vistas)
      if (isDuplicate(pkt.msgId)) {
        Serial.println("[NODE] Q DESCARTADO (duplicata: " + pkt.msgId + ")");
        return;
      }

      // Relay: retransmitir se TTL > 0
      if (pkt.ttl > 0) {
        int newTtl = pkt.ttl - 1;
        String relayPacket = buildPacket('Q', pkt.msgId, newTtl, pkt.payload);

        delay(RELAY_DELAY_MS);

        LoRa.beginPacket();
        LoRa.print(relayPacket);
        LoRa.endPacket();

        msgsRelayed++;

        Serial.print("[NODE] RELAY Q [");
        Serial.print(pkt.msgId);
        Serial.print("] TTL:");
        Serial.print(pkt.ttl);
        Serial.print("->");
        Serial.println(newTtl);

        displayStatus("RELAY Q", "ID:" + pkt.msgId, pkt.payload.substring(0, 20));

        LoRa.receive();
      } else {
        Serial.println("[NODE] Q DESCARTADO (TTL=0)");
      }
    }

    // ===================================================================
    // TIPO R (Resposta) — Verificar se é para mim (MSG_ID pendente)
    // ===================================================================
    else if (pkt.type == 'R') {
      if (isPending(pkt.msgId)) {
        // É resposta para uma pergunta MINHA!
        Serial.print("[NODE] RESPOSTA para mim [");
        Serial.print(pkt.msgId);
        Serial.print("]: \"");
        Serial.print(pkt.payload);
        Serial.println("\"");

        // Entrega ao app via Bluetooth
        SerialBT.println(pkt.payload);
        msgsReceived++;

        displayStatus("RESPOSTA IA", "RSSI:" + String(rssi), pkt.payload.substring(0, 20));
      } else {
        // Não é para mim — relay
        if (pkt.ttl > 0) {
          int newTtl = pkt.ttl - 1;
          String relayPacket = buildPacket('R', pkt.msgId, newTtl, pkt.payload);

          delay(RELAY_DELAY_MS);

          LoRa.beginPacket();
          LoRa.print(relayPacket);
          LoRa.endPacket();

          msgsRelayed++;

          Serial.print("[NODE] RELAY R [");
          Serial.print(pkt.msgId);
          Serial.print("] TTL:");
          Serial.print(pkt.ttl);
          Serial.print("->");
          Serial.println(newTtl);

          displayStatus("RELAY R", "ID:" + pkt.msgId, pkt.payload.substring(0, 20));

          LoRa.receive();
        } else {
          Serial.println("[NODE] R DESCARTADO (TTL=0)");
        }
      }
    }
  }
}