package com.tf_idf.AUXILIARES;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

public class GeradorRealistaTFIDF {

    public static void main(String[] args) {
        //OFICIAL
        // String caminhoArquivo = "corpus_grande.txt";
        // long tamanhoAlvoBytes = 1024L * 1024L * 1024L; // 1 GB

        //TESTE
        String caminhoArquivo = "corpus_test.txt";
        long tamanhoAlvoBytes = 200L * 1024L * 1024L; // 200 MB
        
        // Dicionário expandido para simular diversidade temática
        String[] sementes = {
            "tecnologia", "sustentabilidade", "algoritmo", "distribuido", "concorrencia",
            "interface", "usuario", "experiencia", "desempenho", "otimizacao",
            "memoria", "processamento", "analise", "estatistica", "frequencia",
            "documento", "colecao", "relevancia", "pesquisa", "inteligencia",
            "artificial", "aprendizado", "maquina", "nuvem", "seguranca"
        };

        Random random = new Random();

        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(caminhoArquivo))) {
            long bytesEscritos = 0;
            byte[] espaco = " ".getBytes();
            byte[] quebraLinha = "\n".getBytes();

            System.out.println("Iniciando geração de 1GB de texto realista...");

            while (bytesEscritos < tamanhoAlvoBytes) {
                // 1. Escolhe uma palavra do dicionário
                String palavra = sementes[random.nextInt(sementes.length)];
                
                // 2. Ocasionalmente gera uma "non-word" aleatória para simular ruído/IDs
                if (random.nextInt(10) > 8) {
                    palavra = gerarPalavraAleatoria(random, 5, 12);
                }

                byte[] wordBytes = palavra.getBytes();
                bos.write(wordBytes);
                bytesEscritos += wordBytes.length;

                // 3. Adiciona espaço ou quebra de linha
                if (random.nextInt(15) == 0) {
                    bos.write(quebraLinha);
                    bytesEscritos += quebraLinha.length;
                } else {
                    bos.write(espaco);
                    bytesEscritos += espaco.length;
                }

                // Feedback a cada 100MB
                if (bytesEscritos % (100 * 1024 * 1024) < 50) {
                    System.out.printf("Progresso: %d%% concluído\n", (bytesEscritos * 100 / tamanhoAlvoBytes));
                }
            }
            
            bos.flush();
            System.out.println("\nArquivo concluído: " + caminhoArquivo);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Gera strings aleatórias para aumentar o vocabulário (aumenta o denominador do IDF)
    private static String gerarPalavraAleatoria(Random r, int min, int max) {
        int tam = r.nextInt(max - min + 1) + min;
        StringBuilder sb = new StringBuilder(tam);
        for (int i = 0; i < tam; i++) {
            sb.append((char) ('a' + r.nextInt(26)));
        }
        return sb.toString();
    }
}