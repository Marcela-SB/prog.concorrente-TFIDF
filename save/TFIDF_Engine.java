package com.tf_idf;

import java.util.*;

public class TFIDF_Engine {

    public Map<String, Integer> calcularFrequenciaDocumental(List<String> subLista) {
        Map<String, Integer> localMap = new HashMap<>();
        for (String row : subLista) {
            StringTokenizer st = new StringTokenizer(row.toLowerCase());
            Set<String> uniqueTerms = new HashSet<>();
            while (st.hasMoreTokens()) uniqueTerms.add(st.nextToken());
            
            for (String term : uniqueTerms) {
                localMap.merge(term, 1, Integer::sum);
            }
        }
        return localMap;
    }

    public double calcularChecksumTFIDF(List<String> subLista, Map<String, Integer> globalIDF, int totalDocs) {
        double checksum = 0.0;
        Map<String, Integer> freq = new HashMap<>(64);
        
        for (String row : subLista) {
            freq.clear();
            StringTokenizer st = new StringTokenizer(row.toLowerCase());
            int wordCount = 0;
            while (st.hasMoreTokens()) {
                freq.merge(st.nextToken(), 1, Integer::sum);
                wordCount++;
            }
            
            for (Map.Entry<String, Integer> entry : freq.entrySet()) {
                double tf = (double) entry.getValue() / wordCount;
                double idf = Math.log((double) totalDocs / globalIDF.get(entry.getKey()));
                checksum += (tf * idf);
            }
        }
        return checksum;
    }
}