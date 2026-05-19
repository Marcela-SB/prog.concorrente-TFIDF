import std/[strutils, tables, math, algorithm, sets, times, os, strformat]

proc main() =
  let caminhoArquivo = "corpus_grande.txt"
  let arquivoSaida = "resultado_tfidf_geral.csv"

  var documentosComTermo = initCountTable[string]()
  var tfIdfAcumuladoGlobal = initTable[string, float64]()
  var totalDocumentos: int64 = 0

  # --- Passo 1: Frequência Documental (IDF) ---
  echo "Passo 1: Calculando frequencia documental (IDF)..."
  if not fileExists(caminhoArquivo):
    echo "Erro: Arquivo não encontrado."
    return

  for linha in lines(caminhoArquivo):
    totalDocumentos += 1
    let palavras = linha.toLowerAscii().splitWhitespace()
    
    # Set para termos únicos na linha (documento)
    var termosUnicosNoDoc = initHashSet[string]()
    for p in palavras:
      if p.len > 0:
        termosUnicosNoDoc.incl(p)
    
    for termo in termosUnicosNoDoc:
      documentosComTermo.inc(termo)

  # --- Passo 2: Calculando TF-IDF e acumulando ---
  echo "Passo 2: Calculando TF-IDF por linha e acumulando..."
  for linha in lines(caminhoArquivo):
    let palavras = linha.toLowerAscii().splitWhitespace()
    if palavras.len == 0: continue

    var frequenciaTermo = initCountTable[string]()
    for p in palavras:
      if p.len > 0:
        frequenciaTermo.inc(p)

    for termo, count in frequenciaTermo.pairs:
      let tf = count.float64 / palavras.len.float64
      let idf = ln(totalDocumentos.float64 / documentosComTermo[termo].float64)
      let tfidf = tf * idf
      
      tfIdfAcumuladoGlobal[termo] = tfIdfAcumuladoGlobal.getOrDefault(termo, 0.0) + tfidf

  # --- Passo 3: Médias e Exportação ---
  echo "Passo 3: Calculando médias e salvando resultados em ", arquivoSaida, "..."
  
  var f: File
  if open(f, arquivoSaida, fmWrite):
    f.writeLine("Palavra;TF-IDF_Medio")

    # Criar lista para ordenação (decrescente por valor)
    var listaOrdenada: seq[(string, float64)] = @[]
    for k, v in tfIdfAcumuladoGlobal.pairs:
      listaOrdenada.add((k, v))
    
    listaOrdenada.sort(proc (x, y: (string, float64)): int =
      cmp(y[1], x[1])
    )

    for item in listaOrdenada:
      let mediaTfIdf = item[1] / totalDocumentos.float64
      # Formatando com 10 casas decimais como no Java
      f.writeLine(&"{item[0]};{mediaTfIdf:.10f}")
    
    f.close()

  echo "=== RELATÓRIO FINAL ==="
  echo "Total de Linhas: ", totalDocumentos
  echo "Total de Palavras Únicas: ", tfIdfAcumuladoGlobal.len
  echo "O arquivo CSV foi gerado com sucesso."

main()