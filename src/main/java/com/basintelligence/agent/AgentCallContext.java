package com.basintelligence.agent;

/**
 * Carries the id of the agent tool call (row in {@code agent_tool_audit}) that is currently executing, so a
 * recommendation proposed by Kiro can be linked back to the exact tool call that created it.
 */
public final class AgentCallContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private AgentCallContext() {
    }

    public static Long currentToolCall() {
        return CURRENT.get();
    }

    static void set(Long toolCallId) {
        CURRENT.set(toolCallId);
    }

    static void clear() {
        CURRENT.remove();
    }
}
