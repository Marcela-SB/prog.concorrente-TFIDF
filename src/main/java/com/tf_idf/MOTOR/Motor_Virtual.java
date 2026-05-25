package com.tf_idf.MOTOR;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.tf_idf.TFIDF_Calculator;
import com.tf_idf.AUXILIARES.Segmentador.Segment;

public class Motor_Virtual {
    // Tamanho do bloco para divisão do arquivo (10 MB).
    private static final long CHUNK_SIZE = 10 * 1024 * 1024;

    private final String filePath;
    private final TFIDF_Calculator calculator = new TFIDF_Calculator();

    public Motor_Virtual(String filePath) { 
        this.filePath = filePath; 
    }

    private static class ThreadResult {
        long docCount = 0;
        Map<String, Integer> localDocumentsWithTerm = new HashMap<>();
    }

    public void processar(String outputPath, List<Segment> segments) {
    //public void processar(List<Segment> segments, Consumer<String> resultConsumer) {
        int numTasks = segments.size();
        ThreadResult[] threadResults = new ThreadResult[numTasks];

        // Passo 1: IDF
        // System.out.println("Passo 1: IDF");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < numTasks; i++) {
                threadResults[i] = new ThreadResult();
                final Segment segment = segments.get(i);
                final ThreadResult res = threadResults[i];
                executor.submit(() -> processarSegmentoIDF(segment, res));
            }
        }

        // Consolidar Resultados
        // System.out.println("Consolidar Resultados...");
        Map<String, Integer> documentsWithTerm = new HashMap<>();
        long totalDocuments = 0;
        for (ThreadResult tr : threadResults) {
            totalDocuments += tr.docCount;
            tr.localDocumentsWithTerm.forEach((k, v) -> documentsWithTerm.merge(k, v, Integer::sum));
        }
        
        // Passo 2: TF-IDF (Agora que temos totalDocuments e documentsWithTerm)
        // System.out.println("Passo 2: F-IDF");
        final long totalDocsCount = totalDocuments;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Segment seg : segments) {
                executor.submit(() -> {
                    processarSegmentoTFIDF(seg, documentsWithTerm, totalDocsCount, 
                        outputPath + ".part" + seg.id()
                        //resultConsumer
                    );
                });
            }
        }

        mergePartFiles(numTasks, outputPath);
    }

    private ThreadResult processarSegmentoIDF(Segment seg, ThreadResult result){
        // System.out.println("processarSegmentoIDF()");
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
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r");
            BufferedWriter bw = new BufferedWriter(new FileWriter(out)) //
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
        } catch (IOException e) { e.printStackTrace(); }
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
            List<Segment> segments = new ArrayList<>();
            long filePos = 0;
            int id = 0;

            while (filePos < totalSize - CHUNK_SIZE) {
                file.seek(filePos + CHUNK_SIZE);
                while (file.read() != '\n');
                segments.add(new Segment(id++, filePos, (int) (file.getFilePointer() - filePos)));
                filePos = file.getFilePointer();
            }
            if (filePos < totalSize) {
                segments.add(new Segment(id, filePos, (int) (totalSize - filePos)));
            }
            return segments;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void mergePartFiles(int numThreads, String outputPath) {
        // System.out.println("Dando merge...");
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
}