package com.tf_idf;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.util.*;

public class v2_plataform_threads {

    private static final String FILE_PATH = "corpus_grande.txt";
    private static final String RESULT_FILE = "resultados/java_v2_resultado_tfidf_geral.csv";

    private record Segment(int id, long start, int size) {}

    private static class ThreadResult {
        long docCount = 0;
        Map<String, Integer> localDocumentsWithTerm = new HashMap<>();
    }

    public static void main(String[] args) {
        long globalStart = System.currentTimeMillis();

        List<Segment> segments = getSegments();
        int numThreads = segments.size();

        ThreadResult[] threadResults = new ThreadResult[numThreads];
        Thread[] threads = new Thread[numThreads];

        System.out.println("Iniciando Passo 1 (IDF) com " + numThreads + " Platform Threads...");

        for (int i = 0; i < numThreads; i++) {
            final Segment segment = segments.get(i);
            final int threadId = i;
            threadResults[threadId] = new ThreadResult();

            threads[threadId] = Thread.ofPlatform()
                    .name("passo1-thread-seg-" + segment.id()) 
                    .start(() -> {
                        ThreadResult result = threadResults[threadId];
                        try (RandomAccessFile raf = new RandomAccessFile(FILE_PATH, "r")) {
                            raf.seek(segment.start());
                            try (var is = Channels.newInputStream(raf.getChannel());
                                 var reader = new BufferedReader(new InputStreamReader(is))) {
                                String row;
                                long bytesRead = 0;
                                while (bytesRead < segment.size() && (row = reader.readLine()) != null) {
                                    bytesRead += row.getBytes().length + 1;
                                    
                                    // OTIMIZAÇÃO: StringTokenizer evita RegEx pesada
                                    StringTokenizer st = new StringTokenizer(row.toLowerCase());
                                    if (!st.hasMoreTokens()) continue;

                                    result.docCount++;
                                    while (st.hasMoreTokens()) {
                                        result.localDocumentsWithTerm.merge(st.nextToken(), 1, Integer::sum);
                                    }
                                }
                            }
                        } catch (IOException e) { e.printStackTrace(); }
                    });
        }
        joinThreads(threads);

        Map<String, Integer> documentsWithTerm = new HashMap<>();
        long totalDocuments = 0;
        for (ThreadResult tr : threadResults) {
            totalDocuments += tr.docCount;
            tr.localDocumentsWithTerm.forEach((k, v) -> documentsWithTerm.merge(k, v, Integer::sum));
        }

        System.out.println("Passo 1 concluído. Total de documentos: " + totalDocuments);

        System.out.println("Iniciando Passo 2 (TF-IDF) em paralelo...");
        long[] lineOffsets = new long[numThreads];
        long currentOffset = 0;
        for (int i = 0; i < numThreads; i++) {
            lineOffsets[i] = currentOffset;
            currentOffset += threadResults[i].docCount;
        }

        threads = new Thread[numThreads]; 
        for (int i = 0; i < numThreads; i++) {
            final Segment segment = segments.get(i);
            final int threadId = i;
            final long totalDocsCount = totalDocuments;
            final long startLine = lineOffsets[threadId];

            threads[threadId] = Thread.ofPlatform().start(() -> {
                String tempPartFile = RESULT_FILE + ".part" + segment.id();
                long currentLine = startLine;
                // OTIMIZAÇÃO: Reutilização de Map para evitar milhões de alocações
                Map<String, Integer> freq = new HashMap<>(64);
                StringBuilder sb = new StringBuilder(256);

                try (RandomAccessFile raf = new RandomAccessFile(FILE_PATH, "r");
                     BufferedWriter bw = new BufferedWriter(new FileWriter(tempPartFile))) {
                    raf.seek(segment.start());
                    try (var is = Channels.newInputStream(raf.getChannel());
                         var reader = new BufferedReader(new InputStreamReader(is))) {
                        String row;
                        long bytesRead = 0;
                        while (bytesRead < segment.size() && (row = reader.readLine()) != null) {
                            bytesRead += row.getBytes().length + 1;
                            
                            freq.clear();
                            StringTokenizer st = new StringTokenizer(row.toLowerCase());
                            int wordCount = 0;
                            while(st.hasMoreTokens()) {
                                freq.merge(st.nextToken(), 1, Integer::sum);
                                wordCount++;
                            }
                            if (wordCount == 0) continue;
                            
                            currentLine++;
                            sb.setLength(0); // Limpa o buffer de escrita
                            for (Map.Entry<String, Integer> entry : freq.entrySet()) {
                                double tf = (double) entry.getValue() / wordCount;
                                double idf = Math.log((double) totalDocsCount / documentsWithTerm.get(entry.getKey()));
                                sb.append(currentLine).append(';').append(entry.getKey()).append(';')
                                  .append(String.format("%.10f", tf * idf)).append('\n');
                            }
                            bw.write(sb.toString());
                        }
                    }
                } catch (IOException e) { e.printStackTrace(); }
            });
        }
        joinThreads(threads);
        mergePartFiles(numThreads);
        System.out.println("Processamento concluído em " + (System.currentTimeMillis() - globalStart) + " ms!");
    }

    private static List<Segment> getSegments() {
        try (RandomAccessFile file = new RandomAccessFile(FILE_PATH, "r")) {
            long totalSize = file.length();
            int cores = Runtime.getRuntime().availableProcessors();
            long segmentSize = totalSize / cores;
            List<Segment> segments = new ArrayList<>();
            long filePos = 0;
            int id = 0;

            while (filePos < totalSize - segmentSize) {
                file.seek(filePos + segmentSize);
                while (file.read() != '\n')
                    ;
                segments.add(new Segment(id++, filePos, (int) (file.getFilePointer() - filePos)));
                filePos = file.getFilePointer();
            }
            segments.add(new Segment(id, filePos, (int) (totalSize - filePos)));
            return segments;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void joinThreads(Thread[] threads) {
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    private static void mergePartFiles(int numThreads) {
        new File(RESULT_FILE).getParentFile().mkdirs();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(RESULT_FILE))) {
            bw.write("Numero_Linha;Palavra;TF-IDF\n");

            for (int i = 0; i < numThreads; i++) {
                File partFile = new File(RESULT_FILE + ".part" + i);
                try (BufferedReader br = new BufferedReader(new FileReader(partFile))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        bw.write(line);
                        bw.newLine();
                    }
                }
                partFile.delete();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }



    // Para JMH
    public class ThreadResultJMH {
        public Map<String, Integer> localDocumentsWithTerm = new HashMap<>();
        public long docCount = 0;
        public double localChecksum = 0.0;
    }

    public double executarBenchmark(List<String> linhas, int numThreads) {
        int totalLinhas = linhas.size();
        int chunkSize = totalLinhas / numThreads;

        ThreadResultJMH[] threadResults = new ThreadResultJMH[numThreads];
        Thread[] threads = new Thread[numThreads];

        // Passo 1: IDF (em memória)
        for (int i = 0; i < numThreads; i++) {
            int start = i * chunkSize;
            int end = (i == numThreads - 1) ? totalLinhas : (start + chunkSize);
            List<String> subList = linhas.subList(start, end);
            
            threadResults[i] = new ThreadResultJMH();
            int finalI = i;
            threads[i] = Thread.ofPlatform().start(() -> {
                for (String row : subList) {
                    // OTIMIZAÇÃO 1: Usando StringTokenizer em vez de .split("\\s+")
                    // Evita criar um Array de strings e não processa RegEx repetidamente
                    StringTokenizer st = new StringTokenizer(row.toLowerCase());
                    Set<String> unicTerms = new HashSet<>();
                    while (st.hasMoreTokens()) {
                        unicTerms.add(st.nextToken());
                    }
                    
                    for (String term : unicTerms) {
                        if (!term.isEmpty()) {
                            threadResults[finalI].localDocumentsWithTerm.merge(term, 1, Integer::sum);
                        }
                    }
                    threadResults[finalI].docCount++;
                }
            });
        }
        joinThreads(threads);

        // Consolidação (IDF Global)
        Map<String, Integer> documentsWithTerm = new HashMap<>();
        long totalDocuments = 0;
        for (ThreadResultJMH tr : threadResults) {
            totalDocuments += tr.docCount;
            tr.localDocumentsWithTerm.forEach((k, v) -> documentsWithTerm.merge(k, v, Integer::sum));
        }

        // Passo 2: TF-IDF
        for (int i = 0; i < numThreads; i++) {
            int start = i * chunkSize;
            int end = (i == numThreads - 1) ? totalLinhas : (start + chunkSize);
            List<String> subList = linhas.subList(start, end);
            
            int finalI = i; // Referência para o array
            long finalTotal = totalDocuments;
            
            threads[i] = Thread.ofPlatform().start(() -> {
                Map<String, Integer> freq = new HashMap<>(64); 
                for (String row : subList) {
                    freq.clear();
                    StringTokenizer st = new StringTokenizer(row.toLowerCase());
                    int wordCount = 0;
                    
                    while (st.hasMoreTokens()) {
                        String p = st.nextToken();
                        if (!p.isEmpty()) {
                            freq.merge(p, 1, Integer::sum);
                            wordCount++;
                        }
                    }
                    
                    for (Map.Entry<String, Integer> entry : freq.entrySet()) {
                        double tf = (double) entry.getValue() / wordCount;
                        double idf = Math.log((double) finalTotal / documentsWithTerm.get(entry.getKey()));
                        
                        // CORREÇÃO AQUI: Acumular no campo da PRÓPRIA thread
                        threadResults[finalI].localChecksum += (tf * idf);
                    }
                }
            });
        }
        joinThreads(threads);

        // SOMA FINAL: Consolida os resultados das threads
        double totalChecksum = 0.0;
        for (ThreadResultJMH tr : threadResults) {
            totalChecksum += tr.localChecksum;
        }
        
        return totalChecksum;
    }
}