package com.basintelligence.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.basintelligence.common.ActivityTracker;
import com.basintelligence.config.BasProperties;

class AuditingToolCallbackTest {

    private final AgentAuditService audit = mock(AgentAuditService.class);
    private final ToolCallback delegate = mock(ToolCallback.class);
    private final BasProperties properties = mock(BasProperties.class);

    private AuditingToolCallback callback() {
        when(properties.idlePause()).thenReturn(Duration.ofMinutes(30));
        when(delegate.getToolDefinition()).thenReturn(
                ToolDefinition.builder().name("createMaintenanceDraft").description("d").inputSchema("{}").build());
        when(audit.start(isNull(), isNull(), isNull(), eq("kiro-agent"), eq("createMaintenanceDraft"), anyString()))
                .thenReturn(42L);
        when(properties.agent()).thenReturn(new BasProperties.Agent(true, Duration.ofMillis(300), Duration.ofSeconds(1), 8000, 60));
        return new AuditingToolCallback(delegate, audit, new ActivityTracker(Clock.systemUTC(), properties),
                new AgentToolPolicy(properties, Clock.systemUTC()), java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    @Test
    void recordsSuccessAndExposesTheCallIdToTheTool() {
        var callback = callback();
        when(delegate.call("{\"a\":1}")).thenAnswer(inv -> "id=" + AgentCallContext.currentToolCall());

        assertThat(callback.call("{\"a\":1}")).isEqualTo("id=42");
        verify(audit).finish(eq(42L), eq(AgentAuditService.Outcome.SUCCESS), isNull(), anyLong());
        assertThat(AgentCallContext.currentToolCall()).isNull();
    }

    @Test
    void recordsFailures() {
        var callback = callback();
        when(delegate.call("{}")).thenThrow(new IllegalArgumentException("unknown machine"));

        assertThatThrownBy(() -> callback.call("{}")).hasMessage("unknown machine");
        verify(audit).finish(eq(42L), eq(AgentAuditService.Outcome.ERROR), eq("unknown machine"), anyLong());
    }

    @Test
    void timesOutSlowTools() {
        var callback = callback();
        when(delegate.call("{}")).thenAnswer(inv -> {
            Thread.sleep(5_000);
            return "late";
        });

        assertThatThrownBy(() -> callback.call("{}")).hasMessageContaining("timed out");
        verify(audit).finish(eq(42L), eq(AgentAuditService.Outcome.ERROR), org.mockito.ArgumentMatchers.contains("timed out"),
                anyLong());
    }

    @Test
    void refusesOversizedArgumentsBeforeRunningTheTool() {
        var callback = callback();
        String huge = "{\"rationale\":\"" + "x".repeat(9000) + "\"}";
        when(audit.start(isNull(), isNull(), isNull(), eq("kiro-agent"), eq("createMaintenanceDraft"), eq(huge))).thenReturn(43L);

        assertThatThrownBy(() -> callback.call(huge)).isInstanceOf(AgentToolPolicy.AgentPolicyViolation.class);
        verify(audit).finish(eq(43L), eq(AgentAuditService.Outcome.DENIED), anyString(), anyLong());
        verify(delegate, org.mockito.Mockito.never()).call(anyString());
    }
}
