package com.tf_idf.AUXILIARES;

import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

public class Segmentador {
    public record Segment(int id, long start, int size) {}

    public static List<Segment> gerarSegmentos(String filePath, long chunkSize) {
        try (RandomAccessFile file = new RandomAccessFile(filePath, "r")) {
            long totalSize = file.length();
            List<Segment> segments = new ArrayList<>();
            long filePos = 0;
            int id = 0;

            while (filePos < totalSize - chunkSize) {
                file.seek(filePos + chunkSize);
                // Ajusta para o próximo início de linha
                while (file.getFilePointer() < totalSize && file.read() != '\n');
                
                long end = file.getFilePointer();
                segments.add(new Segment(id++, filePos, (int) (end - filePos)));
                filePos = end;
            }
            if (filePos < totalSize) {
                segments.add(new Segment(id, filePos, (int) (totalSize - filePos)));
            }
            return segments;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao segmentar arquivo", e);
        }
    }
}