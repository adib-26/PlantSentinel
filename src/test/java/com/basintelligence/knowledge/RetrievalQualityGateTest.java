package com.basintelligence.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Quality gate: the bundled manuals must be found by the retriever the offline demo uses (BM25). */
@SpringBootTest
@ActiveProfiles("test")
class RetrievalQualityGateTest {

    @Autowired RetrievalQualityMonitor monitor;

    @Test
    void bundledKnowledgeIsFound() {
        var r = monitor.current();
        System.out.printf("RAG benchmark: hit@1 %.2f recall@3 %.2f MRR %.2f misses %s%n", r.hitAt1(), r.recallAt3(),
                r.meanReciprocalRank(), r.misses());
        assertThat(r.questions()).isEqualTo(22);
        // Measured on the bundled manuals with BM25 (Nov 2026): hit@1 0.59, recall@3 0.86, MRR 0.72. The floors sit
        // just below, so a regression in chunking, tokenising or ranking fails the build.
        assertThat(r.recallAt3()).isGreaterThanOrEqualTo(0.80);
        assertThat(r.meanReciprocalRank()).isGreaterThanOrEqualTo(0.65);
    }
}
