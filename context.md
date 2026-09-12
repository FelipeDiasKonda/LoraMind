# LoraMind — Contexto Técnico do Projeto

## 1. Visão Geral

- **Projeto**: LoraMind
- **Objetivo**: Sistema de comunicação inteligente que permite enviar perguntas e receber respostas de uma IA **100% offline**, via rádio LoRa de longo alcance (até 15km), usando rede Mesh descentralizada.
- **Cenário**: Emergências em áreas sem internet/sinal — pessoa perdida, veículo quebrado, ferimento em área remota, desastres naturais.

## 2. Arquitetura

```
[App Android] ←(Bluetooth)→ [ESP1: Nó Mesh] ←(LoRa 915MHz)→ [ESP2: Base Station] ←(USB Serial)→ [PC + IA Ollama]
```

### 2.1 App Android (LoraMind)
- Interface do usuário final (Jetpack Compose, Clean Architecture, Coroutines)
- Conexão via Bluetooth Clássico (SPP/RFCOMM)
- Envia perguntas e recebe respostas da IA
- BluetoothRepositoryImpl envia String com `\n` no final
- Funciona 100% offline

### 2.2 ESP1 — Nó Mesh (Client Node)
- **Hardware**: ESP32 + módulo LoRa SX1276 (915 MHz) + Display OLED SSD1306 128x64
- **Biblioteca LoRa**: RadioLib (migrado de LoRa.h para interoperabilidade com SX1262)
- **Bluetooth**: Clássico SPP para comunicação com o App
- **Funções**: receber do app via BT, empacotar com header mesh, transmitir via LoRa, relay de pacotes de outros nós
- **Firmware**: `tools/esp1.ino`

### 2.3 ESP2 — Base Station (Gateway Mesh)
- **Hardware**: Heltec WiFi LoRa 32 **V3.2** (ESP32-S3 + SX1262)
- **Biblioteca LoRa**: RadioLib
- **Particularidades V3.2**: Vext GPIO 36 = LOW (obrigatório), TCXO 1.6V, DIO2 como RF Switch
- **Funções**: receber pacotes da mesh, enviar para PC via Serial USB, receber respostas da IA e transmitir de volta
- **Firmware**: `tools/esp2.ino`

### 2.4 PC + IA (Python Bridge + Ollama)
- **Script**: `tools/loramind_bridge.py`
- **Modelo de IA**: Gemma 2 9B (Google DeepMind) — 9.2 bilhões de parâmetros, ~5.4 GB
- **Framework**: Ollama (execução 100% local, sem internet, sem API key, sem limites)
- **Funcionalidades**: auto-detecção de porta COM, histórico por conversa (5 trocas), chunking de respostas, modo teste (`--test`)

## 3. Protocolo Mesh

### 3.1 Formato do Pacote LoRa
```
LM|TIPO|MSG_ID|TTL|payload
```

| Campo | Exemplo | Função |
|-------|---------|--------|
| `LM` | `LM` | Prefixo fixo — filtra ruído/pacotes estranhos |
| `TIPO` | `Q` ou `R` | Q = pergunta (relay até BS) / R = resposta (entrega ou relay) |
| `MSG_ID` | `sshm` | 4 chars alfanuméricos (~1.7M combinações) — identifica a mensagem para deduplicação e entrega |
| `TTL` | `3` | Hops restantes (decrementado a cada relay, descartado em 0) |
| `payload` | `a48d1e:texto` | conv_id:mensagem do usuário |

### 3.2 Roteamento (Stateless por MSG_ID)
- **Q (Pergunta)**: Sempre encaminhada (relay) até a BS. Só a BS processa.
- **R (Resposta)**: Se MSG_ID está na lista de pendentes do nó → entrega via BT. Senão → relay.

### 3.3 Deduplicação
- **Q packets**: Cache de MSG_IDs vistos (evita relay duplicado)
- **R packets**: Cache de MSG_ID + payload (permite múltiplos chunks, impede retransmissão duplicada do mesmo chunk)
- **BS eco**: Cache de respostas enviadas (`sentRCache`) para ignorar ecos R que voltam da mesh

### 3.4 Protocolo Serial (ESP2 ↔ Python Bridge)
```
ESP2 envia:    MSG_LORAMIND:MSG_ID|CONV_ID:mensagem
Python envia:  RESP_AI:MSG_ID|resposta
```

## 4. Configuração de Rádio LoRa

Parâmetros **idênticos** em ambos os módulos:

| Parâmetro | Valor | Observação |
|-----------|-------|------------|
| Frequência | 915.0 MHz | ISM Brasil/América |
| Bandwidth | **250 kHz** | Absorve desvio de cristal (~33 kHz) com folga |
| Spreading Factor | 7 | |
| Coding Rate | 4/5 | |
| Sync Word | 0x12 | Rede privada (RadioLib converte internamente para SX1262) |
| Potência TX | 10 dBm | |
| Preamble | 8 símbolos | |
| CRC | 16-bit (2 bytes) | Habilitado em ambos |
| LDRO | Desativado (forçado) | `radio.forceLDRO(false)` |
| IQ Inversion | Desativada | `radio.invertIQ(false)` |

### Configurações específicas Heltec V3.2 (ESP2):
- TCXO Voltage: 1.6V
- Regulador: DC-DC (não LDO)
- DIO2: RF Switch (obrigatório)
- Vext: GPIO 36 = LOW (habilita alimentação do rádio)

### Por que 250 kHz e não 125 kHz?
O cristal do ESP1 (SX1276) tem desvio natural de ~30-33 kHz vs o TCXO do Heltec. Com BW 125 kHz a tolerância é apenas 31 kHz (beira do limite). Com 250 kHz a tolerância sobe para 62.5 kHz.

## 5. Hardware — Pinagem

### ESP1 — Nó Mesh (ESP32 + SX1276)
```
SPI:   SCK=5, MISO=19, MOSI=27, SS=18
LoRa:  RST=14, DIO0=26
OLED:  SDA=4, SCL=15, RST=16
BT:    SPP nativo ESP32
```

### ESP2 — Base Station (Heltec V3.2 + SX1262)
```
SPI (FSPI):  SCK=9, MISO=11, MOSI=10, NSS=8
LoRa:        RST=12, BUSY=13, DIO1=14
Vext:        GPIO 36 (LOW = liga alimentação do rádio)
```

## 6. Modelo de IA

| Detalhe | Valor |
|---------|-------|
| Nome | Gemma 2 |
| Desenvolvedor | Google DeepMind |
| Parâmetros | 9.2 bilhões (9B) |
| Tamanho | ~5.4 GB (quantizado Q4) |
| Framework | Ollama |
| Licença | Gemma License (gratuita) |
| Idiomas | Multilíngue (PT, EN, ES, etc.) |
| Contexto | 8.192 tokens |
| GPU necessária | NVIDIA 8GB+ VRAM |
| Execução | 100% local, sem internet |

### Prompt de Sistema
O prompt separa dois modos:
- **Conhecimento geral**: Responde factualmente com conhecimento treinado
- **Emergência**: Foca em ações práticas que a pessoa pode fazer sozinha, sem sugerir ligar/chamar resgate

## 7. Arquivos do Projeto

```
tools/esp1.ino              — Firmware do Nó Mesh (ESP32 + SX1276, RadioLib)
tools/esp2.ino              — Firmware da Base Station (Heltec V3.2 + SX1262, RadioLib)
tools/mesh_protocol.h       — Header compartilhado (struct MeshPacket)
tools/loramind_bridge.py    — Bridge Serial ↔ Ollama (Python)
app/                        — Aplicativo Android (Kotlin, Jetpack Compose)
context.md                  — Este arquivo
```

## 8. Bibliotecas e Dependências

### Arduino/ESP32:
- **RadioLib** — Comunicação LoRa (suporta SX1276 E SX1262)
- Adafruit_GFX + Adafruit_SSD1306 — Display OLED
- BluetoothSerial — Bluetooth clássico SPP
- SPI, Wire — Barramentos

### Python:
- pyserial — Comunicação serial USB
- requests — API local do Ollama

### Android:
- Kotlin + Jetpack Compose + Clean Architecture + Coroutines
- Bluetooth SPP/RFCOMM

## 9. Fluxo Completo

### Ida (Pergunta):
```
App → BT → ESP1 → gera MSG_ID → empacota LM|Q|ID|3|conv:texto → LoRa TX
→ (relay por nós intermediários, TTL--) → ESP2 recebe → Serial USB
→ MSG_LORAMIND:ID|conv:texto → Python Bridge → Ollama (Gemma 2) → resposta
```

### Volta (Resposta):
```
Bridge → RESP_AI:ID|resposta → ESP2 → empacota LM|R|ID|3|resposta → LoRa TX
→ (relay) → ESP1 verifica isPending(ID) → SIM → entrega via BT → App exibe
```

Tempo total típico: **5 a 10 segundos**

## 10. Problemas Resolvidos

| Problema | Causa | Solução |
|----------|-------|---------|
| Dados corrompidos entre SX1276↔SX1262 | LoRa.h incompatível com SX1262 | Migrar ESP1 para RadioLib |
| Caracteres estranhos (saturação RF) | TX 17 dBm a poucos cm de distância | Reduzir para 10 dBm, separar módulos |
| Frequency Error ~33 kHz | Cristal do SX1276 vs TCXO do SX1262 | BW 125→250 kHz (tolerância 62.5 kHz) |
| App recebia só 1º chunk | isPending() apagava MSG_ID após 1º chunk | Manter MSG_ID para todos os chunks |
| Heltec V3.2 não inicializava | Vext (GPIO 36) precisa ser LOW | Ativar no setup() |
| IA sugeria "ligar para alguém" | qwen2.5:3b não seguia instruções | Migrar para gemma2:9b + prompt otimizado |
| Erro -24 após transmissão no ESP1 | TxDone disparava rxFlag falso | Limpar rxFlag = false após transmit() |
| Ecos R poluindo log da BS | Relay devolvia pacote R para BS | Cache sentRCache ignora ecos silenciosamente |

## 11. Como Executar

1. Arduino IDE: Upload `esp1.ino` no ESP32 (board: "ESP32 Dev Module")
2. Arduino IDE: Upload `esp2.ino` no Heltec V3.2 (board: "Heltec WiFi LoRa 32 (V3)")
3. Conectar ESP2 ao PC via USB
4. Esquentar modelo: `ollama run gemma2:9b "olá"`
5. Iniciar bridge: `python tools/loramind_bridge.py`
6. Abrir App LoraMind no celular, conectar via Bluetooth
7. Enviar mensagem e aguardar resposta da IA
