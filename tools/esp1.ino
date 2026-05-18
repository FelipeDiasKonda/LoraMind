/*
 * LoraMind — ESP32 Nó 1 (Ponto de Entrada)
 * 
 * BIDIRECIONAL:
 *   [IDA]   Bluetooth -> LoRa  (app envia mensagem)
 *   [VOLTA] LoRa -> Bluetooth  (resposta da IA volta pro app)
 * 
 * Hardware:
 *   - ESP32 com módulo LoRa SX1276 (915MHz)
 *   - Display OLED SSD1306 128x64
 *   - Bluetooth Clássico (SPP/RFCOMM)
 */

#include <SPI.h>
#include <LoRa.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include "BluetoothSerial.h"

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
// OBJETOS GLOBAIS
// ============================================================================
Adafruit_SSD1306 display(128, 64, &Wire, OLED_RST);
BluetoothSerial SerialBT;

// ============================================================================
// PREFIXOS DO PROTOCOLO
// ============================================================================
const String RESP_PREFIX = "RESP_AI:";

// ============================================================================
// FUNÇÕES AUXILIARES - DISPLAY OLED
// ============================================================================

void displayInit() {
  // Reset do OLED
  pinMode(OLED_RST, OUTPUT);
  digitalWrite(OLED_RST, LOW);
  delay(20);
  digitalWrite(OLED_RST, HIGH);

  Wire.begin(4, 15); // SDA=4, SCL=15 (padrão Heltec)
  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    Serial.println("[ESP1] ERRO: Display OLED nao encontrado!");
  }

  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(WHITE);
  display.setCursor(0, 0);
  display.println("LoraMind Node 1");
  display.println("Aguardando...");
  display.display();
}

void displayStatus(String line1, String line2, String line3) {
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(WHITE);

  display.setCursor(0, 0);
  display.println("=== LoraMind N1 ===");
  
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
  Serial.println("[ESP1] ============================");
  Serial.println("[ESP1] LoraMind Node 1 - INICIO");
  Serial.println("[ESP1] Modo: BIDIRECIONAL");
  Serial.println("[ESP1] BT->LoRa (ida) | LoRa->BT (volta)");
  Serial.println("[ESP1] ============================");

  // Inicializa Display OLED
  displayInit();
  Serial.println("[ESP1] Display OLED OK");

  // Inicializa Bluetooth
  SerialBT.begin("LoraMind_Node");
  Serial.println("[ESP1] Bluetooth iniciado: LoraMind_Node");

  // Inicializa LoRa
  SPI.begin(SCK, MISO, MOSI, SS);
  LoRa.setPins(SS, RST, DIO0);

  if (!LoRa.begin(915E6)) {
    Serial.println("[ESP1] ERRO: LoRa nao inicializou!");
    displayStatus("ERRO", "LoRa falhou", "Reinicie");
    while (1); // Trava se LoRa falhar
  }

  Serial.println("[ESP1] LoRa 915MHz OK");
  Serial.println("[ESP1] Pronto! Aguardando dados...");

  displayStatus("Status: OK", "BT: LoraMind_Node", "LoRa: 915MHz");
}

// ============================================================================
// LOOP PRINCIPAL
// ============================================================================

void loop() {
  // ----- CAMINHO DE IDA: Bluetooth -> LoRa -----
  if (SerialBT.available()) {
    String msg = SerialBT.readStringUntil('\n');
    msg.trim();

    if (msg.length() > 0) {
      Serial.print("[ESP1] BT recebido: \"");
      Serial.print(msg);
      Serial.println("\"");

      // Transmite via LoRa com prefixo "LM:" para validação
      LoRa.beginPacket();
      LoRa.print("LM:");
      LoRa.print(msg);
      LoRa.endPacket();

      Serial.print("[ESP1] LoRa enviado: \"");
      Serial.print(msg);
      Serial.println("\"");

      displayStatus("ENVIANDO...", "BT -> LoRa", msg.substring(0, 20));

      // Volta para modo recepção LoRa
      LoRa.receive();
    }
  }

  // ----- CAMINHO DE VOLTA: LoRa -> Bluetooth -----
  int packetSize = LoRa.parsePacket();
  if (packetSize) {
    String recebido = "";
    while (LoRa.available()) {
      recebido += (char)LoRa.read();
    }
    recebido.trim();

    int rssi = LoRa.packetRssi();
    float snr = LoRa.packetSnr();

    Serial.print("[ESP1] LoRa recebido (RSSI:");
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
        // Remove o prefixo e envia para o App Android via Bluetooth
        String mensagem = recebido.substring(3);
        mensagem.trim();

        if (mensagem.length() > 0) {
          SerialBT.println(mensagem);

          Serial.print("[ESP1] BT enviado para App: \"");
          Serial.print(mensagem);
          Serial.println("\"");

          displayStatus("RESPOSTA IA", ("RSSI:" + String(rssi)), mensagem.substring(0, 20));
        }
      } else {
        // Pacote sem prefixo LM: = ruído/interferência — descarta
        Serial.print("[ESP1] DESCARTADO (ruido LoRa): \"");
        Serial.print(recebido.substring(0, 30));
        Serial.println("\"");
      }
    }
  }
}