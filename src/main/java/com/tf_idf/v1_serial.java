package com.tf_idf;

import com.tf_idf.MOTOR.Motor_Serial;

public class v1_serial {
    public static void main(String[] args) {
        String path = "corpus_grande.txt";
        String output = "resultados/v1_resultado.csv";

        // Instancia o motor e executa
        Motor_Serial motor = new Motor_Serial(path);
        motor.processar(output);
        
        //System.out.println("Processamento concluído.");
    }
}