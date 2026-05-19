package java_versions;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class v1_serial {

    public static void main(String[] args) {
        String caminhoArquivo = "corpus_grande.txt";
        String arquivoSaida = "resultados/v1_resultado_tfidf_geral.csv";
        
        Map<String, Integer> documentosComTermo = new HashMap<>();
        Map<String, Double> tfIdfAcumuladoGlobal = new HashMap<>();
        long totalDocumentos = 0;

        // Passo 1: Calculando frequencia documental (IDF)...
        try (BufferedReader br = new BufferedReader(new FileReader(caminhoArquivo))) {
            String linha;
            while ((linha = br.readLine()) != null) {
                totalDocumentos++;
                String[] palavras = linha.toLowerCase().split("\\s+");
                
                Set<String> termosUnicosNoDoc = new HashSet<>(Arrays.asList(palavras));
                for (String termo : termosUnicosNoDoc) {
                    if (!termo.isEmpty()) {
                        documentosComTermo.put(termo, documentosComTermo.getOrDefault(termo, 0) + 1);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Passo 2: Calculando TF-IDF por linha e acumulando...
        try (BufferedReader br = new BufferedReader(new FileReader(caminhoArquivo))) {
            String linha;
            while ((linha = br.readLine()) != null) {
                String[] palavras = linha.toLowerCase().split("\\s+");
                if (palavras.length == 0) continue;

                Map<String, Integer> frequenciaTermo = new HashMap<>();
                for (String p : palavras) {
                    if (!p.isEmpty()) {
                        frequenciaTermo.put(p, frequenciaTermo.getOrDefault(p, 0) + 1);
                    }
                }

                for (String termo : frequenciaTermo.keySet()) {
                    double tf = (double) frequenciaTermo.get(termo) / palavras.length;
                    double idf = Math.log((double) totalDocumentos / documentosComTermo.get(termo));
                    double tfidf = tf * idf;

                    // Acumula o valor para posterior média
                    tfIdfAcumuladoGlobal.put(termo, tfIdfAcumuladoGlobal.getOrDefault(termo, 0.0) + tfidf);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Passo 3: Calculando médias e salvando resultados no arquivo de saida...
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(arquivoSaida))) {
            bw.write("Palavra;TF-IDF_Medio\n");

            // Ordenando por valor (opcional, mas ajuda a ver as mais importantes no topo do arquivo)
            List<Map.Entry<String, Double>> listaOrdenada = new ArrayList<>(tfIdfAcumuladoGlobal.entrySet());
            listaOrdenada.sort((a, b) -> b.getValue().compareTo(a.getValue()));

            for (Map.Entry<String, Double> entrada : listaOrdenada) {
                // MÉDIA: Divide o acumulado pelo total de documentos do corpus
                double mediatfidf = entrada.getValue() / totalDocumentos;
                
                bw.write(String.format("%s;%.10f\n", entrada.getKey(), mediatfidf));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}