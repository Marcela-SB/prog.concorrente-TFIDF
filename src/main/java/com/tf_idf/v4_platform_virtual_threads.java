package com.tf_idf;

import java.util.List;

import com.tf_idf.AUXILIARES.Segmentador.Segment;
import com.tf_idf.MOTOR.Motor_Hibrido;

public class v4_platform_virtual_threads {
    public static void main(String[] args) {
        String path = "corpus_grande.txt";
        String output = "resultados/v4_resultado.csv";

        int cores = Runtime.getRuntime().availableProcessors();
        // Instancia o motor e executa
        Motor_Hibrido motor = new Motor_Hibrido(path, cores);
        List<Segment> segmentos = motor.getSegments();
        
        // Para testar com a escrita (precisa ajustar código motor)
        motor.processar(output, segmentos);
        
        //System.out.println("Processamento concluído.");
    }
}