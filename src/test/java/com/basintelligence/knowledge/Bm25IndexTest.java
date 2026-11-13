package com.basintelligence.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class Bm25IndexTest {

    private final Bm25Index index = new Bm25Index(List.of(
            doc(1, "Bearing wear: vibration rises gradually, then temperature. Re-grease the bearings.", "CNC_MILL"),
            doc(2, "Cooling failure: clean the cooler fins and check the fan when temperature climbs.", "AIR_COMPRESSOR"),
            doc(3, "Leak check: inspect seals, hoses and fittings when pressure drops.", ""),
            doc(4, "Preventive maintenance schedule and routine inspection intervals.", "")));

    @Test
    void ranksTheMostRelevantPassageFirst() {
        var hits = index.search("why is the bearing vibrating", 3, d -> true);

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().document().chunkId()).isEqualTo(1);
    }

    @Test
    void stemmingMatchesInflectedForms() {
        assertThat(index.search("leaking hose", 1, d -> true).getFirst().document().chunkId()).isEqualTo(3);
        assertThat(Bm25Index.stem("bearings")).isEqualTo(Bm25Index.stem("bearing"));
        assertThat(Bm25Index.stem("overheating")).isEqualTo(Bm25Index.stem("overheated"));
    }

    @Test
    void filterRestrictsResults() {
        var hits = index.search("temperature", 5, d -> !d.attributes().get("type").equals("CNC_MILL"));

        assertThat(hits).extracting(h -> h.document().chunkId()).containsExactly(2L);
    }

    @Test
    void stopwordOnlyQueryReturnsNothing() {
        assertThat(index.search("what is the", 5, d -> true)).isEmpty();
    }

    private static Bm25Index.Document doc(long id, String text, String type) {
        return new Bm25Index.Document(id, text, Map.of("type", type));
    }
}
