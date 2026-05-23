package com.tf_idf;

import java.io.*;
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
        int numTasks = segments.size();
        
        ThreadResult[] threadResults = new ThreadResult[numTasks];

        System.out.println("Iniciando Passo 1 (IDF) com " + numTasks + " Platform Threads...");

        List<Thread> threads1 = new ArrayList<>();
        for (int i = 0; i < numTasks; i++) {
            final Segment segment = segments.get(i);
            final int threadId = i;
            threadResults[threadId] = new ThreadResult();

            threads1.add(Thread.ofPlatform().name("IDF-Thread-" + i).start(() -> {
                ThreadResult result = threadResults[threadId];
                try (RandomAccessFile raf = new RandomAccessFile(FILE_PATH, "r")) {
                    raf.seek(segment.start());
                    try (var is = Channels.newInputStream(raf.getChannel());
                         var reader = new BufferedReader(new InputStreamReader(is))) {
                        String row;
                        long bytesRead = 0;
                        while (bytesRead < segment.size() && (row = reader.readLine()) != null) {
                            bytesRead += row.getBytes().length + 1;
                            StringTokenizer st = new StringTokenizer(row.toLowerCase());
                            if (!st.hasMoreTokens()) continue;
                            result.docCount++;
                            while (st.hasMoreTokens()) {
                                result.localDocumentsWithTerm.merge(st.nextToken(), 1, Integer::sum);
                            }
                        }
                    }
                } catch (IOException e) { e.printStackTrace(); }
            }));
        }
        joinThreads(threads1);

        Map<String, Integer> documentsWithTerm = new HashMap<>();
        long totalDocuments = 0;
        for (ThreadResult tr : threadResults) {
            totalDocuments += tr.docCount;
            tr.localDocumentsWithTerm.forEach((k, v) -> documentsWithTerm.merge(k, v, Integer::sum));
        }

        System.out.println("Passo 1 concluído. Total de documentos: " + totalDocuments);
        long[] lineOffsets = new long[numTasks];
        long currentOffset = 0;
        for (int i = 0; i < numTasks; i++) {
            lineOffsets[i] = currentOffset;
            currentOffset += threadResults[i].docCount;
        }

        // Passo 2: TF-IDF
        List<Thread> threads2 = new ArrayList<>();
        for (int i = 0; i < numTasks; i++) {
            final int threadId = i;
            final Segment segment = segments.get(i);
            final long totalDocsCount = totalDocuments;
            final long startLine = lineOffsets[threadId];

            threads2.add(Thread.ofPlatform().name("TFIDF-Thread-" + i).start(() -> {
                String tempPartFile = RESULT_FILE + ".part" + segment.id();
                long currentLine = startLine;
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
                            sb.setLength(0);
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
            }));
        }
        joinThreads(threads2);
        mergePartFiles(numTasks);
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
                while (file.read() != '\n');
                segments.add(new Segment(id++, filePos, (int) (file.getFilePointer() - filePos)));
                filePos = file.getFilePointer();
            }
            segments.add(new Segment(id, filePos, (int) (totalSize - filePos)));
            return segments;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void joinThreads(List<Thread> threads) {
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

        // Passo 1: IDF com Platform Threads manuais
        List<Thread> threads1 = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            int start = i * chunkSize;
            int end = (i == numThreads - 1) ? totalLinhas : (start + chunkSize);
            List<String> subList = linhas.subList(start, end);
            
            threadResults[i] = new ThreadResultJMH();
            int finalI = i;

            threads1.add(Thread.ofPlatform().name("JMH-IDF-" + i).start(() -> {
                for (String row : subList) {
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
            }));
        }
        joinThreads(threads1);

        // Consolidação (IDF Global)
        Map<String, Integer> documentsWithTerm = new HashMap<>();
        long totalDocuments = 0;
        for (ThreadResultJMH tr : threadResults) {
            totalDocuments += tr.docCount;
            tr.localDocumentsWithTerm.forEach((k, v) -> documentsWithTerm.merge(k, v, Integer::sum));
        }

        // Passo 2: TF-IDF com Platform Threads manuais
        List<Thread> threads2 = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            int start = i * chunkSize;
            int end = (i == numThreads - 1) ? totalLinhas : (start + chunkSize);
            List<String> subList = linhas.subList(start, end);
            
            int finalI = i; 
            long finalTotal = totalDocuments;

            threads2.add(Thread.ofPlatform().name("JMH-TFIDF-" + i).start(() -> {
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
                        threadResults[finalI].localChecksum += (tf * idf);
                    }
                }
            }));
        }
        joinThreads(threads2);

        // SOMA FINAL: Consolida os resultados das threads
        double totalChecksum = 0.0;
        for (ThreadResultJMH tr : threadResults) {
            totalChecksum += tr.localChecksum;
        }
        
        return totalChecksum;
    }
}