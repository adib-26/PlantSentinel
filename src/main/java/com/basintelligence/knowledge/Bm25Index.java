package com.basintelligence.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Immutable Okapi BM25 index over manual chunks.
 * <p>
 * Lexical retrieval was chosen over embeddings for the baseline. Maintenance manuals are dense with exact
 * technical terms (part names, fault codes, units) where keyword matching is strong. It needs no model
 * download, uses a few MB of RAM, and its rankings are easy to explain. The {@link #search} contract allows
 * a dense or hybrid retriever to replace it later.
 */
public final class Bm25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "has", "have", "if", "in", "into", "is", "it",
            "its", "of", "on", "or", "that", "the", "this", "to", "was", "were", "will", "with", "what", "why", "how",
            "when", "which", "should", "can", "do", "does", "machine", "please", "i", "we", "you", "my", "our", "me");

    public record Document(long chunkId, String text, Map<String, String> attributes) {
    }

    public record Hit(Document document, double score) {
    }

    private final List<Document> documents;
    private final List<Map<String, Integer>> termFrequencies;
    private final int[] lengths;
    private final Map<String, Integer> documentFrequency;
    private final double averageLength;

    public Bm25Index(List<Document> documents) {
        this.documents = List.copyOf(documents);
        this.termFrequencies = new ArrayList<>(documents.size());
        this.lengths = new int[documents.size()];
        this.documentFrequency = new HashMap<>();
        long total = 0;
        for (int i = 0; i < documents.size(); i++) {
            List<String> tokens = tokenize(documents.get(i).text());
            Map<String, Integer> tf = new HashMap<>();
            tokens.forEach(t -> tf.merge(t, 1, Integer::sum));
            termFrequencies.add(tf);
            lengths[i] = tokens.size();
            total += tokens.size();
            tf.keySet().forEach(t -> documentFrequency.merge(t, 1, Integer::sum));
        }
        this.averageLength = documents.isEmpty() ? 0 : (double) total / documents.size();
    }

    public static Bm25Index empty() {
        return new Bm25Index(List.of());
    }

    public int size() {
        return documents.size();
    }

    public List<Hit> search(String query, int k, Predicate<Document> filter) {
        Set<String> terms = new HashSet<>(tokenize(query));
        if (terms.isEmpty() || documents.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        int n = documents.size();
        for (int i = 0; i < n; i++) {
            Document doc = documents.get(i);
            if (!filter.test(doc)) {
                continue;
            }
            double score = 0;
            Map<String, Integer> tf = termFrequencies.get(i);
            for (String term : terms) {
                Integer f = tf.get(term);
                if (f == null) {
                    continue;
                }
                int df = documentFrequency.get(term);
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * lengths[i] / averageLength));
            }
            if (score > 0) {
                hits.add(new Hit(doc, score));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.subList(0, Math.min(k, hits.size()));
    }

    /** Lower-case alphanumeric terms without stopwords and without stemming. */
    static List<String> terms(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.length() >= 2 && !STOPWORDS.contains(raw)) {
                out.add(raw);
            }
        }
        return out;
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.length() < 2 || STOPWORDS.contains(raw)) {
                continue;
            }
            tokens.add(stem(raw));
        }
        return tokens;
    }

    /** Light suffix stripping so "bearings", "bearing" and "overheating"/"overheated" match. */
    static String stem(String word) {
        if (word.length() <= 4 || Character.isDigit(word.charAt(0))) {
            return word;
        }
        for (String suffix : new String[] {"ations", "ation", "ings", "ing", "ies", "ied", "ed", "es", "s"}) {
            if (word.endsWith(suffix) && word.length() - suffix.length() >= 3) {
                String stem = word.substring(0, word.length() - suffix.length());
                return suffix.startsWith("ie") ? stem + "y" : stem;
            }
        }
        return word;
    }
}
