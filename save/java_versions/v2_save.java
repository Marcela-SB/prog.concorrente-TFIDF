package java_versions;

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

public class v2_save {

    private static final String FILE_PATH = "corpus_grande.txt";
    private static final String RESULT_FILE = "resultados/java_v2_resultado_tfidf_geral.csv";

    private record Segment(int id, long start, int size) {}

    // Estruturas puras para armazenar o resultado local de cada thread
    private static class ThreadResult {
        long docCount = 0;
        Map<String, Integer> localDocumentsWithTerm = new HashMap<>();
    }

    public static void main(String[] args) {
        long globalStart = System.currentTimeMillis();

        List<Segment> segments = getSegments();
        int numThreads = segments.size();

        // Arrays normais indexados pelo ID da thread (Isolamento completo)
        ThreadResult[] threadResults = new ThreadResult[numThreads];
        Thread[] threads = new Thread[numThreads];

        // ================================================================================
        // PASSO 1 NAIVE: Frequência Documental (Sem estruturas concorrentes ou atômicas)
        // ================================================================================
        System.out.println("Iniciando Passo 1 (IDF) com " + numThreads + " Platform Threads (Naive)...");

        for (int i = 0; i < numThreads; i++) {
            final Segment segment = segments.get(i);
            final int threadId = i;
            threadResults[threadId] = new ThreadResult();

            // Instanciação manual de Platform Threads tradicionais
            threads[threadId] = new Thread(new Runnable() {
                @Override
                public void run() {
                    ThreadResult result = threadResults[threadId];

                    try (RandomAccessFile raf = new RandomAccessFile(FILE_PATH, "r")) {
                        raf.seek(segment.start());

                        try (var is = Channels.newInputStream(raf.getChannel());
                            var reader = new BufferedReader(new InputStreamReader(is))) {

                            String row;
                            long bytesRead = 0;

                            while (bytesRead < segment.size() && (row = reader.readLine()) != null) {
                                bytesRead += row.getBytes().length + 1;
                                result.docCount++;

                                String[] words = row.toLowerCase().split("\\s+");
                                Set<String> unicTermsInDoc = new HashSet<>(Arrays.asList(words));
                            
                                for (String term : unicTermsInDoc) {
                                    if (!term.isEmpty()) {
                                        // HashMap puro sem Lock/Sincronização
                                        result.localDocumentsWithTerm.put(term,
                                            result.localDocumentsWithTerm.getOrDefault(term, 0) + 1);
                                    }
                                }
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
            threads[threadId].start();
        }

        // Aguarda todas as threads (Join tradicional)
        joinThreads(threads);

        // --- Consolidação Sequencial na Thread Main ---
        Map<String, Integer> documentsWithTerm = new HashMap<>();
        long totalDocuments = 0;

        for (ThreadResult tr : threadResults) {
            totalDocuments += tr.docCount;
            for (Map.Entry<String, Integer> entry : tr.localDocumentsWithTerm.entrySet()) {
                documentsWithTerm.put(entry.getKey(),
                    documentsWithTerm.getOrDefault(entry.getKey(), 0) + entry.getValue());
            }
        }

        System.out.println("Passo 1 concluído. Total de documentos: " + totalDocuments);

        // ================================================================================
        // PASSO 2 NAÏVE: Calcular TF-IDF e escrever em arquivos parciais isolados
        // ================================================================================
        System.out.println("Iniciando Passo 2 (TF-IDF) em paralelo (Naïve)...");

        // Descobre o offset global de linhas de forma puramente sequencial antes de lançar as threads
        long[] lineOffsets = new long[numThreads];
        long currentOffset = 0;
        for (int i = 0; i < numThreads; i++) {
            lineOffsets[i] = currentOffset;
            currentOffset += threadResults[i].docCount;
        }

        threads = new Thread[numThreads]; // Reseta o array de threads

        for (int i = 0; i < numThreads; i++) {
            final Segment segment = segments.get(i);
            final int threadId = i;
            final long totalDocsCount = totalDocuments;
            final long startLine = lineOffsets[threadId];

            threads[threadId] = new Thread(new Runnable() {
                @Override
                public void run() {
                    String tempPartFile = RESULT_FILE + ".part" + segment.id();
                    long currentLine = startLine;

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

                                String[] words = row.toLowerCase().split("\\s+");
                                if (words.length == 0 || (words.length == 1 && words[0].isEmpty())) continue;

                                Map<String, Integer> frequencyTerm = new HashMap<>();
                                for (String p : words) {
                                    if (!p.isEmpty()) {
                                        frequencyTerm.put(p, frequencyTerm.getOrDefault(p, 0) + 1);
                                    }
                                }

                                StringBuilder sb = new StringBuilder();
                                for (String term : frequencyTerm.keySet()) {
                                    double tf = (double) frequencyTerm.get(term) / words.length;
                                
                                    // Apenas leitura no HashMap global consolidado (Operação segura pós-Passo 1)
                                    int docCount = documentsWithTerm.get(term);

                                    double idf = Math.log((double) totalDocsCount / docCount);
                                    double tfidf = tf * idf;

                                    sb.append(String.format(Locale.US, "%d;%s;%.10f\n", currentLine, term, tfidf));
                                }
                                bw.write(sb.toString()); // Escrita isolada por arquivo parcial
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
            threads[threadId].start();
        }

        joinThreads(threads);

        // ================================================================================
        // CONSOLIDAR CSV: Junta os arquivos sequencialmente
        // ================================================================================
        System.out.println("Consolidando arquivos parciais no arquivo final...");
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
}