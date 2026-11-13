package com.basintelligence.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextChunkerTest {

    @Test
    void markdownHeadingsBecomeSections() {
        var chunks = new TextChunker(500).chunk("""
                # Pump manual

                ## 1. Cavitation
                Crackling noise and pitting.

                ## 2. Bearing wear
                Rising vibration and temperature.
                """);

        assertThat(chunks).extracting(TextChunker.Chunk::section).containsExactly("1. Cavitation", "2. Bearing wear");
        assertThat(chunks.get(1).content()).contains("Rising vibration");
    }

    @Test
    void pdfStyleHeadingsAreDetected() {
        var chunks = new TextChunker(500).chunk("""
                3.2 Lubrication intervals

                Re-grease every 2000 hours.

                TROUBLESHOOTING

                If the fan fails, stop the unit.
                """);

        assertThat(chunks).extracting(TextChunker.Chunk::section).containsExactly("3.2 Lubrication intervals", "TROUBLESHOOTING");
    }

    @Test
    void pdfPageMarkersBecomePageRanges() {
        char f = DocumentTextExtractor.PAGE_BREAK;
        var chunks = new TextChunker(80).chunk(f + "## 1. Lubrication\n\nGrease every 2000 h." + f
                + "Check the oil level weekly.\n\n## 2. Bearings\n\nReplace as a matched set." + f + "Record a baseline.");

        assertThat(chunks).extracting(TextChunker.Chunk::section).containsExactly("1. Lubrication", "2. Bearings");
        assertThat(chunks.get(0).pageStart()).isEqualTo(1);
        assertThat(chunks.get(0).pageEnd()).isEqualTo(2);
        assertThat(chunks.get(1).pageStart()).isEqualTo(2);
        assertThat(chunks.get(1).pageEnd()).isEqualTo(3);
        assertThat(new TextChunker(80).chunk("No pages here.").getFirst().pageStart()).isNull();
    }

    @Test
    void longTextIsSplitWithinTheLimit() {
        String sentence = "The bearing temperature must stay below the alarm limit at all times. ";
        var chunks = new TextChunker(200).chunk(sentence.repeat(20));

        assertThat(chunks).hasSizeGreaterThan(5);
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(200));
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
        }
    }
}
