# LoraMind - Contexto Técnico do Projeto

## 1. Identificação e Visão Geral
- **Projeto**: LoraMind
- **Objetivo**: Criar um ecossistema de comunicação descentralizada para zonas de sombra (sem internet), utilizando rádio LoRa para conectar um aplicativo móvel a um servidor de Inteligência Artificial Generativa (LLM) offline.

## 2. Estado Atual da Arquitetura
O sistema já está funcional de ponta a ponta na parte de hardware. O fluxo de dados atual é:
1.  **App Android**: Envia um texto puro via Bluetooth Clássico.
2.  **ESP32 Nó 1 (Transmissor)**: Recebe do Bluetooth e transmite via rádio LoRa (915MHz).
3.  **ESP32 Nó 2 (Base)**: Escuta o rádio e despeja o texto na porta Serial do computador via cabo USB, sempre com o prefixo `MSG_LORAMIND:`.

---

## 3. Códigos Atuais (C++ / Arduino IDE)

### ESP32 Nó 1 (Ponto de Entrada - Bluetooth -> LoRa)
```cpp
#include <SPI.h>
#include <LoRa.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include "BluetoothSerial.h"

#define OLED_RST 16 
#define SCK 5
#define MISO 19
#define MOSI 27
#define SS 18
#define RST 14
#define DIO0 26

Adafruit_SSD1306 display(128, 64, &Wire, OLED_RST);
BluetoothSerial SerialBT;

void setup() {
  Serial.begin(115200);
  SerialBT.begin("LoraMind_Node");
  
  SPI.begin(SCK, MISO, MOSI, SS);
  LoRa.setPins(SS, RST, DIO0);
  LoRa.begin(915E6);
}

void loop() {
  if (SerialBT.available()) {
    String msg = SerialBT.readStringUntil('\n');
    LoRa.beginPacket();
    LoRa.print(msg);
    LoRa.endPacket();
  }
}

### ESP32 Nó 2 (Ponto de Saída - LoRa -> Serial USB)
```cpp
#include <SPI.h>
#include <LoRa.h>

#define SCK 5
#define MISO 19
#define MOSI 27
#define SS 18
#define RST 14
#define DIO0 26

void setup() {
  Serial.begin(115200);
  SPI.begin(SCK, MISO, MOSI, SS);
  LoRa.setPins(SS, RST, DIO0);
  LoRa.begin(915E6);
}

void loop() {
  int packetSize = LoRa.parsePacket();
  if (packetSize) {
    String recebido = "";
    while (LoRa.available()) {
      recebido += (char)LoRa.read();
    }
    // Prefixo crucial para o futuro script Python filtrar os dados
    Serial.print("MSG_LORAMIND:");
    Serial.println(recebido);
  }
}
### ESP32 Nó 2 (Ponto de Saída - LoRa -> Serial USB)
```cpp
#include <SPI.h>
#include <LoRa.h>

#define SCK 5
#define MISO 19
#define MOSI 27
#define SS 18
#define RST 14
#define DIO0 26

void setup() {
  Serial.begin(115200);
  SPI.begin(SCK, MISO, MOSI, SS);
  LoRa.setPins(SS, RST, DIO0);
  LoRa.begin(915E6);
}

void loop() {
  int packetSize = LoRa.parsePacket();
  if (packetSize) {
    String recebido = "";
    while (LoRa.available()) {
      recebido += (char)LoRa.read();
    }
    // Prefixo crucial para o futuro script Python filtrar os dados
    Serial.print("MSG_LORAMIND:");
    Serial.println(recebido);
  }
}
### 4. Camada Android Atual (Kotlin)
'''

O aplicativo usa Jetpack Compose, Clean Architecture e Coroutines.

A comunicação é feita via Bluetooth Clássico usando BluetoothSocket (perfil SPP/RFCOMM).

O BluetoothRepositoryImpl gerencia a conexão e envia a String com uma quebra de linha (\n) no final para o ESP32 identificar o fim da mensagem.
'''

