package com.tf_idf;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class v1_serial {
    public static void main(String[] args) {
        String filePath = "corpus_grande.txt";
        String resultFile = "resultados/java_v1_resultado_tfidf.csv";
        
        Map<String, Integer> documentsWithTerm = new HashMap<>();
        long totalDocuments = 0;

        // Passo 1: Calculando frequencia documental (IDF)...
        //System.out.println("Iniciando Passo 1: Lendo documentos para o IDF...");
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String row;
            while ((row = br.readLine()) != null) {
                totalDocuments++;
                String[] words = row.toLowerCase().split("\\s+");
                
                Set<String> unicTermsInDoc = new HashSet<>(Arrays.asList(words));
                for (String term : unicTermsInDoc) {
                    if (!term.isEmpty()) {
                        documentsWithTerm.put(term, documentsWithTerm.getOrDefault(term, 0) + 1);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Passo 2: Calculando TF-IDF por linha e gravando direto no arquivo de saída
        //System.out.println("Iniciando Passo 2: Calculando TF-IDF e salvando...");
        try (BufferedReader br = new BufferedReader(new FileReader(filePath));
             BufferedWriter bw = new BufferedWriter(new FileWriter(resultFile))) {
            
            // Escreve o cabeçalho do CSV indicando a linha, a palavra e o score dela naquela linha
            bw.write("Numero_Linha;Palavra;TF-IDF\n");
            
            String row;
            long currentLine = 0;

            while ((row = br.readLine()) != null) {
                currentLine++;
                String[] words = row.toLowerCase().split("\\s+");
                if (words.length == 0) continue;

                // Conta o TF local da linha atual
                Map<String, Integer> frequencyTerm = new HashMap<>();
                for (String p : words) {
                    if (!p.isEmpty()) {
                        frequencyTerm.put(p, frequencyTerm.getOrDefault(p, 0) + 1);
                    }
                }

                // Calcula o TF-IDF de cada palavra da linha e escreve no CSV imediatamente
                for (String term : frequencyTerm.keySet()) {
                    double tf = (double) frequencyTerm.get(term) / words.length;
                    double idf = Math.log((double) totalDocuments / documentsWithTerm.get(term));
                    double tfidf = tf * idf;

                    // Escreve o resultado no formato: ID_DA_LINHA;PALAVRA;SCORE
                    bw.write(String.format("%d;%s;%.10f\n", currentLine, term, tfidf));
                }
            }
            //System.out.println("Processamento concluído com sucesso!");
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    // Para JMH
    public double executarBenchmark(List<String> linhas) {
        Map<String, Integer> documentsWithTerm = new HashMap<>();
        int totalDocuments = linhas.size();
        double checksum = 0.0;

        // Passo 1: IDF
        for (String row : linhas) {
            String[] words = row.toLowerCase().split("\\s+");
            Set<String> unicTermsInDoc = new HashSet<>(Arrays.asList(words));
            for (String term : unicTermsInDoc) {
                if (!term.isEmpty()) {
                    documentsWithTerm.put(term, documentsWithTerm.getOrDefault(term, 0) + 1);
                }
            }
        }

        // Passo 2: TF-IDF (sem escrita em arquivo para o benchmark ser rápido)
        for (String row : linhas) {
            String[] words = row.toLowerCase().split("\\s+");
            if (words.length == 0) continue;

            Map<String, Integer> frequencyTerm = new HashMap<>();
            for (String p : words) {
                if (!p.isEmpty()) {
                    frequencyTerm.put(p, frequencyTerm.getOrDefault(p, 0) + 1);
                }
            }

            for (String term : frequencyTerm.keySet()) {
                double tf = (double) frequencyTerm.get(term) / words.length;
                double idf = Math.log((double) totalDocuments / documentsWithTerm.get(term));
                double tfidf = tf * idf;
                checksum += tfidf;
            }
        }
        return checksum;
    }
}