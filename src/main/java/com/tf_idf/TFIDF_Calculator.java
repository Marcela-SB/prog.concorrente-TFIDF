package com.tf_idf;

public class TFIDF_Calculator {
    // Calcula o score TF-IDF de um termo
    public double calcularScore(int frequenciaTermo, int totalPalavrasLinha, long totalDocs, int docsComTermo) {
        double tf = (double) frequenciaTermo / totalPalavrasLinha;
        double idf = Math.log((double) totalDocs / docsComTermo);
        return tf * idf;
    }
}