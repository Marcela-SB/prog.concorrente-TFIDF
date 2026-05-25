package com.tf_idf;

import java.util.List;

import com.tf_idf.MOTOR.Motor_Platform;

public class v2_platform_threads {
    public static void main(String[] args) {
        String path = "corpus_grande.txt";
        String output = "resultados/v2_resultado.csv";

        int cores = Runtime.getRuntime().availableProcessors();
        // Instancia o motor e executa
        Motor_Platform motor = new Motor_Platform(path, cores);
        List<com.tf_idf.AUXILIARES.Segmentador.Segment> segmentos = motor.getSegments();
        
        // Para testar com a escrita (precisa ajustar código motor)
        motor.processar(output, segmentos);
        
        //System.out.println("Processamento concluído.");
    }
}