package com.basintelligence.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Startup check of the secrets that come from environment variables (see {@code .env.example}). Values are never
 * logged, only which setting is wrong.
 * <p>
 * Always: the edge and agent keys must differ, otherwise one client class could act as the other.
 * Outside the {@code demo} and {@code test} profiles the application refuses to start when a machine key is shorter
 * than 24 characters or a key or seeded password is a placeholder ({@code change-me}, the demo defaults). Short
 * seeded passwords only produce a warning, because the accounts may already exist with other passwords.
 */
@Component
public class SecretsCheck {

    private static final Logger log = LoggerFactory.getLogger(SecretsCheck.class);
    static final int MIN_KEY_LENGTH = 24;
    static final int MIN_PASSWORD_LENGTH = 12;
    private static final Set<String> PLACEHOLDERS = Set.of("change-me", "changeme", "demo-edge-key-change-me",
            "demo-agent-key-change-me", "admin123", "engineer123", "reviewer123", "technician123", "viewer123", "password");

    private final BasProperties properties;
    private final Environment environment;

    public SecretsCheck(BasProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void verify() {
        boolean relaxed = Arrays.stream(environment.getActiveProfiles()).anyMatch(p -> p.equals("demo") || p.equals("test"))
                || environment.getActiveProfiles().length == 0 && Arrays.asList(environment.getDefaultProfiles()).contains("demo");
        List<String> problems = problems(properties.security(), relaxed);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Insecure secret configuration: " + String.join("; ", problems)
                    + ". Generate keys with `openssl rand -hex 32` and set them as environment variables (see .env.example).");
        }
        if (!relaxed) {
            for (BasProperties.User u : properties.security().users()) {
                if (u.password() != null && !u.password().isBlank() && u.password().length() < MIN_PASSWORD_LENGTH) {
                    log.warn("The configured password for account '{}' is shorter than {} characters", u.username(),
                            MIN_PASSWORD_LENGTH);
                }
            }
        }
    }

    static List<String> problems(BasProperties.Security security, boolean relaxed) {
        List<String> problems = new ArrayList<>();
        String edge = security.edgeApiKey();
        String agent = security.agentApiKey();
        if (present(edge) && present(agent) && edge.equals(agent)) {
            problems.add("BAS_EDGE_API_KEY and BAS_AGENT_API_KEY must be different");
        }
        if (present(agent) && agent.startsWith(com.basintelligence.common.AgentRun.TOKEN_PREFIX)) {
            problems.add("BAS_AGENT_API_KEY must not start with '" + com.basintelligence.common.AgentRun.TOKEN_PREFIX
                    + "', which marks the short-lived tokens of Kiro assistant runs");
        }
        if (relaxed) {
            return problems;
        }
        checkKey("BAS_EDGE_API_KEY", edge, problems);
        checkKey("BAS_AGENT_API_KEY", agent, problems);
        for (BasProperties.User u : security.users()) {
            if (present(u.password()) && PLACEHOLDERS.contains(u.password().strip().toLowerCase())) {
                problems.add("the password for account '" + u.username() + "' is a placeholder");
            }
        }
        return problems;
    }

    private static void checkKey(String name, String value, List<String> problems) {
        if (!present(value)) {
            return; // not configured: that client class is simply refused (fail closed)
        }
        if (PLACEHOLDERS.contains(value.strip().toLowerCase())) {
            problems.add(name + " is a placeholder");
        } else if (value.length() < MIN_KEY_LENGTH) {
            problems.add(name + " is shorter than " + MIN_KEY_LENGTH + " characters");
        }
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}
