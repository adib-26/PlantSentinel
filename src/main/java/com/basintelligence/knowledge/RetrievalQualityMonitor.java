package com.basintelligence.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.basintelligence.common.BasMetrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publishes retrieval quality of the current manual library as gauges ({@code bas.rag.eval.*}). The benchmark
 * (about 20 searches) runs again only when the library changed, the first time a gauge is read after the change;
 * reading metrics never touches the database otherwise.
 * <p>
 * The labelled questions cover the bundled manuals. Uploaded manuals are searched too, but no question tests them,
 * so the score says how well the bundled knowledge is still found, not how good every document is.
 */
@Component
public class RetrievalQualityMonitor {

    private static final Logger log = LoggerFactory.getLogger(RetrievalQualityMonitor.class);

    private record Cached(long version, RetrievalBenchmark.Result result) {
    }

    private final ManualService manuals;
    private final List<RetrievalBenchmark.Question> questions;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public RetrievalQualityMonitor(ManualService manuals, MeterRegistry meters) throws IOException {
        this.manuals = manuals;
        try (InputStream in = new ClassPathResource("rag/golden-queries.tsv").getInputStream()) {
            this.questions = RetrievalBenchmark.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        Gauge.builder(BasMetrics.RAG_EVAL_HIT_AT_1, this, m -> m.current().hitAt1())
                .description("Share of labelled questions whose best passage is from the expected section").register(meters);
        Gauge.builder(BasMetrics.RAG_EVAL_RECALL_AT_3, this, m -> m.current().recallAt3())
                .description("Share of labelled questions with the expected section in the top 3").register(meters);
        Gauge.builder(BasMetrics.RAG_EVAL_MRR, this, m -> m.current().meanReciprocalRank())
                .description("Mean reciprocal rank of the expected section (top 5)").register(meters);
    }

    /** The benchmark for the library as it is now (recomputed after uploads, deletions and index rebuilds). */
    public RetrievalBenchmark.Result current() {
        long version = manuals.libraryVersion();
        Cached cached = cache.get();
        if (cached != null && cached.version() == version) {
            return cached.result();
        }
        try {
            var result = RetrievalBenchmark.evaluate(questions,
                    q -> manuals.searchUnmetered(q.text(), q.machineType(), RetrievalBenchmark.DEPTH));
            cache.set(new Cached(version, result));
            log.info("Retrieval benchmark ({} questions): hit@1 {}, recall@3 {}, MRR {}", result.questions(),
                    String.format("%.2f", result.hitAt1()), String.format("%.2f", result.recallAt3()),
                    String.format("%.2f", result.meanReciprocalRank()));
            return result;
        } catch (RuntimeException e) {
            log.warn("Retrieval benchmark failed: {}", e.getMessage());
            return cached == null ? RetrievalBenchmark.Result.EMPTY : cached.result();
        }
    }

    public int questionCount() {
        return questions.size();
    }
}
