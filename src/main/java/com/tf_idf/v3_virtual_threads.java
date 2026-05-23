package com.tf_idf;

import java.io.*;
import java.nio.channels.Channels;
import java.util.*;
import java.util.concurrent.Executors;

public class v3_virtual_threads {

    private static final String FILE_PATH = "corpus_grande.txt";
    private static final String RESULT_FILE = "resultados/java_v3_resultado_tfidf_geral.csv";
    
    // Tamanho do bloco para divisão do arquivo (10 MB).
    private static final long CHUNK_SIZE = 10 * 1024 * 1024; 

    private record Segment(int id, long start, int size) {}

    private static class ThreadResult {
        long docCount = 0;
        Map<String, Integer> localDocumentsWithTerm = new HashMap<>(1024);
    }

    public static void main(String[] args) {
        long globalStart = System.currentTimeMillis();

        List<Segment> segments = getSegments();
        int numTasks = segments.size();
        ThreadResult[] threadResults = new ThreadResult[numTasks];

        // PASSO 1: Frequência Documental com Virtual Threads manuais
        System.out.println("Iniciando Passo 1 (IDF) com " + numTasks + " Virtual Threads...");
        List<Thread> threads1 = new ArrayList<>();
        for (int i = 0; i < numTasks; i++) {
            final Segment segment = segments.get(i);
            final int taskId = i;
            threadResults[taskId] = new ThreadResult();

            threads1.add(Thread.ofVirtual().name("V-IDF-" + i).start(() -> {
                ThreadResult result = threadResults[taskId];
                try (RandomAccessFile raf = new RandomAccessFile(FILE_PATH, "r")) {
                    raf.seek(segment.start());
                    try (var is = Channels.newInputStream(raf.getChannel());
                         var reader = new BufferedReader(new InputStreamReader(is), 65536)) {
                        String row;
                        long bytesRead = 0;
                        while (bytesRead < segment.size() && (row = reader.readLine()) != null) {
                            bytesRead += row.getBytes().length + 1;
                            result.docCount++;
                            StringTokenizer st = new StringTokenizer(row.toLowerCase());
                            Set<String> uniqueTerms = new HashSet<>();
                            while (st.hasMoreTokens()) uniqueTerms.add(st.nextToken());
                                
                            for (String term : uniqueTerms) {
                                result.localDocumentsWithTerm.merge(term, 1, Integer::sum);
                            }
                        }
                    }
                } catch (IOException e) { e.printStackTrace(); }
            }));
        }
        joinThreads(threads1);

        // Consolidação
        Map<String, Integer> documentsWithTerm = new HashMap<>(10000);
        long totalDocuments = 0;
        for (ThreadResult tr : threadResults) {
            totalDocuments += tr.docCount;
            tr.localDocumentsWithTerm.forEach((k, v) -> documentsWithTerm.merge(k, v, Integer::sum));
        }

        // PASSO 2: TF-IDF com Virtual Threads manuais
        System.out.println("Iniciando Passo 2 (TF-IDF)...");
        long[] lineOffsets = new long[numTasks];
        long currentOffset = 0;
        for (int i = 0; i < numTasks; i++) {
            lineOffsets[i] = currentOffset;
            currentOffset += threadResults[i].docCount;
        }

        List<Thread> threads2 = new ArrayList<>();
        for (int i = 0; i < numTasks; i++) {
            final Segment segment = segments.get(i);
            final int taskId = i;
            final long totalDocsCount = totalDocuments;
            final long startLine = lineOffsets[taskId];

            threads2.add(Thread.ofVirtual().name("V-TFIDF-" + i).start(() -> {
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
                            currentLine++;
                                
                            freq.clear();
                            StringTokenizer st = new StringTokenizer(row.toLowerCase());
                            int wordCount = 0;
                            while(st.hasMoreTokens()) {
                                freq.merge(st.nextToken(), 1, Integer::sum);  
                                wordCount++;
                            }
                            
                            sb.setLength(0);
                            for (Map.Entry<String, Integer> entry : freq.entrySet()) {
                                double tf = (double) entry.getValue() / wordCount;
                                double idf = Math.log((double) totalDocsCount / documentsWithTerm.get(entry.getKey()));
                                sb.append(currentLine).append(';').append(entry.getKey()).append(';')
                                  .append(String.format(Locale.US, "%.10f", tf * idf)).append('\n');
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

    
    /**
     * Modificado para fatiar o arquivo em pedaços de tamanho fixo (Ex: 10MB)
     * permitindo uma concorrência muito maior ideal para Virtual Threads.
     */
    private static List<Segment> getSegments() {
        try (RandomAccessFile file = new RandomAccessFile(FILE_PATH, "r")) {
            long totalSize = file.length();
            List<Segment> segments = new ArrayList<>();
            long filePos = 0;
            int id = 0;

            while (filePos < totalSize - CHUNK_SIZE) {
                file.seek(filePos + CHUNK_SIZE);
                // Garante que não vamos cortar uma linha ao meio, vai até o fim dela
                while (file.read() != '\n')
                    ;
                segments.add(new Segment(id++, filePos, (int) (file.getFilePointer() - filePos)));
                filePos = file.getFilePointer();
            }
            // Adiciona o último pedaço restante do arquivo
            if (filePos < totalSize) {
                segments.add(new Segment(id, filePos, (int) (totalSize - filePos)));
            }
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

    private static void mergePartFiles(int numTasks) {
        new File(RESULT_FILE).getParentFile().mkdirs();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(RESULT_FILE))) {
            bw.write("Numero_Linha;Palavra;TF-IDF\n");

            for (int i = 0; i < numTasks; i++) {
                File partFile = new File(RESULT_FILE + ".part" + i);
                if (!partFile.exists()) continue;
                
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



    // --------Para JMH-----------
    public double executarBenchmark(List<String> linhas, int numTasks) {
        int totalLinhas = linhas.size();
        int chunkSize = totalLinhas / numTasks;
        
        // Estrutura para consolidar resultados do benchmark
        class Result { 
            double checksum = 0.0; 
            long docCount = 0; 
            Map<String, Integer> map = new HashMap<>(); 
        }
        
        Result[] results = new Result[numTasks];

        // Passo 1: IDF (Consolidação em memória) com Virtual Threads manuais
        List<Thread> threads1 = new ArrayList<>();
        for (int i = 0; i < numTasks; i++) {
            int start = i * chunkSize;
            int end = (i == numTasks - 1) ? totalLinhas : (start + chunkSize);
            List<String> subList = linhas.subList(start, end);
            results[i] = new Result();
            int idx = i;
            
            threads1.add(Thread.ofVirtual().name("JMH-V-IDF-" + i).start(() -> {
                for (String row : subList) {
                    StringTokenizer st = new StringTokenizer(row.toLowerCase());
                    while (st.hasMoreTokens()) results[idx].map.merge(st.nextToken(), 1, Integer::sum);
                    results[idx].docCount++;
                }
            }));
        }
        // Aguarda conclusão do Passo 1
        joinThreads(threads1);

        Map<String, Integer> globalMap = new HashMap<>();
        long totalDocs = 0;
        for (Result r : results) { 
            totalDocs += r.docCount; 
            r.map.forEach((k, v) -> globalMap.merge(k, v, Integer::sum)); 
        }

        // Passo 2: TF-IDF (Benchmark) com Virtual Threads manuais
        List<Thread> threads2 = new ArrayList<>();
        for (int i = 0; i < numTasks; i++) {
            int start = i * chunkSize;
            int end = (i == numTasks - 1) ? totalLinhas : (start + chunkSize);
            List<String> subList = linhas.subList(start, end);
            int idx = i;
            long total = totalDocs;
            
            threads2.add(Thread.ofVirtual().name("JMH-V-TFIDF-" + i).start(() -> {
                Map<String, Integer> freq = new HashMap<>(64);
                for (String row : subList) {
                    freq.clear();
                    StringTokenizer st = new StringTokenizer(row.toLowerCase());
                    int wCount = 0;
                    while (st.hasMoreTokens()) { freq.merge(st.nextToken(), 1, Integer::sum); wCount++; }
                    for (var entry : freq.entrySet()) {
                        double tf = (double) entry.getValue() / wCount;
                        double idf = Math.log((double) total / globalMap.get(entry.getKey()));
                        results[idx].checksum += (tf * idf);
                    }
                }
            }));
        }
        // Aguarda conclusão do Passo 2
        joinThreads(threads2);

        return Arrays.stream(results).mapToDouble(r -> r.checksum).sum();
    }
}