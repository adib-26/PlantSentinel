package com.basintelligence.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import com.basintelligence.machine.MachineType;

/**
 * Retrieval quality against labelled questions: for each question, is a passage from the expected manual section
 * among the results, and at which rank? Reports hit@1, recall@3 and mean reciprocal rank over the top
 * {@value #DEPTH}. Plain class; {@link RetrievalQualityMonitor} runs it on the live library.
 */
public final class RetrievalBenchmark {

    public static final int DEPTH = 5;

    /** {@code expectedSection} is matched case-insensitively against the section titles of the results. */
    public record Question(MachineType machineType, String expectedSection, String text) {
    }

    public record Result(int questions, double hitAt1, double recallAt3, double meanReciprocalRank, List<String> misses) {
        public static final Result EMPTY = new Result(0, 0, 0, 0, List.of());
    }

    /** Parses the TSV format of {@code rag/golden-queries.tsv}; lines starting with # are comments. */
    public static List<Question> parse(String tsv) {
        List<Question> out = new ArrayList<>();
        for (String line : tsv.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t");
            if (f.length != 3) {
                throw new IllegalArgumentException("Expected 3 tab-separated fields: " + line);
            }
            out.add(new Question(f[0].equals("-") ? null : MachineType.valueOf(f[0].strip()), f[1].strip(), f[2].strip()));
        }
        return out;
    }

    public static Result evaluate(List<Question> questions, Function<Question, List<ManualHit>> search) {
        if (questions.isEmpty()) {
            return Result.EMPTY;
        }
        int hit1 = 0;
        int hit3 = 0;
        double reciprocal = 0;
        List<String> misses = new ArrayList<>();
        for (Question q : questions) {
            List<ManualHit> hits = search.apply(q);
            int rank = 0;
            for (int i = 0; i < Math.min(DEPTH, hits.size()); i++) {
                String section = hits.get(i).section();
                if (section != null && section.toLowerCase(Locale.ROOT).contains(q.expectedSection().toLowerCase(Locale.ROOT))) {
                    rank = i + 1;
                    break;
                }
            }
            if (rank == 1) {
                hit1++;
            }
            if (rank >= 1 && rank <= 3) {
                hit3++;
            }
            if (rank > 0) {
                reciprocal += 1.0 / rank;
            } else {
                misses.add(q.text());
            }
        }
        int n = questions.size();
        return new Result(n, (double) hit1 / n, (double) hit3 / n, reciprocal / n, misses);
    }
}
