package com.tf_idf.MOTOR;

import java.io.*;
import java.nio.channels.Channels;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.tf_idf.TFIDF_Calculator;
import com.tf_idf.AUXILIARES.Segmentador.Segment;

public class Motor_Platform {
    private final String filePath;
    private final int numThreads;
    private final TFIDF_Calculator calculator = new TFIDF_Calculator();

    public Motor_Platform(String filePath, int numThreads) { 
        this.filePath = filePath; 
        this.numThreads = numThreads;
    }

    private static class ThreadResult {
        long docCount = 0;
        Map<String, Integer> localDocumentsWithTerm = new HashMap<>();
    }

    public void processar(String outputPath, List<Segment> segments) {
    //public void processar(List<Segment> segments, Consumer<String> resultConsumer) {
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        ThreadResult[] threadResults = new ThreadResult[numThreads];

        // Passo 1: IDF
        for (int i = 0; i < numThreads; i++) {
            threadResults[i] = new ThreadResult();
            final Segment segment = segments.get(i);
            final ThreadResult res = threadResults[i];
            executor.submit(() -> processarSegmentoIDF(segment, res));
        }
        encerrarExecutor(executor);

        // Consolidar Resultados
        Map<String, Integer> documentsWithTerm = new HashMap<>();
        long totalDocuments = 0;
        for (ThreadResult tr : threadResults) {
            totalDocuments += tr.docCount;
            tr.localDocumentsWithTerm.forEach((k, v) ->
                documentsWithTerm.merge(k, v, Integer::sum));
        }
        
        // Passo 2: TF-IDF (Agora que temos totalDocuments e documentsWithTerm)
        final long totalDocsCount = totalDocuments;
        executor = Executors.newFixedThreadPool(numThreads);
        for (Segment seg : segments) {
            executor.submit(() -> {
                processarSegmentoTFIDF(seg, documentsWithTerm, totalDocsCount, 
                outputPath + ".part" + seg.id()
                //resultConsumer
            );
            });
        }
        encerrarExecutor(executor);

        mergePartFiles(numThreads, outputPath);
    }

    private ThreadResult processarSegmentoIDF(Segment seg, ThreadResult result){
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            raf.seek(seg.start());
            try (var is = Channels.newInputStream(raf.getChannel());
                    var reader = new BufferedReader(new InputStreamReader(is))) {
                String row;
                long bytesRead = 0;
                while (bytesRead < seg.size() && (row = reader.readLine()) != null) {
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
        return result;
    }

    private void processarSegmentoTFIDF(Segment seg, Map<String, Integer> idf, long total, String out /*Consumer<String> resultConsumer*/) {
        try (
            RandomAccessFile raf = new RandomAccessFile(filePath, "r");
            BufferedWriter bw = new BufferedWriter(new FileWriter(out))
        ) {
            raf.seek(seg.start());
            try (var reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(raf.getChannel())))) {
                String row;
                long bytesRead = 0;
                while (bytesRead < seg.size() && (row = reader.readLine()) != null) {
                    bytesRead += row.getBytes().length + 1;
                    Map<String, Integer> freq = contarFrequencias(row);
                    
                    for (Map.Entry<String, Integer> entry : freq.entrySet()) {
                        double score = calculator.calcularScore(entry.getValue(), row.split("\\s+").length, total, idf.get(entry.getKey()));
                        bw.write(String.format(Locale.US, "%s;%.10f\n", entry.getKey(), score));
                        //resultConsumer.accept(String.format(Locale.US, "%s;%.10f\n", entry.getKey(), score));
                    }
                }
            }
        }
        catch (IOException e) { e.printStackTrace(); }
    }
    

    public Map<String, Integer> contarFrequencias(String row) {
        Map<String, Integer> freq = new HashMap<>();
        StringTokenizer st = new StringTokenizer(row.toLowerCase());
        while (st.hasMoreTokens()) {
            freq.merge(st.nextToken(), 1, Integer::sum);
        }
        return freq;
    }

    public List<Segment> getSegments() {
        try (RandomAccessFile file = new RandomAccessFile(filePath, "r")) {
            long totalSize = file.length();
            long segmentSize = totalSize / numThreads;
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

    private static void mergePartFiles(int numThreads, String outputPath) {
        new File(outputPath).getParentFile().mkdirs();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputPath))) {
            bw.write("Numero_Linha;Palavra;TF-IDF\n");

            for (int i = 0; i < numThreads; i++) {
                File partFile = new File(outputPath + ".part" + i);
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

    private void encerrarExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}