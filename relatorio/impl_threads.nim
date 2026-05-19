# 1. Importação obrigatória para habilitar o motor de threads
import std/threads 

# 2. DEFINIÇÃO DA FUNÇÃO (O que a thread vai fazer)
# A função só pode receber um parâmetro
proc nomeTarefaDaThread(dadosEntrada: TipoDoDado) {.thread.} =
  # Todo o código que rodará em paralelo fica aninhado aqui dentro
  discard

# --- FLUXO PRINCIPAL DO PROGRAMA ---
proc main() =
  # 3. DECLARAÇÃO DA VARIÁVEL DA THREAD
  # O tipo dentro de [ ] DEVE ser o mesmo tipo do parâmetro da proc lá de cima.
  var nomeThread: Thread[TipoDoDado]

  # 4. DISPARO / INICIALIZAÇÃO DA THREAD
  # O segundo parâmetro agora bate exatamente com o nome da proc do passo 2
  createThread(nomeThread, nomeTarefaDaThread, dadosEntrada)

  # 5. SINCRONIZAÇÃO / ESPERA
  # Obriga o programa principal a pausar e esperar essa thread terminar antes de fechar o app.
  joinThread(nomeThread)

main()


