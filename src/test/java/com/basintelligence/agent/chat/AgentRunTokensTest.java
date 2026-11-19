package com.basintelligence.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.basintelligence.common.AgentRun;

class AgentRunTokensTest {

    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-18T08:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void aTokenIdentifiesItsRunUntilItIsRevokedOrExpires() {
        var clock = new MovableClock();
        var tokens = new AgentRunTokens(clock);
        var run = new AgentRun("engineer", 7);
        String a = tokens.issue(run, Duration.ofMinutes(6));
        String b = tokens.issue(new AgentRun("engineer2", 8), Duration.ofMinutes(6));

        assertThat(a).startsWith(AgentRun.TOKEN_PREFIX).hasSize(AgentRun.TOKEN_PREFIX.length() + 64).isNotEqualTo(b);
        assertThat(tokens.verify(a)).contains(run);
        assertThat(tokens.verify(a.substring(0, a.length() - 1) + "0")).isEmpty();
        assertThat(tokens.verify("demo-agent-key-change-me")).isEmpty();

        tokens.revoke(a);
        assertThat(tokens.verify(a)).isEmpty();

        clock.now = clock.now.plus(Duration.ofMinutes(6));
        assertThat(tokens.verify(b)).as("expired").isEmpty();
        assertThat(tokens.active()).isZero();
    }

    @Test
    void overlongLinesAreSkippedInsteadOfBuffered() throws Exception {
        var lines = new BoundedLineReader(new StringReader("ok\n" + "x".repeat(50) + "\nlast"), 10);
        assertThat(lines.next()).isEqualTo("ok");
        assertThat(lines.next()).isEqualTo("last");
        assertThat(lines.next()).isNull();
        assertThat(lines.charsRead()).isEqualTo(3 + 51 + 4);
    }
}
