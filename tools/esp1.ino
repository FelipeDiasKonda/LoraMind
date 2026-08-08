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
 *   - Biblioteca LoRa: RadioLib (100% interoperável com Heltec V3 SX1262)
 * 
 * Mesmo firmware para todos os nós clientes — sem nada hardcoded.
 */

#include <SPI.h>
#include <RadioLib.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include "BluetoothSerial.h"
#include "mesh_protocol.h"

// ============================================================================
// CONFIGURAÇÃO
// ============================================================================
#define DEFAULT_TTL    3       // TTL padrão para pacotes criados aqui
#define MSG_ID_LEN     4       // Tamanho do MSG_ID (4 chars = ~1.7M combinações)
#define RELAY_DELAY_MS 100     // Delay antes de retransmitir (evita colisão)

// ============================================================================
// PINOS (ESP32 TTGO / Heltec V2 / SX1276 clássico)
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
// OBJETOS GLOBAIS
// ============================================================================
Adafruit_SSD1306 display(128, 64, &Wire, OLED_RST);
BluetoothSerial SerialBT;

// Módulo SX1276 via RadioLib
SX1276 radio = new Module(SS, DIO0, RST, RADIOLIB_NC);

// Flag de interrupção de recepção LoRa
volatile bool rxFlag = false;

#if defined(ESP8266) || defined(ESP32)
  ICACHE_RAM_ATTR
#endif
void rxDone(void) {
  rxFlag = true;
}

// ============================================================================
// LISTA DE PERGUNTAS PENDENTES (MSG_IDs que EU enviei)
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
// CACHE DE DUPLICATAS (para R packets — evita entrega/relay duplicado)
// ============================================================================
#define SEEN_R_CACHE_SIZE 20
String seenRCache[SEEN_R_CACHE_SIZE];
int seenRIndex = 0;

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
// FUNÇÕES DE PROTOCOLO
// ============================================================================

String generateMsgId() {
  const char chars[] = "abcdefghijklmnopqrstuvwxyz0123456789";
  String id = "";
  for (int i = 0; i < MSG_ID_LEN; i++) {
    id += chars[random(0, 36)];
  }
  return id;
}

String generateBtSuffix() {
  const char hex[] = "0123456789ABCDEF";
  String suffix = "";
  for (int i = 0; i < 4; i++) {
    suffix += hex[random(0, 16)];
  }
  return suffix;
}

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

  if (raw.substring(0, delimiters[0]) != PACKET_PREFIX) return pkt;

  String typeStr = raw.substring(delimiters[0] + 1, delimiters[1]);
  if (typeStr.length() != 1) return pkt;
  char t = typeStr.charAt(0);
  if (t != 'Q' && t != 'R') return pkt;
  pkt.type = t;

  pkt.msgId = raw.substring(delimiters[1] + 1, delimiters[2]);
  if (pkt.msgId.length() == 0) return pkt;

  String ttlStr = raw.substring(delimiters[2] + 1, delimiters[3]);
  pkt.ttl = ttlStr.toInt();
  if (pkt.ttl < 0 || pkt.ttl > 9) return pkt;

  pkt.payload = raw.substring(delimiters[3] + 1);
  if (pkt.payload.length() == 0) return pkt;

  pkt.valid = true;
  return pkt;
}

// ============================================================================
// GERENCIAMENTO DE ESTADO
// ============================================================================

void addPending(String msgId) {
  pendingMsgIds[pendingIndex] = msgId;
  pendingIndex = (pendingIndex + 1) % PENDING_SIZE;
  Serial.print("[NODE] Adicionado pendente: ");
  Serial.println(msgId);
}

bool isPending(String msgId) {
  for (int i = 0; i < PENDING_SIZE; i++) {
    if (pendingMsgIds[i] == msgId) {
      pendingMsgIds[i] = "";
      return true;
    }
  }
  return false;
}

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

bool isResponseDuplicate(String msgId, String payload) {
  String key = msgId + "|" + payload;
  for (int i = 0; i < SEEN_R_CACHE_SIZE; i++) {
    if (seenRCache[i] == key) {
      return true;
    }
  }
  seenRCache[seenRIndex] = key;
  seenRIndex = (seenRIndex + 1) % SEEN_R_CACHE_SIZE;
  return false;
}

// ============================================================================
// DISPLAY OLED
// ============================================================================

void displayInit() {
  pinMode(OLED_RST, OUTPUT);
  digitalWrite(OLED_RST, LOW);
  delay(20);
  digitalWrite(OLED_RST, HIGH);

  Wire.begin(4, 15);
  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C, false, false)) {
    Serial.println("[NODE] Display OLED nao encontrado!");
    return;
  }

  display.clearDisplay();
  display.setTextColor(WHITE);
  display.setTextSize(1);
  display.setCursor(0, 0);
  display.println("LoraMind Mesh Node");
  display.println("Iniciando...");
  display.display();
}

void displayStatus(String line1, String line2, String line3) {
  display.clearDisplay();
  display.setTextSize(1);

  display.setCursor(0, 0);
  display.print("MESH:");
  display.println(btName);

  display.setCursor(0, 14);
  display.println(line1);

  display.setCursor(0, 26);
  display.println(line2);

  display.setCursor(0, 38);
  display.println(line3);

  display.setCursor(0, 52);
  display.print("TX:");
  display.print(msgsSent);
  display.print(" RX:");
  display.print(msgsReceived);
  display.print(" RL:");
  display.print(msgsRelayed);

  display.display();
}

// ============================================================================
// SETUP
// ============================================================================

void setup() {
  Serial.begin(115200);
  delay(1000);

  randomSeed(analogRead(0) ^ (micros() << 16) ^ (millis() << 8));
  btName = "LoraMind_" + generateBtSuffix();

  Serial.println("[NODE] ============================");
  Serial.println("[NODE] LoraMind Mesh Node (RadioLib)");
  Serial.println("[NODE] HW: ESP32 + SX1276");
  Serial.println("[NODE] Modo: MESH (BT + LoRa + Relay)");
  Serial.print("[NODE] Bluetooth: ");
  Serial.println(btName);
  Serial.println("[NODE] ============================");

  displayInit();
  Serial.println("[NODE] Display OLED OK");

  SerialBT.begin(btName);
  Serial.println("[NODE] Bluetooth OK");

  // Inicializa barramento SPI com os pinos corretos do hardware SX1276
  SPI.begin(SCK, MISO, MOSI, SS);
  Serial.println("[NODE] SPI inicializado (SCK:5, MISO:19, MOSI:27, SS:18)");

  // Inicializa LoRa via RadioLib com parâmetros 100% idênticos à Base Station
  Serial.print("[NODE] Inicializando SX1276... ");
  int state = radio.begin(
    915.0,    // Frequência: 915 MHz
    250.0,    // Bandwidth: 250 kHz (absorve desvio de cristal com folga e reduz tempo no ar)
    7,        // Spreading Factor: 7
    5,        // Coding Rate: 4/5
    0x12,     // Sync Word: 0x12
    10,       // Potência TX: 10 dBm
    8         // Preamble: 8 símbolos
  );

  if (state != RADIOLIB_ERR_NONE) {
    Serial.print("FALHOU! Código: ");
    Serial.println(state);
    displayStatus("ERRO", "LoRa falhou", "Código: " + String(state));
    while (1) { delay(1000); }
  }

  // Habilita CRC 16-bit (2 bytes)
  radio.setCRC(2);
  Serial.println("[NODE] CRC: habilitado (2 bytes)");

  // Desativa Low Data Rate Optimize (LDRO) para compatibilidade perfeita em SF7/125kHz
  radio.forceLDRO(false);

  // Garante polaridade padrão de IQ (não invertido)
  radio.invertIQ(false);

  // Configura interrupção DIO0 para recepção não-bloqueante
  radio.setDio0Action(rxDone, RISING);

  // Inicia modo recepção contínua
  radio.startReceive();

  Serial.println("[NODE] LoRa 915MHz OK (RadioLib)");
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

      String msgId = generateMsgId();
      String packet = buildPacket('Q', msgId, DEFAULT_TTL, msg);

      addPending(msgId);
      isDuplicate(msgId);

      // Transmite via LoRa
      int txState = radio.transmit(packet);
      if (txState != RADIOLIB_ERR_NONE) {
        Serial.print("[NODE] ERRO no transmit! Código: ");
        Serial.println(txState);
      }

      msgsSent++;

      Serial.print("[NODE] LoRa enviado [");
      Serial.print(msgId);
      Serial.print("]: \"");
      Serial.print(packet);
      Serial.println("\"");

      displayStatus("ENVIANDO...", "ID:" + msgId, msg.substring(0, 20));

      // Limpa flag que foi disparada pela interrupção de fim de transmissão (TxDone)
      rxFlag = false;

      // Volta a escutar após transmissão
      radio.startReceive();
    }
  }

  // =====================================================================
  // CAMINHO LoRa: Receber pacotes e decidir (entregar / relay / descartar)
  // =====================================================================
  if (rxFlag) {
    rxFlag = false;

    String recebido;
    int state = radio.readData(recebido);

    if (state == RADIOLIB_ERR_NONE) {
      recebido.trim();

      float rssi = radio.getRSSI();
      float snr  = radio.getSNR();

      Serial.print("[NODE] LoRa raw (RSSI:");
      Serial.print(rssi, 1);
      Serial.print(" SNR:");
      Serial.print(snr, 1);
      Serial.print("): \"");
      Serial.print(recebido);
      Serial.println("\"");

      MeshPacket pkt = parsePacket(recebido);

      if (!pkt.valid) {
        Serial.println("[NODE] DESCARTADO (header invalido)");
      } else {
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
          if (isDuplicate(pkt.msgId)) {
            Serial.println("[NODE] Q DESCARTADO (duplicata: " + pkt.msgId + ")");
          } else if (pkt.ttl > 0) {
            int newTtl = pkt.ttl - 1;
            String relayPacket = buildPacket('Q', pkt.msgId, newTtl, pkt.payload);

            delay(RELAY_DELAY_MS);

            radio.transmit(relayPacket);
            rxFlag = false;
            msgsRelayed++;

            Serial.print("[NODE] RELAY Q [");
            Serial.print(pkt.msgId);
            Serial.print("] TTL:");
            Serial.print(pkt.ttl);
            Serial.print("->");
            Serial.println(newTtl);

            displayStatus("RELAY Q", "ID:" + pkt.msgId, pkt.payload.substring(0, 20));
          } else {
            Serial.println("[NODE] Q DESCARTADO (TTL=0)");
          }
        }

        // ===================================================================
        // TIPO R (Resposta) — Verificar se é para mim (MSG_ID pendente)
        // ===================================================================
        else if (pkt.type == 'R') {
          if (isResponseDuplicate(pkt.msgId, pkt.payload)) {
            Serial.println("[NODE] R DESCARTADO (duplicata: " + pkt.msgId + ")");
          } else if (isPending(pkt.msgId)) {
            Serial.print("[NODE] RESPOSTA para mim [");
            Serial.print(pkt.msgId);
            Serial.print("]: \"");
            Serial.print(pkt.payload);
            Serial.println("\"");

            SerialBT.println(pkt.payload);
            msgsReceived++;

            displayStatus("RESPOSTA IA", "RSSI:" + String((int)rssi), pkt.payload.substring(0, 20));
          } else if (pkt.ttl > 0) {
            int newTtl = pkt.ttl - 1;
            String relayPacket = buildPacket('R', pkt.msgId, newTtl, pkt.payload);

            delay(RELAY_DELAY_MS);

            radio.transmit(relayPacket);
            rxFlag = false;
            msgsRelayed++;

            Serial.print("[NODE] RELAY R [");
            Serial.print(pkt.msgId);
            Serial.print("] TTL:");
            Serial.print(pkt.ttl);
            Serial.print("->");
            Serial.println(newTtl);

            displayStatus("RELAY R", "ID:" + pkt.msgId, pkt.payload.substring(0, 20));
          } else {
            Serial.println("[NODE] R DESCARTADO (TTL=0)");
          }
        }
      }
    } else {
      Serial.print("[NODE] Erro na recepção! Código: ");
      Serial.println(state);
    }

    // Volta a escutar
    radio.startReceive();
  }
}