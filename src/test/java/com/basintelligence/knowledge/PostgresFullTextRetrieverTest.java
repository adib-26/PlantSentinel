package com.basintelligence.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostgresFullTextRetrieverTest {

    @Test
    void buildsAnOrQueryFromSafeTermsOnly() {
        assertThat(PostgresFullTextRetriever.toTsQuery("Why is the bearing vibrating? bearing!"))
                .isEqualTo("bearing | vibrating");
    }

    @Test
    void stripsTsQueryOperatorsAndInjectionAttempts() {
        assertThat(PostgresFullTextRetriever.toTsQuery("pump' & !seal | (x:*) ; DROP TABLE documents --"))
                .isEqualTo("pump | seal | drop | table | documents");
    }

    @Test
    void emptyWhenOnlyStopwords() {
        assertThat(PostgresFullTextRetriever.toTsQuery("what is the")).isEmpty();
    }
}
