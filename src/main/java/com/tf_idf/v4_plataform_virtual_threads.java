package com.tf_idf;

import java.io.*;
import java.nio.channels.Channels;
import java.util.*;

public class v4_plataform_virtual_threads {

    private static final String FILE_PATH = "corpus_grande.txt";
    private static final String RESULT_FILE = "resultados/java_v4_resultado_tfidf.csv";
    private static final long CHUNK_SIZE = 10 * 1024 * 1024; // 10 MB

    private record Segment(int id, long start, int size) {}

    private static class ThreadResult {
        long docCount = 0;
        Map<String, Integer> localDocumentsWithTerm = new HashMap<>();
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        long globalStart = System.currentTimeMillis();
        List<Segment> segments = getSegments();
        int numTasks = segments.size();
        ThreadResult[] threadResults = new ThreadResult[numTasks];
        Thread[] workers = new Thread[numTasks];

        System.out.println("Iniciando processamento v4 (Híbrido) com " + numTasks + " P-Threads...");

        // PASSO 1: Frequência Documental (P-Threads para I/O, V-Threads para processamento)
        for (int i = 0; i < numTasks; i++) {
            final Segment segment = segments.get(i);
            final int taskId = i;
            threadResults[taskId] = new ThreadResult();

            workers[taskId] = Thread.ofPlatform().start(() -> {
                try (RandomAccessFile raf = new RandomAccessFile(FILE_PATH, "r")) {
                    raf.seek(segment.start());
                    try (var is = Channels.newInputStream(raf.getChannel());
                         var reader = new BufferedReader(new InputStreamReader(is))) {
                        String row;
                        long bytesRead = 0;
                        while (bytesRead < segment.size() && (row = reader.readLine()) != null) {
                            bytesRead += row.getBytes().length + 1;
                            threadResults[taskId].docCount++;
                            
                            final String line = row;
                            final ThreadResult res = threadResults[taskId];
                            // Processamento em V-Thread
                            Thread.ofVirtual().start(() -> {
                                StringTokenizer st = new StringTokenizer(line.toLowerCase());
                                Set<String> uniqueTerms = new HashSet<>();
                                while (st.hasMoreTokens()) uniqueTerms.add(st.nextToken());
                                for (String term : uniqueTerms) res.localDocumentsWithTerm.merge(term, 1, Integer::sum);
                            }).join(); // join aqui garante a atomicidade do passo
                        }
                    }
                } catch (IOException | InterruptedException e) { e.printStackTrace(); }
            });
        }
        for (Thread w : workers) w.join();

        // Consolidação
        Map<String, Integer> documentsWithTerm = new HashMap<>();
        long totalDocuments = 0;
        for (ThreadResult tr : threadResults) {
            totalDocuments += tr.docCount;
            tr.localDocumentsWithTerm.forEach((k, v) -> documentsWithTerm.merge(k, v, Integer::sum));
        }

        // PASSO 2: TF-IDF
        long[] lineOffsets = new long[numTasks];
        long currentOffset = 0;
        for (int i = 0; i < numTasks; i++) {
            lineOffsets[i] = currentOffset;
            currentOffset += threadResults[i].docCount;
        }

        for (int i = 0; i < numTasks; i++) {
            final Segment segment = segments.get(i);
            final int taskId = i;
            final long totalDocsCount = totalDocuments;
            final long startLine = lineOffsets[taskId];

            workers[taskId] = Thread.ofPlatform().start(() -> {
                String tempPartFile = RESULT_FILE + ".part" + segment.id();
                try (RandomAccessFile raf = new RandomAccessFile(FILE_PATH, "r");
                     BufferedWriter bw = new BufferedWriter(new FileWriter(tempPartFile))) {
                    raf.seek(segment.start());
                    try (var is = Channels.newInputStream(raf.getChannel());
                         var reader = new BufferedReader(new InputStreamReader(is))) {
                        String row;
                        long bytesRead = 0;
                        long currentLine = startLine;
                        Map<String, Integer> freq = new HashMap<>(64);
                        StringBuilder sb = new StringBuilder(256);

                        while (bytesRead < segment.size() && (row = reader.readLine()) != null) {
                            bytesRead += row.getBytes().length + 1;
                            currentLine++;
                            freq.clear();
                            StringTokenizer st = new StringTokenizer(row.toLowerCase());
                            int wordCount = 0;
                            while(st.hasMoreTokens()) { freq.merge(st.nextToken(), 1, Integer::sum); wordCount++; }
                            
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
                while (file.read() != '\n');
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
}