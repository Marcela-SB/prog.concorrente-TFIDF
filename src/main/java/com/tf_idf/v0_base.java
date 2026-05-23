package com.tf_idf;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class v0_base {

    public static void main(String[] args) {
        String caminhoArquivo = "corpus_grande.txt";
        
        Map<String, Integer> documentosComTermo = new HashMap<>();
        long totalDocumentos = 0;

        long inicioTotal = System.currentTimeMillis();

        // PASSO 1: CALCULAR IDF (Lendo o arquivo todo pela primeira vez)
        System.out.println("Iniciando Passo 1 (IDF)...");
        try (BufferedReader br = new BufferedReader(new FileReader(caminhoArquivo))) {
            String linha;
            while ((linha = br.readLine()) != null) {
                totalDocumentos++;
                String[] palavras = linha.toLowerCase().split("\\s+");
                
                Set<String> termosUnicosNoDoc = new HashSet<>(Arrays.asList(palavras));
                for (String termo : termosUnicosNoDoc) {
                    documentosComTermo.put(termo, documentosComTermo.getOrDefault(termo, 0) + 1);
                }

                if (totalDocumentos % 500000 == 0) {
                    System.out.println("Passo 1 -> Linhas lidas: " + totalDocumentos);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // PASSO 2: CALCULAR TF-IDF DE TUDO (Lendo o arquivo todo pela segunda vez)
        System.out.println("\nIniciando Passo 2 (TF-IDF Total)...");
        long contadorPalavrasProcessadas = 0;
        
        try (BufferedReader br = new BufferedReader(new FileReader(caminhoArquivo))) {
            String linha;
            long linhaAtual = 0;
            
            while ((linha = br.readLine()) != null) {
                linhaAtual++;
                String[] palavras = linha.toLowerCase().split("\\s+");
                Map<String, Integer> frequenciaTermo = new HashMap<>();
                
                for (String p : palavras) {
                    frequenciaTermo.put(p, frequenciaTermo.getOrDefault(p, 0) + 1);
                }

                for (String termo : frequenciaTermo.keySet()) {
                    double tf = (double) frequenciaTermo.get(termo) / palavras.length;
                    double idf = Math.log((double) totalDocumentos / (documentosComTermo.get(termo)));
                    double tfidf = tf * idf;
                    
                    // Aqui o cálculo está sendo feito para TODAS as palavras.
                    // Não imprimimos para manter a performance.
                    contadorPalavrasProcessadas++;
                }

                if (linhaAtual % 500000 == 0) {
                    System.out.println("Passo 2 -> Linhas processadas: " + linhaAtual);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        long fimTotal = System.currentTimeMillis();
        
        System.out.println("\n=== RELATÓRIO FINAL ===");
        System.out.println("Total de Documentos (Linhas): " + totalDocumentos);
        System.out.println("Total de cálculos TF-IDF realizados: " + contadorPalavrasProcessadas);
        System.out.println("Tempo Total de Execução: " + (fimTotal - inicioTotal) + " ms");
    }
}