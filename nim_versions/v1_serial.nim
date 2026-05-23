import std/[strutils, tables, math, strformat, os]

proc main() =
  let filePath = "corpus_grande.txt"
  let resultDir = "resultados"
  let resultFile = resultDir / "nim_v1_resultado_tfidf.csv"

  # Cria o diretório de resultados se não existir
  if not dirExists(resultDir):
    createDir(resultDir)

  var documentsWithTerm = initCountTable[string]()
  var totalDocuments: int64 = 0

  # Passo 1: Calculando frequência documental (IDF)
  # echo "Iniciando Passo 1: Lendo documentos para o IDF..."
  if not fileExists(filePath):
    echo "Erro: Arquivo " & filePath & " não encontrado."
    quit(1)

  for row in lines(filePath):
    totalDocuments += 1
    let words = row.toLowerAscii().splitWhitespace()
    
    # Criamos um conjunto (HashSet equivalente) implícito para termos únicos na linha
    # Usando uma tabela temporária para rastrear o que já vimos nesta linha
    var seenInDoc = initTable[string, bool]()
    for term in words:
      if term.len > 0 and not seenInDoc.hasKey(term):
        seenInDoc[term] = true
        documentsWithTerm.inc(term)

  # Passo 2: Calculando TF-IDF por linha e gravando no arquivo de saída
  # echo "Iniciando Passo 2: Calculando TF-IDF e salvando..."
  let outFile = open(resultFile, fmWrite)
  defer: outFile.close()

  outFile.writeLine("Numero_Linha;Palavra;TF-IDF")

  var currentLine: int64 = 0

  for row in lines(filePath):
    currentLine += 1
    let words = row.toLowerAscii().splitWhitespace()
    if words.len == 0: continue

    # Conta o TF local da linha atual
    var frequencyTerm = initCountTable[string]()
    for p in words:
      if p.len > 0:
        frequencyTerm.inc(p)

    # Calcula o TF-IDF de cada palavra e escreve no CSV
    let totalWordsInDoc = words.len.float
    for term, count in frequencyTerm.pairs:
      let tf = count.float / totalWordsInDoc
      let idf = ln(totalDocuments.float / documentsWithTerm[term].float)
      let tfidf = tf * idf

      # Formata o float com 10 casas decimais similar ao %.10f do Java
      outFile.writeLine(&"{currentLine};{term};{tfidf:.10f}")

  # echo "Processamento concluído com sucesso!"

main()