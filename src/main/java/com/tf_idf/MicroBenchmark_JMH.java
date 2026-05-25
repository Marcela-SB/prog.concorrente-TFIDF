package com.tf_idf;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode({Mode.AverageTime, Mode.Throughput}) // Mede o tempo médio de execução
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1) // Garante que o JIT já otimizou o código antes de medir
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MicroBenchmark_JMH {

    private TFIDF_Calculator calculator;
    private String sampleRow;

    @Setup
    public void setup() {
        calculator = new TFIDF_Calculator();
        sampleRow = "O rato roeu a roupa do rei de roma e o rei de roma roeu a roupa do rato";
    }

    // 1. Benchmark para a Lógica Matemática
    @Benchmark
    public void benchmarkCalcularScore(Blackhole bh) {
        bh.consume(calculator.calcularScore(5, 50, 1000000, 500));
    }

    // 2. Benchmark para a Lógica de Manipulação de Texto
    @Benchmark
    public void benchmarkContarFrequenciasTokenizer(Blackhole bh) {
        bh.consume(contarFrequencias_token(sampleRow));
    }

    @Benchmark
    public void benchmarkContarFrequenciasSplit(Blackhole bh) {
        bh.consume(contarFrequencias_split(sampleRow));
    }

    public Map<String, Integer> contarFrequencias_token(String row) {
        Map<String, Integer> freq = new HashMap<>();
        StringTokenizer st = new StringTokenizer(row.toLowerCase());
        while (st.hasMoreTokens()) {
            freq.merge(st.nextToken(), 1, Integer::sum);
        }
        return freq;
    }

    public Map<String, Integer> contarFrequencias_split(String row) {
        Map<String, Integer> freq = new HashMap<>();
        String[] words = row.toLowerCase().split("\\s+");
        for (String w : words) if (!w.isEmpty()) freq.merge(w, 1, Integer::sum);
        return freq;
    }
}