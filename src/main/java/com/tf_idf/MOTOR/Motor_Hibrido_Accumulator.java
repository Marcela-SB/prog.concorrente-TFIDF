package com.tf_idf.MOTOR;

import java.io.*;
import java.nio.channels.Channels;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.function.Consumer;

import com.tf_idf.TFIDF_Calculator;
import com.tf_idf.AUXILIARES.Segmentador.Segment;

public class Motor_Hibrido_Accumulator {
    // Tamanho do bloco para divisão do arquivo (10 MB).
    private static final long CHUNK_SIZE = 10 * 1024 * 1024;
    
    private final String filePath;
    private final TFIDF_Calculator calculator = new TFIDF_Calculator();
    private final int cores;

    public Motor_Hibrido_Accumulator(String filePath, int cores) {
        this.filePath = filePath;
        this.cores = cores;
    }
    
    private final LongAccumulator totalDocuments = new LongAccumulator(Long::sum, 0L);

    public void processar(String outputPath, List<Segment> segments) {
    //public void processar(List<Segment> segments, Consumer<String> resultConsumer) {
        // 1. PASSO: Calcular IDF (Global)
        Map<String, Integer>[] results = new HashMap[segments.size()];

        try (var ioExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < segments.size(); i++) {
                final int index = i; // Precisa ser final para o lambda
                final Segment seg = segments.get(i);
                
                ioExecutor.submit(() -> {
                    // Cada thread popula o seu índice exclusivo
                    results[index] = calcularIDFParcialConsolidado(seg, totalDocuments);
                });
            }
        } 

        // 2. CONSOLIDAÇÃO (Reduced Map)
        Map<String, Integer> idfMap = new HashMap<>();
        for (Map<String, Integer> mapParcial : results) {
            if (mapParcial != null) {
                mapParcial.forEach((k, v) -> idfMap.merge(k, v, Integer::sum));
            }
        }

        // 2. PASSO: Calcular TF-IDF (Score Final)
        //System.out.println("Calculando TF-IDF...");
        ExecutorService cpuPool = Executors.newFixedThreadPool(cores);
        try (var ioExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Segment seg : segments) {
                ioExecutor.submit(() -> {
                    List<String> linhas = lerSegmento(seg);
                    cpuPool.submit(() -> processarLinhas(linhas, idfMap, totalDocuments, 
                    outputPath + ".part" + seg.id()
                    //resultConsumer
                    )  
                );
                });
            }
        } finally {
            cpuPool.shutdown();
            try {
                if (!cpuPool.awaitTermination(10, TimeUnit.MINUTES)) {
                    System.err.println("Timeout: Algumas tarefas de processamento não terminaram!");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Processamento interrompido!");
            }
        }
        
        //System.out.println("Quantidade de segmentos: " + segments.size());
        mergePartFiles(segments.size(), outputPath);
    }

    private Map<String, Integer> calcularIDFParcialConsolidado(Segment seg, LongAccumulator totalDocs) {
        Map<String, Integer> localMap = new HashMap<>();
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            raf.seek(seg.start());
            try (var reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(raf.getChannel())))) {
                String row;
                long bytesRead = 0;
                while (bytesRead < seg.size() && (row = reader.readLine()) != null) {
                    bytesRead += row.getBytes().length + 1;
                    totalDocuments.accumulate(1);
                    
                    StringTokenizer st = new StringTokenizer(row.toLowerCase());
                    Set<String> uniqueTerms = new HashSet<>();

                    while (st.hasMoreTokens()) {
                        String token = st.nextToken();
                        if (uniqueTerms.add(token)) { // O set já garante que é único
                            localMap.merge(token, 1, Integer::sum);
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return localMap;
    }

    private List<String> lerSegmento(Segment seg) {
        List<String> linhas = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            raf.seek(seg.start());
            try (var reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(raf.getChannel())))) {
                String row;
                long bytesRead = 0;
                while (bytesRead < seg.size() && (row = reader.readLine()) != null) {
                    bytesRead += row.getBytes().length + 1;
                    linhas.add(row);
                }
            }
        } catch (IOException e) { e.printStackTrace(); }
        return linhas;
    }

    private void processarLinhas(List<String> linhas, Map<String, Integer> idf, LongAccumulator totalDocs, String out) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(out))) {
            Map<String, Integer> freq = new HashMap<>(128);
            for (String row : linhas) {
                freq.clear();
                freq = contarFrequencias(row);
                
                // Contagem de palavras usando StringTokenizer para evitar split
                int totalPalavrasNaLinha = 0;
                StringTokenizer st = new StringTokenizer(row.toLowerCase());
                while (st.hasMoreTokens()) {
                    st.nextToken();
                    totalPalavrasNaLinha++;
                }

                for (Map.Entry<String, Integer> entry : freq.entrySet()) {
                    String termo = entry.getKey();
                    Integer docFreq = idf.get(termo);
                    if (docFreq != null) {
                        double score = calculator.calcularScore(entry.getValue(), totalPalavrasNaLinha, totalDocs.get(), docFreq);
                        bw.write(String.format(Locale.US, "%s;%.10f\n", termo, score));
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

    private static void mergePartFiles(int totalSegments, String outputPath) {
        new File(outputPath).getParentFile().mkdirs();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputPath))) {
            bw.write("Palavra;TF-IDF\n");

            for (int i = 0; i < totalSegments; i++) {
                File partFile = new File(outputPath + ".part" + i);
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
            e.printStackTrace();
        }
    }
}