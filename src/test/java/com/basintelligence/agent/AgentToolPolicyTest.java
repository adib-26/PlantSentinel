package com.basintelligence.agent;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.basintelligence.config.BasProperties;

class AgentToolPolicyTest {

    private static AgentToolPolicy policy(boolean recordTools, int perMinute) {
        BasProperties props = mock(BasProperties.class);
        when(props.agent()).thenReturn(new BasProperties.Agent(recordTools, Duration.ofSeconds(10), Duration.ofSeconds(30),
                100, perMinute));
        return new AgentToolPolicy(props, Clock.systemUTC());
    }

    @Test
    void onlyAllowlistedToolsMayRun() {
        assertThatCode(() -> policy(true, 60).check("getMachineStatus", "s", "{}")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy(true, 60).check("runShell", "s", "{}"))
                .isInstanceOf(AgentToolPolicy.AgentPolicyViolation.class);
    }

    @Test
    void recordToolsCanBeSwitchedOffWhileReadsStillWork() {
        var p = policy(false, 60);
        assertThatCode(() -> p.check("predictFailureRisk", "s", "{}")).doesNotThrowAnyException();
        assertThatThrownBy(() -> p.check("createMaintenanceDraft", "s", "{}"))
                .isInstanceOf(AgentToolPolicy.AgentPolicyViolation.class).hasMessageContaining("disabled");
    }

    @Test
    void enforcesArgumentSizeAndRateLimit() {
        var p = policy(true, 2);
        assertThatThrownBy(() -> p.check("getMachineStatus", "s", "x".repeat(101))).hasMessageContaining("exceed");
        p.check("getMachineStatus", "s1", "{}");
        p.check("getMachineStatus", "s1", "{}");
        assertThatThrownBy(() -> p.check("getMachineStatus", "s1", "{}")).hasMessageContaining("Rate limit");
        assertThatCode(() -> p.check("getMachineStatus", "s2", "{}")).doesNotThrowAnyException(); // per session
    }

    @Test
    void kiroAssistantRunsCannotCreateDraftsButMayReport() {
        var p = policy(true, 60);
        assertThatThrownBy(() -> p.check("createMaintenanceDraft", "s", "{}", true))
                .isInstanceOf(AgentToolPolicy.AgentPolicyViolation.class).hasMessageContaining("Kiro assistant");
        assertThatCode(() -> p.check("generateMaintenanceReport", "s", "{}", true)).doesNotThrowAnyException();
        assertThatCode(() -> p.check("createMaintenanceDraft", "s", "{}", false)).doesNotThrowAnyException();
    }

    @Test
    void startupRefusesUnlistedOrDangerousTools() {
        var p = policy(true, 60);
        assertThatCode(() -> p.verifyPublished(AgentToolPolicy.TOOLS.keySet())).doesNotThrowAnyException();
        assertThatThrownBy(() -> p.verifyPublished(Set.of("approveRecommendation"))).isInstanceOf(IllegalStateException.class);
    }
}
