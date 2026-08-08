#ifndef MESH_PROTOCOL_H
#define MESH_PROTOCOL_H

#include <Arduino.h>

// ============================================================================
// ESTRUTURA DO PACOTE PARSED
// Usada por esp1.ino e esp2.ino — definida em header para evitar
// conflito com a geração automática de protótipos do Arduino IDE.
// ============================================================================
struct MeshPacket {
  bool valid;
  char type;        // 'Q' ou 'R'
  String msgId;     // ID da mensagem (ex: "a7f3")
  int ttl;          // Hops restantes
  String payload;   // Conteúdo da mensagem
};

#endif // MESH_PROTOCOL_H
