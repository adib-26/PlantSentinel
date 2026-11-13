package com.basintelligence.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SecretsCheckTest {

    private static final String STRONG = "4f1c8e0b9a7d6e5f4c3b2a1908f7e6d5";

    @Test
    void identicalEdgeAndAgentKeysAreAlwaysRefused() {
        var security = new BasProperties.Security("same-key", "same-key", List.of());
        assertThat(SecretsCheck.problems(security, true)).singleElement().asString().contains("must be different");
    }

    @Test
    void placeholdersAndShortKeysAreRefusedOutsideTheDemo() {
        var security = new BasProperties.Security("change-me", "short", List.of(
                new BasProperties.User("admin", "change-me", List.of("ADMIN"))));
        assertThat(SecretsCheck.problems(security, false)).containsExactlyInAnyOrder(
                "BAS_EDGE_API_KEY is a placeholder",
                "BAS_AGENT_API_KEY is shorter than 24 characters",
                "the password for account 'admin' is a placeholder");
        assertThat(SecretsCheck.problems(security, true)).isEmpty();
    }

    @Test
    void strongOrMissingKeysPass() {
        assertThat(SecretsCheck.problems(new BasProperties.Security(STRONG, STRONG.toUpperCase(), List.of()), false)).isEmpty();
        // a missing key is not an error: that client class is simply refused
        assertThat(SecretsCheck.problems(new BasProperties.Security(null, "", List.of()), false)).isEmpty();
    }
}
