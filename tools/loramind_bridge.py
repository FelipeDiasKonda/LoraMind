#!/usr/bin/env python3
"""
LoraMind Bridge — Ponte Serial ↔ Ollama

Fluxo:
  ESP2 (Serial USB) --[MSG_LORAMIND:texto]--> Python --[prompt]--> Ollama (phi3)
  Ollama --[resposta]--> Python --[RESP_AI:resposta]--> ESP2 (Serial USB)

Uso:
  python loramind_bridge.py              # Modo normal (auto-detecta porta COM)
  python loramind_bridge.py --port COM7  # Porta específica
  python loramind_bridge.py --test       # Testa Ollama sem hardware
"""

import argparse
import json
import sys
import time
from datetime import datetime

# Força UTF-8 no stdout para Windows (evita erro com caracteres Unicode)
if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

import requests
import serial
import serial.tools.list_ports

# ============================================================================
# CONFIGURAÇÕES
# ============================================================================

BAUD_RATE = 115200
OLLAMA_URL = "http://localhost:11434/api/generate"
OLLAMA_MODEL = "phi3"
MSG_PREFIX = "MSG_LORAMIND:"
RESP_PREFIX = "RESP_AI:"

# Prompt de sistema para o modelo - instrui a IA a ser concisa (importante
# porque LoRa tem largura de banda limitada)
SYSTEM_PROMPT = (
    "Você é o LoraMind, um assistente de IA offline que se comunica via rádio LoRa. "
    "Responda de forma clara, útil e objetiva em português brasileiro. "
    "Evite respostas excessivamente longas. "
    "NÃO use markdown, headers, listas numeradas ou formatação. Apenas texto puro."
)

# Tamanho máximo de cada chunk LoRa (SX1276 suporta até 255 bytes por pacote,
# mas caracteres UTF-8 em português podem usar 2 bytes cada)
LORA_CHUNK_SIZE = 230

# Delay entre chunks em segundos (tempo para o LoRa transmitir cada pacote)
CHUNK_DELAY = 2.0

# ============================================================================
# CORES PARA O TERMINAL
# ============================================================================

class Colors:
    """Códigos ANSI para cores no terminal."""
    RESET   = "\033[0m"
    RED     = "\033[91m"
    GREEN   = "\033[92m"
    YELLOW  = "\033[93m"
    BLUE    = "\033[94m"
    MAGENTA = "\033[95m"
    CYAN    = "\033[96m"
    BOLD    = "\033[1m"
    DIM     = "\033[2m"

# ============================================================================
# FUNÇÕES DE LOG
# ============================================================================

def _timestamp():
    """Retorna timestamp formatado."""
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]

def log_info(msg):
    """Log informativo (azul)."""
    print(f"{Colors.DIM}{_timestamp()}{Colors.RESET} {Colors.BLUE}[INFO]{Colors.RESET}    {msg}")

def log_success(msg):
    """Log de sucesso (verde)."""
    print(f"{Colors.DIM}{_timestamp()}{Colors.RESET} {Colors.GREEN}[OK]{Colors.RESET}      {msg}")

def log_warning(msg):
    """Log de aviso (amarelo)."""
    print(f"{Colors.DIM}{_timestamp()}{Colors.RESET} {Colors.YELLOW}[WARN]{Colors.RESET}    {msg}")

def log_error(msg):
    """Log de erro (vermelho)."""
    print(f"{Colors.DIM}{_timestamp()}{Colors.RESET} {Colors.RED}[ERROR]{Colors.RESET}   {msg}")

def log_lora_in(msg):
    """Log de mensagem recebida via LoRa (magenta)."""
    print(f"{Colors.DIM}{_timestamp()}{Colors.RESET} {Colors.MAGENTA}[LORA ⬇]{Colors.RESET}  {Colors.BOLD}{msg}{Colors.RESET}")

def log_ai_out(msg):
    """Log de resposta da IA (ciano)."""
    print(f"{Colors.DIM}{_timestamp()}{Colors.RESET} {Colors.CYAN}[AI ⬆]{Colors.RESET}    {Colors.BOLD}{msg}{Colors.RESET}")

# ============================================================================
# AUTO-DETECÇÃO DE PORTA SERIAL
# ============================================================================

def find_serial_port():
    """
    Auto-detecta a porta COM do ESP32.
    Prioriza portas com 'CP210x' ou 'CH340' no nome (chips USB-Serial comuns
    em placas ESP32). Se não encontrar, tenta COM4 e COM7 como fallback.
    """
    log_info("Buscando portas seriais disponíveis...")

    ports = serial.tools.list_ports.comports()

    if not ports:
        log_error("Nenhuma porta serial encontrada!")
        return None

    # Lista todas as portas encontradas
    for p in ports:
        log_info(f"  Porta: {p.device} | Desc: {p.description} | HWID: {p.hwid}")

    # Tenta encontrar ESP32 pelo chip USB-Serial
    esp_keywords = ["CP210", "CH340", "CH910", "FTDI", "USB", "Silicon Labs"]
    for p in ports:
        desc_upper = (p.description or "").upper()
        for keyword in esp_keywords:
            if keyword.upper() in desc_upper:
                log_success(f"ESP32 detectado em {p.device} ({p.description})")
                return p.device

    # Fallback: tenta COM4 e COM7 (portas típicas do usuário)
    fallback_ports = ["COM4", "COM7"]
    available = [p.device for p in ports]
    for fb in fallback_ports:
        if fb in available:
            log_warning(f"Usando porta fallback: {fb}")
            return fb

    # Último recurso: usa a primeira porta disponível
    first = ports[0].device
    log_warning(f"Nenhum ESP detectado. Usando primeira porta: {first}")
    return first

# ============================================================================
# COMUNICAÇÃO COM O OLLAMA
# ============================================================================

def check_ollama():
    """Verifica se o Ollama está rodando e o modelo está disponível."""
    log_info(f"Verificando Ollama em {OLLAMA_URL}...")

    try:
        # Testa se o servidor está ativo
        resp = requests.get("http://localhost:11434/api/tags", timeout=5)
        if resp.status_code != 200:
            log_error(f"Ollama retornou status {resp.status_code}")
            return False

        # Verifica se o modelo está disponível
        models = resp.json().get("models", [])
        model_names = [m.get("name", "") for m in models]
        log_info(f"Modelos disponíveis: {model_names}")

        # Verifica se o modelo desejado existe (com ou sem tag)
        found = any(OLLAMA_MODEL in name for name in model_names)
        if found:
            log_success(f"Modelo '{OLLAMA_MODEL}' encontrado!")
        else:
            log_warning(
                f"Modelo '{OLLAMA_MODEL}' não encontrado. "
                f"Execute: ollama pull {OLLAMA_MODEL}"
            )
            return False

        return True

    except requests.ConnectionError:
        log_error("Ollama não está rodando! Execute: ollama serve")
        return False
    except Exception as e:
        log_error(f"Erro ao verificar Ollama: {e}")
        return False


def _clean_response(text):
    """
    Limpa a resposta do modelo, removendo lixo que o phi3 às vezes gera
    (headers markdown, blocos de instrução, etc).
    """
    import re

    text = text.strip()

    # Remove tudo a partir de "###" (blocos de instrução)
    if "###" in text:
        text = text[:text.index("###")].strip()

    # Remove tudo a partir de "**" (blocos de formatação)
    if "**" in text:
        text = text[:text.index("**")].strip()

    # Remove linhas que parecem instruções
    lines = text.split("\n")
    clean_lines = []
    for line in lines:
        line_stripped = line.strip()
        # Ignora linhas que parecem headers/instruções
        if line_stripped.startswith("#") or line_stripped.startswith("Instruction"):
            continue
        if line_stripped:
            clean_lines.append(line_stripped)

    text = " ".join(clean_lines)

    # Remove espaços duplos
    text = re.sub(r"\s+", " ", text).strip()

    return text


def _split_into_chunks(text, max_chars):
    """
    Divide um texto longo em chunks de no máximo max_chars caracteres.
    Tenta quebrar em espaços para não cortar palavras no meio.
    """
    if len(text) <= max_chars:
        return [text]

    chunks = []
    remaining = text

    while remaining:
        if len(remaining) <= max_chars:
            chunks.append(remaining)
            break

        # Procura o último espaço dentro do limite
        split_pos = remaining.rfind(" ", 0, max_chars)

        if split_pos == -1:
            # Sem espaço encontrado — corta no limite
            split_pos = max_chars

        chunk = remaining[:split_pos].strip()
        remaining = remaining[split_pos:].strip()

        if chunk:
            chunks.append(chunk)

    return chunks


def query_ollama(prompt):
    """
    Envia um prompt para o Ollama e retorna a resposta completa.
    Usa streaming para acompanhar a geração em tempo real.
    """
    log_info(f"Enviando para Ollama ({OLLAMA_MODEL}): \"{prompt}\"")

    payload = {
        "model": OLLAMA_MODEL,
        "prompt": prompt,
        "system": SYSTEM_PROMPT,
        "stream": True,
        "options": {
            "temperature": 0.7,
            "num_predict": 150,  # Permite respostas de qualidade
            "stop": ["###", "\n\n\n", "Instruction", "**"],  # Para de gerar ao encontrar lixo
        }
    }

    try:
        resp = requests.post(OLLAMA_URL, json=payload, stream=True, timeout=120)
        resp.raise_for_status()

        full_response = ""
        sys.stdout.write(f"{Colors.DIM}{_timestamp()}{Colors.RESET} {Colors.CYAN}[AI ...]{Colors.RESET}   ")

        for line in resp.iter_lines():
            if line:
                chunk = json.loads(line)
                token = chunk.get("response", "")
                full_response += token
                sys.stdout.write(token)
                sys.stdout.flush()

                if chunk.get("done", False):
                    break

        print()  # Nova linha após streaming
        full_response = _clean_response(full_response)
        log_success(f"Resposta completa ({len(full_response)} chars)")

        return full_response

    except requests.ConnectionError:
        log_error("Conexão com Ollama perdida!")
        return "Erro: Ollama não respondeu."
    except requests.Timeout:
        log_error("Timeout na resposta do Ollama!")
        return "Erro: Timeout na IA."
    except Exception as e:
        log_error(f"Erro no Ollama: {e}")
        return f"Erro: {e}"

# ============================================================================
# LOOP PRINCIPAL
# ============================================================================

def run_bridge(port_name):
    """
    Loop principal: lê Serial, filtra MSG_LORAMIND:, consulta Ollama,
    devolve RESP_AI: pela Serial.
    """
    log_info(f"Abrindo porta {port_name} a {BAUD_RATE} baud...")

    try:
        ser = serial.Serial(
            port=port_name,
            baudrate=BAUD_RATE,
            timeout=1,
            write_timeout=5
        )
        log_success(f"Porta {port_name} aberta com sucesso!")
    except serial.SerialException as e:
        log_error(f"Não foi possível abrir {port_name}: {e}")
        sys.exit(1)

    # Aguarda ESP2 inicializar
    log_info("Aguardando 2s para o ESP2 inicializar...")
    time.sleep(2)

    # Limpa buffer
    ser.reset_input_buffer()
    log_info("Buffer serial limpo. Escutando mensagens...")

    print()
    print(f"{Colors.BOLD}{'='*60}")
    print(f"  LoraMind Bridge ATIVO — Esperando mensagens LoRa...")
    print(f"  Porta: {port_name} | Modelo: {OLLAMA_MODEL}")
    print(f"  Pressione Ctrl+C para sair")
    print(f"{'='*60}{Colors.RESET}")
    print()

    try:
        while True:
            # Lê uma linha da Serial
            raw_line = ser.readline()

            if not raw_line:
                continue

            # Decodifica e limpa
            try:
                line = raw_line.decode("utf-8", errors="replace").strip()
            except Exception:
                continue

            if not line:
                continue

            # Log de tudo que chega na serial (para debug)
            log_info(f"Serial raw: \"{line}\"")

            # Verifica se é uma mensagem LoRa
            if not line.startswith(MSG_PREFIX):
                log_info("(ignorado - sem prefixo MSG_LORAMIND:)")
                continue

            # Extrai a mensagem limpa
            user_message = line[len(MSG_PREFIX):].strip()

            if not user_message:
                log_warning("Mensagem MSG_LORAMIND: vazia, ignorando.")
                continue

            log_lora_in(f"Mensagem recebida: \"{user_message}\"")

            # Consulta o Ollama
            ai_response = query_ollama(user_message)

            if not ai_response:
                log_warning("Resposta vazia do Ollama, ignorando.")
                continue

            # Divide em chunks se a resposta for maior que o limite do LoRa
            chunks = _split_into_chunks(ai_response, LORA_CHUNK_SIZE)
            total_chunks = len(chunks)

            if total_chunks > 1:
                log_info(f"Resposta dividida em {total_chunks} pacotes LoRa")

            for i, chunk in enumerate(chunks):
                part_label = f" [{i+1}/{total_chunks}]" if total_chunks > 1 else ""

                # Envia de volta pela Serial com prefixo RESP_AI:
                response_line = f"{RESP_PREFIX}{chunk}\n"
                ser.write(response_line.encode("utf-8"))
                ser.flush()

                log_ai_out(f"Enviado para ESP2{part_label}: \"{chunk}\"")

                # Aguarda entre chunks para o LoRa ter tempo de transmitir
                if i < total_chunks - 1:
                    log_info(f"Aguardando {CHUNK_DELAY}s antes do proximo pacote...")
                    time.sleep(CHUNK_DELAY)

            print()  # Separador visual

    except KeyboardInterrupt:
        print()
        log_info("Encerrando LoraMind Bridge...")
    finally:
        ser.close()
        log_success("Porta serial fechada. Até mais! 👋")

# ============================================================================
# MODO TESTE (sem hardware)
# ============================================================================

def run_test():
    """Testa a conexão com o Ollama sem precisar de hardware."""
    print()
    print(f"{Colors.BOLD}{'='*60}")
    print(f"  LoraMind Bridge — MODO TESTE (sem hardware)")
    print(f"{'='*60}{Colors.RESET}")
    print()

    if not check_ollama():
        log_error("Ollama não está disponível. Abortando teste.")
        sys.exit(1)

    # Simula uma mensagem vinda do LoRa
    test_message = "Qual é a capital do Brasil?"
    log_lora_in(f"[SIMULADO] Mensagem: \"{test_message}\"")

    ai_response = query_ollama(test_message)

    if ai_response:
        log_ai_out(f"Resposta: \"{ai_response}\"")
        print()
        log_success("✅ Teste concluído! O Ollama está funcionando corretamente.")
        print()
        print(f"  Resposta que seria enviada via Serial:")
        print(f"  {Colors.CYAN}{RESP_PREFIX}{ai_response}{Colors.RESET}")
    else:
        log_error("❌ Teste falhou — Ollama não retornou resposta.")

# ============================================================================
# ENTRY POINT
# ============================================================================

def _apply_config(model, baud):
    """Aplica configurações de CLI nas variáveis globais do módulo."""
    global OLLAMA_MODEL, BAUD_RATE
    OLLAMA_MODEL = model
    BAUD_RATE = baud


def main():
    parser = argparse.ArgumentParser(
        description="LoraMind Bridge — Ponte Serial ↔ Ollama",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Exemplos:
  python loramind_bridge.py              # Auto-detecta porta COM
  python loramind_bridge.py --port COM7  # Usa porta específica
  python loramind_bridge.py --test       # Testa Ollama sem hardware
  python loramind_bridge.py --model llama3  # Usa modelo diferente
        """
    )
    parser.add_argument(
        "--port", "-p",
        help="Porta serial (ex: COM4, COM7, /dev/ttyUSB0). Auto-detecta se omitido."
    )
    parser.add_argument(
        "--model", "-m",
        default=OLLAMA_MODEL,
        help=f"Modelo do Ollama (default: {OLLAMA_MODEL})"
    )
    parser.add_argument(
        "--test", "-t",
        action="store_true",
        help="Modo teste: testa Ollama sem precisar de hardware"
    )
    parser.add_argument(
        "--baud", "-b",
        type=int,
        default=BAUD_RATE,
        help=f"Baud rate da serial (default: {BAUD_RATE})"
    )

    args = parser.parse_args()

    # Aplica configurações dos argumentos como variáveis do módulo
    _apply_config(args.model, args.baud)

    # Banner
    print()
    print(f"{Colors.BOLD}{Colors.CYAN}")
    print(r"  _                    __  __ _           _ ")
    print(r" | |    ___  _ __ __ _|  \/  (_)_ __   __| |")
    print(r" | |   / _ \| '__/ _` | |\/| | | '_ \ / _` |")
    print(r" | |__| (_) | | | (_| | |  | | | | | | (_| |")
    print(r" |_____\___/|_|  \__,_|_|  |_|_|_| |_|\__,_|")
    print(f"{Colors.RESET}")
    print(f"  {Colors.DIM}Bridge v1.0 — Serial ↔ Ollama ({OLLAMA_MODEL}){Colors.RESET}")
    print()

    # Modo teste
    if args.test:
        run_test()
        return

    # Verifica Ollama primeiro
    if not check_ollama():
        log_error("Ollama não está disponível. Certifique-se que está rodando.")
        log_info("Dica: Execute 'ollama serve' em outro terminal")
        log_info(f"Dica: Execute 'ollama pull {OLLAMA_MODEL}' para baixar o modelo")
        sys.exit(1)

    # Detecta porta serial
    port = args.port or find_serial_port()

    if not port:
        log_error("Nenhuma porta serial encontrada!")
        log_info("Dica: Conecte o ESP2 via USB e tente novamente")
        log_info("Dica: Use --port COM7 para especificar manualmente")
        sys.exit(1)

    # Inicia o bridge
    run_bridge(port)


if __name__ == "__main__":
    main()
