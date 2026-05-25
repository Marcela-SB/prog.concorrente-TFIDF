package com.tf_idf.MOTOR;

import java.io.*;
import java.nio.channels.Channels;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import com.tf_idf.TFIDF_Calculator;
import com.tf_idf.AUXILIARES.Segmentador.Segment;

public class Motor_Hibrido {
    // Tamanho do bloco para divisão do arquivo (10 MB).
    private static final long CHUNK_SIZE = 10 * 1024 * 1024;
    
    private final String filePath;
    private final TFIDF_Calculator calculator = new TFIDF_Calculator();
    private final int cores;

    public Motor_Hibrido(String filePath, int cores) {
        this.filePath = filePath;
        this.cores = cores;
    }
    
    private static class ThreadResult {
        long docCount = 0;
        Map<String, Integer> localDocumentsWithTerm = new HashMap<>();
    }

    public void processar(String outputPath, List<Segment> segments) {
    //public void processar(List<Segment> segments, Consumer<String> resultConsumer) {
        // 1. PASSO: Calcular IDF (Global)
        ThreadResult[] threadResults = new ThreadResult[segments.size()];

        try (var ioExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < segments.size(); i++) {
                final int index = i;
                final Segment seg = segments.get(i);
                ioExecutor.submit(() -> {
                    threadResults[index] = calcularIDFParcialConsolidado(seg);
                });
            }
        } 

        // CONSOLIDAÇÃO
        Map<String, Integer> idfMap = new HashMap<>();
        long totalDocuments = 0;
        for (ThreadResult tr : threadResults) {
            totalDocuments += tr.docCount;
            tr.localDocumentsWithTerm.forEach((k, v) ->
                idfMap.merge(k, v, Integer::sum));
        }

        // 2. PASSO: Calcular TF-IDF (Score Final)
        //System.out.println("Calculando TF-IDF...");
        long totalDocumentsCount = totalDocuments;
        ExecutorService cpuPool = Executors.newFixedThreadPool(cores);
        try (var ioExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Segment seg : segments) {
                ioExecutor.submit(() -> {
                    List<String> linhas = lerSegmento(seg);
                    cpuPool.submit(() -> processarLinhas(linhas, idfMap, totalDocumentsCount, 
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

    private ThreadResult calcularIDFParcialConsolidado(Segment seg) {
        ThreadResult result = new ThreadResult();
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            raf.seek(seg.start());
            try (var reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(raf.getChannel())))) {
                String row;
                long bytesRead = 0;
                while (bytesRead < seg.size() && (row = reader.readLine()) != null) {
                    bytesRead += row.getBytes().length + 1;
                    result.docCount++;
                    
                    String[] words = row.toLowerCase().split("\\s+");
                    Set<String> uniqueTerms = new HashSet<>(Arrays.asList(words));
                    
                    for (String p : uniqueTerms) {
                        if (!p.isEmpty()) {
                            result.localDocumentsWithTerm.merge(p, 1, Integer::sum);
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
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

    private void processarLinhas(List<String> linhas, Map<String, Integer> idf, long totalDocs, String out) {
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
                        double score = calculator.calcularScore(entry.getValue(), totalPalavrasNaLinha, totalDocs, docFreq);
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





    // TESTES
    // Este método recebe os dados já em memória, eliminando o I/O da medição
    // public void processarEmMemoria(List<List<String>> segmentosEmMemoria, Consumer<String> resultConsumer) {
    //     // 1. PASSO: Calcular IDF (Global) a partir dos dados em memória
    //     Map<String, Integer> idfMap = new ConcurrentHashMap<>();
    //     AtomicInteger totalDocs = new AtomicInteger(0);

    //     for (List<String> segmento : segmentosEmMemoria) {
    //         totalDocs.addAndGet(segmento.size());
    //         for (String row : segmento) {
    //             Set<String> palavrasUnicas = new HashSet<>(Arrays.asList(row.toLowerCase().split("\\s+")));
    //             for (String p : palavrasUnicas) {
    //                 if (!p.isEmpty()) idfMap.merge(p, 1, Integer::sum);
    //             }
    //         }
    //     }

    //     // 2. PASSO: Calcular TF-IDF (Score Final)
    //     ExecutorService cpuPool = Executors.newFixedThreadPool(cores);
    //     try {
    //         for (List<String> segmento : segmentosEmMemoria) {
    //             cpuPool.submit(() -> processarLinhas(segmento, idfMap, totalDocs.get(), resultConsumer));
    //         }
    //     } finally {
    //         cpuPool.shutdown();
    //         try {
    //             cpuPool.awaitTermination(10, TimeUnit.MINUTES);
    //         } catch (InterruptedException e) {
    //             Thread.currentThread().interrupt();
    //         }
    //     }
    // }



//     // Para JMH
//     static class Result {
//         double checksum = 0.0;
//         long docCount = 0;
//         Map<String, Integer> map = new HashMap<>();
//     }
//     public double executarBenchmark(List<String> linhas, int numTasks, TFIDF_Calculator calculator) throws InterruptedException {
//     int totalLinhas = linhas.size();
//     int chunkSize = totalLinhas / numTasks;
//     Result[] results = new Result[numTasks];
//     Thread[] workers = new Thread[numTasks];

//     // --- PASSO 1: IDF ---
//     for (int i = 0; i < numTasks; i++) {
//         final int idx = i;
//         int start = i * chunkSize;
//         int end = (i == numTasks - 1) ? totalLinhas : (start + chunkSize);
//         List<String> subList = linhas.subList(start, end);
//         results[idx] = new Result();

//         workers[idx] = Thread.ofVirtual().start(() -> {
//             for (String row : subList) {
//                 StringTokenizer st = new StringTokenizer(row.toLowerCase());
//                 Set<String> uniqueTerms = new HashSet<>();
//                 while (st.hasMoreTokens()) uniqueTerms.add(st.nextToken());
                
//                 for (String term : uniqueTerms) {
//                     results[idx].map.merge(term, 1, Integer::sum);
//                 }
//             }
//             results[idx].docCount = subList.size();
//         });
//     }
//     for (Thread w : workers) w.join();

//     Map<String, Integer> globalMap = new HashMap<>();
//     long totalDocs = 0;
//     for (Result r : results) {
//         totalDocs += r.docCount;
//         r.map.forEach((k, v) -> globalMap.merge(k, v, Integer::sum));
//     }

//     // --- PASSO 2: TF-IDF ---
//     for (int i = 0; i < numTasks; i++) {
//         final int idx = i;
//         int start = i * chunkSize;
//         int end = (i == numTasks - 1) ? totalLinhas : (start + chunkSize);
//         List<String> subList = linhas.subList(start, end);
//         long total = totalDocs;

//         workers[idx] = Thread.ofPlatform().start(() -> {
//             double localChecksum = 0.0;
//             for (String row : subList) {
//                 String[] words = row.toLowerCase().split("\\s+");
//                 Map<String, Integer> freq = new HashMap<>();
//                 for (String w : words) if (!w.isEmpty()) freq.merge(w, 1, Integer::sum);
                
//                 int wCount = words.length;
//                 for (var entry : freq.entrySet()) {
//                     // Chamando o seu calculador original
//                     double score = calculator.calcularScore(entry.getValue(), wCount, total, globalMap.get(entry.getKey()));
//                     localChecksum += score;
//                 }
//             }
//             results[idx].checksum = localChecksum;
//         });
//     }
//     for (Thread w : workers) w.join();

//     return Arrays.stream(results).mapToDouble(r -> r.checksum).sum();
// }
}