package com.tf_idf.MOTOR;

import java.io.*;
import java.util.*;

import com.tf_idf.TFIDF_Calculator;

public class Motor_Serial {
    private final String filePath;
    private final TFIDF_Calculator calculator;
    private long totalDocs = 0;

    public Motor_Serial(String filePath) {
        this.filePath = filePath;
        this.calculator = new TFIDF_Calculator();
    }

    public void processar(String outputPath) {
        Map<String, Integer> docsWithTerm = calcularIDF();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath));
             BufferedWriter bw = new BufferedWriter(new FileWriter(outputPath))) {
            
            String row;
            long lineNum = 0;
            while ((row = br.readLine()) != null) {
                lineNum++;
                processarLinha(row, lineNum, docsWithTerm, totalDocs, bw);
            }
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    //--------AUXILIARES--------
    private Map<String, Integer> calcularIDF() { 
        Map<String, Integer> documentsWithTerm = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String row;
            while ((row = br.readLine()) != null) {
                totalDocs++;
                
                StringTokenizer st = new StringTokenizer(row.toLowerCase());
                Set<String> unicTermsInDoc = new HashSet<>();
                
                while (st.hasMoreTokens()) {
                    unicTermsInDoc.add(st.nextToken());
                }

                for (String term : unicTermsInDoc) {
                    documentsWithTerm.put(term, documentsWithTerm.getOrDefault(term, 0) + 1);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return documentsWithTerm;
    }

    private void processarLinha(String row, long lineNum, Map<String, Integer> docsWithTerm, long totalDocs, BufferedWriter bw) throws IOException {
        StringTokenizer st = new StringTokenizer(row.toLowerCase());
        
        if (!st.hasMoreTokens()) return;

        Map<String, Integer> freq = new HashMap<>();
        int wordCount = 0;

        while (st.hasMoreTokens()) {
            String term = st.nextToken();
            freq.put(term, freq.getOrDefault(term, 0) + 1);
            wordCount++;
        }

        for (Map.Entry<String, Integer> entry : freq.entrySet()) {
            String term = entry.getKey();
            double score = calculator.calcularScore(entry.getValue(), wordCount, totalDocs, docsWithTerm.get(term));
            bw.write(String.format("%d;%s;%.10f\n", lineNum, term, score));
        }
    }
}