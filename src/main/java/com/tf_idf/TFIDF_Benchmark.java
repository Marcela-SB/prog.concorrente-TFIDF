package com.tf_idf;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
//@BenchmarkMode(Mode.Throughput)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 3, time = 5)
@Fork(1)
public class TFIDF_Benchmark {

    private List<String> linhas;
    private v1_serial v1;
    private v2_plataform_threads v2;
    private v3_virtual_threads v3;
    private v4_plataform_virtual_threads v4;

    @Param({"1", "2", "4", "8", "16"})
    public int numThreads;

    @Setup
    public void setup() throws IOException {
        // Carrega o arquivo para a memória apenas uma vez
        linhas = Files.readAllLines(Paths.get("corpus_grande.txt"));

        v1 = new v1_serial();
        v2 = new v2_plataform_threads();
        v3 = new v3_virtual_threads();
        v4 = new v4_plataform_virtual_threads();
    }
    

    @Benchmark
    public void testV1_Serial(Blackhole bh) {
        v1.executarBenchmark(linhas); // Chama o método da sua classe serial
        bh.consume(true);
    }

    @Benchmark
    public void testV2_PlataformThreads(Blackhole blackhole) {
        var resultado = v2.executarBenchmark(linhas, numThreads); 
        blackhole.consume(resultado);
    }

    @Benchmark
    public void testV3_VirtualThreads(Blackhole blackhole) {
        var resultado = v3.executarBenchmark(linhas, numThreads); 
        blackhole.consume(resultado);
    }

    @Benchmark
    public void testV4_PlataformVirtualThreads(Blackhole blackhole) throws InterruptedException {
        var resultado = v4.executarBenchmark(linhas, numThreads); 
        blackhole.consume(resultado);
    }
}