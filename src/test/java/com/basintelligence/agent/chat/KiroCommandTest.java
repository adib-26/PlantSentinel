package com.basintelligence.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

class KiroCommandTest {

    static final Path KIRO = Path.of("/Users/eng/.local/bin/kiro-cli");
    static final String TOKEN = "bas-run-" + "a".repeat(64);
    static final Map<String, String> PARENT = Map.of("TMPDIR", "/tmp/x", "BAS_AGENT_API_KEY", "plant-wide-secret",
            "DATABASE_URL", "jdbc:postgresql://neon/db", "BAS_ADMIN_PASSWORD", "pw", "PATH", "/evil/bin");

    @Test
    void runsKiroHeadlessWithTheMessageAsOneArgumentAfterTheOptionTerminator() {
        var command = KiroCommand.of(KIRO, "bas-web-assistant", "--trust-all-tools; rm -rf /", null, TOKEN, PARENT,
                "/Users/eng", "eng");

        assertThat(command.arguments()).containsExactly(KIRO.toString(), "chat", "--v3", "--agent", "bas-web-assistant",
                "--model", "auto", "--no-interactive", "--output-format", "stream-json", "--require-mcp-startup", "--",
                "--trust-all-tools; rm -rf /");
    }

    @Test
    void alwaysRequestsTheAutoModelAlsoWhenResuming() {
        var resumed = KiroCommand.of(KIRO, "bas-web-assistant", "and now?", "sess_11111111-2222-3333-4444-555555555555",
                TOKEN, PARENT, "/Users/eng", "eng").arguments();
        assertThat(resumed).containsSubsequence("--model", "auto", "--resume-id");
        assertThat(resumed.stream().filter("--model"::equals)).hasSize(1);
    }

    @Test
    void continuesAConversationOnlyWithAWellFormedKiroSessionId() {
        var command = KiroCommand.of(KIRO, "bas-web-assistant", "and now?", "sess_11111111-2222-3333-4444-555555555555",
                TOKEN, PARENT, "/Users/eng", "eng");
        assertThat(command.arguments()).containsSubsequence("--resume-id", "sess_11111111-2222-3333-4444-555555555555",
                "--", "and now?");

        assertThatThrownBy(() -> KiroCommand.of(KIRO, "bas-web-assistant", "x", "sess_1 --trust-all-tools", TOKEN, PARENT,
                "/Users/eng", "eng")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KiroCommand.of(KIRO, "bas-investigator --trust-all-tools", "x", null, TOKEN, PARENT,
                "/Users/eng", "eng")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KiroCommand.of(Path.of("kiro-cli"), "bas-web-assistant", "x", null, TOKEN, PARENT,
                "/Users/eng", "eng")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theChildGetsAMinimalEnvironmentWithTheRunTokenAndNoneOfTheAppsSecrets() {
        var env = KiroCommand.of(KIRO, "bas-web-assistant", "q", null, TOKEN, PARENT, "/Users/eng", "eng").environment();

        assertThat(env).containsEntry("BAS_RUN_TOKEN", TOKEN).containsEntry("HOME", "/Users/eng")
                .containsEntry("TMPDIR", "/tmp/x").containsEntry("PATH", "/Users/eng/.local/bin:/usr/bin:/bin:/usr/sbin:/sbin")
                .doesNotContainKeys("BAS_AGENT_API_KEY", "DATABASE_URL", "BAS_ADMIN_PASSWORD");
        assertThat(env.keySet()).allMatch(k -> k.equals("BAS_RUN_TOKEN") || k.equals("HOME") || k.equals("USER")
                || k.equals("LOGNAME") || k.equals("PATH") || k.equals("LANG") || KiroCommand.PASSED_THROUGH.contains(k));
    }

    @Test
    void neverPrintsTheTokenOrTheMessage() {
        var command = KiroCommand.of(KIRO, "bas-web-assistant", "private question", null, TOKEN, PARENT, "/Users/eng", "eng");
        assertThat(command.toString()).doesNotContain(TOKEN).doesNotContain("private question").endsWith("<message>");
    }
}
