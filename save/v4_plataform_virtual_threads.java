package com.tf_idf;

import java.io.*;
import java.nio.channels.Channels;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class v4_plataform_virtual_threads {

    private static final String FILE_PATH = "corpus_grande.txt";
    private static final String RESULT_FILE = "resultados/java_v4_resultado_tfidf.csv";
    private static final long CHUNK_SIZE = 10 * 1024 * 1024;

    private record Segment(int id, long start, int size) {}

    // Classe para armazenar resultados locais (sem locks)
    private static class ThreadResult {
        long docCount = 0;
        final Map<String, Integer> localDf = new HashMap<>();
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        long globalStart = System.currentTimeMillis();
        List<Segment> segments = getSegments();
        int numTasks = segments.size();
        ThreadResult[] threadResults = new ThreadResult[numTasks];
        Thread[] workers = new Thread[numTasks];

        // PASSO 1: Frequência Documental (IDF) - Isolação total
        for (int i = 0; i < numTasks; i++) {
            final Segment segment = segments.get(i);
            threadResults[i] = new ThreadResult();
            final ThreadResult res = threadResults[i];

            workers[i] = Thread.ofVirtual().start(() -> {
                try (RandomAccessFile raf = new RandomAccessFile(FILE_PATH, "r")) {
                    raf.seek(segment.start());
                    try (var reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(raf.getChannel())))) {
                        String row;
                        long bytesRead = 0;
                        while (bytesRead < segment.size() && (row = reader.readLine()) != null) {
                            bytesRead += row.getBytes().length + 1;
                            res.docCount++;
                            StringTokenizer st = new StringTokenizer(row.toLowerCase());
                            Set<String> uniqueTerms = new HashSet<>();
                            while (st.hasMoreTokens()) uniqueTerms.add(st.nextToken());
                            for (String term : uniqueTerms) {
                                res.localDf.merge(term, 1, Integer::sum);
                            }
                        }
                    }
                } catch (IOException e) { e.printStackTrace(); }
            });
        }
        for (Thread w : workers) w.join();

        // Consolidação Global (Ocorre apenas uma vez, sem concorrência)
        ConcurrentHashMap<String, LongAdder> globalDf = new ConcurrentHashMap<>();
        long totalDocuments = 0;
        for (ThreadResult tr : threadResults) {
            totalDocuments += tr.docCount;
            tr.localDf.forEach((k, v) -> globalDf.computeIfAbsent(k, key -> new LongAdder()).add(v));
        }

        // PASSO 2: TF-IDF (Processamento independente)
        long currentOffset = 0;
        for (int i = 0; i < numTasks; i++) {
            final Segment segment = segments.get(i);
            final long startLine = currentOffset;
            currentOffset += threadResults[i].docCount;
            final long totalDocsCount = totalDocuments;

            workers[i] = Thread.ofPlatform().start(() -> {
                String tempPartFile = RESULT_FILE + ".part" + segment.id();
                try (RandomAccessFile raf = new RandomAccessFile(FILE_PATH, "r");
                     BufferedWriter bw = new BufferedWriter(new FileWriter(tempPartFile))) {
                    raf.seek(segment.start());
                    try (var reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(raf.getChannel())))) {
                        String row;
                        long bytesRead = 0;
                        long currentLine = startLine;
                        while (bytesRead < segment.size() && (row = reader.readLine()) != null) {
                            bytesRead += row.getBytes().length + 1;
                            currentLine++;
                            
                            Map<String, Integer> freq = new HashMap<>();
                            StringTokenizer st = new StringTokenizer(row.toLowerCase());
                            int wordCount = 0;
                            while(st.hasMoreTokens()) { freq.merge(st.nextToken(), 1, Integer::sum); wordCount++; }
                            
                            for (Map.Entry<String, Integer> entry : freq.entrySet()) {
                                double tf = (double) entry.getValue() / wordCount;
                                // Acesso ao ConcurrentHashMap é thread-safe e lock-free para leitura
                                double idf = Math.log((double) totalDocsCount / globalDf.get(entry.getKey()).sum());
                                bw.write(currentLine + ";" + entry.getKey() + ";" + String.format(Locale.US, "%.10f", tf * idf));
                                bw.newLine();
                            }
                        }
                    }
                } catch (IOException e) { e.printStackTrace(); }
            });
        }
        for (Thread w : workers) w.join();

        mergePartFiles(numTasks);
        System.out.println("Processamento concluído em " + (System.currentTimeMillis() - globalStart) + " ms!");
    }

    private static List<Segment> getSegments() throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(FILE_PATH, "r")) {
            long totalSize = file.length();
            List<Segment> segments = new ArrayList<>();
            long filePos = 0;
            int id = 0;
            while (filePos < totalSize - CHUNK_SIZE) {
                file.seek(filePos + CHUNK_SIZE);
                while (file.read() != '\n' && file.getFilePointer() < totalSize);
                segments.add(new Segment(id++, filePos, (int) (file.getFilePointer() - filePos)));
                filePos = file.getFilePointer();
            }
            if (filePos < totalSize) segments.add(new Segment(id, filePos, (int) (totalSize - filePos)));
            return segments;
        }
    }

    private static void mergePartFiles(int numTasks) throws IOException {
        new File(RESULT_FILE).getParentFile().mkdirs();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(RESULT_FILE))) {
            bw.write("Numero_Linha;Palavra;TF-IDF\n");
            for (int i = 0; i < numTasks; i++) {
                File partFile = new File(RESULT_FILE + ".part" + i);
                if (!partFile.exists()) continue;
                try (BufferedReader br = new BufferedReader(new FileReader(partFile))) {
                    String line;
                    while ((line = br.readLine()) != null) { bw.write(line); bw.newLine(); }
                }
                partFile.delete();
            }
        }
    }



    // Para JMH
    static class Result {
        double checksum = 0.0;
        long docCount = 0;
        Map<String, Integer> map = new HashMap<>();
    }

    public double executarBenchmark(List<String> linhas, int numTasks) throws InterruptedException {
        int totalLinhas = linhas.size();
        int chunkSize = totalLinhas / numTasks;
        Result[] results = new Result[numTasks];
        Thread[] workers = new Thread[numTasks];

        // --- PASSO 1: IDF (Sem Executors, Sem Locks) ---
        for (int i = 0; i < numTasks; i++) {
            final int idx = i;
            int start = i * chunkSize;
            int end = (i == numTasks - 1) ? totalLinhas : (start + chunkSize);
            List<String> subList = linhas.subList(start, end);
            results[idx] = new Result();

            // Criamos uma única Virtual Thread por chunk
            workers[idx] = Thread.ofVirtual().start(() -> {
                for (String row : subList) {
                    StringTokenizer st = new StringTokenizer(row.toLowerCase());
                    // Set local (não precisa de lock)
                    Set<String> uniqueTerms = new HashSet<>();
                    while (st.hasMoreTokens()) uniqueTerms.add(st.nextToken());
                    
                    // Atualiza o mapa local (não precisa de lock pois é de uso exclusivo da thread)
                    for (String term : uniqueTerms) {
                        results[idx].map.merge(term, 1, Integer::sum);
                    }
                }
                results[idx].docCount = subList.size();
            });
        }
        for (Thread w : workers) w.join();

        // Consolidação Global (Feita após o join, sem concorrência)
        Map<String, Integer> globalMap = new HashMap<>();
        long totalDocs = 0;
        for (Result r : results) {
            totalDocs += r.docCount;
            r.map.forEach((k, v) -> globalMap.merge(k, v, Integer::sum));
        }

        // --- PASSO 2: TF-IDF (Sem Locks) ---
        for (int i = 0; i < numTasks; i++) {
            final int idx = i;
            List<String> subList = linhas.subList(i * chunkSize, (i == numTasks - 1) ? totalLinhas : (i * chunkSize + chunkSize));
            long total = totalDocs;

            workers[idx] = Thread.ofPlatform().start(() -> {
                double localChecksum = 0.0;
                for (String row : subList) {
                    // Cálculo de frequencia local
                    Map<String, Integer> freq = new HashMap<>(64);
                    StringTokenizer st = new StringTokenizer(row.toLowerCase());
                    int wCount = 0;
                    while (st.hasMoreTokens()) {
                        freq.merge(st.nextToken(), 1, Integer::sum);
                        wCount++;
                    }
                    // Cálculo do score (IDF lido do globalMap é seguro pois não muda mais)
                    for (var entry : freq.entrySet()) {
                        double tf = (double) entry.getValue() / wCount;
                        double idf = Math.log((double) total / globalMap.get(entry.getKey()));
                        localChecksum += (tf * idf);
                    }
                }
                results[idx].checksum = localChecksum; // Sem lock: cada thread escreve no seu índice
            });
        }
        for (Thread w : workers) w.join();

        return Arrays.stream(results).mapToDouble(r -> r.checksum).sum();
    }
}