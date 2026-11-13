package com.basintelligence.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.basintelligence.machine.MachineType;

class RetrievalBenchmarkTest {

    private static ManualHit hit(String section) {
        return new ManualHit(1, "Doc", section, "text", null, 1.0);
    }

    @Test
    void computesHitAt1RecallAt3AndMeanReciprocalRank() {
        var questions = RetrievalBenchmark.parse("""
                # comment
                CNC_MILL\tbearing\tq1
                -\tcavitation\tq2
                CONVEYOR\tgearbox\tq3
                """);
        assertThat(questions).hasSize(3);
        assertThat(questions.get(1).machineType()).isNull();
        Map<String, List<ManualHit>> results = Map.of(
                "q1", List.of(hit("3. Spindle Bearing failure"), hit("other")),          // rank 1
                "q2", List.of(hit("a"), hit("b"), hit("3. Cavitation")),                  // rank 3
                "q3", List.of(hit("a"), hit("b"), hit("c"), hit("d"), hit("e")));          // miss
        var r = RetrievalBenchmark.evaluate(questions, q -> results.get(q.text()));
        assertThat(r.hitAt1()).isEqualTo(1.0 / 3);
        assertThat(r.recallAt3()).isEqualTo(2.0 / 3);
        assertThat(r.meanReciprocalRank()).isCloseTo((1 + 1.0 / 3) / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.misses()).containsExactly("q3");
        assertThat(questions.getFirst().machineType()).isEqualTo(MachineType.CNC_MILL);
    }
}
