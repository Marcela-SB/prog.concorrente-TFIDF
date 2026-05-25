package java_versions;

public class AAA {
    private static final String FILE_PATH = "corpus_grande.txt";
    private static final String RESULT_FILE = "resultados/java_v2_resultado_tfidf_geral.csv";

    private record Segment(long start, int size) {}

    private static class IdfResult {
        byte[] word;
        int docCount;
    }

    private static class TfidfResult {
        byte[] word;
        double tfidf;
    }
}
